package com.example.tvbox.plain.sp360;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * 播放对象序列化 —— 还原混淆库 {@code com.github.catvod.spider.merge.FM.c.g} 的
 * {@code toString()} 行为。
 *
 * <h2>为什么要单独一个类</h2>
 * 上游 {@code SP360.playerContent} 的返回值不是手拼 JSON，而是「new 一个 builder →
 * 链式塞字段 → {@code toString()}」。那个 builder 的 {@code toString()} 是
 * <b>Gson 反序列化整个对象</b>：
 * <pre>
 *   public final String toString() {
 *       return new Gson().newBuilder().disableHtmlEscaping().create().toJson(this);
 *   }
 * </pre>
 * Gson 默认用<b>字段名</b>作键、默认跳过 null、并且 int 字段即使为 0 也会输出。
 * 该类的字段名在发布包里被混淆成了单字母，所以线上报文的键就是这些单字母。
 *
 * <h2>字段用法（由 setter 反推）</h2>
 * <table border="1">
 *   <tr><th>字段</th><th>写入方法</th><th>含义</th><th>SP360 是否写</th></tr>
 *   <tr><td>{@code d}</td><td>{@code f(Map)} → {@code Gson.toJson(map)}</td>
 *       <td>请求头 JSON 字符串（空 map 时不写）</td><td>写（User-Agent）</td></tr>
 *   <tr><td>{@code f}</td><td>{@code a(String)}</td><td>弹幕地址</td><td>写</td></tr>
 *   <tr><td>{@code i}</td><td>{@code x(String)}</td><td>播放/解析地址</td><td>写</td></tr>
 *   <tr><td>{@code k}</td><td>{@code m(int)}</td><td>parse 标志</td><td>写 1</td></tr>
 *   <tr><td>{@code l}</td><td>{@code g()}</td>
 *       <td>校验/开关位，{@code g()} 把它置 1</td><td>写 1（固定）</td></tr>
 *   <tr><td>其余 a/b/c/e/g/h/i/j/m/n/o/p</td><td>本类路径不写</td>
 *       <td>null 被跳过；{@code k}/{@code l} 是 int 所以恒输出</td><td>不写</td></tr>
 * </table>
 *
 * <h2>证据（REA / JADX headless 0.7.1）</h2>
 * <ul>
 *   <li>{@code FM.c.g.toString} —— {@code ev_90c632835a5d9e46fa28aea766ec217ef27a27e15634968da69a43b2c7d0013a}</li>
 *   <li>{@code FM.c.g.a} → {@code this.f = str} —— {@code ev_e0edb8a8a7388d49b9da942a73247949ee47d89abf2762ad66aad9ce424c66ee}</li>
 *   <li>{@code FM.c.g.x} → {@code this.i = str} —— {@code ev_a00393327f923d8fc2ffd87f5081068154462b19b283c75dfac3cc7eafb1b70a}</li>
 *   <li>{@code FM.c.g.m} → {@code this.k = i} —— {@code ev_05705bf53d4218f4f0a1c9dbb9ac8f76a12c1f90fe95b9ce0c358cfdf9836ab2}</li>
 *   <li>{@code FM.c.g.g} → {@code this.l = 1} —— {@code ev_5290457fdaea2310392fb286d07526546c0d44cc48a5195604df5366ee9a102b}</li>
 *   <li>{@code FM.c.g.f(Map)} → {@code this.d = Gson.toJson(map)} —— {@code ev_8f3131c8d5286d3d94397def8d4553bbe3f58016bf24f83201d7791e134e46b5}</li>
 * </ul>
 * <p><b>限制</b>：{@code <init>} 没有被 jadx 反编译出方法体（返回
 * {@code "method exists but has no decompiled body"}），所以「哪些字段在构造时被初始化成非 null」
 * 无法确认；本类按「只输出被显式赋值的字段」处理，并把这一条列为未回收分支。
 *
 * <h2>与 AppYs 族的对比（同样返回播放对象）</h2>
 * AppYs 族手拼 {@code {"parse":..,"playUrl":..,"url":..,"header":".."}}，
 * SP360 走 Gson 单字母键。两者键名完全不同 —— 说明宿主对这两套都认，
 * 或者 SP360 这条线路本来就是坏的。静态分析到此为止，不做推测性改写。
 */
public final class PlayJson {

    /** 上游字段 {@code d}：请求头（Gson 序列化后的字符串） */
    public String header;
    /** 上游字段 {@code f}：弹幕地址 */
    public String danmaku;
    /** 上游字段 {@code i}：播放地址 */
    public String url;
    /** 上游字段 {@code k}：parse 标志 */
    public int parse;
    /** 上游字段 {@code l}：{@code g()} 置的开关位 */
    public int check;

    /**
     * 上游 {@code f(Map)}：把头表序列化成 {@code d}。
     *
     * <p>空 map 时不写（上游 {@code if (map.isEmpty()) return this;}）。
     * Gson 的对象序列化用 LinkedHashMap 的插入序，所以这里也用 LinkedHashMap 保序。
     *
     * @param headers 头表
     * @return this
     */
    public PlayJson headers(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return this;
        }
        this.header = toJsonObject(headers).toString();
        return this;
    }

    /**
     * 上游 {@code a(String)}：弹幕地址。
     *
     * @param value 地址
     * @return this
     */
    public PlayJson danmaku(String value) {
        this.danmaku = value;
        return this;
    }

    /**
     * 上游 {@code x(String)}：播放地址。
     *
     * @param value 地址
     * @return this
     */
    public PlayJson url(String value) {
        this.url = value;
        return this;
    }

    /**
     * 上游 {@code m(int)}：parse 标志。
     *
     * @param value 标志
     * @return this
     */
    public PlayJson parse(int value) {
        this.parse = value;
        return this;
    }

    /**
     * 上游 {@code g()}：把开关位置 1（无入参）。
     *
     * @return this
     */
    public PlayJson check() {
        this.check = 1;
        return this;
    }

    /**
     * 上游 {@code toString()}：Gson 反射序列化。
     *
     * <p>键顺序 = 字段声明顺序（d、f、i、k、l），null 字段跳过，int 字段恒输出。
     * {@code disableHtmlEscaping()} 只影响 {@code <>&=} 的转义；本类产出的
     * {@code header} 是内嵌 JSON 字符串，转义由外层 JSON 序列化负责，键里出现的
     * {@code <} 不会被转成 {@code \u003c} —— 与上游一致。
     *
     * @return 播放对象 JSON
     */
    @Override
    public String toString() {
        JSONObject out = new JSONObject();
        try {
            if (header != null) {
                out.put("d", header);
            }
            if (danmaku != null) {
                out.put("f", danmaku);
            }
            if (url != null) {
                out.put("i", url);
            }
            out.put("k", parse);
            out.put("l", check);
        } catch (Throwable e) {
            return "{}";
        }
        return out.toString();
    }

    /**
     * Gson 风格的 map → JSON（保序、按类型落值）。
     *
     * @param map 头表
     * @return 对象
     */
    public static JSONObject toJsonObject(Map<String, String> map) {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            try {
                o.put(e.getKey(), e.getValue());
            } catch (Throwable ignored) {
                // 键非法就跳过，与 Gson 忽略不可序列化字段一致
            }
        }
        return o;
    }

    /**
     * 便于测试/排障：把对象按 key 排序输出（仅用于断言字段集合）。
     *
     * @return 排序后的 {@code key=value} 列表
     */
    public Map<String, String> sortedFields() {
        TreeMap<String, String> m = new TreeMap<>();
        try {
            JSONObject o = new JSONObject(toString());
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, o.optString(k));
            }
        } catch (Throwable ignored) {
            // 无需处理
        }
        return m;
    }
}
