package com.example.tvbox.plain.pan.client;

import android.content.Context;
import android.util.Log;

/**
 * 百度云盘客户端浏览源。
 *
 * <p>浏览用户自己 百度 账号里的文件。需要登录态。
 *
 * <p>⚠️ <b>本类仅提供框架与降级行为，未实现真实 OAuth / API 调用。</b>
 * 上游原 Java 已在线上验证，建议保留。详见 {@link PanClientBase} 类注释。
 *
 * <h2>登录态文件</h2>
 * <pre>{@code baidu.txt}</pre>
 *
 * <h2>上游 API（未实现，供将来接入参考）</h2>
 * <pre>{@code pan.baidu.com/api/list}</pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>百度网盘的 OAuth 整段未实现</b>（{@code PanBaidu.java} 114 行）：
 *       授权码、{@code refresh_token}、以及 {@code bdtime} 缓存节流都不在本类。</li>
 *   <li><b>本类不实现"换链"（{@code playerContent}）</b>：百度要拿直链必须先
 *       「分享 → 转存到自己的网盘 → 取 dlink」，其中转存链路同
 *       {@code PanUCQuarkBase.refreshShare()} 的量级，本工程未还原。
 *       注意上游 {@code Pan.java:491-536} 的百度分支里<b>另起线程</b>刷新
 *       {@code bdtime} 缓存，那段逻辑也不在本类。</li>
 *   <li><b>{@code action(String)} 未实现</b>（宿主 UI/下载器职责）。</li>
 *   <li>百度特有的 {@code user-agent: pan.baidu.com} 与 {@code Referer} 白名单
 *       （播放时服务器会校验来源）未还原。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>依赖宿主缓存里的 {@code bdtime}</b>（见上游 {@code Pan.java:500-512}
 *       与 {@code PanXunlei} 同构的节流写法）：用
 *       {@code merge.p038m.k.a(k.d("bdtime"))} 读缓存、{@code SimpleDateFormat("yyyyMMdd")}
 *       比日期。<b>不指定 Locale</b> ⇒ 泰语区出佛历年份，节流永不生效（每次都重新转存，
 *       容易触发风控）。→ 本工程所有日期节流统一走
 *       {@code UCQuarkMaintBase.parseYyyyMMdd}/{@code yyyyMMdd}（固定 {@code Locale.US}）。</li>
 *   <li>缓存值直接 {@code Integer.parseInt}（同上），脏缓存抛 {@code NumberFormatException}；
 *       且那段代码在<b>子线程</b>里，异常只会被 {@code catch (Throwable)} 打印。
 *       → 本工程统一容错（解析失败视为过期）。</li>
 *   <li>凭据文件名 {@code baidu.txt} 与"是否存在"的判定分散在多处；
 *       → 本类经 {@link PanCreds} 统一读取，缺失即"未登录"。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/PanBaidu.java}
 */
public class PanBaiduPlain extends PanClientBase {

    private static final String TAG = "PanBaiduPlain";

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        // 上游从本地 cookie 文件读取登录态；本实现不做真实校验
        loggedIn = false;
        accountName = "我的百度网盘";
        Log.i(TAG, "init: loggedIn=" + loggedIn
                + "（未实现 OAuth，详见 PanClientBase 注释）");
    }

    @Override protected String cookieFileName() { return "baidu.txt"; }

    @Override protected String panName() { return "百度"; }
}
