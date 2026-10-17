package com.example.tvbox.plain.qishui;

import android.text.TextUtils;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.net.AndroidBase64;
import com.example.tvbox.plain.player.PlayerResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 汽水音乐（QiShui）—— 纯 JSON + aweme 签名的抖音系音频源。
 *
 * <p>反编译原文: {@code ae/decompiled/QiShui.java}（1017 行）。
 *
 * <h2>协议</h2>
 * <pre>
 *  接口   GET  https://api.qishui.com/luna/...       （luna 系列 JSON 接口）
 *         POST https://api.qishui.com/luna/...?...   （体为 JSON）
 *  头     User-Agent: Mozilla/5.0 (Linux; Android 10; TVBox) ... Chrome/124
 *         Accept: application/json, text/plain, *&#47;*
 *         Referer: https://music.douyin.com/
 *  播放   "v:&lt;video_id&gt;" → https://aweme.snssdk.com/aweme/v1/play/?video_id=..&amp;ratio=1080p&amp;line=0
 *         "a:&lt;track_id&gt;:&lt;qualityKey&gt;" → 先查 /luna/h5/seo_track 的清晰度表，再取对应 url
 * </pre>
 *
 * <h2>本类最"绕"的设计：id 是自包含的 base64 JSON</h2>
 * 上游把详情所需的上下文全部塞进 id 里（{@code prefix:base64url(json)}，flag 11 =
 * URL_SAFE|NO_PADDING|NO_WRAP）：
 * <pre>
 *   mode:&lt;{id,name,kind,sub,pic}&gt;        听歌模式（分类页 "home"）
 *   collection:&lt;{id,name,kind,sub,desc,pic}&gt; 发现页的书架/歌单
 *   video:&lt;{vid,video_id,name,pic,author,duration}&gt; 听抖音
 * </pre>
 * 于是 {@code detailContent} 只要解码 id 就能知道该走哪条分支，不用回查接口 —— 也意味着
 * <b>id 一旦被宿主截断/改写，详情就退化到兜底分支</b>。这就是 {@link #decodeId} 存在的原因。
 *
 * <h2>证据来源</h2>
 * 每个方法上都标了对应的原方法名（{@code A/B/C/D/E/a..z}）。
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>服务端真实响应样本（全部接口）</b> —— {@code /luna/feed/mode}、
 *       {@code /luna/discover}、{@code /luna/discover/mix}、{@code /luna/search/listen_video}、
 *       {@code /luna/search/track}、{@code /luna/charts/&lt;id&gt;}、{@code /luna/playlist/detail}、
 *       {@code /luna/feed/radio/tracks}、{@code /luna/h5/seo_track} 共 9 个接口，
 *       字段路径全部来自反编译静态推断（如 {@code blocks[].inner_block[].resources[].style.cover_url}），
 *       <b>没有一次真实响应可对照</b>。要补齐需要：任一接口的真实响应体（抓包）或
 *       服务端录制的固定向量。</li>
 *   <li><b>「听抖音」的 vid 过期后如何续期</b> —— {@code playerContent} 只是把
 *       {@code video_id} 拼进 aweme 播放地址，没有任何签名/过期时间处理。
 *       若真实服务端要求 {@code &amp;expire=} 或签名参数，此处无法得知。
 *       需要：一条真实的 aweme 播放 URL。</li>
 *   <li><b>DRM / 加密视频</b> —— {@code d()} 里凡是带 {@code encrypt_info} 的视频
 *       （{@link #hasEncryptInfo}）都被<b>直接跳过</b>，上游没有解密逻辑。
 *       这意味着部分"无损/高码率"轨道永远取不到地址。属上游能力缺口，本重写保持同样行为。</li>
 *   <li><b>{@code 48} 个听歌模式名与道序</b> —— {@link #MODE_ORDER} 是原文常量数组；
 *       它的唯一作用是决定 "home" 页里模式的展示顺序。真实模式列表由接口下发，
 *       未在表内的模式统一排到末尾（上游用 {@code map.size()} 作为缺失值），无运行时证据。</li>
 *   <li><b>首页 favicon 图标地址</b> —— {@link #MODE_ICON} 是硬编码的 Apple 商店图，
 *       属静态资源；是否仍可访问未知。</li>
 * </ol>
 *
 * <h2>上游缺陷（逐条标注处理方式）</h2>
 * <ol>
 *   <li><b>已修复（有计划地偏离）</b>：{@code B()} 在 {@code A()} 拿不到 {@code seo_track}
 *       时调用 {@code merge.A.a.m2c("id", str)}。该helper 的实现是
 *       {@code new JSONObject(str).getJSONObject(key)}，也就是把 <b>"id" 当成 JSON 文本解析</b> ——
 *       必然抛 {@code JSONException}。于是上游这一步永远抛异常，
 *       整个 {@code detailContent} 落到最外层 catch 的兜底详情，
 *       <b>连同一响应里已经拿到的清晰度列表一起丢掉</b>。
 *       本重写改成 {@link #syntheticTrack(String)}（即 {@code {"id": id}}，
 *       显然是原意），保留清晰度列表。见类尾 {@code DEVIATIONS} 注释。</li>
 *   <li><b>未复刻（死分支）</b>：{@code D()} 里有一段
 *       {@code if (!TextUtils.isEmpty("") && ...) { desc = A.a.d(" · ", desc); } else if (!TextUtils.isEmpty("")) ...}。
 *       两个条件都以常量空串开头，<b>永远不成立</b>（混淆时把一个已删除的参数内联成了 ""）。
 *       本重写直接取 {@code firstNonEmpty(artists, albumName, "汽水音乐")}，不实现该分支。</li>
 *   <li><b>已修复</b>：{@code g()} 里 {@code inner_block == null} 时上游会直接
 *       {@code jSONArrayOptJSONArray3.length()} → NPE（被外层 catch 吞掉，整页变空）。
 *       本重写按语义补上 {@code resources} 兜底并跳过 null。</li>
 *   <li><b>已修复</b>：{@code g()} 的"发现"页在 {@code resource_id} 缺失时会用
 *       {@code inner_block_id} 兜底，但上游仍可能构造出空 id 的条目；本重写显式跳过空 id。</li>
 *   <li><b>已保留</b>：{@code playerContent} 失败分支返回
 *       {@code {"parse":1,"url":"&lt;原始 id&gt;","msg":...}} —— 把 id 当播放地址返回，
 *       宿主多半播不出。这是上游原样行为，标注但不改。</li>
 *   <li><b>已保留</b>：{@code p()}（md5）失败时回退成 {@code String.valueOf(abs(hashCode()))}，
 *       一个 9~10 位十进制数。上游把它当 {@code search_id} 提交，本重写同样保留该回退。</li>
 * </ol>
 *
 * <h2>已排查但不构成缺陷的疑点（后来者请勿删除这些"看着像死代码"的分支）</h2>
 * <ol>
 *   <li><b>{@code g()} 里 {@code entity.playlist.url_cover} 的封面兜底 —— 曾被我误报为"死分支"，
 *   逐路径穷举后<b>撤回</b>。</b>
 *       它<b>不是</b>死代码，可达性如下（沿上游赋值顺序推）：
 *       <pre>
 *   1. cover = firstNonEmpty(cover_url, ...)        // 先看 cover_url
 *   2. 若 cover_url 为空：
 *        list = entity.optJSONArray("cover_url_list")
 *        if (list == null)  list = new JSONObject()   // ★ 关键：缺失时塞的是"对象"，不是空数组
 *        cover = objOpt(list, 0)                      // 取第 0 个元素
 *   3. 若 cover 仍为空 → 才落到 url_cover 兜底
 *       </pre>
 *       所以兜底可达的充要条件是：{@code cover_url} 为空 <b>且</b> {@code cover_url_list}
 *       是<b>非空数组</b> <b>且</b> 它的第 0 个元素恰好是 JSON {@code null}
 *       （此时 {@code objOpt} 拿到的是 {@code JSONObject.NULL}，经字符串化后是空 → 判定为空）。
 *       两条互补路径都已用测试钉住：
 *       <ul>
 *         <li><b>可达</b>：{@code cover_url_list: [null]} → 断言封面等于 {@code url_cover}
 *             （测试里是 {@code http://nine/cover.jpg}）—— 兜底生效。</li>
 *         <li><b>被挡住</b>：{@code cover_url_list} 缺失、或第 0 个元素不是对象（例如字符串）
 *             → 断言封面为空 —— 兜底不生效。</li>
 *       </ul>
 *       实现侧用 {@code coverUsable} 局部标记逐条复刻这条可达性（见 {@code discover()} 内注释）。（对应测试：
 *       {@code QiShuiTest} 的"发现页封面兜底"两条断言。）
 *       <b>教训</b>：报"上游缺陷/死分支"之前必须把变量在所有路径上穷举一遍；
 *       漏报只是少一条信息，误报会让后来者把<b>其实生效</b>的分支删掉。</li>
 * </ol>
 */
public class QiShuiPlain extends BaseSpider {

    private static final String TAG = "QiShuiPlain";

    // ── 协议常量 ─────────────────────────────────────────────

    /** 接口根。 */
    public static final String BASE_URL = "https://api.qishui.com";
    /** 抖音视频播放接口。 */
    public static final String AWEME_PLAY_URL = "https://aweme.snssdk.com/aweme/v1/play/";
    /** 请求 UA（写死成 TVBox 伪装）。 */
    public static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; TVBox) AppleWebKit/537.36 Chrome/124 Safari/537.36";
    /** Accept 头。 */
    public static final String ACCEPT = "application/json, text/plain, */*";
    /** Referer 头。 */
    public static final String REFERER = "https://music.douyin.com/";
    /** App 展示名。 */
    public static final String APP_NAME = "汽水音乐";
    /**
     * 上游引用的 protobuf 枚举常量 {@code DescriptorProtos.Edition.EDITION_2023_VALUE}。
     *
     * <p>它在 {@code descriptor.proto} 的取值为 <b>1000</b>，而且是常量内联进 DEX 的纯数字，
     * 与 protobuf 运行时无关。本项目 stubs 里没有 {@code com.google.protobuf.*}，
     * 所以这里手写字面量。用法只有"除以它"两处：
     * <ul>
     *   <li>{@code C(bitrate, i)}：{@code bitrate / 1000} → kbps</li>
     *   <li>{@code detailContent}：{@code duration / 1000} → 秒</li>
     * </ul>
     * 两处语义都证实了 1000（毫秒→秒、bit/s→kbit/s）。
     */
    public static final int EDITION_2023_VALUE = 1000;
    /** 每页条数（搜索、听抖音、听歌模式列表都用 20）。 */
    public static final int PAGE_SIZE = 20;
    /** 歌词截断长度（上游 {@code > 1800} 就截）。 */
    public static final int LYRIC_LIMIT = 1800;
    /** 发现页描述截断长度。 */
    public static final int DESC_LIMIT = 80;
    /** 取清晰度时最多回查几首歌（上游 {@code min(3, size)}）。 */
    public static final int QUALITY_PROBE_LIMIT = 3;
    /** 发现页额外拼接的 mix 区块次数（上游固定 2 次）。 */
    public static final int DISCOVER_MIX_TIMES = 2;
    /** 发现页 mix 请求的 count。 */
    public static final int DISCOVER_MIX_COUNT = 50;
    /** 听歌模式/播放列表的固定图标。 */
    public static final String MODE_ICON =
            "https://is1-ssl.mzstatic.com/image/thumb/Purple211/v4/23/19/5a/"
                    + "23195ac8-3fbf-d33b-16ed-20854f9afd35/AppIcon-0-0-1x_U007epad-0-1-0-85-220.png"
                    + "/512x512bb.jpg";

    /** 分类页特殊 id：听歌模式。 */
    public static final String CATE_HOME = "home";
    /** 分类页特殊 id：发现。 */
    public static final String CATE_DISCOVER = "discover";
    /** 分类页特殊 id：听抖音（唯一带筛选的分类）。 */
    public static final String CATE_LISTEN_VIDEO = "listen_video";
    /** 筛选键名。 */
    public static final String FILTER_KEY = "cate";
    /** 默认模式的榜单 id（上游魔数）。 */
    public static final String CHART_DEFAULT_MODE = "7036274230471712007";
    /** 熟悉模式的电台 id。 */
    public static final String RADIO_FAMILIAR_MODE = "7408841207276748809";
    /** 新鲜模式的榜单 id。 */
    public static final String CHART_FRESH_MODE = "7060812597884869927";

    /** id 前缀：听歌模式。 */
    public static final String PREFIX_MODE = "mode";
    /** id 前缀：歌单/书架。 */
    public static final String PREFIX_COLLECTION = "collection";
    /** id 前缀：听抖音视频。 */
    public static final String PREFIX_VIDEO = "video";
    /** 播放 id 前缀：抖音视频（直链）。 */
    public static final String PLAY_PREFIX_VIDEO = "v:";
    /** 播放 id 前缀：音频（需查清晰度）。 */
    public static final String PLAY_PREFIX_TRACK = "a:";
    /** 播放 id 的默认清晰度键。 */
    public static final String QUALITY_AUTO = "auto";
    /** "_" 集合键分隔符（上游 {@code u.a(type, ":", id)}）。 */
    public static final String DEDUP_SEP = ":";

    /** 20 个"听抖音"分类：{名称, 服务端分类 id}。 */
    public static final String[][] VIDEO_CATEGORIES = {
            {"推荐", "0"},
            {"小说", "7517124833219647498"},
            {"音乐", "7475643857839036443"},
            {"现言", "7532767202778847282"},
            {"爽文", "7532779130690492426"},
            {"助眠", "7425531925765429274"},
            {"脱口秀", "7446747083388524571"},
            {"男频", "7532758933284804618"},
            {"女频", "7532763414768146458"},
            {"影视解说", "7425531055719716915"},
            {"相声", "7426586511761954843"},
            {"历史", "7425531262276229158"},
            {"社会时政", "7517125056763320329"},
            {"科普", "7425531697234319397"},
            {"财经", "7425531430040328218"},
            {"职场", "7446749038886688778"},
            {"悬疑", "7532766138716510235"},
            {"重生", "7532768064795465774"},
            {"脑洞", "7532768160219568179"},
            {"穿越", "7532767797405112347"},
    };

    /**
     * 48 个听歌模式的"道序表"。
     *
     * <p>它不参与接口请求，只用来把接口下发的模式列表排成人类熟悉的顺序；
     * 表里没有的模式统一排到末尾（上游把缺失值取成 {@code map.size()}）。
     */
    public static final String[] MODE_ORDER = {
            "默认模式", "熟悉模式", "新鲜模式", "图书馆", "专注模式", "摸鱼", "深夜EMO", "DJ模式",
            "助眠模式", "动感健身", "抖音漫游", "洗澡", "Chill放松", "快乐时光", "电音", "好运",
            "粤语", "通勤必听", "失恋必听", "躺平", "欧美", "打扫", "国风", "打游戏",
            "驾车", "说唱", "沉浸0.8x", "夜晚", "治愈", "轻音乐", "小酒馆", "KTV必点",
            "起床", "浪漫情歌", "摇滚", "R&B", "佛系时间", "怀旧老歌", "民谣", "甜美女声",
            "K-pop", "日语", "旅行", "儿歌", "雨天", "海边", "乡村", "古典",
    };

    /** 音质分档阈值：kbps &gt;= 900 无损。 */
    public static final int QUALITY_LOSSLESS_KBPS = 900;
    /** 音质分档阈值：kbps &gt;= 190 极高。 */
    public static final int QUALITY_HIGH_KBPS = 190;
    /** 音质分档阈值：kbps &gt;= 96 标准。 */
    public static final int QUALITY_STANDARD_KBPS = 96;

    /** 按码率降序比较（等价上游 {@code merge.J.d}）。 */
    private static final Comparator<JSONObject> BY_BITRATE_DESC = new Comparator<JSONObject>() {
        @Override
        public int compare(JSONObject a, JSONObject b) {
            int rb = b.optInt("bitrate");
            int ra = a.optInt("bitrate");
            if (ra == rb) {
                return 0;
            }
            return rb < ra ? -1 : 1;
        }
    };

    // ── 传输 seam ────────────────────────────────────────────

    /**
     * 可覆写的 GET。
     *
     * @param url     完整 URL
     * @param headers 请求头
     * @return 响应文本；失败为空串
     */
    public String fetch(String url, Map<String, String> headers) {
        return http.get(url, headers);
    }

    /**
     * 可覆写的 POST。
     *
     * @param url     完整 URL
     * @param body    请求体文本
     * @param headers 请求头
     * @return 响应文本；失败为空串
     */
    public String fetchPost(String url, String body, Map<String, String> headers) {
        return http.post(url, body, headers);
    }

    /**
     * 上游 {@code k()}：接口公共请求头。
     *
     * @return 新的可变 Map
     */
    public Map<String, String> apiHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", USER_AGENT);
        h.put("Accept", ACCEPT);
        h.put("Referer", REFERER);
        return h;
    }

    // ── 网络封装（上游 a / b） ──────────────────────────────

    /**
     * 上游 {@code a(String)}：GET 并解析 JSON。
     *
     * @param path 形如 {@code /luna/...}
     * @return JSON；任何失败返回空对象
     */
    public JSONObject getJson(String path) {
        try {
            String body = fetch(BASE_URL + path, apiHeaders());
            return TextUtils.isEmpty(body) ? new JSONObject() : new JSONObject(body);
        } catch (Exception e) {
            android.util.Log.w(TAG, "getJson failed: " + path + " " + e);
            return new JSONObject();
        }
    }

    /**
     * 上游 {@code b(String,String,JSONObject)}：POST JSON 并解析。
     *
     * @param path  形如 {@code /luna/...}
     * @param query 查询串（可为 null）
     * @param body  请求体
     * @return JSON；任何失败返回空对象
     */
    public JSONObject postJson(String path, String query, JSONObject body) {
        try {
            String url = BASE_URL + path;
            if (!TextUtils.isEmpty(query)) {
                url = url + "?" + query;
            }
            Map<String, String> headers = apiHeaders();
            headers.put("Content-Type", "application/json; charset=utf-8");
            String resp = fetchPost(url, body == null ? "{}" : body.toString(), headers);
            return TextUtils.isEmpty(resp) ? new JSONObject() : new JSONObject(resp);
        } catch (Exception e) {
            android.util.Log.w(TAG, "postJson failed: " + path + " " + e);
            return new JSONObject();
        }
    }

    // ── 五个核心方法 ─────────────────────────────────────────

    /**
     * 首页：3 个分类 + "听抖音"的 20 个分类筛选。
     *
     * <p>上游 {@code homeContent(boolean)}。注意上游在这里<b>不发任何请求</b>。
     *
     * @param filter 宿主是否要筛选
     * @return 标准首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        VodBuilder vb = VodBuilder.create();
        vb.clazz(CATE_HOME, "模式");
        vb.clazz(CATE_DISCOVER, "发现");
        vb.clazz(CATE_LISTEN_VIDEO, "听抖音");
        JSONArray values = new JSONArray();
        try {
            for (String[] c : VIDEO_CATEGORIES) {
                values.put(new JSONObject().put("n", c[0]).put("v", c[1]));
            }
            JSONArray group = new JSONArray();
            group.put(new JSONObject().put("key", FILTER_KEY).put("name", "分类").put("value", values));
            vb.filter(CATE_LISTEN_VIDEO, group);
        } catch (Exception e) {
            android.util.Log.e(TAG, "homeContent filter failed", e);
        }
        return vb.homeContent();
    }

    /**
     * 首页视频：等价于"听歌模式"第 1 页（上游 {@code homeVideoContent()} → {@code l(1)}）。
     *
     * @return 标准列表 JSON
     */
    @Override
    public String homeVideoContent() {
        try {
            return modeList(1);
        } catch (Exception e) {
            android.util.Log.w(TAG, "homeVideoContent failed", e);
            return page(false, 1, PAGE_SIZE, new ArrayList<Vod>());
        }
    }

    /**
     * 分类（上游 {@code categoryContent}）。
     *
     * <p>三个特殊 id 走不同分支：{@code home} → 听歌模式；{@code discover} → 发现；
     * 其余走"听抖音"搜索，分类 id 从 {@code extend.cate} 取（缺省 "0" = 推荐）。
     *
     * @param typeId 分类 id
     * @param pg     页码字符串
     * @param filter 宿主是否要筛选
     * @param extend 筛选值
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String typeId, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        try {
            if (CATE_HOME.equals(String.valueOf(typeId))) {
                return modeList(page);
            }
            if (CATE_DISCOVER.equals(String.valueOf(typeId))) {
                return discover(page);
            }
            String cate = "0";
            if (extend != null && !TextUtils.isEmpty(extend.get(FILTER_KEY))) {
                cate = extend.get(FILTER_KEY);
            }
            return searchVideo(page, cate);
        } catch (Exception e) {
            android.util.Log.w(TAG, "categoryContent failed", e);
            return page(false, page, PAGE_SIZE, new ArrayList<Vod>());
        }
    }

    /**
     * 详情（上游 {@code detailContent}）。
     *
     * <p>靠 id 前缀分流：{@code video:} → 抖音视频；{@code collection:} → 歌单/书架；
     * {@code mode:} → 听歌模式；其余（裸数字 id）→ 单曲详情（先查 seo_track，
     * 再回落 {@code /luna/h5/seo_track} 的清晰度表）。
     *
     * @param ids id 列表（只用第一个）
     * @return 标准详情 JSON；空入参返回 {@code {"list":[]}}
     */
    @Override
    public String detailContent(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return empty();
        }
        String id = ids.get(0);
        try {
            JSONObject video = decodeId(id, PREFIX_VIDEO);
            if (video != null) {
                return VodBuilder.create().add(videoToVod(id, video)).detailContent();
            }
            JSONObject collection = decodeId(id, PREFIX_COLLECTION);
            if (collection != null) {
                String kind = collection.optString("kind");
                List<JSONObject> tracks;
                if ("radio".equals(kind)) {
                    tracks = radioTracks(collection.optString("id"));
                } else if ("brief_chart".equals(kind) || "chart".equals(kind)) {
                    tracks = chartTracks(collection.optString("id"));
                } else if ("playlist".equals(kind)) {
                    tracks = playlistTracks(collection.optString("id"));
                } else {
                    tracks = new ArrayList<>();
                }
                if (tracks.isEmpty()) {
                    tracks = searchTracks(1, firstNonEmpty(collection.optString("name"), "热门歌曲"));
                }
                return VodBuilder.create().add(collectionVod(id, tracks, collection)).detailContent();
            }
            JSONObject mode = decodeId(id, PREFIX_MODE);
            if (mode == null) {
                return VodBuilder.create().add(songDetail(id)).detailContent();
            }
            String modeId = mode.optString("id");
            List<JSONObject> tracks;
            if ("default".equals(modeId)) {
                tracks = chartTracks(CHART_DEFAULT_MODE);
            } else if ("familiar".equals(modeId)) {
                tracks = radioTracks(RADIO_FAMILIAR_MODE);
            } else if ("fresh".equals(modeId)) {
                tracks = chartTracks(CHART_FRESH_MODE);
            } else {
                tracks = searchTracks(1, firstNonEmpty(mode.optString("name"), "热门歌曲"));
            }
            return VodBuilder.create().add(modeToVod(id, tracks, mode)).detailContent();
        } catch (Exception e) {
            android.util.Log.w(TAG, "detailContent failed: " + id + " " + e);
            return VodBuilder.create().add(new Vod()
                    .id(id).name(APP_NAME).pic("").remarks(APP_NAME)
                    .typeName(APP_NAME)
                    .playFrom(APP_NAME)
                    .playUrl(episodeLabel("a:" + id + ":" + QUALITY_AUTO))).detailContent();
        }
    }

    /**
     * 搜索（两参数重载）。
     *
     * @param key   关键词
     * @param quick 宿主快速搜索标志（忽略）
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    /**
     * 搜索（上游三参数重载）：搜曲目 {@code /luna/search/track}。
     *
     * @param key      关键词
     * @param quick    宿主快速搜索标志
     * @param pageStr  页码字符串
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick, String pageStr) {
        if (TextUtils.isEmpty(key)) {
            return empty();
        }
        int page = pageNum(pageStr, 1);
        try {
            Object[] result = searchTrack(page, key.trim());
            List<JSONObject> tracks = castTracks(result[1]);
            List<Vod> out = new ArrayList<>();
            for (JSONObject t : tracks) {
                Vod v = songToVod(t);
                if (v != null) {
                    out.add(v);
                }
            }
            return page(castBoolean(result[0]), page, PAGE_SIZE, out);
        } catch (Exception e) {
            android.util.Log.w(TAG, "searchContent failed", e);
            return page(false, 1, PAGE_SIZE, new ArrayList<Vod>());
        }
    }

    /**
     * 播放（上游 {@code playerContent}）。
     *
     * <p>三条分支：{@code v:&lt;video_id&gt;} → 拼 aweme 直链；
     * {@code a:&lt;track_id&gt;:&lt;qualityKey&gt;} → 查清晰度表取 url；
     * 其余 → 当裸 track id，等价 {@code a:&lt;id&gt;:auto}。
     *
     * @param flag     播放源标识（忽略）
     * @param id       播放 id
     * @param vipFlags 需解析的标识集合（忽略）
     * @return 播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String playId = id == null ? "" : id;
        try {
            if (playId.startsWith(PLAY_PREFIX_VIDEO)) {
                String url = AWEME_PLAY_URL + "?video_id="
                        + URLEncoder.encode(playId.substring(2), "UTF-8") + "&ratio=1080p&line=0";
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", USER_AGENT);
                return PlayerResponse.build(url, 0, headers);
            }
            String trackId;
            String qualityKey = QUALITY_AUTO;
            if (playId.startsWith(PLAY_PREFIX_TRACK)) {
                String[] parts = playId.split(":", 3);
                trackId = parts.length > 1 ? parts[1] : "";
                if (parts.length > 2) {
                    qualityKey = parts[2];
                }
            } else {
                trackId = playId;
            }
            List<JSONObject> qualities = qualitiesOf(trackDetail(trackId));
            if (qualities.isEmpty()) {
                return PlayerResponse.build("", 0, apiHeaders());
            }
            JSONObject picked = qualities.get(0);
            for (JSONObject q : qualities) {
                if (qualityKey.equals(q.optString("key"))) {
                    picked = q;
                    break;
                }
            }
            return PlayerResponse.build(picked.optString("url"), 0, apiHeaders());
        } catch (Exception e) {
            android.util.Log.w(TAG, "playerContent failed", e);
            return PlayerResponse.build(playId, 1, new HashMap<String, String>());
        }
    }

    // ── 数据获取（上游 A / B / e / v / x / z） ──────────────

    /**
     * 上游 {@code A(String)}：查抖音视频/歌曲的 SEO 详情。
     *
     * @param trackId 视频或曲目 id
     * @return JSON（失败为空对象）
     */
    public JSONObject trackDetail(String trackId) {
        try {
            return getJson("/luna/h5/seo_track?track_id="
                    + URLEncoder.encode(String.valueOf(trackId), "UTF-8") + "&device_platform=web");
        } catch (Exception e) {
            android.util.Log.w(TAG, "trackDetail failed", e);
            return new JSONObject();
        }
    }

    /**
     * 上游 {@code B(String)}：单曲详情（含清晰度→线路映射）。
     *
     * @param id 曲目 id
     * @return 详情 Vod，永不 null
     */
    public Vod songDetail(String id) {
        JSONObject detail = trackDetail(id);
        JSONObject track = unwrapTrack(detail.optJSONObject("seo_track"));
        if (track == null) {
            // 上游此处调用 A.a.m2c("id", id)，而该 helper 会把 "id" 当 JSON 解析 → 必抛异常，
            // 于是整段详情落到兜底。本重写按原意合成 {id: id}（详见类注释缺陷 1）。
            track = syntheticTrack(id);
        }
        Vod vod = songToVod(track);
        if (vod == null) {
            vod = new Vod().id(id).name(APP_NAME).pic("").remarks(APP_NAME);
        }
        String artists = artistsOf(track);
        JSONObject album = track.optJSONObject("album");
        List<JSONObject> qualities = qualitiesOf(detail);

        List<String> from = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        String episodeLabel = episodeLabel(track.optString("name"));
        if (qualities.isEmpty()) {
            from.add(APP_NAME);
            urls.add(episodeLabel + "$" + PLAY_PREFIX_TRACK + id + ":" + QUALITY_AUTO);
        } else {
            for (JSONObject q : qualities) {
                from.add(q.optString("label"));
                urls.add(episodeLabel + "$" + PLAY_PREFIX_TRACK + id + ":" + q.optString("key"));
            }
        }
        vod.typeName("音乐");
        vod.actor(artists);
        String albumName = firstNonEmpty(album != null ? album.optString("name") : "", "未知专辑");
        JSONObject lyric = detail.optJSONObject("lyric");
        String content = lyric != null ? lyric.optString("content") : "";
        if (content.length() > LYRIC_LIMIT) {
            content = content.substring(0, LYRIC_LIMIT);
        }
        vod.content("专辑：" + albumName + "\n" + content);
        vod.playFrom(join("$$$", from));
        vod.playUrl(join("$$$", urls));
        return vod;
    }

    /**
     * 上游 {@code e(String)}：榜单曲目。
     *
     * @param chartId 榜单 id
     * @return 曲目 JSON 列表（失败为空表）
     */
    public List<JSONObject> chartTracks(String chartId) {
        try {
            JSONObject resp = getJson("/luna/charts/" + URLEncoder.encode(String.valueOf(chartId), "UTF-8"));
            JSONObject chart = resp.optJSONObject("chart");
            JSONArray arr = null;
            if (chart != null) {
                arr = chart.optJSONArray("track_ranks");
                if (arr == null) {
                    arr = chart.optJSONArray("tracks");
                }
            }
            if (arr == null) {
                arr = resp.optJSONArray("track_ranks");
            }
            if (arr == null) {
                arr = resp.optJSONArray("tracks");
            }
            if (arr == null) {
                arr = resp.optJSONArray("items");
            }
            return unwrapTracks(arr);
        } catch (Exception e) {
            android.util.Log.w(TAG, "chartTracks failed", e);
            return new ArrayList<>();
        }
    }

    /**
     * 上游 {@code v(String)}：歌单曲目。
     *
     * @param playlistId 歌单 id
     * @return 曲目 JSON 列表
     */
    public List<JSONObject> playlistTracks(String playlistId) {
        try {
            JSONObject body = new JSONObject();
            body.put("playlist_id", String.valueOf(playlistId));
            nulled(body, "cursor");
            body.put("count", 100);
            nulled(body, "type");
            JSONObject extra = new JSONObject();
            nulled(extra, "har");
            nulled(extra, "commerce_block_pattern");
            body.put("feed_playlist_extra", extra);
            nulled(body, "sort_type");
            nulled(body, "session_id");
            body.put("reverse", false);
            body.put("ab_param", "");
            body.put("limited_free_scene", 0);
            return unwrapTracks(postJson("/luna/playlist/detail", "max_length=4194304", body)
                    .optJSONArray("media_resources"));
        } catch (Exception e) {
            android.util.Log.w(TAG, "playlistTracks failed", e);
            return new ArrayList<>();
        }
    }

    /**
     * 上游 {@code x(String)}：电台曲目。
     *
     * @param radioId 电台 id
     * @return 曲目 JSON 列表
     */
    public List<JSONObject> radioTracks(String radioId) {
        try {
            JSONObject body = new JSONObject();
            body.put("radio_id", String.valueOf(radioId));
            body.put("played_media", new JSONArray());
            JSONObject extra = new JSONObject();
            extra.put("real_groups", new JSONArray());
            body.put("feed_radio_media_extra", extra);
            body.put("flow_type", 2);
            body.put("full_media", true);
            return unwrapTracks(postJson("/luna/feed/radio/tracks", null, body).optJSONArray("items"));
        } catch (Exception e) {
            android.util.Log.w(TAG, "radioTracks failed", e);
            return new ArrayList<>();
        }
    }

    /**
     * 上游 {@code z(int,String)}：搜索曲目，返回 {@code {组对象, 曲目列表}}。
     *
     * @param page    页码
     * @param keyword 关键词
     * @return 长度 2 的数组：{@code [JSONObject 分组, List&lt;JSONObject&gt; 曲目]}
     */
    public Object[] searchTrack(int page, String keyword) {
        try {
            JSONObject body = new JSONObject();
            body.put("search_type", "track");
            body.put("q", keyword);
            if (page <= 1) {
                nulled(body, "cursor");
            } else {
                body.put("cursor", String.valueOf((page - 1) * PAGE_SIZE));
            }
            body.put("search_id", md5(keyword));
            body.put("search_method", "input");
            body.put("search_scene", "search_result");
            JSONObject group = findGroup(postJson("/luna/search/track", "device_platform=web", body)
                    .optJSONArray("result_groups"), "tracks");
            return new Object[]{group, unwrapTracks(group.optJSONArray("data"))};
        } catch (Exception e) {
            android.util.Log.w(TAG, "searchTrack failed", e);
            return new Object[]{new JSONObject(), new ArrayList<JSONObject>()};
        }
    }

    /**
     * 便捷：只要曲目列表（{@link #searchTrack} 的第二项）。
     *
     * @param page    页码
     * @param keyword 关键词
     * @return 曲目 JSON 列表
     */
    public List<JSONObject> searchTracks(int page, String keyword) {
        return castTracks(searchTrack(page, keyword)[1]);
    }

    // ── 清晰度（上游 d） ────────────────────────────────────

    /**
     * 上游 {@code d(JSONObject)}：抽取清晰度列表。
     *
     * <p>两个来源，按顺序尝试：
     * <ol>
     *   <li>{@code track_player.video_model}（字符串或对象）→ {@code video_list[]}；
     *       跳过带 {@code encrypt_info} 的条目（DRM，上游不解密）。</li>
     *   <li>上面为空时，用 {@code track_player.url_player_info} 再 GET 一次，
     *       读 {@code Result.Data.PlayInfoList[]}。</li>
     * </ol>
     * 最后按 {@code key|bitrate} 去重（保留先出现的），并按码率<b>降序</b>排列
     * —— 等价上游 {@code merge.J.d} 比较器，所以列表第一项就是"最高音质"。
     *
     * @param detail 详情 JSON（可为 null）
     * @return 清晰度列表，每项 {@code {key,label,bitrate,url}}；失败为空表
     */
    public List<JSONObject> qualitiesOf(JSONObject detail) {
        List<JSONObject> raw = new ArrayList<>();
        try {
            JSONObject player = detail == null ? null : detail.optJSONObject("track_player");
            if (player == null) {
                player = new JSONObject();
            }
            // 注意：必须用 optJSONObject 而不是 opt()+instanceof。
            // 本项目的 org.json stub 把解析出来的嵌套对象存成原始 Map，
            // opt() 会返回 Map（真 org.json 才返回 JSONObject）；optJSONObject 做了惰性包装，
            // 两种实现下都能拿到对象。上游写的是 opt()+instanceof，只在真 org.json 上成立。
            JSONObject model = player.optJSONObject("video_model");
            if (model == null) {
                Object videoModel = player.opt("video_model");
                if (videoModel instanceof String && !TextUtils.isEmpty((String) videoModel)) {
                    try {
                        model = new JSONObject((String) videoModel);
                    } catch (Exception e) {
                        model = new JSONObject();
                    }
                } else {
                    model = new JSONObject();
                }
            }
            JSONArray list = model.optJSONArray("video_list");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    if (item == null || hasEncryptInfo(item)) {
                        continue;
                    }
                    JSONObject meta = item.optJSONObject("video_meta");
                    if (meta == null) {
                        meta = new JSONObject();
                    }
                    int bitrate = meta.optInt("bitrate", 0);
                    if (bitrate <= 0) {
                        bitrate = meta.optInt("real_bitrate", 0);
                    }
                    String url = firstNonEmpty(item.optString("main_url"), item.optString("backup_url"));
                    if (TextUtils.isEmpty(url)) {
                        continue;
                    }
                    raw.add(quality(firstNonEmpty(meta.optString("quality"), String.valueOf(i)), bitrate, i, url));
                }
            }
            if (raw.isEmpty() && !TextUtils.isEmpty(player.optString("url_player_info"))) {
                String body = fetch(player.optString("url_player_info"), apiHeaders());
                if (TextUtils.isEmpty(body)) {
                    body = "{}";
                }
                JSONObject result = new JSONObject(body).optJSONObject("Result");
                JSONObject data = result != null ? result.optJSONObject("Data") : null;
                JSONArray playList = data != null ? data.optJSONArray("PlayInfoList") : null;
                if (playList != null) {
                    for (int i = 0; i < playList.length(); i++) {
                        JSONObject item = playList.optJSONObject(i);
                        if (item == null) {
                            continue;
                        }
                        int bitrate = item.optInt("Bitrate", 0);
                        String url = firstNonEmpty(item.optString("MainPlayUrl"), item.optString("BackupPlayUrl"));
                        if (TextUtils.isEmpty(url)) {
                            continue;
                        }
                        raw.add(quality(String.valueOf(i), bitrate, i, url));
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "qualitiesOf failed", e);
            return new ArrayList<>();
        }
        Map<String, JSONObject> dedup = new LinkedHashMap<>();
        for (JSONObject q : raw) {
            dedup.put(q.optString("key") + "|" + q.optInt("bitrate"), q);
        }
        List<JSONObject> out = new ArrayList<>(dedup.values());
        Collections.sort(out, BY_BITRATE_DESC);
        return out;
    }

    /**
     * 造一条清晰度记录（上游内联的 4 个 put）。
     *
     * @param key     清晰度键
     * @param bitrate 码率
     * @param index   序号（用于生成人类可读标签）
     * @param url     播放地址（主/备已择优）
     * @return JSONObject
     */
    public static JSONObject quality(String key, int bitrate, int index, String url) {
        JSONObject o = new JSONObject();
        try {
            o.put("key", key);
            o.put("label", qualityLabel(bitrate, index));
            o.put("bitrate", bitrate);
            o.put("url", url);
        } catch (Exception e) {
            android.util.Log.e(TAG, "quality build failed", e);
        }
        return o;
    }

    // ── 列表页（上游 l / g / o） ────────────────────────────

    /**
     * 上游 {@code l(int)}："听歌模式"列表。
     *
     * <p>先内置 3 个模式（默认/熟悉/新鲜），再合并接口下发的模式；
     * 按名称去重（{@link #normalizeKey} 去掉空格并转小写），
     * 最后按 {@link #MODE_ORDER} 排序。id 形如 {@code mode:&lt;base64url(json)&gt;}。
     *
     * @param page 页码
     * @return 标准列表 JSON
     */
    public String modeList(int page) {
        List<JSONObject> modes = new ArrayList<>();
        modes.add(buildRef("default", "默认模式", "preference", ""));
        modes.add(buildRef("familiar", "熟悉模式", "preference", ""));
        modes.add(buildRef("fresh", "新鲜模式", "preference", ""));
        JSONObject resp = postJson("/luna/feed/mode", null, modeBody());
        JSONArray blocks = resp.optJSONArray("feed_mode_block");
        if (blocks != null) {
            for (int i = 0; i < blocks.length(); i++) {
                JSONObject block = blocks.optJSONObject(i);
                JSONArray arr = block == null ? null : block.optJSONArray("feed_mode");
                if (arr == null) {
                    continue;
                }
                for (int k = 0; k < arr.length(); k++) {
                    JSONObject item = arr.optJSONObject(k);
                    if (item == null) {
                        continue;
                    }
                    JSONObject entity = item.optJSONObject("entity");
                    JSONObject scene = entity != null ? entity.optJSONObject("feed_scene_mode") : null;
                    String sceneId = scene != null ? scene.optString("scene_mode_id") : "";
                    String sub = scene != null ? scene.optString("sub_queue_type") : "";
                    if (TextUtils.isEmpty(sceneId)) {
                        sceneId = item.optString("text");
                    }
                    modes.add(buildRef(sceneId, firstNonEmpty(item.optString("text"), "听歌模式"), "scene", sub));
                }
            }
        }
        List<Vod> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (JSONObject mode : modes) {
            String id = mode.optString("id");
            String name = mode.optString("name");
            String key = normalizeKey(name);
            if (TextUtils.isEmpty(id) || seen.contains(key)) {
                continue;
            }
            seen.add(key);
            try {
                mode.put("pic", MODE_ICON);
            } catch (Exception e) {
                android.util.Log.e(TAG, "modeList put pic failed", e);
            }
            out.add(new Vod().id(encodeId(PREFIX_MODE, mode)).name(name).pic(MODE_ICON).remarks("听歌模式"));
        }
        Collections.sort(out, modeComparator());
        return page(false, page, Math.max(1, out.size()), out);
    }

    /**
     * 听歌模式请求体（上游 {@code l()} 内联）。
     *
     * @return JSONObject
     */
    public static JSONObject modeBody() {
        JSONObject body = new JSONObject();
        try {
            body.put("ab_param", "");
            nulled(body, "feed_extra");
        } catch (Exception e) {
            android.util.Log.e(TAG, "modeBody failed", e);
        }
        return body;
    }

    /**
     * 上游 {@code g(int)}："发现"页——把区块里的 {@code resources} 拍平成"歌单/电台"入口。
     *
     * <p>请求顺序：{@code /luna/discover} 拿 blocks，再调 {@value #DISCOVER_MIX_TIMES} 次
     * {@code /luna/discover/mix} 追加两块（标题固定 "发现"）。
     * 去重键是 {@code type:resource_id}。
     *
     * @param page 页码
     * @return 标准列表 JSON
     */
    public String discover(int page) {
        List<JSONObject> blocks = new ArrayList<>();
        JSONObject resp = postJson("/luna/discover", null, discoverBody());
        JSONArray raw = resp.optJSONArray("blocks");
        if (raw != null) {
            for (int i = 0; i < raw.length(); i++) {
                JSONObject b = raw.optJSONObject(i);
                if (b != null) {
                    blocks.add(b);
                }
            }
        }
        for (int i = 0; i < DISCOVER_MIX_TIMES; i++) {
            JSONObject mixResp = postJson("/luna/discover/mix", null, mixBody());
            JSONObject wrap = new JSONObject();
            try {
                JSONArray inner = mixResp.optJSONArray("inner_block");
                wrap.put("inner_block", inner != null ? inner : new JSONArray());
                wrap.put("title", "发现");
            } catch (Exception e) {
                android.util.Log.e(TAG, "discover wrap failed", e);
            }
            blocks.add(wrap);
        }

        List<Vod> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (JSONObject block : blocks) {
            JSONArray inner = block.optJSONArray("inner_block");
            if (inner == null && block.optJSONArray("resources") != null) {
                inner = new JSONArray().put(block);
            }
            if (inner == null) {
                continue;
            }
            for (int i = 0; i < inner.length(); i++) {
                JSONObject holder = inner.optJSONObject(i);
                JSONArray resources = holder == null ? null : holder.optJSONArray("resources");
                if (resources == null) {
                    continue;
                }
                for (int k = 0; k < resources.length(); k++) {
                    JSONObject res = resources.optJSONObject(k);
                    if (res == null) {
                        continue;
                    }
                    JSONObject style = res.optJSONObject("style");
                    if (style == null) {
                        style = new JSONObject();
                    }
                    String type = firstNonEmpty(res.optString("type"), "radio");
                    String resId = firstNonEmpty(res.optString("resource_id"),
                            holder.optString("inner_block_id"));
                    if (TextUtils.isEmpty(resId) || seen.contains(type + DEDUP_SEP + resId)) {
                        continue;
                    }
                    seen.add(type + DEDUP_SEP + resId);

                    // 封面：严格照抄上游的控制流（含它的可达性），只把取对象的写法换成
                    // optJSONObject/optJSONArray 以兼容本项目 org.json stub。
                    // coverUsable=true 表示「上游那个 objOpt 已经不是 null/NULL」→ 会挡住 playlist 兜底。
                    boolean coverUsable = false;
                    JSONObject cover = null;
                    Object rawCover = style.opt("cover_url");
                    if (rawCover == null || rawCover == JSONObject.NULL) {
                        JSONArray listObj = style.optJSONArray("cover_url_list");
                        if (listObj != null && listObj.length() > 0) {
                            Object first = listObj.opt(0);
                            if (first == null || first == JSONObject.NULL) {
                                cover = null;              // 上游 objOpt 为 NULL → playlist 兜底可达
                            } else {
                                cover = listObj.optJSONObject(0);
                                coverUsable = true;        // 非对象值时 pickImage 得空串，但兜底被挡住
                            }
                        } else {
                            cover = new JSONObject();      // 上游 new JSONObject() → 挡住兜底
                            coverUsable = true;
                        }
                    } else {
                        cover = style.optJSONObject("cover_url");
                        coverUsable = true;
                    }
                    JSONObject entity = res.optJSONObject("entity");
                    JSONObject playlist = entity != null ? entity.optJSONObject("playlist") : null;
                    if (!coverUsable && playlist != null) {
                        cover = playlist.optJSONObject("url_cover");
                    }
                    JSONObject ref = buildRef(resId,
                            firstNonEmpty(style.optString("title"), holder.optString("title"), "发现"),
                            type, "");
                    try {
                        ref.put("desc", firstNonEmpty(style.optString("desc"), block.optString("title"), "发现"));
                        ref.put("pic", pickImage(cover));
                    } catch (Exception e) {
                        // 键都是字面量，理论上不可达；真出现也只丢这两个展示字段，不影响 id
                        android.util.Log.e(TAG, "discover ref fields failed", e);
                    }
                    String desc = ref.optString("desc");
                    if (desc.length() > DESC_LIMIT) {
                        desc = desc.substring(0, DESC_LIMIT);
                    }
                    out.add(new Vod()
                            .id(encodeId(PREFIX_COLLECTION, ref))
                            .name(ref.optString("name"))
                            .pic(ref.optString("pic"))
                            .remarks(desc));
                }
            }
        }
        return page(false, page, Math.max(1, out.size()), out);
    }

    /**
     * 发现页请求体（上游 {@code g()} 内联）。
     *
     * @return JSONObject
     */
    public static JSONObject discoverBody() {
        JSONObject body = new JSONObject();
        try {
            body.put("ab_param", "");
            nulled(body, "selected_boost");
            nulled(body, "playlist_mix_param");
            nulled(body, "feed_discover_extra");
            body.put("first_request", true);
        } catch (Exception e) {
            android.util.Log.e(TAG, "discoverBody failed", e);
        }
        return body;
    }

    /**
     * 发现页 mix 请求体（上游 {@code g()} 内联）。
     *
     * @return JSONObject
     */
    public static JSONObject mixBody() {
        JSONObject body = new JSONObject();
        try {
            body.put("block_type", "discover_playlist_mix");
            body.put("sub_channel_id", 0);
            nulled(body, "latest_douyin_liked_playlist_show_ts");
            body.put("feed_discover_extra", new JSONObject());
            nulled(body, "cursor");
            body.put("count", DISCOVER_MIX_COUNT);
            nulled(body, "session_id");
            body.put("ab_param", "");
        } catch (Exception e) {
            android.util.Log.e(TAG, "mixBody failed", e);
        }
        return body;
    }

    /**
     * 上游 {@code o(int,String)}："听抖音"列表（本质是按分类名搜视频）。
     *
     * @param page   页码
     * @param cateId 分类 id（{@code 0} = 推荐）
     * @return 标准列表 JSON
     */
    public String searchVideo(int page, String cateId) {
        String name = "推荐";
        for (String[] c : VIDEO_CATEGORIES) {
            if (c[1].equals(String.valueOf(cateId))) {
                name = c[0];
                break;
            }
        }
        if ("推荐".equals(name)) {
            name = "热门视频";
        }
        JSONObject body = new JSONObject();
        List<Vod> out = new ArrayList<>();
        try {
            body.put("search_type", CATE_LISTEN_VIDEO);
            body.put("q", name);
            if (page <= 1) {
                nulled(body, "cursor");
            } else {
                body.put("cursor", String.valueOf((page - 1) * PAGE_SIZE));
            }
            body.put("search_id", md5(name + ":video"));
            body.put("search_method", "input");
            body.put("search_scene", "search_result");
            body.put("scene_name", CATE_LISTEN_VIDEO);
        } catch (Exception e) {
            android.util.Log.e(TAG, "searchVideo body failed", e);
        }
        JSONObject group = findGroup(postJson("/luna/search/listen_video", "device_platform=web", body)
                .optJSONArray("result_groups"), CATE_LISTEN_VIDEO);
        JSONArray data = group.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                JSONObject entity = item == null ? null : item.optJSONObject("entity");
                JSONObject video = entity == null ? null : entity.optJSONObject("video");
                if (video == null) {
                    continue;
                }
                String vid = video.optString("vid");
                if (TextUtils.isEmpty(vid)) {
                    JSONObject clip = video.optJSONObject("clip");
                    if (clip != null) {
                        vid = clip.optString("vid");
                    }
                }
                if (TextUtils.isEmpty(vid)) {
                    continue;
                }
                String author = "汽水视频";
                JSONArray artists = video.optJSONArray("artists");
                if (artists != null && artists.length() > 0) {
                    JSONObject first = artists.optJSONObject(0);
                    JSONObject userInfo = first == null ? null : first.optJSONObject("user_info");
                    if (userInfo != null && !TextUtils.isEmpty(userInfo.optString("nickname"))) {
                        author = userInfo.optString("nickname");
                    }
                }
                String title = firstNonEmpty(video.optString("title"), video.optString("description"), "听抖音");
                JSONObject ref = buildRef("", "", "", "");
                try {
                    ref = new JSONObject();
                    ref.put("vid", vid);
                    ref.put("video_id", video.optString("video_id"));
                    ref.put("name", title);
                    JSONObject cover = video.optJSONObject("cover_url");
                    if (cover == null) {
                        cover = video.optJSONObject("image_url");
                    }
                    ref.put("pic", pickImage(cover));
                    ref.put("author", author);
                    ref.put("duration", video.optInt("duration", 0));
                } catch (Exception e) {
                    android.util.Log.e(TAG, "searchVideo ref failed", e);
                }
                out.add(new Vod()
                        .id(encodeId(PREFIX_VIDEO, ref))
                        .name(title)
                        .pic(ref.optString("pic"))
                        .remarks(author));
            }
        }
        return page(group.optBoolean("has_more"), page, PAGE_SIZE, out);
    }

    // ── 详情构造（上游 u / video 分支） ────────────────────

    /**
     * 上游 {@code u(String,List,JSONObject)}：把曲目列表包装成"多清晰度线路"的详情。
     *
     * <p>清晰度取自<b>前 3 首歌里第一首能查到清晰度的</b>；查不到时退化为单条 "最高音质" 线路。
     *
     * @param id     详情 id
     * @param tracks 曲目列表
     * @param meta   元信息（name/pic/desc）
     * @return 详情 Vod
     */
    public Vod collectionVod(String id, List<JSONObject> tracks, JSONObject meta) {
        List<JSONObject> valid = new ArrayList<>();
        if (tracks != null) {
            for (JSONObject t : tracks) {
                if (t != null && !TextUtils.isEmpty(t.optString("id"))) {
                    valid.add(t);
                }
            }
        }
        List<JSONObject> qualities = new ArrayList<>();
        for (int i = 0; i < Math.min(QUALITY_PROBE_LIMIT, valid.size()); i++) {
            qualities = qualitiesOf(trackDetail(valid.get(i).optString("id")));
            if (!qualities.isEmpty()) {
                break;
            }
        }
        List<String> from = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        if (qualities.isEmpty()) {
            from.add("最高音质");
            List<String> eps = new ArrayList<>();
            for (JSONObject t : valid) {
                eps.add(episodeLabel(t.optString("name")) + "$" + PLAY_PREFIX_TRACK
                        + t.optString("id") + ":" + QUALITY_AUTO);
            }
            urls.add(join("#", eps));
        } else {
            for (JSONObject q : qualities) {
                from.add(q.optString("label"));
                List<String> eps = new ArrayList<>();
                for (JSONObject t : valid) {
                    eps.add(episodeLabel(t.optString("name")) + "$" + PLAY_PREFIX_TRACK
                            + t.optString("id") + ":" + q.optString("key"));
                }
                urls.add(join("#", eps));
            }
        }
        if (meta == null) {
            meta = new JSONObject();
        }
        Vod vod = new Vod()
                .id(id)
                .name(firstNonEmpty(meta.optString("name"), APP_NAME))
                .pic(meta.optString("pic"))
                .remarks(firstNonEmpty(meta.optString("desc"), APP_NAME));
        vod.content(firstNonEmpty(meta.optString("desc"), APP_NAME + " App 分类"));
        vod.playFrom(join("$$$", from));
        vod.playUrl(join("$$$", urls));
        return vod;
    }

    /**
     * 听歌模式的详情（上游 {@code detailContent} 的 mode 分支内联）。
     *
     * @param id     详情 id
     * @param tracks 曲目列表
     * @param mode   模式元信息（name/pic/desc）
     * @return 详情 Vod
     */
    public Vod modeToVod(String id, List<JSONObject> tracks, JSONObject mode) {
        JSONObject meta = new JSONObject();
        try {
            meta.put("name", mode == null ? "" : mode.optString("name"));
            meta.put("pic", mode == null ? "" : mode.optString("pic"));
            meta.put("desc", "听歌模式");
        } catch (Exception e) {
            android.util.Log.e(TAG, "modeToVod meta failed", e);
        }
        Vod vod = collectionVod(id, tracks, meta);
        vod.content(APP_NAME + " · 听歌模式");
        return vod;
    }

    /**
     * 抖音视频的详情（上游 {@code detailContent} 的 video 分支）。
     *
     * <p>时长由毫秒折算成 {@code mm:ss}，为 0 时备注写死 "听抖音"。
     *
     * @param id    详情 id
     * @param video 解码出来的 video JSON
     * @return 详情 Vod
     */
    public Vod videoToVod(String id, JSONObject video) {
        int seconds = video.optInt("duration", 0) / EDITION_2023_VALUE;
        String remarks = seconds > 0
                ? String.format("%02d:%02d", Integer.valueOf(seconds / 60), Integer.valueOf(seconds % 60))
                : "听抖音";
        Vod vod = new Vod()
                .id(id)
                .name(firstNonEmpty(video.optString("name"), "听抖音"))
                .pic(video.optString("pic"))
                .remarks(remarks);
        vod.actor(video.optString("author"));
        vod.content(firstNonEmpty(video.optString("name"), APP_NAME));
        vod.playFrom("抖音视频");
        vod.playUrl("播放$" + PLAY_PREFIX_VIDEO + video.optString("vid"));
        return vod;
    }

    // ── 分页（上游 t） ──────────────────────────────────────

    /**
     * 上游 {@code t(boolean,int,int,ArrayList)}：标准分页响应。
     *
     * <p>上游显式写 {@code page/pagecount/limit/total}，其中
     * {@code pagecount = hasMore ? page+1 : page}、
     * {@code total = size + (page-1)*limit + (hasMore ? limit : 0)}。
     * {@link VodBuilder} 只能给 page/total/limit 并按 total 反推 pagecount，
     * 所以这里用 {@link #pagingTotal} 折算 total，让<b>派生出的 pagecount 与上游完全一致</b>。
     *
     * @param hasMore 是否还有下一页
     * @param page    页码（&lt;1 按 1）
     * @param limit   每页条数
     * @param list    本页数据
     * @return 标准列表 JSON
     */
    public String page(boolean hasMore, int page, int limit, List<Vod> list) {
        int p = Math.max(1, page);
        int l = Math.max(1, limit);
        int size = list == null ? 0 : list.size();
        return VodBuilder.create().addAll(list)
                .page(p, pagingTotal(p, l, size, hasMore), l)
                .categoryContent();
    }

    /**
     * 折算 {@link VodBuilder#page(int, int, int)} 需要的 total，使派生出的 pagecount
     * 等于上游显式值 {@code hasMore ? page+1 : page}。
     *
     * <p>优先用上游公式 {@code size + (p-1)*l + (hasMore ? l : 0)}；只有当它在
     * {@link VodBuilder} 的规则下算不出目标 pagecount 时（例如"空页 + 还有下一页"），
     * 才把 total 调成能让 pagecount 正确的最小值。这样 {@code total} 在绝大多数情况下
     * 就是上游的值，只在退化情形下差 1。
     *
     * @param page    页码
     * @param limit   每页条数
     * @param size    本页条数
     * @param hasMore 是否还有下一页
     * @return 传给 VodBuilder 的 total
     */
    public static int pagingTotal(int page, int limit, int size, boolean hasMore) {
        int p = Math.max(1, page);
        int l = Math.max(1, limit);
        int total = size + (p - 1) * l + (hasMore ? l : 0);
        int wanted = hasMore ? p + 1 : p;
        if (derivedPageCount(total, l, p) != wanted) {
            total = hasMore ? p * l + 1 : (p - 1) * l + 1;
        }
        return Math.max(0, total);
    }

    /**
     * 复刻 {@link VodBuilder} 的 pagecount 推导规则（供 {@link #pagingTotal} 预判）。
     *
     * @param total 总条数
     * @param limit 每页条数
     * @param page  页码
     * @return 派生出的 pagecount
     */
    public static int derivedPageCount(int total, int limit, int page) {
        int p = Math.max(1, page);
        int l = Math.max(1, limit);
        if (total <= 0) {
            return p + 1;
        }
        int pageCount = (total + l - 1) / l;
        return Math.max(pageCount, p);
    }

    /**
     * 空列表响应（上游 {@code merge.A.a.b()} = {@code c.n(new ArrayList())}）。
     *
     * @return {@code {"list":[]}}
     */
    public String empty() {
        return VodBuilder.create().addAll(new ArrayList<Vod>()).searchContent();
    }

    // ── 纯工具（全部可单测） ────────────────────────────────

    /**
     * 上游 {@code C(int,int)}：码率 → 人类可读音质标签。
     *
     * <pre>
     *   kbps = max(0, bitrate / 1000)
     *   &gt;=900 无损音质 / &gt;=190 极高音质 / &gt;=96 标准音质 / 其他 省流音质
     *   kbps == 0 时标签后直接跟序号（如 "省流音质3"），否则跟 "(kbpsK)"
     * </pre>
     *
     * @param bitrate 码率（bit/s）
     * @param index   序号（从 0 开始）
     * @return 标签
     */
    public static String qualityLabel(int bitrate, int index) {
        int kbps = Math.max(0, bitrate / EDITION_2023_VALUE);
        String label;
        if (kbps >= QUALITY_LOSSLESS_KBPS) {
            label = "无损音质";
        } else if (kbps >= QUALITY_HIGH_KBPS) {
            label = "极高音质";
        } else {
            label = kbps >= QUALITY_STANDARD_KBPS ? "标准音质" : "省流音质";
        }
        if (kbps <= 0) {
            return label + (index + 1);
        }
        return label + "(" + kbps + "K)";
    }

    /**
     * 上游 {@code firstNonEmpty(String...)}。
     *
     * @param values 候选值
     * @return 第一个非空值；全空返回空串
     */
    public static String firstNonEmpty(String... values) {
        if (values != null) {
            for (String v : values) {
                if (!TextUtils.isEmpty(v)) {
                    return v;
                }
            }
        }
        return "";
    }

    /**
     * 上游 {@code n(String,ArrayList)}：用分隔符连接。
     *
     * @param sep   分隔符
     * @param items 元素
     * @return 连接结果；空表返回空串
     */
    public static String join(String sep, List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    /**
     * 上游 {@code r(String)}：模式名归一化（转小写 + 去掉所有空格）。
     *
     * @param s 原文
     * @return 归一化结果；null → 空串
     */
    public static String normalizeKey(String s) {
        return s == null ? "" : s.toLowerCase().replace(" ", "");
    }

    /**
     * 上游 {@code p(String)}：md5 小写 hex；失败时退化成 {@code abs(hashCode())}。
     *
     * @param s 原文
     * @return 32 字符 hex，或十进制 hash（上游的兜底行为）
     */
    public static String md5(String s) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", Byte.valueOf(b)));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(Math.abs(s.hashCode()));
        }
    }

    /**
     * 上游 {@code h(JSONObject)}：从各种包装结构里找出真正的 track 对象。
     *
     * <p>本身已经带 {@code id} 且带 {@code name|title} 就直接用；否则依次尝试
     * {@code track} / {@code entity.track_wrapper.track} / {@code track_wrapper.track}
     * / {@code entity.track} 四条路径。
     *
     * @param o 原始对象
     * @return 轨道对象；找不到返回 null
     */
    public static JSONObject unwrapTrack(JSONObject o) {
        if (o == null) {
            return null;
        }
        if (!TextUtils.isEmpty(o.optString("id"))
                && (!TextUtils.isEmpty(o.optString("name")) || !TextUtils.isEmpty(o.optString("title")))) {
            return o;
        }
        String[][] paths = {
                {"track"},
                {"entity", "track_wrapper", "track"},
                {"track_wrapper", "track"},
                {"entity", "track"},
        };
        for (String[] path : paths) {
            JSONObject cur = o;
            for (String key : path) {
                if (cur == null) {
                    break;
                }
                cur = cur.optJSONObject(key);
            }
            if (cur != null && !TextUtils.isEmpty(cur.optString("id"))) {
                return cur;
            }
        }
        return null;
    }

    /**
     * 上游 {@code f(JSONArray)}：逐个 unwrap 并丢掉 null。
     *
     * @param arr 原始数组
     * @return 轨道对象列表
     */
    public static List<JSONObject> unwrapTracks(JSONArray arr) {
        List<JSONObject> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = unwrapTrack(arr.optJSONObject(i));
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 上游 {@code c(JSONObject)}：歌手名用 " / " 连接。
     *
     * @param track 轨道对象
     * @return 歌手串；无歌手返回空串
     */
    public static String artistsOf(JSONObject track) {
        if (track == null) {
            return "";
        }
        JSONArray artists = track.optJSONArray("artists");
        if (artists == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject a = artists.optJSONObject(i);
            if (a != null) {
                String name = firstNonEmpty(a.optString("name"), a.optString("simple_display_name"));
                if (!TextUtils.isEmpty(name)) {
                    names.add(name);
                }
            }
        }
        return join(" / ", names);
    }

    /**
     * 上游 {@code i(JSONArray,String)}：找 id 匹配的分组。
     *
     * @param groups 分组数组
     * @param id     目标 id
     * @return 分组对象；找不到返回<b>空对象</b>（上游行为，不是 null）
     */
    public static JSONObject findGroup(JSONArray groups, String id) {
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g != null && id.equals(g.optString("id"))) {
                    return g;
                }
            }
        }
        return new JSONObject();
    }

    /**
     * 上游 {@code j(JSONObject)}：判断是否有加密信息（DRM）—— 有则跳过该清晰度。
     *
     * @param o 清晰度条目
     * @return true 表示带 encrypt_info 且非"空值"
     */
    public static boolean hasEncryptInfo(JSONObject o) {
        if (o == null || !o.has("encrypt_info") || o.isNull("encrypt_info")) {
            return false;
        }
        Object info = o.opt("encrypt_info");
        if (info instanceof JSONObject) {
            return ((JSONObject) info).length() > 0;
        }
        if (info instanceof java.util.Map) {
            // 本仓 JSON 桩已复刻真 org.json 的行为：put/parse 时把嵌套 Map 归一化成
            // JSONObject/JSONArray（见 ae/stubs/org/json/JSONObject.java 的 norm()）。
            // ⇒ 从 JSON 文本解析出来的 encrypt_info <b>永远走上面那一支</b>。
            // 这一支现在只在「调用方手拼 Map」时可达（测试直接 new JSONObject(map)、
            // 或宿主自己塞了一个 Map）。保留作防御，不是主路径 —— 别按主路径去"优化"它。
            return !((java.util.Map<?, ?>) info).isEmpty();
        }
        if (info instanceof String) {
            return !TextUtils.isEmpty((String) info);
        }
        if (info instanceof Boolean) {
            return ((Boolean) info).booleanValue();
        }
        if (info instanceof Number) {
            return ((Number) info).doubleValue() != 0.0d;
        }
        return info != null;
    }

    /**
     * 上游 {@code m(Object)}：从 {@code {uri,urls,template_prefix}} 结构里取封面。
     *
     * <p>特殊情况：当 {@code urls[0]} 以 {@code /img} 结尾时，
     * 用它当"图片服务前缀"，把 {@code uri} 拼在后面，再补 {@code ~&lt;template_prefix&gt;-ori.image}。
     *
     * @param o 一个 {uri,urls,...} 对象
     * @return 图片地址；不可解析返回空串
     */
    public static String pickImage(Object o) {
        JSONObject j;
        if (o instanceof JSONObject) {
            j = (JSONObject) o;
        } else if (o instanceof java.util.Map) {
            // 同上：解析出来的嵌套对象已经是 JSONObject（桩的 norm() 归一化了），
            // 走到这里意味着调用方手拼了 Map。保留作防御。
            j = new JSONObject((java.util.Map<?, ?>) o);
        } else {
            return "";
        }
        String uri = j.optString("uri");
        String value;
        JSONArray arr = j.optJSONArray("urls");
        if (arr != null) {
            value = arr.length() > 0 ? String.valueOf(arr.opt(0)) : "";
        } else {
            Object urls = j.opt("urls");
            if (urls == null || urls == JSONObject.NULL) {
                value = "";
            } else if (urls instanceof java.util.List) {
                java.util.List<?> l = (java.util.List<?>) urls;
                value = l.isEmpty() ? "" : String.valueOf(l.get(0));
            } else {
                value = String.valueOf(urls);
            }
        }
        if (TextUtils.isEmpty(value) || !value.replaceAll("/+$", "").endsWith("/img")) {
            return firstNonEmpty(value, uri);
        }
        if (TextUtils.isEmpty(uri)) {
            return "";
        }
        String base = value.replaceAll("/+$", "") + "/" + uri.replaceAll("^/+", "");
        String tpl = j.optString("template_prefix");
        if (TextUtils.isEmpty(tpl)) {
            return base;
        }
        return base + "~" + tpl + "-ori.image";
    }

    /**
     * 上游 {@code q(String,String,String,String)}：{@code {id,name,kind,sub}}。
     *
     * @param id   标识
     * @param name 名称
     * @param kind 类型（preference / scene / radio / playlist / chart / video）
     * @param sub  子参数（scene 的 sub_queue_type）
     * @return JSONObject
     */
    public static JSONObject buildRef(String id, String name, String kind, String sub) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("kind", kind);
            o.put("sub", sub == null ? "" : sub);
        } catch (Exception e) {
            android.util.Log.e(TAG, "buildRef failed", e);
        }
        return o;
    }

    /**
     * 上游 {@code s(String,JSONObject)}：{@code prefix:base64url(JSON)}。
     *
     * @param prefix 前缀（mode / collection / video）
     * @param json   载荷
     * @return 自包含 id
     */
    public static String encodeId(String prefix, JSONObject json) {
        try {
            return prefix + ":" + AndroidBase64.encodeToString(
                    json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    AndroidBase64.URL_SAFE | AndroidBase64.NO_PADDING | AndroidBase64.NO_WRAP);
        } catch (Exception e) {
            return prefix + ":";
        }
    }

    /**
     * 上游 {@code E(String,String)}：反向解出自包含 id。
     *
     * @param line   宿主给的 id
     * @param prefix 期望前缀
     * @return 载荷 JSON；前缀不匹配或解码失败返回 null
     */
    public static JSONObject decodeId(String line, String prefix) {
        try {
            String head = prefix.concat(":");
            if (line == null || !line.startsWith(head)) {
                return null;
            }
            return new JSONObject(new String(
                    AndroidBase64.decode(line.substring(head.length()),
                            AndroidBase64.URL_SAFE | AndroidBase64.NO_WRAP),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 上游 {@code y(String)}：剧集名清理（{@code $} 与 {@code #} 是 TVBox 的分隔符，必须换成空格）。
     *
     * @param name 原名
     * @return 清理后的名字；空名返回 "播放"
     */
    public static String episodeLabel(String name) {
        return TextUtils.isEmpty(name) ? "播放" : name.replace("$", " ").replace("#", " ");
    }

    /**
     * 上游 {@code w(String,JSONObject)}：把键写成 JSON 的 {@code null}。
     *
     * @param o   目标对象
     * @param key 键
     */
    public static void nulled(JSONObject o, String key) {
        try {
            o.put(key, JSONObject.NULL);
        } catch (Exception e) {
            android.util.Log.e(TAG, "nulled failed: " + key, e);
        }
    }

    /**
     * 替代上游 {@code merge.A.a.m2c("id", id)} 的"合成最小轨道对象"。
     *
     * <p>上游那次调用必抛异常（把 {@code "id"} 当 JSON 文本解析），
     * 本重写按原意合成 {@code {"id": id}}（详见类注释缺陷 1）。
     *
     * @param id 曲目 id
     * @return {@code {"id": id}}
     */
    public static JSONObject syntheticTrack(String id) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
        } catch (Exception e) {
            android.util.Log.e(TAG, "syntheticTrack failed", e);
        }
        return o;
    }

    /**
     * 上游 {@code D(JSONObject)}：轨道 JSON → 列表项 Vod。
     *
     * <p>字段：id、name（缺省 "未知歌曲"）、pic（专辑封面优先）、remarks（歌手 / 专辑 / "汽水音乐"）。
     *
     * @param o 轨道（可能是包装结构）
     * @return Vod；无有效 id 返回 null
     */
    public static Vod songToVod(JSONObject o) {
        JSONObject track = unwrapTrack(o);
        if (track == null || TextUtils.isEmpty(track.optString("id"))) {
            return null;
        }
        String artists = artistsOf(track);
        JSONObject album = track.optJSONObject("album");
        String desc = firstNonEmpty(artists, album != null ? album.optString("name") : "", APP_NAME);
        String pic = album != null ? pickImage(album.optJSONObject("url_cover")) : "";
        if (TextUtils.isEmpty(pic)) {
            pic = pickImage(track.optJSONObject("url_cover"));
        }
        return new Vod()
                .id(track.optString("id"))
                .name(firstNonEmpty(track.optString("name"), "未知歌曲"))
                .pic(pic)
                .remarks(desc);
    }

    /**
     * 按 {@link #MODE_ORDER} 排序模式（等价上游 {@code merge.J.c}）。
     *
     * @return 比较器
     */
    public static Comparator<Vod> modeComparator() {
        final Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < MODE_ORDER.length; i++) {
            order.put(normalizeKey(MODE_ORDER[i]), Integer.valueOf(i));
        }
        return new Comparator<Vod>() {
            @Override
            public int compare(Vod a, Vod b) {
                int ia = order.containsKey(normalizeKey(a.vodName))
                        ? order.get(normalizeKey(a.vodName)).intValue() : order.size();
                int ib = order.containsKey(normalizeKey(b.vodName))
                        ? order.get(normalizeKey(b.vodName)).intValue() : order.size();
                if (ia == ib) {
                    return 0;
                }
                return ia < ib ? -1 : 1;
            }
        };
    }

    /** 按码率降序排序（原地）。等价上游 {@code Collections.sort(list, new J.d())}。 */
    public static void sortByBitrateDesc(List<JSONObject> list) {
        Collections.sort(list, BY_BITRATE_DESC);
    }

    /**
     * 类型安全的取 {@code has_more}。
     *
     * @param group 分组对象
     * @return {@code has_more}
     */
    public static boolean castBoolean(Object group) {
        return group instanceof JSONObject && ((JSONObject) group).optBoolean("has_more");
    }

    /**
     * 把 {@code Object} 列表收敛成 {@code JSONObject} 列表（测试与内部调用共用）。
     *
     * @param o 任意对象
     * @return 新列表
     */
    @SuppressWarnings("unchecked")
    public static List<JSONObject> castTracks(Object o) {
        List<JSONObject> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object item : (List<Object>) o) {
                if (item instanceof JSONObject) {
                    out.add((JSONObject) item);
                }
            }
        }
        return out;
    }

    /**
     * 上游 {@code categoryContent} 里 {@code "listen_video"} 分类的筛选值（供测试直接断言）。
     *
     * @return {@code [{n,v}...]} 形式的 20 项
     */
    public static List<String[]> videoCategoryPairs() {
        List<String[]> out = new ArrayList<>();
        for (String[] c : VIDEO_CATEGORIES) {
            out.add(new String[]{c[0], c[1]});
        }
        return out;
    }
}
