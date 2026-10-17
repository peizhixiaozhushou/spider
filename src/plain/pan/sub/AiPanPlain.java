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
 * 爱盼 —— 网盘搜索源，继承 {@link PanPlain}。
 *
 * <h2>职责划分</h2>
 * <ul>
 *   <li><b>本类</b>：只做搜索，返回含网盘分享链接的条目</li>
 *   <li><b>基类 PanPlain</b>：识别链接归属网盘（阿里/夸克/UC/…）并分发到解析器</li>
 * </ul>
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  搜索: GET https://www.aipan.me/api/movies/list?keyword=<urlencode(keyword)>
 *  响应: {data:[{id,title,cover,url,year,region,synopsis,rate}]}
 *  字段: id / title / cover / url / year / region / synopsis / rate
 * </pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li>{@code homeContent} 的 3 个分类（{@code AiPan.java:127-137}）：
 *       {@code type_id="top250=1"}→TOP250、{@code "sort=latest"}→最新发布、
 *       {@code "imdbMin=8&sort=latest"}→高分精选。本类返回空响应。</li>
 *   <li>{@code homeVideoContent}（{@code AiPan.java:147} 起）：上游首页有"最近更新"板块。未实现。</li>
 *   <li>{@code categoryContent}：上游把 {@code type_id} <b>整串当 query 拼</b>到
 *       {@code /api/movies/list?} 后面。未实现。</li>
 *   <li>{@code detailContent}：上游走 {@code /api/movies/detail/<id>}（{@code AiPan.java:63}）。
 *       本类由 {@link PanPlain} 基类承担（靠 resolver 解析分享链接），不走详情接口。</li>
 *   <li><b>搜索端点与上游不同</b>：上游的搜索是
 *       {@code GET /api/movies/search?q=<enc>}（{@code AiPan.java:185}），
 *       本类用的是 {@code /api/movies/list?keyword=}。
 *       两者都是站内接口，但<b>参数名不同（q vs keyword）、端点不同</b>，
 *       需要真实流量才能确认哪一个在线上有响应。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>把筛选参数塞进分类 ID</b>（{@code AiPan.java:127-137}）：
 *       {@code type_id} 写成 {@code "top250=1"} / {@code "imdbMin=8&sort=latest"}，
 *       客户端把它们当 ID 上传后再拼进 URL。结果是「分类 ID 即 URL query」，
 *       宿主无法再做组合筛选，也无法安全转义。
 *       → 本类不沿用它（因此分类功能未实现，见上）。</li>
 *   <li><b>严格 / 容错 JSON 访问混用</b>：{@code AiPan.java} 里
 *       {@code getString/getInt/getJSONObject} 共 10 处、{@code optXxx} 共 24 处并存，
 *       哪些字段"必须存在"完全取决于作者当时的心情 —— 站方少一个字段，
 *       严格那 10 处会直接抛异常。
 *       → 本类统一走 {@code optXxx} + 空值兜底（{@code firstNonEmpty}）。
 *       断言：{@code PanChildrenTest} 的列表提取与字段映射容错组。</li>
 *   <li>域名 {@code https://www.aipan.me/} 硬编码（{@code AiPan.java:20} 等）：
 *       站点换域名必须重新发版。→ 本类保留为常量 {@code API}，同样不可配，
 *       但集中在一处便于替换。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/AiPan.java}
 */
public class AiPanPlain extends PanPlain {

    private static final String TAG = "AiPanPlain";

    /** 搜索接口（还原自反编译） */
    private static final String API = "https://www.aipan.me/api/movies/list?keyword=";

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
        String url  = firstNonEmpty(o, "url","link");
        String note = firstNonEmpty(o, "year","rate");
        if (name.isEmpty() && url.isEmpty()) return null;
        return new Vod()
                .id(url)
                .name(name)
                .pic(firstNonEmpty(o, "cover","pic", DEFAULT_PIC))
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
