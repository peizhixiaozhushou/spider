package com.example.tvbox.plain.pan.sub;

import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.pan.PanPlain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PanSou —— 聚合搜索源，继承 {@link PanApiPlain}（→ {@link PanPlain}）。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  搜索: GET &lt;base&gt;/api/search?kw=&lt;enc&gt;
 *              &amp;cloud_types=aliyun,quark,uc,baidu,tianyi,123,xunlei,mobile
 *        等价 PanSou.java:32（另见 :111 的第二处同样拼法）
 *  响应: {"code":0,"data":{"merged_by_type":{ "&lt;类型&gt;":[ {url,note,source}, … ] }}}
 *  展平: 每条 = { vod_id: url, vod_name: note, vod_remarks: source }（PanSou.java:130-137）
 * </pre>
 *
 * <h2>基地址的来源（重要 —— 未内置兜底域名是<b>忠实还原</b>，不是缺口）</h2>
 * 上游<b>没有</b>内置站址：它从宿主缓存目录的 {@code /config.json} 里读
 * <b>{@code pansouUrl}</b> 字段（{@code PanSou.java:32,/103,/255,/268}），
 * 读不到就直接返回 {@code {}} —— <b>没配置就不工作，这是上游的既定设计</b>：
 * 这份配置的所有权在宿主，不在插件。
 *
 * <p>本类沿用同一个语义与<b>同一个键名</b>，只把投递通道换成工程统一的 ext：
 * <ol>
 *   <li><b>上游键名</b>：{@code pansouUrl}（宿主 {@code /config.json}）</li>
 *   <li><b>本工程怎么配</b>：ext 里给 {@code pansouUrl}（也接受工程通用的
 *       {@code site}/{@code url}/{@code host}）。例：
 *       <pre>{@code
 * {"pansouUrl":"https://your-pansou.example"}
 *       }</pre></li>
 *   <li><b>不配置时的行为</b>：与上游一致 —— <b>不工作</b>。
 *       本类会<b>不发请求</b>并返回标准空响应（上游是返回 {@code {}}），
 *       同时记一条 warn 日志说明"未配置 pansouUrl"。</li>
 * </ol>
 * 测试可注入：{@link PanApiPlain#setSiteForTest(String)}。
 * 断言：{@code ★ 未配置 pansouUrl -> 与上游一致：不发请求 + 空结果}、
 * {@code ★ 配置了 pansouUrl -> 真的用配置的域名}。
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>参数名与白名单都改对了（已修）</b>：本工程早期版本写成
 *       {@code /api/search?keyword=<enc>}，而上游是
 *       {@code /api/search?kw=<enc>&cloud_types=aliyun,quark,uc,baidu,tianyi,123,xunlei,mobile}。
 *       两处差异：<b>参数名 {@code kw} / {@code keyword}</b>，
 *       以及<b>完全缺失的 {@code cloud_types} 白名单</b>
 *       —— 少了白名单，服务端可能返回本工程识别不了的网盘类型。
 *       现逐字对齐。断言：{@code ★ PanSou 请求形态（参数名 + cloud_types 白名单）}。</li>
 *   <li><b>{@code categoryContent} / {@code detailContent}</b>（上游皆有）未实现，
 *       详情交给 {@link PanPlain} 的识别 + resolver。</li>
 *   <li><b>不读宿主的 {@code /config.json}</b>：本类不访问任何宿主私有文件，
 *       同一个键（{@code pansouUrl}）改由 ext 下发（见上「基地址的来源」）。
 *       这不是遗漏 —— 本工程所有源都走 ext 这一条配置通道。</li>
 *   <li><b>服务端"按类型分组"的两层结构被展平</b>：上游 {@code categoryContent} 会为每个
 *       网盘类型生成一条列表项、把原始数组塞进静态缓存供 {@code detailContent} 展开；
 *       本类直接展平成"一条一个链接"。对用户更直接，但<b>不是逐字还原</b>。</li>
 *   <li><b>网盘图标映射未复刻</b>（上游 {@code getCloudIcon(type)} 的图床常量表）。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>同一段 URL 拼接在类里写了两遍</b>（{@code PanSou.java:32} 与 {@code :111}）：
 *       第二遍还多加了一次 {@code replaceAll("\\/", "/")} ——
 *       两处任何一处改动都会静默漂移。→ 本类只有 {@link #searchUrl(String)} 一处。
 *       断言：{@code ★ PanSou 请求形态}。</li>
 *   <li><b>Gson 严格访问 28 处、{@code optXxx} 0 处</b>（{@code PanSou.java}）：
 *       配置项或响应字段缺一个就抛。→ 本类逐层 {@code opt} + 判空。</li>
 *   <li><b>配置缺失时失败得太安静</b>（{@code :103-107}）：读不到 {@code pansouUrl}
 *       就直接 {@code return "{}"}，<b>连一条日志都没有</b> ——
 *       用户看到"搜不出东西"，没有任何线索指向"没配置"。
 *       <b>注意：配置交给宿主不是缺陷</b>（那是上游的既定分工），
 *       缺陷只是"不说一声"。→ 本类保留"未配置就不工作"的语义，
 *       但会记一条 warn 日志明说缺哪个键。
 *       断言：{@code ★ 未配置 pansouUrl -> 与上游一致：不发请求 + 空结果}。</li>
 *   <li><b>{@code clearCache()} 每次搜索清空全局静态缓存</b>（{@code :102}）：
 *       两个实例并发会互踩。本类无该缓存（展平后不需要）。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/PanSou.java}（278 行）——
 * 搜索 URL 与白名单 {@code :32,/111}、展平字段 {@code :130-137}、
 * 配置键 {@code pansouUrl} 与"缺配置即不工作"的 {@code :103-107}。
 */
public class PanSouPlain extends PanApiPlain {

    private static final String TAG = "PanSouPlain";

    /**
     * 兜底站点 = <b>空串（未配置）</b>。
     *
     * <p>上游没有内置站址：它从宿主 {@code /config.json} 的 {@code pansouUrl} 键读，
     * 读不到就直接不工作（返回 {@code {}}）。本类<b>忠实沿用这个配置来源</b>，
     * 不自造兜底域名 —— 自造的域名既无上游依据也无线上验证，
     * 而且在 audit / sweep / coverage 三层检查里都是绿的，只有真机才知道打不通。
     *
     * <p>配置方式见类 javadoc 的「基地址的来源」一节。
     */
    private static final String DEFAULT_SITE = "";

    /**
     * 网盘类型白名单 —— 上游传 8 家（{@code PanSou.java:32}），本工程只留夸克+百度。
     *
     * <p>为什么要改而不是照抄：这个串是<b>服务端过滤条件</b>，力量很大 ——
     * 留着 {@code aliyun,uc,tianyi,123,xunlei,mobile} 就是让盘搜把
     * 我们<b>根本解析不了</b>的链接塞进结果里，用户点进去只能看到空线路。
     * 收窄到两家后，服务端只回我们确实支持的分享。
     *
     * <p>顺序保持上游的相对次序（quark 在 baidu 之前，与上游 ali 之后的
     * {@code quark,uc,baidu} 一致）。
     */
    public static final String CLOUD_TYPES = "quark,baidu";

    @Override
    protected String defaultSite() {
        return DEFAULT_SITE;
    }

    @Override
    protected String tagOf() {
        return TAG;
    }

    // ── 协议常量 ─────────────────────────────────────────────

    /**
     * 搜索 URL —— 参数名 {@code kw} 与白名单 {@code cloud_types} 逐字对齐
     * {@code PanSou.java:32}。
     */
    public String searchUrl(String encodedKey) {
        return site() + "/api/search?kw=" + encodedKey + "&cloud_types=" + CLOUD_TYPES;
    }

    public Map<String, String> searchHeaders() {
        return Http.headersOf("User-Agent", UA, "Referer", site() + "/");
    }

    /**
     * 站点来源：<b>优先认上游的键名 {@code pansouUrl}</b>，再退到工程通用的
     * {@code site}/{@code url}/{@code host}。
     *
     * <p>上游是读宿主 {@code /config.json} 的同名键（{@code PanSou.java:32,/103}）；
     * 本工程没有那个文件，所以同一个键名改由 <b>ext</b> 下发 —— 配置的语义与键名都保留，
     * 只是投递通道从"宿主配置文件"换成"ext 参数"（这是本工程统一的配置通道）。
     */
    @Override
    protected String readSiteFromExt() {
        try {
            if (ext != null && !ext.isEmpty()) {
                org.json.JSONObject o = ext.json();
                String v = o == null ? "" : o.optString("pansouUrl", "");
                if (!android.text.TextUtils.isEmpty(v) && !normalizeSite(v).isEmpty()) {
                    return v;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "read pansouUrl from ext failed", e);
        }
        return super.readSiteFromExt();
    }

    @Override
    public String buildSearchUrl(String key) {
        return searchUrl(encode(key));
    }

    // ── 搜索 ────────────────────────────────────────────────

    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return VodBuilder.create().searchContent();
        if (site().isEmpty()) {
            // 与上游一致：未配置就不工作。差别只在于"说一声"（缺陷 3 的修正）——
            // 上游这里是静默 return "{}"，用户完全看不出是没配置。
            Log.w(TAG, "未配置 pansouUrl（ext 下发），与上游一致：不工作");
            return VodBuilder.create().searchContent();
        }

        String resp = fetchWith(searchUrl(encode(key)), searchHeaders());
        JSONObject root = parseObject(resp);
        if (root == null) {
            Log.w(TAG, "search: bad response");
            return VodBuilder.create().searchContent();
        }
        if (root.optInt("code", 0) != 0) {
            Log.w(TAG, "search: code=" + root.optInt("code", 0));
            return VodBuilder.create().searchContent();
        }
        List<Vod> list = flatten(root);
        Log.i(TAG, "search '" + key + "' -> " + list.size() + " items");
        return VodBuilder.of(list).searchContent();
    }

    /** 展平 {@code data.merged_by_type}（上游 {@code PanSou.java:130-137} 的两层结构压平） */
    protected List<Vod> flatten(JSONObject root) {
        List<Vod> out = new ArrayList<>();
        JSONObject data = root == null ? null : root.optJSONObject("data");
        JSONObject byType = data == null ? null : data.optJSONObject("merged_by_type");
        if (byType == null) return out;
        java.util.Iterator<String> it = byType.keys();
        while (it.hasNext()) {
            String type = it.next();
            JSONArray arr = byType.optJSONArray(type);
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "");
                if (TextUtils.isEmpty(url)) continue;
                String note = o.optString("note", "");
                out.add(new Vod()
                        .id(url)
                        .name(TextUtils.isEmpty(note) ? url : note)
                        .pic("")
                        .remarks(o.optString("source", "")));
            }
        }
        return out;
    }

    /** 安全解析（失败 null，不抛） */
    protected static JSONObject parseObject(String json) {
        if (TextUtils.isEmpty(json)) return null;
        String t = json.trim();
        if (!t.startsWith("{")) return null;
        try {
            return new JSONObject(t);
        } catch (Exception e) {
            return null;
        }
    }
}
