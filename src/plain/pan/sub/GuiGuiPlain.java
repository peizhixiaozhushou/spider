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
 * 鬼鬼 —— 网盘搜索源，继承 {@link PanPlain}。
 *
 * <h2>职责划分</h2>
 * <ul>
 *   <li><b>本类</b>：只做搜索，返回含网盘分享链接的条目</li>
 *   <li><b>基类 PanPlain</b>：识别链接归属网盘（阿里/夸克/UC/…）并分发到解析器</li>
 * </ul>
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  搜索: GET https://api.guiguiya.com/api/video/duanju?act=search&name=<urlencode(keyword)>
 *  响应: {data:[{name,url,addtime}]}
 *  字段: name / url / addtime
 * </pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li>上游三参重载 {@code searchContent(String, boolean, String)}（{@code GuiGui.java:64}）未实现。</li>
 *   <li><b>空/失败响应的形态与上游不同</b>：上游在 {@code code != "200"} 时返回
 *       <b>字面量 {@code "[]"}</b>（{@code GuiGui.java:26-27}），而不是 TVBox 标准空响应
 *       {@code {"list":[]}}。本类统一返回标准结构。宿主对 {@code "[]"} 的容错行为未知，
 *       此处按"更正确"的一侧实现，故与上游字节级不同。</li>
 *   <li>上游的 {@code name.contains(str)} 客户端过滤（{@code GuiGui.java:33}）未复刻。</li>
 *   <li>上游埋点 {@code l.a("searchvodname", key)}（{@code GuiGui.java:60}）有意不实现。</li>
 *   <li>上游无 {@code homeContent/categoryContent/detailContent}，由 {@code Pan} 基类承担；
 *       本类由 {@link PanPlain} 承担，不构成遗漏。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>★ 真 bug：搜索词在循环内被覆盖，导致结果只剩第一条</b>
 *       （{@code GuiGui.java:44}）：上游把 {@code str = str3}（{@code str3 = "共N集"}）
 *       写在 {@code for} 循环体内，而循环条件用的是 {@code name.contains(str)}
 *       （{@code GuiGui.java:33}）。于是<b>第 2 条起，过滤条件变成
 *       {@code name.contains("共N集")}</b> —— 几乎永远不成立。
 *       ⇒ 上游实际效果是"只返回第一条（且仅当片名里含'共N集'）"，
 *       表现为"这个源搜出来总是空的"。
 *       → 本实现不做这个过滤，恢复成"返回接口给的全部条目"。</li>
 *   <li>严格 {@code getString/getJSONArray} + {@code throws JSONException}
 *       （{@code GuiGui.java:14, 24-32}）：缺任意字段即抛到宿主。→ 本类统一 {@code opt} 容错。</li>
 *   <li>两种空响应形态并存（{@code "[]"} 与标准结构）→ 本类统一为标准结构。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/GuiGui.java}
 */
public class GuiGuiPlain extends PanPlain {

    private static final String TAG = "GuiGuiPlain";

    /** 搜索接口（还原自反编译） */
    private static final String API = "https://api.guiguiya.com/api/video/duanju?act=search&name=";

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
        String url  = firstNonEmpty(o, "url","link","viewlink");
        String note = firstNonEmpty(o, "addtime","note");
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
