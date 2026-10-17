package com.example.tvbox.plain.ting;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.common.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 蜻蜓FM（{@code csp_QingtingFM}）明文实现 —— 广播电台直播源。
 *
 * <p>继承 {@link BaseSpider}（上游 {@code extends Spider}）。<b>无加密</b>。
 *
 * <h2>协议（全部经真实请求验证）</h2>
 * <pre>
 * 分类  POST https://webbff.qtfm.cn/www   Content-Type: application/json
 *        body = {"query":"{\n radioPage(cid:&lt;tid&gt;, page:&lt;pg&gt;){\n contents\n }\n }"}
 *        ← GraphQL 查询被当成「JSON 里的一个字符串字段」发送（不是标准 GraphQL POST）
 *        响应 data.radioPage.contents.items[]
 *        字段: id / title / imgUrl / desc   （imgUrl 是 //pic.qtfm.cn/... 协议相对）
 *
 * 详情  GET https://webapi.qtfm.cn/api/pc/radio/&lt;id&gt;
 *        → album{ id, title, cover, description }
 *
 * 播放  GET https://lhttp-hw.qtfm.cn/live/&lt;id&gt;/64k.mp3     ← 固定 64k 码率
 * </pre>
 *
 * <h2>分类表</h2>
 * 上游硬编码 44 个省级/类型电台 id（217 广东 / 99 浙江 / 3 北京 …），
 * 顺序与名字严格一一对应，本实现逐字保留。
 *
 * <h2>与上游的差异</h2>
 * <ol>
 *   <li>上游 {@code searchContent} 有 3 个重载（{@code (String,boolean)}、
 *       {@code (String,boolean,String)}、{@code (String,String)}），本实现按
 *       {@link BaseSpider} 契约实现 2 参数版，3 参数版委托过来</li>
 *   <li>{@code imgUrl} 缺 {@code https:} 前缀时上游用 {@code "https:".concat()}；
 *       本实现同，但对空串与已是绝对地址的情况都做了判断</li>
 *   <li>分页：上游 {@code pagecount = size<12 ? page : page+1}；本实现同
 *       （服务端不返回总数）</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/QingtingFM.java}（104 行）
 * 真实夹具:   {@code ae/fixtures/real/qingtingfm_radiopage.json}（分类）
 *             {@code ae/fixtures/real/qingtingfm_radio.json}（详情）
 *             {@code ae/fixtures/real/qingtingfm_search.json}（搜索）
 * 抓取时间:   2026-10-17
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/QingtingFM.java} 的对照复核，
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
 * {@code ae/decompiled/QingtingFM.java} 为对照基准。
 */
public class QingtingFMPlain extends BaseSpider {

    private static final String TAG = "QingtingFMPlain";

    /** 站点主域（上游 final String a，用作 Referer） */
    private static final String SITE = "https://www.qtfm.cn";

    /** GraphQL 网关（分类 / 搜索） */
    private static final String BFF = "https://webbff.qtfm.cn/www";

    /** REST 详情前缀 */
    private static final String RADIO_API = "https://webapi.qtfm.cn/api/pc/radio/";

    /** 播放地址前缀（固定 64k 码率，上游写死） */
    private static final String PLAY_PREFIX = "https://lhttp-hw.qtfm.cn/live/";

    /** 上游硬编码 UA（逐字） */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 每页条数（上游写死 12） */
    private static final int PAGE_SIZE = 12;

    /** 分类 id（上游硬编码，顺序敏感） */
    private static final String[] TIDS = {
            "217", "99", "3", "5", "7", "83", "19", "31", "44", "59", "69", "85",
            "111", "129", "139", "151", "169", "187", "202", "239", "254", "257",
            "259", "281", "291", "316", "327", "351", "357", "308", "342", "433",
            "442", "429", "439", "432", "441", "430", "431", "440", "438", "435",
            "436", "434",
    };

    /** 分类名（与 TIDS 一一对应） */
    private static final String[] TNAMES = {
            "广东", "浙江", "北京", "天津", "河北", "上海", "山西", "内蒙古", "辽宁",
            "吉林", "黑龙江", "江苏", "安徽", "福建", "江西", "山东", "河南", "湖北",
            "湖南", "广西", "海南", "重庆", "四川", "贵州", "云南", "陕西", "甘肃",
            "宁夏", "新疆", "西藏", "青海", "资讯", "音乐", "交通", "经济", "文艺",
            "都市", "体育", "双语", "综合", "生活", "旅游", "曲艺", "方言",
    };

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        // 上游完全忽略 ext（站点写死在类里）
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = SITE;
        }
    }

    /** 还原上游 {@code b()}：UA + Referer */
    public Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", SITE);
        return h;
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页：44 个电台分类，无推荐（还原上游 {@code homeContent}）。
     *
     * @param filter 是否要筛选（上游无）
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        VodBuilder vb = VodBuilder.create();
        for (int i = 0; i < TIDS.length; i++) {
            vb.clazz(TIDS[i], TNAMES[i]);
        }
        return vb.homeContent();
    }

    /** 供测试读取分类数 */
    public static int categoryCount() {
        return TIDS.length;
    }

    /** 供测试读取第 i 个分类 id */
    public static String tidAt(int i) {
        return i >= 0 && i < TIDS.length ? TIDS[i] : "";
    }

    /** 供测试读取第 i 个分类名 */
    public static String tnameAt(int i) {
        return i >= 0 && i < TNAMES.length ? TNAMES[i] : "";
    }

    // ─────────────────────────────────────────────────────────────
    // GraphQL
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code b.f(BFF, json, headers).a()}：POST JSON 到 BFF。
     *
     * @param query  GraphQL 查询文本
     * @return 响应体；失败空串
     */
    public String postGraphql(String query) {
        JSONObject body = new JSONObject();
        try {
            body.put("query", query);
        } catch (Exception e) {
            Log.e(TAG, "postGraphql build body failed", e);
            return "";
        }
        Map<String, String> h = apiHeaders();
        h.put("Content-Type", "application/json");
        return http.post(BFF, body.toString(), h);
    }

    /**
     * 还原上游分类查询（注意 GraphQL 文本里带换行与缩进，逐字保留）。
     *
     * @param tid 分类 id
     * @param pg  页码
     * @return 查询文本
     */
    public static String categoryQuery(String tid, int pg) {
        return "{\n    radioPage(cid:" + tid + ", page:" + pg + "){\n      contents\n    }\n  }";
    }

    /**
     * 还原上游搜索查询。
     *
     * @param key 关键词
     * @param pg  页码
     * @return 查询文本
     */
    public static String searchQuery(String key, int pg) {
        return "{\n        searchResultsPage(keyword:\"" + key + "\", page:" + pg
                + ", include:\"channel_live\" ) {\n          tdk,\n          searchData,\n"
                + "          numFound\n        }\n      }";
    }

    // ─────────────────────────────────────────────────────────────
    // 分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 分类翻页（还原上游 {@code categoryContent}）。
     *
     * @param tid    分类 id
     * @param pg     页码
     * @param filter 是否要筛选（忽略）
     * @param extend 筛选参数，可含 cateId 覆盖 tid（上游同）
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        String cate = tid;
        if (extend != null && extend.get("cateId") != null) {
            cate = extend.get("cateId");
        }
        int page = pageNum(pg, 1);
        String resp = postGraphql(categoryQuery(cate, page));
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "categoryContent empty, tid=" + cate + " pg=" + page);
            return VodBuilder.create().page(page, 0, PAGE_SIZE).categoryContent();
        }

        List<Vod> list;
        try {
            JSONArray items = new JSONObject(resp)
                    .getJSONObject("data").getJSONObject("radioPage")
                    .getJSONObject("contents").getJSONArray("items");
            list = parseItems(items);
        } catch (Exception e) {
            Log.e(TAG, "categoryContent parse failed, tid=" + cate, e);
            return VodBuilder.create().page(page, 0, PAGE_SIZE).categoryContent();
        }
        // 上游：pagecount = size<12 ? page : page+1（服务端不给总数）
        int pageCount = list.size() < PAGE_SIZE ? page : page + 1;
        return VodBuilder.create().addAll(list).page(page, pageCount * PAGE_SIZE, PAGE_SIZE)
                .categoryContent();
    }

    /**
     * 解析 {@code items[]}（还原上游 {@code k.c(id, title, pic, desc, ...)}）。
     *
     * @param items items 数组
     * @return 影片列表，永不 null
     */
    public List<Vod> parseItems(JSONArray items) {
        List<Vod> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) {
                continue;
            }
            out.add(new Vod()
                    .id(o.optString("id", String.valueOf(o.opt("id"))))
                    .name(o.optString("title"))
                    .pic(fixPic(o.optString("imgUrl")))
                    .remarks(o.optString("desc")));
        }
        return out;
    }

    /**
     * 封面地址修正。
     *
     * <p><b>上游逻辑有 bug</b>：{@code if(!contains("https")) "https:".concat(url)} ——
     * 真实搜索响应里 cover 已经是 {@code http://pic.qingting.fm/...}，不含 "https"
     * 子串，于是被拼成 {@code https:http://pic.qingting.fm/...}（坏链）。
     *
     * <p>本实现按真实数据修正为三分支：
     * <ol>
     *   <li>{@code http://} / {@code https://} 开头 → 原样返回</li>
     *   <li>{@code //} 开头（协议相对，分类页 imgUrl 就是这种）→ 补 {@code https:}</li>
     *   <li>其它（裸域名/裸路径）→ 补 {@code https://}，并去掉重复斜杠</li>
     * </ol>
     *
     * @param url 原始值
     * @return 可用地址；输入为空返回空串
     */
    public static String fixPic(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        String u = url.trim();
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return u;
        }
        if (u.startsWith("//")) {
            return "https:" + u;
        }
        // 裸域名：去掉可能的前导斜杠再加协议
        while (u.startsWith("/")) {
            u = u.substring(1);
        }
        return "https://" + u;
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 详情（还原上游 {@code detailContent}）。
     *
     * <p>上游把整个电台当「一集」：集名 = title，地址 = id，
     * 播放时再拼成 64k 直播流地址。
     *
     * @param ids id 列表，首个为电台 id
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (id.isEmpty()) {
            Log.w(TAG, "detailContent 无 id");
            return emptyResponse();
        }

        // 未 init（无站点配置）时不发请求：宿主一定会先 init，此分支只出现在异常/测试场景
        if (requireUrl().isEmpty()) {
            Log.e(TAG, "detailContent 未初始化");
            return emptyResponse();
        }

        String resp = http.get(RADIO_API + id, apiHeaders());
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "detailContent empty, id=" + id);
            return emptyResponse();
        }

        try {
            JSONObject album = new JSONObject(resp).optJSONObject("album");
            if (album == null) {
                Log.w(TAG, "detailContent album 缺失, id=" + id);
                return emptyResponse();
            }
            String title = album.optString("title");
            String albumId = album.optString("id", id);

            Vod vod = new Vod()
                    .id(id)
                    .name(title)
                    .pic(fixPic(album.optString("cover")))
                    .content(album.optString("description"));

            // 上游：title + "$" + id（整台当一集）
            String ep = title + Vod.SEP_NAME_URL + albumId;
            vod.playFrom("蜻蜓FM").playUrl(ep);
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
     * 播放（还原上游 {@code playerContent}）：拼固定 64k 直播流。
     *
     * @param flag     线路名（蜻蜓FM）
     * @param id       电台 id
     * @param vipFlags 需要解析的标识（忽略）
     * @return 标准播放 JSON，带 UA/Referer
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) {
            return emptyResponse();
        }
        String url = PLAY_PREFIX + id + "/64k.mp3";
        return playerWithHeaders(url, apiHeaders());
    }

    /**
     * 拼播放响应并附 header（还原上游 {@code c.h(url).m10e(headers)}）。
     *
     * @param url     播放地址
     * @param headers 播放器要带的头
     * @return 标准播放 JSON
     */
    public static String playerWithHeaders(String url, Map<String, String> headers) {
        String json = VodBuilder.playerContent(url, "audio/mpeg");
        if (headers == null || headers.isEmpty()) {
            return json;
        }
        try {
            JSONObject o = new JSONObject(json);
            o.put("header", toJsonObject(headers));
            return o.toString();
        } catch (Throwable e) {
            return json;
        }
    }

    /**
     * Map → JSON 对象文本（header 字段要求是 JSON 对象而非字符串）。
     *
     * @param map 头表
     * @return JSON 文本
     */
    public static String toJsonObject(Map<String, String> map) {
        JSONObject o = new JSONObject();
        if (map != null) {
            for (Map.Entry<String, String> e : map.entrySet()) {
                try {
                    o.put(e.getKey(), e.getValue());
                } catch (Exception ignored) {
                    // 单个头失败不影响其它头
                }
            }
        }
        return o.toString();
    }

    // ─────────────────────────────────────────────────────────────
    // 搜索
    // ─────────────────────────────────────────────────────────────

    /**
     * 搜索（还原上游 {@code searchContent(String, String)}）。
     *
     * @param key   关键词
     * @param quick 是否快速搜索（忽略；上游另有一个带 page 的重载）
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, "1");
    }

    /**
     * 三参数重载（宿主旧接口），委托到两参数版。
     *
     * @param key        关键词
     * @param quick      是否快速
     * @param quickSearch 快速搜索标识（忽略）
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick, String quickSearch) {
        return searchContent(key, true);
    }

    /**
     * 带页码的搜索（还原上游 {@code searchContent(String str, String str2)}）。
     *
     * @param key  关键词
     * @param pg   页码字符串
     * @return 标准搜索 JSON
     */
    public String searchContent(String key, String pg) {
        if (TextUtils.isEmpty(key)) {
            return VodBuilder.create().searchContent();
        }
        int page = pageNum(pg, 1);
        String resp = postGraphql(searchQuery(key, page));
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "searchContent empty, key=" + key);
            return VodBuilder.create().searchContent();
        }
        List<Vod> list;
        try {
            JSONArray arr = new JSONObject(resp)
                    .getJSONObject("data").getJSONObject("searchResultsPage")
                    .getJSONArray("searchData");
            list = parseSearchItems(arr);
        } catch (Exception e) {
            Log.e(TAG, "searchContent parse failed, key=" + key, e);
            return VodBuilder.create().searchContent();
        }
        return VodBuilder.of(list).searchContent();
    }

    /**
     * 解析 {@code searchData[]}（字段名与分类页不同：cover / description）。
     *
     * @param arr searchData 数组
     * @return 影片列表，永不 null
     */
    public List<Vod> parseSearchItems(JSONArray arr) {
        List<Vod> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            out.add(new Vod()
                    .id(o.optString("id", String.valueOf(o.opt("id"))))
                    .name(o.optString("title"))
                    .pic(fixPic(o.optString("cover")))
                    .remarks(o.optString("description")));
        }
        return out;
    }
}
