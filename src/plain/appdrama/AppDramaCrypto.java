package com.example.tvbox.plain.appdrama;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AppDrama 加密层 —— 全部逐字还原自静态反编译，并已通过编译期 + 运行期验证。
 *
 * <p>服务 4 个站点：天堂 / 苹果 / 苹果2 / 橘汁 / 华谊
 *
 * <h2>机制总览（与直觉相反，务必先读）</h2>
 * <pre>
 *  出站请求：RSA/ECB/PKCS1 用 publicKey 加密「签名字符串」→ 放进 protobuf 的 sign 字段
 *            ↑ 这是防伪造，不是内容加密。没有私钥，无法逆向。
 *
 *  入站响应：AES/ECB 用 ext 的 dataKey / dataIv 双层解密
 *            ↑ 注意: dataKey / dataIv 是**32 字符原文**当 AES-256 key 直接用，
 *              绝不能 Base64 解码（解码后 22 字节，非法长度）
 *
 *  请求体加密：AES/ECB 用硬编码 commonKey ("ed5fdsgucxumegqa")
 *            ↑ 这个才是源码内置常量，仅用于上行
 * </pre>
 *
 * <h2>证据链</h2>
 * <ul>
 *   <li>AES 解密: {@code inspect_android_method(AppDrama.a)}
 *       ev_4ed06b91769b88d968002cb75729f2b7679f30d0962cc1bd4c724715d92cc899
 *       → {@code AES/ECB/PKCS7Padding}, init(2, key)</li>
 *   <li>RSA 签名: {@code inspect_android_method(AppDrama.i)}
 *       ev_14c37da4891937f7f364902649c7058a150736cc726bfaa32059ed55718eebce
 *       → {@code RSA/ECB/PKCS1Padding}, init(1, pub) ← 1 = ENCRYPT</li>
 *   <li>AES 加密(请求体): {@code inspect_android_method(AppDrama.b)}
 *       ev_0ac29a4aa3cbf8c552761f1c0d95ce4b5a1a3e1069ec17411421e3b32f709d18
 *       → CBC 时输出 hex，ECB 时输出 Base64</li>
 *   <li>随机串: {@code inspect_android_method(AppDrama.f)}
 *       ev_7f7893165d5c6c0a80e4bf158017d091644feaf883e3033abd32eec428cb81c1
 *       → 字符集 {@code 1234567890A-Za-z}，生成 i-1 个字符后追加 '='</li>
 *   <li>init 的字段绑定: {@code inspect_android_method(AppDrama.init)}
 *       ev_90f178225f94110b51146bf4ba1fb6275e164448032248579b4c2d156b9f2678</li>
 * </ul>
 *
 * <h2>关于 AES_KEY 的长度（勘误记录）</h2>
 * 本项目早期曾误判「jadx 丢字符」，实际是**人工比对时手敲错**，不是工具缺陷。
 * 事实：MCP 与本地 jadx 输出一致，均为 {@code "ed5fdsgucxumegqa"}（16 字符）；
 * DEX 字符串池亦为同一值。15 字符并非工具产物。
 *
 * <p>保留此记录是因为它证明了一条真规则：
 * <b>「长度敏感」常量必须校验长度合法性</b>（AES 只接受 16/24/32）。
 * 15 字节会被 JCE 直接拒绝，这个检查本身就是最可靠的防线。
 */
public final class AppDramaCrypto {

    // ─────────────────────────────────────────────────────────────
    // 常量（已回 DEX 字符串池核对，勿轻改）
    // ─────────────────────────────────────────────────────────────

    /**
     * 请求体加密用的 commonKey —— 硬编码在 AppDrama.init() 的 {@code this.c}。
     * <p>16 字符，与 ext 里苹果的 {@code commonKey} 相同。
     * <p><b>用途限于上行</b>：{@code encryptRequest()} 用它。
     * 响应解密用的是 ext 的 dataKey/dataIv，不是它。
     */
    public static final String AES_KEY = "ed5fdsgucxumegqa";

    /** 随机串字符集，来自 AppDrama.f(int) */
    private static final String RANDOM_CHARSET =
            "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    // 算法串（DEX 中均确认存在）
    private static final String TRANS_AES_ECB = "AES/ECB/PKCS5Padding"; // Android 侧为 PKCS7
    private static final String TRANS_AES_CBC = "AES/CBC/PKCS5Padding";
    private static final String TRANS_RSA     = "RSA/ECB/PKCS1Padding";

    private static final SecureRandom RNG = new SecureRandom();

    private AppDramaCrypto() {}

    // ─────────────────────────────────────────────────────────────
    // 响应解密（核心）
    // ─────────────────────────────────────────────────────────────

    /**
     * 解密上游响应。对应 {@code AppDrama.a(String, String)}。
     *
     * <p><b>注意</b>：AppDrama 实际调用时 key 传的是 ext 的 {@code dataKey}，
     * 不是本类的 {@link #AES_KEY}。{@link #AES_KEY} 只用于请求体加密。
     *
     * <pre>
     * 原实现:
     *   Cipher c = Cipher.getInstance("AES/ECB/PKCS7Padding");
     *   c.init(2, new SecretKeySpec(str2.getBytes(), "AES"));
     *   return new String(c.doFinal(Base64.decode(str, 0)), UTF_8);
     * </pre>
     *
     * @param cipherText Base64 密文
     * @param key        16 字节密钥（通常传 {@link #AES_KEY}）
     * @return 明文；失败返回空串
     */
    public static String decryptResponse(String cipherText, String key) {
        try {
            Cipher c = Cipher.getInstance(TRANS_AES_ECB);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
            byte[] raw = Base64.getMimeDecoder().decode(cipherText);
            return new String(c.doFinal(raw), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 使用内置 {@link #AES_KEY} 解密响应。
     *
     * @deprecated <b>对 AppDrama 语义错误</b>。真实响应是用 ext 的
     *   {@code dataKey}/{@code dataIv} 双层解密的，{@link #AES_KEY} 只用于上行。
     *   仅为兼容保留；新代码请显式调用 {@code decryptResponse(ct, ext.dataKey)}。
     */
    @Deprecated
    public static String decryptResponse(String cipherText) {
        return decryptResponse(cipherText, AES_KEY);
    }

    // ─────────────────────────────────────────────────────────────
    // 请求加密
    // ─────────────────────────────────────────────────────────────

    /**
     * 加密请求体。对应 {@code AppDrama.b(String 明文, String key, String mode, String iv)}。
     *
     * <p>注意原实现对不同 mode 的输出编码不同：
     * <ul>
     *   <li>{@code mode="CBC"} → 输出 <b>hex</b>（小写，2 位/字节）</li>
     *   <li>其他（ECB）        → 输出 <b>Base64</b>（NO_WRAP）</li>
     * </ul>
     *
     * @param plain 明文
     * @param key   AES 密钥（16/24/32 字节文本）
     * @param mode  "CBC" 或其他（ECB）
     * @param iv    CBC 时的 IV 文本；ECB 时忽略
     * @return 密文（CBC=hex，ECB=Base64）
     */
    public static String encryptRequest(String plain, String key, String mode, String iv) {
        try {
            boolean cbc = "CBC".equals(mode);
            Cipher c = Cipher.getInstance(cbc ? TRANS_AES_CBC : TRANS_AES_ECB);
            SecretKeySpec ks = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            if (cbc) {
                c.init(Cipher.ENCRYPT_MODE, ks, new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
            } else {
                c.init(Cipher.ENCRYPT_MODE, ks);
            }
            byte[] out = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return cbc ? toHex(out) : Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 生成随机串。对应 {@code AppDrama.f(int len)}。
     *
     * <pre>
     * 原实现: 循环 len-1 次取随机字符，最后 append "="
     * </pre>
     * 注意结尾那个 '=' 是原实现的固定行为，不是笔误 —— 它让随机串看起来像 Base64。
     *
     * @param len 目标长度（≥1）
     */
    public static String randomString(int len) {
        StringBuilder sb = new StringBuilder(Math.max(len, 1));
        for (int i = 0; i < len - 1; i++) {
            sb.append(RANDOM_CHARSET.charAt(RNG.nextInt(RANDOM_CHARSET.length())));
        }
        sb.append('=');
        return sb.toString();
    }

    /**
     * RSA 加密签名串。对应 {@code AppDrama.i(String plain, String publicKeyBase64)}。
     *
     * <pre>
     * 原实现:
     *   PublicKey pk = KeyFactory.getInstance("RSA")
     *       .generatePublic(new X509EncodedKeySpec(Base64.decode(pub, 0)));
     *   Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
     *   c.init(1, pk);                       // 1 = ENCRYPT_MODE
     *   return Base64.encodeToString(c.doFinal(plain.getBytes()), 2);
     * </pre>
     *
     * <p><b>没有私钥，此操作不可逆。</b> 它的用途是让服务端验证请求确实来自官方 App。
     *
     * @param plain           待加密的签名字符串（原实现为 {@code timestamp + randomStr}）
     * @param publicKeyBase64 X.509 DER 公钥的 Base64（来自 ext.publicKey）
     * @return Base64 密文；失败返回空串
     */
    public static String rsaSign(String plain, String publicKeyBase64) {
        try {
            byte[] der = Base64.getMimeDecoder().decode(publicKeyBase64);
            PublicKey pk = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            Cipher c = Cipher.getInstance(TRANS_RSA);
            c.init(Cipher.ENCRYPT_MODE, pk);
            return Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────────

    /** 字节数组转小写 hex，与 {@code String.format("%02x", b)} 逐字节等价 */
    public static String toHex(byte[] bytes) {
        char[] HEX = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(out);
    }
}
