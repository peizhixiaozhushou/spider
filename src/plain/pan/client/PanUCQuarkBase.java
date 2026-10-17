package com.example.tvbox.plain.pan.client;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UC / 夸克 同构协议基类 —— 两家网盘的云盘接口是<b>同一套</b>，只有域名、UA、目录名不同。
 *
 * <p>本基类把「一份协议、两个品牌」的重复代码收敛到一处；
 * {@link PanUCPlain} 与 {@link PanQuarkPlain} 只提供品牌常量（域名 / UA / 目录名 / 缓存键）。
 *
 * <h2>为什么两家能共用</h2>
 * UC 与夸克同属一家（ucpro 云盘底座），接口路径、参数名、响应结构、错误判定完全一致：
 * <pre>
 *   /1/clouddrive/file/sort               列目录（pdir_fid + _page + _size + _sort）
 *   /1/clouddrive/file/delete             删文件（action_type=2）
 *   /1/clouddrive/file                    建目录（pdir_fid + file_name + dir_path + dir_init_lock）
 *   /1/clouddrive/share/sharepage/token   分享换 stoken（{"pwd_id":..,"passcode":..}）
 *   /1/clouddrive/share/sharepage/detail  列分享（pwd_id + stoken + pdir_fid + force + _page）
 *   /1/clouddrive/share/sharepage/save    转存到自己的目录（fid_list + fid_token_list + to_pdir_fid + pwd_id + stoken）
 * </pre>
 * 差异只在：{@code pr=UCBrowser@pc-api.uc.cn} vs {@code pr=ucpro@drive-pc.quark.cn}、
 * 以及分享接口落在 {@code pc-api.uc.cn} vs {@code drive.quark.cn}。
 *
 * <h2>本类做什么（用途：网盘转存助手）</h2>
 * <ol>
 *   <li>{@link #ensureTempDir()}：在自己盘根目录建一个 {@code UCtemp}/{@code Quarktemp} 目录</li>
 *   <li>{@link #refreshTempShare()}：把指定分享里的<b>一个随机文件</b>转存进该目录，并缓存日期</li>
 *   <li>{@link #deleteTempFiles()}：把该目录里的文件删掉（{@code deletetime()} 判断满 7 天才跑）</li>
 * </ol>
 * 这样播放器可以直接播自己盘里的文件，绕开分享的时效与限速。
 *
 * <h2>字符串混淆已解开</h2>
 * 上游把全部 URL / UA / 字段名藏在 {@code short[]} 表 + 异或种子里；
 * 本文件的每个字面量都由 {@link PanObfStrings} 解出并已通过
 * {@code literal_audit.py} 与 DEX 字符串池逐字比对（0 处发散）。
 * 解码后的 148 条字符串清单见 {@code ae/work/w-pan/decoded-strings.txt}。
 *
 * <h2>上游逻辑缺陷</h2>
 * <ol>
 *   <li><b>响应校验用 {@code contains("{")}</b>：上游判断 {@code strL.contains("{") || strL.contains("}")}
 *       就算"拿到了 JSON"。一个 500 错误页里带花括号也会被当成成功，随后
 *       {@code new JSONObject(..)} 抛异常。本实现改用 {@code parse*} 直接尝试解析，
 *       解析失败即视为失败。</li>
 *   <li><b>随机选文件</b>（{@code new Random().nextInt(len)}）：每 7 天转存的内容不确定，
 *       播放列表会莫名变化。本实现保留该行为但把随机源做成可注入，便于复现与测试。</li>
 *   <li><b>分享 id 硬编码</b>：{@code UC = c4015526cb494 / 夸克 = 369868287b83}。
 *       分享一旦被删，整个功能静默失效。本实现抽成 {@link #defaultShareId()} 并可注入。</li>
 *   <li><b>7 天判断依赖缓存文件</b>：{@code deletetime()} 里从缓存读 {@code yyyyMMdd} 再
 *       {@code substring(0,4)} 解析，缓存内容被截断（&lt;8 位）时 {@code parseInt} 抛异常。
 *       本实现做了长度与数字校验。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/UCPan.java}(190 行) / {@code ae/decompiled/QuarkPan.java}(193 行)；
 * 解码器: {@code ae/decompiled/{a,b,c,d,e}.java}；
 * 私有库: {@code ae/work/w-pan/helpers/pancommon/k.java}（缓存文件）、{@code net/v.java}（map 构造）。
 */
public abstract class PanUCQuarkBase extends PanClientBase {

    private static final String TAG = "PanUCQuark";

    // ── 通道常量（两家完全一致）────────────────────────────────
    /** 删文件的 body 模板（逐字来自 {@code deleteUCTemp/deleteQuarkTemp}） */
    public static final String BODY_DELETE_PREFIX = "{\"action_type\":2,\"filelist\":[\"";
    /** 删文件 body 后缀 */
    public static final String BODY_DELETE_SUFFIX = "\"],\"exclude_fids\":[]}";
    /** 建目录 body 前缀 */
    public static final String BODY_MKDIR_PREFIX = "{\"pdir_fid\":\"0\",\"file_name\":\"";
    /** 建目录 body 后缀（目录名之后） */
    public static final String BODY_MKDIR_SUFFIX = "\",\"dir_path\":\"\",\"dir_init_lock\":false}";
    /** 换 stoken body 前缀 */
    public static final String BODY_TOKEN_PREFIX = "{\"pwd_id\":\"";
    /** 换 stoken body 后缀 */
    public static final String BODY_TOKEN_SUFFIX = "\",\"passcode\":\"\"}";
    /** 转存 body 片段 */
    public static final String BODY_SAVE_A = "{\"fid_list\":[\"";
    public static final String BODY_SAVE_B = "\"],\"fid_token_list\":[\"";
    public static final String BODY_SAVE_C = "\"],\"pdir_fid\":\"0\",\"to_pdir_fid\":\"";
    public static final String BODY_SAVE_D = "\",\"pwd_id\":\"";
    public static final String BODY_SAVE_E = "\",\"stoken\":\"";
    public static final String BODY_SAVE_F = "\"}";
    /** 分享详情 URL 里的固定尾串（逐字来自两处 l()） */
    public static final String SHARE_DETAIL_TAIL_UC =
            "&_size=9999&_fetch_banner=1&_fetch_share=1&_fetch_total=1&_sort=file_type:asc,file_name:desc";
    /** 转存成功后响应里的成功标记 */
    public static final String MSG_OK = "ok";
    /** 分享详情响应里取 list / share / first_fid 的字段名 */
    public static final String F_LIST = "list";
    public static final String F_STOKEN = "stoken";
    public static final String F_DATA = "data";
    public static final String F_SHARE = "share";
    public static final String F_FIRST_FID = "first_fid";
    public static final String F_FID = "fid";
    public static final String F_SHARE_FID_TOKEN = "share_fid_token";
    public static final String F_FILE_NAME = "file_name";
    public static final String F_MESSAGE = "message";

    /** 转存落点 pdir_fid（转存到 temp 目录） */
    public static final String SAVE_TARGET_PDIR = "0";

    /** 7 天清理阈值（毫秒/天） */
    public static final long DAY_MS = 86400000L;

    /** 登录态 cookie */
    private String cookie = "";

    /** 当前分享 id（可在 init 后覆盖） */
    private String shareId = "";

    /** 可注入随机源（测试用） */
    private java.util.Random random = null;

    /** 候选分享目录的 fid 缓存（转存后才填） */
    private String tempDirFid = "";

    // ─────────────────────────────────────────────────────────────
    // 品牌钩子（子类只提供常量）
    // ─────────────────────────────────────────────────────────────

    /** 品牌中文名：{@code "UC"} / {@code "夸克"} */
    public abstract String brandName();

    /** 凭据文件名：{@code uc_cookie.txt} / {@code quark_cookie.txt} */
    public abstract String brandCookieFile();

    /** 临时目录名：{@code UCtemp} / {@code Quarktemp} */
    public abstract String brandTempDir();

    /** 转存日期缓存键：{@code uctime} / {@code qktime} */
    public abstract String brandTimeKey();

    /** 清理日期缓存键：{@code deleteuctime} / {@code deleteqktime} */
    public abstract String brandDeleteTimeKey();

    /** 硬编码的默认分享 id */
    public abstract String defaultShareId();

    /** 完整业务 UA（逐字，含 Channel 尾串） */
    public abstract String brandUa();

    /** Referer */
    public abstract String brandReferer();

    /** Origin */
    public abstract String brandOrigin();

    /** 列根目录 URL（用来找临时目录） */
    public abstract String rootSortUrl();

    /** 列子目录 URL */
    public abstract String subdirSortUrl(String pdirFid);

    /** 删文件 URL */
    public abstract String deleteUrl();

    /** 建目录 URL */
    public abstract String createDirUrl();

    /** 换 stoken URL */
    public abstract String shareTokenUrl();

    /** 列分享 URL */
    public abstract String shareDetailUrl(String pwdId, String stoken, String pdirFid);

    /** 转存 URL */
    public abstract String shareSaveUrl();

    // ─────────────────────────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────────────────────────

    @Override
    public void init(Context context, String extend) {
        super.init(context, extend);
        this.accountName = "我的" + brandName() + "网盘";
        if (TextUtils.isEmpty(shareId)) {
            shareId = defaultShareId();
        }
        reloadCredential();
        Log.i(TAG, "init " + brandName() + ": loggedIn=" + loggedIn);
    }

    /**
     * 读 cookie —— 还原 {@code checkuccookie() / checkquarkcookie()}。
     *
     * <p>主文件 {@code <sdcard>/TVBox/<cookie>.txt} 是 JSON（取 {@code cookie} 字段），
     * 兜底 {@code <sdcard>/TV/<cookie>.txt} 是裸文本。
     */
    public void reloadCredential() {
        String c = PanCreds.read(brandCookieFile(), "cookie");
        this.cookie = c == null ? "" : c.trim();
        this.loggedIn = !TextUtils.isEmpty(this.cookie);
    }

    /** 注入 cookie —— 测试用 */
    public void setCookieForTest(String c) {
        this.cookie = c == null ? "" : c.trim();
        this.loggedIn = !TextUtils.isEmpty(this.cookie);
    }

    /** 注入分享 id —— 测试用 */
    public void setShareIdForTest(String id) {
        this.shareId = TextUtils.isEmpty(id) ? defaultShareId() : id;
    }

    /** 注入随机源 —— 测试用 */
    public void setRandomForTest(java.util.Random r) {
        this.random = r;
    }

    /** 注入 temp 目录 fid —— 测试用 */
    public void setTempDirFidForTest(String fid) {
        this.tempDirFid = fid == null ? "" : fid;
    }

    public String cookie() {
        return cookie;
    }

    public String shareId() {
        return shareId;
    }

    @Override
    protected String cookieFileName() {
        return brandCookieFile();
    }

    @Override
    protected String panName() {
        return brandName();
    }

    // ─────────────────────────────────────────────────────────────
    // 请求头 / body（纯函数）
    // ─────────────────────────────────────────────────────────────

    /**
     * 业务请求头 —— 还原 {@code j()}。
     *
     * <p>上游是 {@code v.b(ua, cookie)} 再 put 三个头，等价于下面四项。
     *
     * @return header map（Cookie 为空时也带上，与上游一致）
     */
    public Map<String, String> apiHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", brandUa());
        h.put("Cookie", cookie == null ? "" : cookie);
        h.put("Content-Type", "application/json;charset=UTF-8");
        h.put("Referer", brandReferer());
        h.put("Origin", brandOrigin());
        return h;
    }

    /** 删文件 body —— 还原 {@code deleteUCTemp()} 里的字符串拼接 */
    public static String buildDeleteBody(String fid) {
        return BODY_DELETE_PREFIX + nz(fid) + BODY_DELETE_SUFFIX;
    }

    /** 建目录 body —— 还原 {@code m()} */
    public static String buildCreateDirBody(String dirName) {
        return BODY_MKDIR_PREFIX + nz(dirName) + BODY_MKDIR_SUFFIX;
    }

    /** 换 stoken body —— 还原 {@code l()} */
    public static String buildShareTokenBody(String pwdId) {
        return BODY_TOKEN_PREFIX + nz(pwdId) + BODY_TOKEN_SUFFIX;
    }

    /**
     * 转存 body —— 还原 {@code l()} 里的 {@code String.format} 等价拼接。
     *
     * @param fid          文件 fid
     * @param fidToken     文件 share_fid_token
     * @param toPdirFid    目标目录 fid（temp 目录）
     * @param pwdId        分享 id
     * @param stoken       分享 stoken
     * @return JSON body
     */
    public static String buildSaveBody(String fid, String fidToken, String toPdirFid,
                                       String pwdId, String stoken) {
        return BODY_SAVE_A + nz(fid) + BODY_SAVE_B + nz(fidToken) + BODY_SAVE_C
                + nz(toPdirFid) + BODY_SAVE_D + nz(pwdId) + BODY_SAVE_E + nz(stoken) + BODY_SAVE_F;
    }

    // ─────────────────────────────────────────────────────────────
    // 响应解析（纯函数）
    // ─────────────────────────────────────────────────────────────

    /**
     * 解析 {@code data.list[]} —— 还原 {@code m()} / {@code deleteUCTemp()} 的遍历。
     *
     * <p>每条至少要有 {@code fid} 与 {@code file_name}；缺任一即丢弃。
     *
     * @param json 响应体
     * @return 非 null 列表
     */
    public static List<DirEntry> parseList(String json) {
        List<DirEntry> out = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return out;
        }
        try {
            JSONObject data = new JSONObject(json).optJSONObject(F_DATA);
            if (data == null) {
                return out;
            }
            JSONArray arr = data.optJSONArray(F_LIST);
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String fid = o.optString(F_FID, "");
                String name = o.optString(F_FILE_NAME, "");
                if (TextUtils.isEmpty(fid) || TextUtils.isEmpty(name)) {
                    continue;
                }
                DirEntry d = new DirEntry();
                d.id = fid;
                d.name = name;
                // 上游只读 file_name / fid，从不判断目录；下面的目录判定是本实现的启发式：
                // 该接口用 dir=true 或 file_type==0 表示目录（分享/浏览 UI 需要区分）。
                d.isDir = o.optBoolean("dir", false) || o.optInt("file_type", 1) == 0;
                d.size = o.optLong("size", 0L);
                d.updatedAt = o.optString("updated_at", "");
                d.token = o.optString(F_SHARE_FID_TOKEN, "");
                d.isVideo = !d.isDir && looksLikeVideo(name);
                out.add(d);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 从文件列表里找目录的 fid —— 还原 {@code m()} 的
     * {@code z.equals(list[i].file_name)} 判断。
     *
     * @param json    响应体
     * @param dirName 目录名
     * @return 命中目录的 fid；未命中空串
     */
    public static String findDirFid(String json, String dirName) {
        if (TextUtils.isEmpty(json) || TextUtils.isEmpty(dirName)) {
            return "";
        }
        for (DirEntry d : parseList(json)) {
            if (dirName.equals(d.name)) {
                return d.id;
            }
        }
        return "";
    }

    /** 取 {@code data.stoken} —— 还原 {@code l()} 里 {@code getJSONObject(data).getString(stoken)} */
    public static String parseStoken(String json) {
        return parseDataField(json, F_STOKEN);
    }

    /** 取 {@code data.share.first_fid} —— 还原 {@code l()} */
    public static String parseFirstFid(String json) {
        if (TextUtils.isEmpty(json)) {
            return "";
        }
        try {
            JSONObject data = new JSONObject(json).optJSONObject(F_DATA);
            if (data == null) {
                return "";
            }
            JSONObject share = data.optJSONObject(F_SHARE);
            return share == null ? "" : share.optString(F_FIRST_FID, "");
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * 从转存响应判断是否成功 —— 还原 {@code l()} 的
     * {@code json.has("message") && "ok".equals(json.getString("message"))}。
     *
     * @param json 响应体
     * @return 是否 {@code message == "ok"}
     */
    public static boolean isSaveOk(String json) {
        return MSG_OK.equals(parseField(json, F_MESSAGE));
    }

    /** 取顶层字符串字段 */
    public static String parseField(String json, String field) {
        if (TextUtils.isEmpty(json) || TextUtils.isEmpty(field)) {
            return "";
        }
        try {
            return new JSONObject(json).optString(field, "");
        } catch (Throwable e) {
            return "";
        }
    }

    /** 取 {@code data.<field>} */
    public static String parseDataField(String json, String field) {
        if (TextUtils.isEmpty(json) || TextUtils.isEmpty(field)) {
            return "";
        }
        try {
            JSONObject data = new JSONObject(json).optJSONObject(F_DATA);
            return data == null ? "" : data.optString(field, "");
        } catch (Throwable e) {
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 站点方法
    // ─────────────────────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        if (!isLoggedIn()) {
            return super.homeContent(filter);
        }
        VodBuilder vb = VodBuilder.create().clazz("0", accountName);
        vb.addAll(toVods(listDir("0", 1)));
        return vb.homeContent();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        if (!isLoggedIn()) {
            return super.categoryContent(tid, pg, filter, extend);
        }
        int page = pageNum(pg, 1);
        List<DirEntry> entries = listDir(TextUtils.isEmpty(tid) ? "0" : tid, page);
        Log.i(TAG, brandName() + " categoryContent dir=" + tid + " -> " + entries.size());
        return VodBuilder.create().addAll(toVods(entries)).page(page).categoryContent();
    }

    /**
     * 列目录 —— 用 {@code subdirSortUrl}，未登录返回空。
     */
    @Override
    protected List<DirEntry> listDir(String dirId, int page) {
        List<DirEntry> out = new ArrayList<>();
        if (!isLoggedIn()) {
            return out;
        }
        try {
            String url = TextUtils.isEmpty(dirId) || "0".equals(dirId)
                    ? rootSortUrl() : subdirSortUrl(dirId);
            out.addAll(parseList(http.get(url, apiHeaders())));
        } catch (Throwable e) {
            Log.e(TAG, "listDir failed", e);
        }
        return out;
    }

    /**
     * 播放 —— 网盘直链换取需要 app 侧签名，此处未实现（见类注释）。
     * 返回契约要求的空响应，不伪造地址。
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (!isLoggedIn() || TextUtils.isEmpty(id)) {
            return emptyResponse();
        }
        Log.w(TAG, "playerContent: " + brandName() + " 直链换取未实现");
        return emptyResponse();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return VodBuilder.create().searchContent();
    }

    // ─────────────────────────────────────────────────────────────
    // 转存助手：建目录 / 转存 / 清理
    // ─────────────────────────────────────────────────────────────

    /**
     * 确保临时目录存在 —— 还原 {@code m()}。
     *
     * <p>先在根目录找同名目录，找不到就建一个（body：{@code pdir_fid=0 + 目录名}）。
     *
     * @return 临时目录的 fid；失败空串
     */
    public String ensureTempDir() {
        if (!isLoggedIn()) {
            return "";
        }
        if (!TextUtils.isEmpty(tempDirFid)) {
            return tempDirFid;
        }
        try {
            String listing = http.get(rootSortUrl(), apiHeaders());
            String fid = findDirFid(listing, brandTempDir());
            if (!TextUtils.isEmpty(fid)) {
                tempDirFid = fid;
                return tempDirFid;
            }
            String created = http.post(createDirUrl(), buildCreateDirBody(brandTempDir()), apiHeaders());
            tempDirFid = parseDataField(created, F_FID);
            Log.i(TAG, brandName() + " ensureTempDir created: " + (!TextUtils.isEmpty(tempDirFid)));
        } catch (Throwable e) {
            Log.e(TAG, "ensureTempDir failed", e);
        }
        return tempDirFid == null ? "" : tempDirFid;
    }

    /**
     * 转存一次分享 —— 还原 {@code l()}。
     *
     * <p>流程：换 stoken → 列分享根 → 用 {@code first_fid} 再列一次拿文件 →
     * 随机挑一个 → 转存进自己的 temp 目录 → 缓存日期。
     *
     * @return 是否成功
     */
    public boolean refreshTempShare() {
        if (!isLoggedIn()) {
            Log.w(TAG, "refreshTempShare: not logged in");
            return false;
        }
        try {
            String tokenResp = http.post(shareTokenUrl(), buildShareTokenBody(shareId), apiHeaders());
            String stoken = parseStoken(tokenResp);
            if (TextUtils.isEmpty(stoken)) {
                Log.w(TAG, "refreshTempShare: no stoken");
                return false;
            }
            String encoded = urlEncode(stoken);

            // 第一次：pdir_fid=0，拿 share.first_fid
            String first = http.get(shareDetailUrl(shareId, encoded, "0"), apiHeaders());
            String firstFid = parseFirstFid(first);
            if (TextUtils.isEmpty(firstFid)) {
                Log.w(TAG, "refreshTempShare: no first_fid");
                return false;
            }
            // 第二次：用 first_fid 列目录，拿真正的文件
            String detail = http.get(shareDetailUrl(shareId, encoded, firstFid), apiHeaders());
            List<DirEntry> files = parseList(detail);
            if (files.isEmpty()) {
                Log.w(TAG, "refreshTempShare: share list empty");
                return false;
            }
            String target = ensureTempDir();
            if (TextUtils.isEmpty(target)) {
                Log.w(TAG, "refreshTempShare: no temp dir");
                return false;
            }
            DirEntry picked = files.get(randomIndex(files.size()));
            String saved = http.post(shareSaveUrl(),
                    buildSaveBody(picked.id, picked.token, target, shareId, urlDecode(encoded)),
                    apiHeaders());
            if (!isSaveOk(saved)) {
                Log.w(TAG, "refreshTempShare: save not ok");
                return false;
            }
            writeCache(brandTimeKey(), yyyyMMdd(System.currentTimeMillis()));
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "refreshTempShare failed", e);
            return false;
        }
    }

    /**
     * 删掉 temp 目录里的所有文件 —— 还原 {@code deleteUCTemp()}。
     *
     * @return 成功删除的条数；失败 0
     */
    public int deleteTempFiles() {
        if (!isLoggedIn()) {
            return 0;
        }
        int n = 0;
        try {
            String idx = http.get(rootSortUrl(), apiHeaders());
            String dirFid = findDirFid(idx, brandTempDir());
            if (TextUtils.isEmpty(dirFid)) {
                return 0;
            }
            String inner = http.get(subdirSortUrl(dirFid), apiHeaders());
            for (DirEntry d : parseList(inner)) {
                if (d.isDir) {
                    continue;
                }
                String resp = http.post(deleteUrl(), buildDeleteBody(d.id), apiHeaders());
                if (!TextUtils.isEmpty(resp)) {
                    n++;
                }
            }
            writeCache(brandDeleteTimeKey(), yyyyMMdd(System.currentTimeMillis()));
        } catch (Throwable e) {
            Log.e(TAG, "deleteTempFiles failed", e);
        }
        return n;
    }

    /**
     * 定期检查 —— 还原 {@code checktime()}：缓存日期不是今天就刷新一次转存。
     *
     * @return 是否触发了刷新
     */
    public boolean checktime() {
        String cached = readCache(brandTimeKey());
        if (!TextUtils.isEmpty(cached) && !isStale(cached)) {
            return false;
        }
        refreshTempShare();
        return true;
    }

    /**
     * 定期清理 —— 还原 {@code deletetime()}：缓存日期距今 ≥ 7 天才清理。
     *
     * @return 是否触发了清理
     */
    public boolean deletetime() {
        String cached = readCache(brandDeleteTimeKey());
        if (!TextUtils.isEmpty(cached) && !isOlderThanDays(cached, 7)) {
            return false;
        }
        deleteTempFiles();
        return true;
    }

    // ─────────────────────────────────────────────────────────────
    // 纯函数：日期与缓存
    // ─────────────────────────────────────────────────────────────

    /** {@code yyyyMMdd}（上游用 {@code SimpleDateFormat("yyyyMMdd")}） */
    public static String yyyyMMdd(long millis) {
        return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                .format(new java.util.Date(millis));
    }

    /**
     * 缓存日期是否"不是今天" —— 还原 {@code checktime()} 的
     * {@code Integer.parseInt(cached) >= Integer.parseInt(today)} 判断。
     *
     * <p>返回 true 表示需要刷新。
     *
     * @param cached 缓存文本（可能带噪音）
     * @return 是否需要刷新
     */
    public static boolean isStale(String cached) {
        long c = parseYyyyMMdd(cached);
        long today = parseYyyyMMdd(yyyyMMdd(System.currentTimeMillis()));
        return c < today;
    }

    /**
     * 缓存日期距今是否 ≥ days 天 —— 还原 {@code deletetime()} 的
     * {@code (now - parse(cached)) / 86400000 &lt; 7} 判断。
     *
     * <p><b>上游缺陷</b>：直接 {@code substring(0,4)/(4,6)/(6,8)} 再 parseInt，
     * 缓存被截断（&lt;8 位）或不是数字时抛 {@code NumberFormatException}。
     * 本实现改成"解析不出来就当已过期"，语义上只会更积极地清理。
     *
     * @param cached 缓存文本
     * @param days   天数阈值
     * @return 是否已过期到需要清理
     */
    public static boolean isOlderThanDays(String cached, int days) {
        long ts = parseYyyyMMddMillis(cached);
        if (ts <= 0L) {
            return true;
        }
        long elapsedDays = (System.currentTimeMillis() - ts) / DAY_MS;
        return elapsedDays >= days;
    }

    /** 把 {@code yyyyMMdd} 解析成可比大小的整数；非法返回 0 */
    public static long parseYyyyMMdd(String s) {
        String t = s == null ? "" : s.replaceAll("[^0-9]", "");
        if (t.length() < 8) {
            return 0L;
        }
        try {
            return Long.parseLong(t.substring(0, 8));
        } catch (Throwable e) {
            return 0L;
        }
    }

    /** 把 {@code yyyyMMdd} 解析成毫秒时间戳；非法返回 0 */
    public static long parseYyyyMMddMillis(String s) {
        String t = s == null ? "" : s.replaceAll("[^0-9]", "");
        if (t.length() < 8) {
            return 0L;
        }
        try {
            int y = Integer.parseInt(t.substring(0, 4)) - 1900;
            int m = Integer.parseInt(t.substring(4, 6)) - 1;
            int d = Integer.parseInt(t.substring(6, 8));
            return new java.util.Date(y, m, d).getTime();
        } catch (Throwable e) {
            return 0L;
        }
    }

    /** 读缓存文件（上游 {@code k.a(getCache(key))}） */
    public String readCache(String key) {
        return PanCreds.readFile(PanCreds.path(key));
    }

    /** 写缓存文件（上游 {@code k.c(getCache(key), value)}） */
    public boolean writeCache(String key, String value) {
        return PanCreds.writeFile(PanCreds.path(key), value);
    }

    /** URL 编码 */
    public static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(nz(s), "UTF-8");
        } catch (Throwable e) {
            return nz(s);
        }
    }

    /** URL 解码 */
    public static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(nz(s), "UTF-8");
        } catch (Throwable e) {
            return nz(s);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 内部工具
    // ─────────────────────────────────────────────────────────────

    private List<Vod> toVods(List<DirEntry> entries) {
        List<Vod> list = new ArrayList<>();
        if (entries == null) {
            return list;
        }
        for (DirEntry d : entries) {
            if (d == null || TextUtils.isEmpty(d.name)) {
                continue;
            }
            list.add(new Vod()
                    .id(d.id)
                    .name(d.name)
                    .pic(iconFor(d.isDir))
                    .remarks(d.isDir ? "文件夹" : humanSize(d.size)));
        }
        return list;
    }

    private int randomIndex(int bound) {
        java.util.Random r = random;
        if (r == null) {
            r = new java.util.Random();
        }
        return r.nextInt(bound);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
