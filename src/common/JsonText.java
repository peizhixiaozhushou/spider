package com.example.tvbox.common;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 按**调用方声明的顺序**输出 JSON 文本，转义仍用宿主的 {@code org.json}。
 *
 * <h2>为什么不能直接 {@code JSONObject.toString()}</h2>
 *
 * <p>{@code JSONObject} 的键序**不是契约，而是库的实现细节**，而且两个世代不一样：
 *
 * <table border="1">
 *   <caption>实测（{@code o.put("b"),put("a"),put("c"),put("hot"),put("global")}）</caption>
 *   <tr><th>实现</th><th>{@code names()}</th><th>内部结构</th></tr>
 *   <tr><td>现代 json-java（我们早期的桩）</td><td>{@code ["b","a","c","hot","global"]}</td><td>{@code LinkedHashMap} → <b>插入序</b></td></tr>
 *   <tr><td>旧版 org.json（fmplayer.app 的 gateway.jar 自带）</td><td>{@code ["a","b","c","global","hot"]}</td><td>{@code HashMap} → <b>散列序</b></td></tr>
 * </table>
 *
 * <p>所以 {@code toString()} 的输出**随宿主版本而变**。对"宿主按键名解析"的输出（如
 * {@code playerContent}）无所谓；但对**带签名的请求体**是致命的：
 * 服务端若按字面串验签，键序一变签名就永远对不上，而本地（新桩）自测永远是对的。
 *
 * <h2>本类怎么解决</h2>
 *
 * <p>键序由调用方显式给出；**转义仍交给宿主的 {@code org.json}**
 * （{@link JSONObject#quote(String)} 两代都有，所以转义结果与上游逐字节一致 ——
 * 包括旧版会把 {@code /} 转义成 {@code \/} 这个细节）。
 * 这样"顺序确定"与"转义与上游一致"同时成立，且不依赖任何库的迭代顺序。
 */
public final class JsonText {

    private JsonText() {}

    /**
     * 按 {@code order} 给定的键序序列化 {@code src} 里的对应字段。
     *
     * <p>{@code order} 里出现但 {@code src} 中没有的键会被**跳过**；
     * {@code src} 里有但 {@code order} 没列的键**不会**被输出
     * （这是有意的：宁可少输出，也不要让"多出来的键"悄悄改变签名串）。
     *
     * @param src   取值来源，可为 null（此时输出 {@code {}}）
     * @param order 键序；null 或空时输出 {@code {}}
     * @return JSON 文本，永不返回 null
     */
    public static String object(JSONObject src, List<String> order) {
        StringBuilder sb = new StringBuilder("{");
        if (src != null && order != null) {
            boolean first = true;
            for (String k : order) {
                if (k == null || !src.has(k)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append(JSONObject.quote(k)).append(':').append(value(src.opt(k)));
            }
        }
        return sb.append('}').toString();
    }

    /**
     * 按 {@code order} 输出，但**保留** {@code src} 中的其余键（追加在末尾，按其 {@code names()} 序）。
     *
     * <p>用于"已知关键字段必须在前面、但不想丢掉任何字段"的场景。
     *
     * @param src   取值来源，可为 null
     * @param order 优先输出的键序
     * @return JSON 文本，永不返回 null
     */
    public static String objectKeepRest(JSONObject src, List<String> order) {
        if (src == null) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        java.util.List<String> done = new java.util.ArrayList<String>();
        if (order != null) {
            for (String k : order) {
                if (k == null || !src.has(k) || done.contains(k)) continue;
                if (!first) sb.append(',');
                first = false;
                done.add(k);
                sb.append(JSONObject.quote(k)).append(':').append(value(src.opt(k)));
            }
        }
        for (String k : JsonKeys.of(src)) {
            if (done.contains(k)) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append(JSONObject.quote(k)).append(':').append(value(src.opt(k)));
        }
        return sb.append('}').toString();
    }

    /**
     * 序列化单个值。
     *
     * <h3>★ 为什么这里要**自己**递归处理 Map/Collection，而不是交给宿主</h3>
     *
     * <p>直觉上应当让宿主的 {@code JSONObject.wrap()} 去归一化，但**实测真宿主不归一化**：
     * <pre>
     *   JSONObject o = new JSONObject();
     *   Map m = new LinkedHashMap(); m.put("a", 1);
     *   o.put("nested", m);
     *   o.opt("nested")          → java.util.LinkedHashMap   （不是 JSONObject）
     *   o.optJSONObject("nested")→ null
     *   o.toString()             → {"nested":"{a=1}"}         ← 变成一个字符串！发出去就是垃圾
     * </pre>
     * 我们早期的桩（现代 json-java）会归一化，所以这条路在本地"看起来对"。
     *
     * <p>因此本方法**自己**把 Map/Collection/数组 转成 JSON 文本，
     * 递归下去、不依赖任何库的归一化行为。键序取 Map 的迭代顺序
     * （调用方用 {@code LinkedHashMap} 就能得到确定顺序）。
     *
     * @param v 任意值，可为 null
     * @return 该值的 JSON 文本
     */
    public static String value(Object v) {
        if (v == null || v == JSONObject.NULL) return "null";
        if (v instanceof String) return JSONObject.quote((String) v);
        if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
        if (v instanceof JSONObject || v instanceof JSONArray) return v.toString();
        if (v instanceof java.util.Map) return mapToJson((java.util.Map<?, ?>) v);
        if (v instanceof java.util.Collection) return collectionToJson((java.util.Collection<?>) v);
        if (v.getClass().isArray()) return arrayToJson(v);
        // 兜底：转成字符串并按 JSON 字符串规则转义。
        // **不**调用宿主的 wrap() —— 见上方实测，它在这里不可靠。
        return JSONObject.quote(String.valueOf(v));
    }

    /**
     * Map → JSON 对象文本。键序 = Map 自身迭代序（用 {@code LinkedHashMap} 即确定）。
     *
     * @param m 源 Map
     * @return JSON 对象文本
     */
    public static String mapToJson(java.util.Map<?, ?> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(JSONObject.quote(String.valueOf(e.getKey())))
              .append(':').append(value(e.getValue()));
        }
        return sb.append('}').toString();
    }

    /**
     * Collection → JSON 数组文本（保持迭代顺序）。
     *
     * @param c 源集合
     * @return JSON 数组文本
     */
    public static String collectionToJson(java.util.Collection<?> c) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Object o : c) {
            if (!first) sb.append(',');
            first = false;
            sb.append(value(o));
        }
        return sb.append(']').toString();
    }

    /**
     * 原生数组（含基本类型数组）→ JSON 数组文本。
     *
     * @param arr 数组对象
     * @return JSON 数组文本
     */
    public static String arrayToJson(Object arr) {
        int n = java.lang.reflect.Array.getLength(arr);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(value(java.lang.reflect.Array.get(arr, i)));
        }
        return sb.append(']').toString();
    }

    /**
     * 把任意对象塞进一个临时 {@code JSONObject} 再取回，借宿主的 {@code wrap()} 归一化。
     *
     * <p>不直接调 {@code JSONObject.wrap} 是为了兼容"某些宿主把它设成包私有"的情况 ——
     * 用 {@code put}/{@code opt} 这一对两代都有的公开 API 达到同样效果。
     *
     * @param v 任意值
     * @return 归一化后的值；无法归一化时返回 null
     */
    private static Object wrapCompat(Object v) {
        try {
            JSONObject tmp = new JSONObject();
            tmp.put("v", v);
            Object got = tmp.opt("v");
            return got == null ? null : got;
        } catch (Throwable e) {
            return null;
        }
    }
}
