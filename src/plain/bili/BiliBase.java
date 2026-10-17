package com.example.tvbox.plain.bili;

import android.content.Context;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 哔哩哔哩站群公共骨架（目前服务 {@link com.example.tvbox.plain.biliys.BiliYSPlain}；
 * 待重写的 {@code Bili} / {@code AmuBiliV2} 可直接复用）。
 *
 * <h2>为什么抽这一层</h2>
 * B 站系列源（BiliYS / Bili / AmuBiliV2）共用一整套「与业务无关」的基础设施：
 * <ol>
 *   <li><b>cookie 三级来源</b>：ext 直给 → 本地 cookie 文件 → cookie 服务 URL
 *       —— 上游 {@code BiliYS.e()} 写了一整套，三个类各有副本；</li>
 *   <li><b>三条固定请求头</b>：UA（Chrome 151）、{@code cookie}（逗号要转 {@code %2C}）、
 *       {@code Referer}；</li>
 *   <li><b>登录态/大会员态门控清晰度</b>：{@code qn > 32} 需登录、{@code qn > 80} 需大会员。
 *       三条阈值来自 {@code BiliYS.playerContent} 的
 *       {@code (this.d || qn <= 32) && (this.c || qn <= 80)}，
 *       而 {@code d}/{@code c} 来自 {@code /x/web-interface/nav} 的
 *       {@code data.isLogin} / {@code data.vipStatus}（字段名见
 *       {@code ae/work/w-pan/helpers/bili/b.java} 的 {@code @SerializedName}）；</li>
 *   <li><b>DASH → MPD</b>：{@link BiliDash}；</li>
 *   <li><b>双端点搜索合并</b>：{@code search_type=media_ft} + {@code media_bangumi}，
 *       两段代码除 search_type 外逐字相同。</li>
 * </ol>
 *
 * <h2>ext 三层分类</h2>
 * <table border="1">
 *   <tr><th>类</th><th>键</th><th>说明</th></tr>
 *   <tr><td><b>A 类</b>（必需）</td><td>—</td>
 *       <td>没有。B 站大部分接口可不带 cookie 访问（清晰度受限，见门控阈值）</td></tr>
 *   <tr><td><b>B 类</b>（有默认值，可覆盖）</td>
 *       <td>{@code cookie}（默认空）、{@code cookieFile}（默认
 *           {@code ${java.io.tmpdir}/bili_cookie.txt}）、{@code proxyBase}</td>
 *       <td>cookie 也可以写成 http(s) 地址，运行时去那里取 {@code {"cookie":...}}；
 *           这是上游 {@code init} 的既有行为</td></tr>
 *   <tr><td><b>C 类</b>（纯硬编码，直接内联）</td>
 *       <td>UA 串、{@code Referer}、全部 API 地址、音频采样率表、
 *           清晰度门控阈值 32/80、{@code qn=127} 取流参数、分类表与 filters JSON</td>
 *       <td>无 ext 通道</td></tr>
 * </table>
 *
 * <h2>宿主私有依赖的处理</h2>
 * 上游 {@code Proxy.getUrl()} 是宿主注入的私有类，本仓库无等价物 ——
 * 改为可注入的 {@link #proxyBase}，默认空串（此时播放列表里只有标签、没有代理地址）。
 */
public abstract class BiliBase extends BaseSpider {

    // ── C 类硬编码 ──────────────────────────────────────────

    /** UA（Chrome 151，上游逐字） */
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
    /** Referer */
    public static final String REFERER = "https://www.bilibili.com";
    /** 登录态 / 大会员态接口 */
    public static final String API_NAV = "https://api.bilibili.com/x/web-interface/nav";
    /** PGC 取流接口（{@code fnval=4048} = DASH + 4K + HDR + 杜比） */
    public static final String API_PLAYURL = "https://api.bilibili.com/pgc/player/web/playurl";
    /** 弹幕 XML */
    public static final String API_DANMAKU = "https://api.bilibili.com/x/v1/dm/list.so?oid=";
    /** 心跳（上游在 {@code Log.d} 里真发） */
    public static final String API_HEARTBEAT = "https://api.bilibili.com/x/click-interface/web/heartbeat";
    /** 取流时请求的最高清晰度 */
    public static final int QN_MAX = 127;
    /** 超过此清晰度需要登录 */
    public static final int QN_LOGIN_GATE = 32;
    /** 超过此清晰度需要大会员 */
    public static final int QN_VIP_GATE = 80;
    /** {@code fnval} 参数 */
    public static final String FNVAL = "4048";

    // ── B 类：有默认值，可覆盖 ──────────────────────────────

    /** 默认 cookie / 会话文件名（上游 {@code k.b("/bili_cookie.txt")}） */
    public static final String DEFAULT_COOKIE_FILE = "bili_cookie.txt";

    /** 62 字符表（上游 {@code merge.A.a.m1b} 的字面量顺序：字母在前） */
    public static final String ALNUM =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    // ── 实例状态 ────────────────────────────────────────────

    /**
     * 当前 cookie。
     *
     * <p><b>static</b> —— 上游 {@code Bili.java:35} 的 {@code h} 与 {@code BiliYS} 的
     * {@code static a} 都是类级静态；这里照实保留，并把 Bili/BiliYS 两个子类的
     * cookie 访问器统一到这一个字段上（原先各有一份）。
     */
    protected static String cookie = "";
    /** 当前用户 mid（上游静态 {@code mid}） */
    protected static String mid = "";
    /** WBI img 密钥 URL（上游静态 {@code wbiImgUrl}） */
    protected static String wbiImgUrl = "";
    /** WBI sub 密钥 URL（上游静态 {@code wbiSubUrl}） */
    protected static String wbiSubUrl = "";
    /** 是否登录（{@code data.isLogin}） */
    protected boolean login = false;
    /** 是否大会员（{@code data.vipStatus != 0}） */
    protected boolean vip = false;
    /** nav 是否已拉过 */
    protected boolean navLoaded = false;
    /** cookie / 会话文件名（B 类，可覆盖） */
    protected String cookieFile = DEFAULT_COOKIE_FILE;
    /** 缓存目录覆盖（测试 / 宿主注入；null = 走 {@link #cacheDir()} 默认推导） */
    private String cacheDirOverride;
    /** 随机源（{@link #randomToken(int)} 用；测试可注入固定种子） */
    private static Random random = new Random();

    /** 弹幕/播放代理前缀（宿主 {@code Proxy.getUrl()} 的替身） */
    protected String proxyBase = "";

    /** 最近一次请求 URL —— 测试断言用 */
    private String lastUrl = "";

    /** 构造 */
    protected BiliBase() {
        super();
    }

    // ── 生命周期 ────────────────────────────────────────────

    /**
     * 解析 ext：{@code cookie}、{@code cookieFile}、{@code proxyBase}。
     *
     * <p>上游 {@code BiliYS} 在<b>构造函数</b>里发了一次 nav 请求（为了拿 {@code mid}），
     * 这在无网/离线环境会静默失败但拖慢启动。本实现把 nav 改成惰性
     * （{@link #ensureNav()} 首次用到时才拉），见 {@link #ensureNav()}。
     *
     * @param context 宿主上下文，可为 null
     * @param extend  ext 原文，可为 null
     */
    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        String f = ext.optStr("cookieFile");
        if (!f.isEmpty()) {
            this.cookieFile = f;
        }
        String pb = ext.optStr("proxyBase");
        if (!pb.isEmpty()) {
            this.proxyBase = pb;
        }
        this.cookie = resolveCookie(ext.optStr("cookie"));
        Log.i(tag(), "init ok, cookie=" + (cookie.isEmpty() ? "无" : "有")
                + ", proxy=" + (proxyBase.isEmpty() ? "无" : "有"));
    }

    /**
     * cookie 三级来源：ext 直给 → http(s) 地址去取 → 本地文件。
     *
     * @param raw ext.cookie 原文，可为 null
     * @return cookie 文本；都没有时为空串
     */
    protected String resolveCookie(String raw) {
        String c = raw == null ? "" : raw.trim();
        if (c.startsWith("http")) {
            return parseCookieJson(fetch(c));
        }
        if (!c.isEmpty()) {
            return c;
        }
        return parseCookieJson(readCookieFile());
    }

    /**
     * 从 {@code {"cookie":"..."}} 里取 cookie。
     *
     * @param json 文件/接口内容，可为 null
     * @return cookie；解析失败为空串
     */
    public static String parseCookieJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return "";
        }
        try {
            JSONObject o = new JSONObject(json.trim());
            return o.optString("cookie", "").trim();
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 读本地 cookie 文件（默认 {@code ${java.io.tmpdir}/bili_cookie.txt}）。
     *
     * <p>上游用 {@code merge.p038m.k.a(merge.p038m.k.b("/bili_cookie.txt"))}
     * 读宿主的 cache 目录；JVM 侧没有 cache 目录，这里用临时目录，
     * 路径可被 ext.cookieFile 覆盖。
     *
     * @return 文件内容；不存在或读失败为空串
     */
    protected String readCookieFile() {
        try {
            File f = cacheFile();
            if (f == null || !f.isFile()) {
                return "";
            }
            return readAll(new FileInputStream(f));
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 会话文件句柄（上游 {@code k.b("/bili_cookie.txt")}）。
     *
     * @return {@code <cacheDir>/<cookieFile>}
     */
    public File cacheFile() {
        return new File(cacheDir(), cookieFile);
    }

    /**
     * 缓存目录。
     *
     * <p>优先宿主 {@code getFilesDir()}，取不到（桌面 JVM / stub）回落
     * {@code java.io.tmpdir} —— 与同工程 {@code AmuBiliV2Plain#cacheDir()} 保持同一约定，
     * 这样 {@code AmuBiliV2} 写下的会话文件才能被本类读到。
     *
     * @return 目录绝对路径
     */
    public String cacheDir() {
        if (cacheDirOverride != null) {
            return cacheDirOverride;
        }
        try {
            if (mContext != null) {
                File d = mContext.getFilesDir();
                if (d != null) {
                    return d.getAbsolutePath();
                }
            }
        } catch (Throwable ignore) {
            // 桌面 JVM / stub，走临时目录
        }
        return System.getProperty("java.io.tmpdir", ".");
    }

    /**
     * 注入缓存目录（测试用）。
     *
     * @param dir 目录；null 恢复默认推导
     */
    public void setCacheDirForTest(String dir) {
        this.cacheDirOverride = dir;
    }

    /**
     * 读会话文件并解析成 JSON（上游 {@code k.a(k.b("/bili_cookie.txt"))} + JsonParser）。
     *
     * <p>会话文件同时含 {@code cookie} 与 {@code mid}（由
     * {@code AmuBiliV2Plain#persistSession} 落盘），所以 cookie 与 mid 都从这里取。
     *
     * @return 会话对象；文件缺失 / 非法 → 空对象（永不抛）
     */
    public JSONObject loadSession() {
        String raw = readCookieFile();
        if (raw == null || raw.trim().isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw.trim());
        } catch (Throwable e) {
            Log.w(TAG, "load session failed: " + e.getMessage());
            return new JSONObject();
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // ── 请求 ────────────────────────────────────────────────

    /**
     * 三条固定请求头。
     *
     * <p>{@code cookie} 里的逗号要转 {@code %2C} —— 上游这么做是因为某些中间层
     * 会按逗号切分头值。漏了这一步在某些代理下会话会丢。
     *
     * @return 保序 header map
     */
    public Map<String, String> apiHeaders() {
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        String ck = cookie;
        if (ck == null || ck.isEmpty()) {
            ck = loadSession().optString("cookie", "");
        }
        if (ck == null || ck.isEmpty()) {
            ck = "";
        }
        h.put("cookie", ck.replace(",", "%2C"));
        h.put("Referer", REFERER);
        return h;
    }

    /**
     * {@link #apiHeaders()} 的别名（BiliYS 早期版本的命名，保留以免调用点失效）。
     *
     * @return 保序 header map
     */
    public Map<String, String> biliHeaders() {
        return apiHeaders();
    }

    /**
     * HTTP 传输 seam（测试替换；默认实现离线时直接返回空）。
     *
     * <p>原先 Bili 与 BiliYS 各有一套（Bili 用 {@code Fetch} 接口 + 静态注入，
     * BiliYS 靠覆写 {@code fetch(String)}）。这里统一成一份：默认实现走
     * {@link Http}，测试既可以注入 {@link Fetch}，也可以覆写
     * {@link #fetch(String, Map)}。
     */
    public interface Fetch {
        /**
         * 单次请求。
         *
         * @param method  {@code GET}/{@code POST}
         * @param url     完整 URL
         * @param body    POST 体，GET 时 null
         * @param headers 请求头，可为 null
         * @return 响应体；失败空串
         */
        String request(String method, String url, String body, Map<String, String> headers);
    }

    private static final Fetch DEFAULT_FETCH = new Fetch() {
        @Override
        public String request(String method, String url, String body, Map<String, String> headers) {
            if (Http.isOfflineForTest()) {
                Log.d(TAG, "offline, skip " + method + " " + url);
                return "";
            }
            try {
                Http h = Http.create().ua(UA);
                return "POST".equals(method) ? h.post(url, body, headers) : h.get(url, headers);
            } catch (Throwable e) {
                Log.w(TAG, method + " failed " + url + " : " + e.getMessage());
                return "";
            }
        }
    };

    private static Fetch fetchImpl = DEFAULT_FETCH;

    /**
     * 替换 HTTP 传输（测试用）。
     *
     * @param f 实现，null 恢复默认
     */
    public static void setFetchForTest(Fetch f) {
        fetchImpl = f == null ? DEFAULT_FETCH : f;
    }

    /**
     * 替换随机源（测试用，便于确定性断言）。
     *
     * @param r 随机源，null 恢复默认
     */
    public static void setRandomForTest(Random r) {
        random = r == null ? new Random() : r;
    }

    /**
     * 带显式请求头的 GET（可被测试覆写以注入 fixture）。
     *
     * @param url     完整地址
     * @param headers 请求头
     * @return 响应体；失败为空串
     */
    public String fetch(String url, Map<String, String> headers) {
        this.lastUrl = url;
        String b = fetchImpl.request("GET", url, null, headers);
        return b == null ? "" : b;
    }

    /**
     * POST（可被测试覆写）。
     *
     * @param url     完整地址
     * @param body    请求体
     * @param headers 请求头
     * @return 响应体；失败为空串
     */
    public String fetchWith(String url, String body, Map<String, String> headers) {
        this.lastUrl = url;
        String b = fetchImpl.request("POST", url, body, headers);
        return b == null ? "" : b;
    }

    /**
     * 发一次 GET（默认带 {@link #apiHeaders()}）。
     *
     * @param url 完整地址
     * @return 响应体；失败为空串
     */
    public String fetch(String url) {
        return fetch(url, apiHeaders());
    }

    /**
     * 还原 {@code merge.A.a.m1b(int)}：随机 token。
     *
     * <p>字符表顺序按上游字面量（字母在前），不是"数字在前"。
     *
     * @param len 长度
     * @return 随机串
     */
    public static String randomToken(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(ALNUM.charAt((int) Math.floor(random.nextDouble() * ALNUM.length())));
        }
        return sb.toString();
    }

    /**
     * 最近一次请求 URL。
     *
     * @return URL；未请求时为空串
     */
    public String lastUrl() {
        return lastUrl;
    }

    /**
     * 用带 header 的 POST（心跳等）。
     *
     * @param url  地址
     * @param body 表单体
     * @return 响应体；失败为空串
     */
    public String post(String url, String body) {
        return fetchWith(url, body, apiHeaders());
    }

    // ── 登录态 ──────────────────────────────────────────────

    /**
     * 惰性拉取 nav（登录态 + 大会员态 + mid）。
     *
     * @return 是否成功解析
     */
    public boolean ensureNav() {
        return ensureSession();
    }

    /**
     * 惰性建会话：cookie/mid 从会话文件补齐 → 拉 nav → 登录/会员位 + WBI 密钥。
     *
     * <p>这是上游 {@code Bili.detailContent} 首次进入时做的事（Bili.java:230-235），
     * BiliYS 侧只是换个名字叫「nav」。
     *
     * @return 是否拿到 nav 的 {@code data}
     */
    public boolean ensureSession() {
        if (navLoaded) {
            return true;
        }
        if (cookie == null || cookie.isEmpty()) {
            String c = loadSession().optString("cookie", "");
            if (!c.isEmpty()) {
                cookie = c;
            }
        }
        if (mid == null || mid.isEmpty()) {
            String m = loadSession().optString("mid", "");
            if (!m.isEmpty()) {
                mid = m;
            }
        }
        return parseNav(fetch(API_NAV));
    }

    /**
     * 解析 nav 响应。
     *
     * <pre>
     *  {"code":0,"data":{"mid":123,"isLogin":true,"vipStatus":1}}
     * </pre>
     *
     * @param json 响应体，可为 null
     * @return 是否拿到 data
     */
    public boolean parseNav(String json) {
        JSONObject root = Http.parseJson(json);
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            return false;
        }
        this.mid = data.optString("mid", "");
        this.login = data.optBoolean("isLogin", false);
        // 会员位：nav 用 vipStatus（0=无 1=大会员），部分版本给 vipType。
        // 跨类证据：AmuBiliV2Plain（同一个 p031f.b bean）用的是同一条规则；
        // 原先本类写的是 `vipStatus != 0`，会把真实接口里不存在的 2 也算成会员。
        this.vip = data.optInt("vipStatus", 0) == 1 || data.optInt("vipType", 0) > 0;
        JSONObject wbi = data.optJSONObject("wbi_img");
        if (wbi != null) {
            setWbiKeys(wbi.optString("img_url", ""), wbi.optString("sub_url", ""));
        }
        this.navLoaded = true;
        return true;
    }

    /** @return 是否登录 */
    public boolean isLogin() {
        return login;
    }

    /** @return 是否大会员 */
    public boolean isVip() {
        return vip;
    }

    /** @return 当前 cookie */
    public static String cookie() {
        return cookie;
    }

    /** @return 当前 mid */
    public static String mid() {
        return mid;
    }

    /**
     * 设置 mid。
     *
     * @param m mid
     */
    public static void setMid(String m) {
        mid = m == null ? "" : m;
    }

    /**
     * 设置 WBI 密钥 URL（上游从 nav 的 {@code wbi_img} 取）。
     *
     * @param imgUrl {@code wbi_img.img_url}
     * @param subUrl {@code wbi_img.sub_url}
     */
    public static void setWbiKeys(String imgUrl, String subUrl) {
        wbiImgUrl = imgUrl == null ? "" : imgUrl;
        wbiSubUrl = subUrl == null ? "" : subUrl;
    }

    /** @return WBI img 密钥 URL */
    public static String wbiImgUrl() {
        return wbiImgUrl;
    }

    /** @return WBI sub 密钥 URL */
    public static String wbiSubUrl() {
        return wbiSubUrl;
    }

    /**
     * 直接注入登录 / 会员态（测试用，避免依赖 nav）。
     *
     * @param login 登录
     * @param isVip 大会员
     */
    public void setSessionForTest(boolean login, boolean isVip) {
        this.login = login;
        this.vip = isVip;
        this.navLoaded = true;
    }

    /**
     * 注入弹幕/播放代理前缀（宿主 {@code Proxy.getUrl()} 的替身）。
     *
     * @param base 前缀；null 按空串处理（空串时无法产出代理播放地址）
     */
    public void setProxyBase(String base) {
        this.proxyBase = base == null ? "" : base.trim();
    }

    /** @return 当前代理前缀 */
    public String proxyBase() {
        return proxyBase;
    }

    /**
     * 直接设置 cookie（测试 / 宿主注入）。
     *
     * @param cookie cookie 串
     */
    public static void setCookie(String cookie) {
        BiliBase.cookie = cookie == null ? "" : cookie;
    }

    // ── 清晰度门控 ──────────────────────────────────────────

    /**
     * 该清晰度当前账号是否可用。
     *
     * <p>{@code qn > 32} 需登录、{@code qn > 80} 需大会员（阈值来自上游两个常量判断）。
     *
     * @param qn 清晰度
     * @return 是否可用
     */
    public boolean canPlay(int qn) {
        if (qn > QN_LOGIN_GATE && !login) {
            return false;
        }
        return qn <= QN_VIP_GATE || vip;
    }

    /**
     * 按门控过滤 {@code accept_quality} / {@code accept_description} 两个等长数组。
     *
     * <p>上游用两个平行 List 按下标配对，任一边长度不足时只处理到较短的一边。
     *
     * @param qns  清晰度数组
     * @param desc 清晰度描述数组
     * @return {@code [保留的 qn 列表, 保留的描述列表]}
     */
    public List<List<String>> filterQuality(JSONArray qns, JSONArray desc) {
        List<String> keepQn = new ArrayList<>();
        List<String> keepDesc = new ArrayList<>();
        if (qns == null || desc == null) {
            return twoLists(keepQn, keepDesc);
        }
        int n = Math.min(qns.length(), desc.length());
        for (int i = 0; i < n; i++) {
            String qn = qns.optString(i, "");
            int v;
            try {
                v = Integer.parseInt(qn.trim());
            } catch (Throwable e) {
                continue;
            }
            if (canPlay(v)) {
                keepQn.add(qn);
                keepDesc.add(desc.optString(i, ""));
            }
        }
        return twoLists(keepQn, keepDesc);
    }

    private static List<List<String>> twoLists(List<String> a, List<String> b) {
        List<List<String>> out = new ArrayList<>();
        out.add(a);
        out.add(b);
        return out;
    }

    // ── 取流 ────────────────────────────────────────────────

    /**
     * 拼 PGC 取流地址。
     *
     * @param aid 视频 id
     * @param cid 分 P id
     * @param qn  清晰度
     * @return 地址
     */
    public String playUrlApi(String aid, String cid, int qn) {
        return API_PLAYURL + "?avid=" + enc(aid) + "&cid=" + enc(cid)
                + "&qn=" + qn + "&fnval=" + FNVAL + "&fourk=1";
    }

    /**
     * 请求取流接口，返回原始响应对象。
     *
     * <p>取流响应里同时含 {@code dash}（媒体轨）与
     * {@code accept_quality}/{@code accept_description}（可选清晰度），
     * 上游也是「一次请求两处都用」，所以这里暴露原始对象。
     *
     * @param aid 视频 id
     * @param cid 分 P id
     * @param qn  清晰度
     * @return 非 null JSONObject（解析失败为空对象）
     */
    public JSONObject playInfo(String aid, String cid, int qn) {
        return Http.parseJson(fetch(playUrlApi(aid, cid, qn)));
    }

    /**
     * 请求取流接口并解析成 {@link BiliDash}。
     *
     * @param aid 视频 id
     * @param cid 分 P id
     * @param qn  清晰度
     * @return 非 null 的 BiliDash
     */
    public BiliDash dashOf(String aid, String cid, int qn) {
        return BiliDash.parse(playInfo(aid, cid, qn));
    }

    /**
     * 代理播放地址（{@code ?do=biliys&aid=..&cid=..&qn=..&type=mpd}）。
     *
     * @param aid 视频 id
     * @param cid 分 P id
     * @param qn  清晰度
     * @return 代理地址；未注入 proxyBase 时返回空串
     */
    public String proxyPlayUrl(String aid, String cid, String qn) {
        if (effectiveProxyBase().isEmpty()) {
            return "";
        }
        return effectiveProxyBase() + "?do=" + proxyAction() + "&aid=" + enc(aid)
                + "&cid=" + enc(cid) + "&qn=" + enc(qn) + "&type=mpd";
    }

    /**
     * 弹幕地址。
     *
     * @param cid 分 P id
     * @return 弹幕 XML 地址
     */
    public String danmakuUrl(String cid) {
        return API_DANMAKU + enc(cid);
    }

    /**
     * 生成 MPD（代理分支真正吐给播放器的内容）。
     *
     * @param aid 视频 id
     * @param cid 分 P id
     * @param qn  清晰度
     * @return MPD XML
     */
    public String mpd(String aid, String cid, String qn) {
        return dashOf(aid, cid, parseInt(qn, QN_MAX)).toMpd(qn, false);
    }

    /**
     * URL 编码（B 站 id 都是数字，这里只为防御脏值）。
     *
     * @param s 原文
     * @return 编码结果
     */
    public static String enc(String s) {
        return Http.encode(s == null ? "" : s);
    }

    /**
     * 安全 parseInt。
     *
     * @param s   文本
     * @param def 缺省
     * @return 整数
     */
    public static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (Throwable e) {
            return def;
        }
    }

    // ── 搜索合并 ────────────────────────────────────────────

    /**
     * 双端点搜索合并：把一段响应里的 {@code data.result[]} 追加成 Vod。
     *
     * <p>上游用 {@code merge.p027b.k.c(season_id, title, cover, index_show, list, i, 1)}
     * 统一处理两个端点（该 helper 的实体未提取，本实现按调用点语义手写：
     * 追加一条 Vod、返回下一个下标）。<b>额外按 vod_id 去重</b> ——
     * 两个端点会返回同一部番（影视/番剧两条索引），不去重就会在搜索结果里出现两遍。
     *
     * @param out  目标列表（非 null）
     * @param json 响应体，可为 null
     * @return 本次追加的条数
     */
    public static int mergeSearch(List<Vod> out, String json) {
        if (out == null) {
            return 0;
        }
        JSONArray arr = resultArray(json);
        int added = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String id = o.optString("season_id", "");
            if (id.isEmpty()) {
                continue;
            }
            boolean dup = false;
            for (Vod v : out) {
                if (id.equals(v.vodId)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }
            out.add(new Vod(id, cleanTitle(o.optString("title", "")))
                    .pic(o.optString("cover", ""))
                    .remarks(o.optString("index_show", "")));
            added++;
        }
        return added;
    }

    /**
     * 取 {@code data.result[]}（可能不存在 → 空数组）。
     *
     * @param json 响应体，可为 null
     * @return 非 null 数组
     */
    public static JSONArray resultArray(String json) {
        JSONObject data = Http.parseJson(json).optJSONObject("data");
        if (data == null) {
            return new JSONArray();
        }
        JSONArray a = data.optJSONArray("result");
        return a == null ? new JSONArray() : a;
    }

    /**
     * 去掉搜索标题里的 {@code <em>} 等标签。
     *
     * <p>上游 {@code title.replaceAll("<[^>]*>", "")}。
     *
     * @param title 原始标题，可为 null
     * @return 去标签后的标题
     */
    public static String cleanTitle(String title) {
        return title == null ? "" : title.replaceAll("<[^>]*>", "");
    }

    /**
     * {@code ?do=} 型代理动作名（子类用自己的动作名覆盖）。
     *
     * @return 动作名
     */
    protected String proxyAction() {
        return "biliys";
    }

    /**
     * 实际使用的代理基址。
     *
     * <p>默认就是 {@link #proxyBase}；子类可以给出自己的默认值
     * （例如 Bili 的宿主默认端口），而不必重写整个 {@link #proxyPlayUrl}。
     *
     * @return 代理基址；空串表示无可用代理
     */
    protected String effectiveProxyBase() {
        return proxyBase == null ? "" : proxyBase;
    }

    /**
     * 表单编码（{@code k=v&…}，键与值都做 URL 编码）。
     *
     * <p>Bili 与 BiliYS 原先各有一份（一份收 {@code Map<String,Object>}、
     * 一份收 {@code Map<String,String>}）；统一成 {@code Map<String,?>}，
     * 两者的调用点都能编译。
     *
     * @param form 表单，可为 null
     * @return 编码后的请求体
     */
    public static String formEncode(Map<String, ?> form) {
        if (form == null || form.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ?> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(enc(e.getKey())).append('=')
              .append(enc(e.getValue() == null ? "" : String.valueOf(e.getValue())));
        }
        return sb.toString();
    }
}
