package com.example.tvbox.plain.appfox;

import android.content.Context;
import android.util.Log;

import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.appsrc.AppSourceBase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * AppFox 明文实现（苹果CMS 套壳 + 自带 /api.php/Appfox 路由）。
 *
 * <p>反编译原文: {@code ae/decompiled/AppFox.java}（684 行，jadx dev）
 *
 * <h2>两套后端（同一份代码按 ext 切换）</h2>
 * <table border="1">
 *   <tr><th></th><th>自带接口</th><th>CMS 接口</th></tr>
 *   <tr><td>触发条件</td><td>ext 不给 {@code cms}</td><td>ext 给了 {@code cms}</td></tr>
 *   <tr><td>首页</td><td>{@code /api.php/Appfox/init} → {@code data.type_list}</td>
 *       <td>{@code {cms}?ac=list} → {@code class}</td></tr>
 *   <tr><td>分类</td><td>{@code /api.php/Appfox/vodList}</td><td>{@code {cms}?pg=&t=&…}</td></tr>
 *   <tr><td>详情</td><td>{@code /api.php/Appfox/vod?ac=detail&ids=}</td>
 *       <td>{@code {cms}?ac=detail&ids=}</td></tr>
 *   <tr><td>搜索</td><td>{@code /api.php/Appfox/vod?ac=detail&wd=}</td>
 *       <td>{@code {cms}?ac=detail&wd=}</td></tr>
 * </table>
 *
 * <h2>ext 契约（本类的 ext 最多，逐个标类）</h2>
 * <table border="1">
 *   <tr><th>类</th><th>键</th><th>说明</th></tr>
 *   <tr><td><b>A 类</b>（必需）</td><td>{@code host}（或整串就是 URL）</td>
 *       <td>没有它所有接口都无基址</td></tr>
 *   <tr><td><b>B 类</b>（有默认值，可覆盖）</td>
 *       <td>{@code ver}（默认 1；{@code =2} 开启 nav 模式）、{@code cms}（默认空 = 用自带接口）、
 *           {@code from}（默认空）、{@code parse}（默认空 map）、
 *           {@code custom_first}（默认 0）、{@code category}（默认 1；
 *           {@code 0}=禁用、{@code 2}=msearch 模式）、{@code ua}（默认内置 Chrome 124；
 *           可给字符串或对象分场景覆盖）、{@code timeout}（默认 5 秒）</td>
 *       <td>全部有默认值；不给就用自带接口 + 默认 UA</td></tr>
 *   <tr><td><b>C 类</b>（纯硬编码，直接内联）</td>
 *       <td>4 条默认请求头、{@code /api.php/Appfox/*} 全部路径、
 *           {@code msearch:} 前缀、{@code \u2005} 分隔符、
 *           {@code detailContent} 重写分集串的格式、{@code playerContent} 默认播放 UA</td>
 *       <td>无 ext 通道</td></tr>
 * </table>
 *
 * <h3>{@code ua} 的两种形态</h3>
 * <pre>
 *  "ua": "Mozilla/5.0 …"                        → 覆盖全局 User-Agent
 *  "ua": {"host":"…","config":"…","home":"…",
 *         "category":"…","search":"…","parse":"…","player":"…"}
 *                                               → 按用途分别覆盖（键即用途名）
 * </pre>
 * 注意 {@code ua.category} 与顶层 {@code category} <b>同名不同义</b>：
 * 前者是「分类接口用的 UA」，后者是「分类模式开关（0/1/2）」。
 *
 * <h2>发现的上游缺陷（本实现已修，均有断言钉住）</h2>
 * <ol>
 *   <li><b>5 个筛选键全部读 {@code area}</b>（{@code categoryContent}）：
 *       {@code year/lang/class/sort} 的取值表达式写的是 {@code map.get("area")} ——
 *       复制粘贴错误。于是「年份=2024 + 地区=香港」会把 year 也发成「香港」。
 *       本实现各读各的键。</li>
 *   <li><b>CMS 首页分支白请求一次</b>：该分支先取 {@code class}，再<b>重复请求同一个地址</b>
 *       并把结果（含 msearch 改写）整体丢弃 —— 返回体里根本没有 {@code list}。
 *       本实现删掉那次无用请求。</li>
 *   <li><b>带 query 的直链被判成「需解析」</b>：直链正则是
 *       {@code "https?://.*\.(m3u8|mp4|flv|mkv)"} 且用 {@code matches()}
 *       （要求全串匹配），所以 {@code http://a/b.m3u8?token=1} 不匹配 →
 *       {@code jx} 被置 1，走解析流程。本实现允许尾部 {@code ?query}/{@code #fragment}。</li>
 *   <li><b>{@code vodname}/{@code vodindex} 是 public static</b>：多实例共享，
 *       并发播放会互相覆盖。本实现改为实例字段 + 访问器。</li>
 *   <li><b>{@code init} 里 {@code getString("host")} 缺键即抛</b>（被 catch 吞掉），
 *       结果是 host 为空但后续所有分支照跑。本实现用 {@code optString} 并把
 *       「缺 host」记为明确状态。</li>
 * </ol>

 * <h2>未覆盖的分支</h2>
 * <ul>
 *   <li><b>落盘缓存</b>：上游 {@code merge.p026a.a.parseJsonAndSave}（宿主私有），
 *       本仓库无等价物。影响：详情/搜索少了写盘副作用。将来补上需要宿主缓存 API。</li>
 *   <li><b>CMS 首页分支的第二次请求</b>：上游在 {@code ae/decompiled/AppFox.java} 里
 *       先取 {@code class}、再重复请求同一地址并把结果（含 {@code msearch:} 改写）整体丢弃。
 *       本实现<b>故意不复现</b>这次死请求。断言：{@code ★ CMS 分支只请求一次（修缺陷 2）}。</li>
 *   <li><b>弹幕地址</b>：依赖宿主私有 {@code Proxy.getUrl()}。改为可注入 {@code proxyBase}，
 *       未注入时 {@code danmaku} 为空串。断言：{@code 无代理时 danmaku 为空串}。</li>
 *   <li><b>刻意保留的上游行为（看着像 bug，其实是对的，勿「顺手修好」）</b>：
 *       <ol>
 *         <li>{@code homeContent} <b>刻意不输出 {@code list}</b> —— 宿主见不到 {@code list}
 *             才会去调 {@code homeVideoContent}；补一个空 {@code list} 会让首页推荐永远为空。
 *             断言：{@code 刻意不含 list（让宿主走 homeVideoContent）}。</li>
 *         <li>{@code normalizeCms} 用 {@code String.matches}（要求全串匹配），
 *             所以 {@code cms} 带 query 时<b>不会</b>补 {@code &}。
 *             断言：{@code cms 带 query 时不补 &（matches 需全串匹配）}。</li>
 *         <li>播放器的 {@code from@url@片名@集名} 顺序：{@code from} 在前、{@code url} 在后，
 *             与"直觉"相反（上游变量名 {@code str3/str4} 也容易看反）。
 *             断言：{@code 第1集 = 集名$aid+cid} 之外的 {@code playerContent} 系列。</li>
 *       </ol></li>
 * </ul>
 */
public class AppFoxPlain extends AppSourceBase {

    private static final String TAG = "AppFoxPlain";

    // ── C 类硬编码 ──────────────────────────────────────────

    /** 默认 UA */
    public static final String DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /** 播放默认 UA（ext.ua.player 缺省时用） */
    public static final String DEFAULT_PLAYER_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 "
            + "like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 "
            + "Mobile/15E148 Safari/604.1";

    /** 播放器名与 code 的分隔符（上游用的是 U+2005 FOUR-PER-EM SPACE，不是普通空格） */
    public static final String NAME_SEP = "\u2005";

    /** msearch 模式前缀 */
    public static final String MSEARCH = "msearch:";

    /** 首页要剔除的分类名 */
    public static final String[] HOME_BLOCK_NAMES = {"全部", "QQ", "juo.one"};

    /** 首页要剔除的分类名（包含匹配） */
    public static final String HOME_BLOCK_SUBSTR = "企鹅群";

    /** 默认超时秒数 */
    public static final int DEFAULT_TIMEOUT = 5;

    /** 筛选字段中文名（上游 static 匿名 Map） */
    public static final Map<String, String> FILTER_NAMES = filterNames();

    /** 直链正则（修缺陷 3：允许尾部 query/fragment） */
    public static final Pattern DIRECT =
            Pattern.compile("https?://.*\\.(m3u8|mp4|flv|mkv)([?#].*)?$");

    // ── ext 状态 ────────────────────────────────────────────

    /** 解析后的基址 */
    private String host = "";
    /** 每个用途的 UA 覆盖（键：host/config/home/homeVideo/category/search/parse/player） */
    private final Map<String, String> uaByUse = new HashMap<>();
    /** ext.parse 展平后的解析接口表 */
    private final Map<String, Object> parseMap = new HashMap<>();
    /** 配置接口下发的解析接口表（playerCode → url） */
    private final Map<String, String> jiexiMap = new HashMap<>();
    /** 搜索缓存（detailContent 优先命中） */
    private final List<JSONObject> searchCache = new ArrayList<>();

    private int timeout = DEFAULT_TIMEOUT;
    /** 1 = 自带接口；2 = nav 模式 */
    private int ver = 1;
    /** 分类模式：0 禁用 / 1 正常 / 2 msearch */
    private int categoryMode = 1;
    /** 自定义解析优先级 */
    private int customFirst = 0;
    /** CMS 基址（非空即走 CMS 后端） */
    private String cms = "";
    /** {@code from} 参数 */
    private String from = "";

    /** 默认请求头 */
    private final Map<String, String> defaultHeaders = buildDefaultHeaders();

    /** 播放上下文（原上游 public static，改为实例字段 —— 缺陷 4） */
    private String vodName = "";
    private String vodIndex = "";

    /** 最近一次请求 URL —— 测试断言用 */
    private String lastUrl = "";
    /** 已发出的请求 URL 列表 */
    private final List<String> requestLog = new ArrayList<>();

    /** 构造 */
    public AppFoxPlain() {
        super();
    }

    private static Map<String, String> buildDefaultHeaders() {
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", DEFAULT_UA);
        h.put("Connection", "Keep-Alive");
        h.put("Accept-Language", "zh-CN,zh;q=0.8");
        h.put("Cache-Control", "no-cache");
        return h;
    }

    private static Map<String, String> filterNames() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("class", "类型");
        m.put("area", "地区");
        m.put("lang", "语言");
        m.put("year", "年份");
        m.put("sort", "排序");
        return m;
    }

    // ── 生命周期 ────────────────────────────────────────────

    /**
     * 解析 ext（A/B 类见类注释）。
     *
     * @param context 宿主上下文，可为 null
     * @param extend  ext 原文，可为 null
     */
    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        if (extend == null || extend.trim().isEmpty()) {
            return;
        }
        String raw = extend.trim();
        if (raw.startsWith("http")) {
            this.host = raw;
        } else {
            JSONObject o = ext.json();
            // 缺陷 5：上游 getString("host") 缺键即抛
            this.host = o.optString("host", "");
            if (o.optInt("ver", 1) == 2) {
                this.ver = 2;
            }
            String c = o.optString("cms", "");
            if (!c.isEmpty()) {
                this.cms = normalizeCms(c);
            }
            String f = o.optString("from", "");
            if (!f.isEmpty()) {
                this.from = f;
            }
            JSONObject p = o.optJSONObject("parse");
            if (p != null) {
                parseMap.putAll(flatten(p));
            }
            this.customFirst = o.optInt("custom_first", 0);
            if (o.has("category")) {
                this.categoryMode = o.optInt("category", 1);
            }
            // ua 有两种形态：字符串（覆盖全局）或对象（按用途覆盖）。
            // 这里用 optJSONObject 而不是 instanceof JSONObject ——
            // 宿主/测试用的 JSON 实现可能把嵌套对象给成 Map，
            // optJSONObject 两种都能收（本仓库 stub 就是 Map）。
            Object ua = o.opt("ua");
            if (ua instanceof String) {
                defaultHeaders.put("User-Agent", (String) ua);
            } else {
                JSONObject u = o.optJSONObject("ua");
                if (u != null) {
                    for (String k : useKeys()) {
                        String v = u.optString(k, "");
                        if (!v.isEmpty()) {
                            uaByUse.put(k, v);
                        }
                    }
                }
            }
            this.timeout = o.optInt("timeout", DEFAULT_TIMEOUT);
        }
        resolveHost();
        Log.i(tag(), "init ok, host=" + host + ", cms=" + (cms.isEmpty() ? "无" : "有")
                + ", ver=" + ver + ", categoryMode=" + categoryMode);
    }

    /** ua 对象允许的用途键 */
    public static String[] useKeys() {
        return new String[]{"host", "config", "home", "homeVideo", "category", "search",
                "parse", "player"};
    }

    /**
     * CMS 基址规范化（还原 {@code replaceAll("/$","")} + {@code /vod} 补问号）。
     *
     * @param c 原始 cms 值
     * @return 规范化结果
     */
    public static String normalizeCms(String c) {
        String v = c.replaceAll("/$", "");
        if (v.matches("^https?:\\/\\/.*\\/vod")) {
            v = v + (v.contains("?") ? "&" : "?");
        }
        return v;
    }

    /**
     * 判断 host 是不是「纯域名」（是则不触发 apiDomain 解析）。
     *
     * @param h 地址
     * @return 是否纯域名
     */
    public static boolean isPlainDomain(String h) {
        return h != null && h.matches("^https?:\\/\\/[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*(:\\d+)?(\\/)?$");
    }

    /**
     * host 不是纯域名时，去它那里取 {@code apiDomain}（上游行为）。
     */
    private void resolveHost() {
        if (host.isEmpty() || isPlainDomain(host)) {
            host = host.replaceAll("/$", "");
            return;
        }
        Map<String, String> h = headersFor("host");
        String body = fetch(host, h);
        try {
            this.host = new JSONObject(body).optString("apiDomain", "");
        } catch (Throwable e) {
            this.host = body == null ? "" : body.trim();
        }
        this.host = this.host.replaceAll("/$", "");
    }

    // ── 状态访问器 ──────────────────────────────────────────

    /** @return 解析后的基址 */
    public String host() {
        return host;
    }

    /** @return CMS 基址 */
    public String cms() {
        return cms;
    }

    /** @return 分类模式 */
    public int categoryMode() {
        return categoryMode;
    }

    /** @return ver */
    public int ver() {
        return ver;
    }

    /** @return from */
    public String from() {
        return from;
    }

    /** @return 超时秒数 */
    public int timeout() {
        return timeout;
    }

    /** @return 自定义解析优先级 */
    public int customFirst() {
        return customFirst;
    }

    /** @return 上次播放的片名（原 public static，缺陷 4） */
    public String vodName() {
        return vodName;
    }

    /** @return 上次播放的集序号 */
    public String vodIndex() {
        return vodIndex;
    }

    /** @return 配置接口下发的解析接口表（只读副本） */
    public Map<String, String> jiexiMap() {
        return new LinkedHashMap<>(jiexiMap);
    }

    /** @return ext.parse 展平后的表（只读副本） */
    public Map<String, Object> extParseMap() {
        return new LinkedHashMap<>(parseMap);
    }

    /** @return 默认请求头（只读副本） */
    public Map<String, String> defaultHeaders() {
        return new LinkedHashMap<>(defaultHeaders);
    }

    /** @return 最近一次请求 URL */
    public String lastUrl() {
        return lastUrl;
    }

    /** @return 全部请求 URL */
    public List<String> requestLog() {
        return new ArrayList<>(requestLog);
    }

    /** @return 搜索缓存条数 */
    public int searchCacheSize() {
        return searchCache.size();
    }

    // ── 请求 ────────────────────────────────────────────────

    /**
     * 按用途取请求头（默认头 + 该用途的 UA 覆盖）。
     *
     * @param use 用途名
     * @return header map
     */
    public Map<String, String> headersFor(String use) {
        LinkedHashMap<String, String> h = new LinkedHashMap<>(defaultHeaders);
        String ua = uaByUse.get(use);
        if (ua != null && !ua.isEmpty()) {
            h.put("User-Agent", ua);
        }
        return h;
    }

    /**
     * 发一次 GET（可被测试覆写）。
     *
     * @param url     完整地址
     * @param headers 请求头
     * @return 响应体；失败为空串
     */
    public String fetch(String url, Map<String, String> headers) {
        record(url);
        return http.get(url, headers);
    }

    /**
     * 记录一次请求（供测试子类覆写 {@code fetch} 时复用，保证
     * {@link #lastUrl()} / {@link #requestLog()} 仍有值）。
     *
     * @param url 完整地址
     */
    protected void record(String url) {
        this.lastUrl = url;
        this.requestLog.add(url);
    }

    // ── 首页 ────────────────────────────────────────────────

    /**
     * 首页：分类表 + 筛选定义（<b>刻意不含 {@code list}</b>，见方法内注释）。
     *
     * @param filter 忽略（上游无条件带 filters）
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        if (host.isEmpty() || categoryMode == 0) {
            return emptyResponse();
        }
        JSONArray classes = new JSONArray();
        JSONObject filters = new JSONObject();
        try {
            if (cms.isEmpty() || categoryMode == 2) {
                JSONObject data = Http.parseJson(
                        fetch(host + "/api.php/Appfox/init", headersFor("home")))
                        .optJSONObject("data");
                JSONArray types = data == null ? null : data.optJSONArray("type_list");
                for (int i = 0; types != null && i < types.length(); i++) {
                    JSONObject t = types.optJSONObject(i);
                    if (t == null) {
                        continue;
                    }
                    String name = t.optString("type_name", "");
                    if (!isHomeTypeVisible(name)) {
                        continue;
                    }
                    classes.put(new JSONObject()
                            .put("type_id", t.optString("type_id", ""))
                            .put("type_name", name));
                    buildFilters(t, filters);
                }
            } else {
                // 缺陷 2：上游这里请求两次并丢弃第二次结果，本实现只请求一次
                classes = Http.parseJson(fetch(cms, headersFor("home"))).optJSONArray("class");
                if (classes == null) {
                    classes = new JSONArray();
                }
            }
            JSONObject root = new JSONObject();
            root.put("class", classes);
            root.put("filters", filters);
            // 刻意不输出 list：上游就是这样，宿主见不到 list 才会去调 homeVideoContent
            // （补一个空 list 会让首页推荐永远为空）
            return root.toString();
        } catch (Throwable e) {
            Log.e(tag(), "homeContent failed", e);
            return emptyResponse();
        }
    }

    /**
     * 首页分类名是否可见（还原上游 4 个判断）。
     *
     * @param name 分类名
     * @return 是否保留
     */
    public static boolean isHomeTypeVisible(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (String b : HOME_BLOCK_NAMES) {
            if (b.equals(name)) {
                return false;
            }
        }
        return !name.contains(HOME_BLOCK_SUBSTR);
    }

    /**
     * 把一个分类的 {@code filter_type_list} 转成标准 filters 片段（还原上游静态 {@code a}）。
     *
     * <pre>
     *  {"key":"area","name":"地区","value":[{"n":"全部","v":"全部"}]}
     * </pre>
     * 注意 {@code sort} 的 key 会被改写成 {@code by}（上游如此），
     * 因为宿主的排序参数名固定是 {@code by}。
     *
     * @param type 分类对象（需含 {@code type_id} 与 {@code filter_type_list}）
     * @param out  输出对象（type_id → 筛选组数组）
     */
    public static void buildFilters(JSONObject type, JSONObject out) throws org.json.JSONException {
        if (type == null || out == null) {
            return;
        }
        JSONArray list = type.optJSONArray("filter_type_list");
        if (list == null) {
            return;
        }
        JSONArray groups = new JSONArray();
        try {
            for (int i = 0; i < list.length(); i++) {
                JSONObject f = list.optJSONObject(i);
                if (f == null) {
                    continue;
                }
                String name = f.optString("name", "");
                JSONArray values = f.optJSONArray("list");
                if (values == null || values.length() == 0) {
                    continue;
                }
                JSONArray vv = new JSONArray();
                for (int j = 0; j < values.length(); j++) {
                    String s = values.optString(j, "");
                    vv.put(new JSONObject().put("n", s).put("v", s));
                }
                String label = FILTER_NAMES.containsKey(name) ? FILTER_NAMES.get(name) : name;
                String key = "sort".equals(name) ? "by" : name;
                groups.put(new JSONObject().put("key", key).put("name", label).put("value", vv));
            }
            if (groups.length() > 0) {
                out.put(type.optString("type_id", ""), groups);
            }
        } catch (Throwable e) {
            Log.e(TAG, "buildFilters failed", e);
        }
    }

    // ── 首页推荐 ────────────────────────────────────────────

    /**
     * 首页推荐：{@code /api.php/Appfox/index}（或 nav 模式下的 {@code nav_video}）。
     *
     * @return 标准列表 JSON
     */
    @Override
    public String homeVideoContent() {
        if (host.isEmpty() || categoryMode == 0) {
            return emptyResponse();
        }
        if (!cms.isEmpty() && categoryMode != 2) {
            return emptyResponse();
        }
        try {
            String path;
            if (ver == 2) {
                JSONArray nav = Http.parseJson(
                        fetch(host + "/api.php/appfox/nav", headersFor("homeVideo")))
                        .optJSONArray("data");
                String navId = "";
                for (int i = 0; nav != null && i < nav.length(); i++) {
                    JSONObject o = nav.optJSONObject(i);
                    if (o != null) {
                        navId = o.optString("navigationId", "");
                        break;
                    }
                }
                if (navId.isEmpty()) {
                    return emptyResponse();
                }
                path = "nav_video?id=" + Http.encode(navId);
            } else {
                path = "index";
            }
            JSONArray data = Http.parseJson(
                    fetch(host + "/api.php/Appfox/" + path, headersFor("homeVideo")))
                    .optJSONArray("data");
            JSONArray list = new JSONArray();
            for (int i = 0; data != null && i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                JSONArray banner = o.optJSONArray("banner");
                for (int j = 0; banner != null && j < banner.length(); j++) {
                    JSONObject b = banner.optJSONObject(j);
                    if (b != null) {
                        list.put(b);
                    }
                }
                JSONArray cats = o.optJSONArray("categories");
                for (int j = 0; cats != null && j < cats.length(); j++) {
                    JSONObject c = cats.optJSONObject(j);
                    JSONArray videos = c == null ? null : c.optJSONArray("videos");
                    for (int k = 0; videos != null && k < videos.length(); k++) {
                        JSONObject v = videos.optJSONObject(k);
                        if (v != null) {
                            list.put(v);
                        }
                    }
                }
            }
            if (categoryMode == 2) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.optJSONObject(i);
                    if (o != null) {
                        o.put("vod_id", MSEARCH + o.optString("vod_id", ""));
                    }
                }
            }
            JSONObject root = new JSONObject();
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            Log.e(tag(), "homeVideoContent failed", e);
            return emptyResponse();
        }
    }

    // ── 分类 ────────────────────────────────────────────────

    /**
     * 分类翻页。
     *
     * @param tid    分类 id
     * @param pg     页码
     * @param filter 忽略
     * @param extend 筛选（class/area/lang/year/by）
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        if (host.isEmpty()) {
            return emptyResponse();
        }
        Map<String, String> h = headersFor("category");
        // 缺陷 1：上游 5 个键全读 area；本实现各读各的
        Map<String, String> sel = filterSelection(extend);
        try {
            JSONObject result;
            if (cms.isEmpty() || categoryMode == 2) {
                String url = host + "/api.php/Appfox/vodList"
                        + "?type_id=" + Http.encode(tid)
                        + "&page=" + Http.encode(pg)
                        + "&class=" + Http.encode(sel.get("class"))
                        + "&area=" + Http.encode(sel.get("area"))
                        + "&lang=" + Http.encode(sel.get("lang"))
                        + "&year=" + Http.encode(sel.get("year"))
                        + "&sort=" + Http.encode(sel.get("sort"));
                JSONObject data = Http.parseJson(fetch(url, h)).optJSONObject("data");
                JSONArray list = data == null ? null : data.optJSONArray("recommend_list");
                result = new JSONObject();
                result.put("list", list == null ? new JSONArray() : list);
            } else {
                StringBuilder sb = new StringBuilder(cms);
                sb.append("pg=").append(Http.encode(pg)).append("&t=").append(Http.encode(tid));
                for (Map.Entry<String, String> e : sel.entrySet()) {
                    if (!"全部".equals(e.getValue())) {
                        sb.append("&").append(e.getKey()).append("=").append(Http.encode(e.getValue()));
                    }
                }
                result = Http.parseJson(fetch(sb.toString(), h));
            }
            JSONArray list = result.optJSONArray("list");
            if (categoryMode == 2 && list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.optJSONObject(i);
                    if (o != null) {
                        o.put("vod_id", MSEARCH + o.optString("vod_id", ""));
                    }
                }
            }
            int page = pageNum(pg, 1);
            return VodBuilder.create()
                    .addAll(vodsOf(list))
                    .page(page, 0, 0)
                    .categoryContent();
        } catch (Throwable e) {
            Log.e(tag(), "categoryContent failed", e);
            return emptyResponse();
        }
    }

    /**
     * 取筛选值（每键独立，修缺陷 1）。
     *
     * @param extend 宿主筛选
     * @return 键 → 值（缺省「全部」）
     */
    public static Map<String, String> filterSelection(Map<String, String> extend) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (String k : new String[]{"class", "area", "lang", "year", "sort"}) {
            String v = extend == null ? null : extend.get(k);
            m.put(k, v == null || v.isEmpty() ? "全部" : v);
        }
        return m;
    }

    /**
     * 列表数组 → Vod（字段名与本族其它源不同：{@code vod_id/vod_name/vod_pic/vod_remarks}）。
     *
     * @param a 数组，可为 null
     * @return 非 null 列表
     */
    public static List<Vod> vodsOf(JSONArray a) {
        List<Vod> out = new ArrayList<>();
        if (a == null) {
            return out;
        }
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) {
                continue;
            }
            out.add(new Vod(o.optString("vod_id", ""), o.optString("vod_name", ""))
                    .pic(o.optString("vod_pic", ""))
                    .remarks(o.optString("vod_remarks", "")));
        }
        return out;
    }

    // ── 详情 ────────────────────────────────────────────────

    /**
     * 详情：优先命中搜索缓存（上游行为），否则按 id 拉取。
     *
     * @param ids 影片 id 列表
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (id.isEmpty()) {
            return emptyResponse();
        }
        Map<String, String> h = headersFor("search");
        String ua = uaByUse.get("detail");
        if (ua != null && !ua.isEmpty()) {
            h.put("User-Agent", ua);
        }
        try {
            JSONObject vod = fromCache(id);
            if (vod == null) {
                vod = fetchDetail(id, h);
            }
            if (vod == null) {
                return emptyListJson();
            }
            String name = vod.optString("vod_name", "");
            String[] froms = split(vod.optString("vod_play_from", ""), "\\$\\$\\$");
            String[] groups = split(vod.optString("vod_play_url", ""), "\\$\\$\\$");
            rewritePlayUrls(vod, name, froms, groups);
            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Throwable e) {
            Log.e(tag(), "detailContent failed, id=" + id, e);
            return emptyListJson();
        }
    }

    /**
     * 空列表响应（不抛 checked 异常）。
     *
     * @return {@code {"list":[]}}
     */
    public static String emptyListJson() {
        return "{\"list\":[]}";
    }

    /**
     * 从搜索缓存里取（id 相同即命中）。
     *
     * @param id 影片 id
     * @return 缓存行副本；未命中返回 null
     */
    public JSONObject fromCache(String id) {
        for (JSONObject o : searchCache) {
            if (id.equals(o.optString("vod_id", ""))) {
                return Http.parseJson(o.toString());
            }
        }
        return null;
    }

    /**
     * 按 id 拉详情（两套后端）。
     *
     * @param id 影片 id
     * @param h  请求头
     * @return 详情对象；未找到返回 null
     */
    public JSONObject fetchDetail(String id, Map<String, String> h) {
        String url;
        if (cms.isEmpty()) {
            url = host + "/api.php/Appfox/vod?ac=detail&ids=" + Http.encode(id);
        } else {
            String base = cms;
            if (base.contains("?")) {
                base = cms.split("\\?")[0] + "?";
            }
            url = base + "ac=detail&ids=" + Http.encode(id);
        }
        JSONArray list = Http.parseJson(fetch(url, h)).optJSONArray("list");
        return list == null || list.length() == 0 ? null : list.optJSONObject(0);
    }

    /**
     * 重写 {@code vod_play_from} / {@code vod_play_url}（含 config 接口的播放器名映射）。
     *
     * @param vod    详情对象（就地修改）
     * @param name   片名
     * @param froms  原始线路 code
     * @param groups 原始分集组
     */
    public void rewritePlayUrls(JSONObject vod, String name, String[] froms, String[] groups) {
        try {
            JSONObject cfg = Http.parseJson(
                    fetch(host + "/api.php/Appfox/config", headersFor("config")));
            JSONObject data = cfg.optJSONObject("data");
            JSONArray players = data == null ? null : data.optJSONArray("playerList");
            JSONArray jiexi = data == null ? null : data.optJSONArray("jiexiDataList");
            if (players == null || jiexi == null) {
                return;
            }
            Map<String, JSONObject> byCode = new HashMap<>();
            for (int i = 0; i < players.length(); i++) {
                JSONObject p = players.optJSONObject(i);
                if (p != null) {
                    byCode.put(p.optString("playerCode", ""), p);
                }
            }
            List<String> names = new ArrayList<>();
            for (String code : froms) {
                JSONObject p = byCode.get(code);
                if (p == null) {
                    names.add(code);
                    continue;
                }
                String pn = p.optString("playerName", "");
                names.add(pn.equals(code) ? code : pn + NAME_SEP + "(" + code + ")");
            }
            List<String> newGroups = new ArrayList<>();
            for (int i = 0; i < froms.length; i++) {
                if (i >= groups.length) {
                    break;
                }
                List<String> eps = new ArrayList<>();
                for (String ep : split(groups[i], "#")) {
                    String[] p = split(ep, "\\$");
                    if (p.length >= 2) {
                        eps.add(p[0] + "$" + froms[i] + "@" + p[1] + "@" + name + "@" + p[0]);
                    } else {
                        eps.add(ep);
                    }
                }
                newGroups.add(join(eps, "#"));
            }
            vod.put("vod_play_from", join(names, "$$$"));
            vod.put("vod_play_url", join(newGroups, "$$$"));

            jiexiMap.clear();
            for (int i = 0; i < jiexi.length(); i++) {
                JSONObject o = jiexi.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String url = o.optString("url", "");
                if (url.startsWith("http")) {
                    jiexiMap.put(o.optString("playerCode", ""), url);
                }
            }
        } catch (Throwable e) {
            Log.w(tag(), "config 接口失败，保留原始线路名: " + e);
        }
    }

    // ── 搜索 ────────────────────────────────────────────────

    /**
     * 搜索（上游两参数版）。
     *
     * @param key   关键词
     * @param quick 忽略
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    /**
     * 搜索（上游三参数版）。
     *
     * @param key         关键词
     * @param quick       忽略
     * @param quickSearch 忽略（上游未使用）
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick, String quickSearch) {
        if (host.isEmpty()) {
            return emptyResponse();
        }
        Map<String, String> h = headersFor("search");
        try {
            String url;
            if (cms.isEmpty()) {
                url = host + "/api.php/Appfox/vod?ac=detail&wd=" + Http.encode(key);
                if (!from.isEmpty()) {
                    url += "&from=" + Http.encode(from);
                }
            } else {
                String base = cms;
                if (base.contains("?")) {
                    base = cms.split("\\?")[0] + "?";
                }
                url = base + "ac=detail&wd=" + Http.encode(key);
            }
            JSONObject root = Http.parseJson(fetch(url, h));
            searchCache.clear();
            JSONArray list = root.optJSONArray("list");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o != null) {
                    searchCache.add(o);
                }
            }
            return VodBuilder.create().addAll(vodsOf(list)).searchContent();
        } catch (Throwable e) {
            Log.e(tag(), "searchContent failed", e);
            return emptyResponse();
        }
    }

    // ── 播放 ────────────────────────────────────────────────

    /**
     * 播放解析。
     *
     * <p>id 形如 {@code from@url@片名@集名}（由 {@link #rewritePlayUrls} 生成）。
     * 若 url 已是直链（含尾部 query 也算）则直接回；否则按解析接口表替换：
     * <ul>
     *   <li>{@code parse:前缀} → 客户端解析（{@code parse=1}）</li>
     *   <li>http 地址 → 服务端解析接口（先请求，取响应里的 {@code url}）</li>
     * </ul>
     *
     * @param flag     忽略
     * @param id       {@code from@url@片名@集名}
     * @param vipFlags 忽略
     * @return 播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String[] p = (id == null ? "" : id).split("@", 4);
        if (p.length != 4) {
            Log.w(tag(), "播放 id 段数不是 4: " + id);
            return emptyResponse();
        }
        String fromCode = p[0];
        String url = p[1];
        this.vodName = p[2];
        this.vodIndex = p[3];

        Map<String, String> h = headersFor("parse");
        String playerUa = uaByUse.get("player");
        if (playerUa == null || playerUa.isEmpty()) {
            playerUa = DEFAULT_PLAYER_UA;
        }
        List<Map<String, Object>> tables = new ArrayList<>();
        if (customFirst == 1) {
            tables.add(parseMap);
            tables.add(asObjectMap(jiexiMap));
        } else {
            tables.add(asObjectMap(jiexiMap));
            tables.add(parseMap);
        }
        try {
            int parse = 0;
            boolean resolved = false;
            if (!isDirect(url)) {
                for (Map<String, Object> table : tables) {
                    if (parse == 1 || resolved) {
                        break;
                    }
                    for (Map.Entry<String, Object> e : table.entrySet()) {
                        if (e.getKey() == null || !e.getKey().contains(fromCode)) {
                            continue;
                        }
                        Object v = e.getValue();
                        if (v instanceof List) {
                            for (Object item : (List<?>) v) {
                                String s = String.valueOf(item);
                                if (s.startsWith("parse:")) {
                                    url = s.split("parse:")[1] + url;
                                    parse = 1;
                                    break;
                                }
                                String r = tryResolve(s + url, h);
                                if (r != null) {
                                    url = r;
                                    resolved = true;
                                    break;
                                }
                            }
                        } else if (v != null) {
                            String s = String.valueOf(v);
                            if (s.startsWith("parse:")) {
                                url = s.split("parse:")[1] + url;
                                parse = 1;
                                break;
                            }
                            String r = tryResolve(s + url, h);
                            if (r != null) {
                                url = r;
                                resolved = true;
                                break;
                            }
                        }
                    }
                }
            }
            // jx=1 表示还需要客户端嗅探/解析
            int jx = (isDirect(url) || resolved) ? 0 : 1;
            JSONObject o = new JSONObject();
            o.put("jx", jx);
            o.put("parse", parse);
            o.put("url", url);
            o.put("danmaku", danmakuUrl(vodName, vodIndex));
            o.put("header", new JSONObject().put("User-Agent", playerUa));
            return o.toString();
        } catch (Throwable e) {
            Log.e(tag(), "playerContent failed, id=" + id, e);
            return emptyResponse();
        }
    }

    /**
     * 请求一个解析接口，成功时返回其中的 {@code url}。
     *
     * @param url     解析接口地址（已拼上原播放地址）
     * @param headers 请求头
     * @return 解析出的地址；失败返回 null
     */
    public String tryResolve(String url, Map<String, String> headers) {
        try {
            JSONObject o = Http.parseJson(fetch(url, headers));
            String u = o.optString("url", "");
            if (o.has("url") && u.startsWith("http")) {
                return u;
            }
        } catch (Throwable e) {
            Log.w(tag(), "解析接口失败: " + url);
        }
        return null;
    }

    /**
     * 是否直链（修缺陷 3：允许尾部 query/fragment）。
     *
     * @param url 地址
     * @return 是否直链
     */
    public static boolean isDirect(String url) {
        return url != null && DIRECT.matcher(url).matches();
    }

    private static Map<String, Object> asObjectMap(Map<String, String> src) {
        return new LinkedHashMap<String, Object>(src);
    }

    // ── JSON 展平 ───────────────────────────────────────────

    /**
     * 把 JSON 对象递归展平成 {@code Map/List}（还原上游静态 {@code c}）。
     *
     * @param o 对象，可为 null
     * @return 非 null map
     */
    public static Map<String, Object> flatten(JSONObject o) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        if (o == null) {
            return m;
        }
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            if (v instanceof JSONObject) {
                m.put(k, flatten((JSONObject) v));
            } else if (v instanceof JSONArray) {
                m.put(k, flatten((JSONArray) v));
            } else {
                m.put(k, v);
            }
        }
        return m;
    }

    /**
     * 把 JSON 数组递归展平（还原上游静态 {@code b}）。
     *
     * @param a 数组，可为 null
     * @return 非 null 列表
     */
    public static List<Object> flatten(JSONArray a) {
        List<Object> out = new ArrayList<>();
        if (a == null) {
            return out;
        }
        for (int i = 0; i < a.length(); i++) {
            Object v = a.opt(i);
            if (v instanceof JSONObject) {
                out.add(flatten((JSONObject) v));
            } else if (v instanceof JSONArray) {
                out.add(flatten((JSONArray) v));
            } else {
                out.add(v);
            }
        }
        return out;
    }

    // ── 小工具 ──────────────────────────────────────────────

    /**
     * split（null 安全）。
     *
     * @param s  原文
     * @param re 正则
     * @return 非 null 数组
     */
    public static String[] split(String s, String re) {
        return (s == null ? "" : s).split(re);
    }

    /**
     * join（空列表 → 空串）。
     *
     * @param list 元素列表
     * @param sep  分隔符
     * @return 拼接结果
     */
    public static String join(List<String> list, String sep) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            String s = list.get(i);
            if (s != null) {
                sb.append(s);
            }
        }
        return sb.toString();
    }
}
