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
 * <h2>可测试性设计</h2>
 * {@code android.os.Environment} 在重写工程中不可用（stub 未提供），且真实
 * {@code /sdcard} 在 CI 上不存在。因此根目录做成可注入：
 * <ul>
 *   <li>默认 {@link #DEFAULT_ROOT} = {@code /sdcard}（与上游一致）</li>
 *   <li>{@link #setRootForTest(String)} 指向临时目录，测试即可构造真实文件走全路径</li>
 * </ul>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/QuarkPan.java}(L41-52) /
 * {@code ae/decompiled/UCPan.java}(L50-61) /
 * {@code ae/decompiled/Pan123.java} + {@code merge.b.m}（{@code cloud123.txt}）。
 * 文件名常量由异或表解出，见 {@code ae/work/w-pan/decoded-strings.txt}。
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

    /** 默认根目录；测试可覆盖 */
    private static String root = DEFAULT_ROOT;

    private PanCreds() {
    }

    /** 覆盖根目录（测试用）。传 null/空 表示恢复默认。 */
    public static void setRootForTest(String r) {
        root = TextUtils.isEmpty(r) ? DEFAULT_ROOT : r;
    }

    /** 当前根目录（可能带尾斜杠，不要直接拼字符串，用 {@link #path(String)}） */
    public static String root() {
        return root;
    }

    /**
     * 拼一个主目录下的文件路径，斜杠归一化。
     *
     * @param fileName 文件名
     * @return {@code <root>/TVBox/<fileName>}（根目录带不带尾斜杠都正确）
     */
    public static String path(String fileName) {
        String r = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        return r + "/" + DIR_MAIN + "/" + (fileName == null ? "" : fileName);
    }

    /**
     * 候选路径列表，顺序即优先级：主目录在前，兜底目录在后。
     *
     * @param fileName 文件名（如 {@code quark_cookie.txt}）
     * @return 非 null 列表；全空入参返回空列表
     */
    public static List<String> candidatePaths(String fileName) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(fileName)) {
            return out;
        }
        out.add(path(fileName));
        String r = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        out.add(r + "/" + DIR_FALLBACK + "/" + fileName);
        return out;
    }

    /**
     * 读一份凭据 —— 还原上游 {@code checkquarkcookie() / checkuccookie()} 的两级降级。
     *
     * <p>主文件存在时按 JSON 解析取 {@code jsonField}；解析不出（或字段为空）才看兜底文件。
     * 兜底文件按裸文本整份返回（trim 后）。
     *
     * @param fileName  文件名
     * @param jsonField 主文件的 JSON 字段名（如 {@code cookie}）
     * @return 凭据文本；都没有返回空串（<b>不抛异常</b>）
     */
    public static String read(String fileName, String jsonField) {
        String fromJson = readJsonField(fileName, jsonField);
        if (!TextUtils.isEmpty(fromJson)) {
            return fromJson;
        }
        // 上游: if (cookie == null) → 试兜底裸文件
        List<String> paths = candidatePaths(fileName);
        if (paths.size() >= 2) {
            String raw = readFile(paths.get(1));
            if (!TextUtils.isEmpty(raw)) {
                Log.i(TAG, "credential from fallback raw file: " + paths.get(1));
                return raw.trim();
            }
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
        List<String> paths = candidatePaths(fileName);
        if (paths.isEmpty()) {
            return "";
        }
        String text = readFile(paths.get(0));
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        try {
            String v = new JSONObject(text.trim()).optString(jsonField, "").trim();
            return v == null ? "" : v;
        } catch (Throwable e) {
            Log.w(TAG, "readJsonField: not a JSON file: " + paths.get(0));
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
