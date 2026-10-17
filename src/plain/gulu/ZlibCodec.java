package com.example.tvbox.plain.gulu;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 原始 zlib/deflate 编解码（Gulu 协议的第 2 层）。
 *
 * <h2>职责</h2>
 * 上游 {@code Gulu.GuluCrypto.d(byte[], int, boolean)} / {@code e(byte[], boolean)}
 * 的等价实现：protobuf 报文在加密<b>之前</b>先 deflate，解密<b>之后</b>再 inflate。
 * 两层顺序不能颠倒，否则 AES 的输入长度/内容就全错了。
 *
 * <h2>参数取值的证据</h2>
 * <table border="1">
 *   <caption>上游两处调用点</caption>
 *   <tr><th>调用点</th><th>level</th><th>nowrap</th><th>含义</th></tr>
 *   <tr><td>握手请求 {@code handshake()}</td><td>5</td><td>false</td>
 *       <td>带 zlib 头（RFC1950）</td></tr>
 *   <tr><td>业务请求 {@code callApi()}</td><td>6</td><td>true</td>
 *       <td>裸 deflate（RFC1951），无 2 字节 zlib 头</td></tr>
 *   <tr><td>响应解压 {@code e(bytes, true/false)}</td><td>—</td><td>同步请求侧</td>
 *       <td>握手响应 true→false，业务响应 true</td></tr>
 * </table>
 * 即：<b>握手用 zlib 包装、业务用裸 deflate</b>。这一差异是协议里最容易搞错的一处。
 *
 * <h2>与上游的行为差异（安全化）</h2>
 * 上游 {@code inflate} 写的是 {@code while(!inflater.finished())
 * out.write(buf, 0, inflater.inflate(buf))}。当数据被截断时 {@code finished()} 永远为 false
 * 而 {@code inflate} 返回 0 → <b>死循环</b>。本实现遇到「无进展 / 需要更多输入」立即停止，
 * 返回已解出的部分。正常数据上的结果与上游逐字节一致。
 *
 * <h2>证据来源</h2>
 * 反编译原文: {@code ae/decompiled/Gulu.java:102-125}（{@code d}/{@code e}）。
 */
public final class ZlibCodec {

    /** 业务请求的压缩级别（上游 {@code callApi} 用 6）。 */
    public static final int LEVEL_BUSINESS = 6;
    /** 握手请求的压缩级别（上游 {@code handshake} 用 5）。 */
    public static final int LEVEL_HANDSHAKE = 5;

    /** 内部缓冲大小，与上游一致。 */
    private static final int CHUNK = 1024;

    private ZlibCodec() {
    }

    /**
     * deflate 压缩。
     *
     * @param data   原文，null 视作空
     * @param level  压缩级别 0..9（上游用 5 或 6）
     * @param nowrap true = 裸 deflate（RFC1951），false = zlib 包装（RFC1950）
     * @return 压缩结果，永不 null
     */
    public static byte[] deflate(byte[] data, int level, boolean nowrap) {
        Deflater deflater = new Deflater(level, nowrap);
        try {
            deflater.setInput(data == null ? new byte[0] : data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[CHUNK];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                if (n <= 0) {
                    break;
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * inflate 解压。
     *
     * @param data   压缩数据，null 视作空
     * @param nowrap true = 裸 deflate（RFC1951），false = zlib 包装（RFC1950）
     * @return 解压结果；数据损坏时返回已解出的前缀（不抛异常）
     */
    public static byte[] inflate(byte[] data, boolean nowrap) {
        byte[] src = data == null ? new byte[0] : data;
        Inflater inflater = new Inflater(nowrap);
        try {
            inflater.setInput(src);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[CHUNK];
            while (!inflater.finished()) {
                int n;
                try {
                    n = inflater.inflate(buf);
                } catch (Exception e) {
                    break;
                }
                if (n > 0) {
                    out.write(buf, 0, n);
                    continue;
                }
                // inflate 返回 0：要么需要更多输入，要么字典缺失。
                // 继续调用只会原地打转 —— 上游在这里会死循环，我们直接结束。
                if (inflater.needsInput() || inflater.needsDictionary()) {
                    break;
                }
                if (inflater.finished()) {
                    break;
                }
                break;
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /**
     * 便捷：握手侧的压缩（level 5 + zlib 包装）。
     *
     * @param data 原文
     * @return 压缩结果
     */
    public static byte[] deflateHandshake(byte[] data) {
        return deflate(data, LEVEL_HANDSHAKE, false);
    }

    /**
     * 便捷：业务侧的压缩（level 6 + 裸 deflate）。
     *
     * @param data 原文
     * @return 压缩结果
     */
    public static byte[] deflateBusiness(byte[] data) {
        return deflate(data, LEVEL_BUSINESS, true);
    }
}
