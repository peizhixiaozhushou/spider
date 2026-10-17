package com.example.tvbox.plain.pan.client;

import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 网盘登录态交据读写 —— 还原上游「配置中心写文件、插件读文件」的凭据传递方式。
 *
 * <h2>上游行为（逐字来自反编译）</h2>
 * <pre>
 *   夸克 QuarkPan:
 *     File f = new File(d.c(f7short, 9, 30, 1846));   // "/sdcard/TVBox/quark_cookie.txt"
 *     if (f.exists()) cookie = new JSONObject(read(f)).optString("cookie", "").trim();
 *     if (cookie == null)
 *         f = new File(d.c(f7short, 45, 27, 2405));   // "/sdcard/TV/quark_cookie.txt"
 *         if (f.exists()) cookie = read(f);
 *     if (TextUtils.isEmpty(cookie)) return;          // 放弃 = 未登录
 *
 *   UC 同构:  "/sdcard/TVBox/uc_cookie.txt"  →  "/sdcard/TV/uc_cookie.txt"
 *   123 另有第三方:  k.b("/cloud123.txt") = &lt;外部存储&gt;/TVBox/cloud123.txt（纯 JSON token）
 * </pre>
 * 规律：<b>主目录 {@code <sdcard>/TVBox/}，兜底目录 {@code <sdcard>/TV/}；
 * 主文件是 JSON（取 {@code cookie} 字段），兜底文件是裸文本。</b>
 *
 * <h2>可测试性设计（本类 ⇒ 文件层）</h2>
 * {@code android.os.Environment} 在重写工程中不可用（stub 未提供），且真实
 * {@code /sdcard} 在 CI 上不存在。因此根目录做成可注入：
 * <ul>
 *   <li>默认 {@link #DEFAULT_ROOT} = {@code /sdcard}（与上游一致）</li>
 *   <li>{@link #setRootForTest(String)} 指向临时目录，测试即可构造真实文件走全路径</li>
 * </ul>
 *
 * <h2>cookie 配置链（本类只负责第 3 层）</h2>
 * 本项目把"凭据从哪来"拆成 4 层，越靠前优先级越高，<b>先命中即返回</b>：
 * <ol>
 *   <li><b>站点 {@code ext}</b> 里的 {@code cookie}（内联整串）—— 见
 *       {@link PanClientBase#resolveCookie()}。这是 TVBox 配置里唯一"写进 json 就能生效"
 *       的位置，桌面宿主没有 {@code /sdcard} 时靠它。</li>
 *   <li><b>站点 {@code ext}</b> 里的 {@code cookieFile}（绝对路径）—— 同上。</li>
 *   <li><b>本类的目录查找</b>：{@link #PROP_DIR}（{@code -Dtvbox.pan.dir}）→
 *       {@link #ENV_DIR}（{@code TVBOX_PAN_DIR}）→ {@link #DEFAULT_ROOT}，
 *       然后在 {@code <root>/TVBox/<file>} / {@code <root>/TV/<file>} 里找。</li>
 *   <li><b>都没有</b> ⇒ 空串 ⇒ 该网盘视为"未登录"，宿主显示"请前往【配置中心】登录"。</li>
 * </ol>
 * 注意第 3 层仍然保留 {@code /sdcard} 作为兜底，所以 Android 侧配置中心写文件的老路径不变。
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/QuarkPan.java}(L41-52) /
 * {@code ae/decompiled/UCPan.java}(L50-61) /
 * {@code ae/decompiled/Pan123.java} + {@code merge.b.m}（{@code cloud123.txt}）。
 * 文件名常量由异或表解出，见 {@code ae/work/w-pan/decoded-strings.txt}。
 * 桌面宿主的 {@code -Dtvbox.pan.dir} 落点来自实测：fmplayer.app 的
 * {@code ~/Library/Application Support/fmplayer/gateway-data/}。
 */
public final class PanCreds {

    private static final String TAG = "PanCreds";

    /**
     * 与上游一致的根目录。
     *
     * <p>DEX 里的字面量是带尾斜杠的 {@code "/sdcard/"}（由 {@code literal_audit.py} 确认），
     * 这里逐字保留，拼接一律走 {@link #path(String)} 以归一化斜杠。
     */
    public static final String DEFAULT_ROOT = "/sdcard/";

    /** 主目录名（JSON 形态的凭据） */
    public static final String DIR_MAIN = "TVBox";

    /** 兜底目录名（裸文本形态的凭据） */
    public static final String DIR_FALLBACK = "TV";

    /**
     * 覆盖凭据根目录的<b>系统属性</b>名。
     *
     * <p>用于"宿主没有 {@code /sdcard} 的场景"——典型是 FongMi 桌面网关
     * （fmplayer.app 的 {@code gateway.jar}，跑在 macOS/Linux 的 JVM 里）。
     * 用 {@code -Dtvbox.pan.dir=/some/dir} 启动即可让 {@code <dir>/TVBox/quark_cookie.txt}
     * 生效，与 Android 侧行为完全一致。
     */
    public static final String PROP_DIR = "tvbox.pan.dir";

    /** 覆盖凭据根目录的<b>环境变量</b>名（等价于 {@link #PROP_DIR}，给不能传 -D 的宿主用） */
    public static final String ENV_DIR = "TVBOX_PAN_DIR";

    /**
     * 显式设置的根目录。
     *
     * <p>{@code null} = 未设置 ⇒ 继续按 {@link #PROP_DIR} → {@link #ENV_DIR} → {@link #DEFAULT_ROOT}
     * 的优先级查找。这样"配置优先于约定"、"约定优先于硬编码"的顺序是显式的。
     */
    private static String explicitRoot = null;

    private PanCreds() {
    }

    /**
     * 覆盖凭据根目录（配置入口）。
     *
     * @param r 绝对目录；{@code null}/空 表示清除显式设置、回到属性/环境变量/默认的查找链
     */
    public static void setRoot(String r) {
        explicitRoot = TextUtils.isEmpty(r) ? null : r;
    }

    /** 覆盖根目录（测试用同义词，行为与 {@link #setRoot(String)} 一致） */
    public static void setRootForTest(String r) {
        setRoot(r);
    }

    /**
     * 主根目录的来源。
     *
     * @return {@code "explicit"} / {@code "system-property"} / {@code "env"} / {@code ""}（没有主根）
     */
    private static String primaryRootSource() {
        if (!TextUtils.isEmpty(explicitRoot)) {
            return "explicit";
        }
        if (!TextUtils.isEmpty(property(PROP_DIR))) {
            return "system-property";
        }
        if (!TextUtils.isEmpty(environment(ENV_DIR))) {
            return "env";
        }
        return "";
    }

    /** 主根目录；没有配置时返回空串（表示"用默认链"） */
    private static String primaryRoot() {
        String s = primaryRootSource();
        if ("explicit".equals(s)) {
            return explicitRoot;
        }
        if ("system-property".equals(s)) {
            return property(PROP_DIR);
        }
        if ("env".equals(s)) {
            return environment(ENV_DIR);
        }
        return "";
    }

    /**
     * 当前生效的根目录（可能带尾斜杠，不要直接拼字符串，用 {@link #path(String)}）。
     *
     * <p>等于 {@link #roots()} 的第一项。
     */
    public static String root() {
        return roots().get(0);
    }

    /**
     * 当前根目录的<b>来源</b>，只用于日志/诊断。
     *
     * @return {@code "explicit"} / {@code "system-property"} / {@code "env"} / {@code "default"}
     */
    public static String rootSource() {
        String s = primaryRootSource();
        return s.isEmpty() ? "default" : s;
    }

    /**
     * 全部候选根目录，顺序即优先级：
     * <ol>
     *   <li>有显式配置（{@link #setRoot} / {@link #PROP_DIR} / {@link #ENV_DIR}）⇒ <b>只用它</b></li>
     *   <li>否则：{@link #DEFAULT_ROOT}（{@code /sdcard/}，Android 语义）</li>
     *   <li>再否则：{@link #hostRoot()}（宿主数据目录，桌面端唯一可用的一层）</li>
     * </ol>
     *
     * <p>为什么桌面端需要第 3 项：fmplayer.app 的 gateway.jar 跑在 JVM 里，没有
     * {@code /sdcard}，它把数据目录通过 {@code --data} 交给
     * {@code com.github.catvod.utils.Path}（实测 {@code Path.root()} =
     * {@code ~/.gateway}，app 侧是 {@code ~/Library/Application Support/fmplayer/gateway-data}）。
     * 因此把 cookie 放到 {@code <宿主数据目录>/TVBox/quark_cookie.txt} 就能生效。
     *
     * @return 非空列表
     */
    public static List<String> roots() {
        List<String> out = new ArrayList<>();
        String primary = primaryRoot();
        if (!TextUtils.isEmpty(primary)) {
            out.add(primary);
            return out;
        }
        out.add(DEFAULT_ROOT);
        String h = hostRoot();
        if (!TextUtils.isEmpty(h) && !h.equals(trimSlash(DEFAULT_ROOT))) {
            out.add(h);
        }
        return out;
    }

    /** 宿主数据目录（反射读取，缓存）；反射不到返回空串 */
    private static String hostRoot = null;
    /** 是否已经尝试过反射（避免每次调用都做 Class.forName） */
    private static boolean hostRootResolved = false;

    /**
     * 宿主提供的<b>数据目录</b>：反射调用 {@code com.github.catvod.utils.Path.root()}。
     *
     * <p>反射而不是硬依赖：本项目只依赖宿主契约 {@code com.github.catvod.crawler.Spider}，
     * 直接 import {@code Path} 会让 jar 在"没有这个类的宿主"上直接 {@code NoClassDefFoundError}。
     * 拿不到（类不存在/方法不存在/受限 JVM）时返回空串，行为退回 {@code /sdcard}。
     *
     * @return 绝对路径；不可用返回空串
     */
    public static String hostRoot() {
        if (hostRootResolved) {
            return hostRoot == null ? "" : hostRoot;
        }
        hostRootResolved = true;
        try {
            Class<?> c = Class.forName("com.github.catvod.utils.Path");
            Object f = c.getMethod("root").invoke(null);
            if (f instanceof File) {
                hostRoot = ((File) f).getAbsolutePath();
                Log.i(TAG, "host root = " + hostRoot);
            }
        } catch (Throwable e) {
            Log.i(TAG, "host Path.root() unavailable: " + e);
            hostRoot = null;
        }
        return hostRoot == null ? "" : hostRoot;
    }

    /** 重置宿主目录缓存（测试用） */
    public static void resetHostRootForTest() {
        hostRootResolved = false;
        hostRoot = null;
    }

    /**
     * 可写根目录 —— 写缓存文件（{@code qktime} 之类）用。
     *
     * <p>有显式配置就用它；否则优先 {@link #DEFAULT_ROOT}（Android 侧 {@code /sdcard}
     * 存在且可写，保持上游语义），再退到 {@link #hostRoot()}（桌面端唯一可写的位置）。
     */
    private static String writableRoot() {
        String primary = primaryRoot();
        if (!TextUtils.isEmpty(primary)) {
            return primary;
        }
        if (new File(trimSlash(DEFAULT_ROOT)).isDirectory()) {
            return DEFAULT_ROOT;
        }
        String h = hostRoot();
        return TextUtils.isEmpty(h) ? DEFAULT_ROOT : h;
    }

    /** 读系统属性，任何异常（Android 受限 JVM 会抛 SecurityException）都返回空串 */
    private static String property(String key) {
        try {
            String v = System.getProperty(key);
            return v == null ? "" : v.trim();
        } catch (Throwable e) {
            Log.w(TAG, "read property failed: " + key + " -> " + e);
            return "";
        }
    }

    /** 读环境变量，异常一律返回空串 */
    private static String environment(String key) {
        try {
            String v = System.getenv(key);
            return v == null ? "" : v.trim();
        } catch (Throwable e) {
            Log.w(TAG, "read env failed: " + key + " -> " + e);
            return "";
        }
    }

    /**
     * 拼一个主目录下的文件路径（用于写缓存），斜杠归一化。
     *
     * <p>根目录取 {@link #writableRoot()}（而不是读顺序的第一个）—— 桌面端
     * {@code /sdcard} 不存在，写在那里必然失败。
     *
     * @param fileName 文件名
     * @return {@code <writableRoot>/TVBox/<fileName>}
     */
    public static String path(String fileName) {
        String r = trimSlash(writableRoot());
        return r + "/" + DIR_MAIN + "/" + (fileName == null ? "" : fileName);
    }

    /**
     * 候选路径列表，顺序即优先级：对每个根目录，主目录（{@code TVBox}）在前、兜底目录（{@code TV}）在后。
     *
     * <p>根目录顺序见 {@link #roots()}。默认（无显式配置）时是
     * {@code [/sdcard/TVBox, /sdcard/TV, <宿主目录>/TVBox, <宿主目录>/TV]}。
     *
     * @param fileName 文件名（如 {@code quark_cookie.txt}）
     * @return 非 null 列表；全空入参返回空列表
     */
    public static List<String> candidatePaths(String fileName) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(fileName)) {
            return out;
        }
        for (String r0 : roots()) {
            String r = trimSlash(r0);
            out.add(r + "/" + DIR_MAIN + "/" + fileName);
            out.add(r + "/" + DIR_FALLBACK + "/" + fileName);
        }
        return out;
    }

    /** 去掉尾部斜杠（{@code "/sdcard/"} → {@code "/sdcard"}）；空串原样返回 */
    private static String trimSlash(String r) {
        if (TextUtils.isEmpty(r)) {
            return "";
        }
        return r.endsWith("/") ? r.substring(0, r.length() - 1) : r;
    }

    /**
     * 读一份凭据 —— 还原上游 {@code checkquarkcookie()} 的"主文件 JSON / 兜底裸文本"两级降级，
     * 并把它推广到 {@link #candidatePaths(String)} 的<b>全部</b>候选路径。
     *
     * <p>逐个候选文件：内容像 JSON 就取 {@code jsonField} 字段（取不到则看下一个文件），
     * 否则按裸文本整份返回（trim 后）。<b>先命中即返回。</b>
     *
     * @param fileName  文件名
     * @param jsonField 主文件的 JSON 字段名（如 {@code cookie}）
     * @return 凭据文本；都没有返回空串（<b>不抛异常</b>）
     */
    public static String read(String fileName, String jsonField) {
        for (String p : candidatePaths(fileName)) {
            String text = readFile(p).trim();
            if (TextUtils.isEmpty(text)) {
                continue;
            }
            if (text.startsWith("{")) {
                String v = field(text, jsonField);
                if (!TextUtils.isEmpty(v)) {
                    Log.i(TAG, "credential from json field: " + p);
                    return v;
                }
                // 是 JSON 但没有该字段 ⇒ 不是凭据文件，继续看下一个
                continue;
            }
            Log.i(TAG, "credential from raw file: " + p);
            return text;
        }
        return "";
    }

    /**
     * 只读主目录里的 JSON 文件并取字段 —— 上游 {@code new JSONObject(read(f)).optString(field,"").trim()}。
     *
     * <p>任何异常（文件不存在、非法 JSON、字段缺失）都返回空串。
     *
     * @param fileName  文件名
     * @param jsonField 字段名
     * @return 字段值（trim 后）；失败空串
     */
    public static String readJsonField(String fileName, String jsonField) {
        String text = readFile(path(fileName)).trim();
        return field(text, jsonField);
    }

    /** 从 JSON 文本里取字符串字段；不是 JSON / 没有该字段都返回空串 */
    private static String field(String text, String jsonField) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        try {
            String v = new JSONObject(text.trim()).optString(jsonField, "").trim();
            return v == null ? "" : v;
        } catch (Throwable e) {
            Log.w(TAG, "field: not a JSON object (len=" + text.length() + ")");
            return "";
        }
    }

    /**
     * 读整个文件（UTF-8）。
     *
     * @param path 绝对路径
     * @return 内容；不存在/不可读返回空串
     */
    public static String readFile(String path) {
        if (TextUtils.isEmpty(path)) {
            return "";
        }
        RandomAccessFile raf = null;
        try {
            File f = new File(path);
            if (!f.isFile()) {
                return "";
            }
            raf = new RandomAccessFile(f, "r");
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            return new String(buf, Charset.forName("UTF-8"));
        } catch (Throwable e) {
            Log.w(TAG, "readFile failed: " + path + " -> " + e);
            return "";
        } finally {
            close(raf);
        }
    }

    /**
     * 写整个文件（UTF-8），自动建父目录 —— 还原上游 {@code k.c(File, String)}。
     *
     * @param path 绝对路径
     * @param text 内容
     * @return 是否写入成功
     */
    public static boolean writeFile(String path, String text) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        FileOutputStream fos = null;
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w(TAG, "writeFile: mkdirs failed: " + parent);
            }
            fos = new FileOutputStream(f);
            fos.write((text == null ? "" : text).getBytes(Charset.forName("UTF-8")));
            fos.flush();
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "writeFile failed: " + path + " -> " + e);
            return false;
        } finally {
            close(fos);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
