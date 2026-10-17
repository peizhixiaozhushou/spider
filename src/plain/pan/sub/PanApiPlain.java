package com.example.tvbox.plain.pan.sub;

import com.example.tvbox.common.Ext;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.Http;
import com.example.tvbox.plain.pan.PanPlain;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「可以换站点」的搜索源基类 —— 只负责三件事：<b>站点地址（可热更）+ 传输（可注入）+ 编码</b>。
 *
 * <h2>为什么要单独一层</h2>
 * 上游这批子源都有一句 {@code this.f = strTrim}（{@code PanWebShare.java:268}、
 * {@code Wogg.java:214} 等），意思是<b>站点地址是运行期确定、随时可能变的</b>。
 * 本工程早期版本把它写成了 {@code private static final String API = "https://..."}，
 * 站点一换域名整个源就废了，而且测试无法验证"域名变了请求会不会跟着变"。
 *
 * <p>这一层把「站点地址」抽成三个来源（优先级从高到低）：
 * <ol>
 *   <li>{@link #setSiteForTest(String)} —— 测试注入</li>
 *   <li>{@code ext} 的 {@code site} / {@code url} / {@code host} 字段 —— 宿主下发</li>
 *   <li>{@link #defaultSite()} —— 代码兜底常量</li>
 * </ol>
 * 并提供一个<b>可覆写的传输层</b>（{@link #fetchWith} / {@link #postWith}）：
 * {@code Http.setOfflineForTest(true)} 之后真实请求一律返回空串，
 * 只有覆写这两个方法才能捕获「真正发出去的 URL / body / header」——
 * 这是"钉住请求形态"的唯一可行办法。
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>不做重试与退避</b>：上游也是单次请求，失败即空。本层一致，
 *       因此"DDoS 限流后短暂失败"不会自愈。</li>
 *   <li><b>不解析 {@code ext} 的其它字段</b>（如代理、UA 覆盖）：只用 {@code ext.ua()}
 *       之外的三站点候选。<b>UA 固定</b>（上游逐字硬编码同一串）。</li>
 *   <li><b>站点地址不做连通性探测</b>：给什么用什么，只有格式校验（见
 *       {@link #normalizeSite}）。上游那次 {@code init} 探测本层没复刻 ——
 *       它需要先 GET 首页再正则，属于"另一条网络路径"，而在没有合法域名的前提下
 *       探测本身也发不出去。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>站点地址只做一次字符串 {@code trim} 就当域名用</b>
 *       （{@code PanWebShare.java:268} 等）：没有 {@code ://} 校验、没有主机名校验，
 *       探测失败会把半截串写进 {@code this.f}，之后 URL 与 Referer 一起坏掉。
 *       → {@link #normalizeSite(String)} 要求"含 {@code ://} 且其后第一个字符不是斜杠"，
 *       不合格直接返回空串，调用方据此<b>不发请求</b>。
 *       断言：{@code ★ 非法域名被拒}。</li>
 *   <li><b>站点地址不可配</b>：上游靠 {@code init} 探测，宿主无法下发；本工程早期版本
 *       连探测都没有，直接写死常量。→ 现在 ext 可下发、测试可注入。
 *       断言：{@code ★ 域名热更}。</li>
 *   <li><b>传输层与业务逻辑耦合</b>：上游把 {@code merge.p036k.b.l/f} 直接写在业务方法里，
 *       无法替换也就无法测试。→ 抽成 {@link #fetchWith}/{@link #postWith} 两个覆写点。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/PanWebShare.java}、{@code Wogg.java}、
 * {@code MiPan.java}、{@code QuPanSo.java} ——
 * 站点热更来自前者们 {@code init} 里的 {@code this.f = strTrim}，
 * 传输层等价于 {@code merge.p036k.b.l/f}（GET / POST 返回文本）。
 */
public abstract class PanApiPlain extends PanPlain {

    /** 默认 UA（上游这批源逐字相同，见 {@code PanWebShare.java:41} 等） */
    protected static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 测试注入的站点覆盖（最高优先级） */
    private String siteOverride = "";
    /** ext 下发的站点（次高） */
    private String siteFromExt = "";

    /**
     * 本实例中**已确认不可用**的候选域名。
     *
     * <p>为什么需要它：真配置的 `site` 数组是**故障切换列表**，列出的域名里有死有活
     * （实测 `https://tv.yydsys.top` 超时 **14.9 秒**、`https://www.hjzhencai.top` 连接被拒）。
     * 如果不记住"哪个试过了不行"，那么**每一个请求**都要先在那个死域名上耗十几秒再换下一个 ——
     * 用户看到的是"搜索转半天才出结果"。
     */
    private final java.util.Set<String> deadSites = new java.util.LinkedHashSet<>();

    /** 当前生效域名（切换成功后记住，避免每次都从头试） */
    private String activeSite = "";

    // ─────────────────────────────────────────────────────────────
    // 站点地址
    // ─────────────────────────────────────────────────────────────

    /** 代码里的兜底站点（不带尾部斜杠） */
    protected abstract String defaultSite();

    /**
     * 「这个关键字会请求哪个 URL」—— 统一的对外入口。
     *
     * <p>GET 型源返回带关键字的完整 URL；POST 型源的 URL 里没有关键字，
     * 返回请求端点（关键字在 {@code buildSearchBody} 里）。
     * 存在的意义：{@code PanChildrenTest} / 审计脚本 / 人工排查都靠它一眼看到目标地址，
     * 不必读实现。子类必须实现。
     */
    public abstract String buildSearchUrl(String key);

    /** 日志标签 */
    protected abstract String tagOf();

    /** 当前生效的站点（不带尾部斜杠；非法时为空串） */
    public String site() {
        // 1) 测试注入最高优先
        if (!TextUtils.isEmpty(siteOverride)) return normalizeSite(siteOverride);
        // 2) 已经切到某个可用域名 → 直接用（不要再从头试，见 deadSites 的说明）
        if (!TextUtils.isEmpty(activeSite)) {
            String n = normalizeSite(activeSite);
            if (!n.isEmpty()) return n;
        }
        // 3) 在候选表里挑第一个"合法且未确认失效"的
        for (String c : siteCandidates()) {
            String n = normalizeSite(c);
            if (n.isEmpty() || deadSites.contains(n)) continue;
            activeSite = n;
            return n;
        }
        // 4) 全部候选都已确认失效 → 回落第一个合法候选（或兜底），并说明原因。
        //    注意这里**不返回空串**：返回空串会让 siteMissing() 判定为"没域名"而直接放弃请求，
        //    那就再没有机会恢复了；返回一个地址让请求真的发出去，才有机会被判定复活。
        for (String c : siteCandidates()) {
            String n = normalizeSite(c);
            if (!n.isEmpty()) {
                Log.w(tagOf(), "candidates all marked dead, retry first one: " + n);
                activeSite = n;
                deadSites.remove(n);
                return n;
            }
        }
        return normalizeSite(defaultSite());
    }

    /**
     * 候选站点表：**ext 给出的全部地址**（优先级序），ext 没给时用 {@link #defaultSite()}。
     *
     * <p>这正是配置里 `site` 数组的含义 —— 实测该数组就是**故障切换列表**：
     * <pre>{@code
     * "site": ["https://tv.yydsys.top", "https://tv.yydsys.cc", "https://tv.214521.xyz"]
     *             ↑ 超时 14.9s            ↑ HTTP 200
     * }</pre>
     *
     * @return 候选表；永不为 null，且至少含一项（兜底域名）
     */
    public List<String> siteCandidates() {
        List<String> out = new ArrayList<>();
        // ① 子类专属解析结果优先。
        //    这是**扩展点**：`PanSouPlain` 等子类覆写 readSiteFromExt()，从自己的 ext 字段
        //    （如 pansouUrl）取地址。我第一版改造时把 siteFromExt 从 site() 里摘掉了，
        //    结果 PanSou 的 site() 变空 —— PanSearchShapeTest 立刻报
        //    `★ 配置了 pansouUrl -> site() 用它 expected=https://pansou.example actual=`。
        //    所以它必须作为**第一个**候选，排在通用 ext 字段之前。
        String own = normalizeSite(siteFromExt);
        if (!own.isEmpty()) out.add(own);
        // ② 通用 ext 候选（url/host/site/hosts，标量或数组）
        if (ext != null && !ext.isEmpty()) {
            for (String c : ext.sites()) {
                String n = normalizeSite(c);
                if (!n.isEmpty() && !out.contains(n)) out.add(n);
            }
        }
        // ③ 兜底
        if (out.isEmpty()) {
            String d = normalizeSite(defaultSite());
            if (!d.isEmpty()) out.add(d);
        }
        return out;
    }

    /**
     * 把**当前生效域名**标记为不可用，下一次 {@link #site()} 会前进到下一个候选。
     *
     * <p>调用时机（唯一允许的判定口径，见 {@link com.example.tvbox.plain.pan.sub.PanHtmlPlain#searchContent}）：
     * **请求层面失败** —— 抛异常、非 2xx、或响应体为空。
     * "页面拿到了但没有匹配结果"**不算**失败，绝不能因此换域名
     * （那会让"这个词搜不到"变成"换个站再搜一次"，结果不稳定且不可解释）。
     *
     * @return 被标记的域名（可能为空串，表示当时没有生效域名）
     */
    public String markCurrentSiteDead() {
        String cur = site();
        if (!cur.isEmpty()) {
            deadSites.add(cur);
            Log.w(tagOf(), "site marked dead: " + cur + " (remain "
                    + Math.max(0, siteCandidates().size() - deadSites.size()) + ")");
        }
        activeSite = "";
        return cur;
    }

    /** 已确认失效的候选域名（只读快照，供排障与测试断言） */
    public java.util.List<String> deadSites() {
        return new ArrayList<>(deadSites);
    }

    /** 测试注入站点（传 null/空 恢复默认） */
    public void setSiteForTest(String s) {
        siteOverride = s == null ? "" : s;
    }

    /**
     * 站点格式校验 —— 只接受"像 URL"的串。
     *
     * @return 规范化后的站点（去尾斜杠），不合格返回空串
     */
    public static String normalizeSite(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.contains("://")) return "";
        int i = s.indexOf("://") + 3;
        if (i >= s.length()) return "";
        return s.charAt(i) == '/' ? "" : s;
    }

    // ─────────────────────────────────────────────────────────────
    // 传输层（可注入）
    // ─────────────────────────────────────────────────────────────

    /** 可覆写的 GET：默认走 {@link Http}。测试覆写它来捕获 URL/头。 */
    public String fetchWith(String url, Map<String, String> headers) {
        return http.get(url, headers);
    }

    /** 可覆写的 POST：默认走 {@link Http}。测试覆写它来捕获 body。 */
    public String postWith(String url, String body, Map<String, String> headers) {
        return http.post(url, body, headers);
    }

    /** 本类发出的默认请求头（含 Referer） */
    public Map<String, String> requestHeaders() {
        return Http.headersOf("User-Agent", UA, "Referer", site() + "/");
    }

    // ─────────────────────────────────────────────────────────────
    // init
    // ─────────────────────────────────────────────────────────────

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        siteFromExt = readSiteFromExt();
        Log.i(tagOf(), "init, site=" + site());
    }

    /**
     * 从 ext 读站点。
     *
     * <p><b>★ 这里曾经漏掉 `hosts`（数组形态），并且重复实现了 {@link Ext#sites()} 的逻辑</b>
     *
     * <p>旧实现手写了 {@code site} / {@code url} / {@code host} 三个**标量**键的优先级。
     * 但真站点配置里普遍写的是**数组**：
     * <pre>{@code
     * {"hosts":["http://xsayang.fun","http://38.76.197.172:12521"]}
     * {"hosts":["https://www.hjzhencai.top","https://www.huanjuanwp.shop"]}
     * }</pre>
     * 于是这三个键全取不到 → 回落到各源硬编码的兜底域名（多半已失效）→
     * **真机上表现为搜索永远 0 条**，而本地测试全绿
     * （测试用 {@link #setSiteForTest(String)} 直接注入域名，根本不经过 ext 解析）。
     *
     * <p>2026-10-17 在真宿主网关上实测：真配置里 9 个源属于这种形态
     * （快映 / 闪电 / 蜡笔 / 多多 / 八零 / 虎斑 / 花卷 / 玩偶 …）。
     *
     * <p>现在统一走 {@link Ext#sites()} —— 它是"ext 里所有可能地址"的**唯一入口**，
     * 已覆盖 {@code url} / {@code host} / {@code site} / {@code hosts}(数组或分隔串) / 纯文本 ext。
     * 这样以后 ext 再支持新形态时，这里自动跟上，不会再出现"两处各写一份、漏掉一个"。
     *
     * @return 第一个通过 {@link #normalizeSite(String)} 校验的地址；没有则空串
     */
    protected String readSiteFromExt() {
        try {
            if (ext == null || ext.isEmpty()) return "";
            for (String v : ext.sites()) {
                if (!TextUtils.isEmpty(v) && !normalizeSite(v).isEmpty()) {
                    return v;
                }
            }
        } catch (Exception e) {
            Log.w(tagOf(), "read site from ext failed", e);
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────
    // 小工具
    // ─────────────────────────────────────────────────────────────

    /** UTF-8 编码（失败返回空串，不抛） */
    protected static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** JSON 字符串转义（用于手拼 body —— 上游用 Gson 的 addProperty，这里等价） */
    protected static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** null 安全 */
    protected static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 相对链接补全。
     *
     * <ul>
     *   <li>绝对 URL 原样返回</li>
     *   <li><b>协议相对</b>（{@code //host/path}）按站点 scheme 补全 ——
     *       这是 {@code <img data-src="//img.x/y.jpg">} 的标准语义；
     *       若直接拼 {@code site + h} 会得到 {@code https://site//img.x/y.jpg} 这种坏链</li>
     *   <li>站内绝对路径（{@code /v/1.html}）拼 site</li>
     *   <li>其余按目录拼 site</li>
     * </ul>
     */
    protected String absUrl(String href) {
        String h = safe(href).trim();
        if (h.isEmpty()) return "";
        if (h.startsWith("http://") || h.startsWith("https://")) return h;
        String base = site();
        if (h.startsWith("//")) {
            // 协议相对：用站点自己的 scheme（站点为空时按 https 处理）
            String scheme = base.contains("://") ? base.substring(0, base.indexOf("://")) : "https";
            return scheme + ":" + h;
        }
        if (base.isEmpty()) return h;
        return h.startsWith("/") ? base + h : base + "/" + h;
    }

    /** 没有合法站点时统一返回空响应（不发请求） */
    protected boolean siteMissing() {
        if (site().isEmpty()) {
            Log.w(tagOf(), "empty site, skip request");
            return true;
        }
        return false;
    }
}
