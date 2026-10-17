package com.example.tvbox.plain.yunduo;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/**
 * YunDuo {@code /decode/url} 用到的 protobuf 编解码最小实现。
 *
 * <p>反编译原文: {@code ae/decompiled/YunDuo.java} 的 {@code g(int,byte[])} /
 * {@code h(long)} / {@code i(int,long)} 三个私有方法（第 255–281 行）与 {@code b()} 里的响应扫描
 * （第 96–149 行）。上游靠宿主内置的 protobuf 运行时，这里手写 varint，
 * 不引任何依赖。
 *
 * <h2>请求帧（字段号 → 类型 → 含义）</h2>
 * <pre>
 *   1  length-delimited  url       播放地址（或需要二次解码的地址）
 *   2  length-delimited  source    线路标识（上游 {@code from}）
 *   3  varint            time      毫秒时间戳（int64）
 *   4  length-delimited  nonce     16 字节随机数的 32 位小写 hex
 *   5  length-delimited  sign      SHA-256 大写 hex，见 {@link #signInput}
 *   6  length-delimited  dev       客户端标识，默认 {@code com.web.player}
 *   7  varint            1         常量
 * </pre>
 *
 * <h2>响应帧</h2>
 * <p>上游不解析完整消息，只线性扫字段：遇到 <b>字段号 3 且 wire type 2</b> 的
 * length-delimited 段，取它的 UTF-8 文本，若以 {@code http} 开头就返回。
 * 其余字段按 wire type 跳过（0/1/2/5）。
 *
 * <p><b>与上游的等价性（已逐行核对，不是推断）</b>：上游 {@code YunDuo.b()} 的扫描循环
 * 对 {@code wire type} 0/1/2/5 的跳过长度分别是「读完 varint」「+8」「+长度」「+4」，
 * 遇到 3/4/6/7 就 {@code break}；本类 {@link #scanField3String} 与之等价。
 * （此前一份草稿曾怀疑上游 varint 分支少跳 1 字节 —— <b>核对后证伪</b>：
 * 上游 {@code i = i5 + 1} / {@code i5 = i + 1} 这两步合起来正好越过 1 字节 varint 的值。
 * 证据: {@code ev_c33b5265e9a46b5bdca0869f8da153bf57ec78e686b3d0ed5ca91305762a1d3d}）
 */
public final class YunDuoProto {

    /** protobuf wire type */
    public static final int WIRE_VARINT = 0;
    public static final int WIRE_64BIT = 1;
    public static final int WIRE_LENGTH = 2;
    public static final int WIRE_32BIT = 5;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private YunDuoProto() {
    }

    /**
     * 编码一个 varint。
     *
     * @param value 非负数值（按无符号 7 位一组输出）
     * @return varint 字节
     */
    public static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int low = (int) (127L & value);
            value >>>= 7;
            if (value == 0) {
                out.write(low);
                return out.toByteArray();
            }
            out.write(low | 128);
        }
    }

    /**
     * 编码一个 length-delimited 字段（wire type 2）。
     *
     * @param field 字段号
     * @param data  负载
     * @return 完整字段字节：tag + 长度 + 负载
     */
    public static byte[] bytesField(int field, byte[] data) {
        byte[] payload = data == null ? new byte[0] : data;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAll(out, varint(((long) field << 3) | WIRE_LENGTH));
        writeAll(out, varint(payload.length));
        writeAll(out, payload);
        return out.toByteArray();
    }

    /**
     * 编码一个 length-delimited 字段（UTF-8 字符串版）。
     *
     * @param field 字段号
     * @param text  文本，null 视为空串
     * @return 完整字段字节
     */
    public static byte[] bytesField(int field, String text) {
        return bytesField(field, (text == null ? "" : text).getBytes(UTF8));
    }

    /**
     * 编码一个 varint 字段（wire type 0）。
     *
     * @param field 字段号
     * @param value 值
     * @return 完整字段字节：tag + varint
     */
    public static byte[] varintField(int field, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAll(out, varint((long) field << 3));
        writeAll(out, varint(value));
        return out.toByteArray();
    }

    /**
     * 拼若干个已编码字段。
     *
     * @param parts 字段字节
     * @return 拼接结果
     */
    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            writeAll(out, p == null ? new byte[0] : p);
        }
        return out.toByteArray();
    }

    /**
     * 从响应里扫「字段号 3 且 wire type 2」的字符串，返回第一个以 {@code http} 开头的。
     *
     * <p>与上游不同：按 wire type 正确跳过每段（上游 varint 分支会少跳 1 字节）。
     * 遇到非法 wire type（3/4/6/7）停止扫描 —— 上游遇到 3/4/6/7 也是 {@code break}，
     * 只有 1 与 5 的跳过长度一致。
     *
     * @param data 响应字节
     * @return 地址；没找到返回空串
     */
    public static String scanField3String(byte[] data) {
        if (data == null) {
            return "";
        }
        int pos = 0;
        int len = data.length;
        while (pos < len) {
            long tag = 0;
            int shift = 0;
            int p = pos;
            while (true) {
                if (p >= len) {
                    return "";
                }
                byte b = data[p++];
                tag |= ((long) (b & 127)) << shift;
                shift += 7;
                if ((b & 128) == 0) {
                    break;
                }
                if (shift > 63) {
                    return "";
                }
            }
            long fieldNo = tag >>> 3;
            int wire = (int) (tag & 7L);
            pos = p;
            if (wire == WIRE_VARINT) {
                while (pos < len && (data[pos] & 128) != 0) {
                    pos++;
                }
                pos++;
            } else if (wire == WIRE_LENGTH) {
                long size = 0;
                int s = 0;
                while (true) {
                    if (pos >= len) {
                        return "";
                    }
                    byte b = data[pos++];
                    size |= ((long) (b & 127)) << s;
                    s += 7;
                    if ((b & 128) == 0) {
                        break;
                    }
                    if (s > 63) {
                        return "";
                    }
                }
                if (size < 0 || pos + size > len) {
                    return "";
                }
                if (fieldNo == 3L) {
                    String text = new String(data, pos, (int) size, UTF8);
                    if (text.startsWith("http")) {
                        return text;
                    }
                }
                pos += (int) size;
            } else if (wire == WIRE_64BIT) {
                pos += 8;
            } else if (wire == WIRE_32BIT) {
                pos += 4;
            } else {
                return "";
            }
        }
        return "";
    }

    /**
     * 上游 {@code b()} 的签名输入格式串。
     *
     * @param finger {@code id} 常量
     * @param dev    客户端标识
     * @param nonce  随机 nonce（32 位小写 hex）
     * @param sk     {@code sk} 常量
     * @param time   毫秒时间戳
     * @return 待 SHA-256（再转大写）的原文
     */
    public static String signInput(String finger, String dev, String nonce, String sk, long time) {
        return String.format("finger=%s&id=%s&nonce=%s&sk=%s&time=%d&v=1", finger, dev, nonce, sk, time);
    }

    /**
     * 上游 {@code h()} 的 varint 文本形式（便于断言）。
     *
     * @param value 值
     * @return 十六进制串
     */
    public static String hex(byte[] value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void writeAll(ByteArrayOutputStream out, byte[] data) {
        out.write(data, 0, data.length);
    }
}
