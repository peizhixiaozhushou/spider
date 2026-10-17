package com.example.tvbox.plain.gz360;

import com.example.tvbox.common.AesCbc;
import com.example.tvbox.common.Digest;

import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 瓜子视频（Gz360）的加解密/签名原语 —— 与站点逻辑分离，便于单独验证。
 *
 * <p>反编译原文: {@code ae/decompiled/Gz360.java}（472 行，jadx dev）
 * <ul>
 *   <li>{@code i(path, map)}（第 148-196 行）= 请求信封</li>
 *   <li>{@code f(json)}（第 118-138 行）= 响应信封</li>
 *   <li>{@code b(byte[])}（第 106-116 行）= 大写 hex</li>
 *   <li>{@code j(int)}（第 198-200 行）= 大端 4 字节</li>
 *   <li>{@code k()}（第 202-209 行）= 16 位随机密钥</li>
 *   <li>{@code init} 里的设备指纹块（第 354-377 行）</li>
 * </ul>
 *
 * <h2>请求信封（上行）</h2>
 * <pre>
 *  key = k()   iv = k()                          ← 各 16 位 [A-Za-z0-9]
 *  request_key = UPPERHEX( AES-CBC-PKCS5(业务 JSON, key, iv) )
 *  keys        = Base64( RSA-PKCS1( {"key":key,"iv":iv}, 内置公钥 P1 ) )
 *  表单字段    = token_id,token,phone_type,request_key,app_id,time,keys,signature,
 *                phone_model,ad_version
 *  signature   = UPPERHEX( MD5( "k=v,k=v,…" + "*&zvdvdvddbfikkkumtmdwqppp?|4Y!s!2br" ) )
 * </pre>
 * 注意三者都<b>不是</b>常见的形态：分隔符是<b>逗号</b>而不是 {@code &}；
 * 摘要结果转<b>大写</b>；{@code keys} 是「用公钥加密的 AES 密钥对」。
 *
 * <h2>响应信封（下行）</h2>
 * <pre>
 *  {"data":{"response_key":"&lt;hex 密文&gt;","keys":"&lt;Base64 RSA 密文&gt;"}}
 *  keys 解密（内置私钥 P2）→ {"key":..,"iv":..}
 *  response_key → hex 解码 → AES-CBC-PKCS5 解密 → 明文 JSON
 * </pre>
 *
 * <h2>⚠ 内置 RSA 私钥 P2 与内置公钥 P1 <b>不是同一对</b></h2>
 * 已用 openssl 实测两个模数不同：
 * <pre>
 *  P1(公钥) Modulus = D4339FBFCBCB0FB1691DD7F4504BAE17DB9F44530C455C51391E503AE4CAABC6…
 *  P2(私钥) Modulus = 7BA84AAD62E2D734268D34F5A336C4E1074578918DC6E6F195DE86AC51B18A6C…
 * </pre>
 * 因此设计是「客户端上行用服务器公钥 P1、下行用客户端私钥 P2」——
 * 功能上自洽，但把<b>客户端私钥</b>打包进 APK 意味着任何拿到 APK 的人都能解开
 * 全部下行响应（{@link #defaultClientPrivateKey()}）。这是本类唯一的安全隐患，
 * 已在 {@code Gz360Plain} 的 javadoc 里按事实记录。
 *
 * <h2>为什么单独成类</h2>
 * 这段密码学逻辑与「怎么取列表/详情」完全无关，混在一起会让
 * 任何一个 JCE 细节（PKCS7 vs PKCS5、hex 大小写、RSA 填充）都难以定位。
 * 拆出来后 {@code Gz360Test} 可以逐条钉住每个原语。
 */
public final class Gz360Crypto {

    /** 设备号兜底：上游「Settings.Secure 取不到 android_id」时用的纯硬编码值（C 类） */
    public static final String DEVICE_ID_FALLBACK = "1a2b3c4d5e6f7g8h";

    /** HmacSHA1 的密钥（用于设备指纹块），C 类硬编码 */
    public static final String HMAC_KEY = "d6fc3a4a06adbde89223bvefedc24fecde188aaa9161";

    /** 签名盐，C 类硬编码 */
    public static final String SIGN_SALT = "*&zvdvdvddbfikkkumtmdwqppp?|4Y!s!2br";

    /** 密钥随机表（62 字符，与 AppYQK/Gz360.k() 同一张） */
    public static final String KEY_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** 上线用公钥 P1（给 {@code keys} 加密） */
    public static final String RSA_PUBLIC_B64 =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDUM5+/y8sPsWkd1/RQS64X259E"
            + "UwxFXFE5HlA65MqrxnPs0JqoSRojSDy5QhwvROlaD6TwRQHKMY2OAZ6SnQeUJsCh"
            + "TEFIR9qUkwrs3/MVUMxjsv6JS6Oe/juclyJGTgVmDhB55EafXsD0SQYVj/QXXsxR"
            + "6ewR5E2kL52yAAD4yQIDAQAB";

    /**
     * 下行用私钥 P2（给 {@code keys} 解密）—— 上游把 PKCS#8 私钥直接写在源码里。
     * 这是「客户端私钥随 APK 分发」，见类注释的安全说明。
     */
    public static final String RSA_PRIVATE_B64 =
            "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGAe6hKrWLi1zQmjTT1"
            + "ozbE4QdFeJGNxubxld6GrFGximxfMsMB6BpJhpcTouAqywAFppiKetUBBbXwYsYU"
            + "1wNr648XVmPmCMCy4rY8vdliFnbMUj086DU6Z+/oXBdWU3/b1G0DN3E9wULRSwcK"
            + "ZT3wj/cCI1vsCm3gj2R5SqkA9Y0CAwEAAQKBgAJH+4CxV0/zBVcLiBCHvSANm0l7"
            + "HetybTh/j2p0Y1sTXro4ALwAaCTUeqdBjWiLSo9lNwDHFyq8zX90+gNxa7c5EqcW"
            + "V9FmlVXr8VhfBzcZo1nXeNdXFT7tQ2yah/odtdcx+vRMSGJd1t/5k5bDd9wAvYdI"
            + "DblMAg+wiKKZ5KcdAkEA1cCakEN4NexkF5tHPRrR6XOY/XHfkqXxEhMqmNbB9U34"
            + "saTJnLWIHC8IXys6Qmzz30TtzCjuOqKRRy+FMM4TdwJBAJQZFPjsGC+RqcG5UvVM"
            + "iMPhnwe/bXEehShK86yJK/g/UiKrO87h3aEu5gcJqBygTq3BBBoH2md3pr/W+hUM"
            + "WBsCQQChfhTIrdDinKi6lRxrdBnn0Ohjg2cwuqK5zzU9p/N+S9x7Ck8wUI53DKm8"
            + "jUJE8WAG7WLj/oCOWEh+ic6NIwTdAkEAj0X8nhx6AXsgCYRql1klbqtVmL8+95KZ"
            + "K7PnLWG/IfjQUy3pPGoSaZ7fdquG8bq8oyf5+dzjE/oTXcByS+6XRQJAP/5ciy1b"
            + "L3NhUhsaOVy55MHXnPjdcTX0FaLi+ybXZIfIQ2P4rb19mVq1feMbCXhz+L1rG8oa"
            + "t5lYKfpe8k83ZA==";

    /** RSA 模长（1024 位）对应的密文块长度 */
    public static final int RSA_BLOCK_BYTES = 128;

    private static final Random RANDOM = new Random();

    private static PublicKey cachedPublic;
    private static PrivateKey cachedPrivate;

    private Gz360Crypto() {
    }

    // ── 密钥加载 ────────────────────────────────────────────

    /**
     * 内置公钥 P1（{@code keys} 上行加密用）。
     *
     * @return RSA 公钥；解析失败返回 null
     */
    public static PublicKey defaultServerPublicKey() {
        if (cachedPublic == null) {
            cachedPublic = loadPublic(RSA_PUBLIC_B64);
        }
        return cachedPublic;
    }

    /**
     * 内置私钥 P2（{@code keys} 下行解密用）。
     *
     * @return RSA 私钥；解析失败返回 null
     */
    public static PrivateKey defaultClientPrivateKey() {
        if (cachedPrivate == null) {
            cachedPrivate = loadPrivate(RSA_PRIVATE_B64);
        }
        return cachedPrivate;
    }

    /**
     * 从 Base64（X.509/SPKI）加载 RSA 公钥。
     *
     * @param b64 Base64 文本，可为 null
     * @return 公钥；失败返回 null（不抛）
     */
    public static PublicKey loadPublic(String b64) {
        try {
            byte[] der = java.util.Base64.getDecoder().decode(b64);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 从 Base64（PKCS#8）加载 RSA 私钥。
     *
     * @param b64 Base64 文本，可为 null
     * @return 私钥；失败返回 null（不抛）
     */
    public static PrivateKey loadPrivate(String b64) {
        try {
            byte[] der = java.util.Base64.getDecoder().decode(b64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 取模数的 hex —— 用于判断「两把内置钥匙是否同一对」。
     *
     * @param pub 公钥，可为 null
     * @return 模数 hex；不可用时空串
     */
    public static String modulusHex(PublicKey pub) {
        if (!(pub instanceof java.security.interfaces.RSAPublicKey)) {
            return "";
        }
        BigInteger m = ((java.security.interfaces.RSAPublicKey) pub).getModulus();
        return m == null ? "" : m.toString(16);
    }

    /**
     * 取模数的 hex（私钥版）。
     *
     * @param priv 私钥，可为 null
     * @return 模数 hex；不可用时空串
     */
    public static String modulusHex(PrivateKey priv) {
        if (!(priv instanceof java.security.interfaces.RSAPrivateKey)) {
            return "";
        }
        BigInteger m = ((java.security.interfaces.RSAPrivateKey) priv).getModulus();
        return m == null ? "" : m.toString(16);
    }

    // ── 字节/hex 原语 ───────────────────────────────────────

    /**
     * 大端 4 字节（还原 {@code j(int)}）。
     *
     * @param i 整数
     * @return 4 字节
     */
    public static byte[] beInt(int i) {
        return new byte[]{
                (byte) ((i >> 24) & 255),
                (byte) ((i >> 16) & 255),
                (byte) ((i >> 8) & 255),
                (byte) (i & 255)};
    }

    /**
     * 小写 hex。
     *
     * @param b 字节，可为 null
     * @return hex；null 入参返回空串
     */
    public static String hex(byte[] b) {
        return Digest.toHex(b);
    }

    /**
     * 大写 hex（上游 {@code b(byte[]).toUpperCase()}）。
     *
     * @param b 字节，可为 null
     * @return 大写 hex
     */
    public static String hexUpper(byte[] b) {
        return hex(b).toUpperCase();
    }

    /**
     * hex → 字节（还原 {@code f()} 里手写的 nibble 循环）。
     *
     * <p>上游没有做长度校验：奇数长度会读越界；非法字符经
     * {@code Character.digit} 得到 -1，被静默拼进字节。本实现返回 null。
     *
     * @param s hex 文本，可为 null
     * @return 字节；长度非法或含非 hex 字符时返回 null
     */
    public static byte[] unhex(String s) {
        if (s == null || s.isEmpty() || (s.length() % 2) != 0) {
            return null;
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /**
     * 16 位随机密钥（还原 {@code k()}）。
     *
     * @return 16 个 [A-Za-z0-9] 字符
     */
    public static String randomKey16() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(KEY_CHARS.charAt(RANDOM.nextInt(KEY_CHARS.length())));
        }
        return sb.toString();
    }

    // ── AES ─────────────────────────────────────────────────

    /**
     * AES-CBC-PKCS5 加密 → 大写 hex。
     *
     * <p>上游用 {@code "AES/CBC/PKCS5Padding"}（不是 Android 常见的 PKCS7 ——
     * AES 分组固定 16 字节，两者等价，但 PKCS7Padding 在标准 JCE 里不存在）。
     *
     * @param plain 明文，可为 null
     * @param key   16/24/32 字符密钥，可为 null
     * @param iv    16 字符 IV，可为 null
     * @return 大写 hex；参数非法时返回空串（不抛）
     */
    public static String aesEncryptHex(String plain, String key, String iv) {
        byte[] out = AesCbc.encrypt(AesCbc.utf8(plain), AesCbc.utf8(key), AesCbc.utf8(iv));
        return out == null ? "" : hexUpper(out);
    }

    /**
     * 大写/小写 hex → AES-CBC-PKCS5 解密 → UTF-8 文本。
     *
     * @param hex 密文 hex，可为 null
     * @param key 密钥，可为 null
     * @param iv  IV，可为 null
     * @return 明文；任何一步失败返回空串（不抛）
     */
    public static String aesDecryptHex(String hex, String key, String iv) {
        byte[] ct = unhex(hex);
        if (ct == null) {
            return "";
        }
        byte[] pt = AesCbc.decrypt(ct, AesCbc.utf8(key), AesCbc.utf8(iv));
        return pt == null ? "" : new String(pt, StandardCharsets.UTF_8);
    }

    // ── RSA ─────────────────────────────────────────────────

    /**
     * RSA/ECB/PKCS1Padding 加密 → 无换行 Base64（{@code keys} 字段）。
     *
     * @param plain 明文，可为 null
     * @param pub   公钥，可为 null
     * @return Base64；参数非法时返回空串（不抛）
     */
    public static String rsaEncryptB64(String plain, PublicKey pub) {
        if (plain == null || pub == null) {
            return "";
        }
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.ENCRYPT_MODE, pub);
            return java.util.Base64.getEncoder()
                    .encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * RSA/ECB/PKCS1Padding 解密 Base64 → 文本。
     *
     * @param b64  Base64 密文，可为 null
     * @param priv 私钥，可为 null
     * @return 明文；失败返回空串（不抛）
     */
    public static String rsaDecryptB64(String b64, PrivateKey priv) {
        if (b64 == null || priv == null) {
            return "";
        }
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.DECRYPT_MODE, priv);
            byte[] pt = c.doFinal(java.util.Base64.getDecoder().decode(b64.trim()));
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    // ── 信封 ────────────────────────────────────────────────

    /**
     * 上行：把业务 map 打成 {@code {"key","iv","request_key","keys","signature"}}。
     *
     * <p>字段顺序严格对齐上游 {@code i()}：先 {@code key}/{@code iv}/{@code request_key}，
     * 再 {@code token_id}…{@code keys}，最后 {@code signature}/{@code phone_model}/{@code ad_version}。
     *
     * @param biz        业务字段（保序），可为 null
     * @param token      登录 token（{@code AuthData.a}），可为 null
     * @param tokenId    {@code token_id} 字段值（上游恒为空串）
     * @param pub        加密 {@code keys} 用的公钥（默认 {@link #defaultServerPublicKey()}）
     * @param key        指定 AES key（测试用）；null 时随机
     * @param iv         指定 AES iv（测试用）；null 时随机
     * @param timeSec    秒级时间戳串
     * @return 保序表单 map；参数非法时返回不含签名的 map
     */
    public static java.util.LinkedHashMap<String, String> buildRequest(
            Map<String, String> biz, String token, String tokenId, PublicKey pub,
            String key, String iv, String timeSec) {
        String k = key == null ? randomKey16() : key;
        String v = iv == null ? randomKey16() : iv;
        String plain = biz == null ? "{}" : new JSONObject(biz).toString();
        String requestKey = aesEncryptHex(plain, k, v);
        String keys = rsaEncryptB64("{\"key\":\"" + k + "\",\"iv\":\"" + v + "\"}",
                pub == null ? defaultServerPublicKey() : pub);

        java.util.LinkedHashMap<String, String> form = new java.util.LinkedHashMap<>();
        form.put("token_id", tokenId == null ? "" : tokenId);
        form.put("token", token == null ? "" : token);
        form.put("phone_type", "1");
        form.put("request_key", requestKey);
        form.put("app_id", "1");
        form.put("time", timeSec == null ? "" : timeSec);
        form.put("keys", keys);
        form.put("signature", signMap(form));
        form.put("phone_model", "");
        form.put("ad_version", "1");
        return form;
    }

    /**
     * 签名：{@code UPPERHEX(MD5("k=v,k=v,…" + SIGN_SALT))}。
     *
     * <p>三处细节都与常见写法不同：分隔符是<b>逗号</b>；无 URL 编码；结果<b>大写</b>。
     * 上游把 {@code signature} 自己也算进拼接串的时机是「放进去之前」，
     * 因此签名不覆盖 {@code signature} 字段本身。
     *
     * @param form 表单字段（保序），可为 null
     * @return 32 位大写 hex；form 为空时仍返回盐的 MD5
     */
    public static String signMap(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        if (form != null) {
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(e.getKey() == null ? "" : e.getKey())
                  .append('=')
                  .append(e.getValue() == null ? "" : e.getValue());
            }
        }
        return Digest.md5(sb + SIGN_SALT).toUpperCase();
    }

    /**
     * 下行：{@code {"data":{"response_key":..,"keys":..}}} → 明文 JSON。
     *
     * @param body 响应体（或已解析的 {"data":{...}}）
     * @param priv 解密 {@code keys} 用的私钥；null 时用内置私钥 P2
     * @return 明文 JSONObject；任何一步失败返回空对象
     */
    public static JSONObject decodeEnvelope(String body, PrivateKey priv) {
        JSONObject root = parse(body);
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            return new JSONObject();
        }
        String keypair = rsaDecryptB64(data.optString("keys"), priv == null ? defaultClientPrivateKey() : priv);
        if (keypair.isEmpty()) {
            return new JSONObject();
        }
        JSONObject kp = parse(keypair);
        String plain = aesDecryptHex(data.optString("response_key"),
                kp.optString("key"), kp.optString("iv"));
        return parse(plain);
    }

    /**
     * 构造一个合法的下行响应体 —— 仅供测试（用指定公钥加密密钥对）。
     *
     * @param plain    业务明文 JSON
     * @param key      AES key
     * @param iv       AES iv
     * @param clientPub 服务器侧加密 {@code keys} 用的公钥
     * @return 响应体 JSON 串；参数非法时返回 {@code "{}"}
     */
    public static String encodeResponse(String plain, String key, String iv, PublicKey clientPub) {
        try {
            JSONObject data = new JSONObject();
            data.put("response_key", aesEncryptHex(plain, key, iv));
            data.put("keys", rsaEncryptB64("{\"key\":\"" + key + "\",\"iv\":\"" + iv + "\"}", clientPub));
            JSONObject root = new JSONObject();
            root.put("data", data);
            return root.toString();
        } catch (Throwable e) {
            return "{}";
        }
    }

    // ── 设备指纹块 ──────────────────────────────────────────

    /**
     * 设备指纹块（还原 {@code init} 里 {@code byteArrayOutputStream} 的全部写入）。
     *
     * <pre>
     *  [0..3]   秒级时间戳（大端）
     *  [4..7]   Random.nextInt()（大端）
     *  [8]      3
     *  [9]      0
     *  [10..13] android_id.hashCode()（大端）
     *  [14..17] Base64_NO_WRAP(HmacSHA1(前 14 字节, HMAC_KEY)).hashCode()（大端）
     * </pre>
     *
     * 注意最后 4 字节是「Base64 串的 Java hashCode」，不是 HMAC 本身
     * —— 这是一个把 20 字节摘要压成 4 字节的做指纹手法。
     *
     * @param sec           秒级时间戳
     * @param randomInt     随机整数（{@code Random.nextInt()}）
     * @param androidIdHash android_id 的 Java hashCode
     * @return 18 字节
     */
    public static byte[] deviceBlob(int sec, int randomInt, int androidIdHash) {
        byte[] prefix = new byte[14];
        System.arraycopy(beInt(sec), 0, prefix, 0, 4);
        System.arraycopy(beInt(randomInt), 0, prefix, 4, 4);
        prefix[8] = 3;
        prefix[9] = 0;
        System.arraycopy(beInt(androidIdHash), 0, prefix, 10, 4);

        byte[] out = new byte[18];
        System.arraycopy(prefix, 0, out, 0, 14);
        System.arraycopy(beInt(hmacHashCode(prefix)), 0, out, 14, 4);
        return out;
    }

    /**
     * {@code deviceBlob} 的 Base64（无换行）形式 —— 上游放进 {@code old_key} 字段的值。
     *
     * @param sec           秒级时间戳
     * @param randomInt     随机整数
     * @param androidIdHash android_id 的 Java hashCode
     * @return Base64 文本；失败返回空串
     */
    public static String deviceBlobB64(int sec, int randomInt, int androidIdHash) {
        try {
            return java.util.Base64.getEncoder()
                    .encodeToString(deviceBlob(sec, randomInt, androidIdHash));
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * {@code Base64_NO_WRAP(HmacSHA1(data, HMAC_KEY)).hashCode()}。
     *
     * @param data 被 HMAC 的字节，可为 null
     * @return Java 字符串 hashCode；失败返回 0
     */
    public static int hmacHashCode(byte[] data) {
        if (data == null) {
            return 0;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            String b64 = java.util.Base64.getEncoder().encodeToString(mac.doFinal(data));
            return b64.hashCode();
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * SHA-1 → 大写 hex（上游 {@code b(MessageDigest.getInstance("SHA-1").digest(...)).toUpperCase()}，
     * 用于 {@code new_key}）。
     *
     * @param s 原文，可为 null
     * @return 40 位大写 hex
     */
    public static String sha1Upper(String s) {
        if (s == null) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return hexUpper(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * SHA-1 的 hex（小写）—— 与 {@link #sha1Upper(String)} 同源，便于测试大小写。
     *
     * @param s 原文，可为 null
     * @return 40 位小写 hex
     */
    public static String sha1Lower(String s) {
        if (s == null) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return hex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 宽松 JSON 解析（非 JSON → 空对象）。
     *
     * @param s 文本，可为 null
     * @return 非 null JSONObject
     */
    private static JSONObject parse(String s) {
        if (s == null) {
            return new JSONObject();
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(t);
        } catch (Throwable e) {
            return new JSONObject();
        }
    }
}
