package com.example.tvbox.plain.sports;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.appget.AesGcm;
import com.example.tvbox.common.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 飞球体育（{@code csp_SportsFeiqiu}）明文实现。
 *
 * <p>继承 {@link BaseSpider}（上游 {@code extends Spider}）。
 *
 * <h2>“无加密”的真实含义（重要）</h2>
 * 上游把 <b>AES-CBC 当作请求参数封装</b>在用，但<b>密钥与 IV 都硬编码在类里</b>，
 * 且 algorithm 是标准 {@code AES/CBC/PKCS5Padding} —— 属于「协议编码」而非「服务端密钥协商」。
 * 因此本类按普通协议重写，AES 部分直接复用 {@link AesGcm}。
 *
 * <pre>
 *  key = iv = "zNuZTzWqOliEgJAL"   （16 字节，类内常量）
 *  加密:  AES/CBC/PKCS5Padding + Base64
 * </pre>
 *
 * <h2>协议（全部经真实请求验证）</h2>
 * <pre>
 * ① 域名发现（替代上游 init 的两次网络调用）
 *    GET https://pullcfg.butueaa.cn/domain/master/raw/18
 *        → Base64(AES-加密) 的 JSON 数组 ["http://api.l1d0...","http://api.j9y..."]
 *    GET {base}/v191/domain
 *        → Base64(AES-加密) → data.openim_zb_api_list[0] = 播放域名
 *
 * ② 请求头（5 项，注意 base 是加密后的设备 JSON）
 *    User-Agent : okhttp/4.2.2
 *    imei       : 本次会话的 UUID（上游 init 里 UUID.randomUUID()）
 *    device     : "1"
 *    platform   : "fqzb"
 *    base       : AES({"api-version":"11",...,"imei":<imei>,"device":"1"})
 *
 * ③ 分类  GET {base}/v220/schedule?data=<urlencode(AES({"sport_id":tid,"tab":"0","hot":"0"}))>
 *        响应是【明文 JSON】: {"code":200,"data":{"list":[{tab,name,list:[match...]}]}}
 *        match 字段: match_id / sport_id / alias_name / home_name / away_name /
 *                    home_score / away_score / home_logo / match_date / match_time
 *
 * ④ 详情  GET {base}/v171/matchs_live?data=<urlencode(AES({"sport_id","match_id","page":"0","page_size":"18"}))>
 *        响应是【Base64(AES-加密)】→ 数组，元素: room_title / chatroom_id / user_nickname
 *
 * ⑤ 播放  POST {play}/v230/play/url   body: data=<AES({"room_id","sport_id","match_id","code_id":"gqzm"})>
 *        响应是【Base64(AES-加密)】→ data.play_url
 * </pre>
 *
 * <h2>与上游的差异</h2>
 * <ol>
 *   <li><b>域名发现改为惰性</b>：上游在 {@code init()} 里连发 2 个请求，会拖慢宿主启动；
 *       本实现在首次真正需要时解析，失败则退回内置域名（{@code api.j9y5m8k8w7q9h8a.xyz}）</li>
 *   <li><b>match_date 容错</b>：上游 {@code getString("match_date")} 缺失就整页崩；
 *       本实现在缺失时用 {@code match_time} 秒级时间戳格式化</li>
 *   <li><b>响应形态自适应</b>：schedule 明文、matchs_live 密文是<b>实测</b>结果；
 *       为防止上游统一加密，本实现在明文解析失败时会再尝试一次 AES 解密</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/SportsFeiqiu.java}（155 行）
 * 真实夹具:   {@code ae/fixtures/real/sportsfeiqiu_domain.txt}（域名配置密文）
 *             {@code ae/fixtures/real/sportsfeiqiu_schedule.json}（明文分类响应）
 *             {@code ae/fixtures/real/sportsfeiqiu_detail.json}（密文详情响应）
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/SportsFeiqiu.java} 的对照复核，
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
 * {@code ae/decompiled/SportsFeiqiu.java} 为对照基准。
 */
public class SportsFeiqiuPlain extends BaseSpider {

    private static final String TAG = "SportsFeiqiuPlain";

    /** 上游硬编码的兜底 API 域名（static d） */
    private static final String DEFAULT_API = "http://api.j9y5m8k8w7q9h8a.xyz";

    /** 域名配置源（上游 init 里第一个请求） */
    private static final String DOMAIN_CONFIG = "https://pullcfg.butueaa.cn/domain/master/raw/18";

    /** 类内硬编码的 AES key / iv（上游两个参数都用它） */
    private static final String AES_KEY = "zNuZTzWqOliEgJAL";
    private static final String AES_IV = "zNuZTzWqOliEgJAL";

    /** 上游设备指纹 UA */
    private static final String UA = "okhttp/4.2.2";

    /** 播放请求里写死的 code_id（上游逐字） */
    private static final String CODE_ID = "gqzm";

    /** 上游 page_size（详情接口） */
    private static final int DETAIL_PAGE_SIZE = 18;

    /** 首页 4 个分类（上游 homeContent 硬编码） */
    private static final String[][] CATEGORIES = {
            {"1", "足球"},
            {"2", "篮球"},
            {"101", "电竞"},
            {"3", "其他"},
    };

    /** 本次会话的 UUID（上游 static a，init 时生成） */
    private String imei = "";
    /** 加密设备 JSON（上游 static c，作为 base 头） */
    private String deviceBase = "";
    /** 播放域名（上游 static b） */
    private String playHost = "";
    /** 是否已做过域名发现 */
    private boolean resolved;

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        // 上游忽略 ext（域名靠接口下发），本实现允许 ext.url 直接指定域名
        if (baseUrl == null || baseUrl.isEmpty() || !looksLikeApiHost(baseUrl)) {
            baseUrl = DEFAULT_API;
        }
        this.imei = UUID.randomUUID().toString();
        this.deviceBase = buildDeviceBase(imei);
        this.resolved = false;
        Log.i(TAG, "init base=" + baseUrl + " imei=" + imei);
    }

    /**
     * ext 里的地址是否已经是 API 域名（而不是站点域名需要再发现）。
     *
     * @param url 候选地址
     * @return true 视为可用 API 域名
     */
    private static boolean looksLikeApiHost(String url) {
        return url != null && url.contains("api.");
    }

    /**
     * 还原上游 init 里的设备 JSON（逐字保留字段与顺序）。
     *
     * @param uuid 本次会话 UUID
     * @return AES 加密后的 Base64 串（即 base 头）
     */
    public static String buildDeviceBase(String uuid) {
        String plain = "{\"api-version\":\"11\",\"im-uid\":\"\",\"invit-code\":\"\","
                + "\"version\":\"2.3.6\",\"os-ver\":\"9\",\"imid\":\"\","
                + "\"phone-model\":\"22081212C\",\"imtoken\":\"\",\"dun-imei\":\"\","
                + "\"imei\":\"" + nvl(uuid) + "\",\"device\":\"1\"}";
        String ct = AesGcm.cbcEncryptToBase64(plain, AES_KEY, AES_IV);
        return ct == null ? "" : ct;
    }

    /** 还原上游 {@code e()}：5 个头的 HashMap */
    public Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("imei", nvl(imei));
        h.put("device", "1");
        h.put("platform", "fqzb");
        h.put("base", nvl(deviceBase));
        return h;
    }

    // ─────────────────────────────────────────────────────────────
    // 域名发现
    // ─────────────────────────────────────────────────────────────

    /**
     * 惰性完成域名发现（还原上游 init 里的两个请求）。
     *
     * <p>失败不抛：退回 {@link #DEFAULT_API} 与当前 base。
     */
    public synchronized void ensureResolved() {
        if (resolved) {
            return;
        }
        resolved = true;

        // ① 域名列表（失败不致命，DEFAULT_API 兜底）
        String cfg = http.get(DOMAIN_CONFIG, Http.headersOf("User-Agent", UA));
        String api = firstOfJsonArray(decrypt(cfg));
        if (!TextUtils.isEmpty(api)) {
            baseUrl = api;
            Log.i(TAG, "域名发现: api=" + api);
        }

        // ② 播放域名
        String domainResp = http.get(baseUrl + "/v191/domain", apiHeaders());
        String play = playHostFrom(decrypt(domainResp));
        if (!TextUtils.isEmpty(play)) {
            playHost = play;
            Log.i(TAG, "域名发现: play=" + play);
        } else {
            playHost = baseUrl;
            Log.w(TAG, "播放域名发现失败，退回 " + playHost);
        }
    }

    /**
     * 取 JSON 数组首元素（域名配置形如 {@code ["http://a","http://b"]}）。
     *
     * @param json JSON 文本
     * @return 首元素；失败返回空串
     */
    public static String firstOfJsonArray(String json) {
        if (TextUtils.isEmpty(json)) {
            return "";
        }
        try {
            String t = json.trim();
            if (!t.startsWith("[")) {
                return "";
            }
            JSONArray arr = new JSONArray(t);
            if (arr.length() == 0) {
                return "";
            }
            String first = arr.optString(0, "");
            // 只接受像域名的值：既排除 JSONObject.NULL 被 String.valueOf 成 "null"，
            // 也排除畸形 JSON 解析出的垃圾串
            return first.startsWith("http") ? first.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 从 /v191/domain 的明文里取 {@code data.openim_zb_api_list[0]}。
     *
     * @param json 明文 JSON
     * @return 播放域名；失败返回空串
     */
    public static String playHostFrom(String json) {
        if (TextUtils.isEmpty(json)) {
            return "";
        }
        try {
            JSONObject data = new JSONObject(json).optJSONObject("data");
            if (data == null) {
                return "";
            }
            JSONArray arr = data.optJSONArray("openim_zb_api_list");
            if (arr == null || arr.length() == 0) {
                return "";
            }
            String first = arr.optString(0, "");
            return first.startsWith("http") ? first.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 加解密
    // ─────────────────────────────────────────────────────────────

    /**
     * AES 加密请求体（还原上游 {@code A.a.bo(json, key, iv)}）。
     *
     * @param plain 明文 JSON
     * @return Base64 密文；失败返回空串
     */
    public String encrypt(String plain) {
        String ct = AesGcm.cbcEncryptToBase64(plain, AES_KEY, AES_IV);
        return ct == null ? "" : ct;
    }

    /**
     * AES 解密响应（还原上游 {@code A.a.an(...)}）。
     *
     * <p>上游先做 {@code replace("\\", "")}（去掉 JSON 转义的斜杠），本实现同。
     *
     * @param cipher Base64 密文，可为 null
     * @return 明文；失败返回空串
     */
    public String decrypt(String cipher) {
        if (TextUtils.isEmpty(cipher)) {
            return "";
        }
        return AesGcm.cbcDecryptBase64(cipher.replace("\\", ""), AES_KEY, AES_IV);
    }

    /**
     * 解析「可能是明文、也可能是密文」的响应。
     *
     * <p>实测：{@code /v220/schedule} 返回明文，{@code /v171/matchs_live} 与
     * {@code /v230/play/url} 返回密文。这里先按明文试，再按密文试。
     *
     * @param resp 原始响应
     * @return 明文 JSON；都失败返回空串
     */
    public String asPlainJson(String resp) {
        if (TextUtils.isEmpty(resp)) {
            return "";
        }
        String t = resp.trim();
        if (t.startsWith("{")) {
            return t;
        }
        String plain = decrypt(t);
        return plain == null ? "" : plain.trim();
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页：4 个分类，无推荐（还原上游 {@code homeContent}）。
     *
     * @param filter 是否要筛选（上游无筛选项）
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        VodBuilder vb = VodBuilder.create();
        for (String[] c : CATEGORIES) {
            vb.clazz(c[0], c[1]);
        }
        return vb.homeContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 分类翻页（还原上游 {@code categoryContent}）。
     *
     * <p>上游把 {@code data.list} 里<b>所有 tab 的比赛</b>拼在一起（进行中/未开始/已结束），
     * 并用 tab 名判断文案，本实现同。
     *
     * @param tid    分类 id（1 足球 / 2 篮球 / 101 电竞 / 3 其他）
     * @param pg     页码
     * @param filter 是否要筛选（忽略）
     * @param extend 筛选参数（忽略）
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        ensureResolved();
        String base = requireUrl();
        if (base.isEmpty()) {
            return VodBuilder.create().page(page).categoryContent();
        }

        String body = "{\"sport_id\":\"" + nvl(tid) + "\",\"tab\":\"0\",\"hot\":\"0\"}";
        String data = encrypt(body);
        if (data.isEmpty()) {
            Log.e(TAG, "categoryContent 加密失败（key 长度异常？）");
            return VodBuilder.create().page(page).categoryContent();
        }
        String url = base + "/v220/schedule?data=" + URLEncoder.encode(data);
        String resp = http.get(url, apiHeaders());
        String plain = asPlainJson(resp);
        if (plain.isEmpty()) {
            Log.w(TAG, "categoryContent 无有效响应, tid=" + tid);
            return VodBuilder.create().page(page).categoryContent();
        }

        List<Vod> list;
        try {
            JSONObject root = new JSONObject(plain);
            JSONObject dataObj = root.optJSONObject("data");
            JSONArray tabs = dataObj == null ? null : dataObj.optJSONArray("list");
            list = parseScheduleTabs(tabs);
        } catch (Exception e) {
            Log.e(TAG, "categoryContent parse failed", e);
            return VodBuilder.create().page(page).categoryContent();
        }
        // 上游把 pagecount/total 都写成 MAX_VALUE（几乎无限翻页）。
        // 直接透传会让宿主算页数时溢出，这里给「当前页 + 1」表示还有下一页。
        return VodBuilder.create().addAll(list).page(page, 0, Math.max(list.size(), 20))
                .categoryContent();
    }

    /**
     * 解析 {@code data.list[]}（每个元素是一个 tab，含自己的 list）。
     *
     * <p>拼 {@code vod_id} 的规则逐字来自上游：
     * {@code match_id + "|||" + sport_id}（详情/播放都靠它拆参）。
     *
     * @param tabs tab 数组，可为 null
     * @return 影片列表，永不 null
     */
    public List<Vod> parseScheduleTabs(JSONArray tabs) {
        List<Vod> out = new ArrayList<>();
        if (tabs == null) {
            return out;
        }
        for (int i = 0; i < tabs.length(); i++) {
            JSONObject tab = tabs.optJSONObject(i);
            if (tab == null) {
                continue;
            }
            String tabName = tab.optString("tab");
            JSONArray matches = tab.optJSONArray("list");
            if (matches == null) {
                continue;
            }
            for (int j = 0; j < matches.length(); j++) {
                JSONObject m = matches.optJSONObject(j);
                if (m == null) {
                    continue;
                }
                out.add(toVod(m, tabName));
            }
        }
        return out;
    }

    /**
     * 单场比赛 → Vod（还原上游的字段拼接与文案规则）。
     *
     * @param m       比赛对象
     * @param tabName tab 标识（matching / notStarted / finished / exception）
     * @return 影片
     */
    public Vod toVod(JSONObject m, String tabName) {
        String matchId = m.optString("match_id", String.valueOf(m.opt("match_id")));
        String sportId = m.optString("sport_id", String.valueOf(m.opt("sport_id")));
        String name = m.optString("home_name") + " vs " + m.optString("away_name");

        // 上游文案：
        //   进行中 → alias_name + 主分 + ":" + 客分 + " 直播中"
        //   其它   → alias_name + match_date + " 未开赛"
        String remarks;
        if (tabName != null && tabName.contains("matching")) {
            remarks = m.optString("alias_name")
                    + m.optString("home_score", String.valueOf(m.opt("home_score")))
                    + ":" + m.optString("away_score", String.valueOf(m.opt("away_score")))
                    + " 直播中";
        } else {
            String date = m.optString("match_date");
            if (date.isEmpty()) {
                // 上游缺失会崩；这里用 match_time 兜底
                date = formatTime(m.optLong("match_time", 0L));
            }
            remarks = m.optString("alias_name") + date + " 未开赛";
        }
        return new Vod()
                .id(matchId + "|||" + sportId)
                .name(name)
                .pic(m.optString("home_logo"))
                .remarks(remarks.trim());
    }

    /**
     * 秒级时间戳 → {@code MM-dd HH:mm}（上游用 SimpleDateFormat 同一格式）。
     *
     * @param epochSec 秒
     * @return 格式化串；0 返回空串
     */
    public static String formatTime(long epochSec) {
        if (epochSec <= 0) {
            return "";
        }
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm",
                    java.util.Locale.CHINA);
            return f.format(new java.util.Date(epochSec * 1000L));
        } catch (Throwable e) {
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 详情（还原上游 {@code detailContent}）。
     *
     * <p>{@code vod_id} 形如 {@code match_id|||sport_id}；
     * 用 {@code match_id} 拉 {@code /v171/matchs_live}，返回的每个聊天室是一条线路。
     *
     * @param ids id 列表，首个
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (id.isEmpty()) {
            return emptyResponse();
        }
        String[] parts = id.split("\\|\\|\\|");
        String matchId = parts.length > 0 ? parts[0] : "";
        String sportId = parts.length > 1 ? parts[1] : "";
        if (matchId.isEmpty() || sportId.isEmpty()) {
            Log.w(TAG, "detailContent id 结构不合法: " + id);
            return emptyResponse();
        }

        ensureResolved();
        String base = requireUrl();
        if (base.isEmpty()) {
            return emptyResponse();
        }

        String body = "{\"sport_id\":\"" + sportId + "\",\"match_id\":\"" + matchId
                + "\",\"page\":\"0\",\"page_size\":\"" + DETAIL_PAGE_SIZE + "\"}";
        String data = encrypt(body);
        String resp = http.get(base + "/v171/matchs_live?data=" + URLEncoder.encode(data),
                apiHeaders());
        String plain = asPlainJson(resp);
        if (plain.isEmpty()) {
            Log.w(TAG, "detailContent 无有效响应, id=" + id);
            return emptyResponse();
        }

        try {
            JSONArray arr = new JSONObject(plain).optJSONArray("data");
            if (arr == null || arr.length() == 0) {
                Log.w(TAG, "detailContent data 为空, id=" + id);
                return emptyResponse();
            }
            // 标题取首条的 room_title（上游同）
            String title = arr.getJSONObject(0).optString("room_title");

            List<String> eps = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String nickname = o.optString("user_nickname");
                if (nickname.isEmpty()) {
                    nickname = "比赛原声";        // 上游同一兜底文案
                }
                String chatroom = o.optString("chatroom_id");
                if (chatroom.isEmpty()) {
                    continue;
                }
                // 集名$chatroom_id|||match_id|||sport_id（播放层按 ||| 拆）
                eps.add(nickname + Vod.SEP_NAME_URL + chatroom + "|||" + id);
            }

            Vod vod = new Vod().id(id).name(title);
            if (!eps.isEmpty()) {
                vod.playFrom("飞球").playUrl(Html.join(Vod.SEP_EPISODE, eps));
            }
            return VodBuilder.detail(vod).detailContent();
        } catch (Exception e) {
            Log.e(TAG, "detailContent parse failed, id=" + id, e);
            return emptyResponse();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 播放（还原上游 {@code playerContent}）。
     *
     * <p>入参 id 形如 {@code chatroom|||match_id|||sport_id}；
     * 向上游播放接口换真实地址后直链返回。
     *
     * @param flag     线路名（飞球）
     * @param id       播放 id
     * @param vipFlags 需要解析的标识（忽略）
     * @return 标准播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) {
            return emptyResponse();
        }
        String[] parts = id.split("\\|\\|\\|");
        if (parts.length < 3) {
            Log.w(TAG, "playerContent id 结构不合法: " + id);
            return emptyResponse();
        }
        String roomId = parts[0];
        String matchId = parts[1];
        String sportId = parts[2];

        ensureResolved();
        String play = TextUtils.isEmpty(playHost) ? requireUrl() : playHost;
        if (play.isEmpty()) {
            return emptyResponse();
        }

        String body = "{\"room_id\":\"" + roomId + "\",\"sport_id\":\"" + sportId
                + "\",\"match_id\":\"" + matchId + "\",\"code_id\":\"" + CODE_ID + "\"}";
        String data = encrypt(body);
        // 上游用 POST（b.g(url, params, headers)）
        Map<String, String> form = new LinkedHashMap<>();
        form.put("data", data);
        String resp = http.post(play + "/v230/play/url", formBody(form), apiHeaders());

        String plain = asPlainJson(resp);
        if (plain.isEmpty()) {
            Log.w(TAG, "playerContent 无有效响应, room=" + roomId);
            return emptyResponse();
        }
        try {
            JSONObject d = new JSONObject(plain).optJSONObject("data");
            String url = d == null ? "" : d.optString("play_url");
            if (url.isEmpty()) {
                Log.w(TAG, "playerContent play_url 为空");
                return emptyResponse();
            }
            String type = url.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/*";
            String json = VodBuilder.playerContent(url, type);
            // 上游给播放器带 okhttp/4.2.2
            JSONObject o = new JSONObject(json);
            o.put("header", "{\"User-Agent\":\"" + UA + "\"}");
            return o.toString();
        } catch (Exception e) {
            Log.e(TAG, "playerContent parse failed", e);
            return emptyResponse();
        }
    }

    /**
     * 表单编码（上游 {@code b.g()} 把 Map 当 form body）。
     *
     * @param form 参数表
     * @return {@code k=v&k=v}
     */
    public static String formBody(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        if (form == null) {
            return "";
        }
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(Http.encode(e.getKey())).append('=').append(Http.encode(e.getValue()));
        }
        return sb.toString();
    }

    /** null 安全 */
    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
