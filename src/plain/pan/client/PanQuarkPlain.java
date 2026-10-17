package com.example.tvbox.plain.pan.client;

/**
 * 夸克网盘客户端浏览源 —— 品牌常量层。
 *
 * <p><b>本类替换了原先 40 行的降级桩</b>（原桩 {@code listDir} 永远返回空）。
 * 所有协议逻辑在 {@link PanUCQuarkBase}（夸克与 UC 同构），这里只提供夸克的域名/UA/目录名。
 *
 * <h2>品牌常量（每个字面量均由 {@link PanObfStrings} 从 {@code ae/decompiled/QuarkPan.java}
 * 的 {@code f7short} 表解出，括号里是解码调用点）</h2>
 * <pre>
 *   cookie 文件      quark_cookie.txt                (off=9   len=30 seed=1846 @ /sdcard/TVBox/)
 *   兜底 cookie      quark_cookie.txt                (off=45  len=27 seed=2405 @ /sdcard/TV/)
 *   temp 目录名      Quarktemp                       (off=0   len=9  seed=1180)
 *   转存日期缓存     qktime                          (off=72  len=6  seed=2315)
 *   清理日期缓存     deleteqktime                    (off=730 len=12 seed=1436)
 *   默认分享 id      369868287b83                    (off=1274 len=12 seed=1000)
 *   列根目录 URL     drive-pc.quark.cn /1/clouddrive/file/sort?pr=ucpro&amp;fr=pc
 *                                                &amp;uc_param_str=&amp;pdir_fid=0&amp;_page=1&amp;_size=50&amp;...
 *   列子目录 URL     同上 + pdir_fid={fid}&amp;_size=100&amp;...
 *   删文件 URL       drive-pc.quark.cn /1/clouddrive/file/delete?pr=ucpro&amp;fr=pc&amp;uc_param_str=
 *   建目录 URL       drive-pc.quark.cn /1/clouddrive/file?pr=ucpro&amp;fr=pc&amp;uc_param_str=
 *   换 stoken URL    drive.quark.cn    /1/clouddrive/share/sharepage/token?pr=ucpro&amp;fr=pc
 *   列分享 URL       drive.quark.cn    /1/clouddrive/share/sharepage/detail?pr=ucpro&amp;fr=pc
 *   转存 URL         drive-pc.quark.cn /1/clouddrive/share/sharepage/save?pr=ucpro&amp;fr=pc
 *   UA               quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100
 *                    ... Channel/pckk_other_ch
 *   Referer/Origin   https://pan.quark.cn/
 * </pre>
 *
 * <p><b>注意域名不统一</b>：删/建/转存在 {@code drive-pc.quark.cn}，而换取 stoken 与列分享在
 * {@code drive.quark.cn}。这不是笔误 —— 反编译里两个字面量确实分属两个域名，写错会 404。
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>真实 OAuth / 登录态不实现</b>：本类只读 cookie 文件（两级降级），
 *       不实现扫码登录、token 刷新。理由与原实现说明见
 *       {@link PanUCQuarkBase}：那套协议依赖宿主"配置中心"，且频繁变更。</li>
 *   <li><b>{@code playerContent} 不出直链</b>：{@code PanClientBase} 的实现只返回空响应
 *       —— 转直链需要各家带签名的私有接口，本工程未还原。</li>
 *   <li><b>{@code action(String)}（{@code PanQuark.java:58-150}）整段未实现</b>：
 *       上游从 {@code {"folder","shareId","fileToken"}} 起一个 {@code OkHttp} 下载
 *       到 {@code cacheDir/quark_<ts>.apk}，边下边用 {@code ProgressDialog} 报进度、
 *       完成后 {@code l.f(file)} 触发安装。这属于「宿主 UI + 下载器」能力，
 *       与浏览职责不同。<b>有意不还原</b>（会引入 Android UI/OKHttp 依赖）。</li>
 *   <li>{@code PanQuark.java:82-83} 的"整段 JSON 里取第 0 项"（{@code optJSONObject(0)}）
 *       未还原：本类按字段名取（{@code shareId/folder/parentId}）。</li>
 *   <li>{@code homeContent} 的第二个入口「软件商店」固定分享
 *       {@code 969cc97687d3}（{@code PanQuark.java:199}）未还原 ——
 *       本工程把 homeContent 简化成"未登录提示 / 我的网盘"两态。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>可执行文件落地即安装</b>（{@code PanQuark.java:116-119}）：
 *       把服务器返回的字节流写到 {@code cacheDir/quark_*.apk} 后直接
 *       {@code l.f(file)} 走安装流程，<b>没有做任何签名/校验</b>。
 *       本类不实现该路径。</li>
 *   <li><b>进度回调里 {@code Double.parseDouble} 无兜底</b>（{@code PanQuark.java:86}）：
 *       {@code Content-Length} 缺失时用 {@code "1"} 兜底看似安全，
 *       但服务端返回非数字（如分块传输的 {@code "chunked"}）即抛异常，
 *       而该异常在循环外被一个空 {@code catch} 吞掉 ⇒ <b>下载中断但对话框不关</b>。
 *       本类不涉及。</li>
 *   <li>{@code homeContent} 的站点清单是<b>内联的一行 JSON 字面量</b>
 *       （{@code PanQuark.java:199}）：{@code "[{\"name\":\"我的夸克网盘\",...}]"}，
 *       新增入口要改代码并转义。→ 本类用 {@link com.example.tvbox.common.VodBuilder}
 *       生成，不做手写 JSON。</li>
 *   <li>详情页把 {@code Config.getMyQuarkOptions()} 的线路名<b>重复 N 遍</b>
 *       拼成 play_url（{@code PanQuark.java:189-192}）：线路名数量与播放地址数量
 *       并不相等，这里有"每条线路都挂上全部地址"的语义耦合。→ 本类不做该拼接。</li>
 *   <li>品牌常量（域名/UA/目录名/缓存键）来自混淆字符串池，
 *       上游散落在 {@code f7short} 表里；本类已抽成方法并附解码调用点，
 *       见类 javadoc 顶部的参数表与 {@code ae/tools/pan_maint_strings.py}。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/QuarkPan.java}(193 行)、
 * 解码器 {@code ae/decompiled/{a,b,c,d,e}.java}、
 * 字符串清单 {@code ae/work/w-pan/decoded-strings.txt}。
 * 全部字面量已通过 {@code literal_audit.py} 与 DEX 字符串池逐字比对。
 */
public class PanQuarkPlain extends PanUCQuarkBase {

    private static final String TAG = "PanQuarkPlain";

    /** UA —— 逐字来自 {@code QuarkPan.j()}（off=752 len=192 seed=2373） */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 "
          + "Safari/537.36 Channel/pckk_other_ch";

    /** PC 接口域（列目录/删/建/转存） */
    private static final String API_PC = "https://drive-pc.quark.cn/1/clouddrive";
    /** 分享接口域（换 stoken / 列分享） */
    private static final String API_SHARE = "https://drive.quark.cn/1/clouddrive";

    @Override public String brandName() { return "夸克"; }
    @Override public String brandCookieFile() { return "quark_cookie.txt"; }
    @Override public String brandTempDir() { return "Quarktemp"; }
    @Override public String brandTimeKey() { return "qktime"; }
    @Override public String brandDeleteTimeKey() { return "deleteqktime"; }
    @Override public String defaultShareId() { return "369868287b83"; }
    @Override public String brandUa() { return UA; }
    @Override public String brandReferer() { return "https://pan.quark.cn/"; }
    @Override public String brandOrigin() { return "https://pan.quark.cn"; }

    /** 列根目录（off=95 len=172 seed=2204） */
    @Override public String rootSortUrl() {
        return API_PC + "/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid=0&_page=1&_size=50"
                + "&_fetch_total=1&_fetch_sub_dirs=0&_sort=file_type:asc,file_name:asc";
    }

    /** 列子目录（off=287 len=87 + off=374 len=85） */
    @Override public String subdirSortUrl(String pdirFid) {
        return API_PC + "/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid=" + pdirFid
                + "&_page=1&_size=100&_fetch_total=1&_fetch_sub_dirs=0"
                + "&_sort=file_type:asc,file_name:asc";
    }

    /** 删文件（off=559 len=79） */
    @Override public String deleteUrl() {
        return API_PC + "/file/delete?pr=ucpro&fr=pc&uc_param_str=";
    }

    /** 建目录（off=1864 len=72 在 m() 里） */
    @Override public String createDirUrl() {
        return API_PC + "/file?pr=ucpro&fr=pc&uc_param_str=";
    }

    /** 换 stoken（off=1287 len=72） */
    @Override public String shareTokenUrl() {
        return API_SHARE + "/share/sharepage/token?pr=ucpro&fr=pc";
    }

    /** 列分享（off=1178 len=81 + 分页尾串） */
    @Override public String shareDetailUrl(String pwdId, String stoken, String pdirFid) {
        return API_SHARE + "/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=" + pwdId
                + "&stoken=" + stoken + "&pdir_fid=" + pdirFid + "&force=0&_page=1"
                + SHARE_DETAIL_TAIL_UC;
    }

    /** 转存（off=1418 len=74） */
    @Override public String shareSaveUrl() {
        return API_PC + "/share/sharepage/save?pr=ucpro&fr=pc";
    }
}
