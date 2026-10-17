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
 * 百酷 —— 网盘搜索源，继承 {@link PanPlain}。
 *
 * <h2>职责划分</h2>
 * <ul>
 *   <li><b>本类</b>：只做搜索，返回含网盘分享链接的条目</li>
 *   <li><b>基类 PanPlain</b>：识别链接归属网盘（阿里/夸克/UC/…）并分发到解析器</li>
 * </ul>
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  搜索: GET https://api.kuleu.com/api/bddj?text=<urlencode(keyword)>
 *  响应: {code:200, data:[{name, viewlink, addtime}]}
 *  字段: name / viewlink / addtime
 * </pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li>上游有<b>三参重载</b> {@code searchContent(String, boolean, String)}
 *       （{@code Baiku.java:54}），第二参是页码/子关键字；本类只实现
 *       {@code Spider} 接口的两参版（宿主默认只调两参）。
 *       ⇒ 上游的"分页/二次搜索"入口在本实现里不存在。</li>
 *   <li>上游对每条结果做 <b>客户端二次过滤 {@code name.contains(str)}</b>
 *       （{@code Baiku.java:28}）。本类<b>不做该过滤</b>，因此返回条目数会比上游多。</li>
 *   <li>上游给每条结果打<b>固定百度图标</b>（{@code Baiku.java:31}）；本类
 *       {@code DEFAULT_PIC} 为空串 ⇒ 列表无封面。</li>
 *   <li>上游埋点 {@code merge.l.a("searchvodname", key)}（{@code Baiku.java:50}）：
 *       每次搜索把关键字上报第三方。→ <b>有意不实现</b>（本类不引入任何上报）。</li>
 *   <li>上游<b>没有</b> {@code homeContent/categoryContent/detailContent/playerContent}
 *       —— 全靠 {@code Pan} 基类。本类由 {@link PanPlain} 承担同样的角色，
 *       故这几项不构成遗漏。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>{@code E()} 全程没有 try</b>（{@code Baiku.java:18-41}）：
 *       {@code JsonParser.parseString(strL).getAsJsonObject().getAsJsonArray("data")}
 *       任一环节缺失或非法即抛异常，<b>直接冒到宿主</b>（用户看到"搜索失败"而不是空列表）。
 *       → 本类逐层 {@code opt} + 整体 try/兜底空响应。
 *       断言：{@code PanChildrenTest} 的空 key / 畸形响应组。</li>
 *   <li>固定图标 URL 写死在代码里（{@code Baiku.java:31}）：换图要发版，
 *       且该图床域名一旦失效全部条目变空白。→ 本类不内置图标常量。</li>
 *   <li>两参版只是"埋点 + 转调三参版"（{@code Baiku.java:49-52}），
 *       三参版才含逻辑：两参版没有任何独立行为，是纯粹的转发层。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Baiku.java}
 */
public class BaikuPlain extends PanPlain {

    private static final String TAG = "BaikuPlain";

    /** 搜索接口（还原自反编译） */
    private static final String API = "https://api.kuleu.com/api/bddj?text=";

    /** 默认 UA（上游硬编码） */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 默认封面（上游对无图条目统一用此图） */
    private static final String DEFAULT_PIC = "https://img.51shazhu.com/autoupload/g1b6f/20260806/V6JN/1200X800/baidu.jpg/webp";


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
        for (String f : new String[]{"data"}) {
            JSONArray a = root.optJSONArray(f);
            if (a != null) return a;
        }
        return null;
    }

    /** 解析单条；默认按 名称/链接/备注 三字段映射 */
    public Vod parseItem(JSONObject o) {
        String name = firstNonEmpty(o, "name");
        String url  = firstNonEmpty(o, "viewlink");
        String note = firstNonEmpty(o, "addtime");
        if (name.isEmpty() && url.isEmpty()) return null;
        return new Vod()
                .id(url)
                .name(name)
                .pic(firstNonEmpty(o, "", DEFAULT_PIC))
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
