package com.example.tvbox.plain.frame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网盘链接排序器 —— 按配置顺序给网盘链接排序。
 *
 * <p>用途：详情页有多个网盘链接时，把「用户偏好的网盘」排前面。
 *
 * <h2>本项目范围</h2>
 * <p>上游排 9 家（百度→夸克→UC→迅雷→光鸭→天翼→123→阿里→移动）。
 * 本工程<b>只支持夸克与百度</b>，故识别表收窄为两项，
 * <b>其余 7 家的域名一律归为 {@code other} 并沉到末尾</b>
 * —— 它们不会再被识别成"某家网盘"，见
 * {@code maintain/evidence/SCOPE_QUARK_BAIDU.md}。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  识别正则（顺序即默认优先级）:
 *    0 pan.baidu.com            百度
 *    1 pan.quark.cn             夸克
 *
 *  配置来源: /config.json 的 "panOrder" 字段（逗号分隔），缺失用默认顺序
 *  未知网盘: 排在最后（order = Integer.MAX_VALUE）
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/PanOrder.java}（96 行）
 
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>未知网盘</b>：{@code getPanType} 认不出域名时 {@code weightOf} 返回
 *       {@code Integer.MAX_VALUE} → 一律沉到最后，<b>新网盘站点没有任何提示</b>。</li>
 *   <li><b>自定义顺序的非法输入</b>：{@code sort(list, order)} 只按给定数组建表；
 *       重复项/空项不校验（后者会成为"比未知还靠前"的槽位）。</li>
 *   <li><b>稳定性</b>：{@code compare} 只比权重，同权重的顺序依赖
 *       {@code Collections.sort} 的稳定性（TimSort 稳定，但不该被当契约）。</li>
 *   <li><b>未还原</b>：保留的 2 个网盘域名匹配规则按上游原文固化；
 *       真实链接形态（短链/中转页）未穷举。</li>
 * </ol>
 *
 * <h2>记录的上游缺陷 / 硬编码异味</h2>
 * <ol>
 *   <li><b>排序表硬编码在客户端</b>：新增网盘要发版，且老版本会把新网盘排到最后
 *       （用户感知为"顺序乱了"而不是"不认识这个网盘"）。</li>
 *   <li><b>{@code compare(Object,Object)} 泛型丢失</b>：上游比较器接受任意对象并按
 *       {@code toString()} 归类，传入非链接对象不会报错、只会默默沉底。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/PanOrder.java}（96 行；
 * {@code DEFAULT_ORDER} 顺序、{@code getPanType} 的域名匹配、{@code compare} 的权重比较）。
*/
public class PanOrderPlain implements Comparator<Object> {

    /** 默认网盘顺序（上游 9 家里只剩保留的这两项，相对次序不变） */
    public static final String[] DEFAULT_ORDER = { "百度", "夸克" };

    /**
     * 识别正则 —— 数组下标即网盘标识索引。
     * <p>上游有 9 条；本项目只留 {@code baidu} / {@code quark} 两条（正则原文未改）。
     */
    private static final Pattern[] PATTERNS = {
            Pattern.compile("pan\\.baidu\\.com"),
            Pattern.compile("pan\\.quark\\.cn"),
    };

    /** 索引 → 网盘名（下标与 PATTERNS 严格对应） */
    private static final String[] KIND_BY_INDEX = { "百度", "夸克" };

    /** 网盘名 → 排序权重 */
    private final Map<String, Integer> orderMap = new HashMap<>();

    /** 用默认顺序构造 */
    public PanOrderPlain() {
        this(DEFAULT_ORDER);
    }

    /**
     * 用自定义顺序构造。
     *
     * @param order 网盘名数组，下标即权重（越小越靠前）
     */
    public PanOrderPlain(String[] order) {
        if (order != null) {
            for (int i = 0; i < order.length; i++) {
                orderMap.put(order[i], i);
            }
        }
    }

    /** 识别 URL 属于哪个网盘；未知返回 "other" */
    public static String getPanType(String url) {
        if (url == null) return "other";
        for (int i = 0; i < PATTERNS.length; i++) {
            Matcher m = PATTERNS[i].matcher(url);
            if (m.find()) {
                return KIND_BY_INDEX[i];
            }
        }
        return "other";
    }

    /** 取权重；未知网盘返回 Integer.MAX_VALUE（排最后） */
    public int weightOf(String url) {
        Integer w = orderMap.get(getPanType(url));
        return w == null ? Integer.MAX_VALUE : w;
    }

    /**
     * 比较两个对象（URL 或其 toString）—— 还原上游 {@code compare}。
     * <p>null 视为空串，归为 other。
     */
    @Override
    public int compare(Object a, Object b) {
        String sa = a == null ? "" : a.toString();
        String sb = b == null ? "" : b.toString();
        int wa = weightOf(sa);
        int wb = weightOf(sb);
        return Integer.compare(wa, wb);
    }

    /**
     * 原地排序 —— 还原上游 {@code sort(List)}。
     *
     * @param list 待排序列表（元素可为 String 或任意对象）
     */
    public static void sort(List<?> list) {
        if (list == null) return;
        List<Object> tmp = new ArrayList<>(list);
        Collections.sort(tmp, new PanOrderPlain());
        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) list;
        for (int i = 0; i < tmp.size(); i++) raw.set(i, tmp.get(i));
    }

    /** 用指定顺序排序 */
    public static void sort(List<?> list, String[] order) {
        if (list == null) return;
        List<Object> tmp = new ArrayList<>(list);
        Collections.sort(tmp, new PanOrderPlain(order));
        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) list;
        for (int i = 0; i < tmp.size(); i++) raw.set(i, tmp.get(i));
    }
}
