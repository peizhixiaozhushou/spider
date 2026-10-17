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
 * KKPans —— 网盘搜索源，继承 {@link PanApiPlain}（→ {@link PanPlain}）。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  搜索: GET &lt;site&gt;/api/resources/public?page=1&amp;limit=24&amp;search=&lt;enc&gt;&amp;sort=featured
 *        等价 KKPan.java:134
 *  响应: {"data":[{"file_type":"video","share_link":…,"file_name":…,
 *                  "target_platform":…,"updated_at":…}]}   ← data 是<b>数组</b>，不是 data.list
 *  过滤: 只保留 file_type == "video" 的条目（KKPan.java:139）
 *  字段: share_link → vod_id / file_name → vod_name
 *        target_platform → 图标 / updated_at → vod_remarks
 *  分类: GET &lt;site&gt;/api/resources/public?page=&lt;p&gt;&amp;limit=24&amp;platform=&lt;type&gt;&amp;sort=hot  (KKPan.java:55)
 * </pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>三个查询参数名改对了（已修）</b>：本工程早期版本写成
 *       {@code ?page=1&size=20&keyword=}，与上游的
 *       {@code ?page=1&limit=24&search=<enc>&sort=featured} 三处不符 ——
 *       {@code size}/{@code limit}、{@code keyword}/{@code search} 对不上，
 *       而且<b>丢了 {@code sort=featured}</b>（排序不同 ⇒ 结果集与顺序都不同）。
 *       现逐字对齐。断言：{@code ★ KKPan 请求形态（URL 参数名与顺序）}。</li>
 *   <li><b>{@code homeContent} 的 5 个分类</b>（{@code KKPan.java:97-115}）未实现：
 *       夸克网盘 / 百度网盘 / 光鸭云盘 / 迅雷云盘 / UC网盘 —— 每个分类走
 *       {@code platform=<type>&sort=hot}（{@code :55}）。本类只承诺搜索。</li>
 *   <li><b>{@code detailContent} 未实现</b>，交给 {@link PanPlain} 的识别 + resolver。</li>
 *   <li><b>网盘图标映射未复刻</b>：上游 {@code getCloudIcon(target_platform)}
 *       把类型映射到图床常量；本类不内置图床 URL。</li>
 *   <li><b>分类 ID 直接当 {@code platform=} 参数值用</b>（{@code :55}）：
 *       ID 与线上参数值强耦合，站方改参数值即静默失效。本类未实现分类，
 *       但把这条记在此处 —— 将来补分类时别照抄。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>域名硬编码</b>（{@code KKPan.java:55,/134}）：换域必须发版。
 *       → 本类站点走 {@link PanApiPlain#site()}（ext 可下发、测试可注入）。
 *       断言：{@code ★ 域名热更}。</li>
 *   <li><b>{@code file_type} 过滤写在循环体内且只认 {@code "video"}</b>
 *       （{@code :139}）：音频/文档类分享被静默丢弃，代码里没有任何注释说明为什么。
 *       → 本类保留该过滤（不改线上语义），但把常量提出来并注明。
 *       断言：{@code ★ 非 video 条目被过滤}。</li>
 *   <li><b>严格 JSON 访问 3 处 + {@code optXxx} 14 处混用</b>（{@code KKPan.java}）：
 *       {@code getJSONObject(i)} 对 null 元素直接抛，整次搜索失败。
 *       → 本类逐条 {@code optJSONObject} + 判空。</li>
 *   <li><b>{@code catch} 里 {@code printStackTrace()} 后返回空串</b>（{@code :157}）：
 *       空串不是标准空响应，宿主会当成"解析失败"再兜一层。
 *       → 本类返回标准空响应。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/KKPan.java}（158 行）——
 * 搜索 URL {@code :134}、条目过滤与字段 {@code :139-146}、分类 URL {@code :55}。
 */
public class KKPanPlain extends PanApiPlain {

    private static final String TAG = "KKPanPlain";

    /** 兜底站点（可被 ext 的 site/url/host 或 {@link #setSiteForTest} 覆盖） */
    private static final String DEFAULT_SITE = "https://www.kkpans.com";

    /** 每页条数（上游固定 24，见 {@code KKPan.java:134}） */
    public static final int PAGE_SIZE = 24;

    /** 上游只收这一种 {@code file_type}（{@code KKPan.java:139}） */
    public static final String FILE_TYPE_VIDEO = "video";

    @Override
    protected String defaultSite() {
        return DEFAULT_SITE;
    }

    @Override
    protected String tagOf() {
        return TAG;
    }

    // ── 协议常量 ─────────────────────────────────────────────

    /**
     * 搜索 URL —— 参数名与顺序逐字对齐 {@code KKPan.java:134}：
     * {@code page=1&limit=24&search=<enc>&sort=featured}。
     */
    public String searchUrl(String encodedKey) {
        return site() + "/api/resources/public"
                + "?page=1"
                + "&limit=" + PAGE_SIZE
                + "&search=" + encodedKey
                + "&sort=featured";
    }

    /** 分类 URL（{@code KKPan.java:55}；本类未实现分类，保留构造器供核对与将来使用） */
    public String categoryUrl(String type, int page) {
        return site() + "/api/resources/public"
                + "?page=" + (page < 1 ? 1 : page)
                + "&limit=" + PAGE_SIZE
                + "&platform=" + encode(type)
                + "&sort=hot";
    }

    public Map<String, String> searchHeaders() {
        return Http.headersOf("User-Agent", UA, "Referer", site() + "/");
    }

    // ── 搜索 ────────────────────────────────────────────────

    @Override
    public String buildSearchUrl(String key) {
        return searchUrl(encode(key));
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return VodBuilder.create().searchContent();
        if (siteMissing()) return VodBuilder.create().searchContent();

        String resp = fetchWith(searchUrl(encode(key)), searchHeaders());
        JSONObject root = parseObject(resp);
        if (root == null) {
            Log.w(TAG, "search: bad response");
            return VodBuilder.create().searchContent();
        }
        // 注意：上游取的是顶层 data 数组，不是 data.list（KKPan.java:134）
        JSONArray arr = root.optJSONArray("data");
        if (arr == null) {
            Log.w(TAG, "search: no data array");
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

    /**
     * 单条解析：只收 {@code file_type=video}（上游 {@code KKPan.java:139}）。
     *
     * <p>字段名优先用上游的 {@code share_link}/{@code file_name}，
     * 再退到本工程通用的 {@code url}/{@code name} 别名 ——
     * 与本仓库其它子源「一字段多候选」的写法一致，
     * 也让 {@code PanChildrenTest} 的通用 fixture 能覆盖本类。
     */
    public Vod parseItem(JSONObject o) {
        if (!FILE_TYPE_VIDEO.equals(o.optString("file_type", ""))) return null;
        String link = firstNonEmpty(o, "share_link", "url", "link");
        String name = firstNonEmpty(o, "file_name", "name", "title");
        if (TextUtils.isEmpty(link) && TextUtils.isEmpty(name)) return null;
        return new Vod()
                .id(TextUtils.isEmpty(link) ? "" : absUrl(link))
                .name(name)
                .pic("")
                .remarks(o.optString("updated_at", ""));
    }

    /** 依次尝试多个字段名，返回第一个非空值（与其它子源同款工具） */
    protected static String firstNonEmpty(JSONObject o, String... keys) {
        if (o == null || keys == null) return "";
        for (String k : keys) {
            if (k == null || k.isEmpty()) continue;
            String v = o.optString(k, "");
            if (!TextUtils.isEmpty(v) && !"null".equals(v)) return v;
        }
        return "";
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
