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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 米盘 —— 网盘搜索源，继承 {@link PanApiPlain}（→ {@link PanPlain}）。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  ① 取票: GET  &lt;site&gt;/api/token
 *           → {"token":"..."}                        MiPan.java:198
 *  ② 搜索: POST &lt;site&gt;/api/search                  MiPan.java:39（同 :118）
 *           header: User-Agent / Content-Type: application/json / x-mipan-token: &lt;票&gt;
 *           body  : {"kw":"&lt;关键字&gt;"}   ← 原始明文，<b>不做 urlencode</b>
 *           → {"code":0,"data":{"merged_by_type":{ "&lt;网盘类型&gt;":[ {url,note,datetime}, … ] }}}
 *  ③ 展平: 遍历 merged_by_type 的每个类型，跳过 115 / other / others / pikpak
 *           每条 = { vod_id: url, vod_name: note, vod_remarks: datetime }
 * </pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>搜索是 POST 而不是 GET（已修）</b>：本工程早期版本写成
 *       {@code GET /api/search?keyword=}，与上游的
 *       {@code POST /api/search} + JSON body 完全不同 —— <b>真机上拿不到结果</b>。
 *       现按上游改为 POST。断言：{@code ★ MiPan 请求形态（方法/URL/body/头）}。</li>
 *   <li><b>{@code /api/token} 换票链未实现（已修）</b>：上游先取票再带
 *       {@code x-mipan-token} 头搜索（{@code MiPan.java:198}）；早期版本完全跳过这一步。
 *       现在按需取票并缓存（{@link #token()}）。断言：{@code ★ MiPan 先取票再搜索}。</li>
 *   <li><b>{@code categoryContent} 与"按类型分组"的详情展开未实现</b>：
 *       上游 {@code E()} 会为每个网盘类型生成<b>一条</b>列表项（{@code vod_id=类型名}），
 *       把这些类型的原始数组塞进 {@code cacheMap}，等 {@code detailContent} 再展开成链接
 *       （{@code MiPan.java:60-101}）。这是"两层列表"结构。
 *       → 本类直接<b>展平</b>成"一条一个链接"（{@link #SKIP_TYPES} 之外全要），
 *       对用户更直接，但<b>不是逐字还原</b>，故列在此。</li>
 *   <li><b>网盘图标映射未复刻</b>：上游 {@code getCloudIcon(type)} 把 9 种类型映射到图床常量
 *       （{@code MiPan.java:170-194}）；本类不内置任何图床 URL（图床失效会让全站无封面）。</li>
 *   <li><b>不读宿主 {@code /config.json}</b>：上游从该文件读 {@code panView} 决定图标显示
 *       （{@code MiPan.java:277,290}）。本类不读任何宿主私有文件。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>票永远不刷新</b>（{@code MiPan.java:195-204}）：{@code getToken()} 把票写进
 *       {@code static String token}，之后<b>再也没有重新取过</b>；票过期就只能重启。
 *       而且它是 {@code static} ⇒ 多实例共享同一张票，谁先失败谁把别人的票也带坏。
 *       → 本类的票是<b>实例字段</b>，且"搜到 {@code code != 0} 时丢票重取一次"。
 *       断言：{@code ★ 票失效后自动换票}、{@code ★ 两个实例的票互不影响}。</li>
 *   <li><b>{@code getToken()} 用严格 Gson 取值</b>（{@code :200} 的
 *       {@code .get("token").getAsString()}）：响应少一个字段就抛，且 {@code catch} 里
 *       只打日志、{@code token} 保持旧值（可能是 null）⇒ 后续请求带着
 *       {@code x-mipan-token: null} 出去。→ 本类取票失败即视为"未取到"，
 *       搜索请求<b>不带该头</b>而不是带 {@code "null"}。断言同上。</li>
 *   <li><b>严格 JSON 访问 27 处、{@code optXxx} 0 处</b>（{@code MiPan.java}）：
 *       少任一字段整次搜索失败。→ 本类逐层 {@code opt}，单条坏数据只丢那一条。</li>
 *   <li><b>搜索代码写了两遍</b>（{@code E()} 与 {@code F()}，{@code :39} 与 {@code :118}）：
 *       同一段 POST + 解析抄了两份，任一处改动都会漂移。→ 本类只有一份。</li>
 *   <li><b>{@code clearCache()} 在每次搜索时清空全局静态缓存</b>（{@code :32} 的
 *       {@code clearCache()} + {@code synchronized (cacheMap)}）：
 *       两个实例并发搜索会互相把对方的缓存清掉。本类无该缓存（展平后不需要）。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/MiPan.java}（300 行）——
 * 取票 {@code :195-204}、搜索 POST {@code :39,/118}、展平字段 {@code :130-145}。
 */
public class MiPanPlain extends PanApiPlain {

    private static final String TAG = "MiPanPlain";

    /** 兜底站点（可被 ext 的 site/url/host 或 {@link #setSiteForTest} 覆盖） */
    private static final String DEFAULT_SITE = "https://www.mipan.so";

    /**
     * 上游明确跳过的网盘类型（{@code MiPan.java:58,/132}）。
     * <p>{@code 115} 与本工程 {@code PanPlain} 的识别表不匹配；{@code other/others}
     * 是聚合兜底桶（内容杂）；{@code pikpak} 国内不可用。
     */
    public static final Set<String> SKIP_TYPES = new LinkedHashSet<>(
            java.util.Arrays.asList("115", "other", "others", "pikpak"));

    /** 票（实例字段，见缺陷 1） */
    private String token = "";
    /** 页码（上游 E/F 都只用第 1 页，这里可注入便于测试） */
    private int page = 1;
    /** 已换票次数（测试断言用） */
    private int tokenFetches = 0;

    @Override
    protected String defaultSite() {
        return DEFAULT_SITE;
    }

    @Override
    protected String tagOf() {
        return TAG;
    }

    // ── 协议常量 ─────────────────────────────────────────────

    /** 取票端点（{@code MiPan.java:198}） */
    public String tokenUrl() {
        return site() + "/api/token";
    }

    /** 搜索端点（{@code MiPan.java:39}） */
    public String searchUrl() {
        return site() + "/api/search";
    }

    /** 搜索 body：{@code {"kw":"<明文>"}}（上游 Gson {@code addProperty}，<b>不 urlencode</b>） */
    public String buildSearchBody(String key) {
        return "{\"kw\":\"" + jsonEscape(key) + "\"}";
    }

    /** 搜索请求头：UA + Content-Type + 票（票为空则不带，见缺陷 2） */
    public Map<String, String> searchHeaders() {
        Map<String, String> h = Http.headersOf(
                "User-Agent", UA,
                "Content-Type", "application/json");
        if (!TextUtils.isEmpty(token)) {
            h.put("x-mipan-token", token);
        }
        return h;
    }

    /** ext 下发的票（可选，与 token 文件等价的能力） */
    public void setTokenForTest(String t) {
        token = t == null ? "" : t;
    }

    /** 当前票（空串表示没取到） */
    public String token() {
        return token;
    }

    /** 已换票次数 */
    public int tokenFetches() {
        return tokenFetches;
    }

    public int page() {
        return page;
    }

    public void setPageForTest(int p) {
        page = p < 1 ? 1 : p;
    }

    // ── 取票 ────────────────────────────────────────────────

    /**
     * 取票（懒加载 + 可重取）。
     *
     * @param force {@code true} 时无视现有票强制重取（票失效后用）
     * @return false 表示没取到
     */
    public boolean reloadToken(boolean force) {
        if (!force && !TextUtils.isEmpty(token)) return true;
        if (siteMissing()) return false;
        String resp = fetchWith(tokenUrl(), Http.headersOf("User-Agent", UA));
        tokenFetches++;
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "token: empty response");
            return false;
        }
        JSONObject o = parseObject(resp);
        String t = o == null ? "" : o.optString("token", "");
        if (TextUtils.isEmpty(t)) {
            Log.w(TAG, "token: missing field");
            token = "";
            return false;
        }
        token = t;
        return true;
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

        reloadToken(false);
        String body = buildSearchBody(key);
        String resp = postWith(searchUrl(), body, searchHeaders());

        // 票失效的典型表现是 code != 0：丢票重取一次再试（缺陷 1 的修正）
        JSONObject root = parseObject(resp);
        if (root != null && root.optInt("code", 0) != 0 && !TextUtils.isEmpty(token)) {
            Log.i(TAG, "search: code != 0, retry with fresh token");
            if (reloadToken(true)) {
                resp = postWith(searchUrl(), body, searchHeaders());
                root = parseObject(resp);
            }
        }
        if (root == null) {
            Log.w(TAG, "search: bad response");
            return VodBuilder.create().searchContent();
        }
        if (root.optInt("code", 0) != 0) {
            Log.w(TAG, "search: code=" + root.optInt("code", 0));
            return VodBuilder.create().searchContent();
        }

        List<Vod> list = flatten(root);
        Log.i(TAG, "search '" + key + "' -> " + list.size() + " items");
        return VodBuilder.of(list).searchContent();
    }

    /** 把 {@code data.merged_by_type} 展平成条目列表 */
    protected List<Vod> flatten(JSONObject root) {
        List<Vod> out = new ArrayList<>();
        JSONObject data = root == null ? null : root.optJSONObject("data");
        JSONObject byType = data == null ? null : data.optJSONObject("merged_by_type");
        if (byType == null) return out;
        // 本地 org.json 桩的 keys() 返回 Iterator（真 org.json 返回 Iterator 也是它），
        // 所以不能直接 for-each —— 见表遍历的写法约定。
        java.util.Iterator<String> it = byType.keys();
        while (it.hasNext()) {
            String type = it.next();
            if (SKIP_TYPES.contains(type)) continue;
            JSONArray arr = byType.optJSONArray(type);
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "");
                if (TextUtils.isEmpty(url)) continue;
                String note = o.optString("note", "");
                String time = o.optString("datetime", "");
                out.add(new Vod()
                        .id(url)
                        .name(TextUtils.isEmpty(note) ? url : note)
                        .pic("")
                        .remarks(time));
            }
        }
        return out;
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
