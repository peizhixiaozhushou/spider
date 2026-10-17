package com.example.tvbox.plain.frame;

import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Push 万能输入源 —— 粘贴任意链接自动识别类型。
 *
 * <p>这不是内容源，是一个「入口」：用户在 TVBox 里粘贴一个链接，
 * 本类判断它属于哪类，并转成对应的播放方式。
 *
 * <h2>分流规则（还原自反编译）</h2>
 * <pre>
 *  输入含网盘域名（ali/夸克/UC/天翼/123/百度/迅雷/光鸭/移动）
 *      → 交给 PanPlain.detailContent 处理
 *
 *  输入以 file:// 开头
 *      → 本地视频，播放源 "本地"
 *
 *  其他任意链接
 *      → 在线视频，提供三种播放方式: 直连 / 嗅探 / 解析
 *         vod_play_from = "直连$$$嗅探$$$解析"
 *         vod_play_url  = 同一 URL 重复三次
 * </pre>
 *
 * <h2>播放类型映射</h2>
 * <pre>
 *  直连 → parse=0（直接播放）
 *  嗅探 → parse=1（宿主嗅探）
 *  解析 → parse=2 + 需要解析（c.f() 置标志）
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Push.java}（72 行）
 
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>网盘链接的落地</b>：{@code isPanLink} 只做识别，真正的转存/播放依赖
 *       网盘域的实现（本类只把 flag 交出去）。</li>
 *   <li><b>三档播放方式的选择</b>（直连/嗅探/解析）：判定顺序按上游原文；
 *       "同一地址既是直链又是网盘分享页"这类重叠输入未覆盖。</li>
 *   <li><b>本地文件</b>：{@code isLocalFile} 认 {@code file://} 与绝对路径；
 *       真机分区存储下的可读性不在本类。</li>
 *   <li><b>协议留空是设计</b>：{@code homeContent} 返回空壳 ——
 *       本类是"入口"不是内容源（没有首页）。</li>
 * </ol>
 *
 * <h2>记录的上游缺陷 / 硬编码异味</h2>
 * <ol>
 *   <li><b>类型判定靠字符串前缀</b>：{@code http/https/magnet/ed2k/file} 之外一律
 *       当"要嗅探"，误判只会体现为"打不开"。</li>
 *   <li><b>flag 文案即协议</b>：{@code "直连"/"嗅探"/"解析"} 这些中文串既是展示名又是
 *       内部分派键（还有上游笔误 {@code "直链"} 需兼容），改文案就会改行为。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/Push.java}（分流 if-else 链、
 * 三档 flag 文案、{@code detailContent} 的地址拆解）。
*/
public class PushPlain extends BaseSpider {

    private static final String TAG = "PushPlain";

    /** 网盘域名特征（上游逐字硬编码的 10 个判断） */
    private static final String[] PAN_MARKERS = {
            "aliyundrive.com", "alipan.com", "pan.quark.cn", "uc.cn",
            ".189.", ".123", "//123", ".baidu", ".xunlei", ".guangya", ".139"
    };

    /** 播放方式标签 */
    public static final String FROM_DIRECT = "直连";
    public static final String FROM_SNIFF  = "嗅探";
    public static final String FROM_PARSE  = "解析";
    public static final String FROM_LOCAL  = "本地";

    @Override
    public String homeContent(boolean filter) { return emptyResponse(); }
    @Override public String searchContent(String key, boolean quick) { return emptyResponse(); }
    @Override public String categoryContent(String tid, String pg, boolean filter,
                                            java.util.HashMap<String, String> ext) {
        return emptyResponse();
    }

    /** 判断是否为网盘链接 —— 还原上游的 10 个 contains 判断 */
    public static boolean isPanLink(String url) {
        if (url == null) return false;
        for (String m : PAN_MARKERS) {
            if (url.contains(m)) return true;
        }
        return false;
    }

    /** 判断是否本地文件 */
    public static boolean isLocalFile(String url) {
        return url != null && url.startsWith("file://");
    }

    /**
     * 详情 —— 还原上游 {@code Push.detailContent()}。
     *
     * <p>注意：网盘链接会递归交给 {@code PanPlain}，
     * 本类不含网盘解析器，因此网盘分支实际返回空（需宿主注入）。
     */
    @Override
    public String detailContent(List<String> ids) {
        String url = firstId(ids);
        if (TextUtils.isEmpty(url)) {
            return "";
        }

        // 分支 1：网盘链接
        if (isPanLink(url)) {
            Log.i(TAG, "detailContent: pan link, delegate to pan resolver");
            // 本类不内置网盘解析器；宿主应改用 PanPlain 子类
            return emptyResponse();
        }

        // 分支 2：本地视频
        if (isLocalFile(url)) {
            Vod v = new Vod()
                    .id(url)
                    .name("本地视频")
                    .playFrom(FROM_LOCAL)
                    .content("");
            v.playUrl(url);
            return VodBuilder.detail(v).detailContent();
        }

        // 分支 3：在线视频，三路播放
        Vod v = new Vod()
                .id(url)
                .name("在线视频")
                .playFrom(FROM_DIRECT + Vod.SEP_SOURCE + FROM_SNIFF + Vod.SEP_SOURCE + FROM_PARSE)
                .content("");
        String eps = url + Vod.SEP_NAME_URL + url;
        v.playUrl(eps + Vod.SEP_SOURCE + eps + Vod.SEP_SOURCE + eps);
        return VodBuilder.detail(v).detailContent();
    }

    /**
     * 播放 —— 还原上游 {@code Push.playerContent()}。
     *
     * <p>按 flag（播放源名）决定 parse 模式：
     * 直连=0、嗅探=1、解析=2、其他=不限（空）。
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) return emptyResponse();

        int parse;
        if (FROM_DIRECT.equals(flag)) {
            parse = 0;
        } else if (FROM_SNIFF.equals(flag)) {
            parse = 1;
        } else if (FROM_PARSE.equals(flag)) {
            parse = 2;
        } else if (FROM_LOCAL.equals(flag)) {
            parse = 0;
        } else {
            // 未知 flag：只给 url，不指定 parse
            return "{\"url\":\"" + escape(id) + "\"}";
        }

        if (parse == 2) {
            // 解析模式：额外带 jx 标志（上游 c.f()）
            return "{\"parse\":" + parse + ",\"jx\":1,\"url\":\"" + escape(id) + "\"}";
        }
        return "{\"parse\":" + parse + ",\"url\":\"" + escape(id) + "\"}";
    }

    /** 简易 JSON 字符串转义 */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
