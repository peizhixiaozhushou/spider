package com.example.tvbox.crawler;

/**
 * TVBox Spider 接口契约 —— 结构来自对 you_tv_a4.apk 的静态观测
 *
 * 证据来源:
 *   inspect_android_class(you_tv_a4.apk, "com.github.catvod.crawler.Spider")
 *   evidence_id: ev_735cd6efff8c95622f862dc5c2de3a63600fad20d4fe356c7eb9fba98ab7cdd7
 *
 * 观测到 22 个方法（含 2 个 init 重载、2 个 searchContent 重载）。
 * 注意: 这是 host 端定义。插件只需 override 需要的方法，其余继承默认实现。
 * 各方法默认返回值（如空 JSON）属于运行时行为，静态分析未观测 —— 需自行实现。
 */
public abstract class Spider {

    /*
     * ═══════════════════════════════════════════════════════════════════
     * 字段契约 —— 必须与宿主逐位对齐，否则 drop-in jar 会在运行时炸
     * ═══════════════════════════════════════════════════════════════════
     * 下面三个字段的修饰符与静态性是**从宿主 APK 直接观测到的**，不是推测：
     *
     *   Dump(recon/you_tv_a4.apk, "com.github.catvod.crawler.Spider")
     *     public static android.content.Context mContext;
     *     public static org.json.JSONObject empty;
     *     public java.lang.String siteKey;          ← 注意：这个是实例字段
     *   static { Spider.empty = new JSONObject(); }  ← empty 在静态块里被初始化
     *
     * 为什么这里要写这么啰嗦：
     *   早期版本的镜像把 empty / mContext 写成了 **protected 实例字段**。
     *   本地编译一切正常（继承树都是自洽的），但一旦按 drop-in 方式打包
     *   （jar 里不含 Spider.class，运行时用宿主的），就会出现：
     *     - getfield 打到宿主的 static 字段 → IncompatibleClassChangeError
     *     - 用 protected 视角访问 public 字段虽然合法，但 siteKey 若被窄化则可能
     *       NoSuchFieldError（JVMS 字段解析要求名称+描述符一致）
     *   这类问题**编译器抓不到、单元测试也抓不到**，只有对齐契约才安全。
     *   `ae/tests/SpiderContractTest.java` 用反射把这些修饰符钉死了。
     */

    /** 空对象缓存。宿主里是 **static**，且在静态初始化块中被赋值。 */
    public static org.json.JSONObject empty;
    /** 全局 Context。宿主里是 **static**。 */
    public static android.content.Context mContext;
    /** 站点标识。宿主里是**实例**字段（三个里唯一非静态的）。 */
    public java.lang.String siteKey;

    // ── 生命周期 ──────────────────────────────────────────────
    /** 无 ext 初始化 */
    public void init(android.content.Context context) { this.mContext = context; }

    /**
     * 带 ext 初始化 —— ★ ext 字段的唯一入口
     * @param extend api.json 中该站点的 ext 字段原始字符串
     */
    public void init(android.content.Context context, java.lang.String extend) {
        this.mContext = context;
    }

    // 注意：这里**不要**声明 initApi(SpiderApi)。
    // `com.github.catvod.crawler.SpiderApi` 只存在于某些 Android 宿主里，
    // fmplayer.app 的 gateway.jar 里没有这个类；镜像里留着它会让"用宿主类库编译"这一步
    // 直接报 cannot find symbol（那正是我们想要的信号）。
    public void destroy() {}
    public void cancelByTag() {}

    // ── 5 个核心数据方法（必须实现）──────────────────────────
    /** 首页分类 + 推荐 */
    public java.lang.String homeContent(boolean filter) { return "{}"; }
    /** 首页视频（旧接口，可空） */
    public java.lang.String homeVideoContent() { return "{}"; }
    /** 分类翻页 */
    public java.lang.String categoryContent(java.lang.String tid, java.lang.String pg,
                                            boolean filter,
                                            java.util.HashMap<java.lang.String, java.lang.String> extend) {
        return "{}";
    }
    /** 详情，ids 为 vod_id 列表 */
    public java.lang.String detailContent(java.util.List<java.lang.String> ids) { return "{}"; }
    /** 搜索 —— 两参数重载 */
    public java.lang.String searchContent(java.lang.String key, boolean quick) { return "{}"; }
    /** 搜索 —— 三参数重载（多一个 quickSearch 标识） */
    public java.lang.String searchContent(java.lang.String key, boolean quick, java.lang.String quickSearch) {
        return searchContent(key, quick);
    }
    /** 播放解析 */
    public java.lang.String playerContent(java.lang.String flag, java.lang.String id,
                                          java.util.List<java.lang.String> vipFlags) {
        return "{}";
    }

    // ── 可选能力 ─────────────────────────────────────────────
    /** 自定义动作 */
    public java.lang.String action(java.lang.String action) { return "{}"; }
    /** 直播 */
    public java.lang.String liveContent(java.lang.String url) { return "{}"; }
    /** 判断 URL 是否为视频格式（嗅探用） */
    public boolean isVideoFormat(java.lang.String url) { return false; }
    /** 是否手动检测视频 */
    public boolean manualVideoCheck() { return false; }
    /** 本地代理（跨域/防盗链） */
    public java.lang.Object[] proxy(java.util.Map<java.lang.String, java.lang.String> params) { return null; }
    public java.lang.Object[] proxyLocal(java.util.Map<java.lang.String, java.lang.String> params) { return null; }

    // ── 网络基础设施 ─────────────────────────────────────────
    /** 自定义 HTTP 客户端。宿主里是 **static**。 */
    public static okhttp3.OkHttpClient client() { return null; }
    /** DoH 解析。宿主里是 **static**。 */
    public static okhttp3.Dns safeDns() { return null; }

    // ── 宿主有、我们暂未使用但必须存在的成员 ─────────────────
    /*
     * 下面三个方法在宿主里存在，我们的重写目前不 override 它们。
     * 之所以仍然声明，是为了让本镜像在**编译期**就能代表完整的宿主契约 ——
     * 任何队友想 override 时不用先去翻 APK。签名按观测原样抄。
     */
    /** 观测: {@code public int getScreenOrientation()} */
    public int getScreenOrientation() { return 0; }
    /** 观测: {@code public void log(String)} */
    public void log(java.lang.String msg) {}
    /** 观测: {@code public void log(String, String)} */
    public void log(java.lang.String tag, java.lang.String msg) {}
    /**
     * 观测到宿主有**两参数**版本 {@code liveContent(String, boolean)}。
     * 我们此前只声明了一参数版本（更早的观测遗漏了第二个形参）。
     * 两个都保留：两参数版与宿主一致，一参数版供老代码调用。
     */
    public java.lang.String liveContent(java.lang.String url, boolean flag) { return "{}"; }
}
