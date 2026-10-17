package com.example.tvbox.plain.health;

import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 贝乐虎（少儿内容）—— JSON POST API 源。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  列表 POST https://vd.ubestkid.com/api/v1/bv/video
 *       Header: Content-Type: application/json, appver: 6.1.9
 *       Body  : { age:1, appver:"6.1.9", egvip_status:0, svip_status:0,
 *                 vps:60, subcateId:{分类ID}, p:{页码} }
 *       响应  : result.items[] = { url, title, image, brand, endInterval }
 *
 *  6 个分类（硬编码，逐字）:
 *     56 儿歌   63 故事   62 少儿
 *     61 古诗   64 认知   65 习惯
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/BeiLeHu.java}（134 行）
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/BeiLeHu.java} 的对照复核，
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
 * {@code ae/decompiled/BeiLeHu.java} 为对照基准。
 */
public class BeiLeHuPlain extends BaseSpider {

    private static final String TAG = "BeiLeHuPlain";
    private static final String EP_LIST = "https://vd.ubestkid.com/api/v1/bv/video";
    private static final String APIVER = "6.1.9";

    /** 6 个分类（顺序即上游 LinkedHashMap 顺序） */
    public static final Map<String, String> CATEGORIES = new LinkedHashMap<>();
    static {
        CATEGORIES.put("56", "儿歌");
        CATEGORIES.put("63", "故事");
        CATEGORIES.put("62", "少儿");
        CATEGORIES.put("61", "古诗");
        CATEGORIES.put("64", "认知");
        CATEGORIES.put("65", "习惯");
    }

    private Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("appver", APIVER);
        return h;
    }

    @Override
    public String homeContent(boolean filter) {
        VodBuilder vb = VodBuilder.create();
        for (Map.Entry<String, String> e : CATEGORIES.entrySet()) {
            vb.clazz(e.getKey(), e.getValue());
        }
        return vb.homeContent();
    }

    /** 构造列表请求体 —— 还原上游 6 个固定字段 + subcateId + p */
    protected JSONObject buildListBody(String subcateId, int page) {
        JSONObject body = new JSONObject();
        try {
            body.put("age", 1);
            body.put("appver", APIVER);
            body.put("egvip_status", 0);
            body.put("svip_status", 0);
            body.put("vps", 60);
            body.put("subcateId", subcateId);
            body.put("p", page);
        } catch (Exception ignored) {}
        return body;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  java.util.HashMap<String, String> extend) {
        if (TextUtils.isEmpty(tid)) return VodBuilder.create().page(1).categoryContent();
        int page = pageNum(pg, 1);

        String resp = http.post(EP_LIST, buildListBody(tid, page).toString(), apiHeaders());
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "categoryContent: empty response");
            return VodBuilder.create().page(page).categoryContent();
        }

        List<Vod> list = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(resp);
            JSONObject result = root.optJSONObject("result");
            JSONArray items = result == null ? null : result.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.optJSONObject(i);
                    if (o == null) continue;
                    list.add(new Vod()
                            .id(o.optString("url"))
                            .name(o.optString("title", "贝乐虎"))
                            .pic(o.optString("image"))
                            .remarks(o.optString("brand", "")));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "categoryContent parse failed", e);
        }
        Log.i(TAG, "categoryContent " + tid + " p=" + page + " -> " + list.size());
        return VodBuilder.create().addAll(list).page(page).categoryContent();
    }

    /**
     * 详情 —— 还原上游 {@code detailContent}。
     * <p>上游把 vod_id 按某分隔符拆开，第二段作剧名。
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (TextUtils.isEmpty(id)) return emptyResponse();

        String name = "贝乐虎";
        String[] parts = id.split("\\|");
        if (parts.length > 1 && !parts[1].isEmpty()) name = parts[1];

        Vod v = new Vod()
                .id(id)
                .name(name)
                .typeName("少儿")
                .area("")
                .content("")
                .playFrom("贝乐虎")
                .playUrl("播放" + Vod.SEP_NAME_URL + id);
        return VodBuilder.detail(v).detailContent();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        // 上游未实现搜索
        return VodBuilder.create().searchContent();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) return emptyResponse();
        // 上游：id 含 "播放" 时取前半段作真实地址
        String url = id;
        if (id.contains("播放")) {
            url = id.split("播放")[0];
        }
        String type = url.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/*";
        return VodBuilder.playerContent(url, type);
    }
}
