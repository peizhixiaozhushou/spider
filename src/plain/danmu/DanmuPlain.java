package com.example.tvbox.plain.danmu;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 弹幕（{@code csp_Danmu}）明文实现 —— TVBox 弹幕链路。
 *
 * <p>反编译原文: {@code ae/decompiled/Danmu.java}（837 行，23 个方法，全部为
 * {@code public static}）。
 *
 * <h2>职责</h2>
 * 把「影片名 + 集号」解析成一份 <b>XML 弹幕</b>（{@code <i><d p="...">文本</d></i>），
 * 或把各弹幕源的候选列表回给宿主。它<b>自己不实现弹幕库</b>：真正的弹幕数据来自
 * 三个外部提供方，按优先级串成一条兜底链：
 * <ol>
 *   <li>宿主内置库（上游 {@code merge.Z.C0288h.b(name, ep)}）—— 见
 *       {@link #builtInDanmuSource(String, int)}，<b>该分支未还原</b>（依据见方法注释）</li>
 *   <li>360 影视（{@code api.so.360kan.com} → 得到腾讯/爱奇艺/优酷/MGTV 的剧集页 URL，
 *       再交给本地桥 {@code 127.0.0.1:5266} 取弹幕）</li>
 *   <li>本地桥 {@code 127.0.0.1:5266/danmu/auto}</li>
 * </ol>
 *
 * <h2>本地桥约定（关键，全部来自反编译字面量）</h2>
 * <pre>
 * GET http://127.0.0.1:5266/danmu/get?url=&lt;url&gt;&amp;format=xml
 * GET http://127.0.0.1:5266/danmu/get?url=&lt;name&gt;&amp;platform=&lt;p&gt;&amp;format=xml
 * GET http://127.0.0.1:5266/danmu/auto?name=&lt;name&gt;&amp;episode=&lt;ep&gt;[&amp;total=&lt;n&gt;][&amp;platform=&lt;p&gt;]&amp;format=xml
 * GET http://127.0.0.1:5266/danmu/search?keywords=&lt;kw&gt;          →  { 关键词: [ {vod_name,platform,count}, ... ] }
 * GET http://127.0.0.1:9978/media                              →  { title, artist, duration }
 * GET http://127.0.0.1:9978/proxy?do=danmu&amp;site=js&amp;url=vodurl://name@ep@total@platform
 * </pre>
 * 另有 4 个 <b>外部</b>弹幕聚合站（{@code danmu.huaqi.pro} / {@code dmku.hls.one} /
 * {@code danmu.zxz.ee} / {@code dm.ruyijx.com}），见 {@link #formatDanmuUrl(String)}。
 *
 * <h2>弹幕源字符串的 4 种形态（{@link #formatDanmuUrl2(String)} 分派）</h2>
 * <pre>
 * "&lt;?xml version=\"1.0\" encoding=\"UTF-8\"?&gt;..."  已是 XML，原样返回
 * "http..."                                    → /danmu/get?url=&lt;整串&gt;
 * "vodid://&lt;name&gt;@&lt;platform&gt;"                  → /danmu/get?url=&lt;name&gt;&amp;platform=&lt;platform&gt;
 * "vodurl://&lt;name&gt;@&lt;ep&gt;@&lt;total&gt;@&lt;platform&gt;"  先记缓存，再 /danmu/auto
 * </pre>
 *
 * <h2>持久化 KV（上游 {@code merge.p038m.l.a/l.b}）</h2>
 * 上游用宿主 SharedPreferences 存 4 个键：{@code searchvodname}（当前搜索词）、
 * {@code danmakucache}（&lt;片名, "片名  •  平台"&gt;，上限 20 条）、
 * {@code danmuvodname}（网盘播放入口给的片名）、{@code danmucache}（&lt;片名, 搜索命中的片名&gt;）。
 * 本实现通过 {@link #kvGet(String)} / {@link #kvPut(String, String)} 两个
 * <b>可覆写</b>方法访问（默认内存实现，测试无副作用）；宿主接入时覆写为 SharedPreferences。
 *
 * <h2>未覆盖的分支</h2>
 *
 * <p>先给覆盖面对账，再列真正的缺口 —— 免得把「漏了」和「合并了」混为一谈。
 *
 * <h3>上游 22 个方法 → 本类一一对应（无遗漏）</h3>
 * <pre>
 * AppDanmu                     → appDanmu
 * Danmaku                      → danmakuCandidates
 * Danmu                        → danmu
 * DiyDanmu                     → diyDanmu
 * d(String)                    → d
 * filterDanmuResult            → filterDanmuResult
 * formatDanmuUrl               → formatDanmuUrl
 * formatDanmuUrl2              → formatDanmuUrl2
 * processDanmuRequest          → processDanmuRequest
 * generateCombinedRGB          → generateCombinedRGB
 * generateCombinedWhite        → generateCombinedWhite
 * getCachedSearchKeyword       → getCachedSearchKeyword
 * getDanmuFrom5266             → getDanmuFrom5266
 * getDanmuFrom5266Space        → getDanmuFrom5266Space
 * getDanmuFromOK360            → getDanmuFromOK360
 * getDanmuFromPanOK360         → getDanmuFromPanOK360
 * getDanmuFromPanOK360Space    → getDanmuFromPanOK360Space
 * getOriginalVideoName         → getOriginalVideoName
 * getRealName                  → getRealName
 * mergeArrays                  → mergeArrays
 * updateDanmuColors            → updateDanmuColors
 * updateDanmuWhite             → updateDanmuWhite
 * </pre>
 * <p>计数命令：
 * {@code python3 -c "import re;print(re.findall(r'\n    (?:public |private |static |final )*[\w<>\[\],\. ]+?\s(\w+)\(', open('ae/decompiled/Danmu.java').read()))"}
 * （22 个成员方法，与上面的表逐条对上）。
 *
 * <h3>已核对为「合并」而非「缺口」的两处</h3>
 * <ol>
 *   <li><b>两个取页封装</b>：上游对本地桥 / 360 用 {@code merge.p036k.b.k(url)}，
 *       对其它端点用 {@code merge.p036k.b.l(url, headers)}。两者只差「是否带自定义头」，
 *       本类统一收敛为 {@link #fetch(String, Map)}（不带头的场合传 {@code null}/空表），
 *       行为等价。</li>
 *   <li><b>日志设施</b>：{@code SpiderDebug.log} / {@code merge.p038m.I.i} → {@code android.util.Log}
 *       的 {@code d/i/w}，不影响协议。</li>
 * </ol>
 *
 * <h3>真正的缺口（3 条，都不是静默空实现）</h3>
 * <ol>
 *   <li><b>内置弹幕库 {@code merge.Z.C0288h.b(name, episode)}</b> ——
 *       上游两处入口（{@code AppDanmu}/{@code DiyDanmu}）与网盘入口都会先问它，
 *       属混淆私有库：静态分析只能看到调用点（{@code Danmu.java:44 / 124 / 169}）与
 *       「返回一个弹幕源字符串」这一事实，其数据源、索引格式、是否走网络均无法确定。
 *       <b>落地方式</b>：{@link #builtInDanmuSource(String, int)} +
 *       {@link #setBuiltInSourceProvider(BuiltInSourceProvider)} + {@link #hasBuiltInSource()}。
 *       默认「未命中」→ 照常落到 360 / 5266 两级兜底（功能不降级）；提供方抛异常也被吞掉，
 *       不中断兜底链。测试已覆盖「未接 / 已接 / 提供方抛异常 / 复位」四条路径。</li>
 *   <li><b>宿主 {@code config.json} 的私有路径</b> ——
 *       上游 {@code isColoredConfig} 读的是 {@code k.a(k.d("/config.json"))}，
 *       即宿主私有目录，本类无法也不应去猜那个路径。
 *       <b>落地方式</b>：{@link #readHostConfig()}（可覆写）+ {@link #setHostConfig(String)}；
 *       默认从 KV 槽位 {@code __host_config_json} 取，取不到按「非彩色」处理。
 *       注意：这导致<b>「彩色弹幕」开关在默认配置下恒为关</b>，宿主必须显式注入。</li>
 *   <li><b>KV 的持久化语义（行为差异，不只是「实现位置不同」）</b> ——
 *       上游 {@code merge.p038m.l.a/l.b} 落在 SharedPreferences，<b>进程重启后仍在</b>；
 *       本类默认实现是 {@code static LinkedHashMap}，<b>仅进程内有效</b>。
 *       可观察的后果：重启后 {@code danmakucache} 为空 →
 *       {@link #filterDanmuResult(org.json.JSONArray, Map)} 会退化成「精确前缀」比较模式
 *       （该方法的两种模式测试都覆盖了，但「跨重启保留」本身未复刻）。
 *       <b>落地方式</b>：{@link #kvGet(String)} / {@link #kvPut(String, String)} 可覆写为宿主存储。</li>
 * </ol>
 *
 * <h3>一处已被修正的"不可复刻"（旧注释是错的，此处更正）</h3>
 * <p><b>旧注释曾写</b>：「本类用 {@code LinkedHashMap} 的插入序（即『保留最近写入的 20 条』）……
 * 『保留最近写入』这条语义是确定且被测试钉住的。」
 * <p><b>这句话在真宿主上不成立</b>，属于"错误注释比没有注释更危险"那一类：
 * 它让人以为"最近 20 条"已经成立、无需再管。实测（把 {@code spider.jar} 接进
 * fmplayer 宿主后跑 {@code DanmuTest}）：淘汰的是 {@code k10..k13,k15}，
 * 不是最早写入的 {@code k1..k5}。根因与修法见下一节。
 * <p>现在这两种输入形态各有明确契约：
 * 「无顺序信息」时只承诺"上限 20 条"（具体淘汰谁不是契约，也无法成为契约）；
 * 「有写入序」时承诺"保留最近写入的 20 条，且刚写入的一定留住"。
 *
 * <h2>已修复的上游缺陷：「最近 20 条」在真机上是「随机 20 条」</h2>
 * <p>上游 {@code danmakucache} 的裁剪是这么写的（{@code Danmu.java:675-686}）：
 * <pre>
 *   if (jSONObject.length() &gt; 20) {
 *       Iterator&lt;String&gt; itKeys = jSONObject.keys();     // ← "顺序"的唯一来源
 *       while (itKeys.hasNext()) { arrayList.add(itKeys.next()); }
 *       for (int size = arrayList.size() - 20; size &lt; arrayList.size(); size++) {
 *           String str2 = (String) arrayList.get(size);   // ← 取"最后 20 个"
 *           jSONObject2.put(str2, jSONObject.getString(str2));
 *       }
 *   }
 * </pre>
 * 即：它把"最近"定义成「{@code keys()} 迭代序里的最后 20 个」。而那个
 * {@code JSONObject} 不是刚构造的内存对象，是<b>从 SharedPreferences 里读回来的</b>
 * （{@code String strB4 = l.b("danmucache");} 之后 {@code new JSONObject(strB4)}）——
 * 真宿主自带的旧版 {@code org.json} 底层是 {@code HashMap}，{@code keys()} 的迭代序
 * 由键的 {@code hashCode} 决定，<b>与写入序无关</b>。
 * <p><b>实测</b>（fmplayer 宿主自带 {@code org.json}，见 {@code DanmuTest} 的
 * {@code 最早 5 个被淘汰} 用例）：写入 {@code k1..k25} 后裁剪，保留下来的 20 个键是
 * {@code k1,k2,k3,k4,k5,k6,k7,k8,k9,k14,k16,k17,k18,k19,k20,k21,k22,k23,k24,k25} ——
 * 被淘汰的是 {@code k10..k13,k15}，<b>不是</b>最早写入的 {@code k1..k5}。
 * 也就是说上游那句 "最后 20 个" 在真机上是 "随便 20 个"，
 * <b>连它自己刚写进去的那条都可能被当场淘汰</b>（用户刚搜过的词，下一次就查不到了）。
 *
 * <p><b>为什么本地一直看不出来</b> —— 本项目早期用的是自写桩，底层 {@code LinkedHashMap} —— <b>迭代序恰好等于插入序</b>，
 * 于是 "最后 20 个" 在本地<b>碰巧成立</b>。这个巧合让下列断言<b>侥幸通过</b>（现已改写）：
 * <pre>
 *   ok("保留最后 20 个（k6..k25）", trimmed.has("k6") &amp;&amp; trimmed.has("k25"), …);
 *   ok("最早 5 个被淘汰",        !trimmed.has("k1") &amp;&amp; !trimmed.has("k5"), …);
 * </pre>
 * 它们钉住的其实是<b>桩的迭代序</b>，不是本类的行为 —— 换宿主就红，而代码一行没改。
 * （同类巧合还有一处：旧桩不转义 {@code /}，掩盖了 {@code Hxq} 里"正则匹配序列化文本"
 * 在真机必然失效的问题，见 {@code HxqPlain} 的同名小节。）
 *
 * <p><b>本类修法</b> —— 既然"顺序信息"在真机的存储往返里根本不存在，就<b>自己把它记下来</b>：
 * <ol>
 *   <li>新增 KV 槽位 {@link #KEY_DANMU_ORDER}（JSON 数组文本，最老的在前），
 *       与 {@code danmakucache} 同生共死；</li>
 *   <li>{@link #trimCache(JSONObject, String)} 按该顺序淘汰最老的若干条，
 *       并<b>保证刚写入的 {@code newKey} 一定保留</b>（新键不参与淘汰候选）；</li>
 *   <li>{@link #trimCache(JSONObject)}（无顺序信息）退化为"按传入对象迭代序裁 20 条"，
 *       并<b>在注释与测试里放弃</b>"哪 5 条被淘汰"这个说法 —— 它不可能是契约。</li>
 * </ol>
 * <p>契约因此分两档，都被 {@code DanmuTest} 钉住：
 * 「无顺序信息」→ {@code 裁到 20 条且无凭空造键} / {@code 被淘汰 5 条}；
 * 「有写入序」→ {@code ★ 带写入序：保留的是最近写入的 20 个（k6..k25）} /
 * {@code ★ 极端：刚写入的键一定留住}（把新键摆在顺序表最老位，它仍必须留下）。
 *
 * <h2>发现的上游缺陷（本实现已修，逐条列此以免后人误当回归）</h2>
 * <ol>
 *   <li><b>{@code updateDanmuColors/White} 少字段分支写入数组引用</b>：
 *       当 {@code p} 属性不足 4 段时，上游执行 {@code sb.append(strArrSplit)}，
 *       而 {@code String} 的 {@code append} 重载会写成
 *       {@code [Ljava.lang.String;@1a2b3c}，产物被弹幕播放器视为非法颜色值。
 *       本实现改写为「原样回填整段 {@code p} 属性」，并用测试钉住
 *       「输出不含 {@code [Ljava.lang.String}」。</li>
 *   <li><b>{@code Danmu(wangpan)} 对空片名会 NPE</b>：上游直接
 *       {@code l.b("danmuvodname").split(" ")[0]}，KV 无值时 {@code split} 抛 NPE。
 *       本实现先判空返回空弹幕。</li>
 *   <li><b>颜色随机表含重复白</b>：{@code generateCombinedWhite} 20 项里有 15 项是
 *       {@code 16777215}，实际「彩色」概率只有 25%。这是上游设计（不是笔误），
 *       本实现逐字保留。</li>
 *   <li><b>{@code AppDanmu} 未使用 {@code artist}</b>：{@code DiyDanmu} 会优先用
 *       {@code artist} 算集号，{@code AppDanmu} 只用片名。保留差异。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Danmu.java}
 * 端点清单:   {@code out/sources/spider_interfaces.json} → {@code csp_Danmu}
 *             （8 条字面量，与上面列出的外部端点一一对应）
 */
public class DanmuPlain extends BaseSpider {

    private static final String TAG = "DanmuPlain";

    /** 通用 UA（本类所有请求上游都不带头，此处仅用于宿主兼容） */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    // ── 本地桥 ────────────────────────────────────────────────
    /** 弹幕桥（上游字面量） */
    public static final String BRIDGE_5266 = "http://127.0.0.1:5266";
    /** 主程序接口（上游字面量） */
    public static final String BRIDGE_9978 = "http://127.0.0.1:9978";
    /** 360 影视搜索（上游字面量，逐字含 {@code force_v=1} 等参数） */
    public static final String SO360_INDEX =
            "https://api.so.360kan.com/index?force_v=1&kw=%s&from=&pageno=1&v_ap=1&tab=all";

    // ── KV 键（上游字面量） ───────────────────────────────────
    /** 当前搜索词 */
    public static final String KEY_SEARCH_VOD_NAME = "searchvodname";
    /** &lt;片名, "片名  •  平台"&gt; 缓存 */
    public static final String KEY_DANMAKU_CACHE = "danmakucache";

    /**
     * 缓存键的<b>写入序</b>（JSON 数组，最老的在前、最新的在后）。
     *
     * <p>为什么必须单开一个槽位记它：上游 {@code Danmu.java:675-686} 的"保留最后 20 条"
     * 是从 {@code jSONObject.keys()} 的<b>迭代序</b>里取"最后 20 个" ——
     * 而那个 JSONObject 是从 SharedPreferences 的字符串 {@code new JSONObject(str)} 来的，
     * 在真机旧版 org.json 里底层是 {@code HashMap}，迭代序由键的 hashCode 决定、
     * <b>与写入序无关</b>。⇒ 上游这个"最近 20 条"实际上是"随便 20 条"。
     * 我们若要真的按"最近写入"淘汰，就必须自己把顺序记下来（本槽位）。
     *
     * <p>注意：这是<b>修正</b>而非还原 —— 见类 javadoc 的"发现的上游缺陷"。
     */
    public static final String KEY_DANMU_ORDER = "danmuorder";
    /** 网盘播放入口给的片名 */
    public static final String KEY_DANMU_VOD_NAME = "danmuvodname";
    /** 网盘播放入口给的集号文本 */
    public static final String KEY_DANMU_VOD_INDEX = "danmuvodindex";
    /** &lt;片名, 搜索命中的片名&gt; 缓存 */
    public static final String KEY_DANMU_CACHE = "danmucache";
    /** 两个缓存表的条目上限（上游硬编码 20） */
    public static final int CACHE_LIMIT = 20;

    /** 已加载/失败日志里的固定文案（上游逐字保留，便于对照） */
    public static final String MSG_OK = "弹幕加载成功";
    /** 失败文案 */
    public static final String MSG_FAIL = "弹幕加载失败";

    // ── 可覆写 seam ──────────────────────────────────────────
    /** HTTP 传输 seam；默认走 {@link Http}，测试可替换（默认实现离线时直接返回空） */
    public interface Fetch {
        /**
         * 单次 GET。
         *
         * @param url     完整 URL
         * @param headers 请求头，可为 null
         * @return 响应体；失败返回空串（不返回 null，调用方按空串判失败）
         */
        String get(String url, Map<String, String> headers);
    }

    /** 默认传输：离线模式下不发包（保证任何测试都不会真联网） */
    private static final Fetch DEFAULT_FETCH = new Fetch() {
        @Override
        public String get(String url, Map<String, String> headers) {
            if (Http.isOfflineForTest()) {
                Log.d(TAG, "offline, skip GET " + url);
                return "";
            }
            try {
                return Http.create().ua(UA).get(url, headers);
            } catch (Throwable e) {
                Log.w(TAG, "GET failed " + url + " : " + e.getMessage());
                return "";
            }
        }
    };

    private static Fetch fetchImpl = DEFAULT_FETCH;

    /** 内存 KV（默认实现）。宿主接入时覆写 {@link #kvGet(String)} / {@link #kvPut(String, String)} */
    private static final Map<String, String> KV = new LinkedHashMap<>();

    /** 随机数源（颜色表用），测试可替换为固定种子 */
    private static Random random = new Random();

    private Context context;

    // ─────────────────────────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────────────────────────

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        this.context = context;
    }

    /** @return 宿主 context，可为 null */
    public Context context() {
        return context;
    }

    // ─────────────────────────────────────────────────────────────
    // seam 注入
    // ─────────────────────────────────────────────────────────────

    /**
     * 替换 HTTP 传输（测试用；传 null 恢复默认）。
     *
     * @param f 传输实现
     */
    public static void setFetchForTest(Fetch f) {
        fetchImpl = f == null ? DEFAULT_FETCH : f;
    }

    /**
     * 替换随机源（测试用，便于对颜色表做确定性断言）。
     *
     * @param r 随机源，可为 null（恢复默认）
     */
    public static void setRandomForTest(Random r) {
        random = r == null ? new Random() : r;
    }

    /** 清空内存 KV（测试用） */
    public static void clearKvForTest() {
        KV.clear();
    }

    /**
     * 读 KV —— 还原上游 {@code l.b(name)}。
     *
     * @param key 键
     * @return 值；无则空串（上游返回 null，本实现统一空串以免下游 NPE）
     */
    public String kvGet(String key) {
        if (key == null) {
            return "";
        }
        String v = KV.get(key);
        return v == null ? "" : v;
    }

    /**
     * 写 KV —— 还原上游 {@code l.a(name, value)}。
     *
     * @param key   键
     * @param value 值
     */
    public void kvPut(String key, String value) {
        if (key == null) {
            return;
        }
        KV.put(key, value == null ? "" : value);
    }

    /**
     * 单次 GET —— 全类唯一的网络出口。
     *
     * @param url     完整 URL
     * @param headers 请求头，可为 null
     * @return 响应体；失败空串
     */
    public static String fetch(String url, Map<String, String> headers) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        String body = fetchImpl.get(url, headers);
        return body == null ? "" : body;
    }

    /** @return 空请求头 */
    private static Map<String, String> noHeaders() {
        return new LinkedHashMap<>();
    }

    /** URL 编码（UTF-8，异常时返回原文，还原上游 try/catch 语义） */
    public static String urlEncode(String s) {
        if (s == null) {
            return "";
        }
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Throwable e) {
            return s;
        }
    }

    /** URL 解码（异常时返回原文） */
    public static String urlDecode(String s) {
        if (s == null) {
            return "";
        }
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Throwable e) {
            return s;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 名称 / 集号（纯函数，最值得测）
    // ─────────────────────────────────────────────────────────────

    /** 臻彩/真彩 标记（还原上游 {@code [（(【<][臻真]彩[）)】>]}） */
    private static final Pattern COLOR_MARK = Pattern.compile("[（(【<][臻真]彩[）)】>]");
    private static final Pattern EP_ZH = Pattern.compile("第(\\d+)[集期话]");
    private static final Pattern EP_SxxExx = Pattern.compile("S\\d+E(\\d{2,3})");
    private static final Pattern EP_DOT_E = Pattern.compile("·E(\\d+)·");
    private static final Pattern EP_DATE = Pattern.compile("(\\d{4})[-._]?(\\d{2})[-._]?(\\d{2})");
    private static final Pattern EP_LEADING_NUM = Pattern.compile("(\\d+)([a-zA-Z]*|(?:\\s+.*)?)");
    private static final Pattern BRACKET = Pattern.compile("\\[.*?\\]");

    /**
     * 还原上游 {@code getRealName(String)}：去掉「（臻彩）/（真彩）」等画质标记并 trim。
     *
     * @param name 原始片名，可为 null
     * @return 去掉标记后的片名（null 视为空串）
     */
    public static String getRealName(String name) {
        if (name == null) {
            return "";
        }
        return COLOR_MARK.matcher(name).replaceAll("").trim();
    }

    /**
     * 还原上游 {@code d(String)}：从剧集标题里抽集号。
     *
     * <p>判定顺序（上游就是这几级，顺序不能换）：
     * <ol>
     *   <li>先去掉 {@code [..]} 方括号内容</li>
     *   <li>{@code 第N集|期|话}</li>
     *   <li>若同时含 {@code S} 和 {@code E} → {@code SxxEyy}</li>
     *   <li>否则：{@code ·E&lt;n&gt;·}</li>
     *   <li>否则：{@code yyyyMMdd} 日期（去分隔符后整体当集号）</li>
     *   <li>否则：{@code "."} 前的前导数字</li>
     * </ol>
     *
     * @param text 标题 / 索引文本，可为 null
     * @return 集号；无法识别返回 1（上游兜底值）
     */
    public static int d(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        String s = BRACKET.matcher(text).replaceAll("");
        Matcher m = EP_ZH.matcher(s);
        if (m.find()) {
            return parseIntSafe(m.group(1), 1);
        }
        if (s.contains("S") && s.contains("E")) {
            Matcher m2 = EP_SxxExx.matcher(s);
            if (m2.find()) {
                return parseIntSafe(m2.group(1), 1);
            }
        } else {
            Matcher m3 = EP_DOT_E.matcher(s);
            if (m3.find()) {
                return parseIntSafe(m3.group(1), 1);
            }
            Matcher m4 = EP_DATE.matcher(s);
            if (m4.find()) {
                return parseIntSafe(m4.group(1) + m4.group(2) + m4.group(3), 1);
            }
            String head = s.split("\\.")[0];
            Matcher m5 = EP_LEADING_NUM.matcher(head);
            if (m5.find()) {
                return parseIntSafe(m5.group(1), 1);
            }
        }
        return 1;
    }

    /** 上游用 {@code Integer.parseInt}，非法即抛；本实现兜底为默认值并记日志 */
    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable e) {
            Log.w(TAG, "parseInt failed: " + s);
            return def;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 颜色（上游的 36/20 项表，逐字保留）
    // ─────────────────────────────────────────────────────────────

    /** 彩色表（{@code generateCombinedRGB}，36 项，逐字保留） */
    public static final String[] RGB_TABLE = {
            "16711680", "16776960", "65280", "255", "16711935", "8388736",
            "16753920", "65535", "16777215", "16761087", "16777087", "8978431",
            "6527999", "16744447", "16756735", "8454143", "16724787", "16777215",
            "16752723", "16776951", "10000639", "5729279", "16645625", "16185078",
            "12334518", "13882321", "16777215", "16209488", "16772810", "16766758",
            "16777014", "16772362", "16773119", "14410239", "11835903", "16777215"};

    /** 白色表（{@code generateCombinedWhite}，20 项：15 白 + 5 彩，逐字保留） */
    public static final String[] WHITE_TABLE = {
            "16777215", "16777215", "16777215", "16777215", "16777215",
            "16777215", "16777215", "16777215", "16777215", "16777215",
            "16777215", "16777215", "16777215", "16777215", "16777215",
            "16711680", "16776960", "255", "65280", "8388736"};

    /**
     * 还原上游 {@code generateCombinedRGB()}：从彩色表随机取一个十进制 ARGB。
     *
     * @return 颜色十进制串
     */
    public static String generateCombinedRGB() {
        return RGB_TABLE[random.nextInt(RGB_TABLE.length)];
    }

    /**
     * 还原上游 {@code generateCombinedWhite()}：从白色表随机取。
     *
     * @return 颜色十进制串
     */
    public static String generateCombinedWhite() {
        return WHITE_TABLE[random.nextInt(WHITE_TABLE.length)];
    }

    // ─────────────────────────────────────────────────────────────
    // XML 改写
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code updateDanmuColors(String)}：把每条 {@code <d p="...">} 的
     * 第 4 个字段（颜色）换成随机彩色。
     *
     * @param xml 弹幕 XML，可为 null
     * @return 新 XML；输入空则原样返回
     */
    public static String updateDanmuColors(String xml) {
        return rewriteDanmuColor(xml, true);
    }

    /**
     * 还原上游 {@code updateDanmuWhite(String)}：同上，颜色取白色表。
     *
     * @param xml 弹幕 XML，可为 null
     * @return 新 XML；输入空则原样返回
     */
    public static String updateDanmuWhite(String xml) {
        return rewriteDanmuColor(xml, false);
    }

    /**
     * 颜色改写内核。
     *
     * <p>对齐上游扫 {@code <d p="} → 下一个 {@code "&gt;} 的循环；差异见类注释
     * 「发现的上游缺陷」第 1 条：字段不足 4 段时上游会写入数组引用，本实现原样回填。
     *
     * @param xml    弹幕 XML
     * @param colored true 用彩色表，false 用白色表
     * @return 改写结果
     */
    public static String rewriteDanmuColor(String xml, boolean colored) {
        if (xml == null || xml.isEmpty()) {
            return xml;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (true) {
            int open = xml.indexOf("<d p=\"", i);
            if (open < 0) {
                sb.append(xml.substring(i));
                return sb.toString();
            }
            int close = xml.indexOf("\">", open);
            if (close <= 0) {
                sb.append(xml.substring(i));
                return sb.toString();
            }
            sb.append(xml.substring(i, open));
            String raw = xml.substring(open + 6, close);
            String[] parts = raw.split(",");
            if (parts.length < 4) {
                // 上游此处写成 sb.append(parts) → 输出 [Ljava.lang.String;@... ，已修
                sb.append("<d p=\"").append(raw).append("\">");
            } else {
                parts[3] = colored ? generateCombinedRGB() : generateCombinedWhite();
                sb.append("<d p=\"").append(parts[0]);
                for (int k = 1; k < parts.length; k++) {
                    sb.append(",").append(parts[k]);
                }
                sb.append("\">");
            }
            i = close + 2;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 数组合并
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code mergeArrays(JSONArray, JSONArray)}：先 a 后 b，跳过 null 元素。
     *
     * @param a 前段，可为 null
     * @param b 后段，可为 null
     * @return 新数组（永不为 null）
     */
    public static JSONArray mergeArrays(JSONArray a, JSONArray b) {
        JSONArray out = new JSONArray();
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                Object o = a.opt(i);
                if (o != null) {
                    out.put(o);
                }
            }
        }
        if (b != null) {
            for (int i = 0; i < b.length(); i++) {
                Object o = b.opt(i);
                if (o != null) {
                    out.put(o);
                }
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 候选过滤（还原 filterDanmuResult / getCachedSearchKeyword）
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code getCachedSearchKeyword(String)}：优先用「当前搜索词」，
     * 否则用 {@code danmakucache} 里该片名映射到的片名（截到 {@code "  •"} 之前）。
     *
     * @param name 宿主给的片名
     * @return 用于检索的关键词
     */
    public String getCachedSearchKeyword(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        try {
            String s = kvGet(KEY_SEARCH_VOD_NAME);
            if (!s.isEmpty()) {
                return s;
            }
            String cache = kvGet(KEY_DANMAKU_CACHE);
            if (cache.isEmpty()) {
                cache = "{}";
            }
            String mapped = new JSONObject(cache).optString(name);
            if (mapped.isEmpty()) {
                return name;
            }
            int idx = mapped.indexOf("  •");
            return idx != -1 ? mapped.substring(0, idx) : mapped;
        } catch (Throwable e) {
            Log.w(TAG, "getCachedSearchKeyword failed", e);
            return name;
        }
    }

    /**
     * 还原上游 {@code filterDanmuResult(JSONArray, Map)}：把「命中当前片名」的候选
     * 排到前面。
     *
     * <p>命中判定两种模式：
     * <ul>
     *   <li>缓存里<b>没有</b>该片名的映射 → <b>精确前缀</b>比较
     *       （取 {@code name} 中 {@code "  •"} 之前的部分整串相等）</li>
     *   <li>缓存里<b>有</b>映射 → <b>子串</b>比较</li>
     * </ul>
     *
     * @param arr 候选数组，可为 null
     * @param map 宿主参数（取 {@code name}），可为 null
     * @return 重排后的新数组；入参为空时原样返回
     */
    public JSONArray filterDanmuResult(JSONArray arr, Map<String, String> map) {
        if (arr == null || arr.length() == 0 || map == null) {
            return arr;
        }
        String key = kvGet(KEY_SEARCH_VOD_NAME);
        if (key == null || key.isEmpty()) {
            key = map.get("name");
        }
        if (key == null || key.isEmpty()) {
            return arr;
        }
        try {
            String cache = kvGet(KEY_DANMAKU_CACHE);
            if (cache == null || cache.isEmpty()) {
                cache = "{}";
            }
            String mapped = new JSONObject(cache).optString(key);
            boolean exact = false;
            if (mapped == null || mapped.isEmpty()) {
                exact = true;
                mapped = key;
            }
            JSONArray hit = new JSONArray();
            JSONArray miss = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String name = o.optString("name");
                boolean ok = false;
                if (exact) {
                    int idx = name == null ? -1 : name.indexOf("  •");
                    if (idx != -1) {
                        ok = name.substring(0, idx).equals(mapped);
                    }
                } else if (name != null) {
                    ok = name.contains(mapped);
                }
                (ok ? hit : miss).put(o);
            }
            return mergeArrays(hit, miss);
        } catch (Throwable e) {
            Log.w(TAG, "filterDanmuResult failed", e);
            return arr;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 缓存写入（还原 processDanmuRequest / getOriginalVideoName）
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code processDanmuRequest(name, platform)}：把
     * {@code <本片标题> → "片名  •  平台"} 记进 {@code danmakucache}（上限 20 条）。
     *
     * <p>上游未判空 {@code name/platform}，但读 {@code 9978/media} 失败即返回。
     *
     * @param name     片名
     * @param platform 平台
     */
    public void processDanmuRequest(String name, String platform) {
        if (name == null || name.isEmpty() || platform == null || platform.isEmpty()) {
            return;
        }
        try {
            String media = fetch(BRIDGE_9978 + "/media", noHeaders());
            if (media == null || media.isEmpty()) {
                media = "{}";
            }
            String title = new JSONObject(media).optString("title");
            if (title == null || title.isEmpty()) {
                return;
            }
            String cache = kvGet(KEY_DANMAKU_CACHE);
            if (cache == null || cache.isEmpty()) {
                cache = "{}";
            }
            JSONObject o = new JSONObject(cache);
            o.put(title, name + "  •  " + platform);
            // 传 title：裁剪时保证刚写入的这条一定留住，并按真实写入序淘汰最老的
            trimCache(o, title);
            kvPut(KEY_DANMAKU_CACHE, o.toString());
        } catch (Throwable e) {
            Log.w(TAG, "processDanmuRequest failed", e);
        }
    }

    /**
     * 上限 20 条：保留「键迭代顺序」最后 20 个（还原上游
     * {@code for (size-20 .. size-1)} 的写法）。
     *
     * <p><b>本重载不知道"谁最新"</b>，只能按传入对象的迭代序裁 ——
     * 而真机旧版 org.json 的迭代序由 HashMap 决定（与写入序无关），
     * 所以这条路径下"淘汰哪 5 条"<b>不是契约、也无法成为契约</b>。
     * 生产路径请用 {@link #trimCache(JSONObject, String)}（它能拿到真实写入序）。
     *
     * @param o 待裁剪对象
     * @return 裁剪后的对象（与入参同一引用）
     */
    public static JSONObject trimCache(JSONObject o) {
        return trimCache(o, null);
    }

    /**
     * 上限 20 条：按<b>真实写入序</b>淘汰最老的若干条，并保证 {@code newKey} 一定保留。
     *
     * <p>顺序来源是 {@link #KEY_DANMU_ORDER}（我们自己维护的键序数组），
     * 而不是 {@code JSONObject} 的迭代序 —— 后者在真机上不可用（见该常量的说明）。
     * 顺序信息缺失（首次运行、宿主清过存储）时退化为"迭代序淘汰 20 条"，
     * 但<b>仍然保证新写入的键不被淘汰</b>。
     *
     * @param o      待裁剪对象
     * @param newKey 刚刚写入的键；为 null 表示"无新键"
     * @return 裁剪后的对象（与入参同一引用）
     */
    public static JSONObject trimCache(JSONObject o, String newKey) {
        if (o == null) {
            return null;
        }
        if (o.length() <= CACHE_LIMIT) {
            recordOrder(orderOf(o), newKey);
            return o;
        }
        try {
            // 已知顺序：越靠前越老。顺序表里没有的键按"最老"处理（位置 -1）。
            final List<String> known = readOrder();
            List<String> keys = new ArrayList<>();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                keys.add(it.next());
            }
            keys.sort((a, b) -> {
                int ia = known.indexOf(a);
                int ib = known.indexOf(b);
                if (ia < 0) ia = -1;
                if (ib < 0) ib = -1;
                return Integer.compare(ia, ib);
            });

            // 要淘汰的条数（newKey 必须留住，所以它不参与淘汰候选）
            final String keepKey = newKey == null ? "" : newKey;
            int needDrop = keys.size() - CACHE_LIMIT;
            List<String> drop = new ArrayList<>();
            for (String k : keys) {
                if (needDrop <= 0) break;
                if (k.equals(keepKey)) continue;
                drop.add(k);
                needDrop--;
            }
            // 极端情况：除了 newKey 以外全被淘汰完还不够（不会发生：size>CACHE_LIMIT>=1）
            for (String k : keys) {
                if (needDrop <= 0) break;
                if (drop.contains(k)) continue;
                drop.add(k);
                needDrop--;
            }

            JSONObject keep = new JSONObject();
            List<String> keptOrder = new ArrayList<>();
            for (String k : keys) {
                if (drop.contains(k)) continue;
                keep.put(k, o.getString(k));
                keptOrder.add(k);
            }
            // 就地同步：把原对象清空后复制回去，保持调用方持有的引用有效
            List<String> all = new ArrayList<>();
            java.util.Iterator<String> it2 = o.keys();
            while (it2.hasNext()) {
                all.add(it2.next());
            }
            for (String k : all) {
                o.remove(k);
            }
            for (String k : keptOrder) {
                o.put(k, keep.getString(k));
            }
            recordOrder(keptOrder, newKey);
        } catch (Throwable e) {
            Log.w(TAG, "trimCache failed", e);
        }
        return o;
    }

    /** 读写入序（无则空表）；顺序表是 JSON 数组文本 */
    private static List<String> readOrder() {
        List<String> out = new ArrayList<>();
        String raw = KV.get(KEY_DANMU_ORDER);
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                String k = a.optString(i, "");
                if (!k.isEmpty()) {
                    out.add(k);
                }
            }
        } catch (Throwable ignored) {
            // 顺序表坏了就当没有：淘汰退化为迭代序，不影响正确性（只是不保证"最近"）
        }
        return out;
    }

    /** 把新键追加到写入序末尾（去重、只留最后 CACHE_LIMIT 个） */
    private static void recordOrder(List<String> kept, String newKey) {
        List<String> order = new ArrayList<>();
        for (String k : kept) {
            if (k != null && !k.isEmpty() && !order.contains(k)) {
                order.add(k);
            }
        }
        if (newKey != null && !newKey.isEmpty()) {
            order.remove(newKey);
            order.add(newKey);
        }
        while (order.size() > CACHE_LIMIT) {
            order.remove(0);
        }
        JSONArray a = new JSONArray();
        for (String k : order) {
            a.put(k);
        }
        KV.put(KEY_DANMU_ORDER, a.toString());
    }

    /** 当前对象的键（按传入对象的迭代序） */
    private static List<String> orderOf(JSONObject o) {
        List<String> out = new ArrayList<>();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            out.add(it.next());
        }
        return out;
    }

    /**
     * 还原上游 {@code getOriginalVideoName(String)}：网盘入口片名 → 搜索命中的真名。
     *
     * @param fallback 兜底返回的片名
     * @return 真名；无法确定时返回 {@code fallback}
     */
    public String getOriginalVideoName(String fallback) {
        String vodName = kvGet(KEY_DANMU_VOD_NAME);
        if (vodName.isEmpty()) {
            return vodName;
        }
        String searchName = kvGet(KEY_SEARCH_VOD_NAME);
        if (!searchName.isEmpty() && !"获取视频名称失败".equals(searchName)) {
            String cache = kvGet(KEY_DANMU_CACHE);
            if (cache.isEmpty()) {
                cache = "{}";
            }
            try {
                JSONObject o = new JSONObject(cache);
                if (!o.has(vodName)) {
                    o.put(vodName, searchName);
                }
                trimCache(o);
                kvPut(KEY_DANMU_CACHE, o.toString());
                kvPut(KEY_SEARCH_VOD_NAME, "");
            } catch (Throwable e) {
                Log.w(TAG, "getOriginalVideoName cache failed", e);
            }
        }
        String cache2 = kvGet(KEY_DANMU_CACHE);
        if (cache2.isEmpty()) {
            return vodName;
        }
        try {
            String v = new JSONObject(cache2).optString(vodName);
            return v.isEmpty() ? vodName : v;
        } catch (Throwable e) {
            return vodName;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 弹幕源：本地桥
    // ─────────────────────────────────────────────────────────────

    /**
     * 内置弹幕库 seam —— 对应上游 {@code merge.Z.C0288h.b(name, episode)}。
     *
     * <p><b>该分支未还原</b>：{@code merge.Z.C0288h} 属混淆私有库，静态分析只能看到
     * 调用点（{@code Danmu.java:44 / 124 / 169}）与「返回一个弹幕源字符串」这一事实，
     * 其数据源、索引格式、是否走网络均无法从反编译产物确定。凭空编一个
     * 「看起来合理」的实现会污染排查路径，故本类保留同名 seam：
     * <ul>
     *   <li>默认返回空串 —— 语义等价于「内置库没有命中」，
     *       后续 360 / 5266 两级兜底照常执行，功能不降级</li>
     *   <li>宿主可覆写为对私有库的调用，或喂入自有弹幕索引</li>
     * </ul>
     *
     * @param name 片名
     * @param episode 集号
     * @return 弹幕源字符串（{@code vodid://}/{@code vodurl://}/{@code http}/{@code XML} 四形态之一）；无则空串
     */
    public String builtInDanmuSource(String name, int episode) {
        if (builtInProvider != null) {
            try {
                String s = builtInProvider.source(name, episode);
                if (s != null && !s.isEmpty()) {
                    return s;
                }
            } catch (Throwable e) {
                Log.w(TAG, "builtInProvider failed", e);
            }
        }
        Log.d(TAG, "builtInDanmuSource 未命中（上游私有库分支未还原）, name=" + name + " ep=" + episode);
        return "";
    }

    /** 内置弹幕库提供方（宿主注入） */
    public interface BuiltInSourceProvider {
        /**
         * 查内置库。
         *
         * @param name    片名
         * @param episode 集号
         * @return 弹幕源；无则空串
         */
        String source(String name, int episode);
    }

    private static BuiltInSourceProvider builtInProvider;

    /**
     * 注入内置弹幕库提供方 —— 这是把上游私有库
     * {@code merge.Z.C0288h} 接回来的<b>正式接口</b>（见
     * {@link #builtInDanmuSource(String, int)} 的说明）。
     *
     * @param p 提供方；null 表示不接（此时该级直接落空，照常走 360 / 5266 兜底）
     */
    public static void setBuiltInSourceProvider(BuiltInSourceProvider p) {
        builtInProvider = p;
    }

    /**
     * 是否已接入内置弹幕库。
     *
     * @return true 表示 ext / 宿主提供了私有库适配
     */
    public static boolean hasBuiltInSource() {
        return builtInProvider != null;
    }

    /**
     * 还原上游 {@code getDanmuFrom5266(String, int)}：本地桥按片名+集号取弹幕。
     *
     * @param name 片名
     * @param episode 集号
     * @return XML 弹幕；桥不给空串或返回值里没有 {@code <d} 时返回空串
     */
    public String getDanmuFrom5266(String name, int episode) {
        String url = BRIDGE_5266 + "/danmu/auto?name=" + urlEncode(name)
                + "&episode=" + episode + "&format=xml";
        String body = fetch(url, noHeaders());
        if (body == null || body.isEmpty()) {
            return "";
        }
        return (body.indexOf("<d") < 0 || body.indexOf("</d>") < 0) ? "" : body;
    }

    /**
     * 还原上游 {@code getDanmuFrom5266Space(String, int)}：先用
     * {@link #getOriginalVideoName(String)} 换名再查。
     *
     * @param name 片名
     * @param episode 集号
     * @return XML 弹幕；取不到空串
     */
    public String getDanmuFrom5266Space(String name, int episode) {
        String real = getOriginalVideoName(name);
        String url = BRIDGE_5266 + "/danmu/auto?name=" + urlEncode(real)
                + "&episode=" + episode + "&format=xml";
        String body = fetch(url, noHeaders());
        if (body == null || body.isEmpty()) {
            return "";
        }
        return (body.indexOf("<d") < 0 || body.indexOf("</d>") < 0) ? "" : body;
    }

    // ─────────────────────────────────────────────────────────────
    // 弹幕源：360 影视
    // ─────────────────────────────────────────────────────────────

    /**
     * 时长 → 分类（还原上游三段式三目）。
     *
     * @param durationMs 毫秒
     * @return {@code 动漫} / {@code 电视剧} / {@code 电影}
     */
    public static String mediaCategory(long durationMs) {
        long minutes = (durationMs / 1000L) / 60L;
        return minutes < 30 ? "动漫" : minutes < 70 ? "电视剧" : "电影";
    }

    /**
     * 读 {@code 9978/media} 的 duration 并判分类。
     *
     * @return 分类；读不到或 duration=0 返回空串
     */
    public String mediaCategoryFromBridge() {
        String body = fetch(BRIDGE_9978 + "/media", noHeaders());
        if (body == null || body.isEmpty()) {
            return "";
        }
        try {
            long duration = new JSONObject(body).optLong("duration", 0);
            if (duration == 0) {
                return "";
            }
            return mediaCategory(duration);
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 还原上游 {@code getDanmuFromOK360(String, int)}：360 搜索 → 取源 URL。
     *
     * @param name    片名
     * @param episode 集号
     * @return 剧集页 URL；任一步失败返回空串
     */
    public String getDanmuFromOK360(String name, int episode) {
        String category = mediaCategoryFromBridge();
        if (category.isEmpty()) {
            return "";
        }
        String json = fetch(String.format(SO360_INDEX, urlEncode(name)), noHeaders());
        return parse360(json, episode, category, false);
    }

    /**
     * 还原上游 {@code getDanmuFromPanOK360(String, int)}：多一个
     * {@code titleTxt} 必须包含片名的过滤。
     *
     * @param name    片名
     * @param episode 集号
     * @return 剧集页 URL；失败空串
     */
    public String getDanmuFromPanOK360(String name, int episode) {
        String category = mediaCategoryFromBridge();
        if (category.isEmpty()) {
            return "";
        }
        String json = fetch(String.format(SO360_INDEX, urlEncode(name)), noHeaders());
        return parse360Filtered(json, episode, category, name, false);
    }

    /**
     * 还原上游 {@code getDanmuFromPanOK360Space(String, int)}：先换名再查，
     * 其余与 {@link #getDanmuFromPanOK360(String, int)} 相同。
     *
     * @param name    片名
     * @param episode 集号
     * @return 剧集页 URL；失败空串
     */
    public String getDanmuFromPanOK360Space(String name, int episode) {
        String category = mediaCategoryFromBridge();
        if (category.isEmpty()) {
            return "";
        }
        String real = getOriginalVideoName(name);
        String json = fetch(String.format(SO360_INDEX, urlEncode(real)), noHeaders());
        return parse360Filtered(json, episode, category, real, true);
    }

    /**
     * 360 响应解析（不带 titleTxt 过滤的版本，还原 {@code getDanmuFromOK360}）。
     *
     * @param json     360 响应体
     * @param episode  集号
     * @param category 分类（动漫/电视剧/电影）
     * @param strictPrefix true 用 {@code startsWith} 归一化（Pan 版本），false 用 {@code contains}
     * @return 剧集页 URL；失败空串
     */
    public static String parse360(String json, int episode, String category, boolean strictPrefix) {
        return parse360Filtered(json, episode, category, null, strictPrefix);
    }

    /**
     * 360 响应解析内核 —— 还原三个 {@code getDanmuFrom*360*} 的共同主体。
     *
     * <p>差异只在两处（本方法用两个参数表达，避免三份复制）：
     * <ul>
     *   <li>{@code titleFilter}：非空时要求 {@code titleTxt} 为空或包含该串
     *       （Pan 系列的行为）</li>
     *   <li>{@code strictPrefix}：URL 归一化用 {@code startsWith} 还是 {@code contains}</li>
     * </ul>
     *
     * @param json         360 响应体
     * @param episode      集号
     * @param category     分类
     * @param titleFilter  片名过滤，可为 null
     * @param strictPrefix 是否严格前缀归一化
     * @return 剧集页 URL；失败空串
     */
    public static String parse360Filtered(String json, int episode, String category,
                                          String titleFilter, boolean strictPrefix) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            JSONObject data = new JSONObject(json).optJSONObject("data");
            if (data == null) {
                return "";
            }
            JSONObject longData = data.optJSONObject("longData");
            if (longData == null) {
                return "";
            }
            JSONArray rows = longData.optJSONArray("rows");
            if (rows == null || rows.length() == 0) {
                return "";
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                if (titleFilter != null) {
                    String titleTxt = row.optString("titleTxt");
                    if (!(titleTxt.isEmpty() || titleTxt.contains(titleFilter))) {
                        continue;
                    }
                }
                if (!category.equals(row.optString("cat_name"))) {
                    continue;
                }
                String url;
                if ("电影".equals(category)) {
                    url = pickMoviePlaylink(row.optJSONObject("playlinks"));
                } else {
                    JSONArray series = row.optJSONArray("seriesPlaylinks");
                    if (series == null || series.length() == 0) {
                        return "";
                    }
                    int idx = (episode <= 0 ? 0 : episode) - 1;
                    if (idx < 0 || idx >= series.length()) {
                        return "";
                    }
                    JSONObject one = series.optJSONObject(idx);
                    if (one == null) {
                        return "";
                    }
                    url = one.optString("url");
                }
                if (url.isEmpty()) {
                    return "";
                }
                return normalizePlayUrl(url, strictPrefix);
            }
            return "";
        } catch (Throwable e) {
            Log.w(TAG, "parse360 failed", e);
            return "";
        }
    }

    /**
     * 电影 playlinks 取值（上游是四层嵌套 if-else，优先级 qq &gt; qiyi &gt; youku &gt; imgo）。
     *
     * @param playlinks playlinks 对象，可为 null
     * @return 播放页 URL；全空返回空串
     */
    public static String pickMoviePlaylink(JSONObject playlinks) {
        if (playlinks == null) {
            return "";
        }
        String qq = playlinks.optString("qq");
        if (!qq.isEmpty()) {
            return qq;
        }
        String qiyi = playlinks.optString("qiyi");
        if (!qiyi.isEmpty()) {
            return qiyi;
        }
        String youku = playlinks.optString("youku");
        if (!youku.isEmpty()) {
            return youku;
        }
        String imgo = playlinks.optString("imgo");
        return imgo.isEmpty() ? "" : imgo;
    }

    /**
     * 播放页 URL 归一化（还原上游 4 个分支）。
     *
     * @param url          原始 URL
     * @param strictPrefix true 表示 Pan 版本（先判 {@code startsWith} 域名），
     *                     false 表示 OK360 版本（只判 {@code contains}）
     * @return 归一化后的 URL
     */
    public static String normalizePlayUrl(String url, boolean strictPrefix) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        boolean qq = strictPrefix ? url.startsWith("https://v.qq.com/") : url.contains("v.qq.com");
        boolean iqiyi = strictPrefix ? url.startsWith("https://www.iqiyi.com/") : url.contains("www.iqiyi.com");
        boolean mgtv = strictPrefix ? url.startsWith("http://www.mgtv.com/") : url.contains("www.mgtv.com");
        boolean youku = strictPrefix ? url.startsWith("https://v.youku.com") : url.contains("v.youku.com");

        if (qq && url.contains(".html")) {
            int idx = url.indexOf(".html");
            if (idx != -1) {
                return url.substring(0, idx + ".html".length());
            }
        } else if (iqiyi && url.contains(".html")) {
            int idx = url.indexOf(".html");
            if (idx != -1) {
                return url.substring(0, idx + ".html".length());
            }
        } else if (mgtv && url.contains(".html")) {
            int idx = url.indexOf(".html");
            if (idx != -1) {
                return url.substring(0, idx + ".html".length());
            }
        } else if (youku) {
            int idx = url.indexOf("vid=");
            if (idx != -1) {
                int from = idx + 4;
                int end = url.indexOf("&", from);
                if (end == -1) {
                    end = url.length();
                }
                String vid = url.substring(from, end);
                if (!vid.isEmpty()) {
                    return "https://v.youku.com/v_show/id_" + vid + ".html";
                }
            }
        }
        return url;
    }

    // ─────────────────────────────────────────────────────────────
    // 弹幕源理址
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code formatDanmuUrl2(String)}：把 4 种形态的弹幕源变成 XML。
     *
     * @param src 弹幕源（{@code XML} / {@code http} / {@code vodid://} / {@code vodurl://}）
     * @return XML 弹幕；不可识别返回空串
     */
    public String formatDanmuUrl2(String src) {
        if (src == null || src.isEmpty()) {
            return "";
        }
        if (src.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")) {
            return src;
        }
        if (src.startsWith("http")) {
            return fetch(BRIDGE_5266 + "/danmu/get?url=" + src + "&format=xml", noHeaders());
        }
        if (src.startsWith("vodid://")) {
            String[] parts = src.substring("vodid://".length()).split("@");
            if (parts.length < 2) {
                return "";
            }
            return fetch(BRIDGE_5266 + "/danmu/get?url=" + parts[0]
                    + "&platform=" + parts[1] + "&format=xml", noHeaders());
        }
        if (!src.startsWith("vodurl://")) {
            return "";
        }
        String[] parts = src.substring("vodurl://".length()).split("@");
        if (parts.length < 4) {
            return "";
        }
        processDanmuRequest(parts[0], parts[3]);
        return fetch(BRIDGE_5266 + "/danmu/auto?name=" + parts[0]
                + "&episode=" + parts[1] + "&total=" + parts[2]
                + "&platform=" + parts[3] + "&format=xml", noHeaders());
    }

    /**
     * 还原上游 {@code formatDanmuUrl(String)}：依次打 4 个外部弹幕聚合站，
     * 谁返回 {@code {"code":23,...}} 就用谁。
     *
     * @param url 影片页 URL
     * @return 聚合站响应体；四个都失败返回空串
     */
    public String formatDanmuUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        String[] endpoints = {
                "https://danmu.huaqi.pro/?url=" + url,
                "https://dmku.hls.one/?ac=dm&url=" + url,
                "https://danmu.zxz.ee/?type=json&id=" + url,
                "https://dm.ruyijx.com?ac=dm&url=" + url,
        };
        for (String ep : endpoints) {
            String body = fetch(ep, noHeaders());
            if (body != null && !body.isEmpty() && body.startsWith("{") && body.contains("\"code\":23")) {
                return body;
            }
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────
    // 候选列表（还原 Danmaku）
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code Danmaku(Map)}：查本地桥的弹幕索引，产出候选列表。
     *
     * @param map 宿主参数（{@code name} / {@code episode}）
     * @return 候选 JSON 数组串（永不为 null）
     */
    public JSONArray danmakuCandidates(Map<String, String> map) {
        Map<String, String> params = map == null ? new LinkedHashMap<String, String>() : map;
        String keyword = getCachedSearchKeyword(params.get("name"));
        int episode = d(params.get("episode"));
        String raw = fetch(BRIDGE_5266 + "/danmu/search?keywords=" + keyword, null);
        if (raw == null) {
            raw = "{}";
        }
        JSONArray out = new JSONArray();
        try {
            JSONObject root = new JSONObject(raw);
            java.util.Iterator<String> it = root.keys();
            while (it.hasNext()) {
                JSONArray group = root.optJSONArray(it.next());
                if (group == null) {
                    continue;
                }
                for (int i = 0; i < group.length(); i++) {
                    JSONObject item = group.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String vodName = item.optString("vod_name");
                    String platform = item.optString("platform");
                    int count = parseIntSafe(item.optString("count"), 0);
                    String display = vodName + "  •  " + platform
                            + "  第" + episode + "集  (共" + count + "集)";
                    String url = BRIDGE_9978 + "/proxy?do=danmu&site=js&url=vodurl://"
                            + vodName + "@" + episode + "@" + count + "@" + platform;
                    JSONObject row = new JSONObject();
                    row.put("name", display);
                    row.put("url", url);
                    row.put("source", platform);
                    out.put(row);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "danmakuCandidates parse failed", e);
        }
        return filterDanmuResult(out, params);
    }

    // ─────────────────────────────────────────────────────────────
    // 三个入口（还原 AppDanmu / Danmu / DiyDanmu）
    // ─────────────────────────────────────────────────────────────

    /** 空弹幕响应（上游逐字：{@code {200,"application/xml",空流}}） */
    public static Object[] emptyDanmuResponse() {
        return new Object[]{200, "application/xml",
                new ByteArrayInputStream("".getBytes(Charset.forName("UTF-8")))};
    }

    /**
     * 弹幕响应封装（上游直接改 {@code objArr[2]}）。
     *
     * @param xml 弹幕 XML，可为 null
     * @return {@code {200, "application/xml", stream}}
     */
    public static Object[] danmuResponse(String xml) {
        return new Object[]{200, "application/xml",
                new ByteArrayInputStream((xml == null ? "" : xml).getBytes(Charset.forName("UTF-8")))};
    }

    /**
     * 是否开启彩色弹幕（还原上游读 {@code /config.json} 的 {@code danmuColor == "彩色"}）。
     *
     * @param configJson 宿主 config.json 内容，可为 null
     * @return true 表示彩色
     */
    public static boolean isColoredConfig(String configJson) {
        if (configJson == null || configJson.isEmpty()) {
            return false;
        }
        try {
            return "彩色".equals(new JSONObject(configJson).optString("danmuColor"));
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 弹幕源兜底链（AppDanmu / DiyDanmu 共用，还原上游同样的三段尝试）。
     *
     * @param name        片名
     * @param episode     集号
     * @param panVariant  是否走网盘变体（5 级链）
     * @return 弹幕源；全失败空串
     */
    public String resolveDanmuSource(String name, int episode, boolean panVariant) {
        String src = builtInDanmuSource(name, episode);
        if (!src.isEmpty()) {
            return src;
        }
        if (panVariant) {
            src = getDanmuFromPanOK360(name, episode);
            if (!src.isEmpty()) {
                return src;
            }
            src = getDanmuFromPanOK360Space(name, episode);
            if (!src.isEmpty()) {
                return src;
            }
        } else {
            src = getDanmuFromOK360(name, episode);
            if (!src.isEmpty()) {
                return src;
            }
        }
        src = getDanmuFrom5266(name, episode);
        if (!src.isEmpty()) {
            return src;
        }
        if (panVariant) {
            src = getDanmuFrom5266Space(name, episode);
            if (!src.isEmpty()) {
                return src;
            }
        }
        return "";
    }

    /**
     * 还原上游 {@code AppDanmu(Map)}：{@code vodName} + {@code vodIndex} 取弹幕。
     *
     * @param map 宿主参数
     * @return {@code {200,"application/xml",stream}}
     */
    public Object[] appDanmu(Map<String, String> map) {
        Map<String, String> p = map == null ? new LinkedHashMap<String, String>() : map;
        String vodName = p.get("vodName");
        if (TextUtils.isEmpty(vodName)) {
            return emptyDanmuResponse();
        }
        String realName = getRealName(vodName);
        int episode = d(p.get("vodIndex"));
        boolean colored = isColoredConfig(readHostConfig());
        String src = resolveDanmuSource(realName, episode, false);
        if (src.isEmpty()) {
            Log.i(TAG, MSG_FAIL);
            return emptyDanmuResponse();
        }
        String xml = formatDanmuUrl2(src);
        String result = colored ? updateDanmuColors(xml) : updateDanmuWhite(xml);
        if (result != null && !result.isEmpty()) {
            Log.i(TAG, MSG_OK);
        }
        kvPut(KEY_SEARCH_VOD_NAME, "");
        return danmuResponse(result);
    }

    /**
     * 还原上游 {@code DiyDanmu(Map)}：读 {@code 9978/media} 的 title/artist。
     *
     * @param map 宿主参数（上游未使用 map 内容）
     * @return {@code {200,"application/xml",stream}}
     */
    public Object[] diyDanmu(Map<String, String> map) {
        String media = fetch(BRIDGE_9978 + "/media", noHeaders());
        JSONObject o;
        try {
            o = new JSONObject(media == null ? "{}" : media);
        } catch (Throwable e) {
            o = new JSONObject();
        }
        String realName = getRealName(o.optString("title"));
        String artist = o.optString("artist");
        String name = artist.isEmpty() ? realName : artist;
        if (realName.isEmpty()) {
            return emptyDanmuResponse();
        }
        int episode = d(name);
        boolean colored = isColoredConfig(readHostConfig());
        String src = resolveDanmuSource(realName, episode, false);
        if (src.isEmpty()) {
            Log.i(TAG, MSG_FAIL);
            return emptyDanmuResponse();
        }
        String xml = formatDanmuUrl2(src);
        String result = colored ? updateDanmuColors(xml) : updateDanmuWhite(xml);
        if (result != null && !result.isEmpty()) {
            Log.i(TAG, MSG_OK);
        }
        kvPut(KEY_SEARCH_VOD_NAME, "");
        return danmuResponse(result);
    }

    /**
     * 还原上游 {@code Danmu(Map)}：按 {@code site} 分派
     * （{@code js} = 直接给源；{@code wangpan} = 从网盘入口上下文取）。
     *
     * @param map 宿主参数（{@code site} / {@code url}）
     * @return {@code {200,"application/xml",stream}}
     */
    public Object[] danmu(Map<String, String> map) {
        Map<String, String> p = map == null ? new LinkedHashMap<String, String>() : map;
        boolean colored = isColoredConfig(readHostConfig());
        String site = p.get("site");
        String url = p.get("url");
        if (!TextUtils.isEmpty(url) && !url.startsWith("http")) {
            url = urlDecode(url);
        }
        String result = "";
        if ("js".equals(site)) {
            String xml = formatDanmuUrl2(url);
            result = colored ? updateDanmuColors(xml) : updateDanmuWhite(xml);
            Log.i(TAG, result == null || result.isEmpty() ? MSG_FAIL : MSG_OK);
        } else if ("wangpan".equals(site)) {
            String vodName = kvGet(KEY_DANMU_VOD_NAME);
            if (TextUtils.isEmpty(vodName)) {
                // 上游此处直接 split 会 NPE，本实现守卫（见类注释缺陷 2）
                Log.i(TAG, MSG_FAIL);
                kvPut(KEY_SEARCH_VOD_NAME, "");
                return danmuResponse("");
            }
            String name = vodName.split(" ")[0];
            int episode = d(kvGet(KEY_DANMU_VOD_INDEX));
            String src = resolveDanmuSource(name, episode, true);
            if (src.isEmpty()) {
                result = "";
                Log.i(TAG, MSG_FAIL);
            } else {
                String xml = formatDanmuUrl2(src);
                result = colored ? updateDanmuColors(xml) : updateDanmuWhite(xml);
                if (result != null && !result.isEmpty()) {
                    Log.i(TAG, MSG_OK);
                }
            }
        }
        kvPut(KEY_SEARCH_VOD_NAME, "");
        return danmuResponse(result);
    }

    /**
     * 读宿主 {@code /config.json}（上游 {@code k.a(k.d("/config.json"))}）。
     *
     * <p>上游读的是宿主私有目录，本类改为可覆写方法；默认返回空串
     * （即「非彩色」），宿主接入时覆写为真实读取。
     *
     * @return config.json 内容；无则空串
     */
    public String readHostConfig() {
        String fromKv = kvGet("__host_config_json");
        return fromKv == null ? "" : fromKv;
    }

    /**
     * 注入宿主 config.json（测试 / 宿主适配用）。
     *
     * @param json config.json 内容
     */
    public void setHostConfig(String json) {
        kvPut("__host_config_json", json == null ? "" : json);
    }
}
