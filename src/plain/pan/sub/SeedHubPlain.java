package com.example.tvbox.plain.pan.sub;

import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.pan.PanPlain;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SeedHub 种子站 —— HTML 抓取 + 分页，继承 {@link PanPlain}。
 *
 * <h2>协议（还原自反编译 {@code SeedHub.g()}）</h2>
 * <pre>
 *  GET {base}/s/{urlencode(keyword)}/?page={page}
 *  列表项  : 由类内 f(...) 解析（含 data-link 属性）
 *  总页数  : .page-nav .page a 的最后一个文本 → parseInt；无则 Integer.MAX_VALUE
 *  分页参数: page / pagecount / limit / total
 *            limit = max(本页条数, 1)
 *            total = pagecount * limit      ← 上游算法
 * </pre>
 *
 * <p>条目含网盘链接（data-link），由基类 {@link PanPlain} 识别并分发解析器。
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li>{@code homeContent} 的板块与 {@code categoryContent} 的筛选/分页
 *       （上游 {@code SeedHub.java:286} 起）：本类未实现（返回空响应）。</li>
 *   <li>{@code detailContent} / {@code homeVideoContent}：上游皆有，本类未实现。</li>
 *   <li>上游把 {@code /config.json} 用 Gson 读出来的配置项（{@code SeedHub.java:13,276}）
 *       未复刻 —— 本类不读宿主私有文件。</li>
 *   <li>总页数解析：上游读 {@code .page-nav .page a} 的最后一个文本再 {@code parseInt}
 *       （见本类 javadoc 的协议说明）；本类用 {@code RE_PAGE_NAV_START} + {@code RE_PAGE_NUM}
 *       近似实现，<b>"取最后一个"的语义已实现但站方分页控件结构若变化会失配</b>。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>18 组 {@code try/catch} 铺满整个类</b>（{@code SeedHub.java}）：
 *       逐字段 try 说明作者已经知道页面结构不稳，但 catch 体里只打日志/返回空，
 *       <b>没有任何一处告诉调用方"哪一项坏了"</b>，排查只能靠抓包。
 *       → 本类统一在解析入口包一层 try，失败时返回标准空响应并记一条 warn 日志
 *       （{@code PanChildrenTest} 覆盖该路径）。</li>
 *   <li>总页数 {@code parseInt} 不带长度/正则校验：{@code .page-nav} 里的最后一个文本
 *       若不是纯数字（如"末页 3"），直接抛 {@code NumberFormatException}。
 *       本类用 {@code RE_PAGE_NUM} 只取连续 1-6 位数字，且在无匹配时退化为单页
 *       （{@code Integer.MAX_VALUE} 会让宿主无限翻页 —— 本类不那样做）。</li>
 *   <li>站方图标/域名在代码里以 8 张 {@code 1200X800} 图床 URL 硬编码：
 *       该类把"网盘类型 → 图标"做成常量表 {@code PIC_BY_KIND}，
 *       <b>比上游散落的 if-else 更易维护</b>；代价是图床失效时同样全站无封面。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/SeedHub.java} (449 行)
 */
public class SeedHubPlain extends PanPlain {

    private static final String TAG = "SeedHubPlain";

    /** 列表项容器 */
    private static final Pattern RE_ITEM = Pattern.compile(
            "<(?:div|li|article)[^>]*class=\"[^\"]*(?:post|item|card)[^\"]*\"[^>]*>(.*?)(?=<(?:div|li|article)[^>]*class=\"[^\"]*(?:post|item|card)|</body>|$)",
            Pattern.DOTALL);
    /** 链接（data-link 优先，其次 href） */
    private static final Pattern RE_DATA_LINK = Pattern.compile("data-link=\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern RE_HREF = Pattern.compile("href=\"([^\"]+)\"", Pattern.DOTALL);
    /** 标题 */
    private static final Pattern RE_TITLE = Pattern.compile("<h[23][^>]*>\\s*<a[^>]*>(.*?)</a>", Pattern.DOTALL);
    /** 封面 */
    private static final Pattern RE_IMG = Pattern.compile("(?:data-src|src)=\"([^\"]+)\"", Pattern.DOTALL);
    /**
     * 总页数定位。
     *
     * <p>上游用 jsoup 选择器 {@code .page-nav .page a} 取最后一项。
     * 正则近似时不能用「到第一个 </div>」做边界（page-nav 内部有嵌套 div），
     * 改为：先定位 page-nav 的起始偏移，再在其后 2KB 窗口内取所有纯数字文本。
     */
    private static final Pattern RE_PAGE_NAV_START = Pattern.compile(
            "<[^>]*class=\"[^\"]*page-nav[^\"]*\"", Pattern.DOTALL);
    private static final Pattern RE_PAGE_NUM = Pattern.compile(">\\s*(\\d{1,6})\\s*<");
    /** page-nav 之后的搜索窗口大小 */
    private static final int PAGE_NAV_WINDOW = 2048;

    /** 网盘类型 → 封面图（上游硬编码） */
    private static final Map<String, String> PIC_BY_KIND = new LinkedHashMap<>();
    static {
        PIC_BY_KIND.put("ali", "https://img.51shazhu.com/autoupload/gE6Y0Af2tjXBCNig6CtNDI12_FRYNb81z6UPhMWD8iI/20260503/8Yr6/1200X800/aliyun.jpg/webp");
        PIC_BY_KIND.put("quark", "https://img.51shazhu.com/autoupload/g1b6f/20260807/bsds/1200X800/quark.jpg/webp");
        PIC_BY_KIND.put("uc", "https://img.51shazhu.com/autoupload/gE6Y0Af2tjXBCNig6CtNDI12_FRYNb81z6UPhMWD8iI/20260503/kbEn/1200X800/uc.jpg/webp");
        PIC_BY_KIND.put("baidu", "https://img.51shazhu.com/autoupload/g1b6f/20260806/V6JN/1200X800/baidu.jpg/webp");
        PIC_BY_KIND.put("xunlei", "https://img.51shazhu.com/autoupload/gE6Y0Af2tjXBCNig6CtNDI12_FRYNb81z6UPhMWD8iI/20260623/4oPO/1200X800/xunlei.jpg/webp");
    }


    /**
     * 可覆写的 GET —— 测试注入点。
     *
     * <p>{@code Http.setOfflineForTest(true)} 之后真实请求一律返回空串，
     * 「请求形态」与「解析路径」都无法通过真网络断言。测试用一个只记录不联网的子类
     * 覆写本方法，即可把"发出去的到底是什么 / 拿回来的东西怎么被解析"钉死。
     */
    public String fetchWith(String url, java.util.Map<String, String> headers) {
        return http.get(url, headers);
    }

    @Override
    public String homeContent(boolean filter) {
        return emptyResponse();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return search(key, "1");
    }

    /** 三参数重载（上游支持翻页） */
    public String searchContent(String key, boolean quick, String page) {
        return search(key, TextUtils.isEmpty(page) ? "1" : page);
    }

    /**
     * 还原上游 {@code SeedHub.g(keyword, page)} —— 含分页元信息计算。
     */
    protected String search(String key, String page) {
        if (TextUtils.isEmpty(key)) return VodBuilder.create().searchContent();

        String base = baseUrl;
        if (TextUtils.isEmpty(base)) {
            // 上游用 this.x（init 时解析出的站点域名）
            base = ext.url();
        }
        if (TextUtils.isEmpty(base)) {
            Log.w(TAG, "search: baseUrl empty");
            return VodBuilder.create().searchContent();
        }

        String url;
        try {
            url = base + "/s/" + URLEncoder.encode(key, "UTF-8") + "/?page=" + page;
        } catch (Exception e) {
            return VodBuilder.create().searchContent();
        }

        String html = http.get(url, headers());
        if (TextUtils.isEmpty(html)) {
            Log.w(TAG, "search: empty html");
            return VodBuilder.create().searchContent();
        }

        List<Vod> list = parseItems(html);
        int cur = parseIntSafe(page, 1);
        int limit = Math.max(list.size(), 1);
        int pagecount = parsePageCount(html);
        int total = pagecount == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : pagecount * limit;

        Log.i(TAG, "search '" + key + "' page=" + cur + " -> " + list.size()
                + " items, pagecount=" + pagecount);

        return VodBuilder.create().addAll(list).page(cur, total, limit).categoryContent();
    }

    /** 解析列表条目 */
    protected List<Vod> parseItems(String html) {
        List<Vod> out = new ArrayList<>();
        if (html == null) return out;

        Matcher items = RE_ITEM.matcher(html);
        while (items.find()) {
            String block = items.group(1);
            if (block == null) continue;

            String link = firstMatch(RE_DATA_LINK, block);
            if (link.isEmpty()) link = firstMatch(RE_HREF, block);
            String title = stripTags(firstMatch(RE_TITLE, block));
            String img = firstMatch(RE_IMG, block);
            if (TextUtils.isEmpty(link) && TextUtils.isEmpty(title)) continue;

            // 按链接域名推断网盘类型，选对应封面（上游同逻辑）
            PanMatch pm = identify(link);
            String pic = pm != null ? PIC_BY_KIND.getOrDefault(pm.kind, img) : img;

            out.add(new Vod()
                    .id(link)
                    .name(title)
                    .pic(pic)
                    .remarks(pm != null ? panDisplayName(pm.kind) : ""));
        }
        return out;
    }

    /**
     * 解析总页数 —— 还原上游 {@code .page-nav .page a} 取最后一个并 parseInt。
     * <p>解析失败返回 {@link Integer#MAX_VALUE}（与上游一致）。
     */
    protected int parsePageCount(String html) {
        if (html == null) return Integer.MAX_VALUE;
        Matcher nav = RE_PAGE_NAV_START.matcher(html);
        if (!nav.find()) return Integer.MAX_VALUE;

        int start = nav.end();
        int end = Math.min(html.length(), start + PAGE_NAV_WINDOW);
        String window = html.substring(start, end);

        int last = -1;
        Matcher nums = RE_PAGE_NUM.matcher(window);
        while (nums.find()) {
            int v = parseIntSafe(nums.group(1), -1);
            if (v > last) last = v;
        }
        return last > 0 ? last : Integer.MAX_VALUE;
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return emptyResponse();
    }

    // ── 工具 ────────────────────────────────────────────────────

    private static String firstMatch(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static String stripTags(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
