package com.example.tvbox.plain.appsrc;

import com.example.tvbox.common.Digest;
import com.example.tvbox.common.Http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 「带签名的 App 接口源」骨架 —— {@link com.example.tvbox.plain.app3q.App3QPlain} 与
 * {@link com.example.tvbox.plain.appyqk.AppYQKPlain} 的公共父类。
 *
 * <h2>两个源为什么同族</h2>
 * 上游是两个互不相干的 APK（App3Q 是 /api.php 系 CMS 套壳，AppYQK 是 yqk1.app 自研接口），
 * 但<b>签名协议同构</b>，都属于「参数规范化 → 摘要 → 放进签名位」这一族：
 *
 * <table border="1">
 *   <tr><th></th><th>App3Q</th><th>AppYQK</th></tr>
 *   <tr><td>规范化串</td>
 *       <td>{@code finger=..&id=..&nonce=..&sk=..&time=..&v=..}<br>（键<b>字典序</b>，格式串写死在源码里）</td>
 *       <td>{@code appId=..&channelId=..&...&version=..&appKey=..}<br>（键<b>插入序</b>，遍历 LinkedHashMap）</td></tr>
 *   <tr><td>摘要</td><td>SHA-256 → <b>大写</b> hex</td><td>MD5 → <b>小写</b> hex</td></tr>
 *   <tr><td>载体</td><td>请求头 {@code x-sign}</td><td>请求体字段 {@code sign}</td></tr>
 *   <tr><td>盐</td><td>{@code sk}（ext 提供）</td><td>{@code appKey}（硬编码，见 C 类）</td></tr>
 * </table>
 *
 * 差异只有「规范化顺序」「摘要算法」「放哪」三处，因此骨架提供前三者的原语，
 * 子类各自拼自己的字段表 —— 这是本层存在的原因，而不是为了少写几行。
 *
 * <h2>规范化串的两个易错点（都已在子类注释里标出）</h2>
 * <ol>
 *   <li><b>空值也要参与</b>：{@code key=} 必须保留（App3Q 的 {@code v=} 常为空串）。
 *       上游用 {@code map.put("k","")} + 遍历，天然保留。</li>
 *   <li><b>末尾不带分隔符</b>：上游两种做法都删掉尾部分隔符
 *       （App3Q 用 {@code String.format} 天然没有；AppYQK 用 {@code deleteCharAt(len-1)}）。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * <ul>
 *   <li>{@code ae/decompiled/App3Q.java:58-67}（{@code x-sign} 计算）</li>
 *   <li>{@code ae/decompiled/AppYQK.java:53-68}（{@code getSign}）</li>
 *   <li>{@code merge.J.e.a(String)} = MD5 小写 hex，实体见
 *       {@code maintain/src/plain/duanju/ShortQimaoPlain.java:599}（同一方法已由该源独立还原并复核）</li>
 * </ul>
 */
public abstract class AppSignedBase extends AppSourceBase {

    /** 构造 */
    protected AppSignedBase() {
        super();
    }

    // ── 签名原语 ────────────────────────────────────────────

    /**
     * 按<b>插入序</b>拼 {@code k=v<sep>k=v...}，末尾不留分隔符。
     *
     * @param map 参数表（LinkedHashMap 保证顺序），可为 null
     * @param sep 分隔符，通常 {@code '&'}
     * @return 规范化串；map 为空时为空串
     */
    public static String canonical(Map<String, String> map, char sep) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(e.getKey()).append('=').append(e.getValue() == null ? "" : e.getValue());
        }
        return sb.toString();
    }

    /**
     * 按<b>键字典序</b>拼 {@code k=v<sep>k=v...}，末尾不留分隔符。
     *
     * @param map 参数表，可为 null
     * @param sep 分隔符
     * @return 规范化串
     */
    public static String canonicalSorted(Map<String, String> map, char sep) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        return canonical(new TreeMap<>(map), sep);
    }

    /**
     * MD5 → 32 位小写 hex（还原 {@code merge.J.e.a(String)}）。
     *
     * @param s 原文，可为 null
     * @return 小写 hex；异常时为空串
     */
    public static String md5Lower(String s) {
        return s == null ? "" : Digest.md5(s);
    }

    /**
     * SHA-256 → 64 位<b>大写</b> hex。
     *
     * <p>还原上游「{@code String.format("%02x", Byte)} 逐字节拼接后 {@code toUpperCase()}」。
     * {@code %02x} 作用于 {@code Byte} 时按无符号格式化，因此等价于标准小写 hex 再转大写。
     *
     * @param s 原文，可为 null
     * @return 大写 hex；异常时为空串
     */
    public static String sha256Upper(String s) {
        if (s == null) {
            return "";
        }
        String h = Digest.sha256(s);
        return h == null ? "" : h.toUpperCase();
    }

    /**
     * 取签名串里的「参数字段表」快照 —— 便于测试与日志，不参与签名。
     *
     * @param kv 交替的键值对
     * @return 保序 map
     */
    public static LinkedHashMap<String, String> kv(String... kv) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        if (kv == null) {
            return m;
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return m;
    }

    // ── 带签名的请求 ───────────────────────────────────────

    /**
     * 发 GET 请求（带签名头）。
     *
     * @param url     完整地址
     * @param headers 请求头，可为 null
     * @return 响应体；失败为空串
     */
    public String getSigned(String url, Map<String, String> headers) {
        return http.get(url, headers);
    }

    /**
     * 发 POST（JSON 体，带签名头）。
     *
     * @param url     完整地址
     * @param body    请求体
     * @param headers 请求头，可为 null
     * @return 响应体；失败为空串
     */
    public String postSignedJson(String url, String body, Map<String, String> headers) {
        return http.post(url, body, headers);
    }

    /**
     * 把要参与签名的键取出（不存在的键按空串），用于「键表固定」的签名格式。
     *
     * @param src 源表，可为 null
     * @param keys 需要的键，按签名串里的出现顺序
     * @return 保序 map，值为空串而不是缺失
     */
    public static LinkedHashMap<String, String> pick(Map<String, String> src, String... keys) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (keys == null) {
            return out;
        }
        for (String k : keys) {
            String v = src == null ? null : src.get(k);
            out.put(k, v == null ? "" : v);
        }
        return out;
    }

    /**
     * 把 map 摊平成 {@code k=v&...} 的查询串（不含 URL 编码，上游同样不编码）。
     *
     * @param map 参数表，可为 null
     * @return 查询串
     */
    public static String query(Map<String, String> map) {
        return canonical(map, '&');
    }

    /**
     * 解析签名后的响应体；非 JSON 时返回空对象。
     *
     * @param body 响应体，可为 null
     * @return 非 null JSONObject
     */
    public static org.json.JSONObject json(String body) {
        return Http.parseJson(body);
    }

    /**
     * 列出 map 的键 —— 仅用于测试/日志，避免直接暴露可变结构。
     *
     * @param map 参数表，可为 null
     * @return 键列表
     */
    public static List<String> keys(Map<String, String> map) {
        return map == null ? new ArrayList<String>() : new ArrayList<>(map.keySet());
    }
}
