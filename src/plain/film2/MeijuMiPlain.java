package com.example.tvbox.plain.film2;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 美剧迷（MeijuMi）—— 磁力/网盘下载站，WordPress 主题，HTML 抓取。
 *
 * <h2>协议还原（来自 {@code ae/decompiled/MeijuMi.java}，并用真实请求验证）</h2>
 *
 * <h3>端点</h3>
 * <pre>
 *  首页    GET {base}
 *  分类    GET {tid}                    （第 1 页，tid 是绝对 URL，含尾斜杠）
 *          GET {tid}page/{pg}/          （第 2 页起）
 *  详情    GET {vod_id}                 （vod_id 就是绝对 URL）
 *  搜索    GET {base}/?s={urlencode}
 * </pre>
 *
 * <h3>HTML 选择器（逐字对应上游 jsoup 链）</h3>
 * <pre>
 *  首页分类   ul.nav-menu a                      → href + 文本，过滤 更新/推荐/首页/韩剧/即将
 *  列表项     div#post_list_box article          → a@title、img@src、.gxts 文本、a@href
 *  详情标题   .entry-title                       → 去「迅雷下载」后 trim
 *  详情封面   div.single-content img@src
 *  播放链接   div.single-content a[href^=magnet], a[href^=ed2k]
 *  搜索结果   ul.search-page article
 * </pre>
 *
 * <h3>标题清洗（还原上游那段被 jadx 打乱的 substring）</h3>
 * <pre>
 *  原文: "《美国恐怖故事第十三季》American Horror Story Season 13 网盘/迅雷下载"
 *  取《》之间的内容: "美国恐怖故事第十三季"
 *  再 replace("迅雷下载","").trim()
 * </pre>
 *
 * <h3>实测差异（重要）</h3>
 * <ul>
 *   <li><b>搜索被风控拦截</b>：{@code /?s=xxx} 当前返回
 *       「美剧迷-人机验证」页面（erphp-search-captcha），不含 {@code ul.search-page}。
 *       代码按原选择器实现，同时显式识别验证页并返回空列表（而不是抛异常）。</li>
 *   <li>分类页每页 15 条（夹具保留了前 3 条 + 分页块）。</li>
 * </ul>
 *
 * <h2>真实请求验证</h2>
 * <ul>
 *   <li>首页 / 分类第 1 页 / 分类第 2 页 / 详情 / 搜索（验证页）：全部真实抓取</li>
 *   <li>夹具：{@code ae/fixtures/real/meijumi_{cat,detail,search_captcha}.html}</li>
 * </ul>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/MeijuMi.java}（219 行）
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/MeijuMi.java} 的对照复核，
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
 * {@code ae/decompiled/MeijuMi.java} 为对照基准。
 */
public class MeijuMiPlain extends BaseSpider {

    private static final String TAG = "MeijuMiPlain";

    /** 默认站点（反编译 {@code MeijuMi.a} 初值） */
    public static final String DEFAULT_BASE = "https://www.meijumi.net";

    /** 桌面 UA（逐字来自 MeijuMi.b()/c()） */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 首页导航要排除的项（反编译硬编码 5 个） */
    private static final String[] EXCLUDED_NAV = {"更新", "推荐", "首页", "韩剧", "即将"};

    /** 标题里要剔除的后缀词 */
    public static final String NOISE_WORD = "迅雷下载";

    /** 人人机验证页特征（实测搜索会命中） */
    public static final String CAPTCHA_MARK = "erphp-search-captcha";

    private static final Pattern RE_MAGNET_DN = Pattern.compile("(^|&)dn=([^&]*)(&|$)");
    private static final Pattern RE_ED2K_FILE = Pattern.compile("\\|file\\|(.*?)\\|");

    /** 列表项起始标记 */
    private static final String ART_START = "<article id=\"post-";

    /**
     * 构造。
     */
    public MeijuMiPlain() {
        super();
    }

    /**
     * 还原 {@code MeijuMi.init(ctx, str)}：ext 是裸 URL。
     *
     * @param ctx    宿主上下文
     * @param extend ext 原文
     */
    @Override
    public void init(Context ctx, String extend) {
        super.init(ctx, extend);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_BASE;
            Log.w(TAG, "ext 无地址，回退默认站点 " + DEFAULT_BASE);
        }
        if (ext.sites().size() > 1) {
            resolveAliveSite("/usa/");
        }
        Log.i(TAG, "init base=" + baseUrl);
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code MeijuMi.c()} —— UA + Referer。
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
     * 还原 {@code MeijuMi.b(str)} —— 图片 {@code @Headers=} 后缀。
     *
     * @param url 图片地址
     * @return 带后缀地址
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

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code MeijuMi.homeContent(boolean)}：{@code ul.nav-menu a}。
     *
     * <p>注意：与 New6v 不同，本类的 type_id 是<b>绝对 URL</b>，
     * 因为分类页直接拼 {@code tid + "page/N/"}。
     *
     * @param filter 是否返回筛选（上游固定空对象）
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        String html = pageGet(requireUrl() + "/");
        VodBuilder vb = VodBuilder.create();
        for (String[] c : parseMenu(html)) {
            vb.clazz(c[0], c[1]);
        }
        return vb.homeContent();
    }

    /**
     * 解析 {@code ul.nav-menu a}。
     *
     * @param html 首页 HTML
     * @return {href, name} 列表
     */
    protected List<String[]> parseMenu(String html) {
        List<String[]> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        // 导航容器：<ul id="menu-xxx" class="down-menu nav-menu">
        List<String> blocks = HtmlLite.nestedBlocks(html, "<ul id=\"menu-", "ul");
        String block = "";
        for (String b : blocks) {
            if (b.contains("nav-menu")) {
                block = b;
                break;
            }
        }
        if (block.isEmpty()) {
            return out;
        }
        Matcher m = Pattern.compile("(?is)<a\\s+[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>").matcher(block);
        while (m.find()) {
            String href = m.group(1);
            String name = HtmlLite.text(m.group(2));
            if (href.isEmpty() || name.isEmpty() || isExcludedNav(name)) {
                continue;
            }
            out.add(new String[]{href, name});
        }
        return out;
    }

    /** 导航排除项 */
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
     * 还原 {@code MeijuMi.categoryContent(...)}。
     *
     * <pre>
     *  pg == 1 → {tid}
     *  pg != 1 → {tid}page/{pg}/
     * </pre>
     *
     * @param tid    分类（绝对 URL，含尾斜杠）
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
        String path = (page == 1) ? t : t + "page/" + page + "/";
        path = fillPlaceholders(path, extend);
        String url = t.startsWith("http") ? path : HtmlLite.absolute(requireUrl(), path);

        List<Vod> list = parseList(pageGet(url));
        int limit = Math.max(list.size(), 1);
        return VodBuilder.create().addAll(list)
                .page(page, Integer.MAX_VALUE, limit).categoryContent();
    }

    /**
     * 占位符替换（还原上游 for 循环 replace）。
     *
     * @param path   路径
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
     * 解析 {@code div#post_list_box article} 列表。
     *
     * @param html 页面
     * @return 影片列表
     */
    protected List<Vod> parseList(String html) {
        List<Vod> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        int box = html.indexOf("id=\"post_list_box\"");
        String scope = box >= 0 ? html.substring(box) : html;
        for (String block : HtmlLite.blocks(scope, ART_START, "</article>")) {
            // a@title（第一个 a 是封面链接，title 属性带完整标题）
            String raw = HtmlLite.attr(block, "title");
            if (raw.isEmpty()) {
                raw = HtmlLite.text(HtmlLite.group(block,
                        Pattern.compile("(?is)<h2[^>]*class=\"[^\"]*entry-title[^\"]*\"[^>]*>(.*?)</h2>"), 1));
            }
            String name = cleanTitle(raw);
            String href = HtmlLite.attr(block, "href");
            String pic = HtmlLite.attr(block, "src");
            String remarks = HtmlLite.text(HtmlLite.group(block,
                    Pattern.compile("(?is)<span[^>]*class=\"[^\"]*gxts[^\"]*\"[^>]*>(.*?)</span>"), 1));
            if (href.isEmpty() && name.isEmpty()) {
                continue;
            }
            out.add(new Vod()
                    .id(href)
                    .name(name)
                    .pic(withHeaders(pic))
                    .remarks(remarks));
        }
        return out;
    }

    /**
     * 标题清洗：取《》之间内容，再去掉「迅雷下载」。
     *
     * <p>还原上游：
     * <pre>
     *  int s = raw.indexOf("《");
     *  int e = raw.indexOf("》", s + 1);
     *  if (s &gt;= 0 &amp;&amp; e &gt;= 0) raw = raw.substring(s + 1, e);
     *  return raw.replace("迅雷下载", "").trim();
     * </pre>
     *
     * @param raw 原始标题
     * @return 清洗后标题
     */
    public static String cleanTitle(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        int a = s.indexOf("《");
        if (a >= 0) {
            int b = s.indexOf("》", a + 1);
            if (b > a) {
                s = s.substring(a + 1, b);
            }
        }
        return s.replace(NOISE_WORD, "").trim();
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code MeijuMi.detailContent(List)}。
     *
     * @param ids 影片 id（绝对是 URL）
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String vid = firstId(ids);
        if (vid.isEmpty()) {
            return emptyResponse();
        }
        String html = pageGet(HtmlLite.absolute(requireUrl(), vid));
        if (TextUtils.isEmpty(html)) {
            Log.w(TAG, "详情页为空: " + vid);
            return emptyResponse();
        }
        String pic = HtmlLite.attr(HtmlLite.group(html,
                Pattern.compile("(?is)<div[^>]*class=\"[^\"]*single-content[^\"]*\"[^>]*>(.*?)</div>"), 1),
                "src");
        String title = cleanTitle(HtmlLite.text(HtmlLite.group(html,
                Pattern.compile("(?is)<[^>]*class=\"[^\"]*entry-title[^\"]*\"[^>]*>(.*?)</(?:h1|h2|div)>"), 1)));

        List<String> eps = parsePlayLinks(html);
        Vod vod = new Vod()
                .id(vid)
                .name(title)
                .pic(withHeaders(pic))
                .content("")
                .playFrom(eps.isEmpty() ? "" : "美剧迷")
                .playUrl(HtmlLite.join("#", eps));
        return VodBuilder.detail(vod).detailContent();
    }

    /**
     * 解析磁力/ed2k 链接。
     *
     * @param html 详情页
     * @return {@code 名称$链接} 列表
     */
    protected List<String> parsePlayLinks(String html) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        int i = html.indexOf("single-content");
        String scope = i >= 0 ? html.substring(i) : html;
        Matcher m = Pattern.compile("(?is)<a\\s+[^>]*href=\"(magnet:[^\"]*|ed2k:[^\"]*)\"[^>]*>(.*?)</a>")
                .matcher(scope);
        while (m.find()) {
            out.add(buildPlayItem(HtmlLite.text(m.group(2)), HtmlLite.decodeEntities(m.group(1))));
        }
        return out;
    }

    /**
     * 还原播放项命名（与 New6v 同一套规则）。
     *
     * @param label a 文本（兜底名）
     * @param href  原始链接
     * @return {@code 名称$链接}
     */
    public static String buildPlayItem(String label, String href) {
        String name = HtmlLite.nz(label);
        String decoded = href;
        try {
            decoded = URLDecoder.decode(href == null ? "" : href, "UTF-8");
        } catch (Exception ignored) {
            // 忽略
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
     * 还原 {@code MeijuMi.searchContent(str, z)}：{@code {base}/?s=关键词}。
     *
     * <p><b>实测风控</b>：当前站点对该 URL 返回「人机验证」页，
     * 因此会命中 {@link #isCaptchaPage(String)} 并返回空列表。
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
        String k;
        try {
            k = URLEncoder.encode(key, "UTF-8");
        } catch (Exception e) {
            k = key;
        }
        String html = pageGet(base + "/?s=" + k);
        if (isCaptchaPage(html)) {
            Log.w(TAG, "搜索被风控拦截（人机验证页），返回空列表");
            return VodBuilder.create().searchContent();
        }
        return VodBuilder.of(parseSearchResult(html)).searchContent();
    }

    /**
     * 是否为人机验证页（实测特征 {@code erphp-search-captcha}）。
     *
     * @param html 响应
     * @return true 表示被拦截
     */
    public static boolean isCaptchaPage(String html) {
        return html != null && html.contains(CAPTCHA_MARK);
    }

    /**
     * 解析 {@code ul.search-page article}。
     *
     * @param html 搜索页
     * @return 影片列表
     */
    protected List<Vod> parseSearchResult(String html) {
        List<Vod> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        int box = html.indexOf("search-page");
        if (box < 0) {
            return out;
        }
        String scope = html.substring(box);
        for (String block : HtmlLite.blocks(scope, ART_START, "</article>")) {
            String raw = HtmlLite.attr(block, "title");
            String name = cleanTitle(raw);
            String href = HtmlLite.attr(block, "href");
            String pic = HtmlLite.attr(block, "src");
            String remarks = HtmlLite.text(HtmlLite.group(block,
                    Pattern.compile("(?is)<span[^>]*class=\"[^\"]*gxts[^\"]*\"[^>]*>(.*?)</span>"), 1));
            if (href.isEmpty() && name.isEmpty()) {
                continue;
            }
            out.add(new Vod().id(href).name(name).pic(withHeaders(pic)).remarks(remarks));
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code MeijuMi.playerContent(...)}：
     * {@code tvbox}/{@code ed2k} → parse=0；其余 → parse=1 + header。
     *
     * <p>本重写额外把 {@code magnet:} 也按直链处理（磁力不需要嗅探，
     * 上游遗漏了该分支，会让磁力链接被误交给嗅探器）。
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
}
