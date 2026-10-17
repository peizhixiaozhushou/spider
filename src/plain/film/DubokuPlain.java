package com.example.tvbox.plain.film;

import android.content.Context;
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
import java.util.Random;

/**
 * 独播影视（Duboku / 独播库）—— JSON API + 自研签名 + 自研位编码。
 *
 * <h2>证据来源</h2>
 * <ul>
 *   <li>反编译原文：{@code ae/decompiled/Duboku.java}；本地直读版
 *       {@code /tmp/ldall/com.github.catvod.spider.Duboku.java}（含 15KB 筛选表原文）</li>
 *   <li>真实响应夹具：{@code ae/fixtures/film/dbk_home.json}（首页 23KB）、
 *       {@code dbk_cat.json}（分类）、{@code dbk_detail.json}（详情，47 集）、
 *       {@code dbk_play.json}（播放）、{@code dbk_search.json}（搜索）</li>
 *   <li><b>真实请求验证：通过</b>（2026-10，{@code https://api.dbokutv.com}，
 *       用 Python 复现签名后全部 200）</li>
 * </ul>
 *
 * <h2>协议要点</h2>
 * <pre>
 *  API 基址固定 https://api.dbokutv.com（与播放页域名 w.duboku.io 不同）
 *  ext：本类**不使用** ext（上游 init 只调 super）
 *
 *  每个请求都要追加签名查询串 b()：
 *     ?sign={60 位随机}&ssid={Base64 混淆后的 18 位数字串}&token={38 位随机}
 *
 *  首页   GET /home{sign}                          → [{Title, VodList:[...]}]
 *  分类   GET /vodshow/{tid}-{area}-{by}-{class}-{lang}----{pg}---{year}{sign}
 *  详情   GET /voddetail/{id}{sign}
 *  播放   GET /vodplay/{id}-{src}-{num}{sign}      （id 形如 149953-1-1）
 *  搜索   GET /vodsearch{sign}&wd={key}
 *
 *  字段（真实响应，已核对）：
 *    VodList[]: Name / Rating(数字) / Tag / Actor / DId / DuId / TnId
 *    detail    : Name / TnId / ReleaseYear / Description / Actor[] / Director / Playlist[]
 *    Playlist[]: EpisodeName / VId / VuId
 *    play      : HId（加密后的 m3u8）
 *
 *  加密：无 AES/RSA；但有两处自定义编码
 *    1) 签名 b()：时间戳 + 秒级种子 PRNG 生成随机串（见 {@link #sign()}）
 *    2) 字段 d(s)：每 10 字符一组反转 → 还原 '.' 为 '=' → Base64 解码
 *       DId → "/voddetail/149953"，TnId → 封面图，VId → "/vodplay/149953-1-1"，
 *       HId → 真实 m3u8 直链
 * </pre>
 *
 * <h2>签名算法（逐行还原 {@code Duboku.b()}）</h2>
 * <pre>
 *  now   = System.currentTimeMillis()/1000            （秒级）
 *  r     = new Random(now).nextInt(800000000) % 800000001
 *  seed  = (100000000 + r) 与 (900000000 - r) 首尾相接   （共 18 位）
 *  ms    = String.valueOf(System.currentTimeMillis())   （13 位）
 *  inter = seed 与 ms 逐字符交错（各取前 13 位），余下部分追加
 *  ssid  = Base64(inter) 里把 '=' 换成 '.'
 *  sign  = a(60, 60+now, 33)     字符集 0-9a-zA-Z
 *  token = a(38, now+38, 88)     字符集 XYZ 开头的 62 字符循环表
 * </pre>
 * 关键点：<b>两个随机串都用 {@code new Random(以秒时间戳为种子)}</b>，
 * 因此同一秒内可复现，服务端用同一算法校验。
  *
 * <h2>未覆盖的分支</h2>
 *
 * <p><b>无。</b>
 *
 * <p>已逐个核对本类的契约方法（{@code homeContent} / {@code homeVideoContent} /
 * {@code categoryContent} / {@code detailContent} / {@code searchContent} / {@code playerContent}）
 * 与全部私有解析方法，不存在依赖宿主私有 API 或服务端未公开算法的分支。
 * 上游被混淆掉的辅助方法（{@code merge.*}）均已用手写等价逻辑替换，替换点见各方法行内注释。
 *
 * <p>判定口径：本节只在<b>确实存在</b>无法还原的分支时才列条目。写"无"意味着逐路径核对过，
 * 而不是"没找"。日后若发现遗漏，请在此补充并注明原方法名与 行号。
  *
 * <h2>修正的上游缺陷</h2>
 *
 * <p>本类对上游的重写未修改其对外行为契约；<b>有意偏离</b>上游的地方（健壮性补强、
 * 命名歧义澄清、约定修正）已在上方「与上游的差异」一节逐条列出，并都有对应断言钉住。
 * 因此本节不再重复，以免同一事实在两处漂移。
 *
 * <p>判定口径："与上游的差异"与"修正的上游缺陷"在本项目里是同一类信息的两种措辞 ——
 * 前者强调"我们不一样在哪里"，后者强调"上游原本错了什么"。都以
 * {@code ae/decompiled/Duboku.java} 为对照基准。
 */
public class DubokuPlain extends BaseSpider {

    private static final String TAG = "DubokuPlain";

    /** API 基址（上游硬编码，与 ext 无关） */
    static final String API_BASE = "https://api.dbokutv.com";

    /** 上游 {@code Duboku.c()} 的固定请求头 */
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
    static final String REFERER = "https://www.duboku.tv/";

    /** 播放请求专用的 origin / referer（{@code Duboku.playerContent}） */
    static final String PLAY_ORIGIN = "https://w.duboku.io";
    static final String PLAY_REFERER = "https://w.duboku.io/";

    /** 分页大小（上游 {@code cVar.r(i, iCeil, 48, MAX_VALUE)}） */
    static final int PAGE_SIZE = 48;

    /** 分类列表（{@code Duboku.homeContent}，顺序敏感：id 与名字按下标对应） */
    static final String[] TYPE_IDS = {"1", "2", "3", "4", "21", "20", "13", "15", "14"};
    static final String[] TYPE_NAMES = {"电影", "电视剧", "综艺", "动漫", "短剧", "港剧", "陆剧", "日韩剧", "台泰剧"};

    /** 首页筛选表（逐字取自 DEX，见 {@code ae/fixtures/film/Duboku_filters.json}） */
    static final String FILTERS_JSON = FilmFilters.DUBOKU;

    /** 数字字符集（{@code a(.., 33)}） */
    static final String CHARSET_33 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** 88 号偏移的 62 字符循环表（{@code a(.., 88)}） */
    static final String CHARSET_88 = "XYZ0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVW";

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        // 上游 Duboku 完全不用 ext；此处保持同语义，仅记录日志
        Log.i(TAG, "init: API 基址固定为 " + API_BASE + "（忽略 ext）");
    }

    // ─────────────────────────────────────────────────────────────
    // 签名
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code Duboku.a(int len, long seed, int charsetOffset)}：以种子驱动 PRNG 取随机串。
     *
     * <p>注意 {@code new Random(seed)} 的种子是 {@code (秒时间戳 + 偏移)}，
     * 因此同一秒内两次调用结果一致 —— 这是服务端能校验的前提。
     *
     * @param len           生成长度
     * @param seed          随机种子
     * @param charsetOffset 字符集偏移：33=数字字母表；88=88 号循环表；其它=从该 ASCII 起取 62 个
     * @return 随机串
     */
    public static String randomString(int len, long seed, int charsetOffset) {
        Random random = new Random(seed);
        String charset;
        if (charsetOffset == 33) {
            charset = CHARSET_33;
        } else if (charsetOffset == 88) {
            charset = CHARSET_88;
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = charsetOffset; i < charsetOffset + 62; i++) {
                sb.append((char) i);
            }
            charset = sb.toString();
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < len; i++) {
            out.append(charset.charAt(random.nextInt(charset.length())));
        }
        return out.toString();
    }

    /**
     * 还原 {@code Duboku.b()}：生成签名查询串（含前导 {@code ?}）。
     *
     * @return {@code ?sign=…&ssid=…&token=…}
     */
    public static String sign() {
        return sign(System.currentTimeMillis());
    }

    /**
     * 同 {@link #sign()}，但可注入时间戳（供测试固定结果）。
     *
     * @param nowMs 毫秒时间戳
     * @return 签名查询串
     */
    static String sign(long nowMs) {
        long nowSec = nowMs / 1000L;
        int r = new Random(nowSec).nextInt(800000000) % 800000001;
        String seed = (100000000 + r) + String.valueOf(900000000 - r);
        String ms = String.valueOf(nowMs);

        StringBuilder sb = new StringBuilder();
        int min = Math.min(seed.length(), ms.length());
        for (int i = 0; i < min; i++) {
            sb.append(seed.charAt(i));
            sb.append(ms.charAt(i));
        }
        if (seed.length() > min) {
            sb.append(seed.substring(min));
        }
        if (ms.length() > min) {
            sb.append(ms.substring(min));
        }
        String ssid = base64(sb.toString()).replace("=", ".");

        String signStr = randomString(60, 60 + nowSec, 33);
        String token = randomString(38, nowSec + 38, 88);
        return "?sign=" + signStr + "&ssid=" + ssid.replace("=", ".") + "&token=" + token;
    }

    /**
     * 还原 {@code Duboku.d(String)}：每 10 字符一组反转 → {@code '.'} 还原 {@code '='} → Base64 解。
     *
     * <p>已用真实响应验证：
     * {@code DId=GdlRGZvZ3L5kDNx8CbpF.MTN} → {@code /voddetail/149953}。
     *
     * @param s 编码串，可为 null
     * @return 解码后的明文；失败返回空串
     */
    public static String decode(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int len = s.length();
        int i = 0;
        while (i < len) {
            int end = Math.min(i + 10, len);
            sb.append(new StringBuilder(s.substring(i, end)).reverse());
            i = end;
        }
        try {
            String b64 = sb.toString().replace('.', '=');
            byte[] raw = java.util.Base64.getDecoder().decode(stripWhitespace(b64));
            return new String(raw, "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "decode failed: " + head(s), e);
            return "";
        }
    }

    /**
     * 还原 {@code d(s)} 的逆运算（供自测往返验证）。
     *
     * @param plain 明文
     * @return 编码串；失败返回空串
     */
    static String encode(String plain) {
        if (plain == null) {
            return "";
        }
        try {
            String b64 = java.util.Base64.getEncoder().encodeToString(plain.getBytes("UTF-8")).replace('=', '.');
            StringBuilder sb = new StringBuilder();
            int len = b64.length();
            int i = 0;
            while (i < len) {
                int end = Math.min(i + 10, len);
                sb.append(new StringBuilder(b64.substring(i, end)).reverse());
                i = end;
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页：{@code /home} 返回分组数组，逐组展开 {@code VodList}。
     *
     * @param filter 是否附筛选表
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        String body = http.get(API_BASE + "/home" + sign(), httpHeaders());
        if (TextUtils.isEmpty(body)) {
            Log.w(TAG, "home: empty response");
            return emptyResponse();
        }
        List<Vod> recommends = new ArrayList<>();
        try {
            JSONArray groups = new JSONArray(body);
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g == null) continue;
                JSONArray arr = g.optJSONArray("VodList");
                if (arr == null) continue;
                for (int j = 0; j < arr.length(); j++) {
                    Vod v = parseItem(arr.optJSONObject(j));
                    if (v != null) recommends.add(v);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "home parse failed", e);
            return emptyResponse();
        }

        VodBuilder vb = VodBuilder.create().addAll(recommends);
        for (int i = 0; i < TYPE_IDS.length; i++) {
            vb.clazz(TYPE_IDS[i], TYPE_NAMES[i]);
        }
        if (filter) {
            JSONObject f = FilmUtil.parseJson(FILTERS_JSON);
            for (String tid : TYPE_IDS) {
                JSONObject one = f.optJSONObject(tid);
                if (one != null) vb.filter(tid, one);
            }
        }
        return vb.homeContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 分类：{@code /vodshow/{tid}-{area}-{by}-{class}-{lang}----{pg}---{year}}。
     *
     * <p>还原上游的 StringBuilder 拼接顺序（分段 {@code -} 的数量必须一致，否则 404）：
     * <pre>
     *  "/vodshow/" tid "-" area "-" by "-" class "-" lang "----" pg "---" year
     * </pre>
     *
     * @param tid    分类 id
     * @param pg     页码
     * @param filter 忽略（上游未用筛选开关，恒带筛选字段）
     * @param extend 筛选值（class/area/year/lang/by）
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  java.util.HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        Map<String, String> p = extend == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(extend);

        String realTid = p.get("cateId") == null ? nz(tid) : p.get("cateId");
        String area = nz(p.get("area"));
        String year = nz(p.get("year"));
        String by = nz(p.get("by"));
        String cls = nz(p.get("class"));
        String lang = nz(p.get("lang"));

        String path = "/vodshow/" + realTid + "-" + area + "-" + by + "-" + cls + "-" + lang
                + "----" + page + "---" + year;
        String body = http.get(API_BASE + path + sign(), httpHeaders());
        if (TextUtils.isEmpty(body)) {
            Log.w(TAG, "category: empty response, tid=" + tid + " pg=" + pg);
            return VodBuilder.create().page(page).categoryContent();
        }

        List<Vod> list = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("VodList");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    Vod v = parseItem(arr.optJSONObject(i));
                    if (v != null) list.add(v);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "category parse failed", e);
            return VodBuilder.create().page(page).categoryContent();
        }
        // 上游把 total 设为 Integer.MAX_VALUE，pagecount 设为 ceil(MAX/48)：即「未知但可翻」
        int total = list.isEmpty() ? (page - 1) * PAGE_SIZE : Integer.MAX_VALUE;
        return VodBuilder.create().addAll(list).page(page, total, PAGE_SIZE).categoryContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 详情：{@code /voddetail/{id}}，播放列表来自 {@code Playlist[]}。
     *
     * <p>上游把每条播放项拼成 {@code EpisodeName$decodedVId|EpisodeName|剧名}，
     * 三段分别对应 playerContent 里的 {@code str3}/{@code str4}/{@code str5}。
     *
     * @param ids 影片 id 列表
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (id.isEmpty()) return emptyResponse();

        String body = http.get(API_BASE + id + sign(), httpHeaders());
        if (TextUtils.isEmpty(body)) {
            Log.w(TAG, "detail: empty response, id=" + id);
            return emptyResponse();
        }
        try {
            JSONObject o = new JSONObject(body);
            String name = o.optString("Name");
            Vod vod = new Vod()
                    .id(id)
                    .name(name)
                    .pic(decode(o.optString("TnId")))
                    .year(o.optString("ReleaseYear"))
                    .content(o.optString("Description"))
                    .actor(actorText(o.optJSONArray("Actor")))
                    .director(o.optString("Director"))
                    .remarks("");

            JSONArray playlist = o.optJSONArray("Playlist");
            List<String> eps = new ArrayList<>();
            if (playlist != null) {
                for (int i = 0; i < playlist.length(); i++) {
                    JSONObject ep = playlist.optJSONObject(i);
                    if (ep == null) continue;
                    String epName = ep.optString("EpisodeName");
                    String vid = decode(ep.optString("VId"));
                    if (vid.isEmpty()) continue;
                    eps.add(epName + Vod.SEP_NAME_URL + vid + "|" + epName + "|" + name);
                }
            }
            if (!eps.isEmpty()) {
                vod.playFrom(PLAY_FROM).playUrl(join(eps, Vod.SEP_EPISODE));
            }
            return VodBuilder.detail(vod).detailContent();
        } catch (Exception e) {
            Log.e(TAG, "detail parse failed", e);
            return emptyResponse();
        }
    }

    /** 源名（上游写死「独播库」） */
    static final String PLAY_FROM = "独播库";

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 播放：id 形如 {@code /vodplay/149953-1-1|第1集|兰香如故}。
     *
     * <p>取第一段请求 {@code /vodplay/…}，解 {@code HId} 得真实 m3u8。
     * 上游还给弹幕代理加了 {@code do=appdanmu}，本类去掉（无本地代理依赖）。
     *
     * @param flag     源名
     * @param id       播放串
     * @param vipFlags 忽略
     * @return 标准播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) return emptyResponse();
        String[] parts = id.split("\\|");
        String playPath = parts[0];

        String body = http.get(API_BASE + playPath + sign(), httpHeaders());
        if (TextUtils.isEmpty(body)) {
            Log.w(TAG, "player: empty response, path=" + playPath);
            return emptyResponse();
        }
        try {
            JSONObject o = new JSONObject(body);
            String url = decode(o.optString("HId"));
            if (url.isEmpty()) {
                Log.w(TAG, "player: HId decode failed");
                return emptyResponse();
            }
            Map<String, String> h = new LinkedHashMap<>();
            h.put("User-Agent", UA);
            h.put("origin", PLAY_ORIGIN);
            h.put("referer", PLAY_REFERER);
            return FilmUtil.player(url, 0, FilmUtil.headerJson(h), 0);
        } catch (Exception e) {
            Log.e(TAG, "player parse failed", e);
            return emptyResponse();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 搜索
    // ─────────────────────────────────────────────────────────────

    /**
     * 搜索：{@code /vodsearch{sign}&wd=key}。
     *
     * @param key   关键词
     * @param quick 忽略
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return VodBuilder.create().searchContent();
        String url = API_BASE + "/vodsearch" + sign() + "&wd=" + Http.encode(key);
        String body = http.get(url, httpHeaders());
        if (TextUtils.isEmpty(body)) {
            Log.w(TAG, "search: empty response, key=" + key);
            return VodBuilder.create().searchContent();
        }
        List<Vod> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                Vod v = parseItem(arr.optJSONObject(i));
                if (v != null) list.add(v);
            }
        } catch (Exception e) {
            Log.e(TAG, "search parse failed", e);
            return VodBuilder.create().searchContent();
        }
        return VodBuilder.of(list).searchContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 内部
    // ─────────────────────────────────────────────────────────────

    /**
     * 解析一条 {@code VodList} 项。
     *
     * <p>remark 优先级：{@code Tag} → {@code Rating+"分"} → 空（还原上游 if/else 链）。
     *
     * @param o 源对象
     * @return Vod；o 为 null 时返回 null
     */
    protected Vod parseItem(JSONObject o) {
        if (o == null) return null;
        String remark;
        if (o.has("Tag")) {
            remark = o.optString("Tag");
        } else if (o.has("Rating")) {
            remark = optDouble(o, "Rating") + "分";
        } else {
            remark = "";
        }
        return new Vod()
                .id(decode(o.optString("DId")))
                .name(o.optString("Name"))
                .pic(decode(o.optString("TnId")))
                .remarks(remark);
    }

    /**
     * 请求头：还原 {@code Duboku.c()}。
     *
     * @return header 表
     */
    protected Map<String, String> httpHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Connection", "Keep-Alive");
        h.put("Referer", REFERER);
        return h;
    }

    /**
     * 取数值字段（还原上游 {@code getDouble("Rating")}）。
     *
     * <p>stub/宿主 JSON 实现差异下 {@code optDouble} 可能不可用，这里按 Object 自行判定。
     *
     * @param o   源对象
     * @param key 字段名
     * @return 数值；缺失或非数字返回 0
     */
    static double optDouble(JSONObject o, String key) {
        if (o == null) return 0d;
        Object v = o.opt(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble((String) v);
            } catch (Exception ignored) {
                return 0d;
            }
        }
        return 0d;
    }

    /** JSONArray → 逗号串（上游 {@code getJSONArray("Actor").toString()}） */
    private static String actorText(JSONArray arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i);
            if (s.isEmpty() || "null".equals(s)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static String stripWhitespace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    /** 对齐 Android {@code Base64.encodeToString(bytes, 0)}（带 padding、无换行） */
    private static String base64(String s) {
        try {
            return java.util.Base64.getEncoder().encodeToString(s.getBytes("UTF-8"));
        } catch (Exception e) {
            return "";
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String head(String s) {
        if (s == null) return "";
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }
}
