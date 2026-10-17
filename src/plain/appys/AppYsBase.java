package com.example.tvbox.plain.appys;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AppYs 族公共基类 —— {@code AppYs} 与 {@code AppYsV2} 的<b>同族共享部分</b>。
 *
 * <p>反编译原文: {@code ae/decompiled/AppYs.java}（1291 行）、{@code ae/decompiled/AppYsV2.java}（974 行）。
 *
 * <h2>为什么抽基类（动手依据：token 级规范化 diff，可复现）</h2>
 *
 * <p><b>口径</b>：把两份反编译产物的单个方法体取出来，做「注释剥离 +
 * jadx 临时量名归一化（{@code str/i/j/k/c/bArr/jSONObject/jSONArray/map/arrayList/sb/obj/z/e/th/it}
 * 及其带数字后缀的形式统一成一个 token）+ 字符级 LCS」，相似度 = {@code 2*LCS/(|A|+|B|)}。
 *
 * <p><b>复现方式</b>：{@code AppYsTest} 的第 [Z] 节就是这段算法的 Java 实现，
 * 直接读 {@code ae/decompiled/AppYs.java} 与 {@code ae/decompiled/AppYsV2.java} 现算，
 * 所以下面这些数字是<b>测试跑出来的</b>，不是人工判断。命令：
 * <pre>
 *   bash ae/tools/subcompile.sh --tests AppYsTest maintain/src/plain/appys
 *   # 输出里的 "      categoryContent    sim=…" 那几行
 * </pre>
 *
 * <p><b>5 个契约方法</b>（AppYs 名 ↔ AppYsV2 名同形）：
 * <pre>
 *   categoryContent    0.9859
 *   detailContent      0.9118    ← 最低；差异全在「V2 多一层变量提取」
 *   homeContent        0.9807
 *   homeVideoContent   0.9662
 *   searchContent      0.9680
 * </pre>
 *
 * <p><b>5 对静态辅助</b>（名字逐个核对过，不是按位置猜的）：
 * <pre>
 *   l(JSONObject,String,ArrayList) ↔ g(…)   0.983   递归收集数组
 *   n(String):HashMap              ↔ h(…)   0.993   UA 推断
 *   p(String,String):String        ↔ i(…)   0.994   搜索 URL 构造
 *   q(String):boolean              ↔ j(…)   0.970   伦理/情色/福利判定
 *   r(JSONArray):String            ↔ k(…)   0.986   数组 join
 * </pre>
 * 注意这 5 对的相似度是 <b>0.97–0.994 而不是 1.000</b> —— 差异来自 jadx 对同一段代码
 * 在两份产物里给出的临时量名/语句拆分不同（例如 {@code strSplit} 与 {@code split}），
 * 复核时不要把它当成"算法不同"。
 *
 * <p><b>4 对实例辅助</b>：
 * <pre>
 *   u(String):String      ↔ o(…)   0.984   .vod 详情 URL
 *   v(String):String      ↔ p(…)   0.986   分类 URL
 *   w(String,JSONObject)  ↔ q(…)   0.994   筛选器 DSL 生成
 *   y(String):String      ↔ s(…)   0.988   详情 URL（含 3 条站点特判）
 * </pre>
 *
 * <p><b>反向证据（这三块才是真分叉）</b>：下列方法在 V2 侧的<b>最佳</b>匹配都 &lt; 0.60，
 * 即 V2 里根本没有对应物 —— 这就是「不进基类、各自实现」的依据：
 * <pre>
 *   o(String,String,String):String   最佳 0.545  解析前缀表 + 伪 baidu 播放解析
 *   m(String,JSONObject):void        最佳 0.427  同上（播放对象装配）
 *   s(String):String                 最佳 0.437  11 条线路别名表
 *   t(…):void                        最佳 0.589  详情装配（V2 改了四块里的三块）
 *   x(String,String):String          最佳 0.530  解析前缀查表（V2 折进 tryCandidates）
 * </pre>
 * 两条独立的"逐字节"事实再双向印证同族：
 * <ol>
 *   <li>{@code w()} 与 {@code q()} 的 360 字符兜底筛选字面量<b>逐字节相同</b>；</li>
 *   <li>{@code l()}/{@code g()} 递归收集 JSON 数组的写法（连 {@code JSONException} 吞掉的方式）一致。</li>
 * </ol>
 *
 * <h2>真正的算法分叉（不进基类，各自实现）</h2>
 * <table border="1">
 *   <tr><th></th><th>AppYs</th><th>AppYsV2</th></tr>
 *   <tr><td>ext 解析</td><td>整串当基址（{@code this.j = str}）</td>
 *       <td>{@code split("###")}，{@code f[0].trim()} 当基址，{@code f[1]} 是模式标记</td></tr>
 *   <tr><td>线路名</td><td>经 {@code s()} 重命名（youku→优酷M / qq→腾讯M … 共 11 条）</td>
 *       <td>不重命名，直接用 {@code name}；xgapp 分支优先用 {@code code}</td></tr>
 *   <tr><td>解析前缀</td><td>{@code Map<线路,单一前缀>}；{@code .vod} 分支按 21 条 {@code P_JX}
 *       模式<em>排除</em>后取首个，并按 3 条站点特判改写（{@code vip.aotian.love}→{@code vip.gaotian.love} 等）</td>
 *       <td>{@code Map<线路,List<候选前缀>>}（{@code parse} 与 {@code parse2} 合并去重）；从
 *       {@code url=} 里原地切出前缀</td></tr>
 *   <tr><td>playerContent</td><td>把 线路→前缀 与 播放地址 拼成 <b>baidu 搜索态 URL</b>，
 *       再走 {@code m()} 的 20+ 站点分支（含 3 次跟随 Location 重定向）</td>
 *       <td>逐候选前缀请求解析接口 → {@link AppYsVideo#resolve} 规范化 → 返回
 *       {@code {header,url}}；都不成则退化为 {@code {parse:1,jx:"1",url}}</td></tr>
 * </table>
 * <p><b>结论：同族（共享 5 个契约方法与 8 个辅助方法的逐字逻辑），但 playerContent 与「解析前缀」的
 * 数据结构是两套实现。</b>因此这里抽公共基类 + 两个子类各实现分叉部分，而不是复制两份 90% 相同的代码。
 *
 * <h2>请求头（{@code n()}/{@code h()}：逐字相同）</h2>
 * <pre>
 *   api.php/app | xgapp | freekan          → User-Agent: Dart/2.14 (dart:io)
 *   zsb fkxs xays xcys szys dxys ytys qnys → User-Agent: Dart/2.15 (dart:io)
 *   其它：含 ".vod" → okhttp/4.1.0；否则 → Dalvik/2.1.0
 * </pre>
 *
 * <h2>ext 三层分类</h2>
 * <table border="1">
 *   <tr><th>类</th><th>键</th><th>说明</th></tr>
 *   <tr><td><b>B 类</b>（默认值 + ext 可覆盖）</td>
 *       <td>无 JSON 键 —— ext <b>整串</b>就是基址</td>
 *       <td>{@link com.example.tvbox.common.Ext#url()} 的 url/host/site 都能给基址；
 *           V2 额外支持 {@code <基址>###<模式>} 两段式（见 {@link AppYsV2Plain#siteFromExt}）。
 *           上游两版都是 {@code optString} 无兜底，所以没有硬编码域名可内联</td></tr>
 *   <tr><td><b>C 类</b>（纯硬编码）</td>
 *       <td>UA 规则、36 条正则、21 条解析器黑名单、360 字符兜底筛选表、{@code #PN#} 分页占位符</td>
 *       <td>已在 {@link #defaultHeaders} / {@link #FILTERS_FALLBACK} 等常量中内联</td></tr>
 *   <tr><td><b>A 类</b>（ext 必须提供）</td>
 *       <td>无</td>
 *       <td>本族不需要密钥；缺基址只是「不能用」，没有必须下发的密钥</td></tr>
 * </table>
 *
 * <h2>发现的上游缺陷（本实现已修，均有断言钉住）</h2>
 * <ol>
 *   <li><b>{@code AppYsV2.l()} 对 null 数组直接崩</b>：{@code String[] strArr = this.f;} 之后
 *       立刻 {@code strArr.length}。ext 为 {@code null} 时 {@code init} 的
 *       {@code try/catch(Throwable)} 把 {@code f} 留在 null，随后每次响应都会走一次
 *       {@code l()} → NPE → 被外层 catch 吞成空串，<b>整个插件静默失效</b>。
 *       本实现在基类 {@link #logMode} 里做 null 检查。</li>
 *   <li><b>{@code AppYsV2.l()} 是死代码</b>：{@code strArr[1].equals("nftv")} 的结果没人用，
 *       也不抛异常（不是校验）。保留为 {@link #logMode()} 并注明：这是「读一下第 2 段」的残留。</li>
 *   <li><b>{@code AppYs.t()} 的 {@code .vod} 分支重复 put</b>：{@code map.put(from,prefix)} 在
 *       {@code if (matcher3.find())} 内部与外部各写一次，第二次是无条件覆盖且值未变 —— 无害冗余，
 *       本实现只写一次。</li>
 *   <li><b>{@code AppYs.m()} 的 {@code split("wd=")[1]} 无保护</b>：任何不含 {@code wd=} 的入参
 *       会 {@code ArrayIndexOutOfBoundsException}。本实现改为 {@link #substringAfter}（找不到时退化为整串）。</li>
 *   <li><b>{@code homeVideoContent} 的 {@code l(jSONObject,"vlist")} 在数组元素不是对象时
 *       {@code getJSONObject} 抛异常</b>，整段退化为空。本实现逐元素判类型跳过。</li>
 * </ol>
 */
public abstract class AppYsBase extends BaseSpider {

    /** 日志 TAG */
    public static final String TAG = "AppYs";

    // ── 上游 36 条正则（c/d/e/f/g + h[21] + i[7]）──────────────

    /** {@code api.php/…/vod}：V2 用它判断「扁平淡出型」详情响应 */
    public static final Pattern P_VOD_SEG = Pattern.compile("api\\.php/.*?/vod");
    /** {@code api.php/….vod}：V2 用它判断「vod 后缀」型基址（{@code x()} 里也有一次同义判断） */
    public static final Pattern P_VOD_EXT = Pattern.compile("api\\.php/.+?\\.vod");
    /** {@code /.+?.+=}：判断候选解析前缀「像不像带参接口」 */
    public static final Pattern P_QUERY = Pattern.compile("/.+\\?.+=");
    /** {@code .*(url|v|vid|php?id)=}：抽取解析接口的 url 前缀 */
    public static final Pattern P_URL_KEY = Pattern.compile(".*(url|v|vid|php\\?id)=");
    /** {@code https?://[^/]*}：取主机前缀 */
    public static final Pattern P_HOST = Pattern.compile("https?://[^/]*");
    /** {@code .*(http.*)}：从一串脏文本里捞出第一个 http 地址（{@code m()} 末尾用） */
    public static final Pattern P_FIRST_HTTP = Pattern.compile(".*(http.*)");

    /** 上游 {@code h}：21 条「这些主机不是解析接口」黑名单（{@code AppYs.t()} 用） */
    public static final Pattern[] P_JX = {
            Pattern.compile("jx\\.+huimaojia\\.+com/player"),
            Pattern.compile("py\\.+789pan\\.+cn/player/tm\\.php\\?url="),
            Pattern.compile("ztys\\.+waruanzy\\.+com/player/\\?url="),
            Pattern.compile("yingshi\\.+waruanzy\\.+com/789pan/\\?url="),
            Pattern.compile("vip\\.+parwix\\.+com:4433/player/\\?url="),
            Pattern.compile("api\\.+cxitco\\.+cn"),
            Pattern.compile("/vip\\.+renrenmi.cc"),
            Pattern.compile("yanbing\\.+parwix\\.+com:4433/player"),
            Pattern.compile("json\\.+cantin\\.+cc/apijson\\.php"),
            Pattern.compile("ffdm\\.+miaoletv\\.+com/\\?url="),
            Pattern.compile("vip\\.+sylwl\\.+cn/api/\\?key="),
            Pattern.compile("jx\\.+dikotv\\.+com/\\?url="),
            Pattern.compile("zly\\.+xjqxz\\.+top/player/\\?url="),
            Pattern.compile("5znn\\.+xyz/m3u8\\.+php"),
            Pattern.compile("uid=1735&my="),
            Pattern.compile("api\\.+xkvideo\\.+design/m3u8\\.+php\\?url="),
            Pattern.compile("play\\.+szbodankyy\\.+com/xxoocnmb"),
            Pattern.compile("vip\\.+fj6080\\.+xyz/player/\\?url="),
            Pattern.compile("a\\.+dxzj88\\.+com/jiexi"),
            Pattern.compile("host\\.+q-q\\.+wang/api"),
            Pattern.compile("保佑")
    };

    /** 上游 {@code i[7]}：判断一段 HTML 里「有没有播放器」的 7 个特征 */
    public static final Pattern[] P_HTML = {
            Pattern.compile("player=new"),
            Pattern.compile("<div id=\"video\""),
            Pattern.compile("<div id=\"[^\"]*?player\""),
            Pattern.compile("//视频链接"),
            Pattern.compile("HlsJsPlayer\\("),
            Pattern.compile("<iframe[\\s\\S]*?src=\"[^\"]+?\""),
            Pattern.compile("<video[\\s\\S]*?src=\"[^\"]+?\"")
    };

    /**
     * 上游 {@code w()}/{@code q()} 的兜底筛选表（360 字符，两版<b>逐字节相同</b>）。
     *
     * <pre>
     *   分类+全部=+电影=movie+连续剧=tvplay+综艺=tvshow+动漫=comic+4K=movie_4k+体育=tiyu      ← 分类表（不是筛选）
     *   筛选class+全部=+喜剧+…+惊悚+惊悚+伦理+情色+福利+三级+儿童+网络电影                    ← 「惊悚」重复两次，上游如此
     *   筛选area+全部=+大陆+香港+…+其他
     *   筛选year+全部=+2022+…+2000
     * </pre>
     * 只有「非 {@code .vod} 且 非 {@code api.php/app|xgapp}」的基址才用它；
     * 另外 {@link #homeContent} 在 {@code navUrl} 为空时会拿它的<b>第一行</b>当分类表。
     */
    public static final String FILTERS_FALLBACK =
            "分类+全部=+电影=movie+连续剧=tvplay+综艺=tvshow+动漫=comic+4K=movie_4k+体育=tiyu\n"
            + "筛选class+全部=+喜剧+爱情+恐怖+动作+科幻+剧情+战争+警匪+犯罪+动画+奇幻+武侠+冒险+枪战+恐怖+悬疑+惊悚+经典+青春+文艺+微电影+古装+历史+运动+农村+惊悚+惊悚+伦理+情色+福利+三级+儿童+网络电影\n"
            + "筛选area+全部=+大陆+香港+台湾+美国+英国+法国+日本+韩国+德国+泰国+印度+西班牙+加拿大+其他\n"
            + "筛选year+全部=+2022+2021+2020+2019+2018+2017+2016+2015+2014+2013+2012+2011+2010+2009+2008+2007+2006+2005+2004+2003+2002+2001+2000";

    /** {@code 排序} 组的固定尾巴（{@code .vod} 分支专用） */
    public static final String FILTERS_SORT_TAIL = "\n排序+全部=+最新=time+最热=hits+评分=score";

    // ── 实例状态 ────────────────────────────────────────────

    /** ext 原文（未 trim） */
    protected String rawExt = "";

    /** V2 的 {@code ###} 分段结果；AppYs 不切分，这里仍保留一份便于排障 */
    protected String[] extParts = new String[]{""};

    /** 站点基址（由 {@link #siteFromExt} 决定，V2 会 trim） */
    protected String site = "";

    /** 线路名 → 解析前缀候选（保序、去重）。AppYs 每线路只放 1 个；V2 放 parse+parse2 合并结果 */
    protected final LinkedHashMap<String, List<String>> parsers = new LinkedHashMap<>();

    /** 最近一次 {@link #fetchHop} 读到的 {@code Location}（空串表示没有） */
    protected String lastLocation = "";

    /** 弹幕代理前缀（宿主 {@code Proxy.getUrl()} 的替身），空串表示不挂弹幕 */
    protected String proxyBase = "";

    /** 构造 */
    protected AppYsBase() {
        super();
    }

    // ── 生命周期 ────────────────────────────────────────────

    /**
     * 初始化：吸收 ext、切分 {@code ###}、定基址。
     *
     * <p>上游 AppYs 把 ext 整串存进 {@code this.j}；AppYsV2 额外做
     * {@code str.split("###")} 且整个 try/catch(Throwable)。基址取值交给
     * {@link #siteFromExt}（两条分叉的唯一入口）。
     *
     * @param context 宿主上下文，可为 null
     * @param extend  ext 原文，可为 null
     */
    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        this.rawExt = extend == null ? "" : extend;
        if (this.rawExt.isEmpty()) {
            this.extParts = new String[]{""};
        } else {
            try {
                this.extParts = this.rawExt.split("###");
            } catch (Throwable e) {
                this.extParts = new String[]{this.rawExt};
            }
        }
        this.site = siteFromExt(this.rawExt);
        if (this.site.isEmpty()) {
            Log.w(tag(), "ext 为空 → 基址为空，所有接口都会返回空响应");
        }
    }

    /**
     * 子类决定基址怎么从 ext 里取 —— 这是两版唯一的「配置层」分叉点。
     *
     * @param ext ext 原文（非 null）
     * @return 站点基址
     */
    protected abstract String siteFromExt(String ext);

    /**
     * 当前基址。
     *
     * @return 基址（可能为空串）
     */
    public String site() {
        return site;
    }

    /**
     * 线路名 → 解析前缀候选（只读视图，保序）。
     *
     * @return 不可变 map
     */
    public Map<String, List<String>> parsers() {
        return Collections.unmodifiableMap(parsers);
    }

    /**
     * 记录一条「线路名 → 解析前缀」。
     *
     * <p>空值/重复值不入表（V2 的去重语义；AppYs 的重复 put 本就幂等）。
     *
     * @param from   线路名
     * @param prefix 解析前缀，空串忽略
     */
    public void addParser(String from, String prefix) {
        if (from == null || prefix == null || prefix.isEmpty()) {
            return;
        }
        List<String> list = parsers.get(from);
        if (list == null) {
            list = new ArrayList<>();
            parsers.put(from, list);
        }
        if (!list.contains(prefix)) {
            list.add(prefix);
        }
    }

    /**
     * 取线路的第一个解析前缀（AppYs 语义：它每线路只有一个）。
     *
     * @param from 线路名
     * @return 前缀；没有返回空串
     */
    protected String firstParser(String from) {
        List<String> list = parsers.get(from);
        return (list == null || list.isEmpty()) ? "" : list.get(0);
    }

    /**
     * 注入弹幕代理前缀（宿主 {@code Proxy.getUrl()} 的替身）。
     *
     * @param base 形如 {@code http://127.0.0.1:9978/proxy}；null 按空串
     */
    public void setProxyBase(String base) {
        this.proxyBase = base == null ? "" : base.trim();
    }

    /**
     * 当前弹幕代理前缀。
     *
     * @return 非 null，未注入时为空串
     */
    public String proxyBase() {
        return proxyBase;
    }

    /**
     * 钩子：上游 AppYsV2 每拿到一次响应就调一次 {@code l(String)}，AppYs 没有这一步。
     *
     * <p>基类的 5 个契约方法在「响应解析成功」之后各调用本方法一次，
     * 因此 {@link AppYsV2Plain} 覆写它就等价于上游的调用点，
     * {@link AppYsPlain} 保持空实现（= 上游不调用）。
     *
     * <p>注意上游 {@code l(String)} 的<b>入参被完全忽略</b>，方法体只读 ext 第 2 段并与
     * {@code "nftv"} 比较，比较结果直接丢弃 —— 是死代码。这里保留为无参钩子并写清这一点。
     */
    protected void logMode() {
        // AppYs 无此步骤
    }

    // ── HTTP（两层可覆写：fetch → fetchWith → fetchHop）────────

    /**
     * 发一次 GET。
     *
     * @param url 完整地址
     * @return 响应体；失败为空串
     */
    public String fetch(String url) {
        return fetchWith(url, Collections.<String, String>emptyMap());
    }

    /**
     * 发一次带头的 GET。
     *
     * @param url     完整地址
     * @param headers 请求头
     * @return 响应体；失败为空串
     */
    public String fetchWith(String url, Map<String, String> headers) {
        return fetchHop(url, headers);
    }

    /**
     * 发一次 GET，并把响应里的 {@code Location} 记到 {@link #lastLocation}。
     *
     * <p>这是上游 {@code yi.n(url, headers, sink)} + {@code yi.h(sink)} 的组合——
     * 上游用「响应头 map + location 取值」手工跟随重定向（{@code followRedirects(false)}），
     * 因为解析接口常返回 302 到真人地址。
     *
     * <p>测试通过覆写本方法注入 fixture（零网络）。
     *
     * @param url     完整地址
     * @param headers 请求头，可为 null
     * @return 响应体（已按 gzip 解压）；失败为空串且 {@link #lastLocation} 为空
     */
    public String fetchHop(String url, Map<String, String> headers) {
        this.lastLocation = "";
        if (url == null || url.trim().isEmpty()) {
            return "";
        }
        HttpURLConnection conn = null;
        try {
            // 与 common/Http 保持一致：new URL(String) 在 Java 20+ 已废弃
            conn = (HttpURLConnection) java.net.URI.create(url.trim()).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(Http.DEFAULT_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(Http.DEFAULT_READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            int code = conn.getResponseCode();
            String loc = conn.getHeaderField("Location");
            this.lastLocation = loc == null ? "" : loc.trim();
            if (code >= 400) {
                return "";
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable e) {
            Log.e(tag(), "fetch failed: " + url, e);
            return "";
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 最近一次 {@link #fetchHop} 的 {@code Location}。
     *
     * @return 头值；没有则为空串
     */
    public String lastLocation() {
        return lastLocation;
    }

    // ── 弹幕 ────────────────────────────────────────────────

    /**
     * 还原宿主 {@code merge.p026a.a.addDanmaku(json)}：解析 JSON 后补 {@code danmaku} 字段。
     *
     * <p>上游实体（证据 {@code ae/work/w-pan/helpers/pancommon/a.java:24}）：
     * {@code put("danmaku", Proxy.getUrl() + "?do=diydanmu")}。
     * 本实现把注入点做成 {@link #setProxyBase}；未注入时原样返回（不写空字段）。
     *
     * <p>入参不是 JSON 对象时原样返回 —— 与上游 {@code catch (Exception e) { return str; }} 一致。
     *
     * @param json 播放对象 JSON 文本
     * @return 追加了 {@code danmaku} 的 JSON；无法处理时原样返回
     */
    public String addDanmaku(String json) {
        if (proxyBase.isEmpty() || json == null || !json.startsWith("{")) {
            return json;
        }
        try {
            JSONObject o = new JSONObject(json);
            o.put("danmaku", proxyBase + "?do=diydanmu");
            return o.toString();
        } catch (Throwable e) {
            return json;
        }
    }

    // ── 上游静态辅助（逐字复刻，两版相同）─────────────────────

    /**
     * 上游 {@code n()}/{@code h()}：按基址特征推 UA。
     *
     * @param base 基址
     * @return 单元素头表 {@code {"User-Agent": …}}
     */
    public static Map<String, String> defaultHeaders(String base) {
        String b = base == null ? "" : base;
        String ua;
        if (b.contains("api.php/app") || b.contains("xgapp") || b.contains("freekan")) {
            ua = "Dart/2.14 (dart:io)";
        } else if (b.contains("zsb") || b.contains("fkxs") || b.contains("xays") || b.contains("xcys")
                || b.contains("szys") || b.contains("dxys") || b.contains("ytys") || b.contains("qnys")) {
            ua = "Dart/2.15 (dart:io)";
        } else {
            ua = b.contains(".vod") ? "okhttp/4.1.0" : "Dalvik/2.1.0";
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", ua);
        return h;
    }

    /**
     * 上游 {@code r()}/{@code k()}：JSON 数组 → 逗号串。
     *
     * @param arr 数组，可为 null
     * @return 逗号连接；元素读取失败返回空串（与上游 {@code catch (JSONException)} 一致）
     */
    public static String joinArray(JSONArray arr) {
        if (arr == null) {
            return "";
        }
        try {
            List<String> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }
            return TextUtils.join(",", list);
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 上游 {@code q()}/{@code j()}：是不是「伦理/情色/福利」这类要过滤掉的分类。
     *
     * @param name 分类名
     * @return true 表示要跳过
     */
    public static boolean isAdultType(String name) {
        return "伦理".equals(name) || "情色".equals(name) || "福利".equals(name);
    }

    /**
     * 上游 {@code l()}/{@code g()}：递归找出所有名为 {@code key} 的 JSON 数组。
     *
     * <p>上游对每个键都 {@code getJSONObject(i)} 式递归，遇到非对象元素会
     * {@code JSONException} 被吞；本实现对数组元素先判类型。
     *
     * @param obj   源对象，可为 null
     * @param key   目标键名
     * @param out   收集容器（原位追加）
     */
    public static void collectArrays(JSONObject obj, String key, List<JSONArray> out) {
        if (obj == null || key == null || out == null) {
            return;
        }
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String k = it.next();
            // 用 optJSONObject/optJSONArray 而不是 opt(...) instanceof：
            // 两者的真 org.json 语义一致，而工程里的 org.json 桩会把嵌套值存成裸 Map/List，
            // `opt()` 拿到的是 Map→instanceof JSONObject 恒 false。用访问器两边都对。
            JSONArray arr = obj.optJSONArray(k);
            JSONObject child = obj.optJSONObject(k);
            if (key.equals(k) && arr != null) {
                out.add(arr);
            }
            if (child != null) {
                collectArrays(child, key, out);
            } else if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject el = arr.optJSONObject(i);
                    if (el != null) {
                        collectArrays(el, key, out);
                    }
                }
            }
        }
    }

    /**
     * 上游 {@code v()}/{@code p()}：分类导航接口。
     *
     * @param base 基址
     * @return 完整地址；未知形态返回空串（调用方据此回落静态分类表）
     */
    public static String navUrl(String base) {
        String b = base == null ? "" : base;
        if (b.contains("api.php/app") || b.contains("xgapp")) {
            return b + "nav?token=";
        }
        if (b.contains(".vod")) {
            return b.contains("iopenyun.com") ? b + "/list?type" : b + "/types";
        }
        return "";
    }

    /**
     * 上游 {@code u()}/{@code o()}：分类列表接口（分类 id 会拼在后面）。
     *
     * @param base 基址
     * @return 前缀
     */
    public static String categoryUrl(String base) {
        String b = base == null ? "" : base;
        if (b.contains("api.php/app") || b.contains("xgapp")) {
            return b.contains("dijiaxia") ? "http://www.dijiaxia.com/api.php/app/video?tid=" : b + "video?tid=";
        }
        if (b.contains(".vod")) {
            return b.contains("iopenyun") ? b + "/list?type=" : b + "?type=";
        }
        return b + "?ac=list&class=";
    }

    /**
     * 上游 {@code y()}/{@code s()}：详情接口（vod id 会拼在后面）。
     *
     * @param base 基址
     * @return 前缀；未知形态返回空串
     */
    public static String detailUrl(String base) {
        String b = base == null ? "" : base;
        if (b.contains("api.php/app") || b.contains("xgapp")) {
            if (b.contains("dijiaxia")) {
                return "https://www.dijiaxia.com/api.php/app/video_detail?id=";
            }
            return b.contains("1010dy")
                    ? "http://www.1010dy.cc/api.php/app/video_detail?id="
                    : b + "video_detail?id=";
        }
        if (b.contains(".vod")) {
            return b.contains("iopenyun") ? b + "/detailID?vod_id=" : b + "/detail?vod_id=";
        }
        return "";
    }

    /**
     * 上游 {@code p()}/{@code i()}：搜索接口（关键词<b>已</b>URL 编码）。
     *
     * @param base 基址
     * @param wd   URL 编码后的关键词
     * @return 完整地址前缀；未知形态返回空串
     */
    public static String searchUrl(String base, String wd) {
        String b = base == null ? "" : base;
        if (b.contains(".vod")) {
            return b.contains("iopenyun.com") ? b + "/list?wd=" + wd + "&page=" : b + "?wd=" + wd + "&page=";
        }
        if (b.contains("api.php/app") || b.contains("xgapp")) {
            return b + "search?text=" + wd + "&pg=";
        }
        if (!P_VOD_SEG.matcher(b).find()) {
            return "";
        }
        if (b.contains("esellauto") || b.contains("1.14.63.101") || b.contains("zjys") || b.contains("dcd")
                || b.contains("lxue") || b.contains("weetai.cn") || b.contains("haokanju1") || b.contains("fit:8")
                || b.contains("zjj.life") || b.contains("love9989") || b.contains("8d8q") || b.contains("lk.pxun")
                || b.contains("hgyx") || b.contains("521x5") || b.contains("lxyyy") || b.contains("0818tv")
                || b.contains("diyoui") || b.contains("diliktv") || b.contains("ppzhu") || b.contains("aitesucai")
                || b.contains("zz.ci") || b.contains("chxjon") || b.contains("watchmi") || b.contains("vipbp")
                || b.contains("bhtv") || b.contains("xfykl")) {
            return b + "?ac=list&wd=" + wd + "&page=";
        }
        return b + "?ac=list&zm=" + wd + "&page=";
    }

    /**
     * 上游 {@code w()}/{@code q()}：生成筛选器定义文本（{@code \n} 分行的自研 DSL）。
     *
     * <p>格式：{@code 筛选<key>+全部=+<v1>+<v2>…}；解析端把 {@code +} 当分隔符，
     * 所以值里的逗号被 {@code replace(",", "+")} 换掉 —— 上游用 slf4j 的
     * {@code Marker.ANY_NON_NULL_MARKER}（就是 {@code "+"}）做这件事。
     *
     * <p><b>组的先后顺序 = {@code typeExtend.keys()} 的迭代序，而那是 org.json 的实现细节</b>：
     * 现代 json-java 用 LinkedHashMap（写入序），真宿主自带的旧版用 HashMap（散列序）。
     * 上游同样直接遍历 {@code keys()}，所以「顺序跟着宿主库走」才是<b>忠实</b>的实现；
     * 不要为了"稳定"在这里自行排序或显式声明顺序 —— 那会改变宿主里筛选下拉框的顺序，
     * 与上游不一致。断言侧也不应比顺序（见 {@code AppYsTest} 的 D11/D11a/D11b）。
     *
     * @param base       基址
     * @param typeExtend 分类的 {@code type_extend} 对象，可为 null
     * @return DSL 文本
     */
    public static String filterSpec(String base, JSONObject typeExtend) {
        String b = base == null ? "" : base;
        StringBuilder sb = new StringBuilder();
        if (typeExtend != null) {
            Iterator<String> it = typeExtend.keys();
            while (it.hasNext()) {
                String k = it.next();
                if ("class".equals(k) || "area".equals(k) || "lang".equals(k) || "year".equals(k)) {
                    try {
                        sb.append("筛选").append(k).append("+全部=+")
                          .append(typeExtend.getString(k).replace(",", "+"))
                          .append('\n');
                    } catch (Throwable ignored) {
                        // 上游同样吞掉 JSONException
                    }
                }
            }
        }
        if (!b.contains(".vod")) {
            if (b.contains("api.php/app") || b.contains("xgapp")) {
                return sb.toString();
            }
            // 注意：这一支上游直接返回静态表，丢掉了 type_extend
            return FILTERS_FALLBACK;
        }
        return sb.toString() + FILTERS_SORT_TAIL;
    }

    /**
     * 把 {@link #filterSpec} 的 DSL 解析成宿主需要的 {@code filters} 数组。
     *
     * <p>DSL 行形如 {@code 筛选class+全部=+喜剧+…}，其中每段的 {@code 名=值} 拆成
     * {@code {"n":名,"v":值}}；没有 {@code =} 的段 n 与 v 相同。
     * 展示名映射：{@code 筛选class→类型}、{@code 筛选area→地区}、
     * {@code 筛选lang→语言}、{@code 筛选year→年份}；键名去掉「筛选」前缀。
     *
     * <p>与上游一致：值名命中 {@link #isAdultType} 的整段丢弃；
     * 空行跳过（上游在 {@code homeContent} 里对空行只沿用上一轮变量）。
     *
     * @param spec DSL 文本
     * @return {@code [{"key","name","value":[{"n","v"}]}]}
     */
    public static JSONArray parseFilterSpec(String spec) {
        JSONArray out = new JSONArray();
        if (spec == null || spec.isEmpty()) {
            return out;
        }
        for (String line : spec.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] groups = trimmed.split("\\+");
            String head = groups[0].trim();
            String name = head;
            String key = head;
            if (head.contains("筛选")) {
                String stripped = head.replace("筛选", "");
                if ("class".equals(stripped)) {
                    name = "类型";
                } else if ("area".equals(stripped)) {
                    name = "地区";
                } else if ("lang".equals(stripped)) {
                    name = "语言";
                } else if ("year".equals(stripped)) {
                    name = "年份";
                }
                key = stripped;
            }
            JSONArray values = new JSONArray();
            for (int i = 1; i < groups.length; i++) {
                String seg = groups[i].trim();
                if (seg.isEmpty()) {
                    continue;
                }
                int eq = seg.indexOf('=');
                String n;
                String v;
                if (eq != -1) {
                    n = seg.substring(0, eq).trim();
                    v = seg.substring(eq + 1).trim();
                } else {
                    n = seg;
                    v = seg;
                }
                if (isAdultType(n)) {
                    continue;
                }
                try {
                    JSONObject one = new JSONObject();
                    one.put("n", n);
                    one.put("v", v);
                    values.put(one);
                } catch (Throwable e) {
                    Log.w(TAG, "filter value 构造失败: " + seg, e);
                }
            }
            try {
                JSONObject group = new JSONObject();
                group.put("key", key);
                group.put("name", name);
                group.put("value", values);
                out.put(group);
            } catch (Throwable e) {
                Log.w(TAG, "filter group 构造失败: " + trimmed, e);
            }
        }
        return out;
    }

    /**
     * 上游 {@code String.split("wd=")[1]} 的无异常版。
     *
     * @param text  原文
     * @param token 分隔符
     * @return 分隔符之后的部分；找不到时返回整串（上游会抛 {@code ArrayIndexOutOfBoundsException}）
     */
    public static String substringAfter(String text, String token) {
        if (text == null) {
            return "";
        }
        if (token == null || token.isEmpty()) {
            return text;
        }
        int i = text.indexOf(token);
        return i < 0 ? text : text.substring(i + token.length());
    }

    // ── 契约方法（两版共享，逐字复刻）────────────────────────

    /**
     * 首页：分类表 + 每个分类的筛选器。
     *
     * <p>上游流程：{@code navUrl} 为空 → 用兜底表首行当分类表；否则请求 nav 接口，
     * 依次尝试 {@code list} / {@code data.list} / {@code data} 三个位置。
     * 分类名命中 {@link #isAdultType} 的整条跳过。
     *
     * @param filter 是否同时产出 filters
     * @return 标准首页 JSON；异常时返回 {@code ""}（与上游一致，不是 {@code "{}"}）
     */
    @Override
    public String homeContent(boolean filter) {
        try {
            String nav = navUrl(site);
            JSONArray classes = null;
            if (nav.isEmpty()) {
                classes = parseStaticClasses(filterSpec("", null));
            } else {
                JSONObject resp = parseOrNull(fetchWith(nav, defaultHeaders(nav)));
                if (resp == null) {
                    // 上游在这里 new JSONObject("") 会抛异常 → 外层 catch → 返回 ""
                    return "";
                }
                classes = firstArray(resp, "list");
            }
            JSONObject root = new JSONObject();
            JSONArray classOut = new JSONArray();
            JSONObject filters = new JSONObject();
            for (int i = 0; classes != null && i < classes.length(); i++) {
                JSONObject one = classes.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                String typeName = one.optString("type_name");
                if (isAdultType(typeName)) {
                    continue;
                }
                String typeId = one.optString("type_id");
                JSONObject item = new JSONObject();
                item.put("type_id", typeId);
                item.put("type_name", typeName);
                classOut.put(item);
                if (filter) {
                    // 上游无条件 put（即使是空数组）—— 宿主靠「有键」判断该分类要不要显示筛选栏
                    filters.put(typeId,
                            parseFilterSpec(filterSpec(nav, one.optJSONObject("type_extend"))));
                }
            }
            root.put("class", classOut);
            if (filter && filters.length() > 0) {
                root.put("filters", filters);
            }
            logMode();
            return root.toString();
        } catch (Throwable e) {
            Log.e(tag(), "homeContent failed", e);
            return "";
        }
    }

    /**
     * 分类翻页。
     *
     * <p>分页上界推断顺序（上游逐字）：{@code totalpage} → {@code pagecount} →
     * {@code data.total/data.limit} 向上取整 → {@code Integer.MAX_VALUE}。
     * 列表位置：{@code list} → {@code data.list} → {@code data}。
     * 条目两种形态：CMS 型（{@code vod_id/vod_name/vod_pic/vod_remarks}）
     * 与 flat 型（{@code nextlink/title/pic/state}），后者以 {@code vod_id} 字段是否存在分流。
     *
     * @param tid    分类 id
     * @param pg     页码（原样进 URL/响应，不校验）
     * @param filter 忽略（上游也忽略）
     * @param extend 筛选值，可为 null
     * @return 标准分类 JSON；异常时返回 {@code ""}
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(categoryUrl(site)).append(tid);
            if (site.contains("api.php/app") || site.contains("xgapp")) {
                sb.append("&class=筛选class&area=筛选area&lang=筛选lang&year=筛选year&limit=18&pg=#PN#");
            } else if (site.contains(".vod")) {
                sb.append("&class=筛选class&area=筛选area&lang=筛选lang&year=筛选year&by=排序&limit=18&page=#PN#");
            } else {
                sb.append("&page=#PN#&area=筛选area&type=筛选class&start=筛选year");
            }
            String url = fill(sb.toString(), pg, extend);
            Log.d(tag(), "category url: " + url);
            JSONObject resp = parseOrNull(fetchWith(url, defaultHeaders(url)));
            if (resp == null) {
                return "";
            }

            int pageCount;
            try {
                if (resp.has("totalpage") && resp.opt("totalpage") instanceof Number) {
                    pageCount = resp.getInt("totalpage");
                } else if (resp.has("pagecount") && resp.opt("pagecount") instanceof Number) {
                    pageCount = resp.getInt("pagecount");
                } else if (resp.has("data") && resp.optJSONObject("data") != null
                        && resp.getJSONObject("data").has("total")
                        && resp.getJSONObject("data").opt("total") instanceof Number
                        && resp.getJSONObject("data").has("limit")
                        && resp.getJSONObject("data").opt("limit") instanceof Number) {
                    int limit = resp.getJSONObject("data").getInt("limit");
                    int total = resp.getJSONObject("data").getInt("total");
                    pageCount = total % limit == 0 ? total / limit : (total / limit) + 1;
                } else {
                    pageCount = Integer.MAX_VALUE;
                }
            } catch (Throwable e) {
                Log.w(tag(), "pagecount 推断失败", e);
                pageCount = Integer.MAX_VALUE;
            }

            JSONArray list = firstArray(resp, "list");
            JSONArray out = new JSONArray();
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject one = list.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                out.put(normalizeListItem(one));
            }
            JSONObject root = new JSONObject();
            root.put("page", pg);
            root.put("pagecount", pageCount);
            root.put("limit", 90);
            root.put("total", Integer.MAX_VALUE);
            root.put("list", out);
            logMode();
            return root.toString();
        } catch (Throwable e) {
            Log.e(tag(), "categoryContent failed", e);
            return "";
        }
    }

    /**
     * 详情。基类负责分支派发与元数据映射，播放源交给
     * {@link #sourcesFromXgapp}/{@link #sourcesFromVod} 两个分叉点。
     *
     * @param ids 影片 id 列表（取首个）
     * @return 标准详情 JSON；异常时返回 {@code ""}
     */
    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = firstId(ids);
            String api = detailUrl(site);
            if (api.isEmpty()) {
                // 未知基址形态：上游拼出空 URL 后 okhttp 抛异常 → 外层 catch → ""
                return "";
            }
            String url = api + id;
            Log.d(tag(), "detail url: " + url);
            JSONObject resp = parseOrNull(fetchWith(url, defaultHeaders(url)));
            if (resp == null) {
                return "";
            }
            JSONObject vod = collectDetail(site, resp, id);
            JSONObject root = new JSONObject();
            root.put("list", new JSONArray().put(vod));
            logMode();
            return root.toString();
        } catch (Throwable e) {
            Log.e(tag(), "detailContent failed", e);
            return "";
        }
    }

    /**
     * 首页推荐（{@code homeVideoContent}）。
     *
     * @return {@code {"list":[…]}}；异常时返回 {@code ""}
     */
    @Override
    public String homeVideoContent() {
        try {
            String url;
            boolean flatTail;
            if (site.contains("api.php/app") || site.contains("xgapp")) {
                url = site + "index_video?token=";
                flatTail = false;
            } else if (site.contains(".vod")) {
                url = site + "/vodPhbAll";
                flatTail = false;
            } else {
                url = categoryUrl(site) + "movie&page=1&area=&type=&start=";
                flatTail = true;
            }
            Log.d(tag(), "home video url: " + url);
            JSONObject resp = parseOrNull(fetchWith(url, defaultHeaders(url)));
            if (resp == null) {
                return "";
            }
            JSONArray out = new JSONArray();
            if (flatTail) {
                JSONArray data = resp.optJSONArray("data");
                for (int i = 0; data != null && i < data.length(); i++) {
                    JSONObject one = data.optJSONObject(i);
                    if (one == null) {
                        continue;
                    }
                    out.put(flatItem(one));
                }
            } else {
                List<JSONArray> groups = new ArrayList<>();
                collectArrays(resp, "vlist", groups);
                if (groups.isEmpty()) {
                    collectArrays(resp, "vod_list", groups);
                }
                List<String> seen = new ArrayList<>();
                for (JSONArray g : groups) {
                    for (int i = 0; i < g.length(); i++) {
                        JSONObject one = g.optJSONObject(i);
                        if (one == null) {
                            continue;
                        }
                        String vid = one.optString("vod_id");
                        if (vid.isEmpty() || seen.contains(vid)) {
                            continue;
                        }
                        seen.add(vid);
                        JSONObject item = new JSONObject();
                        item.put("vod_id", vid);
                        item.put("vod_name", one.optString("vod_name"));
                        item.put("vod_pic", one.optString("vod_pic"));
                        item.put("vod_remarks", one.optString("vod_remarks"));
                        out.put(item);
                    }
                }
            }
            JSONObject root = new JSONObject();
            root.put("list", out);
            logMode();
            return root.toString();
        } catch (Throwable e) {
            Log.e(tag(), "homeVideoContent failed", e);
            return "";
        }
    }

    /**
     * 搜索。
     *
     * @param key   关键词（内部 URL 编码）
     * @param quick 忽略（上游也忽略）
     * @return 标准搜索 JSON；异常时返回 {@code ""}
     */
    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = searchUrl(site, URLEncoder.encode(key == null ? "" : key, "UTF-8"));
            if (url.isEmpty()) {
                return "";
            }
            JSONObject resp = parseOrNull(fetchWith(url, defaultHeaders(url)));
            if (resp == null) {
                return "";
            }
            JSONArray list = firstArray(resp, "list");
            JSONArray out = new JSONArray();
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject one = list.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                out.put(normalizeListItem(one));
            }
            JSONObject root = new JSONObject();
            root.put("list", out);
            logMode();
            return root.toString();
        } catch (Throwable e) {
            Log.e(tag(), "searchContent failed", e);
            return "";
        }
    }

    /**
     * 上游两版都是 {@code ku.e(url)} —— 直链判定。
     *
     * @param url 地址
     * @return true 表示直链
     */
    @Override
    public boolean isVideoFormat(String url) {
        return AppYsVideo.isDirect(url);
    }

    /**
     * 上游两版都硬编码 {@code return true} —— 强制走宿主的二次嗅探。
     *
     * @return 恒 true
     */
    @Override
    public boolean manualVideoCheck() {
        return true;
    }

    // ── 分叉点（子类实现）────────────────────────────────────

    /**
     * {@code xgapp} 型详情：{@code data.vod_info} → 线路/地址/解析前缀。
     *
     * @param vodInfo {@code data.vod_info} 对象
     * @param froms   线路名收集器（原位追加）
     * @param urls    线路地址收集器（原位追加）
     */
    protected abstract void sourcesFromXgapp(JSONObject vodInfo, List<String> froms, List<String> urls);

    /**
     * {@code .vod} 型详情：{@code data.vod_play_list} → 线路/地址/解析前缀。
     *
     * @param data  {@code data} 对象
     * @param froms 线路名收集器（原位追加）
     * @param urls  线路地址收集器（原位追加）
     */
    protected abstract void sourcesFromVod(JSONObject data, List<String> froms, List<String> urls);

    // ── 共享细节（两版逐字相同）──────────────────────────────

    /**
     * 详情派发 + 元数据映射 + 播放源尾部处理。
     *
     * <p>上游结构（{@code t()}/{@code m()}）：<b>先</b>按基址形态走一个分支，
     * <b>之后无条件</b>再走一次 {@code data} 尾部 —— 后者同时承担
     * {@code api.php/app/} 型的全部工作，并会覆盖前一个分支写的
     * {@code vod_play_from}/{@code vod_play_url}。
     *
     * @param base 基址
     * @param resp 详情响应
     * @param id   影片 id（作为 {@code vod_id} 兜底）
     * @return 组装好的影片对象
     */
    protected final JSONObject collectDetail(String base, JSONObject resp, String id) {
        JSONObject out = new JSONObject();
        if (!base.contains("api.php/app/")) {
            if (base.contains("xgapp")) {
                JSONObject info = resp.optJSONObject("data");
                info = info == null ? new JSONObject() : info.optJSONObject("vod_info");
                if (info != null) {
                    mapMeta(info, out, id);
                    List<String> froms = new ArrayList<>();
                    List<String> urls = new ArrayList<>();
                    sourcesFromXgapp(info, froms, urls);
                    putPlay(out, froms, urls);
                }
            } else if (base.contains(".vod")) {
                JSONObject data = resp.optJSONObject("data");
                if (data != null) {
                    mapMeta(data, out, id);
                    List<String> froms = new ArrayList<>();
                    List<String> urls = new ArrayList<>();
                    sourcesFromVod(data, froms, urls);
                    putPlay(out, froms, urls);
                }
            } else if (P_VOD_SEG.matcher(base).find()) {
                mapMetaFlat(resp, out, id);
                List<String> froms = new ArrayList<>();
                List<String> urls = new ArrayList<>();
                sourcesFromFlat(resp, froms, urls);
                putPlay(out, froms, urls);
            }
        }
        JSONObject data = resp.optJSONObject("data");
        if (data != null) {
            mapMeta(data, out, id);
            List<String> froms = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            sourcesFromTail(data, froms, urls);
            putPlay(out, froms, urls);
        }
        return out;
    }

    /**
     * CMS 型元数据映射（{@code vod_*} 字段名一致）。
     *
     * @param src 源对象
     * @param out 目标对象
     * @param id  {@code vod_id} 兜底
     */
    public static void mapMeta(JSONObject src, JSONObject out, String id) {
        try {
            out.put("vod_id", src.optString("vod_id", id));
            out.put("vod_name", src.optString("vod_name"));
            out.put("vod_pic", src.optString("vod_pic"));
            out.put("type_name", src.optString("vod_class"));
            out.put("vod_year", src.optString("vod_year"));
            out.put("vod_area", src.optString("vod_area"));
            out.put("vod_remarks", src.optString("vod_remarks"));
            out.put("vod_actor", src.optString("vod_actor"));
            out.put("vod_director", src.optString("vod_director"));
            out.put("vod_content", src.optString("vod_content"));
        } catch (Throwable e) {
            Log.w(TAG, "mapMeta failed", e);
        }
    }

    /**
     * 扁平淡出型元数据映射（{@code api.php/…/vod} 那种）。
     *
     * @param src 源对象
     * @param out 目标对象
     * @param id  {@code vod_id} 兜底
     */
    public static void mapMetaFlat(JSONObject src, JSONObject out, String id) {
        try {
            out.put("vod_id", src.optString("vod_id", id));
            out.put("vod_name", src.optString("title"));
            out.put("vod_pic", src.optString("img_url"));
            out.put("type_name", joinArray(src.optJSONArray("type")));
            out.put("vod_year", src.optString("pubtime"));
            out.put("vod_area", joinArray(src.optJSONArray("area")));
            out.put("vod_remarks", src.optString("trunk"));
            out.put("vod_actor", joinArray(src.optJSONArray("actor")));
            out.put("vod_director", joinArray(src.optJSONArray("director")));
            out.put("vod_content", src.optString("intro"));
        } catch (Throwable e) {
            Log.w(TAG, "mapMetaFlat failed", e);
        }
    }

    /**
     * {@code data.vod_url_with_player} → 线路/地址/解析前缀（两版逐字相同）。
     *
     * <p>唯一的分叉在「线路名怎么取」与「{@code parse_api} 是否改写」，
     * 由子类在调用前自行处理；这里只做共享的拼接。
     *
     * <p>上游把 {@code parse_api} 含占位符的整串换成固定的 mengx 接口，
     * 见 {@link AppYsVideo#PARSE_API_REPLACEMENT}。
     *
     * @param data  {@code data} 对象
     * @param froms 线路名收集器
     * @param urls  线路地址收集器
     */
    protected final void sourcesFromTail(JSONObject data, List<String> froms, List<String> urls) {
        JSONArray players = data.optJSONArray("vod_url_with_player");
        for (int i = 0; players != null && i < players.length(); i++) {
            JSONObject one = players.optJSONObject(i);
            if (one == null) {
                continue;
            }
            String from = lineName(one);
            if (from.isEmpty()) {
                continue;
            }
            froms.add(from);
            urls.add(one.optString("url"));
            addParser(from, rewriteTailApi(one.optString("parse_api").trim()));
        }
    }

    /**
     * 扁平淡出型播放源：{@code videolist} 的每个键是一条线路（两版逐字相同）。
     *
     * @param root  响应根对象
     * @param froms 线路名收集器
     * @param urls  线路地址收集器
     */
    protected void sourcesFromFlat(JSONObject root, List<String> froms, List<String> urls) {
        JSONObject videolist = root.optJSONObject("videolist");
        if (videolist == null) {
            return;
        }
        Iterator<String> it = videolist.keys();
        while (it.hasNext()) {
            String key = it.next();
            JSONArray arr = videolist.optJSONArray(key);
            List<String> eps = new ArrayList<>();
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject one = arr.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                eps.add(one.optString("title") + Vod.SEP_NAME_URL + one.optString("url"));
            }
            froms.add(aliasOf(key));
            urls.add(TextUtils.join(Vod.SEP_EPISODE, eps));
        }
    }

    /**
     * 线路名别名钩子。
     *
     * <p>AppYs 用它实现 {@code s()} 的 11 条重命名；AppYsV2 恒等（上游 V2 没有别名表）。
     *
     * @param name 原始线路名
     * @return 别名；默认原样返回
     */
    protected String aliasOf(String name) {
        return name == null ? "" : name;
    }

    /**
     * 尾部（{@code data.vod_url_with_player}）解析接口的改写钩子。
     *
     * <p>AppYs 会把含 {@code jpg.hou.lu/jm/za/index.php} 的整串换成固定的 mengx 接口；
     * AppYsV2 不做任何改写（它的这一位永远是候选列表里的一个元素）。
     *
     * @param api 原 {@code parse_api}
     * @return 改写后的值；默认原样返回
     */
    protected String rewriteTailApi(String api) {
        return api == null ? "" : api;
    }

    /**
     * 「线路名怎么取」的共享默认实现：优先 {@code code}，为空退回 {@code name}。
     *
     * <p>AppYsV2 的两种响应形状都这么取；AppYs 走自己的 {@code s()} 重命名表，
     * 所以它覆写本方法。
     *
     * @param player {@code vod_url_with_player} 的一个元素
     * @return 线路名（已 trim）
     */
    protected String lineName(JSONObject player) {
        String name = player.optString("code").trim();
        return name.isEmpty() ? player.optString("name").trim() : name;
    }

    /**
     * 把线路表写进影片对象。
     *
     * @param out   目标对象
     * @param froms 线路名
     * @param urls  线路地址
     */
    public static void putPlay(JSONObject out, List<String> froms, List<String> urls) {
        try {
            out.put("vod_play_from", TextUtils.join(Vod.SEP_SOURCE, froms));
            out.put("vod_play_url", TextUtils.join(Vod.SEP_SOURCE, urls));
        } catch (Throwable e) {
            Log.w(TAG, "putPlay failed", e);
        }
    }

    /**
     * 列表条目归一化：CMS 型直接取四个字段，flat 型走 {@code nextlink/title/pic/state}。
     *
     * @param one 源条目
     * @return 归一化后的条目
     */
    public static JSONObject normalizeListItem(JSONObject one) {
        JSONObject item = new JSONObject();
        try {
            if (one.has("vod_id")) {
                item.put("vod_id", one.optString("vod_id"));
                item.put("vod_name", one.optString("vod_name"));
                item.put("vod_pic", one.optString("vod_pic"));
                item.put("vod_remarks", one.optString("vod_remarks"));
            } else {
                item.put("vod_id", one.optString("nextlink"));
                item.put("vod_name", one.optString("title"));
                item.put("vod_pic", one.optString("pic"));
                item.put("vod_remarks", one.optString("state"));
            }
        } catch (Throwable e) {
            Log.w(TAG, "normalizeListItem failed", e);
        }
        return item;
    }

    /**
     * flat 型条目（{@code homeVideoContent} 的兜底分支用）。
     *
     * @param one 源条目
     * @return 归一化后的条目
     */
    protected static JSONObject flatItem(JSONObject one) {
        JSONObject item = new JSONObject();
        try {
            item.put("vod_id", one.optString("nextlink"));
            item.put("vod_name", one.optString("title"));
            item.put("vod_pic", one.optString("pic"));
            item.put("vod_remarks", one.optString("state"));
        } catch (Throwable e) {
            Log.w(TAG, "flatItem failed", e);
        }
        return item;
    }

    /**
     * 用筛选值填充 URL 里的 {@code 筛选*} 占位符。
     *
     * @param url    模板
     * @param pg     页码
     * @param extend 筛选值，可为 null
     * @return 填充结果
     */
    protected static String fill(String url, String pg, HashMap<String, String> extend) {
        String out = url == null ? "" : url;
        out = out.replace("#PN#", pg == null ? "" : pg);
        out = out.replace("筛选class", param(extend, "class"));
        out = out.replace("筛选area", param(extend, "area"));
        out = out.replace("筛选lang", param(extend, "lang"));
        out = out.replace("筛选year", param(extend, "year"));
        out = out.replace("排序", param(extend, "排序"));
        return out;
    }

    /** 取筛选值，缺失返回空串 */
    private static String param(HashMap<String, String> extend, String key) {
        if (extend == null) {
            return "";
        }
        String v = extend.get(key);
        return v == null ? "" : v;
    }

    /**
     * 严格解析：<b>模拟上游 {@code new JSONObject(body)} 的异常语义</b>。
     *
     * <p>上游四个契约方法都是「先 {@code yi.k()} 拿正文 → {@code new JSONObject(body)}」，
     * 正文为空或不是 JSON 时构造函数抛 {@code JSONException}，被外层 catch 吞掉后方法返回
     * {@code ""}。本工程用 {@code Http.parseJson}（永不抛、失败返回空对象），若不显式拦，
     * 空正文会被当成「合法但空的响应」继续走完流程，产出 {@code {"list":[{}]}} 这种
     * 上游永远不会产出的东西。
     *
     * @param body 响应正文
     * @return 解析结果；空/非 JSON 返回 null（调用方据此返回 {@code ""}）
     */
    protected static JSONObject parseOrNull(String body) {
        if (body == null) {
            return null;
        }
        String text = body.trim();
        if (text.isEmpty() || text.charAt(0) != '{') {
            return null;
        }
        try {
            return new JSONObject(text);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 上游「列表在哪里」的三段兜底：{@code list} → {@code data.list} → {@code data(数组)}。
     *
     * @param resp 响应
     * @param name 首选键名
     * @return 数组；都没有返回 null（上游语义，调用方自行判空）
     */
    protected static JSONArray firstArray(JSONObject resp, String name) {
        if (resp == null) {
            return null;
        }
        JSONArray direct = resp.optJSONArray(name);
        if (direct != null) {
            return direct;
        }
        JSONObject data = resp.optJSONObject("data");
        if (data != null) {
            return data.optJSONArray(name);
        }
        return resp.optJSONArray("data");
    }

    /**
     * 把兜底筛选表首行解析成分类表（{@code 分类+全部=+电影=movie+…}）。
     *
     * @param spec DSL 文本
     * @return 分类数组；首行不足两项时返回空数组
     */
    public static JSONArray parseStaticClasses(String spec) {
        JSONArray out = new JSONArray();
        if (spec == null || spec.isEmpty()) {
            return out;
        }
        String[] lines = spec.split("\n");
        if (lines.length == 0) {
            return out;
        }
        String[] groups = lines[0].split("\\+");
        for (int i = 1; i < groups.length; i++) {
            String[] kv = groups[i].trim().split("=");
            if (kv.length >= 2) {
                try {
                    JSONObject one = new JSONObject();
                    one.put("type_name", kv[0].trim());
                    one.put("type_id", kv[1].trim());
                    out.put(one);
                } catch (Throwable e) {
                    Log.w(TAG, "静态分类构造失败: " + groups[i], e);
                }
            }
        }
        return out;
    }

    /**
     * 上游 {@code x()} 里 `svip.jhyun.jx.cn` 那段的「换接口」表（AppYs 专用，放基类便于测试比对）。
     *
     * @param from 线路名
     * @return 命中时返回替换前缀；未命中返回 null
     */
    protected static String svipOverride(String from) {
        if (from.contains("人人迷")) {
            return "http://www.1080kan.cc/jiexi/rrmi.php?url=";
        }
        if (from.contains("人人")) {
            return "http://www.1080kan.cc/jiexi/rr.php?url=";
        }
        if (from.contains("番茄")) {
            return "http://www.1080kan.cc/jiexi/fq.php?url=";
        }
        return null;
    }

    /**
     * 上游 {@code o()}/{@code p()} 里那批「非标准 CMS」的 host 名单（AppYs 专用）。
     *
     * @param base 基址
     * @return true 表示属于该批
     */
    protected static boolean isLegacyGroup(String base) {
        String b = base == null ? "" : base;
        return b.contains("zhenfy") || b.contains("cztv") || b.contains("1.14.63.101") || b.contains("fit:8")
                || b.contains("diliktv.xyz") || b.contains("ppzhu.vip") || b.contains("api.8d8q.com")
                || b.contains("haokanju1.cc") || b.contains("cztv");
    }

    /**
     * 上游 {@code o()} 里「tkys 系」的 host 名单（AppYs 专用）。
     *
     * @param base 基址
     * @return true 表示属于该批
     */
    protected static boolean isTkysGroup(String base) {
        String b = base == null ? "" : base;
        return b.contains("lxyyy") || b.contains("j.zjj.life") || b.contains("lktv")
                || b.contains("0818tv") || b.contains("ruoxinew");
    }

    /** 是否 URL 是「带参接口」形态（上游 {@code c}） */
    public static boolean hasQueryForm(String base) {
        return P_QUERY.matcher(base == null ? "" : base).find();
    }

    /** 是否 URL 是 {@code api.php/….vod} 形态（上游 {@code d}） */
    public static boolean isVodExtForm(String base) {
        return P_VOD_EXT.matcher(base == null ? "" : base).find();
    }

    /** 小写化，null 安全 */
    public static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** 上游 {@code wb.f(k,v)}：单元素 HashMap */
    public static Map<String, String> mapOf(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
}
