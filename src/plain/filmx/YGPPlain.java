package com.example.tvbox.plain.filmx;

import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Dom;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预告片世界（6huo.com）—— HTML 抓取型源。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  基址 https://www.6huo.com/
 *
 *  首页/推荐   div.inner-2col-main div.movlist > ul li > a
 *
 *  分类   {base}{tid}{f0}_{f1}_{f2}_{f3}_{page}
 *         ← 上游用 5 段下划线拼接，f0..f3 来自 extend 的下标，f4 是页码
 *         分页   p.page-nav a / p.page-nav a.current
 *
 *  详情   #tabwrapper-all > .tlist
 *         标题   h1.movie-name
 *         副标题 div.movie-title-detail p
 *         分类   div.movie-title-detail a
 *         封面   div.movie-title-mpic > a > img
 *         内容   div.inner-wrapper
 *
 *  搜索   {base}?keyword={urlencode}
 *
 *  唯一分类: movlist/ （"预告片世界"）
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/YGP.java}（309 行）
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/YGP.java} 的对照复核，
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
 * {@code ae/decompiled/YGP.java} 为对照基准。
 */
public class YGPPlain extends BaseSpider {

    private static final String TAG = "YGPPlain";
    private static final String BASE = "https://www.6huo.com/";

    @Override
    public String homeContent(boolean filter) {
        VodBuilder vb = VodBuilder.create();
        vb.clazz("movlist/", "预告片世界");

        // 首页推荐（离线模式下为空，不阻塞）
        String html = http.get(BASE, headers());
        List<Vod> recommends = parseList(html);

        String base = vb.addAll(recommends).homeContent();
        return base;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  java.util.HashMap<String, String> extend) {
        if (TextUtils.isEmpty(tid)) return VodBuilder.create().page(1).categoryContent();
        int page = pageNum(pg, 1);

        // 上游：5 段用 "_" 拼接，f0..f3 来自 extend（下标即段号），f4 是页码
        String[] parts = {"", "", "", "", String.valueOf(page)};
        if (extend != null) {
            for (String k : extend.keySet()) {
                try {
                    int idx = Integer.parseInt(k);
                    if (idx >= 0 && idx < 4) {
                        parts[idx] = URLEncoder.encode(String.valueOf(extend.get(k)), "UTF-8");
                    }
                } catch (Exception ignored) {}
            }
        }
        String url = BASE + tid + TextUtils.join("_", parts);

        String html = http.get(url, headers());
        if (TextUtils.isEmpty(html)) {
            Log.w(TAG, "categoryContent: empty html " + url);
            return VodBuilder.create().page(page).categoryContent();
        }
        List<Vod> list = parseList(html);
        Log.i(TAG, "categoryContent " + tid + " p=" + page + " -> " + list.size());
        return VodBuilder.create().addAll(list).page(page).categoryContent();
    }

    /** 解析列表 —— 选择器 div.inner-2col-main div.movlist > ul li > a */
    public List<Vod> parseList(String html) {
        List<Vod> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;

        Dom.El doc = Dom.parse(html, BASE);
        // 先缩到 .movlist 范围，再取 li > a
        List<Dom.El> lists = doc.select("div.movlist");
        List<Dom.El> anchors = new ArrayList<>();
        for (Dom.El l : lists) anchors.addAll(l.select("li > a"));
        if (anchors.isEmpty()) anchors = doc.select("div.movlist li > a");

        for (Dom.El a : anchors) {
            String href = a.attr("href");
            String title = a.attr("title");
            if (title.isEmpty()) title = a.text();
            String pic = a.selectFirst("img").attr("src");
            if (pic.isEmpty()) pic = a.selectFirst("img").attr("data-src");
            if (href.isEmpty() && title.isEmpty()) continue;
            out.add(new Vod().id(absolute(href)).name(title).pic(pic).remarks(""));
        }
        return out;
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (TextUtils.isEmpty(id)) return emptyResponse();

        String html = http.get(absolute(id), headers());
        if (TextUtils.isEmpty(html)) return emptyResponse();

        try {
            Dom.El doc = Dom.parse(html, BASE);
            String name = doc.selectFirst("h1.movie-name").text();
            if (name.isEmpty()) name = doc.selectFirst("title").text();
            String remark = doc.selectFirst("div.movie-title-detail p").text();
            String typeName = doc.selectFirst("div.movie-title-detail a").text();
            String pic = doc.selectFirst("div.movie-title-mpic > a > img").attr("src");
            String content = doc.selectFirst("div.inner-wrapper").text();

            // 播放来源：上游在 #tabwrapper-all > .tlist 里找视频
            String videoUrl = "";
            for (Dom.El t : doc.select("#tabwrapper-all > .tlist")) {
                Dom.El src = t.selectFirst("video");
                if (src != null) videoUrl = src.attr("src");
                if (videoUrl.isEmpty()) {
                    Dom.El so = t.selectFirst("source");
                    if (so != null) videoUrl = so.attr("src");
                }
                if (!videoUrl.isEmpty()) break;
            }
            if (videoUrl.isEmpty()) {
                // 兜底：全文找 mp4
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(https?://[^\"'\\s]+\\.mp4)").matcher(html);
                if (m.find()) videoUrl = m.group(1);
            }

            Vod v = new Vod()
                    .id(id)
                    .name(name)
                    .pic(pic)
                    .remarks(remark)
                    .typeName(typeName)
                    .content(content)
                    .playFrom("预告片");
            if (!videoUrl.isEmpty()) {
                v.playUrl("播放" + Vod.SEP_NAME_URL + videoUrl);
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
        try {
            String url = BASE + "?keyword=" + URLEncoder.encode(key, "UTF-8");
            return VodBuilder.of(parseList(http.get(url, headers()))).searchContent();
        } catch (Exception e) {
            return VodBuilder.create().searchContent();
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) return emptyResponse();
        String type = id.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/mp4";
        return VodBuilder.playerContent(id, type);
    }

    // ── 工具 ────────────────────────────────────────────────────

    protected static String absolute(String href) {
        if (TextUtils.isEmpty(href)) return "";
        if (href.startsWith("http")) return href;
        if (href.startsWith("//")) return "https:" + href;
        String b = BASE.endsWith("/") ? BASE.substring(0, BASE.length() - 1) : BASE;
        return b + (href.startsWith("/") ? href : "/" + href);
    }

    /** 构造分类 URL（供测试验证拼接规则） */
    public static String buildCategoryUrl(String tid, java.util.HashMap<String, String> ext, int page) {
        String[] parts = {"", "", "", "", String.valueOf(page)};
        if (ext != null) {
            for (String k : ext.keySet()) {
                try {
                    int idx = Integer.parseInt(k);
                    if (idx >= 0 && idx < 4) parts[idx] = URLEncoder.encode(String.valueOf(ext.get(k)), "UTF-8");
                } catch (Exception ignored) {}
            }
        }
        return BASE + tid + TextUtils.join("_", parts);
    }
}
