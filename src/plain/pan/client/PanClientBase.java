package com.example.tvbox.plain.pan.client;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.pan.PanPlain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 云盘客户端浏览源基类 —— 浏览<b>用户自己账号</b>里的文件。
 *
 * <h2>与搜索型网盘源的本质区别</h2>
 * <table>
 *   <tr><th>维度</th><th>搜索型（Baiku/KuLe…）</th><th>客户端型（本类）</th></tr>
 *   <tr><td>数据来源</td><td>第三方搜索 API</td><td>用户自己的网盘账号</td></tr>
 *   <tr><td>是否需要登录</td><td>否</td><td><b>是</b>（OAuth / Cookie）</td></tr>
 *   <tr><td>homeContent</td><td>空</td><td>列出根目录 / 快捷入口</td></tr>
 *   <tr><td>categoryContent</td><td>空</td><td>列目录</td></tr>
 *   <tr><td>能否离线可用</td><td>是</td><td>否</td></tr>
 * </table>
 *
 * <h2>登录态来源</h2>
 * 上游通过「配置中心」写入本地文件，文件名见 {@link #cookieFileName()}：
 * <pre>
 *   夸克: quark_cookie.txt      UC:   uc_cookie.txt
 *   阿里: aliyun.txt            百度: baidu.txt
 *   天翼: cloud189.txt          123:  cloud123.txt
 *   迅雷: xunlei.txt            光鸭: guangya.txt
 * </pre>
 *
 * <h2>本实现的范围说明</h2>
 * <b>仅实现框架与降级路径</b>：
 * <ul>
 *   <li>未登录 → 返回提示条目（与上游行为一致）</li>
 *   <li>已登录 → 调用 {@link #listDir} 钩子，由具体网盘子类实现</li>
 * </ul>
 *
 * <p>⚠️ <b>不实现真实 OAuth 流程</b>：扫码登录、token 刷新、签名认证
 * 依赖各家私有协议且频繁变更。上游原 Java 已在线上验证可用，
 * 建议<b>保留原 spider.jar 中的这 9 个类</b>，不要用本类替换。
 * 本类的价值是：提供统一的降级行为与结构，便于将来接入。
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/PanQuark.java} (231行) /
 * {@code PanAli.java} (170行) / {@code PanBaidu.java} (114行) 等
 */
public abstract class PanClientBase extends PanPlain {

    private static final String TAG = "PanClient";

    /** 站点 {@code ext} 里的内联 cookie 字段名 */
    public static final String EXT_COOKIE = "cookie";

    /** 站点 {@code ext} 里的 cookie 文件路径字段名（绝对路径） */
    public static final String EXT_COOKIE_FILE = "cookieFile";

    /** 是否已登录（子类在 init 时根据 cookie 判断） */
    protected boolean loggedIn = false;
    /** 账号显示名（如"我的夸克网盘"） */
    protected String accountName = "";

    /** 站点 {@code ext} 内联的 cookie（最高优先级） */
    protected String extCookie = "";
    /** 站点 {@code ext} 指定的 cookie 文件绝对路径（次高优先级） */
    protected String extCookieFile = "";
    /** 最近一次 {@link #resolveCookie()} 的命中来源，仅用于日志与测试 */
    protected String cookieOrigin = "";

    // ─────────────────────────────────────────────────────────────
    // 生命周期：解析 ext（cookie 配置的第 1/2 层）
    // ─────────────────────────────────────────────────────────────

    /**
     * 初始化 —— 先把站点 {@code ext} 解析成"可用的 cookie 配置"。
     *
     * <p>宿主（FongMi / TVBox）在创建站点实例后调用本方法，{@code extend} 就是配置里
     * 该站点的 {@code ext} 字段<b>原文</b>。本项目只用其中两个键：
     * <pre>
     *   {"cookie": "ctoken=...; __puus=...",            // 内联整串
     *    "cookieFile": "/abs/path/quark_cookie.txt"}   // 或指向文件
     * </pre>
     *
     * <p><b>为什么需要它</b>：桌面宿主（fmplayer.app 的 gateway.jar）跑在 JVM 里，
     * 没有 {@code /sdcard}，靠文件路径拿不到 cookie；而 {@code ext} 是配置 JSON 的一部分，
     * 导入配置即可生效。
     *
     * <p>{@code extend} 不是合法 JSON 对象时<b>不抛异常</b>，按"没有 ext 配置"处理
     * （TVBox 生态里 {@code ext} 也可能是一段纯文本，塞进 JSON 解析必然失败）。
     */
    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        parseExt(extend);
    }

    /**
     * 解析站点 {@code ext} —— 只认 {@link #EXT_COOKIE} / {@link #EXT_COOKIE_FILE}。
     *
     * @param extend ext 原文，可为 null/空/非 JSON
     */
    protected void parseExt(String extend) {
        extCookie = "";
        extCookieFile = "";
        if (TextUtils.isEmpty(extend)) {
            return;
        }
        try {
            JSONObject o = new JSONObject(extend);
            extCookie = trim(o.optString(EXT_COOKIE, ""));
            extCookieFile = trim(o.optString(EXT_COOKIE_FILE, ""));
        } catch (Throwable e) {
            // 不是 JSON 对象 ⇒ 没有可用配置（不是错误，宿主里很常见）
            Log.i(TAG, "ext is not a JSON object, ignored (len=" + extend.length() + ")");
        }
        if (!TextUtils.isEmpty(extCookie) || !TextUtils.isEmpty(extCookieFile)) {
            Log.i(TAG, "ext cookie config: inline=" + (!TextUtils.isEmpty(extCookie))
                    + " file=" + extCookieFile);
        }
    }

    /**
     * 统一凭据解析 —— cookie 配置的 4 层查找，<b>先命中即返回</b>：
     * <ol>
     *   <li>{@code ext.cookie} 内联整串</li>
     *   <li>{@code ext.cookieFile} 指向的文件（JSON 里取 cookie 字段，纯文本整份用）</li>
     *   <li>{@link PanCreds#candidatePaths(String)} 的目录查找：显式配置 → {@code -Dtvbox.pan.dir}
     *       → {@code TVBOX_PAN_DIR} → {@code /sdcard} → 宿主数据目录（{@link PanCreds#hostRoot()}）</li>
     *   <li>都没有 ⇒ {@code ""}（未登录）</li>
     * </ol>
     *
     * <p>来源写入 {@link #cookieOrigin}：{@code "ext.cookie"} / {@code "ext.cookieFile"} /
     * {@code "file:<命中的绝对路径>"} / {@code "none"}。带上完整路径是刻意的 ——
     * "到底读的哪个文件"是排查网盘登录态最常问的问题。
     *
     * @return cookie 文本（已 trim）；永远非 null
     */
    protected String resolveCookie() {
        if (!TextUtils.isEmpty(extCookie)) {
            cookieOrigin = "ext.cookie";
            return extCookie;
        }
        if (!TextUtils.isEmpty(extCookieFile)) {
            String fromFile = readCookieFile(extCookieFile);
            if (!TextUtils.isEmpty(fromFile)) {
                cookieOrigin = "ext.cookieFile";
                return fromFile;
            }
            Log.w(TAG, "ext.cookieFile 读不到内容: " + extCookieFile);
        }
        for (String p : PanCreds.candidatePaths(cookieFileName())) {
            String v = readCookieFile(p);
            if (!TextUtils.isEmpty(v)) {
                cookieOrigin = "file:" + p;
                return v;
            }
        }
        cookieOrigin = "none";
        return "";
    }

    /**
     * 读一个"cookie 文件"：先按裸文本整份取；整份内容像 JSON 时取其中的 cookie 字段。
     *
     * <p>两种写法都能用：
     * <ul>
     *   <li>直接粘 cookie 串的纯文本文件 → 整份返回（trim 后）</li>
     *   <li>{@code {"cookie":"...","nickname":"..."}} 这种（上游配置中心写的就是 JSON）
     *       → 取 {@code cookie}/{@code Cookie}/{@code __pus}/{@code ctoken} 里第一个非空值</li>
     * </ul>
     *
     * <p>⚠️ 若文件<b>是合法 JSON 但没有上述字段</b>，返回空串而不是把整段 JSON 当 cookie ——
     * 否则 {@code {"nickname":"x"}} 会被当成 cookie 发出去，登录态"看起来有"其实无效。
     *
     * @param path 绝对路径
     * @return 内容；读不到/不是凭据文件返回空串
     */
    public static String readCookieFile(String path) {
        String raw = trim(PanCreds.readFile(path));
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        if (raw.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(raw);
                for (String k : new String[]{"cookie", "Cookie", "__pus", "ctoken"}) {
                    String v = trim(o.optString(k, ""));
                    if (!TextUtils.isEmpty(v)) {
                        return v;
                    }
                }
                // 合法 JSON 但没有 cookie 字段 ⇒ 这不是凭据文件
                Log.w(TAG, "cookie file has no cookie field, ignored: " + path);
                return "";
            } catch (Throwable e) {
                Log.w(TAG, "cookie file is not valid JSON, use raw text: " + path);
            }
        }
        return raw;
    }

    /** 空安全 trim */
    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    // ─────────────────────────────────────────────────────────────
    // 子类必须实现
    // ─────────────────────────────────────────────────────────────

    /** 该网盘的 cookie 文件名，如 {@code "quark_cookie.txt"} */
    protected abstract String cookieFileName();

    /** 网盘中文名，如 {@code "夸克"} */
    protected abstract String panName();

    /**
     * 列出目录内容。
     *
     * @param dirId    目录 ID（根目录通常为 "0" 或 ""）
     * @param page     页码，从 1 开始
     * @return 文件/目录条目；未实现返回空列表
     */
    protected List<DirEntry> listDir(String dirId, int page) {
        // 默认未实现 —— 子类覆写
        return new ArrayList<>();
    }

    /** 目录条目 */
    public static class DirEntry {
        public String id = "";
        public String name = "";
        public boolean isDir = false;
        public long size = 0;
        public String updatedAt = "";
        /** 是否为视频文件（决定能否播放） */
        public boolean isVideo = false;
        /**
         * 分享场景下的 {@code share_fid_token}。
         *
         * <p>UC / 夸克 转存接口要求同时给 {@code fid} 与 {@code share_fid_token}，
         * 缺 token 会被拒；普通目录浏览用不到该字段。
         */
        public String token = "";
    }

    // ─────────────────────────────────────────────────────────────
    // 登录态
    // ─────────────────────────────────────────────────────────────

    /**
     * 检查登录态。
     * <p>上游通过 {@code TextUtils.isEmpty(api.j())} 判断；
     * 本实现由子类在 init 时设置 {@link #loggedIn}。
     */
    public boolean isLoggedIn() {
        return loggedIn;
    }

    /**
     * 最近一次 {@link #resolveCookie()} 的命中来源（诊断/测试用）。
     *
     * @return {@code "ext.cookie"} / {@code "ext.cookieFile"} /
     *         {@code "file:explicit|system-property|env|default"} / {@code "none"}
     */
    public String cookieOrigin() {
        return cookieOrigin;
    }

    /** 站点 ext 里的内联 cookie（空串=未配置） */
    public String extCookie() {
        return extCookie;
    }

    /** 站点 ext 里指定的 cookie 文件路径（空串=未配置） */
    public String extCookieFile() {
        return extCookieFile;
    }

    // ─────────────────────────────────────────────────────────────
    // 站点实现
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页 —— 还原上游 {@code homeContent} 的结构：
     * 未登录时给一条提示，已登录时给"我的网盘"入口。
     *
     * <pre>
     * 上游原文（PanQuark）:
     *   if (api == null || TextUtils.isEmpty(api.j()))
     *       classList.add(new Class("0", "还未登录夸克账号,请前往【配置中心】登录", "1"));
     *   else
     *       for (folders) classList.add(new Class(folder, name, "1"));
     * </pre>
     */
    @Override
    public String homeContent(boolean filter) {
        VodBuilder vb = VodBuilder.create();
        if (!isLoggedIn()) {
            vb.clazz("0", "还未登录" + panName() + "账号，请前往【配置中心】登录");
        } else {
            vb.clazz("0", TextUtils.isEmpty(accountName) ? "我的" + panName() + "网盘" : accountName);
        }
        return vb.homeContent();
    }

    /** 分类浏览 —— 列目录 */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);

        if (!isLoggedIn()) {
            Log.w(TAG, "categoryContent: not logged in (" + panName() + ")");
            return VodBuilder.create().page(page).categoryContent();
        }

        List<DirEntry> entries;
        try {
            entries = listDir(tid, page);
        } catch (Exception e) {
            Log.e(TAG, "listDir failed: " + tid, e);
            return VodBuilder.create().page(page).categoryContent();
        }
        if (entries == null) entries = new ArrayList<>();

        List<Vod> list = new ArrayList<>();
        for (DirEntry d : entries) {
            if (d == null || TextUtils.isEmpty(d.name)) continue;
            list.add(new Vod()
                    .id(buildChildId(tid, d))
                    .name(d.name)
                    .pic(iconFor(d.isDir))
                    .remarks(d.isDir ? "文件夹" : humanSize(d.size)));
        }

        Log.i(TAG, panName() + " dir=" + tid + " page=" + page + " -> " + list.size());
        return VodBuilder.create().addAll(list).page(page).categoryContent();
    }

    /**
     * 详情 —— 云盘文件无需"详情页"，直接构造播放信息。
     * <p>覆盖基类 {@code PanPlain.detailContent}（那是给分享链接用的）。
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (id.isEmpty()) return emptyResponse();

        Vod v = new Vod()
                .id(id)
                .name(extractName(id))
                .playFrom(panName())
                .playUrl("播放$" + id);
        return VodBuilder.detail(v).detailContent();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        // 云盘客户端源不做搜索
        return VodBuilder.create().searchContent();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id) || !loggedIn) return emptyResponse();
        // 真实播放需要换取直链（各家 API 不同），此处返回失败让宿主提示
        Log.w(TAG, "playerContent: direct link acquisition not implemented for " + panName());
        return emptyResponse();
    }

    // ─────────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────────

    /** 子条目 ID = 父路径 + "/" + 子名（与上游一致） */
    protected String buildChildId(String parent, DirEntry d) {
        String p = parent == null ? "" : parent;
        if (!p.endsWith("/")) p = p + "/";
        return p + d.name;
    }

    /** 从路径取文件名 */
    public String extractName(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i >= 0 && i < path.length() - 1 ? path.substring(i + 1) : path;
    }

    /** 目录/文件图标（上游用固定 URL，此处保留同类语义） */
    protected String iconFor(boolean isDir) {
        return isDir
                ? "https://images.cnblogs.com/cnblogs_com/blogs/815326/galleries/2404886/o_240619005852_f.png"
                : "https://images.cnblogs.com/cnblogs_com/blogs/815326/galleries/2404886/o_240619005852_v.png";
    }

    /** 人类可读大小 */
    public static String humanSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024);
        return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }

    /** 常用视频扩展名判断 */
    public static boolean looksLikeVideo(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        for (String ext : new String[]{".mp4", ".mkv", ".avi", ".mov", ".wmv",
                                       ".flv", ".ts", ".m3u8", ".rmvb", ".webm"}) {
            if (n.endsWith(ext)) return true;
        }
        return false;
    }
}
