package com.example.tvbox.plain.pan.sub;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.pan.PanPlain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 搜4K —— 网盘搜索源，继承 {@link PanPlain}。
 *
 * <h2>职责划分</h2>
 * <ul>
 *   <li><b>本类</b>：只做搜索，返回含网盘分享链接的条目</li>
 *   <li><b>基类 PanPlain</b>：识别链接归属网盘（阿里/夸克/UC/…）并分发到解析器</li>
 * </ul>
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  搜索: GET https://sou4k.com/api/recommend/?keyword=<urlencode(keyword)>
 *  响应: {data:[{title,share_url,pwd,disk_type,poster_url,summary,vote_average}]}
 *  字段: title / share_url / pwd / disk_type / poster_url / vote_average / summary
 * </pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>★ 上游的 {@code searchContent} 直接 {@code return null}</b>
 *       （{@code Sou4K.java:179}）—— 这个源<b>本来就不做搜索</b>。
 *       本类反而实现了搜索（改用 {@code /api/recommend/} 端点 + {@code keyword=}），
 *       属于<b>本工程自加的能力</b>，不是对上游的还原。列此以免误认为等价。</li>
 *   <li>{@code homeContent} 的 3 个排行分类（{@code Sou4K.java:127-137}）：
 *       {@code high_score}→电影排行 / {@code classic_tv}→剧集排行 / {@code short}→短剧排行。
 *       本类返回空响应。</li>
 *   <li>{@code categoryContent} 的排行榜：上游用
 *       {@code /api/recommend/<type_id>}（{@code Sou4K.java:57}）且只在 {@code pg=="1"} 时请求。
 *       未实现。</li>
 *   <li><b>两段式"验活"详情链</b>（{@code Sou4K.java:104-140}）：
 *       ① {@code GET /api/search?q=<enc>&page=1&page_size=9999} 取 {@code data.results[]}；
 *       ② 只保留 {@code type=="cloud"} 的条目，最多 30 条，拼成 {@code {"items":[...]}}；
 *       ③ <b>POST</b> {@code /api/check-links} 验活；
 *       ④ 只取 {@code summary=="链接有效"} 的，<b>最多 3 个</b>；
 *       ⑤ 过 {@code PanOrder.sort} 后生成 play_from/play_url。
 *       <b>本类完全没有这段</b>（详情交给 {@link PanPlain} 的 resolver）。</li>
 *   <li>{@code getCloudIcon()} 的 9 种网盘图标映射（{@code Sou4K.java:25-51}）未复刻。</li>
 *   <li>上游 header 有 3 项（{@code UA} + {@code Referer} + {@code Accept}，
 *       {@code Sou4K.java:17-22}），本类只带 UA。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>上游把"搜索"实现成返回 null</b>（{@code Sou4K.java:179}）：
 *       宿主拿到 null 会按"无结果"处理，但这是契约违规（应为 {@code ""} 或标准空响应）。
 *       → 本类返回标准空响应。</li>
 *   <li>验活链里 {@code length > 30} 截断、{@code arrayList.size() < 3} 再截断
 *       （{@code Sou4K.java:108,124}）：两个魔数都没有常量名或注释，
 *       而且"只取前 3 个有效链接"会让本来有效的第 4 个永远播不了。
 *       → 本类不实现该链，但把魔数记在此处。</li>
 *   <li>{@code getCloudIcon} 用一条 {@code if-else} 链比较 9 个字符串
 *       （{@code Sou4K.java:25-51}），无法命中时返回空串 ⇒ 封面为空。
 *       → 本类不内置图标常量。</li>
 *   <li>接口无 {@code try} 包裹的严格访问为零（{@code optXxx} 16 处），
 *       但 {@code detailContent} 用 {@code getString} 取 {@code disk_type/share_url/pwd}
 *       且只在最外层兜一个 {@code catch (Exception)} —— 单条坏数据会让<b>整次详情</b>失败。
 *       → 本类逐条容错。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Sou4K.java}
 */
public class Sou4KPlain extends PanPlain {

    private static final String TAG = "Sou4KPlain";

    /** 搜索接口（还原自反编译） */
    private static final String API = "https://sou4k.com/api/recommend/?keyword=";

    /** 默认 UA（上游硬编码） */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 默认封面（上游对无图条目统一用此图） */
    private static final String DEFAULT_PIC = "";


    /**
     * 可覆写的 GET —— 测试注入点。
     *
     * <p>{@code Http.setOfflineForTest(true)} 之后真实请求一律返回空串，
     * 「请求形态」与「解析路径」都无法通过真网络断言。测试用一个只记录不联网的子类
     * 覆写本方法，即可把"发出去的到底是什么 / 拿回来的东西怎么被解析"钉死。
     */
    public String fetchWith(String url, java.util.Map<String, String> headers) {
        return http.get(url, headers);
    }

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        Log.i(TAG, "init, base=" + baseUrl);
    }

    @Override
    public String homeContent(boolean filter) {
        // 纯搜索型网盘源，无首页分类
        return emptyResponse();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return VodBuilder.create().searchContent();

        String api = buildSearchUrl(key);
        if (api.isEmpty()) {
            Log.w(TAG, "search: empty api url");
            return VodBuilder.create().searchContent();
        }

        String resp = fetchWith(api, Http.headersOf("User-Agent", UA));
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "search: empty response");
            return VodBuilder.create().searchContent();
        }

        List<Vod> list = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(resp);
            JSONArray arr = extractList(root);
            if (arr == null) {
                Log.w(TAG, "search: no list field in response");
                return VodBuilder.create().searchContent();
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Vod v = parseItem(o);
                if (v != null) list.add(v);
            }
        } catch (Exception e) {
            Log.e(TAG, "search parse failed", e);
            return VodBuilder.create().searchContent();
        }

        Log.i(TAG, "search '" + key + "' -> " + list.size() + " items");
        return VodBuilder.of(list).searchContent();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        // 播放由基类 detailContent + 网盘解析器处理
        return emptyResponse();
    }

    // ─────────────────────────────────────────────────────────────
    // 子类可覆写的协议钩子
    // ─────────────────────────────────────────────────────────────

    /** 构造搜索 URL；默认拼接 API + 编码后的关键字 */
    public String buildSearchUrl(String key) {
        try {
            return API + URLEncoder.encode(key, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** 从响应根节点取列表；默认取 data / list / search_list 之一 */
    public JSONArray extractList(JSONObject root) {
        for (String f : new String[]{"data","list"}) {
            JSONArray a = root.optJSONArray(f);
            if (a != null) return a;
        }
        return null;
    }

    /** 解析单条；默认按 名称/链接/备注 三字段映射 */
    public Vod parseItem(JSONObject o) {
        String name = firstNonEmpty(o, "title","name");
        String url  = firstNonEmpty(o, "share_url","url");
        String note = firstNonEmpty(o, "disk_type","type");
        if (name.isEmpty() && url.isEmpty()) return null;
        return new Vod()
                .id(url)
                .name(name)
                .pic(firstNonEmpty(o, "poster_url","cover", DEFAULT_PIC))
                .remarks(note);
    }

    /** 依次尝试多个字段名，返回第一个非空值 */
    protected static String firstNonEmpty(JSONObject o, String... keys) {
        if (o == null || keys == null) return "";
        for (String k : keys) {
            if (k == null || k.isEmpty()) continue;
            String v = o.optString(k, "");
            if (!TextUtils.isEmpty(v) && !"null".equals(v)) return v;
        }
        return "";
    }

    /** 同上，但允许最后给一个默认值 */
    protected static String firstNonEmpty(JSONObject o, String[] keys, String def) {
        String v = firstNonEmpty(o, keys);
        return v.isEmpty() ? (def == null ? "" : def) : v;
    }
}
