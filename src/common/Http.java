package com.example.tvbox.common;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 统一 HTTP 封装。
 *
 * <p>设计约束（重要）：
 * <ul>
 *   <li>编译期依赖 {@code okhttp3}：因为宿主 {@code Spider.client()} 返回 OkHttpClient，
 *       插件侧必须能引用该类型，故保留 {@link #from(okhttp3.OkHttpClient)} 这条通道。</li>
 *   <li>运行期默认走 {@link java.net.HttpURLConnection}：不依赖 okhttp 的真实实现，
 *       因此在纯 JDK 下（自测、桌面验证）可以真实发请求，测试才有意义。</li>
 * </ul>
 *
 * <p>能力：超时、UA 注入、自定义 header、失败重试、gzip 自动解压、JSON 快捷返回。
 *
 * <p>典型用法：
 * <pre>{@code
 * Http http = Http.create().ua("okhttp/3.10.0").retry(2);
 * String html = http.get("https://host/api.php?ac=list");
 * JSONObject json = http.getJson(url, Collections.singletonMap("Referer", base));
 * }</pre>
 */
public class Http {

    private static final String TAG = "Http";

    /** 默认 UA：与原插件观测到的 okhttp 指纹同族，避免部分站点按 UA 拒绝 */
    public static final String DEFAULT_UA = "okhttp/3.12.0";

    /** 默认连接超时（毫秒） */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    /** 默认读取超时（毫秒） */
    public static final int DEFAULT_READ_TIMEOUT_MS = 15_000;
    /** 默认重试次数（不含首次请求） */
    public static final int DEFAULT_RETRY = 1;

    /**
     * 全局离线开关 —— 供单元测试用。
     *
     * <p>设为 {@code true} 后，所有请求立即返回失败（不发起真实连接）。
     * 目的：单元测试不应依赖外网；死站点会让测试挂到超时（实测 10s+15s × 重试）。
     *
     * <p>用法：测试类开头 {@code Http.setOfflineForTest(true);}
     */
    private static volatile boolean offlineForTest = false;

    /** 开启/关闭测试离线模式 */
    public static void setOfflineForTest(boolean offline) {
        offlineForTest = offline;
    }

    /** 当前是否离线模式 */
    public static boolean isOfflineForTest() {
        return offlineForTest;
    }

    /** 缓冲区大小，读响应时使用 */
    private static final int BUF_SIZE = 8 * 1024;

    /** 视为“失败可重试”的最小状态码 */
    private static final int HTTP_SERVER_ERROR = 500;

    // ── 配置 ────────────────────────────────────────────────
    private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
    private int retry = DEFAULT_RETRY;
    private boolean followRedirects = true;
    private boolean gzip = true;

    /** 默认 header（含 UA / Accept），保持插入顺序便于测试断言 */
    private final LinkedHashMap<String, String> defaultHeaders = new LinkedHashMap<>();

    /** 宿主注入的 okhttp 客户端；非 null 时优先使用 */
    private okhttp3.OkHttpClient okClient;

    /** 最近一次请求的最终 URL，方便定位站点换域名后的实际跳转 */
    private String lastUrl = "";

    /** 最近一次请求的状态码，0 表示未发起或连接失败 */
    private int lastCode;

    /**
     * 构造一个使用默认配置的实例。
     * 默认 header：User-Agent / Accept / Accept-Encoding。
     */
    public Http() {
        putHeader(defaultHeaders, "User-Agent", DEFAULT_UA);
        putHeader(defaultHeaders, "Accept", "*/*");
        putHeader(defaultHeaders, "Accept-Encoding", "gzip");
    }

    /**
     * 创建默认配置实例（等价于 {@code new Http()}）。
     *
     * @return 新的 Http 实例
     */
    public static Http create() {
        return new Http();
    }

    /**
     * 创建默认配置实例，语义同 {@link #create()}，供链式书写更自然的别名。
     *
     * @return 新的 Http 实例
     */
    public static Http of() {
        return new Http();
    }

    /**
     * 用宿主提供的 okhttp 客户端构造实例。
     *
     * <p>注意：在纯 JDK 自测环境中传入的是 stub 客户端（返回空响应体），
     * 因此自测请使用 {@link #create()}；只有真正运行在 TVBox 宿主内时才传真实客户端。
     *
     * @param client 宿主 {@code Spider.client()} 返回的客户端，可为 null（等价于默认传输）
     * @return 复用了该客户端的 Http 实例
     */
    public static Http from(okhttp3.OkHttpClient client) {
        Http http = new Http();
        http.okClient = client;
        return http;
    }

    // ── 链式配置 ────────────────────────────────────────────

    /**
     * 同时设置连接与读取超时。
     *
     * @param connectMs 连接超时毫秒，<=0 时保持原值
     * @param readMs    读取超时毫秒，<=0 时保持原值
     * @return this
     */
    public Http timeout(int connectMs, int readMs) {
        if (connectMs > 0) {
            this.connectTimeoutMs = connectMs;
        }
        if (readMs > 0) {
            this.readTimeoutMs = readMs;
        }
        return this;
    }

    /**
     * 设置 User-Agent（覆盖默认 UA）。
     *
     * @param ua UA 字符串，空/null 时忽略
     * @return this
     */
    public Http ua(String ua) {
        if (ua != null && !ua.isEmpty()) {
            putHeader(defaultHeaders, "User-Agent", ua);
        }
        return this;
    }

    /**
     * 追加/覆盖一个默认请求头（大小写不敏感覆盖）。
     *
     * @param name  header 名，空/null 时忽略
     * @param value header 值，null 视为空串
     * @return this
     */
    public Http header(String name, String value) {
        if (name != null && !name.isEmpty()) {
            putHeader(defaultHeaders, name, value == null ? "" : value);
        }
        return this;
    }

    /**
     * 批量追加/覆盖默认请求头。
     *
     * @param headers header 表，null 时忽略
     * @return this
     */
    public Http header(Map<String, String> headers) {
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                header(e.getKey(), e.getValue());
            }
        }
        return this;
    }

    /**
     * 设置失败重试次数（不含首次请求）。
     *
     * @param times 重试次数，负数按 0 处理
     * @return this
     */
    public Http retry(int times) {
        this.retry = Math.max(0, times);
        return this;
    }

    /**
     * 设置是否跟随 3xx 跳转。
     *
     * @param follow true 跟随
     * @return this
     */
    public Http followRedirect(boolean follow) {
        this.followRedirects = follow;
        return this;
    }

    /**
     * 设置是否声明并解压 gzip。
     *
     * @param enable true 启用（默认启用）
     * @return this
     */
    public Http gzip(boolean enable) {
        this.gzip = enable;
        return this;
    }

    /**
     * 读取当前默认请求头的只读快照（自测/排障用）。
     *
     * @return 不可变 header 表
     */
    public Map<String, String> headers() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(defaultHeaders));
    }

    /**
     * 最近一次请求实际访问的 URL（跟随跳转后）。
     *
     * @return URL，未发起请求时为空串
     */
    public String lastUrl() {
        return lastUrl;
    }

    /**
     * 最近一次请求的状态码。
     *
     * @return HTTP 状态码；0 表示未发起或连接层失败
     */
    public int lastCode() {
        return lastCode;
    }

    // ── 请求主体 ────────────────────────────────────────────

    /**
     * GET 请求。
     *
     * @param url 完整 URL
     * @return 响应体；失败返回空串（不抛异常）
     */
    public String get(String url) {
        return get(url, null);
    }

    /**
     * GET 请求（附加本次 header，覆盖默认同名头）。
     *
     * @param url     完整 URL
     * @param headers 本次附加 header，可为 null
     * @return 响应体；失败返回空串
     */
    public String get(String url, Map<String, String> headers) {
        return execute("GET", url, null, headers).body;
    }

    /**
     * POST 请求（默认 Content-Type: application/x-www-form-urlencoded）。
     *
     * @param url  完整 URL
     * @param body 请求体，可为 null
     * @return 响应体；失败返回空串
     */
    public String post(String url, String body) {
        return post(url, body, null);
    }

    /**
     * POST 请求（附加本次 header）。
     *
     * @param url     完整 URL
     * @param body    请求体，可为 null
     * @param headers 本次附加 header，可为 null
     * @return 响应体；失败返回空串
     */
    public String post(String url, String body, Map<String, String> headers) {
        Map<String, String> h = new LinkedHashMap<>();
        if (headers != null) {
            h.putAll(headers);
        }
        if (!containsKeyIgnoreCase(h, "Content-Type")) {
            putHeader(h, "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        }
        Result r = execute("POST", url, body, h);
        return r.body;
    }

    /**
     * POST JSON 请求（Content-Type: application/json）。
     *
     * @param url  完整 URL
     * @param json 请求体 JSON 文本
     * @return 响应体；失败返回空串
     */
    public String postJson(String url, String json) {
        Map<String, String> h = new LinkedHashMap<>();
        putHeader(h, "Content-Type", "application/json; charset=UTF-8");
        return execute("POST", url, json, h).body;
    }

    /**
     * GET 并把响应解析为 JSONObject。
     *
     * @param url 完整 URL
     * @return 解析后的 JSONObject；失败或非 JSON 时返回空对象（不抛异常）
     */
    public JSONObject getJson(String url) {
        return getJson(url, null);
    }

    /**
     * GET 并把响应解析为 JSONObject（附加 header）。
     *
     * @param url     完整 URL
     * @param headers 本次附加 header，可为 null
     * @return 解析后的 JSONObject；失败或非 JSON 时返回空对象
     */
    public JSONObject getJson(String url, Map<String, String> headers) {
        return parseJson(get(url, headers));
    }

    /**
     * POST 并把响应解析为 JSONObject。
     *
     * @param url  完整 URL
     * @param body 请求体
     * @return 解析后的 JSONObject；失败或非 JSON 时返回空对象
     */
    public JSONObject postJsonObject(String url, String body) {
        return parseJson(post(url, body));
    }

    /**
     * 执行一次请求并返回完整结果（含状态码与错误信息），失败不抛异常。
     *
     * @param method  HTTP 方法，支持 GET / POST（其它按 POST 处理并保留原方法名）
     * @param url     完整 URL
     * @param body    请求体，GET 时为 null
     * @param headers 本次附加 header，可为 null
     * @return 请求结果，永不返回 null
     */
    public Result execute(String method, String url, String body, Map<String, String> headers) {
        // 测试离线模式：不发真实请求
        if (offlineForTest) {
            lastUrl = url;
            lastCode = 0;
            return new Result(0, "", "offline-for-test", url);
        }
        if (url == null || url.trim().isEmpty()) {
            return new Result(0, "", "empty url", "");
        }
        final String m = (method == null || method.trim().isEmpty())
                ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        final Map<String, String> merged = mergeHeaders(headers);

        Result last = null;
        // 重试策略：连接层异常 与 5xx 重试；4xx 立即返回（重试无意义）
        for (int attempt = 0; attempt <= retry; attempt++) {
            last = transport().execute(m, url, body, merged, connectTimeoutMs, readTimeoutMs);
            this.lastUrl = last.url == null || last.url.isEmpty() ? url : last.url;
            this.lastCode = last.code;
            boolean retryable = last.error != null || last.code >= HTTP_SERVER_ERROR;
            if (!retryable) {
                return last;
            }
            if (attempt < retry) {
                Log.w(TAG, "retry " + (attempt + 1) + "/" + retry + " url=" + url
                        + " code=" + last.code + " err=" + last.error);
            }
        }
        return last == null ? new Result(0, "", "no attempt", url) : last;
    }

    /**
     * 探测一组候选基址中第一个可用的，用于 ext.site 为域名列表时的容灾。
     *
     * @param bases     候选基址列表（可含前后空白）
     * @param probePath 探测路径，例如 {@code "/"} 或 {@code "/api.php?ac=list"}
     * @return 第一个响应 2xx/3xx 的基址；全部失败返回空串
     */
    public String firstAlive(List<String> bases, String probePath) {
        if (bases == null) {
            return "";
        }
        for (String base : bases) {
            if (base == null || base.trim().isEmpty()) {
                continue;
            }
            String b = base.trim();
            String url = buildUrl(b, probePath, null);
            Result r = execute("GET", url, null, null);
            if (r.code >= 200 && r.code < 400) {
                Log.i(TAG, "alive base=" + b + " code=" + r.code);
                return b;
            }
            Log.w(TAG, "dead base=" + b + " code=" + r.code + " err=" + r.error);
        }
        return "";
    }

    // ── URL 工具（静态，便于各插件复用与自测） ────────────────

    /**
     * 拼接 URL：base + path + query，并做最小规范化。
     *
     * <p>规则：
     * <ul>
     *   <li>path 为绝对 URL（http/https 开头）时直接用它，忽略 base</li>
     *   <li>base 结尾的 / 与 path 开头的 / 去重，避免出现双斜杠</li>
     *   <li>query 按 UTF-8 编码，null 值输出为 name=（保留键）</li>
     * </ul>
     *
     * @param base  基址，例如 {@code https://a.com}（可带路径前缀）
     * @param path  相对路径或绝对 URL，可为空
     * @param query 查询参数，可为 null
     * @return 拼接后的 URL
     */
    public static String buildUrl(String base, String path, Map<String, String> query) {
        String b = base == null ? "" : base.trim();
        String p = path == null ? "" : path.trim();
        String out;
        if (p.startsWith("http://") || p.startsWith("https://")) {
            out = p;
        } else if (p.isEmpty()) {
            out = b;
        } else if (b.isEmpty()) {
            out = p;
        } else {
            boolean bSlash = b.endsWith("/");
            boolean pSlash = p.startsWith("/");
            if (bSlash && pSlash) {
                out = b + p.substring(1);
            } else if (!bSlash && !pSlash) {
                out = b + "/" + p;
            } else {
                out = b + p;
            }
        }
        if (query == null || query.isEmpty()) {
            return out;
        }
        StringBuilder sb = new StringBuilder(out);
        sb.append(out.indexOf('?') >= 0 ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        return sb.toString();
    }

    /**
     * URL 编码（UTF-8）。
     *
     * @param s 原文，null 视为空串
     * @return 编码结果；编码失败返回原文（不抛异常）
     */
    public static String encode(String s) {
        if (s == null) {
            return "";
        }
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "encode failed", e);
            return s;
        }
    }

    /**
     * 把响应体文本解析为 JSONObject，容错。
     *
     * @param text 响应文本，可为 null
     * @return JSONObject；解析失败返回空对象
     */
    public static JSONObject parseJson(String text) {
        if (text == null) {
            return new JSONObject();
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(t);
        } catch (Throwable e) {
            Log.w(TAG, "not json: " + head(t, 120));
            return new JSONObject();
        }
    }

    // ── 内部实现 ────────────────────────────────────────────

    /**
     * 合并默认 header 与本次 header；本次优先（大小写不敏感）。
     *
     * @param extra 本次 header，可为 null
     * @return 合并后的有序 map
     */
    private Map<String, String> mergeHeaders(Map<String, String> extra) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>(defaultHeaders);
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                putHeader(out, e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        if (!gzip) {
            removeKeyIgnoreCase(out, "Accept-Encoding");
        }
        return out;
    }

    /**
     * 选择传输实现：有宿主客户端走 okhttp，否则走 HttpURLConnection。
     *
     * @return 传输实现
     */
    private Transport transport() {
        return okClient != null ? new OkHttpTransport(okClient, followRedirects) : new UrlConnectionTransport(gzip, followRedirects);
    }

    /** 大小写不敏感写入 header */
    private static void putHeader(Map<String, String> map, String name, String value) {
        if (name == null || name.isEmpty()) {
            return;
        }
        removeKeyIgnoreCase(map, name);
        map.put(name, value == null ? "" : value);
    }

    /** 大小写不敏感删除 header */
    private static void removeKeyIgnoreCase(Map<String, String> map, String name) {
        String hit = null;
        for (String k : map.keySet()) {
            if (k.equalsIgnoreCase(name)) {
                hit = k;
                break;
            }
        }
        if (hit != null) {
            map.remove(hit);
        }
    }

    /** 大小写不敏感判断存在 */
    private static boolean containsKeyIgnoreCase(Map<String, String> map, String name) {
        for (String k : map.keySet()) {
            if (k.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** 取前 n 个字符用于日志 */
    private static String head(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /**
     * 传输层抽象：把“怎么发”与“发什么”解耦，便于 JDK / 宿主两种环境共用一套调用代码。
     */
    public interface Transport {
        /**
         * 执行一次请求。
         *
         * @param method    HTTP 方法
         * @param url       完整 URL
         * @param body      请求体，GET 时为 null
         * @param headers   已合并的 header
         * @param connectMs 连接超时
         * @param readMs    读取超时
         * @return 结果，永不返回 null
         */
        Result execute(String method, String url, String body,
                       Map<String, String> headers, int connectMs, int readMs);
    }

    /**
     * JDK 原生传输实现（默认）。纯 JDK 可运行，是自测唯一可用的真实通道。
     */
    public static class UrlConnectionTransport implements Transport {

        private final boolean gzip;
        private final boolean followRedirects;

        /**
         * @param gzip         是否解压 gzip 响应
         * @param followRedirects 是否跟随跳转
         */
        public UrlConnectionTransport(boolean gzip, boolean followRedirects) {
            this.gzip = gzip;
            this.followRedirects = followRedirects;
        }

        @Override
        public Result execute(String method, String url, String body,
                              Map<String, String> headers, int connectMs, int readMs) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(connectMs);
                conn.setReadTimeout(readMs);
                conn.setInstanceFollowRedirects(followRedirects);
                if (headers != null) {
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
                if (body != null && !"GET".equals(method) && !"HEAD".equals(method)) {
                    conn.setDoOutput(true);
                    byte[] raw = body.getBytes("UTF-8");
                    OutputStream os = conn.getOutputStream();
                    try {
                        os.write(raw);
                        os.flush();
                    } finally {
                        closeQuietly(os);
                    }
                }
                int code = conn.getResponseCode();
                String finalUrl = conn.getURL() == null ? url : conn.getURL().toString();
                InputStream in = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
                String text = readText(in, conn.getContentEncoding(), gzip);
                if (code >= 400 && (text == null || text.isEmpty())) {
                    return new Result(code, "", "http " + code, finalUrl);
                }
                return new Result(code, text == null ? "" : text, null, finalUrl);
            } catch (Throwable e) {
                Log.e(TAG, "urlconnect failed: " + url, e);
                return new Result(0, "", String.valueOf(e.getMessage()), url);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }

    /**
     * 宿主 okhttp 传输实现。仅在运行时确实存在真实 okhttp 客户端时才有意义。
     */
    public static class OkHttpTransport implements Transport {

        private final okhttp3.OkHttpClient client;
        private final boolean followRedirects;

        /**
         * @param client          宿主客户端
         * @param followRedirects 是否跟随跳转（okhttp 默认跟随，此处仅记录语义）
         */
        public OkHttpTransport(okhttp3.OkHttpClient client, boolean followRedirects) {
            this.client = client;
            this.followRedirects = followRedirects;
        }

        /**
         * 是否配置为跟随跳转。
         *
         * @return true 表示跟随
         */
        public boolean followRedirects() {
            return followRedirects;
        }

        @Override
        public Result execute(String method, String url, String body,
                              Map<String, String> headers, int connectMs, int readMs) {
            okhttp3.Response resp = null;
            try {
                okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(url);
                if (headers != null) {
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        rb.header(e.getKey(), e.getValue());
                    }
                }
                if ("GET".equals(method)) {
                    rb.get();
                } else {
                    // MediaType 跟随已声明的 Content-Type，避免宿主侧头/体不一致
                    String ct = "application/x-www-form-urlencoded; charset=UTF-8";
                    if (headers != null) {
                        for (Map.Entry<String, String> e : headers.entrySet()) {
                            if ("Content-Type".equalsIgnoreCase(e.getKey())) {
                                ct = e.getValue();
                                break;
                            }
                        }
                    }
                    okhttp3.RequestBody rbBody = okhttp3.RequestBody.create(
                            okhttp3.MediaType.parse(ct),
                            body == null ? "" : body);
                    rb.method(method, rbBody);
                }
                resp = client.newCall(rb.build()).execute();
                int code = resp.code();
                String text = resp.body() == null ? "" : resp.body().string();
                return new Result(code, text == null ? "" : text, null, url);
            } catch (Throwable e) {
                Log.e(TAG, "okhttp failed: " + url, e);
                return new Result(0, "", String.valueOf(e.getMessage()), url);
            } finally {
                if (resp != null) {
                    try {
                        resp.close();
                    } catch (Throwable ignored) {
                        // 关闭失败不影响结果
                    }
                }
            }
        }
    }

    /**
     * 一次请求的完整结果。
     */
    public static class Result {
        /** HTTP 状态码；0 表示连接层失败 */
        public final int code;
        /** 响应体（已解压）；失败时为空串 */
        public final String body;
        /** 错误描述；成功时为 null */
        public final String error;
        /** 实际访问的 URL（含跳转后） */
        public final String url;

        /**
         * @param code  状态码
         * @param body  响应体
         * @param error 错误描述
         * @param url   实际 URL
         */
        public Result(int code, String body, String error, String url) {
            this.code = code;
            this.body = body == null ? "" : body;
            this.error = error;
            this.url = url == null ? "" : url;
        }

        /**
         * 是否成功（2xx 且无错误）。
         *
         * @return true 成功
         */
        public boolean ok() {
            return error == null && code >= 200 && code < 300;
        }

        /**
         * 把响应体解析为 JSONObject，容错。
         *
         * @return JSONObject；失败返回空对象
         */
        public JSONObject json() {
            return parseJson(body);
        }

        /**
         * 便于日志输出。
         *
         * @return 摘要文本
         */
        @Override
        public String toString() {
            return "Result{code=" + code + ", url=" + url
                    + ", err=" + error + ", len=" + body.length() + "}";
        }
    }

    /**
     * 读取输入流为文本，并按需解压 gzip。
     *
     * @param in              输入流，可为 null
     * @param contentEncoding 服务端声明的 Content-Encoding
     * @param allowGzip       是否允许解压
     * @return 文本；in 为 null 时返回空串
     */
    static String readText(InputStream in, String contentEncoding, boolean allowGzip) throws IOException {
        if (in == null) {
            return "";
        }
        InputStream src = in;
        try {
            if (allowGzip && contentEncoding != null
                    && contentEncoding.toLowerCase(Locale.ROOT).contains("gzip")) {
                src = new GZIPInputStream(in);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[BUF_SIZE];
            int n;
            while ((n = src.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            closeQuietly(src);
            if (src != in) {
                closeQuietly(in);
            }
        }
    }

    /**
     * 安静关闭流。
     *
     * @param c 可关闭对象，可为 null
     */
    static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // 忽略关闭异常
            }
        }
    }

    /**
     * 便捷：把 varargs 的 key/value 组成有序 header 表。
     *
     * @param kv 偶数个参数，依次为 key, value
     * @return header 表
     */
    public static Map<String, String> headersOf(String... kv) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                putHeader(m, kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    /**
     * 便捷：把 varargs 的 key/value 组成有序参数表（供 {@link #buildUrl} 使用）。
     *
     * @param kv 偶数个参数，依次为 key, value
     * @return 参数表
     */
    public static Map<String, String> paramsOf(String... kv) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                m.put(kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    /**
     * 复制一份 header 表（避免调用方修改内部状态）。
     *
     * @param src 源表，可为 null
     * @return 复制后的有序表
     */
    public static Map<String, String> copyOf(Map<String, String> src) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        if (src != null) {
            m.putAll(src);
        }
        return m;
    }

    /**
     * 把一组 key/value 追加到已有表（原地修改）。
     *
     * @param target 目标表
     * @param kv     偶数个参数
     * @return 目标表本身
     */
    public static Map<String, String> putAll(Map<String, String> target, String... kv) {
        if (target != null && kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                putHeader(target, kv[i], kv[i + 1]);
            }
        }
        return target;
    }

    /**
     * 返回 list 的浅拷贝，null 安全（节省各插件重复写判空）。
     *
     * @param src 源列表，可为 null
     * @return 非 null 列表
     */
    public static List<String> safeList(List<String> src) {
        return src == null ? new ArrayList<String>() : new ArrayList<>(src);
    }
}
