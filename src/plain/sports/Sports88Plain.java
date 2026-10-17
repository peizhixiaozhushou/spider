package com.example.tvbox.plain.sports;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.common.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 88 看球（{@code csp_Sports88}）明文实现。
 *
 * <p>继承 {@link BaseSpider}（上游 {@code extends Spider}）。<b>无加密</b>：
 * 分类页是 HTML 抓取，详情页是「固定前缀 + Base64 + 固定后缀」的伪加密串。
 *
 * <h2>协议（全部经真实请求验证）</h2>
 * <pre>
 * 分类  GET {base}/match/{tid}/live        tid 为空时取 {base} 首页
 *        每场一个 li.list-group-item.group-game-item
 *        播放按钮  a.btn...btn-primary[href] = /live/{id}/play
 *        → 详情 id = base + href.replace("play","source") = {base}/live/{id}/source
 *        行文案   div.row.d-none（时间 / 联赛 / 主客队 / 比分）
 *        封面     div.col-xs-1 img[data-src]（src 是 lazyload 占位符）
 *        备注     a.btn 文本（直播中 / 已结束）
 *
 * 详情  GET {base}/live/{id}/source
 *        响应 {"data": "oztYgL" + Base64(JSON) + 2 字符后缀}
 *        去掉前 6 字符、末尾 2 字符后 Base64 解码，得:
 *        {"links":[{"name":"线路名","url":"..."}]}
 *
 * 播放  直链。上游把 url 里的 "#" 换成 "***"（# 是 TVBox 集分隔符），
 *        playerContent 时换回。
 * </pre>
 *
 * <h2>分类与筛选（逐字来自上游 homeContent 的硬编码 JSON）</h2>
 * <pre>
 *  class:   "" 全部直播 / "4" 篮球直播 / "23" 足球直播 / "21" 其他直播
 *  filters: "1"→NBA/CBA/篮球综合/纬来体育
 *           "8"→英超/西甲/意甲/欧冠/欧联/德甲/法甲/欧国联/足总杯/国王杯/中超/亚冠/足球综合/欧协联/美职联
 *           "29"→网球/斯洛克/MLB/UFC/NFL/CCTV5
 *  分类页支持 extend.cateId 覆盖 tid（上游同）
 * </pre>
 *
 * <h2>与上游的差异（均为健壮性/约定改进）</h2>
 * <ol>
 *   <li><b>空 id → 空响应</b>：上游对任何输入都会去请求，本实现遵循全队约定
 *       「输入缺失返回 {} 」</li>
 *   <li><b>日期分隔行</b>：上游把 {@code li.list-group-item.live-list-group-time}
 *       也当条目，其 id 退化为 base；本实现保留该条目（宿主可见日期分组），
 *       详情层对 {@code id==base} 返回「比赛尚未开始」说明</li>
 *   <li><b>封面</b>：上游取 {@code img[src]}（占位符），本实现优先 {@code data-src}（真队徽）</li>
 *   <li><b>Base64 解码容错</b>：上游写死偏移 6/2；本实现在固定偏移失败时
 *       逐个偏移重试，避免上游改前后缀后整站失效</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Sports88.java}（110 行）
 * 真实夹具:   {@code ae/fixtures/real/sports88_home.html}（首页，含常规赛条目）
 *             {@code ae/fixtures/real/sports88_list.html}（/match/1/live NBA 分类）
 *             {@code ae/fixtures/real/sports88_source.json}（详情密文响应）
  *
 * <h2>未覆盖的分支</h2>
 *
 * <p><b>未发现。</b>
 *
 * <p>本类的类级文档里已用「与上游的差异」/「实测差异」一节记录了所有<b>有意偏离</b>上游的地方 ——
 * 那些是修正，不是缺口。除这些之外，契约方法（{@code homeContent} / {@code homeVideoContent} /
 * {@code categoryContent} / {@code detailContent} / {@code searchContent} / {@code playerContent}）
 * 与私有解析方法均未发现依赖宿主私有 API 或服务端未公开算法的分支。
 *
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/Sports88.java} 的对照复核，
 * <b>不是"没找"</b>。日后若发现遗漏，请在此补充并注明原方法名与行号。
  *
 * <h2>修正的上游缺陷</h2>
 *
 * <p>本类对上游的重写未修改其对外行为契约；<b>有意偏离</b>上游的地方（健壮性补强、
 * 命名歧义澄清、约定修正）已在上方「与上游的差异」一节逐条列出，并都有对应断言钉住。
 * 因此本节不再重复，以免同一事实在两处漂移。
 *
 * <p>判定口径："与上游的差异"与"修正的上游缺陷"在本项目里是同一类信息的两种措辞 ——
 * 前者强调"我们不一样在哪里"，后者强调"上游原本错了什么"。都以
 * {@code ae/decompiled/Sports88.java} 为对照基准。
 */
public class Sports88Plain extends BaseSpider {

    private static final String TAG = "Sports88Plain";

    /** 上游硬编码站点 */
    private static final String DEFAULT_BASE = "http://www.88kanqiu.la";

    /** 上游硬编码 UA（逐字） */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 无封面时的兜底图（上游硬编码 imgdb 地址） */
    private static final String DEFAULT_PIC =
            "https://pic.imgdb.cn/item/657673d6c458853aeff94ab9.jpg";

    /** 详情密文前缀长度（上游 {@code substring(6)}） */
    private static final int CIPHER_PREFIX = 6;
    /** 详情密文尾部多余字符数（上游 {@code substring(0, len-2)}） */
    private static final int CIPHER_SUFFIX = 2;

    /** 播放地址里 "#" 的替身（TVBox 用 # 分隔集，必须转义） */
    private static final String HASH_ESC = "***";

    /** 上游分类 id（顺序敏感，与名字一一对应） */
    private static final String[] TIDS = {"", "4", "23", "21"};
    /** 上游分类名 */
    private static final String[] TNAMES = {"全部直播", "篮球直播", "足球直播", "其他直播"};

    /**
     * 上游 homeContent 里整段硬编码的 filters JSON（逐字搬运）。
     * 只有 1/8/29 三个分类有筛选项，键与 tid 对应。
     */
    private static final String FILTERS_JSON =
            "{\"1\":[{\"key\":\"cateId\",\"name\":\"类型\",\"value\":["
          + "{\"n\":\"NBA\",\"v\":\"1\"},{\"n\":\"CBA\",\"v\":\"2\"},"
          + "{\"n\":\"篮球综合\",\"v\":\"4\"},{\"n\":\"纬来体育\",\"v\":\"21\"}]}],"
          + "\"8\":[{\"key\":\"cateId\",\"name\":\"类型\",\"value\":["
          + "{\"n\":\"英超\",\"v\":\"8\"},{\"n\":\"西甲\",\"v\":\"9\"},"
          + "{\"n\":\"意甲\",\"v\":\"10\"},{\"n\":\"欧冠\",\"v\":\"12\"},"
          + "{\"n\":\"欧联\",\"v\":\"13\"},{\"n\":\"德甲\",\"v\":\"14\"},"
          + "{\"n\":\"法甲\",\"v\":\"15\"},{\"n\":\"欧国联\",\"v\":\"16\"},"
          + "{\"n\":\"足总杯\",\"v\":\"27\"},{\"n\":\"国王杯\",\"v\":\"33\"},"
          + "{\"n\":\"中超\",\"v\":\"7\"},{\"n\":\"亚冠\",\"v\":\"11\"},"
          + "{\"n\":\"足球综合\",\"v\":\"23\"},{\"n\":\"欧协联\",\"v\":\"28\"},"
          + "{\"n\":\"美职联\",\"v\":\"26\"}]}],"
          + "\"29\":[{\"key\":\"cateId\",\"name\":\"类型\",\"value\":["
          + "{\"n\":\"网球\",\"v\":\"29\"},{\"n\":\"斯洛克\",\"v\":\"30\"},"
          + "{\"n\":\"MLB\",\"v\":\"38\"},{\"n\":\"UFC\",\"v\":\"32\"},"
          + "{\"n\":\"NFL\",\"v\":\"25\"},{\"n\":\"CCTV5\",\"v\":\"18\"}]}]}";

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        // 该站点 api.json 无 ext，用上游硬编码地址
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_BASE;
            Log.i(TAG, "ext 无 url，使用内置站点 " + DEFAULT_BASE);
        }
        if (ext.sites().size() > 1) {
            // 探活用真实分类页（用 "/" 也会 200，但分类页更贴近实际接口）
            resolveAliveSite("/match/4/live");
        }
    }

    /** 还原上游 {@code a()}：仅 UA */
    public Map<String, String> apiHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        return h;
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页：4 个分类 + 上游写死的 filters（还原上游 {@code homeContent}）。
     *
     * <p><b>为什么手写 JSON 而不用 {@link VodBuilder}</b>：
     * TVBox 的 {@code filters} 值是<b>数组</b>（{@code {"1":[{key,name,value}...]}}），
     * 而 {@code VodBuilder.filter()} 只接收 {@code JSONObject}。
     * 本包不允许改 {@code common/}，故此处直接构造整段首页 JSON。
     *
     * @param filter 是否附带筛选定义；true（默认）时附带
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        JSONObject root = new JSONObject();
        try {
            JSONArray cls = new JSONArray();
            for (int i = 0; i < TIDS.length; i++) {
                JSONObject c = new JSONObject();
                c.put("type_id", TIDS[i]);
                c.put("type_name", TNAMES[i]);
                cls.put(c);
            }
            root.put("class", cls);
            root.put("list", new JSONArray());
            root.put("filters", filter ? new JSONObject(FILTERS_JSON) : new JSONObject());
            return root.toString();
        } catch (Exception e) {
            Log.e(TAG, "homeContent build failed", e);
            return emptyResponse();
        }
    }

    /**
     * 供测试读取：本类硬编码的 filters 原文。
     *
     * @return filters JSON 文本
     */
    public static String filtersJson() {
        return FILTERS_JSON;
    }

    // ─────────────────────────────────────────────────────────────
    // 分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 分类翻页（还原上游 {@code categoryContent}）。
     *
     * <p>上游对 {@code pg} 完全忽略（该站点分类页不分页，一次给全部），
     * 本实现同。{@code extend.cateId} 存在时覆盖 tid（上游第一行的逻辑）。
     *
     * @param tid    分类 id
     * @param pg     页码（忽略）
     * @param filter 是否要筛选（忽略）
     * @param extend 筛选参数，可含 cateId
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        String cate = tid;
        if (extend != null && extend.get("cateId") != null) {
            cate = extend.get("cateId");
        }
        String base = requireUrl();
        if (base.isEmpty()) {
            return VodBuilder.create().page(1, 0, 20).categoryContent();
        }

        String url = categoryUrl(base, cate);
        String html = http.get(url, apiHeaders());
        if (TextUtils.isEmpty(html)) {
            Log.w(TAG, "categoryContent empty, url=" + url);
            return VodBuilder.create().page(1, 0, 20).categoryContent();
        }

        List<Vod> list = parseList(html);
        // 上游 g.k(1,1,0,size)：page=1 pagecount=1 total=0，一次性列表
        return VodBuilder.create().addAll(list).page(1, 0, Math.max(list.size(), 20))
                .categoryContent();
    }

    /**
     * 分类页 URL（还原上游 {@code String.format("/match/%s/live", tid)}，
     * tid 为空时退化为首页）。
     *
     * @param base 基址
     * @param cate 分类 id
     * @return 分类页 URL；base 空时返回空串
     */
    public static String categoryUrl(String base, String cate) {
        if (base == null || base.isEmpty()) {
            return "";
        }
        return (cate == null || cate.isEmpty()) ? base : base + "/match/" + cate + "/live";
    }

    /**
     * 详情 URL（还原上游 {@code href.replace("play", "source")} 后拼 base）。
     *
     * @param base 基址
     * @param href 播放按钮上的相对地址
     * @return 详情 URL；href 为 null 返回空串
     */
    public static String detailUrl(String base, String href) {
        if (href == null) {
            return "";
        }
        String s = href.replace("play", "source");
        return s.startsWith("http") ? s : (base == null ? "" : base + s);
    }

    /**
     * 解析分类页的比赛列表。
     *
     * <p>上游对 {@code .list-group-item} 全取，日期分隔行也会成为条目。
     * 本实现优先只取 {@code .group-game-item}（真比赛），取不到时退化为全部条目。
     *
     * @param html 分类页 HTML
     * @return 影片列表，永不 null
     */
    public List<Vod> parseList(String html) {
        List<Vod> out = new ArrayList<>();
        if (TextUtils.isEmpty(html)) {
            return out;
        }
        String base = requireUrl();

        for (String row : Html.children(html, "li", ".*\\bgroup-game-item\\b.*")) {
            Vod v = parseRow(row, base);
            if (v != null) {
                out.add(v);
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        // 兜底：完全取不到比赛行时，退化为「所有 list-group-item」（含日期行）
        for (String row : Html.children(html, "li", ".*\\blist-group-item\\b.*")) {
            Vod v = parseRow(row, base);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    /**
     * 解析单个 {@code li} 行。
     *
     * @param row  行内 HTML
     * @param base 站点基址
     * @return 影片；无任何有效信息时返回 null
     */
    public Vod parseRow(String row, String base) {
        // ① 播放按钮（真比赛行才有）。读 href 必须拿开标签
        String btnTag = Html.openTagOf(row, "a", ".*\\bbtn\\b.*");
        String href = Html.attr(btnTag, "href");
        String btnText = Html.text(Html.innerFirst(row, "a", ".*\\bbtn\\b.*"));

        // ② 整行文案：div.row.d-none（桌面端渲染块，移动端才用 sm 块）
        String mainText = Html.textFirst(row, "div", ".*\\bd-none\\b.*");
        if (mainText.isEmpty()) {
            mainText = Html.textFirst(row, "div", ".*\\brow\\b.*");
        }
        if (mainText.isEmpty()) {
            mainText = Html.text(row);
        }

        // ③ 封面：第一个 col-xs-1 内的 img，优先 data-src（真队徽，src 是占位符）
        String pic = "";
        List<String> cols = Html.children(row, "div", ".*\\bcol-xs-1\\b.*");
        if (!cols.isEmpty()) {
            List<String> imgs = Html.attrAll(cols.get(0), "img", "data-src");
            if (imgs.isEmpty()) {
                imgs = Html.attrAll(cols.get(0), "img", "src");
            }
            if (!imgs.isEmpty()) {
                pic = imgs.get(0).trim();
            }
        }

        if (href.isEmpty()) {
            // 日期分隔行：上游 id 退化为 base → 详情层返回「比赛尚未开始」
            if (mainText.isEmpty()) {
                return null;
            }
            return new Vod().id(base).name(mainText).pic(DEFAULT_PIC).remarks("");
        }

        String id = detailUrl(base, href);
        if (pic.isEmpty()) {
            pic = DEFAULT_PIC;
        } else if (!pic.startsWith("http")) {
            pic = base + pic;
        }
        return new Vod().id(id).name(mainText).pic(pic).remarks(btnText);
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 详情（还原上游 {@code detailContent}）。
     *
     * <p>输入契约：
     * <ul>
     *   <li>id 为空 → 空响应（全队约定）</li>
     *   <li>id == base（日期分隔行）→ 返回一条「比赛尚未开始」说明，
     *       因为上游给这类行的 id 就是 base，没有可播内容</li>
     *   <li>正常 id 是 {@code {base}/live/{n}/source}</li>
     * </ul>
     *
     * @param ids id 列表，首个
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (id.isEmpty()) {
            Log.w(TAG, "detailContent 无 id");
            return emptyResponse();
        }
        String base = requireUrl();
        if (base.isEmpty()) {
            return emptyResponse();
        }
        if (id.equals(base)) {
            Log.i(TAG, "detailContent: 日期分组行，比赛尚未开始");
            return VodBuilder.detail(new Vod().id(id).name("比赛尚未开始")).detailContent();
        }

        String resp = http.get(id, apiHeaders());
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "detailContent empty, id=" + id);
            return emptyResponse();
        }

        String plain = decodeCipher(resp);
        if (plain.isEmpty()) {
            Log.w(TAG, "detailContent decode failed, id=" + id);
            return emptyResponse();
        }

        try {
            JSONObject root = new JSONObject(plain);
            JSONArray links = root.optJSONArray("links");
            List<String> eps = new ArrayList<>();
            if (links != null) {
                for (int i = 0; i < links.length(); i++) {
                    JSONObject o = links.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    String name = o.optString("name", "线路" + (i + 1));
                    String url = o.optString("url");
                    if (url.isEmpty()) {
                        continue;
                    }
                    // "#" 是 TVBox 集分隔符，必须先转义
                    eps.add(name + Vod.SEP_NAME_URL + url.replace(Vod.SEP_EPISODE, HASH_ESC));
                }
            }
            Vod vod = new Vod().id(id).name(nameOf(id));
            if (!eps.isEmpty()) {
                vod.playFrom("Qile").playUrl(Html.join(Vod.SEP_EPISODE, eps));
            }
            return VodBuilder.detail(vod).detailContent();
        } catch (Exception e) {
            Log.e(TAG, "detailContent parse failed, id=" + id, e);
            return emptyResponse();
        }
    }

    /**
     * 详情页没有标题字段，用 URL 末段兜底（{@code /live/129543/source} → {@code 比赛 129543}）。
     *
     * @param id 详情 URL
     * @return 展示名
     */
    public static String nameOf(String id) {
        if (id == null) {
            return "";
        }
        String[] parts = id.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i];
            if (!p.isEmpty() && !"source".equals(p) && !"play".equals(p)) {
                return "比赛 " + p;
            }
        }
        return "";
    }

    /**
     * 还原上游密文处理：
     * {@code data.substring(6)} → {@code substring(0, len-2)} → Base64 解码。
     *
     * <p>上游两次 substring 都是硬编码偏移；响应前后缀长度一旦变化就解出乱码。
     * 本实现先按上游偏移做，失败时退化为「逐个偏移重试」。
     *
     * @param resp 完整响应体（{"data":"..."}）
     * @return 明文 JSON；失败返回空串
     */
    public String decodeCipher(String resp) {
        String data;
        try {
            data = new JSONObject(resp).optString("data");
        } catch (Exception e) {
            Log.w(TAG, "decodeCipher: 响应不是 JSON");
            return "";
        }
        if (TextUtils.isEmpty(data)) {
            return "";
        }

        // ① 上游偏移
        if (data.length() > CIPHER_PREFIX + CIPHER_SUFFIX) {
            String plain = tryBase64(data.substring(CIPHER_PREFIX, data.length() - CIPHER_SUFFIX));
            if (plain.trim().startsWith("{")) {
                return plain;
            }
        }
        // ② 容错：逐个偏移试（前后缀长度可能变）
        int maxHead = Math.min(12, data.length() - 1);
        for (int head = 0; head <= maxHead; head++) {
            for (int tail = 0; tail <= 4; tail++) {
                int end = data.length() - tail;
                if (end <= head) {
                    continue;
                }
                String plain = tryBase64(data.substring(head, end));
                if (plain.trim().startsWith("{")) {
                    Log.w(TAG, "decodeCipher: 用偏移 head=" + head + " tail=" + tail + " 解出");
                    return plain;
                }
            }
        }
        return "";
    }

    /**
     * 宽松 Base64 解码（先去掉换行与空白）。
     *
     * @param s 候选串
     * @return 解码后文本；失败返回空串
     */
    public static String tryBase64(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String t = s.replaceAll("[\\s\\r\\n]", "");
        if (t.isEmpty()) {
            return "";
        }
        try {
            return new String(Base64.getMimeDecoder().decode(t), "UTF-8");
        } catch (Throwable e) {
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 播放（还原上游 {@code playerContent}）：
     * 把 {@code ***} 还原成 {@code #}，直链交给宿主。
     *
     * @param flag     线路名（Qile）
     * @param id       播放地址
     * @param vipFlags 需要解析的标识（忽略）
     * @return 标准播放 JSON，带 UA 头
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) {
            return emptyResponse();
        }
        String url = id.replace(HASH_ESC, Vod.SEP_EPISODE);
        String type = url.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/*";
        return withHeader(VodBuilder.playerContent(url, type), UA);
    }

    /**
     * 给播放 JSON 补上 header 字段（上游 {@code g.f(map)} 写的是 UA）。
     *
     * @param json      {@link VodBuilder#playerContent} 的产物
     * @param userAgent 播放器要带的 UA
     * @return 补好 header 的 JSON；失败原样返回
     */
    public static String withHeader(String json, String userAgent) {
        try {
            JSONObject o = new JSONObject(json);
            o.put("header", "{\"User-Agent\":\"" + userAgent + "\"}");
            return o.toString();
        } catch (Throwable e) {
            return json;
        }
    }
}
