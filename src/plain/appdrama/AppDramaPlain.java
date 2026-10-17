package com.example.tvbox.plain.appdrama;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AppDrama（天堂 / 苹果 / 橘汁 / 华谊 四站共用）—— 明文实现
 *
 * 目标 APK: ae/target/spider_probe.apk
 *   ★ 实测 SHA-256 = e0ab6d3d6e244e0503a7cd31334d23266f20671905f8e18bff2a5fa980eb1c90
 *     任务书给出的 97dc46db...a87a6 与本机文件不符，本文件全部证据基于实测摘要。
 * 提供者: JADX headless 0.7.1，全程静态，APK 从未执行。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 一、三个核心疑点的实证结论（不是猜测）
 * ═══════════════════════════════════════════════════════════════════════
 *
 * 【疑点1】publicKey 到底是「RSA 加密 AES key」还是「验签」？
 *   → 都不是。它是「用 RSA 公钥加密一段签名串，作为请求的防伪 sign 字段上行」。
 *
 *   证据 ev_14c37da4891937f7f364902649c7058a150736cc726bfaa32059ed55718eebce
 *     com.github.catvod.spider.AppDrama.i(String,String):
 *       PublicKey pk = KeyFactory.getInstance("RSA")
 *                        .generatePublic(new X509EncodedKeySpec(Base64.decode(str2,0)));
 *       Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
 *       c.init(1, pk);                                  // 1 == ENCRYPT_MODE
 *       return Base64.encodeToString(c.doFinal(str.getBytes()), 2);  // 2 == NO_WRAP
 *
 *   ★ 关键：init 的第一个参数是 1（ENCRYPT_MODE），不是 2（DECRYPT_MODE）。
 *     RSA 公钥只能加密、不能解密 —— 所以 publicKey 不参与任何响应解密。
 *     响应解密的 key 是 AES，与 publicKey 无关（见疑点2）。
 *     X509EncodedKeySpec 说明是 SubjectPublicKeyInfo（公钥），不是 PKCS8 私钥。
 *
 *   调用点 ev_90f178225f94110b51146bf4ba1fb6275e164448032248579b4c2d156b9f2678
 *     (init)  .setSign(i(timestamp + randomStr, this.b))
 *     → 被加密的明文 = 十进制毫秒时间戳 + 16 位随机串 的字符串拼接
 *
 *   同族调用点 ev_89fd4dc9d2667ab47b8289c05984bdd074524234b25f43c096d6c8a33f43fad5
 *     (方法 c，即请求头构造)  i(timestamp + randomStr + vApp, key)
 *     → 明文多拼一个 vApp（version 去掉点号，如 "3019"）
 *
 *   key 的选取 ev_89fd4dc9...（c 方法首行）：
 *     String str = this.d.isEmpty() ? this.b : this.d;
 *     this.b = ext.publicKey；this.d = 服务器 /api/v5/find/app/zone 返回的 str2+str3+str4+str5 拼接
 *     → 服务器可下发「轮换后的公钥」覆盖 ext 里的 publicKey。
 *
 * 【疑点2】dataKey / dataIv 的真实处理
 *   → 两个都是 AES-256 密钥原文（32 个字符直接 getBytes），走 AES/ECB，没有 IV。
 *     "dataIv" 这个名字是误导：它从来不当 IV 用。
 *
 *   为什么必须用「原文 32 字符」而不是 b64 解码后的字节 —— 实测：
 *     dataKey 天堂 = D2KREWRIBVNAUJJYEDL4VFHOY2Q0PQ==  长度 32
 *     Base64 解码后只有 22 字节  ← 22 不是合法 AES key 长度（须 16/24/32）
 *     若走 b64 解码则 SecretKeySpec 必抛 InvalidKeyException，链路根本跑不通。
 *     而 32 字符 UTF-8 = 32 字节 = AES-256，合法。同理 dataIv 32 字符 → AES-256。
 *     （四个站的 dataKey 解码后分别是 22/22/23/23 字节，全都不是合法长度，进一步排除。）
 *
 *   解密链（关键证据 ev_7ec34ae33eb204b576fc3386634dd4c0f989457138a46cc29aa048fdede41a04
 *           方法 homeVideoContent，a() 的唯一调用者）：
 *     String[] s = this.f2f;                  // f2f = {ext.dataKey, ext.dataIv}
 *     data = a(a(data, s[0]), s[1]);
 *     → 双层 AES/ECB 解密：外层先用 dataIv，内层再用 dataKey。
 *       第一层解出的是「Base64 文本」，第二层才解出 JSON 明文。
 *       顺序不可交换。
 *
 *   解密原语 ev_4ed06b91769b88d968002cb75729f2b7679f30d0962cc1bd4c724715d92cc899
 *     com.github.catvod.spider.AppDrama.a(String str, String str2):
 *       Cipher cipher = Cipher.getInstance("AES/ECB/PKCS7Padding");
 *       cipher.init(2, new SecretKeySpec(str2.getBytes(), "AES"));   // 2 == DECRYPT_MODE
 *       return new String(cipher.doFinal(Base64.decode(str, 0)), StandardCharsets.UTF_8);
 *
 *   开关：homeVideoContent 里 `if (!this.g.equals("0"))` 才解密，
 *         this.g = ext.decrypt。即 ext.decrypt 显式设为 "0" 时跳过解密。
 *
 * 【疑点3】请求体加密用的 key —— 与 dataKey/dataIv 无关，是另一套
 *   客户端有两条上行通道，都用同一个硬编码常量 commonKey：
 *
 *   (A) JSON 通道  ev_ad7f8ec920a97aeddb0a0faadbf4548d377dd21175afeeb0fd2f0d885bb62ea1
 *       方法 e()：paramsData = b(d().toString(), this.c, "CBC", this.c)
 *       → AES/CBC，key = iv = this.c
 *   (B) Protobuf 通道  ev_8b25e1c25ec3cc962928bb4d32be067e6ed5035b8c1e9f02f8432b4c925586ba
 *       方法 g(map)：str3 = randomStr + b(kv串 + timestamp, f2f[0], "ECB", null)
 *       → 注意这里用的是 f2f[0] == ext.dataKey（AES/ECB），不是 commonKey！
 *         然后 str3 被切成 aes_encrypt1 = str3[0..20)、aes_encrypt2 = str3[20..)
 *       (A)(B) 各自独立，不要混用。
 *
 *   this.c 的取值 ev_90f178225f94110b51146bf4ba1fb6275e164448032248579b4c2d156b9f2678
 *     this.c = "ed5fdsgucxumegqa";      ← 16 字符
 *   ★ 该字面量已用 dex 原始字节核验：classes.dex 偏移 1125112 处为 ASCII
 *     "ed5fdsgucxumegqa"，恰 16 字符。jadx 显示一致，无截断。
 *     实测佐证：15 字符 key 会被 JCE 拒绝（InvalidKeyException: Invalid AES key length: 15 bytes），
 *     而本链路在真机可跑，故字面量必须是 16 字符。
 *     它恰好等于 ext 里「苹果」那站的 commonKey —— 四站共用同一硬编码常量，
 *     ext.commonKey 对「天堂/橘汁/华谊」是多余字段。
 *
 *   加密原语 ev_0ac29a4aa3cbf8c552761f1c0d95ce4b5a1a3e1069ec17411421e3b32f709d18
 *     com.github.catvod.spider.AppDrama.b(str, key, mode, iv):
 *       Cipher.getInstance("AES/" + mode + "/PKCS7Padding");   // mode 传 "ECB" 或 "CBC"
 *       "CBC" 分支: init(ENCRYPT, key, new IvParameterSpec(iv.getBytes()))
 *                   输出逐字节 %02x 拼 hex（小写，无分隔）
 *       非 CBC(ECB): init(ENCRYPT, key)
 *                   输出 Base64(NO_WRAP)
 *     ★ 注意输出格式随 mode 变：ECB→Base64，CBC→hex。这是硬编码分支，不是笔误。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 二、JCE 移植注意事项（已实测）
 * ═══════════════════════════════════════════════════════════════════════
 *   - "AES/ECB/PKCS7Padding" / "AES/CBC/PKCS7Padding" 是 Android/BouncyCastle 扩展名，
 *     标准 JCE 抛 NoSuchAlgorithmException（本机 JDK 25 实测确认）。
 *     AES 分组恒为 16 字节，PKCS5Padding 与 PKCS7Padding 在 AES 下完全等价 → 统一改 PKCS5Padding。
 *   - 原实现 str.getBytes() / key.getBytes() 无参，依赖平台默认编码；
 *     这里显式 UTF-8（Android 默认即 UTF-8，密钥全 ASCII，无差异）。
 *   - Base64.decode(x, 0) 是 Android flags=0（严格），此处用 java.util.Base64 基本解码器。
 *   - Base64.encodeToString(x, 2) 是 NO_WRAP，等价于 java.util.Base64 基本编码器。
 *   - hex 用 String.format("%02x", Byte.valueOf(b))：Java 会把 byte 装箱成 Byte，
 *     走 %x 的 Byte 分支，负数也输出两位（实测 0x80→"80"，不会变 "ffffff80"），与原实现一致。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 三、protobuf 线格式（字段号从 dex 内嵌 descriptor 直读，非推测）
 * ═══════════════════════════════════════════════════════════════════════
 *   classes.dex 偏移 705023 处内嵌 SecureRequest.proto 描述符原文：
 *     SecureRequest{aes_encrypt1=1(str), aes_encrypt2=2(str), aes_fakestr=3(str),
 *                   timestamp=4(int64), random_str=5(str)}
 *   偏移 703385 处内嵌 RSARequest.proto：
 *     RSARequest{timestamp=1(int64), sign=2(str), fake1=3(str), random_str=4(str), fake2=5(str)}
 *   偏移 ~703xxx RSAPublic.proto：
 *     RSAPublic{str1..str5 = 1..5, 全 str}
 *   ApiResult.proto：
 *     ApiResult{code=1(int64), msg=2(str), data=3(bytes)}
 *   → 本类按此手工编码，不依赖 protobuf 运行库。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 四、未确定项（明确标注，不编造）
 * ═══════════════════════════════════════════════════════════════════════
 *   [U1] 服务器 /api/v5/find/app/zone 返回的 RSAPublic.str2..str5 拼接成 this.d 后，
 *        作为「轮换公钥」使用（Base64 → X509 → RSA）。其语义（是 PEM 还是裸 DER Base64、
 *        是否带 -----BEGIN 头）静态不可知，需真实响应样本。本实现按「无头纯 Base64」处理，
 *        并提供 stripPem 兜底。
 *   [U2] ext 里 host / version 的实际值未在任务书给出，本类用占位常量。
 *   [U3] 响应里 code != 200 的业务错误分支，原实现未做判断（直接取 data），
 *        本类保持同样行为并在注释标明，避免"看起来更健壮但与原实现不符"。
 *   [U4] 业务 proto（DramaBeanPage / DramaDetailBean / ParsePlayUrlBean 等）字段号
 *        未逐个提取，故本类的业务解析函数保持结构化骨架 + TODO，
 *        加解密层已完全可用且可测。
 
 *
 * <h2>协议核 ↔ 外壳（同名陷阱）</h2>
 * 本类<b>不是内容源</b>：{@code final}、无基类、没有任何 {@code homeContent} 之类的方法。
 * 宿主 {@code com.github.catvod.spider.AppDrama} 的 7 个内容方法实现在
 * {@code maintain/src/plain/appdrama/AppDramaSpiderPlain.java}（那里 {@code extends BaseSpider}）。
 * 文件在 ≠ 做完了 —— 覆盖度是沿继承链走到 {@code Spider} 才算的。
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>业务 proto 解析</b>：本类只有 {@code ProtoWriter}（编码）。业务消息的
 *       **解码**在原 [U4] 里标为未提取 —— 该缺口已补齐，但在
 *       {@code AppDramaSpiderPlain} 的 {@code parse*} 系列里（字段号直读 DEX 内嵌
 *       descriptor），不在本类。</li>
 *   <li><b>响应外层 ApiResult</b>：{@code {code,msg,data}} 的拆包由外壳做；
 *       本类只到"请求体字节"这一层。</li>
 *   <li><b>站点 host / pkg / appName</b>：[U2] 占位 {@code [HOST_*]}；
 *       实际值由 ext 下发（见外壳的 ext 契约）。</li>
 *   <li><b>轮换公钥的 PEM 形态</b>：[U1]，本类提供 {@code stripPem} 兜底但未验证
 *       服务端到底给不给头。</li>
 *   <li><b>{@code code != 200}</b>：[U3]，上游不判断，本类同样不判断。</li>
 * </ol>
 *
 * <h2>记录的上游缺陷 / 硬编码异味（不修，改了服务端就不认）</h2>
 * <ol>
 *   <li><b>{@code dataIv} 名字误导</b>：它从来不当 IV 用，是与 {@code dataKey}
 *       同级的 AES-256 密钥（32 字符原文，不做 Base64 解码 —— 解码后长度不是
 *       16/24/32，JCE 必抛）。</li>
 *   <li><b>输出格式随 mode 变</b>：{@code AES/ECB} 输出 Base64，{@code AES/CBC}
 *       输出小写 hex —— 同一个方法两种编码，是硬编码分支不是笔误。</li>
 *   <li><b>PKCS7Padding</b>：Android/BouncyCastle 扩展名，标准 JCE 不认；
 *       本移植统一改 {@code PKCS5Padding}（AES 分组恒 16 字节，两者等价）。</li>
 *   <li><b>{@code f(int)} 的伪装 Base64</b>：返回长度恒为 {@code len} 且末位永远是
 *       {@code '='}（{@code len-1} 个随机字符 + {@code '='}），刻意模仿 padding。</li>
 *   <li><b>{@code sig2/sig3} 固定 8 字符切点</b>：把 44 字符的 Base64 密文硬切成
 *       8 + 36 两段塞进两个字段，纯混淆。</li>
 *   <li><b>{@code commonKey} 是硬编码常量而且恰好等于"苹果"那站的 ext.commonKey</b>：
 *       四站共用同一常量，ext 里的 publicKey/dataKey 对部分站是多余字段。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/AppDrama.java}（{@code a/b/f/h/i} 原语、
 * {@code c()/d()/e()/g(map)} 报文构造、{@code init} 的 zone 握手）；
 * 业务字段号来自 {@code re/spider.dex} 内嵌 FileDescriptorProto。
*/
public final class AppDramaPlain {

    // ─────────────────────────────────────────────────────────────────
    // 常量：全部为静态观测到源码里的字面量
    // ─────────────────────────────────────────────────────────────────

    /**
     * 硬编码 commonKey，AES-128（16 字符）。
     * 证据 ev_90f178225f94110b51146bf4ba1fb6275e164448032248579b4c2d156b9f2678
     * dex 原始字节核验：classes.dex@1125112 = "ed5fdsgucxumegqa"
     * 用途：JSON 通道 AES/CBC（key=iv=本值）；与 protobuf 通道共用同一常量但角色不同。
     */
    public static final String COMMON_KEY = "ed5fdsgucxumegqa";

    /** ext 里「天堂」那站的 dataKey，32 字符 = AES-256 */
    public static final String TT_DATA_KEY = "D2KREWRIBVNAUJJYEDL4VFHOY2Q0PQ==";
    /** ext 里「天堂」那站的 dataIv，32 字符 = AES-256（不是 IV！） */
    public static final String TT_DATA_IV  = "OC1A06E197EF10CF3F6058CA7A803B5E";

    /** ext 里「苹果」那站的 dataKey */
    public static final String PG_DATA_KEY = "S0VNDTJHOFHHCNMRAW5IV2TOS2PBPQ==";
    /** ext 里「橘汁」那站的 dataKey */
    public static final String JZ_DATA_KEY = "OW1WBLFZCLJ0WTNJQMRSZ2DYSGNBPT0=";
    /** ext 里「华谊」那站的 dataKey */
    public static final String HY_DATA_KEY = "NDBYSZR1DMRRZ05NSUCWEJNIYWLBPT0=";

    /** 天堂/苹果 共用的 RSA-1024 公钥（Base64 SubjectPublicKeyInfo）*/
    public static final String PUBKEY_TT =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCduNEnfxGaLuQRk5ABzXHhPV43zi00sCHjLo8BYc+Wi6xXm2b4v0i28Sq4WlNCKhseft9fz8kO/qLr6/022o1RcuOU7e4GFL3U9WnNODwRBYSYWd+K8nqpI/tAUDmZEBGRWqjrc7x6aMl3A+xpnWkLbPCLsuhbuuUE3tv09oeOpwIDAQAB";
    /** 橘汁 */
    public static final String PUBKEY_JZ =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCr8SzZhjYy+rsya1K09t8d2K50pWFoBkgUqMpKOiW+3IEVKd4eTdvg9RSOjQ82kypL6R9BnsmrS1V8s4PVDwjQbUtYhTPPC9Hz16qY7rpD6m0d2vr09/UpWQ5uOy9PR0QTrsioveZ+DIe9jc3C+zBCu/kZSY/R8stwJoiitki3gwIDAQAB";
    /** 华谊 */
    public static final String PUBKEY_HY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCp9Ek4wIlQAtwFnuBRlsFiow2tr+4UOciGeNKbY7nL74etUqUb6fvpOSOHhFEfaWlfwUpOB17x3JEL3No19nfjCeVYrYPjlJcgoqUWH/tfIfFAQWvtxBIBlKazkhw8d3ChysWmeWRikKqkBsVRY4oqNPuj4sjm6Zult0U4I4prRQIDAQAB";

    /** 随机串字符集，证据 ev_f288966f4e0e1971a7e8b4cfa73d1413282c5e045a841f257e9e60dcf854bad4 */
    private static final String RAND_CHARS =
        "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final SecureRandom RND = new SecureRandom();

    private AppDramaPlain() {}

    // ═════════════════════════════════════════════════════════════════
    // 一、随机串 f(int)
    // ═════════════════════════════════════════════════════════════════

    /**
     * 逐字复原 com.github.catvod.spider.AppDrama.f(int)。
     * 证据 ev_f288966f4e0e1971a7e8b4cfa73d1413282c5e045a841f257e9e60dcf854bad4
     *
     * 观测原文语义：
     *   for (i2 = 0; i2 < i - 1; i2++) sb.append(RAND_CHARS.charAt(random.nextInt(62)));
     *   sb.append("=");
     *   return sb.toString();
     *
     * ★ 重要细节：循环到 i-1，末尾再补一个 '='，所以「返回长度恒等于 i」，
     *   且最后一个字符永远是 '='。这一点很像 Base64 padding，是刻意伪装。
     *   例：f(16) → 15 个随机字符 + '='，共 16 字符。
     *
     * @param len 目标长度
     */
    public static String randomStr(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len - 1; i++) {
            sb.append(RAND_CHARS.charAt(RND.nextInt(RAND_CHARS.length())));
        }
        sb.append('=');
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════════
    // 二、AES 原语（与逆向一致，PKCS7→PKCS5 等价替换）
    // ═════════════════════════════════════════════════════════════════

    /**
     * AES/ECB 解密 —— 逐字复原 AppDrama.a(String,String)。
     * 证据 ev_4ed06b91769b88d968002cb75729f2b7679f30d0962cc1bd4c724715d92cc899
     *
     * 原实现: Cipher.getInstance("AES/ECB/PKCS7Padding"); init(2, key);
     *         new String(doFinal(Base64.decode(str,0)), UTF_8)
     *
     * @param cipherB64 Base64 密文
     * @param key       密钥原文（getBytes() 即字节；AppDrama 里传的是 32 字符 dataKey/dataIv）
     * @return 明文；失败返回 ""（与原实现 try/catch 返回空串的行为一致）
     */
    public static String aesEcbDecrypt(String cipherB64, String key) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding"); // PKCS7 在 AES 下等价
            c.init(Cipher.DECRYPT_MODE,
                   new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
            byte[] raw = Base64.getDecoder().decode(cipherB64);
            return new String(c.doFinal(raw), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * AES/ECB 加密 → Base64(NO_WRAP) —— 复原 b(str,key,"ECB",null) 的非 CBC 分支。
     * 证据 ev_0ac29a4aa3cbf8c552761f1c0d95ce4b5a1a3e1069ec17411421e3b32f709d18
     *
     * 用途：(1) protobuf 通道 g() 里 b(kv串+timestamp, dataKey, "ECB", null)
     *       (2) 测试里构造夹具，验证解密可逆
     */
    public static String aesEcbEncryptToB64(String plain, String key) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE,
                   new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
            return Base64.getEncoder()
                         .encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * AES/CBC 加密 → 小写 hex（无分隔）—— 复原 b(str,key,"CBC",iv) 的 CBC 分支。
     * 证据 ev_0ac29a4aa3cbf8c552761f1c0d95ce4b5a1a3e1069ec17411421e3b32f709d18
     *
     * ★ 输出格式与 ECB 分支不同：CBC 走 hex，ECB 走 Base64。这是源码里的显式分支。
     * 用途：JSON 通道 e()/c() 的 paramsData = b(json, commonKey, "CBC", commonKey)
     */
    public static String aesCbcEncryptToHex(String plain, String key, String iv) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE,
                   new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                   new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
            return toHex(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    /** 小写 hex，逐字等价 String.format("%02x", Byte.valueOf(b)) */
    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", Byte.valueOf(b)));
        }
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════════
    // 三、响应双层解密（dataKey → dataIv）
    // ═════════════════════════════════════════════════════════════════

    /**
     * 双层 AES/ECB 解密 —— 复原 homeVideoContent 里的
     *   data = a(a(data, f2f[0]), f2f[1])
     * 即 a(a(data, dataKey), dataIv)
     * 证据 ev_7ec34ae33eb204b576fc3386634dd4c0f989457138a46cc29aa048fdede41a04（调用点）
     *      ev_4ed06b91769b88d968002cb75729f2b7679f30d0962cc1bd4c724715d92cc899（原语）
     *
     * ★ 顺序不可交换：内层 dataKey 先解，外层 dataIv 后解。
     *   内层解出的是「Base64 文本」，外层才解出 JSON 明文。
     *
     * @param data    响应 ApiResult.data（第一层 Base64 密文）
     * @param dataKey ext.dataKey（32 字符，AES-256）
     * @param dataIv  ext.dataIv（32 字符，AES-256；名字骗人，它是第二层 key 不是 IV）
     * @return JSON 明文；任一环节失败返回 ""
     */
    public static String decryptData(String data, String dataKey, String dataIv) {
        String inner = aesEcbDecrypt(data, dataKey);   // a(data, dataKey)   → Base64 文本
        if (inner.isEmpty()) return "";
        return aesEcbDecrypt(inner, dataIv);           // a(inner, dataIv)   → 明文
    }

    /** ext.decrypt != "0" 时才解密（复原 `if (!this.g.equals("0"))`）*/
    public static String decryptDataIfEnabled(String data, String dataKey, String dataIv,
                                              String decryptFlag) {
        if ("0".equals(decryptFlag)) return data;
        return decryptData(data, dataKey, dataIv);
    }

    // ═════════════════════════════════════════════════════════════════
    // 四、RSA 上行签名（公钥加密，非解密、非验签）
    // ═════════════════════════════════════════════════════════════════

    /**
     * 逐字复原 AppDrama.i(String str, String publicKeyB64)。
     * 证据 ev_14c37da4891937f7f364902649c7058a150736cc726bfaa32059ed55718eebce
     *
     * ★ init(1, pk) —— 1 = ENCRYPT_MODE。这是「用公钥加密上行」，
     *   既不是「RSA 包裹 AES key」，也不是「验签」。RSA 公钥无法解密任何东西。
     *
     * 明文 = timestamp + randomStr（init）或 timestamp + randomStr + vApp（方法 c）
     *
     * @param plain        待加密明文
     * @param publicKeyB64 ext.publicKey（X509 SubjectPublicKeyInfo 的 Base64）
     * @return Base64(NO_WRAP) 的密文；失败返回 ""
     */
    public static String rsaSign(String plain, String publicKeyB64) {
        try {
            PublicKey pk = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(
                            Base64.getDecoder().decode(stripPem(publicKeyB64))));
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.ENCRYPT_MODE, pk);          // ★ 1 == ENCRYPT_MODE
            return Base64.getEncoder()
                         .encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * [U1] 兜底：允许传入带 PEM 头的公钥。
     * 原实现直接 Base64.decode(str2,0)，若服务器下发带 "-----BEGIN PUBLIC KEY-----"
     * 就会解不出来 —— 静态无法确认下发格式，故此处宽松处理。
     */
    static String stripPem(String pem) {
        if (pem == null) return "";
        return pem.replace("-----BEGIN PUBLIC KEY-----", "")
                  .replace("-----END PUBLIC KEY-----", "")
                  .replaceAll("\\s", "");
    }

    /** 公钥指纹/长度自检，便于判断 ext 里 publicKey 是否 1024-bit */
    public static int rsaKeyBits(String publicKeyB64) {
        try {
            PublicKey pk = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(
                            Base64.getDecoder().decode(stripPem(publicKeyB64))));
            java.security.interfaces.RSAPublicKey r = (java.security.interfaces.RSAPublicKey) pk;
            return r.getModulus().bitLength();
        } catch (Exception e) {
            return -1;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // 五、protobuf 手工编码（wire format，字段号来自 dex 内嵌 descriptor）
    // ═════════════════════════════════════════════════════════════════

    /** 复原 init() 里的 RSARequest.newBuilder()...build() */
    public static byte[] buildRsaRequest(long timestamp, String randomStr, String sign,
                                         String fake1, String fake2) {
        ProtoWriter w = new ProtoWriter();
        w.int64(1, timestamp);      // timestamp = 1
        w.string(2, sign);          // sign      = 2
        w.string(3, fake1);         // fake1     = 3
        w.string(4, randomStr);     // random_str= 4
        w.string(5, fake2);         // fake2     = 5
        return w.toByteArray();
    }

    /** 复原 g(HashMap) 里的 SecureRequest.newBuilder()...build() */
    public static byte[] buildSecureRequest(String aesEncrypt1, String aesEncrypt2,
                                            String aesFakestr, long timestamp,
                                            String randomStr) {
        ProtoWriter w = new ProtoWriter();
        w.string(1, aesEncrypt1);   // aes_encrypt1 = 1
        w.string(2, aesEncrypt2);   // aes_encrypt2 = 2
        w.string(3, aesFakestr);    // aes_fakestr  = 3
        w.int64(4, timestamp);      // timestamp    = 4
        w.string(5, randomStr);     // random_str   = 5
        return w.toByteArray();
    }

    /**
     * 复原 g(HashMap) 的完整逻辑：
     *   1) kv 串 = 按 map 迭代顺序 "k=v&k=v"，跳过 null/空值
     *   2) str3 = randomStr + b(kv串 + timestamp, dataKey, "ECB", null)
     *   3) aes_encrypt1 = str3[0,20) ； aes_encrypt2 = str3[20,)
     *   4) aes_fakestr = f(20) 的另一个随机串（纯混淆，服务端应忽略）
     * 证据 ev_8b25e1c25ec3cc962928bb4d32be067e6ed5035b8c1e9f02f8432b4c925586ba
     *
     * @param params  业务参数（需保持调用方给入的顺序，原实现依赖 HashMap 迭代序）
     * @param dataKey ext.dataKey（AES-256，注意此处不是 commonKey）
     */
    public static byte[] buildSecureRequestFromParams(Map<String, String> params,
                                                     String dataKey, long timestamp,
                                                     String randomStr, String fakeStr) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> en : params.entrySet()) {
            String v = en.getValue();
            if (v == null || v.isEmpty()) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(en.getKey()).append('=').append(v);
        }
        String enc = aesEcbEncryptToB64(sb.toString() + timestamp, dataKey);
        String str3 = randomStr + enc;
        String a1 = str3.length() >= 20 ? str3.substring(0, 20) : str3;
        String a2 = str3.length() >= 20 ? str3.substring(20) : "";
        return buildSecureRequest(a1, a2, fakeStr, timestamp, randomStr);
    }

    // ═════════════════════════════════════════════════════════════════
    // 六、请求头（两条通道）
    // ═════════════════════════════════════════════════════════════════

    /**
     * (A) JSON 通道请求头 —— 复原方法 e()（GET 用）
     * 证据 ev_ad7f8ec920a97aeddb0a0faadbf4548d377dd21175afeeb0fd2f0d885bb62ea1
     *
     * paramsData = b(公共参数JSON, commonKey, "CBC", commonKey)  → hex
     * 头: User-Agent=okhttp/3.12.1, Accept=application/json,
     *     Content-Type=application/json; charset=utf-8, publicParams={"paramsData":"<hex>"}
     */
    public static Map<String, String> jsonHeaders(String publicParamsJson, String commonKey) {
        String paramsData = aesCbcEncryptToHex(publicParamsJson, commonKey, commonKey);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", "okhttp/3.12.1");
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("publicParams", "{\"paramsData\":\"" + paramsData + "\"}");
        return h;
    }

    /**
     * (B) Protobuf 通道请求头 —— 复原方法 c()
     * 证据 ev_89fd4dc9d2667ab47b8289c05984bdd074524234b25f43c096d6c8a33f43fad5
     *
     * 公共参数 JSON 追加：
     *   sig    = i(timestamp + randomStr + vApp, keyB64)     ← RSA 公钥加密
     *   random_str, timestamp
     *   sig2   = hex(timestamp+randomStr 用 f2f[1]=dataIv AES/ECB) 的前 8 字符
     *   sig3   = 同一 hex 剩余部分
     * 然后 paramsData = b(该JSON, commonKey, "CBC", commonKey) → hex
     * 头: User-Agent=okhttp/3.12.1, Accept=application/x-protobuf,
     *     Content-Type=application/x-protobuf, publicParams={"paramsData":"<hex>"}
     *
     * ★ sig2/sig3 切割的密文是 (timestamp + randomStr)，用的是 dataIv（32 字符 → AES-256）。
     *   切点固定 8 字符，属硬编码。
     *
     * @param longKeyB64 this.d 非空则传 this.d，否则 ext.publicKey
     */
    public static Map<String, String> protoHeaders(long timestamp, String randomStr,
                                                   String vApp, String longKeyB64,
                                                   String commonKey, String dataIv) {
        String sig = rsaSign(timestamp + randomStr + vApp, longKeyB64);

        String sigHex = aesEcbEncryptToB64(timestamp + randomStr, dataIv);
        String sig2 = sigHex.length() >= 8 ? sigHex.substring(0, 8) : sigHex;
        String sig3 = sigHex.length() >= 8 ? sigHex.substring(8) : "";

        // 公共参数 JSON —— 键序沿用原 JSONObject 插入序
        StringBuilder j = new StringBuilder("{");
        j.append("\"sig\":\"").append(sig).append("\",");
        j.append("\"random_str\":\"").append(randomStr).append("\",");
        j.append("\"timestamp\":").append(timestamp).append(",");
        j.append("\"sig2\":\"").append(sig2).append("\",");
        j.append("\"sig3\":\"").append(sig3).append("\"");
        j.append("}");

        String paramsData = aesCbcEncryptToHex(j.toString(), commonKey, commonKey);

        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", "okhttp/3.12.1");
        h.put("Accept", "application/x-protobuf");
        h.put("Content-Type", "application/x-protobuf");
        h.put("publicParams", "{\"paramsData\":\"" + paramsData + "\"}");
        return h;
    }

    // ═════════════════════════════════════════════════════════════════
    // 七、公共参数 d()（设备指纹）
    // ═════════════════════════════════════════════════════════════════

    /**
     * 复原 d() —— 28 个字段的设备指纹。
     * 证据 ev_009b0a411570d39247fdc7d8f6d3a147f4de294ef7ca85afcb73082cec726571
     *
     * 其中 5 个是常量字符串（cpuId/mac/carrier 是 URL 编码原文，故意保留），
     * 3 个来自 Android Build（本移植版以入参代替），
     * androidID 来自 Settings.Secure。
     */
    public static Map<String, Object> publicParams(String version, String pkg, String appName,
                                                   String manufacturer, String model,
                                                   String brand, int sdkInt, String osRelease,
                                                   String androidId) {
        String uuid = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("country", "CN");
        m.put("vName", version);
        m.put("cpuId", "MT6893Z%2FCZA");
        m.put("young", 0);
        m.put("facturer", manufacturer);
        m.put("pkg", pkg);
        m.put("uuid", uuid);
        m.put("resolution", "1080x2272");
        m.put("mac", "02%3A00%3A00%3A00%3A00%3A00");
        m.put("abid", "397");
        m.put("model", model);
        m.put("plat", "android");
        m.put("udid", uuid);                 // 与 uuid 同值
        m.put("dpi", "440");
        m.put("net", "1");
        m.put("lang", "zh");
        m.put("brand", brand);
        m.put("density", "2.75");
        m.put("appName", appName);
        m.put("cpu", "arm64-v8a");
        m.put("chid", "10000");
        m.put("carrier", "%E8%81%94%E9%80%9A");   // "联通" 的 URL 编码
        m.put("_vOsCode", sdkInt);
        m.put("vOs", osRelease);
        m.put("v", 1);
        m.put("tenantId", "");
        m.put("vApp", version == null ? "" : version.replace(".", ""));
        m.put("device", 0);
        m.put("androidID", androidId);
        return m;
    }

    // ═════════════════════════════════════════════════════════════════
    // 八、极简 protobuf wire writer（无外部依赖）
    // ═════════════════════════════════════════════════════════════════

    static final class ProtoWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void string(int field, String v) {
            if (v == null) return;
            byte[] b = v.getBytes(StandardCharsets.UTF_8);
            tag(field, 2);
            varint(b.length);
            out.write(b, 0, b.length);
        }

        void int64(int field, long v) {
            tag(field, 0);
            varint(v);
        }

        private void tag(int field, int wire) { varint(((long) field << 3) | wire); }

        private void varint(long v) {
            while ((v & ~0x7FL) != 0) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
        }

        byte[] toByteArray() { return out.toByteArray(); }
    }

    // ═════════════════════════════════════════════════════════════════
    // 九、业务层骨架（加解密已完备，业务解析见 [U4]）
    // ═════════════════════════════════════════════════════════════════

    /** 站点配置 —— 明文常量，不再从 ext 下发 */
    public static final class Site {
        public final String name;
        public final String host;
        public final String publicKey;
        public final String dataKey;
        public final String dataIv;
        public final String pkg;
        public final String appName;

        public Site(String name, String host, String publicKey, String dataKey,
                    String dataIv, String pkg, String appName) {
            this.name = name; this.host = host; this.publicKey = publicKey;
            this.dataKey = dataKey; this.dataIv = dataIv;
            this.pkg = pkg; this.appName = appName;
        }
    }

    /** 四站内置表。host 为占位 [U2]，域名需热更时改这里或外部注入。 */
    public static final Site SITE_TIANTANG = new Site(
        "天堂", "[HOST_TIANTANG]", PUBKEY_TT, TT_DATA_KEY, TT_DATA_IV,
        "com.dytiantang.one", "电影天堂");
    public static final Site SITE_PINGGUO = new Site(
        "苹果", "[HOST_PINGGUO]", PUBKEY_TT, PG_DATA_KEY, null, "[PKG_PINGGUO]", "[APP_PINGGUO]");
    public static final Site SITE_JUZHI = new Site(
        "橘汁", "[HOST_JUZHI]", PUBKEY_JZ, JZ_DATA_KEY, null, "[PKG_JUZHI]", "[APP_JUZHI]");
    public static final Site SITE_HUAYI = new Site(
        "华谊", "[HOST_HUAYI]", PUBKEY_HY, HY_DATA_KEY, null, "[PKG_HUAYI]", "[APP_HUAYI]");

    /** 接口路径（静态观测） */
    public static final String PATH_ZONE     = "/api/v5/find/app/zone";
    public static final String PATH_CATEGORY = "/api/v3/drama/getCategory?orderBy=type_id";
    public static final String PATH_TAG_LIST = "/api/ex/v3/security/tag/list";
    public static final String PATH_CATEGORY_PROTO = "/api/proto/v5/drama/category";
    public static final String PATH_SEARCH_PROTO   = "/api/proto/v5/drama/search";
    public static final String PATH_DETAIL_PROTO   = "/api/proto/v5/drama/getDetail";
    public static final String PATH_PLAY_PROTO     = "/api/proto/v5/videoUsableUrl";

    /**
     * categoryContent 的参数构造，逐字复原（含默认值）。
     * 证据 ev_8a9a09527b0ed4060c924d980b2f3e454a930f0aa070fd9ba7f452d5e8ddc1ba
     */
    public static Map<String, String> categoryParams(String typeId, String page,
                                                    Map<String, String> extend) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("pagesize", "21");
        p.put("typeId1", typeId);
        p.put("page", page);
        p.put("vodOrderBy", get(extend, "extend_sort", "最新"));
        p.put("vodArea",   get(extend, "area", ""));
        p.put("vodLang",   get(extend, "lang", ""));
        p.put("vodClass",  get(extend, "class", ""));
        p.put("vodYear",   get(extend, "year", ""));
        return p;
    }

    /**
     * searchContent 的参数构造，逐字复原。
     * 证据 ev_4fd32475e2a86b9e6b10a37c9248f813b298462dfc56e55547ba8ae5e85481f4
     * 两参重载默认 page = "1"（证据 ev_917e5d16599fb555168d72a66408240007be0ea6c5a0a238c1320b90a8a686a7）
     */
    public static Map<String, String> searchParams(String key, String page) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("searchKeys", key);
        p.put("page", page == null ? "1" : page);
        p.put("pagesize", "21");
        return p;
    }

    /** detailContent 的参数 */
    public static Map<String, String> detailParams(String id) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("id", id);
        return p;
    }

    /** playerContent 的参数（从 Base64 的 JSON 里平铺所有键） */
    public static Map<String, String> playParams(Map<String, String> b64JsonFields) {
        return new LinkedHashMap<>(b64JsonFields);
    }

    private static String get(Map<String, String> m, String k, String def) {
        if (m == null) return def;
        String v = m.get(k);
        return v == null ? def : v;
    }

    /** 判 URL 是否直链视频，正则逐字复原（detailContent / playerContent 各出现一次）*/
    private static final java.util.regex.Pattern VIDEO_URL = java.util.regex.Pattern.compile(
        "(?i).*\\.(mp4|m3u8|flv|mkv|avi|ts|mov|mpd|m4a|wmv)(\\?.*)?$");

    public static boolean isVideoUrl(String url) {
        return url != null && VIDEO_URL.matcher(url).matches();
    }

    /**
     * detailContent 里「非直链 → 包成 Base64 JSON」的转换，逐字复原：
     *   {vodPlayFrom: source, playUrl: path} 的 JSON 串 → Base64(NO_WRAP)
     * 证据 ev_82ed5664390b87419fd5d77b727f8d707542eaf6a0185112fc235937298ee006
     */
    public static String wrapPlayUrl(String source, String path) {
        String json = "{\"vodPlayFrom\":\"" + esc(source) + "\",\"playUrl\":\"" + esc(path) + "\"}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** playerContent 里反向解包 */
    public static Map<String, String> unwrapPlayUrl(String b64) {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            String json = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            // 极简 JSON 对象扁平化，仅接受字符串值
            java.util.regex.Matcher mm = java.util.regex.Pattern
                .compile("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
            while (mm.find()) m.put(mm.group(1), unesc(mm.group(2)));
        } catch (Exception ignored) {}
        return m;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    default:  sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }
}
