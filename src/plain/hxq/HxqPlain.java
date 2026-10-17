package com.example.tvbox.plain.hxq;

import android.text.TextUtils;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.net.AndroidBase64;
import com.example.tvbox.plain.player.PlayerResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;

/**
 * 韩小圈（Hxq / hxqapi.hiyun.tv）内容源重写。
 *
 * <p>反编译原文 {@code ae/decompiled/Hxq.java}（2838 行、76 个方法，几乎全是单字母命名）。
 * 本类把可还原的部分重写成可读实现；拿不到证据的部分逐条列在下面的
 * <b>未覆盖的分支</b> 里，测试也不会为它们编造断言。
 *
 * <h2>接口面</h2>
 * <pre>
 *   GET  /api/series/index?offset=N               首页"精选"
 *   GET  /api/series/cate?stype=&amp;sort=&amp;page=&amp;year=&amp;cate=/&amp;cid=  分类
 *   GET  /api/series/rank?rid=&amp;page=             榜单
 *   GET  /api/series/detail?sid=                   剧集详情
 *   GET  /api/series/rank                         榜单 id 表（用于给榜单命名）
 *   GET  /api/series2/detail/normal?sid=&amp;refer=  分集桶
 *   GET  /api/series2/episodes?sid=                分集列表
 *   GET  /api/series2/episode/detail?pid=&amp;refer= 剧集直链（加密）
 *   GET  /api/series/rslvV4?…&amp;sign=             播放地址解析（带签名，实测需要设备头）
 *   GET  /api/search/s5?k=&amp;srefer=&amp;type=0&amp;page=  搜索
 *   POST /api/common/configs                     首次握手（结果丢弃，只当"注册"）
 *   POST {rpc}/sign | {rpc}/dec                  自建 RPC（可选，失败走本地算法）
 * </pre>
 *
 * <h2>三个"加密"层，先分清</h2>
 * <ol>
 *   <li><b>设备头</b>：{@code uk} = AES-CBC(固定 K1/K2, 设备JSON) 的 Base64；密钥来自 {@code md5(uid)}
 *       （前 16 字节 key、后 16 字节 iv）。详见 {@link HxqCrypto}。</li>
 *   <li><b>剧集直链</b>：{@code sources[0].data} 用固定 S/IV 解 Base64 后是 JSON，
 *       取 {@code [0].smid} 作为"副 id"。</li>
 *   <li><b>接口信封</b>：响应里 {@code data} 是字符串时，用 {@code key}（缺省 {@code md5(uid+ts)}）
 *       派生 {@code md5(key+"34F9Q53w/HJW8E6Q")}，前 16/后 16 字节当 key/iv 解密，
 *       再剥掉控制字符。</li>
 * </ol>
 *
 * <h2>混淆名 → 语义名对照表（60 条，原文 76 个方法里的业务方法全在内）</h2>
 * <pre>
 *  原文        本类                           说明
 *  ─────────────────────────────────────────────────────────────────────────
 *  A          starSearch                     按姓名搜明星（/api/search/s5，srefer=star）
 *  B          cateList                       "拼版"首页：各类型各取一页再合并去重
 *  C          updating                       只取未完结（跨 1/2/3 各翻 3 页，按时间降序）
 *  D          starFilter                     明星页筛选组（热门/人气 + 姓氏表）
 *  E          firstNonEmpty                  第一个非空且非 "null"
 *  F          api                            统一接口访问（可选设备签名 + 信封解密）
 *  G          isM3u8                         m3u8 判定
 *  H          isMp4                          mp4 判定
 *  I          rankIds                        榜单 id 表 + 显示序（接口优先，内置表兜底）
 *  J          md5                            MD5 小写 hex
 *  K          mergePages                     多页合并（同 id 取时间更大者）+ 时间降序
 *  L          fixCdnUrl                      播放地址归一化（四类输入 + 51touxiang→CDN）
 *  M          paging                         分页响应（两种 pagecount 算法）
 *  N          window24                       按 24 条切窗口
 *  O          pickImage                      封面（image.{thumb,poster,url} → 顶层四个键）
 *  P          proxyUrl                       把地址包成自家代理 URL
 *  Q          rankFilter                     榜名单（接口 title 优先，"年榜"补当前年）
 *  R          detailRemarks                  详情备注（分类 · 集数 · 完结 · 分数）
 *  S          loadSources                    按 pid 取可播地址（含 carrier 重试）
 *  T          cachedSources                  带缓存的 loadSources
 *  U          rewritePlaylist                m3u8 相对地址补全 + query 白名单
 *  V          randomHex                      16 位随机 hex
 *  W          randomToken                    20 位随机串（uid）
 *  X          resetDevice                    重新生成设备指纹（原 boolean 门）
 *  Y          rpc                            自建 RPC（/sign、/dec）
 *  Z          decodeSources                  播放响应 → 地址列表（RPC/本地解密 + 四级提取）
 *  a          score                          评分（rank → rankScore → score）
 *  a0         categoryFilter                 分类页筛选组（类型/排序/年份）
 *  b          aesEncryptBase64               AES/CBC/PKCS5 + Base64
 *  b0         deviceHeaders                  设备签名头（said/uk/sign + 鉴权）
 *  c          aesDecrypt                     AES/CBC 解密（NoPadding 回退）
 *  c0         sortByTimeDesc                 按时间降序
 *  d          apiHeaders                     普通接口头
 *  d0         toStarItem                     明星条目 → 列表项（id=star|sid|name）
 *  e          toVodItems                     剧集数组 → 列表项
 *  e0         toStarItems                    明星数组 → 列表项（补"明星 · "）
 *  f          decodeText                     先补 padding 解 Base64，失败退回 URLDecode
 *  f0         htmlToText                     HTML → 纯文本
 *  f1         applyDetailText                crew 文本 → 导演/主演/地区/简介
 *  g          episodeGroup                   分集 → 剧集串（多源拆分 + ~~索引）
 *  g0         toVods                         列表项 → Vod
 *  h          streamHeaders                  取流用的头
 *  h0         toVodItem                      剧集条目 → 列表项
 *  i          deviceKey                      md5(uid)（原文 g 为空时兜底）
 *  j          decodeEnvelope                 响应信封解密（key 或 md5(uid+ts)）
 *  k          starDetail                     明星详情（作品 → 第一集剧集串）
 *  l          urlEncode                      URLEncoder 包装
 *  m          mapGet                         按顺序取 map 里第一个非空值
 *  n          buckets                        分集桶（缺省"正片"）
 *  o          cateAll                        指定类型翻页到底（带缓存）
 *  p          catePage                       分类接口一页（year/cate 与 cid 的拼法差异）
 *  q          seriesDetail                   剧集详情（/api/series/detail）
 *  r          episodeSource                  剧集直链（固定 S/IV 解 sources[0].data）
 *  s          episodes                       分集列表（三处来源）
 *  t          index                          首页精选（offset 固定 0）
 *  u          rankList                       榜单翻页到底（带缓存）
 *  v          allRanks                       全部榜单合并（带缓存）
 *  w          resolve                        rslvV4 解析（RPC 签名 → 本地签名回退）
 *  x          searchJson                     搜索接口
 *  y          normalDetail                   分集桶接口（/api/series2/detail/normal）
 *  z          starList                       明星榜（hot/rank，首次探测 16 个姓氏）
 *  Episode    Episode                        分集值对象
 *  VodItem    VodItem                        列表项值对象
 *  1Adder     addBlock                       首页板块累加器（匿名类）
 * </pre>
 *
 * <h2>未覆盖的分支（逐条说明"缺什么证据"）</h2>
 * <ol>
 *   <li><b>所有接口的真实响应样本</b> —— 字段路径（{@code seriesList} / {@code datas[].data} /
 *       {@code playItems} / {@code buckets} / {@code starList} / {@code rankings}…）全部来自静态反编译，
 *       <b>没有一次真实响应可对照</b>。要补齐需要：任一接口的真实响应体。</li>
 *   <li><b>服务端 {@code /sign} RPC 的算法</b> —— 见 {@link HxqCrypto} 说明。本类只在 RPC 返回空时
 *       走本地回退签名（与上游一致）。</li>
 *   <li><b>RPC 主机（ext 里的 {@code rpc}）的语义</b> —— 上游是 POST {@code {rpc}/{op}}，body 是明文 JSON，
 *       返回里取 {@code result} 或 {@code data}。<b>服务端返回什么格式没有样本</b>，只能按这两种键取。</li>
 *   <li><b>载波（carrier）三组 token 的用途</b> —— {@code q} 里的 {@code npHKizhZnQPBj4i_b07o} 等三个值
 *       会被当作"备选 pid"去解析地址（{@code S()}）。它们是什么（内置分享 token？预告片 id？）
 *       没有证据，只按原文顺序尝试。</li>
 *   <li><b>{@code ts=10401} 这个魔数</b> —— 上游用它判断"要不要重新生成设备指纹"（{@code 10401 = 设备失效} 的最可能解释），
 *       以及"限流"（{@code Z}/{@code S} 里的重试）。要确认需要一次真实错误响应。</li>
 *   <li><b>代理参数 {@code type} 的全部取值</b> —— 只见到 {@code mp4list|plist}、{@code hls|m3u8}，
 *       其余一律返回 {@code "ok"}。</li>
 *   <li><b>{@code g(List,boolean)} 的"{@code size=1}"原始形态</b> —— jadx 在该处产出 {@code size = 1; break;}
 *       这种<b>方法体里裸 break</b> 的非法代码（见原文 1316 行）。本类按控制流重建为
 *       "不拆分 → 每条一个地址"，并保留了探测逻辑；重建依据是紧随其后的
 *       {@code if (size &lt;= 1 || …)} 分支与 {@code ~~索引} 后缀（后者被 {@code playerContent} 消费）。</li>
 *   <li><b>{@code Z(JSONObject)} 的三级回退</b> —— jadx 在 {@code while(true)} 里产出
 *       {@code str2 = strL;}（{@code strL} 未赋值），不可直接照抄。本类重建为
 *       "四个直取键 → 正文里找完整 URL → 正文里找 20+ 位长 id"，三级顺序由原文的嵌套结构确定。
 *       <b>并且在真宿主上发现上游这段的第 2/3 级是坏的</b>：详见
 *       {@link #extractUrl} 与"已修复的上游缺陷"。</li>
 *   <li><b>{@code L(String)} 异常分支的返回值</b> —— 原文 catch 块<b>是空的</b>（缺少 return，
 *       在 Java 里无法编译，属 jadx 产物）。本类取 {@code ""}，与同类其余方法（{@code J/P/b/c/Y}）的失败约定一致。</li>
 *   <li><b>{@code R(JSONObject)} 的调用方</b> —— 原文里它是 {@code private static}，名字与
 *       {@code Episode} 撞车（jadx 把它标成 originally abstract）。本类按唯一被调用的语义
 *       （"详情备注"）实现，见 {@link #detailRemarks}。</li>
 *   <li><b>{@code Hxq.Z}（synthetic static int）</b> —— 上游只被 {@code Hxq$1Adder} 引用，是 javac
 *       合成匿名类时的 artifact，无业务语义，不实现。</li>
 * </ol>
 *
 * <h2>已修复的上游缺陷：{@code Z()} 的第 2/3 级回退在真机上必然失效</h2>
 * <p>上游的做法是把整个对象 {@code toString()} 之后用正则找地址：
 * <pre>
 *   strTrim = jSONObject.toString();          // ← 真宿主（旧版 org.json）会输出 {"note":"http:\/\/a\/b"}
 *   ... 正则 "https?://[^\s\"']+/(?:m3u8|video)/..." 在该文本上匹配
 * </pre>
 * <b>实测</b>（fmplayer 宿主的 {@code org.json}，见 {@code HxqTest} 的宿主行为钉）：
 * {@code new JSONObject().put("note","http://b.com/video/e.mp4").toString()}
 * 输出 {@code {"note":"http:\/\/b.com\/video\/e.mp4"}} —— 斜杠被转义成 {@code \/}，
 * 于是那两条正则<b>永远匹配不到</b>："正文里找完整 URL / 找长 id"这两级回退在真机上是死的。
 * （新版 json-java 不转义 {@code /}，所以在本项目的旧桩下反而是对的 —— 这正是它一直没被发现的原因。）
 * <p>本类修法：<b>先按上游原样在序列化文本上找一遍</b>（保持与新版库一致的行为），
 * 再<b>把 JSON 里的字符串值逐条拿出来</b>用同样的两条正则找一遍（见
 * {@link #stringValues}）。语义不变、覆盖变强：任何一代 {@code org.json} 下都能找到。
 *
 * <h2>已排查但不构成缺陷的疑点（看着像死代码，但先看清控制流）</h2>
 * <ol>
 *   <li><b>{@code e(JSONArray)} 里的
 *   {@code if ("0".equals("") || "1".equals(""))} / {@code if ("new".equals(""))} /
 *   {@code if ("hot".equals(""))}</b> —— 三处都是"过滤/排序"的参数被混淆器内联成常量空串，
 *   条件永远不成立。它们<b>确实是</b>被挖空的代码（不是"窄路径可达"），所以本类不实现，
 *   只保留可证实的映射与排序语义。与 QiShui 那条"看着像死分支、其实可达"的不同之处在于：
 *   这里的比较对象是<b>字面量空串本身</b>，无论什么输入都不可能成立。</li>
 *   <li><b>{@code g(List,boolean)} 里的 {@code size = 1; break;}</b> —— 方法体里裸 {@code break}，
 *   在 Java 里非法，属 jadx 把 {@code if} 体压成一行后的产物。本类按随后
 *   {@code if (size <= 1 || …)} 分支与 {@code ~~索引} 后缀重建为
 *   "不拆分 → 每条一个地址"，并保留"探测前 2 条"的上限（原文 {@code min(2, size)}）。</li>
 *   <li><b>{@code Z(JSONObject)} 里 {@code while(true)} 末尾的 {@code str2 = strL;}</b> ——
 *   {@code strL} 在该路径上没有赋值，同样是 jadx 产物。本类重建为
 *   "四个直取键 → 正文完整 URL → 正文长 id"三级回退（见 {@link #extractUrl}）。</li>
 *   <li><b>{@code u(String)} 里的 {@code ?? r1 = 0} 与 {@code for (int i2 = 20; i <= i2; …)}</b> ——
 *   前者是 jadx 未定类型的占位，后者只是"页码上限 20"。已按语义写死为
 *   {@link #RANK_MAX_PAGE}，不需要额外证据。</li>
 *   <li><b>{@code Q()} 里的双 {@code break}</b> —— 内层循环 break 之后紧跟外层 break，
 *   与"找到就停"等价，已合并成一次 break；<b>不是</b>逻辑缺失。</li>
 *   <li><b>{@code Hxq.Z}（synthetic static int）与 {@code Hxq$1Adder}</b> —— javac 为
 *   匿名内部类合成的产物（一个静态 int + 一个累加器类），无业务语义；
 *   累加器语义已由 {@link #addBlock} 承载。</li>
 *   <li><b>{@code R(JSONObject)} 被 jadx 标为 originally abstract</b> —— 原文里它既是
 *   "详情备注"静态方法又与内部类 {@code Episode} 撞名，是命名冲突而非抽象方法。
 *   按唯一调用点的语义实现为 {@link #detailRemarks}。</li>
 *   <li><b>{@code i()} 的 {@code this.g}</b> —— 它只是"设备密钥缓存"，为 null 时用
 *   {@code md5(uid)} 兜底（原文写法在 null 时有 NPE 风险）。本类显式判空，
 *   行为与"非 null 路径"完全一致，<b>不是</b>新语义。</li>
 *   <li><b>{@code o()}/{@code u()} 里那个永远为 0 的连续空页计数器</b> ——
 *   原文写成 {@code i2 = (list.isEmpty() || added == 0) ? i2 + 1 : 0;} 之后<b>立刻</b>
 *   {@code if (i2 >= 1 || !more) break;}，因此等价于"出现一次空页就停"。
 *   本类直接写这个等价行为，并把这个"计数变量其实是常量 0"的现象记在这里，
 *   避免后来者以为它真在数连续空页。</li>
 * </ol>
 *
 * <h2>上游缺陷（不改，只标注）</h2>
 * <ol>
 *   <li><b>已修复</b>：{@code o()}/{@code u()} 的翻页循环里没有"总量上限"，
 *       且 {@code i5}（连续空页计数）逻辑写成 {@code i5 = (list.isEmpty() || i4 == 0) ? i2+1 : 0;}
 *       后立刻判断 {@code if (i5 >= 1 || !more) break;} —— 等价于"只要出现一次空页就停"，
 *       变量 {@code i2} 永远是 0。本类保留这个"一个空页就停"的行为（它就是实际行为），
 *       但去掉无意义的计数变量。</li>
 *   <li><b>已修复</b>：{@code i()} 里 {@code TextUtils.isEmpty(this.g) ? J(this.c) : this.g} ——
 *       {@code g} 为 null 时 NPE 风险；本类改成显式判空。</li>
 *   <li><b>已保留</b>：{@code M(...)} 在 {@code total == null} 时把 <b>本页条数</b>写进 {@code total}
 *       字段（不是真实总数），这是上游原样行为。</li>
 *   <li><b>已保留</b>：{@code episodeGroup} 拆分模式下的剧集名 {@code "第N集 x/y"}，
 *       以及 {@code ~~索引} 后缀 —— 这是上游"一个 pid 多源"的表达方式，不能改。</li>
 *   <li><b>已修复</b>：{@code detailContent} 里对 {@code list.get(0)} 无保护；
 *       本类先判空再取。</li>
 * </ol>
 */
public class HxqPlain extends BaseSpider {

    private static final String TAG = "HxqPlain";

    // ── 协议常量（全部来自原文常量池） ──────────────────────

    /** 接口根（原文 {@code B}）。 */
    public static final String HOST = "https://hxqapi.hiyun.tv";
    /** App 版本（原文 {@code C}）。 */
    public static final String APP_VERSION = "6.9";
    /** 渠道号（原文 {@code D}）。 */
    public static final String APP_VC = "a_8350";
    /** 渠道标识（原文 {@code E}）。 */
    public static final String CHANNEL = "qq";
    /** 请求 UA（原文 {@code I}）。 */
    public static final String USER_AGENT = "HanjuTV/6.9 (25128PNA1C; Android 17; Scale/2.00)";
    /** 默认 refer（原文 {@code N}）。 */
    public static final String DEFAULT_REFER =
            "rSwCIIUMYCiHZXIVfgxlxylE9eGqfmwMg5V8mRSBB55vN8P3bNpXkUrv9MFhYOvm";
    /** CDN 根（原文 {@code Q}）。 */
    public static final String CDN_HOST = "https://voldn-ser01.51touxiang.com";
    /** 代理请求 UA（原文 {@code R}）。 */
    public static final String PROXY_UA = "tdc.8350";
    /** 姓氏表（原文 {@code W}），明星页的字母筛选就是按它逐字发的。 */
    public static final String SURNAMES =
            "金李朴崔郑姜赵尹张韩吴申黄安宋柳车全高许洪文河南白权元徐蔡成罗裴朱";

    /** 首页 5 个 tab：{id, 名称}（原文 {@code T}）。 */
    public static final String[][] TABS = {
            {"rank", "排行榜"},
            {"1", "韩剧"},
            {"2", "综艺"},
            {"3", "电影"},
            {"star", "明星"},
    };

    /** 3 组"备选 pid"（原文 {@code S}）。 */
    public static final String[] CARRIERS = {
            "npHKizhZnQPBj4i_b07o",
            "MexJZ4EcjqR8Yn1uw_i3",
            "PIeNuuJuDVVutWUwycHi",
    };

    /** 22 个榜单短名（原文 {@code Y}），与 {@link #RANK_ORDER} 一一对应。 */
    public static final String[] RANK_NAMES = {
            "日播榜", "周播榜", "月播榜", "2026年榜", "2025年榜", "2024年榜", "2023年榜",
            "2022年榜", "2021年榜", "2020年榜", "2019年榜", "热搜榜", "热剧榜", "高分榜",
            "口碑榜", "好评榜", "高甜榜", "高虐榜", "爱情榜", "古言榜", "悬疑榜", "奇幻榜",
    };

    /** 榜单 id → 展示序（原文 {@code X}）。 */
    public static final int[][] RANK_ORDER = {
            {1, 0}, {31, 1}, {2, 2}, {4, 3}, {38, 4}, {34, 5}, {35, 6}, {36, 7}, {20, 8},
            {18, 9}, {5, 10}, {39, 11}, {22, 12}, {3, 13}, {9, 14}, {21, 15}, {13, 16},
            {15, 17}, {12, 18}, {7, 19}, {10, 20}, {16, 21},
    };

    /** 分类 id → 名称（原文 {@code U}）。 */
    public static final Map<Integer, String> CATEGORY_NAMES = categoryNames();

    /** 分类 id → 类型筛选（原文 {@code V}）。 */
    public static final Map<String, String[][]> CATEGORY_FILTERS = categoryFilters();

    /** 排序选项（原文 {@code a0()} 里内联）。 */
    public static final String[][] SORT_OPTIONS = {{"最新", "new"}, {"最热", "hot"}, {"好评", "score"}};

    /** 年份选项（原文 {@code a0()} 里内联）。 */
    public static final String[][] YEAR_OPTIONS = {
            {"全部", "-1"}, {"2026", "2026"}, {"2025", "2025"}, {"2024", "2024"}, {"2023", "2023"},
            {"2022", "2022"}, {"2021", "2021"}, {"2020", "2020"}, {"2015-2019", "2015-2019"},
            {"更早", "1-2014"},
    };

    /** 明星页的固定两项 + 姓氏表（原文 {@code D()}）。 */
    public static final String[][] STAR_MODES = {
            {"热门明星", "hot"}, {"人气明星", "rank"},
    };

    // ── 分页 / 阈值常量 ────────────────────────────────────

    /** 首页"精选"接口每页条数（原文 {@code offset=…*16}）。 */
    public static final int INDEX_PAGE_SIZE = 16;
    /** 分类接口认为"还有下一页"的最小条数（原文 {@code >= 12}）。 */
    public static final int CATE_MORE_MIN = 12;
    /** 分类翻页上限（原文 {@code i > 60}）。 */
    public static final int CATE_MAX_PAGE = 60;
    /** 榜单接口认为"还有下一页"的最小条数（原文 {@code >= 8}）。 */
    public static final int RANK_MORE_MIN = 8;
    /** 榜单一次翻页上限（原文 {@code i <= 20}）。 */
    public static final int RANK_MAX_PAGE = 20;
    /** 明星接口认为"还有下一页"的最小条数（原文 {@code >= 15}）。 */
    public static final int STAR_MORE_MIN = 15;
    /** 明星探测次数（原文 {@code min(16, 姓氏表长度)}）。 */
    public static final int STAR_PROBE = 16;
    /** 剧集列表按 24 一页切（原文 {@code N()}）。 */
    public static final int LIST_PAGE_SIZE = 24;
    /** 明星页每页 20（原文 {@code z()}）。 */
    public static final int STAR_PAGE_SIZE = 20;
    /** 首页最多 48 条（原文 {@code if (size > 48)}）。 */
    public static final int HOME_MAX = 48;
    /** 首页每个板块最多 8 条（原文 {@code if (i >= 8) return}）。 */
    public static final int HOME_BLOCK_MAX = 8;
    /** "追剧不能停"一次取多少个分类 ×每类多少条（原文 {@code C(1, 16)}）。 */
    public static final int UPDATING_PER_CATE = 16;
    /** 详情里"最多几个播放源"（原文 {@code arrayList2.size() < 6}）。 */
    public static final int DETAIL_MAX_SOURCES = 6;
    /** 详情简介里"导演/主演/地区"最多保留多少字（原文未截断，这里是展示用常量）。 */
    public static final String SPLIT_MARK = "~~";
    /** 播放入口名。 */
    public static final String PLAY_FROM = "韩小圈";
    /** 明星详情播放入口名。 */
    public static final String STAR_PLAY_FROM = "韩小圈";
    /** 明星 id 前缀。 */
    public static final String STAR_PREFIX = "star|";
    /** 明星 id 前缀（用于判断是否明星详情）。 */
    public static final String STAR_ID_FLAG = "star|";
    /** 空剧集 id 前缀（假的"第N集"）。 */
    public static final String FAKE_EPISODE = "ep_";
    /** 设备指纹有效期（14 天，原文 {@code 1209600000}）。 */
    public static final long DEVICE_TTL_MS = 1209600000L;
    /** 设备失效/限流用的错误码（原文魔数）。 */
    public static final int RESCODE_DEVICE = 10401;
    /** 画质参数默认值（原文 {@code p = "10"}）。 */
    public static final String DEFAULT_QUALITY = "10";
    /** 内置"分类页一次全拿"的缓存：stype 兜底。 */
    public static final String TYPE_TV = "1";
    /** 综艺 stype。 */
    public static final String TYPE_VARIETY = "2";
    /** 电影 stype。 */
    public static final String TYPE_MOVIE = "3";
    /** "全部"筛选值。 */
    public static final String ALL = "-1";

    // ── 设备状态 ───────────────────────────────────────────

    private final Random random = new Random();
    private String host = HOST;
    private String uid = "";
    private String said = "";
    private String ai = "";
    private String oa = "";
    private String signKey = "";
    private String uk = "";
    private String refer = DEFAULT_REFER;
    private String rpcHost = "";
    private String authToken = "";
    private String authUid = "";
    private String quality = DEFAULT_QUALITY;
    private long installTime;
    private boolean deviceFromExt;
    private boolean useProxy = true;
    private boolean splitParts = true;
    private boolean registered;
    private List<String> carriers = new ArrayList<>(Arrays.asList(CARRIERS));

    private final Object sourceLock = new Object();
    private final Map<String, List<String>> sourceCache = new LinkedHashMap<>();
    private final Map<String, List<VodItem>> cateAllCache = new LinkedHashMap<>();
    private final Map<String, List<VodItem>> cateCache = new LinkedHashMap<>();
    private final Map<String, List<VodItem>> rankCache = new LinkedHashMap<>();
    private List<VodItem> rankAll;
    private List<int[]> rankIds;
    private List<VodItem> starHot;
    private List<VodItem> starRank;

    // ── 值对象 ─────────────────────────────────────────────

    /** 列表项（原文内部类 {@code VodItem}）。 */
    public static class VodItem {
        /** id（明星是 {@code star|sid|name}，剧集是 sid）。 */
        public String id;
        /** 名称。 */
        public String name;
        /** 封面。 */
        public String pic;
        /** 备注。 */
        public String remarks;
        /** 更新时间（毫秒）或发布时间。 */
        public long time;
        /** 粉丝数（明星用）。 */
        public int fans;
        /** 排行（明星用，缺省 {@code Integer.MAX_VALUE}）。 */
        public int rank = Integer.MAX_VALUE;

        /** 便捷构造。 */
        public VodItem() {
        }

        /** 便捷构造。 */
        public VodItem(String id, String name, String pic, String remarks) {
            this.id = id;
            this.name = name;
            this.pic = pic;
            this.remarks = remarks;
        }
    }

    /** 分集（原文内部类 {@code Episode}）。 */
    public static class Episode {
        /** 播放 id。 */
        public String pid;
        /** 集号。 */
        public int no;
        /** 分集标题。 */
        public String title;
        /** 是否不可播（权限 allowPlay=0 或 type=101）。 */
        public boolean locked;

        /** 便捷构造。 */
        public Episode() {
        }

        /** 便捷构造。 */
        public Episode(String pid, int no, String title, boolean locked) {
            this.pid = pid;
            this.no = no;
            this.title = title;
            this.locked = locked;
        }
    }

    /** 代理返回体（code + mime + 流）。 */
    public static class ProxyResult {
        /** HTTP 码。 */
        public final int code;
        /** MIME。 */
        public final String mime;
        /** 响应体。 */
        public final byte[] body;

        public ProxyResult(int code, String mime, byte[] body) {
            this.code = code;
            this.mime = mime;
            this.body = body == null ? new byte[0] : body;
        }

        /** 转成宿主约定的 {@code Object[]}。 */
        public Object[] toArray() {
            return new Object[]{Integer.valueOf(code), mime, new ByteArrayInputStream(body)};
        }
    }

    // ── 传输 seam（测试覆写这三个） ─────────────────────────

    /**
     * 可覆写的 GET（文本）。
     *
     * @param url     完整 URL
     * @param headers 头
     * @return 响应文本；失败 ""
     */
    public String fetch(String url, Map<String, String> headers) {
        return http.get(url, headers);
    }

    /**
     * 可覆写的 POST（文本）。
     *
     * @param url     完整 URL
     * @param body    体
     * @param headers 头
     * @return 响应文本；失败 ""
     */
    public String fetchPost(String url, String body, Map<String, String> headers) {
        return http.post(url, body, headers);
    }

    /**
     * 可覆写的 GET（二进制，代理用）。
     *
     * @param url     完整 URL
     * @param headers 头
     * @return 响应体；失败 null
     */
    public byte[] fetchBytes(String url, Map<String, String> headers) {
        return null;
    }

    // ── 初始化 ─────────────────────────────────────────────

    /**
     * 原文 {@code init(Context,String)}：重置设备并解析 ext。
     *
     * <p>ext 支持的键：{@code uid / said / refer / rpc / auth-token|auth_token /
     * auth-uid|auth_uid / use_proxy / split_parts / sq / carrier}。
     * 给了 {@code uid} 就不再自动轮换设备指纹。
     *
     * @param context 宿主上下文（忽略）
     * @param ext     ext 字符串
     */
    @Override
    public void init(android.content.Context context, String ext) {
        super.init(context, ext);
        resetDevice();
        this.refer = DEFAULT_REFER;
        this.rpcHost = "";
        this.authToken = "";
        this.authUid = "";
        this.deviceFromExt = false;
        this.useProxy = true;
        this.splitParts = true;
        this.quality = DEFAULT_QUALITY;
        this.carriers = new ArrayList<>(Arrays.asList(CARRIERS));
        synchronized (sourceLock) {
            sourceCache.clear();
        }
        cateAllCache.clear();
        cateCache.clear();
        rankCache.clear();
        rankAll = null;
        rankIds = null;
        starHot = null;
        starRank = null;

        String text = ext == null ? "" : ext.trim();
        if (!text.startsWith("{")) {
            return;
        }
        try {
            JSONObject o = new JSONObject(text);
            String extUid = o.optString("uid");
            if (!TextUtils.isEmpty(extUid)) {
                this.uid = extUid;
                this.signKey = HxqCrypto.deviceKey(extUid);
                this.uk = HxqCrypto.deviceUplink(this.signKey, deviceBody(System.currentTimeMillis()));
                this.registered = false;
                this.deviceFromExt = true;
            }
            if (!TextUtils.isEmpty(o.optString("said"))) {
                this.said = o.optString("said");
            }
            if (!TextUtils.isEmpty(o.optString("refer"))) {
                this.refer = o.optString("refer");
            }
            if (!TextUtils.isEmpty(o.optString("rpc"))) {
                this.rpcHost = HxqCrypto.trimSlash(o.optString("rpc"));
            }
            String token = HxqCrypto.firstNonEmpty(o.optString("auth-token"), o.optString("auth_token"));
            if (!TextUtils.isEmpty(token)) {
                this.authToken = token;
            }
            String tokenUid = HxqCrypto.firstNonEmpty(o.optString("auth-uid"), o.optString("auth_uid"));
            if (!TextUtils.isEmpty(tokenUid)) {
                this.authUid = tokenUid;
            }
            if (o.has("use_proxy")) {
                String v = String.valueOf(o.opt("use_proxy"));
                if ("0".equals(v) || "false".equalsIgnoreCase(v)) {
                    this.useProxy = false;
                }
            }
            if (o.has("split_parts")) {
                String v = String.valueOf(o.opt("split_parts"));
                if ("0".equals(v) || "false".equalsIgnoreCase(v)) {
                    this.splitParts = false;
                }
                if ("1".equals(v) || "true".equalsIgnoreCase(v)) {
                    this.splitParts = true;
                }
            }
            if (o.has("sq") && !TextUtils.isEmpty(o.optString("sq"))) {
                this.quality = o.optString("sq");
            }
            if (!TextUtils.isEmpty(o.optString("carrier"))) {
                List<String> list = new ArrayList<>();
                list.add(o.optString("carrier"));
                for (String c : CARRIERS) {
                    if (!list.contains(c)) {
                        list.add(c);
                    }
                }
                this.carriers = list;
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "init ext 解析失败", e);
        }
    }

    /**
     * 原文 {@code X()}：重新生成一套设备指纹。
     *
     * <p>上游用它处理"设备失效/限流"重试；本类把它做成公开方法，便于测试固定住随机源。
     */
    public void resetDevice() {
        this.ai = HxqCrypto.randomHex(random, 16);
        this.oa = HxqCrypto.randomHex(random, 16);
        this.installTime = System.currentTimeMillis() - DEVICE_TTL_MS;
        this.uid = HxqCrypto.randomToken(random, 20);
        this.signKey = HxqCrypto.deviceKey(this.uid);
        this.uk = HxqCrypto.deviceUplink(this.signKey, deviceBody(System.currentTimeMillis()));
        this.registered = false;
    }

    /**
     * 原文 {@code b0()} 用的设备 JSON（时间由调用方给，便于测试）。
     *
     * @param nowMs 当前毫秒
     * @return JSON 串
     */
    public String deviceBody(long nowMs) {
        return HxqCrypto.buildDeviceJson(
                new HxqCrypto.Device(uid, installTime, ai, oa), nowMs);
    }

    /**
     * 原文 {@code b0()}：上行头（设备签名 + 鉴权）。
     *
     * @return 可变头表
     */
    public HashMap<String, String> deviceHeaders() {
        HashMap<String, String> map = apiHeaders();
        map.put("said", said);
        map.put("uk", uk);
        map.put("sign", HxqCrypto.deviceUplink(signKey, deviceBody(System.currentTimeMillis())));
        if (!TextUtils.isEmpty(authToken)) {
            map.put("auth-token", authToken);
        }
        if (!TextUtils.isEmpty(authUid)) {
            map.put("auth-uid", authUid);
        }
        return map;
    }

    /**
     * 原文 {@code d()}：普通接口头。
     *
     * @return 可变头表
     */
    public HashMap<String, String> apiHeaders() {
        HashMap<String, String> map = new HashMap<>();
        map.put("vc", APP_VC);
        map.put("vn", APP_VERSION);
        map.put("ch", CHANNEL);
        map.put("app", "hj");
        map.put("User-Agent", USER_AGENT);
        map.put("Accept-Encoding", "gzip");
        map.put("Connection", "Keep-Alive");
        return map;
    }

    /**
     * 原文 {@code h()}：代理取流用的头。
     *
     * @return 可变头表
     */
    public static HashMap<String, String> streamHeaders() {
        HashMap<String, String> map = new HashMap<>();
        map.put("User-Agent", PROXY_UA);
        map.put("Accept", "*/*");
        return map;
    }

    /**
     * 原文 {@code i()}：设备指纹 key（{@code md5(uid)}）。
     *
     * @return 32 字符 hex
     */
    public String deviceKey() {
        if (TextUtils.isEmpty(signKey)) {
            signKey = HxqCrypto.deviceKey(uid);
        }
        return signKey;
    }

    /** 当前 uid。 */
    public String uid() {
        return uid;
    }

    /** 当前 said。 */
    public String said() {
        return said;
    }

    /** 当前 uk。 */
    public String uk() {
        return uk;
    }

    /** 当前画质参数。 */
    public String quality() {
        return quality;
    }

    /** 是否走代理。 */
    public boolean useProxy() {
        return useProxy;
    }

    /** 是否拆分多源剧集。 */
    public boolean splitParts() {
        return splitParts;
    }

    /** 是否用了 ext 指定的 uid。 */
    public boolean deviceFromExt() {
        return deviceFromExt;
    }

    /** 当前 refer（原文 {@code this.i}）。 */
    public String refer() {
        return refer;
    }

    /** 当前 RPC 主机（原文 {@code this.j}）。 */
    public String rpcHost() {
        return rpcHost;
    }

    /** 当前 auth-token（原文 {@code this.k}）。 */
    public String authToken() {
        return authToken;
    }

    /** 当前 auth-uid（原文 {@code this.l}）。 */
    public String authUid() {
        return authUid;
    }

    /** 设备随机串 ai（原文 {@code this.d}）。 */
    public String ai() {
        return ai;
    }

    /** 设备随机串 oa（原文 {@code this.e}）。 */
    public String oa() {
        return oa;
    }

    /** 设备安装时间（原文 {@code this.b}）。 */
    public long installTime() {
        return installTime;
    }

    /** 当前备选 pid 表。 */
    public List<String> carriers() {
        return new ArrayList<>(carriers);
    }

    // ── 接口访问（原文 F / Y） ──────────────────────────────

    /**
     * 原文 {@code F(String,boolean,boolean)}：一次接口访问。
     *
     * @param path     形如 {@code /api/...}
     * @param withDevice 是否设备签名（并首次触发 {@code /api/common/configs} 注册）
     * @param decrypt  响应 {@code data} 是字符串时是否解密
     * @return JSON；任何失败返回空对象
     */
    public JSONObject api(String path, boolean withDevice, boolean decrypt) {
        try {
            Map<String, String> headers = withDevice ? deviceHeaders() : apiHeaders();
            if (withDevice && !registered) {
                try {
                    fetchPost(host + "/api/common/configs", deviceBody(System.currentTimeMillis()),
                            deviceHeaders());
                } catch (Exception e) {
                    android.util.Log.w(TAG, "configs 注册失败（忽略）", e);
                }
                registered = true;
            }
            String body = fetch(host + path, headers);
            if (TextUtils.isEmpty(body)) {
                return new JSONObject();
            }
            JSONObject o = new JSONObject(body);
            if (decrypt && (o.opt("data") instanceof String)) {
                return decodeEnvelope(o);
            }
            return o;
        } catch (Exception e) {
            android.util.Log.w(TAG, "api 失败: " + path, e);
            return new JSONObject();
        }
    }

    /**
     * 原文 {@code Y(String,JSONObject)}：自建 RPC。
     *
     * @param op   操作名（{@code sign} / {@code dec}）
     * @param body 请求体
     * @return {@code result} 或 {@code data}；失败 ""
     */
    public String rpc(String op, JSONObject body) {
        if (TextUtils.isEmpty(rpcHost)) {
            return "";
        }
        try {
            String resp = fetchPost(rpcHost + "/" + op,
                    body == null ? "{}" : body.toString(), apiHeaders());
            if (TextUtils.isEmpty(resp)) {
                return "";
            }
            JSONObject o = new JSONObject(resp);
            return HxqCrypto.firstNonEmpty(o.optString("result"), o.optString("data"));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 原文 {@code j(JSONObject)}：把响应信封里的 {@code data} 解开。
     *
     * <p>key 优先取 {@code key}，其次 {@code md5(uid+ts)}；再由
     * {@code md5(key+"34F9Q53w/HJW8E6Q")} 派生出 AES key/iv。
     *
     * @param envelope 响应对象
     * @return 解密后的对象；解不开原样返回
     */
    public JSONObject decodeEnvelope(JSONObject envelope) {
        try {
            String data = envelope.optString("data");
            if (TextUtils.isEmpty(data)) {
                return envelope;
            }
            String key = envelope.optString("key");
            if (TextUtils.isEmpty(key)) {
                key = HxqCrypto.tsKey(uid, envelope.optString("ts"));
            }
            if (TextUtils.isEmpty(key)) {
                return envelope;
            }
            String derived = HxqCrypto.deriveKeyIv(key);
            byte[] plain = HxqCrypto.aesDecrypt(AndroidBase64.decode(data, AndroidBase64.DEFAULT),
                    HxqCrypto.key16(derived).getBytes(HxqCrypto.UTF8),
                    HxqCrypto.iv16(derived).getBytes(HxqCrypto.UTF8));
            return new JSONObject(new String(plain, HxqCrypto.UTF8)
                    .replaceAll("[\\x00-\\x1f\\x7f-\\x9f]", ""));
        } catch (Exception e) {
            return envelope;
        }
    }

    // ── 剧集直链（原文 r / s / g / T / S） ─────────────────

    /**
     * 原文 {@code r(String)}：拿 {@code [scid, smid]}。
     *
     * <p>请求 {@code /api/series2/episode/detail?pid=…&refer=…}，取
     * {@code playItem.sources[0].data}，用固定 S/IV 解 Base64 → JSON 数组 → {@code [0].smid}。
     *
     * @param pid 剧集 pid
     * @return 长度 2 的数组；失败 {"", ""}
     */
    public String[] episodeSource(String pid) {
        JSONObject playItem = api("/api/series2/episode/detail?pid=" + HxqCrypto.urlEncode(pid)
                + "&refer=" + HxqCrypto.urlEncode(refer), true, true).optJSONObject("playItem");
        if (playItem == null) {
            playItem = new JSONObject();
        }
        JSONArray sources = playItem.optJSONArray("sources");
        JSONObject first = (sources == null || sources.length() <= 0) ? new JSONObject()
                : sources.optJSONObject(0);
        if (first == null) {
            first = new JSONObject();
        }
        String scid = first.optString("scid");
        String data = first.optString("data");
        String smid = "";
        if (!TextUtils.isEmpty(data)) {
            String plain = "";
            try {
                plain = HxqCrypto.aesDecryptToString(AndroidBase64.decode(data, AndroidBase64.DEFAULT),
                        HxqCrypto.KEY_S, HxqCrypto.IV_S);
            } catch (Exception e) {
                plain = "";
            }
            try {
                JSONArray arr = new JSONArray(plain);
                if (arr.length() > 0) {
                    smid = arr.optJSONObject(0).optString("smid");
                }
            } catch (Exception e) {
                android.util.Log.w(TAG, "episodeSource data 非 JSON 数组", e);
            }
        }
        return new String[]{scid, smid};
    }

    /**
     * 原文 {@code s(String,JSONObject)}：分集列表（三处来源）。
     *
     * <p>顺序：传入的 {@code playItems} → {@code /api/series2/detail/normal} 的 {@code playItems}
     * → {@code /api/series2/episodes}。集号取 {@code serialNo}，其次 {@code sort}，都缺则用序号。
     * 最后按集号升序排序。
     *
     * @param sid      剧集 id
     * @param fallback 已经拿到的对象（可为 null）
     * @return 分集列表
     */
    public List<Episode> episodes(String sid, JSONObject fallback) {
        JSONObject src = fallback == null ? new JSONObject() : fallback;
        JSONArray items = src.optJSONArray("playItems");
        if (items == null || items.length() == 0) {
            items = normalDetail(sid).optJSONArray("playItems");
        }
        if (items == null || items.length() == 0) {
            try {
                items = api("/api/series2/episodes?sid=" + HxqCrypto.urlEncode(sid), true, true)
                        .optJSONArray("playItems");
            } catch (Exception e) {
                items = null;
            }
        }
        List<Episode> out = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String pid = HxqCrypto.firstNonEmpty(o.optString("pid"), o.optString("programId"),
                        o.optString("id"));
                if (TextUtils.isEmpty(pid)) {
                    continue;
                }
                int no = i + 1;
                try {
                    if (o.has("serialNo")) {
                        no = Integer.parseInt(String.valueOf(o.opt("serialNo")));
                    } else if (o.has("sort")) {
                        no = Integer.parseInt(String.valueOf(o.opt("sort")));
                    }
                } catch (Exception ignored) {
                    // 解析失败保留序号
                }
                Episode ep = new Episode();
                ep.pid = pid;
                ep.no = no;
                ep.title = HxqCrypto.firstNonEmpty(o.optString("title"), o.optString("name"));
                JSONObject permission = o.optJSONObject("permission");
                ep.locked = permission != null
                        && (permission.optInt("allowPlay", 1) == 0 || permission.optInt("type") == 101);
                out.add(ep);
            }
            Collections.sort(out, new Comparator<Episode>() {
                @Override
                public int compare(Episode a, Episode b) {
                    return Integer.compare(a.no, b.no);
                }
            });
        }
        return out;
    }

    /**
     * 原文 {@code S(String,boolean)}：按 pid 取"可播地址列表"。
     *
     * <p>先把 pid 解成 {@code [scid, smid]}，调 {@code w()} 拿播放数据；
     * 若 {@code rescode == 10401}（设备失效）或数据为空，则依次换用
     * {@link #carriers} 里的备选 pid 重试；再不行就重生成设备指纹重试。
     *
     * @param pid   剧集 id
     * @param quick true 时只看第一个备选
     * @return 地址列表（可能为空）
     */
    public List<String> loadSources(String pid, boolean quick) {
        if (TextUtils.isEmpty(pid) || pid.startsWith(FAKE_EPISODE)) {
            return new ArrayList<>();
        }
        String scid;
        try {
            scid = episodeSource(pid)[0];
        } catch (Exception e) {
            scid = "";
        }
        if (TextUtils.isEmpty(scid)) {
            return new ArrayList<>();
        }
        Object[] reply = resolve(pid, scid);
        JSONObject data = (JSONObject) reply[0];
        int code = ((Integer) reply[1]).intValue();
        List<String> urls = decodeSources(data);
        if (!urls.isEmpty()) {
            return urls;
        }
        if (code == RESCODE_DEVICE || data == null) {
            List<String> candidates = new ArrayList<>();
            for (String c : carriers) {
                if (!TextUtils.isEmpty(c) && !c.equals(pid) && !candidates.contains(c)) {
                    candidates.add(c);
                }
            }
            int limit = quick ? Math.min(2, candidates.size()) : candidates.size();
            for (int i = 0; i < limit; i++) {
                String alt = candidates.get(i);
                Object[] altReply = resolve(alt, scid);
                List<String> altUrls = decodeSources((JSONObject) altReply[0]);
                if (!altUrls.isEmpty()) {
                    return altUrls;
                }
                if (quick) {
                    break;
                }
                if (((Integer) altReply[1]).intValue() == RESCODE_DEVICE) {
                    resetDevice();
                    List<String> retry = decodeSources((JSONObject) resolve(alt, scid)[0]);
                    if (!retry.isEmpty()) {
                        return retry;
                    }
                }
            }
        }
        if (quick) {
            return new ArrayList<>();
        }
        for (int i = 0; i < 3; i++) {
            if (!resetDeviceIfNeeded()) {
                break;
            }
            Object[] retry = resolve(pid, scid);
            List<String> urls2 = decodeSources((JSONObject) retry[0]);
            if (!urls2.isEmpty()) {
                return urls2;
            }
            if (((Integer) retry[1]).intValue() != RESCODE_DEVICE) {
                break;
            }
        }
        return new ArrayList<>();
    }

    /** 原文里那个"还能不能再生成设备指纹"的门（{@code X()} 返回 boolean）。 */
    private boolean resetDeviceIfNeeded() {
        resetDevice();
        return true;
    }

    /**
     * 原文 {@code T(String,boolean)}：带缓存的 {@link #loadSources}。
     *
     * @param pid   剧集 id
     * @param quick 快速模式
     * @return 地址列表
     */
    public List<String> cachedSources(String pid, boolean quick) {
        synchronized (sourceLock) {
            List<String> hit = sourceCache.get(pid);
            if (hit != null) {
                return new ArrayList<>(hit);
            }
            List<String> urls = loadSources(pid, quick);
            if (!urls.isEmpty()) {
                sourceCache.put(pid, new ArrayList<>(urls));
            }
            return new ArrayList<>(urls);
        }
    }

    // ── 播放解析（原文 w / Z） ──────────────────────────────

    /**
     * 原文 {@code w(String,String)}：{@code /api/series/rslvV4} 解析。
     *
     * <p>先拼查询串，再要一个签名：优先 RPC {@code /sign}，失败用 {@link HxqCrypto#signQuery}。
     * 拿到签名才发请求；{@code rescode==0} 返回 {@code {json, 0}}，否则 {@code {null, rescode}}。
     *
     * @param pid  剧集 id
     * @param scid 源 id
     * @return {@code [JSONObject 或 null, Integer rescode]}
     */
    public Object[] resolve(String pid, String scid) {
        long now = System.currentTimeMillis() / 1000;
        String secret = HxqCrypto.randomSecret(random);
        String sq = TextUtils.isEmpty(quality) ? DEFAULT_QUALITY : quality;
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("t", String.valueOf(now));
        params.put("dt", HxqCrypto.DT);
        params.put("version", APP_VERSION);
        params.put("uuid", secret);
        params.put("pid", pid);
        params.put("scid", scid);
        params.put("re", "1");
        params.put("sq", sq);
        params.put("udid", deviceKey());
        String query = HxqCrypto.signSource(params);

        String sign = "";
        try {
            JSONObject req = new JSONObject();
            req.put("qs", query);
            req.put("uid", uid);
            req.put("said", said);
            sign = rpc("sign", req);
        } catch (Exception e) {
            sign = "";
        }
        if (TextUtils.isEmpty(sign)) {
            sign = HxqCrypto.signQuery(query);
        }
        if (TextUtils.isEmpty(sign)) {
            return new Object[]{null, Integer.valueOf(-1)};
        }
        try {
            String url = host + "/api/series/rslvV4?"
                    + HxqCrypto.rslvQuery(now, APP_VERSION, secret, pid, scid, sq)
                    + "&sign=" + HxqCrypto.urlEncode(sign);
            String body = fetch(url, deviceHeaders());
            if (TextUtils.isEmpty(body)) {
                return new Object[]{null, Integer.valueOf(-1)};
            }
            JSONObject o = new JSONObject(body);
            int rescode = o.optInt("rescode", -1);
            if (rescode == 0) {
                return new Object[]{o, Integer.valueOf(0)};
            }
            return new Object[]{null, Integer.valueOf(rescode)};
        } catch (Exception e) {
            return new Object[]{null, Integer.valueOf(-1)};
        }
    }

    /**
     * 原文 {@code Z(JSONObject)}：从播放响应里挖出地址。
     *
     * <p>每个 {@code datas[].data} 先试 RPC {@code /dec}，失败再本地解密
     * （{@code md5(smid+"34F9Q53w/HJW8E6Q")} 派生 key/iv）。解出来是 JSON，
     * 依次找 {@code playUrl/url/page/m3u8}；都没有就在正文里找完整 URL，
     * 再退到"20+ 位长 id"拼 CDN 地址。结果用 {@link HxqCrypto#fixCdnUrl} 归一化。
     *
     * @param response 播放响应
     * @return 去重后的地址列表
     */
    public List<String> decodeSources(JSONObject response) {
        List<String> out = new ArrayList<>();
        if (response == null) {
            return out;
        }
        JSONArray datas = response.optJSONArray("datas");
        if (datas == null) {
            return out;
        }
        for (int i = 0; i < datas.length(); i++) {
            JSONObject item = datas.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String data = item.optString("data");
            if (TextUtils.isEmpty(data)) {
                continue;
            }
            String smid = deviceKey();
            String plain;
            try {
                JSONObject req = new JSONObject();
                req.put("data", data);
                req.put("extra", smid);
                req.put("smid", smid);
                req.put("uid", uid);
                plain = rpc("dec", req);
            } catch (Exception e) {
                plain = "";
            }
            if (TextUtils.isEmpty(plain)) {
                String raw;
                try {
                    String derived = HxqCrypto.deriveKeyIv(smid);
                    raw = new String(HxqCrypto.aesDecrypt(
                            AndroidBase64.decode(data, AndroidBase64.DEFAULT),
                            HxqCrypto.key16(derived).getBytes(HxqCrypto.UTF8),
                            HxqCrypto.iv16(derived).getBytes(HxqCrypto.UTF8)), HxqCrypto.UTF8);
                } catch (Exception e) {
                    raw = "";
                }
                plain = raw;
            }
            String url = extractUrl(plain);
            if (!TextUtils.isEmpty(url) && !out.contains(url)) {
                out.add(url);
            }
        }
        return out;
    }

    /**
     * 原文 {@code Z()} 里"从解出来的文本里找地址"的<b>重建</b>（原 jadx 产物不可直接照抄，见类注释）。
     *
     * <p><b>级内择一顺序不保证</b>：若同一级里有多个候选（例如两个不同的完整 URL），
     * 选中哪一个取决于宿主的键迭代顺序。上游同样如此（它是在序列化文本上做正则，
     * 等价于文档顺序）。这里只承诺<b>跨级</b>优先级：完整 URL 一定优先于长 id。
     *
     * @param text 解密后的文本（可能是 JSON）
     * @return 地址；找不到 ""
     */
    public static String extractUrl(String text) {
        String trim = text == null ? "" : text.trim();
        if (TextUtils.isEmpty(trim)) {
            return "";
        }
        try {
            JSONObject o = new JSONObject(trim);
            for (String key : new String[]{"playUrl", "url", "page", "m3u8"}) {
                String v = o.optString(key);
                if (!TextUtils.isEmpty(v)) {
                    return fixCdnUrl(v);
                }
            }
            // 第一通道：把对象序列化后当纯文本找（= 上游原样的做法）。
            // 第二通道：按"字符串值"逐条找 —— 见下方 javadoc：真宿主的 org.json 会把
            // `/` 转义成 `\/`，第一通道在真机上<b>永远命中不了</b>，只有第二通道能救。
            String json = o.toString();
            Matcher full = HxqCrypto.P_FULL_URL.matcher(json);
            if (full.find()) {
                return fixCdnUrl(unslash(full.group(0)));
            }
            for (String value : stringValues(o)) {
                Matcher byValue = HxqCrypto.P_FULL_URL.matcher(value);
                if (byValue.find()) {
                    return fixCdnUrl(byValue.group(0));
                }
            }
            Matcher longId = HxqCrypto.P_M3U8_LONG.matcher(json);
            if (longId.find()) {
                return HxqCrypto.trimSlash(CDN_HOST) + "/m3u8/" + longId.group(1) + ".m3u8";
            }
            for (String value : stringValues(o)) {
                Matcher byValue = HxqCrypto.P_M3U8_LONG.matcher(value);
                if (byValue.find()) {
                    return HxqCrypto.trimSlash(CDN_HOST) + "/m3u8/" + byValue.group(1) + ".m3u8";
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 把一个 {@code JSONObject} 里所有<b>字符串值</b>收集出来。
     *
     * <p>为什么需要它：见 {@link #extractUrl} 的说明 —— 靠 {@code toString()} 之后的文本做正则
     * 会踩到"宿主把 {@code /} 转义成 {@code \/}"这个坑，所以必须能直接看值本身。
     * 遍历用 {@code keys()}（新旧两代 {@code org.json} 都有），不用 {@code keySet()}。
     *
     * <p><b>顺序不是契约</b>：{@code keys()} 的迭代顺序由宿主 {@code org.json} 决定
     * （旧版是 {@code HashMap} 散列序、新版是 {@code LinkedHashMap} 插入序），
     * 所以本方法的返回顺序在真宿主上<b>与插入顺序无关</b>。
     * 调用方<b>不要</b>把"第几条"当优先级依据 —— {@link #extractUrl} 只依赖
     * "先整型找完整 URL、再找长 id"这个<b>跨级</b>优先级，那是与键序无关的。
     *
     * @param node 任意 JSON 节点（{@code JSONObject}/{@code JSONArray}/标量/null）
     * @return 字符串值列表（顺序 = 宿主键迭代顺序，勿依赖）
     */
    public static List<String> stringValues(Object node) {
        List<String> out = new ArrayList<>();
        collectStrings(node, out);
        return out;
    }

    /** 递归实现，见 {@link #stringValues}。 */
    private static void collectStrings(Object node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node instanceof String) {
            out.add((String) node);
            return;
        }
        if (node instanceof JSONObject) {
            Iterator<String> it = ((JSONObject) node).keys();
            while (it.hasNext()) {
                collectStrings(((JSONObject) node).opt(it.next()), out);
            }
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collectStrings(array.opt(i), out);
            }
        }
    }

    /**
     * 去掉 JSON 转义产生的反斜杠（只处理 {@code \/}，其余原样）。
     *
     * <p>第一通道（序列化文本）在<b>新版</b> {@code org.json} 下能命中，那时结果里不含 {@code \/}；
     * 但万一宿主在别处插入了转义，归一化前先还原一下更稳。
     *
     * @param s 文本
     * @return 还原后的文本
     */
    public static String unslash(String s) {
        return s == null ? "" : s.replace("\\/", "/");
    }

    // ── 地址归一化（原文 L / U） ───────────────────────────

    /**
     * 原文 {@code L(String)}：把各种形态的播放地址归一化成 CDN 地址。
     *
     * <p>四类输入：{@code /m3u8/xxx}、{@code /video/xxx.mp4}、纯长 id、完整 URL。
     * 主机名里带 {@code 51touxiang.com} 的一律换成 {@link #CDN_HOST}。
     *
     * @param raw 原地址
     * @return 归一化地址；空入参或解析失败返回 ""
     */
    public static String fixCdnUrl(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (TextUtils.isEmpty(s)) {
            return "";
        }
        String cdn = HxqCrypto.trimSlash(CDN_HOST);
        if (s.startsWith("/m3u8/") || s.startsWith("/video/")) {
            s = cdn + s;
        }
        try {
            URI uri = URI.create(s);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
            String scheme = "https";
            String host = URI.create(cdn).getHost();
            String srcHost = uri.getHost();
            if (srcHost != null && srcHost.contains("51touxiang.com")) {
                host = URI.create(cdn).getHost();
            } else if (srcHost != null) {
                scheme = TextUtils.isEmpty(uri.getScheme()) ? "https" : uri.getScheme();
                host = srcHost;
            }
            Matcher m3u8 = HxqCrypto.P_M3U8_TAIL.matcher(path);
            if (m3u8.find()) {
                return scheme + "://" + host + "/m3u8/" + m3u8.group(1) + ".m3u8"
                        + (TextUtils.isEmpty(query) ? "" : "?" + query);
            }
            if (HxqCrypto.matches(HxqCrypto.P_VIDEO_MP4, path)) {
                return scheme + "://" + host + path + (TextUtils.isEmpty(query) ? "" : "?" + query);
            }
            if (HxqCrypto.matches(HxqCrypto.P_BARE_ID, s)) {
                return cdn + "/m3u8/" + s + ".m3u8";
            }
            if (s.startsWith("http") && (s.contains(".m3u8") || s.contains(".mp4"))) {
                return scheme + "://" + host + path + (TextUtils.isEmpty(query) ? "" : "?" + query);
            }
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 原文 {@code U(String,String)}：把 m3u8 播放列表里的相对地址补全。
     *
     * <p>行为：只保留原列表里 {@code pid/utk/uk/spl/t/sign/ip} 这几个查询参数
     * （顺序固定、值 URL 解码后再编码），拼到每个没有查询串的地址后面；
     * 注释行原样保留；以 {@code /} 开头的行用 CDN 根补全；其他相对行按原 URL 的目录解析。
     *
     * @param playlist 播放列表文本
     * @param baseUrl  原 URL（提供目录与查询串）
     * @return 新播放列表
     */
    public static String rewritePlaylist(String playlist, String baseUrl) {
        String text = playlist == null ? "" : playlist;
        try {
            URI uri = URI.create(baseUrl);
            String cdn = HxqCrypto.trimSlash(CDN_HOST);
            LinkedHashMap<String, String> params = HxqCrypto.parseQuery(uri.getRawQuery());
            StringBuilder kept = new StringBuilder();
            for (String key : new String[]{"pid", "utk", "uk", "spl", "t", "sign", "ip"}) {
                String v = params.get(key);
                if (v != null && !v.isEmpty()) {
                    if (kept.length() > 0) {
                        kept.append('&');
                    }
                    kept.append(HxqCrypto.urlEncode(key)).append('=').append(HxqCrypto.urlEncode(v));
                }
            }
            String rawQuery = uri.getRawQuery();
            String query = kept.length() > 0 ? kept.toString()
                    : (rawQuery == null ? "" : rawQuery);
            String suffix = TextUtils.isEmpty(query) ? "" : "?" + query;

            StringBuilder out = new StringBuilder();
            for (String line : text.split("\n", -1)) {
                String clean = line.replace("\r", "");
                String trim = clean.trim();
                if (TextUtils.isEmpty(trim) || trim.startsWith("#")) {
                    out.append(clean);
                } else {
                    String fixed;
                    if (trim.startsWith("http://") || trim.startsWith("https://")) {
                        fixed = fixCdnUrl(trim);
                    } else if (trim.startsWith("/")) {
                        fixed = cdn + trim;
                    } else {
                        String path = uri.getPath() == null ? "/" : uri.getPath();
                        int slash = path.lastIndexOf('/');
                        fixed = URI.create(cdn + (slash >= 0 ? path.substring(0, slash + 1) : "/"))
                                .resolve(trim).toString();
                    }
                    if (!fixed.contains("?") && !TextUtils.isEmpty(suffix)) {
                        fixed = fixed + suffix;
                    }
                    out.append(fixed);
                }
                out.append("\n");
            }
            return out.toString();
        } catch (Exception e) {
            return text;
        }
    }

    // ── 明细 / 备注（原文 O / R / a / D / Q / I / a0） ──────

    /**
     * 原文 {@code O(JSONObject)}：封面。
     *
     * <p>先看 {@code image.{thumb,poster,url}}，再看顶层的
     * {@code thumb/poster/posterThumb/pic}。
     *
     * @param o 对象（可为 null）
     * @return 封面地址；找不到 ""
     */
    public static String pickImage(JSONObject o) {
        if (o == null) {
            return "";
        }
        JSONObject image = o.optJSONObject("image");
        if (image != null) {
            for (String k : new String[]{"thumb", "poster", "url"}) {
                String v = image.optString(k);
                if (!TextUtils.isEmpty(v)) {
                    return v;
                }
            }
        }
        for (String k : new String[]{"thumb", "poster", "posterThumb", "pic"}) {
            String v = o.optString(k);
            if (!TextUtils.isEmpty(v)) {
                return v;
            }
        }
        return "";
    }

    /**
     * 原文 {@code R(JSONObject)}：详情备注。
     *
     * <p>依次拼：分类名 · 集数（{@code count → lastSerialNo → totalEpisode}）· 完结状态 · 分数。
     *
     * @param o 详情对象
     * @return 备注；无内容 ""
     */
    public static String detailRemarks(JSONObject o) {
        if (o == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        int category = o.optInt("category", 0);
        String name = CATEGORY_NAMES.get(Integer.valueOf(category));
        if (name != null) {
            parts.add(name);
        }
        Object count = o.opt("count");
        if (count == null || "".equals(String.valueOf(count))) {
            count = o.opt("lastSerialNo");
        }
        if (count == null || "".equals(String.valueOf(count))) {
            count = o.opt("totalEpisode");
        }
        if (count != null && !"".equals(String.valueOf(count)) && !"null".equals(String.valueOf(count))) {
            parts.add(count + "集");
        }
        if (o.has("isFinished")) {
            parts.add(o.optBoolean("isFinished") ? "完结" : "更新中");
        }
        String score = HxqCrypto.firstNonEmpty(o.optString("score"), o.optString("rankScore"));
        if (!TextUtils.isEmpty(score)) {
            parts.add(score);
        }
        return TextUtils.join(" · ", parts);
    }

    /**
     * 原文 {@code a(JSONObject)}：取评分（{@code rank → rankScore → score}）。
     *
     * @param o 对象
     * @return 分数；解析不出 0
     */
    public static double score(JSONObject o) {
        if (o == null) {
            return 0.0d;
        }
        for (String k : new String[]{"rank", "rankScore", "score"}) {
            try {
                if (o.has(k)) {
                    return Double.parseDouble(String.valueOf(o.opt(k)));
                }
            } catch (Exception ignored) {
                // 换下一个字段
            }
        }
        return 0.0d;
    }

    /**
     * 原文 {@code D()}：明星页筛选（热门/人气 + 姓氏表）。
     *
     * @return 一个筛选组 {@code {key:"smode", name:"筛选", value:[…]}}
     */
    public static JSONObject starFilter() {
        JSONArray values = new JSONArray();
        try {
            for (String[] mode : STAR_MODES) {
                values.put(new JSONObject().put("n", mode[0]).put("v", mode[1]));
            }
            for (int i = 0; i < SURNAMES.length(); i++) {
                String ch = String.valueOf(SURNAMES.charAt(i));
                values.put(new JSONObject().put("n", ch).put("v", "name:" + ch));
            }
            return new JSONObject().put("key", "smode").put("name", "筛选").put("value", values);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * 原文 {@code a0(String)}：分类页筛选（类型 / 排序 / 年份）。
     *
     * @param stype 分类 id
     * @return 3 个筛选组
     */
    public static JSONArray categoryFilter(String stype) {
        JSONArray out = new JSONArray();
        try {
            String[][] types = CATEGORY_FILTERS.get(stype);
            if (types == null) {
                types = new String[][]{{"全部", ALL}};
            }
            JSONArray values = new JSONArray();
            for (String[] t : types) {
                values.put(new JSONObject().put("n", t[0]).put("v", t[1]));
            }
            out.put(new JSONObject().put("key", "cid").put("name", "类型").put("value", values));

            JSONArray sorts = new JSONArray();
            for (String[] s : SORT_OPTIONS) {
                sorts.put(new JSONObject().put("n", s[0]).put("v", s[1]));
            }
            out.put(new JSONObject().put("key", "sort").put("name", "排序").put("value", sorts));

            JSONArray years = new JSONArray();
            for (String[] y : YEAR_OPTIONS) {
                years.put(new JSONObject().put("n", y[0]).put("v", y[1]));
            }
            out.put(new JSONObject().put("key", "year").put("name", "年份").put("value", years));
        } catch (Exception e) {
            android.util.Log.e(TAG, "categoryFilter 构造失败", e);
        }
        return out;
    }

    /**
     * 原文 {@code I()}：榜单 id 列表 + 显示序。
     *
     * <p>先问 {@code /api/series/rank} 的 {@code rankings}，拿不到就用内置 {@link #RANK_ORDER}；
     * 再按内置序表排序（表外的一律排到 {@code 100}，同序按 id 降序）。
     *
     * @return {@code [[rid, 序], …]}
     */
    public List<int[]> rankIds() {
        if (rankIds != null && !rankIds.isEmpty()) {
            return rankIds;
        }
        List<int[]> list = new ArrayList<>();
        try {
            JSONArray arr = api("/api/series/rank", false, false).optJSONArray("rankings");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null && o.has("rid")) {
                        list.add(new int[]{o.optInt("rid"), i});
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "rankIds 接口失败，用内置表", e);
        }
        if (list.isEmpty()) {
            for (int[] pair : RANK_ORDER) {
                list.add(new int[]{pair[0], pair[1]});
            }
        }
        final Map<Integer, Integer> order = new HashMap<>();
        for (int[] pair : RANK_ORDER) {
            order.put(Integer.valueOf(pair[0]), Integer.valueOf(pair[1]));
        }
        Collections.sort(list, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                int ia = order.containsKey(Integer.valueOf(a[0]))
                        ? order.get(Integer.valueOf(a[0])).intValue() : 100;
                int ib = order.containsKey(Integer.valueOf(b[0]))
                        ? order.get(Integer.valueOf(b[0])).intValue() : 100;
                return ia != ib ? Integer.compare(ia, ib) : Integer.compare(b[0], a[0]);
            }
        });
        this.rankIds = list;
        return list;
    }

    /**
     * 原文 {@code Q()}：榜单筛选值。
     *
     * <p>榜单名优先取接口 {@code title}（{@code "年榜"} 会替换成"当前年份年榜"），
     * 其次用内置 {@link #RANK_NAMES}，最后兜底 {@code "榜N"}。
     *
     * @return {@code [{n,v}…]}（含开头的"全部"）
     */
    public JSONArray rankFilter() {
        JSONArray out = new JSONArray();
        try {
            out.put(new JSONObject().put("n", "全部").put("v", ALL));
            JSONArray rankings = null;
            for (int[] pair : rankIds()) {
                int rid = pair[0];
                String label = null;
                for (int i = 0; i < RANK_ORDER.length; i++) {
                    if (RANK_ORDER[i][0] == rid) {
                        label = RANK_NAMES[i];
                        break;
                    }
                }
                if (label == null) {
                    if (rankings == null) {
                        rankings = api("/api/series/rank", false, false).optJSONArray("rankings");
                    }
                    if (rankings != null) {
                        for (int i = 0; i < rankings.length(); i++) {
                            JSONObject o = rankings.optJSONObject(i);
                            if (o != null && o.optInt("rid") == rid) {
                                String title = o.optString("title").trim();
                                if ("年榜".equals(title)) {
                                    title = Calendar.getInstance().get(1) + "年榜";
                                }
                                label = TextUtils.isEmpty(title) ? "榜" + rid : title;
                                break;
                            }
                        }
                    }
                }
                if (label == null) {
                    label = "榜" + rid;
                }
                out.put(new JSONObject().put("n", label).put("v", String.valueOf(rid)));
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "rankFilter 失败", e);
        }
        return out;
    }

    // ── 列表项映射（原文 h0 / d0 / e / e0 / K / c0 / g0 / M / N） ──

    /**
     * 原文 {@code h0(JSONObject)}：接口条目 → 列表项。
     *
     * @param o 条目
     * @return 列表项；缺 id/name 返回 null
     */
    public static VodItem toVodItem(JSONObject o) {
        if (o == null) {
            return null;
        }
        String id = HxqCrypto.firstNonEmpty(o.optString("sid"), o.optString("seriesId"), o.optString("id"));
        String name = HxqCrypto.firstNonEmpty(o.optString("name"), o.optString("title"));
        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) {
            return null;
        }
        VodItem item = new VodItem();
        item.id = id;
        item.name = name;
        item.pic = pickImage(o);
        item.remarks = detailRemarks(o);
        long time = 0L;
        try {
            time = Long.parseLong(String.valueOf(o.opt("updateTime")));
        } catch (Exception ignored) {
            time = 0L;
        }
        if (time == 0L) {
            try {
                time = Long.parseLong(String.valueOf(o.opt("publishTime")));
            } catch (Exception ignored) {
                // 保持 0
            }
        }
        item.time = time;
        return item;
    }

    /**
     * 原文 {@code d0(JSONObject)}：明星条目 → 列表项（id 是 {@code star|sid|name}）。
     *
     * @param o 条目
     * @return 列表项；缺 id/name 返回 null
     */
    public static VodItem toStarItem(JSONObject o) {
        if (o == null) {
            return null;
        }
        String id = o.optString(o.has("sid") ? "sid" : "id");
        String name = o.optString("name").trim();
        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) {
            return null;
        }
        int fans = 0;
        try {
            fans = Integer.parseInt(String.valueOf(o.opt("fansCount")));
        } catch (Exception ignored) {
            fans = 0;
        }
        int rank = Integer.MAX_VALUE;
        try {
            rank = Integer.parseInt(String.valueOf(o.opt("rank")));
        } catch (Exception ignored) {
            rank = Integer.MAX_VALUE;
        }
        VodItem item = new VodItem();
        item.id = STAR_PREFIX + id + "|" + name;
        item.name = name;
        item.pic = HxqCrypto.firstNonEmpty(o.optString("thumb"), pickImage(o));
        item.remarks = fans > 0 ? "粉丝 " + fans : "明星";
        item.fans = fans;
        item.rank = rank;
        return item;
    }

    /**
     * 原文 {@code e(JSONArray)}：剧集列表映射（含"只看未完结"与"按时间/评分排序"两段
     * <b>已被混淆器拆掉的死代码</b>，见类注释；这里只实现可证实的行为）。
     *
     * @param arr 数组
     * @return 列表项列表
     */
    public static List<VodItem> toVodItems(JSONArray arr) {
        List<JSONObject> raw = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    raw.add(o);
                }
            }
        }
        List<VodItem> out = new ArrayList<>();
        for (JSONObject o : raw) {
            VodItem item = toVodItem(o);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * 原文 {@code e0(JSONArray)}：明星列表映射（备注不以"明星"开头时补 {@code "明星 · "}）。
     *
     * @param arr 数组
     * @return 列表项列表
     */
    public static List<VodItem> toStarItems(JSONArray arr) {
        List<VodItem> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            VodItem item = toStarItem(arr.optJSONObject(i));
            if (item == null) {
                continue;
            }
            String remarks = item.remarks == null ? "明星" : item.remarks;
            if (!remarks.startsWith("明星")) {
                item.remarks = "明星 · " + remarks;
            }
            out.add(item);
        }
        return out;
    }

    /**
     * 原文 {@code K(ArrayList)}：把多页结果按 id 合并（同 id 取 {@code time} 更大者），再排序。
     *
     * @param pages 多页列表
     * @return 合并后的列表
     */
    public static List<VodItem> mergePages(List<List<VodItem>> pages) {
        LinkedHashMap<String, VodItem> merged = new LinkedHashMap<>();
        if (pages != null) {
            for (List<VodItem> page : pages) {
                if (page == null) {
                    continue;
                }
                for (VodItem item : page) {
                    if (item == null || TextUtils.isEmpty(item.id)) {
                        continue;
                    }
                    VodItem old = merged.get(item.id);
                    if (old == null || item.time >= old.time) {
                        merged.put(item.id, item);
                    }
                }
            }
        }
        return sortByTimeDesc(new ArrayList<>(merged.values()));
    }

    /**
     * 原文 {@code c0(ArrayList)}：按 {@code time} 降序。
     *
     * @param list 列表
     * @return 新列表
     */
    public static List<VodItem> sortByTimeDesc(List<VodItem> list) {
        List<VodItem> out = new ArrayList<>(list == null ? new ArrayList<VodItem>() : list);
        Collections.sort(out, new Comparator<VodItem>() {
            @Override
            public int compare(VodItem a, VodItem b) {
                return Long.compare(b.time, a.time);
            }
        });
        return out;
    }

    /**
     * 原文 {@code g0(List)}：列表项 → {@link Vod}。
     *
     * @param list 列表项
     * @return Vod 列表
     */
    public static List<Vod> toVods(List<VodItem> list) {
        List<Vod> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        for (VodItem item : list) {
            if (item == null) {
                continue;
            }
            out.add(new Vod()
                    .id(item.id)
                    .name(item.name)
                    .pic(item.pic)
                    .remarks(item.remarks == null ? "" : item.remarks));
        }
        return out;
    }

    /**
     * 原文 {@code N(ArrayList,int)}：按 24 一页切窗口。
     *
     * @param list 全量列表
     * @param page 页码
     * @return {@code [本页列表, 是否还有更多]}
     */
    public static Object[] window24(List<VodItem> list, int page) {
        int p = Math.max(1, page);
        int size = Math.max(1, LIST_PAGE_SIZE);
        int from = (p - 1) * size;
        List<VodItem> slice = from >= list.size() ? new ArrayList<VodItem>()
                : new ArrayList<>(list.subList(from, Math.min(list.size(), from + size)));
        return new Object[]{slice, Boolean.valueOf(from + size < list.size())};
    }

    /**
     * 原文 {@code M(List,int,int,boolean,Integer)}：分页响应。
     *
     * <p>{@code pagecount} 有两条完全不同的算法：
     * <ul>
     *   <li>{@code total == null}（搜索/分类）：{@code more ? page+1 : page}，且 {@code total} 字段
     *       直接写<b>本页条数</b>（上游原样行为）。</li>
     *   <li>{@code total != null}（榜单）：{@code total == 0 ? 1 : max(1, ceil(total/limit))}。</li>
     * </ul>
     * {@link VodBuilder} 只能按 total 反推 pagecount，表达不了这两条，所以这里<b>显式构造</b>
     * page/pagecount/limit/total/list，值全部与上游一致。
     *
     * @param list  本页列表项
     * @param page  页码
     * @param limit 每页条数
     * @param more  是否还有下一页
     * @param total 真实总数（可为 null）
     * @return 标准列表 JSON
     */
    public static String paging(List<VodItem> list, int page, int limit, boolean more, Integer total) {
        List<VodItem> items = list == null ? new ArrayList<VodItem>() : list;
        int p = Math.max(1, page);
        int l = Math.max(1, limit);
        int totalValue;
        int pageCount;
        if (total == null) {
            totalValue = items.size();
            pageCount = more ? p + 1 : p;
        } else {
            totalValue = Math.max(0, total.intValue());
            pageCount = totalValue != 0 ? Math.max(1, (totalValue + l - 1) / l) : 1;
        }
        JSONObject out = new JSONObject();
        JSONArray array = new JSONArray();
        try {
            out.put("page", p);
            out.put("pagecount", pageCount);
            out.put("limit", l);
            out.put("total", totalValue);
            for (Vod v : toVods(items)) {
                array.put(listItem(v));
            }
            out.put("list", array);
        } catch (Exception e) {
            android.util.Log.e(TAG, "paging 构造失败", e);
        }
        return out.toString();
    }

    /**
     * 列表项形状：与 {@code VodBuilder} 内部列表项一致（去掉播放字段）。
     *
     * @param v Vod
     * @return JSON
     */
    public static JSONObject listItem(Vod v) {
        JSONObject o = VodBuilder.toJson(v);
        try {
            o.remove("vod_play_from");
            o.remove("vod_play_url");
            o.remove("vod_content");
            o.remove("vod_actor");
            o.remove("vod_director");
        } catch (Exception ignored) {
            // stub 不支持 remove 时保持原样
        }
        return o;
    }

    /**
     * 空列表响应（原文 {@code c.i(new ArrayList())}）。
     *
     * @return {@code {"list":[]}}
     */
    public static String empty() {
        return VodBuilder.create().addAll(new ArrayList<Vod>()).searchContent();
    }

    /**
     * 单条详情响应（原文 {@code c.i(list)}）。
     *
     * @param vod 详情
     * @return {@code {"list":[...]}}
     */
    public static String detailResponse(Vod vod) {
        return VodBuilder.create().add(vod).detailContent();
    }

    // ── 播放（原文 playerContent / localProxy） ─────────────

    /**
     * 原文 {@code playerContent(String,String,List)}。
     *
     * <p>三种入口：完整 http 地址直接返回；带 {@code ~~索引} 的取指定源；
     * 其余按剧集 id 解析出地址列表，多源时把它们当"多段"塞进 url（宿主按 {@code |} 拆）。
     *
     * @param flag     播放源标识（忽略）
     * @param id       播放 id
     * @param vipFlags 需解析标识（忽略）
     * @return 播放 JSON（含弹幕地址）
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String playId = id == null ? "" : id.trim();
        HashMap<String, String> headers = streamHeaders();
        try {
            if (playId.startsWith("http://") || playId.startsWith("https://")) {
                return withHeaders(playId, 0, headers);
            }
            if (playId.contains("|")) {
                playId = playId.substring(playId.lastIndexOf(124) + 1).trim();
            }
            if (playId.contains("$")) {
                playId = playId.substring(playId.lastIndexOf(36) + 1).trim();
            }
            Integer index = null;
            if (playId.contains(SPLIT_MARK)) {
                int at = playId.lastIndexOf(SPLIT_MARK);
                String tail = playId.substring(at + SPLIT_MARK.length());
                playId = playId.substring(0, at);
                try {
                    index = Integer.valueOf(Integer.parseInt(tail));
                } catch (Exception e) {
                    index = Integer.valueOf(0);
                }
            }
            List<String> urls = cachedSources(playId, false);
            if (urls.isEmpty()) {
                return withHeaders("", 0, headers, "解析失败");
            }
            if (index != null) {
                int i = index.intValue();
                if (i < 0 || i >= urls.size()) {
                    i = Math.min(Math.max(i, 0), urls.size() - 1);
                }
                return withHeaders(mayProxy(urls.get(i), "hls"), 0, headers);
            }
            if (urls.size() > 1) {
                for (String u : urls) {
                    if (!HxqCrypto.isMp4(u)) {
                        return withHeaders(mayProxy(u, "hls"), 0, headers);
                    }
                }
                List<String> proxied = new ArrayList<>();
                for (String u : urls) {
                    proxied.add(u);
                }
                return PlayerResponse.urls(proxied, headers);
            }
            return withHeaders(mayProxy(urls.get(0), "hls"), 0, headers);
        } catch (Exception e) {
            android.util.Log.w(TAG, "playerContent 失败", e);
            return "";
        }
    }

    /** 需要时把 m3u8 换成自家代理地址（{@code n} = use_proxy）。 */
    private String mayProxy(String url, String type) {
        if (useProxy && HxqCrypto.isM3u8(url)) {
            String proxied = proxyUrl(url, type);
            if (!TextUtils.isEmpty(proxied)) {
                return proxied;
            }
        }
        return url;
    }

    /** 拼播放响应（带 header，可选 msg）。 */
    private static String withHeaders(String url, int parse, Map<String, String> headers) {
        return PlayerResponse.build(url, parse, headers);
    }

    /** 拼播放响应 + msg。 */
    private static String withHeaders(String url, int parse, Map<String, String> headers, String msg) {
        return PlayerResponse.build(url, parse, headers, msg);
    }

    /**
     * 原文 {@code P(String,String)}：把地址包成自家代理 URL。
     *
     * <p>形如 {@code {proxyUrl}?do=hxq&type=hls&url=<urlEncoded(base64(url))>}；
     * 代理未启用（拿不到地址）时返回 ""。
     *
     * @param url  原地址
     * @param type 代理类型
     * @return 代理地址；不可用 ""
     */
    public String proxyUrl(String url, String type) {
        try {
            String base = proxyBaseUrl();
            if (TextUtils.isEmpty(base)) {
                return "";
            }
            String sep = base.contains("?") ? "&" : "?";
            return base + sep + "do=hxq&type=" + type + "&url="
                    + HxqCrypto.urlEncode(AndroidBase64.encodeToString(
                    (url == null ? "" : url).getBytes(HxqCrypto.UTF8), AndroidBase64.DEFAULT));
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 代理入口地址（原文 {@code merge.p017a.a.getUrl()}，即宿主的本地代理）。
     *
     * @return 代理地址；没有代理时 ""
     */
    public String proxyBaseUrl() {
        return com.example.tvbox.plain.frame.ProxyPlain.getUrl();
    }

    /**
     * 代理入口。
     *
     * <p>原文这里是 {@code public static Object[] proxy(Map)}（TVBox 用反射直接调静态方法）。
     * 本仓的 {@code Spider} 桩把它声明成<b>实例方法</b>，静态会直接编译不过，
     * 所以这里改成实例方法 + {@code @Override}，行为不变（转调 {@link #localProxy}）。
     * 这是宿主接口形态差异，不是语义差异。
     *
     * @param params 请求参数
     * @return 宿主约定数组
     */
    @Override
    public Object[] proxy(Map<String, String> params) {
        return localProxy(params);
    }

    /**
     * 原文 {@code proxyLocal(Map)}：等价 {@link #localProxy}。
     *
     * @param params 请求参数
     * @return 宿主约定数组
     */
    @Override
    public Object[] proxyLocal(Map<String, String> params) {
        return localProxy(params);
    }

    /**
     * 原文 {@code localProxy(Map)}：本地代理。
     *
     * <p>四类请求：
     * <ol>
     *   <li>{@code type=mp4list|plist}：把 body 里的 URL 数组合成 VOD 播放列表
     *       （{@code #EXT-X-TARGETDURATION:600}，每段 {@code #EXTINF:600}）。</li>
     *   <li>{@code type=hls|m3u8}：取流；如果不是 mp4 且正文以 {@code #EXTM3U} 开头，
     *       用 {@link #rewritePlaylist} 重写后回给播放器。</li>
     *   <li>其他 type：返回 {@code "ok"}。</li>
     *   <li>URL 不合法：400。</li>
     * </ol>
     *
     * @param params 请求参数（{@code type} / {@code url}）
     * @return {@code [code, mime, InputStream]}
     */
    public Object[] localProxy(Map<String, String> params) {
        Map<String, String> map = params == null ? new HashMap<String, String>() : params;
        String type = String.valueOf(map.get("type") == null ? "" : map.get("type")).toLowerCase(Locale.US);
        String raw = map.get("url") == null ? "" : map.get("url");
        String url = HxqCrypto.decodeText(raw);
        if (!url.startsWith("http") && !url.trim().startsWith("[")) {
            try {
                url = URLDecoder.decode(raw, "UTF-8");
            } catch (Exception ignored) {
                // 保持原串
            }
        }
        if (!url.startsWith("http") && !url.trim().startsWith("[")) {
            return new ProxyResult(400, "text/plain", "bad url".getBytes(HxqCrypto.UTF8)).toArray();
        }
        if ("mp4list".equals(type) || "plist".equals(type)) {
            JSONArray arr;
            try {
                arr = new JSONArray(url);
            } catch (Exception e) {
                try {
                    arr = new JSONArray(HxqCrypto.decodeText(raw));
                } catch (Exception e2) {
                    arr = new JSONArray();
                }
            }
            if (arr.length() == 0) {
                return new ProxyResult(400, "text/plain",
                        "bad mp4 list".getBytes(HxqCrypto.UTF8)).toArray();
            }
            StringBuilder sb = new StringBuilder();
            sb.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n");
            sb.append("#EXT-X-TARGETDURATION:600\n#EXT-X-MEDIA-SEQUENCE:0\n");
            for (int i = 0; i < arr.length(); i++) {
                String segment = String.valueOf(arr.opt(i)).trim();
                if (!TextUtils.isEmpty(segment)) {
                    sb.append("#EXTINF:600,\n").append(segment).append("\n");
                }
            }
            sb.append("#EXT-X-ENDLIST\n");
            return new ProxyResult(200, "application/vnd.apple.mpegurl",
                    sb.toString().getBytes(HxqCrypto.UTF8)).toArray();
        }
        if (!"hls".equals(type) && !"m3u8".equals(type)) {
            return new ProxyResult(200, "text/plain", "ok".getBytes(HxqCrypto.UTF8)).toArray();
        }
        try {
            byte[] body = fetchBytes(url, streamHeaders());
            if (body == null) {
                return new ProxyResult(500, "text/plain", "error".getBytes(HxqCrypto.UTF8)).toArray();
            }
            String head = body.length >= 16
                    ? new String(body, 0, Math.min(16, body.length), HxqCrypto.UTF8) : "";
            String path = url.toLowerCase(Locale.US).split("\\?", 2)[0];
            if (!head.contains("ftyp") && !path.endsWith(".mp4") && !url.contains("/video/")) {
                String text = new String(body, HxqCrypto.UTF8);
                if (!text.trim().startsWith("#EXTM3U")) {
                    return new ProxyResult(200, "application/octet-stream", body).toArray();
                }
                return new ProxyResult(200, "application/vnd.apple.mpegurl",
                        rewritePlaylist(text, url).getBytes(HxqCrypto.UTF8)).toArray();
            }
            return new ProxyResult(200, "video/mp4", body).toArray();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "error" : e.getMessage();
            return new ProxyResult(500, "text/plain", msg.getBytes(HxqCrypto.UTF8)).toArray();
        }
    }

    /**
     * 原文 {@code isVideoFormat(String)}：自家代理地址不算视频（避免宿主二次解析）。
     *
     * @param url 地址
     * @return 是否视为视频地址
     */
    @Override
    public boolean isVideoFormat(String url) {
        String s = url == null ? "" : url.toLowerCase(Locale.US);
        if (s.contains("type=hls") || s.contains("type=m3u8") || s.contains("type=mp4list")) {
            return false;
        }
        return s.contains(".m3u8") || s.contains(".mp4");
    }

    /**
     * 原文 {@code manualVideoCheck()}。
     *
     * @return 恒 true
     */
    @Override
    public boolean manualVideoCheck() {
        return true;
    }

    // ── 爬虫入口：首页 ──────────────────────────────────────

    /**
     * 原文 {@code homeContent(boolean)}：5 个 tab + 各自的筛选。
     *
     * <p>上游这里<b>不发任何请求</b>（榜单名用内置表；{@code Q()} 只在表里没有该 rid 时才回查接口）。
     *
     * @param filter 宿主是否要筛选（忽略）
     * @return 首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        try {
            VodBuilder vb = VodBuilder.create();
            for (String[] tab : TABS) {
                vb.clazz(tab[0], tab[1]);
            }
            vb.filter("rank", new JSONArray().put(
                    new JSONObject().put("key", "rid").put("name", "榜单").put("value", rankFilter())));
            vb.filter("1", categoryFilter("1"));
            vb.filter("2", categoryFilter("2"));
            vb.filter("3", categoryFilter("3"));
            vb.filter("star", new JSONArray().put(starFilter()));
            return vb.homeContent();
        } catch (Exception e) {
            android.util.Log.e(TAG, "homeContent 失败", e);
            return "";
        }
    }

    /**
     * 原文 {@code homeVideoContent()}：5 个板块各取若干条，去重后最多 48 条。
     *
     * <p>板块：精选（{@code t()}）、热门推荐（韩剧 hot 前 2 页）、新剧速递（韩剧 new 前 2 页）、
     * 热播综艺（综艺 hot）、追剧不能停（未完结）。每块最多 {@value #HOME_BLOCK_MAX} 条，
     * 备注会加上板块名；跨块按 id 去重。
     *
     * @return 列表 JSON
     */
    @Override
    public String homeVideoContent() {
        try {
            List<VodItem> collected = new ArrayList<>();
            HashSet<String> seen = new HashSet<>();
            try {
                addBlock(collected, seen, "精选", castItems(index()[0]));
            } catch (Exception e) {
                android.util.Log.w(TAG, "首页板块 精选 失败", e);
            }
            try {
                List<VodItem> hot = new ArrayList<>(castItems(catePage(1, "1", ALL, "hot", ALL)[0]));
                hot.addAll(castItems(catePage(2, "1", ALL, "hot", ALL)[0]));
                addBlock(collected, seen, "热门推荐", hot);
            } catch (Exception e) {
                android.util.Log.w(TAG, "首页板块 热门推荐 失败", e);
            }
            try {
                List<VodItem> fresh = new ArrayList<>(castItems(catePage(1, "1", ALL, "new", ALL)[0]));
                fresh.addAll(castItems(catePage(2, "1", ALL, "new", ALL)[0]));
                addBlock(collected, seen, "新剧速递", fresh);
            } catch (Exception e) {
                android.util.Log.w(TAG, "首页板块 新剧速递 失败", e);
            }
            try {
                addBlock(collected, seen, "热播综艺", castItems(catePage(1, "2", ALL, "hot", ALL)[0]));
            } catch (Exception e) {
                android.util.Log.w(TAG, "首页板块 热播综艺 失败", e);
            }
            try {
                addBlock(collected, seen, "追剧不能停", castItems(updating(1, UPDATING_PER_CATE)[0]));
            } catch (Exception e) {
                android.util.Log.w(TAG, "首页板块 追剧不能停 失败", e);
            }
            List<VodItem> slice = collected.size() > HOME_MAX ? collected.subList(0, HOME_MAX) : collected;
            return VodBuilder.create().addAll(toVods(slice)).searchContent();
        } catch (Exception e) {
            android.util.Log.e(TAG, "homeVideoContent 失败", e);
            return "";
        }
    }

    /**
     * 原文 {@code homeVideoContent()} 里那个匿名 {@code Hxq$1Adder} 的等效实现。
     *
     * <p>给这一块的每条备注前面加 {@code "板块名 · "}，按 id 去重，最多加
     * {@value #HOME_BLOCK_MAX} 条。
     *
     * @param target 汇总列表
     * @param seen   已出现的 id
     * @param title  板块名
     * @param block  本块条目
     */
    public static void addBlock(List<VodItem> target, HashSet<String> seen, String title,
                                List<VodItem> block) {
        List<VodItem> prepared = new ArrayList<>();
        if (block != null) {
            for (VodItem item : block) {
                if (item == null) {
                    continue;
                }
                VodItem copy = new VodItem();
                copy.id = item.id;
                copy.name = item.name;
                copy.pic = item.pic;
                copy.time = item.time;
                String remarks = item.remarks == null ? "" : item.remarks.trim();
                copy.remarks = TextUtils.isEmpty(remarks) ? title : title + " · " + remarks;
                prepared.add(copy);
            }
        }
        int added = 0;
        for (VodItem item : prepared) {
            if (item == null || TextUtils.isEmpty(item.id)) {
                continue;
            }
            if (seen.contains(item.id)) {
                continue;
            }
            seen.add(item.id);
            target.add(item);
            added++;
            if (added >= HOME_BLOCK_MAX) {
                return;
            }
        }
    }

    // ── 爬虫入口：分类 ──────────────────────────────────────

    /**
     * 原文 {@code categoryContent(String,String,boolean,HashMap)}。
     *
     * <p>别名映射（首页板块名 → 真实分类）：
     * {@code new/新剧速递 → stype=1(sort=new)}、{@code variety/热播综艺 → 2(hot)}、
     * {@code c1/精选/热门推荐/jx → 1(hot)}、{@code c2 → 2}、{@code c3 → 3}、
     * {@code 追剧不能停 → updating}。{@code star}/{@code rank} 走各自分支。
     *
     * @param typeId 分类 id
     * @param pg     页码
     * @param filter 宿主是否要筛选（忽略）
     * @param extend 筛选值（{@code cid/cate/sort/year/rid/smode}）
     * @return 分类 JSON
     */
    @Override
    public String categoryContent(String typeId, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        try {
            String type = TextUtils.isEmpty(typeId) ? TYPE_TV : typeId.trim();
            HashMap<String, String> ext = extend == null ? new HashMap<String, String>() : extend;
            if ("new".equals(type)) {
                type = TYPE_TV;
                putIfAbsent(ext, "sort", "new");
            } else if ("variety".equals(type) || "热播综艺".equals(type)) {
                type = TYPE_VARIETY;
                putIfAbsent(ext, "sort", "hot");
            } else if ("c1".equals(type) || "精选".equals(type) || "热门推荐".equals(type)
                    || "jx".equals(type)) {
                type = TYPE_TV;
                putIfAbsent(ext, "sort", "hot");
            } else if ("c2".equals(type)) {
                type = TYPE_VARIETY;
            } else if ("c3".equals(type)) {
                type = TYPE_MOVIE;
            } else if ("新剧速递".equals(type)) {
                type = TYPE_TV;
                putIfAbsent(ext, "sort", "new");
            } else if ("追剧不能停".equals(type)) {
                type = "updating";
            }
            if ("updating".equals(type)) {
                Object[] reply = updating(page, 20);
                return paging(castItems(reply[0]), page, 20, ((Boolean) reply[1]).booleanValue(), null);
            }
            if ("star".equals(type)) {
                String mode = HxqCrypto.firstNonEmpty(mapGet(ext, "smode", "letter"), "hot").trim();
                Object[] reply;
                if (mode.startsWith("name:")) {
                    String ch = mode.substring(5);
                    if (TextUtils.isEmpty(ch)) {
                        ch = String.valueOf(SURNAMES.charAt(0));
                    }
                    reply = starSearch(page, ch);
                } else if (SURNAMES.contains(mode)) {
                    reply = starSearch(page, mode);
                } else if ("rank".equals(mode) || "人气".equals(mode) || "人气明星".equals(mode)) {
                    reply = starList("rank", page);
                } else {
                    reply = starList("hot", page);
                }
                return paging(castItems(reply[0]), page, STAR_PAGE_SIZE,
                        ((Boolean) reply[1]).booleanValue(), null);
            }
            if ("rank".equals(type)) {
                String rid = HxqCrypto.firstNonEmpty(mapGet(ext, "rid"), ALL).trim();
                List<VodItem> items = isAll(rid) ? allRanks() : rankList(rid);
                Object[] reply = window24(items, page);
                return paging(castItems(reply[0]), page, LIST_PAGE_SIZE,
                        ((Boolean) reply[1]).booleanValue(), Integer.valueOf(items.size()));
            }
            if (!TYPE_TV.equals(type) && !TYPE_VARIETY.equals(type) && !TYPE_MOVIE.equals(type)) {
                List<VodItem> boards = cateList(TYPE_TV, "new", ALL);
                Object[] reply = window24(boards, page);
                return paging(castItems(reply[0]), page, LIST_PAGE_SIZE,
                        ((Boolean) reply[1]).booleanValue(), Integer.valueOf(boards.size()));
            }
            String cid = mapGet(ext, "cid", "cate");
            if (TextUtils.isEmpty(cid)) {
                cid = ALL;
            }
            cid = cid.trim();
            String sort = HxqCrypto.firstNonEmpty(mapGet(ext, "sort"), "new").trim();
            String year = HxqCrypto.firstNonEmpty(mapGet(ext, "year"), ALL).trim();
            List<VodItem> items;
            if (isAll(cid)) {
                items = cateList(type, sort, year);
            } else {
                items = cateAll(type, cid, sort, year);
                if ("new".equals(sort)) {
                    items = sortByTimeDesc(items);
                }
            }
            Object[] reply = window24(items, page);
            return paging(castItems(reply[0]), page, LIST_PAGE_SIZE,
                    ((Boolean) reply[1]).booleanValue(), Integer.valueOf(items.size()));
        } catch (Exception e) {
            android.util.Log.e(TAG, "categoryContent 失败", e);
            return "";
        }
    }

    // ── 爬虫入口：详情 ──────────────────────────────────────

    /**
     * 原文 {@code detailContent(List<String>)}。
     *
     * <p>明星 id（以 {@code star|} 开头）走明星详情；其余走剧集详情：
     * 详情 → 分集桶 → 每桶分集 → 按 {@link #episodeGroup} 拼线路与剧集，
     * 然后从 {@code crew} 文本里拆出导演/主演/地区/简介。
     *
     * @param ids id 列表
     * @return 详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String sid = "";
        if (ids != null && !ids.isEmpty()) {
            sid = String.valueOf(ids.get(0));
        }
        if (TextUtils.isEmpty(sid)) {
            return empty();
        }
        if (sid.startsWith(STAR_ID_FLAG)) {
            return starDetail(sid);
        }
        JSONObject series = seriesDetail(sid);
        if (series.length() == 0) {
            return empty();
        }
        try {
            String name = HxqCrypto.firstNonEmpty(series.optString("name"), sid);
            String pic = pickImage(series);
            String remarks = detailRemarks(series);
            String intro = HxqCrypto.htmlToText(HxqCrypto.firstNonEmpty(
                    series.optString("intro"), series.optString("shorthand")));
            String crew = series.optString("crew");
            String year = formatYear(series.opt("publishTime"));

            JSONObject[] holder = new JSONObject[1];
            List<Map<String, String>> buckets = buckets(sid, holder);
            JSONObject normal = holder[0];

            List<String> sourceIds = new ArrayList<>();
            List<List<Episode>> groups = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            List<String> counts = new ArrayList<>();
            for (Map<String, String> bucket : buckets) {
                String bucketId = bucket.get("sid");
                String bucketTitle = bucket.get("title");
                List<Episode> eps = episodes(bucketId, sid.equals(bucketId) ? normal : null);
                for (Episode ep : eps) {
                    if (!ep.locked) {
                        sourceIds.add(ep.pid);
                    }
                }
                labels.add(bucketTitle);
                groups.add(eps);
            }
            if (!sourceIds.isEmpty()) {
                List<String> merged = new ArrayList<>(sourceIds);
                for (String c : carriers) {
                    if (!merged.contains(c)) {
                        merged.add(c);
                    }
                }
                this.carriers = merged;
            }
            List<String> froms = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) {
                List<Episode> eps = groups.get(i);
                if (eps == null || eps.isEmpty()) {
                    continue;
                }
                String block = episodeGroup(eps, splitParts);
                if (!TextUtils.isEmpty(block)) {
                    froms.add(labels.get(i));
                    urls.add(block);
                    counts.add(labels.get(i) + eps.size() + "集");
                }
            }
            if (froms.isEmpty()) {
                froms.add("正片");
                int count = series.optInt("count", 0);
                if (count > 0) {
                    List<String> fake = new ArrayList<>();
                    for (int i = 1; i <= count; i++) {
                        fake.add("第" + i + "集$" + FAKE_EPISODE + i);
                    }
                    urls.add(TextUtils.join("#", fake));
                } else {
                    urls.add(name.replaceAll("[$#]+", " ").trim() + "$");
                }
            }
            if (counts.size() > 1) {
                remarks = TextUtils.join(" · ",
                        Arrays.asList(remarks, TextUtils.join(" / ", counts)));
            }
            Vod vod = new Vod()
                    .id(sid)
                    .name(name)
                    .pic(pic)
                    .remarks(remarks)
                    .year(year);
            applyDetailText(crew, vod);
            if (TextUtils.isEmpty(vod.vodContent) && !TextUtils.isEmpty(intro)) {
                vod.content(intro);
            }
            vod.playFrom(TextUtils.join("$$$", froms));
            vod.playUrl(TextUtils.join("$$$", urls));
            return detailResponse(vod);
        } catch (Exception e) {
            android.util.Log.e(TAG, "detailContent 失败: " + sid, e);
            return empty();
        }
    }

    /**
     * 原文 {@code k(String)}：明星详情。
     *
     * <p>id 形如 {@code star|sid|name}：先查该姓名的明星列表拿到头像与粉丝数，
     * 再把该明星名下 {@value #DETAIL_MAX_SOURCES} 部以内剧集的<b>第一集</b>拼成播放列表。
     * 没搜到就用 {@code "暂无直链，请搜索剧名$"} 占位。
     *
     * @param starId 明星 id
     * @return 详情 JSON
     */
    public String starDetail(String starId) {
        try {
            String[] parts = starId.split("\\|", 3);
            String sid = parts.length > 1 ? parts[1] : "";
            String keyword = parts.length > 2 ? parts[2] : "";
            if (TextUtils.isEmpty(keyword) && !TextUtils.isEmpty(sid)) {
                keyword = sid;
            }
            JSONObject resp = starSearchJson(1, keyword, "star");
            JSONArray starList = resp.optJSONArray("starList");
            String thumb = "";
            String fansCount = "";
            if (starList != null) {
                for (int i = 0; i < starList.length(); i++) {
                    JSONObject o = starList.optJSONObject(i);
                    if (o != null && (String.valueOf(o.opt("sid")).equals(sid)
                            || keyword.equals(o.optString("name")))) {
                        thumb = o.optString("thumb");
                        fansCount = o.optString("fansCount");
                        keyword = HxqCrypto.firstNonEmpty(o.optString("name"), keyword);
                        break;
                    }
                }
                if (TextUtils.isEmpty(thumb) && starList.length() > 0) {
                    thumb = starList.optJSONObject(0).optString("thumb");
                }
            }
            JSONArray seriesList = resp.optJSONArray("seriesList");
            List<String> episodes = new ArrayList<>();
            List<String> works = new ArrayList<>();
            if (seriesList != null) {
                for (int i = 0; i < seriesList.length() && works.size() < DETAIL_MAX_SOURCES; i++) {
                    JSONObject o = seriesList.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    String seriesId = o.optString("sid");
                    String seriesName = o.optString("name").trim();
                    if (TextUtils.isEmpty(seriesId) || TextUtils.isEmpty(seriesName)) {
                        continue;
                    }
                    List<Episode> eps = episodes(seriesId, null);
                    if (eps.isEmpty()) {
                        continue;
                    }
                    String clean = seriesName.replaceAll("[$#]+", " ").trim();
                    if (!TextUtils.isEmpty(clean)) {
                        seriesName = clean;
                    }
                    works.add(seriesName);
                    for (Episode ep : eps) {
                        String label = "[" + seriesName + "]第" + ep.no + "集";
                        if (!TextUtils.isEmpty(ep.title)) {
                            String title = ep.title.replaceAll("[$#]+", " ").trim();
                            if (!TextUtils.isEmpty(title)) {
                                label = label + " " + title;
                            }
                        }
                        episodes.add(label + "$" + ep.pid);
                    }
                }
            }
            String remarks;
            if (TextUtils.isEmpty(fansCount)) {
                remarks = "明星";
            } else {
                remarks = "明星";
                try {
                    remarks = "粉丝 " + Integer.parseInt(fansCount);
                } catch (Exception ignored) {
                    // 保持"明星"
                }
            }
            if (!works.isEmpty()) {
                remarks = remarks + " · " + works.size() + "部可播";
            }
            String content;
            if (episodes.isEmpty()) {
                List<String> titles = new ArrayList<>();
                if (seriesList != null) {
                    for (int i = 0; i < Math.min(15, seriesList.length()); i++) {
                        JSONObject o = seriesList.optJSONObject(i);
                        if (o != null && !TextUtils.isEmpty(o.optString("name"))) {
                            titles.add(o.optString("name"));
                        }
                    }
                }
                episodes.add("暂无直链，请搜索剧名$");
                content = "韩小圈明星 · " + keyword + "\n" + TextUtils.join(" / ", titles);
            } else {
                content = "韩小圈明星 · " + keyword + "\n作品：" + TextUtils.join("、", works);
            }
            Vod vod = new Vod()
                    .id(starId)
                    .name(TextUtils.isEmpty(keyword) ? starId : keyword)
                    .pic(thumb)
                    .remarks(remarks)
                    .actor(keyword)
                    .content(content)
                    .playFrom(STAR_PLAY_FROM)
                    .playUrl(TextUtils.join("#", episodes));
            return detailResponse(vod);
        } catch (Exception e) {
            android.util.Log.e(TAG, "starDetail 失败", e);
            return empty();
        }
    }

    // ── 爬虫入口：搜索 ──────────────────────────────────────

    /**
     * 原文 {@code searchContent(String,boolean)}。
     *
     * @param key   关键词
     * @param quick 宿主快速搜索（忽略）
     * @return 搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    /**
     * 原文 {@code searchContent(String,boolean,String)}。
     *
     * <p>第 1 页优先放明星（"搜人"意图），后续页优先放剧集；再按 id 去重。
     * {@code more} 来自响应，或剧集列表凑满 15 条时也为真。
     *
     * @param key     关键词
     * @param quick   宿主快速搜索（忽略）
     * @param pageStr 页码
     * @return 搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick, String pageStr) {
        int page = pageNum(pageStr, 1);
        try {
            String keyword = key == null ? "" : key.trim();
            if (TextUtils.isEmpty(keyword)) {
                return paging(new ArrayList<VodItem>(), page, 20, false, null);
            }
            JSONObject resp = searchJson(page, keyword, "search_input");
            JSONArray seriesList = resp.optJSONArray("seriesList");
            if ((seriesList == null || seriesList.length() == 0)
                    && resp.optJSONObject("seriesData") != null) {
                seriesList = resp.optJSONObject("seriesData").optJSONArray("seriesList");
            }
            List<VodItem> series = toVodItems(seriesList);
            List<VodItem> stars = toStarItems(resp.optJSONArray("starList"));
            List<VodItem> merged = new ArrayList<>();
            if (page > 1 || stars.isEmpty()) {
                merged.addAll(series);
                merged.addAll(stars);
            } else {
                merged.addAll(stars);
                merged.addAll(series);
            }
            List<VodItem> out = new ArrayList<>();
            HashSet<String> seen = new HashSet<>();
            for (VodItem item : merged) {
                if (item != null && !TextUtils.isEmpty(item.id) && !seen.contains(item.id)) {
                    seen.add(item.id);
                    out.add(item);
                }
            }
            boolean more = resp.optBoolean("more")
                    || (seriesList != null && seriesList.length() >= STAR_MORE_MIN);
            return paging(out, page, 20, more, null);
        } catch (Exception e) {
            android.util.Log.e(TAG, "searchContent 失败", e);
            return "";
        }
    }

    // ── 数据流实现 ──────────────────────────────────────────

    /**
     * 原文 {@code t()}：首页"精选"。注意上游把页码写死成 1，所以 offset 恒为 0。
     *
     * @return {@code [本页列表, 是否还有更多]}
     */
    public Object[] index() {
        JSONObject resp = api("/api/series/index?offset=" + ((Math.max(1, 1) - 1) * INDEX_PAGE_SIZE),
                false, false);
        JSONArray arr = resp.optJSONArray("seriesList");
        return new Object[]{toVodItems(arr),
                Boolean.valueOf(resp.optBoolean("more") || (arr != null && arr.length() >= INDEX_PAGE_SIZE))};
    }

    /**
     * 原文 {@code p(int,String,String,String,String)}：分类接口一页。
     *
     * <p>查询串拼法很讲究：<b>只有</b>年份不是"全部"时才发 {@code year}，
     * 且此时类型走 {@code cate=}；否则类型走 {@code cid=}。
     * 默认排序是 {@code hot}（注意 {@code o()} 那边默认是 {@code new}，上游两处不一致）。
     *
     * @param page  页码
     * @param stype 分类
     * @param cate  类型筛选
     * @param sort  排序
     * @param year  年份
     * @return {@code [本页列表, 是否还有更多]}
     */
    public Object[] catePage(int page, String stype, String cate, String sort, String year) {
        int p = Math.max(1, page);
        String type = TextUtils.isEmpty(stype) ? TYPE_TV : stype;
        String cateValue = TextUtils.isEmpty(cate) ? ALL : cate.trim();
        String sortValue = TextUtils.isEmpty(sort) ? "hot" : sort.trim();
        String yearValue = TextUtils.isEmpty(year) ? ALL : year.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("stype=").append(HxqCrypto.urlEncode(type))
                .append("&sort=").append(HxqCrypto.urlEncode(sortValue))
                .append("&page=").append(p);
        boolean hasYear = !isAll(yearValue);
        boolean hasCate = !isAll(cateValue);
        if (hasYear) {
            sb.append("&year=").append(HxqCrypto.urlEncode(yearValue));
            if (hasCate) {
                sb.append("&cate=").append(HxqCrypto.urlEncode(cateValue));
            }
        } else if (hasCate) {
            sb.append("&cid=").append(HxqCrypto.urlEncode(cateValue));
        }
        JSONArray arr = api("/api/series/cate?" + sb, false, false).optJSONArray("seriesList");
        List<VodItem> items = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                VodItem item = toVodItem(arr.optJSONObject(i));
                if (item != null) {
                    items.add(item);
                }
            }
        }
        return new Object[]{items,
                Boolean.valueOf(arr != null && arr.length() >= CATE_MORE_MIN)};
    }

    /**
     * 原文 {@code o(String,String,String,String)}：指定类型翻页到底（带缓存）。
     *
     * <p>停止条件：页码超过 {@value #CATE_MAX_PAGE}、出现一次空页/无新条目、或响应说没有更多。
     *
     * @param stype 分类
     * @param cate  类型
     * @param sort  排序
     * @param year  年份
     * @return 列表
     */
    public List<VodItem> cateAll(String stype, String cate, String sort, String year) {
        String type = TextUtils.isEmpty(stype) ? TYPE_TV : stype;
        String cateValue = TextUtils.isEmpty(cate) ? ALL : cate.trim();
        String sortValue = TextUtils.isEmpty(sort) ? "new" : sort.trim();
        String yearValue = TextUtils.isEmpty(year) ? ALL : year.trim();
        String key = "_cate_all|" + type + "|" + cateValue + "|" + sortValue + "|" + yearValue;
        List<VodItem> cached = cateAllCache.get(key);
        if (cached != null) {
            return new ArrayList<>(cached);
        }
        List<VodItem> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (int page = 1; page <= CATE_MAX_PAGE; page++) {
            Object[] reply = catePage(page, type, cateValue, sortValue, yearValue);
            List<VodItem> items = castItems(reply[0]);
            int added = 0;
            for (VodItem item : items) {
                if (item != null && !TextUtils.isEmpty(item.id) && !seen.contains(item.id)) {
                    seen.add(item.id);
                    out.add(item);
                    added++;
                }
            }
            boolean more = ((Boolean) reply[1]).booleanValue();
            if (items.isEmpty() || added == 0 || !more) {
                break;
            }
        }
        List<VodItem> result = "new".equals(sortValue) ? sortByTimeDesc(out) : out;
        cateAllCache.put(key, new ArrayList<>(result));
        return result;
    }

    /**
     * 原文 {@code B(String,String,String)}：分类首页的"拼版"。
     *
     * <p>把每个类型各取一页拼起来去重；全都取不到时退回"全部"那一页。
     *
     * @param stype 分类
     * @param sort  排序
     * @param year  年份
     * @return 列表
     */
    public List<VodItem> cateList(String stype, String sort, String year) {
        String type = TextUtils.isEmpty(stype) ? TYPE_TV : stype;
        String sortValue = TextUtils.isEmpty(sort) ? "new" : sort.trim();
        String yearValue = TextUtils.isEmpty(year) ? ALL : year.trim();
        String key = "_stype_boards|" + type + "|" + sortValue + "|" + yearValue;
        List<VodItem> cached = cateCache.get(key);
        if (cached != null) {
            return new ArrayList<>(cached);
        }
        String[][] filters = CATEGORY_FILTERS.get(type);
        if (filters == null) {
            filters = new String[][]{{"全部", ALL}};
        }
        List<String> cates = new ArrayList<>();
        for (String[] f : filters) {
            if (!isAll(f[1])) {
                cates.add(f[1]);
            }
        }
        if (cates.isEmpty()) {
            cates.add(ALL);
        }
        List<List<VodItem>> pages = new ArrayList<>();
        for (String cate : cates) {
            try {
                pages.add(castItems(catePage(1, type, cate, sortValue, yearValue)[0]));
            } catch (Exception ignored) {
                // 单个类型失败不影响其它
            }
        }
        List<VodItem> merged = mergePages(pages);
        if (merged.isEmpty()) {
            try {
                merged = castItems(catePage(1, type, ALL, sortValue, yearValue)[0]);
                if ("new".equals(sortValue)) {
                    merged = sortByTimeDesc(merged);
                }
            } catch (Exception e) {
                merged = new ArrayList<>();
            }
        }
        cateCache.put(key, new ArrayList<>(merged));
        return merged;
    }

    /**
     * 原文 {@code u(String)}：某榜单翻页到底（带缓存）。
     *
     * @param rid 榜单 id
     * @return 列表
     */
    public List<VodItem> rankList(String rid) {
        String key = "_rank_all|" + rid;
        List<VodItem> cached = rankCache.get(key);
        if (cached != null) {
            return new ArrayList<>(cached);
        }
        List<VodItem> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (int page = 1; page <= RANK_MAX_PAGE; page++) {
            JSONObject resp = api("/api/series/rank?rid=" + HxqCrypto.urlEncode(rid) + "&page=" + page,
                    false, false);
            JSONArray arr = resp.optJSONArray("seriesList");
            List<VodItem> items = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    VodItem item = toVodItem(arr.optJSONObject(i));
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
            boolean more = resp.optBoolean("more") || (arr != null && arr.length() >= RANK_MORE_MIN);
            int added = 0;
            for (VodItem item : items) {
                if (item != null && !TextUtils.isEmpty(item.id) && !seen.contains(item.id)) {
                    seen.add(item.id);
                    out.add(item);
                    added++;
                }
            }
            if (items.isEmpty() || added == 0 || !more) {
                break;
            }
        }
        rankCache.put(key, new ArrayList<>(out));
        return out;
    }

    /**
     * 原文 {@code v()}：全部榜单合并（带缓存）。
     *
     * @return 列表
     */
    public List<VodItem> allRanks() {
        if (rankAll != null && !rankAll.isEmpty()) {
            return new ArrayList<>(rankAll);
        }
        List<List<VodItem>> pages = new ArrayList<>();
        for (int[] pair : rankIds()) {
            try {
                pages.add(rankList(String.valueOf(pair[0])));
            } catch (Exception ignored) {
                // 单个榜单失败不影响其它
            }
        }
        List<VodItem> merged = mergePages(pages);
        this.rankAll = new ArrayList<>(merged);
        return merged;
    }

    /**
     * 原文 {@code A(int,String)}：明星搜索。
     *
     * @param page 页码
     * @param name 姓名（空则用姓氏表第一个字）
     * @return {@code [列表, 是否还有更多]}
     */
    public Object[] starSearch(int page, String name) {
        String keyword = TextUtils.isEmpty(name) ? String.valueOf(SURNAMES.charAt(0)) : name;
        JSONObject resp = starSearchJson(page, keyword, "star");
        JSONArray arr = resp.optJSONArray("starList");
        List<VodItem> items = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                VodItem item = toStarItem(arr.optJSONObject(i));
                if (item != null) {
                    items.add(item);
                }
            }
        }
        return new Object[]{items,
                Boolean.valueOf(resp.optBoolean("more") || (arr != null && arr.length() >= STAR_MORE_MIN))};
    }

    /**
     * 原文 {@code z(int,String)}：明星榜（{@code hot}/{@code rank}），首次构建需要
     * {@value #STAR_PROBE} 次探测请求，之后走内存缓存。
     *
     * @param kind {@code rank} 或其它（hot）
     * @param page 页码
     * @return {@code [本页列表, 是否还有更多]}
     */
    public Object[] starList(String kind, int page) {
        boolean byRank = "rank".equals(kind);
        List<VodItem> all = byRank ? starRank : starHot;
        if (all == null) {
            LinkedHashMap<String, VodItem> merged = new LinkedHashMap<>();
            int probe = Math.min(STAR_PROBE, SURNAMES.length());
            for (int i = 0; i < probe; i++) {
                try {
                    JSONArray arr = starSearchJson(1, String.valueOf(SURNAMES.charAt(i)), "star")
                            .optJSONArray("starList");
                    if (arr == null) {
                        continue;
                    }
                    for (int k = 0; k < arr.length(); k++) {
                        VodItem item = toStarItem(arr.optJSONObject(k));
                        if (item == null) {
                            continue;
                        }
                        VodItem old = merged.get(item.id);
                        if (old == null || item.fans > old.fans) {
                            merged.put(item.id, item);
                        }
                    }
                } catch (Exception ignored) {
                    // 单个姓氏失败跳过
                }
            }
            all = new ArrayList<>(merged.values());
            if (byRank) {
                Collections.sort(all, new Comparator<VodItem>() {
                    @Override
                    public int compare(VodItem a, VodItem b) {
                        return a.rank != b.rank ? Integer.compare(a.rank, b.rank)
                                : Integer.compare(b.fans, a.fans);
                    }
                });
                starRank = all;
            } else {
                Collections.sort(all, new Comparator<VodItem>() {
                    @Override
                    public int compare(VodItem a, VodItem b) {
                        return a.fans != b.fans ? Integer.compare(b.fans, a.fans)
                                : Integer.compare(a.rank, b.rank);
                    }
                });
                starHot = all;
            }
        }
        int from = (Math.max(1, page) - 1) * STAR_PAGE_SIZE;
        List<VodItem> slice = from >= all.size() ? new ArrayList<VodItem>()
                : new ArrayList<>(all.subList(from, Math.min(all.size(), from + STAR_PAGE_SIZE)));
        return new Object[]{slice, Boolean.valueOf(from + STAR_PAGE_SIZE < all.size())};
    }

    /**
     * 原文 {@code C(int,int)}：只在"未完结"里挑，跨三种分类各翻 3 页，按更新时间/发布时间降序。
     *
     * @param page 页码
     * @param size 每页条数
     * @return {@code [本页列表, 是否还有更多]}
     */
    public Object[] updating(int page, int size) {
        int p = Math.max(1, page);
        int per = Math.max(1, size);
        LinkedHashMap<String, JSONObject> merged = new LinkedHashMap<>();
        for (String stype : new String[]{TYPE_TV, TYPE_VARIETY, TYPE_MOVIE}) {
            for (int pageNo = 1; pageNo <= 3; pageNo++) {
                JSONObject resp = api("/api/series/cate?stype=" + stype + "&sort=new&page=" + pageNo,
                        false, false);
                JSONArray arr = resp.optJSONArray("seriesList");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) {
                            continue;
                        }
                        if (o.has("isFinished") && !o.optBoolean("isFinished")) {
                            String sid = o.optString("sid");
                            if (!TextUtils.isEmpty(sid) && !merged.containsKey(sid)) {
                                merged.put(sid, o);
                            }
                        }
                    }
                }
                if (!resp.optBoolean("more")) {
                    break;
                }
            }
        }
        List<JSONObject> raw = new ArrayList<>(merged.values());
        Collections.sort(raw, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Long.compare(timeOf(b), timeOf(a));
            }
        });
        int from = (p - 1) * per;
        int to = from + per;
        List<VodItem> slice = new ArrayList<>();
        for (int i = from; i < Math.min(raw.size(), to); i++) {
            VodItem item = toVodItem(raw.get(i));
            if (item != null) {
                slice.add(item);
            }
        }
        return new Object[]{slice, Boolean.valueOf(to < raw.size())};
    }

    /**
     * 原文 {@code w()} 里那段"取更新时间，取不到用发布时间"。
     *
     * @param o 接口条目
     * @return 时间戳；都没有 0
     */
    public static long timeOf(JSONObject o) {
        long t = 0L;
        try {
            t = Long.parseLong(String.valueOf(o.opt("updateTime")));
        } catch (Exception ignored) {
            t = 0L;
        }
        if (t == 0L) {
            try {
                t = Long.parseLong(String.valueOf(o.opt("publishTime")));
            } catch (Exception ignored) {
                // 保持 0
            }
        }
        return t;
    }

    /**
     * 原文 {@code q(String)}：剧集详情。
     *
     * @param sid 剧集 id
     * @return {@code series} 对象；失败空对象
     */
    public JSONObject seriesDetail(String sid) {
        JSONObject o = api("/api/series/detail?sid=" + HxqCrypto.urlEncode(sid), false, false)
                .optJSONObject("series");
        return o == null ? new JSONObject() : o;
    }

    /**
     * 原文 {@code y(String)}：分集桶（带设备头、解密）。
     *
     * @param sid 剧集 id
     * @return JSON；失败空对象
     */
    public JSONObject normalDetail(String sid) {
        try {
            return api("/api/series2/detail/normal?sid=" + HxqCrypto.urlEncode(sid)
                    + "&refer=" + HxqCrypto.urlEncode(refer), true, true);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * 原文 {@code n(String,JSONObject[])}：解析分集桶。
     *
     * @param sid    剧集 id
     * @param holder 长度 ≥1 的数组时回填原始响应
     * @return {@code [{sid,title}…]}；没有 buckets 时退回 {@code [{sid, "正片"}]}
     */
    public List<Map<String, String>> buckets(String sid, JSONObject[] holder) {
        JSONObject resp = normalDetail(sid);
        if (holder != null && holder.length > 0) {
            holder[0] = resp;
        }
        List<Map<String, String>> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        JSONArray arr = resp.optJSONArray("buckets");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String bucketId = o.optString("sid").trim();
                if (TextUtils.isEmpty(bucketId) || seen.contains(bucketId)) {
                    continue;
                }
                seen.add(bucketId);
                String title = o.optString("title", "正片").trim();
                if (TextUtils.isEmpty(title)) {
                    title = "正片";
                }
                String clean = title.replaceAll("[$#]+", " ").trim();
                if (TextUtils.isEmpty(clean)) {
                    clean = "正片";
                }
                Map<String, String> map = new LinkedHashMap<>();
                map.put("sid", bucketId);
                map.put("title", clean);
                out.add(map);
            }
        }
        if (out.isEmpty()) {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("sid", sid);
            map.put("title", "正片");
            out.add(map);
        }
        return out;
    }

    /**
     * 原文 {@code x(int,String,String)}：搜索接口（带设备头、解密）。
     *
     * @param page   页码
     * @param key    关键词
     * @param srefer 来源（空则 {@code search_input}）
     * @return JSON
     */
    public JSONObject searchJson(int page, String key, String srefer) {
        String refer2 = TextUtils.isEmpty(srefer) ? "search_input" : srefer;
        return api("/api/search/s5?k=" + HxqCrypto.urlEncode(key == null ? "" : key.trim())
                + "&srefer=" + HxqCrypto.urlEncode(refer2) + "&type=0&page=" + page, true, true);
    }

    /**
     * 明星搜索接口（原文 {@code x(i, str, "star")} 的语义包装）。
     *
     * @param page    页码
     * @param keyword 姓名
     * @param srefer  来源
     * @return JSON
     */
    public JSONObject starSearchJson(int page, String keyword, String srefer) {
        return searchJson(page, keyword, srefer);
    }

    // ── 剧集分组 / 详情文本（原文 g / f1） ──────────────────

    /**
     * 原文 {@code g(List,boolean)}：把一个桶里的分集拼成 TVBox 的剧集串。
     *
     * <p>{@code split=false}（或探测不到多源）时每条分集一个地址；
     * 否则把"同一 pid 的多个源"展开成 {@code 第N集 i/total}，并加
     * {@link #SPLIT_MARK}{@code i} 后缀 —— 该后缀由 {@code playerContent} 消费。
     * "探测"用的是前 2 条分集的真实地址数（{@code min(2, size)}）。
     *
     * @param list  分集
     * @param split 是否拆分多源
     * @return {@code #} 连接的剧集串
     */
    public String episodeGroup(List<Episode> list, boolean split) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        int size = 1;
        if (split) {
            int probe = Math.min(2, list.size());
            for (int i = 0; i < probe; i++) {
                Episode ep = list.get(i);
                if (ep != null && !TextUtils.isEmpty(ep.pid) && !ep.pid.startsWith(FAKE_EPISODE)) {
                    List<String> urls = cachedSources(ep.pid, true);
                    if (urls.size() > 1) {
                        size = urls.size();
                        break;
                    }
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (Episode ep : list) {
            int no = ep.no <= 0 ? 1 : ep.no;
            String title = (ep.title == null ? "" : ep.title).replaceAll("[$#]+", " ").trim();
            String pid = ep.pid == null ? "" : ep.pid;
            if (size <= 1 || TextUtils.isEmpty(pid) || pid.startsWith(FAKE_EPISODE)) {
                String label = "第" + no + "集";
                if (!TextUtils.isEmpty(title)) {
                    label = "第" + no + "集 " + title;
                }
                out.add(label + "$" + pid);
            } else {
                for (int i = 0; i < size; i++) {
                    String label;
                    if (i != 0 || TextUtils.isEmpty(title)) {
                        label = "第" + no + "集 " + (i + 1) + "/" + size;
                    } else {
                        label = "第" + no + "集 "
                                + (title.length() > 12 ? title.substring(0, 12) : title)
                                + " " + (i + 1) + "/" + size;
                    }
                    out.add(label.replaceAll("[$#]+", " ").trim() + "$" + pid + SPLIT_MARK + i);
                }
            }
        }
        return TextUtils.join("#", out);
    }

    /**
     * 原文 {@code f1(String,Vod)}：从 {@code crew} 文本里拆导演/主演/地区/简介。
     *
     * <p>被显式丢弃的行：编剧 / 类型 / 语言 / 集数 / 又名（上游只保留其余行当简介）。
     *
     * @param crew 文本
     * @param vod  目标
     */
    public static void applyDetailText(String crew, Vod vod) {
        if (vod == null) {
            return;
        }
        if (crew == null || crew.isEmpty()) {
            vod.director("");
            vod.actor("");
            vod.area("");
            vod.content("");
            return;
        }
        String director = "";
        String actor = "";
        String area = "";
        StringBuilder content = new StringBuilder();
        for (String line : crew.split("\\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("导演:") || t.startsWith("导演：")) {
                director = t.substring(3).trim();
            } else if (t.startsWith("主演:") || t.startsWith("主演：")) {
                actor = t.substring(3).trim();
            } else if (t.startsWith("制片国家/地区:") || t.startsWith("制片国家/地区：")) {
                area = t.substring(8).trim();
            } else if (t.startsWith("编剧:") || t.startsWith("编剧：")
                    || t.startsWith("类型:") || t.startsWith("类型：")
                    || t.startsWith("语言:") || t.startsWith("语言：")
                    || t.startsWith("集数:") || t.startsWith("集数：")
                    || t.startsWith("又名:") || t.startsWith("又名：")) {
                // 上游显式丢弃这几类
            } else {
                content.append(t).append("\n");
            }
        }
        vod.director(director);
        vod.actor(actor);
        vod.area(area);
        vod.content(content.toString().trim());
    }

    /**
     * 原文 {@code detailContent} 里年份的三种算法。
     *
     * <p>{@code > 10000000000}（毫秒）→ {@code yyyy-MM-dd}；否则取前 4 位（当成"年"）；
     * 解析失败取前 10 位（可能是 {@code yyyy-MM-dd} 文本）。
     *
     * @param publishTime 原值
     * @return 年份串
     */
    public static String formatYear(Object publishTime) {
        if (publishTime == null || "".equals(String.valueOf(publishTime))) {
            return "";
        }
        String text = String.valueOf(publishTime);
        try {
            long v = Long.parseLong(text);
            if (v > 10000000000L) {
                // ★ 显式声明 Locale 与 TimeZone。上游用的是设备默认时区（这是给人看的上映日期），
                //   这里保持"设备本地日期"的语义，但把默认值写出来，避免后来者误以为它在用 UTC
                //   —— 本仓有过 `new SimpleDateFormat(...)` 不指定 Locale 导致佛历年份的前例。
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                fmt.setTimeZone(java.util.TimeZone.getDefault());
                return fmt.format(new Date(v));
            }
            return text.substring(0, Math.min(4, text.length()));
        } catch (Exception e) {
            return text.substring(0, Math.min(10, text.length()));
        }
    }

    // ── 小工具 ─────────────────────────────────────────────

    /**
     * 原文 {@code m(HashMap,String...)}：按顺序取第一个非空值。
     *
     * @param map  表
     * @param keys 候选键
     * @return 值或 ""
     */
    public static String mapGet(Map<String, String> map, String... keys) {
        if (map != null) {
            for (String k : keys) {
                String v = map.get(k);
                if (!TextUtils.isEmpty(v)) {
                    return v;
                }
            }
        }
        return "";
    }

    /** {@code ext} 里没有才写入（原文 {@code !map.containsKey(...)}）。 */
    private static void putIfAbsent(HashMap<String, String> map, String key, String value) {
        if (!map.containsKey(key)) {
            map.put(key, value);
        }
    }

    /** 原文字面量 {@code "".equals(x) || "-1".equals(x) || "None".equals(x) || "全部".equals(x)}。 */
    public static boolean isAll(String v) {
        return v == null || "".equals(v) || ALL.equals(v) || "None".equals(v) || "全部".equals(v);
    }

    /**
     * 把 {@code Object[]} 里的列表收敛成 {@code List<VodItem>}。
     *
     * @param o 任意对象
     * @return 新列表
     */
    @SuppressWarnings("unchecked")
    public static List<VodItem> castItems(Object o) {
        List<VodItem> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object item : (List<Object>) o) {
                if (item instanceof VodItem) {
                    out.add((VodItem) item);
                }
            }
        }
        return out;
    }

    /** 分类 id → 名称表。 */
    private static Map<Integer, String> categoryNames() {
        Map<Integer, String> map = new LinkedHashMap<>();
        map.put(Integer.valueOf(1), "韩剧");
        map.put(Integer.valueOf(2), "综艺");
        map.put(Integer.valueOf(3), "电影");
        return map;
    }

    /** 分类筛选表（原文 {@code V} 的静态初始化）。 */
    private static Map<String, String[][]> categoryFilters() {
        Map<String, String[][]> map = new LinkedHashMap<>();
        map.put("1", new String[][]{
                {"全部", "-1"}, {"青春校园", "5"}, {"都市爱情", "6"}, {"偶像恋爱", "7"},
                {"短篇网剧", "8"}, {"宫廷古装", "9"}, {"科幻异能", "10"}, {"优质动漫", "83"},
                {"奇幻穿越", "11"}, {"律政检法", "12"}, {"警匪动作", "13"}, {"恐怖惊悚", "82"},
                {"悬疑刑侦", "14"}, {"情景喜剧", "15"}, {"家庭情感", "16"}, {"职场医疗", "17"},
                {"虐恋情深", "18"}, {"战争历史", "80"}, {"特辑花絮", "19"}, {"灾难", "95"},
                {"剧情", "96"}, {"成长治愈", "98"}, {"轻喜剧", "99"}, {"复仇爽剧", "101"},
                {"奇幻异能", "64"}, {"奇幻冒险", "66"},
        });
        map.put("2", new String[][]{
                {"全部", "-1"}, {"娱乐搞笑", "20"}, {"真人秀", "21"}, {"烧脑推理", "24"},
                {"脱口秀", "29"}, {"旅游冒险", "22"}, {"地方美食", "23"}, {"Idol团综", "25"},
                {"明星选秀", "26"}, {"美妆频道", "27"}, {"音乐放送", "28"}, {"好物推荐", "84"},
                {"颁奖大赏", "30"},
        });
        map.put("3", new String[][]{
                {"全部", "-1"}, {"剧情", "31"}, {"爱情", "32"}, {"伦理", "33"}, {"动作", "34"},
                {"犯罪", "35"}, {"动画", "77"}, {"恐怖", "41"}, {"灾难", "36"}, {"喜剧", "37"},
                {"奇幻", "44"}, {"悬疑", "40"}, {"文艺", "39"}, {"科幻", "43"}, {"历史", "38"},
                {"战争", "42"}, {"亲情", "78"}, {"日本", "79"}, {"励志", "97"}, {"谍战", "102"},
        });
        return map;
    }
}
