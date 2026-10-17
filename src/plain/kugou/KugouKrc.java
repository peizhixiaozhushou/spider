package com.example.tvbox.plain.kugou;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 酷狗 KRC 歌词解码器。
 *
 * <p>反编译原文: {@code ae/decompiled/Kugou.java:47-117}（{@code proxy} 的 {@code site=lrc} 分支）
 *
 * <h2>格式（三步，全是"没人会猜到"的那种）</h2>
 * <pre>
 *  1) Base64 解码（上游 android.util.Base64.decode(content, NO_WRAP)）
 *  2) 跳过<b>前 4 字节</b>，其余与一条 16 字节固定密钥循环异或
 *     密钥 = {64,71,97,119,94,50,116,71,81,54,49,45,206,210,110,105}
 *  3) zlib inflate → UTF-8 文本（KRC 歌词）
 * </pre>
 *
 * <h3>三处容易漏掉的细节</h3>
 * <ol>
 *   <li><b>跳过的是 4 字节而不是 16</b>：这 4 字节不是 IV，也不是长度；
 *       异或的下标 {@code i % 16} 从<b>跳过之后</b>重新从 0 开始算。</li>
 *   <li><b>密钥里有 3 个 &gt;127 的值</b>（206/210/110 之后的 206、210）——
 *       它们是 unsigned byte 字面量，Java 里必须写 {@code (byte) 206}
 *       才不会编译报错；本类统一存成 {@code int[]} 再 {@code & 0xFF}。</li>
 *   <li><b>长度 ≤ 4 直接判失败</b>：上游 {@code if (bArrDecode.length <= 4) 404}。
 *       本类同样拒绝，避免 inflate 空输入抛异常。</li>
 * </ol>
 *
 * <h2>为什么单独成类</h2>
 * 这段是 Kugou 里唯一的"算法"，跟取榜单/取播放地址完全无关。
 * 拆出来后 {@link #encodeFixture} 可以提供<b>自造向量</b>，
 * 于是解码器可以被真实往返验证，而不是靠一个写死的 Base64 串（那种断言其实证明不了什么）。
 */
public final class KugouKrc {

    /** 16 字节异或密钥（上游 {@code int[] iArr}，逐字） */
    public static final int[] XOR_KEY = {
            64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105};

    /** 前置跳过字节数 */
    public static final int SKIP = 4;

    private KugouKrc() {
    }

    /**
     * 第 2 步：跳过前 {@link #SKIP} 字节后循环异或。
     *
     * @param raw Base64 解码后的原始字节，可为 null
     * @return 异或结果；{@code raw} 为 null 或长度 ≤ {@link #SKIP} 时返回 {@code null}
     */
    public static byte[] unxor(byte[] raw) {
        if (raw == null || raw.length <= SKIP) {
            return null;
        }
        int len = raw.length - SKIP;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) ((raw[i + SKIP] & 0xFF) ^ XOR_KEY[i % XOR_KEY.length]);
        }
        return out;
    }

    /**
     * 第 3 步：zlib 解压。
     *
     * @param data 已异或的字节，可为 null
     * @return 解压结果；失败返回 {@code null}（不抛）
     */
    public static byte[] inflate(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        Inflater inf = new Inflater();
        try {
            inf.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    break;
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable e) {
            return null;
        } finally {
            inf.end();
        }
    }

    /**
     * 全流程解码（Base64 → 跳 4 字节 → 异或 → inflate → UTF-8）。
     *
     * @param content 歌词 {@code content} 字段（Base64 文本），可为 null
     * @return 解码后的歌词文本；任何一步失败返回空串（不抛）
     */
    public static String decode(String content) {
        byte[] raw = base64(content);
        if (raw == null) {
            return "";
        }
        String text = decodeBytes(raw);
        return text == null ? "" : text;
    }

    /**
     * Base64 解码（宽松，忽略空白）。
     *
     * @param content Base64 文本，可为 null
     * @return 字节；失败返回 null
     */
    public static byte[] base64(String content) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        try {
            return Base64.getMimeDecoder().decode(content.trim());
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 从 Base64 解码后的字节走完 2、3 步。
     *
     * <p>上游在 {@code proxy} 里对「解码后长度 ≤ 4」单独判 404，所以这里也把
     * 「长度不足」与「inflate 失败」区分开：两者都返回 null，由调用方决定状态码。
     *
     * @param raw Base64 解码后的字节，可为 null
     * @return 歌词文本；长度不足或 inflate 失败返回 null
     */
    public static String decodeBytes(byte[] raw) {
        if (raw == null || raw.length <= SKIP) {
            return null;
        }
        byte[] inflated = inflate(unxor(raw));
        return inflated == null ? null : new String(inflated, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 自造向量（仅测试/自检用）：把文本反过来编成 {@link #decode} 能解的形式。
     *
     * <pre>
     *  文本 → zlib deflate → 前置 4 字节 0x00 → 对其余字节循环异或 → Base64
     * </pre>
     * 前置的 4 字节<b>不参与异或</b>（与解码侧跳过它们的语义一致）。
     *
     * @param text 文本，可为 null
     * @return Base64 文本；失败返回空串
     */
    public static String encodeFixture(String text) {
        if (text == null) {
            return "";
        }
        byte[] deflated = deflate(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (deflated == null) {
            return "";
        }
        byte[] out = new byte[SKIP + deflated.length];
        // 前 4 字节直接被解码侧跳过，异或下标从跳过的位置重新从 0 开始
        // （这点必须与 decode/unxor 的 `i % 16` 下标一致，否则往返失败）
        for (int j = 0; j < deflated.length; j++) {
            out[SKIP + j] = (byte) ((deflated[j] & 0xFF) ^ XOR_KEY[j % XOR_KEY.length]);
        }
        return Base64.getEncoder().encodeToString(out);
    }

    /**
     * zlib 压缩（自造向量用）。
     *
     * @param data 原始字节，可为 null
     * @return 压缩结果；失败返回 null
     */
    public static byte[] deflate(byte[] data) {
        if (data == null) {
            return null;
        }
        Deflater def = new Deflater();
        try {
            def.setInput(data);
            def.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            while (!def.finished()) {
                int n = def.deflate(buf);
                if (n == 0) {
                    break;
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable e) {
            return null;
        } finally {
            def.end();
        }
    }
}
