package com.example.tvbox.plain.frame;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.JsonKeys;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置中心（Config）—— <b>★框架支撑类，非内容源★</b>。
 *
 * <p><b>框架支撑类，非内容源</b> —— 不提供 {@code homeContent} 内容数据，仅供框架初始化/配置加载使用。
 * 本类不抓任何影视数据、不解析任何站点响应；它只做三件事：
 * <ol>
 *   <li>把宿主设置面板需要的 <b>配置分类菜单</b> 渲染出来（{@link #homeContent(boolean)}）；</li>
 *   <li>把某个分类下的 <b>设置项</b> 渲染成可点按条目（{@link #categoryContent}），
 *       每条的 {@code vod_id} 是一个"动作 id"（{@code 200} / {@code c400} / {@code recovery} …）；</li>
 *   <li>收到动作 id 后执行 <b>配置副作用</b>：清登录态文件 / 清弹幕缓存 / 备份配置 /
 *       打开对应设置页（{@link #dispatch(String)}，由 {@link #detailContent} 与 {@link #action} 触发）。</li>
 * </ol>
 *
 * <h2>与普通内容源的区别（请勿误用）</h2>
 * <table border="1">
 *   <tr><th>方法</th><th>普通内容源</th><th>本类</th></tr>
 *   <tr><td>{@code homeContent}</td><td>影视分类 + 推荐列表</td>
 *       <td><b>配置分类菜单</b>（11 个设置分区），{@code list} 恒为空</td></tr>
 *   <tr><td>{@code categoryContent}</td><td>影视条目</td>
 *       <td><b>设置项条目</b>，{@code vod_id} 即动作 id，无封面视频含义</td></tr>
 *   <tr><td>{@code detailContent}</td><td>影片详情 + 播放地址</td>
 *       <td><b>无内容返回</b>：仅执行动作副作用后返回空响应</td></tr>
 *   <tr><td>{@code searchContent}</td><td>搜索影视</td>
 *       <td><b>不适用</b>：上游未实现（继承父类默认空响应）</td></tr>
 * </table>
 *
 * <h2>宿主能力与可测性</h2>
 * 上游把 Android 宿主调用直接写在业务流里（{@code k.*} 文件读写 / {@code I.i} 提示 /
 * {@code G.q().z} 打开配置页 / {@code H.*} 维护动作 / {@code Init.execute} 线程池），
 * 导致本类一行都测不了。本重写把这些抽成 {@link Host} 接口，业务逻辑（分类表、设置项文案、
 * 动作分派、双击护栏、配置取值）100% 可离线单测；宿主能力由注入实现决定。
 *
 * <h2>协议要点（还原自反编译）</h2>
 * <ul>
 *   <li><b>配置取值</b>：{@code /config.json} 交给 {@code k.d()} 解析为
 *       {@code <filesDir>/Pizazz/./config.json}；登录态文件在外部存储 {@code /sdcard/TVBox/} 下。</li>
 *   <li><b>双击护栏</b>：所有"清除"动作都要求 <b>两次点击间隔小于 500ms</b>。
 *       第一次点击只记录时间戳，第二次才真正执行（{@link #clearFileWithDoubleCheck}）。</li>
 *   <li><b>可点按开关</b>：上游 {@code init()} 里 {@code this.i = I.f()}，
 *       而 {@code I.f()} 是<b>反射检查宿主 {@code Spider} 是否声明了 {@code action} 方法</b>
 *       —— 老宿主没有 {@code Spider.action()}，此时不给条目附 action（点不动）。
 *       见 {@link #detectHostActionSupport()}。</li>
 *   <li><b>画质选项</b>：由 {@code config.json} 的 {@code aliQuality/quarkQuality/ucQuality/
 *       baiduQuality/123Quality} 五个键决定，取值支持 {@code "A|B"} 形式表示"两个都启用"。</li>
 * </ul>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>缺键即整页空白</b>：上游 {@code categoryContent} 用
 *       {@code new JsonParser().parse(k.a(k.d("/config.json"))).getAsJsonObject()
 *       .get("aliQuality").getAsString()} 取画质/线程文案，
 *       {@code config.json} 缺失、非法或<b>只是少了某个键</b>时直接抛异常，
 *       被外层 catch 吞掉 → 整页返回空串（用户看到空白设置页）。
 *       已改为 {@link #cfgValue} 逐键带默认值取值；
 *       钉住断言：{@code ConfigTest#categoryQualityFallback()}。</li>
 *   <li><b>{@code ""} 被当作文件路径</b>：上游 8 处调用
 *       {@code clearFileWithDoubleCheck(name, "")}，第二个参数 {@code ""} 经 {@code k.b("")}
 *       变成根目录，{@code k.c(目录, "")} 抛 {@code FileNotFoundException} 并被静默吞掉
 *       —— 巧合地成了 no-op（真正需要清理的只有 {@code free_thunder} / {@code free_guangya}）。
 *       已显式跳过空文件名，不再依赖"写目录会失败"这一巧合；
 *       钉住断言：{@code ConfigTest#clearExtraFilePolicy()}。</li>
 *   <li><b>画质判断里的死条件</b>：{@code get123Options}/{@code getBaiduOptions} 写成
 *       {@code if (!"123原画".equals(v) && "123无限".equals(v))}，第一个条件恒被第二个蕴含，
 *       属冗余；语义等价于"等于无限就返回无限，否则返回原画"，已按等价形式实现并注明。</li>
 *   <li><b>无职责的 init</b>：上游 {@code init()} 只设一个开关、不解析 ext —— 本类<b>没有 ext 契约</b>
 *       （它不访问任何站点）。此点已在 {@link #init} 里写明，避免后来者去"补"一个不存在的 ext 解析。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Config.java}<br>
 * 辅助证据（baksmali 反汇编 {@code re/spider.dex}，用于纠正 jadx 的嵌套 if 还原）:
 * {@code Config.p(String)} 的精确跳转 —— 12 个提示分支走 {@code :goto_2cb} 写 flag 后统一
 * {@code G.q().z(map)}；28 个"设置页"分支走 {@code :goto_2c6}（flag=""）；13 个动作分支直接
 * {@code return}；未知 id 直接返回不动作。<br>
 * 上游响应构造器 {@code merge.p028c.c} 的字段名（{@code type_name/vod_id/.../action}）
 * 来自 {@code merge.c.e} 的 {@code @SerializedName} 注解反汇编。
 
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>本类不提供内容数据，所以不存在"未实现的内容方法"</b>：它是框架支撑类
 *       （见类注释第一段），{@code homeContent/categoryContent/detailContent/searchContent/
 *       playerContent} 在本类中<b>不适用</b> —— 宿主 {@code Config} 有它们只是为了满足
 *       TVBox 契约（配置页借内容方法的通道返回设置界面）。因此"6 个内容方法空缺"
 *       不是缺口，请勿按"漏实现"补。{@code categoryContent} 只回一页空壳、
 *       {@code detailContent} 只做 Activity 分派后回 {@code {}}。</li>
 *   <li><b>宿主回调未实现</b>：{@code Host} 接口的 {@code displayName/toast/
 *       openConfigPage/clearDanmu/backupConfig/clearUcToken/runAsync} 在
 *       {@link FileHost} 里是"能落地就落地、落不了就记日志"（例如 toast 无 UI 可弹）；
 *       真机行为需宿主实现 {@code Host} 后覆盖。这些不是"没写"，而是宿主能力边界。</li>
 *   <li><b>设备指纹/系统版本分支</b>：{@code detectHostActionSupport()} 依赖宿主的
 *       反射能力，在纯 JVM 下恒为 false（本重写用 {@code setHostSupportsAction} 显式注入）。</li>
 *   <li><b>未还原</b>：{@code TIPS} 12 条 / {@code PAGE_SITES} 28 个 id /
 *       {@code clearMessage} 18 条文案里，凡未在宿主类里出现原文的，本类不含
 *       （不做同义改写）。真实 host 与图片 CDN 域名（{@code img.51shazhu.com}）
 *       保留为常量，联网行为由宿主决定。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/Config.java}（逐行核对：{@code init}、
 * {@code homeContent} 的 11 个分类、{@code categoryContent} 的 1/1/1/1 分页、
 * {@code detailContent} 的 Activity 拦截、{@code action} 的 13 个分支、
 * {@code clearFileWithDoubleClick} 的 8 个调用点、{@code clearMessage} 映射表）。
*/
public class ConfigPlain extends Spider {

    private static final String TAG = "ConfigPlain";

    // ── 常量（来自上游硬编码） ───────────────────────────────

    /** 配置文件相对路径（上游 {@code k.d("/config.json")}） */
    public static final String CONFIG_PATH = "/config.json";

    /** Go 代理探活地址（上游 {@code merge.p036k.b.l(...)} 的实参） */
    public static final String GO_PROXY_PING_URL = "http://127.0.0.1:5266/api/ping";

    /** 双击护栏窗口：两次点击间隔 < 500ms 才算"双击" */
    public static final long DOUBLE_CLICK_INTERVAL_MS = 500L;

    /** 双击重置的提示语（上游硬编码，含 ⚠️） */
    public static final String CONFIRM_RESET_HINT = "⚠️ 为防止误操作，请双击确认重置";

    /** 首页配置分区表（上游 {@code new JSONObject("...")} 字面量，键=显示名、值=type_id） */
    public static final String SECTIONS_JSON =
            "{\"综合配置\":\"other\",\"夸克网盘配置\":\"quark\","
                    + "\"百度网盘配置\":\"baidu\","
                    + "\"哔哩配置\":\"bili\",\"重置配置\":\"recovery\"}";

    /**
     * 配置分区的<b>显示顺序</b> —— 与 {@link #SECTIONS_JSON} 的书写顺序逐一对应。
     *
     * <h2>为什么必须显式声明，而不能靠 {@code JSONObject.keys()}</h2>
     * {@code org.json} 有两个世代，键迭代顺序**不同**（实测，见
     * {@code maintain/src/common/JsonText.java} 的对照表）：
     * <pre>
     *   现代 json-java  → 插入序（内部 LinkedHashMap）
     *   旧版 org.json  → 散列序（内部 HashMap）—— 真宿主 fmplayer.app 自带的是这一版
     * </pre>
     * 于是"分类菜单的顺序"会<b>随宿主使用的库版本而变</b>：
     * 本地（新库）是 综合配置→…→重置配置，真宿主（旧库）会变成散列序。
     * 而配置页的分区顺序是<b>用户可见的呈现顺序</b>（"重置配置"必须排最后，
     * 否则用户容易误点），所以它不是"库的实现细节"，是我们的行为 →
     * 必须由代码显式声明，不能继承迭代顺序。
     *
     * <p>顺序来源：与字面量书写顺序一致（字面量就是按这个语义排的），
     * 并由 {@link #sectionsNotOrdered()} + 测试断言保证"字面量里的键一个都不漏"。
     */
    public static final String[] SECTION_ORDER = {
            "综合配置", "夸克网盘配置", "百度网盘配置", "哔哩配置", "重置配置",
    };

    /**
     * 登录态 / 配置文件（外部存储 {@code /sdcard/TVBox/} 下，上游 {@code k.b(...)}）。
     *
     * <p>上游这里还有 {@code /aliyun.txt}、{@code /uc_cookie.txt}、{@code /uc_token.txt}、
     * {@code /xunlei.txt}、{@code /guangya.txt}、{@code /cloud189.txt}、
     * {@code /cloud123.txt}，以及迅雷/光鸭的两个隐藏开关
     * （{@code free_thunder} / {@code free_guangya}）。
     * 这些网盘已不在本项目范围内，常量与对应的清理动作一并删除，
     * 见 {@code maintain/evidence/SCOPE_QUARK_BAIDU.md}。
     *
     * <p>哔哩是<b>内容站</b>不是云盘，故保留。
     */
    public static final String FILE_QUARK_COOKIE = "/quark_cookie.txt";
    public static final String FILE_BAIDU = "/baidu.txt";
    public static final String FILE_BILI_COOKIE = "/bili_cookie.txt";

    /**
     * 51shazhu CDN 上的设置项图标（上游硬编码，逐个保留原文）。
     * <p>阿里/UC/天翼/123/迅雷/光鸭 6 家的图标常量随其面板一起删除 ——
     * 面板没了，图标不会再被引用。
     */
    public static final String PIC_QUARK =
            "https://img.51shazhu.com/autoupload/g1b6f/20260807/bsds/1200X800/quark.jpg/webp";
    public static final String PIC_BAIDU =
            "https://img.51shazhu.com/autoupload/g1b6f/20260806/V6JN/1200X800/baidu.jpg/webp";
    public static final String PIC_BILI =
            "https://img.51shazhu.com/autoupload/gE6Y0Af2tjXBCNig6CtNDI12_FRYNb81z6UPhMWD8iI"
                    + "/20260503/3lGI/1200X800/bili.jpg/webp";
    public static final String PIC_HOMEPAGE =
            "https://img.51shazhu.com/autoupload/g1b6f/20260816/3Bad/1200X800/homepage.jpg/webp";
    public static final String PIC_DANMU_SETTING =
            "https://img.51shazhu.com/autoupload/g1b6f/20260816/afQD/1600X1068/danmucolor.jpg/webp";
    public static final String PIC_PROXY_GO =
            "https://img.51shazhu.com/autoupload/g1b6f/20260816/knNx/1200X800/go.jpg/webp";
    public static final String PIC_PROXY_JAVA =
            "https://img.51shazhu.com/autoupload/g1b6f/20260816/dsOE/1200X800/java.jpg/webp";
    public static final String PIC_PANSOU =
            "https://img.51shazhu.com/autoupload/g1b6f/20260816/R9k2/1200X800/pansou.jpg/webp";
    public static final String PIC_PAN_ORDER =
            "https://img.51shazhu.com/autoupload/g1b6f/20260816/sW0O/1200X800/panorder.jpg/webp";
    public static final String PIC_PAN_VIEW =
            "https://img.51shazhu.com/autoupload/g1b6f/20260816/wVEI/1200X800/panview.jpg/webp";
    public static final String PIC_BACKUP =
            "https://img.51shazhu.com/autoupload/g1b6f/20260816/Kt6M/1200X800/backup.jpg/webp";
    public static final String PIC_CLEAN_DANMU =
            "https://img.51shazhu.com/autoupload/g1b6f/20260816/2X66/1200X800/danmuclean.jpg/webp";
    public static final String PIC_UPDATE =
            "https://img.51shazhu.com/autoupload/g1b6f/20260816/P7lE/1200X800/update.jpg/webp";
    public static final String PIC_RECOVERY =
            "https://img.51shazhu.com/autoupload/g1b6f/20260818/hh9g/1200X800/recovery.jpg/webp";

    /** 上游 {@code public static long lastResetClickTime} —— 双击护栏的时间戳（全局共享） */
    public static long lastResetClickTime;

    // ── 宿主能力抽象 ─────────────────────────────────────────

    /**
     * 宿主能力接口 —— 把 Android 专属动作与业务逻辑分离，使本类可离线单测。
     *
     * <p>每个方法都标注了上游对应实现，便于对照审计。
     */
    public interface Host {

        /**
         * 读配置/登录态文件文本。
         *
         * @param name 形如 {@code /aliyun.txt} 或 {@code /config.json}
         * @return 文本；文件不存在或读取失败返回空串（对齐上游 {@code k.a(File)} 永不抛）
         */
        String read(String name);

        /**
         * 写配置/登录态文件文本（空串即"清空"）。
         *
         * @param name 形如 {@code /aliyun.txt}
         * @param text 内容，null 视为空串
         */
        void write(String name, String text);

        /**
         * 文件的显示名，用于清理后的提示（上游 {@code File.getName()}）。
         *
         * @param name 形如 {@code /aliyun.txt}
         * @return 基名，如 {@code aliyun.txt}
         */
        String displayName(String name);

        /**
         * 气泡提示（上游 {@code merge.m.I.i(String)}，内部再 {@code Init.run} 到主线程）。
         *
         * @param message 文本
         */
        void toast(String message);

        /**
         * 打开配置页（上游 {@code G.q().z(Map)}：把 {@code site}+{@code flag} 交给宿主内页）。
         *
         * @param site 站点标识，如 {@code ali} / {@code homePage}
         * @param flag 附加标记（提示语或空串）
         */
        void openConfigPage(String site, String flag);

        /** 清理弹幕缓存（上游 {@code merge.m.H.cleanDanmu()}） */
        void clearDanmu();

        /** 备份配置（上游 {@code merge.m.H.backupsConfig()}） */
        void backupConfig();

        /**
         * 异步执行（上游 {@code Init.execute(Runnable)}，5 线程池）。
         *
         * @param task 任务
         */
        void runAsync(Runnable task);
    }

    /**
     * 默认宿主实现 —— 桌面/纯 JVM 环境下的降级实现。
     *
     * <p>文件读写是<b>真的</b>（落在 {@link #root} 下），宿主动作（提示/开页/备份/token）
     * 降级为日志，不会真的去调 Android。这样在没有宿主时本类依旧可用且不静默出错。
     */
    public static class FileHost implements Host {

        /** 外部存储根（对应上游 {@code /sdcard/TVBox}） */
        private final File root;
        /** 配置目录（对应上游 {@code <filesDir>/Pizazz}） */
        private final File configRoot;

        /** 用默认根构造：{@code java.io.tmpdir/tvbox-config} */
        public FileHost() {
            this(new File(System.getProperty("java.io.tmpdir", "."), "tvbox-config"));
        }

        /**
         * 指定根目录构造。
         *
         * @param root 根目录（外部存储替身）
         */
        public FileHost(File root) {
            this.root = root;
            this.configRoot = new File(root, "Pizazz");
        }

        /** @return 外部存储根（登录态文件所在） */
        public File externalRoot() {
            return root;
        }

        /** @return 配置目录（config.json 所在） */
        public File configRoot() {
            return configRoot;
        }

        /**
         * 把逻辑路径映射到实际文件：{@code /config.json} 落在配置目录，
         * 其余落在外部存储根（对齐上游 {@code k.d} 与 {@code k.b} 两个不同根）。
         *
         * @param name 逻辑路径
         * @return 文件对象
         */
        public File resolve(String name) {
            String n = name == null ? "" : name.replace('\\', '/');
            if (n.startsWith("/config.json")) {
                return new File(configRoot, "config.json");
            }
            String base = n.startsWith("/") ? n.substring(1) : n;
            return new File(root, base);
        }

        @Override
        public String read(String name) {
            try {
                File f = resolve(name);
                if (!f.isFile()) {
                    return "";
                }
                byte[] buf = new byte[(int) f.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                try {
                    int n = in.read(buf);
                    return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
                } finally {
                    in.close();
                }
            } catch (Throwable e) {
                Log.w(TAG, "read failed: " + name);
                return "";
            }
        }

        @Override
        public void write(String name, String text) {
            try {
                File f = resolve(name);
                File parent = f.getParentFile();
                if (parent != null && !parent.isDirectory()) {
                    parent.mkdirs();
                }
                byte[] raw = (text == null ? "" : text).getBytes("UTF-8");
                java.io.FileOutputStream out = new java.io.FileOutputStream(f, false);
                try {
                    out.write(raw);
                    out.flush();
                } finally {
                    out.close();
                }
            } catch (Throwable e) {
                Log.w(TAG, "write failed: " + name);
            }
        }

        @Override
        public String displayName(String name) {
            return resolve(name).getName();
        }

        @Override
        public void toast(String message) {
            Log.i(TAG, "toast: " + message);
        }

        @Override
        public void openConfigPage(String site, String flag) {
            Log.i(TAG, "openConfigPage site=" + site + " flag=" + flag);
        }

        @Override
        public void clearDanmu() {
            Log.i(TAG, "cleanDanmu (无宿主，降级为日志)");
        }

        @Override
        public void backupConfig() {
            Log.i(TAG, "backupsConfig (无宿主，降级为日志)");
        }

        @Override
        public void runAsync(Runnable task) {
            if (task == null) {
                return;
            }
            Thread t = new Thread(task, "ConfigPlainHost");
            t.setDaemon(true);
            t.start();
        }
    }

    /** 当前宿主实现（单例，供 static 方法使用 —— 上游那些方法本身就是 static） */
    private static volatile Host host = new FileHost();

    /**
     * 注入宿主实现。
     *
     * @param h 实现；null 时恢复默认
     */
    public static void setHost(Host h) {
        host = h == null ? new FileHost() : h;
    }

    /**
     * 取当前宿主实现。
     *
     * @return 永不为 null
     */
    public static Host host() {
        Host h = host;
        if (h == null) {
            h = new FileHost();
            host = h;
        }
        return h;
    }

    /** 宿主是否支持 action 回调（上游 {@code this.i}，来自 {@code I.f()}） */
    private boolean hostSupportsAction;

    /** 构造：空实现（上游字段只有 {@code lastResetClickTime} 与 {@code i}） */
    public ConfigPlain() {
        this.empty = new JSONObject();
    }

    // ── 生命周期 ─────────────────────────────────────────────

    /**
     * 初始化。
     *
     * <p>上游实现只有两步：{@code Init.checkPermission()} 与 {@code this.i = I.f()}。
     * <b>本类没有 ext 契约</b>（不访问任何站点，配置全部来自本地文件），
     * 所以这里不解析 ext —— 不要因为"别的源都解析 ext"而补一个出来。
     *
     * @param context 宿主上下文，可为 null
     * @param extend  上游完全忽略（本类无站点配置）
     */
    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        this.mContext = context;
        InitPlain.checkPermission();
        this.hostSupportsAction = detectHostActionSupport();
        Log.i(TAG, "init ok, hostSupportsAction=" + this.hostSupportsAction);
    }

    /**
     * 反射探测宿主 {@code Spider} 基类是否声明了 {@code action} 方法 —— 上游 {@code I.f()} 的等价物。
     *
     * <p>上游常量名 {@code "action"}，目标是 {@code com.github.catvod.crawler.Spider}。
     * 本仓库把宿主契约类改名到 {@code com.example.tvbox.crawler.Spider}（见
     * {@code maintain/src/crawler/Spider.java}），故这里<b>沿继承链向上</b>找同名方法，
     * 语义一致：老宿主（无 {@code action}）→ false → 设置项不带 action（点不动）。
     *
     * @return true 表示宿主支持 action 回调
     */
    public static boolean detectHostActionSupport() {
        try {
            for (Class<?> c = ConfigPlain.class; c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if ("action".equals(m.getName())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable e) {
            Log.w(TAG, "detectHostActionSupport failed", e);
            return false;
        }
    }

    /**
     * 宿主是否支持 action（供测试强制两条分支）。
     *
     * @return 开关值
     */
    public boolean hostSupportsAction() {
        return hostSupportsAction;
    }

    /**
     * 强制设置 {@link #hostSupportsAction}（测试用；上游该值由 {@code I.f()} 反射决定）。
     *
     * @param supported 值
     * @return this
     */
    public ConfigPlain setHostSupportsAction(boolean supported) {
        this.hostSupportsAction = supported;
        return this;
    }

    // ── 配置读取 ─────────────────────────────────────────────

    /**
     * 读配置原文。
     *
     * @return {@code /config.json} 文本；不存在返回空串
     */
    public static String readConfigRaw() {
        return host().read(CONFIG_PATH);
    }

    /**
     * 安全取一个配置键的字符串值。
     *
     * <p>上游写法是 {@code new JsonParser().parse(k.a(k.d("/config.json")))
     * .getAsJsonObject().get(key).getAsString()} —— 配置缺失/非法/少键都会抛异常。
     * 本方法与之等价取值，但异常时回落到 {@code def}（见类注释"修正的上游缺陷 1"）。
     *
     * @param key 键名
     * @param def 缺省值
     * @return 值或 {@code def}
     */
    public static String cfgValue(String key, String def) {
        if (TextUtils.isEmpty(key)) {
            return def;
        }
        String raw = readConfigRaw();
        if (TextUtils.isEmpty(raw)) {
            return def;
        }
        try {
            JSONObject o = new JSONObject(raw);
            String v = o.optString(key, "");
            return v.isEmpty() ? def : v;
        } catch (Throwable e) {
            Log.w(TAG, "config.json 非法，使用默认值: key=" + key);
            return def;
        }
    }

    // ── 画质选项（上游 12 个 public static getter） ───────────

    /**
     * 按"精确取值表"解析画质选项 —— 上游 {@code getAliOptions} 等三个方法的等价抽象。
     *
     * <p>表里没有的取值一律回落到默认项（上游就是这么做的一串 {@code if/else if}）。
     *
     * @param key   配置键，如 {@code aliQuality}
     * @param def   默认项（表未命中时返回的单元素列表）
     * @param table 取值 → 选项列表
     * @return 非 null 列表
     */
    public static List<String> tableOptions(String key, String def,
                                            Map<String, List<String>> table) {
        String v = cfgValue(key, "");
        if (!v.isEmpty() && table != null && table.containsKey(v)) {
            return new ArrayList<>(table.get(v));
        }
        List<String> out = new ArrayList<>();
        out.add(def);
        return out;
    }

    /**
     * 两值枚举式画质（上游 {@code get123Options} / {@code getBaiduOptions}）。
     *
     * <p>上游条件 {@code if (!"123原画".equals(v) && "123无限".equals(v))} 等价于
     * {@code "123无限".equals(v)}（第一个条件是冗余的死条件），此处按等价形式实现。
     *
     * @param key     配置键
     * @param def     默认项（如 {@code 123原画}）
     * @param onValue 使能项（如 {@code 123无限}）
     * @return 单元素列表
     */
    public static List<String> switchOptions(String key, String def, String onValue) {
        List<String> out = new ArrayList<>();
        out.add(onValue.equals(cfgValue(key, "")) ? onValue : def);
        return out;
    }

    /**
     * 百度网盘画质选项。
     *
     * @return 单元素列表：{@code 百度无限} 或 {@code 百度原画}
     */
    public static List<String> getBaiduOptions() {
        return switchOptions("baiduQuality", "百度原画", "百度无限");
    }

    /**
     * 本机百度（个人中心）画质选项（上游固定单值）。
     *
     * @return {@code [百度原画]}
     */
    public static List<String> getMyBaiduOptions() {
        return one("百度原画");
    }

    /**
     * 本机夸克画质选项（上游固定单值）。
     *
     * @return {@code [夸克原画]}
     */
    public static List<String> getMyQuarkOptions() {
        return one("夸克原画");
    }

    /**
     * 夸克网盘画质选项（三种单值 + 两种双启用）。
     *
     * @return 1~2 个元素的列表
     */
    public static List<String> getQuarkOptions() {
        Map<String, List<String>> t = new LinkedHashMap<>();
        t.put("夸克原画", one("夸克原画"));
        t.put("夸克普画", one("夸克普画"));
        t.put("夸克无限", one("夸克无限"));
        t.put("夸克原画|夸克普画", two("夸克原画", "夸克普画"));
        t.put("夸克普画|夸克原画", two("夸克普画", "夸克原画"));
        return tableOptions("quarkQuality", "夸克原画", t);
    }

    /** 单元素列表工具 */
    private static List<String> one(String v) {
        List<String> l = new ArrayList<>();
        l.add(v);
        return l;
    }

    /** 双元素列表工具 */
    private static List<String> two(String a, String b) {
        List<String> l = new ArrayList<>();
        l.add(a);
        l.add(b);
        return l;
    }

    // ── 更新开关 ─────────────────────────────────────────────

    /**
     * 关闭"检查更新"（写回 {@code config.json} 的 {@code update} 键）。
     *
     * <p>已是关闭状态时不写盘（上游 {@code I.i("已是关闭状态")} 后 return）。
     * 读取失败按"空对象"处理（上游：文件不存在或内容为空时用空 JsonObject）。
     */
    public static void disableUpdateCheck() {
        setUpdate("关闭");
    }

    /** 开启"检查更新" */
    public static void enableUpdateCheck() {
        setUpdate("开启");
    }

    /**
     * 写 {@code update} 键。
     *
     * @param value {@code 开启} / {@code 关闭}
     */
    private static void setUpdate(String value) {
        try {
            String raw = readConfigRaw();
            JSONObject o = new JSONObject();
            if (!TextUtils.isEmpty(raw)) {
                try {
                    o = new JSONObject(raw);
                } catch (Throwable e) {
                    Log.w(TAG, "config.json 非法，按空对象处理");
                    o = new JSONObject();
                }
            }
            String cur = o.has("update") ? o.optString("update", "开启") : "开启";
            if (value.equals(cur)) {
                host().toast("已是" + value + "状态");
                return;
            }
            o.put("update", value);
            host().write(CONFIG_PATH, o.toString());
            host().toast("检查更新已" + value);
        } catch (Throwable e) {
            Log.e(TAG, "setUpdate failed", e);
        }
    }

    // ── 代理探活 ─────────────────────────────────────────────

    /**
     * Go 代理是否在线 —— 探活 {@code http://127.0.0.1:5266/api/ping}，响应恰为 {@code ok} 即在线。
     *
     * <p>上游用 {@code merge.p036k.b.l(url, new HashMap())}；本重写走公共
     * {@link com.example.tvbox.common.Http}（测试里 {@code setOfflineForTest(true)} 即恒 false）。
     *
     * @return true 表示在线
     */
    public static boolean is5266GoProxyActive() {
        try {
            String body = com.example.tvbox.common.Http.create()
                    .timeout(2000, 2000)
                    .retry(0)
                    .get(GO_PROXY_PING_URL);
            return "ok".equals(body);
        } catch (Throwable e) {
            return false;
        }
    }

    // ── 契约：首页 = 配置分类菜单（不是内容列表） ──────────────

    /**
     * 首页：返回 <b>配置分类菜单</b>（11 个设置分区），{@code list} 恒为空。
     *
     * <p><b>这不是内容源</b>：上游 {@code homeContent} 就是把
     * {@link #SECTIONS_JSON} 的键值对转成 {@code class} 数组，
     * 列表传 {@code new ArrayList()}（空）。
     *
     * <p>与上游的唯一差异：上游用 {@code p(class, list)} 构造，输出只有
     * {@code class} 与 {@code list} 两个字段；本重写走
     * {@link VodBuilder#homeContent()}，会多一个 {@code "filters":{}}（空筛选，宿主等价处理）。
     *
     * @param filter 宿主传参，本类不使用（配置菜单无筛选）
     * @return {@code {"class":[{"type_id","type_name"}...],"list":[],"filters":{}}}
     */
    @Override
    public String homeContent(boolean filter) {
        VodBuilder b = VodBuilder.create();
        for (Map.Entry<String, String> e : sections().entrySet()) {
            // 上游 new a(type_id, type_name, null) → 键是显示名、值是 type_id
            b.clazz(e.getValue(), e.getKey());
        }
        return b.homeContent();
    }

    /**
     * 解析首页分区表 —— 顺序取自 {@link #SECTION_ORDER}（显式声明），
     * <b>不是</b> {@code JSONObject.keys()}（那是库的实现细节，两代不同）。
     *
     * <p>取值仍走 {@code optString}（与转义/解析交给宿主的 org.json，本类不自己解析字符串）。
     * {@link #sectionsNotOrdered()} 兜底：万一字面量里加了新键而 {@code SECTION_ORDER}
     * 忘了同步，新键会追加在末尾（宁可顺序不完美，也不能凭空丢一个配置分区）。
     *
     * @return 有序表：显示名 → type_id
     */
    public static LinkedHashMap<String, String> sections() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(SECTIONS_JSON);
            for (String k : SECTION_ORDER) {
                if (o.has(k)) {
                    out.put(k, o.optString(k, ""));
                }
            }
            for (String k : sectionsNotOrdered()) {
                out.put(k, o.optString(k, ""));
            }
        } catch (Throwable e) {
            Log.e(TAG, "sections 解析失败", e);
        }
        return out;
    }

    /**
     * 字面量里出现、但 {@link #SECTION_ORDER} 没登记的键。
     *
     * <p>正常应恒为空；非空说明有人往 {@link #SECTIONS_JSON} 加了分区却忘了同步顺序表。
     * 测试用它把这个漂移点钉住（避免"新增分区不显示/顺序乱"这类只有真机才发现的错）。
     *
     * @return 未登记的键（按库的迭代顺序，仅用于兜底与自检）；永不为 null
     */
    public static List<String> sectionsNotOrdered() {
        List<String> out = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(SECTIONS_JSON);
            List<String> declared = new ArrayList<>();
            for (String k : SECTION_ORDER) {
                declared.add(k);
            }
            for (String k : JsonKeys.of(o)) {
                if (!declared.contains(k)) {
                    out.add(k);
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "sectionsNotOrdered 解析失败", e);
        }
        return out;
    }

    // ── 契约：分类页 = 某个分区的设置项 ───────────────────────

    /**
     * 分类页：把某个配置分区的 <b>设置项</b> 渲染成条目。
     *
     * <p>条目的 {@code vod_id} 是可执行的动作 id（{@code 200}/{@code c400}/{@code recovery}…），
     * {@code vod_pic} 是设置项图标，{@code vod_remarks} 是当前状态文案（如"当前线程:16"）。
     * 当 {@link #hostSupportsAction()} 为 false 时 {@code action} 字段为空
     * （对齐上游 {@code this.i ? id : ""}："老宿主点不动"）。
     *
     * <p>分页四元组固定为 {@code page=1,pagecount=1,limit=1,total=1}（上游 {@code i(1,1,1,1)}）。
     *
     * <p>未知 tid 不会抛异常：上游同样只是返回空列表 + 同样的分页四元组。
     *
     * @param tid    分区 id（{@link #homeContent} 里的 type_id）
     * @param pg     页码（本类恒为单页，忽略）
     * @param filter 宿主传参，不使用
     * @param extend 宿主传参，不使用
     * @return 标准分类响应；异常时返回 {@code {}}
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        try {
            List<Item> items = itemsOf(tid);
            VodBuilder b = VodBuilder.create();
            for (Item it : items) {
                b.add(toVod(it));
            }
            // 上游: cVar.i(1,1,1,1) —— page/pagecount/limit/total 全为 1
            return b.page(1, 1, 1).categoryContent();
        } catch (Throwable e) {
            Log.e(TAG, "categoryContent failed, tid=" + tid, e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /**
     * 取某个分区的设置项列表（上游 {@code categoryContent} 的分支体）。
     *
     * @param tid 分区 id
     * @return 非 null 列表；未知 tid 返回空列表
     */
    public List<Item> itemsOf(String tid) {
        List<Item> out = new ArrayList<>();
        if (tid == null) {
            return out;
        }
        String act = hostSupportsAction ? "1" : "";

        if ("quark".equals(tid)) {
            String nick = Account.nickname(host().read(FILE_QUARK_COOKIE));
            out.add(item("newquark", "夸克个人中心", PIC_QUARK, blankTo(nick, "未登录"), act));
            out.add(item("300", "设置夸克Cookie", PIC_QUARK, "点击设置", act));
            out.add(item("400", "清除夸克Cookie", PIC_QUARK, "双击清除", act));
            out.add(item("quarkQuality", "夸克网盘画质", PIC_QUARK,
                    cfgValue("quarkQuality", "夸克无限"), act));
            out.add(item("quarkThread", "夸克网盘线程", PIC_QUARK,
                    "当前线程:" + cfgValue("quarkThread", ""), act));
            out.add(item("quarktip", "夸克网盘帮助", PIC_QUARK, "点击查看", act));
        } else if ("baidu".equals(tid)) {
            String user = Account.userName(host().read(FILE_BAIDU));
            out.add(item("newbaidu", "百度个人中心", PIC_BAIDU, blankTo(user, "未登录"), act));
            out.add(item("b300", "设置百度Cookie", PIC_BAIDU, "点击设置", act));
            out.add(item("b400", "清除百度Cookie", PIC_BAIDU, "双击清除", act));
            out.add(item("baiduQuality", "百度网盘画质", PIC_BAIDU,
                    cfgValue("baiduQuality", "百度无限"), act));
            out.add(item("baiduThread", "百度网盘线程", PIC_BAIDU,
                    "当前线程:" + cfgValue("baiduThread", ""), act));
        } else if ("bili".equals(tid)) {
            String nick = Account.nickname(host().read(FILE_BILI_COOKIE));
            out.add(item("newbili", "哔哩个人中心", PIC_BILI, blankTo(nick, "未登录"), act));
            out.add(item("700", "设置哔哩Cookie", PIC_BILI, "点击设置", act));
            out.add(item("800", "清除哔哩Cookie", PIC_BILI, "双击清除", act));
        } else if ("other".equals(tid)) {
            out.add(item("homePage", "主页管理", PIC_HOMEPAGE, "点击设置", act));
            out.add(item("danmuSetting", "弹幕管理", PIC_DANMU_SETTING, "点击设置", act));
            boolean go = "Go多线程".equals(cfgValue("proxyMode", ""));
            out.add(item("proxyMode", "多线程代理模式", go ? PIC_PROXY_GO : PIC_PROXY_JAVA,
                    "当前模式:" + (go ? "GO多线程" : "Java多线程"), act));
            out.add(item("pansou", "盘搜域名", PIC_PANSOU, cfgValue("pansouUrl", ""), act));
            out.add(item("panOrder", "云盘排序", PIC_PAN_ORDER, "点击设置", act));
            out.add(item("panView", "云盘搜索视图", PIC_PAN_VIEW, cfgValue("panView", ""), act));
            out.add(item("backup", "数据备份", PIC_BACKUP, "点击设置", act));
            out.add(item("cleandanmu", "清理弹幕缓存", PIC_CLEAN_DANMU, "双击清理", act));
            boolean on = "开启".equals(cfgValue("update", ""));
            out.add(item("update", "检查更新", PIC_UPDATE,
                    "当前状态:" + (on ? "已开启" : "已关闭"), act));
        } else if ("recovery".equals(tid)) {
            out.add(item("recovery", "重置配置中心", PIC_RECOVERY, "双击重置", act));
        }
        return out;
    }

    /** 上游 {@code new e(id, name, pic, remarks, action)}；action 为空串时下游不输出该字段 */
    private Item item(String id, String name, String pic, String remarks, String act) {
        return new Item(id, name, pic, remarks, "1".equals(act) ? id : "");
    }

    /**
     * 空值回落。
     *
     * @param v   值
     * @param def 缺省
     * @return 值或 {@code def}
     */
    private static String blankTo(String v, String def) {
        return v == null || v.isEmpty() ? def : v;
    }

    /**
     * 设置项 → {@link Vod}。
     *
     * @param it 设置项
     * @return Vod
     */
    public static Vod toVod(Item it) {
        return new Vod()
                .id(it.id)
                .name(it.name)
                .pic(it.pic)
                .remarks(it.remarks)
                .action(it.action);
    }

    // ── 契约：详情 = 执行动作（无内容返回） ───────────────────

    /**
     * 详情：<b>不返回内容</b>，只执行动作副作用。
     *
     * <p>上游语义：{@code Init.interceptActivityStart(); p(ids.get(0)); return ""; }
     * —— "详情"在这里的含义是"点按了这个设置项"，返回体被宿主丢弃。
     * 本重写执行同样的两步（关掉误开的播放/详情页 → 分派动作），并返回宿主契约要求的
     * 空响应 {@code {}}（上游返回 {@code ""}；宿主对二者都按"无内容"处理）。
     *
     * @param ids 上游只取第一个元素作为动作 id
     * @return {@code {}}
     */
    @Override
    public String detailContent(List<String> ids) {
        try {
            InitPlain.interceptActivityStart();
            if (ids != null && !ids.isEmpty()) {
                dispatch(ids.get(0));
            }
        } catch (Throwable e) {
            Log.e(TAG, "detailContent failed", e);
        }
        return VodBuilder.EMPTY_RESPONSE;
    }

    /**
     * 自定义动作入口。
     *
     * <p>上游 {@code action(String)} = {@code Init.execute(() -> p(str)); return ""; }
     * —— <b>异步</b>执行（放在线程池里），返回值恒为空串。本重写保持一致：
     * 交给 {@link Host#runAsync} 后立即返回空串。
     *
     * @param str 动作 id
     * @return {@code ""}（宿主约定，不返回内容）
     */
    @Override
    public String action(String str) {
        final String id = str;
        host().runAsync(new Runnable() {
            @Override
            public void run() {
                dispatch(id);
            }
        });
        return "";
    }

    // ── 动作分派（上游 private void p(String)） ───────────────

    /** "打开设置页 + 提示语"动作（上游 12 条；本项目只留夸克/百度两条扫码 + 哔哩 + 帮助） */
    private static final Map<String, String[]> TIPS = new LinkedHashMap<>();

    /** "直接把 id 当 site 打开配置页"的动作（上游 28 条；随网盘面板一并收窄） */
    private static final Set<String> PAGE_SITES = new LinkedHashSet<>();

    static {
        TIPS.put("300", new String[]{"quark", "请使用夸克浏览器扫描二维码"});
        TIPS.put("b300", new String[]{"baidu", "请使用百度网盘扫描二维码"});
        TIPS.put("700", new String[]{"bili", "请使用哔哩哔哩扫描二维码"});
        TIPS.put("quarktip", new String[]{"quarktip", "夸克网盘帮助"});

        PAGE_SITES.addAll(Arrays.asList(
                "homePage", "danmuSetting", "panOrder", "pansou", "panView", "proxyMode",
                "backup", "quarkThread", "baiduThread",
                "quarkQuality", "baiduQuality",
                "update", "newquark", "newbaidu", "newbili"));
    }

    /**
     * 分派一个动作 id —— 上游 {@code private void p(String str)} 的完整还原。
     *
     * <p>分支顺序与上游一致（先提示分支 → 再"设置页"分支 → 最后动作分支），
     * 且未知 id <b>不动作也不抛异常</b>（上游同样直接 return）。
     *
     * @param id 动作 id，可为 null（null 时无动作）
     */
    public void dispatch(String id) {
        if (id == null) {
            return;
        }
        String[] tip = TIPS.get(id);
        if (tip != null) {
            // 上游: map.put("site", site); map.put("flag", tip); z(map)
            host().openConfigPage(tip[0], tip[1]);
            return;
        }
        if (PAGE_SITES.contains(id)) {
            // 上游: map.put("site", str); map.put("flag", ""); z(map)
            host().openConfigPage(id, "");
            return;
        }
        if ("400".equals(id)) {
            clearFileWithDoubleCheck(FILE_QUARK_COOKIE, "");
        } else if ("b400".equals(id)) {
            clearFileWithDoubleCheck(FILE_BAIDU, "");
        } else if ("800".equals(id)) {
            clearFileWithDoubleCheck(FILE_BILI_COOKIE, "");
        } else if ("cleandanmu".equals(id)) {
            if (confirmDoubleClick()) {
                host().clearDanmu();
            }
        } else if ("recovery".equals(id)) {
            if (confirmDoubleClick()) {
                host().backupConfig();
            } else {
                host().toast(CONFIRM_RESET_HINT);
            }
        }
        // 其余 id：上游直接 return，不做任何事
    }

    /**
     * 双击确认护栏 —— 上游内联在两个分支里的同一段逻辑。
     *
     * <p>语义：距上次点击 {@code >= }{@value #DOUBLE_CLICK_INTERVAL_MS}ms 视为"第一次点击"
     * ——记录时间戳并返回 false；间隔小于该值时把时间戳清零（消费掉这次配对）并返回 true。
     * 因此"双击"= 500ms 内连点两次。
     *
     * @return true 表示这是有效双击，可以执行破坏性动作
     */
    public static boolean confirmDoubleClick() {
        long now = System.currentTimeMillis();
        if (now - lastResetClickTime >= DOUBLE_CLICK_INTERVAL_MS) {
            lastResetClickTime = now;
            return false;
        }
        lastResetClickTime = 0L;
        return true;
    }

    /**
     * 双击确认后清空文件 —— 上游 {@code clearFileWithDoubleCheck(String, String)}。
     *
     * <p>顺序与上游一致：护栏 → 清主文件 → 取显示名 → 清第二文件 → 提示。
     * 提示文案不是文件名本身，而是 {@link #clearMessage(String)} 映射出的中文
     * （上游把文件名交给 {@code merge.m.I.i(...)}，那里再做一次映射）。
     *
     * <p><b>修正</b>：上游 8 处把第二参数写成 {@code ""}，它经 {@code k.b("")} 变成根目录、
     * 写目录抛异常又被静默吞掉 —— 本重写显式跳过空名（见类注释缺陷 2）。
     *
     * @param name      主文件逻辑路径，如 {@code /aliyun.txt}
     * @param extraName 附加文件逻辑路径，可为 null/空（跳过）
     */
    public static void clearFileWithDoubleCheck(String name, String extraName) {
        if (!confirmDoubleClick()) {
            return;
        }
        host().write(name, "");
        String display = host().displayName(name);
        if (extraName != null && !extraName.isEmpty()) {
            host().write(extraName, "");
        }
        host().toast(clearMessage(display));
    }

    /** 清理提示映射表（上游 18 条；只留夸克/百度/哔哩） */
    private static final Map<String, String> CLEAR_MESSAGES = new LinkedHashMap<>();

    static {
        CLEAR_MESSAGES.put("quark_cookie.txt", "已清除夸克Cookie");
        CLEAR_MESSAGES.put("baidu.txt", "已清除百度Cookie");
        CLEAR_MESSAGES.put("bili_cookie.txt", "已清除哔哩Cookie");
        CLEAR_MESSAGES.put("quark", "还未登录夸克账号,请前往【配置中心】登录");
        CLEAR_MESSAGES.put("baidu", "还未登录百度账号,请前往【配置中心】登录");
    }

    /**
     * 提示文案映射 —— 上游 {@code merge.m.I.i(String)} 的可测部分。
     *
     * <p>表中没有的输入原样返回（上游同样原样交给 Toast）。
     *
     * @param fileName 文件名或站点标识，可为 null
     * @return 提示文案；入参为空时返回空串
     */
    public static String clearMessage(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        String m = CLEAR_MESSAGES.get(fileName);
        return m == null ? fileName : m;
    }

    // ── 数据模型 ─────────────────────────────────────────────

    /**
     * 设置项（上游 {@code merge.p028c.e} 的 5 参构造：id/name/pic/remarks/action）。
     */
    public static class Item {
        /** vod_id —— 同时是动作 id */
        public final String id;
        /** vod_name —— 显示名 */
        public final String name;
        /** vod_pic —— 图标 */
        public final String pic;
        /** vod_remarks —— 当前状态文案 */
        public final String remarks;
        /** action —— 非空时宿主点按回传 {@code action}，否则走 {@code detailContent} */
        public final String action;

        /**
         * 构造。
         *
         * @param id      动作 id
         * @param name    显示名
         * @param pic     图标
         * @param remarks 备注
         * @param action  action 值（空串表示不输出该字段）
         */
        public Item(String id, String name, String pic, String remarks, String action) {
            this.id = id;
            this.name = name;
            this.pic = pic;
            this.remarks = remarks;
            this.action = action;
        }

        @Override
        public String toString() {
            return "Item{" + id + ", " + name + ", " + remarks + ", act=" + action + "}";
        }
    }

    /**
     * 登录态对象（上游 {@code Config$1} 内部类）。
     *
     * <p>字段名来自 Gson 反序列化的 JSON：{@code nickname} / {@code userAccount} /
     * {@code username} / {@code userName} / {@code name}，
     * 以及嵌套对象 {@code user.nick_name}。四个取值口径分别对应上游
     * {@code a()} / {@code b()} / {@code c()} / {@code d()} 四个私有方法。
     *
     * <p>上游把 {@code user} 声明成 {@code Map} 再强转 {@code (String)}，
     * 非字符串值会抛 {@code ClassCastException} 并让调用方 catch 成 {@code ""}。
     * 本实现同样在异常时回落空串。
     */
    public static class Account {
        /** 昵称（夸克/UC/123/哔哩用） */
        public String nickname = "";
        /** 登录名（百度/迅雷/光鸭用） */
        public String username = "";
        /** 账号（天翼用） */
        public String userAccount = "";
        /** 备用名（上游 getuserName$1） */
        public String userName = "";
        /** 名称 */
        public String name = "";
        /** 嵌套用户对象（阿里用 user.nick_name） */
        public JSONObject user;

        /**
         * 从 JSON 文本解析；任何异常都回落为空对象（上游 {@code try/catch → ""}）。
         *
         * @param json JSON 文本，可为 null
         * @return 非 null 对象
         */
        public static Account fromJson(String json) {
            Account a = new Account();
            if (json == null || json.trim().isEmpty()) {
                return a;
            }
            try {
                JSONObject o = new JSONObject(json);
                a.nickname = o.optString("nickname", "");
                a.username = o.optString("username", "");
                a.userAccount = o.optString("userAccount", "");
                a.userName = o.optString("userName", "");
                a.name = o.optString("name", "");
                a.user = o.optJSONObject("user");
            } catch (Throwable e) {
                // 上游同样吞掉异常返回空对象
            }
            return a;
        }

        /**
         * 昵称口径（上游 {@code a()}）。
         *
         * @param json 文本
         * @return {@code nickname}；失败为空串
         */
        public static String nickname(String json) {
            return fromJson(json).nickname;
        }

        /**
         * 登录名口径（上游 {@code b()}）。
         *
         * @param json 文本
         * @return {@code username}；失败为空串
         */
        public static String userName(String json) {
            return fromJson(json).username;
        }

        /**
         * 账号口径（上游 {@code c()}）。
         *
         * @param json 文本
         * @return {@code userAccount}；失败为空串
         */
        public static String userAccount(String json) {
            return fromJson(json).userAccount;
        }

        /**
         * 阿里专用口径（上游 {@code d()} → {@code getAliNickname()}）：{@code user.nick_name}。
         *
         * @param json 文本
         * @return 昵称；缺失/非字符串均为空串
         */
        public static String aliNickname(String json) {
            Account a = fromJson(json);
            if (a.user == null) {
                return "";
            }
            Object v = a.user.opt("nick_name");
            if (v == null || v == JSONObject.NULL) {
                return "";
            }
            return v instanceof String ? (String) v : "";
        }
    }

    /**
     * 供排障：当前分区表的 type_id 列表。
     *
     * @return 非 null 列表
     */
    public static List<String> sectionIds() {
        return new ArrayList<>(sections().values());
    }

    /**
     * 只读视图工具（避免调用方改内部表）。
     *
     * @param src 源列表，可为 null
     * @param <T> 元素类型
     * @return 不可变列表
     */
    public static <T> List<T> ro(List<T> src) {
        return Collections.unmodifiableList(new ArrayList<>(src));
    }

    /**
     * JSON 数组转字符串列表（保留顺序、跳过空项）。
     *
     * @param arr 数组，可为 null
     * @return 非 null 列表
     */
    public static List<String> toStringList(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /**
     * 逗号分隔文本转列表（跳过空项）。
     *
     * @param csv 文本，可为 null
     * @return 非 null 列表
     */
    public static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
