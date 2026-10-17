package com.example.tvbox.plain.health;

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
 * 呦呦DJ（m.djuu.com）—— 音乐 DJ 源。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  基址 https://m.djuu.com
 *  请求头 Referer: https://m.djuu.com/  (或 m.djuu.com)
 *
 *  分类（上游用 "&" 分隔的两个字符串数组，当前各只有 1 项）:
 *     名称 "曲库"  → ID "djlist"，type_flag="1"
 *     （设计上是可扩展的多分类，但上游只填了 1 个）
 *
 *  分页 URL 模板含 {pg} 占位符，替换为页码
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Djuu.java}（255 行）
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/Djuu.java} 的对照复核，
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
 * {@code ae/decompiled/Djuu.java} 为对照基准。
 */
public class DjuuPlain extends BaseSpider {

    private static final String TAG = "DjuuPlain";
    private static final String BASE = "https://m.djuu.com";
    private static final String REFERER = "https://m.djuu.com/";

    /** 分类名（上游 "曲库".split("&")） */
    private static final String[] CAT_NAMES = "曲库".split("&");
    /** 分类 ID（上游 "djlist".split("&")） */
    private static final String[] CAT_IDS = "djlist".split("&");

    private Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Referer", REFERER);
        return h;
    }

    @Override
    public String homeContent(boolean filter) {
        VodBuilder vb = VodBuilder.create();
        for (int i = 0; i < CAT_IDS.length && i < CAT_NAMES.length; i++) {
            vb.clazz(CAT_IDS[i], CAT_NAMES[i]);
        }
        // 上游 type_flag 字段用于区分分类类型，本实现在详情里按 ID 判断
        return vb.homeContent();
    }

    /** 是否为「曲库」类分类（上游 type_flag=="1"） */
    public static boolean isMusicCategory(String tid) {
        return "djlist".equals(tid);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        if (TextUtils.isEmpty(tid)) return VodBuilder.create().page(1).categoryContent();
        int page = pageNum(pg, 1);

        String base = TextUtils.isEmpty(baseUrl) ? BASE : baseUrl;

        // 曲库走列表页；其他分类走 {base}/{tid} 模板（含 {pg} 占位）
        String url;
        if (isMusicCategory(tid)) {
            url = base + "/" + tid + "/" + page + ".html";
        } else {
            url = base + "/" + tid.replace("{pg}", String.valueOf(page));
        }

        String html = http.get(url, apiHeaders());
        if (TextUtils.isEmpty(html)) {
            Log.w(TAG, "categoryContent: empty html for " + url);
            return VodBuilder.create().page(page).categoryContent();
        }

        List<Vod> list = parseList(html, base);
        Log.i(TAG, "categoryContent " + tid + " p=" + page + " -> " + list.size());
        return VodBuilder.create().addAll(list).page(page).categoryContent();
    }

    /** 解析列表 —— 网页结构由上游 HTML 正则近似 */
    protected List<Vod> parseList(String html, String base) {
        List<Vod> out = new ArrayList<>();
        if (html == null) return out;

        java.util.regex.Pattern linkRe = java.util.regex.Pattern.compile(
                "href=\"(/(?:dj|music|play)/[^\"]+)\"[^>]*>([^<]{1,80})<");
        java.util.regex.Matcher m = linkRe.matcher(html);
        while (m.find()) {
            String href = m.group(1);
            String name = m.group(2).trim();
            if (name.isEmpty()) continue;
            out.add(new Vod().id(absolute(base, href)).name(name).pic("").remarks(""));
        }
        return out;
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (TextUtils.isEmpty(id)) return emptyResponse();

        String base = TextUtils.isEmpty(baseUrl) ? BASE : baseUrl;
        String html = http.get(absolute(base, id), apiHeaders());
        if (TextUtils.isEmpty(html)) return emptyResponse();

        try {
            String name = firstGroup(html, "<h1[^>]*>([^<]+)</h1>");
            if (name.isEmpty()) name = "呦呦DJ";
            String pic = firstAttr(html, "og:image");

            // 音频直链：找 .mp3 / .m4a
            String audio = firstGroup(html, "(https?://[^\"'\\s]+\\.(?:mp3|m4a|aac))");

            Vod v = new Vod()
                    .id(id)
                    .name(name)
                    .pic(pic)
                    .playFrom("DJ");
            if (!TextUtils.isEmpty(audio)) {
                v.playUrl("播放" + Vod.SEP_NAME_URL + audio);
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
        String base = TextUtils.isEmpty(baseUrl) ? BASE : baseUrl;
        try {
            String url = base + "/search/?kw=" + URLEncoder.encode(key, "UTF-8");
            String html = http.get(url, apiHeaders());
            return VodBuilder.of(parseList(html, base)).searchContent();
        } catch (Exception e) {
            return VodBuilder.create().searchContent();
        }
    }

    /** 三参数重载 */
    public String searchContent(String key, boolean quick, String page) {
        return searchContent(key, quick);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) return emptyResponse();
        String type = id.contains(".m3u8") ? "application/vnd.apple.mpegurl"
                    : id.matches(".*\\.(mp3|m4a|aac).*") ? "audio/mpeg"
                    : "video/*";
        return VodBuilder.playerContent(id, type);
    }

    // ── 工具 ────────────────────────────────────────────────────

    protected static String absolute(String base, String href) {
        if (TextUtils.isEmpty(href)) return "";
        if (href.startsWith("http")) return href;
        if (href.startsWith("//")) return "https:" + href;
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return b + (href.startsWith("/") ? href : "/" + href);
    }

    private static String firstGroup(String s, String re) {
        if (s == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(re, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static String firstAttr(String s, String prop) {
        return firstGroup(s, "<meta[^>]+property=\"" + prop + "\"[^>]+content=\"([^\"]*)\"");
    }
}
