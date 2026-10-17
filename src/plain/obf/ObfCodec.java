package com.example.tvbox.plain.obf;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * 单字母混淆类 {@code a}~{@code e} 的<b>共享核心</b> —— 五个类的可复原语义都收敛在这里。
 *
 * <h2>为什么是"一个核心 + 五个壳"而不是五份拷贝</h2>
 * 交付前先做了逐文件 diff（`diff ae/decompiled/a.java ae/decompiled/{b,c,d,e}.java`），
 * 结论：<b>五个类的骨架完全同构</b>，差异只有三类 ——
 * ① 类名与方法名（同一个方法在不同类里叫 {@code b/c/d/e}）；
 * ② 一个静态常量 {@code public static int a} 的数值（-757/-711/148/650/417）；
 * ③ jadx 在<b>同一段循环上给出不同形态的反编译产物</b>（有的保留了 `for`，
 *    有的变成 `while` + 负下标、`byteArray[-1]`、自赋值 `length3 = 1` 的无限循环）。
 * 因此抄五份等于把 ③ 的损坏复制五遍；正确做法是把**唯一可复原的字节码意图**
 * 收在一处（本类），五个壳只保留各自的常量与"名字对应表"。
 *
 * <h2>逐文件 diff 的完整结论（含损坏点）</h2>
 * <table border="1">
 *   <tr><th>原类</th><th>常量 {@code a}</th><th>异或常量方法</th><th>哈希方法</th>
 *       <th>short[] 解码</th><th>String 解码方法名</th><th>反编译产物形态</th></tr>
 *   <tr><td>{@code a}</td><td>-757</td><td>{@code b()} = -563 ^ e.a</td>
 *       <td>{@code d(Object)}</td><td>{@code e(short[],int,int,int)}</td><td>{@code c(String)}</td>
 *       <td><b>损坏</b>：`while (string.length() > 0) { }` 空体死循环（string 从不改变）</td></tr>
 *   <tr><td>{@code b}</td><td>-711</td><td>{@code b()} = -542 ^ a（自身字段）</td>
 *       <td>{@code c(Object)}</td><td>{@code d(short[],int,int,int)}</td><td>{@code e(String)}</td>
 *       <td><b>损坏</b>：`byteArray[-1]` + `for(length3=0; ...; length3 = "".length()+1)` 自赋值死循环</td></tr>
 *   <tr><td>{@code c}</td><td>148</td><td>{@code d()} = 881 ^ b.a</td>
 *       <td>{@code c(Object)}</td><td>{@code e(short[],int,int,int)}</td><td>{@code b(String)}</td>
 *       <td><b>损坏</b>：`while (str.length() > 0) { charAt(-2)/charAt(-1) }` 负下标</td></tr>
 *   <tr><td>{@code d}</td><td>650</td><td>{@code b()} = 281 ^ e.a</td>
 *       <td>{@code e(Object)}</td><td>{@code c(short[],int,int,int)}</td><td>{@code d(String)}</td>
 *       <td><b>损坏</b>：与 {@code b} 同形（`byteArray[-1]` + 自赋值死循环）</td></tr>
 *   <tr><td>{@code e}</td><td>417</td><td>{@code e()} = 40 ^ b.a</td>
 *       <td>{@code b(Object)}</td><td>{@code c(short[],int,int,int)}</td><td>{@code d(String)}</td>
 *       <td><b>损坏</b>：与 {@code c} 同形（`while` + 负下标）</td></tr>
 * </table>
 * ★ 五个类里**只有 {@code a.c(String)} 一个方法保留了完整的 `for` 循环体**，
 * 它同时给出两段可复原逻辑（成对 hex 解码 + 掩码异或）。本类就是按这两段实现的，
 * 其余四个类的同一方法<b>不猜、不改写</b>，只在各自壳里标注损坏形态与原文件行号。
 *
 * <h2>★ 重要事实：这不是确定性解码器</h2>
 * 掩码 {@code str2} 由 {@code Math.random()} 现场生成（smali 里逐字可验：
 * {@code invoke-static {…} Ljava/lang/Math;->random()D}），
 * <b>每次调用都不同</b>：
 * <pre>
 *   for (i = 0; i < 15; i++) {
 *       string = string + Integer.toHexString(i);              // → "0123456789abcde"
 *       str2   = str2   + ((int) (Math.random() * 10) ^ i);    // → 15 个随机掩码字符
 *   }
 * </pre>
 * 于是"同一密文 → 同一明文"在这五个类里<b>不成立</b>。可能性只有两种：
 * ① 真机上 {@code Math.random()} 被替换成确定性实现；
 * ② 这段本来就是混淆噪声/干扰项，不是真的字符串解密原语。
 * 无论哪种，本类都<b>不假装它是可用解码器</b>：
 * {@link #decode(String)} 保留随机掩码（与字节码一致，输出不可复现），
 * 真正可用的入口是 {@link #decode(String, String)}（掩码显式给入，确定、可测）。
 * 项目里真正的字符串解密原语（短整型数组 XOR）另有其人，见
 * {@code maintain/src/plain/pan/client/PanObfStrings.java}。
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>随机掩码下的取回</b>：{@link #decode(String)} 的输出不可复现，
 *       所以"解出来的字符串是什么"这一类断言在本类里做不到（也不可能做到），
 *       测试只钉住"随机掩码会变"与"显式掩码可复现"。</li>
 *   <li><b>{@code indexOf} 找不到字符时的语义</b>：上游返回 -1，参与
 *       {@code (a<<4)|b} 后写出的字节是 {@code 0xFF}（不是异常）；
 *       非 hex 字符因此被静默吞掉 —— 本类保留该行为并加了向量。</li>
 *   <li><b>四个损坏方法</b>：{@code a.c}/{@code b.e}/{@code c.b}/{@code d.d}/{@code e.d}
 *       的反编译产物不可执行，<b>不还原</b>；见 {@link #DAMAGED_METHODS} 的逐条登记
 *       （含原文件与行号），那才是诚实的交付形态。</li>
 * </ol>
 *
 * <h2>上游缺陷 / 反编译损坏点</h2>
 * <ol>
 *   <li><b>五个类的 String 解码方法全部不可执行</b>（形态见上表）：
 *       两个是 {@code byteArray[-1]} + 自赋值死循环，两个是负下标，
 *       一个是空体死循环。jadx 在同一段字节码上给出五种不同产物本身就是
 *       "这段代码被刻意写成难以反编译"的旁证。</li>
 *   <li><b>异或常量跨类引用</b>：{@code a}/{@code d} 引用 {@code e.a}，
 *       {@code c}/{@code e} 引用 {@code b.a}，{@code b} 引用自身；
 *       五个类因此不可单独摘除（少一个就 {@code NoClassDefFoundError}），
 *       而它们算出来的常量没有任何一处被使用 —— 典型的"用来撑依赖图"的噪声。</li>
 *   <li><b>哈希方法只是 {@code obj.hashCode()}</b>：五个类各有一份，
 *       放在混淆类里毫无作用。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * 反编译原文 {@code ae/decompiled/a.java}、{@code ae/decompiled/b.java}、
 * {@code ae/decompiled/c.java}、{@code ae/decompiled/d.java}、{@code ae/decompiled/e.java}
 * （各 50~52 行，逐行 diff 见上表）；字节码核对
 * {@code com.github.catvod.spider.a.c(String)} 的 smali（{@code Math.random()D} 调用）。
 */
public final class ObfCodec {

    private static final String TAG = "ObfCodec";

    /** 上游用"逐个 append {@code Integer.toHexString(i)}"拼出来的 15 字符表，值即 {@code 0123456789abcde} */
    public static final String HEX_ALPHABET = buildAlphabet();

    /** 掩码长度（上游循环 {@code i < 15}） */
    public static final int MASK_LEN = 15;

    /** 掩码取值上界（上游 {@code Math.random() * 10}） */
    public static final int MASK_RANGE = 10;

    /**
     * 损坏方法登记表 —— 键是 {@code ae/decompiled/<文件>:<方法>}，值是损坏形态。
     * 测试会逐条核对，防止有人"顺手修"其中一条却不改另一条。
     */
    public static final Map<String, String> DAMAGED_METHODS = buildDamaged();

    private ObfCodec() {
    }

    /** 复刻上游那段 append 循环（不是硬编码字面量，顺序与实现都保持一致） */
    private static String buildAlphabet() {
        String s = "";
        for (int i = 0; i < MASK_LEN; i++) {
            s = new StringBuilder().append(s).append(Integer.toHexString(i)).toString();
        }
        return s;
    }

    private static Map<String, String> buildDamaged() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ae/decompiled/a.java:c(String)",
                "while (string.length() > 0) {} 空体死循环（string 是 15 字符表，从不改变）");
        m.put("ae/decompiled/b.java:e(String)",
                "byteArray[-1] 负下标 + for(length3 = \"\".length() + 1) 自赋值死循环");
        m.put("ae/decompiled/c.java:b(String)",
                "while (str.length() > 0) { charAt(-2) / charAt(-1) } 负下标");
        m.put("ae/decompiled/d.java:d(String)",
                "byteArray[-1] 负下标 + for(length3 = \"\".length() + 1) 自赋值死循环");
        m.put("ae/decompiled/e.java:d(String)",
                "while (str.length() > 0) { charAt(-2) / charAt(-1) } 负下标");
        return m;
    }

    // ── 可复原核心（逐字来自 a.c(String) 里保留下来的那两段 for） ──

    /**
     * 成对 hex 解码 —— 逐字复原 {@code a.c(String)} 里的第二个循环：
     * <pre>
     *   ByteArrayOutputStream out = new ByteArrayOutputStream(str.length() / 2);
     *   for (int i = 0; i &lt; str.length(); i += 2) {
     *       out.write((string.indexOf(str.charAt(i)) &lt;&lt; 4) | string.indexOf(str.charAt(i + 1)));
     *   }
     * </pre>
     * ★ 保留三个上游细节，都<b>不</b>修正：
     * <ul>
     *   <li>奇数长度 → {@code charAt(i+1)} 越界 → {@code StringIndexOutOfBoundsException}；</li>
     *   <li>非 hex 字符 → {@code indexOf} 返回 -1 → {@code (-1&lt;&lt;4)|x} 仍是负数，
     *       {@code write()} 取低 8 位（全 -1 时是 {@code 0xFF}），<b>不抛异常</b>；</li>
     *   <li>初始容量 {@code length/2} 只是预分配，不影响结果。</li>
     * </ul>
     *
     * @param hex 输入的"hex"串（可用字符只有 {@code 0123456789abcde}）
     * @return 解码后的字节
     * @throws StringIndexOutOfBoundsException 长度为奇数（上游同样抛）
     */
    public static byte[] hexPairsToBytes(String hex) {
        String str = hex == null ? "" : hex;
        ByteArrayOutputStream out = new ByteArrayOutputStream(str.length() / 2);
        for (int i = 0; i < str.length(); i += 2) {
            out.write((HEX_ALPHABET.indexOf(str.charAt(i)) << 4)
                    | HEX_ALPHABET.indexOf(str.charAt(i + 1)));
        }
        return out.toByteArray();
    }

    /**
     * 掩码异或 —— 逐字复原 {@code a.c(String)} 里的第三个循环：
     * {@code byteArray[i] = (byte) (byteArray[i] ^ str2.charAt(i % 掩码长度))}。
     *
     * <p>注意 {@code charAt} 取的是 <b>char</b>（16 位）与 <b>byte</b> 异或：
     * 先被提升为 int，结果再截回 byte。掩码为空串时上游会
     * {@code ArithmeticException: / by zero}（{@code i % 0}），本方法同样不救。
     *
     * @param data 输入字节
     * @param mask 掩码，长度不能为 0
     * @return 异或后的结果（UTF-8 解码成字符串，与上游 {@code new String(byteArray)} 一致）
     * @throws ArithmeticException 掩码长度为 0
     */
    public static String xorToString(byte[] data, String mask) {
        if (data == null) {
            return "";
        }
        String m = mask == null ? "" : mask;
        byte[] b = data.clone();
        int len = b.length;
        int maskLen = m.length();
        for (int i = 0; i < len; i++) {
            b[i] = (byte) (b[i] ^ m.charAt(i % maskLen));
        }
        return new String(b);
    }

    /**
     * 生成 15 字符随机掩码 —— 逐字复原上游那段 {@code Math.random()} 循环。
     *
     * @param random 随机源；传 null 时用 {@code Math.random()} 语义（不可复现）
     * @return 长度恒为 {@link #MASK_LEN} 的掩码（字符码位落在 0~15 的控制字符区）
     */
    public static String randomMask(Random random) {
        StringBuilder sb = new StringBuilder(MASK_LEN);
        for (int i = 0; i < MASK_LEN; i++) {
            int r = random == null ? (int) (Math.random() * MASK_RANGE)
                    : random.nextInt(MASK_RANGE);
            sb.append((char) (r ^ i));
        }
        return sb.toString();
    }

    /**
     * 完整链路（<b>随机掩码</b>）—— 与字节码行为一致，但输出不可复现。
     *
     * @param hex 输入的"hex"串
     * @return 每次调用都不同的字符串（因为掩码随机）
     */
    public static String decode(String hex) {
        try {
            return xorToString(hexPairsToBytes(hex), randomMask(null));
        } catch (Throwable e) {
            Log.w(TAG, "decode 失败（上游同样不抛给调用方）");
            return "";
        }
    }

    /**
     * 完整链路（<b>显式掩码</b>）—— 唯一可复现、可用于真实解码的入口。
     *
     * @param hex  输入的"hex"串
     * @param mask 掩码（长度必须与生成时一致，否则结果不同）
     * @return 解码结果
     */
    public static String decode(String hex, String mask) {
        try {
            return xorToString(hexPairsToBytes(hex), mask);
        } catch (Throwable e) {
            Log.w(TAG, "decode(hex,mask) 失败");
            return "";
        }
    }

    /**
     * 短整型数组 XOR 解码 —— 逐字复原 {@code a.e(short[],int,int,int)}：
     * {@code cArr[i] = (char) (sArr[i + off] ^ seed)}。
     *
     * <p>这是本项目<b>真正的</b>字符串解密原语（域内其它等价实现见
     * {@code maintain/src/plain/pan/client/PanObfStrings.java}）。
     * 本方法<b>不做任何越界检查</b>：与上游一致，越界抛
     * {@code ArrayIndexOutOfBoundsException}。
     *
     * @param sArr 源数组
     * @param offset 起始下标
     * @param count 取多少个字符
     * @param seed 异或种子（可为负；{@code char} 强转会截到 16 位）
     * @return 解码结果
     * @throws ArrayIndexOutOfBoundsException {@code offset + count > sArr.length}
     * @throws NullPointerException {@code sArr} 为 null
     */
    public static String decodeShorts(short[] sArr, int offset, int count, int seed) {
        char[] out = new char[count];
        for (int i = 0; i < count; i++) {
            out[i] = (char) (sArr[offset + i] ^ seed);
        }
        return new String(out);
    }

    /**
     * 逐字复原五个类共有的 {@code <x>(Object)} —— 就是 {@code obj.hashCode()}。
     *
     * @param obj 任意对象；null 时上游会 NPE（同样不救：{@code Object.hashCode} 可空，
     *            但上游写法是 {@code obj.hashCode()}）
     * @return 哈希值
     */
    public static int hashCodeOf(Object obj) {
        return obj.hashCode();
    }
}
