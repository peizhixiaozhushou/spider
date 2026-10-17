package com.example.tvbox.plain.pan.client;

/**
 * 异或字符串表解码器 —— 还原上游 {@code com.github.catvod.spider.{a,b,c,d,e}} 的
 * 静态字符串解混淆。
 *
 * <h2>为什么需要它</h2>
 * DEX 里 UC/Quark 等网盘的 URL、请求头、缓存目录名、JSON 字段名全部不是明文字面量，
 * 而是「一个 {@code short[]} 常量池 + 偏移 + 长度 + 种子」的四元组：
 * <pre>
 *   z = d.c(f14short, 0, 6, 2959)      // == "UCtemp"
 * </pre>
 * 上游 {@code com.github.catvod.spider.a/b/c/d/e} 五个类各自提供一个同签名的
 * {@code e(short[],int,int,int)} / {@code c(...)} / {@code d(...)}，<b>五个方法体完全一致</b>：
 * <pre>
 *   char[] out = new char[len];
 *   for (int i = 0; i &lt; len; i++) out[i] = (char)(arr[off + i] ^ seed);
 *   return new String(out);
 * </pre>
 * 即「逐字符与种子异或」。本类把这段逻辑抽成一处，供所有网盘客户端复用。
 *
 * <h2>语义细节（与 JVM 严格一致）</h2>
 * <ul>
 *   <li>{@code short} 参与 {@code ^ int} 时先做<b>符号扩展</b>再截断到 16 位。
 *       上游所有表项都 &lt; 32768（实测最大 3325），因此符号扩展不改变结果；
 *       本实现仍按 {@code & 0xFFFF} 归一，对负值也与 JVM 行为一致。</li>
 *   <li>{@code seed} 可以 &gt; 0xFFFF（实测最大 3236，未越界），此时异或结果仍按 16 位截断。</li>
 *   <li>解码结果可能含 {@code \u0000}；本实现不夹断、不过滤、不 trim，保持与上游一致。</li>
 * </ul>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/a.java} /
 * {@code ae/decompiled/b.java} /
 * {@code ae/decompiled/c.java} /
 * {@code ae/decompiled/d.java} /
 * {@code ae/decompiled/e.java}（五份 {@code e(short[],int,int,int)} 方法体逐字一致）。
 * 调用点样本: {@code ae/decompiled/UCPan.java} 73 处、{@code ae/decompiled/QuarkPan.java} 75 处，
 * 全部 148 条字符串已解出并落在 {@code ae/work/w-pan/decoded-strings.txt}。
 */
public final class PanObfStrings {

    private PanObfStrings() {
    }

    /**
     * 解码一段异或字符串。
     *
     * <p>等价于上游的 {@code a.e/ b.d / c.e / d.c / e.c (short[], int, int, int)}。
     *
     * @param table short 常量池，允许为 null（返回空串）
     * @param offset 起始下标；越界时按「能取多少取多少」处理而不是抛异常
     * @param length 期望长度（字符数）
     * @param seed   异或种子
     * @return 解码后的字符串；参数非法时返回空串
     */
    public static String decode(short[] table, int offset, int length, int seed) {
        if (table == null || length <= 0 || offset < 0 || offset >= table.length) {
            return "";
        }
        int n = Math.min(length, table.length - offset);
        char[] out = new char[n];
        for (int i = 0; i < n; i++) {
            // short 先符号扩展成 int，异或后再截断为 16 位 —— 与 JVM 的 (char) 转换一致。
            out[i] = (char) ((table[offset + i] ^ seed) & 0xFFFF);
        }
        return new String(out);
    }

    /**
     * 解码并把结果 trim —— 上游大量字面量尾部带空格（如 {@code "Captcha token: "}），
     * 拼接 URL 时必须保留，比较时通常需要 trim。
     *
     * @see #decode(short[], int, int, int)
     */
    public static String decodeTrimmed(short[] table, int offset, int length, int seed) {
        return decode(table, offset, length, seed).trim();
    }

    /**
     * 编码一段文本 —— 反向操作，仅用于自测与夹具构造。
     *
     * <p>把明文异或成表项，便于写「固定输入 → 固定输出」的向量测试。
     *
     * @param text 明文
     * @param seed 与解码相同的种子
     * @return 表项数组；text 为 null 时返回空数组
     */
    public static short[] encode(String text, int seed) {
        if (text == null) {
            return new short[0];
        }
        short[] out = new short[text.length()];
        for (int i = 0; i < text.length(); i++) {
            out[i] = (short) ((text.charAt(i) ^ seed) & 0xFFFF);
        }
        return out;
    }
}
