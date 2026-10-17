package com.example.tvbox.plain.pan.client;

/**
 * UC 网盘客户端浏览源 —— 品牌常量层。
 *
 * <p><b>本类替换了原先 40 行的降级桩</b>（原桩 {@code listDir} 永远返回空）。
 * 所有协议逻辑在 {@link PanUCQuarkBase}（UC 与夸克同构），这里只提供 UC 的域名/UA/目录名。
 *
 * <h2>品牌常量（每个字面量均由 {@link PanObfStrings} 从 {@code ae/decompiled/UCPan.java}
 * 的 {@code f14short} 表解出，括号里是解码调用点）</h2>
 * <pre>
 *   cookie 文件      uc_cookie.txt                  (off=20  len=27 seed=2191 @ /sdcard/TVBox/)
 *   兜底 cookie      uc_cookie.txt                  (off=53  len=24 seed=1011 @ /sdcard/TV/)
 *   temp 目录名      UCtemp                         (off=0   len=6  seed=2959)
 *   转存日期缓存     uctime                         (off=6   len=6  seed=1979)
 *   清理日期缓存     deleteuctime                   (off=652 len=12 seed=2035)
 *   默认分享 id      c4015526cb494                  (off=1194 len=13 seed=494)
 *   列根目录 URL     pc-api.uc.cn /1/clouddrive/file/sort?pr=UCBrowser&amp;fr=pc
 *                                                &amp;pdir_fid=0&amp;_page=1&amp;_size=200&amp;...
 *   列子目录 URL     同上 + uc_param_str=&amp;pdir_fid={fid}&amp;_size=100&amp;...
 *   删文件 URL       pc-api.uc.cn /1/clouddrive/file/delete?pr=UCBrowser&amp;fr=pc
 *   建目录 URL       pc-api.uc.cn /1/clouddrive/file?pr=UCBrowser&amp;fr=pc
 *   换 stoken URL    pc-api.uc.cn /1/clouddrive/share/sharepage/token?pr=UCBrowser&amp;fr=pc
 *   列分享 URL       pc-api.uc.cn /1/clouddrive/share/sharepage/detail?pr=UCBrowser&amp;fr=pc
 *   转存 URL         pc-api.uc.cn /1/clouddrive/share/sharepage/save?pr=UCBrowser&amp;fr=pc
 *   UA               uc-cloud-drive/1.8.5 Chrome/100.0.4896.160 Electron/18.3.5.16-b62cf9c50d
 *                    ... Channel/ucpan_other_ch
 *   Referer/Origin   https://drive.uc.cn/
 * </pre>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>真实 OAuth / 登录态不实现</b>：只读 {@code uc_cookie.txt}（两级降级），
 *       不实现扫码登录与 token 刷新（理由见 {@link PanUCQuarkBase}）。</li>
 *   <li><b>{@code playerContent} 不出直链</b>：{@code PanClientBase} 只返回空响应。</li>
 *   <li><b>{@code action(String)} 未实现</b>：与 {@code PanQuark} 同构（下载 apk、
 *       {@code ProgressDialog} 报进度、下载完触发安装），属宿主 UI/下载器职责。</li>
 *   <li>{@code homeContent} 的固定入口分享（{@code PanUC.java} 内的站点清单）未还原，
 *       本工程只给"未登录 / 我的 UC 网盘"两态。</li>
 *   <li>删除/新建/转存三条链路<b>只有浏览与维护入口</b>：完整的
 *       「列目录 → 删 → 建 → 转存」在 {@code PanUCQuarkBase} 里实现，
 *       但 {@code PanUCPlain} 自身只提供品牌常量 ⇒ 若只实例化本类而不调基类方法，
 *       这些能力不会被触发（需宿主/调用方主动调）。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>UC 与夸克是同一份协议但被抄成两个类</b>（{@code PanUC.java} 231 行 ≈
 *       {@code PanQuark.java} 231 行，除品牌常量外逐字相同）。
 *       → 本工程收敛为 {@link PanUCQuarkBase} + 两个只提供常量的子类，
 *       差异点全部集中在品牌钩子里（域名/UA/目录名/缓存键/分享 id）。
 *       断言：{@code UCPanTest} 的"差异对照"组。</li>
 *   <li>品牌常量藏在混淆字符串池里（{@code f14short}），
 *       上游把 73 处解码散落在 190 行里，<b>同一个值在多处重复解码</b>
 *       （如 {@code UCtemp} 出现两次、{@code deleteuctime} 两个不同表项同明文）。
 *       → 本类每个常量只解一次并给名字。
 *       参数表见类 javadoc 顶部与 {@code ae/tools/pan_maint_strings.py}。</li>
 *   <li>上游 {@code homeContent} 用内联 JSON 字面量描述站点清单，与夸克同病。→ 本类用
 *       {@code VodBuilder}。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/UCPan.java}(190 行)、
 * 解码器 {@code ae/decompiled/{a,b,c,d,e}.java}、
 * 字符串清单 {@code ae/work/w-pan/decoded-strings.txt}。
 * 全部字面量已通过 {@code literal_audit.py} 与 DEX 字符串池逐字比对。
 */
public class PanUCPlain extends PanUCQuarkBase {

    private static final String TAG = "PanUCPlain";

    /** UA —— 逐字来自 {@code UCPan.j()}（off=674 len=190 seed=2368） */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) uc-cloud-drive/1.8.5 Chrome/100.0.4896.160 Electron/18.3.5.16-b62cf9c50d "
          + "Safari/537.36 Channel/ucpan_other_ch";

    private static final String API = "https://pc-api.uc.cn/1/clouddrive";

    @Override public String brandName() { return "UC"; }
    @Override public String brandCookieFile() { return "uc_cookie.txt"; }
    @Override public String brandTempDir() { return "UCtemp"; }
    @Override public String brandTimeKey() { return "uctime"; }
    @Override public String brandDeleteTimeKey() { return "deleteuctime"; }
    @Override public String defaultShareId() { return "c4015526cb494"; }
    @Override public String brandUa() { return UA; }
    @Override public String brandReferer() { return "https://drive.uc.cn/"; }
    @Override public String brandOrigin() { return "https://drive.uc.cn"; }

    /** 列根目录（off=83 len=158 seed=695） */
    @Override public String rootSortUrl() {
        return API + "/file/sort?pr=UCBrowser&fr=pc&pdir_fid=0&_page=1&_size=200"
                + "&_fetch_total=1&_fetch_sub_dirs=0&_sort=file_type:asc,file_name:asc";
    }

    /** 列子目录（off=261 len=86 + off=347 len=85） */
    @Override public String subdirSortUrl(String pdirFid) {
        return API + "/file/sort?pr=UCBrowser&fr=pc&uc_param_str=&pdir_fid=" + pdirFid
                + "&_page=1&_size=100&_fetch_total=1&_fetch_sub_dirs=0"
                + "&_sort=file_type:asc,file_name:asc";
    }

    /** 删文件（off=494 len=64） */
    @Override public String deleteUrl() {
        return API + "/file/delete?pr=UCBrowser&fr=pc";
    }

    /** 建目录（off=1770 len=57 在 m() 里） */
    @Override public String createDirUrl() {
        return API + "/file?pr=UCBrowser&fr=pc";
    }

    /** 换 stoken（off=1208 len=74） */
    @Override public String shareTokenUrl() {
        return API + "/share/sharepage/token?pr=UCBrowser&fr=pc";
    }

    /** 列分享（off=1096 len=83 + 分页尾串） */
    @Override public String shareDetailUrl(String pwdId, String stoken, String pdirFid) {
        return API + "/share/sharepage/detail?pr=UCBrowser&fr=pc&pwd_id=" + pwdId
                + "&stoken=" + stoken + "&pdir_fid=" + pdirFid + "&force=0&_page=1"
                + SHARE_DETAIL_TAIL_UC;
    }

    /** 转存（off=1341 len=73） */
    @Override public String shareSaveUrl() {
        return API + "/share/sharepage/save?pr=UCBrowser&fr=pc";
    }
}
