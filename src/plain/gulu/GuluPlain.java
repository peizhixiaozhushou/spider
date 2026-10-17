package com.example.tvbox.plain.gulu;

import android.text.TextUtils;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.player.PlayerResponse;
import com.example.tvbox.plain.proto.Proto;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 咕噜（Gulu）—— 纯 protobuf + AES-GCM 的加密 App 源。
 *
 * <p>反编译原文: {@code ae/decompiled/Gulu.java}（1017 行）。
 *
 * <h2>协议</h2>
 * <pre>
 *  接口   POST http://103.45.132.22:19987/app/bn/v2   （硬编码 IP，非域名）
 *  握手   pb{1:65字节EC点, 2:"1.0.0", 3:{1:1,2:1,3:1}}
 *         → zlib(level5, 带头) → AES-256-CBC/PKCS5(key=随机32字符hex, iv=随机16B)
 *         → base64(iv||ct) 放 text/plain 体 + 头 X-Handshake-Key: <同一把 key>
 *         响应同构：base64 → iv(16) → CBC 解密 → zlib 解压 → pb{1:sessionId, 2:服务端EC点}
 *         会话密钥 = HMAC(HMAC(sessionId, ECDH32), "v2-session"+0x01)[0..32)
 *  业务   pb{1:ts,2:type,3:sub,4:sessionId,5:空,6:payload,7:ts}
 *         → zlib(level6, 裸 deflate) → AES-256-GCM(nonce 12B, tag 128bit)
 *         → pb{1:ts, 2: nonce||ct||tag}  头 X-Player-Page-Protection: 1 / X-Session-Id
 *  类型   1/0 boot（上报指纹 + 取源站表）；3/66 分类与首页；3/62 详情；3/61 搜索
 * </pre>
 *
 * <h2>播放链路（详情里的"线路"其实是外部解析 API 的前缀）</h2>
 * <pre>
 *  详情返回的线路名形如 {@code CO4K_&lt;id&gt;} / {@code NBY-&lt;id&gt;} / {@code xfy-&lt;id&gt;}
 *    ↓ 按前缀查表得"源站名"（PREFIX_TO_SOURCE），再按源站名查 API 模板
 *       （优先用 boot 下发的模板，没有才用源码内置的 SOURCE_TO_API）
 *    ↓ GET 模板 + URLEncoder(id)      →  {"url":"..."}
 *    ↓ 正则兜底 "\"url\"\s*:\s*\"([^\"]+)\""
 *    ↓ upgradeM3u8()：统一码率到 2000k / 720p→1080p
 * </pre>
 *
 * <h2>证据来源</h2>
 * 除类头部说明外，各方法上都标了对应的原方法（{@code a/b/c/d/e/f/i} 等混淆名）。
 *
 * <h2>响应体折算说明（与上游的已知偏差）</h2>
 * 上游用 {@code merge.p028c.c} 手工拼 17 个字段的响应对象，本重写改用项目统一的
 * {@link VodBuilder}。折算规则与差异：
 * <ul>
 *   <li><b>列表/分页</b>：上游 {@code page/pagecount/limit/total} 四项都是显式写入的；
 *       {@code VodBuilder} 只能给 page/total/limit 并由 total 反推 pagecount。
 *       见 {@link #pagingTotal(int, boolean)}：这样折算后
 *       {@code page/pagecount/limit} 三者与上游<b>完全一致</b>，只有
 *       "已满页"时输出的 {@code total} 变成 0（总数未知）而不是"本页条数"。</li>
 *   <li><b>搜索</b>：上游搜索也返回分页信封（同一个 {@code c.i(...)} 调用），
 *       所以这里同样用 {@code categoryContent()} 输出而不是只输出 {@code list}。</li>
 *   <li><b>详情为空</b>：上游返回 {@code {"list":[]}}；{@code VodBuilder.detailContent()}
 *       会返回 {@code "{}"}，因此空详情走 {@link #emptyList()}。</li>
 *   <li><b>缺失字段</b>：上游 Gson 会省略 null 字段但会输出基本类型默认值
 *       （如 {@code "parse":0,"jx":0}），{@code VodBuilder} 不输出这两个键。二者对宿主等价。</li>
 * </ul>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>服务端真实响应样本（全部接口）</b> —— 反编译只能给出"字段号 → 语义"的
 *       静态推断（例如详情 pb 的 5/12/13/17/18/21/22/28/30/75），没有一次真实抓包
 *       或固定向量可对照。因此<b>字段号可能被错配</b>（例如把 17/18 谁是"导演"
 *       写反），运行前需要用真实响应验证一次。要补齐需要：一份真实响应体
 *       （抓包）或服务端录制的固定向量。</li>
 *   <li><b>{@code boot()} 下发的源站模板内容</b> —— 表结构还原了（字段 7 → {2:名字, 3:URL}），
 *       但具体有哪些源站、模板长什么样完全未知（要有一次真实 boot 响应）。</li>
 *   <li><b>{@code parseJsonAndSave(vodName)}</b> —— 上游在 detailContent 末尾调用
 *       {@code merge.p026a.a.parseJsonAndSave}，作用是把片名写进宿主 SharedPreferences
 *       的"历史片名"（供弹幕匹配），依赖宿主 {@code merge.p038m.l} 与
 *       {@code Danmu.getRealName}。本重写<b>不实现该副作用</b>（无返回值、与数据无关；
 *       且它写入的是宿主私有存储）。要补齐需要宿主侧 prefs 通道。</li>
 *   <li><b>后台预热线程</b> —— 上游 {@code init()} 会 {@code new Thread(...).start()} 立刻握手+boot。
 *       本重写改成<b>首次用到时惰性建立会话</b>（{@link GuluSession#ensureReady()}），
 *       理由：① 测试必须零网络；② 宿主对 init 阶段的网络调用敏感。行为差异只在
 *       "首次请求是否多等一次握手"。</li>
 *   <li><b>重试时序</b> —— 上游播放解析失败会 {@code Thread.sleep(600)} 再试一次（共 2 次），
 *       搜索失败会按 {@code 2^n * 500ms} 退避（共 3 次）。本重写保留同样的次数与退避，
 *       但把"等待"抽成可覆写的 {@link #sleep(long)}，测试可置零。</li>
 *   <li><b>真实来源的"线路名 → 标签"映射</b> —— {@code SOURCE_TO_LABEL} 的 15 条是原文常量；
 *       但真实站点是否会下发未列出的线路名、以及未列出时上游直接<b>沿用原名</b>
 *       （本重写同样如此），无运行时证据。</li>
 * </ol>
 *
 * <h2>上游缺陷（本重写保留或修复，逐条标注）</h2>
 * <ol>
 *   <li><b>已修复</b>：{@code detailContent} 里 {@code Long.parseLong(list.get(0))} 未做保护，
 *       id 非数字会抛 {@code NumberFormatException}，{@code list} 为空会抛
 *       {@code IndexOutOfBoundsException}，两者都会冒到宿主。本重写返回空详情体。
 *       另外上游在方法体内给 {@code eVar} 赋值前就有 {@code long j} 的解析，
 *       等于把"参数校验"放在最前面，宿主传坏值时直接崩。</li>
 *   <li><b>已保留（原样复刻）</b>：播放失败分支返回的是 {@code merge.p028c.c.c(msg)}
 *       = {@code {"list":[],"msg":"Empty ID"}}，也就是<b>列表体</b>而不是播放体
 *       （正确形态应是 {@code {"parse","url"}}）。宿主拿到它会解析不出播放地址。
 *       保留是为了行为一致，标注在此供决策。</li>
 *   <li><b>已修复</b>：{@code GuluCrypto.e()}（inflate）在数据被截断时会死循环
 *       （{@code while(!finished())} 且 {@code inflate} 返回 0）。见 {@link ZlibCodec}。</li>
 *   <li><b>已修复</b>：{@code PbUtil.decode} 遇到负长度前缀会 {@code NegativeArraySizeException}
 *       → 游标不前进 → 死循环。见 {@link Proto}。</li>
 *   <li><b>可疑，未改</b>：{@code homeContent} 给每个分类的 filters 写的是<b>单个对象</b>
 *       {@code [{key,name,init,value}]}，而宿主 Gson 反序列化字段类型是
 *       {@code LinkedHashMap<String, List<b>>} —— 只有数组才能对上。
 *       同项目其它类（如 Hxq）写的是数组。此处按上游原样输出单个对象。</li>
 * </ol>
 */
public class GuluPlain extends BaseSpider {

    private static final String TAG = "GuluPlain";

    /** 播放请求头里的 UA（与协议层一致）。 */
    public static final String PLAYER_UA = GuluSession.USER_AGENT;

    /** 播放解析的固定请求头。 */
    public static final Map<String, String> PLAY_HEADERS;

    static {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", PLAYER_UA);
        PLAY_HEADERS = java.util.Collections.unmodifiableMap(h);
    }

    // ── 分类表（上游静态块 map c）────────────────────────────

    /** 分类 id → 服务端 stype 数字。 */
    public static final Map<String, Integer> CATEGORY_TYPES;

    /** 首页分类顺序（上游 {@code homeContent} 里的 12 项表）。 */
    public static final String[][] HOME_CATEGORIES = {
            {"movie", "电影"}, {"tv", "电视剧"}, {"variety", "综艺"},
            {"anime", "动漫"}, {"short", "短剧"}, {"doc", "纪录片"},
            {"cn", "大陆剧"}, {"hk", "港剧"}, {"tw", "台剧"},
            {"us", "美剧"}, {"kr", "韩剧"}, {"jp", "日剧"},
    };

    // ── 播放前缀 → 源站名（上游静态块 map d）─────────────────

    /**
     * 线路 id 前缀 → 源站名。
     *
     * <p>上游用的是 {@code HashMap}（迭代顺序未定义）。这里改用
     * {@link LinkedHashMap} 并按源码声明顺序排列：只要这些前缀两两不构成前缀关系
     * （{@code GuluTest} 里有断言证明），查找结果与迭代顺序无关。
     */
    public static final Map<String, String> PREFIX_TO_SOURCE;

    // ── 源站名 → API 模板（上游静态块 map e）─────────────────

    /**
     * 源码内置的"源站名 → 解析 API 模板"表。
     *
     * <p>只在 boot 未下发同名源站时使用（上游逻辑：
     * {@code sessionApis.containsKey(name) ? sessionApis.get(name) : SOURCE_TO_API.get(name)}）。
     * 模板以 {@code url=} 结尾，后面直接拼 URLEncoder 过的线路 id。
     */
    public static final Map<String, String> SOURCE_TO_API;

    // ── 线路名 → 展示标签（上游静态块 map f）─────────────────

    /** 详情页线路名前缀 → 展示标签（只作用于 {@code vod_play_from}）。 */
    public static final Map<String, String> SOURCE_TO_LABEL;

    static {
        Map<String, Integer> types = new LinkedHashMap<>();
        types.put("movie", 8);
        types.put("tv", 9);
        types.put("variety", 10);
        types.put("anime", 11);
        types.put("short", 12);
        types.put("doc", 6);
        types.put("cn", 34);
        types.put("hk", 41);
        types.put("tw", 42);
        types.put("us", 35);
        types.put("kr", 36);
        types.put("jp", 38);
        types.put("th", 39);
        types.put("uk", 37);
        types.put("ru", 45);
        CATEGORY_TYPES = java.util.Collections.unmodifiableMap(types);

        Map<String, String> prefix = new LinkedHashMap<>();
        prefix.put("CO4K_", "咖啡4K");
        prefix.put("rose_", "咖啡4K");
        prefix.put("NBY-", "蚂蚁");
        prefix.put("qsvip-", "熊出没");
        prefix.put("qingshan-", "熊出没");
        prefix.put("JP-", "旋风金牌");
        prefix.put("xfy-", "新咕噜4K");
        PREFIX_TO_SOURCE = java.util.Collections.unmodifiableMap(prefix);

        Map<String, String> api = new LinkedHashMap<>();
        api.put("咖啡4K", "https://co4k.1ljx.com:32010/api/?key=6db7285d-c228-4cee-918e-67a01ef7c3f8&url=");
        api.put("蚂蚁", "https://api.nbyjson.top:7788/api/?key=8LJohkjTZHC2F9ct48&url=");
        api.put("熊出没", "https://jf.hxx2023.cc/api?key=Uw5rZokFSAywqcLN&url=");
        api.put("旋风金牌", "http://111.170.58.215:4470/api.php?id=");
        api.put("咕噜金牌", "http://111.170.58.215:4470/api.php?id=");
        api.put("新咕噜4K", "http://172.247.189.48:7788/dsxt/api.php?user=xt&key=969632c8da19b3ef8c56e5b51011d5e0&j=19227eae77225a30&url=");
        api.put("咕噜4K", "http://172.247.189.48:7788/dsxt/api.php?user=xt&key=e1234a9df0358c6d78f20721a1b7b055&j=b8582063bca37121&url=");
        SOURCE_TO_API = java.util.Collections.unmodifiableMap(api);

        Map<String, String> label = new LinkedHashMap<>();
        label.put("dyttm3u8", "电影天堂");
        label.put("bfzym3u8", "暴风资源");
        label.put("lzm3u8", "量子资源");
        label.put("ffm3u8", "非凡资源");
        label.put("qq", "腾讯视频");
        label.put("qiyi", "爱奇艺");
        label.put("youku", "优酷");
        label.put("newxfyun", "咕噜4K");
        label.put("xfyun", "咕噜4K二线");
        label.put("CO4K", "咖啡4K超清");
        label.put("jplink", "金牌极速");
        label.put("rose", "玫瑰4K");
        label.put("NBY", "蚂蚁资源");
        label.put("qsvip", "旋风VIP");
        label.put("qingshan", "青山资源");
        SOURCE_TO_LABEL = java.util.Collections.unmodifiableMap(label);
    }

    /** 详情线路重复时追加的后缀（上游用 {@code merge.A.b.a(name,"2")}）。 */
    public static final String DUPLICATE_LABEL_SUFFIX = "2";

    /** 详情页截断长度：导演取前 12 个、分类取前 4 个。 */
    public static final int DETAIL_DIRECTOR_LIMIT = 12;
    /** 详情页分类截断长度。 */
    public static final int DETAIL_CATEGORY_LIMIT = 4;

    /** 搜索/分类每页条数上限（上游 {@code f(bytes, 100)}）。 */
    public static final int PAGE_ITEM_LIMIT = 100;
    /** 首页视频条数上限（上游 {@code f(bytes, 24)}）。 */
    public static final int HOME_VIDEO_LIMIT = 24;

    /** 上游搜索/分类"还有下一页"的判据。 */
    public static final int SEARCH_MORE_THRESHOLD = 10;
    /** 分类里影视类（8..12）的判据。 */
    public static final int CATEGORY_MORE_MOVIE = 10;
    /** 分类里纪录片（6）的判据。 */
    public static final int CATEGORY_MORE_DOC = 3;
    /** 分类的默认判据。 */
    public static final int CATEGORY_MORE_DEFAULT = 2;

    /** 播放重试次数（上游 {@code for (int i2 = 0; i2 < 2; i2++)}）。 */
    public static final int PLAY_RETRY = 2;
    /** 播放重试间隔（毫秒）。 */
    public static final long PLAY_RETRY_DELAY_MS = 600L;
    /** 搜索重试次数。 */
    public static final int SEARCH_RETRY = 3;
    /** 搜索响应的最小合法长度（上游要求 &gt; 200）。 */
    public static final int SEARCH_MIN_LENGTH = 200;

    /** 播放响应里取 url 的正则兜底（响应不是合法 JSON 时用）。 */
    private static final Pattern URL_PATTERN = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");

    // ── 依赖 ─────────────────────────────────────────────────

    /** 会话（握手/密钥/RPC）。可被子类替换。 */
    protected GuluSession session = new GuluSession();

    // ── 生命周期 ─────────────────────────────────────────────

    /**
     * 初始化。
     *
     * <p>与上游的差异：上游在这里开线程做握手+boot（{@code Gulu.a(Gulu)}），
     * 本实现只构造会话，会话在首次用到时惰性建立（{@link GuluSession#ensureReady()}）。
     * 详见类注释「未覆盖的分支」第 4 条。
     *
     * @param context 宿主上下文
     * @param extend  ext 字符串（本站未使用任何 ext 字段）
     */
    @Override
    public void init(android.content.Context context, String extend) {
        super.init(context, extend);
        this.session = new GuluSession();
    }

    /**
     * 覆写会话（测试注入假传输用）。
     *
     * @param s 新会话，null 忽略
     */
    public void setSession(GuluSession s) {
        if (s != null) {
            this.session = s;
        }
    }

    /** @return 当前会话 */
    public GuluSession session() {
        return session;
    }

    /**
     * 可覆写的等待（上游 {@code Thread.sleep}）。测试置空以避免真实等待。
     *
     * @param ms 毫秒
     * @throws InterruptedException 中断时抛出（与上游一致）
     */
    public void sleep(long ms) throws InterruptedException {
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }

    /**
     * 可覆写的文本 GET（播放解析 API 用）。
     *
     * @param url     完整 URL
     * @param headers 请求头
     * @return 响应文本；失败为空串。离线测试下恒为空串
     */
    public String fetch(String url, Map<String, String> headers) {
        return http.get(url, headers);
    }

    // ── 5 个核心方法 ─────────────────────────────────────────

    /**
     * 首页分类与筛选。
     *
     * <p>上游 {@code homeContent(boolean)}。分类与筛选表全部是静态常量，
     * <b>不发任何请求</b>（虽然上游仍会先调 {@code d()} 建会话）。
     *
     * @param filter 宿主是否要筛选（本站两种情况下返回体相同）
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        ensureSession();
        VodBuilder vb = VodBuilder.create();
        for (String[] c : HOME_CATEGORIES) {
            vb.clazz(c[0], c[1]);
        }
        JSONArray years = new JSONArray();
        try {
            years.put(new JSONObject().put("n", "全部").put("v", ""));
            for (int y = 2026; y >= 2010; y--) {
                years.put(new JSONObject().put("n", String.valueOf(y)).put("v", String.valueOf(y)));
            }
            years.put(new JSONObject().put("n", "更早").put("v", "2009"));
        } catch (Exception e) {
            android.util.Log.e(TAG, "homeContent: year filter failed", e);
        }
        for (String[] c : HOME_CATEGORIES) {
            vb.filter(c[0], buildFilter(years));
        }
        return vb.homeContent();
    }

    /**
     * 首页视频（上游 {@code homeVideoContent}）：电视剧(stype 9)第 1 页前 24 条。
     *
     * @return 标准列表 JSON
     */
    @Override
    public String homeVideoContent() {
        ensureSession();
        byte[] resp = null;
        try {
            resp = session.callApi(GuluSession.TYPE_SERIES, GuluSession.SUB_CATEGORY,
                    Proto.encode().varint(1, 9L).varint(2, 1L).toByteArray());
        } catch (Exception e) {
            android.util.Log.e(TAG, "homeVideoContent: callApi failed", e);
        }
        List<Vod> list = new ArrayList<>();
        if (resp != null && resp.length > 0) {
            list = parseVodList(resp, HOME_VIDEO_LIMIT);
        }
        return VodBuilder.create().addAll(list).searchContent();
    }

    /**
     * 分类翻页（上游 {@code categoryContent}）。
     *
     * <p>extend 支持两个键：{@code year}（字段 3）与 {@code sort}（嵌在字段 5 的子消息里，
     * 内含 {@code {1:1, 2:0, 3:sort}}）。
     *
     * @param tid    分类 id（未知 id 回退 tv=9）
     * @param pg     页码
     * @param filter 宿主是否要筛选
     * @param extend 筛选值
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        ensureSession();
        int page = pageNum(pg, 1);
        Integer mapped = CATEGORY_TYPES.get(tid == null ? "" : tid);
        int stype = mapped == null ? 9 : mapped.intValue();

        Proto.Encoder enc = Proto.encode().varint(1, stype).varint(2, page);
        if (extend != null) {
            String year = extend.get("year");
            if (!TextUtils.isEmpty(year)) {
                enc.string(3, year);
            }
            String sort = extend.get("sort");
            if (!TextUtils.isEmpty(sort)) {
                enc.message(5, Proto.encode().varint(1, 1L).varint(2, 0L).string(3, sort));
            }
        }

        List<Vod> list = parseVodList(
                session.callApi(GuluSession.TYPE_SERIES, GuluSession.SUB_CATEGORY, enc.toByteArray()),
                PAGE_ITEM_LIMIT);

        int threshold;
        if (stype >= 8 && stype <= 12) {
            threshold = CATEGORY_MORE_MOVIE;
        } else if (stype == 6) {
            threshold = CATEGORY_MORE_DOC;
        } else {
            threshold = CATEGORY_MORE_DEFAULT;
        }
        boolean more = list.size() >= threshold;
        return VodBuilder.create().addAll(list)
                .page(page, pagingTotal(list.size(), more), Math.max(list.size(), 12))
                .categoryContent();
    }

    /**
     * 详情（上游 {@code detailContent}）。
     *
     * <p>请求 {@code callApi(3, 62, {1:id, 4:"APP_PLATFORM_ANDROID_TV", 5:0})}，
     * 解析出基础字段 + "线路组"（pb 字段 75 → 内层 1=线路名、2=剧集数组）。
     *
     * @param ids 影片 id 列表
     * @return 标准详情 JSON；参数非法或响应为空时返回空列表体
     */
    @Override
    public String detailContent(List<String> ids) {
        ensureSession();
        // 上游直接 Long.parseLong(list.get(0))，坏参数会把异常抛给宿主；此处改为安全降级
        if (ids == null || ids.isEmpty() || TextUtils.isEmpty(ids.get(0))) {
            return emptyList();
        }
        long vodId;
        try {
            vodId = Long.parseLong(ids.get(0).trim());
        } catch (Exception e) {
            android.util.Log.w(TAG, "detailContent: 非法 id=" + ids.get(0));
            return emptyList();
        }

        byte[] resp = session.callApi(GuluSession.TYPE_SERIES, GuluSession.SUB_DETAIL,
                Proto.encode()
                        .varint(1, vodId)
                        .string(4, "APP_PLATFORM_ANDROID_TV")
                        .varint(5, 0L)
                        .toByteArray());
        if (resp == null || resp.length == 0) {
            return emptyList();
        }

        Vod vod = new Vod().id(String.valueOf(vodId));
        List<String> directors = new ArrayList<>();
        List<String> actors = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        List<SourceGroup> groups = new ArrayList<>();

        for (Proto.Field f : Proto.decode(resp)) {
            if (f.wireType() == Proto.WIRE_VARINT) {
                continue;
            }
            switch (f.number()) {
                case 5:
                    vod.name(f.asString());
                    break;
                case 13:
                    vod.pic(f.asString());
                    break;
                case 17:
                    directors.add(f.asString());
                    break;
                case 18:
                    actors.add(f.asString());
                    break;
                case 12:
                    categories.add(f.asString());
                    break;
                case 21:
                    vod.content(f.asString());
                    break;
                case 22:
                    vod.remarks(f.asString());
                    break;
                case 28:
                    vod.area(f.asString());
                    break;
                case 30:
                    vod.year(f.asString());
                    break;
                case 75:
                    SourceGroup g = parseSourceGroup(f.asBytes());
                    if (g != null && !g.episodes.isEmpty()) {
                        groups.add(g);
                    }
                    break;
                default:
                    break;
            }
        }

        vod.director(TextUtils.join("、", directors.subList(0, Math.min(directors.size(), DETAIL_DIRECTOR_LIMIT))));
        vod.actor(TextUtils.join("、", actors));
        vod.area(TextUtils.join("/", categories.subList(0, Math.min(categories.size(), DETAIL_CATEGORY_LIMIT))));

        List<String> playFrom = new ArrayList<>();
        List<String> playUrl = new ArrayList<>();
        HashSet<String> used = new HashSet<>();
        for (SourceGroup g : groups) {
            List<String> eps = new ArrayList<>();
            for (String[] ep : g.episodes) {
                if (ep[1].startsWith("http") || matchPrefix(ep[1]) != null) {
                    eps.add(ep[0] + "$" + ep[1]);
                }
            }
            if (eps.isEmpty()) {
                continue;
            }
            String label = SOURCE_TO_LABEL.containsKey(g.name) ? SOURCE_TO_LABEL.get(g.name) : g.name;
            if (used.contains(label)) {
                // 上游只加一级后缀：第三个同名线路会再次变成同样的 "xxx2"
                label = label + DUPLICATE_LABEL_SUFFIX;
            }
            used.add(label);
            playFrom.add(label);
            playUrl.add(TextUtils.join("#", eps));
        }
        vod.playFrom(TextUtils.join(Vod.SEP_SOURCE, playFrom));
        vod.playUrl(TextUtils.join(Vod.SEP_SOURCE, playUrl));

        return VodBuilder.create().add(vod).detailContent();
    }

    /**
     * 搜索（两参数重载，页码固定第 1 页）。
     *
     * @param key   关键词
     * @param quick 宿主快速搜索标志（本站忽略）
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    /**
     * 搜索（上游三参数重载）。
     *
     * <p>请求 {@code callApi(3, 61, {1:key, 2:page, 5:{1:1, 2:0, 3:"vod_hits_week"}})}，
     * 失败按 {@code 2^n * 500ms} 退避重试 3 次；最终响应长度 &lt; 200 视为无结果。
     *
     * @param key         关键词
     * @param quick       宿主快速搜索标志
     * @param quickSearch 页码字符串
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick, String quickSearch) {
        ensureSession();
        int page = pageNum(quickSearch, 1);
        byte[] payload = Proto.encode()
                .string(1, key == null ? "" : key)
                .varint(2, page)
                .message(5, Proto.encode().varint(1, 1L).varint(2, 0L).string(3, "vod_hits_week"))
                .toByteArray();

        byte[] resp = null;
        for (int i = 0; i < SEARCH_RETRY && (resp == null || resp.length <= SEARCH_MIN_LENGTH); i++) {
            resp = session.callApi(GuluSession.TYPE_SERIES, GuluSession.SUB_SEARCH, payload);
            if (resp != null && resp.length > SEARCH_MIN_LENGTH) {
                break;
            }
            if (i == SEARCH_RETRY - 1) {
                break;
            }
            try {
                sleep((long) (Math.pow(2.0d, i) * 500.0d));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (resp == null || resp.length < SEARCH_MIN_LENGTH) {
            return emptyList();
        }
        List<Vod> list = parseVodList(resp, PAGE_ITEM_LIMIT);
        boolean more = list.size() >= SEARCH_MORE_THRESHOLD;
        return VodBuilder.create().addAll(list)
                .page(page, pagingTotal(list.size(), more), Math.max(list.size(), 12))
                .categoryContent();
    }

    /**
     * 播放（上游 {@code playerContent}）。
     *
     * <p>三条分支：① id 本身是 http 直链 → 直接返回（m3u8/m.php 走宿主嗅探）；
     * ② 前缀命中 → 请求外部解析 API 取 url；③ 其余 → 报错。
     *
     * @param flag     播放源标识（本站未使用）
     * @param id       播放 id
     * @param vipFlags 需解析的标识集合（本站未使用）
     * @return 播放 JSON（成功）或"列表体 + msg"（失败，上游原样行为）
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String playId = id == null ? "" : id;
        if (TextUtils.isEmpty(playId)) {
            return PlayerResponse.listWithMsg("Empty ID");
        }
        if (playId.startsWith("http")) {
            int parse = (playId.contains(".m3u8") || playId.contains("m.php")) ? 1 : 0;
            return PlayerResponse.withDanmaku(
                    PlayerResponse.build(upgradeM3u8(playId), parse ^ 1, PLAY_HEADERS));
        }
        // 咖啡4K 的 id 里可能含空格与 '+'；含 '+' 且未编码时先整体编码再回填几个常见字符
        if ((playId.startsWith("CO4K_") || playId.startsWith("rose_"))
                && playId.contains("+") && !playId.contains("%2B")) {
            try {
                playId = URLEncoder.encode(playId.replace(" ", "+"), "UTF-8")
                        .replace("%3A", ":").replace("%2C", ",")
                        .replace("%5F", "_").replace("%2D", "-");
            } catch (Exception e) {
                android.util.Log.e(TAG, "playerContent: encode failed", e);
            }
        }
        String prefix = matchPrefix(playId);
        if (prefix == null) {
            return PlayerResponse.listWithMsg("Unknown Prefix");
        }
        String sourceName = PREFIX_TO_SOURCE.get(prefix);
        Map<String, String> booted = session.sourceApis();
        String template = booted.containsKey(sourceName) ? booted.get(sourceName) : SOURCE_TO_API.get(sourceName);
        if (template == null) {
            return PlayerResponse.listWithMsg("Missing API Template");
        }
        for (int attempt = 0; attempt < PLAY_RETRY; attempt++) {
            try {
                String resp = fetch(template + URLEncoder.encode(playId, "UTF-8"), PLAY_HEADERS);
                if (!TextUtils.isEmpty(resp)) {
                    String url = extractUrl(resp);
                    if (!TextUtils.isEmpty(url)) {
                        return PlayerResponse.withDanmaku(
                                PlayerResponse.build(upgradeM3u8(url.replace("\\/", "/")), 0, PLAY_HEADERS));
                    }
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "playerContent: attempt " + attempt + " failed", e);
            }
            if (attempt == PLAY_RETRY - 1) {
                break;
            }
            try {
                sleep(PLAY_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return PlayerResponse.listWithMsg("Parse Failed");
    }

    // ── 解析辅助 ─────────────────────────────────────────────

    /**
     * 上游 {@code Gulu.b(byte[])}：pb → {@link Vod}。
     *
     * <p>字段映射：1(varint)=id、2(varint)=年份、3=片名、6=备注、9 或 11=封面
     * （11 优先，9 仅在 11 缺失时用）。字段 1、3 任一缺失即视为无效。
     *
     * @param pb 单条影片的 pb 字节
     * @return 影片；无效返回 null
     */
    public static Vod parseVod(byte[] pb) {
        String id = "";
        String name = "";
        String remarks = "";
        String pic = "";
        String year = "";
        for (Proto.Field f : Proto.decode(pb)) {
            if (f.wireType() == Proto.WIRE_VARINT) {
                if (f.number() == 1) {
                    id = String.valueOf(f.asLong());
                } else if (f.number() == 2) {
                    year = String.valueOf(f.asLong());
                }
            } else if (f.wireType() == Proto.WIRE_LEN) {
                if (f.number() == 3) {
                    name = f.asString();
                } else if (f.number() == 6) {
                    remarks = f.asString();
                } else if (f.number() == 11 || (f.number() == 9 && pic.isEmpty())) {
                    pic = f.asString();
                }
            }
        }
        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) {
            return null;
        }
        return new Vod().id(id).name(name).pic(pic).remarks(remarks).year(year);
    }

    /**
     * 上游 {@code Gulu.f(byte[], int)}：从响应 pb 里抽出影片列表。
     *
     * <p>三层结构（上游逐层试）：
     * <pre>
     *   外层重复字段（wire=2 且长度 >= 30）
     *     ├─ 内层含字段 66（wire=2）  → 66 的子消息本身是一条影片（最常用）
     *     ├─ 内层含字段 2（varint）   → 外层那条本身是一条影片
     *     └─ 否则 内层字段 3（wire=2, 长度 > 30）→ 其子消息是一条影片
     * </pre>
     *
     * @param pb    响应 pb
     * @param limit 最多返回条数
     * @return 影片列表（按出现顺序，最多 limit 条）
     */
    public static List<Vod> parseVodList(byte[] pb, int limit) {
        List<Vod> out = new ArrayList<>();
        if (pb == null) {
            return out;
        }
        for (Proto.Field outer : Proto.decode(pb)) {
            if (outer.wireType() != Proto.WIRE_LEN || outer.asBytes().length < 30) {
                continue;
            }
            try {
                List<Proto.Field> inner = Proto.decode(outer.asBytes());
                boolean handled = false;
                for (Proto.Field f : inner) {
                    if (f.number() == 66 && f.wireType() == Proto.WIRE_LEN) {
                        Vod v = parseVod(f.asBytes());
                        if (v != null) {
                            out.add(v);
                        }
                        handled = true;
                    }
                }
                if (handled) {
                    continue;
                }
                boolean hasVarint2 = false;
                for (Proto.Field f : inner) {
                    if (f.number() == 2 && f.wireType() == Proto.WIRE_VARINT) {
                        hasVarint2 = true;
                    }
                }
                if (hasVarint2) {
                    Vod v = parseVod(outer.asBytes());
                    if (v != null) {
                        out.add(v);
                    }
                } else {
                    for (Proto.Field f : inner) {
                        if (f.number() == 3 && f.wireType() == Proto.WIRE_LEN && f.asBytes().length > 30) {
                            Vod v = parseVod(f.asBytes());
                            if (v != null) {
                                out.add(v);
                            }
                        }
                    }
                }
                if (out.size() >= limit) {
                    break;
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "parseVodList: bad item", e);
            }
        }
        return out.subList(0, Math.min(out.size(), limit));
    }

    /**
     * 详情线路组：名称 + 剧集列表；上游 {@code detailContent} 里字段 75 的内联解析。
     *
     * @param pb 字段 75 的字节
     * @return 线路组；无有效剧集或结构不符返回 null
     */
    static SourceGroup parseSourceGroup(byte[] pb) {
        List<Proto.Field> fields = Proto.decode(pb);
        String name = "";
        List<String[]> episodes = new ArrayList<>();
        for (Proto.Field f : fields) {
            if (f.number() == 1 && f.wireType() == Proto.WIRE_LEN) {
                name = f.asString();
            } else if (f.number() == 2 && f.wireType() == Proto.WIRE_LEN && f.asBytes().length > 10) {
                long index = 0L;
                String label = "";
                String url = "";
                for (Proto.Field ep : Proto.decode(f.asBytes())) {
                    if (ep.number() == 1 && ep.wireType() == Proto.WIRE_VARINT) {
                        index = ep.asLong();
                    } else if (ep.number() == 3 && ep.wireType() == Proto.WIRE_LEN) {
                        url = ep.asString();
                    } else if (ep.number() == 4 && ep.wireType() == Proto.WIRE_LEN) {
                        label = ep.asString();
                    }
                }
                if (!TextUtils.isEmpty(url)) {
                    if (TextUtils.isEmpty(label)) {
                        label = "第" + index + "集";
                    }
                    episodes.add(new String[]{label, url});
                }
            }
        }
        if (episodes.isEmpty()) {
            return null;
        }
        return new SourceGroup(name, episodes);
    }

    /**
     * 一条线路（线路名 + 剧集）。上游用私有内部类 {@code SourceGroup} 表达。
     */
    public static final class SourceGroup {
        /** 线路名（详情 pb 字段 75 的内层字段 1）。 */
        public final String name;
        /** 剧集：{@code [展示名, 播放 id]}。 */
        public final List<String[]> episodes;

        /**
         * @param name     线路名
         * @param episodes 剧集
         */
        public SourceGroup(String name, List<String[]> episodes) {
            this.name = name == null ? "" : name;
            this.episodes = episodes == null ? new ArrayList<String[]>() : episodes;
        }
    }

    /**
     * 上游 {@code Gulu.e(String)}：按前缀匹配线路 id → 源站名。
     *
     * @param lineId 线路 id
     * @return 命中的前缀；未命中返回 null
     */
    public static String matchPrefix(String lineId) {
        if (lineId == null) {
            return null;
        }
        for (String prefix : PREFIX_TO_SOURCE.keySet()) {
            if (lineId.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    /**
     * 上游 {@code Gulu.i(String)}：m3u8 码率/清晰度统一。
     *
     * <p>{@code /\d{3,4}k[bB]?/ → /2000k/}、{@code index_\d+k → index_2000k}、
     * {@code /720[pP]/ → /1080p/}。非 m3u8 链接原样返回。
     *
     * @param url 播放地址
     * @return 处理后地址
     */
    public static String upgradeM3u8(String url) {
        if (url == null) {
            return null;
        }
        if (!url.contains(".m3u8")) {
            return url;
        }
        try {
            return url.replaceAll("/\\d{3,4}k[bB]?/", "/2000k/")
                    .replaceAll("index_\\d+k", "index_2000k")
                    .replaceAll("/720[pP]/", "/1080p/");
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 从解析 API 的响应里取 {@code url}：先按 JSON 解析，失败再用正则兜底。
     *
     * @param resp 响应文本
     * @return 地址；取不到返回空串
     */
    public static String extractUrl(String resp) {
        if (resp == null || resp.isEmpty()) {
            return "";
        }
        try {
            return new JSONObject(resp).optString("url", "").trim();
        } catch (Exception e) {
            Matcher m = URL_PATTERN.matcher(resp);
            return m.find() ? m.group(1) : "";
        }
    }

    /**
     * 构造首页筛选（年份 + 排序），与上游 {@code homeContent} 里逐分类写入的结构一致。
     *
     * @param years 年份选项（复用同一个数组对象，与上游一致）
     * @return 单个筛选对象组成的 JSONArray
     */
    public JSONArray buildFilter(JSONArray years) {
        JSONArray arr = new JSONArray();
        try {
            JSONArray sorts = new JSONArray();
            sorts.put(new JSONObject().put("n", "最热").put("v", "vod_hits_week"));
            sorts.put(new JSONObject().put("n", "最新").put("v", "vod_time"));
            sorts.put(new JSONObject().put("n", "评分").put("v", "vod_score"));
            arr.put(new JSONObject().put("key", "year").put("name", "年份").put("init", "").put("value", years));
            arr.put(new JSONObject().put("key", "sort").put("name", "排序")
                    .put("init", "vod_hits_week").put("value", sorts));
        } catch (Exception e) {
            android.util.Log.e(TAG, "buildFilter failed", e);
        }
        return arr;
    }

    /**
     * 确保会话可用（上游 {@code Gulu.d()}）。
     */
    protected void ensureSession() {
        session.ensureReady();
    }

    /**
     * 空列表响应 —— 上游 {@code merge.p028c.c.n(new ArrayList())}，即 {@code {"list":[]}}。
     *
     * <p>不用 {@code VodBuilder.detailContent()}：它在列表为空时返回 {@code "{}"}，
     * 而上游返回的是带空 list 的对象，宿主对两者的处理不同（前者会被当成解析失败）。
     *
     * @return {@code {"list":[]}}
     */
    protected String emptyList() {
        return VodBuilder.create().addAll(new ArrayList<Vod>()).searchContent();
    }

    /**
     * 折算 {@link VodBuilder#page(int, int, int)} 需要的 {@code total}，
     * 让派生出的 {@code pagecount} 与上游逐字段一致。
     *
     * <p>上游是<b>显式</b>写 pagecount 的（{@code c.i(page, pagecount, limit, total)}）：
     * 本页已满 → {@code pagecount = page + 1}（"可能还有下一页"），否则 {@code = page}。
     * 而 {@code VodBuilder} 是按 {@code total/limit} 反推 pagecount 的，没法直接指定。
     *
     * <p>推导：
     * <ul>
     *   <li>{@code more} → 传 total=0（VodBuilder 约定：0 表示总数未知，pagecount 取 page+1）
     *       → 正好等于上游的 {@code page+1}。</li>
     *   <li>{@code !more} → 传 {@code max(size,1)}，配合 {@code limit = max(size,12) >= size}
     *       → {@code ceil(total/limit) == 1} → {@code pagecount = max(1, page) = page}，
     *       也等于上游的 {@code page}（含 size==0 的空页）。</li>
     * </ul>
     * 代价：{@code more} 时输出的 {@code total} 是 0（总数未知）而不是上游的"本页条数"。
     * {@code total} 只是展示用信息，而 {@code pagecount} 决定宿主能不能翻页 ——
     * 优先保证后者逐字段一致。
     *
     * @param size 本页条数
     * @param more 上游判据得出的"还有下一页"
     * @return 传给 {@link VodBuilder#page(int, int, int)} 的 total
     */
    public static int pagingTotal(int size, boolean more) {
        return more ? 0 : Math.max(size, 1);
    }

    /**
     * 便捷：分类 id 列表（便于测试与配置校验）。
     *
     * @return 分类 id 数组
     */
    public static List<String> categoryIds() {
        return Arrays.asList(CATEGORY_TYPES.keySet().toArray(new String[0]));
    }
}
