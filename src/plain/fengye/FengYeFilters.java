package com.example.tvbox.plain.fengye;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * FengYe 的首页筛选器数据（4 个分类 / 共 18 组筛选值）。
 *
 * <h2>为什么单独一个类</h2>
 * 上游把它内联在 {@code homeContent} 里（2501 字符的单行字面量），抽出来后可读性与可测试性都更好。
 *
 * <h2>键是分类 id，必须与 {@code class[].type_id} 逐字一致</h2>
 * <ul>
 *   <li>{@code "2"} → class(4), area(7), year(8), lang(4), letter(5), sort(3)</li>
 *   <li>{@code "1"} → class(8), area(7), year(8), lang(4), letter(5), sort(3)</li>
 *   <li>{@code "4"} → class(3), year(4), sort(3)</li>
 *   <li>{@code "3"} → class(3), year(4), sort(3)</li>
 * </ul>
 *
 * <h2>值与展示名全部照抄上游</h2>
 * 含上游的重复项/笔误（例如同一筛选组里重复出现的「惊悚」、
 * 「其他 → 泰国」这类名值不一致），一律不"顺手修正" —— 站点按值精确匹配。
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/FengYe.java}。抽取方式：把 {@code new JSONObject("…")}
 * 的字面量反转义后用 {@code json.loads} 验证通过，再按 typeId 拆成下面的常量。
 */
public final class FengYeFilters {

    /** 全部 typeId（顺序与上游内联字面量一致） */
    public static final String[] KEYS = {"2", "1", "4", "3"};

    private FengYeFilters() {
    }

    /** 分类 {@code 2} 的筛选器（6 组：class, area, year, lang, letter, sort） */
    public static final String TYPE_2 =
            "[{\"key\": \"class\", \"name\": \"类型\", \"value\": [{\"n\": \"全部\", \"v\": \"2\"}, {\"n\": \"国产剧\", \"v\": \"13\"}, {\"n\": \"日韩剧\", \"v\": \"15\"}, {\"n\": \"海外剧\", \"v\": \"16\"}]}, {\"key\": \"area\", \"name\": \"地区\", \"value\": [{\"n\": \"全部\", \"v\": \"\"}, {\"n\": \"大陆\", \"v\": \"大陆\"}, {\"n\": \"香港\", \"v\": \"香港\"}, {\"n\": \"台湾\", \"v\": \"台湾\"}, {\"n\": \"美国\", \"v\": \"美国\"}, {\"n\": \"韩国\", \"v\": \"韩国\"}, {\"n\": \"日本\", \"v\": \"日本\"}]}, {\"key\": \"year\", \"name\": \"年份\", \"value\": [{\"n\": \"全部\", \"v\": \"\"}, {\"n\": \"2026\", \"v\": \"2026\"}, {\"n\": \"2025\", \"v\": \"2025\"}, {\"n\": \"2024\", \"v\": \"2024\"}, {\"n\": \"2023\", \"v\": \"2023\"}, {\"n\": \"2022\", \"v\": \"2022\"}, {\"n\": \"2021\", \"v\": \"2021\"}, {\"n\": \"2020\", \"v\": \"2020\"}]}, {\"key\": \"lang\", \"name\": \"语言\", \"value\": [{\"n\": \"全部\", \"v\": \"\"}, {\"n\": \"国语\", \"v\": \"国语\"}, {\"n\": \"英语\", \"v\": \"英语\"}, {\"n\": \"粤语\", \"v\": \"粤语\"}]}, {\"key\": \"letter\", \"name\": \"字母\", \"value\": [{\"n\": \"全部\", \"v\": \"\"}, {\"n\": \"A\", \"v\": \"A\"}, {\"n\": \"B\", \"v\": \"B\"}, {\"n\": \"C\", \"v\": \"C\"}, {\"n\": \"D\", \"v\": \"D\"}]}, {\"key\": \"sort\", \"name\": \"排序\", \"value\": [{\"n\": \"时间\", \"v\": \"time\"}, {\"n\": \"人气\", \"v\": \"hits\"}, {\"n\": \"评分\", \"v\": \"score\"}]}]";

    /** 分类 {@code 1} 的筛选器（6 组：class, area, year, lang, letter, sort） */
    public static final String TYPE_1 =
            "[{\"key\": \"class\", \"name\": \"类型\", \"value\": [{\"n\": \"全部\", \"v\": \"1\"}, {\"n\": \"动作片\", \"v\": \"6\"}, {\"n\": \"喜剧片\", \"v\": \"7\"}, {\"n\": \"恐怖片\", \"v\": \"8\"}, {\"n\": \"科幻片\", \"v\": \"9\"}, {\"n\": \"爱情片\", \"v\": \"10\"}, {\"n\": \"剧情片\", \"v\": \"11\"}, {\"n\": \"战争片\", \"v\": \"12\"}]}, {\"key\": \"area\", \"name\": \"地区\", \"value\": [{\"n\": \"全部\", \"v\": \"\"}, {\"n\": \"大陆\", \"v\": \"大陆\"}, {\"n\": \"香港\", \"v\": \"香港\"}, {\"n\": \"台湾\", \"v\": \"台湾\"}, {\"n\": \"美国\", \"v\": \"美国\"}, {\"n\": \"韩国\", \"v\": \"韩国\"}, {\"n\": \"日本\", \"v\": \"日本\"}]}, {\"key\": \"year\", \"name\": \"年份\", \"value\": [{\"n\": \"全部\", \"v\": \"\"}, {\"n\": \"2026\", \"v\": \"2026\"}, {\"n\": \"2025\", \"v\": \"2025\"}, {\"n\": \"2024\", \"v\": \"2024\"}, {\"n\": \"2023\", \"v\": \"2023\"}, {\"n\": \"2022\", \"v\": \"2022\"}, {\"n\": \"2021\", \"v\": \"2021\"}, {\"n\": \"2020\", \"v\": \"2020\"}]}, {\"key\": \"lang\", \"name\": \"语言\", \"value\": [{\"n\": \"全部\", \"v\": \"\"}, {\"n\": \"国语\", \"v\": \"国语\"}, {\"n\": \"英语\", \"v\": \"英语\"}, {\"n\": \"粤语\", \"v\": \"粤语\"}]}, {\"key\": \"letter\", \"name\": \"字母\", \"value\": [{\"n\": \"全部\", \"v\": \"\"}, {\"n\": \"A\", \"v\": \"A\"}, {\"n\": \"B\", \"v\": \"B\"}, {\"n\": \"C\", \"v\": \"C\"}, {\"n\": \"D\", \"v\": \"D\"}]}, {\"key\": \"sort\", \"name\": \"排序\", \"value\": [{\"n\": \"时间\", \"v\": \"time\"}, {\"n\": \"人气\", \"v\": \"hits\"}, {\"n\": \"评分\", \"v\": \"score\"}]}]";

    /** 分类 {@code 4} 的筛选器（3 组：class, year, sort） */
    public static final String TYPE_4 =
            "[{\"key\": \"class\", \"name\": \"类型\", \"value\": [{\"n\": \"全部\", \"v\": \"4\"}, {\"n\": \"国产动漫\", \"v\": \"25\"}, {\"n\": \"日韩动漫\", \"v\": \"26\"}]}, {\"key\": \"year\", \"name\": \"年份\", \"value\": [{\"n\": \"全部\", \"v\": \"\"}, {\"n\": \"2026\", \"v\": \"2026\"}, {\"n\": \"2025\", \"v\": \"2025\"}, {\"n\": \"2024\", \"v\": \"2024\"}]}, {\"key\": \"sort\", \"name\": \"排序\", \"value\": [{\"n\": \"时间\", \"v\": \"time\"}, {\"n\": \"人气\", \"v\": \"hits\"}, {\"n\": \"评分\", \"v\": \"score\"}]}]";

    /** 分类 {@code 3} 的筛选器（3 组：class, year, sort） */
    public static final String TYPE_3 =
            "[{\"key\": \"class\", \"name\": \"类型\", \"value\": [{\"n\": \"全部\", \"v\": \"3\"}, {\"n\": \"大陆综艺\", \"v\": \"21\"}, {\"n\": \"日韩综艺\", \"v\": \"22\"}]}, {\"key\": \"year\", \"name\": \"年份\", \"value\": [{\"n\": \"全部\", \"v\": \"\"}, {\"n\": \"2026\", \"v\": \"2026\"}, {\"n\": \"2025\", \"v\": \"2025\"}, {\"n\": \"2024\", \"v\": \"2024\"}]}, {\"key\": \"sort\", \"name\": \"排序\", \"value\": [{\"n\": \"时间\", \"v\": \"time\"}, {\"n\": \"人气\", \"v\": \"hits\"}, {\"n\": \"评分\", \"v\": \"score\"}]}]";

    /**
     * 按分类 id 取筛选器数组。
     *
     * @param typeId 分类 id（{@code class[].type_id}）
     * @return 解析后的 JSONArray；未知 id 返回空数组（永不 null）
     */
    public static JSONArray of(String typeId) {
        String r = raw(typeId);
        if (r.isEmpty()) {
            return new JSONArray();
        }
        try {
            return new JSONArray(r);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /**
     * 分类 id → 常量原文。
     *
     * @param typeId 分类 id
     * @return JSON 文本；未知 id 返回空串
     */
    public static String raw(String typeId) {
        if ("2".equals(typeId)) {
            return TYPE_2;
        }
        if ("1".equals(typeId)) {
            return TYPE_1;
        }
        if ("4".equals(typeId)) {
            return TYPE_4;
        }
        if ("3".equals(typeId)) {
            return TYPE_3;
        }
        return "";
    }

    /**
     * 完整筛选器对象（宿主 {@code filters} 字段的形态）。
     *
     * @return 以 typeId 为键的对象；构造失败返回空对象
     */
    public static JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("2", of("2"));
            o.put("1", of("1"));
            o.put("4", of("4"));
            o.put("3", of("3"));
        } catch (Exception e) {
            return new JSONObject();
        }
        return o;
    }
}
