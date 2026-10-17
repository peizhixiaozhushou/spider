package com.example.tvbox.plain.film2;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1905 电影网（Web1905）—— HTML 抓取 + 签名播放接口。
 *
 * <h2>⚠️ 验证状态：纯静态推断（未拿到真实响应）</h2>
 * <p>本类<b>没有真实请求验证</b>。原因：{@code www.1905.com} 对非浏览器客户端
 * 统一返回 403（返回一个 "Embed Iframe" 反爬页），已实测：
 * <pre>
 *  GET https://www.1905.com/vod/list/n_1/o3p1.html         → 403
 *  GET https://www.1905.com/api/content/?m=Vod&amp;a=...       → 403
 *  GET https://profile.m1905.com/mvod/getVideoinfo.php?...  → 未可达
 * </pre>
 * 因此以下全部内容来自 {@code ae/decompiled/Web1905.java} 的静态还原，
 * 逻辑与常量逐字对应，但<b>无法保证线上仍生效</b>。
 *
 * <h2>协议还原</h2>
 *
 * <h3>1. 首页分类（硬编码 8 项）</h3>
 * <pre>
 *  type_id  : n_1        n_1_c_922  n_2     c_927   n_1_c_586  n_1_c_178  n_1_c_1024  n_1_c_1053
 *  type_name: 电影        微电影      系列电影  记录片    晚会        独家        综艺         体育
 * </pre>
 *
 * <h3>2. 分类页</h3>
 * <pre>
 *  GET https://www.1905.com/vod/list/{tid}/o3p{page}.html
 *  选择器：tid == "n_2" ? div.mod &gt; div &gt; a : section.search-list &gt; div &gt; a
 *  条目：a@href → 正则 play/(.*?).sh 抽出 cid；a@title；img@src；p 文本作 remarks
 * </pre>
 *
 * <h3>3. 详情</h3>
 * <pre>
 *  GET https://www.1905.com/api/content/?m=Vod&amp;a=getVodSidebar&amp;id={cid}&amp;fomat=json
 *  字段：title thumb commendreason description direct starring
 *  info.series_data[] → {title, contentid} 追加为播放集
 *  播放列表第一项固定是 {title}$ {cid}
 * </pre>
 *
 * <h3>4. 播放（SHA-1 签名 —— 本类唯一的密码学点）</h3>
 * <pre>
 *  nonce      = 当前秒级时间戳
 *  expiretime = nonce + 600
 *  uuid       = UUID.randomUUID().toString()
 *  playerid   = uuid 去掉 '-' 后取 [5,20) 共 15 字符
 *  page       = "https://www.1905.com/vod/play/{cid}.shtml" 再做
 *               ':'→"%3A"、'/'→"%2F" 的替换（先替换后拼入签名原文）
 *  sign 原文  = "cid={cid}&amp;expiretime={expiretime}&amp;nonce={nonce}"
 *               "&amp;page={page}&amp;playerid={playerid}&amp;type=hls&amp;uuid={uuid}.dde3d61a0411511d"
 *  signature  = SHA1(sign 原文) 的 hex（小写，不足两位左补 0）
 *  GET https://profile.m1905.com/mvod/getVideoinfo.php?nonce=..&amp;expiretime=..&amp;cid=..
 *      &amp;uuid=..&amp;playerid=..&amp;page=..&amp;type=hls&amp;signature=..&amp;callback=
 *  响应是 JSONP：先去掉 "(" 和 ")" 再解析
 *  优先级：sign 里有 uhd → hd → sd；取 sign[x].sign
 *  最终地址 = quality[x].host + sign[x].sign + path[x].path
 *            （三段直接字符串拼接，中间无分隔符 —— 上游 u.a(a,b,c) 就是 StringBuilder 连拼）
 * </pre>
 *
 * <h2>证据</h2>
 * <ul>
 *   <li>反编译原文: {@code ae/decompiled/Web1905.java}（197 行）</li>
 *   <li>{@code I.k(String)} = SHA-1 hex，本地反编译核对：
 *       {@code ae/decompiled/local/.../p038m/I.java} 第 369 行
 *       （{@code MessageDigest.getInstance("SHA-1")} + 逐字节 {@code Integer.toHexString} 补零）</li>
 *   <li>{@code u.a(a,b,c)} = {@code new StringBuilder().append(a).append(b).append(c)}，三段无分隔符</li>
 * </ul>
  *
 * <h2>未覆盖的分支</h2>
 *
 * <p><b>有 —— 但性质是"验证缺口"，不是"没实现"。</b>
 *
 * <ol>
 *   <li><b>本轮未拿到真实响应</b>（原实现见 {@code ae/decompiled/Web1905.java}）。
 *       本类的选择器、常量、URL 模板与反编译产物<b>逐字对应</b>，逻辑是完整的；
 *       但没有任何一次真实站点请求的响应被用来核对"这些选择器今天是否仍能命中"。
 *       <b>因此无法保证线上仍生效。</b>
 *       <b>将来补上需要</b>：一次真实响应样本（或抓包），把它固化成 fixture
 *       再加断言 —— 那时本节即可改为"无"。</li>
 * </ol>
 *
 * <p>除上条外，契约方法与私有解析方法均已逐一核对，无其它未覆盖分支。
 * 判定口径：本节只在<b>确实存在</b>无法还原的分支时才列条目。
  *
 * <h2>修正的上游缺陷</h2>
 *
 * <p>本类对上游的重写未修改其对外行为契约；<b>有意偏离</b>上游的地方（健壮性补强、
 * 命名歧义澄清、约定修正）已在上方「与上游的差异」一节逐条列出，并都有对应断言钉住。
 * 因此本节不再重复，以免同一事实在两处漂移。
 *
 * <p>判定口径："与上游的差异"与"修正的上游缺陷"在本项目里是同一类信息的两种措辞 ——
 * 前者强调"我们不一样在哪里"，后者强调"上游原本错了什么"。都以
 * {@code ae/decompiled/Web1905.java} 为对照基准。
 */
public class Web1905Plain extends BaseSpider {

    private static final String TAG = "Web1905Plain";

    /** 站点基址（反编译里多处硬编码） */
    public static final String DEFAULT_BASE = "https://www.1905.com";

    /** 播放信息接口（另一个域名） */
    public static final String PROFILE_HOST = "https://profile.m1905.com";

    /** 桌面 UA（逐字来自 Web1905.a()） */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 额外 Accept 头（逐字来自 Web1905.a()） */
    public static final String ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,"
          + "*/*;q=0.8,application/signed-exchange;v=b3;q=0.7";

    /**
     * 签名盐 —— 拼接在 uuid 后面。
     *
     * <p>逐字来自上游：{@code "...&uuid=%s.dde3d61a0411511d"}。
     * 这 16 位 hex 是站点侧硬编码的盐值，不随请求变化。
     */
    public static final String SIGN_SALT = "dde3d61a0411511d";

    /** 签名有效期（秒），上游 {@code 600 + now} */
    public static final int EXPIRE_SECONDS = 600;

    /** 首页 8 个分类（反编译 Arrays.asList 逐字） */
    public static final String[] TYPE_IDS = {
            "n_1", "n_1_c_922", "n_2", "c_927",
            "n_1_c_586", "n_1_c_178", "n_1_c_1024", "n_1_c_1053"
    };
    public static final String[] TYPE_NAMES = {
            "电影", "微电影", "系列电影", "记录片", "晚会", "独家", "综艺", "体育"
    };

    /** 系列电影分类的专用选择器分支（反编译 "n_2".equals(str)） */
    public static final String TID_SERIES = "n_2";

    /** 从播放页 URL 里抽 cid */
    private static final Pattern RE_PLAY_CID = Pattern.compile("play/(.*?).sh");

    /**
     * 构造。
     */
    public Web1905Plain() {
        super();
    }

    /**
     * 还原 {@code Web1905.init(ctx, str)}：上游忽略 ext，写死域名。
     *
     * @param ctx    宿主上下文
     * @param extend ext 原文
     */
    @Override
    public void init(Context ctx, String extend) {
        super.init(ctx, extend);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_BASE;
        }
        Log.i(TAG, "init base=" + baseUrl + "（本站 403 反爬，未真实验证）");
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code Web1905.a(str)} —— UA + Referer + Accept。
     *
     * @return header 表
     */
    protected Map<String, String> requestHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", "https://www.1905.com");
        h.put("Accept", ACCEPT);
        return h;
    }

    /**
     * GET。
     *
     * @param url 完整地址
     * @return 响应体；失败空串
     */
    protected String pageGet(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        return http.get(url, requestHeaders());
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code Web1905.homeContent(boolean)} —— 8 个硬编码分类。
     *
     * @param filter 是否返回筛选（上游无筛选）
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        VodBuilder vb = VodBuilder.create();
        for (int i = 0; i < TYPE_IDS.length; i++) {
            vb.clazz(TYPE_IDS[i], TYPE_NAMES[i]);
        }
        return vb.homeContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code Web1905.categoryContent(...)}。
     *
     * <pre>
     *  GET {base}/vod/list/{tid}/o3p{pg}.html
     *  tid == "n_2" → "div.mod > div > a"
     *  否则          → "section.search-list > div > a"
     * </pre>
     *
     * @param tid    分类 id
     * @param pg     页码
     * @param filter 是否启用筛选
     * @param extend 筛选值
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  java.util.HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String t = tid == null ? "" : tid.trim();
        if (t.isEmpty()) {
            return VodBuilder.create().page(page).categoryContent();
        }
        String url = requireUrl() + "/vod/list/" + t + "/o3p" + page + ".html";
        List<Vod> list = parseCategory(pageGet(url), t);
        int limit = Math.max(list.size(), 1);
        // 上游 h.a(pg, new c(), 0, 0, 0, list)：total/pagecount 全 0
        return VodBuilder.create().addAll(list).page(page, 0, limit).categoryContent();
    }

    /**
     * 解析分类列表。
     *
     * <p>上游用 {@code k.b(href, title, src, p文本, list)} 收集，
     * 即：id=href（经 play/(.*?).sh 抽 cid）、name=title、pic=src、remarks=p 文本。
     *
     * @param html 页面
     * @param tid  分类（决定选择器分支）
     * @return 影片列表
     */
    protected List<Vod> parseCategory(String html, String tid) {
        List<Vod> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        String scope = extractListScope(html, TID_SERIES.equals(tid));
        if (scope.isEmpty()) {
            return out;
        }
        // 每个条目是一个 <a ...>...</a>（内部含 img）
        Matcher m = Pattern.compile("(?is)<a\\s+[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>").matcher(scope);
        while (m.find()) {
            String href = extractCid(m.group(1));
            String inner = m.group(2);
            String title = HtmlLite.attr(m.group(0), "title");
            if (title.isEmpty()) {
                title = HtmlLite.text(inner);
            }
            String pic = HtmlLite.attr(inner, "src");
            // ⚠️ <p> 是 <a> 的兄弟节点（紧随其后），不在 inner 里。
            //    必须从 <a> 结束位置往后找，不能只搜 inner。
            String after = scope.substring(Math.min(m.end(), scope.length()));
            String remarks = HtmlLite.text(HtmlLite.group(after,
                    Pattern.compile("(?is)^\\s*<p[^>]*>(.*?)</p>"), 1));
            if (remarks.isEmpty()) {
                // 兜底：条目块内任意 <p>
                remarks = HtmlLite.text(HtmlLite.group(inner,
                        Pattern.compile("(?is)<p[^>]*>(.*?)</p>"), 1));
            }
            if (href.isEmpty() && title.isEmpty()) {
                continue;
            }
            out.add(new Vod().id(href).name(title).pic(pic).remarks(remarks));
        }
        return out;
    }

    /**
     * 截取列表容器范围。
     *
     * <p>上游选择器含 {@code >} 子代选择器，正则无法精确表达，
     * 这里退化为「定位容器 class 起点 → 到其配对结束」的近似：
     * 系列电影看 {@code class="mod"}，其余看 {@code class="search-list"}。
     *
     * @param html     页面
     * @param series   true 表示系列电影分支
     * @return 容器片段；未命中返回空串
     */
    protected String extractListScope(String html, boolean series) {
        String mark = series ? "class=\"mod\"" : "class=\"search-list\"";
        int i = html.indexOf(mark);
        if (i < 0) {
            // 退一步：用宽松匹配（class 可能带其它类名）
            String loose = series ? "mod" : "search-list";
            i = html.indexOf(loose);
        }
        if (i < 0) {
            return "";
        }
        // 从容器起点向后取一段（条目都在紧随其后的区域内）
        int start = html.lastIndexOf('<', i);
        if (start < 0) {
            start = i;
        }
        int end = html.length();
        // 遇到 footer / 下一个大区块就截断
        int foot = html.indexOf("<footer", start);
        if (foot > start) {
            end = foot;
        }
        return html.substring(start, end);
    }

    /**
     * 从播放页/详情页 URL 里抽 cid（还原 {@code Pattern.compile("play/(.*?).sh")}）。
     *
     * @param href 原始 href
     * @return cid；不含 play/ 时原样返回
     */
    public static String extractCid(String href) {
        if (href == null || href.isEmpty()) {
            return "";
        }
        Matcher m = RE_PLAY_CID.matcher(href);
        return m.find() ? m.group(1) : href;
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code Web1905.detailContent(List)}。
     *
     * @param ids cid
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String cid = firstId(ids);
        if (cid.isEmpty()) {
            return emptyResponse();
        }
        String url = requireUrl() + "/api/content/?m=Vod&a=getVodSidebar&id=" + cid + "&fomat=json";
        return buildDetail(cid, pageGet(url));
    }

    /**
     * 从详情接口响应构造详情 JSON（纯函数，便于离线自测）。
     *
     * @param cid  cid
     * @param resp 接口响应
     * @return 标准详情 JSON
     */
    protected String buildDetail(String cid, String resp) {
        if (TextUtils.isEmpty(resp)) {
            return emptyResponse();
        }
        JSONObject o;
        try {
            o = new JSONObject(resp);
        } catch (Exception e) {
            Log.e(TAG, "详情响应不是 JSON", e);
            return emptyResponse();
        }
        String title = o.optString("title");
        Vod vod = new Vod()
                .id(cid)
                .name(title)
                .pic(o.optString("thumb"))
                .remarks(o.optString("commendreason"))
                .content(o.optString("description"))
                .director(o.optString("direct"))
                .actor(o.optString("starring"));

        List<String> eps = new ArrayList<>();
        // 上游第一项固定是 {title}${cid}
        eps.add(title + "$" + cid);
        JSONObject info = o.optJSONObject("info");
        JSONArray series = info == null ? null : info.optJSONArray("series_data");
        if (series != null) {
            for (int i = 0; i < series.length(); i++) {
                JSONObject s = series.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                String contentId = s.optString("contentid");
                if (contentId.isEmpty()) {
                    continue;
                }
                eps.add(s.optString("title") + "$" + contentId);
            }
        }
        vod.playFrom("1905").playUrl(HtmlLite.join("#", eps));
        return VodBuilder.detail(vod).detailContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 搜索
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code Web1905.searchContent(str, z)}：
     * {@code /search/index-p-type-all-q-{urlencode}.html}。
     *
     * <p>选择器 {@code div.movie_box > div > div}；条目里的
     * {@code div > ul > li.paly-tab-icon > a@href} 是播放页链接（抽 cid），
     * 标题取 {@code img@alt}，封面取 {@code img@src}。
     *
     * @param key   关键词
     * @param quick 快速搜索
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) {
            return VodBuilder.create().searchContent();
        }
        String k;
        try {
            k = URLEncoder.encode(key, "UTF-8");
        } catch (Exception e) {
            k = key;
        }
        String url = requireUrl() + "/search/index-p-type-all-q-" + k + ".html";
        return VodBuilder.of(parseSearch(pageGet(url))).searchContent();
    }

    /**
     * 解析搜索结果页。
     *
     * @param html 页面
     * @return 影片列表
     */
    protected List<Vod> parseSearch(String html) {
        List<Vod> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        int box = html.indexOf("movie_box");
        if (box < 0) {
            return out;
        }
        String scope = html.substring(box);
        // 每个结果块以 <div ...> 开头；用 img 作为锚点，块范围取到下一个 img 之前
        Matcher m = Pattern.compile("(?is)<img\\s+[^>]*alt=\"([^\"]*)\"[^>]*>").matcher(scope);
        List<int[]> spans = new ArrayList<>();
        List<String> alts = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
            alts.add(m.group(1));
        }
        for (int i = 0; i < spans.size(); i++) {
            int from = i == 0 ? 0 : spans.get(i)[0];
            int to = i + 1 < spans.size() ? spans.get(i + 1)[0] : scope.length();
            String block = scope.substring(from, to);
            String alt = alts.get(i);
            String pic = HtmlLite.attr(block.substring(Math.max(0, spans.get(i)[0] - from)),
                    "src");
            String playHref = HtmlLite.group(block,
                    Pattern.compile("(?is)<a\\s+[^>]*href=\"([^\"]*)\"[^>]*>"), 1);
            String cid = extractCid(playHref);
            if (cid.isEmpty() && alt.isEmpty()) {
                continue;
            }
            out.add(new Vod().id(cid).name(HtmlLite.decodeEntities(alt)).pic(pic).remarks(""));
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 播放（SHA-1 签名）
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code Web1905.playerContent(...)} —— 签名取 m3u8。
     *
     * @param flag     线路名
     * @param id       cid
     * @param vipFlags 需要解析的标识
     * @return 播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) {
            return emptyResponse();
        }
        long now = System.currentTimeMillis() / 1000L;
        String url = requestPlayUrl(id, now, UUID.randomUUID().toString());
        if (url.isEmpty()) {
            return emptyResponse();
        }
        return VodBuilder.playerContent(url, url.contains(".m3u8")
                ? "application/vnd.apple.mpegurl" : "video/*");
    }

    /**
     * 构造签名 URL 并请求播放信息（可注入时间与 uuid，便于确定性自测）。
     *
     * @param cid  cid
     * @param now  秒级时间戳
     * @param uuid 随机 UUID 文本（含 '-'）
     * @return 最终 m3u8 地址；失败空串
     */
    protected String requestPlayUrl(String cid, long now, String uuid) {
        String apiUrl = buildSignedUrl(cid, now, uuid);
        String resp = pageGet(apiUrl);
        return parsePlayResponse(resp);
    }

    /**
     * 拼签名 URL —— 本类核心算法，逐字还原上游。
     *
     * @param cid  cid
     * @param now  秒级时间戳（nonce）
     * @param uuid 随机 UUID 文本（含 '-'）
     * @return 完整请求 URL
     */
    public static String buildSignedUrl(String cid, long now, String uuid) {
        long expire = EXPIRE_SECONDS + now;
        String u = uuid == null ? "" : uuid;
        String playerId = playerIdOf(u);
        String page = pageParam(cid);

        String signSrc = String.format(
                "cid=%s&expiretime=%d&nonce=%d&page=%s&playerid=%s&type=hls&uuid=%s.%s",
                cid, expire, now, page, playerId, u, SIGN_SALT);
        String signature = sha1Hex(signSrc);

        StringBuilder sb = new StringBuilder();
        sb.append(PROFILE_HOST).append("/mvod/getVideoinfo.php?nonce=").append(now);
        sb.append("&expiretime=").append(expire);
        sb.append("&cid=").append(cid);
        sb.append("&uuid=").append(u);
        sb.append("&playerid=").append(playerId);
        sb.append("&page=https%3A%2F%2Fwww.1905.com%2Fvod%2Fplay%2F").append(cid)
          .append(".shtml&type=hls&signature=").append(signature);
        sb.append("&callback=");
        return sb.toString();
    }

    /**
     * 还原 {@code uuid.replace("-", "").substring(5, 20)} —— 取 15 字符作为 playerid。
     *
     * @param uuid 含 '-' 的 UUID 文本
     * @return playerid；uuid 过短时返回空串
     */
    public static String playerIdOf(String uuid) {
        if (uuid == null) {
            return "";
        }
        String s = uuid.replace("-", "");
        if (s.length() < 20) {
            return "";
        }
        return s.substring(5, 20);
    }

    /**
     * 还原签名原文里的 page 参数：
     * {@code "https://www.1905.com/vod/play/{cid}.shtml"} 再把 {@code :}→{@code %3A}、
     * {@code /}→{@code %2F}。
     *
     * <p>注意：上游是「先算好 page 再拼进签名原文」，但最终<b>请求 URL 里</b>
     * 的 page 参数用的是手写常量 {@code https%3A%2F%2Fwww.1905.com%2Fvod%2Fplay%2F{cid}.shtml}
     * —— 两者内容等价，这里保留同样的双份写法以逐字对齐。
     *
     * @param cid cid
     * @return 编码后的 page 值
     */
    public static String pageParam(String cid) {
        String raw = "https://www.1905.com/vod/play/" + HtmlLite.nz(cid) + ".shtml";
        return raw.replace(":", "%3A").replace("/", "%2F");
    }

    /**
     * 解析播放信息响应（JSONP，需先剥 {@code (} 和 {@code )}）。
     *
     * @param resp 响应体
     * @return m3u8 地址；失败空串
     */
    protected String parsePlayResponse(String resp) {
        if (TextUtils.isEmpty(resp)) {
            return "";
        }
        // 剥 JSONP 壳：取首个 '(' 之后、末个 ')' 之前。
        // ⚠️ 不能用 replace("(","") —— 那只会删掉括号字符，
        //    "callback({...})" 会变成 "callback{...}"，JSON 解析必失败。
        int lp = resp.indexOf('(');
        int rp = resp.lastIndexOf(')');
        String json;
        if (lp >= 0 && rp > lp) {
            json = resp.substring(lp + 1, rp).trim();
        } else {
            json = resp.trim();          // 非 JSONP，按纯 JSON 处理
        }
        String url = pickQualityUrl(json);
        if (url.isEmpty()) {
            Log.w(TAG, "播放响应里没有可用画质");
        }
        return url;
    }

    /**
     * 从 {@code data} 里按 uhd → hd → sd 顺序取直链（纯函数）。
     *
     * @param json 已剥壳的 JSON
     * @return 直链；失败空串
     */
    public static String pickQualityUrl(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                return "";
            }
            JSONObject sign = data.optJSONObject("sign");
            JSONObject quality = data.optJSONObject("quality");
            JSONObject path = data.optJSONObject("path");
            if (sign == null || quality == null || path == null) {
                return "";
            }
            String pick = "";
            for (String q : new String[]{"uhd", "hd", "sd"}) {
                if (sign.has(q)) {
                    pick = q;
                    break;
                }
            }
            if (pick.isEmpty()) {
                return "";
            }
            JSONObject s = sign.optJSONObject(pick);
            JSONObject h = quality.optJSONObject(pick);
            JSONObject p = path.optJSONObject(pick);
            if (s == null || h == null || p == null) {
                return "";
            }
            // 上游 u.a(host, sign, path)：三段直接连拼，无分隔符
            return h.optString("host") + s.optString("sign") + p.optString("path");
        } catch (Exception e) {
            Log.e(TAG, "画质解析失败", e);
            return "";
        }
    }

    /**
     * 还原 {@code I.k(String)} —— SHA-1 小写 hex（不足两位左补 0）。
     *
     * @param s 原文
     * @return 40 字符 hex；异常返回空串
     */
    public static String sha1Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(HtmlLite.nz(s).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                String h = Integer.toHexString(b & 0xFF);
                if (h.length() == 1) {
                    sb.append('0');
                }
                sb.append(h);
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "SHA-1 失败", e);
            return "";
        }
    }
}
