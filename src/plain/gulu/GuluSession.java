package com.example.tvbox.plain.gulu;

import com.example.tvbox.plain.net.RawBytes;
import com.example.tvbox.plain.proto.Proto;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gulu 会话层 —— 握手 / 会话密钥推导 / 加密 RPC / 源站表下发。
 *
 * <p>上游对应 {@code ae/decompiled/Gulu.java} 的内嵌类 {@code Gulu.GuluCrypto}
 * （字段 {@code a..f}，方法 {@code a()..isBooted()}）。本类用可读命名替代
 * {@code a/b/c/d/e}，并把内联在 {@code callApi} 里的编码/解码拆成可直接断言的
 * 纯函数，语义逐条对齐。
 *
 * <h2>协议全貌（三层叠加，顺序不可乱）</h2>
 * <pre>
 *  明文 = protobuf 消息
 *    ↓ 1) zlib：握手用 level5 + zlib 包装；业务用 level6 + 裸 deflate
 *    ↓ 2) AES：握手用 AES-256-CBC/PKCS5（key = 随机 32 字符 hex 的 UTF-8 字节，
 *            iv = 随机 16 字节）；业务用 AES-256-GCM（nonce 12 字节，tag 128 位）
 *    ↓ 3) 封装：握手把 base64(iv||ct) 放 text/plain 体 + X-Handshake-Key 头；
 *            业务把 pb{1:时间戳, 2: nonce||ct||tag} 放 application/x-protobuf 体 +
 *            X-Session-Id 头
 * </pre>
 *
 * <h2>会话密钥推导（本类最关键、也最容易写错的一段）</h2>
 * <pre>
 *   ECDH(secp256r1) → shared(64B) --normalize32--> 32B
 *   k1        = HMAC-SHA256(key = sessionId(UTF-8), msg = shared32)
 *   sessionKey = HMAC-SHA256(key = k1,               msg = "v2-session" + 0x01)[0..32)
 * </pre>
 * 注意第二次 HMAC 的 <b>key 是 k1、消息是固定串</b>（不是反过来）。上游这里连续调用
 * 两次 {@code mac.init(...)}，第二次的 key 用的是第一次的输出 —— 阅读时极易看漏。
 *
 * <h2>★ 为什么这套推导是"可证伪"的</h2>
 * 业务层用 AES-GCM，任何一步推导错误都会在第一次解密响应时抛
 * {@link javax.crypto.AEADBadTagException}，而不是静默产出乱码。见
 * {@link AesGcmCodec} 的类注释与 GuluTest 的三组探针。
 *
 * <h2>证据来源</h2>
 * <ul>
 *   <li>握手: {@code ae/decompiled/Gulu.java:226-304}</li>
 *   <li>业务 RPC: {@code Gulu.java:164-224}</li>
 *   <li>boot（拉取源站 API 模板表）: {@code Gulu.java:127-162}</li>
 *   <li>32 字节归一化: {@code Gulu.java:75-86}；hex 随机串: {@code Gulu.java:88-100}</li>
 * </ul>
 */
public final class GuluSession {

    // ── 协议常量（全部来自反编译原文，逐条可核对）──────────────

    /** 服务端地址 —— 上游硬编码 IP:端口，非域名。 */
    public static final String HOST = "http://103.45.132.22:19987";
    /** 唯一接口路径：握手与业务共用。 */
    public static final String API_PATH = "/app/bn/v2";
    /** 完整接口 URL。 */
    public static final String API_URL = HOST + API_PATH;
    /** 客户端 UA —— 伪装成 Pixel 4 / Android 11 的 Dalvik。 */
    public static final String USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 11; Pixel 4)";
    /** 会话 id 头（业务请求）。 */
    public static final String HEADER_SESSION_ID = "X-Session-Id";
    /** 握手密钥头（握手请求）—— 明文把本轮 AES-CBC 密钥送过去。 */
    public static final String HEADER_HANDSHAKE_KEY = "X-Handshake-Key";
    /** WAF/风控开关头，固定值 {@link #PROTECTION_VALUE}。 */
    public static final String HEADER_PROTECTION = "X-Player-Page-Protection";
    /** {@link #HEADER_PROTECTION} 的固定值。 */
    public static final String PROTECTION_VALUE = "1";
    /** protobuf 请求体 Content-Type。 */
    public static final String CONTENT_TYPE_PROTOBUF = "application/x-protobuf";
    /** 握手请求体 Content-Type。 */
    public static final String CONTENT_TYPE_TEXT = "text/plain";
    /** 握手时上报的客户端版本。 */
    public static final String CLIENT_VERSION = "1.0.0";
    /** boot 上报的 App 名。 */
    public static final String APP_NAME = "咕噜咕噜";
    /** boot 上报的 App 版本。 */
    public static final String APP_VERSION = "2.1.2";
    /** boot 上报的包名。 */
    public static final String APP_PACKAGE = "com.jymqfh.xee";
    /** boot 上报的渠道号。 */
    public static final String APP_CHANNEL = "212";
    /** boot 上报的平台标识。 */
    public static final String APP_PLATFORM = "gulu";
    /** boot 上报的系统串。 */
    public static final String APP_PLATFORM_VERSION = "Android 11";

    /**
     * 会话密钥派生用的固定消息：ASCII {@code v2-session} 后跟一个 0x01 字节。
     *
     * <p>上游写作 {@code new byte[]{118,50,45,115,101,115,115,105,111,110,1}}。
     * 这里保留字节写法 —— 在源码里写 {@code "\\u0001"} 之类的转义容易踩到
     * javac 的 unicode 转义预处理（注释里也不行），字节数组最不容易出错。
     */
    public static final byte[] SESSION_MAC_MESSAGE = {
            118, 50, 45, 115, 101, 115, 115, 105, 111, 110, 1
    };

    /** 握手 / boot 的业务类型号（上游 {@code callApi(1, 0, ...)}）。 */
    public static final int TYPE_BOOT = 1;
    /** 业务子类型号：分类列表。 */
    public static final int SUB_CATEGORY = 66;
    /** 业务子类型号：详情。 */
    public static final int SUB_DETAIL = 62;
    /** 业务子类型号：搜索。 */
    public static final int SUB_SEARCH = 61;
    /** 分类请求 / 首页视频共用的类型号（上游 {@code callApi(3, ...)}）。 */
    public static final int TYPE_SERIES = 3;

    /** 业务请求用的响应体长度门槛（12 nonce + 16 tag）。 */
    public static final int MIN_REPLY_LENGTH = AesGcmCodec.MIN_WIRE_LENGTH;

    // ── 传输抽象 ─────────────────────────────────────────────

    /**
     * 传输抽象：只要能把 protobuf 字节 POST 出去并拿回响应字节即可。
     *
     * <p>抽出来是为了让测试可以注入"假的服务器"，在零网络的前提下走完整条
     * 握手 → boot → 加解密 → 解析链路。
     */
    public interface Transport {
        /**
         * POST 原始字节。
         *
         * @param url     完整 URL
         * @param headers 请求头
         * @param body    请求体字节
         * @return 响应字节；失败返回 null
         */
        byte[] post(String url, Map<String, String> headers, byte[] body);
    }

    /** 默认传输：走 {@link RawBytes}（精确字节，尊重全局离线开关）。 */
    public static final class HttpTransport implements Transport {
        @Override
        public byte[] post(String url, Map<String, String> headers, byte[] body) {
            RawBytes.Result r = RawBytes.post(url, body, headers);
            return r.ok() ? r.bytes : null;
        }
    }

    // ── 状态 ─────────────────────────────────────────────────

    private final Transport transport;
    /** 上游字段 {@code d}：16 字符 hex 随机设备号（每次实例化重新生成）。 */
    private final String deviceId;
    /** 上游字段 {@code b}：会话 id（服务端下发）。 */
    private String sessionId;
    /** 上游字段 {@code c}：32 字节会话密钥。 */
    private byte[] sessionKey;
    /** 上游字段 {@code e}：boot 是否成功。 */
    private boolean booted;
    /** 上游字段 {@code f}：源站 → API 模板（boot 下发）。 */
    private final Map<String, String> sourceApis = new LinkedHashMap<>();

    /** 默认构造：使用真实 HTTP 传输。 */
    public GuluSession() {
        this(new HttpTransport());
    }

    /**
     * 注入传输（测试用）。
     *
     * @param transport 传输实现，null 时退化为真实 HTTP
     */
    public GuluSession(Transport transport) {
        this.transport = transport == null ? new HttpTransport() : transport;
        this.deviceId = randHex(16);
    }

    // ── 对外状态 ─────────────────────────────────────────────

    /** @return 设备号（16 字符 hex） */
    public String deviceId() {
        return deviceId;
    }

    /** @return 会话 id；未握手时为空串 */
    public String sessionId() {
        return sessionId == null ? "" : sessionId;
    }

    /** @return 会话密钥副本；未握手时为 null */
    public byte[] sessionKey() {
        return sessionKey == null ? null : sessionKey.clone();
    }

    /** @return boot 下发的源站表副本（保持下发顺序） */
    public Map<String, String> sourceApis() {
        return new LinkedHashMap<>(sourceApis);
    }

    /** @return 是否已完成握手且拿到会话密钥 */
    public boolean isHandshaked() {
        return sessionKey != null;
    }

    /** @return 上游 {@code isBooted()}：boot 成功 <b>且</b> 有会话密钥 */
    public boolean isBooted() {
        return booted && sessionKey != null;
    }

    /**
     * 上游 {@code Gulu.d()} 的等价：确保会话可用。
     *
     * <p>已是 booted 状态直接返回；否则先握手，握手失败也返回（不重试、不抛）。
     * 上游还额外开线程预热，这里不预热 —— 见 {@code GuluPlain} 的说明。
     */
    public void ensureReady() {
        if (isBooted() || !handshake()) {
            return;
        }
        boot();
    }

    // ── 握手 ─────────────────────────────────────────────────

    /**
     * 握手：ECDH 交换 + 推导会话密钥。
     *
     * @return true 表示拿到会话密钥
     */
    public boolean handshake() {
        try {
            KeyPair keyPair = generateEcKeyPair();
            byte[] point = uncompressedPoint(keyPair.getPublic());
            byte[] request = buildHandshakeRequest(point);
            String handshakeKey = randHex(32);
            byte[] iv = randBytes(16);
            byte[] body = encodeHandshakeRequest(handshakeKey.getBytes("UTF-8"), iv, request)
                    .getBytes("UTF-8");

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", CONTENT_TYPE_TEXT);
            headers.put("User-Agent", USER_AGENT);
            headers.put(HEADER_PROTECTION, PROTECTION_VALUE);
            headers.put(HEADER_HANDSHAKE_KEY, handshakeKey);

            byte[] reply = transport.post(API_URL, headers, body);
            if (reply == null) {
                return false;
            }
            byte[] plain = decodeHandshakeResponse(handshakeKey.getBytes("UTF-8"), new String(reply, "UTF-8"));
            if (plain == null) {
                return false;
            }
            HandshakeReply parsed = parseHandshakeReply(plain);
            if (parsed == null) {
                return false;
            }
            byte[] shared = ecdhShared32(keyPair.getPrivate(), parsed.serverPoint);
            if (shared == null) {
                return false;
            }
            byte[] derived = deriveSessionKey(parsed.sessionId, shared);
            if (derived == null) {
                return false;
            }
            this.sessionKey = derived;
            this.sessionId = parsed.sessionId;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 握手响应解析结果。 */
    public static final class HandshakeReply {
        /** 服务端下发的会话 id（pb 字段 1）。 */
        public final String sessionId;
        /** 服务端 65 字节未压缩 EC 公钥（pb 字段 2）。 */
        public final byte[] serverPoint;

        /**
         * @param sessionId   会话 id
         * @param serverPoint 65 字节 EC 点
         */
        public HandshakeReply(String sessionId, byte[] serverPoint) {
            this.sessionId = sessionId;
            this.serverPoint = serverPoint;
        }
    }

    /**
     * 解析握手响应明文（pb）。
     *
     * <p>上游按字段号取值、不校验 wire type：字段 1 → 文本，字段 2 → 字节。
     * 两个都非空才算成功。
     *
     * @param pb 响应明文
     * @return 解析结果；缺字段返回 null
     */
    public static HandshakeReply parseHandshakeReply(byte[] pb) {
        String id = "";
        byte[] point = null;
        for (Proto.Field f : Proto.decode(pb)) {
            if (f.number() == 1) {
                id = f.asString();
            } else if (f.number() == 2) {
                point = f.asBytes();
            }
        }
        if (id == null || id.isEmpty() || point == null) {
            return null;
        }
        return new HandshakeReply(id, point);
    }

    /**
     * 推导会话密钥（见类注释的公式）。
     *
     * @param sessionId 会话 id（HMAC key 的文本）
     * @param shared32  ECDH 共享秘密，必须已归一化为 32 字节
     * @return 32 字节密钥；失败返回 null
     */
    public static byte[] deriveSessionKey(String sessionId, byte[] shared32) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // 第一次：key = sessionId，消息 = shared32
            mac.init(new SecretKeySpec(sessionId.getBytes("UTF-8"), "HmacSHA256"));
            byte[] k1 = mac.doFinal(shared32);
            // 第二次：把上一步输出当作 key，消息换成固定串
            mac.init(new SecretKeySpec(k1, "HmacSHA256"));
            byte[] out = mac.doFinal(SESSION_MAC_MESSAGE);
            return Arrays.copyOf(out, 32);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 握手请求的明文 pb：{@code {1: EC 点, 2: "1.0.0", 3: {1:1,2:1,3:1}}}。
     *
     * @param point65 65 字节未压缩 EC 公钥
     * @return pb 字节
     */
    public static byte[] buildHandshakeRequest(byte[] point65) {
        Proto.Encoder caps = Proto.encode().varint(1, 1L).varint(2, 1L).varint(3, 1L);
        return Proto.encode()
                .bytes(1, point65)
                .string(2, CLIENT_VERSION)
                .message(3, caps)
                .toByteArray();
    }

    /**
     * 握手请求体：{@code base64(iv || AES-256-CBC/zlib(明文))}。
     *
     * @param keyUtf8 32 字节密钥（32 字符 hex 的 UTF-8 字节）
     * @param iv     16 字节 IV
     * @param plain  明文 pb
     * @return base64 文本（标准字母表 + 补齐，对应上游 flag 2 = NO_WRAP）
     * @throws Exception 加密失败
     */
    public static String encodeHandshakeRequest(byte[] keyUtf8, byte[] iv, byte[] plain) throws Exception {
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, new SecretKeySpec(keyUtf8, "AES"), new IvParameterSpec(iv));
        byte[] ct = c.doFinal(ZlibCodec.deflateHandshake(plain));
        byte[] wire = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, wire, 0, iv.length);
        System.arraycopy(ct, 0, wire, iv.length, ct.length);
        return android.util.Base64.encodeToString(wire, 2);
    }

    /**
     * 解开握手响应体：base64 → {@code iv(16) || ct} → AES-CBC 解密 → zlib 解压。
     *
     * @param keyUtf8    与请求同一个密钥
     * @param base64Body 响应体（text/plain，内容是 base64）
     * @return 明文 pb；任一环节失败返回 null
     */
    public static byte[] decodeHandshakeResponse(byte[] keyUtf8, String base64Body) {
        try {
            byte[] wire = android.util.Base64.decode(base64Body, 0);
            if (wire.length <= 16) {
                return null;
            }
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE, new SecretKeySpec(keyUtf8, "AES"),
                    new IvParameterSpec(wire, 0, 16));
            byte[] ct = new byte[wire.length - 16];
            System.arraycopy(wire, 16, ct, 0, ct.length);
            return ZlibCodec.inflate(c.doFinal(ct), false);
        } catch (Exception e) {
            return null;
        }
    }

    // ── boot（拉源站表）───────────────────────────────────────

    /**
     * boot：上报 App/设备指纹，取回"源站 → API 模板"表。
     *
     * <p><b>与上游一致的细节</b>：上游先把成功标志置位、再解析响应，解析抛异常时
     * {@code return false} 但标志位不回滚，于是可能出现 {@code boot()==false} 而
     * {@code isBooted()==true} 的组合。这里保持同样行为（不"顺手修掉"）。
     *
     * @return true 表示响应非空
     */
    public boolean boot() {
        try {
            byte[] appInfo = Proto.encode()
                    .string(1, APP_NAME)
                    .string(2, APP_VERSION)
                    .string(3, APP_PACKAGE)
                    .string(4, deviceId)
                    .string(5, APP_CHANNEL)
                    .toByteArray();
            byte[] clientInfo = Proto.encode()
                    .string(1, deviceId)
                    .varint(2, 0L)
                    .string(3, "11")
                    .string(4, "Pixel 4")
                    .string(5, "google/redfin/redfin:11/RQ3A.211001.001")
                    .string(6, "google")
                    .string(7, "redfin")
                    .varint(8, 0L)
                    .string(9, "unknown")
                    .string(10, "unknown")
                    .varint(11, 0L)
                    .varint(12, 30L)
                    .toByteArray();
            byte[] payload = Proto.encode()
                    .string(1, "v1")
                    .string(2, APP_PLATFORM_VERSION)
                    .string(3, APP_PLATFORM)
                    .bytes(4, appInfo)
                    .bytes(5, clientInfo)
                    .toByteArray();

            byte[] reply = callApi(TYPE_BOOT, 0, payload);
            this.booted = reply != null;
            if (this.booted) {
                sourceApis.clear();
                sourceApis.putAll(parseSourceApis(reply));
            }
            return this.booted;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从 boot 响应里抽出"源站 → API 模板"表。
     *
     * <p>上游结构：顶层字段 7（length-delimited）里再套一层消息，
     * 内层字段 2 = 源站名、字段 3 = API 模板。
     *
     * @param bootReply boot 响应字节（非加密层，已是 pb）
     * @return 源站表（可能为空）
     */
    public static Map<String, String> parseSourceApis(byte[] bootReply) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Proto.Field f : Proto.decode(bootReply)) {
            if (f.number() != 7 || f.wireType() != Proto.WIRE_LEN) {
                continue;
            }
            String name = "";
            String url = "";
            for (Proto.Field inner : f.asMessage()) {
                if (inner.number() == 2 && inner.wireType() == Proto.WIRE_LEN) {
                    name = inner.asString();
                } else if (inner.number() == 3 && inner.wireType() == Proto.WIRE_LEN) {
                    url = inner.asString();
                }
            }
            if (!name.isEmpty() && !url.isEmpty()) {
                out.put(name, url);
            }
        }
        return out;
    }

    // ── 加密 RPC ─────────────────────────────────────────────

    /**
     * 加密 RPC。未握手（无会话密钥）直接返回 null（上游同样早退）。
     *
     * @param type    业务类型号
     * @param sub     业务子类型号
     * @param payload 明文 pb 负载
     * @return 响应负载（pb 字段 4 的内容）；任何环节失败返回 null
     */
    public byte[] callApi(int type, int sub, byte[] payload) {
        if (sessionKey == null) {
            return null;
        }
        try {
            long ts = System.currentTimeMillis();
            byte[] envelope = buildCallEnvelope(ts, type, sub, sessionId, payload);
            byte[] compressed = ZlibCodec.deflateBusiness(envelope);
            byte[] nonce = randBytes(AesGcmCodec.NONCE_LENGTH);
            byte[] wire = AesGcmCodec.seal(sessionKey, nonce, compressed);
            byte[] request = buildRequestEnvelope(ts, wire);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", CONTENT_TYPE_PROTOBUF);
            headers.put("User-Agent", USER_AGENT);
            headers.put(HEADER_PROTECTION, PROTECTION_VALUE);
            headers.put(HEADER_SESSION_ID, sessionId);

            byte[] reply = transport.post(API_URL, headers, request);
            if (reply == null) {
                return null;
            }
            return parseCallReply(sessionKey, reply);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构造业务请求的明文 pb：{@code {1:ts, 2:type, 3:sub, 4:sessionId, 5:空字节串, 6:payload, 7:ts}}。
     *
     * <p>字段 5 是<b>零长度 bytes 字段</b>（tag + 长度 0，共 2 字节），不是省略。
     * 上游 {@code writeBytes(5, new byte[0])} 同样会写出这 2 字节 —— 服务端可能用它做
     * 版本/兼容探测，省掉会改变报文长度与字段布局。
     *
     * @param ts        时间戳（字段 1 与 7 相同）
     * @param type      类型号
     * @param sub       子类型号
     * @param sessionId 会话 id
     * @param payload   负载
     * @return 明文 pb
     */
    public static byte[] buildCallEnvelope(long ts, int type, int sub, String sessionId, byte[] payload) {
        return Proto.encode()
                .varint(1, ts)
                .varint(2, type)
                .varint(3, sub)
                .string(4, sessionId)
                .bytes(5, new byte[0])
                .bytes(6, payload)
                .varint(7, ts)
                .toByteArray();
    }

    /**
     * 业务请求体外层：{@code {1:ts, 2: nonce||ct||tag}}。
     *
     * @param ts   时间戳
     * @param wire 已加密字节
     * @return pb 字节
     */
    public static byte[] buildRequestEnvelope(long ts, byte[] wire) {
        return Proto.encode().varint(1, ts).bytes(2, wire).toByteArray();
    }

    /**
     * 解析业务响应：取字段 2 → 解 GCM → 裸 inflate → 取字段 4。
     *
     * @param key     会话密钥
     * @param replyPb 响应 pb
     * @return 负载字节；字段缺失/长度不足(<28)/认证失败 都返回 null
     */
    public static byte[] parseCallReply(byte[] key, byte[] replyPb) {
        Proto.Field f2 = Proto.find(Proto.decode(replyPb), 2);
        if (f2 == null || f2.wireType() != Proto.WIRE_LEN) {
            return null;
        }
        byte[] wire = f2.asBytes();
        if (wire.length < MIN_REPLY_LENGTH) {
            return null;
        }
        try {
            byte[] plain = ZlibCodec.inflate(AesGcmCodec.open(key, wire), true);
            Proto.Field f4 = Proto.find(Proto.decode(plain), 4);
            if (f4 == null || f4.wireType() != Proto.WIRE_LEN) {
                return null;
            }
            return f4.asBytes();
        } catch (Exception e) {
            return null;
        }
    }

    // ── 纯工具（全部可单测）──────────────────────────────────

    /**
     * 把任意长度字节归一化成 32 字节（上游 {@code a(byte[])}）。
     *
     * <p>规则：长度 &gt; 32 取<b>末尾</b> 32 字节；长度 &lt; 32 在<b>左侧补零</b>。
     * 取末尾是因为 {@code BigInteger.toByteArray()} 对最高位为 1 的坐标会多一个
     * 0x00 符号字节（33 字节），去掉它才是真正的坐标值。
     *
     * @param in 输入，null 视作空
     * @return 32 字节
     */
    public static byte[] normalize32(byte[] in) {
        byte[] src = in == null ? new byte[0] : in;
        if (src.length == 32) {
            return src.clone();
        }
        byte[] out = new byte[32];
        if (src.length > 32) {
            System.arraycopy(src, src.length - 32, out, 0, 32);
        } else {
            System.arraycopy(src, 0, out, 32 - src.length, src.length);
        }
        return out;
    }

    /**
     * 生成 n 个随机字节。
     *
     * @param n 长度
     * @return 随机字节
     */
    public static byte[] randBytes(int n) {
        byte[] b = new byte[Math.max(0, n)];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /**
     * 生成"看起来长度为 len"的随机 hex 串（上游 {@code b(int)}）。
     *
     * <p>注意上游实现是 {@code len/2} 个随机字节再 hex 化，因此<b>奇数 len 会少一个字符</b>
     * （例如 {@code b(31)} 返回 30 个字符）。这里保留该行为，避免与上游产生长度差异。
     *
     * @param len 目标长度（实际长度为 {@code 2 * (len / 2)}）
     * @return 小写 hex 串
     */
    public static String randHex(int len) {
        return AesGcmCodec.hex(randBytes(Math.max(0, len) / 2));
    }

    /**
     * 生成 secp256r1 密钥对。
     *
     * @return 密钥对；失败返回 null
     */
    public static KeyPair generateEcKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * EC 公钥 → 65 字节未压缩点（{@code 0x04 || X(32) || Y(32)}）。
     *
     * @param key EC 公钥
     * @return 65 字节；失败返回 null
     */
    public static byte[] uncompressedPoint(PublicKey key) {
        if (!(key instanceof ECPublicKey)) {
            return null;
        }
        ECPoint w = ((ECPublicKey) key).getW();
        byte[] x = normalize32(w.getAffineX().toByteArray());
        byte[] y = normalize32(w.getAffineY().toByteArray());
        byte[] out = new byte[65];
        out[0] = 4;
        System.arraycopy(x, 0, out, 1, 32);
        System.arraycopy(y, 0, out, 33, 32);
        return out;
    }

    /**
     * 65 字节未压缩点 → EC 公钥（secp256r1）。
     *
     * @param point65 65 字节点
     * @return 公钥；失败返回 null
     */
    public static PublicKey publicKeyFromPoint(byte[] point65) {
        if (point65 == null || point65.length < 65) {
            return null;
        }
        try {
            byte[] x = Arrays.copyOfRange(point65, 1, 33);
            byte[] y = Arrays.copyOfRange(point65, 33, 65);
            ECPoint point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ECDH 共享秘密，归一化为 32 字节。
     *
     * @param privateKey 本方私钥
     * @param point65    对方 65 字节公钥点
     * @return 32 字节；失败返回 null
     */
    public static byte[] ecdhShared32(PrivateKey privateKey, byte[] point65) {
        try {
            PublicKey peer = publicKeyFromPoint(point65);
            if (peer == null) {
                return null;
            }
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(privateKey);
            ka.doPhase(peer, true);
            return normalize32(ka.generateSecret());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * md5 小写 hex（本类仅用于自检；协议层不依赖）。
     *
     * @param text 文本
     * @return 32 字符 hex；失败返回空串
     */
    public static String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return AesGcmCodec.hex(md.digest((text == null ? "" : text).getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 主流程自检：握手 → boot。
     *
     * <p>上游 {@code init} 里开线程跑的就是这两步（{@code Gulu.a(Gulu)}）。
     *
     * @return true 表示会话可用
     */
    public boolean warmUp() {
        if (!handshake()) {
            return false;
        }
        boot();
        return isBooted();
    }

    /** @return 供日志/断言用的源站表展示文本 */
    @Override
    public String toString() {
        return "GuluSession{handshaked=" + isHandshaked() + ", booted=" + booted
                + ", sources=" + sourceApis.size() + "}";
    }
}
