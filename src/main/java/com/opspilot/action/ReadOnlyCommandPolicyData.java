package com.opspilot.action;

import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * L0 只读命令策略白名单的**内容**（ADR-0029 第二个切面）：classpath 固定路径
 * {@value #RESOURCE_PATH} 是工具 / 动词 / 子动词三张成员关系表的唯一事实源；四层判定机制、
 * 注入/链式门、逐工具旗标/尾部校验、SQL 判据仍留在 {@link ReadOnlyCommandPolicy}。
 *
 * <p><b>strict / fail-closed</b>：未知 {@code schema_version}、未知顶层/动词/子动词键、未知工具、
 * 动词家族形状不符、重复项（含重复 JSON 对象键）、大写工具/通配/正则/元字符/空白、空集、类型
 * 错误、文件缺失或不可读——一律抛 {@link IllegalStateException}，不静默忽略、不回落默认值。
 * 只从 classpath 读这一个固定路径：没有环境变量、HTTP 上传、按租户覆盖或热加载面。
 *
 * <p>注册为 Spring 组件是为了让非法数据在**启动期**失败，而不是等到第一次只读建议才失败；
 * 判定方走静态 {@link #load()}，不依赖注入。
 *
 * <p><b>为什么白名单是这些而不是更多</b>（原 {@code ReadOnlyCommandPolicy.TOOLS} 上方的安全理由，
 * 内容外置后随表移动至此）：第 1 层只收"读"是唯一语义的诊断二进制。有意<b>不收</b>的：
 * 解释器与可执行脚本入口（{@code java}/{@code python}/{@code node}/{@code mysql}/{@code psql}
 * ——客户端语义随语句走，无法在词法层收敛）、文件/进程变更器（{@code rm}/{@code mv}/{@code cp}/
 * {@code tee}/{@code chmod}/{@code chown}/{@code kill}/{@code taskkill}/{@code truncate}）、
 * 流量捕获（{@code tcpdump} 默认落 pcap 文件）、落盘型下载（{@code wget} 默认把响应写进当前目录）、
 * 执行器 {@code env}（{@code env rm -rf /} 的首词会过任何白名单，2026-10-11 评审批移出），
 * 以及多态但未收敛子动词的网络配置四件套（{@code ip}/{@code ifconfig}/{@code route}/{@code arp}
 * 都能改路由表与网卡）。后两者的收敛成本高于收益、语料也没用到——真需要时照 {@code VERBS} 的
 * 现例补子动词闭集即可。外壳 {@code sh}/{@code bash}/{@code ssh} 等是递归校验的**结构性入口**，
 * 不在工具白名单内；{@code find}/{@code sed} 当前不可达，也不得借数据"救活"。
 */
@Component
public final class ReadOnlyCommandPolicyData {

    /** 固定资源路径（相对 classpath 根）；这是本类唯一的读取入口。 */
    static final String RESOURCE_PATH = "policy/readonly-command-policy.json";

    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** 工具：小写字母数字开头，余下允许点/横杠（{@code redis-cli}、{@code jps} 等）。 */
    private static final Pattern TOOL_TOKEN = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    /** 动词：允许大写（大写动词数据可与闭集逐字匹配），但加载器不做大小写归一。 */
    private static final Pattern VERB_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /**
     * 已知工具宇宙（62 项 = 56 个纯读工具 + 6 个 verbs 独有工具）。<b>代码字面量，不从数据派生</b>：
     * 否则"未知工具"判据退化为同义反复。{@code sh}/{@code bash}/{@code ssh}/{@code find}/
     * {@code sed} 等机制入口刻意不在表内——数据列出即启动失败。
     */
    private static final Set<String> KNOWN_TOOLS = Set.of(
            // 56 个纯读工具
            "ps", "top", "uptime", "free", "vmstat", "iostat", "mpstat", "pidstat", "sar",
            "ls", "cat", "file", "stat", "head", "tail", "wc", "sort", "uniq", "cut", "tr", "jq",
            "grep", "egrep", "fgrep", "rg",
            "echo", "printf", "date", "printenv", "id", "whoami", "hostname", "uname",
            "dmesg", "lsb_release",
            "ping", "traceroute", "tracepath", "ss", "netstat", "dig", "nslookup", "host", "curl",
            "timedatectl", "chronyc", "ntpq", "ntpstat",
            "jps", "jstack", "jstat", "jinfo", "jmap",
            "keytool", "openssl",
            "journalctl",
            // 6 个 verbs 独有工具
            "kubectl", "docker", "git", "systemctl", "service", "redis-cli");

    /** 恰为 9 个多态工具；动词家族键必须与之精确相等，防止数据把多态工具降级为纯读工具。 */
    private static final Set<String> VERB_TOOLS = Set.of(
            "kubectl", "docker", "git", "systemctl", "service",
            "timedatectl", "chronyc", "openssl", "redis-cli");

    private static final Set<String> ROOT_KEYS =
            Set.of("schema_version", "tools", "verbs", "subverbs");

    /** 严格对象键：启用 Jackson 3 的重复键检测，不能依赖"后值覆盖前值"的默认行为。 */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private static final ReadOnlyCommandPolicyData INSTANCE =
            load(ReadOnlyCommandPolicyData.class.getClassLoader());

    private final Set<String> tools;
    private final Map<String, Set<String>> verbs;
    private final Map<String, Map<String, Set<String>>> subverbs;

    /** 由 Spring 在启动期实例化；静态初始化已在此前完成，非法数据会先抛出去。 */
    public ReadOnlyCommandPolicyData() {
        this.tools = INSTANCE.tools;
        this.verbs = INSTANCE.verbs;
        this.subverbs = INSTANCE.subverbs;
    }

    private ReadOnlyCommandPolicyData(Set<String> tools, Map<String, Set<String>> verbs,
                                      Map<String, Map<String, Set<String>>> subverbs) {
        this.tools = tools;
        this.verbs = verbs;
        this.subverbs = subverbs;
    }

    /** 加载后的三张成员关系表；判定方只读这一个入口（JVM 内单例，失败即 fail-closed）。 */
    public static ReadOnlyCommandPolicyData load() {
        return INSTANCE;
    }

    Set<String> tools() {
        return tools;
    }

    Map<String, Set<String>> verbs() {
        return verbs;
    }

    Map<String, Map<String, Set<String>>> subverbs() {
        return subverbs;
    }

    /** 从指定 classloader 读取固定路径（仅测试用于构造缺失/不可读场景）。 */
    static ReadOnlyCommandPolicyData load(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException(
                        "classpath 缺少 " + RESOURCE_PATH + "：拒绝回落默认白名单");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + RESOURCE_PATH + " 失败", e);
        }
    }

    /** 严格解析；任何不符合 schema 的数据都抛 {@link IllegalStateException}。 */
    static ReadOnlyCommandPolicyData parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (RuntimeException e) {
            // 保留解析器原文：重复 JSON 键等严格检测的错误信息要能传到失败日志里
            throw new IllegalStateException(
                    "readonly-command-policy 不是合法 JSON：" + e.getMessage(), e);
        }
        requireObject(root, "根节点");
        requireExactKeys(root, ROOT_KEYS, "根节点");

        JsonNode version = root.get("schema_version");
        if (version == null || !version.isInt() || version.intValue() != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalStateException("schema_version 必须为 " + SUPPORTED_SCHEMA_VERSION
                    + "，实际为 " + version);
        }

        Set<String> tools = parseMembers(root.get("tools"), "tools", TOOL_TOKEN, "工具");
        requireKnownTools(tools, "tools");

        JsonNode verbsNode = root.get("verbs");
        requireObject(verbsNode, "verbs");
        if (verbsNode.isEmpty() || !verbsNode.propertyNames().containsAll(VERB_TOOLS)
                || verbsNode.size() != VERB_TOOLS.size()) {
            throw new IllegalStateException("verbs 的键必须恰为 9 个多态工具 " + VERB_TOOLS);
        }
        Map<String, Set<String>> verbs = new HashMap<>();
        for (String tool : VERB_TOOLS) {
            String where = "verbs 的 " + tool;
            requireKnownTool(tool, where);
            Set<String> values = parseMembers(verbsNode.get(tool), where, VERB_TOKEN, "动词");
            verbs.put(tool, values);
        }

        JsonNode subverbsNode = root.get("subverbs");
        requireObject(subverbsNode, "subverbs");
        if (subverbsNode.isEmpty()) {
            throw new IllegalStateException("subverbs 不得为空");
        }
        Map<String, Map<String, Set<String>>> subverbs = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : subverbsNode.properties()) {
            String tool = entry.getKey();
            String where = "subverbs 的 " + tool;
            requireKnownTool(tool, where);
            if (!verbs.containsKey(tool)) {
                throw new IllegalStateException(where + " 不是动词家族");
            }
            JsonNode subNode = entry.getValue();
            requireObject(subNode, where);
            if (subNode.isEmpty()) {
                throw new IllegalStateException(where + " 不得为空");
            }
            Map<String, Set<String>> subs = new HashMap<>();
            for (Map.Entry<String, JsonNode> sub : subNode.properties()) {
                String verb = sub.getKey();
                if (!verbs.get(tool).contains(verb)) {
                    throw new IllegalStateException(where + " 含该工具动词集以外的键：" + verb);
                }
                subs.put(verb, parseMembers(sub.getValue(), where + "." + verb,
                        TOOL_TOKEN, "子动词"));
            }
            subverbs.put(tool, Map.copyOf(subs));
        }
        return new ReadOnlyCommandPolicyData(tools, Map.copyOf(verbs), Map.copyOf(subverbs));
    }

    /** 非空字符串数组：每项匹配字面量形状且无重复；类型错误/空集/元字符/空白一律拒绝。 */
    private static Set<String> parseMembers(JsonNode node, String where, Pattern pattern, String kind) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalStateException(where + " 必须是非空数组（空" + kind + "集即启动失败）");
        }
        Set<String> values = new LinkedHashSet<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.path(i);
            if (!item.isString()) {
                throw new IllegalStateException(where + " 第 " + i + " 项不是字符串");
            }
            String value = item.asText();
            if (!pattern.matcher(value).matches()) {
                throw new IllegalStateException(
                        where + " 含非法" + kind + "（通配/正则/空白/元字符等）：" + value);
            }
            if (!values.add(value)) {
                throw new IllegalStateException(where + " 有重复" + kind + "：" + value);
            }
        }
        return Set.copyOf(values);
    }

    private static void requireKnownTools(Set<String> candidates, String where) {
        for (String tool : candidates) {
            requireKnownTool(tool, where);
        }
    }

    private static void requireKnownTool(String tool, String where) {
        if (!KNOWN_TOOLS.contains(tool)) {
            throw new IllegalStateException(where + " 含未知工具：" + tool);
        }
    }

    private static void requireObject(JsonNode node, String where) {
        if (node == null || !node.isObject()) {
            throw new IllegalStateException(where + " 必须是 JSON 对象");
        }
    }

    private static void requireExactKeys(JsonNode node, Set<String> expected, String where) {
        Set<String> actual = new LinkedHashSet<>(node.propertyNames());
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    where + " 的键必须恰为 " + expected + "，实际为 " + actual);
        }
    }
}
