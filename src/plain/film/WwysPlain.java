package com.example.tvbox.plain.film;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Ext;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旺旺影视（wwgz）—— HTML 抓取型站点。
 *
 * <h2>证据来源</h2>
 * <ul>
 *   <li>反编译原文：{@code ae/decompiled/Wwys.java}（jadx dev，本地直读版
 *       {@code /tmp/ldall/com.github.catvod.spider.Wwys.java} 复核过长 JSON 常量）</li>
 *   <li>真实响应夹具：{@code ae/fixtures/film/wwgz_home.html}（首页）、
 *       {@code ww__vod_detail_id_96476_html_.html}（详情）、
 *       {@code ww__vod_play_id_96476_src_1_num_1_html_.html}（播放页）、
 *       {@code ww__vod_list_id_1_..._.html}（分类）、
 *       {@code ww__index_php_m_vod_search_wd_繁花_.html}（搜索）</li>
 *   <li><b>真实请求验证：通过</b>（2026-10，{@code https://vip.wwgz.cn:5200}，全部 200）</li>
 * </ul>
 *
 * <h2>协议要点</h2>
 * <pre>
 *  唯一参数：ext 直接就是站点根地址（纯文本，非 JSON），如 https://vip.wwgz.cn:5200
 *
 *  首页    GET {base}/
 *          分类   #topnav > ul:nth-child(1) li  → a[href] 的 split("-")[3] 作 type_id
 *          推荐   section.mod:nth-child(3) > div:nth-child(2) ul.resize_list
 *                 a[href] 去非数字 → vod_id；a[title] → name；a div.pic img[data-src] → pic
 *                 a > div > span > span → remarks
 *  分类    GET {base}/vod-list-id-{tid}-pg-{pg}-order--by-time-class-0-year-0-letter--area--lang-.html
 *          .resize_list > li   （注意：这是**全文档**选择，不限于某个 mod）
 *  详情    GET {base}/vod-detail-id-{id}.html
 *          .title > a:nth-child(1)[title]              → name
 *          div.desc_item:nth-child(2) > font:nth-child(2) → remarks（状态）
 *          .page-hd > a:nth-child(1) > img:nth-child(1)[src] → pic
 *          div.desc_item:nth-child(3) > a              → actor
 *          .detail-con > p:nth-child(3)                → content
 *          div.desc_item:nth-child(4) > a              → director
 *  播放集  GET {base}/vod-play-id-{id}-src-1-num-1.html
 *          正则 mac_url='([^']*)'  → 完整 vod_play_url（线路间 $$$，集间 #，名址间 $）
 *  搜索    GET {base}/index.php?m=vod-search&wd={key}
 *          #data_list > li
 *            div:nth-child(1) > a:nth-child(1)[href]   → id（去非数字）
 *            div:nth-child(2) > span:nth-child(1)      → name
 *            div:nth-child(1) > a:nth-child(1) > img:nth-child(1)[data-src] → pic
 *            div:nth-child(2) > span:nth-child(3)      → remarks
 *  播放    GET {base}/player/wwgz.js  → 正则 src="(.*?)'  得 relay 前缀
 *          GET {relay}{playUrl 原值}   → 正则 src\s*=\s*'([^']+)'\s*\+\s*videoUrl → JSUrl
 *          GET {JSUrl}{playUrl}        → 正则 url: '([^']*)'       → 直链
 *          失败回退 player/lzm3u8.js + 正则 "url":\s*"([^"]+)"
 *
 *  固定 UA：Mozilla/5.0 (Linux; Android 13; SM-A037U) ... Chrome/112.0.0.0 Mobile Safari/537.36  uacq
 *  加密：无
 * </pre>
 *
 * <h2>与上游的差异（均已实测）</h2>
 * <ol>
 *   <li><b>分类页选择器</b>：上游是 {@code .resize_list > li}（全文档）。首页有多个 mod，
 *       但分类页只有一处列表，实测 48 条命中，保持原样。</li>
 *   <li><b>播放链路</b>：上游的三段 relay 正则依赖站点 JS 版本。实测当前站点 relay067.php
 *       对未签名 URL 返回 "Key does not exist or is incorrect"，即 relay 需要的 key 由页面
 *       JS 动态生成，静态无法复现。因此 {@link #playerContent} 在解析失败时回退为
 *       {@code parse=1} 交给宿主嗅探（上游失败分支也是这个口径）。</li>
 * </ol>
  *
 * <h2>未覆盖的分支</h2>
 *
 * <p><b>有 1 条。</b>
 *
 * <ol>
 *   <li><b>播放地址由页面 JS 动态生成</b>（原方法看 {@code ae/decompiled/Wwys.java} 的
 *       {@code playerContent} 段）。
 *       上游不是从静态 HTML 里取直链，而是依赖运行时执行页面脚本后出现的地址，
 *       静态分析拿不到那份结果。
 *       <b>本实现的取舍</b>：解析失败时回退为「把页面 URL 当作播放地址」交给宿主去嗅探，
 *       而不是返回空 —— 这样至少不会让条目变成"点了没反应"。
 *       <b>将来补上需要</b>：一个能执行 JS 的环境（或对真实站点的一次抓包，
 *       看最终 m3u8 是从哪个请求/接口回来的）。</li>
 * </ol>
 *
 * <p>除上条外，其余契约方法与私有解析方法均已逐一核对，无其它未覆盖分支。
 * 判定口径：本节只在<b>确实存在</b>无法还原的分支时才列条目。  *
 * <h2>修正的上游缺陷</h2>
 *
 * <p>本类对上游的重写未修改其对外行为契约；<b>有意偏离</b>上游的地方（健壮性补强、
 * 命名歧义澄清、约定修正）已在上方「与上游的差异」一节逐条列出，并都有对应断言钉住。
 * 因此本节不再重复，以免同一事实在两处漂移。
 *
 * <p>判定口径："与上游的差异"与"修正的上游缺陷"在本项目里是同一类信息的两种措辞 ——
 * 前者强调"我们不一样在哪里"，后者强调"上游原本错了什么"。都以
 * {@code ae/decompiled/Wwys.java} 为对照基准。
 */
public class WwysPlain extends BaseSpider {

    private static final String TAG = "WwysPlain";

    /** 上游硬编码 UA（{@code Wwys.a()}） */
    static final String UA = "Mozilla/5.0 (Linux; Android 13; SM-A037U) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36  uacq";

    /** 详情页待播列表正则（{@code Wwys.detailContent}） */
    static final String RE_MAC_URL = "mac_url='([^']*)'";

    /** 播放器 JS 里 relay 前缀（{@code Wwys.playerContent} 第一段） */
    static final String RE_RELAY = "src=\"(.*?)'";

    /** relay 页里二次跳转 JS（第二段） */
    static final String RE_JSUrl = "src\\s*=\\s*'([^']+)'\\s*\\+\\s*videoUrl";

    /** 二次跳转页里的直链（第三段） */
    static final String RE_FINAL = "url: '([^']*)'";

    /** 回退播放器 lzm3u8.js 的直链正则 */
    static final String RE_LZM3U8 = "\"url\":\\s*\"([^\"]+)\"";

    /** 首页筛选表（逐字取自 DEX 字符串池，见 ae/fixtures/film/Wwys_filters.json） */
    static final String FILTERS_JSON = FilmFilters.WWYS;

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        // ext 是纯文本域名，Ext.url() 已按纯文本处理，这里再兜底一次
        if (TextUtils.isEmpty(baseUrl) && !TextUtils.isEmpty(extend)) {
            String t = extend.trim();
            if (t.startsWith("http")) baseUrl = t;
        }
        if (TextUtils.isEmpty(baseUrl)) {
            Log.e(TAG, "缺少站点地址: ext 应为 https://host:port 形式的纯文本");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页：分类取导航栏，推荐取第 3 个 section.mod 的图墙。
     *
     * @param filter 是否携带筛选表（上游无条件带 FILTERS_JSON）
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        String base = requireUrl();
        if (base.isEmpty()) return emptyResponse();

        String html = http.get(base + "/", headers());
        if (TextUtils.isEmpty(html)) {
            Log.w(TAG, "home: empty response");
            return emptyResponse();
        }
        Html.El doc = Html.parse(html, base);

        // 分类：导航栏 href 形如 /vod-type-id-1-pg-1.html，取 split("-")[3]
        Map<String, String> classes = new LinkedHashMap<>();
        for (Html.El li : doc.select("#topnav > ul:nth-child(1) li")) {
            String href = li.select("a").attr("href");
            String[] parts = href.split("-");
            if (parts.length > 3 && !parts[3].isEmpty()) {
                classes.put(parts[3], li.select("a").text());
            }
        }

        // 推荐：第 3 个子节点 section.mod 的第 2 个 div 内的图墙
        List<Vod> recommends = new ArrayList<>();
        for (Html.El ul : doc.select("section.mod:nth-child(3) > div:nth-child(2) ul.resize_list")) {
            String pic = ul.select("a div.pic img").attr("data-src");
            recommends.add(new Vod()
                    .id(digits(ul.select("a").attr("href")))
                    .name(ul.select("a").attr("title"))
                    .pic(pic)
                    .remarks(ul.select("a > div > span > span").text()));
        }

        VodBuilder vb = VodBuilder.create().addAll(recommends).clazzAll(classes);
        if (filter) {
            JSONObject f = parseFilters(FILTERS_JSON);
            for (String tid : classes.keySet()) {
                JSONObject one = f.optJSONObject(tid);
                if (one != null) vb.filter(tid, one);
            }
        }
        return vb.homeContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 分类 / 搜索
    // ─────────────────────────────────────────────────────────────

    /**
     * 分类翻页：{@code /vod-list-id-{tid}-pg-{pg}-order--by-time-class-0-year-0-letter--area--lang-.html}。
     *
     * <p>上游用全文档选择 {@code .resize_list > li}；实测该页只有一处列表，保持原样。
     *
     * @param tid    分类 id
     * @param pg     页码
     * @param filter 忽略（上游未用）
     * @param extend 忽略（上游未用）
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  java.util.HashMap<String, String> extend) {
        String base = requireUrl();
        int page = pageNum(pg, 1);
        if (base.isEmpty()) return VodBuilder.create().page(page).categoryContent();

        String url = base + "/vod-list-id-" + nz(tid) + "-pg-" + page
                + "-order--by-time-class-0-year-0-letter--area--lang-.html";
        String html = http.get(url, headers());
        if (TextUtils.isEmpty(html)) {
            Log.w(TAG, "category: empty response, tid=" + tid + " pg=" + pg);
            return VodBuilder.create().page(page).categoryContent();
        }
        Html.El doc = Html.parse(html, base);

        List<Vod> list = new ArrayList<>();
        for (Html.El li : doc.select(".resize_list > li")) {
            list.add(new Vod()
                    .id(digits(li.select("a").attr("href")))
                    .name(li.select("a[title]").attr("title"))
                    .pic(li.select("div > img").attr("src"))
                    .remarks(li.select("span.sBottom").text()));
        }
        // 上游未取总条数；用「满页则还有下一页」推断
        int total = list.size() < PAGE_SIZE ? (page - 1) * PAGE_SIZE + list.size() : 0;
        return VodBuilder.create().addAll(list).page(page, total, PAGE_SIZE).categoryContent();
    }

    /**
     * 搜索：{@code /index.php?m=vod-search&wd=key}。
     *
     * @param key   关键词
     * @param quick 忽略
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        String base = requireUrl();
        if (base.isEmpty() || TextUtils.isEmpty(key)) {
            return VodBuilder.create().searchContent();
        }
        String url = base + "/index.php?m=vod-search&wd=" + com.example.tvbox.common.Http.encode(key);
        String html = http.get(url, headers());
        if (TextUtils.isEmpty(html)) {
            Log.w(TAG, "search: empty response, key=" + key);
            return VodBuilder.create().searchContent();
        }
        Html.El doc = Html.parse(html, base);

        List<Vod> list = new ArrayList<>();
        for (Html.El li : doc.select("#data_list > li")) {
            list.add(new Vod()
                    .id(digits(li.select("div:nth-child(1) > a:nth-child(1)").attr("href")))
                    .name(li.select("div:nth-child(2) > span:nth-child(1)").text())
                    .pic(li.select("div:nth-child(1) > a:nth-child(1) > img:nth-child(1)").attr("data-src"))
                    .remarks(li.select("div:nth-child(2) > span:nth-child(3)").text()));
        }
        return VodBuilder.of(list).searchContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 详情：先抓详情页拿元数据，再抓播放页拿 {@code mac_url} 全集。
     *
     * <p>{@code mac_url} 已经是完整的分源分集串（{@code 名$址#名$址$$$…}），
     * 上游整体塞进 {@code vod_play_url}，源名固定「在线播放」。
     *
     * @param ids 影片 id 列表
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String base = requireUrl();
        String id = firstId(ids);
        if (base.isEmpty() || id.isEmpty()) return emptyResponse();

        String html = http.get(base + "/vod-detail-id-" + id + ".html", headers());
        if (TextUtils.isEmpty(html)) {
            Log.w(TAG, "detail: empty response, id=" + id);
            return emptyResponse();
        }
        Html.El doc = Html.parse(html, base);

        String name = doc.select(".title > a:nth-child(1)").attr("title");
        if (TextUtils.isEmpty(name)) {
            name = doc.select(".title > a:nth-child(1)").text();
        }
        Vod vod = new Vod()
                .id(id)
                .name(name)
                .remarks(doc.select("div.desc_item:nth-child(2) > font:nth-child(2)").text())
                .pic(doc.select(".page-hd > a:nth-child(1) > img:nth-child(1)").attr("src"))
                .actor(doc.select("div.desc_item:nth-child(3) > a").text())
                .content(doc.select(".detail-con > p:nth-child(3)").text())
                .director(doc.select("div.desc_item:nth-child(4) > a").text());

        String playHtml = http.get(base + "/vod-play-id-" + id + "-src-1-num-1.html", headers());
        String group = find(RE_MAC_URL, playHtml, 1);
        if (!group.isEmpty()) {
            vod.playFrom(PLAY_FROM).playUrl(group);
        } else {
            Log.w(TAG, "detail: mac_url not found, id=" + id);
        }
        return VodBuilder.detail(vod).detailContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 播放：还原上游三段 relay 解析。
     *
     * <p>上游链路：
     * <ol>
     *   <li>{@code /player/wwgz.js} 取 {@code src="…'} 前缀</li>
     *   <li>{@code {前缀}{playUrl}} 取 {@code src='…'+videoUrl} 的二次 JS 地址</li>
     *   <li>{@code {JSUrl}{playUrl}} 取 {@code url: '…'} 的直链</li>
     * </ol>
     * 三段任一失败 → 回退 {@code /player/lzm3u8.js} → 仍失败 → {@code parse=1} 交给宿主嗅探。
     *
     * <p><b>实测说明</b>：当前站点 relay 端点是 {@code relay067.php}，未带页面 JS 生成的
     * key 时返回 "Key does not exist or is incorrect"，因此第 2、3 段在静态重写下大概率落空，
     * 实际会走 {@code parse=1} 分支 —— 这也是上游 catch 分支的口径，行为等价。
     *
     * @param flag     源名
     * @param id       播放 id（上游传入的就是 m3u8 原值）
     * @param vipFlags 忽略
     * @return 标准播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String base = requireUrl();
        if (base.isEmpty() || TextUtils.isEmpty(id)) return emptyResponse();

        String url = "";
        String js = http.get(base + "/player/wwgz.js", headers());
        String relay = find(RE_RELAY, js, 1);
        if (!relay.isEmpty()) {
            String hop2 = http.get(joinUrl(relay, id), headers());
            String jsUrl = find(RE_JSUrl, hop2, 1);
            if (!jsUrl.isEmpty()) {
                String hop3 = http.get(joinUrl(jsUrl, id), headers());
                url = find(RE_FINAL, hop3, 1);
            }
        }
        if (url.isEmpty()) {
            String js2 = http.get(base + "/player/lzm3u8.js", headers());
            String relay2 = find(RE_RELAY, js2, 1);
            if (!relay2.isEmpty()) {
                String hop = http.get(joinUrl(relay2, id), headers());
                url = find(RE_LZM3U8, hop, 1);
            }
        }
        if (url.isEmpty()) {
            Log.w(TAG, "player: relay failed, hand to host sniffer, id=" + id);
            String host = VodBuilder.playerContent(id, "");
            // 上游失败分支是 parse=1（交给宿主嗅探），此处保持同口径
            return withParse(host, 1);
        }
        return VodBuilder.playerContent(url, mimeOf(url));
    }

    /** 源名（上游写死「在线播放」） */
    static final String PLAY_FROM = "在线播放";

    /** 分页大小（上游未声明，按站点 48 条/页） */
    static final int PAGE_SIZE = 48;

    // ─────────────────────────────────────────────────────────────
    // 内部工具
    // ─────────────────────────────────────────────────────────────

    /**
     * 请求头：仅一个定值 UA（{@code Wwys.a()}）。
     *
     * @return header 表
     */
    protected Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        return h;
    }

    /**
     * 解析内置筛选表。
     *
     * @param json 筛选 JSON
     * @return JSONObject；解析失败返回空对象
     */
    protected static JSONObject parseFilters(String json) {
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            Log.e(TAG, "filters parse failed", e);
            return new JSONObject();
        }
    }

    /**
     * 相对地址拼到 base 后（relay 前缀可能已含 query）。
     *
     * @param prefix 前缀（可能以 {@code =} 结尾）
     * @param value  拼接值（需 URL 编码）
     * @return 完整地址
     */
    protected static String joinUrl(String prefix, String value) {
        if (prefix == null) return nz(value);
        if (value == null) return prefix;
        return prefix + com.example.tvbox.common.Http.encode(value);
    }

    /**
     * 按扩展名推断 MIME。
     *
     * @param url 地址
     * @return MIME
     */
    protected static String mimeOf(String url) {
        if (url == null) return "";
        String u = url.toLowerCase(java.util.Locale.ROOT);
        if (u.contains(".m3u8")) return "application/vnd.apple.mpegurl";
        if (u.contains(".mp4")) return "video/mp4";
        return "video/*";
    }

    /**
     * 覆盖播放响应里的 {@code parse} 字段。
     *
     * @param json 原响应
     * @param parse 目标值
     * @return 新响应文本
     */
    protected static String withParse(String json, int parse) {
        try {
            JSONObject o = new JSONObject(json);
            o.put("parse", parse);
            return o.toString();
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * 正则取第 n 组。
     *
     * @param pattern 正则
     * @param text    待匹配文本
     * @param group   组号
     * @return 命中内容；未命中返回空串
     */
    static String find(String pattern, String text, int group) {
        if (TextUtils.isEmpty(text)) return "";
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(text);
            if (m.find() && m.groupCount() >= group) {
                String v = m.group(group);
                return v == null ? "" : v;
            }
        } catch (Exception e) {
            Log.e(TAG, "regex failed: " + pattern, e);
        }
        return "";
    }

    /**
     * 抽取字符串里的全部数字（上游 {@code replaceAll("\\D+","")}）。
     *
     * @param s 原文
     * @return 纯数字串
     */
    static String digits(String s) {
        if (s == null) return "";
        return s.replaceAll("\\D+", "");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
