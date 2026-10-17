package com.example.tvbox.plain.appys;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AppYs 族共用的「播放地址判定/解析」策略。
 *
 * <p>上游这一族把三件事外包给混淆库 {@code com.github.catvod.spider.merge.A0.ku}：
 * {@code ku.e}（是不是直链）、{@code ku.f}（是不是主流站）、{@code ku.g}（把解析接口的响应
 * 规范化成播放对象）。这三个方法的 <b>字节码原文</b>已通过 REA 取回并逐字复刻到这里。
 *
 * <h2>证据（REA / JADX headless 0.7.1，artifact {@code ae/target/spider_probe.apk}）</h2>
 * <table border="1">
 *   <tr><th>上游</th><th>Evidence ID</th><th>结论</th></tr>
 *   <tr><td>{@code ku.<clinit>}</td><td>{@code ev_695cc14e5a19fd04dd19e73800c2ef87be404c84c4e564907b1568fb0cbaa0f2}</td>
 *       <td>{@code a} = 12 个扩展名/MIME 片段；{@code b} = {@code {"=http",".html","?http"}}</td></tr>
 *   <tr><td>{@code ku.e}</td><td>{@code ev_1e4de54b24678d56de7ecf3b959f68b0766c82423a0c55bc244966471f049fca}</td>
 *       <td>直链判定（见 {@link #isDirect}）</td></tr>
 *   <tr><td>{@code ku.f}</td><td>{@code ev_02810f7516ac144202634d4ed3eda0b8c19f6957affaa6e9ecc204903498616a}</td>
 *       <td>16 个主流站 host（见 {@link #isMainstream}）</td></tr>
 *   <tr><td>{@code ku.g}</td><td>{@code ev_c88730fb1c0272256f3cec95f1c5f100100d1969cac2a8065ba68b1ae23bab7f}</td>
 *       <td>解析响应规范化（见 {@link #resolve}）</td></tr>
 * </table>
 *
 * <p>注意 {@code ku.e} 的原始表达式是
 * {@code (!contains("=http") && !contains(".html")) || (contains("m3u8.pw/Cache") && contains("from=https://banyung.pw"))}
 * —— 用 {@code &&} 与 {@code ||} 的优先级凑出来的判定，本类保持原样，不做「化简」。
 */
public final class AppYsVideo {

    /** 上游 {@code ku.a}（12 项，顺序照抄 —— {@code ku.e} 的循环只依赖 contains，顺序无影响但保持一致便于比对） */
    public static final String[] VIDEO_MARKS = {
            ".m3u8", ".mp4", ".flv", ".avi", ".mkv", ".rm", ".wmv", ".mpg",
            "video/tos", ".mp3", ".m4a", "mime_type=video_mp4"
    };

    /** 上游 {@code ku.b}（3 项）：命中即「不是直链」的排除标记 */
    public static final String[] NON_DIRECT_MARKS = {"=http", ".html", "?http"};

    /** 上游 {@code ku.f} 的 16 个主流站 host */
    public static final String[] MAINSTREAM_HOSTS = {
            "iqiyi.com", "v.qq.com", "youku.com", "le.com", "tudou.com", "mgtv.com",
            "sohu.com", "acfun.cn", "bilibili.com", "baofeng.com", "pptv.com",
            "www.miguvideo.com", "www.ixigua.com", "vip.1905.com", "www.fun.tv", "m.fun.tv"
    };

    /**
     * 上游 {@code ku.e} 里那个「豁免标记」的<b>小写</b>形式。
     *
     * <p>上游原文是 {@code lowerCase.contains("m3u8.pw/Cache")} —— 拿一个含大写 {@code C}
     * 的字面量去匹配<b>已经小写化</b>的串，永远为 false（见 {@link #UPSTREAM_EXEMPT_LITERAL}）。
     * 本实现按作者显然的意图换成小写字面量，并在测试里同时钉住「上游写法恒不成立」这一事实。
     */
    public static final String EXEMPT_CACHE = "m3u8.pw/cache";
    /** 豁免还需要「来源是 banyung.pw」 */
    public static final String EXEMPT_FROM = "from=https://banyung.pw";
    /** 以及第三个更严的条件：地址里必须出现 {@code getm3u8?url=http} */
    public static final String EXEMPT_GET = "getm3u8?url=http";
    /** 上游写死的字面量（含大写 C）—— 保留用于「这是死代码」的断言 */
    public static final String UPSTREAM_EXEMPT_LITERAL = "m3u8.pw/Cache";

    /** 上游 {@code ku.g} 里两处「给你换一个解析接口」的蜘蛛 */
    public static final String PARSE_API_PLACEHOLDER = "jpg.hou.lu/jm/za/index.php";
    public static final String PARSE_API_REPLACEMENT =
            "http://vip.mengx.vip/home/api?type=ys&uid=3249696&key=aefqrtuwxyEFHKNOQY&url=";

    private static final Pattern AUTHORITY = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*://([^/?#]+)");

    private AppYsVideo() {
    }

    /**
     * 上游 {@code ku.e}：这个地址是不是「可以直接播」的直链。
     *
     * <p>复刻要点（三处都照抄上游）：
     * <ol>
     *   <li>整体小写后判定；</li>
     *   <li>第一层：不含 {@code =http} 且不含 {@code .html} 才继续 —— 但
     *       {@link #EXEMPT_CACHE} + {@link #EXEMPT_FROM} 的组合可以豁免；</li>
     *   <li>第二层：命中 {@link #VIDEO_MARKS} 之一；若同时命中
     *       {@link #NON_DIRECT_MARKS} 之一，还要再看三个豁免标记（含
     *       {@link #EXEMPT_GET}）是否齐全。</li>
     * </ol>
     *
     * <p><b>已修的上游缺陷</b>：第一层与第二层都比对的字面量写成了 {@code "m3u8.pw/Cache"}
     * （大写 {@code C}），而左值是 {@code str.toLowerCase()} —— 该分支恒为 false，
     * 豁免逻辑整段是死代码。本实现改用 {@link #EXEMPT_CACHE}（小写）。
     * 连带的语义变化：{@code m3u8.pw/Cache} + {@code from=https://banyung.pw} 的地址
     * 因为含 {@code =http}（{@code from=https…}）而本该走豁免，上游会判成「非直链」，
     * 本实现按作者意图判成直链。
     *
     * @param url 地址，null 视为空串
     * @return true 表示直链
     */
    public static boolean isDirect(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        boolean exempt = lower.contains(EXEMPT_CACHE) && lower.contains(EXEMPT_FROM);
        if ((!lower.contains("=http") && !lower.contains(".html")) || exempt) {
            for (int i = 0; i < 12; i++) {
                if (lower.contains(VIDEO_MARKS[i])) {
                    for (int j = 0; j < 3; j++) {
                        if (lower.contains(NON_DIRECT_MARKS[j])
                                && (!lower.contains(EXEMPT_CACHE)
                                    || !lower.contains(EXEMPT_FROM)
                                    || !lower.contains(EXEMPT_GET))) {
                            return false;
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 上游 {@code ku.f}：是不是优酷/腾讯/爱奇艺这类「需要解析」的主流站。
     *
     * <p>上游用 {@code Uri.parse(url).getHost()}；无 scheme 时 Android 返回 null，
     * 随后 {@code null.contains(...)} 抛 NPE 被 catch 掉 → {@code false}。
     * 本实现用正则取 authority，无 scheme 时 authority 为空串，
     * {@code "".contains(x)} 恒 false —— 与上游等价，只是不用抛异常。
     *
     * <p>{@code iqiyi.com} 一项有额外条件：必须匹配 {@code /a_} / {@code /w_} / {@code /v_} 之一，
     * 否则首页域名也会被判成「片子地址」。
     *
     * @param url 地址，null 视为空串
     * @return true 表示主流站
     */
    public static boolean isMainstream(String url) {
        String s = url == null ? "" : url;
        String host = host(s);
        for (int i = 0; i < 16; i++) {
            if (host.contains(MAINSTREAM_HOSTS[i])
                    && (!"iqiyi.com".equals(MAINSTREAM_HOSTS[i])
                        || s.contains("iqiyi.com/a_")
                        || s.contains("iqiyi.com/w_")
                        || s.contains("iqiyi.com/v_"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取 URL 的 authority（小写，去端口）。等价 Android {@code Uri.getHost()}，无 scheme 时返回空串。
     *
     * @param url 地址
     * @return authority；无法解析返回空串
     */
    public static String host(String url) {
        if (url == null) {
            return "";
        }
        Matcher m = AUTHORITY.matcher(url.trim());
        if (!m.find()) {
            return "";
        }
        String auth = m.group(1);
        // 顺序要紧：Android Uri.getHost() 先丢掉 userinfo 再丢端口。
        // 反过来会把 "u:p@a.com" 的端口分隔符误认成 userinfo 里的那个冒号。
        int at = auth.lastIndexOf('@');
        if (at >= 0) {
            auth = auth.substring(at + 1);
        }
        if (auth.startsWith("[")) {
            int close = auth.indexOf(']');
            auth = close > 0 ? auth.substring(0, close) : auth;
        } else {
            int colon = auth.indexOf(':');
            if (colon >= 0) {
                auth = auth.substring(0, colon);
            }
        }
        return auth.toLowerCase(Locale.ROOT);
    }

    /**
     * 上游 {@code ku.g}：把「解析接口返回的 JSON」规范化成播放对象。
     *
     * <p>上游原文的判定顺序（逐字复刻）：
     * <ol>
     *   <li>{@code data.url} 优先，否则 {@code url}；{@code //} 开头补 {@code https:}；</li>
     *   <li>补完仍不以 {@code http} 开头 → {@code null}；</li>
     *   <li>「解出来的还是原地址」且（是主流站 或 不是直链）→ {@code null}；</li>
     *   <li>含 {@code 973973.xyz} 或 {@code .fit:} → {@code null}；</li>
     *   <li>透传 {@code user-agent}/{@code referer} 两个头（上游在值<em>前面</em>拼一个空格，保持原样）；</li>
     *   <li>mgtv / bilibili 覆盖成固定头。</li>
     * </ol>
     *
     * @param rawUrl   原始（加密或解析页）地址，用于「解出来还是自己」的判定
     * @param playResp 解析接口响应体
     * @return {@code {"header":{...},"url":"..."}}；不满足条件返回 null
     */
    public static JSONObject resolve(String rawUrl, String playResp) {
        if (playResp == null) {
            return null;
        }
        JSONObject root;
        try {
            root = new JSONObject(playResp);
        } catch (Throwable e) {
            return null;
        }
        String url;
        try {
            url = root.has("data") ? root.getJSONObject("data").getString("url") : root.getString("url");
        } catch (Throwable e) {
            return null;
        }
        if (url == null) {
            return null;
        }
        if (url.startsWith("//")) {
            url = "https:" + url;
        }
        if (!url.startsWith("http")) {
            return null;
        }
        if ((url.equals(rawUrl) && (isMainstream(url) || !isDirect(url)))
                || url.contains("973973.xyz")
                || url.contains(".fit:")) {
            return null;
        }
        try {
            JSONObject header = new JSONObject();
            String ua = root.optString("user-agent", "");
            if (ua.trim().length() > 0) {
                header.put("User-Agent", " " + ua);
            }
            String referer = root.optString("referer", "");
            if (referer.trim().length() > 0) {
                header.put("Referer", " " + referer);
            }
            String src = rawUrl == null ? "" : rawUrl;
            if (src.contains("www.mgtv.com") || url.contains("titan.mgtv")) {
                header.put("Referer", " ");
                header.put("User-Agent", " Mozilla/5.0");
            } else if (src.contains("bilibili")) {
                header.put("Referer", " https://www.bilibili.com/");
                header.put("User-Agent", " Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                        + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36");
            }
            JSONObject out = new JSONObject();
            out.put("header", header);
            out.put("url", url);
            return out;
        } catch (Throwable e) {
            return null;
        }
    }
}
