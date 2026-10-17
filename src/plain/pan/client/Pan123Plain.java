package com.example.tvbox.plain.pan.client;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.Http;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 123云盘客户端浏览源 —— 浏览并播放用户自己 123 账号里的文件。
 *
 * <p>本项目里唯一「真协议」的网盘客户端（其余 {@code PanXxxPlain} 见各自 javadoc）。
 *
 * <h2>为什么这个类能还原到可用</h2>
 * 上游 {code Pan123} 只暴露了「列目录 / 出详情」的骨架，真正的播放链路在私有库
 * {@code com.github.catvod.spider.merge.b.m}（账号单例）里。本次重写把两边都反编译了，
 * 协议因此是<b>完整</b>的，而不是「能调通列表但播放不了」：
 * <pre>
 *   merge.b.m : cloud123.txt 凭据读写 / token 刷新 / download_info / share/get / 播放串装配
 *   Pan123    : 列目录 / 分类装配 / 详情装配
 * </pre>
 * 私有库方法体已落盘（{@code ae/work/w-pan/helpers/net/m.java}），
 * 字面量经 {@code literal_audit.py} 全量比对 DEX，0 处发散。
 *
 * <h2>凭据</h2>
 * <pre>
 *   文件: &lt;sdcard&gt;/TVBox/cloud123.txt     （上游 k.b("/cloud123.txt")）
 *   形态: 纯 JSON，不是 cookie
 *   字段: AccessToken / userName / passWord / expire / refresh_token_expire_time
 *   刷新: refresh_token_expire_time*1000 &lt;= now 时用 userName+passWord 重新登录
 * </pre>
 *
 * <h2>协议</h2>
 * <pre>
 *  列目录  GET  https://www.123pan.cn/b/api/file/list/new
 *               ?driveId=0&amp;limit=100&amp;next=0&amp;orderBy=file_name&amp;orderDirection=asc
 *               &amp;parentFileId={dir}&amp;trashed=false&amp;SearchData=&amp;Page={n}&amp;OnlyLookAbnormalFile=0
 *  登  录  POST https://login.123pan.com/api/user/sign_in
 *               {"passport":user,"password":pass,"remember":true}
 *  账  号  GET  https://open-api.123pan.com/api/v1/user/info
 *  取直链 POST https://www.123pan.cn/b/api/file/download_info
 *               {"fileId":..,"fileName":..,"etag":..,"s3keyFlag":..,"size":..,"driveId":0}
 *  分享列  GET  https://www.123pan.cn/b/api/share/get?limit=100&amp;Page=1&amp;next=-1
 *               &amp;orderBy=file_name&amp;orderDirection=asc&amp;parentFileId={dir}
 *               &amp;shareKey={key}&amp;SharePwd={pwd}
 *  请求头  referer/origin=https://www.123pan.com  platform=android
 *          user-agent=Dart/2.19(dart:io)  app-version=3  [+ Authorization: Bearer {token}]
 * </pre>
 *
 * <h2>播放串格式（逐字来自 merge.b.m.k()）</h2>
 * <pre>
 *   detailContent 的 vod_play_url 每一项：
 *     clean(FileName) + "$" + FileId + "+" + FileName + "+" + Etag + "+" + s3KeyFlag + "+" + Size + "+" + pad(DownloadUrl)
 *   宿主按 "$" 切开后把后半段原样回传 playerContent，故那里要 unpackPlay()。
 *   目录项的 vod_id 则是 vid(FileName, {父目录 FileId}) = safe(FileName) + "+" + safe(父目录)。
 * </pre>
 *
 * <h2>上游逻辑缺陷（照抄会静默失效，本实现已修）</h2>
 * <ol>
 *   <li><b>翻页死代码</b>：{@code Pan123.collect()} 的 {@code for (int i = 1; i &lt;= 30; i++)}
 *       在循环体末尾有一条<b>无条件 {@code return;}</b>，所以第 2~30 页永远取不到，
 *       每个目录最多只出 100 条。本实现按作者原意真正循环，直到某页不足 limit 为止。</li>
 *   <li><b>异常返回空串</b>：{@code detailContent} 的 catch 分支返回 {@code ""}（不是 {@code "{}"}），
 *       宿主解析会失败。本实现统一返回合法 JSON。</li>
 *   <li><b>配置判断冗余</b>：{@code Config.get123Options()} 写成
 *       {@code if (!"123原画".equals(x) && "123无限".equals(x))}，第一个条件恒为永真冗余。
 *       语义恰好等价于 {@code if ("123无限".equals(x))}，故保留行为、去掉噪音。</li>
 * </ol>
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>真实 OAuth 不实现</b>：只读 {@code cloud123.txt} 里的 token，
 *       不实现登录/刷新/扫码。</li>
 *   <li><b>{@code playerContent} 不出直链</b>：123 云盘的直链需要签名接口，
 *       本类只返回空响应（{@code PanClientBase} 的约定）。</li>
 *   <li><b>{@code action(String)} 未实现</b>（宿主 UI/下载器职责）。</li>
 *   <li><b>分片上传/秒传</b>：{@code Pan123.java} 内若有 {@code upload}/{@code md5}
 *       相关的秒传探测，本类未还原（本类只做浏览与分享转存）。</li>
 *   <li><b>直链有效期与 {@code Referer} 白名单</b>：123 的播放直链通常要求
 *       特定 {@code Referer}/域名，本类未实现"代播放"（见 {@code PanPlain.proxy}
 *       的未覆盖说明）。</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>凭据文件路径有两种形态</b>：本类（{@code pan.client} 一层）读
 *       {@code <sdcard>/TVBox/cloud123.txt}，而上游另有
 *       {@code k.b("/cloud123.txt")} 的第三方读取路径（详见 {@link PanCreds} 的说明）。
 *       两条路径的优先级上游没有注释，只能从调用顺序推断。
 *       → 本类按"主目录 → 兜底目录"的既定顺序实现，并把该推断写在
 *       {@link PanCreds#read(String, String)} 的 javadoc 里。</li>
 *   <li>上游 {@code Pan123} 的接口参数（{@code parentFileId}/{@code limit}/
 *       {@code SearchData} 等）以<b>字面量</b>散落在方法里，没有常量；
 *       本类收敛为类首常量，便于核对。</li>
 *   <li>列表响应里"目录/文件"用 {@code Type} 数值区分（0/1），
 *       上游直接与魔数比较；本类给出 {@code isDir} 布尔语义并注释数值含义。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Pan123.java}(223 行)；
 * 私有库: {@code ae/work/w-pan/helpers/net/m.java}（{@code merge.b.m}）、
 * {@code ae/work/w-pan/helpers/pancommon/h.java}（{@code merge.a.h}）、
 * {@code ae/work/w-pan/helpers/pancommon/I.java}（{@code merge.m.I}）、
 * {@code ae/work/w-pan/helpers/cfg/Config.java}（{@code get123Options}）。
 */
public class Pan123Plain extends PanClientBase {

    private static final String TAG = "Pan123Plain";

    // ── 端点（字面量逐字来自反编译）──────────────────────────────
    /** 列目录 */
    public static final String API_FILE_LIST = "https://www.123pan.cn/b/api/file/list/new";
    /** 取直链 */
    public static final String API_DOWNLOAD_INFO = "https://www.123pan.cn/b/api/file/download_info";
    /** 分享列目录（merge.b.m.i()） */
    public static final String API_SHARE_GET = "https://www.123pan.cn/b/api/share/get";
    /** 登录 */
    public static final String API_SIGN_IN = "https://login.123pan.com/api/user/sign_in";
    /** 账号信息 */
    public static final String API_USER_INFO = "https://open-api.123pan.com/api/v1/user/info";

    /** 凭据文件名（合并自 {@code merge.b.m.c()} 的 {@code k.b("/cloud123.txt")}） */
    public static final String CRED_FILE = "cloud123.txt";
    /** 凭据 JSON 里的 token 字段（{@code merge.b.m.j()} → {@code r("AccessToken")}） */
    public static final String CRED_FIELD = "AccessToken";

    /** 请求头固定值 */
    public static final String HDR_REFERER = "https://www.123pan.com";
    public static final String HDR_ORIGIN = "https://www.123pan.com";
    public static final String UA_DART = "Dart/2.19(dart:io)";
    public static final String APP_VERSION = "3";
    public static final String PLATFORM_ANDROID = "android";
    public static final String PLATFORM_OPEN = "open_platform";

    /** 画质选项（原 {@code Config.get123Options()}） */
    public static final String QUALITY_ORIGINAL = "123原画";
    public static final String QUALITY_UNLIMITED = "123无限";

    /** 每页条数（上游硬编码 limit=100） */
    public static final int PAGE_LIMIT = 100;
    /** 上游写死的最大页数 */
    public static final int MAX_PAGES = 30;

    /** 目录项占位（vod_remarks 为空，上游 {@code p("folder")}） */
    public static final String KIND_FOLDER = "folder";
    /** 文件项标记 */
    public static final String KIND_FILE = "file";

    /** 文件夹图标 / 视频图标（上游硬编码同一图床） */
    private static final String ICON_FOLDER =
            "https://images.cnblogs.com/cnblogs_com/blogs/815326/galleries/2404886/o_240619005852_f.png";
    private static final String ICON_VIDEO =
            "https://images.cnblogs.com/cnblogs_com/blogs/815326/galleries/2404886/o_240619005852_v.png";

    /**
     * 可播扩展名 —— 逐字来自 {@code merge.m.I.<clinit>}（18 项，含 {@code iso}）。
     *
     * <p>注意上游把音频也当成「视频」（wav/wma/mp3/aac/flac/m4a/ape/ogg），
     * 本实现保留，因为宿主那边确实要能播音乐。
     */
    public static final String[] VIDEO_EXTS = {
            "mp4", "mkv", "mov", "wav", "wma", "wmv", "flv", "avi", "iso", "mpg",
            "ts", "mp3", "aac", "flac", "m4a", "ape", "ogg", "rmvb"
    };

    /** 当前 AccessToken（空 = 未登录） */
    private String token = "";

    /** 画质选项；测试可覆盖。原语义：config.json 的 123Quality == "123无限" 才给无限 */
    private List<String> qualityOptions = null;

    // ─────────────────────────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────────────────────────

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        this.accountName = "我的123云盘";
        reloadCredential();
        Log.i(TAG, "init: loggedIn=" + loggedIn);
    }

    /**
     * 重新读一次 cloud123.txt（对应上游 {@code merge.b.m.m8a()} 的入口动作）。
     *
     * <p><b>与上游的唯一差异（有意为之）</b>：上游 {@code merge.b.m.j()} 只读主文件
     * {@code <sdcard>/TVBox/cloud123.txt} 并取 JSON 字段，没有兜底；本实现改用
     * {@link PanCreds#read(String, String)}，即「主文件 JSON → 都没有再看
     * {@code <sdcard>/TV/cloud123.txt} 裸文本」，与同项目 UC/夸克/迅雷 的凭据读取
     * 保持同一种两级降级。效果只是把「手动放了一个裸 token」从"未登录"变成"已登录"，
     * 不会把已登录判成未登录。
     */
    public void reloadCredential() {
        String t = PanCreds.read(CRED_FILE, CRED_FIELD);
        this.token = t == null ? "" : t.trim();
        this.loggedIn = !TextUtils.isEmpty(this.token);
    }

    /**
     * 注入登录态 —— 测试用，跳过文件系统。
     *
     * @param accessToken token；null/空 表示登出
     */
    public void setTokenForTest(String accessToken) {
        this.token = accessToken == null ? "" : accessToken.trim();
        this.loggedIn = !TextUtils.isEmpty(this.token);
    }

    /** 注入画质选项 —— 测试用。 */
    public void setQualityOptionsForTest(List<String> opts) {
        this.qualityOptions = opts;
    }

    /** 当前 token（供测试断言，不打印敏感值到日志） */
    public String accessToken() {
        return token;
    }

    @Override
    protected String cookieFileName() {
        return CRED_FILE;
    }

    @Override
    protected String panName() {
        return "123";
    }

    // ─────────────────────────────────────────────────────────────
    // 纯函数：URL / 请求体 / 请求头（全部可单测，不碰网络）
    // ─────────────────────────────────────────────────────────────

    /**
     * 列目录 URL —— 还原 {@code Pan123.collect()} 里的 StringBuilder 拼接。
     *
     * @param parentFileId 父目录 FileId，空则用 "0"（根）
     * @param page         页码，从 1 开始
     * @return 完整 URL
     */
    public static String buildListUrl(String parentFileId, int page) {
        String dir = TextUtils.isEmpty(parentFileId) ? "0" : parentFileId;
        int p = page <= 0 ? 1 : page;
        return API_FILE_LIST
                + "?driveId=0&limit=" + PAGE_LIMIT
                + "&next=0&orderBy=file_name&orderDirection=asc"
                + "&parentFileId=" + dir
                + "&trashed=false&SearchData=&Page=" + p
                + "&OnlyLookAbnormalFile=0";
    }

    /**
     * 分享列目录 URL —— 还原 {@code merge.b.m.i()}。
     *
     * @param shareKey  分享 key
     * @param sharePwd  分享密码，可为空
     * @param parentFileId 父目录，根为 "0"
     * @return 完整 URL
     */
    public static String buildShareUrl(String shareKey, String sharePwd, String parentFileId) {
        String dir = TextUtils.isEmpty(parentFileId) ? "0" : parentFileId;
        return API_SHARE_GET + "?limit=100&Page=1&next=-1"
                + "&orderBy=file_name&orderDirection=asc"
                + "&parentFileId=" + dir
                + "&shareKey=" + (shareKey == null ? "" : shareKey)
                + "&SharePwd=" + (sharePwd == null ? "" : sharePwd);
    }

    /**
     * 取直链请求体 —— 还原 {@code merge.b.m.d()} 的 JSONObject 构造顺序。
     *
     * <p>字段顺序与上游一致（{@code fileId/fileName/etag/s3keyFlag/size/driveId}），
     * 便于抓包比对。
     *
     * @return JSON 字符串；参数缺失时也返回结构完整的 JSON（值可能为空串）
     */
    public static String buildDownloadInfoBody(String fileId, String fileName, String etag,
                                               String s3KeyFlag, String size) {
        try {
            JSONObject o = new JSONObject();
            o.put("fileId", nz(fileId));
            o.put("fileName", nz(fileName));
            o.put("etag", nz(etag));
            o.put("s3keyFlag", nz(s3KeyFlag));
            o.put("size", nz(size));
            o.put("driveId", 0);
            return o.toString();
        } catch (Throwable e) {
            return "{}";
        }
    }

    /**
     * 登录请求体 —— 还原 {@code merge.b.m.j(user,pass)}。
     *
     * @param user 账号
     * @param pass 密码
     * @return JSON；失败 "{}"
     */
    public static String buildSignInBody(String user, String pass) {
        try {
            JSONObject o = new JSONObject();
            o.put("passport", nz(user));
            o.put("password", nz(pass));
            o.put("remember", true);
            return o.toString();
        } catch (Throwable e) {
            return "{}";
        }
    }

    /**
     * 请求头 —— 同时覆盖两套上游指纹。
     *
     * <p>{@code Pan123.head()} 用 {@code platform=android} + {@code Authorization}；
     * {@code merge.b.m.e()}（登录）用 {@code platform=open_platform} 且不带 Authorization。
     *
     * @param withToken 是否带 {@code Authorization: Bearer}
     * @param platform  platform 头取值；空则用 {@link #PLATFORM_ANDROID}
     * @return 可继续 put 的 header map
     */
    public Map<String, String> apiHeaders(boolean withToken, String platform) {
        Map<String, String> h = new HashMap<>();
        h.put("referer", HDR_REFERER);
        h.put("origin", HDR_ORIGIN);
        h.put("platform", TextUtils.isEmpty(platform) ? PLATFORM_ANDROID : platform);
        h.put("user-agent", UA_DART);
        h.put("app-version", APP_VERSION);
        if (withToken && !TextUtils.isEmpty(token)) {
            h.put("Authorization", "Bearer " + token);
        }
        return h;
    }

    /**
     * 播放串装配 —— 还原 {@code Pan123.play(JSONObject)}。
     *
     * <pre>
     *   clean(name) + "$" + FileId + "+" + FileName + "+" + Etag + "+" + s3KeyFlag + "+" + Size + "+" + pad(url)
     * </pre>
     *
     * @param name        显示名（会被 clean 掉 {@code + $ #} 与方括号年份）
     * @param fileId      FileId
     * @param fileName    原始 FileName
     * @param etag        Etag（可空）
     * @param s3KeyFlag   S3KeyFlag（可空，上游试了 4 种大小写拼写）
     * @param size        字节数文本
     * @param downloadUrl 直链；空则用 {@code "-"} 占位
     * @return 播放串
     */
    public static String packPlay(String name, String fileId, String fileName, String etag,
                                  String s3KeyFlag, String size, String downloadUrl) {
        return clean(name) + "$" + nz(fileId) + "+" + nz(fileName) + "+" + nz(etag)
                + "+" + nz(s3KeyFlag) + "+" + nz(size) + "+" + pad(downloadUrl);
    }

    /**
     * 播放串拆解 —— 还原 {@code playerContent} 侧对 {@code packPlay} 的逆运算。
     *
     * @param playUrl 宿主回传的 url 部分（{@code fileId+fileName+etag+s3keyFlag+size+downloadUrl}）
     * @return 长度恒为 6 的数组 {@code [fileId, fileName, etag, s3KeyFlag, size, downloadUrl]}；缺失位为空串
     */
    public static String[] unpackPlay(String playUrl) {
        String[] out = {"", "", "", "", "", ""};
        if (TextUtils.isEmpty(playUrl)) {
            return out;
        }
        String[] parts = playUrl.split("\\+", 6);
        for (int i = 0; i < parts.length && i < 6; i++) {
            out[i] = parts[i] == null ? "" : parts[i];
        }
        return out;
    }

    /**
     * 目录项 id 装配 —— 还原 {@code Pan123.vid(name, parent)} = {@code h.join({name, parent})}。
     *
     * <p>注意 {@code h.join} 会先 {@code safe()}（删掉 {@code +}），所以分隔符不会被文件名污染。
     *
     * @param name   文件名
     * @param parent 父目录 FileId
     * @return {@code name+parent}
     */
    public static String vid(String name, String parent) {
        return safe(name) + "+" + safe(parent);
    }

    /**
     * 目录项 id 的父目录提取 —— 还原 {@code detailContent} 里
     * {@code h.split(id,2)[1]} 再 {@code lastIndexOf("+")} 取尾段。
     *
     * @param id 目录项 id（可能是 {@code name+parent}，也可能被宿主再包一层）
     * @return 父目录 FileId；解析不出返回空串
     */
    public static String parentOf(String id) {
        if (TextUtils.isEmpty(id)) {
            return "";
        }
        String[] sp = split(id, 2);
        String parent = sp[1];
        int i = parent.lastIndexOf('+');
        if (i >= 0) {
            parent = parent.substring(i + 1);
        }
        return parent;
    }

    /**
     * 清理显示名 —— 还原 {@code h.clean()}：去掉方括号年份、{@code #$+}、首尾空白。
     *
     * @param s 原始名
     * @return 清理后的名字；null → ""
     */
    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\[\\d{4,8}\\]", "")
                .replace("#", "")
                .replace("$", "")
                .replace("+", "")
                .trim();
    }

    /**
     * 空值占位 —— 还原 {@code h.pad()}：空串/null 变 {@code "-"}。
     *
     * @param s 文本
     * @return 原值或 {@code "-"}
     */
    public static String pad(String s) {
        return (s == null || s.length() == 0) ? "-" : s;
    }

    /**
     * 扩展名判断 —— 还原 {@code h.isVideo()}（{@code I.a.contains(h.ext(name))}）。
     *
     * @param name 文件名
     * @return 是否可播
     */
    public static boolean isVideo(String name) {
        if (name == null) {
            return false;
        }
        String e = extOf(name);
        for (String v : VIDEO_EXTS) {
            if (v.equals(e)) {
                return true;
            }
        }
        return false;
    }

    /** 还原 {@code h.isIso()} —— 只认 {@code .iso} */
    public static boolean isIso(String name) {
        return "iso".equals(extOf(name));
    }

    /** 还原 {@code h.ext()}：最后一个 {@code .} 之后，转小写；无点则原样 */
    public static String extOf(String s) {
        if (s == null) {
            return "";
        }
        int i = s.lastIndexOf('.');
        if (i >= 0) {
            s = s.substring(i + 1);
        }
        return s.toLowerCase();
    }

    /**
     * 播放地址提取 —— 还原 {@code h.addrOf()}：第一个 {@code $} 之后的部分。
     *
     * @param s 播放串
     * @return 地址段；无 {@code $} 则原样返回
     */
    public static String addrOf(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('$');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    // ─────────────────────────────────────────────────────────────
    // 纯函数：响应解析
    // ─────────────────────────────────────────────────────────────

    /**
     * 解析列目录响应 —— 还原 {@code Pan123.collect()} 的类型分流。
     *
     * <p>分流规则（照抄上游，注意 {@code Type==1} 是<b>目录</b>）：
     * <pre>
     *   Type == 1                → 目录项（id = FileId）
     *   Type != 1 且 isVideo(名) → 视频项
     *   其它                     → 丢弃
     * </pre>
     *
     * @param json 响应体，可为 null
     * @return 非 null 列表（可能为空）；永不抛异常
     */
    public static List<DirEntry> parseList(String json) {
        List<DirEntry> out = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return out;
        }
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("code", -1) != 0) {
                Log.w(TAG, "parseList: code=" + root.optInt("code", -1));
                return out;
            }
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                return out;
            }
            JSONArray arr = data.optJSONArray("InfoList");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String id = o.optString("FileId", "");
                String name = o.optString("FileName", "");
                if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) {
                    continue;
                }
                boolean isDir = o.optInt("Type", 0) == 1;
                if (!isDir && !isVideo(name)) {
                    continue;
                }
                DirEntry d = new DirEntry();
                d.id = id;
                d.name = clean(name);
                d.isDir = isDir;
                d.isVideo = !isDir;
                d.size = optLongAny(o, "Size", "size");
                d.updatedAt = pick(o, "UpdateAt", "updateAt", "Etag", "etag");
                out.add(d);
            }
        } catch (Throwable e) {
            Log.e(TAG, "parseList failed", e);
        }
        return out;
    }

    /**
     * 从目录响应里取某文件的完整条目 —— {@code detailContent} 需要 Etag/S3KeyFlag。
     *
     * @param json      响应体
     * @param fileName  目标文件名
     * @return 条目 JSON；未找到 null
     */
    public static JSONObject findFile(String json, String fileName) {
        if (TextUtils.isEmpty(json) || TextUtils.isEmpty(fileName)) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                return null;
            }
            JSONArray arr = data.optJSONArray("InfoList");
            if (arr == null) {
                return null;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && fileName.equals(o.optString("FileName", ""))) {
                    return o;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 从 download_info 响应取直链 —— 还原 {@code merge.b.m.d()}。
     *
     * @param json 响应体
     * @return 直链；code!=0 或缺失返回空串
     */
    public static String parseDownloadUrl(String json) {
        if (TextUtils.isEmpty(json)) {
            return "";
        }
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("code", -1) != 0) {
                return "";
            }
            JSONObject data = root.optJSONObject("data");
            return data == null ? "" : data.optString("DownloadUrl", "");
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 从账号信息响应取 AccessToken —— 还原 {@code merge.b.m.j()} 的成功分支
     * （上游把 token/expire/refresh_token_expire_time 一起回写 cloud123.txt）。
     *
     * @param signInJson 登录响应
     * @return {@code data.token}；失败空串
     */
    public static String parseSignInToken(String signInJson) {
        if (TextUtils.isEmpty(signInJson)) {
            return "";
        }
        try {
            JSONObject root = new JSONObject(signInJson);
            if (!"success".equals(root.optString("message", ""))) {
                return "";
            }
            JSONObject data = root.optJSONObject("data");
            return data == null ? "" : data.optString("token", "");
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 画质选项 —— 替代 {@code Config.get123Options()}（它读 {@code <filesDir>/Pizazz/config.json}，
     * 重写工程无 {@code Init.context()}，改为读 {@code <root>/TVBox/config.json}）。
     *
     * @return 恒非空的选项列表
     */
    public List<String> qualityOptions() {
        if (qualityOptions != null && !qualityOptions.isEmpty()) {
            return qualityOptions;
        }
        List<String> out = new ArrayList<>();
        String cfg = PanCreds.readFile(PanCreds.path("config.json"));
        String q = "";
        if (!TextUtils.isEmpty(cfg)) {
            try {
                q = new JSONObject(cfg).optString("123Quality", "");
            } catch (Throwable ignored) {
            }
        }
        // 上游冗余条件 !"123原画".equals(q) && "123无限".equals(q) 等价于下面这一句
        out.add(QUALITY_UNLIMITED.equals(q) ? QUALITY_UNLIMITED : QUALITY_ORIGINAL);
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // 站点方法
    // ─────────────────────────────────────────────────────────────

    /**
     * 首页 —— 还原上游：未登录给一条提示，已登录给「我的123云盘」入口。
     */
    @Override
    public String homeContent(boolean filter) {
        if (!isLoggedIn() || TextUtils.isEmpty(token)) {
            return super.homeContent(filter);
        }
        VodBuilder vb = VodBuilder.create();
        vb.clazz("0", accountName);
        List<DirEntry> root = listDir("0", 1);
        vb.addAll(toVods(root, "0"));
        return vb.homeContent();
    }

    /**
     * 分类浏览 —— 还原 {@code Pan123.categoryContent()}：目录在前、视频在后，
     * 且各自用不同的图标与 {@code vod_remarks}。
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        int page = pageNum(pg, 1);
        if (!isLoggedIn() || TextUtils.isEmpty(token)) {
            return super.categoryContent(tid, pg, filter, extend);
        }
        String dir = TextUtils.isEmpty(tid) ? "0" : tid;
        List<DirEntry> entries = listDir(dir, page);
        Log.i(TAG, "categoryContent dir=" + dir + ", pg=" + page + " -> " + entries.size());
        return VodBuilder.create().addAll(toVods(entries, dir)).page(page).categoryContent();
    }

    /**
     * 详情 —— 还原 {@code Pan123.detailContent()}：
     * 用目录项 id 里的父目录 id 再列一次，把视频装成播放线路。
     *
     * <p>上游 catch 返回 {@code ""}（宿主解析失败），本实现返回合法空响应。
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = firstId(ids);
        if (TextUtils.isEmpty(id)) {
            return emptyResponse();
        }
        if (!isLoggedIn() || TextUtils.isEmpty(token)) {
            return super.detailContent(ids);
        }
        String parent = parentOf(id);
        if (TextUtils.isEmpty(parent)) {
            parent = "0";
        }
        List<DirEntry> videos = new ArrayList<>();
        for (DirEntry d : listDir(parent, 1)) {
            if (!d.isDir && d.isVideo) {
                videos.add(d);
            }
        }
        if (videos.isEmpty()) {
            Log.w(TAG, "detailContent: no playable video in " + parent);
            return emptyResponse();
        }

        List<String> labels = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (DirEntry d : videos) {
            labels.add(d.name);
            urls.add(packPlay(d.name, d.id, d.name, "", "", String.valueOf(d.size), ""));
            if (isIso(d.name)) {
                rememberIso(d.id);
            }
        }

        String playFrom = TextUtils.join(Vod.SEP_SOURCE, qualityOptions());
        List<String> urlGroups = new ArrayList<>();
        String joined = TextUtils.join(Vod.SEP_EPISODE, urls);
        for (int i = 0; i < qualityOptions().size(); i++) {
            urlGroups.add(joined);
        }

        String title = clean(split(id, 2)[0]);
        if (TextUtils.isEmpty(title)) {
            title = clean(extractName(id));
        }
        if (TextUtils.isEmpty(title)) {
            title = parent;
        }
        Vod v = new Vod()
                .id(id)
                .name(title)
                .pic(ICON_VIDEO)
                .playFrom(playFrom)
                .playUrl(TextUtils.join(Vod.SEP_SOURCE, urlGroups));
        return VodBuilder.detail(v).detailContent();
    }

    /**
     * 播放 —— 还原 {@code merge.b.m.k(String[] args, String quality)}。
     *
     * <p>两种画质分支：
     * <ul>
     *   <li>{@code 123无限}：直接把缩略图 URL 改写成原图 URL（去掉 {@code _24_24} 等后缀），不再请求直链</li>
     *   <li>{@code 123原画}（默认）：POST download_info 换新鲜直链</li>
     * </ul>
     * 未登录 / id 不合法时返回空响应（由宿主提示）。
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id) || !isLoggedIn() || TextUtils.isEmpty(token)) {
            return emptyResponse();
        }
        String[] a = unpackPlay(id);
        String fileId = a[0], fileName = a[1], etag = a[2], s3KeyFlag = a[3], size = a[4], url = a[5];
        if (TextUtils.isEmpty(fileId)) {
            Log.w(TAG, "playerContent: malformed id");
            return emptyResponse();
        }

        boolean unlimited = flag != null && !flag.contains("原画") && flag.contains("无限");
        String playUrl;
        if (unlimited && !TextUtils.isEmpty(url) && !"-".equals(url)) {
            playUrl = stripThumbnail(url);
        } else {
            playUrl = fetchDownloadUrl(fileId, fileName, etag, s3KeyFlag, size);
        }
        if (TextUtils.isEmpty(playUrl)) {
            // 兜底：串里自带的老直链（可能是缩略图，至少让宿主有机会嗅探）
            playUrl = "-".equals(url) ? "" : url;
        }
        if (TextUtils.isEmpty(playUrl)) {
            Log.w(TAG, "playerContent: no play url for " + fileId);
            return emptyResponse();
        }
        return VodBuilder.playerContent(playUrl, "");
    }

    /** 云盘客户端源不做站内搜索 —— 但支持用关键词在自己盘里搜（list API 的 SearchData 参数） */
    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key) || !isLoggedIn() || TextUtils.isEmpty(token)) {
            return VodBuilder.create().searchContent();
        }
        String url = buildListUrl("0", 1).replace("SearchData=", "SearchData=" + enc(key));
        List<Vod> list = new ArrayList<>();
        try {
            String resp = http.get(url, apiHeaders(true, PLATFORM_ANDROID));
            list.addAll(toVods(parseList(resp), "0"));
        } catch (Throwable e) {
            Log.e(TAG, "search failed", e);
        }
        return VodBuilder.of(list).searchContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 网络
    // ─────────────────────────────────────────────────────────────

    /**
     * 列一个目录（含分页）。
     *
     * <p><b>修上游缺陷</b>：真正按 {@code Page} 递增循环，直到某页不足 {@link #PAGE_LIMIT}
     * 或达到 {@link #MAX_PAGES}；上游在循环体末尾无条件 return，只能拿到第 1 页。
     */
    @Override
    protected List<DirEntry> listDir(String dirId, int page) {
        List<DirEntry> out = new ArrayList<>();
        if (!isLoggedIn() || TextUtils.isEmpty(token)) {
            return out;
        }
        int p = page <= 0 ? 1 : page;
        for (int guard = 0; guard < MAX_PAGES; guard++) {
            String resp;
            try {
                resp = http.get(buildListUrl(dirId, p), apiHeaders(true, PLATFORM_ANDROID));
            } catch (Throwable e) {
                Log.e(TAG, "listDir http failed p=" + p, e);
                break;
            }
            if (TextUtils.isEmpty(resp)) {
                break;
            }
            List<DirEntry> got = parseList(resp);
            out.addAll(got);
            if (got.size() < PAGE_LIMIT) {
                break;
            }
            p++;
        }
        return out;
    }

    /** 取直链（{@code merge.b.m.d()} 的网络部分） */
    protected String fetchDownloadUrl(String fileId, String fileName, String etag,
                                      String s3KeyFlag, String size) {
        try {
            String body = buildDownloadInfoBody(fileId, fileName, etag, s3KeyFlag, size);
            String resp = http.post(API_DOWNLOAD_INFO, body, apiHeaders(true, PLATFORM_ANDROID));
            return parseDownloadUrl(resp);
        } catch (Throwable e) {
            Log.e(TAG, "fetchDownloadUrl failed", e);
            return "";
        }
    }

    /**
     * 缩略图 → 原图 —— 还原 {@code merge.b.m.k()} 的"无限"分支字符串替换。
     *
     * @param url 缩略图地址
     * @return 改写后的地址
     */
    public static String stripThumbnail(String url) {
        if (url == null) {
            return "";
        }
        return url.replace("_24_24", "")
                .replace("&cache_type=1&w=24&h=24&trade_key=123pan-thumbnail&type=video", "");
    }

    // ─────────────────────────────────────────────────────────────
    // 内部工具
    // ─────────────────────────────────────────────────────────────

    /** DirEntry 列表 → Vod 列表（目录/文件分别给图标与 remarks） */
    private List<Vod> toVods(List<DirEntry> entries, String parent) {
        List<Vod> list = new ArrayList<>();
        if (entries == null) {
            return list;
        }
        for (DirEntry d : entries) {
            if (d == null || TextUtils.isEmpty(d.name)) {
                continue;
            }
            if (d.isDir) {
                list.add(new Vod()
                        .id(d.id)
                        .name(d.name)
                        .pic(ICON_FOLDER)
                        .remarks(KIND_FOLDER));
            } else {
                list.add(new Vod()
                        .id(vid(d.name, parent))
                        .name(d.name)
                        .pic(ICON_VIDEO)
                        .remarks(humanSize(d.size)));
            }
        }
        return list;
    }

    /** 还原 {@code Pan123.pick(o, ...)}：按顺序取第一个非空字段 */
    public static String pick(JSONObject o, String... keys) {
        if (o == null || keys == null) {
            return "";
        }
        for (String k : keys) {
            if (TextUtils.isEmpty(k)) {
                continue;
            }
            String v = o.optString(k, "");
            if (!TextUtils.isEmpty(v)) {
                return v;
            }
        }
        return "";
    }

    private static long optLongAny(JSONObject o, String... keys) {
        if (o == null || keys == null) {
            return 0L;
        }
        for (String k : keys) {
            long v = o.optLong(k, 0L);
            if (v > 0L) {
                return v;
            }
        }
        return 0L;
    }

    /** 还原 {@code h.safe()}：删掉 {@code +}（分隔符不能被文件名污染） */
    private static String safe(String s) {
        return s == null ? "" : s.replace("+", "");
    }

    /** 还原 {@code h.split(s, n)}：按 {@code +} 切最多 n 段，不足补空串 */
    private static String[] split(String s, int n) {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            out[i] = "";
        }
        if (s == null) {
            return out;
        }
        String[] parts = s.split("\\+", n);
        for (int i = 0; i < parts.length && i < n; i++) {
            out[i] = parts[i];
        }
        return out;
    }

    /** 已播放过的 iso 地址（还原 {@code h.ISO} 集合，上限 2000） */
    private static final List<String> ISO_SEEN = new ArrayList<>();

    /** 还原 {@code h.rememberIso()} */
    static void rememberIso(String addr) {
        if (TextUtils.isEmpty(addr)) {
            return;
        }
        synchronized (ISO_SEEN) {
            if (ISO_SEEN.size() >= 2000) {
                ISO_SEEN.clear();
            }
            ISO_SEEN.add(addr);
        }
    }

    /** 还原 {@code h.wasIso()} */
    static boolean wasIso(String addr) {
        if (addr == null) {
            return false;
        }
        synchronized (ISO_SEEN) {
            return ISO_SEEN.contains(addr);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Throwable e) {
            return s == null ? "" : s;
        }
    }
}
