package com.example.tvbox.plain.pan;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UC 网盘「维护例程」—— 逐字还原 {@code com.github.catvod.spider.UCPan}（190 行）。
 *
 * <h2>职责</h2>
 * 与 {@link QuarkPanPlain} 完全同构（ucpro 云盘底座，同一套接口），只有品牌常量不同。
 * 三个入口：{@link #reloadCredential()}、{@link #checkTime()}、{@link #deleteTime()}。
 * 协议链路与判据见 {@link UCQuarkMaintBase} 类注释。
 *
 * <h2>与 {@code PanUC} / {@code PanUCPlain} 的分工</h2>
 * 同 {@link QuarkPanPlain}：{@code UCPan.java:27} 是 {@code extends Spider}（后台例程，全 static），
 * {@code PanUC.java:29} 是 {@code extends Pan}（内容源，覆写 {@code detailContent}）。
 * 方法集零交集 ⇒ 不是新旧两版，是两个不同层，都要保留。
 *
 * <h2>字符串池解码参数（每个调用点，表 {@code UCPan.f14short}，1827 项）</h2>
 * <pre>
 *   L34   d.c(0,     6,    2959)  → "UCtemp"                        目录名
 *   L37   e.c(6,     6,    1979)  → "uctime"                        续期日期缓存键
 *   L39   d.c(12,    8,    2051)  → "yyyyMMdd"                      日期格式
 *   L50   e.c(20,    27,   2191)  → "/sdcard/TVBox/uc_cookie.txt"   主凭据
 *   L54   c.e(47,    6,    1770)  → "cookie"                        凭据 JSON 字段
 *   L61   e.c(53,    24,   1011)  → "/sdcard/TV/uc_cookie.txt"      兜底凭据
 *   L76   d.c(77,    6,    830)   → "UCtemp"                        清理目标目录名
 *   L77   d.c(83,    158,  695)   → 列根目录 URL（_size=200 file_name:asc）
 *   L77   c.e(241,   4,    1332)  → "data"    /  d.c(245,4,952)   → "list"
 *   L80   c.e(249,   9,    2983)  → "file_name"
 *   L81   a.e(261,   86,   1985)  → 列子目录 URL 前缀
 *   L81   e.c(258,   3,    3107)  → "fid"
 *   L81   d.c(347,   85,   1759)  → 列子目录 URL 尾串（_size=100）
 *   L83   c.e(494,   64,   2403)  → 删文件 URL
 *   L83   d.c(443,   30,   2156)  → {"action_type":2,"filelist":["  （body 前缀）
 *   L83   a.e(440,   3,    790)   → "fid"
 *   L83   a.e(473,   21,   2647)  → "],"exclude_fids":[]}           （body 后缀）
 *   L87   d.c(558,   8,    1128)  → "yyyyMMdd"
 *   L88   a.e(566,   12,   1662)  → "deleteuctime"                  清理日期缓存键
 *   L89   a.e(578,   12,   2526)  → "UCTempDelete"（日志 TAG）
 *   L89   e.c(590,   20,   1484)  → "Record delete time: "
 *   L91   e.c(610,   12,   2018)  → "UCTempDelete"
 *   L91   a.e(622,   30,   2391)  → "Error deleting UC temp files: "
 *   L110  d.c(664,   10,   1984)  → "User-Agent"
 *   L110  d.c(674,   190,  2368)  → 完整 UA（uc-cloud-drive/1.8.5 …）
 *   L111  a.e(864,   6,    1662)  → "Cookie"
 *   L112  c.e(870,   12,   1462)  → "Content-Type"
 *   L112  d.c(882,   30,   1274)  → "application/json;charset=UTF-8"
 *   L113  a.e(912,   7,    1827)  → "Referer"
 *   L113  e.c(919,   20,   1854)  → "https://drive.uc.cn/"
 *   L114  c.e(939,   6,    1184)  → "Origin"
 *   L114  a.e(945,   19,   1452)  → "https://drive.uc.cn"
 *   L119  d.c(964,   7,    1846)  → "message"
 *   L120  c.e(971,   92,   2280)  → 分享详情尾串（_size=9999 … file_name:desc）
 *   L121  d.c(1063,  15,   367)   → "&force=0&_page="
 *   L122  d.c(1078,  10,   1685)  → "&pdir_fid="
 *   L123  c.e(1088,  8,    449)   → "&stoken="
 *   L124  d.c(1096,  83,   2848)  → 分享详情 URL 前缀（pc-api.uc.cn）
 *   L125  a.e(1179,  4,    1195)  → "list"
 *   L126  a.e(1183,  6,    2044)  → "stoken"
 *   L127  a.e(1189,  4,    636)   → "data"
 *   L129  d.c(1194,  13,   494)   → "c4015526cb494"                 默认分享 id
 *   L132  c.e(1208,  74,   467)   → 换 stoken URL
 *   L132  a.e(1282,  11,   1270)  → {"pwd_id":"                    （body 前缀）
 *   L132  d.c(1293,  16,   1895)  → ","passcode":""}               （body 后缀）
 *   L140  d.c(1309,  5,    884)   → "share"
 *   L140  e.c(1314,  9,    1037)  → "first_fid"
 *   L148  c.e(1323,  3,    2896)  → "fid"
 *   L149  d.c(1326,  15,   3234)  → "share_fid_token"
 *   L150  e.c(1341,  73,   778)   → 转存 URL
 *   L150  d.c(1414,  14,   467)   → {"fid_list":["                 （body 段 A）
 *   L150  a.e(1428,  22,   2995)  → "],"fid_token_list":["         （body 段 B）
 *   L150  a.e(1450,  33,   477)   → "],"pdir_fid":"0","to_pdir_fid":" （body 段 C）
 *   L150  c.e(1483,  12,   2526)  → ","pwd_id":"                    （body 段 D）
 *   L150  c.e(1495,  12,   2520)  → ","stoken":"                    （body 段 E）
 *   L150  d.c(1507,  2,    1385)  → "}
 *   L153  a.e(1509,  2,    1008)  → "ok"                           成功标记
 *   L154  e.c(1519,  6,    1737)  → "uctime"
 *   L154  a.e(1511,  8,    1722)  → "yyyyMMdd"
 *   L169  d.c(1525,  158,  827)   → m() 用的根目录 URL（与清理流程<b>相同</b>）
 *   L170  d.c(1683,  4,    1903)  → "data"
 *   L171  a.e(1687,  4,    2337)  → "list"
 *   L175  c.e(1691,  3,    3048)  → "fid"
 *   L179  e.c(1694,  9,    846)   → "file_name"
 *   L186  c.e(1770,  57,   611)   → 建目录 URL
 *   L186  e.c(1703,  29,   497)   → {"pdir_fid":"0","file_name":"   （body 前缀）
 *   L186  a.e(1732,  38,   1722)  → ","dir_path":"","dir_init_lock":false}（body 后缀）
 * </pre>
 * 可重算的机器可读版本见 {@code maintain/evidence/UCPan_strings.md}。
 *
 * <h2>UC 相对夸克的差异（抄错必挂）</h2>
 * <ol>
 *   <li>域：{@code pc-api.uc.cn}（<b>所有</b>接口，包括分享）vs 夸克分裂成两个域。</li>
 *   <li>{@code pr=UCBrowser} vs {@code pr=ucpro}。</li>
 *   <li>根目录 {@code _size=200}，且 {@code file/sort} <b>不带</b> {@code uc_param_str=}（夸克带）。</li>
 *   <li>但列子目录两条<b>都</b>带 {@code uc_param_str=} —— 不要"统一"。</li>
 *   <li>删文件/建目录 URL <b>不带</b> {@code uc_param_str=}；夸克两条都带（空值）。</li>
 *   <li>{@code m()} 用的根目录 URL 在 UC 与清理流程<b>完全相同</b>；夸克不同（{@code updated_at:desc}）。</li>
 *   <li>方法名：{@code checkuccookie()}（不是 checkucookie）、{@code deleteUCTemp()}。</li>
 * </ol>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>宿主侧调度器不在 jar 内 —— 本类永远不会自己跑起来。</b>
 *       全库 {@code grep} 过：没有任何类调用 {@code UCPan.checkuccookie()/checktime()/deletetime()}
 *       （{@code UCPan.java} 内也没有自调用）。这三个 {@code static} 方法由 TVBox 宿主 +
 *       配置中心在外部按计划触发。⇒ 本次重写不提供触发逻辑，是设计事实而非遗漏；
 *       要定时跑请自行挂 {@link #checkTime()} / {@link #deleteTime()}。</li>
 *   <li>{@code merge.p026a.a.a(String)}（{@code UCPan.java:186}）的方法体未反编译，
 *       用法同夸克（包建目录 body 前缀），推定为恒等。无字节码佐证。</li>
 *   <li>{@code merge.p027b.h.b(a,b,c)} / {@code merge.p027b.v.b(k,v)} 未反编译；
 *       三串拼接 / 两元素 Map 由调用点语义反推。</li>
 *   <li>{@code merge.p036k.b.f(...).a()} 的包装语义按「POST 返回响应文本」处理；
 *       若它另有编码/解压，本类会漏。</li>
 *   <li>{@code checkuccookie()} 里 {@code strA2} 的<b>三态语义</b>无法从字节码完全证实：
 *       本实现按「主文件合法 JSON 但 {@code cookie} 字段为空 ⇒ 不再降级」复刻
 *       （见 {@link UCQuarkMaintBase#reloadCredential()} 的注释）。
 *       这条与 {@code PanCreds.read()} 的宽松版本并存，两者行为差异已写在注释里 ——
 *       若上游真实语义是"字段空也降级"，则二者的差异点就在这里。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <p><b>（a）UC/夸克共有（同构协议，代码收敛在 {@code UCQuarkMaintBase}）</b>
 * <ol>
 *   <li><b>{@code static} 可变状态跨实例污染</b>（{@code UCPan.java:32-33}）：
 *       {@code private static String d}（cookie）/{@code y}（UCtemp 的 fid）。
 *       多账号时 A 的 cookie 被 B 覆盖、转存落到别人的 UCtemp。
 *       → 已改为实例字段。断言：{@code ★ 跨品牌实例隔离（上游 static 会串）}。</li>
 *   <li><b>{@code contains("{")} 当 JSON 校验</b>（{@code UCPan.java:136,139,141}）。
 *       → 真解析 + 字段校验。断言：{@code ★ 畸形响应}。</li>
 *   <li><b>{@code SimpleDateFormat} 不指定 Locale</b>（{@code UCPan.java:39,87,154}）：
 *       泰语区佛历年份让"缓存≥今天"永不成立 ⇒ 每次启动重复转存。
 *       → 固定 {@code Locale.US}。</li>
 *   <li><b>缓存脏数据 {@code parseInt} 抛异常</b>（{@code UCPan.java:39,98}），
 *       且 {@code deletetime()} 外层无 try。→ 解析失败按 0（视为过期）。</li>
 *   <li><b>{@code deletetime()} 的 {@code substring(0,4/4,6/6,8)}</b>（{@code UCPan.java:98}）：
 *       缓存短于 8 字符即 {@code StringIndexOutOfBoundsException}。同上修正。</li>
 * </ol>
 * <p><b>（b）UC 独有</b>
 * <ol>
 *   <li><b>删文件不看响应</b>（{@code UCPan.java:83}）：返回值整份丢弃，风控时仍写
 *       {@code deleteuctime} ⇒ 7 天内不再重试。→ 保留时机 + 记日志 + 如实返回条数。</li>
 *   <li><b>建目录后不校验</b>（{@code UCPan.java:186}）→ 先查列表再建。</li>
 *   <li><b>{@code new Random().nextInt(len)} 随机挑文件</b>（{@code UCPan.java:147}）
 *       ⇒ 每 7 天转存内容不确定。→ 行为保留，随机源可注入。</li>
 *   <li><b>分享 id 硬编码</b>（{@code UCPan.java:129} 的 {@code c4015526cb494}）
 *       ⇒ 分享被撤即静默失效。→ 抽成 {@link #defaultShareId()}。</li>
 *   <li><b>{@code checktime()} 与 cookie 无耦合</b>（{@code UCPan.java:36-44}）：
 *       漏调 {@code checkuccookie()} 就会带空 Cookie 发请求。→ 空时自动补读。</li>
 *   <li><b>{@code uc_param_str=} 的有无不对称</b>（{@code UCPan.java:81} 子目录有、
 *       {@code :77/:83/:186} 根目录/删/建没有）—— 上游无注释，极易被"统一"掉。
 *       → 保留原样并在 {@link #subdirSortUrl} / {@link #deleteUrl} 注释里点名。
 *       断言：{@code ★ UC 独有}</li>
 *   <li><b>方法名易错</b>：上游是 {@code checkuccookie()}（非 {@code checkucookie()}）与
 *       {@code deleteUCTemp()}（大写 UC）。本实现的命名以协议语义为准，javadoc 标注原名。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/UCPan.java}；
 * 字段/方法清单: {@code ae/decompiled/_index.json}；
 * 解码器: {@code ae/decompiled/{a,b,c,d,e}.java}；
 * <b>字符串池解码参数逐调用点清单: {@code maintain/evidence/UCPan_strings.md}</b>（73 行，
 * 由 {@code ae/tools/pan_maint_strings.py} 从反编译源现算生成，含每处 {@code (from, len, seed)}）；
 * 全量 148 条: {@code ae/work/w-pan/decoded-strings.txt}。
 */
public class UCPanPlain extends UCQuarkMaintBase {

    /** 目录名（{@code L34 d.c(0,6,2959)}） */
    public static final String TEMP_DIR = "UCtemp";
    /** 续期日期缓存键（{@code L37 e.c(6,6,1979)}） */
    public static final String TIME_KEY = "uctime";
    /** 清理日期缓存键（{@code L88 a.e(566,12,1662)}） */
    public static final String DELETE_TIME_KEY = "deleteuctime";
    /** 默认分享 id（{@code L129 d.c(1194,13,494)}） */
    public static final String DEFAULT_SHARE_ID = "c4015526cb494";
    /** 凭据文件名 */
    public static final String COOKIE_FILE = "uc_cookie.txt";
    /** 主凭据完整路径（{@code L50 e.c(20,27,2191)}） */
    public static final String COOKIE_PATH_MAIN = "/sdcard/TVBox/uc_cookie.txt";
    /** 兜底凭据完整路径（{@code L61 e.c(53,24,1011)}） */
    public static final String COOKIE_PATH_FALLBACK = "/sdcard/TV/uc_cookie.txt";

    /** 完整 UA（{@code L110 d.c(674,190,2368)}，逐字保留） */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) uc-cloud-drive/1.8.5 Chrome/100.0.4896.160 "
          + "Electron/18.3.5.16-b62cf9c50d Safari/537.36 Channel/ucpan_other_ch";

    /** 接口域（UC 只有一个域） */
    public static final String API = "https://pc-api.uc.cn/1/clouddrive";

    /** 列子目录尾串（{@code L81 d.c(347,85,1759)}） */
    public static final String SUBDIR_TAIL =
            "&_page=1&_size=100&_fetch_total=1&_fetch_sub_dirs=0&_sort=file_type:asc,file_name:asc";

    @Override public String brandName() { return "UC"; }
    @Override public String brandCookieFile() { return COOKIE_FILE; }
    @Override public String brandTempDirName() { return TEMP_DIR; }
    @Override public String brandTimeKey() { return TIME_KEY; }
    @Override public String brandDeleteTimeKey() { return DELETE_TIME_KEY; }
    @Override public String defaultShareId() { return DEFAULT_SHARE_ID; }
    @Override public String brandUserAgent() { return UA; }
    @Override public String brandReferer() { return "https://drive.uc.cn/"; }
    @Override public String brandOrigin() { return "https://drive.uc.cn"; }

    /** 列根目录 —— {@code L77 d.c(83,158,695)}（无 {@code uc_param_str=}，{@code _size=200}） */
    @Override public String rootSortUrl() {
        return API + "/file/sort?pr=UCBrowser&fr=pc&pdir_fid=0&_page=1&_size=200"
                + "&_fetch_total=1&_fetch_sub_dirs=0&_sort=file_type:asc,file_name:asc";
    }

    /** 列子目录（{@code L81} 前缀 + fid + 尾串；<b>这里反而有</b> {@code uc_param_str=}） */
    @Override public String subdirSortUrl(String pdirFid) {
        return API + "/file/sort?pr=UCBrowser&fr=pc&uc_param_str=&pdir_fid="
                + (pdirFid == null ? "" : pdirFid) + SUBDIR_TAIL;
    }

    /** 删文件 —— {@code L83 c.e(494,64,2403)}（无 {@code uc_param_str=}） */
    @Override public String deleteUrl() {
        return API + "/file/delete?pr=UCBrowser&fr=pc";
    }

    /** 建目录 —— {@code L186 c.e(1770,57,611)} */
    @Override public String createDirUrl() {
        return API + "/file?pr=UCBrowser&fr=pc";
    }

    /** 换 stoken —— {@code L132 c.e(1208,74,467)} */
    @Override public String shareTokenUrl() {
        return API + "/share/sharepage/token?pr=UCBrowser&fr=pc";
    }

    /** 分享详情前缀 —— {@code L124 d.c(1096,83,2848)} */
    @Override public String shareDetailBase() {
        return API + "/share/sharepage/detail?pr=UCBrowser&fr=pc&pwd_id=";
    }

    /** 转存 —— {@code L150 e.c(1341,73,778)} */
    @Override public String shareSaveUrl() {
        return API + "/share/sharepage/save?pr=UCBrowser&fr=pc";
    }

    /**
     * 供测试断言用的「链路快照」。
     *
     * @return 有序 Map
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
