package com.example.tvbox.plain.film;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 极简 HTML 解析 + CSS 选择器引擎 —— 供影视站群（film 包）共用。
 *
 * <h2>为什么自己写</h2>
 * 上游插件全部依赖混淆后的 jsoup 分支（{@code FM.K.m} / {@code A0.ed} / {@code G.i} 三套不同版本），
 * 本仓库没有 jsoup 依赖（编译期只有 JDK + 自维护 common + stub），因此这里实现
 * <b>与原插件实际用到的那一小撮 CSS 语法等价</b> 的引擎，而不是引入新依赖。
 *
 * <h2>已还原的语义对照（来自反编译核对）</h2>
 * <table>
 *   <tr><th>上游调用</th><th>jsoup 语义</th><th>本类等价方法</th></tr>
 *   <tr><td>{@code doc.o0(css)} / {@code ed.ah(css)}</td><td>{@code select(css)}</td><td>{@link #select(String)}</td></tr>
 *   <tr><td>{@code els.i()} / {@code els.f()}</td><td>{@code Elements.text()}</td><td>{@link #text()}</td></tr>
 *   <tr><td>{@code el.v0()}</td><td>{@code Element.text()}</td><td>{@link #text()}</td></tr>
 *   <tr><td>{@code els.a(attr)}</td><td>{@code Elements.attr()}</td><td>{@link #attr(String)}</td></tr>
 *   <tr><td>{@code el.a(attr)}（s.a）</td><td>{@code absUrl()}</td><td>{@link #absUrl(String)}</td></tr>
 *   <tr><td>{@code el.c(attr)}（s.c）</td><td>{@code attr()}</td><td>{@link #attr(String)}</td></tr>
 *   <tr><td>{@code doc.c0()}</td><td>{@code html()}</td><td>{@link #html()}</td></tr>
 * </table>
 *
 * <h2>支持的 CSS 子集</h2>
 * <pre>
 *   tag / * / .cls / #id / [attr] / [attr=value]
 *   复合：div.reslut-list.mi_ne_kd#x
 *   组合子：后代(空格)、子代(&gt;)
 *   伪类：:nth-child(n)、:first-child、:last-child
 *   分组：逗号（".ft2, .public-list-prt"）
 * </pre>
 *
 * <h2>容错</h2>
 * <ul>
 *   <li>未闭合的 {@code <li>} / {@code <p>} / {@code <tr>} 等按 HTML 语义自动闭合</li>
 *   <li>属性区内出现游离 {@code <}（真实页面里 {@code <img data-src="x"<img src="x">}）时吞掉继续解析</li>
 *   <li>{@code <script>} / {@code <style>} 内容作为原始文本节点保留，{@link #html()} 可原样再现</li>
 * </ul>
 *
 * <p>本类不做网络请求、不抛异常：解析失败退化为空文档。
 */
public final class Html {

    private Html() {
    }

    /** 视为"块级"的标签：{@code text()} 拼接时前后补空格（对齐 jsoup Element.text()） */
    private static final Set<String> BLOCK = new HashSet<>(Arrays.asList(
            "address", "article", "aside", "blockquote", "br", "dd", "div", "dl", "dt",
            "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4",
            "h5", "h6", "header", "hr", "li", "main", "nav", "ol", "p", "pre", "section",
            "table", "tbody", "td", "tfoot", "th", "thead", "tr", "ul"));

    /** 自闭合（空元素）标签 */
    private static final Set<String> VOID = new HashSet<>(Arrays.asList(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
            "param", "source", "track", "wbr"));

    /** 隐式闭合规则：新开 left 时，若栈顶是 right 则先关闭（HTML 解析器行为） */
    private static final Map<String, Set<String>> IMPLIED_END = new LinkedHashMap<>();

    static {
        Set<String> li = new HashSet<>(Arrays.asList("li"));
        IMPLIED_END.put("li", li);
        Set<String> p = new HashSet<>(Arrays.asList("p"));
        IMPLIED_END.put("p", p);
        Set<String> tr = new HashSet<>(Arrays.asList("tr"));
        IMPLIED_END.put("tr", tr);
        Set<String> td = new HashSet<>(Arrays.asList("td", "th"));
        IMPLIED_END.put("td", td);
        Set<String> th = new HashSet<>(Arrays.asList("td", "th"));
        IMPLIED_END.put("th", th);
        Set<String> option = new HashSet<>(Arrays.asList("option"));
        IMPLIED_END.put("option", option);
    }

    /** 原始文本标签：内容不做标签解析 */
    private static final Set<String> RAW_TEXT = new HashSet<>(Arrays.asList("script", "style"));

    // ─────────────────────────────────────────────────────────────
    // 解析入口
    // ─────────────────────────────────────────────────────────────

    /**
     * 解析 HTML 文本为文档节点。
     *
     * @param source HTML 源码，可为 null
     * @return 文档根（tag = "#document"），永不为 null
     */
    public static El parse(String source) {
        return parse(source, "");
    }

    /**
     * 解析 HTML 文本为文档节点，并绑定基址用于 {@link El#absUrl(String)}。
     *
     * @param source HTML 源码，可为 null
     * @param baseUrl 文档原始 URL（用于相对路径解析），可为 null
     * @return 文档根
     */
    public static El parse(String source, String baseUrl) {
        El doc = new El("#document", null, baseUrl == null ? "" : baseUrl);
        if (source == null || source.isEmpty()) {
            return doc;
        }
        El cur = doc;
        int n = source.length();
        int i = 0;
        StringBuilder text = new StringBuilder();
        while (i < n) {
            char c = source.charAt(i);
            if (c != '<') {
                text.append(c);
                i++;
                continue;
            }
            // ── 注释 / DOCTYPE / CDATA 直接跳过 ──
            if (source.startsWith("<!--", i)) {
                flushText(cur, text);
                int end = source.indexOf("-->", i + 4);
                i = end < 0 ? n : end + 3;
                continue;
            }
            if (source.startsWith("<![", i)) {
                flushText(cur, text);
                int end = source.indexOf('>', i);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (i + 1 < n && (source.charAt(i + 1) == '!' || source.charAt(i + 1) == '?')) {
                flushText(cur, text);
                int end = source.indexOf('>', i);
                i = end < 0 ? n : end + 1;
                continue;
            }
            boolean closing = i + 1 < n && source.charAt(i + 1) == '/';
            int nameStart = i + (closing ? 2 : 1);
            if (nameStart >= n || !isNameStart(source.charAt(nameStart))) {
                // 游离 '<'（文本里的比较符），当普通字符
                text.append(c);
                i++;
                continue;
            }
            // ── 读标签名 ──
            int p = nameStart;
            while (p < n && isNameChar(source.charAt(p))) {
                p++;
            }
            String tag = source.substring(nameStart, p).toLowerCase(Locale.ROOT);

            if (closing) {
                flushText(cur, text);
                int end = source.indexOf('>', p);
                i = end < 0 ? n : end + 1;
                cur = closeTag(cur, tag);
                continue;
            }

            // ── 读属性（对游离 '<' 容错：继续扫描到本标签真正的 '>'）──
            Map<String, String> attrs = new LinkedHashMap<>();
            boolean selfClose = false;
            int q = p;
            while (q < n) {
                char sc = source.charAt(q);
                if (sc == '>') {
                    q++;
                    break;
                }
                if (sc == '/') {
                    // 可能是自闭合 "/>"，也可能是属性值里的斜杠 —— 只在后面紧跟 '>' 时判定
                    if (q + 1 < n && source.charAt(q + 1) == '>') {
                        selfClose = true;
                        q += 2;
                        break;
                    }
                    q++;
                    continue;
                }
                if (isSpace(sc) || sc == '<') {
                    q++;
                    continue;
                }
                // 属性名
                int an = q;
                while (q < n) {
                    char ac = source.charAt(q);
                    if (isSpace(ac) || ac == '=' || ac == '>' || ac == '/' || ac == '<') {
                        break;
                    }
                    q++;
                }
                String aname = source.substring(an, q).toLowerCase(Locale.ROOT);
                if (aname.isEmpty()) {
                    q++;
                    continue;
                }
                // 跳过 '=' 前后空白
                int save = q;
                while (q < n && isSpace(source.charAt(q))) {
                    q++;
                }
                if (q < n && source.charAt(q) == '=') {
                    q++;
                    while (q < n && isSpace(source.charAt(q))) {
                        q++;
                    }
                    String aval;
                    if (q < n && (source.charAt(q) == '"' || source.charAt(q) == '\'')) {
                        char quote = source.charAt(q);
                        int vs = q + 1;
                        int ve = source.indexOf(quote, vs);
                        if (ve < 0) {
                            ve = n;
                        }
                        aval = source.substring(vs, Math.min(ve, n));
                        q = Math.min(ve + 1, n);
                    } else {
                        int vs = q;
                        while (q < n) {
                            char vc = source.charAt(q);
                            if (isSpace(vc) || vc == '>') {
                                break;
                            }
                            q++;
                        }
                        aval = source.substring(vs, q);
                    }
                    if (!attrs.containsKey(aname)) {
                        attrs.put(aname, decodeEntities(aval));
                    }
                } else {
                    q = save;
                    if (!attrs.containsKey(aname)) {
                        attrs.put(aname, "");
                    }
                }
            }
            i = q;
            flushText(cur, text);

            // ── 隐式闭合 ──
            cur = applyImpliedEnd(cur, tag);

            El el = new El(tag, attrs, cur.base);
            el.parent = cur;
            cur.children.add(el);

            if (VOID.contains(tag) || selfClose) {
                continue;
            }
            if (RAW_TEXT.contains(tag)) {
                // 原始文本：一直读到对应结束标签
                String close = "</" + tag;
                int end = indexOfIgnoreCase(source, close, i);
                int contentEnd = end < 0 ? n : end;
                String raw = source.substring(i, contentEnd);
                if (!raw.isEmpty()) {
                    El t = new El(null, null, cur.base);
                    t.raw = raw;
                    t.parent = el;
                    el.children.add(t);
                }
                if (end < 0) {
                    i = n;
                } else {
                    int gt = source.indexOf('>', end);
                    i = gt < 0 ? n : gt + 1;
                }
                continue;
            }
            cur = el;
        }
        flushText(cur, text);
        return doc;
    }

    // ─────────────────────────────────────────────────────────────
    // 节点
    // ─────────────────────────────────────────────────────────────

    /**
     * 一个元素节点（tag 为 null 时代表文本节点）。
     */
    public static final class El {

        /** 小写标签名；文本节点为 null */
        public final String tag;
        /** 属性表（键已小写、值已解实体）；文本节点为 null */
        public final Map<String, String> attrs;
        /** 子节点（元素 + 文本混合，保持文档顺序） */
        public final List<El> children = new ArrayList<>();
        /** 文档基址，用于 {@link #absUrl(String)} */
        final String base;
        /** 父节点 */
        El parent;
        /** 文本节点的原始内容（script/style 原样保留；普通文本为解实体后文本） */
        String raw;

        El(String tag, Map<String, String> attrs, String base) {
            this.tag = tag;
            this.attrs = attrs == null ? new LinkedHashMap<String, String>() : attrs;
            this.base = base == null ? "" : base;
        }

        /** @return 是否文本节点 */
        public boolean isText() {
            return tag == null;
        }

        /**
         * 文档顺序下的全部后代元素（含自身）。
         *
         * @return 非 null 列表
         */
        public List<El> all() {
            List<El> out = new ArrayList<>();
            collect(this, out);
            return out;
        }

        private static void collect(El e, List<El> out) {
            out.add(e);
            for (El c : e.children) {
                if (!c.isText()) {
                    collect(c, out);
                }
            }
        }

        /**
         * CSS 选择。
         *
         * @param css 选择器，支持逗号分组
         * @return 命中的元素（文档顺序、去重）
         */
        public Els select(String css) {
            Els out = new Els();
            if (css == null || css.trim().isEmpty()) {
                return out;
            }
            List<List<Step>> groups = new ArrayList<>();
            for (String part : splitTopLevel(css, ',')) {
                List<Step> st = parseSelector(part);
                if (!st.isEmpty()) {
                    groups.add(st);
                }
            }
            if (groups.isEmpty()) {
                return out;
            }
            Set<El> seen = new HashSet<>();
            for (El e : all()) {
                if (e == this) {
                    continue;
                }
                for (List<Step> g : groups) {
                    if (matches(e, g, g.size() - 1) && seen.add(e)) {
                        out.list.add(e);
                        break;
                    }
                }
            }
            return out;
        }

        /**
         * 取第一个命中元素。
         *
         * @param css 选择器
         * @return 元素；无命中返回 null
         */
        public El selectFirst(String css) {
            Els es = select(css);
            return es.isEmpty() ? null : es.get(0);
        }

        /**
         * 取元素文本（对齐 jsoup {@code Element.text()}：块级元素间补空格，压缩空白，trim）。
         *
         * @return 文本，永不为 null
         */
        public String text() {
            StringBuilder sb = new StringBuilder();
            appendText(this, sb);
            return collapse(sb.toString());
        }

        /** 只取直接文本子节点拼接（对齐 jsoup {@code ownText()}） */
        public String ownText() {
            StringBuilder sb = new StringBuilder();
            for (El c : children) {
                if (c.isText()) {
                    appendNormalised(sb, c.raw == null ? "" : c.raw);
                }
            }
            return collapse(sb.toString());
        }

        /**
         * 取属性原始值（对齐 jsoup {@code attr(name)}）。
         *
         * @param name 属性名（大小写不敏感）
         * @return 值；缺失返回空串
         */
        public String attr(String name) {
            if (name == null || attrs == null) {
                return "";
            }
            String v = attrs.get(name.toLowerCase(Locale.ROOT));
            return v == null ? "" : v;
        }

        /**
         * 取属性的绝对 URL（对齐 jsoup {@code absUrl()}）。
         *
         * @param name 属性名
         * @return 绝对 URL；缺失返回空串
         */
        public String absUrl(String name) {
            String v = attr(name);
            if (v.isEmpty()) {
                return "";
            }
            return resolve(base, v);
        }

        /**
         * 是否具有某属性。
         *
         * @param name 属性名
         * @return true 表示存在
         */
        public boolean hasAttr(String name) {
            return name != null && attrs != null && attrs.containsKey(name.toLowerCase(Locale.ROOT));
        }

        /**
         * 重新序列化为 HTML（含 script/style 原始文本）。
         *
         * @return HTML 文本
         */
        public String html() {
            StringBuilder sb = new StringBuilder();
            for (El c : children) {
                write(c, sb);
            }
            return sb.toString();
        }

        /** 序列化整棵子树（含自身标签） */
        private static void write(El e, StringBuilder sb) {
            if (e.isText()) {
                sb.append(e.raw == null ? "" : e.raw);
                return;
            }
            if ("#document".equals(e.tag)) {
                for (El c : e.children) {
                    write(c, sb);
                }
                return;
            }
            sb.append('<').append(e.tag);
            for (Map.Entry<String, String> en : e.attrs.entrySet()) {
                sb.append(' ').append(en.getKey()).append("=\"").append(en.getValue()).append('"');
            }
            if (VOID.contains(e.tag)) {
                sb.append('>');
                return;
            }
            sb.append('>');
            for (El c : e.children) {
                write(c, sb);
            }
            sb.append("</").append(e.tag).append('>');
        }

        private static void appendText(El e, StringBuilder sb) {
            for (El c : e.children) {
                if (c.isText()) {
                    appendNormalised(sb, c.raw == null ? "" : c.raw);
                } else {
                    if (sb.length() > 0 && BLOCK.contains(c.tag) && !endsWithSpace(sb)) {
                        sb.append(' ');
                    }
                    appendText(c, sb);
                }
            }
        }

        private static void appendNormalised(StringBuilder sb, String text) {
            boolean lastWs = sb.length() == 0 || endsWithSpace(sb);
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (isSpace(ch)) {
                    if (!lastWs) {
                        sb.append(' ');
                        lastWs = true;
                    }
                } else {
                    sb.append(ch);
                    lastWs = false;
                }
            }
        }

        private static boolean endsWithSpace(StringBuilder sb) {
            return sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ';
        }

        private static String collapse(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            boolean lastWs = true;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (isSpace(ch)) {
                    if (!lastWs) {
                        sb.append(' ');
                        lastWs = true;
                    }
                } else {
                    sb.append(ch);
                    lastWs = false;
                }
            }
            int end = sb.length();
            while (end > 0 && sb.charAt(end - 1) == ' ') {
                end--;
            }
            return sb.substring(0, end);
        }

        @Override
        public String toString() {
            return isText() ? "#text(" + (raw == null ? "" : raw) + ")" : "<" + tag + ">";
        }
    }

    /**
     * 元素集合（对齐 jsoup {@code Elements} 的常用面）。
     */
    public static final class Els implements Iterable<El> {

        private final List<El> list = new ArrayList<>();

        /**
         * 迭代器（便于 for-each，对齐 jsoup {@code Elements}）。
         *
         * @return 迭代器
         */
        @Override
        public java.util.Iterator<El> iterator() {
            return list.iterator();
        }

        /**
         * 元素数量。
         *
         * @return 个数
         */
        public int size() {
            return list.size();
        }

        /**
         * 是否为空。
         *
         * @return true 表示无元素
         */
        public boolean isEmpty() {
            return list.isEmpty();
        }

        /**
         * 按下标取元素。
         *
         * @param i 下标
         * @return 元素；越界返回 null
         */
        public El get(int i) {
            return i >= 0 && i < list.size() ? list.get(i) : null;
        }

        /**
         * 取第一个元素。
         *
         * @return 元素；空集合返回 null
         */
        public El first() {
            return list.isEmpty() ? null : list.get(0);
        }

        /**
         * 对齐 jsoup {@code Elements.text()}：各元素 text() 用空格连接，再次压缩空白。
         *
         * @return 文本
         */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (El e : list) {
                String t = e.text();
                if (t.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(t);
            }
            return sb.toString();
        }

        /**
         * 每个元素 text() 的列表（上游 {@code eachText()}）。
         *
         * @return 非 null 列表
         */
        public List<String> eachText() {
            List<String> out = new ArrayList<>();
            for (El e : list) {
                out.add(e.text());
            }
            return out;
        }

        /**
         * 对齐 jsoup {@code Elements.attr(name)}：取首个元素的属性。
         *
         * @param name 属性名
         * @return 值；空集合返回空串
         */
        public String attr(String name) {
            El e = first();
            return e == null ? "" : e.attr(name);
        }

        /**
         * 首个元素的绝对 URL。
         *
         * @param name 属性名
         * @return URL；空集合返回空串
         */
        public String absUrl(String name) {
            El e = first();
            return e == null ? "" : e.absUrl(name);
        }

        /**
         * 逐个元素取属性（上游 {@code eachAttr()}）。
         *
         * @param name 属性名
         * @return 非 null 列表
         */
        public List<String> eachAttr(String name) {
            List<String> out = new ArrayList<>();
            for (El e : list) {
                String v = e.attr(name);
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
            return out;
        }

        /**
         * 只保留命中选择器的元素。
         *
         * @param css 选择器
         * @return this
         */
        public Els filter(String css) {
            List<Step> st = parseSelector(css);
            if (st.isEmpty()) {
                return this;
            }
            List<El> keep = new ArrayList<>();
            for (El e : list) {
                if (matches(e, st, st.size() - 1)) {
                    keep.add(e);
                }
            }
            list.clear();
            list.addAll(keep);
            return this;
        }

        @Override
        public String toString() {
            return "Els" + list;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 选择器：解析与匹配
    // ─────────────────────────────────────────────────────────────

    /** 一级组合：step 之间 + 组合子 */
    private static final class Step {
        String combinator = "";   // "" 表示紧邻首个 / 后代；">" 表示子代
        String tag;               // 小写标签；null 表示任意
        final List<String> classes = new ArrayList<>();
        String id;
        final List<String[]> attrs = new ArrayList<>();  // [name, value|null]
        int nthChild = -1;
        boolean firstChild;
        boolean lastChild;
    }

    private static List<Step> parseSelector(String css) {
        List<Step> steps = new ArrayList<>();
        if (css == null) {
            return steps;
        }
        String s = css.trim();
        int i = 0;
        Step cur = new Step();
        boolean pendingChild = false;
        int n = s.length();
        boolean any = false;
        while (i < n) {
            char c = s.charAt(i);
            if (isSpace(c)) {
                i++;
                // 判定组合子
                int j = i;
                while (j < n && isSpace(s.charAt(j))) {
                    j++;
                }
                if (j < n && s.charAt(j) == '>') {
                    i = j;
                    continue;
                }
                if (any) {
                    steps.add(cur);
                    cur = new Step();
                    cur.combinator = " ";
                    any = false;
                }
                continue;
            }
            if (c == '>') {
                if (any) {
                    steps.add(cur);
                    cur = new Step();
                    any = false;
                }
                pendingChild = true;
                i++;
                continue;
            }
            if (pendingChild) {
                cur.combinator = ">";
                pendingChild = false;
            }
            any = true;
            if (c == '*') {
                i++;
                continue;
            }
            if (c == '.') {
                int st = ++i;
                while (i < n && isIdent(s.charAt(i))) {
                    i++;
                }
                cur.classes.add(s.substring(st, i));
                continue;
            }
            if (c == '#') {
                int st = ++i;
                while (i < n && isIdent(s.charAt(i))) {
                    i++;
                }
                cur.id = s.substring(st, i);
                continue;
            }
            if (c == '[') {
                int end = s.indexOf(']', i);
                if (end < 0) {
                    break;
                }
                String body = s.substring(i + 1, end).trim();
                int eq = body.indexOf('=');
                if (eq < 0) {
                    cur.attrs.add(new String[]{body.toLowerCase(Locale.ROOT), null});
                } else {
                    String name = body.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                    String val = body.substring(eq + 1).trim();
                    if (val.length() >= 2
                            && (val.charAt(0) == '"' || val.charAt(0) == '\'')
                            && val.charAt(val.length() - 1) == val.charAt(0)) {
                        val = val.substring(1, val.length() - 1);
                    }
                    cur.attrs.add(new String[]{name, val});
                }
                i = end + 1;
                continue;
            }
            if (c == ':') {
                int st = ++i;
                while (i < n && (isIdent(s.charAt(i)) || s.charAt(i) == '-')) {
                    i++;
                }
                String pseudo = s.substring(st, i).toLowerCase(Locale.ROOT);
                String arg = null;
                if (i < n && s.charAt(i) == '(') {
                    int close = s.indexOf(')', i);
                    if (close < 0) {
                        close = n;
                    }
                    arg = s.substring(i + 1, close).trim();
                    i = Math.min(close + 1, n);
                }
                if ("first-child".equals(pseudo)) {
                    cur.firstChild = true;
                } else if ("last-child".equals(pseudo)) {
                    cur.lastChild = true;
                } else if ("nth-child".equals(pseudo) && arg != null) {
                    try {
                        cur.nthChild = Integer.parseInt(arg);
                    } catch (Exception ignored) {
                        cur.nthChild = -1;
                    }
                }
                continue;
            }
            // 标签名
            int st = i;
            while (i < n && isIdent(s.charAt(i))) {
                i++;
            }
            String tag = s.substring(st, i).toLowerCase(Locale.ROOT);
            if (!tag.isEmpty()) {
                cur.tag = tag;
            } else {
                // 未识别的字符：跳过，保证前进（否则选择器解析会死循环）
                i++;
            }
        }
        if (any) {
            steps.add(cur);
        }
        return steps;
    }

    /** 从 steps[idx] 开始向上匹配 */
    private static boolean matches(El el, List<Step> steps, int idx) {
        if (el == null || el.isText() || idx < 0) {
            return false;
        }
        if (!matchOne(el, steps.get(idx))) {
            return false;
        }
        if (idx == 0) {
            return true;
        }
        String comb = steps.get(idx).combinator;
        if (">".equals(comb)) {
            El p = el.parent;
            return p != null && !p.isText() && matches(p, steps, idx - 1);
        }
        // 后代：逐级向上尝试
        El p = el.parent;
        while (p != null && !p.isText()) {
            if (matches(p, steps, idx - 1)) {
                return true;
            }
            p = p.parent;
        }
        return false;
    }

    private static boolean matchOne(El el, Step st) {
        if (el.isText()) {
            return false;
        }
        if (st.tag != null && !st.tag.equals(el.tag)) {
            return false;
        }
        for (String cls : st.classes) {
            if (!hasClass(el, cls)) {
                return false;
            }
        }
        if (st.id != null && !st.id.equals(el.attr("id"))) {
            return false;
        }
        for (String[] a : st.attrs) {
            if (!el.hasAttr(a[0])) {
                return false;
            }
            if (a[1] != null && !a[1].equals(el.attr(a[0]))) {
                return false;
            }
        }
        if (st.nthChild > 0) {
            if (elementIndex(el) != st.nthChild) {
                return false;
            }
        }
        if (st.firstChild && elementIndex(el) != 1) {
            return false;
        }
        if (st.lastChild) {
            El p = el.parent;
            if (p == null) {
                return false;
            }
            int total = 0;
            for (El c : p.children) {
                if (!c.isText()) {
                    total++;
                }
            }
            if (elementIndex(el) != total) {
                return false;
            }
        }
        return true;
    }

    /** 元素在同级元素中的 1-based 序号（对齐 jsoup elementSiblingIndex+1） */
    private static int elementIndex(El el) {
        El p = el.parent;
        if (p == null) {
            return 1;
        }
        int idx = 0;
        for (El c : p.children) {
            if (c.isText()) {
                continue;
            }
            idx++;
            if (c == el) {
                return idx;
            }
        }
        return idx;
    }

    private static boolean hasClass(El el, String cls) {
        String v = el.attr("class");
        if (v.isEmpty()) {
            return false;
        }
        int i = 0;
        int n = v.length();
        while (i < n) {
            while (i < n && isSpace(v.charAt(i))) {
                i++;
            }
            int st = i;
            while (i < n && !isSpace(v.charAt(i))) {
                i++;
            }
            if (i > st && v.substring(st, i).equals(cls)) {
                return true;
            }
        }
        return false;
    }

    /** 顶层逗号切分（忽略 [ ] 与引号内的逗号） */
    private static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                cur.append(c);
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                cur.append(c);
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
            if (c == sep && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (cur.toString().trim().length() > 0) {
            out.add(cur.toString());
        }
        if (out.isEmpty()) {
            out.add(s);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 小工具
    // ─────────────────────────────────────────────────────────────

    /** 元素名起始字符 */
    private static boolean isNameStart(char c) {
        return Character.isLetter(c);
    }

    /** 元素名可含字符 */
    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':';
    }

    /** 选择器标识符字符 */
    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    /** 空白判定（HTML 语义，含 &nbsp; 产生的 \u00a0） */
    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u00a0';
    }

    private static void flushText(El cur, StringBuilder buf) {
        if (buf.length() == 0) {
            return;
        }
        // script/style 里可能被误当作标签，但 RAW_TEXT 分支已单独处理；此处是普通文本
        El t = new El(null, null, cur.base);
        t.raw = decodeEntities(buf.toString());
        t.parent = cur;
        cur.children.add(t);
        buf.setLength(0);
    }

    /** 隐式闭合：新开 tag 时若栈顶属于应被闭合集合，则先弹出 */
    private static El applyImpliedEnd(El cur, String tag) {
        Set<String> closes = IMPLIED_END.get(tag);
        if (closes == null) {
            return cur;
        }
        El c = cur;
        while (c != null && !c.isText() && closes.contains(c.tag)) {
            c = c.parent;
        }
        return c == null ? cur : c;
    }

    /** 处理结束标签：向上找到同名节点并返回其父 */
    private static El closeTag(El cur, String tag) {
        El c = cur;
        while (c != null && !c.isText()) {
            if (tag.equals(c.tag)) {
                return c.parent == null ? cur : c.parent;
            }
            c = c.parent;
        }
        // 无匹配结束标签：忽略
        return cur;
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        int n = haystack.length();
        int m = needle.length();
        for (int i = Math.max(0, from); i + m <= n; i++) {
            if (haystack.regionMatches(true, i, needle, 0, m)) {
                return i;
            }
        }
        return -1;
    }

    /** 常见实体解码（够覆盖站点文本；未识别的保留原样） */
    static String decodeEntities(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i);
            if (semi < 0 || semi - i > 10) {
                sb.append(c);
                i++;
                continue;
            }
            String ent = s.substring(i + 1, semi);
            String rep = entity(ent);
            if (rep == null) {
                sb.append(c);
                i++;
                continue;
            }
            sb.append(rep);
            i = semi + 1;
        }
        return sb.toString();
    }

    private static String entity(String ent) {
        if (ent.isEmpty()) {
            return null;
        }
        if (ent.charAt(0) == '#') {
            try {
                int code = ent.length() > 1 && (ent.charAt(1) == 'x' || ent.charAt(1) == 'X')
                        ? Integer.parseInt(ent.substring(2), 16)
                        : Integer.parseInt(ent.substring(1));
                return String.valueOf((char) code);
            } catch (Exception e) {
                return null;
            }
        }
        switch (ent.toLowerCase(Locale.ROOT)) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return "\u00a0";
            case "ensp": return " ";
            case "emsp": return " ";
            case "middot": return "·";
            case "hellip": return "…";
            case "mdash": return "—";
            case "ndash": return "–";
            case "ldquo": return "“";
            case "rdquo": return "”";
            case "lsquo": return "‘";
            case "rsquo": return "’";
            default: return null;
        }
    }

    /**
     * 相对路径转绝对（对齐 jsoup {@code absUrl}：支持协议相对、根相对、点段与 ..）。
     *
     * @param base 文档 URL
     * @param url  待解析地址
     * @return 绝对地址；无法解析时原样返回
     */
    public static String resolve(String base, String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) {
            return "";
        }
        String lower = u.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("rtmp://") || lower.startsWith("rtsp://")) {
            return u;
        }
        String b = base == null ? "" : base.trim();
        if (b.isEmpty()) {
            return u;
        }
        String scheme = "http";
        int sc = b.indexOf("://");
        if (sc > 0) {
            scheme = b.substring(0, sc);
        }
        if (u.startsWith("//")) {
            return scheme + ":" + u;
        }
        String origin = origin(b);
        if (u.startsWith("/")) {
            return origin + u;
        }
        // 相对路径：拼到 base 的目录后规范 ../
        String dir = b;
        int qm = dir.indexOf('?');
        if (qm >= 0) {
            dir = dir.substring(0, qm);
        }
        int hash = dir.indexOf('#');
        if (hash >= 0) {
            dir = dir.substring(0, hash);
        }
        int slash = dir.lastIndexOf('/');
        if (slash > sc + 3) {
            dir = dir.substring(0, slash + 1);
        } else {
            dir = origin + "/";
        }
        return normalize(dir + u);
    }

    /**
     * 取 URL 的协议 + 主机（含端口）。
     *
     * @param url URL
     * @return origin；解析失败返回空串
     */
    public static String origin(String url) {
        String u = url == null ? "" : url.trim();
        int sc = u.indexOf("://");
        if (sc < 0) {
            return "";
        }
        int slash = u.indexOf('/', sc + 3);
        return slash < 0 ? u : u.substring(0, slash);
    }

    /** 规范化路径里的 "." 与 ".." 段 */
    private static String normalize(String url) {
        int sc = url.indexOf("://");
        if (sc < 0) {
            return url;
        }
        int pathStart = url.indexOf('/', sc + 3);
        if (pathStart < 0) {
            return url;
        }
        String prefix = url.substring(0, pathStart);
        String path = url.substring(pathStart);
        String suffix = "";
        int cut = path.length();
        int qm = path.indexOf('?');
        int hh = path.indexOf('#');
        if (qm >= 0) {
            cut = qm;
        }
        if (hh >= 0 && hh < cut) {
            cut = hh;
        }
        if (cut < path.length()) {
            suffix = path.substring(cut);
            path = path.substring(0, cut);
        }
        List<String> segs = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (!segs.isEmpty()) {
                    segs.remove(segs.size() - 1);
                }
                continue;
            }
            segs.add(seg);
        }
        StringBuilder sb = new StringBuilder(prefix);
        for (String seg : segs) {
            sb.append('/').append(seg);
        }
        if (path.endsWith("/") && sb.length() > prefix.length()) {
            sb.append('/');
        }
        if (sb.length() == prefix.length()) {
            sb.append('/');
        }
        return sb.append(suffix).toString();
    }
}
