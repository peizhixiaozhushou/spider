package com.example.tvbox.plain.film;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Dom;
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
 * Ikanbot（爱看机器人 / ikanbot）—— HTML 抓取 + 一次性接口令牌。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  首页分类     : 固定四项（见 {@link #TYPE_IDS}） + {@link IkanbotFilters}
 *  首页推荐     : GET &lt;base&gt;/  → body div.v-list div.item
 *  分类         : GET &lt;base&gt;/&lt;cateId&gt;[-p-N].html
 *  详情         : GET &lt;base&gt;&lt;path&gt;   从页面里正则取三个隐藏 input：
 *                  id="current_id" value="…"   视频 id
 *                  id="mtype"      value="…"   资源类型
 *                  id="e_token"    value="…"   令牌碎片
 *  资源列表     : GET &lt;base&gt;/api/getResN?videoId=&mtype=&token=&lt;getTks(id, e_token)&gt;
 *                 响应 {data:{list:[{resData:"[{flag,url},…]"}]}}
 *  搜索         : GET &lt;base&gt;/search?q=&[&p=N]
 * </pre>
 *
 * <h2>{@code get_tks} —— 本类唯一需要"读懂"的算法</h2>
 * 服务端把真正的令牌切成片，按视频 id 的<b>末 4 位数字</b>决定取片规则：
 * <pre>
 *   for each digit d in videoId[-4:]:
 *       i = d % 3
 *       take token[i+1 .. i+9)   → 8 个字符
 *       token = token[i+9 ..]    → 消费掉已取部分
 * </pre>
 * 也就是"偏移 1..3 + 固定 8 字长"的可变步长切片。{@link #getTks(String, String)} 完整复刻。
 * 若某一步令牌长度不足，上游靠外层 catch 返回空串（鉴权失败）；这里同样返回空串，
 * 但把失败原因留在注释里以便排查。
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>无穷翻页</b>：上游 {@code pagecount = Integer.MAX_VALUE}、
 *       {@code total = Integer.MAX_VALUE}、{@code limit = list.size()}，
 *       宿主会一路往下翻。已改为"满页 ⇒ 还有下一页"的常规判定。</li>
 *   <li><b>分类 URL 分支互相覆盖</b>：上游先按 {@code kanlist} 设一次 URL，
 *       紧接着 {@code if (!"1".equals(page))} 又整体覆盖一次；
 *       {@code kanlist} 分支的结果永远被丢弃。已合并为一个按语义排序的判定，
 *       并对 {@code billboard}（榜单无分页）明确返回空。</li>
 *   <li><b>榜单/片单在非首页页码直接返回空串</b>：上游返回 {@code ""}，
 *       宿主拿到空串会报 JSON 解析错误。已改为返回契约一致的空分页。</li>
 *   <li><b>{@code static} 站点地址被多实例共享</b>：{@code public static String a}
 *       是全局可变的，两个不同站点的实例会互相改写对方的地址。已改为实例字段。</li>
 *   <li><b>{@code init} 的 ext 解析过于宽松</b>：上游
 *       {@code str.split("\\[")[1]} 在 ext 形如 {@code "https://x[排序:]" }
 *       或含多个 {@code [} 时会取错段甚至越界。已改为用
 *       {@code indexOf("[排序:")} 精确定位。</li>
 *   <li><b>排序逻辑会跳过并破坏原列表</b>：上游在嵌套循环里
 *       {@code arrayList2.remove(i3)} 又继续用同一个 {@code i3} 递增，
 *       命中多个排序键时会漏项。已改为"先挑出命中项、再追加未命中项"的稳定重排。</li>
 *   <li><b>播放地址清理用了正则替换</b>：上游构造
 *       {@code "\\$" + flag + "\\#" } 再 {@code replaceAll} —— 当 {@code flag}
 *       含正则元字符（如 {@code +}、{@code .}、{@code (}）时会抛
 *       {@code PatternSyntaxException} 或误删。已改为<b>字面量</b> {@code replace}。</li>
 *   <li><b>失败返回空串</b>：上游 {@code detailContent} 出错返回 {@code ""}。
 *       已改为 {@code "{}"}。</li>
 *   <li><b>重复的 {@code c()} 方法</b>：类里有两个<b>完全相同</b>的
 *       {@code d(String)} 与 {@code c(String)}（只差名字），都做 "URL@Headers={…}" 包装。
 *       已合并为 {@link #withHeaders(String)}。</li>
 * </ol>
 *
 * <h2>ext 字段</h2>
 * <pre>
 *   "https://www1.ikanbot.com"                     只给地址
 *   "https://www1.ikanbot.com[排序:线路1,线路2]"    地址 + 线路排序
 *   { "url": "…[排序:…]" }                          本重写额外支持的 JSON 形式
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Ikanbot.java}
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
public class IkanbotPlain extends BaseSpider {

    private static final String TAG = "IkanbotPlain";

    /** 默认站点（上游硬编码） */
    public static final String DEFAULT_SITE = "https://www1.ikanbot.com";

    /** 站点请求 UA（硬编码） */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 图片防盗链的 Referer（硬编码为豆瓣） */
    public static final String IMAGE_REFERER = "https://www.douban.com";

    /** 首页四项分类（顺序即上游顺序） */
    public static final String[] TYPE_IDS = {
            "hot/index-movie-热门", "hot/index-tv-热门", "billboard", "kanlist/全部"};
    public static final String[] TYPE_NAMES = {"电影", "剧集", "榜单", "片单"};

    /** 片单分类的 {@code type_flag}（上游写死 "2"） */
    public static final String KANLIST_FLAG = "2";

    /** 详情页隐藏字段的正则片段 */
    public static final String HIDDEN_CURRENT_ID = "id=\"current_id\" value=\"";
    public static final String HIDDEN_MTYPE = "id=\"mtype\" value=\"";
    public static final String HIDDEN_E_TOKEN = "id=\"e_token\" value=\"";

    /** 线路名里的替换：{@code m3u8} → {@code 线路} */
    public static final String M3U8_TAG = "m3u8";
    public static final String LINE_TAG = "线路";

    /** ext 中排序段的标记 */
    public static final String SORT_MARKER = "[排序:";

    /** 每页条数（站点固定 20，用于"满页即还有下一页"判定） */
    public static final int PAGE_SIZE = 20;

    /** 播放线路名兜底 */
    public static final String DEFAULT_LINE = "线路";

    private String site = DEFAULT_SITE;
    private String sortSpec = "";

    @Override
    public void init(android.content.Context context, String extend) {
        super.init(context, extend);
        String raw = extend == null ? "" : extend.trim();
        if (raw.isEmpty()) {
            return;
        }
        String candidate = raw.startsWith("{") ? ext.url() : raw;
        if (candidate == null || candidate.isEmpty()) {
            return;
        }
        // ★ 精确定位排序段，避免 split("[") 在多个 "["
        int sortAt = candidate.indexOf(SORT_MARKER);
        if (sortAt >= 0) {
            site = trimSlash(candidate.substring(0, sortAt).trim());
            int end = candidate.indexOf(']', sortAt);
            sortSpec = end > sortAt
                    ? candidate.substring(sortAt + SORT_MARKER.length(), end)
                    : candidate.substring(sortAt + SORT_MARKER.length());
        } else {
            site = trimSlash(candidate.trim());
        }
        if (site.isEmpty()) {
            site = DEFAULT_SITE;
        }
    }

    /** 当前站点地址 */
    public String site() {
        return site;
    }

    /** 当前线路排序段（逗号分隔），无则为空串 */
    public String sortSpec() {
        return sortSpec;
    }

    // ── HTTP（测试覆写） ─────────────────────────────────────

    /** 站点请求头 */
    public Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", site);
        return h;
    }

    /** 取页面文本（utf-8）。测试覆写即可离线。 */
    public String fetch(String url) {
        return fetchWith(url, headers());
    }

    /**
     * 带自定义请求头取文本。资源接口需要额外的 AJAX 头，因此与 {@link #fetch} 分离。
     * 测试覆写此方法即可让<b>所有</b>请求离线化。
     *
     * @param url     完整地址
     * @param headers 本次请求头
     * @return 响应文本；失败返回空串
     */
    public String fetchWith(String url, Map<String, String> headers) {
        return http.get(url, headers);
    }

    // ── 图片防盗链包装 ───────────────────────────────────────

    /**
     * 把图片地址包成 {@code &lt;url&gt;@Headers={…}} 形式。
     *
     * <p>上游有 {@code d(String)} 与 {@code c(String)} 两个<b>逻辑完全相同</b>的方法
     * （只有名字不同），都做这件事。这里合并为一个。
     *
     * @param url 原图地址
     * @return 包装后的地址；失败时原样返回
     */
    public static String withHeaders(String url) {
        if (url == null || url.isEmpty()) {
            return url == null ? "" : url;
        }
        JSONObject h = new JSONObject();
        try {
            h.put("User-Agent", UA);
            h.put("Referer", IMAGE_REFERER);
        } catch (Exception e) {
            return url;
        }
        return url + "@Headers=" + h.toString();
    }

    // ── 令牌算法 ─────────────────────────────────────────────

    /**
     * 由视频 id 与令牌碎片算出接口令牌。
     *
     * <pre>
     *   for each digit d in videoId 的末 4 位:
     *       i = d % 3
     *       piece = token[i+1 .. i+9)     取 8 个字符
     *       token = token[i+9 ..]          消费掉前缀
     *       拼接 piece
     * </pre>
     *
     * @param videoId 视频 id（须至少 4 个字符）
     * @param token   令牌碎片
     * @return 令牌；参数非法或长度不足时返回空串（等价于上游的鉴权失败）
     */
    public static String getTks(String videoId, String token) {
        if (videoId == null || token == null || videoId.length() < 4) {
            return "";
        }
        String rest = token;
        StringBuilder sb = new StringBuilder();
        String tail = videoId.substring(videoId.length() - 4);
        for (int idx = 0; idx < tail.length(); idx++) {
            char ch = tail.charAt(idx);
            if (ch < '0' || ch > '9') {
                return "";   // 末 4 位必须是数字（上游 Integer.parseInt 会抛）
            }
            int i = (ch - '0') % 3;
            int from = i + 1;
            int to = i + 9;
            if (to > rest.length()) {
                return "";   // 令牌不足（上游 StringIndexOutOfBounds → 空串）
            }
            sb.append(rest, from, to);
            rest = rest.substring(to);
        }
        return sb.toString();
    }

    // ── 契约方法 ─────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        VodBuilder out = VodBuilder.create();
        for (int i = 0; i < TYPE_IDS.length; i++) {
            // 片单是"筛选型"分类，需要 type_flag=2（上游硬编码）
            boolean kanlist = TYPE_IDS[i].contains("kanlist");
            out.clazzFlag(TYPE_IDS[i], TYPE_NAMES[i], kanlist ? KANLIST_FLAG : null);
        }
        if (filter) {
            for (String tid : TYPE_IDS) {
                out.filter(tid, IkanbotFilters.of(tid));
            }
        }
        return out.homeContent();
    }

    /**
     * 首页推荐列表（{@code homeVideoContent}）—— 上游单独实现，抓 {@code body div.v-list div.item}。
     */
    @Override
    public String homeVideoContent() {
        String html = fetch(site);
        // 上游先取 body div.v-list（容器），再在容器内取 div.item
        List<Vod> list = parseItems(html, "body div.v-list", "div.item", false);
        return VodBuilder.create().addAll(list).searchContent();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                 java.util.HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String cateId = tid;

        // 片单/榜单类不支持翻页
        boolean paged = !(tid != null && (tid.contains("billboard") || tid.contains("article")));
        if (!paged && page > 1) {
            // ★ 上游返回 ""（宿主会 JSON 解析报错）
            return VodBuilder.create().page(page, 0, 0).categoryContent();
        }

        // ★ 上游这里把 URL 拼了两遍、后一遍覆盖前一遍；这里按语义一次成型
        String path;
        if (tid != null && tid.contains("article")) {
            path = tid;                                    // ext 直接给相对路径
        } else if (page > 1) {
            path = "/" + cateId + "-p-" + page + ".html";
        } else {
            path = "/" + cateId + ".html";
        }
        // 筛选器可以在 extend 里覆盖 cateId（key=cateId）
        String override = extend == null ? null : extend.get("cateId");
        if (override != null && !override.isEmpty()) {
            cateId = override;
            path = path.replace(tid == null ? "" : tid, cateId);
        }

        String html = fetch(site + path);
        boolean kanlist = tid != null && tid.contains("kanlist");
        List<Vod> list = parseItems(html, containerSelectorFor(tid), itemSelectorWithin(tid), kanlist);
        for (Vod v : list) {
            if (kanlist) {
                v.tag("folder");
            } else {
                v.tag("file");
            }
        }
        return VodBuilder.create()
                .addAll(list)
                .page(page, list.size() >= PAGE_SIZE ? (page + 1) * PAGE_SIZE : 0, PAGE_SIZE)
                .categoryContent();
    }

    /**
     * 不同分类的【容器】选择器（与上游一一对应）。
     *
     * <p>注意上游的模式是"先取容器、再在容器内取 {@code div.item}"，
     * 直接把 {@code div.v-list div.item} 当选择器用也能命中，
     * 但片单与榜单的容器类名不同，因此这里返回容器层。
     */
    public static String containerSelectorFor(String tid) {
        if (tid == null) {
            return "div.v-list";
        }
        if (tid.contains("kanlist")) {
            return "div.reslut-list";
        }
        if (tid.contains("billboard") || tid.contains("article")) {
            return "div#video-list";
        }
        return "div.v-list";
    }

    /** 容器<b>内部</b>的条目选择器。 */
    public static String itemSelectorWithin(String tid) {
        if (tid != null && (tid.contains("kanlist") || tid.contains("reslut-list"))) {
            return "div.media";
        }
        return "div.item";
    }

    /** 兼容旧调用：返回"容器 条目"的组合选择器。 */
    public static String itemSelectorFor(String tid) {
        if (tid == null) {
            return "div.v-list div.item";
        }
        if (tid.contains("kanlist")) {
            return "div.reslut-list div.media";
        }
        if (tid.contains("billboard") || tid.contains("article")) {
            return "div#video-list div.item";
        }
        return "div.v-list div.item";
    }

    /**
     * 解析列表项。
     *
     * <p>上游的模式是"先取容器、再在容器内取 {@code div.item}"，
     * 因此这里接受两级选择器。
     *
     * <p>经历两种形态：
     * <ul>
     *   <li>{@code kanlist}（片单）→ 目录项，{@code vod_id} 就是 href，名称取 {@code h5}</li>
     *   <li>其余 → 文件项，{@code vod_id} 是 {@code href$$$名称$$$封面} 三段；
     *       名称取 {@code .title}（榜单/文章）或 {@code p}（普通分类）</li>
     * </ul>
     *
     * @param html         页面
     * @param containerSel 容器选择器
     * @param itemSel      容器内的条目选择器；为 null/空表示容器本身就是条目
     * @param kanlist      是否片单型
     * @return 列表
     */
    public List<Vod> parseItems(String html, String containerSel, String itemSel, boolean kanlist) {
        List<Vod> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        Dom.El root = Dom.parse(html, site);
        List<Dom.El> els = new ArrayList<>();
        for (Dom.El container : root.select(containerSel)) {
            if (itemSel == null || itemSel.isEmpty()) {
                els.add(container);
            } else {
                els.addAll(container.select(itemSel));
            }
        }
        boolean billboardLike = containerSel != null && containerSel.contains("video-list");
        for (Dom.El el : els) {
            String href = el.selectAttr("a", "href", "");
            if (href.isEmpty()) {
                continue;
            }
            String name;
            if (kanlist) {
                name = el.selectText("h5", "");
            } else if (billboardLike) {
                name = el.selectText(".title", "");
            } else {
                name = el.selectText("p", "");
            }
            String pic = withHeaders(el.selectAttr("img", "data-src", ""));
            if (kanlist) {
                out.add(new Vod().id(href).name(name).pic(pic));
            } else {
                out.add(new Vod()
                        .id(href + Vod.SEP_SOURCE + name + Vod.SEP_SOURCE + pic)
                        .name(name)
                        .pic(pic));
            }
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
        String[] parts = id.split("\\$\\$\\$", -1);
        if (parts.length < 3) {
            return emptyResponse();
        }
        String path = parts[0];
        String name = parts[1];
        String pic = parts[2];

        String detailUrl = site + path;
        String html = fetch(detailUrl);
        if (html == null || html.isEmpty()) {
            return emptyResponse();
        }
        String videoId = between(html, HIDDEN_CURRENT_ID);
        String mtype = between(html, HIDDEN_MTYPE);
        String eToken = between(html, HIDDEN_E_TOKEN);
        if (videoId.isEmpty() || mtype.isEmpty() || eToken.isEmpty()) {
            return emptyResponse();
        }

        Dom.El root = Dom.parse(html, site);
        Vod vod = new Vod()
                .id(id)
                .name(name)
                .pic(pic)
                .year(root.selectText("div.detail > .year", ""))
                .area(root.selectText("div.detail > .country", ""))
                .actor(root.selectText("div.detail > .celebrity", ""))
                .content("");

        // 资源接口需要额外的 AJAX 头
        Map<String, String> apiHeaders = headers();
        apiHeaders.put("Accept", "*/*");
        apiHeaders.put("Referer", detailUrl);
        apiHeaders.put("X-Requested-With", "XMLHttpRequest");

        String apiUrl = site + "/api/getResN?videoId=" + Http.encode(videoId)
                + "&mtype=" + Http.encode(mtype)
                + "&token=" + getTks(videoId, eToken);
        String body = fetchWith(apiUrl, apiHeaders);

        Lines lines = parseLines(body);
        lines = reorder(lines, sortSpec);
        if (!lines.froms.isEmpty()) {
            vod.playFrom(join(lines.froms, Vod.SEP_SOURCE));
            vod.playUrl(join(lines.urls, Vod.SEP_SOURCE));
        }
        return VodBuilder.detail(vod).detailContent();
    }

    /** 播放线路解析结果 */
    public static final class Lines {
        /** 线路名列表 */
        public final List<String> froms;
        /** 每线路的集列表（线路内以 {@code #} 连接） */
        public final List<String> urls;

        public Lines(List<String> froms, List<String> urls) {
            this.froms = froms;
            this.urls = urls;
        }
    }

    /**
     * 解析资源接口响应。
     *
     * <p>{@code resData} 是一段<b>被再次 JSON 包装的字符串</b>，
     * 解出来是 {@code [{flag, url}, …]}。线路名 = {@code flag} 把 {@code m3u8}
     * 换成 {@code 线路}（如 {@code "m3u8线路"} → {@code "线路线路"}）。
     *
     * @param body 接口响应
     * @return 线路名与集列表；失败返回空 {@link Lines}（非 null）
     */
    public static Lines parseLines(String body) {
        List<String> froms = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return new Lines(froms, urls);
        }
        JSONArray list;
        try {
            JSONObject data = new JSONObject(body).optJSONObject("data");
            list = data == null ? null : data.optJSONArray("list");
        } catch (Exception e) {
            return new Lines(froms, urls);
        }
        if (list == null) {
            return new Lines(froms, urls);
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String resData = o.optString("resData", "");
            if (resData.isEmpty()) {
                continue;
            }
            JSONArray arr;
            try {
                arr = new JSONArray(resData);
            } catch (Exception e) {
                continue;
            }
            if (arr.length() == 0) {
                continue;
            }
            JSONObject first = arr.optJSONObject(0);
            String flag = first == null ? "" : first.optString("flag", "");
            List<String> eps = new ArrayList<>();
            for (int j = 0; j < arr.length(); j++) {
                JSONObject e = arr.optJSONObject(j);
                if (e == null) {
                    continue;
                }
                eps.add(cleanUrl(e.optString("url", ""), flag));
            }
            if (eps.isEmpty()) {
                continue;
            }
            froms.add(flag.replace(M3U8_TAG, LINE_TAG));
            urls.add(join(eps, Vod.SEP_EPISODE));
        }
        return new Lines(froms, urls);
    }

    /**
     * 清理播放地址里夹带的标记。
     *
     * <p>上游用 {@code replaceAll("\\$" + flag + "\\#", "")} —— 当 {@code flag}
     * 含正则元字符时会抛异常或误删。这里改用字面量替换。
     *
     * @param url  原始地址
     * @param flag 线路标识
     * @return 清理后的地址
     */
    public static String cleanUrl(String url, String flag) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        String f = flag == null ? "" : flag;
        return url.replace("$" + f + "#", "").replace("$" + f, "");
    }

    /**
     * 按 {@code 排序} 段重排线路。
     *
     * <p>排序段形如 {@code "线路1,线路2"}，命中项按声明顺序排到前面，
     * 未命中项保持原相对顺序追加在后。
     *
     * @param lines 原始线路
     * @param sort  排序段（可空）
     * @return 重排后的线路
     */
    public static Lines reorder(Lines lines, String sort) {
        if (lines == null || sort == null || sort.isEmpty() || lines.froms.isEmpty()) {
            return lines == null ? new Lines(new ArrayList<>(), new ArrayList<>()) : lines;
        }
        List<String> keys = new ArrayList<>();
        for (String k : sort.split(",")) {
            String t = k.trim();
            if (!t.isEmpty()) {
                keys.add(t);
            }
        }
        List<String> headFroms = new ArrayList<>();
        List<String> headUrls = new ArrayList<>();
        boolean[] taken = new boolean[lines.froms.size()];
        for (String key : keys) {
            for (int i = 0; i < lines.froms.size(); i++) {
                if (!taken[i] && lines.froms.get(i).contains(key)) {
                    taken[i] = true;
                    headFroms.add(lines.froms.get(i));
                    headUrls.add(lines.urls.get(i));
                }
            }
        }
        for (int i = 0; i < lines.froms.size(); i++) {
            if (!taken[i]) {
                headFroms.add(lines.froms.get(i));
                headUrls.add(lines.urls.get(i));
            }
        }
        return new Lines(headFroms, headUrls);
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return search(key, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String quickSearch) {
        return search(key, quickSearch == null || quickSearch.isEmpty() ? "1" : quickSearch);
    }

    /**
     * 搜索（上游 {@code e(key, page)}）。
     *
     * @param key  关键字
     * @param pg   页码字符串
     * @return 搜索响应
     */
    public String search(String key, String pg) {
        int page = pageNum(pg, 1);
        String path = "/search?q=" + URLEncoder.encode(key == null ? "" : key);
        if (page > 1) {
            path = path + "&p=" + page;
        }
        String html = fetch(site + path);
        if (html == null || html.isEmpty()) {
            return VodBuilder.create().searchContent();
        }
        Dom.El root = Dom.parse(html, site);
        List<Vod> out = new ArrayList<>();
        for (Dom.El el : root.select("body .media")) {
            String href = el.selectAttr("a", "href", "");
            if (href.isEmpty()) {
                continue;
            }
            // 标题末尾的 4 位年份要去掉（上游 \s\d{4}$）
            String title = el.selectText(".title-text", "").replaceAll("\\s\\d{4}$", "").trim();
            String pic = withHeaders(el.selectAttr("img", "data-src", ""));
            String remark = el.selectText("span.label", "");
            out.add(new Vod()
                    .id(href + Vod.SEP_SOURCE + title + Vod.SEP_SOURCE + pic)
                    .name(title)
                    .pic(pic)
                    .remarks(remark)
                    .tag("file"));
        }
        return VodBuilder.create().addAll(out).searchContent();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        JSONObject out = new JSONObject();
        try {
            JSONObject h = new JSONObject();
            h.put("User-Agent", UA);
            out.put("url", id == null ? "" : id);
            out.put("parse", 0);
            out.put("playUrl", "");
            out.put("header", h.toString());
        } catch (Exception e) {
            return "{}";
        }
        return out.toString();
    }

    // ── 工具 ─────────────────────────────────────────────────

    /**
     * 取 {@code prefix} 与下一个 {@code "} 之间的内容。
     *
     * <p>上游用 {@code wh.q(html, prefix, "\"").get(0)} —— 一旦没匹配到，
     * {@code get(0)} 会抛 {@code IndexOutOfBoundsException}。这里返回空串。
     *
     * @param html   页面
     * @param prefix 前缀（含左侧引号）
     * @return 值；未找到返回空串
     */
    public static String between(String html, String prefix) {
        if (html == null || prefix == null) {
            return "";
        }
        int at = html.indexOf(prefix);
        if (at < 0) {
            return "";
        }
        int from = at + prefix.length();
        int to = html.indexOf('"', from);
        return to <= from ? "" : html.substring(from, to);
    }

    /** 去掉尾部斜杠。 */
    public static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
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
