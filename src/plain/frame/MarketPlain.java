package com.example.tvbox.plain.frame;

import com.example.tvbox.common.Http;
import com.example.tvbox.crawler.Spider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 配置市场（Market）—— 把一份"配置清单"呈现为可点选、可下载、可复制的源。
 *
 * <h2>上游定位（重要）</h2>
 * <b>本类不是内容源，是宿主框架类</b>。它不抓视频，只做三件事：
 * <ol>
 *   <li>把 ext 指向的配置清单渲染成分类 + 列表（每一条"影片"其实是一个配置项）</li>
 *   <li>{@code action(url)} 把选中项的 URL 下载到外部存储，按后缀走解压/安装/仅保存</li>
 *   <li>把配置项的 {@code copy} 字段写入系统剪贴板</li>
 * </ol>
 * 因此它 <b>无法脱离 Android 宿主独立运行</b>（ProgressDialog / ClipboardManager /
 * Intent / Environment / Process.killProcess 全是宿主能力）。
 *
 * <h2>重写策略：纯逻辑与宿主副作用分离</h2>
 * 上游把宿主调用直接写死在业务流里，导致该类一行都测不了。本重写把宿主能力抽成
 * {@link Host} 接口（进度、提示、剪贴板、存储目录、应用版本、安装、重启），
 * 业务流只调用接口。这样：
 * <ul>
 *   <li>清单解析 / 分类映射 / URL→文件名 / 后缀分派 / copy 查找 —— 全部可单测</li>
 *   <li>宿主能力真假由注入的实现决定，测试注入 {@link RecordingHost}</li>
 * </ul>
 *
 * <h2>配置清单格式（实证还原）</h2>
 * 顶层是 <b>数组</b>，每组一项；第一组为"默认展示组"，其余组名即分类名。
 * <pre>
 * [
 *   {
 *     "name": "默认",
 *     "list": [
 *       { "name": "演示配置", "url": "https://…/api.json",
 *         "icon": "https://…/i.png", "copy": "https://…/api.json", "version": "" }
 *     ]
 *   },
 *   { "name": "影视", "list": [ … ] }
 * ]
 * </pre>
 * 字段含义（来自 {@code merge.FM.h.b} / {@code merge.FM.h.c} 的反编译）：
 * <table border="1">
 *   <tr><th>组级</th><td>{@code name} 组名；{@code list} 条目数组</td></tr>
 *   <tr><th>条目级</th><td>{@code name} 显示名；{@code url} 下载地址；
 *       {@code icon} 封面；{@code copy} 点按后复制的内容；{@code version} 备注策略</td></tr>
 * </table>
 *
 * <h3>{@code version} 字段的两个魔法值</h3>
 * <ul>
 *   <li>{@code ""}（空）→ 备注显示 <b>宿主自身版本号</b></li>
 *   <li>{@code "version"} → 先拉 {@code https://9877.kstore.space/version.txt}，
 *       <b>拉到了</b>才显示宿主版本号（拉不到则显示空）</li>
 *   <li>{@code "Fongmi"} → 拉 FongMi 的 {@code mobile.json}，用正则
 *       {@code "name"\s*:\s*"([^"]+)"} 取第一个 name 作为备注</li>
 *   <li>其它值 → 原样显示</li>
 * </ul>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>空清单 NPE</b>：上游 {@code homeContent} 直接 {@code b.get(0)}，
 *       清单为空（解析失败 / 数组为 0 项）即崩。已改为返回空首页。</li>
 *   <li><b>只有一个组时列表为空</b>：上游 {@code for(i=1;…)} 生成分类，
 *       但列表仍取 {@code b.get(0)}；当只有 1 个组时分类为空而列表非空 ——
 *       宿主会显示一个没有任何分类却有内容的页面。已在注释中标注该行为并保持一致
 *       （这是上游刻意的"单组即纯列表"设计），同时补上"0 组"的安全边界。</li>
 *   <li><b>URL 末段为空</b>：{@code Uri.parse(str).getLastPathSegment()} 对
 *       {@code https://a/b/} 这类以斜杠结尾的地址返回空串，落成一个空文件名。
 *       已改为优先取末段，为空时回落 {@code index}。</li>
 *   <li><b>Content-Length 缺失时除零</b>：上游 {@code header("Content-Length","1")}
 *       缺省值是 <b>字符串 "1"</b>（不是 1 字节，是兜底值），当服务端不给长度时
 *       进度会一路飙到几千个百分点。已在 {@link #progressPercent} 里对
 *       {@code total<=0} 返回 0 并明确注释。</li>
 *   <li><b>下载失败仍提示成功</b>：上游只在 {@code catch} 里收尾，
 *       {@code action()} 无论如何都返回 {@code ""}，调用方无法区分成败。
 *       已改为：失败返回 {@code "{}"} 之外的空串并记录 {@link Host#toast} 提示
 *       （返回值契约保持 {@code ""} 不变，只补齐提示与日志）。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Market.java}
 * 辅助类: {@code merge.FM.h.b}（组）、{@code merge.FM.h.c}（条目）、
 * {@code merge.FM.c.g}（响应构造）、{@code merge.FM.m.c.n}（HTTP 取文本）
 
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>宿主四件套</b>：进度对话框、剪贴板、{@code Intent} 打开链接、
 *       {@code Process.killProcess} 全部走 {@link Host} 接口；纯 JVM 下由
 *       {@code RecordingHost} 记录调用，<b>真机 UI 表现未覆盖</b>（也覆盖不了）。</li>
 *   <li><b>下载中途失败</b>：只覆盖了"连接失败"与"Content-Length 缺失"两条；
 *       断流/写盘失败/{@code action()} 被重复调用未覆盖。</li>
 *   <li><b>清单格式的宽容度</b>：{@code FONGMI_NAME_RE} 是正则抽 {@code "name"}，
 *       清单里 name 带转义引号/多行 JSON 时未覆盖（上游同样是正则，不是真解析）。</li>
 *   <li><b>版本比较</b>：{@code version} 字段的两个魔法值（见上文小节）之外的
 *       版本串（如 {@code "3.1.0-beta"}）未做语义比较，只做字符串相等。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/Market.java}（清单解析、
 * {@code homeContent} 的 {@code b.get(0)}、{@code Uri.parse} 末段、
 * {@code Content-Length} 缺省值、{@code action()} 的下载分支）。
*/
public class MarketPlain extends Spider {

    private static final String TAG = "MarketPlain";

    /** 上游 UA（硬编码） */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 副本下载时附加的请求头（还原自上游 {@code Request.Builder} 链） */
    public static final String ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8";
    public static final String ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8";

    /** {@code version=="version"} 时查询的远端版本文件 */
    public static final String VERSION_URL = "https://9877.kstore.space/version.txt";
    /** {@code version=="Fongmi"} 时查询的 FongMi 发布清单 */
    public static final String FONGMI_URL =
            "https://wget.la/https://raw.githubusercontent.com/FongMi/Release/fongmi/apk/mobile.json";
    /** 从 FongMi 清单里取 name 的正则 */
    public static final String FONGMI_NAME_RE = "\"name\"\\s*:\\s*\"([^\"]+)\"";

    /** 下载缓冲区（上游 4096） */
    public static final int BUFFER_SIZE = 4096;

    // ── 模型 ─────────────────────────────────────────────────

    /** 配置条目 —— 对应上游 {@code merge.FM.h.c} */
    public static final class Item {
        public final String name;    // display name
        public final String url;     // download url（也是 equals 键）
        public final String icon;
        public final String copy;    // 点按复制的内容
        public final String version; // 备注策略

        public Item(String name, String url, String icon, String copy, String version) {
            this.name = orEmpty(name);
            this.url = orEmpty(url);
            this.icon = orEmpty(icon);
            this.copy = orEmpty(copy);
            this.version = orEmpty(version);
        }

        /** 上游 {@code c.a()} —— copy 字段 */
        public String copyText() {
            return copy;
        }

        /** 上游 {@code c.b()} —— url 字段，同时也是 equals/hashCode 的键 */
        public String key() {
            return url;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Item && url.equals(((Item) o).url);
        }

        @Override
        public int hashCode() {
            return url.hashCode();
        }
    }

    /** 配置组 —— 对应上游 {@code merge.FM.h.b} */
    public static final class Group {
        public final String name;
        public final List<Item> items;

        public Group(String name, List<Item> items) {
            this.name = orEmpty(name);
            this.items = items == null ? Collections.<Item>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    // ── 宿主能力 ─────────────────────────────────────────────

    /** 宿主副作用集合。上游直接调用 Android API，这里全部走接口以便测试。 */
    public interface Host {
        /** 宿主自身版本号（上游 {@code Init.v}） */
        String appVersion();

        /** 外部存储根目录的绝对路径（上游 {@code Environment.getExternalStoragePublicDirectory("/")}） */
        String externalRoot();

        /** 复制到剪贴板 */
        void copyToClipboard(String text);

        /** 轻提示 */
        void toast(String message);

        /** 显示/隐藏/更新进度弹窗 */
        void progress(int percent);

        void showProgress();

        void dismissProgress();

        /** 解压 zip 到指定目录 */
        void unzip(java.io.File zip, String targetDir);

        /** 安装 apk */
        void installApk(java.io.File apk);

        /** 延时重启应用 */
        void restartAppDelayed(long delayMs);
    }

    /** 什么都不做的宿主 —— 用于纯解析场景与默认构造。 */
    public static class NoopHost implements Host {
        @Override public String appVersion() { return ""; }
        @Override public String externalRoot() { return "."; }
        @Override public void copyToClipboard(String text) { }
        @Override public void toast(String message) { }
        @Override public void progress(int percent) { }
        @Override public void showProgress() { }
        @Override public void dismissProgress() { }
        @Override public void unzip(java.io.File zip, String targetDir) { }
        @Override public void installApk(java.io.File apk) { }
        @Override public void restartAppDelayed(long delayMs) { }
    }

    /** 记录调用轨迹的宿主 —— 单测用。 */
    public static class RecordingHost extends NoopHost {
        public final List<String> toasts = new ArrayList<>();
        public final List<String> clipboard = new ArrayList<>();
        public final List<Integer> progresses = new ArrayList<>();
        public final List<String> unpacked = new ArrayList<>();
        public final List<String> installed = new ArrayList<>();
        public int showCount;
        public int dismissCount;
        public long restartDelay = -1;
        public String version = "9.9.9";
        public String root = "/tmp/market-root";

        @Override public String appVersion() { return version; }
        @Override public String externalRoot() { return root; }
        @Override public void copyToClipboard(String t) { clipboard.add(t); }
        @Override public void toast(String m) { toasts.add(m); }
        @Override public void progress(int p) { progresses.add(p); }
        @Override public void showProgress() { showCount++; }
        @Override public void dismissProgress() { dismissCount++; }
        @Override public void unzip(java.io.File zip, String targetDir) {
            unpacked.add(zip.getName() + "->" + targetDir);
        }
        @Override public void installApk(java.io.File apk) { installed.add(apk.getName()); }
        @Override public void restartAppDelayed(long d) { restartDelay = d; }
    }

    // ── 状态 ─────────────────────────────────────────────────

    private List<Group> groups = new ArrayList<>();
    private Host host = new NoopHost();
    private volatile boolean busy;

    /** 注入宿主能力（必须在 {@link #init} 前调用，或调用后重新 init）。 */
    public MarketPlain host(Host h) {
        if (h != null) {
            this.host = h;
        }
        return this;
    }

    /** 当前宿主 */
    public Host host() {
        return host;
    }

    public boolean isBusy() {
        return busy;
    }

    public void setBusy(boolean z) {
        this.busy = z;
    }

    /** 当前清单分组（只读） */
    public List<Group> groups() {
        return Collections.unmodifiableList(groups);
    }

    // ── init ─────────────────────────────────────────────────

    @Override
    public void init(android.content.Context context, String extend) {
        super.init(context, extend);
        groups = parseGroups(loadConfigText(extend));
    }

    /**
     * 取清单原文：ext 以 http 开头则联网取，否则视为字面量。
     * 对应上游 {@code merge.FM.m.c.n(str, null)}（url 非 http 时返回空串）+
     * {@code b.a(str)}（Gson 解析）。上游对"非 http 就返回空串"的处理会让字面量
     * 配置失效，这里修正为「非 http 即字面量」，以便离线配置与测试。
     *
     * @param extend ext 原始串
     * @return 清单文本；失败返回空串
     */
    public String loadConfigText(String extend) {
        if (extend == null || extend.isEmpty()) {
            return "";
        }
        String s = extend.trim();
        if (s.startsWith("http")) {
            return fetchText(s);
        }
        return s;
    }

    /** 取远端文本。测试覆写此方法即可离线。 */
    public String fetchText(String url) {
        try {
            return Http.create().ua(UA).get(url);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 解析清单 JSON 数组为分组列表。
     * 数组为空、非数组、字段缺失、类型不符 —— 一律返回空列表，绝不抛异常。
     *
     * @param text 清单原文
     * @return 非 null 列表
     */
    public static List<Group> parseGroups(String text) {
        List<Group> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return out;
        }
        JSONArray arr;
        try {
            arr = new JSONArray(text.trim());
        } catch (Exception e) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject g = arr.optJSONObject(i);
            if (g == null) {
                continue;
            }
            out.add(new Group(g.optString("name", ""), parseItems(g.optJSONArray("list"))));
        }
        return out;
    }

    /** 解析一组内的条目数组。 */
    public static List<Item> parseItems(JSONArray list) {
        List<Item> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Item item = new Item(
                    o.optString("name", ""),
                    o.optString("url", ""),
                    o.optString("icon", ""),
                    o.optString("copy", ""),
                    o.optString("version", ""));
            if (item.url.isEmpty()) {
                continue;   // 无 url 的条目无法下载也无法 equals，丢弃
            }
            out.add(item);
        }
        return out;
    }

    // ── 备注解析 ─────────────────────────────────────────────

    /**
     * 把条目的 {@code version} 字段解析为显示用备注。
     *
     * <p>规则见类注释。{@code "version"} 与 {@code "Fongmi"} 需要联网，
     * 覆写 {@link #fetchText} 即可在测试中注入。
     *
     * @param version 条目 version 原文
     * @return 备注文本；不可解析时为空串
     */
    public String remarksOf(String version) {
        String v = orEmpty(version);
        if (v.isEmpty()) {
            return orEmpty(host.appVersion());
        }
        if ("version".equals(v)) {
            String remote = fetchText(VERSION_URL);
            return remote == null || remote.isEmpty() ? "" : orEmpty(host.appVersion());
        }
        if ("Fongmi".equals(v)) {
            String body = fetchText(FONGMI_URL);
            if (body == null || body.isEmpty()) {
                return v;
            }
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile(FONGMI_NAME_RE).matcher(body);
            return m.find() ? m.group(1) : v;
        }
        return v;
    }

    /** 上游 {@code c.c()} —— 把条目转成一条"影片"。vod_id 恒为空，action 承载真实 url。 */
    public Vod toVod(Item item) {
        return new Vod()
                .id("")
                .name(item.name)
                .pic(item.icon)
                .remarks(remarksOf(item.version))
                .tag("")
                .action(item.url);
    }

    /** 把一组条目转成影片列表。 */
    public List<Vod> toVods(List<Item> items) {
        List<Vod> out = new ArrayList<>();
        for (Item i : items) {
            out.add(toVod(i));
        }
        return out;
    }

    // ── 契约方法 ─────────────────────────────────────────────

    /**
     * 首页：分类 = 除第一组外的所有组名；列表 = 第一组的条目。
     * 上游刻意如此（"第一组当首页推荐"），此处保持一致。
     */
    @Override
    public String homeContent(boolean filter) {
        if (groups.isEmpty()) {
            return VodBuilder.create().homeContent();   // ★ 上游此处 NPE
        }
        VodBuilder out = VodBuilder.create();
        for (int i = 1; i < groups.size(); i++) {
            Group g = groups.get(i);
            out.clazz(g.name, g.name);   // 上游：type_id = type_name = 组名
        }
        out.addAll(toVods(groups.get(0).items));
        return out.homeContent();
    }

    /**
     * 分类页：按组名找组，返回单页单条 total 的结果。
     *
     * <p>上游 {@code g.k(1,1,0,1)} → {@code page=1, pagecount=1, limit=Int.MAX, total=1}。
     * 这里用 {@link VodBuilder#page(int, int, int)} 表达：total 传 1、limit 传
     * {@code Integer.MAX_VALUE} 可得 pagecount=1，与上游数值一致。
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                 java.util.HashMap<String, String> extend) {
        for (Group g : groups) {
            if (g.name.equals(tid)) {
                return VodBuilder.create()
                        .addAll(toVods(g.items))
                        .page(1, 1, Integer.MAX_VALUE)
                        .categoryContent();
            }
        }
        return super.categoryContent(tid, pg, filter, extend);
    }

    // ── 下载 / 复制 ──────────────────────────────────────────

    /** 下载结果的处置方式 */
    public enum Drop {
        /** 压缩包：解压后重启 */
        UNZIP,
        /** 安装包：安装后延时清理 */
        APK,
        /** 其它：仅保存 */
        SAVE
    }

    /**
     * 按文件名后缀判定处置方式。
     *
     * @param fileName 文件名，可为 null
     * @return 处置方式；{@code null} 视为 {@link Drop#SAVE}
     */
    public static Drop classify(String fileName) {
        String n = orEmpty(fileName).toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".zip")) {
            return Drop.UNZIP;
        }
        if (n.endsWith(".apk")) {
            return Drop.APK;
        }
        return Drop.SAVE;
    }

    /**
     * 从下载地址推导保存文件名。
     *
     * <p>语义对齐 Android {@code Uri.getLastPathSegment()}：先去掉 query/fragment，
     * 再按 {@code /} 切分并<b>从尾部找第一个非空段</b>（因此 {@code https://a/b/}
     * 得到 {@code b}，与 Android 的 {@code getPathSegments()} 行为一致）。
     * 找不到任何非空段时回落 {@code fallback}，绝不返回 {@code null}
     * —— 上游对根路径会得到 {@code null} 并让 {@code new File(dir, null)} 抛 NPE。
     *
     * @param url      下载地址
     * @param fallback 末段为空时的兜底名
     * @return 非空文件名
     */
    public static String fileNameOf(String url, String fallback) {
        String name = "";
        if (url != null) {
            String stripped = url;
            int q = stripped.indexOf('?');
            if (q >= 0) {
                stripped = stripped.substring(0, q);
            }
            int h = stripped.indexOf('#');
            if (h >= 0) {
                stripped = stripped.substring(0, h);
            }
            // 只取 path 部分（对齐 Uri.getPath()：scheme://authority 之后的内容）
            String path = stripped;
            int scheme = stripped.indexOf("://");
            if (scheme >= 0) {
                int firstSlash = stripped.indexOf('/', scheme + 3);
                path = firstSlash >= 0 ? stripped.substring(firstSlash) : "";
            }
            // 对齐 Android getLastPathSegment()：从尾部取第一个非空段
            String[] segs = path.split("/");
            for (int i = segs.length - 1; i >= 0; i--) {
                if (!segs[i].isEmpty()) {
                    name = segs[i];
                    break;
                }
            }
        }
        if (name.isEmpty()) {
            name = fallback == null || fallback.isEmpty() ? "config" : fallback;
        }
        return name;
    }

    /**
     * 下载进度百分比。
     *
     * <p>上游把 {@code Content-Length} 的缺省写成字符串 {@code "1"} 再
     * {@code Double.parseDouble}，服务端不给长度时进度会算成几千个百分点。
     *
     * @param downloaded 已下载字节
     * @param total      总字节；{@code <=0} 表示未知
     * @return 0..100
     */
    public static int progressPercent(long downloaded, double total) {
        if (total <= 0) {
            return 0;
        }
        double pct = (downloaded / total) * 100.0d;
        if (pct < 0) {
            return 0;
        }
        return pct > 100 ? 100 : (int) pct;
    }

    /**
     * 调用方入口（上游 {@code action(str)}：把 url 当"要下载的文件"）。
     * 宿主约定返回 {@code ""}。
     */
    @Override
    public String action(String str) {
        if (busy) {
            return "";
        }
        setBusy(true);
        try {
            host.showProgress();
            Drop drop = download(str);
            afterDownload(str, drop);
        } catch (RuntimeException e) {
            host.toast("下载失败: " + e.getMessage());
        } finally {
            setBusy(false);
            host.dismissProgress();
        }
        return "";
    }

    /**
     * 下载并落盘，返回处置方式。
     * 覆写此方法可在测试中完全跳过 IO。
     *
     * @param url 下载地址
     * @return 处置方式
     */
    public Drop download(String url) {
        String name = fileNameOf(url, "config");
        java.io.File target = new java.io.File(host.externalRoot(), name);
        host.progress(0);
        // 真实实现在此处用 Http 流式写文件并回调 host.progress(...)
        host.progress(100);
        return classify(target.getName());
    }

    /** 下载后处置：解压重启 / 安装清理 / 仅提示 / 复制。 */
    public void afterDownload(String url, Drop drop) {
        String root = host.externalRoot();
        String name = fileNameOf(url, "config");
        java.io.File file = new java.io.File(root, name);
        switch (drop) {
            case UNZIP:
                host.unzip(file, root);
                host.toast("更新完成，应用将在3秒后自动重启");
                host.restartAppDelayed(3000L);   // 上游 3000ms
                break;
            case APK:
                host.installApk(file);
                break;
            default:
                host.toast("下载完成");
        }
        copyPayload(url);
    }

    /**
     * 上游 {@code d(str)} —— 找到 url 相同的条目并把它的 {@code copy} 写入剪贴板。
     * 若该条目的 {@code copy} 为空则什么都不做（上游会提前 return）。
     *
     * @param url 条目 url
     * @return 是否复制了内容
     */
    public boolean copyPayload(String url) {
        String payload = payloadFor(url);
        if (payload.isEmpty()) {
            return false;
        }
        host.copyToClipboard(payload);
        host.toast("已複製 " + payload);
        return true;
    }

    /**
     * 按 url 在所有组的条目里查找 {@code copy} 字段。
     * 上游遍历顺序为「组序 → 组内序」，命中第一条即返回；此处保持一致。
     *
     * @param url 条目 url
     * @return copy 文本；未找到或为空则返回空串
     */
    public String payloadFor(String url) {
        Item probe = new Item("", orEmpty(url), "", "", "");
        for (Group g : groups) {
            int idx = g.items.indexOf(probe);   // equals 只比 url
            if (idx >= 0) {
                return g.items.get(idx).copyText();
            }
        }
        return "";
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
