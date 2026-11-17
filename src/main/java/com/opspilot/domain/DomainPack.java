package com.opspilot.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 一次部署所选的领域内容（ADR-0031 薄切片；契约 second-domain-accept）。
 *
 * <p><b>五个字段，别无其他</b>：{@code id} / {@code corpusPath} / {@code stopLossMarkers} /
 * {@code diagnosticMarkers} / {@code diagnosticExcludes}。本切片刻意不含 prompt / 文案槽位：
 * 那些生成面内容留在底座，等薄切片验收后另立契约。
 *
 * <p><b>严格加载 / fail-closed</b>：manifest 只从 classpath 固定路径
 * {@code domain/<id>/domain.json} 读（无 HTTP、无热加载、无按租户覆盖）。未知
 * {@code schema_version}、未知顶层键、空段名列表、段名为空白、止损段与诊断段相交、
 * 段名含正则元字符或路径分隔符/空白、{@code corpusPath} 为绝对路径/含 {@code ..}/
 * 不在 {@code offline/} 下——一律抛 {@link IllegalStateException}，不静默忽略、不回落默认。
 *
 * <p><b>没有全局默认</b>：生产路径由 {@link DomainRegistry} 解析并构造器注入；
 * {@link #sre()} 是显式 test-only / 兼容夹具，从 {@code domain/sre/domain.json} 严格解析，
 * 不是硬编码常量，也不是"未初始化回落"。
 */
public record DomainPack(
        String id,
        String corpusPath,
        List<String> stopLossMarkers,
        List<String> diagnosticMarkers,
        List<String> diagnosticExcludes) {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private static final Set<String> ROOT_KEYS = Set.of(
            "schema_version", "id", "corpus_path", "stop_loss_markers",
            "diagnostic_markers", "diagnostic_excludes");

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** 段名只做精确子串匹配；正则/通配/路径/空白字符一律拒绝，避免"数据即代码"。 */
    private static final String FORBIDDEN_MARKER_CHARS = ".*()|+?[]{}^$/\\";

    /** 显式 test-only 夹具：严格解析 SRE manifest；生产注入路径不使用本方法。 */
    public static DomainPack sre() {
        return load("sre");
    }

    /** 按固定 classpath 路径 {@code domain/<id>/domain.json} 严格加载；不回落到任何默认。 */
    static DomainPack load(String id) {
        String resource = "domain/" + id + "/domain.json";
        try (InputStream in = DomainPack.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺少领域 manifest：" + resource
                        + "（领域 id=" + id + "）");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), id);
        } catch (IOException e) {
            throw new IllegalStateException("读取领域 manifest 失败：" + resource + "（领域 id=" + id + "）", e);
        }
    }

    /** 严格解析；任何不合 schema 的输入都抛异常。{@code expectedId} 为 null 时以 JSON 里的 id 为准。 */
    static DomainPack parse(String json, String expectedId) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (RuntimeException e) {
            throw new IllegalStateException("领域 manifest 不是合法 JSON（领域 id=" + expectedId + "）", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("领域 manifest 根节点必须是 JSON 对象（领域 id=" + expectedId + "）");
        }
        Set<String> actual = new LinkedHashSet<>(root.propertyNames());
        if (!actual.equals(ROOT_KEYS)) {
            throw new IllegalStateException("领域 manifest 顶层键必须恰为 " + ROOT_KEYS
                    + "，实际为 " + actual + "（领域 id=" + expectedId + "）");
        }
        JsonNode version = root.get("schema_version");
        if (version == null || !version.isInt() || version.intValue() != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalStateException("schema_version 必须为 " + SUPPORTED_SCHEMA_VERSION
                    + "，实际为 " + version + "（领域 id=" + expectedId + "）");
        }
        String id = requiredText(root, "id", expectedId);
        if (expectedId != null && !expectedId.equals(id)) {
            throw new IllegalStateException("领域 id 不一致：manifest 写的是 " + id
                    + "，选择的是 " + expectedId);
        }
        String corpusPath = requiredText(root, "corpus_path", id);
        requireCorpusPath(corpusPath, id);

        List<String> stop = parseMarkers(root.get("stop_loss_markers"), "stop_loss_markers", id);
        List<String> diag = parseMarkers(root.get("diagnostic_markers"), "diagnostic_markers", id);
        List<String> excludes = parseMarkers(root.get("diagnostic_excludes"), "diagnostic_excludes", id);
        for (String s : stop) {
            for (String d : diag) {
                if (s.contains(d) || d.contains(s)) {
                    throw new IllegalStateException("止损段与诊断段标记相交：" + s + " / " + d
                            + "（领域 id=" + id + "）");
                }
            }
        }
        return new DomainPack(id, corpusPath, List.copyOf(stop), List.copyOf(diag), List.copyOf(excludes));
    }

    private static String requiredText(JsonNode root, String field, String id) {
        JsonNode node = root.get(field);
        if (node == null || !node.isString() || node.asText().isBlank()) {
            throw new IllegalStateException("领域 manifest 的 " + field + " 必须是非空字符串"
                    + "（领域 id=" + id + "）");
        }
        return node.asText();
    }

    private static List<String> parseMarkers(JsonNode node, String field, String id) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalStateException("领域 manifest 的 " + field + " 必须是非空数组"
                    + "（领域 id=" + id + "）");
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (item == null || !item.isString() || item.asText().isBlank()) {
                throw new IllegalStateException(field + " 第 " + i + " 项必须是非空字符串"
                        + "（领域 id=" + id + "）");
            }
            String value = item.asText();
            if (value.strip().length() != value.length()) {
                throw new IllegalStateException(field + " 段名不得含首尾空白：" + value
                        + "（领域 id=" + id + "）");
            }
            for (char c : value.toCharArray()) {
                if (FORBIDDEN_MARKER_CHARS.indexOf(c) >= 0 || Character.isWhitespace(c)) {
                    throw new IllegalStateException(field + " 段名不得含正则元字符/空白/路径分隔符："
                            + value + "（领域 id=" + id + "）");
                }
            }
            if (!seen.add(value)) {
                throw new IllegalStateException(field + " 段名重复：" + value + "（领域 id=" + id + "）");
            }
            out.add(value);
        }
        return List.copyOf(out);
    }

    private static void requireCorpusPath(String path, String id) {
        String why = "（领域 id=" + id + "）";
        if (path.startsWith("/") || path.contains("\\") || path.contains("..")
                || path.strip().length() != path.length()) {
            throw new IllegalStateException("corpus_path 必须是 offline/ 下的相对路径：" + path + why);
        }
        if (!path.startsWith("offline/")) {
            throw new IllegalStateException("corpus_path 必须在 offline/ 下：" + path + why);
        }
    }
}
