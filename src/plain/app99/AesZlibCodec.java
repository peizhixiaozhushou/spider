package com.example.tvbox.plain.app99;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 「密钥派生自设备 ID + 随机 IV 前置 + zlib 压缩」信封编解码器
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么单独抽出来
 * ═══════════════════════════════════════════════════════════════════════
 * App99 的请求/响应封装是「IV 前缀 + 密文」这种自描述信封：
 * 发送方把随机 IV 拼在密文前面，接收方无须协商 IV 即可解密。
 * 这类封装在同类 App 站群里反复出现（YunDuo 的 web-sign、App3Q 的 x-device-id
 * 都可能采用类似形态），故抽为通用工具，避免每个站点各写一遍。
 *
 * 本类的**唯一权威来源**是 App99 的两处静态观测：
 *
 *   加密  ev_040ddc7b9b5c3755f5e00bc44bfe009f31ce2c7364aa86daf3da450b8d2083ca
 *     App99.d(String str):
 *       String str2 = TextUtils.isEmpty("") ? this.c : "";       // 恒取 this.c
 *       SecretKeySpec ks = new SecretKeySpec(str2.replace("-","").getBytes("UTF-8"), "AES");
 *       Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
 *       c.init(1, ks);                    // 1=ENCRYPT，不传 IV → provider 随机生成
 *       byte[] ct = c.doFinal(plain);
 *       byte[] iv = c.getIV();            // ★ 必须取回
 *       return Base64(iv || ct);
 *
 *   解密  ev_fbdcdf087369ede01fe367a3e6f0ff0ca829c9b154abc9f2a36763cafbb79951
 *     App99.c(String str, String str2):
 *       if (TextUtils.isEmpty(str2)) str2 = this.c;
 *       byte[] raw = Base64.getDecoder().decode(str);
 *       iv = raw[0..16);  body = raw[16..);
 *       SecretKeySpec ks = new SecretKeySpec(str2.replace("-","").getBytes("UTF-8"), "AES");
 *       Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
 *       c.init(2, ks, new IvParameterSpec(iv));
 *       byte[] pt = c.doFinal(body);
 *       try  { Inflater → UTF-8 }          // 先解压
 *       catch{ new String(pt,"UTF-8") }    // 解压失败当纯文本
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 密钥长度：32 字符 → AES-256，不是 AES-128
 * ═══════════════════════════════════════════════════════════════════════
 *   UUID.randomUUID() 得 36 字符，去掉 4 个 '-' 得 **32 字符 = 32 字节 → AES-256**。
 *   「UUID 是 128 bit」说的是 UUID 自身的熵，与去横线后字符串的字节数是两件事，
 *   极易误判成 AES-128。{@link #keyBits(String)} 实测算位数，不接受直觉。
 *   AES 只接受 16/24/32 字节，其它长度 {@link SecretKeySpec} 会抛
 *   InvalidKeyException —— {@link #encrypt} / {@link #decrypt} 捕获后返回空串。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 与原实现的差异（都是缺陷修正，非语义变更）
 * ═══════════════════════════════════════════════════════════════════════
 *   1) 原解密循环 `while(!inflater.finished()) write(inflate(buf))` 对空输入/畸形
 *      数据会因 inflate() 恒返回 0 而**死循环**。本版在 n==0 且 needsInput()/
 *      needsDictionary() 时跳出，避免挂死。正常数据路径完全一致。
 *   2) 解压结果为空串时回退纯文本，与原 catch 分支行为对齐。
 *   3) 显式 UTF-8（Android 默认即 UTF-8，无行为差异）。
 *
 * 目标 APK 实测 SHA-256: e0ab6d3d6e244e0503a7cd31334d23266f20671905f8e18bff2a5fa980eb1c90
 * 提供者: JADX headless 0.7.1，全程静态，APK 从未执行。
 */
public final class AesZlibCodec {

    /** IV 长度恒为 16（AES 分组） */
    public static final int IV_LEN = 16;

    private AesZlibCodec() {}

    // ═════════════════════════════════════════════════════════════════
    // 密钥
    // ═════════════════════════════════════════════════════════════════

    /**
     * 由设备 ID 派生 AES 密钥字节：去掉所有 '-' 后取 UTF-8。
     * 逐字对应原实现 {@code str2.replace("-","").getBytes("UTF-8")}。
     *
     * @param deviceId 通常是 UUID 字符串；允许传入已去横线的形式
     */
    public static byte[] deriveKey(String deviceId) {
        if (deviceId == null) deviceId = "";
        return deviceId.replace("-", "").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 报告派生出的密钥位数（128/192/256），非法长度返回 -1。
     * 用于测试与自检，避免把 32 字符误当成 AES-128。
     */
    public static int keyBits(String deviceId) {
        switch (deriveKey(deviceId).length) {
            case 16: return 128;
            case 24: return 192;
            case 32: return 256;
            default: return -1;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // 加密
    // ═════════════════════════════════════════════════════════════════

    /**
     * 加密为「IV 前缀信封」的 Base64。
     *
     * 流程：AES/CBC/PKCS5 用随机 IV 加密 → 取回 getIV() → 拼 IV(16)||密文 → Base64。
     *
     * @param plain    明文
     * @param deviceId 密钥来源（UUID）
     * @return Base64(iv||ct)；任何异常返回空串（与原实现一致）
     */
    public static String encrypt(String plain, String deviceId) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveKey(deviceId), "AES"));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(concatIv(c.getIV(), ct));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 指定 IV 加密（仅用于构造可复现夹具；真实链路必须用随机 IV）。
     *
     * @param iv 必须恰好 16 字节
     */
    public static String encryptWithIv(String plain, String deviceId, byte[] iv) {
        if (iv == null || iv.length != IV_LEN) return "";
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveKey(deviceId), "AES"),
                   new IvParameterSpec(iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(concatIv(iv, ct));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 加密前先 zlib 压缩，再做信封（服务端若期望压缩体时使用）。
     *
     * ★ 必须直接加密压缩后的**原始字节**；若把它先转成 String 再 getBytes(UTF-8)，
     *   非 UTF-8 可表示的字节会被替换（U+FFFD），压缩流立刻损坏。
     *   故这里走字节版内部方法，不经 String 中转。
     */
    public static String encryptCompressed(String plain, String deviceId) {
        try {
            byte[] z = deflate(plain.getBytes(StandardCharsets.UTF_8));
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveKey(deviceId), "AES"));
            byte[] ct = c.doFinal(z);
            return Base64.getEncoder().encodeToString(concatIv(c.getIV(), ct));
        } catch (Exception e) {
            return "";
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // 解密
    // ═════════════════════════════════════════════════════════════════

    /**
     * 解「IV 前缀信封」的 Base64，自动尝试 zlib 解压。
     *
     * 流程：Base64 → 拆 [0,16) 为 IV、[16,) 为密文 → AES/CBC 解密 → zlib inflate → UTF-8。
     * 解压失败回退为纯文本（逐字对应原实现的 catch 分支）。
     *
     * @param envelopeB64 Base64(iv||ct)
     * @param deviceId    密钥来源；为空时用 fallbackDeviceId
     * @param fallbackDeviceId 可为 null
     * @return 明文；任何异常返回空串
     */
    public static String decrypt(String envelopeB64, String deviceId, String fallbackDeviceId) {
        String keySrc = (deviceId == null || deviceId.isEmpty())
                ? (fallbackDeviceId == null ? "" : fallbackDeviceId) : deviceId;
        try {
            byte[] raw = Base64.getDecoder().decode(envelopeB64);
            if (raw.length <= IV_LEN) return "";
            byte[] iv = new byte[IV_LEN];
            byte[] body = new byte[raw.length - IV_LEN];
            System.arraycopy(raw, 0, iv, 0, IV_LEN);
            System.arraycopy(raw, IV_LEN, body, 0, body.length);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(deriveKey(keySrc), "AES"),
                   new IvParameterSpec(iv));
            return inflateOrText(c.doFinal(body));
        } catch (Exception e) {
            return "";
        }
    }

    /** 便捷重载：deviceId 必填 */
    public static String decrypt(String envelopeB64, String deviceId) {
        return decrypt(envelopeB64, deviceId, null);
    }

    /** 只解密不尝试解压（服务端明确返回纯文本时用） */
    public static String decryptRaw(String envelopeB64, String deviceId, String fallbackDeviceId) {
        String keySrc = (deviceId == null || deviceId.isEmpty())
                ? (fallbackDeviceId == null ? "" : fallbackDeviceId) : deviceId;
        try {
            byte[] raw = Base64.getDecoder().decode(envelopeB64);
            if (raw.length <= IV_LEN) return "";
            byte[] iv = new byte[IV_LEN];
            byte[] body = new byte[raw.length - IV_LEN];
            System.arraycopy(raw, 0, iv, 0, IV_LEN);
            System.arraycopy(raw, IV_LEN, body, 0, body.length);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(deriveKey(keySrc), "AES"),
                   new IvParameterSpec(iv));
            return new String(c.doFinal(body), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 解密为**原始字节**，不做 String 转换、不尝试解压。
     *
     * 当密文里装的是二进制（例如 zlib 流、protobuf）时，必须走这个方法；
     * 若经 String 中转，非 UTF-8 可表示的字节会被替换成 U+FFFD 而永久损坏。
     *
     * @return 明文字节；结构非法或解密失败返回 null
     */
    public static byte[] decryptToBytes(String envelopeB64, String deviceId,
                                        String fallbackDeviceId) {
        String keySrc = (deviceId == null || deviceId.isEmpty())
                ? (fallbackDeviceId == null ? "" : fallbackDeviceId) : deviceId;
        try {
            byte[][] p = splitEnvelope(envelopeB64);
            if (p == null) return null;
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(deriveKey(keySrc), "AES"),
                   new IvParameterSpec(p[0]));
            return c.doFinal(p[1]);
        } catch (Exception e) {
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // zlib
    // ═════════════════════════════════════════════════════════════════

    /**
     * zlib 解压，失败回退纯文本。
     * 逐字复原原实现的 try/catch 语义，并加入死循环保护（见类头「与原实现的差异」）。
     */
    public static String inflateOrText(byte[] data) {
        if (data == null || data.length == 0) return "";
        try {
            Inflater inf = new Inflater();
            inf.setInput(data);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length);
            byte[] buf = new byte[1024];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    // 原实现此处会死循环；正常 zlib 数据不会走到这里
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                bos.write(buf, 0, n);
            }
            inf.end();
            bos.close();
            String s = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            return s.isEmpty() ? new String(data, StandardCharsets.UTF_8) : s;
        } catch (Exception e) {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    /** zlib 压缩（构造夹具 / 需要压缩上行时用） */
    public static byte[] deflate(byte[] data) {
        Deflater d = new Deflater();
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(data.length * 2, 64));
        byte[] buf = new byte[1024];
        while (!d.finished()) {
            int n = d.deflate(buf);
            bos.write(buf, 0, n);
        }
        d.end();
        return bos.toByteArray();
    }

    // ═════════════════════════════════════════════════════════════════
    // 信封结构工具
    // ═════════════════════════════════════════════════════════════════

    /**
     * 拆信封：返回 {iv, ciphertext}；结构非法返回 null。
     * 便于上层在解密前校验长度（避免把过短输入当密文处理）。
     */
    public static byte[][] splitEnvelope(String envelopeB64) {
        try {
            byte[] raw = Base64.getDecoder().decode(envelopeB64);
            if (raw.length <= IV_LEN) return null;
            byte[] iv = new byte[IV_LEN];
            byte[] body = new byte[raw.length - IV_LEN];
            System.arraycopy(raw, 0, iv, 0, IV_LEN);
            System.arraycopy(raw, IV_LEN, body, 0, body.length);
            return new byte[][]{iv, body};
        } catch (Exception e) {
            return null;
        }
    }

    /** 信封长度是否合法（>16 且密文段为 16 的整数倍） */
    public static boolean isValidEnvelope(String envelopeB64) {
        byte[][] p = splitEnvelope(envelopeB64);
        return p != null && p[1].length % 16 == 0;
    }

    private static byte[] concatIv(byte[] iv, byte[] ct) {
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }
}
