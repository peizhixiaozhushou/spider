package com.example.tvbox.plain.pan.client;

import android.content.Context;
import android.util.Log;

/**
 * 123云盘客户端浏览源。
 *
 * <p>浏览用户自己 123 账号里的文件。需要登录态。
 *
 * <p>⚠️ <b>本类仅提供框架与降级行为，未实现真实 OAuth / API 调用。</b>
 * 上游原 Java 已在线上验证，建议保留。详见 {@link PanClientBase} 类注释。
 *
 * <h2>登录态文件</h2>
 * <pre>{@code cloud123.txt}</pre>
 *
 * <h2>上游 API（未实现，供将来接入参考）</h2>
 * <pre>{@code www.123pan.cn/b/api/file/list/new}</pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>只是"123 云盘"的另一套凭据入口</b>：本类与 {@link Pan123Plain} 同一个网盘，
 *       读的是同一份凭据；<b>真正的接口逻辑在 {@link Pan123Plain} 与基类里</b>，
 *       本类没有独立协议。因此"未覆盖的分支"与 {@link Pan123Plain} 相同：
 *       真实 OAuth、直链 {@code playerContent}、宿主 {@code action(String)} 下载安装。</li>
 *   <li>{@code homeContent} / {@code categoryContent} 的<b>站点自定义入口</b>
 *       （上游 {@code Pan123} 里的固定分享或分类）未还原 —— 本类只给
 *       「未登录 / 我的 123 网盘」两态（见 {@code PanClientBase}）。</li>
 *   <li>上游若有"离线下载/秒传"入口，本类未还原（只做浏览）。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>同类两名</b>：上游同时存在 {@code Pan123.java} 与 {@code C123}.class-like
 *       的两个入口（本工程对应 {@code C123Plain} 与 {@code Pan123Plain}），
 *       基类不同、职责重叠，容易误认为"一个是被调用的、一个是死代码"。
 *       → 本工程保留两个类，并在两者 javadoc 里互相注明"同一网盘的两个入口，
 *       凭据文件相同（{@code cloud123.txt}）"。判定依据见 {@link Pan123Plain}。</li>
 *   <li>凭据读取的第三来源 {@code k.b("/cloud123.txt")}（外部存储）没有做存在性/格式校验，
 *       读到半截 JSON 就抛。→ 本类经 {@link PanCreds#read(String, String)} 读取，
 *       先试 JSON 字段、再试裸文本，两处都不命中即视为未登录（不抛）。</li>
 *   <li>上游把 {@code 123} 是"文件名还是分享"混在一个字段里传递，本类改为显式语义。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Pan123.java}
 */
public class C123Plain extends PanClientBase {

    private static final String TAG = "C123Plain";

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        // 上游从本地 cookie 文件读取登录态；本实现不做真实校验
        loggedIn = false;
        accountName = "我的123网盘";
        Log.i(TAG, "init: loggedIn=" + loggedIn
                + "（未实现 OAuth，详见 PanClientBase 注释）");
    }

    @Override protected String cookieFileName() { return "cloud123.txt"; }

    @Override protected String panName() { return "123"; }
}
