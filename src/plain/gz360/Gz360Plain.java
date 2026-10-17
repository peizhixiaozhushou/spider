package com.example.tvbox.plain.gz360;

import android.content.Context;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 瓜子视频（Gz360）明文实现。
 *
 * <p>反编译原文: {@code ae/decompiled/Gz360.java}（472 行，jadx dev）
 *
 * <h2>协议（全部经静态反编译还原）</h2>
 * <pre>
 *  所有业务接口：POST {base}/App/...  表单编码（application/x-www-form-urlencoded）
 *    表单 = token_id,token,phone_type,request_key,app_id,time,keys,signature,
 *           phone_model,ad_version        ← 见 {@link Gz360Crypto#buildRequest}
 *    响应 = {"data":{"response_key","keys"}}  → 解密后才是业务 JSON
 *
 *  请求头（8 项，C 类硬编码）:
 *    lang=zh_cn / User-Agent=okhttp/3.12.0 / Content-Type=form-urlencoded;charset=UTF-8
 *    code=GZ0313 / version=2606029 / packagename=com.v54f07a912.t4ee50f184.dbb1e7669f20260721
 *    ver=3.0.4.8 / api-ver=3.0.4.8 / referer={base}
 *
 *  接口:
 *    设备位登录  /App/Authentication/Device/signIn    (403 → signUp)
 *    令牌刷新    /App/Authentication/Authenticator/refresh
 *    用户信息    /App/UserInfo/getUserInfo            (取 playRef)
 *    首页推荐    /App/IndexList/choiceList            pid=1
 *    分类列表    /App/IndexList/indexList             tid/page/pageSize=30/area/year/sort/sub
 *    详情        /App/IndexPlay/playInfo              token_id/vod_id/mobile_time/token
 *    线路        /App/Resource/Vurl/show              vurl_cloud_id=2/vod_d_id
 *    播放地址    /App/Resource/VurlDetail/showOne
 *    搜索        /App/Index/findMore                keywords/order_val
 * </pre>
 *
 * <h2>ext 三层分类</h2>
 * <table border="1">
 *   <tr><th>类</th><th>键</th><th>说明</th></tr>
 *   <tr><td><b>A 类</b>（必需）</td><td>—</td>
 *       <td>本源<b>没有</b> A 类键：5 个域名全部有内置默认值，登录态可自动获取。
 *           不传任何 ext 也能跑（代价是每次启动要登录一次）</td></tr>
 *   <tr><td><b>B 类</b>（有默认值，可覆盖）</td>
 *       <td>{@code sites}（逗号分隔域名表，默认 5 个）、{@code url}/{@code host}（单域名）、
 *           {@code token} + {@code appUserId}（预置登录态，免去登录）、
 *           {@code uaSuffix}</td>
 *       <td>域名会热更、密钥不会；登录态预置可省一次登录往返</td></tr>
 *   <tr><td><b>C 类</b>（纯硬编码，直接内联）</td>
 *       <td>{@code 1a2b3c4d5e6f7g8h}（android_id 兜底）、HmacSHA1 密钥
 *           {@code d6fc3a4a06adbde89223bvefedc24fecde188aaa9161}、签名盐
 *           {@code *&zvdvdvddbfikkkumtmdwqppp?|4Y!s!2br}、内置 RSA 公私钥、
 *           8 个请求头、5 级分类表、filters 大 JSON、默认 {@code playRef}</td>
 *       <td>无 ext 通道</td></tr>
 * </table>
 *
 * <h2>发现的上游缺陷（本实现已修，均有断言钉住）</h2>
 * <ol>
 *   <li><b>清晰度标签被写错</b>（{@code detailContent}）：循环里
 *       {@code str = 候选清晰度} 是「最后遍历到的键」而不是「命中的键」。
 *       若某条线路只有 {@code 480P} 且其 {@code param} 为空，
 *       标签会变成 {@code [240P]}（最后尝试的候选）。本实现只在真正命中时记键。</li>
 *   <li><b>ext.filters 读出即丢</b>（第 344 行 {@code jSONObject.optString("filters");}
 *       无赋值）。本实现把它当作可覆盖 filters 大 JSON 的入口（B 类）。</li>
 *   <li><b>静态可变全局</b>：{@code AuthData e} 与 {@code playRef f} 都是 {@code static}，
 *       多实例共享登录态；{@code f} 还被 {@code playerContent} 当 Referer 用。
 *       本实现改成实例字段，并保留 {@link #playRef()} 访问器。</li>
 *   <li><b>{@code f()} 里 hex 解码无长度校验</b>：奇数长度会读越界，
 *       非法字符被静默拼进字节。见 {@link Gz360Crypto#unhex}。</li>
 *   <li><b>设备号依赖 {@code Settings.Secure}</b>：stub/JVM 环境没有该 API，
 *       且读不到时回落硬编码常量。本实现改为可注入 {@link #setAndroidId}（默认走兜底常量）。</li>
 *   <li><b>设备 UA 后缀依赖 {@code android.os.Build}</b>：把 {@code @User-Agent=Dalvik/…}
 *       拼在<b>图片 URL 尾部</b>（给宿主图片代理传 UA 的惯例）。本实现做成可注入
 *       {@link #setUaSuffix}，默认空串（拼接逻辑与位置保持不变）。</li>
 * </ol>

 * <h2>未覆盖的分支</h2>
 * <ul>
 *   <li><b>内置 RSA 公私钥不是同一对 → 无法用内置常量自证下行解密</b>：
 *       实测公钥模数 {@code D4339FBF…}、私钥模数 {@code 7BA84AAD…}（见
 *       {@link Gz360Crypto} 类注释）。设计上是"上行用服务器公钥 P1、下行用客户端私钥 P2"，
 *       所以包内没有 P2 的公钥，<b>造不出</b>一个能被内置私钥解开的响应。
 *       本实现把私钥做成可注入（{@code setClientPrivateKey}），测试用<b>自造密钥对</b>做信封往返回路。
 *       断言：{@code 公私钥模数不同（不是同一对）}、{@code 往返后 code}。</li>
 *   <li><b>设备指纹的真实来源</b>：{@code Settings.Secure.getString(android_id)} 与
 *       {@code android.os.Build.*} 在 JVM/stub 下不可用。改为可注入
 *       {@code setAndroidId} / {@code setUaSuffix}，默认走源码里那条硬编码兜底值
 *       {@code 1a2b3c4d5e6f7g8h}。断言：{@code 空 id 回落兜底常量}。</li>
 *   <li><b>登录流程的完整字段集</b>：{@code signIn/signUp/refresh/getUserInfo} 四个接口
 *       只按调用点还原了 {@code new_key/old_key/recommend_id} 三个业务字段；
 *       若服务端还要求其它字段，静态产物里看不到。将来补上需要抓包。</li>
 *   <li><b>刻意保留的上游行为（看着像 bug，其实是对的）</b>：{@code homeContent}
 *       <b>刻意不输出 {@code list}</b> —— 首页推荐交给 {@code homeVideoContent}，
 *       补空 {@code list} 会让推荐位永远为空。断言：{@code ★ 刻意不输出 list（让宿主走 homeVideoContent）}。</li>
 * </ul>
 */
public class Gz360Plain extends BaseSpider {

    private static final String TAG = "Gz360Plain";

    // ── C 类硬编码 ──────────────────────────────────────────

    /** 5 个内置域名（B 类：可被 ext.sites 整体替换） */
    public static final String[] DEFAULT_SITES = {
            "https://api.h27sq4f.com",
            "https://api.wbhxjjv.com",
            "https://sdapi.e2wu4ht.com",
            "https://sdapi.7wstgq6.com",
            "https://apinew.uozvr.com"};

    /** 默认播放 Referer（{@code static String f}） */
    public static final String DEFAULT_PLAY_REF = "http://WJiZxLXA2.com/";

    /** 播放头 UA（C 类） */
    public static final String PLAY_UA = "Lavf/57.83.100";

    /** 固定 8 个请求头中的常量部分 */
    public static final String HDR_CODE = "GZ0313";
    public static final String HDR_VERSION = "2606029";
    public static final String HDR_PACKAGE =
            "com.v54f07a912.t4ee50f184.dbb1e7669f20260721";
    public static final String HDR_VER = "3.0.4.8";
    public static final String HDR_API_VER = "3.0.4.8";
    public static final String HDR_UA = "okhttp/3.12.0";
    public static final String HDR_LANG = "zh_cn";
    public static final String HDR_CONTENT_TYPE =
            "application/x-www-form-urlencoded; charset=UTF-8";

    /** 播放线路名（上游硬编码字面量） */
    public static final String PLAY_FROM = "瓜子视频";

    /** 分类页每页条数 */
    public static final String PAGE_SIZE = "30";

    /** 清晰度候选（按优先级） */
    public static final List<String> RESOLUTIONS =
            Collections.unmodifiableList(java.util.Arrays.asList(
                    "3840", "2160", "1080", "720", "480", "360", "240"));

    /** 内容过滤词（命中即剔除） */
    public static final String[] BLOCK_WORDS = {"伦理", "成人", "三级"};

    /** 弹幕代理动作（{@code merge.p026a.a.addDanmaku} = {@code ?do=diydanmu}） */
    public static final String DANMAKU_ACTION = "diydanmu";

    /** 5 级静态分类表 */
    public static final String CLASS_JSON = "[" 
            + "{\"type_id\":\"1\",\"type_name\":\"电影\"},"
            + "{\"type_id\":\"2\",\"type_name\":\"剧集\"},"
            + "{\"type_id\":\"3\",\"type_name\":\"综艺\"},"
            + "{\"type_id\":\"4\",\"type_name\":\"动漫\"},"
            + "{\"type_id\":\"64\",\"type_name\":\"短剧\"}]";

    /** filters 大 JSON（"{\"1\":[{\"key\":\"sub\",\"name\":\"类型\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"动作片\",\"v\":\"动作\"},{\"n\":\"喜剧片\",\"v\":\"喜剧\"},{\"n\":\"爱情片\",\"v\":\"爱情\"},{\"n\":\"科幻片\",\"v\":\"科幻\"},{\"n\":\"恐怖片\",\"v\":\"恐怖\"},{\"n\":\"剧情片\",\"v\":\"剧情\"},{\"n\":\"战争片\",\"v\":\"战争\"},{\"n\":\"纪录片\",\"v\":\"纪录\"}]},{\"key\":\"area\",\"name\":\"地区\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"大陆\",\"v\":\"大陆\"},{\"n\":\"香港\",\"v\":\"香港\"},{\"n\":\"台湾\",\"v\":\"台湾\"},{\"n\":\"美国\",\"v\":\"美国\"},{\"n\":\"日本\",\"v\":\"日本\"},{\"n\":\"韩国\",\"v\":\"韩国\"},{\"n\":\"欧洲\",\"v\":\"欧洲\"}]},{\"key\":\"year\",\"name\":\"年份\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"2026\",\"v\":\"2026\"},{\"n\":\"2025\",\"v\":\"2025\"},{\"n\":\"2024\",\"v\":\"2024\"},{\"n\":\"2023\",\"v\":\"2023\"},{\"n\":\"2022\",\"v\":\"2022\"},{\"n\":\"2021\",\"v\":\"2021\"},{\"n\":\"2020\",\"v\":\"2020\"},{\"n\":\"2019\",\"v\":\"2019\"},{\"n\":\"2018\",\"v\":\"2018\"}]},{\"key\":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"最新\",\"v\":\"d_id\"},{\"n\":\"最热\",\"v\":\"hits\"},{\"n\":\"评分\",\"v\":\"score\"}]}],\"2\":[{\"key\":\"sub\",\"name\":\"类型\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"国产剧\",\"v\":\"国产剧\"},{\"n\":\"港台剧\",\"v\":\"港台剧\"},{\"n\":\"日韩剧\",\"v\":\"日韩剧\"},{\"n\":\"欧美剧\",\"v\":\"欧美剧\"},{\"n\":\"海外剧\",\"v\":\"海外剧\"}]},{\"key\":\"area\",\"name\":\"地区\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"内地\",\"v\":\"内地\"},{\"n\":\"韩国\",\"v\":\"韩国\"},{\"n\":\"香港\",\"v\":\"香港\"},{\"n\":\"台湾\",\"v\":\"台湾\"},{\"n\":\"日本\",\"v\":\"日本\"},{\"n\":\"美国\",\"v\":\"美国\"},{\"n\":\"泰国\",\"v\":\"泰国\"},{\"n\":\"英国\",\"v\":\"英国\"}]},{\"key\":\"year\",\"name\":\"年份\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"2026\",\"v\":\"2026\"},{\"n\":\"2025\",\"v\":\"2025\"},{\"n\":\"2024\",\"v\":\"2024\"},{\"n\":\"2023\",\"v\":\"2023\"},{\"n\":\"2022\",\"v\":\"2022\"},{\"n\":\"2021\",\"v\":\"2021\"},{\"n\":\"2020\",\"v\":\"2020\"},{\"n\":\"2019\",\"v\":\"2019\"},{\"n\":\"2018\",\"v\":\"2018\"}]},{\"key\":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"最新\",\"v\":\"d_id\"},{\"n\":\"最热\",\"v\":\"hits\"},{\"n\":\"评分\",\"v\":\"score\"}]}],\"3\":[{\"key\":\"area\",\"name\":\"地区\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"内地\",\"v\":\"内地\"},{\"n\":\"港台\",\"v\":\"港台\"},{\"n\":\"日韩\",\"v\":\"日韩\"},{\"n\":\"欧美\",\"v\":\"欧美\"}]},{\"key\":\"year\",\"name\":\"年份\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"2026\",\"v\":\"2026\"},{\"n\":\"2025\",\"v\":\"2025\"},{\"n\":\"2024\",\"v\":\"2024\"},{\"n\":\"2023\",\"v\":\"2023\"},{\"n\":\"2022\",\"v\":\"2022\"},{\"n\":\"2021\",\"v\":\"2021\"},{\"n\":\"2020\",\"v\":\"2020\"},{\"n\":\"2019\",\"v\":\"2019\"},{\"n\":\"2018\",\"v\":\"2018\"}]},{\"key\":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"最新\",\"v\":\"d_id\"},{\"n\":\"最热\",\"v\":\"hits\"},{\"n\":\"评分\",\"v\":\"score\"}]}],\"4\":[{\"key\":\"area\",\"name\":\"地区\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"国产\",\"v\":\"国产\"},{\"n\":\"日本\",\"v\":\"日本\"},{\"n\":\"欧美\",\"v\":\"欧美\"}]},{\"key\":\"year\",\"name\":\"年份\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"2026\",\"v\":\"2026\"},{\"n\":\"2025\",\"v\":\"2025\"},{\"n\":\"2024\",\"v\":\"2024\"},{\"n\":\"2023\",\"v\":\"2023\"},{\"n\":\"2022\",\"v\":\"2022\"},{\"n\":\"2021\",\"v\":\"2021\"},{\"n\":\"2020\",\"v\":\"2020\"},{\"n\":\"2019\",\"v\":\"2019\"},{\"n\":\"2018\",\"v\":\"2018\"},{\"n\":\"2017\",\"v\":\"2017\"},{\"n\":\"2016\",\"v\":\"2016\"}]},{\"key\":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"最新\",\"v\":\"d_id\"},{\"n\":\"最热\",\"v\":\"hits\"},{\"n\":\"评分\",\"v\":\"score\"}]}],\"64\":[{\"key\":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"最新\",\"v\":\"d_id\"},{\"n\":\"最热\",\"v\":\"hits\"},{\"n\":\"评分\",\"v\":\"score\"}]}]}"） */
    public static final String FILTERS_JSON = "{\"1\":[{\"key\":\"sub\",\"name\":\"类型\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"动作片\",\"v\":\"动作\"},{\"n\":\"喜剧片\",\"v\":\"喜剧\"},{\"n\":\"爱情片\",\"v\":\"爱情\"},{\"n\":\"科幻片\",\"v\":\"科幻\"},{\"n\":\"恐怖片\",\"v\":\"恐怖\"},{\"n\":\"剧情片\",\"v\":\"剧情\"},{\"n\":\"战争片\",\"v\":\"战争\"},{\"n\":\"纪录片\",\"v\":\"纪录\"}]},{\"key\":\"area\",\"name\":\"地区\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"大陆\",\"v\":\"大陆\"},{\"n\":\"香港\",\"v\":\"香港\"},{\"n\":\"台湾\",\"v\":\"台湾\"},{\"n\":\"美国\",\"v\":\"美国\"},{\"n\":\"日本\",\"v\":\"日本\"},{\"n\":\"韩国\",\"v\":\"韩国\"},{\"n\":\"欧洲\",\"v\":\"欧洲\"}]},{\"key\":\"year\",\"name\":\"年份\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"2026\",\"v\":\"2026\"},{\"n\":\"2025\",\"v\":\"2025\"},{\"n\":\"2024\",\"v\":\"2024\"},{\"n\":\"2023\",\"v\":\"2023\"},{\"n\":\"2022\",\"v\":\"2022\"},{\"n\":\"2021\",\"v\":\"2021\"},{\"n\":\"2020\",\"v\":\"2020\"},{\"n\":\"2019\",\"v\":\"2019\"},{\"n\":\"2018\",\"v\":\"2018\"}]},{\"key\":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"最新\",\"v\":\"d_id\"},{\"n\":\"最热\",\"v\":\"hits\"},{\"n\":\"评分\",\"v\":\"score\"}]}],\"2\":[{\"key\":\"sub\",\"name\":\"类型\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"国产剧\",\"v\":\"国产剧\"},{\"n\":\"港台剧\",\"v\":\"港台剧\"},{\"n\":\"日韩剧\",\"v\":\"日韩剧\"},{\"n\":\"欧美剧\",\"v\":\"欧美剧\"},{\"n\":\"海外剧\",\"v\":\"海外剧\"}]},{\"key\":\"area\",\"name\":\"地区\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"内地\",\"v\":\"内地\"},{\"n\":\"韩国\",\"v\":\"韩国\"},{\"n\":\"香港\",\"v\":\"香港\"},{\"n\":\"台湾\",\"v\":\"台湾\"},{\"n\":\"日本\",\"v\":\"日本\"},{\"n\":\"美国\",\"v\":\"美国\"},{\"n\":\"泰国\",\"v\":\"泰国\"},{\"n\":\"英国\",\"v\":\"英国\"}]},{\"key\":\"year\",\"name\":\"年份\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"2026\",\"v\":\"2026\"},{\"n\":\"2025\",\"v\":\"2025\"},{\"n\":\"2024\",\"v\":\"2024\"},{\"n\":\"2023\",\"v\":\"2023\"},{\"n\":\"2022\",\"v\":\"2022\"},{\"n\":\"2021\",\"v\":\"2021\"},{\"n\":\"2020\",\"v\":\"2020\"},{\"n\":\"2019\",\"v\":\"2019\"},{\"n\":\"2018\",\"v\":\"2018\"}]},{\"key\":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"最新\",\"v\":\"d_id\"},{\"n\":\"最热\",\"v\":\"hits\"},{\"n\":\"评分\",\"v\":\"score\"}]}],\"3\":[{\"key\":\"area\",\"name\":\"地区\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"内地\",\"v\":\"内地\"},{\"n\":\"港台\",\"v\":\"港台\"},{\"n\":\"日韩\",\"v\":\"日韩\"},{\"n\":\"欧美\",\"v\":\"欧美\"}]},{\"key\":\"year\",\"name\":\"年份\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"2026\",\"v\":\"2026\"},{\"n\":\"2025\",\"v\":\"2025\"},{\"n\":\"2024\",\"v\":\"2024\"},{\"n\":\"2023\",\"v\":\"2023\"},{\"n\":\"2022\",\"v\":\"2022\"},{\"n\":\"2021\",\"v\":\"2021\"},{\"n\":\"2020\",\"v\":\"2020\"},{\"n\":\"2019\",\"v\":\"2019\"},{\"n\":\"2018\",\"v\":\"2018\"}]},{\"key\":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"最新\",\"v\":\"d_id\"},{\"n\":\"最热\",\"v\":\"hits\"},{\"n\":\"评分\",\"v\":\"score\"}]}],\"4\":[{\"key\":\"area\",\"name\":\"地区\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"国产\",\"v\":\"国产\"},{\"n\":\"日本\",\"v\":\"日本\"},{\"n\":\"欧美\",\"v\":\"欧美\"}]},{\"key\":\"year\",\"name\":\"年份\",\"value\":[{\"n\":\"全部\",\"v\":\"0\"},{\"n\":\"2026\",\"v\":\"2026\"},{\"n\":\"2025\",\"v\":\"2025\"},{\"n\":\"2024\",\"v\":\"2024\"},{\"n\":\"2023\",\"v\":\"2023\"},{\"n\":\"2022\",\"v\":\"2022\"},{\"n\":\"2021\",\"v\":\"2021\"},{\"n\":\"2020\",\"v\":\"2020\"},{\"n\":\"2019\",\"v\":\"2019\"},{\"n\":\"2018\",\"v\":\"2018\"},{\"n\":\"2017\",\"v\":\"2017\"},{\"n\":\"2016\",\"v\":\"2016\"}]},{\"key\":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"最新\",\"v\":\"d_id\"},{\"n\":\"最热\",\"v\":\"hits\"},{\"n\":\"评分\",\"v\":\"score\"}]}],\"64\":[{\"key\":\"sort\",\"name\":\"排序\",\"value\":[{\"n\":\"最新\",\"v\":\"d_id\"},{\"n\":\"最热\",\"v\":\"hits\"},{\"n\":\"评分\",\"v\":\"score\"}]}]}";

    // ── 实例状态 ────────────────────────────────────────────

    private String[] sites = DEFAULT_SITES.clone();
    /** 登录 token（{@code AuthData.a}） */
    private String token = "";
    /** 用户 id（{@code AuthData.b}） */
    private String appUserId = "";
    /** 播放 Referer（上游静态 {@code f}） */
    private String playRef = DEFAULT_PLAY_REF;
    /** 可注入的图片 URL 尾缀（上游来自 {@code android.os.Build}） */
    private String uaSuffix = "";
    /** 可注入的 android_id（上游来自 {@code Settings.Secure}） */
    private String androidId = Gz360Crypto.DEVICE_ID_FALLBACK;
    /** filters 覆盖（B 类；上游读出即丢） */
    private String filtersOverride = "";
    /** 弹幕代理前缀（宿主 {@code Proxy.getUrl()} 的替身；默认空 = 不产出 danmaku） */
    private String proxyBase = "";
    /** 下行解密用的私钥；null = 用内置 P2（测试可注入自造密钥对对整个信封做往返回路） */
    private java.security.PrivateKey clientPrivateKey;

    /** 最近一次请求的 URL / 表单 —— 供测试断言，不发包 */
    private String lastUrl = "";
    private Map<String, String> lastForm = new LinkedHashMap<>();

    /** 构造 */
    public Gz360Plain() {
        super();
    }

    // ── 生命周期 ────────────────────────────────────────────

    /**
     * 解析 ext。只读 4 个键，其余全走默认值（本类没有 A 类键）。
     *
     * @param context 宿主上下文，可为 null
     * @param extend  ext 原文，可为 null
     */
    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        String s = ext.optStr("sites");
        if (!s.isEmpty()) {
            List<String> list = new ArrayList<>();
            for (String part : s.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    list.add(t);
                }
            }
            if (!list.isEmpty()) {
                this.sites = list.toArray(new String[0]);
            }
        }
        if (baseUrl.isEmpty()) {
            baseUrl = trimSlash(sites[0]);
        }
        this.token = ext.optStr("token");
        this.appUserId = ext.optStr("appUserId");
        this.uaSuffix = ext.optStr("uaSuffix");
        this.filtersOverride = ext.optStr("filters");
        Log.i(tag(), "init ok, base=" + baseUrl + ", sites=" + sites.length
                + ", token=" + (token.isEmpty() ? "无" : "有"));
    }

    private static String trimSlash(String s) {
        String v = s == null ? "" : s.trim();
        while (v.endsWith("/") && v.length() > 1) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    // ── 状态访问器（供测试与宿主） ──────────────────────────

    /** @return 当前域名表 */
    public String[] sites() {
        return sites.clone();
    }

    /** @param token 预置登录 token */
    public void setToken(String token) {
        this.token = token == null ? "" : token;
    }

    /** @return 当前 token */
    public String token() {
        return token;
    }

    /** @param id 预置 app_user_id */
    public void setAppUserId(String id) {
        this.appUserId = id == null ? "" : id;
    }

    /** @return 当前 app_user_id */
    public String appUserId() {
        return appUserId;
    }

    /** @return 播放 Referer */
    public String playRef() {
        return playRef;
    }

    /** @param ref 覆盖播放 Referer */
    public void setPlayRef(String ref) {
        this.playRef = ref == null ? DEFAULT_PLAY_REF : ref;
    }

    /** @param suffix 图片 URL 尾缀（上游由 Build.* 拼出） */
    public void setUaSuffix(String suffix) {
        this.uaSuffix = suffix == null ? "" : suffix;
    }

    /** @return 图片 URL 尾缀 */
    public String uaSuffix() {
        return uaSuffix;
    }

    /**
     * @param id android_id；null/空时回落 {@link Gz360Crypto#DEVICE_ID_FALLBACK}
     */
    public void setAndroidId(String id) {
        this.androidId = (id == null || id.isEmpty())
                ? Gz360Crypto.DEVICE_ID_FALLBACK : id;
    }

    /** @return 当前 android_id */
    public String androidId() {
        return androidId;
    }

    /** @return 最近一次请求 URL */
    public String lastUrl() {
        return lastUrl;
    }

    /** @return 最近一次请求表单（只读副本） */
    public Map<String, String> lastForm() {
        return new LinkedHashMap<>(lastForm);
    }

    /**
     * 把图片地址补上 UA 尾缀（还原 {@code vod_pic + this.c}）。
     *
     * @param pic 原始图片地址，可为 null
     * @return 拼接结果
     */
    public String picUrl(String pic) {
        return (pic == null ? "" : pic) + (uaSuffix == null ? "" : uaSuffix);
    }

    // ── 请求 ────────────────────────────────────────────────

    /**
     * 8 个固定请求头（{@code referer} 用当前 base）。
     *
     * @return 保序 header map
     */
    public Map<String, String> headers() {
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put("lang", HDR_LANG);
        h.put("User-Agent", HDR_UA);
        h.put("Content-Type", HDR_CONTENT_TYPE);
        h.put("code", HDR_CODE);
        h.put("version", HDR_VERSION);
        h.put("packagename", HDR_PACKAGE);
        h.put("ver", HDR_VER);
        h.put("api-ver", HDR_API_VER);
        h.put("referer", baseUrl);
        return h;
    }

    /**
     * 发一次表单 POST（可被测试覆写）。
     *
     * @param path 接口路径或完整 URL
     * @param form 表单字段
     * @return 响应体；失败为空串
     */
    public String fetchPost(String path, Map<String, String> form) {
        String p = path == null ? "" : path;
        String url = p.startsWith("http") ? p : baseUrl + p;
        record(url, form);
        return http.post(url, formEncode(form), headers());
    }

    /**
     * 记录一次请求（供测试子类复用，保证覆写 {@code fetchPost} 时
     * {@link #lastUrl()} / {@link #lastForm()} 仍然有值）。
     *
     * @param url  完整 URL
     * @param form 表单字段，可为 null
     */
    protected void record(String url, Map<String, String> form) {
        this.lastUrl = url;
        this.lastForm = form == null
                ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(form);
    }

    /**
     * 表单编码（{@code k=v&…}，值与键都做 URL 编码）。
     *
     * @param form 表单，可为 null
     * @return 编码后的请求体
     */
    public static String formEncode(Map<String, String> form) {
        if (form == null || form.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(Http.encode(e.getKey())).append('=')
              .append(Http.encode(e.getValue() == null ? "" : e.getValue()));
        }
        return sb.toString();
    }

    /**
     * 一次业务请求：打信封 → POST → 解信封。
     *
     * @param path 路径
     * @param biz  业务字段，可为 null
     * @return 明文 JSONObject；失败为空对象
     */
    public JSONObject request(String path, Map<String, String> biz) {
        String[] kv = newEnvelopeKeyIv();
        LinkedHashMap<String, String> form = Gz360Crypto.buildRequest(
                biz, token, "", null, kv[0], kv[1], String.valueOf(nowSec()));
        String body = fetchPost(path, form);
        return decodeResponse(body);
    }

    /**
     * 生成本次信封的 AES key/iv（每次请求都不同 —— 上游 {@code i()} 里两次 {@code k()}）。
     *
     * <p>抽成方法是为了给测试留缝：测试子类返回固定值后，就能
     * 解出自己记录下来的 {@code request_key} 来断言业务参数。
     *
     * @return {@code {key, iv}}，各 16 字符
     */
    protected String[] newEnvelopeKeyIv() {
        return new String[]{Gz360Crypto.randomKey16(), Gz360Crypto.randomKey16()};
    }

    /**
     * 解密响应信封。
     *
     * @param body 响应体，可为 null
     * @return 明文 JSONObject；失败为空对象
     */
    public JSONObject decodeResponse(String body) {
        return Gz360Crypto.decodeEnvelope(body, clientPrivateKey);
    }

    /**
     * 注入下行解密私钥 —— 仅测试/诊断用。
     *
     * <p>内置私钥 P2 的公钥不在包内（在服务器侧），因此<b>无法</b>用内置私钥
     * 自造一个能被自己解开的响应；要做信封往返回路测试必须注入自造密钥对。
     *
     * @param key 私钥；null 表示恢复使用内置 P2
     */
    public void setClientPrivateKey(java.security.PrivateKey key) {
        this.clientPrivateKey = key;
    }

    /**
     * 当前秒级时间戳。
     *
     * @return 秒
     */
    public static long nowSec() {
        return System.currentTimeMillis() / 1000L;
    }

    // ── 登录 ────────────────────────────────────────────────

    /**
     * 保证有可用登录态：有 token 走 refresh，否则 signIn（403 → signUp）。
     *
     * @return 是否拿到 token
     */
    public boolean ensureLogin() {
        if (!token.isEmpty()) {
            JSONObject r = request("/App/Authentication/Authenticator/refresh",
                    new HashMap<String, String>());
            if (applyAuth(r)) {
                return true;
            }
            Log.w(tag(), "refresh 失败，改走注册/登录");
        }
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("new_key", Gz360Crypto.sha1Upper(java.util.UUID.randomUUID().toString()));
        m.put("old_key", deviceKey());
        JSONObject r = postFormJson("/App/Authentication/Device/signIn", m);
        if (r.optInt("code") == 403) {
            m.put("recommend_id", "");
            r = postFormJson("/App/Authentication/Device/signUp", m);
        }
        if (applyAuth(r)) {
            return true;
        }
        return false;
    }

    /**
     * signIn/signUp 走的是「同一套信封」，但上游在这两处直接读 {@code code} 再解密，
     * 因此这里单独保留一个能拿到 {@code code} 的入口。
     *
     * @param path 路径
     * @param biz  业务字段
     * @return 响应 JSON（已解密）
     */
    public JSONObject postFormJson(String path, Map<String, String> biz) {
        return request(path, biz);
    }

    /**
     * 从登录响应里取 token / app_user_id（上游 {@code f()} 后读 token）。
     *
     * @param r 解密后的响应
     * @return 是否拿到非空 token
     */
    public boolean applyAuth(JSONObject r) {
        if (r == null) {
            return false;
        }
        String t = r.optString("token", "");
        if (t.isEmpty()) {
            return false;
        }
        this.token = t;
        this.appUserId = r.optString("app_user_id", "");
        return true;
    }

    /**
     * 设备指纹 Base64（{@code old_key} 字段）。
     *
     * @return Base64 文本
     */
    public String deviceKey() {
        return Gz360Crypto.deviceBlobB64(
                (int) nowSec(),
                new Random().nextInt(),
                androidId == null ? 0 : androidId.hashCode());
    }

    /**
     * 取用户信息并回填 {@code playRef}（上游 {@code /App/UserInfo/getUserInfo}）。
     *
     * @return 是否更新了 playRef
     */
    public boolean refreshUserInfo() {
        JSONObject r = request("/App/UserInfo/getUserInfo", new HashMap<String, String>());
        if (r.has("playRef")) {
            String v = r.optString("playRef", "");
            if (!v.isEmpty()) {
                this.playRef = v;
                return true;
            }
        }
        return false;
    }

    // ── 数据方法 ────────────────────────────────────────────

    /**
     * 首页：静态 5 级分类 + （可选）filters。
     *
     * <p><b>刻意不输出 {@code list}</b>：上游就是这样 —— 首页推荐交给
     * {@link #homeVideoContent()}，宿主见不到 {@code list} 才会去调它。
     * 补一个空 {@code list} 会让推荐位永远为空，因此这里保留上游形状。
     *
     * @param filter 是否带 filters
     * @return 首页 JSON
     */
    @Override
    public String homeContent(boolean filter) {
        JSONObject root = new JSONObject();
        try {
            root.put("class", new JSONArray(CLASS_JSON));
            if (filter) {
                root.put("filters", new JSONObject(
                        filtersOverride.isEmpty() ? FILTERS_JSON : filtersOverride));
            }
            return root.toString();
        } catch (Throwable e) {
            Log.e(tag(), "homeContent failed", e);
            return emptyResponse();
        }
    }

    /**
     * 首页推荐：{@code /App/IndexList/choiceList?pid=1}。
     *
     * @return 标准列表 JSON
     */
    @Override
    public String homeVideoContent() {
        JSONObject r = request("/App/IndexList/choiceList",
                Collections.singletonMap("pid", "1"));
        List<Vod> list = new ArrayList<>();
        for (JSONObject o : objects(r.optJSONArray("list"))) {
            list.add(vodFromChoice(o));
        }
        return VodBuilder.create().addAll(list).searchContent();
    }

    /**
     * 分类翻页。
     *
     * @param tid    分类 id
     * @param pg     页码
     * @param filter 忽略
     * @param extend 筛选（area/year/sort/sub）
     * @return 标准分类 JSON
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        LinkedHashMap<String, String> biz = new LinkedHashMap<>();
        biz.put("area", extParam(extend, "area", "0"));
        biz.put("year", extParam(extend, "year", "0"));
        biz.put("pageSize", PAGE_SIZE);
        biz.put("sort", extParam(extend, "sort", "d_id"));
        biz.put("sub", extParam(extend, "sub", "0"));
        biz.put("page", String.valueOf(page));
        biz.put("tid", tid == null ? "" : tid);
        JSONObject r = request("/App/IndexList/indexList", biz);
        return VodBuilder.create()
                .addAll(vodsFromList(r.optJSONArray("list")))
                .page(page)
                .categoryContent();
    }

    /**
     * 详情：playInfo + Vurl/show 两次请求。
     *
     * @param ids 影片 id 列表
     * @return 标准详情 JSON
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (id.isEmpty()) {
            return emptyResponse();
        }
        LinkedHashMap<String, String> info = new LinkedHashMap<>();
        info.put("token_id", appUserId);
        info.put("vod_id", id);
        info.put("mobile_time", String.valueOf(nowSec()));
        info.put("token", token);
        LinkedHashMap<String, String> vurl = new LinkedHashMap<>();
        vurl.put("vurl_cloud_id", "2");
        vurl.put("vod_d_id", id);

        JSONObject playInfo = request("/App/IndexPlay/playInfo", info);
        JSONObject show = request("/App/Resource/Vurl/show", vurl);
        JSONObject vi = playInfo.optJSONObject("vodInfo");
        if (vi == null) {
            Log.w(tag(), "详情无 vodInfo, id=" + id);
            return emptyResponse();
        }
        Vod v = new Vod(id, vi.optString("vod_name", ""))
                .pic(picUrl(vi.optString("vod_pic", "")))
                .remarks(vi.optString("new_continue", ""))
                .year(vi.optString("vod_addtime", ""))
                .area(vi.optString("vod_area", ""))
                .actor(vi.optString("vod_actor", ""))
                .director(vi.optString("vod_director", ""))
                .content(vi.optString("vod_use_content", ""));

        List<String> eps = new ArrayList<>();
        for (JSONObject o : objects(show.optJSONArray("list"))) {
            eps.add(episodeOf(o, eps.size() + 1));
        }
        v.playFrom(PLAY_FROM).playUrl(join(eps, "#"));
        return VodBuilder.detail(v).detailContent();
    }

    /**
     * 搜索（{@code /App/Index/findMoreVod}）。
     *
     * @param key   关键词
     * @param quick 忽略
     * @return 标准搜索 JSON
     */
    @Override
    public String searchContent(String key, boolean quick) {
        LinkedHashMap<String, String> biz = new LinkedHashMap<>();
        biz.put("keywords", key == null ? "" : key);
        biz.put("order_val", "1");
        JSONObject r = request("/App/Index/findMoreVod", biz);
        return VodBuilder.create()
                .addAll(vodsFromList(r.optJSONArray("list")))
                .searchContent();
    }

    /**
     * 播放解析：id 是 {@code Base64("k=v&…")}，解出参数后请求 {@code showOne}。
     *
     * @param flag     忽略
     * @param id       Base64 参数串
     * @param vipFlags 忽略
     * @return 标准播放 JSON
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        Map<String, String> biz = decodeId(id);
        if (biz.isEmpty()) {
            Log.w(tag(), "播放 id 解码为空: " + id);
            return emptyResponse();
        }
        JSONObject r = request("/App/Resource/VurlDetail/showOne", biz);
        String url = r.optString("url", "");
        try {
            JSONObject o = new JSONObject();
            o.put("parse", 0);
            o.put("playUrl", "");
            o.put("url", url);
            JSONObject h = new JSONObject();
            h.put("User-Agent", PLAY_UA);
            h.put("Referer", playRef);
            o.put("header", h.toString());
            String danmaku = danmakuUrl();
            if (!danmaku.isEmpty()) {
                o.put("danmaku", danmaku);
            }
            return o.toString();
        } catch (Throwable e) {
            Log.e(tag(), "playerContent failed", e);
            return emptyResponse();
        }
    }

    // ── 内部工具 ────────────────────────────────────────────

    /**
     * 还原 {@code playerContent} 里的 id 解码：Base64 → {@code k=v&…} → map，
     * 其中 {@code vod_d_id} 改名为 {@code vod_id}。
     *
     * @param id Base64 串，可为 null
     * @return 参数表；解码失败为空 map
     */
    public static Map<String, String> decodeId(String id) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        if (id == null || id.trim().isEmpty()) {
            return m;
        }
        byte[] raw;
        try {
            raw = java.util.Base64.getMimeDecoder().decode(id.trim());
        } catch (Throwable e) {
            return m;
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        for (String seg : text.split("&")) {
            String[] p = seg.split("=", 2);
            if (p.length != 2) {
                continue;
            }
            String k = "vod_d_id".equals(p[0]) ? "vod_id" : p[0];
            m.put(k, p[1]);
        }
        return m;
    }

    /**
     * choiceList 的一行 → Vod（字段名与 indexList 不同：{@code c_name}/{@code c_pic}）。
     *
     * @param o 源对象，可为 null
     * @return 非 null Vod
     */
    public Vod vodFromChoice(JSONObject o) {
        if (o == null) {
            return new Vod();
        }
        String remarks = o.optString("new_continue", "");
        if (remarks.isEmpty()) {
            remarks = "评分:" + o.optString("vod_douban_score", "");
        }
        return new Vod(o.optString("vod_id", ""), o.optString("c_name", ""))
                .pic(picUrl(o.optString("c_pic", "")))
                .remarks(remarks);
    }

    /**
     * indexList 的一行 → Vod，含「伦理/成人/三级」过滤。
     *
     * @param a 源数组，可为 null
     * @return 非 null 列表
     */
    public List<Vod> vodsFromList(JSONArray a) {
        List<Vod> out = new ArrayList<>();
        for (JSONObject o : objects(a)) {
            String tags = o.optString("tags", "");
            if (blocked(tags)) {
                continue;
            }
            String remarks = o.optString("new_continue", "");
            if (remarks.isEmpty()) {
                remarks = "评分:" + o.optString("vod_scroe", "");
            }
            out.add(new Vod(o.optString("vod_id", ""), o.optString("vod_name", ""))
                    .pic(picUrl(o.optString("vod_pic", "")))
                    .remarks(remarks));
        }
        return out;
    }

    /**
     * 是否命中内容过滤词。
     *
     * @param tags 标签串，可为 null
     * @return true 表示应剔除
     */
    public static boolean blocked(String tags) {
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        for (String w : BLOCK_WORDS) {
            if (tags.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一条线路 → {@code 标题$Base64(param)}。
     *
     * <p>修复缺陷 1：只在<b>真正命中且 param 非空</b>时记清晰度键，
     * 否则标签里的 {@code [NP]} 会写成最后尝试的候选（上游 bug）。
     *
     * @param o        线路对象
     * @param fallback 无标题时用的序号
     * @return 集串
     */
    public String episodeOf(JSONObject o, int fallback) {
        if (o == null) {
            return "";
        }
        JSONObject play = o.optJSONObject("play");
        String hit = "";
        String param = "";
        if (play != null) {
            for (String res : RESOLUTIONS) {
                if (!play.has(res)) {
                    continue;
                }
                JSONObject r = play.optJSONObject(res);
                String p = r == null ? "" : r.optString("param", "");
                if (!p.isEmpty()) {
                    hit = res;
                    param = p;
                    break;
                }
            }
        }
        String title = o.optString("title", "");
        if (title.isEmpty()) {
            title = String.valueOf(fallback);
        }
        if (!hit.isEmpty()) {
            title = title + " [" + hit + "P]";
        }
        return title + "$" + java.util.Base64.getEncoder()
                .encodeToString(param.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 弹幕地址（宿主未注入 proxyBase 时为空串）。
     *
     * @return 弹幕 URL
     */
    public String danmakuUrl() {
        if (proxyBase == null || proxyBase.isEmpty()) {
            return "";
        }
        return proxyBase + "?do=" + DANMAKU_ACTION;
    }

    /**
     * 注入弹幕代理前缀（宿主 {@code Proxy.getUrl()} 的替身）。
     *
     * @param base 前缀，可为 null
     */
    public void setProxyBase(String base) {
        this.proxyBase = base == null ? "" : base.trim();
    }

    /** @return 弹幕代理前缀 */
    public String proxyBase() {
        return proxyBase;
    }

    /**
     * 数组 → 只保留 JSONObject。
     *
     * @param a 源数组，可为 null
     * @return 非 null 列表
     */
    public static List<JSONObject> objects(JSONArray a) {
        List<JSONObject> out = new ArrayList<>();
        if (a == null) {
            return out;
        }
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) {
                out.add(o);
            }
        }
        return out;
    }

    /**
     * join（空列表 → 空串）。
     *
     * @param list 元素列表，可为 null
     * @param sep  分隔符
     * @return 拼接结果
     */
    public static String join(List<String> list, String sep) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            String s = list.get(i);
            if (s != null) {
                sb.append(s);
            }
        }
        return sb.toString();
    }
}
