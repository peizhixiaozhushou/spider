package com.example.tvbox.plain.hxq;

import com.example.tvbox.plain.net.AndroidBase64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 韩小圈（hxq / hiyun.tv）的加密与签名原语。
 *
 * <p>反编译原文 {@code ae/decompiled/Hxq.java} 里的静态工具方法（{@code b/c/f/f0/J/L/P/U/V/W}
 * 等）集中在这里，方便单独验证。所有方法是纯函数（随机数走显式传入的 {@link Random}），
 * 因此可以用固定种子做确定性测试。
 *
 * <h2>密钥来源（全部来自 DEX 常量池，已在 smali 里核对过长度 16）</h2>
 * <pre>
 *   K1 "f349wghhe784tqwh"  设备签名 AES key   （原文 J）
 *   K2 "d3w8hf94fidk38lk"  设备签名 AES iv    （原文 K）
 *   S  "e2320a0c5c7320a9"  剧集直链 AES key   （原文 O）
 *   IV "cf99167f1f087475"  剧集直链 AES iv    （原文 P）
 * </pre>
 * 这两组是<b>固定常量</b>（不是协商出来的），所以"设备签名"其实只是把一段 JSON 做了对称加密；
 * 而真正的一次性材料是 {@code uid}：{@code signKey = md5(uid)}，取前 16 字节做 key、后 16 字节做 iv。
 *
 * <h2>签名链路（成对出现，缺一不可）</h2>
 * <pre>
 *   1) signKey(g) = md5(uid)
 *   2) uk(f)      = AES-CBC(K1,K2, 设备JSON) → Base64       ← 放在 header 的 uk
 *   3) 播放页签名  = md5("&k1=v1&k2=v2…"（键名反序，排除 sign）+ "&2E159Q/Z8979WckQ")
 *      —— 优先走服务端 RPC /sign；RPC 不可用时才用这条本地算法。
 *   4) 剧集直链    = AES-CBC(S,IV, Base64(data))  → JSON 数组，取 [0].smid
 *   5) 接口信封    = md5(key 或 uid+ts + "34F9Q53w/HJW8E6Q") 作为 AES key，
 *                   再对 Base64(data) 解密（key[0:16] / key[16:32]）
 * </pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>服务端 {@code /sign} RPC 的算法</b> —— {@link #signQuery} 复刻的是
 *       <b>客户端本地回退算法</b>（原文 {@code w()} 里 {@code Y("sign",…)} 失败后才走的那段）。
 *       服务端为什么要签、签的是什么，没有证据；需要：一次 RPC 成功时的请求/响应样本。</li>
 *   <li><b>设备 JSON 里各字段的真实含义</b> —— {@code tm/d8m/br/scc/plc/toc/tsc/pa/crec/nw/px/isp
 *       /dpc/dsc/qpc/apad} 这些数字是采集项（屏幕/电量/网络…），只按原文照抄，
 *       <b>没有一件设备能核对</b>。需要：真机抓包。</li>
 * </ol>
 */
public final class HxqCrypto {

    /** 原文 {@code A}。 */
    public static final Charset UTF8 = Charset.forName("UTF-8");

    /** 设备签名 AES key（原文 {@code J}）。 */
    public static final String KEY_K1 = "f349wghhe784tqwh";
    /** 设备签名 AES iv（原文 {@code K}）。 */
    public static final String KEY_K2 = "d3w8hf94fidk38lk";
    /** 剧集直链 AES key（原文 {@code O}）。 */
    public static final String KEY_S = "e2320a0c5c7320a9";
    /** 剧集直链 AES iv（原文 {@code P}）。 */
    public static final String IV_S = "cf99167f1f087475";
    /** 本地签名盐（原文 {@code M}）。 */
    public static final String SIGN_SALT = "2E159Q/Z8979WckQ";
    /** 派生密钥盐（原文 {@code L}）。 */
    public static final String DERIVE_SALT = "34F9Q53w/HJW8E6Q";

    /** 播放页签名参数顺序（原文 {@code w()} 里的 {@code strArr2}）。 */
    public static final String[] SIGN_FIELDS = {
            "t", "dt", "version", "uuid", "udid", "pid", "scid", "re", "sq"
    };
    /** 播放页签名里必须排除的键。 */
    public static final String SIGN_EXCLUDE = "sign";
    /** {@code dt} 固定值。 */
    public static final String DT = "android";
    /** 小写 hex 字母表（原文 {@code V()} 用）。 */
    public static final String HEX_ALPHABET = "0123456789abcdef";
    /** 62 字符字母表（原文 {@code W()} 用）。 */
    public static final String TOKEN_ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /** uuid 用的小写在前字母表（原文 {@code w()} 用）。 */
    public static final String SECRET_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** 设备 JSON 里的固定设备型号（原文 {@code F}）。 */
    public static final String DEVICE_MODEL = "25128PNA1C";
    /** 设备 JSON 里的固定厂商（原文 {@code G}）。 */
    public static final String DEVICE_MAKER = "Xiaomi";
    /** 设备 JSON 里的固定系统版本（原文 {@code H}）。 */
    public static final String DEVICE_OSV = "17";
    /** 设备 JSON 里的固定包名。 */
    public static final String PKG = "com.babycloud.hanju";

    private HxqCrypto() {
    }

    // ── 摘要 / 随机 ─────────────────────────────────────────

    /**
     * 原文 {@code J(String)}：MD5 小写 hex；任何异常返回空串。
     *
     * @param s 原文（null 当空串）
     * @return 32 字符 hex 或 ""
     */
    public static String md5(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest((s == null ? "" : s).getBytes(UTF8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", Integer.valueOf(b & 255)));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 原文 {@code V()}：{@code len} 个随机 hex 字符。
     *
     * @param random 随机源
     * @param len    长度（原文写死 16）
     * @return 随机串
     */
    public static String randomHex(Random random, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(HEX_ALPHABET.charAt(random.nextInt(HEX_ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * 原文 {@code W()}：{@code len} 个随机 62 进制字符（uid 就是它）。
     *
     * @param random 随机源
     * @param len    长度（原文写死 20）
     * @return 随机串
     */
    public static String randomToken(Random random, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(TOKEN_ALPHABET.charAt(random.nextInt(TOKEN_ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * 原文 {@code w()} 里那段 32 字符 {@code uuid}。
     *
     * @param random 随机源
     * @return 32 字符随机串
     */
    public static String randomSecret(Random random) {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(SECRET_ALPHABET.charAt(random.nextInt(SECRET_ALPHABET.length())));
        }
        return sb.toString();
    }

    // ── AES-CBC ─────────────────────────────────────────────

    /**
     * 原文 {@code b(byte[],byte[],String)}：AES/CBC/PKCS5 + Base64（flag 2 = NO_WRAP）。
     *
     * @param key   key（16 字节）
     * @param iv    iv（16 字节）
     * @param plain 明文（null 当空串）
     * @return Base64 或 ""
     */
    public static String aesEncryptBase64(byte[] key, byte[] iv, String plain) {
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.IvParameterSpec(iv));
            return AndroidBase64.encodeToString(
                    cipher.doFinal((plain == null ? "" : plain).getBytes(UTF8)),
                    AndroidBase64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 原文 {@code c(byte[],byte[],byte[])}：AES/CBC 解密。
     *
     * <p>先试 {@code PKCS5Padding}；失败则退回 {@code NoPadding} 并手工去掉尾部 0x00
     * （原文就是这么写的，两条路都留）。
     *
     * @param data 密文
     * @param key  key
     * @param iv   iv
     * @return 明文；任何失败返回零长度数组
     */
    public static byte[] aesDecrypt(byte[] data, byte[] key, byte[] iv) {
        try {
            try {
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(key, "AES"),
                        new javax.crypto.spec.IvParameterSpec(iv));
                return cipher.doFinal(data);
            } catch (Exception e) {
                javax.crypto.Cipher cipher2 = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
                cipher2.init(javax.crypto.Cipher.DECRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(key, "AES"),
                        new javax.crypto.spec.IvParameterSpec(iv));
                byte[] out = cipher2.doFinal(data);
                int len = out.length;
                while (len > 0 && out[len - 1] == 0) {
                    len--;
                }
                if (len == out.length) {
                    return out;
                }
                byte[] trimmed = new byte[len];
                System.arraycopy(out, 0, trimmed, 0, len);
                return trimmed;
            }
        } catch (Exception e2) {
            return new byte[0];
        }
    }

    /**
     * 便捷：解密成字符串（UTF-8）。
     *
     * @param data 密文
     * @param key  字符串 key（16 字节）
     * @param iv   字符串 iv（16 字节）
     * @return 明文串；解不出来返回 ""
     */
    public static String aesDecryptToString(byte[] data, String key, String iv) {
        return new String(aesDecrypt(data, key.getBytes(UTF8), iv.getBytes(UTF8)), UTF8);
    }

    // ── 密钥派生 ────────────────────────────────────────────

    /**
     * 原文 {@code b0()} / {@code j()} / {@code Z()} 里共用的"{@code substring(0,16)}"。
     *
     * @param signKey 32 字符的 md5 串
     * @return 前 16 字符；不足 16 时返回原串
     */
    public static String key16(String signKey) {
        String s = signKey == null ? "" : signKey;
        return s.length() >= 16 ? s.substring(0, 16) : s;
    }

    /**
     * 原文里的"{@code substring(16,32)}"。
     *
     * @param signKey 32 字符的 md5 串
     * @return 第 16~32 字符；不足时返回空串
     */
    public static String iv16(String signKey) {
        String s = signKey == null ? "" : signKey;
        return s.length() >= 32 ? s.substring(16, 32) : "";
    }

    /**
     * 原文 {@code i()}：设备指纹密钥，等于 {@code md5(uid)}。
     *
     * @param uid 设备 uid
     * @return 32 字符 hex
     */
    public static String deviceKey(String uid) {
        return md5(uid);
    }

    /**
     * 原文 {@code Z()} / {@code j()} 里的 {@code md5(key + L)}。
     *
     * @param seed 种子串
     * @return 32 字符 hex
     */
    public static String deriveKeyIv(String seed) {
        return md5((seed == null ? "" : seed) + DERIVE_SALT);
    }

    /**
     * 原文 {@code j()} 的 {@code md5(uid + ts)}（信封没带 key 时用它）。
     *
     * @param uid uid
     * @param ts  信封里的 ts
     * @return 32 字符 hex；uid 为空返回 ""
     */
    public static String tsKey(String uid, String ts) {
        if (uid == null || uid.isEmpty() || ts == null || ts.isEmpty()) {
            return "";
        }
        return md5(uid + ts);
    }

    // ── 播放页签名 ──────────────────────────────────────────

    /**
     * 原文 {@code w()} 的本地签名回退算法。
     *
     * <pre>
     *   把 qs 解析成键值表 → 去掉 sign → 键名<b>倒序</b>排序
     *   → 拼成 "&amp;k1=v1&amp;k2=v2…" → 末尾追加 "&amp;2E159Q/Z8979WckQ" → MD5
     * </pre>
     *
     * 注意拼出来的串<b>以 "&amp;" 开头</b>（原文就是这么拼的），不要"顺手修掉"。
     *
     * @param queryString 形如 {@code t=..&dt=android&…}
     * @return 32 字符 hex；解析失败返回 ""
     */
    public static String signQuery(String queryString) {
        try {
            LinkedHashMap<String, String> params = parseQuery(queryString);
            List<String> keys = new ArrayList<>();
            for (String k : params.keySet()) {
                if (!SIGN_EXCLUDE.equals(k)) {
                    keys.add(k);
                }
            }
            Collections.sort(keys, Collections.reverseOrder());
            StringBuilder sb = new StringBuilder();
            for (String k : keys) {
                sb.append('&').append(k).append('=').append(params.get(k));
            }
            sb.append('&').append(SIGN_SALT);
            return md5(sb.toString());
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 把 {@code a=1&b=2} 解析成有序表（键值都做一次 URL 解码，失败就用原串）。
     *
     * @param query 查询串
     * @return 保序表（同键<body>后者覆盖</body>，但保留首次出现的顺序）
     */
    public static LinkedHashMap<String, String> parseQuery(String query) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (query == null) {
            return out;
        }
        for (String pair : query.split("&")) {
            if (pair == null || pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                k = URLDecoder.decode(k, "UTF-8");
                v = URLDecoder.decode(v, "UTF-8");
            } catch (Exception ignored) {
                // 原文同样保留未解码的值
            }
            out.put(k, v);
        }
        return out;
    }

    /**
     * 原文 {@code w()} 拼出的待签串（供测试直接断言，不含 sign 本身）。
     *
     * @param params 按 {@link #SIGN_FIELDS} 顺序取值的参数表
     * @return {@code t=..&dt=..&…&sq=..}
     */
    public static String signSource(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (String k : SIGN_FIELDS) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(k).append('=').append(params.get(k) == null ? "" : params.get(k));
        }
        return sb.toString();
    }

    /**
     * 原文 {@code w()} 的 URL 拼装（{@code pid}/{@code scid}/{@code sq} 会二次编码）。
     *
     * @param t       秒级时间戳
     * @param version 版本号
     * @param uuid    32 字符随机串
     * @param pid     剧集 pid
     * @param scid    源 scid
     * @param sq      画质
     * @return 查询串
     */
    public static String rslvQuery(long t, String version, String uuid, String pid, String scid, String sq) {
        String s = "t=" + t + "&dt=" + DT + "&version=" + version + "&uuid=" + uuid
                + "&pid=" + urlEncode(pid) + "&scid=" + urlEncode(scid) + "&re=1";
        if (sq != null && !sq.isEmpty()) {
            s = s + "&sq=" + urlEncode(sq);
        }
        return s;
    }

    /**
     * 原文 {@code l(String)}：URLEncoder，失败返回原串。
     *
     * @param s 原文
     * @return 编码串
     */
    public static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    // ── 设备上报 ────────────────────────────────────────────

    /**
     * 设备指纹（原文 {@code b0()} 用的那几个字段）。
     */
    public static final class Device {
        /** 设备 id（uid）。 */
        public final String uid;
        /** 安装/首次时间戳（毫秒）。 */
        public final long installTime;
        /** {@code ai} 字段（原文 {@code this.d}）。 */
        public final String ai;
        /** {@code oa} 字段（原文 {@code this.e}）。 */
        public final String oa;

        public Device(String uid, long installTime, String ai, String oa) {
            this.uid = uid;
            this.installTime = installTime;
            this.ai = ai;
            this.oa = oa;
        }
    }

    /**
     * 原文 {@code b0()} 的 JSON 体。
     *
     * <p>字段顺序、固定值、字段名全部照抄；{@code ts} 用调用方给的时间，
     * 便于测试固定住输出。
     *
     * @param d      设备信息
     * @param nowMs  当前毫秒
     * @return JSON 串
     */
    public static String buildDeviceJson(Device d, long nowMs) {
        return "{\"emu\":0,\"ou\":0,\"it\":" + d.installTime + ",\"iit\":" + d.installTime
                + ",\"bs\":0,\"uid\":\"" + d.uid + "\",\"pc\":0,\"tm\":81,"
                + "\"d8m\":\"0,0,0,0,0,0,0,4\",\"md\":\"" + DEVICE_MODEL + "\","
                + "\"maker\":\"" + DEVICE_MAKER + "\",\"osv\":\"" + DEVICE_OSV + "\","
                + "\"br\":95,\"rpc\":0,\"scc\":2,\"plc\":6,\"toc\":19,\"tsc\":10,"
                + "\"ts\":" + nowMs + ",\"pa\":1,\"crec\":0,\"nw\":2,\"px\":\"0\",\"isp\":\"\","
                + "\"ai\":\"" + d.ai + "\",\"oa\":\"" + d.oa + "\",\"dpc\":0,\"dsc\":0,"
                + "\"qpc\":0,\"apad\":0,\"pk\":\"" + PKG + "\"}";
    }

    /**
     * 原文 {@code b0()} 里的 {@code uk} = AES-CBC(K1,K2, 设备JSON) → Base64。
     *
     * @param signKey {@code md5(uid)}
     * @param deviceJson 设备 JSON
     * @return Base64；失败 ""
     */
    public static String deviceUplink(String signKey, String deviceJson) {
        try {
            return aesEncryptBase64(key16(signKey).getBytes(UTF8), iv16(signKey).getBytes(UTF8), deviceJson);
        } catch (Exception e) {
            return "";
        }
    }

    // ── 文本处理 ────────────────────────────────────────────

    /**
     * 原文 {@code f(String)}：先补 "=" 再按 Base64 解；失败退回 URLDecode；再失败返回原串。
     *
     * @param s 原文
     * @return 解码结果
     */
    public static String decodeText(String s) {
        if (s == null) {
            return "";
        }
        try {
            try {
                if (!isBase64Alphabet(s)) {
                    // android.util.Base64.decode(str, 0) 遇到非字母表字符会抛异常，
                    // 上游正是靠这个异常回退到 URLDecode。本仓的 AndroidBase64 更宽容
                    // （会忽略非法字符、自动补 padding），所以这里显式判一次字符集来复刻上游路径。
                    throw new IllegalArgumentException("not base64: " + s);
                }
                String t = s;
                int pad = t.length() % 4;
                if (pad > 0) {
                    t = t + "====".substring(pad);
                }
                return new String(AndroidBase64.decode(t, AndroidBase64.DEFAULT), UTF8);
            } catch (Exception e) {
                return URLDecoder.decode(s, "UTF-8");
            }
        } catch (Exception e2) {
            return s;
        }
    }

    /**
     * 是否只含 base64 字母表（含 url-safe 与 {@code =}）。
     *
     * @param s 待判断串
     * @return true 表示可以按 base64 解
     */
    public static boolean isBase64Alphabet(String s) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '-' || c == '_' || c == '=';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * 原文 {@code f0(String)}：去标签清洗简介。
     *
     * <p>顺序很关键：先整段剥 {@code script}/{@code style}，再把 {@code <br>} 换成换行，
     * 才去掉其余标签，最后处理实体。换掉顺序会把 {@code &lt;} 之类的实体提前破坏。
     *
     * @param s HTML 片段
     * @return 纯文本
     */
    public static String htmlToText(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replaceAll("(?is)<script[^>]*>.*?</script>", "")
                .replaceAll("(?is)<style[^>]*>.*?</style>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\s+\\n", "\n")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim();
    }

    /**
     * 原文 {@code E(String...)}：第一个非空且非 {@code "null"} 的值。
     *
     * @param values 候选
     * @return 命中的值或 ""
     */
    public static String firstNonEmpty(String... values) {
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isEmpty() && !"null".equals(v)) {
                    return v;
                }
            }
        }
        return "";
    }

    /**
     * 原文 {@code G(String)}：是不是 m3u8（先去掉查询串、小写化）。
     *
     * @param url 地址
     * @return true 表示 m3u8
     */
    public static boolean isM3u8(String url) {
        String s = (url == null ? "" : url).toLowerCase(java.util.Locale.US).split("\\?", 2)[0];
        return s.endsWith(".m3u8") || s.contains("/m3u8/");
    }

    /**
     * 原文 {@code H(String)}：是不是 mp4。
     *
     * @param url 地址
     * @return true 表示 mp4
     */
    public static boolean isMp4(String url) {
        String s = (url == null ? "" : url).toLowerCase(java.util.Locale.US).split("\\?", 2)[0];
        return s.endsWith(".mp4") || s.contains("/video/");
    }

    /**
     * 把字符串里所有字节都塞进一个 OutputStream 的小工具（代理回包用）。
     *
     * @param s 文本
     * @return 字节流
     */
    public static ByteArrayOutputStream bytes(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write((s == null ? "" : s).getBytes(UTF8));
        } catch (Exception ignored) {
            // ByteArrayOutputStream 不会抛
        }
        return out;
    }

    /** 原文里到处出现的"主备地址择优 + 去空"：取第一个非空的参数字段。 */
    public static String pick(JSONObject o, String... keys) {
        if (o == null) {
            return "";
        }
        for (String k : keys) {
            String v = o.optString(k);
            if (v != null && !v.isEmpty() && !"null".equals(v)) {
                return v;
            }
        }
        return "";
    }

    /** 正则常量（避免每次调用都编译）。 */
    public static final Pattern P_M3U8_TAIL = Pattern.compile("/m3u8/([A-Za-z0-9_]+)(?:\\.m3u8)?");
    public static final Pattern P_VIDEO_MP4 = Pattern.compile("/video/[A-Za-z0-9_]+\\.mp4$");
    public static final Pattern P_BARE_ID = Pattern.compile("^[A-Za-z0-9_]{20,}$");
    public static final Pattern P_FULL_URL = Pattern.compile(
            "https?://[^\\s\"']+/(?:m3u8|video)/[A-Za-z0-9_]+(?:\\.m3u8|\\.mp4)?");
    public static final Pattern P_M3U8_LONG = Pattern.compile("/m3u8/([A-Za-z0-9_]{20,})(?:\\.m3u8)?");

    /**
     * 构造 CDN 前缀工具（原文反复出现的 {@code Q.replaceAll("/+$","")}）。
     *
     * @param cdnHost CDN 根
     * @return 去掉尾斜杠的根
     */
    public static String trimSlash(String cdnHost) {
        return cdnHost == null ? "" : cdnHost.replaceAll("/+$", "");
    }

    /**
     * URI 解析的安全包装（原文用 {@code URI.create}）。
     *
     * @param url 地址
     * @return URI；不可解析返回 null
     */
    public static URI uri(String url) {
        try {
            return URI.create(url);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 正则匹配辅助。
     *
     * @param pattern 正则
     * @param input   输入
     * @return 匹配器（已 find 过）
     */
    public static Matcher find(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input == null ? "" : input);
        m.find();
        return m;
    }

    /**
     * 给 {@link #find} 用的"是否命中"简写。
     *
     * @param pattern 正则
     * @param input   输入
     * @return true 表示命中
     */
    public static boolean matches(Pattern pattern, String input) {
        return pattern.matcher(input == null ? "" : input).find();
    }

    /**
     * 测试/调用方需要的 {@link List} 拷贝构造辅助。
     *
     * @param src 源
     * @return 新列表
     */
    public static <T> List<T> copyOf(List<T> src) {
        return src == null ? new ArrayList<T>() : new ArrayList<>(src);
    }
}
