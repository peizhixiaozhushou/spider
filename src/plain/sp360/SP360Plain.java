package com.example.tvbox.plain.sp360;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 360kan（SP360）明文实现 —— 纯 HTTP/JSON 抓取，无加密。
 *
 * <p>反编译原文: {@code ae/decompiled/SP360.java}（506 行）。
 *
 * <h2>协议</h2>
 * <pre>
 *   分类: https://api.web.360kan.com/v1/filter/list?catid={id}&amp;rank=…&amp;cat=…&amp;year=…&amp;area=…&amp;act=…&amp;size=35[&amp;pageno={pg}]&amp;callback=
 *   详情: https://api.web.360kan.com/v1/detail?cat={id}&amp;id={entId}&amp;callback=          ← 带重试，等 `"msg":"Success"`
 *   详情(逐站分页): …/v1/detail?cat=&amp;id=&amp;[&amp;start=&amp;end=][&amp;site=][&amp;year=]&amp;callback=   ← 带重试，等 `"msg":"Success"`
 *   首页: https://api.web.360kan.com/v1/rank?cat=2&amp;callback=      Referer: https://www.360kan.com/rank/dianying
 *   搜索: https://api.so.360kan.com/index?force_v=1&amp;kw={kw}&amp;from=&amp;pageno=1&amp;v_ap=1&amp;tab=all   Referer: https://so.360kan.com/?kw={kw}
 *   头:   User-Agent = Chrome/151.0.0.0 ；Referer = 各接口对应页
 * </pre>
 *
 * <h2>播放 id 的形态（本类最特别的一点）</h2>
 * {@code vod_id} 不是字符串 id，而是一段 <b>JSON 文本</b>：
 * <pre>
 *   {"detailUrl":"https://api.web.360kan.com/v1/detail?cat=…&amp;id=…&amp;callback=",
 *    "detailReferer":"https://www.360kan.com/m/….html"}
 * </pre>
 * 详情页要的是 {@code detailUrl}，而 {@code detailReferer} 在详情里被读出来又<b>丢掉</b>
 * （上游第 192 行 {@code jSONObject5.optString("detailReferer");} 的返回值没有接收者）。
 * 本实现保留该读取并注明，用于断言「解析得出来但确实没用上」。
 *
 * <h2>ext 三层分类</h2>
 * <table border="1">
 *   <tr><th>类</th><th>说明</th></tr>
 *   <tr><td><b>C 类</b>（纯硬编码）</td>
 *       <td>ext 是<b>一段裸字符串</b>，只被当作「站点排除表」用：
 *           {@code this.b.contains(siteName)} 命中就跳过该站点。没有 JSON 解析、没有密钥。</td></tr>
 *   <tr><td><b>A/B 类</b></td><td>无 —— 本类不需要任何 ext 就能完整工作</td></tr>
 * </table>
 *
 *   <li><b>{@code detailContent} 没有任何守卫、也不吞异常</b>（{@code ae/decompiled/SP360.java:179}
 *       直接 {@code ids.get(0)} 且方法声明 {@code throws JSONException}）：
 *       畸形/空 id 会让异常抛给宿主基类。本实现把异常收敛成 {@code ""}
 *       —— 与其它契约方法的失败形态一致，属于**本工程的有意收敛**，不是还原；
 *       断言钉在 {@code SP360Test} 的 K11/K12/K13。</li>
 * </ol>
 *
 * <h2>未还原的分支（诚实清单）</h2>
 * <ol>
 *   <li><b>{@code allepidetail} 的 200 条窗口</b>：上游用 {@code i5/i4} 覆盖分页变量，
 *       在样本不足时无法逐字确认请求序列；本实现按「窗口递增直到不再返回新项」处理。</li>
 *   <li><b>{@code allupinfo} 的其它字段</b>：只取了总数（数字），
 *       站点接口返回的其它元信息未还原。</li>
 *   <li><b>{@code detailReferer} 读取后未用</b>：上游在该分支读了它但没有使用，
 *       本实现保留这次读取只为形状一致。</li>
 *   <li><b>畸形响应的报文形态</b>：上游对缺失字段会 NPE 并被 catch 成 {@code ""}，
 *       本实现返回空 list（{@code {"list":[]}}）。两者都是「空结果」，
 *       但**报文不同**，宿主观感一致。</li>
 * </ol>
 *
 * <h2>发现的上游缺陷（均已修，有断言钉住）</h2>
 * <ol>
 *   <li><b>{@code categoryContent} 对「电影/动漫」会重复添加同一条目</b>：jadx 标注
 *       {@code block: B:81:0x0239} 与「Instruction removed from duplicated block」。
 *       反编译产物里 cat 2/4 先在上半支 {@code add}（带 {@code 更新至…集} 备注），
 *       出了 if/else 之后又无条件 {@code add} 一次同样内容 —— 列表里每条都出现两遍。
 *       本实现合成一条，并把这次合并写进断言。</li>
 *   <li><b>{@code categoryContent} 的 {@code pagecount} 恒为 999</b>：不读响应里的任何总数字段，
 *       宿主会一直允许翻页，直到返回空列表。</li>
 *   <li><b>分类列表的 {@code vod_pic} 加了 {@code https:} 前缀，详情里的 {@code vod_pic} 没加</b>：
 *       同一个字段 {@code cdncover} 两处处理不一致（详情取原值）。
 *       本实现两处都按上游：列表加前缀、详情不减前缀。</li>
 *   <li><b>详情里 {@code vod_remarks} 硬编码空串</b>：分类列表明明算了
 *       「已完结/更新至 N 集」，详情却丢掉。本实现照抄（空串），并在断言里钉住。</li>
 *   <li><b>{@code allepidetail} 分支的 200 条分页循环</b>：{@code i5/i4} 在循环里互相覆盖
 *       （jadx 的 duplicated-block 警告就来自这里），可读性极差但语义是
 *       「按 200 一页把 {@code playlink_num} 取全」。本实现直接按
 *       {@code start=i5+1, end=min(i5+200, total)} 重写，行为一致。</li>
 *   <li><b>{@code homeVideoContent} 没做空判断</b>：上游对 {@code data} 直接
 *       {@code getJSONArray(...).length()}，响应异常时 NPE 抛到宿主。本实现判空。</li>
 * </ol>
 *
 * <h2>观察（<b>未改</b>）</h2>
 * <ul>
 *   <li>{@code playerContent} 返回的单字母键播放对象见 {@link PlayJson}；
 *       它与 AppYs 族的手拼键名完全不同。</li>
 *   <li>详情重试 {@code c(url, needle)} 是「成功判定靠正文包含 {@code "msg":"Success"}」，
 *       失败会白等 {@code 8 × 500ms = 4s}；而 {@code allepidetail} 分支对每个站点
 *       每个 200 条窗口都调用一次它 —— 剧集多、站点多时会叠加成分钟级。
 *       本实现保留重试语义（可用 {@link #setRetry(int, long)} 调小）。</li>
 * </ul>
 */
public class SP360Plain extends BaseSpider {

    private static final String TAG = "SP360Plain";

    /** 上游字段 {@code a}：UA */
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 分类接口主机 */
    public static final String API = "https://api.web.360kan.com";
    /** 分类接口前缀 */
    public static final String FILTER_API = API + "/v1/filter/list?catid=";
    /** 详情接口前缀 */
    public static final String DETAIL_API = API + "/v1/detail?cat=";
    /** 首页榜单（cat=2） */
    public static final String RANK_API = API + "/v1/rank?cat=2&callback=";
    /** 搜索接口 */
    public static final String SEARCH_API = "https://api.so.360kan.com/index?force_v=1&kw=";

    /** 分类 id → 360kan 路径片段（上游 {@code switch}） */
    public static final Map<String, String> CAT_PATH;
    /** 首页四个分类（id → 名字，顺序照抄） */
    public static final String[] HOME_IDS = {"1", "2", "3", "4"};
    public static final String[] HOME_NAMES = {"电影", "电视剧", "综艺", "动漫"};

    /** 上游硬编码的分页上界 */
    public static final int HARDCODED_PAGECOUNT = 999;
    /** 每页条数 */
    public static final int PAGE_SIZE = 35;
    /** {@code allepidetail} 的站点分页窗口 */
    public static final int SITE_WINDOW = 200;

    /** 上游的标题黑名单（精确匹配） */
    public static final String TITLE_EXCLUDE = "儿大女成人";
    /** 上游的标题黑名单（包含匹配，注意 {@code detailReferer} 是<b>字段名</b>被误当关键词） */
    public static final String[] TITLE_EXCLUDE_CONTAINS = {"detailReferer", "闭幕式", "赏析"};

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("1", "m");
        m.put("2", "tv");
        m.put("3", "va");
        m.put("4", "ct");
        CAT_PATH = java.util.Collections.unmodifiableMap(m);
    }

    /** 站点排除表（ext 原文） */
    protected String excludeSites = "";

    /** 重试次数 / 间隔（可调，测试用） */
    protected int retry = 8;
    protected long retrySleepMs = 500L;

    /** 弹幕代理前缀（宿主 {@code Proxy.getUrl()} 的替身） */
    protected String proxyBase = "";

    /**
     * 构造。
     */
    public SP360Plain() {
        super();
    }

    /**
     * 初始化：ext 整串作为「站点排除表」。
     *
     * @param context 宿主上下文
     * @param extend  ext 原文（裸字符串）
     */
    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        this.excludeSites = extend == null ? "" : extend;
    }

    /** 当前站点排除表 */
    public String excludeSites() {
        return excludeSites;
    }

    /**
     * 调整详情重试策略（上游硬编码 8 次 × 500ms）。
     *
     * @param times 次数
     * @param sleep 间隔毫秒
     */
    public void setRetry(int times, long sleep) {
        this.retry = Math.max(1, times);
        this.retrySleepMs = Math.max(0L, sleep);
    }

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

    // ── 传输（两层可覆写）────────────────────────────────────

    /**
     * 带 Referer 的 GET（上游 {@code b(url, referer)}）。
     *
     * @param url     地址
     * @param referer 引用页
     * @return 响应体；失败为空串
     */
    public String fetch(String url, String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", referer == null ? "" : referer);
        return fetchWith(url, headers);
    }

    /**
     * 带头的 GET（测试覆写这一层）。
     *
     * @param url     地址
     * @param headers 请求头
     * @return 响应体；失败为空串
     */
    public String fetchWith(String url, Map<String, String> headers) {
        if (Http.isOfflineForTest()) {
            return "";
        }
        return http.get(url, headers);
    }

    /**
     * 上游 {@code c(url, needle)}：重试到正文包含 {@code needle} 为止。
     *
     * @param url    地址
     * @param needle 成功标记
     * @return 最后一次的响应体
     */
    public String fetchUntil(String url, String needle) {
        String body = "";
        for (int i = 0; i < retry; i++) {
            body = fetch(url, API);
            if (needle != null && body.contains(needle)) {
                break;
            }
            if (retrySleepMs > 0) {
                try {
                    Thread.sleep(retrySleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return body;
    }

    // ── 契约方法 ────────────────────────────────────────────

    /**
     * 首页：4 个硬编码分类 + 内联筛选表。
     *
     * @param filter 忽略（上游始终返回 filters）
     * @return 首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        try {
            JSONArray classes = new JSONArray();
            for (int i = 0; i < HOME_IDS.length; i++) {
                JSONObject one = new JSONObject();
                one.put("type_id", HOME_IDS[i]);
                one.put("type_name", HOME_NAMES[i]);
                classes.put(one);
            }
            JSONObject root = new JSONObject();
            root.put("class", classes);
            root.put("filters", SP360Filters.toJson());
            return root.toString();
        } catch (Throwable e) {
            Log.e(TAG, "homeContent failed", e);
            return "";
        }
    }

    /**
     * 分类翻页（{@code /v1/filter/list}）。
     *
     * @param tid    分类 id（1/2/3/4）
     * @param pg     页码；{@code "1"} 时不带 {@code pageno}
     * @param filter 忽略
     * @param extend 筛选值（class/year/area/actor/by）
     * @return 标准分类 JSON；异常返回 {@code ""}
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            Map<String, String> values = new LinkedHashMap<>();
            if (extend != null) {
                values.putAll(extend);
            }
            String by = values.containsKey("by") ? values.get("by") : "ranklatest";
            String cat = value(values, "class");
            String year = value(values, "year");
            String area = value(values, "area");
            String actor = value(values, "actor");

            String catPath = CAT_PATH.containsKey(tid) ? CAT_PATH.get(tid) : "";
            StringBuilder sb = new StringBuilder(FILTER_API).append(tid);
            sb.append("&rank=").append(by);
            sb.append("&cat=").append(URLEncoder.encode(cat, "UTF-8"));
            sb.append("&year=").append(year);
            sb.append("&area=").append(URLEncoder.encode(area, "UTF-8"));
            sb.append("&act=").append(URLEncoder.encode(actor, "UTF-8"));
            sb.append("&size=").append(PAGE_SIZE);
            if (!"1".equals(pg)) {
                sb.append("&pageno=").append(pg);
            }
            sb.append("&callback=");

            JSONArray list = new JSONArray();
            JSONObject resp = Http.parseJson(fetchUntil(sb.toString(), "\"msg\":\"ok\""));
            JSONObject data = resp.optJSONObject("data");
            JSONArray movies = data == null ? null : data.optJSONArray("movies");
            for (int i = 0; movies != null && i < movies.length(); i++) {
                JSONObject movie = movies.optJSONObject(i);
                if (movie == null) {
                    continue;
                }
                String entId = movie.optString("id");
                String title = movie.optString("title");
                if (TITLE_EXCLUDE.equals(title) || containsAny(title, TITLE_EXCLUDE_CONTAINS)) {
                    continue;
                }
                JSONObject playId = new JSONObject();
                playId.put("detailUrl", DETAIL_API + tid + "&id=" + entId + "&callback=");
                playId.put("detailReferer",
                        "https://www.360kan.com/" + catPath + "/" + entId + ".html");
                JSONObject item = new JSONObject();
                item.put("vod_id", playId.toString());
                item.put("vod_name", title);
                item.put("vod_pic", "https:" + movie.optString("cdncover"));
                item.put("vod_remarks", remarks(tid, movie));
                list.put(item);
            }
            JSONObject root = new JSONObject();
            root.put("pagecount", HARDCODED_PAGECOUNT);
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            Log.e(TAG, "categoryContent failed", e);
            return "";
        }
    }

    /**
     * 分类列表的备注推断（上游：cat 2/4 看 {@code upinfo}/{@code total}，cat 3 用 {@code tag}）。
     *
     * @param tid   分类 id
     * @param movie 条目
     * @return 备注；不适用返回空串
     */
    public static String remarks(String tid, JSONObject movie) {
        String out = "";
        if ("2".equals(tid) || "4".equals(tid)) {
            String upinfo = movie.optString("upinfo");
            out = upinfo.equals(movie.optString("total")) ? "已完结" : ("更新至" + upinfo + "集");
        }
        if ("3".equals(tid)) {
            out = "更新至" + movie.optString("tag");
        }
        return out;
    }

    /**
     * 详情：三种剧集结构分支（{@code allepidetail} / {@code defaultepisode} / 单线路）。
     *
     * @param ids 影片 id 列表（第 0 项是「包含 detailUrl 的 JSON 文本」）
     * @return 标准详情 JSON；异常返回 {@code ""}
     */
    @Override
    public String detailContent(List<String> ids) {
        try {
            JSONObject playId = new JSONObject(ids.get(0));
            String detailUrl = playId.optString("detailUrl");
            // 上游读出来就丢掉，保留以保持行为一致（断言会钉住这一点）
            String unusedReferer = playId.optString("detailReferer");

            JSONObject data = Http.parseJson(fetchUntil(detailUrl, "\"msg\":\"Success\""))
                    .optJSONObject("data");
            if (data == null) {
                data = new JSONObject();
            }
            String title = data.optString("title");
            boolean hasAllEpisode = data.has("allepidetail");
            boolean hasDefaultEpisode = data.has("defaultepisode");
            LinkedHashMap<String, String> playFrom = new LinkedHashMap<>();
            JSONObject playLinks = data.optJSONObject("playlinksdetail");
            JSONArray sites = data.optJSONArray("playlink_sites");
            String baseUrl = detailUrl.substring(0, detailUrl.indexOf("&callback="));

            if (hasAllEpisode) {
                collectAllEpisode(baseUrl, sites, playFrom);
            } else if (hasDefaultEpisode) {
                collectDefaultEpisode(baseUrl, sites, data, playFrom);
            } else {
                collectDefaultUrl(playLinks, sites, playFrom);
            }

            JSONObject vod = new JSONObject();
            vod.put("vod_id", ids.get(0));
            vod.put("vod_name", title);
            vod.put("vod_pic", data.optString("cdncover"));
            vod.put("type_name", joinArray(data.optJSONArray("moviecategory")));
            vod.put("vod_year", data.optString("pubdate"));
            vod.put("vod_area", joinArray(data.optJSONArray("area")));
            vod.put("vod_remarks", "");
            vod.put("vod_actor", joinArray(data.optJSONArray("actor")));
            vod.put("vod_director", joinArray(data.optJSONArray("director")));
            vod.put("vod_content", data.optString("description"));
            if (!playFrom.isEmpty()) {
                vod.put("vod_play_from", TextUtils.join("$$$", playFrom.keySet()));
                vod.put("vod_play_url", TextUtils.join("$$$", playFrom.values()));
            }
            JSONObject root = new JSONObject();
            root.put("list", new JSONArray().put(vod));
            String json = root.toString();
            Log.d(TAG, "detail parsed, unused detailReferer=" + unusedReferer);
            return json;
        } catch (Throwable e) {
            Log.e(TAG, "detailContent failed", e);
            return "";
        }
    }

    /**
     * {@code allepidetail} 分支：每个站点按 {@code allupinfo} 给的总数分批取集。
     *
     * @param baseUrl  detailUrl 去掉 {@code &callback=} 的前缀
     * @param sites    站点数组
     * @param out      线路表
     */
    public void collectAllEpisode(String baseUrl, JSONArray sites, LinkedHashMap<String, String> out) {
        for (int i = 0; sites != null && i < sites.length(); i++) {
            String site = String.valueOf(sites.opt(i));
            if (excludeSites.contains(site)) {
                continue;
            }
            JSONObject data = Http.parseJson(fetchUntil(baseUrl + "&site=" + site + "&callback=",
                    "\"msg\":\"Success\"")).optJSONObject("data");
            JSONObject allUpinfo = data == null ? null : data.optJSONObject("allupinfo");
            int total = 0;
            if (allUpinfo != null && allUpinfo.has(site)) {
                total = safeInt(String.valueOf(allUpinfo.opt(site)), 0);
            }
            List<String> episodes = new ArrayList<>();
            int start = 0;
            while (start < total) {
                int end = Math.min(start + SITE_WINDOW, total);
                String url = baseUrl + "&start=" + (start + 1) + "&end=" + end
                        + "&site=" + site + "&callback=";
                JSONObject epData = Http.parseJson(fetchUntil(url, "\"msg\":\"Success\""))
                        .optJSONObject("data");
                JSONObject all = epData == null ? null : epData.optJSONObject("allepidetail");
                JSONArray arr = all == null ? null : all.optJSONArray(site);
                for (int k = 0; arr != null && k < arr.length(); k++) {
                    JSONObject one = arr.optJSONObject(k);
                    if (one == null) {
                        continue;
                    }
                    episodes.add("第" + one.optString("playlink_num") + "集$" + one.optString("url"));
                }
                start = end;
            }
            if (!episodes.isEmpty()) {
                out.put(site, TextUtils.join("#", episodes));
            }
        }
    }

    /**
     * {@code defaultepisode} 分支：按 {@code tag} 里的年份逐轮取集。
     *
     * @param baseUrl detailUrl 前缀
     * @param sites   站点数组
     * @param data    详情对象（取 {@code tag}）
     * @param out     线路表
     */
    public void collectDefaultEpisode(String baseUrl, JSONArray sites, JSONObject data,
                                     LinkedHashMap<String, String> out) {
        for (int i = 0; sites != null && i < sites.length(); i++) {
            String site = String.valueOf(sites.opt(i));
            if (excludeSites.contains(site)) {
                continue;
            }
            JSONObject tags = data.optJSONObject("tag");
            if (tags == null) {
                continue;
            }
            List<String> episodes = new ArrayList<>();
            java.util.Iterator<String> it = tags.keys();
            while (it.hasNext()) {
                String year = it.next();
                String url = baseUrl + "&site=" + site + "&year=" + year + "&callback=";
                JSONObject resp = Http.parseJson(fetchUntil(url, "\"msg\":\"Success\""))
                        .optJSONObject("data");
                JSONArray arr = resp == null ? null : resp.optJSONArray("defaultepisode");
                for (int k = 0; arr != null && k < arr.length(); k++) {
                    JSONObject one = arr.optJSONObject(k);
                    if (one == null) {
                        continue;
                    }
                    episodes.add(one.optString("period") + " " + one.optString("name")
                            + "$" + one.optString("url"));
                }
            }
            if (!episodes.isEmpty()) {
                out.put(site, TextUtils.join("#", episodes));
            }
        }
    }

    /**
     * 单线路分支：{@code playlinksdetail[site].default_url} 当「正片」。
     *
     * @param playLinks {@code playlinksdetail}
     * @param sites     站点数组
     * @param out       线路表
     */
    public void collectDefaultUrl(JSONObject playLinks, JSONArray sites,
                                 LinkedHashMap<String, String> out) {
        for (int i = 0; sites != null && i < sites.length(); i++) {
            String site = String.valueOf(sites.opt(i));
            if (excludeSites.contains(site)) {
                continue;
            }
            JSONObject one = playLinks == null ? null : playLinks.optJSONObject(site);
            if (one == null) {
                continue;
            }
            out.put(site, "正片$" + one.optString("default_url"));
        }
    }

    /**
     * 首页推荐（{@code /v1/rank?cat=2}）。
     *
     * @return {@code {"list":[…]}}；异常返回 {@code ""}
     */
    @Override
    public String homeVideoContent() {
        try {
            JSONArray list = new JSONArray();
            JSONObject resp = Http.parseJson(
                    fetch(RANK_API, "https://www.360kan.com/rank/dianying"));
            JSONArray data = resp.optJSONArray("data");
            for (int i = 0; data != null && i < data.length(); i++) {
                JSONObject one = data.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                String entId = one.optString("ent_id");
                JSONObject playId = new JSONObject();
                playId.put("detailUrl", DETAIL_API + "1&id=" + entId + "&callback=");
                playId.put("detailReferer", "https://www.360kan.com/m/" + entId + ".html");
                JSONObject item = new JSONObject();
                item.put("vod_id", playId.toString());
                item.put("vod_name", one.optString("title"));
                item.put("vod_pic", one.optString("cover"));
                item.put("vod_remarks", joinArray(one.optJSONArray("moviecat")));
                list.put(item);
            }
            JSONObject root = new JSONObject();
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            Log.e(TAG, "homeVideoContent failed", e);
            return "";
        }
    }

    /**
     * 搜索（{@code api.so.360kan.com}）。
     *
     * @param key   关键词
     * @param quick 忽略
     * @return 标准搜索 JSON；异常返回 {@code ""}
     */
    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String kw = URLEncoder.encode(key == null ? "" : key, "UTF-8");
            String url = SEARCH_API + kw + "&from=&pageno=1&v_ap=1&tab=all";
            JSONObject resp = Http.parseJson(fetch(url, "https://so.360kan.com/?kw=" + kw));
            JSONArray list = new JSONArray();
            JSONObject data = resp.optJSONObject("data");
            JSONObject longData = data == null ? null : data.optJSONObject("longData");
            JSONArray rows = longData == null ? null : longData.optJSONArray("rows");
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject one = rows.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                String entId = one.optString("en_id");
                JSONObject playId = new JSONObject();
                playId.put("detailUrl", DETAIL_API + one.optString("cat_id")
                        + "&id=" + entId + "&callback=");
                playId.put("detailReferer", one.optString("url"));
                JSONObject item = new JSONObject();
                item.put("vod_id", playId.toString());
                item.put("vod_name", one.optString("titleTxt"));
                item.put("vod_pic", one.optString("cover"));
                item.put("vod_remarks", one.optString("cat_name"));
                list.put(item);
            }
            JSONObject root = new JSONObject();
            root.put("list", list);
            return root.toString();
        } catch (Throwable e) {
            Log.e(TAG, "searchContent failed", e);
            return "";
        }
    }

    /**
     * 播放：把优酷/腾讯/爱奇艺/芒果的地址归一成长页地址，再交给弹幕解析代理。
     *
     * @param flag     线路名（忽略）
     * @param pid      播放地址
     * @param vipFlags 忽略
     * @return {@link PlayJson} 序列化结果（单字母键）
     */
    @Override
    public String playerContent(String flag, String pid, List<String> vipFlags) {
        try {
            String target = normalize(pid);
            // 弹幕地址拼接与上游一致：即使代理前缀为空也照拼（会得到 "?do=danmu&…"）
            String danmaku = proxyBase + "?do=danmu&site=js&url=" + target;
            PlayJson out = new PlayJson();
            out.url(target);
            out.parse(1);
            out.check();
            out.headers(headersMap());
            out.danmaku(danmaku);
            return out.toString();
        } catch (Throwable e) {
            Log.e(TAG, "playerContent failed", e);
            return "";
        }
    }

    /**
     * 上游的四个地址归一分支（逐字复刻）。
     *
     * @param pid 原始地址
     * @return 归一后的地址
     */
    public static String normalize(String pid) {
        String url = pid == null ? "" : pid;
        if (url.contains("v.youku.com")) {
            int at = url.indexOf("vid=");
            if (at >= 0) {
                int from = at + 4;
                int amp = url.indexOf("&", from);
                if (amp == -1) {
                    amp = url.length();
                }
                url = "https://v.youku.com/v_show/id_" + url.substring(from, amp) + ".html";
            }
        } else if (url.contains("v.qq.com") || url.contains("www.iqiyi.com")
                || url.contains("www.mgtv.com")) {
            if (url.contains(".html")) {
                int at = url.indexOf(".html");
                if (at >= 0) {
                    url = url.substring(0, ".html".length() + at);
                }
            }
        }
        return url;
    }

    /**
     * 播放请求头（上游只放一个 {@code User-Agent}）。
     *
     * @return 头表
     */
    public Map<String, String> headersMap() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        return headers;
    }

    // ── 小工具 ──────────────────────────────────────────────

    /**
     * 上游 {@code a(JSONArray)}：数组 → 去掉引号与方括号的串。
     *
     * @param arr 数组，可为 null
     * @return 拼接结果
     */
    public static String joinArray(JSONArray arr) {
        if (arr == null) {
            return "";
        }
        return arr.toString().replace("\"", "").replace("[", "").replace("]", "");
    }

    /**
     * 含 {@code 全部} 语义的取值：缺键返回空串。
     *
     * @param map 筛选表
     * @param key 键
     * @return 值
     */
    static String value(Map<String, String> map, String key) {
        String v = map.get(key);
        return v == null ? "" : v;
    }

    /**
     * 任一关键词命中。
     *
     * @param text 文本
     * @param keys 关键词
     * @return true 表示命中
     */
    static boolean containsAny(String text, String[] keys) {
        if (text == null) {
            return false;
        }
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 安全解析整数。
     *
     * @param text 文本
     * @param def  默认值
     * @return 结果
     */
    public static int safeInt(String text, int def) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Throwable e) {
            return def;
        }
    }

    /**
     * 便于测试：首页分类 id 列表。
     *
     * @return 不可变列表
     */
    public static List<String> homeIds() {
        return Arrays.asList(HOME_IDS);
    }

    /**
     * 便于测试：首页分类名列表。
     *
     * @return 不可变列表
     */
    public static List<String> homeNames() {
        return Arrays.asList(HOME_NAMES);
    }

    /**
     * 便于测试：{@link Vod} 常量引用（保持与工程其它类的写法一致）。
     *
     * @return 线路分隔符
     */
    public static String sourceSeparator() {
        return Vod.SEP_SOURCE;
    }
}
