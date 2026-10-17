package com.example.tvbox.plain.crypto;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Digest;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 热播影视 —— 带 MD5 时间戳签名的 App 源。
 *
 * <h2>签名机制（还原自反编译）</h2>
 * <pre>
 *  timestamp = 当前秒级时间戳
 *  sign      = md5( SIGN_SALT + timestamp )     ← 小写 32 位 hex
 *  请求头     = { timestamp, sign }
 * </pre>
 * 上游原文：{@code a("7gp0bnd2sr85ydii2j32pcypscoc4w6c7g5spl" + str2)}，
 * 其中 {@code a()} 就是标准的 MD5 → 32 位小写 hex。
 *
 * <h2>端点</h2>
 * <pre>
 *  首页  /v3/type/top_type     → data.list[{type_id, type_name, extend, area, year, lang}]
 *  分类  /v3/home/type_search
 *  搜索  /v3/home/search
 *  详情  /v3/home/vod_details
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/AppRJ.java} (32550 字节)
 
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>签名的时间精度</b>：上游用**秒级**时间戳，所以同一秒内的多次请求签名完全相同；
 *       本类保留（改成毫秒会与服务端校验不一致）。</li>
 *   <li><b>业务错误码</b>：只取 {@code data}，不看业务 code（上游同样）。</li>
 *   <li><b>播放地址里的签名</b>：{@code playerContent} 把
 *       {@code &sign=…&timestamp=…} 拼进 URL 查询串；本类保留这一形态，
 *       但"CDN 是否校验该签名"未验证。</li>
 *   <li><b>未还原</b>：接口域名/线路前缀来自 ext 或站点常量；真实线路 id 集合未知。</li>
 * </ol>
 *
 * <h2>记录的上游缺陷 / 硬编码异味</h2>
 * <ol>
 *   <li><b>签名 = md5(盐 + 时间戳)</b>：盐硬编码在客户端，且没有 nonce/参数参与 ——
 *       同一秒内可重放；签名与具体请求体无关（换 body 不换签名）。</li>
 *   <li><b>MD5 十六进制用了 {@code BigInteger} + 手工补零</b>：
 *       {@code new BigInteger(1, digest).toString(16)} 会丢前导零，于是上游自己补到 32 位 ——
 *       等价于 {@code String.format("%032x")}，属于绕路写法（本类保持输出一致）。</li>
 *   <li><b>签名进 URL</b>：播放地址带 {@code sign/timestamp} 查询串，容易进 Referer 与日志；
 *       上游没有做任何脱敏。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/AppRJ.java}（{@code a()} 的 MD5+补零、
 * 4 处 {@code h.c("timestamp",…,"sign",…)} 调用点、{@code playerContent} 的 URL 拼签名）。
*/
public class AppRJPlain extends BaseSpider {

    private static final String TAG = "AppRJPlain";

    /**
     * 签名盐 —— 硬编码在上游 {@code homeContent()} 里。
     * <p>这是「纯硬编码」类常量（不属于 ext 契约），可直接内联。
     */
    public static final String SIGN_SALT = "7gp0bnd2sr85ydii2j32pcypscoc4w6c7g5spl";

    private static final int PAGE_SIZE = 20;

    /** 分类筛选维度的中文名映射（上游同样是这 4 个） */
    private static final Map<String, String> FILTER_LABELS = new LinkedHashMap<>();
    static {
        FILTER_LABELS.put("extend", "类型");
        FILTER_LABELS.put("area", "地区");
        FILTER_LABELS.put("year", "年份");
        FILTER_LABELS.put("lang", "语言");
    }

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        Log.i(TAG, "init, base=" + baseUrl + " (签名: md5(salt+timestamp))");
    }

    /** 生成签名头 —— 还原上游 {@code str2 + a(SALT + str2)} 逻辑 */
    protected Map<String, String> signedHeaders() {
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        Map<String, String> h = new LinkedHashMap<>(headers());
        h.put("timestamp", ts);
        h.put("sign", Digest.md5(SIGN_SALT + ts));
        return h;
    }

    /** 带签名的 POST */
    protected String apiPost(String path, JSONObject body) {
        String base = requireUrl();
        if (base.isEmpty()) return "";
        String resp = http.post(base + path, body == null ? "{}" : body.toString(), signedHeaders());
        return TextUtils.isEmpty(resp) ? "" : resp;
    }

    @Override
    public String homeContent(boolean filter) {
        String resp = apiPost("/v3/type/top_type", new JSONObject());
        if (resp.isEmpty()) return emptyResponse();

        Map<String, String> classes = new LinkedHashMap<>();
        JSONObject filters = new JSONObject();
        try {
            JSONObject data = new JSONObject(resp).optJSONObject("data");
            JSONArray list = data == null ? null : data.optJSONArray("list");
            if (list == null) return emptyResponse();

            for (int i = 0; i < list.length(); i++) {
                JSONObject t = list.optJSONObject(i);
                if (t == null) continue;
                String tid = t.optString("type_id");
                String tname = t.optString("type_name");
                if (tid.isEmpty()) continue;
                classes.put(tid, tname);

                // 构造筛选：extend/area/year/lang 四个维度
                JSONObject f = new JSONObject();
                for (Map.Entry<String, String> e : FILTER_LABELS.entrySet()) {
                    JSONArray vals = t.optJSONArray(e.getKey());
                    if (vals == null || vals.length() == 0) continue;
                    JSONArray opts = new JSONArray();
                    JSONObject all = new JSONObject();
                    try {
                        all.put("n", "全部");
                        all.put("v", "");
                    } catch (Exception ignored) {}
                    opts.put(all);
                    for (int j = 0; j < vals.length(); j++) {
                        String v = vals.optString(j, "");
                        if (v.length() <= 1) continue;   // 上游同样过滤过短值
                        JSONObject o = new JSONObject();
                        try {
                            o.put("n", v);
                            o.put("v", v);
                        } catch (Exception ignored) {}
                        opts.put(o);
                    }
                    if (opts.length() > 1) {
                        try { f.put(e.getKey(), opts); } catch (Exception ignored) {}
                    }
                }
                if (f.length() > 0) {
                    try { filters.put(tid, f); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "homeContent parse failed", e);
            return emptyResponse();
        }

        VodBuilder vb = VodBuilder.create();
        for (Map.Entry<String, String> e : classes.entrySet()) {
            vb.clazz(e.getKey(), e.getValue());
        }
        return vb.homeContent();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extendMap) {
        int page = pageNum(pg, 1);
        JSONObject body = new JSONObject();
        try {
            body.put("type_id", tid);
            body.put("page", page);
            if (extendMap != null) {
                for (String k : FILTER_LABELS.keySet()) {
                    String v = extendMap.get(k);
                    if (v != null && !v.isEmpty()) body.put(k, v);
                }
            }
        } catch (Exception ignored) {}

        String resp = apiPost("/v3/home/type_search", body);
        if (resp.isEmpty()) return VodBuilder.create().page(page).categoryContent();

        List<Vod> list = parseVodList(resp);
        return VodBuilder.create().addAll(list).page(page).categoryContent();
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (id.isEmpty()) return emptyResponse();

        JSONObject body = new JSONObject();
        try { body.put("vod_id", id); } catch (Exception ignored) {}

        String resp = apiPost("/v3/home/vod_details", body);
        if (resp.isEmpty()) return emptyResponse();

        try {
            JSONObject data = new JSONObject(resp).optJSONObject("data");
            JSONObject v = data == null ? null : data.optJSONObject("vod");
            if (v == null) v = data;
            if (v == null) return emptyResponse();

            Vod vod = new Vod()
                    .id(id)
                    .name(v.optString("vod_name"))
                    .pic(v.optString("vod_pic"))
                    .remarks(v.optString("vod_remarks"))
                    .content(v.optString("vod_content"))
                    .actor(v.optString("vod_actor"))
                    .director(v.optString("vod_director"))
                    .area(v.optString("vod_area"))
                    .year(v.optString("vod_year"));

            JSONArray play = v.optJSONArray("vod_play_list");
            if (play != null && play.length() > 0) {
                List<String> froms = new ArrayList<>();
                List<String> urls = new ArrayList<>();
                for (int i = 0; i < play.length(); i++) {
                    JSONObject line = play.optJSONObject(i);
                    if (line == null) continue;
                    String name = line.optString("name", "线路" + (i + 1));
                    JSONArray eps = line.optJSONArray("url");
                    if (eps == null) continue;
                    List<String> parts = new ArrayList<>();
                    for (int j = 0; j < eps.length(); j++) {
                        String raw = eps.optString(j, "");
                        if (raw.isEmpty()) continue;
                        parts.add(raw.contains("$") ? raw : ("第" + (j + 1) + "集$" + raw));
                    }
                    if (parts.isEmpty()) continue;
                    froms.add(name);
                    urls.add(TextUtils.join(Vod.SEP_EPISODE, parts));
                }
                if (!froms.isEmpty()) {
                    vod.playFrom(TextUtils.join(Vod.SEP_SOURCE, froms))
                       .playUrl(TextUtils.join(Vod.SEP_SOURCE, urls));
                }
            }
            return VodBuilder.detail(vod).detailContent();
        } catch (Exception e) {
            Log.e(TAG, "detailContent parse failed", e);
            return emptyResponse();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return emptyResponse();

        JSONObject body = new JSONObject();
        try {
            body.put("keyword", key);
            body.put("page", 1);
        } catch (Exception ignored) {}

        String resp = apiPost("/v3/home/search", body);
        if (resp.isEmpty()) return VodBuilder.create().searchContent();
        return VodBuilder.of(parseVodList(resp)).searchContent();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) return emptyResponse();
        String type = id.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/*";
        return VodBuilder.playerContent(id, type);
    }

    /** 解析列表 —— 容错多种字段路径 */
    protected List<Vod> parseVodList(String resp) {
        List<Vod> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(resp);
            JSONArray arr = root.optJSONArray("list");
            if (arr == null) {
                JSONObject data = root.optJSONObject("data");
                if (data != null) arr = data.optJSONArray("list");
            }
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new Vod()
                        .id(o.optString("vod_id"))
                        .name(o.optString("vod_name"))
                        .pic(o.optString("vod_pic"))
                        .remarks(o.optString("vod_remarks")));
            }
        } catch (Exception e) {
            Log.e(TAG, "parseVodList failed", e);
        }
        return out;
    }
}
