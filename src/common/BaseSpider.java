package com.example.tvbox.common;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.crawler.Spider;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件基类模板。
 *
 * <p>把每个站点插件都要写的重复代码集中到一处：
 * <ul>
 *   <li>{@link #init(Context, String)} 自动解析 ext → {@link #ext}</li>
 *   <li>{@link #http} 已按 ext 的 UA / 指纹配置好</li>
 *   <li>{@link #requireUrl()} / {@link #requireKey()} 等守卫，缺配置时打日志并返回空串</li>
 *   <li>{@link #headers()} 产出与 ext 一致的请求头（UA / deviceId / version / token / 包名）</li>
 *   <li>5 个核心方法给空实现 + 告警日志，子类按需覆写</li>
 * </ul>
 *
 * <p>子类最小骨架：
 * <pre>{@code
 * public class XxxPlain extends BaseSpider {
 *     public XxxPlain() { super(); }
 *
 *     @Override
 *     public void init(Context ctx, String extend) {
 *         super.init(ctx, extend);            // 解析 ext、准备 http
 *         ext.withKeys("dataKey", "内置16位key", "dataIv", "内置16位iv"); // 密钥固化
 *     }
 *
 *     @Override
 *     public String homeContent(boolean filter) {
 *         String base = requireUrl();
 *         if (base.isEmpty()) return VodBuilder.EMPTY_RESPONSE;
 *         String body = http.get(base + "/api.php?ac=class");
 *         ...
 *         return VodBuilder.create().clazzAll(map).addAll(vods).homeContent();
 *     }
 * }
 * }</pre>
 *
 * <p>密钥安全提示：本基类不打印任何密钥明文，{@link Ext#toString()} 也只输出长度。
 */
public abstract class BaseSpider extends Spider {

    /** 日志 TAG，子类可用 {@link #tag()} 取得「类名」级别 TAG */
    protected static final String TAG = "BaseSpider";

    // ── 为什么这两个字段由**我们自己**持有，而不是用宿主的 ──────────────────
    //
    // 宿主 `Spider` 的字段集合**因宿主而异**：
    //   · Android TVBox 系：有 `public static JSONObject empty`、`public static Context mContext`
    //   · FongMi 桌面版（fmplayer.app 的 gateway.jar）：**只有** `public String siteKey`
    //
    // 早期实现写了 `this.empty = new JSONObject();` —— 因为 `empty` 在继承链上来自宿主的
    // `Spider`，这条赋值在字节码里变成 `putstatic com/github/catvod/crawler/Spider.empty`。
    // 编译期完全正常、单元测试全绿（本地有镜像 `Spider`），
    // **但在 fmplayer.app 上一实例化就炸**：
    //
    //   java.lang.NoSuchFieldError: Class com.example.tvbox.common.BaseSpider
    //        does not have member field 'org.json.JSONObject empty'
    //       at com.example.tvbox.common.BaseSpider.<init>(BaseSpider.java:73)
    //       at com.github.catvod.spider.YGP.<init>(YGP.java:13)
    //
    // 修法：把这两个字段**搬到我们自己的基类上**，从此不引用宿主的任何静态字段。
    // 这样对**两种宿主都成立** —— 我们不占用宿主的名字，也不假设它有什么。
    // 那条"契约对齐"的真规则不是"照抄某个宿主"，而是
    // **只依赖所有宿主的公共交集**；交集之外的东西一律自己带。

    /** 空 JSON 对象缓存。**本类自持**，不引用宿主字段（见上方说明）。 */
    protected static final org.json.JSONObject empty = new org.json.JSONObject();

    /** 宿主 Context。**本类自持**，不引用宿主字段（见上方说明）。 */
    protected static android.content.Context mContext;

    /** ext 解析结果，永不为 null（未初始化时为 empty） */
    protected Ext ext = Ext.empty();

    /** 已按 ext 配置好的 HTTP 客户端，永不为 null */
    protected Http http = Http.create();

    /** 站点基址（已去掉尾部斜杠），未配置时为空串 */
    protected String baseUrl = "";

    /** 是否已完成 init */
    protected boolean inited;

    /** 构造：只做本类自己的初始化，**不触碰宿主的任何字段** */
    protected BaseSpider() {
        // empty 是本类的 static final 字段（见字段区注释），无需在此赋值。
    }

    // ── 生命周期 ────────────────────────────────────────────

    /**
     * 无 ext 初始化（宿主某些路径只调这个）。
     *
     * <p><b>★ 这里有一个非常容易踩、且本地永远测不出来的坑：</b>
     * 本方法**绝不能**写成 {@code init(context, "")} 或 {@code super.init(context, ...)}。
     *
     * <p>真实宿主 {@code com.github.catvod.crawler.Spider.init(Context, String)}（FongMi 桌面版
     * gateway.jar）的字节码就是一句话：
     * <pre>{@code
     *   public void init(Context, String) { init(context); }   // invokevirtual init(Context)
     * }</pre>
     * 它是 **virtual 调用**，会派发到我们覆写的 {@link #init(Context)}。于是：
     * <pre>
     *   BaseSpider.init(Context)  →  init(Context, "")   →  super.init(Context, "")   （宿主）
     *        ↑                                                        ↓
     *        └──────────────  host.invokevirtual init(Context)  ←─────┘
     * </pre>
     * 结果是 {@code java.lang.StackOverflowError}，实测栈：
     *
     * <pre>
     *   at com.github.catvod.crawler.Spider.init(Spider.java:30)
     *   at com.example.tvbox.common.BaseSpider.init(BaseSpider.java:125)
     *   at com.example.tvbox.plain.film.DubokuPlain.init(DubokuPlain.java:132)
     *   at com.example.tvbox.common.BaseSpider.init(BaseSpider.java:111)
     *   ... 无限重复 ...
     * </pre>
     *
     * <p>为什么本地测不出来：本地镜像 {@code Spider.init(Context,String)} 是**空实现**（照抄的是
     * Android TVBox 的形状），所以两个宿主的行为在这里分叉，
     * 而"分叉点"正好落在一个本地看不见的地方。
     *
     * <p>结论：本类的两个 {@code init} 都只调**本类的**私有实现 {@link #applyInit}，
     * 一次都不调 {@code super.init(...)} —— 我们本来也不需要宿主替我们做什么初始化。
     *
     * @param context 宿主上下文，可为 null
     */
    @Override
    public void init(Context context) {
        applyInit(context, "");
    }

    /**
     * 带 ext 初始化：解析配置 → 选站点基址 → 配置 HTTP。
     *
     * <p>子类覆写时应先 {@code super.init(context, extend)}，再用
     * {@link Ext#withKey(String, String)} 覆盖密钥。
     *
     * @param context 宿主上下文，可为 null
     * @param extend  api.json 中该站点 ext 字段原始字符串，可为 null
     */
    @Override
    public void init(Context context, String extend) {
        applyInit(context, extend);
    }

    /**
     * 真正的初始化逻辑（两个 {@code init} 的唯一共同出口）。
     *
     * <p>私有 —— 不改写任何可被宿主覆写的方法名，因此不可能被宿主回调进来形成回环。
     *
     * @param context 宿主上下文，可为 null
     * @param extend  api.json 中该站点 ext 字段原始字符串，可为 null
     */
    private void applyInit(Context context, String extend) {
        // 写**本类**的 mContext（static），不写宿主的 —— 宿主可能根本没有这个字段
        mContext = context;
        this.ext = new Ext(extend);
        this.baseUrl = ext.url();
        this.http = buildHttp();
        this.inited = true;

        if (baseUrl.isEmpty()) {
            Log.e(tag(), "ext 中未找到站点地址（url/host/site 全空）: ext=" + ext.raw());
        } else {
            Log.i(tag(), "init ok, base=" + baseUrl + ", sites=" + ext.sites().size());
        }
    }

    /**
     * 释放资源。子类如需关闭连接池/线程，覆写后调 super。
     */
    @Override
    public void destroy() {
        this.inited = false;
        Log.i(tag(), "destroy");
    }

    // ── 供子类使用的守卫 ────────────────────────────────────

    /**
     * 取站点基址，缺失时打错误日志。
     *
     * @return 基址；缺失返回空串（调用方应直接返回 EMPTY_RESPONSE）
     */
    protected String requireUrl() {
        if (baseUrl == null || baseUrl.isEmpty()) {
            Log.e(tag(), "缺少站点地址: 请检查 ext 的 url/host/site");
            return "";
        }
        return baseUrl;
    }

    /**
     * 取 AES 密钥，缺失时打错误日志。
     *
     * @return 密钥；缺失返回空串
     */
    protected String requireKey() {
        String k = ext.key();
        if (TextUtils.isEmpty(k)) {
            Log.e(tag(), "缺少 dataKey: 请在子类 init 中用 ext.withKey 固化");
            return "";
        }
        return k;
    }

    /**
     * 取 AES IV，缺失时打错误日志。
     *
     * @return IV；缺失返回空串
     */
    protected String requireIv() {
        String v = ext.iv();
        if (TextUtils.isEmpty(v)) {
            Log.e(tag(), "缺少 dataIv: 请在子类 init 中用 ext.withKey 固化");
            return "";
        }
        return v;
    }

    /**
     * 是否已具备发请求的最低配置。
     *
     * @return true 表示基址非空
     */
    protected boolean ready() {
        return inited && baseUrl != null && !baseUrl.isEmpty();
    }

    /**
     * 逐个探测 ext.site 候选域名，返回第一个可用的，并更新 {@link #baseUrl}。
     *
     * <p>用于站点换域名：ext.site 给出一串域名，运行时择优。
     *
     * @return 可用基址；全部失败返回空串
     */
    protected String resolveAliveSite() {
        return resolveAliveSite("/");
    }

    /**
     * 逐个探测 ext.site 候选域名，用指定路径探活，返回第一个可用的并回填 {@link #baseUrl}。
     *
     * <p><b>探活路径必须与真实接口路径一致</b>，否则会出现「域名正常但被误判为不可用」：
     * 例如接口在 {@code /api.php?ac=list}，却用 {@code /} 探活，服务端对不存在的路径返回 404，
     * 探测结果会把所有候选都判死。站群插件建议传本站真实的轻量接口路径。
     *
     * @param probePath 探活路径，空/null 时回退 {@code "/"}
     * @return 可用基址；全部失败返回空串
     */
    protected String resolveAliveSite(String probePath) {
        List<String> sites = ext.sites();
        if (sites.isEmpty()) {
            Log.e(tag(), "ext.site 为空，无法探测");
            return "";
        }
        String path = (probePath == null || probePath.trim().isEmpty()) ? "/" : probePath.trim();
        if (sites.size() == 1) {
            this.baseUrl = sites.get(0);
            return baseUrl;
        }
        String alive = http.firstAlive(sites, path);
        if (!alive.isEmpty()) {
            this.baseUrl = alive;
        } else {
            Log.e(tag(), "所有候选域名均不可用: " + sites + " probePath=" + path);
        }
        return alive;
    }

    // ── 请求构造 ────────────────────────────────────────────

    /**
     * 生成与 ext 一致的请求头。
     *
     * <p>会按 ext 有的字段注入：UA、deviceId、version、token、包名、构建号、签名。
     * 头名使用原插件观测到的常见形态（deviceId / version / token / packageName）。
     *
     * @return 新的 header map（调用方可以继续 put）
     */
    protected Map<String, String> headers() {
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        String ua = ext.ua();
        if (!ua.isEmpty()) {
            h.put("User-Agent", ua);
        }
        String deviceId = ext.deviceId();
        if (!deviceId.isEmpty()) {
            h.put("deviceId", deviceId);
        }
        String version = ext.version();
        if (!version.isEmpty()) {
            h.put("version", version);
        }
        String token = ext.token();
        if (!token.isEmpty()) {
            h.put("token", token);
        }
        String pkg = ext.pkg();
        if (!pkg.isEmpty()) {
            h.put("packageName", pkg);
        }
        String buildNumber = ext.buildNumber();
        if (!buildNumber.isEmpty()) {
            h.put("buildNumber", buildNumber);
        }
        String sign = ext.buildSignature();
        if (!sign.isEmpty()) {
            h.put("buildSignature", sign);
        }
        return h;
    }

    /**
     * 带 exts 指纹头的 GET。
     *
     * @param url 完整 URL
     * @return 响应体；失败为空串
     */
    protected String getWithFingerprint(String url) {
        return http.get(url, headers());
    }

    /**
     * 带 exts 指纹头的 POST。
     *
     * @param url  完整 URL
     * @param body 请求体
     * @return 响应体；失败为空串
     */
    protected String postWithFingerprint(String url, String body) {
        return http.post(url, body, headers());
    }

    /**
     * 按 ext 配置构造 HTTP 客户端。子类可覆写以调整超时/重试。
     *
     * @return 配置好的 Http 实例
     */
    protected Http buildHttp() {
        Http h = Http.create()
                .ua(ext.ua().isEmpty() ? Http.DEFAULT_UA : ext.ua())
                .retry(Http.DEFAULT_RETRY)
                .timeout(Http.DEFAULT_CONNECT_TIMEOUT_MS, Http.DEFAULT_READ_TIMEOUT_MS);
        if (!ext.token().isEmpty()) {
            h.header("token", ext.token());
        }
        return h;
    }

    // ── 通用小工具 ──────────────────────────────────────────

    /**
     * 日志 TAG，默认用运行期类名，便于区分各站点插件。
     *
     * @return TAG
     */
    protected String tag() {
        String n = getClass().getSimpleName();
        return n == null || n.isEmpty() ? TAG : n;
    }

    /**
     * 页码字符串安全转 int。
     *
     * @param pg 页码文本，可为 null/空/非数字
     * @param def 缺省值
     * @return 页码（>=1）
     */
    protected static int pageNum(String pg, int def) {
        if (pg == null || pg.trim().isEmpty()) {
            return Math.max(1, def);
        }
        try {
            return Math.max(1, Integer.parseInt(pg.trim()));
        } catch (Throwable e) {
            return Math.max(1, def);
        }
    }

    /**
     * 取分类页 extend 中的筛选值（宿主回传的一级筛选）。
     *
     * @param extend 宿主传入的筛选表，可为 null
     * @param key    参数名
     * @param def    缺省值
     * @return 值
     */
    protected static String extParam(HashMap<String, String> extend, String key, String def) {
        if (extend == null || key == null) {
            return def;
        }
        String v = extend.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    /**
     * ids 列表安全取首个元素。
     *
     * @param ids id 列表，可为 null
     * @return 首个非空 id；无则空串
     */
    protected static String firstId(List<String> ids) {
        if (ids == null) {
            return "";
        }
        for (String s : ids) {
            if (s != null && !s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    /**
     * 空响应体（宿主约定返回 {@code "{}"}）。
     *
     * @return {@code "{}"}
     */
    protected static String emptyResponse() {
        return VodBuilder.EMPTY_RESPONSE;
    }

    /**
     * 允许子类换用一个已配置好的 Http（例如宿主注入了 client）。
     *
     * @param client 宿主 {@code Spider.client()} 返回值，可为 null（忽略）
     * @return this
     */
    protected BaseSpider useHostClient(okhttp3.OkHttpClient client) {
        if (client != null) {
            this.http = Http.from(client)
                    .ua(ext.ua().isEmpty() ? Http.DEFAULT_UA : ext.ua())
                    .timeout(Http.DEFAULT_CONNECT_TIMEOUT_MS, Http.DEFAULT_READ_TIMEOUT_MS);
            Log.i(tag(), "using host okhttp client");
        }
        return this;
    }

    // ── 5 个核心方法（空实现 + 告警，子类覆写）────────────────

    /**
     * 首页分类 + 推荐。子类覆写。
     *
     * @param filter 是否请求筛选定义
     * @return 标准首页 JSON；默认空响应
     */
    @Override
    public String homeContent(boolean filter) {
        Log.w(tag(), "homeContent 未实现");
        return emptyResponse();
    }

    /**
     * 分类翻页。子类覆写。
     *
     * @param tid    分类 id
     * @param pg     页码（从 1 开始）
     * @param filter 是否请求筛选
     * @param extend 筛选参数
     * @return 标准分类 JSON；默认空响应
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        Log.w(tag(), "categoryContent 未实现, tid=" + tid + " pg=" + pg);
        return emptyResponse();
    }

    /**
     * 详情。子类覆写。
     *
     * @param ids 影片 id 列表
     * @return 标准详情 JSON；默认空响应
     */
    @Override
    public String detailContent(List<String> ids) {
        Log.w(tag(), "detailContent 未实现, ids=" + ids);
        return emptyResponse();
    }

    /**
     * 搜索。子类覆写。
     *
     * @param key   关键词
     * @param quick 是否快速搜索
     * @return 标准搜索 JSON；默认空响应
     */
    @Override
    public String searchContent(String key, boolean quick) {
        Log.w(tag(), "searchContent 未实现, key=" + key);
        return emptyResponse();
    }

    /**
     * 播放解析。子类覆写。
     *
     * @param flag     播放源标识
     * @param id       播放 id
     * @param vipFlags 需要解析的标识集合
     * @return 标准播放 JSON；默认空响应
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        Log.w(tag(), "playerContent 未实现, flag=" + flag + " id=" + id);
        return emptyResponse();
    }

    /**
     * 供日志/自测读取当前解析出的 ext。
     *
     * @return ext 实例（永不为 null）
     */
    public Ext ext() {
        return ext;
    }

    /**
     * 供自测读取当前基址。
     *
     * @return 基址，未初始化时为空串
     */
    public String baseUrl() {
        return baseUrl == null ? "" : baseUrl;
    }

    /**
     * 便捷：把 url 拆成列表（供需要多线路的插件用）。
     *
     * @param csv 逗号分隔文本，可为 null
     * @return 非 null 列表
     */
    protected static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 把一条播放源拼成 vod_play_from / vod_play_url 需要的格式，便于多处复用。
     *
     * @param from   源名（如「线路1」）
     * @param labels 集名列表
     * @param urls   集地址列表（与 labels 等长）
     * @return {@code labels[0]$urls[0]#labels[1]$urls[1]...}；参数不合法时为空串
     */
    protected static String buildPlayUrl(List<String> labels, List<String> urls) {
        if (labels == null || urls == null) {
            return "";
        }
        int n = Math.min(labels.size(), urls.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(Vod.SEP_EPISODE);
            }
            sb.append(labels.get(i) == null ? "" : labels.get(i))
              .append(Vod.SEP_NAME_URL)
              .append(urls.get(i) == null ? "" : urls.get(i));
        }
        return sb.toString();
    }
}
