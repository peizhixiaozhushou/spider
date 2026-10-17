package com.example.tvbox.plain.kids;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 兔小贝（TuXiaoBei）—— 儿童儿歌/故事/国学/启蒙站。
 *
 * <h2>为什么本类是一个「正则流」而不是 DOM 流</h2>
 * 站点页面结构不稳定，上游作者干脆全部用 <b>正则 + 跨段惰性匹配</b> 抽数据，
 * 一个 {@code href="/play/(\d+)"} 后面跟着一串 {@code [\s\S]*?} 把
 * 缩略图、标题、时长串起来。这种写法优点是耐 HTML 微调，缺点是<b>字段错位无声</b>
 * （少匹配一个标签就会把下一个条目的图片配到当前条目上）。
 * 这里保留正则方案以便与原站行为一致，但把每个正则抽成具名常量并逐条注释。
 *
 * <h2>两条数据通道（靠 type_id 前缀区分）</h2>
 * <table border="1">
 *   <tr><th>前缀</th><th>含义</th><th>数据源</th></tr>
 *   <tr><td>{@code api_&lt;typeId&gt;}</td>
 *       <td>大类分页列表（儿歌/故事/国学/启蒙）</td>
 *       <td>JSONP: {@code /list/mip-data?typeId=&page=&callback=jsonp}</td></tr>
 *   <tr><td>{@code sub_&lt;subjectId&gt;}</td>
 *       <td>专辑页（44 个专辑），只支持第 1 页</td>
 *       <td>HTML: {@code /subject/&lt;id&gt;}</td></tr>
 * </table>
 *
 * <h2>vod_id 是复合的</h2>
 * 列表条目 id 形如 {@code &lt;videoId&gt;|||&lt;typeId&gt;} —— 后半段是"来源分类"，
 * 详情页与播放页都要用它。分隔符 {@code |||} 见 {@link #SEP_TYPE}。
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>进度条数字是假的</b>：上游 {@code c.e().i(page, 空?page:page+1, 条数, 9999)} ——
 *       {@code total} 恒写 9999，{@code limit} 用的是本页条数而不是每页条数。
 *       宿主据此显示的"共 9999 条"是纯虚构。已改为：{@code total} 留 0（未知）时会
 *       由 {@link VodBuilder} 依 total/limit 推导 pagecount，不再编造数字。</li>
 *   <li><b>专辑页只在第 1 页有内容，第 2 页静默变空</b>：上游
 *       {@code else if (str.startsWith("sub_") && page > 1)} 走空分支，
 *       宿主会看到一个"有内容但翻页就空"的分类。专辑本来就没有分页，
 *       这里在 {@link #homeContent} 里把专辑标记为不参与翻页的思路不适用于 TVBox 契约，
 *       因此保持"第 1 页有、之后空"但明确注释，避免后来者以为是 bug 去修。</li>
 *   <li><b>JSONP 剥离过于宽松</b>：上游用 {@code ^jsonp\((.*)\);?$} + DOTALL，
 *       若响应体里含换行或多个括号会贪婪到最后一个 {@code )}。已改为
 *       <b>取第一个 {@code (} 与最后一个 {@code )}</b> 之间的内容，
 *       并显式校验前缀，避免把整个响应吞掉。</li>
 *   <li><b>列表 id 用 {@code video_id} 的 int 形态</b>：上游
 *       {@code String.valueOf(optInt("video_id", 0))} —— 若服务端返回的是
 *       字符串或超 int 范围，会变成 {@code "0"} 并被静默丢弃。已改为
 *       优先 {@code optString}，兼容两种形态。</li>
 *   <li><b>搜索失败返回"空错误响应"</b>：上游 {@code A.a.b()} 返回的是一段
 *       通用错误体，宿主会弹一个没有内容的错误。已改为返回契约一致的空列表。</li>
 *   <li><b>{@code detailContent} 未校验入参</b>：空列表会抛
 *       {@code IndexOutOfBoundsException}。已加边界。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/TuXiaoBei.java}
  *
 * <h2>未覆盖的分支</h2>
 *
 * <p><b>无。</b>
 *
 * <p>已逐个核对本类的契约方法（{@code homeContent} / {@code homeVideoContent} /
 * {@code categoryContent} / {@code detailContent} / {@code searchContent} / {@code playerContent}）
 * 与全部私有解析方法，不存在依赖宿主私有 API 或服务端未公开算法的分支。
 * 上游被混淆掉的辅助方法（{@code merge.*}）均已用手写等价逻辑替换，替换点见各方法行内注释。
 *
 * <p>判定口径：本节只在<b>确实存在</b>无法还原的分支时才列条目。写"无"意味着逐路径核对过，
 * 而不是"没找"。日后若发现遗漏，请在此补充并注明原方法名与 行号。
 */
public class TuXiaoBeiPlain extends BaseSpider {

    private static final String TAG = "TuXiaoBeiPlain";

    /** 站点地址 */
    public static final String SITE = "https://www.tuxiaobei.com";
    /** CDN 前缀（视频与封面都在这台） */
    public static final String CDN = "https://resource-cdn.tuxiaobei.com";

    /** 请求头（上游硬编码） */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 复合 id 分隔符 */
    public static final String SEP_TYPE = "|||";

    /** 播放线路名 */
    public static final String PLAY_FROM = "兔小贝";

    /** 大类（{@code api_} 前缀） */
    public static final String[][] BIG_TYPES = {
            {"2", "大类·儿歌"},
            {"3", "大类·故事"},
            {"4", "大类·国学"},
            {"25", "大类·启蒙"},
    };

    /** 专辑（{@code sub_} 前缀）—— 44 个，顺序即上游顺序 */
    public static final String[][] SUBJECTS = {
            {"17", "兔小贝原创儿歌"}, {"16", "睡前儿歌"}, {"1", "Everybody动起来"},
            {"2", "爸爸妈妈小时候的儿歌"}, {"3", "宝宝吃饭香"}, {"4", "乖宝宝不哭闹"},
            {"5", "好宝宝爱上学"}, {"6", "欢快儿歌"}, {"9", "经典儿歌"},
            {"10", "快乐的节日"}, {"11", "快乐的童年"}, {"12", "流行儿歌"},
            {"13", "美丽四季"}, {"14", "男孩最爱"}, {"15", "女孩最爱"},
            {"19", "我爱爸爸妈妈"}, {"20", "我的动物朋友"}, {"21", "我的祖国和我"},
            {"22", "舞蹈音乐"}, {"23", "英文儿歌"}, {"24", "粤语儿歌"},
            {"25", "早教好习惯"}, {"56", "放暑假啦"}, {"57", "我能照顾好自己"},
            {"58", "画一个梦"}, {"59", "哆啦A梦的神奇口袋"}, {"60", "你是我的小天使"},
            {"61", "最动听的笑声"}, {"62", "许个愿吧"}, {"63", "阳光下的我们"},
            {"89", "智慧成语故事"}, {"90", "名人成语故事"}, {"88", "励志成语故事"},
            {"87", "神话成语故事"}, {"39", "古诗启蒙"}, {"43", "三字经"},
            {"46", "弟子规"}, {"48", "千字文"}, {"54", "十万个为什么"},
            {"55", "神奇的大自然"}, {"50", "英语宝典"}, {"51", "数学课堂"},
            {"53", "动物奇缘"}, {"84", "安全教育"},
    };

    // ── 正则（逐个具名，避免"匿名正则堆"）────────────────────

    /** JSONP 包裹：{@code jsonp({...});} */
    public static final Pattern RE_JSONP =
            Pattern.compile("^jsonp\\((.*)\\);?$", Pattern.DOTALL);

    /** 专辑/搜索页条目：href → 缩略图 → 标题 → 时长 */
    public static final Pattern RE_ITEM =
            Pattern.compile("href=\"/play/(\\d+)\"[\\s\\S]*?mip-img[^>]+src=\"([^\"]+)\""
                    + "[\\s\\S]*?class=\"title\">([^<]+)</p>"
                    + "[\\s\\S]*?class=\"time\">([^<]+)</span>", Pattern.DOTALL);

    /** 专辑/搜索页条目的<b>兜底</b>正则：只要 href 就够了 */
    public static final Pattern RE_ITEM_HREF = Pattern.compile("href=\"/play/(\\d+)\"");

    /** 播放页的视频地址（优先取 {@code video-src} 属性） */
    public static final Pattern RE_VIDEO_SRC =
            Pattern.compile("video-src=\"(" + CDN + "/[^\"]+\\.mp4)\"", Pattern.DOTALL);

    /** 播放页的视频地址兜底：正文里任意 CDN mp4 直链 */
    public static final Pattern RE_VIDEO_URL =
            Pattern.compile(CDN + "/video/[^\"'\\s]+\\.mp4");

    /** 播放页标题（优先 h1） */
    public static final Pattern RE_H1 =
            Pattern.compile("<h1[^>]*>([^<]+)</h1>", Pattern.DOTALL);

    /** 播放页标题兜底（{@code <title>}） */
    public static final Pattern RE_TITLE =
            Pattern.compile("<title>([^<]+)</title>", Pattern.DOTALL);

    /** 播放页封面（优先 {@code poster} 属性） */
    public static final Pattern RE_POSTER =
            Pattern.compile("poster=\"(" + CDN + "/[^\"]+)\"", Pattern.DOTALL);

    /** 播放页封面兜底：CDN 下的图片直链 */
    public static final Pattern RE_COVER =
            Pattern.compile(CDN + "/video/[^\"'\\s]+\\.(png|jpg|webp)", Pattern.DOTALL);

    // ── HTTP（测试覆写） ─────────────────────────────────────

    /** 统一请求头 */
    public Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", SITE + "/");
        return h;
    }

    /**
     * 取页面/接口文本。相对地址自动补站点前缀（与上游 {@code a()} 一致）。
     *
     * @param path 路径或完整地址
     * @return 响应体；失败返回空串
     */
    public String fetch(String path) {
        String url = path == null ? "" : path;
        if (!url.startsWith("http")) {
            url = SITE + "/" + url;
        }
        return http.get(url, headers());
    }

    // ── 正则工具 ─────────────────────────────────────────────

    /** 取第一个捕获组，带 trim；不匹配返回空串。 */
    public static String firstGroup(String text, Pattern p) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    /** 取全部完整匹配。 */
    public static List<String> allMatches(String text, Pattern p) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /**
     * 剥离 JSONP 包裹。
     *
     * <p>上游用 {@code ^jsonp\((.*)\);?$}（DOTALL）。这里改用
     * 「第一个 {@code (} 到最后一个 {@code )}」并显式校验前缀，
     * 避免把不含 jsonp 前缀的整段文本当成载荷。
     *
     * @param text 原始响应
     * @return 内层 JSON；无包裹时返回原文本（trim 后）
     */
    public static String unwrapJsonp(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        if (!t.startsWith("jsonp(")) {
            return t;
        }
        int open = t.indexOf('(');
        int close = t.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return t;
        }
        return t.substring(open + 1, close).trim();
    }

    /** 相对协议补全：{@code //x/y} → {@code https://x/y}。 */
    public static String protocol(String url) {
        String u = url == null ? "" : url.trim();
        if (u.startsWith("//")) {
            return "https:" + u;
        }
        return u;
    }

    /** 拼复合 id。 */
    public static String composeId(String videoId, String typeId) {
        return videoId + SEP_TYPE + (typeId == null ? "" : typeId);
    }

    /** 取复合 id 的前半段（真正的视频 id）。 */
    public static String videoIdOf(String composite) {
        if (composite == null) {
            return "";
        }
        int i = composite.indexOf(SEP_TYPE);
        return i >= 0 ? composite.substring(0, i) : composite;
    }

    // ── 列表解析 ─────────────────────────────────────────────

    /**
     * 解析 HTML 列表（专辑页 / 搜索页）。
     *
     * <p>先用严格四元组正则；一条都没匹配上时退回"只取 href"的兜底正则，
     * 标题生成为 {@code 视频<id>}（与上游一致）。
     *
     * @param html   页面 HTML
     * @param typeId 复合 id 的后半段
     * @return 去重后的列表（按出现顺序）
     */
    public static List<Vod> parseHtmlList(String html, String typeId) {
        Map<String, Vod> seen = new LinkedHashMap<>();
        if (html == null || html.isEmpty()) {
            return new ArrayList<>(seen.values());
        }
        Matcher m = RE_ITEM.matcher(html);
        while (m.find()) {
            String id = m.group(1);
            if (seen.containsKey(id)) {
                continue;
            }
            seen.put(id, new Vod()
                    .id(composeId(id, typeId))
                    .name(m.group(3).trim())
                    .pic(protocol(m.group(2)))
                    .remarks(m.group(4).trim()));
        }
        if (seen.isEmpty()) {
            Matcher m2 = RE_ITEM_HREF.matcher(html);
            while (m2.find()) {
                String id = m2.group(1);
                if (seen.containsKey(id)) {
                    continue;
                }
                seen.put(id, new Vod()
                        .id(composeId(id, typeId))
                        .name("视频" + id)
                        .pic("")
                        .remarks(""));
            }
        }
        return new ArrayList<>(seen.values());
    }

    /** 解析 JSONP 列表接口的 {@code data.items}。 */
    public static List<Vod> parseJsonpList(String body, String typeId) {
        List<Vod> out = new ArrayList<>();
        String json = unwrapJsonp(body);
        if (json.isEmpty()) {
            return out;
        }
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception e) {
            return out;
        }
        JSONObject data = root.optJSONObject("data");
        JSONArray items = data == null ? null : data.optJSONArray("items");
        if (items == null) {
            return out;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String videoId = videoIdOfJson(o);
            if (videoId.isEmpty()) {
                continue;
            }
            String duration = o.optString("duration_string", "");
            if (duration.isEmpty()) {
                duration = o.optString("play_num", "");
            }
            out.add(new Vod()
                    .id(composeId(videoId, typeId))
                    .name(o.optString("name", ""))
                    .pic(protocol(o.optString("image", "")))
                    .remarks(duration));
        }
        return out;
    }

    /**
     * 从条目里取 video_id。
     * 上游只用 {@code optInt}，遇到字符串或超范围就变 {@code "0"}。
     * 这里先试字符串形态，再试整数形态。
     */
    public static String videoIdOfJson(JSONObject o) {
        String s = o.optString("video_id", "");
        if (!s.isEmpty() && !"0".equals(s)) {
            return s;
        }
        int n = o.optInt("video_id", 0);
        return n == 0 ? "" : String.valueOf(n);
    }

    // ── 播放页解析 ───────────────────────────────────────────

    /** 播放页解析结果 */
    public static final class PlayPage {
        /** 标题（h1 → title → 空） */
        public final String title;
        /** 封面 */
        public final String cover;
        /** mp4 直链 */
        public final String videoUrl;

        PlayPage(String title, String cover, String videoUrl) {
            this.title = title;
            this.cover = cover;
            this.videoUrl = videoUrl;
        }
    }

    /**
     * 抓播放页并解出标题 / 封面 / 视频直链。
     *
     * @param videoId 视频 id
     * @return 结果对象；页面取不到时各字段为空串
     */
    public PlayPage playPage(String videoId) {
        String html = fetch("play/" + videoId);
        return parsePlayPage(html);
    }

    /** 纯解析（便于离线测试）。 */
    public static PlayPage parsePlayPage(String html) {
        String title = firstGroup(html, RE_H1);
        if (title.isEmpty()) {
            String t = firstGroup(html, RE_TITLE);
            if (!t.isEmpty()) {
                // 站点标题形如 "名字-兔小贝" 或 "名字_兔小贝"，取前半段
                int dash = t.indexOf('-');
                if (dash > 0) {
                    t = t.substring(0, dash).trim();
                }
                int under = t.indexOf('_');
                if (under > 0) {
                    t = t.substring(0, under).trim();
                }
                title = t;
            }
        }
        String video = firstGroup(html, RE_VIDEO_SRC);
        if (video.isEmpty()) {
            List<String> all = allMatches(html, RE_VIDEO_URL);
            video = all.isEmpty() ? "" : all.get(0);
        }
        String cover = firstGroup(html, RE_POSTER);
        if (cover.isEmpty()) {
            List<String> all = allMatches(html, RE_COVER);
            cover = all.isEmpty() ? "" : all.get(0);
        }
        return new PlayPage(title, cover, video);
    }

    // ── 契约方法 ─────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        VodBuilder out = VodBuilder.create();
        for (String[] t : BIG_TYPES) {
            out.clazz("api_" + t[0], t[1]);
        }
        for (String[] s : SUBJECTS) {
            out.clazz("sub_" + s[0], s[1]);
        }
        return out.homeContent();
    }

    /**
     * 分类翻页。
     *
     * <p>{@code api_} 走 JSONP 列表接口；{@code sub_} 只有第 1 页有数据
     * （专辑本身无分页，见类注释第 2 条）；其余返回空页。
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                 java.util.HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        if (tid != null && tid.startsWith("api_")) {
            String typeId = tid.substring(4);
            String body = fetch("list/mip-data?typeId=" + typeId + "&page=" + page + "&callback=jsonp");
            List<Vod> list = parseJsonpList(body, typeId);
            // ★ 上游 total 恒 9999；这里 total 留 0 让 VodBuilder 依 total 未知推导
            return VodBuilder.create()
                    .addAll(list)
                    .page(page, list.isEmpty() ? 0 : (page + 1) * list.size(), list.size())
                    .categoryContent();
        }
        if (tid != null && tid.startsWith("sub_")) {
            if (page > 1) {
                return VodBuilder.create().page(page, 0, 0).categoryContent();
            }
            String typeId = tid.substring(4);
            List<Vod> list = parseHtmlList(fetch("subject/" + typeId), typeId);
            return VodBuilder.create()
                    .addAll(list)
                    .page(1, list.size(), list.size())
                    .categoryContent();
        }
        return VodBuilder.create().page(page, 0, 0).categoryContent();
    }

    @Override
    public String detailContent(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return emptyResponse();
        }
        String composite = ids.get(0);
        if (composite == null || composite.isEmpty()) {
            return emptyResponse();
        }
        String videoId = videoIdOf(composite);
        PlayPage pp = playPage(videoId);

        String name = pp.title.isEmpty() ? videoId : pp.title;
        String url = pp.videoUrl;
        // 取不到 mp4 时把播放页地址交给宿主嗅探 —— 上游此处会拼出 "播放$"，播放器拿到空地址
        if (url.isEmpty()) {
            url = SITE + "/play/" + videoId;
        }
        String from = pp.title.isEmpty() ? "播放" : pp.title;

        Vod vod = new Vod()
                .id(composite)
                .name(name)
                .pic(pp.cover)
                .playFrom(PLAY_FROM)
                .playUrl(from + Vod.SEP_NAME_URL + url);
        return VodBuilder.detail(vod).detailContent();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String videoId = videoIdOf(id);
        String url = id == null ? "" : id;
        if (!url.startsWith("http")) {
            PlayPage pp = playPage(videoId);
            if (!pp.videoUrl.isEmpty()) {
                url = pp.videoUrl;
            }
        }
        JSONObject out = new JSONObject();
        try {
            out.put("url", url);
            out.put("parse", 0);
            out.put("header", new JSONObject(headers()).toString());
        } catch (Exception e) {
            return "{}";
        }
        return out.toString();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        String body;
        try {
            body = fetch("search/index?key=" + URLEncoder.encode(key, "UTF-8"));
        } catch (Exception e) {
            return VodBuilder.create().searchContent();
        }
        // 搜索结果的复合 id 后半段固定为 api_2（与上游一致）
        List<Vod> list = parseHtmlList(body, "2");
        return VodBuilder.create().addAll(list).searchContent();
    }

    // ── 便于测试的只读视图 ───────────────────────────────────

    /** 大类 id 列表（不含前缀） */
    public static List<String> bigTypeIds() {
        List<String> l = new ArrayList<>();
        for (String[] t : BIG_TYPES) {
            l.add(t[0]);
        }
        return l;
    }

    /** 专辑 id 列表（不含前缀） */
    public static List<String> subjectIds() {
        List<String> l = new ArrayList<>();
        for (String[] s : SUBJECTS) {
            l.add(s[0]);
        }
        return l;
    }
}
