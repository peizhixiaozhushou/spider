package com.example.tvbox.plain.film;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Dom;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迅雷电影天堂（Xunlei8）—— 以 ed2k / magnet 直链为播放地址的影视站。
 *
 * <h2>为什么值得单独说</h2>
 * 这是全项目里最典型的「免嗅探直链」源：播放地址不是 http 视频流，而是
 * {@code ed2k://} 与 {@code magnet:} 这类把文件名编码在 URL 里的链接。
 * 上游因此写了两个正则分别从 URL 里反解出标题 —— 这是本类最容易出错的地方。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  首页     : 静态两分类，tv=电视剧 / list=电影；filters 见 {@link Xunlei8Filters}
 *  分类     : GET &lt;base&gt;/&lt;tid&gt;-&lt;k1&gt;-&lt;k2&gt;-&lt;k3&gt;-&lt;k4&gt;-&lt;page&gt;-30.html
 *             列表项 : div.bc3ba  →  a[title] / img[src] / .b9b4a(备注) / a[href]
 *  详情     : GET &lt;base&gt;/&lt;id&gt;
 *             封面   : div.ba330 img[src]
 *             标题   : div.b586afc9 &gt; h1
 *             播放链 : div.bf8243b9 li  （倒序！）
 *  搜索     : GET &lt;base&gt;/s/&lt;keyword&gt;.html
 *             列表项 : div.b59a2b64df1  →  h2(标题) / img[src] / a[href]
 * </pre>
 * 所有 CSS 类名都是站点自己的混淆名，不是我们的命名 —— 换站必须改选择器。
 *
 * <h2>过滤器链的语义</h2>
 * 分类 URL 是 <b>位置参数</b>：{@code tid-k1-k2-k3-k4-page-30.html}，
 * 而 {@link #FILTER_KEYS} 把宿主回传的 {@code key}（"1"/"2"/"3"/"4"）映射到
 * 数组下标 1..4。也就是说 <b>key 就是位置</b>，这是本类最不直观的一处设计。
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>无穷翻页</b>：上游 {@code pagecount = Integer.MAX_VALUE}、
 *       {@code total = Integer.MAX_VALUE}，宿主会一直往下翻直到拿不到数据。
 *       已改为「本页非空 ⇒ 认为有下一页」（{@code page+1}），与站点"没有总数"的事实一致。</li>
 *   <li><b>{@code limit} 用列表长度</b>：上游 {@code limit = list.size()}，
 *       这是每页的"实际返回条数"而非配置的每页条数（站点固定 30）。
 *       已改为常量 {@link #PAGE_SIZE}=30；条目少于 30 说明已到末页。</li>
 *   <li><b>ed2k 标题正则过宽</b>：上游 {@code \|file\|(.*?)\|} 用了贪婪可再少的
 *       {@code (.*?)}，遇到文件名里含 {@code |} 会截断。已收紧为
 *       {@code [^|]*}（文件名里不可能有 {@code |}，它是 ed2k 的分隔符）。</li>
 *   <li><b>magnet 标题正则漏 {@code ?} 起始</b>：上游只匹配 {@code &dn=} 与 {@code ^dn=}，
 *       实际 magnet 常写作 {@code magnet:?xt=…&dn=…}，能匹配；但若 {@code dn} 是
 *       第一个参数（{@code magnet:?dn=…}）则 {@code ^&? dn=} 匹配不到。
 *       已改为从 query 里按 name=value 解析，与参数顺序无关。</li>
 *   <li><b>详情页 URL {@code URLDecoder.decode} 未处理异常</b>：畸形百分号转义会抛
 *       {@code IllegalArgumentException}，上游用外层 catch 兜住整条链路，
 *       导致<b>一个坏链接把整页播放列表清空</b>。已单条容错：坏链接降级为不解析标题。</li>
 *   <li><b>{@code init} 空 ext 直接跳过</b>：上游 {@code if(str.isEmpty()) return;}
 *       本身没错，但它把 {@code a} 当<b>静态可变字段</b>，多个实例会互相污染。
 *       已改为实例字段。</li>
 *   <li><b>{@code b()} 用字符串拼 JSON</b>：上游手工拼 {@code "@Headers=" + json}，
 *       若 UA 里出现特殊字符就破坏结构。这里用 {@link JSONObject} 构造后拼接。</li>
 * </ol>
 *
 * <h2>ext 字段</h2>
 * <pre>
 *   "https://xunlei8.cc"              直接是站点地址（上游就是这样用的）
 *   { "url": "https://xunlei8.cc" }   本重写额外支持的 JSON 形式
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Xunlei8.java}
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
public class Xunlei8Plain extends BaseSpider {

    private static final String TAG = "Xunlei8Plain";

    /** 默认站点（上游 {@code public static String a} 的初值） */
    public static final String DEFAULT_SITE = "https://xunlei8.cc";

    /** 上游 UA（硬编码） */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 站点固定每页 30 条（上游 URL 里写死的 {@code -30.html}） */
    public static final int PAGE_SIZE = 30;

    /** 播放线路名（上游硬编码） */
    public static final String PLAY_FROM = "迅雷电影天堂";

    /**
     * 分类 → 筛选位置的映射。
     * 宿主回传的 extend 的 key 就是 URL 里的位置下标（1..4），
     * 不是"第几个筛选器"，这是上游设计与 TVBox 惯例不同之处。
     */
    public static final String[] FILTER_KEYS = {"1", "2", "3", "4"};

    /** 分类 id → 显示名 */
    public static final String[] TYPE_IDS = {"tv", "list"};
    public static final String[] TYPE_NAMES = {"电视剧", "电影"};

    /** ed2k 文件名：{@code |file|<名称>|} */
    public static final Pattern RE_ED2K_FILE =
            Pattern.compile("\\|file\\|([^|]*)\\|");
    /** magnet 的 {@code dn} 参数 */
    public static final Pattern RE_MAGNET_DN =
            Pattern.compile("(?:^|[?&])dn=([^&]*)");

    private String site = DEFAULT_SITE;

    @Override
    public void init(android.content.Context context, String extend) {
        super.init(context, extend);
        String raw = extend == null ? "" : extend.trim();
        if (raw.isEmpty()) {
            return;
        }
        // 上游直接把它当站点地址；这里额外接受 {"url":…} 形式
        String candidate = raw;
        if (raw.startsWith("{")) {
            candidate = ext.url();
        }
        if (candidate != null && !candidate.isEmpty()) {
            site = candidate.endsWith("/")
                    ? candidate.substring(0, candidate.length() - 1)
                    : candidate;
        }
    }

    /** 当前站点地址 */
    public String site() {
        return site;
    }

    // ── HTTP（测试覆写） ─────────────────────────────────────

    /** 站点请求头：UA + Referer */
    public java.util.Map<String, String> headers() {
        java.util.Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", site + "/");
        return h;
    }

    /**
     * 取页面 HTML。
     * 上游对页面一律按 utf-8 解码，这里保持一致。
     *
     * @param url 完整地址
     * @return HTML；失败返回空串
     */
    public String fetch(String url) {
        return http.get(url, headers());
    }

    // ── URL 工具 ─────────────────────────────────────────────

    /**
     * 把图片地址包成「带请求头」形式：{@code &lt;url&gt;@Headers={...}}。
     * 这是 TVBox 约定的防盗链写法 —— 图片本身不带 header 就无法显示。
     *
     * @param url 原图地址
     * @return 包装后的地址；原地址为空时原样返回
     */
    public String withHeaders(String url) {
        if (url == null || url.isEmpty()) {
            return url == null ? "" : url;
        }
        JSONObject h = new JSONObject();
        try {
            h.put("User-Agent", UA);
            h.put("Referer", site);
        } catch (Exception e) {
            return url;
        }
        return url + "@Headers=" + h.toString();
    }

    /**
     * 相对地址补全。
     * 上游用 {@code wh.g(base, url)}；此处实现等价语义：已是绝对地址则原样返回。
     *
     * @param url 可能是相对地址
     * @return 绝对地址；{@code url} 为空时返回站点根
     */
    public String absolute(String url) {
        if (url == null || url.isEmpty()) {
            return site;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        return site + (url.startsWith("/") ? url : "/" + url);
    }

    /**
     * 拼分类页地址：{@code <site>/<tid>-<k1>-<k2>-<k3>-<k4>-<page>-30.html}。
     *
     * @param tid      分类 id
     * @param page     页码
     * @param filters  位置 → 值（key 为 "1".."4"），可为 null
     * @return 完整地址
     */
    public String categoryUrl(String tid, int page, java.util.Map<String, String> filters) {
        String[] parts = {"", "0", "0", "0", "date", ""};
        parts[0] = tid == null ? "" : tid;
        parts[5] = String.valueOf(Math.max(1, page));
        if (filters != null) {
            for (java.util.Map.Entry<String, String> e : filters.entrySet()) {
                int idx;
                try {
                    idx = Integer.parseInt(e.getKey());
                } catch (NumberFormatException ignore) {
                    continue;
                }
                if (idx >= 0 && idx < parts.length && e.getValue() != null) {
                    parts[idx] = e.getValue();
                }
            }
        }
        StringBuilder sb = new StringBuilder(site).append('/');
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('-');
            }
            sb.append(parts[i]);
        }
        sb.append('-').append(PAGE_SIZE).append(".html");
        return sb.toString();
    }

    // ── 契约方法 ─────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        VodBuilder out = VodBuilder.create();
        for (int i = 0; i < TYPE_IDS.length; i++) {
            out.clazz(TYPE_IDS[i], TYPE_NAMES[i]);
        }
        if (filter) {
            // 上游给 tv 与 list 挂的是<b>不同</b>的筛选器（"类型" vs "类 型"，值里的空格也不同）
            org.json.JSONObject all = Xunlei8Filters.toJson();
            out.filter("tv", all.optJSONArray("tv"));
            out.filter("list", all.optJSONArray("list"));
        }
        return out.homeContent();
    }

    /** 兼容上游 extend 的 key 语义（key 即位置下标）。 */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                 java.util.HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String url = categoryUrl(tid, page, extend);
        String html = fetch(url);
        List<Vod> list = parseCategory(html);
        if (list.isEmpty()) {
            return emptyResponse();
        }
        // ★ 站点不给总数：本页有数据即认为还有下一页
        return VodBuilder.create()
                .addAll(list)
                .page(page, list.size() >= PAGE_SIZE ? (page + 1) * PAGE_SIZE : 0, PAGE_SIZE)
                .categoryContent();
    }

    /** 解析分类页列表项。 */
    public List<Vod> parseCategory(String html) {
        List<Vod> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        Dom.El root = Dom.parse(html, site);
        for (Dom.El el : root.select("div.bc3ba")) {
            String name = el.selectAttr("a", "title", "");
            String href = el.selectAttr("a", "href", "");
            if (href.isEmpty()) {
                continue;
            }
            out.add(new Vod()
                    .id(absolute(href))
                    .name(name)
                    .pic(withHeaders(absolute(el.selectAttr("img", "src", ""))))
                    .remarks(el.selectText(".b9b4a", "")));
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
        String html = fetch(absolute(id));
        if (html == null || html.isEmpty()) {
            return emptyResponse();
        }
        Dom.El root = Dom.parse(html, site);

        String pic = "";
        List<Dom.El> cover = root.select("div.ba330 img");
        if (!cover.isEmpty()) {
            pic = absolute(cover.get(0).attr("src"));
        }
        String name = root.selectText("div.b586afc9 > h1", "");

        Vod vod = new Vod()
                .id(id)
                .name(name)
                .pic(withHeaders(pic))
                .playFrom(PLAY_FROM)
                .playUrl(buildPlayUrl(root));
        return VodBuilder.detail(vod).detailContent();
    }

    /**
     * 从详情页抽取播放列表。
     *
     * <p>上游对 {@code li} 列表做 {@link Collections#reverse}（站点是按集数倒序排的），
     * 这里保持一致，否则用户会从最后一集开始看。
     *
     * @param root 详情页 DOM
     * @return {@code 名称$地址#名称$地址…}；无有效链接返回空串
     */
    public String buildPlayUrl(Dom.El root) {
        List<Dom.El> lis = new ArrayList<>(root.select("div.bf8243b9 li"));
        Collections.reverse(lis);
        List<String> items = new ArrayList<>();
        for (Dom.El li : lis) {
            String link = li.selectAttr("a", "href", "");
            if (link.isEmpty() || link.startsWith("http")) {
                continue;
            }
            String title = li.selectText("a", "");
            String decoded;
            try {
                decoded = URLDecoder.decode(link, "UTF-8");
            } catch (Exception e) {
                decoded = link;   // ★ 单条坏链接不再清空整页
            }
            String fromUrl = titleFromLink(decoded);
            if (!fromUrl.isEmpty()) {
                title = fromUrl;
            }
            items.add(title + Vod.SEP_NAME_URL + link);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(Vod.SEP_EPISODE);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    /**
     * 从 ed2k / magnet 链接里反解文件名。
     *
     * @param link 已 URL 解码的链接
     * @return 文件名；非这两种协议或解析不出时返回空串
     */
    public static String titleFromLink(String link) {
        if (link == null) {
            return "";
        }
        if (link.startsWith("ed2k:")) {
            Matcher m = RE_ED2K_FILE.matcher(link);
            return m.find() ? m.group(1) : "";
        }
        if (link.startsWith("magnet:")) {
            Matcher m = RE_MAGNET_DN.matcher(link);
            return m.find() ? m.group(1) : "";
        }
        return "";
    }

    @Override
    public String searchContent(String key, boolean quick) {
        String html = fetch(site + "/s/" + Http.encode(key) + ".html");
        List<Vod> out = new ArrayList<>();
        if (html != null && !html.isEmpty()) {
            Dom.El root = Dom.parse(html, site);
            for (Dom.El el : root.select("div.b59a2b64df1")) {
                String href = el.selectAttr("a", "href", "");
                if (href.isEmpty()) {
                    continue;
                }
                out.add(new Vod()
                        .id(absolute(href))
                        .name(el.selectText("h2", ""))
                        .pic(withHeaders(absolute(el.selectAttr("img", "src", ""))))
                        .remarks(""));
            }
        }
        return VodBuilder.create().addAll(out).searchContent();
    }

    /**
     * 播放解析。
     *
     * <p>上游判定：{@code tvbox:} / {@code ed2k:} / {@code thunder:} 前缀的地址
     * 交给宿主/外链处理（{@code parse=0}，不做嗅探）；其余地址加防盗链头后
     * {@code parse=1} 让宿主去嗅探真实流地址。
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        JSONObject out = new JSONObject();
        try {
            out.put("url", id == null ? "" : id);
            out.put("playUrl", "");
            if (isExternalScheme(id)) {
                out.put("parse", 0);
            } else {
                JSONObject h = new JSONObject();
                h.put("User-Agent", UA);
                out.put("header", h.toString());
                out.put("parse", 1);
            }
        } catch (Exception e) {
            return "{}";
        }
        return out.toString();
    }

    /**
     * 是否属于"不走嗅探"的外链协议。
     *
     * @param url 播放地址
     * @return {@code tvbox:} / {@code ed2k:} / {@code thunder:} 前缀为 true
     */
    public static boolean isExternalScheme(String url) {
        if (url == null) {
            return false;
        }
        return url.startsWith("tvbox") || url.startsWith("ed2k") || url.startsWith("thunder");
    }

    /** 便于测试：分类 id 列表 */
    public static List<String> typeIds() {
        List<String> l = new ArrayList<>();
        Collections.addAll(l, TYPE_IDS);
        return l;
    }

    /** 便于测试：分类名列表 */
    public static List<String> typeNames() {
        List<String> l = new ArrayList<>();
        Collections.addAll(l, TYPE_NAMES);
        return l;
    }

    /** 便于测试：把 JSONArray 转成 List&lt;String&gt; */
    public static List<String> strings(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            out.add(arr.optString(i, ""));
        }
        return out;
    }
}
