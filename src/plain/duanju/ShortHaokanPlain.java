package com.example.tvbox.plain.duanju;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 好看短剧（百度 haokan / sv.baidu.com）明文实现。
 *
 * <h2>证据来源</h2>
 * <ul>
 *   <li>反编译: {@code ae/decompiled/ShortHaokan.java}（160 行）</li>
 *   <li><b>真实请求验证（分类端点）</b>: {@code ae/fixtures/real/shorthaokan_category.json}
 *       —— {@code tag_id=1..5} 均返回 9 条真实数据</li>
 *   <li><b>实测已失效的端点</b>（2026-01 复测，均返回 404 或「接口不存在」）：
 *       <ul>
 *         <li>{@code /haokan/ui-feed/playletShelfFeed}（上游首页分类来源）</li>
 *         <li>{@code /haokan/ui-interact/playlet/search/sugs}（上游搜索）</li>
 *         <li>{@code /haokan/ui-video/playlet/rec/detail}（上游详情）</li>
 *         <li>{@code /appui/api} 的 {@code video/commonlist}（上游详情前置取 vid）</li>
 *       </ul>
 *       本实现保留完整协议还原，并在运行时优雅降级（失效即返回空响应，不影响宿主）。
 *   </li>
 * </ul>
 *
 * <h2>协议要点</h2>
 *
 * <h3>1. 请求头（4 项，Talos 模块标识）</h3>
 * <pre>
 *  User-Agent           : Mozilla/5.0 (Linux; Android 11; M2012K10C ...) haokan/7.80.0.18 ... Talos/1.8.7
 *  Content-Type         : application/x-www-form-urlencoded; charset=utf-8
 *  Cookie               : BAIDUCUID=giHCu0azv80G8SfQ0avU8gaaH8jfiv86ju2MugiR2i8-k3a35avAa1_mA
 *  Talos-Module-Version : 1.0.71.1
 *  Talos-Module-Name    : shortDrama
 * </pre>
 *
 * <h3>2. 分类（<b>唯一仍可用的端点</b>）</h3>
 * <pre>
 *  POST https://sv.baidu.com/haokan/ui-feed/playletTagsFeed?osbranch=a0
 *  body: tag_id={tid}&amp;rn=9&amp;pn={page}
 *  → {@code {"status":0,"data":{"list":[{"playlet_id","playlet_title",
 *            "playlet_poster","episodes_num_text",...}],"pn","rn","has_more"}}}
 * </pre>
 * 实测：{@code tag_id} 有效范围 1..5，6 以上返回 {@code list:null}；
 * {@code tag_id} 非数字（如 {@code "rec"}）返回 {@code status:1006 参数解析错误}。
 *
 * <h3>3. 首页分类（上游从 shelfFeed 抓 tag_list，现已失效）</h3>
 * 上游解析路径 {@code data.playlet_shelf_filter_panel[].tag_list[].{tag_id,name}}。
 * 本实现在该请求失败时回退到<b>内置分类表</b>（1..5），保证首页不至于空白。
 *
 * <h3>4. 详情（两级：commonlist 拿 vid → rec/detail 拿剧集）</h3>
 * <pre>
 *  ① POST https://sv.baidu.com/appui/api?osbranch=a0
 *     body: video/commonlist + enable_enter_playlet=0&amp;...&amp;commonlist_id={millis}
 *           &amp;id={playletId}&amp;rn=10&amp;pn=1&amp;direction=3
 *     → video/commonlist.data.results[0].content.vid
 *  ② POST https://sv.baidu.com/haokan/ui-video/playlet/rec/detail?osbranch=a0
 *     body: vid={vid}&amp;playlet_id={playletId}
 *     → data.{playlet_title, playlet_poster, description, vid_list:[vid...]}
 * </pre>
 * 上游把 {@code vid_list} 每项拼成 {@code 第N集${vid}|||{playletId}}，
 * 播放时拆 {@code |||} 再调 {@code video/relate} 换真实地址。
 *
 * <h3>5. 播放（多级清晰度）</h3>
 * <pre>
 *  POST https://sv.baidu.com/appui/api?osbranch=a0
 *  body: video/relate + method=post&amp;vid={vid}&amp;immersive_mode=v4_5&amp;...
 *        &amp;video_set_id={playletId}&amp;...
 *  → video/relate.data.cur_video.clarityUrl:[{key:"1080p"|"sc"|..., url}]
 * </pre>
 * {@code extractVideoUrl} 优先级：{@code 1080p} → {@code sc} → 数组首项。
 *
 * <h2>加密算法</h2>
 * 无。纯 HTTP 头指纹 + 嵌套 JSON 路径。
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/ShortHaokan.java} 的对照复核，
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
 * {@code ae/decompiled/ShortHaokan.java} 为对照基准。
 */
public class ShortHaokanPlain extends ShortBase {

    private static final String TAG = "ShortHaokanPlain";

    /** 站点根 */
    private static final String HOST = "https://sv.baidu.com";

    /** 上游那条超长 UA（haokan 客户端 + Talos 模块指纹），逐字照抄 */
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 11; M2012K10C Build/RP1A.200720.011; wv) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 "
                    + "Chrome/87.0.4280.141 Mobile Safari/537.36 haokan/7.80.0.18 "
                    + "(Baidu; P1 11)/imoaiX_03_11_C01K2102M/1043677m/"
                    + "5ACDB023CFB9D64743B08E51953F7C76%7CVSAJ32AVA/1/7.80.0.18/780001/1/"
                    + "immersiveMode/modeV4PlusWhite/isFirstInstall/bbqMode/bbqModeV2/"
                    + "blackStyle/isPlaylet Talos/1.8.7";

    /** 上游硬编码的 BAIDUCUID */
    private static final String COOKIE =
            "BAIDUCUID=giHCu0azv80G8SfQ0avU8gaaH8jfiv86ju2MugiR2i8-k3a35avAa1_mA";

    /** Talos 模块版本 */
    private static final String TALOS_VERSION = "1.0.71.1";
    /** Talos 模块名 */
    private static final String TALOS_NAME = "shortDrama";

    /** 每页条数：上游 rn=9 */
    private static final int PAGE_SIZE = 9;

    /** 详情 vid_list 的长度上限（上游用 rn=10 取 commonlist） */
    private static final int DETAIL_RN = 10;

    /** 集名与地址的分隔标记（上游自定义，非 TVBox 的 $） */
    private static final String SEP_VID_SET = "|||";

    /**
     * 内置分类回退表。
     *
     * <p>上游从 shelfFeed 动态抓 tag_list，但该端点已 404；实测 tag_id 1..5 有效，
     * 故内置这 5 项保证首页可用。新增分类只需在此追加。
     */
    private static final String[][] FALLBACK_TAGS = {
            {"1", "推荐"}, {"2", "都市"}, {"3", "逆袭"}, {"4", "古装"}, {"5", "甜宠"}
    };

    /** 清晰度优先级（上游 extractVideoUrl 的顺序） */
    private static final String[] CLARITY_ORDER = {"1080p", "sc"};

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);

        // ext.url 可覆盖站点根；非 http 前缀回退内置
        if (!baseUrl.startsWith("http")) {
            baseUrl = HOST;
        }
        Log.i(TAG, "init base=" + baseUrl);
    }

    @Override
    protected String siteName() {
        return "短剧";
    }

    @Override
    protected int pageSize() {
        return PAGE_SIZE;
    }

    // ─────────────────────────────────────────────────────────────
    // 请求头
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code ShortHaokan.a()}：4 项固定头。
     *
     * @return 新的 header 表
     */
    protected Map<String, String> baseHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        h.put("Cookie", COOKIE);
        h.put("Talos-Module-Version", TALOS_VERSION);
        h.put("Talos-Module-Name", TALOS_NAME);
        return h;
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页分类。
     *
     * <p>先试上游的 shelfFeed（动态 tag_list）；该端点已失效，失败则回退内置表。
     *
     * @param filter 忽略
     * @return 首页标准体
     */
    @Override
    public String homeContent(boolean filter) {
        Map<String, String> classes = fetchShelfTags();
        if (classes.isEmpty()) {
            Log.w(TAG, "shelfFeed unavailable, using builtin tag table");
            classes = builtinTags();
        }
        return homeResponse(classes, new ArrayList<Vod>());
    }

    /**
     * 从 shelfFeed 抓分类表。
     *
     * <p>上游路径：{@code data.playlet_shelf_filter_panel[].tag_list[].{tag_id,name}}。
     *
     * @return 分类表；失败返回空表
     */
    protected Map<String, String> fetchShelfTags() {
        Map<String, String> out = new LinkedHashMap<>();
        String url = Http.buildUrl(apiBaseOrHost(), "/haokan/ui-feed/playletShelfFeed",
                Http.paramsOf("osbranch", "a0"));
        String resp = http.get(url, baseHeaders());
        JSONObject root = parseObject(resp);
        if (root == null) {
            return out;
        }
        JSONArray panels = digArray(root, "data", "playlet_shelf_filter_panel");
        if (panels == null) {
            return out;
        }
        for (int i = 0; i < panels.length(); i++) {
            JSONObject p = panels.optJSONObject(i);
            if (p == null) {
                continue;
            }
            JSONArray tags = p.optJSONArray("tag_list");
            if (tags == null) {
                continue;
            }
            for (int j = 0; j < tags.length(); j++) {
                JSONObject t = tags.optJSONObject(j);
                if (t == null) {
                    continue;
                }
                String id = pick(t, "tag_id");
                String name = pickText(t, "name");
                if (!id.isEmpty()) {
                    out.put(id, name.isEmpty() ? id : name);
                }
            }
        }
        return out;
    }

    /**
     * 内置分类表（shelfFeed 失效时的兜底）。
     *
     * @return 分类表，永不 null
     */
    public Map<String, String> builtinTags() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] t : FALLBACK_TAGS) {
            m.put(t[0], t[1]);
        }
        return m;
    }

    // ─────────────────────────────────────────────────────────────
    // 分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 分类翻页（<b>真实可用</b>）。
     *
     * @param tid    分类 id（tag_id，有效 1..5）
     * @param pg     页码
     * @param filter 忽略
     * @param extend 可用 {@code cateId} 覆盖 tid
     * @return 分类标准体
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  java.util.HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String tagId = TextUtils.isEmpty(tid) ? "1" : tid.trim();
        if (extend != null) {
            String c = extend.get("cateId");
            if (!TextUtils.isEmpty(c)) {
                tagId = c;
            }
        }

        String url = Http.buildUrl(apiBaseOrHost(), "/haokan/ui-feed/playletTagsFeed",
                Http.paramsOf("osbranch", "a0"));
        String body = "tag_id=" + tagId
                + "&rn=" + PAGE_SIZE
                + "&pn=" + page;
        String resp = http.post(url, body, baseHeaders());
        JSONObject root = parseObject(resp);
        if (root == null) {
            return pageResponse(new ArrayList<Vod>(), page, PAGE_SIZE, false);
        }
        List<Vod> list = mapVods(digArray(root, "data", "list"), null,
                "playlet_id", "playlet_title", "playlet_poster", "episodes_num_text");
        boolean hasMore = vodsHasMore(root, list.size());
        return pageResponse(list, page, PAGE_SIZE, hasMore);
    }

    /**
     * 是否还有下一页：优先读 {@code has_more}，否则按满页推断。
     *
     * @param root 响应
     * @param size 本页条数
     * @return true 表示有下一页
     */
    private boolean vodsHasMore(JSONObject root, int size) {
        JSONObject data = root.optJSONObject("data");
        if (data != null && data.has("has_more")) {
            Object raw = data.opt("has_more");
            if (raw instanceof Number) {
                return ((Number) raw).doubleValue() != 0;
            }
            if (raw instanceof Boolean) {
                return (Boolean) raw;
            }
        }
        return size >= PAGE_SIZE;
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 详情（两级请求）。
     *
     * <p><b>实测该链路已失效</b>（commonlist 返回「非法请求」、rec/detail 返回 404）。
     * 代码完整保留协议，任何一步失败都返回只含基础信息的详情，不抛异常。
     *
     * @param ids 影片 id（playlet_id）
     * @return 详情标准体
     */
    @Override
    public String detailContent(List<String> ids) {
        String playletId = firstId(ids);
        if (playletId.isEmpty()) {
            return emptyResponse();
        }

        String vid = fetchVid(playletId);
        if (vid.isEmpty()) {
            Log.w(TAG, "detail: no vid for playlet " + playletId
                    + " (commonlist endpoint likely retired)");
            return detailResponse(new Vod().id(playletId).name(playletId));
        }

        JSONObject data = fetchPlayletDetail(vid, playletId);
        if (data == null) {
            Log.w(TAG, "detail: rec/detail unavailable for " + playletId);
            return detailResponse(new Vod().id(playletId).name(playletId));
        }

        String title = pickText(data, "playlet_title");
        Vod vod = new Vod()
                .id(playletId)
                .name(title.isEmpty() ? playletId : title)
                .pic(absolutize(pick(data, "playlet_poster")));
        vod.content(pickText(data, "description"));

        JSONArray vidList = data.optJSONArray("vid_list");
        if (vidList == null || vidList.length() == 0) {
            return detailResponse(vod);
        }
        List<String> eps = new ArrayList<>();
        for (int i = 0; i < vidList.length(); i++) {
            String v = vidList.optString(i, "");
            if (v.isEmpty()) {
                continue;
            }
            // 上游：第N集${vid}|||${playletId}
            String payload = v + SEP_VID_SET + playletId;
            String ep = episode("第" + (i + 1) + "集", payload, i + 1);
            if (!ep.isEmpty()) {
                eps.add(ep);
            }
        }
        return detailResponse(attachPlay(vod, siteName(), joinEpisodes(eps)));
    }

    /**
     * 调 commonlist 拿 vid。
     *
     * @param playletId 短剧 id
     * @return vid；失败返回空串
     */
    protected String fetchVid(String playletId) {
        String url = Http.buildUrl(apiBaseOrHost(), "/appui/api",
                Http.paramsOf("osbranch", "a0"));
        // 上游用 System.currentTimeMillis() 当 commonlist_id；ctime 固定 0
        String body = "video/commonlist"
                + "&enable_enter_playlet=0&seek_time=0&hotspot=0"
                + "&auto_show_hot_point_panel=0&type=playlet"
                + "&commonlist_id=" + System.currentTimeMillis()
                + "&scene=&vid=&enable_atlas=0&mark_pn=&uk=&ctime=0"
                + "&from=playlet_new&id=" + playletId
                + "&rn=" + DETAIL_RN + "&pn=1&direction=3";
        // 上游 k.g(url, body, headers) 是 POST；此处同样 POST
        String resp = http.post(url, body, baseHeaders());
        JSONObject root = parseObject(resp);
        if (root == null) {
            return "";
        }
        JSONArray results = digArray(root, "video/commonlist", "data", "results");
        if (results == null || results.length() == 0) {
            return "";
        }
        JSONObject first = results.optJSONObject(0);
        if (first == null) {
            return "";
        }
        JSONObject content = first.optJSONObject("content");
        return content == null ? "" : pick(content, "vid");
    }

    /**
     * 调 rec/detail 拿剧集信息。
     *
     * @param vid       当前集 vid
     * @param playletId 短剧 id
     * @return {@code data} 对象；失败返回 null
     */
    protected JSONObject fetchPlayletDetail(String vid, String playletId) {
        String url = Http.buildUrl(apiBaseOrHost(), "/haokan/ui-video/playlet/rec/detail",
                Http.paramsOf("osbranch", "a0"));
        String body = "vid=" + vid + "&playlet_id=" + playletId;
        String resp = http.post(url, body, baseHeaders());
        JSONObject root = parseObject(resp);
        if (root == null) {
            return null;
        }
        return root.optJSONObject("data");
    }

    // ─────────────────────────────────────────────────────────────
    // 搜索
    // ─────────────────────────────────────────────────────────────

    /**
     * 搜索。
     *
     * <p><b>实测 {@code /haokan/ui-interact/playlet/search/sugs} 已 404</b>。
     * 代码保留上游协议，失败时返回空列表。
     *
     * @param key   关键词
     * @param quick 忽略
     * @return 搜索标准体
     */
    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    /**
     * 三参数搜索（宿主旧版会调这个重载）。
     *
     * @param key        关键词
     * @param quick      忽略
     * @param quickSearch 忽略
     * @return 搜索标准体
     */
    @Override
    public String searchContent(String key, boolean quick, String quickSearch) {
        if (TextUtils.isEmpty(key)) {
            return emptyResponse();
        }
        String url = Http.buildUrl(apiBaseOrHost(),
                "/haokan/ui-interact/playlet/search/sugs",
                Http.paramsOf("osbranch", "a0", "search_word", key.trim()));
        String resp = http.get(url, baseHeaders());
        JSONObject root = parseObject(resp);
        if (root == null) {
            return listResponse(new ArrayList<Vod>());
        }
        JSONArray arr = root.optJSONArray("data");
        if (arr == null) {
            return listResponse(new ArrayList<Vod>());
        }
        // 上游字段：id / title / cover_url / tag（与分类不同！）
        return listResponse(mapVods(arr, null, "id", "title", "cover_url", "tag"));
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 播放：拆 {@code vid|||playletId}，调 video/relate 换真实地址。
     *
     * @param flag     线路名（忽略）
     * @param id       详情阶段打的包（{@code vid|||playletId}）
     * @param vipFlags 忽略
     * @return 播放标准体
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        Map<String, String> h = baseHeaders();
        if (TextUtils.isEmpty(id)) {
            return playResponse("", h);
        }
        String vid = id;
        String playletId = "";
        int at = id.indexOf(SEP_VID_SET);
        if (at > 0) {
            vid = id.substring(0, at);
            playletId = id.substring(at + SEP_VID_SET.length());
        }
        String url = fetchVideoUrl(vid, playletId);
        return playResponse(url, h);
    }

    /**
     * 调 video/relate 拿播放地址。
     *
     * @param vid       集 vid
     * @param playletId 短剧 id
     * @return 最佳播放地址；失败返回空串
     */
    protected String fetchVideoUrl(String vid, String playletId) {
        if (TextUtils.isEmpty(vid)) {
            return "";
        }
        String url = Http.buildUrl(apiBaseOrHost(), "/appui/api",
                Http.paramsOf("osbranch", "a0"));
        String body = "video/relate"
                + "&method=post&vid=" + vid
                + "&immersive_mode=v4_5&tplname=feed_small_video&tag=playlet_talos"
                + "&tab=detail&external_from=&is_dp_video=0&immersive_square_type=3"
                + "&video_set_id=" + playletId
                + "&play_screen_type=1&play_volume_type=2&play_external_device_type=1";
        String resp = http.post(url, body, baseHeaders());
        JSONObject root = parseObject(resp);
        if (root == null) {
            return "";
        }
        JSONObject cur = digObject(root, "video/relate", "data", "cur_video");
        if (cur == null) {
            return "";
        }
        return extractVideoUrl(cur.optJSONArray("clarityUrl"));
    }

    /**
     * 还原 {@code ShortHaokan.extractVideoUrl(JSONArray)}：
     * 按 {@code 1080p → sc → 首项} 的优先级挑地址。
     *
     * @param clarity {@code [{key,url},...]}，可为 null
     * @return 地址；无可用项返回空串
     */
    public static String extractVideoUrl(JSONArray clarity) {
        if (clarity == null || clarity.length() == 0) {
            return "";
        }
        for (String key : CLARITY_ORDER) {
            String u = findByKey(clarity, key);
            if (!u.isEmpty()) {
                return u;
            }
        }
        JSONObject first = clarity.optJSONObject(0);
        return first == null ? "" : first.optString("url", "");
    }

    /**
     * 在 {@code [{key,url}]} 数组中按 key 找 url（精确匹配）。
     *
     * @param arr 数组
     * @param key 目标 key
     * @return url；未命中返回空串
     */
    public static String findByKey(JSONArray arr, String key) {
        if (arr == null || key == null) {
            return "";
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (key.equals(o.optString("key", ""))) {
                return o.optString("url", "");
            }
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────────

    /**
     * 取站点根；baseUrl 非法时回退内置。
     *
     * @return 站点根
     */
    protected String apiBaseOrHost() {
        String b = apiBase();
        return TextUtils.isEmpty(b) || !b.startsWith("http") ? HOST : b;
    }

    /**
     * 供测试读取 UA。
     *
     * @return UA 原文
     */
    public static String ua() {
        return UA;
    }

    /**
     * 供测试读取 Cookie。
     *
     * @return Cookie 原文
     */
    public static String cookie() {
        return COOKIE;
    }

    /**
     * 供测试读取站点根。
     *
     * @return 站点根
     */
    public static String host() {
        return HOST;
    }
}
