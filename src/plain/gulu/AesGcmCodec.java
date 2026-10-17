package com.example.tvbox.plain.gulu;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;

/**
 * AES/GCM/NoPadding 编解码器（Gulu 协议的第 3 层，最外层）。
 *
 * <h2>★ GCM 与 CBC 的本质区别 —— 为什么这里能当"签名是否正确"的探针</h2>
 * CBC 只做混淆：<b>密钥错、IV 错、密文被篡改，都只会解出乱码，不会报错</b>
 * （用 PKCS5 填充时还能靠末尾填充字节碰运气地报一次 BadPaddingException）。
 * GCM 是 AEAD：密文尾部带 16 字节（128 位）认证标签，解密时先验标签，
 * <b>密钥错 / IV 错 / 密文被改 / 标签被改，一律抛
 * {@link javax.crypto.AEADBadTagException}</b>。
 *
 * <p>因此 Gulu 的整条会话密钥推导链（ECDH → HMAC → session key）是<b>可证伪</b>的：
 * 只要客户端与服务端的推导不一致，第一次 {@code callApi} 解密响应就会立刻抛
 * {@code AEADBadTagException}，而不会静默解出乱码。GuluTest 里专门用这条性质
 * 做了「错 key / 改 tag / 改 IV」三组探针断言。
 *
 * <h2>线格式</h2>
 * 上游把 12 字节 nonce <b>拼在密文前面</b>：
 * <pre>
 *   wire = nonce(12) || ciphertext(与明文等长) || tag(16)
 * </pre>
 * 所以最短合法长度是 {@code 12 + 0 + 16 = 28} 字节 —— 上游正是用
 * {@code asBytes.length >= 28} 做长度门槛（{@code Gulu.java:194}）。
 * nonce 长度 12 而不是 16：GCM 的标准 IV 长度，上游 {@code c(12)} 明确要 12 字节。
 *
 * <h2>证据来源</h2>
 * 反编译原文: {@code ae/decompiled/Gulu.java:173-178}（加密）、{@code 195-197}（解密）、
 * {@code 194}（28 字节门槛）、{@code 172}（nonce 12 字节）、{@code 174}（tag 128 位）。
 */
public final class AesGcmCodec {

    /** 认证标签长度（位）—— 上游固定 128。 */
    public static final int TAG_BITS = 128;
    /** 认证标签长度（字节）。 */
    public static final int TAG_LENGTH = TAG_BITS / 8;
    /** nonce（IV）长度 —— 上游固定 12。 */
    public static final int NONCE_LENGTH = 12;
    /** 线上最短合法长度 = nonce + tag。 */
    public static final int MIN_WIRE_LENGTH = NONCE_LENGTH + TAG_LENGTH;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private AesGcmCodec() {
    }

    /**
     * 加密（不含 nonce 拼接）。
     *
     * @param key   密钥，长度须为 16/24/32
     * @param nonce 12 字节 nonce
     * @param plain 明文，null 视作空
     * @return {@code ciphertext || tag}；长度 = 明文长度 + 16
     * @throws java.security.GeneralSecurityException 参数非法（如 key 长度错、nonce 长度错）
     */
    public static byte[] encrypt(byte[] key, byte[] nonce, byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance(TRANSFORMATION);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        return c.doFinal(plain == null ? new byte[0] : plain);
    }

    /**
     * 解密（不含 nonce 拼接）。
     *
     * @param key         密钥
     * @param nonce       12 字节 nonce
     * @param cipherAndTag {@code ciphertext || tag}
     * @return 明文
     * @throws javax.crypto.AEADBadTagException 密钥错 / nonce 错 / 密文或标签被篡改
     *                                          —— 这是本类最有价值的失败信号，调用方不要吞掉它
     * @throws java.security.GeneralSecurityException 其它参数/编码问题
     */
    public static byte[] decrypt(byte[] key, byte[] nonce, byte[] cipherAndTag) throws Exception {
        Cipher c = Cipher.getInstance(TRANSFORMATION);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        return c.doFinal(cipherAndTag == null ? new byte[0] : cipherAndTag);
    }

    /**
     * 加密并拼出线上格式 {@code nonce || ciphertext || tag}。
     *
     * @param key   密钥
     * @param nonce 12 字节 nonce
     * @param plain 明文
     * @return 线上字节
     * @throws java.security.GeneralSecurityException 参数非法
     */
    public static byte[] seal(byte[] key, byte[] nonce, byte[] plain) throws Exception {
        byte[] body = encrypt(key, nonce, plain);
        byte[] out = new byte[nonce.length + body.length];
        System.arraycopy(nonce, 0, out, 0, nonce.length);
        System.arraycopy(body, 0, out, nonce.length, body.length);
        return out;
    }

    /**
     * 解开线上格式 {@code nonce || ciphertext || tag}。
     *
     * @param key  密钥
     * @param wire 线上字节，长度须 &gt;= {@link #MIN_WIRE_LENGTH}
     * @return 明文
     * @throws IllegalArgumentException 长度不足
     * @throws javax.crypto.AEADBadTagException 认证失败（密钥错 / 被篡改）
     * @throws java.security.GeneralSecurityException 其它问题
     */
    public static byte[] open(byte[] key, byte[] wire) throws Exception {
        if (wire == null || wire.length < MIN_WIRE_LENGTH) {
            throw new IllegalArgumentException("wire too short: " + (wire == null ? -1 : wire.length));
        }
        byte[] nonce = Arrays.copyOfRange(wire, 0, NONCE_LENGTH);
        byte[] body = Arrays.copyOfRange(wire, NONCE_LENGTH, wire.length);
        return decrypt(key, nonce, body);
    }

    /**
     * AES 密钥长度是否合法（16/24/32）。
     *
     * <p>这是最便宜也最可靠的第一道校验：长度不对时 JCE 会抛
     * {@code InvalidKeyException: Invalid AES key length}，
     * 而该报错<b>不会告诉你真实原因</b>（是偶尔写错还是协议设计如此）。
     *
     * @param key 密钥，可为 null
     * @return true 表示长度合法
     */
    public static boolean isValidKey(byte[] key) {
        return key != null && (key.length == 16 || key.length == 24 || key.length == 32);
    }

    /**
     * 字节数组 → 小写 hex（协议里多处用 hex 表达密钥/摘要，便于断言与日志）。
     *
     * @param data 字节，可为 null
     * @return hex 串
     */
    public static String hex(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", Byte.valueOf(b)));
        }
        return sb.toString();
    }

    /**
     * hex 串 → 字节。
     *
     * @param hex hex 串（长度须为偶数），可为 null/空
     * @return 字节数组；非法输入返回空数组
     */
    public static byte[] fromHex(String hex) {
        if (hex == null) {
            return new byte[0];
        }
        String s = hex.trim();
        if (s.length() % 2 != 0) {
            return new byte[0];
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
