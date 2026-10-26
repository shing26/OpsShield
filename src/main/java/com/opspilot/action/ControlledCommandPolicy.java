package com.opspilot.action;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * L1 受控行动契约的判定器（ADR-0028 规格 §3）：一条命令是 L0-read、L1-write-low 还是被拒。
 *
 * <p><b>三条通道，顺序固定</b>：
 * <ol>
 *   <li><b>注入通道判定（L1 不豁免）</b>——单段（任何 {@code ;}/{@code |}/{@code &&} 链式即拒）、
 *       无命令/进程替换、无写重定向。复用 {@link ReadOnlyCommandPolicy} 的包可见判定，
 *       同一套策略判两遍，不重写第二条；
 *   <li><b>L0 读白名单</b>——直接委托 {@link ReadOnlyCommandPolicy#isReadOnly}（现状不变）；
 *   <li><b>L1 双确认</b>——chunk 显式声明 {@code action_class: L1-write-low}（文档作者的有意标注）
 *       ∧ 命令命中 L1 窄闭集前缀 ∧ 宾语/旗标白名单。</li>
 * </ol>
 *
 * <p><b>为什么 L1 白名单这么窄</b>：v1 灰度只放 {@code kubectl rollout restart|undo|status}
 * 与 {@code systemctl restart <白名单>}——每放开一族都需先过红队（ADR-0028 验收路径）。
 * 链式/替换在第一关整体拒（红队用例 l1-contract-hidden-cmd 的判据）。
 */
public final class ControlledCommandPolicy {

    private ControlledCommandPolicy() {}

    /** L1 动词前缀窄闭集（顺序敏感：前缀匹配后剩余 token 还要过宾语/旗标判定）。 */
    private static final List<String> L1_PREFIXES = List.of(
            "kubectl rollout restart",
            "kubectl rollout undo",
            "kubectl rollout status",
            "systemctl restart");

    /** kubectl rollout 的旗标白名单：只许指定 namespace——其余旗标闭集外判拒。 */
    private static final Set<String> KUBECTL_ROLLOUT_FLAGS = Set.of("-n", "--namespace");

    /** systemctl restart 宾语白名单（服务名闭集；扩集走语料评审 + 红队）。 */
    private static final Set<String> SYSTEMCTL_UNITS = Set.of(
            "nginx", "redis", "mysql", "order-api", "inventory-api");

    public enum Verdict { L0_READ, L1_WRITE_LOW, REJECT }

    /**
     * @param chunkDeclaresL1 chunk 元数据是否显式标注 {@code action_class: L1-write-low}
     *                       ——不是语料里的写命令都自动成为契约；文档作者的声明是语义确认。
     */
    public static Verdict classify(String command, boolean chunkDeclaresL1) {
        if (command == null || command.isBlank()) {
            return Verdict.REJECT;
        }
        // ── 第一关：L0（现状不变，链式由 L0 自身逐段判）──────────────
        if (ReadOnlyCommandPolicy.isReadOnly(command)) {
            return Verdict.L0_READ;
        }
        // ── 第二关：注入通道（L1 不豁免，且比 L0 更窄：必须单段）────
        // 链式对 L0 合法（cat x | grep y 每段独立判），但 L1 写操作不允许任何链式——
        // 一个 rollout restart 后面藏 ; rm 正是红队 l1-contract-hidden-cmd 的靶子。
        List<String> segments = ReadOnlyCommandPolicy.segments(command);
        if (segments.size() != 1) {
            return Verdict.REJECT;
        }
        String single = segments.get(0).strip();
        if (single.isEmpty()
                || !ReadOnlyCommandPolicy.noCommandSubstitution(single)
                || !ReadOnlyCommandPolicy.redirectsReadOnly(single)) {
            return Verdict.REJECT;
        }
        // ── 第三关：L1 双确认（文档声明 ∧ 引擎白名单）──────────────
        if (chunkDeclaresL1 && l1PrefixMatch(single)) {
            return Verdict.L1_WRITE_LOW;
        }
        return Verdict.REJECT;
    }

    private static boolean l1PrefixMatch(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        for (String prefix : L1_PREFIXES) {
            if (lower.startsWith(prefix + " ") || lower.equals(prefix)) {
                return tailValid(prefix, command.substring(prefix.length()).strip());
            }
        }
        return false;
    }

    /** 前缀之后的剩余 token：kubectl rollout 走资源+旗标白名单，systemctl 走宾语闭集。 */
    private static boolean tailValid(String prefix, String tail) {
        if (tail.isEmpty()) {
            return false;
        }
        String[] args = tail.split("\\s+");
        if (prefix.startsWith("kubectl")) {
            boolean sawResource = false;
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.startsWith("-") || a.startsWith("--")) {
                    if (!KUBECTL_ROLLOUT_FLAGS.contains(a)) {
                        return false;           // 闭集外旗标
                    }
                    i++;                        // 取值型旗标：吞掉它的值
                    if (i >= args.length) {
                        return false;           // 旗标缺值
                    }
                    if (!isPlainToken(args[i])) {
                        return false;
                    }
                } else if (isPlainToken(a)) {
                    sawResource = true;         // deployment/order-api
                } else {
                    return false;               // 资源名里有元字符
                }
            }
            return sawResource;
        }
        // systemctl restart <unit>：单宾语，白名单闭集
        return args.length == 1 && SYSTEMCTL_UNITS.contains(args[0]);
    }

    /**
     * 抽取 L1 命令的目标（灰度白名单用）：kubectl → deployment/order-api；systemctl → unit。
     * 抽不出返回 null（调用方按"不在白名单"处理 = 拒）。
     */
    public static String targetOf(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String lower = command.trim().toLowerCase(Locale.ROOT);
        String[] args = lower.split("\\s+");
        if (lower.startsWith("kubectl")) {
            for (int i = 3; i < args.length; i++) {          // kubectl rollout <verb> <target>
                if (!args[i].startsWith("-") && isPlainToken(args[i])) {
                    return args[i];
                }
            }
            return null;
        }
        if (lower.startsWith("systemctl restart")) {
            return args.length == 3 && isPlainToken(args[2]) ? args[2] : null;
        }
        return null;
    }

    /** 资源名/namespace 的安全字符集：小写字母、数字、点、斜杠、连字符。 */
    private static boolean isPlainToken(String token) {
        return token.matches("[a-z0-9][a-z0-9./-]*");
    }
}
