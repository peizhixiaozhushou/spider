package com.example.tvbox.plain.duanju;

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
 * 短剧站群公共骨架 —— 承载 5 个短剧类（ShortQimao / ShortHema / ShortWeiguan /
 * ShortHaokan / ShortXingya）与少儿类共用的样板代码。
 *
 * <h2>为什么要抽这一层</h2>
 * 反编译原文显示这 5 个类<b>骨架完全同构</b>，只有「请求怎么签」「响应字段叫什么」两处不同：
 *
 * <pre>
 *  homeContent     → （可选 GET/POST）→ 取 data.xxx 数组 → 分类表 → 输出 {class, list}
 *  categoryContent → （可选 POST）→ 取 data.list → 每项映射成 Vod → 输出分页四元组
 *  detailContent   → （可选 POST）→ 取 data → 组装 vod_play_from / vod_play_url
 *  searchContent   → 同 categoryContent，只是分页恒为 1
 *  playerContent   → 直链 + 可选 UA 头 → {parse:0, url, header}
 * </pre>
 *
 * 上游把这套结构在 5 个类里各抄了一遍（合计 1080 行），本骨架把其中
 * <b>字段映射、分页计算、集数拼接、播放串组装、空响应兜底</b> 全部收拢到一处，
 * 子类只保留自身协议差异（约 60~120 行），显著降低重复。
 *
 * <h2>TVBox 播放串约定（本骨架统一实现）</h2>
 * <pre>
 *  vod_play_from = 线路名1$$$线路名2
 *  vod_play_url  = 集名1$地址1#集名2$地址2$$$集名1$地址1#...
 * </pre>
 * 分隔符取自 {@link Vod#SEP_SOURCE} / {@link Vod#SEP_EPISODE} / {@link Vod#SEP_NAME_URL}，
 * 不硬编码字面量，避免与宿主约定漂移。
 *
 * <h2>证据来源</h2>
 * <ul>
 *   <li>反编译原文: {@code ae/decompiled/Short*.java}（jadx dev，经 local_dump.py 复核常量长度）</li>
 *   <li>上游 VO 结构: {@code com.github.catvod.spider.merge.p028c.e}
 *       —— 字段名 {@code vod_id/vod_name/vod_pic/vod_remarks/vod_play_from/vod_play_url}</li>
 *   <li>上游响应构造器: {@code com.github.catvod.spider.merge.p028c.c}
 *       —— 分页字段 {@code page/pagecount/limit/total}，播放体 {@code parse/url/header}</li>
 *   <li>上游播放串拼法: {@code merge.A.a.p(map, "$$$", eVar, "$$$")}</li>
 * </ul>
 *
 * <h2>与上游的行为差异（均为修 bug，不是简化）</h2>
 * <ol>
 *   <li>上游多处用 {@code android.text.TextUtils.join} 拼集数。该 API 在非 android 运行时不产出，
 *       本骨架改用自有 {@link #join(List, String)}。</li>
 *   <li>上游 {@code ShortHema.categoryContent} 用 {@code Integer.parseInt(tid)} 且无 try，
 *       tid 非数字直接崩。本骨架在子类里做了容错。</li>
 *   <li>上游分页 {@code Integer.MAX_VALUE} 直接塞进 JSON（{@code ShortHaokan}），
 *       TVBox 侧计算 pagecount 会溢出。本骨架改为按实际条数估算。</li>
 * </ol>
 */
public abstract class ShortBase extends BaseSpider {

    /** 分页默认条数；子类可用 {@link #pageSize()} 覆盖 */
    public static final int DEFAULT_PAGE_SIZE = 24;

    /** {@code parse=0} 表示直链，无需嗅探/解析 */
    protected static final int PLAY_DIRECT = 0;

    /**
     * 构造：仅初始化基类状态。
     *
     * <p>密钥/站点等差异全部走 {@link #init(android.content.Context, String)} 子类钩子。
     */
    protected ShortBase() {
        super();
    }

    // ─────────────────────────────────────────────────────────────
    // 子类必须实现的协议差异
    // ─────────────────────────────────────────────────────────────

    /**
     * 站点显示名，用于日志与默认线路名。
     *
     * @return 非空名称，如 {@code "七猫"}
     */
    protected abstract String siteName();

    /**
     * 本站每页条数（上游多以硬编码传入，如 20 / 18 / 30 / 24 / 9）。
     *
     * @return 每页条数，&gt;0
     */
    protected int pageSize() {
        return DEFAULT_PAGE_SIZE;
    }

    // ─────────────────────────────────────────────────────────────
    // 字段映射：pipe 分隔的候选链
    // ─────────────────────────────────────────────────────────────

    /**
     * 按「候选字段链」取第一个非空字符串。
     *
     * <p>上游 ShortQimao 的封面就是这样写的：
     * <pre>
     *   String[] covers = {"image_link","image","cover","vertical_cover","playlet_cover"};
     *   for (String c : covers) { v = o.optString(c); if (!isEmpty(v)) break; }
     * </pre>
     * 本方法把这段展开成一行配置，5 个类共用。
     *
     * @param o     源 JSON 对象，可为 null
     * @param chain 候选字段名，用 {@code |} 分隔，如 {@code "image_link|image|cover"}
     * @return 首个非空值；全空或入参非法时返回空串
     */
    protected static String pick(JSONObject o, String chain) {
        if (o == null || chain == null || chain.isEmpty()) {
            return "";
        }
        for (String key : chain.split("\\|")) {
            String k = key.trim();
            if (k.isEmpty()) {
                continue;
            }
            // type_id 这类字段在真实响应里是 JSON 数字，optString 会转成 "0" 而不是空，
            // 因此这里统一用 opt() 取原始值再转字符串，兼容数字/字符串两种形态。
            Object raw = o.opt(k);
            if (raw == null || raw == JSONObject.NULL) {
                continue;
            }
            String v = String.valueOf(raw).trim();
            if (!v.isEmpty() && !"null".equals(v)) {
                return v;
            }
        }
        return "";
    }

    /**
     * 按候选链取字段，并剥离 HTML 标签（上游 ShortQimao 对 title 做了
     * {@code Pattern.compile("<[^>]+>").replaceAll("")}）。
     *
     * @param o     源对象
     * @param chain 候选链
     * @return 去标签后的文本；无值返回空串
     */
    protected static String pickText(JSONObject o, String chain) {
        return stripTags(pick(o, chain));
    }

    /**
     * 去掉 HTML 标签并解码常见实体、折叠空白。
     *
     * @param s 原文，可为 null
     * @return 纯文本；入参为空时返回空串
     */
    protected static String stripTags(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (s.indexOf('<') < 0 && s.indexOf('&') < 0) {
            return s.trim();
        }
        String t = s.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        return t.replaceAll("[\\s\\u00a0\\u3000]+", " ").trim();
    }

    /**
     * 把一个 JSON 数组映射成 Vod 列表。
     *
     * <p>四个映射表达式均支持 {@code |} 候选链，且可为空串表示「不要该字段」。
     *
     * @param arr     源数组，可为 null
     * @param node    若每条记录外面还包了一层节点名（Xingya 的 {@code "theater"}）填它，
     *                否则传 null / 空串
     * @param idChain     vod_id 候选链
     * @param nameChain   vod_name 候选链
     * @param picChain    vod_pic 候选链
     * @param remarkChain vod_remarks 候选链
     * @return 非 null 列表；缺 id 的条目会被跳过（上游同样跳过）
     */
    protected List<Vod> mapVods(JSONArray arr, String node,
                                String idChain, String nameChain,
                                String picChain, String remarkChain) {
        List<Vod> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            JSONObject src = o;
            if (!TextUtils.isEmpty(node)) {
                JSONObject inner = o.optJSONObject(node);
                if (inner == null) {
                    continue;
                }
                src = inner;
            }
            String id = pick(src, idChain);
            if (id.isEmpty()) {
                continue;
            }
            out.add(new Vod()
                    .id(id)
                    .name(pickText(src, nameChain))
                    .pic(absolutize(pick(src, picChain)))
                    .remarks(pickText(src, remarkChain)));
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 图片地址归一
    // ─────────────────────────────────────────────────────────────

    /**
     * 补全协议相对地址（{@code //host/x.jpg} → {@code https://host/x.jpg}）。
     *
     * <p>上游多个类各自写了一遍 {@code if (s.startsWith("//")) s = "https:" + s;}，
     * TongQu / TuXiaoBei / FirstAid 都有，这里收敛成一处。
     *
     * @param url 原始地址，可为 null
     * @return 可播放/可展示的地址；入参空时返回空串
     */
    protected static String absolutize(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        if (u.isEmpty()) {
            return "";
        }
        if (u.startsWith("//")) {
            return "https:" + u;
        }
        return u;
    }

    /**
     * 把相对路径拼到基址上（{@code /jijiu/a.html} + {@code https://m.youlai.cn}）。
     *
     * @param base 基址，可为 null
     * @param path 相对路径或已是绝对 URL，可为 null
     * @return 绝对 URL；两者都空时返回空串
     */
    protected static String joinUrl(String base, String path) {
        String p = path == null ? "" : path.trim();
        if (p.isEmpty()) {
            return "";
        }
        if (p.startsWith("http://") || p.startsWith("https://")) {
            return p;
        }
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.isEmpty()) {
            return p;
        }
        return p.startsWith("/") ? b + p : b + "/" + p;
    }

    // ─────────────────────────────────────────────────────────────
    // 播放串组装
    // ─────────────────────────────────────────────────────────────

    /**
     * 拼一集的 {@code 集名$地址}。
     *
     * @param label 集名，如 {@code "第1集"}；空则按序号补
     * @param url   播放地址
     * @param index 序号（从 1 开始），label 为空时用于生成集名
     * @return 单集串；url 为空时返回空串
     */
    protected static String episode(String label, String url, int index) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) {
            return "";
        }
        String l = label == null ? "" : label.trim();
        if (l.isEmpty()) {
            l = "第" + index + "集";
        }
        return l + Vod.SEP_NAME_URL + u;
    }

    /**
     * 把若干 {@code 集名$地址} 用 {@link Vod#SEP_EPISODE} 连成一整条线路。
     *
     * @param episodes 单集串列表，可为 null
     * @return 连接结果；无有效集时返回空串
     */
    protected static String joinEpisodes(List<String> episodes) {
        if (episodes == null || episodes.isEmpty()) {
            return "";
        }
        List<String> valid = new ArrayList<>();
        for (String e : episodes) {
            if (e != null && !e.isEmpty()) {
                valid.add(e);
            }
        }
        return join(valid, Vod.SEP_EPISODE);
    }

    /**
     * 组装详情页的播放字段。
     *
     * <p>对应上游 {@code merge.A.a.p(LinkedHashMap, "$$$", e, "$$$")}
     * 以及各处手写的 {@code eVar.m(TextUtils.join("$$$", keys)); eVar.n(...)}。
     *
     * @param vod       目标影片
     * @param fromNames 线路名列表，可为 null
     * @param perLine   与 fromNames 等长的「整条线路串」列表
     * @return 传入的 vod（便于链式），永不 null
     */
    protected Vod attachPlay(Vod vod, List<String> fromNames, List<String> perLine) {
        if (vod == null) {
            return new Vod();
        }
        if (fromNames == null || perLine == null || fromNames.isEmpty()) {
            return vod;
        }
        int n = Math.min(fromNames.size(), perLine.size());
        List<String> froms = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String u = perLine.get(i);
            if (u == null || u.isEmpty()) {
                continue;
            }
            froms.add(fromNames.get(i) == null ? siteName() : fromNames.get(i));
            urls.add(u);
        }
        if (froms.isEmpty()) {
            return vod;
        }
        return vod.playFrom(join(froms, Vod.SEP_SOURCE)).playUrl(join(urls, Vod.SEP_SOURCE));
    }

    /**
     * 单线路便捷版 {@link #attachPlay(Vod, List, List)}。
     *
     * @param vod      目标影片
     * @param fromName 线路名，空则用 {@link #siteName()}
     * @param eps      整条线路串（已用 {@code #} 连接）
     * @return 传入的 vod
     */
    protected Vod attachPlay(Vod vod, String fromName, String eps) {
        List<String> f = new ArrayList<>();
        f.add(TextUtils.isEmpty(fromName) ? siteName() : fromName);
        List<String> u = new ArrayList<>();
        u.add(eps == null ? "" : eps);
        return attachPlay(vod, f, u);
    }

    // ─────────────────────────────────────────────────────────────
    // 标准响应
    // ─────────────────────────────────────────────────────────────

    /**
     * 分类/搜索共用的列表响应。
     *
     * @param list  影片列表，可为 null
     * @param page  当前页（&gt;=1）
     * @return 搜索标准体 {@code {"list":[...]}}
     */
    protected String listResponse(List<Vod> list) {
        return VodBuilder.of(list).searchContent();
    }

    /**
     * 分类分页响应。
     *
     * <p>上游普遍拿不到总条数，靠「本页是否满页」推断还有没有下一页；
     * 本方法把这一约定集中实现，避免每个类各写一遍。
     *
     * @param list    本页影片
     * @param page    当前页（&gt;=1）
     * @param limit   每页条数（&lt;=0 时用 {@link #pageSize()}）
     * @param hasMore 是否还有下一页；无法判断时传 {@code list.size() >= limit}
     * @return TVBox 分类标准体
     */
    protected String pageResponse(List<Vod> list, int page, int limit, boolean hasMore) {
        int size = limit <= 0 ? pageSize() : limit;
        int total = hasMore
                ? page * size + 1
                : (page - 1) * size + (list == null ? 0 : list.size());
        return VodBuilder.create()
                .addAll(list)
                .page(page <= 0 ? 1 : page, total, size)
                .categoryContent();
    }

    /**
     * 首页响应（分类 + 推荐）。
     *
     * @param classes 分类表，保持插入顺序，可为 null
     * @param vods    推荐位，可为 null
     * @return TVBox 首页标准体
     */
    protected String homeResponse(Map<String, String> classes, List<Vod> vods) {
        VodBuilder vb = VodBuilder.create().addAll(vods);
        if (classes != null) {
            vb.clazzAll(classes);
        }
        return vb.homeContent();
    }

    /**
     * 详情响应。
     *
     * @param vod 已填好播放字段的影片，可为 null
     * @return TVBox 详情标准体；vod 为 null 时返回空响应
     */
    protected String detailResponse(Vod vod) {
        if (vod == null) {
            return emptyResponse();
        }
        return VodBuilder.detail(vod).detailContent();
    }

    /**
     * 直链播放响应。
     *
     * <p>对应上游 {@code c.e().u(url).e(headers).k(0)}：parse=0，携带自定义请求头。
     *
     * @param url     播放地址，可为 null
     * @param headers 播放器需带的请求头（UA / Referer），可为 null
     * @return TVBox 播放标准体；url 为空时返回空 url 的合法体
     */
    protected String playResponse(String url, Map<String, String> headers) {
        String u = url == null ? "" : url.trim();
        JSONObject root = new JSONObject();
        try {
            root.put("parse", PLAY_DIRECT);
            root.put("playUrl", "");
            root.put("url", u);
            root.put("header", headerJson(headers));
        } catch (Exception e) {
            Log.e(tag(), "playResponse failed", e);
            return emptyResponse();
        }
        return root.toString();
    }

    /**
     * 把 header 表序列化成播放器可读的 JSON 字符串。
     *
     * @param headers header 表，可为 null
     * @return JSON 文本；表空时返回空串
     */
    protected static String headerJson(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                o.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        } catch (Exception ignored) {
            return "";
        }
        return o.toString();
    }

    /**
     * 单个 UA 头的便捷构造。
     *
     * @param ua User-Agent 值，可为 null
     * @return 有序 header 表，永不 null
     */
    protected static Map<String, String> uaHeader(String ua) {
        Map<String, String> h = new LinkedHashMap<>();
        if (ua != null && !ua.isEmpty()) {
            h.put("User-Agent", ua);
        }
        return h;
    }

    // ─────────────────────────────────────────────────────────────
    // JSON 取值容错
    // ─────────────────────────────────────────────────────────────

    /**
     * 逐层下钻取数组，任一层缺失即返回 null（不抛异常）。
     *
     * <p>上游到处都是 {@code new JSONObject(s).getJSONObject("a").getJSONObject("b")
     * .getJSONArray("list")}，任一环为 null 就 NPE。本方法把这条链变成安全路径。
     *
     * @param root 根对象，可为 null
     * @param path 节点名链，如 {@code "data", "list"}；最后一段可以是数组
     * @return 找到的数组；任一层缺失返回 null
     */
    protected static JSONArray digArray(JSONObject root, String... path) {
        Object cur = root;
        for (int i = 0; i < path.length; i++) {
            if (cur == null) {
                return null;
            }
            boolean last = (i == path.length - 1);
            if (cur instanceof JSONObject) {
                JSONObject o = (JSONObject) cur;
                if (last) {
                    return o.optJSONArray(path[i]);
                }
                cur = o.optJSONObject(path[i]);
            } else {
                return null;
            }
        }
        return null;
    }

    /**
     * 逐层下钻取对象，任一层缺失即返回 null。
     *
     * @param root 根对象，可为 null
     * @param path 节点名链
     * @return 找到的对象；任一层缺失返回 null
     */
    protected static JSONObject digObject(JSONObject root, String... path) {
        Object cur = root;
        for (String key : path) {
            if (!(cur instanceof JSONObject)) {
                return null;
            }
            cur = ((JSONObject) cur).optJSONObject(key);
        }
        return (JSONObject) cur;
    }

    /**
     * 安全解析 JSON 文本。
     *
     * @param text 响应体，可为 null/非 JSON
     * @return 对象；解析失败返回 null（区别于"空对象"，便于调用方判断「不是 JSON」）
     */
    protected static JSONObject parseObject(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.isEmpty() || (!t.startsWith("{") && !t.startsWith("["))) {
            return null;
        }
        try {
            return new JSONObject(t);
        } catch (Throwable e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────────

    /**
     * 用分隔符连接字符串列表（替代 stub 环境下返回空串的 {@code TextUtils.join}）。
     *
     * @param parts 片段，可为 null
     * @param sep   分隔符
     * @return 连接结果；空列表返回空串
     */
    protected static String join(List<String> parts, String sep) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        String s = sep == null ? "" : sep;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(s);
            }
            sb.append(parts.get(i) == null ? "" : parts.get(i));
        }
        return sb.toString();
    }

    /**
     * 安全 int 解析。
     *
     * @param s   文本，可为 null/非数字
     * @param def 缺省值
     * @return 解析结果或 def
     */
    protected static int toInt(String s, int def) {
        if (s == null || s.trim().isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable e) {
            return def;
        }
    }

    /**
     * 日志截断，避免把整页 HTML/JSON 打进 logcat。
     *
     * @param s 原文
     * @param n 保留字符数
     * @return 截断结果
     */
    protected static String head(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /**
     * 带重试的 GET（走基类已配置的 Http）。
     *
     * @param url     完整 URL
     * @param headers 请求头，可为 null
     * @return 响应体；失败返回空串
     */
    protected String get(String url, Map<String, String> headers) {
        return http.get(url, headers);
    }

    /**
     * POST 表单/JSON（Content-Type 由调用方在 headers 里指定）。
     *
     * @param url     完整 URL
     * @param body    请求体，可为 null
     * @param headers 请求头，可为 null
     * @return 响应体；失败返回空串
     */
    protected String post(String url, String body, Map<String, String> headers) {
        return http.post(url, body, headers);
    }

    /**
     * 当前站点基址（子类若把接口域名与展示域名分开，覆写本方法给出接口基址）。
     *
     * @return 基址；未配置时为空串
     */
    protected String apiBase() {
        return baseUrl == null ? "" : baseUrl;
    }

    /**
     * 按 ext 配好的 Http 客户端（供子类在 init 里追加默认头）。
     *
     * @return Http 实例，永不 null
     */
    protected Http http() {
        return http;
    }
}
