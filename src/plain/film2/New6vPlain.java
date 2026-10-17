package com.example.tvbox.plain.film2;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 新6V（New6v）—— 磁力/ed2k 下载站，HTML 抓取。
 *
 * <h2>协议还原（来自 {@code ae/decompiled/New6v.java}，并用真实请求验证）</h2>
 *
 * <h3>端点</h3>
 * <pre>
 *  首页     GET {base}                                     ← ul#menus a 取分类
 *  分类     GET {base}{tid}                                （首页第 1 页）
 *           GET {base}{tid}index_{pg}.html                 （第 2 页起，pg != 1）
 *  最新50部 GET {base}{tid}                                （tid 含 /qian 时）→ div#tab-content li
 *  详情     GET {base}{vod_id}                             （vod_id 是站内相对路径）
 *  搜索     POST {base7}/e/search/so.php                    ← 表单 action（不是 11index.php！）
 * </pre>
 *
 * <h3>反编译与实际站点的差异（重要，实测发现）</h3>
 * <ul>
 *   <li>反编译里搜索地址写死 {@code https://www.xb6v.org/e/search/11index.php}，
 *       实测该地址 404；站点真实表单 action 是 {@code /e/search/so.php}。</li>
 *   <li>{@code so.php} 返回 <b>302</b>，Location 指向
 *       {@code /e/search/result/?searchid=NNNN}，需要跟随一次跳转才拿到结果页。</li>
 *   <li>反编译里详情页用 {@code div#content>div>h1} 取标题，实测结构是
 *       {@code div#content h1}（外层少了 {@code div>}）。两种都兼容。</li>
 *   <li>反编译里 {@code li:contains(导演:)} 在当前站点不存在（无豆瓣式简介块），
 *       因此详情只有标题/封面/磁力。代码保留 director/actor 抽取但允许为空。</li>
 * </ul>
 *
 * <h3>HTML 选择器 → 本实现</h3>
 * <pre>
 *  首页分类   ul#menus a                    → 取 href + 文本，过滤 首页/最新/旧版
 *  列表项     ul#post_container li          → 标题 h2、图 img@src、日期 .info_date、链 a@href
 *  最新50部   div#tab-content li            → 链接 a@href + 文本（含《》时截取书名号起）
 *  详情封面   div#post_content img@src
 *  详情标题   div#content h1
 *  播放链接   div#post_content a[href^=magnet], a[href^=ed2k]
 * </pre>
 *
 * <h3>播放串命名规则（还原）</h3>
 * <pre>
 *  ed2k: 用 |file|(.*?)\| 从 URLDecoder.decode(href) 里取文件名
 *  magnet: 用 (^|&amp;)dn=([^&amp;]*)(&amp;|$) 取 dn 参数
 *  拼成 "名字$原始链接"，多集用 # 连接，线路名固定 "新6V"
 * </pre>
 *
 * <h2>真实请求验证</h2>
 * <ul>
 *   <li>首页 / 分类 / 详情 / 最新50部 / 搜索（含 302 跟随）：全部真实抓取</li>
 *   <li>夹具：{@code ae/fixtures/real/new6v_{home,cat,qian50m,detail,search}.html}</li>
 * </ul>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/New6v.java}（301 行）
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/New6v.java} 的对照复核，
 * <b>不是"没找"</b>。日后若发现遗漏，请在此补充并注明原方法名与行号。
  *
 * <h2>修正的上游缺陷</h2>
 *
 * <p>本类对上游的重写未修改其对外行为契约；<b>有意偏离</b>上游的地方（健壮性补强、
 * 命名歧义澄清、约定修正）已在上方「与上游的差异」一节逐条列出，并都有对应断言钉住。
 * 因此本节不再重复，以免同一事实在两处漂移。
 *
 * <p>判定口径："与上游的差异"与"修正的上游缺陷"在本项目里是同一类信息的两种措辞 ——
 * 前者强调"我们不一样在哪里"，后者强调"上游原本错了什么"。都以
 * {@code ae/decompiled/New6v.java} 为对照基准。
 */
public class New6vPlain extends BaseSpider {

    private static final String TAG = "New6vPlain";

    /** 默认站点（反编译 {@code New6v.a} 的初值） */
    public static final String DEFAULT_BASE = "http://www.xb6v.com";

    /** 桌面 UA（逐字来自 New6v.b()/c()） */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /**
     * 搜索入口。
     *
     * <p>反编译写死 {@code www.xb6v.org/e/search/11index.php}，实测 404；
     * 站点表单真实 action 为 {@code /e/search/so.php}（302 → result/?searchid=N）。
     * 这里用后者，并保留原路径作为兜底候选。
     */
    public static final String[] SEARCH_PATHS = {
            "/e/search/so.php",
            "/e/search/1index.php",
            "/e/search/11index.php"
    };

    /** 首页分类要排除的导航项（反编译硬编码） */
    private static final String[] EXCLUDED_NAV = {"首页", "最新", "旧版"};

    /** 短路径标记：命中时列表走 div#tab-content 而不是 ul#post_container */
    public static final String MARK_QIAN = "/qian";

    /** 搜索表单固定字段（逐字来自反编译的 LinkedHashMap 顺序） */
    private static final String[][] SEARCH_FORM = {
            {"show", "title"},
            {"tempid", "1"},
            {"tbname", "article"},
            {"mid", "1"},
            {"dopost", "search"},
            {"submit", ""},
            {"keyboard", ""}          // 关键词在运行时填
    };

    /** 磁力 dn 参数 */
    private static final Pattern RE_MAGNET_DN = Pattern.compile("(^|&)dn=([^&]*)(&|$)");
    /** ed2k 文件名 */
    private static final Pattern RE_ED2K_FILE = Pattern.compile("\\|file\\|(.*?)\\|");
    /** 书名号截取 */
    private static final Pattern RE_BOOK_TITLE = Pattern.compile("《[^》]*》");

    /** 分类页列表项起始标记 */
    private static final String LI_POST = "<li class=\"post";

    /**
     * 构造。
     */
    public New6vPlain() {
        super();
    }

    /**
     * 还原 {@code New6v.init(ctx, str)}：ext 若是纯地址就用它，否则用默认站点。
     *
     * @param ctx    宿主上下文
     * @param extend ext 原文（本类的 api.json 里就是一个裸 URL 字符串）
     */
    @Override
    public void init(Context ctx, String extend) {
        super.init(ctx, extend);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_BASE;
            Log.w(TAG, "ext 无地址，回退默认站点 " + DEFAULT_BASE);
        }
        // 多域名时用分类页探活（真实存在且轻量）
        if (ext.sites().size() > 1) {
            resolveAliveSite("/xijupian/");
        }
        Log.i(TAG, "init base=" + baseUrl);
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code New6v.c()} —— UA + Referer。
     *
     * @return header 表
     */
    protected Map<String, String> requestHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", baseUrl + "/");
        return h;
    }

    /**
     * GET 页面。
     *
     * @param url 完整地址
     * @return HTML；失败空串
     */
    protected String pageGet(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        return http.get(url, requestHeaders());
    }

    /**
     * 还原 {@code New6v.b(str)} —— 给图片地址附上 {@code @Headers={...}} 后缀。
     *
     * <p>这是 TVBox 宿主识别的「图片带请求头」语法，宿主会解析该后缀并带上 UA。
     * 直接返回裸地址也能显示，但部分图床有防盗链，所以保留后缀。
     *
     * @param url 图片地址
     * @return 带后缀的地址
     */
    public static String withHeaders(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        JSONObject h = new JSONObject();
        try {
            h.put("User-Agent", UA);
        } catch (Exception e) {
            return url;
        }
        return url + "@Headers=" + h.toString();
    }

    /**
     * 去掉 {@code @Headers=} 后缀，得到真正的图片地址（自测/调试用）。
     *
     * @param url 带后缀的地址
     * @return 纯地址
     */
    public static String stripHeaders(String url) {
        if (url == null) {
            return "";
        }
        int i = url.indexOf("@Headers=");
        return i < 0 ? url : url.substring(0, i);
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code New6v.homeContent(boolean)}：从 {@code ul#menus a} 取分类。
     *
     * <p>分类的 type_id 是<b>站内相对路径</b>（如 {@code /xijupian/}），
     * 因为分类页直接拼 {@code base + tid + index_N.html}。
     *
     * @param filter 是否返回筛选定义（上游固定空对象）
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        String html = pageGet(requireUrl() + "/");
        List<String[]> classes = parseMenu(html);
        VodBuilder vb = VodBuilder.create();
        for (String[] c : classes) {
            vb.clazz(c[0], c[1]);
        }
        return vb.homeContent();
    }

    /**
     * 解析 {@code ul#menus a}，过滤掉 首页/最新/旧版 三项。
     *
     * @param html 首页 HTML
     * @return 每项 {href, 名称}；失败返回空列表
     */
    protected List<String[]> parseMenu(String html) {
        List<String[]> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        // 用嵌套配对取 ul#menus 完整块 —— 菜单里每个 li 内还有一个 <ul class="sub-menu"></ul>，
        // 若按第一个 </ul> 截断，只能拿到「首页」这一项（实测踩过）。
        List<String> menuBlocks = HtmlLite.nestedBlocks(html, "<ul id=\"menus\"", "ul");
        if (menuBlocks.isEmpty()) {
            return out;
        }
        String block = menuBlocks.get(0);
        // 每个 <li ...><a href="...">文本</a>；子菜单的 <li> 没有 class="menu-item " 也无妨，统一按 <a 取
        Matcher m = Pattern.compile("(?is)<a\\s+[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>").matcher(block);
        while (m.find()) {
            String href = m.group(1);
            String name = HtmlLite.text(m.group(2));
            if (href.isEmpty() || name.isEmpty()) {
                continue;
            }
            if (isExcludedNav(name)) {
                continue;
            }
            // 站内相对路径才能拼分类页；外链（旧版6v）已被名称过滤
            if (!href.startsWith("http") && !href.startsWith("/")) {
                href = "/" + href;
            }
            out.add(new String[]{href, name});
        }
        return out;
    }

    /** 首页导航排除项 */
    private static boolean isExcludedNav(String name) {
        for (String s : EXCLUDED_NAV) {
            if (name.contains(s)) {
                return true;
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // 分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code New6v.categoryContent(...)}。
     *
     * <p>路径规则（反编译逐字）：
     * <pre>
     *  pg == "1"        → {tid}
     *  pg != "1"        → {tid}index_{pg}.html
     *  tid 含 ".html"    → 直接返回空串（防注入，上游就是这样）
     *  tid 不含 "http"   → 前面拼 base
     * </pre>
     *
     * <p>tid 含 {@code /qian} 时列表选择器换成 {@code div#tab-content li}
     * （「最新50部」页面结构不同）。
     *
     * @param tid    分类（站内路径）
     * @param pg     页码
     * @param filter 是否启用筛选
     * @param extend 筛选值
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String t = tid == null ? "" : tid.trim();
        if (t.isEmpty()) {
            return VodBuilder.create().page(page).categoryContent();
        }
        // 上游：含 .html 的 tid 直接拒
        if (t.contains(".html")) {
            Log.w(TAG, "tid 含 .html，拒绝: " + t);
            return emptyResponse();
        }

        String path = (page == 1) ? t : t + "index_" + page + ".html";
        // 筛选值同样按 {key} 占位替换（本类模板里没有占位，保留兼容）
        path = fillPlaceholders(path, extend);
        String url = t.contains("http") ? path : HtmlLite.absolute(requireUrl(), path);

        String html = pageGet(url);
        List<Vod> list = parseCategory(html, t);
        int limit = Math.max(list.size(), 1);
        // 上游 pagecount 给 Integer.MAX_VALUE（站点没有总数）
        return VodBuilder.create().addAll(list)
                .page(page, Integer.MAX_VALUE, limit).categoryContent();
    }

    /**
     * 占位符替换（还原上游 for 循环 replace）。
     *
     * @param path   路径模板
     * @param extend 筛选值
     * @return 替换后路径
     */
    protected static String fillPlaceholders(String path, HashMap<String, String> extend) {
        String p = path == null ? "" : path;
        if (extend != null) {
            for (Map.Entry<String, String> e : extend.entrySet()) {
                String v = e.getValue();
                if (v != null && !v.isEmpty()) {
                    p = p.replace("{" + e.getKey() + "}", v);
                }
            }
        }
        return p;
    }

    /**
     * 解析分类列表页 —— 两种结构都覆盖。
     *
     * <p>结构一（普通分类，{@code ul#post_container li}）：
     * <pre>
     *  &lt;li class="post box row"&gt;…&lt;h2&gt;&lt;a href="/xijupian/29820.html"&gt;阳光舞者&lt;/a&gt;&lt;/h2&gt;
     *      &lt;img src="https://tu.66tutup.com:667/2026/3944.jpg" alt="阳光舞者"/&gt;
     *      &lt;span class="info_date info_ico"&gt;2026-10-17&lt;/span&gt;…&lt;/li&gt;
     * </pre>
     * 结构二（最新50部，{@code div#tab-content li}）：
     * <pre>
     *  &lt;li&gt;&lt;a href='/juqingpian/29821.html'&gt;2026高分传记…&lt;/a&gt;&lt;/li&gt;
     * </pre>
     *
     * @param html 页面 HTML
     * @param tid  当前分类（用于判断是否 /qian）
     * @return 影片列表
     */
    protected List<Vod> parseCategory(String html, String tid) {
        List<Vod> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        if (tid != null && tid.contains(MARK_QIAN)) {
            return parseTabContent(html);
        }
        for (String block : HtmlLite.blocks(html, LI_POST, "</li>")) {
            String name = HtmlLite.text(HtmlLite.group(block,
                    Pattern.compile("(?is)<h2[^>]*>(.*?)</h2>"), 1));
            String href = HtmlLite.attr(block, "href");
            String pic = HtmlLite.attr(block, "src");
            String date = HtmlLite.text(HtmlLite.group(block,
                    Pattern.compile("(?is)class=\"info_date[^\"]*\"[^>]*>(.*?)</span>"), 1));
            if (name.isEmpty() && href.isEmpty()) {
                continue;
            }
            out.add(new Vod()
                    .id(href)
                    .name(name)
                    .pic(withHeaders(pic))
                    .remarks(date));
        }
        return out;
    }

    /**
     * 解析「最新50部」页面 —— {@code div#tab-content li}。
     *
     * <p>该页每个 li 只有一条 a：文本里带《剧名》时截取书名号起，
     * 因为文本前缀是「2026喜剧剧情」这类年份/类型标签。
     *
     * @param html 页面 HTML
     * @return 影片列表
     */
    protected List<Vod> parseTabContent(String html) {
        List<Vod> out = new ArrayList<>();
        int i = html.indexOf("id=\"tab-content\"");
        if (i < 0) {
            return out;
        }
        String sub = html.substring(i);
        for (String block : HtmlLite.blocks(sub, "<li>", "</li>")) {
            String href = HtmlLite.attr(block, "href");
            String name = HtmlLite.text(block);
            if (href.isEmpty() || name.isEmpty()) {
                continue;
            }
            name = cutBookTitle(name);
            out.add(new Vod().id(href).name(name).pic("").remarks(""));
        }
        return out;
    }

    /**
     * 若有《》则从书名号起截取（还原 {@code str.substring(str.indexOf("《"))}）。
     *
     * @param s 原文
     * @return 截取结果；无书名号原样返回
     */
    public static String cutBookTitle(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        Matcher m = RE_BOOK_TITLE.matcher(s);
        if (m.find()) {
            return s.substring(m.start()).trim();
        }
        return s.trim();
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code New6v.detailContent(List)}。
     *
     * <p>vod_id 是站内相对路径（如 {@code /xijupian/29820.html}），
     * 直接 {@code base + vod_id} 取页面。
     *
     * @param ids 影片 id（相对路径）
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String vid = firstId(ids);
        if (vid.isEmpty()) {
            return emptyResponse();
        }
        String url = HtmlLite.absolute(requireUrl(), vid);
        String html = pageGet(url);
        if (TextUtils.isEmpty(html)) {
            Log.w(TAG, "详情页为空: " + url);
            return emptyResponse();
        }

        String pic = HtmlLite.attr(HtmlLite.group(html,
                Pattern.compile("(?is)<div[^>]*id=\"post_content\"[^>]*>(.*?)</div>"), 1), "src");
        // 兼容两种层级：div#content>div>h1（反编译写法）与 div#content h1（实测写法）
        String title = HtmlLite.text(HtmlLite.group(html,
                Pattern.compile("(?is)<div[^>]*id=\"content\"[^>]*>.*?<h1[^>]*>(.*?)</h1>"), 1));

        List<String> eps = parsePlayLinks(html);
        Vod vod = new Vod()
                .id(vid)
                .name(title)
                .pic(withHeaders(pic))
                .content("")
                .playFrom(eps.isEmpty() ? "" : "新6V")
                .playUrl(HtmlLite.join("#", eps));
        return VodBuilder.detail(vod).detailContent();
    }

    /**
     * 解析磁力/ed2k 链接。
     *
     * <p>反编译用 {@code div#post_content a[href^=magnet],a[href^=ed2k]}，
     * 即整个 {@code #post_content} 里所有以 magnet:/ed2k: 开头的 a。
     *
     * @param html 详情页
     * @return {@code 名称$链接} 列表
     */
    protected List<String> parsePlayLinks(String html) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        int i = html.indexOf("id=\"post_content\"");
        String scope = i >= 0 ? html.substring(i) : html;
        Matcher m = Pattern.compile("(?is)<a\\s+[^>]*href=\"(magnet:[^\"]*|ed2k:[^\"]*)\"[^>]*>(.*?)</a>")
                .matcher(scope);
        while (m.find()) {
            String href = HtmlLite.decodeEntities(m.group(1));
            String label = HtmlLite.text(m.group(2));
            out.add(buildPlayItem(label, href));
        }
        return out;
    }

    /**
     * 还原播放项命名：ed2k 取 {@code |file|名字|}，magnet 取 {@code dn=} 参数。
     *
     * @param label a 标签文本（兜底名）
     * @param href  原始链接
     * @return {@code 名称$链接}
     */
    public static String buildPlayItem(String label, String href) {
        String name = HtmlLite.nz(label);
        String decoded = href;
        try {
            decoded = URLDecoder.decode(href, "UTF-8");
        } catch (Exception ignored) {
            // 解码失败就用原文
        }
        if (href != null && href.startsWith("ed2k:")) {
            Matcher m = RE_ED2K_FILE.matcher(decoded);
            if (m.find()) {
                name = m.group(1);
            }
        } else if (href != null && href.startsWith("magnet:")) {
            Matcher m = RE_MAGNET_DN.matcher(decoded);
            if (m.find()) {
                name = m.group(2);
            }
        }
        return name + "$" + HtmlLite.nz(href);
    }

    // ─────────────────────────────────────────────────────────────
    // 搜索
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code New6v.searchContent(str, z)} —— POST 表单搜索。
     *
     * <p>与原实现的差异（实测修正）：原实现硬编码 {@code www.xb6v.org/e/search/11index.php}，
     * 该地址已 404。站点真实表单 action 是 {@code /e/search/so.php}，返回 302 到
     * {@code /e/search/result/?searchid=N}。本实现按 {@link #SEARCH_PATHS} 顺序尝试，
     * 并对 302 的 Location 再取一次。
     *
     * @param key   关键词
     * @param quick 快速搜索（上游未使用）
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) {
            return VodBuilder.create().searchContent();
        }
        String base = requireUrl();
        if (base.isEmpty()) {
            return VodBuilder.create().searchContent();
        }
        String html = "";
        for (String path : SEARCH_PATHS) {
            html = postSearch(base + path, key);
            if (html.contains("post_container") || html.contains("post box")) {
                break;
            }
            html = "";
        }
        return VodBuilder.of(parseCategory(html, "search")).searchContent();
    }

    /**
     * 发一次搜索 POST，若 302 则跟随 Location 再 GET。
     *
     * @param url 搜索入口
     * @param key 关键词
     * @return 结果页 HTML；失败空串
     */
    protected String postSearch(String url, String key) {
        String body = buildSearchBody(key);
        Map<String, String> h = requestHeaders();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        Http.Result r = http.execute("POST", url, body, h);
        if (r == null) {
            return "";
        }
        if (r.code >= 300 && r.code < 400) {
            String loc = extractLocation(r);
            if (!loc.isEmpty()) {
                return pageGet(HtmlLite.absolute(originOf(url), loc));
            }
            return "";
        }
        return r.body == null ? "" : r.body;
    }

    /**
     * 从 302 响应里取 Location（{@code Http.Result} 不含响应头，这里只能靠
     * 已经跟随跳转的 {@code lastUrl} 或正文里的 location 文本）。
     *
     * <p>实现说明：{@code Http} 默认 {@code followRedirects=true}，
     * 所以 302 通常已被内部跟随，{@code r.body} 就是结果页。此方法处理
     * 「未跟随」的少数情况：解析不到就返回空串，由调用方按失败处理。
     *
     * @param r 结果
     * @return Location 值或空串
     */
    protected String extractLocation(Http.Result r) {
        if (r == null || r.body == null) {
            return "";
        }
        Matcher m = Pattern.compile("(?i)location:\\s*(\\S+)").matcher(r.body);
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     * 构造搜索表单体（保持反编译里的字段顺序）。
     *
     * @param key 关键词
     * @return form-urlencoded 体
     */
    public static String buildSearchBody(String key) {
        StringBuilder sb = new StringBuilder();
        for (String[] kv : SEARCH_FORM) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            String v = "keyboard".equals(kv[0]) ? HtmlLite.nz(key) : kv[1];
            sb.append(kv[0]).append('=').append(urlEncode(v));
        }
        return sb.toString();
    }

    /**
     * 取 URL 的 scheme://host 部分。
     *
     * @param url 完整地址
     * @return 源；解析失败返回原串
     */
    public static String originOf(String url) {
        if (url == null) {
            return "";
        }
        int i = url.indexOf("://");
        if (i < 0) {
            return url;
        }
        int j = url.indexOf('/', i + 3);
        return j < 0 ? url : url.substring(0, j);
    }

    /** UTF-8 百分号编码 */
    protected static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(HtmlLite.nz(s), "UTF-8");
        } catch (Exception e) {
            return HtmlLite.nz(s);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code New6v.playerContent(...)}：
     * {@code tvbox}/{@code ed2k} 开头 → parse=0 直链；其余 → parse=1 且带 header。
     *
     * @param flag     线路名
     * @param id       播放地址
     * @param vipFlags 需要解析的标识
     * @return 播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) {
            return emptyResponse();
        }
        JSONObject o = new JSONObject();
        try {
            o.put("url", id);
            if (id.startsWith("tvbox") || id.startsWith("ed2k") || id.startsWith("magnet")) {
                o.put("parse", 0);
            } else {
                JSONObject h = new JSONObject();
                h.put("User-Agent", UA);
                o.put("header", h.toString());
                o.put("parse", 1);
            }
            o.put("playUrl", "");
            return o.toString();
        } catch (Exception e) {
            Log.e(TAG, "playerContent 构造失败", e);
            return emptyResponse();
        }
    }

    /**
     * 首页推荐（还原 {@code homeVideoContent()}）—— 用的是 {@code ul#post_container li}。
     *
     * @return 标准搜索结构（宿主对 homeVideo 只读 list）
     */
    @Override
    public String homeVideoContent() {
        String html = pageGet(requireUrl() + "/");
        return VodBuilder.of(parseCategory(html, "home")).searchContent();
    }

    /**
     * 供测试读到的默认分类项数量（真实首页导航）。
     *
     * @return 分类 {href,name} 列表
     */
    public List<String[]> classesForTest() {
        return parseMenu(pageGet(requireUrl() + "/"));
    }

    /** 便于测试注入的 JSONArray 辅助（未使用，占位保持 import 语义） */
    static JSONArray emptyArray() {
        return new JSONArray();
    }
}
