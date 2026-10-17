package com.example.tvbox.plain.film2;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 HTML 提取工具 —— 用正则近似 jsoup 选择器的语义。
 *
 * <h2>为什么不用 jsoup</h2>
 * 上游 8 个影视类里有 6 个是 jsoup 抓取（{@code oj.a(...).ah("selector")}）。
 * 本仓库的编译目标只有 {@code ae/stubs} + JDK，没有引入 jsoup 依赖，
 * 因此这里用「块切分 + 属性/文本提取」的方式还原同样语义，并把上游选择器
 * 逐字写进各子类注释，便于与反编译原文对照。
 *
 * <h2>能力边界（明确写出，避免误用）</h2>
 * <ul>
 *   <li>支持：按正则切出元素块、取属性、取纯文本、实体解码、相对路径补全</li>
 *   <li>不支持：CSS 层级伪类（:contains）、DOM 树遍历、隐式标签闭合修正</li>
 * </ul>
 * 所以各子类只使用「扁平选择器」（如 {@code ul#post_container li}），
 * 对 {@code li:contains(导演:)} 这类选择器改用纯文本段定位（见 {@link #segment}）。
 *
 * <h2>证据</h2>
 * 上游 jsoup 调用点：{@code ae/decompiled/New6v.java}、{@code MeijuMi.java}、
 * {@code Web1905.java}、{@code SeedHub.java}、{@code 4KZhinan.java}、{@code YGP.java}。
 */
public final class HtmlLite {

    /** 需要整体丢弃的标签内容（脚本/样式/注释） */
    private static final Pattern RE_DROP = Pattern.compile(
            "(?is)<script\\b[^>]*>.*?</script>"
          + "|<style\\b[^>]*>.*?</style>"
          + "|<!--.*?-->");

    /** 任意标签 */
    private static final Pattern RE_TAG = Pattern.compile("(?s)<[^>]+>");

    /** 连续空白 */
    private static final Pattern RE_WS = Pattern.compile("[\\s\\u00a0]+");

    /** 数字串 */
    private static final Pattern RE_DIGITS = Pattern.compile("(\\d{1,9})");

    private HtmlLite() {
    }

    // ─────────────────────────────────────────────────────────────
    // 文本
    // ─────────────────────────────────────────────────────────────

    /**
     * 取元素块的纯文本：丢脚本/样式 → 去标签 → 解实体 → 折叠空白。
     *
     * @param html 片段，可为 null
     * @return 纯文本，永不为 null
     */
    public static String text(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String s = RE_DROP.matcher(html).replaceAll(" ");
        s = RE_TAG.matcher(s).replaceAll(" ");
        s = decodeEntities(s);
        return RE_WS.matcher(s).replaceAll(" ").trim();
    }

    /**
     * 只解实体，不动标签（用于 {@code title="..."} 属性值）。
     *
     * @param s 原文，可为 null
     * @return 解码后文本
     */
    public static String decodeEntities(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        String r = s;
        r = r.replace("&amp;middot;", "·").replace("&amp;nbsp;", " ");
        r = r.replace("&nbsp;", " ").replace("&middot;", "·");
        r = r.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        r = r.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        r = r.replace("&ldquo;", "“").replace("&rdquo;", "”");
        r = r.replace("&hellip;", "…").replace("&mdash;", "—").replace("&ndash;", "–");
        r = r.replace("&times;", "×").replace("&divide;", "÷");
        // 数字实体：&#123; / &#x1F600;
        Matcher m = Pattern.compile("&#(x?)([0-9A-Fa-f]{1,6});").matcher(r);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try {
                int cp = "x".equals(m.group(1))
                        ? Integer.parseInt(m.group(2), 16)
                        : Integer.parseInt(m.group(2), 10);
                m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
            } catch (Throwable ignored) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────
    // 属性
    // ─────────────────────────────────────────────────────────────

    /**
     * 取标签块里第一个指定属性的值（支持双引号/单引号/无引号三种写法）。
     *
     * @param tagHtml 标签片段（可以是整段 HTML，取第一个命中）
     * @param name    属性名，如 {@code href} / {@code data-src} / {@code title}
     * @return 属性值（已解实体）；缺失返回空串
     */
    public static String attr(String tagHtml, String name) {
        if (tagHtml == null || tagHtml.isEmpty() || name == null || name.isEmpty()) {
            return "";
        }
        Pattern p = Pattern.compile(
                "(?is)\\b" + Pattern.quote(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>\"']+))");
        Matcher m = p.matcher(tagHtml);
        if (!m.find()) {
            return "";
        }
        String v = m.group(1) != null ? m.group(1)
                : (m.group(2) != null ? m.group(2) : m.group(3));
        return decodeEntities(v == null ? "" : v).trim();
    }

    /**
     * 取标签块里所有指定属性的值（保持文档顺序，去重）。
     *
     * @param html 整段 HTML
     * @param name 属性名
     * @return 非 null 列表
     */
    public static List<String> attrs(String html, String name) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        Matcher m = Pattern.compile(
                "(?is)\\b" + Pattern.quote(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").matcher(html);
        while (m.find()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2);
            if (v != null && !v.isEmpty() && !out.contains(v)) {
                out.add(decodeEntities(v).trim());
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 结构切分
    // ─────────────────────────────────────────────────────────────

    /**
     * 用「起始标记 + 结束标记」切出重复块 —— 等价 jsoup 的一次扁平 {@code select}。
     *
     * <p>典型用法（对应上游 {@code ul#post_container li}）：
     * <pre>
     *  List&lt;String&gt; items = HtmlLite.blocks(html, "&lt;li class=\"post", "&lt;/li&gt;");
     * </pre>
     *
     * <p>只适用于块内不再出现同名标签的结构（{@code li} / {@code article} / 自闭合项）。
     * 嵌套同名标签（如 {@code div}）请用 {@link #nestedBlocks(String, String, String)}。
     *
     * @param html      整段 HTML
     * @param startMark 起始标记子串（区分大小写，按原文写）
     * @param endMark   结束标记子串
     * @return 每个元素为「含起止标记」的完整块；未命中返回空列表
     */
    public static List<String> blocks(String html, String startMark, String endMark) {
        List<String> out = new ArrayList<>();
        if (html == null || startMark == null || endMark == null
                || startMark.isEmpty() || endMark.isEmpty()) {
            return out;
        }
        int from = 0;
        while (true) {
            int s = html.indexOf(startMark, from);
            if (s < 0) {
                break;
            }
            int e = html.indexOf(endMark, s);
            if (e < 0) {
                break;
            }
            out.add(html.substring(s, e + endMark.length()));
            from = e + endMark.length();
        }
        return out;
    }

    /**
     * 嵌套安全的块切分 —— 按标签深度配对，正确处理块内同名嵌套标签。
     *
     * <p>用于 {@code div} 容器（SeedHub 的 {@code .cover} 里有多层 {@code div}/{@code ul}）。
     * 算法：定位起始标记 → 从该标记所在 {@code <tag} 开始，逐个扫描 {@code <tag} 与
     * {@code </tag>}，深度归零处即为块尾。
     *
     * @param html      整段 HTML
     * @param startMark 起始标记子串（一般形如 {@code <div class="cover"}）
     * @param tag       标签名，如 {@code div}（小写）
     * @return 每个元素为完整块（含起止标签）；未命中返回空列表
     */
    public static List<String> nestedBlocks(String html, String startMark, String tag) {
        List<String> out = new ArrayList<>();
        if (html == null || startMark == null || tag == null
                || startMark.isEmpty() || tag.isEmpty()) {
            return out;
        }
        String open = "<" + tag;
        String close = "</" + tag;
        int from = 0;
        while (true) {
            int s = html.indexOf(startMark, from);
            if (s < 0) {
                break;
            }
            int depth = 0;
            int i = s;
            int end = -1;
            while (i < html.length()) {
                int no = indexOfIgnoreCase(html, open, i);
                int nc = indexOfIgnoreCase(html, close, i);
                if (nc < 0) {
                    break;
                }
                if (no >= 0 && no < nc) {
                    depth++;
                    i = no + open.length();
                } else {
                    depth--;
                    i = nc + close.length();
                    if (depth <= 0) {
                        int gt = html.indexOf('>', i - close.length());
                        end = gt >= 0 ? gt + 1 : i;
                        break;
                    }
                }
            }
            if (end < 0) {
                break;
            }
            out.add(html.substring(s, Math.min(html.length(), end)));
            from = end;
        }
        return out;
    }

    /**
     * 大小写不敏感查找。
     *
     * @param haystack 被查找文本
     * @param needle   目标
     * @param from     起始下标
     * @return 下标；未命中 -1
     */
    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        int n = needle.length();
        int last = haystack.length() - n;
        for (int i = Math.max(0, from); i <= last; i++) {
            if (haystack.regionMatches(true, i, needle, 0, n)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 取整段 HTML 里第一个匹配组 1 的值（正则选择器主入口）。
     *
     * @param html    整段 HTML
     * @param pattern 带一个捕获组的正则
     * @return 组 1 文本；未命中返回空串
     */
    public static String first(String html, Pattern pattern) {
        if (html == null || pattern == null) {
            return "";
        }
        Matcher m = pattern.matcher(html);
        return m.find() && m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : "";
    }

    /**
     * 取第 n 个捕获组。
     *
     * @param html    整段 HTML
     * @param pattern 正则
     * @param group   组号（0 = 整体）
     * @return 组文本；未命中返回空串
     */
    public static String group(String html, Pattern pattern, int group) {
        if (html == null || pattern == null) {
            return "";
        }
        Matcher m = pattern.matcher(html);
        if (!m.find() || group < 0 || group > m.groupCount()) {
            return "";
        }
        return m.group(group) == null ? "" : m.group(group);
    }

    /**
     * 取所有匹配组的文本。
     *
     * @param html    整段 HTML
     * @param pattern 正则
     * @param group   组号
     * @return 非 null 列表
     */
    public static List<String> all(String html, Pattern pattern, int group) {
        List<String> out = new ArrayList<>();
        if (html == null || pattern == null) {
            return out;
        }
        Matcher m = pattern.matcher(html);
        while (m.find()) {
            if (group >= 0 && group <= m.groupCount() && m.group(group) != null) {
                String v = m.group(group).trim();
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    /**
     * 「标签 + 纯文本」段落定位 —— 替代 jsoup 的 {@code li:contains(导演:)}。
     *
     * <p>做法：找到 {@code label} 起点，截取到下一个 {@code nextLabel}（或 {@code limit} 字符）
     * 之前，再去标签取文本。用于从豆瓣式简介块里抠「导演: xxx」这类行。
     *
     * @param text      已去标签的纯文本
     * @param label     起始标签，如 {@code 导演:}
     * @param nextLabel 结束标签，如 {@code 编剧:}；null 表示到文本尾
     * @param limit     最大截取长度，&lt;=0 时用默认 200
     * @return 两标签之间的文本（不含标签本身）；未命中返回空串
     */
    public static String segment(String text, String label, String nextLabel, int limit) {
        if (text == null || label == null || label.isEmpty()) {
            return "";
        }
        int s = text.indexOf(label);
        if (s < 0) {
            return "";
        }
        int from = s + label.length();
        int to;
        if (nextLabel != null && !nextLabel.isEmpty()) {
            to = text.indexOf(nextLabel, from);
        } else {
            to = -1;
        }
        if (to < 0) {
            to = Math.min(text.length(), from + (limit > 0 ? limit : 200));
        }
        return text.substring(from, to).trim();
    }

    // ─────────────────────────────────────────────────────────────
    // URL / 数字
    // ─────────────────────────────────────────────────────────────

    /**
     * 相对地址补全：已是绝对地址原样返回，否则拼到 base 后面。
     *
     * @param base 站点基址（无尾斜杠）
     * @param href 候选地址
     * @return 绝对地址；href 为空时返回空串
     */
    public static String absolute(String base, String href) {
        if (href == null || href.isEmpty()) {
            return "";
        }
        String h = href.trim();
        if (h.startsWith("http://") || h.startsWith("https://")
                || h.startsWith("magnet:") || h.startsWith("ed2k:") || h.startsWith("ftp")) {
            return h;
        }
        String b = base == null ? "" : base.trim();
        if (h.startsWith("//")) {
            return "https:" + h;
        }
        if (h.startsWith("/")) {
            return b + h;
        }
        return b + "/" + h;
    }

    /**
     * 提取文本里的第一个整数。
     *
     * @param s   原文
     * @param def 缺省值
     * @return 整数或缺省值
     */
    public static int intOf(String s, int def) {
        if (s == null) {
            return def;
        }
        Matcher m = RE_DIGITS.matcher(s);
        if (!m.find()) {
            return def;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (Throwable e) {
            return def;
        }
    }

    /**
     * 拼接字符串列表（本仓库 {@code TextUtils.join} 是空桩，不能依赖）。
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
            String p = parts.get(i);
            sb.append(p == null ? "" : p);
        }
        return sb.toString();
    }

    /**
     * 空安全。
     *
     * @param s 原文
     * @return 非 null 字符串
     */
    public static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 是否空白（对应上游 {@code Ga.b()}）。
     *
     * @param s 原文
     * @return true 表示 null 或全空白
     */
    public static boolean blank(String s) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
