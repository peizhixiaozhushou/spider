package com.example.tvbox.plain.pan.client;

import android.content.Context;
import android.util.Log;

/**
 * 天翼云盘客户端浏览源。
 *
 * <p>浏览用户自己 天翼 账号里的文件。需要登录态。
 *
 * <p>⚠️ <b>本类仅提供框架与降级行为，未实现真实 OAuth / API 调用。</b>
 * 上游原 Java 已在线上验证，建议保留。详见 {@link PanClientBase} 类注释。
 *
 * <h2>登录态文件</h2>
 * <pre>{@code cloud189.txt}</pre>
 *
 * <h2>上游 API（未实现，供将来接入参考）</h2>
 * <pre>{@code cloud.189.cn/api/open/file/listFiles.action}</pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>天翼云盘（189）的 OAuth 未实现</b>（{@code PanTianyi.java}）。
 *       值得注意：天翼的登录流程在四家里最复杂（需要
 *       {@code clientId}/{@code clientSecret} 与设备指纹），本类只读
 *       {@code cloud189.txt}。</li>
 *   <li><b>{@code playerContent} 不出直链</b>：天翼需要
 *       {@code /api/open/file/getFileDownloadUrl} 一类带 sessionKey 的接口，
 *       本类只返回空响应。</li>
 *   <li><b>{@code action(String)} 未实现</b>（宿主 UI/下载器职责）。</li>
 *   <li>天翼的<b>个人盘 / 家庭盘</b>两种空间在 {@code Pan.java} 的分类里是分开的
 *       （{@code cloud189} 与 {@code mobile} 识别项），本类未区分这两种空间。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>上游把 {@code cloud.189.cn} 与 {@code cloud.189.com} 两种域名写进同一条正则</b>
 *       （{@code Pan.java:45}），而 {@code .cn} 与 {@code .com} 的接口域并不总是一致；
 *       本工程沿用该正则（逐字保留，避免与线上不一致），
 *       但在 {@link PanPlain} 里注明"域名后缀不区分"是上游行为。</li>
 *   <li>天翼分享链接的提取码有两种写法（{@code ?pwd=} 与中文括号
 *       「（访问码：xxxx）」），上游正则用交替分支兼容；本工程逐字保留，
 *       并在 {@link PanPlain} 的 javadoc 里点名 —— 改这条正则前先确认线上两种都在用。</li>
 *   <li>凭据文件 {@code cloud189.txt} 的读取又是一份独立拷贝。
 *       → 统一到 {@link PanCreds}（主目录 JSON → 兜底目录裸文本）。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/PanTianyi.java}
 */
public class PanTianyiPlain extends PanClientBase {

    private static final String TAG = "PanTianyiPlain";

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        // 上游从本地 cookie 文件读取登录态；本实现不做真实校验
        loggedIn = false;
        accountName = "我的天翼网盘";
        Log.i(TAG, "init: loggedIn=" + loggedIn
                + "（未实现 OAuth，详见 PanClientBase 注释）");
    }

    @Override protected String cookieFileName() { return "cloud189.txt"; }

    @Override protected String panName() { return "天翼"; }
}
