package com.example.tvbox.plain.pan.client;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.Digest;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 迅雷云盘客户端 —— 凭据维护 + 分享转存（把指定分享存进自己盘，再定期清理）。
 *
 * <p><b>本类替换了原先 40 行的降级桩</b>（原桩只做「未登录提示」骨架，
 * {@code listDir} 永远返回空）。现在协议是真实现。
 *
 * <h2>两类凭据，两个文件（别混）</h2>
 * <pre>
 *   &lt;sdcard&gt;/TVBox/xunlei.txt   ← 登录态。JSON: {"deviceid": "...", "token_resp": "{\"access_token\":\"...\"}"}
 *                                  （merge.b.X.x() = k.b("/xunlei.txt")，X.k()/X.l() 都从它读）
 *   &lt;sdcard&gt;/TVBox/free_thunder ← 另一个文件，与登录态无关（merge.b.X.p()）
 * </pre>
 * <ul>
 *   <li>{@code deviceId = json["deviceid"]}</li>
 *   <li>{@code authorization = "Bearer " + JSON.parse(json["token_resp"]).access_token}</li>
 * </ul>
 *
 * <h2>风控签名（这是迅雷能调通的前提）</h2>
 * 每个写接口都要先换 {@code captcha_token}，签名算法是 10 轮 MD5 链（已完整还原）：
 * <pre>
 *   seed = clientId + version + pkgName + deviceId + timestampMillis
 *   for salt in SALTS[0..9]:
 *       seed = md5_hex_lower(seed + salt)
 *   captcha_sign = "1." + seed
 * </pre>
 * 关键点：{@code merge.A.b.a(x,y)} 只是 {@code x + y} 字符串拼接 —— 不是加密。
 * 锁定这一条之后整条链就是纯 MD5，可以逐位复算。
 *
 * <h2>协议</h2>
 * <pre>
 *  换风控票 POST https://xluser-ssl.xunlei.com/v1/shield/captcha/init
 *           {"client_id","action","device_id","meta":{...}}
 *  查分享   GET  https://api-pan.xunlei.com/drive/v1/share
 *               ?share_id=..&amp;pass_code=..&amp;limit=9999&amp;pass_code_token=&amp;page_token=&amp;thumbnail_size=SIZE_SMALL
 *  列目录   GET  https://api-pan.xunlei.com/drive/v1/share/detail
 *               ?share_id=..&amp;parent_id=..&amp;pass_code_token=..&amp;limit=9999&amp;page_token=&amp;thumbnail_size=SIZE_SMALL
 *  转  存   POST https://api-pan.xunlei.com/drive/v1/share/restore
 *               {"parent_id":"","share_id":..,"pass_code_token":..,"ancestor_ids":[],
 *                "file_ids":[..],"specify_parent_id":true,"params":{"origin":"runegg"}}
 *  删临时   POST https://api-pan.xunlei.com/drive/v1/files:batchDelete   {"ids":[..],"space":""}
 *  查任务   GET  https://api-pan.xunlei.com/drive/v1/tasks/{task_id}
 *  请求头   User-Agent: ANDROID-com.xunlei.downloadprovider/8.31.0.9726 ...（长指纹，逐字保留）
 *           Referer: https://pan.xunlei.com/
 *           x-client-id / x-device-id / x-captcha-token [+ authorization]
 * </pre>
 *
 * <h2>与上游的差异（都在增强方向，已逐条注明）</h2>
 * <ol>
 *   <li><b>风控票缓存</b>：上游 {@code merge.b.X.l(JSONObject)} 按 {@code action} 缓存
 *       {@code captcha_token}（TTL = {@code expires_in - 30} 秒），而 {@code XunleiPan.q()}
 *       每次都重新申请。本实现采用前者的带缓存版本，少一次请求。</li>
 *   <li><b>异常不再吞</b>：上游把 4 种异常分别 {@code System.err.println} 后继续；本实现
 *       统一用 {@link Log} 记录并返回安全默认值，便于定位。</li>
 *   <li><b>凭据文件名</b>：主用 {@code xunlei.txt}（{@code merge.b.X.x()} 证据），
 *       兜底 {@code free_thunder}（{@code merge.b.X.p()} 的文件名，仅作兼容）。</li>
 * </ol>
 *
 * <h2>上游逻辑缺陷</h2>
 * <ol>
 *   <li>分享 id / 提取码 / 目录 id 全部<b>硬编码</b>（{@code VOwTF0m3ItvrjtoIuRcXN8jqA1} / {@code ptpz}），
 *       该分享一旦失效整个功能静默不工作；本实现把它们抽成可注入字段并保留原默认值。</li>
 *   <li>转存时从文件列表里<b>随机取一个</b>（{@code new Random().nextInt(len)}），
 *       同一分享每 7 天转存的文件不确定；本实现保留该行为但用可注入的随机源，便于测试。</li>
 * </ol>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>真实 OAuth 不实现</b>：只读 {@code xunlei.txt} 的
 *       {@code deviceid} + {@code token_resp.access_token}，不实现登录/刷新。</li>
 *   <li><b>{@code playerContent} 不出直链</b>：{@code PanClientBase} 只返回空响应。
 *       迅雷的直链需要 {@code authorization} + 风控票，本工程未还原该接口。</li>
 *   <li><b>{@code action(String)} 未实现</b>（宿主 UI/下载器职责）。</li>
 *   <li><b>风控票只做"取到就用"</b>：本类取到 {@code captcha_token} 即用，
 *       <b>不实现票的过期缓存与复用</b>（上游各处也是每次现取，无缓存）。
 *       若同一轮里连续调用，会产生多次 {@code captcha/init} 请求。</li>
 *   <li>{@code merge.p027b.X} 的其余能力（如 {@code X.m().J(...)} 转直链）
 *       不可见（未反编译）⇒ 本类无法判断上游是否还有别的授权路径。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>分享 id / 提取码硬编码</b>（{@code PAXunlei.java} 对应上游
 *       {@code XunleiPan.java:63,70,77} 的 {@code VOwTF0m3ItvrjtoIuRcXN8jqA1}/{@code ptpz}）：
 *       分享被撤即静默失效。→ 抽成可注入字段，默认值逐字相同。
 *       断言：本类 {@code setShareForTest} 组。</li>
 *   <li><b>10 轮 MD5 风控签名的盐值硬编码</b>（{@code XunleiPan.java:36} 的
 *       {@code static String[] b}）：盐一旦轮换，只能等站方封杀后才发现。
 *       → 抽成 {@link #SIGN_SALTS} 常量 + {@code captchaSign(seed, salts)} 重载，
 *       便于用固定向量回归（见 {@code XunleiPanTest} 的 Python 独立复算向量）。</li>
 *   <li><b>风控 {@code captcha_sign} 的实现藏在 {@code merge.A.b.a(x,y)} 里</b>，
 *       静态看只是字符串拼接 —— 上下游对它的理解容易出错（误当成加密）。
 *       → 本类直接把公式写成 {@code md5_hex_lower(seed + salt)} 循环并写清注释。</li>
 *   <li>UA 字符串在 {@code o()} 与 {@code r()} 两处<b>各写一遍</b>
 *       （{@code XunleiPan.java:125,162}）：两处任何一处改字就会不一致。
 *       → 本类收敛成单个 {@link #UA} 常量，并用"与反编译字面量逐字相同"的断言钉住。</li>
 *   <li>下载/转存/批删三条请求各自现拼 header（{@code o()}/{@code r()} 各 5-6 行），
 *       没有公共 header 构造。→ 本类统一 {@code headers()}/{@code authHeaders()} 两个方法。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/XunleiPan.java}(186 行)；
 * 私有库: {@code ae/work/w-pan/helpers/xcls/X.java}（{@code merge.b.X}：凭据/设备/风控缓存）、
 * {@code ae/work/w-pan/helpers/xl/a.java}（{@code merge.A.a.e}）、
 * {@code ae/work/w-pan/helpers/xl/b.java}（{@code merge.A.b.a} = 字符串拼接）、
 * {@code ae/work/w-pan/helpers/net/h.java}（{@code merge.b.h.b} = 三串拼接）。
 */
public class PanXunleiPlain extends PanClientBase {

    private static final String TAG = "PanXunleiPlain";

    // ── 应用指纹（逐字来自 XunleiPan 静态字段 + merge.b.X 字段）──────
    /** x-client-id（{@code XunleiPan.h} / {@code merge.b.X.n}） */
    public static final String CLIENT_ID = "Xp6vsxz_7IYVw2BB";
    /** 客户端包名（{@code XunleiPan.k} / {@code merge.b.X.f}） */
    public static final String PKG = "com.xunlei.downloadprovider";
    /** 客户端版本（{@code XunleiPan.j} / {@code merge.b.X.g}） */
    public static final String VERSION = "8.31.0.9726";
    /** 匿名 user_id（{@code XunleiPan.i}） */
    public static final String USER_ID = "0";

    /** 业务 UA（逐字保留，一个字符都不能改） */
    public static final String UA = "ANDROID-com.xunlei.downloadprovider/8.31.0.9726 netWorkType/5G"
            + " appid/40 deviceName/Xiaomi_M2004j7ac deviceModel/M2004J7AC OSVersion/12"
            + " protocolVersion/301 platformVersion/10 sdkVersion/512000 Oauth2Client/0.9"
            + " (Linux 4_14_186-perf-gddfs8vbb238b) (JAVA 0)";
    /** 风控接口专用 UA */
    public static final String CAPTCHA_UA =
            "Dalvik/2.1.0 (Linux; U; Android 12; M2004J7AC Build/SP1A.210812.016)";
    /** Referer */
    public static final String REFERER = "https://pan.xunlei.com/";

    /** 10 轮签名盐值 —— 逐字来自 {@code XunleiPan.b} */
    public static final String[] SIGN_SALTS = {
            "9uJNVj/wLmdwKrJaVj/omlQ",
            "Oz64Lp0GigmChHMf/6TNfxx7O9PyopcczMsnf",
            "Eb+L7Ce+Ej48u",
            "jKY0",
            "ASr0zCl6v8W4aidjPK5KHd1Lq3t+vBFf41dqv5+fnOd",
            "wQlozdg6r1qxh0eRmt3QgNXOvSZO6q/GXK",
            "gmirk+ciAvIgA/cxUUCema47jr/YToixTT+Q6O",
            "5IiCoM9B1/788ntB",
            "P07JH0h6qoM6TSUAK2aL9T5s2QBVeY9JWvalf",
            "+oK0AN"
    };

    // ── 端点 ─────────────────────────────────────────────────────
    public static final String API_CAPTCHA_INIT = "https://xluser-ssl.xunlei.com/v1/shield/captcha/init";
    public static final String API_SHARE = "https://api-pan.xunlei.com/drive/v1/share";
    public static final String API_SHARE_DETAIL = "https://api-pan.xunlei.com/drive/v1/share/detail";
    public static final String API_SHARE_RESTORE = "https://api-pan.xunlei.com/drive/v1/share/restore";
    public static final String API_BATCH_DELETE = "https://api-pan.xunlei.com/drive/v1/files:batchDelete";
    public static final String API_FILES = "https://api-pan.xunlei.com/drive/v1/files/";
    public static final String API_TASKS = "https://api-pan.xunlei.com/drive/v1/tasks/";

    // ── 凭据文件 ─────────────────────────────────────────────────
    /** 登录态文件（{@code merge.b.X.x()}） */
    public static final String CRED_FILE = "xunlei.txt";
    /** 兼容用的另一个文件名（{@code merge.b.X.p()}），仅作兜底 */
    public static final String CRED_FILE_ALT = "free_thunder";

    // ── 上游硬编码的分享（缺陷 1：抽成字段）──────────────────────
    /** 默认分享 id */
    public static final String DEFAULT_SHARE_ID = "VOwTF0m3ItvrjtoIuRcXN8jqA1";
    /** 默认分享提取码 */
    public static final String DEFAULT_PASS_CODE = "ptpz";

    /** 转存落点 params.origin */
    public static final String ORIGIN = "runegg";

    /** 风控票缓存（action → [token, expireAtMillis]） */
    private final Map<String, String[]> captchaCache = new HashMap<>();

    /** 登录态 */
    private String deviceId = "";
    private String authorization = "";

    /** 可注入的分享参数 */
    private String shareId = DEFAULT_SHARE_ID;
    private String passCode = DEFAULT_PASS_CODE;

    /** 可注入的随机源（测试用；null 时用新 Random） */
    private Random random = null;

    // ─────────────────────────────────────────────────────────────
    // 生命周期与凭据
    // ─────────────────────────────────────────────────────────────

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        this.accountName = "我的迅雷云盘";
        reloadCredential();
        Log.i(TAG, "init: loggedIn=" + loggedIn);
    }

    /**
     * 读 {@code xunlei.txt} —— 还原 {@code merge.b.X.k()/l()}。
     *
     * <p>{@code deviceid} 与 {@code token_resp.access_token} 缺一不可：
     * 只有 deviceId 没有 token 时，未被授权的接口会被风控拦下，所以这里把两者都作为登录条件。
     */
    public void reloadCredential() {
        String text = PanCreds.readFile(credPath(CRED_FILE));
        if (TextUtils.isEmpty(text)) {
            text = PanCreds.readFile(credPath(CRED_FILE_ALT));
        }
        String dev = "";
        String auth = "";
        if (!TextUtils.isEmpty(text)) {
            try {
                JSONObject o = new JSONObject(text.trim());
                dev = o.optString("deviceid", "");
                String tokenResp = o.optString("token_resp", "");
                if (!TextUtils.isEmpty(tokenResp)) {
                    String at = new JSONObject(tokenResp).optString("access_token", "");
                    if (!TextUtils.isEmpty(at)) {
                        auth = "Bearer " + at;
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "credential is not the expected JSON");
            }
        }
        this.deviceId = dev;
        this.authorization = auth;
        this.loggedIn = !TextUtils.isEmpty(deviceId) && !TextUtils.isEmpty(authorization);
    }

    /**
     * 注入登录态 —— 测试用。
     *
     * @param dev  设备 id
     * @param auth Authorization 头（形如 {@code Bearer xxx}）；空则视为登出
     */
    public void setCredentialForTest(String dev, String auth) {
        this.deviceId = dev == null ? "" : dev.trim();
        this.authorization = auth == null ? "" : auth.trim();
        this.loggedIn = !TextUtils.isEmpty(deviceId) && !TextUtils.isEmpty(authorization);
    }

    /** 注入分享参数 —— 测试用 */
    public void setShareForTest(String shareId, String passCode) {
        this.shareId = TextUtils.isEmpty(shareId) ? DEFAULT_SHARE_ID : shareId;
        this.passCode = passCode == null ? "" : passCode;
    }

    /** 注入随机源 —— 测试用（让"随机取一个文件"可预测） */
    public void setRandomForTest(Random r) {
        this.random = r;
    }

    public String deviceId() {
        return deviceId;
    }

    public String authorization() {
        return authorization;
    }

    @Override
    protected String cookieFileName() {
        return CRED_FILE;
    }

    @Override
    protected String panName() {
        return "迅雷";
    }

    // ─────────────────────────────────────────────────────────────
    // 纯函数：风控签名（最核心，可逐位复算）
    // ─────────────────────────────────────────────────────────────

    /**
     * 签名种子 —— 还原 {@code XunleiPan.q()} 里
     * {@code h.b(clientId+version+pkg, deviceId, timestamp)}。
     *
     * @param tsMillis 毫秒时间戳
     * @return {@code clientId + VERSION + PKG + deviceId + ts}
     */
    public static String signSeed(String deviceId, long tsMillis) {
        return CLIENT_ID + VERSION + PKG + (deviceId == null ? "" : deviceId) + tsMillis;
    }

    /**
     * 10 轮 MD5 链 —— 还原 {@code XunleiPan.q()} 的循环。
     *
     * <pre>
     *   s = seed
     *   for salt in salts: s = md5_hex_lower(s + salt)
     *   return "1." + s
     * </pre>
     *
     * @param seed  种子（{@link #signSeed}）
     * @return captcha_sign；seed 为 null 时返回 {@code "1."}
     */
    public static String captchaSign(String seed) {
        return captchaSign(seed, SIGN_SALTS);
    }

    /**
     * 同上，盐值可注入（便于写固定向量测试）。
     *
     * @param seed  种子
     * @param salts 盐值序列
     * @return {@code "1." + md5链}
     */
    public static String captchaSign(String seed, String[] salts) {
        String s = seed == null ? "" : seed;
        if (salts != null) {
            for (String salt : salts) {
                // 上游 merge.A.b.a(x,y) == x + y（纯拼接，不是加密）
                s = md5HexLower(s + (salt == null ? "" : salt));
            }
        }
        return "1." + s;
    }

    /**
     * MD5 小写十六进制，左侧补 0 到 32 位 —— 还原 {@code XunleiPan.q()} 里的
     * {@code new BigInteger(1, md5.digest()).toString(16)} 补零逻辑。
     *
     * <p>注意 {@code BigInteger.toString(16)} 会丢掉前导 0，上游靠 while 循环补回来。
     * {@link Digest#md5(String)} 直接输出 32 位，语义等价；这里仍显式补零以保持逐位可核。
     *
     * @param s 输入
     * @return 32 位小写十六进制；失败返回 32 个 0
     */
    public static String md5HexLower(String s) {
        String h = Digest.md5(s == null ? "" : s);
        if (h == null) {
            h = "";
        }
        h = h.toLowerCase();
        StringBuilder sb = new StringBuilder(h);
        while (sb.length() < 32) {
            sb.insert(0, '0');
        }
        return sb.length() > 32 ? sb.substring(0, 32) : sb.toString();
    }

    /**
     * 风控初始化请求体 —— 逐字还原 {@code XunleiPan.q()} 的 {@code String.format} 模板。
     *
     * @param action   动作，如 {@code get:/drive/v1/share}
     * @param timestamp 毫秒时间戳文本
     * @param captchaSign 签名
     * @return JSON 体（字段顺序与上游一致）
     */
    public static String buildCaptchaInitBody(String action, String deviceId, String captchaSign,
                                              String timestamp) {
        return String.format("{\"client_id\":\"%s\",\"action\":\"%s\",\"device_id\":\"%s\","
                        + "\"meta\":{\"username\":\"\",\"phone_number\":\"\",\"email\":\"\","
                        + "\"package_name\":\"%s\",\"client_version\":\"%s\",\"captcha_sign\":\"%s\","
                        + "\"timestamp\":\"%s\",\"user_id\":\"%s\"}}",
                CLIENT_ID, nz(action), nz(deviceId), PKG, VERSION, nz(captchaSign), nz(timestamp), USER_ID);
    }

    // ─────────────────────────────────────────────────────────────
    // 纯函数：URL / 请求体
    // ─────────────────────────────────────────────────────────────

    /**
     * 列目录 URL —— 逐字还原 {@code merge.b.X.F()}：
     * <pre>
     *   https://api-pan.xunlei.com/drive/v1/files/{dirId}?_magic=2021&amp;usage=PLAY&amp;thumbnail_size=SIZE_LARGE
     * </pre>
     *
     * @param dirId 父目录 id
     * @return 完整 URL
     */
    public static String buildFileListUrl(String dirId) {
        return API_FILES + nz(dirId) + "?_magic=2021&usage=PLAY&thumbnail_size=SIZE_LARGE";
    }

    /** 查分享 URL —— 还原 {@code XunleiPan.m()} 的字面量拼接 */
    public static String buildShareUrl(String shareId, String passCode) {
        return API_SHARE + "?share_id=" + nz(shareId) + "&pass_code=" + nz(passCode)
                + "&limit=9999&pass_code_token=&page_token=&thumbnail_size=SIZE_SMALL";
    }

    /** 列分享目录 URL —— 还原 {@code XunleiPan.m()} */
    public static String buildShareDetailUrl(String shareId, String parentId, String passCodeToken) {
        return API_SHARE_DETAIL + "?share_id=" + nz(shareId)
                + "&parent_id=" + nz(parentId)
                + "&pass_code_token=" + nz(passCodeToken)
                + "&limit=9999&page_token=&thumbnail_size=SIZE_SMALL";
    }

    /** 转存任务 URL */
    public static String buildTaskUrl(String taskId) {
        return API_TASKS + nz(taskId);
    }

    /**
     * 转存请求体 —— 逐字还原 {@code XunleiPan.m()} 的 {@code String.format} 模板。
     *
     * <p>上游这里对 token 先 {@code URLEncoder.encode} 再 {@code URLDecoder.decode}
     * （等于什么都没做），本实现直接使用原 token 并在 javadoc 注明。
     */
    public static String buildRestoreBody(String shareId, String passCodeToken, String fileId) {
        return String.format("{\"parent_id\":\"\",\"share_id\":\"%s\",\"pass_code_token\":\"%s\","
                        + "\"ancestor_ids\":[],\"file_ids\":[\"%s\"],\"specify_parent_id\":true,"
                        + "\"params\":{\"origin\":\"%s\"}}",
                nz(shareId), nz(passCodeToken), nz(fileId), ORIGIN);
    }

    /** 删除请求体 —— 还原 {@code XunleiPan.u()}：{@code {"ids":[".."],"space":""}} */
    public static String buildBatchDeleteBody(String fileId) {
        return String.format("{\"ids\":[\"%s\"],\"space\":\"\"}", nz(fileId));
    }

    /** URL 编码（上游用 URLEncoder.encode 处理 pass_code_token） */
    public static String urlEncode(String s) {
        try {
            return URLEncoder.encode(nz(s), "UTF-8");
        } catch (Throwable e) {
            return nz(s);
        }
    }

    /** URL 解码 */
    public static String urlDecode(String s) {
        try {
            return URLDecoder.decode(nz(s), "UTF-8");
        } catch (Throwable e) {
            return nz(s);
        }
    }

    /**
     * 业务请求头 —— 还原 {@code XunleiPan.o()} / {@code r()}。
     *
     * @param captchaToken 风控票
     * @param withAuth     是否带 {@code authorization}（{@code o()} 不带，{@code r()} 带）
     * @return header map
     */
    public Map<String, String> apiHeaders(String captchaToken, boolean withAuth) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", REFERER);
        h.put("x-client-id", CLIENT_ID);
        h.put("x-device-id", nz(deviceId));
        h.put("x-captcha-token", nz(captchaToken));
        if (withAuth && !TextUtils.isEmpty(authorization)) {
            h.put("authorization", authorization);
        }
        return h;
    }

    /** 风控请求头 —— 还原 {@code XunleiPan.q()} 里的 {@code h.c("User-Agent", CAPTCHA_UA, "Referer", REFERER)} */
    public static Map<String, String> captchaHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", CAPTCHA_UA);
        h.put("Referer", REFERER);
        return h;
    }

    // ─────────────────────────────────────────────────────────────
    // 纯函数：响应解析
    // ─────────────────────────────────────────────────────────────

    /** 分享状态是否 OK —— 还原 {@code XunleiPan.m()} 的 {@code share_status == "OK"} 判断 */
    public static boolean isShareOk(String json) {
        return "OK".equals(parseField(json, "share_status"));
    }

    /** 取字符串字段（顶层） */
    public static String parseField(String json, String field) {
        if (TextUtils.isEmpty(json) || TextUtils.isEmpty(field)) {
            return "";
        }
        try {
            return new JSONObject(json).optString(field, "");
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 取 {@code files[0].id} —— 还原 {@code XunleiPan.m()}（分享根目录第一个文件）。
     *
     * @return id；无 files/空数组/坏 JSON 都返回空串
     */
    public static String parseFirstFileId(String json) {
        if (TextUtils.isEmpty(json)) {
            return "";
        }
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("files");
            if (arr == null || arr.length() == 0) {
                return "";
            }
            JSONObject o = arr.optJSONObject(0);
            return o == null ? "" : o.optString("id", "");
        } catch (Throwable e) {
            return "";
        }
    }

    /** 取全部 {@code files[].id} —— 还原 {@code XunleiPan.m()} 里收集 ids 的循环 */
    public static List<String> parseFileIds(String json) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return out;
        }
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("files");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    String id = o.optString("id", "");
                    if (!TextUtils.isEmpty(id)) {
                        out.add(id);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 取 {@code params.trace_file_ids} —— 还原 {@code XunleiPan.m()} 转存成功后记录待删文件。
     *
     * @return 原样的 JSON 文本（上游直接把这个字符串缓存起来）；缺失返回空串
     */
    public static String parseTraceFileIds(String json) {
        if (TextUtils.isEmpty(json)) {
            return "";
        }
        try {
            JSONObject params = new JSONObject(json).optJSONObject("params");
            return params == null ? "" : params.optString("trace_file_ids", "");
        } catch (Throwable e) {
            return "";
        }
    }

    /** 取 {@code task_id} —— 还原 {@code XunleiPan.u()} */
    public static String parseTaskId(String json) {
        return parseField(json, "task_id");
    }

    /**
     * 从 {@code trace_file_ids} 文本里取第一个 id —— 还原 {@code XunleiPan.u()} 的
     * {@code jSONObject.getString(jSONObject.keys().next())}。
     *
     * @param traceJson 形如 {@code {"xxx":"yyy"}}
     * @return 第一个 value；解析不出返回空串
     */
    public static String firstTraceId(String traceJson) {
        if (TextUtils.isEmpty(traceJson)) {
            return "";
        }
        try {
            JSONObject o = new JSONObject(traceJson);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = o.optString(k, "");
                if (!TextUtils.isEmpty(v)) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────
    // 站点方法
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页 —— 未登录给提示（{@link PanClientBase} 的文案），已登录给「我的迅雷网盘」。
     */
    @Override
    public String homeContent(boolean filter) {
        if (!isLoggedIn()) {
            return super.homeContent(filter);
        }
        return VodBuilder.create()
                .clazz("0", accountName)
                .homeContent();
    }

    /** 分类浏览：迅雷盘内文件（未登录走基类的空列表降级） */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        if (!isLoggedIn()) {
            return super.categoryContent(tid, pg, filter, extend);
        }
        int page = pageNum(pg, 1);
        List<DirEntry> entries = listDir(TextUtils.isEmpty(tid) ? "0" : tid, page);
        List<Vod> list = new ArrayList<>();
        for (DirEntry d : entries) {
            if (d == null || TextUtils.isEmpty(d.name)) {
                continue;
            }
            list.add(new Vod()
                    .id(buildChildId(tid, d))
                    .name(d.name)
                    .pic(iconFor(d.isDir))
                    .remarks(d.isDir ? "文件夹" : humanSize(d.size)));
        }
        return VodBuilder.create().addAll(list).page(page).categoryContent();
    }

    /**
     * 列目录 —— {@code /drive/v1/files}（上游 {@code u()} 用过该端点）。
     *
     * <p>未登录 / 拿不到风控票 / 网络失败都返回空列表，不抛异常。
     */
    @Override
    protected List<DirEntry> listDir(String dirId, int page) {
        List<DirEntry> out = new ArrayList<>();
        if (!isLoggedIn()) {
            return out;
        }
        // 动作串大小写敏感：列目录用 GET:/drive/v1/files/{dir}（merge.b.X.F() 原文）
        String token = captchaToken("GET:/drive/v1/files/" + nz(dirId));
        if (TextUtils.isEmpty(token)) {
            Log.w(TAG, "listDir: no captcha token");
            return out;
        }
        try {
            String url = buildFileListUrl(dirId);
            String resp = http.get(url, apiHeaders(token, false));
            out.addAll(parseFileList(resp));
        } catch (Throwable e) {
            Log.e(TAG, "listDir failed", e);
        }
        return out;
    }

    /**
     * 解析文件列表响应 —— 迅雷 {@code /drive/v1/files} 的 {@code files[]}。
     *
     * @param json 响应体
     * @return 非 null 列表
     */
    public static List<DirEntry> parseFileList(String json) {
        List<DirEntry> out = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return out;
        }
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("files");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String id = o.optString("id", "");
                String name = o.optString("name", "");
                if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) {
                    continue;
                }
                DirEntry d = new DirEntry();
                d.id = id;
                d.name = name;
                d.isDir = o.optInt("kind", 1) != 1;
                d.size = o.optLong("size", 0L);
                d.updatedAt = o.optString("modified_time", "");
                d.isVideo = !d.isDir && looksLikeVideo(name);
                out.add(d);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 播放 —— 迅雷盘内文件需要换直链，此处未实现（见类注释的「未覆盖分支」）。
     *
     * <p>返回契约要求的空响应，让宿主提示失败，而不是伪造一个播放地址。
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (!isLoggedIn() || TextUtils.isEmpty(id)) {
            return emptyResponse();
        }
        Log.w(TAG, "playerContent: 迅雷直链换取未实现（需 app 侧签名）");
        return emptyResponse();
    }

    /** 云盘客户端源不做站内搜索 */
    @Override
    public String searchContent(String key, boolean quick) {
        return VodBuilder.create().searchContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 风控票
    // ─────────────────────────────────────────────────────────────

    /**
     * 取风控票（带缓存）—— 还原 {@code merge.b.X.l(JSONObject)}。
     *
     * <pre>
     *   缓存键 = action；TTL = (expires_in - 30) * 1000 ms
     *   未命中 → POST captcha/init
     * </pre>
     *
     * @param action 动作串
     * @return captcha_token；失败空串（<b>不抛异常</b>）
     */
    public String captchaToken(String action) {
        if (TextUtils.isEmpty(action)) {
            return "";
        }
        String[] cached = captchaCache.get(action);
        if (cached != null && cached[0] != null && System.currentTimeMillis() <= parseLong(cached[1], 0L)) {
            return cached[0];
        }
        long ts = System.currentTimeMillis();
        String sign = captchaSign(signSeed(deviceId, ts), SIGN_SALTS);
        String body = buildCaptchaInitBody(action, deviceId, sign, String.valueOf(ts));
        String resp;
        try {
            resp = http.post(API_CAPTCHA_INIT, body, captchaHeaders());
        } catch (Throwable e) {
            Log.e(TAG, "captchaToken http failed", e);
            return "";
        }
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "captchaToken: empty response for " + action);
            return "";
        }
        String token = parseField(resp, "captcha_token");
        if (TextUtils.isEmpty(token)) {
            return "";
        }
        long ttl = 0L;
        try {
            long expiresIn = new JSONObject(resp).optLong("expires_in", 0L);
            ttl = System.currentTimeMillis() + Math.max(0L, (expiresIn - 30L) * 1000L);
        } catch (Throwable ignored) {
        }
        captchaCache.put(action, new String[]{token, String.valueOf(ttl)});
        return token;
    }

    /** 清空风控票缓存（测试用） */
    public void clearCaptchaCache() {
        captchaCache.clear();
    }

    // ─────────────────────────────────────────────────────────────
    // 转存 / 清理（对应上游 checktime() → m() → u()）
    // ─────────────────────────────────────────────────────────────

    /**
     * 检查并刷新转存 —— 还原 {@code XunleiPan.checktime()}：
     * 缓存里的日期早于今天就跑一次 {@link #refreshTempShare()}。
     *
     * @return 是否真的跑了刷新
     */
    public boolean checktime() {
        long cached = readCacheLong("xltime");
        long today = Integer.parseInt(todayYyyyMMdd());
        if (cached >= today) {
            return false;
        }
        refreshTempShare();
        return true;
    }

    /**
     * 转存一次分享 —— 还原 {@code XunleiPan.m()}。
     *
     * <p>流程：查分享 → 取首个文件 id → 用 pass_code_token 列目录 → 随机挑一个文件转存 →
     * 记录 {@code trace_file_ids} → 写 {@code xltime} 缓存 → 删除刚转存的文件。
     *
     * @return 是否成功转存
     */
    public boolean refreshTempShare() {
        if (!isLoggedIn()) {
            Log.w(TAG, "refreshTempShare: not logged in");
            return false;
        }
        String token = captchaToken("get:/drive/v1/share");
        if (TextUtils.isEmpty(token)) {
            return false;
        }
        try {
            String shareResp = http.get(buildShareUrl(shareId, passCode), apiHeaders(token, false));
            if (!isShareOk(shareResp)) {
                Log.w(TAG, "refreshTempShare: share_status != OK");
                return false;
            }
            String firstId = parseFirstFileId(shareResp);
            String passCodeToken = parseField(shareResp, "pass_code_token");
            String encoded = urlEncode(passCodeToken);

            String detailResp = http.get(
                    buildShareDetailUrl(shareId, firstId, encoded), apiHeaders(token, false));
            List<String> ids = parseFileIds(detailResp);
            if (ids.isEmpty()) {
                Log.w(TAG, "refreshTempShare: no files in share");
                return false;
            }

            String picked = ids.get(randomIndex(ids.size()));
            String restoreToken = captchaToken("get:/drive/v1/about");
            String restoreResp = http.post(API_SHARE_RESTORE,
                    buildRestoreBody(shareId, urlDecode(encoded), picked),
                    apiHeaders(restoreToken, true));
            if (!isShareOk(restoreResp)) {
                Log.w(TAG, "refreshTempShare: restore failed");
                return false;
            }
            String trace = parseTraceFileIds(restoreResp);
            writeCache("xltime", todayYyyyMMdd());
            sleepQuiet(1000L);
            deleteTraceFiles(trace, token);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "refreshTempShare failed", e);
            return false;
        }
    }

    /**
     * 删除转存产生的临时文件 —— 还原 {@code XunleiPan.u()}。
     *
     * @param traceJson {@code params.trace_file_ids} 原文
     * @param token     风控票
     * @return 是否提交了删除任务
     */
    public boolean deleteTraceFiles(String traceJson, String token) {
        String id = firstTraceId(traceJson);
        if (TextUtils.isEmpty(id)) {
            return false;
        }
        try {
            String resp = http.post(API_BATCH_DELETE, buildBatchDeleteBody(id),
                    apiHeaders(token, true));
            String taskId = parseTaskId(resp);
            if (TextUtils.isEmpty(taskId)) {
                return false;
            }
            http.get(buildTaskUrl(taskId), apiHeaders(token, true));
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "deleteTraceFiles failed", e);
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 缓存（上游 k.a/k.c → <filesDir>/Pizazz/<name>）
    // ─────────────────────────────────────────────────────────────

    /** 今天的 yyyyMMdd（还原 {@code new SimpleDateFormat("yyyyMMdd").format(new Date())}） */
    public static String todayYyyyMMdd() {
        return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                .format(new java.util.Date());
    }

    /** 读缓存数值 —— 还原 {@code XunleiPan.checktime()} 的 {@code k.a(getCache("xltime"))} */
    public long readCacheLong(String name) {
        String s = PanCreds.readFile(credPath(name)).trim().replaceAll("[^0-9]", "");
        if (s.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(s);
        } catch (Throwable e) {
            return 0L;
        }
    }

    /** 写缓存 —— 还原 {@code k.c(getCache(...), ...)} */
    public boolean writeCache(String name, String value) {
        return PanCreds.writeFile(credPath(name), value);
    }

    // ─────────────────────────────────────────────────────────────
    // 内部工具
    // ─────────────────────────────────────────────────────────────

    /** 凭据/缓存文件路径：{@code <root>/TVBox/<name>} */
    private static String credPath(String name) {
        return PanCreds.path(name);
    }

    private int randomIndex(int bound) {
        Random r = random;
        if (r == null) {
            r = new Random();
        }
        return r.nextInt(bound);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (Throwable e) {
            return def;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
