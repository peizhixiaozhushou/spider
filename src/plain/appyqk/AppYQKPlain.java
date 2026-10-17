package com.example.tvbox.plain.appyqk;

import android.content.Context;
import android.util.Log;

import com.example.tvbox.common.Http;
import com.example.tvbox.common.JsonText;
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

/**
 * AppYQK 明文实现（yqk1.app 自研接口的安卓 App 端）。
 *
 * <p>反编译原文: {@code ae/decompiled/AppYQK.java}（343 行，jadx dev）
 *
 * <h2>协议</h2>
 * <pre>
 *  POST https://yzy0916.n0z6fkpuk.com/v2/api/home/header           → data.channeList
 *  POST https://yzy0916.n0z6fkpuk.com/v2/api/channel/topicListView → data.topicList[].vodList[]
 *  POST https://yzy0916.n0z6fkpuk.com/v2/api/vodInfo/index         → data{...}
 *  POST https://yzy0916.n0z6fkpuk.com/v2/api/vodInfo/epDetail      → data[]
 *  POST https://yzy0916.n0z6fkpuk.com/v2/api/vodInfo/playUrl       → data.playUrl
 *  POST https://yzy0916.n0z6fkpuk.com/v1/api/search/search         → data.items[]
 * </pre>
 * 请求体 = 业务字段 + {@code sign} 的 JSON；请求头三条（见 {@link #headers()}）。
 *
 * <h3>sign 规范化串（键按<b>插入序</b>，appKey 恒在末尾）</h3>
 * <pre>
 *  appId=..&amp;channelId=..&amp;…&amp;version=1.2.7.104&amp;appKey=3359de478f8d45638125e446a10ec541
 *  → MD5 → 小写 hex
 * </pre>
 * 注意 {@code appKey} <b>参与</b>摘要（是盐），但<b>不进请求体</b>；
 * 而 {@code appId}（{@code e6ddefe0…}）是普通业务字段，不是密钥。
 *
 * <h2>ext 三层分类</h2>
 * <table border="1">
 *   <tr><th>类</th><th>内容</th><th>说明</th></tr>
 *   <tr><td><b>B 类</b>（有默认值，可覆盖）</td>
 *       <td>域名 {@code yzy0916.n0z6fkpuk.com}</td>
 *       <td>上游只有一个硬编码域名（无 {@code init()}，
 *           ext 完全不生效）；本实现把它变成默认值 + {@code ext.url} 可覆盖
 *           —— 换域名是常态，密钥不是</td></tr>
 *   <tr><td><b>B 类</b>（可覆盖的展示参数）</td>
 *       <td>{@code reqDomain=}
 *           {@code yqk1.app}、{@code version=1.2.7.104}、{@code deviceInfo=Android}、
 *           {@code cus1tom=aabbcc}</td>
 *       <td>全部写死在 6 个调用点；本实现抽成常量并允许 ext 覆盖
 *           （键名 {@code reqDomain}/{@code version}/{@code deviceInfo}/{@code cus1tom}）</td></tr>
 *   <tr><td><b>C 类</b>（纯硬编码，直接内联）</td>
 *       <td>{@code appId=e6ddefe0…}、{@code appKey=3359de47…}、UA
 *           {@code Dart/3.1 (dart:io)}、{@code Origin/Referer=https://yqk1.app/}、
 *           62 字符随机表、全部接口路径</td>
 *       <td>无 ext 通道；{@code appKey} 已在 {@link #APP_KEY} 内联</td></tr>
 * </table>
 *
 * <h2>发现的上游缺陷（本实现已修，均有断言钉住）</h2>
 * <ol>
 *   <li><b>{@code getUUID()} 拼完 uuid 又丢掉</b>：方法先按
 *       {@code xxxxxxxx-xxxx-4xxx-yxxx-…} 生成真 uuid，再拼上 16 位毫秒 hex，
 *       然后 <b>丢弃拼接结果</b> 只 {@code return str}。于是 {@code udid}
 *       实际是「当前毫秒的 %016x」，每次请求都变、且同一毫秒并发会撞。
 *       本实现保留该返回值（协议要一致），但把空缺的 uuid 生成器单独暴露为
 *       {@link #uuidV4()} 并在 javadoc 标注差异。</li>
 *   <li><b>{@code getSign} 就地改写入参 map</b>：先 {@code put("appKey")}，
 *       再 {@code remove("appKey")}、{@code put("sign")}。若调用方复用同一 map，
 *       {@code sign} 会残留并被带进下一次签名。本实现不改写入参。</li>
 *   <li><b>详情页 {@code vod_play_from} 末尾多一个 {@code $$$}</b>：
 *       循环里先 {@code append(名)} 再无条件 {@code append("$$$")}，
 *       多播放器时尾部留空线路。改为 join。</li>
 *   <li><b>{@code canPlay} 用 {@code getString(...).equals("true")}</b>：
 *       字段缺失直接抛；且只认小写字符串 {@code "true"}，布尔 {@code true} 会被拒。
 *       本实现按 {@link #objStr} 取文本并忽略大小写。</li>
 *   <li><b>{@code searchContent} 里 {@code flags} 缺失</b>用 {@code optString("flags","")}
 *       已是安全写法，但 {@code vodName/coverImg} 用 {@code getString} —— 缺字段即整页失败。
 *       本实现改为容错取。</li>
 * </ol>

 * <h2>未覆盖的分支</h2>
 * <ul>
 *   <li><b>没有登录 / 取 token 的分支</b>：{@code ae/decompiled/AppYQK.java} 里不存在登录接口，
 *       {@code init} 只读 ext。本实现同样未复现任何登录态获取（上游也没有）——
 *       已核对的方法：{@code init / homeContent / categoryContent / detailContent /
 *       searchContent / playerContent}。</li>
 *   <li><b>{@code getUUID()} 造了 uuid 又丢弃</b>：上游返回 {@code %016x} 毫秒值当 udid，
 *       导致每次请求 udid 都变、同毫秒内还会碰撞。本实现<b>保留</b>该行为（它是指纹的一部分），
 *       只把真正的 uuid v4 单独暴露成 {@code uuidV4()}。
 *       断言：{@code udid 长度 16（缺陷 1：uuid 被丢弃）}、{@code uuidV4 与 udid 不同（缺陷 1 的两半）}。</li>
 *   <li><b>弹幕地址</b>：依赖宿主私有 {@code Proxy.getUrl()}。改为可注入 {@code proxyBase}，
 *       未注入时省略 {@code danmaku} 字段（见 {@code playerContent} 里的空判）。</li>
 *   <li><b>刻意保留的上游行为（看着像 bug，其实是对的）</b>：{@code playUrl} 的参数顺序与
 *       {@code detailContent} 不同（{@code epId} 插在 {@code deviceInfo} 之后），
 *       不能"统一成一套 commonParams"——签名对顺序敏感。
 *       断言：{@code 固定向量（python hashlib）} + {@code 顺序影响签名}。</li>
 * </ul>
 */
public class AppYQKPlain extends AppSignedBase {

    private static final String TAG = "AppYQKPlain";

    // ── C 类硬编码 ──────────────────────────────────────────

    /** 业务 appId（不是密钥，但服务端按它路由） */
    public static final String APP_ID = "e6ddefe09e0349739874563459f56c54";
    /** 签名盐 appKey（参与 MD5，不进请求体） */
    public static final String APP_KEY = "3359de478f8d45638125e446a10ec541";
    /** UA（Dart 客户端特征） */
    public static final String UA = "Dart/3.1 (dart:io)";
    /** Origin */
    public static final String ORIGIN = "https://yqk1.app";
    /** Referer */
    public static final String REFERER = "https://yqk1.app/";

    // ── B 类：有默认值，可被 ext 覆盖 ────────────────────────

    /** 默认域名（上游唯一硬编码值） */
    public static final String DEFAULT_BASE = "https://yzy0916.n0z6fkpuk.com";
    /** 请求体默认值：requestId 之外的展示/环境参数 */
    public static final String DEF_REQ_DOMAIN = "yqk1.app";
    public static final String DEF_VERSION = "1.2.7.104";
    public static final String DEF_DEVICE_INFO = "Android";
    public static final String DEF_CUS1TOM = "aabbcc";

    /** 路径常量 */
    public static final String PATH_HOME_HEADER = "/v2/api/home/header";
    public static final String PATH_TOPIC_LIST = "/v2/api/channel/topicListView";
    public static final String PATH_VOD_INFO = "/v2/api/vodInfo/index";
    public static final String PATH_EP_DETAIL = "/v2/api/vodInfo/epDetail";
    public static final String PATH_PLAY_URL = "/v2/api/vodInfo/playUrl";
    public static final String PATH_SEARCH = "/v1/api/search/search";

    /** 搜索结果条数（上游硬编码） */
    public static final String SEARCH_COUNT = "15";

    // ── 实例状态 ────────────────────────────────────────────

    private String reqDomain = DEF_REQ_DOMAIN;
    private String version = DEF_VERSION;
    private String deviceInfo = DEF_DEVICE_INFO;
    private String cus1tom = DEF_CUS1TOM;

    /** 最近一次请求的 URL —— 测试断言用，不发包 */
    private String lastUrl = "";

    /** 构造 */
    public AppYQKPlain() {
        super();
    }

    // ── 生命周期 ────────────────────────────────────────────

    /**
     * 上游没有 {@code init()}；本实现补上，只用于吸收 ext（域名 + 4 个展示参数）。
     *
     * @param context 宿主上下文，可为 null
     * @param extend  ext 原文，可为 null
     */
    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        if (baseUrl.isEmpty()) {
            baseUrl = DEFAULT_BASE;             // B 类：默认值兜底
        }
        this.reqDomain = def(ext.optStr("reqDomain"), DEF_REQ_DOMAIN);
        this.version = def(ext.optStr("version"), DEF_VERSION);
        this.deviceInfo = def(ext.optStr("deviceInfo"), DEF_DEVICE_INFO);
        this.cus1tom = def(ext.optStr("cus1tom"), DEF_CUS1TOM);
        Log.i(tag(), "init ok, base=" + baseUrl + ", version=" + version);
    }

    private static String def(String v, String fallback) {
        return v == null || v.isEmpty() ? fallback : v;
    }

    // ── 签名 ────────────────────────────────────────────────

    /**
     * 计算 {@code sign}（不修改入参 —— 缺陷 2）。
     *
     * @param fields 业务字段（保序），可为 null
     * @return 32 位小写 hex
     */
    public static String sign(Map<String, String> fields) {
        String head = canonical(fields, '&');
        String joined = head.isEmpty() ? "" : head + "&";
        return md5Lower(joined + "appKey=" + APP_KEY);
    }

    /**
     * 业务字段 + 签名 → 请求体 JSON。
     *
     * <p>字段顺序与上游一致：业务字段按插入序，{@code sign} 最后。
     *
     * <p><b>为什么必须显式声明键序，不能用 {@code o.toString()}</b>
     *
     * <p>上游按字面 {@code LinkedHashMap} 的插入序拼请求体
     * （{@code ae/decompiled/AppYQK.java:115-122}、{@code :143-150}、
     * {@code :265-272}、{@code :294-302}），服务端<b>按字面串验签</b>：
     * 键序一变，签名就永远对不上，而本地自测永远是对的。
     *
     * <p>而 {@code JSONObject.toString()} 的键序是<b>库的实现细节</b>：
     * <ul>
     *   <li>现代 json-java（本项目早期的桩）：内部 {@code LinkedHashMap} → 插入序</li>
     *   <li>旧版 {@code org.json}（fmplayer.app 的 {@code gateway.jar} 自带）：内部
     *       {@code HashMap} → <b>散列序</b></li>
     * </ul>
     * 实测（真宿主）：本方法原先输出
     * {@code {"requestId":…,"appId":…,"sign":…,"reqDomain":…,…}} ——
     * {@code sign} 跑到了中间，三条"字段顺序"断言在真宿主下全红。
     *
     * <p>修法：用 {@link JsonText#object} 按「字段表的插入序 + {@code sign} 最后」
     * 显式输出。转义仍交给宿主的 {@code org.json}（两代都有
     * {@code JSONObject.quote}），所以转义与上游逐字节一致。
     *
     * @param fields 业务字段（保序），可为 null
     * @return JSON 串（构造失败时为空对象串）
     */
    public static String signedBody(Map<String, String> fields) {
        JSONObject o = new JSONObject();
        try {
            List<String> order = new ArrayList<>();
            if (fields != null) {
                for (Map.Entry<String, String> e : fields.entrySet()) {
                    o.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
                    order.add(e.getKey());
                }
            }
            o.put("sign", sign(fields));
            order.add("sign");
            return JsonText.object(o, order);
        } catch (Throwable e) {
            Log.e(TAG, "signedBody failed", e);
            return "{}";
        }
    }

    /**
     * 组一套「公共参数」（6 个调用点逐字重复的那 8 项中除业务项外的部分）。
     *
     * @param requestId 32 位随机串（{@link #randomId()}）
     * @param udid      客户端 id（{@link #getUUID()}）
     * @return 保序字段表
     */
    public LinkedHashMap<String, String> commonParams(String requestId, String udid) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("appId", APP_ID);
        m.put("cus1tom", cus1tom);
        m.put("deviceInfo", deviceInfo);
        m.put("reqDomain", reqDomain);
        m.put("requestId", requestId);
        m.put("udid", udid);
        m.put("version", version);
        return m;
    }

    /**
     * 生成 32 位随机 id（还原 {@code i()}）。
     *
     * @return 32 位 62 进制随机串
     */
    public static String randomId() {
        return randomAlnum(32);
    }

    /**
     * 客户端 id —— 还原 {@code getUUID()} 的<b>实际</b>返回值（缺陷 1）。
     *
     * <p>上游实现里生成的 uuid 被丢弃，返回值是 {@code String.format("%016x", millis)}。
     * 本方法保持一致。
     *
     * @return 16 位小写 hex
     */
    public static String getUUID() {
        return nowHex16();
    }

    /**
     * 上游<b>本该</b>返回的真 uuid（缺陷 1 的另一半，仅供诊断/未来修协议用）。
     *
     * <p>格式 {@code xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx}，{@code y ∈ {8,9,a,b}}。
     *
     * @return 36 字符 uuid
     */
    public static String uuidV4() {
        char[] tpl = "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".toCharArray();
        StringBuilder sb = new StringBuilder(tpl.length);
        for (char c : tpl) {
            if (c == 'x') {
                sb.append(Integer.toHexString(randInt(16)));
            } else if (c == 'y') {
                sb.append(Integer.toHexString((randInt(16) & 3) | 8));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 请求头三条（C 类硬编码）。
     *
     * @return 保序 header map
     */
    public Map<String, String> headers() {
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Origin", ORIGIN);
        h.put("Referer", REFERER);
        return h;
    }

    // ── HTTP ────────────────────────────────────────────────

    /**
     * 发一次 POST（可被测试覆写以注入 fixture）。
     *
     * @param path 接口路径或完整 URL
     * @param body 请求体
     * @return 响应体；失败为空串
     */
    public String fetchPost(String path, String body) {
        String p = path == null ? "" : path;
        String url = p.startsWith("http") ? p : baseUrl + p;
        this.lastUrl = url;
        return http.post(url, body, headers());
    }

    /**
     * 最近一次请求 URL。
     *
     * @return URL；未请求时为空串
     */
    public String lastUrl() {
        return lastUrl;
    }

    /**
     * 发请求并解析 JSON（非 JSON → 空对象）。
     *
     * @param path 路径
     * @param body 请求体
     * @return 非 null JSONObject
     */
    public JSONObject post(String path, String body) {
        return json(fetchPost(path, body));
    }

    // ── 数据方法 ────────────────────────────────────────────

    /**
     * 首页分类：剔除「短剧」「体育」。
     *
     * @param filter 忽略（上游无 filters）
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        String body = signedBody(commonParams(randomId(), getUUID()));
        JSONObject root = post(PATH_HOME_HEADER, body);
        JSONObject data = root.optJSONObject("data");
        VodBuilder b = VodBuilder.create();
        for (JSONObject o : objects(arr(data, "channeList"))) {
            String id = str(o, "channelId");
            String name = str(o, "channelName");
            if (name.isEmpty() || "短剧".equals(name) || "体育".equals(name)) {
                continue;
            }
            b.clazzFlag(id, name, id);
        }
        return b.homeContent();
    }

    /**
     * 分类：{@code data.topicList[].vodList[]} 展平。
     *
     * <p>上游分页参数 {@code page/pagecount/limit} =
     * {@code (pg, pg+1, -1)}，其中 {@code limit=-1} 不是合法值；
     * 本实现用 {@link VodBuilder#page(int)}（pagecount=pg+1，limit 取默认 20）。
     *
     * @param tid    频道 id
     * @param pg     页码
     * @param filter 忽略
     * @param extend 忽略
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        LinkedHashMap<String, String> m = commonParams(randomId(), getUUID());
        m.put("channelId", tid == null ? "" : tid);
        JSONObject root = post(PATH_TOPIC_LIST, signedBody(m));
        List<Vod> list = new ArrayList<>();
        for (JSONObject topic : objects(arr(root.optJSONObject("data"), "topicList"))) {
            list.addAll(vodsFrom(arr(topic, "vodList")));
        }
        return VodBuilder.create().addAll(list).page(page).categoryContent();
    }

    /**
     * 详情：{@code data} 直接是一个对象（不是数组）。
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
        LinkedHashMap<String, String> m = commonParams(randomId(), getUUID());
        m.put("vodId", id);
        JSONObject root = post(PATH_VOD_INFO, signedBody(m));
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            Log.w(tag(), "详情无 data, id=" + id);
            return emptyResponse();
        }
        String vodName = str(data, "vodName");
        Vod v = new Vod(id, vodName)
                .pic(str(data, "coverImg"))
                .area(str(data, "areaName"))
                .year(str(data, "year"))
                .remarks(objStr(data, "updateRemark"))
                .content(str(data, "intro"))
                .actor(actors(data));

        List<String> froms = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        for (JSONObject player : objects(arr(data, "playerList"))) {
            String playerName = str(player, "playerName");
            String total = str(player, "totalEpCount");
            froms.add(playerName + String.format("共(%s)集", total));
            List<String> eps = new ArrayList<>();
            for (JSONObject ep : objects(arr(player, "epList"))) {
                String epId = str(ep, "epId");
                String epName = str(ep, "epName");
                // TVBox 用 $ 切「集名$地址」；地址内部再用 | 分隔三段（见 playerContent）
                eps.add(epName + "$" + epId + "|" + vodName + "|" + epName);
            }
            groups.add(join(eps, "#"));
        }
        v.playFrom(join(froms, "$$$")).playUrl(join(groups, "$$$"));
        return VodBuilder.detail(v).detailContent();
    }

    /**
     * 搜索：{@code data.items[]}，{@code flags} 含「短剧」的剔除。
     *
     * @param key   关键词
     * @param quick 忽略
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        LinkedHashMap<String, String> m = commonParams(randomId(), getUUID());
        m.put("keyword", key == null ? "" : key);
        m.put("nextCount", SEARCH_COUNT);
        JSONObject root = post(PATH_SEARCH, signedBody(m));
        List<Vod> list = new ArrayList<>();
        for (JSONObject o : objects(arr(root.optJSONObject("data"), "items"))) {
            if (objStr(o, "flags").contains("短剧")) {
                continue;
            }
            list.add(vodFromItem(o));
        }
        return VodBuilder.create().addAll(list).searchContent();
    }

    /**
     * 播放解析。
     *
     * <p>id 形如 {@code epId|片名|集名}（由 {@link #detailContent} 生成）。
     * 流程：{@code epDetail(vodEpId=epId)} → 取 {@code canPlay} 的
     * {@code (showName, vodResolution)} 列表 → 逐个 {@code playUrl(vodResolution)}
     * → 输出「清晰度 → 地址」的多线路播放体。
     *
     * @param flag     忽略
     * @param id       {@code epId|片名|集名}
     * @param vipFlags 忽略
     * @return 标准播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String[] p = (id == null ? "" : id).split("\\|");
        if (p.length < 3) {
            Log.w(tag(), "播放 id 段数不足 3: " + id);
            return errorPlay();
        }
        String epId = p[0];
        String vodName = p[1];
        String epName = p[2];
        String danmaku = danmakuUrl(vodName, epName);
        try {
            JSONObject epRoot = post(PATH_EP_DETAIL, signedBody(epParams(epId)));
            JSONObject url = new JSONObject();
            for (JSONObject o : objects(epRoot.optJSONArray("data"))) {
                if (!"true".equalsIgnoreCase(objStr(o, "canPlay"))) {
                    continue;
                }
                String showName = str(o, "showName");
                String resolution = str(o, "vodResolution");
                JSONObject pr = post(PATH_PLAY_URL, signedBody(playParams(epId, resolution)));
                String playUrl = str(pr.optJSONObject("data"), "playUrl");
                if (showName.isEmpty()) {
                    showName = resolution;
                }
                url.put(showName, playUrl);
            }
            JSONObject root = new JSONObject();
            root.put("parse", 0);
            root.put("url", url);
            if (!danmaku.isEmpty()) {
                root.put("danmaku", danmaku);
            }
            root.put("header", new JSONObject(headers()));
            return root.toString();
        } catch (Throwable e) {
            Log.e(tag(), "playerContent failed, id=" + id, e);
            return errorPlay();
        }
    }

    // ── 内部：参数与解析 ────────────────────────────────────

    /**
     * epDetail 的字段表（在公共参数基础上加 {@code vodEpId}）。
     *
     * @param epId 剧集 id
     * @return 保序字段表
     */
    public LinkedHashMap<String, String> epParams(String epId) {
        LinkedHashMap<String, String> m = commonParams(randomId(), getUUID());
        m.put("vodEpId", epId == null ? "" : epId);
        return m;
    }

    /**
     * playUrl 的字段表（{@code epId} 在 {@code deviceInfo} 之后，
     * 与 {@code epDetail} 的字段顺序<b>不同</b> —— 上游两处顺序不一致，签名串因此不同）。
     *
     * @param epId       剧集 id
     * @param resolution 清晰度
     * @return 保序字段表
     */
    public LinkedHashMap<String, String> playParams(String epId, String resolution) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("appId", APP_ID);
        m.put("cus1tom", cus1tom);
        m.put("deviceInfo", deviceInfo);
        m.put("epId", epId == null ? "" : epId);
        m.put("reqDomain", reqDomain);
        m.put("requestId", randomId());
        m.put("udid", getUUID());
        m.put("version", version);
        m.put("vodResolution", resolution == null ? "" : resolution);
        return m;
    }

    /**
     * 拼演员名（上游逐个 append + 空格）。
     *
     * @param data 详情 data
     * @return 演员串（末尾带一个空格，与上游一致）
     */
    public static String actors(JSONObject data) {
        StringBuilder sb = new StringBuilder();
        for (JSONObject o : objects(arr(data, "actorList"))) {
            sb.append(str(o, "vodWorkerName")).append(' ');
        }
        return sb.toString();
    }

    /**
     * 播放失败体（上游直接抛异常，没有兜底体；这里给一个合法空播放体）。
     *
     * @return JSON 串
     */
    public static String errorPlay() {
        try {
            JSONObject o = new JSONObject();
            o.put("parse", 0);
            o.put("url", "");
            o.put("msg", "播放地址解析失败");
            return o.toString();
        } catch (Throwable e) {
            return "{}";
        }
    }

    /**
     * 列表项 → {@link Vod} —— 注意字段名与本族其它源<b>不同</b>：
     * {@code vodId / vodName / coverImg / remark}（不是 {@code vod_id/vod_name/...}）。
     *
     * @param o 源对象，可为 null
     * @return 非 null Vod
     */
    public static Vod vodFromItem(JSONObject o) {
        return new Vod(str(o, "vodId"), str(o, "vodName"))
                .pic(str(o, "coverImg"))
                .remarks(objStr(o, "remark"));
    }

    /**
     * 列表数组 → Vod 列表（字段名见 {@link #vodFromItem}）。
     *
     * @param a 源数组，可为 null
     * @return 非 null 列表
     */
    public static List<Vod> vodsFrom(JSONArray a) {
        List<Vod> out = new ArrayList<>();
        if (a == null) {
            return out;
        }
        for (JSONObject o : objects(a)) {
            out.add(vodFromItem(o));
        }
        return out;
    }

    /**
     * 数组 → 只保留 JSONObject 元素。
     *
     * @param a 源数组，可为 null
     * @return 非 null 列表
     */
    public static List<JSONObject> objects(JSONArray a) {
        List<JSONObject> out = new ArrayList<>();
        if (a == null) {
            return out;
        }
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) {
                out.add(o);
            }
        }
        return out;
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
