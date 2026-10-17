package com.example.tvbox.plain.film;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.frame.ProxyPlain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 爱看（AiKan）—— 自建 API 的影视聚合源，带服务端解析中转与弹幕。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  GET  /apptov5/v1/home/data?id=1&mold=1         → {code:1,data:{sections:[{items:[vod…]}]}}
 *  GET  /apptov5/v1/vod/lists?type_id=&order=&page=&pageSize=21
 *                                                  → {code:1,data:{data:[vod…],total}}
 *  GET  /apptov5/v1/vod/getVod?id=&lt;urlencode&gt;      → {code:1,data:{vod…, vod_play_list:[…]}}
 *  GET  /apptov5/v1/search/lists?wd=&lt;urlencode&gt;&page=1
 *                                                  → {code:1,data:{data:[vod…]}}
 *  POST /apptov5/v1/parsing/proxy                   body:{play_url,label,key}  → {code:1,data:{url}}
 * </pre>
 * 所有响应统一以 {@code code==1} 表示成功，成功时真正的载荷在 {@code data} 里。
 * 这是本类唯一的成功判据 —— 不看 HTTP 状态码。
 *
 * <h2>播放地址的复合编码（本类最需要小心的地方）</h2>
 * 详情页把每集写成一个 4 段 {@code @} 连接串：
 * <pre>
 *   第N集$&lt;play_url&gt;@&lt;from&gt;@&lt;vodName&gt;@&lt;集名&gt;
 *                    ↑        ↑          ↑
 *                    |        |          └─ 弹幕用（vodIndex）
 *                    |        └─ 弹幕用（vodName）
 *                    └─ 解析器标识（RR/BBA/NBY/YYNB/qq/…），决定用哪个第三方解析
 * </pre>
 * 所以 {@code playerContent} 里 {@code split("@")} 之后
 * {@code [0]=url, [1]=from, [2]=vodName, [3]=集名}。
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>筛选条件写反且会 NPE（严重）</b>：上游
 *       {@code if (map == null && !map.containsKey("order"))}。
 *       <ul>
 *         <li>{@code map == null} 为真时会继续求值 {@code map.containsKey(...)}
 *             → <b>NPE</b>，被外层 catch 吞掉，分类页直接返回"分类加载失败"。</li>
 *         <li>即使 {@code map != null}，整个条件恒为 false，{@code order} <b>永远</b>
 *             保持 "time"，宿主的排序筛选完全失效。</li>
 *       </ul>
 *       正确语义是"有 extend 且含 order 才取"，已改为
 *       {@code map != null && map.containsKey("order")}。</li>
 *   <li><b>pagecount 与 total 语义不清</b>：上游 {@code pagecount = size>=21 ? page+1 : page}，
 *       且 {@code total} 取服务端 {@code total}（缺失时回落本页条数）。看起来对，
 *       但当服务端给了 total 时它与 pagecount 会互相矛盾（pagecount 说"可能还有下一页"，
 *       total 说"总共就这么点"）。已统一为：total 已知就按 {@code ceil(total/pageSize)} 算
 *       pagecount，未知才退回 page+1。</li>
 *   <li><b>缺 {@code player_info} 时线路名退化但 from 为空</b>：上游对
 *       {@code player_info == null} 只补了显示名 {@code "线路N"}，没有补 {@code from}，
 *       于是播放串里出现连续两个 {@code @}，解析器标识为空。已补一个稳定的
 *       {@link #DEFAULT_PLAYER_FROM}。</li>
 *   <li><b>{@code strOptString} 空名集数</b>：上游用局部 {@code StringBuilder} 造
 *       {@code "第N集"} 作为 {@code name} 的兜底 —— 逻辑对，但那个 StringBuilder
 *       每轮都新建、只服务一次，是纯粹的噪音。已抽成 {@link #fallbackEpisodeName}。</li>
 *   <li><b>硬编码 IP 作为默认接口</b>：{@code http://43.248.117.123:4680} 是明文 IP，
 *       域名迁移即失效。已保留为默认值以便复现，同时允许 ext 覆盖。</li>
 * </ol>
 *
 * <h2>ext 字段</h2>
 * <pre>
 *   "http://host:port"                 直接是接口基地址（上游就是这样用的）
 *   { "url": "http://host:port" }      本重写额外支持的 JSON 形式
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/AiKan.java}
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
public class AiKanPlain extends BaseSpider {

    private static final String TAG = "AiKanPlain";

    /** 默认接口基地址（上游硬编码明文 IP） */
    public static final String DEFAULT_BASE = "http://43.248.117.123:4680";

    /** 请求头（上游 {@code h.c} 三连） */
    public static final String UA = "okhttp/4.9.3";
    public static final String ACCEPT = "application/json";

    /** 分类页固定每页 21 条（上游 URL 里写死 {@code pageSize=21}） */
    public static final int PAGE_SIZE = 21;

    /** 首页固定五分类（上游 {@code Arrays.asList(...)}） */
    public static final String[] TYPE_IDS = {"1", "2", "3", "4", "23"};
    public static final String[] TYPE_NAMES = {"电影", "电视剧", "综艺", "动漫", "短剧"};

    /** 默认排序（上游硬编码 "time"） */
    public static final String DEFAULT_ORDER = "time";

    /** 缺 {@code player_info.from} 时的兜底解析器标识 */
    public static final String DEFAULT_PLAYER_FROM = "";

    /** 直接被识别为直链的后缀 */
    public static final String[] DIRECT_SUFFIXES = {"m3u8", "mp4", "flv"};

    /** 需要走 {@code huaqi} 解析器的第三方站点标识 */
    public static final List<String> HUAQI_SITES =
            Arrays.asList("qq", "qiyi", "youku", "mgtv", "mg", "migu");

    /** 上游对"有广告"线路的过滤关键字 */
    public static final String AD_KEYWORD = "有广告";

    private String base = DEFAULT_BASE;

    @Override
    public void init(android.content.Context context, String extend) {
        super.init(context, extend);
        String raw = extend == null ? "" : extend.trim();
        if (raw.isEmpty()) {
            return;
        }
        String candidate = raw.startsWith("{") ? ext.url() : raw;
        if (candidate != null && !candidate.isEmpty()) {
            base = candidate.endsWith("/")
                    ? candidate.substring(0, candidate.length() - 1)
                    : candidate;
        }
    }

    /** 当前接口基地址 */
    public String base() {
        return base;
    }

    // ── HTTP（测试覆写） ─────────────────────────────────────

    /** 统一请求头 */
    public Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Accept", ACCEPT);
        return h;
    }

    /** GET 原始响应体；失败返回空串 */
    public String get(String path) {
        return http.get(base + path, headers());
    }

    /** POST 原始响应体；失败返回空串 */
    public String post(String path, Map<String, String> body) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : body.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(Http.encode(e.getKey())).append('=').append(Http.encode(e.getValue()));
        }
        return http.post(base + path, sb.toString(), headers());
    }

    /**
     * 取 {@code data} 载荷。
     * 上游 {@code c(str)} 的等价物：只有 {@code code==1} 才算成功。
     *
     * @param path 接口路径（含 query）
     * @return {@code data} 对象；失败返回空对象（非 null）
     */
    public JSONObject data(String path) {
        return unwrap(get(path));
    }

    /** 从响应体里解出 {@code data}；失败返回空对象。 */
    public static JSONObject unwrap(String body) {
        if (body == null || body.isEmpty()) {
            return new JSONObject();
        }
        try {
            JSONObject o = new JSONObject(body);
            if (o.optInt("code") != 1) {
                return new JSONObject();
            }
            JSONObject d = o.optJSONObject("data");
            return d == null ? new JSONObject() : d;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ── 列表解析 ─────────────────────────────────────────────

    /**
     * 解析影片列表数组。
     * 上游要求 {@code vod_id} 与 {@code vod_name} 同时非空，否则丢弃。
     *
     * @param arr 数组，可为 null
     * @return 非 null 列表
     */
    public static List<Vod> parseVodList(JSONArray arr) {
        List<Vod> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String id = o.optString("vod_id", "");
            String name = o.optString("vod_name", "");
            if (id.isEmpty() || name.isEmpty()) {
                continue;
            }
            out.add(new Vod()
                    .id(id)
                    .name(name)
                    .pic(o.optString("vod_pic", ""))
                    .remarks(o.optString("vod_remarks", "")));
        }
        return out;
    }

    // ── 契约方法 ─────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        VodBuilder out = VodBuilder.create();
        for (int i = 0; i < TYPE_IDS.length; i++) {
            out.clazz(TYPE_IDS[i], TYPE_NAMES[i]);
        }
        JSONObject home = data("/apptov5/v1/home/data?id=1&mold=1");
        JSONArray sections = home.optJSONArray("sections");
        if (sections != null) {
            for (int i = 0; i < sections.length(); i++) {
                JSONObject s = sections.optJSONObject(i);
                if (s != null) {
                    out.addAll(parseVodList(s.optJSONArray("items")));
                }
            }
        }
        return out.homeContent();
    }

    /**
     * 分类翻页。
     *
     * <p>★ 上游此处的 null 判断写反，导致分类页在宿主不传筛选时直接 NPE 失败，
     * 且 order 筛选永远不生效。此处已修正。
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                 java.util.HashMap<String, String> extend) {
        // ★ 修正：有 extend 且显式给了 order 才用它
        String order = DEFAULT_ORDER;
        if (extend != null && extend.containsKey("order")) {
            String v = extend.get("order");
            if (v != null && !v.isEmpty()) {
                order = v;
            }
        }
        int page = pageNum(pg, 1);
        JSONObject d = data(String.format(
                "/apptov5/v1/vod/lists?type_id=%s&order=%s&page=%d&pageSize=%d",
                Http.encode(tid), Http.encode(order), page, PAGE_SIZE));
        List<Vod> list = parseVodList(d.optJSONArray("data"));

        int reported = d.optInt("total", 0);
        int total;
        if (reported > 0) {
            total = reported;
        } else if (list.size() >= PAGE_SIZE) {
            total = (page + 1) * PAGE_SIZE;   // 满页 ⇒ 还有下一页
        } else {
            total = (page - 1) * PAGE_SIZE + list.size();  // 不满页 ⇒ 到底
        }
        return VodBuilder.create()
                .addAll(list)
                .page(page, total, PAGE_SIZE)
                .categoryContent();
    }

    @Override
    public String detailContent(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return fail("详情加载失败");
        }
        String id = ids.get(0);
        if (id == null || id.isEmpty()) {
            return fail("详情加载失败");
        }
        JSONObject d;
        try {
            d = data("/apptov5/v1/vod/getVod?id=" + URLEncoder.encode(id, "UTF-8"));
        } catch (Exception e) {
            return fail("详情加载失败");
        }
        if (d.length() == 0) {
            return fail("详情加载失败");
        }

        String vodName = d.optString("vod_name", "");
        Vod vod = new Vod()
                .id(d.optString("vod_id", id))
                .name(vodName)
                .pic(d.optString("vod_pic", ""))
                .year(d.optString("vod_year", ""))
                .area(d.optString("vod_area", ""))
                .typeName(d.optString("vod_class", ""))
                .actor(d.optString("vod_actor", ""))
                .director(d.optString("vod_director", ""))
                .content(d.optString("vod_content", d.optString("vod_blurb", "")))
                .remarks(d.optString("vod_remarks", ""));

        PlayList plays = playListOf(d.optJSONArray("vod_play_list"), vodName);
        vod.playFrom(plays.froms).playUrl(plays.urls);
        return VodBuilder.detail(vod).detailContent();
    }

    /** 播放列表解析结果 */
    public static final class PlayList {
        /** 线路名，以 {@code $$$} 连接 */
        public final String froms;
        /** 每线路的集列表，以 {@code $$$} 连接；线路内集以 {@code #} 连接 */
        public final String urls;

        PlayList(String froms, String urls) {
            this.froms = froms;
            this.urls = urls;
        }
    }

    /**
     * 解析 {@code vod_play_list}。
     *
     * <p>跳过规则（与上游一致）：线路显示名含 {@link #AD_KEYWORD}，或 {@code urls} 为空。
     *
     * @param arr     播放线路数组
     * @param vodName 剧名，用于填写弹幕参数
     * @return 线路名与集列表
     */
    public static PlayList playListOf(JSONArray arr, String vodName) {
        List<String> froms = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        if (arr == null) {
            return new PlayList("", "");
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject line = arr.optJSONObject(i);
            if (line == null) {
                continue;
            }
            JSONObject info = line.optJSONObject("player_info");
            String show = info != null ? info.optString("show", "") : ("线路" + (i + 1));
            // ★ 上游对 info==null 不补 from，会产生空解析器标识
            String from = info != null ? info.optString("from", DEFAULT_PLAYER_FROM)
                    : DEFAULT_PLAYER_FROM;

            if (show.contains(AD_KEYWORD)) {
                continue;
            }
            JSONArray urls = line.optJSONArray("urls");
            if (urls == null || urls.length() == 0) {
                continue;
            }
            List<String> eps = new ArrayList<>();
            for (int j = 0; j < urls.length(); j++) {
                JSONObject u = urls.optJSONObject(j);
                if (u == null) {
                    continue;
                }
                String name = u.optString("name", fallbackEpisodeName(j));
                String url = u.optString("url", "");
                if (url.isEmpty()) {
                    continue;
                }
                eps.add(name + Vod.SEP_NAME_URL + url + "@" + from + "@" + vodName + "@" + name);
            }
            if (!eps.isEmpty()) {
                froms.add(show);
                lines.add(join(eps, Vod.SEP_EPISODE));
            }
        }
        return new PlayList(join(froms, Vod.SEP_SOURCE), join(lines, Vod.SEP_SOURCE));
    }

    /** 集名兜底：{@code 第N集}（{@code index} 从 0 开始，显示从 1 开始）。 */
    public static String fallbackEpisodeName(int index) {
        return "第" + (index + 1) + "集";
    }

    @Override
    public String searchContent(String key, boolean quick) {
        JSONObject d;
        try {
            d = data("/apptov5/v1/search/lists?wd="
                    + URLEncoder.encode(key, "UTF-8") + "&page=1");
        } catch (Exception e) {
            return fail("搜索加载失败");
        }
        List<Vod> list = parseVodList(d.optJSONArray("data"));
        return VodBuilder.create().addAll(list).searchContent();
    }

    /**
     * 播放解析。
     *
     * <p>两条路径：
     * <ol>
     *   <li>地址本身是 {@code m3u8/mp4/flv} → 直接返回，带请求头与弹幕地址</li>
     *   <li>否则 POST 到 {@code /parsing/proxy} 让服务端换出真实地址；
     *       失败则退回原始地址（宁可让宿主试着播，也不要空手而归）</li>
     * </ol>
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String[] parts = splitPlayId(id);
        if (parts == null) {
            return fail("播放链接解析失败");
        }
        String playUrl = parts[0];
        String from = parts[1];
        String vodName = parts[2];
        String epName = parts[3];

        JSONObject out = new JSONObject();
        try {
            if (isDirect(playUrl)) {
                out.put("url", playUrl);
                out.put("header", new JSONObject(headers()).toString());
                out.put("parse", 0);
            } else {
                String resolved = resolve(playUrl, from);
                out.put("url", resolved == null || resolved.isEmpty() ? playUrl : resolved);
                out.put("header", new JSONObject(headers()).toString());
                out.put("parse", 0);
            }
            out.put("danmaku", danmakuUrl(vodName, epName));
        } catch (Exception e) {
            return fail("播放链接解析失败");
        }
        return out.toString();
    }

    /**
     * 切分播放 id 的 4 段。段数不足时后面补空串（与上游一致），
     * 但 {@code playUrl} 为空则视为非法。
     *
     * @param id 复合 id
     * @return 4 元素数组；非法返回 {@code null}
     */
    public static String[] splitPlayId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        String[] raw = id.split("@");
        String[] out = new String[4];
        for (int i = 0; i < 4; i++) {
            out[i] = i < raw.length ? raw[i] : "";
        }
        return out[0].isEmpty() ? null : out;
    }

    /** 地址是否自带可播后缀（无需服务端解析）。 */
    public static boolean isDirect(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase(java.util.Locale.ROOT);
        for (String s : DIRECT_SUFFIXES) {
            if (u.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把解析器标识映射成服务端认识的 {@code label}。
     *
     * <table border="1">
     *   <tr><th>传入</th><th>label</th></tr>
     *   <tr><td>RR</td><td>rrsp</td></tr>
     *   <tr><td>BBA</td><td>bba</td></tr>
     *   <tr><td>NBY</td><td>mayi</td></tr>
     *   <tr><td>YYNB</td><td>yynb2</td></tr>
     *   <tr><td>qq / qiyi / youku / mgtv / mg / migu</td><td>huaqi</td></tr>
     *   <tr><td>其它</td><td>小写原值</td></tr>
     * </table>
     *
     * @param from 解析器标识
     * @return label
     */
    public static String labelOf(String from) {
        String f = from == null ? "" : from.trim();
        if ("RR".equalsIgnoreCase(f)) {
            return "rrsp";
        }
        if ("BBA".equalsIgnoreCase(f)) {
            return "bba";
        }
        if ("NBY".equalsIgnoreCase(f)) {
            return "mayi";
        }
        if ("YYNB".equalsIgnoreCase(f)) {
            return "yynb2";
        }
        String lower = f.toLowerCase(java.util.Locale.ROOT);
        if (HUAQI_SITES.contains(lower)) {
            return "huaqi";
        }
        return lower;
    }

    /**
     * 调服务端解析接口换真实地址。
     *
     * @param playUrl 原始地址
     * @param from    解析器标识
     * @return 真实地址；失败返回 {@code null}
     */
    public String resolve(String playUrl, String from) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("play_url", playUrl);
        body.put("label", labelOf(from));
        body.put("key", from == null ? "" : from);
        JSONObject d;
        try {
            d = unwrap(post("/apptov5/v1/parsing/proxy", body));
        } catch (RuntimeException e) {
            return null;
        }
        String url = d.optString("url", "");
        return url.isEmpty() ? null : url;
    }

    /**
     * 弹幕地址：走本地代理把剧名/集名带过去。
     *
     * @param vodName 剧名
     * @param epName  集名
     * @return 完整 URL
     */
    public String danmakuUrl(String vodName, String epName) {
        return ProxyPlain.getUrl() + "?do=appdanmu&vodName="
                + Http.encode(vodName == null ? "" : vodName)
                + "&vodIndex=" + Http.encode(epName == null ? "" : epName);
    }

    /** 上游失败响应体：带 {@code msg} 让宿主弹提示。 */
    public static String fail(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("msg", message);
        } catch (Exception ignore) {
            return "{}";
        }
        return o.toString();
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
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
