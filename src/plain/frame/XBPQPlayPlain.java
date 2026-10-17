package com.example.tvbox.plain.frame;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XBPQ 播放入口 —— 把「任意一个地址」交给播放器，并自动挂上同目录/同名的字幕。
 *
 * <p><b>出发点是一个地址，不是一个站</b>：这个插件没有首页、没有分类、不搜索。
 * 它的 {@code detailContent} 收到的 id 本身就是：
 * <ul>
 *   <li>本地文件：{@code file:///storage/.../movie.mkv}</li>
 *   <li>远程直链：{@code https://.../movie.mp4}</li>
 *   <li>磁力/ed2k/ftp/thunder 等：原样透传</li>
 *   <li>B 站/爱奇艺/腾讯等站点页：交给外部解析</li>
 * </ul>
 * 然后按地址类型给出三档播放方式：<b>解析 / 嗅探 / 直连</b>。
 *
 * <h2>⚠️ 父类悬空（必须说明）</h2>
 * 上游字节码里 {@code XBPQPlay} 的父类是 {@code spider/Spider}（记作 {@code .super spider/Spider}），
 * <b>该符号在本 dex 及其依赖里不存在</b> —— 也就是说上游这个类在运行时一加载就会
 * {@code NoClassDefFoundError}，是包内不可用的死类。
 * 因此本实现直接继承本工程的 {@link BaseSpider}（{@code crawler.Spider} 的子类），
 * 语义上等价于「继承宿主 Spider」，并顺带拿到 {@code ext}/{@code http} 基础设施。
 * <b>这也意味着：本类无法与上游做运行时行为对比，只能做静态语义还原。</b>
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  detailContent(id):
 *     s = id.trim()
 *     若含 "magnet" → 用正则抽出 magnet:?xt=urn:btih:[40hex]（丢弃后面的 &amp;dn= 等）
 *     flags: 站点页 → 解析/嗅探/直链 ；直链或磁力 → 直连/嗅探/解析 ；其它 → 嗅探/解析/直连
 *     vod_play_from = flags.join("$$$")
 *     vod_play_url  = ["播放$"+s] × 3   （三个线路都是同一个地址，靠 flag 分流）
 *
 *  playerContent(flag, url):
 *    "直连" → {"url":url, "subs":[同目录/同名字幕]}
 *    "嗅探" → {"parse":1, "url":url}
 *    "解析" → {"parse":1, "jx":1, "url":url}
 *    其它   → {}                 （上游就是空对象）
 *    youtube.com 特判 → 走外部 Youtube.fetch（本工程不可用，见下）
 * </pre>
 *
 * <h2>字幕发现规则（{@code a(String)}）</h2>
 * <ul>
 *   <li><b>本地</b>（{@code file://}）：列出<b>父目录</b>所有文件，取扩展名是
 *       {@code srt/ass/ssa} 的，条目名 = 文件名去扩展名，URL = {@code file://+绝对路径}。
 *       <b>注意：不做同名匹配</b> —— 父目录里任意字幕都会被挂上（上游行为）</li>
 *   <li><b>远程</b>（{@code http://}）：仅当主地址扩展名是 {@code mp4/mkv} 时，
 *       依次探测 {@code <去扩展名>.srt} 与 {@code <.ass>}，HTTP 200 才收录同一域名下的地址</li>
 * </ul>
 *
 * <h2>上游缺陷（含 jadx 反编译损坏点）</h2>
 * <ol>
 *   <li><b>站点域名判断被 jadx 反编译成了空循环</b>：{@code m.d(url)} 原文是一个
 *       {@code for} + 空 {@code if} 体 + {@code z=false}，实际应为
 *       {@code if (host.contains(domain) && !(是爱奇艺且不是 a_/w_/v_ 页)) return true;}。
 *       本实现按<b>原意</b>修复，并保留爱奇艺的 {@code /a_ /w_ /v_} 白名单特例。
 *       未修复时所有站点页都会落到"嗅探"档，解析档永远走不到。</li>
 *   <li><b>本地字幕不判同名</b>：{@code file://} 场景下父目录里任何字幕都会被挂上，
 *       一个目录放多集就会串字幕。本实现保留行为（避免与上游播放结果不一致），
 *       但在 {@link #episodesOfFile(String)} 的 javadoc 里标注，并提供
 *       {@link #setStrictSubtitleMatch(boolean)} 开关。</li>
 *   <li><b>{@code http://} 才探测字幕，{@code https://} 不探</b>：上游只判断
 *       {@code str.startsWith("http://")}，HTTPS 直链永远拿不到字幕。本实现同时接受
 *       {@code https://}（增强，不影响 http 行为）。</li>
 * </ol>
 *
 * <h2>未覆盖的分支</h2>
 * <ul>
 *   <li>{@code youtube.com} 特判：上游调 {@code com.github.catvod.spider.Youtube.fetch(url)}，
 *       那是另一个含签名/播放列表解析的类，不在本次重写范围。本实现保留分支与日志，
 *       返回空播放对象而不是伪造地址。</li>
 *   <li>磁力的真正下载/转码：上游也是把这串交给宿主，插件侧不处理。</li>
 * </ul>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/XBPQPlay.java}(130 行)；
 * 私有库: {@code ae/work/w-pan/helpers/xbpq/o.java}（{@code C0.j.o} 扩展名/体积/时间工具）、
 * {@code ae/work/w-pan/helpers/xbpq/m.java}（{@code C0.j.m} 直链与站点判定正则）、
 * {@code ae/work/w-pan/helpers/xbpq/{d,g,e,b,n}.java}（{@code C0.c.d/g/e} 三个 bean、
 * {@code C0.h.b} HTTP、{@code C0.b.n} 字符串拼接），字面量与 DEX 字符串池逐字一致。
 */
public class XBPQPlayPlain extends BaseSpider {

    private static final String TAG = "XBPQPlayPlain";

    /** 播放源名（上游 {@code gVar.a("XBPQ")}） */
    public static final String SITE_NAME = "XBPQ";
    /** 默认封面（上游硬编码） */
    public static final String DEFAULT_PIC =
            "https://pic.rmb.bdstatic.com/bjh/1d0b02d0f57f0a42201f92caba5107ed.jpeg";
    /** 剧集名前缀（上游 {@code n.a("播放$", url)}） */
    public static final String PLAY_PREFIX = "播放$";

    /** 三档播放来源名（与 {@code playerContent} 的分支串一致） */
    public static final String FLAG_PARSE = "解析";
    public static final String FLAG_SNIFF = "嗅探";
    public static final String FLAG_DIRECT = "直连";

    /**
     * 站点页分支里上游写的第三个线路名 —— 注意是「直链」不是「直连」。
     *
     * <p><b>这是上游的错字</b>：{@code playerContent} 只比较 {@code "直连"}，
     * 所以站点页的第三条线路永远落进 default 分支、返回空播放对象（死线路）。
     * 本实现<b>保留错字</b>以维持与上游一致的可观测行为；要修的话把它换成
     * {@link #FLAG_DIRECT} 即可（会改变宿主看到的线路名，故不默认改）。
     */
    public static final String FLAG_DIRECT_TYPO = "直链";

    /** 字幕扩展名 */
    public static final List<String> SUBTITLE_EXTS = Arrays.asList("srt", "ass", "ssa");

    /** 远程探测字幕时允许的主地址扩展名 */
    public static final List<String> PROBE_MAIN_EXTS = Arrays.asList("mp4", "mkv");

    /**
     * 站点页判定域名 —— 逐字来自 {@code C0.j.m.d()}。
     *
     * <p>判断依据是 host 包含其一，且爱奇艺要额外带 {@code /a_ /w_ /v_} 才算详情页。
     */
    public static final String[] SITE_DOMAINS = {
            "iqiyi.com", "v.qq.com", "youku.com", "le.com", "tudou.com", "mgtv.com",
            "sohu.com", "acfun.cn", "bilibili.com", "baofeng.com", "pptv.com", "1905.com"
    };

    /** 直链判定正则 —— 逐字来自 {@code C0.j.m.a} */
    private static final Pattern RE_DIRECT = Pattern.compile(
            "http((?!http).)*?default\\.365yg\\.com/.*"
          + "|http\\S{12,}?\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3)"
          + "|http\\S{12,}?\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3)\\?\\S*"
          + "|http\\S{12,}?m3u8\\S*"
          + "|http((?!http).)*?default\\.ixigua\\.com/.*"
          + "|http((?!http).)*?dycdn-tos\\.pstatp[^\\?]*"
          + "|http.*?/play.{0,3}\\?[^url]{2,8}=.*"
          + "|http.*?/player/.*?[pP]lay\\.php\\?url=.*"
          + "|http.*?/download.aspx\\?.*"
          + "|http.*?/api/up_api.php\\?.*"
          + "|https.*?\\.66yk\\.cn.*"
          + "|http((?!http).)*?netease\\.com/file/.*"
          + "|http((?!http).)*?douyin\\.com/.*/play/\\?.*"
          + "|http((?!http).)*?huoshan\\.com/.*/\\?item.*"
          + "|magnet:.*|ed2k:.*|ftp:.*|file:.*|tvbox-xg:.*|thunder:.*|push:.*");

    /** 磁力范式提取（上游 {@code replaceAll(...,"$1")}） */
    private static final Pattern RE_MAGNET = Pattern.compile(
            "[\\S\\s]*(magnet:\\?xt=urn:btih:[0-9a-fA-F]{40})[\\S\\s]*");

    /** 严格同名字幕模式（默认关，保持上游行为） */
    private boolean strictSubtitle = false;

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        // 本类不需要站点地址（输入是地址而不是站），
        // BaseSpider 对空 url 会打 error 日志，这里补一句解释避免误判为配置错误。
        Log.i(TAG, "init: XBPQPlay 是「地址→播放器」型插件，不需要 ext.url");
    }

    /** 打开/关闭"字幕必须与主文件同名"（默认关闭 = 上游行为） */
    public void setStrictSubtitleMatch(boolean strict) {
        this.strictSubtitle = strict;
    }

    // ─────────────────────────────────────────────────────────────
    // 站点方法
    // ─────────────────────────────────────────────────────────────

    /** 无首页 */
    @Override
    public String homeContent(boolean filter) {
        return emptyResponse();
    }

    /** 无分类 */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  java.util.HashMap<String, String> extend) {
        return emptyResponse();
    }

    /** 无搜索 */
    @Override
    public String searchContent(String key, boolean quick) {
        return emptyResponse();
    }

    /**
     * 详情 —— 还原 {@code XBPQPlay.detailContent(List)}。
     *
     * <p>input 就是一个地址；输出三条同名线路（解析/嗅探/直连），靠 flag 分流。
     */
    @Override
    public String detailContent(List<String> ids) {
        String raw = firstId(ids).trim();
        if (raw.isEmpty()) {
            return emptyResponse();
        }
        String url = normalizeMagnet(raw);
        Vod v = new Vod()
                .id(url)
                .name(url)
                .typeName(SITE_NAME)
                .pic(DEFAULT_PIC)
                .playFrom(TextUtils.join(Vod.SEP_SOURCE, flagsFor(url)))
                .playUrl(TextUtils.join(Vod.SEP_SOURCE,
                        Arrays.asList(PLAY_PREFIX + url, PLAY_PREFIX + url, PLAY_PREFIX + url)));
        return VodBuilder.detail(v).detailContent();
    }

    /**
     * 播放 —— 还原 {@code XBPQPlay.playerContent(String,String,List)} 的四分支。
     *
     * @param flag 线路名：{@code 直连 / 嗅探 / 解析 / 其它}
     * @param id   地址
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            if (id == null) {
                return addDanmaku(playJson(0, 0, "", null));
            }
            if (id.contains("youtube.com")) {
                // 上游调 Youtube.fetch(id)。该类不在本次重写范围 → 保留分支，返回空播放对象。
                Log.w(TAG, "playerContent: youtube 分支需要 Youtube.fetch，本实现不覆盖");
                return addDanmaku(playJson(0, 0, "", null));
            }
            if (FLAG_DIRECT.equals(flag)) {
                return addDanmaku(playJson(0, 0, id, episodesOf(id)));
            }
            if (FLAG_SNIFF.equals(flag)) {
                return addDanmaku(playJson(1, 0, id, null));
            }
            if (FLAG_PARSE.equals(flag)) {
                return addDanmaku(playJson(1, 1, id, null));
            }
            // 上游 default 分支：返回一个"什么都没有"的播放对象
            return addDanmaku(playJson(0, 0, "", null));
        } catch (Throwable e) {
            Log.e(TAG, "playerContent failed", e);
            return addDanmaku(emptyResponse());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 纯函数：地址分类
    // ─────────────────────────────────────────────────────────────

    /**
     * 三个线路名 —— 还原 {@code b(String)} 里的 if/else 链，且修复被反编译破坏的域名判断。
     *
     * <pre>
     *   站点详情页(爱奇艺/腾讯/优酷/…/B站/1905) → 解析, 嗅探, 直链
     *   直链或磁力/ed2k/ftp/file/thunder/push → 直连, 嗅探, 解析
     *   其它                                   → 嗅探, 解析, 直连
     * </pre>
     *
     * @param url 地址
     * @return 恒为 3 个元素的列表
     */
    public static List<String> flagsFor(String url) {
        if (isMainstreamVideoSite(url)) {
            // 注意第三项是上游错字「直链」，不是 FLAG_DIRECT
            return Arrays.asList(FLAG_PARSE, FLAG_SNIFF, FLAG_DIRECT_TYPO);
        }
        if (isDirectPlayable(url) || (url != null && url.startsWith("magnet"))) {
            return Arrays.asList(FLAG_DIRECT, FLAG_SNIFF, FLAG_PARSE);
        }
        return Arrays.asList(FLAG_SNIFF, FLAG_PARSE, FLAG_DIRECT);
    }

    /**
     * 是否是"需要解析"的主流视频站详情页 —— 还原 {@code C0.j.m.d()} 的<b>原意</b>。
     *
     * <p>站点域名命中其一即算，但爱奇艺必须是 {@code /a_ /w_ /v_} 详情页。
     *
     * @param url 地址
     * @return 是否站点页
     */
    public static boolean isMainstreamVideoSite(String url) {
        if (TextUtils.isEmpty(url) || url.contains("url=http")) {
            return false;
        }
        String host = hostOf(url);
        if (host.isEmpty()) {
            return false;
        }
        for (String d : SITE_DOMAINS) {
            if (!matchesDomain(host, d)) {
                continue;
            }
            if ("iqiyi.com".equals(d)
                    && !url.contains("iqiyi.com/a_")
                    && !url.contains("iqiyi.com/w_")
                    && !url.contains("iqiyi.com/v_")) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * host 是否属于某域名 —— <b>修掉了上游 {@code contains} 的子串误判</b>。
     *
     * <p>上游写的是 {@code host.contains(domain)}，于是 {@code le.com}（乐视）会命中
     * {@code example.com}、{@code sample.com} 这类<b>以 "le.com" 结尾的无关域名</b>，
     * 把普通直链误判成"需要解析的站点页"。
     * 这里改成精确相等或点号后缀匹配：{@code host == d || host.endsWith("." + d)}。
     * 真实站点（{@code www.bilibili.com} / {@code v.qq.com} / {@code www.le.com}）
     * 判定结果不变，只是不再误伤无关域名。
     *
     * @param host   host（小写）
     * @param domain 站群域名
     * @return 是否属于该域名
     */
    public static boolean matchesDomain(String host, String domain) {
        if (host == null || domain == null || host.isEmpty() || domain.isEmpty()) {
            return false;
        }
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /**
     * 是否可直连播放 —— 还原 {@code C0.j.m.c()}。
     *
     * <p>命中直链正则即真，但 {@code cdn-tos} 上的 {@code .css/.js} 与 {@code url=http} 要排除
     * （上游防的是把页面里的资源当视频）。
     *
     * @param url 地址
     * @return 是否直连
     */
    public static boolean isDirectPlayable(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        if (!RE_DIRECT.matcher(url).find()) {
            return false;
        }
        if ((url.contains("cdn-tos") && (url.contains(".css") || url.contains(".js")))
                || url.contains("url=http")) {
            return false;
        }
        return true;
    }

    /**
     * 磁力范式归一 —— 还原 {@code detailContent} 里的
     * {@code replaceAll("[\\S\\s]*(magnet:\\?xt=urn:btih:[0-9a-fA-F]{40})[\\S\\s]*", "$1")}。
     *
     * @param s 原始输入
     * @return 归一后的 40 位磁力链接；不含 magnet 则原样返回
     */
    public static String normalizeMagnet(String s) {
        if (s == null) {
            return "";
        }
        if (!s.contains("magnet")) {
            return s;
        }
        Matcher m = RE_MAGNET.matcher(s);
        return m.matches() ? m.group(1) : s;
    }

    /** 取 host（不依赖 android.net.Uri，纯字符串解析） */
    public static String hostOf(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        try {
            int i = url.indexOf("://");
            if (i < 0) {
                return "";
            }
            String rest = url.substring(i + 3);
            int e = rest.indexOf('/');
            if (e < 0) {
                e = rest.length();
            }
            String host = rest.substring(0, e);
            int at = host.indexOf('@');
            if (at >= 0) {
                host = host.substring(at + 1);
            }
            int colon = host.indexOf(':');
            if (colon >= 0) {
                host = host.substring(0, colon);
            }
            return host.toLowerCase();
        } catch (Throwable e) {
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 纯函数：字幕发现
    // ─────────────────────────────────────────────────────────────

    /**
     * 按地址类型收集字幕 —— 还原 {@code a(String)}。
     *
     * @param url 主地址
     * @return 非 null 列表；每项 {@code {url, name, format}}
     */
    public List<String[]> episodesOf(String url) {
        if (TextUtils.isEmpty(url)) {
            return new ArrayList<>();
        }
        if (url.startsWith("file://")) {
            return episodesOfFile(url);
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return episodesOfHttp(url);
        }
        return new ArrayList<>();
    }

    /**
     * 本地文件：列<b>父目录</b>里的全部字幕 —— 还原 {@code a()} 的 file:// 分支。
     *
     * <p>⚠️ 上游<b>不判断同名</b>：父目录里任何 {@code srt/ass/ssa} 都会被挂上。
     * 需要严格同名时调用 {@link #setStrictSubtitleMatch(boolean)}。
     *
     * @param fileUrl {@code file://} 开头的地址
     * @return 字幕条目
     */
    public List<String[]> episodesOfFile(String fileUrl) {
        List<String[]> out = new ArrayList<>();
        try {
            File f = new File(fileUrl.replace("file://", ""));
            File parent = f.getParentFile();
            if (parent == null) {
                return out;
            }
            File[] files = parent.listFiles();
            if (files == null) {
                return out;
            }
            String mainBase = baseName(f.getName());
            for (File one : files) {
                if (one == null || !one.isFile()) {
                    continue;
                }
                String ext = extOf(one.getName());
                if (!SUBTITLE_EXTS.contains(ext)) {
                    continue;
                }
                if (strictSubtitle && !baseName(one.getName()).equals(mainBase)) {
                    continue;
                }
                out.add(new String[]{
                        "file://" + one.getAbsolutePath(),
                        baseName(one.getName()),
                        formatForExt(ext)});
            }
        } catch (Throwable e) {
            Log.w(TAG, "episodesOfFile failed", e);
        }
        return out;
    }

    /**
     * 远程直链：探测 {@code <去扩展名>.srt} / {@code .ass} —— 还原 {@code a()} 的 http 分支。
     *
     * <p>上游只认 {@code http://}；本实现同时认 {@code https://}（增强）。
     *
     * @param url 主地址
     * @return 字幕条目（含 HTTP 200 校验通过的）
     */
    public List<String[]> episodesOfHttp(String url) {
        List<String[]> out = new ArrayList<>();
        if (TextUtils.isEmpty(url) || !PROBE_MAIN_EXTS.contains(extOf(url))) {
            return out;
        }
        String base = baseWithUrl(url);
        for (String sub : Arrays.asList("srt", "ass")) {
            String candidate = base + "." + sub;
            try {
                Http.Result r = http.execute("GET", candidate, null, null);
                if (r == null || r.code != 200) {
                    continue;
                }
            } catch (Throwable e) {
                continue;
            }
            out.add(new String[]{candidate, lastPathSegment(candidate), formatForExt(sub)});
        }
        return out;
    }

    /**
     * 字幕 MIME —— 逐字还原 {@code C0.c.e.a(String)}。
     *
     * @param ext 扩展名
     * @return MIME；未知扩展名按 subrip
     */
    public static String formatForExt(String ext) {
        if ("ass".equals(ext) || "ssa".equals(ext)) {
            return "text/x-ssa";
        }
        if ("vtt".equals(ext)) {
            return "text/vtt";
        }
        return "application/x-subrip";
    }

    /** 扩展名（小写，无点）—— 还原 {@code C0.j.o.b()} */
    public static String extOf(String name) {
        if (name == null) {
            return "";
        }
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i + 1).toLowerCase() : "";
    }

    /** 文件名去扩展名 —— 还原 {@code C0.j.o.e()} */
    public static String baseName(String name) {
        if (name == null) {
            return "";
        }
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(0, i) : name;
    }

    /** 地址去扩展名（保留 query 之前的整体）—— 还原 {@code C0.j.o.e(str)} 用于拼 {@code .srt} */
    public static String baseWithUrl(String url) {
        if (url == null) {
            return "";
        }
        int i = url.lastIndexOf('.');
        return i >= 0 ? url.substring(0, i) : url;
    }

    /** 最后一个路径段 —— 还原 {@code Uri.getLastPathSegment()} */
    public static String lastPathSegment(String url) {
        if (url == null) {
            return "";
        }
        int q = url.indexOf('?');
        String s = q >= 0 ? url.substring(0, q) : url;
        int i = s.lastIndexOf('/');
        return i >= 0 && i < s.length() - 1 ? s.substring(i + 1) : s;
    }

    // ─────────────────────────────────────────────────────────────
    // 播放对象构造
    // ─────────────────────────────────────────────────────────────

    /**
     * 构造播放对象 JSON —— 字段与上游 {@code C0.c.d} 一一对应。
     *
     * <pre>
     *   parse=1 对应 dVar.d()；jx=1 对应 dVar.b()；url 对应 i()；subs 对应 h()
     *   Gson 默认不输出 null 字段，所以"空播放对象"就是 "{}"
     * </pre>
     *
     * @param parse 1=让宿主嗅探
     * @param jx    1=让宿主走解析
     * @param url   地址
     * @param subs  字幕数组，每项 {url,name,format}；null 表示不带 subs 字段
     * @return JSON 字符串
     */
    public static String playJson(int parse, int jx, String url, List<String[]> subs) {
        try {
            JSONObject o = new JSONObject();
            if (parse > 0) {
                o.put("parse", parse);
            }
            if (jx > 0) {
                o.put("jx", jx);
            }
            if (!TextUtils.isEmpty(url)) {
                o.put("url", url);
            }
            if (subs != null && !subs.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (String[] s : subs) {
                    JSONObject one = new JSONObject();
                    one.put("url", s[0]);
                    one.put("name", s[1]);
                    one.put("format", s[2]);
                    arr.put(one);
                }
                o.put("subs", arr);
            }
            return o.toString();
        } catch (Throwable e) {
            return emptyResponse();
        }
    }

    /**
     * 挂弹幕地址 —— 还原宿主 {@code Spider.addDanmaku(String)} 的效果。
     *
     * <p>本工程的 {@code crawler.Spider} 未提供 {@code getDanmaku()}，因此改为从 ext 读：
     * {@code ext.optStr("danmaku","")} 非空时写进 {@code danmaku} 字段，否则原样返回。
     *
     * @param json 播放对象 JSON
     * @return 追加了 danmaku 字段的 JSON
     */
    public String addDanmaku(String json) {
        String d = ext == null ? "" : ext.optStr("danmaku", "");
        if (TextUtils.isEmpty(d) || TextUtils.isEmpty(json) || !json.startsWith("{")) {
            return json;
        }
        try {
            JSONObject o = new JSONObject(json);
            o.put("danmaku", d);
            return o.toString();
        } catch (Throwable e) {
            return json;
        }
    }
}
