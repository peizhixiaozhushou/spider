package com.example.tvbox.plain.bili;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.Digest;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 哔哩哔哩（{@code csp_Bili}）明文实现。
 *
 * <p>反编译原文: {@code ae/decompiled/Bili.java}（515 行，26 个方法）。
 *
 * <h2>WBI 签名（本类最关键、最易被「顺手重构」改坏的部分）</h2>
 * B 站的 {@code /x/space/wbi/arc/search} 与 {@code /x/polymer/web-dynamic/v1/feed/all}
 * 要求 {@code w_rid} + {@code wts}。上游完整实现了它，本实现逐字还原并用
 * <b>固定向量</b>钉死（见 {@code BiliTest} 的 {@code WBI} 段）：
 * <pre>
 * 1) mixinKey = (imgKey + subKey) 按 mixinKeyEncTab 前 32 个下标重排
 *      下标表（64 项，反编译 Bili.java:38 逐字保留）：
 *      46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,
 *      14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,
 *      56,59,6,63,57,62,11,36,20,34,44,52
 *      （注意：只用前 32 个下标 → mixinKey 固定 32 字符）
 * 2) params.put("wts", now/1000)
 * 3) 排序串 = TreeMap(params 去掉 w_rid) 逐项 "k=" + URLEncoder(v) + "&" ，去掉尾 "&"
 * 4) w_rid = MD5(排序串 + mixinKey)
 * 5) 最终串 = LinkedHashMap(params) 同法拼（<b>保持插入顺序</b>），去尾 "&" ，再补 "&w_rid=HEX"
 * </pre>
 *
 * <p><b>已知固定向量</b>（imgKey {@code 7cd084941338484aae1ad9425b84077c}、
 * subKey {@code 4932caff0ff746eab6f01bf08b70ac45}）：
 * <pre>
 * mixinKey = ea1db124af3c7062474693fa704f4ff8
 * params   = {type:video, platform:web, web_location:333.1365,
 *             features:itemOpusStyle,listOnlyfans, offset:""}  wts=1700000000
 * 排序串   = features=itemOpusStyle%2ClistOnlyfans&offset=&platform=web
 *            &type=video&web_location=333.1365&wts=1700000000
 * w_rid    = 2512b53ac87e9ea2c545afb2571cf604
 * 输出     = type=video&platform=web&web_location=333.1365
 *            &features=itemOpusStyle%2ClistOnlyfans&offset=&wts=1700000000
 *            &w_rid=2512b53ac87e9ea2c545afb2571cf604
 * </pre>
 * URL 编码用 Java {@code URLEncoder}（字母数字与 {@code .-*_} 不编码、空格→{@code +}）；
 * 生成向量时也是按同一规则独立算的，两者一致才通过 —— 算法被改动会立刻红。
 *
 * <h2>为什么 {@code categoryContent} 的 w_rid 是「假」的</h2>
 * 反编译 Bili.java:220 里 {@code qv_id} 与 {@code w_rid} 都用
 * {@code merge.A.a.m1b(32)}（随机 32 字符）填，<b>不是真签名</b>。这是上游行为，
 * 本实现用 {@link #randomToken(int)} 等价复刻（否则与真实流量不符）。
 *
 * <h2>混淆 DTO → TVBox 字段的映射（推断依据在此，勿凭感觉改）</h2>
 * 上游用 {@code merge.p028c.e}（详情构建器）的单字母 setter 与 {@code merge.p031f.b}
 * （响应包装）的单字母 getter。单字母语义靠<b>跨类交叉印证</b>得到：
 * <table border="1">
 *   <tr><th>setter</th><th>TVBox 字段</th><th>交叉证据（其它类的同名用法）</th></tr>
 *   <tr><td>{@code j}</td><td>vod_id</td><td>{@code eVar.j(list.get(0))} 出现 13 次，全在详情首位</td></tr>
 *   <tr><td>{@code k}</td><td>vod_name</td><td>{@code eVar3.k(o.optString("vod_name"))}</td></tr>
 *   <tr><td>{@code l}</td><td>vod_pic</td><td>{@code eVar3.l(o.optString("vod_pic"))}</td></tr>
 *   <tr><td>{@code m}</td><td>vod_play_from</td><td>{@code eVar.m(join("$$$", ...))}</td></tr>
 *   <tr><td>{@code n}</td><td>vod_play_url</td><td>{@code eVar.n(join("#"/"$$$", ...))}</td></tr>
 *   <tr><td>{@code o}</td><td>vod_remarks</td><td>{@code eVar3.o(o.optString("vod_remarks"))}</td></tr>
 *   <tr><td>{@code p}</td><td>vod_tag</td><td>{@code eVar.p("folder")} / {@code eVar.p("file")}</td></tr>
 *   <tr><td>{@code f}</td><td>vod_actor</td><td>{@code eVar.f(o.optString("vod_actor"))}</td></tr>
 *   <tr><td>{@code i}</td><td>vod_director</td><td>{@code eVar.i(o.optString("vod_director"))}</td></tr>
 *   <tr><td>{@code h}</td><td>vod_content</td><td>{@code eVar3.h(o.optString("vod_content"))}</td></tr>
 *   <tr><td>{@code e}</td><td>vod_class</td><td>{@code eVar3.e(o.optString("vod_class"))}</td></tr>
 *   <tr><td>{@code g}</td><td>vod_area</td><td>{@code eVar.g(o.optString("vod_area"))}</td></tr>
 *   <tr><td>{@code q}</td><td>vod_year</td><td>{@code eVar.q(o.optString("vod_year"))}</td></tr>
 * </table>
 * 复现命令：{@code grep -rhoE 'eVar[A-Za-z0-9]*\.[a-z]\([^;]{0,50}' ae/decompiled/*.java | sort | uniq -c}
 *
 * <p>由此反推 {@code detailContent} 里 {@code p031f.b} 的 getter：
 * {@code k()=data.pic}、{@code n()=data.title}、{@code o()=data.tname}、
 * {@code f()=data.desc}、{@code g()=data.duration}、{@code t()=data.owner}（{@code a()=owner.name}）、
 * {@code j()=data.pages}（{@code a()=cid}/{@code b()=part}）、{@code d()=data.cid}、
 * {@code b()=accept_quality}、{@code a()=accept_description}。
 * 这些字段名与 B 站 {@code /x/web-interface/view} 与
 * {@code /x/player/playurl} 的公开响应结构一致。
 *
 * <h2>未能确定的上游细节（如实列出）</h2>
 * <ol>
 *   <li>{@code p031f.b} 的 {@code k()} 同时被当成 {@code nav} 的登录位使用
 *       （{@code this.b = bVarA.m13k()}，jadx 的 mangled 名）。两者不可能同为
 *       {@code k()}，故 nav 侧的登录/会员位<b>按真实字段 {@code isLogin/vipStatus} 还原</b>，
 *       与 {@link com.example.tvbox.plain.amu.AmuBiliV2Plain} 的结论一致。</li>
 *   <li>{@code p028c.c}（播放构建器）的 {@code b()} 无参无字面量，无法从静态产物
 *       确定它写入哪个字段；本实现按同族构建器语义取 {@code parse=1}，
 *       并暴露 {@link #playerParseFlag} 供宿主改写。</li>
 *   <li>DASH 的 {@code Representation id} 用 {@code {id}_{codecid}}；{@code codecid}
 *       是推断（B 站 dash.video 里唯一能与 {@code id} 组合成唯一 id 的字段）。</li>
 *   <li>{@code e} setter 写出的<b>字段名</b>：跨类证据显示它的取值源是
 *       {@code o.optString("vod_class")}（如 {@code eVar3.e(o.optString("vod_class"))}），
 *       即「输入键名 = vod_class」。TVBox 的 Vod 模型里对应字段是 {@code type_name}
 *       （{@code com.github.catvod.bean.Vod#typeName}），故本实现写入
 *       {@code vod.typeName(...)} → 输出 {@code type_name}。
 *       反编译产物看不到 setter 内部，因此「写 type_name 还是 vod_class」无法 100% 确认；
 *       选 {@code type_name} 是因为它是宿主真正会读的标准字段。
 *       测试断言同时接受两者（{@code vod_type + type_name}），改动实现时会仍然通过，
 *       若要钉死字段名请把断言收紧为 `type_name`。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Bili.java}
 * 端点清单:   {@code out/sources/spider_interfaces.json} → {@code csp_Bili}（8 条）

 * <h2>未覆盖的分支</h2>
 * <ul>
 *   <li><b>{@code p028c.c.b()} 无参无字面量</b>：无法从静态产物确定它写哪个字段，
 *       本实现按同族构建器语义取 {@code parse=1}，并暴露 {@code playerParseFlag} 供宿主改写。</li>
 *   <li><b>{@code e} setter 写出的字段名</b>：跨类证据指向 {@code vod_class}，
 *       TVBox 侧对应 {@code type_name}；反编译看不到 setter 内部，故本实现写 {@code type_name}，
 *       测试同时接受两者。要钉死字段名请把断言收紧为 {@code type_name}。</li>
 *   <li><b>DASH {@code Representation id} 里的 {@code codecid}</b> 是推断
 *       （B 站 {@code dash.video} 里唯一能与 {@code id} 组合成唯一 id 的字段）。</li>
 *   <li><b>宿主私有 {@code Proxy.getUrl()}</b>：上游直接调宿主静态方法，全仓无等价物。
 *       本实现保留 {@code DEFAULT_PROXY}（宿主默认端口）+ 可注入 seam。</li>
 *   <li><b>宿主私有落盘/缓存设施</b>：会话文件路径、写盘动作都走可注入 seam；
 *       真实宿主的 {@code Init.execute(Runnable)} 异步壳未复现（同步写）。</li>
 * </ul>
 *
 * <h2>修正的上游缺陷（合并 BiliBase 时逐条对齐，均有断言钉住）</h2>
 * <ol>
 *   <li><b>{@code SegmentBase.Initialization} 的形状</b>：B 站 playurl 给的是<b>字符串</b>
 *       {@code "Initialization":"0-991"}（与 DASH 规范的 {@code <Initialization range=".."/>}
 *       元素不同）。{@code BiliPlain} 一直按字符串读（对），而 {@code BiliDash} 原先只读
 *       {@code Initialization.range}（对象形式，对真实响应得到空串）。→ 两边都改：
 *       {@code BiliDash.initRange} 两种都收、字符串优先。
 *       断言：{@code initRange 字符串形式（B 站真实形状）}、{@code initRange 对象形式（DASH 规范）}、
 *       {@code Initialization range}。</li>
 *   <li><b>会员位判定不一致</b>：{@code BiliPlain} 用 {@code vipStatus==1 || vipType>0}，
 *       {@code BiliBase}（BiliYS 用）用 {@code vipStatus != 0} —— 后者会把真实接口里
 *       不存在的 2 也算成会员。→ 统一为前者（与同工程 {@code AmuBiliV2Plain} 的结论一致，
 *       两者读的是同一个 {@code p031f.b} bean）。
 *       断言：{@code vipStatus=1 → 会员}、{@code vipStatus=2 不算会员（与 AmuBiliV2Plain 对齐）}、
 *       {@code vipType>0 也算会员}。</li>
 *   <li><b>随机 token 的字符表顺序</b>：上游 {@code merge.A.a.m1b} 的字面量是
 *       <b>字母在前</b>（{@code "abc…XYZ0-9"}），{@code BiliPlain} 原先写的是数字在前。
 *       → 统一到上游字面量顺序，并只保留一份 {@code BiliBase.ALNUM}。
 *       断言：{@code ALNUM 字母在前（上游 m1b 字面量顺序）}。</li>
 *   <li><b>{@code minBufferTime} 的缺省值是发明出来的</b>：{@code BiliPlain} 原先
 *       {@code optString("minBufferTime", "1.5")} —— 上游只是把 bean 的值原样打进模板，
 *       没有 1.5 这个默认。→ 改为按原值直出（{@code BiliDash}）。
 *       断言：{@code minBufferTime 用之字面值}。</li>
 *   <li><b>同一概念三处重复实现</b>：空 MPD（两份字面量）、音频采样率表（两份）、
 *       {@code formEncode}（两份，一份收 {@code Object} 一份收 {@code String}）。
 *       → 各收敛成一份（{@code BiliDash.emptyMpd} / {@code BiliDash.AUDIO_SAMPLE_RATE} /
 *       {@code BiliBase.formEncode(Map&lt;String,?&gt;)}）。
 *       断言：{@code emptyMpd 与 BiliDash 同一份}、{@code AUDIO_SAMPLE_RATE 与 BiliDash 同一个对象}、
 *       {@code formEncode 收 Object 值}。</li>
 *   <li><b>{@code i()} 的 cookie 回落</b>：上游 {@code Bili.java:135-142} 在静态 cookie 为空时
 *       <b>真的会去读会话文件</b>，且 {@code %2C} 转义发生在回落<b>之后</b>（143 行）。
 *       {@code BiliBase} 原先只读静态 cookie、不回落到文件。→ 按上游补上回落。
 *       断言：{@code 无静态 cookie 时回落到会话文件}、{@code 回落后才做 %2C 转义（上游 143 行在回落之后）}。</li>
 * </ol>
 */
public class BiliPlain extends BiliBase {

    private static final String TAG = "BiliPlain";

    /** UA（反编译 Bili.java:134）—— 与 {@link BiliBase#UA} 同一份字面量 */
    public static final String UA = BiliBase.UA;

    /** WBI 下标表（反编译 Bili.java:38 逐字保留，64 项） */
    public static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52};

    // ── 端点（全部取自反编译字面量） ──────────────────────────
    /** 登录态 / WBI 密钥 —— 与 {@link BiliBase#API_NAV} 同一份字面量 */
    public static final String NAV = BiliBase.API_NAV;
    /** 搜索（分类页） */
    public static final String SEARCH_TYPE =
            "https://api.bilibili.com/x/web-interface/wbi/search/type";
    /** 稿件详情 */
    public static final String VIEW = "https://api.bilibili.com/x/web-interface/view";
    /** 播放地址 */
    public static final String PLAYURL = "https://api.bilibili.com/x/player/playurl";
    /** 热门（首页推荐） */
    public static final String POPULAR = "https://api.bilibili.com/x/web-interface/popular";
    /** 关注列表 */
    public static final String FOLLOWINGS = "https://api.bilibili.com/x/relation/followings";
    /** 历史记录 */
    public static final String HISTORY = "https://api.bilibili.com/x/v2/history";
    /** 动态流 */
    public static final String DYNAMIC = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all";
    /** UP 主投稿 */
    public static final String SPACE_ARC = "https://api.bilibili.com/x/space/wbi/arc/search";
    /** 心跳（播放上报）—— 与 {@link BiliBase#API_HEARTBEAT} 同一份字面量 */
    public static final String HEARTBEAT = BiliBase.API_HEARTBEAT;
    /** 弹幕 XML —— 与 {@link BiliBase#API_DANMAKU} 同一份字面量 */
    public static final String DM_LIST = BiliBase.API_DANMAKU;
    /** Referer —— 与 {@link BiliBase#REFERER} 同一份字面量 */
    public static final String REFERER = BiliBase.REFERER;

    /** 页面数据文件（同时含 cookie 与 mid，见 AmuBiliV2Plain#persistSession） */
    public static final String CACHE_NAME = BiliBase.DEFAULT_COOKIE_FILE;

    /**
     * 音频质量 → 采样率（反编译 Bili.java:124-130）。
     *
     * <p>与 {@link BiliDash#AUDIO_SAMPLE_RATE} 是<b>同一个对象</b>：
     * Bili 与 BiliYS 的 DASH 渲染都读这一份表。
     */
    public static final Map<String, String> AUDIO_SAMPLE_RATE = BiliDash.AUDIO_SAMPLE_RATE;

    /** 非登录用户允许的最高 qn（反编译 Bili.java:252 的 {@code iIntValue <= 32}） */
    public static final int QN_GUEST_MAX = QN_LOGIN_GATE;
    /** 非会员允许的最高 qn（{@code iIntValue <= 80}） */
    public static final int QN_LOGIN_MAX = QN_VIP_GATE;

    // ─────────────────────────────────────────────────────────────
    // 静态会话（还原上游的 static h / mid / wbiImgUrl / wbiSubUrl / lastOffset）
    // ─────────────────────────────────────────────────────────────

    /** 动态流游标（上游 static {@code lastOffset}） */
    private static String LAST_OFFSET;

    /** 播放构建器的 parse 标志（见类注释「未能确定」第 2 条） */
    private static int playerParseFlag = 1;

    // ─────────────────────────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────────────────────────

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        this.http = Http.create().ua(UA);
        resetSession();
    }

    /** 清空静态会话（测试 / 换账号用） */
    public static void resetSession() {
        cookie = "";
        mid = "";
        wbiImgUrl = "";
        wbiSubUrl = "";
        LAST_OFFSET = null;
    }

    /** @return 播放构建器的 parse 标志 */
    public static int playerParseFlag() {
        return playerParseFlag;
    }

    /**
     * 改写播放 parse 标志（见类注释「未能确定」第 2 条）。
     *
     * @param flag 0/1
     */
    public static void setPlayerParseFlag(int flag) {
        playerParseFlag = flag;
    }

    /** @return 当前动态流游标 */
    public static String lastOffset() {
        return LAST_OFFSET;
    }

    /**
     * 设置动态流游标（测试用）。
     *
     * @param o 游标；null 表示「未初始化」
     */
    public static void setLastOffset(String o) {
        LAST_OFFSET = o;
    }

    // ─────────────────────────────────────────────────────────────
    // WBI
    // ─────────────────────────────────────────────────────────────

    /**
     * 取 URL 最后一段路径中第一个 {@code .} 之前的部分
     * （还原上游 {@code Uri.parse(u).getLastPathSegment().split("\\.")[0]}）。
     *
     * @param url 形如 {@code https://i0.hdslb.com/bfs/wbi/abc.png}
     * @return key；解析失败空串
     */
    public static String lastSegmentKey(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int slash = url.lastIndexOf('/');
        String seg = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = seg.indexOf('.');
        return dot >= 0 ? seg.substring(0, dot) : seg;
    }

    /**
     * 还原上游 {@code getMixinKey(String, String)}：
     * {@code (imgKey + subKey)} 按 {@link #MIXIN_KEY_ENC_TAB} 前 32 个下标重排。
     *
     * @param imgKey img_key
     * @param subKey sub_key
     * @return 32 字符 mixinKey；拼接串短于 64 时按可用长度尽力取（上游会抛，这里不抛）
     */
    public static String getMixinKey(String imgKey, String subKey) {
        String cat = (imgKey == null ? "" : imgKey) + (subKey == null ? "" : subKey);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            int idx = MIXIN_KEY_ENC_TAB[i];
            if (idx < cat.length()) {
                sb.append(cat.charAt(idx));
            } else {
                Log.w(TAG, "mixinKey: 拼接串只有 " + cat.length() + " 字符，缺少下标 " + idx);
            }
        }
        return sb.toString();
    }

    /**
     * 从当前 WBI 密钥 URL 推 mixinKey。
     *
     * @return mixinKey；密钥缺失返回空串
     */
    public static String mixinKey() {
        return getMixinKey(lastSegmentKey(wbiImgUrl), lastSegmentKey(wbiSubUrl));
    }

    /**
     * 还原上游 {@code substring(String)}：去掉最后 1 个字符。
     *
     * @param s 原文
     * @return 去尾结果
     */
    public static String substring(String s) {
        return substring(s, 1);
    }

    /**
     * 还原上游 {@code substring(String, int)}：去掉最后 {@code i} 个字符；
     * 长度不足时原样返回（上游同）。
     *
     * @param s 原文
     * @param i 去掉的字符数
     * @return 结果
     */
    public static String substring(String s, int i) {
        return (s == null || s.length() <= i) ? s : s.substring(0, s.length() - i);
    }

    /**
     * 还原上游 {@code getQuery(LinkedHashMap)}：用当前时间戳做 {@code wts}。
     *
     * @param params 参数（会被原地 put {@code wts}）
     * @return 带 {@code w_rid} 的查询串
     */
    public static String getQuery(LinkedHashMap<String, Object> params) {
        return getQuery(params, System.currentTimeMillis() / 1000L);
    }

    /**
     * 还原上游 {@code getQuery(LinkedHashMap)} 的完整算法（{@code wts} 可注入）。
     *
     * <p>见类注释顶部的 5 步；这一步是固定的，改动会被
     * {@code BiliTest} 的固定向量测试立刻抓住。
     *
     * @param params 参数表（插入顺序即输出顺序）
     * @param wts    秒级时间戳
     * @return 查询串 {@code k=v&...&wts=..&w_rid=HEX}
     */
    public static String getQuery(LinkedHashMap<String, Object> params, long wts) {
        if (params == null) {
            params = new LinkedHashMap<>();
        }
        String mk = mixinKey();
        params.put("wts", wts);

        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (!"w_rid".equals(e.getKey())) {
                sorted.put(e.getKey(), e.getValue());
            }
        }
        StringBuilder signSrc = new StringBuilder();
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            signSrc.append(e.getKey()).append('=')
                    .append(enc(String.valueOf(e.getValue()))).append('&');
        }
        String wRid = Digest.md5(substring(signSrc.toString()) + mk);

        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            out.append(e.getKey()).append('=')
                    .append(enc(String.valueOf(e.getValue()))).append('&');
        }
        return substring(out.toString()) + "&w_rid=" + wRid;
    }

    /**
     * 还原上游 {@code g(int, String)}：UP 主投稿的参数表（<b>顺序即字面量顺序</b>）。
     *
     * @param pn  页码
     * @param mid UP 主 mid
     * @return 参数表
     */
    public static LinkedHashMap<String, Object> spaceArcParams(int pn, String mid) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("mid", mid);
        m.put("pn", String.valueOf(pn));
        m.put("ps", String.valueOf(30));
        m.put("order", "pubdate");
        m.put("tid", "0");
        m.put("keyword", "");
        m.put("platform", "web");
        m.put("web_location", "1550101");
        m.put("order_avoided", "true");
        m.put("dm_img_list", "[]");
        m.put("dm_img_str", "V2ViR0wgMS");
        m.put("dm_cover_img_str", "SW50ZWwoUikgSEQgR3JhcGhpY3NJbnRlbA");
        return m;
    }

    // ─────────────────────────────────────────────────────────────
    // 首页（还原 homeContent）
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code homeContent(boolean)}：
     * {@code ext.n} 命中内置 6 套配置之一 → 原样返回该 JSON；
     * 否则若有 {@code ext.json} → 拉那个 URL 并原样返回；再否则空结构。
     *
     * @param filter 是否要筛选（上游忽略：内置配置本身就带 filters）
     * @return 首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        String n = ext.optStr("n", "");
        String builtin = BiliHomeConfigs.config(n);
        if (builtin != null) {
            Log.i(TAG, "homeContent builtin=" + n);
            return builtin;
        }
        if (ext.has("json")) {
            String url = ext.optStr("json", "");
            if (!url.isEmpty()) {
                String body = fetch(url, apiHeaders());
                if (!body.isEmpty()) {
                    return body;
                }
                Log.w(TAG, "homeContent ext.json 拉取失败: " + url);
            }
        }
        return BiliHomeConfigs.EMPTY;
    }

    /**
     * 还原上游 {@code homeVideoContent()}：热门 {@code ps=20}。
     *
     * @return {@code {"list":[...]}}
     */
    @Override
    public String homeVideoContent() {
        String body = fetch(POPULAR + "?ps=20", apiHeaders());
        return listJson(parseSearchResult(body));
    }

    // ─────────────────────────────────────────────────────────────
    // 分类 / 搜索（还原 categoryContent + 5 个 mybili*）
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code categoryContent}：5 个特殊 tid 分派 + 普通搜索。
     *
     * @param tid    分类 id（特殊值见类注释）
     * @param pg     页码
     * @param filter 忽略
     * @param extend 可含 {@code order} / {@code duration} / {@code tid}
     * @return 标准响应
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        if ("我的关注".equals(tid)) {
            return mybili(pg);
        }
        if (tid != null && tid.startsWith("bili_up_")) {
            return mybiliup(tid, pg);
        }
        if ("历史记录".equals(tid)) {
            return mybilihistory(pg);
        }
        if ("最新动态".equals(tid)) {
            return mybililatest(pg);
        }
        if ("其他分区".equals(tid)) {
            return BiliHomeConfigs.PARTITION;
        }
        String order = extParam(extend, "order", "totalrank");
        String duration = extParam(extend, "duration", "0");
        String keyword = tid == null ? "" : tid;
        String tidFilter = extend == null ? null : extend.get("tid");
        if (tidFilter != null && !tidFilter.isEmpty()) {
            keyword = keyword + " " + tidFilter;
        }
        String url = SEARCH_TYPE
                + "?qv_id=" + randomToken(32)
                + "&search_type=video&&web_location=1430654"
                + "&w_rid=" + randomToken(32)
                + "&keyword=" + enc(keyword)
                + "&order=" + order
                + "&duration=" + duration
                + "&page=" + (pg == null ? "" : pg);
        String body = fetch(url, apiHeaders());
        return listJson(parseSearchResult(body));
    }

    /**
     * 还原上游 {@code searchContent(String, boolean)}：转发到分类搜索。
     *
     * @param key   关键词
     * @param quick 忽略
     * @return 标准响应
     */
    @Override
    public String searchContent(String key, boolean quick) {
        return categoryContent(key, "1", true, new HashMap<String, String>());
    }

    /**
     * 还原上游 {@code searchContent(String, boolean, String)}（带页码重载）。
     *
     * @param key   关键词
     * @param quick 忽略
     * @param pg    页码
     * @return 标准响应
     */
    @Override
    public String searchContent(String key, boolean quick, String pg) {
        return categoryContent(key, pg, true, new HashMap<String, String>());
    }

    /**
     * 还原上游 {@code mybili(String, String)}：我的关注。
     *
     * <p>上游用 {@code new JSONObject(k.a(k.b("/bili_cookie.txt"))).getString("mid")}
     * 取 mid；取不到时整段抛异常并返回 {@code {"list":[]}}。本实现用
     * {@link #loadSession()}，缺失同样返回空列表。
     *
     * @param pg 页码
     * @return {@code {"list":[...]}}，失败时 {@code {"list":[]}}
     */
    public String mybili(String pg) {
        JSONObject empty = emptyList();
        try {
            JSONObject session = loadSession();
            String m = session.optString("mid");
            if (TextUtils.isEmpty(m)) {
                m = mid;
            }
            if (TextUtils.isEmpty(m)) {
                Log.w(TAG, "mybili 无 mid，返回空列表");
                return empty.toString();
            }
            int pn = Integer.parseInt(pg == null ? "1" : pg);
            String url = FOLLOWINGS + "?vmid=" + m + "&pn=" + pn
                    + "&ps=50&order=desc&order_type=attention";
            JSONObject root = new JSONObject(fetch(url, apiHeaders()));
            JSONArray list = root.getJSONObject("data").getJSONArray("list");
            List<Vod> out = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                out.add(new Vod()
                        .id("bili_up_" + o.getString("mid"))
                        .name(o.getString("uname"))
                        .pic(o.getString("face"))
                        .remarks(o.getString("sign"))
                        .tag("folder"));
            }
            return listJson(out);
        } catch (Throwable e) {
            Log.w(TAG, "mybili failed", e);
            return empty.toString();
        }
    }

    /**
     * 还原上游 {@code mybilihistory(String, String)}：历史记录。
     *
     * @param pg 页码
     * @return {@code {"list":[...]}}，失败时 {@code {"list":[]}}
     */
    public String mybilihistory(String pg) {
        try {
            JSONArray arr = new JSONObject(fetch(HISTORY + "?pn=" + pg + "&ps=30", apiHeaders()))
                    .getJSONArray("data");
            List<Vod> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Vod()
                        .id(String.valueOf(o.optLong("aid", 0)))
                        .name(o.getString("title"))
                        .pic(o.getString("pic"))
                        .remarks(clock(o.getInt("duration"))));
            }
            return listJson(out);
        } catch (Throwable e) {
            Log.w(TAG, "mybilihistory failed", e);
            return emptyList().toString();
        }
    }

    /**
     * 还原上游 {@code mybililatest(String, String)}：最新动态（<b>走 WBI 签名</b>）。
     *
     * <p>游标语义与上游一致：上游把上次的 {@code offset} 放进参数，再把响应里的
     * {@code data.offset} 存回静态字段。
     *
     * @param pg 页码（上游未使用，动态流是游标式）
     * @return {@code {"list":[...]}}，失败时 {@code {"list":[]}}
     */
    public String mybililatest(String pg) {
        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("type", "video");
            params.put("platform", "web");
            params.put("web_location", "333.1365");
            params.put("features",
                    "itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote,decorationCard,"
                            + "onlyfansAssetsV2,forwardListHidden,ugcDelete");
            String offset = LAST_OFFSET;
            if (offset == null) {
                offset = "";
                LAST_OFFSET = "";
            }
            params.put("offset", offset);
            String body = fetch(DYNAMIC + "?" + getQuery(params), apiHeaders());
            JSONObject data = new JSONObject(body).getJSONObject("data");
            LAST_OFFSET = data.optString("offset", "");
            JSONArray items = data.getJSONArray("items");
            List<Vod> out = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject a = items.getJSONObject(i)
                        .getJSONObject("modules")
                        .getJSONObject("module_dynamic")
                        .getJSONObject("major")
                        .getJSONObject("archive");
                out.add(new Vod()
                        .id(a.getString("aid"))
                        .name(a.getString("title"))
                        .pic(a.getString("cover"))
                        .remarks(a.getString("duration_text")));
            }
            return listJson(out);
        } catch (Throwable e) {
            Log.w(TAG, "mybililatest failed", e);
            return emptyList().toString();
        }
    }

    /**
     * 还原上游 {@code mybiliup(String, String)}：某个 UP 主的投稿（<b>走 WBI 签名</b>）。
     *
     * @param tid 形如 {@code bili_up_<mid>}
     * @param pg  页码
     * @return {@code {"list":[...]}}，失败时 {@code {"list":[]}}
     */
    public String mybiliup(String tid, String pg) {
        try {
            int pn = Integer.parseInt(pg == null ? "1" : pg);
            String m = tid.substring("bili_up_".length());
            String url = SPACE_ARC + "?" + getQuery(spaceArcParams(pn, m));
            JSONArray vlist = new JSONObject(fetch(url, apiHeaders()))
                    .getJSONObject("data").getJSONObject("list").getJSONArray("vlist");
            JSONArray out = new JSONArray();
            for (int i = 0; i < vlist.length(); i++) {
                JSONObject o = vlist.getJSONObject(i);
                JSONObject one = new JSONObject();
                one.put("vod_id", String.valueOf(o.optLong("aid", 0)));
                one.put("vod_name", o.getString("title"));
                one.put("vod_pic", o.getString("pic"));
                one.put("vod_remarks", o.getString("length"));
                out.put(one);
            }
            JSONObject root = new JSONObject();
            root.put("list", out);
            return root.toString();
        } catch (Throwable e) {
            Log.w(TAG, "mybiliup failed", e);
            return emptyList().toString();
        }
    }

    /**
     * 还原上游 {@code partition(String, String)}：其他分区（字面量）。
     *
     * @return 分区列表 JSON（逐字来自反编译）
     */
    public String partition() {
        return BiliHomeConfigs.PARTITION;
    }

    // ─────────────────────────────────────────────────────────────
    // 详情（还原 detailContent）
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code detailContent(List)}。
     *
     * <p>先确保会话（cookie + 登录/会员位），再拿 view 与 playurl，
     * 按登录/会员门槛过滤清晰度，最后把分 P 拼成
     * {@code <part>$<aid>+<cid>+<qn列表>:<描述列表>}（用 {@code #} 连接）。
     *
     * @param ids id 列表，取首个（aid）
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String aid = firstId(ids);
        if (aid.isEmpty()) {
            return emptyResponse();
        }
        ensureSession();
        Log.i(TAG, "detailContent aid=" + aid);
        try {
            JSONObject view = new JSONObject(fetch(VIEW + "?aid=" + aid, apiHeaders()));
            JSONObject data = view.optJSONObject("data");
            if (data == null) {
                return emptyResponse();
            }
            Vod vod = new Vod()
                    .id(aid)
                    .pic(data.optString("pic"))
                    .name(data.optString("title"))
                    .typeName(data.optString("tname"))
                    .content(data.optString("desc"))
                    .remarks((data.optLong("duration", 0) / 60) + "分鐘");
            JSONObject owner = data.optJSONObject("owner");
            if (owner != null) {
                vod.director(owner.optString("name"));
            }

            long cid = data.optLong("cid", 0);
            String playBody = fetch(PLAYURL + "?avid=" + aid + "&cid=" + cid
                    + "&qn=127&fnval=4048&fourk=1", apiHeaders());
            JSONObject playData = new JSONObject(playBody);
            JSONObject playRoot = playData.has("data")
                    ? playData.optJSONObject("data") : playData;
            if (playRoot == null) {
                playRoot = new JSONObject();
            }
            JSONArray qns = playRoot.optJSONArray("accept_quality");
            JSONArray descs = playRoot.optJSONArray("accept_description");
            List<String> keptQn = new ArrayList<>();
            List<String> keptDesc = new ArrayList<>();
            int n = qns == null ? 0 : qns.length();
            for (int i = 0; i < n; i++) {
                int qn = qns.optInt(i);
                if (canPlay(qn)) {
                    keptQn.add(String.valueOf(qn));
                    keptDesc.add(descs == null ? "" : descs.optString(i));
                }
            }
            String qnJoined = TextUtils.join(":", keptQn);
            String descJoined = TextUtils.join(":", keptDesc);

            List<String> parts = new ArrayList<>();
            JSONArray pages = data.optJSONArray("pages");
            if (pages != null) {
                for (int i = 0; i < pages.length(); i++) {
                    JSONObject p = pages.optJSONObject(i);
                    if (p == null) {
                        continue;
                    }
                    parts.add(p.optString("part") + Vod.SEP_NAME_URL + aid + "+"
                            + p.optLong("cid", 0) + "+" + qnJoined + "+" + descJoined);
                }
            }
            LinkedHashMap<String, String> lines = new LinkedHashMap<>();
            lines.put("B站", TextUtils.join(Vod.SEP_EPISODE, parts));
            vod.playFrom(TextUtils.join(Vod.SEP_SOURCE, lines.keySet()));
            vod.playUrl(TextUtils.join(Vod.SEP_SOURCE, lines.values()));
            return VodBuilder.detail(vod).detailContent();
        } catch (Throwable e) {
            Log.e(TAG, "detailContent failed", e);
            return emptyResponse();
        }
    }

    /** @return 是否已登录（门禁 qn&gt;32）—— 与 {@link #isLogin()} 同义 */
    public boolean loggedIn() {
        return isLogin();
    }

    /** @return 是否大会员（门禁 qn&gt;80）—— 与 {@link #isVip()} 同义 */
    public boolean vip() {
        return isVip();
    }

    // ─────────────────────────────────────────────────────────────
    // 播放（还原 playerContent / proxy / MPD）
    // ─────────────────────────────────────────────────────────────

    /**
     * 还原上游 {@code playerContent(String, String, List)}。
     *
     * <p>{@code id} 形如 {@code <aid>+<cid>+<qn1:qn2>+<desc1:desc2>}（detail 阶段拼出）。
     * 输出结构见类注释与 {@code p028c.c} 的实例构建器：
     * {@code {"url":[名称1,地址1,名称2,地址2,...], "header":"<json>", "danmaku":"<xml url>"}}。
     *
     * @param flag     线路名
     * @param id       播放 id
     * @param vipFlags 忽略
     * @return 标准播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) {
            return emptyResponse();
        }
        try {
            String[] parts = id.split("\\+");
            if (parts.length < 4) {
                Log.w(TAG, "playerContent id 结构不符: " + id);
                return emptyResponse();
            }
            String aid = parts[0];
            String cid = parts[1];
            String[] qnList = parts[2].split(":");
            String[] descList = parts[3].split(":");

            Map<String, String> headers = apiHeaders();
            // 心跳上报：上游结果只打日志、不使用（Bili.java:491）
            LinkedHashMap<String, Object> hb = new LinkedHashMap<>();
            hb.put("aid", aid);
            hb.put("cid", cid);
            hb.put("mid", mid);
            hb.put("played_time", "0");
            hb.put("realtime", "0");
            hb.put("type", "3");
            hb.put("refer_url", "https://www.bilibili.com/");
            hb.put("play_type", "3");
            String hbResp = fetchWith(HEARTBEAT, formEncode(hb), headers);
            Log.d(TAG, "HeartbeatResponse " + hbResp);

            JSONArray urlArray = new JSONArray();
            for (int i = 0; i < qnList.length; i++) {
                String desc = i < descList.length ? descList[i] : "";
                urlArray.put(desc);
                urlArray.put(proxyPlayUrl(aid, cid, qnList[i]));
            }
            JSONObject out = new JSONObject();
            out.put("url", urlArray);
            out.put("header", new JSONObject(headers).toString());
            out.put("danmaku", DM_LIST + cid);
            out.put("parse", playerParseFlag);
            return out.toString();
        } catch (Throwable e) {
            Log.e(TAG, "playerContent failed", e);
            return emptyResponse();
        }
    }

    /** 宿主代理基址默认值（宿主 {@code Proxy.getPort()} 的默认端口，见 Proxy 反编译 {@code a = -1} → 运行时探测） */
    public static final String DEFAULT_PROXY = "http://127.0.0.1:9978/proxy";

    /**
     * 代理基址覆盖（宿主注入）。
     *
     * <p><b>static</b>：宿主与测试都按 {@code BiliPlain.setProxyUrl(...)} 静态调用
     * （上游 {@code Proxy.getUrl()} 本身也是宿主级静态）。存储仍只有一份 ——
     * 基类的 {@link BiliBase#proxyBase}；这里只是"未注入时回落到
     * {@link #DEFAULT_PROXY}"这层 Bili 特有的默认值。
     */
    private static String proxyUrlOverride;

    /**
     * 注入代理基址（宿主 / 测试）。
     *
     * @param url 形如 {@code http://127.0.0.1:9978/proxy}；null 恢复默认
     */
    public static void setProxyUrl(String url) {
        proxyUrlOverride = (url == null || url.isEmpty()) ? null : url;
    }

    /**
     * 代理基址。
     *
     * <p>上游调用宿主静态 {@code Proxy.getUrl()}（反编译 {@code ae/decompiled/Proxy.java:41}，
     * 即 {@code getOriginUrl() + "/proxy"}），端口由宿主运行时探测。
     * 为避免与 {@code frame} 域硬耦合，这里用可注入 seam：默认
     * {@link #DEFAULT_PROXY}，宿主接入时 {@link #setProxyUrl(String)}。
     *
     * @return 形如 {@code http://127.0.0.1:9978/proxy}
     */
    public String proxyUrl() {
        return proxyUrlOverride == null ? DEFAULT_PROXY : proxyUrlOverride;
    }

    @Override
    protected String effectiveProxyBase() {
        String injected = proxyUrlOverride;
        if (injected != null && !injected.isEmpty()) {
            return injected;
        }
        return proxyBase().isEmpty() ? DEFAULT_PROXY : proxyBase();
    }

    @Override
    protected String proxyAction() {
        return "bili";
    }

    /**
     * 还原上游 {@code proxy(Map)}：把 playurl 的 DASH 流拼成 MPD。
     *
     * <p>上游把 {@code dash.video[]} 里 {@code id == qn} 的那条、以及
     * {@code dash.audio[]} 里 id 命中音质表的那几条拼成 MPD。
     *
     * @param params 宿主参数（{@code aid} / {@code cid} / {@code qn}）
     * @return {@code {200,"application/dash+xml",stream}}
     */
    public Object[] proxy(Map<String, String> params) {
        Map<String, String> p = params == null ? new LinkedHashMap<String, String>() : params;
        String aid = p.get("aid");
        String cid = p.get("cid");
        String qn = p.get("qn");
        String url = PLAYURL + "?avid=" + aid + "&cid=" + cid + "&qn=" + qn
                + "&fnval=4048&fourk=1";
        String body = fetch(url, apiHeaders());
        String mpd = buildMpd(body, qn);
        return new Object[]{200, "application/dash+xml",
                new ByteArrayInputStream(mpd.getBytes(Charset.forName("UTF-8")))};
    }

    /**
     * 从 playurl 响应生成 MPD。
     *
     * <p>渲染本身已上收到 {@link BiliDash}（Bili 与 BiliYS 共用）；
     * 本方法只保留"Bili 的调用形状 + 失败兜底"。
     *
     * <p>与旧实现的差异（见交付报告"分歧清单"）：
     * <ul>
     *   <li>{@code SegmentBase.Initialization} 是<b>对象</b>（{@code {"range":"0-991"}}），
     *       旧实现用 {@code optString} 读它，会得到 {@code {range=0-991}} 这种垃圾；
     *       {@link BiliDash} 读的是 {@code Initialization.range}。</li>
     *   <li>{@code minBufferTime} 缺省不再发明 {@code "1.5"}，按原值直出。</li>
     *   <li>一条轨都没渲染出来时返回 {@link #emptyMpd()}（与旧实现一致）。</li>
     * </ul>
     *
     * @param playurlJson playurl 响应体
     * @param qn          目标清晰度（字符串，与 {@code dash.video[].id} 比较）
     * @return MPD XML；输入非法时返回最小合法空 MPD
     */
    public String buildMpd(String playurlJson, String qn) {
        try {
            return BiliDash.parse(playurlJson).toMpd(qn, false);
        } catch (Throwable e) {
            Log.e(TAG, "buildMpd failed", e);
            return emptyMpd();
        }
    }

    /** @return 空 MPD（与 {@link BiliDash#emptyMpd()} 同一份字面量） */
    public static String emptyMpd() {
        return BiliDash.emptyMpd();
    }

    // ─────────────────────────────────────────────────────────────
    // 解析工具
    // ─────────────────────────────────────────────────────────────

    /**
     * 解析 B 站搜索 / 热门列表。
     *
     * <p>上游用混淆 bean {@code merge.f.e.a} 的 {@code e()} 转 Vod；本实现按
     * {@code /x/web-interface/wbi/search/type} 与 {@code /x/web-interface/popular}
     * 的真实响应字段还原：{@code aid / title / pic / duration / typename}。
     * {@code title} 含 {@code <em class="keyword">} 高亮标签，需剥掉。
     *
     * @param json 响应体，可为 null
     * @return 影片列表（永不为 null）
     */
    public List<Vod> parseSearchResult(String json) {
        List<Vod> out = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        JSONArray arr = null;
        try {
            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            if (data != null) {
                arr = data.optJSONArray("result");
                if (arr == null) {
                    arr = data.optJSONArray("list");
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "parseSearchResult failed", e);
            return out;
        }
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            out.add(new Vod()
                    .id(String.valueOf(o.optLong("aid", 0)))
                    .name(stripTags(o.optString("title")))
                    .pic(fixPic(o.optString("pic")))
                    .remarks(o.optString("duration"))
                    .typeName(o.optString("typename")));
        }
        return out;
    }

    /**
     * 去掉 B 站搜索高亮标签并还原 HTML 实体。
     *
     * @param s 原文，可为 null
     * @return 干净标题
     */
    public static String stripTags(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("<[^>]*>", "")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'")
                .trim();
    }

    /**
     * 还原协议相对 URL（{@code //i0.hdslb.com/...} → {@code https:} + 原串）。
     *
     * @param pic 图片地址
     * @return 绝对地址
     */
    public static String fixPic(String pic) {
        if (pic == null || pic.isEmpty()) {
            return "";
        }
        return pic.startsWith("//") ? "https:" + pic : pic;
    }

    /**
     * 秒数 → {@code m:ss}（还原上游历史记录里的补零写法）。
     *
     * @param seconds 秒
     * @return 形如 {@code 10:05}
     */
    public static String clock(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    /**
     * {@code {"list":[...]}}（还原上游 {@code merge.p028c.c.n(List)}）。
     *
     * @param vods 影片列表
     * @return JSON
     */
    public static String listJson(List<Vod> vods) {
        return VodBuilder.create().addAll(vods).searchContent();
    }

    /** @return 空列表对象 */
    private static JSONObject emptyList() {
        JSONObject o = new JSONObject();
        try {
            o.put("list", new JSONArray());
        } catch (Throwable ignore) {
            // stub
        }
        return o;
    }

    /** @return {@link #AUDIO_SAMPLE_RATE} 的键（供测试与断言） */
    public static String[] audioQualityIds() {
        return AUDIO_SAMPLE_RATE.keySet().toArray(new String[0]);
    }

    /** @return 下标表长度（应为 64） */
    public static int tabLength() {
        return MIXIN_KEY_ENC_TAB.length;
    }

    /** @return 下标表副本 */
    public static int[] tabCopy() {
        return Arrays.copyOf(MIXIN_KEY_ENC_TAB, MIXIN_KEY_ENC_TAB.length);
    }
}
