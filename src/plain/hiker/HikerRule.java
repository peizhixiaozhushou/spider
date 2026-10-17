package com.example.tvbox.plain.hiker;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XYQHiker 单站点规则的模型 —— 整个采集器「唯一的行为来源」。
 *
 * <p>反编译原文: {@code ae/decompiled/XYQHiker.java}
 * （{@code ab}/{@code aa} 在 {@code :384-392}，字段清单散落在
 * {@code homeContent} {@code:954-1268}、{@code homeVideoContent} {@code:1269-3080}、
 * {@code s} / {@code ah} / {@code detailContent} / {@code playerContent} / {@code t}）。
 * smali 权威对照: {@code baksmali re/dex/classes.dex} →
 * {@code /tmp/hiker-smali/com/github/catvod/spider/XYQHiker.smali}。
 *
 * <h2>原混淆名 → 语义名对照表</h2>
 * <table>
 *   <tr><th>原</th><th>smali 行</th><th>本类</th></tr>
 *   <tr><td>{@code ab(String,String)}</td><td>1514</td><td>{@link #value}</td></tr>
 *   <tr><td>{@code aa(String)}</td><td>1502</td><td>{@link #raw}</td></tr>
 *   <tr><td>{@code XYQHiker.j}</td><td>—</td><td>{@link #root()}（解析后的规则 JSON）</td></tr>
 *   <tr><td>{@code XYQHiker.i}</td><td>—</td><td>{@link #source()}（ext 原始串）</td></tr>
 *   <tr><td>{@code w()}</td><td>21936</td><td>{@link #of}（把 ext 串变成规则对象）</td></tr>
 * </table>
 *
 * <h2>{@code ab} 的「空值」语义（上游原样保留）</h2>
 * 取值后若命中 {@code ""}、{@code "空"}、{@code "&&"} 三者之一，一律回落到默认值。
 * 也就是说 DSL 里写 {@code "空"} 等价于「不配」，这是作者留的显式空标记。
 *
 * <h2>「中文名 or 英文别名」—— 表驱动的核心</h2>
 * 上游的取值范式是
 * <pre>
 *   String key = aa("中文名").isEmpty() ? "英文别名" : "中文名";
 *   String v   = ab(key, 默认值);
 * </pre>
 * 即<b>两套字段名并存</b>，旧的 api.json 用英文、新的用中文。本类把它收敛为
 * {@link #value(Field)} / {@link #field(String)}：一张表同时登记中文名、英文别名、
 * 默认值和「谁在消费它」。这样 {@link XYQHikerPlain} 的 5 个方法里就不再出现
 * 上游那种「60 个局部变量 + 60 个 if-empty」的样板代码。
 *
 * <p>完整字段表见 {@link #FIELDS}（{@link XYQHiker_DSL 说明见 maintain/evidence/XYQHiker_DSL.md}）。
 *
 * <h2>多站点作用域（本项目扩展，非上游行为）</h2>
 * TVBox 会给同一个 Spider 实例传入不同站点的 ext。上游 XYQHiker 只认一份扁平
 * JSON（{@code this.j}），多个站点配在一起会互相串味。
 * 本类引入 {@link #scoped(String)}：当 ext 顶层存在 {@code "sites"} 对象时，
 * 按站点键取子对象，查值先查子对象、再回落到顶层（共享默认值）。
 * 该行为标记为【本项目扩展】，已在 {@link #scoped} 的 javadoc 中声明；
 * 未配置 {@code "sites"} 时行为与上游完全一致。
 */
public final class HikerRule {

    /** 一个 DSL 字段的登记项：中文名 / 英文别名 / 默认值 / 消费点。 */
    public static final class Field {
        /** 中文字段名（新版 api.json 用） */
        public final String cn;
        /** 英文别名字段名（旧版 api.json 用）；无别名时为 {@code null} */
        public final String en;
        /** 缺失时的默认值 */
        public final String def;
        /** 消费它的位置，用于文档与排错 */
        public final String consumer;

        public Field(String cn, String en, String def, String consumer) {
            this.cn = cn;
            this.en = en;
            this.def = def;
            this.consumer = consumer;
        }

        /** 解析时实际使用的键名：优先中文，缺则英文。 */
        public String key(JSONObject scope) {
            if (cn != null && scope != null && has(scope, cn)) return cn;
            if (en != null && scope != null && has(scope, en)) return en;
            return cn != null ? cn : en;
        }

        @Override public String toString() {
            return cn + (en == null ? "" : "/" + en) + " def=" + def;
        }
    }

    /** 上游「空值」标记：命中即回落默认值。 */
    public static final String[] EMPTY_MARKERS = {"", "空", "&&"};

    /** 上游常量 {@code a}：被反爬/验证页拦下的 URL 特征；{@code checkveriry} 用它判定。 */
    public static final String[] ANTI_BOT_URL_FEATURES = {
            "ajax/verify_check", "ajax.php?ac=code_check", "/verify/index.html", "?scheckAC=check"};

    /** 上游常量 {@code b}：可当「恒等」处理的属性名。 */
    public static final String[] IDENTITY_ATTRS = HikerSelector.IDENTITY_ATTRS;

    // ══════════════════════════════════════════════════════════════
    // 字段表（= DSL 的完整清单）
    // ══════════════════════════════════════════════════════════════

    private static final List<Field> F = new ArrayList<Field>();
    private static Field f(String cn, String en, String def, String consumer) {
        Field x = new Field(cn, en, def, consumer);
        F.add(x);
        return x;
    }

    // ── 通用 ────────────────────────────────────────────────────────
    public static final Field HEADERS = f("请求头参数", "Headers", "", "y() 请求头；playerContent 也用它");
    public static final Field SEARCH_HEADERS = f("搜索请求头参数", "SHeaders", "", "ac() 搜索请求头");
    public static final Field CHARSET = f("网页编码格式", "Coding_format", "UTF-8", "所有取页面的方法");
    public static final Field DEBUG = f("DEBUG", null, "0", "l 开关：写 \"1\"/\"是\" 才打 Init.show 提示");
    public static final Field OCR_API = f("OCR_API", null, "https://api.nn.ci/ocr/b64/text", "r() 验证码识别");
    public static final Field ANAL_MAC_PLAYER = f("分析MacPlayer", "Anal_MacPlayer", "2", "playerContent");
    public static final Field SNIFF_KEYWORDS = f("手动嗅探视频链接关键词", "VideoFormat",
            ".m3u8#.mp4#.flv#video/tos#.mp3#.m4a", "isVideoFormat");
    public static final Field SNIFF_FILTER = f("手动嗅探视频链接过滤词", "VideoFilter",
            "=http#.html", "isVideoFormat");
    public static final Field SNIFF_MANUAL = f("是否开启手动嗅探", "ManualSniffer", "", "manualVideoCheck");
    public static final Field PIC_PROXY = f("图片是否需要代理", "PicNeedProxy", "", "列表/详情里的图片是否套 x()");
    public static final Field FORCE_PLAY = f("链接是否直接播放", "force_play", "", "detailContent 是否跳过抓取");
    public static final Field PLAY_PREFIX = f("直接播放链接加前缀", "play_prefix", "", "直接播放时拼前缀");
    public static final Field PLAY_SUFFIX = f("直接播放链接加后缀", "play_suffix", "", "直接播放时拼后缀");
    public static final Field PLAY_HEADER = f("直接播放直链视频请求头", "play_header", "", "直接播放时的请求头");

    // ── 首页分类 + 筛选面板 ─────────────────────────────────────────
    public static final Field CLASS_NAME = f("分类名称", "class_name", "", "homeContent → class[].type_name");
    public static final Field CLASS_VALUE = f("分类名称替换词", "class_value", "", "homeContent → class[].type_id；首页聚合也用它");
    public static final Field CLASS_URL = f("分类链接", "class_url", "", "homeContent → class[].type_flag");
    public static final Field FCLASS_NAME = f("筛选子分类名称", "fclass_name", "", "t() 子分类");
    public static final Field FCLASS_VALUE = f("筛选子分类替换词", "fclass_value", "", "t() 子分类");
    public static final Field FCATELOG_NAME = f("筛选类型名称", "fcatelog_name", "", "t() 类型");
    public static final Field FCATELOG_VALUE = f("筛选类型替换词", "fcatelog_value", "", "t() 类型");
    public static final Field FAREA_NAME = f("筛选地区名称", "farea_name", "", "t() 地区");
    public static final Field FAREA_VALUE = f("筛选地区替换词", "farea_value", "", "t() 地区");
    public static final Field FYEAR_NAME = f("筛选年份名称", "fyear_name", "<动态:今年&…&20年前>", "t() 年份");
    public static final Field FYEAR_VALUE = f("筛选年份替换词", "fyear_value", "*", "t() 年份");
    public static final Field FLANG_NAME = f("筛选语言名称", "flang_name", "", "t() 语言");
    public static final Field FLANG_VALUE = f("筛选语言替换词", "flang_value", "", "t() 语言");
    public static final Field FSORT_NAME = f("筛选排序名称", "fsort_name", "时间&人气&评分", "t() 排序");
    public static final Field FSORT_VALUE = f("筛选排序替换词", "fsort_value", "time&hits&score", "t() 排序");
    public static final Field FILTER_DATA = f("筛选数据", "filterdata", "", "homeContent → filters（\"EXT\" 表示现场生成）");

    // ── 首页片单 ────────────────────────────────────────────────────
    public static final Field HOME_ENABLED = f("是否开启获取首页数据", "homeContent", "", "homeVideoContent 是否用分类聚合首页");
    public static final Field HOME_RECOMMEND_URL = f("首页推荐链接", "rcmed_url", "", "homeVideoContent 抓取地址");
    public static final Field HOME_ARR_RULE = f("首页列表数组规则", "home_arr_rule", "", "homeVideoContent 列表容器");
    public static final Field HOME_PLAY_ARR_RULE = f("首页片单列表数组规则", "hmepi_arr_rule", "", "homeVideoContent 每条卡片");
    public static final Field HOME_IS_JSOUP = f("首页片单是否Jsoup写法", "home_is_jsoup", "1", "homeVideoContent 选择器语法分支");
    public static final Field HOME_TITLE = f("首页片单标题", "home_title", "", "homeVideoContent 标题规则");
    public static final Field HOME_URL = f("首页片单链接", "home_url", "", "homeVideoContent 链接规则");
    public static final Field HOME_PIC = f("首页片单图片", "home_pic", "", "homeVideoContent 图片规则");
    public static final Field HOME_SUBTITLE = f("首页片单副标题", "home_subtitle", "", "homeVideoContent 副标题规则");
    public static final Field HOME_PREFIX = f("首页片单链接加前缀", "home_prefix", "", "homeVideoContent 拼前缀");
    public static final Field HOME_SUFFIX = f("首页片单链接加后缀", "home_suffix", "", "homeVideoContent 拼后缀");

    // ── 分类片单 ────────────────────────────────────────────────────
    public static final Field CAT_ARR_RULE = f("分类列表数组规则", "cat_arr_rule", "", "categoryContent 每条卡片");
    public static final Field CAT_IS_JSOUP = f("分类片单是否Jsoup写法", "cat_is_jsoup", "1", "categoryContent 选择器语法分支");
    public static final Field CAT_TITLE = f("分类片单标题", "cat_title", "", "categoryContent 标题规则");
    public static final Field CAT_URL = f("分类片单链接", "cat_url", "", "categoryContent 链接规则");
    public static final Field CAT_PIC = f("分类片单图片", "cat_pic", "", "categoryContent 图片规则");
    public static final Field CAT_SUBTITLE = f("分类片单副标题", "cat_subtitle", "", "categoryContent 副标题规则");
    public static final Field CAT_PREFIX = f("分类片单链接加前缀", "cat_prefix", "", "categoryContent 拼前缀");
    public static final Field CAT_SUFFIX = f("分类片单链接加后缀", "cat_suffix", "", "categoryContent 拼后缀");
    public static final Field CAT_JSON_TWICE = f("分类Json数据二次截取", "catjson_twice", "", "categoryContent JSON 二次截取（cat_mode=json）");
    public static final Field CAT_MODE = f("分类截取模式", "cat_mode", "", "categoryContent 取值方式 json/html");
    public static final Field CAT_FIRST_PAGE = f("分类起始页码", "firstpage", "0", "categoryContent 页偏移");

    // ── 搜索 ────────────────────────────────────────────────────────
    public static final Field SEARCH_URL = f("搜索链接", "search_url", "", "searchContent 抓取地址");
    public static final Field SEARCH_POST_BODY = f("POST请求数据", "sea_PtBody", "", "searchContent POST 表单");
    public static final Field SEARCH_FIRST_PAGE = f("搜索起始页码", "sea_firstpage", "0", "searchContent 页偏移");
    public static final Field SEARCH_ARR_RULE = f("搜索列表数组规则", "sea_arr_rule", "", "searchContent 每条卡片");
    public static final Field SEARCH_IS_JSOUP = f("搜索片单是否Jsoup写法", "sea_is_jsoup", "1", "searchContent 选择器语法分支");
    public static final Field SEARCH_TITLE = f("搜索片单标题", "sea_title", "", "searchContent 标题规则");
    public static final Field SEARCH_VOD_URL = f("搜索片单链接", "sea_url", "", "searchContent 链接规则");
    public static final Field SEARCH_PIC = f("搜索片单图片", "sea_pic", "", "searchContent 图片规则");
    public static final Field SEARCH_SUBTITLE = f("搜索片单副标题", "sea_subtitle", "", "searchContent 副标题规则");
    public static final Field SEARCH_PREFIX = f("搜索片单链接加前缀", "search_prefix", "", "searchContent 拼前缀");
    public static final Field SEARCH_SUFFIX = f("搜索片单链接加后缀", "search_suffix", "", "searchContent 拼后缀");
    public static final Field SEARCH_JSON_TWICE = f("搜索Json数据二次截取", "seajson_twice", "", "searchContent JSON 二次截取");
    public static final Field SEARCH_MODE = f("搜索截取模式", "search_mode", "", "searchContent 取值方式 json/html");

    // ── 详情 + 播放 ─────────────────────────────────────────────────
    public static final Field DETAIL_IS_JSOUP = f("详情是否Jsoup写法", "proj_is_jsoup", "1", "detailContent 选择器语法分支");
    public static final Field DETAIL_COVER = f("封面详情", "proj_cover", "", "detailContent → vod_pic");
    public static final Field DETAIL_CATE = f("类型详情", "proj_cate", "", "detailContent → type_name");
    public static final Field DETAIL_YEAR = f("年代详情", "proj_year", "", "detailContent → vod_year");
    public static final Field DETAIL_AREA = f("地区详情", "proj_area", "", "detailContent → vod_area");
    public static final Field DETAIL_ACTOR = f("演员详情", "proj_actor", "", "detailContent → vod_actor");
    public static final Field DETAIL_PLOT = f("简介详情", "proj_plot", "", "detailContent → vod_content");
    public static final Field PLAY_ARR_RULE = f("播放列表数组规则", "list_arr_rule", "", "detailContent 线路容器");
    public static final Field TAB_TITLE = f("线路标题", "tab_title", "", "detailContent → vod_play_from");
    public static final Field TAB_ARR_RULE = f("线路列表数组规则", "tab_arr_rule", "", "detailContent 每条线路");
    public static final Field EPI_ARR_RULE = f("选集列表数组规则", "epi_arr_rule", "", "detailContent 每集");
    public static final Field EPI_TITLE = f("选集标题", "epi_title", "", "detailContent 集标题");
    public static final Field EPI_URL = f("选集链接", "epi_url", "", "detailContent 集链接");
    public static final Field EPI_IS_JSOUP = f("选集标题链接是否Jsoup写法", "epi_is_jsoup", "1", "detailContent 集选择器语法分支");
    public static final Field EPI_PREFIX = f("选集链接加前缀", "epiurl_prefix", "", "detailContent 集链接拼前缀");
    public static final Field EPI_SUFFIX = f("选集链接加后缀", "epiurl_suffix", "", "detailContent 集链接拼后缀");
    public static final Field EPI_REVERSE = f("是否反转选集序列", "epi_reverse", "", "detailContent 选集顺序反转");

    /** 完整 DSL 字段表（只读）。{@code maintain/evidence/XYQHiker_DSL.md} 由它生成。 */
    public static final List<Field> FIELDS = Collections.unmodifiableList(F);

    // ══════════════════════════════════════════════════════════════
    // 实例
    // ══════════════════════════════════════════════════════════════

    private final String source;
    private final JSONObject root;
    private final String siteKey;
    private final JSONObject scope;

    private HikerRule(String source, JSONObject root, String siteKey, JSONObject scope) {
        this.source = source;
        this.root = root == null ? new JSONObject() : root;
        this.siteKey = siteKey == null ? "" : siteKey;
        this.scope = scope == null ? this.root : scope;
    }

    /**
     * 从 ext 串构造。永不抛异常（ext 不是 JSON 时退化为空规则）。
     *
     * <p>smali {@code XYQHiker.smali:21936}（原 {@code w}）：
     * 上游若 ext 以 {@code http} 开头会去网络取 JSON —— 该分支在本项目里
     * 被 {@link #of(String)} 显式拒绝（测试零网络），并在 DSL 文档中标注为未覆盖。
     */
    public static HikerRule of(String ext) {
        String src = ext == null ? "" : ext.trim();
        JSONObject obj = new JSONObject();
        if (!src.isEmpty() && !src.startsWith("http")) {
            try {
                obj = new JSONObject(src);
            } catch (Exception e) {
                obj = new JSONObject();
            }
        }
        return new HikerRule(ext, obj, "", null);
    }

    /** 从已解析的 JSON 构造（测试与内嵌规则用）。 */
    public static HikerRule of(JSONObject json) {
        return new HikerRule(json == null ? "{}" : json.toString(), json, "", null);
    }

    /** 空规则（所有取值走默认）。 */
    public static HikerRule empty() {
        return of("{}");
    }

    public String source() { return source; }

    public JSONObject root() { return root; }

    public String siteKey() { return siteKey; }

    /**
     * 【本项目扩展】切换到某个站点作用域。
     *
     * <p>规则来源优先级：
     * <ol>
     *   <li>ext 顶层 {@code "sites" → siteKey} 的对象</li>
     *   <li>ext 顶层直接以 siteKey 为键的对象</li>
     *   <li>ext 顶层本身（无作用域，与上游一致）</li>
     * </ol>
     * 查值时先查作用域对象、再回落顶层 —— 这样公共的 {@code "请求头参数"}、
     * {@code "网页编码格式"} 写一次即可，站点私有规则放在各自键下。
     */
    public HikerRule scoped(String siteKey) {
        if (siteKey == null || siteKey.isEmpty()) return new HikerRule(source, root, "", null);
        JSONObject sites = root.optJSONObject("sites");
        if (sites != null) {
            JSONObject sub = sites.optJSONObject(siteKey);
            if (sub != null) return new HikerRule(source, root, siteKey, sub);
        }
        JSONObject direct = root.optJSONObject(siteKey);
        if (direct != null) return new HikerRule(source, root, siteKey, direct);
        return new HikerRule(source, root, siteKey, null);
    }

    /** 该站点作用域是否命中（{@code scoped} 是否真的找到了子对象）。 */
    public boolean scopeResolved() {
        return scope != root;
    }

    // ══════════════════════════════════════════════════════════════
    // 取值
    // ══════════════════════════════════════════════════════════════

    /**
     * 上游 {@code aa(key)}：取字段，默认空串。
     */
    public String raw(String key) {
        if (key == null || key.isEmpty()) return "";
        String v = lookup(key);
        return isBlank(v) ? "" : v;
    }

    /**
     * 上游 {@code ab(key, def)}：取字段，命中「空标记」时回落 {@code def}。
     *
     * <p>smali {@code XYQHiker.smali:1514}。
     */
    public String value(String key, String def) {
        if (key == null || key.isEmpty()) return def == null ? "" : def;
        String v = lookup(key);
        if (isBlank(v)) return def == null ? "" : def;
        return v;
    }

    /** 按 {@link Field} 取：自动「中文优先 / 英文别名回落 / 默认值兜底」。 */
    public String value(Field field) {
        if (field == null) return "";
        String key = field.key(scope);
        if (field.cn != null && !key.equals(field.cn)) {
            // 英文别名命中：中文名若在别处（如顶层）配了，仍以中文为准
            String cnVal = value(field.cn, null);
            if (cnVal != null && !cnVal.isEmpty()) return cnVal;
        }
        return value(key, resolveDefault(field));
    }

    /** 动态默认值（如年份列表）在这里展开。 */
    private static String resolveDefault(Field field) {
        String def = field.def;
        if (def != null && def.startsWith("<动态:")) return HikerSelector.yearList();
        return def;
    }

    /** 查字段用的键名（中文或英文），供文档/排错。 */
    public String keyOf(Field field) {
        return field == null ? "" : field.key(scope);
    }

    /** 布尔开关：DSL 约定 {@code "1"} 或 {@code "是"} 为真。 */
    public boolean flag(Field field) {
        String v = value(field);
        return "1".equals(v) || "是".equals(v);
    }

    /** 数字字段（如起始页码），解析失败用默认值。 */
    public int number(Field field, int def) {
        String v = value(field);
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 覆盖一个字段（测试用；也用于「内置默认 + ext 可覆盖」的两层配置）。
     *
     * <p>覆盖写进<b>当前作用域</b>：扁平规则写顶层；{@link #scoped} 过的规则写它自己的
     * 子对象，否则「站点私有字段」会被顶层同名键盖掉，站点隔离就失效了。
     */
    public HikerRule with(String key, String val) {
        JSONObject newRoot = deepCopy(root);
        JSONObject target = newRoot;
        if (scope != root && !siteKey.isEmpty()) {
            JSONObject sites = newRoot.optJSONObject("sites");
            JSONObject sub = sites == null ? null : sites.optJSONObject(siteKey);
            if (sub == null) sub = newRoot.optJSONObject(siteKey);
            if (sub != null) target = sub;
        }
        try {
            target.put(key, val);
        } catch (Exception e) {
            // 键非法时忽略，保持其余字段可用
        }
        HikerRule r = new HikerRule(source, newRoot, siteKey, null);
        return siteKey.isEmpty() ? r : r.scoped(siteKey);
    }

    private static JSONObject deepCopy(JSONObject o) {
        try {
            return new JSONObject(o == null ? "{}" : o.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 是否存在该字段（无论中英文名）。 */
    public boolean has(Field field) {
        if (field == null) return false;
        return has(scope, field.cn) || has(scope, field.en)
                || has(root, field.cn) || has(root, field.en);
    }

    /** 该 JSON 直接含某键（值非 null 且非 JSONObject.NULL）。 */
    private static boolean has(JSONObject o, String k) {
        if (o == null || k == null) return false;
        return o.has(k) && !o.isNull(k);
    }

    private static boolean isBlank(String v) {
        if (v == null) return true;
        for (String m : EMPTY_MARKERS) if (m.equals(v)) return true;
        return false;
    }

    /** 作用域优先、顶层兜底。 */
    private String lookup(String key) {
        if (scope != root && scope != null) {
            String v = str(scope, key);
            if (!isBlank(v)) return v;
        }
        return str(root, key);
    }

    private static String str(JSONObject o, String key) {
        if (o == null || key == null) return "";
        try {
            if (!o.has(key) || o.isNull(key)) return "";
            Object v = o.opt(key);
            if (v == null) return "";
            return String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 便利视图
    // ══════════════════════════════════════════════════════════════

    /** 用作请求头里的标识；没有专门字段时退回站点键。 */
    public String tag() {
        return siteKey.isEmpty() ? "XYQHiker" : siteKey;
    }

    /** 把字段表导成 {@code 键 → 值} 的只读快照，供文档/调试打印。 */
    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (Field field : FIELDS) {
            m.put(field.cn + (field.en == null ? "" : "/" + field.en), value(field));
        }
        return m;
    }

    /** 字段名列表（测试用）。 */
    public static List<String> fieldNames() {
        List<String> out = new ArrayList<String>();
        for (Field field : FIELDS) out.add(field.cn);
        return out;
    }

    /** 各消费点的字段数统计（文档用）。 */
    public static Map<String, Integer> consumerHistogram() {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        for (Field field : FIELDS) {
            String c = field.consumer == null ? "?" : field.consumer.split("[ ;（(]")[0];
            Integer n = m.get(c);
            m.put(c, n == null ? 1 : n + 1);
        }
        return m;
    }

    @Override public String toString() {
        return "HikerRule{site=" + (siteKey.isEmpty() ? "-" : siteKey)
                + ", fields=" + FIELDS.size() + ", scoped=" + scopeResolved() + "}";
    }

    /** 便于测试：把若干 {@code k=v} 直接变成规则。 */
    public static HikerRule ofPairs(String... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try {
                o.put(kv[i], kv[i + 1]);
            } catch (Exception e) {
                // ignore
            }
        }
        return of(o);
    }

    /** 全部字段名 × 消费点，按消费点排序（文档生成用）。 */
    public static List<Field> fieldsByConsumer() {
        List<Field> out = new ArrayList<Field>(FIELDS);
        Collections.sort(out, new java.util.Comparator<Field>() {
            @Override public int compare(Field a, Field b) {
                String ca = a.consumer == null ? "" : a.consumer;
                String cb = b.consumer == null ? "" : b.consumer;
                return ca.compareTo(cb);
            }
        });
        return out;
    }

    static {
        // 自检：字段表不允许中文名重复（重复会导致文档与代码不一致）
        List<String> seen = new ArrayList<String>();
        for (Field field : F) {
            if (seen.contains(field.cn)) {
                throw new IllegalStateException("重复的中文字段名: " + field.cn);
            }
            seen.add(field.cn);
        }
        Arrays.sort(new String[0]);
    }
}
