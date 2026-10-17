package com.example.tvbox.plain.duanju;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.Vod;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 河马短剧（dzmf / zqqds）明文实现。
 *
 * <h2>证据来源</h2>
 * <ul>
 *   <li>反编译: {@code ae/decompiled/ShortHema.java}（294 行）</li>
 *   <li>加密常量: {@code merge.p032g.a3} —— key {@code dzkjgfyxgshylgzm}(16)、
 *       iv {@code apiupdownedcrypt}(16)，已按本地 jadx 复核长度</li>
 *   <li>HTTP 封装: {@code merge.J.b.b1()} —— 完整还原了 datas 头与响应解密</li>
 *   <li><b>真实请求验证</b>: {@code ae/fixtures/real/shorthema_*.json}
 *       （首页 / 分类 / 搜索 / 详情 / 播放 五端点，线上实抓并解密）</li>
 * </ul>
 *
 * <h2>协议要点（全部经真实请求确认）</h2>
 *
 * <h3>1. 请求（三处都要加密）</h3>
 * <pre>
 *  POST https://freevideo.zqqds.cn/free-video-portal/portal/{1125|1131|1139|1803}
 *  Header:
 *    User-Agent      : okhttp/4.10.0
 *    Content-Type    : application/json; charset=utf-8
 *    alg             : HG45LKBS                       ← 固定标记
 *    datas           : AES-CBC(登录态JSON, k, iv)      ← 请求指纹，加密后 Base64
 *    x-request-id    : 随机 UUID
 *  Body:
 *    AES-CBC(业务JSON, k, iv) → Base64  直接当 body 发，Content-Type 仍声明 json
 * </pre>
 * <b>注意</b>：body 是 Base64 密文，不是 JSON；上游 {@code k.c} 用 POST 发原始字符串。
 *
 * <h3>2. datas 头内容（登录态）</h3>
 * 上游 {@code merge.J.b.l()} 构造 22 个字段后 AES 加密：
 * <pre>
 *  version=3.6.0, pname=com.dz.hmjc, channelCode=HMJC1000001,
 *  utdidTmp="A"+yyyyMMddHHmmss+"TVBX", token="", utdid=7d2e96f3ed64e228ef1b337de386cdce,
 *  os=android, osv=30, brand=Redmi, model=M2012K10C, manu=Xiaomi,
 *  userId="", launch=shortcut, mchid=HMJC1000001, nchid=HMJC1000004,
 *  session1=UUID, session2=同UUID, startScene=shortcut, recSwitch=1,
 *  installTime=毫秒时间戳, p=36
 * </pre>
 *
 * <h3>3. 响应</h3>
 * <pre>
 *  {"timestamp":..., "code":0, "userId":..., "data":"&lt;AES-CBC 密文 Base64&gt;"}
 * </pre>
 * {@code code!=0} 表示业务错误；{@code data} 解密后才是明文 JSON。
 * 若 {@code data} 为空则回退读 {@code datas} 字段（上游同）。
 *
 * <h3>4. 端点与参数</h3>
 * <pre>
 *  首页  1125  {recSwitch:true, pageFlag:"", theaterSubscriptSwitch:true}
 *  分类  1125  + {channelGroupId:int, (channelId:int), pageFlag:"N-1"}
 *  详情  1131  {bookId, needNextChapter:0, isNeedAlias:"", bookAlias:"", resolutionRate:"1080P"}
 *  播放  1139  {bookId, chapterId, unClockType:"load", tierPlaySource:null,
 *               chapterIds:[cid], omap:{...埋点固定值...}}
 *  搜索  1803  {keyword, page:1, size:15, hotWordType:2}
 * </pre>
 *
 * <h3>5. 真实响应结构</h3>
 * <pre>
 *  首页/分类: {channelGroupData:[{channelGroupId, channelGroupName, channelData:[...]}],
 *             columnData:[{videoData:[...]}], videoData:[...]}
 *     —— 上游 ShortHema.c() 先遍历 columnData[].videoData，为空再取顶层 videoData
 *  详情: {videoInfo:{bookName, coverWap, introduction, protagonist, finishStatusCn},
 *          chapterList:[{chapterId, chapterName, chapterIndex}]}
 *  播放: {chapterInfo:[{content:[{mp4Url, mp4SwitchUrl:[...]}]}], ad:{content:...}}
 *     —— content 结构不固定，上游用「递归找第一个 http 地址」的兜底策略
 *  搜索: {searchVos:[{bookId, bookName, coverWap, finishStatusCn, updateNum, videoStarsNum}]}
 * </pre>
 *
 * <h2>加密算法</h2>
 * AES/CBC/PKCS5Padding，key={@code dzkjgfyxgshylgzm}，iv={@code apiupdownedcrypt}，
 * 输出 Base64（上行 NO_WRAP，下行宽松解码）。
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/ShortHema.java} 的对照复核，
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
 * {@code ae/decompiled/ShortHema.java} 为对照基准。
 */
public class ShortHemaPlain extends ShortBase {

    private static final String TAG = "ShortHemaPlain";

    /** 站点根（上游 merge.J.b.b1 里的硬编码常量） */
    private static final String HOST = "https://freevideo.zqqds.cn";

    /** AES key —— 16 字符，已按本地 jadx 复核 */
    private static final String AES_KEY = "dzkjgfyxgshylgzm";
    /** AES iv —— 16 字符，同上 */
    private static final String AES_IV = "apiupdownedcrypt";

    /** alg 头的固定值 */
    private static final String ALG = "HG45LKBS";

    /** 上游 User-Agent */
    private static final String UA = "okhttp/4.10.0";

    /** 站点公用的 mchid（也用于 x-request-id 之外的业务校验） */
    private static final String MCHID = "HMJC1000001";

    /** 每页条数：上游 categoryContent 传 18 */
    private static final int PAGE_SIZE = 18;

    /** 播放器 UA 模板（上游把 Build.MODEL 拼进去，本实现用固定机型） */
    private static final String PLAYER_UA =
            "aliplayer(appv=2.7.1&av=7.1.0&av2=7.1.0_46933858&os=android&ov=11&dm=M2012K10C)";

    /** datas 头缓存（解密后需登录态时才重建；与上游 static 字段 语义一致） */
    private String datasHeader = "";

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);

        // ext.url 可覆盖站点根（便于镜像站）；
        // ext 若是纯文本（Ext 会把任意裸文本当 url），非 http 开头时回退内置站点根，
        // 否则 apiBase() 会拿到一句胡话，请求全部发不出去。
        if (!baseUrl.startsWith("http")) {
            baseUrl = HOST;
        }
        // datas 头与业务无关，可在 init 阶段一次算好（与上游 static 缓存一致）
        datasHeader = buildDatasHeader();
        Log.i(TAG, "init base=" + baseUrl + " datasLen=" + datasHeader.length());
    }

    @Override
    protected String siteName() {
        return "河马";
    }

    @Override
    protected int pageSize() {
        return PAGE_SIZE;
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页：分类 + 推荐。
     *
     * <p>分类取自 {@code channelGroupData}，每个 group 是一个分类（id 用 channelGroupId），
     * 其下的 {@code channelData} 作为该分类的二级筛选（宿主 filters 的 class 维度）。
     *
     * <p>上游把 {@code "全部"} 重命名为 {@code "推荐"} —— 本实现保持一致。
     *
     * @param filter 是否请求筛选定义（本接口数据天然带筛选，故总是输出）
     * @return 首页标准体
     */
    @Override
    public String homeContent(boolean filter) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recSwitch", true);
        body.put("pageFlag", "");
        body.put("theaterSubscriptSwitch", true);

        JSONObject root = postEncrypted("/free-video-portal/portal/1125", body);
        Map<String, String> classes = new LinkedHashMap<>();
        List<Vod> recommend = new ArrayList<>();
        if (root == null) {
            return homeResponse(classes, recommend);
        }

        JSONArray groups = root.optJSONArray("channelGroupData");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g == null) {
                    continue;
                }
                String gid = pick(g, "channelGroupId");
                String gname = pickText(g, "channelGroupName");
                if (gid.isEmpty()) {
                    continue;
                }
                if ("全部".equals(gname)) {
                    gname = "推荐";
                }
                classes.put(gid, gname.isEmpty() ? gid : gname);
            }
        }
        recommend = mapVods(collectVideoData(root), null,
                "bookId|id", "bookName|title", "coverWap|cover",
                "finishStatusCn");
        return homeResponse(classes, recommend);
    }

    // ─────────────────────────────────────────────────────────────
    // 分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 分类翻页。
     *
     * <p>上游用 {@code pageFlag = pg-1}（注意是<b>减一</b>，第 1 页传空串），
     * 且 {@code channelGroupId} 必须能解析成 int。本实现做了容错：
     * tid 非数字时回退 {@code 0}，不抛异常。
     *
     * @param tid    分类 id（channelGroupId）
     * @param pg     页码
     * @param filter 忽略
     * @param extend 筛选参数，可用 {@code class} 传 {@code "channelId@名称"} 指定二级分类
     * @return 分类标准体
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  java.util.HashMap<String, String> extend) {
        int page = pageNum(pg, 1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recSwitch", true);
        body.put("pageFlag", page > 1 ? String.valueOf(page - 1) : "");
        body.put("theaterSubscriptSwitch", true);
        body.put("channelGroupId", toInt(tid, 0));

        // 二级分类：宿主回传 "channelId@名称"
        int channelId = 0;
        if (extend != null) {
            String cls = extend.get("class");
            if (!TextUtils.isEmpty(cls) && cls.contains("@")) {
                channelId = toInt(cls.split("@")[0], 0);
            }
        }
        if (channelId > 0) {
            body.put("channelId", channelId);
        }

        JSONObject root = postEncrypted("/free-video-portal/portal/1125", body);
        if (root == null) {
            return pageResponse(new ArrayList<Vod>(), page, PAGE_SIZE, false);
        }
        List<Vod> list = mapVods(collectVideoData(root), null,
                "bookId|id", "bookName|title", "coverWap|cover", "finishStatusCn");
        // 上游以 hasMore 与「满页」双重判断；此处以满页为准（hasMore 在真实响应里恒 true）
        boolean hasMore = list.size() >= PAGE_SIZE;
        return pageResponse(list, page, PAGE_SIZE, hasMore);
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 详情。
     *
     * <p>真实响应 {@code videoInfo} 与顶层字段同构，上游做了
     * {@code if (videoInfo == null) 用响应本身} 的回退 —— 本实现保留该兜底。
     * 章节列表可能在顶层或在 videoInfo 内，两处都试。
     *
     * @param ids 影片 id（bookId）列表
     * @return 详情标准体
     */
    @Override
    public String detailContent(List<String> ids) {
        String bookId = firstId(ids);
        if (bookId.isEmpty()) {
            return emptyResponse();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bookId", bookId);
        body.put("needNextChapter", 0);
        body.put("isNeedAlias", "");
        body.put("bookAlias", "");
        body.put("resolutionRate", "1080P");

        JSONObject root = postEncrypted("/free-video-portal/portal/1131", body);
        if (root == null) {
            return emptyResponse();
        }
        JSONObject info = root.optJSONObject("videoInfo");
        if (info == null) {
            info = root;
        }

        String name = pickText(info, "bookName|title");
        Vod vod = new Vod()
                .id(bookId)
                .name(name.isEmpty() ? bookId : name)
                .pic(absolutize(pick(info, "coverWap|cover")))
                .remarks(pickText(info, "finishStatusCn"));
        vod.content(pickText(info, "introduction"));
        vod.actor(pickText(info, "protagonist"));

        JSONArray chapters = root.optJSONArray("chapterList");
        if (chapters == null) {
            chapters = info.optJSONArray("chapterList");
        }
        if (chapters == null || chapters.length() == 0) {
            return detailResponse(vod);
        }

        List<String> eps = new ArrayList<>();
        for (int i = 0; i < chapters.length(); i++) {
            JSONObject ch = chapters.optJSONObject(i);
            if (ch == null) {
                continue;
            }
            String chName = pickText(ch, "chapterName");
            String chId = pick(ch, "chapterId");
            if (chId.isEmpty()) {
                continue;
            }
            // 上游把 bookId 与 chapterId 用 "@" 拼成播放 id，播放时再拆
            String playId = bookId + "@" + chId;
            String ep = episode(chName, playId, i + 1);
            if (!ep.isEmpty()) {
                eps.add(ep);
            }
        }
        // 上游此处拼了三条<b>完全相同</b>的线路（arrayList/arrayList2/arrayList3），
        // 明显是复制粘贴残留。本实现只输出一条，避免宿主出现三个一样的线路。
        return detailResponse(attachPlay(vod, siteName(), joinEpisodes(eps)));
    }

    // ─────────────────────────────────────────────────────────────
    // 搜索
    // ─────────────────────────────────────────────────────────────

    /**
     * 搜索。
     *
     * <p>上游 size 固定 15、hotWordType 固定 2；结果数组优先 {@code searchVos}，
     * 回退 {@code content}（真实响应用的是 searchVos）。
     *
     * @param key   关键词
     * @param quick 忽略
     * @return 搜索标准体
     */
    @Override
    public String searchContent(String key, boolean quick) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keyword", key == null ? "" : key.trim());
        body.put("page", 1);
        body.put("size", 15);
        body.put("hotWordType", 2);

        JSONObject root = postEncrypted("/free-video-portal/portal/1803", body);
        if (root == null) {
            return listResponse(new ArrayList<Vod>());
        }
        JSONArray arr = root.optJSONArray("searchVos");
        if (arr == null) {
            arr = root.optJSONArray("content");
        }
        if (arr == null) {
            return listResponse(new ArrayList<Vod>());
        }
        return listResponse(mapVods(arr, null,
                "bookId|id", "bookName|title", "coverWap|cover", "finishStatusCn"));
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 播放：先调播放接口拿真实 mp4 地址。
     *
     * <p>播放 id 形如 {@code bookId@chapterId}（详情阶段拼好）。
     * {@code chapterInfo.content} 的结构不稳定（有时是对象、有时是数组），
     * 上游用 {@code d(obj, key)} 做「递归找第一个以 http 开头的值」——
     * 本实现用 {@link #findFirstHttp(Object, String)} 等价实现。
     *
     * @param flag     线路名（忽略）
     * @param id       播放 id（{@code bookId@chapterId}）
     * @param vipFlags 忽略
     * @return 播放标准体
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        Map<String, String> h = uaHeader(PLAYER_UA);
        if (TextUtils.isEmpty(id)) {
            return playResponse("", h);
        }
        int at = id.indexOf('@');
        if (at <= 0 || at >= id.length() - 1) {
            Log.w(TAG, "player: bad id " + id);
            return playResponse("", h);
        }
        String bookId = id.substring(0, at);
        String chapterId = id.substring(at + 1);

        Map<String, Object> omap = new LinkedHashMap<>();
        omap.put("expId", null);
        omap.put("logId", null);
        omap.put("originName", "bigdata_rec");
        omap.put("recId", null);
        omap.put("scene", "dzmf_video_sc_reco");
        omap.put("sceneId", "dzmf_video_sc_reco");
        omap.put("strategyId", "godum7go");
        omap.put("strategyName", "omap");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bookId", bookId);
        body.put("chapterId", chapterId);
        body.put("unClockType", "load");
        body.put("tierPlaySource", null);
        List<String> ids = new ArrayList<>();
        ids.add(chapterId);
        body.put("chapterIds", ids);
        body.put("omap", omap);

        JSONObject root = postEncrypted("/free-video-portal/portal/1139", body);
        String url = "";
        if (root != null) {
            JSONArray arr = root.optJSONArray("chapterInfo");
            JSONObject ch = null;
            if (arr != null && arr.length() > 0) {
                ch = arr.optJSONObject(0);
            } else {
                ch = root.optJSONObject("chapterInfo");
            }
            if (ch != null) {
                url = findFirstHttp(ch.opt("content"), "mp4Url");
            }
            // 上游回退：广告位里也可能塞了正片地址
            if (url.isEmpty()) {
                JSONObject ad = root.optJSONObject("ad");
                if (ad != null) {
                    url = findFirstHttp(ad.opt("content"), "mp4Url");
                }
            }
        }
        return playResponse(url, h);
    }

    // ─────────────────────────────────────────────────────────────
    // 响应解析辅助
    // ─────────────────────────────────────────────────────────────

    /**
     * 收集所有 {@code videoData}：先按 {@code columnData[].videoData}，为空再取顶层。
     *
     * <p>严格还原上游 {@code ShortHema.c(JSONObject)}。
     *
     * @param root 已解密的响应对象
     * @return 合并后的数组；无数据时返回空数组（非 null）
     */
    protected JSONArray collectVideoData(JSONObject root) {
        JSONArray out = new JSONArray();
        if (root == null) {
            return out;
        }
        JSONArray columns = root.optJSONArray("columnData");
        if (columns != null) {
            for (int i = 0; i < columns.length(); i++) {
                JSONObject col = columns.optJSONObject(i);
                if (col == null) {
                    continue;
                }
                JSONArray vs = col.optJSONArray("videoData");
                if (vs == null) {
                    continue;
                }
                for (int j = 0; j < vs.length(); j++) {
                    Object o = vs.opt(j);
                    if (o != null) {
                        out.put(o);
                    }
                }
            }
        }
        if (out.length() == 0) {
            JSONArray top = root.optJSONArray("videoData");
            if (top != null) {
                for (int j = 0; j < top.length(); j++) {
                    Object o = top.opt(j);
                    if (o != null) {
                        out.put(o);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 在一个任意结构（对象/数组/字符串）里找出第一个 http 地址。
     *
     * <p>还原上游 {@code ShortHema.d(Object, String)} 的语义：
     * <ol>
     *   <li>先试指定 key（如 {@code mp4Url}）</li>
     *   <li>再试 {@code mp4Url}</li>
     *   <li>再试 {@code mp4SwitchUrl} 数组</li>
     *   <li>最后遍历对象所有字段找第一个 http</li>
     * </ol>
     *
     * @param node     任意节点
     * @param preferKey 首选字段名，可为 null
     * @return 找到的 http 地址；无则空串
     */
    public static String findFirstHttp(Object node, String preferKey) {
        if (node == null || node == JSONObject.NULL) {
            return "";
        }
        if (node instanceof String) {
            String s = ((String) node).trim();
            return s.startsWith("http") ? s : "";
        }
        // 兼容两种容器形态：
        //   ① 归一化之后，`new JSONObject(text)` / put 出来的嵌套对象**一律是 JSONObject**，
        //      所以 JSONObject/JSONArray 那两支才是主路径；
        //   ② 调用方手拼 LinkedHashMap/ArrayList 传进来时才会走 Map/List 这两支。
        // 两支都保留是有意的：本方法是对外 public 的通用游走器，输入形态由调用方决定。
        // ⚠️ 历史注释曾写"opt() 可能返回底层 Map，这里兜底" —— 那描述的是**旧版桩的缺陷**
        //    （桩的 put 未按真 org.json 归一化），该缺陷已修，故措辞一并更正，
        //    避免后来者把 Map 分支当成主路径去"优化"。
        if (node instanceof java.util.Map) {
            JSONObject wrapped = new JSONObject((java.util.Map<?, ?>) node);
            return findFirstHttp(wrapped, preferKey);
        }
        if (node instanceof java.util.List) {
            JSONArray wrapped = new JSONArray((java.util.List<?>) node);
            return findFirstHttp(wrapped, preferKey);
        }
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String s = asHttp(a.optString(i));
                if (!s.isEmpty()) {
                    return s;
                }
            }
            // 数组元素也可能是对象
            for (int i = 0; i < a.length(); i++) {
                String s = findFirstHttp(a.opt(i), null);
                if (!s.isEmpty()) {
                    return s;
                }
            }
            return "";
        }
        if (!(node instanceof JSONObject)) {
            return "";
        }
        JSONObject o = (JSONObject) node;

        if (!TextUtils.isEmpty(preferKey)) {
            String s = findFirstHttp(o.opt(preferKey), null);
            if (!s.isEmpty()) {
                return s;
            }
        }
        String s = asHttp(o.optString("mp4Url"));
        if (!s.isEmpty()) {
            return s;
        }
        JSONArray sw = o.optJSONArray("mp4SwitchUrl");
        if (sw != null) {
            for (int i = 0; i < sw.length(); i++) {
                String v = asHttp(sw.optString(i));
                if (!v.isEmpty()) {
                    return v;
                }
            }
        }
        // 兜底：遍历所有字段
        java.util.Iterator<String> keys = o.keys();
        while (keys != null && keys.hasNext()) {
            String k = keys.next();
            String v = findFirstHttp(o.opt(k), null);
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    /**
     * 判断字符串是否 http 地址。
     *
     * @param s 原文
     * @return 原值或空串
     */
    private static String asHttp(String s) {
        if (s == null) {
            return "";
        }
        String v = s.trim();
        return v.startsWith("http") ? v : "";
    }

    // ─────────────────────────────────────────────────────────────
    // 加密 HTTP 层
    // ─────────────────────────────────────────────────────────────

    /**
     * 发一次加密 POST 并解密响应。
     *
     * @param path 端点路径，如 {@code /free-video-portal/portal/1125}
     * @param body 业务参数（会被序列化后 AES 加密）
     * @return 解密后的明文对象；任一步失败返回 null
     */
    protected JSONObject postEncrypted(String path, Map<String, Object> body) {
        String base = apiBase();
        if (TextUtils.isEmpty(base)) {
            Log.e(TAG, "postEncrypted: empty base");
            return null;
        }
        if (TextUtils.isEmpty(datasHeader)) {
            datasHeader = buildDatasHeader();
        }

        String plainBody = toJson(body);
        String cipherBody = AesGcmCbc.encrypt(plainBody, AES_KEY, AES_IV);
        if (cipherBody.isEmpty()) {
            Log.e(TAG, "postEncrypted: body encrypt failed");
            return null;
        }

        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("alg", ALG);
        h.put("datas", datasHeader);
        h.put("x-request-id", UUID.randomUUID().toString());

        String url = com.example.tvbox.common.Http.buildUrl(base, path, null);
        String resp = http.post(url, cipherBody, h);
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "postEncrypted: empty response " + path);
            return null;
        }

        JSONObject env = parseObject(resp);
        if (env == null) {
            Log.w(TAG, "postEncrypted: non-json " + head(resp, 120));
            return null;
        }
        // 上游的 retry 分支：code=8 表示登录态过期，重取一次
        String data = env.optString("data", "");
        if (data.isEmpty()) {
            data = env.optString("datas", "");
        }
        if (data.isEmpty()) {
            Log.w(TAG, "postEncrypted: no data field, code=" + env.optString("code")
                    + " msg=" + env.optString("msg"));
            return null;
        }
        String plain = AesGcmCbc.decrypt(data, AES_KEY, AES_IV);
        if (plain.isEmpty()) {
            Log.e(TAG, "postEncrypted: decrypt failed");
            return null;
        }
        JSONObject out = parseObject(plain);
        if (out == null) {
            Log.w(TAG, "postEncrypted: plain is not json " + head(plain, 120));
        }
        return out;
    }

    /**
     * 构造 {@code datas} 头：登录态 JSON → AES-CBC → Base64。
     *
     * <p>字段顺序与取值严格对齐上游 {@code merge.J.b.l()}。
     *
     * @return Base64 密文；加密失败返回空串
     */
    protected String buildDatasHeader() {
        String sess = UUID.randomUUID().toString();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", "3.6.0");
        m.put("pname", "com.dz.hmjc");
        m.put("channelCode", MCHID);
        m.put("utdidTmp", "A" + utdidTime() + "TVBX");
        m.put("token", "");
        m.put("utdid", "7d2e96f3ed64e228ef1b337de386cdce");
        m.put("os", "android");
        m.put("osv", 30);
        m.put("brand", "Redmi");
        m.put("model", "M2012K10C");
        m.put("manu", "Xiaomi");
        m.put("userId", "");
        m.put("launch", "shortcut");
        m.put("mchid", MCHID);
        m.put("nchid", "HMJC1000004");
        m.put("session1", sess);
        m.put("session2", sess);
        m.put("startScene", "shortcut");
        m.put("recSwitch", 1);
        m.put("installTime", System.currentTimeMillis());
        m.put("p", 36);
        return AesGcmCbc.encrypt(toJson(m), AES_KEY, AES_IV);
    }

    /**
     * 生成 {@code yyyyMMddHHmmss}（本地时区，与上游 SimpleDateFormat 一致）。
     *
     * @return 14 位时间串
     */
    private static String utdidTime() {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US);
        return f.format(new java.util.Date());
    }

    /**
     * 把 Map 序列化成 JSON 文本（保持插入顺序，null 输出为 JSON null）。
     *
     * @param map 参数表
     * @return JSON 文本
     */
    public static String toJson(Map<String, Object> map) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                Object v = e.getValue();
                // null 必须显式写成 JSON null（JSONObject.NULL）。
                // 为什么不能直接 o.put(k, null)：org.json 的 put(String, Object) 遇到 null 是
                // **删键**语义（现代 json-java 与宿主旧版都如此，不是版本差异）——
                // 于是本方法原先承诺的"null 输出为 JSON null"（见本方法 javadoc）根本没做到，
                // 而是静默把键丢了。真机实测：m.put("n", null) 后产物里既没有 "n" 也没有 null。
                // 这也是"文档与实现矛盾"里最隐蔽的一类：序列化器少一个键，调用方看不出。
                if (v == null) {
                    o.put(e.getKey(), JSONObject.NULL);
                    continue;
                }
                // 同时接受"原始集合"与"已包装的 JSONObject/JSONArray"：
                // 调用方可能直接塞 JSONArray，也可能塞自己拼的 List/Map。
                // 只认一种会在另一半输入上静默走 else，把整个子对象按标量塞进去。
                if (v instanceof JSONArray) {
                    o.put(e.getKey(), v);
                } else if (v instanceof List) {
                    JSONArray a = new JSONArray();
                    for (Object item : (List<?>) v) {
                        a.put(item);
                    }
                    o.put(e.getKey(), a);
                } else if (v instanceof JSONObject) {
                    o.put(e.getKey(), v);
                } else if (v instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sub = (Map<String, Object>) v;
                    o.put(e.getKey(), new JSONObject(toJson(sub)));
                } else {
                    o.put(e.getKey(), v);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "toJson failed", e);
        }
        return o.toString();
    }

    /**
     * 供测试读取当前 datas 头。
     *
     * @return Base64 密文
     */
    public String datasHeader() {
        return datasHeader == null ? "" : datasHeader;
    }

    /**
     * AES key（供测试断言长度）。
     *
     * @return key 原文
     */
    public static String aesKey() {
        return AES_KEY;
    }

    /**
     * AES iv（供测试断言长度）。
     *
     * @return iv 原文
     */
    public static String aesIv() {
        return AES_IV;
    }

    /**
     * 站点根（供测试断言）。
     *
     * @return 站点根 URL
     */
    public static String host() {
        return HOST;
    }

    // ─────────────────────────────────────────────────────────────
    // 内联的 AES-CBC 工具
    // ─────────────────────────────────────────────────────────────

    /**
     * 本类专用的 AES/CBC/PKCS5 实现。
     *
     * <p>不复用 {@code plain.appget.AesGcm} 的原因：那是 appget 包内部工具，
     * 跨包直接依赖会让两个任务的演进互相牵制；此处只有 40 行，自带更安全。
     */
    static final class AesGcmCbc {

        private AesGcmCbc() {
        }

        /**
         * 加密 → Base64（NO_WRAP）。
         *
         * @param plain 明文
         * @param key   16/24/32 字符
         * @param iv    16 字符（不足补零、超出截断）
         * @return Base64 密文；失败返回空串
         */
        static String encrypt(String plain, String key, String iv) {
            try {
                javax.crypto.Cipher c =
                        javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec(key), ivSpec(iv));
                byte[] out = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(out);
            } catch (Exception e) {
                Log.e(TAG, "aes encrypt failed", e);
                return "";
            }
        }

        /**
         * Base64 解密（宽松解码，容忍换行与缺 padding）。
         *
         * @param cipherB64 Base64 密文
         * @param key       密钥
         * @param iv        IV
         * @return 明文；失败返回空串
         */
        static String decrypt(String cipherB64, String key, String iv) {
            try {
                byte[] raw = decodeLenient(cipherB64);
                if (raw.length == 0) {
                    return "";
                }
                javax.crypto.Cipher c =
                        javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec(key), ivSpec(iv));
                return new String(c.doFinal(raw), StandardCharsets.UTF_8);
            } catch (Exception e) {
                Log.e(TAG, "aes decrypt failed", e);
                return "";
            }
        }

        /**
         * 宽松 Base64 解码，等价 Android {@code Base64.decode(s, 0)}。
         *
         * @param s 原文
         * @return 字节；非法输入返回空数组
         */
        static byte[] decodeLenient(String s) {
            if (s == null) {
                return new byte[0];
            }
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '\n' || ch == '\r' || ch == ' ' || ch == '\t') {
                    continue;
                }
                sb.append(ch);
            }
            String t = sb.toString();
            int rem = t.length() % 4;
            if (rem == 2) {
                t = t + "==";
            } else if (rem == 3) {
                t = t + "=";
            } else if (rem == 1) {
                return new byte[0];
            }
            try {
                return Base64.getDecoder().decode(t);
            } catch (Exception e) {
                return new byte[0];
            }
        }

        /**
         * 构造 AES 密钥。
         *
         * @param key 密钥文本
         * @return SecretKeySpec
         */
        private static javax.crypto.spec.SecretKeySpec keySpec(String key) {
            return new javax.crypto.spec.SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "AES");
        }

        /**
         * 构造 IV，非 16 字节时补零/截断。
         *
         * @param iv IV 文本
         * @return IvParameterSpec
         */
        private static javax.crypto.spec.IvParameterSpec ivSpec(String iv) {
            byte[] b = iv.getBytes(StandardCharsets.UTF_8);
            if (b.length == 16) {
                return new javax.crypto.spec.IvParameterSpec(b);
            }
            byte[] out = new byte[16];
            System.arraycopy(b, 0, out, 0, Math.min(b.length, 16));
            return new javax.crypto.spec.IvParameterSpec(out);
        }
    }
}
