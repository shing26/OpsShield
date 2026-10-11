package com.opspilot.action;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 只读命令策略（ADRs 0017）：一段命令文本是否"读"。
 *
 * <p>网关**不执行**这条命令——它只是决定"这条只读建议能不能上线"。因此判据是
 * 结构性的、fail-closed 的：认不出的形态一律判拒，并把拒绝计数暴露到
 * {@code aiops.guard.action_commands_rejected}（Prometheus 侧，不进 /state 面板契约）。
 * 静默丢弃比误放一条写操作好——后者会直接推翻 ADR-0013 的「只读、零写、非侵入」。
 *
     * <p><b>四层判据</b>（任一层不过即拒）：
     * <ol>
     *   <li><b>工具白名单</b>：第一 token 必须在闭集内。`rm`/`mv`/`chmod`/`taskkill`/`mysql`/
     *       `python -c` 等天然不在表内——这是最主要的一道闸。</li>
     *   <li><b>多态工具的子动词</b>：{@code kubectl}/{@code docker}/{@code redis-cli}/{@code git}
     *       等单二进制多语义工具，按「动词（必要时再看子动词）」收敛到闭集：
     *       {@code kubectl delete}、{@code redis-cli FLUSHALL}、{@code git push} 在此层被拒。</li>
     *   <li><b>外壳/执行型前缀递归校验</b>：{@code sh -c '…'}、{@code ssh host '…'}、
     *       {@code kubectl exec … -- …}、{@code docker exec … -- …} 不许"整体放行"——
     *       外壳里装的负载要用<b>同一套策略</b>再判一遍（深度上限防 {@code sh -c 'sh -c …'} 嵌套炸弹）。
     *       负载判不过，外壳一起拒。</li>
     *   <li><b>旗标与重定向</b>：命令替换（{@code $(…)}/反引号/{@code system(}）与进程替换
     *       （{@code <(…)}/{@code =(…)}）是唯一能把写操作藏进"已核准工具"参数里的通道，一律判拒；
     *       重定向目标只允许 {@code /dev/null}（{@code 2>/dev/null} 无害，{@code > /tmp/x} 是写文件）。</li>
     * </ol>
     *
     * <p><b>已知的保守偏差</b>（刻意接受，均记入 ADRs 0017）：判据是词法级的，不解析 SQL 字符串
     * 字面量（{@code SELECT * FROM t WHERE s='DELETE'} 会被拒），也不识别 PromQL 等查询语
     * （{@code container_memory_working_set_bytes{…}} 无命令形态，整块丢弃），awk 的
     * {@code $(NF)} 字段语法同样撞在第 4 层上；进程替换的合法读用法（{@code diff <(sort a) <(sort b)}）
     * 与 {@code ssh} 的取值旗标闭集外形态（{@code -o}/-v 等）一并判拒。这些都是"少给一条建议"，
     * 不是"给错一条建议"。
     */
public final class ReadOnlyCommandPolicy {

    private ReadOnlyCommandPolicy() {}

    /** 第 3 层递归深度上限：外壳套外壳最多三层，再深判拒（防嵌套炸弹，也防策略自我循环）。 */
    private static final int MAX_DEPTH = 3;

    /** 外壳与远程执行前缀：判据=递归校验负载，而非整体放行/整体拒绝。 */
    private static final Set<String> SHELLS =
            Set.of("sh", "bash", "zsh", "dash", "ash", "powershell", "pwsh", "ssh");

    /**
     * 第 1 层：第一 token 白名单（闭集）。只收"读"是唯一语义的诊断二进制。
     * 有意<b>不收</b>的：解释器与可执行脚本入口（{@code java}/{@code python}/{@code node}/
     * {@code mysql}/{@code psql}——客户端语义随语句走，无法在词法层收敛）、
     * 文件/进程变更器（{@code rm}/{@code mv}/{@code cp}/{@code tee}/{@code chmod}/{@code chown}/
     * {@code kill}/{@code taskkill}/{@code truncate}）、流量捕获（{@code tcpdump} 默认落 pcap 文件）、
     * 落盘型下载（{@code wget} 默认把响应写进当前目录）、执行器 {@code env}
     * （{@code env rm -rf /} 的首词会过任何白名单，2026-10-11 评审批移出），
     * 以及多态但未收敛子动词的网络配置四件套
     * （{@code ip}/{@code ifconfig}/{@code route}/{@code arp} 都能改路由表与网卡）。后两者的
     * 收敛成本高于收益、语料也没用到——真需要时照 {@code VERBS} 的现例补子动词闭集即可。
     */
    private static final Set<String> TOOLS = Set.of(
            // 进程与系统
            "ps", "top", "uptime", "free", "vmstat", "iostat", "mpstat", "pidstat", "sar",
            // 文件与文本
            "ls", "cat", "file", "stat", "head", "tail", "wc", "sort", "uniq", "cut", "tr", "jq",
            "grep", "egrep", "fgrep", "rg",
            // 环境
            "echo", "printf", "date", "printenv", "id", "whoami", "hostname", "uname",
            "dmesg", "lsb_release",
            // 网络探测（只发不收）
            "ping", "traceroute", "tracepath", "ss", "netstat", "dig", "nslookup",
            "host", "curl",
            // 时间同步
            "timedatectl", "chronyc", "ntpq", "ntpstat",
            // JVM 观测
            "jps", "jstack", "jstat", "jinfo", "jmap",
            // 证书
            "keytool", "openssl",
            // 系统日志
            "journalctl"
    );

    /**
     * 第 2 层：多态工具的动词闭集。键=第一 token 之后的<b>动词</b>位置（通常是 argv[1]）。
     * 注意 {@code redis-cli} 的动词前面可能跟连接旗标（{@code -h host}），另行定位（见 {@link #verbIndex}）。
     */
    private static final Map<String, Set<String>> VERBS = Map.of(
            "kubectl", Set.of("get", "logs", "describe", "top", "explain", "api-resources",
                    "version", "cluster-info", "events", "exec"),
            "docker", Set.of("ps", "logs", "inspect", "stats", "top", "images", "version",
                    "info", "port", "exec"),
            // git 刻意不收 branch/tag/remote：-d/-D/add 是元数据写，旗标闭集成本高于收益
            // （语料零使用，2026-10-11 评审批收紧）
            "git", Set.of("status", "log", "ls-files", "show", "diff", "blame", "rev-parse",
                    "check-ignore", "describe", "shortlog", "grep"),
            "systemctl", Set.of("status", "list-units", "list-unit-files", "is-active",
                    "is-enabled", "show", "cat"),
            "service", Set.of("status"),
            "timedatectl", Set.of("status", "show", "list-timezones"),
            "chronyc", Set.of("tracking", "sources", "sourcestats", "clients", "serverstats"),
            "openssl", Set.of("x509", "s_client", "version", "ciphers"),
            "jcmd", Set.of("VM.version", "VM.uptime", "VM.info", "VM.flags", "VM.command_line",
                    "VM.system_properties", "Thread.print", "GC.class_histogram", "GC.heap_info"),
            "redis-cli", Set.of("info", "slowlog", "cluster", "get", "mget", "exists", "ttl",
                    "pttl", "type", "scan", "dbsize", "keys", "hget", "hgetall", "lrange",
                    "smembers", "zrange", "zscore", "config", "memory", "client", "monitor",
                    "object", "command", "latency", "xinfo", "randomkey")
    );

    /** 第 2 层的子动词：复合动词的第二 token 闭集（缺省=该动词不再有子动词约束）。 */
    private static final Map<String, Map<String, Set<String>>> SUBVERBS = Map.of(
            "redis-cli", Map.of(
                    "slowlog", Set.of("get", "len"),
                    "cluster", Set.of("info", "nodes", "slots", "shards"),
                    "config", Set.of("get"),
                    "memory", Set.of("usage", "doctor", "stats"),
                    "client", Set.of("list", "info"),
                    "object", Set.of("encoding", "refcount", "idletime", "freq"),
                    "command", Set.of("count", "docs", "info", "getkeys"),
                    "latency", Set.of("history", "latest", "doctor", "graph"),
                    "xinfo", Set.of("stream", "groups", "consumers")
            )
    );

    /** {@code redis-cli} 取值型旗标：跳过后才是动词（{@code -h host INFO clients}）。 */
    private static final Set<String> REDIS_VALUE_FLAGS = Set.of("-h", "-p", "-n", "-a", "-s", "-u");

    /**
     * {@code ssh} 取值型旗标闭集：跳值后才轮到 host（{@code ssh -i key host cat log}）。
     * 闭集外的旗标一律判拒——{@code -o} 的值是任意配置（RemoteCommand/ProxyCommand 能藏命令）、
     * {@code -L}/-R/-D/-W/-J 是隧道与跳板，词法层收敛不了，fail-closed。旧判据"跳过全部
     * {@code -} 再跳一个 host"会把 {@code ssh -i key host rm -rf /} 的负载整体漏校验
     * （2026-10-11 评审批）。
     */
    private static final Set<String> SSH_VALUE_FLAGS = Set.of("-i", "-p", "-F", "-l", "-b", "-c", "-m");
    private static final Set<String> SSH_REJECT_FLAGS = Set.of("-o", "-L", "-R", "-D", "-W", "-J", "-O");

    /** {@code dmesg} 只读旗标闭集（{@code -c}/{@code -C} 清内核环形缓冲，是写面）。 */
    private static final Set<String> DMESG_FLAGS = Set.of("-T", "-H", "-w", "-k", "-x", "-e",
            "--human", "--kernel", "--userspace", "--follow", "--ctime", "--notime");

    /** {@code keytool} 只读旗标闭集（{@code -genkey}/{-import} 之类是写，不在表内）。 */
    private static final Set<String> KEYTOOL_FLAGS = Set.of("-list", "-printcert");

    /** {@code jmap} 只读旗标闭集（{@code -dump:…} 会把堆写进文件）。 */
    private static final Set<String> JMAP_FLAGS = Set.of("-histo", "-histo:live", "-heap", "-clstats");

    /** {@code find} 的写动作谓词闭集（其余谓词都不落盘，放开）。 */
    private static final Set<String> FIND_WRITE_PREDICATES =
            Set.of("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprintf");

    /**
     * SQL 首关键字白集：语料里 {@code ```sql} 围栏装的是裸 SQL（无 mysql 客户端前缀），
     * 判据只能建在首关键字上。查字典系的只读语句才可能上线。
     */
    private static final Set<String> SQL_READ_VERBS =
            Set.of("SELECT", "SHOW", "EXPLAIN", "DESC", "DESCRIBE", "WITH", "ANALYZE");

    /**
     * SQL 写/副作用关键字：<b>段内任一词命中即整条判拒</b>。刻意覆盖 {@code FOR UPDATE}
     * （行锁）、{@code INTO OUTFILE}（落盘）、{@code SET}/{@code KILL}（会改状态）。
     * 误伤是已知代价：词法层不解析字符串字面量，{@code SELECT * FROM t WHERE op='DELETE'}
     * 会被拒——少给一条建议，好过给错一条。
     */
    private static final Set<String> SQL_WRITE_VERBS = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE", "RENAME",
            "REPLACE", "MERGE", "GRANT", "REVOKE", "KILL", "FLUSH", "LOCK", "UNLOCK", "SET",
            "CALL", "BEGIN", "START", "COMMIT", "ROLLBACK", "SAVEPOINT", "INTO", "LOAD", "COPY",
            "IMPORT", "EXPORT", "OPTIMIZE", "REPAIR", "PURGE", "RESET", "SHUTDOWN", "FOR");

    /** {@code redis-cli} 无值旗标里的只读形态闭集（{@code --latency}/{@code --bigkeys} 等）。 */
    private static final Set<String> REDIS_READONLY_FLAGS =
            Set.of("--latency", "--latency-history", "--bigkeys", "--stat", "--hotkeys",
                    "--intrinsic-latency", "--scan");

    /**
     * 第 4 层：命令替换/进程替换/任意执行入口。{@code <(…)}/{@code >(…)}（bash/zsh 进程替换）与
     * {@code =(…)}（zsh）和 {@code $(…)} 同罪——{@code cat <(rm -rf /)} 的 argv 首词是 cat，
     * 替换体里的命令却会真实执行，不判进程替换就整个漏网（2026-10-11 评审批）。
     */
    private static final List<String> SUBSTITUTION_MARKERS =
            List.of("$(", "`", "<(", ">(", "=(", "system(", "popen(", "exec(");

    /** {@code curl} 的方法改写/载荷/上传旗标——出现即视为非 GET 请求。 */
    private static final Set<String> CURL_MUTATION_FLAGS = Set.of(
            "-d", "--data", "--data-ascii", "--data-binary", "--data-raw", "--data-urlencode",
            "-F", "--form", "-T", "--upload-file", "-X", "--request");

    /**
     * {@code docker exec}/{@code kubectl exec} 的取值型旗标：后一个 token 是旗标值，不是容器名。
     * docker 与 kubectl 的取并集——多认一个只是让"容器名"判定右移，见 {@link #execPayload}。
     */
    private static final Set<String> EXEC_VALUE_FLAGS = Set.of(
            "-e", "--env", "--env-file", "-u", "--user", "-w", "--workdir", "--detach-keys",
            "-c", "--container", "-f", "--filename", "--pod-running-timeout");

    /** {@code docker exec}/{@code kubectl exec} 的无值旗标（同上，并集）。 */
    private static final Set<String> EXEC_FLAGS = Set.of(
            "-d", "--detach", "-i", "--interactive", "--stdin", "-t", "--tty",
            "-l", "--privileged", "-q", "--quiet");

    /** 上表无值短旗标的可组合字符集：{@code -it}/{@code -ti}/{@code -dit} 是 {@code exec} 最常见形态。 */
    private static final String EXEC_SHORT_FLAGS = "idltq";

    // ────────────────────────────── 入口 ──────────────────────────────

    /** 判一条（可能是 {@code ;} / {@code |} / {@code &&} 串起来的）命令是否只读。 */
    public static boolean isReadOnly(String command) {
        return isReadOnly(command, 0);
    }

    private static boolean isReadOnly(String command, int depth) {
        if (command == null || command.isBlank() || depth > MAX_DEPTH) {
            return false;
        }
        for (String segment : segments(command)) {
            if (segment.isBlank()) {
                continue;   // 尾部分号/管道产生的空段（`SELECT …;` 最常见）不携带命令
            }
            if (!segmentReadOnly(segment, depth)) {
                return false;
            }
        }
        return true;
    }

    // ────────────────────────────── 段 ──────────────────────────────

    /**
     * 引号感知切段：{@code ;}、{@code |}、{@code ||}、{@code &&}、{@code &} 都算顺序执行边界，
     * 每一段独立判。引号内的分隔符不算（{@code grep "a;b" f} 只有一段）。
     */
    static List<String> segments(String command) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (quote != 0) {
                cur.append(ch);
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                cur.append(ch);
                continue;
            }
            if (ch == ';' || ch == '|') {
                if (ch == '|' && i + 1 < command.length() && command.charAt(i + 1) == ch) {
                    i++;   // ||：吞掉第二个字符
                }
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            if (ch == '&') {
                if (i + 1 < command.length() && command.charAt(i + 1) == ch) {
                    i++;   // &&：逻辑与，吞掉第二个字符
                } else if ((i > 0 && command.charAt(i - 1) == '>')
                        || (i + 1 < command.length() && command.charAt(i + 1) == '>')) {
                    // >&2 / 2>&1 / &>file 是重定向语法而非执行边界：不当分隔符
                    cur.append(ch);
                    continue;
                }
                out.add(cur.toString());   // 裸 & = 后台执行符
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    private static boolean segmentReadOnly(String segment, int depth) {
        if (!redirectsReadOnly(segment) || !noCommandSubstitution(segment)) {
            return false;
        }
        List<String> args = argv(segment);
        if (args.isEmpty()) {
            return false;
        }
        // SQL 裸语句：首关键字在白集内才走 SQL 判据（写关键字命中即整条拒）
        String head = args.get(0).toUpperCase(Locale.ROOT);
        if (SQL_READ_VERBS.contains(head)) {
            return sqlReadOnly(args);
        }
        String tool = args.get(0).toLowerCase(Locale.ROOT);
        if (!TOOLS.contains(tool) && !SHELLS.contains(tool) && !VERBS.containsKey(tool)) {
            return false;
        }
        return toolReadOnly(tool, args, depth);
    }

    private static boolean toolReadOnly(String tool, List<String> args, int depth) {
        // 外壳：定位负载并递归校验（sh -c '…' / ssh host '…' / sh mqadmin …）
        if (SHELLS.contains(tool)) {
            return payloadReadOnly(shellPayload(tool, args), depth);
        }
        // 容器内执行：kubectl exec … -- … / docker exec … -- …
        if ((tool.equals("kubectl") || tool.equals("docker")) && args.size() > 1
                && args.get(1).toLowerCase(Locale.ROOT).equals("exec")) {
            return payloadReadOnly(execPayload(args), depth);
        }
        // 旗标型只读工具（无动词位，只校验旗标本身）
        if (tool.equals("keytool")) {
            return args.size() > 1 && KEYTOOL_FLAGS.contains(args.get(1));
        }
        if (tool.equals("jmap")) {
            return args.size() > 1 && JMAP_FLAGS.contains(args.get(1));
        }
        if (tool.equals("find")) {
            return args.stream().noneMatch(FIND_WRITE_PREDICATES::contains);
        }
        if (tool.equals("sed")) {
            // 原地改写 = 写文件（-i / --in-place / -i.bak）
            return args.stream().noneMatch(a -> a.equals("-i") || a.equals("--in-place")
                    || a.startsWith("-i.") || a.startsWith("--in-place="));
        }
        if (tool.equals("curl")) {
            return curlReadOnly(args);
        }
        // 纯读工具里的旗标级写面（2026-10-11 评审批收紧：读是这些工具的常态语义，
        // 但个别旗标落盘/改状态——闭集外判拒，不逐个枚举全部旗标）
        if (tool.equals("sort")) {
            // --output=/tmp/x 是单 token：startsWith 才兜得住
            return args.stream().noneMatch(a -> a.equals("-o") || a.startsWith("--output"));
        }
        if (tool.equals("hostname")) {
            return args.size() == 1;   // 带参数=改主机名
        }
        if (tool.equals("dmesg")) {
            return args.size() == 1 || args.stream().skip(1).allMatch(DMESG_FLAGS::contains);
        }
        if (tool.equals("journalctl")) {
            // --vacuum-* 删归档日志；--rotate/--sync 触发日志轮转与刷写——都是状态变更
            return args.stream().noneMatch(a -> a.startsWith("--vacuum")
                    || a.equals("--rotate") || a.equals("--sync"));
        }
        if (tool.equals("date")) {
            return args.stream().noneMatch(a -> a.equals("-s") || a.startsWith("--set"));
        }
        // 动词型工具
        Set<String> verbs = VERBS.get(tool);
        if (verbs == null) {
            return true;   // 纯读工具：进得了 TOOLS 白名单即无语义歧义，也无旗标级写形态
        }
        if (args.size() < 2) {
            return false;   // 动词型工具没带动词（裸 kubectl / 裸 git）不猜
        }
        int vi = verbIndex(tool, args);
        if (vi < 0) {
            // 只剩旗标（redis-cli --latency -h host 这类"旗标模式"）：每个旗标都要在只读闭集里
            return flagOnlyReadOnly(tool, args);
        }
        if (vi >= args.size()) {
            return false;
        }
        String verb = args.get(vi).toLowerCase(Locale.ROOT);
        if (!verbs.contains(verb)) {
            return false;
        }
        Map<String, Set<String>> subs = SUBVERBS.get(tool);
        if (subs != null && subs.containsKey(verb)) {
            Set<String> allowed = subs.get(verb);
            if (vi + 1 >= args.size() || !allowed.contains(args.get(vi + 1).toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        if (tool.equals("openssl") && verb.equals("x509")
                && args.stream().anyMatch(a -> a.equals("-out") || a.startsWith("-out="))) {
            return false;   // -out 把转换结果写盘（语料现役的 -noout 只读形态不受影响）
        }
        return true;
    }

    /** 动词在 argv 里的下标：{@code redis-cli} 要跳过连接旗标，其余工具恒为 1（argv[0] 是工具）。 */
    private static int verbIndex(String tool, List<String> args) {
        if (!tool.equals("redis-cli")) {
            return 1;
        }
        for (int i = 1; i < args.size(); i++) {
            String a = args.get(i);
            if (REDIS_VALUE_FLAGS.contains(a)) {
                i++;   // 跳旗标值
                continue;
            }
            if (a.startsWith("-")) {
                continue;   // --latency / --bigkeys 等无值旗标
            }
            return i;
        }
        return -1;   // 旗标模式：没有动词位
    }

    /** 旗标模式判据：取值型连接旗标（host/port/db/auth）放过，其余旗标必须在只读闭集内。 */
    private static boolean flagOnlyReadOnly(String tool, List<String> args) {
        if (!tool.equals("redis-cli")) {
            return false;   // 只有 redis-cli 存在"旗标模式"（--latency/--bigkeys 无子命令）
        }
        for (int i = 1; i < args.size(); i++) {
            String a = args.get(i);
            if (REDIS_VALUE_FLAGS.contains(a)) {
                i++;
                continue;
            }
            if (!REDIS_READONLY_FLAGS.contains(a)) {
                return false;
            }
        }
        return true;
    }

    /** SQL 判据：段内任一词命中写/副作用关键字即整条拒（词法层，宁可误伤）。 */
    private static boolean sqlReadOnly(List<String> args) {
        for (String a : args) {
            if (SQL_WRITE_VERBS.contains(a.toUpperCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static boolean curlReadOnly(List<String> args) {
        if (args.stream().anyMatch(CURL_MUTATION_FLAGS::contains)) {
            return false;   // 带请求体/上传/显式方法改写：不认作 GET
        }
        for (int i = 1; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("-o") || a.equals("--output") || a.equals("-O")) {
                String target = i + 1 < args.size() ? args.get(i + 1) : "";
                if (!target.startsWith("/dev/null")) {
                    return false;   // -o file 是写盘
                }
            }
        }
        return true;
    }

    // ────────────────────────────── 第 3 层：负载 ──────────────────────────────

    private static boolean payloadReadOnly(List<String> payload, int depth) {
        return !payload.isEmpty() && isReadOnly(String.join(" ", payload), depth + 1);
    }

    /** 外壳负载定位：先 {@code -c}/{@code --command}，否则 {@code ssh host '…'} 取 host 之后的全部，
     *  再否则把工具名之后的全部当负载（{@code sh mqadmin clusterList}）。 */
    private static List<String> shellPayload(String tool, List<String> args) {
        for (int i = 1; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("-c") || a.equals("--command") || a.equals("-Command")) {
                return args.subList(i + 1, args.size());
            }
        }
        if (tool.equals("ssh")) {
            int i = 1;
            while (i < args.size() && args.get(i).startsWith("-")) {
                if (SSH_REJECT_FLAGS.contains(args.get(i))) {
                    return List.of();          // 隧道/代理/任意配置：fail-closed
                }
                if (SSH_VALUE_FLAGS.contains(args.get(i))) {
                    if (i + 1 >= args.size()) {
                        return List.of();      // 取值旗标缺值
                    }
                    i += 2;                    // 旗标 + 旗标值
                } else {
                    return List.of();          // 闭集外旗标：不猜
                }
            }
            if (i >= args.size()) {
                return List.of();              // 连 host 都没有
            }
            return args.subList(i + 1, args.size());   // host 之后的负载全量递归校验
        }
        return args.subList(1, args.size());
    }

    /**
     * 容器执行负载：有 {@code --} 取其后全部；否则按 {@code [OPTIONS] CONTAINER COMMAND [ARG…]}
     * 的真实语法跳过旗标与容器名，返回 COMMAND 起的 token。<b>没有 {@code --} 也照样定位到了
     * 负载</b>——{@code docker exec -it ctr cat f} 是最常见的形态，判拒会让契约少一半可用建议。
     *
     * <p>定位失败（只剩容器名没命令）就返回空，由调用方判拒——不猜。认不出的旗标会落进
     * "容器名"格里，于是负载从它身后的 token 起算：那只会改变"哪些 token 被校验"，
     * 漏不到写操作上（要漏掉一个写，必须让写工具名本身被当成容器名，而那与 docker/kubectl
     * 自己的语法矛盾）。
     */
    static List<String> execPayload(List<String> args) {
        for (int i = 2; i < args.size(); i++) {
            if (args.get(i).equals("--")) {
                return args.subList(i + 1, args.size());
            }
        }
        int i = 2;
        while (i < args.size()) {
            String a = args.get(i);
            if (EXEC_VALUE_FLAGS.contains(a)) {
                i += 2;       // 旗标 + 旗标值
                continue;
            }
            if (EXEC_FLAGS.contains(a) || combinedShortFlags(a)) {
                i++;         // 无值旗标 / -it 组合短旗标
                continue;
            }
            break;           // 容器名 / POD 名
        }
        return i + 1 < args.size() ? args.subList(i + 1, args.size()) : List.of();
    }

    /** {@code -it} / {@code -ti} / {@code -dit} 这类组合短旗标：每个字符都必须是已知无值短旗标。 */
    private static boolean combinedShortFlags(String a) {
        if (a.length() < 3 || a.charAt(0) != '-' || a.charAt(1) == '-') {
            return false;
        }
        for (int i = 1; i < a.length(); i++) {
            if (EXEC_SHORT_FLAGS.indexOf(a.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    // ────────────────────────────── 第 4 层：重定向与替换 ──────────────────────────────

    /** 重定向目标只允许 {@code /dev/null}（{@code 2>/dev/null} 无害）与 {@code &2} 之类的 fd 重定向。 */
    private static boolean redirectsReadOnly(String segment) {
        String scan = maskPlaceholders(segment);   // <pid>/<orderId> 是占位符，不是重定向
        char quote = 0;
        for (int i = 0; i < scan.length(); i++) {
            char ch = scan.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                continue;
            }
            if (ch != '>') {
                continue;
            }
            int j = i + 1;
            while (j < scan.length() && scan.charAt(j) == '>') {
                j++;   // >>
            }
            int s = j;
            while (s < scan.length() && Character.isWhitespace(scan.charAt(s))) {
                s++;
            }
            int e = s;
            while (e < scan.length() && !Character.isWhitespace(scan.charAt(e))) {
                e++;
            }
            String target = scan.substring(s, e);
            if (!target.startsWith("/dev/null") && !target.startsWith("/dev/stdout")
                    && !target.startsWith("/dev/stderr") && !target.startsWith("&")) {
                return false;
            }
            i = e - 1;
        }
        return true;
    }

    /**
     * 把 {@code <pid>}/{@code <pod>}/{@code <orderId>} 这类占位符掩成等长填充，免得重定向扫描
     * 把尖括号当成 {@code <}/@code >}。判据（2026-10-11 评审批收紧）：整个空白分隔段
     * <b>恰好是</b> {@code <名>}——名字只含字母/数字/下划线/点/横杠，且 {@code >} 后紧跟
     * 空白或串尾。{@code <in>out} 这种"{@code >} 后还贴着字"的形态不是占位符：那个
     * {@code >} 必须留给重定向扫描（bash 语义下它真会把 out 落盘）；跨空白的尖括号
     * 同样不当占位符（很可能是两个不同的重定向）。真输入重定向（{@code cat < f}）
     * {@code <} 后有空白，不会被掩掉。
     */
    private static String maskPlaceholders(String segment) {
        StringBuilder sb = new StringBuilder(segment.length());
        int i = 0;
        while (i < segment.length()) {
            int close = purePlaceholderEnd(segment, i);
            if (close > i) {
                sb.append("P".repeat(close - i + 1));
                i = close + 1;
                continue;
            }
            sb.append(segment.charAt(i));
            i++;
        }
        return sb.toString();
    }

    /** {@code start} 处的 {@code <} 是否起一个纯占位符段，是则返回其 {@code >} 的下标，否则 -1。 */
    private static int purePlaceholderEnd(String segment, int start) {
        if (start + 1 >= segment.length() || segment.charAt(start) != '<') {
            return -1;
        }
        int k = start + 1;
        while (k < segment.length()) {
            char c = segment.charAt(k);
            if (c == '>') {
                break;
            }
            if (Character.isWhitespace(c) || !isPlaceholderNameChar(c)) {
                return -1;
            }
            k++;
        }
        if (k >= segment.length() || k == start + 1) {
            return -1;   // 没配上 >，或空名字
        }
        if (k + 1 < segment.length() && !Character.isWhitespace(segment.charAt(k + 1))) {
            return -1;   // > 后贴着字符：<in>out 形态，不掩
        }
        return k;
    }

    /** 占位符名的字符集：字母（含 CJK）、数字、下划线、点、横杠。 */
    private static boolean isPlaceholderNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
    }

    /** 命令替换是唯一能把任意写操作藏进"已核准工具"参数里的通道（{@code ls $(rm -rf /)}）。 */
    private static boolean noCommandSubstitution(String segment) {
        return SUBSTITUTION_MARKERS.stream().noneMatch(segment::contains);
    }

    // ────────────────────────────── 分词 ──────────────────────────────

    /** 引号感知分词：返回剥掉引号的 argv（迷你 shell，够策略判断用，不做变量展开）。 */
    static List<String> argv(String segment) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < segment.length(); i++) {
            char ch = segment.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else {
                    cur.append(ch);
                }
                started = true;
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                started = true;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (started) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    started = false;
                }
                continue;
            }
            cur.append(ch);
            started = true;
        }
        if (started) {
            out.add(cur.toString());
        }
        return out;
    }
}
