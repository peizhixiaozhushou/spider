package com.example.tvbox.plain.pan.sub;

import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.pan.PanPlain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 趣盘搜 —— 网盘搜索源，继承 {@link PanApiPlain}（→ {@link PanPlain}）。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  搜索: POST &lt;site&gt;/search                        QuPanSo.java:21
 *        header: content-type: application/json
 *                Origin: &lt;site&gt;   Referer: &lt;site&gt;/search
 *        body  : {"keyword":"&lt;明文&gt;","categoryid":0,"filetypeid":1,"courseid":1,
 *                 "page":&lt;页码&gt;,"pageSize":15,"sortBy":"sort","order":"desc","offset":0}
 *        → {"code":200,"data":{"list":[{"url":…,"title":…,"updatetime":…}]}}
 *  清洗: title 去掉 &lt;em&gt;…&lt;/em&gt; 搜索高亮（QuPanSo.java:31）
 *  图标: 按 url 里的 5 种域名特征匹配图床常量（:31-52）
 * </pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>搜索是 POST 而不是 GET（已修）</b>：本工程早期版本写成
 *       {@code GET /search?keyword=}，与上游的 {@code POST /search} + 9 字段 JSON body
 *       完全不同 —— <b>真机上拿不到结果</b>。现按上游改为 POST。
 *       断言：{@code ★ QuPanSo 请求形态（方法/URL/body 9 字段/头）}。</li>
 *   <li><b>分页入口缺失（已修）</b>：上游 {@code E(str, page)} 的第二参就是 {@code page}
 *       （{@code QuPanSo.java:21}），三参重载把宿主给的页码传进来（{@code :78}）。
 *       本类把页码做成可注入（{@link #setPageForTest}），默认第 1 页。</li>
 *   <li><b>网盘图标映射未复刻</b>：上游按 {@code pan.quark.cn}/{@code drive.uc.cn}/
 *       {@code pan.baidu.com}/{@code aliyundrive.com}/{@code alipan.com}
 *       5 种特征匹配图床常量（{@code :31-52}）。本类不内置图床 URL。
 *       其中 UC/阿里已不在本项目范围内（见
 *       {@code maintain/evidence/SCOPE_QUARK_BAIDU.md}）。</li>
 *   <li><b>{@code categoryid}/{@code filetypeid}/{@code courseid}/{@code sortBy}/{@code order}
 *       五个筛选字段固定为上游的常量值</b>（0/1/1/sort/desc）：上游没有把它们暴露成
 *       可配置参数，本类同样固定 —— 若站点改了默认筛选语义，需要改
 *       {@link #buildSearchBody}。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>手写字符串拼 JSON，且不做任何转义</b>（{@code QuPanSo.java:28-62}）：
 *       逐段 {@code append} 出 {@code {"list":[...]}}，片名里出现 {@code "} 或 {@code \\}
 *       就产出非法 JSON，宿主解析失败 —— 而失败是静默的（宿主只看到空列表）。
 *       → 本类用 {@link Vod}/{@code VodBuilder} 生成，并由基类 {@code jsonEscape}
 *       负责请求 body 的转义。
 *       断言：{@code ★ 含引号/反斜杠的关键字不破坏 body}。</li>
 *   <li><b>请求 body 里的关键字也不转义</b>（{@code :21} 的
 *       {@code "{\"keyword\":\"" + str + "\",…}"}）：关键字带双引号即让整个 body 非法。
 *       → {@link #buildSearchBody(String)} 统一走 {@code jsonEscape}。
 *       断言同上。</li>
 *   <li><b>{@code code != 200} 与"空结果"不可区分</b>（{@code :22}）：
 *       上游对失败也返回 {@code {"list":[]}}，日志里没有任何痕迹。
 *       → 本类失败时记 warn 日志（返回的仍是标准空响应，行为对宿主一致）。</li>
 *   <li><b>"两条空响应形态"并存</b>：{@code code != 200} 返回 {@code {"list":[]}}，
 *       而成功但无结果时是走 {@code parseItemsToJson} 生成的 {@code {"list":[]}} ——
 *       巧合一致，但代码里有两条独立路径。→ 本类只有一条。</li>
 *   <li><b>两参版把页码硬编码成 {@code "1"}</b>（{@code :76} 的 {@code E(str,"1")}），
 *       三参重载才传真页码（{@code :78}）：宿主要么永远第 1 页、要么走另一条重载。
 *       → 本类页码是实例状态，两参入口也能翻页。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/QuPanSo.java}（85 行）——
 * POST 与 body 字段 {@code :21}、{@code code} 判定 {@code :22}、
 * 标题清洗 {@code :31}、{@code updatetime} {@code :55}。
 */
public class QuPanSoPlain extends PanApiPlain {

    private static final String TAG = "QuPanSoPlain";

    /** 兜底站点（可被 ext 的 site/url/host 或 {@link #setSiteForTest} 覆盖） */
    private static final String DEFAULT_SITE = "https://b.funletu.com";

    /** 页码（默认 1，可注入） */
    private int page = 1;

    /** 每页条数（上游固定 15，见 {@code QuPanSo.java:21}） */
    public static final int PAGE_SIZE = 15;

    @Override
    protected String defaultSite() {
        return DEFAULT_SITE;
    }

    @Override
    protected String tagOf() {
        return TAG;
    }

    // ── 协议常量 ─────────────────────────────────────────────

    /** 搜索端点（{@code QuPanSo.java:21}） */
    public String searchUrl() {
        return site() + "/search";
    }

    /**
     * 搜索 body —— 上游 9 个字段，逐字保留顺序。
     * <p>关键字经 {@code jsonEscape}，其余是上游写死的常量。
     */
    public String buildSearchBody(String key) {
        return "{\"keyword\":\"" + jsonEscape(key) + "\","
             + "\"categoryid\":0,"
             + "\"filetypeid\":1,"
             + "\"courseid\":1,"
             + "\"page\":" + page + ","
             + "\"pageSize\":" + PAGE_SIZE + ","
             + "\"sortBy\":\"sort\","
             + "\"order\":\"desc\","
             + "\"offset\":0}";
    }

    /** 请求头：上游用 {@code content-type}（<b>小写</b>）+ Origin + Referer */
    public Map<String, String> searchHeaders() {
        return Http.headersOf(
                "content-type", "application/json",
                "Origin", site(),
                "Referer", site() + "/search",
                "User-Agent", UA);
    }

    public int page() {
        return page;
    }

    public void setPageForTest(int p) {
        page = p < 1 ? 1 : p;
    }

    // ── 搜索 ────────────────────────────────────────────────

    @Override
    public String buildSearchUrl(String key) {
        // POST 型：URL 里没有关键字，关键字在 body 里（见 buildSearchBody）
        return searchUrl();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return VodBuilder.create().searchContent();
        if (siteMissing()) return VodBuilder.create().searchContent();

        String resp = postWith(searchUrl(), buildSearchBody(key), searchHeaders());
        JSONObject root = parseObject(resp);
        if (root == null) {
            Log.w(TAG, "search: bad response");
            return VodBuilder.create().searchContent();
        }
        if (root.optInt("code", 0) != 200) {
            Log.w(TAG, "search: code=" + root.optInt("code", 0));
            return VodBuilder.create().searchContent();
        }
        JSONObject data = root.optJSONObject("data");
        JSONArray arr = data == null ? null : data.optJSONArray("list");
        if (arr == null) {
            Log.w(TAG, "search: no data.list");
            return VodBuilder.create().searchContent();
        }
        List<Vod> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Vod v = parseItem(o);
            if (v != null) list.add(v);
        }
        Log.i(TAG, "search '" + key + "' -> " + list.size() + " items");
        return VodBuilder.of(list).searchContent();
    }

    /** 单条解析：{@code url} 是分享链接，{@code title} 去高亮，{@code updatetime} 选填 */
    public Vod parseItem(JSONObject o) {
        String url = o.optString("url", "");
        if (TextUtils.isEmpty(url)) return null;
        String title = cleanTitle(o.optString("title", ""));
        String note = o.optString("updatetime", "");
        return new Vod()
                .id(absUrl(url))
                .name(TextUtils.isEmpty(title) ? url : title)
                .pic("")
                .remarks(note);
    }

    /** 去掉搜索高亮标签（等价上游 {@code replace("<em>","").replace("</em>","")}） */
    protected static String cleanTitle(String title) {
        return safe(title).replace("<em>", "").replace("</em>", "").trim();
    }

    /** 安全解析（失败 null，不抛） */
    protected static JSONObject parseObject(String json) {
        if (TextUtils.isEmpty(json)) return null;
        String t = json.trim();
        if (!t.startsWith("{")) return null;
        try {
            return new JSONObject(t);
        } catch (Exception e) {
            return null;
        }
    }
}
