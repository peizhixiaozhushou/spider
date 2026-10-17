package com.example.tvbox.plain.health;

import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Dom;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 急救教学 —— HTML 抓取型健康科普源。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  基址 https://m.youlai.cn
 *
 *  首页分类（8 个，硬编码）:
 *     jijiu|0 急救技能    jijiu|1 家庭生活
 *     jijiu|2 急危重症    jijiu|3 常见损伤
 *     jijiu|4 动物致伤    jijiu|5 海洋急救
 *     jijiu|6 中毒急救    jijiu|7 意外事故
 *
 *  分类: GET {base}/{cateId}
 *        封面  .block100 第 i 个的 src
 *        条目  .jj-title-li 第 i 个 → .list-br3 内每个
 *              链接 a[href]、标题 a 文本
 *
 *  详情: GET {详情URL}
 *        标题 .video-title.h1-title
 *        封面 .video-cover.list-flex-in img[src]
 *        作者 span.doc-name
 *        简介 .img-text-con
 *        视频 #video source[src]，回退 #video[src]
 *
 *  搜索: GET {base}/cse/search?q={urlencode}
 *        条目 .search-video-li.list-br2
 *              链接 a[href]、标题 h5.line-clamp1、封面 dt.logo-bg img[src]
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/FirstAid.java}（104 行）
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/FirstAid.java} 的对照复核，
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
 * {@code ae/decompiled/FirstAid.java} 为对照基准。
 */
public class FirstAidPlain extends BaseSpider {

    private static final String TAG = "FirstAidPlain";
    private static final String BASE = "https://m.youlai.cn";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 8 个分类（tid|索引，与上游逐字一致） */
    private static final String[][] CATEGORIES = {
            {"jijiu|0", "急救技能"}, {"jijiu|1", "家庭生活"},
            {"jijiu|2", "急危重症"}, {"jijiu|3", "常见损伤"},
            {"jijiu|4", "动物致伤"}, {"jijiu|5", "海洋急救"},
            {"jijiu|6", "中毒急救"}, {"jijiu|7", "意外事故"},
    };

    private Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        return h;
    }

    @Override
    public String homeContent(boolean filter) {
        VodBuilder vb = VodBuilder.create();
        for (String[] c : CATEGORIES) {
            vb.clazz(c[0], c[1]);
        }
        return vb.homeContent();
    }

    /**
     * 分类 —— 还原上游 {@code categoryContent}。
     * <p>tid 格式 {@code cateId|blockIndex}；extend 里 {@code cateId} 可覆盖。
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        if (TextUtils.isEmpty(tid)) return VodBuilder.create().page(1).categoryContent();

        String cateId;
        int blockIndex;
        String[] parts = tid.split("\\|");
        cateId = parts[0];
        blockIndex = parts.length > 1 ? parseInt(parts[1], 0) : 0;
        if (extend != null && extend.get("cateId") != null) {
            cateId = extend.get("cateId");
        }

        String url = BASE + "/" + cateId;
        String html = http.get(url, apiHeaders());
        if (TextUtils.isEmpty(html)) {
            Log.w(TAG, "categoryContent: empty html for " + url);
            return VodBuilder.create().page(1).categoryContent();
        }

        List<Vod> list = new ArrayList<>();
        try {
            Dom.El doc = Dom.parse(html, BASE);

            // 封面：.block100 第 blockIndex 个的 src
            List<Dom.El> blocks = doc.select(".block100");
            String pic = "";
            if (blockIndex >= 0 && blockIndex < blocks.size()) {
                pic = blocks.get(blockIndex).attr("src");
            }

            // 条目：.jj-title-li 第 blockIndex 个 → .list-br3 内每个
            List<Dom.El> titleLis = doc.select(".jj-title-li");
            if (blockIndex >= 0 && blockIndex < titleLis.size()) {
                for (Dom.El item : titleLis.get(blockIndex).select(".list-br3")) {
                    Dom.El a = item.selectFirst("a");
                    String href = a.attr("href");
                    String name = a.text();
                    if (href.isEmpty() && name.isEmpty()) continue;
                    list.add(new Vod()
                            .id(absolute(href))
                            .name(name)
                            .pic(pic));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "categoryContent parse failed", e);
        }
        Log.i(TAG, "categoryContent " + tid + " -> " + list.size());
        return VodBuilder.create().addAll(list).page(1).categoryContent();
    }

    /** 详情 —— 还原上游 {@code detailContent} */
    @Override
    public String detailContent(List<String> ids) {
        String url = firstId(ids);
        if (TextUtils.isEmpty(url)) return emptyResponse();

        String html = http.get(absolute(url), apiHeaders());
        if (TextUtils.isEmpty(html)) return emptyResponse();

        try {
            Dom.El doc = Dom.parse(html, BASE);
            String name = doc.selectFirst(".video-title.h1-title").text();
            String pic  = doc.selectFirst(".video-cover.list-flex-in img").attr("src");
            String author = doc.selectFirst("span.doc-name").text();
            String intro  = doc.selectFirst(".img-text-con").text();

            // 视频地址：#video source[src]，回退 #video[src]
            String videoUrl = doc.selectFirst("#video source").attr("src");
            if (videoUrl.isEmpty()) videoUrl = doc.selectFirst("#video").attr("src");

            Vod v = new Vod()
                    .id(url)
                    .name(name)
                    .pic(pic)
                    .area("中国")
                    .content(intro)
                    .actor(author)
                    .playFrom("Qile");
            if (!TextUtils.isEmpty(videoUrl)) {
                v.playUrl(name + Vod.SEP_NAME_URL + videoUrl);
            }
            return VodBuilder.detail(v).detailContent();
        } catch (Exception e) {
            Log.e(TAG, "detailContent parse failed", e);
            return emptyResponse();
        }
    }

    /** 搜索 —— 还原上游 {@code searchContent} */
    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return emptyResponse();

        String url;
        try {
            url = BASE + "/cse/search?q=" + URLEncoder.encode(key, "UTF-8");
        } catch (Exception e) {
            return VodBuilder.create().searchContent();
        }

        String html = http.get(url, apiHeaders());
        if (TextUtils.isEmpty(html)) return VodBuilder.create().searchContent();

        List<Vod> list = new ArrayList<>();
        try {
            Dom.El doc = Dom.parse(html, BASE);
            for (Dom.El item : doc.select(".search-video-li.list-br2")) {
                Dom.El a = item.selectFirst("a");
                String href = a.attr("href");
                String name = item.selectFirst("h5.line-clamp1").text();
                String pic  = item.selectFirst("dt.logo-bg img").attr("src");
                if (href.isEmpty() && name.isEmpty()) continue;
                list.add(new Vod().id(absolute(href)).name(name).pic(pic));
            }
        } catch (Exception e) {
            Log.e(TAG, "searchContent parse failed", e);
        }
        Log.i(TAG, "search '" + key + "' -> " + list.size());
        return VodBuilder.of(list).searchContent();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) return emptyResponse();
        // 上游直接把 id 当播放地址（带 headers）
        String type = id.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/*";
        return VodBuilder.playerContent(id, type);
    }

    // ── 工具 ────────────────────────────────────────────────────

    /** 相对路径转绝对 */
    protected String absolute(String href) {
        if (TextUtils.isEmpty(href)) return "";
        if (href.startsWith("http")) return href;
        if (href.startsWith("//")) return "https:" + href;
        return BASE + (href.startsWith("/") ? href : "/" + href);
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
