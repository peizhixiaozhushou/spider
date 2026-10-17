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
 * 博看听书（{@code csp_TingBokan}）明文实现。
 *
 * <p>继承 {@link BaseSpider}（上游 {@code extends Spider}）。<b>无加密</b>。
 *
 * <h2>协议（全部经真实请求验证）</h2>
 * <pre>
 * 分类  GET https://api.bookan.com.cn/voice/book/list
 *          ?instance_id=25304&amp;page=&lt;pg&gt;&amp;category_id=&lt;tid&gt;&amp;num=24
 *        响应 {"code":0,"data":{"total":517,"current_page":1,"last_page":N,
 *                             "list":[{id,name,cover,extra:{author}}]}}
 *        注意: 成功码是 **code==0**（不是 200）
 *
 * 详情  GET https://api.bookan.com.cn/voice/album/units
 *          ?album_id=&lt;id&gt;&amp;page=&lt;i&gt;&amp;num=200&amp;order=1
 *        响应 {"code":0,"data":{"last_page":N,"list":[{title,file,updated_at}]}}
 *        需按 last_page 循环拉全部集（每页 200）
 *
 * 搜索  GET https://es.bookan.com.cn/api/v3/voice/book
 *          ?instanceId=25304&amp;keyword=&lt;urlencode&gt;&amp;pageNum=1&amp;limitNum=20
 *        响应 {"code":0,"data":{"list":[{id,name,cover,extra:{author},press}]}}
 *
 * 播放  直链（units[].file 就是 m4a 地址）
 * </pre>
 *
 * <h2>关键坑</h2>
 * <ol>
 *   <li><b>成功码是 {@code code==0}</b>，不是 200。上游用 {@code optInt("code",-1)!=0} 判错</li>
 *   <li><b>vod_id 是「id@name」</b>（用 {@code @} 拼），详情层再 split 回来。
 *       上游 {@code String.valueOf(id) + "@" + name}，id 是 int 要转字符串</li>
 *   <li><b>详情要循环翻页</b>：album 可能有 3977 集，单页 num=200 拉不完</li>
 *   <li>分类总数 {@code total=517} 真实存在，可用它算 pagecount（上游用 last_page）</li>
 * </ol>
 *
 * <h2>与上游的差异</h2>
 * <ul>
 *   <li>上游首页只列 11 个分类（{@code for i<11}），而数组有 18 个 ——
 *       后 7 个（历史小说/科技科普/经典必读/商业财经/红色岁月/音乐戏剧/社会观察）
 *       永远不可达。本实现把 18 个全部放出（修上游 off-by-N bug）</li>
 *   <li>详情翻页加上限保护，避免 last_page 异常导致死循环</li>
 * </ul>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/TingBokan.java}（132 行）
 * 真实夹具:   {@code ae/fixtures/real/tingbokan_list.json}
 *             {@code ae/fixtures/real/tingbokan_units.json}
 *             {@code ae/fixtures/real/tingbokan_search.json}
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/TingBokan.java} 的对照复核，
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
 * {@code ae/decompiled/TingBokan.java} 为对照基准。
 */
public class TingBokanPlain extends BaseSpider {

    private static final String TAG = "TingBokanPlain";

    /** 分类 API */
    private static final String API = "https://api.bookan.com.cn";
    /** 搜索 API（独立域名） */
    private static final String SEARCH_API = "https://es.bookan.com.cn/api/v3/voice/book";

    /** 机构 id（上游写死） */
    private static final String INSTANCE_ID = "25304";

    /** 上游 UA（逐字） */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 分类页每页条数 */
    private static final int PAGE_SIZE = 24;
    /** 详情每页集数 */
    private static final int UNIT_PAGE_SIZE = 200;
    /** 详情翻页上限（防御 last_page 异常） */
    private static final int MAX_UNIT_PAGES = 100;

    /** 分类名（上游 18 项） */
    private static final String[] TNAMES = {
            "故事会", "儿童文学", "少年读物", "文艺少年", "青春励志", "个人提升",
            "育儿心经", "心理哲学", "家庭健康", "历史小说", "科技科普", "经典必读",
            "商业财经", "红色岁月", "音乐戏剧", "社会观察", "相声评书", "国学经典",
    };
    /** 分类 id（与 TNAMES 一一对应） */
    private static final String[] TIDS = {
            "1303", "1304", "1305", "1306", "1307", "1308", "1309",
            "1310", "1311", "1312", "1313", "1314", "1315", "1316",
            "1317", "1318", "1319", "1320",
    };

    /** id 与 name 的拼接分隔符（上游用 @） */
    private static final String ID_SEP = "@";

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = API;
        }
    }

    /** 还原上游 {@code a()}：仅 UA */
    public Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        return h;
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页：18 个分类（还原上游 {@code homeContent}，但修了只放 11 个的 bug）。
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

    /** 供测试：分类总数 */
    public static int categoryCount() {
        return TIDS.length;
    }

    /** 供测试：第 i 个分类名 */
    public static String tnameAt(int i) {
        return i >= 0 && i < TNAMES.length ? TNAMES[i] : "";
    }

    /** 供测试：第 i 个分类 id */
    public static String tidAt(int i) {
        return i >= 0 && i < TIDS.length ? TIDS[i] : "";
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
     * @param extend 筛选参数（忽略）
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        if (requireUrl().isEmpty()) {
            return VodBuilder.create().page(page, 0, PAGE_SIZE).categoryContent();
        }

        String url = baseUrl + "/voice/book/list?instance_id=" + INSTANCE_ID
                + "&page=" + page + "&category_id=" + Http.encode(tid) + "&num=" + PAGE_SIZE;
        String resp = http.get(url, apiHeaders());
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "categoryContent empty, tid=" + tid + " pg=" + page);
            return VodBuilder.create().page(page, 0, PAGE_SIZE).categoryContent();
        }

        List<Vod> list;
        int lastPage;
        try {
            JSONObject root = new JSONObject(resp);
            if (!ok(root)) {
                Log.w(TAG, "categoryContent code!=0: " + root.optInt("code", -1));
                return VodBuilder.create().page(page, 0, PAGE_SIZE).categoryContent();
            }
            JSONObject data = root.getJSONObject("data");
            list = parseBookList(data.optJSONArray("list"));
            lastPage = data.optInt("last_page", page);
        } catch (Exception e) {
            Log.e(TAG, "categoryContent parse failed, tid=" + tid, e);
            return VodBuilder.create().page(page, 0, PAGE_SIZE).categoryContent();
        }
        return VodBuilder.create().addAll(list).page(page, lastPage * PAGE_SIZE, PAGE_SIZE)
                .categoryContent();
    }

    /**
     * 成功码判断：博看用 {@code code==0}（不是 200）。
     *
     * @param root 响应根对象
     * @return true 表示成功
     */
    public static boolean ok(JSONObject root) {
        return root != null && root.optInt("code", -1) == 0;
    }

    /**
     * 解析书籍数组（还原上游 {@code new e(id+"@"+name, name, cover, extra.author)}）。
     *
     * @param arr list 数组
     * @return 影片列表，永不 null
     */
    public List<Vod> parseBookList(JSONArray arr) {
        List<Vod> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String id = String.valueOf(o.opt("id"));
            String name = o.optString("name");
            JSONObject extra = o.optJSONObject("extra");
            String author = extra == null ? "" : extra.optString("author");
            out.add(new Vod()
                    .id(id + ID_SEP + name)
                    .name(name)
                    .pic(o.optString("cover"))
                    .remarks(author));
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 详情（还原上游 {@code detailContent}）。
     *
     * <p>按 {@code last_page} 循环拉全部分集（上游 do-while 同）；
     * remarks 用首集的 {@code updated_at}（上游写「更新于 xxx」）。
     *
     * @param ids id 列表，首个形如 {@code 4033132@葵花向太阳}
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String raw = firstId(ids);
        if (raw.isEmpty()) {
            Log.w(TAG, "detailContent 无 id");
            return emptyResponse();
        }
        if (requireUrl().isEmpty()) {
            return emptyResponse();
        }

        String[] parts = raw.split(ID_SEP);
        String albumId = parts.length > 0 ? parts[0] : "";
        String name = parts.length > 1 ? parts[1] : "";
        if (albumId.isEmpty()) {
            Log.w(TAG, "detailContent id 结构非法: " + raw);
            return emptyResponse();
        }

        List<String> eps = new ArrayList<>();
        String updated = "";
        int page = 1;
        int lastPage = 1;
        int guard = 0;
        do {
            String url = baseUrl + "/voice/album/units?album_id=" + Http.encode(albumId)
                    + "&page=" + page + "&num=" + UNIT_PAGE_SIZE + "&order=1";
            String resp = http.get(url, apiHeaders());
            if (TextUtils.isEmpty(resp)) {
                Log.w(TAG, "detailContent 第 " + page + " 页空响应");
                break;
            }
            try {
                JSONObject root = new JSONObject(resp);
                if (!ok(root)) {
                    Log.w(TAG, "detailContent 第 " + page + " 页 code!=0");
                    break;
                }
                JSONObject data = root.getJSONObject("data");
                lastPage = data.optInt("last_page", 1);
                JSONArray arr = data.optJSONArray("list");
                if (arr != null) {
                    if (page == 1 && arr.length() > 0) {
                        String u = arr.getJSONObject(0).optString("updated_at");
                        if (!u.isEmpty()) {
                            updated = "更新于 " + u;
                        }
                    }
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject u = arr.optJSONObject(i);
                        if (u == null) {
                            continue;
                        }
                        String title = u.optString("title");
                        String file = u.optString("file");
                        if (file.isEmpty()) {
                            continue;
                        }
                        eps.add((title.isEmpty() ? "第" + (eps.size() + 1) + "集" : title)
                                + Vod.SEP_NAME_URL + file);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "detailContent 第 " + page + " 页解析失败", e);
                break;
            }
            page++;
        } while (page <= lastPage && guard++ < MAX_UNIT_PAGES);

        if (eps.isEmpty()) {
            Log.w(TAG, "detailContent 未取到任何分集, album=" + albumId);
            return emptyResponse();
        }
        Vod vod = new Vod()
                .id(albumId)
                .name(name)
                .remarks(updated)
                .playFrom("博看听书")
                .playUrl(Html.join(Vod.SEP_EPISODE, eps));
        return VodBuilder.detail(vod).detailContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 播放（还原上游 {@code playerContent}）：直链 + UA 头。
     *
     * @param flag     线路名（博看听书）
     * @param id       m4a 地址
     * @param vipFlags 需要解析的标识（忽略）
     * @return 标准播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) {
            return emptyResponse();
        }
        return QingtingFMPlain.playerWithHeaders(id, apiHeaders());
    }

    // ─────────────────────────────────────────────────────────────
    // 搜索
    // ─────────────────────────────────────────────────────────────

    /**
     * 搜索（还原上游 {@code searchContent}）。
     *
     * <p>上游对空关键词返回空列表（不发请求），本实现同。
     *
     * @param key   关键词
     * @param quick 是否快速搜索（忽略）
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) {
            return VodBuilder.create().searchContent();
        }
        String url = SEARCH_API + "?instanceId=" + INSTANCE_ID
                + "&keyword=" + Http.encode(key.trim())
                + "&pageNum=1&limitNum=20";
        String resp = http.get(url, apiHeaders());
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "searchContent empty, key=" + key);
            return VodBuilder.create().searchContent();
        }
        try {
            JSONObject root = new JSONObject(resp);
            if (!ok(root)) {
                Log.w(TAG, "searchContent code!=0");
                return VodBuilder.create().searchContent();
            }
            return VodBuilder.of(parseSearchList(
                    root.getJSONObject("data").optJSONArray("list"))).searchContent();
        } catch (Exception e) {
            Log.e(TAG, "searchContent parse failed, key=" + key, e);
            return VodBuilder.create().searchContent();
        }
    }

    /**
     * 解析搜索结果（还原上游：author 缺失时退化到 {@code press}）。
     *
     * @param arr list 数组
     * @return 影片列表，永不 null
     */
    public List<Vod> parseSearchList(JSONArray arr) {
        List<Vod> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String id = String.valueOf(o.opt("id"));
            String name = o.optString("name");
            JSONObject extra = o.optJSONObject("extra");
            String author = extra == null ? "" : extra.optString("author");
            if (author.isEmpty()) {
                author = o.optString("press");     // 上游同一兜底
            }
            out.add(new Vod()
                    .id(id + ID_SEP + name)
                    .name(name)
                    .pic(o.optString("cover"))
                    .remarks(author));
        }
        return out;
    }
}
