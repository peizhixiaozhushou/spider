package com.example.tvbox.plain.film;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Ikanbot 的首页筛选器数据。
 *
 * <h2>为什么单独一个类</h2>
 * 上游把这份约 1.5 KB 的 JSON 内联在 homeContent 里。抽出来后可读性提升明显。
 *
 * <h2>四个键都不相同，且有一处中间的空格</h2>
 * <pre>
 *   "kanlist/全部"            → 分类 15 项（片单）
 *   "billboard"               → 空数组（榜单没有筛选器）
 *   "hot/index-movie-热门"    → 分类 17 项（电影）
 *   "hot/index-tv-热门"       → 分类 10 项（剧集）
 * </pre>
 * 键名里带 {@code /} 与中文，与 {@code class[].type_id} <b>逐字对应</b>，
 * 改一个字符宿主就找不到筛选器。
 *
 * <h2>值里的空格是故意的</h2>
 * 例如 {@code "kanlist/ 科幻"}（斜杠后一个空格）、
 * {@code "hot/index-movie-豆瓣高 分"}，以及展示名 {@code "冷 门佳片"}。
 * 站点按值精确匹配，不要"顺手清理"。
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Ikanbot.java}（{@code homeContent} 内联字面量）
 */
public final class IkanbotFilters {

    private IkanbotFilters() {
    }

    /** 片单（kanlist/全部）筛选器 */
    public static final String KANLIST =

            "[{\"name\":\"分类\",\"value\":[{\"v\":\"\",\"n\":\"全部\"},{\"v\":\"kanlist/剧情\",\"n\":\"剧情\"},{\"v\":\"kanlist/情感\",\"n\":\"情感\"},{\"v\":\"kanlist/治愈\",\"n\":\"治愈\"},{\"v\":\"kanlist/爱情\",\"n\":\"爱情\"},{\"v\":\"kanlist/颁奖\",\"n\":\"颁奖\"},{\"v\":\"kanlist/喜剧\",\"n\":\"喜剧\"},{\"v\":\"kanlist/获奖\",\"n\":\"获奖\"},{\"v\":\"kanlist/ 科幻\",\"n\":\"科幻\"},{\"v\":\"kanlist/漫威\",\"n\":\"漫威\"},{\"v\":\"kanlist/甜蜜\",\"n\":\"甜蜜\"},{\"v\":\"kanlist/悬疑\",\"n\":\"悬疑\"},{\"v\":\"kanlist/励志\",\"n\":\"励志\"},{\"v\":\"kanlist/烧脑\",\"n\":\"烧脑\"},{\"v\":\"kanlist/友情\",\"n\":\"友情\"}],\"key\":\"cateId\"}]";

    /** 榜单（billboard）筛选器 —— 上游为空数组 */
    public static final String BILLBOARD =
            "[]";

    /** 电影热门（hot/index-movie-热门）筛选器 */
    public static final String HOT_MOVIE =

            "[{\"name\":\"分类\",\"value\":[{\"v\":\"\",\"n\":\"全部\"},{\"v\":\"hot/index-movie-最新\",\"n\":\"最新\"},{\"v\":\"hot/index-movie-经典\",\"n\":\"经典\"},{\"v\":\"hot/index-movie-豆瓣高 分\",\"n\":\"豆瓣高分\"},{\"v\":\"hot/index-movie-冷门佳片\",\"n\":\"冷 门佳片\"},{\"v\":\"hot/index-movie-华语\",\"n\":\"华语\"},{\"v\":\"hot/index-movie-欧美\",\"n\":\"欧美\"},{\"v\":\"hot/index-movie-韩国\",\"n\":\"韩国\"},{\"v\":\"hot/index-movie-日本\",\"n\":\"日本\"},{\"v\":\"hot/index-movie-动作\",\"n\":\"动作\"},{\"v\":\"hot/index-movie-喜剧\",\"n\":\"喜剧\"},{\"v\":\"hot/index-movie-爱情\",\"n\":\"爱情\"},{\"v\":\"hot/index-movie-科幻\",\"n\":\"科幻\"},{\"v\":\"hot/index-movie-悬疑\",\"n\":\"悬疑\"},{\"v\":\"hot/index-movie-恐怖\",\"n\":\"恐怖\"},{\"v\":\"hot/index-movie-成长\",\"n\":\"成长\"},{\"v\":\"hot/index-movie-豆瓣top250\",\"n\":\"豆瓣top250\"}],\"key\":\"cateId\"}]";

    /** 剧集热门（hot/index-tv-热门）筛选器 */
    public static final String HOT_TV =

            "[{\"name\":\"分类\",\"value\":[{\"v\":\"\",\"n\":\"全部\"},{\"v\":\"hot/index-tv-美剧\",\"n\":\"美剧\"},{\"v\":\"hot/index-tv-英剧\",\"n\":\"英剧\"},{\"v\":\"hot/index-tv-韩剧\",\"n\":\"韩剧\"},{\"v\":\"hot/index-tv-日剧\",\"n\":\"日剧\"},{\"v\":\"hot/index-tv-国产剧\",\"n\":\"国产剧\"},{\"v\":\"hot/index-tv-港剧\",\"n\":\"港剧\"},{\"v\":\"hot/index-tv-日本动画\",\"n\":\"日本动画\"},{\"v\":\"hot/index-tv-综艺\",\"n\":\"综艺\"},{\"v\":\"hot/index-tv-纪录片\",\"n\":\"纪录片\"}],\"key\":\"cateId\"}]";

    /**
     * 取某个分类的筛选器。
     *
     * @param typeId class[].type_id
     * @return 筛选器数组；未知分类返回空数组
     */
    public static JSONArray of(String typeId) {
        String s;
        if ("kanlist/全部".equals(typeId)) {
            s = KANLIST;
        } else if ("billboard".equals(typeId)) {
            s = BILLBOARD;
        } else if ("hot/index-movie-热门".equals(typeId)) {
            s = HOT_MOVIE;
        } else if ("hot/index-tv-热门".equals(typeId)) {
            s = HOT_TV;
        } else {
            return new JSONArray();
        }
        try {
            return new JSONArray(s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /**
     * 完整筛选器对象。
     *
     * @return {@code {"<typeId>":[…]}}；构造失败返回空对象
     */
    public static JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("kanlist/全部", of("kanlist/全部"));
            o.put("billboard", of("billboard"));
            o.put("hot/index-movie-热门", of("hot/index-movie-热门"));
            o.put("hot/index-tv-热门", of("hot/index-tv-热门"));
        } catch (Exception e) {
            return new JSONObject();
        }
        return o;
    }
}
