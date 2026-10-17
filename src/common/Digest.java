package com.example.tvbox.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 散列与消息认证码工具 —— 覆盖上游 6 个类共用的 MD5/SHA/Hmac 需求。
 *
 * <h2>为什么单独抽出来</h2>
 * 上游 {@code AppRJ} / {@code QiShui} / {@code Yidong4K} / {@code ShortWeiguan} /
 * {@code XunleiPan} / {@code Init} 六个类各自内联了一份几乎相同的 MD5 实现，
 * 且都带同一个「补零到 32 位」的循环。抽成公共类消除重复。
 *
 * <h2>上游原始实现（逐字）</h2>
 * <pre>
 *  // AppRJ.a(String)
 *  StringBuilder sb = new StringBuilder(
 *      new BigInteger(1, MessageDigest.getInstance("MD5").digest(str.getBytes("UTF-8")))
 *          .toString(16));
 *  while (sb.length() &lt; 32) sb.insert(0, "0");
 *  return sb.toString().toLowerCase();
 * </pre>
 *
 * <p>注意上游这个写法有两个隐患，本实现已规避：
 * <ol>
 *   <li>{@code BigInteger.toString(16)} 会丢掉前导零 —— 上游用 while 循环补，
 *       但只在长度 &lt; 32 时补，属"事后补救"</li>
 *   <li>{@code toString(16)} 对负数会带符号，靠 {@code BigInteger(1, ...)} 规避</li>
 * </ol>
 * 本实现直接按字节格式化，天然无前导零问题。
 */
public final class Digest {

    private Digest() {}

    /** MD5 → 32 位小写 hex（与上游 AppRJ.a() 等价，但无前导零隐患） */
    public static String md5(String input) {
        return hexDigest("MD5", input);
    }

    /** MD5 → 32 位小写 hex，输入为字节 */
    public static String md5(byte[] input) {
        return hexDigest("MD5", input);
    }

    /** SHA-1 → 40 位小写 hex */
    public static String sha1(String input) {
        return hexDigest("SHA-1", input);
    }

    /** SHA-256 → 64 位小写 hex */
    public static String sha256(String input) {
        return hexDigest("SHA-256", input);
    }

    /** SHA-256，输入为字节 */
    public static String sha256(byte[] input) {
        return hexDigest("SHA-256", input);
    }

    /** HmacSHA1 → hex */
    public static String hmacSha1(String data, String key) {
        return hmac("HmacSHA1", data, key);
    }

    /** HmacSHA256 → hex */
    public static String hmacSha256(String data, String key) {
        return hmac("HmacSHA256", data, key);
    }

    /**
     * 通用字节摘要 → hex。
     * <p>任何异常返回空串（与上游「失败返空」的约定一致）。
     */
    public static String hexDigest(String algorithm, String input) {
        if (input == null) return "";
        return hexDigest(algorithm, input.getBytes(StandardCharsets.UTF_8));
    }

    public static String hexDigest(String algorithm, byte[] input) {
        if (input == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return toHex(md.digest(input));
        } catch (Exception e) {
            return "";
        }
    }

    /** 通用 Hmac → hex */
    public static String hmac(String algorithm, String data, String key) {
        if (data == null || key == null) return "";
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), algorithm));
            return toHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    /** 字节数组 → 小写 hex（不丢前导零） */
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

    /**
     * 还原上游「BigInteger.toString(16) + 补零」的等价结果。
     * <p>仅供需要与上游逐字节对齐的场合；正常情况下用 {@link #md5(String)} 即可。
     */
    public static String md5LegacyStyle(String input) {
        if (input == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
            String s = new java.math.BigInteger(1, d).toString(16);
            while (s.length() < 32) s = "0" + s;
            return s.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }
}
