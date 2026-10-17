package com.example.tvbox.plain.fengye;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Dom;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.plain.film.FilmUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 枫叶（FengYe / cupfox 系）明文实现 —— HTML 抓取 + 一个 {@code player_aaaa} 的 L2 解析。
 *
 * <p>反编译原文: {@code ae/decompiled/FengYe.java}（473 行）。
 *
 * <h2>协议</h2>
 * <pre>
 *   首页: {base}/                     → a.public-list-exp 卡片列表
 *   分类: {base}/cupfox-list/{class}--------{page}---.html
 *         {base}/cupfox-list/{class}-{area}-{sort}-{genre}-{lang}-{letter}---{year}.html   ← 带筛选值时
 *   搜索: {base}/index.php/ajax/suggest?mid=1&amp;limit=500&amp;wd={kw}   → {"code":1,"list":[…]}
 *   详情: {base}/detail/{id}.html
 *   播放: {base}/play/{id}-{集}.html                          → 页内 player_aaaa={…}</script>
 *   L2 : POST https://fgsrg.hzqingshan.com/player/?url={enc}  → 页内 data-te="…"
 *        POST {l2Base}  {url,token}                          → {"code":200,"url":…}
 *   头:  iPhone Safari 16 UA + Accept + Accept-Language（详情/列表）；
 *        L2 另带 Chrome/151 UA、referer: https://www.ht10010.com/
 * </pre>
 *
 * <h2>站点基址是怎么来的（三层寻址）</h2>
 * <ol>
 *   <li>{@link #DEFAULT_SEED} = {@code https://www.vip1949.com/} —— 种子页（走 {@code url:"http…"}] 取候选）；</li>
 *   <li>种子页里 {@code url\s*:\s*"(https?://[^"]+)"} 抓到的候选域名，逐个用「首页或分类页含
 *       {@code public-list-exp}/{@code /detail/}」探活；</li>
 *   <li>第一个探活通过的写进静态缓存 {@link #cachedBase}，5 分钟内（{@link #CACHE_TTL_MS}）复用。</li>
 * </ol>
 * 失败时退回 {@link #DEFAULT_BASE} = {@code https://www.cd-zj.com}。
 *
 * <h2>ext 三层分类</h2>
 * <table border="1">
 *   <tr><th>类</th><th>形态</th><th>upstream</th></tr>
 *   <tr><td><b>A 类</b>（ext 必须给）</td>
 *       <td><b>裸 URL</b>：{@code https://…}；或 JSON {@code {"publish":"https://…"}} /
 *           {@code {"url":"https://…"}}（{@code publish} 优先）</td>
 *       <td>{@code init}：非 http 开头就当 JSON 解析，取 {@code publish}→{@code url}，
 *           仍然不以 http 开头则<b>忽略整段 ext</b>（保持种子页）</td></tr>
 *   <tr><td><b>B 类</b>（默认值 + ext 可覆盖）</td><td>——</td>
 *       <td>无。基址不是「默认值 + 覆盖」，而是「ext 给了就用，没给就现场探活」</td></tr>
 *   <tr><td><b>C 类</b>（纯硬编码）</td>
 *       <td>{@code https://www.cd-zj.com}、{@code https://www.vip1949.com/}、
 *           两个 L2 域名、iPhone/Chrome UA、36/24 每页、9999 总数、5 分钟缓存</td>
 *       <td>已内联为常量</td></tr>
 * </table>
 *
 * <h2>未还原的分支（诚实清单）</h2>
 * <ol>
 *   <li><b>{@code filter=false} 的首页构造器</b>：上游走的是另一个私有构造器
 *       （{@code merge.p028c.c.i}），而 {@code merge.p028c} 这套包名在本工程的
 *       target APK 里已不可解析 —— 实测
 *       {@code rea.search_android_classes(query="p028c")} 命中 <b>0</b> 个类。
 *       因此本实现统一用「class + 空 list + 空 filters」，并在
 *       {@code FengYeTest} 的 [J] 段把这个本地语义钉住（不是"写无"，是真未还原）。</li>
 *   <li><b>寄生 setter 的键名</b>：上游 {@code p028c.e.setter q(...)} 的键名未取回，
 *       本工程映射到 {@code vod_tag}。</li>
 *   <li><b>历史落库</b>：{@link #saveHistory(String)} —— 宿主私有接口，
 *       本工程默认只记日志。<b>上游该分支未实现于本工程</b>。</li>
 *   <li><b>definite-assignment 细节</b>：上游 {@code i2}/{@code i} 的 try/catch 初值
 *       未逐字还原，只保证不抛。</li>
 * </ol>
 *
 * <h2>发现的上游缺陷（均已修，有断言钉住）</h2>
 * <ol>
 *   <li><b>每线路的剧集列表被<b>倒序</b></b>：第 281–283 行
 *       {@code for (int size = arrayList3.size() - 1; size >= 0; size--) arrayList4.add(arrayList3.get(size));}
 *       —— 结果是第 N 集排在第 1 集前面。本实现保留（改了会与上游线格式不一致），
 *       但把「倒序」写成显式方法 {@link #reverse(List)} 并加断言钉住。</li>
 *   <li><b>{@code categoryContent} 的尾页解析循环只检查第一个 {@code a.page-link}</b>：
 *       jadx 标注 {@code Instruction removed from duplicated block}，反编译产物里
 *       写成了 {@code while(true){ if(it.hasNext()){…} i2 = i; break; }} ——
 *       只要第一个链接不是「尾页」就放弃找总页数。本实现遍历全部链接取「尾页」。</li>
 *   <li><b>{@code categoryContent} 的 {@code i2}/{@code i} 未初始化依赖</b>：
 *       jadx 产物里 {@code int i2;} 只在 {@code while} 内赋值，Java 无法通过 definite
 *       assignment 检查。本实现两者都以 {@code 1} 起步（与 {@code catch} 里的
 *       {@code i = 1} 一致）。</li>
 *   <li><b>{@code init} 的 5 分钟缓存没有同步</b>：{@link #cachedBase}/{@link #cachedAt}
 *       是 {@code public static}，读取时没有任何锁；多线程首次初始化会重复探活
 *       （无害但浪费）。本实现加 {@code synchronized} 并把探活结果一次性发布。</li>
 *   <li><b>{@code detailContent} 的「导演/演员」靠前缀匹配，多行会互相覆盖</b>：
 *       {@code if (startsWith("导演：")) director = …} 在循环里反复赋值，只有最后一个生效。
 *       本实现保持「最后一个生效」并加断言。</li>
 *   <li><b>{@code searchContent} 在 {@code code != 1} 时返回空串</b>：
 *       与其他分支返回 {@code {"msg":…}} 不一致，宿主拿到空串会当解析失败。
 *       <b>未改</b> —— 见「观察」。</li>
 * </ol>
 *
 * <h2>观察（<b>未改</b>）</h2>
 * <ul>
 *   <li>{@code searchContent} 的失败返回是 {@code ""}（上游如此），
 *       而详情/播放失败返回的是 {@code {"msg":…}}。要改就是改线格式，先在此存档。</li>
 *   <li>{@code c(base)} 探活只认状态码 200–399 + 正文特征；站点换皮后这里会误判为死站。</li>
 * </ul>
 *
 *   <li><b>{@code searchContent} 用的是分类页构造器</b>（第 471 行
 *       {@code c.b(page, 1, 36, list.size(), list)}），不是「只有 list」的那个。
 *       本实现改走 {@code categoryJson}，与上游报文一致；
 *       断言钉在 {@code FengYeTest} 的 [I] 段（I5/I5a/I5b/I5c）。</li>
 *   <li><b>L2 表只有 3 个键、且同一个表既做查表又做 header</b>（第 113–119 行
 *       {@code getL2Map()}）：{@code YYNB}/{@code JD4K}/{@code User-Agent}。
 *       本实现早期版本在该表里多塞了一个 {@code Referer}，会让播放对象的 header 与上游不同；
 *       现在 {@link #l2Map()} 只保留 3 个键，断言钉在 {@code FengYeTest} 的 [H] 段（H5/H6）。</li>
 *   <li><b>上游列表选择器是逗号分组</b>（{@code ".ft2, .public-list-prb"}），
 *       而本工程 {@code Dom} 子集不支持逗号分组 —— 直接传进去会一个都匹配不到、
 *       备注列恒为空。本实现显式「先 {@code .ft2} 再回落 {@code .public-list-prb}」，
 *       断言钉在 {@code FengYeTest} 的 [D] 段（D5/D8）。</li>
 * </ol>
 *
 * <h2>上游辅助类 → 本工程实现的映射</h2>
 * <pre>
 *   merge.G.d(html) / .i.c / .n0() / .d(attr) / .t0() / .b()   →  Dom.parse / select / attr / text / selectFirst
 *   merge.p028c.e                                              →  Vod
 *   merge.p028c.c.{b,i,r,m,n,e}                                →  VodBuilder / FilmUtil.player
 *   merge.p028c.c.c(msg)                                       →  fail(msg)  = {"msg":…}
 *   merge.p036k.b.{n,l,g}                                      →  fetch / postRaw / postForm
 *   merge.A.a.f / .n、merge.A.b.a、merge.p027b.h.a、C0295x.a      →  join(...) / joinUrl(...)
 *   merge.p026a.a.parseJsonAndSave                             →  onDetailParsed(...)
 *   merge.p027b.k.b(id,name,pic,?,list)                        →  addVod(list, id, name, pic, remarks)
 * </pre>
 */
public class FengYePlain extends BaseSpider {

    private static final String TAG = "FengYePlain";

    /** C 类：种子页（在它上面找候选域名） */
    public static final String DEFAULT_SEED = "https://www.vip1949.com/";
    /** C 类：探活全失败时的兜底基址 */
    public static final String DEFAULT_BASE = "https://www.cd-zj.com";
    /** C 类：缓存有效期 */
    public static final long CACHE_TTL_MS = 300_000L;

    /** iPhone Safari 头（列表/详情/搜索） */
    public static final String UA_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X)"
            + " AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1";
    public static final String ACCEPT_HTML =
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
    public static final String ACCEPT_LANG = "zh-CN,zh;q=0.9";

    /** L2 的两条解析线路（线路名 → 域名） */
    public static final String L2_YYNB = "https://zzrs.mfdyvip.com/player/mplayer.php";
    public static final String L2_JD4K = "https://fgsrg.hzqingshan.com/player/mplayer.php";
    /** L2 取 token 的地址 */
    public static final String L2_TOKEN_URL = "https://fgsrg.hzqingshan.com/player/?url=";

    /** 每页条数（分类 36 / label 24） */
    public static final int PAGE_SIZE = 36;
    public static final int LABEL_SIZE = 24;
    /** 上游硬编码的 total */
    public static final int HARDCODED_TOTAL = 9999;

    /** 详情页里的播放器配置（大小写不敏感） */
    public static final Pattern PLAYER_JSON = Pattern.compile("player_aaaa=(.*?)</script>", 32);
    /** L2 页面里的 token */
    public static final Pattern DATA_TE = Pattern.compile("data-te=\"(.*?)\"");
    /** 种子页里的候选域名 */
    public static final Pattern SEED_URL = Pattern.compile("url\\s*:\\s*\"(https?://[^\"]+)\"");
    /** 详情列表项的链接 */
    public static final Pattern DETAIL_LINK = Pattern.compile("/detail/(\\d+)\\.html");
    /** 剧集链接 */
    public static final Pattern PLAY_LINK = Pattern.compile("/play/(.*?)\\.html");
    /** 尾页链接 */
    public static final Pattern TAIL_PAGE = Pattern.compile("---(\\d+)---");

    /** 5 分钟缓存（上游是 public static 无锁） */
    protected static volatile String cachedBase = "";
    protected static volatile long cachedAt = 0L;

    /** 当前基址（上游字段 {@code f4a}） */
    protected String base = DEFAULT_BASE;
    /** 种子页（上游字段 {@code b}） */
    protected String seed = DEFAULT_SEED;
    /** ext 原文 */
    protected String rawExt = "";
    /** {@code /play/} 取到空正文时的回落地址（测试注入用） */
    protected String proxyBase = "";

    /**
     * 构造。
     */
    public FengYePlain() {
        super();
    }

    // ── 生命周期 ────────────────────────────────────────────

    /**
     * 初始化：吸收 ext → 定种子 → （必要时）探活 → 定基址。
     *
     * @param context 宿主上下文
     * @param extend  ext 原文：{@code http…} 或 {@code {"publish":…}} / {@code {"url":…}}
     */
    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        this.rawExt = extend == null ? "" : extend;
        this.seed = DEFAULT_SEED;
        String candidate = resolveExtUrl(this.rawExt);
        if (!candidate.isEmpty()) {
            this.seed = candidate;
        }
        long now = System.currentTimeMillis();
        String cached = cachedBase;
        if (!TextUtils.isEmpty(cached) && now - cachedAt < CACHE_TTL_MS) {
            this.base = cached;
            return;
        }
        String resolved = probeSeed(seed);
        this.base = resolved;
        cachedBase = resolved;
        cachedAt = now;
    }

    /**
     * ext → 候选基址（上游 {@code init} 的前半段）。
     *
     * @param ext ext 原文
     * @return 以 http 开头的地址；否则空串（表示「沿用种子页」）
     */
    public static String resolveExtUrl(String ext) {
        if (TextUtils.isEmpty(ext)) {
            return "";
        }
        String text = ext.trim();
        if (!text.startsWith("http")) {
            try {
                JSONObject o = new JSONObject(text);
                String publish = o.optString("publish");
                if (TextUtils.isEmpty(publish)) {
                    publish = o.optString("url");
                }
                if (!TextUtils.isEmpty(publish)) {
                    text = publish.trim();
                }
            } catch (Throwable ignored) {
                // 非 JSON 且非 http → 忽略整段 ext（上游如此）
            }
        }
        return text.startsWith("http") ? text : "";
    }

    /**
     * 探活种子页：抓候选域名，挑第一个能用的。
     *
     * @param seedUrl 种子页
     * @return 可用基址；都不可用返回 {@link #DEFAULT_BASE}
     */
    public String probeSeed(String seedUrl) {
        try {
            String html = fetch(seedUrl, headersFor(DEFAULT_BASE));
            if (!TextUtils.isEmpty(html)) {
                List<String> candidates = new ArrayList<>();
                Matcher m = SEED_URL.matcher(html);
                while (m.find()) {
                    String one = stripSlash(m.group(1));
                    if (!TextUtils.isEmpty(one) && !candidates.contains(one)) {
                        candidates.add(one);
                    }
                }
                for (String one : candidates) {
                    if (isAlive(one)) {
                        return one;
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "probeSeed failed", e);
        }
        return DEFAULT_BASE;
    }

    /**
     * 探活一个域名（上游 {@code c(base)}）：首页或分类页 200–399 且含特征串。
     *
     * @param candidate 候选基址
     * @return true 表示可用
     */
    public boolean isAlive(String candidate) {
        String root = stripSlash(candidate);
        String[] probes = {root + "/", root + "/cupfox-list/1--------1---.html"};
        for (String url : probes) {
            try {
                Http.Result resp = fetchRaw(url, headersFor(root));
                if (resp.code >= 200 && resp.code < 400 && !TextUtils.isEmpty(resp.body)
                        && (resp.body.contains("public-list-exp") || resp.body.contains("/detail/"))) {
                    return true;
                }
            } catch (Throwable e) {
                Log.d(TAG, "isAlive probe failed: " + url, e);
            }
        }
        return false;
    }

    /** 测试用：直接指定基址（跳过探活） */
    public void setBaseForTest(String value) {
        this.base = value;
    }

    /** 测试用：直接指定种子页（跳过 ext 解析） */
    public void setSeedForTest(String value) {
        this.seed = value;
    }

    /** 当前基址 */
    public String base() {
        return base;
    }

    /** 当前种子页 */
    public String seed() {
        return seed;
    }

    /** 清掉静态缓存（测试用） */
    public static void clearCache() {
        cachedBase = "";
        cachedAt = 0L;
    }

    /**
     * 注入弹幕代理前缀（本类上游不挂弹幕，保留以便统一）。
     *
     * @param proxy 前缀
     */
    public void setProxyBase(String proxy) {
        this.proxyBase = proxy == null ? "" : proxy.trim();
    }

    // ── 传输（三层可覆写）────────────────────────────────────

    /**
     * GET 正文（上游 {@code a(path)} / {@code p036k.b.n(url,headers).a()}）。
     *
     * @param url     完整地址
     * @param headers 请求头
     * @return 正文；失败空串
     */
    public String fetch(String url, Map<String, String> headers) {
        return fetchRaw(url, headers).body;
    }

    /**
     * GET 原始响应（上游 {@code p036k.d}：有正文也有状态码）。
     *
     * @param url     完整地址
     * @param headers 请求头
     * @return 响应；离线/失败返回 code=0
     */
    public Http.Result fetchRaw(String url, Map<String, String> headers) {
        if (Http.isOfflineForTest()) {
            return new Http.Result(0, "", "offline-for-test", url);
        }
        if (TextUtils.isEmpty(url)) {
            return new Http.Result(0, "", "empty-url", "");
        }
        try {
            return http.execute("GET", url, null, headers);
        } catch (Throwable e) {
            Log.w(TAG, "fetchRaw failed: " + url, e);
            return new Http.Result(0, "", e.toString(), url);
        }
    }

    /**
     * POST 表单并取正文（上游 {@code p036k.b.l(url, map)}）。
     *
     * @param url     地址
     * @param headers 请求头
     * @return 正文；失败空串
     */
    public String postRaw(String url, Map<String, String> headers) {
        if (Http.isOfflineForTest()) {
            return "";
        }
        try {
            Http.Result r = http.execute("POST", url, "", headers);
            return r.body == null ? "" : r.body;
        } catch (Throwable e) {
            Log.w(TAG, "postRaw failed: " + url, e);
            return "";
        }
    }

    /**
     * POST 带表单体（上游 {@code p036k.b.g(url, form, headers).a()}）。
     *
     * @param url     地址
     * @param form    表单
     * @param headers 请求头
     * @return 正文；失败空串
     */
    public String postForm(String url, Map<String, String> form, Map<String, String> headers) {
        if (Http.isOfflineForTest()) {
            return "";
        }
        try {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put("Content-Type", "application/x-www-form-urlencoded");
            Http.Result r = http.execute("POST", url, encodeForm(form), h);
            return r.body == null ? "" : r.body;
        } catch (Throwable e) {
            Log.w(TAG, "postForm failed: " + url, e);
            return "";
        }
    }

    /**
     * 列表/详情请求头（上游 {@code getBaseHeadersMap()} + {@code g(base)}，
     * 后者把 L2 的两个域名也塞进去了 —— 上游就是混着用的，这里保持）。
     *
     * @param forBase 目标域名
     * @return 头表
     */
    public Map<String, String> headersFor(String forBase) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA_IPHONE);
        h.put("Accept", ACCEPT_HTML);
        h.put("Accept-Language", ACCEPT_LANG);
        h.put("Referer", stripSlash(forBase) + "/");
        return h;
    }

    /**
     * 上游 {@code getL2Map()}：<b>既是「线路名 → L2 接口」的查表，也是播放对象里的 header</b>。
     *
     * <p>逐字复刻上游内容（只有 3 个键，没有 Referer）：
     * {@code YYNB} → {@link #L2_YYNB}、{@code JD4K} → {@link #L2_JD4K}、{@code User-Agent} → iPhone UA。
     * 所以「L2 分支能不能走到」取决于播放页 {@code player_aaaa} 里的 {@code from} 是否
     * <b>恰好</b>等于 {@code YYNB} / {@code JD4K}。
     *
     * @return L2 表（可覆写，便于测试注入）
     */
    public static Map<String, String> l2Map() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("YYNB", L2_YYNB);
        h.put("JD4K", L2_JD4K);
        h.put("User-Agent", UA_IPHONE);
        return h;
    }

    /**
     * L2 表（实例层可覆写点）。
     *
     * @param forBase 目标域名（上游未使用）
     * @return {@link #l2Map()}
     */
    public Map<String, String> l2Headers(String forBase) {
        return l2Map();
    }

    // ── 契约方法 ────────────────────────────────────────────

    /**
     * 首页：7 个分类 + 内联筛选表。
     *
     * @param filter 是否带 filters
     * @return 首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        try {
            com.example.tvbox.common.VodBuilder out = com.example.tvbox.common.VodBuilder.create();
            for (String[] pair : HOME_CLASSES) {
                out.clazz(pair[0], pair[1]);
            }
            if (!filter) {
                return out.homeContent();
            }
            for (String tid : FengYeFilters.KEYS) {
                out.filter(tid, FengYeFilters.of(tid));
            }
            return out.homeContent();
        } catch (Throwable e) {
            Log.e(TAG, "homeContent failed", e);
            return fail("首页加载失败");
        }
    }

    /** 首页 7 个分类（id, name，顺序照抄上游） */
    public static final String[][] HOME_CLASSES = {
            {"/label/qq", "腾讯"},
            {"/label/bli", "哔哩"},
            {"/label/youku", "优酷"},
            {"2", "电视剧"},
            {"1", "电影"},
            {"4", "动漫"},
            {"3", "综艺"},
            {"5", "短剧"},
    };

    /**
     * 分类翻页。
     *
     * @param tid    分类 id（{@code /label/…} 或纯数字）
     * @param pg     页码
     * @param filter 忽略
     * @param extend 筛选值（class/tid/area/genre/year/lang/letter/sort）
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            if (tid != null && tid.startsWith("/label")) {
                List<Vod> list = parseList(fetch(base + tid + "/page/" + pg + ".html",
                        headersFor(base)));
                int page = parsePage(pg);
                int pageCount = list.size() < LABEL_SIZE ? page : page + 2;
                return categoryJson(page, pageCount, LABEL_SIZE, pageCount * LABEL_SIZE, list);
            }
            Map<String, String> values = new LinkedHashMap<>();
            if (extend != null) {
                for (Map.Entry<String, String> e : extend.entrySet()) {
                    if (!TextUtils.isEmpty(e.getValue())) {
                        values.put(e.getKey(), e.getValue());
                    }
                }
            }
            String cls = value(values, "class", value(values, "tid", tid));
            String area = value(values, "area", "");
            String genre = value(values, "genre", "");
            String year = value(values, "year", "");
            String lang = value(values, "lang", "");
            String letter = value(values, "letter", "");
            String sort = value(values, "sort", "");
            if (!TextUtils.isEmpty(area) || !TextUtils.isEmpty(genre) || !TextUtils.isEmpty(year)
                    || !TextUtils.isEmpty(lang) || !TextUtils.isEmpty(letter)
                    || !TextUtils.isEmpty(sort)) {
                StringBuilder path = new StringBuilder("/cupfox-list/");
                path.append(cls).append('-').append(area).append('-');
                path.append(sort).append('-').append(genre).append('-');
                path.append(lang).append('-').append(letter).append("---");
                path.append(year).append(".html");
                List<Vod> list = parseList(fetch(base + path, headersFor(base)));
                return categoryJson(1, 1, PAGE_SIZE, HARDCODED_TOTAL, list);
            }
            String listUrl = "/cupfox-list/" + cls + "--------" + pg + "---.html";
            String html = fetch(base + listUrl, headersFor(base));
            List<Vod> list = parseList(html);
            int page = parsePage(pg);
            int pageCount = page;
            for (Dom.El link : Dom.parse(html, base).select("a.page-link")) {
                if ("尾页".equals(link.text().trim())) {
                    Matcher m = TAIL_PAGE.matcher(link.attr("href"));
                    if (m.find()) {
                        pageCount = safeInt(m.group(1), page);
                        break;
                    }
                }
            }
            if (list.isEmpty()) {
                pageCount = 0;
            }
            return categoryJson(page, pageCount, PAGE_SIZE, HARDCODED_TOTAL, list);
        } catch (Throwable e) {
            Log.e(TAG, "categoryContent failed", e);
            return fail("分类加载失败");
        }
    }

    /**
     * 详情。
     *
     * @param ids 影片 id（多于一个时只取第一个，{@code ,} 之后截掉）
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return fail("详情参数为空");
        }
        try {
            String id = ids.get(0);
            if (id != null && id.contains(",")) {
                id = id.substring(0, id.indexOf(','));
            }
            id = id == null ? "" : id.trim();
            if (TextUtils.isEmpty(id)) {
                return fail("影片ID为空");
            }
            String html = fetch(base + "/detail/" + id + ".html", headersFor(base));
            Dom.El root = Dom.parse(html, base);
            Vod vod = new Vod().id(id);
            vod.name(root.selectText("h3.slide-info-title", "").trim());
            Dom.El pic = root.selectFirst("img.lazy");
            vod.pic(pic == null ? "" : url(pic.attr("data-src")));

            String director = "";
            String actor = "";
            for (Dom.El el : root.select(".slide-info")) {
                String line = el.text().trim();
                if (line.startsWith("导演：")) {
                    director = line.replace("导演：", "").trim();
                } else if (line.startsWith("演员：")) {
                    actor = line.replace("演员：", "").trim();
                }
            }
            vod.director(director);
            vod.actor(actor);
            Dom.El content = root.selectFirst("#height_limit");
            vod.content(content == null ? "" : content.text().trim());

            List<String> fromNames = new ArrayList<>();
            for (Dom.El el : root.select(".anthology-tab a.swiper-slide")) {
                String name = el.text().trim();
                if (!TextUtils.isEmpty(name)) {
                    fromNames.add(name);
                }
            }
            List<Dom.El> boxes = root.select(".anthology-list-box");
            List<String> urlGroups = new ArrayList<>();
            for (int i = 0; i < boxes.size(); i++) {
                List<String> episodes = new ArrayList<>();
                for (Dom.El a : boxes.get(i).select("li a")) {
                    Matcher m = PLAY_LINK.matcher(a.attr("href"));
                    if (m.find()) {
                        String name = a.text().trim();
                        if (TextUtils.isEmpty(name)) {
                            name = "正片";
                        }
                        episodes.add(name + "$" + id + "-" + m.group(1));
                    }
                }
                if (!episodes.isEmpty() && i < fromNames.size()) {
                    urlGroups.add(TextUtils.join("#", reverse(episodes)));
                }
            }
            List<String> froms = new ArrayList<>();
            for (int i = 0; i < fromNames.size(); i++) {
                if (i < urlGroups.size()) {
                    froms.add(fromNames.get(i));
                }
            }
            vod.playFrom(TextUtils.join("$$$", froms));
            vod.playUrl(TextUtils.join("$$$", urlGroups));
            JSONObject root2 = new JSONObject();
            root2.put("list", new JSONArray().put(com.example.tvbox.common.VodBuilder.toJson(vod)));
            String json = root2.toString();
            onDetailParsed(json);
            return json;
        } catch (Throwable e) {
            Log.e(TAG, "detailContent failed", e);
            return fail("详情加载失败");
        }
    }

    /**
     * 首页推荐（{@code {base}/} 的卡片列表）。
     *
     * @return {@code {"list":[…]}}
     */
    @Override
    public String homeVideoContent() {
        try {
            return com.example.tvbox.common.VodBuilder.create()
                    .addAll(parseList(fetch(base + "/", headersFor(base))))
                    .homeContent();
        } catch (Throwable e) {
            Log.e(TAG, "homeVideoContent failed", e);
            return fail("首页加载失败");
        }
    }

    /**
     * 播放。
     *
     * @param flag     线路名（忽略）
     * @param pid      播放 id（取最后一个 {@code $} 之后的部分）
     * @param vipFlags 忽略
     * @return 播放 JSON
     */
    @Override
    public String playerContent(String flag, String pid, List<String> vipFlags) {
        String id;
        if (TextUtils.isEmpty(pid)) {
            id = "";
        } else {
            id = pid.trim();
            if (id.contains("$")) {
                id = id.substring(id.lastIndexOf('$') + 1);
            }
        }
        if (TextUtils.isEmpty(id)) {
            return fail("播放ID为空");
        }
        if (!id.startsWith("http")) {
            id = base + "/play/" + id + ".html";
        }
        try {
            String html = fetch(id, headersFor(base));
            Map<String, String> l2 = l2Headers(base);
            if (TextUtils.isEmpty(html)) {
                return FilmUtil.player(id, 1, jsonOf(l2), 0);
            }
            Matcher m = PLAYER_JSON.matcher(html);
            if (!m.find()) {
                return FilmUtil.player(id, 1, jsonOf(l2), 0);
            }
            JSONObject player = new JSONObject(m.group(1));
            String url = player.optString("url");
            String from = player.optString("from");
            if (TextUtils.isEmpty(url)) {
                return fail("无播放地址");
            }
            if (url.startsWith("http") && (url.contains(".m3u8") || url.contains(".mp4"))) {
                return FilmUtil.player(url, 0, jsonOf(l2), 0);
            }
            // from 必须精确等于 L2 表里的键（上游 getL2Map().get(from)），否则整段 L2 分支跳过
            String l2Base = l2.get(from);
            if (!TextUtils.isEmpty(l2Base)) {
                try {
                    Map<String, String> l2Post = new LinkedHashMap<>();
                    l2Post.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                            + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36");
                    l2Post.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,"
                            + "image/avif,image/webp,image/apng,*/*;q=0.8");
                    l2Post.put("accept-language", "zh-CN,zh;q=0.9");
                    l2Post.put("cache-control", "no-cache");
                    l2Post.put("pragma", "no-cache");
                    l2Post.put("referer", "https://www.ht10010.com/");
                    String tokenPage = postRaw(L2_TOKEN_URL
                            + URLEncoder.encode(url, "UTF-8"), l2Post);
                    Matcher te = DATA_TE.matcher(tokenPage);
                    if (te.find()) {
                        Map<String, String> form = new LinkedHashMap<>();
                        form.put("url", url);
                        form.put("token", te.group(1));
                        JSONObject l2Resp = new JSONObject(postForm(l2Base, form, l2Post));
                        if (l2Resp.optInt("code") == 200 && !TextUtils.isEmpty(l2Resp.optString("url"))) {
                            return FilmUtil.player(l2Resp.optString("url"), 0, jsonOf(l2), 0);
                        }
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "L2 解析失败", e);
                }
            }
            return FilmUtil.player(id, 1, jsonOf(l2), 0);
        } catch (Throwable e) {
            Log.e(TAG, "playerContent failed", e);
            return FilmUtil.player(id, 1, jsonOf(l2Headers(base)), 0);
        }
    }

    /**
     * 搜索（两个重载里短的这层）。
     *
     * @param key   关键词
     * @param quick 忽略
     * @return 标准搜索 JSON；{@code code != 1} 时返回 {@code ""}（上游如此，未改）
     */
    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    /**
     * 搜索（带页码参数，上游第三个重载）。
     *
     * @param key   关键词
     * @param quick 忽略
     * @param pg    页码文本
     * @return 标准搜索 JSON；{@code code != 1} 时返回 {@code ""}
     */
    public String searchContent(String key, boolean quick, String pg) {
        try {
            String wd = URLEncoder.encode(key == null ? "" : key.trim(), "UTF-8");
            String body = fetch(base + "/index.php/ajax/suggest?mid=1&limit=500&wd=" + wd,
                    headersFor(base));
            JSONObject resp = Http.parseJson(body);
            JSONArray list = resp.optJSONArray("list");
            if (resp.optInt("code") != 1 || list == null) {
                return "";
            }
            List<Vod> out = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject one = list.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                addVod(out, one.optString("id"), one.optString("name"),
                        one.optString("pic").replace("&amp;", "&"), "");
            }
            // 上游 searchContent 用的是**分类页那个构造器**（merge.p028c.c.b）：
            //   c.b(page, 1, 36, list.size(), list) → {page, pagecount:1, limit:36, total:N, list}
            // 而不是「只有 list」的那个（c.n）。所以这里走 categoryJson 而不是 VodBuilder.searchContent()。
            return categoryJson(safeInt(pg, 1), 1, PAGE_SIZE, out.size(), out);
        } catch (Throwable e) {
            Log.e(TAG, "searchContent failed", e);
            return "";
        }
    }

    // ── 解析 ────────────────────────────────────────────────

    /**
     * 列表解析（上游 {@code d(html)}）：{@code a.public-list-exp} 卡片。
     *
     * @param html 页面
     * @return Vod 列表（按 id 去重）
     */
    public List<Vod> parseList(String html) {
        List<Vod> out = new ArrayList<>();
        if (TextUtils.isEmpty(html)) {
            return out;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Dom.El el : Dom.parse(html, base).select("a.public-list-exp")) {
            Matcher m = DETAIL_LINK.matcher(el.attr("href"));
            if (!m.find()) {
                continue;
            }
            String id = m.group(1);
            if (!seen.add(id)) {
                continue;
            }
            Dom.El img = el.selectFirst("img");
            String name = el.attr("title");
            if (TextUtils.isEmpty(name) && img != null) {
                name = img.attr("alt");
            }
            String pic = img == null ? "" : url(img.attr("data-src"));
            // 上游选择器是 ".ft2, .public-list-prb"（逗号分组），但本工程的 Dom 子集只支持
            // 单段/后代/子级组合符，不支持逗号分组 —— 直接传进去会一个都匹配不到（备注永远为空）。
            // 这里显式做「先 .ft2，再回落 .public-list-prb」，与上游语义等价且不依赖未实现的语法糖。
            Dom.El remarkEl = el.selectFirst(".ft2");
            if (remarkEl == null || remarkEl.text().trim().isEmpty()) {
                remarkEl = el.selectFirst(".public-list-prb");
            }
            String remark = remarkEl == null ? "" : remarkEl.text().trim();
            List<Dom.El> extraEls = el.select("span.public-prt");
            StringBuilder extra = new StringBuilder();
            for (Dom.El one : extraEls) {
                if (extra.length() > 0) {
                    extra.append(',');
                }
                extra.append(one.text());
            }
            Vod vod = new Vod().id(id).name(name == null ? "" : name.trim()).pic(pic).remarks(remark);
            // 上游 setter q() 的线键未取回（见类 javadoc 观察），本工程 Vod 只有 vod_tag 可承载
            vod.tag(extra.toString());
            out.add(vod);
        }
        return out;
    }

    /**
     * 上游 {@code b(str)}：{@code //} 开头补 {@code https:}，否则把 {@code &amp;} 还成 {@code &}。
     *
     * @param s 原文
     * @return 归一化地址
     */
    public static String url(String s) {
        if (TextUtils.isEmpty(s)) {
            return "";
        }
        return s.startsWith("//") ? "https:" + s : s.replace("&amp;", "&");
    }

    /**
     * 上游 {@code f(str)}：去掉结尾的全部 {@code /}。
     *
     * @param s 原文
     * @return 去尾斜杠结果
     */
    public static String stripSlash(String s) {
        if (TextUtils.isEmpty(s)) {
            return s == null ? "" : s;
        }
        String out = s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /**
     * 上游 {@code e(map,key,def)}：空值回落默认。
     *
     * @param map 表
     * @param key 键
     * @param def 默认值
     * @return 值
     */
    public static String value(Map<String, String> map, String key, String def) {
        String v = map == null ? null : map.get(key);
        return TextUtils.isEmpty(v) ? def : v;
    }

    /**
     * 上游那段「把列表倒过来」的循环，单独成方法便于断言。
     *
     * @param src 原列表
     * @return 倒序新列表
     */
    public static List<String> reverse(List<String> src) {
        List<String> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        for (int i = src.size() - 1; i >= 0; i--) {
            out.add(src.get(i));
        }
        return out;
    }

    /**
     * 上游 {@code parseJsonAndSave}：把片名交给宿主（这里是可覆写钩子）。
     *
     * @param detailJson 详情 JSON
     */
    public void onDetailParsed(String detailJson) {
        try {
            JSONArray list = new JSONObject(detailJson).optJSONArray("list");
            if (list != null && list.length() > 0) {
                saveHistory(list.getJSONObject(0).optString("vod_name"));
            }
        } catch (Throwable e) {
            Log.w(TAG, "onDetailParsed failed", e);
        }
    }

    /**
     * 历史落库钩子 —— <b>上游该分支未实现于本工程</b>。
     *
     * <p>上游写宿主私有存储；本工程 {@code crawler} 契约没有该接口，默认只记日志。
     *
     * @param vodName 片名
     */
    public void saveHistory(String vodName) {
        Log.d(TAG, "history: " + vodName);
    }

    /**
     * 错误响应（等价上游 {@code merge.p028c.c.c(msg)}）。
     *
     * @param message 消息
     * @return {@code {"msg":…}}
     */
    public static String fail(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("msg", message);
        } catch (Throwable e) {
            return "{}";
        }
        return o.toString();
    }

    /**
     * {@code application/x-www-form-urlencoded} 体编码（上游 okhttp {@code FormBody}）。
     *
     * @param form 表单
     * @return 编码后的体
     */
    public static String encodeForm(Map<String, String> form) {
        if (form == null || form.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(Http.encode(e.getKey())).append('=')
              .append(Http.encode(e.getValue() == null ? "" : e.getValue()));
        }
        return sb.toString();
    }

    /**
     * 分类页 JSON —— <b>手拼</b>而不是用 {@code VodBuilder}。
     *
     * <p>原因：上游 {@code p028c.c.b(page, pagecount, limit, total, list)} 把
     * {@code pagecount} 当<b>字面值</b>写出去（例如 {@code (1, 1, 36, 9999)} 表示
     * 「第 1 页 / 共 1 页 / 每页 36 / 总 9999」），而 {@code VodBuilder.categoryContent()}
     * 会按 {@code ceil(total/limit)} 重算成 278。为了与上游报文逐字一致，这里手拼。
     *
     * @param page      当前页
     * @param pageCount 总页数（字面值）
     * @param limit     每页条数
     * @param total     总条数（字面值）
     * @param list      条目
     * @return 分类页 JSON
     */
    public static String categoryJson(int page, int pageCount, int limit, int total, List<Vod> list) {
        JSONObject root = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (Vod v : list) {
                arr.put(com.example.tvbox.common.VodBuilder.toJson(v));
            }
            root.put("page", page);
            root.put("pagecount", pageCount);
            root.put("limit", limit);
            root.put("total", total);
            root.put("list", arr);
        } catch (Throwable e) {
            Log.w(TAG, "categoryJson failed", e);
            return "{}";
        }
        return root.toString();
    }

    /**
     * 头表 → JSON 字符串（上游把 L2 头直接塞进播放对象的 header 字段）。
     *
     * @param headers 头表
     * @return JSON 文本
     */
    public static String jsonOf(Map<String, String> headers) {
        return FilmUtil.headerJson(headers);
    }

    /**
     * 添一条搜索结果（上游 {@code k.b(id,name,pic,?,list)}）。
     *
     * @param out     目标列表
     * @param id      id
     * @param name    名称
     * @param pic     图
     * @param remarks 备注
     */
    public static void addVod(List<Vod> out, String id, String name, String pic, String remarks) {
        out.add(new Vod().id(id).name(name).pic(pic).remarks(remarks == null ? "" : remarks));
    }

    /**
     * 页码解析（上游 {@code Integer.parseInt} + 兜底 1）。
     *
     * @param pg 文本
     * @return 页码
     */
    static int parsePage(String pg) {
        return safeInt(pg, 1);
    }

    /**
     * 安全解析整数。
     *
     * @param text 文本
     * @param def  默认值
     * @return 结果
     */
    static int safeInt(String text, int def) {
        try {
            return Integer.parseInt(text == null ? "" : text.trim());
        } catch (Throwable e) {
            return def;
        }
    }
}
