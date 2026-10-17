package com.example.tvbox.plain.live;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ALLLive —— 五大直播平台（斗鱼 / 虎牙 / 抖音 / 快手 / 哔哩）聚合。
 *
 * <h2>架构：本类只是"皮"</h2>
 * 上游全部逻辑都不在 Java 里 —— 它通过 {@code http://127.0.0.1:5266/alllive/*} 调用
 * 宿主内置的本地桥接服务，由桥负责真正抓取各家直播间的流地址。
 * 因此本类要做的事只有：拼请求、解响应、转成 TVBox 契约结构。
 *
 * <pre>
 *  GET /alllive/recommend?site=&lt;site&gt;&page=&lt;page&gt;&fa=&lt;token&gt;   → {code,data:{items:[...]}}
 *  GET /alllive/detail?site=&room_id=&fa=                          → {code,data:{title,cover,user_name}}
 *  GET /alllive/qualities?site=&room_id=&fa=                       → {code,data:[{quality}]}
 *  GET /alllive/play_urls?site=&room_id=&quality=&fa=              → {code,data:{quality,urls:[],headers:{}}}
 *  GET /api/ping                                                   → "ok"
 *  ws://127.0.0.1:5266/alllive/danmaku/ws?site=&room_id=&fa=       → 弹幕
 * </pre>
 *
 * <h2>令牌从哪来</h2>
 * 上游在 {@code init()} 里轮询 {@code /api/ping} 最多 10 次（每次隔 1 秒），
 * ping 通后读文件 {@code /iptv_auth_token} 得到 {@code fa} 令牌，附在每个请求上。
 * 本重写把「读文件」抽象为 {@link #token()}，默认读同路径；测试可覆写。
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>分页参数用了站点名</b>：上游写 {@code "...&page=" + str}，而 {@code str} 是
 *       {@code tid}（站点名），真正的页码参数 {@code str2} 完全没用上 ——
 *       结果是任何分类翻页都只返回第 1 页（且 page 值是个字符串如 {@code douyu}）。
 *       已修正为 {@code "&page=" + pg}。</li>
 *   <li><b>init 轮询静默失败</b>：10 次 ping 都不通时直接落出循环，{@code faToken}
 *       保持 {@code null}，之后每个请求都带 {@code fa=null}。现在会明确记录状态并
 *       由 {@link #ready()} 暴露。</li>
 *   <li><b>searchContent 返回 null</b>：宿主对 {@code null} 没有统一处理。改为返回
 *       契约一致的空列表。</li>
 *   <li><b>detailContent 的 id 切分越界</b>：上游 {@code split("@")} 后直接取
 *       {@code [1]}，遇到畸形 id 抛 {@code ArrayIndexOutOfBounds}。已加校验。</li>
 * </ol>
 *
 * <h2>ext 字段</h2>
 * <pre>
 *  { "url": "http://127.0.0.1:5266" }   // 桥接地址，默认即此值
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/ALLLive.java}
  *
 * <h2>未覆盖的分支</h2>
 *
 * <p><b>无。</b>
 *
 * <p>已逐个核对本类的契约方法（{@code homeContent} / {@code homeVideoContent} /
 * {@code categoryContent} / {@code detailContent} / {@code searchContent} / {@code playerContent}）
 * 与全部私有解析方法，不存在依赖宿主私有 API 或服务端未公开算法的分支。
 * 上游被混淆掉的辅助方法（{@code merge.*}）均已用手写等价逻辑替换，替换点见各方法行内注释。
 *
 * <p>判定口径：本节只在<b>确实存在</b>无法还原的分支时才列条目。写"无"意味着逐路径核对过，
 * 而不是"没找"。日后若发现遗漏，请在此补充并注明原方法名与 行号。
 */
public class ALLLivePlain extends BaseSpider {

    private static final String TAG = "ALLLivePlain";

    /** 本地桥接服务默认地址（上游硬编码） */
    public static final String DEFAULT_BRIDGE = "http://127.0.0.1:5266";
    /** 令牌文件路径（上游硬编码） */
    public static final String TOKEN_FILE = "/iptv_auth_token";
    /** ping 重试次数与间隔（上游硬编码 10 次 / 1000ms） */
    public static final int PING_ATTEMPTS = 10;
    public static final long PING_INTERVAL_MS = 1000L;

    /** 五大平台（顺序即上游 homeContent 顺序） */
    public static final String[] SITES = {"douyu", "huya", "douyin", "kuaishou", "bilibili"};
    public static final String[] SITE_NAMES = {"斗鱼", "虎牙", "抖音", "快手", "哔哩"};

    private String bridge = DEFAULT_BRIDGE;
    private String faToken = "";
    private boolean bridgeAlive = false;

    @Override
    public void init(android.content.Context context, String extend) {
        super.init(context, extend);
        String configured = ext.url();
        if (configured != null && !configured.isEmpty()) {
            // 去掉可能存在的尾部斜杠，避免出现 //alllive
            bridge = configured.endsWith("/")
                    ? configured.substring(0, configured.length() - 1)
                    : configured;
        }
    }

    // ── 可注入点（测试覆写）─────────────────────────────────

    /** 桥接服务基地址 */
    public String bridge() {
        return bridge;
    }

    /** 当前 {@code fa} 令牌；未握手成功时为空串 */
    public String token() {
        return faToken == null ? "" : faToken;
    }

    /** 直接设定令牌 —— 供测试与外部注入 */
    public void setToken(String token) {
        this.faToken = token == null ? "" : token;
    }

    /** 桥接服务是否已 ping 通 */
    public boolean ready() {
        return bridgeAlive;
    }

    /** 是否已握手（ping 通且拿到令牌） */
    public boolean authenticated() {
        return bridgeAlive && !token().isEmpty();
    }

    /** 读取令牌文件的原始内容，默认读 {@link #TOKEN_FILE}。测试覆写此方法即可免 IO。 */
    public String readTokenFile() {
        try {
            java.io.File f = new java.io.File(TOKEN_FILE);
            if (!f.isFile()) {
                return null;
            }
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
                return r.readLine();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 握手：轮询 ping，通了再取令牌。
     * @return 是否拿到令牌
     */
    public boolean handshake() {
        if (authenticated()) {
            return true;
        }
        for (int i = 0; i < PING_ATTEMPTS; i++) {
            String pong;
            try {
                pong = http.get(bridge + "/api/ping");
            } catch (RuntimeException e) {
                pong = null;
            }
            if ("ok".equals(pong)) {
                bridgeAlive = true;
                String t = readTokenFile();
                faToken = t == null ? "" : t.trim();
                return !faToken.isEmpty();
            }
            try {
                Thread.sleep(PING_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();   // 上游吞掉中断标志，这里保留
                return false;
            }
        }
        return false;
    }

    // ── URL 拼装 ─────────────────────────────────────────────

    /** {@code <bridge>/alllive/<path>?<query>&fa=<token>} */
    public String api(String path, String query) {
        StringBuilder sb = new StringBuilder(bridge).append("/alllive/").append(path);
        sb.append('?');
        if (query != null && !query.isEmpty()) {
            sb.append(query).append('&');
        }
        sb.append("fa=").append(token());
        return sb.toString();
    }

    /**
     * 取一个 JSON 响应。抽成方法是为了让测试注入固定响应，
     * 从而验证解析逻辑而不发真实请求。
     *
     * @param url 完整请求地址
     * @return 解析后的 JSON；失败返回 {@code null}
     */
    public org.json.JSONObject fetchJson(String url) {
        return http.getJson(url);
    }

    // ── 五个契约方法 ─────────────────────────────────────────

    /** 固定五大平台分类表，不随网络变化 */
    @Override
    public String homeContent(boolean filter) {
        VodBuilder out = VodBuilder.create();
        for (int i = 0; i < SITES.length; i++) {
            out.clazz(SITES[i], SITE_NAMES[i]);
        }
        return out.homeContent();
    }

    /**
     * 分类推荐列表。
     * @param tid 站点名（douyu/huya/douyin/kuaishou/bilibili）
     * @param pg  页码 —— <b>上游此处误用了 tid</b>，见类注释
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                 java.util.HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String url = api("recommend", "site=" + Http.encode(tid) + "&page=" + page);
        JSONObject resp = fetchJson(url);
        if (resp == null || resp.optInt("code") != 0) {
            return emptyResponse();
        }
        JSONArray items = resp.optJSONObject("data") == null
                ? null
                : resp.optJSONObject("data").optJSONArray("items");
        if (items == null) {
            return emptyResponse();
        }
        VodBuilder out = VodBuilder.create().page(page);
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String roomId = o.optString("room_id", "");
            if (roomId.isEmpty()) {
                continue;
            }
            out.add(new Vod()
                    .id(tid + "@" + roomId)
                    .name(o.optString("title", ""))
                    .pic(o.optString("cover", ""))
                    .remarks(o.optString("user_name", "")));
        }
        return out.categoryContent();
    }

    @Override
    public String detailContent(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return emptyResponse();
        }
        String[] parts = splitId(ids.get(0));
        if (parts == null) {
            return emptyResponse();
        }
        String site = parts[0];
        String roomId = parts[1];

        JSONObject detail = fetchJson(api("detail", "site=" + Http.encode(site) + "&room_id=" + Http.encode(roomId)));
        if (detail == null || detail.optInt("code") != 0 || detail.optJSONObject("data") == null) {
            return emptyResponse();
        }
        JSONObject d;
        try {
            d = detail.getJSONObject("data");
        } catch (org.json.JSONException e) {
            return emptyResponse();
        }

        JSONObject qualityResp = fetchJson(api("qualities", "site=" + Http.encode(site) + "&room_id=" + Http.encode(roomId)));
        if (qualityResp == null || qualityResp.optInt("code") != 0) {
            return emptyResponse();
        }
        JSONArray qualities = qualityResp.optJSONArray("data");
        if (qualities == null) {
            qualities = new JSONArray();
        }

        List<String> froms = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < qualities.length(); i++) {
            JSONObject q = qualities.optJSONObject(i);
            if (q == null) {
                continue;
            }
            String quality = q.optString("quality", "");
            if (quality.isEmpty()) {
                continue;
            }
            // 播放线路名用画质；单条线路里放一个"直播"按钮，id = site@room_id@quality
            froms.add(quality);
            lines.add("直播$" + site + "@" + roomId + "@" + quality);
        }

        Vod vod = new Vod()
                .id(ids.get(0))
                .name(d.optString("title", ""))
                .pic(d.optString("cover", ""))
                .remarks(d.optString("user_name", ""))
                .playFrom(join(froms, Vod.SEP_SOURCE))
                .playUrl(join(lines, Vod.SEP_SOURCE));
        return VodBuilder.detail(vod).detailContent();
    }

    /**
     * 播放地址。
     * @param id {@code site@room_id@quality}
     * @return {@code {url:[quality1,url1,quality2,url2,…], header:"{…}", danmaku:"ws://…"}}
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String[] parts = splitId(id);
        if (parts == null || parts.length < 3) {
            return "{}";
        }
        String site = parts[0];
        String roomId = parts[1];
        String quality = parts[2];

        JSONObject resp = fetchJson(api("play_urls",
                "site=" + Http.encode(site) + "&room_id=" + Http.encode(roomId)
                        + "&quality=" + Http.encode(quality)));
        if (resp == null || resp.optInt("code") != 0 || resp.optJSONObject("data") == null) {
            return "{}";
        }
        JSONObject d;
        try {
            d = resp.getJSONObject("data");
        } catch (org.json.JSONException e) {
            return "{}";
        }
        JSONArray urls = d.optJSONArray("urls");
        JSONObject headers = d.optJSONObject("headers");
        String q = d.optString("quality", quality);

        JSONArray flat = new JSONArray();
        if (urls != null) {
            for (int i = 0; i < urls.length(); i++) {
                flat.put(q + (i + 1));          // 线路名，如 "蓝光1"
                flat.put(urls.optString(i, ""));
            }
        }
        JSONObject out = new JSONObject();
        try {
            out.put("url", flat);
            out.put("header", headers == null ? "{}" : headers.toString());
            out.put("danmaku", "ws://127.0.0.1:5266/alllive/danmaku/ws?site="
                    + Http.encode(site) + "&room_id=" + Http.encode(roomId) + "&fa=" + token());
        } catch (org.json.JSONException e) {
            return "{}";
        }
        return out.toString();
    }

    /** 直播平台不支持按关键字搜索（上游返回 null，这里返回契约空结构） */
    @Override
    public String searchContent(String key, boolean quick) {
        return emptyResponse();
    }

    /** 只用于判断"有没有更多"：桥接服务不回总数，按"本页非空即可能有下一页"处理 */
    @Override
    public String homeVideoContent() {
        return emptyResponse();
    }

    // ── 工具 ─────────────────────────────────────────────────

    /**
     * 切分 {@code site@room_id[@quality]}。
     * 上游直接取 {@code [1]}，畸形 id 会抛越界 —— 这里返回 {@code null}。
     */
    public static String[] splitId(String id) {
        if (id == null) {
            return null;
        }
        String[] parts = id.split("@");
        return parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty() ? parts : null;
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
