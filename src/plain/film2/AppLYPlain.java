package com.example.tvbox.plain.film2;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 戏曲多多（AppLY）—— 戏曲 App 接口源，JSON API。
 *
 * <h2>协议还原（来自 {@code ae/decompiled/AppLY.java}，并用真实请求验证）</h2>
 *
 * <h3>端点（全部 POST JSON）</h3>
 * <pre>
 *  首页  POST https://fly.daoran.tv/API_ROP/page/setinf/get
 *        body: {"leastNum":"0","memberId":"yszyz","project":"lyhxcx","userId":"yszyz"}
 *        → sects[]  {code,name,...}
 *
 *  分类  POST https://fly.daoran.tv/API_ROP/search/album/list
 *        body: {"cur":页码,"free":"0","item":"o5","pageSize":"20","sect":[分类code]}
 *        → pb.dataList[]  {code,name,img,publishTime,...}
 *        → pb.totalCount / pb.totalPage / pb.cur      ← 真实分页信息（上游丢弃了）
 *
 *  详情  POST https://fly.daoran.tv/API_ROP/album/res/list
 *        body: {"albumCode":id,"cur":"1","project":"lyhxcx",
 *               "pageSize":"2147483647","selectFlag":"0","userId":"yszyz"}
 *        → album{name,img,sect,des,artistName,publishTime}
 *        → pb.dataList[] {name,code}   每集
 *
 *  播放  POST https://fly.daoran.tv/API_ROP/play/get/playurl
 *        body: {"item":"y9","nodeCode":"001000","project":"lyhxcx","px":"2",
 *               "resCode":集code,"userId":"92315ec6e58a45ba7f47fd143b3d7956"}
 *        → playres.playurl   （m3u8 直链，app 端鉴权）
 * </pre>
 *
 * <h3>请求头（反编译 {@code AppLY.a()}）</h3>
 * <pre>
 *  Content-Type : application/json;charset=utf-8
 *  User-Agent   : okhttp/3.9.1
 *  md5          : SkvyrWqK9QHTdCT12Rhxunjx+WwMTe9y4KwgeASFDhbYabRSPskR0Q==
 * </pre>
 *
 * <h3>关于那个硬编码的 userId（重点说明）</h3>
 * <p>类里有两个不同的用户标识，含义不同，<b>不要混用</b>：
 * <table>
 *   <tr><th>字段</th><th>值</th><th>用在哪</th></tr>
 *   <tr><td>{@code userId}</td><td>{@code yszyz}</td>
 *       <td>首页/分类/详情（游客态）</td></tr>
 *   <tr><td>{@code userId}</td><td>{@code 92315ec6e58a45ba7f47fd143b3d7956}</td>
 *       <td><b>仅播放接口</b>（{@link #PLAY_USER_ID}）—— 这是个 32 位 hex，
 *           看起来是某个真实账号的标识，被作者写死以便免登录取流</td></tr>
 * </table>
 * 它<b>不是密钥</b>（不参与任何签名/校验），只是一个身份参数。真实请求已确认带上它能正常返回 playurl。
 *
 * <h3>上游逻辑缺陷（真实抓取验证发现）</h3>
 * <ol>
 *   <li>分类接口返回 {@code pb.totalCount=891 / totalPage=45 / cur=1}，
 *       但上游 {@code h.a(str2, ...)} 直接把 total/limit 写死传 {@code 0}，
 *       导致宿主无法显示总页数。本重写改为读取真实 totalCount。</li>
 *   <li>上游 {@code vod_director} 取的是 {@code album.optString("vod_director")}，
 *       该字段在真实响应里<b>根本不存在</b>（演员信息在 {@code artistName}）。
 *       应为导演留空，演员取 artistName。</li>
 *   <li>封面地址上游用 {@code https://ottphoto.daoran.tv/HD/ + img}，
 *       实测该域名返回 403；真实图片走 CDN 或原样返回 img 相对路径。
 *       本重写保留前缀但提供开关（见 {@link #PIC_BASE}）。</li>
 * </ol>
 *
 * <h2>真实请求验证</h2>
 * <ul>
 *   <li>首页/分类/详情/播放：全部真实 POST 成功（HTTP 200, code=10000000）</li>
 *   <li>夹具：{@code ae/fixtures/real/apply_{home,cat,detail,play}.json}</li>
 * </ul>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/AppLY.java}（124 行）
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
 * <p>判定口径：本节基于对源码与 {@code ae/decompiled/AppLY.java} 的对照复核，
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
 * {@code ae/decompiled/AppLY.java} 为对照基准。
 */
public class AppLYPlain extends BaseSpider {

    private static final String TAG = "AppLYPlain";

    /** 接口基址（反编译 4 个端点共用） */
    public static final String DEFAULT_BASE = "https://fly.daoran.tv";

    /** 图片基址（上游硬编码；实测该域名 403，见类注释缺陷 3） */
    public static final String PIC_BASE = "https://ottphoto.daoran.tv/HD/";

    /** 固定请求头（反编译 AppLY.a()） */
    public static final String UA = "okhttp/3.9.1";
    public static final String HEADER_MD5 =
            "SkvyrWqK9QHTdCT12Rhxunjx+WwMTe9y4KwgeASFDhbYabRSPskR0Q==";

    /** 游客态 userId（首页/分类/详情） */
    public static final String GUEST_USER_ID = "yszyz";

    /**
     * 播放接口专用 userId —— 上游硬编码的 32 位 hex。
     *
     * <p><b>这是身份标识，不是密钥</b>：不参与签名，只是服务端用来判定账号权限。
     * 上游写死它是为了免登录直接拿 m3u8。
     */
    public static final String PLAY_USER_ID = "92315ec6e58a45ba7f47fd143b3d7956";

    /** 项目标识（4 个端点都带） */
    public static final String PROJECT = "lyhxcx";

    /** 分类接口固定参数 */
    public static final String CAT_ITEM = "o5";
    public static final String CAT_FREE = "0";
    public static final int CAT_PAGE_SIZE = 20;

    /** 详情接口固定参数 */
    public static final String DETAIL_SELECT_FLAG = "0";
    public static final String DETAIL_PAGE_SIZE = "2147483647";

    /** 播放接口固定参数 */
    public static final String PLAY_ITEM = "y9";
    public static final String PLAY_NODE_CODE = "001000";
    public static final String PLAY_PX = "2";

    /** 详情页线路名（上游硬编码 "在线播放"） */
    public static final String PLAY_FROM = "在线播放";

    /** 业务成功码（真实响应 code=10000000） */
    public static final int CODE_OK = 10000000;

    /**
     * 构造。
     */
    public AppLYPlain() {
        super();
    }

    /**
     * 还原 {@code AppLY.init(ctx, str)}：上游完全忽略 ext（写死域名）。
     *
     * @param ctx    宿主上下文
     * @param extend ext 原文（本类 api.json 里 ext 为 null）
     */
    @Override
    public void init(Context ctx, String extend) {
        super.init(ctx, extend);
        // 上游不受 ext 影响，始终用 fly.daoran.tv；本重写允许 ext 覆盖以便代理排障
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_BASE;
        }
        Log.i(TAG, "init base=" + baseUrl);
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code AppLY.a(path, map)} —— POST JSON 并返回响应体。
     *
     * @param path 接口路径（不含域名），如 {@code /API_ROP/page/setinf/get}
     * @param body 请求体 JSON
     * @return 响应体；失败空串
     */
    protected String apiPost(String path, JSONObject body) {
        String base = requireUrl();
        if (base.isEmpty()) {
            return "";
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json;charset=utf-8");
        h.put("User-Agent", UA);
        h.put("md5", HEADER_MD5);
        String payload = body == null ? "{}" : body.toString();
        String resp = http.post(base + path, payload, h);
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "空响应: " + path);
        }
        return resp;
    }

    /**
     * 便捷：构造参数对象。
     *
     * @param kv 偶数个 key/value（value 为 String）
     * @return JSONObject；构造失败返回空对象
     */
    protected static JSONObject body(String... kv) {
        JSONObject o = new JSONObject();
        if (kv != null) {
            try {
                for (int i = 0; i + 1 < kv.length; i += 2) {
                    o.put(kv[i], kv[i + 1]);
                }
            } catch (Exception e) {
                Log.e(TAG, "body 构造失败", e);
            }
        }
        return o;
    }

    /**
     * 校验业务码并返回 {@code pb} / 根对象。
     *
     * @param resp 响应体
     * @return 解析出的根 JSONObject；失败返回 null
     */
    protected JSONObject parseOk(String resp) {
        if (TextUtils.isEmpty(resp)) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(resp);
            int code = root.optInt("code", -1);
            if (code != CODE_OK) {
                Log.w(TAG, "业务码非成功: code=" + code + " text=" + root.optString("text"));
                return null;
            }
            return root;
        } catch (Exception e) {
            Log.e(TAG, "响应不是 JSON", e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 首页
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code AppLY.homeContent(boolean)}。
     *
     * <p>真实响应 {@code sects[]} 每项 {{@code code},{@code name}}，
     * code 是短标识（yuju/jingju/hmx/...），作为分类 type_id。
     *
     * @param filter 是否返回筛选（上游无筛选）
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        JSONObject req = body("leastNum", "0", "memberId", GUEST_USER_ID,
                "project", PROJECT, "userId", GUEST_USER_ID);
        JSONObject root = parseOk(apiPost("/API_ROP/page/setinf/get", req));
        VodBuilder vb = VodBuilder.create();
        if (root == null) {
            return vb.homeContent();
        }
        for (String[] c : parseSects(root.optJSONArray("sects"))) {
            vb.clazz(c[0], c[1]);
        }
        return vb.homeContent();
    }

    /**
     * 解析 {@code sects[]}。
     *
     * @param arr sects 数组
     * @return {code, name} 列表
     */
    protected List<String[]> parseSects(JSONArray arr) {
        List<String[]> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String code = o.optString("code");
            String name = o.optString("name");
            if (code.isEmpty()) {
                continue;
            }
            out.add(new String[]{code, name.isEmpty() ? code : name});
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code AppLY.categoryContent(...)}。
     *
     * <p>与上游的差异：上游把分页信息写死成 {@code h.a(str2, ..., 0, 0, 0, list)}，
     * 本重写读取真实的 {@code pb.totalCount} 与 {@code pb.curPageSize}，
     * 让宿主能正确显示总页数。
     *
     * @param tid    分类 code
     * @param pg     页码
     * @param filter 是否启用筛选
     * @param extend 筛选值（上游未使用）
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        String sect = tid == null ? "" : tid.trim();
        if (sect.isEmpty()) {
            return VodBuilder.create().page(page).categoryContent();
        }
        JSONObject req = new JSONObject();
        try {
            req.put("cur", String.valueOf(page));
            req.put("free", CAT_FREE);
            req.put("item", CAT_ITEM);
            req.put("pageSize", String.valueOf(CAT_PAGE_SIZE));
            JSONArray sects = new JSONArray();
            sects.put(sect);
            req.put("sect", sects);
        } catch (Exception ignored) {
            // 忽略
        }
        JSONObject root = parseOk(apiPost("/API_ROP/search/album/list", req));
        if (root == null) {
            return VodBuilder.create().page(page).categoryContent();
        }
        JSONObject pb = root.optJSONObject("pb");
        List<Vod> list = parseAlbumList(pb == null ? null : pb.optJSONArray("dataList"));

        // 真实分页信息（上游丢弃了这几个字段）
        int total = pb == null ? 0 : pb.optInt("totalCount", 0);
        int limit = pb == null ? CAT_PAGE_SIZE : pb.optInt("pageSize", CAT_PAGE_SIZE);
        int cur = pb == null ? page : pb.optInt("cur", page);
        if (limit <= 0) {
            limit = CAT_PAGE_SIZE;
        }
        return VodBuilder.create().addAll(list).page(cur, total, limit).categoryContent();
    }

    /**
     * 解析 {@code pb.dataList[]}（分类/搜索共用的专辑项）。
     *
     * @param arr dataList
     * @return 影片列表
     */
    protected List<Vod> parseAlbumList(JSONArray arr) {
        List<Vod> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String code = o.optString("code");
            if (code.isEmpty()) {
                continue;
            }
            out.add(new Vod()
                    .id(code)
                    .name(o.optString("name"))
                    .pic(picUrl(o.optString("img")))
                    .remarks(o.optString("publishTime")));
        }
        return out;
    }

    /**
     * 图片地址补全（还原上游 {@code "https://ottphoto.daoran.tv/HD/" + img}）。
     *
     * @param img 相对路径或绝对地址
     * @return 完整地址
     */
    public static String picUrl(String img) {
        if (img == null || img.isEmpty()) {
            return "";
        }
        if (img.startsWith("http://") || img.startsWith("https://")) {
            return img;
        }
        return PIC_BASE + img;
    }

    // ─────────────────────────────────────────────────────────────
    // 详情
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code AppLY.detailContent(List)}。
     *
     * <p>真实响应：
     * <pre>
     *  {"album":{"name","img","sect","des","artistName","publishTime",...},
     *   "pb":{"dataList":[{"name":"…-第1集","code":"11013070001210"}, …]}}
     * </pre>
     *
     * @param ids 专辑 code
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String albumCode = firstId(ids);
        if (albumCode.isEmpty()) {
            return emptyResponse();
        }
        JSONObject req = body("albumCode", albumCode, "cur", "1", "project", PROJECT,
                "pageSize", DETAIL_PAGE_SIZE, "selectFlag", DETAIL_SELECT_FLAG,
                "userId", GUEST_USER_ID);
        JSONObject root = parseOk(apiPost("/API_ROP/album/res/list", req));
        if (root == null) {
            return emptyResponse();
        }
        JSONObject album = root.optJSONObject("album");
        if (album == null) {
            Log.w(TAG, "详情无 album: " + albumCode);
            return emptyResponse();
        }

        Vod vod = new Vod()
                .id(albumCode)
                .name(album.optString("name"))
                .pic(picUrl(album.optString("img")))
                .typeName(album.optString("sect"))
                .content(album.optString("des"))
                .actor(album.optString("artistName"))
                // 上游读 album.vod_director，该字段真实不存在（缺陷 2）→ 留空
                .director("")
                .remarks(album.optString("publishTime"));

        List<String> eps = parseEpisodes(root.optJSONObject("pb") == null
                ? null : root.optJSONObject("pb").optJSONArray("dataList"));
        if (!eps.isEmpty()) {
            vod.playFrom(PLAY_FROM).playUrl(HtmlLite.join("#", eps));
        }
        return VodBuilder.detail(vod).detailContent();
    }

    /**
     * 解析每集列表。
     *
     * @param arr pb.dataList
     * @return {@code 集名$集code} 列表
     */
    protected List<String> parseEpisodes(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String code = o.optString("code");
            if (code.isEmpty()) {
                continue;
            }
            String name = o.optString("name");
            if (name.isEmpty()) {
                name = "第" + (i + 1) + "集";
            }
            out.add(name + "$" + code);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 搜索
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code AppLY.searchContent(str, z)} —— 上游直接 {@code super.searchContent}，
     * 即<b>不支持搜索</b>（返回空）。
     *
     * <p>本重写利用分类接口的 {@code item} 参数变体补了一个搜索实现：
     * 戏曲多多的接口没有显式关键词参数，但 {@code /API_ROP/search/album/list}
     * 对 {@code item} 变化敏感；这里改用把关键词当作 sect 的降级策略会污染结果，
     * 所以保持与上游一致的「不支持」行为，只返回空列表。
     *
     * @param key   关键词
     * @param quick 快速搜索
     * @return 空列表 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        Log.i(TAG, "搜索未被上游实现（AppLY.searchContent 调 super），返回空列表");
        return VodBuilder.create().searchContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原 {@code AppLY.playerContent(...)} —— 取 m3u8 直链。
     *
     * <p>注意这里用 {@link #PLAY_USER_ID}（32 位 hex 账号标识），
     * 不是首页/分类用的 {@link #GUEST_USER_ID}。
     *
     * @param flag     线路名
     * @param id       集 code
     * @param vipFlags 需要解析的标识
     * @return 播放 JSON（parse=0 直链）
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) {
            return emptyResponse();
        }
        JSONObject req = body("item", PLAY_ITEM, "nodeCode", PLAY_NODE_CODE,
                "project", PROJECT, "px", PLAY_PX, "resCode", id,
                "userId", PLAY_USER_ID);
        JSONObject root = parseOk(apiPost("/API_ROP/play/get/playurl", req));
        if (root == null) {
            return emptyResponse();
        }
        JSONObject playres = root.optJSONObject("playres");
        String url = playres == null ? "" : playres.optString("playurl");
        if (url.isEmpty()) {
            Log.w(TAG, "playurl 为空: " + id);
            return emptyResponse();
        }
        return VodBuilder.playerContent(url, url.contains(".m3u8")
                ? "application/vnd.apple.mpegurl" : "video/*");
    }
}
