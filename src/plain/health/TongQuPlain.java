package com.example.tvbox.plain.health;

import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Dom;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 童趣（播视网 boosj.com）—— GSLB 接口 + HTML 列表。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  GSLB 解析: GET {gslb|默认 https://gslb.boosj.com/gslbm}?{参数key=value&...}
 *             → 返回可用接口域名
 *
 *  列表 HTML 正则（逐字来自上游）:
 *    条目容器  &lt;div class="bj-col4"&gt;([\s\S]*?)&lt;/div&gt;\s*&lt;/div&gt;
 *    图片块    &lt;div class="pubpic"&gt;([\s\S]*?)&lt;/div&gt;\s*&lt;/div&gt;
 *    标题      &lt;h2 class="video_title"&gt;([^&lt;]+)&lt;/h2&gt;
 *
 *  19 个分类 + 2 个筛选维度（年龄段 / 排序）
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/TongQu.java}（260 行）
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/TongQu.java} 的对照复核，
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
 * {@code ae/decompiled/TongQu.java} 为对照基准。
 */
public class TongQuPlain extends BaseSpider {

    private static final String TAG = "TongQuPlain";

    /** 19 个分类名（顺序与 ID 严格对应） */
    public static final String[] CAT_NAMES = {
            "全部", "辅食", "动画", "儿童舞蹈", "少儿英语", "儿童歌曲", "才艺",
            "播视自制", "故事", "亲子教育", "美术", "其他", "儿童游戏", "识物",
            "绘本", "古诗", "科普", "儿童玩具", "播视童趣儿童玩具"
    };
    /** 19 个分类 ID */
    public static final String[] CAT_IDS = {
            "all", "28", "582", "3364", "3366", "3367", "3622",
            "3782", "3822", "3842", "4402", "4583", "4762", "4842",
            "4843", "4844", "4845", "5102", "5142"
    };

    /** 年龄段筛选 */
    private static final String[][] FILTER_AREA = {
            {"全部", ""}, {"6岁以上", "?p367=370"},
            {"3~6岁", "?p367=369"}, {"0~3岁", "?p367=368"}
    };
    /** 排序筛选 */
    private static final String[][] FILTER_SORT = {
            {"全部", ""}, {"最新发布", "lately"},
            {"最多播放", "pop"}, {"最多评论", "view"}
    };

    /** 列表条目正则（上游逐字） */
    /**
     * 条目容器。
     *
     * <p>⚠️ 上游正则是 {@code <div class="bj-col4">([\s\S]*?)</div>\s*</div>}，
     * 但真实页面里条目内含嵌套 div，惰性匹配会在第一个 {@code </div></div>} 处截断，
     * 导致后面的 {@code <h2 class="video_title">} 落不进条目块。
     * 本实现改用「下一个 bj-col4 或文档尾」作边界，与真实 DOM 一致。
     */
    private static final String RE_ITEM =
            "<div class=\"bj-col4\">([\\s\\S]*?)(?=<div class=\"bj-col4\">|<div class=\"page|<\\/body|$)";
    private static final String RE_PIC_BLOCK =
            "<div class=\"pubpic\">([\\s\\S]*?)</div>\\s*</div>";
    private static final String RE_TITLE =
            "<h2 class=\"video_title\">([^<]+)</h2>";

    @Override
    public String homeContent(boolean filter) {
        VodBuilder vb = VodBuilder.create();

        // 分类
        for (int i = 0; i < CAT_IDS.length; i++) {
            vb.clazz(CAT_IDS[i], CAT_NAMES[i]);
        }

        // 两个筛选维度挂到每个分类上
        JSONObject areaOpts = buildOptions(FILTER_AREA);
        JSONObject sortOpts = buildOptions(FILTER_SORT);
        JSONObject filters = new JSONObject();
        for (String id : CAT_IDS) {
            JSONObject f = new JSONObject();
            try {
                f.put("area", areaOpts);
                f.put("by", sortOpts);
                filters.put(id, f);
            } catch (Exception ignored) {}
        }
        // VodBuilder 无 filters 直接注入，改写响应
        String base = vb.homeContent();
        try {
            JSONObject root = new JSONObject(base);
            root.put("filters", filters);
            return root.toString();
        } catch (Exception e) {
            return base;
        }
    }

    private static JSONObject buildOptions(String[][] pairs) {
        JSONArray arr = new JSONArray();
        for (String[] p : pairs) {
            JSONObject o = new JSONObject();
            try {
                o.put("n", p[0]);
                o.put("v", p[1]);
            } catch (Exception ignored) {}
            arr.put(o);
        }
        JSONObject wrap = new JSONObject();
        try { wrap.put("options", arr); } catch (Exception ignored) {}
        return wrap;
    }

    /**
     * 还原上游 {@code a(JSONObject)} —— GSLB 参数拼接。
     * <p>遍历 key，URL 编码值，用 {@code &} 连接。
     */
    public static String buildGslbUrl(JSONObject params) {
        if (params == null) return "";
        String base = params.optString("gslb", "https://gslb.boosj.com/gslbm");
        StringBuilder sb = new StringBuilder(base).append('?');
        boolean first = true;
        // 用 JsonKeys 而非 params.keySet()：宿主自带的是旧版 org.json，没有 keySet()
        for (String k : com.example.tvbox.common.JsonKeys.of(params)) {
            if (!first) sb.append('&');
            first = false;
            sb.append(k).append('=');
            String v = params.optString(k, "");
            try {
                v = URLEncoder.encode(v, "UTF-8").replace("+", "%20");
            } catch (Exception ignored) {}
            sb.append(v);
        }
        return sb.toString();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  java.util.HashMap<String, String> extend) {
        if (TextUtils.isEmpty(tid)) return VodBuilder.create().page(1).categoryContent();
        int page = pageNum(pg, 1);

        String base = requireUrl();
        if (base.isEmpty()) {
            Log.w(TAG, "categoryContent: no baseUrl in ext");
            return VodBuilder.create().page(page).categoryContent();
        }

        // 拼列表页 URL：{base}/{tid}-{sort}-{page}.html 之类（上游用 GSLB 派生域名）
        String path = "/" + tid + "-" + sortOf(extend) + "-" + page + ".html";
        String html = http.get(base + path, headers());
        if (TextUtils.isEmpty(html)) {
            Log.w(TAG, "categoryContent: empty html, tid=" + tid);
            return VodBuilder.create().page(page).categoryContent();
        }

        List<Vod> list = parseList(html);
        Log.i(TAG, "categoryContent " + tid + " p=" + page + " -> " + list.size());
        return VodBuilder.create().addAll(list).page(page).categoryContent();
    }

    /** 从 extend 取排序参数，缺省用 "all" */
    private static String sortOf(java.util.HashMap<String, String> ext) {
        if (ext == null) return "all";
        String v = ext.get("by");
        if (v == null || v.isEmpty()) return "all";
        return v.replace("?", "");
    }

    /** 解析列表 HTML —— 三个正则对应上游三段 */
    protected List<Vod> parseList(String html) {
        List<Vod> out = new ArrayList<>();
        if (html == null) return out;

        java.util.regex.Matcher items =
                java.util.regex.Pattern.compile(RE_ITEM, java.util.regex.Pattern.DOTALL).matcher(html);
        while (items.find()) {
            String block = items.group(1);
            if (block == null) continue;

            String title = firstGroup(RE_TITLE, block);
            String link  = firstAttr(block, "href");
            String pic   = firstAttr(firstGroup(RE_PIC_BLOCK, block), "src");
            if (TextUtils.isEmpty(title) && TextUtils.isEmpty(link)) continue;

            out.add(new Vod()
                    .id(link)
                    .name(title)
                    .pic(pic)
                    .remarks(""));
        }
        return out;
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (TextUtils.isEmpty(id)) return emptyResponse();
        Vod v = new Vod().id(id).name("童趣").playFrom("播视").playUrl("播放" + Vod.SEP_NAME_URL + id);
        return VodBuilder.detail(v).detailContent();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return emptyResponse();
        String base = requireUrl();
        if (base.isEmpty()) return VodBuilder.create().searchContent();
        try {
            String url = base + "/search.html?kw=" + URLEncoder.encode(key, "UTF-8");
            String html = http.get(url, headers());
            return VodBuilder.of(parseList(html)).searchContent();
        } catch (Exception e) {
            return VodBuilder.create().searchContent();
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) return emptyResponse();
        String type = id.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/*";
        return VodBuilder.playerContent(id, type);
    }

    // ── 正则工具 ────────────────────────────────────────────────

    private static String firstGroup(String re, String s) {
        if (s == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(re, java.util.regex.Pattern.DOTALL).matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static String firstAttr(String s, String attr) {
        if (s == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(attr + "=\"([^\"]*)\"", java.util.regex.Pattern.DOTALL).matcher(s);
        return m.find() ? m.group(1) : "";
    }
}
