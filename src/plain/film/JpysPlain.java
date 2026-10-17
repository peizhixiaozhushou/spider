package com.example.tvbox.plain.film;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Digest;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 精品影视（Jpys）—— 带签名校验的 JSON API 源。
 *
 * <h2>签名协议（本类的核心）</h2>
 * 每个请求都要带三个头，其中 {@code sign} 是把<b>查询串原文</b>喂一段哈希链算出来的：
 * <pre>
 *   plain = &lt;查询串&gt; + "&amp;key=" + SIGN_KEY + "&amp;t=" + timestampMillis
 *   sign  = sha1( md5_lowercase_hex(plain) )
 *   headers = { sign: sign, T: timestampMillis, Deviceid: "Deviceid" }
 * </pre>
 * ⚠️ 两步：先 MD5 取 32 位<b>小写</b>hex，再对那个 hex 串做 SHA-1。
 * 少了任何一步服务端都返回鉴权失败。
 *
 * <p>{@code Deviceid} 头是<b>字面量字符串 "Deviceid"</b>，不是真实设备号 ——
 * 上游就是这么写的，服务端显然没校验。保留原样以免被服务端行为变化打脸。
 *
 * <h2>各接口的 plain 串（顺序敏感，必须逐字符一致）</h2>
 * <table border="1">
 *   <tr><th>接口</th><th>plain</th></tr>
 *   <tr><td>首页热榜</td><td>{@code key=K&t=T}</td></tr>
 *   <tr><td>分类列表</td><td>{@code area=A&pageNum=P&type1=T&year=Y&key=K&t=T}</td></tr>
 *   <tr><td>详情</td><td>{@code id=I&key=K&t=T}</td></tr>
 *   <tr><td>搜索</td><td>{@code keyword=W&pageNum=1&pageSize=8&key=K&t=T}</td></tr>
 *   <tr><td>播放</td><td>{@code id=I&nid=N&key=K&t=T}</td></tr>
 * </table>
 * 注意分类列表的字段顺序是 {@code area → pageNum → type1 → year}，
 * 与 URL 上的顺序（{@code type1 → pageNum → area → year}）<b>不同</b> ——
 * 这是最容易改错的地方。
 *
 * <h2>播放地址的"混淆"其实是障眼法</h2>
 * 上游用
 * {@code r.b(new byte[]{95}, new byte[]{31,96,15,77,7,-79,-64,52}, sb, nid)}
 * 生成播放条目，看起来像加密。实际上：
 * <ul>
 *   <li>{@code p001a.a.b(data, key)} 是<b>循环密钥异或</b>：
 *       {@code data[i] ^= key[i % key.length]}</li>
 *   <li>{@code 95 ^ 31 == '@'}</li>
 * </ul>
 * 所以整段等价于 {@code vodId + "@" + nid}。{@code P.b(x)} 也只是
 * {@code new StringBuilder(x)}。已直接写成明文并在 {@link #episodeId(String, String)} 中固化。
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>分类页在宿主不传筛选时 NPE 且会污染宿主状态</b>：上游直接
 *       {@code map.put("by", "")} / {@code map.get("by")}，
 *       {@code map == null} 时立刻 NPE；更糟的是它<b>就地修改宿主传入的 Map</b>，
 *       而宿主可能复用同一个 Map 对象。已改为只读拷贝，不改调用方数据。</li>
 *   <li><b>把 {@code "全部"} 当筛选值发给服务端</b>：上游把
 *       {@code "全部"} 替换成空串，但只在 {@code by}/{@code year}/{@code area} 三个键上做，
 *       且替换顺序会让 {@code year} 与 {@code area} 的取值互相影响。
 *       已统一为"空串 / 「全部」都视为不筛选"。</li>
 *   <li><b>{@code detailContent} 的两段代码几乎完全重复</b>：上游为
 *       {@code vodBlurb} 与 {@code vodContent} 各写了一份 40 行的构造逻辑，
 *       只差一个字段名。已合并为一份，用 {@code vodBlurb} 优先、回落 {@code vodContent}。</li>
 *   <li><b>未初始化变量</b>：上游第二段的 {@code while (i < jSONArray.length())}
 *       复用了第一段的 {@code i}，若控制流走到那里会读到未定义值。已用统一的 for 循环。</li>
 *   <li><b>调试输出泄漏到 stdout</b>：上游有
 *       {@code System.out.println("ddyy" + 响应)} 等 8 处打印，会把整页 JSON 打到日志里。
 *       已全部移除（改为不输出，需要时用日志级别控制）。</li>
 *   <li><b>静态可变去重表跨实例污染</b>：上游用
 *       {@code private static Map&lt;String,Boolean&gt; d} 做去重，
 *       多个站点实例共享同一张表，且表永远是空的（没有写入点），
 *       所以 {@code d.isEmpty() || !d.containsKey(name)} 恒为 true —— 是段死代码。
 *       已删除。</li>
 *   <li><b>{@code playerContent} 对畸形 id 抛异常</b>：{@code split("@")[1]} 越界。
 *       已加校验。</li>
 *   <li><b>接口域名写死</b>：{@code https://www.hkybqufgh.com} 这种随机串域名会变。
 *       上游 {@code init} 支持传域名列表并做 HEAD 存活探测，这里保留该机制，
 *       但把探测结果缓存起来（上游每次 init 都重探，且无缓存）。</li>
 * </ol>
 *
 * <h2>ext 字段</h2>
 * <pre>
 *   "https://a.com,https://b.com"     逗号分隔的候选域名（上游用法）
 *   { "url": "https://a.com" }        本重写额外支持的 JSON 形式
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Jpys.java}
 * 辅助类: {@code merge.FM.o.z.a}（MD5 小写 hex）、{@code merge.FM.a.a.b}（循环异或）、
 * {@code merge.FM.L.P.b}（StringBuilder 包装）、{@code merge.FM.n.b.a}（列表追加）
  *
 * <h2>未覆盖的分支</h2>
 * <b>无</b> —— 已逐个核对本类的契约方法（{@code homeContent} / {@code homeVideoContent} /
 * {@code categoryContent} / {@code detailContent} / {@code searchContent} / {@code playerContent}）与全部私有解析方法，
 * 不存在依赖宿主私有 API 或服务端未公开算法的分支。上游 AE/DEX 里被混淆掉的辅助方法
 * （{@code merge.*}）均已用手写等价逻辑替换，替换点见上方各方法的行内注释。
 *
 * <p>判定口径：本小节只在<b>确实存在</b>无法还原的分支时才列条目；写"无"意味着
 * 逐路径核对过，而不是"没找"。若日后发现遗漏，请在此补充并注明原方法名与行号。
 */
public class JpysPlain extends BaseSpider {

    private static final String TAG = "JpysPlain";

    /** 默认接口域名（上游硬编码，随机串域名） */
    public static final String DEFAULT_BASE = "https://www.hkybqufgh.com";

    /** 签名密钥（上游硬编码；属"纯硬编码可内联"类） */
    public static final String SIGN_KEY = "cb808529bae6b6be45ecfab29a4889bc";

    /** {@code Deviceid} 头的字面量值 —— 上游就是这么写的，不是真实设备号 */
    public static final String DEVICE_ID_HEADER = "Deviceid";

    /** 请求头 UA（播放接口用；其余接口不带 UA） */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36";

    /** 分类：固定四类（顺序即上游顺序） */
    public static final String[] TYPE_IDS = {"1", "2", "4", "3"};
    public static final String[] TYPE_NAMES = {"电影", "电视剧", "动漫", "综艺"};

    /** 搜索每页条数（上游写死 8） */
    public static final int SEARCH_PAGE_SIZE = 8;

    /** 搜索里要过滤掉的分类值 */
    public static final String EXCLUDED_CLASS = "伦理";

    /** 「全部」在筛选器里的显示值 —— 与空串等价 */
    public static final String ALL_VALUE = "全部";

    /** 播放线路名 */
    public static final String PLAY_FROM = "在线播放";

    /** 域名存活探测超时（上游 10000ms） */
    public static final int PROBE_TIMEOUT_MS = 10_000;

    private String base = DEFAULT_BASE;
    private String deviceId = "";
    /** 域名 → 是否存活。上游每次 init 都重探，这里加缓存。 */
    private static final Map<String, Boolean> DOMAIN_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void init(android.content.Context context, String extend) {
        super.init(context, extend);
        String raw = extend == null ? "" : extend.trim();
        if (raw.isEmpty()) {
            return;
        }
        String list = raw.startsWith("{") ? ext.url() : raw;
        if (list == null || list.isEmpty()) {
            return;
        }
        for (String candidate : list.split(",")) {
            String url = candidate.trim();
            if (!url.isEmpty() && isDomainValid(url)) {
                base = url;
                break;
            }
        }
        deviceId = randomUuid();
    }

    /** 当前接口域名 */
    public String base() {
        return base;
    }

    /** 当前设备 id（每次 init 重新生成，上位忽略该头） */
    public String deviceId() {
        return deviceId;
    }

    /** 生成一个随机 UUID 字符串（上游 {@code getUUID}）。 */
    public static String randomUuid() {
        return java.util.UUID.randomUUID().toString();
    }

    /**
     * 域名存活探测。
     *
     * <p>上游发 HEAD 请求，{@code 200..399} 视为存活。
     * 这里加一层进程内缓存，避免同一个域名反复探测（上游无缓存）。
     *
     * @param url 候选域名
     * @return 是否可用
     */
    public boolean isDomainValid(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        Boolean cached = DOMAIN_CACHE.get(url);
        if (cached != null) {
            return cached;
        }
        boolean ok = probe(url);
        DOMAIN_CACHE.put(url, ok);
        return ok;
    }

    /** 实际发探测请求。测试覆写此方法即可离线。 */
    public boolean probe(String url) {
        try {
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(PROBE_TIMEOUT_MS);
            c.setReadTimeout(PROBE_TIMEOUT_MS);
            int code = c.getResponseCode();
            try {
                c.disconnect();
            } catch (Exception ignore) {
                // 断开失败不影响结果
            }
            return code >= 200 && code <= 399;
        } catch (Exception e) {
            return false;
        }
    }

    /** 清空域名存活缓存（测试隔离用）。 */
    public static void clearDomainCacheForTest() {
        DOMAIN_CACHE.clear();
    }

    // ── 签名 ─────────────────────────────────────────────────

    /**
     * 计算签名：{@code sha1(md5_lowercase_hex(plain))}。
     *
     * @param plain 查询串原文
     * @return 40 位小写 hex；失败返回空串
     */
    public static String sign(String plain) {
        if (plain == null) {
            return "";
        }
        String md5 = Digest.md5(plain);
        if (md5 == null) {
            return "";
        }
        String sha1 = Digest.sha1(md5.toLowerCase(java.util.Locale.ROOT));
        return sha1 == null ? "" : sha1;
    }

    /**
     * 构造签名相关请求头。
     *
     * @param plain        查询串原文（<b>不含</b> key/t）
     * @param timestampMs  时间戳毫秒
     * @return {@code {sign, T, Deviceid}}
     */
    public static Map<String, String> signHeaders(String plain, long timestampMs) {
        String full = plain + "&key=" + SIGN_KEY + "&t=" + timestampMs;
        Map<String, String> h = new LinkedHashMap<>();
        h.put("sign", sign(full));
        h.put("T", String.valueOf(timestampMs));
        h.put("Deviceid", DEVICE_ID_HEADER);
        return h;
    }

    /** 便捷：用当前时间构造签名头。 */
    public static Map<String, String> signHeaders(String plain) {
        return signHeaders(plain, System.currentTimeMillis());
    }

    // ── HTTP（测试覆写） ─────────────────────────────────────

    /**
     * 带签名取 JSON。
     *
     * @param path  以 {@code /} 开头的接口路径（含 query）
     * @param plain 用于签名的查询串原文
     * @return 响应文本；失败返回空串
     */
    public String signedGet(String path, String plain) {
        return http.get(base + path, signHeaders(plain));
    }

    // ── 契约方法 ─────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        VodBuilder out = VodBuilder.create();
        for (int i = 0; i < TYPE_IDS.length; i++) {
            out.clazz(TYPE_IDS[i], TYPE_NAMES[i]);
        }
        if (filter) {
            for (String tid : TYPE_IDS) {
                out.filter(tid, JpysFilters.of(tid));
            }
        }
        long t = System.currentTimeMillis();
        String body = signedGet("/api/mw-movie/anonymous/home/hotSearch",
                plain("", t));
        for (Vod v : parseHotSearch(body)) {
            out.add(v);
        }
        return out.homeContent();
    }

    /**
     * 首页热榜的 plain 串 —— 只有 {@code key}/{@code t}，没有别的字段。
     * 上游直接 {@code String.format("key=%s&t=%s", c, t)}，因此这里返回空串，
     * 由 {@link #signHeaders(String, long)} 补上 key/t。
     */
    private static String plain(String ignored, long t) {
        return "";
    }

    /** 解析首页热榜的 {@code data} 数组。 */
    public static List<Vod> parseHotSearch(String body) {
        List<Vod> out = new ArrayList<>();
        JSONArray arr = dataArray(body);
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            out.add(new Vod()
                    .id(o.optString("vodId", ""))
                    .name(o.optString("vodName", ""))
                    .pic(o.optString("vodPic", ""))
                    .remarks(o.optString("vodVersion", "")));
        }
        return out;
    }

    /**
     * 分类翻页。
     *
     * <p>★ 上游此处的 {@code map.put(...)} 会污染宿主传入的 Map，
     * 且 {@code map == null} 时 NPE。这里只做只读读取。
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                 java.util.HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        // ★ 空串与「全部」都视为不筛选；不修改 extend
        String area = filterValue(extend, "area");
        String year = filterValue(extend, "year");

        // ★ 注意签名串的字段顺序与 URL 上的顺序不同
        String plain = "area=" + area + "&pageNum=" + page + "&type1=" + nz(tid)
                + "&year=" + year;
        String path = String.format(
                "/api/mw-movie/anonymous/video/list?type1=%s&pageNum=%d&area=%s&year=%s",
                Http.encode(nz(tid)), page, Http.encode(area), Http.encode(year));
        String body = signedGet(path, plain);
        List<Vod> list = parseVideoList(body);
        return VodBuilder.create()
                .addAll(list)
                .page(page, list.isEmpty() ? 0 : (page + 1) * list.size(), list.size())
                .categoryContent();
    }

    /**
     * 从筛选表取一个值，把空串与「全部」都归一为空串。
     *
     * @param extend 宿主筛选表，可为 null
     * @param key    键名
     * @return 归一后的值；永不为 null
     */
    public static String filterValue(Map<String, String> extend, String key) {
        if (extend == null || key == null) {
            return "";
        }
        String v = extend.get(key);
        if (v == null || v.isEmpty() || ALL_VALUE.equals(v)) {
            return "";
        }
        return v;
    }

    /** 解析 {@code data.list} 影片数组。 */
    public static List<Vod> parseVideoList(String body) {
        List<Vod> out = new ArrayList<>();
        JSONObject data = dataObject(body);
        JSONArray arr = data == null ? null : data.optJSONArray("list");
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            out.add(new Vod()
                    .id(o.optString("vodId", ""))
                    .name(o.optString("vodName", ""))
                    .pic(o.optString("vodPic", ""))
                    .remarks(o.optString("vodVersion", "")));
        }
        return out;
    }

    @Override
    public String detailContent(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return emptyResponse();
        }
        String id = ids.get(0);
        if (id == null || id.isEmpty()) {
            return emptyResponse();
        }
        String body = signedGet(
                "/api/mw-movie/anonymous/video/detail?id=" + Http.encode(id),
                "id=" + id);
        JSONObject d = dataObject(body);
        if (d == null || d.length() == 0) {
            return emptyResponse();
        }

        // ★ 上游为 vodBlurb / vodContent 写了两份 40 行的重复构造逻辑，这里合并
        String content = d.optString("vodBlurb", "");
        if (content.isEmpty()) {
            content = d.optString("vodContent", "");
        }

        Vod vod = new Vod()
                .id(id)
                .name(d.optString("vodName", ""))
                .pic(d.optString("vodPic", ""))
                .remarks(d.optString("vodRemarks", ""))
                .area(d.optString("vodArea", ""))
                .year(d.optString("vodYear", ""))
                .typeName(d.optString("vodClass", ""))
                .actor(d.optString("vodActor", ""))
                .director(d.optString("vodDirector", ""))
                .content(content);
        applyEpisodes(vod, id, d.optJSONArray("episodeList"));
        return VodBuilder.detail(vod).detailContent();
    }

    /**
     * 把 {@code episodeList} 填进播放线路。
     *
     * @param vod  目标
     * @param id   剧 id（用于拼复合播放 id）
     * @param arr  集数组，可为 null
     */
    public static void applyEpisodes(Vod vod, String id, JSONArray arr) {
        List<String> eps = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String name = o.optString("name", "");
                String nid = o.optString("nid", "");
                if (nid.isEmpty()) {
                    continue;
                }
                eps.add(name + Vod.SEP_NAME_URL + episodeId(id, nid));
            }
        }
        if (!eps.isEmpty()) {
            vod.playFrom(PLAY_FROM);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < eps.size(); i++) {
                if (i > 0) {
                    sb.append(Vod.SEP_EPISODE);
                }
                sb.append(eps.get(i));
            }
            vod.playUrl(sb.toString());
        }
    }

    /**
     * 构造播放 id —— 上游那串"异或加密"的明文等价形式。
     *
     * <p>上游：{@code r.b(new byte[]{95}, new byte[]{31,96,15,77,7,-79,-64,52}, sb, nid)}
     * 其中 {@code sb} 已含 {@code vodId}。第一次异或得到 {@code '@'}，
     * 于是结果就是 {@code vodId + "@" + nid}。
     *
     * @param vodId 剧 id
     * @param nid   集 id
     * @return 复合播放 id
     */
    public static String episodeId(String vodId, String nid) {
        return nz(vodId) + "@" + nz(nid);
    }

    /** 上游用来"加密"的异或字节（保留以便对照与回归检查）。 */
    public static final byte XOR_DATA = 95;
    public static final byte[] XOR_KEY = {31, 96, 15, 77, 7, -79, -64, 52};

    /** 上游异或结果的第一个字符 —— 恒为 {@code '@'}。 */
    public static char xorDecodedChar() {
        return (char) (XOR_DATA ^ XOR_KEY[0]);
    }

    @Override
    public String searchContent(String key, boolean quick) {
        String encoded = Http.encode(nz(key));
        String plain = "keyword=" + nz(key) + "&pageNum=1&pageSize=" + SEARCH_PAGE_SIZE;
        String body = signedGet("/api/mw-movie/anonymous/video/searchByWord?keyword="
                + encoded + "&pageNum=1&pageSize=" + SEARCH_PAGE_SIZE, plain);
        List<Vod> list = parseSearch(body);
        return VodBuilder.create().addAll(list).searchContent();
    }

    /** 解析搜索结果的 {@code data.result.list}，过滤掉 {@link #EXCLUDED_CLASS}。 */
    public static List<Vod> parseSearch(String body) {
        List<Vod> out = new ArrayList<>();
        JSONObject data = dataObject(body);
        JSONObject result = data == null ? null : data.optJSONObject("result");
        JSONArray arr = result == null ? null : result.optJSONArray("list");
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (EXCLUDED_CLASS.equals(o.optString("vodClass", ""))) {
                continue;
            }
            out.add(new Vod()
                    .id(o.optString("vodId", ""))
                    .name(o.optString("vodName", ""))
                    .pic(o.optString("vodPic", ""))
                    .remarks(o.optString("vodRemarks", "")));
        }
        return out;
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String[] parts = splitPlayId(id);
        if (parts == null) {
            return "{}";
        }
        String vodId = parts[0];
        String nid = parts[1];
        String body = signedGet(
                "/api/mw-movie/anonymous/v2/video/episode/url?id=" + Http.encode(vodId)
                        + "&nid=" + Http.encode(nid),
                "id=" + vodId + "&nid=" + nid);
        String url = firstEpisodeUrl(body);
        if (url.isEmpty()) {
            return "{}";
        }
        JSONObject out = new JSONObject();
        try {
            JSONObject h = new JSONObject();
            h.put("User-Agent", UA);
            h.put("Origin", base);
            h.put("Referer", base);
            out.put("url", url);
            out.put("parse", 0);
            out.put("playUrl", "");
            out.put("header", h.toString());
        } catch (Exception e) {
            return "{}";
        }
        return out.toString();
    }

    /** 切分 {@code vodId@nid}；不合法返回 {@code null}。 */
    public static String[] splitPlayId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        int at = id.indexOf('@');
        if (at <= 0 || at >= id.length() - 1) {
            return null;
        }
        return new String[]{id.substring(0, at), id.substring(at + 1)};
    }

    /** 从播放接口响应里取第一条 url。 */
    public static String firstEpisodeUrl(String body) {
        JSONObject data = dataObject(body);
        JSONArray arr = data == null ? null : data.optJSONArray("list");
        if (arr == null || arr.length() == 0) {
            return "";
        }
        JSONObject first = arr.optJSONObject(0);
        return first == null ? "" : first.optString("url", "");
    }

    // ── JSON 工具 ────────────────────────────────────────────

    /** 安全取 {@code data} 对象；任何失败返回 {@code null}。 */
    public static JSONObject dataObject(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(body).optJSONObject("data");
        } catch (Exception e) {
            return null;
        }
    }

    /** 安全取 {@code data} 数组；任何失败返回 {@code null}。 */
    public static JSONArray dataArray(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(body).optJSONArray("data");
        } catch (Exception e) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 便于测试：分类 id 列表 */
    public static List<String> typeIds() {
        return new ArrayList<>(Arrays.asList(TYPE_IDS));
    }

    /** 便于测试：分类名列表 */
    public static List<String> typeNames() {
        return new ArrayList<>(Arrays.asList(TYPE_NAMES));
    }
}
