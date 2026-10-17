package com.example.tvbox.common;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 最小 HTML 提取工具 —— 供 sports / ting 两个包共用。
 *
 * <h2>为什么不用 jsoup</h2>
 * 上游插件用 okhttp + jsoup（{@code Jsoup.parse} / {@code .o0(selector)} / {@code .i()} /
 * {@code .a("href")}）。本重写工程的编译环境未引入 jsoup，而 {@code maintain/src/common/}
 * 不归本包维护，因此这里用正则实现<b>只覆盖本任务需要的 4 个动作</b>：
 * <ol>
 *   <li>按「标签名 + class 正则」取出所有同级块（支持同名标签嵌套计数）</li>
 *   <li>取开标签上的属性值</li>
 *   <li>取纯文本（去 script/style、去标签、折叠空白、解常用实体）</li>
 *   <li>取首次匹配的子串（用于 meta content 这类定点抽取）</li>
 * </ol>
 *
 * <h2>xpath-lite 语义（对应上游 jsoup 用法）</h2>
 * <table>
 *   <tr><th>上游 jsoup</th><th>本类等价</th><th>说明</th></tr>
 *   <tr><td>{@code doc.select("li.item")}</td>
 *       <td>{@link #children(String,String,String) children(html,"li",".*\\bitem\\b.*")}</td>
 *       <td>返回<b>内部 HTML</b> 列表；同名标签做深度计数，嵌套能正确闭合</td></tr>
 *   <tr><td>{@code el.select("a").attr("href")}</td>
 *       <td>{@link #openTagOf(String,String,String) openTagOf} + {@link #attr(String,String) attr}</td>
 *       <td>读属性必须取<b>开标签</b>，{@code innerFirst} 只有内部 HTML</td></tr>
 *   <tr><td>{@code el.select("h3").text()}</td>
 *       <td>{@link #textFirst(String,String,String) textFirst}</td>
 *       <td>去注释/script/style、删内联标签、块级标签换空格、折叠空白、解实体</td></tr>
 *   <tr><td>{@code el.text()}</td>
 *       <td>{@link #text(String) text}</td>
 *       <td>同上，作用于整段</td></tr>
 *   <tr><td>{@code doc.selectFirst("meta[name=_b]").attr("content")}</td>
 *       <td>{@link #meta(String,String) meta(html,"_b")}</td>
 *       <td>name 大小写不敏感，属性顺序无关</td></tr>
 * </table>
 *
 * <p><b>不是通用 DOM 实现</b>：不支持 CSS 组合选择器、兄弟/父子伪类、属性选择器。
 * 调用方应先用 {@link #children(String, String, String)} 收敛到块，再在块内做定点抽取。
 *
 * <p>若后续 infra 决定把 HTML 工具收编进 {@code common}，本类可整体迁移，方法签名无需改动。
 */
public final class Html {

    /** 折叠空白时使用的宽松空白正则（含全角空格） */
    private static final Pattern WS = Pattern.compile("[\\s\\u00a0\\u3000]+");

    /** script / style 块，提取文本前先剔除 */
    private static final Pattern NOISE =
            Pattern.compile("<(script|style)\\b[^>]*>.*?</\\1\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 内联标签（不产生换行/空格语义，如 <b>a</b>b 应得 "ab" 而不是 "a b"） */
    private static final Pattern INLINE_TAG = Pattern.compile(
            "</?(b|i|u|em|strong|span|a|font|small|big|sup|sub|code|mark|label|nobr|tt|s|del|ins)"
          + "\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /** 其余（块级）标签：替换为空格，避免相邻块文本粘连 */
    private static final Pattern TAG = Pattern.compile("<[^>]*>", Pattern.DOTALL);

    /** 注释 */
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private Html() {
    }

    // ─────────────────────────────────────────────────────────────
    // 块抽取
    // ─────────────────────────────────────────────────────────────

    /**
     * 取出所有「标签名 + 开标签 class 匹配」的元素的内部 HTML。
     *
     * <p>同名标签会做深度计数，因此 {@code <div><div>..</div></div>} 能正确闭合。
     * 自闭合标签（{@code <img/>}、{@code <br>}）不适用本方法。
     *
     * @param html         源 HTML，可为 null
     * @param tag          标签名（不含尖括号），如 {@code "div"}
     * @param classRegex   开标签 class 属性要匹配的正则；null 表示不限定（任意元素）
     * @return 内部 HTML 片段列表，永不 null
     */
    public static List<String> children(String html, String tag, String classRegex) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isEmpty() || tag == null || tag.isEmpty()) {
            return out;
        }
        Pattern open = Pattern.compile("<" + tag + "\\b[^>]*>", Pattern.CASE_INSENSITIVE);
        Pattern token = Pattern.compile("<(/?)" + tag + "\\b", Pattern.CASE_INSENSITIVE);
        Matcher m = open.matcher(html);
        int from = 0;
        while (from < html.length() && m.find(from)) {
            String openTag = m.group();
            if (classRegex != null && !hasClass(openTag, classRegex)) {
                from = m.end();
                continue;
            }
            int innerStart = m.end();
            int end = matchEnd(html, token, innerStart, tag);
            out.add(html.substring(innerStart, Math.min(end, html.length())));
            from = Math.min(end + 1, html.length());
        }
        return out;
    }

    /**
     * 从 {@code innerStart} 起寻找与已打开的 {@code tag} 匹配的结束位置（指向结束标签起点）。
     *
     * @param html       源 HTML
     * @param token      形如 {@code <(/?)tag\b} 的记号正则
     * @param innerStart 内容起点
     * @param tag        标签名（仅用于日志可读性）
     * @return 内容结束下标；未找到时返回 html.length()
     */
    private static int matchEnd(String html, Pattern token, int innerStart, String tag) {
        int depth = 1;
        Matcher t = token.matcher(html);
        if (innerStart >= html.length()) {
            return html.length();
        }
        t.region(innerStart, html.length());
        while (t.find()) {
            if (t.group(1).isEmpty()) {
                depth++;
            } else {
                depth--;
                if (depth == 0) {
                    return t.start();
                }
            }
        }
        // 未闭合（真实页面里被截断的情况）——退化为整段剩余，交给调用方的容错逻辑
        return html.length();
    }

    /**
     * 取第一个「标签名 + class 匹配」元素的内部 HTML。
     *
     * @param html       源 HTML
     * @param tag        标签名
     * @param classRegex class 正则，null 表示不限定
     * @return 内部 HTML；未命中返回空串
     */
    public static String innerFirst(String html, String tag, String classRegex) {
        List<String> all = children(html, tag, classRegex);
        return all.isEmpty() ? "" : all.get(0);
    }

    /**
     * 取第一个「标签名 + 无 class 要求」元素的内部 HTML（用于 {@code <a href>} 这类）。
     *
     * @param html 源 HTML
     * @param tag  标签名
     * @return 内部 HTML；未命中返回空串
     */
    public static String innerFirst(String html, String tag) {
        return innerFirst(html, tag, null);
    }

    // ─────────────────────────────────────────────────────────────
    // 属性
    // ─────────────────────────────────────────────────────────────

    /**
     * 取片段里<b>第一个开标签</b>的指定属性值。
     *
     * @param fragment 片段（可含子元素，只看首个开标签）
     * @param name     属性名（大小写不敏感）
     * @return 属性值（已解实体）；不存在返回空串
     */
    public static String attr(String fragment, String name) {
        if (fragment == null || name == null || name.isEmpty()) {
            return "";
        }
        String openTag = openTag(fragment);
        if (openTag.isEmpty()) {
            return "";
        }
        Pattern p = Pattern.compile(
                "\\b" + Pattern.quote(name) + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>\"']+))",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(openTag);
        if (!m.find()) {
            return "";
        }
        String v = m.group(2) != null ? m.group(2) : (m.group(3) != null ? m.group(3) : m.group(4));
        return decode(v == null ? "" : v);
    }

    /**
     * 取片段里第一个开标签（含尖括号之前的全部内容）。
     *
     * @param fragment 片段
     * @return 开标签文本；无尖括号时返回整段
     */
    public static String openTag(String fragment) {
        if (fragment == null) {
            return "";
        }
        int gt = fragment.indexOf('>');
        return gt < 0 ? fragment.trim() : fragment.substring(0, gt + 1);
    }

    /**
     * 取第一个「标签名 + class 匹配」元素的<b>开标签</b>文本（用于读属性）。
     *
     * <p>{@link #innerFirst} 只给内部 HTML，拿不到外层属性；读 {@code href/src}
     * 这类属性时要用本方法。
     *
     * @param html       源 HTML
     * @param tag        标签名
     * @param classRegex class 正则，null 表示不限定
     * @return 开标签文本（含尖括号）；未命中返回空串
     */
    public static String openTagOf(String html, String tag, String classRegex) {
        if (html == null || html.isEmpty() || tag == null || tag.isEmpty()) {
            return "";
        }
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            if (classRegex == null || hasClass(m.group(), classRegex)) {
                return m.group();
            }
        }
        return "";
    }

    /**
     * 判断开标签的 class 属性是否匹配给定正则（子串语义由调用方用 {@code .*x.*} 表达）。
     *
     * @param openTag    开标签文本
     * @param classRegex class 正则
     * @return true 表示匹配
     */
    public static boolean hasClass(String openTag, String classRegex) {
        String cls = attr(openTag, "class");
        if (cls.isEmpty() || classRegex == null) {
            return classRegex == null;
        }
        return cls.matches(classRegex);
    }

    /**
     * 取指定标签上某个属性的<b>全部</b>出现（用于一页多图这类场景）。
     *
     * @param html  源 HTML
     * @param tag   标签名
     * @param name  属性名
     * @return 值列表，永不 null
     */
    public static List<String> attrAll(String html, String tag, String name) {
        List<String> out = new ArrayList<>();
        if (html == null || tag == null || name == null) {
            return out;
        }
        Pattern p = Pattern.compile("<" + tag + "\\b[^>]*>", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find()) {
            String v = attr(m.group(), name);
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 文本
    // ─────────────────────────────────────────────────────────────

    /**
     * 取纯文本：剔除注释 / script / style，去标签，解常用实体，折叠空白并 trim。
     *
     * @param html 源 HTML 或片段，可为 null
     * @return 纯文本；输入为空时返回空串
     */
    public static String text(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String s = COMMENT.matcher(html).replaceAll(" ");
        s = NOISE.matcher(s).replaceAll(" ");
        // 内联标签直接删（保词内连续），块级标签替换为空格
        s = INLINE_TAG.matcher(s).replaceAll("");
        s = TAG.matcher(s).replaceAll(" ");
        s = decode(s);
        s = WS.matcher(s).replaceAll(" ");
        return s.trim();
    }

    /**
     * 取首个「标签名 + class 匹配」元素的纯文本。
     *
     * @param html       源 HTML
     * @param tag        标签名
     * @param classRegex class 正则，null 不限定
     * @return 纯文本；未命中返回空串
     */
    public static String textFirst(String html, String tag, String classRegex) {
        return text(innerFirst(html, tag, classRegex));
    }

    // ─────────────────────────────────────────────────────────────
    // 定点抽取
    // ─────────────────────────────────────────────────────────────

    /**
     * 正则首次匹配的第 {@code group} 组。
     *
     * @param html   源文本
     * @param regex  正则（调用方保证含足够分组）
     * @param group  分组号（>=1）
     * @return 匹配内容；未命中返回空串
     */
    public static String first(String html, String regex, int group) {
        if (html == null || regex == null) {
            return "";
        }
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (!m.find() || m.groupCount() < group) {
            return "";
        }
        return m.group(group) == null ? "" : m.group(group);
    }

    /**
     * 正则全部匹配的第 {@code group} 组。
     *
     * @param html  源文本
     * @param regex 正则
     * @param group 分组号（>=1）
     * @return 匹配内容列表，永不 null
     */
    public static List<String> all(String html, String regex, int group) {
        List<String> out = new ArrayList<>();
        if (html == null || regex == null) {
            return out;
        }
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (m.find()) {
            if (m.groupCount() < group) {
                break;
            }
            String v = m.group(group);
            if (v != null && !v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    /**
     * 取 {@code <meta name="X" content="Y">} 的 Y（顺序无关，由 {@link #attr} 兜底）。
     *
     * @param html 源 HTML
     * @param name meta 的 name 值，如 {@code "_b"}
     * @return content 值；未命中返回空串
     */
    public static String meta(String html, String name) {
        if (html == null || name == null) {
            return "";
        }
        List<String> metas = children(html, "meta", null);
        for (String tag : metas) {
            if (name.equalsIgnoreCase(attr(tag, "name"))) {
                return attr(tag, "content");
            }
        }
        // children 会因 <meta> 自闭合无法配对而退化为剩余整段，故再补一条正则路径
        return first(html, "<meta[^>]*name=[\"']" + Pattern.quote(name) + "[\"'][^>]*content=[\"']([^\"']*)[\"']", 1);
    }

    // ─────────────────────────────────────────────────────────────
    // 实体
    // ─────────────────────────────────────────────────────────────

    /**
     * 解码常见 HTML 实体与数字实体。
     *
     * @param s 原文
     * @return 解码结果
     */
    public static String decode(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        String r = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&mdash;", "—")
                .replace("&middot;", "·")
                .replace("&hellip;", "…");
        return decodeNumeric(r);
    }

    /**
     * 解码 {@code &#123;} / {@code &#x1F;} 形式的数字实体。
     *
     * @param s 原文
     * @return 解码结果
     */
    private static String decodeNumeric(String s) {
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp;
            try {
                cp = m.group(1).isEmpty()
                        ? Integer.parseInt(m.group(2))
                        : Integer.parseInt(m.group(2), 16);
            } catch (Throwable e) {
                continue;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 拼接字符串集合（用替代 {@code android.text.TextUtils.join}，stub 环境返回空串）。
     *
     * @param sep   分隔符
     * @param parts 片段
     * @return 拼接结果
     */
    public static String join(String sep, List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(parts.get(i) == null ? "" : parts.get(i));
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────
    // jsoup 风格桥接（供使用 Html.parse(...).select(...) 的插件）
    // ─────────────────────────────────────────────────────────────

    /** 元素节点别名 —— 见 {@link Dom.El} */
    public static final class El extends Dom.El {
        El(String tag, java.util.Map<String, String> attrs, String inner, String outer, int idx) {
            super(tag, attrs, inner, outer, idx);
        }
    }

    /** 解析 HTML（jsoup 风格） */
    public static Dom.El parse(String html, String base) {
        return Dom.parse(html, base);
    }

    /** 解析 HTML（无 base） */
    public static Dom.El parse(String html) {
        return Dom.parse(html, "");
    }

    /** 在 HTML 中按选择器查找 */
    public static java.util.List<Dom.El> select(String html, String selector) {
        return Dom.select(html, selector);
    }

    /** 是否自闭合标签（img/br/input 等） */
    public static boolean isVoidTag(String tag) {
        if (tag == null) return false;
        switch (tag.toLowerCase()) {
            case "area": case "base": case "br": case "col": case "embed":
            case "hr": case "img": case "input": case "link": case "meta":
            case "param": case "source": case "track": case "wbr":
                return true;
            default:
                return false;
        }
    }

}
