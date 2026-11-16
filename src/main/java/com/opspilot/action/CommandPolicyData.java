package com.opspilot.action;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * L1 命令策略白名单的**内容**（ADR-0029 首个切面）：classpath 固定路径
 * {@value #RESOURCE_PATH} 是前缀 / 旗标 / 宾语闭集的唯一事实源；判定机制、注入/链式门与
 * 判定顺序仍留在 {@link ControlledCommandPolicy}。
 *
 * <p><b>strict / fail-closed</b>：未知 {@code schema_version}、未知顶层/家族键、未知家族 id、
 * 重复项、通配/正则、首尾空白、{@code --flag=value}、空前缀集/空旗标集/空宾语集、文件缺失或
 * 不可读——一律抛 {@link IllegalStateException}，不静默忽略、不回落默认值。只从 classpath
 * 读这一个固定路径：没有环境变量、HTTP 上传、按租户覆盖或热加载面。
 *
 * <p>注册为 Spring 组件是为了让非法数据在**启动期**失败，而不是等到第一次提案才失败；判定方
 * 走静态 {@link #load()}，不依赖注入。
 */
@Component
public final class CommandPolicyData {

    /** 固定资源路径（相对 classpath 根）；这是本类唯一的读取入口。 */
    static final String RESOURCE_PATH = "policy/command-policy.json";

    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** 前缀 token 序列：全小写、单空格分隔、无首尾空白、无元字符/通配/正则。 */
    private static final Pattern PREFIX = Pattern.compile(
            "[a-z0-9][a-z0-9-]*( [a-z0-9][a-z0-9-]*)*");

    /** 旗标：{@code -n} / {@code --namespace}；{@code --flag=value} 因 {@code =} 被拒。 */
    private static final Pattern FLAG = Pattern.compile("-{1,2}[a-z][a-z0-9-]*");

    /** 宾语（服务名/资源名）：小写字母数字开头，余下允许连字符。 */
    private static final Pattern UNIT = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /** 家族 id → 尾部校验形状。未知 id 启动期失败——数据不能借新增家族扩权。 */
    private static final Map<String, TailShape> KNOWN_FAMILIES = Map.of(
            "kubectl-rollout", TailShape.FLAG_ARGS,
            "systemctl-unit", TailShape.SINGLE_UNIT);

    private static final Set<String> ROOT_KEYS = Set.of("schema_version", "families");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final CommandPolicyData INSTANCE = load(CommandPolicyData.class.getClassLoader());

    private final List<Family> families;

    /** 由 Spring 在启动期实例化；静态初始化已在此前完成，非法数据会先抛出去。 */
    public CommandPolicyData() {
        this.families = INSTANCE.families;
    }

    private CommandPolicyData(List<Family> families) {
        this.families = families;
    }

    /** 加载后的白名单内容；判定方只读这一个入口（JVM 内单例，失败即 fail-closed）。 */
    public static CommandPolicyData load() {
        return INSTANCE;
    }

    /** 家族：前缀闭集 + 该家族的尾部校验形状（旗标型或单宾语型）。 */
    record Family(String id, List<String> prefixes, Set<String> flags, Set<String> units,
                  TailShape shape) {}

    /** 尾部校验形状：前缀之后是"资源 + 旗标闭集"还是"单个宾语闭集"。 */
    enum TailShape { FLAG_ARGS, SINGLE_UNIT }

    List<Family> families() {
        return families;
    }

    /** 从指定 classloader 读取固定路径（仅测试用于构造缺失/不可读场景）。 */
    static CommandPolicyData load(ClassLoader loader) {
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
    static CommandPolicyData parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (RuntimeException e) {
            throw new IllegalStateException("command-policy 不是合法 JSON", e);
        }
        requireObject(root, "根节点");
        requireExactKeys(root, ROOT_KEYS, "根节点");

        JsonNode version = root.get("schema_version");
        if (version == null || !version.isInt() || version.intValue() != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalStateException("schema_version 必须为 " + SUPPORTED_SCHEMA_VERSION
                    + "，实际为 " + version);
        }

        JsonNode familiesNode = root.get("families");
        requireObject(familiesNode, "families");
        if (familiesNode.isEmpty()) {
            throw new IllegalStateException("families 不得为空");
        }

        List<Family> families = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : familiesNode.properties()) {
            families.add(parseFamily(entry.getKey(), entry.getValue()));
        }
        requireUnambiguousPrefixes(families);
        return new CommandPolicyData(List.copyOf(families));
    }

    private static Family parseFamily(String id, JsonNode node) {
        TailShape shape = KNOWN_FAMILIES.get(id);
        if (shape == null) {
            throw new IllegalStateException("未知命令家族 id：" + id);
        }
        requireObject(node, "家族 " + id);
        Set<String> expected = shape == TailShape.FLAG_ARGS
                ? Set.of("prefixes", "flags")
                : Set.of("prefixes", "units");
        requireExactKeys(node, expected, "家族 " + id);

        List<String> prefixes = parseList(node.get("prefixes"), "家族 " + id + " 的 prefixes", PREFIX, "前缀");
        if (shape == TailShape.FLAG_ARGS) {
            Set<String> flags = new LinkedHashSet<>(
                    parseList(node.get("flags"), "家族 " + id + " 的 flags", FLAG, "旗标"));
            return new Family(id, prefixes, Set.copyOf(flags), Set.of(), shape);
        }
        Set<String> units = new LinkedHashSet<>(
                parseList(node.get("units"), "家族 " + id + " 的 units", UNIT, "宾语"));
        return new Family(id, prefixes, Set.of(), Set.copyOf(units), shape);
    }

    private static List<String> parseList(JsonNode node, String where, Pattern pattern, String kind) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalStateException(where + " 必须是非空数组（空" + kind + "集即启动失败）");
        }
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.path(i);
            if (!item.isString()) {
                throw new IllegalStateException(where + " 第 " + i + " 项不是字符串");
            }
            String value = item.asText();
            if (!pattern.matcher(value).matches()) {
                throw new IllegalStateException(
                        where + " 含非法" + kind + "（通配/正则/大写/空白/元字符等）：" + value);
            }
            if (!seen.add(value)) {
                throw new IllegalStateException(where + " 有重复" + kind + "：" + value);
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    /**
     * 前缀全表唯一，且任一项不得是另一项的 token 真前缀——否则匹配结果依赖文件顺序
     * （{@code kubectl rollout} 与 {@code kubectl rollout restart} 并存即拒）。
     */
    private static void requireUnambiguousPrefixes(List<Family> families) {
        List<String> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Family family : families) {
            for (String prefix : family.prefixes()) {
                if (!seen.add(prefix)) {
                    throw new IllegalStateException("前缀在家族间重复：" + prefix);
                }
                all.add(prefix);
            }
        }
        for (String prefix : all) {
            for (String other : all) {
                if (!prefix.equals(other) && other.startsWith(prefix + " ")) {
                    throw new IllegalStateException(
                            "前缀互为真前缀（匹配顺序敏感）：" + prefix + " ⊂ " + other);
                }
            }
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
