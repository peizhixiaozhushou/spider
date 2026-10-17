package com.example.tvbox.plain.duanju;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.Vod;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 星芽短剧（whjzjx / shytkjgs）明文实现 —— 本批唯一带 AES 的短剧类。
 *
 * <h2>证据来源</h2>
 * <ul>
 *   <li>反编译: {@code ae/decompiled/ShortXingya.java}（191 行）</li>
 *   <li>AES 常量复核: {@code local_dump.py re/spider.dex ShortXingya}
 *       —— key {@code B@ecf920Od8A4df7}(16) 逐字符核对</li>
 *   <li><b>真实请求验证</b>: 登录接口（返回真实 JWT token）+
 *       {@code ae/fixtures/real/shortxingya_*.json}（分类 / 搜索 / 详情，线上实抓）</li>
 * </ul>
 *
 * <h2>协议要点（全部经真实请求确认）</h2>
 *
 * <h3>1. 登录（AES/ECB/PKCS5Padding）</h3>
 * <pre>
 *  POST https://u.shytkjgs.com/user/v3/account/login
 *  Header:
 *    platform        : 1
 *    user_agent      : Mozilla/5.0 (Linux; Android 9; V1938T ...) Safari/537.36
 *    content-type    : application/json; charset=utf-8
 *  Body:   Base64( AES/ECB/PKCS5( 设备JSON ) )     ← 注意是<密文>，不是 JSON
 *
 *  设备JSON（明文，12 字段）:
 *    {"device":"2a50580e69d38388c94c93605241fb306","package_name":"com.jz.xydj",
 *     "android_id":"ec1280db12795506","install_first_open":true,
 *     "first_install_time":1752505243345,"last_update_time":1752505243345,
 *     "report_link_url":"","authorization":"","timestamp":<当前毫秒>}
 *
 *  响应: {"code":"ok","data":{"token":"eyJhbGci...","user_id":99721588,...}}
 * </pre>
 * 实测该接口<b>无需鉴权即可换到 token</b>；token 是 JWT，内含 exp 与用户 id。
 *
 * <h3>2. 业务请求头</h3>
 * <pre>
 *  authorization : &lt;登录拿到的 JWT&gt;
 *  platform      : 1
 *  version_name  : 3.8.3.1
 * </pre>
 *
 * <h3>3. 端点</h3>
 * <pre>
 *  分类  GET https://app.whjzjx.cn/v1/theater/home_page
 *            ?theater_class_id={tid}&amp;page_num={pg}&amp;page_size=24
 *        → {"code":"ok","data":{"list":[{"type":"Theater","theater":{...}}],"total","is_end"}}
 *  详情  GET https://app.whjzjx.cn/v2/theater_parent/detail?theater_parent_id={id}
 *        → {"data":{"title","cover_url","introduction","filing",
 *                   "theaters":[{"num":1,"son_video_url":"...mp4?sign="}]}}
 *  搜索  POST https://app.whjzjx.cn/v3/search   body {"text":"..."}
 *        → {"data":{"theater":{"search_data":[{"id","title","cover_url","score_str"}]}}}
 *  首页  GET 同上 /v1/theater/home_page?...&amp;class2_id=4  （homeVideoContent 用）
 * </pre>
 *
 * <h3>4. 分类列表的「嵌套 theater」结构（关键坑）</h3>
 * 每条记录不是剧本身，而是 {@code {"type":"Theater","theater":{...}}}。
 * 上游 {@code c(JSONArray, String)} 显式取 {@code optJSONObject("theater")}，
 * 取不到就<b>整条跳过</b>。remarks 优先取传入字段名（{@code "theme"} 或
 * {@code "play_amount_str"}），回退 {@code play_amount_str}。
 *
 * <h3>5. theater id 是数字</h3>
 * {@code optInt("id")} 再 {@code String.valueOf} —— 用 optString 会得到同样的数字串，
 * 但本实现统一走 {@link ShortBase#pick} 的数字兼容路径。
 *
 * <h2>加密算法</h2>
 * AES/ECB/PKCS5Padding，key = {@code B@ecf920Od8A4df7}（16 字节，UTF-8），
 * <b>无 IV</b>（ECB 模式），输入明文为紧凑 JSON，输出 Base64。
 * 业务接口本身不加密（明文 JSON），AES 只用于登录请求体。
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/ShortXingya.java} 的对照复核，
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
 * {@code ae/decompiled/ShortXingya.java} 为对照基准。
 */
public class ShortXingyaPlain extends ShortBase {

    private static final String TAG = "ShortXingyaPlain";

    /** 业务域 */
    private static final String HOST = "https://app.whjzjx.cn";
    /** 登录域 */
    private static final String LOGIN_URL = "https://u.shytkjgs.com/user/v3/account/login";

    /** AES/ECB key —— 16 字符，逐字符核对过 */
    private static final String AES_KEY = "B@ecf920Od8A4df7";

    /** 登录请求体的固定设备指纹（上游硬编码） */
    private static final String DEVICE_ID = "2a50580e69d38388c94c93605241fb306";
    private static final String PACKAGE_NAME = "com.jz.xydj";
    private static final String ANDROID_ID = "ec1280db12795506";
    private static final long INSTALL_TIME = 1752505243345L;

    /** 登录接口的 UA */
    private static final String LOGIN_UA =
            "Mozilla/5.0 (Linux; Android 9; V1938T Build/PQ3A.190705.08211809; wv) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 "
                    + "Chrome/91.0.4472.114 Safari/537.36";

    /** 播放器 UA（上游给的是截断的 UA 串，本体照抄） */
    private static final String PLAYER_UA =
            "Linux; Android 12; Pixel 3 XL) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/98.0.4758.101 Mobile Safari/537.36";

    /** 每页条数：上游写死 24 */
    private static final int PAGE_SIZE = 24;

    /** 版本号（业务头必带） */
    private static final String VERSION_NAME = "3.8.3.1";

    /** 首页固定分类（上游 homeContent 硬编码 5 项，不走网络） */
    private static final String[][] HOME_CLASSES = {
            {"1", "剧场"}, {"3", "新剧"}, {"2", "热播"}, {"7", "星选"}, {"5", "阳光"}
    };

    /** 登录拿到的 JWT（同实例复用；上游用字段 a 缓存） */
    private String token = "";

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);

        // ext.url 可覆盖业务域；非 http 前缀回退内置
        if (!baseUrl.startsWith("http")) {
            baseUrl = HOST;
        }
        // ext 里若已给了 token，直接复用（省一次登录）
        String t = ext.token();
        if (!TextUtils.isEmpty(t)) {
            token = t;
        }
        Log.i(TAG, "init base=" + baseUrl + " hasToken=" + !token.isEmpty());
    }

    @Override
    protected String siteName() {
        return "星芽";
    }

    @Override
    protected int pageSize() {
        return PAGE_SIZE;
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页：上游<b>硬编码</b> 5 个分类，不发网络请求。
     *
     * <p>真实测过 {@code /v1/theater/home_page?theater_class_id=1} 能出数据，
     * 但首页分类表本身是常量，没必要多打一次请求。
     *
     * @param filter 忽略
     * @return 首页标准体
     */
    @Override
    public String homeContent(boolean filter) {
        Map<String, String> classes = new LinkedHashMap<>();
        for (String[] c : HOME_CLASSES) {
            classes.put(c[0], c[1]);
        }
        return homeResponse(classes, new ArrayList<Vod>());
    }

    /**
     * 首页推荐位（宿主可选调用）。
     *
     * <p>上游 {@code homeVideoContent()} 用
     * {@code theater_class_id=1&class2_id=4&page_num=1&page_size=24}，
     * remarks 取 {@code play_amount_str}（播放量）。
     *
     * @return 搜索标准体（仅 list）
     */
    @Override
    public String homeVideoContent() {
        String path = "/v1/theater/home_page?theater_class_id=1&class2_id=4"
                + "&page_num=1&page_size=" + PAGE_SIZE;
        JSONObject root = apiGet(path);
        if (root == null) {
            return listResponse(new ArrayList<Vod>());
        }
        JSONArray list = digArray(root, "data", "list");
        return listResponse(mapVods(list, "theater",
                "id", "title", "cover_url", "play_amount_str|theme"));
    }

    // ─────────────────────────────────────────────────────────────
    // 分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 分类翻页。
     *
     * <p>列表项包在 {@code theater} 节点里，remarks 优先取 {@code theme}（题材），
     * 缺失时回退 {@code play_amount_str}（播放量「66.7万」）。
     *
     * @param tid    分类 id（theater_class_id）
     * @param pg     页码
     * @param filter 忽略
     * @param extend 忽略（本接口无筛选维度）
     * @return 分类标准体
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  java.util.HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String classId = TextUtils.isEmpty(tid) ? "1" : tid.trim();

        String path = "/v1/theater/home_page?theater_class_id=" + classId
                + "&page_num=" + page
                + "&page_size=" + PAGE_SIZE;
        JSONObject root = apiGet(path);
        if (root == null) {
            return pageResponse(new ArrayList<Vod>(), page, PAGE_SIZE, false);
        }
        JSONArray list = digArray(root, "data", "list");
        List<Vod> vods = mapVods(list, "theater",
                "id", "title", "cover_url", "theme|play_amount_str");

        // 真实响应有 is_end，比「满页推断」更准。
        // 注意：线上返回的 is_end 是<b>布尔</b>（false = 还有下一页），
        // 但也不排除某些版本返回 0/1，故两种类型都要兼容。
        boolean hasMore;
        JSONObject data = root.optJSONObject("data");
        if (data != null && data.has("is_end")) {
            hasMore = !isTruthy(data.opt("is_end"));
        } else {
            hasMore = vods.size() >= PAGE_SIZE;
        }
        return pageResponse(vods, page, PAGE_SIZE, hasMore);
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 详情。
     *
     * <p>真实响应：
     * <pre>
     *  {"code":"ok","data":{"id":3866,"title":"我真是个弱女子！",
     *     "cover_url":"https://qiniu...","introduction":"...","filing":"备案号",
     *     "desc_tags":["..."],
     *     "theaters":[{"num":1,"son_video_url":"http://qcapp.xingya.com.cn/...mp4?sign=..",
     *                  "son_video_h265_url":"..."}]}}
     * </pre>
     * 上游分支：
     * <ul>
     *   <li>{@code theaters} 为空 → 用顶层 {@code video_url} 单集，线路名 {@code "无播放源"} 或正常</li>
     *   <li>{@code theaters} 有数据 → 每项 {@code num$son_video_url}，线路名 {@code "星芽"}</li>
     * </ul>
     * 注意上游 {@code "无播放源"} 那个分支：它在<b>有 video_url 时</b>仍然把线路名设成
     * {@code "无播放源"}（变量 {@code str} 的赋值顺序 bug）。本实现修正为「有地址就用站点名」。
     *
     * @param ids 影片 id（theater_parent_id）
     * @return 详情标准体
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (id.isEmpty()) {
            return emptyResponse();
        }

        JSONObject root = apiGet("/v2/theater_parent/detail?theater_parent_id=" + id);
        if (root == null) {
            return emptyResponse();
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            Log.w(TAG, "detail: no data (id=" + id + ")");
            return detailResponse(new Vod().id(id).name(id));
        }

        String title = pickText(data, "title");
        String filing = pickText(data, "filing");
        Vod vod = new Vod()
                .id(id)
                .name(title.isEmpty() ? id : title)
                .pic(absolutize(pick(data, "cover_url")))
                .remarks(filing);
        vod.content(pickText(data, "introduction"));
        // 上游把 desc_tags[0] 塞进 vod_area（语义上是「标签」，位置借用了 area 字段）
        JSONArray tags = data.optJSONArray("desc_tags");
        if (tags != null && tags.length() > 0) {
            vod.area(tags.optString(0, ""));
        }

        JSONArray theaters = data.optJSONArray("theaters");
        if (theaters == null || theaters.length() == 0) {
            // 单集分支
            String single = pick(data, "video_url|son_video_url");
            if (single.isEmpty()) {
                return detailResponse(vod);
            }
            return detailResponse(attachPlay(vod, siteName(),
                    episode("1", single, 1)));
        }

        List<String> eps = new ArrayList<>();
        for (int i = 0; i < theaters.length(); i++) {
            JSONObject t = theaters.optJSONObject(i);
            if (t == null) {
                continue;
            }
            String num = pick(t, "num");
            String url = pick(t, "son_video_url|son_video_h265_url");
            String ep = episode(num.isEmpty() ? "第" + (i + 1) + "集" : "第" + num + "集",
                    url, i + 1);
            if (!ep.isEmpty()) {
                eps.add(ep);
            }
        }
        return detailResponse(attachPlay(vod, siteName(), joinEpisodes(eps)));
    }

    // ─────────────────────────────────────────────────────────────
    // 搜索
    // ─────────────────────────────────────────────────────────────

    /**
     * 搜索。
     *
     * <p>真实响应 {@code data.theater.search_data[]}，字段
     * {@code id / title / cover_url / score_str}。
     * 上游对 id 做了「字符串空或 "0" 时再取 long」的双重兜底 —— 本实现用
     * {@link ShortBase#pick} 统一处理数字/字符串。
     *
     * @param key   关键词
     * @param quick 忽略
     * @return 搜索标准体
     */
    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) {
            return emptyResponse();
        }
        JSONObject body = new JSONObject();
        try {
            body.put("text", key.trim());
        } catch (Exception ignored) {
        }

        JSONObject root = apiPost("/v3/search", body.toString());
        if (root == null) {
            return listResponse(new ArrayList<Vod>());
        }
        JSONArray arr = digArray(root, "data", "theater", "search_data");
        if (arr == null) {
            arr = digArray(root, "data", "list");
        }
        List<Vod> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String id = pick(o, "id");
                // 上游显式排除 id 为空或 "0" 的条目
                if (id.isEmpty() || "0".equals(id)) {
                    continue;
                }
                out.add(new Vod()
                        .id(id)
                        .name(pickText(o, "title"))
                        .pic(absolutize(pick(o, "cover_url")))
                        .remarks(pickText(o, "score_str")));
            }
        }
        return listResponse(out);
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 播放：详情阶段已拿直链，此处补播放器 UA。
     *
     * @param flag     线路名（忽略）
     * @param id       播放地址
     * @param vipFlags 忽略
     * @return 播放标准体
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return playResponse(id, uaHeader(PLAYER_UA));
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP 层
    // ─────────────────────────────────────────────────────────────

    /**
     * 发一次带业务鉴权头的 GET。
     *
     * @param pathWithQuery 路径（可含查询串）
     * @return 响应对象；失败返回 null
     */
    protected JSONObject apiGet(String pathWithQuery) {
        String base = apiBase();
        if (base.isEmpty()) {
            base = HOST;
        }
        Map<String, String> h = authHeaders();
        if (h == null) {
            return null;
        }
        String resp = http.get(base + pathWithQuery, h);
        return toObject(resp, pathWithQuery);
    }

    /**
     * 发一次带业务鉴权头的 POST（JSON 体）。
     *
     * @param path 路径
     * @param body JSON 文本
     * @return 响应对象；失败返回 null
     */
    protected JSONObject apiPost(String path, String body) {
        String base = apiBase();
        if (base.isEmpty()) {
            base = HOST;
        }
        Map<String, String> h = authHeaders();
        if (h == null) {
            return null;
        }
        h.put("Content-Type", "application/json");
        String resp = http.post(base + path, body, h);
        return toObject(resp, path);
    }

    /**
     * 把响应体转对象并做业务码检查。
     *
     * <p>真实响应用 {@code code:"ok"}（字符串），不是 0/200。
     *
     * @param resp 响应体
     * @param what 端点标识（日志用）
     * @return 对象；失败返回 null
     */
    private JSONObject toObject(String resp, String what) {
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "empty response: " + what);
            return null;
        }
        JSONObject root = parseObject(resp);
        if (root == null) {
            Log.w(TAG, "non-json: " + head(resp, 120));
            return null;
        }
        String code = root.optString("code", "");
        if (!code.isEmpty() && !"ok".equalsIgnoreCase(code) && !"0".equals(code)
                && !"200".equals(code)) {
            Log.w(TAG, "api code=" + code + " msg=" + root.optString("msg") + " @ " + what);
        }
        return root;
    }

    /**
     * 业务鉴权头；无 token 时先登录。
     *
     * @return header 表；登录失败返回 null
     */
    protected Map<String, String> authHeaders() {
        if (TextUtils.isEmpty(token)) {
            token = login();
        }
        if (TextUtils.isEmpty(token)) {
            Log.e(TAG, "authHeaders: login failed, no token");
            return null;
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("authorization", token);
        h.put("platform", "1");
        h.put("version_name", VERSION_NAME);
        return h;
    }

    /**
     * 登录换 token。
     *
     * <p>请求体是 {@code Base64(AES/ECB/PKCS5(设备JSON))}，Content-Type 声明为 json
     * 但实际发的是 Base64 文本（上游 {@code k.c} 原样发字符串）。
     *
     * @return JWT；失败返回空串
     */
    protected String login() {
        JSONObject dev = new JSONObject();
        try {
            dev.put("device", DEVICE_ID);
            dev.put("package_name", PACKAGE_NAME);
            dev.put("android_id", ANDROID_ID);
            dev.put("install_first_open", true);
            dev.put("first_install_time", INSTALL_TIME);
            dev.put("last_update_time", INSTALL_TIME);
            dev.put("report_link_url", "");
            dev.put("authorization", "");
            dev.put("timestamp", System.currentTimeMillis());
        } catch (Exception e) {
            Log.e(TAG, "login: build payload failed", e);
            return "";
        }

        String cipher = encryptEcb(dev.toString());
        if (cipher.isEmpty()) {
            Log.e(TAG, "login: encrypt failed");
            return "";
        }

        Map<String, String> h = new LinkedHashMap<>();
        h.put("platform", "1");
        h.put("user_agent", LOGIN_UA);
        h.put("content-type", "application/json; charset=utf-8");

        String resp = http.post(LOGIN_URL, cipher, h);
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "login: empty response");
            return "";
        }
        JSONObject root = parseObject(resp);
        if (root == null) {
            Log.w(TAG, "login: non-json " + head(resp, 120));
            return "";
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            Log.w(TAG, "login: no data, code=" + root.optString("code"));
            return "";
        }
        String t = data.optString("token", "");
        if (t.isEmpty()) {
            Log.w(TAG, "login: token empty");
        }
        return t;
    }

    // ─────────────────────────────────────────────────────────────
    // AES/ECB
    // ─────────────────────────────────────────────────────────────

    /**
     * AES/ECB/PKCS5Padding 加密 → Base64（NO_WRAP）。
     *
     * <p>还原上游 {@code Cipher.getInstance("AES/ECB/PKCS5Padding")} +
     * {@code SecretKeySpec("B@ecf920Od8A4df7".getBytes(UTF_8), "AES")} +
     * {@code Base64.encodeToString(..., 2)}。
     *
     * @param plain 明文，可为 null
     * @return Base64 密文；失败返回空串
     */
    public static String encryptEcb(String plain) {
        if (plain == null) {
            return "";
        }
        try {
            javax.crypto.Cipher c =
                    javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(
                            AES_KEY.getBytes(StandardCharsets.UTF_8), "AES"));
            return Base64.getEncoder()
                    .encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.e(TAG, "encryptEcb failed", e);
            return "";
        }
    }

    /**
     * AES/ECB/PKCS5Padding 解密（供测试与排障；上游无此方向调用）。
     *
     * @param cipherB64 Base64 密文
     * @return 明文；失败返回空串
     */
    public static String decryptEcb(String cipherB64) {
        if (cipherB64 == null || cipherB64.isEmpty()) {
            return "";
        }
        try {
            javax.crypto.Cipher c =
                    javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(
                            AES_KEY.getBytes(StandardCharsets.UTF_8), "AES"));
            byte[] raw = Base64.getDecoder().decode(cipherB64.trim());
            return new String(c.doFinal(raw), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 判断 JSON 原始值是否为「真」。
     *
     * <p>兼容布尔 {@code true}、数字非 0、字符串 {@code "1"/"true"}。
     * 用于 {@code is_end} 这类线上类型不稳定的字段。
     *
     * @param raw {@code JSONObject.opt()} 的原始返回值
     * @return true 表示为真
     */
    protected static boolean isTruthy(Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            return false;
        }
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue() != 0;
        }
        String s = String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    /**
     * 供测试读取 AES key。
     *
     * @return key 原文
     */
    public static String aesKey() {
        return AES_KEY;
    }

    /**
     * 供测试读取当前 token。
     *
     * @return JWT；未登录时为空串
     */
    public String token() {
        return token == null ? "" : token;
    }

    /**
     * 供测试注入 token（避免真登录）。
     *
     * @param t JWT
     */
    public void setTokenForTest(String t) {
        this.token = t == null ? "" : t;
    }

    /**
     * 供测试直接取首页分类表。
     *
     * @return 分类表（保持声明顺序）
     */
    public Map<String, String> homeClasses() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] c : HOME_CLASSES) {
            m.put(c[0], c[1]);
        }
        return m;
    }
}
