package com.example.tvbox.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 DOM —— jsoup 的最小子集，供站点插件用 CSS 选择器语法。
 *
 * <h2>为什么需要它</h2>
 * 上游 6 个类用 jsoup（{@code merge.I.c} 及其 {@code n0/a/c} 方法）。
 * 工程没有 jsoup 依赖，但 CSS 选择器是最自然的表达方式。
 * 本类实现常用选择器的正则等价，API 形状刻意贴近 jsoup。
 *
 * <h2>支持的 CSS 选择器</h2>
 * <table>
 *   <tr><th>语法</th><th>含义</th><th>示例</th></tr>
 *   <tr><td>{@code tag}</td><td>标签名</td><td>{@code li}</td></tr>
 *   <tr><td>{@code .cls}</td><td>class 含该词</td><td>{@code .resize_list}</td></tr>
 *   <tr><td>{@code #id}</td><td>id 等于</td><td>{@code #topnav}</td></tr>
 *   <tr><td>{@code A B}</td><td>后代</td><td>{@code #nav li}</td></tr>
 *   <tr><td>{@code A > B}</td><td>直接子级</td><td>{@code ul > li}</td></tr>
 *   <tr><td>{@code tag[n]}</td><td>第 n 个同类</td><td>{@code ul:nth-child(1)}</td></tr>
 *   <tr><td>{@code tag.cls}</td><td>组合</td><td>{@code ul.resize_list}</td></tr>
 * </table>
 *
 * <h2>与 jsoup 的差异（重要）</h2>
 * <ul>
 *   <li>不做完整 HTML 规范化：畸形标签可能解析不出预期结构</li>
 *   <li>{@code :nth-child(n)} 只支持同类计数，不支持 {@code even/odd/an+b}</li>
 *   <li>不自动补全缺失的结束标签</li>
 *   <li>性能：大文档（&gt;1MB）建议先用 {@link #children} 缩范围</li>
 * </ul>
 */
public final class Dom {

    private Dom() {}

    /** 元素节点 */
    public static class El {
        final String tag;
        final Map<String, String> attrs;
        final String inner;          // 内部 HTML
        final String outer;          // 完整 HTML（含开闭标签）
        final int indexInParent;     // 同类序号（从 1 开始）

        El(String tag, Map<String, String> attrs, String inner, String outer, int idx) {
            this.tag = tag;
            this.attrs = attrs;
            this.inner = inner;
            this.outer = outer;
            this.indexInParent = idx;
        }

        /** 标签名（小写） */
        public String tagName() { return tag; }

        /** 取属性，不存在返回空串 */
        public String attr(String name) {
            if (name == null) return "";
            String v = attrs.get(name.toLowerCase());
            return v == null ? "" : v;
        }

        /** 是否含该属性 */
        public boolean hasAttr(String name) {
            return name != null && attrs.containsKey(name.toLowerCase());
        }

        /** 全部属性 */
        public Map<String, String> attributes() { return attrs; }

        /** 内部 HTML */
        public String html() { return inner; }

        /** 完整 HTML */
        public String outerHtml() { return outer; }

        /** 纯文本（去标签 + 解实体 + 压缩空白） */
        public String text() {
            return Html.decode(Html.text(inner)).trim();
        }

        /** 该元素是否为自闭合（img/br/input...） */
        public boolean isVoid() {
            return Html.isVoidTag(tag);
        }

        /**
         * 在<b>本元素内部</b>按选择器查找。
         *
         * @param selector CSS 选择器（支持子集见类注释）
         * @return 匹配元素列表，按文档顺序
         */
        public List<El> select(String selector) {
            return Dom.select(inner, selector);
        }

        /** 取首个匹配元素；无匹配返回一个空元素（不返回 null，避免调用方空指针） */
        public El selectFirst(String selector) {
            List<El> l = select(selector);
            return l.isEmpty() ? emptyEl() : l.get(0);
        }

        /** 便捷：本元素内首个匹配的 attr */
        public String selectAttr(String selector, String attr, String def) {
            El e = selectFirst(selector);
            String v = e.attr(attr);
            return v.isEmpty() ? (def == null ? "" : def) : v;
        }

        /** 便捷：本元素内首个匹配的 text */
        public String selectText(String selector, String def) {
            El e = selectFirst(selector);
            String v = e.text();
            return v.isEmpty() ? (def == null ? "" : def) : v;
        }

        @Override public String toString() {
            return tag + attrs;
        }
    }

    /** 空元素（用于 selectFirst 无匹配时避免 null） */
    private static El emptyEl() {
        return new El("", new LinkedHashMap<String, String>(), "", "", 0);
    }

    /**
     * 解析 HTML 文档。
     *
     * @param html 原始 HTML
     * @param base 相对 URL 的基准（当前实现保留参数以兼容 jsoup 风格，未做 URL 绝对化）
     * @return 伪根元素，其 {@code select} 作用于全文
     */
    public static El parse(String html, String base) {
        if (html == null) html = "";
        El root = new El("#root", new LinkedHashMap<String, String>(), html, html, 1);
        return root;
    }

    /** 解析（无 base） */
    public static El parse(String html) {
        return parse(html, "");
    }

    // ─────────────────────────────────────────────────────────────
    // 选择器实现
    // ─────────────────────────────────────────────────────────────

    /**
     * 在给定 HTML 中按选择器查找。
     *
     * <p>实现策略：把选择器拆成「后代链」或「子级链」，
     * 逐段用正则定位候选，再逐步缩小范围。
     * 不是完整的 CSS 引擎，但覆盖站点抓取的绝大多数用法。
     */
    public static List<El> select(String html, String selector) {
        List<El> out = new ArrayList<>();
        if (html == null || selector == null || selector.trim().isEmpty()) return out;

        String sel = selector.trim();

        // 处理 child 组合符 ">"：逐段向下，每段只保留「上一段元素的直接子级」
        if (sel.contains(">")) {
            String[] chain = sel.split("\\s*>\\s*");
            // 当前层候选元素
            List<El> current = selectSimple(html, chain[0].trim());
            for (int ci = 1; ci < chain.length; ci++) {
                String part = chain[ci].trim();
                List<El> next = new ArrayList<>();
                for (El parent : current) {
                    // 在父元素内部找匹配，但只保留「直接子级」
                    for (El cand : selectSimple(parent.inner, part)) {
                        if (isDirectChild(parent.inner, cand)) next.add(cand);
                    }
                }
                current = next;
                if (current.isEmpty()) return out;
            }
            return current;
        }

        // 后代组合符：空格分隔
        String[] parts = sel.split("\\s+");
        if (parts.length == 1) {
            return selectSimple(html, sel);
        }

        // 逐段缩小：先找第一段，再在其内部找剩余链
        List<El> first = selectSimple(html, parts[0]);
        StringBuilder rest = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            if (i > 1) rest.append(' ');
            rest.append(parts[i]);
        }
        String restSel = rest.toString();
        for (El e : first) {
            out.addAll(select(restSel.contains(">") ? e.inner : e.inner, restSel));
        }
        return out;
    }

    private static final Pattern TAG_OPEN = Pattern.compile(
            "<([a-zA-Z][a-zA-Z0-9]*)((?:\\s+[a-zA-Z_:][a-zA-Z0-9_.:-]*\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+))*)\\s*/?>");
    private static final Pattern ATTR = Pattern.compile(
            "([a-zA-Z_:][a-zA-Z0-9_.:-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    /**
     * 单段选择器（无组合符）：
     * {@code tag} / {@code .cls} / {@code #id} / {@code tag.cls} /
     * {@code .cls1.cls2} / {@code tag.cls1.cls2} / {@code tag#id.cls} /
     * {@code tag:nth-child(n)}
     */
    private static List<El> selectSimple(String html, String simple) {
        List<El> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;

        // 拆出 nth-child
        int nth = -1;
        String s = simple;
        Matcher nm = Pattern.compile(":nth-child\\(\\s*(\\d+)\\s*\\)").matcher(s);
        if (nm.find()) {
            nth = Integer.parseInt(nm.group(1));
            s = s.replace(nm.group(0), "");
        }

        // 支持组合：#id、.cls1.cls2、tag.cls1.cls2、tag#id.cls
        String wantTag = null;
        List<String> wantClasses = new ArrayList<>();
        String wantId = null;

        // 先摘出 #id（#id 之后的部分按类名继续解析）
        int hash = s.indexOf('#');
        if (hash >= 0) {
            int after = hash + 1;
            int nextDot = s.indexOf('.', after);
            wantId = nextDot < 0 ? s.substring(after) : s.substring(after, nextDot);
            s = nextDot < 0 ? "" : s.substring(nextDot);
        }
        // 再摘出 tag 前缀（只有当第一个字符不是 '.' 时才是 tag）
        if (!s.isEmpty() && !s.startsWith(".")) {
            int dot = s.indexOf('.');
            if (dot > 0) {
                wantTag = s.substring(0, dot);
                s = s.substring(dot);
            } else {
                wantTag = s;
                s = "";
            }
        }
        // 剩下全部是 .cls 段
        if (!s.isEmpty()) {
            for (String part : s.split("\\.")) {
                if (!part.isEmpty()) {
                    wantClasses.add(part);
                }
            }
        }
        // 退化选择器：既无 tag 也无 #id 也无 .cls（例如单独一个 "."）——
        // 若不加这道判断，三个条件全为 null 就等于"匹配任意元素"，
        // 调用方本意是"没有有效选择器"，应该拿到空结果。
        boolean anyElement = "*".equals(simple.trim());
        if (!anyElement && wantTag == null && wantId == null && wantClasses.isEmpty()) {
            return out;
        }

        // 逐标签扫描
        Map<String, Integer> tagCounter = new LinkedHashMap<>();
        Matcher m = TAG_OPEN.matcher(html);
        while (m.find()) {
            String tag = m.group(1);
            String tagLower = tag.toLowerCase();
            if (wantTag != null && !wantTag.equalsIgnoreCase(tag)) continue;

            Map<String, String> attrs = parseAttrs(m.group(2));
            if (wantId != null && !wantId.equals(attrs.get("id"))) continue;
            if (!wantClasses.isEmpty()) {
                String cls = attrs.get("class");
                boolean all = true;
                for (String w : wantClasses) {
                    if (!hasClassWord(cls, w)) {
                        all = false;
                        break;
                    }
                }
                if (!all) continue;
            }

            // 同类计数（用于 nth-child）
            Integer prev = tagCounter.get(tagLower);
            int idx = (prev == null ? 0 : prev) + 1;
            tagCounter.put(tagLower, idx);
            if (nth > 0 && idx != nth) continue;

            // 取内部 HTML
            int innerStart = m.end();
            int innerEnd = Html.isVoidTag(tagLower) ? innerStart : findClose(html, tagLower, innerStart);
            if (innerEnd < innerStart) innerEnd = innerStart;
            String inner = html.substring(innerStart, innerEnd);
            int outerEnd = Html.isVoidTag(tagLower) ? innerStart : Math.min(html.length(), innerEnd + closeTagLen(tag));
            String outer = html.substring(m.start(), Math.max(outerEnd, innerStart));

            out.add(new El(tagLower, attrs, inner, outer, idx));
        }
        return out;
    }

    /** 解析属性串 */
    static Map<String, String> parseAttrs(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) return m;
        Matcher a = ATTR.matcher(raw);
        while (a.find()) {
            String name = a.group(1).toLowerCase();
            String val = a.group(2) != null ? a.group(2)
                       : a.group(3) != null ? a.group(3)
                       : a.group(4) != null ? a.group(4) : "";
            m.put(name, Html.decode(val));
        }
        return m;
    }

    /** class 属性中是否含某个完整词 */
    static boolean hasClassWord(String classAttr, String word) {
        if (classAttr == null || word == null) return false;
        for (String part : classAttr.trim().split("\\s+")) {
            if (part.equals(word)) return true;
        }
        return false;
    }

    /** 从 from 起找匹配的结束标签位置（考虑嵌套） */
    static int findClose(String html, String tagLower, int from) {
        Pattern open = Pattern.compile("<" + Pattern.quote(tagLower) + "(?=[\\s/>])", Pattern.CASE_INSENSITIVE);
        Pattern close = Pattern.compile("</" + Pattern.quote(tagLower) + "\\s*>", Pattern.CASE_INSENSITIVE);
        Matcher om = open.matcher(html);
        Matcher cm = close.matcher(html);
        om.region(from, html.length());
        cm.region(from, html.length());
        int depth = 1;
        int pos = from;
        while (pos < html.length()) {
            boolean hasO = om.find(pos);
            boolean hasC = cm.find(pos);
            if (!hasC) return html.length();
            if (hasO && om.start() < cm.start()) {
                depth++;
                pos = om.end();
            } else {
                depth--;
                if (depth == 0) return cm.start();
                pos = cm.end();
            }
        }
        return html.length();
    }

    /** 判断 cand 是否为 parentInner 的直接子级（通过前置未闭合深度判定） */
    static boolean isDirectChild(String parentInner, El cand) {
        if (parentInner == null || cand == null || cand.outer.isEmpty()) return false;
        int pos = parentInner.indexOf(cand.outer);
        if (pos < 0) return false;
        return depthAt(parentInner.substring(0, pos)) == 0;
    }

    /** 计算 HTML 片段末尾的未闭合标签深度 */
    static int depthAt(String html) {
        Pattern open = Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)(?=[\\s/>])");
        Pattern close = Pattern.compile("</([a-zA-Z][a-zA-Z0-9]*)\\s*>");
        int depth = 0, i = 0;
        while (i < html.length()) {
            Matcher om = open.matcher(html); om.region(i, html.length());
            Matcher cm = close.matcher(html); cm.region(i, html.length());
            boolean hasO = om.find(), hasC = cm.find();
            if (!hasO && !hasC) break;
            if (hasO && (!hasC || om.start() < cm.start())) {
                String tag = om.group(1).toLowerCase();
                if (!Html.isVoidTag(tag)) depth++;
                i = om.end();
            } else {
                depth = Math.max(0, depth - 1);
                i = cm.end();
            }
        }
        return depth;
    }

    private static int closeTagLen(String tag) {
        return tag.length() + 3;   // "</" + tag + ">"
    }
}
