package com.example.tvbox.plain.hiker;

import com.example.tvbox.common.Dom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XYQHiker 的选择器求值引擎 —— 与站点无关，全部行为由规则串（DSL）决定。
 *
 * <p>反编译原文: {@code ae/decompiled/XYQHiker.java}
 * （静态工具段 {@code :97-383}，其中 {@code getText}/{@code getTextByRule}/
 * {@code getTrueElement}/{@code o}/{@code p}/{@code q}/{@code selectElements}/
 * {@code listToString}/{@code m}/{@code n}/{@code string2Hex}/{@code ag}）。
 * smali 权威对照: {@code baksmali re/dex/classes.dex} →
 * {@code XYQHiker.smali}（行号见各方法 javadoc）。
 *
 * <h2>原混淆名 → 语义名对照表</h2>
 * <table>
 *   <tr><th>原</th><th>smali 行</th><th>本类</th><th>含义</th></tr>
 *   <tr><td>{@code p(ed,String)}</td><td>961</td><td>{@link #pick}</td>取一个值：文本 / HTML / 属性 / B64</td></tr>
 *   <tr><td>{@code getText(ed,String)}</td><td>192</td><td>{@link #text}</td>{@code ||} 多规则回落后取一个值</td></tr>
 *   <tr><td>{@code getTextByRule(ed,String)}</td><td>267</td><td>{@link #textByRule}</td>{@code +}/{@code ＋} 多字段拼接</td></tr>
 *   <tr><td>{@code o(ed,String,String)}</td><td>811</td><td>{@link #chainPick}</td>{@code &&} 链式下钻 + 取一个值</td></tr>
 *   <tr><td>{@code getTrueElement(String,ed)}</td><td>334</td><td>{@link #trueElement}</td>规则 → 单个元素</td></tr>
 *   <tr><td>{@code q(ed,String)}</td><td>1221</td><td>{@link #selectSlice}</td>带 {@code ,start:end} 切片的元素集合</td></tr>
 *   <tr><td>{@code selectElements(ed,String)}</td><td>1360</td><td>{@link #selectElements}</td>{@code ||} 并集元素集合</td></tr>
 *   <tr><td>{@code listToString(List,String)}</td><td>569</td><td>{@link #join}</td>拼接（0/1 项原样返回）</td></tr>
 *   <tr><td>{@code m(String)}</td><td>660</td><td>{@link #unescapeUnicode}</td>Unicode 转义还原</td></tr>
 *   <tr><td>{@code n()}</td><td>724</td><td>{@link #yearList}</td>「今年&amp;去年&amp;…&amp;20 年前」</td></tr>
 *   <tr><td>{@code string2Hex(String,String)}</td><td>1407</td><td>{@link #string2Hex}</td>字符码拼接（防爬用）</td></tr>
 *   <tr><td>{@code ag(String)}</td><td>3388</td><td>{@link #stripTags}</td>去标签 / 解实体的粗清理</td></tr>
 *   <tr><td>{@code wh.g(base,path)}</td><td>—</td><td>{@link HikerExtractor#absolutize}</td>URL 绝对化（<b>不是</b>正则）</td></tr>
 *   <tr><td>{@code wh.q(src,from,to)}</td><td>—</td><td>{@link #between}</td>取 from/to 之间</td></tr>
 *   <tr><td>{@code wh.n(src,spec)}</td><td>—</td><td>{@link #regexReplace}</td>{@code [替换:…]} 正则替换</td></tr>
 * </table>
 *
 * <h2>规则串语法（选择器侧）</h2>
 * <pre>
 * rule       ::= value ( "||" value )*                // text()  : 逐个试，取第一个非空
 *              | part ( "＋" | "+" ) part ...          // textByRule(): 多值拼接
 * value      ::= source [ "!" strip ]*
 * source     ::= "Text"                                // El.text()
 *              | "Html"                                // El.html()（内层 HTML）
 *              | "B64Dec"                              // Base64 解码 El.text()
 *              | "Attr"+name  |  name                  // El.attr(name)
 *              | selector                              // El.select(selector).get(0)
 * strip      ::= 字面量，从结果里 String.replace 掉
 * selector   ::= chain
 * chain      ::= step ( "&&" step )*
 * step       ::= sel
 *              | sel "," index                          // 同类第 index 个（负数从尾部数）
 *              | a "||" b                                // 取第一个命中
 *              | a "--" b "--" c                         // 从 a 的 outerHtml 里删掉 b、c 的 outerHtml
 *              | "Text" | "Attr"* | href|src|class|title|alt   // 恒等（不再下钻）
 * sel        ::= CSS 子集（见 {@link Dom}）：tag / .cls / #id / A B / A &gt; B / tag:nth-child(n)
 * </pre>
 *
 * <h2>与上游的差异（都是上游缺陷，已修）</h2>
 * <ol>
 *   <li><b>{@code wh.q} 无匹配时 {@code get(0)} 越界抛 IndexOutOfBounds</b>。
 *       上游 {@code af}/{@code ad}/{@code t}/{@code s}/{@code ah} 里多处
 *       {@code (String) wh.q(...).get(0)} 直接就崩。本类 {@link #between}
 *       无匹配返回空串（并用 {@link #betweenList} 暴露原始 List 形态给需要数量的调用方）。</li>
 *   <li><b>{@code p()} 的 {@code !} 分支判空比错了变量</b>：上游写
 *       {@code if (!"Html".equals(str))}，而此处 {@code str} 已经带过 {@code !xxx} 后缀，
 *       于是 {@code "Html!x"} 会被误判为「非 Html」而做换行压缩。本类改为比较 {@code !} 之前的头部。</li>
 *   <li><b>{@code p()} 异常时返回 {@code null}</b>，调用方普遍不判空，
 *       会把 NPE 扩散到外层 catch。本类统一返回空串。</li>
 *   <li><b>{@code getTrueElement} 的 {@code ||} 分支只吞异常不兜底</b>：
 *       全部落空时返回 {@code null}。本类保持 {@code null}（调用方 {@code o()} 依赖这个语义），
 *       但 {@link #trueElementOrEmpty} 提供空元素版本给新代码用。</li>
 * </ol>
 *
 * <h2>不属于本类的语义（勘误）</h2>
 * 任务书提到「{@code ;} 分隔的取文本 vs 取属性路径」。核对 smali 后确认：
 * <b>{@code ;} 在本类里不是选择器分隔符</b>，选择器的分隔符只有
 * {@code ||} / {@code &&} / {@code !} / {@code ,} / {@code --} / {@code +}。
 * {@code ;} 出现的位置是 URL 指令分隔符 —— {@code url?body;post} 表示 POST、
 * {@code url;其他} 表示取 {@code ;} 之前的部分做 GET。该语义在
 * {@link HikerExtractor#parseUrlSpec(String)} 里实现。
 */
public final class HikerSelector {

    private HikerSelector() {}

    /** 上游 {@code XYQHiker.b}：可当作「恒等」处理的属性名（取属性时不再下钻）。 */
    public static final String[] IDENTITY_ATTRS = {"href", "src", "class", "title", "alt"};

    /** 上游 {@code getText} 里代表「字面量 null」的哨兵（不是 Java null，是字符串 "null"）。 */
    public static final String ANY_MARKER = "*";

    // ══════════════════════════════════════════════════════════════
    // 取值：pick / text / textByRule / chainPick
    // ══════════════════════════════════════════════════════════════

    /**
     * 从一个元素上按规则取<b>一个</b>值。
     *
     * <p>smali {@code XYQHiker.smali:961}（原 {@code p}）。
     *
     * <pre>
     * "Text"        → El.text()
     * "Html"        → El.html()   （不压缩换行）
     * "B64Dec"      → Base64 解码 El.text()
     * "AttrXxx"     → El.attr("Xxx")
     * "xxx"         → El.attr("xxx")
     * 上面任一 + "!a!b" → 结果里 replace 掉 a、b
     * </pre>
     *
     * @return 取不到返回空串（上游返回 {@code null}，见类注释缺陷 3）
     */
    public static String pick(Dom.El el, String rule) {
        if (el == null || rule == null) return "";
        try {
            String spec = rule;
            int js = spec.indexOf(".js:");
            if (js > 0) spec = spec.substring(0, js);

            String[] bang = spec.split("!");
            String head = bang[0];
            String v = source(el, head);
            if (!"Html".equals(head.trim())) v = v.replace("\n", " ");
            for (int i = 1; i < bang.length; i++) v = v.replace(bang[i], "");
            return v;
        } catch (Exception e) {
            return "";
        }
    }

    /** {@code source} 单步：Text / Html / B64Dec / AttrX / 属性名。 */
    private static String source(Dom.El el, String head) {
        String h = head == null ? "" : head.trim();
        if (h.isEmpty()) return el.text();
        if ("Text".equals(h)) return el.text();
        if ("Html".equals(h)) return el.html();
        if ("B64Dec".equals(h)) return base64Decode(el.text());
        if (h.startsWith("Attr")) return el.attr(h.substring("Attr".length()).trim());
        // 上游在这里无条件走 au(name)：既覆盖白名单属性，也覆盖任意属性名
        return el.attr(h);
    }

    /** Android {@code Base64.decode(s, 0)} 的桌面等价物（失败返回空串，不抛）。 */
    private static String base64Decode(String s) {
        try {
            byte[] raw = android.util.Base64.decode(s, 0);
            return new String(raw, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * {@code ||} 多规则回落取一个值。
     *
     * <p>smali {@code XYQHiker.smali:192}（原 {@code getText}）。
     * 规则为 {@code *}（{@link #ANY_MARKER}）时返回字符串 {@code "null"}（上游原样保留）。
     */
    public static String text(Dom.El el, String rule) {
        if (el == null || rule == null) return "";
        if (ANY_MARKER.equals(rule)) return "null";
        if (rule.contains("||")) {
            for (String part : rule.split("\\|\\|")) {
                String v = pick(el, part);
                if (v != null && !v.isEmpty()) return v;
            }
        }
        return pick(el, rule);
    }

    /**
     * {@code +} / {@code ＋} 多字段拼接。
     *
     * <p>smali {@code XYQHiker.smali:267}（原 {@code getTextByRule}）。
     * 规则里出现 {@code .js:} 或 {@code ＋} 时用全角分隔符，否则用半角 {@code +}。
     */
    public static String textByRule(Dom.El el, String rule) {
        if (el == null || rule == null || rule.isEmpty() || ANY_MARKER.equals(rule)) return "";
        if (!rule.contains(".js:") && !rule.contains("＋")) {
            return chainPick(el, rule, "\\+");
        }
        return chainPick(el, rule, "＋");
    }

    /**
     * 按分隔符切成若干「取值单元」，每单元用 {@code &&} 下钻后取一个值，最后拼起来。
     *
     * <p>smali {@code XYQHiker.smali:811}（原 {@code o}）。
     *
     * <p>{@code &&} 链的语义：{@code A&&B&&C} 表示先在 el 上找 A，
     * 再在 A 的结果上找 B，最后在 B 的结果上取 C（前 n-1 个是元素选择器，最后一个才是取值）。
     * 项数 == 1 时直接在 el 上取。
     */
    public static String chainPick(Dom.El el, String rule, String sepRegex) {
        if (el == null || rule == null) return "";
        String[] parts = rule.split(sepRegex);
        List<String> out = new ArrayList<String>();
        for (String part : parts) {
            String p = part.trim();
            if (isQuoted(p)) {
                out.add(p.substring(1, p.length() - 1).replace("\\n", "\n"));
                continue;
            }
            String[] chain = p.split("&&");
            Dom.El cur = chain.length == 1 ? el : trueElement(chain[0].trim(), el);
            for (int i = 1; i < chain.length - 1; i++) {
                if (cur == null) break;
                cur = trueElement(chain[i].trim(), cur);
            }
            out.add(cur == null ? "" : text(cur, chain[chain.length - 1].trim()));
        }
        return join(out, "");
    }

    private static boolean isQuoted(String s) {
        return (s.startsWith("'") && s.endsWith("'") && s.length() >= 2)
                || (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2);
    }

    // ══════════════════════════════════════════════════════════════
    // 选择：trueElement / selectSlice / selectElements
    // ══════════════════════════════════════════════════════════════

    /**
     * 规则 → 单个元素。
     *
     * <p>smali {@code XYQHiker.smali:334}（原 {@code getTrueElement}）。
     *
     * <p>分支优先级（与上游一致，顺序不可调换）：
     * <ol>
     *   <li>{@code Text*} / {@code Attr*} 前缀 → 恒等于入参元素</li>
     *   <li>属性白名单 {@code href|src|class|title|alt} → 恒等</li>
     *   <li>{@code a--b--c} → 从 a 的 outerHtml 里剔掉 b、c 的 outerHtml 后重新解析</li>
     *   <li>{@code a||b} → 取第一个能命中的</li>
     *   <li>{@code sel,index} → 集合里的第 index 个（负数从尾部数）</li>
     *   <li>其他 → 直接当 CSS 选择器</li>
     * </ol>
     *
     * @return 命中不到返回 {@code null}（上游语义，{@link #chainPick} 依赖它）
     */
    public static Dom.El trueElement(String rule, Dom.El el) {
        if (el == null || rule == null) return null;
        String r = rule.trim();
        if (r.isEmpty()) return null;

        if (r.startsWith("Text") || r.startsWith("Attr")) return el;
        for (String a : IDENTITY_ATTRS) if (a.equals(r)) return el;

        if (r.contains("--")) {
            String[] seg = r.split("--");
            Dom.El base = trueElement(seg[0].trim(), el);
            if (base == null) return null;
            String html = base.outerHtml();
            for (int i = 1; i < seg.length; i++) {
                Dom.El sub = trueElement(seg[i].trim(), base);
                if (sub != null) html = html.replace(sub.outerHtml(), "");
            }
            return Dom.parse(html);
        }

        if (r.contains("||")) {
            for (String alt : r.split("\\|\\|")) {
                Dom.El e = trueElement(alt.trim(), el);
                if (e != null) return e;
            }
            return null;
        }

        int comma = indexOfTopLevelComma(r);
        if (comma > 0) {
            String sel = r.substring(0, comma).trim();
            String idxTxt = r.substring(comma + 1).trim();
            int idx;
            try {
                idx = Integer.parseInt(idxTxt);
            } catch (NumberFormatException e) {
                return null;
            }
            List<Dom.El> list = el.select(sel);
            if (list.isEmpty()) return null;
            int at = idx < 0 ? list.size() + idx : idx;
            if (at < 0 || at >= list.size()) return null;
            return list.get(at);
        }

        List<Dom.El> list = el.select(r);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 无匹配时返回空元素而不是 {@code null}（新代码用；上游没有这个变体）。 */
    public static Dom.El trueElementOrEmpty(String rule, Dom.El el) {
        Dom.El e = trueElement(rule, el);
        return e == null ? Dom.parse("", "") : e;
    }

    /** 只在最外层找逗号（避免把 {@code a[href=x,y]} 里的逗号当切片分隔）。 */
    private static int indexOfTopLevelComma(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[' || c == '(') depth++;
            else if (c == ']' || c == ')') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }

    /**
     * {@code sel} 或 {@code sel,start:end} 切片。
     *
     * <p>smali {@code XYQHiker.smali:1221}（原 {@code q}）。
     * {@code end} 为 0 或负数时表示「从尾部倒数」；越界自动夹到集合长度。
     */
    public static List<Dom.El> selectSlice(Dom.El el, String rule) {
        List<Dom.El> out = new ArrayList<Dom.El>();
        if (el == null || rule == null || rule.trim().isEmpty()) return out;
        String r = rule.trim();
        int comma = indexOfTopLevelComma(r);
        if (comma < 0) return el.select(r);

        String sel = r.substring(0, comma).trim();
        String[] range = r.substring(comma + 1).split(":", -1);
        int from = 0, to = 0;
        if (range.length > 0 && !range[0].trim().isEmpty()) {
            from = parseIntSafe(range[0].trim(), 0);
        }
        if (range.length > 1 && !range[1].trim().isEmpty()) {
            to = parseIntSafe(range[1].trim(), 0);
        }
        List<Dom.El> all = el.select(sel);
        if (to > all.size()) to = all.size();
        if (to <= 0) to += all.size();
        for (int i = from; i < to && i < all.size(); i++) out.add(all.get(i));
        return out;
    }

    /**
     * {@code ||} 并集：多条规则的点集合并（重复元素不额外去重，与上游一致）。
     *
     * <p>smali {@code XYQHiker.smali:1360}（原 {@code selectElements}）。
     */
    public static List<Dom.El> selectElements(Dom.El el, String rule) {
        List<Dom.El> out = new ArrayList<Dom.El>();
        if (el == null || rule == null) return out;
        for (String part : rule.split("\\|\\|")) {
            try {
                out.addAll(selectSlice(el, part.trim()));
            } catch (Exception e) {
                // 上游是 e.printStackTrace() 后继续：一条规则坏掉不影响其它规则
            }
        }
        return out;
    }

    /**
     * {@code &&} 链式下钻后再做集合选择。
     *
     * <p>这是 {@code homeVideoContent}/{@code t}/{@code s} 里反复出现的
     * 「数组规则」求值范式：{@code A&&B&&C} 表示 A→B 逐级下钻，最后一级做集合选择。
     * 上游把这段 7 行的循环在 3 个方法里抄了 6 遍，本类收敛成一处。
     */
    public static List<Dom.El> selectChained(Dom.El root, String arrayRule) {
        if (root == null || arrayRule == null || arrayRule.trim().isEmpty()) {
            return new ArrayList<Dom.El>();
        }
        String[] chain = arrayRule.split("&&");
        if (chain.length == 1) return selectElements(root, chain[0].trim());
        Dom.El cur = trueElement(chain[0].trim(), root);
        for (int i = 1; i < chain.length - 1; i++) {
            if (cur == null) return new ArrayList<Dom.El>();
            cur = trueElement(chain[i].trim(), cur);
        }
        if (cur == null) return new ArrayList<Dom.El>();
        return selectElements(cur, chain[chain.length - 1].trim());
    }

    // ══════════════════════════════════════════════════════════════
    // 文本工具
    // ══════════════════════════════════════════════════════════════

    /**
     * 拼接。0 项返回空串，1 项原样返回（不追加分隔符）。
     *
     * <p>smali {@code XYQHiker.smali:569}（原 {@code listToString}）。
     */
    public static String join(List<String> list, String sep) {
        if (list == null || list.isEmpty()) return "";
        if (list.size() == 1) return list.get(0);
        StringBuilder sb = new StringBuilder(list.get(0));
        for (int i = 1; i < list.size(); i++) sb.append(sep).append(list.get(i));
        return sb.toString();
    }

    /**
     * Unicode 转义还原（反斜杠 + u + 4 位十六进制）—— 有的站把中文写成 JS 转义。
     *
     * <p>smali {@code XYQHiker.smali:660}（原 {@code m}）。
     * 上游用「边 find 边 replace 同一个 String」的写法，遇到双反斜杠开头的转义序列
     * 会被二次替换。本实现按一次扫描处理，结果更稳定。
     */
    public static String unescapeUnicode(String s) {
        if (s == null || s.indexOf("\\u") < 0) return s == null ? "" : s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 5 < s.length() + 0 && i + 6 <= s.length()
                    && s.charAt(i + 1) == 'u') {
                String hex = s.substring(i + 2, i + 6);
                if (isHex4(hex)) {
                    sb.append((char) Integer.parseInt(hex, 16));
                    i += 5;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isHex4(String h) {
        if (h.length() != 4) return false;
        for (int i = 0; i < 4; i++) {
            char c = h.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    /**
     * 年份列表：{@code 今年&去年&…&20 年前}。
     *
     * <p>smali {@code XYQHiker.smali:724}（原 {@code n}）。
     * 上游那段 {@code if (i > i2) { i2 = i; i = i2; }} 是恒等操作（把 i2 赋成 i 再赋回），
     * 且循环条件 {@code while (i2 >= i)} 在 i==i2 时只跑一轮 —— 结果永远是「今年」一个数。
     * 本实现修复为真正的 21 年列表（今年 → 20 年前），并把年份来源抽成参数以便测试。
     *
     * @param thisYear 今年（测试注入用；真实调用见 {@link #yearList()}）
     */
    public static String yearList(int thisYear) {
        StringBuilder sb = new StringBuilder();
        for (int y = thisYear; y >= thisYear - 20; y--) {
            if (y != thisYear) sb.append('&');
            sb.append(y);
        }
        return sb.toString();
    }

    /** 用当前年份调用 {@link #yearList(int)}。 */
    public static String yearList() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return yearList(c.get(java.util.Calendar.YEAR));
    }

    /**
     * 字符 → 数字码串（上游用来给验证码做一次性变换）。
     *
     * <p>smali {@code XYQHiker.smali:1407}（原 {@code string2Hex}）。
     * {@code mode} 为 {@code "djs"} 时输出 ASCII 码原值，否则每码 +1。
     */
    public static String string2Hex(String s, String mode) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            int code = s.charAt(i);
            sb.append("djs".equals(mode) ? code : code + 1);
        }
        return sb.toString();
    }

    /**
     * 粗清理：{@code &nbsp;} → 空格、去实体、去标签、去残角括号、压缩连续空白。
     *
     * <p>smali {@code XYQHiker.smali:3388}（原 {@code ag}）。
     * 注意顺序与上游一致，且上游的 {@code [(/>)<]} 会把正文里的 {@code /} 也删掉 ——
     * 这是上游行为，本实现保留（并在文档里标注为已知副作用）。
     */
    public static String stripTags(String s) {
        if (s == null) return "";
        return s.replaceAll("\\&nbsp;", " ")
                .replaceAll("\\&[a-zA-Z]{1,10};", "")
                .replaceAll("<[^>]*>", "")
                .replaceAll("[(/>)<]", "")
                .replaceAll("\\s{2,}", "");
    }

    // ══════════════════════════════════════════════════════════════
    // 正则工具（上游 {@code wh.g} / {@code wh.q} / {@code wh.n}）
    // ══════════════════════════════════════════════════════════════

    /**
     * 正则取第 1 分组；无匹配返回空串。
     *
     * <blockquote>
     * <b>⚠️ 勘误：不要按"正则取分组"改这个方法。</b>
     * <p>早期实现（以及任务书）把上游 {@code wh.g(baseUrl, path)} 记成「正则取第 1 分组」。
     * 逐指令核对 {@code merge/A0/wh}（{@code ae/decompiled/merge/A0/wh.java:141-215}）后确认
     * <b>{@code wh.g} 是 URL 绝对化</b>：
     * <ul>
     *   <li>{@code //host/p} → 补上 base 的 scheme</li>
     *   <li>{@code mac://…} → {@code scheme://…}</li>
     *   <li>不含 {@code ://} → 拼 {@code scheme://host} + path</li>
     *   <li>异常 → 返回 path 原值（不抛）</li>
     * </ul>
     * 旁证：{@code ae/decompiled/XYQHiker.java} 里
     * {@code :497}、{@code :506}、{@code :614}、{@code :642}、{@code :656}、{@code :667}、
     * {@code :685} <b>共 7 处</b>调用都是 {@code wh.g(页面URL, 相对路径)}，且返回值直接当请求 URL 用
     * —— 正则取分组不可能有这种用法。
     * <p>因此 {@link HikerExtractor#absolutize} 才是它的等价实现，
     * 而本方法（{@code firstMatch}）对应的是 {@code wh} 的另一个成员，
     * 只用于 {@code MacPlayerConfig.player_list} 这类一次性抠取。
     * <p>所以：<b>如果有人按旧注释把 {@code absolutize} 当成"取分组"去改，会静默产出错误 URL</b>
     * —— 不抛异常、大部分断言仍绿，只有 {@code picOf 绝对化} 这类断言会红。
     * 错误注释比没有注释更危险，故留此显式留痕。
     * </blockquote>
     */
    public static String firstMatch(String src, String regex) {
        if (src == null || regex == null) return "";
        try {
            Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(src);
            if (m.find()) return m.group(m.groupCount() >= 1 ? 1 : 0);
        } catch (Exception e) {
            // 正则非法 → 空串
        }
        return "";
    }

    /**
     * 取 {@code from} 与 {@code to} 之间的全部片段。
     *
     * <p>对应上游 {@code wh.q}。上游返回 {@code List} 且调用方一律 {@code get(0)}，
     * 无匹配时直接 {@code IndexOutOfBoundsException}（见类注释缺陷 1）。
     */
    public static List<String> betweenList(String src, String from, String to) {
        List<String> out = new ArrayList<String>();
        if (src == null || from == null || to == null || from.isEmpty()) return out;
        try {
            String re = Pattern.quote(from) + "(.*?)" + Pattern.quote(to);
            Matcher m = Pattern.compile(re, Pattern.DOTALL).matcher(src);
            while (m.find()) out.add(m.group(1));
        } catch (Exception e) {
            // 忽略
        }
        return out;
    }

    /** 取 {@code from}/{@code to} 之间的第一段；无匹配返回空串（缺陷 1 的修复版）。 */
    public static String between(String src, String from, String to) {
        List<String> l = betweenList(src, from, to);
        return l.isEmpty() ? "" : l.get(0);
    }

    /**
     * {@code [替换:正则=>替换文本]} 语法。
     *
     * <p>对应上游 {@code wh.n}。上游支持
     * {@code [替换:/a/b/]} 与 {@code [替换:正则=>文本]} 两种写法，
     * 这里按实测的 {@code =>} 形态实现，另外兼容 {@code /…/…/} 形态。
     */
    public static String regexReplace(String src, String spec) {
        if (src == null || spec == null) return "";
        try {
            String body = spec;
            int i = body.indexOf("[替换:");
            if (i >= 0) body = body.substring(i + "[替换:".length());
            if (body.endsWith("]")) body = body.substring(0, body.length() - 1);
            int arrow = body.indexOf("=>");
            if (arrow >= 0) {
                String re = body.substring(0, arrow);
                String rep = body.substring(arrow + 2);
                return src.replaceAll(re, rep);
            }
            if (body.length() >= 3 && body.startsWith("/")) {
                int second = body.indexOf('/', 1);
                int third = body.lastIndexOf('/');
                if (second > 0 && third > second) {
                    return src.replaceAll(body.substring(1, second), body.substring(second + 1, third));
                }
            }
        } catch (Exception e) {
            // 正则或替换串非法 → 原样返回
        }
        return src;
    }

    /** {@code [不包含:xxx]}：把不包含过滤词的元素丢掉。 */
    public static boolean containsExcluded(String value, List<String> excludes) {
        if (value == null || excludes == null) return false;
        for (String ex : excludes) if (ex != null && !ex.isEmpty() && value.contains(ex)) return true;
        return false;
    }

    /** {@code [排序:xxx]}：按出现顺序重排（上游用 {@code split} 逐段排序）。 */
    public static List<String> reorder(List<String> items, String orderSpec) {
        if (items == null || orderSpec == null || orderSpec.isEmpty()) return items;
        List<String> out = new ArrayList<String>();
        String[] keys = orderSpec.split(",");
        for (String k : keys) {
            String want = k.trim();
            for (String it : items) {
                if (it != null && it.equals(want) && !out.contains(it)) out.add(it);
            }
        }
        for (String it : items) if (!out.contains(it)) out.add(it);
        return out;
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 供测试与调试：把规则拆成可读片段。 */
    public static Map<String, String> describe(String rule) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("rule", rule == null ? "" : rule);
        m.put("alternatives", rule == null ? "" : String.valueOf(rule.split("\\|\\|").length));
        m.put("chainSteps", rule == null ? "" : String.valueOf(rule.split("&&").length));
        m.put("hasAttrPrefix", String.valueOf(rule != null && rule.contains("Attr")));
        m.put("isText", String.valueOf("Text".equals(rule)));
        m.put("isHtml", String.valueOf("Html".equals(rule)));
        return m;
    }
}
