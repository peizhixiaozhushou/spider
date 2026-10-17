package com.example.tvbox.plain.frame;

import android.content.Context;
import android.util.Log;

import com.example.tvbox.common.AesCbc;
import com.example.tvbox.common.Http;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 框架初始化器（Init）—— <b>★框架支撑类，非内容源★</b>。
 *
 * <p><b>框架支撑类，非内容源</b> —— 不提供 {@code homeContent} 内容数据，仅供框架初始化/配置加载使用。
 * 本类<b>不继承 {@code Spider}、不是任何形式的爬虫</b>：它没有 {@code homeContent} /
 * {@code categoryContent} / {@code searchContent} 等契约方法，上游从未把它注册成源。
 * 它做的是 App 冷启动阶段的基础设施：
 * <ol>
 *   <li><b>防二次打包三连</b>：核对包名 / App 名称白名单、核对签名 DN 黑名单；</li>
 *   <li><b>默认配置落盘</b>：{@code /config.json} 缺失或空则写默认值，否则补齐缺失键并归一化 {@code version}；</li>
 *   <li><b>权限与网络前置检查</b>：全文件访问权限 / 5 项运行时权限 / 联网状态；</li>
 *   <li><b>启动 Go 代理二进制</b>：<i>按 CPU ABI 选 pvideo-* → 下载 zip → MD5 双校验 → 解压 → chmod → exec</i>；</li>
 *   <li><b>派生 4 个后台任务</b>（夸克/UC cookie 检查、百度时间戳、迅雷时效检查）与 1 个版本检查线程；</li>
 *   <li>提供全局线程池（{@code execute/post/run}）与 Activity 反射工具供兄弟类使用。</li>
 * </ol>
 *
 * <h2>★ 内置密文与 key == iv（真实协议特征，勿"顺手修正"）</h2>
 * 上游用 {@code C0296a.a(密文, key, iv)} 解密三个内置常量。
 * <b>三个调用点的 key 与 iv 都是同一个字符串 {@code "1234123412341234"}（16 字节）</b>
 * —— 这不是笔误，是上游的真实写法：{@link #KEY} 同时充当 CBC 的 key 与 IV。
 * 解密结果（本文件用 JCE 实解得到，见 {@link #decryptEmbedded}）：
 * <table border="1">
 *   <tr><th>常量</th><th>明文</th></tr>
 *   <tr><td>{@link #PACKAGE_WHITELIST_CIPHER}</td><td>54 个包名的逗号串（com.fongmi.android.tv,…）</td></tr>
 *   <tr><td>{@link #APP_NAME_WHITELIST_CIPHER}</td><td>35 个 App 名的逗号串（影视,影視,OK影视,…）</td></tr>
 *   <tr><td>{@link #LOAD_FAILED_CIPHER}</td><td>{@code 加载失败}</td></tr>
 * </table>
 * 上游算法串是 {@code AES/CBC/PKCS7Padding}；<b>PKCS7 在桌面 JCE 不存在，本重写统一用
 * {@link AesCbc} 的 {@code PKCS5Padding}</b>（AES 分组 16 字节，两者语义等价）。
 *
 * <h2>宿主能力与可测性</h2>
 * 上游把 Android 调用写在业务流里（{@code ActivityThread} 反射、{@code Runtime.exec}、
 * {@code Environment}、{@code ConnectivityManager}、{@code Process.killProcess}…），
 * 导致本类完全无法单测。本重写把这些抽成 {@link Host}：纯逻辑
 * （解密、白名单判定、MD5、zip 解包、ABI 选型、linker 探测、配置合并、启动编排）
 * 100% 可离线验证，宿主副作用由注入实现记录。
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>Go 代理启动逻辑被整段复制两份</b>：{@code Init$1}（direct exec）与
 *       {@code Init$2}（linker 启动）两个匿名 Runnable 里各写了一份
 *       <b>逐行相同</b>的"下载→解压→MD5 校验→执行"代码（合计约 200 行）。
 *       已抽成 {@link #ensureAndLaunch(boolean)} + {@link #launch(File, boolean)}；
 *       行为保持等价（targetSdk&lt;29 时两条分支依旧都会跑），
 *       并把"同一文件被并发下载/执行"标记为上游缺陷；
 *       钉住断言：{@code InitTest#goProxySequence()}。</li>
 *   <li><b>MD5 校验失败后漏删 zip</b>：上游在"解压后 MD5 不匹配"分支里删了
 *       {@code file} 与 {@code file2}，但"setExecutable 失败"分支只删了 {@code file}，
 *       zip 残留。本重写统一清理并注释。</li>
 *   <li><b>{@code saveConfig} 的 catch 分支覆盖用户配置</b>：文件存在但 JSON 损坏时，
 *       上游直接<b>整文件覆写默认值</b>（用户配置全丢）。本重写保持同样行为（避免与上游
 *       产物不一致），但在 javadoc 与测试里显式记录该风险；
 *       钉住断言：{@code InitTest#saveConfigBrokenJson()}。</li>
 *   <li><b>版本号字段被无条件改写</b>：{@code saveConfig} 里
 *       {@code if (!has("version") || !get("version").equals(asString)) addProperty("version", asString)}
 *       —— 任何与默认值不同的 {@code version} 都会被改回 {@code "2.0"}，
 *       用户手改的版本号会被静默吃掉。已按原样保留并注明。</li>
 *   <li><b>writer 用平台默认编码</b>：上游 {@code k.c(File,String)} 用
 *       {@code str.getBytes()}（无参）写文件，中文 Windows 下会是 GBK；
 *       本重写固定 UTF-8（协议内均为 ASCII/UTF-8，行为等价）。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Init.java}<br>
 * 关键辅助证据（baksmali 反汇编 {@code re/spider.dex}）:
 * <ul>
 *   <li>{@code merge.m.k.b(String)/d(String)/a(File)/c(File,String)} —— 两个文件根：
 *       外部存储 {@code /sdcard/TVBox}（{@code k.b()}）与 {@code <filesDir>/Pizazz}（{@code k.d()}），
 *       且 {@code k.c} 写文件后会执行 {@code chmod 777}。</li>
 *   <li>{@code merge.m.I.f()} —— 即 {@link ConfigPlain#detectHostActionSupport()} 的来源，
 *       反射找宿主 {@code Spider.action} 方法。</li>
 *   <li>{@code merge.A.aa/ab/acc/ad} —— 4 个后台任务的具体动作（见 {@link Task}）。</li>
 * </ul>
 
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>本类不是内容源，不存在"未实现的内容方法"</b>：它连 TVBox 的
 *       {@code Spider} 都不继承（{@code final} 工具类，只做初始化/权限/自杀/更新），
 *       所以"没有 homeContent"是设计而不是缺口。宿主 {@code Init} 的内容方法同样
 *       只是满足契约的空壳。</li>
 *   <li><b>Activity 生命周期分派只做到"返回意图"</b>：{@code interceptActivityStart()}
 *       给出该被拦截的 Activity 与原因；真正 {@code finish()} 由宿主
 *       （{@code WindowRef} 接口）执行 —— 纯 JVM 下没有 Activity 可拦截。</li>
 *   <li><b>真机专属分支</b>：{@code isExternalStorageManager}/{@code hasPermission}/
 *       {@code requestAllFilesAccess}/{@code killApp}/{@code startFloatBall} 都是
 *       {@code Host} 接口的职责；{@link DesktopHost} 里 {@code killApp} 只记日志
 *       （桌面 JVM 上自杀没有意义）。</li>
 *   <li><b>四个启动任务</b>（Quark/UC/bdtime/Xunlei）在纯 JVM 下不执行：
 *       {@code startTask} 由宿主决定；{@link Task} 只是枚举与顺序声明。</li>
 *   <li><b>未还原</b>：代理二进制清单里 4 个 md5 与 6 个 linker 候选路径都按
 *       宿主类原文固化，但<b>没有实测二进制样本</b>；{@code download} 只校验
 *       HTTP 200 与目录写入，不做签名校验（上游也没有）。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/Init.java}（逐行核对：{@code init} 的
 * 白名单校验/权限分支/自杀与更新延时、{@code saveConfig} 的 19 键默认配置、
 * 两段逐字重复的 Go 代理启动匿名 Runnable 等）。
*/
public final class InitPlain {

    private static final String TAG = "InitPlain";

    // ── 常量（全部来自上游硬编码） ───────────────────────────

    /** 当前版本号（上游 {@code public static String v = "10.14"}） */
    public static final String VERSION = "10.14";

    /** 版本检查地址（上游 {@code UP.run()} 内硬编码） */
    public static final String VERSION_URL = "https://9877.kstore.space/version.txt";

    /**
     * 内置密文的 key —— <b>同时被当作 IV 使用</b>。
     *
     * <p>上游三个调用点均为 {@code C0296a.a(密文, "1234123412341234", "1234123412341234")}：
     * key 与 iv 是同一个 16 字节常量。这是<b>真实协议特征，不是笔误</b>，
     * 改动它会导致三个内置常量全部解不开（见类注释）。
     */
    public static final String KEY = "1234123412341234";

    /** 包名白名单密文（AES/CBC/PKCS7，key=iv={@link #KEY}，密文以 Base64 存储） */
    public static final String PACKAGE_WHITELIST_CIPHER =
            "CiH7mT8Z5SsWtxVyK0yesTJ7LhJ9/GNlP/hj8RNMTpFQmv7rLktoFkd+dOJadCK6WnE30Llr1P3Rg+X/zW/sG2H8OhMg9xeqBY00aX3"
                    + "/fqmjZo37hlqYML8Dh6Vn3zCAv0rIbM1zH/nBlIUCXnPR0DsZzmNsDmiHYSVjer1g+T5NS2E2FuQIenTd7sfW5C8T75RvPpRewj8"
                    + "NSBaXcHCEyux4yqsq/d6d/fKdWxx/WBDY+0pLnR4zdrKzXensid9NcpBSRPZWEXdp/FB6TX/szXUZ4Kq1GlOMGEVDVhpsRl4YYpyl"
                    + "60gXtbuX1na4iCJ07Xcf4tRrzJbMeNVcRHmDgjwSGoX1JTqgtkzGtOHNgysY3Hg1s07fIYCPO/UMw2G7UyJxnJ0wnZZ4QBuZPMsp"
                    + "928fbqQgFNdt1F5cUej8+SxeWgE5k/iMMnZFHeDhvKyr9qsJgnoPUN6JKN7AiaZKlgF8GvKWhtUC5E3rQ3Hbiy0XjYq4HdpmcMS67"
                    + "E2kkE2YGtrQiYzp7HyVOVR0g1BjcjzNvMDGURgPyUWFGd04G+E8wgZUgk7Gq4UKdY1Vp5Z/wS9wloNx7wVhWwLE6gi/XMvt3sOUVe"
                    + "WVaCoCNUdh3b2c6TJcktFayuhMGxrDRpVWJVnaBmMyt8qWM4/ZHQAgMNzXQOE5OBUcFmiMQzi/70wwaL3VqFRodwnq5Y+zQnhvZz"
                    + "T34AkNKRt5P2kY7jhp5X5kB2W24uYzSO9M2ZeW3NY3heGbk2+vfedlIiDx1HJcCnaMDaMmgswxLi6giuuuYi6WCOxHPLRkAiOgfL"
                    + "+prM1xpSWqFAuAtzBnKSXdYR5tfZCWzyTsdO8GjXbZhTayp0d5hyLD/ddCJ3IJIcCorcB85Eg8st1uNv/1UydaDmxZcMq7j2+nDF"
                    + "26GkvoUxISpqZjsulwZhPZhTvd6FjbKQbnThyFP2CaTHKipbEdAzeOymozmvCxRreaAqEk3Ma+M+vZTjEYVjjAePLHylb0YrUn1"
                    + "bPGt/CRhFjLu7DEYG6+cbWeTpkfHHz0+mQEC44fQpbdt9U5TRV00GIoiKNGEsVP8RTuJNm3HY7jMTiTyqVfzleeRLfoAW6ZbpQE"
                    + "kzqbFzryyGJGA30jsAFoaZFfSuh5TYI9y3X0dDa8UjXcE52sAFO9z5SGZmo+jbrx58eQxgLOnq05JCAmJI6VK8s+7EEgDZOy8qa2"
                    + "0BIvMk7bNstqqkut3XxSS5BqO7I/AQAgpx4P/qtNpiBsfZM01B0Aei0pBVQIIIEFsx67R7Qt35oErsTkOJFFDK3oukVYTBF7wxyG"
                    + "zpZTGxDq+JLT0VfNzoTzwRqRkdacThXxMr9rRX+q6zuHBNtv0j98/hkMWi2NdtdCMFFjCiOD3kJjUgeDEfwJcE4zprVGo7+FjU8"
                    + "kGSrP";

    /** App 名称白名单密文（同一 key/iv） */
    public static final String APP_NAME_WHITELIST_CIPHER =
            "emnJecdMz87MFx2I1QRel3AWTZJq9oCjI2N1+VXdtg8Sk1QzVU6k6SAbDzSPzr6QlTzxmL7EAgTkQpnE7sQUu8Ql6s6EbRY6mdSC"
                    + "ze9UItqN5DWhUFfEqZEBqFKFhxbPMb5y902ledskxLdpm2jDBCQYs+MgqpfWBFfwFdvPgMvaLPNHgEZAvDe6wrqDqEFheOYH7PL"
                    + "0paYjzHwK3ScS5tXLGLByC/i/icFR0zm05PXOhkvS+c4FDi0cEuwSa+rKv8ZAEjnQJRLiwwY8Y0c9MKSlLOVsq/TK8Ps60FVEaEw"
                    + "9tQrQHUd+EqAPZcsKbgkCSM2AShoxb7lKPX5ZeNtUh5NvIrhDULf8Pg1SncPjOVcHSmNWXtHEUAsoJZKz6AdT";

    /** "加载失败"提示密文（同一 key/iv） */
    public static final String LOAD_FAILED_CIPHER = "6ODi1b8xyfKfZpaErEEE9w==";

    /** 默认配置（上游 {@code saveConfig()} 里的字面量，出现 3 次） */
    public static final String DEFAULT_CONFIG_JSON =
            "{\"version\":\"2.0\",\"update\":\"关闭\",\"danmuColor\":\"默认\",\"danmuSearch\":\"开启\","
                    + "\"aliQuality\":\"阿里原画\",\"quarkQuality\":\"夸克无限\",\"ucQuality\":\"UC无限\","
                    + "\"baiduQuality\":\"百度无限\",\"123Quality\":\"123无限\",\"proxyMode\":\"Go多线程\","
                    + "\"pansouUrl\":\"https://so.252035.xyz\",\"panView\":\"文件夹视图\","
                    + "\"panOrder\":\"百度,夸克,UC,迅雷,光鸭,天翼,123,阿里,移动\","
                    + "\"homePage\":\"猜你喜欢,热门电影,热播剧集,热门动漫,热播综艺,电影筛选,电视筛选,电影榜单,电视剧榜单\","
                    + "\"aliThread\":\"64\",\"quarkThread\":\"16\",\"ucThread\":\"自动\",\"baiduThread\":\"16\","
                    + "\"xunleiThread\":\"10\"}";

    /** Go 代理二进制清单（URL + MD5，上游硬编码 JSON） */
    public static final String PROXY_BINARIES_JSON =
            "{\"pvideo-x86_64\":\"https://oss-v1.wangmeipo.cn/236/pvideo-x86_64_1013.zip\","
                    + "\"pvideo-x86\":\"https://oss-v1.wangmeipo.cn/236/pvideo-x86_1013.zip\","
                    + "\"pvideo-armeabi-v7a\":\"https://oss-v1.wangmeipo.cn/236/pvideo-armeabi-v7a_1013.zip\","
                    + "\"pvideo-arm64-v8a\":\"https://oss-v1.wangmeipo.cn/236/pvideo-arm64-v8a_1013.zip\","
                    + "\"pvideo-x86_64.md5\":\"b61e576eeb148b2fa85524b7ec9dc088\","
                    + "\"pvideo-x86.md5\":\"15ff4bf639e42a23687fecc7c16a813a\","
                    + "\"pvideo-armeabi-v7a.md5\":\"f8da80ad673ac15ff0745a4e8e3fd915\","
                    + "\"pvideo-arm64-v8a.md5\":\"14711967d54ab08700eeb7d7b42655f6\"}";

    /** 代理启动参数（上游两处硬编码 {@code -port 5266}） */
    public static final String PROXY_PORT_ARG = "-port";
    public static final String PROXY_PORT = "5266";

    /** 上游按序遍历的 linker 候选路径（顺序即优先级） */
    public static final String[] LINKER_CANDIDATES = {
            "/apex/com.android.runtime/bin/linker64",
            "/system/bin/linker64",
            "/system/bin/bootstrap/linker64",
            "/apex/com.android.runtime/bin/linker",
            "/system/bin/linker",
            "/system/bin/bootstrap/linker",
    };

    /** 签名 DN 黑名单关键词（命中即判"二次打包"） */
    public static final String[] SIGNATURE_DN_BLACKLIST = {
            "1613616e64726f696440616e64726f69642e636f6d", // hex("android@android.com")
            "CN=np,OU=np,O=np",
            "CN=Android,OU=Android,O=Android",
            "CN=apktool",
            "CN=MT Manager",
            "CN=AndroResGuard",
    };

    /** 运行时权限清单（上游 init 前置检查） */
    public static final String[] REQUIRED_PERMISSIONS = {
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.INTERNET",
    };

    /** 权限请求码（上游硬编码 100） */
    public static final int PERMISSION_REQUEST_CODE = 100;

    /** 首次点击与再次点击的最小间隔（{@code clearFileWithDoubleCheck} 的兄弟实现同样用 500ms） */
    public static final long UPDATE_CHECK_DELAY_MS = 2000L;

    /** 命中防篡改门后杀死进程前的等待（上游 5000ms） */
    public static final long KILL_DELAY_MS = 5000L;

    /** 配置目录/外部目录（对齐上游 {@code k.d()}/{@code k.b()}） */
    public static final String CONFIG_PATH = "/config.json";

    // ── 宿主能力抽象 ─────────────────────────────────────────

    /**
     * 上游 4 个后台网络任务（{@code Init.init} 里的 4 个 {@code new Thread(...).start()}）。
     *
     * <p>具体动作在别的类里，本类只负责编排；枚举保留上游语义便于审计：
     * <ul>
     *   <li>{@link #QUARK_COOKIE_CHECK} —— {@code merge.A.aa.a}：夸克 cookie 非空时调
     *       {@code QuarkPan.checkquarkcookie()/deletetime()/checktime()}</li>
     *   <li>{@link #UC_COOKIE_CHECK} —— {@code merge.A.ab.b}：UC cookie 非空时调
     *       {@code UCPan.checkuccookie()/deletetime()/checktime()}</li>
     *   <li>{@link #BAIDU_TIME_STAMP} —— {@code merge.A.acc.a}：把
     *       {@code yyyyMMdd}(Asia/Shanghai) 写进偏好键 {@code bdtime}，并做天数差判断</li>
     *   <li>{@link #XUNLEI_TIME_CHECK} —— {@code merge.A.ad.d}：迅雷 token 非空时
     *       {@code X.Companion.set(...)} 后调 {@code XunleiPan.checktime()}</li>
     * </ul>
     */
    public enum Task {
        /** 夸克 cookie 检查 */
        QUARK_COOKIE_CHECK,
        /** UC cookie 检查 */
        UC_COOKIE_CHECK,
        /** 百度时间戳写入 */
        BAIDU_TIME_STAMP,
        /** 迅雷时效检查 */
        XUNLEI_TIME_CHECK,
    }

    /**
     * 一个宿主 Activity 的替身（上游 {@code ActivityThread.mActivities} 里的
     * {@code ActivityClientRecord}：字段 {@code paused} 与 {@code activity}）。
     */
    public interface WindowRef {
        /** @return Activity 类名（上游 {@code getComponentName().getClassName()}） */
        String className();

        /** @return 上游 {@code ActivityClientRecord.paused} */
        boolean paused();

        /** 关闭该 Activity（上游 {@code activity.finish()}） */
        void finish();
    }

    /** 宿主能力接口 —— 标注了上游对应实现，便于对照审计 */
    public interface Host {
        /**
         * 读配置/登录态文件。
         *
         * @param name 逻辑路径
         * @return 文本；失败为空串
         */
        String read(String name);

        /**
         * 写配置/登录态文件。
         *
         * @param name 逻辑路径
         * @param text 内容
         */
        void write(String name, String text);

        /** @return 当前 SDK 版本（上游 {@code Build.VERSION.SDK_INT}） */
        int sdkInt();

        /** @return 应用 targetSdkVersion */
        int targetSdkVersion();

        /** @return 应用包名（上游 {@code context.getPackageName()}） */
        String packageName();

        /** @return 应用显示名（上游 {@code packageManager.getApplicationLabel(...)}） */
        String appLabel();

        /** @return 首个签名证书的 SubjectDN；无签名返回空串 */
        String signatureDn();

        /** @return 支持的 ABI 列表（上游 {@code Build.SUPPORTED_ABIS}） */
        String[] supportedAbis();

        /** @return 是否已获得全文件访问（上游 {@code Environment.isExternalStorageManager()}） */
        boolean isExternalStorageManager();

        /**
         * 查询运行时权限。
         *
         * @param permission 权限名
         * @return 是否已授予
         */
        boolean hasPermission(String permission);

        /**
         * 请求运行时权限。
         *
         * @param permissions 权限数组
         * @param requestCode 请求码
         */
        void requestPermissions(String[] permissions, int requestCode);

        /**
         * 跳系统设置申请"所有文件访问权限"。
         *
         * @param pkg 包名
         */
        void requestAllFilesAccess(String pkg);

        /** @return 当前网络是否可用（上游 {@code ConnectivityManager.getActiveNetworkInfo().isConnected()}） */
        boolean isNetworkConnected();

        /**
         * 气泡提示。
         *
         * @param message 文本
         */
        void toast(String message);

        /**
         * 杀死自身进程（上游 {@code Process.killProcess(Process.myPid())}）。
         * <p>桌面实现只记录、不真杀。
         */
        void killApp();

        /**
         * 投递到主线程（上游 {@code Handler.post}）。
         *
         * @param task 任务
         */
        void post(Runnable task);

        /**
         * 延时投递到主线程（上游 {@code Handler.postDelayed}）。
         *
         * @param task  任务
         * @param delay 延时毫秒
         */
        void postDelayed(Runnable task, long delay);

        /**
         * 执行 shell 命令（上游 {@code Runtime.getRuntime().exec(String)}）。
         *
         * @param command 完整命令
         */
        void exec(String command);

        /**
         * 以 argv 形式执行（上游 linker 启动路径用的 {@code exec(String[])}）。
         *
         * @param argv 参数数组
         */
        void exec(String[] argv);

        /**
         * 判断路径是否可执行（上游 {@code new File(p).canExecute()}）。
         *
         * @param path 路径
         * @return 是否可执行
         */
        boolean canExecute(String path);

        /**
         * 外部存储根（上游 {@code k.b()} = {@code /sdcard/TVBox}）。
         *
         * @return 目录
         */
        File externalDir();

        /**
         * 配置目录（上游 {@code k.d()} = {@code <filesDir>/Pizazz}）。
         *
         * @return 目录
         */
        File configDir();

        /** @return 当前 Activity 列表（上游反射 {@code ActivityThread.mActivities}） */
        List<WindowRef> activities();

        /** 启动悬浮球（上游 {@code merge.Z.C0281a.a().b(context)}） */
        void startFloatBall();

        /**
         * 启动一个后台任务（上游 4 个 {@code new Thread(...).start()}）。
         *
         * @param task 任务
         */
        void startTask(Task task);

        /**
         * 异步执行任意任务（上游全局 5 线程池 {@code Init.execute}）。
         *
         * @param task 任务
         */
        void runAsync(Runnable task);
    }

    /**
     * 默认宿主实现 —— 桌面/纯 JVM 环境下降级。
     *
     * <p>文件 IO 是真的（两个根目录都落在 {@code java.io.tmpdir/tvbox-config} 下），
     * shell / 提示 / 杀进程等 Android 专属动作降级为日志（{@link #killApp()} 绝不真杀进程）。
     */
    public static class DesktopHost implements Host {

        private final ConfigPlain.FileHost files = new ConfigPlain.FileHost();
        private final ExecutorService pool = Executors.newFixedThreadPool(5);

        @Override
        public String read(String name) {
            return files.read(name);
        }

        @Override
        public void write(String name, String text) {
            files.write(name, text);
        }

        @Override
        public int sdkInt() {
            return 23;
        }

        @Override
        public int targetSdkVersion() {
            return 29;
        }

        @Override
        public String packageName() {
            return "";
        }

        @Override
        public String appLabel() {
            return "";
        }

        @Override
        public String signatureDn() {
            return "";
        }

        @Override
        public String[] supportedAbis() {
            String arch = System.getProperty("os.arch", "");
            return new String[]{arch};
        }

        @Override
        public boolean isExternalStorageManager() {
            return false;
        }

        @Override
        public boolean hasPermission(String permission) {
            return false;
        }

        @Override
        public void requestPermissions(String[] permissions, int requestCode) {
            Log.i(TAG, "requestPermissions(" + requestCode + "): " + Arrays.toString(permissions));
        }

        @Override
        public void requestAllFilesAccess(String pkg) {
            Log.i(TAG, "requestAllFilesAccess: " + pkg);
        }

        @Override
        public boolean isNetworkConnected() {
            return true;
        }

        @Override
        public void toast(String message) {
            Log.i(TAG, "toast: " + message);
        }

        @Override
        public void killApp() {
            // 桌面环境绝不真杀进程 —— 只记录，避免测试/开发时把自己杀掉
            Log.w(TAG, "killApp 被调用（桌面宿主降级为日志，不会真的结束进程）");
        }

        @Override
        public void post(Runnable task) {
            runAsync(task);
        }

        @Override
        public void postDelayed(final Runnable task, long delay) {
            if (task == null) {
                return;
            }
            final long d = Math.max(0, delay);
            runAsync(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(d);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    task.run();
                }
            });
        }

        @Override
        public void exec(String command) {
            Log.i(TAG, "exec: " + command);
        }

        @Override
        public void exec(String[] argv) {
            Log.i(TAG, "exec: " + Arrays.toString(argv));
        }

        @Override
        public boolean canExecute(String path) {
            return new File(path).canExecute();
        }

        @Override
        public File externalDir() {
            return files.externalRoot();
        }

        @Override
        public File configDir() {
            return files.configRoot();
        }

        @Override
        public List<WindowRef> activities() {
            return new ArrayList<>();
        }

        @Override
        public void startFloatBall() {
            Log.i(TAG, "startFloatBall (无宿主，降级为日志)");
        }

        @Override
        public void startTask(Task task) {
            Log.i(TAG, "startTask: " + task);
        }

        @Override
        public void runAsync(Runnable task) {
            if (task != null) {
                pool.execute(task);
            }
        }

        /** @return 后台线程池（排障用） */
        public ExecutorService pool() {
            return pool;
        }
    }

    /** 当前宿主实现（本类的方法全是 static，故用静态字段持有） */
    private static volatile Host host = new DesktopHost();

    /** 全局单例（上游 {@code Loader.a} 双检锁写法） */
    private static volatile InitPlain instance;

    /** 主线程投递器（上游 {@code new Handler(Looper.getMainLooper())}） */
    private final android.os.Handler handler;

    /**
     * 构造：初始化主线程 Handler。
     */
    public InitPlain() {
        android.os.Handler h = null;
        try {
            h = new android.os.Handler(android.os.Looper.getMainLooper());
        } catch (Throwable e) {
            // 非 Android 环境没有 Looper —— handler 保持 null，post 走 Host
            h = null;
        }
        this.handler = h;
    }

    /**
     * 注入宿主实现。
     *
     * @param h 实现；null 时恢复默认
     */
    public static void setHost(Host h) {
        host = h == null ? new DesktopHost() : h;
    }

    /**
     * 取当前宿主实现。
     *
     * @return 永不为 null
     */
    public static Host host() {
        Host h = host;
        if (h == null) {
            h = new DesktopHost();
            host = h;
        }
        return h;
    }

    /**
     * 取单例（上游 {@code Init.get()}）。
     *
     * @return 单例
     */
    public static InitPlain get() {
        InitPlain i = instance;
        if (i == null) {
            synchronized (InitPlain.class) {
                i = instance;
                if (i == null) {
                    i = new InitPlain();
                    instance = i;
                }
            }
        }
        return i;
    }

    // ── 内置常量解密（固定向量，key == iv） ───────────────────

    /**
     * 解密内置常量 —— 上游 {@code C0296a.a(密文, KEY, KEY)} 的等价物。
     *
     * <p>算法：AES/CBC/PKCS5Padding（上游写作 PKCS7），key 与 IV 均为
     * {@code "1234123412341234"} 的 UTF-8 字节，密文为 Base64。
     *
     * @param cipherBase64 Base64 密文，可为 null
     * @return 明文；失败返回空串（不抛异常）
     */
    public static String decryptEmbedded(String cipherBase64) {
        if (cipherBase64 == null || cipherBase64.isEmpty()) {
            return "";
        }
        byte[] key = KEY.getBytes(StandardCharsets.UTF_8);
        String plain = AesCbc.decryptBase64ToString(cipherBase64, key, key);
        if (plain == null) {
            Log.e(TAG, "内置常量解密失败（key/iv 必须同为 " + KEY + "）");
            return "";
        }
        return plain;
    }

    /**
     * 包名白名单明文（54 项，逗号分隔）。
     *
     * @return 明文；失败为空串
     */
    public static String packageWhitelist() {
        return decryptEmbedded(PACKAGE_WHITELIST_CIPHER);
    }

    /**
     * App 名称白名单明文（35 项，逗号分隔）。
     *
     * @return 明文；失败为空串
     */
    public static String appNameWhitelist() {
        return decryptEmbedded(APP_NAME_WHITELIST_CIPHER);
    }

    /**
     * 防篡改门命中时的提示语（解出来是 {@code 加载失败}）。
     *
     * @return 明文；失败为空串
     */
    public static String loadFailedMessage() {
        return decryptEmbedded(LOAD_FAILED_CIPHER);
    }

    /**
     * 逗号串 → 列表（去空项、不 trim 之外的处理）。
     *
     * @param csv 逗号串，可为 null
     * @return 非 null 列表
     */
    public static List<String> whitelist(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isEmpty()) {
            return out;
        }
        for (String s : csv.split(",")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 包名是否在白名单内 —— 上游 {@code a(Context)} 的判定式
     * {@code (","+白名单+",").contains(","+pkg+",")} 的等价实现。
     *
     * @param pkg 包名，可为 null
     * @return true 表示命中
     */
    public static boolean isPackageAllowed(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        return whitelist(packageWhitelist()).contains(pkg);
    }

    /**
     * App 显示名是否在白名单内 —— 上游 {@code b(Context)}。
     *
     * @param label 应用名，可为 null
     * @return true 表示命中
     */
    public static boolean isAppNameAllowed(String label) {
        if (label == null || label.isEmpty()) {
            return false;
        }
        return whitelist(appNameWhitelist()).contains(label);
    }

    /**
     * 签名 DN 是否命中黑名单 —— 上游 {@code c(Context)}。
     *
     * <p>注意是子串匹配（{@code name.contains(关键词)}），不是相等。
     *
     * @param dn SubjectDN，可为 null
     * @return true 表示命中（疑似二次打包）
     */
    public static boolean isSignatureBlacklisted(String dn) {
        if (dn == null || dn.isEmpty()) {
            return false;
        }
        for (String k : SIGNATURE_DN_BLACKLIST) {
            if (dn.contains(k)) {
                return true;
            }
        }
        return false;
    }

    // ── 线程 / Handler 门面（上游 post/run/execute） ──────────

    /**
     * 提交到全局线程池（上游 5 线程）。
     *
     * @param task 任务，可为 null（忽略）
     */
    public static void execute(Runnable task) {
        if (task != null) {
            host().runAsync(task);
        }
    }

    /**
     * 投递到主线程（上游 {@code post}，等价 {@code run}）。
     *
     * @param task 任务
     */
    public static void post(Runnable task) {
        run(task);
    }

    /**
     * 投递到主线程。
     *
     * @param task 任务，可为 null（忽略）
     */
    public static void run(Runnable task) {
        if (task == null) {
            return;
        }
        android.os.Handler h = get().handler;
        if (h != null) {
            h.post(task);
        } else {
            host().post(task);
        }
    }

    /**
     * 延时投递到主线程。
     *
     * @param task  任务
     * @param delay 延时毫秒
     */
    public static void run(Runnable task, int delay) {
        if (task == null) {
            return;
        }
        android.os.Handler h = get().handler;
        if (h != null) {
            h.postDelayed(task, delay);
        } else {
            host().postDelayed(task, delay);
        }
    }

    // ── 文件摘要 ─────────────────────────────────────────────

    /**
     * 文件 MD5（小写 hex）—— 上游 {@code d(File)}，流式 8192 字节。
     *
     * @param file 文件，可为 null
     * @return 32 位小写 hex；失败返回空串
     */
    public static String md5(File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        FileInputStream in = null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            in = new FileInputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return hex(md.digest());
        } catch (Throwable e) {
            Log.e(TAG, "md5 failed: " + file, e);
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * 字节数组 MD5。
     *
     * @param data 数据，可为 null
     * @return 32 位小写 hex；失败返回空串
     */
    public static String md5(byte[] data) {
        if (data == null) {
            return "";
        }
        try {
            return hex(java.security.MessageDigest.getInstance("MD5").digest(data));
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 字节数组 → 小写 hex —— 上游写法
     * {@code Integer.toString((b & 255) + 256, 16).substring(1)} 的等价实现。
     *
     * @param data 数据
     * @return hex 串
     */
    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(Integer.toString((b & 255) + 256, 16).substring(1));
        }
        return sb.toString();
    }

    // ── 配置落盘 ─────────────────────────────────────────────

    /**
     * 默认配置文本（上游 {@code saveConfig()} 的字面量）。
     *
     * @return JSON 文本
     */
    public static String defaultConfig() {
        return DEFAULT_CONFIG_JSON;
    }

    /**
     * 合并默认配置 —— 上游 {@code saveConfig()} 的核心逻辑（抽出来便于单测）。
     *
     * <p>规则与上游逐条对齐：
     * <ol>
     *   <li>已有文本为空 → 直接返回默认文本；</li>
     *   <li>逐键补缺：默认里有的键、已有里没有的，补进去（<b>已有值一律不覆盖</b>）；</li>
     *   <li>{@code version} 无条件归一化为默认值（上游写法如此，会吃掉用户手改的版本号）；</li>
     *   <li>已有文本不是合法 JSON → 直接返回默认文本（上游 catch 分支同样整文件覆写）。</li>
     * </ol>
     *
     * @param existing 已有配置文本，可为 null/空
     * @param defaults 默认配置文本（通常传 {@link #DEFAULT_CONFIG_JSON}）
     * @return 合并后的 JSON 文本
     */
    public static String mergeConfig(String existing, String defaults) {
        String dup = defaults == null || defaults.isEmpty() ? DEFAULT_CONFIG_JSON : defaults;
        if (existing == null || existing.isEmpty()) {
            return dup;
        }
        try {
            JSONObject def = new JSONObject(dup);
            JSONObject cur = new JSONObject(existing);
            String defVersion = def.optString("version", "");
            java.util.Iterator<String> it = def.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!cur.has(k)) {
                    cur.put(k, def.opt(k));
                }
            }
            if (!cur.has("version") || !defVersion.equals(cur.optString("version", ""))) {
                cur.put("version", defVersion);
            }
            return cur.toString();
        } catch (Throwable e) {
            Log.w(TAG, "配置 JSON 损坏，整文件回写默认值（上游同样行为）");
            return dup;
        }
    }

    /**
     * 确保配置文件存在且键齐全 —— 上游 {@code saveConfig()}。
     *
     * <p>文件不存在 / 长度为 0 / 内容为空 → 写默认值；
     * 否则合并补缺并写回。
     */
    public static void saveConfig() {
        Host h = host();
        String cur = h.read(CONFIG_PATH);
        if (cur.isEmpty()) {
            h.write(CONFIG_PATH, DEFAULT_CONFIG_JSON);
            return;
        }
        h.write(CONFIG_PATH, mergeConfig(cur, DEFAULT_CONFIG_JSON));
    }

    // ── 权限 ─────────────────────────────────────────────────

    /**
     * 权限前置检查 —— 上游 {@code checkPermission()}。
     *
     * <p>两个分支：
     * <ul>
     *   <li>targetSdk ≥ 30 且 SDK ≥ 30：只要有 {@code MANAGE_APP_ALL_FILES_ACCESS_PERMISSION}
     *       的跳转，不申请运行时权限；</li>
     *   <li>否则：逐个检查 {@link #REQUIRED_PERMISSIONS}，有缺才一次性请求（请求码 100）。</li>
     * </ul>
     * 没有 Activity 时直接返回（上游同样先 {@code activity != null}）。
     *
     * @return true 表示发起过"所有文件访问"跳转；false 表示走了传统权限或无事可做
     */
    public static boolean checkPermission() {
        try {
            Host h = host();
            WindowRef w = getActivity();
            if (w == null) {
                return false;
            }
            if (h.sdkInt() < 23) {
                return false;
            }
            if (h.targetSdkVersion() >= 30 && h.sdkInt() >= 30) {
                if (h.isExternalStorageManager()) {
                    return false;
                }
                h.requestAllFilesAccess(h.packageName());
                return true;
            }
            List<String> missing = missingPermissions();
            if (missing.isEmpty()) {
                return false;
            }
            h.requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            return false;
        } catch (Throwable e) {
            Log.e(TAG, "checkPermission failed", e);
            return false;
        }
    }

    /**
     * 计算缺失的运行时权限。
     *
     * @return 缺失的权限名（顺序同 {@link #REQUIRED_PERMISSIONS}）
     */
    public static List<String> missingPermissions() {
        List<String> out = new ArrayList<>();
        Host h = host();
        for (String p : REQUIRED_PERMISSIONS) {
            if (!h.hasPermission(p)) {
                out.add(p);
            }
        }
        return out;
    }

    // ── Activity 反射工具 ────────────────────────────────────

    /**
     * 取当前"未暂停"的第一个 Activity —— 上游 {@code getActivity()}。
     *
     * <p>上游反射 {@code ActivityThread.mActivities}，遍历第一个 {@code paused == false}
     * 的记录并返回其 {@code activity} 字段。
     *
     * @return 匹配的 Activity 替身；无则 null
     */
    public static WindowRef getActivity() {
        try {
            for (WindowRef w : host().activities()) {
                if (w != null && !w.paused()) {
                    return w;
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "getActivity failed", e);
        }
        return null;
    }

    /**
     * 取 Activity 列表里的<b>第一个</b>（不看 paused）—— 上游 {@code getConfigActivity()}。
     *
     * @return 第一个 Activity 替身；列表为空则 null
     */
    public static WindowRef getConfigActivity() {
        try {
            List<WindowRef> list = host().activities();
            return list == null || list.isEmpty() ? null : list.get(0);
        } catch (Throwable e) {
            Log.e(TAG, "getConfigActivity failed", e);
            return null;
        }
    }

    /**
     * 关闭误开的播放/详情页 —— 上游 {@code interceptActivityStart()}。
     *
     * <p>规则：Activity 类名<b>包含</b> {@code Video} 或 {@code Detail} 就 {@code finish()}。
     * 这是配置中心用来"弹掉"点按设置项时宿主顺手拉起的页面。
     */
    public static void interceptActivityStart() {
        try {
            for (WindowRef w : host().activities()) {
                if (w == null) {
                    continue;
                }
                String cn = w.className();
                if (cn == null) {
                    continue;
                }
                if (cn.contains("Video") || cn.contains("Detail")) {
                    w.finish();
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "interceptActivityStart failed", e);
        }
    }

    // ── 下载 / 落盘 / 解包 ───────────────────────────────────

    /**
     * 打开下载流 —— 上游 {@code i(String url)}。
     *
     * <p>上游用 okhttp：非 200 抛
     * {@code IOException("Download failed with HTTP code: N | URL: " + response)}。
     * 本重写保留同样的失败语义。<b>这是二进制下载</b>，不能用只返回字符串的
     * {@link Http#get(String)}（UTF-8 解码会破坏 ELF 字节），因此这里直接用
     * {@code HttpURLConnection}，与 {@link Http.UrlConnectionTransport} 同一实现路线。
     *
     * <p>测试下 {@code Http.setOfflineForTest(true)} 会直接抛 IOException ——
     * 保证零网络；测试通过覆写本方法注入字节流。
     *
     * @param url 下载地址
     * @return 字节流（调用方负责关闭）
     * @throws IOException 非 200、连接失败或测试离线模式
     */
    public static InputStream download(String url) throws IOException {
        if (streamProvider != null) {
            return streamProvider.open(url);
        }
        if (Http.isOfflineForTest()) {
            throw new IOException("offline-for-test: " + url);
        }
        if (url == null || url.isEmpty()) {
            throw new IOException("Network error while downloading: empty url");
        }
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("Download failed with HTTP code: " + code + " | URL: " + url);
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return new ByteArrayInputStream(bos.toByteArray());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Network error while downloading: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
    }

    /**
     * 把输入流写进文件 —— 上游 {@code write(File, InputStream)}。
     *
     * <p>语义：先删旧文件、createNewFile，再以 65536 缓冲写，最后双双关闭。
     *
     * @param file 目标文件
     * @param in   输入流（本方法会关闭它，与上游一致）
     * @throws IOException 写入失败
     */
    public static void write(File file, InputStream in) throws IOException {
        if (file == null) {
            throw new IOException("write: null file");
        }
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "旧文件删除失败: " + file);
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        if (!file.createNewFile() && !file.isFile()) {
            throw new IOException("createNewFile failed: " + file);
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            byte[] buf = new byte[65536];
            int n;
            while (in != null && (n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            closeQuietly(out);
            closeQuietly(in);
        }
    }

    /**
     * 解压 zip 到目标文件的同级目录 —— 上游 {@code unzip(File zip, File target)}。
     *
     * <p>注意上游语义：条目路径是相对 {@code target.getParent()} 展开的
     * （因为压缩包内是裸文件名，而 target 是解压后的可执行文件路径）。
     *
     * @param zip    压缩包
     * @param target 目标文件（解压后同名文件应出现在它旁边）
     * @throws IOException 读取失败
     */
    public static void unzip(File zip, File target) throws IOException {
        if (zip == null || target == null) {
            throw new IOException("unzip: null argument");
        }
        File outRoot = target.getParentFile();
        if (outRoot != null) {
            outRoot.mkdirs();
        }
        byte[] buf = new byte[4096];
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        try {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                File f = new File(outRoot, entry.getName());
                if (entry.isDirectory()) {
                    f.mkdirs();
                } else {
                    File p = f.getParentFile();
                    if (p != null) {
                        p.mkdirs();
                    }
                    FileOutputStream fos = new FileOutputStream(f);
                    try {
                        int n;
                        while ((n = zin.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                        fos.flush();
                    } finally {
                        closeQuietly(fos);
                    }
                }
                zin.closeEntry();
            }
        } finally {
            closeQuietly(zin);
        }
    }

    // ── Go 代理：选型 / 清单 / linker ─────────────────────────

    /**
     * 按 CPU ABI 选择代理二进制名 —— 上游 {@code Init$1/Init$2} 里逐字重复的判断链。
     *
     * <p>上游把 {@code Arrays.toString(Build.SUPPORTED_ABIS)} 拼成字符串再
     * {@code contains} 判断，顺序敏感：<b>先 x86_64 → 再 x86 → 再 arm64 → 兜底 armeabi-v7a</b>。
     * 本实现保留同一顺序与同一"子串包含"语义。
     *
     * @param abis ABI 列表，可为 null
     * @return 形如 {@code pvideo-arm64-v8a}
     */
    public static String selectProxyBinary(String[] abis) {
        String s = abis == null ? "null" : Arrays.toString(abis);
        if (s.contains("x86_64")) {
            return "pvideo-x86_64";
        }
        if (s.contains("x86")) {
            return "pvideo-x86";
        }
        if (s.contains("arm64")) {
            return "pvideo-arm64-v8a";
        }
        return "pvideo-armeabi-v7a";
    }

    /**
     * 取代理二进制的下载地址。
     *
     * @param name 二进制名，如 {@code pvideo-arm64-v8a}
     * @return URL；未知名字返回空串
     */
    public static String proxyUrl(String name) {
        return proxyEntry(name);
    }

    /**
     * 取代理二进制的期望 MD5。
     *
     * @param name 二进制名
     * @return 32 位小写 hex；未知名字返回空串
     */
    public static String proxyMd5(String name) {
        return proxyEntry(name == null ? null : name + ".md5");
    }

    /**
     * 当前生效的代理清单（默认 {@link #PROXY_BINARIES_JSON}）。
     *
     * <p>上游把清单写死在代码里，换代理版本必须重新打包；本重写允许覆盖，
     * 便于服务端下发新清单与离线验证（也用于测试注入自造的 zip/md5）。
     */
    private static volatile String manifest = PROXY_BINARIES_JSON;

    /**
     * 覆盖代理清单。
     *
     * @param json 清单 JSON；null/空恢复内置清单
     */
    public static void setManifest(String json) {
        manifest = (json == null || json.isEmpty()) ? PROXY_BINARIES_JSON : json;
    }

    /**
     * 当前代理清单文本。
     *
     * @return JSON 文本
     */
    public static String manifest() {
        String m = manifest;
        return m == null ? PROXY_BINARIES_JSON : m;
    }

    /** 从当前清单里取一项 */
    private static String proxyEntry(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        try {
            return new JSONObject(manifest()).optString(key, "");
        } catch (Throwable e) {
            Log.e(TAG, "代理清单解析失败", e);
            return "";
        }
    }

    /**
     * 是否走"直接 exec"启动路径 —— 上游 {@code startGoProxy} 的首个条件
     * {@code targetSdkVersion < 29}。
     *
     * @param targetSdk targetSdkVersion
     * @return true 表示会走第一条（direct exec）分支
     */
    public static boolean shouldDirectLaunch(int targetSdk) {
        return targetSdk < 29;
    }

    /**
     * 按上游顺序找可用的 linker。
     *
     * @return linker 路径；全都不可执行时为空串（上游此时退化为直接 exec）
     */
    public static String linkerPath() {
        Host h = host();
        for (String p : LINKER_CANDIDATES) {
            try {
                if (h.canExecute(p)) {
                    return p;
                }
            } catch (Throwable e) {
                // 单个候选探测失败不影响其余
            }
        }
        return "";
    }

    // ── Go 代理：编排 ────────────────────────────────────────

    /**
     * 启动 Go 代理 —— 上游 {@code startGoProxy(Context)}。
     *
     * <p>上游结构（<b>两条分支都会起线程</b>，第一条仅在 targetSdk &lt; 29 时）：
     * <pre>
     *   if (targetSdkVersion &lt; 29) new Thread(Init$1).start();   // direct exec
     *   new Thread(Init$2).start();                              // linker 启动
     * </pre>
     * 两个 Runnable 体逐行相同（下载→解压→MD5→执行），差异只在最后 {@code launch} 的方式
     * —— 因此旧系统上同一份二进制会被<b>下载/执行两次</b>。本重写保留该结构
     * （见类注释缺陷 1），但把公共部分抽到 {@link #ensureAndLaunch(boolean)}。
     *
     * @param context 宿主上下文（仅用于触发时机，实际能力走 {@link Host}）
     */
    public static void startGoProxy(Context context) {
        int target = host().targetSdkVersion();
        if (shouldDirectLaunch(target)) {
            host().runAsync(new Runnable() {
                @Override
                public void run() {
                    ensureAndLaunch(false);
                }
            });
        }
        host().runAsync(new Runnable() {
            @Override
            public void run() {
                ensureAndLaunch(true);
            }
        });
    }

    /**
     * 确保本地代理二进制可用并执行 —— 上游两个匿名 Runnable 的公共体。
     *
     * <p>流程：按 ABI 选名 → 若本地文件 MD5 已匹配则直接执行 → 否则删旧文件/旧包、
     * 下载 zip、解包、校验解压后 MD5、{@code setExecutable(true)}、执行。
     * 校验失败按分支清理（见类注释缺陷 2）。
     *
     * @param useLinker true 走 linker 启动路径，false 走 direct exec
     * @return true 表示最终执行了二进制
     */
    public static boolean ensureAndLaunch(boolean useLinker) {
        Host h = host();
        String name = selectProxyBinary(h.supportedAbis());
        String url = proxyUrl(name);
        String expectMd5 = proxyMd5(name);
        File dir = h.externalDir();
        File file = new File(dir, name);
        File zip = new File(dir, name + ".zip");
        Log.i(TAG, "开始检查本地文件: " + file + " expectMd5=" + expectMd5);

        if (file.isFile()) {
            String local = md5(file);
            if (!expectMd5.isEmpty() && expectMd5.equals(local)) {
                Log.i(TAG, "本地文件 MD5 校验通过，直接执行");
                launch(file, useLinker);
                return true;
            }
        }
        if (file.exists()) {
            file.delete();
        }
        if (zip.exists()) {
            zip.delete();
        }
        try {
            write(zip, download(url));
            unzip(zip, file);
            deleteQuietly(zip);
            String got = md5(file);
            if (!expectMd5.isEmpty() && !expectMd5.equals(got)) {
                Log.w(TAG, "解压后 MD5 不匹配，清理: " + got);
                deleteQuietly(file);
                return false;
            }
            if (!file.setExecutable(true)) {
                Log.w(TAG, "设置可执行权限失败，清理");
                deleteQuietly(file);
                deleteQuietly(zip); // 上游此分支漏删 zip —— 见类注释缺陷 2
                return false;
            }
            launch(file, useLinker);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "下载/解压/校验失败: " + e.getMessage(), e);
            deleteQuietly(zip);
            deleteQuietly(file);
            return false;
        }
    }

    /**
     * 执行代理二进制 —— 上游两条启动路径的差异点。
     *
     * <p>direct 路径（{@code Init$1.a}）：{@code pkill -f 名字} → sleep 200 →
     * {@code chmod 755 路径} → {@code 路径 -port 5266}（<b>整串 shell 命令</b>）；
     * linker 路径（{@code Init$2.a}）：{@code pkill -f 名字} → sleep 200 →
     * {@code chmod 755 路径} → sleep 200 → 按 {@link #LINKER_CANDIDATES} 找 linker →
     * {@code exec([linker, 路径, "-port", "5266"])}（<b>argv 形式</b>），
     * 找不到 linker 时退化为 {@code exec([路径, "-port", "5266"])}。
     *
     * @param file      二进制文件
     * @param useLinker 是否走 linker 路径
     */
    public static void launch(File file, boolean useLinker) {
        Host h = host();
        String path = file.getAbsolutePath();
        Log.i(TAG, "正在启动代理程序... useLinker=" + useLinker);
        h.exec("pkill -f " + file.getName());
        sleepQuietly(200L);
        h.exec("chmod 755 " + path);
        if (!useLinker) {
            h.exec(path + " " + PROXY_PORT_ARG + " " + PROXY_PORT);
            Log.i(TAG, "代理程序已启动");
            return;
        }
        sleepQuietly(200L);
        String linker = linkerPath();
        if (!linker.isEmpty()) {
            h.exec(new String[]{linker, path, PROXY_PORT_ARG, PROXY_PORT});
        } else {
            h.exec(new String[]{path, PROXY_PORT_ARG, PROXY_PORT});
        }
        Log.i(TAG, "代理程序已启动");
    }

    // ── 版本检查 ─────────────────────────────────────────────

    /**
     * 版本检查判定 —— 上游 {@code UP.run()} 的纯逻辑部分（抽出来便于单测）。
     *
     * <p>条件（全部满足才提示）：配置可用且 {@code update == "开启"}、
     * 远端文本非空、且与 {@link #VERSION} 不等。命中时 toast
     * {@code "检测到新版本：{remote}\n(当前版本：{VERSION})"}。
     *
     * @param configJson 配置原文，可为 null
     * @param remoteText 远端版本文件内容，可为 null
     * @return 需要提示时返回远端版本（已 trim）；否则空串
     */
    public static String checkUpdate(String configJson, String remoteText) {
        try {
            if (configJson == null || configJson.isEmpty()) {
                return "";
            }
            if (!"开启".equals(new JSONObject(configJson).optString("update", ""))) {
                return "";
            }
            if (remoteText == null || remoteText.isEmpty() || remoteText.equals(VERSION)) {
                return "";
            }
            host().toast("检测到新版本：" + remoteText + "\n(当前版本：" + VERSION + ")");
            return remoteText.trim();
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 取远端文本（默认走公共 {@link Http}）。
     *
     * <p>上游把这一步写在 {@code merge.p036k.b.l(url, new HashMap())} 里；
     * 测试可注入 {@link #setFetcher} 或让 {@code Http} 保持离线（返回空串 → 不提示）。
     *
     * @param url 地址
     * @return 文本；失败为空串
     */
    public static String fetchText(String url) {
        if (fetcher != null) {
            try {
                return fetcher.fetch(url);
            } catch (Throwable e) {
                return "";
            }
        }
        try {
            return Http.create().timeout(5000, 5000).retry(0).get(url);
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 启动版本检查线程 —— 上游 {@code new Thread(new UP(context)).start()}：
     * 先 sleep 2000ms 再检查。
     */
    public static void startUpdateCheck() {
        host().runAsync(new Runnable() {
            @Override
            public void run() {
                sleepQuietly(UPDATE_CHECK_DELAY_MS);
                checkUpdate(host().read(CONFIG_PATH), fetchText(VERSION_URL));
            }
        });
    }

    // ── 总入口 ───────────────────────────────────────────────

    /**
     * 冷启动初始化 —— 上游 {@code init(Context)} 的完整还原。
     *
     * <p>防篡改门（顺序即上游顺序）：
     * <pre>
     *   if (!包名白名单)      → log("包名不匹配，疑似二次打包")
     *   else if (!App名白名单) → log("App名称不匹配，疑似二次打包")
     *   else if (签名DN黑名单) → log("签名DN命中黑名单，疑似二次打包")
     *   else                  → 正常启动（本方法返回 true）
     *   命中任一 → 绑定 Application、toast(解密出的"加载失败")、
     *              5 秒后 killProcess(myPid())   ← 自毁
     * </pre>
     *
     * <p>正常启动流程：绑定 Application → {@link #saveConfig()} → {@link Host#startFloatBall()} →
     * 联网权限检查 → 网络可用性检查 → {@link #startGoProxy}(context) →
     * 4 个 {@link Task} → {@link #startUpdateCheck()}。
     *
     * @param context 宿主上下文，可为 null（null 时按包名空串处理 → 命中第一道门）
     * @return true 表示正常启动；false 表示命中防篡改自毁分支
     */
    public static boolean init(Context context) {
        Host h = host();
        String pkg = h.packageName();
        String label = h.appLabel();
        boolean gatePass = false;
        if (!isPackageAllowed(pkg)) {
            Log.w(TAG, "包名不匹配，疑似二次打包: " + pkg);
        } else if (!isAppNameAllowed(label)) {
            Log.w(TAG, "App名称不匹配，疑似二次打包: " + label);
        } else if (isSignatureBlacklisted(h.signatureDn())) {
            Log.w(TAG, "签名DN命中黑名单，疑似二次打包: " + h.signatureDn());
        } else {
            gatePass = true;
        }
        if (!gatePass) {
            selfDestruct();
            return false;
        }
        try {
            saveConfig();
            h.startFloatBall();
            if (!h.hasPermission("android.permission.INTERNET")) {
                Log.w(TAG, "缺少INTERNET权限，无法执行网络操作");
                return true;
            }
            if (!h.isNetworkConnected()) {
                h.toast("网络不可用，请检查连接");
                return true;
            }
            Log.i(TAG, "网络连接正常，启动所有网络任务");
            startGoProxy(context);
            for (Task t : Task.values()) {
                h.startTask(t);
            }
            startUpdateCheck();
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "初始化异常", e);
            return true;
        }
    }

    /**
     * 防篡改自毁 —— 上游 catch 分支：
     * 绑定 Application、toast 解密出的"加载失败"、5 秒后杀进程。
     *
     * <p>{@link Host#killApp()} 在桌面宿主下只记录日志，不会真的结束进程。
     */
    public static void selfDestruct() {
        try {
            host().toast(loadFailedMessage());
            host().postDelayed(new Runnable() {
                @Override
                public void run() {
                    host().killApp();
                }
            }, KILL_DELAY_MS);
        } catch (Throwable e) {
            Log.e(TAG, "selfDestruct failed", e);
        }
    }

    // ── 测试/宿主注入点 ──────────────────────────────────────

    /**
     * 下载流提供者 —— 上游这里由 okhttp {@code Response.body().byteStream()} 提供；
     * 本重写的静态 API 无法被子类覆写，故提供显式注入点，让"下载→校验→解包→执行"
     * 全链路可以完全离线地被断言。
     */
    public interface StreamProvider {
        /**
         * 打开下载流。
         *
         * @param url 地址
         * @return 字节流
         * @throws IOException 打开失败
         */
        InputStream open(String url) throws IOException;
    }

    /** 文本获取提供者 —— 用于在测试中注入远端版本号 */
    public interface Fetcher {
        /**
         * 取文本。
         *
         * @param url 地址
         * @return 文本
         */
        String fetch(String url);
    }

    /** 当前下载流提供者；null 表示走真实 {@code HttpURLConnection} */
    private static volatile StreamProvider streamProvider;
    /** 当前文本获取提供者；null 表示走真实 {@link Http} */
    private static volatile Fetcher fetcher;

    /**
     * 注入下载流提供者（null 恢复真实网络）。
     *
     * @param p 提供者
     */
    public static void setStreamProvider(StreamProvider p) {
        streamProvider = p;
    }

    /**
     * 注入文本获取提供者（null 恢复真实网络）。
     *
     * @param f 提供者
     */
    public static void setFetcher(Fetcher f) {
        fetcher = f;
    }

    // ── 小工具 ───────────────────────────────────────────────

    /**
     * 睡眠，忽略中断。
     *
     * @param ms 毫秒
     */
    static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 安静关闭可关闭对象。
     *
     * @param c 对象，可为 null
     */
    static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 安静删除 */
    static void deleteQuietly(File f) {
        if (f != null && f.exists() && !f.delete()) {
            Log.w(TAG, "delete failed: " + f);
        }
    }

    /**
     * 供测试与排障：代理清单快照（名字 → URL/MD5）。
     *
     * @return 有序表
     */
    public static Map<String, String> proxyManifest() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(manifest());
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(k, o.optString(k, ""));
            }
        } catch (Throwable e) {
            Log.e(TAG, "proxyManifest failed", e);
        }
        return out;
    }
}
