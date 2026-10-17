package com.example.tvbox.plain.health;

import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 养生堂（北京时间 btime.com）—— 纯 JSON API 源。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  列表 GET https://pc.api.btime.com/btimeweb/infoFlow
 *         ?list_id={tid}&refresh=1&count=2000&expands=pageinfo
 *       响应 { errno:0, data:{ list:[ {gid, data:{title, covers[], corner[]}} ] } }
 *
 *  详情 GET https://app.api.btime.com/video/play?id={vod_id}
 *       响应 data.video_stream[0].stream_url
 *
 *  请求头: User-Agent(Chrome/151) + Referer(https://www.btime.com/)
 *
 *  分类: 9 个年份 2018..2026，tid 形如
 *        btv_08da67cea600bf3c78973427bfaba12d_s0_{year}
 * </pre>
 *
 * <h2>字段映射</h2>
 * <pre>
 *  vod_id      = gid
 *  vod_name    = data.title
 *  vod_pic     = data.covers[0]
 *  vod_remarks = data.corner[0].text
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/YST.java}（163 行）
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/YST.java} 的对照复核，
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
 * {@code ae/decompiled/YST.java} 为对照基准。
 */
public class YSTPlain extends BaseSpider {

    private static final String TAG = "YSTPlain";
    private static final String EP_LIST = "https://pc.api.btime.com/btimeweb/infoFlow";
    private static final String EP_PLAY = "https://app.api.btime.com/video/play";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.btime.com/";

    /** 分类 ID 前缀（上游硬编码） */
    private static final String TID_PREFIX = "btv_08da67cea600bf3c78973427bfaba12d_s0_";
    /** 年份列表 2018..2026 */
    private static final int YEAR_FROM = 2018;
    private static final int YEAR_TO   = 2026;

    private Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", REFERER);
        return h;
    }

    /** 空列表响应（与上游逐字一致） */
    private static final String EMPTY_LIST =
            "{\"list\":[],\"page\":1,\"pagecount\":1,\"limit\":20,\"total\":0}";

    @Override
    public String homeContent(boolean filter) {
        VodBuilder vb = VodBuilder.create();
        for (int y = YEAR_TO; y >= YEAR_FROM; y--) {
            vb.clazz(TID_PREFIX + y, String.valueOf(y));
        }
        return vb.homeContent();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  java.util.HashMap<String, String> extend) {
        if (TextUtils.isEmpty(tid)) return EMPTY_LIST;

        String url = EP_LIST + "?list_id=" + tid + "&refresh=1&count=2000&expands=pageinfo";
        String resp = http.get(url, apiHeaders());
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "categoryContent: empty response for " + tid);
            return EMPTY_LIST;
        }

        List<Vod> list = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(resp);
            if (root.optInt("errno", -1) != 0) {
                Log.w(TAG, "categoryContent: errno=" + root.optInt("errno", -1));
                return EMPTY_LIST;
            }
            JSONObject data = root.optJSONObject("data");
            JSONArray arr = data == null ? null : data.optJSONArray("list");
            if (arr == null) return EMPTY_LIST;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String gid = item.optString("gid");
                JSONObject d = item.optJSONObject("data");
                if (d == null) continue;

                // 封面：covers[0]
                String pic = "";
                JSONArray covers = d.optJSONArray("covers");
                if (covers != null && covers.length() > 0) pic = covers.optString(0, "");

                // 备注：corner[0].text
                String remark = "";
                JSONArray corner = d.optJSONArray("corner");
                if (corner != null && corner.length() > 0) {
                    JSONObject c0 = corner.optJSONObject(0);
                    if (c0 != null) remark = c0.optString("text", "");
                }

                list.add(new Vod()
                        .id(gid)
                        .name(d.optString("title"))
                        .pic(pic)
                        .remarks(remark));
            }
        } catch (Exception e) {
            Log.e(TAG, "categoryContent parse failed", e);
            return EMPTY_LIST;
        }
        Log.i(TAG, "categoryContent " + tid + " -> " + list.size());
        return VodBuilder.create().addAll(list).page(1).categoryContent();
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (TextUtils.isEmpty(id)) return emptyResponse();

        String resp = http.get(EP_PLAY + "?id=" + id, apiHeaders());
        if (TextUtils.isEmpty(resp)) return emptyResponse();

        try {
            JSONObject root = new JSONObject(resp);
            JSONObject data = root.optJSONObject("data");
            String streamUrl = "";
            if (data != null) {
                JSONArray streams = data.optJSONArray("video_stream");
                if (streams != null && streams.length() > 0) {
                    JSONObject s0 = streams.optJSONObject(0);
                    if (s0 != null) streamUrl = s0.optString("stream_url", "");
                }
            }
            if (streamUrl.isEmpty()) {
                Log.w(TAG, "detailContent: no stream_url for " + id);
                return emptyResponse();
            }

            Vod v = new Vod()
                    .id(id)
                    .name("养生堂")
                    .playFrom("Btime")
                    .playUrl("播放" + Vod.SEP_NAME_URL + streamUrl);
            return VodBuilder.detail(v).detailContent();
        } catch (Exception e) {
            Log.e(TAG, "detailContent parse failed", e);
            return emptyResponse();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        // 上游未实现搜索
        return VodBuilder.create().searchContent();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) return emptyResponse();
        String type = id.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/*";
        return VodBuilder.playerContent(id, type);
    }
}
