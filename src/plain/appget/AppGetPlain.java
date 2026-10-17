package com.example.tvbox.plain.appget;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Ext;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

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
 * AppGet 站群明文实现 —— 一个类服务 4 个站点。
 *
 * <table>
 *   <tr><th>站点</th><th>dataKey / dataIv</th></tr>
 *   <tr><td>魔方</td><td>1234567887654321</td></tr>
 *   <tr><td>王子</td><td>5a9w6x58dsq6z3a6</td></tr>
 *   <tr><td>茉莉</td><td>88689667dce61725</td></tr>
 *   <tr><td>咕咕动漫</td><td>nKfZ8KX6JTNWRzTD</td></tr>
 * </table>
 *
 * <h2>协议还原（全部来自静态反编译）</h2>
 *
 * <h3>端点</h3>
 * <pre>
 *  POST {base}/api.php{path}
 *    path 取值:
 *      首页    /getappapi.index/initV119      body: {}
 *      分类    /getappapi.index/typeFilterVodList?page=N
 *              body: {"type_id","page","class"?,"area"?,"lang"?,"year"?,"sort"?}
 *      详情    /getappapi.index/vodDetail      body: {"vod_id":...}
 *      搜索    /getappapi.index/searchList     body: {"keyword":..., "page":...}
 * </pre>
 *
 * <h3>请求头（固定 6 项）</h3>
 * <pre>
 *  User-Agent              : ext.ua 或默认 "okhttp/3.14.9"
 *  Content-Type            : application/x-www-form-urlencoded
 *  app-user-device-id      : ext.deviceId
 *  app-version-code        : ext.version
 *  app-user-token          : ext.token   （非空才带）
 *  app-api-verify-time     : 当前秒级时间戳
 *  app-ui-mode             : "light"
 * </pre>
 *
 * <h3>响应解密（关键）</h3>
 * <pre>
 *  原始响应: {"data": "<base64 密文>"}
 *  解密:     AES/CBC/PKCS5 用 (dataKey, dataIv)
 *  说明:     dataKey / dataIv 是 16~32 字符原文，直接 getBytes()，
 *            绝不 Base64 解码（解码后长度非法）
 * </pre>
 *
 * <h3>密文二次包装</h3>
 * 部分字段（如播放地址）外层还包了 {@code &url=<密文>&token}，
 * 需二次 AES 解密，正则 {@code (&url=)(.*?)(?=&token)(&token)}。
 *
 * <h2>证据</h2>
 * <ul>
 *   <li>init 字段绑定: ev_d9bbab49dd2900e48b51527c78f3388f80845315e450d5e3fd0d015888e6140e</li>
 *   <li>HTTP 封装 a(): ev_2b828d218db28f21612ecdfcba5e4d649c52964b2405d5d9cf4bdc5545f56ac7</li>
 *   <li>eduAesDecode:  ev_7da4ed034d201cc03ebe678597d0a7d284cd7c538b2eb1f650df6505ff229612</li>
 *   <li>AES 算法:      ev_51e2af1dbeff82d569c9bc2fb156e6ea388f750b251d9ea6ae5ed074412ed862</li>
 *   <li>homeContent:   ev_775d9f7511753e9740844a66565d4d8b6e224019b7bd5643aa8bb7fdb857f0c6</li>
 * </ul>
 
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>换站探测的失败形态</b>：{@code firstAlive(sites, path)} 只认"能解出 JSON"；
 *       多站全挂时返回第一个站，不抛异常（上游同样不区分"域名挂了"和"密钥错了"）。</li>
 *   <li><b>业务错误码</b>：上游只取 {@code data} 字段，不判断业务 code；
 *       本类保持同一行为，因此"code 非 200 但 data 有值"会被当成功。</li>
 *   <li><b>二次包装的正则边界</b>：播放地址的 {@code &url=<密文>&token} 解包用固定正则；
 *       服务端少一个 {@code &token} 就匹配不上（上游同样），本类不猜补。</li>
 *   <li><b>未还原</b>：{@code ext} 里给的三站 dataKey/dataIv 是站点常量表原文；
 *       真机账号/token 形态未知。</li>
 * </ol>
 *
 * <h2>记录的上游缺陷 / 硬编码异味</h2>
 * <ol>
 *   <li><b>dataKey/dataIv 名义是密钥原文</b>：绝不做 Base64 解码（解码后长度不是
 *       16/24/32，JCE 必抛）—— 上游注释里"dataIv"这个名字会被误读成 IV。</li>
 *   <li><b>{@code initV119} 硬编码在路径里</b>：升级服务端版本要改代码重新打包，
 *       没有任何协商机制（对比 AppQi 把版本号放进 ext.init）。</li>
 *   <li><b>请求头固定 6 项</b>：{@code app-user-token} 只在非空时才带 ——
 *       服务端无法区分"未登录"与"token 为空串"。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/AppGet.java}（逐行核对：
 * {@code /api.php/getappapi.index/initV119}、{@code typeFilterVodList?page=}、
 * {@code vodDetail}、{@code searchList}、{@code vodParse}，
 * 以及 {@code a()} 的 6 个请求头与 AES/CBC 响应解密）。
*/
public class AppGetPlain extends BaseSpider {

    private static final String TAG = "AppGetPlain";

    /**
     * 二次密文定位。
     *
     * <p>原实现正则严格为 {@code (&url=)(.*?)(?=&token)(&token)} ——
     * 要求密文出现在 {@code &url=} 位置（前面有其它 query 参数）。
     * 本重写放宽为同时接受 {@code ?url=}（密文是首个参数）与 {@code &url=}，
     * 比原实现更宽容，避免上游参数顺序变化时失效。
     */
    private static final Pattern URL_TOKEN =
            Pattern.compile("([?&]url=)(.*?)(?=&token)(&token)");

    /** 分页大小（上游未在响应中给出 limit，按 20 估算） */
    private static final int PAGE_SIZE = 20;

    /** 默认 UA，来自 AppGet.b() 的兜底值 */
    private static final String DEFAULT_UA = "okhttp/3.14.9";

    /** 需过滤的分类名（原实现硬编码的 4 个排除项） */
    private static final String[] EXCLUDED_TYPES = {"正版QQ群", "伦理", "福利", "小影院"};

    /** 允许的筛选维度（原实现只接受这 5 个） */
    private static final String[] ALLOWED_FILTERS = {"class", "area", "lang", "year", "sort"};

    private String dataKey;
    private String dataIv;
    /** detailContent 解析出的剧名，供播放串拼接使用 */
    private String currentVodName;

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);

        // ── 站点地址：ext.url，或在 ext.site 的域名列表中探活 ──
        baseUrl = ext.url();

        // ── 密钥：优先 ext，其次内置表（站群新增站点无需改代码） ──
        dataKey = ext.key();
        dataIv = ext.iv();

        if (TextUtils.isEmpty(dataKey) || TextUtils.isEmpty(dataIv)) {
            if (bindBuiltinKey(baseUrl)) {
                ext = ext.withKeys("dataKey", dataKey, "dataIv", dataIv);
            } else {
                Log.w(TAG, "no key bound for base=" + baseUrl);
            }
        }

        // 若 ext 提供多域名，探活取首个可用
        List<String> sites = ext.sites();
        if (sites.size() > 1 && baseUrl != null && (baseUrl.isEmpty() || sites.contains(baseUrl))) {
            String alive = http.firstAlive(sites, "/api.php/getappapi.index/initV119");
            if (!alive.isEmpty()) baseUrl = alive;
        }

        Log.i(TAG, "init base=" + baseUrl + " keyLen=" + (dataKey == null ? 0 : dataKey.length()));
    }

    /**
     * 内置密钥表 —— 4 个已知站点的 dataKey/dataIv。
     * 这些值原本在 api.json 的 ext 里明文下发，现固化进源码。
     * <p>新增站点：在此加一行即可，无需改动其它代码。
     *
     * @return true 表示已绑定
     */
    protected boolean bindBuiltinKey(String url) {
        if (url == null) url = "";
        String k, v;
        if (url.contains("douy32mf.top")) {              // 魔方
            k = "1234567887654321"; v = k;
        } else if (url.contains("95112475.xyz")) {       // 王子
            k = "5a9w6x58dsq6z3a6"; v = k;
        } else if (url.contains("aliyuncs.com")) {       // 茉莉（site 为 oss txt）
            k = "88689667dce61725"; v = k;
        } else if (url.contains("gugu3.com")) {          // 咕咕动漫
            k = "nKfZ8KX6JTNWRzTD"; v = k;
        } else {
            return false;
        }
        this.dataKey = k;
        this.dataIv = v;
        return true;
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP 层：还原 AppGet.a() / AppGet.b()
    // ─────────────────────────────────────────────────────────────

    /** 还原 {@code AppGet.b()} —— 只返回 UA */
    protected Map<String, String> baseHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        String ua = TextUtils.isEmpty(ext.ua()) ? DEFAULT_UA : ext.ua();
        h.put("User-Agent", ua);
        return h;
    }

    /**
     * 还原 {@code AppGet.a(path, body)}：
     * 发 POST，取 {@code data} 字段，AES 解密后返回明文 JSON。
     */
    protected String apiPost(String path, String bodyJson) {
        String base = requireUrl();
        if (base.isEmpty() || !ready()) return "";

        Map<String, String> h = baseHeaders();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("app-user-device-id", nvl(ext.deviceId()));
        h.put("app-version-code", nvl(ext.version()));
        if (!TextUtils.isEmpty(ext.token())) {
            h.put("app-user-token", ext.token());
        }
        h.put("app-api-verify-time", String.valueOf(System.currentTimeMillis() / 1000L));
        h.put("app-ui-mode", "light");

        String url = base + "/api.php" + path;
        String resp = http.post(url, bodyJson, h);
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "empty response: " + path);
            return "";
        }
        try {
            JSONObject root = new JSONObject(resp);
            String data = root.optString("data", "");
            if (data.isEmpty()) {
                Log.w(TAG, "no data field: " + truncate(resp));
                return "";
            }
            return AesGcm.decrypt(data, dataKey, dataIv);
        } catch (Exception e) {
            Log.e(TAG, "apiPost parse failed: " + path, e);
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 站点实现
    // ─────────────────────────────────────────────────────────────

    /** 首页：还原 AppGet.homeContent() */
    @Override
    public String homeContent(boolean filter) {
        String plain = apiPost("/getappapi.index/initV119", "{}");
        if (plain.isEmpty()) return emptyResponse();

        List<Vod> recommends = new ArrayList<>();
        Map<String, String> classes = new LinkedHashMap<>();
        JSONObject filters = new JSONObject();

        try {
            JSONObject root = new JSONObject(plain);
            recommends = parseVodList(root.optJSONArray("recommend_list"));

            JSONArray typeList = root.optJSONArray("type_list");
            if (typeList != null) {
                for (int i = 0; i < typeList.length(); i++) {
                    JSONObject t = typeList.optJSONObject(i);
                    if (t == null) continue;
                    // 真实响应中 type_id 是 JSON 数字（Long），optString 会转成 "0"/"1"
                    // 用 opt() 取原始值再转字符串，兼容数字与字符串两种形态
                    String tid = String.valueOf(t.opt("type_id"));
                    String tname = t.optString("type_name");
                    if (tid.isEmpty() || isExcludedType(tname)) continue;
                    classes.put(tid, tname);

                    JSONObject f = buildFilter(t.optJSONArray("filter_type_list"));
                    if (f.length() > 0) {
                        try { filters.put(tid, f); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "homeContent parse failed", e);
            return emptyResponse();
        }

        VodBuilder vb = VodBuilder.create().addAll(recommends);
        for (Map.Entry<String, String> e : classes.entrySet()) {
            vb.clazz(e.getKey(), e.getValue());
        }
        return vb.homeContent();
    }

    /** 分类：还原 AppGet.categoryContent() */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extendMap) {
        int page = pageNum(pg, 1);

        JSONObject body = new JSONObject();
        try {
            body.put("type_id", tid);
            body.put("page", page);
            if (extendMap != null) {
                for (String dim : ALLOWED_FILTERS) {
                    String v = extendMap.get(dim);
                    if (v != null && !v.isEmpty()) body.put(dim, v);
                }
            }
        } catch (Exception ignored) {}

        String plain = apiPost("/getappapi.index/typeFilterVodList?page=" + page, body.toString());
        if (plain.isEmpty()) return VodBuilder.create().page(page).categoryContent();

        List<Vod> list;
        int total;
        try {
            JSONObject root = new JSONObject(plain);
            JSONArray arr = root.optJSONArray("recommend_list");
            list = parseVodList(arr);
            total = root.optInt("total", page * PAGE_SIZE + (list.size() >= PAGE_SIZE ? 1 : 0));
        } catch (Exception e) {
            Log.e(TAG, "categoryContent parse failed", e);
            return VodBuilder.create().page(page).categoryContent();
        }
        return VodBuilder.create().addAll(list).page(page, total, PAGE_SIZE).categoryContent();
    }

    /**
     * 详情：还原 AppGet.detailContent()。
     *
     * <p><b>真实响应结构（已用真实请求验证）</b>：
     * <pre>
     *  {
     *    "vod": { "vod_name", "vod_pic", "vod_remarks", "vod_content",
     *             "vod_actor", "vod_director", "vod_class" },
     *    "vod_play_list": [                    ← 在顶层，不在 vod 内
     *      { "player_info": { "show", "parse" },
     *        "urls": [ { "name", "url", "parse_api_url", "token", "nid" } ] }
     *    ]
     *  }
     * </pre>
     *
     * <p>播放地址处理：
     * <ul>
     *   <li>{@code parse_api_url} 是 http 开头 → 直接用作播放地址</li>
     *   <li>否则 → {@code parse_api=<parse>&url=<AES加密url>&token=<token>}，
     *       其中 url 用 dataKey/dataIv 加密后 Base64</li>
     * </ul>
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (id.isEmpty()) return emptyResponse();

        JSONObject body = new JSONObject();
        try { body.put("vod_id", id); } catch (Exception ignored) {}

        String plain = apiPost("/getappapi.index/vodDetail", body.toString());
        if (plain.isEmpty()) return emptyResponse();

        try {
            JSONObject root = new JSONObject(plain);
            JSONObject v = root.optJSONObject("vod");
            if (v == null) {
                Log.w(TAG, "detail: vod is null (id=" + id + "), upstream may have retired this endpoint");
                return emptyResponse();
            }

            currentVodName = v.optString("vod_name");
            Vod vod = new Vod()
                    .id(id)
                    .name(currentVodName)
                    .pic(v.optString("vod_pic"))
                    .remarks(v.optString("vod_remarks"))
                    .content(v.optString("vod_content"))
                    .actor(v.optString("vod_actor"))
                    .director(v.optString("vod_director"))
                    .typeName(v.optString("vod_class"));

            JSONArray playList = root.optJSONArray("vod_play_list");
            PlaySplit ps = parsePlayList(playList);
            if (!ps.from.isEmpty()) vod.playFrom(ps.from).playUrl(ps.url);
            return VodBuilder.detail(vod).detailContent();
        } catch (Exception e) {
            Log.e(TAG, "detailContent parse failed", e);
            return emptyResponse();
        }
    }

    /** 搜索：还原 AppGet.searchContent() */
    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return emptyResponse();

        JSONObject body = new JSONObject();
        try {
            body.put("keyword", key);
            body.put("page", 1);
        } catch (Exception ignored) {}

        String plain = apiPost("/getappapi.index/searchList", body.toString());
        if (plain.isEmpty()) return VodBuilder.create().searchContent();

        List<Vod> list;
        try {
            JSONObject root = new JSONObject(plain);
            JSONArray arr = root.optJSONArray("search_list");
            if (arr == null) arr = root.optJSONArray("recommend_list");
            list = parseVodList(arr);
        } catch (Exception e) {
            Log.e(TAG, "searchContent parse failed", e);
            return VodBuilder.create().searchContent();
        }
        return VodBuilder.of(list).searchContent();
    }

    /** 播放：还原 AppGet.playerContent() —— 直链，交给宿主播放器 */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) return emptyResponse();
        // 播放地址在 detail 阶段已解出，此处直接返回（原实现同）
        String url = decryptWrapped(id);
        String type = url.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/*";
        return VodBuilder.playerContent(url, type);
    }

    // ─────────────────────────────────────────────────────────────
    // 解析辅助
    // ─────────────────────────────────────────────────────────────

    /** 还原 {@code AppGet.parseVodList()} —— 只取 4 个字段 */
    protected List<Vod> parseVodList(JSONArray arr) {
        List<Vod> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            out.add(new Vod()
                    .id(o.optString("vod_id"))
                    .name(o.optString("vod_name"))
                    .pic(o.optString("vod_pic"))
                    .remarks(o.optString("vod_remarks")));
        }
        return out;
    }

    /** 播放线路拆分结果 */
    protected static class PlaySplit {
        String from = "";
        String url = "";
    }

    /**
     * 解析 {@code vod_play_list}（真实结构，已验证）。
     *
     * <pre>
     * [{ "player_info": {"show":"线路名","parse":"解析标识"},
     *    "urls": [ {"name":"第1集","url":"...","parse_api_url":"","token":"","nid":""} ] }]
     * </pre>
     */
    protected PlaySplit parsePlayList(JSONArray playList) {
        PlaySplit rs = new PlaySplit();
        if (playList == null) return rs;

        List<String> froms = new ArrayList<>();
        List<String> urls = new ArrayList<>();

        for (int i = 0; i < playList.length(); i++) {
            JSONObject line = playList.optJSONObject(i);
            if (line == null) continue;

            JSONObject info = line.optJSONObject("player_info");
            String show = info == null ? "" : info.optString("show", "线路" + (i + 1));
            String parseTag = info == null ? "" : info.optString("parse", "");

            JSONArray items = line.optJSONArray("urls");
            if (items == null) continue;

            List<String> eps = new ArrayList<>();
            for (int j = 0; j < items.length(); j++) {
                JSONObject it = items.optJSONObject(j);
                if (it == null) continue;
                String name = it.optString("name", "第" + (j + 1) + "集");
                String rawUrl = it.optString("url", "");
                if (rawUrl.isEmpty()) continue;

                String apiUrl = it.optString("parse_api_url", "");
                String token = it.optString("token", "");
                String nid = it.optString("nid", "");
                String playUrl;

                if (apiUrl.matches("^https?://.*")) {
                    // 已有直连解析地址
                    playUrl = apiUrl;
                } else {
                    // 需二次加密：parse_api=<parse>&url=<cipher>&token=<token>
                    String cipher = AesGcm.cbcEncryptToBase64(rawUrl, dataKey, dataIv);
                    playUrl = "parse_api=" + parseTag + "&url=" + cipher + "&token=" + token;
                }
                eps.add(name + Vod.SEP_NAME_URL + playUrl + "|" + vodNameOf(playList) + "|" + nid);
            }
            if (eps.isEmpty()) continue;
            froms.add(show);
            urls.add(join(eps, Vod.SEP_EPISODE));
        }
        rs.from = join(froms, Vod.SEP_SOURCE);
        rs.url = join(urls, Vod.SEP_SOURCE);
        return rs;
    }

    /** 线路内携带的剧名（原实现用 vod_name 拼在播放串尾部） */
    private String vodNameOf(JSONArray playList) {
        return currentVodName == null ? "" : currentVodName;
    }

    /**
     * 还原 {@code AppGet.eduAesDecode()} —— 解密 {@code &url=<cipher>&token} 包装。
     * <p>若无包装则原样返回。
     */
    protected String decryptWrapped(String s) {
        if (s == null || s.isEmpty()) return s;
        // 兼容 ?url= 与 &url= 两种形态（前者为密文处于首参位置）
        if ((!s.contains("&url=") && !s.contains("?url=")) || dataKey == null || dataIv == null) return s;
        Matcher m = URL_TOKEN.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try {
                String plain = AesGcm.decrypt(m.group(2), dataKey, dataIv);
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + plain + m.group(3)));
            } catch (Exception e) {
                return s;
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 构造筛选 JSON，只接受 5 个维度 */
    protected JSONObject buildFilter(JSONArray filterTypeList) {
        JSONObject out = new JSONObject();
        if (filterTypeList == null) return out;
        for (int i = 0; i < filterTypeList.length(); i++) {
            JSONObject dim = filterTypeList.optJSONObject(i);
            if (dim == null) continue;
            String name = dim.optString("name");
            if (!isAllowedFilter(name)) continue;
            JSONArray opts = dim.optJSONArray("list");
            if (opts == null) continue;
            try { out.put(name, opts); } catch (Exception ignored) {}
        }
        return out;
    }

    private static boolean isAllowedFilter(String n) {
        for (String s : ALLOWED_FILTERS) if (s.equals(n)) return true;
        return false;
    }

    private static boolean isExcludedType(String name) {
        if (name == null) return true;
        for (String s : EXCLUDED_TYPES) if (name.contains(s)) return true;
        return false;
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** 剧集名$地址，多集用 # 连接 */
    private static String joinEpisodes(List<String> eps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < eps.size(); i++) {
            if (i > 0) sb.append(Vod.SEP_EPISODE);
            String e = eps.get(i);
            // 已含 $ 则原样；否则补一个集数名
            if (e.contains(Vod.SEP_NAME_URL)) sb.append(e);
            else sb.append("第").append(i + 1).append("集").append(Vod.SEP_NAME_URL).append(e);
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
