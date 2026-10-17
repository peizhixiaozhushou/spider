package com.example.tvbox.plain.pan;

import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.plain.pan.client.PanCreds;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * UC / 夸克「网盘维护例程」基类 —— 还原上游 {@code com.github.catvod.spider.UCPan}
 * 与 {@code QuarkPan} 两个类。
 *
 * <h2>1. 这两个类是什么（与 {@code PanUC}/{@code PanQuark} 的分工）</h2>
 *
 * <p>反编译目录里同一家网盘各有<b>两个</b>类，基类不同、职责不同，<b>都要保留</b>：
 *
 * <table border="1">
 *   <tr><th>上游类</th><th>基类</th><th>行数</th><th>职责</th><th>关键方法</th></tr>
 *   <tr>
 *     <td>{@code PanQuark} / {@code PanUC}</td>
 *     <td>{@code extends Pan}</td>
 *     <td>231 / 231</td>
 *     <td><b>TVBox 内容源（UI 层）</b>：给宿主提供首页/分类/详情，列目录、生成播放条目</td>
 *     <td>{@code homeContent / categoryContent / detailContent / action}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code QuarkPan} / {@code UCPan}（<b>本类还原对象</b>）</td>
 *     <td>{@code extends Spider}</td>
 *     <td>193 / 190</td>
 *     <td><b>无界面维护例程（后台层）</b>：读登录态、每日续期、7 天清临时文件</td>
 *     <td>{@code checkquarkcookie / checktime / deletetime / l() / m()}</td>
 *   </tr>
 * </table>
 *
 * <p><b>判定依据（不是猜测，逐条给行号）</b>：
 * <ol>
 *   <li><b>基类不同即入口不同</b>：{@code QuarkPan} 的第 28 行是 {@code extends Spider}，
 *       {@code PanQuark} 的第 29 行是 {@code extends Pan}。TVBox 的 {@code Pan} 是
 *       「分享链接解析器」模板（{@code PanQuark} 覆写了 {@code detailContent}，
 *       并用 {@code merge.p028c.e} 造播放条目），而裸 {@code Spider} 只能作为独立 spider 被调度。</li>
 *   <li><b>方法集完全不重叠</b>：{@code QuarkPan} 的 10 个方法
 *       （见 {@code ae/decompiled/_index.json} 的 {@code methods} 字段）里
 *       <b>没有任何</b> {@code homeContent/categoryContent/detailContent/searchContent/playerContent}；
 *       {@code PanQuark} 的 8 个方法里<b>没有</b> {@code checktime/deletetime/deleteQuarkTemp}。
 *       即：不存在「新版覆写了旧版」的关系 —— 它们连一个同名方法都没有。</li>
 *   <li><b>不可互换</b>：{@code QuarkPan} 是 {@code static} 工具类
 *       （{@code checkquarkcookie()/checktime()/deletetime()/getCache(String)} 全 static），
 *       被设计为「宿主定时调用一次」；{@code PanQuark} 是实例类，
 *       字段是 {@code ProgressDialog i} / {@code Handler} / {@code boolean busy}，
 *       需要在 UI 线程跑。一个是 cron，一个是页面。</li>
 *   <li><b>共享同一份混淆字符串表</b>：两者都用 ucpro 云盘的同一套 URL
 *       （{@code /1/clouddrive/file/sort} 等），所以字面量看起来"重复" ——
 *       这也是早期把二者误判成"新旧两版"的诱因。{@code PanQuark} 的网络动作在
 *       {@code merge.p027b.w}（{@code this.h.r/u/w}），{@code QuarkPan} 的网络动作在
 *       {@code merge.p036k.b}，两条通道不同。</li>
 * </ol>
 *
 * <p><b>所以</b>：
 * <ul>
 *   <li>本类（{@link UCQuarkMaintBase} 及其子类 {@code QuarkPanPlain} / {@code UCPanPlain}）
 *       覆盖 {@code QuarkPan.java} / {@code UCPan.java}；
 *   <li>{@code PanQuarkPlain} / {@code PanUCPlain}（{@code pan.client} 包）覆盖
 *       {@code PanQuark.java} / {@code PanUC.java}。
 *   <li>既有 {@code ae/tests/QuarkPanTest.java} / {@code UCPanTest.java} 测的是
 *       <b>client 层</b>，其文件名叫 {@code *PanTest} 与 {@code PanQuark} 类名不同位；
 *       本任务新增的测试因此命名为 {@code QuarkPanMaintTest / UCPanMaintTest}，
 *       避免覆盖队友的测试。</li>
 * </ul>
 *
 * <h2>2. 维护例程到底做什么</h2>
 * <pre>
 *   checkXXXcookie()   读登录态（两级文件降级，见 §4）
 *   checktime()        cache 里的 yyyyMMdd &lt; 今天 → 跑 l()（每日一次续期）
 *   l()                ① POST /share/sharepage/token  {pwd_id:&lt;硬编码分享&gt;, passcode:""}
 *                      ② GET  /share/sharepage/detail pdir_fid=0         → data.share.first_fid
 *                      ③ GET  /share/sharepage/detail pdir_fid=first_fid → data.list
 *                      ④ m()：找到/创建 Quarktemp 目录，拿它的 fid
 *                      ⑤ 从 list 里随机挑一个文件 → fid + share_fid_token
 *                      ⑥ POST /share/sharepage/save 把该文件转存进 Quarktemp
 *                      ⑦ 响应 message == "ok" → 写 qktime=今天
 *   deletetime()       cache 里的 deleteqktime 距今 ≥ 7 天 → 跑 deleteQuarkTemp()
 *   deleteQuarkTemp()  ① GET /file/sort 找 Quarktemp 的 fid
 *                      ② GET /file/sort?pdir_fid=该fid 列出里面所有文件
 *                      ③ 逐个 POST /file/delete {action_type:2, filelist:[fid]}
 *                      ④ 写 deleteqktime=今天
 * </pre>
 * 目的：让账号每天都有一条「自己盘里的新鲜转存」可用，同时不让临时文件无限堆积。
 *
 * <h2>3. 字符串池解码参数（每个调用点，{@code (from, len, seed)}）</h2>
 * 全部 148 条见 {@code ae/work/w-pan/decoded-strings.txt}；按品牌分组的可重算版本见
 * {@code maintain/evidence/QuarkPan_strings.md} 与 {@code maintain/evidence/UCPan_strings.md}。
 * 解码算法 = 上游 {@code d.c(short[],int,int,int)}：{@code out[i]=(char)(arr[off+i]^seed)}，
 * 由 {@link com.example.tvbox.plain.pan.client.PanObfStrings#decode} 复现。
 * <p>例（夸克，调用点 → 值）：
 * <pre>
 *   L35  d.c(0,    9,   1180) → "Quarktemp"
 *   L41  d.c(9,    30,  1846) → "/sdcard/TVBox/quark_cookie.txt"
 *   L52  d.c(45,   27,  2405) → "/sdcard/TV/quark_cookie.txt"
 *   L66  a.e(72,   6,   2315) → "qktime"
 *   L99  c.e(730,  12,  1436) → "deleteqktime"
 *   L132 a.e(1274, 12,  1000) → "369868287b83"      (默认分享 id)
 *   L153 c.e(1418, 74,  2649) → "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/save?pr=ucpro&amp;fr=pc"
 * </pre>
 *
 * <h2>4. 上游缺陷（本实现逐条修正并标注）</h2>
 * <ol>
 *   <li><b>{@code static} 可变状态跨实例污染</b>（{@code QuarkPan:33-34} / {@code UCPan:32-33}）：
 *       {@code private static String d}（cookie）与 {@code private static String y}（temp 目录 fid）
 *       是 {@code static}。一个进程里只要有两个实例（或两台账号），后读的会覆盖前一个，
 *       转存会落到别人的目录。→ 本实现改为<b>实例字段</b> {@link #cookie} / {@link #tempDirFid}。</li>
 *   <li><b>用 {@code contains("{")} 判断"是不是 JSON"</b>（{@code QuarkPan:139-144} / {@code UCPan:136-141}）：
 *       一个 500 错误页里带花括号也算成功，随后 {@code new JSONObject} 抛异常。
 *       → 本实现直接尝试解析并校验必需字段（{@link #parseObject}、{@link #pathString}）。</li>
 *   <li><b>日期格式化不指定 Locale</b>（{@code new SimpleDateFormat("yyyyMMdd")}）：
 *       在 Thai（{@code th_TH}）等区域会输出佛历年份（2568 而非 2025），
 *       于是「缓存 ≥ 今天」永远不成立 ⇒ 每次启动都重复转存。
 *       → 本实现固定 {@link Locale#US}。</li>
 *   <li><b>缓存值不校验直接 {@code parseInt}</b>（{@code checktime()} / {@code deletetime()}）：
 *       缓存被截断或写入脏数据时 {@code NumberFormatException} 逃出（{@code deletetime} 无 try）。
 *       → 本实现 {@link #parseYyyyMMdd} 解析失败按 0 处理（视为过期，只会更积极地维护）。</li>
 *   <li><b>{@code deletetime()} 的 {@code substring(0,4)}</b>：缓存短于 8 字符直接抛
 *       {@code StringIndexOutOfBoundsException}。同上修正。</li>
 *   <li><b>删文件不看响应</b>（{@code deleteQuarkTemp():86}）：POST delete 的返回值整个丢弃，
 *       401/风控时仍写「已清理」时间戳，于是 7 天内不再重试。
 *       → 本实现仍保留写入时机（与上游一致），但把每次响应记入日志，并把成功计数据实返回。</li>
 *   <li><b>建目录后不校验</b>（{@code m():189}）：直接 {@code get("data").get("fid")}，
 *       目录名已存在时 UC 返回的是 {@code code!=0}，取 fid 抛异常（被外层 catch 吞掉）。
 *       → 本实现先查列表、再建，取 fid 失败返回空串。</li>
 *   <li><b>随机挑文件</b>（{@code new Random().nextInt(len)}）：每 7 天转存内容不确定。
 *       → 保留行为，但随机源可注入（{@link #setRandomForTest}）。</li>
 * </ol>
 *
 * <h2>5. 与 common 基础设施的对应关系</h2>
 * <table border="1">
 *   <tr><th>上游私有库调用</th><th>本实现</th></tr>
 *   <tr><td>{@code merge.p036k.b.l(url, headers)}（GET）</td><td>{@link #fetchWith}</td></tr>
 *   <tr><td>{@code merge.p036k.b.f(url, body, headers).a()}（POST）</td><td>{@link #postWith}</td></tr>
 *   <tr><td>{@code merge.p038m.k.a(File)}（读文件）</td><td>{@link #readTextFile} / {@link PanCreds#readFile}</td></tr>
 *   <tr><td>{@code merge.p038m.k.c(File, String)}（写文件）</td><td>{@link #writeTextFile} / {@link PanCreds#writeFile}</td></tr>
 *   <tr><td>{@code merge.p038m.k.d(String)}（缓存文件）</td><td>{@link #cacheFile}</td></tr>
 *   <tr><td>{@code merge.p027b.v.b(k,v)}（两元素 Map）</td><td>{@link #requestHeaders}</td></tr>
 *   <tr><td>{@code merge.A.a.c(String)}（gson → JsonObject）</td><td>{@link #parseObject}</td></tr>
 *   <tr><td>{@code merge.A.a.e(json, field)}（取字段）</td><td>{@link JSONObject#optString}</td></tr>
 *   <tr><td>{@code merge.p026a.a.a(String)}</td><td><b>未反编译</b>；从上下文（拼接 JSON 片段、若做转义会破坏结构）推定为恒等，见 §6</td></tr>
 *   <tr><td>{@code merge.p027b.h.b(a,b,c)}（三串拼接）</td><td>直接 {@code +}</td></tr>
 * </table>
 *
 * <h2>6. 未能还原的分支（诚实清单）</h2>
 * <ul>
 *   <li>{@code merge.p026a.a.a(String)} 的方法体未取到反编译输出。它只出现在
 *       {@code m()} 的建目录 body 里包一个 JSON 前缀，任何"转义型"实现都会让 body 非法，
 *       故推定为恒等；若它其实是别的（例如去空白），对本 body 无影响。</li>
 *   <li>{@code merge.p027b.h.b(..)} / {@code merge.p027b.v.b(..)} 未反编译。
 *       三串拼接与「两元素 Map」是唯一能解释调用点语义的读法，但没有字节码佐证。</li>
 * </ul>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/QuarkPan.java}(193 行) /
 * {@code ae/decompiled/UCPan.java}(190 行)；
 * 解码器: {@code ae/decompiled/{a,b,c,d,e}.java}；
 * 方法/字段清单: {@code ae/decompiled/_index.json}；
 * 字符串清单: {@code maintain/evidence/QuarkPan_strings.md}、{@code maintain/evidence/UCPan_strings.md}。
 */
public abstract class UCQuarkMaintBase extends BaseSpider {

    private static final String TAG = "UCQuarkMaint";

    // ─────────────────────────────────────────────────────────────
    // 协议常量（两家逐字一致，逐条对应解码调用点）
    // ─────────────────────────────────────────────────────────────

    /** 分享详情查询的固定尾串（夸克 {@code L123 e.c(1053,92,365)} / UC {@code L120 c.e(971,92,2280)}） */
    public static final String SHARE_DETAIL_TAIL =
            "&_size=9999&_fetch_banner=1&_fetch_share=1&_fetch_total=1&_sort=file_type:asc,file_name:desc";

    /** 删文件 body 前缀（夸克 {@code L86 d.c(508,30,2462)} / UC {@code L83 d.c(443,30,2156)}） */
    public static final String BODY_DELETE_PREFIX = "{\"action_type\":2,\"filelist\":[\"";
    /** 删文件 body 后缀（夸克 {@code L86 c.e(538,21,3126)} / UC {@code L83 a.e(473,21,2647)}） */
    public static final String BODY_DELETE_SUFFIX = "\"],\"exclude_fids\":[]}";
    /** 建目录 body 前缀（夸克 {@code L189 a.e(1797,29,2410)} / UC {@code L186 e.c(1703,29,497)}） */
    public static final String BODY_MKDIR_PREFIX = "{\"pdir_fid\":\"0\",\"file_name\":\"";
    /** 建目录 body 后缀（夸克 {@code L189 a.e(1826,38,1121)} / UC {@code L186 a.e(1732,38,1722)}） */
    public static final String BODY_MKDIR_SUFFIX = "\",\"dir_path\":\"\",\"dir_init_lock\":false}";
    /** 换 stoken body 前缀（夸克 {@code L135 d.c(1359,11,2575)} / UC {@code L132 a.e(1282,11,1270)}） */
    public static final String BODY_TOKEN_PREFIX = "{\"pwd_id\":\"";
    /** 换 stoken body 后缀（夸克 {@code L135 e.c(1370,16,1563)} / UC {@code L132 d.c(1293,16,1895)}） */
    public static final String BODY_TOKEN_SUFFIX = "\",\"passcode\":\"\"}";
    /** 转存 body：{@code {"fid_list":["} */
    public static final String BODY_SAVE_A = "{\"fid_list\":[\"";
    /** 转存 body：{@code "],"fid_token_list":["} */
    public static final String BODY_SAVE_B = "\"],\"fid_token_list\":[\"";
    /** 转存 body：{@code "],"pdir_fid":"0","to_pdir_fid":"} */
    public static final String BODY_SAVE_C = "\"],\"pdir_fid\":\"0\",\"to_pdir_fid\":\"";
    /** 转存 body：{@code ","pwd_id":"} */
    public static final String BODY_SAVE_D = "\",\"pwd_id\":\"";
    /** 转存 body：{@code ","stoken":"} */
    public static final String BODY_SAVE_E = "\",\"stoken\":\"";
    /** 转存 body 收尾 */
    public static final String BODY_SAVE_F = "\"}";

    /** 转存成功的响应标记（夸克 {@code L156 c.e(1587,2,2142)} / UC {@code L153 a.e(1509,2,1008)}） */
    public static final String MSG_OK = "ok";

    /** JSON 字段名（全部来自混淆表，见各调用点注释） */
    public static final String F_DATA = "data";
    public static final String F_LIST = "list";
    public static final String F_STOKEN = "stoken";
    public static final String F_SHARE = "share";
    public static final String F_FIRST_FID = "first_fid";
    public static final String F_FID = "fid";
    public static final String F_FILE_NAME = "file_name";
    public static final String F_SHARE_FID_TOKEN = "share_fid_token";
    public static final String F_MESSAGE = "message";
    public static final String F_COOKIE = "cookie";

    /** 请求头字段名 */
    public static final String H_UA = "User-Agent";
    public static final String H_COOKIE = "Cookie";
    public static final String H_CONTENT_TYPE = "Content-Type";
    public static final String H_REFERER = "Referer";
    public static final String H_ORIGIN = "Origin";
    public static final String V_CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    /** 清理阈值：天 */
    public static final int CLEAN_AFTER_DAYS = 7;
    /** 一天的毫秒数（上游字面量 {@code 86400000}） */
    public static final long DAY_MS = 86400000L;

    // ─────────────────────────────────────────────────────────────
    // 实例状态（★ 上游是 static，见类注释 §4 缺陷 1）
    // ─────────────────────────────────────────────────────────────

    /** 登录态 cookie —— 上游 {@code private static String d}，本实现改实例字段 */
    private String cookie = "";

    /** 临时目录的 fid —— 上游 {@code private static String y}，本实现改实例字段 */
    private String tempDirFid = "";

    /** 今日日期覆盖（测试用；空 = 用系统时钟） */
    private String todayOverride = "";

    /** 当前时间覆盖（测试用；&lt;0 = 用系统时钟） */
    private long nowOverride = -1L;

    /** 随机源（测试用；null = 每次新建，与上游 {@code new Random()} 一致） */
    private Random random;

    /** 缓存目录（上游 {@code merge.p038m.k.d} 落盘处） */
    private MaintCache cache = new MaintCache("");

    // ─────────────────────────────────────────────────────────────
    // 品牌钩子
    // ─────────────────────────────────────────────────────────────

    /** 品牌中文名：{@code "夸克"} / {@code "UC"} */
    public abstract String brandName();

    /** 凭据文件名：{@code quark_cookie.txt} / {@code uc_cookie.txt} */
    public abstract String brandCookieFile();

    /** 临时目录名：{@code Quarktemp} / {@code UCtemp} */
    public abstract String brandTempDirName();

    /** 续期日期缓存键：{@code qktime} / {@code uctime} */
    public abstract String brandTimeKey();

    /** 清理日期缓存键：{@code deleteqktime} / {@code deleteuctime} */
    public abstract String brandDeleteTimeKey();

    /** 上游硬编码的「续期用分享」id */
    public abstract String defaultShareId();

    /** 完整业务 UA（逐字，含 Channel 尾串） */
    public abstract String brandUserAgent();

    /** Referer */
    public abstract String brandReferer();

    /** Origin */
    public abstract String brandOrigin();

    /**
     * 列根目录 URL —— {@code deleteXXXTemp()} 用的那一条。
     *
     * <p>注意夸克有<b>两条不同</b>的根目录 URL：清理流程用
     * {@code _sort=file_type:asc,file_name:asc}，而 {@code m()} 找临时目录用
     * {@code _sort=file_type:asc,updated_at:desc}。UC 两条恰好相同。
     */
    public abstract String rootSortUrl();

    /**
     * {@code m()} 里"找临时目录"用的根目录 URL。
     *
     * <p>默认与 {@link #rootSortUrl()} 相同（UC 即如此）；夸克覆写为 updated_at 版本。
     *
     * @return 列根目录 URL
     */
    public String dirScanSortUrl() {
        return rootSortUrl();
    }

    /** 列子目录 URL（{@code &pdir_fid=<fid>&_page=1&_size=100&...}） */
    public abstract String subdirSortUrl(String pdirFid);

    /** 删文件 URL */
    public abstract String deleteUrl();

    /** 建目录 URL */
    public abstract String createDirUrl();

    /** 换 stoken URL */
    public abstract String shareTokenUrl();

    /** 分享详情 URL 前缀（到 {@code pwd_id=} 为止，后面由 {@link #buildShareDetailUrl} 拼） */
    public abstract String shareDetailBase();

    /** 转存 URL */
    public abstract String shareSaveUrl();

    // ─────────────────────────────────────────────────────────────
    // 可覆写的 IO 层（测试注入点；必须 public —— 测试在默认包）
    // ─────────────────────────────────────────────────────────────

    /**
     * GET 一个 URL（不带附加头）。
     *
     * @param url 完整 URL
     * @return 响应体；失败返回空串（不抛异常）
     */
    public String fetch(String url) {
        return fetchWith(url, requestHeaders());
    }

    /**
     * GET 一个 URL（带附加头）—— 对应上游 {@code merge.p036k.b.l(url, headers)}。
     *
     * @param url     完整 URL
     * @param headers 请求头，可为 null
     * @return 响应体；失败返回空串
     */
    public String fetchWith(String url, Map<String, String> headers) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        try {
            return http.get(url, headers);
        } catch (Throwable e) {
            Log.w(TAG, "fetch failed: " + url, e);
            return "";
        }
    }

    /**
     * POST 一个 JSON body —— 对应上游 {@code merge.p036k.b.f(url, body, headers).a()}。
     *
     * @param url     完整 URL
     * @param body    请求体
     * @param headers 请求头，可为 null
     * @return 响应体；失败返回空串
     */
    public String postWith(String url, String body, Map<String, String> headers) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        try {
            return http.post(url, body == null ? "" : body, headers);
        } catch (Throwable e) {
            Log.w(TAG, "post failed: " + url, e);
            return "";
        }
    }

    /**
     * 读一个文本文件 —— 对应上游 {@code merge.p038m.k.a(File)}。
     *
     * @param path 绝对路径
     * @return 内容；不存在/不可读返回空串
     */
    public String readTextFile(String path) {
        return PanCreds.readFile(path);
    }

    /**
     * 写一个文本文件 —— 对应上游 {@code merge.p038m.k.c(File, String)}。
     *
     * @param path 绝对路径
     * @param text 内容
     * @return 是否成功
     */
    public boolean writeTextFile(String path, String text) {
        return PanCreds.writeFile(path, text);
    }

    // ─────────────────────────────────────────────────────────────
    // 生命周期 / 注入点
    // ─────────────────────────────────────────────────────────────

    /** 注入随机源（测试用）；null 表示用系统随机 */
    public void setRandomForTest(Random r) {
        this.random = r;
    }

    /** 注入"今天"（{@code yyyyMMdd}）覆盖；空串恢复真实时钟 */
    public void setTodayForTest(String yyyyMMdd) {
        this.todayOverride = yyyyMMdd == null ? "" : yyyyMMdd.trim();
    }

    /** 注入当前毫秒（测试用）；负数恢复真实时钟 */
    public void setNowForTest(long millis) {
        this.nowOverride = millis;
    }

    /** 注入缓存目录（测试用） */
    public void setCacheDirForTest(String dir) {
        this.cache = new MaintCache(dir);
    }

    /** 注入 cookie（测试用） */
    public void setCookieForTest(String c) {
        this.cookie = c == null ? "" : c.trim();
    }

    /** 注入临时目录 fid（测试用） */
    public void setTempDirFidForTest(String fid) {
        this.tempDirFid = fid == null ? "" : fid.trim();
    }

    /** 当前 cookie（可能为空 = 未登录） */
    public String cookie() {
        return cookie;
    }

    /** 当前临时目录 fid（空 = 还没解析/创建） */
    public String tempDirFid() {
        return tempDirFid;
    }

    /** 是否已拿到登录态 */
    public boolean loggedIn() {
        return !TextUtils.isEmpty(cookie);
    }

    // ─────────────────────────────────────────────────────────────
    // 凭据读取（checkquarkcookie / checkuccookie）
    // ─────────────────────────────────────────────────────────────

    /**
     * 主凭据文件路径 —— 上游字面量 {@code "/sdcard/TVBox/<name>.txt"}。
     *
     * @param name 文件名
     * @return 绝对路径（根目录可被 {@link PanCreds#setRootForTest} 覆盖）
     */
    public String credentialPathMain(String name) {
        return PanCreds.path(name);
    }

    /**
     * 兜底凭据文件路径 —— 上游字面量 {@code "/sdcard/TV/<name>.txt"}。
     *
     * @param name 文件名
     * @return 绝对路径
     */
    public String credentialPathFallback(String name) {
        java.util.List<String> p = PanCreds.candidatePaths(name);
        return p.size() >= 2 ? p.get(1) : "";
    }

    /**
     * 读登录态 —— 还原 {@code checkquarkcookie()} / {@code checkuccookie()}。
     *
     * <p>上游的三态语义（{@code QuarkPan:37-63} / {@code UCPan:46-72}）：
     * <ol>
     *   <li>主文件存在且能解析成 JSON → 取 {@code cookie} 字段 trim；<b>取到空串也算"读到了"</b>
     *       （{@code strA2} 不再是 null，不会再走兜底）</li>
     *   <li>只有主文件缺失或 JSON 解析抛异常（{@code strA2} 仍为 null）才读兜底裸文本</li>
     *   <li>最终为空 → <b>不改写</b>已有 cookie（即"保持上次的登录态"）</li>
     * </ol>
     *
     * <p>⚠️ 注意这里与 {@code PanCreds.read(name, "cookie")} 的差别：后者在"主文件合法但
     * 字段为空"时<b>也会</b>降级去读兜底文件，比上游宽松。两条路径本仓库都保留，
     * 因为 client 层要「尽量登进去」，而维护层要「和上游逐字一致」。
     *
     * @return 读到的 cookie；未读到时返回当前值（可能为空）
     */
    public String reloadCredential() {
        String name = brandCookieFile();
        String cookieCandidate = null;

        String raw = readTextFile(credentialPathMain(name));
        if (!TextUtils.isEmpty(raw)) {
            JSONObject o = parseObject(raw);
            if (o != null) {
                String v = o.optString(F_COOKIE, "");
                cookieCandidate = v == null ? "" : v.trim();
            }
        }

        if (cookieCandidate == null) {
            String fb = readTextFile(credentialPathFallback(name));
            if (!TextUtils.isEmpty(fb)) {
                cookieCandidate = fb.trim();
            }
        }

        if (TextUtils.isEmpty(cookieCandidate)) {
            Log.i(TAG, brandName() + " 未找到凭据，沿用上次状态（loggedIn=" + loggedIn() + "）");
            return cookie;
        }
        this.cookie = cookieCandidate;
        Log.i(TAG, brandName() + " 已读取凭据，长度=" + cookie.length());
        return cookie;
    }

    // ─────────────────────────────────────────────────────────────
    // 请求头（j()）
    // ─────────────────────────────────────────────────────────────

    /**
     * 维护请求的固定头 —— 还原 {@code j()}。
     *
     * <p>上游：{@code v.b("User-Agent", ua)} 起手，再 put Cookie / Content-Type /
     * Referer / Origin 共 5 项。返回 {@link LinkedHashMap} 以保持插入顺序（便于断言）。
     *
     * @return 新的可变 Map
     */
    public Map<String, String> requestHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put(H_UA, brandUserAgent());
        h.put(H_COOKIE, cookie == null ? "" : cookie);
        h.put(H_CONTENT_TYPE, V_CONTENT_TYPE_JSON);
        h.put(H_REFERER, brandReferer());
        h.put(H_ORIGIN, brandOrigin());
        return h;
    }

    // ─────────────────────────────────────────────────────────────
    // body / URL 构造（纯函数，逐字复刻上游拼接顺序）
    // ─────────────────────────────────────────────────────────────

    /** 换 stoken 的 body：{@code {"pwd_id":"<默认分享>","passcode":""}} */
    public String shareTokenBody() {
        return BODY_TOKEN_PREFIX + defaultShareId() + BODY_TOKEN_SUFFIX;
    }

    /**
     * 删文件 body：{@code {"action_type":2,"filelist":["<fid>"],"exclude_fids":[]}}。
     *
     * @param fid 文件 id
     * @return JSON 文本
     */
    public static String buildDeleteBody(String fid) {
        return BODY_DELETE_PREFIX + (fid == null ? "" : fid) + BODY_DELETE_SUFFIX;
    }

    /**
     * 建目录 body。
     *
     * @param name 目录名
     * @return JSON 文本
     */
    public static String buildMkdirBody(String name) {
        return BODY_MKDIR_PREFIX + (name == null ? "" : name) + BODY_MKDIR_SUFFIX;
    }

    /**
     * 转存 body —— 逐字复刻上游 5 段拼接（{@code QuarkPan:153} / {@code UCPan:150}）。
     *
     * @param fid          分享内文件 id
     * @param fidToken     分享内文件 token（{@code share_fid_token}）
     * @param toPdirFid    转存落点（临时目录 fid）
     * @param pwdId        分享 id
     * @param stoken       分享 stoken（<b>已解码</b>的明文，不是 URL 编码后的）
     * @return JSON 文本
     */
    public static String buildSaveBody(String fid, String fidToken, String toPdirFid,
                                       String pwdId, String stoken) {
        return BODY_SAVE_A + safe(fid)
                + BODY_SAVE_B + safe(fidToken)
                + BODY_SAVE_C + safe(toPdirFid)
                + BODY_SAVE_D + safe(pwdId)
                + BODY_SAVE_E + safe(stoken)
                + BODY_SAVE_F;
    }

    /**
     * 分享详情 URL —— 上游把 stoken <b>URL 编码</b>后放进 query。
     *
     * <p>拼接顺序（夸克 {@code L141} / UC {@code L138}）：
     * {@code 前缀 + pwd_id + "&stoken=" + encode(stoken) + "&pdir_fid=" + pdirFid
     * + "&force=0&_page=1" + 尾串}。
     *
     * @param pwdId   分享 id
     * @param stoken  已 URL 编码的 stoken（见 {@link #urlEncode}）
     * @param pdirFid 目录 fid（{@code "0"} 或 {@code first_fid}）
     * @return 完整 URL
     */
    public String buildShareDetailUrl(String pwdId, String stoken, String pdirFid) {
        return shareDetailBase() + safe(pwdId)
                + "&stoken=" + safe(stoken)
                + "&pdir_fid=" + safe(pdirFid)
                + "&force=0&_page=1"
                + SHARE_DETAIL_TAIL;
    }

    /** URL 编码（上游 {@code URLEncoder.encode}） */
    public static String urlEncode(String s) {
        return Http.encode(s);
    }

    /**
     * URL 解码（上游 {@code URLDecoder.decode(strEncode)} 还原 stoken 明文）。
     *
     * @param s 编码后的串
     * @return 明文；解码失败返回原文
     */
    public static String urlDecode(String s) {
        if (s == null) {
            return "";
        }
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Throwable e) {
            return s;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 主流程 1：每日续期（checktime → l()）
    // ─────────────────────────────────────────────────────────────

    /**
     * 每日续期检查 —— 还原 {@code checktime()}。
     *
     * <p>上游：{@code Integer.parseInt(缓存) >= Integer.parseInt(今天)} 则返回（不做事），
     * 否则跑 {@code l()}。缓存为空也要跑。
     *
     * @return 是否触发了续期
     */
    public boolean checkTime() {
        String cached = readCache(brandTimeKey());
        if (!TextUtils.isEmpty(cached) && !isStale(cached, today())) {
            Log.i(TAG, brandName() + " 今日已续期（" + cached + "），跳过");
            return false;
        }
        Log.i(TAG, brandName() + " 续期开始（缓存=" + cached + "）");
        refreshShare();
        return true;
    }

    /**
     * 转存续期 —— 还原 {@code l()}。
     *
     * <p>六步链路见类注释 §2。任一必需字段缺失即返回 false（<b>不再用
     * {@code contains("{")} 猜</b>，见缺陷 2）。
     *
     * @return 是否转存成功并写入了日期缓存
     */
    public boolean refreshShare() {
        if (TextUtils.isEmpty(cookie)) {
            // 上游由调度方先调 checkXXXcookie()；这里补一次，避免静默发空 Cookie
            reloadCredential();
        }
        if (TextUtils.isEmpty(cookie)) {
            Log.w(TAG, brandName() + " 无 cookie，续期中止");
            return false;
        }
        String pwdId = defaultShareId();

        // ① 换 stoken
        JSONObject tokenResp = parseObject(postWith(shareTokenUrl(), shareTokenBody(), requestHeaders()));
        String stoken = pathString(tokenResp, F_DATA, F_STOKEN);
        if (TextUtils.isEmpty(stoken)) {
            Log.w(TAG, "换 stoken 失败");
            return false;
        }
        String encoded = urlEncode(stoken);

        // ② 列分享根
        JSONObject root = parseObject(fetchWith(buildShareDetailUrl(pwdId, encoded, "0"), requestHeaders()));
        String firstFid = pathString(root, F_DATA, F_SHARE, F_FIRST_FID);
        if (TextUtils.isEmpty(firstFid)) {
            Log.w(TAG, "分享根节点缺失 first_fid");
            return false;
        }

        // ③ 列分享首层
        JSONObject inner = parseObject(fetchWith(buildShareDetailUrl(pwdId, encoded, firstFid), requestHeaders()));
        JSONArray list = pathArray(inner, F_DATA, F_LIST);
        if (list == null || list.length() == 0) {
            Log.w(TAG, "分享里没有文件");
            return false;
        }

        // ④ 临时目录
        String dirFid = resolveTempDirFid();
        if (TextUtils.isEmpty(dirFid)) {
            Log.w(TAG, "拿不到 " + brandTempDirName() + " 目录 fid");
            return false;
        }

        // ⑤ 随机挑一个文件
        JSONObject picked = list.optJSONObject(pickIndex(list.length()));
        if (picked == null) {
            return false;
        }
        String fid = picked.optString(F_FID, "");
        String fidToken = picked.optString(F_SHARE_FID_TOKEN, "");
        if (TextUtils.isEmpty(fid) || TextUtils.isEmpty(fidToken)) {
            Log.w(TAG, "挑中的条目缺 fid/share_fid_token");
            return false;
        }

        // ⑥ 转存（stoken 用解码后的明文，与上游一致）
        String body = buildSaveBody(fid, fidToken, dirFid, pwdId, urlDecode(encoded));
        JSONObject saved = parseObject(postWith(shareSaveUrl(), body, requestHeaders()));
        if (saved == null || !MSG_OK.equals(saved.optString(F_MESSAGE, ""))) {
            Log.w(TAG, "转存未返回 message=ok");
            return false;
        }

        // ⑦ 写续期日期
        writeCache(brandTimeKey(), today());
        Log.i(TAG, brandName() + " 续期完成：" + fid);
        return true;
    }

    /**
     * 解析/创建临时目录 —— 还原 {@code m()}。
     *
     * <p>上游会把结果缓存在 {@code static String y}；本实现缓存在实例字段
     * {@link #tempDirFid}（缺陷 1）。
     *
     * @return 临时目录 fid；失败返回空串
     */
    public String resolveTempDirFid() {
        if (!TextUtils.isEmpty(tempDirFid)) {
            return tempDirFid;
        }
        JSONObject root = parseObject(fetchWith(dirScanSortUrl(), requestHeaders()));
        JSONArray list = pathArray(root, F_DATA, F_LIST);
        String name = brandTempDirName();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                if (name.equals(o.optString(F_FILE_NAME, ""))) {
                    String fid = o.optString(F_FID, "");
                    if (!TextUtils.isEmpty(fid)) {
                        this.tempDirFid = fid;
                        return fid;
                    }
                }
            }
        }
        // 没找到 → 建一个
        JSONObject created = parseObject(postWith(createDirUrl(), buildMkdirBody(name), requestHeaders()));
        String fid = pathString(created, F_DATA, F_FID);
        if (TextUtils.isEmpty(fid)) {
            Log.w(TAG, "创建 " + name + " 失败");
            return "";
        }
        this.tempDirFid = fid;
        Log.i(TAG, brandName() + " 已创建临时目录 " + name + " -> " + fid);
        return fid;
    }

    /**
     * 挑一个下标 —— 上游 {@code new Random().nextInt(len)}。
     *
     * @param len 列表长度
     * @return 合法下标（len&lt;=0 时返回 0）
     */
    public int pickIndex(int len) {
        if (len <= 0) {
            return 0;
        }
        Random r = random != null ? random : new Random();
        return r.nextInt(len);
    }

    // ─────────────────────────────────────────────────────────────
    // 主流程 2：7 天清理（deletetime → deleteXXXTemp）
    // ─────────────────────────────────────────────────────────────

    /**
     * 清理检查 —— 还原 {@code deletetime()}。
     *
     * <p>上游：{@code (now - parse(缓存)) / 86400000 < 7} 则返回，否则跑
     * {@code deleteXXXTemp()}。<b>缓存为空也要跑</b>（首次装机就清一次）。
     *
     * @return 是否触发了清理
     */
    public boolean deleteTime() {
        String cached = readCache(brandDeleteTimeKey());
        if (!TextUtils.isEmpty(cached) && !isOlderThanDays(cached, CLEAN_AFTER_DAYS, nowMillis())) {
            Log.i(TAG, brandName() + " 距上次清理不足 " + CLEAN_AFTER_DAYS + " 天（" + cached + "）");
            return false;
        }
        Log.i(TAG, brandName() + " 清理开始（缓存=" + cached + "）");
        deleteTempFiles();
        return true;
    }

    /**
     * 删掉临时目录里的所有文件 —— 还原 {@code deleteQuarkTemp()} / {@code deleteUCTemp()}。
     *
     * <p>逐字保留上游的三段式（列根 → 找目录 → 列目录 → 逐个删），
     * 但把「删了几个」如实返回（上游丢弃全部响应，缺陷 6）。
     *
     * @return 尝试删除的文件数
     */
    public int deleteTempFiles() {
        if (TextUtils.isEmpty(cookie)) {
            reloadCredential();
        }
        if (TextUtils.isEmpty(cookie)) {
            Log.w(TAG, brandName() + " 无 cookie，清理中止");
            return 0;
        }
        int n = 0;
        try {
            String dirFid = findDirFid(listRoot());
            if (TextUtils.isEmpty(dirFid)) {
                Log.i(TAG, "没有 " + brandTempDirName() + " 目录，无需清理");
                return 0;
            }
            JSONArray files = pathArray(parseObject(fetchWith(subdirSortUrl(dirFid), requestHeaders())),
                    F_DATA, F_LIST);
            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject o = files.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    String fid = o.optString(F_FID, "");
                    if (TextUtils.isEmpty(fid)) {
                        continue;
                    }
                    String resp = postWith(deleteUrl(), buildDeleteBody(fid), requestHeaders());
                    Log.d(TAG, "delete " + fid + " -> " + head(resp, 120));
                    n++;
                }
            }
            writeCache(brandDeleteTimeKey(), today());
            Log.i(TAG, brandName() + " 清理完成，条数=" + n);
        } catch (Throwable e) {
            Log.e(TAG, brandName() + " 清理异常", e);
        }
        return n;
    }

    /**
     * 列根目录的原始 JSON —— 抽出来便于测试覆盖"空/畸形响应"。
     *
     * @return {@code data.list}；失败返回 null
     */
    public JSONArray listRoot() {
        return pathArray(parseObject(fetchWith(rootSortUrl(), requestHeaders())), F_DATA, F_LIST);
    }

    /**
     * 在根目录列表里找名为 {@code dirName} 的条目 —— 上游 {@code for} 循环。
     *
     * @param list 根目录列表
     * @return fid；找不到返回空串
     */
    public String findDirFid(JSONArray list) {
        if (list == null) {
            return "";
        }
        String name = brandTempDirName();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (name.equals(o.optString(F_FILE_NAME, ""))) {
                return o.optString(F_FID, "");
            }
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────
    // 缓存文件（getCache / k.a / k.c）
    // ─────────────────────────────────────────────────────────────

    /**
     * 缓存文件句柄 —— 还原 {@code getCache(String)} = {@code merge.p038m.k.d(name)}。
     *
     * @param name 缓存名（如 {@code qktime}）
     * @return 文件；name 为空时返回 null
     */
    public File cacheFile(String name) {
        return cache.file(name);
    }

    /**
     * 读缓存 —— 还原 {@code k.a(getCache(name))}。
     *
     * @param name 缓存名
     * @return 内容（已 trim）；不存在返回空串
     */
    public String readCache(String name) {
        return cache.read(name);
    }

    /**
     * 写缓存 —— 还原 {@code k.c(getCache(name), text)}。
     *
     * @param name 缓存名
     * @param text 内容
     * @return 是否成功
     */
    public boolean writeCache(String name, String text) {
        return cache.write(name, text);
    }

    // ─────────────────────────────────────────────────────────────
    // 纯函数：日期
    // ─────────────────────────────────────────────────────────────

    /** 当前毫秒（可注入） */
    public long nowMillis() {
        return nowOverride >= 0 ? nowOverride : System.currentTimeMillis();
    }

    /**
     * 今天 —— {@code yyyyMMdd}。
     *
     * <p>上游用 {@code new SimpleDateFormat("yyyyMMdd")}（默认 Locale）。
     * 本实现固定 {@link Locale#US}，见缺陷 3。
     *
     * @return 形如 {@code 20251017}
     */
    public String today() {
        if (!TextUtils.isEmpty(todayOverride)) {
            return todayOverride;
        }
        return yyyyMMdd(nowMillis());
    }

    /**
     * 格式化 {@code yyyyMMdd}。
     *
     * @param millis 毫秒
     * @return 形如 {@code 20251017}
     */
    public static String yyyyMMdd(long millis) {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(millis));
    }

    /**
     * 把 {@code yyyyMMdd} 解析成可比较的整数。
     *
     * <p>上游直接 {@code Integer.parseInt(缓存)}：缓存脏了会抛异常（缺陷 4）。
     * 本实现只取前 8 位数字，解析不出返回 0（⇒ 视为过期，只会更积极维护）。
     *
     * @param s 缓存文本
     * @return 整数日；非法 0
     */
    public static long parseYyyyMMdd(String s) {
        String t = digits(s);
        if (t.length() < 8) {
            return 0L;
        }
        try {
            return Long.parseLong(t.substring(0, 8));
        } catch (Throwable e) {
            return 0L;
        }
    }

    /**
     * 缓存日期是否"早于今天"。
     *
     * @param cached 缓存文本
     * @param today  {@code yyyyMMdd}
     * @return true = 需要续期
     */
    public static boolean isStale(String cached, String today) {
        return parseYyyyMMdd(cached) < parseYyyyMMdd(today);
    }

    /**
     * 缓存日期距今是否 ≥ days 天 —— 还原 {@code deletetime()} 的除法比较。
     *
     * @param cached 缓存文本
     * @param days   天数阈值
     * @param now    当前毫秒
     * @return true = 需要清理
     */
    public static boolean isOlderThanDays(String cached, int days, long now) {
        long ts = parseYyyyMMddMillis(cached);
        if (ts <= 0L) {
            return true;
        }
        long elapsed = (now - ts) / DAY_MS;
        return elapsed >= days;
    }

    /**
     * 把 {@code yyyyMMdd} 解析成毫秒 —— 上游
     * {@code new Date(y-1900, m-1, d).getTime()}。
     *
     * @param s 文本
     * @return 毫秒；非法 0
     */
    @SuppressWarnings("deprecation")
    public static long parseYyyyMMddMillis(String s) {
        String t = digits(s);
        if (t.length() < 8) {
            return 0L;
        }
        try {
            int y = Integer.parseInt(t.substring(0, 4)) - 1900;
            int m = Integer.parseInt(t.substring(4, 6)) - 1;
            int d = Integer.parseInt(t.substring(6, 8));
            return new Date(y, m, d).getTime();
        } catch (Throwable e) {
            return 0L;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 纯函数：JSON 容错访问
    // ─────────────────────────────────────────────────────────────

    /**
     * 把响应文本解析成 JSONObject。
     *
     * <p>代替上游「{@code contains("{")} 就算 JSON」的判断（缺陷 2）：
     * 必须以 {@code &#123;} 开头且整体可解析。
     *
     * @param text 响应文本
     * @return JSONObject；空/非对象/畸形 → null（<b>不抛异常</b>）
     */
    public static JSONObject parseObject(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.length() < 2 || t.charAt(0) != '{') {
            return null;
        }
        try {
            return new JSONObject(t);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 按路径取字符串：{@code root.a.b}。
     *
     * @param root 根对象（可 null）
     * @param path 逐级字段名
     * @return 字段值；任一级缺失返回空串
     */
    public static String pathString(JSONObject root, String... path) {
        JSONObject cur = root;
        for (int i = 0; i < path.length - 1; i++) {
            if (cur == null) {
                return "";
            }
            cur = cur.optJSONObject(path[i]);
        }
        if (cur == null || path.length == 0) {
            return "";
        }
        String v = cur.optString(path[path.length - 1], "");
        return v == null ? "" : v;
    }

    /**
     * 按路径取数组：{@code root.a.b} 且末级是数组。
     *
     * @param root 根对象（可 null）
     * @param path 逐级字段名
     * @return 数组；任一级缺失或不是数组返回 null
     */
    public static JSONArray pathArray(JSONObject root, String... path) {
        JSONObject cur = root;
        for (int i = 0; i < path.length - 1; i++) {
            if (cur == null) {
                return null;
            }
            cur = cur.optJSONObject(path[i]);
        }
        if (cur == null || path.length == 0) {
            return null;
        }
        return cur.optJSONArray(path[path.length - 1]);
    }

    // ─────────────────────────────────────────────────────────────
    // 内部小工具
    // ─────────────────────────────────────────────────────────────

    /** 只保留数字 */
    private static String digits(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String head(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
