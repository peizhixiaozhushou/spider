package com.example.tvbox.plain.player;

import android.text.TextUtils;

import com.example.tvbox.plain.frame.ProxyPlain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * TVBox 播放响应构造器（{@code playerContent} 的返回体）。
 *
 * <h2>为什么单独成一个共享类</h2>
 * Gulu / Hxq（以及 QiShui 的直链分支）都要产出同一种 JSON：
 * {@code {"parse":0,"url":"...","header":"{...}"}}，而且都要在末尾补一个弹幕字段
 * {@code "danmaku": Proxy.getUrl()+"?do=diydanmu"}。这两件事都来自上游同一个
 * 混淆工具类（{@code merge.p028c.c} 的 {@code e()/u()/k()/e(Map)} 与
 * {@code merge.p026a.a.addDanmaku}），所以集中实现一份，避免三个类各写一遍。
 *
 * <h2>字段语义（上游证据）</h2>
 * <table border="1">
 *   <caption>与 {@code merge.p028c.c} 的对应</caption>
 *   <tr><th>本类</th><th>上游</th><th>说明</th></tr>
 *   <tr><td>{@link #build}</td><td>{@code c.e().u(url).k(parse).e(headers)}</td>
 *       <td>{@code parse=0} 直链、{@code parse=1} 需嗅探</td></tr>
 *   <tr><td>{@link #urls}</td><td>{@code c.e().y(list).m10e(headers)}</td>
 *       <td>{@code url} 为数组 —— 让宿主自己挑一路</td></tr>
 *   <tr><td>{@link #withDanmaku}</td><td>{@code merge.p026a.a.addDanmaku(json)}</td>
 *       <td>补 {@code danmaku} 键；解析失败时原样返回</td></tr>
 *   <tr><td>{@link #danmakuUrl}</td><td>{@code Proxy.getUrl()+"?do=diydanmu"}</td>
 *       <td>弹幕走宿主本地代理</td></tr>
 *   <tr><td>{@link #listWithMsg}</td><td>{@code c.c(msg)}</td>
 *       <td><b>上游缺陷</b>：错误分支返回的是列表体 {@code {"list":[],"msg":..}}
 *           而不是播放体，见 GuluPlain 的「未覆盖的分支 / 上游缺陷」一节</td></tr>
 * </table>
 *
 * <h2>建议</h2>
 * 更合适的归宿是 {@code common/VodBuilder}（那里已经有
 * {@code VodBuilder.playerContent(url, type)}）。本类先独立存在，是为了不与正在被
 * 并发修改的共享文件冲突。
 */
public final class PlayerResponse {

    /** 弹幕代理动作 —— 上游常量，直接写死。 */
    public static final String DANMAKU_ACTION = "?do=diydanmu";

    private PlayerResponse() {
    }

    /**
     * 弹幕地址：宿主本地代理 + {@code ?do=diydanmu}。
     *
     * <p>端口由 {@link ProxyPlain#getUrl()} 自行探测；探测失败时形如
     * {@code http://127.0.0.1:0/proxy?do=diydanmu}（与上游一致 —— 上游也只做拼接）。
     *
     * @return 弹幕地址，永不 null
     */
    public static String danmakuUrl() {
        return ProxyPlain.getUrl() + DANMAKU_ACTION;
    }

    /**
     * 直链响应：{@code {parse,url,header}}。
     *
     * @param url     播放地址
     * @param parse   0 = 直链，1 = 交给宿主嗅探
     * @param headers 请求头，空/null 时不输出 {@code header} 字段（上游 {@code e(Map)} 同样早退）
     * @return JSON 文本；构造失败返回 {@code {}}
     */
    public static String build(String url, int parse, Map<String, String> headers) {
        return build(url, parse, headers, "");
    }

    /**
     * 直链响应（可带 {@code msg}）。
     *
     * @param url     播放地址
     * @param parse   0/1
     * @param headers 请求头
     * @param msg     附加提示，空/null 时不输出该字段
     * @return JSON 文本
     */
    public static String build(String url, int parse, Map<String, String> headers, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("parse", parse);
            o.put("url", url == null ? "" : url);
            String header = headerJson(headers);
            if (!header.isEmpty()) {
                o.put("header", header);
            }
            if (!TextUtils.isEmpty(msg)) {
                o.put("msg", msg);
            }
        } catch (Throwable e) {
            return "{}";
        }
        return o.toString();
    }

    /**
     * 多线路响应：{@code url} 是数组。
     *
     * @param urls    候选地址
     * @param headers 请求头
     * @return JSON 文本
     */
    public static String urls(List<String> urls, Map<String, String> headers) {
        JSONObject o = new JSONObject();
        try {
            o.put("parse", 0);
            JSONArray arr = new JSONArray();
            if (urls != null) {
                for (String u : urls) {
                    if (u != null) {
                        arr.put(u);
                    }
                }
            }
            o.put("url", arr);
            String header = headerJson(headers);
            if (!header.isEmpty()) {
                o.put("header", header);
            }
        } catch (Throwable e) {
            return "{}";
        }
        return o.toString();
    }

    /**
     * 补 {@code danmaku} 字段（上游 {@code merge.p026a.a.addDanmaku} 的等价实现）。
     *
     * @param playerJson 播放响应 JSON
     * @return 追加了 danmaku 的 JSON；入参非法时原样返回
     */
    public static String withDanmaku(String playerJson) {
        try {
            JSONObject o = new JSONObject(playerJson);
            o.put("danmaku", danmakuUrl());
            return o.toString();
        } catch (Exception e) {
            return playerJson;
        }
    }

    /**
     * 上游 {@code merge.p028c.c.c(String)} 的等价输出：列表体 + {@code msg}。
     *
     * <p><b>注意</b>：Gulu 的播放失败分支用的就是它（见类注释表格）。保留是为了
     * 与上游逐字段一致，不代表这是正确的播放错误响应。
     *
     * @param msg 提示
     * @return JSON 文本
     */
    public static String listWithMsg(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("list", new JSONArray());
            o.put("msg", msg == null ? "" : msg);
        } catch (Throwable e) {
            return "{}";
        }
        return o.toString();
    }

    /**
     * header map → JSON 文本（上游用 Gson 序列化 Map）。
     *
     * @param headers 请求头，可为 null/空
     * @return JSON 文本；无内容时为空串
     */
    public static String headerJson(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    o.put(e.getKey(), e.getValue());
                }
            }
        } catch (Throwable e) {
            return "";
        }
        return o.toString();
    }
}
