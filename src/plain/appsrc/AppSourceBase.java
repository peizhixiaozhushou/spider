package com.example.tvbox.plain.appsrc;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 「安卓 App 客户端逆向源」公共骨架。
 *
 * <h2>这一层解决什么</h2>
 * {@code App3Q} / {@code AppYQK} / {@code AppFox} 三个类（反编译合计 1315 行）出自同一批
 * 灰产 App 的协议族，彼此只有「怎么签名」「字段叫什么」不同，下面这些东西是<b>逐字重复</b>的：
 *
 * <ol>
 *   <li><b>弹幕代理 URL</b>：
 *       {@code Proxy.getUrl() + "?do=appdanmu&vodName=" + 片名 + "&vodIndex=" + 集序号}
 *       —— 三个类一字不差（App3Q:255、AppYQK:261、AppFox:623）</li>
 *   <li><b>4 字段列表映射</b>：{@code vod_id / vod_name / vod_pic / vod_remarks}
 *       —— {@code App3Q.parseVodList} 与 {@code AppYQK.categoryContent} 完全相同</li>
 *   <li><b>62 字符随机串表</b>：{@code "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"}
 *       —— App3Q 用 {@code Random}、AppYQK 用预置 {@code char[] a}、Gz360 也有同一张表</li>
 *   <li><b>秒级时间戳 / 毫秒 16 位 hex</b>：{@code System.currentTimeMillis()/1000}、
 *       {@code String.format("%016x", millis)} —— 两处签名源都在用</li>
 *   <li><b>{@code JSONObject.NULL} 判空</b>：{@code opt("remark") != JSONObject.NULL ? ... : ""}
 *       —— 上游比较的是<b>对象引用</b>，字段缺失时 {@code opt} 返回 null，条件为真后
 *       {@code null.toString()} 直接 NPE（见 {@link #objStr}）</li>
 * </ol>
 *
 * <h2>宿主私有依赖的处理</h2>
 * 上游弹幕 URL 以 {@code Proxy.getUrl()} 开头 —— {@code Proxy} 是宿主注入的私有类，
 * 本仓库没有等价物。这里改为可注入的 {@link #proxyBase}（默认空串 = 不产出弹幕字段），
 * 由宿主或测试通过 {@link #setProxyBase} 提供。行为等价，且不再依赖宿主内部类。
 *
 * <h2>证据来源</h2>
 * <ul>
 *   <li>反编译原文: {@code ae/decompiled/App3Q.java}、{@code AppYQK.java}、{@code AppFox.java}</li>
 *   <li>{@code addDanmaku} 的上游实体: {@code ae/work/w-pan/helpers/pancommon/a.java:24}
 *       （{@code merge.p026a.a.addDanmaku} 追加的是 {@code "?do=diydanmu"}，与本族
 *       {@code "?do=appdanmu"} 不同 —— 说明这是两套弹幕代理动作）</li>
 * </ul>
 */
public abstract class AppSourceBase extends BaseSpider {

    /** 本族统一的弹幕代理动作名（三处调用点一致） */
    public static final String DANMAKU_ACTION = "appdanmu";

    /** 62 字符随机表；{@code AppYQK.a} / {@code Gz360.k()} 用同一张表 */
    public static final String ALNUM62 =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** 共享随机源（上游 {@code AppYQK.b} 就是静态 {@code Random}） */
    private static final Random RANDOM = new Random();

    /**
     * 弹幕代理前缀（上游 {@code Proxy.getUrl()}）。
     * 空串表示宿主未提供代理，此时 {@link #danmakuUrl} 返回空串、调用方不写 danmaku 字段。
     */
    protected String proxyBase = "";

    /** 构造 */
    protected AppSourceBase() {
        super();
    }

    // ── 弹幕代理 ────────────────────────────────────────────

    /**
     * 注入弹幕代理前缀（宿主 {@code Proxy.getUrl()} 的替身）。
     *
     * @param base 形如 {@code http://127.0.0.1:9978/proxy}；null 按空串处理
     */
    public void setProxyBase(String base) {
        this.proxyBase = base == null ? "" : base.trim();
    }

    /**
     * 当前弹幕代理前缀。
     *
     * @return 非 null 字符串，未注入时为空串
     */
    public String proxyBase() {
        return proxyBase;
    }

    /**
     * 构造弹幕地址。
     *
     * <p>还原三处调用点逐字相同的字面量：
     * {@code Proxy.getUrl() + "?do=appdanmu&vodName=" + vodName + "&vodIndex=" + vodIndex}。
     * 上游没有做 URL 编码（片名含 {@code &}/{@code #} 会截断），这里补上编码。
     *
     * @param vodName  片名，可为 null
     * @param vodIndex 集序号（上游传的是集名里抽出的数字，无数字时为 {@code "1"}）
     * @return 弹幕地址；未注入 proxyBase 时为空串
     */
    public String danmakuUrl(String vodName, String vodIndex) {
        if (proxyBase == null || proxyBase.isEmpty()) {
            return "";
        }
        return proxyBase + "?do=" + DANMAKU_ACTION
                + "&vodName=" + Http.encode(vodName == null ? "" : vodName)
                + "&vodIndex=" + Http.encode(vodIndex == null ? "" : vodIndex);
    }

    // ── JSON / 字段容错 ─────────────────────────────────────

    /**
     * 安全取数组：缺失或类型不符返回空数组（上游多为 {@code optJSONArray(...)} 后直接
     * {@code .length()}，字段缺失即 NPE）。
     *
     * @param root 源对象，可为 null
     * @param name 字段名
     * @return 非 null 的 JSONArray
     */
    public static JSONArray arr(JSONObject root, String name) {
        if (root == null || name == null) {
            return new JSONArray();
        }
        JSONArray a = root.optJSONArray(name);
        return a == null ? new JSONArray() : a;
    }

    /**
     * 安全取字符串：缺失 / 显式 null 均返回空串。
     *
     * @param o 源对象，可为 null
     * @param k 字段名
     * @return 非 null 字符串
     */
    public static String str(JSONObject o, String k) {
        if (o == null || k == null) {
            return "";
        }
        return o.optString(k, "");
    }

    /**
     * 取「可能为 null 的任意类型字段」的文本形式 —— 修复上游 NPE。
     *
     * <p>上游写法（AppYQK:132-133、337）：
     * <pre>{@code
     * Object objOpt = jSONObject.opt("remark");
     * ... objOpt != JSONObject.NULL ? objOpt.toString() : ""
     * }</pre>
     * {@code opt} 在<b>字段缺失</b>时返回的是 Java {@code null} 而不是
     * {@code JSONObject.NULL}，于是条件为真、随后 {@code null.toString()} 抛 NPE。
     * 只有字段被显式写成 {@code null} 才会返回 {@code JSONObject.NULL}。
     *
     * @param o 源对象，可为 null
     * @param k 字段名
     * @return 文本；缺失或为 null 时为空串
     */
    public static String objStr(JSONObject o, String k) {
        if (o == null || k == null) {
            return "";
        }
        Object v = o.opt(k);
        if (v == null || v == JSONObject.NULL) {
            return "";
        }
        return String.valueOf(v);
    }

    // ── 列表映射 ────────────────────────────────────────────

    /**
     * 一行 JSON → {@link Vod}（只取 4 个列表页字段）。
     *
     * @param o 源对象，可为 null（返回全空 Vod）
     * @return 非 null Vod
     */
    public static Vod vod(JSONObject o) {
        return new Vod(str(o, "vod_id"), str(o, "vod_name"))
                .pic(str(o, "vod_pic"))
                .remarks(str(o, "vod_remarks"));
    }

    /**
     * 数组 → 列表，逐项容错。
     *
     * <p>上游 {@code App3Q.parseVodList} 把 {@code try/catch} 放在循环<b>内部</b>且
     * catch 块为空 —— 单条畸形只丢该条。本方法保持同样语义（跳过畸形项）。
     *
     * @param a 源数组，可为 null
     * @return 非 null 列表
     */
    public static List<Vod> vods(JSONArray a) {
        List<Vod> out = new ArrayList<>();
        if (a == null) {
            return out;
        }
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) {
                continue;
            }
            out.add(vod(o));
        }
        return out;
    }

    // ── 时间 / 随机 ─────────────────────────────────────────

    /**
     * 当前秒级时间戳（十进制串）。
     *
     * @return 形如 {@code "1700000000"}
     */
    public static String nowSec() {
        return String.valueOf(System.currentTimeMillis() / 1000L);
    }

    /**
     * 当前毫秒时间戳的 16 位小写 hex。
     *
     * <p>还原 {@code AppYQK.getUUID()} 的实际返回值（上游先拼 uuid 再丢弃，见该类 javadoc）。
     *
     * @return 16 位小写 hex
     */
    public static String nowHex16() {
        return String.format("%016x", System.currentTimeMillis());
    }

    /**
     * 生成 n 位 62 进制随机串。
     *
     * @param n 长度，&lt;=0 时返回空串
     * @return 随机串
     */
    public static String randomAlnum(int n) {
        if (n <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ALNUM62.charAt(RANDOM.nextInt(ALNUM62.length())));
        }
        return sb.toString();
    }

    /**
     * 生成 [0, bound) 的随机整数。
     *
     * @param bound 上界，&lt;=0 时返回 0
     * @return 随机整数
     */
    public static int randInt(int bound) {
        return bound <= 0 ? 0 : RANDOM.nextInt(bound);
    }
}
