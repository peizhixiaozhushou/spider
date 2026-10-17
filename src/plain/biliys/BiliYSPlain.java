package com.example.tvbox.plain.biliys;

import android.util.Log;

import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.bili.BiliBase;
import com.example.tvbox.plain.bili.BiliDash;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BiliYS 明文实现（哔哩哔哩影视/番剧源）。
 *
 * <p>反编译原文: {@code ae/decompiled/BiliYS.java}（310 行，jadx dev）
 *
 * <h2>协议</h2>
 * <pre>
 *  首页       静态（不发请求）→ 6 个分类 + 固定 filters
 *  分类       GET /pgc/season/index/result?order=..&amp;season_status=..&amp;style_id=-1
 *             &amp;sort=-1&amp;area=-1&amp;pagesize=20&amp;type=1&amp;season_type={tid}&amp;page={pg}
 *  详情       GET /pgc/view/web/season?season_id={id}       → result{... episodes[]}
 *  搜索       GET /x/web-interface/wbi/search/type?search_type=media_ft|media_bangumi
 *             &amp;keyword=..&amp;page=..&amp;w_rid={32 位随机}
 *  取流       GET /pgc/player/web/playurl?avid=..&amp;cid=..&amp;qn=127&amp;fnval=4048&amp;fourk=1
 *  心跳       POST /x/click-interface/web/heartbeat
 *  弹幕       GET /x/v1/dm/list.so?oid={cid}
 *  代理播放   {proxyBase}?do=biliys&amp;aid=..&amp;cid=..&amp;qn=..&amp;type=mpd   → MPD
 * </pre>
 *
 * <h2>ext 三层分类</h2>
 * 继承 {@link BiliBase}：<b>没有 A 类键</b>；B 类为
 * {@code cookie} / {@code cookieFile} / {@code proxyBase}；
 * 其余（UA、Referer、全部 API、分类表、filters、清晰度阈值、{@code qn=127}）都是 C 类硬编码。
 *
 * <h2>发现的上游缺陷（本实现已修，均有断言钉住）</h2>
 * <ol>
 *   <li><b>构造函数发网络请求</b>：{@code BiliYS()} 里直接
 *       {@code new JSONObject(b.l("…/nav", e())).getJSONObject("data").optString("mid")}。
 *       构造即联网 —— 宿主在离线/启动阶段实例化会卡住，且异常被空 catch 吞掉。
 *       本实现改为惰性 {@code ensureNav()}。</li>
 *   <li><b>分类页 pagecount 用整除</b>：{@code parseInt(total)/parseInt(size)}，
 *       {@code total=41,size=20} 得 1 页（实际 3 页）。本实现用向上取整。
 *       另外 {@code parseInt("")} 会抛 NumberFormatException 直接崩分类页。</li>
 *   <li><b>{@code w_rid} 是随机串</b>：真实 B 站要求 WBI 签名，随机值会返回
 *       {@code -403}。本实现保留该行为（零网络无法验证真实签名），但在返回值里
 *       对空结果做容错，并在 javadoc 标注。</li>
 *   <li><b>{@code Log.d("HeartbeatResponse", …)} 里真发心跳</b>：把带副作用的请求
 *       塞进日志参数。本实现保留心跳（协议需要）但抽成可关闭的
 *       {@link #setHeartbeatEnabled(boolean)}。</li>
 *   <li><b>搜索标题不去重</b>：{@code media_ft} 与 {@code media_bangumi} 会返回同一部番，
 *       上游直接 append 两遍。本实现在 {@link BiliBase#mergeSearch} 里按 vod_id 去重。</li>
 * </ol>

 * <h2>未覆盖的分支</h2>
 * <ul>
 *   <li><b>WBI 签名</b>：上游 {@code ae/decompiled/BiliYS.java} 的 {@code w_rid} 用的是
 *       32 位<b>随机串</b>（{@code merge.A.a.m1b(32)}），而真实 B 站要求 WBI 签名
 *       （{@code mixinKey + 排序参数 + md5}，同工程的 {@code BiliPlain.getQuery} 里有完整算法）。
 *       本实现<b>保留</b>上游行为并在断言里钉住；对真实站点搜索结果大概率为空。
 *       将来补上需要：把 {@code BiliPlain.getQuery} 的算法接进来 + 抓包验证。</li>
 *   <li><b>{@code merge.A.b.a(url)} 的变换未知</b>：{@code detailContent} 里用它包了一层
 *       {@code k.l(...)} 的 URL，单参 helper 没提取到实体。本实现直接 {@code fetch(url)}；
 *       若它做的是 URL 规范化，非普通详情地址可能表现不同。</li>
 *   <li><b>宿主私有 {@code Proxy.getUrl()}</b>：MPD 代理地址依赖它，全仓无等价物。
 *       改为可注入 {@code proxyBase}；未注入时 {@code playerContent} 返回带 {@code msg}
 *       的占位体，而不是伪造地址。断言：{@code 无代理 → 有 msg}。</li>
 *   <li><b>cookie 文件位置</b>：上游读宿主 cache 目录（{@code k.b("/bili_cookie.txt")}）；
 *       本实现按工程约定用 {@code getFilesDir()} → {@code java.io.tmpdir}（可注入），
 *       以便与 {@code AmuBiliV2Plain#persistSession} 落盘的位置对齐。</li>
 * </ul>
 */
public class BiliYSPlain extends BiliBase {

    private static final String TAG = "BiliYSPlain";

    /** 分类 id → 名称（C 类硬编码；顺序即上游 {@code Arrays.asList} 的顺序） */
    public static final String[] TYPE_IDS = {"1", "2", "3", "4", "5", "7"};
    public static final String[] TYPE_NAMES = {"番剧", "电影", "纪录", "国创", "剧集", "综艺"};

    /** 分类页固定参数 */
    public static final String PAGESIZE = "20";
    public static final String STYLE_ID = "-1";
    public static final String SORT = "-1";
    public static final String AREA = "-1";
    public static final String TYPE = "1";
    /** 排序默认（上游 {@code map.containsKey("order") ? ... : "2"}） */
    public static final String DEF_ORDER = "2";
    /** 付费状态默认 */
    public static final String DEF_SEASON_STATUS = "-1";

    /** 详情线路名（上游硬编码） */
    public static final String PLAY_FROM = "哔哩影视";

    /** 番剧分类下才挂 filters 的键 */
    public static final String[] FILTERED_TYPES = {"1", "2", "3", "4", "5", "7"};

    /** 播放代理动作 */
    public static final String PROXY_ACTION = "biliys";

    /** 心跳开关（默认开 —— 上游确实在发） */
    private boolean heartbeatEnabled = true;

    /** 最近一次搜索结果缓存（上游的 {@code this.i} 在 AppFox 里，BiliYS 没有；这里仅供测试观察） */
    private int lastSearchAdded = 0;

    /** 构造 */
    public BiliYSPlain() {
        super();
    }

    /**
     * 是否发送心跳。
     *
     * @param enabled false 时不发
     */
    public void setHeartbeatEnabled(boolean enabled) {
        this.heartbeatEnabled = enabled;
    }

    // ── 首页 ────────────────────────────────────────────────

    /**
     * 首页：6 个静态分类 + 固定 filters（不发请求）。
     *
     * <p>上游把同一份 filters 在 6 个键下各抄了一遍（合计约 6 KB 字面量）。
     * 本实现按「每个键内容一致」这一观察用构造器生成 —— 输出与逐字复制等价
     * （测试里对 {@code "1"} 与 {@code "7"} 逐字段比对，并断言 6 个键内容相同）。
     *
     * @param filter 忽略（上游无条件返回 filters）
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        VodBuilder b = VodBuilder.create();
        for (int i = 0; i < TYPE_IDS.length; i++) {
            b.clazz(TYPE_IDS[i], TYPE_NAMES[i]);
        }
        JSONObject filters = new JSONObject();
        try {
            for (String t : FILTERED_TYPES) {
                filters.put(t, typeFilters());
            }
        } catch (Throwable e) {
            Log.e(TAG, "filters failed", e);
        }
        // VodBuilder 不接受外部 filters，这里补上（上游的 filters 是固定字面量）
        JSONObject root = Http.parseJson(b.homeContent());
        try {
            root.put("filters", filters);
        } catch (Throwable e) {
            return emptyResponse();
        }
        return root.toString();
    }

    /**
     * 单个分类的筛选组（上游 6 份字面量完全相同）。
     *
     * @return 2 个筛选组：排序 + 付费
     */
    public static JSONArray typeFilters() {
        JSONArray groups = new JSONArray();
        try {
            JSONObject order = new JSONObject();
            order.put("key", "order");
            order.put("name", "排序");
            JSONArray ov = new JSONArray();
            String[][] orders = {
                    {"更新时间", "0"}, {"弹幕数量", "1"}, {"播放数量", "2"},
                    {"追看人数", "3"}, {"最高评分", "4"}, {"开播时间", "5"}, {"上映时间", "6"}};
            for (String[] o : orders) {
                ov.put(new JSONObject().put("n", o[0]).put("v", o[1]));
            }
            order.put("value", ov);
            groups.put(order);

            JSONObject status = new JSONObject();
            status.put("key", "season_status");
            status.put("name", "付费");
            JSONArray sv = new JSONArray();
            String[][] states = {
                    {"全部", "-1"}, {"免费", "1"}, {"付费", "2%2C6"}, {"大会员", "4%2C6"}};
            for (String[] s : states) {
                sv.put(new JSONObject().put("n", s[0]).put("v", s[1]));
            }
            status.put("value", sv);
            groups.put(status);
        } catch (Throwable e) {
            Log.e(TAG, "typeFilters failed", e);
        }
        return groups;
    }

    // ── 分类 ────────────────────────────────────────────────

    /**
     * 分类翻页。
     *
     * @param tid    分类 id（1/2/3/4/5/7）
     * @param pg     页码
     * @param filter 忽略
     * @param extend {@code order} / {@code season_status}
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String url = "https://api.bilibili.com/pgc/season/index/result"
                + "?order=" + enc(extParam(extend, "order", DEF_ORDER))
                + "&season_status=" + enc(extParam(extend, "season_status", DEF_SEASON_STATUS))
                + "&style_id=" + STYLE_ID
                + "&sort=" + SORT
                + "&area=" + AREA
                + "&pagesize=" + PAGESIZE
                + "&type=" + TYPE
                + "&season_type=" + enc(tid)
                + "&page=" + page;
        JSONObject data = Http.parseJson(fetch(url)).optJSONObject("data");
        List<Vod> list = new ArrayList<>();
        if (data != null) {
            JSONArray arr = data.optJSONArray("list");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                list.add(new Vod(o.optString("season_id", ""), o.optString("title", ""))
                        .pic(o.optString("cover", ""))
                        .remarks(o.optString("index_show", "")));
            }
        }
        int size = parseInt(data == null ? "" : data.optString("size", ""), 0);
        int total = parseInt(data == null ? "" : data.optString("total", ""), 0);
        return VodBuilder.create().addAll(list)
                .page(page, total, size > 0 ? size : 0)
                .categoryContent();
    }

    // ── 详情 ────────────────────────────────────────────────

    /**
     * 详情：{@code /pgc/view/web/season?season_id=}。
     *
     * @param ids 影片 id 列表
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (id.isEmpty()) {
            return emptyResponse();
        }
        ensureNav();    // 上游在详情里补一次 nav（为后面的清晰度门控）
        JSONObject result = Http.parseJson(
                fetch("https://api.bilibili.com/pgc/view/web/season?season_id=" + enc(id)))
                .optJSONObject("result");
        if (result == null) {
            Log.w(TAG, "详情无 result, id=" + id);
            return emptyResponse();
        }
        Vod v = new Vod(id, result.optString("title", ""))
                .pic(result.optString("cover", ""))
                .content(result.optString("evaluate", ""))
                .director(result.optString("staff", ""))
                .actor(result.optString("actors", ""));

        List<String> eps = new ArrayList<>();
        JSONArray arr = result.optJSONArray("episodes");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            // 上游: share_copy + "$" + aid + "+" + cid  （"+" 来自 slf4j 的 ANY_NON_NULL_MARKER）
            eps.add(o.optString("share_copy", "") + "$"
                    + o.optString("aid", "") + "+" + o.optString("cid", ""));
        }
        v.playFrom(PLAY_FROM).playUrl(join(eps, "#"));
        return VodBuilder.detail(v).detailContent();
    }

    // ── 搜索 ────────────────────────────────────────────────

    /**
     * 搜索（上游三参数版）。
     *
     * @param key   关键词
     * @param quick 忽略
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, "1");
    }

    /**
     * 搜索（上游三参数重载：{@code quickSearch} 实际当页码用）。
     *
     * @param key        关键词
     * @param quick      忽略
     * @param quickSearch 页码
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick, String quickSearch) {
        return searchContent(key, quickSearch);
    }

    /**
     * 搜索主实现：两个 search_type 端点 + 去重合并。
     *
     * <pre>
     *  /x/web-interface/wbi/search/type?search_type=media_ft|media_bangumi
     *    &amp;keyword={enc}&amp;page={page}&amp;w_rid={32 位随机}
     * </pre>
     *
     * @param key  关键词
     * @param page 页码（字符串）
     * @return 标准搜索 JSON
     */
    public String searchContent(String key, String page) {
        String kw = enc(key == null ? "" : key);
        String pg = page == null || page.trim().isEmpty() ? "1" : page.trim();
        List<Vod> out = new ArrayList<>();
        int added = 0;
        added += mergeSearch(out, fetch(searchApi("media_ft", kw, pg)));
        added += mergeSearch(out, fetch(searchApi("media_bangumi", kw, pg)));
        this.lastSearchAdded = added;
        return VodBuilder.create().addAll(out).searchContent();
    }

    /**
     * 拼搜索地址。
     *
     * @param searchType {@code media_ft} 或 {@code media_bangumi}
     * @param keyword    已 URL 编码的关键词
     * @param page       页码
     * @return 地址
     */
    public String searchApi(String searchType, String keyword, String page) {
        return "https://api.bilibili.com/x/web-interface/wbi/search/type"
                + "?search_type=" + searchType
                + "&keyword=" + keyword
                + "&page=" + page
                + "&w_rid=" + randomId();
    }

    /** @return 上一次搜索实际追加的条数（去重后） */
    public int lastSearchAdded() {
        return lastSearchAdded;
    }

    // ── 播放 ────────────────────────────────────────────────

    /**
     * 播放解析。
     *
     * <p>id 形如 {@code aid+cid}（由 {@link #detailContent} 生成）。
     *
     * @param flag     忽略
     * @param id       {@code aid+cid}
     * @param vipFlags 忽略
     * @return 播放 JSON；无法产地址时返回带 {@code msg} 的空播放体
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String[] p = (id == null ? "" : id).split("\\+");
        if (p.length < 2) {
            Log.w(TAG, "播放 id 段数不足 2: " + id);
            return placeholder();
        }
        String aid = p[0];
        String cid = p[1];

        sendHeartbeat(aid, cid);

        JSONObject root = playInfo(aid, cid, QN_MAX);
        JSONObject body = BiliDash.body(root);
        BiliDash dash = BiliDash.parse(root);
        List<List<String>> kept = filterQuality(
                body.optJSONArray("accept_quality"), body.optJSONArray("accept_description"));
        List<String> qns = kept.get(0);
        List<String> desc = kept.get(1);

        try {
            List<String> urls = new ArrayList<>();
            for (int i = 0; i < qns.size(); i++) {
                String proxy = proxyPlayUrl(aid, cid, qns.get(i));
                if (proxy.isEmpty()) {
                    continue;   // 宿主未注入 proxyBase，无法产出可播地址
                }
                urls.add(desc.get(i));
                urls.add(proxy);
            }
            if (urls.isEmpty()) {
                Log.w(TAG, "无可用线路（proxyBase 未注入或清晰度全被门控挡掉），"
                        + "dash 视频轨=" + dash.video.size());
                return placeholder();
            }
            JSONObject o = new JSONObject();
            o.put("parse", 0);
            o.put("url", new JSONArray(urls));
            o.put("danmaku", danmakuUrl(cid));
            o.put("header", new JSONObject(biliHeaders()));
            return o.toString();
        } catch (Throwable e) {
            Log.e(TAG, "playerContent failed", e);
            return placeholder();
        }
    }

    /**
     * 播放体兜底（上游此处无兜底，直接抛）。
     *
     * @return JSON 串
     */
    public static String placeholder() {
        try {
            JSONObject o = new JSONObject();
            o.put("parse", 0);
            o.put("url", "");
            o.put("msg", "无法生成播放地址（需宿主注入 proxyBase 且账号清晰度权限足够）");
            return o.toString();
        } catch (Throwable e) {
            return "{}";
        }
    }

    /**
     * 发一次心跳（上游写在 {@code Log.d} 的参数里，实际会执行）。
     *
     * @param aid 视频 id
     * @param cid 分 P id
     * @return 响应体；关闭或失败时为空串
     */
    public String sendHeartbeat(String aid, String cid) {
        if (!heartbeatEnabled) {
            return "";
        }
        LinkedHashMap<String, String> form = new LinkedHashMap<>();
        form.put("aid", aid == null ? "" : aid);
        form.put("cid", cid == null ? "" : cid);
        form.put("mid", mid);
        form.put("played_time", "0");
        form.put("realtime", "0");
        form.put("type", "3");
        form.put("refer_url", "https://www.bilibili.com/");
        form.put("play_type", "3");
        try {
            return post(API_HEARTBEAT, formEncode(form));
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 代理 MPD 响应（宿主把 {@code ?do=biliys&…} 交给本方法）。
     *
     * @param params 查询参数（{@code aid}/{@code cid}/{@code qn}）
     * @return {@code Object[]{200, "application/dash+xml", ByteArrayInputStream}}；
     *         参数缺失时返回 {@code null}（上游此处 NPE）
     */
    @Override
    public Object[] proxy(Map<String, String> params) {
        if (params == null) {
            return null;
        }
        String aid = params.get("aid");
        String cid = params.get("cid");
        String qn = params.get("qn");
        if (aid == null || cid == null || qn == null) {
            Log.w(TAG, "proxy 参数缺失: " + params);
            return null;
        }
        try {
            byte[] mpd = mpd(aid, cid, qn).getBytes(StandardCharsets.UTF_8);
            return new Object[]{200, "application/dash+xml", new ByteArrayInputStream(mpd)};
        } catch (Throwable e) {
            Log.e(TAG, "proxy failed", e);
            return null;
        }
    }

    /**
     * 32 位随机 {@code w_rid}（还原 {@code merge.A.a.m1b(32)}）。
     *
     * @return 32 位 62 进制随机串
     */
    public static String randomId() {
        return randomToken(32);
    }

    /** 62 字符表（{@code merge.A.a.m1b} 用的那张）—— 与 {@link BiliBase#ALNUM} 同一份 */
    public static final String ALNUM = BiliBase.ALNUM;

    /**
     * join（空列表 → 空串）。
     *
     * @param list 元素列表，可为 null
     * @param sep  分隔符
     * @return 拼接结果
     */
    public static String join(List<String> list, String sep) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            String s = list.get(i);
            if (s != null) {
                sb.append(s);
            }
        }
        return sb.toString();
    }

    /** 供测试读取分类名表 */
    public static List<String> typeNames() {
        return Arrays.asList(TYPE_NAMES);
    }
}
