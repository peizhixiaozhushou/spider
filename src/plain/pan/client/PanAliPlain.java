package com.example.tvbox.plain.pan.client;

import android.content.Context;
import android.util.Log;

/**
 * 阿里云盘客户端浏览源。
 *
 * <p>浏览用户自己 阿里 账号里的文件。需要登录态。
 *
 * <p>⚠️ <b>本类仅提供框架与降级行为，未实现真实 OAuth / API 调用。</b>
 * 上游原 Java 已在线上验证，建议保留。详见 {@link PanClientBase} 类注释。
 *
 * <h2>登录态文件</h2>
 * <pre>{@code aliyun.txt}</pre>
 *
 * <h2>上游 API（未实现，供将来接入参考）</h2>
 * <pre>{@code openapi.alipan.com/adrive/v1.0/openFile/list}</pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>阿里云盘的 OAuth 状态机整段未实现</b>：上游 {@code PanAli.java}（170 行）
 *       的登录、{@code refresh_token} 换 {@code access_token}、以及
 *       「扫码 → 回调 → 写 {@code aliyun.txt}」的闭环，本类全都没有。
 *       理由：这套流程依赖宿主"配置中心"提供的回调与浏览器跳转，静态重写没有意义；
 *       上游原 Java 已在线上验证可用。</li>
 *   <li><b>{@code playerContent} 不出直链</b>：阿里转直链需要
 *       {@code X-Share-Token}/{@code signature} 等签名头，本类只返回空响应。</li>
 *   <li><b>{@code action(String)} 未实现</b>（宿主 UI/下载器职责）。</li>
 *   <li>阿里特有的<b>转码播放链路</b>（{@code video_preview_play_info} 的清晰度列表）
 *       未还原 —— 这是"多清晰度选线"的来源。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>token 过期后无透明刷新</b>：上游在 {@code access_token} 过期时
 *       只是请求失败，需要用户手动重新登录；{@code refresh_token} 有但用得不完整
 *       （无法从代码判断失败后是否会重试）。→ 本类显式返回"未登录"语义，
 *       由宿主提示用户，不做静默重试（避免把失败包成成功）。</li>
 *   <li>凭据文件名与格式（{@code aliyun.txt}）在 {@link PanCreds} 里统一说明；
 *       上游对该文件的 JSON 结构没有任何 schema 校验，缺字段即 NPE。
 *       → 本类逐字段 {@code optString} 取值，缺字段按空处理。</li>
 *   <li>上游把"我的盘"与"分享盘"两种入口混在同一套方法里，靠空串判断分支；
 *       本类用显式的 {@code shareId} 是否为空区分。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/PanAli.java}
 */
public class PanAliPlain extends PanClientBase {

    private static final String TAG = "PanAliPlain";

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        // 上游从本地 cookie 文件读取登录态；本实现不做真实校验
        loggedIn = false;
        accountName = "我的阿里网盘";
        Log.i(TAG, "init: loggedIn=" + loggedIn
                + "（未实现 OAuth，详见 PanClientBase 注释）");
    }

    @Override protected String cookieFileName() { return "aliyun.txt"; }

    @Override protected String panName() { return "阿里"; }
}
