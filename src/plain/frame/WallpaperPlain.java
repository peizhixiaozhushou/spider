package com.example.tvbox.plain.frame;

import android.util.Log;

import com.example.tvbox.common.BaseSpider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * 随机壁纸 —— 图片代理型源。
 *
 * <p>不是内容源，只提供一个 {@code proxy} 接口随机返回一张壁纸图。
 * 上游按「是否电视设备」选 PC 或手机壁纸分类。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  TV 设备 : https://uapis.cn/api/v1/random/image?category=pc_wallpaper
 *  手机设备: https://uapis.cn/api/v1/random/image?category=mobile_wallpaper
 *  探测方式: PackageManager.hasSystemFeature("android.hardware.type.television")
 *  超时    : 连接/读取各 10 秒
 *  返回    : Object[]{ 200, "image/jpeg", InputStream }
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Wallpaper.java}（40 行）
 
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>网络取图</b>：{@code proxy(Map)} 现场 {@code HttpURLConnection} 拉图；
 *       纯 JVM 下无网络，测试只断言"失败时返回空流而不是 null"这条兜底。</li>
 *   <li><b>电视/手机分支</b>：{@code isTvDevice()} 用
 *       {@code hasSystemFeature("android.hardware.type.television")}；无 Context 时
 *       恒走手机分支（测试覆盖了"无 Context"这一支）。</li>
 *   <li><b>MIME 固定为 jpeg</b>：站点返回 png/webp 时 Content-Type 仍然是
 *       {@code image/jpeg}（上游同样不探测）。</li>
 *   <li><b>协议方法留空是设计</b>：本类只有 {@code proxy}，
 *       {@code homeContent/searchContent/detailContent/playerContent} 返回空壳
 *       —— 壁纸不是内容源。</li>
 * </ol>
 *
 * <h2>记录的上游缺陷 / 硬编码异味</h2>
 * <ol>
 *   <li><b>失败吞成空流</b>：取图失败返回 0 字节的 {@code InputStream} + 状态码 200，
 *       宿主会拿到"一张空图"而不是错误码（上游原文如此，本类保持并注释）。</li>
 *   <li><b>两个硬编码图片地址</b>：PC/手机各一个固定 URL，站点换路径就全白。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/Wallpaper.java}（40 行；
 * {@code proxy} 的 3 元素返回数组与 {@code hasSystemFeature} 判定）。
*/
public class WallpaperPlain extends BaseSpider {

    private static final String TAG = "WallpaperPlain";

    private static final String URL_TV   = "https://uapis.cn/api/v1/random/image?category=pc_wallpaper";
    private static final String URL_PHONE= "https://uapis.cn/api/v1/random/image?category=mobile_wallpaper";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 10000;

    @Override
    public String homeContent(boolean filter) { return emptyResponse(); }

    @Override
    public String searchContent(String key, boolean quick) { return emptyResponse(); }

    @Override
    public String detailContent(List<String> ids) { return emptyResponse(); }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) { return emptyResponse(); }

    /**
     * 图片代理 —— 还原上游 {@code Wallpaper.proxy(Map)}。
     *
     * @return {@code Object[]{200, "image/jpeg", InputStream}}；
     *         失败时 InputStream 为空流（与上游一致，不返回 null）
     */
    @Override
    public Object[] proxy(Map<String, String> params) {
        Object[] out = new Object[3];
        out[0] = 200;
        out[1] = "image/jpeg";

        String url = isTvDevice() ? URL_TV : URL_PHONE;
        InputStream in;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            in = conn.getInputStream();
        } catch (Exception e) {
            Log.w(TAG, "proxy: fetch failed for " + url + ": " + e);
            in = new ByteArrayInputStream(new byte[0]);
        }
        out[2] = in;
        return out;
    }

    /** 是否电视设备（上游用 hasSystemFeature 判断） */
    public boolean isTvDevice() {
        try {
            if (mContext == null) return false;
            android.content.pm.PackageManager pm = mContext.getPackageManager();
            return pm != null && pm.hasSystemFeature("android.hardware.type.television");
        } catch (Exception e) {
            return false;
        }
    }
}
