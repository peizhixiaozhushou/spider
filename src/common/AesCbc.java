package com.example.tvbox.common;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-CBC 二进制编解码 —— 处理"密钥与 IV 都以原始字节给出"的场景。
 *
 * <h2>为什么不直接用 {@code AesCrypto} / {@code AesGcm}</h2>
 * 那两个类面向的是 <b>字符串</b> 场景（明文是 UTF-8 文本、密文是 Base64/Hex）。
 * 但 spider 里还有一类协议是纯二进制的：
 * <ul>
 *   <li>字幕文件：<b>前 16 字节是 IV</b>，其后整体是密文，解出来仍是非 UTF-8 安全的字节流</li>
 *   <li>JS 混淆块：密钥与 IV 都是<b>数字字面量的 ASCII 字节</b>（如 {@code "0123456789abcdef"}）</li>
 * </ul>
 * 这类场景用字符串 API 会经过一次不必要的编解码，且无法表达"IV 前置"。
 *
 * <h2>padding 名称的坑（与 AesGcm 同一处）</h2>
 * Android/BouncyCastle 提供的名字是 {@code PKCS7Padding}；
 * 标准 JCE（桌面 JVM）<b>不认这个名字</b>，只认 {@code PKCS5Padding}。
 * AES 分组固定 16 字节，两者语义完全等价，因此本类统一用
 * {@code AES/CBC/PKCS5Padding}，在 Android 与桌面 JVM 上行为一致。
 *
 * <h2>错误语义</h2>
 * 所有方法在失败时返回 {@code null}（而不是抛异常）。
 * 调用方必须判空 —— 协议数据来自网络，任何一步都可能畸形。
 *
 * <h2>证据</h2>
 * 对齐上游 {@code merge.FM.o.a}（{@code c/d/g} 三个方法）的调用语义，
 * 以及 {@code merge.FM.E.a.a}（字节 → 字符串）的解码约定。
 */
public final class AesCbc {

    private static final String CBC_PKCS5 = "AES/CBC/PKCS5Padding";

    /** AES 分组长度，同时也是 IV 的标准长度 */
    public static final int BLOCK_SIZE = 16;

    private AesCbc() {
    }

    // ── 原始字节接口 ─────────────────────────────────────────

    /**
     * AES-CBC 解密（原始字节进、原始字节出）。
     *
     * @param data 密文
     * @param key  密钥，长度须为 16/24/32
     * @param iv   IV，长度须为 16
     * @return 明文；参数非法或解密失败返回 {@code null}
     */
    public static byte[] decrypt(byte[] data, byte[] key, byte[] iv) {
        if (data == null || key == null || iv == null || data.length == 0) {
            return null;
        }
        try {
            Cipher c = Cipher.getInstance(CBC_PKCS5);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * AES-CBC 加密（原始字节进、原始字节出）。主要供测试构造密文向量。
     *
     * @param data 明文
     * @param key  密钥
     * @param iv   IV
     * @return 密文；参数非法或加密失败返回 {@code null}
     */
    public static byte[] encrypt(byte[] data, byte[] key, byte[] iv) {
        if (data == null || key == null || iv == null) {
            return null;
        }
        try {
            Cipher c = Cipher.getInstance(CBC_PKCS5);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解密"IV 前置"格式：前 {@link #BLOCK_SIZE} 字节是 IV，其余是密文。
     *
     * <p>这是本项目里最少见但最容易写错的一种协议 —— 上游把 IV 直接拼在密文头部，
     * 于是"换 IV 无需额外传参"。长度不足一个分组时返回 {@code null}。
     *
     * @param data 前置 IV + 密文
     * @param key  密钥
     * @return 明文；长度不足或解密失败返回 {@code null}
     */
    public static byte[] decryptIvPrefixed(byte[] data, byte[] key) {
        if (data == null || key == null || data.length <= BLOCK_SIZE) {
            return null;
        }
        byte[] iv = Arrays.copyOfRange(data, 0, BLOCK_SIZE);
        byte[] body = Arrays.copyOfRange(data, BLOCK_SIZE, data.length);
        return decrypt(body, key, iv);
    }

    /**
     * 解密"IV 前置"格式并转成 UTF-8 字符串。
     *
     * @param data 前置 IV + 密文
     * @param key  密钥
     * @return 明文文本；失败返回 {@code null}
     */
    public static String decryptIvPrefixedToString(byte[] data, byte[] key) {
        byte[] plain = decryptIvPrefixed(data, key);
        return plain == null ? null : new String(plain, StandardCharsets.UTF_8);
    }

    // ── Base64 便捷接口 ──────────────────────────────────────

    /**
     * Base64 密文 + 显式 IV 解密并转字符串。
     *
     * @param base64 Base64 密文
     * @param key    密钥字节
     * @param iv     IV 字节
     * @return 明文文本；失败返回 {@code null}
     */
    public static String decryptBase64ToString(String base64, byte[] key, byte[] iv) {
        byte[] raw = decodeBase64(base64);
        byte[] plain = decrypt(raw, key, iv);
        return plain == null ? null : new String(plain, StandardCharsets.UTF_8);
    }

    /**
     * Base64 密文，IV 前置，解密并转字符串。
     *
     * @param base64 Base64 密文（含前置 IV）
     * @param key    密钥字节
     * @return 明文文本；失败返回 {@code null}
     */
    public static String decryptBase64IvPrefixedToString(String base64, byte[] key) {
        return decryptIvPrefixedToString(decodeBase64(base64), key);
    }

    // ── 编码工具 ─────────────────────────────────────────────

    /**
     * 宽松 Base64 解码：忽略换行/空白，并自动补 {@code =}。
     * 上游用的是 Android {@code Base64.decode(s, 0)}，其默认行为就是忽略空白。
     *
     * @param s Base64 文本，可为 null
     * @return 字节数组；无法解码返回 {@code null}
     */
    public static byte[] decodeBase64(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s", "");
        // URL-safe 变体（上游某些接口用 -_ 代替 +/）
        t = t.replace('-', '+').replace('_', '/');
        int rem = t.length() % 4;
        if (rem == 2) {
            t = t + "==";
        } else if (rem == 3) {
            t = t + "=";
        } else if (rem == 1) {
            return null;   // 长度非法
        }
        try {
            return java.util.Base64.getDecoder().decode(t);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 标准 Base64 编码（无换行）。
     *
     * @param data 原始字节，可为 null
     * @return Base64 文本；{@code data} 为 null 时返回 {@code null}
     */
    public static String encodeBase64(byte[] data) {
        return data == null ? null : java.util.Base64.getEncoder().encodeToString(data);
    }

    /**
     * 把字符串按 UTF-8 取字节 —— 上游 {@code key.getBytes()} 的无参默认编码版本。
     *
     * <p>⚠️ 上游用的是平台默认编码；在中文 Windows 上会是 GBK。
     * 本重写固定 UTF-8 —— 协议里这些字符串都是 ASCII 数字/字母，两种编码结果相同。
     *
     * @param s 文本；null 视为空
     * @return 字节；永不为 {@code null}
     */
    public static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 字符串整体反转。
     *
     * <p>上游有一个反复出现的"反着存"混淆手法：
     * 服务端把 Base64 串倒序后放进 JSON，前端 {@code reverse()} 之后再 Base64 解码。
     *
     * @param s 文本，可为 null
     * @return 反转后的文本；{@code s} 为 null 时返回空串
     */
    public static String reverse(String s) {
        return s == null ? "" : new StringBuilder(s).reverse().toString();
    }
}
