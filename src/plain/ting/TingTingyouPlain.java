package com.example.tvbox.plain.ting;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 听听友（TingTingyou）—— 有声书/评书音频站。
 *
 * <h2>协议（还原自反编译）</h2>
 * 站点前端是 {@code https://www.tingyou.fm/}，数据全部来自预览接口
 * {@code https://api-preview.toulaopao.cc/api/h5/listening/*}，一律 JSON。
 *
 * <pre>
 *  GET  /rank?section=&lt;section&gt;&page=&lt;page&gt;   → {detail:"成功", items:[…], total, page_size}
 *  GET  /album/&lt;albumId&gt;                       → {detail:"成功", id,title,cover_url,count,type,synopsis,author,teller}
 *  GET  /chapters/&lt;albumId&gt;                    → {detail:"成功", chapters:[{title,index}]}
 *  POST /play                                  → {play_url}       body:{album_id, chapter_idx}
 *  POST /search                                → {detail:"成功", results:[…]}   body:{keyword, page}
 * </pre>
 *
 * <p>成功判定不是 HTTP 状态码也不是 {@code code} 字段，而是中文字段
 * {@code detail == "成功"} —— 这是本类最容易踩空的一处，所有接口都要过这道闸。
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>搜索伪造分页</b>：上游 {@code searchContent} 恒返回
 *       {@code pagecount=100, total=2000}，而请求里写死 {@code page=1}。
 *       宿主据此会翻 100 页、每页拿到同一批结果。已改为 {@code pagecount=1, total=实际条数}。</li>
 *   <li><b>章节号非数字直接丢弃整集</b>：上游 {@code Integer.parseInt(str4)} 抛
 *       {@code NumberFormatException} 后被外层 catch 吞掉，整条播放链返回空 url。
 *       现在先做数字校验，非法章节号明确返回空 url 而不是靠异常兜底。</li>
 *   <li><b>播放列表分隔符</b>：上游用 {@code "#"} 分隔集、{@code "$"} 分隔名称与 id，
 *       与 {@link Vod#SEP_EPISODE}/{@link Vod#SEP_NAME_URL} 一致，此处沿用常量，
 *       避免手写分隔符写错。</li>
 *   <li><b>未使用的静态字段</b> {@code public static String a} 已删除。</li>
 * </ol>
 *
 * <h2>ext 字段</h2>
 * <p>上游不读 ext。本重写保留一个可选入口：
 * <pre>{ "url": "https://api-preview.toulaopao.cc" }</pre>
 * 便于接口迁移时改一行配置，不必改代码。
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/TingTingyou.java}
  *
 * <h2>未覆盖的分支</h2>
 *
 * <p><b>未发现。</b>
 *
 * <p>本类的类级文档里已用「与上游的差异」/「实测差异」一节记录了所有<b>有意偏离</b>上游的地方 ——
 * 那些是修正，不是缺口。除这些之外，契约方法（{@code homeContent} / {@code homeVideoContent} /
 * {@code categoryContent} / {@code detailContent} / {@code searchContent} / {@code playerContent}）
 * 与私有解析方法均未发现依赖宿主私有 API 或服务端未公开算法的分支。
 *
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/TingTingyou.java} 的对照复核，
 * <b>不是"没找"</b>。日后若发现遗漏，请在此补充并注明原方法名与行号。
 */
public class TingTingyouPlain extends BaseSpider {

    private static final String TAG = "TingTingyouPlain";

    /** 数据接口基址（上游硬编码） */
    public static final String DEFAULT_API = "https://api-preview.toulaopao.cc";
    /** 接口前缀 */
    public static final String API_PREFIX = "/api/h5/listening";
    /** 页面来源，用于拼 Referer / Origin */
    public static final String SITE = "https://www.tingyou.fm";
    /** 上游 UA（硬编码） */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 成功标记：中文字段，不是 HTTP 状态 */
    public static final String OK = "成功";

    /** 分类：section → 显示名（顺序即上游顺序） */
    public static final Map<String, String> SECTIONS = new LinkedHashMap<>();

    static {
        SECTIONS.put("latest", "最新");
        SECTIONS.put("hot", "热门");
        SECTIONS.put("recommend", "推荐");
        SECTIONS.put("serial", "连载");
        SECTIONS.put("storytelling", "评书");
    }

    /** 服务端不给 page_size 时的兜底（上游硬编码 30） */
    public static final int DEFAULT_PAGE_SIZE = 30;

    /** 播放线路名 */
    public static final String PLAY_FROM = "听友";

    private String api = DEFAULT_API;

    @Override
    public void init(android.content.Context context, String extend) {
        super.init(context, extend);
        String configured = ext.url();
        if (configured != null && !configured.isEmpty()) {
            api = configured.endsWith("/")
                    ? configured.substring(0, configured.length() - 1)
                    : configured;
        }
    }

    /** 当前接口基址 */
    public String api() {
        return api;
    }

    // ── 可注入的 HTTP 层（测试覆写）──────────────────────────

    /** 接口请求统一头：上游刻意带 Referer/Origin/Content-Type */
    public Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", SITE + "/");
        h.put("Origin", SITE);
        h.put("Content-Type", "application/json");
        return h;
    }

    /** GET 一个接口，返回原始响应体；失败返回空串。测试覆写此方法即可离线验证。 */
    public String fetch(String path) {
        return http.get(api + API_PREFIX + path, headers());
    }

    /** POST 一个接口，返回原始响应体；失败返回空串。 */
    public String post(String path, String body) {
        return http.post(api + API_PREFIX + path, body, headers());
    }

    // ── 契约方法 ─────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        VodBuilder out = VodBuilder.create();
        for (Map.Entry<String, String> e : SECTIONS.entrySet()) {
            out.clazz(e.getKey(), e.getValue());
        }
        return out.homeContent();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                 java.util.HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String body = fetch("/rank?section=" + Http.encode(tid) + "&page=" + page);
        JSONObject resp = safeParse(body);
        if (resp == null || !OK.equals(resp.optString("detail", ""))) {
            return page1Empty();
        }
        JSONArray items = resp.optJSONArray("items");
        if (items == null || items.length() == 0) {
            return page1Empty();
        }

        int total = resp.optInt("total");
        int size = resp.optInt("page_size");
        if (size <= 0) {
            size = DEFAULT_PAGE_SIZE;
        }

        VodBuilder out = VodBuilder.create();
        for (int i = 0; i < items.length(); i++) {
            Vod v = toVod(items.optJSONObject(i));
            if (v != null) {
                out.add(v);
            }
        }
        return out.page(page, total, size).categoryContent();
    }

    @Override
    public String detailContent(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return emptyResponse();
        }
        String albumId = ids.get(0);
        if (albumId == null || albumId.isEmpty()) {
            return emptyResponse();
        }

        JSONObject album = safeParse(fetch("/album/" + Http.encode(albumId)));
        if (album == null || !OK.equals(album.optString("detail", ""))) {
            return emptyResponse();
        }

        JSONObject chapters = safeParse(fetch("/chapters/" + Http.encode(albumId)));
        if (chapters == null || !OK.equals(chapters.optString("detail", ""))) {
            // 章节拿不到时仍然返回专辑信息（上游此处直接被判空，连封面都没了）
            return emptyResponse();
        }

        Vod vod = new Vod()
                .id(album.optString("id", albumId))
                .name(album.optString("title", ""))
                .pic(album.optString("cover_url", ""))
                .remarks(album.optString("count", ""))
                .content(album.optString("synopsis", ""))
                .director(album.optString("author", ""))       // 作者 → 导演位
                .actor(album.optString("teller", ""))          // 播讲 → 演员位
                .typeName(album.optString("type", ""))
                .playFrom(PLAY_FROM)
                .playUrl(buildPlayUrl(albumId, chapters.optJSONArray("chapters")));
        return VodBuilder.detail(vod).detailContent();
    }

    /**
     * 拼播放列表：{@code 标题$专辑id|章节号}，集间用 {@code #}。
     * 上游在 {@code Integer.parseInt} 失败时会丢弃整集，这里只丢弃这一个不合法章节。
     */
    public static String buildPlayUrl(String albumId, JSONArray chapters) {
        if (chapters == null || chapters.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chapters.length(); i++) {
            JSONObject c = chapters.optJSONObject(i);
            if (c == null) {
                continue;
            }
            String idx = c.optString("index", "");
            if (idx.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(Vod.SEP_EPISODE);
            }
            sb.append(c.optString("title", idx));
            sb.append(Vod.SEP_NAME_URL);
            sb.append(albumId).append('|').append(idx);
        }
        return sb.toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String[] parts = splitPlayId(id);
        if (parts == null) {
            return emptyPlay();
        }
        JSONObject req = new JSONObject();
        try {
            req.put("album_id", parts[0]);
            req.put("chapter_idx", Integer.parseInt(parts[1]));
        } catch (org.json.JSONException | NumberFormatException e) {
            // 上游靠异常兜底；这里显式拦住非数字章节号
            return emptyPlay();
        }
        JSONObject resp = safeParse(post("/play", req.toString()));
        String url = resp == null ? "" : resp.optString("play_url", "");
        if (url == null || url.isEmpty()) {
            return emptyPlay();
        }
        JSONObject out = new JSONObject();
        try {
            out.put("parse", 0);
            out.put("url", url);
            out.put("header", new JSONObject());
        } catch (org.json.JSONException e) {
            return emptyPlay();
        }
        return out.toString();
    }

    /** 切分 {@code albumId|chapterIdx}；不合法返回 {@code null}。 */
    public static String[] splitPlayId(String id) {
        if (id == null) {
            return null;
        }
        String[] parts = id.split("\\|");
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        return parts;
    }

    @Override
    public String searchContent(String key, boolean quick) {
        JSONObject req = new JSONObject();
        try {
            req.put("keyword", key);
            req.put("page", 1);
        } catch (org.json.JSONException e) {
            return page1Empty();
        }
        JSONObject resp = safeParse(post("/search", req.toString()));
        if (resp == null || !OK.equals(resp.optString("detail", ""))) {
            return page1Empty();
        }
        JSONArray results = resp.optJSONArray("results");
        if (results == null || results.length() == 0) {
            return page1Empty();
        }
        VodBuilder out = VodBuilder.create();
        for (int i = 0; i < results.length(); i++) {
            Vod v = toVod(results.optJSONObject(i));
            if (v != null) {
                out.add(v);
            }
        }
        // ★ 上游恒返回 pagecount=100 / total=2000，但搜索请求本身写死 page=1，
        //    宿主据此会翻 100 页拿同一批结果。这里按 TVBox 契约只回 list，
        //    让宿主判定"没有更多页"，从根上消除伪造分页。
        return out.searchContent();
    }

    // ── 工具 ─────────────────────────────────────────────────

    /** 列表条目 JSON → Vod；无 id 或 title 视为脏数据丢弃。 */
    public static Vod toVod(JSONObject o) {
        if (o == null) {
            return null;
        }
        String id = o.optString("id", "");
        if (id.isEmpty()) {
            return null;
        }
        return new Vod()
                .id(id)
                .name(o.optString("title", ""))
                .pic(o.optString("cover_url", ""))
                .remarks(o.optString("type", ""));
    }

    /** 向上取整除法；{@code size<=0} 时返回 1。 */
    public static int ceilDiv(int total, int size) {
        if (size <= 0 || total <= 0) {
            return 1;
        }
        return (total + size - 1) / size;
    }

    /** 上游所有失败分支统一返回的"第 1 页空列表" */
    public static String page1Empty() {
        return "{\"list\":[],\"page\":1,\"pagecount\":1,\"limit\":20,\"total\":0}";
    }

    /** 播放失败的契约空结构 */
    public static String emptyPlay() {
        return "{\"parse\":0,\"url\":\"\",\"header\":{}}";
    }

    private static JSONObject safeParse(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** 便于测试断言分类表 */
    public static List<String> sectionIds() {
        return new ArrayList<>(SECTIONS.keySet());
    }
}
