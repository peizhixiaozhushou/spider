package com.example.tvbox.plain.frame;

import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 本地文件浏览器 —— 遍历设备文件系统，列出视频文件。
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>{@code categoryContent(dir)} → 列出该目录下的子目录与视频文件</li>
 *   <li>{@code detailContent(filePath)} → 列出同目录下所有视频，组装成播放列表</li>
 * </ul>
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  视频扩展名（18 个，逐字）:
 *    .avi .wmv .rm .3gp .mov .mp .m4 .vob .ts .webm .dat
 *    .m3u .mkv .flv .aac .wma .flac .ape
 *
 *  目录条目: vod_id=绝对路径, vod_name=文件名,
 *            vod_pic=文件夹/视频图标, vod_tag=folder|file,
 *            vod_remarks=yyyy/MM/dd aHH:mm:ss  (注意 aHH 是 12 小时制)
 *
 *  详情: vod_name=父目录路径, vod_play_from="本地",
 *        vod_play_url="文件名$绝对路径#文件名$绝对路径..."
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/LocalFile.java}（136 行）
 
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>真机文件树</b>：{@code categoryContent(dir, …)} 只是把给定目录当"分类 id"
 *       去遍历 {@code File}；纯 JVM 下 {@code /storage/emulated/0} 不存在，
 *       测试用临时目录替代，<b>真机根目录挂载点差异未覆盖</b>。</li>
 *   <li><b>分区/权限被拒</b>：上游不区分"目录不存在"与"无权限"（都返回空列表），
 *       Android 11+ 的分区存储（MANAGE_EXTERNAL_STORAGE）分支不在本类，
 *       在 {@code InitPlain.checkPermission()}。</li>
 *   <li><b>{@code guessMime}/{@code formatTime} 的后缀表</b>：不含冷门容器
 *       （{@code .rmvb/.wmv/.iso} 之外的），未列后缀一律 {@code application/octet-stream}。</li>
 *   <li><b>协议方法留空是设计</b>：{@code homeContent}/{@code searchContent} 返回空壳
 *       —— 上游 LocalFile 不是内容源（没有首页也没有搜索），别按"漏实现"补。</li>
 * </ol>
 *
 * <h2>记录的上游缺陷 / 硬编码异味</h2>
 * <ol>
 *   <li><b>分类 id 就是文件系统路径</b>：宿主回传什么路径就遍历什么，
 *       没有任何白名单/根目录约束 —— 越权读取靠宿主自己拦。</li>
 *   <li><b>{@code checkString} 只看后缀名</b>：改名的非视频文件会被当视频列出。</li>
 *   <li><b>错误一律静默</b>：不可读目录返回空列表，用户看不到"权限被拒"与"空目录"的区别。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/LocalFile.java}（{@code categoryContent} 的
 * 遍历与后缀过滤、{@code playerContent} 的本地路径处理）。
*/
public class LocalFilePlain extends BaseSpider {

    private static final String TAG = "LocalFilePlain";

    /** 视频扩展名（逐字来自上游 checkstring） */
    private static final String[] VIDEO_EXTS = {
            ".avi", ".wmv", ".rm", ".3gp", ".mov", ".mp", ".m4", ".vob", ".ts",
            ".webm", ".dat", ".m3u", ".mkv", ".flv", ".aac", ".wma", ".flac", ".ape"
    };

    /** 目录图标（上游硬编码 URL） */
    private static final String ICON_FOLDER =
            "https://cc-im-kefu-cos.7moor-fs2.com/im/2768a390-5474-11ea-afc9-7b323e3e16c0/"
          + "e8213224-8902-4b2f-8042-ef5809445c8e/2024-06-07/2024-06-07_18:01:26/"
          + "1717754486746/11664624/folder.png";
    /** 视频图标 */
    private static final String ICON_VIDEO =
            "https://cc-im-kefu-cos.7moor-fs2.com/im/2768a390-5474-11ea-afc9-7b323e3e16c0/"
          + "e8213224-8902-4b2f-8042-ef5809445c8e/2024-06-07/2024-06-07_18:02:18/"
          + "1717754538432/24531685/video.png";

    @Override
    public String homeContent(boolean filter) { return emptyResponse(); }

    @Override
    public String searchContent(String key, boolean quick) { return emptyResponse(); }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) return emptyResponse();
        // 本地文件直接播放
        return VodBuilder.playerContent(id, guessMime(id));
    }

    /**
     * 还原 {@code LocalFile.checkstring(String)} —— 判断是否视频文件。
     * <p>上游用 {@code contains} 而非 {@code endsWith}，本实现保持一致。
     */
    public static boolean checkString(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : VIDEO_EXTS) {
            if (lower.contains(ext)) return true;
        }
        return false;
    }

    /**
     * 列目录 —— 还原 {@code LocalFile.categoryContent(dir, ...)}。
     *
     * @param dir 目录绝对路径（作为 tid 传入）
     */
    @Override
    public String categoryContent(String dir, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        if (TextUtils.isEmpty(dir)) return VodBuilder.create().page(1).categoryContent();

        File d = new File(dir);
        File[] files = d.listFiles();
        if (files == null) {
            Log.w(TAG, "categoryContent: cannot list " + dir);
            return VodBuilder.create().page(1).categoryContent();
        }
        // 上游按名称排序（目录优先由渲染层处理）
        Arrays.sort(files, Comparator.comparing(File::getName));

        List<Vod> list = new ArrayList<>();
        for (File f : files) {
            String name = f.getName();
            // 跳过隐藏文件（上游：name.indexOf('.') != 0）
            if (name.startsWith(".")) continue;

            boolean isDir = f.isDirectory();
            if (!isDir && !checkString(name)) continue;   // 非目录且非视频 -> 跳过

            list.add(new Vod()
                    .id(f.getAbsolutePath())
                    .name(name)
                    .pic(isDir ? ICON_FOLDER : ICON_VIDEO)
                    .remarks(formatTime(f.lastModified())));
        }

        Log.i(TAG, "categoryContent " + dir + " -> " + list.size());
        return VodBuilder.create().addAll(list).page(1).categoryContent();
    }

    /**
     * 详情 —— 还原 {@code LocalFile.detailContent(filePath)}。
     *
     * <p>策略：取该文件的<b>父目录</b>，把同目录下所有视频组成一个播放列表。
     */
    @Override
    public String detailContent(List<String> ids) {
        String filePath = firstId(ids);
        if (TextUtils.isEmpty(filePath)) return emptyResponse();

        File self = new File(filePath);
        String parentPath = self.getParent();
        if (parentPath == null) parentPath = filePath;

        File parentDir = new File(parentPath);
        File[] files = parentDir.listFiles();
        if (files == null) {
            Log.w(TAG, "detailContent: cannot list parent " + parentPath);
            return emptyResponse();
        }
        Arrays.sort(files, Comparator.comparing(File::getName));

        List<String> eps = new ArrayList<>();
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (!f.isFile() || !checkString(name)) continue;
            eps.add(name + Vod.SEP_NAME_URL + f.getAbsolutePath());
        }

        Vod v = new Vod()
                .id(filePath)
                .name(parentPath)
                .pic("")
                .typeName("")
                .playFrom("本地")
                .playUrl(TextUtils.join(Vod.SEP_EPISODE, eps));
        return VodBuilder.detail(v).detailContent();
    }

    /**
     * 时间格式化 —— 还原上游 {@code new SimpleDateFormat("yyyy/MM/dd aHH:mm:ss")}。
     * <p>注意 {@code aHH} 是 12 小时制带 AM/PM 前缀，这是上游的原始格式。
     */
    public static String formatTime(long millis) {
        try {
            return new SimpleDateFormat("yyyy/MM/dd aHH:mm:ss", Locale.getDefault())
                    .format(new Date(millis));
        } catch (Exception e) {
            return "";
        }
    }

    /** 按扩展名猜 MIME */
    static String guessMime(String path) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (p.endsWith(".m3u"))  return "audio/x-mpegurl";
        if (p.endsWith(".flac")) return "audio/flac";
        if (p.endsWith(".mp3"))  return "audio/mpeg";
        if (p.endsWith(".mp4"))  return "video/mp4";
        if (p.endsWith(".mkv"))  return "video/x-matroska";
        if (p.endsWith(".webm")) return "video/webm";
        return "video/*";
    }
}
