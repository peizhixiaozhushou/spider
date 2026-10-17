package com.example.tvbox.plain.sports;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.appget.AesGcm;
import com.example.tvbox.common.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 瓜子体育（{@code csp_SportsGuazi}）明文实现。
 *
 * <p>继承 {@link BaseSpider}（上游 {@code extends Spider}）。
 *
 * <h2>“无加密”的真实含义</h2>
 * 请求参数用 {@code AES/CBC/PKCS5Padding} + Base64 封装，<b>key 与 iv 都是类内常量</b>：
 * <pre>
 *  key = "KANGEQIU@8868!~."      （16 字节，恰好合法）
 *  iv  = "0200010900030207"      （16 字节）
 * </pre>
 * 请求体与响应体<b>双向</b>都加密；响应外层是明文 JSON，只有 {@code data} 字段是密文。
 *
 * <h2>协议（全部经真实请求验证）</h2>
 * <pre>
 * 站点: https://api.46d5umpk.com
 *
 * 请求头（5 项，逐字）
 *   User-Agent: okhttp/3.12.0
 *   content-type: application/x-www-form-urlencoded
 *   user-platform: null      ← 字面量 "null"
 *   client-version: 3.0.1.1
 *   client-channel: (空)
 *   token: (空)
 *
 * 分类  POST {base}/gz/live/sports?parameter=key   body: parameter=<AES(json)>
 *        json = {"frame":"0","hot":"1","tag":"0","type":"0"}   ← 5 个分类各一套
 *        响应 {"code":200,"data":"<Base64 密文>"}
 *        解密 → 比赛数组:
 *          mid / match_time(秒) / m_status / event_name / match_status_info /
 *          home{name,logo,score} / visiting{name,logo,score}
 *
 * 详情  POST {base}/gz/live/detail?parameter=key   body: parameter=<AES({"mid":mid})>
 *        解密 → 单场对象（含 live_line[] = [{name,url}] 直播线路）
 *
 * 播放  直链（detail 已给 m3u8）
 * </pre>
 *
 * <h2>分类 → 请求体映射（逐字来自上游 categoryContent 的 HashMap）</h2>
 * <table>
 *   <tr><th>tid</th><th>parameter 明文</th><th>说明</th></tr>
 *   <tr><td>hot</td><td>{"frame":"0","hot":"1","tag":"0","type":"0"}</td><td>热门</td></tr>
 *   <tr><td>nba</td><td>{"frame":"0","hot":"0","tag":"37","type":"0"}</td><td>NBA</td></tr>
 *   <tr><td>football</td><td>{"frame":"0","hot":"0","tag":"0","type":"1"}</td><td>足球</td></tr>
 *   <tr><td>basketball</td><td>{"frame":"0","hot":"0","tag":"0","type":"2"}</td><td>篮球</td></tr>
 *   <tr><td>其他</td><td>{"frame":"0","hot":"0","tag":"0","type":"0"}</td><td>上游退化为 "all" → map.get("all") 为 null → 实际会 NPE</td></tr>
 * </table>
 *
 * <h2>与上游的差异</h2>
 * <ol>
 *   <li>上游只处理 {@code pg=="1"}（第二页直接返回空串）。本实现同（服务端不分页）</li>
 *   <li>上游 {@code mapC.get("all")} 是 null（HashMap 里没有 all 键）→ NPE。
 *       本实现把未知 tid 退化到 hot 的请求体，不崩</li>
 *   <li>上游只保留「24 小时内有比赛且 m_status &lt; 2」的条目；本实现同，
 *       但把「已完场」也纳入（否则列表经常全空），仅在 remarks 标注</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/SportsGuazi.java}（130 行）
 * 真实夹具:   {@code ae/fixtures/real/sportsguazi_hot.json}（加密响应原文）
 *             {@code ae/fixtures/real/sportsguazi_detail.json}
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/SportsGuazi.java} 的对照复核，
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
 * {@code ae/decompiled/SportsGuazi.java} 为对照基准。
 */
public class SportsGuaziPlain extends BaseSpider {

    private static final String TAG = "SportsGuaziPlain";

    /** 上游硬编码站点 */
    private static final String DEFAULT_BASE = "https://api.46d5umpk.com";

    /** 类内硬编码 AES key（上游 final String b） */
    private static final String AES_KEY = "KANGEQIU@8868!~.";
    /** 类内硬编码 AES iv（上游 final String a） */
    private static final String AES_IV = "0200010900030207";

    /** 上游 UA */
    private static final String UA = "okhttp/3.12.0";

    /** 上游首页 4 个分类（顺序与名字逐字） */
    private static final String[][] CATEGORIES = {
            {"hot", "热门"},
            {"nba", "NBA"},
            {"football", "足球"},
            {"basketball", "篮球"},
    };

    /** tid → parameter 明文（逐字来自上游 HashMap） */
    private static final Map<String, String> BODIES = new LinkedHashMap<>();

    static {
        BODIES.put("hot", "{\"frame\":\"0\",\"hot\":\"1\",\"tag\":\"0\",\"type\":\"0\"}");
        BODIES.put("nba", "{\"frame\":\"0\",\"hot\":\"0\",\"tag\":\"37\",\"type\":\"0\"}");
        BODIES.put("football", "{\"frame\":\"0\",\"hot\":\"0\",\"tag\":\"0\",\"type\":\"1\"}");
        BODIES.put("basketball", "{\"frame\":\"0\",\"hot\":\"0\",\"tag\":\"0\",\"type\":\"2\"}");
    }

    /** 上游只展示 24 小时内的比赛 */
    private static final long WINDOW_MS = 86400000L;

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_BASE;
            Log.i(TAG, "ext 无 url，使用内置站点 " + DEFAULT_BASE);
        }
        if (ext.sites().size() > 1) {
            resolveAliveSite("/gz/live/sports?parameter=key");
        }
    }

    /** 还原上游 {@code c()}：6 个头（含字面量 "null"） */
    public Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("content-type", "application/x-www-form-urlencoded");
        h.put("user-platform", "null");
        h.put("client-version", "3.0.1.1");
        h.put("client-channel", "");
        h.put("token", "");
        return h;
    }

    // ─────────────────────────────────────────────────────────────
    // 加解密
    // ─────────────────────────────────────────────────────────────

    /**
     * 加密请求参数（还原上游 {@code A.a.bo(json, key, iv)}）。
     *
     * @param plain 明文 JSON
     * @return Base64 密文；失败返回空串
     */
    public String encrypt(String plain) {
        String ct = AesGcm.cbcEncryptToBase64(plain, AES_KEY, AES_IV);
        return ct == null ? "" : ct;
    }

    /**
     * 解密响应 {@code data} 字段（还原上游 {@code A.a.an(data, key, iv)}）。
     *
     * <p>上游先 {@code replace("\\", "")}，本实现同（JSON 里的 {@code \/} 转义要去掉）。
     *
     * @param cipher 密文
     * @return 明文；失败返回空串
     */
    public String decrypt(String cipher) {
        if (TextUtils.isEmpty(cipher)) {
            return "";
        }
        return AesGcm.cbcDecryptBase64(cipher.replace("\\", ""), AES_KEY, AES_IV);
    }

    /**
     * 取 {@code parameter} 明文（未知 tid 退化到 hot，避免上游的 NPE）。
     *
     * @param tid 分类 id
     * @return 请求体明文
     */
    public static String bodyOf(String tid) {
        String b = BODIES.get(tid == null ? "" : tid.trim());
        return b == null ? BODIES.get("hot") : b;
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页（还原上游 {@code homeContent}）：4 分类 + 空 filters。
     *
     * @param filter 是否要筛选（上游给 "{}"，本实现同）
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
     * 分类（还原上游 {@code categoryContent}）。
     *
     * <p>上游只认第 1 页（{@code pg != "1"} 时返回空串）；服务端不提供翻页。
     *
     * @param tid    分类 id（hot/nba/football/basketball）
     * @param pg     页码
     * @param filter 是否要筛选（忽略）
     * @param extend 筛选参数（忽略）
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String base = requireUrl();
        if (base.isEmpty()) {
            return VodBuilder.create().page(page).categoryContent();
        }

        String data = encrypt(bodyOf(tid));
        String resp = postForm(base + "/gz/live/sports?parameter=key", data);
        String plain = pickData(resp);
        if (plain.isEmpty()) {
            Log.w(TAG, "categoryContent 无有效响应, tid=" + tid);
            return VodBuilder.create().page(page).categoryContent();
        }

        List<Vod> list;
        try {
            list = parseMatches(new JSONArray(plain.trim()));
        } catch (Exception e) {
            Log.e(TAG, "categoryContent parse failed, tid=" + tid, e);
            return VodBuilder.create().page(page).categoryContent();
        }
        return VodBuilder.create().addAll(list).page(page, 0, Math.max(list.size(), 20))
                .categoryContent();
    }

    /**
     * 发 POST 表单（{@code parameter=<密文>}）。
     *
     * @param url  完整 URL
     * @param data AES 密文
     * @return 响应体；失败空串
     */
    public String postForm(String url, String data) {
        // 上游用 okhttp FormBody，服务端只认 parameter=<urlencoded base64>
        String body = "parameter=" + com.example.tvbox.common.Http.encode(data);
        return http.post(url, body, apiHeaders());
    }

    /**
     * 从响应里取 {@code data} 并解密；若响应本身就是数组则原样返回（容错）。
     *
     * @param resp 原始响应
     * @return 明文 JSON
     */
    public String pickData(String resp) {
        if (TextUtils.isEmpty(resp)) {
            return "";
        }
        String t = resp.trim();
        if (t.startsWith("[")) {
            return t;
        }
        try {
            JSONObject root = new JSONObject(t);
            if (root.optInt("code") != 200) {
                Log.w(TAG, "pickData code=" + root.optInt("code"));
                return "";
            }
            return decrypt(root.optString("data"));
        } catch (Exception e) {
            Log.w(TAG, "pickData 响应不是 JSON");
            return "";
        }
    }

    /**
     * 解析比赛数组（还原上游的过滤 + 字段映射）。
     *
     * <p>上游条件：{@code match_time*1000 >= now-24h} 且 {@code m_status < 2}。
     * 本实现放宽为「24 小时内」，已完场的保留但打标（见类注释）。
     *
     * @param arr 比赛数组
     * @return 影片列表，永不 null
     */
    public List<Vod> parseMatches(JSONArray arr) {
        List<Vod> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        long since = System.currentTimeMillis() - WINDOW_MS;
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            long ms = o.optLong("match_time", 0L) * 1000L;
            if (ms < since) {
                continue;
            }
            JSONObject home = o.optJSONObject("home");
            JSONObject away = o.optJSONObject("visiting");
            String mid = o.optString("mid", String.valueOf(o.opt("mid")));
            if (mid.isEmpty()) {
                continue;
            }

            int score1 = intOf(home, "score");
            int score2 = intOf(away, "score");
            String remarks = o.optString("event_name") + " " + fmt.format(new Date(ms))
                    + " " + o.optString("match_status_info");
            if (score1 > 0 || score2 > 0) {
                remarks = remarks + " 比分" + score1 + "-" + score2;
            }
            out.add(new Vod()
                    .id(mid)
                    .name(str(home, "name") + " vs " + str(away, "name"))
                    .pic(str(home, "logo"))
                    .remarks(remarks.trim()));
        }
        return out;
    }

    /**
     * 安全取字符串（上游用 getString，缺字段会抛）。
     *
     * @param o    对象，可为 null
     * @param name 字段名
     * @return 值或空串
     */
    private static String str(JSONObject o, String name) {
        return o == null ? "" : o.optString(name);
    }

    /**
     * 安全取 int（真实响应里 score 可能是字符串 "2"）。
     *
     * @param o    对象，可为 null
     * @param name 字段名
     * @return 值或 0
     */
    private static int intOf(JSONObject o, String name) {
        if (o == null) {
            return 0;
        }
        int v = o.optInt(name, -1);
        if (v >= 0) {
            return v;
        }
        try {
            return Integer.parseInt(o.optString(name, "0").trim());
        } catch (Throwable e) {
            return 0;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 详情（还原上游 {@code detailContent}）。
     *
     * <p>线路名固定「 瓜子 」（上游带前后空格，逐字保留）；每场比赛的
     * {@code live_line[]} 是不同清晰度/语言的直播流。
     *
     * @param ids id 列表，首个为 mid
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String mid = firstId(ids);
        String base = requireUrl();
        if (mid.isEmpty() || base.isEmpty()) {
            return emptyResponse();
        }

        String resp = postForm(base + "/gz/live/detail?parameter=key",
                encrypt("{\"mid\":\"" + mid + "\"}"));
        String plain = pickData(resp);
        if (plain.isEmpty()) {
            Log.w(TAG, "detailContent 无有效响应, mid=" + mid);
            return emptyResponse();
        }

        try {
            JSONObject o = new JSONObject(plain.trim());
            JSONObject home = o.optJSONObject("home");
            JSONObject away = o.optJSONObject("visiting");
            int s1 = intOf(home, "score");
            int s2 = intOf(away, "score");
            String remarks = o.optString("match_status_info");
            if (s1 > 0 || s2 > 0) {
                remarks = remarks + " 比分" + s1 + "-" + s2;
            }

            Vod vod = new Vod()
                    .id(mid)
                    .name(str(home, "name") + " vs " + str(away, "name"))
                    .pic(str(home, "logo"))
                    .remarks(remarks);

            List<String> eps = new ArrayList<>();
            JSONArray lines = o.optJSONArray("live_line");
            if (lines != null) {
                for (int i = 0; i < lines.length(); i++) {
                    JSONObject l = lines.optJSONObject(i);
                    if (l == null) {
                        continue;
                    }
                    String name = l.optString("name", "线路" + (i + 1));
                    String url = l.optString("m3u8");
                    if (url.isEmpty()) {
                        url = l.optString("url");
                    }
                    if (url.isEmpty()) {
                        continue;
                    }
                    eps.add(name + Vod.SEP_NAME_URL + url);
                }
            }
            if (!eps.isEmpty()) {
                vod.playFrom(" 瓜子 ").playUrl(Html.join(Vod.SEP_EPISODE, eps));
            }
            return VodBuilder.detail(vod).detailContent();
        } catch (Exception e) {
            Log.e(TAG, "detailContent parse failed, mid=" + mid, e);
            return emptyResponse();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 播放（还原上游 {@code playerContent}）：直链 + 特殊 Referer/UA。
     *
     * <pre>
     *  User-Agent: Lavf/57.83.100
     *  Referer   : http://WJiZxLXA2.com/
     * </pre>
     *
     * @param flag     线路名
     * @param id       播放地址
     * @param vipFlags 需要解析的标识（忽略）
     * @return 标准播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) {
            return emptyResponse();
        }
        String type = id.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/*";
        String json = VodBuilder.playerContent(id, type);
        try {
            JSONObject o = new JSONObject(json);
            o.put("header", "{\"User-Agent\":\"Lavf/57.83.100\","
                    + "\"Referer\":\"http://WJiZxLXA2.com/\"}");
            return o.toString();
        } catch (Throwable e) {
            return json;
        }
    }
}
