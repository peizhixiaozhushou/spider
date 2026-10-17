package com.example.tvbox.plain.net;

import android.util.Log;

import com.example.tvbox.common.Http;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 精确字节的 HTTP 传输（<b>对 {@code common/Http} 的能力补充</b>）。
 *
 * <h2>为什么需要它</h2>
 * {@code com.example.tvbox.common.Http} 的全部入口（{@code get/post/execute}）都以
 * {@code String} 承载响应体，且在 {@code UrlConnectionTransport}/{@code OkHttpTransport} 里
 * 用 <b>UTF-8 解码</b>字节流。这对文本接口完全够用，但下面两类流量会被破坏：
 * <ul>
 *   <li><b>protobuf 报文</b>（{@code Gulu} 的握手／boot／callApi 全是二进制 pb，
 *       请求体也是 pb）—— 非 UTF-8 字节会变成 U+FFFD，且 {@code String.getBytes("UTF-8")}
 *       无法把请求体原样发出去。</li>
 *   <li><b>视频/媒体字节</b>（{@code Hxq.localProxy} 要透传 mp4/octet-stream）——
 *       任何有损解码都会破坏文件。</li>
 * </ul>
 * 上游用的是 okhttp 的 {@code ResponseBody.bytes()}，天然是字节语义；本类是等价的
 * 最小实现（{@link HttpURLConnection}，支持 gzip 与超时），并且
 * <b>沿用 {@link Http#setOfflineForTest(boolean)} 全局离线开关</b>，让测试依旧零网络。
 *
 * <h2>定位说明（重要，给 Lead 判断）</h2>
 * 这是一个<b>能力缺口补丁</b>，不是 {@code Http} 的替代品：超时/重试/UA/URL 拼接/
 * JSON 解析/候选域名探测等一律继续用 {@code Http}。理想归宿是把
 * {@code Result.raw} 与 {@code executeRaw} 加进 {@code common/Http} 并删掉本类；
 * 之所以先放在域内，是因为 {@code maintain/src/common/Http.java} 在同一时刻
 * 有其它队友在改，改共享文件会产生写冲突。
 *
 * <h2>证据来源</h2>
 * 上游取字节的等价点：{@code ae/decompiled/Gulu.java:179-197}（okhttp {@code Response.body().bytes()}）、
 * {@code ae/decompiled/Hxq.java:2640-2654}（{@code b.d(url, h())} → {@code response.body().bytes()}）。
 */
public final class RawBytes {

    private static final String TAG = "RawBytes";

    /** 默认 UA，与 {@link Http#DEFAULT_UA} 保持一致。 */
    public static final String DEFAULT_UA = Http.DEFAULT_UA;

    /** 默认连接超时（毫秒）。 */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 15_000;
    /** 默认读取超时（毫秒）。 */
    public static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

    private RawBytes() {
    }

    /** 一次请求的结果（字节语义）。 */
    public static final class Result {

        /** HTTP 状态码；0 表示连接层失败或离线。 */
        public final int code;
        /** 响应体原始字节；失败时为空数组（永不为 null）。 */
        public final byte[] bytes;
        /** 错误描述；成功时为 null。 */
        public final String error;
        /** Content-Type；未知为空串。 */
        public final String contentType;

        /**
         * @param code        状态码
         * @param bytes       响应字节，null 视作空
         * @param error       错误，可为 null
         * @param contentType 响应 Content-Type，可为 null
         */
        public Result(int code, byte[] bytes, String error, String contentType) {
            this.code = code;
            this.bytes = bytes == null ? new byte[0] : bytes;
            this.error = error;
            this.contentType = contentType == null ? "" : contentType;
        }

        /** @return 状态码 2xx/3xx */
        public boolean ok() {
            return code >= 200 && code < 400;
        }

        /** @return 用 UTF-8 解读的文本（仅用于文本响应） */
        public String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        /** @return 字节长度 */
        public int length() {
            return bytes.length;
        }

        @Override
        public String toString() {
            return "Result{code=" + code + ", len=" + bytes.length
                    + ", type=" + contentType + ", err=" + error + "}";
        }
    }

    /**
     * GET。
     *
     * @param url     完整 URL
     * @param headers 附加 header，可为 null
     * @return 结果，永不 null
     */
    public static Result get(String url, Map<String, String> headers) {
        return execute("GET", url, null, headers, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    /**
     * POST 原始字节（默认 Content-Type: application/octet-stream）。
     *
     * @param url     完整 URL
     * @param body    请求体字节，可为 null
     * @param headers 附加 header，可为 null
     * @return 结果，永不 null
     */
    public static Result post(String url, byte[] body, Map<String, String> headers) {
        return execute("POST", url, body, headers, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    /**
     * 执行一次请求，返回精确字节。
     *
     * @param method    HTTP 方法
     * @param url       完整 URL
     * @param body      请求体字节，GET 时可为 null
     * @param headers   附加 header，可为 null
     * @param connectMs 连接超时（毫秒）
     * @param readMs    读超时（毫秒）
     * @return 结果，永不 null；任何异常都被转成 {@code error} 而不是抛出
     */
    public static Result execute(String method, String url, byte[] body,
                                Map<String, String> headers, int connectMs, int readMs) {
        if (Http.isOfflineForTest()) {
            return new Result(0, new byte[0], "offline-for-test", "");
        }
        if (url == null || url.trim().isEmpty()) {
            return new Result(0, new byte[0], "empty url", "");
        }
        String m = (method == null || method.trim().isEmpty())
                ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod(m);
            conn.setConnectTimeout(connectMs <= 0 ? DEFAULT_CONNECT_TIMEOUT_MS : connectMs);
            conn.setReadTimeout(readMs <= 0 ? DEFAULT_READ_TIMEOUT_MS : readMs);
            conn.setInstanceFollowRedirects(true);
            Map<String, String> h = headers == null
                    ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(headers);
            if (!containsKeyIgnoreCase(h, "User-Agent")) {
                h.put("User-Agent", DEFAULT_UA);
            }
            if (body != null && !containsKeyIgnoreCase(h, "Content-Type")) {
                h.put("Content-Type", "application/octet-stream");
            }
            for (Map.Entry<String, String> e : h.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null && !"GET".equals(m) && !"HEAD".equals(m)) {
                conn.setDoOutput(true);
                conn.getOutputStream().write(body);
                conn.getOutputStream().flush();
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] raw = readAll(in, conn.getContentEncoding());
            if (code >= 400) {
                return new Result(code, raw, "http " + code, conn.getContentType());
            }
            return new Result(code, raw, null, conn.getContentType());
        } catch (Throwable e) {
            Log.w(TAG, "raw fetch failed: " + url + " " + e);
            return new Result(0, new byte[0], String.valueOf(e.getMessage()), "");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 读完整个流并（按需）解 gzip。
     *
     * @param in     输入流，可为 null
     * @param gzipHeader 响应的 Content-Encoding，可为 null
     * @return 原始字节，永不 null
     */
    private static byte[] readAll(InputStream in, String gzipHeader) throws Exception {
        if (in == null) {
            return new byte[0];
        }
        InputStream src = in;
        if (gzipHeader != null && gzipHeader.toLowerCase(Locale.ROOT).contains("gzip")) {
            src = new GZIPInputStream(in);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = src.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        src.close();
        if (src != in) {
            in.close();
        }
        return bos.toByteArray();
    }

    /**
     * 大小写不敏感地判断 header 是否存在。
     *
     * @param h    header 表
     * @param name 名称
     * @return true 表示已存在
     */
    private static boolean containsKeyIgnoreCase(Map<String, String> h, String name) {
        for (String k : h.keySet()) {
            if (k != null && k.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
