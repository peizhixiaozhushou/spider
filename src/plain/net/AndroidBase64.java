package com.example.tvbox.plain.net;

import java.nio.charset.StandardCharsets;

/**
 * Android {@code android.util.Base64} 的 <b>flag 语义</b>实现。
 *
 * <h2>为什么需要它</h2>
 * 本项目 stubs 里的 {@code android.util.Base64} <b>忽略 flags</b>：
 * {@code encodeToString(b, 11)} 与 {@code encodeToString(b, 0)} 输出完全相同（标准字母表 + 补 '='），
 * 而 Android 上 {@code flag=11} 表示 {@code URL_SAFE | NO_PADDING | NO_WRAP}。
 *
 * <p>这一点对 {@code QiShui} 是硬伤：它的条目 id 形如
 * {@code mode:<base64url(json)>}、{@code video:<base64url(json)>}
 * （上游 {@code QiShui.s()} 用 {@code Base64.encodeToString(..., 11)}，
 * {@code QiShui.E()} 用 {@code Base64.decode(..., 10)}）。若按标准字母表输出，
 * 含 {@code +} {@code /} {@code =} 的 id 与真实插件的 id 不再一致；
 * 而 stubs 的 MIME 解码器又会把 URL-safe 的 {@code -} {@code _} <b>当非法字符丢掉</b>，
 * 于是解真实 id 时会静默损坏 JSON。
 *
 * <h2>实现范围与取舍</h2>
 * <ul>
 *   <li>支持 {@link #DEFAULT} / {@link #NO_PADDING} / {@link #NO_WRAP} / {@link #CRLF} / {@link #URL_SAFE}
 *       的组合，与我们的用例（0、2、8、10、11）语义一致。</li>
 *   <li><b>不主动折行</b>：Android 的 {@code DEFAULT} 会每 76 字符插入换行。本项目既有的
 *       {@code android.util.Base64} stub 从不折行，为保持全仓一致性，这里也只在显式传
 *       {@link #CRLF} 时折行。</li>
 *   <li>解码器<b>宽松</b>：丢弃字母表外字符、自动补 '='、两种字母表的 {@code +//} 与 {@code -/_}
 *       都接受（Android 的 URL_SAFE 解码器同样两种都吃）。非法长度（{@code len % 4 == 1}）
 *       抛 {@link IllegalArgumentException}，与 Android 行为一致。</li>
 * </ul>
 *
 * <h2>证据来源</h2>
 * {@code ae/decompiled/QiShui.java:257-283}（{@code s()} 用 11、{@code E()} 用 10）、
 * {@code ae/decompiled/Gulu.java:247}（用 2：NO_WRAP）、
 * {@code ae/decompiled/Hxq.java:585}（用 2）。
 *
 * <h2>建议</h2>
 * 更彻底的做法是把本类逻辑并回 {@code ae/stubs/android/util/Base64.java}（让所有既存
 * plain 代码受益）。此处先独立成类，避免在共享 stub 上制造写冲突。
 */
public final class AndroidBase64 {

    /** 默认：标准字母表 + 补 '='（本实现不折行，见类注释）。 */
    public static final int DEFAULT = 0;
    /** 不补 '='。 */
    public static final int NO_PADDING = 1;
    /** 不折行（本实现本来就只在 CRLF 下折行）。 */
    public static final int NO_WRAP = 2;
    /** 每 76 字符插入 CRLF。 */
    public static final int CRLF = 4;
    /** URL 安全字母表：62/63 用 '-'/'_'。 */
    public static final int URL_SAFE = 8;

    private static final char[] STD =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final char[] URL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    /** 字母表外字符的统一兜底值。 */
    private static final char NONE = (char) 0xFF;

    private AndroidBase64() {
    }

    /**
     * 编码。
     *
     * @param data  字节，null 视作空
     * @param flags 见类常量
     * @return 编码文本（可能为空串）
     */
    public static String encodeToString(byte[] data, int flags) {
        byte[] d = data == null ? new byte[0] : data;
        char[] table = (flags & URL_SAFE) != 0 ? URL : STD;
        boolean pad = (flags & NO_PADDING) == 0;
        StringBuilder sb = new StringBuilder(((d.length + 2) / 3) * 4 + 8);
        int i = 0;
        int col = 0;
        while (i + 2 < d.length) {
            int n = ((d[i] & 0xFF) << 16) | ((d[i + 1] & 0xFF) << 8) | (d[i + 2] & 0xFF);
            sb.append(table[(n >>> 18) & 0x3F]).append(table[(n >>> 12) & 0x3F])
              .append(table[(n >>> 6) & 0x3F]).append(table[n & 0x3F]);
            i += 3;
            col += 4;
            col = maybeWrap(sb, col, flags);
        }
        int rest = d.length - i;
        if (rest == 1) {
            int n = (d[i] & 0xFF) << 16;
            sb.append(table[(n >>> 18) & 0x3F]).append(table[(n >>> 12) & 0x3F]);
            if (pad) {
                sb.append("==");
            }
        } else if (rest == 2) {
            int n = ((d[i] & 0xFF) << 16) | ((d[i + 1] & 0xFF) << 8);
            sb.append(table[(n >>> 18) & 0x3F]).append(table[(n >>> 12) & 0x3F])
              .append(table[(n >>> 6) & 0x3F]);
            if (pad) {
                sb.append('=');
            }
        }
        return sb.toString();
    }

    /**
     * 编码（{@link #encodeToString} 的别名，便于与 Android API 对齐）。
     *
     * @param data  字节
     * @param flags flag
     * @return 编码文本
     */
    public static String encode(byte[] data, int flags) {
        return encodeToString(data, flags);
    }

    /**
     * 字符串便捷编码（UTF-8）。
     *
     * @param text  文本
     * @param flags flag
     * @return 编码文本
     */
    public static String encodeUtf8(String text, int flags) {
        return encodeToString((text == null ? "" : text).getBytes(StandardCharsets.UTF_8), flags);
    }

    /**
     * 解码。
     *
     * @param s     编码文本，null/空返回空数组
     * @param flags 见类常量（只影响字母表选择；解码器两种字母表都吃）
     * @return 原始字节
     * @throws IllegalArgumentException 有效字符数 {@code % 4 == 1}，无法构成合法分组
     */
    public static byte[] decode(String s, int flags) {
        if (s == null || s.isEmpty()) {
            return new byte[0];
        }
        int[] map = (flags & URL_SAFE) != 0 ? urlTable() : dualTable();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        int acc = 0;
        int bits = 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v = c < 128 ? map[c] : NONE;
            if (v == NONE) {
                continue;
            }
            acc = (acc << 6) | v;
            bits += 6;
            n++;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >>> bits) & 0xFF);
            }
        }
        if (n % 4 == 1) {
            throw new IllegalArgumentException("bad base64 length: " + n);
        }
        return out.toByteArray();
    }

    /**
     * 解码字节数组形式的输入。
     *
     * @param s     编码字节
     * @param flags flag
     * @return 原始字节
     */
    public static byte[] decode(byte[] s, int flags) {
        return decode(s == null ? "" : new String(s, StandardCharsets.ISO_8859_1), flags);
    }

    /**
     * 解码为 UTF-8 文本。
     *
     * @param s     编码文本
     * @param flags flag
     * @return 文本；解码失败返回空串
     */
    public static String decodeUtf8(String s, int flags) {
        try {
            return new String(decode(s, flags), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 若本行达到 76 字符且要求 CRLF，则插入换行。
     *
     * @param sb    输出
     * @param col   当前列
     * @param flags flag
     * @return 新列号
     */
    private static int maybeWrap(StringBuilder sb, int col, int flags) {
        if ((flags & CRLF) != 0 && col >= 76) {
            sb.append("\r\n");
            return 0;
        }
        return col;
    }

    /**
     * 标准字母表 + URL 字母表的并集（解码时两种都接受）。
     *
     * @return 128 项查找表
     */
    private static int[] dualTable() {
        int[] t = baseTable();
        t['-'] = 62;
        t['_'] = 63;
        return t;
    }

    /**
     * 标准字母表（外加 {@code -}/{@code _}），用于 URL_SAFE 解码。
     *
     * @return 128 项查找表
     */
    private static int[] urlTable() {
        return dualTable();
    }

    /**
     * 构造查找表：默认全部为 {@link #NONE}，只把字母表字符映射到 0..63。
     *
     * @return 128 项查找表
     */
    private static int[] baseTable() {
        int[] t = new int[128];
        for (int i = 0; i < t.length; i++) {
            t[i] = NONE;
        }
        for (int i = 0; i < STD.length; i++) {
            t[STD[i]] = i;
        }
        return t;
    }
}
