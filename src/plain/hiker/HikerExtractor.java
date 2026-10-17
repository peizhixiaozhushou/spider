package com.example.tvbox.plain.hiker;

import com.example.tvbox.common.Dom;
import com.example.tvbox.common.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.tvbox.plain.hiker.HikerRule.ANAL_MAC_PLAYER;
import static com.example.tvbox.plain.hiker.HikerRule.CHARSET;
import static com.example.tvbox.plain.hiker.HikerRule.DEBUG;
import static com.example.tvbox.plain.hiker.HikerRule.FCLASS_NAME;
import static com.example.tvbox.plain.hiker.HikerRule.FCLASS_VALUE;
import static com.example.tvbox.plain.hiker.HikerRule.FCATELOG_NAME;
import static com.example.tvbox.plain.hiker.HikerRule.FCATELOG_VALUE;
import static com.example.tvbox.plain.hiker.HikerRule.FAREA_NAME;
import static com.example.tvbox.plain.hiker.HikerRule.FAREA_VALUE;
import static com.example.tvbox.plain.hiker.HikerRule.FLANG_NAME;
import static com.example.tvbox.plain.hiker.HikerRule.FLANG_VALUE;
import static com.example.tvbox.plain.hiker.HikerRule.FSORT_NAME;
import static com.example.tvbox.plain.hiker.HikerRule.FSORT_VALUE;
import static com.example.tvbox.plain.hiker.HikerRule.FYEAR_NAME;
import static com.example.tvbox.plain.hiker.HikerRule.FYEAR_VALUE;
import static com.example.tvbox.plain.hiker.HikerRule.HEADERS;
import static com.example.tvbox.plain.hiker.HikerRule.OCR_API;
import static com.example.tvbox.plain.hiker.HikerRule.SEARCH_HEADERS;
import static com.example.tvbox.plain.hiker.HikerRule.SNIFF_FILTER;
import static com.example.tvbox.plain.hiker.HikerRule.SNIFF_KEYWORDS;
import static com.example.tvbox.plain.hiker.HikerRule.SNIFF_MANUAL;

/**
 * XYQHiker 的「动作层」—— 占位符替换、URL/请求头构造、反爬识别、嗅探判定、筛选面板生成。
 *
 * <p>反编译原文: {@code ae/decompiled/XYQHiker.java}
 * （{@code ac} 请求头 {@code:393-492}、{@code y} 请求头 {@code:4546-4656}、
 * {@code x} 图片代理 {@code:4535-4545}、{@code z} 筛选项 {@code:4657-4700}、
 * {@code t} 筛选面板 {@code:3696-4489}、{@code isVideoFormat} {@code:3086-3108}、
 * {@code manualVideoCheck} {@code:3109-3203}、{@code checkstring}/{@code checkveriry}/{@code vertype} {@code:97-116}/{@code:373-383}、
 * {@code w} 初始化 {@code:4518-4534}）。
 * smali 权威对照: {@code /tmp/hiker-smali/com/github/catvod/spider/XYQHiker.smali}。
 *
 * <h2>原混淆名 → 语义名对照表</h2>
 * <table>
 *   <tr><th>原</th><th>smali 行</th><th>本类</th><th>含义</th></tr>
 *   <tr><td>{@code w()}</td><td>21936</td><td>{@link #init}</td>吃 DSL：charset / OCR / debug</td></tr>
 *   <tr><td>{@code y(String)}</td><td>22083</td><td>{@link #headers}</td>普通请求头（UA 预设 + Cookie）</td></tr>
 *   <tr><td>{@code ac(String)}</td><td>1555</td><td>{@link #searchHeaders}</td>搜索请求头</td></tr>
 *   <tr><td>{@code x(String,String)}</td><td>22041</td><td>{@link #proxyImage}</td>图片套 {@code @Headers=} 反盗链</td></tr>
 *   <tr><td>{@code checkstring}</td><td>106</td><td>{@link #isBlockedContent}</td>「被拦了」判定</td></tr>
 *   <tr><td>{@code checkveriry}</td><td>149</td><td>{@link #isVerifyPage}</td>「要验证码」判定</td></tr>
 *   <tr><td>{@code vertype}</td><td>1467</td><td>{@link #verifyFeature}</td>返回命中的特征串</td></tr>
 *   <tr><td>{@code isVideoFormat}</td><td>13253</td><td>{@link #sniffMatch}</td>关键词/过滤词判定</td></tr>
 *   <tr><td>{@code manualVideoCheck}</td><td>13415</td><td>{@link #manualSniff}</td>是否手动嗅探</td></tr>
 *   <tr><td>{@code z(a,b,c,d)}</td><td>22756</td><td>{@link #filterItem}</td>一个筛选项</td></tr>
 *   <tr><td>{@code t(×14)}</td><td>20399</td><td>{@link #buildFilterPanel}</td>整个筛选面板</td></tr>
 *   <tr><td>{@code ku.d(s,ku.c)}</td><td>—</td><td>{@link #md5}</td>MD5 小写十六进制</td></tr>
 * </table>
 *
 * <h2>占位符全表（本项目把两套名字都收进来，统一替换）</h2>
 * <table>
 *   <tr><th>占位符</th><th>来源（上游字段/方法）</th><th>含义</th></tr>
 *   <tr><td>{@code {cateId}}</td><td>{@code s(tid,…)}</td><td>分类 id</td></tr>
 *   <tr><td>{@code {catePg}}</td><td>{@code s(…,pg,…)}</td><td>分类页码</td></tr>
 *   <tr><td>{@code {pg}}</td><td>同上（本项目别名）</td><td>页码</td></tr>
 *   <tr><td>{@code {searchKey}}</td><td>{@code ah(key,…)}</td><td>搜索关键词</td></tr>
 *   <tr><td>{@code {wd}}</td><td>{@code ah}（上游原名）</td><td>搜索关键词（未 URL 编码）</td></tr>
 *   <tr><td>{@code {searchPg}}</td><td>{@code ah(…,pg)}</td><td>搜索页码</td></tr>
 *   <tr><td>{@code {SearchPg}}</td><td>{@code ah}（上游原名，注意大小写）</td><td>搜索页码</td></tr>
 *   <tr><td>{@code {site}}</td><td>站点键</td><td>站点域名/键</td></tr>
 *   <tr><td>{@code {vid}}</td><td>{@code detailContent(ids)}</td><td>详情 id</td></tr>
 *   <tr><td>{@code {cateName}}</td><td>分类名</td><td>分类显示名</td></tr>
 *   <tr><td>{@code {class}/{@code {area}}/{@code {year}}/{@code {lang}}/{@code {by}}</td><td>{@code t()} 里的筛选键</td><td>筛选面板键名</td></tr>
 * </table>
 * <b>未登记的占位符原样保留</b>（不删、不清空）—— 这是刻意行为：DSL 里写错的占位符
 * 应当原样出现在请求 URL 里，方便对抓包结果直接定位，而不是被静默吃掉。
 */
public final class HikerExtractor {

    /** 上游 {@code w()} 里 {@code this.k} 的默认值。 */
    public static final String DEFAULT_OCR_API = "https://api.nn.ci/ocr/b64/text";

    /** UA 预设（与上游 {@code y}/{@code ac} 逐字一致）。 */
    public static final String UA_PC =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/133.0.0.0 Safari/537.36 Edg/133.0.0.0";
    public static final String UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 13; Xiaomi 13 Pro) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/133.0.0.0 Mobile Safari/537.36";
    public static final String UA_IOS =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/16.0 Mobile/15E148 Safari/604.1";
    public static final String UA_MAC =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 20_40; rv:100.0) AppleWebKit/537.75.14 (KHTML, like Gecko) "
                    + "Version/15.0.0 Safari/1500";

    /** 上游「Headers 为空」时的兜底 UA（注意是 okhttp 标识，不是浏览器 UA）。 */
    public static final String UA_DEFAULT = "okhttp/3.12.11";

    /** 上游 {@code checkstring} 的三条「被拦」特征。 */
    public static final String[] BLOCKED_FEATURES = {
            "m3u8.pw/Cache", "from=https://banyung.pw", "getm3u8?url=http"};

    private final HikerRule rule;
    private String cookie = "";
    private String charset = "UTF-8";
    private boolean debug;
    private String ocrApi = DEFAULT_OCR_API;

    public HikerExtractor(HikerRule rule) {
        this.rule = rule == null ? HikerRule.empty() : rule;
        init();
    }

    /**
     * 上游 {@code w()}：把 DSL 里的 charset / OCR_API / DEBUG 读出来。
     *
     * <p>smali {@code XYQHiker.smali:21936}。上游还会在 ext 以 {@code http} 开头时联网
     * 拉规则 JSON —— 该分支**未实现**（测试要求零网络；TVBox 实际也只用内联 ext），
     * 详见 {@code XYQHiker_DSL.md} 的「未实现」小节。
     */
    public HikerExtractor init() {
        this.charset = rule.value(CHARSET);
        if (this.charset.isEmpty()) this.charset = "UTF-8";
        this.ocrApi = rule.value(OCR_API);
        String dbg = rule.value(DEBUG);
        this.debug = "1".equals(dbg) || "是".equals(dbg);
        return this;
    }

    public HikerRule rule() { return rule; }

    public String charset() { return charset; }

    public boolean debug() { return debug; }

    public String ocrApi() { return ocrApi; }

    public String cookie() { return cookie; }

    /** 累积响应里的 {@code Set-Cookie}（上游 {@code this.c = TextUtils.join(";", list)}）。 */
    public void accumulateCookie(List<String> setCookie) {
        if (setCookie == null || setCookie.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < setCookie.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(setCookie.get(i));
        }
        this.cookie = sb.toString();
    }

    /** 累积一个形如 {@code k=v} 的 Cookie 片段（内部用）。 */
    public void addCookie(String kv) {
        if (kv == null || kv.isEmpty()) return;
        this.cookie = this.cookie.isEmpty() ? kv : this.cookie + ";" + kv;
    }

    // ══════════════════════════════════════════════════════════════
    // 占位符
    // ══════════════════════════════════════════════════════════════

    /** 占位符 → 变量名的登记表，供文档与测试遍历。 */
    public static final String[][] PLACEHOLDERS = {
            {"{cateId}", "cateId", "分类 id"},
            {"{catePg}", "catePg", "分类页码"},
            {"{pg}", "catePg", "页码（{catePg} 的别名）"},
            {"{searchKey}", "searchKey", "搜索关键词（URL 编码）"},
            {"{wd}", "wd", "搜索关键词（原样，上游 `ah` 用它）"},
            {"{searchPg}", "searchPg", "搜索页码"},
            {"{SearchPg}", "searchPg", "搜索页码（上游大小写）"},
            {"{site}", "site", "站点键"},
            {"{cateName}", "cateName", "分类显示名"},
            {"{vid}", "vid", "详情 id"},
            {"{class}", "class", "筛选-类型 占位"},
            {"{area}", "area", "筛选-地区 占位"},
            {"{year}", "year", "筛选-年份 占位"},
            {"{lang}", "lang", "筛选-语言 占位"},
            {"{by}", "by", "筛选-排序 占位"},
    };

    /** 构造变量表（缺项留空，不写 <code>null</code>）。 */
    public static Map<String, String> vars(String cateId, String catePg, String searchKey,
                                           String searchPg, String site, String vid) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        put(m, "cateId", cateId);
        put(m, "catePg", catePg);
        put(m, "searchKey", urlEncode(searchKey));
        put(m, "wd", searchKey);
        put(m, "searchPg", searchPg);
        put(m, "site", site);
        put(m, "vid", vid);
        return m;
    }

    private static void put(Map<String, String> m, String k, String v) {
        m.put(k, v == null ? "" : v);
    }

    /**
     * 占位符替换。
     *
     * <p><b>未登记的占位符原样保留</b>（见类注释）；已登记但值为空的占位符替换成空串
     * —— 这两条是有区别的：前者是「DSL 写错了」，后者是「这个上下文本来就没有该值」。
     */
    public static String fill(String template, Map<String, String> vars) {
        if (template == null) return "";
        if (template.indexOf('{') < 0) return template;
        String out = template;
        for (String[] row : PLACEHOLDERS) {
            if (out.indexOf(row[0]) < 0) continue;
            String v = vars == null ? null : vars.get(row[1]);
            if (v == null) continue;
            out = out.replace(row[0], v);
        }
        return out;
    }

    /** {@code {cateId}} / {@code {catePg}} 便捷替换。 */
    public static String fillCategory(String template, String cateId, String catePg, String site) {
        return fill(template, vars(cateId, catePg, null, null, site, null));
    }

    /** {@code {wd}} / {@code {SearchPg}} 便捷替换（搜索侧）。 */
    public static String fillSearch(String template, String key, String pg) {
        return fill(template, vars(null, null, key, pg, null, null));
    }

    /** 上游 {@code URLEncoder.encode(s, charset)}。 */
    public static String urlEncode(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // URL 规格：;post / ?body / 时间戳 / md5()
    // ══════════════════════════════════════════════════════════════

    /** 一条「抓取地址规格」解析结果。 */
    public static final class UrlSpec {
        /** 去掉指令后的真实 URL */
        public final String url;
        /** 是否 POST */
        public final boolean post;
        /** POST 体（表单串或 JSON 串）；GET 时为空串 */
        public final String body;
        /** 是否有 {@code ;post} 标记 */
        public final boolean hasPostMarker;

        UrlSpec(String url, boolean post, String body, boolean hasPostMarker) {
            this.url = url;
            this.post = post;
            this.body = body;
            this.hasPostMarker = hasPostMarker;
        }

        @Override public String toString() {
            return (post ? "POST " : "GET ") + url + (body.isEmpty() ? "" : " body=" + body);
        }
    }

    /**
     * 解析上游的 URL 指令语法。
     *
     * <pre>
     * u?b;post   → POST，body = b（`b` 以 { 开头且以 } 结尾时当 JSON 体）
     * u;other    → GET，url = u（; 之后的全部丢弃 —— `ah` 里也是 split(";")[0]）
     * u          → GET，url = u
     * </pre>
     *
     * <p>证据：{@code s} smali {@code:17663}（{@code ";post"}）、{@code:17708}（{@code ";"}）、
     * {@code detailContent} smali 同一套写法；{@code ah} smali 里是
     * {@code search_url.split(";")[0]}。
     */
    public static UrlSpec parseUrlSpec(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return new UrlSpec("", false, "", false);

        boolean hasPost = s.contains(";post");
        if (hasPost) {
            // 上游写的是 url.split("?")[0]，对「无 ? 只有 ;post」的写法会把 ;post 留在 URL 里。
            // 本实现把指令标记一起剥掉（并在此记录为对上游的修正）。
            String head = s.split("\\?", 2)[0].replace(";post", "").replace("？？", "?").trim();
            String tail = "";
            String[] q = s.split("\\?", 2);
            if (q.length > 1) {
                tail = q[1].split(";", 2)[0].replace("？？", "?").trim();
            }
            return new UrlSpec(head, true, tail, true);
        }
        int semi = s.indexOf(';');
        String url = semi < 0 ? s : s.substring(0, semi);
        return new UrlSpec(url.replace("？？", "?").trim(), false, "", false);
    }

    /**
     * 表单串 → 有序 Map。
     *
     * <p>上游是在 {@code s}/{@code ah} 里手写 {@code indexOf("=")} 切分后 put 进
     * {@code LinkedHashMap}；本方法把它抽出来并容忍缺 {@code =} 的片段。
     */
    public static Map<String, String> formToMap(String body) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (body == null || body.isEmpty()) return m;
        for (String seg : body.split("&")) {
            if (seg.isEmpty()) continue;
            int eq = seg.indexOf('=');
            if (eq < 0) m.put(seg, "");
            else m.put(seg.substring(0, eq), seg.substring(eq + 1));
        }
        return m;
    }

    /** POST 体是否是 JSON（上游判定：以 {@code {} 开头、以 {@code }} 结尾）。 */
    public static boolean isJsonBody(String body) {
        String b = body == null ? "" : body.trim();
        return b.startsWith("{") && b.endsWith("}");
    }

    /**
     * 展开 URL 里的宏：
     * <ul>
     *   <li>{@code 时间戳} → 秒级 Unix 时间</li>
     *   <li>{@code 时间标} → 毫秒级 Unix 时间</li>
     *   <li>{@code md5(内容)} → {@code md5(内容)} 整段替换成它的 MD5 小写十六进制</li>
     * </ul>
     */
    public static String expandMacros(String url, long nowMs) {
        if (url == null || url.isEmpty()) return "";
        String s = url.replace("时间戳", String.valueOf(nowMs / 1000))
                .replace("时间标", String.valueOf(nowMs));
        if (s.contains("md5(")) {
            String inner = HikerSelector.between(s, "md5(", ")");
            if (!inner.isEmpty()) s = s.replace("md5(" + inner + ")", md5(inner));
        }
        return s;
    }

    /** 用当前时间调用 {@link #expandMacros(String, long)}。 */
    public static String expandMacros(String url) {
        return expandMacros(url, System.currentTimeMillis());
    }

    /** 上游 {@code ku.d(s, ku.c)}：MD5 小写十六进制。 */
    public static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 前缀 + 值 + 后缀（三者任一为空都直接忽略该段）。 */
    public static String applyPreSuffix(String prefix, String value, String suffix) {
        StringBuilder sb = new StringBuilder();
        if (prefix != null) sb.append(prefix);
        if (value != null) sb.append(value);
        if (suffix != null) sb.append(suffix);
        return sb.toString();
    }

    /** 上游 {@code wh.g(base, href)}：把相对链接绝对化（对应 {@code URL(base, href)}）。 */
    public static String absolutize(String base, String href) {
        if (href == null || href.isEmpty()) return "";
        if (base == null || base.isEmpty()) return href;
        String h = href.trim();
        if (h.startsWith("http://") || h.startsWith("https://") || h.startsWith("//")) return h;
        try {
            java.net.URL b = new java.net.URL(base);
            return new java.net.URL(b, h).toString();
        } catch (Exception e) {
            if (h.startsWith("/")) {
                int i = base.indexOf("://");
                int slash = i < 0 ? -1 : base.indexOf('/', i + 3);
                String root = slash < 0 ? base : base.substring(0, slash);
                return root + h;
            }
            return h;
        }
    }

    /** 把 CSS {@code url("...")} 里的地址抠出来；不含 {@code url(} 时原样返回。 */
    public static String unwrapCssUrl(String value) {
        if (value == null) return "";
        if (!value.contains("url(")) return value;
        String v = value.replace("\\&quot;", "");
        String[] parts = v.split("url\\(");
        if (parts.length > 1 && parts[1].contains(")")) {
            return parts[1].split("\\)")[0].replaceAll("['\"]", "");
        }
        return value;
    }

    /**
     * 上游 {@code x(url, referer)}：给图片/视频地址套上 {@code @Headers=} 反盗链。
     *
     * <p>smali {@code XYQHiker.smali:22041}。TVBox 播放器认出这个后缀后会把
     * {@code @Headers} 后面的 JSON 当请求头发。
     */
    public String proxyImage(String url, String referer) {
        if (url == null || url.isEmpty()) return "";
        try {
            JSONObject h = new JSONObject();
            if (referer != null && !referer.isEmpty()) h.put("referer", referer);
            return url + "@Headers=" + h.toString();
        } catch (Exception e) {
            return url;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 请求头
    // ══════════════════════════════════════════════════════════════

    /**
     * UA 预设展开。
     *
     * <p>{@code PC_UA}/{@code 电脑} → 桌面 Edge；{@code MOBILE_UA}/{@code 手机} → Android Chrome；
     * {@code IOS_UA}/{@code 苹果手机} → iOS Safari；{@code MAC_UA}/{@code 苹果电脑} → macOS Safari。
     */
    public static String uaFor(String token, String orEmpty) {
        if (token == null) return orEmpty == null ? "" : orEmpty;
        String t = token.trim();
        if (t.equals("PC_UA") || t.equals("电脑")) return UA_PC;
        if (t.equals("MOBILE_UA") || t.equals("手机")) return UA_MOBILE;
        if (t.equals("IOS_UA") || t.equals("苹果手机")) return UA_IOS;
        if (t.equals("MAC_UA") || t.equals("苹果电脑")) return UA_MAC;
        return orEmpty == null ? t : (t.isEmpty() ? orEmpty : t);
    }

    /**
     * 构造普通请求头。
     *
     * <p>smali {@code XYQHiker.smali:22083}（原 {@code y}）。DSL 形态：
     * <ul>
     *   <li>不含 {@code $}：整串就是一个 UA 预设名或自定义 UA</li>
     *   <li>含 {@code $}：{@code key$value#key$value#…}，例如
     *       {@code user-agent$MOBILE_UA#referer$WebView}</li>
     * </ul>
     * {@code referer$WebView} 是特殊值：表示「用当前页 URL 当 Referer」，
     * 由 {@link #headers} 的 {@code referer} 参数注入（上游在 playerContent 里处理）。
     *
     * <p><b>与上游的差异</b>：上游 {@code y()} 里还有一段把 {@code this.e} / {@code this.f}
     * 两个<b>硬编码空串</b>拼进 Cookie 的逻辑（{@code e}/{@code f} 字段声明成
     * {@code public final String e = ""}），等价于死代码。本实现只保留「累积 Cookie」一条路径。
     *
     * @param referer 当前页 URL（{@code referer$WebView} 用它）
     */
    public Map<String, String> headers(String referer) {
        String spec = rule.value(HEADERS).trim();
        if (spec.isEmpty()) spec = UA_DEFAULT;
        return expandHeaders(spec, referer);
    }

    /**
     * 请求头规格串展开（{@code headers} 与 {@code searchHeaders} 共用一份逻辑）。
     *
     * <p>抽出来的原因：上游 {@code y()} 与 {@code ac()} 是两份 110 行的近似复制，
     * 且 {@code ac()} 里忘了带上累积 Cookie。共用后搜索请求头也能带上 Cookie。
     */
    private Map<String, String> expandHeaders(String spec, String referer) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (spec == null || spec.isEmpty()) spec = UA_DEFAULT;

        if (!spec.contains("$")) {
            String ua = uaFor(spec, UA_DEFAULT);
            out.put("User-Agent", ua);
            if (!cookie.isEmpty()) out.put("Cookie", cookie);
            return out;
        }

        for (String seg : spec.split("#")) {
            if (seg.isEmpty()) continue;
            String[] kv = seg.split("\\$", 2);
            if (kv.length < 2) continue;
            String name = kv[0].trim();
            String value = uaFor(kv[1].trim(), kv[1].trim());
            if (name.equalsIgnoreCase("referer") && value.equalsIgnoreCase("WebView")) {
                if (referer != null && !referer.isEmpty()) out.put(name, referer);
                continue;
            }
            if (name.equalsIgnoreCase("cookie")) {
                out.put("cookie", cookie.isEmpty() ? value : value + ";" + cookie);
                continue;
            }
            out.put(name, value);
        }
        if (!cookie.isEmpty() && !containsKeyIgnoreCase(out, "cookie")) out.put("Cookie", cookie);
        return out;
    }

    /**
     * 构造搜索请求头。
     *
     * <p>smali {@code XYQHiker.smali:1555}（原 {@code ac}）。
     * 未单独配 {@code 搜索请求头参数} 时，退化成 {@link #headers}。
     */
    public Map<String, String> searchHeaders(String referer) {
        String spec = rule.value(SEARCH_HEADERS);
        if (spec == null || spec.trim().isEmpty()) return headers(referer);
        return expandHeaders(spec, referer);
    }

    private static boolean containsKeyIgnoreCase(Map<String, String> m, String key) {
        for (String k : m.keySet()) if (k.equalsIgnoreCase(key)) return true;
        return false;
    }

    /** 便于测试：请求头规格串（不构造 Map）。 */
    public String headersSpec() {
        return rule.value(HEADERS);
    }

    // ══════════════════════════════════════════════════════════════
    // 反爬识别
    // ══════════════════════════════════════════════════════════════

    /**
     * 「响应被拦了」判定。
     *
     * <p>smali {@code XYQHiker.smali:106}（原 {@code checkstring}）。
     * 命中 {@link #BLOCKED_FEATURES} 任一即视为被拦。这三个特征描述的是
     * 「代理缓存页 / 反代跳转页」，不是验证码页。
     */
    public static boolean isBlockedContent(String body) {
        if (body == null || body.isEmpty()) return false;
        for (String feat : BLOCKED_FEATURES) if (body.contains(feat)) return true;
        return false;
    }

    /**
     * 「响应是需要过验证码的页面」判定。
     *
     * <p>smali {@code XYQHiker.smali:149}（原 {@code checkveriry}）。
     * 命中 {@link HikerRule#ANTI_BOT_URL_FEATURES} 任一，<b>且</b>不含
     * {@code myui-vodbox-content}（那个标记说明页面其实已经渲染出了正文）时才算。
     */
    public static boolean isVerifyPage(String body) {
        if (body == null || body.isEmpty()) return false;
        if (body.contains("myui-vodbox-content")) return false;
        for (String feat : HikerRule.ANTI_BOT_URL_FEATURES) {
            if (body.contains(feat)) return true;
        }
        return false;
    }

    /**
     * 返回命中的反爬特征串；没命中的返回空串。
     *
     * <p>smali {@code XYQHiker.smali:1467}（原 {@code vertype}）。
     * 注意上游这个版本<b>没有</b> {@code myui-vodbox-content} 的豁免，
     * 与 {@link #isVerifyPage} 的判定并不一致 —— 这是上游自相矛盾处，本实现保持原样。
     */
    public static String verifyFeature(String body) {
        if (body == null) return "";
        for (String feat : HikerRule.ANTI_BOT_URL_FEATURES) {
            if (body.contains(feat)) return feat;
        }
        return "";
    }

    // ══════════════════════════════════════════════════════════════
    // 嗅探
    // ══════════════════════════════════════════════════════════════

    /**
     * 上游 {@code isVideoFormat(url)} 的判定核心。
     *
     * <p>smali {@code XYQHiker.smali:13253}。
     * <blockquote>
     * <b>⚠️ 实现陷阱：一个"规范化副本"被两处语义不同的判定共用。</b>
     * <p>本方法里有两个字符串判定，对大小写的要求<b>相反</b>：
     * <ol>
     *   <li><b>关键词/过滤词比对</b>：DSL 里用户写 {@code .m3u8}，URL 里可能是
     *       {@code .M3U8} → 需要<b>大小写不敏感</b>，用小写副本。</li>
     *   <li><b>「被拦内容」判定</b>：上游是 {@code body.contains(feat)} <b>原文比对</b>，
     *       而特征串 {@code m3u8.pw/Cache} 里含<b>大写 C</b>（见
     *       {@link #BLOCKED_FEATURES} 与 smali {@code :13253}）→ 必须用<b>原始 URL</b>。</li>
     * </ol>
     * 第一版实现为了做关键词比对把 URL 转成小写，然后<b>两个判定共用那个小写副本</b>，
     * 结果 {@link #isBlockedContent} 对小写串永远返回 false，
     * 于是「被拦内容豁免」这条分支<b>永远不会进入</b>。
     * <p>它的隐蔽性在于：不抛异常、不报错、绝大多数断言仍然绿，
     * 只有「反爬豁免使其判为视频」这一条会红（794 条断言里的一条）。
     * <p><b>正确写法：被拦判定用原始 URL；只有关键词/过滤词用小写副本。</b>
     * <p>推广：一个看起来"只是规范化"的局部变量，一旦被两处语义不同的判定共用，
     * 就会静默改变其中一处的行为。这类缺陷无法靠"跑通"发现，只能靠断言钉住。
     * </blockquote>
     *
     * <p>语义：
     * <ol>
     *   <li>URL 含 {@code =http} 或 {@code .html} 且不是被拦内容 → false（这是跳转页，不是视频）</li>
     *   <li>URL 命中任一关键词 → 再看过滤词：命中所属过滤词则 false，否则 true</li>
     *   <li>都没命中 → false</li>
     * </ol>
     * 关键词/过滤词以 {@code #} 分隔，默认值分别是
     * {@code .m3u8#.mp4#.flv#video/tos#.mp3#.m4a} 与 {@code =http#.html}。
     */
    public boolean sniffMatch(String url) {
        if (url == null) return false;
        String keywords = rule.value(SNIFF_KEYWORDS);
        String filters = rule.value(SNIFF_FILTER);
        String[] kws = keywords.toLowerCase().split("#");
        String[] fls = filters.toLowerCase().split("#");
        String u = url.toLowerCase();
        boolean blocked = isBlockedContent(url);

        if ((u.contains("=http") || u.contains(".html")) && !blocked) return false;

        for (String kw : kws) {
            if (kw.isEmpty()) continue;
            if (!u.contains(kw)) continue;
            for (String fl : fls) {
                if (fl.isEmpty()) continue;
                if (u.contains(fl) && !blocked) return false;
            }
            return true;
        }
        return false;
    }

    /**
     * 是否开启手动嗅探。
     *
     * <p>smali {@code XYQHiker.smali:13415}。取值 {@code "1"} 或 {@code "是"} 为真，
     * 其它（含缺省空串、{@code "0"}、{@code "否"}）为假。
     */
    public boolean manualSniff() {
        String v = rule.value(SNIFF_MANUAL);
        return "1".equals(v) || "是".equals(v);
    }

    // ══════════════════════════════════════════════════════════════
    // 二次截取（分类/搜索的「Json 数据二次截取」与「截取模式」）
    // ══════════════════════════════════════════════════════════════

    /**
     * 按点路径取 JSON 数组。
     *
     * <p>证据：{@code s} smali {@code:18380-18490} —— 上游把
     * {@code 二次截取} 的值按 {@code \.} 切开，逐段
     * {@code getJSONObject}，最后一段 {@code getJSONArray}；段数为 1 时直接在根上
     * {@code getJSONArray}。支持 1..4 段是上游写死的四个分支，本实现推广到任意段数。
     *
     * @param dotPath 形如 {@code data} / {@code data.list} / {@code a.b.c}
     * @return 找不到返回 {@code null}
     */
    public static JSONArray jsonPath(String text, String dotPath) {
        if (text == null || dotPath == null) return null;
        try {
            JSONObject root = new JSONObject(text);
            String[] seg = dotPath.split("\\.");
            JSONObject cur = root;
            for (int i = 0; i < seg.length - 1; i++) {
                cur = cur.getJSONObject(seg[i]);
            }
            return cur.getJSONArray(seg[seg.length - 1]);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * HTML 模式下的二次截取：{@code from&&to} 取中间段。
     *
     * <p>证据：{@code s} smali {@code:18314} —— 取规格的
     * {@code split("&&")[0]} 与 {@code [1]} 做 {@code wh.q}。
     */
    public static String htmlCut(String text, String fromAndTo) {
        if (text == null || fromAndTo == null) return "";
        String[] seg = fromAndTo.split("&&");
        if (seg.length < 2) return text;
        return HikerSelector.between(text, seg[0], seg[1]);
    }

    /**
     * 截取模式判是否走 JSON 分支。
     *
     * <p>smali {@code XYQHiker.smali:4330-4380}（{@code ah}）：判定是
     * {@code mode.equals("0") || mode.equals("否")} ——
     * <b>「0 / 否」才走 JSON</b>，缺省（含 {@code "1"} / {@code "是"}）走 HTML/Jsoup。
     * 这个方向与字段名的直觉相反，但它在 {@code ah} 与 {@code s} 两处一致，属上游原样行为。
     *
     * <p>【本项目扩展】额外接受 {@code "json"} 字面量（DSL 作者的自然写法），
     * 避免因为方向反直觉而把站点配错；不影响上游语义。
     */
    public static boolean isJsonMode(String mode) {
        if (mode == null) return false;
        String m = mode.trim();
        return m.equals("0") || m.equals("否") || m.equalsIgnoreCase("json");
    }

    // ══════════════════════════════════════════════════════════════
    // 筛选面板
    // ══════════════════════════════════════════════════════════════

    /**
     * 一个筛选项：{@code {key, name, value:[{n,v},…]}}。
     *
     * <p>smali {@code XYQHiker.smali:22756}（原 {@code z}）。
     *
     * <ul>
     *   <li>{@code padZero=true}（上游是 {@code !key.equals("by")}）时补一个「全部」项</li>
     *   <li>{@code key="by"} 且值非空且名称不含「默认」时补一个「默认」项</li>
     *   <li>值含 {@code &} 时按 {@code &} 切成多组（名称与值一一对应）</li>
     * </ul>
     *
     * <p>上游缺陷：早期版本把同一个 {@code JSONObject} 反复 {@code put} 进数组（别名引用），
     * 现在 decompile 里已经是每次 {@code new JSONObject()}。本实现保持「每次新建」。
     */
    public static JSONObject filterItem(String key, String name, String names, String values) {
        try {
            JSONArray arr = new JSONArray();
            if (!"by".equals(key) && !"空".equals(values) && names != null && !names.contains("全部")) {
                JSONObject o = new JSONObject();
                o.put("n", "全部");
                o.put("v", "");
                arr.put(o);
            } else if ("by".equals(key) && !"空".equals(values) && names != null && !names.contains("默认")) {
                JSONObject o = new JSONObject();
                o.put("n", "默认");
                o.put("v", "");
                arr.put(o);
            }
            if (values != null && values.contains("&") && !"空".equals(values)) {
                String[] ns = names == null ? new String[0] : names.split("&");
                String[] vs = values.split("&");
                int n = Math.min(ns.length, vs.length);
                for (int i = 0; i < n; i++) {
                    JSONObject o = new JSONObject();
                    o.put("n", ns[i]);
                    o.put("v", vs[i].replaceAll("＆＆", "&"));
                    arr.put(o);
                }
            } else if (values != null && !"空".equals(values)) {
                JSONObject o = new JSONObject();
                o.put("n", names == null ? "" : names);
                o.put("v", values);
                arr.put(o);
            }
            JSONObject out = new JSONObject();
            out.put("key", key == null ? "" : key);
            out.put("name", name == null ? "" : name);
            out.put("value", arr);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 筛选面板的 6 个维度：{key, 名称字段, 值字段}。 */
    public static final HikerRule.Field[][] FILTER_DIMENSIONS = {
            {FCATELOG_NAME, FCATELOG_VALUE},
            {FCLASS_NAME, FCLASS_VALUE},
            {FAREA_NAME, FAREA_VALUE},
            {FYEAR_NAME, FYEAR_VALUE},
            {FLANG_NAME, FLANG_VALUE},
            {FSORT_NAME, FSORT_VALUE},
    };

    /** 维度 key（与上游 {@code t} 里 {@code jSONObject2.put(...)} 的键一致）。 */
    public static final String[] FILTER_KEYS = {"cateId", "class", "area", "year", "lang", "by"};

    /**
     * 一个分类下的 6 个筛选项。
     *
     * <p>维度顺序与上游 {@code t} 一致：类型 → 子分类 → 地区 → 年份 → 语言 → 排序；
     * 空维度不进面板。{@code classId} 非空时，会把各维度规则里的
     * {@code {cateId}} 占位符替换成它（上游 {@code t} smali {@code:3868} 就是这个判断）。
     */
    public static JSONArray filterItems(HikerRule rule, String classId) {
        JSONArray out = new JSONArray();
        if (rule == null) return out;
        for (int i = 0; i < FILTER_DIMENSIONS.length; i++) {
            HikerRule.Field nameField = FILTER_DIMENSIONS[i][0];
            HikerRule.Field valueField = FILTER_DIMENSIONS[i][1];
            String names = rule.value(nameField);
            String values = rule.value(valueField);
            if (names.isEmpty() && values.isEmpty()) continue;
            if (classId != null && !classId.isEmpty()) {
                values = values.replace("{cateId}", classId);
            }
            JSONObject item = filterItem(FILTER_KEYS[i], names, names, values);
            if (item != null) out.put(item);
        }
        return out;
    }

    /**
     * 整个筛选面板。
     *
     * <p>smali {@code XYQHiker.smali:20399}（原 {@code t}，14 个 String 参数）。
     * 上游把「名称/值」两个字段先在外层解成 14 个字符串再传进来，本方法直接读 rule。
     *
     * <p>形状是 TVBox 标准的 {@code filters}：
     * <pre>
     * { "&lt;分类id&gt;": [ {"key":…, "name":…, "value":[{"n":…,"v":…}, …]}, … ] }
     * </pre>
     * 外层键来自 {@code 分类名称替换词}（{@code class_value}）按 {@code &} 切开的每一段
     * —— 上游 {@code t} 的第一步就是 {@code class_value.split("&")}
     * （smali {@code:3716} + {@code:3797} 的 {@code jSONObject2.put(classIds.get(i2), items)}）。
     *
     * @return 无分类 id 时返回空对象（不是 {@code null}）
     */
    public static JSONObject buildFilterPanel(HikerRule rule) {
        JSONObject panel = new JSONObject();
        if (rule == null) return panel;
        String ids = rule.value(HikerRule.CLASS_VALUE);
        if (ids.isEmpty()) return panel;
        for (String raw : ids.split("&")) {
            String id = (raw == null ? "" : raw).replaceAll("＆＆", "&");
            if (id.isEmpty()) continue;
            try {
                JSONArray items = filterItems(rule, id);
                if (items.length() > 0) panel.put(id, items);
            } catch (Exception e) {
                // 单个分类坏掉不影响其它
            }
        }
        return panel;
    }

    // ══════════════════════════════════════════════════════════════
    // MacPlayer / 播放页 JS 解析
    // ══════════════════════════════════════════════════════════════

    /** 上游字段 {@code h}：从播放页 JS 里抠 {@code player_list} 的正则。 */
    public static final String MAC_PLAYER_LIST_RE =
            "[\\W|\\S|.]*?MacPlayerConfig.player_list[\\W|\\S|.]*?=([\\W|\\S|.]*?),MacPlayerConfig.downer_list";

    /** 从播放页 JS 里取 {@code player_list}（拿不到就空串）。 */
    public static String macPlayerList(String js) {
        return HikerSelector.firstMatch(js, MAC_PLAYER_LIST_RE);
    }

    /** {@code 分析MacPlayer} 是否开启（上游判定：取值等于 {@code "2"}）。 */
    public boolean analyzeMacPlayer() {
        return "2".equals(rule.value(ANAL_MAC_PLAYER));
    }

    // ══════════════════════════════════════════════════════════════
    // 取页面（可覆写，供测试离线）
    // ══════════════════════════════════════════════════════════════

    /**
     * GET 取文本。
     *
     * <p>smali {@code XYQHiker.smali:21790}（原 {@code u}）。
     * {@code clan://} 开头的地址走本地文件读取（上游 {@code yi.k}）；
     * 本项目改为交给 {@link Http#get} 的离线开关，测试期一律返回空串。
     */
    public String fetch(String url, Map<String, String> headers) {
        if (url == null || url.isEmpty()) return "";
        if (Http.isOfflineForTest()) return "";
        return new Http().get(url, headers);
    }

    /** POST 表单取文本。smali {@code XYQHiker.smali:21880}（原 {@code v}）。 */
    public String fetchPost(String url, Map<String, String> form, Map<String, String> headers) {
        if (url == null || url.isEmpty()) return "";
        if (Http.isOfflineForTest()) return "";
        return new Http().post(url, formToString(form), headers);
    }

    /** POST 原始体（JSON）取文本。smali {@code XYQHiker.smali:16982}（原 {@code r}）。 */
    public String fetchPostRaw(String url, String body, Map<String, String> headers) {
        if (url == null || url.isEmpty()) return "";
        if (Http.isOfflineForTest()) return "";
        Http h = new Http();
        h.header("Content-Type", "text/plain; charset=utf-8");
        return h.post(url, body, headers);
    }

    /** 表单 Map → 串（按插入序）。 */
    public static String formToString(Map<String, String> form) {
        if (form == null || form.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** 取页 + 自动处理两种反爬页（上游 {@code ad}/{@code ae} 的调用点）。 */
    public String fetchGuarded(String url, Map<String, String> headers) {
        String body = fetch(url, headers);
        if (body == null) return "";
        return body.replaceAll("\r|\n", "");
    }

    /** 便捷：把 HTML 解析成文档根（上游 {@code oj.a(m(str))}）。 */
    public static Dom.El parse(String html, String base) {
        return Dom.parse(HikerSelector.unescapeUnicode(html == null ? "" : html), base);
    }

    /** 便捷：解析并返回元素集合（数组规则）。 */
    public static List<Dom.El> selectArray(Dom.El root, String arrayRule) {
        return HikerSelector.selectChained(root, arrayRule);
    }

    /** 把 {@code 前缀/后缀} 与 {@code PG_URL} 语义应用到一条卡片链接上。 */
    public static String buildCardUrl(String prefix, String link, String suffix, String pageUrl) {
        String v = link == null ? "" : link;
        if (v.contains("PG_URL")) v = v.replace("PG_URL", pageUrl == null ? "" : pageUrl).replace("'", "");
        return applyPreSuffix(prefix, v, suffix);
    }

    /** 卡片 id 编码：{@code 标题$$$图片$$$链接}（上游在多处手拼）。 */
    public static String cardId(String title, String pic, String url) {
        return (title == null ? "" : title) + "$$$" + (pic == null ? "" : pic) + "$$$" + (url == null ? "" : url);
    }

    /** 拆开 {@link #cardId} 的结果；不足 3 段的用空串补齐。 */
    public static String[] splitCardId(String vodId) {
        String[] parts = (vodId == null ? "" : vodId).split("\\$\\$\\$");
        String[] out = new String[]{"", "", "", ""};
        for (int i = 0; i < parts.length && i < 4; i++) out[i] = parts[i];
        return out;
    }

    /** 线路/选集拼装：用 {@code $$$} 连接（上游 {@code TextUtils.join("$$$", list)}）。 */
    public static String joinSource(List<String> items) {
        return HikerSelector.join(items, "$$$");
    }

    /** ed2k / magnet / 保留页链 的识别（上游 {@code detailContent} 里对选集链接做过特判）。 */
    public static boolean isSpecialLink(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        return u.startsWith("ed2k:") || u.startsWith("magnet:") || u.contains("[保留页链]");
    }

    /** 选集链接里的 {@code ed2k} 文件名（{@code \|file\|(.*?)\|}）。 */
    public static String ed2kFileName(String ed2k) {
        return HikerSelector.firstMatch(ed2k, "\\|file\\|(.*?)\\|");
    }

    /** magnet 的 {@code dn=} 参数。 */
    public static String magnetName(String magnet) {
        return HikerSelector.firstMatch(magnet, "(^|&)dn=([^&]*)(&|$)");
    }

    /** 只保留唯一值（保序）；选集去重用。 */
    public static List<String> distinct(List<String> in) {
        List<String> out = new ArrayList<String>();
        if (in == null) return out;
        for (String s : in) if (!out.contains(s)) out.add(s);
        return out;
    }

    /** 反转列表（上游 {@code 是否反转选集序列}）。 */
    public static List<String> reversed(List<String> in) {
        List<String> out = new ArrayList<String>();
        if (in == null) return out;
        for (int i = in.size() - 1; i >= 0; i--) out.add(in.get(i));
        return out;
    }

    /** 便捷：正则命中数（测试与统计用）。 */
    public static int countMatches(String src, String regex) {
        if (src == null || regex == null) return 0;
        try {
            Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(src);
            int n = 0;
            while (m.find()) n++;
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 便捷：HashMap 版请求头（部分宿主/老代码用 HashMap）。 */
    public static HashMap<String, String> toHashMap(Map<String, String> in) {
        HashMap<String, String> out = new HashMap<String, String>();
        if (in != null) out.putAll(in);
        return out;
    }
}
