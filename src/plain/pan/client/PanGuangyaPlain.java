package com.example.tvbox.plain.pan.client;

import android.content.Context;
import android.util.Log;

/**
 * 光鸭云盘客户端浏览源。
 *
 * <p>浏览用户自己 光鸭 账号里的文件。需要登录态。
 *
 * <p>⚠️ <b>本类仅提供框架与降级行为，未实现真实 OAuth / API 调用。</b>
 * 上游原 Java 已在线上验证，建议保留。详见 {@link PanClientBase} 类注释。
 *
 * <h2>登录态文件</h2>
 * <pre>{@code guangya.txt}</pre>
 *
 * <h2>上游 API（未实现，供将来接入参考）</h2>
 * <pre>{@code api.guangyapan.com/userres/v1/file/get_file_list}</pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>光鸭（GuangYa）的登录/OAuth 未实现</b>（{@code PanGuangya.java}）：
 *       只读 {@code guangya.txt} 的凭据。</li>
 *   <li><b>{@code playerContent} 不出直链</b>：光鸭的直链需要带 {@code signature}
 *       的接口，本类只返回空响应。</li>
 *   <li><b>{@code action(String)} 未实现</b>（宿主 UI/下载器职责）。</li>
 *   <li>上游 {@code Pan.playerContent} 里光鸭分支是 {@code G.r().N(...)}
 *       （{@code Pan.java:527} 附近），说明上游把它做成<b>单例 + 分享解析</b>，
 *       本类未还原该单例形态（本工程用实例 + resolver）。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>{@code G.r()} 单例</b>：上游把光鸭解析器做成静态单例并在
 *       {@code playerContent} 里直接调用，多个站点共享同一份状态（含 token），
 *       与"每站点独立 ext"的模型冲突。→ 本工程改为实例 + {@link PanPlain} 的
 *       resolver 注册表（{@code PanPlain.registerResolver}），
 *       断言见 {@code PanTest#testResolverDispatch()}。</li>
 *   <li>凭据文件 {@code guangya.txt} 的读取与 {@code UCQuark} 同构但代码独立
 *       （又一份拷贝）⇒ 三级降级逻辑在工程里出现了三遍。
 *       → 本工程统一到 {@link PanCreds}。</li>
 *   <li>光鸭的分享 ID 与提取码字段名（{@code ?code=} 而非 {@code ?pwd=}）
 *       在上游正则里单独写了一条；本工程由 {@code PanPlain.RE_GUANGYA} 覆盖，
 *       注意它的提取码参数名与其它网盘不同（易错点）。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/PanGuangya.java}
 */
public class PanGuangyaPlain extends PanClientBase {

    private static final String TAG = "PanGuangyaPlain";

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        // 上游从本地 cookie 文件读取登录态；本实现不做真实校验
        loggedIn = false;
        accountName = "我的光鸭网盘";
        Log.i(TAG, "init: loggedIn=" + loggedIn
                + "（未实现 OAuth，详见 PanClientBase 注释）");
    }

    @Override protected String cookieFileName() { return "guangya.txt"; }

    @Override protected String panName() { return "光鸭"; }
}
