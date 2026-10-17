package com.example.tvbox.plain.pan.sub;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.Dom;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.pan.PanPlain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「HTML 列表页」型网盘搜索源的公共基类 —— 把 {@link PanPlain} 家族里
 * 「GET 一个 HTML 页 → 按 CSS 选择器抽条目」这一族收敛到一处。
 *
 * <h2>为什么需要这个基类</h2>
 * 上游有 5 个子源解析的是 <b>HTML 页面</b>（苹果CMS / DedeCMS 的搜索结果页），
 * 不是 JSON 接口：
 * <pre>
 *   PanWebShare    ae/decompiled/PanWebShare.java:85    .module-search-item
 *   PanWebShareHJ  ae/decompiled/PanWebShareHJ.java:94  .module-card-item, .module-item
 *   PanWebShareLL  ae/decompiled/PanWebShareLL.java:67  .module-search-item
 *   Wogg           ae/decompiled/Wogg.java:87           .module-search-item
 *   RenRen         ae/decompiled/RenRen.java:37         #movielist li.pure-g.shadow
 * </pre>
 * 本工程早期版本把这 5 个类写成了「JSON 接口 + 硬编码域名」，与上游的站型不符
 * ⇒ <b>真机上必然返回空结果</b>（见各类 javadoc 的"未覆盖的分支"）。
 * 本基类把它们改回 HTML 解析，并把域名做成<b>可热更</b>（上游的 {@code this.f}
 * 就是在 {@code init} 时探测/写入后随时可用，见 {@code PanWebShare.java:268}
 * 等处的 {@code this.f = strTrim}）。
 *
 * <h2>子类只需回答 4 个问题</h2>
 * <ol>
 *   <li>{@link #defaultSite()} —— 站点基地址（极可能是"半死"的，可被 ext / setSiteForTest 覆盖）</li>
 *   <li>{@link #searchUrl(String, int)} —— 搜索页 URL（不同 CMS 的路径模板差别很大）</li>
 *   <li>{@link #itemSelector()} —— 条目容器的 CSS 选择器（<b>允许逗号并列多套模板</b>）</li>
 *   <li>{@link #parseItem(Dom.El)} —— 从单个条目元素抽一条 {@link Vod}</li>
 * </ol>
 *
 * <h2>域名热更的三种来源（优先级从高到低）</h2>
 * <ol>
 *   <li>{@link #setSiteForTest(String)} —— 测试注入</li>
 *   <li>{@code ext} 里的 {@code site} / {@code url} / {@code host} 字段（宿主下发）</li>
 *   <li>{@link #defaultSite()} —— 代码里的兜底常量</li>
 * </ol>
 * 因为 {@link #searchUrl(String, int)} 是在<b>每次调用时</b>现读 {@link #site()}，
 * 所以域名一变请求 URL 立刻跟着变（断言：{@code ★ 域名热更}）。
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>分页只在 {@link #pageForSearch()} 这一处</b>：苹果CMS 的搜索页多数只给第一页，
 *       上游也只有 {@code str2} 一个页码参数（{@code Wogg.java:87} 用、其余三个不用）。
 *       本基类把页码做成可注入（{@link #setPageForTest(int)}），但<b>不实现"翻页抓全"</b>
 *       —— 那需要解析 {@code .page} 控件，上游也没做。</li>
 *   <li><b>不解析分类/首页</b>：上游的 {@code homeContent}/{@code categoryContent}
 *       各站差异极大（每个站一套选择器），本基类只管搜索这一条路径。</li>
 *   <li><b>{@code Referer} 用 {@link #site()} 现拼</b>：上游是把 {@code this.f + "/"}
 *       写进 header（{@code PanWebShare.java:58}），若 {@code site()} 为空则退化成 {@code "/"}。
 *       本基类在 {@code site()} 为空时<b>不发请求</b>（返回空响应），避免发出畸形 Referer。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>域名靠一次 {@code init} 里的字符串 trim 探测，无校验</b>
 *       （{@code PanWebShare.java:268} / {@code PanWebShareHJ.java:280} /
 *       {@code PanWebShareLL.java:229} / {@code Wogg.java:214}）：
 *       探测失败会把 {@code this.f} 置成半个串，之后所有请求与 Referer 一起坏掉，
 *       而代码里没有任何地方检查它是不是合法 URL。
 *       → 本基类只接受"含 {@code ://} 且有主机名"的字符串，否则回落默认值。</li>
 *   <li><b>封面字段靠 {@code lastIndexOf("http")} 硬切</b>
 *       （{@code PanWebShare.java:92} / {@code Wogg.java:93}）：
 *       {@code data-src} 形如 {@code /lazy.php?u=https://...} 时能切对，
 *       但串里出现第二个 {@code http}（{@code ?url=http://a&next=http://b}）就切错。
 *       → 本基类用 {@link #pickPic(String)}，<b>只切一次且校验切出来的像不像 URL</b>。</li>
 *   <li><b>空选择器结果不判空</b>：上游 {@code o0(...).a("href")} 直接链式取值，
 *       选择器不命中时行为取决于 DOM 库（多数是返回空串），字段全空却仍然 {@code add}
 *       一条空 {@link Vod} 进去 ⇒ 列表里出现"没有名字没有链接"的幽灵条目。
 *       → 本基类在 {@link #parse(String)} 里丢掉落空的条目。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/PanWebShare.java}、{@code PanWebShareHJ.java}、
 * {@code PanWebShareLL.java}、{@code Wogg.java}、{@code RenRen.java}；
 * 选择器与 URL 模板逐字取自上述四处的搜索方法。
 */
public abstract class PanHtmlPlain extends PanApiPlain {

    /** 搜索页码（苹果CMS 搜索页一般只用第 1 页） */
    private int page = 1;

    // ─────────────────────────────────────────────────────────────
    // 子类要回答的 4 个问题
    // ─────────────────────────────────────────────────────────────

    /**
     * 搜索页 URL。
     *
     * @param key  关键字（已由调用方编码，子类直接用）
     * @param page 页码（从 1 开始；不支持的站直接忽略）
     */
    protected abstract String searchUrl(String key, int page);

    /** 条目容器的选择器；<b>可用逗号并列多套模板</b>（如 {@code ".module-card-item, .module-item"}） */
    public abstract String itemSelector();

    /** 从单个条目元素解析一条；返回 {@code null} 表示丢弃 */
    protected abstract Vod parseItem(Dom.El el);

    // ─────────────────────────────────────────────────────────────
    // 域名（可热更）
    // ─────────────────────────────────────────────────────────────

    /** 当前页码（供断言） */
    public int page() {
        return page;
    }

    /** 测试注入页码 */
    public void setPageForTest(int p) {
        page = p < 1 ? 1 : p;
    }

    // ─────────────────────────────────────────────────────────────
    // init / 搜索模板
    // ─────────────────────────────────────────────────────────────

    @Override
    public String buildSearchUrl(String key) {
        return searchUrl(encode(key), page);
    }

    @Override
    public String homeContent(boolean filter) {
        // 纯搜索型网盘源，无首页分类
        return emptyResponse();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        // 播放由基类 detailContent + 网盘解析器处理
        return emptyResponse();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return VodBuilder.create().searchContent();

        if (siteMissing()) {
            // 没有合法域名就别发请求（否则 Referer / URL 全是畸形串）
            return VodBuilder.create().searchContent();
        }
        // ★ 多域名失败切换：真配置的 site 数组是**故障切换列表**，
        //   列出的域名里有死有活（实测 https://tv.yydsys.top 超时 14.9s、
        //   https://www.hjzhencai.top 连接被拒，而同列表的第二个域名 HTTP 200）。
        //
        //   只在**请求层面失败**时换域名（响应体为空 = 拿不到东西）；
        //   "页面拿到了但没有匹配条目"**不换** —— 那是"这个词搜不到"，不是"站点挂了"。
        //   换过的域名由 PanApiPlain 记住（deadSites），所以不会每个请求都重头试一遍死域名。
        //
        //   次数上界 = 候选域名个数（每个域名最多试一次），不会出现无限重试。
        int tries = Math.max(1, siteCandidates().size());
        String html = "";
        for (int attempt = 0; attempt < tries; attempt++) {
            if (siteMissing()) {
                Log.w(tagOf(), "search: no usable site, give up");
                return VodBuilder.create().searchContent();
            }
            String url = searchUrl(encode(key), page);
            if (TextUtils.isEmpty(url) || !url.startsWith("http")) {
                Log.w(tagOf(), "search: bad url for key=" + key);
                return VodBuilder.create().searchContent();
            }
            html = fetchWith(url, requestHeaders());
            if (!TextUtils.isEmpty(html)) break;

            // 请求失败 → 换下一个候选域名再试（只有一个候选时就不白费力气了）
            if (siteCandidates().size() <= 1) {
                Log.w(tagOf(), "search: empty response and only one candidate");
                return VodBuilder.create().searchContent();
            }
            Log.w(tagOf(), "search: empty response from " + site() + ", switching candidate");
            markCurrentSiteDead();
        }
        if (TextUtils.isEmpty(html)) {
            Log.w(tagOf(), "search: all candidates failed");
            return VodBuilder.create().searchContent();
        }

        List<Vod> list = parse(html);
        Log.i(tagOf(), "search '" + key + "' -> " + list.size() + " items");
        return VodBuilder.of(list).searchContent();
    }

    /** 把 HTML 解析成条目列表（子类可覆写做特殊处理） */
    protected List<Vod> parse(String html) {
        List<Vod> out = new ArrayList<>();
        for (Dom.El el : selectItems(html)) {
            Vod v;
            try {
                v = parseItem(el);
            } catch (Exception e) {
                Log.w(tagOf(), "parse item failed: " + e);
                continue;
            }
            if (v == null) continue;
            // 丢掉"既没名字也没链接"的幽灵条目（见缺陷 3）
            if (TextUtils.isEmpty(safe(v.vodName)) && TextUtils.isEmpty(safe(v.vodId))) continue;
            out.add(v);
        }
        return out;
    }

    /**
     * 按 {@link #itemSelector()} 选条目。
     * <p>逗号并列的多套模板会**依次**取并集，并按 {@code outerHtml} 去重
     * （苹果CMS 里一个元素可能同时带 {@code module-card-item} 与 {@code module-item} 两个类，
     * 两个选择器都会命中它 —— 不去重就会出重复条目）。
     */
    protected List<Dom.El> selectItems(String html) {
        List<Dom.El> out = new ArrayList<>();
        String sel = itemSelector();
        if (TextUtils.isEmpty(sel)) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (String part : sel.split(",")) {
            String one = part.trim();
            if (one.isEmpty()) continue;
            for (Dom.El el : Dom.select(html, one)) {
                if (seen.add(el.outerHtml())) {
                    out.add(el);
                }
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 公共小工具
    // ─────────────────────────────────────────────────────────────

    /**
     * 封面字段清洗 —— 还原上游 {@code lastIndexOf("http")} 的意图，但加校验。
     *
     * <p>上游（{@code PanWebShare.java:92}、{@code Wogg.java:93}）：
     * {@code data-src} 可能是懒加载代理串（{@code /lazy.php?u=https://img...}），
     * 用"找最后一个 http 再截"拿到真图。本实现的差别：<b>切完要看起来像 URL 才用</b>，
     * 避免 {@code ?url=http://a&next=http://b} 这种串切出半截。
     */
    protected String pickPic(String raw) {
        String s = safe(raw).trim();
        if (s.isEmpty()) return "";
        int first = s.indexOf("http");
        int last = s.lastIndexOf("http");
        // 没有 http 前缀就原样返回 —— 协议相对地址（//img/x.jpg）与站内相对路径都属此类，
        // 上游这里的逻辑也是"找不到就保留原值"（PanWebShare.java:92 的 if >= 0 分支）。
        if (first < 0) return s;
        // 上游无条件从最后一个 "http" 处切（即使只有一处 —— 那正是
        // "/lazy.php?u=https://img/x.jpg" 这种代理串的常见形态，必须切）。
        String tail = s.substring(last);
        int s2 = tail.indexOf("://");
        return s2 > 0 && s2 + 3 < tail.length() ? tail : s;
    }

    /**
     * 封面：先按 {@link #pickPic(String)} 剥掉懒加载代理，再把相对地址补成绝对。
     *
     * <p>上游只做了第一步，第二步没做 —— 站方如果给的是 {@code /up/1.jpg}
     * 这种站内相对路径，上游会把相对路径原样塞进 {@code vod_pic}，
     * 宿主拿到后无法加载 ⇒ <b>整页封面全裂</b>（{@code RenRen.java:40} 就是这样）。
     * 这一步是修正，不是还原。
     */
    protected String absPic(String raw) {
        return absUrl(pickPic(raw));
    }

    /** 依次尝试多个属性，取第一个非空 */
    protected static String firstAttr(Dom.El el, String[] selectors, String attr) {
        if (el == null) return "";
        for (String sel : selectors) {
            String v = el.selectAttr(sel, attr, "");
            if (!TextUtils.isEmpty(v)) return v;
        }
        return "";
    }

    /** 依次尝试多个选择器的文本，取第一个非空 */
    protected static String firstText(Dom.El el, String[] selectors) {
        if (el == null) return "";
        for (String sel : selectors) {
            String v = el.selectText(sel, "");
            if (!TextUtils.isEmpty(v)) return v;
        }
        return "";
    }

    /** 去掉残留的 HTML 标签（上游 {@code .i()} / {@code .v0()} 的等价语义） */
    protected static String stripTags(String s) {
        return safe(s).replaceAll("<[^>]*>", "").trim();
    }
}
