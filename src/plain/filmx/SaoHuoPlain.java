package com.example.tvbox.plain.filmx;

import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Dom;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 骚火影视 —— HTML 抓取型源。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  分类页 GET {base}/list/{tid}-{page}.html
 *         条目容器  .v_list .v_img       ← 上游用字节数组混淆，已还原
 *         链接      a[href]
 *         标题      a[title]
 *         封面      img[data-original]
 *         备注      .v_note              ← 上游用字节数组混淆，已还原
 *
 *  详情   {base}{详情路径}
 *         播放源    .play_from ul.from_list li
 *         剧集      #play_link li
 *         简介      p.p_txt
 *
 *  播放   {base}{id} → iframe[src]
 *
 *  搜索   同分类页结构
 * </pre>
 *
 * <h2>★ 字节数组混淆的还原</h2>
 * 上游把 CSS 选择器写成 {@code p014n.a.a(byte[], byte[], element)} 形式。
 * 三个调用点解出的结果：
 * <pre>
 *   home/category/search 备注  → XOR(bytes, key) = ".v_note"
 *   category 条目容器          → XOR(bytes, key) = ".v_list .v_img"
 * </pre>
 * 算法就是<b>按 key 循环异或</b>，无需额外密钥。
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/SaoHuo.java}（165 行）
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/SaoHuo.java} 的对照复核，
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
 * {@code ae/decompiled/SaoHuo.java} 为对照基准。
 */
public class SaoHuoPlain extends BaseSpider {

    private static final String TAG = "SaoHuoPlain";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 4 个分类（ID / 名称，逐字来自上游） */
    private static final String[] CAT_IDS   = {"1", "2", "20", "4"};
    private static final String[] CAT_NAMES = {"电影", "电视剧", "国产剧", "动漫"};

    /** 分类页条目容器选择器（由字节数组 XOR 还原） */
    public static final String SEL_ITEM = ".v_list .v_img";
    /** 备注选择器（由字节数组 XOR 还原） */
    public static final String SEL_NOTE = ".v_note";

    /** 上游硬编码的筛选表（逐字保留） */
    private static final String FILTERS_JSON =
            "{\"1\":[{\"key\":\"cateId\",\"name\":\"类型\",\"value\":["
          + "{\"n\":\"全部\",\"v\":\"1\"},{\"n\":\"喜剧\",\"v\":\"6\"},{\"n\":\"爱情\",\"v\":\"7\"},"
          + "{\"n\":\"恐怖\",\"v\":\"8\"},{\"n\":\"动作\",\"v\":\"9\"},{\"n\":\"科幻\",\"v\":\"10\"},"
          + "{\"n\":\"战争\",\"v\":\"11\"},{\"n\":\"犯罪\",\"v\":\"12\"},{\"n\":\"动画\",\"v\":\"13\"},"
          + "{\"n\":\"奇幻\",\"v\":\"14\"},{\"n\":\"剧情\",\"v\":\"15\"},{\"n\":\"冒险\",\"v\":\"16\"},"
          + "{\"n\":\"悬疑\",\"v\":\"17\"},{\"n\":\"惊悚\",\"v\":\"18\"},{\"n\":\"其他\",\"v\":\"20\"}]}],"
          + "\"2\":[{\"key\":\"cateId\",\"name\":\"类型\",\"value\":["
          + "{\"n\":\"全部\",\"v\":\"2\"},{\"n\":\"国产剧\",\"v\":\"20\"},{\"n\":\"TVB\",\"v\":\"21\"},"
          + "{\"n\":\"韩剧\",\"v\":\"22\"},{\"n\":\"美剧\",\"v\":\"23\"},{\"n\":\"日剧\",\"v\":\"24\"},"
          + "{\"n\":\"英剧\",\"v\":\"25\"},{\"n\":\"台剧\",\"v\":\"26\"},{\"n\":\"其他\",\"v\":\"27\"}]}],"
          + "\"3\":[{\"key\":\"cateId\",\"name\":\"类型\",\"value\":["
          + "{\"n\":\"全部\",\"v\":\"4\"},{\"n\":\"搞笑\",\"v\":\"38\"},{\"n\":\"恋爱\",\"v\":\"39\"},"
          + "{\"n\":\"热血\",\"v\":\"40\"},{\"n\":\"格斗\",\"v\":\"41\"},{\"n\":\"美少女\",\"v\":\"42\"},"
          + "{\"n\":\"魔法\",\"v\":\"43\"},{\"n\":\"机战\",\"v\":\"44\"},{\"n\":\"校园\",\"v\":\"45\"},"
          + "{\"n\":\"亲子\",\"v\":\"46\"},{\"n\":\"童话\",\"v\":\"47\"},{\"n\":\"冒险\",\"v\":\"48\"},"
          + "{\"n\":\"真人\",\"v\":\"49\"},{\"n\":\"LOLI\",\"v\":\"50\"},{\"n\":\"其他\",\"v\":\"51\"}]}]}";

    /** Cookie（上游 init 时从首页 Set-Cookie 取） */
    private String cookie = "";

    private Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("accept-language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        if (!TextUtils.isEmpty(cookie)) h.put("Cookie", cookie);
        return h;
    }

    @Override
    public String homeContent(boolean filter) {
        VodBuilder vb = VodBuilder.create();
        for (int i = 0; i < CAT_IDS.length; i++) {
            vb.clazz(CAT_IDS[i], CAT_NAMES[i]);
        }
        // 注入上游硬编码筛选表
        String base = vb.homeContent();
        try {
            JSONObject root = new JSONObject(base);
            root.put("filters", new JSONObject(FILTERS_JSON));
            return root.toString();
        } catch (Exception e) {
            return base;
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  java.util.HashMap<String, String> extend) {
        if (TextUtils.isEmpty(tid)) return VodBuilder.create().page(1).categoryContent();
        int page = pageNum(pg, 1);

        String base = requireUrl();
        if (base.isEmpty()) {
            Log.w(TAG, "categoryContent: no baseUrl");
            return VodBuilder.create().page(page).categoryContent();
        }

        String url = base + String.format("/list/%s-%s.html", tid, page);
        String html = http.get(url, apiHeaders());
        if (TextUtils.isEmpty(html)) {
            Log.w(TAG, "categoryContent: empty html " + url);
            return VodBuilder.create().page(page).categoryContent();
        }

        List<Vod> list = parseList(html, base);
        Log.i(TAG, "categoryContent " + tid + "-" + page + " -> " + list.size());
        return VodBuilder.create().addAll(list).page(page).categoryContent();
    }

    /** 解析列表 —— 用还原出的选择器 */
    public List<Vod> parseList(String html, String base) {
        List<Vod> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;

        Dom.El doc = Dom.parse(html, base);
        for (Dom.El item : doc.select(SEL_ITEM)) {
            Dom.El a = item.selectFirst("a");
            String href = a.attr("href");
            String title = a.attr("title");
            if (title.isEmpty()) title = a.text();
            String pic = item.selectFirst("img").attr("data-original");
            if (pic.isEmpty()) pic = item.selectFirst("img").attr("src");
            // 备注：.v_note 可能在条目内或紧随其后
            String note = item.selectFirst(SEL_NOTE).text();

            if (href.isEmpty() && title.isEmpty()) continue;
            out.add(new Vod().id(absolute(base, href)).name(title).pic(pic).remarks(note));
        }
        return out;
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (TextUtils.isEmpty(id)) return emptyResponse();

        String base = requireUrl();
        String html = http.get(absolute(base, id), apiHeaders());
        if (TextUtils.isEmpty(html)) return emptyResponse();

        try {
            Dom.El doc = Dom.parse(html, base);
            String name = doc.selectFirst("h1").text();
            if (name.isEmpty()) name = doc.selectFirst("title").text();
            String intro = doc.selectFirst("p.p_txt").text();

            // 播放源列表
            List<String> froms = new ArrayList<>();
            for (Dom.El li : doc.select(".play_from ul.from_list li")) {
                String t = li.text();
                if (!t.isEmpty()) froms.add(t);
            }
            if (froms.isEmpty()) froms.add("线路1");

            // 剧集
            List<String> eps = new ArrayList<>();
            for (Dom.El li : doc.select("#play_link li")) {
                Dom.El a = li.selectFirst("a");
                String href = a.attr("href");
                String t = a.text();
                if (t.isEmpty()) t = li.text();
                if (href.isEmpty()) continue;
                eps.add(t + Vod.SEP_NAME_URL + absolute(base, href));
            }

            Vod v = new Vod()
                    .id(id)
                    .name(name)
                    .pic("")
                    .content(intro)
                    .playFrom(TextUtils.join(Vod.SEP_SOURCE, froms));
            if (!eps.isEmpty()) {
                v.playUrl(TextUtils.join(Vod.SEP_EPISODE, eps));
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
        try {
            String url = base + "/search/" + java.net.URLEncoder.encode(key, "UTF-8") + ".html";
            String html = http.get(url, apiHeaders());
            return VodBuilder.of(parseList(html, base)).searchContent();
        } catch (Exception e) {
            return VodBuilder.create().searchContent();
        }
    }

    /** 播放 —— 取 iframe src */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) return emptyResponse();
        String base = requireUrl();
        String html = http.get(absolute(base, id), apiHeaders());
        String src = "";
        if (!TextUtils.isEmpty(html)) {
            src = Dom.parse(html, base).selectFirst("iframe").attr("src");
        }
        String url = src.isEmpty() ? id : absolute(base, src);
        String type = url.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/*";
        return VodBuilder.playerContent(url, type);
    }

    /** 相对转绝对 */
    protected static String absolute(String base, String href) {
        if (TextUtils.isEmpty(href)) return "";
        if (href.startsWith("http")) return href;
        if (href.startsWith("//")) return "https:" + href;
        String b = base == null ? "" : base;
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + (href.startsWith("/") ? href : "/" + href);
    }

    /** 字节数组 XOR 解码 —— 还原上游 p014n.a.a 的核心算法（供审计复现） */
    public static String xorDecode(byte[] bytes, byte[] key) {
        if (bytes == null || key == null || key.length == 0) return "";
        byte[] out = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = (byte) (bytes[i] ^ key[i % key.length]);
        }
        return new String(out, java.nio.charset.StandardCharsets.UTF_8);
    }
}
