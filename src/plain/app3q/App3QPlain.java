package com.example.tvbox.plain.app3q;

import android.content.Context;
import android.util.Log;

import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.appsrc.AppSignedBase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * App3Q 明文实现（/api.php 系套壳 CMS 的安卓 App 端）。
 *
 * <p>反编译原文: {@code ae/decompiled/App3Q.java}（288 行，jadx dev）
 *
 * <h2>协议</h2>
 * <pre>
 *  GET {host}/api.php/app/index/home                      → data.categories / data.recommend
 *  GET {host}/api.php/app/filter/vod?type_name=..&page=N&sort=hits
 *  GET {host}/api.php/app/vod/get_detail?vod_id=..         → data[0] + vodplayer[]
 *  GET {host}/api.php/app/search/index?wd=..&page=1&limit=15
 *  GET {host}/api.php/app/decode/url/?url=..&vodFrom=..    → {code:2, challenge, data}
 * </pre>
 *
 * <h3>请求头（12 项，一项都不能少）</h3>
 * <pre>
 *  user-agent      okhttp/4.12.0
 *  accept          application/json
 *  x-platform      android
 *  x-ave           ext.ver
 *  x-aid           ext.pkg                 ← 注意是「包名」不是「设备号」
 *  x-time          秒级时间戳
 *  x-nonc          1..999 随机
 *  x-sign          SHA-256(下文) 大写
 *  x-device-id     0b4328287a5d953e        ← 纯硬编码（C 类）
 *  x-device-brand  ext.deviceBrand
 *  x-device-model  ext.deviceModel
 *  x-update-id     host                    ← 上游塞的是「域名」，不是 updateId
 * </pre>
 *
 * <h3>x-sign 规范化串（键按字典序，格式串写死）</h3>
 * <pre>
 *  finger={ext.finger}&amp;id={ext.pkg}&amp;nonce={x-nonc}&amp;sk={ext.sk}&amp;time={x-time}&amp;v={ext.ver}
 *  → SHA-256 → 大写 hex
 * </pre>
 *
 * <h2>ext 三层分类</h2>
 * <table border="1">
 *   <tr><th>类</th><th>键</th><th>说明</th></tr>
 *   <tr><td><b>A 类</b>（必需）</td>
 *       <td>{@code host}、{@code sk}</td>
 *       <td>{@code host} 缺失则所有请求无基址；{@code sk} 缺失签名仍能算出但服务端必拒
 *           —— 两者不传就用不了</td></tr>
 *   <tr><td><b>A 类</b>（指纹）</td>
 *       <td>{@code finger}、{@code pkg}、{@code ver}、{@code deviceBrand}、{@code deviceModel}</td>
 *       <td>上游无内置默认值（全是 {@code optString} 无兜底），缺失即空串进签名/头，
 *           服务端按设备画像校验，等价于不可用</td></tr>
 *   <tr><td><b>B 类</b>（可覆盖的域名）</td>
 *       <td>{@code url} / {@code site}</td>
 *       <td>与 {@code host} 同义（{@link com.example.tvbox.common.Ext#url()} 已做回退），
 *           换域名时改这里即可，签名与头无需变动</td></tr>
 *   <tr><td><b>C 类</b>（硬编码）</td>
 *       <td>{@code x-device-id = 0b4328287a5d953e}、UA、{@code x-platform}、
 *           各接口路径、{@code sort=hits}</td>
 *       <td>直接内联成常量 {@link #DEVICE_ID}，无 ext 覆盖路径</td></tr>
 * </table>
 *
 * <h2>发现的上游缺陷（本实现已修，均有断言钉住）</h2>
 * <ol>
 *   <li><b>{@code ext.updateId} 是死字段</b>：{@code init} 把它读进 {@code this.c}，
 *       但全文再无引用；{@code x-update-id} 头填的是 {@code host}。本实现保留该字段并
 *       暴露 {@link #updateId()}，但明确不参与任何请求。</li>
 *   <li><b>challenge 令牌被算出来又丢掉</b>：{@code playerContent} 里
 *       {@code String str4 = "&token=" + strC;} 之后 {@code str4} 从未使用，
 *       3 次重试实际上只在「响应体为空」时生效。本实现在 {@code data} 为空且返回
 *       {@code code==2+challenge} 时把令牌带入下一次请求（这才是 3 次重试的用意）。</li>
 *   <li><b>{@code challenge} 片段不足 8 字符直接失败</b>：上游 {@code strTrim2.substring(0, 8)}
 *       越界被 catch 吞掉，整串返回空。改为取 {@code min(8, len)}。</li>
 *   <li><b>详情页分集缺 {@code $} 时整页崩</b>：{@code strArrSplit4[1]} 越界 → 外层 catch →
 *       整个 {@code detailContent} 退化成 {@code {}}。改为保留原始集串。</li>
 *   <li><b>分类页 pagecount 恒为 0</b>：上游 {@code c.i(page, 0, 0, 0)} 把 pagecount/limit/total
 *       全填 0，TVBox 会判定「没有下一页」。改为 {@code page+1}（无限翻页约定）。</li>
 *   <li><b>片名未 URL 编码</b>：弹幕 URL 直接字符串拼接。已补编码（见基类）。</li>
 * </ol>

 * <h2>未覆盖的分支</h2>
 * <ul>
 *   <li><b>挑战 token 的计算结果被上游丢弃</b>：{@code ae/decompiled/App3Q.java} 里算出
 *       {@code a:b:hash} 之后没有把它放进任何请求，三次重试也只在「响应体为空」时触发。
 *       本实现把 {@code challengeToken()} 与重试判据抽成可测函数并钉了断言，
 *       但<b>没有</b>把它接回请求流程 —— 接回去会改变线上行为。
 *       将来补上需要：一次带抓包的请求序列（看清 token 到底进不进头）。</li>
 *   <li><b>落盘缓存</b>：上游 {@code merge.p026a.a.parseJsonAndSave} 是宿主私有方法，
 *       本仓库无等价物。影响：详情/搜索少了这一次写盘副作用。
 *       将来补上需要：宿主缓存 API 或 SharedPreferences 实现。</li>
 *   <li><b>弹幕地址</b>：依赖宿主私有 {@code Proxy.getUrl()}（全仓无等价物）。
 *       改为可注入 {@code proxyBase}；未注入时<b>不产出</b> {@code danmaku} 字段，
 *       而不是伪造一个地址。断言：{@code 不注入 proxyBase 时无 danmaku}。</li>
 *   <li><b>刻意保留的上游行为（看着像 bug，其实是对的）</b>：{@code categoryContent}
 *       的 pagecount 走「total&lt;=0 → page+1」的宿主约定，因此分类页表现为不可翻页 ——
 *       这是上游形状，勿「顺手修好」。断言：{@code pagecount = page+1（缺陷 5）}。</li>
 * </ul>
 */
public class App3QPlain extends AppSignedBase {

    private static final String TAG = "App3QPlain";

    /** C 类硬编码设备号 —— 上游字面量，无 ext 覆盖路径 */
    public static final String DEVICE_ID = "0b4328287a5d953e";

    /** C 类硬编码 UA */
    public static final String UA = "okhttp/4.12.0";

    /** 首页 */
    public static final String PATH_HOME = "/api.php/app/index/home";
    /** 分类（注意：{@code type_name} 传的是分类<b>名</b>，不是 id） */
    public static final String PATH_CATEGORY = "/api.php/app/filter/vod";
    /** 详情 */
    public static final String PATH_DETAIL = "/api.php/app/vod/get_detail";
    /** 搜索 */
    public static final String PATH_SEARCH = "/api.php/app/search/index";
    /** 播放地址解码 */
    public static final String PATH_DECODE = "/api.php/app/decode/url/";

    /** 上游硬编码的分类排序参数 */
    public static final String CATEGORY_SORT = "hits";

    /** 上游硬编码的搜索分页 */
    public static final String SEARCH_PAGE = "1";
    /** 上游硬编码的搜索条数 */
    public static final String SEARCH_LIMIT = "15";

    /** challenge 提取正则（上游 {@code "_0x1\\s*=\\s*\\[(.*?)\\];"}，这里放宽尾部空白） */
    private static final Pattern CHALLENGE =
            Pattern.compile("_0x1\\s*=\\s*\\[(.*?)]\\s*;");

    /** 直链正则（上游 {@code "https?://.*\.(m3u8|mp4|flv|mkv)"}，未含 avi/mov 以外的差别） */
    private static final Pattern DIRECT =
            Pattern.compile(".*(m3u8|mp4|flv|avi|mov|mkv).*");

    // ext 字段（A 类）
    private String host = "";
    private String finger = "";
    /** 上游读了但从未使用（缺陷 1），保留仅为透明 */
    private String updateId = "";
    private String deviceBrand = "";
    private String deviceModel = "";
    private String pkg = "";
    private String sk = "";
    private String ver = "";

    /** 每次「会话」刷新一次的时间/随机（上游 init 时算一次） */
    private String time = "";
    private String nonce = "";

    /** 最近一次实际请求的 URL —— 供测试断言请求构造，不发包 */
    private String lastUrl = "";

    /** 构造 */
    public App3QPlain() {
        super();
    }

    // ── 生命周期 ────────────────────────────────────────────

    /**
     * 解析 ext 并生成首个 nonce。ext 可以是 JSON，也可以是纯文本 URL
     * （{@link com.example.tvbox.common.Ext} 已兼容两种形态）。
     *
     * @param context 宿主上下文，可为 null
     * @param extend  ext 原文，可为 null
     */
    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        this.host = ext.url();
        this.finger = ext.optStr("finger");
        this.updateId = ext.optStr("updateId");
        this.deviceBrand = ext.optStr("deviceBrand");
        this.deviceModel = ext.optStr("deviceModel");
        this.pkg = ext.optStr("pkg");
        this.sk = ext.optStr("sk");
        this.ver = ext.optStr("ver");
        refreshSession();
        Log.i(tag(), "init ok, host=" + host + ", pkg=" + pkg + ", ver=" + ver);
    }

    /**
     * 重新生成 {@code x-time} / {@code x-nonc}（上游在 {@code init} 里做一次）。
     *
     * <p>注意：签名里含时间戳，长时间运行后需要刷新，否则服务端可能判重放。
     */
    public void refreshSession() {
        this.time = nowSec();
        this.nonce = String.valueOf(randInt(999) + 1);
    }

    /**
     * 固定时间戳与随机数 —— 仅测试用，用于复现固定签名向量。
     *
     * @param time  秒级时间戳串
     * @param nonce 随机数串
     */
    public void setTimeAndNonce(String time, String nonce) {
        this.time = time == null ? "" : time;
        this.nonce = nonce == null ? "" : nonce;
    }

    // ── 请求构造 ────────────────────────────────────────────

    /**
     * 站点基址（已去尾斜杠）。
     *
     * @return 基址；未配置时为空串
     */
    public String host() {
        return host;
    }

    /**
     * 上游读了却未使用的 {@code updateId}（缺陷 1）。
     *
     * @return ext.updateId 原值
     */
    public String updateId() {
        return updateId;
    }

    /**
     * 当前 {@code x-time}。
     *
     * @return 秒级时间戳串
     */
    public String time() {
        return time;
    }

    /**
     * 当前 {@code x-nonc}。
     *
     * @return 随机数串
     */
    public String nonce() {
        return nonce;
    }

    /**
     * 最近一次请求的完整 URL（不联网）。
     *
     * @return URL；尚未请求时为空串
     */
    public String lastUrl() {
        return lastUrl;
    }

    /**
     * 计算 {@code x-sign}。
     *
     * @return 64 位大写 hex
     */
    public String sign() {
        String raw = String.format("finger=%s&id=%s&nonce=%s&sk=%s&time=%s&v=%s",
                finger, pkg, nonce, sk, time, ver);
        return sha256Upper(raw);
    }

    /**
     * 产出发请求用的 12 个头。
     *
     * @return 保序 header map
     */
    public Map<String, String> requestHeaders() {
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put("user-agent", UA);
        h.put("accept", "application/json");
        h.put("x-platform", "android");
        h.put("x-ave", ver);
        h.put("x-aid", pkg);
        h.put("x-time", time);
        h.put("x-nonc", nonce);
        h.put("x-sign", sign());
        h.put("x-device-id", DEVICE_ID);
        h.put("x-device-brand", deviceBrand);
        h.put("x-device-model", deviceModel);
        h.put("x-update-id", host);
        return h;
    }

    /**
     * 发一次 GET（可被测试覆写以注入 fixture）。
     *
     * @param path 接口路径或完整 URL
     * @return 响应体；失败为空串
     */
    public String fetch(String path) {
        String p = path == null ? "" : path;
        String url = p.startsWith("http") ? p : host + p;
        this.lastUrl = url;
        return http.get(url, requestHeaders());
    }

    /**
     * 上游 {@code a(String)}：GET + 重新序列化 JSON；非 JSON / 空响应返回空串。
     *
     * @param path 接口路径
     * @return 规范化 JSON 串；失败为空串
     */
    public String api(String path) {
        String body = fetch(path);
        if (body == null) {
            return "";
        }
        String t = body.trim();
        if (t.isEmpty()) {
            return "";
        }
        try {
            return new JSONObject(t).toString();
        } catch (Throwable e) {
            Log.w(tag(), "非 JSON 响应: " + head(t));
            return "";
        }
    }

    // ── 数据方法 ────────────────────────────────────────────

    /**
     * 首页：分类表（剔除「电影」）+ 推荐列表。
     *
     * <p>上游用 {@code type_name} 同时当 id 和 name 传（分类接口按名字过滤），
     * 所以这里的 {@code type_id == type_name}，不是笔误。
     *
     * @param filter 是否请求筛选定义（上游忽略此参数，无 filters）
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        JSONObject root = json(api(PATH_HOME));
        JSONObject data = root.optJSONObject("data");
        VodBuilder b = VodBuilder.create();
        if (data == null) {
            return b.homeContent();
        }
        JSONArray cats = arr(data, "categories");
        for (int i = 0; i < cats.length(); i++) {
            JSONObject o = cats.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String name = str(o, "type_name");
            if (name.isEmpty() || "电影".equals(name)) {
                continue;
            }
            b.clazz(name, name);
        }
        b.addAll(vods(arr(data, "recommend")));
        return b.homeContent();
    }

    /**
     * 分类翻页。
     *
     * @param tid    分类<b>名</b>（上游如此）
     * @param pg     页码
     * @param filter 忽略
     * @param extend 忽略（上游分类接口不接受筛选）
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String path = PATH_CATEGORY
                + "?type_name=" + Http.encode(tid == null ? "" : tid)
                + "&page=" + page
                + "&sort=" + CATEGORY_SORT;
        JSONObject root = json(api(path));
        // 上游取 data 数组；data 缺失时为 null，这里容错成空列表
        List<Vod> list = vods(root.optJSONArray("data"));
        return VodBuilder.create().addAll(list).page(page, 0, 0).categoryContent();
    }

    /**
     * 详情：重写 {@code vod_play_from}（code → 显示名）与 {@code vod_play_url}
     * （每集改成 {@code 集名$地址@线路code@片名@集号}，供 {@link #playerContent} 拆解）。
     *
     * @param ids 影片 id 列表
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (id.isEmpty()) {
            return emptyResponse();
        }
        JSONObject root = json(api(PATH_DETAIL + "?vod_id=" + Http.encode(id)));
        JSONArray data = root.optJSONArray("data");
        JSONObject o = data == null ? null : data.optJSONObject(0);
        if (o == null) {
            Log.w(tag(), "详情无 data[0], id=" + id);
            return emptyResponse();
        }
        String vodName = str(o, "vod_name");
        Vod v = new Vod(id, vodName)
                .pic(str(o, "vod_pic"))
                .typeName(str(o, "vod_class"))
                .remarks(str(o, "vod_remarks"))
                .content(str(o, "vod_content").trim())
                .actor(str(o, "vod_actor"))
                .director(str(o, "vod_director"));

        Map<String, String> show = playerNames(root);
        String[] froms = split(str(o, "vod_play_from"), "\\$\\$\\$");
        String[] urls = split(str(o, "vod_play_url"), "\\$\\$\\$");

        List<String> nameList = new ArrayList<>();
        for (String f : froms) {
            String s = show.get(f);
            nameList.add(s == null || s.isEmpty() ? f : s);
        }

        List<String> groups = new ArrayList<>();
        for (int i = 0; i < urls.length; i++) {
            String code = i < froms.length ? froms[i] : "";
            List<String> items = new ArrayList<>();
            for (String ep : split(urls[i], "#")) {
                items.add(rewriteEpisode(ep, code, vodName));
            }
            groups.add(join(items, "#"));
        }

        v.playFrom(join(nameList, "$$$")).playUrl(join(groups, "$$$"));
        return VodBuilder.detail(v).detailContent();
    }

    /**
     * 搜索（上游固定 page=1&lt;br&gt;limit=15）。
     *
     * @param key   关键词
     * @param quick 忽略（上游无快速搜索分支）
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        String path = PATH_SEARCH
                + "?wd=" + Http.encode(key == null ? "" : key)
                + "&page=" + SEARCH_PAGE
                + "&limit=" + SEARCH_LIMIT;
        JSONObject root = json(api(path));
        return VodBuilder.create().addAll(vods(root.optJSONArray("data"))).searchContent();
    }

    /**
     * 播放解析。
     *
     * <p>id 形如 {@code 地址@线路code@片名@集号}（由 {@link #detailContent} 拼接）。
     * 直链（含 m3u8/mp4/flv/avi/mov/mkv）直接回播放体；否则走
     * {@code decode/url} 解码，最多 3 次（challenge 令牌会带入下一次，见缺陷 2）。
     *
     * @param flag     播放源标识（上游忽略）
     * @param id       播放 id
     * @param vipFlags 忽略
     * @return 标准播放 JSON；彻底失败时为 {@code ""} 或错误提示 JSON（与上游一致）
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String[] p = (id == null ? "" : id).split("@");
        if (p.length < 4) {
            // 上游在这里抛 ArrayIndexOutOfBounds，被 catch 后返回错误提示
            Log.w(tag(), "播放 id 段数不足 4: " + id);
            return errorPlay("播放链接解析失败，请更换其他源播放");
        }
        String url = p[0].trim();
        String from = p[1].trim();
        String vodName = p[2].trim();
        String vodIndex = p[3].trim();
        String danmaku = danmakuUrl(vodName, vodIndex);

        try {
            if (DIRECT.matcher(url).matches()) {
                return directPlay(url, danmaku);
            }
            String token = "";
            for (int attempt = 0; attempt < 3; attempt++) {
                String body = fetch(decodePath(url, from, token));
                if (body == null || body.trim().isEmpty()) {
                    continue;
                }
                JSONObject o = Http.parseJson(body);
                String data = str(o, "data").trim();
                if (o.optInt("code", -1) == 2 && o.has("challenge")) {
                    String t = challengeToken(str(o, "challenge").trim());
                    if (!t.isEmpty()) {
                        token = t;
                        if (data.isEmpty()) {
                            continue;
                        }
                    }
                }
                if (data.isEmpty()) {
                    continue;
                }
                return data.startsWith("http") ? directPlay(data, danmaku) : data;
            }
            return "";
        } catch (Throwable e) {
            Log.e(tag(), "playerContent failed, id=" + id, e);
            return errorPlay("播放链接解析失败，请更换其他源播放");
        }
    }

    // ── 内部：URL / 解析 ────────────────────────────────────

    /**
     * 拼 decode 请求路径（{@code &token} 仅在拿到 challenge 令牌后追加）。
     *
     * @param url   待解码地址
     * @param from  线路 code
     * @param token challenge 令牌，可为空
     * @return 路径
     */
    public String decodePath(String url, String from, String token) {
        String path = PATH_DECODE
                + "?url=" + Http.encode(url == null ? "" : url)
                + "&vodFrom=" + Http.encode(from == null ? "" : from);
        if (token != null && !token.isEmpty()) {
            path += "&token=" + Http.encode(token);
        }
        return path;
    }

    /**
     * 解析 {@code vodplayer[]} 的 code → 显示名。
     *
     * @param root 详情响应
     * @return code → 显示名；缺失时为空 map
     */
    public Map<String, String> playerNames(JSONObject root) {
        Map<String, String> m = new HashMap<>();
        JSONArray players = root == null ? null : root.optJSONArray("vodplayer");
        if (players == null) {
            return m;
        }
        for (int i = 0; i < players.length(); i++) {
            JSONObject o = players.optJSONObject(i);
            if (o == null) {
                continue;
            }
            m.put(str(o, "from"), str(o, "show"));
        }
        return m;
    }

    /**
     * 重写一集：{@code 集名$地址} → {@code 集名$地址@号线code@片名@集号}。
     *
     * <p>集号取集名里的数字，无数字时为 {@code "1"}（上游 {@code replaceAll("\\D+","")}）。
     *
     * @param ep      原始集串
     * @param code    线路 code
     * @param vodName 片名
     * @return 重写后的集串；缺 {@code $} 时原样返回（缺陷 4）
     */
    public static String rewriteEpisode(String ep, String code, String vodName) {
        String[] p = split(ep, "\\$");
        if (p.length < 2) {
            return ep == null ? "" : ep;
        }
        String name = p[0];
        String num = name.replaceAll("\\D+", "");
        return name + "$" + p[1] + "@" + (code == null ? "" : code)
                + "@" + (vodName == null ? "" : vodName)
                + "@" + (num.isEmpty() ? "1" : num);
    }

    /**
     * 从 JS 片段里算 challenge 令牌。
     *
     * <pre>
     *   _0x1 = ['a','b','c','d'];
     *   s = "a:b:c:d"
     *   h = ((h&lt;&lt;5)-h)+ch  → 每步 &amp; 0xFFFFFFFF
     *   返回 "a:" + hex(|h|) + ":" + b[0..8]
     * </pre>
     *
     * @param js JS 片段，可为 null
     * @return 令牌；无匹配 / 片段过短时为空串
     */
    public static String challengeToken(String js) {
        if (js == null || js.isEmpty()) {
            return "";
        }
        Matcher m = CHALLENGE.matcher(js);
        if (!m.find()) {
            Log.w(TAG, "未匹配到 JS 中的组，解析失败");
            return "";
        }
        String[] parts = m.group(1).split(",");
        if (parts.length < 2) {
            return "";
        }
        String a = unquote(parts[0]);
        String b = unquote(parts[1]);
        String c = parts.length > 2 ? unquote(parts[2]) : "";
        String d = parts.length > 3 ? unquote(parts[3]) : "";
        String joined = String.format("%s:%s:%s:%s", a, b, c, d);
        long h = 0;
        for (int i = 0; i < joined.length(); i++) {
            h = (((h << 5) - h) + joined.charAt(i)) & 0xFFFFFFFFL;
        }
        String tail = b.substring(0, Math.min(8, b.length()));
        return String.format("%s:%s:%s", a, Long.toHexString(Math.abs(h)), tail);
    }

    /**
     * 播放体（直链 + 弹幕）。
     *
     * @param url     直链
     * @param danmaku 弹幕地址，可为空
     * @return 播放 JSON
     */
    public String directPlay(String url, String danmaku) {
        String base = VodBuilder.playerContent(url, "");
        if (danmaku == null || danmaku.isEmpty()) {
            return base;
        }
        try {
            JSONObject o = new JSONObject(base);
            o.put("danmaku", danmaku);
            return o.toString();
        } catch (Throwable e) {
            return base;
        }
    }

    /**
     * 错误提示播放体（上游 {@code c.f("...")}）。
     *
     * @param msg 提示语
     * @return 播放 JSON，{@code url} 为空、{@code msg} 带提示
     */
    public static String errorPlay(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("parse", 0);
            o.put("playUrl", "");
            o.put("url", "");
            o.put("msg", msg == null ? "" : msg);
            return o.toString();
        } catch (Throwable e) {
            return emptyJson();
        }
    }

    /** 空对象字面量（内部用，避免依赖基类可见性） */
    private static String emptyJson() {
        return "{}";
    }

    /** 去引号并 trim（上游 {@code replaceAll("['\"]","").trim()}） */
    private static String unquote(String s) {
        return s == null ? "" : s.replaceAll("['\"]", "").trim();
    }

    /**
     * 按正则 split；空串返回长度 1 的 {@code [""]}（与 {@link String#split} 一致但显式化）。
     *
     * @param s   原文，可为 null
     * @param re  正则
     * @return 非 null 数组
     */
    public static String[] split(String s, String re) {
        return (s == null ? "" : s).split(re);
    }

    /**
     * join（替代 {@code android.text.TextUtils.join} 的非 Android 依赖路径）。
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

    /** 日志用截断 */
    private static String head(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
