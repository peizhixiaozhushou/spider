package com.example.tvbox.plain.app99;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App99 的 <b>Spider 外壳</b> —— 剧圈99 / 半日99 / 双星99 三站共用的 TVBox 内容源。
 *
 * <h2>为什么需要这个类（与 {@link App99Plain} 的分工）</h2>
 * {@link App99Plain} 是<b>协议核</b>（纯静态工具：签名、AES+IV、zlib、请求体拼装、
 * 站点常量表），<b>没有基类、不是源</b>；本类才是宿主真正调用的源，负责
 * <i>ext 契约 → 会话初始化 → 5 个内容方法 → 响应模型</i>。
 * 两者关系：本类只做编排与映射，密码学与报文格式<b>全部委托给 {@link App99Plain}</b>
 * （不再重复推导，见其 javadoc 里逐条证据）。
 *
 * <h2>★ 一处对 {@link App99Plain} 的证据更正（[U3] 已解决）</h2>
 * {@code App99Plain} 的 [U3] 写着"业务接口路径未在 App99 里出现明文常量，推测由 ext
 * 或 systemInit 下发，本类提供骨架 + 标注，不编造路径"。<b>这句话已被宿主类代码推翻</b>
 * —— 路径全部是宿主类里的<b>明文字面量</b>，本类按实测值固化（并把它们暴露成常量）：
 * <table border="1">
 *   <tr><th>用途</th><th>路径</th><th>方法</th></tr>
 *   <tr><td>公共初始化</td><td>{@code /app/systemInit}</td><td>POST（{@code init}）</td></tr>
 *   <tr><td>登录/用户信息</td><td>{@code /app/userInfo}（可由 ext {@code LoginPath} 覆盖）</td>
 *       <td>POST（{@code init}）</td></tr>
 *   <tr><td>分类 + 首页 + 搜索</td><td>{@code /vod/search}</td><td>POST</td></tr>
 *   <tr><td>详情</td><td>{@code /vod/detail}</td><td>POST</td></tr>
 *   <tr><td>播放地址解析</td><td>{@code /app/vodParser}</td><td>POST（{@code playerContent}）</td></tr>
 * </table>
 * 因此 {@link App99Plain#PATH_CATEGORY} 等四个占位常量在本类里<b>不再使用</b>。
 *
 * <h2>ext 契约（6 个必填 + 4 个可选）</h2>
 * <pre>
 *   必填: host  name  buildSignature  buildNumber  versionName  package
 *         （任一为空 → init 直接返回，源不可用 —— 上游就是这样静默失败的）
 *   可选: uuid（缺省随机 UUID）、ua（缺省桌面 Chrome/151）、version（缺省 = versionName）、
 *         LoginPath（缺省 /app/userInfo）
 * </pre>
 * <b>uuid 同时是数据加密密钥</b>（去横线后 32 字节 → AES-256），参见
 * {@link App99Plain#aesKeyBitsOfUuid(String)} —— 换 uuid 等于换密钥。
 *
 * <h2>会话状态（上游 5 个字段 + 1 个缓存表）</h2>
 * <ul>
 *   <li>{@code host} —— 站点根，来自 ext（<b>不含</b>尾斜杠，路径自带前导斜杠）</li>
 *   <li>{@code userToken} —— 登录后由 {@code LoginPath} 响应的 {@code userInfo.user_token}
 *       填充；<b>首轮请求它还是空串</b>，签名原文因此以冒号结尾（见 {@link App99Plain#sign}）</li>
 *   <li>{@code uuid} —— 设备标识，同时是密钥</li>
 *   <li>{@code ua} / {@code version} —— 请求头值</li>
 *   <li>{@code cache} —— {@code systemInit} 响应缓存：{@code player}（线路解码表）、
 *       {@code parses}（解析器列表）、{@code categories}（分类 + 筛选项）</li>
 * </ul>
 *
 * <h2>修正 / 记录的上游问题</h2>
 * <ol>
 *   <li><b>筛选参数收了不用（上游缺陷，本重写可选修复）</b>：{@code categoryContent} 里
 *       连着四段 {@code if (map != null && map.containsKey("class")) { map.get("class"); }}
 *       —— 取值后<b>直接丢弃</b>，既没进请求体也没进 URL。也就是说首页发布的
 *       "类型/地区/语言/年份"筛选<b>点了等于没点</b>。本重写默认<b>保持上游行为</b>
 *       （{@code useFilters=false}），并提供 {@link #setUseFilters(boolean)} 打开真正下发；
 *       钉住断言：{@code App99SpiderTest#filterPassthrough()}。</li>
 *   <li><b>页码无容错</b>：结尾 {@code Integer.parseInt(str2)} 遇到空/非数字页码抛 NFE。
 *       已用 {@code pageNum(pg,1)}；钉住断言：{@code App99SpiderTest#pageTolerance()}。</li>
 *   <li><b>{@code playerContent} 的 split 越界</b>：上游 {@code str2.split("@")} 后直接取
 *       {@code [1]/[2]/[3]}，少于 4 段就 AIOOBE，最终落到 catch 返回"播放链接解析失败，
 *       请更换其他源播放"。已改为显式长度判断后走同一句提示（行为等价、无异常）；
 *       钉住断言：{@code App99SpiderTest#playerMalformedId()}。</li>
 *   <li><b>弹幕地址未做 URL 编码</b>（上游三处拼接同一字面量）：片名含 {@code &} 或
 *       {@code #} 会截断查询串。本重写补上编码，与仓库内 {@code appsrc/AppSourceBase}
 *       的同类修复一致。</li>
 *   <li><b>{@code detailContent} 的 {@code play_url} 依赖服务端 {@code name$url} 两段格式</b>：
 *       上游 {@code split("\\$")} 后直接取 {@code [1]}，服务端换成三段就 AIOOBE（被 catch 吞掉后
 *       详情只剩空壳）。已改为"取不到第二段就整段当 url"并注释；钉住断言：
 *       {@code App99SpiderTest#playUrlOneSegment()}。</li>
 *   <li><b>被静默吞掉的解析失败</b>：上游多处 {@code catch (Exception) { printStackTrace(); }}，
 *       失败后返回空详情/空列表。本重写保留"不抛"语义但改为可观测的日志。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 宿主类反编译原文: {@code ae/decompiled/App99.java}（647 行，逐行核对：
 * 字段 {@code a..e}/{@code g}、方法 {@code a/b/c/d/e/f/g}、五个内容方法、{@code processDetailResult}）。
 * 协议核与证据编号: {@link App99Plain} 的类 javadoc。
 * 响应模型（{@code danmaku}/{@code url} 两个键）来自 DEX 反汇编
 * {@code merge.p028c.c} 的 {@code @SerializedName}：{@code a(String)→f→"danmaku"}、
 * {@code u(String)→g→"url"}。
 
 *
 * <h2>未覆盖的分支</h2>
 * 逐条列出，避免"漏实现"与"上游本来没有"混淆：
 * <ol>
 *   <li><b>{@code homeVideoContent} 上游没有实现</b>：宿主 {@code App99} 的 15 个方法里
 *       只有 5 个内容方法（{@code homeContent/categoryContent/detailContent/
 *       searchContent/playerContent}）。<b>本类不写 {@code homeVideoContent}</b> ——
 *       基类默认返回空串即宿主需要的"无轮播"语义。请勿"热心补上"：
 *       上游从未有过这个接口的报文格式，任何实现都是编造。</li>
 *   <li><b>筛选参数默认不下发</b>（{@code useFilters=false} = 上游行为）。
 *       {@code setUseFilters(true)} 才把 class/area/lang/year 写进请求体；
 *       <b>服务端是否认这四个键未知</b> —— 上游从来没发过，没有报文样本可核对。</li>
 *   <li><b>弹幕字段依赖宿主代理</b>：{@code Proxy.getUrl()} 在本重写里是
 *       {@link #setProxyBase} 注入；未注入时输出**不含** {@code danmaku} 键
 *       （上游一定含，值形如 {@code ?do=appdanmu…} 的空前缀地址）。</li>
 *   <li><b>解析器业务判定</b>：{@code /app/vodParser} 只认 {@code data} 以
 *       {@code http} 开头（上游 {@code startsWith("http")}），
 *       https/相对路径/多级 JSON 都不认；本类保持同一判定。</li>
 *   <li><b>{@code page_count} 的宿主语义</b>：上游把 page_count 直接塞进
 *       {@code c.i(page, page_count, MAX, MAX)}；本重写用
 *       {@code page(page, page_count * 21, 21)} 复现同一个 pagecount，
 *       但 {@code total/limit} 不再是 {@code Integer.MAX_VALUE}（见类注释）。</li>
 *   <li><b>未还原</b>：站点真实 host 与 uuid 下发方式（ext 给），
 *       以及服务端对 sig 的校验细节（无样本）。</li>
 * </ol>

 *
 * <h2>协议核 ↔ 外壳的文件名对应关系（防止"文件在就是做完了"）</h2>
 * <table border="1">
 *   <tr><th>宿主类</th><th>协议核（无基类、不是源）</th><th>外壳（真源）</th></tr>
 *   <tr><td>{@code com.github.catvod.spider.App99}</td>
 *       <td>{@code maintain/src/plain/app99/App99Plain.java}</td>
 *       <td>{@code maintain/src/plain/app99/App99SpiderPlain.java}（本类）</td></tr>
 * </table>
 * 本类负责三件事：① ext 契约与两次握手（systemInit / LoginPath）；
 * ② 5 个内容方法（home/category/detail/search/player，<b>没有 homeVideoContent</b>）；
 * ③ 把签名与加解密接到 {@link App99Plain} 上（本类不做任何密码学推导）。
 *
 * <p><b>coverage.py 如何识别这个结构</b>：工具按
 * "<i>宿主类名 + Plain</i>" 与 "<i>宿主类名 + SpiderPlain</i>" 两个候选名找实现，
 * 再<b>沿继承链走到 Spider</b> 才算 done ——
 * {@link App99Plain} 是纯静态工具（不继承任何 Spider），所以它永远到不了 done；
 * 本类 {@code extends BaseSpider}（→ {@code Spider}）才是被计入的那一个。
 * 报告里这两个文件会一起显示为 {@code App99Plain + App99SpiderPlain}，
 * 意思是"协议核与外壳都已在位"。只看到其中一半时，请先确认是哪一半。
*/
public class App99SpiderPlain extends BaseSpider {

    private static final String TAG = "App99Spider";

    // ── 路径常量（DEX 实测字面量，见类注释的更正说明） ────────

    /** 公共初始化端点 */
    public static final String PATH_SYSTEM_INIT = "/app/systemInit";
    /** 分类 / 首页 / 搜索共用端点 */
    public static final String PATH_VOD_SEARCH = "/vod/search";
    /** 详情端点 */
    public static final String PATH_VOD_DETAIL = "/vod/detail";
    /** 播放地址解析端点 */
    public static final String PATH_VOD_PARSER = "/app/vodParser";
    /** 登录/用户信息端点缺省值（ext {@code LoginPath} 可覆盖） */
    public static final String DEFAULT_LOGIN_PATH = "/app/userInfo";

    /** 每页条数（上游三处都是 21） */
    public static final int PAGE_LIMIT = 21;
    /** 搜索排序键 */
    public static final String ORDER_BY_SEARCH = "vod_hits_month";
    /** 分类排序键 */
    public static final String ORDER_BY_CATEGORY = "time";
    /** 详情请求里固定的 {@code v} 字段 */
    public static final String DETAIL_VERSION_FIELD = "2.0.0";
    /** 详情请求里固定的 {@code eps} 字段 */
    public static final String DETAIL_EPS = "1";
    /** 播放失败提示（上游原文） */
    public static final String PLAY_FAILED_HINT = "播放链接解析失败,请更换其他源播放";
    /** 弹幕动作（与 {@code Proxy} 代理约定的 {@code do} 参数） */
    public static final String DANMAKU_ACTION = "appdanmu";
    /** 默认 UA（与 {@link App99Plain#DEFAULT_UA} 同源） */
    public static final String DEFAULT_UA = App99Plain.DEFAULT_UA;

    /** ext 必填键（缺一即初始化失败，上游静默返回） */
    public static final String[] REQUIRED_EXT_KEYS = {
            "host", "name", "buildSignature", "buildNumber", "versionName", "package",
    };

    /** 详情里被 {@link #reorderPlayByQuality} 提前的线路关键字（顺序即优先级） */
    public static final String QUALITY_FIRST = "4K";
    /** 次优先线路关键字 */
    public static final String QUALITY_SECOND = "蓝光";

    // ── 会话状态 ─────────────────────────────────────────────

    /** 站点根（ext.host） */
    protected String host = "";
    /** 登录令牌（ext/LoginPath 响应填充） */
    protected String userToken = "";
    /** 设备标识（同时是 AES 密钥来源） */
    protected String uuid = "";
    /** 请求 UA */
    protected String ua = DEFAULT_UA;
    /** 上报版本（ext.version 或 versionName） */
    protected String version = "";
    /** 登录路径 */
    protected String loginPath = DEFAULT_LOGIN_PATH;
    /** ext 里的站点描述（构造 {@link App99Plain.Site} 用） */
    protected App99Plain.Site site;

    /** systemInit 响应缓存：player / parses / categories */
    protected final Map<String, Object> cache = new LinkedHashMap<>();

    /** 弹幕代理前缀（空串 = 不写 danmaku 字段，与仓库内 AppSourceBase 约定一致） */
    protected String proxyBase = "";

    /** 是否把筛选参数真正下发（默认 false = 保持上游"收了不用"的行为） */
    protected boolean useFilters = false;

    /** 构造 */
    public App99SpiderPlain() {
        super();
    }

    // ── 生命周期 / ext 契约 ──────────────────────────────────

    /**
     * 初始化 —— 上游 {@code init(Context, String)} 的完整还原。
     *
     * <p>顺序：校验 6 个必填 ext → 存会话字段 → POST {@code /app/systemInit} 取
     * player/parses/categories → 若 {@code userToken} 仍为空则 POST {@code LoginPath} 取
     * {@code userInfo.user_token}。任一步失败都不抛（上游全包在一个 try/catch 里）。
     *
     * @param context 宿主上下文，可为 null
     * @param extend  ext JSON
     */
    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        configure(extend);
    }

    /**
     * 解析 ext 并完成会话初始化（可单测的主体，与 {@link #init} 等价）。
     *
     * @param extend ext JSON 文本
     * @return true 表示必填项齐全且已尝试初始化；false 表示 ext 不合格（源不可用）
     */
    public boolean configure(String extend) {
        if (TextUtils.isEmpty(extend)) {
            Log.w(TAG, "ext 为空，App99 源不可用（上游同样直接 return）");
            return false;
        }
        try {
            JSONObject o = new JSONObject(extend);
            String h = o.optString("host", "");
            String name = o.optString("name", "");
            String sig = o.optString("buildSignature", "");
            String buildNumber = o.optString("buildNumber", "");
            String versionName = o.optString("versionName", "");
            String pkg = o.optString("package", "");
            if (TextUtils.isEmpty(h) || TextUtils.isEmpty(name) || TextUtils.isEmpty(sig)
                    || TextUtils.isEmpty(buildNumber) || TextUtils.isEmpty(versionName)
                    || TextUtils.isEmpty(pkg)) {
                Log.w(TAG, "ext 缺少必填项（host/name/buildSignature/buildNumber/"
                        + "versionName/package），源不可用");
                return false;
            }
            this.host = h;
            this.uuid = o.optString("uuid", App99Plain.newUuid());
            this.ua = o.optString("ua", DEFAULT_UA);
            this.version = o.optString("version", versionName);
            this.loginPath = o.optString("LoginPath", DEFAULT_LOGIN_PATH);
            this.site = new App99Plain.Site(name, h, name, versionName, pkg,
                    buildNumber, sig, loginPath);

            // ① 公共初始化
            String resp = postSystemInit(versionName, name, sig);
            if (!TextUtils.isEmpty(resp)) {
                JSONObject r = new JSONObject(resp);
                if (r.has("player")) {
                    cache.put("player", r.getJSONObject("player"));
                }
                if (r.has("parser_api")) {
                    cache.put("parses", r.getJSONArray("parser_api"));
                }
                if (r.has("categorys")) {
                    JSONObject cat = r.getJSONObject("categorys");
                    if (cat.has("data")) {
                        cache.put("categories", cat.getJSONArray("data"));
                    }
                }
            }

            // ② 首次登录（token 为空时）
            if (TextUtils.isEmpty(userToken)) {
                String lr = postLogin(versionName, name, pkg, buildNumber, sig);
                if (TextUtils.isEmpty(lr)) {
                    return true;
                }
                JSONObject lo = new JSONObject(lr);
                if (lo.has("userInfo")) {
                    JSONObject ui = lo.getJSONObject("userInfo");
                    if (ui.has("user_token")) {
                        this.userToken = ui.optString("user_token", "");
                    }
                }
            }
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "init 失败（上游同样吞异常后静默）", e);
            return false;
        }
    }

    // ── 请求发送 ─────────────────────────────────────────────

    /**
     * 一次完整 POST —— 生产入口：自动生成 nonce/时间戳，
     * <b>请求体与签名头共用同一对 nonce/时间戳</b>（上游就是这个顺序：
     * 先 {@code e()} 取 nonce、再取毫秒时间戳，然后拷进 body，再拿同一对值做签名头）。
     *
     * @param path      接口路径
     * @param plainBody 明文 JSON（由调用方用 {@link #bodyWith} 构造）
     * @param nonce     nonce
     * @param timestamp 毫秒时间戳文本
     * @return 解密后的响应文本；任何失败返回空串（不抛）
     */
    public String postSigned(String path, String plainBody, String nonce, String timestamp) {
        try {
            String cipher = App99Plain.encryptBody(plainBody, uuid);
            Map<String, String> headers = App99Plain.headers(
                    nonce, timestamp, cipher, uuid, userToken, version, ua);
            String raw = send(host + path, cipher, headers);
            if (TextUtils.isEmpty(raw)) {
                return "";
            }
            return App99Plain.decryptResponse(raw, uuid, uuid);
        } catch (Throwable e) {
            Log.e(TAG, "postSigned failed: " + path, e);
            return "";
        }
    }

    /**
     * 自动生成 nonce/时间戳的 POST。
     *
     * @param path      接口路径
     * @param plainBody 明文 JSON
     * @return 解密后的响应文本；失败为空串
     */
    public String post(String path, String plainBody) {
        return postSigned(path, plainBody, App99Plain.nonce(),
                String.valueOf(System.currentTimeMillis()));
    }

    /**
     * 以有序字段表发 POST：自动补 {@code timestamp}/{@code nonce} 并保证与签名头一致。
     *
     * @param path   接口路径
     * @param fields 字段（<b>有序</b>；顺序会影响请求体文本，进而影响调试比对）
     * @return 解密后的响应文本；失败为空串
     */
    public String postFields(String path, Map<String, String> fields) {
        String nonce = App99Plain.nonce();
        String ts = String.valueOf(System.currentTimeMillis());
        return postSigned(path, bodyWith(fields, ts, nonce), nonce, ts);
    }

    /**
     * 发公共初始化请求（{@code /app/systemInit}）。
     *
     * @param versionName 版本名
     * @param name        站点名
     * @param buildSig    包签名
     * @return 解密后的响应文本；失败为空串
     */
    public String postSystemInit(String versionName, String name, String buildSig) {
        String nonce = App99Plain.nonce();
        String ts = String.valueOf(System.currentTimeMillis());
        return postSigned(PATH_SYSTEM_INIT + "",
                App99Plain.systemInitBody(versionName, name, buildSig, ts, nonce), nonce, ts);
    }

    /**
     * 发登录/用户信息请求（{@code LoginPath}）。
     *
     * @param versionName 版本名
     * @param name        站点名
     * @param pkg         包名
     * @param buildNumber 构建号
     * @param buildSig    包签名
     * @return 解密后的响应文本；失败为空串
     */
    public String postLogin(String versionName, String name, String pkg,
                            String buildNumber, String buildSig) {
        String nonce = App99Plain.nonce();
        long now = System.currentTimeMillis();
        String ts = String.valueOf(now);
        return postSigned(loginPath,
                App99Plain.loginBody(versionName, name, pkg, buildNumber, buildSig,
                        now, App99Plain.newUuid(), ts, nonce), nonce, ts);
    }

    /**
     * 提交通用业务请求体（{@code /vod/search}、{@code /vod/detail}、{@code /app/vodParser}）。
     *
     * @param path   接口路径
     * @param fields 字段（有序）
     * @return 解密后的响应文本；失败为空串
     */
    public String postBiz(String path, Map<String, String> fields) {
        return postFields(path, fields);
    }

    /**
     * 把有序字段拼成请求体 JSON —— 在末尾补 {@code timestamp}/{@code nonce}
     * （与上游"手写 put 后再补两项"的顺序一致）。
     *
     * @param fields    字段（有序），可为 null
     * @param timestamp 毫秒时间戳文本
     * @param nonce     nonce
     * @return 明文 JSON
     */
    public static String bodyWith(Map<String, String> fields, String timestamp, String nonce) {
        JSONObject o = new JSONObject();
        try {
            if (fields != null) {
                for (Map.Entry<String, String> e : fields.entrySet()) {
                    o.put(e.getKey(), e.getValue());
                }
            }
            o.put("timestamp", timestamp == null ? "" : timestamp);
            o.put("nonce", nonce == null ? "" : nonce);
        } catch (Throwable e) {
            Log.e(TAG, "bodyWith failed", e);
        }
        return o.toString();
    }

    /**
     * 自动时间戳/nonce 的 {@link #bodyWith}（便于排障手测）。
     *
     * @param fields 字段
     * @return 明文 JSON
     */
    public static String buildBody(Map<String, String> fields) {
        return bodyWith(fields, String.valueOf(System.currentTimeMillis()), App99Plain.nonce());
    }

    /**
     * 底层发送（覆写即可完全离线测试）。
     *
     * @param url     完整地址
     * @param body    已加密的 Base64 请求体
     * @param headers 头表
     * @return 响应文本；失败为空串
     */
    public String send(String url, String body, Map<String, String> headers) {
        try {
            return http.post(url, body, headers);
        } catch (Throwable e) {
            Log.e(TAG, "send failed: " + url, e);
            return "";
        }
    }

    // ── 响应解析（纯函数） ───────────────────────────────────

    /**
     * 条目解析 —— 上游 {@code b(JSONArray)} 的等价实现。
     *
     * <p>字段：{@code id/name/pic/remarks/year/blurb/class/area/actor/director}
     * （{@code blurb} 进详情简介，列表里先放 remarks 位）。
     *
     * @param arr 数组，可为 null
     * @return 非 null 列表（跳过非对象元素）
     */
    public static List<Vod> parseItems(JSONArray arr) {
        List<Vod> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            out.add(new Vod()
                    .id(o.optString("id", ""))
                    .name(o.optString("name", ""))
                    .pic(o.optString("pic", ""))
                    .remarks(o.optString("remarks", ""))
                    .year(o.optString("year", ""))
                    .area(o.optString("area", ""))
                    .actor(o.optString("actor", ""))
                    .director(o.optString("director", ""))
                    .typeName(o.optString("class", ""))
                    .content(o.optString("blurb", "")));
        }
        return out;
    }

    /**
     * 筛选项解析 —— 上游 {@code a(JSONArray)}：数组元素当字符串取，构造 {@code (n,v)} 对。
     *
     * <p>注意上游对非字符串元素直接跳过（{@code optString(i)} 空串即丢），本实现保持一致。
     * 输出的每个选项是 {@code {"n":值,"v":值}}（上游 {@code new b.a(x,x)} 两个字段同值）。
     *
     * @param arr 数组，可为 null
     * @return 非 null 的 options 数组
     */
    public static JSONArray filterOptions(JSONArray arr) {
        JSONArray out = new JSONArray();
        if (arr == null) {
            return out;
        }
        try {
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, "");
                if (TextUtils.isEmpty(v)) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("n", v);
                o.put("v", v);
                out.put(o);
            }
        } catch (Throwable e) {
            Log.e(TAG, "filterOptions failed", e);
        }
        return out;
    }

    /**
     * 由分类的 {@code type_extend} 构造筛选组 —— 上游 {@code homeContent} 里那四行。
     *
     * @param typeExtend 分类对象里的 {@code type_extend}，可为 null
     * @return 非 null 的 filters 数组（{@code key/name/value}）
     */
    public static JSONArray buildFilters(JSONObject typeExtend) {
        JSONArray out = new JSONArray();
        if (typeExtend == null) {
            return out;
        }
        try {
            out.put(group("class", "类型", filterOptions(typeExtend.optJSONArray("class"))));
            out.put(group("area", "地区", filterOptions(typeExtend.optJSONArray("areas"))));
            out.put(group("lang", "语言", filterOptions(typeExtend.optJSONArray("lang"))));
            out.put(group("year", "年份", filterOptions(typeExtend.optJSONArray("years"))));
        } catch (Throwable e) {
            Log.e(TAG, "buildFilters failed", e);
        }
        return out;
    }

    /** 单组筛选 {@code {"key","name","value"}} */
    private static JSONObject group(String key, String name, JSONArray value) {
        JSONObject o = new JSONObject();
        try {
            o.put("key", key);
            o.put("name", name);
            o.put("value", value == null ? new JSONArray() : value);
        } catch (Throwable e) {
            Log.w(TAG, "group failed: " + key);
        }
        return o;
    }

    // ── 详情：播放串改写 ─────────────────────────────────────

    /**
     * 线路 code → 显示名映射 —— 上游从缓存的 {@code player} 表里取
     * {@code code → name}（两者都 trim）。
     *
     * @return 非 null 表
     */
    public Map<String, String> playerNameMap() {
        Map<String, String> out = new LinkedHashMap<>();
        Object o = cache.get("player");
        if (!(o instanceof JSONObject)) {
            return out;
        }
        JSONObject player = (JSONObject) o;
        try {
            java.util.Iterator<String> it = player.keys();
            while (it.hasNext()) {
                JSONObject one = player.optJSONObject(it.next());
                if (one == null) {
                    continue;
                }
                out.put(one.optString("code", "").trim(), one.optString("name", "").trim());
            }
        } catch (Throwable e) {
            Log.e(TAG, "playerNameMap failed", e);
        }
        return out;
    }

    /**
     * {@code vod_play_from} 改写：把每个 code 换成显示名，缺映射时保留原值。
     *
     * @param rawFrom  服务端原始 {@code play_from}（{@code $$$} 分隔）
     * @param codeName code→显示名
     * @return 改写后的串
     */
    public static String remapPlayFrom(String rawFrom, Map<String, String> codeName) {
        String[] parts = (rawFrom == null ? "" : rawFrom).split("\\$\\$\\$");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append("$$$");
            }
            String mapped = codeName == null ? null : codeName.get(p);
            sb.append(mapped == null ? p : mapped);
        }
        return sb.toString();
    }

    /**
     * 从集名里抽集序号 —— 上游 {@code name.replaceAll("\\D+", "")}，
     * 抽不到（全是非数字）时用 {@code "1"}。
     *
     * @param episodeName 集名
     * @return 数字串
     */
    public static String episodeIndex(String episodeName) {
        String digits = (episodeName == null ? "" : episodeName).replaceAll("\\D+", "");
        return digits.isEmpty() ? "1" : digits;
    }

    /**
     * 把服务端一组播放地址改写成宿主格式 —— 上游那段
     * {@code 名$地址@线路@片名@集号} 的拼接。
     *
     * <p>服务端条目格式是 {@code 名$地址}（两段）。上游取 {@code [1]} 时未判长度，
     * 三段/一段都会 AIOOBE（被 catch 吞掉后详情只剩空壳）。本实现：取不到第二段时
     * 把整段当地址（更宽容），并保留 {@code 名$地址} 的拼法。
     *
     * @param groupEntries 该组地址（{@code #} 分隔）
     * @param groupCode    该组的原始 code（进 {@code @} 段）
     * @param vodName      片名（进 {@code @} 段）
     * @return 改写后的串
     */
    public static String rewritePlayUrls(String groupEntries, String groupCode, String vodName) {
        String[] entries = (groupEntries == null ? "" : groupEntries).split("#");
        StringBuilder sb = new StringBuilder();
        for (String e : entries) {
            if (e.isEmpty()) {
                continue;
            }
            String[] kv = e.split("\\$");
            String name = kv.length > 0 ? kv[0] : "";
            String url = kv.length > 1 ? kv[1] : e;
            if (sb.length() > 0) {
                sb.append("#");
            }
            sb.append(name).append('$').append(url)
                    .append('@').append(groupCode == null ? "" : groupCode)
                    .append('@').append(vodName == null ? "" : vodName)
                    .append('@').append(episodeIndex(name));
        }
        return sb.toString();
    }

    /**
     * 把服务端 {@code play_from}/{@code play_url} 改写成宿主格式。
     *
     * @param rawFrom  服务端 {@code play_from}
     * @param rawUrl   服务端 {@code play_url}
     * @param vodName  片名
     * @param codeName code→显示名
     * @return 长度 2 的数组：{@code [play_from, play_url]}
     */
    public static String[] rewritePlays(String rawFrom, String rawUrl, String vodName,
                                        Map<String, String> codeName) {
        String[] froms = (rawFrom == null ? "" : rawFrom).split("\\$\\$\\$");
        String[] urls = (rawUrl == null ? "" : rawUrl).split("\\$\\$\\$");
        StringBuilder fromOut = new StringBuilder();
        StringBuilder urlOut = new StringBuilder();
        for (int i = 0; i < urls.length; i++) {
            String code = i < froms.length ? froms[i] : "";
            String groupUrl = rewritePlayUrls(urls[i], code, vodName);
            if (urlOut.length() > 0 && groupUrl.length() > 0) {
                urlOut.append("$$$");
            }
            urlOut.append(groupUrl);
            if (fromOut.length() > 0) {
                fromOut.append("$$$");
            }
            String mapped = codeName == null ? null : codeName.get(code);
            fromOut.append(mapped == null ? code : mapped);
        }
        return new String[]{fromOut.toString(), urlOut.toString()};
    }

    /**
     * 线路质量重排 —— 上游 {@code processDetailResult}: 含 {@code 4K} 的排最前、
     * 含 {@code 蓝光} 的次之、其余最后；<b>若所有线路都是这两类则原样返回</b>。
     *
     * @param detailJson 详情响应文本
     * @return 重排后的文本；任何异常原样返回（上游同样 catch 后 return str）
     */
    public static String reorderPlayByQuality(String detailJson) {
        if (detailJson == null) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(detailJson);
            JSONArray list = root.optJSONArray("list");
            if (list == null || list.length() == 0) {
                return detailJson;
            }
            JSONObject vod = list.optJSONObject(0);
            if (vod == null) {
                return detailJson;
            }
            String from = vod.optString("vod_play_from", "");
            String url = vod.optString("vod_play_url", "");
            if (from.isEmpty() || url.isEmpty()) {
                return detailJson;
            }
            String[] froms = from.split("\\$\\$\\$");
            String[] urls = url.split("\\$\\$\\$");
            int n = Math.min(froms.length, urls.length);
            if (n == 0) {
                return detailJson;
            }
            List<String> firstF = new ArrayList<>();
            List<String> firstU = new ArrayList<>();
            List<String> secondF = new ArrayList<>();
            List<String> secondU = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (froms[i].contains(QUALITY_FIRST)) {
                    firstF.add(froms[i]);
                    firstU.add(urls[i]);
                } else if (froms[i].contains(QUALITY_SECOND)) {
                    secondF.add(froms[i]);
                    secondU.add(urls[i]);
                }
            }
            if (firstF.size() + secondF.size() == n) {
                return detailJson;   // 全部已是高优先级，无需重排（上游原样返回）
            }
            firstF.addAll(secondF);
            firstU.addAll(secondU);
            for (int i = 0; i < n; i++) {
                if (!froms[i].contains(QUALITY_FIRST) && !froms[i].contains(QUALITY_SECOND)) {
                    firstF.add(froms[i]);
                    firstU.add(urls[i]);
                }
            }
            vod.put("vod_play_from", join(firstF, "$$$"));
            vod.put("vod_play_url", join(firstU, "$$$"));
            // 把改好的条目**写回 root 自己的 map**：真实 org.json 里嵌套对象是同一引用，
            // 但本仓库的 JSONObject 桩对嵌套对象做惰性复制，直接改嵌套对象不会反映到
            // root.toString()。写回 list 在两种实现下都成立（语义也更明确）。
            JSONArray out = new JSONArray();
            out.put(vod);
            root.put("list", out);
            return root.toString();
        } catch (Throwable e) {
            Log.w(TAG, "reorderPlayByQuality: 解析失败，原样返回");
            return detailJson;
        }
    }

    /** 用分隔符连接 */
    static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 详情解析完成后的钩子 —— 上游 {@code merge.p026a.a.parseJsonAndSave(String)}。
     *
     * <p>上游把首条 {@code vod_name} 写进宿主偏好 {@code historyvodname}（弹幕匹配用），
     * 且列表为空时内部会抛异常再自己吞掉。本重写改为显式钩子：默认只记日志，覆写即可落库。
     *
     * @param detailJson 详情文本
     * @param vodName    首条片名（无数据时不调用本方法）
     */
    public void afterDetail(String detailJson, String vodName) {
        Log.i(TAG, "afterDetail: " + vodName);
    }

    // ── 5 个内容方法 ─────────────────────────────────────────

    /**
     * 首页 —— 分类表 + 筛选项 + 首页列表（热门）。
     *
     * <p>上游用 {@code pid="1"} 拉第一页当首页列表；分类来自 {@code systemInit} 缓存。
     * 缓存缺失时也能出分类（空表）而不崩。
     *
     * @param filter 宿主传参，未使用
     * @return 标准首页响应
     */
    @Override
    public String homeContent(boolean filter) {
        try {
            VodBuilder b = VodBuilder.create();
            List<Vod> list = new ArrayList<>();
            JSONArray cats = cachedCategories();
            for (int i = 0; i < cats.length(); i++) {
                JSONObject c = cats.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                String id = c.optString("id", "");
                b.clazz(id, c.optString("name", ""));
                JSONArray f = buildFilters(c.optJSONObject("type_extend"));
                if (f.length() > 0) {
                    b.filter(id, f);
                }
            }
            String resp = postBiz(PATH_VOD_SEARCH, homeFields());
            if (!TextUtils.isEmpty(resp)) {
                list = parseItems(new JSONObject(resp).optJSONArray("data"));
            }
            return b.addAll(list).homeContent();
        } catch (Throwable e) {
            Log.e(TAG, "homeContent failed", e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /**
     * 分类页。
     *
     * <p>默认<b>不下发</b>筛选参数（上游收了不用，见类注释缺陷 1）；
     * 打开 {@link #setUseFilters(boolean)} 后下发 {@code class/area/lang/year}。
     *
     * @param tid    分类 id
     * @param pg     页码（空/非数字按 1）
     * @param filter 宿主传参，未使用
     * @param extend 筛选（class/area/lang/year）
     * @return 标准分类响应
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        try {
            int page = pageNum(pg, 1);
            String resp = postBiz(PATH_VOD_SEARCH,
                    categoryFields(tid, page, extend));
            if (!TextUtils.isEmpty(resp)) {
                JSONObject r = new JSONObject(resp);
                if (r.has("data")) {
                    List<Vod> list = parseItems(r.optJSONArray("data"));
                    int pageCount = r.optInt("page_count", 1);
                    // 上游 i(page, page_count, MAX, MAX)：limit/total 都是"无穷"
                    return VodBuilder.create().addAll(list)
                            .page(page, Math.max(1, pageCount) * PAGE_LIMIT, PAGE_LIMIT)
                            .categoryContent();
                }
            }
            return VodBuilder.create().page(page).categoryContent();
        } catch (Throwable e) {
            Log.e(TAG, "categoryContent failed, tid=" + tid, e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /**
     * 详情页。
     *
     * <p>流程：code→显示名映射 → POST {@code /vod/detail} → 字段映射 →
     * {@code play_from/play_url} 改写 → 质量重排 → {@link #afterDetail}。
     *
     * @param ids 上游只取第一个
     * @return 标准详情响应
     */
    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids == null || ids.isEmpty() ? "" : ids.get(0);
            String resp = postBiz(PATH_VOD_DETAIL, detailFields(id));
            if (TextUtils.isEmpty(resp)) {
                return VodBuilder.EMPTY_RESPONSE;
            }
            JSONObject r = new JSONObject(resp);
            if (!r.has("data")) {
                return VodBuilder.EMPTY_RESPONSE;
            }
            JSONObject d = r.getJSONObject("data");
            String name = d.optString("name", "");
            String rawFrom = d.optString("play_from", "");
            String rawUrl = d.optString("play_url", "");
            String[] plays = rewritePlays(rawFrom, rawUrl, name, playerNameMap());
            Vod v = new Vod()
                    .id(d.optString("id", ""))
                    .name(name)
                    .pic(d.optString("pic", ""))
                    .remarks(d.optString("remarks", ""))
                    .year(d.optString("year", ""))
                    .area(d.optString("area", ""))
                    .actor(d.optString("actor", ""))
                    .director(d.optString("director", ""))
                    .content(d.optString("content", ""))
                    .typeName(d.optString("class", ""))
                    .playFrom(plays[0])
                    .playUrl(plays[1]);
            String json = reorderPlayByQuality(VodBuilder.detail(v).detailContent());
            afterDetail(json, name);
            return json;
        } catch (Throwable e) {
            Log.e(TAG, "detailContent failed", e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /**
     * 搜索。
     *
     * @param key    关键词
     * @param filter 宿主传参，未使用
     * @return {@code {"list":[…]}}
     */
    @Override
    public String searchContent(String key, boolean filter) {
        try {
            String resp = postBiz(PATH_VOD_SEARCH, searchFields(key));
            List<Vod> list = new ArrayList<>();
            if (!TextUtils.isEmpty(resp)) {
                JSONObject r = new JSONObject(resp);
                if (r.has("data")) {
                    list = parseItems(r.getJSONArray("data"));
                }
            }
            return VodBuilder.create().addAll(list).searchContent();
        } catch (Throwable e) {
            Log.e(TAG, "searchContent failed", e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /**
     * 播放。
     *
     * <p>入参格式（由 {@link #detailContent} 产出）：
     * {@code 地址@线路code@片名@集号}。
     * <ul>
     *   <li>输出是 {@code {"url":…,"danmaku":…}} 两个键 —— 上游只用
     *       {@code c.u(url)} 与 {@code c.a(danmaku)}，<b>没有 parse/jx 字段</b>
     *       （DEX 里 {@code a(String)→"danmaku"}、{@code u(String)→"url"}）；</li>
     *   <li>线路 {@code type != 0} 时按缓存的 {@code parses} 逐个试
     *       {@code /app/vodParser}，返回的 {@code data} 以 {@code http} 开头就替换地址；</li>
     *   <li>任何异常都返回"播放链接解析失败,请更换其他源播放"（上游同样）。</li>
     * </ul>
     *
     * @param flag 站点标识（未使用）
     * @param id   播放串
     * @param flags 未使用
     * @return 播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> flags) {
        try {
            String[] parts = (id == null ? "" : id).split("@");
            if (parts.length < 4) {
                Log.w(TAG, "播放串格式不符（需要 地址@线路@片名@集号）: " + id);
                return playFailed();
            }
            String url = parts[0];
            String code = parts[1];
            String vodName = parts[2].trim();
            String vodIndex = parts[3].trim();
            String danmaku = danmakuUrl(vodName, vodIndex);

            Object pObj = cache.get("player");
            JSONObject player = pObj instanceof JSONObject ? ((JSONObject) pObj).optJSONObject(code) : null;
            if (player != null && player.optInt("type", 0) != 0) {
                url = resolveViaParser(url, player);
            }
            JSONObject out = new JSONObject();
            out.put("url", url);
            if (!danmaku.isEmpty()) {
                out.put("danmaku", danmaku);
            }
            return out.toString();
        } catch (Throwable e) {
            Log.e(TAG, "playerContent failed", e);
            return playFailed();
        }
    }

    /**
     * 按缓存解析器列表试解析 —— 上游 {@code playerContent} 里那段双层循环。
     *
     * @param url    原始地址
     * @param player 该线路的配置（含 {@code type}/{@code parseUrl}）
     * @return 解析后的地址；全部失败时返回原始地址
     */
    public String resolveViaParser(String url, JSONObject player) {
        Object pObj = cache.get("parses");
        if (!(pObj instanceof JSONArray)) {
            Log.w(TAG, "无解析器缓存，保持原地址");
            return url;
        }
        JSONArray parses = (JSONArray) pObj;
        String csv = player == null ? "" : player.optString("parseUrl", "");
        String[] allow = TextUtils.isEmpty(csv) ? new String[0] : csv.split(",");
        for (int i = 0; i < parses.length(); i++) {
            JSONObject one = parses.optJSONObject(i);
            if (one == null) {
                continue;
            }
            String pid = String.valueOf(one.optInt("id"));
            if (allow.length > 0) {
                boolean hit = false;
                for (String a : allow) {
                    if (pid.equals(a)) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    continue;
                }
            }
            try {
                String resp = postBiz(PATH_VOD_PARSER, parserFields(pid, url));
                if (TextUtils.isEmpty(resp)) {
                    continue;
                }
                String data = new JSONObject(resp).optString("data", "");
                if (!TextUtils.isEmpty(data) && data.startsWith("http")) {
                    return data;
                }
            } catch (Throwable e) {
                Log.w(TAG, "解析器 " + pid + " 失败，继续下一个");
            }
        }
        return url;
    }

    /**
     * 解析器 id 是否被该线路允许（把上游那段内层 while 抽成可测函数）。
     *
     * @param csv        线路的 {@code parseUrl}（逗号分隔；空表示允许全部）
     * @param parserId   解析器 id
     * @return true 表示允许
     */
    public static boolean parserAllowed(String csv, String parserId) {
        if (TextUtils.isEmpty(csv)) {
            return true;
        }
        for (String s : csv.split(",")) {
            if (s.equals(parserId)) {
                return true;
            }
        }
        return false;
    }

    // ── 报文构造 & 小工具 ────────────────────────────────────

    /**
     * 首页请求体字段 —— 上游 {@code homeContent} 里那段。
     *
     * <p><b>注意 token 是字面量空串</b>（上游首页请求用 {@code ""}，
     * 分类/搜索才用 {@code this.b}），别顺手改成 {@link #userToken}。
     *
     * @return 有序字段表
     */
    public Map<String, String> homeFields() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("kw", "");
        p.put("page", "1");
        p.put("limit", String.valueOf(PAGE_LIMIT));
        p.put("pid", "1");
        p.put("orderBy", ORDER_BY_CATEGORY);
        p.put("isCategory", "1");
        p.put("token", "");
        return p;
    }

    /**
     * 分类请求体字段 —— 上游 {@code categoryContent}。
     *
     * @param tid    分类 id
     * @param page   页码
     * @param extend 筛选（仅当 {@link #useFilters()} 为 true 时下发）
     * @return 有序字段表
     */
    public Map<String, String> categoryFields(String tid, int page,
                                              Map<String, String> extend) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("kw", "");
        p.put("page", String.valueOf(page));
        p.put("limit", String.valueOf(PAGE_LIMIT));
        p.put("pid", tid == null ? "" : tid);
        p.put("orderBy", ORDER_BY_CATEGORY);
        p.put("isCategory", "1");
        if (useFilters && extend != null) {
            for (String k : new String[]{"class", "area", "lang", "year"}) {
                String v = extend.get(k);
                if (!TextUtils.isEmpty(v)) {
                    p.put(k, v);
                }
            }
        }
        p.put("token", userToken);
        return p;
    }

    /**
     * 搜索请求体字段 —— 上游 {@code searchContent}。
     *
     * @param key 关键词
     * @return 有序字段表
     */
    public Map<String, String> searchFields(String key) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("kw", key == null ? "" : key);
        p.put("page", "1");
        p.put("limit", String.valueOf(PAGE_LIMIT));
        p.put("orderBy", ORDER_BY_SEARCH);
        p.put("sort", "desc");
        p.put("token", userToken);
        return p;
    }

    /**
     * 详情请求体字段 —— 上游 {@code detailContent}。
     *
     * @param id 影片 id
     * @return 有序字段表
     */
    public Map<String, String> detailFields(String id) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("id", id == null ? "" : id);
        p.put("eps", DETAIL_EPS);
        p.put("v", DETAIL_VERSION_FIELD);
        p.put("pl", "1");
        p.put("token", userToken);
        return p;
    }

    /**
     * 解析器请求体字段 —— 上游 {@code playerContent} 里的 {@code /app/vodParser}。
     *
     * @param parserId 解析器 id
     * @param url      待解析地址
     * @return 有序字段表
     */
    public Map<String, String> parserFields(String parserId, String url) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("id", parserId == null ? "" : parserId);
        p.put("url", url == null ? "" : url);
        p.put("token", userToken);
        return p;
    }

    /**
     * 缓存里的分类数组（systemInit 下发），缺失返回空数组。
     *
     * @return 非 null 数组
     */
    public JSONArray cachedCategories() {
        Object o = cache.get("categories");
        return o instanceof JSONArray ? (JSONArray) o : new JSONArray();
    }

    /**
     * 构造弹幕地址（与仓库内 {@code AppSourceBase#danmakuUrl} 等价）。
     *
     * <p>上游三处都直接拼 {@code Proxy.getUrl() + "?do=appdanmu&vodName=…&vodIndex=…"}，
     * <b>不做 URL 编码</b>（片名含 {@code &}/{@code #} 会截断）。这里补编码。
     *
     * @param vodName  片名
     * @param vodIndex 集号
     * @return 弹幕地址；未注入 {@link #proxyBase} 时为空串
     */
    public String danmakuUrl(String vodName, String vodIndex) {
        if (TextUtils.isEmpty(proxyBase)) {
            return "";
        }
        return proxyBase + "?do=" + DANMAKU_ACTION
                + "&vodName=" + Http.encode(vodName == null ? "" : vodName)
                + "&vodIndex=" + Http.encode(vodIndex == null ? "" : vodIndex);
    }

    /** 播放失败响应（上游 {@code c.f("播放链接解析失败,请更换其他源播放")}） */
    public static String playFailed() {
        JSONObject o = new JSONObject();
        try {
            o.put("msg", PLAY_FAILED_HINT);
        } catch (Throwable e) {
            Log.w(TAG, "playFailed failed");
        }
        return o.toString();
    }

    // ── 注入点（默认实现保持上游行为） ────────────────────────

    /**
     * 注入弹幕代理前缀。
     *
     * @param base 形如 {@code http://127.0.0.1:9978/proxy}；空串表示不写 danmaku 字段
     */
    public void setProxyBase(String base) {
        this.proxyBase = base == null ? "" : base.trim();
    }

    /**
     * 当前弹幕代理前缀。
     *
     * @return 可能为空串
     */
    public String proxyBase() {
        return proxyBase;
    }

    /**
     * 是否把筛选参数真正下发（见类注释缺陷 1）。
     *
     * @param use true 时分类请求体带上 class/area/lang/year
     */
    public void setUseFilters(boolean use) {
        this.useFilters = use;
    }

    /**
     * 当前是否下发筛选参数。
     *
     * @return 默认 false（= 上游行为）
     */
    public boolean useFilters() {
        return useFilters;
    }

    /**
     * 测试/宿主注入会话状态（等价于 {@code configure} 成功后的内部字段）。
     *
     * @param host     站点根
     * @param uuid     设备 uuid（同时是密钥）
     * @param token    登录令牌
     */
    public void setSession(String host, String uuid, String token) {
        this.host = host == null ? "" : host;
        this.uuid = uuid == null ? "" : uuid;
        this.userToken = token == null ? "" : token;
        this.site = new App99Plain.Site("test", this.host, "test", "1.0.0", "pkg",
                "1", "sig", loginPath);
    }

    /**
     * 注入会话的 UA/版本。
     *
     * @param ua      UA
     * @param version 版本
     */
    public void setUaVersion(String ua, String version) {
        this.ua = TextUtils.isEmpty(ua) ? DEFAULT_UA : ua;
        this.version = version == null ? "" : version;
    }

    /**
     * 注入缓存表（player/parses/categories）。
     *
     * @param key   {@code player}/{@code parses}/{@code categories}
     * @param value 值
     */
    public void putCache(String key, Object value) {
        cache.put(key, value);
    }

    /**
     * 读缓存表。
     *
     * @param key 键
     * @return 值；不存在为 null
     */
    public Object cacheOf(String key) {
        return cache.get(key);
    }

    /**
     * 当前站点根。
     *
     * @return 可能为空串（未初始化）
     */
    public String host() {
        return host;
    }

    /**
     * 当前登录令牌。
     *
     * @return 可能为空串（首轮请求前的正常状态）
     */
    public String userToken() {
        return userToken;
    }

    /**
     * 当前设备 uuid。
     *
     * @return 可能为空串（未初始化）
     */
    public String uuid() {
        return uuid;
    }

    /**
     * 当前 UA。
     *
     * @return 非 null
     */
    public String ua() {
        return ua;
    }

    /**
     * 当前登录路径。
     *
     * @return 非 null
     */
    public String loginPath() {
        return loginPath;
    }

    /**
     * 供排障：所有缓存键。
     *
     * @return 非 null 集合
     */
    public java.util.Set<String> cacheKeys() {
        return cache.keySet();
    }
}
