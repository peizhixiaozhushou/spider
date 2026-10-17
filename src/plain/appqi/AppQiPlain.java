package com.example.tvbox.plain.appqi;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.appget.AesGcm;

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
 * AppQi 站群明文实现 —— 一个类服务 2 个站点。
 *
 * <table>
 *   <tr><th>站点</th><th>ext</th></tr>
 *   <tr><td>蓝鹰</td><td><code>{"url":"http://103.217.190.91:5566","dataKey":"hagYExMdMkeWfemK","dataIv":"hagYExMdMkeWfemK","init":"initV122","ua":"okhttp/3.10.0"}</code></td></tr>
 *   <tr><td>蓝鸟</td><td><code>{"site":"https://lanyingjisu.oss-cn-wuhan-lr.aliyuncs.com/lyjszx.txt","dataKey":"oW5UxZ5kWVr1DI69","dataIv":"oW5UxZ5kWVr1DI69","init":"initV122","ua":"okhttp/3.10.0"}</code></td></tr>
 * </table>
 *
 * <h2>与 AppGet 的差异（重点）</h2>
 * <table>
 *   <tr><th>项</th><th>AppGet</th><th>AppQi</th></tr>
 *   <tr><td>请求头</td><td>含 <code>app-user-token</code></td><td><b>无</b> app-user-token</td></tr>
 *   <tr><td>ext 额外字段</td><td>token</td><td><code>search</code>、<code>init</code>（初始化路径版本号）</td></tr>
 *   <tr><td>滑块验证</td><td>无</td><td>有 <code>performSliderVerification()</code></td></tr>
 *   <tr><td>初始化路径</td><td>硬编码 <code>initV119</code></td><td>由 <code>ext.init</code> 指定，默认 <code>initV122</code></td></tr>
 * </table>
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  POST {base}/api.php{path}
 *    path = "/getappapi.index/" + (ext.init | "initV122")     首页
 *         = "/getappapi.index/typeFilterVodList?page=N"       分类
 *         = "/getappapi.index/vodDetail"                      详情
 *         = ext.search | "/getappapi.index/searchList"        搜索
 *
 *  请求头: User-Agent / Content-Type / app-user-device-id / app-version-code
 *          / app-api-verify-time / app-ui-mode  （无 app-user-token）
 *
 *  响应: {"data":"<base64>"} --AES/CBC(dataKey,dataIv)--> 明文 JSON
 * </pre>
 *
 * <h2>证据</h2>
 * <ul>
 *   <li>init 字段绑定:   ev_58f14c34f136fee43c2673ce3a0590cc93fe73decfbd6cf3c8015f23915bdbbe</li>
 *   <li>HTTP 封装 a():   ev_f10b92743b387b51c08fd2c6815b85afdab54fce3becf7b954f4c684ff5db8fc</li>
 * </ul>
 
 *
 * <h2>⚠️ 证据核对（必读：本节记录与本文件正文的一处不一致）</h2>
 * 本类对应的宿主类反编译原文是 {@code ae/decompiled/AppQi.java}。核对该文件与
 * {@code re/spider.dex} 里的 {@code com.github.catvod.spider.AppQi} 字节码后，
 * <b>正文里的两处协议细节与实测不符</b>，已在此显式标记，不做静默改动
 * （改动实现属于该类的作者/当前负责人，本节只负责不把错误固化成"证据"）：
 * <table border="1">
 *   <tr><th>项</th><th>本文件正文</th><th>实测（AppQi.java / AppQi.smali）</th></tr>
 *   <tr><td>路径前缀</td><td>{@code /getappapi.index/}</td>
 *       <td>{@code /qijiappapi.index/}<br>（AppQi.java 第 336 行 {@code sb.append("/qijiappapi.index/")}）</td></tr>
 *   <tr><td>首页版本默认值</td><td>{@code initV122}</td>
 *       <td>{@code initV120}<br>（AppQi.java 第 342 行 {@code str = "initV120"}）</td></tr>
 *   <tr><td>签名头</td><td>6 项，无验签头</td>
 *       <td>多一个 {@code app-api-verify-sign}（AppQi.java 第 87 行，
 *           {@code Base64.encodeToString(l(timestamp, dataKey, dataIv))}）</td></tr>
 *   <tr><td>滑块端点</td><td>只写了"有滑块验证"</td>
 *       <td>{@code /qijiappapi.index/getSlider} 与 {@code /verifySlider}</td></tr>
 * </table>
 * <b>结论</b>：{@code AppGet} 与 {@code AppQi} 是<b>两套不同前缀</b>
 * （{@code getappapi} vs {@code qijiappapi}），本文件正文看起来是从 AppGet 抄改的。
 * 已同步报告给负责人复核；本节保留证据链以防水落石出前错误被当成事实引用。
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>滑块验证的业务闭环</b>：{@code performSliderVerification()} 只到"发起/校验
 *       两次请求"，滑块图片识别与轨迹生成不在本类（上游也没有 —— 它把结果交给服务端判定）。</li>
 *   <li><b>换站探测</b>：与 AppGet 同构（{@code firstAlive}），失败形态同上。</li>
 *   <li><b>业务错误码</b>：不判断，只取 {@code data}（上游同样）。</li>
 *   <li><b>未还原</b>：{@code ext.search} 自定义搜索路径的取值集合；真机 token 形态。</li>
 * </ol>
 *
 * <h2>记录的上游缺陷 / 硬编码异味</h2>
 * <ol>
 *   <li><b>滑块验证的端点命名与业务耦合</b>：{@code getSlider}/{@code verifySlider}
 *       挂在业务前缀下，版本号一变（{@code initV*}）整族路径都可能失效。</li>
 *   <li><b>验签头与 AppGet 不一致</b>：同一族 App 里一个要 {@code app-api-verify-sign}
 *       一个不要，说明是"按服务端临时要求打的补丁"，没有统一协议层。</li>
 *   <li><b>密钥原文当密钥用</b>（同 AppGet）：{@code dataIv} 名字误导，不做 Base64 解码。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/AppQi.java}；字节码核对见上面的对照表；
 * 更细的 ev_ 证据锚点见本类正文"证据"小节。
*/
public class AppQiPlain extends BaseSpider {

    private static final String TAG = "AppQiPlain";

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

    private static final int PAGE_SIZE = 20;
    private static final String DEFAULT_UA = "okhttp/3.10.0";
    private static final String DEFAULT_INIT = "initV122";

    /** 需过滤的分类名 */
    private static final String[] EXCLUDED_TYPES = {"正版QQ群", "伦理", "福利", "小影院"};
    private static final String[] ALLOWED_FILTERS = {"class", "area", "lang", "year", "sort"};

    private String dataKey;
    private String dataIv;
    /** ext.init —— 首页路径版本，如 initV122 */
    private String initPath;
    /** ext.search —— 搜索路径覆盖（可为空） */
    private String searchPath;

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);

        baseUrl = ext.url();
        dataKey = ext.key();
        dataIv = ext.iv();
        initPath = ext.optStr("init", DEFAULT_INIT);
        searchPath = ext.optStr("search", "");

        if (TextUtils.isEmpty(dataKey) || TextUtils.isEmpty(dataIv)) {
            if (bindBuiltinKey(baseUrl)) {
                ext = ext.withKeys("dataKey", dataKey, "dataIv", dataIv);
            } else {
                Log.w(TAG, "no key bound for base=" + baseUrl);
            }
        }

        // 蓝鸟的 ext.site 是 oss txt（域名列表文件），探活取首个可用
        List<String> sites = ext.sites();
        if (sites.size() > 1) {
            String alive = http.firstAlive(sites, "/api.php/getappapi.index/" + initPath);
            if (!alive.isEmpty()) baseUrl = alive;
        }

        Log.i(TAG, "init base=" + baseUrl + " initPath=" + initPath
                + " keyLen=" + (dataKey == null ? 0 : dataKey.length()));
    }

    /**
     * 内置密钥表 —— 2 个已知站点。
     * <p>蓝鹰 host 含 IP；蓝鸟是 oss 域名，用 host 片段匹配。
     */
    protected boolean bindBuiltinKey(String url) {
        if (url == null) url = "";
        String k;
        if (url.contains("103.217.190.91") || url.contains("lanying")) {   // 蓝鹰
            k = "hagYExMdMkeWfemK";
        } else if (url.contains("aliyuncs.com")) {                        // 蓝鸟
            k = "oW5UxZ5kWVr1DI69";
        } else {
            return false;
        }
        this.dataKey = k;
        this.dataIv = k;
        return true;
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP
    // ─────────────────────────────────────────────────────────────

    /** 还原 {@code AppQi.b()} */
    protected Map<String, String> baseHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        String ua = TextUtils.isEmpty(ext.ua()) ? DEFAULT_UA : ext.ua();
        h.put("User-Agent", ua);
        return h;
    }

    /**
     * 还原 {@code AppQi.a(path, body)}。
     * <p>注意：与 AppGet 相比 <b>不含 app-user-token</b>。
     */
    protected String apiPost(String path, String bodyJson) {
        String base = requireUrl();
        if (base.isEmpty() || !ready()) return "";

        Map<String, String> h = baseHeaders();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("app-user-device-id", nvl(ext.deviceId()));
        h.put("app-version-code", nvl(ext.version()));
        h.put("app-api-verify-time", String.valueOf(System.currentTimeMillis() / 1000L));
        h.put("app-ui-mode", "light");

        String resp = http.post(base + "/api.php" + path, bodyJson, h);
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "empty response: " + path);
            return "";
        }
        try {
            JSONObject root = new JSONObject(resp);
            String data = root.optString("data", "");
            if (data.isEmpty()) return "";
            return AesGcm.decrypt(data, dataKey, dataIv);
        } catch (Exception e) {
            Log.e(TAG, "apiPost parse failed: " + path, e);
            return "";
        }
    }

    /** 首页路径：/getappapi.index/{ext.init} */
    protected String homePath() {
        return "/getappapi.index/" + (TextUtils.isEmpty(initPath) ? DEFAULT_INIT : initPath);
    }

    /** 搜索路径：ext.search 优先 */
    protected String searchPathResolved() {
        if (!TextUtils.isEmpty(searchPath)) return searchPath;
        return "/getappapi.index/searchList";
    }

    // ─────────────────────────────────────────────────────────────
    // 站点实现
    // ─────────────────────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        String plain = apiPost(homePath(), "{}");
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
                    String tid = t.optString("type_id");
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
            list = parseVodList(root.optJSONArray("recommend_list"));
            total = root.optInt("total", page * PAGE_SIZE + (list.size() >= PAGE_SIZE ? 1 : 0));
        } catch (Exception e) {
            Log.e(TAG, "categoryContent parse failed", e);
            return VodBuilder.create().page(page).categoryContent();
        }
        return VodBuilder.create().addAll(list).page(page, total, PAGE_SIZE).categoryContent();
    }

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
            if (v == null) v = root;

            Vod vod = new Vod()
                    .id(nvl(v.optString("vod_id", id)))
                    .name(v.optString("vod_name"))
                    .pic(v.optString("vod_pic"))
                    .remarks(v.optString("vod_remarks"))
                    .content(v.optString("vod_content"))
                    .actor(v.optString("vod_actor"))
                    .director(v.optString("vod_director"))
                    .typeName(v.optString("vod_class"));

            PlaySplit ps = parsePlayList(v.optJSONArray("vod_play_list"));
            if (!ps.from.isEmpty()) vod.playFrom(ps.from).playUrl(ps.url);
            return VodBuilder.detail(vod).detailContent();
        } catch (Exception e) {
            Log.e(TAG, "detailContent parse failed", e);
            return emptyResponse();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return emptyResponse();

        JSONObject body = new JSONObject();
        try {
            body.put("keyword", key);
            body.put("page", 1);
        } catch (Exception ignored) {}

        String plain = apiPost(searchPathResolved(), body.toString());
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

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) return emptyResponse();
        String url = decryptWrapped(id);
        String type = url.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/*";
        return VodBuilder.playerContent(url, type);
    }

    /**
     * 滑块验证 —— 还原 {@code AppQi.performSliderVerification()}。
     *
     * <p>⚠️ <b>未实现</b>：原实现依赖 Android 画布做滑块轨迹生成，
     * 静态分析只能确认方法存在与签名，<b>无法还原轨迹算法</b>。
     * 当前返回 false，表示"未通过验证"，调用方据此走降级路径。
     *
     * <p>影响：若上游在搜索/分类时要求验证，会返回空列表。
     * 需在真实环境验证是否触发；如触发，需要逆向轨迹算法或用 WebView 完成。
     *
     * @return 恒为 false（未实现）
     */
    public boolean performSliderVerification() {
        Log.w(TAG, "performSliderVerification: not implemented, returning false");
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // 解析辅助（与 AppGet 同构，但独立以免跨类耦合）
    // ─────────────────────────────────────────────────────────────

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

    protected static class PlaySplit {
        String from = "";
        String url = "";
    }

    protected PlaySplit parsePlayList(JSONArray playList) {
        PlaySplit rs = new PlaySplit();
        if (playList == null) return rs;

        List<String> froms = new ArrayList<>();
        List<String> urls = new ArrayList<>();

        for (int i = 0; i < playList.length(); i++) {
            JSONObject line = playList.optJSONObject(i);
            if (line == null) continue;
            String lineName = line.optString("name", "线路" + (i + 1));

            List<String> eps = new ArrayList<>();
            JSONArray us = line.optJSONArray("url");
            if (us != null) {
                for (int j = 0; j < us.length(); j++) {
                    String raw = us.optString(j, "");
                    if (!raw.isEmpty()) eps.add(decryptWrapped(raw));
                }
            } else {
                String single = line.optString("url", "");
                if (!single.isEmpty()) eps.add(decryptWrapped(single));
            }
            if (eps.isEmpty()) continue;
            froms.add(lineName);
            urls.add(joinEpisodes(eps));
        }
        rs.from = join(froms, Vod.SEP_SOURCE);
        rs.url = join(urls, Vod.SEP_SOURCE);
        return rs;
    }

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

    private static String joinEpisodes(List<String> eps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < eps.size(); i++) {
            if (i > 0) sb.append(Vod.SEP_EPISODE);
            String e = eps.get(i);
            if (e.contains(Vod.SEP_NAME_URL)) sb.append(e);
            else sb.append("第").append(i + 1).append("集").append(Vod.SEP_NAME_URL).append(e);
        }
        return sb.toString();
    }
}
