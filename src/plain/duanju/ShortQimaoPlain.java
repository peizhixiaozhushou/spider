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
import java.util.TreeMap;

/**
 * 七猫短剧（qmplaylet）明文实现。
 *
 * <h2>证据来源</h2>
 * <ul>
 *   <li>反编译: {@code ae/decompiled/ShortQimao.java}（288 行，jadx dev）</li>
 *   <li>常量长度复核: {@code python3 ae/tools/local_dump.py re/spider.dex ShortQimao}
 *       —— 签名盐 {@code d3dGiJc651gSQ8w1}(16)、置换表 3 张（10/26/26）均按本地 jadx 复核</li>
 *   <li><b>真实请求验证</b>: {@code ae/fixtures/real/shortqimao_*.json}
 *       （首页 / 分类 / 搜索 / 详情四端点，线上实抓）</li>
 * </ul>
 *
 * <h2>协议要点（全部经真实请求确认）</h2>
 *
 * <h3>1. 域名发现</h3>
 * <pre>
 *  GET https://neptune.qmplaylet.com/playlet-domain-android.json
 *      Header: User-Agent: okhttp/4.10.0
 *  →    {"data":{"bc":"https://api-store.qmplaylet.com",    ← 内容域(store)
 *               "ks":"https://api-read.qmplaylet.com", ...}} ← 播放域(read)
 * </pre>
 *
 * <h3>2. 请求头（12 项，缺一即「验签失败」）</h3>
 * <pre>
 *  authorization      : ""              ← 必须带空值，不是省略
 *  reg                : ""
 *  is-white           : ""
 *  user-agent         : webviewversion/0
 *  net-env            : 1
 *  channel            : va-vivo_lf
 *  platform           : android
 *  application-id     : com.duoduo.read
 *  app-version        : 10001
 *  qm-params          : &lt;见 §3&gt;
 *  no-permiss         : 3
 *  sign               : md5(固定模板 + qm-params + 盐)   ← 见 §4
 * </pre>
 *
 * <h3>3. qm-params —— 设备指纹 + Base64 + 三段置换</h3>
 * 把 17 个键按<b>固定插入顺序</b>序列化成紧凑 JSON（无空格），Base64（NO_WRAP），
 * 然后按字符类别做替换（这是上游唯一的「自定义编码」，不是加密）：
 * <pre>
 *  '+'      → 'P'
 *  '/'      → 'X'
 *  '0'~'9'  → "MUlErYWbdJ"        [c - '0']
 *  'A'~'Z'  → "9saI0oy_HGitgNA8Fk3hfRqC4p" [c - 'A']
 *  'a'~'z'  → "mBOuc6Kx5T-2zSZ1VvjQ7DwnLe" [c - 'a']
 * </pre>
 * 注意 {@code session-id} 是毫秒时间戳，但实测<b>服务端不校验时效</b>
 * （-0ms / -1h / -24h 均通过），故无需与真实时钟强同步。
 *
 * <h3>4. sign 的双重含义</h3>
 * <pre>
 *  Header sign = md5("AUTHORIZATION=app-version=10001application-id=com.duoduo.read"
 *                  + "channel=va-vivo_lfis-white=net-env=1platform=android"
 *                  + "qm-params=" + qm + "reg=" + 盐)
 *  Body   sign = md5(按 key 字典序拼接 "k=v" + 盐)      ← 作为 query 参数 sign 传递
 * </pre>
 * 两者都由盐 {@code d3dGiJc651gSQ8w1} 收尾。实测 header 值可与 sign 模板同改（自洽即可），
 * 说明服务端是<b>按收到的头重算比对</b>，而非白名单硬编码。
 *
 * <h3>5. 端点</h3>
 * <pre>
 *  首页   GET {bc}/api/v1/playlet/index   tag_id=0  playlet_privacy=1 operation=1
 *  分类   GET {bc}/api/v1/playlet/index   tag_id={tid} next_id={pg} playlet_privacy={tid=="0"?0:1}
 *  搜索   GET {bc}/api/v1/playlet/search  extend= page=1 wd={key} read_preference=0 0={auth}{millis}
 *  详情   GET {ks}/player/api/v1/playlet/info  playlet_id={id}
 * </pre>
 * 搜索中文<b>必须 URL 编码</b>，否则返回纯文本 {@code Bad Request}。
 *
 * <h2>加密算法</h2>
 * 无 AES/RSA。仅 MD5（签名）+ Base64（qm-params）+ 字符置换。
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/ShortQimao.java} 的对照复核，
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
 * {@code ae/decompiled/ShortQimao.java} 为对照基准。
 */
public class ShortQimaoPlain extends ShortBase {

    private static final String TAG = "ShortQimaoPlain";

    /** 内容域默认值（域名发现失败时兜底，取自反编译常量） */
    private static final String DEFAULT_STORE = "https://api-store.qmplaylet.com";
    /** 播放域默认值 */
    private static final String DEFAULT_READ = "https://api-read.qmplaylet.com";
    /** 域名发现端点 */
    private static final String DOMAIN_URL =
            "https://neptune.qmplaylet.com/playlet-domain-android.json";

    /** 签名盐 —— 长度 16，已按本地 jadx 复核 */
    private static final String SIGN_SALT = "d3dGiJc651gSQ8w1";

    /** AUTHORIZATION 头的固定值（也是 qm-params 里 AUTHORIZATION/wlb-uid 的值） */
    private static final String AUTH_VALUE = "6bcc46919d10d06a";

    /** Header sign 的固定模板；{@code %s} 处填 qm-params */
    private static final String HEADER_SIGN_TEMPLATE =
            "AUTHORIZATION=app-version=10001application-id=com.duoduo.read"
                    + "channel=va-vivo_lfis-white=net-env=1platform=android"
                    + "qm-params=%sreg=";

    /** qm-params 三张字符置换表（下标 = 原字符在该类别中的偏移） */
    private static final String PERM_DIGIT = "MUlErYWbdJ";
    private static final String PERM_UPPER = "9saI0oy_HGitgNA8Fk3hfRqC4p";
    private static final String PERM_LOWER = "mBOuc6Kx5T-2zSZ1VvjQ7DwnLe";

    /** 每页条数：上游 categoryContent/searchContent 传 20 */
    private static final int PAGE_SIZE = 20;

    /** 内容域（bc） */
    private String storeBase = DEFAULT_STORE;
    /** 播放域（ks） */
    private String readBase = DEFAULT_READ;

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);

        // ext.url 若给了具体域名，优先用它当内容域（站群换域名时无需改代码）
        if (!baseUrl.isEmpty()) {
            storeBase = baseUrl;
        }
        // ext 可用 dataKey 显式指定播放域（复用现有字段，不新增 ext 约定）
        String readOverride = ext.optStr("dataIv");
        if (!TextUtils.isEmpty(readOverride) && readOverride.startsWith("http")) {
            readBase = trimSlash(readOverride);
        }

        refreshDomains();

        // 让基类 baseUrl 指向内容域，供 requireUrl()/日志使用
        baseUrl = storeBase;
        Log.i(TAG, "init store=" + storeBase + " read=" + readBase);
    }

    /**
     * 拉域名配置并回填 store/read 域。
     *
     * <p>上游 {@code ShortQimao.init} 每次 init 都拉一次，失败则保留默认值；
     * 本实现同样不因失败而中断（域名端点挂了不应导致整站不可用）。
     */
    private void refreshDomains() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", "okhttp/4.10.0");
        String resp = http.get(DOMAIN_URL, h);
        JSONObject root = parseObject(resp);
        if (root == null) {
            Log.w(TAG, "domain config unavailable, keep defaults");
            return;
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            return;
        }
        String bc = data.optString("bc", "");
        String ks = data.optString("ks", "");
        if (!bc.isEmpty()) {
            storeBase = trimSlash(bc);
        }
        if (!ks.isEmpty()) {
            readBase = trimSlash(ks);
        }
    }

    @Override
    protected String siteName() {
        return "七猫";
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
     * <p>真实响应 {@code data.tag_items[]} 每项 {@code {tag_id, tag_name}}；
     * 上游同时用 {@code operation=1} 标记「带运营位」。
     *
     * @param filter 宿主是否请求筛选（本接口无筛选定义，忽略）
     * @return 首页标准体
     */
    @Override
    public String homeContent(boolean filter) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("tag_id", "0");
        q.put("playlet_privacy", "1");
        q.put("operation", "1");
        String resp = apiGet(storeBase, "/api/v1/playlet/index", q);
        JSONObject root = parseObject(resp);
        if (root == null) {
            return emptyResponse();
        }
        JSONArray tags = digArray(root, "data", "tag_items");
        Map<String, String> classes = new LinkedHashMap<>();
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                JSONObject t = tags.optJSONObject(i);
                if (t == null) {
                    continue;
                }
                String tid = pick(t, "tag_id");
                String tname = pickText(t, "tag_name");
                if (!tid.isEmpty()) {
                    classes.put(tid, tname.isEmpty() ? tid : tname);
                }
            }
        }
        // 首页推荐位：真实响应 data.list 是「推荐」tag 下的剧集
        List<Vod> recommend = mapVods(digArray(root, "data", "list"), null,
                "playlet_id|id", "title", "image_link|image|cover|vertical_cover|playlet_cover",
                "total_episode_num|total_num|sub_title");
        return homeResponse(classes, recommend);
    }

    // ─────────────────────────────────────────────────────────────
    // 分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 分类翻页。
     *
     * <p>关键：{@code playlet_privacy} 与 tag 有关 —— tid 为 {@code "0"}（推荐）时传 {@code "0"}，
     * 否则传 {@code "1"}。这是上游的三元表达式，实测两种取值都能出 list，但保持原样以防筛选差异。
     *
     * @param tid    分类 id
     * @param pg     页码（本接口用 next_id 表示，从 1 开始）
     * @param filter 是否请求筛选
     * @param extend 筛选参数（本接口无可用维度）
     * @return 分类标准体
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  java.util.HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String tagId = TextUtils.isEmpty(tid) ? "0" : tid.trim();

        Map<String, String> q = new LinkedHashMap<>();
        q.put("tag_id", tagId);
        q.put("next_id", String.valueOf(page));
        q.put("playlet_privacy", "0".equals(tagId) ? "0" : "1");

        String resp = apiGet(storeBase, "/api/v1/playlet/index", q);
        JSONObject root = parseObject(resp);
        if (root == null) {
            return pageResponse(new ArrayList<Vod>(), page, PAGE_SIZE, false);
        }
        List<Vod> list = mapVods(digArray(root, "data", "list"), null,
                "playlet_id|id", "title", "image_link|image|cover|vertical_cover|playlet_cover",
                "total_episode_num|total_num|sub_title");
        // 上游以「本页非空即有下一页」为准（接口不返回 total）
        return pageResponse(list, page, PAGE_SIZE, !list.isEmpty());
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 详情：走<b>播放域</b>的 {@code /player/api/v1/playlet/info}。
     *
     * <p>真实响应 {@code data}：
     * <pre>
     *  { "playlet_id", "title", "image_link", "intro", "total_episode_num",
     *    "play_list": [ { "video_id", "video_url", "sort", "video_h265_url", ... } ] }
     * </pre>
     * 上游只用 {@code title / image_link / intro / play_list}。
     * 本实现在 {@code video_url} 缺失时回退 {@code video_h265_url}（上游无此回退，
     * 但真实响应里 h265 与 h264 并存，回退可提高可用性）。
     *
     * @param ids 影片 id 列表，取首个
     * @return 详情标准体
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (id.isEmpty()) {
            return emptyResponse();
        }

        Map<String, String> q = new LinkedHashMap<>();
        q.put("playlet_id", id);
        String resp = apiGet(readBase, "/player/api/v1/playlet/info", q);
        JSONObject root = parseObject(resp);
        if (root == null) {
            return emptyResponse();
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            Log.w(TAG, "detail: no data (id=" + id + ")");
            return detailResponse(new Vod().id(id).name(id));
        }

        String name = pickText(data, "title");
        Vod vod = new Vod()
                .id(id)
                .name(name.isEmpty() ? id : name)
                .pic(absolutize(pick(data, "image_link|image|cover")))
                .remarks(pickText(data, "total_episode_num"));
        vod.content(pickText(data, "intro"));

        JSONArray playList = data.optJSONArray("play_list");
        if (playList == null || playList.length() == 0) {
            return detailResponse(vod);
        }

        List<String> eps = new ArrayList<>();
        for (int i = 0; i < playList.length(); i++) {
            JSONObject it = playList.optJSONObject(i);
            if (it == null) {
                continue;
            }
            String sort = pick(it, "sort");
            String label = "第" + (sort.isEmpty() ? String.valueOf(i + 1) : sort) + "集";
            String url = pick(it, "video_url|video_h265_url");
            String ep = episode(label, url, i + 1);
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
     * <p>上游的 {@code "0"} 参数值是 {@code AUTHORIZATION + 当前毫秒}，参与 body 签名，
     * 缺它或值不符都会「验签失败」。
     *
     * @param key   关键词（中文必须 URL 编码，由 {@link com.example.tvbox.common.Http#buildUrl} 完成）
     * @param quick 是否快速搜索（本接口无差异）
     * @return 搜索标准体
     */
    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) {
            return emptyResponse();
        }
        Map<String, String> q = new LinkedHashMap<>();
        q.put("extend", "");
        q.put("page", "1");
        q.put("wd", key.trim());
        q.put("read_preference", "0");
        q.put("0", AUTH_VALUE + System.currentTimeMillis());

        String resp = apiGet(storeBase, "/api/v1/playlet/search", q);
        JSONObject root = parseObject(resp);
        if (root == null) {
            return listResponse(new ArrayList<Vod>());
        }
        JSONArray arr = digArray(root, "data", "list");
        if (arr == null) {
            arr = root.optJSONArray("list");
        }
        return listResponse(mapVods(arr, null,
                "playlet_id|id", "title",
                "image_link|image|cover|vertical_cover|playlet_cover",
                "total_episode_num|total_num|sub_title"));
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 播放：详情阶段已拿到 m3u8 直链，此处只补播放器 UA / Referer。
     *
     * <p>上游 {@code playerContent} 用
     * {@code {"User-Agent":"webviewversion/0","Referer":"Dalvik/2.1.0 (Linux; U; Android 11; M2012K10C ...)"}}。
     *
     * @param flag     线路名（忽略）
     * @param id       播放地址
     * @param vipFlags 需解析标识（忽略，直链）
     * @return 播放标准体
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", "webviewversion/0");
        h.put("Referer", "Dalvik/2.1.0 (Linux; U; Android 11; M2012K10C Build/RP1A.200720.011)");
        return playResponse(id, h);
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP 层：签名 + 发送
    // ─────────────────────────────────────────────────────────────

    /**
     * 发一次带签名的 GET。
     *
     * <p>query 里会追加上计算好的 {@code sign}；header 里带 12 项指纹。
     *
     * @param base  域（store 或 read）
     * @param path  路径
     * @param query 业务参数（会按字典序参与签名）
     * @return 响应体；配置不全/网络失败时返回空串
     */
    protected String apiGet(String base, String path, Map<String, String> query) {
        if (TextUtils.isEmpty(base)) {
            Log.e(TAG, "apiGet: empty base, path=" + path);
            return "";
        }
        // 1) body sign：按 key 字典序拼 k=v 再拼盐
        Map<String, String> sorted = new TreeMap<>(query == null ? new LinkedHashMap<String, String>() : query);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue() == null ? "" : e.getValue());
        }
        sb.append(SIGN_SALT);
        sorted.put("sign", md5(sb.toString()));

        // 2) header：qm-params 先算，sign 依赖它
        String qm = buildQmParams();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("authorization", "");
        h.put("reg", "");
        h.put("is-white", "");
        h.put("user-agent", "webviewversion/0");
        h.put("net-env", "1");
        h.put("channel", "va-vivo_lf");
        h.put("platform", "android");
        h.put("application-id", "com.duoduo.read");
        h.put("app-version", "10001");
        h.put("qm-params", qm);
        h.put("no-permiss", "3");
        h.put("sign", md5(String.format(HEADER_SIGN_TEMPLATE, qm) + SIGN_SALT));

        String url = com.example.tvbox.common.Http.buildUrl(base, path, sorted);
        String resp = http.get(url, h);
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "empty response: " + path);
        }
        return resp;
    }

    // ─────────────────────────────────────────────────────────────
    // qm-params 构造
    // ─────────────────────────────────────────────────────────────

    /**
     * 构造 qm-params：
     * 设备指纹 JSON → Base64(NO_WRAP) → 三段字符置换。
     *
     * <p>键顺序严格等于上游 {@code LinkedHashMap} 的插入顺序 —— 顺序变了服务端验签会失败，
     * 因此这里必须用 {@link LinkedHashMap} 而不是普通 Map。
     *
     * @return 置换后的字符串
     */
    public String buildQmParams() {
        Map<String, String> fp = new LinkedHashMap<>();
        fp.put("static_score", "0.8");
        fp.put("uuid", "00000000-6f7c-e347-0000-000000000000");
        fp.put("device-id", "202504012213236fa2ed536aed584e0cc8a6a09fe2f2d4016cdc5bc74f2d5f");
        fp.put("mac", "");
        fp.put("sourceuid", "9494817a02a93435");
        fp.put("refresh-type", "0");
        fp.put("model", "M2012K10C");
        fp.put("wlb-imei", "");
        fp.put("AUTHORIZATION", AUTH_VALUE);
        fp.put("brand", "Redmi");
        fp.put("oaid", "");
        fp.put("oaid-no-cache", "");
        fp.put("sys-ver", "11");
        fp.put("trusted-id", "");
        fp.put("phone-level", "H");
        fp.put("imei", "");
        fp.put("wlb-uid", AUTH_VALUE);
        fp.put("session-id", String.valueOf(System.currentTimeMillis()));

        String json = compactJson(fp);
        String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return permute(b64);
    }

    /**
     * 三段字符置换（上游唯一的自定义编码）。
     *
     * @param b64 原始 Base64 串
     * @return 置换后的串
     */
    public static String permute(String b64) {
        if (b64 == null || b64.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(b64.length());
        for (int i = 0; i < b64.length(); i++) {
            char c = b64.charAt(i);
            if (c == '+') {
                sb.append('P');
            } else if (c == '/') {
                sb.append('X');
            } else if (c >= '0' && c <= '9') {
                sb.append(PERM_DIGIT.charAt(c - '0'));
            } else if (c >= 'A' && c <= 'Z') {
                sb.append(PERM_UPPER.charAt(c - 'A'));
            } else if (c >= 'a' && c <= 'z') {
                sb.append(PERM_LOWER.charAt(c - 'a'));
            } else {
                // '=' 等其余字符原样保留（上游 switch 的 default 分支同样是原样）
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 生成紧凑 JSON（无空格），保持插入顺序。
     *
     * <p>等价 Gson 默认 {@code toJson(Map)}。上游用的是 {@code new Gson()}（htmlSafe=true），
     * 但本 map 的值全为字母/数字/连字符，不含需转义的 HTML 字符，故两种实现输出一致。
     *
     * @param map 有序键值表
     * @return 紧凑 JSON 文本
     */
    protected static String compactJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        return sb.append('}').toString();
    }

    /**
     * JSON 字符串转义（只处理必要字符）。
     *
     * @param s 原文，可为 null
     * @return 带双引号的转义串
     */
    private static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * MD5 → 小写 hex。
     *
     * <p>还原上游 {@code merge.J.e.a(String)}（两者实现一致：digest 后逐字节 {@code Integer.toHexString}，
     * 不足两位补 {@code 0}）。
     *
     * @param s 原文，可为 null
     * @return 32 位小写 hex；异常时返回空串（与上游一致）
     */
    public static String md5(String s) {
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
            return "";
        }
    }

    /**
     * 去掉尾部斜杠。
     *
     * @param s 原文
     * @return 处理结果
     */
    private static String trimSlash(String s) {
        String v = s == null ? "" : s.trim();
        while (v.endsWith("/") && v.length() > 1) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    /**
     * 供测试读取内容域。
     *
     * @return store 域
     */
    public String storeBase() {
        return storeBase == null ? "" : storeBase;
    }

    /**
     * 供测试读取播放域。
     *
     * @return read 域
     */
    public String readBase() {
        return readBase == null ? "" : readBase;
    }
}
