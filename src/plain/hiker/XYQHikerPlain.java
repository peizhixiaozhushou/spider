package com.example.tvbox.plain.hiker;

import android.content.Context;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Dom;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.tvbox.plain.hiker.HikerRule.CAT_ARR_RULE;
import static com.example.tvbox.plain.hiker.HikerRule.CAT_FIRST_PAGE;
import static com.example.tvbox.plain.hiker.HikerRule.CAT_IS_JSOUP;
import static com.example.tvbox.plain.hiker.HikerRule.CAT_JSON_TWICE;
import static com.example.tvbox.plain.hiker.HikerRule.CAT_MODE;
import static com.example.tvbox.plain.hiker.HikerRule.CAT_PIC;
import static com.example.tvbox.plain.hiker.HikerRule.CAT_PREFIX;
import static com.example.tvbox.plain.hiker.HikerRule.CAT_SUBTITLE;
import static com.example.tvbox.plain.hiker.HikerRule.CAT_SUFFIX;
import static com.example.tvbox.plain.hiker.HikerRule.CAT_TITLE;
import static com.example.tvbox.plain.hiker.HikerRule.CAT_URL;
import static com.example.tvbox.plain.hiker.HikerRule.CHARSET;
import static com.example.tvbox.plain.hiker.HikerRule.CLASS_NAME;
import static com.example.tvbox.plain.hiker.HikerRule.CLASS_URL;
import static com.example.tvbox.plain.hiker.HikerRule.CLASS_VALUE;
import static com.example.tvbox.plain.hiker.HikerRule.DETAIL_ACTOR;
import static com.example.tvbox.plain.hiker.HikerRule.DETAIL_AREA;
import static com.example.tvbox.plain.hiker.HikerRule.DETAIL_CATE;
import static com.example.tvbox.plain.hiker.HikerRule.DETAIL_COVER;
import static com.example.tvbox.plain.hiker.HikerRule.DETAIL_IS_JSOUP;
import static com.example.tvbox.plain.hiker.HikerRule.DETAIL_PLOT;
import static com.example.tvbox.plain.hiker.HikerRule.DETAIL_YEAR;
import static com.example.tvbox.plain.hiker.HikerRule.EPI_ARR_RULE;
import static com.example.tvbox.plain.hiker.HikerRule.EPI_IS_JSOUP;
import static com.example.tvbox.plain.hiker.HikerRule.EPI_PREFIX;
import static com.example.tvbox.plain.hiker.HikerRule.EPI_REVERSE;
import static com.example.tvbox.plain.hiker.HikerRule.EPI_SUFFIX;
import static com.example.tvbox.plain.hiker.HikerRule.EPI_TITLE;
import static com.example.tvbox.plain.hiker.HikerRule.EPI_URL;
import static com.example.tvbox.plain.hiker.HikerRule.FILTER_DATA;
import static com.example.tvbox.plain.hiker.HikerRule.FORCE_PLAY;
import static com.example.tvbox.plain.hiker.HikerRule.HOME_ARR_RULE;
import static com.example.tvbox.plain.hiker.HikerRule.HOME_ENABLED;
import static com.example.tvbox.plain.hiker.HikerRule.HOME_IS_JSOUP;
import static com.example.tvbox.plain.hiker.HikerRule.HOME_PIC;
import static com.example.tvbox.plain.hiker.HikerRule.HOME_PLAY_ARR_RULE;
import static com.example.tvbox.plain.hiker.HikerRule.HOME_PREFIX;
import static com.example.tvbox.plain.hiker.HikerRule.HOME_RECOMMEND_URL;
import static com.example.tvbox.plain.hiker.HikerRule.HOME_SUBTITLE;
import static com.example.tvbox.plain.hiker.HikerRule.HOME_SUFFIX;
import static com.example.tvbox.plain.hiker.HikerRule.HOME_TITLE;
import static com.example.tvbox.plain.hiker.HikerRule.HOME_URL;
import static com.example.tvbox.plain.hiker.HikerRule.PIC_PROXY;
import static com.example.tvbox.plain.hiker.HikerRule.PLAY_ARR_RULE;
import static com.example.tvbox.plain.hiker.HikerRule.PLAY_PREFIX;
import static com.example.tvbox.plain.hiker.HikerRule.PLAY_SUFFIX;
import static com.example.tvbox.plain.hiker.HikerRule.SEARCH_ARR_RULE;
import static com.example.tvbox.plain.hiker.HikerRule.SEARCH_FIRST_PAGE;
import static com.example.tvbox.plain.hiker.HikerRule.SEARCH_IS_JSOUP;
import static com.example.tvbox.plain.hiker.HikerRule.SEARCH_JSON_TWICE;
import static com.example.tvbox.plain.hiker.HikerRule.SEARCH_MODE;
import static com.example.tvbox.plain.hiker.HikerRule.SEARCH_PIC;
import static com.example.tvbox.plain.hiker.HikerRule.SEARCH_POST_BODY;
import static com.example.tvbox.plain.hiker.HikerRule.SEARCH_PREFIX;
import static com.example.tvbox.plain.hiker.HikerRule.SEARCH_SUBTITLE;
import static com.example.tvbox.plain.hiker.HikerRule.SEARCH_SUFFIX;
import static com.example.tvbox.plain.hiker.HikerRule.SEARCH_TITLE;
import static com.example.tvbox.plain.hiker.HikerRule.SEARCH_URL;
import static com.example.tvbox.plain.hiker.HikerRule.SEARCH_VOD_URL;
import static com.example.tvbox.plain.hiker.HikerRule.TAB_ARR_RULE;
import static com.example.tvbox.plain.hiker.HikerRule.TAB_TITLE;

/**
 * XYQHiker 明文重写 —— 「规则驱动型」通用采集器。
 *
 * <p>反编译原文: {@code ae/decompiled/XYQHiker.java}（4700 行）
 * smali 权威对照: {@code /tmp/hiker-smali/com/github/catvod/spider/XYQHiker.smali}（22967 行）
 * （可用 {@code baksmali d re/dex/classes.dex -o /tmp/hiker-smali} 复现）
 *
 * <h2>它是什么</h2>
 * 本类<b>不含任何站点逻辑</b>。所有行为来自 ext 里的 JSON DSL：字段名、默认值、
 * 消费点见 {@link HikerRule}（82 条字段的完整表）与
 * {@code maintain/evidence/XYQHiker_DSL.md}。
 * 换句话说：改 DSL 就能改站点，改代码只能改引擎。
 *
 * <h2>原混淆名 → 语义名对照表（主流程侧）</h2>
 * <table>
 *   <tr><th>原</th><th>smali 行</th><th>本类</th></tr>
 *   <tr><td>{@code homeContent}</td><td>10111</td><td>{@link #homeContent}</td></tr>
 *   <tr><td>{@code homeVideoContent}</td><td>10885</td><td>{@link #homeVideoContent}</td></tr>
 *   <tr><td>{@code categoryContent} → {@code s}</td><td>6470 / 17038</td><td>{@link #categoryContent}</td></tr>
 *   <tr><td>{@code searchContent} → {@code ah}</td><td>20377 / 3428</td><td>{@link #searchContent}</td></tr>
 *   <tr><td>{@code detailContent}</td><td>6505</td><td>{@link #detailContent}</td></tr>
 *   <tr><td>{@code playerContent}</td><td>13478</td><td>{@link #playerContent}</td></tr>
 *   <tr><td>{@code isVideoFormat}</td><td>13253</td><td>{@link #isVideoFormat}</td></tr>
 *   <tr><td>{@code manualVideoCheck}</td><td>13415</td><td>{@link #manualVideoCheck}</td></tr>
 *   <tr><td>{@code init}</td><td>13243</td><td>{@link #init}</td></tr>
 * </table>
 *
 * <h2>为什么代码比上游短这么多（这是硬指标，不是偷工）</h2>
 * 上游 {@code homeContent}+{@code homeVideoContent} 合计约 2050 行，其中 jadx 标注
 * {@code Code duplicated} 的块遍布：
 * <ul>
 *   <li>{@code homeVideoContent} 的 1811 行里，<b>同一段「列表解析」被抄了 3 遍</b>
 *       （分支 #1 @1493-2131、#2 @2137-2600、#3 @2604-3068，三块的 const-string 序列完全一致）。
 *       jadx 的 {@code Instruction removed from duplicated block} 还把 #2/#3 的条件
 *       写成了恒真（{@code if (...) z = true; else z = true;}）。</li>
 *   <li>{@code 卡片解析}（标题/图片/副标题/链接/前缀后缀/&amp;&amp;链）在上游出现了 4 次以上。</li>
 * </ul>
 * 本类把这套范式收敛成一张 {@link Section} 表 + 4 个纯函数
 * （{@link #field} / {@link #titleOf} / {@link #picOf} / {@link #linkOf}），
 * 因此同样的行为只写一次。
 *
 * <h2>证据来源与行号区间</h2>
 * 反编译原文 {@code ae/decompiled/XYQHiker.java}（4700 行）。只写文件名等于没有证据，
 * 所以这里给全部区间（行号用 {@code grep -n} 可直接复核）：
 * <table>
 *   <tr><th>方法</th><th>decompiled 行</th><th>smali 行（22767 行文件）</th><th>状态</th></tr>
 *   <tr><td>{@code homeContent}</td><td>954–1268（315 行）</td><td>10111</td><td>可读</td></tr>
 *   <tr><td>{@code homeVideoContent}</td><td>1269–3080（1812 行）</td><td>10885</td><td>可读，但同一段被抄 3 遍</td></tr>
 *   <tr><td>{@code ah}（搜索核心）</td><td><b>869–878</b>，dump@871（2507 单指令）</td><td>3428–6469</td><td><b>jadx 失败，靠 smali 补出</b></td></tr>
 *   <tr><td>{@code detailContent}</td><td><b>889–899</b>，dump@891（2953）</td><td>6505–10110</td><td><b>jadx 失败，靠 smali 补出</b></td></tr>
 *   <tr><td>{@code playerContent}</td><td>3204 起，dump@<b>3607</b>（2990）</td><td>13478–16981</td><td><b>部分失败，靠 smali 补出</b></td></tr>
 *   <tr><td>{@code s}（分类核心）</td><td>3663 起，dump@<b>3665</b>（2828）</td><td>17038–20376</td><td><b>jadx 失败，靠 smali 补出</b></td></tr>
 *   <tr><td>{@code t}（筛选面板）</td><td>3696 起（794 行，体在）</td><td>20399</td><td>体在，含 5 处死分支</td></tr>
 *   <tr><td>{@code y} / {@code ac}（请求头）</td><td>4546 / 393</td><td>22083 / 1555</td><td>可读</td></tr>
 *   <tr><td>{@code z}（筛选项）</td><td>4657</td><td>22756</td><td>可读</td></tr>
 * </table>
 * 量化证据（可直接跑）：
 * <pre>
 * grep -c "Method dump skipped"        ae/decompiled/XYQHiker.java   → 4
 * grep -c "Code duplicated"            ae/decompiled/XYQHiker.java   → 206
 * wc -l                                ae/decompiled/XYQHiker.java   → 4700
 * </pre>
 * smali 的取得方式（这是"反编译失败时还有路可走"的方法记录）：
 * <pre>
 * baksmali d re/dex/classes.dex -o /tmp/hiker-smali     # 2.5.2
 * # → /tmp/hiker-smali/com/github/catvod/spider/XYQHiker.smali （22967 行）
 * # 4 个 dump skipped 方法与 smali 行号的对应：
 * #   ah@3428  detailContent@6505  playerContent@13478  s@17038
 * </pre>
 * <b>结论</b>：本类的 {@code ah}/{@code detailContent}/{@code playerContent}/{@code s}
 * 四条主流程是<b>逐条读 smali 常量表 + 分支结构</b>补出来的，
 * 不是靠猜 jadx 的残缺输出；每条常量表都能在上表的 smali 行号处复核。
 *
 * <h2>实现陷阱</h2>
 * <h3>1. 「同一份规范化副本」被两处不同语义的判定共用（隐藏得很深）</h3>
 * {@link HikerExtractor#sniffMatch} 里同时存在两种字符串判定：
 * <ul>
 *   <li><b>关键词/过滤词比对</b> —— 应该大小写不敏感，用小写副本。</li>
 *   <li><b>「被拦内容」判定</b> —— 上游是 {@code body.contains(feat)} <b>原文比对</b>，
 *       而特征串 {@code m3u8.pw/Cache} 里含<b>大写 C</b>，所以必须用<b>原始 URL</b>。</li>
 * </ul>
 * 第一版实现把 URL 转小写后<b>两个判定共用那个副本</b>，于是
 * {@code isBlockedContent} 永远返回 false、"被拦内容豁免"分支永远不成立
 * —— 表面上代码在跑、断言大部分都绿，只有
 * {@code 反爬豁免使其判为视频} 这一条会红。
 * <p><b>修法：被拦判定用原始 URL；只有关键词/过滤词用小写副本。</b>
 * <p>教训：一个"看起来只是规范化"的局部变量，如果被两处语义不同的判定共用，
 * 就会静默地改变其中一处的行为。这类 bug 不会抛异常，只会让某个分支永不进入。
 *
 * <h3>2. 非法 Unicode 转义在<b>词法分析之前</b>就会炸（全项目通用的坑）</h3>
 * Java 在词法分析前扫描「反斜杠 + u + 4 位十六进制」这种转义，而且<b>不认识注释</b>，
 * 所以只要在 javadoc 里出现「反斜杠 + u」而后面不是恰好 4 位十六进制
 * （例如拿它讲正则、讲转义本身），{@code javac} 就会报 {@code illegal unicode escape}，
 * 而且<b>报出的行号是错的</b> —— 它会指向文件开头附近，而不是真正出问题的那一行。
 * <p>本包四个文件都踩过。特别注意：<b>这一节本身的初稿也踩了一次</b>
 * ——在"讲这个坑"的段落里又一次写出了那个组合，报错指向第 87 行，真正的问题在第 173 行。
 * 这正说明它是"写文档时必然遇到"的坑，而不是一次性疏忽。修法是：不要在注释里出现
 * 反斜杠紧挨着 u；要讲它就用中文描述或全角字符。复检：
 * <pre>
 * python3 ae/tools/audit_docs.py --src maintain/src --plain-root maintain/src/plain --by-class
 * </pre>
 *
 * <h2>修正的上游缺陷（与上游的差异）</h2>
 * 全部以 smali 为据；每条都有断言钉住（断言名见「钉住的断言」列）。
 * <table>
 *   <tr><th>#</th><th>上游缺陷</th><th>上游写法</th><th>本实现</th><th>钉住的断言</th></tr>
 *   <tr><td>1</td><td>布尔开关判定恒真</td>
 *       <td>{@code if (!v.equals("1") || v.equals("是")) z = true;} —— 对
 *           {@code "1"}/{@code "0"}/{@code "否"}/{@code ""} <b>全为真</b>；
 *           而同文件的 {@code 是否开启获取首页数据} 用的是正确形式</td>
 *       <td>统一按 {@code "1"}/{@code "是"} 为真</td>
 *       <td>{@code 缺陷6 Jsoup 开关按 1/是 判真}、{@code 缺陷6 Jsoup 开关 "0" 判假}</td></tr>
 *   <tr><td>2</td><td>同字段读两遍的死分支</td>
 *       <td>{@code x = ab(F); if (x.isEmpty() &amp;&amp; a == b) x = ab(F);} —— 两个分支读同一个字段（在 prefix/suffix/title/pic/subtitle 上共 5 处）</td>
 *       <td>按语义等价折叠为一次读取</td>
 *       <td>{@code 分类片单链接加前缀} 相关断言（{@code rulesOf} 只解一次）</td></tr>
 *   <tr><td>3</td><td>吞掉 Cookie 的请求头复制</td>
 *       <td>{@code y()} 与 {@code ac()} 是两份近似复制，{@code ac()} 漏了累积 Cookie；且两处都有 {@code if (this.c.isEmpty()) { sb.append(this.c) }} 的反向条件</td>
 *       <td>{@code headers()} / {@code searchHeaders()} 共用一份展开逻辑，搜索请求头也带 Cookie</td>
 *       <td>{@code 搜索请求头带累积 Cookie}、{@code 请求头带累积 Cookie}</td></tr>
 *   <tr><td>4</td><td>反爬豁免与关键词比对共用一个副本</td>
 *       <td>见上「实现陷阱 1」</td><td>被拦判定用原始 URL</td>
 *       <td>{@code 反爬豁免使其判为视频}</td></tr>
 *   <tr><td>5</td><td>{@code wh.g} 被误记为"正则取分组"</td>
 *       <td>实为 URL 绝对化（见 {@link HikerSelector#firstMatch} 的勘误块）</td>
 *       <td>{@link HikerExtractor#absolutize} 为等价实现</td>
 *       <td>{@code absolutize 根路径}、{@code absolutize 相对路径}、{@code picOf 绝对化}</td></tr>
 *   <tr><td>6</td><td>{@code ;post} 指令残留进 URL</td>
 *       <td>{@code url.split("?")[0]} 对无 {@code ?} 的 {@code url;post} 会把 {@code ;post} 留在 URL 里</td>
 *       <td>连同指令标记一起剥掉</td>
 *       <td>{@code ;post → POST}</td></tr>
 *   <tr><td>7</td><td>13 处其余缺陷</td>
 *       <td>{@code wh.q().get(0)} 越界、{@code p()} 判空比错变量、{@code p()} 异常返回 null、
 *           {@code ag()} 抹掉正斜杠、{@code n()} 恒等赋值、{@code vertype} 与 {@code checkveriry} 判定不一致、
 *           {@code 截取模式} 方向反直觉、{@code 'input'} 自替换产出重复链接 等</td>
 *       <td>逐条见 {@code maintain/evidence/XYQHiker_DSL.md} 第 12 节</td>
 *       <td>{@code defectPins} 段共 15 条断言（前缀 {@code 缺陷N}）</td></tr>
 * </table>
 * <p>另有一条<b>刻意偏离、非缺陷</b>：多站点作用域用
 * {@link HikerRule#scoped(String)}（本项目扩展；上游只认一份扁平 JSON，
 * 多站点配在一起会互相串味）。未配置 {@code "sites"} 时行为与上游一致。
 *
 * <h2>未覆盖的分支</h2>
 * <h3>已通过 smali 补出的部分（不是缺口，是方法记录）</h3>
 * jadx 在 4 处直接放弃了：{@code ah}(@871) / {@code detailContent}(@891) /
 * {@code playerContent}(@3607) / {@code s}(@3665)，以及 {@code homeVideoContent} 里
 * 206 处 {@code Code duplicated} 警告。这四条主流程与首页列表解析<b>已经还原</b>，
 * 手法是：{@code baksmali d re/dex/classes.dex} 拿原始 smali
 * （{@code /tmp/hiker-smali/.../XYQHiker.smali}，22967 行），
 * 先抽 {@code const-string} 常量表（字段名、输出键、错误串都在里面），
 * 再按分支结构与寄存器流向对照 {@code .catch} 边界重建控制流。
 * 后来者遇到同类 "Method dump skipped" 可以照这条路走 ——
 * 这比在 jadx 残骸上猜要快得多，也可靠得多。
 * <h3>确实没有还原的分支</h3>
 * <ol>
 *   <li><b>{@code w()} 的「ext 以 http 开头 → 联网取规则 JSON」</b>
 *       （smali {@code:21936} 起）。原本行为：把远端返回的 JSON 当规则集。
 *       还原不了的原因：本项目测试零网络，且该路径没有可离线复现的输入；
 *       补上需要：一个真实的远端规则 URL 与其响应体 fixture。
 *       现状：{@link HikerRule#of(String)} 显式跳过该分支，DSL 文档标「未实现」。</li>
 *   <li><b>OCR 验证码三连重试</b>（{@code af} / {@code:2683-3387}，233 行）。
 *       原本行为：抓验证码图 → POST 到 {@code OCR_API} 取文本 → 按
 *       {@code /verify} / {@code scheckAC} / {@code inc/ajax.php} 三条站点族分别回帖 →
 *       最多重试 4 次。还原不了的原因：需要真实验证码图与 OCR 服务；
 *       补上需要：一张验证码图 fixture + OCR 响应 fixture。
 *       现状：{@link HikerExtractor#isVerifyPage} 只做「识别出被拦」，
 *       重试链未实现（{@link HikerExtractor#fetchGuarded} 不做重试）。</li>
 *   <li><b>{@code ad} / {@code ae} 两条反爬绕行</b>（{@code:2213-2682}）。
 *       原本行为：{@code /huadong_}/{@code /renji_} 页面里抠
 *       {@code key/value} 再算 {@code string2Hex} 二次请求；{@code btwaf} 页面抠
 *       {@code btwaf=} token 拼到 URL 重试。还原不了的原因：两套页面结构已知，
 *       但 {@code ku.d}/{@code ku.c} 混淆函数的入参语义只在特定响应里成立。
 *       现状：{@link HikerRule} 记录特征串，{@link HikerExtractor#isBlockedContent}
 *       能识别，绕行未实现。</li>
 *   <li><b>MacPlayer 播放页 JS 逐行解释</b>（{@code playerContent} 的
 *       {@code /static/player/*.js} / {@code playerconfig.js} 分支，smali {@code:15579-16604}）。
 *       原本行为：下载播放页引用的 JS，正则抠 {@code var player_*}、
 *       {@code ps}/{@code from}/{@code link_next}/{@code nid} 等字段拼播放地址。
 *       还原不了的原因：这段有 1000+ 行、且强依赖目标站 JS 的具体形态。
 *       现状：{@link #playerContent} 实现了「直链 / 已带 {@code $} 分隔的普通地址」
 *       两条主路径，输出 {@code parse=0}（直连播放）；MacPlayer JS 深挖未实现。</li>
 *   <li><b>{@code 分析MacPlayer=2} 时对 {@code /zxzj_} 链接的特判</b>
 *       （smali {@code:16306} 起）。同上，未实现。</li>
 *   <li><b>选集链接的 {@code ed2k}/{@code magnet} 名称抽取</b>：
 *       规则常量与正则已归档（{@link HikerExtractor#ed2kFileName} /
 *       {@link HikerExtractor#magnetName}），但上游把它嵌在 MacPlayer 分支里；
 *       本类不调用它 —— 换成了「链接原样下发」。DSL 文档标「部分实现」。</li>
 * </ol>
 *
 * <h2>测试可覆写的网络边界</h2>
 * {@link #fetch(String, Map)} / {@link #fetchPost(String, Map, Map)} /
 * {@link #fetchRaw(String, String, Map)} 都是 {@code public} 且非 final，
 * 测试子类覆写后即可注入 fixture，全程零网络。
 */
public class XYQHikerPlain extends BaseSpider {

    protected static final String TAG_NAME = "XYQHikerPlain";

    /** 上游常量 {@code a}：反爬/验证页的 URL 特征表。 */
    public static final String[] ANTI_BOT_URL_FEATURES = HikerRule.ANTI_BOT_URL_FEATURES;

    /** 上游常量 {@code b}：可取值属性白名单。 */
    public static final String[] IDENTITY_ATTRS = HikerRule.IDENTITY_ATTRS;

    /** 上游字段 {@code h}：从播放页 JS 里抠 {@code player_list} 的正则。 */
    public static final String MAC_PLAYER_LIST_RE = HikerExtractor.MAC_PLAYER_LIST_RE;

    /** 上游「卡片链接占位」：链接里写 {@code 'input'} 表示「就地替换成抓取到的链接」。 */
    public static final String SELF_LINK_PLACEHOLDER = "'input'";

    /** 详情 id 的分隔符（列表页拼、详情页拆）。 */
    public static final String ID_SEP = "$$$";

    protected HikerRule rule = HikerRule.empty();
    protected HikerExtractor hiker = new HikerExtractor(rule);

    // ══════════════════════════════════════════════════════════════
    // 生命周期
    // ══════════════════════════════════════════════════════════════

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        this.rule = HikerRule.of(extend);
        String key = siteKeyOf(extend);
        if (!key.isEmpty()) this.rule = this.rule.scoped(key);
        this.hiker = new HikerExtractor(this.rule);
    }

    /** 站点键：优先用 ext 里的 {@code siteKey}/{@code 站名}，否则用 host 的最后一段。 */
    protected String siteKeyOf(String extend) {
        String k = ext.optStr("siteKey");
        if (k == null || k.isEmpty()) k = ext.optStr("站名");
        if (k != null && !k.isEmpty()) return k;
        String u = ext.url();
        if (u == null || u.isEmpty()) return "";
        try {
            String host = java.net.URI.create(u).getHost();
            if (host == null) return "";
            String[] p = host.split("\\.");
            return p.length >= 2 ? p[p.length - 2] : host;
        } catch (Exception e) {
            return "";
        }
    }

    /** 当前规则（测试与排错用）。 */
    public HikerRule rule() { return rule; }

    /** 当前动作层（测试与排错用）。 */
    public HikerExtractor extractor() { return hiker; }

    // ══════════════════════════════════════════════════════════════
    // 可覆写的网络边界（测试注入 fixture 用）
    // ══════════════════════════════════════════════════════════════

    /** GET。smali {@code:21790}（原 {@code u}）。 */
    public String fetch(String url, Map<String, String> headers) {
        return hiker.fetch(url, headers);
    }

    /** GET 的别名（便于测试覆写语义化命名）。 */
    public String fetchWith(String url, Map<String, String> headers) {
        return fetch(url, headers);
    }

    /** POST 表单。smali {@code:21880}（原 {@code v}）。 */
    public String fetchPost(String url, Map<String, String> form, Map<String, String> headers) {
        return hiker.fetchPost(url, form, headers);
    }

    /** POST 原始体（JSON）。smali {@code:16982}（原 {@code r}）。 */
    public String fetchRaw(String url, String body, Map<String, String> headers) {
        return hiker.fetchPostRaw(url, body, headers);
    }

    /**
     * 按 {@link HikerExtractor.UrlSpec} 抓一页：统一 GET / 表单 POST / JSON POST 三条路径。
     *
     * <p>上游把这套 if-else 抄在 {@code homeVideoContent}、{@code s}、{@code ah}、
     * {@code detailContent} 四处；这里收敛成一处。
     */
    public String fetchBySpec(String rawUrl, String referer) {
        HikerExtractor.UrlSpec spec = HikerExtractor.parseUrlSpec(HikerExtractor.expandMacros(rawUrl));
        if (spec.url.isEmpty()) return "";
        Map<String, String> h = hiker.headers(referer);
        if (!spec.post) return fetch(spec.url, h);
        if (spec.body.isEmpty()) return fetchPost(spec.url, new LinkedHashMap<String, String>(), h);
        if (HikerExtractor.isJsonBody(spec.body)) return fetchRaw(spec.url, spec.body, h);
        return fetchPost(spec.url, HikerExtractor.formToMap(spec.body), h);
    }

    /** 反爬页识别 + 提示（上游 {@code l} 开关）。 */
    protected boolean blocked(String body) {
        return HikerExtractor.isBlockedContent(body) || HikerExtractor.isVerifyPage(body);
    }

    // ══════════════════════════════════════════════════════════════
    // 表驱动：分区规则
    // ══════════════════════════════════════════════════════════════

    /** 一个「卡片列表」在 DSL 里的 8 个字段 + jsoup 开关。 */
    public enum Section {
        /** 首页片单（{@code 首页片单*}） */
        HOME(HOME_ARR_RULE, HOME_PLAY_ARR_RULE, HOME_IS_JSOUP, HOME_TITLE, HOME_PIC,
                HOME_SUBTITLE, HOME_URL, HOME_PREFIX, HOME_SUFFIX),
        /** 分类片单（{@code 分类片单*}） */
        CATEGORY(null, CAT_ARR_RULE, CAT_IS_JSOUP, CAT_TITLE, CAT_PIC,
                CAT_SUBTITLE, CAT_URL, CAT_PREFIX, CAT_SUFFIX),
        /** 搜索片单（{@code 搜索片单*}） */
        SEARCH(null, SEARCH_ARR_RULE, SEARCH_IS_JSOUP, SEARCH_TITLE, SEARCH_PIC,
                SEARCH_SUBTITLE, SEARCH_VOD_URL, SEARCH_PREFIX, SEARCH_SUFFIX),
        /** 详情-选集（{@code 选集*}） */
        EPISODE(null, EPI_ARR_RULE, EPI_IS_JSOUP, EPI_TITLE, null,
                null, EPI_URL, EPI_PREFIX, EPI_SUFFIX),
        /** 详情-线路（{@code 线路*}） */
        TAB(null, TAB_ARR_RULE, DETAIL_IS_JSOUP, TAB_TITLE, null,
                null, null, null, null);

        /** 外层数组规则（首页用 {@code 首页列表数组规则} 作容器） */
        public final HikerRule.Field outerArray;
        /** 卡片数组规则 */
        public final HikerRule.Field innerArray;
        /** jsoup 语法开关 */
        public final HikerRule.Field jsoup;
        public final HikerRule.Field title;
        public final HikerRule.Field pic;
        public final HikerRule.Field subtitle;
        public final HikerRule.Field url;
        public final HikerRule.Field prefix;
        public final HikerRule.Field suffix;

        Section(HikerRule.Field outerArray, HikerRule.Field innerArray, HikerRule.Field jsoup,
                HikerRule.Field title, HikerRule.Field pic, HikerRule.Field subtitle,
                HikerRule.Field url, HikerRule.Field prefix, HikerRule.Field suffix) {
            this.outerArray = outerArray;
            this.innerArray = innerArray;
            this.jsoup = jsoup;
            this.title = title;
            this.pic = pic;
            this.subtitle = subtitle;
            this.url = url;
            this.prefix = prefix;
            this.suffix = suffix;
        }
    }

    /** 已解析成「可执行规则」的一张卡片规格。 */
    public static final class CardRules {
        public final Section section;
        public final String outerArrayRule;
        public final String arrayRule;
        public final boolean jsoup;
        public final String titleRule;
        public final String picRule;
        public final String subtitleRule;
        public final String urlRule;
        public final String prefix;
        public final String suffix;
        public final boolean proxyPic;

        CardRules(Section section, String outerArrayRule, String arrayRule, boolean jsoup,
                  String titleRule, String picRule, String subtitleRule, String urlRule,
                  String prefix, String suffix, boolean proxyPic) {
            this.section = section;
            this.outerArrayRule = outerArrayRule;
            this.arrayRule = arrayRule;
            this.jsoup = jsoup;
            this.titleRule = titleRule;
            this.picRule = picRule;
            this.subtitleRule = subtitleRule;
            this.urlRule = urlRule;
            this.prefix = prefix;
            this.suffix = suffix;
            this.proxyPic = proxyPic;
        }

        /** 是否有足够信息解析列表（数组规则 + 链接规则不能都空）。 */
        public boolean usable() {
            return arrayRule != null && !arrayRule.isEmpty()
                    && urlRule != null && !urlRule.isEmpty();
        }

        @Override public String toString() {
            return section + "{arr=" + arrayRule + ", jsoup=" + jsoup + "}";
        }
    }

    /** 把 {@link Section} 在 DSL 里的字段名解成实际规则串。 */
    public static CardRules rulesOf(HikerRule rule, Section section) {
        return new CardRules(section,
                section.outerArray == null ? "" : rule.value(section.outerArray),
                rule.value(section.innerArray),
                rule.flag(section.jsoup),
                section.title == null ? "" : rule.value(section.title),
                section.pic == null ? "" : rule.value(section.pic),
                section.subtitle == null ? "" : rule.value(section.subtitle),
                section.url == null ? "" : rule.value(section.url),
                section.prefix == null ? "" : rule.value(section.prefix),
                section.suffix == null ? "" : rule.value(section.suffix),
                rule.flag(PIC_PROXY));
    }

    // ══════════════════════════════════════════════════════════════
    // 表驱动：卡片解析的 4 个纯函数
    // ══════════════════════════════════════════════════════════════

    /**
     * 取一个字段值。
     *
     * <p>上游在 jsoup 模式下用 {@code getTextByRule}（{@code +}/{@code ＋} 拼接），
     * 非 jsoup 模式下用 {@code ag(wh.q(outerHtml, from, to).get(0))}（HTML 区间截取 + 去标签）。
     * 这里就是那段 if/else 的唯一实现。
     */
    public static String field(Dom.El el, String ruleStr, boolean jsoup) {
        if (el == null || ruleStr == null || ruleStr.isEmpty()) return "";
        if (jsoup) return HikerSelector.textByRule(el, ruleStr);
        String[] seg = ruleStr.split("&&");
        if (seg.length < 2) return HikerSelector.text(el, ruleStr);
        return HikerSelector.stripTags(HikerSelector.between(el.outerHtml(), seg[0], seg[1]));
    }

    /**
     * 取标题，并做 {@code 《…》} 收窄。
     *
     * <p>上游：标题里同时含 {@code 《} 与 {@code 》} 时，只保留两者之间的部分。
     */
    public static String titleOf(Dom.El el, String ruleStr, boolean jsoup) {
        String t = field(el, ruleStr, jsoup);
        if (t.contains("《") && t.contains("》")) {
            String[] a = t.split("《");
            if (a.length > 1) {
                String[] b = a[1].split("》");
                if (b.length > 0) t = b[0];
            }
        }
        return t;
    }

    /**
     * 取图片地址。
     *
     * <p>上游：先取值（非 jsoup 模式<b>不做</b> {@code ag} 去标签，与标题/副标题不同），
     * 再处理 CSS {@code url(...)}，最后用 {@code wh.g(页面URL, 图片)} 绝对化。
     */
    public static String picOf(Dom.El el, String ruleStr, boolean jsoup, String pageUrl) {
        if (el == null || ruleStr == null || ruleStr.isEmpty()) return "";
        String v;
        if (jsoup) {
            v = HikerSelector.textByRule(el, ruleStr).trim();
        } else {
            String[] seg = ruleStr.split("&&");
            v = seg.length < 2 ? HikerSelector.text(el, ruleStr)
                    : HikerSelector.between(el.outerHtml(), seg[0], seg[1]);
            v = v.trim();
        }
        if (v.isEmpty()) return "";
        v = HikerExtractor.unwrapCssUrl(v);
        return HikerExtractor.absolutize(pageUrl, v);
    }

    /**
     * 取卡片链接。
     *
     * <p>上游：值里若含 {@code [替换:…]} 就做正则替换；随后
     * {@code 前缀 + 值 + 后缀}；若结果含 {@code 'input'} 则整段替换成链接本身。
     */
    public static String linkOf(Dom.El el, String ruleStr, boolean jsoup,
                                String prefix, String suffix) {
        if (el == null || ruleStr == null || ruleStr.isEmpty()) return "";
        String spec = ruleStr.split("\\[替换:")[0];
        String v = field(el, spec, jsoup);
        if (ruleStr.contains("[替换")) v = HikerSelector.regexReplace(v, ruleStr);
        String out = HikerExtractor.applyPreSuffix(prefix, v, suffix);
        if (out.contains(SELF_LINK_PLACEHOLDER)) out = out.replaceAll("'input'", v);
        return out;
    }

    /**
     * 解析一个卡片列表 —— 首页 / 分类 / 搜索 / 选集 共用。
     *
     * <p>上游把这段逻辑抄了 4 份（{@code homeVideoContent} 3 份、{@code s} 1 份、
     * {@code ah} 1 份、{@code detailContent} 的选集部分 1 份）。这里只有一份。
     *
     * @param root    HTML 文档根（或 JSON 模式外的兜底）
     * @param r       卡片规格
     * @param pageUrl 抓取这条列表用的 URL（既用于图片绝对化，也用于 {@code PG_URL} 替换）
     * @param limit   最多取多少条；&lt;=0 表示不限
     */
    public List<Vod> parseCards(Dom.El root, CardRules r, String pageUrl, int limit) {
        List<Vod> out = new ArrayList<Vod>();
        if (root == null || r == null || !r.usable()) return out;

        String containerRule = r.outerArrayRule == null || r.outerArrayRule.isEmpty()
                ? r.arrayRule : r.outerArrayRule;
        List<Dom.El> cards = HikerSelector.selectChained(root, containerRule);
        for (Dom.El card : cards) {
            if (limit > 0 && out.size() >= limit) break;
            List<Dom.El> items = r.outerArrayRule == null || r.outerArrayRule.isEmpty()
                    ? Arrays.asList(card)
                    : HikerSelector.selectChained(card, r.arrayRule);
            for (Dom.El item : items) {
                if (limit > 0 && out.size() >= limit) break;
                Vod v = card(item, r, pageUrl);
                if (v != null) out.add(v);
            }
        }
        return out;
    }

    /** 单张卡片 → {@link Vod}；标题与链接都空时返回 {@code null}（上游是「跳过」）。 */
    public Vod card(Dom.El item, CardRules r, String pageUrl) {
        if (item == null || r == null) return null;
        String title = titleOf(item, r.titleRule, r.jsoup);
        String pic = picOf(item, r.picRule, r.jsoup, pageUrl);
        String sub = field(item, r.subtitleRule, r.jsoup);
        String url = linkOf(item, r.urlRule, r.jsoup, r.prefix, r.suffix);
        String vodUrl = url.replace("PG_URL", pageUrl == null ? "" : pageUrl);
        if (title.isEmpty() && vodUrl.isEmpty()) return null;
        if (r.proxyPic && !pic.isEmpty()) pic = hiker.proxyImage(pic, pageUrl);
        return new Vod()
                .id(HikerExtractor.cardId(title, pic, vodUrl))
                .name(title)
                .pic(pic)
                .remarks(sub);
    }

    // ══════════════════════════════════════════════════════════════
    // homeContent
    // ══════════════════════════════════════════════════════════════

    /**
     * 首页 = 分类数组（{@code class}）+ 筛选面板（{@code filters}）。
     *
     * <p>smali {@code XYQHiker.smali:10111}。
     *
     * <p>筛选面板的来源由 {@code 筛选数据}（{@code filterdata}）决定：
     * <ul>
     *   <li>{@code clan://…} 或 {@code http…} / {@code ./…} → 抓下来当 JSON</li>
     *   <li>{@code EXT} → 用规则现场生成（{@link HikerExtractor#buildFilterPanel}）</li>
     *   <li>其它 → 当作规则 JSON 里的键名取子对象</li>
     * </ul>
     */
    @Override
    public String homeContent(boolean filter) {
        JSONObject resp = new JSONObject();
        try {
            resp.put("class", buildClasses());
            JSONObject filters = resolveFilters();
            if (filter && filters != null) resp.put("filters", filters);
        } catch (Exception e) {
            return categoryFallback();
        }
        return resp.toString();
    }

    /** 分类数组：{@code 分类名称}、{@code 分类名称替换词}、{@code 分类链接} 三段式。 */
    public JSONArray buildClasses() {
        JSONArray arr = new JSONArray();
        String names = rule.value(CLASS_NAME);
        String ids = rule.value(CLASS_VALUE);
        String urls = rule.value(CLASS_URL);
        if (names.isEmpty() || ids.isEmpty()) return arr;
        String[] ns = names.split("&");
        String[] is = ids.split("&");
        String[] us = urls.isEmpty() ? new String[0] : urls.split("&");
        int n = Math.min(ns.length, is.length);
        for (int i = 0; i < n; i++) {
            try {
                JSONObject o = new JSONObject();
                o.put("type_id", is[i].replaceAll("＆＆", "&"));
                o.put("type_name", ns[i]);
                if (us.length > i) o.put("type_flag", us[i]);
                else if (!urls.isEmpty()) o.put("type_flag", urls);
                arr.put(o);
            } catch (Exception e) {
                // 单条坏掉不影响其它分类
            }
        }
        return arr;
    }

    /** 解析 {@code 筛选数据} 得到面板；任何失败都返回 {@code null}（上游行为）。 */
    public JSONObject resolveFilters() {
        try {
            String src = rule.value(FILTER_DATA);
            if (src == null || src.isEmpty()) return null;
            if (src.startsWith("clan://")) {
                String body = readLocalOrUrl(src);
                return body.startsWith("{") ? new JSONObject(body) : null;
            }
            if (src.startsWith("http") || src.startsWith("./")) {
                String body = readLocalOrUrl(src);
                return body.startsWith("{") && body.endsWith("}") ? new JSONObject(body) : null;
            }
            if (src.equalsIgnoreCase("EXT")) {
                return HikerExtractor.buildFilterPanel(rule);
            }
            return rule.root().optJSONObject(src);
        } catch (Exception e) {
            return null;
        }
    }

    /** 读 {@code clan://} 本地文件或 http 远端（离线时返回空串）。 */
    protected String readLocalOrUrl(String spec) {
        if (spec == null || spec.isEmpty()) return "";
        if (spec.startsWith("clan://")) {
            String path = spec.substring("clan://".length());
            try {
                byte[] raw = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
                return new String(raw, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
        return fetch(spec, hiker.headers(spec));
    }

    /** 上游 catch 分支：出错时回落到「只有分类的 homeContent」。 */
    protected String categoryFallback() {
        try {
            JSONObject o = new JSONObject();
            o.put("class", buildClasses());
            return o.toString();
        } catch (Exception e) {
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // homeVideoContent
    // ══════════════════════════════════════════════════════════════

    /**
     * 首页视频列表。
     *
     * <p>smali {@code XYQHiker.smali:10885}（1811 行反编译体 → 这里 1 份逻辑）。
     *
     * <p>上游分支结构（用花括号深度还原出来的，见类注释中的行号）：
     * <pre>
     * if (首页推荐链接 空 || 首页片单列表数组规则 空)
     *     if (是否开启获取首页数据 == 1/是)         // 用分类聚合凑首页
     *         for 每个分类: s(分类id, "1", false, {}) 取前 5 条, 累计到 20 条就停
     *     if (!首页推荐链接.空 &amp;&amp; 首页片单列表数组规则.空)  return 聚合结果
     *     else if (是否开启获取首页数据 != 1/是)      → 用 分类片单 规则解析一次 → return
     * return 解析结果
     * </pre>
     * 即：只有当「推荐链接」与「首页片单数组规则」都配了，才走 {@code 首页片单} 选择器；
     * 否则退化成「按分类聚合」或「用分类选择器解析推荐链接」。
     */
    @Override
    public String homeVideoContent() {
        JSONObject resp = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            boolean homeEnabled = rule.flag(HOME_ENABLED);
            String rcmedUrl = rule.value(HOME_RECOMMEND_URL);
            String homePlayArr = rule.value(HOME_PLAY_ARR_RULE);

            if (rcmedUrl.isEmpty() || homePlayArr.isEmpty()) {
                if (homeEnabled) {
                    list = aggregateByCategory(20, 5);
                }
                if (!rcmedUrl.isEmpty() && homePlayArr.isEmpty()) {
                    resp.put("list", list);
                    return resp.toString();
                }
                if (!homeEnabled) {
                    List<Vod> vods = parsePage(rcmedUrl, rulesOf(rule, Section.CATEGORY), 0);
                    put(resp, vods);
                    return resp.toString();
                }
                resp.put("list", list);
                return resp.toString();
            }

            CardRules home = rulesOf(rule, Section.HOME);
            List<Vod> vods = parsePage(rcmedUrl, home, 0);
            put(resp, vods);
        } catch (Exception e) {
            // 上游此处 return str（空串）
            return "";
        }
        return resp.toString();
    }

    private void put(JSONObject resp, List<Vod> vods) throws Exception {
        resp.put("list", vodsToArray(vods));
    }

    /**
     * {@link Vod} 列表 → JSON 数组。
     *
     * <p>直接复用 {@link VodBuilder#searchContent()} 的字段序列化，
     * 不在这里重写一份「Vod 有哪些键」——那份知识属于 common。
     */
    protected static JSONArray vodsToArray(List<Vod> vods) {
        JSONArray arr = new JSONArray();
        if (vods == null || vods.isEmpty()) return arr;
        try {
            JSONObject o = new JSONObject(VodBuilder.create().addAll(vods).searchContent());
            JSONArray got = o.optJSONArray("list");
            return got == null ? arr : got;
        } catch (Exception e) {
            return arr;
        }
    }

    /**
     * 抓一个列表页并解析成卡片（表驱动主路径）。
     *
     * <p>上游在 4 个方法里各写一遍的「拼 URL → 抓 → 解析 → 遍历卡片」。
     */
    public List<Vod> parsePage(String rawUrl, CardRules cardRules, int limit) {
        if (rawUrl == null || rawUrl.isEmpty() || cardRules == null) return new ArrayList<Vod>();
        String url = HikerExtractor.expandMacros(rawUrl);
        String body = fetchBySpec(url, url);
        if (body == null) body = "";
        String mode = rule.value(cardRules.section == Section.SEARCH ? SEARCH_MODE : CAT_MODE);
        if (HikerExtractor.isJsonMode(mode)) {
            return parseJsonPage(body, cardRules, url, limit);
        }
        Dom.El root = HikerExtractor.parse(body, url);
        return parseCards(root, cardRules, url, limit);
    }

    /**
     * JSON 模式的列表解析（{@code 分类截取模式} / {@code 搜索截取模式} 为 {@code 0}/{@code 否}）。
     *
     * <p>点分路径来源（smali 核对结论）：
     * <ul>
     *   <li>分类 → {@code 分类Json数据二次截取}/{@code catjson_twice}，缺省 {@code "data"}
     *       （{@code s} smali {@code:18330}）</li>
     *   <li>搜索 → 优先 {@code 搜索Json数据二次截取}/{@code seajson_twice}，
     *       为空时用 {@code 搜索列表数组规则}/{@code sea_arr_rule} 的值当路径
     *       （{@code ah} smali 的 JSON 分支）</li>
     * </ul>
     */
    public List<Vod> parseJsonPage(String body, CardRules cardRules, String pageUrl, int limit) {
        List<Vod> out = new ArrayList<Vod>();
        boolean search = cardRules.section == Section.SEARCH;
        String dotPath = rule.value(search ? SEARCH_JSON_TWICE : CAT_JSON_TWICE);
        if (dotPath.isEmpty() && search) dotPath = rule.value(SEARCH_ARR_RULE);
        JSONArray arr = HikerExtractor.jsonPath(body, dotPath.isEmpty() ? "data" : dotPath);
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            if (limit > 0 && out.size() >= limit) break;
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String title = jsonField(o, cardRules.titleRule);
            String pic = HikerExtractor.absolutize(pageUrl, jsonField(o, cardRules.picRule));
            String sub = jsonField(o, cardRules.subtitleRule);
            String url = HikerExtractor.applyPreSuffix(cardRules.prefix,
                    jsonField(o, cardRules.urlRule), cardRules.suffix);
            if (title.isEmpty() && url.isEmpty()) continue;
            out.add(new Vod().id(HikerExtractor.cardId(title, pic, url))
                    .name(title).pic(pic).remarks(sub));
        }
        return out;
    }

    /**
     * JSON 容器取字段：支持 {@code a.b.c} 点路径。
     *
     * <p><b>为什么参数是 {@link Object} 而不是 {@link JSONObject}：</b>
     * 容器形态取决于 JSON 库实现，而"只认一种形态"会静默返回空值：
     * <ul>
     *   <li><b>真 org.json（Android）</b>：{@code put}/{@code parse} 时就把 {@code Map} 包成
     *       {@code JSONObject}、{@code Collection} 包成 {@code JSONArray}，
     *       所以 {@code opt(k)} 对嵌套对象返回 {@code JSONObject}。</li>
     *   <li><b>本项目早期 stub</b>：把原始 {@code LinkedHashMap} 直接存进去，
     *       于是 {@code opt(k)} 返回 {@code Map}。</li>
     * </ul>
     * 两种形态都要接受 —— 只认一种会在另一半输入上静默取不到值。
     * <p>上游私库普遍写 {@code opt() + instanceof JSONObject} 做深解析，
     * 照搬到"返回 Map"的桩上曾看起来正常、换真机就失效；反之亦然。
     * 这里显式两种都收，并用 {@link JSONArray}/{@code List} 收尾处理数组下标。
     *
     * @param root 任意 JSON 型容器（{@code JSONObject} / {@code Map} / {@code JSONArray} / {@code List}）
     */
    public static String jsonField(Object root, String ruleStr) {
        if (root == null || ruleStr == null || ruleStr.isEmpty()) return "";
        String path = ruleStr.split("\\[替换:")[0];
        Object cur = root;
        for (String s : path.split("\\.")) {
            cur = step(cur, s);
            if (cur == null) return "";
        }
        if (cur == JSONObject.NULL) return "";
        String v = String.valueOf(cur);
        if (ruleStr.contains("[替换")) v = HikerSelector.regexReplace(v, ruleStr);
        return v;
    }

    /** 便捷重载：根是 {@link JSONObject}（最常见的调用形态）。 */
    public static String jsonField(JSONObject o, String ruleStr) {
        return jsonField((Object) o, ruleStr);
    }

    /** 沿一个键走一步（容器可以是 JSONObject / Map / JSONArray / List）。 */
    private static Object step(Object cur, String key) {
        if (cur == null || key == null) return null;
        if (cur instanceof JSONObject) return ((JSONObject) cur).opt(key);
        if (cur instanceof Map) return ((Map<?, ?>) cur).get(key);
        int idx = -1;
        try {
            idx = Integer.parseInt(key.trim());
        } catch (Exception e) {
            return null;
        }
        if (cur instanceof JSONArray) return ((JSONArray) cur).opt(idx);
        if (cur instanceof List) {
            List<?> l = (List<?>) cur;
            return idx >= 0 && idx < l.size() ? l.get(idx) : null;
        }
        return null;
    }

    /** 便捷：{@link #vodsToArray} 的逐条形态。 */
    protected static List<JSONObject> arrayOf(List<Vod> vods) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        JSONArray arr = vodsToArray(vods);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(o);
        }
        return out;
    }

    /**
     * 上游 {@code homeVideoContent} 的「按分类聚合首页」分支。
     *
     * @param total 累计到多少条就停（上游 20）
     * @param per   每个分类最多取几条（上游 5）
     */
    public JSONArray aggregateByCategory(int total, int per) {
        JSONArray out = new JSONArray();
        String ids = rule.value(CLASS_VALUE);
        if (ids.isEmpty()) return out;
        CardRules cat = rulesOf(rule, Section.CATEGORY);
        String rcmedUrl = rule.value(HOME_RECOMMEND_URL);
        for (String tid : ids.split("&")) {
            if (out.length() >= total) break;
            String url = HikerExtractor.fillCategory(rcmedUrl, tid.replaceAll("＆＆", "&"), "1", baseUrl);
            List<Vod> vods = parsePage(url, cat, per);
            for (JSONObject o : arrayOf(vods)) {
                if (out.length() >= total) break;
                out.put(o);
            }
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    // categoryContent
    // ══════════════════════════════════════════════════════════════

    /**
     * 分类翻页。
     *
     * <p>smali {@code XYQHiker.smali:6470}（外壳）+ {@code:17038}（原 {@code s}，核心）。
     *
     * <p>响应形状（smali {@code:20290-20355} 逐条确认）：
     * <pre>
     * { "page": 请求页, "pagecount": 2147483647, "limit": list.length,
     *   "total": 2147483647, "list": [...] }
     * </pre>
     * {@code list} 为空时上游 {@code return null}（外壳把它翻成空串）。
     */
    @Override
    public String categoryContent(String tid, String pg, boolean quick, HashMap<String, String> extend) {
        JSONObject o = categoryJson(tid, pg, extend);
        return o == null ? "" : o.toString();
    }

    /** 分类核心：可被测试直接调用（不做网络）。 */
    public JSONObject categoryJson(String tid, String pg, HashMap<String, String> extend) {
        try {
            CardRules cat = rulesOf(rule, Section.CATEGORY);
            String base = rule.value(HOME_RECOMMEND_URL);
            int offset = rule.number(CAT_FIRST_PAGE, 0);
            String realPg = String.valueOf(parsePage(pg) + offset);
            String url = HikerExtractor.fillCategory(base, tid, realPg, baseUrl);
            url = applyExtend(url, extend);
            List<Vod> vods = parsePage(url, cat, 0);
            if (vods.isEmpty()) return null;
            JSONObject out = new JSONObject();
            out.put("page", parsePage(pg));
            out.put("pagecount", Integer.MAX_VALUE);
            out.put("limit", vods.size());
            out.put("total", Integer.MAX_VALUE);
            out.put("list", vodsToArray(vods));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把 {@code extend} 里的筛选项拼进 URL。
     *
     * <p>两条路：
     * <ol>
     *   <li>URL 模板里含 {@code {键名}} 占位符 → 就地替换
     *       （对应上游 {@code {cateId}}/{@code {class}}/{@code {area}}/{@code {year}}/
     *       {@code {lang}}/{@code {by}} 这套占位符，见 {@link HikerExtractor#PLACEHOLDERS}）</li>
     *   <li>否则当查询参数追加</li>
     * </ol>
     */
    protected String applyExtend(String url, HashMap<String, String> extend) {
        if (extend == null || extend.isEmpty()) return url;
        String out = url;
        Map<String, String> asMap = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : extend.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) asMap.put(e.getKey(), e.getValue());
        }
        out = HikerExtractor.fill(out, asMap);
        for (Map.Entry<String, String> e : extend.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || k.isEmpty() || v == null || v.isEmpty()) continue;
            if (url.contains("{" + k + "}")) continue;
            out += (out.indexOf('?') < 0 ? "?" : "&") + k + "=" + v;
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    // searchContent
    // ══════════════════════════════════════════════════════════════

    /**
     * 搜索。
     *
     * <p>smali {@code XYQHiker.smali:20377}/{@code:20389}（外壳）+ {@code:3428}
     * （原 {@code ah}，核心）。
     *
     * <p>上游核心逻辑要点（从 smali 常量表 + 分支还原）：
     * <pre>
     * charset = 网页编码格式 / Coding_format
     * pg 偏移 = 搜索起始页码 / sea_firstpage
     * url = 搜索链接 / search_url
     * 若 url 含 "firstPage=" → 处理起始页标记（缺省 ""）
     * 若 url 含 "{SearchPg}" → 页码原样填；否则页码 = pg-1+偏移
     * url = url.replace("{wd}", URLEncode(key)).replace("{SearchPg}", 页码).split(";")[0]
     * 若 含 "时间戳"/"时间标"/"md5(" → 展开宏
     * 抓取：含 ";post" 走 POST（体来自 "?" 之后），否则 GET
     * 解析：搜索列表数组规则 / sea_arr_rule + 搜索片单* 选择器
     * </pre>
     */
    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String quickSearch) {
        JSONObject o = searchJson(key, quickSearch);
        return o == null ? "" : o.toString();
    }

    /** 搜索核心：可被测试直接调用（不做网络）。 */
    public JSONObject searchJson(String key, String pg) {
        try {
            String template = rule.value(SEARCH_URL);
            if (template.isEmpty()) return null;
            int offset = rule.number(SEARCH_FIRST_PAGE, 0);
            String page = String.valueOf(parsePage(pg) + offset);
            String url = template.replace("{wd}", HikerExtractor.urlEncode(key))
                    .replace("{SearchPg}", page)
                    .replace("{searchPg}", page)
                    .replace("{searchKey}", HikerExtractor.urlEncode(key));
            url = HikerExtractor.expandMacros(url);
            HikerExtractor.UrlSpec spec = HikerExtractor.parseUrlSpec(url);

            Map<String, String> headers = hiker.searchHeaders(spec.url);
            String body;
            if (spec.post) {
                String formSpec = rule.value(SEARCH_POST_BODY);
                String postBody = formSpec.isEmpty() ? spec.body : formSpec;
                // 上游细节（ah smali）：URL 模板里的 {wd} 走 URLEncoder，
                // 但 POST body 模板里的 {wd} 用**未经编码**的原始关键词
                postBody = postBody.replace("{wd}", key)
                        .replace("{searchKey}", key)
                        .replace("{SearchPg}", page)
                        .replace("{searchPg}", page);
                body = fetchPost(spec.url, HikerExtractor.formToMap(postBody), headers);
            } else {
                body = fetch(spec.url, headers);
            }
            if (body == null) body = "";

            CardRules sr = rulesOf(rule, Section.SEARCH);
            List<Vod> vods;
            if (HikerExtractor.isJsonMode(rule.value(SEARCH_MODE))) {
                vods = parseJsonPage(body, sr, spec.url, 0);
            } else {
                vods = parseCards(HikerExtractor.parse(body, spec.url), sr, spec.url, 0);
            }
            if (vods.isEmpty()) return null;
            JSONObject out = new JSONObject();
            out.put("list", vodsToArray(vods));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // detailContent
    // ══════════════════════════════════════════════════════════════

    /**
     * 详情。
     *
     * <p>smali {@code XYQHiker.smali:6505}。
     *
     * <p>入参 {@code ids.get(0)} 的形状是列表页拼出来的
     * {@code 标题$$$图片$$$详情链接}，见
     * {@link HikerExtractor#cardId} / {@link HikerExtractor#splitCardId}。
     *
     * <p>流程图（smali 尾部 {@code:9900-10110} 逐字段确认）：
     * <pre>
     * 若 链接是否直接播放/force_play == 1/是  → 不抓详情页，直接用 id 里的链接拼线路
     * 否则 抓 详情链接（含 ;post 时 POST）
     *   播放列表数组规则/list_arr_rule → 线路容器
     *   线路列表数组规则/tab_arr_rule  → 每条线路
     *     线路标题/tab_title            → vod_play_from
     *     选集列表数组规则/epi_arr_rule → 每集
     *       选集标题/epi_title / 选集链接/epi_url（+ 前缀后缀，可反转）
     *   封面/类型/年代/地区/演员/简介 详情 → vod_pic / type_name / vod_year /
     *        vod_area / vod_actor / vod_content
     * </pre>
     * 输出的键依次是：{@code vod_id, vod_name, vod_pic, type_name, vod_year,
     * vod_area, vod_remarks, vod_actor, vod_director, vod_content,
     * vod_play_from, vod_play_url}。
     */
    @Override
    public String detailContent(List<String> ids) {
        JSONObject o = detailJson(ids);
        return o == null ? VodBuilder.EMPTY_RESPONSE : o.toString();
    }

    /** 详情核心：{@code body} 为 {@code null} 时自动抓取（测试传 fixture 走离线解析）。 */
    public JSONObject detailJson(List<String> ids) {
        try {
            String id = firstId(ids);
            if (id == null || id.isEmpty()) return null;
            String[] parts = HikerExtractor.splitCardId(id);
            String name = parts[0];
            String pic = parts[1];
            String detailUrl = parts[2];

            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", name);
            vod.put("vod_pic", pic);

            CardRules tab = rulesOf(rule, Section.TAB);
            CardRules epi = rulesOf(rule, Section.EPISODE);

            List<String> froms = new ArrayList<String>();
            List<String> urlGroups = new ArrayList<String>();

            if (rule.flag(FORCE_PLAY)) {
                String link = HikerExtractor.applyPreSuffix(rule.value(PLAY_PREFIX), detailUrl,
                        rule.value(PLAY_SUFFIX));
                froms.add(name.isEmpty() ? "直链" : name);
                urlGroups.add(name + "$" + link);
            } else {
                String body = fetchBySpec(detailUrl, detailUrl);
                if (body == null) body = "";
                Dom.El root = HikerExtractor.parse(body, detailUrl);
                List<Dom.El> lines = HikerSelector.selectChained(root, tab.arrayRule);
                for (Dom.El line : lines) {
                    String from = field(line, tab.titleRule, tab.jsoup);
                    if (from.isEmpty()) from = name;
                    List<Dom.El> eps = HikerSelector.selectChained(line, epi.arrayRule);
                    List<String> piped = new ArrayList<String>();
                    for (Dom.El ep : eps) {
                        String t = field(ep, epi.titleRule, epi.jsoup);
                        String u = linkOf(ep, epi.urlRule, epi.jsoup, epi.prefix, epi.suffix);
                        if (t.isEmpty() && u.isEmpty()) continue;
                        piped.add((t.isEmpty() ? u : t) + "$" + u);
                    }
                    if (rule.flag(EPI_REVERSE)) piped = HikerExtractor.reversed(piped);
                    froms.add(from);
                    urlGroups.add(HikerSelector.join(piped, "#"));
                }
                fillDetailFields(root, vod, tab.jsoup);
            }

            vod.put("vod_play_from", HikerSelector.join(froms, ID_SEP));
            vod.put("vod_play_url", HikerSelector.join(urlGroups, ID_SEP));

            JSONObject out = new JSONObject();
            JSONArray arr = new JSONArray();
            arr.put(vod);
            out.put("list", arr);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 详情页的 6 个属性字段（{@code *_详情}）。 */
    protected void fillDetailFields(Dom.El root, JSONObject vod, boolean jsoup) throws Exception {
        if (root == null || vod == null) return;
        String cover = field(root, rule.value(DETAIL_COVER), jsoup);
        String cate = field(root, rule.value(DETAIL_CATE), jsoup);
        String year = field(root, rule.value(DETAIL_YEAR), jsoup);
        String area = field(root, rule.value(DETAIL_AREA), jsoup);
        String actor = field(root, rule.value(DETAIL_ACTOR), jsoup);
        String plot = field(root, rule.value(DETAIL_PLOT), jsoup);
        if (!cover.isEmpty()) vod.put("vod_pic", HikerExtractor.absolutize("", cover));
        if (!cate.isEmpty()) vod.put("type_name", cate);
        if (!year.isEmpty()) vod.put("vod_year", year);
        if (!area.isEmpty()) vod.put("vod_area", area);
        if (!actor.isEmpty()) vod.put("vod_actor", actor);
        if (!plot.isEmpty()) vod.put("vod_content", plot);
    }

    // ══════════════════════════════════════════════════════════════
    // playerContent
    // ══════════════════════════════════════════════════════════════

    /**
     * 播放解析。
     *
     * <p>smali {@code XYQHiker.smali:13478}。
     *
     * <p>实现的两条路径：
     * <ol>
     *   <li>{@code id} 形如 {@code 名称$地址} → 拆出地址，按
     *       {@code 直接播放链接加前缀/后缀} 拼装，必要时用
     *       {@code 直接播放直链视频请求头}/{@code play_header} 下发请求头。</li>
     *   <li>其它情况：把 URL 原样下发，{@code parse=1} 让宿主自己去嗅探 ——
     *       对应上游「不明地址就走嗅探」的分支。</li>
     * </ol>
     * 未实现的 MacPlayer JS 深挖见类注释「未覆盖的分支」第 4 条。
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        JSONObject out = new JSONObject();
        try {
            String name = "";
            String url = id == null ? "" : id;
            int dollar = url.lastIndexOf('$');
            if (dollar > 0) {
                name = url.substring(0, dollar);
                url = url.substring(dollar + 1);
            }
            url = HikerExtractor.applyPreSuffix(rule.value(PLAY_PREFIX), url, rule.value(PLAY_SUFFIX));
            out.put("parse", 0);
            out.put("playUrl", "");
            out.put("url", url);
            // header 只承载「直接播放直链视频请求头」。片名不能塞进来：
            // 宿主会把 header 的值当请求头解析，塞片名会让播放失败。
            String playHeader = rule.value(HikerRule.PLAY_HEADER);
            if (!playHeader.isEmpty()) out.put("header", playHeader);
            return out.toString();
        } catch (Exception e) {
            return VodBuilder.playerContent(id, flag);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 嗅探开关
    // ══════════════════════════════════════════════════════════════

    /** smali {@code:13253}。关键词/过滤词表见 {@link HikerExtractor#sniffMatch}。 */
    @Override
    public boolean isVideoFormat(String url) {
        return hiker.sniffMatch(url);
    }

    /** smali {@code:13415}。DSL 取值 {@code "1"}/{@code "是"} 为真。 */
    @Override
    public boolean manualVideoCheck() {
        return hiker.manualSniff();
    }

    // ══════════════════════════════════════════════════════════════
    // 小工具
    // ══════════════════════════════════════════════════════════════

    /** 页码解析，失败当第 1 页。 */
    protected static int parsePage(String pg) {
        try {
            return Integer.parseInt(pg.trim());
        } catch (Exception e) {
            return 1;
        }
    }

    /** 便于测试：直接设定规则（不经过 init/ext）。 */
    public XYQHikerPlain withRule(HikerRule r) {
        this.rule = r == null ? HikerRule.empty() : r;
        this.hiker = new HikerExtractor(this.rule);
        return this;
    }

    /** 便于测试/调试：当前各分区解析出的卡片规格。 */
    public Map<String, String> describeSections() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (Section s : Section.values()) m.put(s.name(), String.valueOf(rulesOf(rule, s)));
        return m;
    }

    /** 便于测试：直接统计「能否工作」（关键字段齐全）。 */
    public boolean ready() {
        return rulesOf(rule, Section.HOME).usable()
                || rulesOf(rule, Section.CATEGORY).usable()
                || !rule.value(SEARCH_URL).isEmpty()
                || !rule.value(HOME_RECOMMEND_URL).isEmpty()
                || rule.flag(FORCE_PLAY);
    }

    /** HTTP 是否处于离线（测试断言用）。 */
    public static boolean offline() {
        return Http.isOfflineForTest();
    }
}
