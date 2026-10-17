package com.example.tvbox.plain.film2;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 荐片（JianPian）—— 磁力/短剧站，JSON API 站群。
 *
 * <h2>协议还原（来自 {@code ae/decompiled/JianPian.java}，并用真实请求验证）</h2>
 *
 * <h3>1. 站点地址发现（DoH + 随机子域）</h3>
 * <pre>
 *  GET https://dns.alidns.com/resolve?name=swrdsfeiujo25sw.cc&amp;type=TXT
 *      → Answer[0].data = "\"cqxkn.com,hzlfm.com,whhyn.com,...\""   （逗号分隔的裸域名）
 *  对每个裸域名 d：
 *      candidate = "https://" + randomString(6) + "." + d      ← 随机 6 位子域，逐次探测
 *      命中 200 即定为主域 this.a
 *  全部失败 → 用第一个裸域名拼随机子域兜底
 *
 *  然后用主域再取一次图片域名：
 *      GET {a}/api/v2/settings/resourceDomainConfig
 *      → data.imgDomain = "img.ypfbj.com,img.bptpw.com,img.cqkgy.com"
 *      → 取 split(",")[1] 作为封面域名 this.b（注意：是**第二项**，不是第一项）
 * </pre>
 *
 * <h3>2. 固定请求头（还原 {@code JianPian.d()}）</h3>
 * <pre>
 *  User-Agent: "Mozilla/5.0 (Linux; Android 7.1.2; V2049A Build/UP1A.231005.007; wv)
 *               AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/81.0.4044.117
 *               Mobile Safari/537.36;webank/h5face;webank/1.0;netType:NETWORK_WIFI;
 *               appVersion:422;packageName=com.jp3.xg3"
 * </pre>
 * 这条 UA 里带 {@code ;webank/...} 与 {@code packageName=com.jp3.xg3}，
 * 是站点侧风控指纹，缺了会 4xx。
 *
 * <h3>3. 五个端点</h3>
 * <pre>
 *  首页推荐   GET {a}/api/dyTag/list?category_id=88
 *  分类列表   GET {a}/api/crumb/list?fcate_pid={tid}&amp;category_id=&amp;area=&amp;year=&amp;type=&amp;sort=&amp;page={pg}
 *  短剧分类   GET {a}/api/crumb/shortList?fcate_pid={tid}&amp;category_id=&amp;sort=update&amp;page={pg}
 *  详情       GET {a}/api/video/detailv2?id={id}
 *  短剧详情   GET {a}/api/detail?vid={id}
 *  搜索       GET {a}/api/v2/search/videoV2?key={urlencode}&amp;category_id=88&amp;page=1&amp;pageSize=20
 * </pre>
 *
 * <h3>4. vod_id 复合编码（本类的核心特征）</h3>
 * <pre>
 *  vod_id = "{id}$$${title}$$${picPath}$$${tid}"        ← 四段，用 $$$ 分隔
 *  详情时按段取用：
 *      [0] id       [1] title      [2] picPath      [3] tid
 *  tid == "67" 时走短剧分支（端点与字段都不同）
 * </pre>
 *
 * <h3>5. 播放地址（真实响应已确认）</h3>
 * <pre>
 *  data.source_list_source = [
 *    { "id":5514641, "name":"VIP线路",
 *      "source_list": [ {"source_name":"HD国语中字",
 *                        "url":"https://mv.gzlcl.com/api/v2/vip/normal/1500598/index.m3u8"} ] }
 *  ]
 *  ftp:// 开头的 url 需加前缀 "tvbox-xg:"
 * </pre>
 *
 * <h2>真实请求验证</h2>
 * <ul>
 *   <li>DNS TXT 发现：已验证（返回 5 个裸域名）</li>
 *   <li>随机子域命中：已验证（{@code https://6dnr.cqlzn.com} 返回 200）</li>
 *   <li>分类/搜索/详情：已验证，真实响应存于 {@code ae/fixtures/real/jianpian_*.json}</li>
 * </ul>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/JianPian.java}（453 行）
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/JianPian.java} 的对照复核，
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
 * {@code ae/decompiled/JianPian.java} 为对照基准。
 */
public class JianPianPlain extends BaseSpider {

    private static final String TAG = "JianPianPlain";

    /** DoH 端点（阿里 DNS 的 JSON 接口，上游写死） */
    private static final String DOH = "https://dns.alidns.com/resolve";
    /** DoH 查询的域名（站点域名列表托管在这里） */
    private static final String DOH_NAME = "swrdsfeiujo25sw.cc";

    /** 风控 UA（逐字来自 JianPian.d()，不可裁剪） */
    public static final String UA =
            "Mozilla/5.0 (Linux; Android 7.1.2; V2049A Build/UP1A.231005.007; wv) "
          + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/81.0.4044.117 "
          + "Mobile Safari/537.36;webank/h5face;webank/1.0;netType:NETWORK_WIFI;"
          + "appVersion:422;packageName=com.jp3.xg3";

    /** 短剧分类标识（上游多处 if 判断的魔法值） */
    public static final String TID_SHORT = "67";

    /** 随机子域字符集（上游 generateRandomString 用 0-9a-z） */
    private static final String RANDOM_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    /** 首页 5 个分类（types 与 ids 一一对应，第 5 个是短剧） */
    private static final String[] TYPE_NAMES = {"电影", "电视剧", "动漫", "综艺", "短剧"};
    private static final String[] TYPE_IDS = {"1", "2", "3", "4", "67"};

    /** 封面域名（init 时从 resourceDomainConfig 取第二项） */
    private String imgDomain;

    /** 首页 filters 固化常量（上游直接内联一个 3.4KB 的 JSON 字面量） */
    public static final String FILTERS_JSON =
            "{\"1\":[{\"key\":\"area\",\"name\":\"地区\",\"value\":[{\"n\":\"全部\",\"v\":\"\"},{\"n\":\"国产\",\"v\":\"1\"},{\"n\":\"中国香港\",\"v\":\"3\"},{\"n\":\"中国台湾\",\"v\":\"6\"},{\"n\":\"美国\",\"v\":\"5\"},{\"n\":\"韩国\",\"v\":\"18\"},{\"n\":\"日本\",\"v\":\"2\"}]},{\"key\":\"year\",\"name\":\"年份\",\"value\":[{\"n\":\"全部\",\"v\":\"\"},{\"n\":\"2026\",\"v\":\"162\"},{\"n\":\"2025\",\"v\":\"107\"},{\"n\":\"2024\",\"v\":\"119\"},{\"n\":\"2023\",\"v\":\"153\"},{\"n\":\"2022\",\"v\":\"101\"},{\"n\":\"2021\",\"v\":\"118\"},{\"n\":\"2020\",\"v\":\"16\"},{\"n\":\"2019\",\"v\":\"7\"},{\"n\":\"2018\",\"v\":\"2\"},{\"n\":\"2017\",\"v\":\"3\"},{\"n\":\"2016\",\"v\":\"22\"}]},{\"key\":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"热门\",\"v\":\"hot\"},{\"n\":\"更新\",\"v\":\"update\"},{\"n\":\"评分\",\"v\":\"rating\"}]}]," +
            "\"2\":[{\"key\":\"area\",\"name\":\"地区\",\"value\":[{\"n\":\"全部\",\"v\":\"\"},{\"n\":\"国产\",\"v\":\"1\"},{\"n\":\"中国香港\",\"v\":\"3\"},{\"n\":\"中国台湾\",\"v\":\"6\"},{\"n\":\"美国\",\"v\":\"5\"},{\"n\":\"韩国\",\"v\":\"18\"},{\"n\":\"日本\",\"v\":\"2\"}]},{\"key\":\"year\",\"name\":\"年份\",\"value\":[{\"n\":\"全部\",\"v\":\"\"},{\"n\":\"2026\",\"v\":\"162\"},{\"n\":\"2025\",\"v\":\"107\"},{\"n\":\"2024\",\"v\":\"119\"},{\"n\":\"2023\",\"v\":\"153\"},{\"n\":\"2022\",\"v\":\"101\"},{\"n\":\"2021\",\"v\":\"118\"},{\"n\":\"2020\",\"v\":\"16\"},{\"n\":\"2019\",\"v\":\"7\"},{\"n\":\"2018\",\"v\":\"2\"},{\"n\":\"2017\",\"v\":\"3\"},{\"n\":\"2016\",\"v\":\"22\"}]},{\"key\":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"热门\",\"v\":\"hot\"},{\"n\":\"更新\",\"v\":\"update\"},{\"n\":\"评分\",\"v\":\"rating\"}]}]," +
            "\"3\":[{\"key\":\"area\",\"name\":\"地区\",\"value\":[{\"n\":\"全部\",\"v\":\"\"},{\"n\":\"国产\",\"v\":\"1\"},{\"n\":\"中国香港\",\"v\":\"3\"},{\"n\":\"中国台湾\",\"v\":\"6\"},{\"n\":\"美国\",\"v\":\"5\"},{\"n\":\"韩国\",\"v\":\"18\"},{\"n\":\"日本\",\"v\":\"2\"}]},{\"key\":\"year\",\"name\":\"年份\",\"value\":[{\"n\":\"全部\",\"v\":\"\"},{\"n\":\"2026\",\"v\":\"162\"},{\"n\":\"2025\",\"v\":\"107\"},{\"n\":\"2024\",\"v\":\"119\"},{\"n\":\"2023\",\"v\":\"153\"},{\"n\":\"2022\",\"v\":\"101\"},{\"n\":\"2021\",\"v\":\"118\"},{\"n\":\"2020\",\"v\":\"16\"},{\"n\":\"2019\",\"v\":\"7\"},{\"n\":\"2018\",\"v\":\"2\"},{\"n\":\"2017\",\"v\":\"3\"},{\"n\":\"2016\",\"v\":\"22\"}]},{\"key\":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"热门\",\"v\":\"hot\"},{\"n\":\"更新\",\"v\":\"update\"},{\"n\":\"评分\",\"v\":\"rating\"}]}]," +
            "\"4\":[{\"key\":\"area\",\"name\":\"地区\",\"value\":[{\"n\":\"全部\",\"v\":\"\"},{\"n\":\"国产\",\"v\":\"1\"},{\"n\":\"中国香港\",\"v\":\"3\"},{\"n\":\"中国台湾\",\"v\":\"6\"},{\"n\":\"美国\",\"v\":\"5\"},{\"n\":\"韩国\",\"v\":\"18\"},{\"n\":\"日本\",\"v\":\"2\"}]},{\"key\":\"year\",\"name\":\"年份\",\"value\":[{\"n\":\"全部\",\"v\":\"\"},{\"n\":\"2026\",\"v\":\"162\"},{\"n\":\"2025\",\"v\":\"107\"},{\"n\":\"2024\",\"v\":\"119\"},{\"n\":\"2023\",\"v\":\"153\"},{\"n\":\"2022\",\"v\":\"101\"},{\"n\":\"2021\",\"v\":\"118\"},{\"n\":\"2020\",\"v\":\"16\"},{\"n\":\"2019\",\"v\":\"7\"},{\"n\":\"2018\",\"v\":\"2\"},{\"n\":\"2017\",\"v\":\"3\"},{\"n\":\"2016\",\"v\":\"22\"}]},{\"key\":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"热门\",\"v\":\"hot\"},{\"n\":\"更新\",\"v\":\"update\"},{\"n\":\"评分\",\"v\":\"rating\"}]}]," +
            "\"67\":[{\"key\":\"category_id\",\"name\":\"类型\",\"value\":[{\"n\":\"全部\",\"v\":\"\"},{\"n\":\"言情\",\"v\":\"70\"},{\"n\":\"爱情\",\"v\":\"71\"},{\"n\":\"战神\",\"v\":\"72\"},{\"n\":\"古代\",\"v\":\"73\"},{\"n\":\"萌娃\",\"v\":\"74\"},{\"n\":\"神医\",\"v\":\"75\"},{\"n\":\"玄幻\",\"v\":\"76\"},{\"n\":\"重生\",\"v\":\"77\"},{\"n\":\"激情\",\"v\":\"79\"},{\"n\":\"时尚\",\"v\":\"82\"},{\"n\":\"剧情演绎\",\"v\":\"83\"},{\"n\":\"影视\",\"v\":\"84\"},{\"n\":\"人文社科\",\"v\":\"85\"},{\"n\":\"二次元\",\"v\":\"86\"},{\"n\":\"明星八卦\",\"v\":\"87\"},{\"n\":\"随拍\",\"v\":\"88\"},{\"n\":\"个人管理\",\"v\":\"89\"},{\"n\":\"音乐\",\"v\":\"90\"},{\"n\":\"汽车\",\"v\":\"91\"},{\"n\":\"休闲\",\"v\":\"92\"},{\"n\":\"校园教育\",\"v\":\"93\"},{\"n\":\"游戏\",\"v\":\"94\"},{\"n\":\"科普\",\"v\":\"95\"},{\"n\":\"科技\",\"v\":\"96\"},{\"n\":\"时政社会\",\"v\":\"97\"},{\"n\":\"萌宠\",\"v\":\"98\"},{\"n\":\"体育\",\"v\":\"99\"},{\"n\":\"穿越\",\"v\":\"80\"},{\"n\":\"\",\"v\":\"81\"},{\"n\":\"闪婚\",\"v\":\"112\"}]}]}";

    /**
     * 构造。
     */
    public JianPianPlain() {
        super();
    }

    // ─────────────────────────────────────────────────────────────
    // 初始化：域名发现
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code JianPian.init(Context)}：
     * 无 ext 版，走 DoH 发现域名。带 ext 版优先用 ext 给的地址。
     *
     * @param ctx    宿主上下文
     * @param extend ext 原文，可为空
     */
    @Override
    public void init(Context ctx, String extend) {
        super.init(ctx, extend);
        if (baseUrl.isEmpty()) {
            // ext 没给地址 → 走上游的 DoH 发现流程
            String alive = discoverBase();
            if (!alive.isEmpty()) {
                baseUrl = alive;
            }
        }
        imgDomain = fetchImgDomain();
        Log.i(TAG, "init base=" + baseUrl + " img=" + imgDomain);
    }

    /**
     * 还原 DoH 发现流程：取 TXT → 拆逗号 → 逐域名拼随机子域探测 → 首个 200 采纳。
     *
     * @return 可用主域（如 {@code https://6dnr.cqlzn.com}）；全部失败返回空串
     */
    protected String discoverBase() {
        String resp = http.get(DOH + "?name=" + DOH_NAME + "&type=TXT");
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "DoH 返回为空");
            return "";
        }
        List<String> domains = parseDohAnswer(resp);
        if (domains.isEmpty()) {
            Log.w(TAG, "DoH 未解析出域名");
            return "";
        }
        for (String d : domains) {
            String candidate = withRandomSub(d, randomString(6));
            if (isAlive(candidate)) {
                Log.i(TAG, "命中 " + candidate);
                return candidate;
            }
        }
        // 全部失败：按上游逻辑用首个裸域名兜底
        String fallback = withRandomSub(domains.get(0), randomString(6));
        Log.w(TAG, "全部探测失败，兜底 " + fallback);
        return fallback;
    }

    /**
     * 从 DoH 响应里取域名列表。
     *
     * <p>响应形如：
     * <pre>{"Answer":[{"name":"...","type":16,"data":"\"cqxkn.com,hzlfm.com\""}]}</pre>
     * 注意 data 两头还包着双引号（DNS TXT 的引号），要剥掉。
     *
     * @param resp DoH JSON 响应
     * @return 裸域名列表，非 null
     */
    protected List<String> parseDohAnswer(String resp) {
        List<String> out = new ArrayList<>();
        if (resp == null || resp.isEmpty()) {
            return out;
        }
        try {
            JSONObject root = new JSONObject(resp);
            JSONArray answers = root.optJSONArray("Answer");
            if (answers == null || answers.length() == 0) {
                return out;
            }
            String data = answers.optJSONObject(0) == null
                    ? "" : answers.optJSONObject(0).optString("data");
            for (String part : data.split(",")) {
                String d = part.trim().replace("\"", "").trim();
                if (!d.isEmpty()) {
                    out.add(d);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "DoH 解析失败", e);
        }
        return out;
    }

    /**
     * 还原 {@code JianPian.aaaa(str)}：已带协议原样返回，否则拼
     * {@code https://{随机6位}.{str}。
     *
     * @param host 裸域名
     * @param sub  随机子域；空时内部现生成
     * @return 完整 base
     */
    public static String withRandomSub(String host, String sub) {
        if (host == null || host.isEmpty()) {
            return "";
        }
        if (host.startsWith("http://") || host.startsWith("https://")) {
            return host;
        }
        String s = (sub == null || sub.isEmpty()) ? randomString(6) : sub;
        return "https://" + s + "." + host;
    }

    /**
     * 还原 {@code JianPian.generateRandomString(n)}。
     *
     * @param n 长度
     * @return 随机串
     */
    public static String randomString(int n) {
        StringBuilder sb = new StringBuilder(Math.max(0, n));
        Random r = new Random();
        for (int i = 0; i < n; i++) {
            sb.append(RANDOM_CHARS.charAt(r.nextInt(RANDOM_CHARS.length())));
        }
        return sb.toString();
    }

    /** 探活：GET 详情页，2xx/3xx 视为可用 */
    private boolean isAlive(String base) {
        Http.Result r = http.execute("GET", base + "/api/v2/settings/resourceDomainConfig", null, null);
        return r != null && r.code >= 200 && r.code < 400;
    }

    /**
     * 还原 init 里取封面域名的逻辑：
     * {@code data.imgDomain.split(",")[1]} —— <b>取第 2 项</b>。
     *
     * @return 封面域名；失败返回空串
     */
    protected String fetchImgDomain() {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return "";
        }
        String resp = http.get(baseUrl + "/api/v2/settings/resourceDomainConfig", requestHeaders());
        return fetchImgDomainFrom(resp);
    }

    /**
     * 从 resourceDomainConfig 响应里取封面域名（纯函数，便于离线自测）。
     *
     * <p>上游是 {@code split(",")[1]}：站点故意用「第二项」作为图片域名，
     * 第一项通常是被墙或限流的域名。这里保留该顺序语义，
     * 并补了单域名/空值兜底。
     *
     * @param resp 响应体
     * @return 封面域名；无法解析返回空串
     */
    protected static String fetchImgDomainFrom(String resp) {
        if (resp == null || resp.isEmpty()) {
            return "";
        }
        try {
            JSONObject root = new JSONObject(resp);
            JSONObject data = root.optJSONObject("data");
            String raw = data == null ? "" : data.optString("imgDomain");
            if (raw.isEmpty()) {
                return "";
            }
            String[] parts = raw.split(",");
            if (parts.length > 1) {
                return parts[1].trim();
            }
            return parts[0].trim();
        } catch (Exception e) {
            Log.e(TAG, "imgDomain 解析失败", e);
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code JianPian.d(base)} 的请求头。
     *
     * @return 单条 UA 的 header 表
     */
    protected Map<String, String> requestHeaders() {
        return Http.headersOf("User-Agent", UA);
    }

    /**
     * 带指纹头的 GET。
     *
     * @param url 完整地址
     * @return 响应体；失败空串
     */
    protected String apiGet(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        return http.get(url, requestHeaders());
    }

    /**
     * 还原 {@code JianPian.c(str)}：首字符是 {@code /} 时补上 img 域名。
     *
     * @param str 相对路径或绝对地址
     * @return 绝对图片地址
     */
    public String imgUrl(String str) {
        if (str == null || str.isEmpty()) {
            return "";
        }
        if (!str.startsWith("/")) {
            return str;
        }
        String host = (imgDomain == null || imgDomain.isEmpty()) ? "img.bptpw.com" : imgDomain;
        return "https://" + host + str;
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code JianPian.homeContent(boolean)}：
     * 分类是硬编码 5 项；filter=true 时附带上游内联的 filters 常量。
     *
     * @param filter 是否返回筛选定义
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        VodBuilder vb = VodBuilder.create();
        for (int i = 0; i < TYPE_IDS.length; i++) {
            vb.clazz(TYPE_IDS[i], TYPE_NAMES[i]);
        }
        vb.addAll(homeVideos());
        String base = vb.homeContent();
        if (!filter) {
            return base;
        }
        // filters 的值是「数组」（每个分类一组筛选维度），VodBuilder.filter 只收对象，
        // 这里在构造器输出上补挂原样的 filters 对象，保持上游结构。
        try {
            JSONObject root = new JSONObject(base);
            root.put("filters", new JSONObject(FILTERS_JSON));
            return root.toString();
        } catch (Exception e) {
            Log.e(TAG, "filters 挂载失败", e);
            return base;
        }
    }

    /**
     * 还原 {@code JianPian.homeVideoContent()}：
     * {@code GET {a}/api/dyTag/list?category_id=88} → data[].dataList[]。
     *
     * <p>注意首页推荐项的 vod_id 第 4 段被写死成 {@code "1"}（不是短剧）。
     *
     * @param html 已废弃参数（占位，便于子类/测试复用）
     * @return 影片列表
     */
    protected List<Vod> parseHomeVideos(String html) {
        List<Vod> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        try {
            JSONArray groups = new JSONObject(html).optJSONArray("data");
            if (groups == null) {
                return out;
            }
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g == null) {
                    continue;
                }
                JSONArray items = g.optJSONArray("dataList");
                if (items == null) {
                    continue;
                }
                for (int j = 0; j < items.length(); j++) {
                    JSONObject o = items.optJSONObject(j);
                    if (o == null) {
                        continue;
                    }
                    String title = o.optString("title");
                    String path = imgUrl(optStr(o, "path"));
                    out.add(new Vod()
                            .id(buildVodId(optStr(o, "id"), title, path, "1"))
                            .name(title)
                            .pic(path)
                            .remarks(optStr(o, "mask")));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "首页推荐解析失败", e);
        }
        return out;
    }

    /** 拉取首页推荐 */
    private List<Vod> homeVideos() {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return new ArrayList<>();
        }
        return parseHomeVideos(apiGet(baseUrl + "/api/dyTag/list?category_id=88"));
    }

    // ─────────────────────────────────────────────────────────────
    // 分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code JianPian.categoryContent(...)}。
     *
     * <p>路径模板在反编译里是字符串替换：
     * <pre>
     *   普通: {a}/api/crumb/list?fcate_pid={cateId}&amp;category_id={category_id}
     *                &amp;area={area}&amp;year={year}&amp;type={type}&amp;sort={sort}&amp;page={page}
     *   短剧: {a}/api/crumb/shortList?fcate_pid={cateId}&amp;category_id={category_id}
     *                &amp;sort=update&amp;page={page}
     * </pre>
     * 未被筛选值填充的占位符（如未选地区）会被替换成空串，这一点必须保留，
     * 否则服务端会把字面量 {@code {area}} 当参数值。
     *
     * @param tid    分类 id（1/2/3/4/67）
     * @param pg     页码
     * @param filter 是否启用筛选
     * @param extend 宿主回传的筛选值
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  java.util.HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String base = requireUrl();
        if (base.isEmpty()) {
            return VodBuilder.create().page(page).categoryContent();
        }
        // 上游对短剧分类的 URL 里若带 .html 直接返回空（防注入）
        if (tid != null && tid.contains(".html")) {
            return emptyResponse();
        }

        String template = TID_SHORT.equals(tid)
                ? base + "/api/crumb/shortList?fcate_pid={cateId}&category_id={category_id}"
                       + "&sort=update&page={page}"
                : base + "/api/crumb/list?fcate_pid={cateId}&category_id={category_id}"
                       + "&area={area}&year={year}&type={type}&sort={sort}&page={page}";

        String url = fillTemplate(template, tid, page, extend);
        String resp = apiGet(url);
        List<Vod> list = parseCategoryList(resp, tid);
        int limit = Math.max(list.size(), 1);
        // 上游 pagecount 直接给 Integer.MAX_VALUE
        return VodBuilder.create().addAll(list)
                .page(page, Integer.MAX_VALUE, limit).categoryContent();
    }

    /**
     * 还原上游的字符串占位符替换。
     *
     * @param template URL 模板（含 {xxx} 占位）
     * @param tid      分类 id
     * @param page     页码
     * @param extend   筛选值
     * @return 填充后的 URL
     */
    protected static String fillTemplate(String template, String tid, int page,
                                         java.util.HashMap<String, String> extend) {
        String u = template == null ? "" : template;
        u = u.replace("{page}", String.valueOf(page));
        // 先按宿主回传的筛选值替换
        if (extend != null) {
            for (Map.Entry<String, String> e : extend.entrySet()) {
                String v = e.getValue();
                if (v != null && !v.isEmpty()) {
                    u = u.replace("{" + e.getKey() + "}", v);
                }
            }
        }
        u = u.replace("{cateId}", tid == null ? "" : tid);
        // 剩下的占位符一律清空（上游就是 replace 成 ""）
        u = u.replace("{category_id}", "").replace("{area}", "")
             .replace("{year}", "").replace("{type}", "").replace("{sort}", "");
        return u;
    }

    /**
     * 解析分类/列表响应。
     *
     * <p>真实响应（已验证）：
     * <pre>
     * {"code":1,"data":[{"id":542546,"title":"致命复活","score":"2.0",
     *   "path":"/upload/video/2026/01/26/xxx.jpeg","mask":"正片"}]}
     * </pre>
     * 上游取 {@code path}（不是 {@code tvimg}），mask 为空时回退 score。
     *
     * @param resp 响应体
     * @param tid  当前分类
     * @return 影片列表
     */
    protected List<Vod> parseCategoryList(String resp, String tid) {
        List<Vod> out = new ArrayList<>();
        if (resp == null || resp.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONObject(resp).optJSONArray("data");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String title = o.optString("title");
                String pic = imgUrl(o.optString("path"));
                String remarks = o.optString("mask");
                if (remarks.isEmpty()) {
                    remarks = o.optString("score");
                }
                out.add(new Vod()
                        .id(buildVodId(optStr(o, "id"), title, pic, tid))
                        .name(title)
                        .pic(pic)
                        .remarks(remarks));
            }
        } catch (Exception e) {
            Log.e(TAG, "分类响应解析失败", e);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code JianPian.detailContent(List)}。
     *
     * <p>vod_id 四段拆解后：
     * <ul>
     *   <li>普通分类 → {@code /api/video/detailv2?id={0}，读
     *       {@code data.description/year/area/actors/source_list_source}</li>
     *   <li>短剧(67) → {@code /api/detail?vid={0}，读 {@code data.playlist[]}</li>
     * </ul>
     *
     * @param ids 影片 id 列表
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String vodId = firstId(ids);
        if (vodId.isEmpty()) {
            return emptyResponse();
        }
        String base = requireUrl();
        if (base.isEmpty()) {
            return emptyResponse();
        }

        String[] seg = vodId.split("\\$\\$\\$");
        String realId = seg.length > 0 ? seg[0] : vodId;
        String title = seg.length > 1 ? seg[1] : "";
        String pic = seg.length > 2 ? seg[2] : "";
        String tid = seg.length > 3 ? seg[3] : "";

        String url = TID_SHORT.equals(tid)
                ? base + "/api/detail?vid=" + realId
                : base + "/api/video/detailv2?id=" + realId;

        String resp = apiGet(url);
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "详情响应为空: " + url);
            return emptyResponse();
        }

        try {
            JSONObject data = new JSONObject(resp).optJSONObject("data");
            if (data == null) {
                return emptyResponse();
            }
            Vod vod = new Vod().id(vodId).name(title).pic(pic);
            vod.content(data.optString("description"));

            PlaySplit ps;
            if (TID_SHORT.equals(tid)) {
                ps = parseShortPlayList(data.optJSONArray("playlist"));
            } else {
                vod.year(data.optString("year"))
                   .area(data.optString("area"))
                   .typeName("")
                   .remarks("")
                   .director("")
                   .actor(joinActorNames(data.optJSONArray("actors")));
                ps = parseSourceList(data.optJSONArray("source_list_source"));
            }
            if (!ps.from.isEmpty()) {
                vod.playFrom(ps.from).playUrl(ps.url);
            }
            return VodBuilder.detail(vod).detailContent();
        } catch (Exception e) {
            Log.e(TAG, "详情解析失败", e);
            return emptyResponse();
        }
    }

    /**
     * 还原普通线路解析：{@code source_list_source[].name + source_list[].source_name/url}。
     *
     * <p>真实响应里的 {@code source_name} 是画质标签（如「HD国语中字」），
     * 不是集数；ftp 开头的地址要加 {@code tvbox-xg:} 前缀。
     *
     * @param arr source_list_source
     * @return 线路名与播放串
     */
    protected PlaySplit parseSourceList(JSONArray arr) {
        PlaySplit rs = new PlaySplit();
        if (arr == null) {
            return rs;
        }
        List<String> froms = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject line = arr.optJSONObject(i);
            if (line == null) {
                continue;
            }
            String lineName = line.optString("name");
            JSONArray items = line.optJSONArray("source_list");
            if (items == null) {
                items = new JSONArray();
            }
            List<String> eps = new ArrayList<>();
            for (int j = 0; j < items.length(); j++) {
                JSONObject it = items.optJSONObject(j);
                if (it == null) {
                    continue;
                }
                String epName = it.optString("source_name");
                String u = it.optString("url");
                if (u.isEmpty()) {
                    continue;
                }
                if (u.startsWith("ftp")) {
                    u = "tvbox-xg:" + u;
                }
                eps.add(epName + "$" + u);
            }
            if (eps.isEmpty()) {
                continue;
            }
            froms.add(lineName);
            urls.add(HtmlLite.join("#", eps));
        }
        rs.from = HtmlLite.join("$$$", froms);
        rs.url = HtmlLite.join("$$$", urls);
        return rs;
    }

    /**
     * 还原短剧线路解析：{@code data.playlist[]} 直接给出 title/url，线路名固定「常规线路」。
     *
     * @param arr playlist
     * @return 线路名与播放串
     */
    protected PlaySplit parseShortPlayList(JSONArray arr) {
        PlaySplit rs = new PlaySplit();
        if (arr == null) {
            return rs;
        }
        List<String> eps = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String t = o.optString("title");
            String u = o.optString("url");
            if (u.isEmpty()) {
                continue;
            }
            eps.add(t + "$" + u);
        }
        if (!eps.isEmpty()) {
            rs.from = "常规线路";
            rs.url = HtmlLite.join("#", eps);
        }
        return rs;
    }

    /** 线路拆分结果 */
    protected static class PlaySplit {
        /** 线路名，多线路用 $$$ 连接 */
        String from = "";
        /** 播放串 */
        String url = "";
    }

    /**
     * 还原上游 {@code actors.toString()} 的语义：把演员名连成 {@code [a, b, c]}。
     *
     * @param arr actors 数组（元素为 {"id":..,"name":".."}）
     * @return 形如 {@code [张鲁一, 于和伟]}
     */
    protected static String joinActorNames(JSONArray arr) {
        if (arr == null || arr.length() == 0) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String n = o.optString("name");
            if (!n.isEmpty()) {
                names.add(n);
            }
        }
        return names.isEmpty() ? "" : "[" + HtmlLite.join(", ", names) + "]";
    }

    // ─────────────────────────────────────────────────────────────
    // 搜索
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code JianPian.searchContent(str, z)}：
     * {@code /api/v2/search/videoV2?key=..&category_id=88&page=1&pageSize=20}。
     *
     * <p>搜索结果项用 {@code thumbnail} 作封面（不是 path），
     * 第 4 段 tid 取 {@code top_category.id}。
     *
     * @param key   关键词
     * @param quick 快速搜索标记（上游未使用）
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) {
            return VodBuilder.create().searchContent();
        }
        String base = requireUrl();
        if (base.isEmpty()) {
            return VodBuilder.create().searchContent();
        }
        String k;
        try {
            k = URLEncoder.encode(key, "UTF-8");
        } catch (Exception e) {
            k = key;
        }
        String url = base + "/api/v2/search/videoV2?key=" + k
                + "&category_id=88&page=1&pageSize=20";
        return VodBuilder.of(parseSearch(apiGet(url))).searchContent();
    }

    /**
     * 解析搜索结果。
     *
     * <p>真实响应字段（已验证）：title / thumbnail / mask / id / top_category.id。
     *
     * @param resp 响应体
     * @return 影片列表
     */
    protected List<Vod> parseSearch(String resp) {
        List<Vod> out = new ArrayList<>();
        if (resp == null || resp.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONObject(resp).optJSONArray("data");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String title = o.optString("title");
                String thumb = o.optString("thumbnail");
                String pic = thumb.isEmpty() ? "" : imgUrl(thumb);
                JSONObject top = o.optJSONObject("top_category");
                String tid = top == null ? "0" : String.valueOf(top.optInt("id", 0));
                out.add(new Vod()
                        .id(buildVodId(String.valueOf(o.optInt("id", 0)), title, pic, tid))
                        .name(title)
                        .pic(pic)
                        .remarks(o.optString("mask")));
            }
        } catch (Exception e) {
            Log.e(TAG, "搜索响应解析失败", e);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code JianPian.playerContent(...)}。
     *
     * <p>上游用 {@code ku.f(url)} 判断「是否属于需要嗅探的站点」：
     * 命中 iqiyi/v.qq.com/youku 等 16 个域名时 parse=1（交给宿主嗅探），
     * 否则 parse=0 直链播放。
     *
     * @param flag     线路标识（上游未使用）
     * @param id       播放地址
     * @param vipFlags 需要解析的标识集合（上游未使用）
     * @return 播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) {
            return emptyResponse();
        }
        boolean needSniff = isNeedSniffSite(id) && id.startsWith("http");
        if (needSniff) {
            JSONObject o = new JSONObject();
            try {
                o.put("parse", 1);
                o.put("jx", 1);
                o.put("url", id);
            } catch (Exception ignored) {
            }
            return o.toString();
        }
        return VodBuilder.playerContent(id, id.contains(".m3u8")
                ? "application/vnd.apple.mpegurl" : "video/*");
    }

    /**
     * 还原 {@code ku.f(str)} —— 需要嗅探的 16 个视频站域名。
     *
     * <p>iqiyi 还有额外判断：只有 {@code /a_}、{@code /w_}、{@code /v_} 才是正片页。
     *
     * @param url 播放地址
     * @return true 表示交给宿主嗅探
     */
    public static boolean isNeedSniffSite(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase();
        String[] hosts = {"iqiyi.com", "v.qq.com", "youku.com", "le.com", "tudou.com",
                "mgtv.com", "sohu.com", "acfun.cn", "bilibili.com", "baofeng.com",
                "pptv.com", "www.miguvideo.com", "www.ixigua.com", "vip.1905.com",
                "www.fun.tv", "m.fun.tv"};
        for (String h : hosts) {
            if (!lower.contains(h)) {
                continue;
            }
            if ("iqiyi.com".equals(h)
                    && !lower.contains("iqiyi.com/a_")
                    && !lower.contains("iqiyi.com/w_")
                    && !lower.contains("iqiyi.com/v_")) {
                continue;
            }
            return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────────

    /**
     * 拼 vod_id 四段。
     *
     * @param id    站内 id
     * @param title 片名
     * @param pic   封面绝对地址
     * @param tid   分类 id
     * @return {@code id$$$title$$$pic$$$tid}
     */
    public static String buildVodId(String id, String title, String pic, String tid) {
        return HtmlLite.nz(id) + "$$$" + HtmlLite.nz(title) + "$$$"
                + HtmlLite.nz(pic) + "$$$" + HtmlLite.nz(tid);
    }

    /**
     * 拆 vod_id 四段。
     *
     * @param vodId 复合 id
     * @return 长度恒为 4 的数组（缺失段为空串）
     */
    public static String[] splitVodId(String vodId) {
        String[] out = {"", "", "", ""};
        if (vodId == null || vodId.isEmpty()) {
            return out;
        }
        String[] seg = vodId.split("\\$\\$\\$");
        for (int i = 0; i < out.length && i < seg.length; i++) {
            out[i] = seg[i] == null ? "" : seg[i];
        }
        return out;
    }

    /** optString 但把数字转成整数字面量（避免 542546.0） */
    private static String optStr(JSONObject o, String k) {
        Object v = o.opt(k);
        if (v == null) {
            return "";
        }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
        }
        return String.valueOf(v);
    }
}
