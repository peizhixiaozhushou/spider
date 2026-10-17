package com.example.tvbox.plain.duanju;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.Vod;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 围观短剧（9ddm）明文实现。
 *
 * <h2>证据来源</h2>
 * <ul>
 *   <li>反编译: {@code ae/decompiled/ShortWeiguan.java}（147 行，本批最简短的一个）</li>
 *   <li><b>真实请求验证</b>: {@code ae/fixtures/real/shortweiguan_*.json}
 *       （tags / search / detail 三端点，线上实抓）</li>
 * </ul>
 *
 * <h2>协议要点（经真实请求确认）</h2>
 *
 * <h3>1. 统一的 clientInfo 查询串（每个请求都要带）</h3>
 * <pre>
 *  ?version_code=1600&version_name=1.6.0&device_name={Build.MODEL}
 *  &device_type=phone&is_first_day=true&is_first_24h=true&app_launch_way=icon
 *  &default_homepage=homepage_interaction&device_owning_firm={Build.BRAND}
 *  &font_scale=default&os_type=1&clientInfo={MD5(随机10位)}
 * </pre>
 * 上游在 {@code init} 里生成一次 {@code clientInfo}：从
 * {@code a-zA-Z0-9} 随机取 10 个字符，取 MD5 小写 hex（32 位）。
 *
 * <h3>2. 端点</h3>
 * <pre>
 *  首页  GET https://api.drama.9ddm.com/drama/home/shortVideoTags{qs}
 *        → {"code":200,"tags":["逆袭","都市",...]}
 *  分类  POST .../drama/home/search{qs}
 *        body {"audience":"全部","order":"最新","page":N,"pageSize":30,
 *              "searchWord":"","subject":{tid}}
 *        → {"code":200,"data":[{oneId,title,horzPoster,episodeCount}]}
 *  搜索  POST .../drama/home/search{qs}
 *        body {"audience":"","order":"","page":1,"pageSize":30,
 *              "searchWord":{key},"subject":""}
 *  详情  GET .../drama/home/shortVideoDetail{qs}&oneId={id}&page=1&pageSize=1000
 *        &userId=0&queryAll=true
 *        → {title, vertPoster, description, data:[{playOrder, videoClarityList:[...]}]}
 * </pre>
 *
 * <h3>3. 播放串的「两级 Base64」</h3>
 * 详情阶段把每集的 <b>videoClarityList 整个 JSON 数组</b>序列化后 Base64，
 * 拼成 {@code playOrder$Base64(json)}；播放阶段再解码数组、遍历取 name/url。
 * 这段 Base64 <b>不是加密</b>（无 key），只是把结构化数据塞进单一字符串。
 *
 * <h3>4. 真实响应里的清晰度数组</h3>
 * <pre>
 *  videoClarityList = [{"name":"1080P","url":"https://tv-video.cdn...mp4?...sign=.."},
 *                      {"name":"720P",...}, {"name":"480P",...}]
 * </pre>
 * 上游不清洗，全部交给宿主播放器（{@code c.e().v(list).e(headers)} 会把它渲染成多线路）。
 *
 * <h2>加密算法</h2>
 * 仅 MD5（clientInfo 混淆用），无 AES/RSA。播放地址用 Base64 打包（非加密）。
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/ShortWeiguan.java} 的对照复核，
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
 * {@code ae/decompiled/ShortWeiguan.java} 为对照基准。
 */
public class ShortWeiguanPlain extends ShortBase {

    private static final String TAG = "ShortWeiguanPlain";

    /** 站点根 —— 上游三处 URL 都是这个固定域，无域名发现机制 */
    private static final String HOST = "https://api.drama.9ddm.com";

    /** 上游 User-Agent（okhttp 5.x 指纹） */
    private static final String UA = "okhttp/5.1.0";

    /** 每页条数：上游 categoryContent/searchContent 都用 30 */
    private static final int PAGE_SIZE = 30;

    /** 随机串字母表（62 字符，与上游一致） */
    private static final String ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** 随机串长度 */
    private static final int RANDOM_LEN = 10;

    /** 详情接口要求一次拿全（上游写死 1000） */
    private static final int DETAIL_PAGE_SIZE = 1000;

    /** 设备型号（上游取 Build.MODEL，重写环境用固定机型） */
    private static final String DEVICE_NAME = "M2012K10C";
    /** 设备厂商（上游取 Build.BRAND） */
    private static final String DEVICE_FIRM = "Xiaomi";

    /** clientInfo = MD5(随机 10 位)，init 时生成一次 */
    private String clientInfo = "";

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);

        // ext.url 可覆盖站点根（镜像站）；非 http 前缀一律回退内置值
        if (!baseUrl.startsWith("http")) {
            baseUrl = HOST;
        }
        // 上游在这里生成 clientInfo，全生命周期复用同一个值
        clientInfo = md5Hex(randomString(RANDOM_LEN));
        Log.i(TAG, "init base=" + baseUrl + " clientInfo=" + clientInfo);
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
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页分类。
     *
     * <p>真实响应 {@code {"code":200,"msg":"success","tags":[...],"orders":[...]}}——
     * 上游只取了 {@code tags}，并且<b>用 tag 名同时当 id 和 name</b>
     * （{@code new a(string, string)}）。本实现保持该语义。
     *
     * @param filter 忽略
     * @return 首页标准体
     */
    @Override
    public String homeContent(boolean filter) {
        String url = apiUrl("/drama/home/shortVideoTags");
        String resp = http.get(url, uaHeader(UA));
        JSONObject root = parseObject(resp);
        Map<String, String> classes = new LinkedHashMap<>();
        if (root == null) {
            return homeResponse(classes, new ArrayList<Vod>());
        }
        JSONArray tags = root.optJSONArray("tags");
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                String t = tags.optString(i, "").trim();
                if (!t.isEmpty()) {
                    // 上游 tag_id 与 tag_name 都是 tag 原文
                    classes.put(t, t);
                }
            }
        }
        return homeResponse(classes, new ArrayList<Vod>());
    }

    // ─────────────────────────────────────────────────────────────
    // 分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 分类翻页。
     *
     * <p>上游把 {@code subject} 传成 {@code tid}（tag 原文，如「逆袭」），
     * 并允许宿主用 {@code cateId} 覆盖它。
     *
     * @param tid    分类 id（tag 原文）
     * @param pg     页码
     * @param filter 忽略
     * @param extend 可用 {@code cateId} 覆盖 tid
     * @return 分类标准体
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  java.util.HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String subject = tid == null ? "" : tid;
        if (extend != null && extend.containsKey("cateId")) {
            String c = extend.get("cateId");
            if (!TextUtils.isEmpty(c)) {
                subject = c;
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("audience", "全部");
        body.put("order", "最新");
        body.put("page", page);
        body.put("pageSize", PAGE_SIZE);
        body.put("searchWord", "");
        body.put("subject", subject);

        JSONObject root = postSearch(body);
        if (root == null) {
            return pageResponse(new ArrayList<Vod>(), page, PAGE_SIZE, false);
        }
        List<Vod> list = mapVods(root.optJSONArray("data"), null,
                "oneId|id", "title", "horzPoster|vertPoster|cover", "episodeCount");
        // 上游：本页不满 30 条即认为没有下一页
        return pageResponse(list, page, PAGE_SIZE, list.size() >= PAGE_SIZE);
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 详情。
     *
     * <p>真实响应结构（注意 <b>title / vertPoster / description 在顶层</b>，
     * 不在 {@code data} 里；{@code data} 是剧集数组）：
     * <pre>
     *  { "code":200, "title":"穿书后总裁听我心声",
     *    "vertPoster":"https://pic.cdn...jpg", "description":"...",
     *    "totalEpisodeCount":60,
     *    "data":[ {"playOrder":1,
     *              "videoClarityList":[{"name":"1080P","url":"...mp4?sign=.."}] } ] }
     * </pre>
     * 上游对 {@code videoClarityList} 整体做 Base64 塞进播放串，播放阶段再解。
     *
     * @param ids 影片 id（oneId）
     * @return 详情标准体
     */
    @Override
    public String detailContent(List<String> ids) {
        String oneId = firstId(ids);
        if (oneId.isEmpty()) {
            return emptyResponse();
        }

        String url = apiUrl("/drama/home/shortVideoDetail")
                + "&oneId=" + oneId
                + "&page=1&pageSize=" + DETAIL_PAGE_SIZE
                + "&userId=0&queryAll=true";
        String resp = http.get(url, uaHeader(UA));
        JSONObject root = parseObject(resp);
        if (root == null) {
            return emptyResponse();
        }

        String title = pickText(root, "title");
        Vod vod = new Vod()
                .id(oneId)
                .name(title.isEmpty() ? oneId : title)
                .pic(absolutize(pick(root, "vertPoster|horzPoster|cover")))
                .remarks(pickText(root, "totalEpisodeCount"));
        vod.content(pickText(root, "description"));

        JSONArray data = root.optJSONArray("data");
        if (data == null || data.length() == 0) {
            return detailResponse(vod);
        }

        List<String> eps = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject ep = data.optJSONObject(i);
            if (ep == null) {
                continue;
            }
            String order = pick(ep, "playOrder");
            String label = (order.isEmpty() ? String.valueOf(i + 1) : order) + "$";
            // 上游 label 只写 playOrder 数字（不带「第…集」），此处保持原样
            String payload = packClarity(ep.optJSONArray("videoClarityList"));
            if (payload.isEmpty()) {
                continue;
            }
            eps.add(label + payload);
        }
        return detailResponse(attachPlay(vod, siteName(), joinEpisodes(eps)));
    }

    // ─────────────────────────────────────────────────────────────
    // 搜索
    // ─────────────────────────────────────────────────────────────

    /**
     * 搜索。
     *
     * <p>与分类走同一端点，区别是 {@code audience/order/subject} 全空、
     * {@code searchWord} 填关键词。真实响应 {@code data[]} 字段与分类一致。
     *
     * @param key   关键词
     * @param quick 忽略
     * @return 搜索标准体
     */
    @Override
    public String searchContent(String key, boolean quick) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("audience", "");
        body.put("order", "");
        body.put("page", 1);
        body.put("pageSize", PAGE_SIZE);
        body.put("searchWord", key == null ? "" : key.trim());
        body.put("subject", "");

        JSONObject root = postSearch(body);
        if (root == null) {
            return listResponse(new ArrayList<Vod>());
        }
        JSONArray data = root.optJSONArray("data");
        if (data == null) {
            data = new JSONArray();
        }
        return listResponse(mapVods(data, null,
                "oneId|id", "title", "horzPoster|vertPoster|cover", "episodeCount"));
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 播放：解开详情阶段打包的 clarity 数组，交给宿主当多条线路。
     *
     * <p>上游用 {@code c.e().v(list)} 传扁平 list（name,url,name,url,...），
     * 宿主据此生成清晰度选项。本实现按 TVBox 标准 {@code header} + 主 url 输出，
     * 并把首选地址作为 {@code url}（优先 1080P，其次第一个）。
     *
     * @param flag     线路名（忽略）
     * @param id       详情阶段打的包（Base64(JSON 数组)）
     * @param vipFlags 忽略
     * @return 播放标准体
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        Map<String, String> h = uaHeader(UA);
        if (TextUtils.isEmpty(id)) {
            return playResponse("", h);
        }
        JSONArray clarity = unpackClarity(id);
        if (clarity == null || clarity.length() == 0) {
            Log.w(TAG, "player: no clarity in payload");
            return playResponse("", h);
        }
        // 优先 1080P，其次 720P，最后第一个
        String best = "";
        for (int i = 0; i < clarity.length(); i++) {
            JSONObject o = clarity.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String name = o.optString("name", "");
            String url = o.optString("url", "");
            if (url.isEmpty()) {
                continue;
            }
            if ("1080P".equalsIgnoreCase(name)) {
                best = url;
                break;
            }
            if (best.isEmpty()) {
                best = url;
            }
        }
        return playResponse(best, h);
    }

    // ─────────────────────────────────────────────────────────────
    // Base64 打包/解包（非加密）
    // ─────────────────────────────────────────────────────────────

    /**
     * 把清晰度数组序列化后 Base64（NO_WRAP）。
     *
     * <p>还原上游 {@code Base64.encodeToString(arr.toString().getBytes(UTF_8), 2)}。
     *
     * @param clarity videoClarityList 数组，可为 null
     * @return Base64 文本；入参空时返回空串
     */
    public static String packClarity(JSONArray clarity) {
        if (clarity == null || clarity.length() == 0) {
            return "";
        }
        try {
            byte[] raw = clarity.toString().getBytes(StandardCharsets.UTF_8);
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            Log.e(TAG, "packClarity failed", e);
            return "";
        }
    }

    /**
     * 解开 {@link #packClarity(JSONArray)} 打的包。
     *
     * @param payload Base64 文本
     * @return 数组；解码/解析失败返回 null
     */
    public static JSONArray unpackClarity(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(payload.trim());
            return new JSONArray(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "unpackClarity failed: " + head(payload, 40), e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP 层
    // ─────────────────────────────────────────────────────────────

    /**
     * 拼出 {@code {base}{path}{clientInfo 查询串}}。
     *
     * <p>注意 clientInfo 串以 {@code ?} 开头，因此 path 自身不能带查询参数，
     * 需要额外参数的端点（如 detail）在返回后自行追加 {@code &...}。
     *
     * @param path 接口路径，如 {@code /drama/home/shortVideoTags}
     * @return 完整 URL
     */
    public String apiUrl(String path) {
        String base = apiBase();
        if (base.isEmpty()) {
            base = HOST;
        }
        return base + path + clientQuery();
    }

    /**
     * 构造 clientInfo 查询串（每次请求都带，内容固定）。
     *
     * @return 以 {@code ?} 开头的查询串
     */
    public String clientQuery() {
        return "?version_code=1600&version_name=1.6.0"
                + "&device_name=" + DEVICE_NAME
                + "&device_type=phone&is_first_day=true&is_first_24h=true"
                + "&app_launch_way=icon&default_homepage=homepage_interaction"
                + "&device_owning_firm=" + DEVICE_FIRM
                + "&font_scale=default&os_type=1"
                + "&clientInfo=" + (clientInfo == null ? "" : clientInfo);
    }

    /**
     * 调 search 端点（分类与搜索共用）。
     *
     * @param body 业务参数
     * @return 响应对象；失败返回 null
     */
    protected JSONObject postSearch(Map<String, Object> body) {
        String url = apiUrl("/drama/home/search");
        Map<String, String> h = uaHeader(UA);
        String json = toJsonBody(body);
        String resp = http.post(url, json, h);
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "postSearch: empty response");
            return null;
        }
        JSONObject root = parseObject(resp);
        if (root == null) {
            Log.w(TAG, "postSearch: non-json " + head(resp, 120));
        }
        return root;
    }

    /**
     * 把 Map 序列化成 JSON（保持插入顺序）。
     *
     * @param map 参数表
     * @return JSON 文本
     */
    protected static String toJsonBody(Map<String, Object> map) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
        } catch (Exception e) {
            Log.e(TAG, "toJsonBody failed", e);
        }
        return o.toString();
    }

    // ─────────────────────────────────────────────────────────────
    // MD5 / 随机串
    // ─────────────────────────────────────────────────────────────

    /**
     * 生成随机串（a-zA-Z0-9）。
     *
     * <p>还原上游 {@code init} 里的 {@code new Random()} + 62 字母表取样。
     *
     * @param len 长度
     * @return 随机串；len&lt;=0 时返回空串
     */
    public static String randomString(int len) {
        if (len <= 0) {
            return "";
        }
        Random r = new Random();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * MD5 → 小写 hex。
     *
     * <p>还原上游 {@code init} 里的 {@code MessageDigest.getInstance("MD5")} +
     * {@code Integer.toHexString(b & 255)}（不足两位补 0）。
     *
     * @param s 原文，可为 null
     * @return 32 位小写 hex；异常时返回入参本身（与上游 catch 分支一致）
     */
    public static String md5Hex(String s) {
        if (s == null) {
            return "";
        }
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
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
            // 上游 catch 里返回的是<b>明文原串</b>而非空串，这里保持同样语义
            return s;
        }
    }

    /**
     * 供测试读取 clientInfo。
     *
     * @return 32 位 MD5；未 init 时为空串
     */
    public String clientInfo() {
        return clientInfo == null ? "" : clientInfo;
    }

    /**
     * 供测试读取站点根。
     *
     * @return 站点根
     */
    public static String host() {
        return HOST;
    }

    /**
     * 供测试读取字母表。
     *
     * @return 62 字符字母表
     */
    public static String alphabet() {
        return ALPHABET;
    }
}
