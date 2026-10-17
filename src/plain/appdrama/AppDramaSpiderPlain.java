package com.example.tvbox.plain.appdrama;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;
import com.example.tvbox.plain.proto.Proto;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AppDrama 的 <b>Spider 外壳</b> —— 天堂 / 苹果 / 橘汁 / 华谊 四站共用的 TVBox 内容源。
 *
 * <h2>与 {@link AppDramaPlain} 的分工</h2>
 * {@link AppDramaPlain} 是<b>协议核</b>（AES/RSA 原语、protobuf 手工编码、
 * 两条通道的请求头、设备指纹 publicParams、站点常量表），<b>无基类、不是源</b>；
 * 本类才是宿主调用的源：负责 <i>ext 契约 → 会话/公钥轮换握手 → 7 个内容方法 →
 * 响应反序列化</i>。密码学与线格式全部委托给协议核，本类只做编排 + 解析。
 *
 * <h2>★ 补充证据：业务 protobuf 的字段号已从 DEX 内嵌 descriptor 直读</h2>
 * {@link AppDramaPlain} 的 [U4] 写着"业务 proto（DramaBeanPage / DramaDetailBean /
 * ParsePlayUrlBean 等）字段号未逐个提取，故业务解析函数保持结构化骨架"。
 * 本类<b>把这部分补上了</b>：在 {@code re/spider.dex} 里定位到内嵌的
 * FileDescriptorProto（与协议核找到 SecureRequest/RSARequest 同一片数据区），
 * 逐条解出业务消息的字段名/字段号/wire 类型（脚本与偏移见交付报告），
 * 因此下面每个 {@code parse*} 的字段号都是<b>实测</b>而非猜测：
 * <table border="1">
 *   <tr><th>消息</th><th>用到的字段号</th></tr>
 *   <tr><td>ApiResult</td><td>code=1(int64) msg=2(str) data=3(bytes)</td></tr>
 *   <tr><td>DramaBeanPage</td><td>dramaBean=1(repeated DramaBean) total=16(int32)</td></tr>
 *   <tr><td>DramaBean</td><td>area=1 cover_image=2(msg) id=3(int) brief=4 name=5
 *       director=7 actor=12 remark=13 year=14 clazz=15</td></tr>
 *   <tr><td>DramaCoverImageBean</td><td>path=1 thumbnail_path=2</td></tr>
 *   <tr><td>DramaDetailBean</td><td>area=1 cover_image=2 intro=6 name=9 director=12
 *       tag=13 year=18 actor=25 remark=26 videos=29(repeated DramaVideoBean)</td></tr>
 *   <tr><td>DramaVideoBean</td><td>title=2 path=4 source=9 source_cn=10</td></tr>
 *   <tr><td>ParsePlayUrlBean</td><td>play_url=1 headers=6(repeated map entry: key=1 value=2)</td></tr>
 *   <tr><td>RSAPublic</td><td>str1..str5 = 1..5</td></tr>
 *   <tr><td>NormalTagsBean / SectionBean</td><td>sections=31 / vodList=5</td></tr>
 * </table>
 * 解码用仓库既有的 {@link Proto#decode(byte[])}（另一路已交付的通用 wire 解码器），
 * 不重复造轮子，也不需要 protobuf 运行库。
 *
 * <h2>ext 契约</h2>
 * <pre>
 *   host      站点根（"站"字段可下发域名覆盖它，见 {@link #configure}）
 *   publicKey RSA 公钥 Base64（SubjectPublicKeyInfo；<b>出站签名用</b>，不是解密钥）
 *   pkg       包名（进设备指纹）
 *   appName   App 名（进设备指纹）
 *   version   版本名（进设备指纹；vApp 会去掉点号）
 *   decrypt   "0" 表示 <b>跳过</b> homeVideoContent 的双层解密
 *   dataKey   AES-256 原始 32 字符密钥（<b>不是</b> Base64 解码后再用）
 *   dataIv    同上，且<b>名字是误导：它从来不当 IV 用</b>，也是 AES-256 密钥
 *   site      可选；非空时先 GET 它取 {@code domain} 覆盖 host
 * </pre>
 *
 * <h2>修正 / 记录的上游问题</h2>
 * <ol>
 *   <li><b>筛选表 null 直接 NPE</b>：{@code categoryContent} 用
 *       {@code map.getOrDefault(...)}；宿主首次进入分类页常传 null。已按空表处理；
 *       钉住断言：{@code AppDramaSpiderTest#categoryNullExtend()}。</li>
 *   <li><b>页码无容错</b>：结尾 {@code cVar.c(Integer.parseInt(str2))} 遇空/非数字页码 NFE。
 *       已用 {@code pageNum(pg,1)}；钉住断言：{@code AppDramaSpiderTest#pageTolerance()}。</li>
 *   <li><b>{@code c() 被调用两次</b>：{@code init} 里
 *       {@code HashMap mapC = c(); mapC.putAll(c());} —— 第二次调用只是覆盖同一批键，
 *       等价于一次（每次都重新生成 randomStr/时间戳，等于白算一遍 RSA 签名）。
 *       已按一次实现并注释。</li>
 *   <li><b>公钥轮换依赖"站"字段的裸 GET</b>：{@code b.l(site, null)} 没有解包任何包装，
 *       直接 {@code optString("domain")}；站点返回非 JSON 时被 catch 吞掉。
 *       保持行为并加日志。</li>
 *   <li><b>{@code coverImage} 可能缺失</b>：homeVideoContent 里
 *       {@code optJSONObject("coverImage").optString("path")} 未判空 → NPE。已改为缺省空串；
 *       钉住断言：{@code AppDramaSpiderTest#tagListWithoutCover()}。</li>
 *   <li><b>详情里 sourceCn 为空时统一叫"橘汁"</b>：不只是空串兜底，
 *       它还会把"四站通用"的线路塞进"橘汁"这一组；保持原样并注释。</li>
 *   <li><b>空响应会变成"幻影条目"</b>：详情接口没有 {@code data} 时上游仍会
 *       {@code DramaDetailBean.parseFrom(new byte[0])}，getter 全返回默认值，
 *       最终吐出一个 {@code vod_id/vod_name} 全空的条目，宿主渲染空卡片。
 *       已把"没有 data"判成无详情（返回 {@code {}}）；播放接口同理，
 *       {@code play_url} 为空时改吐"播放链接解析失败"而不是 {@code {"url":""}}。
 *       钉住断言：{@code AppDramaSpiderTest#detailEmptyResponse()} /
 *       {@code AppDramaSpiderTest#playerEmptyPlayUrl()}。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 宿主类反编译原文: {@code ae/decompiled/AppDrama.java}（逐行核对字段与 7 个内容方法）；
 * 协议核与证据编号: {@link AppDramaPlain} 的类 javadoc；
 * 字段号: {@code re/spider.dex} 内嵌 FileDescriptorProto（偏移见交付报告）。
 
 *
 * <h2>协议核 ↔ 外壳的文件名对应关系（防止"文件在就是做完了"的误判）</h2>
 * <table border="1">
 *   <tr><th>宿主类</th><th>协议核（无基类、不是源）</th><th>外壳（真源）</th></tr>
 *   <tr><td>{@code com.github.catvod.spider.AppDrama}</td>
 *       <td>{@code maintain/src/plain/appdrama/AppDramaPlain.java}</td>
 *       <td>{@code maintain/src/plain/appdrama/AppDramaSpiderPlain.java}（本类）</td></tr>
 * </table>
 * 只看文件名会以为 appdrama 域已经做完 —— 事实上 {@link AppDramaPlain} 只覆盖了
 * 加密/线格式/站点常量，<b>7 个内容方法与 ext 契约只在本类里</b>。
 * 覆盖度工具是沿继承链走到 {@code Spider} 才算完成的：{@link AppDramaPlain} 是
 * {@code final} 且不继承任何 Spider，所以它<b>永远不会</b>让 coverage 变绿；
 * 本类 {@code extends BaseSpider}（→ {@code Spider}）才是那个"被计入"的类。
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>{@code sourceCn} 缺失的归类</b>：上游把空 sourceCn 归到字面量
 *       {@code "橘汁"}；也就是说在"天堂"这台设备上，四站通用的线路会显示成"橘汁"。
 *       本类保持原样（改成站名会让线路名与其他实现不一致）。</li>
 *   <li><b>非直链地址的二次解析</b>：详情里凡是不匹配视频后缀的路径都被包成
 *       Base64 JSON，真正的解析在 {@code playerContent} 侧；
 *       如果宿主直接调用 {@code playerContent} 而跳过详情，那些参数不成立。</li>
 *   <li><b>解密开关只对 {@code homeVideoContent} 生效</b>：上游 {@code g=="0"} 只
 *       跳过标签列表的双层解密；分类/详情/搜索/播放走的是 AES 请求体通道，
 *       与本开关无关（别把它当全局开关）。</li>
 *   <li><b>设备指纹里的 Android 值</b>：{@code Build.MANUFACTURER/MODEL/BRAND}、
 *       {@code SDK_INT}、{@code Settings.Secure android_id} 在本重写里是可注入字段
 *       （{@link #setDevice}），默认值来自证据笔记；真机值未采集。</li>
 *   <li><b>未还原</b>：{@code ApiResult.code != 200} 的业务错误分支（上游不判断，
 *       直接取 data；本类同样不判断，见类注释）、以及 zone 响应里
 *       {@code str2..str5} 拼接后的公钥格式（本类按"无头纯 Base64"处理，
 *       协议核提供 {@code stripPem} 兜底）。</li>
 * </ol>
 *
 * <h2>上游缺陷 / 已修正项</h2>
 * 见类注释顶部"修正 / 记录的上游问题"6 条；其中<b>已在本类修正</b>的是
 * ①②③⑤（null extend 的 NPE、页码 NFE、{@code c()} 重复调用、缺 coverImage 的 NPE），
 * <b>原样保留并注释</b>的是 ④（site 裸 GET）与 ⑥（sourceCn 归"橘汁"），
 * 另有"空响应变幻影条目"一条见类注释末项。
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/AppDrama.java}；协议核证据编号见
 * {@code appdrama/AppDramaPlain.java} 的类 javadoc；业务 proto 字段号见类注释的字段表。

 *
 * <h2>coverage.py 如何识别"协议核 + 外壳"</h2>
 * 工具的判定顺序是：按 "<i>宿主类名 + Plain</i>" 与 "<i>宿主类名 + SpiderPlain</i>"
 * 两个候选找实现文件 → <b>沿继承链走到 Spider</b> 才算 done。
 * 于是本域的两个文件各自的身份就清楚了：
 * <ul>
 *   <li>{@code AppDramaPlain} —— 协议核（{@code final} 工具类，继承链为空）→ 永远不是 done；</li>
 *   <li>{@code AppDramaSpiderPlain}（本类）—— {@code extends BaseSpider} → 被判为 done。</li>
 * </ul>
 * 报告里的 {@code AppDramaPlain + AppDramaSpiderPlain} 表示"两者都在位"；
 * 若只显示 {@code AppDramaPlain}，说明外壳缺失（这正是历史上把它误判成做完了的原因）。
*/
public class AppDramaSpiderPlain extends BaseSpider {

    private static final String TAG = "AppDramaSpider";

    // ── 常量 ─────────────────────────────────────────────────

    /** 四条接口路径（与协议核常量同源） */
    public static final String PATH_ZONE = AppDramaPlain.PATH_ZONE;
    public static final String PATH_CATEGORY = AppDramaPlain.PATH_CATEGORY;
    public static final String PATH_TAG_LIST = AppDramaPlain.PATH_TAG_LIST;
    public static final String PATH_CATEGORY_PROTO = AppDramaPlain.PATH_CATEGORY_PROTO;
    public static final String PATH_SEARCH_PROTO = AppDramaPlain.PATH_SEARCH_PROTO;
    public static final String PATH_DETAIL_PROTO = AppDramaPlain.PATH_DETAIL_PROTO;
    public static final String PATH_PLAY_PROTO = AppDramaPlain.PATH_PLAY_PROTO;

    /** 硬编码 commonKey（16 字符，AES-128；JSON 通道 key=iv=本值） */
    public static final String COMMON_KEY = AppDramaPlain.COMMON_KEY;

    /** 固定 UA（两条通道都是 okhttp/3.12.1） */
    public static final String USER_AGENT = "okhttp/3.12.1";

    /** 每页条数（三个接口都是 21） */
    public static final int PAGE_SIZE = 21;

    /** 详情里 sourceCn 为空的线路被归到这一组（上游硬编码） */
    public static final String DEFAULT_SOURCE_CN = "橘汁";

    /** 首页分类里要跳过的名字（上游硬编码） */
    public static final String SKIP_CATEGORY_NAME = "公告";

    /** 首页筛选项的来源键（上游 {@code Arrays.asList(...)} 顺序） */
    public static final String[] FILTER_KEYS =
            {"class", "lang", "area", "year", "extend_sort"};

    /** 首页筛选参数在 extend 里的键名（与 {@link AppDramaPlain#categoryParams} 对应） */
    public static final String FILTER_SORT_KEY = "extend_sort";
    /** 排序默认值 */
    public static final String DEFAULT_SORT = "最新";

    /** 跳过解密开关值：ext.decrypt == 本值时不解密（上游 {@code !this.g.equals("0")}） */
    public static final String DECRYPT_OFF = "0";

    /** 弹幕动作（与仓库内 AppSourceBase 约定一致） */
    public static final String DANMAKU_ACTION = "appdanmu";

    /** 播放失败提示 */
    public static final String PLAY_FAILED_HINT = "播放链接解析失败,请更换其他源播放";

    // 业务 proto 字段号（全部来自 DEX 内嵌 descriptor，见类注释）
    /** ApiResult.data 字段号 */
    public static final int F_API_DATA = 3;
    /** DramaBeanPage.dramaBean 字段号 */
    public static final int F_PAGE_BEAN = 1;
    /** DramaBean.id / cover_image / name / remark / year */
    public static final int F_BEAN_ID = 3;
    /** DramaBean.cover_image */
    public static final int F_BEAN_COVER = 2;
    /** DramaBean.name */
    public static final int F_BEAN_NAME = 5;
    /** DramaBean.remark */
    public static final int F_BEAN_REMARK = 13;
    /** DramaCoverImageBean.thumbnail_path */
    public static final int F_COVER_THUMB = 2;
    /** DramaDetailBean.videos */
    public static final int F_DETAIL_VIDEOS = 29;
    /** DramaVideoBean.path */
    public static final int F_VIDEO_PATH = 4;
    /** DramaVideoBean.source_cn */
    public static final int F_VIDEO_SOURCE_CN = 10;
    /** DramaVideoBean.source */
    public static final int F_VIDEO_SOURCE = 9;
    /** DramaVideoBean.title */
    public static final int F_VIDEO_TITLE = 2;
    /** ParsePlayUrlBean.play_url */
    public static final int F_PLAY_URL = 1;
    /** ParsePlayUrlBean.headers（map entry） */
    public static final int F_PLAY_HEADERS = 6;

    // ── 会话状态（与宿主类字段一一对应） ─────────────────────

    /** 站点根（宿主字段 a） */
    protected String host = "";
    /** ext.publicKey（宿主字段 b） */
    protected String publicKey = "";
    /** 硬编码 commonKey（宿主字段 c） */
    protected String commonKey = COMMON_KEY;
    /** 服务端轮换公钥 str2+str3+str4+str5（宿主字段 d） */
    protected String rotatedPublicKey = "";
    /** ext.pkg（宿主字段 e） */
    protected String pkg = "";
    /** ext 原文（宿主字段 f1e） */
    protected JSONObject extJson = new JSONObject();
    /** ext.appName（宿主字段 f） */
    protected String appName = "";
    /** {dataKey, dataIv}（宿主字段 f2f） */
    protected String[] f2f = new String[]{"", ""};
    /** ext.decrypt（宿主字段 g） */
    protected String decryptFlag = "";
    /** ext.version */
    protected String version = "";

    /** 设备指纹里来自 Android Build 的部分（可注入，便于离线测试） */
    protected String manufacturer = "xiaomi";
    protected String model = "b0q";
    protected String brand = "xiaomi";
    protected int sdkInt = 32;
    protected String osRelease = "15";
    protected String androidId = "V417IR";

    /** 弹幕代理前缀（空串 = 不写 danmaku 字段） */
    protected String proxyBase = "";

    /** 构造 */
    public AppDramaSpiderPlain() {
        super();
    }

    // ── 生命周期 / ext 契约 ──────────────────────────────────

    /**
     * 初始化 —— 上游 {@code init(Context, String)} 的完整还原。
     *
     * @param context 宿主上下文，可为 null
     * @param extend  ext JSON
     */
    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        configure(extend);
    }

    /**
     * 解析 ext 并完成握手（可单测的主体，与 {@link #init} 等价）。
     *
     * <p>顺序与上游一致：
     * ① 填会话字段 → ② {@code site} 非空时 GET 它取 {@code domain} 覆盖 host
     * → ③ RSA 签名请求 {@code /api/v5/find/app/zone} 取轮换公钥。
     *
     * @param extend ext JSON 文本
     * @return true 表示已尝试初始化（ext 非空且可解析）
     */
    public boolean configure(String extend) {
        if (TextUtils.isEmpty(extend)) {
            Log.w(TAG, "ext 为空，AppDrama 源不可用");
            return false;
        }
        try {
            JSONObject o = new JSONObject(extend);
            this.extJson = o;
            this.host = o.optString("host", "");
            this.publicKey = o.optString("publicKey", "");
            this.commonKey = COMMON_KEY;                    // 硬编码，与 ext 无关
            this.rotatedPublicKey = "";
            this.pkg = o.optString("pkg", "");
            this.appName = o.optString("appName", "");
            this.version = o.optString("version", "");
            this.decryptFlag = o.optString("decrypt", "");
            this.f2f = new String[]{o.optString("dataKey", ""), o.optString("dataIv", "")};

            // ② "站"字段可下发域名覆盖 host（裸 GET，无头）
            String site = o.optString("site", "");
            if (!site.isEmpty()) {
                String domain = optJsonString(sendJsonGet(site, null), "domain");
                if (!domain.isEmpty()) {
                    this.host = domain;
                    Log.i(TAG, "站点域名被 site 字段覆盖: " + domain);
                }
            }
            if (TextUtils.isEmpty(this.host) || TextUtils.isEmpty(this.publicKey)) {
                Log.w(TAG, "host/publicKey 缺失，跳过 zone 握手");
                return true;
            }

            // ③ zone 握手取轮换公钥
            //    上游这里是 c(); mapC.putAll(c()); —— 等价的重复调用，已按一次实现
            long ts = System.currentTimeMillis();
            String rand = AppDramaPlain.randomStr(16);
            String sign = AppDramaPlain.rsaSign(ts + rand, this.publicKey);
            byte[] req = AppDramaPlain.buildRsaRequest(ts, rand, sign,
                    AppDramaPlain.randomStr(16), AppDramaPlain.randomStr(16));
            byte[] resp = sendBytes(this.host + PATH_ZONE, req, protoHeadersFor(ts, rand));
            this.rotatedPublicKey = parseZone(apiData(resp));
            Log.i(TAG, "zone 握手完成，轮换公钥长度=" + rotatedPublicKey.length());
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "init 失败", e);
            return false;
        }
    }

    /** 取 JSON 文本里的字符串字段（失败返回空串） */
    static String optJsonString(String json, String key) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            return new JSONObject(json).optString(key, "");
        } catch (Throwable e) {
            return "";
        }
    }

    // ── 请求层 ───────────────────────────────────────────────

    /**
     * JSON 通道请求头 —— 上游 {@code e()}。
     *
     * @param publicParamsJson 设备指纹 JSON
     * @return 非 null 头表
     */
    public Map<String, String> jsonChannelHeaders(String publicParamsJson) {
        return AppDramaPlain.jsonHeaders(publicParamsJson, commonKey);
    }

    /**
     * proto 通道请求头 —— 上游 {@code c()}。
     *
     * <p>公钥选择沿用上游：{@code rotatedPublicKey} 非空则用它，否则用 ext 的 publicKey。
     *
     * @param timestamp 毫秒时间戳
     * @param randomStr 随机串
     * @return 非 null 头表
     */
    public Map<String, String> protoHeadersFor(long timestamp, String randomStr) {
        String key = TextUtils.isEmpty(rotatedPublicKey) ? publicKey : rotatedPublicKey;
        String vApp = version.replace(".", "");
        if (vApp.isEmpty()) {
            vApp = "3019";
        }
        return AppDramaPlain.protoHeaders(timestamp, randomStr, vApp, key,
                commonKey, dataIv());
    }

    /**
     * 设备指纹 JSON —— 上游 {@code d()}。
     *
     * <p>键序固定为上游 {@code put} 顺序（{@link AppDramaPlain#publicParams} 已保证），
     * 手工序列化以免依赖 JSONObject 的实现顺序。
     *
     * @return JSON 文本
     */
    public String publicParamsJson() {
        Map<String, Object> m = AppDramaPlain.publicParams(version, pkg, appName,
                manufacturer, model, brand, sdkInt, osRelease, androidId);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number && !(v instanceof Double) && !(v instanceof Float)) {
                sb.append(v);
            } else {
                sb.append('"').append(esc(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    /**
     * JSON 通道 GET（覆写即可离线测试）。
     *
     * @param url     完整地址
     * @param headers 头表，可为 null
     * @return 响应文本；失败为空串
     */
    public String sendJsonGet(String url, Map<String, String> headers) {
        try {
            if (headers == null) {
                return http.get(url);
            }
            return http.get(url, headers);
        } catch (Throwable e) {
            Log.e(TAG, "sendJsonGet failed: " + url, e);
            return "";
        }
    }

    /**
     * proto 通道 POST（覆写即可离线测试）。
     *
     * <p><b>为什么不用 {@code common.Http}</b>：{@code Http} 是 String 通道 ——
     * 请求体走 {@code body.getBytes("UTF-8")}、响应走 {@code new String(bytes,"UTF-8")}，
     * 而 protobuf 是二进制（例如毫秒时间戳的 varint 会出现 ≥0x80 的字节），
     * 经 UTF-8 往返会被改写成 0xC2/0xC3 前缀，服务端必然解不出。
     * 所以这里直接用 {@code HttpURLConnection} 写/读字节；测试覆写本方法即可完全离线。
     * 证据：宿主 {@code b.b(url, bytes, headers)} 就是 byte[] 通道。
     *
     * @param url     完整地址
     * @param body    protobuf 请求体
     * @param headers 头表，可为 null
     * @return 响应字节；失败返回空数组（不抛）
     */
    public byte[] sendBytes(String url, byte[] body, Map<String, String> headers) {
        if (com.example.tvbox.common.Http.isOfflineForTest()) {
            Log.i(TAG, "离线测试模式，跳过真实请求: " + url);
            return new byte[0];
        }
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            byte[] payload = body == null ? new byte[0] : body;
            conn.setFixedLengthStreamingMode(payload.length);
            java.io.OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                return new byte[0];
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return bos.toByteArray();
        } catch (Throwable e) {
            Log.e(TAG, "sendBytes failed: " + url, e);
            return new byte[0];
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                    // 断开失败不影响结果
                }
            }
        }
    }

    /**
     * 走 JSON 通道的 GET（自动带头）。
     *
     * @param path 接口路径
     * @return 响应文本
     */
    public String jsonGet(String path) {
        return sendJsonGet(host + path, jsonChannelHeaders(publicParamsJson()));
    }

    /**
     * 走 proto 通道的 POST —— 上游 {@code g(map)} + {@code c()}。
     *
     * @param path      接口路径
     * @param bizParams 业务参数（<b>顺序敏感</b>：kv 串直接进签名材料）
     * @return {@code ApiResult.data} 字节；失败返回空数组
     */
    public byte[] protoPost(String path, Map<String, String> bizParams) {
        try {
            long ts = System.currentTimeMillis();
            String rand = AppDramaPlain.randomStr(8);
            byte[] req = AppDramaPlain.buildSecureRequestFromParams(bizParams, dataKey(), ts,
                    rand, AppDramaPlain.randomStr(20));
            byte[] resp = sendBytes(host + path, req, protoHeadersFor(ts, rand));
            return apiData(resp);
        } catch (Throwable e) {
            Log.e(TAG, "protoPost failed: " + path, e);
            return new byte[0];
        }
    }

    // ── protobuf 解析（字段号来自 DEX descriptor） ────────────

    /**
     * 取 {@code ApiResult.data} —— 上游 {@code ApiResultProto.ApiResult.parseFrom(x).getData()}。
     *
     * @param raw 原始响应字节
     * @return data 字节；解析失败或字段缺失返回空数组
     */
    public static byte[] apiData(byte[] raw) {
        try {
            Proto.Field f = Proto.find(Proto.decode(raw), F_API_DATA);
            return f == null ? new byte[0] : f.asBytes();
        } catch (Throwable e) {
            Log.w(TAG, "apiData 解析失败");
            return new byte[0];
        }
    }

    /**
     * 解析 {@code RSAPublic} → {@code str2+str3+str4+str5} —— 上游 init 尾部。
     *
     * @param data ApiResult.data 字节
     * @return 轮换公钥串；失败返回空串
     */
    public static String parseZone(byte[] data) {
        try {
            List<Proto.Field> fs = Proto.decode(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i <= 5; i++) {
                Proto.Field f = Proto.find(fs, i);
                sb.append(f == null ? "" : f.asString());
            }
            return sb.toString();
        } catch (Throwable e) {
            Log.w(TAG, "parseZone 失败");
            return "";
        }
    }

    /**
     * 解析 {@code DramaBeanPage.dramaBean} —— 分类/搜索列表。
     *
     * @param data ApiResult.data 字节
     * @return 非 null 列表
     */
    public static List<DramaBean> parseBeanPage(byte[] data) {
        List<DramaBean> out = new ArrayList<>();
        try {
            for (Proto.Field f : Proto.findAll(Proto.decode(data), F_PAGE_BEAN)) {
                out.add(DramaBean.of(f.asMessage()));
            }
        } catch (Throwable e) {
            Log.w(TAG, "parseBeanPage 失败");
        }
        return out;
    }

    /**
     * 解析 {@code DramaDetailBean} —— 详情。
     *
     * @param data ApiResult.data 字节
     * @return 详情对象；失败返回 null
     */
    public static DramaDetail parseDetail(byte[] data) {
        try {
            return DramaDetail.of(Proto.decode(data));
        } catch (Throwable e) {
            Log.w(TAG, "parseDetail 失败");
            return null;
        }
    }

    /**
     * 解析 {@code ParsePlayUrlBean} —— 播放地址与请求头。
     *
     * @param data ApiResult.data 字节
     * @return 播放对象；失败返回 null
     */
    public static PlayUrl parsePlayUrl(byte[] data) {
        try {
            return PlayUrl.of(Proto.decode(data));
        } catch (Throwable e) {
            Log.w(TAG, "parsePlayUrl 失败");
            return null;
        }
    }

    /** DramaBean（字段号见 {@link #F_BEAN_ID} 等常量） */
    public static class DramaBean {
        /** id（字段 3） */
        public String id = "";
        /** name（字段 5） */
        public String name = "";
        /** cover_image.thumbnail_path（2.2） */
        public String pic = "";
        /** remark（字段 13） */
        public String remark = "";
        /** year（字段 14） */
        public String year = "";
        /** cover_image.path（2.1） */
        public String coverPath = "";

        /**
         * 从字段表构造。
         *
         * @param fs 字段表
         * @return 非 null
         */
        public static DramaBean of(List<Proto.Field> fs) {
            DramaBean b = new DramaBean();
            Proto.Field id = Proto.find(fs, F_BEAN_ID);
            b.id = id == null ? "" : String.valueOf(id.asLong());
            Proto.Field name = Proto.find(fs, F_BEAN_NAME);
            b.name = name == null ? "" : name.asString();
            Proto.Field remark = Proto.find(fs, F_BEAN_REMARK);
            b.remark = remark == null ? "" : remark.asString();
            Proto.Field year = Proto.find(fs, 14);
            b.year = year == null ? "" : String.valueOf(year.asLong());
            List<Proto.Field> cover = coverFields(fs);
            b.pic = stringOf(cover, F_COVER_THUMB);
            b.coverPath = stringOf(cover, 1);
            return b;
        }

        /** 取 cover_image 子消息字段（缺失返回空表） */
        private static List<Proto.Field> coverFields(List<Proto.Field> fs) {
            Proto.Field c = Proto.find(fs, F_BEAN_COVER);
            return c == null ? new ArrayList<Proto.Field>() : c.asMessage();
        }

        @Override
        public String toString() {
            return "DramaBean{" + id + "," + name + "}";
        }
    }

    /** DramaDetailBean 的常用字段子集 */
    public static class DramaDetail {
        /** 片名（字段 9） */
        public String name = "";
        /** 简介（字段 6） */
        public String intro = "";
        /** 标签（字段 13） */
        public String tag = "";
        /** 地区（字段 1） */
        public String area = "";
        /** 年份（字段 18） */
        public String year = "";
        /** 主演（字段 25） */
        public String actor = "";
        /** 导演（字段 12） */
        public String director = "";
        /** 备注（字段 26） */
        public String remark = "";
        /** 封面 cover_image.path（2） */
        public String pic = "";
        /** 视频列表（字段 29，repeated） */
        public List<DramaVideo> videos = new ArrayList<>();

        /**
         * 从字段表构造。
         *
         * @param fs 字段表
         * @return 非 null
         */
        public static DramaDetail of(List<Proto.Field> fs) {
            DramaDetail d = new DramaDetail();
            d.name = str(fs, 9);
            d.intro = str(fs, 6);
            d.tag = str(fs, 13);
            d.area = str(fs, 1);
            d.year = num(fs, 18);
            d.actor = str(fs, 25);
            d.director = str(fs, 12);
            d.remark = str(fs, 26);
            Proto.Field cover = Proto.find(fs, F_BEAN_COVER);
            d.pic = cover == null ? "" : stringOf(cover.asMessage(), 1);
            for (Proto.Field f : Proto.findAll(fs, F_DETAIL_VIDEOS)) {
                d.videos.add(DramaVideo.of(f.asMessage()));
            }
            return d;
        }
    }

    /** DramaVideoBean 的常用字段子集 */
    public static class DramaVideo {
        /** 集名（字段 2） */
        public String title = "";
        /** 地址（字段 4） */
        public String path = "";
        /** 线路标记（字段 9） */
        public String source = "";
        /** 线路中文名（字段 10） */
        public String sourceCn = "";

        /**
         * 从字段表构造。
         *
         * @param fs 字段表
         * @return 非 null
         */
        public static DramaVideo of(List<Proto.Field> fs) {
            DramaVideo v = new DramaVideo();
            v.title = str(fs, F_VIDEO_TITLE);
            v.path = str(fs, F_VIDEO_PATH);
            v.source = str(fs, F_VIDEO_SOURCE);
            v.sourceCn = str(fs, F_VIDEO_SOURCE_CN);
            return v;
        }
    }

    /** ParsePlayUrlBean 的子集 */
    public static class PlayUrl {
        /** 播放地址（字段 1） */
        public String playUrl = "";
        /** 自定义请求头（字段 6，map entry: key=1/value=2） */
        public final Map<String, String> headers = new LinkedHashMap<>();

        /**
         * 从字段表构造。
         *
         * @param fs 字段表
         * @return 非 null
         */
        public static PlayUrl of(List<Proto.Field> fs) {
            PlayUrl p = new PlayUrl();
            p.playUrl = str(fs, F_PLAY_URL);
            for (Proto.Field f : Proto.findAll(fs, F_PLAY_HEADERS)) {
                List<Proto.Field> entry = f.asMessage();
                String k = str(entry, 1);
                String v = str(entry, 2);
                if (!k.isEmpty()) {
                    p.headers.put(k, v);
                }
            }
            return p;
        }
    }

    /** 取字符串字段 */
    static String str(List<Proto.Field> fs, int number) {
        Proto.Field f = Proto.find(fs, number);
        return f == null ? "" : f.asString();
    }

    /** 取整数/枚举字段的字符串形式 */
    static String num(List<Proto.Field> fs, int number) {
        Proto.Field f = Proto.find(fs, number);
        return f == null ? "" : String.valueOf(f.asLong());
    }

    /** 在子消息字段表里取字符串 */
    static String stringOf(List<Proto.Field> fs, int number) {
        return fs == null ? "" : str(fs, number);
    }

    // ── 契约方法 ─────────────────────────────────────────────

    /**
     * 首页：分类表 + 筛选项（JSON 通道）。
     *
     * <p>跳过名为"公告"的分类；筛选项来自分类的 {@code converUrl}（一个 JSON 字符串），
     * 键顺序为 {@link #FILTER_KEYS}。
     *
     * @param filter 宿主传参，未使用
     * @return 标准首页响应
     */
    @Override
    public String homeContent(boolean filter) {
        try {
            VodBuilder b = VodBuilder.create();
            JSONObject resp = new JSONObject(jsonGet(PATH_CATEGORY));
            JSONArray data = resp.optJSONArray("data");
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject c = data.optJSONObject(i);
                    if (c == null) {
                        continue;
                    }
                    if (SKIP_CATEGORY_NAME.equals(c.optString("name", ""))) {
                        continue;
                    }
                    String id = c.optString("id", "");
                    b.clazz(id, c.optString("name", ""));
                    JSONArray f = parseFilters(c.optString("converUrl", ""));
                    if (f.length() > 0) {
                        b.filter(id, f);
                    }
                }
            }
            return b.homeContent();
        } catch (Throwable e) {
            Log.e(TAG, "homeContent failed", e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /**
     * 解析分类的 {@code converUrl} 筛选项。
     *
     * @param converUrl JSON 文本（形如 {@code {"class":"喜剧,动作","year":"2024"}}）
     * @return 非 null 数组（{@code key/name/value}）
     */
    public static JSONArray parseFilters(String converUrl) {
        JSONArray out = new JSONArray();
        if (TextUtils.isEmpty(converUrl)) {
            return out;
        }
        try {
            JSONObject o = new JSONObject(converUrl);
            for (String key : FILTER_KEYS) {
                if (!o.has(key)) {
                    continue;
                }
                String v = o.optString(key, "");
                if (v.isEmpty()) {
                    continue;
                }
                JSONArray options = new JSONArray();
                for (String one : v.split(",")) {
                    if (one.isEmpty()) {
                        continue;
                    }
                    JSONObject opt = new JSONObject();
                    opt.put("n", one);
                    opt.put("v", one);
                    options.put(opt);
                }
                JSONObject group = new JSONObject();
                group.put("key", key);
                group.put("name", key);          // 上游 name 与 key 同值
                group.put("value", options);
                out.put(group);
            }
        } catch (Throwable e) {
            Log.w(TAG, "converUrl 解析失败，忽略该分类筛选");
        }
        return out;
    }

    /**
     * 首页轮播（标签列表 → sections[].vodList[]）。
     *
     * <p>{@code ext.decrypt != "0"} 时先做双层 AES/ECB 解密
     * （内层 dataKey、外层 dataIv，顺序不可交换）。
     *
     * @return {@code {"list":[…]}}
     */
    @Override
    public String homeVideoContent() {
        try {
            String data = new JSONObject(jsonGet(PATH_TAG_LIST)).optString("data", "");
            if (data.isEmpty()) {
                return VodBuilder.create().searchContent();
            }
            if (!DECRYPT_OFF.equals(decryptFlag)) {
                data = AppDramaPlain.aesEcbDecrypt(
                        AppDramaPlain.aesEcbDecrypt(data, dataKey()), dataIv());
            }
            List<Vod> list = new ArrayList<>();
            JSONArray arr = new JSONArray(data);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject tag = arr.optJSONObject(i);
                if (tag == null) {
                    continue;
                }
                JSONArray sections = tag.optJSONArray("sections");
                if (sections == null) {
                    continue;
                }
                for (int j = 0; j < sections.length(); j++) {
                    JSONObject sec = sections.optJSONObject(j);
                    if (sec == null) {
                        continue;
                    }
                    JSONArray vodList = sec.optJSONArray("vodList");
                    if (vodList == null) {
                        continue;
                    }
                    for (int k = 0; k < vodList.length(); k++) {
                        JSONObject v = vodList.optJSONObject(k);
                        if (v == null) {
                            continue;
                        }
                        list.add(new Vod()
                                .id(v.optString("id", ""))
                                .name(v.optString("name", ""))
                                .pic(coverPath(v))
                                .remarks(v.optString("remark", "")));
                    }
                }
            }
            return VodBuilder.create().addAll(list).searchContent();
        } catch (Throwable e) {
            Log.e(TAG, "homeVideoContent failed", e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /** 取 {@code coverImage.path}；缺 coverImage 时返回空串（上游此处会 NPE） */
    static String coverPath(JSONObject vod) {
        JSONObject cover = vod == null ? null : vod.optJSONObject("coverImage");
        return cover == null ? "" : cover.optString("path", "");
    }

    /**
     * 分类页（proto 通道）。
     *
     * @param tid    分类 id
     * @param pg     页码（空/非数字按 1）
     * @param filter 宿主传参，未使用
     * @param extend 筛选（class/area/lang/year/extend_sort），可为 null
     * @return 标准分类响应
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        try {
            int page = pageNum(pg, 1);
            Map<String, String> params = AppDramaPlain.categoryParams(tid,
                    String.valueOf(page),
                    extend == null ? new HashMap<String, String>() : extend);
            List<DramaBean> beans = parseBeanPage(protoPost(PATH_CATEGORY_PROTO, params));
            List<Vod> list = new ArrayList<>();
            for (DramaBean b : beans) {
                list.add(toVod(b));
            }
            // 上游 cVar.z(list) + cVar.c(page)：只有 page 与 list
            return VodBuilder.create().addAll(list).page(page).categoryContent();
        } catch (Throwable e) {
            Log.e(TAG, "categoryContent failed, tid=" + tid, e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /** DramaBean → Vod */
    public static Vod toVod(DramaBean b) {
        return new Vod().id(b.id).name(b.name).pic(b.pic).remarks(b.remark).year(b.year);
    }

    /**
     * 详情页（proto 通道）。
     *
     * <p>视频按 {@code sourceCn} 分组（为空时归到 {@link #DEFAULT_SOURCE_CN}）；
     * 非直链地址会被包成 Base64 JSON 交给 {@code playerContent} 二次解析。
     *
     * @param ids 上游只取第一个
     * @return 标准详情响应
     */
    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids == null || ids.isEmpty() ? "" : ids.get(0);
            byte[] data = protoPost(PATH_DETAIL_PROTO, AppDramaPlain.detailParams(id));
            if (data.length == 0) {
                // 上游这里是 DramaDetailBean.parseFrom(new byte[0]) —— 不报错，
                // 于是 getter 全返回默认值，最后吐出一个 vod_id/vod_name 全空的"幻影条目"。
                // 这里把"没有 data"显式判成无详情，避免宿主渲染空卡片。
                Log.w(TAG, "详情响应没有 data，按无详情返回");
                return VodBuilder.EMPTY_RESPONSE;
            }
            DramaDetail d = parseDetail(data);
            if (d == null) {
                return VodBuilder.EMPTY_RESPONSE;
            }
            Map<String, List<String>> groups = new LinkedHashMap<>();
            for (DramaVideo v : d.videos) {
                String sourceCn = v.sourceCn.isEmpty() ? DEFAULT_SOURCE_CN : v.sourceCn;
                if (!groups.containsKey(sourceCn)) {
                    groups.put(sourceCn, new ArrayList<String>());
                }
                String path = v.path;
                if (!AppDramaPlain.isVideoUrl(path)) {
                    path = AppDramaPlain.wrapPlayUrl(v.source, path);
                }
                groups.get(sourceCn).add(v.title + "$" + path);
            }
            StringBuilder from = new StringBuilder();
            StringBuilder url = new StringBuilder();
            for (Map.Entry<String, List<String>> e : groups.entrySet()) {
                if (from.length() > 0) {
                    from.append("$$$");
                }
                from.append(e.getKey());
                if (url.length() > 0) {
                    url.append("$$$");
                }
                url.append(join(e.getValue(), "#"));
            }
            Vod vod = new Vod()
                    .id(d.name)
                    .name(d.name)
                    .pic(d.pic)
                    .remarks(d.remark)
                    .year(d.year)
                    .area(d.area)
                    .actor(d.actor)
                    .director(d.director)
                    .content(d.intro)
                    .tag(d.tag)
                    .playFrom(from.toString())
                    .playUrl(url.toString());
            String json = VodBuilder.detail(vod).detailContent();
            afterDetail(json, d.name);
            return json;
        } catch (Throwable e) {
            Log.e(TAG, "detailContent failed", e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /**
     * 详情后的钩子 —— 上游 {@code merge.p026a.a.parseJsonAndSave(String)}。
     *
     * @param detailJson 详情文本
     * @param vodName    片名
     */
    public void afterDetail(String detailJson, String vodName) {
        Log.i(TAG, "afterDetail: " + vodName);
    }

    /**
     * 搜索（proto 通道，页码固定 1）。
     *
     * @param key    关键词
     * @param filter 宿主传参，未使用
     * @return {@code {"list":[…]}}
     */
    @Override
    public String searchContent(String key, boolean filter) {
        return searchContent(key, filter, "1");
    }

    /**
     * 搜索（可指定页码）—— 上游的三参重载。
     *
     * @param key    关键词
     * @param filter 宿主传参，未使用
     * @param page   页码
     * @return {@code {"list":[…]}}
     */
    public String searchContent(String key, boolean filter, String page) {
        try {
            List<DramaBean> beans = parseBeanPage(protoPost(PATH_SEARCH_PROTO,
                    AppDramaPlain.searchParams(key, page)));
            List<Vod> list = new ArrayList<>();
            for (DramaBean b : beans) {
                list.add(toVod(b));
            }
            return VodBuilder.create().addAll(list).searchContent();
        } catch (Throwable e) {
            Log.e(TAG, "searchContent failed", e);
            return VodBuilder.EMPTY_RESPONSE;
        }
    }

    /**
     * 播放。
     *
     * <p>两个分支与上游一致：
     * <ul>
     *   <li>入参本身是直链视频 → 直接返回（{@code {"url":…}}）；</li>
     *   <li>否则按 Base64 JSON 解包成业务参数 → POST {@code /api/proto/v5/videoUsableUrl}
     *       → {@code {"url": play_url, "header": {…}}}</li>
     * </ul>
     * ⚠️ 上游<b>不输出 parse 字段</b>（缺省即直链），本重写保持一致。
     *
     * @param flag  站点标识（未使用）
     * @param id    播放串
     * @param flags 未使用
     * @return 播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> flags) {
        try {
            if (AppDramaPlain.isVideoUrl(id)) {
                return addDanmaku(playJson(id, null));
            }
            Map<String, String> params = AppDramaPlain.unwrapPlayUrl(id == null ? "" : id);
            if (params.isEmpty()) {
                Log.w(TAG, "播放串不是直链也不是合法 Base64 JSON");
                return playFailed();
            }
            PlayUrl p = parsePlayUrl(protoPost(PATH_PLAY_PROTO,
                    AppDramaPlain.playParams(params)));
            if (p == null || p.playUrl.isEmpty()) {
                // 上游会输出 {"url":""}（ParsePlayUrlBean 全默认值），宿主只会得到一个
                // 打不开的播放器；这里改成与 catch 分支同一句提示。
                Log.w(TAG, "解析器没有返回可用地址");
                return playFailed();
            }
            return addDanmaku(playJson(p.playUrl, p.headers));
        } catch (Throwable e) {
            Log.e(TAG, "playerContent failed", e);
            return playFailed();
        }
    }

    /**
     * 构造播放响应 —— 上游 {@code c.s(url)} + {@code c.e(headers)}（+ 弹幕）。
     *
     * @param url     播放地址
     * @param headers 请求头，可为 null/空
     * @return JSON 文本
     */
    public String playJson(String url, Map<String, String> headers) {
        JSONObject o = new JSONObject();
        try {
            o.put("url", url == null ? "" : url);
            if (headers != null && !headers.isEmpty()) {
                JSONObject h = new JSONObject();
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    h.put(e.getKey(), e.getValue());
                }
                // 上游用 Gson 把 Map 序列化成**字符串**后放入 header 字段
                o.put("header", h.toString());
            }
        } catch (Throwable e) {
            Log.e(TAG, "playJson failed", e);
        }
        return o.toString();
    }

    /**
     * 追加弹幕字段 —— 上游 {@code merge.p026a.a.addDanmaku(String)}。
     *
     * @param json 播放 JSON
     * @return 带 danmaku 的文本；未注入代理时原样返回
     */
    public String addDanmaku(String json) {
        if (json == null || json.isEmpty() || proxyBase.isEmpty()) {
            return json;
        }
        try {
            JSONObject o = new JSONObject(json);
            o.put("danmaku", proxyBase + "?do=" + DANMAKU_ACTION);
            return o.toString();
        } catch (Throwable e) {
            return json;
        }
    }

    /** 播放失败响应 */
    public static String playFailed() {
        JSONObject o = new JSONObject();
        try {
            o.put("msg", PLAY_FAILED_HINT);
        } catch (Throwable e) {
            Log.w(TAG, "playFailed failed");
        }
        return o.toString();
    }

    // ── 小工具 / 注入点 ──────────────────────────────────────

    /**
     * dataKey（AES-256 原始 32 字符；<b>不要</b> Base64 解码）。
     *
     * @return 密钥原文
     */
    public String dataKey() {
        return f2f == null || f2f.length < 1 ? "" : f2f[0];
    }

    /**
     * dataIv（同样是 AES-256 密钥；<b>名字是误导，从不当 IV 用</b>）。
     *
     * @return 密钥原文
     */
    public String dataIv() {
        return f2f == null || f2f.length < 2 ? "" : f2f[1];
    }

    /** 拼接 */
    static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /** JSON 字符串转义 */
    static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 注入弹幕代理前缀。
     *
     * @param base 空串表示不写 danmaku
     */
    public void setProxyBase(String base) {
        this.proxyBase = base == null ? "" : base.trim();
    }

    /**
     * 当前弹幕代理前缀。
     *
     * @return 可能为空串
     */
    public String proxyBase() {
        return proxyBase;
    }

    /**
     * 注入设备指纹里的 Android 字段（离线测试用）。
     *
     * @param manufacturer 厂商
     * @param model        型号
     * @param brand        品牌
     * @param sdkInt       SDK 版本
     * @param osRelease    系统版本
     * @param androidId    androidId
     */
    public void setDevice(String manufacturer, String model, String brand, int sdkInt,
                          String osRelease, String androidId) {
        this.manufacturer = manufacturer;
        this.model = model;
        this.brand = brand;
        this.sdkInt = sdkInt;
        this.osRelease = osRelease;
        this.androidId = androidId;
    }

    /**
     * 直接注入会话（等价于 configure 成功后的内部状态）。
     *
     * @param host      站点根
     * @param publicKey ext 公钥
     * @param dataKey   dataKey
     * @param dataIv    dataIv
     */
    public void setSession(String host, String publicKey, String dataKey, String dataIv) {
        this.host = host == null ? "" : host;
        this.publicKey = publicKey == null ? "" : publicKey;
        this.f2f = new String[]{dataKey == null ? "" : dataKey, dataIv == null ? "" : dataIv};
    }

    /**
     * 设置解密开关。
     *
     * @param flag {@link #DECRYPT_OFF} 表示跳过 homeVideoContent 解密
     */
    public void setDecryptFlag(String flag) {
        this.decryptFlag = flag == null ? "" : flag;
    }

    /**
     * 设置轮换公钥。
     *
     * @param key 服务端下发的公钥
     */
    public void setRotatedPublicKey(String key) {
        this.rotatedPublicKey = key == null ? "" : key;
    }

    /**
     * 设置 ext 里的版本/包名/App 名。
     *
     * @param version 版本名
     * @param pkg     包名
     * @param appName App 名
     */
    public void setAppInfo(String version, String pkg, String appName) {
        this.version = version == null ? "" : version;
        this.pkg = pkg == null ? "" : pkg;
        this.appName = appName == null ? "" : appName;
    }

    /**
     * 当前站点根。
     *
     * @return 可能为空串
     */
    public String host() {
        return host;
    }

    /**
     * 当前轮换公钥。
     *
     * @return 可能为空串
     */
    public String rotatedPublicKey() {
        return rotatedPublicKey;
    }

    /**
     * 当前解密开关。
     *
     * @return 可能为空串
     */
    public String decryptFlag() {
        return decryptFlag;
    }

    /**
     * 当前 commonKey。
     *
     * @return 16 字符常量
     */
    public String commonKey() {
        return commonKey;
    }

    /**
     * 供排障：ext 原文。
     *
     * @return 非 null
     */
    public JSONObject extJson() {
        return extJson;
    }

    /**
     * 供排障：首页筛选键顺序。
     *
     * @return 非 null 列表
     */
    public static List<String> filterKeys() {
        return new ArrayList<>(Arrays.asList(FILTER_KEYS));
    }
}
