package com.example.tvbox.plain.pan.client;

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

    /** 是否已登录（子类在 init 时根据 cookie 判断） */
    protected boolean loggedIn = false;
    /** 账号显示名（如"我的夸克网盘"） */
    protected String accountName = "";

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
