package com.example.tvbox.common;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 跨 {@code org.json} 版本安全的键遍历。
 *
 * <p><b>为什么需要这个类</b>
 *
 * <p>`org.json` 有两个互不兼容的世代：
 * <ul>
 *   <li><b>旧版</b>（Android 内置的 / 老 json-java）：只有
 *       {@code Iterator keys()} 和 {@code JSONArray names()}，**没有** {@code keySet()}。</li>
 *   <li><b>新版</b>（现代 json-java / 本项目原先的桩）：有 {@code Set&lt;String&gt; keySet()}。</li>
 * </ul>
 *
 * <p>本项目的桩当年照抄的是**新版**，于是 {@code obj.keySet()} 在本地编译通过、
 * 单元测试全绿，而到真宿主上直接炸：
 * <pre>
 *   java.lang.NoSuchMethodError: 'java.util.Set org.json.JSONObject.keySet()'
 *   [jar] spider fail key=独播影视 api=csp_Duboku
 * </pre>
 * 实测宿主（fmplayer.app 的 {@code gateway.jar}）自带的是**旧版**。
 *
 * <p>修法与纪律：**只用两代都有的 API**。
 * {@code names()} 是交集里唯一"直接给出键集合"的方法（两代都有），
 * 所以本类以它为主路径，并对"返回 null 表示空对象"这一旧版语义做兜底。
 */
public final class JsonKeys {

    private JsonKeys() {}

    /**
     * 取一个 {@code JSONObject} 的全部键名。
     *
     * <p>返回的 List 是**稳定快照**：调用方遍历期间修改原对象不会抛
     * {@code ConcurrentModificationException}，也不会看到半途的键。
     *
     * @param obj 目标对象，可为 null
     * @return 键名列表；对象为 null 或没有键时返回空列表（**永不返回 null**）
     */
    public static List<String> of(JSONObject obj) {
        if (obj == null) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        try {
            // names() 两代都有；旧版在"没有键"时返回 null（而不是空数组），必须判空
            JSONArray names = obj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i, null);
                    if (k != null) out.add(k);
                }
                return out;
            }
        } catch (Throwable ignored) {
            // 落到下面的 keys() 兜底
        }
        try {
            // keys() 两代都有；旧版返回原始 Iterator（未泛型化），不能直接写 for-each
            Iterator<?> it = obj.keys();
            while (it != null && it.hasNext()) {
                Object k = it.next();
                if (k != null) out.add(String.valueOf(k));
            }
        } catch (Throwable ignored) {
            // 真的取不到就返回已拿到的部分
        }
        return out;
    }

    /**
     * 对象是否有键。
     *
     * @param obj 目标对象，可为 null
     * @return 有键返回 true；null / 空对象返回 false
     */
    public static boolean isEmpty(JSONObject obj) {
        return of(obj).isEmpty();
    }
}
