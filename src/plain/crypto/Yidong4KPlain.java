package com.example.tvbox.plain.crypto;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
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
 * 移动4K（vidsite）—— 固定请求头 + JSON API 源。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  基址   https://vidsite.cloudseekr.com
 *  首页   POST /vidsite/vs/qryPageColumnVideo
 *  分类   POST /vidsite/vs/qryPageColumnVideo   （带 columnId / pageNo）
 *  搜索   POST /vidsite/vs/qryPageColumnVideo   （带 keyword）
 *  详情   POST /vidsite/vs/qryVideoDetail
 *  埋点   POST /vidsite/vs/addAccessRecord      （详情前调用，失败则放弃详情）
 *
 *  固定请求头（11 项）:
 *     Origin / Accept / Cache-Control / User-Agent / Referer / Pragma
 *     Content-Type / Host / Connection / Accept-Encoding
 *
 *  响应字段: code（"000000" 为成功）/ title / appCoverUrl / remark / resUrl
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Yidong4K.java}（212 行）
 
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>"埋点失败就不给详情"</b>：上游 {@code detailContent} 先 POST
 *       {@code /vidsite/vs/addAccessRecord}，只有它返回 {@code code=="000000"} 才继续取详情；
 *       埋点接口抖动会直接导致详情打不开。本类按原样保留（属上游行为），
 *       但这意味着"纯展示"也被埋点强耦合。</li>
 *   <li><b>业务码语义非标准</b>：成功码是字符串 {@code "000000"}，
 *       {@code code} 为数字或 {@code "0"} 都会被判失败（上游同样）。</li>
 *   <li><b>未还原</b>：11 个固定请求头里的 {@code Referer/Origin} 站点形态、
 *       以及 {@code columnId} 的真实取值范围（搜索用 0 是硬编码）。</li>
 * </ol>
 *
 * <h2>记录的上游缺陷 / 硬编码异味</h2>
 * <ol>
 *   <li><b>分类 id 直接 {@code Integer.parseInt}</b>：宿主传空串/非数字 tid 时 NFE；
 *       本类改走"非数字按 0/原样透传"的容错路径（见实现与测试）。</li>
 *   <li><b>三个不同语义的接口共用一个路径</b>（{@code qryPageColumnVideo} 同时承担
 *       首页/分类/搜索），靠请求体字段区分 —— 一旦服务端拆分接口，客户端版本就废。</li>
 *   <li><b>埋点前置阻塞主流程</b>（见上）。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/Yidong4K.java}（212 行；逐行核对
 * {@code qryPageColumnVideo}/{@code qryVideoDetail}/{@code addAccessRecord} 三处调用
 * 与 {@code code.equals("000000")} 的判定）。
*/
public class Yidong4KPlain extends BaseSpider {

    private static final String TAG = "Yidong4KPlain";

    private static final String BASE = "https://vidsite.cloudseekr.com";
    private static final String EP_LIST   = "/vidsite/vs/qryPageColumnVideo";
    private static final String EP_DETAIL = "/vidsite/vs/qryVideoDetail";
    private static final String EP_ACCESS = "/vidsite/vs/addAccessRecord";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 成功码 */
    private static final String OK_CODE = "000000";

    private static final int PAGE_SIZE = 20;

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        Log.i(TAG, "init, base=" + baseUrl);
    }

    /** 还原上游 {@code f()} —— 11 项固定请求头 */
    protected Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Origin", BASE);
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Cache-Control", "no-cache");
        h.put("User-Agent", UA);
        h.put("Referer", BASE + "/vid/app/");
        h.put("Pragma", "no-cache");
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("Host", "vidsite.cloudseekr.com");
        h.put("Connection", "Keep-Alive");
        h.put("Accept-Encoding", "gzip");
        return h;
    }

    protected String post(String path, JSONObject body) {
        String resp = http.post(BASE + path, body == null ? "{}" : body.toString(), apiHeaders());
        return TextUtils.isEmpty(resp) ? "" : resp;
    }

    /** 是否成功响应 */
    protected boolean isOk(JSONObject root) {
        return root != null && OK_CODE.equals(root.optString("code"));
    }

    @Override
    public String homeContent(boolean filter) {
        JSONObject body = new JSONObject();
        try {
            body.put("pageNo", 1);
            body.put("pageSize", PAGE_SIZE);
        } catch (Exception ignored) {}

        String resp = post(EP_LIST, body);
        if (resp.isEmpty()) return emptyResponse();

        List<Vod> list;
        Map<String, String> classes = new LinkedHashMap<>();
        try {
            JSONObject root = new JSONObject(resp);
            if (!isOk(root)) {
                Log.w(TAG, "homeContent: code=" + root.optString("code"));
                return emptyResponse();
            }
            JSONObject data = root.optJSONObject("data");
            list = parseList(data);

            // 分类来自 columnList（若存在）
            JSONArray cols = data == null ? null : data.optJSONArray("columnList");
            if (cols != null) {
                for (int i = 0; i < cols.length(); i++) {
                    JSONObject c = cols.optJSONObject(i);
                    if (c == null) continue;
                    String id = c.optString("columnId");
                    String name = c.optString("columnName");
                    if (!id.isEmpty() && !name.isEmpty()) classes.put(id, name);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "homeContent parse failed", e);
            return emptyResponse();
        }

        VodBuilder vb = VodBuilder.create().addAll(list);
        for (Map.Entry<String, String> e : classes.entrySet()) {
            vb.clazz(e.getKey(), e.getValue());
        }
        return vb.homeContent();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        JSONObject body = new JSONObject();
        try {
            body.put("columnId", tid);
            body.put("pageNo", page);
            body.put("pageSize", PAGE_SIZE);
        } catch (Exception ignored) {}

        String resp = post(EP_LIST, body);
        if (resp.isEmpty()) return VodBuilder.create().page(page).categoryContent();

        try {
            JSONObject root = new JSONObject(resp);
            if (!isOk(root)) return VodBuilder.create().page(page).categoryContent();
            List<Vod> list = parseList(root.optJSONObject("data"));
            return VodBuilder.create().addAll(list).page(page).categoryContent();
        } catch (Exception e) {
            Log.e(TAG, "categoryContent parse failed", e);
            return VodBuilder.create().page(page).categoryContent();
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (id.isEmpty()) return emptyResponse();

        // 上游先打埋点，失败则放弃详情
        JSONObject access = new JSONObject();
        try { access.put("videoId", id); } catch (Exception ignored) {}
        String accResp = post(EP_ACCESS, access);
        if (!accResp.isEmpty()) {
            try {
                if (!isOk(new JSONObject(accResp))) {
                    Log.w(TAG, "detailContent: addAccessRecord rejected, abort");
                    return emptyResponse();
                }
            } catch (Exception ignored) {}
        }

        JSONObject body = new JSONObject();
        try { body.put("videoId", id); } catch (Exception ignored) {}
        String resp = post(EP_DETAIL, body);
        if (resp.isEmpty()) return emptyResponse();

        try {
            JSONObject root = new JSONObject(resp);
            if (!isOk(root)) return emptyResponse();
            JSONObject d = root.optJSONObject("data");
            if (d == null) d = root.optJSONObject("video");
            if (d == null) return emptyResponse();

            String resUrl = d.optString("resUrl");
            Vod vod = new Vod()
                    .id(id)
                    .name(d.optString("title"))
                    .pic(d.optString("appCoverUrl"))
                    .remarks(d.optString("remark"));
            if (!resUrl.isEmpty()) {
                vod.playFrom("移动4K").playUrl("播放$" + resUrl);
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
            body.put("pageNo", 1);
            body.put("pageSize", PAGE_SIZE);
        } catch (Exception ignored) {}

        String resp = post(EP_LIST, body);
        if (resp.isEmpty()) return VodBuilder.create().searchContent();

        try {
            JSONObject root = new JSONObject(resp);
            if (!isOk(root)) return VodBuilder.create().searchContent();
            return VodBuilder.of(parseList(root.optJSONObject("data"))).searchContent();
        } catch (Exception e) {
            Log.e(TAG, "searchContent parse failed", e);
            return VodBuilder.create().searchContent();
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) return emptyResponse();
        String type = id.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/*";
        return VodBuilder.playerContent(id, type);
    }

    /** 从 data 取列表 —— 容错多种字段名 */
    protected List<Vod> parseList(JSONObject data) {
        List<Vod> out = new ArrayList<>();
        if (data == null) return out;
        JSONArray arr = data.optJSONArray("records");
        if (arr == null) arr = data.optJSONArray("list");
        if (arr == null) arr = data.optJSONArray("rows");
        if (arr == null) return out;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("videoId");
            if (id.isEmpty()) id = o.optString("id");
            out.add(new Vod()
                    .id(id)
                    .name(o.optString("title"))
                    .pic(o.optString("appCoverUrl"))
                    .remarks(o.optString("remark")));
        }
        return out;
    }
}
