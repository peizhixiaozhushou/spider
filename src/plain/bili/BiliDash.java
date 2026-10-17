package com.example.tvbox.plain.bili;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 哔哩哔哩 DASH 清单模型 + MPD 渲染。
 *
 * <p>还原上游 {@code merge.p031f.a}（dash）+ {@code merge.p031f.c}（Representation）
 * + {@code merge.p031f.d}（SegmentBase）三个类，以及 {@code BiliYS.proxy()} 里的
 * MPD 模板拼装（{@code ae/decompiled/BiliYS.java:78-109}）。
 *
 * <h2>上游方法映射（本类的手写等价物）</h2>
 * <table border="1">
 *   <tr><th>上游</th><th>字段</th><th>本类</th></tr>
 *   <tr><td>{@code a.h()}</td><td>{@code mimeType}</td><td>{@link Rep#mimeType}</td></tr>
 *   <tr><td>{@code a.g()}</td><td>{@code id}（quality / audio id）</td><td>{@link Rep#id}</td></tr>
 *   <tr><td>{@code a.a()}</td><td>{@code bandwidth}</td><td>{@link Rep#bandwidth}</td></tr>
 *   <tr><td>{@code a.d()}</td><td>{@code codecs}</td><td>{@link Rep#codecs}</td></tr>
 *   <tr><td>{@code a.f() / l() / e() / i()}</td>
 *       <td>{@code height / width / frameRate / sar}</td>
 *       <td>{@link Rep#height} 等</td></tr>
 *   <tr><td>{@code a.k()}</td><td>{@code startWithSap}</td><td>{@link Rep#startWithSap}</td></tr>
 *   <tr><td>{@code a.c()}</td><td>{@code codecid}</td><td>{@link Rep#codecid}</td></tr>
 *   <tr><td>{@code a.b()}</td><td>{@code baseUrl}</td><td>{@link Rep#baseUrl}</td></tr>
 *   <tr><td>{@code a.j()}</td><td>{@code SegmentBase.indexRange/Initialization}</td>
 *       <td>{@link Rep#indexRange} / {@link Rep#initRange}</td></tr>
 *   <tr><td>{@code a.b()/c()/d()}</td><td>dash 的 duration/minBufferTime/(video/audio 列表)</td>
 *       <td>{@link #duration} / {@link #minBufferTime} / {@link #video} / {@link #audio}</td></tr>
 * </table>
 *
 * <h2>与上游的三处差异（都是修 bug / 定行为，不是简化）</h2>
 * <ol>
 *   <li><b>空 {@code dash}</b>：上游 {@code b.e()} 在 {@code dash==null} 时返回 {@code new a()}，
 *       其内部列表为 null → {@code proxy()} 里 {@code for (c : aVarE.a())} 直接 NPE。
 *       本类恒返回非 null 列表。</li>
 *   <li><b>{@code segment_base} 缺失</b>：上游 {@code a.j().a()}/{@code .b()} 在
 *       {@code SegmentBase} 缺失时同样 NPE。本类用空串。</li>
 *   <li><b>数字格式化</b>：上游混用 {@code Locale.getDefault()} 与默认 locale。
 *       本类统一 {@code Locale.ROOT}，避免阿拉伯语等 locale 下产出非 ASCII 数字，
 *       让 MPD 字符串可复现（断言可钉住固定输出）。</li>
 * </ol>
 */
public final class BiliDash {

    /** 音频 id → 采样率（上游 {@code BiliYS.h()} 的硬编码表） */
    public static final Map<String, String> AUDIO_SAMPLE_RATE = buildAudioTable();

    /** 一条 Representation（视频或音频轨） */
    public static final class Rep {
        /** 轨道 id：视频为清晰度（如 {@code "80"}），音频为音频 id（如 {@code "30280"}） */
        public String id = "";
        /** 播放地址 */
        public String baseUrl = "";
        /** 编码描述，如 {@code avc1.640028} */
        public String codecs = "";
        /** MIME，如 {@code video/mp4} / {@code audio/mp4} */
        public String mimeType = "";
        /** 帧率（字符串形式，B 站给的是 "25" 这类） */
        public String frameRate = "";
        /** 采样纵横比，如 {@code "1:1"} */
        public String sar = "";
        /** 码率 */
        public int bandwidth;
        /** 编码 id */
        public int codecid;
        /** 宽 */
        public int width;
        /** 高 */
        public int height;
        /** startWithSAP */
        public int startWithSap;
        /** SegmentBase indexRange，如 {@code 0-1090} */
        public String indexRange = "";
        /** SegmentBase Initialization range */
        public String initRange = "";

        /**
         * 内容类型：{@code mimeType} 的斜杠前段（{@code video} / {@code audio}）。
         *
         * @return 类型；无斜杠时返回原串
         */
        public String contentType() {
            int i = mimeType == null ? -1 : mimeType.indexOf('/');
            return i < 0 ? (mimeType == null ? "" : mimeType) : mimeType.substring(0, i);
        }

        /** @return 是否视频轨 */
        public boolean isVideo() {
            return contentType().startsWith("video");
        }

        /** @return 是否音频轨 */
        public boolean isAudio() {
            return contentType().startsWith("audio");
        }

        /**
         * 渲染 {@code <Representation>} 的属性部分（还原 {@code BiliYS.g(Rep)}）。
         *
         * @return {@code height='..' width='..' frameRate='..' sar='..'} 或
         *         {@code numChannels='2' sampleRate='..'}；未知类型返回空串
         */
        public String attributes() {
            if (isVideo()) {
                return String.format(Locale.ROOT,
                        "height='%s' width='%s' frameRate='%s' sar='%s'",
                        height, width, frameRate, sar);
            }
            if (isAudio()) {
                String rate = AUDIO_SAMPLE_RATE.get(id);
                return String.format(Locale.ROOT, "numChannels='2' sampleRate='%s'",
                        rate == null ? "" : rate);
            }
            return "";
        }
    }

    /** dash 时长（秒，字符串形式） */
    public String duration = "0";
    /** 最小缓冲时长（秒，字符串形式，可能是小数） */
    public String minBufferTime = "0";
    /** 视频轨 */
    public final List<Rep> video = new ArrayList<>();
    /** 音频轨 */
    public final List<Rep> audio = new ArrayList<>();

    /** 构造 */
    public BiliDash() {
    }

    private static Map<String, String> buildAudioTable() {
        Map<String, String> m = new HashMap<>();
        m.put("30280", "192000");
        m.put("30232", "132000");
        m.put("30216", "64000");
        return m;
    }

    // ── 解析 ────────────────────────────────────────────────

    /**
     * 解析 playurl 响应。
     *
     * <p>兼容三种外层：{@code {"code":0,"result":{...}}}（PGC）、
     * {@code {"code":0,"data":{...}}}（web 接口）、以及直接给出 {@code dash} 的对象。
     *
     * @param json 响应体，可为 null
     * @return 非 null 的 BiliDash（解析失败时各列表为空）
     */
    public static BiliDash parse(String json) {
        return parse(safe(json));
    }

    /**
     * 解析已解好 JSON 的 playurl 响应（避免重复解析）。
     *
     * @param root 响应对象，可为 null
     * @return 非 null 的 BiliDash
     */
    public static BiliDash parse(JSONObject root) {
        BiliDash d = new BiliDash();
        JSONObject body = body(root);
        JSONObject dash = body.optJSONObject("dash");
        if (dash == null) {
            return d;
        }
        d.duration = num(dash, "duration", "0");
        d.minBufferTime = num(dash, "minBufferTime", "0");
        fill(d.video, dash.optJSONArray("video"));
        fill(d.audio, dash.optJSONArray("audio"));
        return d;
    }

    /**
     * 剥掉 {@code result} / {@code data} 外层（PGC 与 web 两套接口的差异就在这里）。
     *
     * @param root 响应根对象，可为 null
     * @return 业务层对象（永不为 null）
     */
    public static JSONObject body(JSONObject root) {
        if (root == null) {
            return new JSONObject();
        }
        JSONObject r = root.optJSONObject("result");
        if (r != null) {
            return r;
        }
        JSONObject d = root.optJSONObject("data");
        return d == null ? root : d;
    }

    /**
     * 读 {@code SegmentBase.Initialization}。
     *
     * <p><b>B 站的形状与 DASH 规范不同</b>：规范里是
     * {@code <Initialization range="0-991"/>} 元素（对象），而 B 站 playurl 的
     * {@code dash} 里给的是<b>字符串</b> {@code "Initialization":"0-991"}。
     * 跨类证据：同工程的 {@code BiliPlain} 一直用 {@code optString("Initialization")}
     * 读它，且能对上真实响应；本类原先只读对象形式（{@code Initialization.range}），
     * 对真实响应会得到空串。
     *
     * <p>这里两种都收：字符串优先（B 站真实形状），对象形式作为兼容。
     *
     * @param segmentBase {@code SegmentBase} 对象，可为 null
     * @return range 字符串；都没有则空串
     */
    public static String initRange(JSONObject segmentBase) {
        if (segmentBase == null) {
            return "";
        }
        Object init = segmentBase.opt("Initialization");
        // 真 org.json（Android）与本仓桩都会在 put/parse 时把嵌套 Map 归一化成
        // JSONObject（桩侧见 ae/stubs/org/json/JSONObject.java 的 norm()），
        // 所以从 JSON 文本解析出来的 Initialization 一定是 JSONObject。
        // ⇒ 这里只认 JSONObject；原先的 `instanceof java.util.Map` 兜底是<b>不可达的</b>，
        //    已删除（保留它会让后来者以为 Map 是主路径）。
        if (init instanceof JSONObject) {
            return ((JSONObject) init).optString("range", "");
        }
        String plain = segmentBase.optString("Initialization", "");
        return "null".equals(plain) ? "" : plain;
    }

    private static void fill(List<Rep> out, JSONArray arr) {
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Rep r = new Rep();
            r.id = o.optString("id", "");
            r.baseUrl = pick(o, "baseUrl", "base_url", "url");
            r.codecs = o.optString("codecs", "");
            r.mimeType = pick(o, "mimeType", "mime_type");
            r.frameRate = pick(o, "frameRate", "frame_rate");
            r.sar = o.optString("sar", "");
            r.bandwidth = intOf(o, "bandwidth");
            r.codecid = intOf(o, "codecid");
            r.width = intOf(o, "width");
            r.height = intOf(o, "height");
            r.startWithSap = o.has("startWithSAP") ? intOf(o, "startWithSAP")
                    : (o.has("startWithSap") ? intOf(o, "startWithSap") : 1);
            JSONObject sb = o.optJSONObject("SegmentBase");
            if (sb != null) {
                r.indexRange = sb.optString("indexRange", "");
                r.initRange = initRange(sb);
            }
            out.add(r);
        }
    }

    // ── 查询 ────────────────────────────────────────────────

    /**
     * 按 id 找音频轨。
     *
     * @param id 音频 id，如 {@code "30280"}
     * @return 命中轨；未命中返回 null
     */
    public Rep findAudio(String id) {
        for (Rep r : audio) {
            if (r.id != null && r.id.equals(id)) {
                return r;
            }
        }
        return null;
    }

    /**
     * 按清晰度找视频轨。
     *
     * @param qn 清晰度，如 {@code "80"}
     * @return 命中轨；未命中返回 null
     */
    public Rep findVideo(String qn) {
        for (Rep r : video) {
            if (r.id != null && r.id.equals(qn)) {
                return r;
            }
        }
        return null;
    }

    // ── 渲染 ────────────────────────────────────────────────

    /**
     * 渲染一段 {@code <AdaptationSet>}（还原 {@code BiliYS.h(Rep, attrs)}）。
     *
     * <p>注意 {@code BaseURL} 里的 {@code &} 被转成 {@code &amp;}（XML 转义），
     * 这一条很容易漏 —— 漏了的话播放器解析 MPD 会拿到截断的地址。
     *
     * @param r 轨道，可为 null
     * @return XML 片段；{@code r} 为 null 或类型未知时为空串
     */
    public static String adaptationSet(Rep r) {
        if (r == null) {
            return "";
        }
        String attrs = r.attributes();
        if (attrs.isEmpty()) {
            return "";
        }
        return String.format(Locale.ROOT,
                "<AdaptationSet>\n"
                + "<ContentComponent contentType=\"%s\"/>\n"
                + "<Representation id=\"%s\" bandwidth=\"%s\" codecs=\"%s\" mimeType=\"%s\" %s startWithSAP=\"%s\">\n"
                + "<BaseURL>%s</BaseURL>\n"
                + "<SegmentBase indexRange=\"%s\">\n"
                + "<Initialization range=\"%s\"/>\n"
                + "</SegmentBase>\n"
                + "</Representation>\n"
                + "</AdaptationSet>",
                r.contentType(), r.id + "_" + r.codecid, r.bandwidth, r.codecs, r.mimeType,
                attrs, r.startWithSap, xmlEscape(r.baseUrl), r.indexRange, r.initRange);
    }

    /**
     * XML 文本转义（只处理会破坏 MPD 的三种：{@code & < >}）。
     *
     * @param s 原文，可为 null
     * @return 转义结果
     */
    public static String xmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 渲染完整 MPD。
     *
     * @param qn         请求的清晰度（决定哪条视频轨进 MPD）
     * @param includeAlt true 时同时带上请求清晰度之外的<b>全部</b>音频轨
     * @return MPD XML；参数不足时仍返回可解析的空 MPD
     */
    public String toMpd(String qn, boolean includeAlt) {
        StringBuilder audioXml = new StringBuilder();
        for (Rep r : audio) {
            if (includeAlt || AUDIO_SAMPLE_RATE.containsKey(r.id)) {
                audioXml.append(adaptationSet(r));
            }
        }
        StringBuilder videoXml = new StringBuilder();
        Rep hit = findVideo(qn);
        if (hit != null) {
            videoXml.append(adaptationSet(hit));
        }
        // 一条轨都没渲染出来时，返回最小合法空 MPD —— 与 BiliPlain.emptyMpd()
        // 共用同一份字面量，避免同一个概念出现两种"空 MPD"
        if (videoXml.length() == 0 && audioXml.length() == 0) {
            return emptyMpd();
        }
        return String.format(Locale.ROOT,
                "<MPD xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                + " xmlns=\"urn:mpeg:dash:schema:mpd:2011\""
                + " xsi:schemaLocation=\"urn:mpeg:dash:schema:mpd:2011 DASH-MPD.xsd\""
                + " type=\"static\" mediaPresentationDuration=\"PT%sS\""
                + " minBufferTime=\"PT%sS\""
                + " profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n"
                + "<Period duration=\"PT%sS\" start=\"PT0S\">\n%s\n%s\n</Period>\n</MPD>",
                duration, minBufferTime, duration, videoXml, audioXml);
    }

    /**
     * 无任何可用轨道时的最小合法 MPD。
     *
     * <p>上游 Bili 的 {@code emptyMpd} 就是这个串（Bili.java 的 parse 失败分支）；
     * Bili 与 BiliYS 共用这一份，不再各写一遍。
     *
     * @return MPD XML
     */
    public static String emptyMpd() {
        return "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\""
                + " mediaPresentationDuration=\"PT0S\" minBufferTime=\"PT0S\""
                + " profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n"
                + "<Period duration=\"PT0S\" start=\"PT0S\">\n</Period>\n</MPD>";
    }

    // ── 小工具 ──────────────────────────────────────────────

    private static JSONObject safe(String json) {
        if (json == null) {
            return new JSONObject();
        }
        String t = json.trim();
        if (t.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(t);
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    private static String pick(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static int intOf(JSONObject o, String k) {
        Object v = o.opt(k);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Throwable e) {
            return 0;
        }
    }

    private static String num(JSONObject o, String k, String def) {
        Object v = o.opt(k);
        if (v == null || v == JSONObject.NULL) {
            return def;
        }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return d == Math.floor(d) && !Double.isInfinite(d)
                    ? String.valueOf((long) d) : String.valueOf(d);
        }
        return String.valueOf(v);
    }

    /**
     * 供测试读取音频表（只读副本）。
     *
     * @return id → 采样率
     */
    public static Map<String, String> audioTable() {
        return new LinkedHashMap<>(AUDIO_SAMPLE_RATE);
    }
}
