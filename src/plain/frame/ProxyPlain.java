package com.example.tvbox.plain.frame;

import com.example.tvbox.common.Http;
import com.example.tvbox.crawler.Spider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地代理分发器（Proxy）—— 把 {@code /proxy?do=<动作>} 路由到各源的处理器。
 *
 * <h2>职责</h2>
 * 上游的 {@code Proxy} 是一个<b>中央调度器</b>，本身不产出内容。它做两件事：
 * <ol>
 *   <li>在 9978..9999 端口段探测宿主内置的本地 HTTP 服务，缓存端口</li>
 *   <li>把 {@code do=} 参数分发给 17 个静态处理器（弹幕、B 站、酷狗、网盘……）</li>
 * </ol>
 *
 * <h2>重写时的设计改变（重要）</h2>
 * 上游用 <b>硬编码 if-else 链</b> 直接调用 {@code Danmu.xxx / Bili.proxy / Hxq.proxy …}。
 * 这带来两个问题：编译器把 17 个类全部拖进依赖，任何一类缺失就整链 {@code NoClassDefFoundError}；
 * 加一个动作要改这个类。
 *
 * <p>本重写改为 <b>注册表</b>：每个源在静态初始化时 {@link #register(String, Handler)} 自己，
 * 分发器只认 {@code Map<String, Handler>}。未注册的动作返回 {@code null}（与上游语义一致），
 * 且互不牵连。测试可任意注册桩。
 *
 * <h2>端口探测的上游缺陷</h2>
 * 上游 {@code for(i=9978; i<10000; i++)} 用 {@code l(url,null).equals("ok")} 判定 —— 但
 * {@code l()} 在网络异常时返回 {@code null}，{@code null.equals(...)} 会 NPE，
 * 整个探测循环直接抛出而不是继续下一个端口。已改为 {@code "ok".equals(...)} 并把异常吃掉。
 *
 * <h2>响应契约</h2>
 * 宿主期望 {@code Object[]{Integer status, String contentType, InputStream body}}。
 *
 * <h2>证据</h2>
 * 反编译原文: {@code ae/decompiled/Proxy.java}
 
 *
 * <h2>未覆盖的分支</h2>
 * <ol>
 *   <li><b>端口探测</b>：{@code getPort()} 在 {@code 9978..10000} 内探测，
 *       全被占用或宿主禁网时的行为未覆盖（上游直接失败）。</li>
 *   <li><b>动作处理器</b>：本类只做注册表与分发；各动作的实现分散在各自域里
 *       （弹幕/网盘/解析…）。测试里注册的是替身，
 *       <b>"某个动作没人注册"这条路径的真机表现为 404</b>，不在本类断言范围。</li>
 *   <li><b>真实 socket 服务</b>：本类不管监听与并发（宿主负责），
 *       因此"请求打到一半被取消""并发同名动作"未覆盖。</li>
 *   <li><b>未还原</b>：上游 17 个动作名与各自签名的完整清单（本类保留注册式同名分发）。</li>
 * </ol>
 *
 * <h2>记录的上游缺陷（本重写修正的就是它）</h2>
 * <ol>
 *   <li><b>硬编码 if-else 直调 17 个源类</b>：任何一类缺失就 {@code NoClassDefFoundError}
 *       整链崩（缺失的类是"另一个插件"时尤其常见）；本类改成
 *       {@code register/unregister} 注册表，缺失的动作退化为"未注册"而不是崩溃。</li>
 *   <li><b>端口硬编码</b>：上游把 9978 写死在弹幕/播放地址里，与宿主实际监听端口
 *       不一致时全站弹幕静默失效；本类保留探测区间并提供 {@code setPortForTest} 注入。</li>
 * </ol>
 *
 * <h2>证据来源</h2>
 * 宿主类反编译原文 {@code ae/decompiled/Proxy.java}（{@code do=<动作>} 分发链、
 * 17 个硬编码调用点、端口常量与 {@code getUrl()} 拼接）。
*/
public class ProxyPlain extends Spider {

    private static final String TAG = "ProxyPlain";

    /** 端口探测区间（含左不含右），还原自上游 {@code 9978..10000} */
    public static final int PORT_MIN = 9978;
    public static final int PORT_MAX = 10000;

    /** 探测路径：本地服务对 {@code do=ck} 应答 {@code ok} */
    public static final String PROBE_PATH = "/proxy?do=ck";
    public static final String PROBE_BODY = "ok";

    /** 探测结果缓存。{@code -1} = 未探测；{@code 0} = 探测过但没找到 */
    private static volatile int port = -1;

    /** 动作 → 处理器 */
    private static final Map<String, Handler> HANDLERS = new LinkedHashMap<>();

    /** 本地代理处理器：给定请求参数，返回宿主的 {@code Object[3]} */
    public interface Handler {
        Object[] handle(Map<String, String> params);
    }

    // ── 注册表 ───────────────────────────────────────────────

    /** 注册（或覆盖）一个动作处理器。线程安全，可重复调用。 */
    public static synchronized void register(String action, Handler handler) {
        if (action == null || action.isEmpty() || handler == null) {
            return;
        }
        HANDLERS.put(action, handler);
    }

    /** 注销一个动作，返回是否曾经存在。 */
    public static synchronized boolean unregister(String action) {
        return HANDLERS.remove(action) != null;
    }

    /** 已注册的动作，按注册顺序。 */
    public static synchronized java.util.List<String> actions() {
        return new java.util.ArrayList<>(HANDLERS.keySet());
    }

    /** 清空注册表 —— 仅供测试隔离，生产代码不要调用。 */
    public static synchronized void clearHandlersForTest() {
        HANDLERS.clear();
    }

    // ── 端口探测 ─────────────────────────────────────────────

    /**
     * 探测本地服务端口并缓存。已探测过则立即返回。
     * 上游在异常时会中断整个循环，这里逐个端口独立容错。
     */
    public static synchronized void discover() {
        if (port > 0) {
            return;
        }
        Http http = Http.create().timeout(500, 1_000).retry(0);
        for (int candidate = PORT_MIN; candidate < PORT_MAX; candidate++) {
            String probe = "http://127.0.0.1:" + candidate + PROBE_PATH;
            String body;
            try {
                body = http.get(probe);
            } catch (RuntimeException e) {
                continue;   // 单个端口失败不影响后续端口（上游此处会整体中断）
            }
            if (PROBE_BODY.equals(body)) {
                port = candidate;
                return;
            }
        }
        port = 0;
    }

    /** 本地服务端口；未找到时为 {@code 0}。 */
    public static int getPort() {
        discover();
        return port;
    }

    /** {@code http://127.0.0.1:<port>}；未找到时为 {@code http://127.0.0.1:0}。 */
    public static String getOriginUrl() {
        return "http://127.0.0.1:" + getPort();
    }

    /** {@code http://127.0.0.1:<port>/proxy}；未找到时为 {@code http://127.0.0.1:0/proxy}。 */
    public static String getUrl() {
        return getOriginUrl() + "/proxy";
    }

    /** 把 {@code http://127.0.0.1:<port>/} 换成别的端口 —— 供测试注入确定端口。 */
    public static void setPortForTest(int p) {
        port = p;
    }

    // ── 分发 ─────────────────────────────────────────────────

    /**
     * 宿主入口。{@code params} 至少含 {@code do}；其余键原样交给处理器。
     *
     * @return {@code Object[3]} 或 {@code null}（动作未知 / 无 {@code do}）
     */
    @Override
    public Object[] proxy(Map<String, String> params) {
        return dispatch(params);
    }

    /** 与 {@link #proxy(Map)} 同义，便于静态调用与测试。 */
    public static Object[] dispatch(Map<String, String> params) {
        if (params == null) {
            return null;
        }
        String action = params.get("do");
        if (action == null) {
            return null;
        }
        if ("ck".equals(action)) {
            // 上游把 ck 写死在 dispatch 里；保留同样行为，但同样允许被注册表覆盖
            Handler override = lookup(action);
            if (override != null) {
                return override.handle(params);
            }
            return text(200, PROBE_BODY);
        }
        Handler handler = lookup(action);
        if (handler == null) {
            return null;
        }
        try {
            return handler.handle(params);
        } catch (RuntimeException e) {
            System.err.println("[" + TAG + "] action=" + action + " failed: " + e);
            return text(500, "");
        }
    }

    private static synchronized Handler lookup(String action) {
        return HANDLERS.get(action);
    }

    // ── 响应构造 ─────────────────────────────────────────────

    /** 构造宿主需要的 {@code Object[]{status, contentType, InputStream}}。 */
    public static Object[] response(int status, String contentType, String body) {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        return new Object[]{status, contentType, new ByteArrayInputStream(bytes)};
    }

    /** 纯文本 UTF-8 响应。 */
    public static Object[] text(int status, String body) {
        return response(status, "text/plain; charset=utf-8", body);
    }

    /** JSON 响应。 */
    public static Object[] json(String body) {
        return response(200, "application/json; charset=utf-8", body);
    }

    /**
     * 把 {@code Object[]} 里的 body 读成字符串 —— 测试与断言用。
     * 非 {@code Object[3]}、body 不是 {@link InputStream} 时返回 {@code null}。
     */
    public static String bodyOf(Object[] result) {
        if (result == null || result.length < 3 || !(result[2] instanceof InputStream)) {
            return null;
        }
        try (InputStream in = (InputStream) result[2]) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** 便捷：从参数 Map 造一个可变副本。 */
    public static Map<String, String> params(String... kv) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }
}
