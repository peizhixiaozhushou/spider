package com.example.tvbox.plain.amu;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阿木哔哩 V2（{@code csp_AmuBiliV2}）明文实现 —— B 站 cookie / WBI 密钥刷新器。
 *
 * <p>反编译原文: {@code ae/decompiled/AmuBiliV2.java}（69 行，4 个方法）。
 *
 * <h2>职责</h2>
 * 上游 {@code AmuBiliV2} 本身<b>没有内容抓取逻辑</b>：它只做一件事 ——
 * 用一份 cookie 请求 {@code /x/web-interface/nav}，把登录态 + WBI 图片密钥
 * 落盘成 {@code bili_cookie.txt}，供同工程的 {@code Bili} 读取。
 * 三个可调用入口：
 * <ul>
 *   <li>{@code b()}（私有）—— 构造请求头（UA / cookie / Referer）</li>
 *   <li>{@code getCache()} —— 返回缓存文件句柄 {@code k.b("/bili_cookie.txt")}</li>
 *   <li>{@code refreshUser(cookie)} —— 刷 cookie 主流程，返回是否登录成功</li>
 * </ul>
 *
 * <h2>协议（依据反编译逐字还原）</h2>
 * <pre>
 * GET https://api.bilibili.com/x/web-interface/nav
 *   User-Agent: Chrome/151.0.0.0（逐字保留）
 *   cookie:     &lt;ext/文件/内置默认&gt;
 *   Referer:    https://www.bilibili.com
 * 响应 {"code":0,"data":{"isLogin":true,"mid":...,"uname":...,
 *        "wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/&lt;IMG_KEY&gt;.png",
 *                   "sub_url":"https://i0.hdslb.com/bfs/wbi/&lt;SUB_KEY&gt;.png"}}}
 * 落盘  {"cookie":"&lt;原样&gt;"}  → bili_cookie.txt
 * </pre>
 *
 * <h2>混淆 DTO 到真实字段的映射（推断依据写在此处，勿改动结论前先看证据）</h2>
 * 上游通过混淆 DTO {@code merge.p031f.b} 的短方法名取值，方法名无语义。
 * 本实现按「调用点语义 + B 站 nav 接口真实响应」还原：
 * <table border="1">
 *   <tr><th>上游调用</th><th>本实现</th><th>判定依据</th></tr>
 *   <tr><td>{@code gVar.h(str)}</td><td>cookie 字段</td>
 *       <td>入参就是 cookie 串本身（AmuBiliV2.java:55）</td></tr>
 *   <tr><td>{@code bVarA.s()} → {@code gVar.k(...)}</td><td>isVip</td>
 *       <td>Bili.detailContent 用它门禁 {@code qn<=80}（1080P），
 *           qn&gt;80 需大会员（Bili.java:252）</td></tr>
 *   <tr><td>{@code bVarA.m13k()}（即 {@code k()}）</td><td>isLogin</td>
 *       <td>同上，门禁 {@code qn<=32}（480P），未登录只能到 480P</td></tr>
 *   <tr><td>{@code bVarA.p()} / {@code bVarA.i()}</td><td>mid / uname</td>
 *       <td>nav 的 {@code data} 中与「会话身份」相关的正是 mid、uname；
 *           {@code gVar.g()} 作为 {@code refreshUser} 返回值 → isLogin</td></tr>
 *   <tr><td>{@code f.l(url,"wbi/",".")}</td><td>{@link #wbiKeyFromUrl(String)}</td>
 *       <td>取 {@code wbi/} 与 {@code .} 之间的片段，即 WBI img/sub key</td></tr>
 * </table>
 * <p><b>不确定性</b>：{@code p()/i()} 到底是 mid 还是 uname 无法从反编译静态确定
 * （两者都是字符串）；本实现按 nav 字段名取 {@code mid}/{@code uname}，
 * 即便互换也不影响 {@link #refreshUser(String)} 的布尔结果与 WBI 密钥提取。
 *
 * <h2>与上游的差异（均有依据，非随手改）</h2>
 * <ol>
 *   <li>上游用 Android {@code android.util.Base64} + okhttp + {@code Init.execute(Runnable)}
 *       落盘；本实现改用 {@link Http}（可被测试替换）与本地文件写。
 *       {@code Init} 类属于宿主私有设施（未重写），落盘动作改为
 *       {@link #persistSession(NavInfo)} 这一<b>可覆写</b>方法，便于接入宿主。</li>
 *   <li>上游读缓存用 {@code k.a(k.b("/bili_cookie.txt"))}（混淆 lib 的文件读写）；
 *       本实现用 {@link #loadCookieFromCache()}，路径可通过
 *       {@link #setCacheDirForTest(String)} 注入，测试零网络零磁盘副作用。</li>
 *   <li>上游 {@code refreshUser} 的 catch 只 {@code printStackTrace()}；
 *       本实现保留行为并补一条 warn 日志。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/AmuBiliV2.java}
 * 接口清单:   {@code out/sources/spider_interfaces.json} → {@code csp_AmuBiliV2}
 *             （只含 {@code https://www.bilibili.com} 与 {@code .../nav} 两条，
 *              印证本类确无其他网络端点）

 * <h2>未覆盖的分支</h2>
 * <ul>
 *   <li><b>宿主异步落盘壳未复现</b>：上游用 {@code Init.execute(Runnable)}
 *       （宿主私有设施）异步写会话文件；本实现是同步写，且把它做成可覆写的
 *       {@code persistSession(NavInfo)}。影响：真实宿主里"写盘与后续请求的时序"可能不同。</li>
 *   <li><b>真实站点抓包未做</b>：{@code vipType} 兜底那条分支
 *       （{@code vipStatus==0 && vipType>0}）是按"部分版本 nav 这么给"推断的，
 *       零网络下无法验证。同族证据：{@code BiliPlain} / {@code BiliBase} 已与本类对齐到同一条规则。</li>
 *   <li><b>混淆 DTO 的字段映射是推断</b>：见上文"混淆 DTO 到真实字段的映射"一节，
 *       每个映射都写了依据，但没有一条能靠静态产物 100% 确认。</li>
 * </ul>
 */
public class AmuBiliV2Plain extends BaseSpider {

    private static final String TAG = "AmuBiliV2Plain";

    /** 上游逐字保留的 UA（AmuBiliV2.java:23） */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** nav 接口（AmuBiliV2.java:53） */
    public static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";

    /** Referer（AmuBiliV2.java:41） */
    public static final String REFERER = "https://www.bilibili.com";

    /** 缓存文件名（上游 {@code k.b("/bili_cookie.txt")}） */
    public static final String CACHE_NAME = "bili_cookie.txt";

    /**
     * 内置默认 cookie（AmuBiliV2.java:38 逐字保留，含 {@code %3D%3D} 转义与
     * 过期了的 {@code bili_ticket}，属上游「开箱可用但会过期」的兜底值）。
     */
    public static final String DEFAULT_COOKIE =
            "buvid3=9AB9323A-F21B-1A49-93F8-ABBE7738F82787336infoc; b_nut=1730031187;"
                    + " b_lsid=C5DE8ADD_192CDE4F974;"
                    + " _uuid=39F105341-5ABB-297C-E8AC-193910249F69488356infoc;"
                    + " buvid4=3AAE7EBA-A505-6B5F-153E-EEFE17AA68A888888-024102712-wN48cfQQcbzwQQDk0fiimQ%3D%3D;"
                    + " buvid_fp=799be2784f8761d26ecab89ab3c5982f;"
                    + " bili_ticket=eyJhbGciOiJIUzI1NiIsImtpZCI6InMwMyIsInR5cCI6IkpXVCJ9."
                    + "eyJleHAiOjE3MzAyOTAzODksImlhdCI6MTczMDAzMTEyOSwicGx0IjotMX0."
                    + "XzvRhDap3hcbkoNoOeK8lkWjH_SAUCXk8N0JtAfxGhE;"
                    + " bili_ticket_expires=1730290329";

    /** 测试注入的缓存目录（null 表示走默认解析） */
    private String cacheDirOverride;

    /** 首字母大写的类名，用于拼缓存 key（上游 {@code this.G}，此处只需稳定标识） */
    private String sessionKey = "AmuBiliV2";

    // ─────────────────────────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────────────────────────

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        // 上游 init 为空（继承 Spider）；这里只把 http 的 UA 对齐上游常量
        this.http = Http.create().ua(UA);
    }

    // ─────────────────────────────────────────────────────────────
    // 请求头（还原上游 b()）
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游静态方法 {@code b()}：UA + cookie + Referer。
     *
     * <p>cookie 选择顺序与上游一致：
     * 「缓存文件里的 cookie」→ 若为空则用 {@link #DEFAULT_COOKIE}。
     * 找不到文件或 JSON 非法时不抛异常（上游 catch 后走默认 cookie）。
     *
     * @return 新的 header map（可变，调用方可继续 put）
     */
    public Map<String, String> apiHeaders() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        map.put("User-Agent", UA);
        String cookie = loadCookieFromCache();
        if (TextUtils.isEmpty(cookie)) {
            cookie = DEFAULT_COOKIE;
            Log.d(TAG, "using built-in default cookie");
        }
        map.put("cookie", cookie);
        map.put("Referer", REFERER);
        return map;
    }

    /**
     * 指定 cookie 的请求头（还原上游 {@code refreshUser} 里
     * {@code mapB.put("cookie", str)} 的覆盖动作）。
     *
     * @param cookie cookie 串，null/空则退回 {@link #DEFAULT_COOKIE}
     * @return 新的 header map
     */
    public Map<String, String> apiHeaders(String cookie) {
        Map<String, String> map = apiHeaders();
        map.put("cookie", TextUtils.isEmpty(cookie) ? DEFAULT_COOKIE : cookie);
        return map;
    }

    // ─────────────────────────────────────────────────────────────
    // 缓存文件（还原 k.b / k.a / k.d）
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code getCache()}：{@code k.b("/bili_cookie.txt")} → 缓存文件句柄。
     *
     * @return 缓存文件（不保证存在）
     */
    public File getCache() {
        return new File(cacheDir(), CACHE_NAME);
    }

    /**
     * 缓存目录。优先用 {@code Context.getFilesDir()}（宿主真实环境），
     * 取不到时回落到系统临时目录（桌面 JVM / 测试环境）。
     *
     * @return 目录路径，永不为 null
     */
    public String cacheDir() {
        if (cacheDirOverride != null) {
            return cacheDirOverride;
        }
        try {
            if (mContext != null) {
                File dir = mContext.getFilesDir();
                if (dir != null) {
                    return dir.getAbsolutePath();
                }
            }
        } catch (Throwable ignore) {
            // stub / 无权限，走临时目录
        }
        return System.getProperty("java.io.tmpdir", ".");
    }

    /**
     * 注入缓存目录（测试用，避免真写宿主目录）。
     *
     * @param dir 目录路径；null 表示恢复默认解析
     */
    public void setCacheDirForTest(String dir) {
        this.cacheDirOverride = dir;
    }

    /**
     * 还原上游 {@code k.a(k.b("/bili_cookie.txt"))}：读文件并取 JSON 的
     * {@code cookie} 字段。
     *
     * @return cookie；文件不存在 / 内容非法 / cookie 为空 → 空串（永不抛）
     */
    public String loadCookieFromCache() {
        File f = getCache();
        if (f == null || !f.isFile()) {
            return "";
        }
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[4096];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, Charset.forName("UTF-8")));
            }
            return new JSONObject(sb.toString()).optString("cookie");
        } catch (Throwable e) {
            Log.w(TAG, "read " + CACHE_NAME + " failed: " + e.getMessage());
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignore) {
                    // 关闭失败无需处理
                }
            }
        }
    }

    /**
     * 落盘会话（还原上游 {@code Init.execute(new r(gVar, getCache(), 2))}
     * 里「把会话写进 {@code bili_cookie.txt}」这一步）。
     *
     * <p>上游把写盘交给宿主 {@code Init}（未重写的私有设施）；本实现用
     * {@code {"cookie":"..."}} 结构写同一份文件，保持与 {@link #loadCookieFromCache()}
     * 及 {@code Bili} 的读取端兼容。
     *
     * @param info 会话信息，可为 null（直接返回 false）
     * @return 是否写入成功
     */
    public boolean persistSession(NavInfo info) {
        if (info == null || TextUtils.isEmpty(info.cookie)) {
            return false;
        }
        File f = getCache();
        FileOutputStream out = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            JSONObject o = new JSONObject();
            o.put("cookie", info.cookie);
            o.put("mid", info.mid);
            o.put("isLogin", info.isLogin);
            o.put("isVip", info.isVip);
            o.put("wbiImgKey", info.wbiImgKey);
            o.put("wbiSubKey", info.wbiSubKey);
            out = new FileOutputStream(f);
            out.write(o.toString().getBytes(Charset.forName("UTF-8")));
            out.flush();
            Log.d(TAG, "session persisted to " + f.getName());
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "persist session failed: " + e.getMessage());
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignore) {
                    // 关闭失败无需处理
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 网络（可覆写，测试期不联网）
    // ─────────────────────────────────────────────────────────────

    /**
     * 单次 GET，返回响应体。测试可覆写此方法（或子类化）以避免真实网络。
     *
     * @param url     完整 URL
     * @param headers 请求头
     * @return 响应体；失败空串
     */
    public String fetch(String url, Map<String, String> headers) {
        return http.get(url, headers);
    }

    /**
     * 带 body 的请求。本类没有 POST 路径，保留该 seam 是为了与
     * 工程内其它 Plain 类的「可覆写 fetch 家族」约定一致。
     *
     * @param url     完整 URL
     * @param body    请求体
     * @param headers 请求头
     * @return 响应体；失败空串
     */
    public String fetchWith(String url, String body, Map<String, String> headers) {
        return http.post(url, body, headers);
    }

    // ─────────────────────────────────────────────────────────────
    // nav 解析
    // ─────────────────────────────────────────────────────────────

    /**
     * 会话信息（还原上游 {@code merge.p031f.g} 会话 bean 的字段集合）。
     */
    public static final class NavInfo {
        /** 写回缓存的原始 cookie */
        public String cookie = "";
        /** 是否已登录（上游 {@code gVar.g()} 的返回值） */
        public boolean isLogin;
        /** 是否大会员（门禁 qn&gt;80） */
        public boolean isVip;
        /** 用户 mid（字符串化；nav 里是数字） */
        public String mid = "";
        /** 用户名 */
        public String uname = "";
        /** 头像 */
        public String face = "";
        /** WBI img key（{@code wbi/<key>.png} 的 key 段） */
        public String wbiImgKey = "";
        /** WBI sub key */
        public String wbiSubKey = "";
        /** 原始 wbi_img.img_url（保留供排查） */
        public String wbiImgUrl = "";
        /** 原始 wbi_img.sub_url */
        public String wbiSubUrl = "";

        @Override
        public String toString() {
            return "NavInfo{login=" + isLogin + ", vip=" + isVip + ", mid=" + mid
                    + ", imgKey=" + wbiImgKey + ", subKey=" + wbiSubKey + "}";
        }
    }

    /**
     * 还原上游 {@code f.l(url, "wbi/", ".")}：取 {@code begin} 与 {@code end} 之间
     * 的第一段子串。
     *
     * <p>上游实现：{@code ("#" + url + "#").replaceAll(".*" + begin + "\\$([^#]+?)#.*", "$1")}
     * 的等价形态（{@code f.l}）。本实现用 indexOf 手工切，避免正则回溯。
     *
     * @param url   完整 URL，可为 null
     * @param begin 起始标记（如 {@code "wbi/"}）
     * @param end   结束标记（如 {@code "."}）
     * @return 中间片段；找不到任一侧 → 空串
     */
    public static String between(String url, String begin, String end) {
        if (url == null || begin == null || end == null) {
            return "";
        }
        int b = url.indexOf(begin);
        if (b < 0) {
            return "";
        }
        int from = b + begin.length();
        int e = url.indexOf(end, from);
        if (e < 0) {
            return "";
        }
        return url.substring(from, e);
    }

    /**
     * 从 wbi 图片 URL 提取 key（{@code wbi/<KEY>.png} → {@code KEY}）。
     *
     * @param url 形如 {@code https://i0.hdslb.com/bfs/wbi/7cd0....png}
     * @return key；解析失败空串
     */
    public static String wbiKeyFromUrl(String url) {
        return between(url, "wbi/", ".");
    }

    /**
     * 解析 nav 响应为 {@link NavInfo}（还原上游 {@code bVarA} 系列取值）。
     *
     * @param json nav 响应体，可为 null/非法（则返回空 NavInfo，不抛）
     * @param cookie 本次请求使用的 cookie，写回 info.cookie
     * @return 会话信息，永不为 null
     */
    public static NavInfo parseNav(String json, String cookie) {
        NavInfo info = new NavInfo();
        info.cookie = cookie == null ? "" : cookie;
        if (json == null || json.isEmpty()) {
            return info;
        }
        JSONObject data;
        try {
            JSONObject root = new JSONObject(json);
            data = root.optJSONObject("data");
        } catch (Throwable e) {
            return info;
        }
        if (data == null) {
            return info;
        }
        info.isLogin = data.optBoolean("isLogin", false);
        // nav 用 vipStatus（0=无 1=大会员），部分版本给 vipType
        info.isVip = data.optInt("vipStatus", 0) == 1 || data.optInt("vipType", 0) > 0;
        info.mid = data.optString("mid", "");
        info.uname = data.optString("uname", "");
        info.face = data.optString("face", "");
        JSONObject wbi = data.optJSONObject("wbi_img");
        if (wbi != null) {
            info.wbiImgUrl = wbi.optString("img_url", "");
            info.wbiSubUrl = wbi.optString("sub_url", "");
            info.wbiImgKey = wbiKeyFromUrl(info.wbiImgUrl);
            info.wbiSubKey = wbiKeyFromUrl(info.wbiSubUrl);
        }
        return info;
    }

    /**
     * 仅取 isLogin（还原上游 {@code gVar.g()} 语义）。
     *
     * @param json nav 响应体
     * @return 是否登录；解析失败 false
     */
    public static boolean isLogin(String json) {
        return parseNav(json, "").isLogin;
    }

    /**
     * 仅取 WBI 拼接键 {@code imgKey + subKey}（供 {@code Bili} 计算 mixinKey）。
     *
     * @param json nav 响应体
     * @return 64 字符拼接键；取不到则空串
     */
    public static String wbiConcatKey(String json) {
        NavInfo info = parseNav(json, "");
        if (info.wbiImgKey.isEmpty() || info.wbiSubKey.isEmpty()) {
            return "";
        }
        return info.wbiImgKey + info.wbiSubKey;
    }

    // ─────────────────────────────────────────────────────────────
    // 主流程（还原 refreshUser）
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code refreshUser(String)}
     * （AmuBiliV2.java:49-68）。
     *
     * <p>流程：用给定 cookie 请求 nav → 解析登录态与 WBI 密钥 →
     * {@link #persistSession(NavInfo)} 落盘 → 返回登录态。
     *
     * <p>上游的 {@code Init.execute(...)} 落盘 + {@code merge.p031f.r} 任务壳
     * 属宿主私有设施；本实现改为同步落盘（语义等价、无异步竞态），
     * 并在落盘失败时只记日志、不影响返回值（与上游一致：返回值取自会话 bean）。
     *
     * @param cookie 待刷新的 cookie
     * @return 是否登录成功；任何异常返回 false（上游同）
     */
    public boolean refreshUser(String cookie) {
        if (TextUtils.isEmpty(cookie)) {
            Log.w(TAG, "refreshUser with empty cookie");
            return false;
        }
        try {
            Map<String, String> headers = apiHeaders(cookie);
            String body = fetch(NAV_URL, headers);
            NavInfo info = parseNav(body, cookie);
            persistSession(info);
            Log.i(TAG, "refreshUser → " + info);
            return info.isLogin;
        } catch (Throwable e) {
            Log.w(TAG, "refreshUser failed: " + e);
            return false;
        }
    }

    /**
     * 当前会话标识（上游 {@code this.G}，用于给其它模块拼缓存 key）。
     *
     * @return 标识串
     */
    public String sessionKey() {
        return sessionKey;
    }

    /**
     * 覆盖会话标识（测试 / 多站点隔离用）。
     *
     * @param key 新标识
     */
    public void setSessionKey(String key) {
        this.sessionKey = key == null ? "" : key;
    }
}
