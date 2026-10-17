package com.example.tvbox.plain.app99;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * App99（剧圈99 / 半日99 / 双星99 三站共用）—— 明文实现
 *
 * 目标 APK: ae/target/spider_probe.apk
 *   ★ 实测 SHA-256 = e0ab6d3d6e244e0503a7cd31334d23266f20671905f8e18bff2a5fa980eb1c90
 *     （任务书给的 97dc46db…a87a6 与本机文件不符，全部证据基于实测摘要）
 * 提供者: JADX headless 0.7.1，全程静态，APK 从未执行。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 一、buildSignature 到底怎么参与请求？—— 实测结论，与常见猜测不同
 * ═══════════════════════════════════════════════════════════════════════
 *
 * 请求头由 g(nonce, timestamp, body) 构造，
 * 证据 ev_0a0e6054224367ca132c41240865d3464b5a41eee01f069cabf293bf560c0690：
 *
 *   byte[] d = MessageDigest.getInstance("SHA-256")
 *                .digest(String.format("%s:%s:%s:%s", str3, str2, str, this.b)
 *                        .getBytes("UTF-8"));
 *   // 形参 str3 = 请求体密文；str2 = timestamp；str = nonce；this.b = user_token
 *
 *   map.put("uuid",         this.c);          // device uuid
 *   map.put("timestamp",    str2);
 *   map.put("sign",         hex(d));          // ★ SHA-256 小写 hex，64 字符
 *   map.put("nonce",        str);
 *   map.put("version",      this.e);
 *   map.put("api_version",  "v1");
 *   // 另加 f() 的 4 个头（见下）
 *
 * ★★★ 关键结论：签名字符串是 `密文:timestamp:nonce:user_token` 四段冒号拼接，
 *     **buildSignature / buildNumber / package 全部 不参与 SHA-256 计算**。
 *     它们只出现在请求体的 JSON 里（`app.buildSignature`、`app.buildNumber`）：
 *
 *   证据 ev_cd6a5aca05eac734aca308e291e5e841c5b99e5f96f49c1fcdea68acafaaf533：
 *     JSONObject app = {version, name, package, buildNumber, buildSignature, install, update}
 *     然后 d(body) 做 AES 加密，最后走 POST。
 *
 *   → 准确说法：buildSignature 是**请求体里的设备/安装态标识**（模拟"官方 APK 已安装"），
 *     不是 header 签名因子，也不参与 sign 计算。
 *     服务端拿它校验「请求来自签名一致的官方包」，属于应用级白名单，而非密码学绑定。
 *     所以：值可以是 APK 签名的 SHA-256（真实来源），但它在上行链路里只是普通 JSON 字段。
 *
 *   两个 systemInit 变体（证据同上）：
 *     变体A（公共初始化）body = {v, n, s, pl:"1", apiVersion:"v2", token:"", timestamp, nonce}
 *        v=versionName  n=name  s=buildSignature  ← 这里 buildSignature 直接用 "s" 短键
 *     变体B（登录，LoginPath）body = {os,name,version,sdkInt,...,app:{...完整...},did,...}
 *     ★ 变体A 的 "s" 暗示服务端确实把它当"签名"校验，但仍是 body 字段。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 二、传输层
 * ═══════════════════════════════════════════════════════════════════════
 *   证据 ev_c3757541f7222c3def7b0b64b6ac63d2ea246c0f6dc5bf9f644c4873af139724：
 *     merge.k.b.f(String url, String str2=body, Map map=headers)
 *       → new c(url, body, headers).a(client)
 *   f(String,String,Map) 与 f(URL,byte[],HashMap) 是不同重载；App99 走的是前者：
 *   **POST，body 为明文 JSON 字符串（已由 d() 加密成 Base64），签名头全部放 header**。
 *
 *   头集合（证据 ev_fa384874e6414c86387ffff686dfaad82a88c292dbc7398ff2d8a5f4c20183b9）：
 *     User-Agent    = ext.ua（缺省 Chrome/151 桌面 UA）
 *     Accept        = application/json
 *     Content-Type  = application/json
 *     client_type   = android
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 三、请求体加密 d(String)：AES-128-CBC，key = uuid 去横线，IV 前置
 * ═══════════════════════════════════════════════════════════════════════
 *   证据 ev_040ddc7b9b5c3755f5e00bc44bfe009f31ce2c7364aa86daf3da450b8d2083ca：
 *     String str2 = TextUtils.isEmpty("") ? this.c : "";    // 恒取 this.c（uuid）
 *     SecretKeySpec ks = new SecretKeySpec(str2.replace("-","").getBytes(), "AES");
 *     Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
 *     c.init(1, ks);                      // 1 = ENCRYPT；不传 IV → 由 JCE 随机生成
 *     byte[] ct = c.doFinal(plain);
 *     byte[] iv = c.getIV();              // ★ 取回随机 IV
 *     return Base64(iv + ct);             // ★ IV 前置拼在密文前
 *
 *   ★ 三个易错点：
 *     1) key 是 uuid 去掉 '-' 后的 **32 个字符 = 32 字节 → AES-256**（不是 AES-128）。
 *        容易误判成 AES-128："UUID 是 128 bit"说的是 UUID 的熵，
 *        不是去横线后字符串的字节数。36 字符去 4 个横线得 32 字符 = 32 字节。
 *        本类用 aesKeyBitsOfUuid() 实测算位数，测试 E1 断言其等于 256，不凭直觉写注释。
 *     2) cipher.init(1, ks) 只有两个参数 → IV 由 provider 随机生成，必须 getIV() 取回
 *     3) 输出是 iv||ct 的 Base64，不是纯 ct
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 四、响应解密 c(String ct, String key)：取前 16 字节当 IV，再 zlib 解压
 * ═══════════════════════════════════════════════════════════════════════
 *   证据 ev_fbdcdf087369ede01fe367a3e6f0ff0ca829c9b154abc9f2a36763cafbb79951：
 *     if (TextUtils.isEmpty(str2)) str2 = this.c;
 *     byte[] raw = Base64.getDecoder().decode(str);
 *     byte[] iv  = raw[0..16];  byte[] body = raw[16..];
 *     SecretKeySpec ks = new SecretKeySpec(str2.replace("-","").getBytes(), "AES");
 *     Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
 *     c.init(2, ks, new IvParameterSpec(iv));
 *     byte[] pt = c.doFinal(body);
 *     try { Inflater → ByteArrayOutputStream → UTF-8 }   // ★ 解压后再读
 *     catch { new String(pt, "UTF-8") }                    // 解压失败则当纯文本
 *
 *   ★ 响应体是「AES-CBC 密文 → zlib 压缩的 JSON」，两层都要还原。
 *     失败静默回退为纯文本 —— 这个 catch 分支说明服务端有时不压缩。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 五、nonce e()：16 字节 SecureRandom → Base64（24 字符，含 '=' 填充）
 * ═══════════════════════════════════════════════════════════════════════
 *   证据 ev_2783bfdd21d8d357b734cf9dcb787cec74e04262f4caa3ff0f843e9f91fce9eb
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 六、未确定项（明确标注，不编造）
 * ═══════════════════════════════════════════════════════════════════════
 *   [U1] 三个站点的 host / name / versionName / buildNumber / package 真实值
 *        只有任务书给出的部分（剧圈99 buildSignature/package/buildNumber、
 *        半日99/双星99 buildSignature），其余用占位常量，可在 SITES 表内补。
 *   [U2] user_token（this.b）初始为空串，登录后由 LoginPath 响应 userInfo.user_token 填充。
 *        首轮请求 sign 里最后一段是空串，即 "密文:ts:nonce:" 结尾带一个冒号 —— 已按此实现。
 *   [U3] 业务接口路径（category / detail / search / play）未在 App99 里出现明文常量，
 *        推测由 ext 或 systemInit 响应下发；本类提供骨架 + 标注，不编造路径。
 *   [U4] "s" 之外的 buildSignature 用法（服务端是否比对）静态不可知；
 *        客户端侧已确认它只是 JSON 字段。
 
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>业务接口路径</b>：本类里 {@code PATH_CATEGORY}/{@code PATH_DETAIL}/
 *       {@code PATH_SEARCH}/{@code PATH_PLAY} 是占位常量 —— 证据提取时只确认了
 *       {@code /app/systemInit} 与 {@code /app/userInfo} 两处。
 *       <b>该缺口已由 {@code app99/App99SpiderPlain} 补齐</b>：业务路径在宿主类里
 *       是明文字面量（{@code /vod/search}、{@code /vod/detail}、{@code /app/vodParser}），
 *       外壳按实测值固化，本类的四个占位常量不再被使用（见其 javadoc 的更正段）。</li>
 *   <li><b>响应业务字段</b>：本类只负责传输层（解密/zlib），不解析条目字段；
 *       列表映射在 {@code App99SpiderPlain.parseItems}。</li>
 *   <li><b>真实站点 host</b>：三站 host 由 ext 下发，本类不内置（宿主类里也没有）。</li>
 *   <li><b>服务端校验强度</b>：{@code sign} 是否被服务端强校验、sig 是否参与防重放，
 *       无报文样本可证，未知。</li>
 * </ol>
 *
 * <h2>上游缺陷 / 代码异味（本类记录，不修）</h2>
 * <ol>
 *   <li><b>签名原文用冒号拼接且允许空段</b>：{@code String.format("%s:%s:%s:%s",
 *       cipherBody, timestamp, nonce, user_token)}；首轮请求 {@code user_token} 为空，
 *       原文以冒号结尾。若某个字段本身含冒号，段边界就不可区分（本类逐字保留该语义，
 *       因为改分隔符会让签名与服务端不一致）。</li>
 *   <li><b>同协议两个版本号</b>：请求头 {@code api_version: "v1"}，请求体里
 *       {@code apiVersion: "v2"}。</li>
 *   <li><b>解密失败静默回退明文</b>：响应解不开时按 UTF-8 当明文返回
 *       （容错，但会把乱码继续往下传；外壳靠后续 JSON 解析失败兜住）。</li>
 *   <li><b>列表解析假定元素都是对象</b>：{@code b(JSONArray)}/{@code a(JSONArray)}
 *       {@code getJSONObject(i)} 遇非对象直接抛，被外层 catch 吞掉后整个列表变空。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/App99.java}（字段 {@code a..g}、
 * 方法 {@code a/b/c/d/e/f/g}、{@code processDetailResult}）；
 * 配套外壳 {@code maintain/src/plain/app99/App99SpiderPlain.java}。
*/
public final class App99Plain {

    // ─────────────────────────────────────────────────────────────────
    // 常量
    // ─────────────────────────────────────────────────────────────────

    /** UA 缺省值，证据 ev_cd6a5aca05eac734aca308e291e5e841c5b99e5f96f49c1fcdea68acafaaf533 */
    public static final String DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** LoginPath 缺省值 */
    public static final String DEFAULT_LOGIN_PATH = "/app/userInfo";

    /** 公共初始化端点 */
    public static final String PATH_SYSTEM_INIT = "/app/systemInit";

    /** 客户端固定上报值 */
    public static final String CLIENT_TYPE = "android";
    /** g() 里 api_version 固定 "v1"；body 里 apiVersion 固定 "v2" —— 两处不同，勿混 */
    public static final String HEADER_API_VERSION = "v1";
    public static final String BODY_API_VERSION  = "v2";

    private static final SecureRandom RND = new SecureRandom();

    private App99Plain() {}

    // ═════════════════════════════════════════════════════════════════
    // 一、nonce
    // ═════════════════════════════════════════════════════════════════

    /**
     * 逐字复原 e()：16 字节 SecureRandom → Base64。
     * 证据 ev_2783bfdd21d8d357b734cf9dcb787cec74e04262f4caa3ff0f843e9f91fce9eb
     * 输出恒为 24 字符（16 字节 Base64 含一个 '=' 填充）。
     */
    public static String nonce() {
        byte[] b = new byte[16];
        RND.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    // ═════════════════════════════════════════════════════════════════
    // 二、签名（核心）
    // ═════════════════════════════════════════════════════════════════

    /**
     * 逐字复原 g() 里的 sign 计算。
     * 证据 ev_0a0e6054224367ca132c41240865d3464b5a41eee01f069cabf293bf560c0690
     *
     * SHA-256( "<密文>:<timestamp>:<nonce>:<userToken>" ) → 小写 hex（64 字符）
     *
     * ★ 拼接顺序严格是 cipherBody, timestamp, nonce, userToken
     *   （对应源码 String.format("%s:%s:%s:%s", str3, str2, str, this.b)）
     * ★ buildSignature 不在此串内 —— 见类头注释第一节。
     * ★ hex 用 Integer.toHexString(b & 255) 并补前导 0，等价于小写 %02x。
     *
     * @param cipherBody 请求体密文（d() 的输出）
     * @param timestamp  毫秒时间戳字符串
     * @param nonce      e() 生成的 nonce
     * @param userToken  this.b，未登录时为空串
     */
    public static String sign(String cipherBody, String timestamp, String nonce, String userToken) {
        try {
            String raw = String.format("%s:%s:%s:%s",
                    cipherBody, timestamp, nonce, userToken == null ? "" : userToken);
            byte[] d = MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return toHexLower(d);
        } catch (Exception e) {
            return "";
        }
    }

    /** 小写 hex，逐字等价 Integer.toHexString(b & 255) + 补 0 */
    public static String toHexLower(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String h = Integer.toHexString(b & 255);
            if (h.length() == 1) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════════
    // 三、请求头 g(nonce, timestamp, cipherBody)
    // ═════════════════════════════════════════════════════════════════

    /**
     * 逐字复原 g(String str=nonce, String str2=timestamp, String str3=cipherBody)。
     * 证据 ev_0a0e6054224367ca132c41240865d3464b5a41eee01f069cabf293bf560c0690（签名+头）
     *      ev_fa384874e6414c86387ffff686dfaad82a88c292dbc7398ff2d8a5f4c20183b9（f() 基础头）
     *
     * 头键序（LinkedHashMap 保持与源码 put 顺序一致，便于比对抓包）：
     *   User-Agent, Accept, Content-Type, client_type,
     *   uuid, timestamp, sign, nonce, version, api_version
     */
    public static Map<String, String> headers(String nonce, String timestamp, String cipherBody,
                                              String uuid, String userToken,
                                              String version, String ua) {
        Map<String, String> h = new LinkedHashMap<>();
        // f() 的 4 个基础头
        h.put("User-Agent", ua == null || ua.isEmpty() ? DEFAULT_UA : ua);
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json");
        h.put("client_type", CLIENT_TYPE);
        // g() 追加的 6 个签名头
        h.put("uuid", uuid);
        h.put("timestamp", timestamp);
        h.put("sign", sign(cipherBody, timestamp, nonce, userToken));
        h.put("nonce", nonce);
        h.put("version", version);
        h.put("api_version", HEADER_API_VERSION);
        return h;
    }

    // ═════════════════════════════════════════════════════════════════
    // 四、请求体加密 d(String)  —  IV 前置
    // ═════════════════════════════════════════════════════════════════

    /**
     * 逐字复原 d(String str)：AES/CBC/PKCS5 加密，随机 IV 前置，整体 Base64。
     * 证据 ev_040ddc7b9b5c3755f5e00bc44bfe009f31ce2c7364aa86daf3da450b8d2083ca
     *
     * 原实现：
     *   SecretKeySpec ks = new SecretKeySpec(uuid.replace("-","").getBytes("UTF-8"), "AES");
     *   Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
     *   c.init(1, ks);                 // 无 IV 参数 → provider 随机生成
     *   byte[] ct = c.doFinal(plain);
     *   return Base64(iv || ct);
     *
     * ★ 必须用 getIV() 取回真实 IV 再拼接 —— 否则服务端无从解密。
     *
     * @param plain 明文 JSON
     * @param uuid  ext.uuid 或 UUID.randomUUID().toString()
     */
    public static String encryptBody(String plain, String uuid) {
        // 单一实现来源已抽到 AesZlibCodec，此处仅委托，避免两处逻辑漂移。
        return AesZlibCodec.encrypt(plain, uuid);
    }

    /** 测试/调试用：指定 IV 的加密（真实链路用随机 IV，此重载仅为可复现夹具） */
    public static String encryptBodyWithIv(String plain, String uuid, byte[] iv) {
        return AesZlibCodec.encryptWithIv(plain, uuid, iv);
    }

    // ═════════════════════════════════════════════════════════════════
    // 五、响应解密 c(String ct, String key)  —  前 16 字节当 IV + zlib
    // ═════════════════════════════════════════════════════════════════

    /**
     * 逐字复原 c(String str, String str2)。
     * 证据 ev_fbdcdf087369ede01fe367a3e6f0ff0ca829c9b154abc9f2a36763cafbb79951
     *
     * 流程：Base64 → 拆 [0,16) 为 IV、[16,) 为密文 → AES/CBC 解密 → zlib inflate → UTF-8
     * 解压失败时回退为纯文本（与原实现 catch 分支一致）。
     *
     * @param cipherB64 响应体
     * @param uuid      设备 uuid（空则用 fallbackUuid）
     */
    public static String decryptResponse(String cipherB64, String uuid, String fallbackUuid) {
        // 委托 AesZlibCodec：IV 前置 + AES/CBC + zlib inflate 的单一实现
        return AesZlibCodec.decrypt(cipherB64, uuid, fallbackUuid);
    }

    /**
     * zlib 解压，失败回退纯文本 —— 逐字复原原实现的 try/catch 语义。
     * 证据同上（ev_fbdcdf08…bb79951）
     *
     * 原实现循环 `while(!inflater.finished()) write(inflate(buf))`；
     * 这里加 needInput 保护，避免畸形数据导致 inflate 恒返回 0 的死循环
     * （原实现靠 finished() 退出，对空输入会死循环 —— 属于原实现缺陷，本版修正并注明）。
     */
    public static String inflateOrText(byte[] data) {
        return AesZlibCodec.inflateOrText(data);
    }

    /** 测试/调试用：zlib 压缩，用于构造响应夹具 */
    public static byte[] deflate(byte[] data) {
        return AesZlibCodec.deflate(data);
    }

    // ═════════════════════════════════════════════════════════════════
    // 六、两个请求体构造函数（逐字复原 init() 的两条分支）
    // ═════════════════════════════════════════════════════════════════

    /**
     * 变体A：公共初始化 /app/systemInit 的 JSON 体。
     * 证据 ev_cd6a5aca05eac734aca308e291e5e841c5b99e5f96f49c1fcdea68acafaaf533
     *
     * 键序与源码 put 顺序一致：v, n, s, pl, apiVersion, token, timestamp, nonce
     * ★ 这里 buildSignature 用短键 "s"
     */
    public static String systemInitBody(String versionName, String name, String buildSignature,
                                        String timestamp, String nonce) {
        return "{"
            + "\"v\":\"" + esc(versionName) + "\","
            + "\"n\":\"" + esc(name) + "\","
            + "\"s\":\"" + esc(buildSignature) + "\","
            + "\"pl\":\"1\","
            + "\"apiVersion\":\"" + BODY_API_VERSION + "\","
            + "\"token\":\"\","
            + "\"timestamp\":\"" + esc(timestamp) + "\","
            + "\"nonce\":\"" + esc(nonce) + "\""
            + "}";
    }

    /**
     * 变体B：登录（LoginPath，默认 /app/userInfo）的 JSON 体。
     * 证据 ev_cd6a5aca05eac734aca308e291e5e841c5b99e5f96f49c1fcdea68acafaaf533
     *
     * ★ 这是 buildSignature 出现得最完整的地方：app.buildSignature
     *   同时带 buildNumber / package / version / name / install / update。
     * 键序与源码 put 顺序一致。
     *
     * @param installUpdate 原实现 install 与 update 同值（同一毫秒）
     */
    public static String loginBody(String versionName, String name, String pkg,
                                   String buildNumber, String buildSignature,
                                   long installUpdate, String did,
                                   String timestamp, String nonce) {
        return "{"
            + "\"os\":\"android\","
            + "\"name\":\"xiaomi\","
            + "\"version\":\"15\","
            + "\"sdkInt\":32,"
            + "\"device\":\"xiaomi\","
            + "\"brand\":\"xiaomi\","
            + "\"manufacturer\":\"xiaomi\","
            + "\"product\":\"b0q\","
            + "\"hardware\":\"xiaomi\","
            + "\"isPhysicalDevice\":true,"
            + "\"androidId\":\"V417IR\","
            + "\"bootloader\":\"unknown\","
            + "\"display\":\"V417IR release-keys\","
            + "\"host\":\"a11-gz01-test\","
            + "\"tags\":\"release-keys\","
            + "\"type\":\"user\","
            + "\"finger\":\"xiaomi/b0q/b0q:15/V619IR/613:user/release-keys\","
            + "\"app\":{"
                + "\"version\":\"" + esc(versionName) + "\","
                + "\"name\":\"" + esc(name) + "\","
                + "\"package\":\"" + esc(pkg) + "\","
                + "\"buildNumber\":\"" + esc(buildNumber) + "\","
                + "\"buildSignature\":\"" + esc(buildSignature) + "\","
                + "\"install\":" + installUpdate + ","
                + "\"update\":" + installUpdate
            + "},"
            + "\"did\":\"" + esc(did) + "\","
            + "\"apiVersion\":\"" + BODY_API_VERSION + "\","
            + "\"channel\":\"\","
            + "\"token\":\"\","
            + "\"timestamp\":\"" + esc(timestamp) + "\","
            + "\"nonce\":\"" + esc(nonce) + "\""
            + "}";
    }

    /**
     * 从 LoginPath 响应里取 userInfo.user_token。
     * 证据 ev_cd6a5aca05eac734aca308e291e5e841c5b99e5f96f49c1fcdea68acafaaf533
     * 原实现：if(j.has("userInfo")) { if(userInfo.has("user_token")) this.b = ... }
     */
    public static String extractUserToken(String decryptedJson) {
        if (decryptedJson == null) return "";
        int i = decryptedJson.indexOf("\"userInfo\"");
        if (i < 0) return "";
        int j = decryptedJson.indexOf("\"user_token\"", i);
        if (j < 0) return "";
        int colon = decryptedJson.indexOf(':', j);
        if (colon < 0) return "";
        int q1 = decryptedJson.indexOf('"', colon);
        if (q1 < 0) return "";
        int q2 = decryptedJson.indexOf('"', q1 + 1);
        if (q2 < 0) return "";
        return decryptedJson.substring(q1 + 1, q2);
    }

    // ═════════════════════════════════════════════════════════════════
    // 七、站点表
    // ═════════════════════════════════════════════════════════════════

    /**
     * 站点配置。buildSignature 等来自任务书提供的真实 ext 值；
     * host / name / versionName 为已知/占位混合，见 [U1]。
     */
    public static final class Site {
        public final String label;
        public final String host;
        public final String name;
        public final String versionName;
        public final String pkg;
        public final String buildNumber;
        public final String buildSignature;
        public final String loginPath;

        public Site(String label, String host, String name, String versionName, String pkg,
                    String buildNumber, String buildSignature, String loginPath) {
            this.label = label; this.host = host; this.name = name;
            this.versionName = versionName; this.pkg = pkg;
            this.buildNumber = buildNumber; this.buildSignature = buildSignature;
            this.loginPath = loginPath;
        }
    }

    /** 剧圈99 —— buildSignature/package/buildNumber 来自任务书真实 ext */
    public static final Site JUJUAN99 = new Site(
        "剧圈99", "[HOST_JUJUAN99]", "剧圈99", "[VERSIONNAME_JUJUAN99]",
        "com.qingtangxiaozhuan.xyz", "2002",
        "054FA8DDA4319C6B6A9B954CA5777541C993F00B1B0BD4394F7EDE48184C4594",
        DEFAULT_LOGIN_PATH);

    /** 半日99 —— 仅 buildSignature 已知 */
    public static final Site BANRI99 = new Site(
        "半日99", "[HOST_BANRI99]", "半日99", "[VERSIONNAME_BANRI99]",
        "[PKG_BANRI99]", "[BUILDNUMBER_BANRI99]",
        "A40DA80A59D170CAA950CF15C18C454D47A39B26989D8B640ECD745BA71BF5DC",
        DEFAULT_LOGIN_PATH);

    /** 双星99 —— 与半日99 共用同一 buildSignature */
    public static final Site SHUANGXING99 = new Site(
        "双星99", "[HOST_SHUANGXING99]", "双星99", "[VERSIONNAME_SHUANGXING99]",
        "[PKG_SHUANGXING99]", "[BUILDNUMBER_SHUANGXING99]",
        "A40DA80A59D170CAA950CF15C18C454D47A39B26989D8B640ECD745BA71BF5DC",
        DEFAULT_LOGIN_PATH);

    /** [U3] 业务路径未在 App99 常量中出现，集中放这里便于后续用抓包补齐 */
    public static final String PATH_CATEGORY = "[PATH_CATEGORY]";
    public static final String PATH_DETAIL   = "[PATH_DETAIL]";
    public static final String PATH_SEARCH   = "[PATH_SEARCH]";
    public static final String PATH_PLAY     = "[PATH_PLAY]";

    // ═════════════════════════════════════════════════════════════════
    // 八、一站式请求构造（便于实际发请求 / 测试）
    // ═════════════════════════════════════════════════════════════════

    /** 一次完整请求所需的一切 */
    public static final class PreparedRequest {
        public final String url;
        public final String body;                 // 已加密的 Base64，直接作为 POST body
        public final Map<String, String> headers;
        public final String nonce;
        public final String timestamp;
        public final String plainBody;            // 明文 JSON，仅供调试

        PreparedRequest(String url, String body, Map<String, String> headers,
                        String nonce, String timestamp, String plainBody) {
            this.url = url; this.body = body; this.headers = headers;
            this.nonce = nonce; this.timestamp = timestamp; this.plainBody = plainBody;
        }
    }

    /**
     * 构造一个完整的、可直接发送的请求。
     * 严格按 init() 的观测顺序：nonce → timestamp → 明文 JSON → d()加密 → g()签名头
     */
    public static PreparedRequest prepare(Site site, String path, String plainBody,
                                          String uuid, String userToken, String ua) {
        String nc = nonce();
        String ts = String.valueOf(System.currentTimeMillis());
        String cipher = encryptBody(plainBody, uuid);
        Map<String, String> h = headers(nc, ts, cipher, uuid, userToken, site.versionName, ua);
        return new PreparedRequest(site.host + path, cipher, h, nc, ts, plainBody);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 诊断：报告 uuid 去横线后作为 AES key 的实际位数（避免注释凭直觉写） */
    public static int aesKeyBitsOfUuid(String uuid) {
        int bytes = uuid.replace("-", "").getBytes(StandardCharsets.UTF_8).length;
        switch (bytes) {
            case 16: return 128;
            case 24: return 192;
            case 32: return 256;
            default: return -1;
        }
    }

    /** 生成一个标准 UUID 字符串（与原实现 UUID.randomUUID().toString() 同格式） */
    public static String newUuid() {
        return UUID.randomUUID().toString();
    }

    /** 便于业务层：把解码后的 JSON 数组扁平转为 List<Map> 的占位（[U3] 业务解析待补） */
    public static List<Map<String, String>> emptyList() {
        return new ArrayList<>();
    }

    /** 小写规范化，避免各站 buildSignature 大小写不一致导致比对失败 */
    public static String normalizeSignature(String sig) {
        return sig == null ? "" : sig.trim().toUpperCase(Locale.ROOT);
    }
}
