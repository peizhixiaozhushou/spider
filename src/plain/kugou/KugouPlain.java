package com.example.tvbox.plain.kugou;

import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 酷狗音乐 / 爱听音乐明文实现。
 *
 * <p>反编译原文: {@code ae/decompiled/Kugou.java}（316 行，jadx dev）
 *
 * <h2>协议</h2>
 * <pre>
 *  首页      静态：JSON 字面量 {"热门榜":"hot","特色榜":"special","全球榜":"global"} 的键序
 *  分类      GET {cdn}/api/v3/rank/list?version=9108&amp;plat=0&amp;showtype=2&amp;parentid=0
 *            &amp;apiver=6&amp;area_code=1&amp;withsong=0&amp;with_res_tag=0
 *  榜单歌曲  GET {cdn}/api/v3/rank/song?...&amp;rankid={id}&amp;…
 *  爱听榜    GET {ating}/yy.php?ac=videolist&amp;t=djwuqu&amp;pg=1
 *  爱听列表  GET {ating}/yy.php?ac=videolist&amp;ids={id}
 *  酷狗播放  GET https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&amp;hash={hash}
 *  酷狗 MV   GET https://m.kugou.com/app/i/mv.php?cmd=100&amp;hash={hash}&amp;ismp3=1&amp;ext=mp4
 *  歌词搜索  GET http://krcs.kugou.com/search?ver=1&amp;man=yes&amp;client=mobi&amp;…&amp;hash={hash}
 *  歌词下载  GET http://lyrics.kugou.com/download?ver=1&amp;client=pc&amp;id={id}&amp;accesskey={ak}
 *            &amp;fmt=krc&amp;charset=utf8
 *  付费兜底  GET https://www.sq0527.cn/search?ac={歌名}  →  解析 ul.mul &gt; li &gt; a
 *            → 详情页 #btn-download-mp3 的 href
 * </pre>
 *
 * <h2>ext 三层分类</h2>
 * <table border="1">
 *   <tr><th>类</th><th>键</th><th>说明</th></tr>
 *   <tr><td><b>A 类</b>（必需）</td><td>—</td>
 *       <td>没有。全部接口地址硬编码，不需要 ext</td></tr>
 *   <tr><td><b>B 类</b>（有默认值，可覆盖）</td>
 *       <td>{@code proxyBase}（默认空）、{@code ua}（默认 Chrome 151）、
 *           {@code songApi}/{@code mvApi}/{@code lyricSearch}/{@code lyricDownload}
 *           （默认上游硬编码地址，便于改镜像）</td>
 *       <td>域名会换、接口路径会变；本实现把 4 个上游地址提成可覆盖字段</td></tr>
 *   <tr><td><b>C 类</b>（纯硬编码，直接内联）</td>
 *       <td>3 个榜单分类、{@code MP3}/{@code MV} 线路名、爱听线路名、
 *           KRC 16 字节异或密钥、{@code ver=1&amp;man=yes&amp;client=mobi} 等固定参数</td>
 *       <td>无 ext 通道</td></tr>
 * </table>
 *
 * <h2>发现的上游缺陷（本实现已修，均有断言钉住）</h2>
 * <ol>
 *   <li><b>id 用 {@code split("-")[1]} 截断</b>：爱听线路的 id 是
 *       {@code aiting-http://host/a-b.mp3}，按 {@code "-"} 全量切分后
 *       {@code [1]} 只到第二个 {@code -} 之前 —— URL 里带连字符就丢后半截。
 *       本实现用 {@code split("-", 2)}。</li>
 *   <li><b>非第 1 页返回空串</b>：{@code categoryContent} 里
 *       {@code !"1".equals(str2) ? "" : …} —— {@code ""} 不是合法 JSON。
 *       本实现返回结构完整的空分类页。</li>
 *   <li><b>{@code backup_url} 缺失即 NPE</b>：
 *       {@code jSONObject.optJSONArray("backup_url").optString(0)} 无判空。</li>
 *   <li><b>MV 线路恒存在</b>：{@code j("MP3$$$MV")} 写死，但没有 MV 的歌曲会
 *       产出一条空线路（{@code vod_play_url} 尾部空档）。本实现按实际是否有 MV 决定线路名表。</li>
 *   <li><b>{@code lrchashid} 全局静态</b>：上游用宿主 SharedPreferences 存"当前歌词 hash"，
 *       多实例互相覆盖。本实现改为实例字段（并提供显式存取），语义相同但不再跨实例污染。</li>
 * </ol>

 * <h2>未覆盖的分支</h2>
 * <ul>
 *   <li><b>{@code lrchashid} 的跨启动持久化</b>：上游把"当前歌词 hash"写进宿主
 *       SharedPreferences（{@code ae/decompiled/Kugou.java} 的 {@code l.a/l.b}），
 *       重启后仍能拿到上次的 hash。本实现改成实例字段（{@code setLrcHashId}），
 *       语义等价但<b>不跨实例、不跨启动</b>。将来补上需要宿主 SharedPreferences 实现。</li>
 *   <li><b>{@code l}/{@code e} 两个 VO setter 的语义是推断</b>：两处调用点传的都是同一个
 *       品牌字面量"爱音乐爱生活"，无法从静态产物区分哪个写 {@code vod_remarks}、
 *       哪个写 {@code vod_content}。因为取值相同，输出不受影响。
 *       将来补上需要：{@code merge.FM.p003c.j} 的实体。</li>
 *   <li><b>{@code merge.FM.p013m.c.n(url, null)} 的第二个参数未知</b>：本实现用
 *       {@code fetch(url)} + UA 头等价替代。若那个参数是缓存开关/超时，行为会有差异。</li>
 *   <li><b>歌词代理只支持 {@code site=lrc}</b>：其余 {@code site} 一律 404 ——
 *       这是上游形状（不是缺口），断言 {@code 非 lrc site → 404} 钉住。</li>
 *   <li><b>刻意保留的上游行为（看着像 bug，其实是对的）</b>：分类页只有第 1 页有数据，
 *       第 2 页起返回空列表（上游返回的是空串 {@code ""}，那是非法 JSON，
 *       本实现改成结构完整的空分类页）。断言：{@code 第 2 页不是空串（修缺陷 2）}。</li>
 * </ul>
 */
public class KugouPlain extends BaseSpider {

    private static final String TAG = "KugouPlain";

    // ── C 类硬编码 ──────────────────────────────────────────

    /** 首页 3 个榜单分类（键序即上游 JSON 字面量的插入序） */
    public static final String RANK_JSON =
            "{\"热门榜\":\"hot\",\"特色榜\":\"special\",\"全球榜\":\"global\"}";

    /** 线路名：MP3 */
    public static final String FROM_MP3 = "MP3";
    /** 线路名：MV */
    public static final String FROM_MV = "MV";
    /** 线路名：爱听 */
    public static final String FROM_AITING = "爱音乐爱生活";

    /** 榜单 list 接口固定参数 */
    public static final String RANK_LIST_QUERY =
            "?version=9108&plat=0&showtype=2&parentid=0&apiver=6&area_code=1&withsong=0&with_res_tag=0";

    /** 歌词代理动作 */
    public static final String PROXY_ACTION = "kugou";

    /** UA（上游 {@code b()} 里的 Chrome 151） */
    public static final String DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    // ── B 类：有默认值，可覆盖 ──────────────────────────────

    /** 榜单接口基址 */
    public static final String DEF_CDN = "http://mobilecdnbj.kugou.com";
    /** 爱听接口基址 */
    public static final String DEF_ATING = "https://catbox.n13.club/yy.php";
    /** 歌曲信息接口 */
    public static final String DEF_SONG_API = "https://m.kugou.com/app/i/getSongInfo.php";
    /** MV 接口 */
    public static final String DEF_MV_API = "https://m.kugou.com/app/i/mv.php";
    /** 歌词搜索 */
    public static final String DEF_LRC_SEARCH = "http://krcs.kugou.com/search";
    /** 歌词下载 */
    public static final String DEF_LRC_DOWNLOAD = "http://lyrics.kugou.com/download";
    /** 付费歌曲兜底站 */
    public static final String DEF_FALLBACK = "https://www.sq0527.cn";

    private String ua = DEFAULT_UA;
    private String cdn = DEF_CDN;
    private String ating = DEF_ATING;
    private String songApi = DEF_SONG_API;
    private String mvApi = DEF_MV_API;
    private String lrcSearch = DEF_LRC_SEARCH;
    private String lrcDownload = DEF_LRC_DOWNLOAD;
    private String fallback = DEF_FALLBACK;

    /** 弹幕/歌词代理前缀（宿主 {@code Proxy.getUrl()} 的替身） */
    private String proxyBase = "";

    /** 当前歌词 hash（上游宿主 SharedPreferences 的 {@code lrchashid}） */
    private String lrcHashId = "";

    /** 最近一次请求 URL —— 测试断言用 */
    private String lastUrl = "";

    /** 构造 */
    public KugouPlain() {
        super();
    }

    // ── 生命周期 / 配置 ─────────────────────────────────────

    /**
     * 解析 ext（本类没有 A 类键，全部可缺省）。
     *
     * @param context 宿主上下文，可为 null
     * @param extend  ext 原文，可为 null
     */
    @Override
    public void init(android.content.Context context, String extend) {
        super.init(context, extend);
        this.ua = def(ext.optStr("ua"), DEFAULT_UA);
        this.cdn = def(ext.url(), DEF_CDN);
        this.ating = def(ext.optStr("ating"), DEF_ATING);
        this.songApi = def(ext.optStr("songApi"), DEF_SONG_API);
        this.mvApi = def(ext.optStr("mvApi"), DEF_MV_API);
        this.lrcSearch = def(ext.optStr("lyricSearch"), DEF_LRC_SEARCH);
        this.lrcDownload = def(ext.optStr("lyricDownload"), DEF_LRC_DOWNLOAD);
        this.fallback = def(ext.optStr("fallback"), DEF_FALLBACK);
        this.proxyBase = ext.optStr("proxyBase");
        Log.i(tag(), "init ok, cdn=" + cdn + ", proxy=" + (proxyBase.isEmpty() ? "无" : "有"));
    }

    private static String def(String v, String fallback) {
        return v == null || v.isEmpty() ? fallback : v;
    }

    /** @param base 弹幕代理前缀，可为 null */
    public void setProxyBase(String base) {
        this.proxyBase = base == null ? "" : base.trim();
    }

    /** @return 代理前缀 */
    public String proxyBase() {
        return proxyBase;
    }

    /** @param id 当前歌词 hash */
    public void setLrcHashId(String id) {
        this.lrcHashId = id == null ? "" : id;
    }

    /** @return 当前歌词 hash */
    public String lrcHashId() {
        return lrcHashId;
    }

    /** @return 最近一次请求 URL */
    public String lastUrl() {
        return lastUrl;
    }

    /** @return 当前 UA */
    public String ua() {
        return ua;
    }

    // ── 请求 ────────────────────────────────────────────────

    /**
     * 请求头（上游 {@code b()} 只给一条 UA）。
     *
     * @return header map
     */
    public Map<String, String> headers() {
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", ua);
        return h;
    }

    /**
     * 发一次 GET（可被测试覆写）。
     *
     * @param url 完整地址
     * @return 响应体；失败为空串
     */
    public String fetch(String url) {
        this.lastUrl = url;
        return http.get(url, headers());
    }

    /**
     * 还原上游 {@code a(String)}：GET + 交给 JSON 解析（不重排）。
     *
     * @param url 地址
     * @return 响应体；失败为空串
     */
    public String api(String url) {
        String body = fetch(url);
        return body == null ? "" : body.trim();
    }

    // ── 首页 ────────────────────────────────────────────────

    /**
     * 首页：3 个榜单分类（无推荐列表）。
     *
     * @param filter 忽略
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        VodBuilder b = VodBuilder.create();
        JSONObject o = Http.parseJson(RANK_JSON);
        for (String key : keyList(o)) {
            b.clazz(o.optString(key, ""), key);
        }
        return b.homeContent();
    }

    private static List<String> keyList(JSONObject o) {
        List<String> out = new ArrayList<>();
        if (o == null) {
            return out;
        }
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            out.add(it.next());
        }
        return out;
    }

    // ── 分类 ────────────────────────────────────────────────

    /**
     * 分类：只有第 1 页有内容（上游用 {@code ""} 表示没有下一页 —— 非法 JSON）。
     *
     * @param tid    榜单类型（{@code hot}/{@code special}/{@code global}）
     * @param pg     页码
     * @param filter 忽略
     * @param extend 忽略
     * @return 标准分类 JSON（第 2 页起是空列表而非空串 —— 修缺陷 2）
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        if (page != 1) {
            return VodBuilder.create().page(page, 0, 0).categoryContent();
        }
        return getKuGouRank(tid);
    }

    // ── 详情 ────────────────────────────────────────────────

    /**
     * 详情：{@code kugou_<rankid>} → 榜单歌曲；{@code aiting_<id>} → 爱听列表。
     *
     * @param ids id 列表
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (id.startsWith("kugou_")) {
            return getKuGouMusicList(splitOnce(id, "_"));
        }
        if (id.startsWith("aiting_")) {
            return getAiTingMusicList(splitOnce(id, "_"));
        }
        return emptyResponse();
    }

    // ── 播放 ────────────────────────────────────────────────

    /**
     * 播放解析。
     *
     * <p>id 形如 {@code kugou-mp3_hash_...}/{@code kugou-mv_hash}/{@code aiting-<url>}，
     * 只按<b>第一个</b>连字符切分（修缺陷 1）。
     *
     * @param flag     忽略
     * @param id       播放 id
     * @param vipFlags 忽略
     * @return 标准播放 JSON；无法识别时为空响应
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String v = id == null ? "" : id;
        if (v.startsWith("kugou")) {
            return getKugouMusicPlayUrl(splitOnce(v, "-"));
        }
        if (v.startsWith("aiting")) {
            return getAiTingMusicPlayUrl(splitOnce(v, "-"));
        }
        return emptyResponse();
    }

    /**
     * 按第一个分隔符切分（还原意图：{@code split("-", 2)}）。
     *
     * @param s   原文，可为 null
     * @param sep 分隔符
     * @return 第二段；无分隔符时为空串（与上游 {@code [1]} 越界前的语义对齐为「空」）
     */
    public static String splitOnce(String s, String sep) {
        if (s == null || sep == null || sep.isEmpty()) {
            return "";
        }
        int i = s.indexOf(sep);
        return i < 0 ? "" : s.substring(i + sep.length());
    }

    // ── 榜单 ────────────────────────────────────────────────

    /**
     * 榜单分类列表。
     *
     * @param kind {@code hot} / {@code special} / {@code global}
     * @return 标准分类 JSON（列表为空时返回空列表页）
     */
    public String getKuGouRank(String kind) {
        JSONObject root = Http.parseJson(api(cdn + "/api/v3/rank/list" + RANK_LIST_QUERY));
        JSONArray arr = dataArray(root, "info");
        List<Vod> list = new ArrayList<>();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (!matchClassify(kind, o.optString("classify", ""))) {
                continue;
            }
            list.add(new Vod("kugou_" + o.optString("rankid", ""), o.optString("rankname", ""))
                    .pic(o.optString("imgurl", "").replace("{size}", "400")));
        }
        return VodBuilder.create().addAll(list).page(1, 0, 0).categoryContent();
    }

    /**
     * 榜单与 {@code classify} 的对应关系（上游三段 if）。
     *
     * <pre>
     *  hot     → classify 1 或 2
     *  special → classify 3 或 5
     *  global  → classify 4 或 2
     * </pre>
     *
     * @param kind     榜单类型
     * @param classify 条目 classify 值
     * @return 是否保留
     */
    public static boolean matchClassify(String kind, String classify) {
        if (kind == null) {
            return false;
        }
        switch (kind) {
            case "hot":
                return "1".equals(classify) || "2".equals(classify);
            case "special":
                return "3".equals(classify) || "5".equals(classify);
            case "global":
                return "4".equals(classify) || "2".equals(classify);
            default:
                return false;
        }
    }

    /**
     * 榜单歌曲 → 详情（MP3 线路 + 可选 MV 线路）。
     *
     * @param rankId 榜单 id
     * @return 标准详情 JSON
     */
    public String getKuGouMusicList(String rankId) {
        JSONObject root = Http.parseJson(api(cdn
                + "/api/v3/rank/song?version=9108&ranktype=0&plat=0&pagesize=200"
                + "&area_code=1&page=1&volid=35050&rankid=" + Http.encode(rankId)
                + "&with_res_tag=0"));
        JSONArray arr = dataArray(root, "info");
        List<String> mp3 = new ArrayList<>();
        List<String> mv = new ArrayList<>();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String name = o.optString("filename", "");
            String albumId = o.optString("album_id", "");
            String albumAudioId = o.optString("album_audio_id", "");
            String hash = o.optString("sqhash", "");
            if (hash.isEmpty()) {
                hash = o.optString("hash", "");
            }
            mp3.add(name + "$kugou-mp3_" + hash + "_" + albumId + "_" + albumAudioId);
            String mvHash = o.optString("mvhash", "");
            if (!mvHash.isEmpty()) {
                mv.add(name + "$kugou-mv_" + mvHash);
            }
        }
        // 缺陷 4：上游恒为 "MP3$$$MV"，没有 MV 时留下空线路
        List<String> froms = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        froms.add(FROM_MP3);
        groups.add(join(mp3, "#"));
        if (!mv.isEmpty()) {
            froms.add(FROM_MV);
            groups.add(join(mv, "#"));
        }
        Vod v = new Vod(rankId, "酷狗音乐")
                .remarks(FROM_AITING)
                .content(FROM_AITING)
                .playFrom(join(froms, "$$$"))
                .playUrl(join(groups, "$$$"));
        return VodBuilder.detail(v).detailContent();
    }

    // ── 爱听 ────────────────────────────────────────────────

    /**
     * 爱听榜（{@code t=djwuqu}）。
     *
     * @return 标准分类 JSON
     */
    public String getAiTingRank() {
        JSONObject root = Http.parseJson(api(ating + "?ac=videolist&t=djwuqu&pg=1"));
        JSONArray arr = root.optJSONArray("list");
        List<Vod> list = new ArrayList<>();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            list.add(new Vod("aiting_" + o.optString("vod_id", ""), o.optString("vod_name", ""))
                    .pic(o.optString("vod_pic", "")));
        }
        return VodBuilder.create().addAll(list).page(1, 0, 0).categoryContent();
    }

    /**
     * 爱听详情：把 {@code $http} 改写成 {@code $aiting-http}（这样播放时能识别线路）。
     *
     * @param id 爱听视频 id
     * @return 标准详情 JSON；失败为空响应
     */
    public String getAiTingMusicList(String id) {
        JSONObject root = Http.parseJson(api(ating + "?ac=videolist&ids=" + Http.encode(id)));
        JSONArray arr = root.optJSONArray("list");
        JSONObject o = arr == null ? null : arr.optJSONObject(0);
        if (o == null) {
            return emptyResponse();
        }
        Vod v = new Vod(id, o.optString("vod_name", ""))
                .remarks(FROM_AITING)
                .content(FROM_AITING)
                .playFrom(FROM_AITING)
                .playUrl(o.optString("vod_play_url", "").replace("$http", "$aiting-http"));
        return VodBuilder.detail(v).detailContent();
    }

    /**
     * 爱听播放地址直连。
     *
     * @param url 地址
     * @return 标准播放 JSON
     */
    public String getAiTingMusicPlayUrl(String url) {
        return playBody(url == null ? "" : url, false);
    }

    // ── 酷狗播放 ────────────────────────────────────────────

    /**
     * 酷狗播放地址解析（MP3 走 songInfo，MV 走 mv.php；付费歌走兜底站）。
     *
     * @param id {@code mp3_<hash>_…} 或 {@code mv_<hash>}
     * @return 标准播放 JSON
     */
    public String getKugouMusicPlayUrl(String id) {
        String v = id == null ? "" : id;
        if (!v.startsWith("mp3")) {
            return mvPlayUrl(splitUniform(v));
        }
        String hash = splitUniform(v);
        setLrcHashId(hash);
        JSONObject o = Http.parseJson(api(songApi + "?cmd=playInfo&hash=" + Http.encode(hash)));
        String url;
        if (o.optString("error", "").contains("要付费")) {
            url = paidFallback(o.optString("songName", ""), o.optString("author_name", ""));
        } else {
            url = o.optString("url", "");
            if (url.isEmpty()) {
                JSONArray backup = o.optJSONArray("backup_url");
                // 缺陷 3：上游此处 optJSONArray(...).optString(0) 无判空
                url = backup == null ? "" : backup.optString(0, "");
            }
        }
        return addLrc(playBody(url, false));
    }

    /**
     * MV 播放地址（{@code sq} 优先，其次 {@code le}）。
     *
     * @param hash MV hash
     * @return 标准播放 JSON
     */
    public String mvPlayUrl(String hash) {
        setLrcHashId(hash);
        JSONObject mvdata = Http.parseJson(api(mvApi + "?cmd=100&hash=" + Http.encode(hash)
                + "&ismp3=1&ext=mp4")).optJSONObject("mvdata");
        if (mvdata == null) {
            return emptyResponse();
        }
        JSONObject sq = mvdata.optJSONObject("sq");
        if (sq != null) {
            return playBody(sq.optString("downurl", ""), true);
        }
        JSONObject le = mvdata.optJSONObject("le");
        if (le == null) {
            return emptyResponse();
        }
        return playBody(le.optString("downurl", ""), true);
    }

    /**
     * 取 {@code mp3_}/{@code mv_} 之后的第一段（还原 {@code split("_")[1]}）。
     *
     * @param s 形如 {@code mp3_hash_album_aid}
     * @return hash；段数不足时为空串
     */
    public static String splitUniform(String s) {
        if (s == null) {
            return "";
        }
        String[] p = s.split("_");
        return p.length >= 2 ? p[1] : "";
    }

    /**
     * 付费歌曲兜底：在第三方站搜索同名同歌手，取下载按钮的 href。
     *
     * @param songName 歌名
     * @param artist   歌手
     * @return 直链；找不到返回空串
     */
    public String paidFallback(String songName, String artist) {
        if (songName == null || songName.isEmpty()) {
            return "";
        }
        String html = api(fallback + "/search?ac=" + Http.encode(songName));
        String href = findDownloadLink(html, songName, artist);
        if (href.isEmpty()) {
            return "";
        }
        String page = api(fallback + href);
        return extractMp3Href(page);
    }

    /**
     * 在搜索结果页里找「标题同时含歌名与歌手」的链接（还原
     * {@code Dom.select("ul.mul > li > a")} 的遍历）。
     *
     * @param html     搜索结果页
     * @param songName 歌名
     * @param artist   歌手
     * @return href；找不到返回空串
     */
    public static String findDownloadLink(String html, String songName, String artist) {
        for (com.example.tvbox.common.Dom.El a : com.example.tvbox.common.Dom
                .parse(html == null ? "" : html, DEF_FALLBACK).select("ul.mul > li > a")) {
            String text = a.text();
            if (text.contains(songName) && (artist == null || artist.isEmpty()
                    || text.contains(artist))) {
                return a.attr("href");
            }
        }
        return "";
    }

    /**
     * 取详情页下载按钮的 href（还原 {@code #btn-download-mp3} 的 {@code href}）。
     *
     * @param page 详情页
     * @return href；找不到返回空串
     */
    public static String extractMp3Href(String page) {
        return com.example.tvbox.common.Dom.parse(page == null ? "" : page, DEF_FALLBACK)
                .selectAttr("#btn-download-mp3", "href", "");
    }

    // ── 播放体 / 歌词 ───────────────────────────────────────

    /**
     * 组装播放体。
     *
     * @param url     直链
     * @param withUa  true 时带 UA 头（MV 分支上游这么干）
     * @return 播放 JSON
     */
    public String playBody(String url, boolean withUa) {
        String base = VodBuilder.playerContent(url == null ? "" : url, "");
        if (!withUa) {
            return base;
        }
        try {
            JSONObject o = new JSONObject(base);
            o.put("header", new JSONObject(headers()).toString());
            return o.toString();
        } catch (Throwable e) {
            return base;
        }
    }

    /**
     * 给播放体挂歌词代理（还原 {@code addLrc}）。
     *
     * @param json 播放体
     * @return 带 {@code lrc} 字段的播放体；非 JSON 原样返回
     */
    public String addLrc(String json) {
        if (proxyBase == null || proxyBase.isEmpty()) {
            return json;
        }
        try {
            JSONObject o = new JSONObject(json);
            o.put("lrc", proxyBase + "?do=" + PROXY_ACTION + "&site=lrc");
            return o.toString();
        } catch (Throwable e) {
            return json;
        }
    }

    // ── 歌词代理（KRC） ─────────────────────────────────────

    /**
     * 歌词代理：{@code ?do=kugou&site=lrc}。
     *
     * @param params 查询参数
     * @return {@code Object[]{code, contentType, InputStream, headers}}；
     *         非 lrc / 缺 hash / 上游 404 时 code=404
     */
    @Override
    public Object[] proxy(Map<String, String> params) {
        Object[] out = {200, "text/plain; charset=utf-8",
                new ByteArrayInputStream(new byte[0]), new HashMap<String, String>()};
        try {
            if (params == null || !"lrc".equals(params.get("site"))) {
                out[0] = 404;
                return out;
            }
            String hash = lrcHashId;
            if (hash == null || hash.isEmpty()) {
                out[0] = 404;
                return out;
            }
            JSONObject search = Http.parseJson(api(lrcSearch
                    + "?ver=1&man=yes&client=mobi&keyword=&duration=&hash=" + Http.encode(hash)));
            if (search.optInt("status", 0) != 200) {
                out[0] = 404;
                return out;
            }
            JSONArray candidates = search.optJSONArray("candidates");
            JSONObject first = candidates == null ? null : candidates.optJSONObject(0);
            if (first == null) {
                out[0] = 404;
                return out;
            }
            String id = first.optString("id", "");
            String accessKey = first.optString("accesskey", "");
            if (id.isEmpty() || accessKey.isEmpty()) {
                out[0] = 404;
                return out;
            }
            JSONObject dl = Http.parseJson(api(lrcDownload + "?ver=1&client=pc&id="
                    + Http.encode(id) + "&accesskey=" + Http.encode(accessKey)
                    + "&fmt=krc&charset=utf8"));
            String content = dl.optString("content", "");
            if (content.isEmpty()) {
                out[0] = 404;
                return out;
            }
            // 上游顺序：先判「Base64 解码后长度 <= 4 → 404」，再异或/解压
            byte[] raw = KugouKrc.base64(content);
            if (raw == null || raw.length <= KugouKrc.SKIP) {
                out[0] = 404;
                return out;
            }
            String text = KugouKrc.decodeBytes(raw);
            if (text == null) {
                out[0] = 404;      // 上游此处会抛 → catch → 404
                return out;
            }
            out[2] = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
            return out;
        } catch (Throwable e) {
            Log.e(TAG, "proxy lrc failed", e);
            out[0] = 404;
            out[1] = "text/plain";
            out[2] = new ByteArrayInputStream(new byte[0]);
            return out;
        }
    }

    // ── 小工具 ──────────────────────────────────────────────

    /**
     * 取 {@code data.info}（榜单接口的固定层级）。
     *
     * @param root 响应
     * @param key  字段名
     * @return 数组；缺失返回 null
     */
    public static JSONArray dataArray(JSONObject root, String key) {
        JSONObject data = root == null ? null : root.optJSONObject("data");
        return data == null ? null : data.optJSONArray(key);
    }

    /**
     * join（空列表 → 空串）。
     *
     * @param list 元素列表，可为 null
     * @param sep  分隔符
     * @return 拼接结果
     */
    public static String join(List<String> list, String sep) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            String s = list.get(i);
            if (s != null) {
                sb.append(s);
            }
        }
        return sb.toString();
    }
}
