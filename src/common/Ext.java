package com.example.tvbox.common;

import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * ext 字段解析器。
 *
 * <p>ext 是 api.json 里每个站点的配置大杂烩，实测字段集合（来自真实订阅）：
 * <pre>
 *   url / host / site     站点地址；site 可能是换行或逗号分隔的域名列表
 *   dataKey / dataIv      AES 的 key / iv（原插件直接下发，明文）
 *   ua / deviceId / version / token   请求指纹与登录态
 *   appName / name / pkg / package / versionName / buildNumber / buildSignature   仿 App 身份
 *   publicKey / commonKey / sk        RSA 公钥或站点级密钥
 *   init / decrypt / LoginPath        初始化变体、解密开关、登录端点
 * </pre>
 *
 * <p>容错原则：raw 为 null / 空串 / 非法 JSON 时，所有访问器返回安全默认值（空串 / 空列表 / 空对象），
 * <b>永不抛异常</b>。若 raw 不是 JSON 而是纯文本地址，则整体视作 url（真实订阅中大量存在）。
 *
 * <p>用法：
 * <pre>{@code
 * Ext ext = new Ext(siteExtend);
 * String base = ext.url();
 * for (String host : ext.sites()) { ... }   // 逐个探测可用域名
 * String k = ext.withKey("dataKey", "内置密钥").key();
 * }</pre>
 */
public class Ext {

    private static final String TAG = "Ext";

    /** 空字符串常量，减少重复字面量 */
    private static final String EMPTY = "";

    /** 视为“像 URL”的前缀（用于非 JSON 纯文本 ext 的识别） */
    private static final String[] URL_PREFIXES = {"http://", "https://", "rtmp://", "rtsp://"};

    /** site 字段的分隔符集合：换行 / 逗号 / 分号 / 竖线 / 空白 */
    private static final String SITE_SPLIT_REGEX = "[\\s,;|]+";

    /** 有效的 AES key/iv 字节长度（16 / 24 / 32） */
    private static final int[] AES_KEY_LENGTHS = {16, 24, 32};

    private final String raw;
    private final JSONObject obj;
    /** 覆盖层：withKey 写入的内联配置，优先于 JSON 原文 */
    private final JSONObject override = new JSONObject();

    /**
     * 解析 ext。
     *
     * @param raw ext 原始字符串，可为 null
     */
    public Ext(String raw) {
        this.raw = raw == null ? EMPTY : raw.trim();
        JSONObject parsed = null;
        String t = this.raw;
        if (!t.isEmpty()) {
            if (t.startsWith("{")) {
                try {
                    parsed = new JSONObject(t);
                } catch (Throwable e) {
                    Log.w(TAG, "ext is not valid json, treat as plain: " + head(t));
                    parsed = null;
                }
            }
        }
        this.obj = parsed == null ? new JSONObject() : parsed;
    }

    /**
     * 便捷工厂。
     *
     * @param raw ext 原始字符串
     * @return Ext 实例（永不 null）
     */
    public static Ext of(String raw) {
        return new Ext(raw);
    }

    /** 空配置，供缺省场景复用。 */
    public static Ext empty() {
        return new Ext(EMPTY);
    }

    // ── 基础信息 ────────────────────────────────────────────

    /**
     * 原始 ext 字符串。
     *
     * @return 原文（已 trim），未提供时为空串
     */
    public String raw() {
        return raw;
    }

    /**
     * 是否没有任何可用配置。
     *
     * @return true 表示既无 JSON 字段也无纯文本地址
     */
    public boolean isEmpty() {
        return raw.isEmpty();
    }

    /**
     * 底层 JSON 对象（只读语义，调用方不要修改内部状态）。
     *
     * @return JSONObject，空配置时为空对象
     */
    public JSONObject json() {
        return obj;
    }

    /**
     * 原始是否为非 JSON 的纯文本（常见于 ext 直接写死一个域名）。
     *
     * @return true 表示按纯文本处理
     */
    public boolean isPlainText() {
        return JsonKeys.isEmpty(obj) && !raw.isEmpty();
    }

    /**
     * 是否存在某个字段（含覆盖层）。
     *
     * @param name 字段名
     * @return true 存在且有非空值
     */
    public boolean has(String name) {
        return !optStr(name).isEmpty();
    }

    // ── 站点地址 ────────────────────────────────────────────

    /**
     * 主站点地址。
     *
     * <p>取值优先级：{@code url} → {@code host} → {@code site} 列表首项 → 纯文本 ext。
     * 结果保证无尾部斜杠，便于 {@link Http#buildUrl} 拼接。
     *
     * @return 站点基址；无法确定时为空串
     */
    public String url() {
        // ★ 只依赖 sites()，**不再**自己用 optStr 读一遍 url/host。
        //
        // 这里曾经是第二个实现：先 optStr("url")、再 optStr("host")、最后才回落 sites()。
        // 与 sites() 里各写一份优先级，必然漏掉一个 —— 实测就漏了"数组"：
        // 配置写 {"url":["http://u1","http://u2"]} 时，optStr 返回的是数组的 **JSON 文本**
        // （`["http:\/\/u1.com","http:\/\/u2.com"]`），于是 url() 返回这一整串垃圾，
        // 而 sites() 明明已经正确解析出了两个地址。
        //
        // 现在优先级只在 SITE_KEYS 一处定义，url() 就是"候选表第一项"。
        List<String> s = sites();
        return s.isEmpty() ? EMPTY : s.get(0);
    }

    /**
     * 全部候选站点地址（去重，保持声明顺序）。
     *
     * <p>来源：{@code url}、{@code host}、{@code site}（支持换行 / 逗号 / 分号 / 竖线 / 空白分隔），
     * 以及纯文本 ext 整体。用于换域名后的逐个可用性探测。
     *
     * @return 非 null 列表；无配置时为空列表
     */
    /**
     * 站点地址候选表的键名，**按优先级排列**。
     *
     * <p>顺序即优先级：{@code url} → {@code host} → {@code site} → {@code hosts}，
     * 每个键内部先标量后数组、保持配置原序。{@link #url()} 取本表第一项，
     * 因此"改动这里的顺序"就等于"改动选站优先级"。
     */
    private static final String[] SITE_KEYS = {"url", "host", "site", "hosts"};

    /**
     * **单值**站点键：值就是"一个地址"，**不做分隔符切分**。
     *
     * <p>为什么必须区分：真实 ext 里存在**含逗号的单个地址**。实测（`IkanbotTest`）：
     * <pre>{@code
     *   {"url":"https://j.example[排序:HD,m3u8]"}
     * }</pre>
     * 逗号在 {@code [排序:…]} 段里，是"线路排序规则"的一部分，不是多地址分隔符。
     * 如果对 {@code url} 也按逗号切分，这个地址会被切成
     * {@code https://j.example[排序:HD} 与 {@code m3u8]} 两段 —— 直接坏掉。
     *
     * <p>所以只有 {@link #MULTI_VALUE_KEYS} 里的键才切分。
     */
    private static final String[] SINGLE_VALUE_KEYS = {"url", "host"};

    /**
     * **可多值**站点键：标量形态下按 {@link #SITE_SPLIT_REGEX} 切分（这是 `site` 字段的既有语义）。
     */
    private static final String[] MULTI_VALUE_KEYS = {"site", "hosts"};

    /**
     * 从 ext 中收集**所有**可能的站点地址，按优先级、去重、保持配置原序。
     *
     * <h2>★ 每个键都必须同时接受"标量"和"数组"两种形态</h2>
     *
     * <p>真站点配置里**数组是常态**。实测（2026-10-17，用户机器上的真实配置）：
     * 81 个源的 ext 共 129 个字段值，**其中 8 个是数组，形态完全一致**：
     * <pre>{@code
     * "site": ["http://xsayang.fun", "http://38.76.197.172:12521"]            // 快映
     * "site": ["http://xhban.xyz", "http://38.76.197.172:16969"]              // 虎斑
     * "site": ["https://www.hjzhencai.top", "https://www.huanjuanwp.shop"]    // 花卷
     * "site": ["https://www.wogg.live", "https://woggpan.888484.xyz", ...]    // 玩偶
     * }</pre>
     *
     * <p>本工程早期只认标量，于是这些源在真机上**取不到任何地址**，
     * 回落到各自硬编码的兜底域名（多半已失效）→ **用户看到空结果，而本地测试全绿**
     * （测试用 `setSiteForTest` 直接注入域名，不经过 ext 解析）。
     *
     * <h2>另外两个必须遵守的约束（都是踩出来的）</h2>
     *
     * <ol>
     *   <li><b>不能用 {@code optStr} 判类型</b>：{@code optString} 对数组返回其 **JSON 文本**，
     *       所以 {@code {"site":[]}} 会得到字符串 {@code "[]"}；
     *       拿它去 split 就会把 {@code "[]"} 当成一个域名。
     *       因此下面按 {@code Object} 的**运行时类型**分支。</li>
     *   <li><b>必须尊重 {@code override} 层</b>：{@code withKey(name, value)} 写入的内联覆盖
     *       优先于 JSON 原文。直接读 {@code obj.opt(name)} 会绕过它 ——
     *       实测就是这样把 {@code ExtSiteArrayTest}/{@code CommonTest} 的
     *       {@code withKey} 用例打红的。</li>
     * </ol>
     *
     * @return 候选地址列表；无可用字段时返回空表（**永不返回 null**）
     */
    public List<String> sites() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String key : SINGLE_VALUE_KEYS) {
            collectAddresses(set, key, false);
        }
        for (String key : MULTI_VALUE_KEYS) {
            collectAddresses(set, key, true);
        }
        if (isPlainText() && !raw.contains("{")) {
            for (String part : raw.split(SITE_SPLIT_REGEX)) {
                addUrl(set, part);
            }
        }
        return new ArrayList<>(set);
    }

    /**
     * 把某个键下的地址（标量或数组）加进候选集。
     *
     * @param set       目标集合（自动去重）
     * @param key       字段名
     * @param splittable 标量形态是否按分隔符切分（{@code site}/{@code hosts} 为 true）
     */
    private void collectAddresses(LinkedHashSet<String> set, String key, boolean splittable) {
        // 1) 覆盖层优先（withKey / withKeys 写入的内联配置）
        if (override.has(key)) {
            addScalarOrArray(set, override.opt(key), splittable);
            return;
        }
        // 2) JSON 原文
        if (obj == null || !obj.has(key)) return;
        addScalarOrArray(set, obj.opt(key), splittable);
    }

    /**
     * 按值的**运行时类型**加进候选集（字符串 / 数组 / 其它）。
     *
     * @param set        目标集合
     * @param v          原始值
     * @param splittable 字符串是否按分隔符切分
     */
    private static void addScalarOrArray(LinkedHashSet<String> set, Object v, boolean splittable) {
        if (v == null || v == JSONObject.NULL) return;
        if (v instanceof String) {
            String text = (String) v;
            if (text.isEmpty()) return;
            if (!splittable) {
                addUrl(set, text);
                return;
            }
            for (String part : text.split(SITE_SPLIT_REGEX)) {
                addUrl(set, part);
            }
            return;
        }
        if (v instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) v;
            for (int i = 0; i < arr.length(); i++) {
                addUrl(set, arr.optString(i, EMPTY));
            }
            return;
        }
        // 其它类型（数字/布尔/嵌套对象）不该出现在站点地址字段里。
        // 明确忽略，而不是 String.valueOf 之后硬塞一个假地址进候选集。
    }

    public List<String> hosts() {
        return sites();
    }

    // ── 密钥 ────────────────────────────────────────────────

    /**
     * AES 密钥（原样字符串，不做长度裁剪）。
     *
     * <p>取值优先级：{@code dataKey} → {@code key} → {@code commonKey}。
     *
     * @return 密钥字符串；无配置时为空串
     */
    public String key() {
        String v = optStr("dataKey");
        if (v.isEmpty()) {
            v = optStr("key");
        }
        if (v.isEmpty()) {
            v = optStr("commonKey");
        }
        return v;
    }

    /**
     * AES IV（原样字符串）。
     *
     * <p>取值优先级：{@code dataIv} → {@code iv} → {@code dataKey}（部分站点 iv 与 key 相同）。
     *
     * @return IV 字符串；无配置时为空串
     */
    public String iv() {
        String v = optStr("dataIv");
        if (v.isEmpty()) {
            v = optStr("iv");
        }
        if (v.isEmpty()) {
            v = key();
        }
        return v;
    }

    /**
     * 密钥的原始字节。
     *
     * <p>真实 ext 里 dataKey 有两种形态：
     * <ol>
     *   <li>明文 16 字符，如 {@code 1234567887654321} —— 直接 UTF-8</li>
     *   <li>Base64，如 {@code D2KREWRIBVNAUJJYEDL4VFHOY2Q0PQ==} —— 需先解码</li>
     * </ol>
     * 本方法先按 UTF-8 取字节；若长度不是合法 AES 长度、且 Base64 解码后恰好是 16/24/32 字节，
     * 则采用解码结果。
     *
     * @return 字节数组；无密钥时长度为 0
     */
    public byte[] keyBytes() {
        return toKeyBytes(key());
    }

    /**
     * IV 的原始字节，规则同 {@link #keyBytes()}。
     *
     * @return 字节数组；无 IV 时长度为 0
     */
    public byte[] ivBytes() {
        return toKeyBytes(iv());
    }

    /**
     * RSA 公钥（Base64 DER，来自 {@code publicKey}）。
     *
     * @return 公钥字符串；无配置时为空串
     */
    public String publicKey() {
        return optStr("publicKey");
    }

    // ── 请求指纹 / 登录态 ────────────────────────────────────

    /**
     * User-Agent。真实值如 {@code okhttp/3.10.0}。
     *
     * @return UA；无配置时为空串（调用方回退到 {@link Http#DEFAULT_UA}）
     */
    public String ua() {
        return optStr("ua");
    }

    /**
     * 设备 ID。
     *
     * @return 设备 ID；无配置时为空串
     */
    public String deviceId() {
        return optStr("deviceId");
    }

    /**
     * 版本号。兼容 {@code version} / {@code versionName} / {@code ver}。
     *
     * @return 版本号；无配置时为空串
     */
    public String version() {
        String v = optStr("version");
        if (v.isEmpty()) {
            v = optStr("versionName");
        }
        if (v.isEmpty()) {
            v = optStr("ver");
        }
        return v;
    }

    /**
     * 登录令牌。
     *
     * @return token；无配置时为空串
     */
    public String token() {
        return optStr("token");
    }

    /**
     * 仿 App 的包名。兼容 {@code package} / {@code pkg} / {@code aid}。
     *
     * @return 包名；无配置时为空串
     */
    public String pkg() {
        String v = optStr("package");
        if (v.isEmpty()) {
            v = optStr("pkg");
        }
        if (v.isEmpty()) {
            v = optStr("aid");
        }
        return v;
    }

    /**
     * 构建号（数字串）。
     *
     * @return 构建号；无配置时为空串
     */
    public String buildNumber() {
        return optStr("buildNumber");
    }

    /**
     * 构建签名（APK 签名摘要，App99 系列用于服务端校验）。
     *
     * @return 签名；无配置时为空串
     */
    public String buildSignature() {
        return optStr("buildSignature");
    }

    /**
     * 初始化变体名，如 {@code initV122}（决定请求路径/参数集）。
     *
     * @return 变体名；无配置时为空串
     */
    public String init() {
        return optStr("init");
    }

    /**
     * 登录端点（部分站点 ext 内写死，如 {@code LoginPath}）。
     *
     * @return 端点；无配置时为空串
     */
    public String loginPath() {
        return optStr("LoginPath");
    }

    /**
     * 站点级 HMAC 密钥（如 {@code sk}）。
     *
     * @return 密钥；无配置时为空串
     */
    public String sk() {
        return optStr("sk");
    }

    /**
     * 应用名（用于展示/日志，不参与请求）。
     *
     * @return 名称；无配置时为空串
     */
    public String appName() {
        String v = optStr("appName");
        if (v.isEmpty()) {
            v = optStr("name");
        }
        return v;
    }

    // ── 通用访问 ────────────────────────────────────────────

    /**
     * 取字符串字段（覆盖层优先）。
     *
     * @param name 字段名
     * @return 值；缺失或为 null 时返回空串
     */
    public String optStr(String name) {
        if (name == null || name.isEmpty()) {
            return EMPTY;
        }
        if (override.has(name)) {
            return nz(override.optString(name, EMPTY));
        }
        if (!obj.has(name)) {
            return EMPTY;
        }
        Object v = obj.opt(name);
        if (v == null) {
            return EMPTY;
        }
        return nz(obj.optString(name, EMPTY));
    }

    /**
     * 取字符串字段并给默认值。
     *
     * @param name 字段名
     * @param def  缺省值
     * @return 值或缺省值
     */
    public String optStr(String name, String def) {
        String v = optStr(name);
        return v.isEmpty() ? nz(def) : v;
    }

    /**
     * 取 JSON 子对象。
     *
     * @param name 字段名
     * @return 子对象；缺失或类型不符时返回空 JSONObject（永不 null）
     */
    public JSONObject optJson(String name) {
        if (name == null || name.isEmpty()) {
            return new JSONObject();
        }
        JSONObject v = null;
        if (override.has(name)) {
            v = override.optJSONObject(name);
        }
        if (v == null && obj.has(name)) {
            v = obj.optJSONObject(name);
        }
        return v == null ? new JSONObject() : v;
    }

    /**
     * 取子数组的字符串化列表（元素为对象时跳过，避免误用）。
     *
     * @param name 字段名
     * @return 非 null 列表
     */
    public List<String> optArray(String name) {
        List<String> out = new ArrayList<>();
        if (name == null || name.isEmpty() || !obj.has(name)) {
            return out;
        }
        org.json.JSONArray arr = obj.optJSONArray(name);
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i);
            if (s != null && !s.isEmpty() && !"null".equals(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 取 int 字段。
     *
     * @param name 字段名
     * @param def  缺省值
     * @return 值或缺省值
     */
    public int optInt(String name, int def) {
        if (name == null || name.isEmpty()) {
            return def;
        }
        String v = optStr(name);
        if (v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable e) {
            return def;
        }
    }

    /**
     * 取 boolean 字段。识别 true/1/yes/on（大小写不敏感）。
     *
     * @param name 字段名
     * @param def  缺省值
     * @return 值或缺省值
     */
    public boolean optBool(String name, boolean def) {
        String v = optStr(name).trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return def;
        }
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v);
    }

    // ── 内联覆盖 ────────────────────────────────────────────

    /**
     * 设置内联覆盖值，优先于 ext 原文 —— 用于把密钥固化进子类源码。
     *
     * <pre>{@code
     * super(ext);
     * this.ext.withKey("dataKey", "1234567887654321").withKey("dataIv", "1234567887654321");
     * }</pre>
     *
     * @param name  字段名，空/null 时忽略
     * @param value 值，null 视为空串（即显式置空）
     * @return this，便于链式
     */
    public Ext withKey(String name, String value) {
        if (name == null || name.isEmpty()) {
            return this;
        }
        try {
            override.put(name, value == null ? EMPTY : value);
        } catch (Throwable e) {
            Log.e(TAG, "withKey failed: " + name, e);
        }
        return this;
    }

    /**
     * 批量内联覆盖，参数为偶数个 key/value。
     *
     * @param kv key1, value1, key2, value2 ...
     * @return this
     */
    public Ext withKeys(String... kv) {
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                withKey(kv[i], kv[i + 1]);
            }
        }
        return this;
    }

    /**
     * 合并后的完整配置（原文 + 覆盖），便于排障打印。
     *
     * @return JSONObject 快照
     */
    public JSONObject merged() {
        JSONObject out = new JSONObject();
        try {
            for (String k : JsonKeys.of(obj)) {
                out.put(k, obj.optString(k, EMPTY));
            }
            for (String k : JsonKeys.of(override)) {
                out.put(k, override.optString(k, EMPTY));
            }
        } catch (Throwable e) {
            Log.e(TAG, "merged failed", e);
        }
        return out;
    }

    /**
     * 调试输出（隐藏密钥明文，仅保留长度）。
     *
     * @return 摘要
     */
    @Override
    public String toString() {
        return "Ext{url=" + url() + ", keyLen=" + key().length()
                + ", ivLen=" + iv().length() + ", ua=" + ua() + "}";
    }

    // ── 内部工具 ────────────────────────────────────────────

    /**
     * 把候选地址加入集合（去空白、去尾部斜杠、跳过空值）。
     *
     * @param set  目标集合
     * @param cand 候选值
     */
    private static void addUrl(LinkedHashSet<String> set, String cand) {
        String v = trimSlash(nz(cand).trim());
        if (!v.isEmpty() && !"null".equals(v)) {
            set.add(v);
        }
    }

    /**
     * 去掉尾部斜杠，保留协议头（{@code https://a.com/} → {@code https://a.com}）。
     *
     * @param s 原文
     * @return 处理结果
     */
    static String trimSlash(String s) {
        String v = nz(s);
        while (v.endsWith("/") && v.length() > 1) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    /**
     * 字符串密钥转字节：优先 UTF-8，若长度非法再尝试 Base64。
     *
     * @param s 密钥字符串
     * @return 字节数组；空密钥返回长度 0 数组
     */
    public static byte[] toKeyBytes(String s) {
        if (s == null || s.isEmpty()) {
            return new byte[0];
        }
        byte[] utf8;
        try {
            utf8 = s.getBytes("UTF-8");
        } catch (Throwable e) {
            utf8 = s.getBytes();
        }
        if (isAesLength(utf8.length)) {
            return utf8;
        }
        try {
            byte[] dec = Base64.getDecoder().decode(s);
            if (isAesLength(dec.length)) {
                return dec;
            }
        } catch (Throwable ignored) {
            // 非 Base64，保持 UTF-8
        }
        return utf8;
    }

    /**
     * 是否为合法 AES 密钥长度。
     *
     * @param len 字节数
     * @return true 表示 16/24/32
     */
    public static boolean isAesLength(int len) {
        for (int n : AES_KEY_LENGTHS) {
            if (len == n) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否以已知协议前缀开头。
     *
     * @param s 原文
     * @return true 表示像 URL
     */
    public static boolean looksLikeUrl(String s) {
        String v = nz(s).trim().toLowerCase(Locale.ROOT);
        for (String p : URL_PREFIXES) {
            if (v.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** null 安全 */
    private static String nz(String s) {
        return s == null ? EMPTY : s;
    }

    /** 日志截断 */
    private static String head(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }
}
