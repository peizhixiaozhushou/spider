package com.example.tvbox.plain.ting;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.common.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界听书（{@code csp_TingShijie}）明文实现。
 *
 * <p>继承 {@link BaseSpider}（上游 {@code extends Spider}）。
 *
 * <h2>加密分类</h2>
 * <b>密钥透明加密</b>：播放接口要一个 {@code addItParapet} 签名，
 * 算法是<b>双重 MD5</b>，盐值硬编码在类里：
 * <pre>
 *   SALT = "J9gSpfUlzYxE8Hn5IXiGaD2jVMrwAm0K"      （32 位，类内常量）
 *   addItParapet = md5( md5(timeStamp + SALT) + SALT )
 * </pre>
 * 属于「可完整还原」，不需要外部密钥协商。本实现用 {@code MessageDigest} 内联实现，
 * 不引入新依赖。
 *
 * <h2>协议（站点探测 + 真实请求验证）</h2>
 * <pre>
 * ① 域名发现（还原上游 init）
 *    GET http://101.43.48.231:8090/config/tingchina2025.txt
 *        ← 该地址 2026-10 已不可达（连接超时），上游的兜底值才是实际可用域名：
 *          https://app.365ting.com/listen/Apitzg2025/
 *
 * ② 分类  GET {base}appHomeByCategory?categoryId=&lt;tid&gt;&amp;page=&lt;pg&gt;&amp;size=120
 *        响应 {"status":0,"data":[{id,bookTitle,bookImage,bookAnchor,bookDesc}]}
 *        注意: 成功码是 **status==0**
 *
 * ③ 详情  GET {base}book?bookId=&lt;id&gt;
 *        响应 {"status":0,"data":{"bookData":{bookTitle,bookDesc,bookImage,count}}}
 *        分集  GET {base}chapter?size=1000&amp;page=N&amp;sort=asc&amp;bookId=&lt;id&gt;
 *        响应 {"status":0,"data":{"list":[{chapterId,position}]}}
 *        需按 count 算出页数循环拉（每页 1000）
 *
 * ④ 播放  GET {base}AppGetChapterUrl2023?timeStamp=&lt;ms&gt;&amp;uid=&amp;chapterId=&lt;cid&gt;
 *              &amp;addItParapet=&lt;double-md5&gt;&amp;bookId=&lt;bid&gt;
 *        响应 {"status":0,"src":"<音频地址>"}
 *
 * ⑤ 搜索  GET {base}appSearch?client=babala-android&amp;search=&lt;key&gt;&amp;app_token=abcSEARCH-2025
 *        响应 {"status":0,"data":{"bookData":[...]}}
 * </pre>
 *
 * <h2>需要说明的不确定性（重要）</h2>
 * 播放接口在 2026-10-17 实测返回：
 * <pre>{"status":1,"message":"分集地址解析失败请重试点击播放"}</pre>
 * 即<b>签名算法本身无法从响应侧确认</b>（服务端先判权/判时段，再校验签名）。
 * 签名公式来自反编译原文，我按原文逐字实现并单测了 MD5 链；
 * 但「公式正确 → 能拿到地址」这一步<b>未能用真实响应证实</b>。
 * 详见测试里的 {@code [inferred]} 标记。
 *
 * <h2>与上游的差异</h2>
 * <ul>
 *   <li>域名发现加超时保护：上游 {@code b.l()} 无超时，黑板地址挂了会卡住 init。
 *       本实现失败即用兜底域名</li>
 *   <li>章节翻页按 {@code count} 精确计算页数，并加 100 页上限防死循环</li>
 * </ul>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/TingShijie.java}（222 行）
 * 真实夹具:   {@code ae/fixtures/real/tingshijie_category.json}
 *             {@code ae/fixtures/real/tingshijie_book.json}
 *             {@code ae/fixtures/real/tingshijie_search.json}
 * 抓取时间:   2026-10-17
  *
 * <h2>未覆盖的分支</h2>
 *
 * <p><b>有 1 条。</b>
 *
 * <ol>
 *   <li><b>签名算法无法从响应侧反向确认</b>（原实现见 {@code ae/decompiled/TingShijie.java}）。
 *       服务端的校验顺序是"先判权/判时段，<b>再</b>校验签名"，因此即使用错误的签名去请求，
 *       拿到的也是权限类错误、而不是签名类错误 —— 无法用响应差异区分"签名对不对"。
 *       <b>本实现的取舍</b>：按反编译产物逐字复刻签名构造（参数顺序、拼接串、摘要算法），
 *       并把签名函数单独暴露出来做固定向量测试，钉住"算法与我理解的一致"；
 *       但**不能**证明它与服务端一致。
 *       <b>将来补上需要</b>：一次成功请求的抓包（含正确签名），或一个已知有效的
 *       (参数, 签名) 固定对。</li>
 * </ol>
 *
 * <p>除上条外，其余契约方法与私有解析方法均已逐一核对，无其它未覆盖分支。
 * 判定口径：本节只在<b>确实存在</b>无法还原的分支时才列条目。
  *
 * <h2>修正的上游缺陷</h2>
 *
 * <p>本类对上游的重写未修改其对外行为契约；<b>有意偏离</b>上游的地方（健壮性补强、
 * 命名歧义澄清、约定修正）已在上方「与上游的差异」一节逐条列出，并都有对应断言钉住。
 * 因此本节不再重复，以免同一事实在两处漂移。
 *
 * <p>判定口径："与上游的差异"与"修正的上游缺陷"在本项目里是同一类信息的两种措辞 ——
 * 前者强调"我们不一样在哪里"，后者强调"上游原本错了什么"。都以
 * {@code ae/decompiled/TingShijie.java} 为对照基准。
 */
public class TingShijiePlain extends BaseSpider {

    private static final String TAG = "TingShijiePlain";

    /** 上游域名配置地址（实测已不可达，保留以还原逻辑） */
    private static final String CONFIG_URL = "http://101.43.48.231:8090/config/tingchina2025.txt";

    /** 兜底站点（上游 {@code TextUtils.isEmpty(strL)} 分支的值，也是实测可用值） */
    private static final String DEFAULT_BASE = "https://app.365ting.com/listen/Apitzg2025/";

    /** 上游 UA（注意是伪装成 App 的，不是浏览器） */
    private static final String UA = "TingShiJie/1.8.8 (m.i275.com)";

    /** 双重 MD5 的盐（类内常量，32 字符） */
    private static final String SALT = "J9gSpfUlzYxE8Hn5IXiGaD2jVMrwAm0K";

    /** 搜索接口固定参数 */
    private static final String SEARCH_TOKEN = "abcSEARCH-2025";
    private static final String SEARCH_CLIENT = "babala-android";

    /** 分类页每页条数（上游 size=120） */
    private static final int CATEGORY_PAGE_SIZE = 120;
    /** 章节每页条数（上游 size=1000） */
    private static final int CHAPTER_PAGE_SIZE = 1000;
    /** 章节翻页上限 */
    private static final int MAX_CHAPTER_PAGES = 100;

    /** 上游 11 个分类（顺序敏感） */
    private static final String[] TIDS = {
            "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "49",
    };
    private static final String[] TNAMES = {
            "玄幻奇幻", "都市言情", "宫斗女频", "官场商战", "武侠仙侠", "刑侦推理",
            "探险科幻", "重生穿越", "恐怖惊悚", "文学历史", "两性情感",
    };

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        if (baseUrl == null || baseUrl.isEmpty() || baseUrl.equalsIgnoreCase("null")) {
            baseUrl = resolveBase();
        }
        Log.i(TAG, "init base=" + baseUrl);
    }

    /**
     * 还原上游 {@code init} 的域名发现：拉配置文本，空则用兜底。
     *
     * <p>上游无超时保护，黑板挂了会阻塞；本实现用一个短超时的 Http 拉，
     * 失败立刻退回兜底域名。
     *
     * @return 可用 base（保证以 / 结尾，因为上游直接字符串拼接）
     */
    public String resolveBase() {
        try {
            Http probe = Http.create().ua(UA).timeout(3000, 5000).retry(0);
            String cfg = probe.get(CONFIG_URL, Http.headersOf("User-Agent", UA));
            if (!TextUtils.isEmpty(cfg)) {
                String t = cfg.trim();
                if (t.startsWith("http")) {
                    Log.i(TAG, "域名配置命中: " + t);
                    return withSlash(t);
                }
                Log.w(TAG, "域名配置非 URL，忽略: " + head(t));
            }
        } catch (Throwable e) {
            Log.w(TAG, "域名配置拉取失败，用兜底: " + e.getMessage());
        }
        return DEFAULT_BASE;
    }

    /**
     * 补尾部斜杠（上游 base 直接参与字符串拼接，必须是 {@code .../} 形式）。
     *
     * @param url 地址
     * @return 带尾斜杠的地址
     */
    public static String withSlash(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        return url.endsWith("/") ? url : url + "/";
    }

    /** 还原上游 {@code b()}：伪装 UA */
    public Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        return h;
    }

    // ─────────────────────────────────────────────────────────────
    // 签名（双重 MD5）
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code I.a(I.a(ts + SALT) + SALT)} —— 双重 MD5。
     *
     * <p>上游 {@code I.a} 的实现：{@code md5hex 后补零到 32 位并 toLowerCase()}。
     *
     * @param timeStamp 毫秒时间戳字符串
     * @return addItParapet；失败返回空串
     */
    public static String sign(String timeStamp) {
        if (TextUtils.isEmpty(timeStamp)) {
            return "";
        }
        String inner = md5Hex(timeStamp + SALT);
        if (inner.isEmpty()) {
            return "";
        }
        return md5Hex(inner + SALT);
    }

    /**
     * MD5 十六进制小写（补零到 32 位）。
     *
     * @param s 原文
     * @return 32 位小写 hex；失败空串
     */
    public static String md5Hex(String s) {
        if (s == null) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                String h = Integer.toHexString(b & 0xFF);
                if (h.length() == 1) {
                    sb.append('0');
                }
                sb.append(h);
            }
            return sb.toString().toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable e) {
            Log.e(TAG, "md5 failed", e);
            return "";
        }
    }

    /**
     * 拼播放 URL（便于测试与复现）。
     *
     * @param base      站点 base（带尾斜杠）
     * @param bookId    书 id
     * @param chapterId 章节 id
     * @param timeStamp 毫秒时间戳
     * @return 完整 URL
     */
    public static String playUrl(String base, String bookId, String chapterId, String timeStamp) {
        return withSlash(base) + "AppGetChapterUrl2023?timeStamp=" + timeStamp
                + "&uid=&chapterId=" + chapterId
                + "&addItParapet=" + sign(timeStamp)
                + "&bookId=" + bookId;
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页：11 个分类（还原上游 {@code homeContent}）。
     *
     * @param filter 是否要筛选（上游无）
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        VodBuilder vb = VodBuilder.create();
        for (int i = 0; i < TIDS.length; i++) {
            vb.clazz(TIDS[i], TNAMES[i]);
        }
        return vb.homeContent();
    }

    /** 供测试：分类数 */
    public static int categoryCount() {
        return TIDS.length;
    }

    // ─────────────────────────────────────────────────────────────
    // 状态判断
    // ─────────────────────────────────────────────────────────────

    /**
     * 成功码判断：世界听书用 {@code status==0}。
     *
     * @param root 响应根对象
     * @return true 表示成功
     */
    public static boolean ok(JSONObject root) {
        return root != null && root.optInt("status", -1) == 0;
    }

    // ─────────────────────────────────────────────────────────────
    // 分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 分类翻页（还原上游 {@code categoryContent}）。
     *
     * @param tid    分类 id
     * @param pg     页码
     * @param filter 是否要筛选（忽略）
     * @param extend 筛选参数（忽略）
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        if (requireUrl().isEmpty()) {
            return VodBuilder.create().page(page).categoryContent();
        }
        String url = baseUrl + "appHomeByCategory?categoryId=" + Http.encode(tid)
                + "&page=" + page + "&size=" + CATEGORY_PAGE_SIZE;
        String resp = http.get(url, apiHeaders());
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "categoryContent empty, tid=" + tid + " pg=" + page);
            return VodBuilder.create().page(page).categoryContent();
        }
        List<Vod> list;
        try {
            JSONObject root = new JSONObject(resp);
            if (!ok(root)) {
                Log.w(TAG, "categoryContent status!=0: " + root.optInt("status", -1));
                return VodBuilder.create().page(page).categoryContent();
            }
            list = parseBooks(root.optJSONArray("data"));
        } catch (Exception e) {
            Log.e(TAG, "categoryContent parse failed, tid=" + tid, e);
            return VodBuilder.create().page(page).categoryContent();
        }
        // 上游把 pagecount 写死 100 / total 写死 2000（服务端不给总数）
        return VodBuilder.create().addAll(list).page(page, 0, CATEGORY_PAGE_SIZE)
                .categoryContent();
    }

    /**
     * 解析书籍数组（还原上游字段映射）。
     *
     * @param arr data 数组
     * @return 影片列表，永不 null
     */
    public List<Vod> parseBooks(JSONArray arr) {
        List<Vod> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            out.add(new Vod()
                    .id(o.optString("id", String.valueOf(o.opt("id"))))
                    .name(o.optString("bookTitle"))
                    .pic(o.optString("bookImage"))
                    .remarks(o.optString("bookAnchor")));
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 详情（还原上游 {@code detailContent}）。
     *
     * <p>上游按 {@code (count + 999) / 1000} 算页数循环拉章节；
     * 本实现同，但加 100 页上限。
     *
     * @param ids id 列表，首个为 bookId
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String bookId = firstId(ids);
        if (bookId.isEmpty()) {
            Log.w(TAG, "detailContent 无 id");
            return emptyResponse();
        }
        if (requireUrl().isEmpty()) {
            return emptyResponse();
        }

        String name = "";
        String desc = "";
        String pic = "";
        int count = 0;

        String bookResp = http.get(baseUrl + "book?bookId=" + Http.encode(bookId), apiHeaders());
        if (TextUtils.isEmpty(bookResp)) {
            Log.w(TAG, "detailContent book 空响应, id=" + bookId);
            return emptyResponse();
        }
        try {
            JSONObject root = new JSONObject(bookResp);
            if (!ok(root)) {
                Log.w(TAG, "detailContent book status!=0");
                return emptyResponse();
            }
            JSONObject book = root.getJSONObject("data").optJSONObject("bookData");
            if (book == null) {
                return emptyResponse();
            }
            name = book.optString("bookTitle");
            desc = book.optString("bookDesc");
            pic = book.optString("bookImage");
            count = book.optInt("count", 0);
        } catch (Exception e) {
            Log.e(TAG, "detailContent book 解析失败", e);
            return emptyResponse();
        }

        List<String> eps = new ArrayList<>();
        int pages = (count + CHAPTER_PAGE_SIZE - 1) / CHAPTER_PAGE_SIZE;
        if (pages <= 0) {
            pages = 1;
        }
        pages = Math.min(pages, MAX_CHAPTER_PAGES);
        for (int p = 1; p <= pages; p++) {
            String url = baseUrl + "chapter?size=" + CHAPTER_PAGE_SIZE + "&page=" + p
                    + "&sort=asc&bookId=" + Http.encode(bookId);
            String resp = http.get(url, apiHeaders());
            if (TextUtils.isEmpty(resp)) {
                Log.w(TAG, "detailContent chapter 第 " + p + " 页空响应");
                continue;
            }
            try {
                JSONObject root = new JSONObject(resp);
                if (!ok(root)) {
                    continue;
                }
                JSONArray list = root.getJSONObject("data").optJSONArray("list");
                if (list == null) {
                    continue;
                }
                for (int i = 0; i < list.length(); i++) {
                    JSONObject c = list.optJSONObject(i);
                    if (c == null) {
                        continue;
                    }
                    String position = String.valueOf(c.opt("position"));
                    String chapterId = c.optString("chapterId", String.valueOf(c.opt("chapterId")));
                    if (chapterId.isEmpty()) {
                        continue;
                    }
                    // 集名$bookId|chapterId（播放层按 | 拆）
                    eps.add(position + Vod.SEP_NAME_URL + bookId + "|" + chapterId);
                }
            } catch (Exception e) {
                Log.w(TAG, "detailContent chapter 第 " + p + " 页解析失败: " + e);
            }
        }

        if (eps.isEmpty()) {
            Log.w(TAG, "detailContent 未取到分集, id=" + bookId);
            return emptyResponse();
        }
        Vod vod = new Vod()
                .id(bookId)
                .name(name)
                .pic(pic)
                .content(desc)
                .playFrom("世界听书")
                .playUrl(Html.join(Vod.SEP_EPISODE, eps));
        return VodBuilder.detail(vod).detailContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 播放（还原上游 {@code playerContent}）。
     *
     * <p>入参 id 形如 {@code bookId|chapterId}。
     *
     * @param flag     线路名（世界听书）
     * @param id       播放 id
     * @param vipFlags 需要解析的标识（忽略）
     * @return 标准播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) {
            return emptyResponse();
        }
        if (requireUrl().isEmpty()) {
            return emptyResponse();
        }
        String[] parts = id.split("\\|");
        if (parts.length < 2) {
            Log.w(TAG, "playerContent id 结构非法: " + id);
            return emptyResponse();
        }
        String bookId = parts[0];
        String chapterId = parts[1];
        String ts = String.valueOf(System.currentTimeMillis());
        String url = playUrl(baseUrl, bookId, chapterId, ts);

        String resp = http.get(url, apiHeaders());
        String src = parseSrc(resp);
        if (TextUtils.isEmpty(src)) {
            Log.w(TAG, "playerContent 未取到 src（签名/时段/权限均可能导致，见类注释）");
            return emptyResponse();
        }
        return QingtingFMPlain.playerWithHeaders(src, apiHeaders());
    }

    /**
     * 解析播放响应取 {@code src}。
     *
     * @param resp 原始响应
     * @return 音频地址；失败空串
     */
    public static String parseSrc(String resp) {
        if (TextUtils.isEmpty(resp)) {
            return "";
        }
        try {
            JSONObject o = new JSONObject(resp.trim());
            if (o.optInt("status", -1) != 0) {
                return "";
            }
            return o.optString("src", "");
        } catch (Exception e) {
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 搜索
    // ─────────────────────────────────────────────────────────────

    /**
     * 搜索（还原上游 {@code searchContent}）。
     *
     * @param key   关键词
     * @param quick 是否快速搜索（忽略）
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) {
            return VodBuilder.create().searchContent();
        }
        if (requireUrl().isEmpty()) {
            return VodBuilder.create().searchContent();
        }
        String url = baseUrl + "appSearch?client=" + SEARCH_CLIENT
                + "&search=" + Http.encode(key) + "&app_token=" + SEARCH_TOKEN;
        String resp = http.get(url, apiHeaders());
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "searchContent empty, key=" + key);
            return VodBuilder.create().searchContent();
        }
        try {
            JSONObject root = new JSONObject(resp);
            if (!ok(root)) {
                return VodBuilder.create().searchContent();
            }
            return VodBuilder.of(parseBooks(
                    root.getJSONObject("data").optJSONArray("bookData"))).searchContent();
        } catch (Exception e) {
            Log.e(TAG, "searchContent parse failed, key=" + key, e);
            return VodBuilder.create().searchContent();
        }
    }

    private static String head(String s) {
        return s == null ? "" : (s.length() <= 60 ? s : s.substring(0, 60) + "…");
    }
}
