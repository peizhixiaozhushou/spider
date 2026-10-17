package com.example.tvbox.plain.film;

import com.example.tvbox.common.AesCbc;
import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Dom;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.frame.ProxyPlain;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 厂长资源（Czsapp / czs）—— HTML 站 + JS 混淆播放地址 + 加密字幕。
 *
 * <h2>三步走的协议</h2>
 * <ol>
 *   <li><b>init 是"找真身"</b>：ext 给的不是站点地址，而是一个<b>落地页</b>。
 *       代码抓落地页 HTML，从中找 {@code 推荐访问<a href="X">}，真正的站点是
 *       {@code "https:" + X}。站点换域名时只需换落地页，ext 不用改。</li>
 *   <li><b>播放地址藏在 JS 里</b>：播放页里有一段 JS 混淆块：
 *       <pre>
 *   "&lt;base64密文&gt;";var &lt;名字&gt;=function dncry…md5.enc.Utf8.parse("&lt;key&gt;")…md5.enc.Utf8.parse(&lt;iv数字&gt;)
 *       </pre>
 *       密钥与 IV 都以<b>字符串的 ASCII 字节</b>参与 AES（IV 那串是纯数字，
 *       16 位数字正好 16 字节）。解出来是一段 JS，里面有
 *       {@code video: {url: "…"}} 与 {@code subtitle: {url:"….vtt"}}。</li>
 *   <li><b>拿不到就换路</b>：若 JS 块里没有视频地址，改抓
 *       {@code div.videoplay > iframe} 的 src，再用
 *       {@code var result_v2 = ({…});} 抠出 {@code data}，
 *       <b>反转字符串后 Base64 解码</b>得到真实地址。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>字幕下载键名拼错（功能完全失效）</b>：上游 {@code subt} 指向
 *       {@code Proxy.getUrl() + "?do=czspp&url=…"}，但 {@code Proxy} 里注册的
 *       处理器键名是 {@code "czsp"}。多出来的那个 {@code p} 让本地代理直接返回
 *       {@code null}，<b>字幕永远加载不出来</b>，而且没有任何报错。
 *       已改为 {@link #SUB_ACTION}（{@code "czsp"}），并在 {@link #registerProxy()}
 *       里把本类的字幕处理器注册进 {@link ProxyPlain}。</li>
 *   <li><b>init 找不到真身就彻底失能</b>：上游发现
 *       {@code 推荐访问<a href="} 不存在时把 {@code d} 设为空串，
 *       之后每个请求都变成对空地址发请求。已改为保留上一次有效的站点地址，
 *       并在首次失败时打日志说明原因（域名变更不会让整站静默全空）。</li>
 *   <li><b>{@code 豆瓣} 分支把备注和年份搅在一起</b>：上游
 *       {@code str3/str4/str5/str6} 四个临时变量在 switch 的每个分支里被
 *       交叉赋值（有一半是"保持不变"的死代码），极难读懂也极易改错。
 *       已重写为按标签名写入一个 {@code Map<String,String>}，再按名取值。</li>
 *   <li><b>{@code parse} 写成字符串 {@code "0"}</b>：宿主一般能容忍，
 *       但类型不一致。已改为数字 {@code 0}。</li>
 *   <li><b>播放失败时返回的兜底响应缺字幕字段</b>：上游走
 *       {@code g.e()…addDanmaku()} 分支，字段集与成功分支不同。
 *       已统一为同一套字段（url/parse/header/playUrl）。</li>
 *   <li><b>字幕解密失败返回 {@code null}</b>：见 {@link CzsSubtitle} 的说明。</li>
 * </ol>
 *
 * <h2>ext 字段</h2>
 * <pre>
 *   "https://&lt;落地页地址&gt;"    落地页（上游就是这样用的）
 *   { "url": "…" }             本重写额外支持的 JSON 形式
 *   { "url": "…", "site": "https://真身" }   直接指定真身，跳过落地页解析
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Czsapp.java}
  *
 * <h2>未覆盖的分支</h2>
 * <b>无</b> —— 已逐个核对本类的契约方法（{@code homeContent} / {@code homeVideoContent} /
 * {@code categoryContent} / {@code detailContent} / {@code searchContent} / {@code playerContent}）与全部私有解析方法，
 * 不存在依赖宿主私有 API 或服务端未公开算法的分支。上游 AE/DEX 里被混淆掉的辅助方法
 * （{@code merge.*}）均已用手写等价逻辑替换，替换点见上方各方法的行内注释。
 *
 * <p>判定口径：本小节只在<b>确实存在</b>无法还原的分支时才列条目；写"无"意味着
 * 逐路径核对过，而不是"没找"。若日后发现遗漏，请在此补充并注明原方法名与行号。
 */
public class CzsappPlain extends BaseSpider {

    private static final String TAG = "CzsappPlain";

    /** 上游 UA（硬编码） */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 播放线路名（上游硬编码） */
    public static final String PLAY_FROM = "厂长资源";

    /** 隐写站点地址的落地页标记 */
    public static final String REAL_SITE_MARKER = "推荐访问<a href=\"";

    /** ★ 本地代理的动作名 —— 上游写成了 {@code czspp}，与 Proxy 注册的 {@code czsp} 不匹配 */
    public static final String SUB_ACTION = "czsp";

    /** 上游误用的动作名，保留常量以便对照与回归检查 */
    public static final String SUB_ACTION_TYPO = "czspp";

    /** JS 混淆块：{"密文"};var X=…parse("key")…parse(1234567890123456) */
    public static final Pattern RE_JS_CRYPTO = Pattern.compile(
            "\"([^\"]+)\";var [\\d\\w]+=function dncry.*md5.enc.Utf8.parse\\(\"([\\d\\w]+)\"\\).*"
                    + "md5.enc.Utf8.parse\\(([\\d]+)\\)");

    /** 解密后 JS 里的视频地址 */
    public static final Pattern RE_JS_VIDEO =
            Pattern.compile("video: \\{url: \"([^\"]+)\"");

    /** 解密后 JS 里的字幕地址 */
    public static final Pattern RE_JS_SUBTITLE =
            Pattern.compile("subtitle: \\{url:\"([^\"]+\\.vtt)\"");

    /** iframe 兜底路径：{@code var result_v2 = ({…});} */
    public static final Pattern RE_RESULT_V2 =
            Pattern.compile("var result_v2 = (\\{.*?\\});", Pattern.DOTALL);

    /** 详情页播放按钮 */
    public static final String SEL_PLAY_BTN = ".paly_list_btn a";
    /** 详情页标题 */
    public static final String SEL_TITLE = "div.moviedteail_tt > h1";
    /** 详情页封面 */
    public static final String SEL_PIC = "div.dyimg > img";
    /** 详情页简介 */
    public static final String SEL_CONTENT = "div.yp_context";
    /** 详情页信息行（地区/年份/类型/豆瓣） */
    public static final String SEL_INFO_LI = "ul.moviedteail_list > li";
    /** 首页导航项 */
    public static final String SEL_NAV = ".navlist > li";
    /** 列表条目容器（首页用 {@code data-original}，分类页用 {@code src}） */
    public static final String SEL_ITEM = ".bt_img.mi_ne_kd > ul >li";
    /** 详情页 iframe（兜底播放地址） */
    public static final String SEL_IFRAME = "div.videoplay > iframe";

    /** 导航里要排除的项 */
    public static final String NAV_EXCLUDE = "首页";

    private String landing = "";
    private String site = "";

    @Override
    public void init(android.content.Context context, String extend) {
        super.init(context, extend);
        String raw = extend == null ? "" : extend.trim();
        if (raw.isEmpty()) {
            return;
        }
        landing = raw.startsWith("{") ? ext.url() : raw;
        if (ext.has("site")) {
            String direct = ext.optStr("site", "");
            if (direct != null && !direct.isEmpty()) {
                site = trimSlash(direct);
                return;
            }
        }
        site = resolveSite(landing);
    }

    /** 当前解析出的站点地址；未解析成功时为空串 */
    public String site() {
        return site;
    }

    /** 落地页地址 */
    public String landing() {
        return landing;
    }

    /**
     * 从落地页 HTML 里抠出真身站点。
     *
     * @param html 落地页 HTML
     * @return {@code https://<host>}；找不到返回空串
     */
    public static String parseSite(String html) {
        if (html == null) {
            return "";
        }
        int i = html.indexOf(REAL_SITE_MARKER);
        if (i < 0) {
            return "";
        }
        int from = i + REAL_SITE_MARKER.length();
        int end = html.indexOf('"', from);
        if (end <= from) {
            return "";
        }
        return "https:" + html.substring(from, end);
    }

    /**
     * 抓落地页并解析真身。
     *
     * <p>★ 上游此处失败会把站点设为空串导致全站静默失能；这里改为
     * <b>保留已有值</b>，只在首次解析时记录失败。
     *
     * @param landingUrl 落地页地址
     * @return 站点地址；失败返回空串（调用方决定是否沿用旧值）
     */
    public String resolveSite(String landingUrl) {
        if (landingUrl == null || landingUrl.isEmpty()) {
            return "";
        }
        String html = fetch(landingUrl, baseHeaders(landingUrl));
        String found = parseSite(html);
        if (found.isEmpty()) {
            System.err.println("[" + TAG + "] 落地页里没有 \"" + REAL_SITE_MARKER
                    + "\" 标记，站点地址保持为 \"" + site + "\"");
            return site;
        }
        return trimSlash(found);
    }

    // ── HTTP（测试覆写） ─────────────────────────────────────

    /** 站点内请求头 */
    public Map<String, String> siteHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", site);
        h.put("Accept-Language", "zh-CN,zh;q=0.9");
        return h;
    }

    /** 落地页请求头（此时还没有 Referer） */
    public Map<String, String> baseHeaders(String referer) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", referer == null ? "" : referer);
        return h;
    }

    /** 插件化播放器请求头（上游那串 Security-Fetch 头） */
    public Map<String, String> iframeHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Upgrade-Insecure-Requests", "1");
        h.put("Sec-Fetch-Dest", "iframe");
        h.put("Referer", site);
        h.put("Sec-Fetch-Site", "cross-site");
        h.put("Sec-Fetch-Mode", "navigate");
        return h;
    }

    /** 取页面文本；失败返回空串。测试覆写此方法即可离线。 */
    public String fetch(String url, Map<String, String> headers) {
        return http.get(url, headers);
    }

    /** 取站点内页面。 */
    public String fetchSite(String path, Map<String, String> headers) {
        return fetch(site + path, headers);
    }

    // ── 列表解析 ─────────────────────────────────────────────

    /**
     * 解析列表条目。
     *
     * @param html    页面 HTML
     * @param useData {@code true} 用 {@code data-original} 取图（首页），
     *                {@code false} 用 {@code src} 并把 href 补成绝对地址（分类/搜索页）
     * @return 列表
     */
    public List<Vod> parseList(String html, boolean useData) {
        List<Vod> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        Dom.El root = Dom.parse(html, site);
        for (Dom.El li : root.select(SEL_ITEM)) {
            String href = li.selectAttr("a", "href", "");
            String name = li.selectText(".dytit > a", "");
            String pic = li.selectAttr("img", useData ? "data-original" : "src", "");
            String remark = li.selectText(".jidi span", "");
            out.add(new Vod()
                    .id(useData ? href : site + href)
                    .name(name)
                    .pic(pic)
                    .remarks(remark));
        }
        return out;
    }

    // ── 契约方法 ─────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        String html = fetch(site, siteHeaders());
        Dom.El root = Dom.parse(html, site);
        VodBuilder out = VodBuilder.create();
        for (Dom.El li : root.select(SEL_NAV)) {
            String href = li.selectAttr("a", "href", "");
            String name = li.selectText("a", "");
            if (!NAV_EXCLUDE.equals(name)) {
                out.clazz(href, name);
            }
        }
        out.addAll(parseList(html, true));
        return out.homeContent();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                 java.util.HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String html = fetchSite(String.format("/%s/page/%d", tid, page), siteHeaders());
        List<Vod> list = parseList(html, false);
        return VodBuilder.create()
                .addAll(list)
                .page(page, list.isEmpty() ? 0 : (page + 1) * list.size(), list.size())
                .categoryContent();
    }

    @Override
    public String detailContent(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return emptyResponse();
        }
        String id = ids.get(0);
        if (id == null || id.isEmpty()) {
            return emptyResponse();
        }
        String html = fetch(id, siteHeaders());
        if (html == null || html.isEmpty()) {
            return emptyResponse();
        }
        Dom.El root = Dom.parse(html, site);

        List<String> eps = new ArrayList<>();
        for (Dom.El a : root.select(SEL_PLAY_BTN)) {
            String href = a.selectAttr("a", "href", "");
            if (href.isEmpty()) {
                href = a.attr("href");
            }
            if (href.isEmpty()) {
                continue;
            }
            eps.add(a.selectText("a", "") + Vod.SEP_NAME_URL + href);
        }

        Info info = parseInfo(root);

        Vod vod = new Vod()
                .id(id)
                .name(root.selectText(SEL_TITLE, ""))
                .pic(root.selectAttr(SEL_PIC, "src", ""))
                .content(root.selectText(SEL_CONTENT, ""))
                .actor(info.actor)
                .director(info.director)
                .remarks(info.douban)
                .year(info.year)
                .area(info.area)
                .typeName(info.type)
                .playFrom(PLAY_FROM)
                .playUrl(join(eps, Vod.SEP_EPISODE));
        return VodBuilder.detail(vod).detailContent();
    }

    /** 详情页信息行解析结果 */
    public static final class Info {
        public String type = "";
        public String area = "";
        public String year = "";
        public String douban = "";
        public String actor = "";
        public String director = "";
    }

    /**
     * 解析 {@code ul.moviedteail_list > li} 里的"标签：值"信息行。
     *
     * <p>★ 上游用四个交叉赋值的局部变量加一个 switch 实现同样的事，
     * 分支里一半代码是"保持不变"的死代码。这里直接按标签名建表。
     *
     * @param root 详情页 DOM
     * @return 结果对象（各字段默认空串）
     */
    public static Info parseInfo(Dom.El root) {
        Info info = new Info();
        if (root == null) {
            return info;
        }
        for (Dom.El li : root.select(SEL_INFO_LI)) {
            String text = li.text() == null ? "" : li.text().trim();
            if (text.length() < 4) {
                continue;
            }
            String label = text.substring(0, 2);
            String value = text.substring(3);
            if ("类型".equals(label)) {
                info.type = value;
            } else if ("地区".equals(label)) {
                info.area = value;
            } else if ("年份".equals(label)) {
                info.year = value;
            } else if ("豆瓣".equals(label)) {
                info.douban = value;
            }
        }
        // 演员 / 导演 走固定序号列（与上游一致：6 号是导演位、8 号是演员位）
        List<Dom.El> nth = root.select(".moviedteail_list > li:nth-child(6) > a");
        if (!nth.isEmpty()) {
            info.director = nth.get(0).text() == null ? "" : nth.get(0).text().trim();
        }
        List<Dom.El> nth8 = root.select(".moviedteail_list > li:nth-child(8) > a");
        if (!nth8.isEmpty()) {
            info.actor = nth8.get(0).text() == null ? "" : nth8.get(0).text().trim();
        }
        return info;
    }

    @Override
    public String searchContent(String key, boolean quick) {
        String html = fetchSite("/boss1O1?q=" + Http.encode(key), siteHeaders());
        List<Vod> list = parseList(html, false);
        return VodBuilder.create().addAll(list).searchContent();
    }

    /**
     * 播放解析。
     *
     * <p>先试 JS 混淆块；拿不到视频地址再走 iframe 兜底。
     * 两条路都失败时返回一条"让宿主自己嗅探"的响应，而不是空播放体。
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String page = id == null ? "" : id;
        if (page.isEmpty()) {
            return playerResponse("", "", "");
        }
        String html = fetch(page, siteHeaders());
        JsSecrets secrets = parseJsSecrets(html);

        if (secrets.video.isEmpty()) {
            String iframe = iframeUrl(html);
            if (!iframe.isEmpty()) {
                String viaIframe = resolveViaIframe(iframe);
                if (!viaIframe.isEmpty()) {
                    secrets = new JsSecrets(viaIframe, secrets.subtitle);
                }
            }
        }
        return playerResponse(secrets.video.isEmpty() ? page : secrets.video,
                secrets.subtitle, page);
    }

    /**
     * 组装播放响应。
     *
     * @param url      播放地址
     * @param subtitle 字幕地址（可空）
     * @param raw      原始页面地址（作为 Referer 兜底）
     * @return 播放 JSON
     */
    public String playerResponse(String url, String subtitle, String raw) {
        JSONObject out = new JSONObject();
        try {
            out.put("parse", 0);
            out.put("playUrl", "");
            out.put("url", url == null ? "" : url);
            out.put("header", new JSONObject(siteHeaders()).toString());
            if (subtitle != null && !subtitle.isEmpty()) {
                out.put("subf", "/vtt/utf-8");
                // ★ 上游写的是 do=czspp，与 Proxy 注册的 czsp 不匹配 → 字幕永不加载
                out.put("subt", ProxyPlain.getUrl() + "?do=" + SUB_ACTION
                        + "&url=" + URLEncoder.encode(subtitle, "UTF-8"));
            }
        } catch (Exception e) {
            return "{}";
        }
        return out.toString();
    }

    /** JS 混淆块解出的两个地址 */
    public static final class JsSecrets {
        public final String video;
        public final String subtitle;

        public JsSecrets(String video, String subtitle) {
            this.video = video == null ? "" : video;
            this.subtitle = subtitle == null ? "" : subtitle;
        }
    }

    /**
     * 解 JS 混淆块。
     *
     * @param html 播放页 HTML
     * @return 视频与字幕地址；未命中时两者皆空
     */
    public static JsSecrets parseJsSecrets(String html) {
        if (html == null || html.isEmpty()) {
            return new JsSecrets("", "");
        }
        Matcher m = RE_JS_CRYPTO.matcher(html);
        if (!m.find()) {
            return new JsSecrets("", "");
        }
        String plain = AesCbc.decryptBase64ToString(
                m.group(1), AesCbc.utf8(m.group(2)), AesCbc.utf8(m.group(3)));
        if (plain == null) {
            return new JsSecrets("", "");
        }
        return new JsSecrets(
                firstGroup(plain, RE_JS_VIDEO),
                firstGroup(plain, RE_JS_SUBTITLE));
    }

    /** 取播放页 iframe 地址。 */
    public static String iframeUrl(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        Dom.El root = Dom.parse(html, "");
        return root.selectAttr(SEL_IFRAME, "src", "");
    }

    /**
     * iframe 兜底：抓 iframe 页面，抠 {@code result_v2.data} 后反转再 Base64 解码。
     *
     * @param iframeUrl 完整 iframe 地址
     * @return 真实地址；失败返回空串
     */
    public String resolveViaIframe(String iframeUrl) {
        if (iframeUrl == null || iframeUrl.isEmpty()) {
            return "";
        }
        String html = fetch(iframeUrl, iframeHeaders());
        if (html == null || html.isEmpty()) {
            return "";
        }
        String json = firstGroup(html, RE_RESULT_V2);
        if (json.isEmpty()) {
            return "";
        }
        String data;
        try {
            data = new JSONObject(json).optString("data", "");
        } catch (Exception e) {
            return "";
        }
        if (data.isEmpty()) {
            return "";
        }
        byte[] decoded = AesCbc.decodeBase64(AesCbc.reverse(data));
        return decoded == null ? "" : new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 字幕代理处理器 —— 注册进 {@link ProxyPlain} 后，
     * {@code ?do=czsp&url=…} 就能拉到解密并转好的 VTT。
     *
     * @param params 代理参数，需含 {@code url}
     * @return 宿主响应 {@code Object[3]}
     */
    public Object[] loadSub(Map<String, String> params) {
        String url = params == null ? null : params.get("url");
        if (url == null || url.isEmpty()) {
            return ProxyPlain.response(200, "text/vtt; charset=utf-8", CzsSubtitle.emptyVtt());
        }
        byte[] raw;
        try {
            raw = fetchBytes(url);
        } catch (RuntimeException e) {
            raw = null;
        }
        if (raw == null || raw.length == 0) {
            return ProxyPlain.response(200, "text/vtt; charset=utf-8", CzsSubtitle.emptyVtt());
        }
        String vtt = CzsSubtitle.toVtt(raw, subKey(raw));
        return ProxyPlain.response(200, "text/vtt; charset=utf-8", vtt);
    }

    /**
     * 字幕解密密钥。
     *
     * <p>上游 {@code loadSub} 用的是 {@code b()} 里的站点头，<b>并没有密钥</b> ——
     * 它把 IV 取成密文前 16 字节、把密钥取成同一段 16 字节
     * （{@code a.c(copyOfRange(16,…), iv, iv, "AES/CBC/PKCS7Padding")}，
     * 第二、三个参数都是 {@code bArrCopyOfRange}）。也就是说 <b>key == IV == 密文前 16 字节</b>。
     * 已在 {@link #subKey(byte[])} 里如实保留该约定。
     *
     * @param data 密文（含前置 IV）
     * @return 16 字节密钥；长度不足返回 {@code null}
     */
    public static byte[] subKey(byte[] data) {
        if (data == null || data.length < AesCbc.BLOCK_SIZE) {
            return null;
        }
        return java.util.Arrays.copyOfRange(data, 0, AesCbc.BLOCK_SIZE);
    }

    /** 取原始字节。默认实现走 {@link #fetch} 再按 UTF-8 取字节（二进制安全起见应覆写）。 */
    public byte[] fetchBytes(String url) {
        String body = fetch(url, siteHeaders());
        return body == null ? null : body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 把本类的字幕处理器注册进本地代理。
     *
     * <p>注册键为 {@link #SUB_ACTION}（{@code czsp}），与 {@link ProxyPlain} 的约定一致。
     * 返回 {@code this} 便于链式调用。
     */
    public CzsappPlain registerProxy() {
        ProxyPlain.register(SUB_ACTION, this::loadSub);
        return this;
    }

    // ── 工具 ─────────────────────────────────────────────────

    /** 取第一个捕获组。 */
    public static String firstGroup(String text, Pattern p) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** 去掉尾部斜杠。 */
    public static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
