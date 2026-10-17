package com.example.tvbox.plain.film;

import com.example.tvbox.common.AesCbc;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 厂长资源（czs）的字幕处理器 —— 把加密的 SRT 字节流转成可用的 WebVTT 文本。
 *
 * <h2>为什么字幕需要"处理器"</h2>
 * 站点给的不是普通 {@code .vtt}，而是一坨 AES 加密字节：
 * <pre>
 *   [ 0 .. 16 )   IV（明文，固定 16 字节）
 *   [ 16 .. 末尾 ) AES/CBC/PKCS5 密文，解出来是 SubRip（.srt）文本
 * </pre>
 * 也就是说 IV 直接拼在密文头部。解密后还要把 SRT 洗成 VTT：SRT 的时间戳是
 * {@code mm:ss,mmm}（逗号），VTT 要 {@code hh:mm:ss.mmm}（点），且 VTT 需要序号块。
 *
 * <h2>上游的洗数据链（逐条保留语义）</h2>
 * <ol>
 *   <li>砍掉时间范围之后的所有内容（站点在后面塞了坐标/样式）</li>
 *   <li>{@code mm:ss.mmm} → {@code 00:mm:ss.mmm}</li>
 *   <li>剥掉成对标签包裹，如 {@code <font><b>文本</b></font>} → {@code 文本}</li>
 *   <li>删掉 HTML 实体，如 {@code &nbsp;}、{@code &amp;}</li>
 *   <li>删掉 {@code NOTE} 行（VTT 注释块）</li>
 * </ol>
 *
 * <h2>修正的上游缺陷</h2>
 * <ol>
 *   <li><b>空列表下标越界（会崩）</b>：上游在编号循环里用
 *       {@code ((String) arrayList.get(arrayList.size() - 1)).trim().isEmpty()}
 *       判断"上一条是空行"，但<b>第一行如果就是时间戳</b>（有些 SRT 没有前导空行），
 *       {@code arrayList} 还是空的，{@code get(-1)} 直接抛
 *       {@code IndexOutOfBoundsException}，整段字幕功能失效。
 *       已改为"列表为空也算一个块的开始"。</li>
 *   <li><b>缺 {@code WEBVTT} 头</b>：上游只在 404 兜底路径里返回 {@code "WEBVTT"}，
 *       正常路径输出的文本<b>没有文件头</b>。绝大多数播放器会拒绝加载没有头的 VTT。
 *       已统一补头（若原文已含则不重复添加）。</li>
 *   <li><b>解密失败静默返回 null</b>：上游 {@code loadSub} 出错时
 *       {@code return null}，宿主拿到 {@code null} 会当成"没有字幕"，用户只看到
 *       "字幕没出来"而看不到原因。这里改为返回只含头部的合法空字幕 + 日志。</li>
 *   <li><b>用 {@code PKCS7Padding}</b>：Android 专有名字，桌面 JCE 不认。
 *       已统一走 {@link AesCbc}（内部用等价的 {@code PKCS5Padding}）。</li>
 * </ol>
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Czsapp.java}（{@code loadSub} 方法）
 */
public final class CzsSubtitle {

    /** VTT 文件头 */
    public static final String VTT_HEADER = "WEBVTT";

    /** 时间戳行判定：{@code 00:00:01.000 --> 00:00:04.000} */
    public static final Pattern RE_TIME_RANGE =
            Pattern.compile("\\d{2}:\\d{2}:\\d{2}.\\d{3}.+\\d{2}:\\d{2}:\\d{2}.\\d{3}");

    /** 1. 砍掉时间范围之后的内容 */
    private static final Pattern STRIP_AFTER_TIME =
            Pattern.compile("(\\d{2}:\\d{2}:\\d{2}.\\d{3}.+\\d{2}:\\d{2}:\\d{2}.\\d{3}).*");

    /** 2. {@code mm:ss.mmm} → {@code 00:mm:ss.mmm} */
    private static final Pattern PAD_HOURS =
            Pattern.compile("(\\d{2}:\\d{2}.\\d{3})( --> )(\\d{2}:\\d{2}.\\d{3}).*");

    /** 3. 剥掉成对标签包裹 */
    private static final Pattern UNWRAP_TAGS =
            Pattern.compile("<.*><.*>(.*)<.*><.*>");

    /** 4. 删掉 HTML 实体 */
    private static final Pattern STRIP_ENTITY = Pattern.compile("&(.*);");

    /** 5. 删掉 NOTE 行 */
    private static final Pattern STRIP_NOTE = Pattern.compile(".*NOTE.*");

    private CzsSubtitle() {
    }

    // ── 主流程 ───────────────────────────────────────────────

    /**
     * 解密字幕字节流。
     *
     * @param data 前置 IV + AES/CBC 密文
     * @param key  密钥字节
     * @return 解密后的 SRT 文本；失败返回 {@code null}
     */
    public static String decrypt(byte[] data, byte[] key) {
        return AesCbc.decryptIvPrefixedToString(data, key);
    }

    /**
     * 完整流程：密文字节 → VTT 文本。
     *
     * @param data 前置 IV + 密文
     * @param key  密钥
     * @return VTT 文本（含文件头）；永不为 {@code null}
     */
    public static String toVtt(byte[] data, byte[] key) {
        String srt = decrypt(data, key);
        if (srt == null) {
            return VTT_HEADER;
        }
        return toVttFromSrt(srt);
    }

    /**
     * SRT（或半成品 VTT）文本 → 规范 VTT 文本。
     *
     * @param srt 源文本，可为 null
     * @return VTT 文本（含文件头）；永不为 {@code null}
     */
    public static String toVttFromSrt(String srt) {
        if (srt == null) {
            return VTT_HEADER;
        }
        String cleaned = srt
                .replaceAll(STRIP_AFTER_TIME.pattern(), "$1")
                .replaceAll(PAD_HOURS.pattern(), "00:$1$200:$3")
                .replaceAll(UNWRAP_TAGS.pattern(), "$1")
                .replaceAll(STRIP_ENTITY.pattern(), "")
                .replaceAll(STRIP_NOTE.pattern(), "");
        return ensureHeader(numberCues(cleaned));
    }

    /**
     * 给每个时间轴块补序号。
     *
     * <p>编号规则：遇到时间戳行，且（列表为空 <b>或</b> 上一条是空行）时，
     * 先插入一个递增序号。
     *
     * @param text 已清洗的多行文本
     * @return 带序号的文本；失败时返回原文本
     */
    public static String numberCues(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        List<String> out = new ArrayList<>();
        int index = 1;
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // ★ 上游此处对空列表调用 get(size-1) 会越界
                boolean atBlockStart = out.isEmpty()
                        || out.get(out.size() - 1).trim().isEmpty();
                if (RE_TIME_RANGE.matcher(line).matches() && atBlockStart) {
                    out.add(String.valueOf(index++));
                }
                out.add(line);
            }
        } catch (Exception e) {
            return text;
        }
        return join(out, "\n");
    }

    /**
     * 确保文本以 {@code WEBVTT} 开头。
     *
     * @param text 文本，可为 null
     * @return 带头的文本
     */
    public static String ensureHeader(String text) {
        String t = text == null ? "" : text;
        String head = t.trim();
        if (head.startsWith(VTT_HEADER)) {
            return t;
        }
        if (t.isEmpty()) {
            return VTT_HEADER;
        }
        return VTT_HEADER + "\n" + t;
    }

    /** 404 兜底：只含头部的合法空字幕。 */
    public static String emptyVtt() {
        return VTT_HEADER;
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
