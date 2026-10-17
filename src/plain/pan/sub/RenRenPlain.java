package com.example.tvbox.plain.pan.sub;

import com.example.tvbox.common.Dom;
import com.example.tvbox.common.Vod;
import com.example.tvbox.plain.pan.PanPlain;

import android.text.TextUtils;

/**
 * 人人 —— 网盘搜索源，继承 {@link PanHtmlPlain}（→ {@link PanPlain}）。
 *
 * <h2>职责划分</h2>
 * <ul>
 *   <li><b>本类</b>：只做搜索，返回含网盘分享链接的条目</li>
 *   <li><b>基类 PanHtmlPlain</b>：GET HTML 页 → 按选择器抽条目（含域名热更、封面清洗）</li>
 *   <li><b>基类 PanPlain</b>：识别链接归属网盘（阿里/夸克/UC/…）并分发到解析器</li>
 * </ul>
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  搜索: GET &lt;site&gt;/plus/search.php?q=&lt;urlencode(keyword)&gt;
 *  站型: DedeCMS 的 <b>HTML</b> 搜索结果页（不是 JSON 接口）
 *  条目: #movielist li.pure-g.shadow
 *    - 链接: a[href]
 *    - 封面: img[data-original]，回退 img[src]
 *    - 标题: h2 &gt; a[title]，再从 《》 里截片名
 *  等价上游: ae/decompiled/RenRen.java:37(条目) / :40(封面) / :44(标题)
 * </pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li>{@code homeContent} / {@code categoryContent}（上游 {@code RenRen.java:26,130} 皆有，
 *       各有自己的一套选择器）未实现 —— 本类只承诺「搜索」这一条路径。</li>
 *   <li>{@code detailContent}（{@code RenRen.java:54} 起）未实现，交给 {@link PanPlain}
 *       的识别 + resolver；上游那里是把 {@code list.get(0)} 再解析一次列表页（{@code :171}），
 *       属于"从搜索结果里点进去看详情"的另一条路径。</li>
 *   <li>分页不抓全：上游 {@code searchContent}（{@code :156} 起）只请求第一页，
 *       {@code .page} 控件不解析。本类一致。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>★ 本工程早期版本解析错了站型（已修）</b>：曾用
 *       {@code new JSONObject(resp)} 解析这个<b>HTML 页</b>，对 HTML 必然抛异常
 *       ⇒ 真机上永远返回空结果。现改为 {@code Dom.select} + 上游同款选择器。
 *       断言：{@code ★ RenRen 真实 HTML fixture 能解析出条目}。</li>
 *   <li><b>用 {@code 《》} 定位片名会丢条目</b>（{@code RenRen.java:44}）：
 *       {@code indexOf("《")} 与 {@code indexOf("》")} 都 {@code != -1} 才取，否则置空串，
 *       而空串又让 {@code if (!TextUtils.isEmpty(strReplace))} 丢掉整条
 *       ⇒ 标题里没有书名号的条目被静默丢弃。
 *       → 本类按「书名号内有内容就用它，否则用 title 原值」处理，<b>不丢条目</b>。
 *       断言：{@code ★ 无书名号的标题不被丢弃}。</li>
 *   <li>把条目构造交给 {@code merge.k.b(...)} 静态方法（{@code :47}），
 *       与其它子源不统一 ⇒ 同一工程存在多套条目构造路径。
 *       → 本类统一用 {@link Vod} / {@code VodBuilder}。</li>
 *   <li>域名硬编码在 {@code API} 常量里（本工程早期版本）：上游的站址是可变的
 *       （同族的苹果CMS 站都是 {@code this.f} 热更）。→ 本类域名走
 *       {@link PanHtmlPlain#site()}（ext 可下发，{@code setSiteForTest} 可注入）。
 *       断言：{@code ★ 域名热更}。</li>
 *   <li>{@code <font color='red'>} 搜索高亮标签上游用 {@code replace} 清了两次
 *       （{@code :44}），只处理了单引号一种写法；本类用 {@link #stripTags} 统一去标签，
 *       双引号/大写写法一并覆盖。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/RenRen.java}（187 行）
 */
public class RenRenPlain extends PanHtmlPlain {

    private static final String TAG = "RenRenPlain";

    /** 兜底域名（可被 ext / setSiteForTest 覆盖） */
    private static final String DEFAULT_SITE = "https://www.rrdynb.com";

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
        // 等价 RenRen.java:161 —— DedeCMS 的搜索入口
        return site() + "/plus/search.php?q=" + encodedKey;
    }

    @Override
    public String itemSelector() {
        // 等价 RenRen.java:37
        return "#movielist li.pure-g.shadow";
    }

    @Override
    protected Vod parseItem(Dom.El el) {
        if (el == null) return null;
        String href = el.selectAttr("a", "href", "");
        String titleAttr = el.selectAttr("h2 > a", "title", "");
        if (TextUtils.isEmpty(titleAttr)) {
            titleAttr = el.selectText("h2 > a", "");
        }
        // 封面：data-original 优先，回退 src（等价 RenRen.java:40-43）
        String pic = el.selectAttr("img", "data-original", "");
        if (TextUtils.isEmpty(pic)) {
            pic = el.selectAttr("img", "src", "");
        }
        String name = pickName(titleAttr);
        if (TextUtils.isEmpty(href) && TextUtils.isEmpty(name)) return null;
        return new Vod()
                .id(absUrl(href))
                .name(name)
                .pic(absPic(pic))
                .remarks("");
    }

    /**
     * 从标题里取片名。
     *
     * <p>上游（{@code RenRen.java:44}）只认 {@code 《…》}，取不到就把整条丢掉；
     * 本实现取不到时<b>回退用原标题</b>（去掉高亮标签），避免"搜得到但列表是空的"。
     */
    protected String pickName(String title) {
        String t = stripTags(title);
        if (TextUtils.isEmpty(t)) return "";
        int a = t.indexOf('《');
        int b = a < 0 ? -1 : t.indexOf('》', a);
        if (a >= 0 && b > a + 1) {
            return t.substring(a + 1, b).trim();
        }
        return t;
    }
}
