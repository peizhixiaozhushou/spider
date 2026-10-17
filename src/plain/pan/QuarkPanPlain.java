package com.example.tvbox.plain.pan;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 夸克网盘「维护例程」—— 逐字还原 {@code com.github.catvod.spider.QuarkPan}（193 行）。
 *
 * <h2>职责</h2>
 * 本类<b>不是</b>内容源，没有 {@code homeContent/categoryContent/detailContent}。
 * 它是被宿主定时调用的后台例程：
 * <ol>
 *   <li>{@link #reloadCredential()} —— 读 {@code quark_cookie.txt}（两级降级）</li>
 *   <li>{@link #checkTime()} —— 每天续期一次（{@code qktime} 缓存比日期）</li>
 *   <li>{@link #deleteTime()} —— 满 7 天清一次 {@code Quarktemp} 目录</li>
 * </ol>
 * 协议链路与判据见 {@link UCQuarkMaintBase} 类注释。
 *
 * <h2>与 {@code PanQuark} / {@code PanQuarkPlain} 的分工（本任务的核心判断）</h2>
 * <table border="1">
 *   <tr><th></th><th>{@code QuarkPan}（本类）</th><th>{@code PanQuark}（{@code PanQuarkPlain}）</th></tr>
 *   <tr><td>基类</td><td>{@code extends Spider}（{@code QuarkPan.java:28}）</td>
 *       <td>{@code extends Pan}（{@code PanQuark.java:29}）</td></tr>
 *   <tr><td>方法</td><td>{@code checkquarkcookie/checktime/deleteQuarkTemp/deletetime/getCache/j/l/m}</td>
 *       <td>{@code homeContent/categoryContent/detailContent/action/init/isBusy/setBusy/b}</td></tr>
 *   <tr><td>实例 or 静态</td><td>全部 {@code static}（调度器调用一次）</td><td>实例（需 UI 线程、{@code ProgressDialog}）</td></tr>
 *   <tr><td>网络通道</td><td>{@code merge.p036k.b}（纯文本 GET/POST）</td><td>{@code merge.p027b.w}（带业务的封装）</td></tr>
 *   <tr><td>输出</td><td>无（只写本地缓存文件）</td><td>TVBox 标准 JSON（{@code merge.p028c.c}）</td></tr>
 * </table>
 * <p><b>结论：两者是「后台 cron」与「前台页面」，职责不重叠、无版本关系，都必须保留。</b>
 * 依据：方法集零交集（{@code ae/decompiled/_index.json} 的 {@code methods} 字段）、
 * 基类不同、可实例化性不同。详见 {@link UCQuarkMaintBase} 类注释 §1。
 *
 * <h2>字符串池解码参数（每个调用点，改一行就重算不出来）</h2>
 * 表 {@code QuarkPan.f7short}（1936 项），解码 {@code d.c(arr, from, len, seed)}：
 * <pre>
 *   L35   d.c(0,     9,    1180)  → "Quarktemp"                       目录名
 *   L41   d.c(9,     30,   1846)  → "/sdcard/TVBox/quark_cookie.txt"  主凭据
 *   L45   c.e(39,    6,    2110)  → "cookie"                          凭据 JSON 字段
 *   L52   d.c(45,    27,   2405)  → "/sdcard/TV/quark_cookie.txt"     兜底凭据
 *   L66   a.e(72,    6,    2315)  → "qktime"                          续期日期缓存键
 *   L68   c.e(78,    8,    2885)  → "yyyyMMdd"                        日期格式
 *   L77   a.e(86,    9,    1796)  → "Quarktemp"                       清理目标目录名
 *   L78   e.c(95,    172,  2204)  → 列根目录 URL（_size=50  file_name:asc）
 *   L81   c.e(275,   9,    1280)  → "file_name"
 *   L78   e.c(267,   4,    3117)  → "data"        /  a.e(271,4,2591)   → "list"
 *   L82   a.e(287,   87,   2379)  → 列子目录 URL 前缀
 *   L82   e.c(284,   3,    3184)  → "fid"
 *   L82   a.e(374,   85,   960)   → 列子目录 URL 尾串（_size=100）
 *   L83   c.e(459,   15,   2597)  → "QuarkTempDelete"（日志 TAG）
 *   L83   d.c(474,   23,   3084)  → "Folder files response: "
 *   L84   c.e(497,   4,    2179)  → "data"        /  a.e(501,4,1642)   → "list"
 *   L86   a.e(559,   79,   2813)  → 删文件 URL
 *   L86   d.c(508,   30,   2462)  → {"action_type":2,"filelist":["     （body 前缀）
 *   L86   c.e(505,   3,    665)   → "fid"
 *   L86   c.e(538,   21,   3126)  → "],"exclude_fids":[]}              （body 后缀）
 *   L90   a.e(638,   8,    3166)  → "yyyyMMdd"
 *   L91   a.e(646,   12,   2605)  → "deleteqktime"                    清理日期缓存键
 *   L94   c.e(708,   22,   3124)  → "Error deleting files: "
 *   L99   c.e(730,   12,   1436)  → "deleteqktime"
 *   L113  e.c(742,   10,   2322)  → "User-Agent"
 *   L113  e.c(752,   192,  2373)  → 完整 UA（quark-cloud-drive/2.5.20 …）
 *   L114  d.c(944,   6,    2923)  → "Cookie"
 *   L115  a.e(950,   12,   708)   → "Content-Type"
 *   L115  e.c(962,   30,   475)   → "application/json;charset=UTF-8"
 *   L116  d.c(992,   7,    500)   → "Referer"
 *   L116  e.c(999,   21,   2669)  → "https://pan.quark.cn/"
 *   L117  a.e(1020,  6,    1331)  → "Origin"
 *   L117  e.c(1026,  20,   2409)  → "https://pan.quark.cn"
 *   L122  e.c(1046,  7,    1218)  → "message"
 *   L123  e.c(1053,  92,   365)   → 分享详情尾串（_size=9999 … file_name:desc）
 *   L124  e.c(1145,  15,   3170)  → "&force=0&_page="
 *   L125  a.e(1160,  10,   614)   → "&pdir_fid="
 *   L126  a.e(1170,  8,    1715)  → "&stoken="
 *   L127  d.c(1178,  81,   2706)  → 分享详情 URL 前缀（drive.quark.cn）
 *   L128  e.c(1259,  4,    1383)  → "list"
 *   L129  c.e(1263,  6,    765)   → "stoken"
 *   L130  d.c(1269,  4,    2070)  → "data"
 *   L132  a.e(1274,  12,   1000)  → "369868287b83"                    默认分享 id
 *   L135  d.c(1287,  72,   384)   → 换 stoken URL
 *   L135  d.c(1359,  11,   2575)  → {"pwd_id":"                       （body 前缀）
 *   L135  e.c(1370,  16,   1563)  → ","passcode":""}                  （body 后缀）
 *   L143  c.e(1386,  5,    3236)  → "share"
 *   L143  a.e(1391,  9,    1524)  → "first_fid"
 *   L151  d.c(1400,  3,    3079)  → "fid"
 *   L152  c.e(1403,  15,   2532)  → "share_fid_token"
 *   L153  c.e(1418,  74,   2649)  → 转存 URL
 *   L153  e.c(1492,  14,   1850)  → {"fid_list":["                    （body 段 A）
 *   L153  d.c(1506,  22,   1960)  → "],"fid_token_list":["            （body 段 B）
 *   L153  d.c(1528,  33,   3031)  → "],"pdir_fid":"0","to_pdir_fid":"  （body 段 C）
 *   L153  e.c(1561,  12,   1922)  → ","pwd_id":"                       （body 段 D）
 *   L153  a.e(1573,  12,   2935)  → ","stoken":"                       （body 段 E）
 *   L153  a.e(1585,  2,    643)   → "}
 *   L156  c.e(1587,  2,    2142)  → "ok"                              成功标记
 *   L157  c.e(1597,  6,    1785)  → "qktime"
 *   L157  a.e(1589,  8,    2896)  → "yyyyMMdd"
 *   L172  e.c(1603,  174,  2766)  → m() 用的根目录 URL（_sort=updated_at:desc）
 *   L173  d.c(1777,  4,    1153)  → "data"
 *   L174  e.c(1781,  4,    1542)  → "list"
 *   L178  a.e(1785,  3,    1000)  → "fid"
 *   L182  e.c(1788,  9,    2258)  → "file_name"
 *   L189  d.c(1864,  72,   892)   → 建目录 URL
 *   L189  a.e(1797,  29,   2410)  → {"pdir_fid":"0","file_name":"      （body 前缀）
 *   L189  a.e(1826,  38,   1121)  → ","dir_path":"","dir_init_lock":false}（body 后缀）
 * </pre>
 * 解码算法与该方法等价性证明见 {@code PanObfStrings}；可重算的机器可读版本见
 * {@code maintain/evidence/QuarkPan_strings.md}；全量 148 条见
 * {@code ae/work/w-pan/decoded-strings.txt}。
 *
 * <h2>夸克独有的坑</h2>
 * <ol>
 *   <li><b>域名分裂</b>：列目录/删/建/转存在 {@code drive-pc.quark.cn}，
 *       换 stoken 与列分享在 {@code drive.quark.cn}。写错会 404。</li>
 *   <li><b>两条根目录 URL</b>：清理流程 {@code _sort=file_type:asc,file_name:asc}，
 *       而 {@code m()} 找目录用 {@code _sort=file_type:asc,updated_at:desc}。
 *       UC 这两条恰好相同 —— 只按 UC 抄会漏掉。</li>
 *   <li><b>{@code uc_param_str=}</b> 只有夸克有（空值），UC 的 file/sort 没有这个参数，
 *       但 UC 的 file/sort 子目录版<b>又有</b>。不要"统一"。</li>
 * </ol>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>宿主侧调度器不在 jar 内 —— 本类永远不会自己跑起来。</b>
 *       全库 {@code grep} 过：没有任何类调用 {@code QuarkPan.checkquarkcookie()/checktime()/deletetime()}
 *       （{@code QuarkPan.java} 里这三个方法也没有自调用）。它们是 {@code static} 工具方法，
 *       由 <b>TVBox 宿主 + 配置中心</b>在外部按计划触发（读 {@code /sdcard/TVBox/quark_cookie.txt}
 *       的那个"配置中心"就是同一套外部设施）。
 *       ⇒ 本次重写<b>不提供</b>触发逻辑，这是设计事实而非遗漏；后续要定时跑请自行挂
 *       {@link #checkTime()} / {@link #deleteTime()}。</li>
 *   <li>{@code merge.p026a.a.a(String)}（{@code QuarkPan.java:189}）的方法体未反编译。
 *       它只用来包建目录 body 的 JSON 前缀 {@code {"pdir_fid":"0","file_name":"}，
 *       任何"转义型"实现都会破坏该 JSON，故推定为恒等。无字节码佐证。</li>
 *   <li>{@code merge.p027b.h.b(a,b,c)} 与 {@code merge.p027b.v.b(k,v)} 未反编译。
 *       三串拼接 / 两元素 Map 是由调用点语义（还原文本身、构造请求头）唯一反推出的读法，
 *       没有字节码佐证。</li>
 *   <li>{@code merge.p036k.b.f(url, body, headers).a()} 的包装语义按「POST 返回响应文本」处理
 *       （{@code .a()} == 取 body）。若它其实还做了编码/解压，本类会漏。</li>
 *   <li>{@code merge.p036k.b} 的失败语义未知：上游对非 200 是否抛异常无法从调用点判断，
 *       本实现按 {@code Http} 的约定退化为空串（失败不抛），因此"网络失败"与"服务端返回空"
 *       在本实现里不可区分。这是有意的健壮性取舍，列此备查。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <p><b>（a）夸克/UC 共有的 5 条</b>
 * <ol>
 *   <li><b>{@code static} 可变状态跨实例污染</b>（{@code QuarkPan.java:33-34}）：
 *       {@code private static String d}（cookie）与 {@code private static String y}（临时目录 fid）
 *       是 {@code static}。同进程两个实例/两个账号时，后读的 cookie 覆盖前一个（A 被 B 覆盖），
 *       转存也会落到别人的 Quarktemp 目录。
 *       → 已改为实例字段 {@code cookie} / {@code tempDirFid}。
 *       断言：{@code ★ 缺陷修正：static 可变字段改实例}（反射断言非 static + 双实例互不污染）。</li>
 *   <li><b>用 {@code contains("{")} 判断"是不是 JSON"</b>（{@code QuarkPan.java:139-144}）：
 *       500 错误页里带一个花括号就被当成成功，随后 {@code new JSONObject(..)} 抛异常。
 *       → 已改为真解析 + 必需字段校验（{@code parseObject} / {@code pathString}）。
 *       断言：{@code ★ 续期失败分支（5 条）}、{@code ★ 畸形响应（不抛异常）}。</li>
 *   <li><b>{@code new SimpleDateFormat("yyyyMMdd")} 不指定 Locale</b>（{@code QuarkPan.java:68,90,157}）：
 *       泰语区（{@code th_TH}）输出佛历年份（2568 而非 2025），"缓存 ≥ 今天"永不成立
 *       ⇒ <b>每次启动都重复转存</b>。
 *       → 已固定 {@link java.util.Locale#US}。
 *       断言：{@code ★ 日期 helper 容错}。</li>
 *   <li><b>缓存值不校验直接 {@code Integer.parseInt}</b>（{@code QuarkPan.java:68,101}）：
 *       缓存被截断或写入脏数据时 {@code NumberFormatException} 逃出；
 *       {@code deletetime()} 外层<b>没有 try</b>，异常直接冒到调用方。
 *       → 解析失败按 0 处理（视为过期，只会更积极地维护）。
 *       断言：{@code ★ 脏缓存 / 空缓存 → 视为过期}。</li>
 *   <li><b>{@code deletetime()} 的 {@code substring(0,4)/{4,6}/{6,8}}</b>（{@code QuarkPan.java:101}）：
 *       缓存短于 8 字符直接 {@code StringIndexOutOfBoundsException}。同上修正。</li>
 * </ol>
 * <p><b>（b）夸克独有</b>
 * <ol>
 *   <li><b>删文件完全不看响应</b>（{@code QuarkPan.java:86}）：POST delete 的返回值整份丢弃，
 *       401/风控时照样写 {@code deleteqktime}=今天 ⇒ 7 天内不再重试。
 *       → 保留写入时机（与上游一致），但每次响应记日志并如实返回条数。
 *       断言：{@code ★ 7 天清理}。</li>
 *   <li><b>建目录后不校验</b>（{@code QuarkPan.java:189}）：直接 {@code data.fid}，
 *       目录已存在时取不到即抛（被外层 catch 吞掉，表现为"莫名其妙转存失败"）。
 *       → 先查列表再建，取 fid 失败返回空串。断言：{@code ★ 新建目录成功（列根为空 -> 建）}。</li>
 *   <li><b>{@code new Random().nextInt(len)} 随机挑文件</b>（{@code QuarkPan.java:150}）：
 *       每 7 天转存的内容不确定，播放列表会莫名变化。→ 行为保留，随机源可注入。
 *       断言：{@code ★ 续期链路（l() 的 6 步）}。</li>
 *   <li><b>分享 id 硬编码</b>（{@code QuarkPan.java:132} 的 {@code 369868287b83}）：
 *       分享被撤即整条链路静默失效。→ 抽成 {@link #defaultShareId()}，默认值逐字相同。
 *       断言：{@code ★ 协议常量（URL / body 拼接）}。</li>
 *   <li><b>{@code checktime()} 与 cookie 读取无耦合</b>（{@code QuarkPan.java:65-73}）：
 *       {@code l()} 直接用 {@code static d}，若调度方漏调 {@code checkquarkcookie()}
 *       就会<b>带着空 Cookie 发请求</b>（服务端返回登录失败，但代码以为只是"没文件"）。
 *       → cookie 为空时自动补读一次。断言：{@code ★ 未登录不发请求}。</li>
 *   <li><b>两条根目录 URL 名称相同语义不同</b>（{@code QuarkPan.java:78} 与 {@code :172}）：
 *       前者 {@code file_name:asc}（清理用）、后者 {@code updated_at:desc}（找目录用），
 *       上游命名成 {@code deleteQuarkTemp()} 内联字面量 + {@code m()} 内联字面量，无注释。
 *       → 分离为 {@link #rootSortUrl()} / {@link #dirScanSortUrl()} 并加注释。
 *       断言：{@code ★ 两条根目录 URL 不相等（夸克独有）}。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/QuarkPan.java}；
 * 字段/方法清单: {@code ae/decompiled/_index.json}；
 * 解码器: {@code ae/decompiled/{a,b,c,d,e}.java}；
 * <b>字符串池解码参数逐调用点清单: {@code maintain/evidence/QuarkPan_strings.md}</b>（75 行，
 * 由 {@code ae/tools/pan_maint_strings.py} 从反编译源现算生成，含每处 {@code (from, len, seed)}）；
 * 全量 148 条: {@code ae/work/w-pan/decoded-strings.txt}。
 */
public class QuarkPanPlain extends UCQuarkMaintBase {

    /** 目录名（{@code L35 d.c(0,9,1180)}） */
    public static final String TEMP_DIR = "Quarktemp";
    /** 续期日期缓存键（{@code L66 a.e(72,6,2315)}） */
    public static final String TIME_KEY = "qktime";
    /** 清理日期缓存键（{@code L99 c.e(730,12,1436)}） */
    public static final String DELETE_TIME_KEY = "deleteqktime";
    /** 默认分享 id（{@code L132 a.e(1274,12,1000)}） */
    public static final String DEFAULT_SHARE_ID = "369868287b83";
    /** 凭据文件名 */
    public static final String COOKIE_FILE = "quark_cookie.txt";
    /** 主凭据完整路径（{@code L41 d.c(9,30,1846)}） */
    public static final String COOKIE_PATH_MAIN = "/sdcard/TVBox/quark_cookie.txt";
    /** 兜底凭据完整路径（{@code L52 d.c(45,27,2405)}） */
    public static final String COOKIE_PATH_FALLBACK = "/sdcard/TV/quark_cookie.txt";

    /** 完整 UA（{@code L113 e.c(752,192,2373)}，逐字保留） */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 "
          + "Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch";

    /** PC 接口域（列目录 / 删 / 建 / 转存） */
    public static final String API_PC = "https://drive-pc.quark.cn/1/clouddrive";
    /** 分享接口域（换 stoken / 列分享） */
    public static final String API_SHARE = "https://drive.quark.cn/1/clouddrive";

    /** 列子目录尾串（{@code L82 a.e(374,85,960)}） */
    public static final String SUBDIR_TAIL =
            "&_page=1&_size=100&_fetch_total=1&_fetch_sub_dirs=0&_sort=file_type:asc,file_name:asc";

    @Override public String brandName() { return "夸克"; }
    @Override public String brandCookieFile() { return COOKIE_FILE; }
    @Override public String brandTempDirName() { return TEMP_DIR; }
    @Override public String brandTimeKey() { return TIME_KEY; }
    @Override public String brandDeleteTimeKey() { return DELETE_TIME_KEY; }
    @Override public String defaultShareId() { return DEFAULT_SHARE_ID; }
    @Override public String brandUserAgent() { return UA; }
    @Override public String brandReferer() { return "https://pan.quark.cn/"; }
    @Override public String brandOrigin() { return "https://pan.quark.cn"; }

    /** 列根目录（清理流程用）—— {@code L78 e.c(95,172,2204)} */
    @Override public String rootSortUrl() {
        return API_PC + "/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid=0&_page=1&_size=50"
                + "&_fetch_total=1&_fetch_sub_dirs=0&_sort=file_type:asc,file_name:asc";
    }

    /** 找临时目录用 —— {@code L172 e.c(1603,174,2766)}，注意 {@code updated_at:desc} */
    @Override public String dirScanSortUrl() {
        return API_PC + "/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid=0&_page=1&_size=50"
                + "&_fetch_total=1&_fetch_sub_dirs=0&_sort=file_type:asc,updated_at:desc";
    }

    /** 列子目录（{@code L82} 前缀 + fid + 尾串） */
    @Override public String subdirSortUrl(String pdirFid) {
        return API_PC + "/file/sort?pr=ucpro&fr=pc&uc_param_str=&pdir_fid="
                + (pdirFid == null ? "" : pdirFid) + SUBDIR_TAIL;
    }

    /** 删文件 —— {@code L86 a.e(559,79,2813)} */
    @Override public String deleteUrl() {
        return API_PC + "/file/delete?pr=ucpro&fr=pc&uc_param_str=";
    }

    /** 建目录 —— {@code L189 d.c(1864,72,892)} */
    @Override public String createDirUrl() {
        return API_PC + "/file?pr=ucpro&fr=pc&uc_param_str=";
    }

    /** 换 stoken —— {@code L135 d.c(1287,72,384)} */
    @Override public String shareTokenUrl() {
        return API_SHARE + "/share/sharepage/token?pr=ucpro&fr=pc";
    }

    /** 分享详情前缀 —— {@code L127 d.c(1178,81,2706)} */
    @Override public String shareDetailBase() {
        return API_SHARE + "/share/sharepage/detail?pr=ucpro&fr=pc&pwd_id=";
    }

    /** 转存 —— {@code L153 c.e(1418,74,2649)} */
    @Override public String shareSaveUrl() {
        return API_PC + "/share/sharepage/save?pr=ucpro&fr=pc";
    }

    /**
     * 供测试断言用的「链路快照」—— 一次拿全本类的全部常量与 URL。
     *
     * @return 有序 Map（键名即字段语义）
     */
    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("brand", brandName());
        m.put("tempDir", brandTempDirName());
        m.put("timeKey", brandTimeKey());
        m.put("deleteTimeKey", brandDeleteTimeKey());
        m.put("shareId", defaultShareId());
        m.put("cookieFile", brandCookieFile());
        m.put("cookieMain", COOKIE_PATH_MAIN);
        m.put("cookieFallback", COOKIE_PATH_FALLBACK);
        m.put("ua", brandUserAgent());
        m.put("rootSort", rootSortUrl());
        m.put("dirScan", dirScanSortUrl());
        m.put("subdir", subdirSortUrl("{fid}"));
        m.put("delete", deleteUrl());
        m.put("mkdir", createDirUrl());
        m.put("token", shareTokenUrl());
        m.put("detailBase", shareDetailBase());
        m.put("save", shareSaveUrl());
        m.put("tokenBody", shareTokenBody());
        return m;
    }
}
