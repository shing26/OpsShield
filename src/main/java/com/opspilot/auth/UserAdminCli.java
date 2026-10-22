package com.opspilot.auth;

import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.HexFormat;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 用户管理 CLI（P2）：直连 H2 文件库。
 * 密码只从 --password-env 指定的环境变量或交互 stdin 读取，绝不进 argv（避免 ps 泄露）。
 * 不进 Spring 容器、无 HTTP 面：{@code java -cp app.jar com.opspilot.auth.UserAdminCli <cmd> ...}。
 * 包装见 scripts/user_admin.sh。
 *
 * <p><b>G0（2026-10-22 运行可信）三处收紧</b>：
 * <ol>
 *   <li>默认库 URL **去掉 AUTO_SERVER**：它要求网关与 CLI 同时打开同一 H2 文件，而
 *       AUTO_SERVER 走 Windows 防火墙常被拦、在 bind mount 上锁不可靠（事故 51103 的同类风险）。
 *       "CLI 与网关并存"现在由 scripts/user_admin.sh 的守卫改成**默认拒绝**，不再是默认路径；
 *       确需 AUTO_SERVER 的部署在 H2_DB_URL 里显式写出来（那是知情选择）。</li>
 *   <li>restore/disable/rotate 追加 {@code logs/audit.jsonl} 的 {@code ev=admin} 行：
 *       这几个动作此前只留在终端历史里，而终端历史不是审计。schema 与网关
 *       {@code AuditService.logAdmin} 对齐（ts/ev/action/outcome/sub/detail），
 *       restore 的 detail 带源 zip 的 sha256——事后能确认"从哪一份产物恢复的"。</li>
 *   <li>新增 {@code list --to-db NAME}（打开演练库，供 scripts/restore_drill.sh 核对账号与 token_ver）。</li>
 * </ol>
 */
public final class UserAdminCli {

    /** 默认库（无 AUTO_SERVER）：与 application.yml 的默认一致，去掉的是"隐式跨进程共存"这个默认值。 */
    private static final String DEFAULT_DB_URL = "jdbc:h2:file:./data/users";

    /** 管理动作留痕文件：与网关 logback 的 opspilot.audit appender 同一物理文件。 */
    private static final String AUDIT_FILE = "logs/audit.jsonl";

    private UserAdminCli() {}

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) { usage(); System.exit(2); }
        String url = env("H2_DB_URL", DEFAULT_DB_URL);
        String dbUser = env("H2_DB_USER", "opspilot");
        String dbPass = System.getenv().getOrDefault("H2_DB_PASSWORD", "");
        String cmd = argv[0];
        // H2 2.x 移除了 RESTORE SQL：走官方 Restore 工具（纯文件操作，目标库必须不存在=天然防误覆盖）
        if (cmd.equals("restore")) {
            String from = require(argv, "--from");
            String toDb = opt(argv, "--to-db");
            audited("restore", () -> {
                String path = H2BackupPath.safe(from);   // 先过白名单再算摘要：非法路径的错误要可读
                String digest = sha256(path);
                restore(path, toDb);
                return "from=" + from + " sha256=" + digest + (toDb == null ? "" : " to_db=" + toDb);
            });
            return;
        }
        // 演练库读取：restore_drill.sh 恢复到 restore-drill 后必须能连上去核对账号数/token_ver。
        // 放在 open 之前改 URL，保证后续所有命令只碰目标库（绝不悄悄连主库）。
        if (cmd.equals("list") && opt(argv, "--to-db") != null) {
            url = urlForDb(url, opt(argv, "--to-db"));
        }
        try (Connection c = open(url, dbUser, dbPass)) {
            ensureTable(c);
            switch (cmd) {
                case "add" -> add(c, require(argv, "--user"), require(argv, "--tenant"),
                        Integer.parseInt(require(argv, "--level")), require(argv, "--role"), readPw(argv));
                case "passwd" -> passwd(c, require(argv, "--user"), readPw(argv));
                case "role" -> role(c, require(argv, "--user"), require(argv, "--role"));
                case "disable" -> {
                    String sub = require(argv, "--user");
                    audited("disable", () -> {
                        flag(c, "disable", sub, true);
                        return "target=" + sub + " disabled=true";
                    });
                }
                case "enable" -> flag(c, "enable", require(argv, "--user"), false);
                case "rotate" -> {
                    String sub = require(argv, "--user");
                    audited("rotate", () -> {
                        rotate(c, sub);
                        return "target=" + sub + " token_ver=bumped";
                    });
                }
                case "backup" -> backup(c, require(argv, "--to"));
                case "list" -> list(c);
                default -> { usage(); System.exit(2); }
            }
        }
    }

    /**
     * 单路连接：嵌入式直连。
     * G0 起**不再隐式回退 AUTO_SERVER**——旧双路（嵌入式失败→AUTO_SERVER）让"CLI 与网关并存"
     * 成为默认能力，而那条路在 Windows 防火墙下常被拦、在 bind mount 上锁不可靠。
     * 现在并存由脚本守卫默认拒绝；真需要时在 H2_DB_URL 显式声明 AUTO_SERVER。
     */
    private static Connection open(String url, String user, String pass) {
        try {
            return DriverManager.getConnection(url, user, pass);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "无法连接 H2（" + url + "）：网关可能正持有账号库文件锁。"
                    + "请先停网关再重试；网关在跑时备份走 HTTP /api/v1/admin/backup（详见 OPS.md §7/§8）",
                    e);
        }
    }

    /** 在线热备份（H2 BACKUP 是事务一致的，取代危险的 cp 热拷）。 */
    private static void backup(Connection c, String to) throws Exception {
        try (var st = c.createStatement()) {
            st.execute("BACKUP TO '" + safeZipPath(to) + "'");
        }
        System.out.println("backup written: " + to);
    }

    /** 从 zip 恢复（Restore 工具要求目标库不存在，防误覆盖）；--to-db 支持恢复到演练库。 */
    private static void restore(String from, String toDb) throws Exception {
        String path = H2BackupPath.safe(from);
        String file = env("H2_DB_URL", DEFAULT_DB_URL)
                .replaceFirst("^jdbc:h2:file:", "").split(";")[0];
        java.io.File db = new java.io.File(file).getAbsoluteFile();
        String dir = db.getParent();
        String target = toDb != null ? toDb.replaceAll("[^\\w.\\-]", "") : db.getName();
        if (new java.io.File(dir, target + ".mv.db").exists()) {
            throw new IllegalStateException("目标库已存在: " + dir + "/" + target + ".mv.db —— "
                    + "恢复请先停网关并移走旧库（mv users.mv.db users.broken），演练库则换 --to-db 名或删掉");
        }
        org.h2.tools.Restore.execute(path, dir, target);
        System.out.println("restored " + path + " -> " + dir + "/" + target + ".mv.db");
    }

    /** SQL 字面量注入防线：委托与网关共用的白名单校验（H2BackupPath）。 */
    private static String safeZipPath(String raw) {
        return H2BackupPath.safe(raw);
    }

    /** 把主库 URL 的文件名换成演练库名（--to-db）；参数（AUTO_SERVER 等）一律丢弃，演练库只做嵌入式读。 */
    private static String urlForDb(String url, String name) {
        if (!name.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("--to-db 仅允许字母数字/_.-，长度≤64: " + name);
        }
        String rest = url.replaceFirst("^jdbc:h2:", "");
        if (!rest.startsWith("file:")) {
            throw new IllegalArgumentException("--to-db 仅支持 file: 形式的 H2 URL: " + url);
        }
        String pathPart = rest.substring("file:".length());
        int semi = pathPart.indexOf(';');
        if (semi >= 0) pathPart = pathPart.substring(0, semi);
        int slash = Math.max(pathPart.lastIndexOf('/'), pathPart.lastIndexOf('\\'));
        String dir = slash >= 0 ? pathPart.substring(0, slash + 1) : "";
        return "jdbc:h2:file:" + dir + name;
    }

    /**
     * CLI 独立打开数据库时的兜底建表（G1 起 schema 的**事实源**是 Flyway V1，不是本方法）。
     *
     * <p>为什么还留：CLI 走 {@code SpringApplication.run} 时 Flyway 会先迁移，本方法是 no-op；
     * 但 restore 到演练库等路径可能在迁移之前/之外拿到连接，表不在时给一条可读的建表兜底，
     * 比让调用方看到 "Table USERS not found" 更接近运维现场可执行的动作。DDL 必须与 V1 同步。
     */
    private static void ensureTable(Connection c) throws Exception {
        try (var st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "sub VARCHAR(64) PRIMARY KEY, pass_bcrypt VARCHAR(100) NOT NULL,"
                    + " tenant VARCHAR(64) NOT NULL, auth_level INT NOT NULL,"
                    + " CHECK (auth_level BETWEEN 1 AND 9), role VARCHAR(32) NOT NULL,"
                    + " disabled BOOLEAN NOT NULL DEFAULT FALSE, token_ver INT NOT NULL DEFAULT 1,"
                    + " updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    private static void add(Connection c, String sub, String tenant, int level, String role, String pw)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (sub, pass_bcrypt, tenant, auth_level, role) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, sub);
            ps.setString(2, new BCryptPasswordEncoder(10).encode(pw));
            ps.setString(3, tenant);
            ps.setInt(4, level);
            ps.setString(5, role);
            ps.executeUpdate();
        }
        System.out.println("added user=" + sub + " tenant=" + tenant + " level=" + level);
    }

    private static void passwd(Connection c, String sub, String pw) throws Exception {
        // 改密同时 bump token_ver：旧 token 全失效
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET pass_bcrypt = ?, token_ver = token_ver + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE sub = ?")) {
            ps.setString(1, new BCryptPasswordEncoder(10).encode(pw));
            ps.setString(2, sub);
            if (ps.executeUpdate() == 0) throw new IllegalStateException("no such user: " + sub);
        }
        System.out.println("password rotated + tokens invalidated for " + sub);
    }

    /** 变更角色（信任域维度，QA P0-2）：如 sre-full → platform。role 不进 token、每请求查 DB，
     *  故即时生效且无需 bump token_ver（区别于 passwd/disable 的吊销语义）。 */
    private static void role(Connection c, String sub, String role) throws Exception {
        if (!role.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("role 仅允许字母数字/_/-，长度≤32");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET role = ?, updated_at = CURRENT_TIMESTAMP WHERE sub = ?")) {
            ps.setString(1, role);
            ps.setString(2, sub);
            if (ps.executeUpdate() == 0) throw new IllegalStateException("no such user: " + sub);
        }
        System.out.println("role=" + role + " for " + sub + "（即时生效，不吊销 token）");
    }

    private static void flag(Connection c, String cmd, String sub, boolean disabled) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET disabled = ?, token_ver = token_ver + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE sub = ?")) {
            ps.setBoolean(1, disabled);
            ps.setString(2, sub);
            if (ps.executeUpdate() == 0) throw new IllegalStateException("no such user: " + sub);
        }
        System.out.println(cmd + " ok: " + sub);
    }

    private static void rotate(Connection c, String sub) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET token_ver = token_ver + 1, updated_at = CURRENT_TIMESTAMP WHERE sub = ?")) {
            ps.setString(1, sub);
            if (ps.executeUpdate() == 0) throw new IllegalStateException("no such user: " + sub);
        }
        System.out.println("token_ver bumped (all tokens revoked) for " + sub);
    }

    private static void list(Connection c) throws Exception {
        try (var st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT sub, tenant, auth_level, role, disabled, token_ver FROM users ORDER BY sub")) {
            while (rs.next()) {
                System.out.printf("%-16s tenant=%-16s level=%d role=%-8s disabled=%-5s ver=%d%n",
                        rs.getString("sub"), rs.getString("tenant"), rs.getInt("auth_level"),
                        rs.getString("role"), rs.getBoolean("disabled"), rs.getInt("token_ver"));
            }
        }
    }

    // ---------------- 审计与工具 ----------------

    @FunctionalInterface
    private interface AdminAction {
        String run() throws Exception;
    }

    /**
     * 管理动作留痕（ev=admin，与网关 {@code AuditService.logAdmin} 同 schema）。
     * 为什么 CLI 也要写：restore/disable/rotate 是最需要"谁在何时做了什么"的动作，
     * 而它们此前只留在终端历史里——终端历史不是审计，换个人接手就断线。
     * sub 记 **OS 账号（cli:&lt;user&gt;）**而不是伪造一个 DB 主体：CLI 没有 JWT，
     * 编造 sub 会让审计面把"本机操作者"误读成某个被授权用户；被操作账号在 detail 里。
     */
    private static void audited(String action, AdminAction body) throws Exception {
        try {
            String detail = body.run();
            audit(action, "ok", detail);
        } catch (Exception e) {
            // 失败也要留痕：审计 only-on-success 正是"最需要证据的时刻是黑洞"的老问题
            audit(action, "failed", e.getMessage());
            throw e;
        }
    }

    private static void audit(String action, String outcome, String detail) {
        String line = "{\"ts\":" + System.currentTimeMillis()
                + ",\"ev\":\"admin\",\"surface\":\"cli\""
                + ",\"action\":" + json(action)
                + ",\"outcome\":" + json(outcome)
                + ",\"sub\":" + json("cli:" + System.getProperty("user.name", "unknown"))
                + (detail == null ? "" : ",\"detail\":" + json(trunc(detail, 200)))
                + "}\n";
        try {
            Path p = Path.of(AUDIT_FILE);
            Path parent = p.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(p, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // 与 AuditService.writeEvent 同纪律：审计失败不阻断管理动作，但必须留痕可排障
            System.err.println("warn: 审计写入失败（动作已执行）: " + e);
        }
    }

    /** 最小 JSON 字符串转义：CLI 不进 Spring，不为一行审计拉 Jackson 依赖。 */
    private static String json(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha256(String file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(Path.of(file))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** 密码来源：--password-env VAR 读环境变量；否则交互式 stdin。绝不从 argv 取明文。 */
    private static String readPw(String[] argv) {
        String envName = opt(argv, "--password-env");
        if (envName != null) {
            String v = System.getenv(envName);
            if (v == null || v.isBlank()) throw new IllegalStateException("env " + envName + " 未设置");
            return v;
        }
        Console con = System.console();
        if (con == null) throw new IllegalStateException("非交互环境请用 --password-env 指定口令环境变量");
        char[] p = con.readPassword("password: ");
        return new String(p);
    }

    private static String require(String[] argv, String name) {
        String v = opt(argv, name);
        if (v == null) throw new IllegalArgumentException("缺少参数 " + name);
        return v;
    }

    private static String opt(String[] argv, String name) {
        for (int i = 0; i < argv.length - 1; i++) {
            if (name.equals(argv[i])) return argv[i + 1];
        }
        return null;
    }

    private static String env(String k, String def) {
        return System.getenv().getOrDefault(k, def);
    }

    private static void usage() {
        System.err.println(String.join("\n", Arrays.asList(
                "用法: UserAdminCli <cmd> [opts]",
                "  add      --user U --tenant T --level N --role R [--password-env VAR]",
                "  passwd   --user U [--password-env VAR]   (同时吊销旧 token)",
                "  role     --user U --role R               (信任域变更，如 platform；即时生效不吊销)",
                "  disable  --user U | enable --user U",
                "  rotate   --user U                        (仅吊销 token，不动口令)",
                "  backup   --to backup/users-YYYYMMDD.zip  (在线热备份，H2 BACKUP)",
                "  restore  --from <zip> [--to-db NAME]     (目标库须不存在；恢复前停网关并移走旧库)",
                "  list     [--to-db NAME]                  (列账号；--to-db 打开演练库)",
                "  restore/disable/rotate 追加 logs/audit.jsonl 的 ev=admin 行")));
    }
}
