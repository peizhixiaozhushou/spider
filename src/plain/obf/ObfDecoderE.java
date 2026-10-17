package com.example.tvbox.plain.obf;

/**
 * 原 {@code com.github.catvod.spider.e} —— 单字母混淆类之一，语义壳。
 *
 * <h2>身份</h2>
 * <pre>
 *   原类         : com.github.catvod.spider.e
 *   反编译原文   : ae/decompiled/e.java（50 行）
 *   静态常量     : public static int a = 417
 *   异或常量方法 : e()   原文 40 ^ b.a（引用类 b 的字段 a = -711）
 *                  → 实测值 -751
 * </pre>
 * 五个同族类（{@code a}~{@code e}）的逐文件 diff 结论与损坏清单见 {@link ObfCodec} 的类注释；
 * 本壳只保留「这一个类独有」的两样东西：<b>常量</b>与<b>名字对应表</b>。
 * 所有可复原逻辑都收在 {@code ObfCodec}（同一段字节码意图不抄五份）。
 *
 * <h2>方法名对应表（原名 → 本重写语义名）</h2>
 * <table border="1">
 *   <tr><th>原方法</th><th>语义名</th></tr>
 *   <tr><td>{@code b(Object)}</td><td>{@code hashCodeOf(Object)}</td></tr>
 *   <tr><td>{@code d(String)}</td><td>{@code decode(String) / decode(String,String)}</td></tr>
 *   <tr><td>{@code e()}</td><td>{@code xorConst()}</td></tr>
 *   <tr><td>{@code c(short[],int,int,int)}</td><td>{@code decodeShorts(short[],int,int,int)}</td></tr>
 * </table>
 *
 * <h2>反编译损坏点（不还原）</h2>
 * <p><b>{@code e(String)}</b>（原名见上表）：`while (str.length() > 0) { charAt(-2) / charAt(-1) }` —— 与 c.b(String) 同形的负下标
 * <p>按纪律：<b>不猜</b>、不按「看起来应该怎样」补循环；只在此处说明 + 在
 * {@link ObfCodec#DAMAGED_METHODS} 里登记。上游原始字节见 {@code ae/decompiled/e.java}。
 * 
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li>本壳没有独立逻辑（全部转发），分支覆盖等同于 {@link ObfCodec}：
 *       {@code String} 解码在随机掩码下不可复现，因此只做「掩码会变」与
 *       「显式掩码可复现」两类断言。</li>
 *   <li>损坏方法的 <b>真机行为无法验证</b>：本地根本执行不到（死循环 / 负下标）。
 *       若真机上它确实在跑，其语义必然与反编译产物不同 —— 那属于证据缺失，
 *       不属于「可以推断」。</li>
 * </ol>
 *
 * <h2>上游缺陷</h2>
 * <ol>
 *   <li>异或常量方法算出的值 <b>没有任何调用点</b>（跨类引用 {@code 40 ^ b.a（引用类 b 的字段 a = -711）}），
 *       作用是把五个类绑成一个拆不开的依赖团。</li>
 *   <li>同一段字节码在五个类里被反编译成五种形态，其中四种直接不可执行 ——
 *       源码被刻意写成难以反编译。</li>
 *   <li>{@code hashCode(Object)} 五个类各存一份，纯噪声。</li>
 * </ol>
 */
public final class ObfDecoderE {

    /** 工具类，禁止实例化 */
    private ObfDecoderE() {
    }

    /** 原类字段 {@code public static int a} */
    public static final int CONST = 417;

    /**
     * 原 {@code e.e()}：40 ^ b.a（引用类 b 的字段 a = -711）。
     *
     * <p>★ 该值在原 APK 里 <b>从未被使用</b>；保留它只为让「五个类的常量表」可核对
     * （测试逐值钉住）。
     *
     * @return 异或结果
     */
    public static int xorConst() {
        return -751;
    }

    /**
     * 原 {@code e} 的十六进制串解码（随机掩码版本，与字节码一致）。
     *
     * @param hex 输入串
     * @return 输出；<b>每次调用都不同</b>（掩码来自 Math.random）
     */
    public static String decode(String hex) {
        return ObfCodec.decode(hex);
    }

    /**
     * 原 {@code e} 的十六进制串解码（显式掩码，可复现）。
     *
     * @param hex  输入串
     * @param mask 掩码
     * @return 解码结果
     */
    public static String decode(String hex, String mask) {
        return ObfCodec.decode(hex, mask);
    }

    /**
     * 原 {@code short[]} 解码（项目里真正的字符串解密原语）。
     *
     * @param sArr   源数组
     * @param offset 起始下标
     * @param count  个数
     * @param seed   异或种子
     * @return 解码结果
     */
    public static String decodeShorts(short[] sArr, int offset, int count, int seed) {
        return ObfCodec.decodeShorts(sArr, offset, count, seed);
    }

    /**
     * 原 {@code e(Object)}：{@code obj.hashCode()}。
     *
     * @param obj 任意对象
     * @return 哈希值
     */
    public static int hashCodeOf(Object obj) {
        return ObfCodec.hashCodeOf(obj);
    }
}
