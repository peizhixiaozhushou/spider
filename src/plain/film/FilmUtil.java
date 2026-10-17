package com.example.tvbox.plain.film;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 影视站群共用小工具 —— 只放「上游多个类都重复实现」的那几件事，避免每个类再抄一遍。
 *
 * <h2>覆盖的上游重复模式</h2>
 * <table>
 *   <tr><th>用途</th><th>上游形态</th><th>本类</th></tr>
 *   <tr><td>正则取分组</td><td>{@code Pattern.compile(x).matcher(s).find() ? group(n) : ""}</td><td>{@link #find}</td></tr>
 *   <tr><td>抽取数字 id</td><td>{@code href.replaceAll("\\D+","")}</td><td>{@link #digits}</td></tr>
 *   <tr><td>MIME 推断</td><td>{@code g.x(url).l()} 内部判断</td><td>{@link #mimeOf}</td></tr>
 *   <tr><td>播放响应</td><td>{@code p028c.c.e().u(url).k(parse).e(map)}</td><td>{@link #player}</td></tr>
 *   <tr><td>改写 parse</td><td>返回体二次构造</td><td>{@link #withParse}</td></tr>
 *   <tr><td>拼接带参地址</td><td>{@code prefix + URLEncoder.encode(v)}</td><td>{@link #joinUrl}</td></tr>
 *   <tr><td>VOID/空值兜底</td><td>{@code TextUtils.isEmpty}</td><td>{@link #nz}</td></tr>
 * </table>
 *
 * <p>本类无状态、无网络、不抛异常。
 */
public final class FilmUtil {

    private static final String TAG = "FilmUtil";

    private FilmUtil() {
    }

    /**
     * 正则取指定分组。
     *
     * @param pattern 正则（每次编译；调用点很少，可读性优先）
     * @param text    待匹配文本，可为 null
     * @param group   组号（1 起）
     * @return 命中内容；未命中或 group 越界返回空串
     */
    public static String find(String pattern, String text, int group) {
        if (pattern == null || text == null || text.isEmpty()) {
            return "";
        }
        try {
            Matcher m = Pattern.compile(pattern).matcher(text);
            if (m.find() && m.groupCount() >= group) {
                String v = m.group(group);
                return v == null ? "" : v;
            }
        } catch (Exception e) {
            Log.e(TAG, "regex failed: " + pattern, e);
        }
        return "";
    }

    /**
     * 正则取指定分组（带 flags，如 {@link Pattern#DOTALL}）。
     *
     * @param pattern 正则
     * @param flags   {@link Pattern} 标志
     * @param text    待匹配文本
     * @param group   组号
     * @return 命中内容；未命中返回空串
     */
    public static String find(String pattern, int flags, String text, int group) {
        if (pattern == null || text == null || text.isEmpty()) {
            return "";
        }
        try {
            Matcher m = Pattern.compile(pattern, flags).matcher(text);
            if (m.find() && m.groupCount() >= group) {
                String v = m.group(group);
                return v == null ? "" : v;
            }
        } catch (Exception e) {
            Log.e(TAG, "regex failed: " + pattern, e);
        }
        return "";
    }

    /**
     * 抽取字符串中的全部数字（对齐上游 {@code replaceAll("\\D+", "")}）。
     *
     * @param s 原文，可为 null
     * @return 纯数字串；无数字返回空串
     */
    public static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D+", "");
    }

    /**
     * 按扩展名推断内容类型。
     *
     * @param url 地址，可为 null
     * @return MIME；无法判断返回 {@code video/*}
     */
    public static String mimeOf(String url) {
        if (url == null) {
            return "video/*";
        }
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        if (u.contains(".mp4")) {
            return "video/mp4";
        }
        if (u.contains(".flv")) {
            return "video/x-flv";
        }
        return "video/*";
    }

    /**
     * 构造播放响应（对齐上游 {@code p028c.c} 的播放分支）。
     *
     * <pre>
     *  {"parse":0|1,"url":"...","header":"{...}","jx":0|1,"playUrl":""}
     * </pre>
     *
     * @param url    播放地址，null 视为空串
     * @param parse  0=直连；1=交给宿主解析
     * @param header 请求头 JSON 文本，可为 null
     * @param jx     jx 标志（上游多为 0 或 1）
     * @return 播放 JSON；构造失败返回 {@code "{}"}
     */
    public static String player(String url, int parse, String header, int jx) {
        JSONObject o = new JSONObject();
        try {
            o.put("parse", parse);
            o.put("playUrl", "");
            o.put("url", url == null ? "" : url);
            o.put("header", header == null ? "" : header);
            o.put("jx", jx);
            return o.toString();
        } catch (Exception e) {
            Log.e(TAG, "player build failed", e);
            return "{}";
        }
    }

    /**
     * 构造播放响应（带字幕字段）。
     *
     * @param url     播放地址
     * @param parse   0=直连；1=解析
     * @param header  请求头 JSON 文本
     * @param subUrl  字幕地址，空则不写字幕字段
     * @param subFmt  字幕格式（如 {@code /vtt/utf-8}），空则不写
     * @return 播放 JSON
     */
    public static String playerSub(String url, int parse, String header, String subUrl, String subFmt) {
        JSONObject o = new JSONObject();
        try {
            o.put("parse", parse);
            o.put("playUrl", "");
            o.put("url", url == null ? "" : url);
            o.put("header", header == null ? "" : header);
            if (subUrl != null && !subUrl.isEmpty()) {
                o.put("subt", subUrl);
                o.put("subf", subFmt == null ? "" : subFmt);
            }
            return o.toString();
        } catch (Exception e) {
            Log.e(TAG, "player build failed", e);
            return "{}";
        }
    }

    /**
     * 覆盖已有播放响应里的 {@code parse} 字段。
     *
     * @param json  原响应
     * @param parse 目标值
     * @return 新响应文本；解析失败原样返回
     */
    public static String withParse(String json, int parse) {
        try {
            JSONObject o = new JSONObject(json);
            o.put("parse", parse);
            return o.toString();
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * 前缀 + URL 编码后的值（上游 relay 前缀常以 {@code =} 或 {@code ?url=} 结尾）。
     *
     * @param prefix 前缀
     * @param value  拼接值
     * @return 完整地址
     */
    public static String joinUrl(String prefix, String value) {
        if (prefix == null) {
            return nz(value);
        }
        if (value == null) {
            return prefix;
        }
        return prefix + com.example.tvbox.common.Http.encode(value);
    }

    /**
     * 请求头表转 JSON 文本（上游 {@code new JSONObject(map).toString()}）。
     *
     * @param headers 头表，可为 null
     * @return JSON 文本；空表返回 {@code "{}"}
     */
    public static String headerJson(Map<String, String> headers) {
        JSONObject o = new JSONObject();
        if (headers != null) {
            try {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    o.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
                }
            } catch (Exception e) {
                Log.e(TAG, "headerJson failed", e);
            }
        }
        return o.toString();
    }

    /**
     * 解析内置筛选表 JSON。
     *
     * @param json 筛选 JSON
     * @return JSONObject；解析失败返回空对象
     */
    public static JSONObject parseJson(String json) {
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            Log.e(TAG, "filters parse failed", e);
            return new JSONObject();
        }
    }

    /**
     * 把 {@code {"k":[维度...]}} 形态的筛选表按分类 id 拆出单条。
     *
     * @param filters 全量筛选表
     * @param tid     分类 id
     * @return 该分类的筛选；不存在返回 null
     */
    public static JSONObject filterOf(JSONObject filters, String tid) {
        if (filters == null || tid == null) {
            return null;
        }
        Object v = filters.opt(tid);
        if (v instanceof JSONObject) {
            return (JSONObject) v;
        }
        if (v instanceof JSONArray) {
            JSONObject wrap = new JSONObject();
            try {
                wrap.put("value", v);
            } catch (Exception ignored) {
                // 忽略：无法包装时按无筛选处理
            }
            return wrap;
        }
        return null;
    }

    /**
     * null → 空串。
     *
     * @param s 原文
     * @return 非 null 字符串
     */
    public static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 把 {@code a$$$b} 形态按 {@code $$$} 拆开。
     *
     * @param s 原文
     * @return 非 null 列表
     */
    public static List<String> splitSource(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        for (String p : s.split("\\$\\$\\$")) {
            out.add(p);
        }
        return out;
    }
}
