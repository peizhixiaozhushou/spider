package com.example.tvbox.plain.xbpq;

import android.util.Log;

import com.example.tvbox.common.Digest;
import com.example.tvbox.common.Http;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * XBPQ 的 <b>验证码 / 人机识别（OCR）子协议</b> —— 从 43771 行的 {@code XBPQ} 里切出来的
 * 唯一「有外部服务依赖」的模块，独立成明文类。
 *
 * <p>反编译原文: {@code ae/decompiled/XBPQ.java}
 * <br>对应上游片段（行号即反编译行号）：
 * <table border="1">
 *   <tr><th>上游</th><th>行</th><th>本类</th></tr>
 *   <tr><td>{@code p(String,String,String)}</td><td>40707–40751（45 行）</td>
 *       <td>{@link #recognize(String, int, String)} + {@link #buildRequest(int, String)} +
 *           {@link #extractCode(int, String)}</td></tr>
 *   <tr><td>{@code B(String,boolean)}</td><td>32750–32902（153 行）</td>
 *       <td>{@link #endpoints(String, boolean, String, boolean)}</td></tr>
 *   <tr><td>{@code R(String,String)} 的 OCR 段</td><td>33375–33430</td>
 *       <td>{@link #classify(String)} + {@link #ocrBase(int, String)} + {@link #signValue(String)}</td></tr>
 *   <tr><td>{@code a0}/{@code e0} 的 {@code &&} 取串子集</td><td>37908–38586</td>
 *       <td>{@link #between(String, String, String)}</td></tr>
 * </table>
 *
 * <h2>协议（逐字来自反编译）</h2>
 * <pre>
 * ── 判定 ──
 * 页含「输入验证码」 → 图形验证码 → ocr_type=1
 * 页含「滑动验证」   → 滑块验证码 → ocr_type=3
 * 页含「检测中」+「跳转中」+「btwaf」 → 宝塔盾拦截页（先带 btwaf= 令牌重放）
 *
 * ── 取验证码图（GET，用当前 UA） ──
 * GET {验证图片页 URL}
 *   → body 字节 = 验证码图，Base64（不换行）
 *   → Set-Cookie 头原样保留，用于之后提交
 *
 * ── 送 OCR 服务（POST application/json） ──
 * 配置地址 ext：H("ocr","Ocr","OCR","")，非 http 开头则用默认：
 *   ocr_type=1 → https://ocr.wogg.link/      （图形验证码：识别字符）
 *   ocr_type=3 → https://ocr1.wogg.link/     （滑块验证码：识别缺口 x 偏移）
 * body：
 *   ocr_type=1 → {"ocr_type":1,"img":"&lt;b64&gt;","backgroundlmg":"None"}
 *   ocr_type=3 → {"ocr_type":3,"img":"&lt;b64&gt;"}
 * 响应：
 *   ocr_type=1 → {"result":"&lt;验证码文本&gt;"}
 *   ocr_type=3 → {"result":{"target":"[123,45]"}}   ← 方括号与逗号被剥掉，只留 "123,45"
 *
 * ── 提交 ──
 * ocr_type=1：GET/POST {codeUrl}{code}  →  响应 JSON 的 code 必须 == 1
 * ocr_type=3：GET/POST {codeUrl}{code}  →  响应体必须包含 "ok"
 * </pre>
 *
 * <h2>验证端点推导（还原 {@code B()}）</h2>
 * {@code ext} 的「验证」配置为 {@code #} 分隔串，{@code [0]} 是验证图片页、
 * {@code [1]}（搜索时是 {@code [2]}）是提交地址；值可为 {@code 空}、
 * 绝对 URL、以 {@code /} 开头的相对 URL 或相对片段：
 * <pre>
 * 图形：page   = {site}/index.php/verify/index.html?
 *       submit = {site}/index.php/ajax/verify_check?type=show&amp;verify=      （搜索时 type=search）
 * 滑块：page   = {site}/extend/vercode/tncode.php?
 *       submit = {site}/extend/vercode/check.php?tn_r=
 * </pre>
 *
 * <h2>本类刻意不做的事（边界，写在文档里以免误以为「漏了」）</h2>
 * <ul>
 *   <li>不重放「宝塔盾」页面、不重试整个请求链 —— 那属于 {@code XBPQ.R()} 的循环与
 *       {@code XBPQ.k()} 的取页流程，仍在原类里。</li>
 *   <li>不实现 {@code a0/e0} 的宏（{@code url:} / {@code +} / {@code $sub:} /
 *       {@code urlDecode(...)}）。{@link #between(String, String, String)} 只做
 *       「取 {@code &&} 两侧之间的子串」，即验证码链实际用到的那一档。</li>
 *   <li>不持有 cookie jar、不做站点规则匹配 —— 见 {@code maintain/evidence/XBPQ_RETAIN.md}。</li>
 * </ul>
 *
 * <h2>未能确定项（如实列出）</h2>
 * <ol>
 *   <li>{@code signValue} 的 MD5 <b>大小写</b>无法从静态产物确定（上游
 *       {@code merge.C0.j.m.a(str, charset)} 只出现两次，另一处是配置宏
 *       {@code md5(...)} 的替换）。本实现取下写（与 {@code Digest.md5} 一致），
 *       并额外提供 {@link #signValueUpper(String)} 供宿主切换。</li>
 *   <li>提交动作的 HTTP 方法：上游图形码走 {@code merge.C0.h.b.c(url, ...)}、
 *       滑块走 {@code merge.C0.k.g(url, headers)}，两者都是「GET 且要 cookie」的
 *       封装，故本实现统一 GET；宿主如遇服务端拒绝可覆写 {@link #submit}。</li>
 * </ol>
 */
public class XbpqOcr {

    private static final String TAG = "XbpqOcr";

    // ── OCR 服务 ──────────────────────────────────────────────
    /** 图形验证码识别服务（反编译 XBPQ.java:33397） */
    public static final String DEFAULT_OCR_IMAGE = "https://ocr.wogg.link/";
    /** 滑块缺口识别服务（反编译 XBPQ.java:33393） */
    public static final String DEFAULT_OCR_SLIDER = "https://ocr1.wogg.link/";

    /** {@code ocr_type}：图形验证码 */
    public static final int TYPE_IMAGE = 1;
    /** {@code ocr_type}：滑块验证码 */
    public static final int TYPE_SLIDER = 3;

    // ── 页面标记（反编译字面量） ──────────────────────────────
    /** 图形验证码页标记 */
    public static final String MARK_IMAGE = "输入验证码";
    /** 滑块验证码页标记 */
    public static final String MARK_SLIDER = "滑动验证";
    /** 宝塔盾拦截页标记（三个必须同时出现） */
    public static final String MARK_BTWAF_1 = "检测中";
    /** 宝塔盾标记 2 */
    public static final String MARK_BTWAF_2 = "跳转中";
    /** 宝塔盾标记 3 */
    public static final String MARK_BTWAF_3 = "btwaf";

    /** 「空」占位（反编译里用于表示该段无覆盖值） */
    public static final String EMPTY_SLOT = "空";

    /** 提交成功的判据（滑块分支要求响应体包含） */
    public static final String SUBMIT_OK = "ok";

    // ─────────────────────────────────────────────────────────────
    // 传输 seam
    // ─────────────────────────────────────────────────────────────

    /** 单次 HTTP 响应（含字节体与响应头） */
    public static final class Response {
        /** HTTP 状态码；0 表示未取到 */
        public int code;
        /** 原始字节体（图片走这里） */
        public byte[] bytes = new byte[0];
        /** 文本体（UTF-8，从 bytes 解出） */
        public String body = "";
        /** 响应头（键小写） */
        public Map<String, String> headers = new LinkedHashMap<>();

        /** @return Set-Cookie（原样，可能为 null） */
        public String setCookie() {
            return headers.get("set-cookie");
        }

        @Override
        public String toString() {
            return "Response{code=" + code + ", bytes=" + bytes.length + "}";
        }
    }

    /**
     * GET（可覆写）。默认实现用 {@link HttpURLConnection} 拿字节
     * （工程内 {@link Http} 只暴露字符串体，Base64 图片不能走字符串）。
     *
     * @param url     完整 URL
     * @param headers 请求头（可为 null）
     * @return 响应；失败时 {@code code=0} 且体为空（不抛）
     */
    public Response httpGet(String url, Map<String, String> headers) {
        Response r = new Response();
        if (url == null || url.isEmpty()) {
            return r;
        }
        if (Http.isOfflineForTest()) {
            Log.d(TAG, "offline, skip GET " + url);
            return r;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(Http.DEFAULT_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(Http.DEFAULT_READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            r.code = conn.getResponseCode();
            r.bytes = readAll(conn.getInputStream());
            r.body = new String(r.bytes, Charset.forName("UTF-8"));
            r.headers = collectHeaders(conn);
        } catch (Throwable e) {
            Log.w(TAG, "GET failed " + url + " : " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return r;
    }

    /**
     * POST JSON（可覆写）。
     *
     * @param url     完整 URL
     * @param json    请求体
     * @param headers 请求头（可为 null）
     * @return 响应；失败时 {@code code=0} 且体为空
     */
    public Response httpPost(String url, String json, Map<String, String> headers) {
        Response r = new Response();
        if (url == null || url.isEmpty()) {
            return r;
        }
        if (Http.isOfflineForTest()) {
            Log.d(TAG, "offline, skip POST " + url);
            return r;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(Http.DEFAULT_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(Http.DEFAULT_READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            conn.getOutputStream().write((json == null ? "" : json)
                    .getBytes(Charset.forName("UTF-8")));
            conn.getOutputStream().flush();
            r.code = conn.getResponseCode();
            r.bytes = readAll(conn.getInputStream());
            r.body = new String(r.bytes, Charset.forName("UTF-8"));
            r.headers = collectHeaders(conn);
        } catch (Throwable e) {
            Log.w(TAG, "POST failed " + url + " : " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return r;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static Map<String, String> collectHeaders(HttpURLConnection conn) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, java.util.List<String>> e : conn.getHeaderFields().entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            out.put(e.getKey().toLowerCase(java.util.Locale.US), String.join(";", e.getValue()));
        }
        return out;
    }

    /**
     * 提交验证结果（可覆写）。
     *
     * @param url     提交地址（已含 code 后缀）
     * @param headers 请求头
     * @return 响应体；失败空串
     */
    public String submit(String url, Map<String, String> headers) {
        Response r = httpGet(url, headers);
        return r.body == null ? "" : r.body;
    }

    // ─────────────────────────────────────────────────────────────
    // 判定
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code R()} 里 {@code z = str2.indexOf("输入验证码") >= 0}。
     *
     * @param html 页面源码，可为 null
     * @return 是否图形验证码
     */
    public static boolean isImageCaptcha(String html) {
        return html != null && html.contains(MARK_IMAGE);
    }

    /**
     * 还原上游 {@code str2.indexOf("滑动验证") >= 0}。
     *
     * @param html 页面源码
     * @return 是否滑块验证码
     */
    public static boolean isSliderCaptcha(String html) {
        return html != null && html.contains(MARK_SLIDER);
    }

    /**
     * 是否进入 OCR 分支（两者任一）。
     *
     * @param html 页面源码
     * @return true 表示需要 OCR
     */
    public static boolean needsOcr(String html) {
        return isImageCaptcha(html) || isSliderCaptcha(html);
    }

    /**
     * 验证类型判定。
     *
     * <p>与上游一致：<b>先判图形码</b>，故同时含两个标记时按图形码处理。
     *
     * @param html 页面源码
     * @return {@link #TYPE_IMAGE} / {@link #TYPE_SLIDER} / {@code -1}（不需要 OCR）
     */
    public static int classify(String html) {
        if (isImageCaptcha(html)) {
            return TYPE_IMAGE;
        }
        return isSliderCaptcha(html) ? TYPE_SLIDER : -1;
    }

    /**
     * 还原上游 {@code R()} 开头的宝塔盾判定。
     *
     * @param html 页面源码，可为 null
     * @return true 表示是宝塔盾拦截页
     */
    public static boolean isBtwafInterstitial(String html) {
        return html != null
                && html.contains(MARK_BTWAF_1)
                && html.contains(MARK_BTWAF_2)
                && html.contains(MARK_BTWAF_3);
    }

    /**
     * 从宝塔盾页里取重放令牌（还原 {@code a0(str2, "btwaf=&&\"", "").get(0)}）。
     *
     * @param html 宝塔盾页
     * @return 令牌；取不到空串
     */
    public static String extractBtwafToken(String html) {
        return between(html, "btwaf=", "\"");
    }

    // ─────────────────────────────────────────────────────────────
    // OCR 服务
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code H("ocr","Ocr","OCR","")} 的取值规则：
     * 配置非 http 开头（含空）→ 用内置默认。
     *
     * @param type       验证类型（1/3）
     * @param configured ext 配置值
     * @return OCR 服务基址（总是以 {@code /} 结尾的可用值）
     */
    public static String ocrBase(int type, String configured) {
        if (configured != null && !configured.isEmpty() && configured.startsWith("http")) {
            return configured;
        }
        return type == TYPE_SLIDER ? DEFAULT_OCR_SLIDER : DEFAULT_OCR_IMAGE;
    }

    /**
     * 还原上游 {@code Base64.encodeToString(bytes, 0).replaceAll("\\s", "")}。
     *
     * <p>{@code android.util.Base64} 的 flag 0（DEFAULT）会插入换行，
     * 所以上游必须再过一遍空白剔除；这里用标准无换行编码器 + 同样的剔除。
     *
     * @param img 图片字节，可为 null
     * @return Base64 串（无换行、无空白）
     */
    public static String encodeImage(byte[] img) {
        if (img == null || img.length == 0) {
            return "";
        }
        return Base64.getEncoder().encodeToString(img).replaceAll("\\s", "");
    }

    /**
     * 还原上游 {@code p()} 里的两段请求体构造。
     *
     * @param type   验证类型
     * @param base64 Base64 图片
     * @return JSON 请求体；类型未知返回空串（上游同样返回空串）
     */
    public static String buildRequest(int type, String base64) {
        String img = base64 == null ? "" : base64;
        if (type == TYPE_IMAGE) {
            return "{\"ocr_type\":1,\"img\":\"" + img + "\",\"backgroundlmg\":\"None\"}";
        }
        if (type == TYPE_SLIDER) {
            return "{\"ocr_type\":3,\"img\":\"" + img + "\"}";
        }
        return "";
    }

    /**
     * 还原上游 {@code p()} 的响应取值。
     *
     * @param type 验证类型
     * @param json OCR 服务响应体
     * @return 图形码 = {@code result}；滑块 = {@code result.target} 去掉 {@code [}{@code ]}；
     *         取不到空串
     */
    public static String extractCode(int type, String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            JSONObject o = new JSONObject(json);
            if (type == TYPE_IMAGE) {
                return o.optString("result");
            }
            if (type == TYPE_SLIDER) {
                JSONObject r = o.optJSONObject("result");
                if (r == null) {
                    return "";
                }
                return r.optString("target").replace("[", "").replace("]", "");
            }
        } catch (Throwable e) {
            Log.w(TAG, "extractCode failed: " + e.getMessage());
        }
        return "";
    }

    /** OCR 结果 */
    public static final class OcrResult {
        /** 识别出的验证码文本 / 滑块偏移 */
        public String code = "";
        /** 验证码页给的 Set-Cookie（上游原样透传给提交请求） */
        public String cookie = "";
        /** OCR 服务是否给出了可用结果 */
        public boolean ok;

        @Override
        public String toString() {
            return "OcrResult{ok=" + ok + ", code=" + (code.length() > 24 ? code.substring(0, 24) : code)
                    + ", cookie=" + (cookie.isEmpty() ? "" : "<set>") + "}";
        }
    }

    /**
     * 还原上游 {@code p(String,String,String)} 全流程：
     * 取验证码图 → Base64 → POST OCR 服务 → 取 code 与 Set-Cookie。
     *
     * @param imageUrl     验证码图片页 URL（上游 {@code jSONObjectB.getString("str")}）
     * @param type         验证类型
     * @param configuredBase ext 里配置的 OCR 基址（可空）
     * @return 结果；任一步失败 {@code ok=false}
     */
    public OcrResult recognize(String imageUrl, int type, String configuredBase) {
        OcrResult out = new OcrResult();
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", defaultUa());
        Response img = httpGet(imageUrl, headers);
        out.cookie = img.setCookie() == null ? "" : img.setCookie();
        String b64 = encodeImage(img.bytes);
        if (b64.isEmpty()) {
            Log.w(TAG, "验证码图未取到: " + imageUrl);
            return out;
        }
        String base = ocrBase(type, configuredBase);
        Response ocr = httpPost(base, buildRequest(type, b64), headers);
        out.code = extractCode(type, ocr.body);
        out.ok = !out.code.isEmpty();
        Log.i(TAG, "OCR → " + out);
        return out;
    }

    /**
     * 验证码链使用的 UA（还原上游 {@code M("")} 的默认分支字面量）。
     *
     * @return UA
     */
    public static String defaultUa() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
    }

    // ─────────────────────────────────────────────────────────────
    // 端点推导（还原 B()）
    // ─────────────────────────────────────────────────────────────

    /** 验证端点对 */
    public static final class Endpoints {
        /** 验证码图片页 URL（上游 JSON 的 {@code str} 字段） */
        public String page = "";
        /** 提交地址（上游 JSON 的 {@code codeUrl} 字段） */
        public String submit = "";

        @Override
        public String toString() {
            return "Endpoints{page=" + page + ", submit=" + submit + "}";
        }
    }

    /**
     * 还原上游 {@code B(String, boolean)}：推导验证码页与提交地址。
     *
     * @param verifyConfig ext「验证」配置串（{@code #} 分隔，可为空）
     * @param imageCaptcha true=图形码，false=滑块
     * @param siteBase     站点基址（上游 {@code this.f}，末尾不要带 {@code /}）
     * @param search       是否处于搜索上下文（上游 {@code "搜索".equals(this.r)}）
     * @return 端点对；入参非法时返回空对
     */
    public static Endpoints endpoints(String verifyConfig, boolean imageCaptcha,
                                      String siteBase, boolean search) {
        Endpoints e = new Endpoints();
        if (siteBase == null) {
            siteBase = "";
        }
        String cfg = verifyConfig == null ? "" : verifyConfig;

        if (imageCaptcha) {
            e.page = siteBase + "/index.php/verify/index.html?";
            e.submit = siteBase + "/index.php/ajax/verify_check?type="
                    + (search ? "search" : "show") + "&verify=";
        } else {
            e.page = siteBase + "/extend/vercode/tncode.php?";
            e.submit = siteBase + "/extend/vercode/check.php?tn_r=";
        }
        if (cfg.isEmpty()) {
            return e;
        }
        String[] parts = cfg.split("#");
        // 提交地址：搜索用 [2]，其余用 [1]（越界则保留默认）
        int submitIdx = search ? 2 : 1;
        if (parts.length > submitIdx && !EMPTY_SLOT.equals(parts[submitIdx])) {
            e.submit = resolve(parts[submitIdx], siteBase);
        }
        // 图片页：[0] 非「空」时覆盖
        if (parts.length > 0 && !EMPTY_SLOT.equals(parts[0])) {
            e.page = resolve(parts[0], siteBase);
        }
        return e;
    }

    /**
     * 还原上游的 URL 规整三态：绝对 URL 原样 / {@code /} 开头拼站点 /
     * 其余拼 {@code 站点 + "/"}。
     *
     * @param v        片段
     * @param siteBase 站点基址
     * @return 规整后的 URL
     */
    public static String resolve(String v, String siteBase) {
        if (v == null) {
            return "";
        }
        String base = siteBase == null ? "" : siteBase;
        if (v.startsWith("http")) {
            return v;
        }
        if (v.startsWith("/")) {
            return base + v;
        }
        return base + "/" + v;
    }

    // ─────────────────────────────────────────────────────────────
    // 人机识别里的签名（还原 R() 的 key/value 处理）
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code R()} 里的取串：
     * <pre>
     * a0(strH, "var key=\"&amp;&amp;\"", "")   → between(html, "var key=\"", "\"")
     * a0(strH, ",value=\"&amp;&amp;\"", "")    → between(html, ",value=\"", "\"")
     * a0(strH, "c.get(\"&amp;&amp;\&amp;", "")  → between(html, "c.get(\"", "\\&amp;")
     * </pre>
     *
     * <p>这是上游 {@code e0(html, pattern, def)} 在「pattern 里含 {@code &&}」
     * 且不含宏（{@code url:}/{@code +}/{@code $sub:}/{@code urlDecode}）时的行为。
     *
     * @param html  源码，可为 null
     * @param left  左界（不含）
     * @param right 右界（不含）
     * @return 中间片段；任一侧缺失返回空串
     */
    public static String between(String html, String left, String right) {
        if (html == null || left == null || right == null) {
            return "";
        }
        int i = html.indexOf(left);
        if (i < 0) {
            return "";
        }
        int from = i + left.length();
        int j = html.indexOf(right, from);
        if (j < 0) {
            return "";
        }
        return html.substring(from, j);
    }

    /**
     * 取人机识别页里的 {@code key} 值。
     *
     * @param html 识别页源码
     * @return key；取不到空串
     */
    public static String extractSignKey(String html) {
        return between(html, "var key=\"", "\"");
    }

    /**
     * 取人机识别页里的 {@code value} 值。
     *
     * @param html 识别页源码
     * @return value；取不到空串
     */
    public static String extractSignValue(String html) {
        return between(html, ",value=\"", "\"");
    }

    /**
     * 取识别页里 {@code c.get("...")} 的首段（上游用它拿提交通知地址）。
     *
     * @param html 识别页源码
     * @return 片段；取不到空串
     */
    public static String extractCallback(String html) {
        return between(html, "c.get(\"", "\\&");
    }

    /**
     * 还原上游 {@code R()} 的 {@code key} 拼接：
     * {@code value} 逐字符转十进制码后首尾相接。
     *
     * @param value 原文，可为 null
     * @return 十进制码串
     */
    public static String digitConcat(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            sb.append((int) value.charAt(i));
        }
        return sb.toString();
    }

    /**
     * 还原上游 {@code R()} 的 {@code m.a(string4, m.b)}：对
     * {@link #digitConcat(String)} 的结果取 MD5（小写 hex）。
     *
     * <p>依据：同一 {@code m.a(String, Charset)} 在 {@code XBPQ.N()} 里被用来
     * 替换配置宏 {@code md5(...)}（XBPQ.java:33098、33101），故它就是 MD5。
     * 大小写不可确定，见类注释「未能确定项」第 1 条。
     *
     * @param value 原文
     * @return 32 位小写 hex；空输入返回空串
     */
    public static String signValue(String value) {
        String s = digitConcat(value);
        return s.isEmpty() ? "" : Digest.md5(s);
    }

    /**
     * 同 {@link #signValue(String)}，但返回大写 hex（宿主可按实际服务端要求切换）。
     *
     * @param value 原文
     * @return 32 位大写 hex；空输入返回空串
     */
    public static String signValueUpper(String value) {
        String s = signValue(value);
        return s.isEmpty() ? "" : s.toUpperCase(java.util.Locale.US);
    }

    /**
     * 还原上游宝塔盾重放 URL：在原 URL 后追加 {@code btwaf=<token>}，
     * 已带 query 时用 {@code &}，否则用 {@code ?}。
     *
     * @param url   原 URL
     * @param token 宝塔盾令牌
     * @return 重放 URL
     */
    public static String withBtwaf(String url, String token) {
        if (url == null) {
            return "";
        }
        return url + (url.indexOf('?') >= 0 ? "&" : "?") + "btwaf=" + token;
    }
}
