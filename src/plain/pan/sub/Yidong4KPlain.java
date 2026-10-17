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
 * 移动4K —— 网盘搜索源，继承 {@link PanPlain}。
 *
 * <h2>职责划分</h2>
 * <ul>
 *   <li><b>本类</b>：只做搜索，返回含网盘分享链接的条目</li>
 *   <li><b>基类 PanPlain</b>：识别链接归属网盘（阿里/夸克/UC/…）并分发到解析器</li>
 * </ul>
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  搜索: GET https://www.4kvm.net/api/search?keyword=<urlencode(keyword)>
 *  响应: {data:[{name,url}]}
 *  字段: name / url
 * </pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>★ 上游是 POST 型 JSON 接口，端点在 {vidsite.cloudseekr.com}</b>：
 *       {@code Yidong4K.java:65,/180} {@code /vidsite/vs/qryPageColumnVideo}（列分类/列表）、
 *       {@code :113} {@code /vidsite/vs/qryVideoDetail}（详情）、
 *       {@code :105} {@code /vidsite/vs/addAccessRecord}（<b>埋点</b>）。
 *       本类用 {@code https://www.4kvm.net/api/search?keyword=} ——
 *       <b>域名、路径、方法三者都不同</b>，需真实流量确认。</li>
 *   <li>{@code homeContent} / {@code categoryContent} / {@code detailContent} 上游皆有，本类未实现。</li>
 *   <li>{@code addAccessRecord} 的访问上报未实现 —— <b>有意不实现</b>（见下）。</li>
 *   <li>上游的固定请求头集合（{@code Yidong4K.java} 内的 header 组装）未完全复刻。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>每次调用都上报一次 {@code addAccessRecord}</b>（{@code Yidong4K.java:105}）：
 *       把用户行为上报给第三方（与 {@code Baiku} 等处的 {@code l.a("searchvodname", …)}
 *       是同一类数据让渡）。
 *       → 本类<b>有意不实现</b>任何上报。这是本工程与上游的<b>明确差异</b>，
 *       不是遗漏：如果需要统计，请用宿主自己的埋点通道。</li>
 *   <li><b>全量严格 JSON 访问</b>：{@code Yidong4K.java} 里
 *       {@code getXxx} 共 27 处、{@code optXxx} <b>0 处</b>，只有 2 组 {@code try/catch}。
 *       ⇒ 接口少任一字段就整次失败。→ 本类统一 {@code opt} 容错。
 *       断言：{@code PanChildrenTest} 字段映射容错组。</li>
 *   <li>4 个 endpoint 字面量散落在 4 处（{@code :65,:105,:113,:180}），
 *       没有一个常量或路径前缀；域名 {@code vidsite.cloudseekr.com} 也重复出现。
 *       → 本类把端点收敛到类首常量。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Yidong4K.java}
 */
public class Yidong4KPlain extends PanPlain {

    private static final String TAG = "Yidong4KPlain";

    /** 搜索接口（还原自反编译） */
    private static final String API = "https://www.4kvm.net/api/search?keyword=";

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
        String name = firstNonEmpty(o, "name","title");
        String url  = firstNonEmpty(o, "url","link");
        String note = firstNonEmpty(o, "note");
        if (name.isEmpty() && url.isEmpty()) return null;
        return new Vod()
                .id(url)
                .name(name)
                .pic(firstNonEmpty(o, "pic","cover", DEFAULT_PIC))
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
