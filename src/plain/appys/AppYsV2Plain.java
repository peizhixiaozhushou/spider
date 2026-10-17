package com.example.tvbox.plain.appys;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * AppYsV2 明文实现 —— {@link AppYsPlain} 的同族第二版。
 *
 * <p>反编译原文: {@code ae/decompiled/AppYsV2.java}（974 行）。共享部分见 {@link AppYsBase}。
 *
 * <h2>与 AppYs 的差异（只有 4 处，其余 5 个契约方法与 8 个辅助方法逐字相同）</h2>
 * <table border="1">
 *   <tr><th>#</th><th>位置</th><th>AppYs</th><th>AppYsV2（本类）</th></tr>
 *   <tr><td>1</td><td>{@code init} / 基址</td>
 *       <td>ext 整串当基址，不 trim</td>
 *       <td>{@code ext.split("###")}，{@code [0].trim()} 当基址，{@code [1]} 是模式标记
 *           （{@link #logMode()}）</td></tr>
 *   <tr><td>2</td><td>线路名</td>
 *       <td>11 条别名表 + {@code .vod} 分支取 {@code show}</td>
 *       <td>无别名表；{@code .vod} 分支优先 {@code from}、退回 {@code show}；
 *           {@code vod_url_with_player} 分支优先 {@code code}、退回 {@code name}</td></tr>
 *   <tr><td>3</td><td>解析前缀</td>
 *       <td>{@code Map<线路,单值>}；{@code .vod} 分支先用 21 条黑名单 + 「像接口」双过滤，
 *           再按 3 条站点特判改写</td>
 *       <td>{@code Map<线路,List<候选>>}；{@code .vod} 分支把 {@code parse} 与 {@code parse2}
 *           <b>直接合并</b>（不筛黑名单），逐项归一到「接口前缀」后去重</td></tr>
 *   <tr><td>4</td><td>{@code playerContent}</td>
 *       <td>拼伪 baidu 地址 + 20 余条站点分支 + 手工跟跳</td>
 *       <td>逐候选前缀请求解析接口 → {@link AppYsVideo#resolve} 规范化；
 *           都不成则返回 {@code {parse:1,jx:"1",url}}</td></tr>
 * </table>
 *
 * <h2>ext 契约</h2>
 * <p><b>B 类</b>：{@code ext = "<基址>###<模式>"}。
 * <ul>
 *   <li>{@code [0]} —— 基址（必需，缺则全站不可用）；</li>
 *   <li>{@code [1]} —— 模式标记，上游只与 {@code "nftv"} 比较且<b>丢弃结果</b>（死代码）。</li>
 * </ul>
 * 单段 ext（没有 {@code ###}）也合法，此时 {@code [1]} 不存在，{@link #logMode()} 直接返回。
 *
 * <h2>未还原的分支（诚实清单）</h2>
 * <ol>
 *   <li><b>{@code l(String)} 里的 {@code nftv} 判定</b>：上游把
 *       {@code f[1].equals("nftv")} 的结果<b>丢弃</b>（无赋值、无副作用）。
 *       本实现同样「只读不写」。如果未被 jadx 还原的字节码里对该结果另有动作，
 *       本地证据不支持，无法还原。</li>
 *   <li><b>{@code .vod} 分支里线路名的就地改写</b>：上游在同一循环里对
 *       {@code strTrim}（线路名）重新赋值，只有 id 含 {@code $} 时两版结果才不同；
 *       本实现按「每箱都用原 id」处理。</li>
 *   <li><b>跨线路的候选去重顺序</b>：本实现用 {@code addParser} 的值去重（等价），
 *       但上游是 {@code ArrayList.contains} 逐项判，插入序在混合
 *       {@code parse}/{@code parse2} 时未逐字验证。</li>
 * </ol>
 *
 * <h2>发现的上游缺陷（均已修，有断言钉住）</h2>
 * <ol>
 *   <li><b>{@code l(String)} 会因 ext 为 null 抛 NPE 并把整站打成空串</b>：
 *       {@code init} 的 {@code try/catch(Throwable)} 让 {@code this.f} 留在 null，
 *       而 {@code l()} 第一句就是 {@code strArr.length}。任何一次响应解析都会触发它。
 *       本实现的 {@link #logMode()} 先判 null。</li>
 *   <li><b>{@code l()} 是死代码</b>：{@code strArr[1].equals("nftv")} 的结果无人使用。
 *       保留为可调用钩子（基类流水线在每个响应后调用一次，与上游调用点一致）并注明。</li>
 *   <li><b>{@code playerContent} 的候选循环会吞掉「最后一次失败」</b>：上游 {@code r()}
 *       遍历完仍空则返回 {@code null}，外层直接退化成 {@code {parse:1,jx:"1"}}；
 *       本实现保留该行为，但把 {@code null} 与「空列表」两种情况都显式判掉。</li>
 * </ol>
 *
 * <h2>观察（<b>未改</b>，只记录）</h2>
 * <ul>
 *   <li>{@code r()} 命中候选时返回的播放对象<b>没有 {@code parse} 字段</b>，只有
 *       {@code {"header":"{…}","url":"…"}}。AppYs 的同位置一定会写 {@code parse}。
 *       两条可能：宿主对缺省 {@code parse} 按 0（直链）处理，或者这条路径本就是坏的。
 *       静态分析无法判定，且改动会与上游线格式不一致 —— 保持原样并在此存档。</li>
 *   <li>{@code ku.g} 在「解出来还是原地址」时才用 {@link AppYsVideo#isMainstream}
 *       与 {@link AppYsVideo#isDirect}，因此它俩在本类里只通过 {@code resolve} 间接生效。</li>
 * </ul>
 */
public class AppYsV2Plain extends AppYsBase {

    private static final String TAG = "AppYsV2Plain";

    /** ext 的分段分隔符 */
    public static final String EXT_SEPARATOR = "###";

    /** 上游 {@code l()} 用来比较、但丢弃结果的那个标记 */
    public static final String MODE_NFTV = "nftv";

    /**
     * 构造。
     */
    public AppYsV2Plain() {
        super();
    }

    // ── 分叉 1：ext 解析 ────────────────────────────────────

    /**
     * 基址 = ext 的第一段（trim 后）。
     *
     * <p>上游 {@code n()}：{@code strArr == null || strArr.length < 1 ? "" : strArr[0].trim()}。
     *
     * @param ext ext 原文
     * @return 基址
     */
    @Override
    protected String siteFromExt(String ext) {
        String[] parts = extParts;
        if (parts == null || parts.length < 1) {
            return "";
        }
        return parts[0] == null ? "" : parts[0].trim();
    }

    /**
     * ext 的第二段（模式标记）。
     *
     * @return 标记；不存在返回空串
     */
    public String mode() {
        String[] parts = extParts;
        if (parts == null || parts.length < 2) {
            return "";
        }
        return parts[1] == null ? "" : parts[1];
    }

    /**
     * 上游 {@code l(String)}（入参被忽略）。
     *
     * <p>原逻辑：{@code if (f.length > 1) f[1].equals("nftv");} —— 结果丢弃。
     * 这里补上 null 检查（上游缺它，见类 javadoc 缺陷 #1），并保留「只读模式段」的语义。
     */
    @Override
    protected void logMode() {
        String mode = mode();
        if (mode.isEmpty()) {
            return;
        }
        // 上游会与 "nftv" 比较并丢弃结果；这里保留比较以免编译期常量被优化掉，语义仍是「无副作用」。
        boolean ignored = MODE_NFTV.equals(mode);
        if (ignored) {
            Log.d(tag(), "mode=" + mode + "（上游该分支无动作）");
        }
    }

    // ── 分叉 2/3：播放源收集 ────────────────────────────────

    /**
     * {@code xgapp} 型：线路名优先 {@code code}（trim），解析前缀进候选列表。
     *
     * <p>与 AppYs 的差别：无别名表、无 mengx 替换、候选可多条。
     *
     * @param vodInfo {@code data.vod_info}
     * @param froms   线路名收集器
     * @param urls    线路地址收集器
     */
    @Override
    public void sourcesFromXgapp(JSONObject vodInfo, List<String> froms, List<String> urls) {
        JSONArray players = vodInfo.optJSONArray("vod_url_with_player");
        for (int i = 0; players != null && i < players.length(); i++) {
            JSONObject one = players.optJSONObject(i);
            if (one == null) {
                continue;
            }
            // 上游不做「名字为空就跳过」：线路名与 vod_play_url 必须逐位对齐，
            // 空名也会占一个位（addParser 内部对空前缀去重，无需在这里筛）。
            String from = lineName(one);
            froms.add(from);
            urls.add(one.optString("url"));
            addParser(from, one.optString("parse_api").trim());
        }
    }

    /**
     * {@code .vod} 型：线路名优先 {@code from}；{@code parse} + {@code parse2} 直接合并、
     * 逐项归一成「接口前缀」后去重。
     *
     * <p>上游在 {@code .vod} 分支复用了 {@code vod_url_with_player} 分支的那段「前缀归一」
     * 循环体（三处 duplicated block 警告的来源）：含 {@code http} → 取 {@code P_URL_KEY} 命中段；
     * 含 {@code //} → 补 {@code http:}；否则用基址的 {@code P_HOST}+{@code P_URL_KEY} 拼。
     * 与 {@code xgapp} 分支唯一不同是最后没有 {@code ++} 就是「不筛黑名单」。
     *
     * @param data  {@code data}
     * @param froms 线路名收集器
     * @param urls  线路地址收集器
     */
    @Override
    public void sourcesFromVod(JSONObject data, List<String> froms, List<String> urls) {
        JSONArray playList = data.optJSONArray("vod_play_list");
        for (int i = 0; playList != null && i < playList.length(); i++) {
            JSONObject one = playList.optJSONObject(i);
            if (one == null) {
                continue;
            }
            JSONObject info = one.optJSONObject("player_info");
            String from = info == null ? "" : info.optString("from").trim();
            if (from.isEmpty()) {
                from = info == null ? "" : info.optString("show").trim();
            }
            froms.add(from);
            urls.add(one.optString("url"));
            if (info == null) {
                continue;
            }
            List<String> merged = new ArrayList<>();
            merged.addAll(Arrays.asList(info.optString("parse").split(",")));
            merged.addAll(Arrays.asList(info.optString("parse2").split(",")));
            for (String raw : merged) {
                String normalized = normalizePrefix(raw, site);
                if (!normalized.isEmpty()) {
                    addParser(from, normalized);
                }
            }
        }
    }

    /**
     * 极简版「前缀归一」（上游三处 duplicated block 的核心三行）。
     *
     * @param raw  候选原文
     * @param base 基址
     * @return 归一后的接口前缀；不可用返回空串
     */
    public static String normalizePrefix(String raw, String base) {
        String s = raw == null ? "" : raw;
        try {
            if (s.contains("http")) {
                Matcher m = P_URL_KEY.matcher(s);
                if (m.find()) {
                    s = m.group(0);
                }
            } else if (s.contains("//")) {
                Matcher m = P_URL_KEY.matcher(s);
                if (m.find()) {
                    s = "http:" + m.group(0);
                }
            } else {
                Matcher host = P_HOST.matcher(base == null ? "" : base);
                if (host.find()) {
                    Matcher key = P_URL_KEY.matcher(base == null ? "" : base);
                    if (key.find()) {
                        s = host.group(0) + key.group(0);
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "normalizePrefix failed", e);
        }
        return s.replace("..", ".").trim();
    }

    /**
     * 扁平淡出型：V2 会从每个 {@code url=} 里切出前缀进候选，并把集地址里的
     * {@code url=} 之前部分<b>丢掉</b>。
     *
     * <p>与 AppYs 的差别：AppYs 原样保留 {@code title$url} 且线路名走别名表；
     * V2 用原键名 + 切前缀。上游这段还顺带把候选长度做了去重。
     *
     * @param root  响应根对象
     * @param froms 线路名收集器
     * @param urls  线路地址收集器
     */
    @Override
    public void sourcesFromFlat(JSONObject root, List<String> froms, List<String> urls) {
        JSONObject videolist = root.optJSONObject("videolist");
        if (videolist == null) {
            return;
        }
        java.util.Iterator<String> it = videolist.keys();
        while (it.hasNext()) {
            String key = it.next();
            JSONArray arr = videolist.optJSONArray(key);
            List<String> eps = new ArrayList<>();
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject one = arr.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                String url = one.optString("url");
                String title = one.optString("title");
                if (url.contains("url=")) {
                    int cut = url.indexOf("url=") + 4;
                    addParser(key, url.substring(0, cut).trim());
                    eps.add(title + "$" + url.substring(cut).trim());
                } else {
                    eps.add(title + "$" + url);
                }
            }
            froms.add(key);
            urls.add(android.text.TextUtils.join("#", eps));
        }
    }

    // ── 分叉 4：playerContent ───────────────────────────────

    /**
     * {@code playerContent}：逐候选前缀试解析。
     *
     * <p>流程（上游逐字）：
     * <ol>
     *   <li>取本线路的候选前缀；</li>
     *   <li>对每个候选：{@code prefix + 播放地址} 发一次请求，
     *       {@link AppYsVideo#resolve} 规范化；拿到带 {@code url}+{@code header} 的对象就用它
     *       （{@code header} 会被 {@code toString()} 成字符串——宿主契约如此）；</li>
     *   <li>响应是 HTML 且命中 {@link #P_HTML} 任一特征 → 记下这个候选，继续试下一个；</li>
     *   <li>全部失败：有「HTML 候选」→ {@code {parse:1,playUrl:候选,url:原地址}}；
     *       否则地址本身是直链 → {@code {parse:0,url}}；
     *       都不是 → {@code {parse:1,jx:"1",url}}。</li>
     * </ol>
     *
     * @param flag     线路名
     * @param id       播放地址
     * @param vipFlags 忽略
     * @return 播放 JSON（已挂弹幕）；异常返回 {@code ""}
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            List<String> candidates = parsers.get(flag);
            if (candidates != null && !candidates.isEmpty()) {
                JSONObject resolved = tryCandidates(candidates, id);
                if (resolved != null) {
                    return addDanmaku(resolved.toString());
                }
            }
            JSONObject out = new JSONObject();
            if (AppYsVideo.isDirect(id)) {
                out.put("parse", 0);
                out.put("playUrl", "");
                out.put("url", id);
                return addDanmaku(out.toString());
            }
            out.put("parse", 1);
            out.put("jx", "1");
            out.put("url", id);
            return addDanmaku(out.toString());
        } catch (Throwable e) {
            Log.e(tag(), "playerContent failed", e);
            return "";
        }
    }

    /**
     * 上游 {@code r(ArrayList,String)}：逐候选试解析。
     *
     * @param candidates 候选前缀
     * @param playId     播放地址
     * @return 规范化后的播放对象；全部失败返回 null
     */
    public final JSONObject tryCandidates(List<String> candidates, String playId) {
        String htmlCandidate = "";
        for (String prefix : candidates) {
            if (prefix == null || prefix.isEmpty() || "null".equals(prefix)) {
                continue;
            }
            String body = fetch(prefix + playId);
            JSONObject resolved = AppYsVideo.resolve(playId, body);
            if (resolved != null && resolved.has("url") && resolved.has("header")) {
                try {
                    resolved.put("header", resolved.getJSONObject("header").toString());
                } catch (Throwable e) {
                    Log.w(TAG, "header 序列化失败", e);
                }
                // 注意：上游此处<b>不写 parse 字段</b>（返回体只有 header/url），本实现保持原样。
                // 见类 javadoc「观察」一节：宿主必须把缺省 parse 当成直链，否则这条线路播不了。
                return resolved;
            }
            if (body.contains("<html")) {
                for (java.util.regex.Pattern p : P_HTML) {
                    if (p.matcher(body).find()) {
                        htmlCandidate = prefix;
                        break;
                    }
                }
            }
        }
        if (htmlCandidate.isEmpty()) {
            return null;
        }
        try {
            JSONObject out = new JSONObject();
            out.put("parse", 1);
            out.put("playUrl", htmlCandidate);
            out.put("url", playId);
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 便于排障：当前候选表。
     *
     * @return {@code 线路 → [前缀…]}
     */
    public Map<String, List<String>> candidates() {
        return parsers();
    }
}
