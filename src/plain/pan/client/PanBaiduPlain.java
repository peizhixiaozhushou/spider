package com.example.tvbox.plain.pan.client;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 百度云盘客户端浏览源 —— 浏览<b>用户自己 百度 账号</b>里的文件。
 *
 * <h2>cookie 从哪来（本项目新增的配置入口）</h2>
 * 查找顺序见 {@link PanClientBase#resolveCookie()}，越靠前优先级越高：
 * <ol>
 *   <li>站点配置 {@code ext.cookie} —— 内联整串，<b>导入配置即生效</b></li>
 *   <li>站点配置 {@code ext.cookieFile} —— 绝对路径</li>
 *   <li>{@code -Dtvbox.pan.dir=<dir>} → {@code <dir>/TVBox/baidu.txt}（JSON 取 {@code cookie} 字段）</li>
 *   <li>环境变量 {@code TVBOX_PAN_DIR}（同上）</li>
 *   <li>{@code /sdcard/TVBox/baidu.txt} → {@code /sdcard/TV/baidu.txt}（Android 配置中心的老路径）</li>
 * </ol>
 * 例如（写进 {@code tvbox.json} 的 {@code sites[]}）：
 * <pre>
 * { "key": "csp_PanBaidu", "name": "百度网盘", "type": 3, "api": "csp_PanBaidu",
 *   "ext": {"cookie": "BDUSS=...; STOKEN=..."} }
 * </pre>
 *
 * <h2>已实现的接口（全部来自 DEX 字符串池，逐字）</h2>
 * <table>
 *   <tr><th>用途</th><th>URL</th></tr>
 *   <tr><td>目录列表</td><td>{@code https://pan.baidu.com/api/list?clienttype=0&app_id=250528&web=1
 *       &dp-logid=&order=time&desc=1&dir=<enc>&num=100&page=<p>}</td></tr>
 *   <tr><td>取 bdstoken</td><td>{@code https://pan.baidu.com/api/gettemplatevariable?clienttype=0
 *       &app_id=250528&web=1&fields=["bdstoken","token","uk","isdocuser","servertime"]}</td></tr>
 *   <tr><td>用户信息</td><td>{@code https://pan.baidu.com/rest/2.0/xpan/nas?method=uinfo}（{@code baidu_name}）</td></tr>
 * </table>
 * 这些字面量来自 {@code ae/target/spider_probe.apk} 的 {@code classes.dex}
 * （{@code maintain/evidence/SCOPE_QUARK_BAIDU.md} 记录了提取方式）。
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>扫码登录 / OAuth 整段未实现</b>（上游 {@code PanBaidu.java} 114 行 +
 *       {@code merge.p027b.j} 客户端类）：{@code passport.baidu.com} 的二维码轮询、
 *       {@code BDUSS} 换取、{@code refresh_token} 刷新都不在本类。本类只<b>消费</b>已获得的 cookie。</li>
 *   <li><b>{@code playerContent} 不出直链</b>：上游要「分享 → 转存 → 取 dlink」三步，
 *       其中 dlink 还需要 {@code pan.baidu.com/api/mediainfo} 与签名；本类未还原，
 *       播放时返回空响应（宿主提示失败），<b>不伪造地址</b>。</li>
 *   <li><b>搜索 / 转存 / 秒传 / 删除</b>未实现：DEX 里能看到
 *       {@code /api/search}、{@code /share/transfer} 等字面量，但不掌握完整的
 *       {@code bdstoken} + {@code sekey} 时序，宁缺毋滥。</li>
 *   <li><b>{@code homeContent} 的"软件商店"固定入口</b>未还原（上游 {@code PanBaidu} 没有该入口，
 *       这是夸克侧的写法）。</li>
 *   <li><b>分页总数</b>：{@code /api/list} 不返回总页数，本类按"本页是否满 100 条"决定
 *       是否还有下一页（与上游 {@code Integer.MAX_VALUE} 的写法不同，见下）。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>日期节流用默认 Locale</b>（上游 {@code Pan.java:500-512} 的 {@code bdtime} 缓存）：
 *       {@code SimpleDateFormat("yyyyMMdd")} 不带 Locale ⇒ 泰语区出佛历年份，节流永不生效，
 *       每次详情页都重新转存，容易触发风控。本类不做转存，故无此路径；
 *       需要节流的地方统一走 {@code UCQuarkMaintBase.parseYyyyMMdd}（固定 {@code Locale.US}）。</li>
 *   <li><b>分页写死 {@code Integer.MAX_VALUE}</b>（上游 {@code cVar.i(1, 1, MAX, MAX)}）：
 *       宿主据此画出无限翻页，实际只有一页有数据。→ 本类按"本页条数 &lt; 每页条数"判定末页。</li>
 *   <li><b>凭据判定分散</b>（上游多处 {@code TextUtils.isEmpty(x.j())}）：
 *       → 本类统一经 {@link #reloadCredential()} 更新 {@code loggedIn}，缺失即"未登录"。</li>
 *   <li><b>详情页把"线路名"重复拼成 play_url</b>（上游 {@code PanBaidu.detailContent}）：
 *       线路数与地址数并不相等。→ 本类不拼，交给 {@link PanClientBase#detailContent}。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/PanBaidu.java}（114 行，行为对照）；
 * URL/UA 字面量: {@code ae/target/spider_probe.apk!classes.dex} 字符串池
 * （{@code pan.baidu.com} ×21、{@code bdstoken} ×8、{@code api/list} ×3）。
 */
public class PanBaiduPlain extends PanClientBase {

    private static final String TAG = "PanBaidu";

    /**
     * 请求 UA。
     *
     * <p>⚠️ 归属说明：DEX 字符串池里与百度相关的 UA 只有这一条（Haokan 客户端的 WebView UA），
     * 上游真正的 {@code merge.p027b.j} 客户端类<b>未被反编译</b>，无法确认它用的就是这条。
     * 因此这里标注为"来自 DEX、归属未证实"，并留 {@link #setUaForTest(String)} 便于换值验证。
     */
    public static final String UA =
            "Mozilla/5.0 (Linux; Android 11; M2012K10C Build/RP1A.200720.011; wv) "
          + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/87.0.4280.141 "
          + "Mobile Safari/537.36 haokan/7.80.0.18 (Baidu; P1 11)"
          + "/imoaiX_03_11_C01K2102M/1043677m"
          + "/5ACDB023CFB9D64743B08E51953F7C76%7CVSAJ32AVA/1/7.80.0.18/780001/1"
          + "/immersiveMode/modeV4PlusWhite/isFirstInstall/bbqMode/bbqModeV2"
          + "/blackStyle/isPlaylet Talos/1.8.7";

    /** 请求来源（百度对 Referer 敏感，缺失会返回未登录） */
    public static final String REFERER = "https://pan.baidu.com/disk/main";
    /** Origin */
    public static final String ORIGIN = "https://pan.baidu.com";

    /** 取 bdstoken（DEX 逐字） */
    public static final String URL_TEMPLATE_VAR =
            "https://pan.baidu.com/api/gettemplatevariable?clienttype=0&app_id=250528&web=1"
          + "&fields=[\"bdstoken\",\"token\",\"uk\",\"isdocuser\",\"servertime\"]";

    /** 目录列表前缀（DEX 逐字，末尾补 {@code &dir=...}） */
    public static final String URL_LIST_PREFIX =
            "https://pan.baidu.com/api/list?clienttype=0&app_id=250528&web=1"
          + "&dp-logid=&order=time&desc=1";

    /** 用户信息（DEX 逐字） */
    public static final String URL_UINFO =
            "https://pan.baidu.com/rest/2.0/xpan/nas?method=uinfo";

    /** 每页条数（DEX 的 {@code num=100}） */
    public static final int PAGE_SIZE = 100;

    /** 根目录在百度侧的写法 */
    public static final String ROOT_DIR = "/";

    private String cookie = "";
    private String bdstoken = "";
    private String ua = UA;

    // ─────────────────────────────────────────────────────────────
    // 生命周期 / 登录态
    // ─────────────────────────────────────────────────────────────

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);   // 解析 ext.cookie / ext.cookieFile
        this.accountName = "我的百度网盘";
        reloadCredential();
    }

    /**
     * 读 cookie 并更新登录态。
     *
     * <p>查找链见 {@link PanClientBase#resolveCookie()}。读不到即 {@code loggedIn=false}
     * —— 宿主会显示"还未登录百度账号,请前往【配置中心】登录"（与上游文案一致）。
     */
    public void reloadCredential() {
        String c = resolveCookie();
        this.cookie = c == null ? "" : c.trim();
        this.loggedIn = !TextUtils.isEmpty(this.cookie);
        Log.i(TAG, "cookie origin=" + cookieOrigin + " len=" + this.cookie.length()
                + " loggedIn=" + this.loggedIn);
    }

    /** 注入 cookie（测试 / 手动） */
    public void setCookieForTest(String c) {
        this.cookie = c == null ? "" : c.trim();
        this.loggedIn = !TextUtils.isEmpty(this.cookie);
    }

    /** 注入 bdstoken（测试） */
    public void setBdstokenForTest(String t) {
        this.bdstoken = t == null ? "" : t.trim();
    }

    /** 注入 UA（测试 / 换值验证） */
    public void setUaForTest(String u) {
        this.ua = TextUtils.isEmpty(u) ? UA : u;
    }

    public String cookie() {
        return cookie;
    }

    public String bdstoken() {
        return bdstoken;
    }

    @Override protected String cookieFileName() { return "baidu.txt"; }

    @Override protected String panName() { return "百度"; }

    // ─────────────────────────────────────────────────────────────
    // 请求头（纯函数）
    // ─────────────────────────────────────────────────────────────

    /**
     * 业务请求头。
     *
     * <p>百度要同时带 {@code Cookie} 与 {@code Referer}；缺 Referer 时 {@code /api/list}
     * 会返回 {@code errno: -6}（未登录），这不是配置错误但很容易被误判。
     *
     * @return header map
     */
    public Map<String, String> apiHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", ua);
        h.put("Cookie", cookie == null ? "" : cookie);
        h.put("Referer", REFERER);
        h.put("Origin", ORIGIN);
        h.put("Accept", "application/json, text/plain, */*");
        return h;
    }

    // ─────────────────────────────────────────────────────────────
    // 纯函数：URL / 解析
    // ─────────────────────────────────────────────────────────────

    /**
     * 归一化目录 id —— 宿主可能给 {@code ""} / {@code "0"}（本项目基类的根写法）或 {@code "/"}。
     *
     * @param dir 入参
     * @return 百度侧的目录路径（以 {@code /} 开头）
     */
    public static String normalizeDir(String dir) {
        if (TextUtils.isEmpty(dir) || "0".equals(dir)) {
            return ROOT_DIR;
        }
        return dir.startsWith("/") ? dir : "/" + dir;
    }

    /**
     * 目录列表 URL。
     *
     * @param dir      目录（内部会 {@link #normalizeDir(String)} 并 URL 编码）
     * @param page     页码，&lt;1 回退 1
     * @param bdstoken bdstoken；空则不追加该参数（DEX 里两种形态都有）
     * @return 完整 URL
     */
    public static String buildListUrl(String dir, int page, String bdstoken) {
        int p = page < 1 ? 1 : page;
        StringBuilder sb = new StringBuilder(URL_LIST_PREFIX);
        sb.append("&dir=").append(encode(normalizeDir(dir)));
        sb.append("&num=").append(PAGE_SIZE);
        sb.append("&page=").append(p);
        if (!TextUtils.isEmpty(bdstoken)) {
            sb.append("&bdstoken=").append(encode(bdstoken.trim()));
        }
        return sb.toString();
    }

    /**
     * 解析 {@code /api/list} 响应。
     *
     * <p>响应形态（PCS web 接口）：
     * <pre>
     * {"errno":0,"list":[{"fs_id":123,"path":"/a/b.mp4","server_filename":"b.mp4",
     *                     "isdir":0,"size":12345}]}
     * </pre>
     *
     * @param json 响应文本；null/空/非法一律返回空列表（不抛）
     * @return 目录条目；{@code errno != 0} 时为空
     */
    public static List<DirEntry> parseList(String json) {
        List<DirEntry> out = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return out;
        }
        try {
            JSONObject o = new JSONObject(json);
            int errno = o.optInt("errno", 0);
            if (errno != 0) {
                Log.w(TAG, "list errno=" + errno + " msg=" + o.optString("errmsg", ""));
                return out;
            }
            JSONArray arr = o.optJSONArray("list");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) {
                    continue;
                }
                String name = it.optString("server_filename", "");
                if (TextUtils.isEmpty(name)) {
                    continue;
                }
                DirEntry d = new DirEntry();
                d.name = name;
                d.isDir = it.optInt("isdir", 0) == 1;
                d.size = it.optLong("size", 0);
                d.id = it.optString("path", "");
                if (TextUtils.isEmpty(d.id)) {
                    d.id = name;
                }
                d.updatedAt = it.optString("server_mtime", "");
                d.isVideo = !d.isDir && looksLikeVideo(name);
                out.add(d);
            }
        } catch (Throwable e) {
            Log.e(TAG, "parseList failed", e);
            return new ArrayList<>();
        }
        return out;
    }

    /**
     * 解析 {@code gettemplatevariable} 响应取 {@code bdstoken}。
     *
     * @param json 响应文本
     * @return bdstoken；失败空串
     */
    public static String parseBdstoken(String json) {
        if (TextUtils.isEmpty(json)) {
            return "";
        }
        try {
            JSONObject info = new JSONObject(json).optJSONObject("info");
            if (info == null) {
                return "";
            }
            String t = info.optString("bdstoken", "");
            return t == null ? "" : t.trim();
        } catch (Throwable e) {
            Log.w(TAG, "parseBdstoken failed: " + e);
            return "";
        }
    }

    /**
     * 解析 {@code xpan/nas?method=uinfo} 取昵称。
     *
     * @param json 响应文本
     * @return {@code baidu_name}；失败空串
     */
    public static String parseUserName(String json) {
        if (TextUtils.isEmpty(json)) {
            return "";
        }
        try {
            String n = new JSONObject(json).optString("baidu_name", "");
            return n == null ? "" : n.trim();
        } catch (Throwable e) {
            return "";
        }
    }

    /** URL 参数编码（只用 UTF-8 百分号编码，不依赖宿主工具类） */
    static String encode(String s) {
        if (s == null) {
            return "";
        }
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Throwable e) {
            return s;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 站点实现
    // ─────────────────────────────────────────────────────────────

    /** 首页分类：未登录给提示，已登录给"我的百度网盘"（tid 用百度侧根路径 {@code /}） */
    @Override
    public String homeContent(boolean filter) {
        com.example.tvbox.common.VodBuilder vb = com.example.tvbox.common.VodBuilder.create();
        if (!isLoggedIn()) {
            vb.clazz("0", "还未登录" + panName() + "账号，请前往【配置中心】登录");
        } else {
            vb.clazz(ROOT_DIR, accountName);
        }
        return vb.homeContent();
    }

    /**
     * 列目录 —— 真实调用 {@code /api/list}。
     *
     * <p>未登录直接空；任何异常（网络/解析/风控）都降级为空列表并记日志，
     * <b>不抛出、不伪造条目</b>。
     *
     * @param dirId 目录（空 / {@code 0} / {@code /} 都视为根）
     * @param page  页码
     * @return 条目列表
     */
    @Override
    protected List<DirEntry> listDir(String dirId, int page) {
        List<DirEntry> out = new ArrayList<>();
        if (!isLoggedIn()) {
            return out;
        }
        String dir = normalizeDir(dirId);
        try {
            ensureBdstoken();
            String url = buildListUrl(dir, page, bdstoken);
            String body = http.get(url, apiHeaders());
            out.addAll(parseList(body));
            Log.i(TAG, "list dir=" + dir + " page=" + page + " -> " + out.size());
        } catch (Throwable e) {
            Log.e(TAG, "listDir failed: dir=" + dir + " page=" + page, e);
        }
        return out;
    }

    /** bdstoken 只取一次（失败不缓存，下次重试） */
    private void ensureBdstoken() {
        if (!TextUtils.isEmpty(bdstoken)) {
            return;
        }
        bdstoken = parseBdstoken(http.get(URL_TEMPLATE_VAR, apiHeaders()));
        Log.i(TAG, "bdstoken fetched: " + !TextUtils.isEmpty(bdstoken));
    }
}
