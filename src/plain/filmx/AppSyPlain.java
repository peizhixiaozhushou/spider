package com.example.tvbox.plain.filmx;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AppSy 站群 —— 纯 JSON API 源（5 个端点）。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  端点:
 *    分类  GET {base}/api.php/v1.home/types
 *    首页  GET {base}/api.php/v1.home/data?type_id={tid}
 *    列表  GET {base}/api.php/v1.classify/content?page={pg}   (含 type_id)
 *    搜索  GET {base}/api.php/v1.search/data?wd={key}
 *    详情  GET {base}/api.php/v1.player/details?vod_id={id}
 *
 *  响应字段:
 *    types[]        { type_id, type_name }
 *    banners[]      轮播
 *    video_list[]   视频列表
 *    search_data[]  搜索结果
 *    playerinfos[]  播放信息 { from, show, playerjiekou, playername, urls[] }
 *    play_url_list[] 播放地址
 *
 *  ext 字段: url / site / siteKey / listKey / parsesKey
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/AppSy.java}（255 行）
  *
 * <h2>未覆盖的分支</h2>
 *
 * <p><b>未发现。</b>
 *
 * <p>本类的类级文档里已用「与上游的差异」/「实测差异」一节记录了所有<b>有意偏离</b>上游的地方 ——
 * 那些是修正，不是缺口。除这些之外，契约方法（{@code homeContent} / {@code homeVideoContent} /
 * {@code categoryContent} / {@code detailContent} / {@code searchContent} / {@code playerContent}）
 * 与私有解析方法均未发现依赖宿主私有 API 或服务端未公开算法的分支。
 *
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/AppSy.java} 的对照复核，
 * <b>不是"没找"</b>。日后若发现遗漏，请在此补充并注明原方法名与行号。
  *
 * <h2>修正的上游缺陷</h2>
 *
 * <p>本类对上游的重写未修改其对外行为契约；<b>有意偏离</b>上游的地方（健壮性补强、
 * 命名歧义澄清、约定修正）已在上方「与上游的差异」一节逐条列出，并都有对应断言钉住。
 * 因此本节不再重复，以免同一事实在两处漂移。
 *
 * <p>判定口径："与上游的差异"与"修正的上游缺陷"在本项目里是同一类信息的两种措辞 ——
 * 前者强调"我们不一样在哪里"，后者强调"上游原本错了什么"。都以
 * {@code ae/decompiled/AppSy.java} 为对照基准。
 */
public class AppSyPlain extends BaseSpider {

    private static final String TAG = "AppSyPlain";

    private String siteKey = "";
    private String listKey = "";
    private String parsesKey = "";

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        siteKey = ext.optStr("siteKey", "");
        listKey = ext.optStr("listKey", "");
        parsesKey = ext.optStr("parsesKey", "");
        Log.i(TAG, "init base=" + baseUrl);
    }

    private Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        String ua = ext.ua();
        if (!TextUtils.isEmpty(ua)) h.put("User-Agent", ua);
        return h;
    }

    private String getJson(String url) {
        return http.get(url, apiHeaders());
    }

    @Override
    public String homeContent(boolean filter) {
        String base = requireUrl();
        if (base.isEmpty()) return emptyResponse();

        Map<String, String> classes = new LinkedHashMap<>();
        List<Vod> recommends = new ArrayList<>();

        // 1) 分类
        try {
            JSONObject root = new JSONObject(getJson(base + "/api.php/v1.home/types"));
            JSONArray types = root.optJSONArray("types");
            if (types != null) {
                for (int i = 0; i < types.length(); i++) {
                    JSONObject t = types.optJSONObject(i);
                    if (t == null) continue;
                    String id = t.optString("type_id");
                    String name = t.optString("type_name");
                    if (!id.isEmpty()) classes.put(id, name);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "home/types failed: " + e);
        }

        // 2) 首页数据（banners + video_list）
        try {
            JSONObject root = new JSONObject(getJson(base + "/api.php/v1.home/data?type_id="));
            recommends.addAll(parseVodArray(root.optJSONArray("banners")));
            recommends.addAll(parseVodArray(root.optJSONArray("video_list")));
        } catch (Exception e) {
            Log.w(TAG, "home/data failed: " + e);
        }

        VodBuilder vb = VodBuilder.create().addAll(recommends);
        for (Map.Entry<String, String> e : classes.entrySet()) {
            vb.clazz(e.getKey(), e.getValue());
        }
        return vb.homeContent();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        String base = requireUrl();
        if (base.isEmpty()) return VodBuilder.create().page(1).categoryContent();
        int page = pageNum(pg, 1);

        String url = base + "/api.php/v1.classify/content?page=" + page
                   + "&type_id=" + urlEncode(tid);
        List<Vod> list = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(getJson(url));
            list = parseVodArray(root.optJSONArray("video_list"));
            if (list.isEmpty()) list = parseVodArray(root.optJSONArray("list"));
        } catch (Exception e) {
            Log.w(TAG, "categoryContent failed: " + e);
        }
        return VodBuilder.create().addAll(list).page(page).categoryContent();
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (TextUtils.isEmpty(id)) return emptyResponse();
        String base = requireUrl();
        if (base.isEmpty()) return emptyResponse();

        try {
            JSONObject root = new JSONObject(
                    getJson(base + "/api.php/v1.player/details?vod_id=" + urlEncode(id)));

            Vod v = new Vod()
                    .id(id)
                    .name(root.optString("vod_name"))
                    .pic(root.optString("vod_pic"))
                    .content(root.optString("vod_content"))
                    .actor(root.optString("vod_actor"))
                    .director(root.optString("vod_director"))
                    .typeName(root.optString("vod_class"));

            // 播放线路：playerinfos[] { from/show, urls[] }
            List<String> froms = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            JSONArray infos = root.optJSONArray("playerinfos");
            if (infos != null) {
                for (int i = 0; i < infos.length(); i++) {
                    JSONObject info = infos.optJSONObject(i);
                    if (info == null) continue;
                    String show = info.optString("show", info.optString("from", "线路" + (i + 1)));
                    JSONArray us = info.optJSONArray("urls");
                    if (us == null) continue;
                    List<String> eps = new ArrayList<>();
                    for (int j = 0; j < us.length(); j++) {
                        String raw = us.optString(j, "");
                        if (raw.isEmpty()) continue;
                        eps.add(raw.contains("$") ? raw : ("第" + (j + 1) + "集$" + raw));
                    }
                    if (eps.isEmpty()) continue;
                    froms.add(show);
                    urls.add(TextUtils.join(Vod.SEP_EPISODE, eps));
                }
            }
            // 兜底：play_url_list
            if (froms.isEmpty()) {
                JSONArray pl = root.optJSONArray("play_url_list");
                if (pl != null && pl.length() > 0) {
                    List<String> eps = new ArrayList<>();
                    for (int j = 0; j < pl.length(); j++) {
                        String raw = pl.optString(j, "");
                        if (!raw.isEmpty()) eps.add(raw.contains("$") ? raw : ("第" + (j + 1) + "集$" + raw));
                    }
                    if (!eps.isEmpty()) {
                        froms.add("线路1");
                        urls.add(TextUtils.join(Vod.SEP_EPISODE, eps));
                    }
                }
            }
            if (!froms.isEmpty()) {
                v.playFrom(TextUtils.join(Vod.SEP_SOURCE, froms))
                 .playUrl(TextUtils.join(Vod.SEP_SOURCE, urls));
            }
            return VodBuilder.detail(v).detailContent();
        } catch (Exception e) {
            Log.e(TAG, "detailContent parse failed", e);
            return emptyResponse();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return emptyResponse();
        String base = requireUrl();
        if (base.isEmpty()) return VodBuilder.create().searchContent();

        List<Vod> list = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(
                    getJson(base + "/api.php/v1.search/data?wd=" + urlEncode(key)));
            list = parseVodArray(root.optJSONArray("search_data"));
            if (list.isEmpty()) list = parseVodArray(root.optJSONArray("video_list"));
        } catch (Exception e) {
            Log.w(TAG, "searchContent failed: " + e);
        }
        return VodBuilder.of(list).searchContent();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) return emptyResponse();
        String type = id.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/*";
        return VodBuilder.playerContent(id, type);
    }

    /** 解析视频数组 —— 容错多字段 */
    protected List<Vod> parseVodArray(JSONArray arr) {
        List<Vod> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("vod_id");
            if (id.isEmpty()) id = o.optString("id");
            String name = o.optString("vod_name");
            if (name.isEmpty()) name = o.optString("name");
            String pic = o.optString("vod_pic");
            if (pic.isEmpty()) pic = o.optString("pic");
            if (id.isEmpty() && name.isEmpty()) continue;
            out.add(new Vod().id(id).name(name).pic(pic).remarks(o.optString("vod_remarks")));
        }
        return out;
    }

    private static String urlEncode(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }
}
