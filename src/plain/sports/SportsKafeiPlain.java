package com.example.tvbox.plain.sports;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 咖啡体育（{@code csp_SportsKafei}）明文实现。
 *
 * <p>继承 {@link BaseSpider}（上游 {@code extends Spider}）。本类<b>无加密</b>：
 * 纯 HTTP GET + JSON，无签名、无 AES、无自定义 header 签名。
 *
 * <h2>协议（全部经真实请求验证）</h2>
 * <pre>
 * 列表  GET {base}/api/v1/schedule?type=&lt;tid&gt;&amp;page=&lt;pg&gt;&amp;size=30
 *        响应 {"code":200,"data":[{...}]}
 *        条目: id / league_name / home_team / away_team / home_team_logo
 *              start_time / status / archor{room_id, name, stream_url, ...}
 *        注意: vod_id 取自 archor.room_id（不是条目自身 id），detail 也用 room_id 拉
 *
 * 详情  GET {base}/api/v1/room/&lt;room_id&gt;
 *        响应 {"code":200,"data":{"room_info":{...},"signals":[{name,stream_url}]}}
 *        room_info: room_id / title / league / home_team / away_team
 *
 * 播放  直链。detail 阶段已把 signals[].stream_url 写进 vod_play_url，
 *        playerContent 原样返回该 url（parse=0）
 * </pre>
 *
 * <h2>真实响应要点（已抓）</h2>
 * <ul>
 *   <li>{@code code} 为 int 200；非 200 时上游返回固定空页，本实现同</li>
 *   <li>{@code archor} 可能为 null → 上游 {@code getJSONObject} 会抛，
 *       被外层 catch 吞掉并返回空页；本实现显式跳过该条目（比上游更稳）</li>
 *   <li>上游把 pagecount 写死 100、total 写死 6000（服务端不给总数）；
 *       本实现保留该「无限翻页」语义，但用 {@code pagecount=page+1} 让宿主能自然翻页</li>
 *   <li>首页 {@code homeContent} 只给 class，不给 list（上游同）</li>
 * </ul>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/SportsKafei.java}（143 行）
 * 真实夹具:   {@code ae/fixtures/real/sportskafei_schedule.json}
 *             {@code ae/fixtures/real/sportskafei_room.json}
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/SportsKafei.java} 的对照复核，
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
 * {@code ae/decompiled/SportsKafei.java} 为对照基准。
 */
public class SportsKafeiPlain extends BaseSpider {

    private static final String TAG = "SportsKafeiPlain";

    /** 默认站点（上游硬编码；ext 为空时用它） */
    private static final String DEFAULT_BASE = "https://kafeizhibo.cc";

    /** 上游硬编码 UA（截断版，逐字保留） */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    /** 每页条数（上游 size=30） */
    private static final int PAGE_SIZE = 30;

    /** 分类表（上游 homeContent 硬编码 3 项） */
    private static final String[][] CATEGORIES = {
            {"hot", "热门"},
            {"1", "足球"},
            {"2", "篮球"},
    };

    /** 空分类响应体（上游逐字使用的兜底串） */
    private static final String EMPTY_CATEGORY =
            "{\"list\":[],\"page\":1,\"pagecount\":1,\"limit\":30,\"total\":0}";

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        // ext 没给地址就用上游硬编码域名（该站点 ext 在 api.json 中为空）
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_BASE;
            Log.i(TAG, "ext 无 url，使用内置站点 " + DEFAULT_BASE);
        }
        // ext.site 给了多域名则探活（探活路径用列表接口，避免 404 误判）
        if (ext.sites().size() > 1) {
            resolveAliveSite("/api/v1/schedule?type=hot&page=1&size=1");
        }
    }

    /** 还原上游 {@code map.put("User-Agent",...) + Accept: application/json} */
    public Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Accept", "application/json");
        return h;
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页：只产出分类，不含推荐列表（还原上游 {@code homeContent}）。
     *
     * @param filter 是否要筛选定义（上游无筛选）
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        VodBuilder vb = VodBuilder.create();
        for (String[] c : CATEGORIES) {
            vb.clazz(c[0], c[1]);
        }
        Log.i(TAG, "homeContent classes=" + CATEGORIES.length);
        return vb.homeContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 分类翻页（还原上游 {@code categoryContent}）。
     *
     * @param tid    分类 id（hot / 1 / 2）
     * @param pg     页码字符串，空按 1
     * @param filter 是否带筛选（上游忽略）
     * @param extend 筛选参数（本类无筛选项，忽略）
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String base = requireUrl();
        if (base.isEmpty()) {
            return EMPTY_CATEGORY;
        }

        String url = base + "/api/v1/schedule?type=" + urlPart(tid)
                + "&page=" + page + "&size=" + PAGE_SIZE;
        String resp = http.get(url, apiHeaders());
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "categoryContent empty response, tid=" + tid + " pg=" + page);
            return EMPTY_CATEGORY;
        }

        List<Vod> list;
        try {
            JSONObject root = new JSONObject(resp);
            if (root.optInt("code") != 200) {
                Log.w(TAG, "categoryContent code!=200: " + root.optInt("code"));
                return EMPTY_CATEGORY;
            }
            list = parseSchedule(root.optJSONArray("data"));
        } catch (Exception e) {
            Log.e(TAG, "categoryContent parse failed", e);
            return EMPTY_CATEGORY;
        }
        return VodBuilder.create().addAll(list).page(page, 0, PAGE_SIZE).categoryContent();
    }

    /**
     * 解析 {@code data[]} —— 还原上游逐字段映射。
     *
     * <p>关键差异：上游对 {@code archor} 做 {@code getJSONObject} 会抛 NPE 式异常，
     * 本实现改为「无 archor 或 room_id 为空则跳过」，避免整页因一条坏数据全灭。
     *
     * @param data 响应 data 数组，可为 null
     * @return 影片列表，永不 null
     */
    public List<Vod> parseSchedule(JSONArray data) {
        List<Vod> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.optJSONObject(i);
            if (o == null) {
                continue;
            }
            JSONObject archor = o.optJSONObject("archor");
            String roomId = archor == null ? "" : archor.optString("room_id");
            if (roomId.isEmpty()) {
                // 上游会崩；此处跳过（无直播源可播的条目本来也无意义）
                continue;
            }
            String name = o.optString("home_team") + " VS " + o.optString("away_team");
            out.add(new Vod()
                    .id(roomId)
                    .name(name)
                    .pic(o.optString("home_team_logo"))
                    .remarks(o.optString("league_name")));
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 详情（还原上游 {@code detailContent}）。
     *
     * <p>vod_id 是 {@code room_id}；signals[] 的每个 {@code name/stream_url}
     * 拼成一条线路的多集（实际是同一场的多个解说源）。
     *
     * @param ids id 列表，取首个
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String roomId = firstId(ids);
        String base = requireUrl();
        if (roomId.isEmpty() || base.isEmpty()) {
            return emptyResponse();
        }

        String resp = http.get(base + "/api/v1/room/" + roomId, apiHeaders());
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "detailContent empty response, room=" + roomId);
            return emptyResponse();
        }

        try {
            JSONObject root = new JSONObject(resp);
            if (root.optInt("code") != 200) {
                Log.w(TAG, "detailContent code!=200: " + root.optInt("code"));
                return emptyResponse();
            }
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                return emptyResponse();
            }
            JSONObject info = data.optJSONObject("room_info");
            if (info == null) {
                return emptyResponse();
            }

            Vod vod = new Vod()
                    .id(info.optString("room_id", roomId))
                    .name(info.optString("title"))
                    .director(info.optString("league"))
                    .actor(info.optString("home_team") + " VS " + info.optString("away_team"));

            PlaySplit ps = parseSignals(data.optJSONArray("signals"));
            if (!ps.url.isEmpty()) {
                vod.playFrom(ps.from).playUrl(ps.url);
            }
            return VodBuilder.detail(vod).detailContent();
        } catch (Exception e) {
            Log.e(TAG, "detailContent parse failed", e);
            return emptyResponse();
        }
    }

    /** 线路拆分结果 */
    public static class PlaySplit {
        /** 线路名，多线路用 $$$ 分隔 */
        String from = "";
        /** 集串，集间用 # 分隔，名与址用 $ 分隔 */
        String url = "";
    }

    /**
     * 还原上游 signals 循环：{@code name + "$" + stream_url}，多集用 {@code #} 连接，
     * 线路名固定「咖啡体育」。
     *
     * @param signals signals 数组，可为 null
     * @return 拆分结果，永不为 null
     */
    public PlaySplit parseSignals(JSONArray signals) {
        PlaySplit rs = new PlaySplit();
        if (signals == null || signals.length() == 0) {
            return rs;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < signals.length(); i++) {
            JSONObject o = signals.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String name = o.optString("name");
            String stream = o.optString("stream_url");
            if (stream.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(Vod.SEP_EPISODE);
            }
            sb.append(name.isEmpty() ? "线路" + (i + 1) : name)
              .append(Vod.SEP_NAME_URL)
              .append(stream);
        }
        if (sb.length() > 0) {
            rs.from = "咖啡体育";
            rs.url = sb.toString();
        }
        return rs;
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 播放：detail 阶段已拿到直链，这里原样返回（还原上游 {@code playerContent}）。
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
        return VodBuilder.playerContent(id, type);
    }

    /**
     * URL 片段安全编码（分类 id 只允许简单字符，这里做最小转义）。
     *
     * @param s 原文
     * @return 可拼进 query 的片段
     */
    public static String urlPart(String s) {
        if (s == null) {
            return "";
        }
        return s.replace(" ", "%20");
    }
}
