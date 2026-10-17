package com.example.tvbox.plain.pan;

import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.plain.pan.client.PanCreds;
import com.example.tvbox.plain.pan.client.PanXunleiPlain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 迅雷云盘「维护例程」—— 逐字还原 {@code com.github.catvod.spider.XunleiPan}（186 行）。
 *
 * <h2>与另两个维护类的关键不同：<b>它没有混淆字符串池</b></h2>
 * 任务表里写的是「同类结构（超大 {@code short[]} + {@code d.c(...)} 解码）」，但实测
 * {@code XunleiPan.java} <b>整份没有一个 {@code short[]} 字段</b>，也没有一次
 * {@code a.e/b.e/c.e/d.c/e.c} 调用 —— 它的 URL、UA、盐值全是 DEX 明文常量
 * （{@code ae/decompiled/_index.json} 的 {@code fields} 只列出
 * {@code l/k/j/h/i/b}，无 {@code short[]}）。
 * 因此本类<b>不需要</b> {@code PanObfStrings}，但它另有更硬的东西：
 * <b>10 轮 MD5 风控签名链</b>（见 {@link #captchaSign}）。这一节才是本类真正的难点。
 *
 * <h2>职责（与 {@code PanXunlei} / {@code PanXunleiPlain} 的分工）</h2>
 * <table border="1">
 *   <tr><th></th><th>{@code XunleiPan}（本类）</th><th>{@code PanXunlei}（{@code PanXunleiPlain}）</th></tr>
 *   <tr><td>基类</td><td>{@code extends Spider}（{@code XunleiPan.java:30}）</td>
 *       <td>{@code extends Pan}（{@code PanXunlei.java}）</td></tr>
 *   <tr><td>方法</td><td>{@code checktime/getCache/m/o/q/r/u} —— <b>全是 static</b></td>
 *       <td>{@code homeContent/categoryContent/detailContent/action/init/b}</td></tr>
 *   <tr><td>做什么</td><td>每 7 天把一条分享转存到自己的「我的迅雷原画」，<b>再立刻批量删除</b>，
 *       只为把 {@code trace_file_ids} 续上</td>
 *       <td>给宿主列目录、出播放条目</td></tr>
 *   <tr><td>输出</td><td>无（写 {@code xltime} 缓存）</td><td>TVBox 标准 JSON</td></tr>
 * </table>
 * <p><b>判定：与 {@code PanXunlei} 无版本关系、无方法交集，是「后台 cron」对「前台页面」，
 * 两者都要保留。</b>依据：{@code XunleiPan} 的 9 个方法（{@code _index.json}）里
 * 没有一个是内容方法；反之 {@code PanXunlei} 没有任何 {@code checktime/trace} 逻辑。
 * 判定方法与 {@link UCQuarkMaintBase} 类注释 §1 同一套。
 *
 * <h2>协议链路</h2>
 * <pre>
 *   checktime()   cache "xltime" 非今天 → m()
 *   m()           ① q("get:/drive/v1/share", userId) → captcha_token
 *                 ② GET  /drive/v1/share            → share_status=="OK" + files[0].id
 *                 ③ GET  /drive/v1/share/detail     → files[]
 *                 ④ 随机挑一个 file.id
 *                 ⑤ q("get:/drive/v1/about", userId) → 第二个 captcha_token
 *                 ⑥ POST /drive/v1/share/restore    → params.trace_file_ids（存字段 l）
 *                 ⑦ 写 xltime=今天；Thread.sleep(1000)；u()
 *   u()           ① q("get:/drive/v1/files", userId) → captcha_token
 *                 ② trace_file_ids 的<b>第一个 key</b> 当 id
 *                 ③ POST /drive/v1/files:batchDelete {"ids":["&lt;id&gt;"],"space":""} → task_id
 *                 ④ GET  /drive/v1/tasks/&lt;task_id&gt;（响应丢弃，只为触发）
 *   q(action,uid) seed = clientId+version+pkg+deviceId+timestamp
 *                 for salt in b[0..9]: seed = md5_hex_lower(seed + salt)
 *                 captcha_sign = "1." + seed
 *                 POST https://xluser-ssl.xunlei.com/v1/shield/captcha/init → captcha_token
 * </pre>
 *
 * <h2>{@code static} 可变状态（缺陷，已修）</h2>
 * <p>上游 {@code private static String l} 存 {@code trace_file_ids}，{@code m()} 写、
 * {@code u()} 读。同进程两个账号会互相覆盖，导致 A 的 trace 被 B 删掉。
 * 本实现改为实例字段 {@link #traceFileIds}。
 * <p>其余 {@code static}（{@code h/j/k/i} 与数组 {@code b}）都是<b>只读常量</b>，
 * 上游从未重新赋值，故保留 {@code static final}（不做无意义改动）。
 *
 * <h2>上游缺陷清单（本实现逐条修正并标注）</h2>
 * <ol>
 *   <li><b>分享 id / 提取码硬编码在 URL 字面量里</b>（{@code m():63,70,77}）：
 *       {@code VOwTF0m3ItvrjtoIuRcXN8jqA1} / {@code ptpz}。分享被撤即整条链路静默失效。
 *       → 抽成字段 {@link #shareId}/{@link #passCode}（默认值逐字相同）。</li>
 *   <li><b>用 {@code contains("{")||contains("}")} 判断 JSON</b>（{@code m():71,78}）。
 *       → 直接解析并校验 {@code share_status}（{@link #parseObject}）。</li>
 *   <li><b>{@code catch (JSONException e) {}} 空吞</b>（{@code u():183-184}）：删除失败完全无痕迹。
 *       → 记录日志并返回 false。</li>
 *   <li><b>{@code u()} 只看 {@code keys().next()} 一个 id</b>：{@code trace_file_ids} 里
 *       通常有多个，只删第一个 ⇒ 垃圾文件持续累积。行为保留（改了会动到协议语义），
 *       但把「本可删几个 / 实删几个」记进日志。</li>
 *   <li><b>{@code Thread.sleep(1000)} 卡住调用线程</b>：若在 UI 线程调用会 ANR。
 *       → 保留默认 1s（协议上等任务建立），但可通过 {@link #setSleepEnabledForTest} 关闭。</li>
 *   <li><b>{@code checktime()} 里正则与 {@code parseInt} 混用</b>：先去非数字再去判断
 *       {@code matches("\\d+")}，第二段判断恒真（无用代码）。行为等价，保留结构并注明。</li>
 *   <li><b>{@code new SimpleDateFormat("yyyyMMdd")} 用默认 Locale</b>：泰语区出佛历年份，
 *       节流永远失效 → 每次都跑。固定 {@link Locale#US}。</li>
 * </ol>
 *
 * <h2>未能还原的分支</h2>
 * <ul>
 *   <li>{@code merge.p027b.X}（{@code X.m().k()/l()}）未反编译。其 {@code k()} 是设备 id、
 *       {@code l()} 是 {@code Authorization} 值；本实现按 {@code xunlei.txt} 的
 *       {@code deviceid} + {@code token_resp.access_token} 读取，与
 *       {@code PanXunleiPlain.reloadCredential()} 同一套假设（那里已在用）。
 *       若上游实际还有别的来源（如 register 流程），本类不会触发。</li>
 *   <li>{@code merge.A.b.a(x,y)} 被推定为字符串拼接（{@code PanXunleiPlain} 的注释里
 *       有同一结论与固定向量佐证）；{@code merge.A.a.e(json,field)} 推定为取字段。</li>
 *   <li>{@code merge.p036k.b.l(url, headers)} 的「响应即字符串」语义：本实现用
 *       {@link Http#get(String, Map)}。</li>
 * </ul>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>宿主侧调度器不在 jar 内 —— 本类永远不会自己跑起来。</b>
 *       全库 {@code grep} 过：没有任何类调用 {@code XunleiPan.checktime()}
 *       （{@code XunleiPan.java} 内也没有自调用）。它由 TVBox 宿主 + 配置中心在外部按计划触发。
 *       ⇒ 本次重写不提供触发逻辑，是设计事实而非遗漏；要定时跑请自行挂 {@link #checkTime()}。
 *       这一条对三个维护类都成立，写在这里以免后来者以为漏了触发逻辑。</li>
 *   <li><b>{@code merge.p027b.X}（{@code X.m().k()/l()}）未反编译</b> —— 设备 id 与
 *       {@code Authorization} 的来源。本实现按 {@code xunlei.txt} 的 {@code deviceid}
 *       + {@code token_resp.access_token} 读取（与既有
 *       {@code PanXunleiPlain.reloadCredential()} 同一套假设，那里已在用）。
 *       <b>若上游还有别的来源（如 register 流程），本类不会触发</b>，需要补
 *       {@link #reloadCredential()}。</li>
 *   <li>{@code merge.A.b.a(x,y)} 推定为纯字符串拼接（非加密）；{@code merge.A.a.e(json,field)}
 *       推定为取字段。二者未反编译，但 {@code PanXunleiPlain} 侧已有固定向量佐证拼接语义。</li>
 *   <li>{@code merge.p036k.b.l(url, headers)} 的"响应即字符串"语义按
 *       {@code Http.get(url, headers)} 处理；{@code u()} 里 {@code tasks/<id>} 的响应上游整份丢弃，
 *       本实现同样丢弃（只记日志），因此"任务是否真的完成"不可知 ——
 *       <b>上游本来就是"发出去就算数"，这里不假装能确认</b>。</li>
 *   <li>{@code merge.p027b.u.a(a,b,c)} 与 {@code merge.p027b.h.c(k,v,...)} 未反编译；
 *       三串拼接 / 变参 Map 由调用点语义反推。</li>
 *   <li>{@code merge.p027b.h.b(a,b,c)} 的第三个参数在 {@code q()} 里是 {@code String.valueOf(ts)}，
 *       本实现按 {@code Long.toString} 处理（两者对正数输出一致，无副作用）。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>{@code private static String l}（trace_file_ids）跨实例污染</b>
 *       （{@code XunleiPan.java:31}）：{@code m()} 写、{@code u()} 读，两个账号同进程时
 *       A 会把 B 的痕迹文件删掉。
 *       → 已改为实例字段 {@link #traceFileIds}。
 *       断言：{@code ★ 缺陷修正：static 可变字段改实例}（反射断言 + 双实例隔离）。</li>
 *   <li><b>{@code catch (JSONException e) {}} 空吞</b>（{@code XunleiPan.java:183-184}）：
 *       批删失败完全无痕迹，出问题只能看服务器。
 *       → 记日志并返回 false（拆成 {@link #deleteTraceFiles()} 的返回值）。
 *       断言：{@code ★ 批删空响应 -> false（不再静默）}。</li>
 *   <li><b>{@code u()} 只删 {@code keys().next()} 第一个 id</b>（{@code XunleiPan.java:178}）：
 *       {@code trace_file_ids} 通常有多个，垃圾文件持续累积。
 *       → 协议语义不动（改多删可能触发风控），但把"本可删几个 / 实删几个"记进日志。
 *       断言：{@code ★ 只删第一个 key}、{@code ★ 多 key 能成功}。</li>
 *   <li><b>{@code Thread.sleep(1000)} 卡调用线程</b>（{@code XunleiPan.java:82}）：
 *       在 UI 线程调用会 ANR。
 *       → 保留默认 1s（协议上要等任务建立），但可通过 {@link #setSleepEnabledForTest} 关闭。
 *       断言：{@code ★ sleepEnabled=false 时不阻塞}。</li>
 *   <li><b>{@code checktime()} 里正则判断恒真</b>（{@code XunleiPan.java:41-45}）：
 *       先 {@code replaceAll("[^0-9]","")} 去掉非数字、再 {@code matches("\\d+")}，
 *       第二段永远为真（除非空串，而空串已被前一个条件拦下）。
 *       → 保留结构以对齐上游，注释标明这是死代码（行为等价，不做无谓改动）。</li>
 *   <li><b>{@code share_id}/{@code pass_code} 硬编码在 URL 字面量里</b>
 *       （{@code XunleiPan.java:63,70,77} 的 {@code VOwTF0m3ItvrjtoIuRcXN8jqA1} / {@code ptpz}）：
 *       分享被撤即整条链路静默失效。
 *       → 抽成 {@link #shareId} / {@link #passCode}（默认值逐字相同，可注入）。
 *       断言：{@code ★ 注入后分享 id 生效}。</li>
 *   <li><b>{@code new SimpleDateFormat("yyyyMMdd")} 不指定 Locale</b>（{@code XunleiPan.java:46,80}）：
 *       泰语区输出佛历年份 ⇒ 节流永不生效 ⇒ 每次启动都转存+删除。
 *       → 固定 {@link Locale#US}。断言：{@code ★ checktime 三条路径}。</li>
 *   <li><b>{@code checktime()} 里 {@code strA} 未经长度校验就 {@code parseInt}</b>
 *       （{@code XunleiPan.java:46}）：{@code "99999999999999999999"} 会抛
 *       {@code NumberFormatException}。→ 用 {@code Long} 并容错。</li>
 *   <li><b>分享参数与业务参数混在一条 URL 字面量里</b>（{@code XunleiPan.java:63}）：
 *       改一个提取码要动整条长串，极易改错。→ 拆成
 *       {@link #shareListUrl()} / {@link #shareDetailUrl(String, String)} 两个构造器。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/XunleiPan.java}；
 * 字段/方法清单: {@code ae/decompiled/_index.json}（{@code fields: l,k,j,h,i,b}，无 {@code short[]}）；
 * <b>明文常量清单（含"本类无混淆表"的机械证明）: {@code maintain/evidence/XunleiPan_strings.md}</b>，
 * 由 {@code ae/tools/pan_maint_strings.py} 从反编译源现算生成；
 * 签名链独立复算向量（Python {@code hashlib}）: {@code ae/tests/XunleiPanMaintTest.java} 的
 * {@code SIGN} / {@code SIGN_2SALT} 常量。
 */
public class XunleiPanPlain extends BaseSpider {

    private static final String TAG = "XunleiPanPlain";

    // ─────────────────────────────────────────────────────────────
    // 只读常量（上游 static 字段，从未重新赋值）
    // ─────────────────────────────────────────────────────────────

    /** x-client-id（上游 {@code h}，{@code XunleiPan.java:34}） */
    public static final String CLIENT_ID = "Xp6vsxz_7IYVw2BB";
    /** 客户端包名（上游 {@code k}，{@code :32}） */
    public static final String PKG = "com.xunlei.downloadprovider";
    /** 客户端版本（上游 {@code j}，{@code :33}） */
    public static final String VERSION = "8.31.0.9726";
    /** 匿名 user_id（上游 {@code i}，{@code :35}） */
    public static final String USER_ID = "0";

    /** 10 轮 MD5 盐值（上游 {@code static String[] b}，{@code :36}，逐字） */
    public static final String[] SIGN_SALTS = PanXunleiPlain.SIGN_SALTS;

    /** 业务 UA（{@code m():125,162} 两处同串，逐字） */
    public static final String UA = PanXunleiPlain.UA;
    /** 风控接口 UA（{@code q():158}，逐字） */
    public static final String CAPTCHA_UA = PanXunleiPlain.CAPTCHA_UA;
    /** Referer（{@code :125,162}） */
    public static final String REFERER = "https://pan.xunlei.com/";

    /** 端点 */
    public static final String API_CAPTCHA_INIT = "https://xluser-ssl.xunlei.com/v1/shield/captcha/init";
    public static final String API_SHARE = "https://api-pan.xunlei.com/drive/v1/share";
    public static final String API_SHARE_DETAIL = "https://api-pan.xunlei.com/drive/v1/share/detail";
    public static final String API_SHARE_RESTORE = "https://api-pan.xunlei.com/drive/v1/share/restore";
    public static final String API_BATCH_DELETE = "https://api-pan.xunlei.com/drive/v1/files:batchDelete";
    public static final String API_TASKS = "https://api-pan.xunlei.com/drive/v1/tasks/";

    /** 续期日期缓存键（{@code checktime():39} 与 {@code m():80}） */
    public static final String TIME_KEY = "xltime";

    /** 默认分享 id（{@code m():63} 字面量内嵌） */
    public static final String DEFAULT_SHARE_ID = "VOwTF0m3ItvrjtoIuRcXN8jqA1";
    /** 默认提取码（{@code m():63}） */
    public static final String DEFAULT_PASS_CODE = "ptpz";

    /** 风控 action：查分享（{@code m():62}） */
    public static final String ACTION_SHARE = "get:/drive/v1/share";
    /** 风控 action：转存前（{@code m():77}） */
    public static final String ACTION_ABOUT = "get:/drive/v1/about";
    /** 风控 action：删文件（{@code u():171}） */
    public static final String ACTION_FILES = "get:/drive/v1/files";

    /** 登录态文件（{@code merge.p027b.X}） */
    public static final String CRED_FILE = PanXunleiPlain.CRED_FILE;
    /** 兼容用的第二个文件名 */
    public static final String CRED_FILE_ALT = PanXunleiPlain.CRED_FILE_ALT;

    /** 风控 init 请求体模板（{@code q():158}，逐字） */
    public static final String BODY_CAPTCHA =
            "{\"client_id\":\"%s\",\"action\":\"%s\",\"device_id\":\"%s\","
          + "\"meta\":{\"username\":\"\",\"phone_number\":\"\",\"email\":\"\","
          + "\"package_name\":\"%s\",\"client_version\":\"%s\",\"captcha_sign\":\"%s\","
          + "\"timestamp\":\"%s\",\"user_id\":\"%s\"}}";

    /** 转存请求体模板（{@code m():77}，逐字） */
    public static final String BODY_RESTORE =
            "{\"parent_id\":\"\",\"share_id\":\"%s\",\"pass_code_token\":\"%s\","
          + "\"ancestor_ids\":[],\"file_ids\":[\"%s\"],\"specify_parent_id\":true,"
          + "\"params\":{\"origin\":\"runegg\"}}";

    /** 批量删除请求体（{@code u():178}，{@code u.a("{\"ids\":[\"", key, "\"],\"space\":\"\"}")） */
    public static final String BODY_BATCH_PREFIX = "{\"ids\":[\"";
    public static final String BODY_BATCH_SUFFIX = "\"],\"space\":\"\"}";

    /** 分享状态成功值 */
    public static final String STATUS_OK = "OK";
    /** 分享列表尾部 query（{@code m():63}） */
    public static final String SHARE_QUERY_TAIL =
            "&limit=9999&pass_code_token=&page_token=&thumbnail_size=SIZE_SMALL";

    /** 一天的毫秒数（备用，与另两个维护类一致） */
    public static final long DAY_MS = 86400000L;

    // ─────────────────────────────────────────────────────────────
    // 实例状态（★ 上游 static，见类注释「static 可变状态」）
    // ─────────────────────────────────────────────────────────────

    /** 转存留下的 trace_file_ids（上游 {@code private static String l}） */
    private String traceFileIds = "";

    /** 登录态 */
    private String deviceId = "";
    private String authorization = "";

    /** 分享参数（上游硬编码在 URL 里；改为可注入） */
    private String shareId = DEFAULT_SHARE_ID;
    private String passCode = DEFAULT_PASS_CODE;

    /** 缓存 */
    private MaintCache cache = new MaintCache("");

    /** 随机源（测试用） */
    private Random random;

    /** 当前时间覆盖（测试用；&lt;0 = 系统时钟） */
    private long nowOverride = -1L;

    /** 今日覆盖（测试用） */
    private String todayOverride = "";

    /** 是否执行协议要求的 1s 等待（测试可关） */
    private boolean sleepEnabled = true;

    // ─────────────────────────────────────────────────────────────
    // 注入点（测试用）
    // ─────────────────────────────────────────────────────────────

    /** 注入登录态 */
    public void setCredentialForTest(String dev, String auth) {
        this.deviceId = dev == null ? "" : dev.trim();
        this.authorization = auth == null ? "" : auth.trim();
    }

    /** 注入分享参数 */
    public void setShareForTest(String shareId, String passCode) {
        this.shareId = TextUtils.isEmpty(shareId) ? DEFAULT_SHARE_ID : shareId;
        this.passCode = passCode == null ? "" : passCode;
    }

    /** 注入随机源 */
    public void setRandomForTest(Random r) {
        this.random = r;
    }

    /** 注入缓存目录 */
    public void setCacheDirForTest(String dir) {
        this.cache = new MaintCache(dir);
    }

    /** 注入当前毫秒 */
    public void setNowForTest(long millis) {
        this.nowOverride = millis;
    }

    /** 注入"今天" */
    public void setTodayForTest(String yyyyMMdd) {
        this.todayOverride = yyyyMMdd == null ? "" : yyyyMMdd.trim();
    }

    /** 关闭协议里的 1 秒等待（测试用） */
    public void setSleepEnabledForTest(boolean enabled) {
        this.sleepEnabled = enabled;
    }

    /** 注入 trace_file_ids */
    public void setTraceFileIdsForTest(String json) {
        this.traceFileIds = json == null ? "" : json;
    }

    public String deviceId() { return deviceId; }
    public String authorization() { return authorization; }
    public String traceFileIds() { return traceFileIds; }
    public String shareId() { return shareId; }
    public String passCode() { return passCode; }
    public boolean loggedIn() { return !TextUtils.isEmpty(deviceId) && !TextUtils.isEmpty(authorization); }

    // ─────────────────────────────────────────────────────────────
    // 凭据（merge.p027b.X.k()/l()）
    // ─────────────────────────────────────────────────────────────

    /**
     * 读 {@code xunlei.txt} 拿设备 id 与 Authorization。
     *
     * @return 是否读到完整登录态
     */
    public boolean reloadCredential() {
        String text = readTextFile(PanCreds.path(CRED_FILE));
        if (TextUtils.isEmpty(text)) {
            text = readTextFile(PanCreds.path(CRED_FILE_ALT));
        }
        String dev = "";
        String auth = "";
        JSONObject o = parseObject(text);
        if (o != null) {
            dev = o.optString("deviceid", "");
            String tokenResp = o.optString("token_resp", "");
            JSONObject tr = parseObject(tokenResp);
            if (tr != null) {
                String at = tr.optString("access_token", "");
                if (!TextUtils.isEmpty(at)) {
                    auth = "Bearer " + at;
                }
            }
        }
        if (TextUtils.isEmpty(dev) && TextUtils.isEmpty(auth)) {
            Log.i(TAG, "未找到迅雷凭据，沿用上次状态");
            return loggedIn();
        }
        this.deviceId = dev;
        this.authorization = auth;
        return loggedIn();
    }

    // ─────────────────────────────────────────────────────────────
    // 可覆写的 IO 层（必须 public —— 测试在默认包）
    // ─────────────────────────────────────────────────────────────

    /**
     * GET —— 对应 {@code merge.p036k.b.l(url, headers)}。
     *
     * @param url     完整 URL
     * @param headers 请求头
     * @return 响应体；失败空串
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
     * POST —— 对应 {@code merge.p036k.b.f(url, body, headers).a()}。
     *
     * @param url     完整 URL
     * @param body    请求体
     * @param headers 请求头
     * @return 响应体；失败空串
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

    /** 读文本文件（{@code merge.p038m.k.a}） */
    public String readTextFile(String path) {
        return PanCreds.readFile(path);
    }

    /**
     * 协议要求的等待（上游 {@code Thread.sleep(1000)}）。
     *
     * @param ms 毫秒
     */
    public void sleep(long ms) {
        if (!sleepEnabled || ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 缓存与日期
    // ─────────────────────────────────────────────────────────────

    /** 缓存文件（{@code getCache(String)}） */
    public File cacheFile(String name) {
        return cache.file(name);
    }

    /** 读缓存（{@code k.a(getCache(name))}） */
    public String readCache(String name) {
        return cache.read(name);
    }

    /** 写缓存（{@code k.c(getCache(name), text)}） */
    public boolean writeCache(String name, String text) {
        return cache.write(name, text);
    }

    /** 当前毫秒（可注入） */
    public long nowMillis() {
        return nowOverride >= 0 ? nowOverride : System.currentTimeMillis();
    }

    /** 今天 {@code yyyyMMdd}（固定 {@link Locale#US}，见缺陷 7） */
    public String today() {
        if (!TextUtils.isEmpty(todayOverride)) {
            return todayOverride;
        }
        return new java.text.SimpleDateFormat("yyyyMMdd", Locale.US)
                .format(new java.util.Date(nowMillis()));
    }

    // ─────────────────────────────────────────────────────────────
    // 签名链（纯函数，可逐位复算）
    // ─────────────────────────────────────────────────────────────

    /**
     * 签名种子 —— 还原 {@code q()} 里的
     * {@code h.b(clientId+version+pkg, X.m().k(), timestamp)}（三串拼接）。
     *
     * @param deviceId 设备 id
     * @param tsMillis 毫秒时间戳
     * @return {@code CLIENT_ID + VERSION + PKG + deviceId + ts}
     */
    public static String signSeed(String deviceId, long tsMillis) {
        return CLIENT_ID + VERSION + PKG + (deviceId == null ? "" : deviceId) + tsMillis;
    }

    /**
     * 10 轮 MD5 链 —— 还原 {@code q()} 的 {@code for (String str3 : b)} 循环。
     *
     * <p>{@code merge.A.b.a(strB, str3)} 是纯字符串拼接（非加密），
     * 每轮取 {@code md5_hex_lower}，共 10 轮，最后加前缀 {@code "1."}。
     *
     * @param seed 种子（{@link #signSeed}）
     * @return {@code "1." + 10 轮 MD5}；seed 为 null 时按空串参与计算（上游不会传 null）
     */
    public static String captchaSign(String seed) {
        // 复用 client 层的同一实现 —— 两处必须逐位一致，测试里交叉断言
        return PanXunleiPlain.captchaSign(seed);
    }

    /**
     * 同上，盐值可注入（写固定向量用）。
     *
     * @param seed  种子
     * @param salts 盐值序列
     * @return {@code "1." + md5 链}
     */
    public static String captchaSign(String seed, String[] salts) {
        return PanXunleiPlain.captchaSign(seed, salts);
    }

    // ─────────────────────────────────────────────────────────────
    // 请求头（o() / r()）
    // ─────────────────────────────────────────────────────────────

    /**
     * 业务请求头 —— 还原 {@code o(String captchaToken)}（<b>不带</b> authorization）。
     *
     * @param captchaToken 风控票
     * @return 新的可变 Map
     */
    public Map<String, String> headers(String captchaToken) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", REFERER);
        h.put("x-client-id", CLIENT_ID);
        h.put("x-device-id", deviceId);
        h.put("x-captcha-token", captchaToken == null ? "" : captchaToken);
        return h;
    }

    /**
     * 已授权请求头 —— 还原 {@code r(String captchaToken)}：在 {@link #headers} 之上
     * 追加 {@code authorization}。
     *
     * @param captchaToken 风控票
     * @return 新的可变 Map
     */
    public Map<String, String> authHeaders(String captchaToken) {
        Map<String, String> h = headers(captchaToken);
        h.put("authorization", authorization);
        return h;
    }

    // ─────────────────────────────────────────────────────────────
    // URL 构造
    // ─────────────────────────────────────────────────────────────

    /** 查分享 URL（{@code m():63}，参数顺序逐字） */
    public String shareListUrl() {
        return API_SHARE + "?share_id=" + shareId + "&pass_code=" + passCode + SHARE_QUERY_TAIL;
    }

    /**
     * 列分享内容 URL（{@code m():70}）。
     *
     * @param parentId       父目录 id（首次为 {@code ""}）
     * @param passCodeToken  已 URL 编码的 {@code pass_code_token}
     * @return 完整 URL
     */
    public String shareDetailUrl(String parentId, String passCodeToken) {
        return API_SHARE_DETAIL + "?share_id=" + shareId
                + "&parent_id=" + (parentId == null ? "" : parentId)
                + "&pass_code_token=" + (passCodeToken == null ? "" : passCodeToken)
                + SHARE_QUERY_TAIL;
    }

    /**
     * 批量删除 body（{@code u():178}）。
     *
     * @param fileId 文件 id
     * @return JSON 文本
     */
    public static String buildBatchDeleteBody(String fileId) {
        return BODY_BATCH_PREFIX + (fileId == null ? "" : fileId) + BODY_BATCH_SUFFIX;
    }

    /** 风控 init body（{@code q():158} 的 {@code String.format}） */
    public String buildCaptchaBody(String action, String sign, long ts, String userId) {
        return String.format(Locale.US, BODY_CAPTCHA,
                CLIENT_ID, action, deviceId, PKG, VERSION, sign, String.valueOf(ts), userId);
    }

    /** 转存 body（{@code m():77} 的 {@code String.format}） */
    public String buildRestoreBody(String passCodeTokenEncoded, String fileId) {
        return String.format(Locale.US, BODY_RESTORE,
                shareId, urlDecode(passCodeTokenEncoded), fileId);
    }

    /** URL 编码 */
    public static String urlEncode(String s) {
        return Http.encode(s);
    }

    /**
     * URL 解码。
     *
     * @param s 编码串
     * @return 明文；失败返回原文
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
    // 主流程
    // ─────────────────────────────────────────────────────────────

    /**
     * 每日/每 7 天检查 —— 还原 {@code checktime()}。
     *
     * <p>上游逻辑（保留结构，缺陷 6 已注明）：
     * <pre>
     *   if (缓存非空) {
     *       数字 = 缓存去非数字;
     *       if (数字空 || !数字.matches("\\d+")) { m(); return; }   // 第二段恒假
     *       if (parseInt(数字) >= parseInt(今天)) return;
     *   }
     *   m();
     * </pre>
     *
     * @return 是否触发了续期
     */
    public boolean checkTime() {
        String cached = readCache(TIME_KEY);
        if (!TextUtils.isEmpty(cached)) {
            String digits = cached.replaceAll("[^0-9]", "").trim();
            if (digits.isEmpty()) {
                Log.w(TAG, "xltime 缓存无数字，直接续期：" + cached);
                refreshShare();
                return true;
            }
            if (Long.parseLong(digits) >= Long.parseLong(today())) {
                Log.i(TAG, "迅雷今日已续期（" + digits + "）");
                return false;
            }
        }
        refreshShare();
        return true;
    }

    /**
     * 转存续期 —— 还原 {@code m()}。
     *
     * @return 是否成功转存并写入 {@code xltime}
     */
    public boolean refreshShare() {
        if (!loggedIn()) {
            reloadCredential();
        }
        if (TextUtils.isEmpty(deviceId)) {
            Log.w(TAG, "无 deviceId，续期中止");
            return false;
        }

        // ① 风控票
        String token = captchaToken(ACTION_SHARE, USER_ID);
        if (TextUtils.isEmpty(token)) {
            Log.w(TAG, "拿不到 captcha_token（share）");
            return false;
        }

        // ② 查分享
        JSONObject share = parseObject(fetchWith(shareListUrl(), headers(token)));
        if (share == null || !STATUS_OK.equals(share.optString("share_status", ""))) {
            Log.w(TAG, "分享状态不是 OK");
            return false;
        }
        String parentId = "";
        JSONArray files = share.optJSONArray("files");
        if (files != null && files.length() > 0) {
            JSONObject first = files.optJSONObject(0);
            if (first != null) {
                parentId = first.optString("id", "");
            }
        }
        String passCodeToken = share.optString("pass_code_token", "");
        String encoded = urlEncode(passCodeToken);

        // ③ 列分享
        JSONObject detail = parseObject(fetchWith(shareDetailUrl(parentId, encoded), headers(token)));
        JSONArray list = detail == null ? null : detail.optJSONArray("files");
        if (list == null || list.length() == 0) {
            Log.w(TAG, "分享里没有文件");
            return false;
        }

        // ④ 随机挑一个
        JSONObject picked = list.optJSONObject(pickIndex(list.length()));
        if (picked == null) {
            return false;
        }
        String fileId = picked.optString("id", "");
        if (TextUtils.isEmpty(fileId)) {
            return false;
        }

        // ⑤ 转存
        String about = captchaToken(ACTION_ABOUT, USER_ID);
        String body = buildRestoreBody(encoded, fileId);
        JSONObject restored = parseObject(postWith(API_SHARE_RESTORE, body, authHeaders(about)));
        if (restored == null || !STATUS_OK.equals(restored.optString("share_status", ""))) {
            Log.w(TAG, "转存未返回 share_status=OK");
            return false;
        }

        // ⑥ 记 trace + 日期，等 1s 再删
        JSONObject params = restored.optJSONObject("params");
        String trace = params == null ? "" : params.optString("trace_file_ids", "");
        this.traceFileIds = trace == null ? "" : trace;
        writeCache(TIME_KEY, today());
        Log.i(TAG, "转存完成：" + fileId + " trace=" + head(trace, 160));
        sleep(1000L);
        deleteTraceFiles();
        return true;
    }

    /**
     * 删掉刚转存的痕迹文件 —— 还原 {@code u()}。
     *
     * <p>上游只取 {@code trace_file_ids} 的<b>第一个</b> key（缺陷 4），本实现保留该行为。
     *
     * @return 是否成功提交删除任务
     */
    public boolean deleteTraceFiles() {
        if (TextUtils.isEmpty(traceFileIds)) {
            return false;
        }
        String token = captchaToken(ACTION_FILES, USER_ID);
        if (TextUtils.isEmpty(token)) {
            Log.w(TAG, "拿不到 captcha_token（files）");
            return false;
        }
        JSONObject trace = parseObject(traceFileIds);
        if (trace == null) {
            Log.w(TAG, "trace_file_ids 不是 JSON：" + head(traceFileIds, 80));
            return false;
        }
        java.util.Iterator<String> keys = trace.keys();
        if (keys == null || !keys.hasNext()) {
            return false;
        }
        int total = trace.length();
        String key = keys.next();
        if (total > 1) {
            Log.w(TAG, "trace_file_ids 有 " + total + " 个，上游只删第一个");
        }
        JSONObject del = parseObject(postWith(API_BATCH_DELETE, buildBatchDeleteBody(key), authHeaders(token)));
        if (del == null) {
            Log.w(TAG, "batchDelete 无响应/非 JSON");
            return false;
        }
        String taskId = del.optString("task_id", "");
        if (TextUtils.isEmpty(taskId)) {
            Log.w(TAG, "batchDelete 没有 task_id");
            return false;
        }
        String ignored = fetchWith(API_TASKS + taskId, authHeaders(token));
        Log.d(TAG, "task " + taskId + " -> " + head(ignored, 120));
        return true;
    }

    /**
     * 风控 init —— 还原 {@code q(String action, String userId)}。
     *
     * @param action 形如 {@code get:/drive/v1/share}
     * @param userId 匿名 id（上游 {@code i} = {@code "0"}）
     * @return {@code captcha_token}；失败空串
     */
    public String captchaToken(String action, String userId) {
        long ts = nowMillis();
        String seed = signSeed(deviceId, ts);
        String sign = captchaSign(seed);
        String body = buildCaptchaBody(action, sign, ts, userId == null ? "" : userId);

        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", CAPTCHA_UA);
        h.put("Referer", REFERER);

        JSONObject resp = parseObject(postWith(API_CAPTCHA_INIT, body, h));
        if (resp == null) {
            return "";
        }
        String token = resp.optString("captcha_token", "");
        return token == null ? "" : token;
    }

    /**
     * 挑一个下标 —— 上游 {@code new Random().nextInt(len)}。
     *
     * @param len 长度
     * @return 合法下标
     */
    public int pickIndex(int len) {
        if (len <= 0) {
            return 0;
        }
        Random r = random != null ? random : new Random();
        return r.nextInt(len);
    }

    // ─────────────────────────────────────────────────────────────
    // 容错 JSON
    // ─────────────────────────────────────────────────────────────

    /**
     * 解析 JSON 对象 —— 代替上游「{@code contains("{")}」的判断。
     *
     * @param text 文本
     * @return 对象；空/非对象/畸形 → null
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

    private static String head(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
