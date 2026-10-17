package com.example.tvbox.common;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TVBox 标准响应构造器。
 *
 * <p>把「站点抓取结果」与「宿主期望的 JSON 结构」解耦。宿主只认四种返回体，
 * 本类一次性提供，避免每个插件重复手写 JSON 字符串拼接（这是原混淆代码里最容易出错的部分）。
 *
 * <p>四种标准返回体：
 * <pre>
 * homeContent     {"class":[{"type_id","type_name"}], "list":[Vod...], "filters":{}}
 * categoryContent {"page":1, "pagecount":10, "limit":20, "total":200, "list":[Vod...]}
 * detailContent   {"list":[{...Vod..., vod_play_from, vod_play_url}]}
 * searchContent   {"list":[Vod...]}
 * </pre>
 *
 * <p>典型用法：
 * <pre>{@code
 * // 首页
 * VodBuilder b = VodBuilder.create()
 *         .clazz("1", "电影").clazz("2", "电视剧")
 *         .filter("1", filterJson)
 *         .add(new Vod().id("1").name("示例"));
 * return b.homeContent();
 *
 * // 分类页
 * return VodBuilder.create().addAll(list).page(pg, total, 20).categoryContent();
 *
 * // 详情
 * return VodBuilder.detail(vodWithPlayUrl).detailContent();
 * }</pre>
 */
public class VodBuilder {

    private static final String TAG = "VodBuilder";

    /** 分类页默认每页条数（TVBox 常见取值） */
    public static final int DEFAULT_LIMIT = 20;
    /** 空响应体，宿主要求返回 "{}" 而不是空串 */
    public static final String EMPTY_RESPONSE = "{}";

    /** 分类列表（type_id → type_name，保持声明顺序） */
    private final LinkedHashMap<String, String> classes = new LinkedHashMap<>();
    /** filters 表（type_id → 筛选 JSON） */
    private final LinkedHashMap<String, Object> filters = new LinkedHashMap<>();
    /** 分类的 type_flag（type_id → flag），无则不输出 */
    private final LinkedHashMap<String, String> classFlags = new LinkedHashMap<>();
    /** 影片列表 */
    private final List<Vod> list = new ArrayList<>();

    /** 当前页码，从 1 开始 */
    private int page = 1;
    /** 总条数；0 表示未知（TVBox 允许） */
    private int total = 0;
    /** 每页条数 */
    private int limit = DEFAULT_LIMIT;

    /** 构造空构造器。 */
    public VodBuilder() {
    }

    /**
     * 创建实例。
     *
     * @return 新构造器
     */
    public static VodBuilder create() {
        return new VodBuilder();
    }

    /**
     * 用一组影片快速创建。
     *
     * @param vods 影片集合，可为 null
     * @return 新构造器
     */
    /**
     * 把单个 {@link Vod} 转成 TVBox 标准字段的 {@code JSONObject}。
     *
     * <p>语义就是 {@link Vod#toJSONObject()} —— 这里是同一件事的<b>静态入口</b>，
     * 方便在"手工拼列表"的代码里写成 {@code arr.put(VodBuilder.toJson(v))}，
     * 读起来比 {@code arr.put(v.toJSONObject())} 更能表达"这是在构建响应"。
     *
     * <p>为什么不直接让大家用 {@code Vod#toJSONObject()}：两者等价，
     * 但列表项的形状属于**响应契约**的一部分，放在 builder 侧更不容易被误改。
     * 两个入口共享同一份字段映射实现（本方法直接转发），因此不会漂移。
     *
     * @param vod 待转换的条目，可为 null
     * @return 新的 JSONObject；{@code vod} 为 null 时返回空对象（不返回 null）
     */
    public static JSONObject toJson(Vod vod) {
        return vod == null ? new JSONObject() : vod.toJSONObject();
    }

    public static VodBuilder of(Collection<Vod> vods) {
        return new VodBuilder().addAll(vods);
    }

    /**
     * 用单条影片快速创建（详情页最常用）。
     *
     * @param vod 影片，可为 null
     * @return 新构造器
     */
    public static VodBuilder detail(Vod vod) {
        return new VodBuilder().add(vod);
    }

    // ── 累加 ────────────────────────────────────────────────

    /**
     * 追加一条影片。
     *
     * @param vod 影片，null 时忽略
     * @return this
     */
    public VodBuilder add(Vod vod) {
        if (vod != null) {
            list.add(vod);
        }
        return this;
    }

    /**
     * 批量追加影片。
     *
     * @param vods 影片集合，null 时忽略
     * @return this
     */
    public VodBuilder addAll(Collection<Vod> vods) {
        if (vods != null) {
            for (Vod v : vods) {
                add(v);
            }
        }
        return this;
    }

    /**
     * 追加一个分类。
     *
     * @param typeId   分类 id（站点内部标识）
     * @param typeName 分类显示名
     * @return this
     */
    public VodBuilder clazz(String typeId, String typeName) {
        if (typeId != null && !typeId.isEmpty()) {
            classes.put(typeId, typeName == null ? "" : typeName);
        }
        return this;
    }

    /**
     * 追加一个带 {@code type_flag} 的分类。
     *
     * <p>{@code type_flag} 是宿主用来改变分类行为的小开关，目前已知取值：
     * <ul>
     *   <li>{@code "1"} —— 该分类是"文件夹/目录"型（默认）</li>
     *   <li>{@code "2"} —— 该分类是"筛选/片单"型，宿主会当作多选筛选入口</li>
     * </ul>
     * 传 {@code null} 或空串等价于不输出该字段。
     *
     * @param typeId   分类 id
     * @param typeName 分类名
     * @param typeFlag type_flag，可为 null
     * @return this
     */
    public VodBuilder clazzFlag(String typeId, String typeName, String typeFlag) {
        clazz(typeId, typeName);
        if (typeFlag != null && !typeFlag.isEmpty()) {
            classFlags.put(typeId, typeFlag);
        }
        return this;
    }

    /**
     * 批量追加分类。
     *
     * @param typeIdToName 分类表（保持插入顺序）
     * @return this
     */
    public VodBuilder clazzAll(Map<String, String> typeIdToName) {
        if (typeIdToName != null) {
            for (Map.Entry<String, String> e : typeIdToName.entrySet()) {
                clazz(e.getKey(), e.getValue());
            }
        }
        return this;
    }

    /**
     * 追加一个分类筛选定义（首页 filters 字段）。
     *
     * <p>宿主结构里 {@code filters} 的值本身是<b>数组</b>
     * （{@code [{"name":"类型","value":[{"v","n"}],"key":"1"}, …]}），
     * 因此参数类型为 {@link Object}，允许直接传 {@link JSONArray}。
     *
     * @param typeId 分类 id
     * @param filter 筛选值（通常是 JSONArray），可为 null
     * @return this
     */
    public VodBuilder filter(String typeId, Object filter) {
        if (typeId != null && !typeId.isEmpty()) {
            filters.put(typeId, filter == null ? new JSONObject() : filter);
        }
        return this;
    }

    /**
     * 设置分页信息。
     *
     * @param page  当前页（从 1 开始，<=0 按 1）
     * @param total 总条数，未知填 0
     * @param limit 每页条数，<=0 用 {@link #DEFAULT_LIMIT}
     * @return this
     */
    public VodBuilder page(int page, int total, int limit) {
        this.page = page <= 0 ? 1 : page;
        this.total = Math.max(0, total);
        this.limit = limit <= 0 ? DEFAULT_LIMIT : limit;
        return this;
    }

    /**
     * 设置当前页码。
     *
     * @param page 页码，<=0 按 1
     * @return this
     */
    public VodBuilder page(int page) {
        this.page = page <= 0 ? 1 : page;
        return this;
    }

    /**
     * 当前累积的影片列表（只读视图）。
     *
     * @return 非 null 列表
     */
    public List<Vod> vods() {
        return new ArrayList<>(list);
    }

    /**
     * 当前分页参数。
     *
     * @return {page, total, limit} 的三元素数组
     */
    public int[] paging() {
        return new int[]{page, total, limit};
    }

    // ── 四种标准输出 ────────────────────────────────────────

    /**
     * 首页返回体。
     *
     * <p>结构：{@code {"class":[...],"list":[...],"filters":{...}}}
     *
     * @return JSON 字符串；构造失败返回 {@code "{}"}
     */
    public String homeContent() {
        JSONObject root = new JSONObject();
        try {
            JSONArray cls = new JSONArray();
            for (Map.Entry<String, String> e : classes.entrySet()) {
                JSONObject c = new JSONObject();
                c.put("type_id", e.getKey());
                c.put("type_name", e.getValue());
                String flag = classFlags.get(e.getKey());
                if (flag != null && !flag.isEmpty()) {
                    c.put("type_flag", flag);
                }
                cls.put(c);
            }
            root.put("class", cls);
            root.put("list", toArray(list, false));
            root.put("filters", filtersJson());
            return root.toString();
        } catch (Throwable e) {
            Log.e(TAG, "homeContent failed", e);
            return EMPTY_RESPONSE;
        }
    }

    /**
     * 分类页返回体。
     *
     * <p>结构：{@code {"page":1,"pagecount":N,"limit":20,"total":M,"list":[...]}}。
     * pagecount 在 total 已知时由 {@code ceil(total/limit)} 计算，否则回退为当前页 + 1
     * （表示「可能还有下一页」），这是原插件普遍采用的做法。
     *
     * @return JSON 字符串；构造失败返回 {@code "{}"}
     */
    public String categoryContent() {
        JSONObject root = new JSONObject();
        try {
            int pageCount = computePageCount();
            root.put("page", page);
            root.put("pagecount", pageCount);
            root.put("limit", limit);
            root.put("total", total);
            root.put("list", toArray(list, false));
            return root.toString();
        } catch (Throwable e) {
            Log.e(TAG, "categoryContent failed", e);
            return EMPTY_RESPONSE;
        }
    }

    /**
     * 详情页返回体。
     *
     * <p>结构：{@code {"list":[{...vod, vod_play_from, vod_play_url}]}}。
     * 详情页约定只返回一条；若累积了多条，取第一条。
     *
     * @return JSON 字符串；无影片时返回 {@code "{}"}
     */
    public String detailContent() {
        if (list.isEmpty()) {
            Log.w(TAG, "detailContent without vod");
            return EMPTY_RESPONSE;
        }
        JSONObject root = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            Vod v = list.get(0);
            arr.put(toObject(v, true));
            root.put("list", arr);
            return root.toString();
        } catch (Throwable e) {
            Log.e(TAG, "detailContent failed", e);
            return EMPTY_RESPONSE;
        }
    }

    /**
     * 搜索返回体。
     *
     * <p>结构：{@code {"list":[...]}}。
     *
     * @return JSON 字符串；构造失败返回 {@code "{}"}
     */
    public String searchContent() {
        JSONObject root = new JSONObject();
        try {
            root.put("list", toArray(list, false));
            return root.toString();
        } catch (Throwable e) {
            Log.e(TAG, "searchContent failed", e);
            return EMPTY_RESPONSE;
        }
    }

    /**
     * 首页推荐（{@code Spider#homeVideoContent()}）的返回体。
     *
     * <p><b>与 {@link #searchContent()} 输出完全相同</b> —— 都是
     * {@code {"list":[...]}}（raw 数组，不做 {@code vod_play_*} 字段提升）。
     * 存在的意义只有一条：<b>让调用点的名字和宿主的契约名对上</b>。
     *
     * <h3>为什么要有这个别名（而不是让大家一律写 searchContent()）</h3>
     * 上游的 {@code homeVideoContent} 与 {@code searchContent} 共用同一个
     * 列表响应构造器（观测到的 {@code merge.p028c.c.n(list)}），
     * 产出形状确实一模一样。但反过来**不成立**：
     *
     * <pre>
     * homeContent()  → {"class":[...], "list":[...], "filters":{...}}   ← 多两个键
     * searchContent()/homeVideoContent() → {"list":[...]}
     * </pre>
     *
     * 于是出现过一个真实的坑：某域在 {@code homeVideoContent()} 里图省事调了
     * {@code homeContent()}，输出多出 {@code class}/{@code filters} 两个宿主不期望的键。
     * 有个语义化的名字摆在旁边，比靠注释提醒可靠。
     *
     * @return JSON 字符串；构造失败返回 {@code "{}"}
     */
    public String homeVideoContent() {
        return searchContent();
    }

    /**
     * 播放解析返回体（直链，无需嗅探时使用）。
     *
     * @param url  播放地址
     * @param type 内容类型，如 {@code application/vnd.apple.mpegurl}
     * @return JSON 字符串；构造失败返回 {@code "{}"}
     */
    public static String playerContent(String url, String type) {
        JSONObject root = new JSONObject();
        try {
            root.put("parse", 0);
            root.put("playUrl", "");
            root.put("url", url == null ? "" : url);
            root.put("header", "");
            if (type != null && !type.isEmpty()) {
                root.put("contentType", type);
            }
            return root.toString();
        } catch (Throwable e) {
            Log.e(TAG, "playerContent failed", e);
            return EMPTY_RESPONSE;
        }
    }

    // ── 内部 ────────────────────────────────────────────────

    /**
     * 计算 pagecount。
     *
     * @return 总页数；total 未知时返回 page + 1
     */
    private int computePageCount() {
        if (total <= 0) {
            return page + 1;
        }
        int n = (int) Math.ceil(total / (double) limit);
        return Math.max(n, page);
    }

    /**
     * filters 转 JSONObject。
     *
     * @return filters 对象（无内容时为空对象）
     */
    private JSONObject filtersJson() throws Exception {
        JSONObject f = new JSONObject();
        for (Map.Entry<String, Object> e : filters.entrySet()) {
            f.put(e.getKey(), e.getValue());
        }
        return f;
    }

    /**
     * 列表转 JSONArray。
     *
     * @param src      影片列表
     * @param withPlay true 时附带播放字段（详情页用）
     * @return JSONArray
     */
    private static JSONArray toArray(List<Vod> src, boolean withPlay) throws Exception {
        JSONArray arr = new JSONArray();
        for (Vod v : src) {
            arr.put(toObject(v, withPlay));
        }
        return arr;
    }

    /**
     * 单条影片转 JSONObject；列表场景剔除详情专用字段，减少传输体积。
     *
     * @param v        影片
     * @param withPlay true 保留 vod_play_from / vod_play_url
     * @return JSONObject
     */
    private static JSONObject toObject(Vod v, boolean withPlay) {
        JSONObject o = v.toJSONObject();
        if (!withPlay) {
            try {
                o.remove("vod_play_from");
                o.remove("vod_play_url");
                o.remove("vod_content");
                o.remove("vod_actor");
                o.remove("vod_director");
            } catch (Throwable ignored) {
                // stub 实现可能不支持 remove，忽略即可
            }
        }
        return o;
    }
}
