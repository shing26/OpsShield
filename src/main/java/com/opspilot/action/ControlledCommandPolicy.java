package com.opspilot.action;

import java.util.List;
import java.util.Locale;

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
 * <p><b>白名单内容不在本类</b>：前缀、旗标、宾语闭集由 classpath 数据文件
 * {@code policy/command-policy.json} 供给，经 {@link CommandPolicyData} 严格加载（未知键/
 * 未知家族/通配/重复/空集即启动失败）；本类只保留"前缀之后怎么判"的机制与顺序。v1 灰度仍只放
 * 两个命令家族——每放开一族都需先过红队（ADR-0028 验收路径）。链式/替换在第一关整体拒
 * （红队用例 l1-contract-hidden-cmd 的判据）。
 */
public final class ControlledCommandPolicy {

    private ControlledCommandPolicy() {}

    /** 白名单内容唯一事实源：classpath 固定路径的数据文件（ADR-0029 首个切面）。 */
    private static final CommandPolicyData DATA = CommandPolicyData.load();

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
        for (CommandPolicyData.Family family : DATA.families()) {
            for (String prefix : family.prefixes()) {
                if (lower.startsWith(prefix + " ") || lower.equals(prefix)) {
                    return tailValid(family, command.substring(prefix.length()).strip());
                }
            }
        }
        return false;
    }

    /** 前缀之后的剩余 token：旗标型家族走资源+旗标白名单，单宾语家族走宾语闭集。 */
    private static boolean tailValid(CommandPolicyData.Family family, String tail) {
        if (tail.isEmpty()) {
            return false;
        }
        String[] args = tail.split("\\s+");
        if (family.shape() == CommandPolicyData.TailShape.SINGLE_UNIT) {
            return args.length == 1 && family.units().contains(args[0]);
        }
        boolean sawResource = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("-")) {
                if (!family.flags().contains(a)) {
                    return false;               // 闭集外旗标
                }
                i++;                            // 取值型旗标：吞掉它的值
                if (i >= args.length) {
                    return false;               // 旗标缺值
                }
                if (!isPlainToken(args[i])) {
                    return false;
                }
            } else if (isPlainToken(a)) {
                sawResource = true;             // deployment/order-api
            } else {
                return false;                   // 资源名里有元字符
            }
        }
        return sawResource;
    }

    /**
     * 抽取 L1 命令的目标（灰度白名单用）：前缀型家族取第一个非旗标的资源 token，单宾语家族取
     * 唯一宾语。命令不命中任何数据文件前缀、或抽不出目标时返回 null（调用方按"不在白名单"
     * 处理 = 拒）。
     */
    public static String targetOf(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String lower = command.trim().toLowerCase(Locale.ROOT);
        for (CommandPolicyData.Family family : DATA.families()) {
            for (String prefix : family.prefixes()) {
                if (!lower.equals(prefix) && !lower.startsWith(prefix + " ")) {
                    continue;
                }
                if (lower.equals(prefix)) {
                    return null;                // 缺宾语
                }
                String[] args = lower.substring(prefix.length()).strip().split("\\s+");
                if (family.shape() == CommandPolicyData.TailShape.SINGLE_UNIT) {
                    return args.length == 1 && isPlainToken(args[0]) ? args[0] : null;
                }
                for (String arg : args) {
                    if (!arg.startsWith("-") && isPlainToken(arg)) {
                        return arg;
                    }
                }
                return null;
            }
        }
        return null;
    }

    /** 资源名/namespace 的安全字符集：小写字母、数字、点、斜杠、连字符。 */
    private static boolean isPlainToken(String token) {
        return token.matches("[a-z0-9][a-z0-9./-]*");
    }
}
