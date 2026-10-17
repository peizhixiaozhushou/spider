package com.example.tvbox.plain.pan;

import android.text.TextUtils;

import com.example.tvbox.plain.pan.client.PanCreds;

import java.io.File;

/**
 * 维护例程的本地缓存（键 → 小文本文件）。
 *
 * <p>还原上游 {@code merge.p038m.k} 的三个方法：
 * <pre>
 *   k.d(String name)  → File     取 cache 目录下的文件句柄
 *   k.a(File f)       → String   整份读（UTF-8）
 *   k.c(File f, String text)     整份写（UTF-8，自动建父目录）
 * </pre>
 * 上游把 {@code qktime / uctime / deleteqktime / deleteuctime / xltime} 这类
 * 「上次跑成功的日期」写在这里，用来做每日/每 7 天的节流。
 *
 * <h2>为什么单独抽出来</h2>
 * {@code QuarkPan} / {@code UCPan} / {@code XunleiPan} 三个类都有这一模块，
 * 而它们分属两套协议（ucpro 与迅雷风控），不该互相继承。抽成独立小类即可共享，
 * 也让 {@code setCacheDirForTest} 只有一个实现。
 *
 * <h2>与上游的差异</h2>
 * 上游用 Android 的 {@code context.getCacheDir()}；本工程没有 Context 依赖，
 * 因此根目录默认取 {@code System.getProperty("java.io.tmpdir")}（设备上通常是
 * {@code /data/local/tmp}）。<b>这只是落盘位置不同，语义一致</b>；
 * 调用方可用构造参数指向任意目录（测试就是这么做的）。
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/QuarkPan.java}(L108-110, L91, L157) /
 * {@code ae/decompiled/UCPan.java}(L105-107, L88, L154) /
 * {@code ae/decompiled/XunleiPan.java}(L53-55, L80)。
 */
public final class MaintCache {

    /** 默认根目录名（与 {@code pan.client} 层的临时目录区分开） */
    public static final String DEFAULT_DIR_NAME = "tvbox-cache";

    /** 缓存目录 */
    private final File dir;

    /**
     * 构造。
     *
     * @param baseDir 根目录；空则用 {@code java.io.tmpdir}
     */
    public MaintCache(String baseDir) {
        String base = TextUtils.isEmpty(baseDir)
                ? System.getProperty("java.io.tmpdir", "/data/local/tmp")
                : baseDir;
        this.dir = new File(base, DEFAULT_DIR_NAME);
    }

    /** 缓存目录（可能尚不存在） */
    public File dir() {
        return dir;
    }

    /**
     * 取文件句柄 —— 对应 {@code k.d(name)}。
     *
     * @param name 缓存名
     * @return 文件；name 为空返回 null
     */
    public File file(String name) {
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        return new File(dir, name);
    }

    /**
     * 读缓存 —— 对应 {@code k.a(getCache(name))}。
     *
     * @param name 缓存名
     * @return 内容（已 trim）；不存在/不可读返回空串
     */
    public String read(String name) {
        File f = file(name);
        return f == null ? "" : PanCreds.readFile(f.getAbsolutePath()).trim();
    }

    /**
     * 写缓存 —— 对应 {@code k.c(getCache(name), text)}。
     *
     * @param name 缓存名
     * @param text 内容
     * @return 是否成功
     */
    public boolean write(String name, String text) {
        File f = file(name);
        if (f == null) {
            return false;
        }
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        return PanCreds.writeFile(f.getAbsolutePath(), text);
    }
}
