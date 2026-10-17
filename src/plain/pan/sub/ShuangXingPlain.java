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
 * 双星 —— 网盘搜索源，继承 {@link PanPlain}。
 *
 * <h2>职责划分</h2>
 * <ul>
 *   <li><b>本类</b>：只做搜索，返回含网盘分享链接的条目</li>
 *   <li><b>基类 PanPlain</b>：识别链接归属网盘（阿里/夸克/UC/…）并分发到解析器</li>
 * </ul>
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  搜索: GET https://1.star2.cn/search/?keyword=<urlencode(keyword)>
 *  响应: {data:[{name,url}]}
 *  字段: name / url
 * </pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li>{@code homeContent} / {@code categoryContent} / {@code detailContent}：上游三者皆有
 *       （含一个用 {@code JSONArray} 手工拼列表的搜索实现，{@code ShuangXing.java:39,157,164,201}）。
 *       本类未实现，详情交给 {@link PanPlain}。</li>
 *   <li><b>分页/三参重载</b>：上游 {@code searchContent} 支持页码（{@code :201} 一带），
 *       本类只实现两参版。</li>
 *   <li>上游的列表结果用 {@code new JSONArray()} + 手工 {@code put} 拼装，
 *       而非标准响应构造器；本类用 {@link com.example.tvbox.common.VodBuilder}，
 *       结构与上游等价但生成路径不同。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li>{@code JSONArray}/{@code JSONObject} 手工拼装（{@code ShuangXing.java} 内 13 处
 *       {@code new JSONObject/JSONArray}）：字段名靠字面量硬写，漏一个就产出宿主
 *       认不出的结构，且<b>没有一处做转义</b>（片名含引号即坏 JSON）。
 *       → 本类改用 {@code Vod}+{@code VodBuilder}，由公共基础设施负责转义。
 *       断言：{@code PanChildrenTest} 的输出结构组。</li>
 *   <li>{@code optXxx} 0 处、{@code try/catch} 只有 2 组（{@code ShuangXing.java}）：
 *       容错密度与它的解析代码量不匹配 ⇒ 页面/接口一变即整次失败。
 *       → 本类全程 try + 逐条判空。</li>
 *   <li>站型是 HTML（{@code 1.star2.cn/search/?keyword=}）但上游把结果当 JSON 处理
 *       （13 处 {@code new JSONObject}）—— 若线上返回的是 HTML，这段代码本来就走不通。
 *       <b>列此备查：本类当前同样按 JSON 解析，两者需要真实流量才能判定谁对。</b></li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/ShuangXing.java}
 */
public class ShuangXingPlain extends PanPlain {

    private static final String TAG = "ShuangXingPlain";

    /** 搜索接口（还原自反编译） */
    private static final String API = "https://1.star2.cn/search/?keyword=";

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
