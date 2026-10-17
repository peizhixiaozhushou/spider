package com.example.tvbox.plain.pan.sub;

import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.pan.PanPlain;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 4K指南 —— HTML 抓取型网盘源。
 *
 * <p>⚠️ <b>类名说明</b>：DEX 中原始类名是 {@code 4KZhinan}，首字符是数字，
 * 不是合法 Java 标识符。此处用 {@code K4Zhinan}，与上游 jadx 重命名
 * （{@code C4KZhinan}）等价。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  GET {base}/page/{page}?post_type=book&s={urlencode(keyword)}
 *  UA : Chrome/151 桌面 UA
 *  HTML 选择器（上游用 jsoup 封装）:
 *    列表  .posts-item.book-item.d-flex.style-book-v
 *    链接  div.item-header > div > a        → href
 *    标题  div.item-body.flex-fill > h3 > a → text
 *    封面  div.item-header > div > a > img  → data-src
 *    备注  div.item-body.flex-fill > div    → text
 * </pre>
 *
 * <p>本实现用<b>正则</b>近似 jsoup 选择器（避免引入 jsoup 依赖）。
 * 选择器结构与上游注释一致，便于对照。
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li>{@code homeContent} 的 4 个分类（{@code 4KZhinan.java:118-127}）：
 *       {@code /books/zuixin}→最新、{@code /books/top250}→top250、
 *       {@code /books/juji}→剧集、{@code /books/dianying}→电影。本类返回空响应。</li>
 *   <li>{@code categoryContent}（同上路径 + 分页）未实现。</li>
 *   <li><b>{@code detailContent} 的"夸克链接抽取"未实现</b>
 *       （{@code 4KZhinan.java:24-34} 的 {@code extractQuarkLinks}）：
 *       上游用的是<b>精确正则</b>
 *       {@code href\s*=\s*"(https://pan\.quark\.cn\/s\/[a-fA-F0-9]+)"}，
 *       从 {@code div.book-info ... div.site-go} 这个容器里抽链接，
 *       再交给 {@code detailContentVodPlayFrom/Url} 生成线路。
 *       <b>本类不做这一步</b>（详情交给 {@link PanPlain} 的识别 + resolver）。</li>
 *   <li>上游基地址是 {@code public final String a = "https://4kzn.cc"}（实例常量，
 *       {@code 4KZhinan.java:22}），搜索是
 *       {@code a + "/page/<page>?post_type=book&s=<enc>"}（{@code :35-40}）。
 *       本类的搜取 URL 形式不同（需核对 {@code buildSearchUrl}），
 *       且页码入口（三参 {@code searchContent}）未实现。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>{@code extractQuarkLinks} 只认夸克</b>（{@code 4KZhinan.java:24-34}）：
 *       正则把 {@code https://pan.quark.cn/s/<hex>} 写死 ⇒ 该站即使出现阿里/UC 链接也全部忽略；
 *       而且 {@code [a-fA-F0-9]+} <b>不允许</b>真实夸克分享 ID 里可能出现的非十六进制字符，
 *       匹配不上就静默返回空列表（表现为"详情页没有播放线路"）。
 *       → 本类不内联该正则，改由 {@link PanPlain} 的 9 种网盘识别统一处理（覆盖面更广）。
 *       断言：{@code K4ZhinanTest}。</li>
 *   <li>详情解析里用一串 {@code indexOf}/{@code substring} 手工切"IMDb: "、片名、年份等
 *       （{@code 4KZhinan.java:95-112}）：<b>每个字段都靠"找标记再截"</b>，
 *       页面文案改一个字就整段错位，且没有一处校验截取结果长度是否合理。
 *       → 本类用正则解析列表（{@code RE_ITEM/RE_HREF/RE_TITLE}），
 *       对整个匹配块做非贪婪捕获，比"位置切片"稳。</li>
 *   <li>{@code homeContent} 的分类用两个平行 {@code Arrays.asList}（路径表 + 名称表，
 *       {@code :120-121}）按下标对应 ⇒ 任一处增删一项就整体错位，
 *       且编译器不会发现。→ 本类不实现该分类（列在未覆盖），将来实现时用配对结构。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/4KZhinan.java} (142 行)
 */
public class K4ZhinanPlain extends PanPlain {

    private static final String TAG = "K4ZhinanPlain";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /**
     * 列表项容器（上游 jsoup 选择器 → 正则近似）。
     *
     * <p>用「下一个 posts-item 或文档尾」作为结束边界，而非第一个 </div> ——
     * 因为条目内部有嵌套 div，惰性匹配到 </div> 会截断。
     */
    private static final Pattern RE_ITEM = Pattern.compile(
            "<(?:div|article)[^>]*class=\"[^\"]*posts-item[^\"]*\"[^>]*>(.*?)(?=<(?:div|article)[^>]*class=\"[^\"]*posts-item|</body>|$)",
            Pattern.DOTALL);

    /** 链接 + 封面（同在一个 a/img 结构里） */
    private static final Pattern RE_HREF = Pattern.compile(
            "href=\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern RE_IMG = Pattern.compile(
            "data-src=\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern RE_TITLE = Pattern.compile(
            "<h3[^>]*>\\s*<a[^>]*>(.*?)</a>", Pattern.DOTALL);


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
     * 还原上游 {@code b(String keyword, String page)}。
     */
    protected String search(String key, String page) {
        if (TextUtils.isEmpty(key)) return VodBuilder.create().searchContent();

        String base = requireUrl();
        if (base.isEmpty()) {
            Log.w(TAG, "search: baseUrl empty (ext.url 缺失)");
            return VodBuilder.create().searchContent();
        }

        String url;
        try {
            url = base + "/page/" + page + "?post_type=book&s=" + URLEncoder.encode(key, "UTF-8");
        } catch (Exception e) {
            return VodBuilder.create().searchContent();
        }

        String html = fetchWith(url, Http.headersOf("User-Agent", UA));
        if (TextUtils.isEmpty(html)) {
            Log.w(TAG, "search: empty html");
            return VodBuilder.create().searchContent();
        }

        List<Vod> list = parse(html);
        Log.i(TAG, "search '" + key + "' page=" + page + " -> " + list.size());
        return VodBuilder.of(list).searchContent();
    }

    /** 解析列表页 —— 选择器语义对应上游 jsoup 链 */
    protected List<Vod> parse(String html) {
        List<Vod> out = new ArrayList<>();
        if (html == null) return out;

        Matcher items = RE_ITEM.matcher(html);
        while (items.find()) {
            String block = items.group(1);
            if (block == null) continue;

            String href = first(RE_HREF, block);
            String title = first(RE_TITLE, block);
            String img = first(RE_IMG, block);
            if (TextUtils.isEmpty(title) && TextUtils.isEmpty(href)) continue;

            out.add(new Vod()
                    .id(href)
                    .name(stripTags(title))
                    .pic(img)
                    .remarks(""));
        }
        return out;
    }

    private static String first(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    /** 去掉 HTML 标签与多余空白 */
    private static String stripTags(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return emptyResponse();
    }
}
