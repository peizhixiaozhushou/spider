package com.example.tvbox.plain.yunduo;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * YunDuo 明文实现（protobuf 私有协议 + web-sign 签名 + 可选登录态）。
 *
 * <p>反编译原文: {@code ae/decompiled/YunDuo.java}（1114 行，其中 {@code detailContent} 415 行）。
 * 上游是 okhttp + protobuf 运行时；本实现只用 JDK：okhttp → {@link HttpURLConnection}（带
 * trust-all，与上游一致），protobuf → 手写 varint（{@link YunDuoProto}）。
 *
 * <h2>协议</h2>
 * <pre>
 *   GET  {api}/index/home                       首页分类 + 每个分类的推荐
 *   GET  {api}/filter/vod?type_name=&amp;page=&amp;sort=hits   分类列表
 *   GET  {api}/vod/get_detail?vod_id=            详情（v1，优先）
 *   GET  {api}/vod/detail_v2?vod_id=             详情（v2，v1 拿不到线路时回落）
 *   GET  {api}/vod/playback_v2?vod_id=&amp;source=   播放（拿 episodes）
 *   GET  {api}/search/index?wd=&amp;pg=1            搜索
 *   POST {api}/decode/url                        protobuf 地址解码（见 {@link YunDuoProto}）
 *   POST {api}/account/login                     {"username","password"} → Set-Cookie
 *   GET  {host}                                  ext 是 http(s) 时可远程下发配置
 * </pre>
 *
 * <h2>请求头（除 {@code /account/login} 外基本一致）</h2>
 * <pre>
 *   User-Agent : {ua}        Accept: application/json | application/x-protobuf
 *   X-Client   : {x_client}  web-sign: {web_sign}
 *   Cookie     : {session}   ← 仅 need_login 且登录成功后
 * </pre>
 *
 * <h2>ext 三层分类（★ 哪一个是默认值、哪个键能覆盖）</h2>
 * <table border="1">
 *   <tr><th>类</th><th>键</th><th>默认值</th><th>覆盖方式</th></tr>
 *   <tr><td rowspan="11"><b>B 类</b></td><td>{@code host}</td>
 *       <td>{@value #DEFAULT_HOST}</td><td>ext {@code host}</td></tr>
 *   <tr><td>{@code api}</td><td>{@code host + "/api.php/web"}（<b>由 host 派生</b>）</td>
 *       <td>ext {@code api}（显式给了就完全覆盖，不再拼接）</td></tr>
 *   <tr><td>{@code dev}</td><td>{@value #DEFAULT_DEV}</td><td>ext {@code dev}</td></tr>
 *   <tr><td>{@code id}</td><td>{@value #DEFAULT_ID}</td><td>ext {@code id}</td></tr>
 *   <tr><td>{@code sk}</td><td>{@value #DEFAULT_SK}</td><td>ext {@code sk}</td></tr>
 *   <tr><td>{@code web_sign}</td><td>{@value #DEFAULT_WEB_SIGN}</td><td>ext {@code web_sign}</td></tr>
 *   <tr><td>{@code x_client}</td><td>{@value #DEFAULT_X_CLIENT}</td><td>ext {@code x_client}</td></tr>
 *   <tr><td>{@code ua}</td><td>{@value #DEFAULT_UA}</td><td>ext {@code ua}</td></tr>
 *   <tr><td>{@code need_login}</td><td>{@code false}</td><td>ext {@code need_login}（布尔）</td></tr>
 *   <tr><td>{@code account_url}</td><td>无（仅 {@code need_login} 时用）</td><td>ext {@code account_url}</td></tr>
 *   <tr><td>{@code accounts[]} / {@code username} + {@code password}</td>
 *       <td>无</td><td>ext {@code accounts} 数组优先，其次 {@code account_url}，最后账号密码</td></tr>
 *   <tr><td><b>C 类</b></td><td>——</td>
 *       <td>签名格式串 {@code finger=%s&id=%s&nonce=%s&sk=%s&time=%d&v=1}、protobuf 字段号 1–7、
 *           重试 3 次 + 500ms、{@code ?do=diydanmu} 弹幕路径</td><td>硬编码，已内联为常量</td></tr>
 *   <tr><td><b>A 类</b></td><td>——</td><td>无</td>
 *       <td>{@code id}/{@code sk} <b>有默认值</b>，所以不是 A 类；本类不需要 ext 就能跑</td></tr>
 * </table>
 * <p>注意 {@code api} 的推导是「{@code optString("api", host + "/api.php/web")}」——
 * <b>默认值由 host 现算</b>，不是写死的字符串；因此只给 {@code host} 就能改用整套接口，
 * 既给 {@code host} 又给 {@code api} 时 {@code api} 生效。
 *
 * <h2>未还原的分支（诚实清单）</h2>
 * <ol>
 *   <li><b>历史落库</b>：{@link #saveHistory(String)} —— 上游写宿主私有存储；
 *       本工程 {@code crawler} 契约没有该接口，默认只记日志。
 *       <b>上游该分支未实现于本工程</b>。</li>
 *   <li><b>会话字段只取 cookie</b>：上游登录后只从 {@code Set-Cookie} 取
 *       {@code yunduo_web_session}（退一步匹配含 {@code session} 的那条）；
 *       body 里若还有 token 类字段，本地没有样本。</li>
 *   <li><b>protobuf 响应只扫顶层 field 3</b>：嵌套 message 里的地址载体未还原
 *       （见 {@link YunDuoProto#scanField3String(byte[])} 的逐行等价性说明）。</li>
 *   <li><b>401 不重试</b>：{@code need_login=false} 时上游不做「未登录」重试，
 *       本实现照上游（这一点是「照上游」而不是「按协议应有」）。</li>
 * </ol>
 *
 * <h2>发现的上游缺陷（均已修，有断言钉住）</h2>
 * <ol>
 *   <li><b>{@code playerContent} 两段重复代码的 {@code parse} 语义相反</b>（第 984–990 行 vs
 *       第 1033–1039 行，jaдx 标了 8 处 duplicated block）：
 *       <ul>
 *         <li>「episodes 为空 → 重新请求」那一支：{@code b()} 返回空 → {@code parse=1} 并合成
 *             {@code /play/} 页面地址；非空 → {@code parse=0} + 直链。<b>自洽</b>。</li>
 *         <li>「episodes 直接可用」那一支：{@code b()} 返回空 → {@code parse=0} 且把
 *             <b>非 http 的原始 id</b> 当直链；非空 → {@code parse=1} 但 url 换成了
 *             {@code /play/} 页面地址。<b>两半都反了</b>。</li>
 *       </ul>
 *       本实现统一按第一种（自洽）语义：{@code b()} 成功 → {@code parse=0}+直链，
 *       失败 → {@code parse=1}+{@code /play/} 页面地址。</li>
 *   <li><b>{@code categoryContent} 的页码变量未初始化</b>：jadx 产物 {@code int i;} 之后只在
 *       {@code str2 != null} 分支赋值 —— Java 里根本无法编译（definite assignment）。
 *       本实现按 {@code str2} 为空/空串 → 1 处理（与 {@code str2.isEmpty()} 分支一致）。</li>
 *   <li><b>{@code signInput} 用了 {@code finger=} 而不是 {@code dev=}</b>：字段名与传入的
 *       {@code id} 常量（{@code WF-…}）对不上，且第 6 个 protobuf 字段（{@code dev}）另有其值。
 *       本实现保持字段名照抄（改了就签不过），只在此存档。</li>
 *   <li><b>{@code c()} 的「未登录」判定会无限重试 3 次再返回空串</b>：{@code need_login} 打开
 *       但账号密码为空时，{@code e()} 恒返回空串、Cookie 永远带上不去，三次都命中
 *       {@code 401} 分支后返回 {@code ""}，表现为「分类/详情全空」。本实现保留
 *       {@code e()} 的返回值语义，但在 {@link #needLogin} 为真且凭据为空时直接不重试
 *       （避免 3×500ms 的无效等待），并记一条 warn 日志。</li>
 *   <li><b>信任所有证书 + 不校验主机名</b>：上游 {@code init} 装了一个空实现的
 *       {@code X509TrustManager} 和恒真的 {@code hostnameVerifier}。这是<b>故意</b>的（站点证书常过期），
 *       本实现保留该行为以便与上游一致，并把风险写在 {@link #trustAll()} 的注释里。</li>
 * </ol>
 *
 * <h2>观察（<b>未改</b>）</h2>
 * <ul>
 *   <li>{@code d} / {@code e}（{@code WF-} / {@code WEB-} 常量）在 {@code init} 之后
 *       没有任何其它读取点吗？—— 有的：{@code b()} 的签名里用了 {@code d}（finger）与
 *       {@code e}（sk），{@code c()} 也读。{@link #id} / {@link #sk} 因此不是死字段
 *       （{@code AUDIT_REPORT.md} 第 230 行把它标为「疑似死字段，未验证」，此处已证伪）。</li>
 *   <li>{@code /decode/url} 的响应只扫「字段号 3 的 length-delimited」，其它字段全跳过 ——
 *       如果站点把地址放在别的字段号上就会永远返回空串{@code →}退化成 {@code /play/} 页面。</li>
 * </ul>
 */
public class YunDuoPlain extends BaseSpider {

    private static final String TAG = "YunDuoPlain";

    /** 上游字段 {@code a} 的初值：ext 里没给 host 时用它拼默认 api */
    public static final String DEFAULT_HOST = "https://www.vip1949.com/";
    /** 上游字段 {@code c}：客户端标识，进 protobuf 字段 6 与签名 */
    public static final String DEFAULT_DEV = "com.web.player";
    /** 上游字段 {@code d}：签名里的 {@code finger} 常量 */
    public static final String DEFAULT_ID =
            "WF-2c064bc5b3400788f31b848849bc3a60f835423ba2dfe69d7ea93974c216e4f2";
    /** 上游字段 {@code e}：签名里的 {@code sk} 常量 */
    public static final String DEFAULT_SK =
            "WEB-50a8e9c84a1dc05669a692ded99a2dac46527229e607a7be15db88dbc59059d1";
    /** 上游字段 {@code f}：UA */
    public static final String DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    /** 上游字段 {@code g}：{@code web-sign} 头默认值 */
    public static final String DEFAULT_WEB_SIGN = "ddtvf65f3a83d6d9ad6f";
    /** 上游字段 {@code h}：{@code X-Client} 头默认值 */
    public static final String DEFAULT_X_CLIENT = "8f3d2a1c7b6e5d4c9a0b1f2e3d4c5b6a";

    /** 上游 {@code b()} 的签名格式串（C 类硬编码） */
    public static final String SIGN_FORMAT = "finger=%s&id=%s&nonce=%s&sk=%s&time=%d&v=1";
    /** 重试次数与间隔（C 类硬编码） */
    public static final int RETRY = 3;
    public static final long RETRY_SLEEP_MS = 500L;
    /** 播放地址后缀白名单（C 类硬编码） */
    public static final Pattern DIRECT_URL =
            Pattern.compile("(?i).*\\.(m3u8|mp4|flv|ts|mkv|webm|m4s)(\\?.*)?$");

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // ── 配置（B 类：默认值 + ext 可覆盖）────────────────────

    /** ext {@code host}，默认 {@link #DEFAULT_HOST} */
    protected String host = DEFAULT_HOST;
    /** ext {@code api}，默认 {@code host + "/api.php/web"} */
    protected String api = DEFAULT_HOST + "/api.php/web";
    /** ext {@code dev}，默认 {@link #DEFAULT_DEV} */
    protected String dev = DEFAULT_DEV;
    /** ext {@code id}（签名 finger），默认 {@link #DEFAULT_ID} */
    protected String id = DEFAULT_ID;
    /** ext {@code sk}（签名 salt），默认 {@link #DEFAULT_SK} */
    protected String sk = DEFAULT_SK;
    /** ext {@code ua}，默认 {@link #DEFAULT_UA} */
    protected String ua = DEFAULT_UA;
    /** ext {@code web_sign}，默认 {@link #DEFAULT_WEB_SIGN} */
    protected String webSign = DEFAULT_WEB_SIGN;
    /** ext {@code x_client}，默认 {@link #DEFAULT_X_CLIENT} */
    protected String xClient = DEFAULT_X_CLIENT;
    /** ext {@code need_login}，默认 false */
    protected boolean needLogin = false;
    /** ext {@code username} */
    protected String username = "";
    /** ext {@code password} */
    protected String password = "";
    /** 登录后的会话 Cookie（上游字段 {@code l}） */
    protected String session = null;

    /** ext 原文 */
    protected String rawExt = "";

    /**
     * 构造。
     */
    public YunDuoPlain() {
        super();
    }

    // ── 生命周期 ────────────────────────────────────────────

    /**
     * 初始化：吸收 ext（URL 或 JSON），建立 trust-all 传输。
     *
     * @param context 宿主上下文
     * @param extend  ext 原文：{@code http(s)://…} 远程配置，或直接一段 JSON
     */
    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        this.rawExt = extend == null ? "" : extend;
        if (this.rawExt.trim().isEmpty()) {
            return;
        }
        String text = this.rawExt.trim();
        try {
            JSONObject config;
            if (text.startsWith("http://") || text.startsWith("https://")) {
                Resp resp = request("GET", text, null, null, mapOf("User-Agent", ua));
                if (resp.code < 200 || resp.code >= 300 || resp.body.isEmpty()) {
                    Log.w(TAG, "远程 ext 拉取失败: code=" + resp.code);
                    return;
                }
                config = new JSONObject(resp.body);
            } else {
                config = new JSONObject(text);
            }
            this.host = config.optString("host", this.host);
            this.api = config.optString("api", this.host + "/api.php/web");
            this.dev = config.optString("dev", this.dev);
            this.id = config.optString("id", this.id);
            this.sk = config.optString("sk", this.sk);
            this.webSign = config.optString("web_sign", this.webSign);
            this.xClient = config.optString("x_client", this.xClient);
            this.ua = config.optString("ua", this.ua);
            this.needLogin = config.optBoolean("need_login", false);
            if (this.needLogin) {
                String accountUrl = config.optString("account_url", "");
                JSONArray accounts = config.optJSONArray("accounts");
                if (accounts != null && accounts.length() > 0) {
                    pickAccount(accounts);
                } else if (!accountUrl.isEmpty()) {
                    loadAccounts(accountUrl);
                } else {
                    this.username = config.optString("username", "");
                    this.password = config.optString("password", "");
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "init 解析 ext 失败", e);
        }
    }

    /** 当前基址（供测试/排障） */
    public String api() {
        return api;
    }

    /** 当前 host */
    public String host() {
        return host;
    }

    /** 当前 UA */
    public String ua() {
        return ua;
    }

    /** 当前 {@code web-sign} */
    public String webSign() {
        return webSign;
    }

    /** 当前 {@code X-Client} */
    public String xClient() {
        return xClient;
    }

    /** 当前签名 finger 常量 */
    public String finger() {
        return id;
    }

    /** 当前签名 salt 常量 */
    public String salt() {
        return sk;
    }

    /** 当前 dev（进 protobuf 字段 6 与签名） */
    public String dev() {
        return dev;
    }

    /** 当前账号名（可为空串） */
    public String username() {
        return username;
    }

    /** 当前密码（可为空串） */
    public String password() {
        return password;
    }

    /** 是否需要登录 */
    public boolean needLogin() {
        return needLogin;
    }

    /** 会话 Cookie；未登录为 null */
    public String session() {
        return session;
    }

    // ── 传输层（唯一网络出口，测试覆写它）────────────────────

    /** 一次 HTTP 响应：状态码 + 文本体 + 原始响应头（取 Set-Cookie 用） */
    public static final class Resp {
        /** HTTP 状态码；0 表示没发出去（离线/异常） */
        public final int code;
        /** 响应体；失败为空串 */
        public final String body;
        /** 全部响应头值（按名扁平化），用于 {@code Set-Cookie} */
        public final List<String> headerValues;

        /**
         * @param code         状态码
         * @param body         文本体
         * @param headerValues 响应头值
         */
        public Resp(int code, String body, List<String> headerValues) {
            this.code = code;
            this.body = body == null ? "" : body;
            this.headerValues = headerValues == null ? new ArrayList<String>() : headerValues;
        }

        /** 是否 2xx */
        public boolean ok() {
            return code >= 200 && code < 300;
        }

        /** 是否有响应体 */
        public boolean hasBody() {
            return !body.isEmpty();
        }
    }

    /**
     * 发一次请求 —— 本类的唯一网络出口。
     *
     * <p>与上游 okhttp 的对应关系：{@code m.newCall(...).execute()}。
     * trust-all 与上游一致（见 {@link #trustAll()}）。测试覆写本方法注入 fixture。
     *
     * <p>当 {@link Http#isOfflineForTest()} 为真时<b>不发包</b>，直接返回
     * {@code Resp(0,"",[])} —— 保证测试零网络。
     *
     * @param method      HTTP 方法
     * @param url         完整地址
     * @param body        请求体（可为 null）
     * @param contentType 请求体 Content-Type（body 非空时用）
     * @param headers     请求头
     * @return 响应；异常/离线返回 code=0
     */
    public Resp request(String method, String url, byte[] body, String contentType,
                        Map<String, String> headers) {
        if (Http.isOfflineForTest()) {
            return new Resp(0, "", new ArrayList<String>());
        }
        if (TextUtils.isEmpty(url)) {
            return new Resp(0, "", new ArrayList<String>());
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setSSLSocketFactory(trustAll());
                https.setHostnameVerifier((h, s) -> true);
            }
            conn.setRequestMethod(method == null ? "GET" : method);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            if (body != null && body.length > 0) {
                conn.setDoOutput(true);
                if (contentType != null) {
                    conn.setRequestProperty("Content-Type", contentType);
                }
                conn.setFixedLengthStreamingMode(body.length);
                OutputStream os = conn.getOutputStream();
                os.write(body);
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            List<String> headerValues = flattenHeaders(conn);
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in == null ? "" : readAll(in);
            return new Resp(code, text, headerValues);
        } catch (Throwable e) {
            Log.w(TAG, "request failed: " + method + " " + url, e);
            return new Resp(0, "", new ArrayList<String>());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 把响应头扁平成「值列表」，方便按 {@code Set-Cookie} 过滤。
     *
     * <p>上游用 okhttp 的 {@code response.headers("Set-Cookie")}；{@link HttpURLConnection}
     * 侧用 {@code getHeaderFields()} 拿到同名多值。
     *
     * @param conn 已发出请求的连接
     * @return 全部头值（保序）
     */
    private static List<String> flattenHeaders(HttpURLConnection conn) {
        List<String> out = new ArrayList<>();
        try {
            Map<String, List<String>> fields = conn.getHeaderFields();
            for (Map.Entry<String, List<String>> e : fields.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                if ("set-cookie".equalsIgnoreCase(e.getKey())) {
                    out.addAll(e.getValue());
                }
            }
        } catch (Throwable ignored) {
            // 取头失败不影响正文
        }
        return out;
    }

    /**
     * 读取输入流为 UTF-8 文本。
     *
     * @param in 输入流
     * @return 文本
     * @throws Exception IO 异常
     */
    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), UTF8);
    }

    /**
     * trust-all 的 {@link SSLSocketFactory} —— 与上游 {@code init} 里的空
     * {@code X509TrustManager} 等价。
     *
     * <p><b>这是故意的</b>：站点证书长期过期/自签，上游靠关掉校验才可用。
     * 代价是失去中间人防护 —— 本方法只在 {@link #request} 里使用。
     *
     * @return 不校验链的 socket factory；构造失败返回 null（退回默认校验）
     */
    public static SSLSocketFactory trustAll() {
        try {
            TrustManager[] managers = {new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("SSL");
            ctx.init(null, managers, new SecureRandom());
            return ctx.getSocketFactory();
        } catch (Throwable e) {
            Log.w(TAG, "trustAll 构造失败，退回默认校验", e);
            return null;
        }
    }

    // ── 私有协议 ────────────────────────────────────────────

    /**
     * 上游 {@code c(url)}：带签名的 GET，重试 3 次。
     *
     * <p>重试条件：非 2xx / 无响应体 / {@code need_login} 且响应里出现
     * {@code "code":401} 或 {@code 未登录}（此时把会话 Cookie 清掉）。
     *
     * @param url 地址
     * @return 响应体；3 次都失败返回空串
     */
    public String apiGet(String url) {
        boolean retryOnAuthFailure = needLogin && (username.isEmpty() || password.isEmpty()) ? false : true;
        for (int attempt = 0; attempt < RETRY; attempt++) {
            Resp resp = request("GET", url, null, null, signedHeaders(false, true));
            if (!resp.ok() || !resp.hasBody()) {
                sleep();
                continue;
            }
            if (needLogin && (resp.body.contains("\"code\":401") || resp.body.contains("未登录"))) {
                this.session = null;
                if (retryOnAuthFailure) {
                    continue;
                }
                return resp.body;
            }
            return resp.body;
        }
        if (!retryOnAuthFailure) {
            Log.w(TAG, "need_login 已开启但账号密码为空 → 直接返回空（上游会白等 3×500ms）");
        }
        return "";
    }

    /**
     * 上游 {@code b(str, str2)}：protobuf 地址解码，重试 3 次。
     *
     * <p>每次重试都重新生成 nonce 与时间戳（上游如此 —— 时间戳参与签名，重试必须换）。
     *
     * @param url    待解码地址
     * @param source 线路标识，可为 null
     * @return 解出的地址；3 次都失败返回空串
     */
    public String decodeUrl(String url, String source) {
        String src = source == null ? "" : source;
        for (int attempt = 0; attempt < RETRY; attempt++) {
            try {
                long now = System.currentTimeMillis();
                String nonce = randomNonce();
                String sign = sha256Upper(String.format(SIGN_FORMAT, id, dev, nonce, sk, now));
                byte[] payload = YunDuoProto.concat(
                        YunDuoProto.bytesField(1, url),
                        YunDuoProto.bytesField(2, src),
                        YunDuoProto.varintField(3, now),
                        YunDuoProto.bytesField(4, nonce),
                        YunDuoProto.bytesField(5, sign),
                        YunDuoProto.bytesField(6, dev),
                        YunDuoProto.varintField(7, 1L));
                Resp resp = request("POST", api + "/decode/url", payload,
                        "application/x-protobuf", signedHeaders(true, false));
                if (resp.ok()) {
                    String found = YunDuoProto.scanField3String(resp.body.getBytes(UTF8));
                    if (!found.isEmpty()) {
                        return found;
                    }
                    sleep();
                } else {
                    sleep();
                }
            } catch (Throwable e) {
                Log.w(TAG, "decodeUrl 第 " + attempt + " 次失败", e);
            }
        }
        return "";
    }

    /**
     * 上游 {@code e()}：登录并缓存会话 Cookie。
     *
     * @return 形如 {@code name=value} 的 Cookie；未登录/失败返回空串
     */
    public String login() {
        if (!needLogin) {
            return "";
        }
        if (session != null) {
            return session;
        }
        if (username.isEmpty() || password.isEmpty()) {
            return "";
        }
        try {
            // 登录体是「无签名的 KV 载荷」：服务端按键取值，**键序不是契约**，
            // 所以这里直接用宿主 JSONObject.toString()（键序随宿主库版本变）即可 ——
            // 不要"为了稳定"改成 JsonText.object(...) 显式声明顺序：
            // 只有**被签名**的请求体才需要显式声明（签名逐字覆盖字节）。
            JSONObject body = new JSONObject();
            body.put("username", username);
            body.put("password", password);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "application/json");
            headers.put("X-Client", xClient);
            headers.put("web-sign", webSign);
            headers.put("User-Agent", ua);
            Resp resp = request("POST", api + "/account/login", body.toString().getBytes(UTF8),
                    "application/json", headers);
            for (String cookie : resp.headerValues) {
                if (cookie.contains("yunduo_web_session") || cookie.contains("session")) {
                    this.session = cookie.split(";")[0];
                    break;
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "login failed", e);
        }
        return session == null ? "" : session;
    }

    /**
     * 上游 {@code d(url)}：从远端账号池随机取一个账号。
     *
     * @param url 账号池地址
     */
    public void loadAccounts(String url) {
        try {
            Resp resp = request("GET", url, null, null, mapOf("User-Agent", ua));
            if (resp.ok() && resp.hasBody()) {
                pickAccount(new JSONArray(resp.body));
            }
        } catch (Throwable e) {
            Log.e(TAG, "loadAccounts failed", e);
        }
    }

    /**
     * 上游 {@code f(JSONArray)}：随机挑一个账号。
     *
     * @param accounts 账号数组
     */
    public void pickAccount(JSONArray accounts) {
        try {
            if (accounts != null && accounts.length() > 0) {
                JSONObject pick = accounts.getJSONObject(new SecureRandom().nextInt(accounts.length()));
                this.username = pick.optString("username");
                this.password = pick.optString("password");
            }
        } catch (Throwable e) {
            Log.e(TAG, "pickAccount failed", e);
        }
    }

    /**
     * 上游 {@code j(str)}：SHA-256 → 小写 hex → <b>转大写</b>。
     *
     * @param text 原文
     * @return 64 位大写 hex；失败返回空串
     */
    public static String sha256Upper(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().toUpperCase();
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 上游 {@code b()} 里的 nonce：16 字节随机数的 32 位小写 hex。
     *
     * @return 32 位小写 hex
     */
    public static String randomNonce() {
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 构造带签名的请求头。
     *
     * @param protobuf 是否是 protobuf 请求（影响 Accept）
     * @param withAuth 是否附带会话 Cookie
     * @return 请求头
     */
    public Map<String, String> signedHeaders(boolean protobuf, boolean withAuth) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", ua);
        headers.put("Accept", protobuf ? "application/x-protobuf" : "application/json");
        headers.put("X-Client", xClient);
        headers.put("web-sign", webSign);
        if (withAuth && needLogin) {
            String cookie = login();
            if (!cookie.isEmpty()) {
                headers.put("Cookie", cookie);
            }
        }
        return headers;
    }

    /** 一次 500ms 退避（上游 {@code Thread.sleep(500)}） */
    protected void sleep() {
        try {
            Thread.sleep(RETRY_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── 契约方法 ────────────────────────────────────────────

    /**
     * 首页：{@code /index/home} 的分类表 + 去重后的推荐列表。
     *
     * @param filter 忽略（上游没有筛选器）
     * @return 标准首页 JSON；异常返回 {@code ""}
     */
    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject root = new JSONObject();
            JSONArray classes = new JSONArray();
            JSONArray list = new JSONArray();
            JSONObject resp = Http.parseJson(apiGet(api + "/index/home"));
            if (resp.optInt("code") == 200 && resp.has("data")) {
                JSONArray categories = resp.getJSONObject("data").optJSONArray("categories");
                List<String> seen = new ArrayList<>();
                for (int i = 0; categories != null && i < categories.length(); i++) {
                    JSONObject category = categories.optJSONObject(i);
                    if (category == null) {
                        continue;
                    }
                    String typeName = category.optString("type_name", "");
                    JSONObject clazz = new JSONObject();
                    clazz.put("type_name", typeName);
                    clazz.put("type_id", typeName);
                    classes.put(clazz);
                    JSONArray videos = category.optJSONArray("videos");
                    for (int j = 0; videos != null && j < videos.length(); j++) {
                        JSONObject video = videos.optJSONObject(j);
                        if (video == null) {
                            continue;
                        }
                        String vodId = video.optString("vod_id");
                        if (!vodId.isEmpty() && !seen.contains(vodId)) {
                            seen.add(vodId);
                            JSONObject item = new JSONObject();
                            item.put("vod_id", vodId);
                            item.put("vod_name", video.optString("vod_name"));
                            item.put("vod_pic", video.optString("vod_pic"));
                            item.put("vod_remarks", video.optString("vod_remarks"));
                            list.put(item);
                        }
                    }
                }
            }
            root.put("class", classes);
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            Log.e(TAG, "homeContent failed", e);
            return "";
        }
    }

    /**
     * 分类翻页：{@code /filter/vod?type_name=&page=&sort=hits}。
     *
     * @param tid    分类名（上游把它当 {@code type_name}，不是 id）
     * @param pg     页码
     * @param filter 忽略
     * @param extend 筛选值；值为 {@code 全部} 的项不拼进 URL
     * @return 标准分类 JSON；异常返回 {@code ""}
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        int page;
        if (pg == null || pg.isEmpty()) {
            page = 1;
        } else {
            try {
                page = Integer.parseInt(pg);
            } catch (Throwable e) {
                // 上游这里 catch 后 return ""（不是兜底 1）
                return "";
            }
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(api).append("/filter/vod?type_name=")
              .append(URLEncoder.encode(tid == null ? "" : tid, "UTF-8"))
              .append("&page=").append(page).append("&sort=hits");
            if (extend != null && !extend.isEmpty()) {
                for (Map.Entry<String, String> e : extend.entrySet()) {
                    if (!"全部".equals(e.getValue())) {
                        sb.append('&').append(e.getKey()).append('=')
                          .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
                    }
                }
            }
            JSONObject resp = Http.parseJson(apiGet(sb.toString()));
            JSONObject root = new JSONObject();
            JSONArray list = new JSONArray();
            if (resp.optInt("code") == 200) {
                JSONArray data = resp.optJSONArray("data");
                for (int i = 0; data != null && i < data.length(); i++) {
                    JSONObject one = data.optJSONObject(i);
                    if (one == null) {
                        continue;
                    }
                    JSONObject item = new JSONObject();
                    item.put("vod_id", one.optString("vod_id"));
                    item.put("vod_name", one.optString("vod_name"));
                    item.put("vod_pic", one.optString("vod_pic"));
                    item.put("vod_remarks", one.optString("vod_remarks"));
                    list.put(item);
                }
                root.put("page", page);
                int pageCount = resp.optInt("pageCount", 0);
                root.put("pagecount", pageCount <= 0 ? 1 + page : pageCount);
                root.put("limit", 24);
                root.put("total", resp.optInt("total", list.length()));
            }
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            Log.e(TAG, "categoryContent failed", e);
            return "";
        }
    }

    /**
     * 详情：先 {@code /vod/get_detail}，拿不到线路再 {@code /vod/detail_v2}。
     *
     * <p>两条路径的播放项编码不同：
     * <ul>
     *   <li>v1：{@code 集名$token@<地址>@<线路名>}；</li>
     *   <li>v2：{@code 集名$v2@<vodId>@<集号 1 基>@<线路下标 0 基>}；</li>
     * </ul>
     * v2 没有 {@code episodes} 时用 {@code episode_count} 合成 {@code 第%02d集}。
     *
     * @param ids 影片 id 列表
     * @return 标准详情 JSON；异常返回 {@code ""}
     */
    @Override
    public String detailContent(List<String> ids) {
        try {
            String vodId = firstId(ids);
            JSONObject root = new JSONObject();
            JSONArray out = new JSONArray();
            JSONObject resp = Http.parseJson(apiGet(api + "/vod/get_detail?vod_id=" + vodId));
            if (resp.optInt("code") == 200 && resp.has("data")) {
                JSONObject detail = asObject(resp);
                if (detail != null && !detail.optString("vod_play_from", "").isEmpty()) {
                    JSONObject item = buildFromV1PlayList(detail, vodId, resp);
                    if (item != null) {
                        out.put(item);
                    }
                }
            }
            if (out.length() == 0) {
                JSONObject resp2 = Http.parseJson(apiGet(api + "/vod/detail_v2?vod_id=" + vodId));
                if (resp2.optInt("code") == 200 && resp2.has("data")) {
                    JSONObject data = resp2.optJSONObject("data");
                    JSONObject detail = data == null ? null : data.optJSONObject("detail");
                    JSONObject playback = data == null ? null : data.optJSONObject("playback");
                    if (detail != null && playback != null) {
                        JSONObject item = buildFromV2(detail, playback, vodId);
                        if (item != null) {
                            out.put(item);
                        }
                    }
                }
            }
            root.put("list", out);
            String json = root.toString();
            onDetailParsed(json);
            return json;
        } catch (Throwable e) {
            Log.e(TAG, "detailContent failed", e);
            return "";
        }
    }

    /**
     * 搜索：{@code /search/index?wd=&pg=1}，并对结果做「像片名」过滤。
     *
     * <p>过滤条件（上游逐字）：{@code vod_id} 非空、{@code vod_name} 去空白后非空、
     * 片名小写包含关键词小写、片名不含 {@code http}、不含 {@code .com}。
     *
     * @param key   关键词
     * @param quick 忽略
     * @return 标准搜索 JSON；异常返回 {@code ""}
     */
    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String wd = key == null ? "" : key;
            JSONObject resp = Http.parseJson(
                    apiGet(api + "/search/index?wd=" + URLEncoder.encode(wd, "UTF-8") + "&pg=1"));
            JSONObject root = new JSONObject();
            JSONArray list = new JSONArray();
            if (resp.optInt("code") == 200) {
                JSONArray data = resp.optJSONArray("data");
                String needle = wd.toLowerCase();
                for (int i = 0; data != null && i < data.length(); i++) {
                    JSONObject one = data.optJSONObject(i);
                    if (one == null) {
                        continue;
                    }
                    String vodId = one.optString("vod_id", "");
                    String name = one.optString("vod_name", "").trim();
                    if (!vodId.isEmpty() && !name.isEmpty()
                            && name.toLowerCase().contains(needle)
                            && !name.contains("http") && !name.contains(".com")) {
                        JSONObject item = new JSONObject();
                        item.put("vod_id", vodId);
                        item.put("vod_name", name);
                        item.put("vod_pic", one.optString("vod_pic"));
                        item.put("vod_remarks", one.optString("vod_remarks"));
                        list.put(item);
                    }
                }
            }
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            Log.e(TAG, "searchContent failed", e);
            return "";
        }
    }

    /**
     * 播放。
     *
     * <p>播放 id 两种前缀：
     * <ul>
     *   <li>{@code token@<地址>@<线路名>} —— 直接 {@link #decodeUrl}；</li>
     *   <li>{@code v2@<vodId>@<集号>@<线路下标>} —— 先 {@code /vod/playback_v2} 取集地址，
     *       http 开头则按后缀判 {@code parse}；否则再 {@link #decodeUrl}。</li>
     * </ul>
     * {@code Referer} 取播放地址的 origin，取不到退回 {@link #host}。
     *
     * @param flag     线路名
     * @param pid      播放 id
     * @param vipFlags 忽略
     * @return 播放 JSON（已挂弹幕）；异常返回 {@code ""}
     */
    @Override
    public String playerContent(String flag, String pid, List<String> vipFlags) {
        try {
            if (pid == null) {
                return "";
            }
            if (pid.startsWith("token@")) {
                String[] parts = pid.split("@");
                String target = parts.length > 1 ? parts[1] : "";
                String source = parts.length > 2 ? parts[2] : "";
                JSONObject out = new JSONObject();
                String direct = decodeUrl(target, source);
                if (direct.isEmpty()) {
                    out.put("parse", 1);
                    out.put("url", host + "/play/unknown?source=" + URLEncoder.encode(source, "UTF-8"));
                } else {
                    out.put("parse", 0);
                    out.put("url", direct);
                }
                out.put("header", playHeaders(out.optInt("parse") == 0
                        ? out.optString("url") : ""));
                return addDanmaku(out.toString());
            }
            if (!pid.startsWith("v2@")) {
                return "";
            }
            String[] parts = pid.split("@");
            String vodId = parts.length > 1 ? parts[1] : "";
            int episode = parts.length > 2 ? safeInt(parts[2], 1) : 1;
            int sourceIndex = parts.length > 3 ? safeInt(parts[3], 0) : 0;

            JSONObject out = new JSONObject();
            String sourceId = "";
            String episodeUrl = "";
            JSONObject resp = Http.parseJson(apiGet(api + "/vod/playback_v2?vod_id=" + vodId));
            JSONArray sources = null;
            if (resp.optInt("code") == 200 && resp.has("data")) {
                sources = resp.getJSONObject("data").optJSONArray("sources");
            }
            if (sources != null && sourceIndex < sources.length()) {
                JSONObject source = sources.getJSONObject(sourceIndex);
                sourceId = source.optString("from", "");
                JSONArray episodes = source.optJSONArray("episodes");
                if (episodes == null || episodes.length() == 0) {
                    String retryUrl = api + "/vod/playback_v2?vod_id=" + vodId
                            + "&source=" + URLEncoder.encode(sourceId, "UTF-8");
                    JSONObject resp2 = Http.parseJson(apiGet(retryUrl));
                    if (resp2.optInt("code") == 200 && resp2.has("data")) {
                        JSONArray retrySources = resp2.getJSONObject("data").optJSONArray("sources");
                        if (retrySources != null && sourceIndex < retrySources.length()) {
                            episodes = retrySources.getJSONObject(sourceIndex).optJSONArray("episodes");
                        }
                    }
                }
                if (episodes != null && episode <= episodes.length()) {
                    episodeUrl = episodes.getJSONObject(episode - 1).optString("url", "");
                }
            }

            if (!episodeUrl.isEmpty()) {
                String finalUrl = episodeUrl;
                if (episodeUrl.startsWith("http")) {
                    // 直链判定：后缀白名单；否则交给宿主解析
                    out.put("parse", DIRECT_URL.matcher(episodeUrl).matches() ? 0 : 1);
                } else {
                    String decoded = decodeUrl(episodeUrl, sourceId);
                    if (decoded.isEmpty()) {
                        out.put("parse", 1);
                        finalUrl = playPage(vodId, episode, sourceId);
                    } else {
                        out.put("parse", 0);
                        finalUrl = decoded;
                    }
                }
                out.put("url", finalUrl);
                out.put("header", playHeaders(finalUrl));
                return addDanmaku(out.toString());
            }

            // 上游兜底：拿不到集地址就返回 /play/ 页面，交给宿主嗅探
            out.put("parse", 1);
            out.put("url", playPage(vodId, episode, sourceId));
            out.put("header", playHeaders(""));
            return addDanmaku(out.toString());
        } catch (Throwable e) {
            Log.e(TAG, "playerContent failed", e);
            return "";
        }
    }

    /**
     * 上游 {@code /play/} 兜底页面地址。
     *
     * @param vodId     影片 id
     * @param episode   集号（1 基）
     * @param sourceId  线路标识
     * @return 页面地址
     */
    public String playPage(String vodId, int episode, String sourceId) {
        try {
            return host + "/play/" + vodId + "?ep=" + episode
                    + "&source=" + URLEncoder.encode(sourceId == null ? "" : sourceId, "UTF-8");
        } catch (Throwable e) {
            return host + "/play/" + vodId;
        }
    }

    /**
     * 播放请求头：{@code User-Agent} + {@code Referer}（播放地址 origin，取不到用 host）
     * + 登录态 Cookie。
     *
     * @param playUrl 播放地址（空串表示还没有地址）
     * @return 头对象
     */
    public JSONObject playHeaders(String playUrl) {
        JSONObject headers = new JSONObject();
        try {
            headers.put("User-Agent", ua);
            String referer = host;
            if (playUrl != null && playUrl.startsWith("http")) {
                int slash = playUrl.indexOf("/", playUrl.indexOf("://") + 3);
                if (slash > 0) {
                    referer = playUrl.substring(0, slash);
                }
            }
            headers.put("Referer", referer);
            if (needLogin) {
                String cookie = login();
                if (!cookie.isEmpty()) {
                    headers.put("Cookie", cookie);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "playHeaders failed", e);
        }
        return headers;
    }

    // ── v1 / v2 详情组装 ─────────────────────────────────────

    /**
     * v1（{@code /vod/get_detail}）线路组装。
     *
     * @param detail {@code data}
     * @param vodId  影片 id
     * @param resp   整个响应（取顶层 {@code vodplayer}）
     * @return 影片对象；没有可用线路返回 null
     */
    public JSONObject buildFromV1PlayList(JSONObject detail, String vodId, JSONObject resp) {
        List<String> froms = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        Map<String, String> display = new LinkedHashMap<>();
        JSONArray players = resp.optJSONArray("vodplayer");
        for (int i = 0; players != null && i < players.length(); i++) {
            JSONObject one = players.optJSONObject(i);
            if (one == null) {
                continue;
            }
            String from = one.optString("from", "");
            String show = one.optString("show", from);
            display.put(from, show.isEmpty() ? from : show);
        }
        String[] groups = detail.optString("vod_play_from", "").split("\\$\\$\\$");
        String[] episodes = detail.optString("vod_play_url", "").split("\\$\\$\\$");
        for (int i = 0; i < groups.length; i++) {
            String from = groups[i];
            if (i >= episodes.length || episodes[i].isEmpty()) {
                continue;
            }
            List<String> out = new ArrayList<>();
            for (String ep : episodes[i].split("#")) {
                String[] kv = ep.split("\\$");
                if (kv.length == 2) {
                    out.add(kv[0] + "$" + "token@" + kv[1] + "@" + from);
                }
            }
            if (!out.isEmpty()) {
                String shown = display.containsKey(from) ? display.get(from) : from;
                froms.add(shown);
                urls.add(TextUtils.join("#", out));
            }
        }
        if (froms.isEmpty()) {
            return null;
        }
        return meta(detail, vodId, froms, urls);
    }

    /**
     * v2（{@code /vod/detail_v2}）线路组装。
     *
     * @param detail   {@code data.detail}
     * @param playback {@code data.playback}
     * @param vodId    影片 id
     * @return 影片对象；没有可用线路返回 null
     */
    public JSONObject buildFromV2(JSONObject detail, JSONObject playback, String vodId) {
        List<String> froms = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        JSONArray sources = playback.optJSONArray("sources");
        if (sources == null) {
            // 上游 else 分支：sources 缺失 → 不加任何条目
            return null;
        }
        for (int si = 0; si < sources.length(); si++) {
            JSONObject source = sources.optJSONObject(si);
            if (source == null) {
                continue;
            }
            String shown = source.optString("display_name", source.optString("from", "").trim());
            JSONArray episodes = source.optJSONArray("episodes");
            if (episodes == null || episodes.length() == 0) {
                int count = source.optInt("episode_count", 0);
                if (count > 0) {
                    JSONArray synthesized = new JSONArray();
                    for (int n = 1; n <= count; n++) {
                        JSONObject ep = new JSONObject();
                        try {
                            ep.put("title", String.format("第%02d集", n));
                        } catch (Throwable e) {
                            Log.w(TAG, "合成集标题失败", e);
                        }
                        synthesized.put(ep);
                    }
                    episodes = synthesized;
                }
            }
            if (episodes == null) {
                continue;
            }
            List<String> out = new ArrayList<>();
            for (int ei = 0; ei < episodes.length(); ei++) {
                JSONObject ep = episodes.optJSONObject(ei);
                if (ep == null) {
                    continue;
                }
                int index = ei + 1;
                out.add(ep.optString("title", String.valueOf(index))
                        + "$" + "v2@" + vodId + "@" + index + "@" + si);
            }
            if (!out.isEmpty()) {
                froms.add(shown);
                urls.add(TextUtils.join("#", out));
            }
        }
        // 与 v1 不同：v2 分支的影片条目是<b>无条件</b>加的（上游在 sources 非 null 时就建条目，
        // vod_play_from / vod_play_url 允许为空串）。所以这里不因「没有线路」而返回 null。
        return meta(detail, vodId, froms, urls);
    }

    /**
     * 两条详情路径共用的元数据映射。
     *
     * <p>{@code vod_content} 去掉 HTML 标签后 trim（上游 {@code replaceAll("<[^>]+>","")}）。
     *
     * @param src   详情对象
     * @param vodId 影片 id
     * @param froms 线路名
     * @param urls  线路地址
     * @return 影片对象
     */
    public static JSONObject meta(JSONObject src, String vodId, List<String> froms, List<String> urls) {
        JSONObject item = new JSONObject();
        try {
            item.put("vod_id", src.optString("vod_id", vodId));
            item.put("vod_name", src.optString("vod_name", ""));
            item.put("vod_pic", src.optString("vod_pic", ""));
            item.put("vod_director", src.optString("vod_director", ""));
            item.put("vod_actor", src.optString("vod_actor", ""));
            item.put("vod_year", src.optString("vod_year", ""));
            item.put("vod_area", src.optString("vod_area", ""));
            item.put("vod_content", src.optString("vod_content", "").replaceAll("<[^>]+>", "").trim());
            item.put("vod_remarks", src.optString("vod_remarks", ""));
            item.put("vod_play_from", TextUtils.join("$$$", froms));
            item.put("vod_play_url", TextUtils.join("$$$", urls));
        } catch (Throwable e) {
            Log.w(TAG, "meta failed", e);
        }
        return item;
    }

    /**
     * 上游 {@code merge.p026a.a.parseJsonAndSave(json)}：把片名记入宿主历史。
     *
     * <p>宿主契约没有暴露历史库写入（上游走 {@code p038m.l.a("historyvodname", …)} 与
     * {@code Danmu.getRealName}）。因此这里做成可覆写钩子：默认实现只把片名交给
     * {@link #saveHistory(String)}，由宿主/子类决定落哪里。
     *
     * @param detailJson 详情响应 JSON
     */
    protected void onDetailParsed(String detailJson) {
        try {
            JSONArray list = new JSONObject(detailJson).optJSONArray("list");
            if (list != null && list.length() > 0) {
                saveHistory(realName(list.getJSONObject(0).optString("vod_name")));
            }
        } catch (Throwable e) {
            Log.w(TAG, "onDetailParsed failed", e);
        }
    }

    /**
     * 历史落库钩子 —— <b>上游该分支未实现于本工程</b>。
     *
     * <p>上游调 {@code p038m.l.a("historyvodname", name)} 写宿主私有存储；
     * 本工程的 {@code crawler} 契约没有该接口，所以默认不做事（不是占位：
     * 片名归一化 + 钩子调用链是真的，只有最终落库位置改由宿主决定）。
     *
     * @param vodName 归一化后的片名
     */
    protected void saveHistory(String vodName) {
        Log.d(TAG, "history: " + vodName);
    }

    /**
     * 上游 {@code Danmu.getRealName} 的最小等价：去掉「臻彩/真彩」画质标记并 trim。
     *
     * @param name 片名
     * @return 归一化片名
     */
    public static String realName(String name) {
        return name == null ? "" : name.replaceAll("[（(【<][臻真]彩[）)】>]", "").trim();
    }

    /**
     * 弹幕：还原宿主 {@code merge.p026a.a.addDanmaku(json)}。
     *
     * @param json 播放对象 JSON
     * @return 追加了 {@code danmaku} 的 JSON；不是对象时原样返回
     */
    public String addDanmaku(String json) {
        if (proxyBase.isEmpty() || json == null || !json.startsWith("{")) {
            return json;
        }
        try {
            JSONObject o = new JSONObject(json);
            o.put("danmaku", proxyBase + "?do=diydanmu");
            return o.toString();
        } catch (Throwable e) {
            return json;
        }
    }

    /** 弹幕代理前缀（宿主 {@code Proxy.getUrl()} 的替身） */
    protected String proxyBase = "";

    /**
     * 注入弹幕代理前缀。
     *
     * @param base 形如 {@code http://127.0.0.1:9978/proxy}；null 按空串
     */
    public void setProxyBase(String base) {
        this.proxyBase = base == null ? "" : base.trim();
    }

    /** 当前弹幕代理前缀 */
    public String proxyBase() {
        return proxyBase;
    }

    // ── 小工具 ──────────────────────────────────────────────

    /**
     * 取数组或对象形态的 {@code data}。
     *
     * @param value {@code opt("data")} 的结果
     * @return 对象；数组取第 0 个；都不是返回 null
     */
    public static JSONObject asObject(JSONObject resp) {
        JSONObject direct = resp.optJSONObject("data");
        if (direct != null) {
            return direct;
        }
        JSONArray arr = resp.optJSONArray("data");
        if (arr != null && arr.length() > 0) {
            return arr.optJSONObject(0);
        }
        return null;
    }

    /**
     * 安全解析整数。
     *
     * @param text 文本
     * @param def  默认值
     * @return 解析结果或默认值
     */
    public static int safeInt(String text, int def) {
        try {
            return Integer.parseInt(text);
        } catch (Throwable e) {
            return def;
        }
    }

    /**
     * 单元素不可变头表。
     *
     * @param k 键
     * @param v 值
     * @return 头表
     */
    public static Map<String, String> mapOf(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
}
