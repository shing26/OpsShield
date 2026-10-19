package com.opspilot.storm;

import com.opspilot.retrieval.EsSearchService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 问题指纹（前 128 位、32 位十六进制）的两个来源：
 * <ul>
 *   <li><b>查询指纹</b>（{@link #fingerprint}）：`SHA256(service + "|" + env + "|" +
 *       normalized_error_msg)`——归一化剥离时间戳/UUID/十六进制地址/数字，使同质告警收敛到同一指纹。
 *       适用于人工提问与既有自举源。</li>
 *   <li><b>告警身份</b>（{@link #identity}）：`SHA256("alert-id|" + alertId)`——输入是调用方
 *       显式给出的稳定身份，不做文本归一化。适用于外部告警模板会把 `$value` 渲染进文案的场景。</li>
 * </ul>
 *
 * 分隔符与长度都写明的原因：指纹同时是 L1 缓存键的组分、SSE meta 的回传值、以及反馈端点
 * 用来归档"问题"的键——外部要靠它对上，只写"SHA256(...)"会让人以为是 64 位（实测误解过一次）。
 * 截断到 128 位的碰撞界约 2^64，对告警去重足够。**注**：该截断在仓内只有实现、无决策记录。
 */
@Service
public class FingerprintService {

    private static final Pattern TS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX = Pattern.compile("\\b0x[0-9a-fA-F]+\\b|\\b[0-9a-fA-F]{16,}\\b");
    private static final Pattern NUM = Pattern.compile("\\d+");
    private static final Pattern ID_KV = Pattern.compile("(?i)(msg_?id|trace_?id|request_?id|order_?id|span_?id|txn_?id|[0-9a-f]{6,})\\s*[=:]\\s*\\S+");
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"|'[^']*'");
    private static final Pattern WS = Pattern.compile("\\s+");
    // 错误码需保留（不同码=不同故障），先抽出占位再归一化其余。
    // 占位符必须是纯字母（数字会被 NUM 掩码破坏），下标用 26 进制字母编码
    // （a…z, aa, ab…），突破单字母 26 个上限；« 与 » 不与正文冲突，
    // 且整段字面匹配保证 «ECa» 不会命中 «ECan»（其后是 'n' 而非 '»'）。
    // 错误码词法单一事实源在 EsSearchService.ERROR_CODE（fast-path/指纹共用同一形状）。
    private static final Pattern ERROR_CODE = EsSearchService.ERROR_CODE;

    private static String ecToken(int index) {
        StringBuilder letters = new StringBuilder();
        int i = index;
        do {
            letters.insert(0, (char) ('a' + i % 26));
            i = i / 26 - 1;
        } while (i >= 0);
        return "«EC" + letters + "»";
    }

    public String normalizeError(String msg) {
        String s = msg == null ? "" : msg;
        // 1) 保护错误码：替换为稳定占位符（保留其身份，屏蔽周边噪声）
        java.util.List<String> codes = new ArrayList<>();
        Matcher ec = ERROR_CODE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (ec.find()) {
            codes.add(ec.group());
            ec.appendReplacement(sb, ecToken(codes.size() - 1));
        }
        ec.appendTail(sb);
        s = sb.toString();
        // 2) 掩码高基数噪声
        s = TS.matcher(s).replaceAll("<TS>");
        s = UUID.matcher(s).replaceAll("<UUID>");
        s = ID_KV.matcher(s).replaceAll("<ID>");
        s = QUOTED.matcher(s).replaceAll("<STR>");
        s = HEX.matcher(s).replaceAll("<HEX>");
        s = NUM.matcher(s).replaceAll("<N>");
        s = WS.matcher(s.trim()).replaceAll(" ");
        // 3) 还原错误码
        for (int i = 0; i < codes.size(); i++) {
            s = s.replace(ecToken(i), codes.get(i));
        }
        return s;
    }

    public String fingerprint(String service, String env, String errorMsg) {
        return digest((service == null ? "" : service) + "|" + (env == null ? "" : env)
                + "|" + normalizeError(errorMsg));
    }

    /**
     * **告警身份**（2026-10-17，与"查询指纹"拆开）：调用方给的稳定身份 → 同一个 32 位十六进制形状。
     *
     * <p>为什么不是把 alertId 当文本再走 {@link #normalizeError}：身份是**不透明的**，
     * 没有"数字/时间戳该不该掩码"这回事——掩码是给人类可读文案发明的手段，
     * 对已经是稳定标识的输入做掩码只会引入无意义的不确定性（也正是不该由服务端做判断的那部分）。
     *
     * <p>固定前缀 {@code "alert-id|"} 让两种身份的输入空间**在实践中**分开：文本指纹形如
     * {@code service|env|文本}，告警身份形如 {@code alert-id|<身份>}；bridge 产出的身份是定长
     * 十六进制，不含分隔符。**这是约定隔离，不是密码学隔离**——{@code service}/{@code env} 是
     * 自由文本，理论上仍可构造出撞车的输入；真正的权限隔离是与本指纹同列的
     * {@code tenant×authLevel}（L1 键、SingleFlight 组键都带）。形状与文本指纹一致
     * （前 128 位、32 位十六进制），故 SSE meta、审计列、{@code FeedbackRequest} 的形状校验都不需要变。
     */
    public String identity(String alertId) {
        return digest("alert-id|" + (alertId == null ? "" : alertId));
    }

    private static String digest(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
