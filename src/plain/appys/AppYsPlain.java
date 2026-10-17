package com.example.tvbox.plain.appys;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * AppYs 明文实现（多形态 CMS 套壳，{@code api.php/…} / {@code .vod} / {@code xgapp} 三种基址）。
 *
 * <p>反编译原文: {@code ae/decompiled/AppYs.java}（1291 行）。共享部分见
 * {@link AppYsBase}；本类只实现<b>与 AppYsV2 分叉</b>的部分。
 *
 * <h2>本类独有的三件东西（V2 完全没有）</h2>
 * <ol>
 *   <li>{@link #aliasOf} / {@code s()}：线路名重命名表（11 条，
 *       {@code youku→优酷M}、{@code mgtv→芒果TVM}…）。V2 直接用原线路名。</li>
 *   <li>{@link #parsePrefix} / {@code x()}：把「线路名」映射成解析前缀，
 *       内含 {@code svip.jhyun.jx.cn} / {@code svip.jhdyw.vip} / {@code jhsj.manduhu.com} /
 *       {@code v.jhdyw.vip/nhdz666} / {@code x-n.cc} 五条站点特判。</li>
 *   <li>{@link #buildResolverUrl} {@code o()} + {@link #resolvePlayUrl} {@code m()}：
 *       把「解析前缀 + 播放地址」拼成一个 {@code https://www.baidu.com/s?wd=…} 的
 *       <b>伪搜索地址</b>，再按 20+ 条站点分支把它还原成真的播放地址；其中一段会
 *       连续跟随 {@code Location} 重定向（这是全族唯一用到「手工跟跳」的地方）。</li>
 * </ol>
 *
 * <h2>协议</h2>
 * <pre>
 *   GET  {base}nav?token=                          分类导航（api.php/app|xgapp）
 *   GET  {base}types | /list?type                 分类导航（.vod）
 *   GET  {base}video?tid={tid}&amp;…&amp;pg=#PN#         分类列表
 *   GET  {base}video_detail?id={id}                详情
 *   GET  {base}search?text={wd}&amp;pg=              搜索
 *   GET  {base}index_video?token=                  首页推荐
 *   头:  User-Agent = Dart/2.14|2.15|okhttp/4.1.0|Dalvik/2.1.0（由基址特征决定）
 * </pre>
 *
 * <h2>ext 契约</h2>
 * <p><b>B 类</b>：ext 整串就是基址（上游 {@code this.j = str}，不 trim、不解析 JSON）。
 * 没有 A 类密钥，也没有 C 类兜底域名。
 *
 * <h2>未还原的分支（诚实清单）</h2>
 * <ol>
 *   <li><b>重定向链的多次跳转</b>：上游用 {@code yi.h(treeMap)} 取 {@code Location}，
 *       本实现只复刻到「无 Location 就直接解析响应体 / 有 Location 就跟随」这一层；
 *       连续多跳时是否取最后一次、是否带 cookie，本地没有可核对的报文样本。
 *       相关断言：{@code AppYsTest} 的 [J] 段（无跳转形态）。</li>
 *   <li><b>{@code type_extend} 缺失时的筛选</b>：上游给的是<b>空筛选数组</b>
 *       （{@code w(navUrl, null)}），本实现回落 {@link AppYsBase#FILTERS_FALLBACK}
 *       （静态分类表 + 固定筛选 DSL）。这是<b>有意统一</b>，不是还原 —— 断言钉在
 *       {@code AppYsTest} 的 [D]/[E] 段。</li>
 *   <li><b>伪 baidu 链的 kwarg 穷举</b>：{@code m()} 里只有 {@code =http} / {@code url=}
 *       两类 kwarg 被显式判过，其它 kwarg 形态（{@code ?v=} / {@code ?vid=} /
 *       {@code ?php?id=}）走的是同一套正则，但没有逐形态样本。</li>
 *   <li><b>{@code Uri.getHost()} 的畸形输入</b>：上游对无 scheme / 畸形 authority 会
 *       NPE 并被 catch 成 {@code false}；本实现是「空串 → false」。<b>等价但不逐字</b>。</li>
 * </ol>
 *
 * <h2>发现的上游缺陷（均已修，有断言钉住）</h2>
 * <ol>
 *   <li>{@code m()} 第 144 行判 {@code strH2.contains(...)} 之前没有 null 检查 ——
 *       上游在 {@code while} 里把 {@code strH2} 置成 {@code str5}（初值 {@code ""}），
 *       所以恰好不会 NPE，但语义是「上一跳的 Location」。本实现保持该语义并显式命名
 *       {@code lastHop}，避免读者误以为在判当前 Location。</li>
 *   <li>{@code str3.split("url=")[1]} 出现 4 次、无保护（第 191/197/204/236/242/249 行）——
 *       不含 {@code url=} 时抛 {@code ArrayIndexOutOfBoundsException}，被最外层
 *       catch 吞掉 → {@code playerContent} 返回空串。本实现统一走
 *       {@link AppYsBase#substringAfter}，找不到时退化为整串，并让分支判断保持原义。</li>
 *   <li>{@code x()} 的 {@code str} 参数被当成「可变局部量」反复赋值（先 base 后前缀），
 *       可读性极差。本实现改成两个入参 {@code base}/{@code from}，行为不变。</li>
 * </ol>
 */
public class AppYsPlain extends AppYsBase {

    private static final String TAG = "AppYsPlain";

    /** 上游 {@code s()} 的线路别名表（11 条，保序） */
    public static final Map<String, String> LINE_ALIAS;

    static {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("youku", "优酷M");
        m.put("qq", "腾讯M");
        m.put("iqiyi", "爱奇艺M");
        m.put("qiyi", "奇艺M");
        m.put("letv", "乐视M");
        m.put("sohu", "搜狐M");
        m.put("tudou", "土豆M");
        m.put("pptv", "PPTVM");
        m.put("mgtv", "芒果TVM");
        m.put("wasu", "华数M");
        m.put("bilibili", "哔哩M");
        LINE_ALIAS = java.util.Collections.unmodifiableMap(m);
    }

    /** 上游 {@code x()} 的三个「换接口」触发主机 */
    public static final String[] SVIP_HOSTS = {"svip.jhyun.jx.cn", "svip.jhdyw.vip"};

    /** 上游 {@code x()} 的兜底解析接口（命中最后那个 {@code else if} 反条件时用） */
    public static final String FALLBACK_JX = "https://jx.parwix.com:4433/player/?url=";

    /** 上游 {@code m()} 里 5 处硬编码的「二次解析」接口 */
    public static final String JX_6080KAN = "https://www.6080kan.cc/app.php?url=";
    public static final String JX_M3U8TV = "https://api.m3u8.tv:5678/home/api?type=ys&uid=233711&key=dgilouvFKNRSWX2467&url=";
    public static final String JX_1080KAN_RRMI = "http://www.1080kan.cc/jiexi/rrmi.php?url=";
    public static final String JX_39000 = "http://1.117.152.239:39000/?url=";
    public static final String JX_BANYUNG = "https://jx.banyung.xyz:7799/player/?url=";

    /** 上游 {@code o()} 里 5 条「同款接口」前缀（拼在 baidu wd 参数里） */
    public static final String WD_BAIDU = "https://www.baidu.com/s?wd=";
    public static final String WD_RONGXING = "https://fast.rongxingvr.cn:8866/api/?key=nShWumGdMIbTwngTbI&url=";
    public static final String WD_PYXDDC = "https://f7.pyxddc.com/bcjx/4k.php?url=";
    public static final String WD_RENRENMI = "https://kuba.renrenmi.cc:2266/api/?key=Y6UYLYtjImTCKe98JD&url=";
    public static final String WD_HFyrw = "https://json.hfyrw.com/mao.go?url=";
    public static final String WD_DMTT = "http://cache.dmtt.xyz/xfyjx/xfyjx.php?url=";
    public static final String WD_PARWIX = "https://jx.parwix.com:4433/player/?url=";
    public static final String WD_TKYS = "https://play.tkys.tv/?url=";
    public static final String WD_APP_SUFFIX = "&app=10000&account=272775028&password=qq272775028";

    /** 上游 {@code m()} 里的组合头字面量（逐字照抄，含多余空格与结尾的 {@code }}） */
    public static final String HDR_DUODUOZY = "{\"User-Agent\":\" Mozilla/5.0 (Windows NT 10.0; WOW64)"
            + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/72.0.3626.81 Safari/537.36 SE 2.X MetaSr 1.0\","
            + "\"referer\":\" https://player.duoduozy.com\",\"origin\":\" https://dp.duoduozy.com\","
            + "\"Host\":\" cache.m3u8.suoyo.cc\"}";
    public static final String HDR_XFY = "{\"referer\":\" appguapi.lihaoyun.top:11543\","
            + "\"User-Agent\":\" Dart/2.14 (dart:io)\"}}";
    public static final String HDR_IOPENYUN = "{\"Referer\":\"https://user.iopenyun.com:520/user/video/\"}";
    public static final String HDR_WKFILE = "{\"User-Agent\":\" Lavf/58.12.100\",\"Referer\":\" wkfile.com\"}";
    public static final String HDR_MGTV = "{\"User-Agent\":\" Mozilla/5.0\", \"Referer\":\" \"}";
    public static final String HDR_RRMI = "{\"Referer\":\"http://www.1080kan.cc/\"}";
    public static final String HDR_YSGC = "{\"Referer\":\" https://ysgc.cc\"}";
    public static final String HDR_RUIFENG = "{\"Referer\":\" https://1.ruifenglb.com\"}";

    /** 上游 {@code m()} 的「移动端 UA」 */
    public static final String UA_ANDROID = "Mozilla/5.0 Android";

    /**
     * 构造。
     */
    public AppYsPlain() {
        super();
    }

    /**
     * 基址 = ext 整串（上游 {@code this.j = str}，不 trim、不解析 JSON）。
     *
     * @param ext ext 原文
     * @return 基址
     */
    @Override
    protected String siteFromExt(String ext) {
        return ext == null ? "" : ext;
    }

    // ── 线路名 ──────────────────────────────────────────────

    /**
     * 上游 {@code s()}：线路名别名。
     *
     * <p>上游用 {@code synchronized(b)} 惰性建表；这里改成静态不可变表，语义等价。
     *
     * @param name 原始线路名
     * @return 别名；不在表里返回原名
     */
    @Override
    protected String aliasOf(String name) {
        String n = name == null ? "" : name;
        String v = LINE_ALIAS.get(n);
        return v == null ? n : v;
    }

    /**
     * AppYs 的线路名只认 {@code name}（不认 {@code code}），过一遍别名表。
     *
     * @param player {@code vod_url_with_player} / {@code vod_play_list} 的元素
     * @return 线路名
     */
    @Override
    protected String lineName(JSONObject player) {
        return aliasOf(player.optString("name"));
    }

    // ── 播放源收集（分叉点）──────────────────────────────────

    /**
     * {@code xgapp} 型：{@code data.vod_info.vod_url_with_player[]}。
     *
     * <p>与 V2 的差异：线路名走别名表；{@code parse_api} 含
     * {@code jpg.hou.lu/jm/za/index.php} 时整串换成固定的 mengx 接口；只保留 1 个前缀。
     *
     * @param vodInfo {@code vod_info}
     * @param froms   线路名收集器
     * @param urls    线路地址收集器
     */
    @Override
    protected void sourcesFromXgapp(JSONObject vodInfo, List<String> froms, List<String> urls) {
        JSONArray players = vodInfo.optJSONArray("vod_url_with_player");
        for (int i = 0; players != null && i < players.length(); i++) {
            JSONObject one = players.optJSONObject(i);
            if (one == null) {
                continue;
            }
            String from = aliasOf(one.optString("name"));
            froms.add(from);
            urls.add(one.optString("url"));
            addParser(from, rewriteParseApi(one.optString("parse_api")));
        }
    }

    /**
     * {@code .vod} 型：{@code data.vod_play_list[]}，每条线路的解析前缀从
     * {@code player_info.parse}/{@code parse2} 里筛出来。
     *
     * <p>筛选规则（逐字复刻上游 {@code t()}）：
     * <ol>
     *   <li>先按 {@link #P_QUERY} 判断「像带参接口」；</li>
     *   <li>再被 {@link #P_JX} 的 21 条黑名单命中就丢掉；</li>
     *   <li>{@code parse} 与 {@code parse2} 依次追加；</li>
     *   <li>取首个：没有或不含 {@code =} → {@link #JX_39000}；
     *       含 {@code http} → 用 {@link #P_URL_KEY} 截出前缀并做 {@code ..→.} 与
     *       {@code vip.aotian.love→vip.gaotian.love} 改写；
     *       含 {@code //} → 补 {@code http:}；
     *       否则用 {@link #P_HOST} + {@link #P_URL_KEY} 在基址上拼一个。</li>
     * </ol>
     *
     * @param data  {@code data}
     * @param froms 线路名收集器
     * @param urls  线路地址收集器
     */
    @Override
    protected void sourcesFromVod(JSONObject data, List<String> froms, List<String> urls) {
        JSONArray playList = data.optJSONArray("vod_play_list");
        for (int i = 0; playList != null && i < playList.length(); i++) {
            JSONObject one = playList.optJSONObject(i);
            if (one == null) {
                continue;
            }
            JSONObject info = one.optJSONObject("player_info");
            String from = aliasOf(info == null ? "" : info.optString("show"));
            froms.add(from);
            urls.add(one.optString("url"));
            if (info == null) {
                continue;
            }
            List<String> candidates = new ArrayList<>();
            collectCandidates(info.optString("parse"), candidates);
            collectCandidates(info.optString("parse2"), candidates);
            addParser(from, pickParser(candidates, site));
        }
    }

    /**
     * 从 {@code parse}/{@code parse2} 逗号串里挑出「像接口」且不在黑名单里的项。
     *
     * @param csv    逗号分隔串
     * @param out    收集器
     */
    protected static void collectCandidates(String csv, List<String> out) {
        if (csv == null || csv.isEmpty()) {
            return;
        }
        for (String raw : csv.split(",")) {
            String s = raw == null ? "" : raw.trim();
            if (s.isEmpty() || !P_QUERY.matcher(s).find()) {
                continue;
            }
            boolean blacklisted = false;
            for (java.util.regex.Pattern p : P_JX) {
                if (p.matcher(s).find()) {
                    blacklisted = true;
                    break;
                }
            }
            if (!blacklisted) {
                out.add(s);
            }
        }
    }

    /**
     * 取首个候选并按 3 条站点特判改写（上游 {@code t()} 的 1054-1107 行）。
     *
     * @param candidates 候选前缀
     * @param base       基址
     * @return 解析前缀；无候选或都不合格返回 {@link #JX_39000}
     */
    public static String pickParser(List<String> candidates, String base) {
        if (candidates.isEmpty() || !candidates.get(0).contains("=")) {
            return JX_39000;
        }
        String s = candidates.get(0);
        try {
            if (s.contains("http")) {
                Matcher m = P_URL_KEY.matcher(s);
                if (m.find()) {
                    s = m.group(0).replace("..", ".").replace("vip.aotian.love", "vip.gaotian.love");
                }
            } else if (s.contains("//")) {
                Matcher m = P_URL_KEY.matcher(s);
                if (m.find()) {
                    s = "http:" + m.group(0).replace("..", ".");
                }
            } else {
                Matcher host = P_HOST.matcher(base == null ? "" : base);
                if (host.find()) {
                    Matcher key = P_URL_KEY.matcher(base == null ? "" : base);
                    if (key.find()) {
                        s = host.group(0) + key.group(0).replace("..", ".");
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "pickParser 改写失败", e);
        }
        return s;
    }

    /**
     * 尾部（{@code data.vod_url_with_player}）解析接口改写：与 {@code xgapp} 分支同一张替换表。
     *
     * @param api 原 {@code parse_api}
     * @return 改写后的值
     */
    @Override
    protected String rewriteTailApi(String api) {
        return rewriteParseApi(api);
    }

    /**
     * 上游 {@code t()} 里那段 {@code parse_api} → mengx 接口的替换。
     *
     * @param api 原值
     * @return 替换后的值
     */
    protected static String rewriteParseApi(String api) {
        String a = api == null ? "" : api;
        return a.contains(AppYsVideo.PARSE_API_PLACEHOLDER) ? AppYsVideo.PARSE_API_REPLACEMENT : a;
    }

    // ── 播放解析（分叉点：整段 AppYs 独有）────────────────────

    /**
     * {@code playerContent}：线路 → 解析前缀 → 伪 baidu 地址 → 真实播放地址。
     *
     * @param flag     线路名
     * @param id       播放 id（真实播放地址）
     * @param vipFlags 忽略（上游也忽略）
     * @return 播放 JSON（已挂弹幕）；异常返回 {@code ""}
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String prefix = parsePrefix(site, flag);
            String wdUrl = buildResolverUrl(site, prefix, id);
            JSONObject out = new JSONObject();
            resolvePlayUrl(wdUrl, out);
            return addDanmaku(out.toString());
        } catch (Throwable e) {
            Log.e(tag(), "playerContent failed", e);
            return "";
        }
    }

    /**
     * 上游 {@code x()}：线路名 → 解析前缀。
     *
     * <p>上游先按基址形态决定要不要查表（{@code api.php/….vod} 与
     * {@code api.php/app/|xgapp} 查表，其余形态原样透传基址），再按 5 条站点特判改写。
     *
     * @param base 基址
     * @param from 线路名
     * @return 解析前缀
     */
    public String parsePrefix(String base, String from) {
        String b = base == null ? "" : base;
        String f = from == null ? "" : from;
        String prefix = b;
        if (isVodExtForm(b) || b.contains("api.php/app/") || b.contains("xgapp")) {
            prefix = firstParser(f);
        }
        if (containsAny(prefix, SVIP_HOSTS)) {
            String override = svipOverride(f);
            if (override != null) {
                return override;
            }
        } else if (!prefix.contains("jhsj.manduhu.com")
                && !prefix.contains("v.jhdyw.vip/nhdz666")
                && !prefix.contains("x-n.cc")) {
            return prefix;
        }
        return FALLBACK_JX;
    }

    /**
     * 上游 {@code o()}：把「基址 + 解析前缀 + 播放地址」拼成伪 baidu 搜索地址。
     *
     * @param base   基址
     * @param prefix 解析前缀（{@link #parsePrefix} 的结果）
     * @param playId 播放地址
     * @return 伪 baidu 地址；上游所有分支都以 {@link #WD_BAIDU} 开头
     */
    public String buildResolverUrl(String base, String prefix, String playId) {
        String b = base == null ? "" : base;
        String p = prefix == null ? "" : prefix;
        String id = playId == null ? "" : playId;
        if (b.contains("xgapp") || b.contains("api.php/app/") || b.contains(".vod")) {
            String direct = directOrWrapped(id);
            if (direct != null) {
                return direct;
            }
            if (!p.contains("url=") && id.contains("RongXingVR")) {
                return WD_BAIDU + WD_RONGXING + id;
            }
            if (!p.contains("url=") && id.contains("LT")) {
                return WD_BAIDU + WD_PYXDDC + id;
            }
            if (!p.contains("url=") && id.contains("renrenmi")) {
                return WD_BAIDU + WD_RENRENMI + id;
            }
            if (!p.contains("url=") && id.contains(".html")) {
                return WD_BAIDU + JX_39000 + id;
            }
            if (id.contains("xfy")) {
                return WD_BAIDU + "http://jiexi.yunl.cc/api/?key=xYNESYSvHp1DV2ckKs&url=" + id;
            }
            return WD_BAIDU + p + id;
        }
        // 上游 o() 这里的守卫是 c = api\.php/.*?/vod（不是 e = /.+\?.+=）。
        // 用错模式会让「api.php/…/vod 型基址」在 tkys/legacy 等分支前就被早退。
        if (!P_VOD_SEG.matcher(b).find()) {
            return WD_BAIDU + id;
        }
        String direct = directOrWrapped(id);
        if (direct != null) {
            return direct;
        }
        if (id.contains("xfy")) {
            if (!id.contains("url=")) {
                return WD_BAIDU + WD_HFyrw + id;
            }
            return WD_BAIDU + WD_DMTT + substringAfter(id, "url=");
        }
        if (id.contains("www.bilibili.com")) {
            return WD_BAIDU + WD_PARWIX + substringAfter(id, "url=");
        }
        if (isLegacyGroup(b)) {
            return WD_BAIDU + id + WD_APP_SUFFIX;
        }
        if (!isTkysGroup(b)) {
            return WD_BAIDU + id;
        }
        return WD_BAIDU + WD_TKYS + substringAfter(id, "url=");
    }

    /**
     * {@code .m3u8/.mp4/obj/tos} 三个「看着就是直链」的早退分支（上游两处重复的判断）。
     *
     * @param id 播放地址
     * @return 已确定的伪地址；不命中返回 null
     */
    private static String directOrWrapped(String id) {
        if (id.indexOf(".m3u8") > 15 || id.indexOf(".mp4") > 15 || id.contains("/obj/tos")) {
            if (!id.contains("url=")) {
                return WD_BAIDU + id;
            }
            return WD_BAIDU + substringAfter(id, "url=");
        }
        return null;
    }

    /**
     * 上游 {@code m()}：把伪 baidu 地址还原成播放对象（写进 {@code out}）。
     *
     * <p>结构（逐字复刻）：
     * <ol>
     *   <li>不含 {@code baidu.com} → 直接 {@code parse=1} + 原样 url；</li>
     *   <li>取 {@code wd=} 之后的部分；</li>
     *   <li>6 个站点特判（duoduozy/suoyo、xfy、iopenyun:88、wkfile）；</li>
     *   <li>{@code contains("=") || .m3u8 出现在 15 位内} 且 {@code .mp4 出现在 15 位内}
     *       且不含 {@code /obj/tos} → 请求一次看看：
     *       有 {@code Location} 就手工跟跳直到 {@code .mp4} 出现在 30 位之后；
     *       HTML 里有播放器特征 → 换解析接口；否则取响应里的 {@code url}；</li>
     *   <li>兜底：{@code hsl.ysgc.xyz} / {@code 1.ruifenglb.com} 两条站点特判；
     *       最后用 {@code .*(http.*)} 从脏文本里捞地址。</li>
     * </ol>
     *
     * @param wdUrl 伪 baidu 地址
     * @param out   输出对象
     */
    public void resolvePlayUrl(String wdUrl, JSONObject out) {
        try {
            if (wdUrl == null || !wdUrl.contains("baidu.com")) {
                out.put("parse", 1);
                out.put("playUrl", "");
                out.put("url", wdUrl == null ? "" : wdUrl);
                return;
            }
            String id = substringAfter(wdUrl, "wd=");
            if (id.contains("duoduozy.com") || id.contains("suoyo.cc")) {
                JSONObject resp = json(fetch(JX_6080KAN + id));
                out.put("parse", 0);
                out.put("playUrl", "");
                out.put("url", resp.optString("url"));
                out.put("header", HDR_DUODUOZY);
                return;
            }
            if (id.contains("xfy")) {
                JSONObject resp = json(fetch(id));
                out.put("parse", 0);
                out.put("playUrl", "");
                out.put("url", resp.optString("url"));
                out.put("header", HDR_XFY);
                return;
            }
            if (id.contains("api.iopenyun.com:88")) {
                if (!id.contains("html")) {
                    out.put("parse", 1);
                    out.put("playUrl", "");
                    out.put("url", id);
                    out.put("ua", "Mozilla/5.0 Windows10");
                    out.put("header", HDR_IOPENYUN);
                    return;
                }
                JSONObject resp = json(fetch(JX_M3U8TV + substringAfter(id, "=")));
                out.put("parse", 0);
                out.put("playUrl", "");
                out.put("url", resp.optString("url"));
                return;
            }
            if (id.contains("cat.wkfile.com")) {
                out.put("parse", 0);
                out.put("playUrl", "");
                out.put("url", id);
                out.put("header", HDR_WKFILE);
                return;
            }
            if (id.contains("=") || id.indexOf(".m3u8") <= 15) {
                if (id.indexOf(".mp4") <= 15 && !id.contains("/obj/tos")) {
                    if (!id.contains("=")) {
                        out.put("parse", 0);
                        out.put("playUrl", "");
                        out.put("url", id);
                        return;
                    }
                    if (resolveViaRedirects(id, out)) {
                        return;
                    }
                }
            }
            if (id.contains("hsl.ysgc.xyz")) {
                JSONObject resp = json(fetch("https://jx.ysgc.xyz/?url=" + id));
                out.put("parse", 0);
                out.put("playUrl", "");
                out.put("url", resp.optString("url"));
                out.put("header", HDR_YSGC);
                return;
            }
            if (id.contains("1.ruifenglb.com")) {
                out.put("parse", 0);
                out.put("playUrl", "");
                out.put("url", id);
                out.put("header", HDR_RUIFENG);
                return;
            }
            Matcher m = P_FIRST_HTTP.matcher(id);
            out.put("parse", 0);
            out.put("playUrl", "");
            out.put("url", m.find() ? m.group(1) : id);
        } catch (Throwable e) {
            Log.e(TAG, "resolvePlayUrl failed", e);
        }
    }

    /**
     * 「先请求一次、必要时手工跟跳」那一段（上游 120-254 行）。
     *
     * <p>跟跳循环逐字复刻：只要拿到的 {@code Location} 里 {@code .mp4} 出现在 30 位之前，
     * 就继续请求，并把「上一跳的 Location」记成 {@code lastHop}；
     * 循环结束时用 {@code lastHop} 做后续判断（上游这里读的是 {@code strH2}，
     * 而它在退出时已被赋成 {@code str5}）。
     *
     * @param id  播放地址
     * @param out 输出对象
     * @return true 表示已经写好了 {@code out}（上游所有子分支都 return）
     */
    private boolean resolveViaRedirects(String id, JSONObject out) throws Exception {
        Map<String, String> headers = mapOf("User-Agent", UA_ANDROID);
        String body = fetchHop(id, headers);
        String location = lastLocation();
        if (location != null && !location.isEmpty()) {
            String lastHop = "";
            String current = location;
            String previousBody = body;
            while (true) {
                String hopBody = previousBody;
                if (current == null || current.isEmpty()) {
                    current = lastHop;
                    break;
                }
                if (current.indexOf(".mp4") > 30) {
                    break;
                }
                hopBody = fetchHop(current, headers);
                lastHop = current;
                previousBody = hopBody;
                current = lastLocation();
            }
            if (!current.contains("=http") && !current.contains("url=")) {
                out.put("parse", 0);
                out.put("playUrl", "");
                out.put("url", current);
                if (id.contains("www.mgtv.com")) {
                    out.put("header", HDR_MGTV);
                }
                return true;
            }
            if (previousBody.contains("<html")) {
                out.put("parse", 1);
                out.put("playUrl", "");
                out.put("url", current);
                return true;
            }
            JSONObject resp = json(previousBody);
            out.put("parse", 0);
            out.put("playUrl", "");
            out.put("url", resp.optString("url"));
            if (current.contains("mgtv.com")) {
                out.put("header", HDR_MGTV);
            }
            return true;
        }
        if (body.contains("<html")) {
            for (java.util.regex.Pattern p : P_HTML) {
                if (p.matcher(body).find()) {
                    out.put("parse", 1);
                    out.put("playUrl", "");
                    out.put("url", id);
                    return true;
                }
            }
            String tail = substringAfter(id, "url=");
            if (tail.contains("http")) {
                out.put("parse", 1);
                out.put("playUrl", "");
                out.put("url", JX_39000 + tail);
                return true;
            }
            if (tail.contains("renrenmi")) {
                out.put("parse", 1);
                out.put("playUrl", "");
                out.put("url", JX_1080KAN_RRMI + tail);
                out.put("header", HDR_RRMI);
                return true;
            }
            out.put("parse", 1);
            out.put("playUrl", "");
            out.put("url", JX_BANYUNG + tail);
            return true;
        }
        String inner = json(body).optString("url");
        if (inner.length() > 1) {
            out.put("parse", 0);
            out.put("playUrl", "");
            out.put("url", inner);
            if (id.contains("mgtv.com")) {
                out.put("header", HDR_MGTV);
            }
            return true;
        }
        String tail = substringAfter(id, "url=");
        if (tail.contains("http")) {
            out.put("parse", 1);
            out.put("playUrl", "");
            out.put("url", JX_39000 + tail);
            return true;
        }
        if (tail.contains("renrenmi")) {
            out.put("parse", 1);
            out.put("playUrl", "");
            out.put("url", JX_1080KAN_RRMI + tail);
            out.put("header", HDR_RRMI);
            return true;
        }
        out.put("parse", 1);
        out.put("playUrl", "");
        out.put("url", JX_BANYUNG + tail);
        return true;
    }

    /** 任一关键字命中 */
    private static boolean containsAny(String text, String[] keys) {
        if (text == null) {
            return false;
        }
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * JSON 容错解析（上游直接用 {@code new JSONObject(text)}，非 JSON 时抛异常被吞）。
     *
     * @param text 响应体
     * @return 对象；非法返回空对象
     */
    static JSONObject json(String text) {
        try {
            return new JSONObject(text == null ? "" : text);
        } catch (Throwable e) {
            return new JSONObject();
        }
    }
}
