package com.example.tvbox.plain.douban;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.JsonKeys;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.frame.ConfigPlain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 豆瓣（Douban）—— 走<b>微信小程序</b> Referer 的 frodo API 内容源。
 *
 * <h2>为什么 Referer 是"微信小程序"</h2>
 * frodo 是豆瓣给客户端/小程序用的聚合接口，<b>对匿名服务端请求有反爬</b>：
 * 缺少合法 Referer/UA 时会被风控挡下（返回空或风控页）。
 * 上游因此刻意伪装成"微信小程序内嵌页"：
 * <pre>
 *   Host      frodo.douban.com
 *   Referer   ^^^^ 见 {@link #WECHAT_REFERER}（servicewechat.com/wx2f9b06c1de1ccfca/84/page-frame.html）
 *   User-Agent 见 {@link #WECHAT_UA}（… MicroMessenger/7.0.9.501 NetType/WIFI
 *               MiniProgramEnv/Windows WindowsWechat）
 * </pre>
 * 这两个值是<b>绕开反爬的关键</b>，必须原样保留 —— 换成普通浏览器 UA 会直接失效。
 *
 * <h2>图片地址的宿主专用格式（本类独立实现并单独测）</h2>
 * 豆瓣图床校验 Referer，直接给原图 URL 大概率 403。上游把图片地址拼成
 * <pre>
 *   {图片URL}@Referer=https://api.douban.com/@User-Agent={桌面 Chrome UA}
 * </pre>
 * 即"URL + 两个宿主指令"，宿主播放器据此在拉图时补上伪造的 Referer/UA。
 * 解析规则见 {@link #picUrl(String)} / {@link #picReferer(String)} /
 * {@link #picUserAgent(String)}，拼接见 {@link #doubanPic(String)}。
 *
 * <h2>vod_id 约定</h2>
 * 所有条目的 id 都是 {@code msearch:<豆瓣id>} —— {@code msearch:} 是宿主约定的
 * "站内搜索"前缀，点击后回落到宿主的搜索流程，因此本类<b>不实现 detailContent</b>。
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>{@code map} 未判空</b>：{@code processHotGaia}/{@code processTvHot}/{@code processShowHot}/
 *       {@code processMovieRecommend}/{@code processRankList*} 都直接 {@code map.get("sort")}，
 *       宿主传 null 即 NPE 冒泡（只有 {@code processAnimeHot} 判了 null）。
 *       已统一按"空表"处理；钉住断言：{@code DoubanTest#extendNullSafe()}。</li>
 *   <li><b>页码解析未容错</b>：{@code Integer.parseInt(pg)} 遇到空串/非数字直接 NFE
 *       —— 宿主首页首次进入常传空页码。已用 {@code pageNum(pg,1)} 兜底；
 *       钉住断言：{@code DoubanTest#pageBoundaries()}。</li>
 *   <li><b>homeContent 配置缺失即整体崩</b>：上游读 {@code /config.json} 的
 *       {@code homePage} 键时没有 try/catch，文件缺失/损坏时 {@code JSONObject}
 *       构造抛异常并冒泡到宿主（首页白屏）。已改为容错读取（读不到=不过滤分类）。</li>
 *   <li><b>homeContent 的推荐列表失败即白屏</b>：上游把"实时热门"请求的结果直接
 *       {@code new JSONObject(...)}，网络失败（空串）时抛异常冒泡。已改为"拿不到就空列表"。</li>
 *   <li><b>{@code filterItemsWithoutPic} 就地改写入参对象</b>（上游语义，非缺陷但易踩）：
 *       它把过滤后的数组 {@code put} 回同一个 {@link JSONObject}，调用方手里的对象会被改掉。
 *       本重写<b>保留</b>该副作用并提供 2 参重载以便断言；见
 *       {@link #filterItemsWithoutPic(JSONObject)}。</li>
 *   <li><b>{@code getJSONObject(i)} 对非对象元素抛异常</b>：上游解析 items 时若数组里混入
 *       非对象元素会整体失败。已改为跳过非对象元素。</li>
 *   <li><b>{@code recommendations} 的 apikey 大小写不一致</b>：搜索/推荐用 {@code apikey}，
 *       而 {@code /recommendations} 端点用 {@code apiKey}（大写 K）。这是上游原文，
 *       保持原样并注释，勿"顺手统一"。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Douban.java}<br>
 * 上游响应构造器 {@code merge.p028c.c}（字段名 {@code page/pagecount/limit/total/list}）与
 * {@code merge.p028c.e}（{@code vod_id/vod_name/vod_pic/vod_remarks/action}）来自 DEX
 * {@code @SerializedName} 注解反汇编。
 
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>真实网络失败形态</b>：所有 fetch 都通过可覆写的 {@code fetch(url,headers)}；
 *       测试里注入固定响应。上游对"HTTP 200 但业务 code 非 200""限流 403""验证码页"
 *       没有分支，本类同样只认 JSON 字段，不猜服务端语义。</li>
 *   <li><b>微信小程序 Referer 的失效</b>：{@code servicewechat.com/wx2f9b06c1de1ccfca/84}
 *       是硬编码版本号；小程序升级到别的 build 后上游站会拒，本类保留原文并注释
 *       （改成自适应会引入未验证的行为）。</li>
 *   <li><b>图片字段的 {@code @Referer=…@User-Agent=…} 后缀</b>：本类能构造与解析，
 *       但"哪些 CDN 认这个后缀"未验证 —— 后缀原样透传给宿主，不做二次判断。</li>
 *   <li><b>历史记录存储</b>：{@code searchHistory()} 从宿主配置读
 *       {@code historyvodname}；本重写经 {@code ConfigPlain.host()} 拿配置，
 *       纯 JVM 下没有配置宿主时返回空历史（不是缺口，是依赖注入）。</li>
 *   <li><b>未还原</b>：9 个分类的 id→name 表按宿主类原文固化；
 *       {@code FILTERS_JSON} 与上游逐字节一致（5621 字符，已程序化核对）。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/Douban.java}（{@code init} 的 UA/Referer、
 * 9 个分类、{@code FILTERS_JSON} 原文、9 个 processor 的字段映射、
 * {@code filterItemsWithoutPic} 的原地修改、以及 {@code apiKey} 大小写不一致处）。
*/
public class DoubanPlain extends BaseSpider {

    private static final String TAG = "DoubanPlain";

    // ── 协议常量（上游硬编码） ───────────────────────────────

    /** frodo 的公开 apiKey（上游硬编码，出现在每个请求里） */
    public static final String API_KEY = "0ac44ae016490db2204ce0a042db2916";

    /** 追加在主站 API 后面的 key 串（上游 {@code this.a}） */
    public static final String QUERY_SUFFIX = "?apikey=" + API_KEY;

    /** frodo 主机 */
    public static final String HOST = "frodo.douban.com";

    /** 站点根 */
    public static final String BASE = "https://frodo.douban.com";

    /**
     * <b>微信小程序内嵌页 Referer</b> —— 反爬绕过用，必须原样保留
     * （换成普通网页 Referer 会被风控挡下）。
     */
    public static final String WECHAT_REFERER =
            "https://servicewechat.com/wx2f9b06c1de1ccfca/84/page-frame.html";

    /**
     * <b>微信小程序 UA</b>（MicroMessenger + MiniProgramEnv/Windows）—— 与 Referer 配套，
     * 上游原样保留。
     */
    public static final String WECHAT_UA =
            "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/53.0.2785.143 Safari/537.36 MicroMessenger/7.0.9.501 "
                    + "NetType/WIFI MiniProgramEnv/Windows WindowsWechat";

    /** 图片地址里注入的 Referer（豆瓣图床校验它） */
    public static final String PIC_REFERER = "https://api.douban.com/";

    /** 图片地址里注入的桌面 UA */
    public static final String PIC_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    /** 图片地址后缀（宿主指令）：{@code @Referer=…@User-Agent=…} */
    public static final String PIC_SUFFIX = "@Referer=" + PIC_REFERER + "@User-Agent=" + PIC_UA;

    /** 每页条数（上游所有接口都是 20） */
    public static final int PAGE_SIZE = 20;

    /** 分类页 id（顺序即首页顺序） */
    public static final String[] CATEGORY_IDS = {
            "guess_you_like", "hot_gaia", "tv_hot", "anime_hot", "show_hot",
            "movie", "tv", "rank_list_movie", "rank_list_tv",
    };

    /** 分类页显示名（与 {@link #CATEGORY_IDS} 一一对应） */
    public static final String[] CATEGORY_NAMES = {
            "猜你喜欢", "热门电影", "热播剧集", "热门动漫", "热播综艺",
            "电影筛选", "电视筛选", "电影榜单", "电视剧榜单",
    };

    /** 榜单默认值（上游分别硬编码） */
    public static final String RANK_MOVIE_DEFAULT = "movie_real_time_hotest";
    public static final String RANK_TV_DEFAULT = "tv_real_time_hotest";
    /** 首页推荐集合（上游硬编码） */
    public static final String HOME_COLLECTION = "subject_real_time_hotest";
    /** 搜索历史偏好键（上游 {@code l.b("historyvodname")}） */
    public static final String HISTORY_KEY = "historyvodname";

    /** 清洗搜索词用的正则（上游原文，含全角括号分支） */
    public static final String CLEAN_KEY_REGEX =
            "\\{[^{}]*\\}|\\[[^\\[\\]]*\\]|\\([^()]*\\)|（[^（）]*）";

    /** 上游 homeContent 的 filters 字面量（逐字节保留） */
    public static final String FILTERS_JSON =
            "{\"hot_gaia\":[{\"key\":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"热度\",\"v\":\"recommend\""
            + "},{\"n\":\"最新\",\"v\":\"time\"},{\"n\":\"评分\",\"v\":\"rank\"}]},{\"key\":\"area\",\"name\":\""
            + "地区\",\"value\":[{\"n\":\"全部\",\"v\":\"全部\"},{\"n\":\"华语\",\"v\":\"华语\"},{\"n\":\"欧美\",\"v\":"
            + "\"欧美\"},{\"n\":\"韩国\",\"v\":\"韩国\"},{\"n\":\"日本\",\"v\":\"日本\"}]}],\"tv_hot\":[{\"key\":\"ty"
            + "pe\",\"name\":\"分类\",\"value\":[{\"n\":\"综合\",\"v\":\"tv_hot\"},{\"n\":\"国产剧\",\"v\":\"tv_do"
            + "mestic\"},{\"n\":\"欧美剧\",\"v\":\"tv_american\"},{\"n\":\"日剧\",\"v\":\"tv_japanese\"},{\"n\":"
            + "\"韩剧\",\"v\":\"tv_korean\"},{\"n\":\"动画\",\"v\":\"tv_animation\"}]}],\"anime_hot\":[{\"key\""
            + ":\"类型\",\"name\":\"类型\",\"value\":[{\"n\":\"全部\",\"v\":\"\"},{\"n\":\"热血\",\"v\":\"热血\"},{\""
            + "n\":\"搞笑\",\"v\":\"搞笑\"},{\"n\":\"恋爱\",\"v\":\"恋爱\"},{\"n\":\"校园\",\"v\":\"校园\"},{\"n\":\"科幻"
            + "\",\"v\":\"科幻\"},{\"n\":\"奇幻\",\"v\":\"奇幻\"},{\"n\":\"悬疑\",\"v\":\"悬疑\"},{\"n\":\"治愈\",\"v\""
            + ":\"治愈\"},{\"n\":\"运动\",\"v\":\"运动\"},{\"n\":\"机甲\",\"v\":\"机甲\"},{\"n\":\"少女\",\"v\":\"少女\"}"
            + ",{\"n\":\"少年\",\"v\":\"少年\"}]},{\"key\":\"地区\",\"name\":\"地区\",\"value\":[{\"n\":\"全部\",\"v\""
            + ":\"\"},{\"n\":\"日本\",\"v\":\"日本\"},{\"n\":\"中国大陆\",\"v\":\"中国大陆\"},{\"n\":\"美国\",\"v\":\"美国\""
            + "},{\"n\":\"韩国\",\"v\":\"韩国\"},{\"n\":\"英国\",\"v\":\"英国\"},{\"n\":\"法国\",\"v\":\"法国\"}]},{\"k"
            + "ey\":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"近期热度\",\"v\":\"T\"},{\"n\":\"首播时间\",\"v\":"
            + "\"R\"},{\"n\":\"高分优先\",\"v\":\"S\"}]},{\"key\":\"年代\",\"name\":\"年代\",\"value\":[{\"n\":\"全部"
            + "\",\"v\":\"\"},{\"n\":\"2026\",\"v\":\"2026\"},{\"n\":\"2025\",\"v\":\"2025\"},{\"n\":\"2024"
            + "\",\"v\":\"2024\"},{\"n\":\"2023\",\"v\":\"2023\"},{\"n\":\"2022\",\"v\":\"2022\"},{\"n\":\""
            + "2021\",\"v\":\"2021\"},{\"n\":\"2020\",\"v\":\"2020\"},{\"n\":\"2019\",\"v\":\"2019\"},{\"n\""
            + ":\"2010年代\",\"v\":\"2010年代\"},{\"n\":\"2000年代\",\"v\":\"2000年代\"},{\"n\":\"90年代\",\"v\":\"90"
            + "年代\"},{\"n\":\"更早\",\"v\":\"更早\"}]}],\"show_hot\":[{\"key\":\"type\",\"name\":\"分类\",\"value"
            + "\":[{\"n\":\"综合\",\"v\":\"show_hot\"},{\"n\":\"国内\",\"v\":\"show_domestic\"},{\"n\":\"国外\",\""
            + "v\":\"show_foreign\"}]}],\"movie\":[{\"key\":\"类型\",\"name\":\"类型\",\"value\":[{\"n\":\"全部类型"
            + "\",\"v\":\"\"},{\"n\":\"喜剧\",\"v\":\"喜剧\"},{\"n\":\"爱情\",\"v\":\"爱情\"},{\"n\":\"动作\",\"v\":\""
            + "动作\"},{\"n\":\"科幻\",\"v\":\"科幻\"},{\"n\":\"动画\",\"v\":\"动画\"},{\"n\":\"悬疑\",\"v\":\"悬疑\"},{\""
            + "n\":\"犯罪\",\"v\":\"犯罪\"},{\"n\":\"惊悚\",\"v\":\"惊悚\"},{\"n\":\"冒险\",\"v\":\"冒险\"},{\"n\":\"音乐"
            + "\",\"v\":\"音乐\"},{\"n\":\"历史\",\"v\":\"历史\"},{\"n\":\"奇幻\",\"v\":\"奇幻\"},{\"n\":\"恐怖\",\"v\""
            + ":\"恐怖\"},{\"n\":\"战争\",\"v\":\"战争\"},{\"n\":\"传记\",\"v\":\"传记\"},{\"n\":\"歌舞\",\"v\":\"歌舞\"}"
            + ",{\"n\":\"武侠\",\"v\":\"武侠\"},{\"n\":\"情色\",\"v\":\"情色\"},{\"n\":\"灾难\",\"v\":\"灾难\"},{\"n\":"
            + "\"西部\",\"v\":\"西部\"},{\"n\":\"纪录片\",\"v\":\"纪录片\"},{\"n\":\"短片\",\"v\":\"短片\"}]},{\"key\":\""
            + "地区\",\"name\":\"地区\",\"value\":[{\"n\":\"全部地区\",\"v\":\"\"},{\"n\":\"华语\",\"v\":\"华语\"},{\"n"
            + "\":\"欧美\",\"v\":\"欧美\"},{\"n\":\"中国\",\"v\":\"中国\"},{\"n\":\"美国\",\"v\":\"美国\"},{\"n\":\"中国香"
            + "港\",\"v\":\"中国香港\"},{\"n\":\"中国台湾\",\"v\":\"中国台湾\"},{\"n\":\"韩国\",\"v\":\"韩国\"},{\"n\":\"日本\""
            + ",\"v\":\"日本\"},{\"n\":\"英国\",\"v\":\"英国\"},{\"n\":\"法国\",\"v\":\"法国\"},{\"n\":\"菲律宾\",\"v\":"
            + "\"菲律宾\"},{\"n\":\"德国\",\"v\":\"德国\"},{\"n\":\"意大利\",\"v\":\"意大利\"},{\"n\":\"西班牙\",\"v\":\"西班"
            + "牙\"},{\"n\":\"印度\",\"v\":\"印度\"},{\"n\":\"泰国\",\"v\":\"泰国\"},{\"n\":\"俄罗斯\",\"v\":\"俄罗斯\"},{"
            + "\"n\":\"加拿大\",\"v\":\"加拿大\"},{\"n\":\"澳大利亚\",\"v\":\"澳大利亚\"},{\"n\":\"爱尔兰\",\"v\":\"爱尔兰\"},{"
            + "\"n\":\"瑞典\",\"v\":\"瑞典\"},{\"n\":\"巴西\",\"v\":\"巴西\"},{\"n\":\"丹麦\",\"v\":\"丹麦\"}]},{\"key\""
            + ":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"近期热度\",\"v\":\"T\"},{\"n\":\"首映时间\",\"v\":\"R\""
            + "},{\"n\":\"高分优先\",\"v\":\"S\"}]},{\"key\":\"年代\",\"name\":\"年代\",\"value\":[{\"n\":\"全部年代\","
            + "\"v\":\"\"},{\"n\":\"2026\",\"v\":\"2026\"},{\"n\":\"2025\",\"v\":\"2025\"},{\"n\":\"2024\","
            + "\"v\":\"2024\"},{\"n\":\"2023\",\"v\":\"2023\"},{\"n\":\"2022\",\"v\":\"2022\"},{\"n\":\"202"
            + "1\",\"v\":\"2021\"},{\"n\":\"2020\",\"v\":\"2020\"},{\"n\":\"2019\",\"v\":\"2019\"},{\"n\":\""
            + "2010年代\",\"v\":\"2010年代\"},{\"n\":\"2000年代\",\"v\":\"2000年代\"},{\"n\":\"90年代\",\"v\":\"90年代\""
            + "},{\"n\":\"80年代\",\"v\":\"80年代\"},{\"n\":\"70年代\",\"v\":\"70年代\"},{\"n\":\"60年代\",\"v\":\"60"
            + "年代\"},{\"n\":\"更早\",\"v\":\"更早\"}]}],\"tv\":[{\"key\":\"类型\",\"name\":\"类型\",\"value\":[{\"n"
            + "\":\"不限\",\"v\":\"\"},{\"n\":\"电视剧\",\"v\":\"电视剧\"},{\"n\":\"综艺\",\"v\":\"综艺\"}]},{\"key\":\""
            + "电视剧形式\",\"name\":\"电视剧形式\",\"value\":[{\"n\":\"不限\",\"v\":\"\"},{\"n\":\"喜剧\",\"v\":\"喜剧\"},"
            + "{\"n\":\"爱情\",\"v\":\"爱情\"},{\"n\":\"悬疑\",\"v\":\"悬疑\"},{\"n\":\"动画\",\"v\":\"动画\"},{\"n\":\""
            + "武侠\",\"v\":\"武侠\"},{\"n\":\"古装\",\"v\":\"古装\"},{\"n\":\"家庭\",\"v\":\"家庭\"},{\"n\":\"犯罪\",\"v"
            + "\":\"犯罪\"},{\"n\":\"科幻\",\"v\":\"科幻\"},{\"n\":\"恐怖\",\"v\":\"恐怖\"},{\"n\":\"历史\",\"v\":\"历史\""
            + "},{\"n\":\"战争\",\"v\":\"战争\"},{\"n\":\"动作\",\"v\":\"动作\"},{\"n\":\"冒险\",\"v\":\"冒险\"},{\"n\""
            + ":\"传记\",\"v\":\"传记\"},{\"n\":\"剧情\",\"v\":\"剧情\"},{\"n\":\"奇幻\",\"v\":\"奇幻\"},{\"n\":\"惊悚\","
            + "\"v\":\"惊悚\"},{\"n\":\"灾难\",\"v\":\"灾难\"},{\"n\":\"歌舞\",\"v\":\"歌舞\"},{\"n\":\"音乐\",\"v\":\""
            + "音乐\"}]},{\"key\":\"综艺形式\",\"name\":\"综艺形式\",\"value\":[{\"n\":\"不限\",\"v\":\"\"},{\"n\":\"真人"
            + "秀\",\"v\":\"真人秀\"},{\"n\":\"脱口秀\",\"v\":\"脱口秀\"},{\"n\":\"音乐\",\"v\":\"音乐\"},{\"n\":\"歌舞\",\""
            + "v\":\"歌舞\"}]},{\"key\":\"地区\",\"name\":\"地区\",\"value\":[{\"n\":\"全部地区\",\"v\":\"\"},{\"n\":"
            + "\"华语\",\"v\":\"华语\"},{\"n\":\"欧美\",\"v\":\"欧美\"},{\"n\":\"中国\",\"v\":\"中国\"},{\"n\":\"美国\",\""
            + "v\":\"美国\"},{\"n\":\"中国香港\",\"v\":\"中国香港\"},{\"n\":\"韩国\",\"v\":\"韩国\"},{\"n\":\"日本\",\"v\":"
            + "\"日本\"},{\"n\":\"英国\",\"v\":\"英国\"},{\"n\":\"泰国\",\"v\":\"泰国\"},{\"n\":\"中国台湾\",\"v\":\"中国台湾"
            + "\"},{\"n\":\"意大利\",\"v\":\"意大利\"},{\"n\":\"法国\",\"v\":\"法国\"},{\"n\":\"德国\",\"v\":\"德国\"},{\""
            + "n\":\"西班牙\",\"v\":\"西班牙\"},{\"n\":\"俄罗斯\",\"v\":\"俄罗斯\"},{\"n\":\"瑞典\",\"v\":\"瑞典\"},{\"n\":"
            + "\"巴西\",\"v\":\"巴西\"},{\"n\":\"丹麦\",\"v\":\"丹麦\"},{\"n\":\"印度\",\"v\":\"印度\"},{\"n\":\"加拿大\","
            + "\"v\":\"加拿大\"},{\"n\":\"爱尔兰\",\"v\":\"爱尔兰\"},{\"n\":\"澳大利亚\",\"v\":\"澳大利亚\"}]},{\"key\":\"so"
            + "rt\",\"name\":\"排序\",\"value\":[{\"n\":\"近期热度\",\"v\":\"T\"},{\"n\":\"首播时间\",\"v\":\"R\"},{\""
            + "n\":\"高分优先\",\"v\":\"S\"}]},{\"key\":\"年代\",\"name\":\"年代\",\"value\":[{\"n\":\"全部\",\"v\":\""
            + "\"},{\"n\":\"2026\",\"v\":\"2026\"},{\"n\":\"2025\",\"v\":\"2025\"},{\"n\":\"2024\",\"v\":\""
            + "2024\"},{\"n\":\"2023\",\"v\":\"2023\"},{\"n\":\"2022\",\"v\":\"2022\"},{\"n\":\"2021\",\"v\""
            + ":\"2021\"},{\"n\":\"2020\",\"v\":\"2020\"},{\"n\":\"2019\",\"v\":\"2019\"},{\"n\":\"2010年代\""
            + ",\"v\":\"2010年代\"},{\"n\":\"2000年代\",\"v\":\"2000年代\"},{\"n\":\"90年代\",\"v\":\"90年代\"},{\"n\""
            + ":\"80年代\",\"v\":\"80年代\"},{\"n\":\"70年代\",\"v\":\"70年代\"},{\"n\":\"60年代\",\"v\":\"60年代\"},{\""
            + "n\":\"更早\",\"v\":\"更早\"}]},{\"key\":\"平台\",\"name\":\"平台\",\"value\":[{\"n\":\"全部\",\"v\":\""
            + "\"},{\"n\":\"腾讯视频\",\"v\":\"腾讯视频\"},{\"n\":\"爱奇艺\",\"v\":\"爱奇艺\"},{\"n\":\"优酷\",\"v\":\"优酷\""
            + "},{\"n\":\"湖南卫视\",\"v\":\"湖南卫视\"},{\"n\":\"Netflix\",\"v\":\"Netflix\"},{\"n\":\"HBO\",\"v\""
            + ":\"HBO\"},{\"n\":\"BBC\",\"v\":\"BBC\"},{\"n\":\"NHK\",\"v\":\"NHK\"},{\"n\":\"CBS\",\"v\":\""
            + "CBS\"},{\"n\":\"NBC\",\"v\":\"NBC\"},{\"n\":\"tvN\",\"v\":\"tvN\"}]}],\"rank_list_movie\":[{"
            + "\"key\":\"榜单\",\"name\":\"榜单\",\"value\":[{\"n\":\"实时热门电影\",\"v\":\"movie_real_time_hotest\""
            + "},{\"n\":\"一周口碑电影榜\",\"v\":\"movie_weekly_best\"},{\"n\":\"豆瓣电影Top250\",\"v\":\"movie_top250"
            + "\"}]}],\"rank_list_tv\":[{\"key\":\"榜单\",\"name\":\"榜单\",\"value\":[{\"n\":\"实时热门电视\",\"v\":"
            + "\"tv_real_time_hotest\"},{\"n\":\"华语口碑剧集榜\",\"v\":\"tv_chinese_best_weekly\"},{\"n\":\"全球口碑剧"
            + "集榜\",\"v\":\"tv_global_best_weekly\"},{\"n\":\"国内口碑综艺榜\",\"v\":\"show_chinese_best_weekly\"}"
            + ",{\"n\":\"国外口碑综艺榜\",\"v\":\"show_global_best_weekly\"}]}]}";

    /**
     * 筛选组的<b>输出顺序</b> —— 与 {@link #FILTERS_JSON} 字面量的书写顺序逐一对应
     * （{@code hot_gaia, tv_hot, anime_hot, show_hot, movie, tv, rank_list_movie, rank_list_tv}）。
     *
     * <h2>为什么显式声明，而不是迭代 {@code JSONObject.keys()}</h2>
     * {@code org.json} 的键序**不是契约**，两个世代不同（实测见
     * {@code maintain/src/common/JsonText.java}）：
     * <pre>
     *   现代 json-java  → 插入序；旧版 org.json（真宿主 fmplayer.app）→ 散列序
     * </pre>
     * 筛选组顺序是<b>用户可见的呈现顺序</b>（宿主的筛选条就按这个序渲染），
     * 属于我们的行为而不是库的实现细节；靠 {@code keys()} 会让它随宿主库版本漂移。
     * 上游的字面量本来就按这个语义排过序，所以顺序表直接对齐字面量书写序。
     *
     * <p>{@link #filterGroupsNotOrdered()} 兜底：字面量新增了组而本表忘了同步时，
     * 新组会追加在末尾 —— 宁可顺序不完美，也不能让一个筛选组凭空消失。
     */
    public static final String[] FILTER_GROUP_ORDER = {
            "hot_gaia", "tv_hot", "anime_hot", "show_hot",
            "movie", "tv", "rank_list_movie", "rank_list_tv",
    };

    /** 上次搜索响应（上游字段 {@code this.b}，guessyoulike 的兜底数据源） */
    private String lastSearchResponse = "";

    /** 构造 */
    public DoubanPlain() {
        super();
    }

    /**
     * 初始化。
     *
     * <p>上游 {@code init(Context,String)} 是<b>空方法</b>：本站点地址与 apiKey 全硬编码，
     * <b>没有 ext 契约</b>。本重写调用父类初始化（保持仓库内统一），但不读取任何 ext 键。
     *
     * @param context 宿主上下文，可为 null
     * @param extend  ext 原文（本类忽略）
     */
    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        Log.i(TAG, "init ok, base=" + BASE + "（无 ext 契约，地址与 apiKey 硬编码）");
    }

    // ── 请求 ─────────────────────────────────────────────────

    /**
     * frodo 请求头 —— 上游 {@code a()}。
     *
     * <p>四个键（Host/Connection/Referer/User-Agent）全为伪装微信小程序，见类注释。
     *
     * @return 非 null 头表
     */
    public Map<String, String> doubanHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Host", HOST);
        h.put("Connection", "Keep-Alive");
        h.put("Referer", WECHAT_REFERER);
        h.put("User-Agent", WECHAT_UA);
        return h;
    }

    /**
     * GET 文本（覆写即可在测试中完全离线）。
     *
     * @param url 完整地址
     * @return 响应文本；失败为空串
     */
    public String fetch(String url) {
        return fetch(url, doubanHeaders());
    }

    /**
     * 带自定义头的 GET 文本。
     *
     * @param url     完整地址
     * @param headers 头表，可为 null
     * @return 响应文本；失败为空串
     */
    public String fetch(String url, Map<String, String> headers) {
        try {
            return http.get(url, headers);
        } catch (Throwable e) {
            Log.e(TAG, "fetch failed: " + url, e);
            return "";
        }
    }

    /**
     * 读 {@code /config.json}（上游 {@code k.a(k.d("/config.json"))}）。
     *
     * <p>配置中心（{@link ConfigPlain}）与本站点读的是同一个文件，
     * 因此复用它的 {@link ConfigPlain.Host} 抽象；测试注入 {@link ConfigPlain#setHost} 即可。
     *
     * @return 文本；不存在为空串
     */
    public String readConfig() {
        return ConfigPlain.host().read(ConfigPlain.CONFIG_PATH);
    }

    /**
     * 搜索历史（上游 {@code merge.p038m.l.b("historyvodname")}）—— 宿主偏好里的 JSON 数组文本。
     *
     * <p>默认返回空串（桌面/测试环境无宿主偏好），"猜你喜欢"据此回落到空列表。
     * 覆写本方法即可注入历史。
     *
     * @return JSON 数组文本，如 {@code ["片名A","片名B"]}
     */
    public String searchHistory() {
        return "";
    }

    // ── 图片地址格式（独立方法 + 单独测） ─────────────────────

    /**
     * 把豆瓣原图地址拼成宿主专用格式 —— 上游那段
     * {@code pic.normal + "@Referer=https://api.douban.com/@User-Agent=…"}。
     *
     * @param normal 原图地址，可为 null/空
     * @return 拼接结果；{@code normal} 为空时仍然返回后缀（与上游一致，逐字节相同拼法）
     */
    public static String doubanPic(String normal) {
        return (normal == null ? "" : normal) + PIC_SUFFIX;
    }

    /**
     * 从宿主格式里取出<b>纯净图片地址</b>（第一个 {@code @Referer=} 之前的部分）。
     *
     * @param pic 宿主格式地址，可为 null
     * @return 纯 URL；无后缀时原样返回；null 时返回空串
     */
    public static String picUrl(String pic) {
        if (pic == null) {
            return "";
        }
        int i = pic.indexOf("@Referer=");
        return i < 0 ? pic : pic.substring(0, i);
    }

    /**
     * 从宿主格式里取出 Referer。
     *
     * @param pic 宿主格式地址，可为 null
     * @return Referer；缺失返回空串
     */
    public static String picReferer(String pic) {
        if (pic == null) {
            return "";
        }
        int i = pic.indexOf("@Referer=");
        if (i < 0) {
            return "";
        }
        int start = i + "@Referer=".length();
        int j = pic.indexOf("@User-Agent=", start);
        return j < 0 ? pic.substring(start) : pic.substring(start, j);
    }

    /**
     * 从宿主格式里取出 User-Agent。
     *
     * @param pic 宿主格式地址，可为 null
     * @return UA；缺失返回空串
     */
    public static String picUserAgent(String pic) {
        if (pic == null) {
            return "";
        }
        int i = pic.indexOf("@User-Agent=");
        return i < 0 ? "" : pic.substring(i + "@User-Agent=".length());
    }

    // ── 条目解析 ─────────────────────────────────────────────

    /**
     * 条目 id —— 上游 {@code "msearch:" + optString("id")}。
     *
     * @param o 条目，可为 null
     * @return {@code msearch:<id>}；无 id 时为 {@code msearch:}
     */
    public static String vodId(JSONObject o) {
        return "msearch:" + (o == null ? "" : o.optString("id", ""));
    }

    /**
     * 条目封面 —— 上游 {@code getJSONObject("pic").optString("normal") + 宿主后缀}，
     * 缺 {@code pic} 字段时整段被 catch 成空串。
     *
     * @param o 条目，可为 null
     * @return 宿主格式地址；无封面为空串
     */
    public static String itemPic(JSONObject o) {
        if (o == null) {
            return "";
        }
        JSONObject pic = o.optJSONObject("pic");
        if (pic == null) {
            return "";
        }
        return doubanPic(pic.optString("normal", ""));
    }

    /**
     * 条目评分文案 —— 上游 {@code "评分：" + getJSONObject("rating").optString("value")}。
     *
     * @param o 条目，可为 null
     * @return 形如 {@code 评分：8.7}；无 rating 时为空串
     */
    public static String itemRating(JSONObject o) {
        if (o == null) {
            return "";
        }
        JSONObject rating = o.optJSONObject("rating");
        if (rating == null) {
            return "";
        }
        String v = rating.optString("value", "");
        return v.isEmpty() ? "评分：" : "评分：" + v;
    }

    /**
     * 条目 → {@link Vod}（列表口径：id/name/pic/remarks）。
     *
     * @param o 条目，可为 null
     * @return Vod；{@code o} 为 null 时返回 null
     */
    public static Vod itemToVod(JSONObject o) {
        if (o == null) {
            return null;
        }
        return new Vod()
                .id(vodId(o))
                .name(o.optString("title", ""))
                .pic(itemPic(o))
                .remarks(itemRating(o));
    }

    /**
     * 解析 items 数组 —— 上游 {@code c(JSONArray)}。
     *
     * @param items 数组，可为 null
     * @return 非 null 列表（跳过非对象元素）
     */
    public static List<Vod> parseItems(JSONArray items) {
        List<Vod> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null) {
                out.add(itemToVod(o));
            }
        }
        return out;
    }

    // ── ★ 过滤无封面条目（会就地修传入对象） ──────────────────

    /**
     * 过滤掉没有封面的条目 —— 上游 {@code public static String filterItemsWithoutPic(String)}。
     *
     * <p><b>副作用（上游真实语义，本重写保留）</b>：解析后的对象被<b>就地</b>
     * {@code put("list", 过滤后的数组)} 再 {@code toString()} 返回，
     * 而不是返回一个新建的对象。因此：
     * <ul>
     *   <li>返回的 JSON 文本里 {@code list} 已被裁剪；</li>
     *   <li>若调用方自己也持有那个 {@link JSONObject}（见 2 参重载），它会看到 {@code list} 被换掉。</li>
     * </ul>
     *
     * @param json 形如 {@code {"page":1,"list":[{...}]}} 的文本
     * @return 过滤后的 JSON 文本；解析失败时<b>原样返回入参</b>（上游 catch 分支）
     */
    public static String filterItemsWithoutPic(String json) {
        if (json == null) {
            return "";
        }
        try {
            JSONObject root = new JSONObject(json);
            if (root.optJSONArray("list") == null) {
                // 上游用 getJSONArray("list")：缺键时抛 JSONException → 被 catch 后原样返回
                return json;
            }
            filterItemsWithoutPic(root);
            return root.toString();
        } catch (Throwable e) {
            Log.w(TAG, "filterItemsWithoutPic: 非 JSON，原样返回");
            return json;
        }
    }

    /**
     * 就地过滤 —— 把 {@code list} 里 {@code vod_pic} 为空的条目剔除后<b>写回同一对象</b>。
     *
     * @param root 目标对象；null 时直接返回 null
     * @return 同一个 {@code root}（便于链式），null 入参返回 null
     */
    public static JSONObject filterItemsWithoutPic(JSONObject root) {
        if (root == null) {
            return null;
        }
        try {
            JSONArray src = root.optJSONArray("list");
            if (src == null) {
                return root;   // 缺 list：上游 getJSONArray 抛异常 → 不做任何修改
            }
            JSONArray kept = new JSONArray();
            if (src != null) {
                for (int i = 0; i < src.length(); i++) {
                    JSONObject o = src.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    if (!TextUtils.isEmpty(o.optString("vod_pic", ""))) {
                        kept.put(o);
                    }
                }
            }
            root.put("list", kept);
        } catch (Throwable e) {
            Log.w(TAG, "filterItemsWithoutPic(root) failed", e);
        }
        return root;
    }

    // ── 查询串构造 ───────────────────────────────────────────

    /**
     * 去掉末尾一个字符 —— 上游 {@code merge.A.a.u(String)} 的等价实现。
     *
     * <p>上游用它去掉 {@link #tagsOf} 拼出来的尾逗号；语义是：
     * {@code s == null} 原样、{@code length <= 1} 原样、否则 {@code substring(0, len-1)}。
     *
     * @param s 文本，可为 null
     * @return 处理结果
     */
    public static String trimTrailingComma(String s) {
        if (s == null || s.length() <= 1) {
            return s;
        }
        return s.substring(0, s.length() - 1);
    }

    /**
     * 由筛选参数拼 tags —— 上游 {@code b(HashMap)}。
     *
     * <p>规则：<b>跳过键 {@code sort}</b>，其余每个值后面跟一个 {@code ,}，
     * 最后去掉尾逗号。因此 {@code {类型:剧情, 地区:日本}} → {@code 剧情,日本}。
     *
     * <p>⚠️ 上游入参是 {@link HashMap}，遍历顺序由哈希决定，故拼接顺序本身不确定
     * （测试里用 {@link LinkedHashMap} 固定顺序来断言规则）。
     *
     * @param extend 筛选表，可为 null
     * @return tags 串；空表返回空串
     */
    public static String tagsOf(Map<String, String> extend) {
        if (extend == null || extend.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : extend.entrySet()) {
            if ("sort".equals(e.getKey())) {
                continue;
            }
            sb.append(e.getValue() == null ? "" : e.getValue()).append(',');
        }
        return trimTrailingComma(sb.toString());
    }

    /**
     * 分页起点 —— 上游到处出现的 {@code (pg-1)*20}（第 1 页为 0）。
     *
     * @param pg 页码文本（容错：非数字按 1）
     * @return 起点偏移
     */
    public static int startOf(String pg) {
        int p = pageNum(pg, 1);
        return (p - 1) * PAGE_SIZE;
    }

    /**
     * 清洗搜索词 —— 上游正则
     * {@code \{[^{}]*\}|\[[^\[\]]*\]|\([^()]*\)|（[^（）]*）} 去掉括号内容。
     *
     * @param name 原始片名，可为 null
     * @return 清洗后的词；null 返回空串
     */
    public static String cleanSearchKey(String name) {
        return name == null ? "" : name.replaceAll(CLEAN_KEY_REGEX, "");
    }

    // ── URL 构造（全部可单测） ───────────────────────────────

    /**
     * 热门电影（gaia）URL。
     *
     * @param pg     页码
     * @param extend 筛选（{@code sort}/{@code area}）
     * @return 完整 URL
     */
    public static String hotGaiaUrl(String pg, Map<String, String> extend) {
        String sort = param(extend, "sort", "recommend");
        String area = param(extend, "area", "全部");
        return BASE + "/rexxar/api/v2/movie/hot_gaia" + QUERY_SUFFIX
                + "&sort=" + sort + "&area=" + Http.encode(area)
                + "&start=" + startOf(pg) + "&count=" + PAGE_SIZE;
    }

    /**
     * 热门动漫 URL（注意 tags 会叠加 类型/地区/年代）。
     *
     * @param pg     页码
     * @param extend 筛选
     * @return 完整 URL
     */
    public static String animeHotUrl(String pg, Map<String, String> extend) {
        String sort = param(extend, "sort", "T");
        StringBuilder tags = new StringBuilder("动画");
        for (String k : new String[]{"类型", "地区", "年代"}) {
            String v = param(extend, k, "");
            if (!v.isEmpty()) {
                tags.append(',').append(v);
            }
        }
        return BASE + "/rexxar/api/v2/tv/recommend" + QUERY_SUFFIX
                + "&sort=" + sort + "&tags=" + tags
                + "&start=" + startOf(pg) + "&count=" + PAGE_SIZE;
    }

    /**
     * 电影筛选 URL（tags 来自 {@link #tagsOf}）。
     *
     * @param pg     页码
     * @param extend 筛选
     * @return 完整 URL
     */
    public static String movieRecommendUrl(String pg, Map<String, String> extend) {
        String sort = param(extend, "sort", "T");
        return BASE + "/rexxar/api/v2/movie/recommend" + QUERY_SUFFIX
                + "&sort=" + sort + "&tags=" + Http.encode(tagsOf(extend))
                + "&start=" + startOf(pg) + "&count=" + PAGE_SIZE;
    }

    /**
     * 电视筛选 URL。
     *
     * @param pg     页码
     * @param extend 筛选
     * @return 完整 URL
     */
    public static String tvRecommendUrl(String pg, Map<String, String> extend) {
        String sort = param(extend, "sort", "T");
        return BASE + "/rexxar/api/v2/tv/recommend" + QUERY_SUFFIX
                + "&sort=" + sort + "&tags=" + Http.encode(tagsOf(extend))
                + "&start=" + startOf(pg) + "&count=" + PAGE_SIZE;
    }

    /**
     * 电影榜单 URL。
     *
     * @param pg     页码
     * @param extend 筛选（{@code 榜单}）
     * @return 完整 URL
     */
    public static String rankListMovieUrl(String pg, Map<String, String> extend) {
        String id = param(extend, "榜单", RANK_MOVIE_DEFAULT);
        return BASE + "/rexxar/api/v2/subject_collection/" + id + "/items" + QUERY_SUFFIX
                + "&start=" + startOf(pg) + "&count=" + PAGE_SIZE;
    }

    /**
     * 电视榜单 URL。
     *
     * @param pg     页码
     * @param extend 筛选（{@code 榜单}）
     * @return 完整 URL
     */
    public static String rankListTvUrl(String pg, Map<String, String> extend) {
        String id = param(extend, "榜单", RANK_TV_DEFAULT);
        return BASE + "/rexxar/api/v2/subject_collection/" + id + "/items" + QUERY_SUFFIX
                + "&start=" + startOf(pg) + "&count=" + PAGE_SIZE;
    }

    /**
     * 综艺 URL。
     *
     * @param pg     页码
     * @param extend 筛选（{@code type}）
     * @return 完整 URL
     */
    public static String showHotUrl(String pg, Map<String, String> extend) {
        String type = param(extend, "type", "show_hot");
        return collectionItemsUrl(type, pg);
    }

    /**
     * 热播剧集 URL。
     *
     * @param pg     页码
     * @param extend 筛选（{@code type}）
     * @return 完整 URL
     */
    public static String tvHotUrl(String pg, Map<String, String> extend) {
        String type = param(extend, "type", "tv_hot");
        return collectionItemsUrl(type, pg);
    }

    /**
     * 集合条目 URL（show_hot / tv_hot / 榜单共用同一种形状）。
     *
     * @param collection 集合 id
     * @param pg         页码
     * @return 完整 URL
     */
    public static String collectionItemsUrl(String collection, String pg) {
        return BASE + "/rexxar/api/v2/subject_collection/" + collection + "/items" + QUERY_SUFFIX
                + "&start=" + startOf(pg) + "&count=" + PAGE_SIZE;
    }

    /**
     * 微信小程序搜索 URL（{@code search/weixin}）。
     *
     * @param q 关键词
     * @return 完整 URL
     */
    public static String weixinSearchUrl(String q) {
        return BASE + "/rexxar/api/v2/search/weixin?q=" + Http.encode(q)
                + "&start=0&count=" + PAGE_SIZE + "&apikey=" + API_KEY;
    }

    /**
     * 相关推荐 URL。
     *
     * <p>⚠️ 上游这里关键字是 <b>大写 K</b> 的 {@code apiKey}，与其它端点的
     * {@code apikey} 不一致 —— 是上游原文，保持原样（勿"顺手统一"）。
     *
     * @param targetType 目标类型（tv / movie）
     * @param id         目标 id
     * @return 完整 URL
     */
    public static String recommendationsUrl(String targetType, String id) {
        return BASE + "/rexxar/api/v2/" + targetType + "/" + id
                + "/recommendations?apiKey=" + API_KEY;
    }

    /** 取筛选值并给默认值 */
    public static String param(Map<String, String> extend, String key, String def) {
        if (extend == null || key == null) {
            return def;
        }
        String v = extend.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    // ── 分类页处理 ───────────────────────────────────────────

    /**
     * 分类：猜你喜欢（依据宿主搜索历史逐条找相关推荐）。
     *
     * <p>上游流程：取历史第 {@code pg-1} 条名字 → 清洗括号 → 微信搜索 →
     * 找 {@code has_linewatch=true} 且标题完全一致的目标（没有就取第一个）→
     * 拉该目标的 recommendations → 解析成列表；
     * 若这条路径失败，则回落到<b>搜索响应的 items</b>（只取 target_type 为 tv/movie 的）。
     *
     * @param pg 页码（即历史下标 + 1）
     * @return {@code {"list":[...]}}
     */
    public String processGuessyoulike(String pg) {
        try {
            int page = pageNum(pg, 1);
            String history = searchHistory();
            if (TextUtils.isEmpty(history)) {
                return emptyListJson();
            }
            JSONArray names = new JSONArray(history);
            if (page - 1 >= names.length()) {
                return emptyListJson();
            }
            String key = cleanSearchKey(names.getString(page - 1));
            lastSearchResponse = fetch(weixinSearchUrl(key));
            JSONObject search = new JSONObject(lastSearchResponse);
            JSONObject chosen = null;
            JSONArray items = search.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) {
                        continue;
                    }
                    JSONObject target = it.optJSONObject("target");
                    if (target == null || !target.optBoolean("has_linewatch", false)) {
                        continue;
                    }
                    if (key.equals(target.optString("title", ""))) {
                        chosen = it;
                        break;
                    }
                    if (chosen == null) {
                        chosen = it;
                    }
                }
            }
            if (chosen != null) {
                String type = chosen.optString("target_type", "");
                JSONObject target = chosen.optJSONObject("target");
                String id = target == null ? "" : target.optString("id", "");
                if (!id.isEmpty() && !type.isEmpty()) {
                    String body = fetch(recommendationsUrl(type, id));
                    if (!body.contains("\"code\":404")) {
                        JSONArray rec = new JSONArray(body);
                        JSONArray out = new JSONArray();
                        for (int i = 0; i < rec.length(); i++) {
                            out.put(rec.optJSONObject(i));
                        }
                        JSONObject root = new JSONObject();
                        root.put("list", toSimpleList(out, false));
                        return root.toString();
                    }
                }
            }
            // 回落：用搜索响应本身
            JSONArray fallback = new JSONArray();
            JSONArray its = new JSONObject(lastSearchResponse).optJSONArray("items");
            if (its != null) {
                for (int i = 0; i < its.length(); i++) {
                    JSONObject it = its.optJSONObject(i);
                    if (it == null) {
                        continue;
                    }
                    String t = it.optString("target_type", "");
                    if (!"tv".equals(t) && !"movie".equals(t)) {
                        continue;
                    }
                    JSONObject target = it.optJSONObject("target");
                    if (target == null) {
                        continue;
                    }
                    fallback.put(target);
                }
            }
            JSONObject root2 = new JSONObject();
            root2.put("list", toSimpleList(fallback, true));
            return root2.toString();
        } catch (Throwable e) {
            Log.e(TAG, "processGuessyoulike failed", e);
            return emptyListJson();
        }
    }

    /**
     * 把一批对象转成简单条目列表。
     *
     * @param arr           源数组
     * @param coverUrlField true 时封面取 {@code cover_url}（搜索回落口径），
     *                      false 时取 {@code pic.normal}（推荐口径）
     * @return 数组
     */
    private static JSONArray toSimpleList(JSONArray arr, boolean coverUrlField) {
        JSONArray out = new JSONArray();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            try {
                JSONObject v = new JSONObject();
                v.put("vod_id", vodId(o));
                v.put("vod_name", o.optString("title", ""));
                v.put("vod_pic", coverUrlField
                        ? doubanPic(o.optString("cover_url", ""))
                        : itemPic(o));
                v.put("vod_remarks", itemRating(o));
                out.put(v);
            } catch (Throwable e) {
                Log.w(TAG, "toSimpleList item failed");
            }
        }
        return out;
    }

    /**
     * 分类：热门电影。
     *
     * @param pg     页码
     * @param extend 筛选
     * @return 标准分类响应
     */
    public String processHotGaia(String pg, Map<String, String> extend) {
        return commonItems(hotGaiaUrl(pg, extend), pg, "items");
    }

    /**
     * 分类：电影筛选。
     *
     * @param pg     页码
     * @param extend 筛选
     * @return 标准分类响应
     */
    public String processMovieRecommend(String pg, Map<String, String> extend) {
        return commonItems(movieRecommendUrl(pg, extend), pg, "items");
    }

    /**
     * 分类：电视筛选。
     *
     * @param pg     页码
     * @param extend 筛选
     * @return 标准分类响应
     */
    public String processTvRecommend(String pg, Map<String, String> extend) {
        return commonItems(tvRecommendUrl(pg, extend), pg, "items");
    }

    /**
     * 分类：电影榜单。
     *
     * @param pg     页码
     * @param extend 筛选
     * @return 标准分类响应
     */
    public String processRankListMovie(String pg, Map<String, String> extend) {
        return commonItems(rankListMovieUrl(pg, extend), pg, "subject_collection_items");
    }

    /**
     * 分类：电视榜单。
     *
     * @param pg     页码
     * @param extend 筛选
     * @return 标准分类响应
     */
    public String processRankListTv(String pg, Map<String, String> extend) {
        return commonItems(rankListTvUrl(pg, extend), pg, "subject_collection_items");
    }

    /**
     * 分类：综艺。
     *
     * @param pg     页码
     * @param extend 筛选
     * @return 标准分类响应
     */
    public String processShowHot(String pg, Map<String, String> extend) {
        return commonItems(showHotUrl(pg, extend), pg, "subject_collection_items");
    }

    /**
     * 分类：热播剧集。
     *
     * @param pg     页码
     * @param extend 筛选
     * @return 标准分类响应
     */
    public String processTvHot(String pg, Map<String, String> extend) {
        return commonItems(tvHotUrl(pg, extend), pg, "subject_collection_items");
    }

    /**
     * 分类：热门动漫（与其它分类的唯一差异是"无封面条目也会被过滤"）。
     *
     * @param pg     页码
     * @param extend 筛选
     * @return 标准分类响应
     */
    public String processAnimeHot(String pg, Map<String, String> extend) {
        try {
            List<Vod> list = parseItems(arrOf(fetch(animeHotUrl(pg, extend)), "items"));
            int page = pageNum(pg, 1);
            VodBuilder b = VodBuilder.create().addAll(list).page(page, list.size(), PAGE_SIZE);
            return filterItemsWithoutPic(b.categoryContent());
        } catch (Throwable e) {
            Log.e(TAG, "processAnimeHot failed", e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /**
     * 通用分类处理：拉取 → 取数组 → 解析 → 就地过滤无封面 → 标准响应。
     *
     * <p>分页口径：上游写 {@code i(page, Integer.MAX_VALUE, 20, Integer.MAX_VALUE)}
     * （pagecount/total 都是"无穷"），本重写用 {@code page(page, MAX, 20)} 交给
     * {@link VodBuilder} 推导 pagecount —— 语义等价（都是"未知上限、可继续翻"）。
     *
     * @param url      接口地址
     * @param pg       页码
     * @param arrayKey 条目数组所在字段名
     * @return 标准分类响应
     */
    private String commonItems(String url, String pg, String arrayKey) {
        try {
            List<Vod> list = parseItems(arrOf(fetch(url), arrayKey));
            int page = pageNum(pg, 1);
            VodBuilder b = VodBuilder.create().addAll(list)
                    .page(page, Integer.MAX_VALUE, PAGE_SIZE);
            return filterItemsWithoutPic(b.categoryContent());
        } catch (Throwable e) {
            Log.e(TAG, "commonItems failed: " + url, e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /**
     * 从响应文本里取数组字段。
     *
     * @param body 响应文本
     * @param key  字段名
     * @return 数组；解析失败返回 null
     */
    private static JSONArray arrOf(String body, String key) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(body).optJSONArray(key);
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code {"list":[]}} */
    public static String emptyListJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("list", new JSONArray());
            return o.toString();
        } catch (Throwable e) {
            return "{\"list\":[]}";
        }
    }

    // ── 契约方法 ─────────────────────────────────────────────

    /**
     * 分类页分发。
     *
     * <p>未知分类上游返回空串；本重写返回宿主契约要求的空响应 {@code {}} 并记日志。
     *
     * @param tid    分类 id（见 {@link #CATEGORY_IDS}）
     * @param pg     页码
     * @param filter 宿主传参，上游未使用
     * @param extend 筛选表，可为 null（上游部分分支未判空，本重写全部容错）
     * @return 标准分类响应
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        Map<String, String> ext = extend == null ? new HashMap<String, String>() : extend;
        try {
            if ("guess_you_like".equals(tid)) {
                return processGuessyoulike(pg);
            }
            if ("hot_gaia".equals(tid)) {
                return processHotGaia(pg, ext);
            }
            if ("tv_hot".equals(tid)) {
                return processTvHot(pg, ext);
            }
            if ("anime_hot".equals(tid)) {
                return processAnimeHot(pg, ext);
            }
            if ("show_hot".equals(tid)) {
                return processShowHot(pg, ext);
            }
            if ("tv".equals(tid)) {
                return processTvRecommend(pg, ext);
            }
            if ("movie".equals(tid)) {
                return processMovieRecommend(pg, ext);
            }
            if ("rank_list_movie".equals(tid)) {
                return processRankListMovie(pg, ext);
            }
            if ("rank_list_tv".equals(tid)) {
                return processRankListTv(pg, ext);
            }
            Log.w(TAG, "未知分类: " + tid);
            return VodBuilder.EMPTY_RESPONSE;
        } catch (Throwable e) {
            Log.e(TAG, "categoryContent failed, tid=" + tid + " pg=" + pg, e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /**
     * 首页：分类表（受 {@code config.json} 的 {@code homePage} 控制）+ 实时热门推荐。
     *
     * <p>{@code homePage} 是逗号分隔的<b>显示名</b>列表；为空则展示全部 9 个分类
     * （上游语义）。filters 用硬编码的 {@link #FILTERS_JSON}。
     *
     * @param filter 宿主传参，本类始终返回完整 filters
     * @return 标准首页响应
     */
    @Override
    public String homeContent(boolean filter) {
        try {
            Set<String> allow = homePageFilter();
            VodBuilder b = VodBuilder.create();
            for (int i = 0; i < CATEGORY_IDS.length; i++) {
                if (allow.isEmpty() || allow.contains(CATEGORY_NAMES[i])) {
                    b.clazz(CATEGORY_IDS[i], CATEGORY_NAMES[i]);
                }
            }
            b.addAll(homeVods());
            addFilters(b);
            return b.homeContent();
        } catch (Throwable e) {
            Log.e(TAG, "homeContent failed", e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /**
     * 首页推荐列表（实时热门）—— 失败时返回空列表（上游此处置会把首页打白）。
     *
     * @return 非 null 列表
     */
    public List<Vod> homeVods() {
        try {
            String body = fetch(collectionItemsUrl(HOME_COLLECTION, "1"));
            return parseItems(arrOf(body, "subject_collection_items"));
        } catch (Throwable e) {
            Log.e(TAG, "homeVods failed", e);
            return new ArrayList<>();
        }
    }

    /**
     * 解析 {@code homePage} 白名单（显示名集合），读不到就返回空集合（=展示全部）。
     *
     * @return 非 null 集合
     */
    public Set<String> homePageFilter() {
        Set<String> out = new LinkedHashSet<>();
        try {
            String cfg = readConfig();
            if (cfg == null || cfg.isEmpty()) {
                return out;
            }
            String v = new JSONObject(cfg).optString("homePage", "");
            if (TextUtils.isEmpty(v)) {
                return out;
            }
            out.addAll(Arrays.asList(v.split(",")));
        } catch (Throwable e) {
            Log.w(TAG, "homePage 配置不可用，展示全部分类");
        }
        return out;
    }

    /**
     * 把硬编码 filters 写进构造器。
     *
     * @param b 构造器
     */
    public static void addFilters(VodBuilder b) {
        try {
            JSONObject f = new JSONObject(FILTERS_JSON);
            // 顺序取自 FILTER_GROUP_ORDER（显式声明），不是 f.keys()（库的实现细节，两代不同）
            for (String k : FILTER_GROUP_ORDER) {
                if (f.has(k)) {
                    b.filter(k, f.optJSONArray(k));
                }
            }
            for (String k : filterGroupsNotOrdered()) {
                b.filter(k, f.optJSONArray(k));
            }
        } catch (Throwable e) {
            Log.e(TAG, "FILTERS_JSON 解析失败", e);
        }
    }

    /**
     * 供测试/排障：filters JSON 的键集合。
     *
     * @return 非 null 列表
     */
    public static List<String> filterKeys() {
        List<String> out = new ArrayList<>();
        try {
            JSONObject f = new JSONObject(FILTERS_JSON);
            for (String k : FILTER_GROUP_ORDER) {
                if (f.has(k)) {
                    out.add(k);
                }
            }
            out.addAll(filterGroupsNotOrdered());
        } catch (Throwable e) {
            Log.e(TAG, "filterKeys failed", e);
        }
        return out;
    }

    /**
     * 字面量里出现、但 {@link #FILTER_GROUP_ORDER} 未登记的筛选组。
     *
     * <p>正常恒为空；非空说明往 {@link #FILTERS_JSON} 加了组却没同步顺序表。
     * 测试据此钉住这个漂移点（这种错只会在真机上以"少一个筛选条"的形式暴露）。
     *
     * @return 未登记的键；永不为 null
     */
    public static List<String> filterGroupsNotOrdered() {
        List<String> out = new ArrayList<>();
        try {
            JSONObject f = new JSONObject(FILTERS_JSON);
            List<String> declared = new ArrayList<>();
            for (String k : FILTER_GROUP_ORDER) {
                declared.add(k);
            }
            for (String k : JsonKeys.of(f)) {
                if (!declared.contains(k)) {
                    out.add(k);
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "filterGroupsNotOrdered failed", e);
        }
        return out;
    }

    /**
     * 供测试读取最近一次搜索响应（上游字段 {@code this.b} 的排障出口）。
     *
     * @return 文本；未搜索过为空串
     */
    public String lastSearchResponse() {
        return lastSearchResponse;
    }

    /**
     * 站点标识（日志用）。
     *
     * @return 固定 {@code douban}
     */
    public String siteName() {
        return "douban";
    }

    /** 供测试断言：不使用但保持与其它 Plain 一致的可读性工具 */
    static Set<String> setOf(String... v) {
        return new HashSet<>(Arrays.asList(v));
    }
}
