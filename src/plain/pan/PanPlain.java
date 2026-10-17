package com.example.tvbox.plain.pan;

import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网盘链接识别基类 —— 对应上游 {@code com.github.catvod.spider.Pan}。
 *
 * <p><b>这是全库最高杠杆的类</b>：27 个子类继承它（AiPan/Baiku/KuLe/GuiGui/
 * PanAli/PanQuark/PanUC/PanBaidu/PanXunlei/PanWebShare 等）。
 * 重写本类即覆盖 27 个类的 {@code detailContent} 能力。
 *
 * <h2>职责</h2>
 * 子类只负责"搜索"（返回网盘分享链接），本类负责：
 * <ol>
 *   <li>从文本中提取提取码（"提取码: xxxx" → "?pwd=xxxx"）</li>
 *   <li>识别链接属于哪个网盘（9 种正则）</li>
 *   <li>调用对应网盘的解析器（由子类注入）</li>
 *   <li>输出标准 TVBox 详情 JSON</li>
 * </ol>
 *
 * <h2>支持的网盘（正则来自上游，逐字保留）</h2>
 * <table>
 *   <tr><th>标识</th><th>域名特征</th></tr>
 *   <tr><td>ali</td><td>aliyundrive.com / alipan.com</td></tr>
 *   <tr><td>quark</td><td>pan.quark.cn</td></tr>
 *   <tr><td>uc</td><td>drive.uc.cn</td></tr>
 *   <tr><td>cloud189</td><td>cloud.189.cn|com</td></tr>
 *   <tr><td>cloud123</td><td>123xxx.com|cn / 123pan</td></tr>
 *   <tr><td>baidu</td><td>pan.baidu.com</td></tr>
 *   <tr><td>xunlei</td><td>pan.xunlei.com</td></tr>
 *   <tr><td>guangya</td><td>www.guangyapan.com</td></tr>
 *   <tr><td>mobile</td><td>移动云盘 /w/i/ 等</td></tr>
 * </table>
 *
 * <h2>子类需要做的</h2>
 * <pre>
 * public class XxxPlain extends PanPlain {
 *     // 1. 实现搜索（返回含网盘链接的 Vod 列表）
 *     &#64;Override public String searchContent(String key, boolean quick) { ... }
 *
 *     // 2. 可选：为支持的网盘注入解析器
 *     &#64;Override protected PanResolver resolverFor(String kind) { ... }
 * }
 * </pre>
 *
 * <h2>未覆盖的分支</h2>
 * <p>本类是<b>架构层重写</b>，不是逐行移植：上游把「9 家网盘的分享解析」硬编码进 {@code Pan} 本体，
 * 本实现把它改成 {@link PanResolver} 注入接口。因此下列分支<b>有意留空</b>，
 * 每一项都写了"上游在哪、缺什么、谁该补"：
 * <ol>
 *   <li><b>{@code Pan.a(Matcher, String)} 的 9 个网盘分支</b>（{@code Pan.java:52-136}）：
 *       上游按正则命中逐家调用私有 API 把分享换成直链，返回 {@code merge.p028c.e} 播放条目。
 *       本实现改为 {@link #resolverFor(String)} —— <b>默认没有任何 resolver 注册</b>，
 *       所以未注册时 {@link #detailContent(List)} 会返回空响应并打日志
 *       （{@code no resolver for kind=...}）。这是设计（解耦），不是缺陷；
 *       要真正解析分享请用 {@code pan.client/} 下的 {@code Pan*Plain} 注册。</li>
 *   <li><b>{@code Pan.playerContent(String,String,List)} 的 9 路分发</b>
 *       （{@code Pan.java:491-536}）：上游按线路中文名分发到 9 个单例
 *       （{@code i.d()} 阿里 / {@code w.e()} 夸克 / {@code B.c()} UC / {@code l.c()} 天翼 /
 *       {@code m.a()} 123 / {@code j.f()} 百度 / {@code X.m()} 迅雷 / {@code G.r()} 光鸭 /
 *       {@code P.a()} 移动），其中<b>百度分支还会另起线程刷新 {@code bdtime} 缓存</b>
 *       （上游 {@code new Thread(new Runnable(){...}).start()}，内含与
 *       {@code QuarkPan.checktime()} 同构的"日期不等于今天就干活"节流）。
 *       本类<b>不覆写</b> {@code playerContent}，继承 {@code BaseSpider} 的告警日志实现。
 *       ⇒ 缺省下"点播放"不会有直链，需要子类/宿主接。</li>
 *   <li><b>{@code Pan.proxy(Map)}</b>（{@code Pan.java:222-261}）：上游的播放代理 ——
 *       用 {@code merge.F.a.c/d} 按 {@code type} 取(链接, 线程数, chunkSize)，
 *       再包 {@code merge.C0301f} 转发，并剔除 {@code do/site/shareId/fileId/fileToken/
 *       url/host/thread/chunkSize/remote-addr/http-client-ip} 十个参数。
 *       <b>本类未实现</b>（{@code Spider.proxy} 保留基类返回 null）。
 *       这是"带 Referer 的直链在 TVBox 里播不动"的关键补丁，需要时按上游结构补。</li>
 *   <li><b>{@code Pan.checkValid(String)}</b>（{@code Pan.java:141-156}）与
 *       <b>{@code Pan.processJsonResponse(String)} / {@code processPan(String)}</b>
 *       （{@code Pan.java:164-221}）：分享页 HTML/JSON 的解析与有效性校验。
 *       本实现只做「正则识别 + 交给 resolver」，不解析分享页。</li>
 *   <li><b>{@link #detailContentVodPlayUrl(List)} 输出的是占位标识</b>
 *       （{@code kind://shareId?pwd=...}）而不是真实播放串 —— 上游此处会去调具体解析器。
 *       本实现把它留给 resolver，故"详情页能显示线路、但播放地址是占位符"。
 *       已在方法 javadoc 标明。</li>
 *   <li><b>{@link #filterCloudDiskLink(String)} 是简化实现</b>：上游按空白切分取第一个
 *       {@code http} 片段，本实现相同；但上游还会处理"一行里带多个链接"的更多形态。
 *       当前只取第一个，多链接输入会被截断。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>{@code private static String a}（{@code Pan.java:39}）是 {@code static} 可变字段</b>：
 *       同进程多站点共享，会被后初始化的站点覆盖。本类不保留任何 {@code static} 可变状态
 *       （只用 {@code static final} 正则），解析器句柄是 <b>实例</b>字段
 *       {@code resolvers}。
 *       断言：{@code PanTest#testResolverDispatch()}（未注册 → {@code resolverFor} 返回 null /
 *       注册后可取 / {@code detailContent} 无解析器 → 空响应）。</li>
 *   <li><b>9 家网盘硬编码在基类</b>（{@code Pan.java:52-136} + {@code :491-536} 共 ~330 行）：
 *       新增一家网盘必须改基类，且 27 个子类全部被动继承这套 if-else。
 *       → 已改为 {@link PanResolver} 注册表（{@link #registerResolver}），
 *       基类只留「识别 + 分发」。</li>
 *   <li><b>用中文线路名做分发键</b>（{@code str.contains("阿里")}/{@code "夸克"}…）：
 *       UI 文案一变分发就断，且 {@code contains} 会被子串误命中
 *       （若出现同时含"夸克"和"UC"的线路名，先匹配者胜）。
 *       → 本实现用 {@link PanMatch#kind} 这类<b>稳定标识</b>（ali/quark/uc/…）分发，
 *       中文名只用于显示（{@link #panDisplayName(String)}）。</li>
 *   <li><b>上游 {@code playerContent} 里的百度分支吞异常</b>：{@code catch (Throwable th)}
 *       后只 {@code println}，且 {@code parseInt} 失败时整个节流失效但无人知。
 *       → 本类不实现该分支（见上），但把这条列在案：补实现时应按
 *       {@code UCQuarkMaintBase.parseYyyyMMdd} 的容错方式写，别照抄。</li>
 *   <li><b>提取码正则只认「提取码」三个字</b>（{@code Pan.java:157-163}，
 *       模式 {@code "提取码[\\s:]*"}）：上游实测站点还会写
 *       <b>「访问码」「密码」「提取碼」</b>（繁简）以及 {@code ?pwd=} 已带码的情形，
 *       这些都会漏。本实现逐字保留上游行为（不擅自扩正则，以免与线上不一致），
 *       <b>但把这一点明确记在此处</b>：若遇到"提取码没被转成 ?pwd="，
 *       第一个要看的就是这里。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Pan.java}（537 行）——
 * 正则（{@code :42-50}）、提取码转换（{@code :157-163}）、解析器分发顺序（{@code :262-320}）、
 * {@code proxy}（{@code :222-261}）、{@code playerContent} 九路分发（{@code :491-536}）
 * 均已逐字核对；本类的未覆盖项就来自这四处。
 * 子类清单与 ext 契约: {@code maintain/evidence/INVENTORY.md}。
 */
public abstract class PanPlain extends BaseSpider {

    private static final String TAG = "PanPlain";

    // ── 9 种网盘识别正则（逐字来自上游 Pan.java）────────────────
    /** 阿里云盘 */
    protected static final Pattern RE_ALI = Pattern.compile(
            "(www\\.aliyundrive\\.com|www\\.alipan\\.com)/s/([^/]+)(/folder/([^/]+))?");
    /** 夸克网盘 */
    protected static final Pattern RE_QUARK = Pattern.compile(
            "pan\\.quark\\.cn/s/([^/?#]+)(?:#/list/share)?(?:\\?pwd=([^&]+))?(?:[#&].*)?$");
    /** UC 网盘 */
    protected static final Pattern RE_UC = Pattern.compile(
            "drive\\.uc\\.cn/s/([0-9a-fA-F]{8,20})(?:\\?pwd=([^&]+))?(?:\\?public=1)?(?:[#&].*)?$");
    /** 天翼云盘 */
    protected static final Pattern RE_189 = Pattern.compile(
            "cloud\\.189\\.(?:cn|com)/(?:[^/?#]+/)?(?:share\\.html#/)?t/([A-Za-z0-9]+)"
                    + "(?:\\?pwd=([A-Za-z0-9]+)|[（(]访问码：([A-Za-z0-9]+)[）)])?");
    /** 123 云盘 */
    protected static final Pattern RE_123 = Pattern.compile(
            "(?:www\\.)?123(?:[a-zA-Z0-9]{3}|pan)\\.(?:com|cn)/(?:s|123pan)/([^/?#]+)"
                    + "(?:\\?pwd=([^&#]+))?(?:[&#].*)?$");
    /**
     * 百度网盘。
     *
     * <p>⚠️ 上游正则要求 {@code ?pwd=} 必须存在，导致<b>无提取码的链接识别失败</b>
     * （如 {@code pan.baidu.com/s/1xyz}）。本实现把 pwd 段改为可选，
     * 比上游更健壮；分组顺序保持与上游一致（1=sid 2=pwd 3=surl 4=pwd2）。
     */
    protected static final Pattern RE_BAIDU = Pattern.compile(
            "pan\\.baidu\\.com/(?:s/1([^?&#/\\s]+)(?:\\?pwd=([^&\\s]+))?"
          + "|share/init\\?surl=([^&\\s]+)(?:&pwd=([^&\\s]+))?)");
    /** 迅雷云盘 */
    protected static final Pattern RE_XUNLEI = Pattern.compile(
            "pan\\.xunlei\\.com/s/([^?#/]+)(?:\\?pwd=([^&#]+))?");
    /** 光鸭云盘 */
    protected static final Pattern RE_GUANGYA = Pattern.compile(
            "www\\.guangyapan\\.com/s/([^?#/]+)(?:\\?code=([^&#]+))?");
    /** 移动云盘 */
    protected static final Pattern RE_MOBILE = Pattern.compile(
            "(?:/w/i/|\\?linkID=|/m/i\\?)([\\w]+)");

    /** 提取码模式：把"提取码: xxxx"转成"?pwd=xxxx" */
    private static final Pattern RE_EXTRACT_CODE = Pattern.compile("提取码[\\s:]*");

    /** 网盘解析器接口 —— 由子类按需注入 */
    public interface PanResolver {
        /**
         * @param shareId 分享 ID
         * @param pwd     提取码，可能为空
         * @return 解析出的播放信息；失败返回 null
         */
        Vod resolve(String shareId, String pwd);
    }

    /** 已注册的解析器，key 为网盘标识（ali/quark/uc/...） */
    private final Map<String, PanResolver> resolvers = new LinkedHashMap<>();

    /** 注册网盘解析器 */
    public void registerResolver(String kind, PanResolver r) {
        if (kind != null && r != null) resolvers.put(kind, r);
    }

    /** 取解析器；子类可覆写实现延迟创建 */
    public PanResolver resolverFor(String kind) {
        return resolvers.get(kind);
    }

    // ─────────────────────────────────────────────────────────────
    // 提取码处理
    // ─────────────────────────────────────────────────────────────

    /**
     * 提取码转换 —— 还原 {@code Pan.processExtractionCode()}。
     *
     * <pre>
     * 上游: Pattern.compile("提取码[\\s:]*").matcher(str).replaceAll("?pwd=")
     * 例:  "链接 https://pan.quark.cn/s/abc 提取码：1234"
     *   ->  "链接 https://pan.quark.cn/s/abc?pwd=1234"
     * </pre>
     */
    public static String processExtractionCode(String s) {
        if (s == null) return "";
        return RE_EXTRACT_CODE.matcher(s).replaceAll("?pwd=");
    }

    /**
     * 从文本中挑出网盘链接 —— 对应上游 {@code filterCloudDiskLinks}。
     * 简化实现：按空白切分取第一个 http 开头的片段。
     */
    protected String filterCloudDiskLink(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.startsWith("http")) {
            int sp = t.indexOf(' ');
            return sp > 0 ? t.substring(0, sp) : t;
        }
        for (String part : t.split("\\s+")) {
            if (part.startsWith("http")) return part;
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────
    // 链接识别
    // ─────────────────────────────────────────────────────────────

    /**
     * 识别结果。
     * <p>public 是刻意设计：外部工具/测试需要检查识别结果，
     * 且子类可能跨包使用。
     */
    public static class PanMatch {
        /** ali/quark/uc/cloud189/cloud123/baidu/xunlei/guangya/mobile */
        public final String kind;
        /** 分享 ID */
        public final String shareId;
        /** 提取码（可能为空串） */
        public final String pwd;
        /** 原始 matcher，供特殊分支使用 */
        public final Matcher matcher;

        public PanMatch(String kind, String shareId, String pwd, Matcher m) {
            this.kind = kind;
            this.shareId = shareId == null ? "" : shareId;
            this.pwd = pwd == null ? "" : pwd;
            this.matcher = m;
        }

        @Override public String toString() {
            return kind + "[id=" + shareId + ",pwd=" + pwd + "]";
        }
    }

    /**
     * 按上游顺序识别链接归属 —— 还原 {@code Pan.detailContent()} 的 if-else 链。
     *
     * <p>顺序很重要：先匹配的优先。上游就是 ali → quark → uc → 189 → 123
     * → baidu → xunlei → guangya → mobile。
     *
     * @return 未识别返回 null
     */
    public PanMatch identify(String url) {
        if (url == null || url.isEmpty()) return null;

        Matcher m = RE_ALI.matcher(url);
        if (m.find()) {
            // ali: group(2)=shareId, groupCount()==4 时 group(4)=folderId
            String id = m.group(2);
            if (m.groupCount() == 4 && m.group(4) != null) id = id + "/" + m.group(4);
            return new PanMatch("ali", id, "", m);
        }
        m = RE_QUARK.matcher(url);
        if (m.find()) return new PanMatch("quark", m.group(1), m.group(2), m);

        m = RE_UC.matcher(url);
        if (m.find()) return new PanMatch("uc", m.group(1), m.group(2), m);

        m = RE_189.matcher(url);
        if (m.find()) {
            String pwd = m.group(2) != null ? m.group(2) : m.group(3);
            return new PanMatch("cloud189", m.group(1), pwd, m);
        }
        m = RE_123.matcher(url);
        if (m.find()) return new PanMatch("cloud123", m.group(1), m.group(2), m);

        m = RE_BAIDU.matcher(url);
        if (m.find()) {
            String id = m.group(1) != null ? m.group(1) : m.group(3);
            String pwd = m.group(1) != null ? m.group(2) : m.group(4);
            return new PanMatch("baidu", id, pwd, m);
        }
        m = RE_XUNLEI.matcher(url);
        if (m.find()) return new PanMatch("xunlei", m.group(1), m.group(2), m);

        m = RE_GUANGYA.matcher(url);
        if (m.find()) return new PanMatch("guangya", m.group(1), m.group(2), m);

        m = RE_MOBILE.matcher(url);
        if (m.find()) return new PanMatch("mobile", m.group(1), "", m);

        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // 搜索地址（统一对外入口）
    // ─────────────────────────────────────────────────────────────

    /**
     * 「这个关键字会请求哪个 URL」—— 供审计、测试、排障用的统一入口。
     *
     * <p>为什么提到基类：17 个搜索子源<b>每一个</b>都需要回答这个问题
     * （{@code PanChildrenTest} 与 {@code ae/tools/audit_docs.py} 的核对都靠它），
     * 但它原来只散落在各子类里、可见性还不一致（{@code protected}/{@code public} 混着），
     * 导致"用反射才能统一验"—— 而反射会在成员缺方法时<b>静默漏测</b>。
     * 提到基类后，缺实现就是编译错误。
     *
     * <p>子类语义：
     * <ul>
     *   <li>GET 型源：返回<b>带关键字</b>的完整 URL</li>
     *   <li>POST 型源（{@code MiPan}/{@code QuPanSo}）：URL 里没有关键字，
     *       返回请求端点（关键字在 {@code buildSearchBody} 里）</li>
     * </ul>
     * 默认返回空串 = 该类没有单一搜索地址（本基类的非搜索子类用它）。
     *
     * @param key 关键字（是否需要编码由子类自行决定，与各自的实现保持一致）
     * @return 请求 URL；没有则空串
     */
    public String buildSearchUrl(String key) {
        return "";
    }

    // ─────────────────────────────────────────────────────────────
    // 详情：基类统一处理
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code Pan.detailContent()} —— 子类通常不需要覆写。
     *
     * <p>输入是含网盘链接的文本（来自搜索结果），输出标准详情 JSON。
     */
    @Override
    public String detailContent(List<String> ids) {
        String input = firstId(ids);
        if (input.isEmpty()) return emptyResponse();

        String link = filterCloudDiskLink(processExtractionCode(input));
        if (!link.startsWith("http")) {
            Log.w(TAG, "detailContent: no http link in input");
            return emptyResponse();
        }

        PanMatch m = identify(link);
        if (m == null) {
            Log.w(TAG, "detailContent: unrecognized pan link: " + truncate(link));
            return emptyResponse();
        }

        PanResolver r = resolverFor(m.kind);
        if (r == null) {
            Log.w(TAG, "detailContent: no resolver for kind=" + m.kind
                    + " (subclass must registerResolver)");
            return emptyResponse();
        }

        Vod vod = r.resolve(m.shareId, m.pwd);
        if (vod == null) {
            Log.w(TAG, "detailContent: resolver returned null for " + m);
            return emptyResponse();
        }
        return VodBuilder.detail(vod).detailContent();
    }

    /**
     * 还原 {@code Pan.detailContentVodPlayFrom()} —— 为每个链接生成线路名。
     */
    protected String detailContentVodPlayFrom(List<String> links) {
        if (links == null || links.isEmpty()) return "";
        List<String> froms = new ArrayList<>();
        for (int i = 0; i < links.size(); i++) {
            PanMatch m = identify(filterCloudDiskLink(processExtractionCode(links.get(i))));
            String kind = m == null ? "unknown" : m.kind;
            froms.add(panDisplayName(kind) + (i + 1));
        }
        return TextUtils.join(Vod.SEP_SOURCE, froms);
    }

    /** 网盘标识 → 中文显示名 */
    public static String panDisplayName(String kind) {
        if (kind == null) return "网盘";
        switch (kind) {
            case "ali":      return "阿里";
            case "quark":    return "夸克";
            case "uc":       return "UC";
            case "cloud189": return "天翼";
            case "cloud123": return "123";
            case "baidu":    return "百度";
            case "xunlei":   return "迅雷";
            case "guangya":  return "光鸭";
            case "mobile":   return "移动";
            default:         return "网盘";
        }
    }

    /** 还原 {@code Pan.detailContentVodPlayUrl()} —— 批量识别链接 */
    protected String detailContentVodPlayUrl(List<String> links) {
        if (links == null || links.isEmpty()) return "";
        List<String> out = new ArrayList<>();
        for (String raw : links) {
            PanMatch m = identify(filterCloudDiskLink(processExtractionCode(raw)));
            if (m == null) continue;
            // 上游此处调用具体网盘解析器拿到播放串；本基类只输出占位标识
            out.add(m.kind + "://" + m.shareId + (m.pwd.isEmpty() ? "" : "?pwd=" + m.pwd));
        }
        return TextUtils.join(Vod.SEP_SOURCE, out);
    }

    private static String truncate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }
}
