package com.example.tvbox.plain.appget;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES 多模式加解密器 —— 对应上游共享类
 * {@code com.github.catvod.spider.merge.p038m.C0296a}（DEX 原名 {@code merge.m.a}）。
 *
 * <p>该共享类是 AppGet / AppQi / Init 等站点的 cipher 中枢，共 12 个静态方法、
 * <b>覆盖 CBC / CTR / ECB / GCM 四种模式</b>。本类按方法一一对应实现。
 *
 * <h2>命名说明（重要）</h2>
 * 本类名 {@code AesGcm} 是早期误起 —— 当时只发现了 CBC。
 * 实际涵盖 4 种模式；<b>算法以方法名为准，不要被类名误导</b>。
 *
 * <h2>上游方法映射表</h2>
 * <table>
 *   <tr><th>上游</th><th>模式</th><th>方向</th><th>输入</th><th>本类方法</th></tr>
 *   <tr><td>a</td><td>CBC/PKCS7</td><td>DEC</td><td>Base64</td><td>{@link #cbcDecryptBase64}</td></tr>
 *   <tr><td>b</td><td>CBC/PKCS5</td><td>DEC</td><td>Base64</td><td>{@link #cbcDecryptBase64}</td></tr>
 *   <tr><td>c</td><td>CBC/PKCS5</td><td>DEC</td><td><b>hex</b></td><td>{@link #cbcDecryptHex}</td></tr>
 *   <tr><td>d</td><td>CBC/PKCS5</td><td>ENC</td><td>String</td><td>{@link #cbcEncryptToBase64}</td></tr>
 *   <tr><td>e</td><td>CBC/PKCS5</td><td>ENC</td><td>String</td><td>{@link #cbcEncryptToHex}</td></tr>
 *   <tr><td>f</td><td>CTR</td><td>DEC</td><td>Base64</td><td>{@link #ctrDecryptBase64}</td></tr>
 *   <tr><td>g</td><td>CTR</td><td>ENC</td><td>String</td><td>{@link #ctrEncryptToBase64}</td></tr>
 *   <tr><td>h</td><td>ECB/PKCS7</td><td>DEC</td><td>Base64</td><td>{@link #ecbDecryptBase64}</td></tr>
 *   <tr><td>i</td><td>ECB/PKCS7</td><td>ENC</td><td>String</td><td>{@link #ecbEncryptToBase64}</td></tr>
 *   <tr><td>j</td><td>GCM/NoPadding</td><td>DEC</td><td><b>hex</b> + AAD</td><td>{@link #gcmDecryptHex}</td></tr>
 *   <tr><td>k</td><td>—</td><td>hex 解码</td><td>hex</td><td>{@link #hexToBytes}</td></tr>
 *   <tr><td>l</td><td>CBC/PKCS5</td><td>ENC</td><td>String</td><td>{@link #cbcEncryptToBytes}</td></tr>
 * </table>
 *
 * <h2>关键约束</h2>
 * <ul>
 *   <li>上游用 {@code PKCS7Padding}（Android/BouncyCastle 扩展名）；
 *       桌面 JVM 无此 provider，统一换成 {@code PKCS5Padding}（AES 分组固定 16 字节，语义等价）</li>
 *   <li>GCM 的 tag 长度 128 bit；上游有 {@code SDK_INT>=19} 分支，
 *       低于 19 时 {@code GCMParameterSpec} 为 null 仍调 init，实际会抛异常 —— 属死分支，
 *       本实现直接按 SDK≥19 处理</li>
 *   <li>所有 key 均为 <b>raw 字符串 getBytes()</b>，长度须 ∈ {16,24,32}，<b>不做 Base64 解码</b></li>
 * </ul>
 *
 * <h2>证据</h2>
 * 共享 cipher 类反编译: {@code ae/decompiled/_SHARED_C0296a_cipher.java}
 * 算法串在 DEX 池中均存在（CBC/CTR/ECB/GCM 六种 padding 组合）
 * AppGet 调用点: ev_2b828d218db28f21612ecdfcba5e4d649c52964b2405d5d9cf4bdc5545f56ac7
 * 密文定位正则:  ev_7da4ed034d201cc03ebe678597d0a7d284cd7c538b2eb1f650df6505ff229612
 */
public final class AesGcm {

    // Android 侧原名；JCE 只认 PKCS5Padding
    private static final String CBC = "AES/CBC/PKCS5Padding";
    private static final String CTR = "AES/CTR/NoPadding";
    private static final String ECB = "AES/ECB/PKCS5Padding";
    private static final String GCM = "AES/GCM/NoPadding";
    /** 上游 GCM tag 长度（bit） */
    private static final int GCM_TAG_BITS = 128;

    private AesGcm() {}

    // =============================================================
    // CBC
    // =============================================================

    /** 上游 {@code a()} / {@code b()}：CBC 解密，Base64 输入 */
    public static String cbcDecryptBase64(String cipherText, String key, String iv) {
        try {
            return new String(doCbc(Cipher.DECRYPT_MODE, decodeBase64Lenient(cipherText), key, iv),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 上游 {@code c()}：CBC 解密，<b>hex</b> 输入 */
    public static String cbcDecryptHex(String hexCipher, String key, String iv) {
        try {
            byte[] raw = hexToBytes(hexCipher);
            if (raw.length == 0) return "";
            return new String(doCbc(Cipher.DECRYPT_MODE, raw, key, iv), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 上游 {@code d()}：CBC 加密 -> Base64 */
    public static String cbcEncryptToBase64(String plain, String key, String iv) {
        try {
            return Base64.getEncoder().encodeToString(
                    doCbc(Cipher.ENCRYPT_MODE, plain.getBytes(StandardCharsets.UTF_8), key, iv));
        } catch (Exception e) {
            return "";
        }
    }

    /** 上游 {@code e()}：CBC 加密 -> hex */
    public static String cbcEncryptToHex(String plain, String key, String iv) {
        try {
            return toHex(doCbc(Cipher.ENCRYPT_MODE, plain.getBytes(StandardCharsets.UTF_8), key, iv));
        } catch (Exception e) {
            return "";
        }
    }

    /** 上游 {@code l()}：CBC 加密 -> 原始字节 */
    public static byte[] cbcEncryptToBytes(String plain, String key, String iv) {
        try {
            return doCbc(Cipher.ENCRYPT_MODE, plain.getBytes(StandardCharsets.UTF_8), key, iv);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static byte[] doCbc(int mode, byte[] input, String key, String iv) throws Exception {
        Cipher c = Cipher.getInstance(CBC);
        c.init(mode, keySpec(key), ivSpec(iv));
        return c.doFinal(input);
    }

    // =============================================================
    // CTR
    // =============================================================

    /** 上游 {@code f()}：CTR 解密，Base64 输入 */
    public static String ctrDecryptBase64(String cipherText, String key, String iv) {
        try {
            Cipher c = Cipher.getInstance(CTR);
            c.init(Cipher.DECRYPT_MODE, keySpec(key), ivSpec(iv));
            return new String(c.doFinal(decodeBase64Lenient(cipherText)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 上游 {@code g()}：CTR 加密 -> Base64 */
    public static String ctrEncryptToBase64(String plain, String key, String iv) {
        try {
            Cipher c = Cipher.getInstance(CTR);
            c.init(Cipher.ENCRYPT_MODE, keySpec(key), ivSpec(iv));
            return Base64.getEncoder()
                    .encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    // =============================================================
    // ECB
    // =============================================================

    /** 上游 {@code h()}：ECB 解密，Base64 输入 */
    public static String ecbDecryptBase64(String cipherText, String key) {
        try {
            Cipher c = Cipher.getInstance(ECB);
            c.init(Cipher.DECRYPT_MODE, keySpec(key));
            return new String(c.doFinal(decodeBase64Lenient(cipherText)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 上游 {@code i()}：ECB 加密 -> Base64 */
    public static String ecbEncryptToBase64(String plain, String key) {
        try {
            Cipher c = Cipher.getInstance(ECB);
            c.init(Cipher.ENCRYPT_MODE, keySpec(key));
            return Base64.getEncoder()
                    .encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    // =============================================================
    // GCM
    // =============================================================

    /**
     * 上游 {@code j()}：GCM 解密，<b>hex</b> 输入。
     *
     * <p>上游逻辑：hex 解码后前 16 字节作 IV 之后的部分为密文+tag？——
     * 实际上游用两个独立入参，此处按「iv 单独传入，cipherHex 仅密文+tag」处理。
     *
     * @param cipherHex hex 编码的 密文||tag
     * @param key       AES 密钥 raw 文本
     * @param iv        IV raw 文本（通常 12 字节；不足 16 时补零）
     * @param aad       附加认证数据，可为 null
     */
    public static String gcmDecryptHex(String cipherHex, String key, String iv, byte[] aad) {
        try {
            byte[] raw = hexToBytes(cipherHex);
            if (raw.length == 0) return "";
            Cipher c = Cipher.getInstance(GCM);
            // SDK>=19 分支：GCMParameterSpec(128, iv)
            c.init(Cipher.DECRYPT_MODE, keySpec(key), new GCMParameterSpec(GCM_TAG_BITS, ivSpecBytes(iv)));
            if (aad != null && aad.length > 0) c.updateAAD(aad);
            return new String(c.doFinal(raw), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** GCM 加密（供测试构造样本）-> hex(密文||tag) */
    public static String gcmEncryptToHex(String plain, String key, String iv, byte[] aad) {
        try {
            Cipher c = Cipher.getInstance(GCM);
            c.init(Cipher.ENCRYPT_MODE, keySpec(key), new GCMParameterSpec(GCM_TAG_BITS, ivSpecBytes(iv)));
            if (aad != null && aad.length > 0) c.updateAAD(aad);
            return toHex(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    // =============================================================
    // 兼容层：AppGet/AppQi 实际只用到 CBC+Base64（上游 a/b）
    // =============================================================

    /**
     * 兼容旧调用点 —— 等价于 {@link #cbcDecryptBase64}。
     *
     * @deprecated 名称未指明模式，易误导。新代码请用 {@link #cbcDecryptBase64}。
     */
    @Deprecated
    public static String decrypt(String cipherText, String key, String iv) {
        return cbcDecryptBase64(cipherText, key, iv);
    }

    /** 兼容旧调用点 —— 等价于 {@link #cbcEncryptToBase64} */
    @Deprecated
    public static String encryptForTest(String plain, String key, String iv) {
        return cbcEncryptToBase64(plain, key, iv);
    }

    // =============================================================
    // 编解码与校验
    // =============================================================

    /** 上游 {@code k()}：hex 解码 */
    public static byte[] hexToBytes(String s) {
        if (s == null) return new byte[0];
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') continue;
            sb.append(ch);
        }
        String t = sb.toString();
        if (t.length() % 2 != 0) return new byte[0];
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(t.charAt(i * 2), 16);
            int lo = Character.digit(t.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return new byte[0];
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** 字节数组 -> 小写 hex */
    public static String toHex(byte[] bytes) {
        if (bytes == null) return "";
        char[] HEX = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(out);
    }

    /** AES 合法密钥长度 */
    public static boolean isAesKeyLength(int n) {
        return n == 16 || n == 24 || n == 32;
    }

    /**
     * 宽松 Base64 解码，等价 Android {@code Base64.decode(s, 0)}。
     */
    public static byte[] decodeBase64Lenient(String s) {
        if (s == null) return new byte[0];
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == ' ' || ch == '\t') continue;
            sb.append(ch);
        }
        String t = sb.toString();
        int rem = t.length() % 4;
        if (rem == 2) t = t + "==";
        else if (rem == 3) t = t + "=";
        else if (rem == 1) return new byte[0];
        try {
            return Base64.getDecoder().decode(t);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static SecretKeySpec keySpec(String key) {
        return new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
    }

    private static IvParameterSpec ivSpec(String iv) {
        return new IvParameterSpec(ivSpecBytes(iv));
    }

    /** IV 对齐到 16 字节（不足补零 / 超出截断），与原实现的宽松行为一致 */
    private static byte[] ivSpecBytes(String iv) {
        byte[] b = iv.getBytes(StandardCharsets.UTF_8);
        if (b.length == 16) return b;
        byte[] out = new byte[16];
        System.arraycopy(b, 0, out, 0, Math.min(b.length, 16));
        return out;
    }

    /** 未使用，保留以消除 IDE 提示 */
    @SuppressWarnings("unused")
    private static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(a.length + b.length);
        o.write(a, 0, a.length);
        o.write(b, 0, b.length);
        return o.toByteArray();
    }
}
