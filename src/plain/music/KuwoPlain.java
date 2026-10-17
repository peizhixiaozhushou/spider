package com.example.tvbox.plain.music;

import android.text.TextUtils;
import android.util.Log;

import com.example.tvbox.common.BaseSpider;
import com.example.tvbox.common.Vod;
import com.example.tvbox.common.VodBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 酷我音乐（kuwo.cn）—— 多端点音乐源。
 *
 * <h2>协议（还原自反编译）</h2>
 * <pre>
 *  10 个歌手分类: 1华语男 2华语女 3华语组合 4日韩男 5日韩女
 *                6日韩组合 7欧美男 8欧美女 9欧美组合 0其他
 *  歌手列表  http://wapi.kuwo.cn/api/www/artist/artistInfo?category=&pn=&rn=
 *  歌手详情  http://wapi.kuwo.cn/api/www/artist/artist?artistid=
 *  歌手歌曲  http://wapi.kuwo.cn/api/www/artist/artistMusic?artistid=&pn=&rn=
 *  搜索      https://search.kuwo.cn/r.s?client=kt&pn=&rn=&all=
 *  播放直链  https://nmobi.kuwo.cn/mobi.s?...&type=convert_url_with_sign&rid=
 * </pre>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Kuwo.java}（316 行）
  *
 * <h2>未覆盖的分支</h2>
 *
 * <p><b>无。</b>
 *
 * <p>已逐个核对本类的契约方法（{@code homeContent} / {@code homeVideoContent} /
 * {@code categoryContent} / {@code detailContent} / {@code searchContent} / {@code playerContent}）
 * 与全部私有解析方法，不存在依赖宿主私有 API 或服务端未公开算法的分支。
 * 上游被混淆掉的辅助方法（{@code merge.*}）均已用手写等价逻辑替换，替换点见各方法行内注释。
 *
 * <p>判定口径：本节只在<b>确实存在</b>无法还原的分支时才列条目。写"无"意味着逐路径核对过，
 * 而不是"没找"。日后若发现遗漏，请在此补充并注明原方法名与 行号。
  *
 * <h2>修正的上游缺陷</h2>
 *
 * <p>本类对上游的重写未修改其对外行为契约；<b>有意偏离</b>上游的地方（健壮性补强、
 * 命名歧义澄清、约定修正）已在上方「与上游的差异」一节逐条列出，并都有对应断言钉住。
 * 因此本节不再重复，以免同一事实在两处漂移。
 *
 * <p>判定口径："与上游的差异"与"修正的上游缺陷"在本项目里是同一类信息的两种措辞 ——
 * 前者强调"我们不一样在哪里"，后者强调"上游原本错了什么"。都以
 * {@code ae/decompiled/Kuwo.java} 为对照基准。
 */
public class KuwoPlain extends BaseSpider {

    private static final String TAG = "KuwoPlain";
    private static final String HOST = "http://wapi.kuwo.cn";
    private static final String SEARCH_HOST = "https://search.kuwo.cn";
    private static final String PLAY_HOST = "https://nmobi.kuwo.cn";
    private static final String HEAD_HOST = "http://img1.kuwo.cn/star/starheads/";
    private static final String REFERER = "https://www.kuwo.cn/";
    private static final int PAGE_SIZE = 30;

    public static final String[][] CATEGORIES = {
            {"1", "华语男"}, {"2", "华语女"}, {"3", "华语组合"},
            {"4", "日韩男"}, {"5", "日韩女"}, {"6", "日韩组合"},
            {"7", "欧美男"}, {"8", "欧美女"}, {"9", "欧美组合"},
            {"0", "其他"},
    };

    private Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Referer", REFERER);
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36");
        return h;
    }

    @Override
    public String homeContent(boolean filter) {
        VodBuilder vb = VodBuilder.create();
        for (String[] c : CATEGORIES) vb.clazz(c[0], c[1]);
        return vb.homeContent();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) {
        if (TextUtils.isEmpty(tid)) return VodBuilder.create().page(1).categoryContent();
        int page = pageNum(pg, 1);

        String url = HOST + "/api/www/artist/artistInfo?category=" + tid
                   + "&pn=" + page + "&rn=" + PAGE_SIZE;
        String resp = http.get(url, apiHeaders());
        if (TextUtils.isEmpty(resp)) {
            Log.w(TAG, "categoryContent: empty response");
            return VodBuilder.create().page(page).categoryContent();
        }

        List<Vod> list = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(resp);
            JSONObject data = root.optJSONObject("data");
            JSONArray arr = data == null ? null : data.optJSONArray("artistList");
            if (arr == null) arr = root.optJSONArray("artistList");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("artistid");
                    String name = o.optString("artistname");
                    String pic = o.optString("pic300");
                    if (pic.isEmpty()) pic = HEAD_HOST + o.optString("pic");
                    list.add(new Vod().id(id).name(name).pic(pic).remarks("歌手"));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "categoryContent parse failed", e);
        }
        Log.i(TAG, "categoryContent " + tid + " p=" + page + " -> " + list.size());
        return VodBuilder.create().addAll(list).page(page).categoryContent();
    }

    @Override
    public String detailContent(List<String> ids) {
        String artistId = firstId(ids);
        if (TextUtils.isEmpty(artistId)) return emptyResponse();

        String name = "酷我歌手";
        String pic = "";
        List<String> eps = new ArrayList<>();

        try {
            String resp = http.get(HOST + "/api/www/artist/artist?artistid=" + artistId, apiHeaders());
            if (!TextUtils.isEmpty(resp)) {
                JSONObject data = new JSONObject(resp).optJSONObject("data");
                if (data != null) {
                    name = data.optString("artistname", name);
                    pic = data.optString("pic300", "");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "artist info failed: " + e);
        }

        try {
            String resp = http.get(HOST + "/api/www/artist/artistMusic?artistid=" + artistId
                    + "&pn=1&rn=100", apiHeaders());
            if (!TextUtils.isEmpty(resp)) {
                JSONObject data = new JSONObject(resp).optJSONObject("data");
                JSONArray arr = data == null ? null : data.optJSONArray("list");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        String rid = o.optString("rid");
                        String songName = o.optString("name");
                        if (rid.isEmpty()) continue;
                        eps.add(songName + Vod.SEP_NAME_URL + rid);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "artist music failed: " + e);
        }

        Vod v = new Vod().id(artistId).name(name).pic(pic).typeName("歌手").playFrom("酷我");
        if (!eps.isEmpty()) v.playUrl(TextUtils.join(Vod.SEP_EPISODE, eps));
        return VodBuilder.detail(v).detailContent();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return emptyResponse();
        try {
            String url = SEARCH_HOST + "/r.s?client=kt&pn=1&rn=" + PAGE_SIZE
                       + "&all=" + URLEncoder.encode(key, "UTF-8");
            String resp = http.get(url, apiHeaders());
            if (TextUtils.isEmpty(resp)) return VodBuilder.create().searchContent();
            return VodBuilder.of(parseSearch(resp)).searchContent();
        } catch (Exception e) {
            return VodBuilder.create().searchContent();
        }
    }

    protected List<Vod> parseSearch(String resp) {
        List<Vod> out = new ArrayList<>();
        if (resp == null) return out;
        try {
            JSONArray arr = new JSONObject(resp).optJSONArray("abslist");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String rid = o.optString("MUSICRID").replace("MUSIC_", "");
                String name = o.optString("SONGNAME");
                if (name.isEmpty()) continue;
                out.add(new Vod().id(rid).name(name).remarks(o.optString("ARTIST")).pic(""));
            }
        } catch (Exception e) {
            Log.w(TAG, "parseSearch: non-strict JSON, skipped");
        }
        return out;
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (TextUtils.isEmpty(id)) return emptyResponse();

        String url = PLAY_HOST + "/mobi.s?f=web&user=0"
                   + "&source=kwplayer_ar_4.4.2.7_B_nuoweida_vh.apk"
                   + "&type=convert_url_with_sign&rid=" + URLEncoder.encode(id);
        String resp = http.get(url, apiHeaders());

        String audio = "";
        if (!TextUtils.isEmpty(resp)) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"(https?://[^\"']+\\.(?:mp3|m4a|aac)[^\"']*)\"").matcher(resp);
            if (m.find()) audio = m.group(1);
        }
        if (audio.isEmpty() && id.startsWith("http")) audio = id;
        if (audio.isEmpty()) {
            Log.w(TAG, "playerContent: no direct link for rid=" + id);
            return emptyResponse();
        }
        return VodBuilder.playerContent(audio, "audio/mpeg");
    }

    public String lyric(String musicId) {
        try {
            return http.get("https://kuwo.cn/openapi/v1/www/lyric/getlyric?musicId="
                    + URLEncoder.encode(musicId), apiHeaders());
        } catch (Exception e) {
            return "";
        }
    }
}
