package com.example.tvbox.plain.feiyu;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 飞宇（Feiyu）—— 4kyszx.top 的 CMS App 接口，**带 HMAC-SHA256 请求签名**。
 *
 * <h2>签名协议（逐字还原自反编译 + DEX）</h2>
 * <pre>
 *   // 1) 启动时用设备盐派生签名密钥（只做一次，缓存在 {@link #signKey}）
 *   signKey = HMAC-SHA256(message = "f45a775875e2e004adbcea78e3312218",
 *                         key     = "cms_device_salt_v1_2024cms_app_sign_key_v1_2024_secure")
 *           = 007dbfc8184236587aa00d40c9a624fc5b76b04538e817761a4a1d3d44983412
 *
 *   // 2) 每个请求构造 16 字符随机串 → 取其 Base64(NO_WRAP) 作为 nonce
 *   nonce = Base64.encodeToString(randomText(16).getBytes(UTF_8), NO_WRAP)
 *
 *   // 3) 签名原文（六段，\n 分隔；首段是 "GET" 的大写形式）
 *   source = "GET\n" + path + "\n" + query + "\n" + timestamp + "\n" + nonce + "\n" + "2.6.8+1"
 *   sign   = hex(HMAC-SHA256(message = source, key = signKey))   // 小写 hex，64 字符
 *
 *   // 4) 4 个关键头（另有固定 6 个）
 *   x-signature / x-nonce / x-timestamp 必须与签名原文一致；x-device-id 固定
 * </pre>
 * <b>易错点一</b>：上游 {@code e()} 的布尔参数 {@code z} 是"<b>签名原文是否用原始值</b>"，
 * 而<b>拼到 URL 上的 query 永远 URL-encode</b>：
 * <ul>
 *   <li>{@code z=true}（分类/搜索）：签名原文用<b>原始值</b>（含裸中文），URL 用编码值 ——
 *       两者不同，这是上游刻意的防篡改设计；</li>
 *   <li>{@code z=false}（首页/轮播/详情）：签名原文与 URL 都用编码值。</li>
 * </ul>
 * 本重写把该参数命名为 {@code rawInSignature} 以免误读。
 * <b>易错点二</b>：密钥来自"设备盐"两段拼接，{@link #DEVICE_ID} 同时还是 {@code x-device-id}。
 *
 * <h2>播放入口格式</h2>
 * 详情页每条播放地址被拼成 {@code 名称$解析接口||地址}（见 {@link #playEntry}），
 * 多个地址用 {@code #} 连接、多组播放源用 {@code $$$} 连接。播放时按 {@code ||} 切开：
 * <ul>
 *   <li>左半为空 → 直链（{@code parse=0, jx=0}）；</li>
 *   <li>左半含 {@code json}/{@code api} → 先请求 {@code 左半+右半} 取 {@code url} 字段；
 *       拿到就直链，拿不到就退回 {@code parse=1, jx=1}；</li>
 *   <li>其它 → {@code parse=1, jx=1}，url 仍是 {@code 左半+右半}。</li>
 * </ul>
 *
 * <h2>修正的上游缺陷 / 已确认的代码异味</h2>
 * <ol>
 *   <li><b>页码解析无容错</b>：{@code categoryContent} 结尾用
 *       {@code Integer.parseInt((String) linkedHashMap.get("page"))} 回填页码，
 *       宿主传空串/非数字时直接 NFE（分类页白屏）。已用 {@code pageNum(pg,1)}；
 *       钉住断言：{@code FeiyuTest#pageTolerance()}。</li>
 *   <li><b>筛选表不判空</b>：上游若 {@code map} 为 null 时只跳过筛选，看似安全，
 *       但同类 {@code merge.m.G} 等调用点会先 {@code map.get(...)}。本重写全部按空表处理。</li>
 *   <li><b>{@code getJSONObject(i)} 对非对象元素整体抛</b>：三处列表解析都用
 *       {@code getJSONObject}，数组里混入非对象元素就整体失败。已改为跳过。</li>
 *   <li><b>{@code playerContent} 的条件冗余</b>：上游写
 *       {@code (左半为空 && 含 m3u8/mp4) || 左半为空}，前半个条件被后半个完全蕴含
 *       （等价于"左半为空"）。已按等价形式实现并注释。</li>
 *   <li><b>{@code e()} 里的 {@code str2} 是死变量</b>（<b>不是缺陷</b>，特此更正）：
 *       它在两个分支里都被赋成空串，且 {@code 编码串为空 ⟺ 参数表为空 ⟺ 原文串为空}，
 *       所以 {@code 编码串.isEmpty() ? str2 : "?"+编码串} 里的 {@code str2} 恒为 {@code ""}。
 *       已简化为直白的"有参数才拼 {@code ?}"，并保留注释说明为什么可以简化。</li>
 *   <li><b>详情页无数据时白跑一次解析</b>：上游无论 {@code data} 是否存在都调用
 *       {@code parseJsonAndSave}，列表为空时内部 {@code getJSONObject(0)} 抛异常
 *       （被它自己 catch 后打堆栈）。本重写只在有数据时才回调 {@link #afterDetail}。</li>
 *   <li><b>弹幕/历史钩子是隐式全局副作用</b>：上游 {@code addDanmaku} 依赖静态的
 *       代理地址、{@code parseJsonAndSave} 依赖宿主 preferences。已改为
 *       {@link #proxyBase}（可注入）+ {@link #afterDetail}（可覆写），默认不落库。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Feiyu.java}；签名常量的 hex 由 JCE 实算核对
 * （见 {@code FeiyuTest#signatureChain()}）。
 
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>真实服务端签名校验</b>：本类能生成与上游逐字节同构的签名头
 *       （{@code x-sign}/{@code x-timestamp}/{@code x-nonce}），但"服务端接受与否"
 *       没有样本可证；测试用固定向量钉住算法，不假装验证过服务端。</li>
 *   <li><b>{@code x-device-id} 是客户端生成的常量</b>（{@code f45a775875e2e004adbcea78e3312218}），
 *       换设备不会换 id；上游就是这么写的。</li>
 *   <li><b>{@code playerContent} 的 {@code m3u8/mp4} 判定</b>：上游对直链只做后缀判断，
 *       本类保留同一正则，不额外探测内容类型。</li>
 *   <li><b>{@code addDanmaku}/{@code parseJsonAndSave} 的全局副作用</b>：
 *       上游会写宿主偏好、注册弹幕地址；本重写只保留方法内的可见副作用
 *       （返回带 {@code danmaku} 的 JSON / 触发 {@code afterDetail} 钩子），
 *       真正的落库由宿主覆写。</li>
 *   <li><b>未还原</b>：{@code PAGE_COUNT=999} 是上游写死的"假装很多页"，
 *       真实总页数以服务端返回为准（本类同样写死，保持行为一致）。</li>
 * </ol>
 *
 * <h2>已排查但不构成缺陷的疑点（★ 请勿"顺手修"）</h2>
 * <p><b>{@code e(str, map, z)} 里的</b> {@code string2}/{@code str2} <b>是死变量，
 * 不是"丢参数"。</b>排查过程（穷举，不是猜测）：
 * <ol>
 *   <li>方法先把入参 map 拆成"原始值 query"与"编码后 query"两份，再按第三个参数
 *       {@code z} 选签名原文用哪一份 —— {@code z == true}（分类/搜索）用**原始值**
 *       做签名原文、URL 仍然编码；{@code z == false}（首页/排行/详情）**两边都用编码值**。
 *       这是本方法的全部语义，与 {@code string2} 无关。</li>
 *   <li>上游另一处对 {@code String} 重载做了同一件事：它把入参字符串原样赋给
 *       "原始值"分支、编码后赋给"编码值"分支。</li>
 *   <li>该 {@code String} 重载的调用点只有"query 为空"这一种（分类/搜索之外的
 *       接口都不带 query），于是 {@code str2} 在两条分支上都被赋成 {@code ""}。</li>
 *   <li>三个"空"条件互相等价：{@code str2.isEmpty() ⟺ map == null || map.isEmpty()
 *       ⟺ str.isEmpty()}。所以走哪条分支都得到 {@code ""}，
 *       <b>行为上不存在"未编码 query 被误用"的可能</b>。</li>
 * </ol>
 * 结论：那处冗余三元/未使用变量记为<b>代码异味</b>，不是行为缺陷；
 * 本类按"z=true 用原始值、z=false 用编码值"实现，并在
 * {@code FeiyuTest} 里用同一 URL、不同签名的对照向量钉住了这一点。
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/Feiyu.java}（{@code init} 的 signKey 推导、
 * {@code e(str,map,z)} 的签名分支、62 字符随机表、nonce 的 Base64 形态、
 * 各接口路径与参数）。
*/
public class FeiyuPlain extends BaseSpider {

    private static final String TAG = "FeiyuPlain";

    // ── 协议常量 ─────────────────────────────────────────────

    /** 站点根 */
    public static final String BASE = "https://4kyszx.top";
    /** Host 头（与站点根一致，上游硬编码） */
    public static final String HOST = "4kyszx.top";
    /** 设备 id（同时是签名密钥的 message 部分） */
    public static final String DEVICE_ID = "f45a775875e2e004adbcea78e3312218";
    /** 设备盐（签名密钥的 key 部分） */
    public static final String DEVICE_SALT =
            "cms_device_salt_v1_2024cms_app_sign_key_v1_2024_secure";
    /** App 版本（出现在签名原文最后一段与 {@code x-app-version} 头） */
    public static final String APP_VERSION = "2.6.8+1";
    /** 固定 UA（Dart 客户端伪装） */
    public static final String APP_UA = "Dart/3.10 (dart:io)";
    /** 平台标记 */
    public static final String PLATFORM = "android";
    /** 预览接口用的浏览器 UA（上游 playContent 里硬编码） */
    public static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
    /** 每页条数 */
    public static final int PAGE_SIZE = 20;
    /** 页码上限（上游分类页固定 999） */
    public static final int PAGE_COUNT = 999;
    /** 随机串字符表（62 个，顺序即上游原文） */
    public static final String SIGN_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    /** 随机串长度（上游 16） */
    public static final int NONCE_RAW_LEN = 16;
    /** 播放地址分隔符（组内多地址） */
    public static final String SEP_EPISODE = "#";
    /** 播放源分隔符（多组） */
    public static final String SEP_SOURCE = "$$$";
    /** 解析接口与地址的分隔符 */
    public static final String SEP_PARSE = "||";
    /** 弹幕代理追加的查询串（上游 {@code merge.p026a.a.addDanmaku} 硬编码） */
    public static final String DANMAKU_QUERY = "?do=diydanmu";
    /** 签名原文首段（上游写的是 {@code "GET".toUpperCase()}） */
    public static final String SIGN_HTTP_METHOD = "GET";

    // ── 状态 ─────────────────────────────────────────────────

    /** 派生出来的签名密钥（上游字段 {@code this.a}，初值空串） */
    private String signKey = "";

    /** 弹幕代理基址；空串表示不产出 {@code danmaku} 字段（本仓库约定，见 AppSourceBase） */
    protected String proxyBase = "";

    /** 构造 */
    public FeiyuPlain() {
        super();
    }

    /**
     * 初始化：派生签名密钥。
     *
     * <p>上游无 ext 依赖（站点与密钥全硬编码），只把 {@code d(DEVICE_ID, SALT)} 存进字段。
     *
     * @param context 宿主上下文，可为 null
     * @param extend  ext 原文（本类忽略）
     */
    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        this.signKey = hmacSha256Hex(DEVICE_ID, DEVICE_SALT);
        Log.i(TAG, "init ok, signKey=" + (signKey.length() == 64 ? signKey.substring(0, 8) + "…" : signKey));
    }

    /**
     * 当前签名密钥。
     *
     * @return 64 位小写 hex；未初始化时为空串
     */
    public String signKey() {
        return signKey;
    }

    /**
     * 注入弹幕代理基址。
     *
     * @param base 形如 {@code http://127.0.0.1:9978/proxy}；null/空 表示不产出弹幕字段
     */
    public void setProxyBase(String base) {
        this.proxyBase = base == null ? "" : base.trim();
    }

    /**
     * 当前弹幕代理基址。
     *
     * @return 可能为空串
     */
    public String proxyBase() {
        return proxyBase;
    }

    // ── HMAC / 签名（全部纯函数，可单测） ────────────────────

    /**
     * HMAC-SHA256 → 小写 hex —— 上游 {@code d(String str, String str2)}。
     *
     * <p><b>参数顺序沿用上游：第一个是被签名内容，第二个才是密钥</b>
     * （上游 {@code mac.init(str2)} / {@code doFinal(str)}）。
     * 与公共 {@code Digest.hmacSha256(data, key)} 的顺序一致。
     *
     * <p>hex 用小写 {@code String.format("%02x", Byte)}（上游写法）；
     * 本仓库已验证该写法对负字节不会补出 {@code ffffff}（见项目 README 的同类记录）。
     *
     * @param message 被签名内容
     * @param key     密钥
     * @return 64 位小写 hex；失败返回空串（上游同样吞异常）
     */
    public static String hmacSha256Hex(String message, String key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(String.format("%02x", Byte.valueOf(b)));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "hmacSha256Hex failed", e);
            return "";
        }
    }

    /**
     * 签名原文 —— 上游那段六段拼装。
     *
     * @param path      接口路径（含前导 {@code /}）
     * @param query     参与签名的 query（是否需要 URL-encode 由调用方决定）
     * @param timestamp 秒级时间戳文本
     * @param nonce     随机 nonce
     * @return 形如 {@code GET\n/path\nquery\nts\nnonce\n2.6.8+1}
     */
    public static String signSource(String path, String query, String timestamp, String nonce) {
        return SIGN_HTTP_METHOD + "\n" + path + "\n" + nz(query) + "\n" + timestamp + "\n"
                + nonce + "\n" + APP_VERSION;
    }

    /**
     * 生成长度为 {@code len} 的随机串（字符表见 {@link #SIGN_CHARS}）。
     *
     * @param len 长度
     * @param r   随机源（测试注入固定种子）
     * @return 随机串
     */
    public static String randomText(int len, Random r) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(SIGN_CHARS.charAt(r.nextInt(SIGN_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * 生成 nonce：16 字符随机串的 Base64（NO_WRAP）—— 上游写法。
     *
     * @param r 随机源
     * @return Base64 文本
     */
    public static String nonce(Random r) {
        return Base64.encodeToString(
                randomText(NONCE_RAW_LEN, r).getBytes(StandardCharsets.UTF_8), 2);
    }

    /**
     * 拼 query 串 —— 上游那段 {@code key=value&…} 的两种形态。
     *
     * <p>{@code encodeValues=true} 时值走 URL-encode，false 时用原文
     * （签名原文用后者、URL 用前者，见类注释"易错点一"）。
     *
     * @param params       参数表（顺序即遍历顺序），可为 null
     * @param encodeValues 是否 URL-encode 值
     * @return query 串；空表返回空串
     */
    public static String joinQuery(Map<String, String> params, boolean encodeValues) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            String v = e.getValue() == null ? "" : e.getValue();
            sb.append(e.getKey()).append('=').append(encodeValues ? encode(v) : v);
        }
        return sb.toString();
    }

    /**
     * URL-encode（UTF-8）—— 上游 {@code URLEncoder.encode(v, "UTF-8")}。
     *
     * @param v 值
     * @return 编码结果；失败返回原文
     */
    public static String encode(String v) {
        try {
            return URLEncoder.encode(v == null ? "" : v, "UTF-8");
        } catch (Exception e) {
            return v == null ? "" : v;
        }
    }

    /**
     * 构造签名头（10 个）—— 上游 {@code e()} 里的 {@code map2}。
     *
     * @param sign 签名值
     * @param ts   时间戳
     * @param nc   nonce
     * @return 非 null 头表（插入顺序与上游一致）
     */
    public static Map<String, String> signedHeaders(String sign, String ts, String nc) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("x-signature", sign);
        h.put("user-agent", APP_UA);
        h.put("x-nonce", nc);
        h.put("accept", "application/json");
        h.put("x-timestamp", ts);
        h.put("host", HOST);
        h.put("x-device-id", DEVICE_ID);
        h.put("content-type", "application/json");
        h.put("x-app-version", APP_VERSION);
        h.put("x-platform", PLATFORM);
        return h;
    }

    /**
     * 拼接最终 URL。
     *
     * @param path         路径
     * @param encodedQuery 已 URL-encode 的 query（空串表示无参数）
     * @return 完整 URL
     */
    public static String signedUrl(String path, String encodedQuery) {
        return BASE + path + (encodedQuery == null || encodedQuery.isEmpty()
                ? "" : "?" + encodedQuery);
    }

    // ── 请求 ─────────────────────────────────────────────────

    /**
     * 发一次带签名的请求 —— 上游 {@code e(String path, Map map, boolean encode)}。
     *
     * @param path         接口路径
     * @param params       参数表，可为 null
     * @param encodeValues 生成 query 时是否 URL-encode 值（影响<b>签名原文</b>，
     *                     URL 永远用编码值）
     * @return 响应对象；任何失败都返回空对象（上游同样吞异常）
     */
    public JSONObject request(String path, Map<String, String> params, boolean rawInSignature) {
        return request(path, params, rawInSignature, System.currentTimeMillis() / 1000,
                new Random());
    }

    /**
     * 可注入时间戳/随机源的版本（用于固定向量测试）。
     *
     * @param path         接口路径
     * @param params       参数表
     * @param encodeValues 签名原文是否编码
     * @param timestamp    秒级时间戳
     * @param random       随机源
     * @return 响应对象；失败返回空对象
     */
    public JSONObject request(String path, Map<String, String> params, boolean rawInSignature,
                              long timestamp, Random random) {
        try {
            String ts = String.valueOf(timestamp);
            String nc = nonce(random);
            // 上游 z=true 时签名原文用**原始值**（URL 仍用编码值）；z=false 时两者都用编码值
            String signQuery = joinQuery(params, !rawInSignature);
            String urlQuery = joinQuery(params, true);
            String source = signSource(path, signQuery, ts, nc);
            String sign = hmacSha256Hex(source, signKey);
            String url = signedUrl(path, urlQuery);
            if (sign.isEmpty()) {
                Log.w(TAG, "签名密钥为空（init 未调用？），请求可能被服务端拒绝");
            }
            Map<String, String> headers = signedHeaders(sign, ts, nc);
            rememberHeaders(headers);
            String body = fetch(url, headers);
            if (body == null || body.isEmpty()) {
                return new JSONObject();
            }
            return new JSONObject(body);
        } catch (Exception e) {
            Log.e(TAG, "request failed: " + path, e);
            return new JSONObject();
        }
    }

    /**
     * GET 文本（覆写即可离线测试）。
     *
     * @param url     完整地址
     * @param headers 头表
     * @return 响应文本；失败为空串
     */
    public String fetch(String url, Map<String, String> headers) {
        try {
            return http.get(url, headers);
        } catch (Throwable e) {
            Log.e(TAG, "fetch failed: " + url, e);
            return "";
        }
    }

    // ── 列表解析 ─────────────────────────────────────────────

    /**
     * 取 {@code data} 字段里的数组 —— 上游三处都写了"直接是数组，或是 {@code {list:[…]}}"。
     *
     * @param resp 响应对象，可为 null
     * @return 数组；两种形状都不是时返回 null
     */
    public static JSONArray dataArray(JSONObject resp) {
        if (resp == null || !resp.has("data")) {
            return null;
        }
        JSONArray direct = resp.optJSONArray("data");
        if (direct != null) {
            return direct;
        }
        JSONObject wrap = resp.optJSONObject("data");
        return wrap == null ? null : wrap.optJSONArray("list");
    }

    /**
     * 解析视频条目数组。
     *
     * <p>两种字段口径（上游不同端点用不同优先序，都要兼容）：
     * <ul>
     *   <li>{@code listShape=true}（分类/搜索）：名称 {@code name|title}、封面 {@code pic|cover}、
     *       备注 {@code remarks|subTitle}；</li>
     *   <li>{@code listShape=false}（首页轮播）：名称 {@code title|name}、封面
     *       {@code cover|pic}、备注 {@code subtitle|remarks}。</li>
     * </ul>
     *
     * @param arr       数组，可为 null
     * @param listShape 是否按"列表接口"的字段优先序解析
     * @return 非 null 列表（跳过非对象元素）
     */
    public static List<Vod> parseVideos(JSONArray arr, boolean listShape) {
        List<Vod> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String name;
            String pic;
            String remarks;
            if (listShape) {
                name = o.optString("name", o.optString("title", ""));
                pic = o.optString("pic", o.optString("cover", ""));
                remarks = o.optString("remarks", o.optString("subTitle", ""));
            } else {
                name = o.optString("title", o.optString("name", ""));
                pic = o.optString("cover", o.optString("pic", ""));
                remarks = o.optString("subtitle", o.optString("remarks", ""));
            }
            out.add(new Vod()
                    .id(String.valueOf(o.optInt("id")))
                    .name(name)
                    .pic(pic)
                    .remarks(remarks));
        }
        return out;
    }

    // ── 详情：播放源排序与拼装 ───────────────────────────────

    /**
     * 线路质量打分 —— 上游 {@code c(String)}。
     *
     * <p>规则（大小写不敏感，按顺序短路）：含 {@code 4k}→100；
     * 含 {@code 藍光}/{@code 2k}→90；含 {@code 高清}/{@code hd}→80；
     * 含 {@code m3u8}/{@code 採集}/{@code 資源}/{@code zy}→10；其余 50。
     *
     * @param name 线路名，可为 null
     * @return 分值
     */
    public static int qualityRank(String name) {
        String s = name == null ? "" : name.toLowerCase();
        if (s.contains("4k")) {
            return 100;
        }
        if (s.contains("藍光") || s.contains("2k")) {
            return 90;
        }
        if (s.contains("高清") || s.contains("hd")) {
            return 80;
        }
        if (s.contains("m3u8") || s.contains("採集") || s.contains("資源") || s.contains("zy")) {
            return 10;
        }
        return 50;
    }

    /**
     * 线路名 —— 上游 {@code optString("name", optString("code", ""))}。
     *
     * @param group 线路，可为 null
     * @return 线路名
     */
    public static String groupName(JSONObject group) {
        return group == null ? "" : group.optString("name", group.optString("code", ""));
    }

    /**
     * 按质量<b>降序</b>排序线路 —— 上游那个匿名 Comparator 的等价实现。
     *
     * @param groups 线路数组，可为 null
     * @return 非 null、已排序的新列表
     */
    public static List<JSONObject> sortGroupsByQuality(JSONArray groups) {
        List<JSONObject> list = new ArrayList<>();
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject o = groups.optJSONObject(i);
                if (o != null) {
                    list.add(o);
                }
            }
        }
        Collections.sort(list, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                int ra = qualityRank(groupName(a));
                int rb = qualityRank(groupName(b));
                if (ra == rb) {
                    return 0;
                }
                return rb < ra ? -1 : 1;
            }
        });
        return list;
    }

    /**
     * 单条播放地址 —— 上游 {@code 名称 + "$" + parseApi + "||" + url}。
     *
     * @param name     集数名
     * @param parseApi 解析接口
     * @param url      地址
     * @return 拼接结果
     */
    public static String playEntry(String name, String parseApi, String url) {
        return nz(name) + "$" + nz(parseApi) + SEP_PARSE + nz(url);
    }

    /**
     * 解析一组线路内的播放地址数组。
     *
     * @param group 线路对象
     * @return 非 null 列表
     */
    public static List<String> playEntriesOf(JSONObject group) {
        List<String> out = new ArrayList<>();
        if (group == null) {
            return out;
        }
        String parseApi = group.optString("parseApi", "");
        JSONArray urls = group.optJSONArray("playUrls");
        if (urls == null) {
            return out;
        }
        for (int i = 0; i < urls.length(); i++) {
            JSONObject u = urls.optJSONObject(i);
            if (u == null) {
                continue;
            }
            out.add(playEntry(u.optString("name", ""), parseApi, u.optString("url", "")));
        }
        return out;
    }

    /**
     * 把线路列表拼成 {@code vod_play_from} 的值（{@code $$$} 分隔）。
     *
     * @param sortedGroups 已排序线路
     * @return 播放源串
     */
    public static String buildPlayFrom(List<JSONObject> sortedGroups) {
        StringBuilder sb = new StringBuilder();
        if (sortedGroups != null) {
            for (JSONObject g : sortedGroups) {
                if (sb.length() > 0) {
                    sb.append(SEP_SOURCE);
                }
                sb.append(groupName(g));
            }
        }
        return sb.toString();
    }

    /**
     * 把线路列表拼成 {@code vod_play_url} 的值（组内 {@code #}、组间 {@code $$$}）。
     *
     * @param sortedGroups 已排序线路
     * @return 播放地址串
     */
    public static String buildPlayUrl(List<JSONObject> sortedGroups) {
        StringBuilder sb = new StringBuilder();
        if (sortedGroups != null) {
            for (JSONObject g : sortedGroups) {
                if (sb.length() > 0) {
                    sb.append(SEP_SOURCE);
                }
                List<String> entries = playEntriesOf(g);
                for (int i = 0; i < entries.size(); i++) {
                    if (i > 0) {
                        sb.append(SEP_EPISODE);
                    }
                    sb.append(entries.get(i));
                }
            }
        }
        return sb.toString();
    }

    // ── 播放 ─────────────────────────────────────────────────

    /**
     * 播放响应 —— 上游 {@code playerContent} 的三分支。
     *
     * <p>分支判定（已把上游冗余条件化简，见类注释缺陷 4）：
     * <ol>
     *   <li>左半为空 → 直链 {@code parse=0/jx=0/url=右半}；</li>
     *   <li>左半含 {@code json}/{@code api} → 请求 {@code 左半+右半}，响应有 {@code url} 就直链；</li>
     *   <li>其它（含第 2 步取不到 url）→ {@code parse=1/jx=1/url=左半+右半}。</li>
     * </ol>
     *
     * @param playString 形如 {@code 解析接口||地址}
     * @return 播放 JSON（可能带 {@code danmaku}）
     */
    public String buildPlayer(String playString) {
        String[] parts = (playString == null ? "" : playString).split("\\|\\|");
        String left = parts.length > 0 ? nz(parts[0]) : "";
        String right = parts.length > 1 ? nz(parts[1]) : "";
        JSONObject o = new JSONObject();
        try {
            if (left.isEmpty()) {
                o.put("parse", 0);
                o.put("url", right);
                o.put("jx", 0);
                return addDanmaku(o.toString());
            }
            if (left.contains("json") || left.contains("api")) {
                Map<String, String> h = new HashMap<>();
                h.put("User-Agent", BROWSER_UA);
                String body = fetch(left + right, h);
                if (body != null && !body.isEmpty()) {
                    JSONObject peek = new JSONObject(body);
                    if (peek.has("url")) {
                        o.put("parse", 0);
                        o.put("url", peek.optString("url", ""));
                        o.put("jx", 0);
                        return addDanmaku(o.toString());
                    }
                }
            }
            o.put("parse", 1);
            o.put("url", left + right);
            o.put("jx", 1);
            return addDanmaku(o.toString());
        } catch (Exception e) {
            Log.e(TAG, "buildPlayer failed", e);
            return addDanmaku(o.toString());
        }
    }

    /**
     * 追加弹幕地址 —— 上游 {@code merge.p026a.a.addDanmaku(String)} 的等价实现。
     *
     * <p>上游把静态的代理地址 {@code Proxy.getUrl() + "?do=diydanmu"} 写进
     * {@code danmaku} 字段；本重写改为可注入的 {@link #proxyBase}，
     * 未注入时不产出该字段（避免写进一个假地址）。
     *
     * @param json 播放 JSON 文本
     * @return 带 {@code danmaku} 的文本；解析失败或未注入代理时原样返回
     */
    public String addDanmaku(String json) {
        if (json == null || json.isEmpty() || proxyBase.isEmpty()) {
            return json;
        }
        try {
            JSONObject o = new JSONObject(json);
            o.put("danmaku", proxyBase + DANMAKU_QUERY);
            return o.toString();
        } catch (Exception e) {
            Log.w(TAG, "addDanmaku: 非 JSON，原样返回");
            return json;
        }
    }

    /**
     * 详情解析完成后的钩子 —— 上游 {@code merge.p026a.a.parseJsonAndSave(String)}。
     *
     * <p>上游行为：把详情列表<b>第一条</b>的 {@code vod_name} 写进宿主偏好
     * {@code historyvodname}（供弹幕匹配上次播放的片名）。这是隐式全局副作用，
     * 且列表为空时内部 {@code getJSONObject(0)} 会抛异常（被它自己吞掉并打堆栈）。
     * 本重写把它变成显式钩子：默认不落库，需要时覆写即可。
     *
     * @param detailJson 详情响应文本
     * @param vodName    第一条的片名；无数据时为空串（此时不调用本方法）
     */
    public void afterDetail(String detailJson, String vodName) {
        Log.i(TAG, "afterDetail: " + vodName + "（默认不落宿主偏好，可按需覆写）");
    }

    // ── 契约方法 ─────────────────────────────────────────────

    /**
     * 分类页。
     *
     * @param tid    分类 id
     * @param pg     页码（空/非数字按 1）
     * @param filter 宿主传参，未使用
     * @param extend 筛选（class/year/area/lang），可为 null
     * @return 标准分类响应
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        try {
            int page = pageNum(pg, 1);
            Map<String, String> params = new LinkedHashMap<>();
            params.put("page", String.valueOf(page));
            params.put("page_size", String.valueOf(PAGE_SIZE));
            if (extend != null) {
                for (String k : new String[]{"class", "year", "area", "lang"}) {
                    if (extend.containsKey(k)) {
                        params.put(k, nz(extend.get(k)));
                    }
                }
            }
            JSONObject resp = request("/api/app/categories/" + tid + "/videos", params, true);
            List<Vod> list = parseVideos(dataArray(resp), true);
            return VodBuilder.create().addAll(list)
                    .page(page, PAGE_COUNT * PAGE_SIZE, PAGE_SIZE)
                    .categoryContent();
        } catch (Throwable e) {
            Log.e(TAG, "categoryContent failed, tid=" + tid, e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /**
     * 详情页。
     *
     * @param ids 上游只取第一个
     * @return 标准详情响应
     */
    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids == null || ids.isEmpty() ? "" : nz(ids.get(0));
            JSONObject resp = request("/api/app/videos/" + id, null, false);
            JSONObject data = resp == null ? null : resp.optJSONObject("data");
            if (data == null) {
                return VodBuilder.EMPTY_RESPONSE;
            }
            List<JSONObject> groups = sortGroupsByQuality(data.optJSONArray("playGroups"));
            Vod v = new Vod()
                    .id(String.valueOf(data.optInt("id")))
                    .name(data.optString("name", ""))
                    .pic(data.optString("pic", ""))
                    .remarks(data.optString("remarks", data.optString("subTitle", "")))
                    .typeName(data.optString("categoryName", ""))
                    .year(data.optString("year", ""))
                    .area(data.optString("area", ""))
                    .actor(data.optString("actor", ""))
                    .director(data.optString("director", ""))
                    .content(data.optString("content", ""))
                    .playFrom(buildPlayFrom(groups))
                    .playUrl(buildPlayUrl(groups));
            String json = VodBuilder.detail(v).detailContent();
            afterDetail(json, v.vodName);
            return json;
        } catch (Throwable e) {
            Log.e(TAG, "detailContent failed", e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /**
     * 首页分类。
     *
     * <p>上游 {@code filters} 给的是空对象（本类不提供筛选面板），保持原样。
     *
     * @param filter 宿主传参，未使用
     * @return 标准首页响应
     */
    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject resp = request("/api/app/categories", null, false);
            VodBuilder b = VodBuilder.create();
            JSONArray arr = resp == null ? null : resp.optJSONArray("data");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null) {
                        continue;
                    }
                    b.clazz(String.valueOf(c.optInt("id")), c.optString("name", ""));
                }
            }
            return b.homeContent();
        } catch (Throwable e) {
            Log.e(TAG, "homeContent failed", e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /**
     * 首页轮播（分类 1 的排行榜）。
     *
     * @return {@code {"list":[…]}}
     */
    @Override
    public String homeVideoContent() {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("category", "1");
            JSONObject resp = request("/api/app/ranking/list", params, false);
            List<Vod> list = parseVideos(
                    resp == null ? null : resp.optJSONArray("data"), false);
            // 形状与上游一致：{"list":[...]}（searchContent() 就是这个形状）
            return VodBuilder.create().addAll(list).searchContent();
        } catch (Throwable e) {
            Log.e(TAG, "homeVideoContent failed", e);
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
            Map<String, String> params = new LinkedHashMap<>();
            params.put("keyword", nz(key));
            params.put("page", "1");
            params.put("page_size", String.valueOf(PAGE_SIZE));
            JSONObject resp = request("/api/app/videos/search", params, true);
            List<Vod> list = parseVideos(dataArray(resp), true);
            return VodBuilder.create().addAll(list).searchContent();
        } catch (Throwable e) {
            Log.e(TAG, "searchContent failed", e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /**
     * 播放。
     *
     * @param flag   站点标识（未使用）
     * @param id     播放串，形如 {@code 解析接口||地址}
     * @param flags  未使用
     * @return 播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> flags) {
        return buildPlayer(id);
    }

    /**
     * 空值归一。
     *
     * @param s 文本，可为 null
     * @return 非 null 文本
     */
    public static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 供测试/排障：上一次请求用过的 URL 与签名（覆写 {@link #fetch} 时可读）。
     *
     * @return 最近一次签名后的头表副本；未请求过为空表
     */
    public Map<String, String> lastSignedHeaders() {
        return lastHeaders == null ? new LinkedHashMap<String, String>() : lastHeaders;
    }

    /** 最近一次请求的头（排障用） */
    private Map<String, String> lastHeaders;

    /**
     * 记录最近一次签名头，便于断言（覆写 {@link #fetch} 的测试也可直接读）。
     *
     * @param headers 头表
     */
    protected void rememberHeaders(Map<String, String> headers) {
        this.lastHeaders = headers == null ? null : new LinkedHashMap<>(headers);
    }

    /**
     * 生成一次签名的中间结果（供测试钉住固定向量）。
     *
     * @param path         路径
     * @param params       参数
     * @param encodeValues 签名原文是否编码
     * @param timestamp    时间戳
     * @param randomText   已确定的随机串（16 字符）
     * @return 三元组：签名原文、签名、最终 URL
     */
    public String[] signVector(String path, Map<String, String> params, boolean rawInSignature,
                               long timestamp, String randomText) {
        String ts = String.valueOf(timestamp);
        String nc = Base64.encodeToString(
                randomText.getBytes(StandardCharsets.UTF_8), 2);
        String source = signSource(path, joinQuery(params, !rawInSignature), ts, nc);
        return new String[]{source, hmacSha256Hex(source, signKey), signedUrl(path, joinQuery(params, true))};
    }
}
