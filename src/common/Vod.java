package com.example.tvbox.common;

import org.json.JSONObject;

/**
 * 统一视频数据模型（POJO）。
 *
 * <p>字段名与 TVBox 标准返回体一致（下划线命名），Java 侧用驼峰。
 * 一次 {@link #toJson()} 即可直接塞进 home/category/detail/search 的 list 中。
 *
 * <p>所有字段默认空串，避免 TVBox 侧因 null 崩溃；全部 setter 返回 this 便于链式构造：
 * <pre>{@code
 * Vod v = new Vod().id("1001").name("示例影片").pic(picUrl).remarks("更新至12集");
 * }</pre>
 */
public class Vod {

    /** vod_id —— 站点内唯一标识；详情/播放接口回传 */
    public String vodId = "";
    /** vod_name —— 片名 */
    public String vodName = "";
    /** vod_pic —— 封面图 URL */
    public String vodPic = "";
    /** vod_remarks —— 更新状态/备注，如「更新至12集」 */
    public String vodRemarks = "";
    /** vod_year —— 年份，字符串（TVBox 不解析数值） */
    public String vodYear = "";
    /** vod_area —— 地区 */
    public String vodArea = "";
    /** vod_content —— 剧情简介 */
    public String vodContent = "";
    /** vod_play_from —— 播放源名，多个用 $ 分隔，如「线路1$$$线路2」 */
    public String vodPlayFrom = "";
    /** vod_play_url —— 播放地址，集与集用 # 分隔、名与址用 $ 分隔，源之间用 $$$ 分隔 */
    public String vodPlayUrl = "";
    /** type_name —— 分类名，列表页可空 */
    public String typeName = "";
    /** vod_actor —— 主演，可空 */
    public String vodActor = "";
    /** vod_director —— 导演，可空 */
    public String vodDirector = "";

    /**
     * vod_tag —— 宿主用它区分"条目类型"（{@code folder} / {@code file}）。
     * 空串表示不输出该字段。
     */
    public String vodTag = "";

    /**
     * action —— 点按条目时回传给 {@code Spider.action(url)} 的地址。
     * 配置市场（Market）等"非内容型"源靠它把点击转成动作。
     * 空串表示不输出该字段。
     */
    public String action = "";

    /** 播放源之间的分隔符（TVBox 约定） */
    public static final String SEP_SOURCE = "$$$";
    /** 同一源内，集与集之间的分隔符（TVBox 约定） */
    public static final String SEP_EPISODE = "#";
    /** 单集内，集名与地址之间的分隔符（TVBox 约定） */
    public static final String SEP_NAME_URL = "$";

    /** 构造空对象。 */
    public Vod() {
    }

    /**
     * 构造并设置主键与名称。
     *
     * @param vodId   站点内唯一 id
     * @param vodName 片名
     */
    public Vod(String vodId, String vodName) {
        this.vodId = nz(vodId);
        this.vodName = nz(vodName);
    }

    // ── 链式 setter ─────────────────────────────────────────

    /**
     * 设置 vod_id。
     *
     * @param v 值
     * @return this
     */
    public Vod id(String v) {
        this.vodId = nz(v);
        return this;
    }

    /**
     * 设置 vod_name。
     *
     * @param v 值
     * @return this
     */
    public Vod name(String v) {
        this.vodName = nz(v);
        return this;
    }

    /**
     * 设置 vod_pic。
     *
     * @param v 值
     * @return this
     */
    public Vod pic(String v) {
        this.vodPic = nz(v);
        return this;
    }

    /**
     * 设置 vod_remarks。
     *
     * @param v 值
     * @return this
     */
    public Vod remarks(String v) {
        this.vodRemarks = nz(v);
        return this;
    }

    /**
     * 设置 vod_year。
     *
     * @param v 值
     * @return this
     */
    public Vod year(String v) {
        this.vodYear = nz(v);
        return this;
    }

    /**
     * 设置 vod_area。
     *
     * @param v 值
     * @return this
     */
    public Vod area(String v) {
        this.vodArea = nz(v);
        return this;
    }

    /**
     * 设置 vod_content。
     *
     * @param v 值
     * @return this
     */
    public Vod content(String v) {
        this.vodContent = nz(v);
        return this;
    }

    /**
     * 设置 vod_play_from。
     *
     * @param v 值，多个源用 {@link #SEP_SOURCE} 连接
     * @return this
     */
    public Vod playFrom(String v) {
        this.vodPlayFrom = nz(v);
        return this;
    }

    /**
     * 设置 vod_play_url。
     *
     * @param v 值
     * @return this
     */
    public Vod playUrl(String v) {
        this.vodPlayUrl = nz(v);
        return this;
    }

    /**
     * 设置 type_name。
     *
     * @param v 值
     * @return this
     */
    public Vod typeName(String v) {
        this.typeName = nz(v);
        return this;
    }

    /**
     * 设置 vod_actor。
     *
     * @param v 值
     * @return this
     */
    public Vod actor(String v) {
        this.vodActor = nz(v);
        return this;
    }

    /**
     * 设置 vod_director。
     *
     * @param v 值
     * @return this
     */
    public Vod director(String v) {
        this.vodDirector = nz(v);
        return this;
    }

    /**
     * 设置 vod_tag。
     *
     * @param v 值
     * @return this
     */
    public Vod tag(String v) {
        this.vodTag = nz(v);
        return this;
    }

    /**
     * 设置 action（点按回传地址）。
     *
     * @param v 值
     * @return this
     */
    public Vod action(String v) {
        this.action = nz(v);
        return this;
    }

    // ── 序列化 ──────────────────────────────────────────────

    /**
     * 转成 TVBox 标准字段的 JSONObject。
     *
     * @return 新构造的 JSONObject（字段值均为字符串，永不 null）
     */
    public JSONObject toJSONObject() {
        JSONObject o = new JSONObject();
        try {
            o.put("vod_id", vodId);
            o.put("vod_name", vodName);
            o.put("vod_pic", vodPic);
            o.put("vod_remarks", vodRemarks);
            o.put("vod_year", vodYear);
            o.put("vod_area", vodArea);
            o.put("vod_content", vodContent);
            o.put("vod_play_from", vodPlayFrom);
            o.put("vod_play_url", vodPlayUrl);
            o.put("type_name", typeName);
            o.put("vod_actor", vodActor);
            o.put("vod_director", vodDirector);
            // 这两个字段只在有值时输出 —— 保证既有源的输出与原实现逐字节一致
            if (!vodTag.isEmpty()) {
                o.put("vod_tag", vodTag);
            }
            if (!action.isEmpty()) {
                o.put("action", action);
            }
        } catch (Throwable e) {
            // stub/宿主 JSON 实现差异不应中断列表构建
            android.util.Log.e("Vod", "toJSONObject failed", e);
        }
        return o;
    }

    /**
     * 转成 TVBox 标准字段的 JSON 文本。
     *
     * @return JSON 字符串
     */
    public String toJson() {
        return toJSONObject().toString();
    }

    /**
     * 从站点 JSON 反填（尽力而为，缺失字段保持空串）。
     *
     * @param o 源对象，null 时不做任何事
     * @return this
     */
    public Vod fromJSONObject(JSONObject o) {
        if (o == null) {
            return this;
        }
        this.vodId = nz(o.optString("vod_id", o.optString("id")));
        this.vodName = nz(o.optString("vod_name", o.optString("name")));
        this.vodPic = nz(o.optString("vod_pic", o.optString("pic")));
        this.vodRemarks = nz(o.optString("vod_remarks", o.optString("remarks")));
        this.vodYear = nz(o.optString("vod_year", o.optString("year")));
        this.vodArea = nz(o.optString("vod_area", o.optString("area")));
        this.vodContent = nz(o.optString("vod_content", o.optString("content")));
        this.vodPlayFrom = nz(o.optString("vod_play_from"));
        this.vodPlayUrl = nz(o.optString("vod_play_url"));
        this.typeName = nz(o.optString("type_name"));
        this.vodActor = nz(o.optString("vod_actor", o.optString("actor")));
        this.vodDirector = nz(o.optString("vod_director", o.optString("director")));
        this.vodTag = nz(o.optString("vod_tag"));
        this.action = nz(o.optString("action"));
        return this;
    }

    /**
     * 调试输出。
     *
     * @return 摘要
     */
    @Override
    public String toString() {
        return "Vod{" + vodId + ", " + vodName + ", " + vodRemarks + "}";
    }

    /**
     * null 安全转字符串。
     *
     * @param s 原文
     * @return 非 null 字符串
     */
    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
