package com.example.tvbox.plain.pan.sub;

import com.example.tvbox.common.Dom;
import com.example.tvbox.common.Vod;
import com.example.tvbox.plain.pan.PanPlain;

import android.text.TextUtils;

/**
 * 网盘分享搜索源（苹果CMS，HJ 模板）—— 继承 {@link PanHtmlPlain}（→ {@link PanPlain}）。
 *
 * <h2>职责划分</h2>
 * <ul>
 *   <li><b>本类</b>：只做搜索，返回含网盘分享链接的条目</li>
 *   <li><b>基类 PanHtmlPlain</b>：GET HTML 页 → 按选择器抽条目（含域名热更、封面清洗）</li>
 *   <li><b>基类 PanPlain</b>：识别链接归属网盘并分发到解析器</li>
 * </ul>
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  搜索: GET &lt;site&gt;/index.php/vod/search/wd/&lt;urlencode(keyword)&gt;.html
 *  站型: 苹果CMS 的 <b>HTML</b> 搜索结果页
 *  条目: ".module-card-item, .module-item"（<b>两套模板并列</b>，见下）
 *    - 链接: .module-card-item-poster[href]
 *    - 标题: .module-card-item-title a 的文本
 *    - 封面: .module-item-pic img[data-original]
 *    - 备注: .module-item-note 的文本
 * </pre>
 *
 * <h2>为什么条目选择器是两个并列</h2>
 * 上游 {@code PanWebShareHJ.java:94} 写的是
 * {@code ".module-card-item, .module-item"} —— 站方模板换过，作者用"或"兜住两代结构。
 * {@link PanHtmlPlain#selectItems(String)} 会把逗号切开依次取并集并<b>按 outerHtml 去重</b>
 * （一个元素可能同时带这两个类，不去重就会出重复条目）。
 *
 * <h2>与同族三份拷贝的关系</h2>
 * 与 {@code PanWebShare} / {@code PanWebShareLL} 是同站三份拷贝
 * （上游差异仅基地址探测与选择器集合），本工程保留三个类名、
 * 共享 {@link PanHtmlPlain} 的搜索骨架。
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li>{@code homeContent} / {@code categoryContent}（上游 PanWebShareHJ:248 起）未实现：
 *       每个站的首页/分类各有自己一套选择器，本类只承诺「搜索」这一条路径。</li>
 *   <li>{@code detailContent}（上游 PanWebShareHJ:120 起）未实现，交给 {@link PanPlain}
 *       的识别 + resolver。</li>
 *   <li>分页不抓全：苹果CMS 搜索页多数只给第一页，上游也只传一个页码参数，
 *       {@code .page} 控件不解析。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>★ 本工程早期版本解析错了站型（已修）</b>：曾用 {@code new JSONObject(resp)}
 *       解析这个<b>HTML 页</b> ⇒ 对 HTML 必然抛异常，真机上永远返回空结果。
 *       现改为 {@code Dom.select} + 上游同款选择器。
 *       断言：{@code ★ PanWebShareHJPlain 真实 HTML fixture 能解析出条目}。</li>
 *   <li><b>域名靠一次 {@code init} 里的字符串 trim 探测，无任何校验</b>
 *       （{@code PanWebShareHJ:280} 的 {@code this.f = strTrim}）：
 *       探测失败就把半截串当域名，之后 URL 与 Referer 一起坏掉。
 *       → 本类域名走 {@link PanHtmlPlain#site()}（含 {@code ://} 与主机名校验），
 *       且 ext 可下发、{@code setSiteForTest} 可注入。
 *       断言：{@code ★ 域名热更}、{@code ★ 非法域名被拒}。</li>
 *   <li><b>封面靠 {@code lastIndexOf("http")} 硬切</b>（{@code PanWebShareHJ:99}）：
 *       {@code data-src} 若是 {@code /lazy.php?u=https://img...} 能切对，
 *       但串里出现第二个 {@code http} 就切错。
 *       → {@link PanHtmlPlain#pickPic(String)} 切完校验"像不像 URL"。
 *       断言：{@code ★ 封面懒加载串被正确切出}。</li>
 *   <li><b>选择器不命中时仍 {@code add} 空条目</b>：上游 {@code o0(...).a("href")} 直接链式取值，
 *       选择器失配就拿空串，却照样加进列表 ⇒ 出现"没名字没链接"的幽灵条目。
 *       → {@link PanHtmlPlain#parse(String)} 丢掉落空条目。
 *       断言：{@code ★ 空选择器结果不产生幽灵条目}。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/PanWebShareHJ.java}（321 行）——
 * 搜索 URL 模板取自 `PanWebShareHJ.java:94`，条目选择器与字段选择器取自 `PanWebShareHJ.java:94-105`。
 */
public class PanWebShareHJPlain extends PanHtmlPlain {

    private static final String TAG = "PanWebShareHJPlain";

    /** 兜底域名（可被 ext 的 site/url/host 或 {@link #setSiteForTest} 覆盖） */
    private static final String DEFAULT_SITE = "https://www.panwbs.com";

    @Override
    protected String defaultSite() {
        return DEFAULT_SITE;
    }

    @Override
    protected String tagOf() {
        return TAG;
    }

    @Override
    protected String searchUrl(String encodedKey, int page) {
        // 等价 PanWebShareHJ.java:94 —— 与 PanWebShare 同一个搜索路径
        return site() + "/index.php/vod/search/wd/" + encodedKey + ".html";
    }

    @Override
    public String itemSelector() {
        // 等价 PanWebShareHJ:94
        return ".module-card-item, .module-item";
    }

    @Override
    protected Vod parseItem(Dom.El el) {
        if (el == null) return null;
        String href = firstAttr(el, new String[]{".module-card-item-poster", "a"}, "href");
        String name = firstText(el, new String[]{".module-card-item-title a", ".module-item-title"});
        String pic = firstAttr(el, new String[]{".module-item-pic img", "img"}, "data-original");
        String note = firstText(el, new String[]{".module-item-note"});
        if (TextUtils.isEmpty(href) && TextUtils.isEmpty(name)) return null;
        return new Vod()
                .id(absUrl(href))
                .name(stripTags(name))
                .pic(absPic(pic))
                .remarks(stripTags(note));
    }
}
