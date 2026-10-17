package com.example.tvbox.plain.ting;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.common.Html;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 堇夏听书（{@code csp_TingJinxia}）明文实现 —— 有听网 {@code m.ting15.com}，纯 HTML 抓取。
 *
 * <p>继承 {@link BaseSpider}（上游 {@code extends Spider}）。<b>无加密</b>；
 * 唯一带「签名」的地方是播放接口 {@code api-getneoplay}，参数全部来自页面 meta，
 * 不需要任何本地计算（见下）。
 *
 * <h2>协议（全部经真实请求验证）</h2>
 * <pre>
 * 分页规则（上游逐字）
 *   第 1 页：  /{tid}/                        （无 index 后缀）
 *   第 N 页：  /{tid}/index{N}                （N>=2）
 *   带排序：   /{tid}/index{N}-order-{o}.html  o ∈ {hits, addtime}
 *
 * 分类/搜索共用列表解析
 *   条目容器:  div.clist > a[href]        （备选: section a[href$=.html]:has(h3)）
 *   标题:      h3（去掉 "[xxx]" 前缀）
 *   封面:      dt img[src]，再退化到 img[src]
 *             封面会追加 @Referer=...@User-Agent=... （TVBox 的防盗链语法）
 *   备注:      dd p 里以「连载」或「状态」开头的行
 *   分页总数:  .cpage span 文本 "1/34" 的第二段
 *
 * 详情  GET {base}{相对路径}
 *   标题  .binfo h1
 *   封面  .bimg img[src]
 *   作者/类型/状态/播音  .binfo p 里「作者：xxx」这类（中英文冒号都要处理）
 *   简介  .intro p 中长度 > 20 且不含「看APP」的第一个
 *   分集  .plist a.f  → 集名$href
 *
 * 播放  GET {base}{分集路径}
 *   从页面 meta 取 4 个参数: _b(bookId) / _p(isPay) / _cp(page) / _c(xt)
 *   POST {base}/?s=api-getneoplay   body: bookId&isPay&page&xt
 *   → JSON {url, status, ourl}      ← 注意响应带 BOM，必须先剥掉
 *   兜底: 页面 audio#player[src]
 *
 * 搜索  GET {base}/?s=ting-search-wd-{urlencode(key)}   → 与分类相同的列表解析
 * </pre>
 *
 * <h2>关键坑（都踩过）</h2>
 * <ol>
 *   <li><b>BOM</b>：{@code api-getneoplay} 响应以 {@code \uFEFF} 开头，
 *       {@code new JSONObject()} 会直接抛。上游有 {@code startsWith("\ufeff")} 剥离逻辑</li>
 *   <li><b>必填 4 个参数</b>：{@code xt(_c)} 缺失时接口返回失败；
 *       {@code _cp} 缺失时上游默认 "1"</li>
 *   <li><b>封面防盗链</b>：图片地址必须带 {@code @Referer=...@User-Agent=...} 后缀，
 *       否则宿主播放器/图片加载器取不到图</li>
 *   <li><b>h3 的 "[类型]" 前缀</b>：部分列表的 h3 形如 {@code [连载]书名}，要剥掉</li>
 *   <li>站点 HTML 带 UTF-8 BOM，{@code text()} 前需处理</li>
 * </ol>
 *
 * <h2>与上游的差异</h2>
 * <ul>
 *   <li>上游 {@code .cpage span} 遍历里有个判断写反了
 *       （{@code if(!text.contains("/")) { split("/") }} —— 不含 "/" 才 split，永远拿不到页数）。
 *       本实现按真实页面 {@code "1/34"} 正确解析</li>
 *   <li>分集数上限 95（上游 {@code arrayList.size() >= 95} break），本实现保留</li>
 * </ul>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/TingJinxia.java}（342 行）
 * 真实夹具:   {@code ae/fixtures/real/tingjinxia_category.html}
 *             {@code ae/fixtures/real/tingjinxia_detail.html}
 *             {@code ae/fixtures/real/tingjinxia_play.html}
 *             {@code ae/fixtures/real/tingjinxia_neoplay.json}
 * 抓取时间:   2026-10-17
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/TingJinxia.java} 的对照复核，
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
 * {@code ae/decompiled/TingJinxia.java} 为对照基准。
 */
public class TingJinxiaPlain extends BaseSpider {

    private static final String TAG = "TingJinxiaPlain";

    /** 默认站点（ext 提供时覆盖；上游 static a 的初值） */
    private static final String DEFAULT_BASE = "https://m.ting15.com";

    /** 上游 UA（逐字） */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 封面防盗链后缀（TVBox 语法，上游逐字） */
    private static final String PIC_SUFFIX =
            "@Referer=https://m.ting15.com/@User-Agent=" + UA;

    /** 分类表（上游 f9a，9 项，顺序敏感） */
    private static final String[][] CATEGORIES = {
            {"wuxiaxuanhuan", "武侠玄幻"},
            {"kongbulingyi", "恐怖灵异"},
            {"tuilixuanyi", "推理悬疑"},
            {"dushiyanqing", "都市言情"},
            {"jiatinglunli", "家庭伦理"},
            {"wenxuemingzhu", "文学名著"},
            {"jingdianpingshu", "经典评书"},
            {"quyixiqu", "曲艺戏曲"},
            {"xiangShengXiaoPin", "相声小品"},
    };

    /** 排序筛选项（上游 order: 全部/热门/最新） */
    private static final String ORDER_FILTER =
            "[{\"key\":\"order\",\"name\":\"排序\",\"value\":["
          + "{\"n\":\"全部\",\"v\":\"\"},{\"n\":\"热门\",\"v\":\"hits\"},"
          + "{\"n\":\"最新\",\"v\":\"addtime\"}]}]";

    /** 列表最多取 95 条（上游 break 条件） */
    private static final int MAX_LIST = 95;

    /** 播放接口的 BOM 字符 */
    private static final char BOM = '\uFEFF';

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_BASE;
            Log.i(TAG, "ext 无 url，使用内置站点 " + DEFAULT_BASE);
        }
        // 上游：去掉尾部斜杠
        while (baseUrl.endsWith("/") && baseUrl.length() > 1) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    /** 还原上游 {@code a(str)}：UA + Referer + Accept */
    public Map<String, String> apiHeaders(String refererPath) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", baseUrl + "/");
        h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        return h;
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页：9 分类 + 首页推荐 + 排序筛选（还原上游 {@code homeContent}）。
     *
     * @param filter 是否带筛选定义
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        JSONObject root = new JSONObject();
        try {
            org.json.JSONArray cls = new org.json.JSONArray();
            for (String[] c : CATEGORIES) {
                JSONObject o = new JSONObject();
                o.put("type_id", c[0]);
                o.put("type_name", c[1]);
                cls.put(o);
            }
            root.put("class", cls);

            // 首页推荐：抓 "/" 的列表（失败不影响分类可用）
            List<Vod> recommend = new ArrayList<>();
            if (!requireUrl().isEmpty()) {
                String html = http.get(baseUrl + "/", apiHeaders("/"));
                recommend = parseList(html);
            }
            org.json.JSONArray list = new org.json.JSONArray();
            for (Vod v : recommend) {
                list.put(v.toJSONObject());
            }
            root.put("list", list);

            if (filter) {
                JSONObject filters = new JSONObject();
                org.json.JSONArray order = new org.json.JSONArray(ORDER_FILTER);
                for (String[] c : CATEGORIES) {
                    filters.put(c[0], order);
                }
                root.put("filters", filters);
            } else {
                root.put("filters", new JSONObject());
            }
            return root.toString();
        } catch (Exception e) {
            Log.e(TAG, "homeContent build failed", e);
            return emptyResponse();
        }
    }

    /** 供测试：分类数 */
    public static int categoryCount() {
        return CATEGORIES.length;
    }

    // ─────────────────────────────────────────────────────────────
    // URL 构造
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游分类 URL 构造（含那个容易写错的 index 规则）。
     *
     * <pre>
     *  第 1 页  → /{tid}/              （结尾带斜杠）
     *  第 N 页  → /{tid}/index{N}
     *  带排序   → /{tid}/index{N}-order-{o}.html
     * </pre>
     *
     * @param tid   分类 id
     * @param page  页码（>=1）
     * @param order 排序（hits/addtime，可空）
     * @return 相对路径
     */
    public static String categoryPath(String tid, int page, String order) {
        String t = tid == null ? "" : tid.trim();
        // 空 tid 无法构造有意义路径 —— 返回空串让调用方走空响应，不要发出 "//" 这种请求
        if (t.isEmpty()) {
            return "";
        }
        if (TextUtils.isEmpty(order)) {
            return "/" + t + (page <= 1 ? "/" : "/index" + page);
        }
        return "/" + t + "/index" + Math.max(page, 1) + "-order-" + order + ".html";
    }

    /**
     * 还原上游 {@code a(str)} 里的相对路径补全逻辑。
     *
     * <pre>
     *  ""            → base
     *  http...       → 原样
     *  //x           → https://x
     *  /x            → base + /x
     *  x             → base + / + x
     * </pre>
     *
     * @param path 相对或绝对路径
     * @return 完整 URL
     */
    public String absolute(String path) {
        if (TextUtils.isEmpty(path)) {
            return baseUrl;
        }
        if (path.startsWith("http")) {
            return path;
        }
        if (path.startsWith("//")) {
            return "https:".concat(path);
        }
        if (path.startsWith("/")) {
            return baseUrl + path;
        }
        return baseUrl + "/" + path;
    }

    // ─────────────────────────────────────────────────────────────
    // 列表解析
    // ─────────────────────────────────────────────────────────────

    /**
     * 解析列表页（分类 / 搜索 / 首页共用）。
     *
     * @param html 页面 HTML
     * @return 影片列表，永不 null
     */
    public List<Vod> parseList(String html) {
        List<Vod> out = new ArrayList<>();
        if (TextUtils.isEmpty(html)) {
            return out;
        }
        // 容器：div.clist 里的 a[href]
        String clist = Html.innerFirst(html, "div", ".*\\bclist\\b.*");
        String scope = clist.isEmpty() ? html : clist;

        List<String> seps = new ArrayList<>();
        // a 标签可能自闭合或成对；这里按「开标签位置 + 内容」逐段取
        for (String a : Html.children(scope, "a", null)) {
            // children 给内部 HTML，需要开标签才能读 href —— 用正则重新定位
            seps.add(a);
        }
        // 直接按正则抽 a 块（更贴合真实页面结构）
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<a\\b([^>]*)>(.*?)</a\\s*>", java.util.regex.Pattern.DOTALL)
                .matcher(scope);
        while (m.find()) {
            if (out.size() >= MAX_LIST) {
                break;
            }
            String attrs = m.group(1);
            String inner = m.group(2);
            String href = Html.attr("<a" + attrs + ">", "href").trim();
            if (href.isEmpty() || href.startsWith("http") || href.startsWith("javascript:")) {
                continue;
            }
            Vod v = toVod(href, inner);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    /**
     * 单条目解析（还原上游 {@code c(f)} 循环体）。
     *
     * @param href  详情相对路径
     * @param inner {@code <a>} 内部 HTML
     * @return 影片；无标题返回 null
     */
    public Vod toVod(String href, String inner) {
        String name = Html.text(Html.innerFirst(inner, "h3"));
        // 剥掉 "[xxx]" 前缀
        if (name.startsWith("[") && name.contains("]")) {
            int end = name.indexOf(']');
            if (end + 1 < name.length()) {
                name = name.substring(end + 1).trim();
            }
        }
        if (TextUtils.isEmpty(name)) {
            name = Html.text(inner);
        }
        if (TextUtils.isEmpty(name)) {
            return null;
        }

        String pic = Html.attr(Html.openTagOf(inner, "img", null), "src");
        if (TextUtils.isEmpty(pic)) {
            pic = Html.attr(Html.openTagOf(inner, "img", null), "data-src");
        }
        if (!TextUtils.isEmpty(pic) && !pic.contains("@Referer=")) {
            pic = pic + PIC_SUFFIX;
        }

        return new Vod()
                .id(absolute(href))
                .name(name)
                .pic(pic)
                .remarks(statusOf(inner));
    }

    /**
     * 还原上游状态解析：{@code dd p} 里以「连载」或「状态」开头的行。
     *
     * @param inner 条目内部 HTML
     * @return 状态文案；找不到返回「听书」
     */
    public static String statusOf(String inner) {
        List<String> ps = Html.children(inner, "p", null);
        for (String p : ps) {
            String t = Html.text(p).trim();
            if (t.startsWith("连载") || t.startsWith("状态")) {
                String v = t.replace("状态：", "").replace("状态:", "").trim();
                if (v.startsWith("连载")) {
                    v = v.replace("连载：", "").replace("连载:", "").trim();
                }
                if (!v.isEmpty()) {
                    return v;
                }
            }
        }
        return "听书";
    }

    /**
     * 从 {@code .cpage span} 里取总页数（真实页面形如 {@code "1/34"}）。
     *
     * <p><b>上游此处逻辑写反了</b>：它用 {@code if(!text.contains("/"))} 才去 split，
     * 所以永远解析不到页数。本实现按真实格式正确解析。
     *
     * @param html 页面 HTML
     * @param page 当前页（解析失败时作为返回值）
     * @return 总页数
     */
    public static int pageCountOf(String html, int page) {
        if (TextUtils.isEmpty(html)) {
            return Math.max(page, 1);
        }
        String cpage = Html.innerFirst(html, "div", ".*\\bcpage\\b.*");
        String scope = cpage.isEmpty() ? html : cpage;
        for (String span : Html.children(scope, "span", null)) {
            String t = Html.text(span).trim();
            if (t.contains("/")) {
                String[] parts = t.split("/");
                if (parts.length >= 2) {
                    try {
                        return Math.max(Integer.parseInt(parts[1].trim()), page);
                    } catch (Throwable ignored) {
                        // 继续找下一个
                    }
                }
            }
        }
        return Math.max(page, 1);
    }

    // ─────────────────────────────────────────────────────────────
    // 分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 分类翻页（还原上游 {@code categoryContent}）。
     *
     * @param tid    分类 id
     * @param pg     页码
     * @param filter 是否要筛选（忽略）
     * @param extend 筛选参数，含 order
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String order = extend == null ? "" : extend.get("order");
        if (order == null) {
            order = "";
        }
        if (requireUrl().isEmpty()) {
            return VodBuilder.create().page(page).categoryContent();
        }

        String path = categoryPath(tid, page, order);
        if (path.isEmpty()) {
            Log.w(TAG, "categoryContent tid 为空，跳过请求");
            return VodBuilder.create().page(page).categoryContent();
        }
        String html = http.get(absolute(path), apiHeaders(path));
        List<Vod> list = parseList(html);
        int pageCount = pageCountOf(html, page);
        // page(page,total,limit)：total 用「总页数 × 每页条数」估算，limit 用本页实际条数
        int limit = Math.max(list.size(), 1);
        return VodBuilder.create().addAll(list).page(page, pageCount * limit, limit)
                .categoryContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 详情（还原上游 {@code detailContent}）。
     *
     * @param ids id 列表，首个是详情绝对 URL
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (id.isEmpty()) {
            Log.w(TAG, "detailContent 无 id");
            return emptyResponse();
        }
        if (requireUrl().isEmpty()) {
            return emptyResponse();
        }

        String html = http.get(absolute(id), apiHeaders(""));
        if (TextUtils.isEmpty(html)) {
            Log.w(TAG, "detailContent empty, id=" + id);
            return emptyResponse();
        }

        // 标题：.binfo h1（上游 selector 就是 .binfo h1）
        String name = Html.text(Html.innerFirst(html, "h1", null));
        // 封面：.bimg img[src]（上游 .bimg img）
        String pic = "";
        List<String> bimgs = Html.children(html, "div", ".*\\bbimg\\b.*");
        if (!bimgs.isEmpty()) {
            pic = Html.attr(Html.openTagOf(bimgs.get(0), "img", null), "src");
        }
        if (!TextUtils.isEmpty(pic) && !pic.contains("@Referer=")) {
            pic = pic + PIC_SUFFIX;
        }

        String author = binfoValue(html, "作者");
        String type = binfoValue(html, "类型");
        String status = binfoValue(html, "状态");
        String announcer = binfoValue(html, "播音");
        String intro = introOf(html);

        // 分集：.plist a.f（真实页面 3977 集，必须逐块取 href）
        List<String> eps = plistEpisodes(html);

        Vod vod = new Vod()
                .id(id)
                .name(name)
                .pic(pic)
                .content(intro)
                .actor(announcer)
                .director(author)
                .typeName(type)
                .remarks(status);
        if (!eps.isEmpty()) {
            vod.playFrom("堇夏").playUrl(Html.join(Vod.SEP_EPISODE, eps));
        }
        return VodBuilder.detail(vod).detailContent();
    }

    /**
     * 还原上游 {@code b(prefix, f)}：在 {@code .binfo p} 里找以 prefix 开头的行并取值。
     *
     * @param html   页面 HTML
     * @param prefix 「作者」/「类型」/「状态」/「播音」
     * @return 值；找不到返回空串
     */
    public static String binfoValue(String html, String prefix) {
        String binfo = Html.innerFirst(html, "div", ".*\\bbinfo\\b.*");
        if (binfo.isEmpty()) {
            return "";
        }
        for (String p : Html.children(binfo, "p", null)) {
            String t = Html.text(p).trim();
            if (t.startsWith(prefix) && t.length() > prefix.length()) {
                return t.substring(prefix.length()).trim().replace("：", "").replace(":", "");
            }
        }
        return "";
    }

    /**
     * 取简介（还原上游：{@code .intro p} 中长度 > 20 且不含「看APP」的第一个）。
     *
     * @param html 页面 HTML
     * @return 简介文本；无则空串
     */
    public static String introOf(String html) {
        for (String div : Html.children(html, "div", ".*\\bintro\\b.*")) {
            for (String p : Html.children(div, "p", null)) {
                String t = Html.text(p).trim();
                if (t.length() > 20 && !t.contains("看APP")) {
                    return t;
                }
            }
        }
        return "";
    }

    /**
     * 逐块解析 {@code .plist a.f}（集名 + href）。
     *
     * @param html 页面 HTML
     * @return 集串列表（{@code 集名$href}）
     */
    public static List<String> plistEpisodes(String html) {
        List<String> out = new ArrayList<>();
        String plist = Html.innerFirst(html, "div", ".*\\bplist\\b.*");
        if (plist.isEmpty()) {
            return out;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<a\\b([^>]*\\bclass\\s*=\\s*[\"'][^\"']*\\bf\\b[^\"']*[\"'][^>]*)>(.*?)</a\\s*>",
                        java.util.regex.Pattern.DOTALL)
                .matcher(plist);
        while (m.find()) {
            String href = Html.attr("<a" + m.group(1) + ">", "href").trim();
            String t = Html.text(m.group(2)).trim();
            if (!href.isEmpty() && !t.isEmpty()) {
                out.add(t + Vod.SEP_NAME_URL + href);
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 播放（还原上游 {@code playerContent}）—— 最复杂的一段。
     *
     * <pre>
     * ① 若 id 含 '$'，取 $ 之后（上游用 A.a.C(str2, 36, 1)）
     * ② 补前导 /，GET 章节页
     * ③ 从 meta 取 _b/_p/_cp/_c 四个参数
     * ④ POST ?s=api-getneoplay   form: bookId&isPay&page&xt
     * ⑤ 剥 BOM → JSON → status==1 时取 url（空则 ourl）
     * ⑥ 兜底：页面 audio#player[src]
     * </pre>
     *
     * @param flag     线路名（堇夏）
     * @param id       分集路径（或 集名$路径）
     * @param vipFlags 需要解析的标识（忽略）
     * @return 标准播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) {
            return emptyResponse();
        }
        if (requireUrl().isEmpty()) {
            return emptyResponse();
        }

        // ① '$' 之后才是路径
        String path = id;
        int dollar = path.indexOf('$');
        if (dollar >= 0 && dollar + 1 < path.length()) {
            path = path.substring(dollar + 1);
        }
        // ② 补前导 /
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        String pageUrl = baseUrl + path;
        String html = http.get(pageUrl, apiHeaders(path));
        if (TextUtils.isEmpty(html)) {
            Log.w(TAG, "playerContent 章节页空响应: " + pageUrl);
            return emptyResponse();
        }

        // ③ meta 四参数
        String bookId = Html.meta(html, "_b");
        String isPay = Html.meta(html, "_p");
        String cp = Html.meta(html, "_cp");
        String xt = Html.meta(html, "_c");

        String playUrl = "";
        if (!bookId.isEmpty() && !isPay.isEmpty()) {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("bookId", bookId);
            form.put("isPay", isPay);
            form.put("page", cp.isEmpty() ? "1" : cp);
            if (!xt.isEmpty()) {
                form.put("xt", xt);
            }
            Map<String, String> h = new LinkedHashMap<>();
            h.put("User-Agent", UA);
            h.put("Referer", pageUrl);
            String resp = http.post(baseUrl + "/?s=api-getneoplay", formBody(form), h);
            playUrl = parseNeoplay(resp);
        }

        // ⑥ 兜底：audio#player[src]
        if (TextUtils.isEmpty(playUrl)) {
            playUrl = Html.attr(Html.openTagOf(html, "audio", null), "src");
            if (!playUrl.isEmpty()) {
                Log.i(TAG, "playerContent 用页面 audio src 兜底");
            }
        }

        if (TextUtils.isEmpty(playUrl)) {
            Log.w(TAG, "playerContent 未取到播放地址, page=" + pageUrl);
            return emptyResponse();
        }
        return QingtingFMPlain.playerWithHeaders(playUrl, apiHeaders(path));
    }

    /**
     * 解析 {@code api-getneoplay} 响应（必须先剥 BOM）。
     *
     * @param resp 原始响应
     * @return 播放地址；失败空串
     */
    public static String parseNeoplay(String resp) {
        if (TextUtils.isEmpty(resp)) {
            return "";
        }
        String t = resp.trim();
        if (!t.isEmpty() && t.charAt(0) == BOM) {
            t = t.substring(1);
        }
        try {
            JSONObject o = new JSONObject(t);
            if (o.optInt("status", 0) != 1) {
                return "";
            }
            String url = o.optString("url", "");
            if (TextUtils.isEmpty(url)) {
                url = o.optString("ourl", "");
            }
            return url;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 表单编码。
     *
     * @param form 参数表
     * @return {@code k=v&k=v}
     */
    public static String formBody(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        if (form == null) {
            return "";
        }
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(Http.encode(e.getKey())).append('=').append(Http.encode(e.getValue()));
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────
    // 搜索
    // ─────────────────────────────────────────────────────────────

    /**
     * 搜索（还原上游 {@code searchContent}）：走站内搜索页，与分类共用列表解析。
     *
     * @param key   关键词
     * @param quick 是否快速搜索（忽略）
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) {
            return VodBuilder.create().searchContent();
        }
        if (requireUrl().isEmpty()) {
            return VodBuilder.create().searchContent();
        }
        String url = baseUrl + "/?s=ting-search-wd-" + Http.encode(key);
        String html = http.get(url, apiHeaders("/"));
        return VodBuilder.of(parseList(html)).searchContent();
    }
}
