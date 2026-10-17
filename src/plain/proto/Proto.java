package com.example.tvbox.plain.proto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 最小 protobuf wire 编解码器（纯手写，零外部依赖）。
 *
 * <h2>为什么手写</h2>
 * 上游内嵌了自己的 {@code PbUtil}（见 {@code ae/decompiled/Gulu.java} 的
 * {@code Gulu.PbUtil}），只用到 protobuf 线格式的最小集合：varint / length-delimited
 * / tag / 嵌套消息。本项目 stubs 里<b>没有</b> {@code com.google.protobuf.*}，
 * 因此这里做等价实现；{@code Gulu} 整套协议（握手、boot、callApi）都构在它之上。
 *
 * <h2>证据来源</h2>
 * <ul>
 *   <li>{@code ae/decompiled/Gulu.java:311-463} —— {@code PbUtil.PbEncoder} / {@code PbUtil.PbField}
 *       / {@code PbUtil.decode} 的逐行行为（tag 编码、varint 无符号右移、wire type 1/5 跳过、
 *       wire type 3/4/6/7 终止、异常吞掉）。</li>
 *   <li>{@code ae/decompiled/QiShui.java} —— 只引用了
 *       {@code com.google.protobuf.DescriptorProtos.Edition.EDITION_2023_VALUE}（= 1000）
 *       这一个常量，<b>没有任何 wire 编解码</b>。见 {@code QiShuiPlain.EDITION_2023_VALUE}。</li>
 * </ul>
 *
 * <h2>与上游的行为差异（有意为之，安全化）</h2>
 * <ul>
 *   <li>上游 {@code decode} 把异常吞在循环里继续解析：遇到「负的 length 前缀」会
 *       {@code new byte[负数]} → {@code NegativeArraySizeException} → 游标不前进 →
 *       <b>死循环（DoS）</b>。本实现在任何不自洽字节处停止解析。差异只体现在畸形输入上，
 *       正常报文解析结果逐字段一致。</li>
 *   <li>上游 wire type 1/5 只做 {@code cursor += 8/4} 且不产出 {@link Field}；本实现相同，
 *       但先做边界校验，越界即停止。</li>
 *   <li>上游 {@code getAsBytes()} 在字段不是 bytes 时返回 {@code new byte[0]}，
 *       {@code getAsString()} 返回 {@code ""}，{@code getAsLong()} 返回 {@code 0}；
 *       {@link Field#asBytes()} / {@link Field#asString()} / {@link Field#asLong()} 语义一致。</li>
 * </ul>
 *
 * <h2>典型用法（编解码闭环）</h2>
 * <pre>{@code
 * byte[] msg = Proto.encode()
 *         .varint(1, 1700000000000L)
 *         .string(2, "hello")
 *         .bytes(3, new byte[0])                       // 显式空 bytes：2 字节 0x1A 0x00
 *         .message(4, Proto.encode().string(1, "in"))  // 嵌套消息
 *         .toByteArray();
 * for (Proto.Field f : Proto.decode(msg)) {
 *     if (f.number() == 2) System.out.println(f.asString());
 * }
 * }</pre>
 *
 * <p>无状态纯函数工具；{@link Encoder} 非线程安全，每次编码新建一个。
 */
public final class Proto {

    /** wire type 0：varint（整型） */
    public static final int WIRE_VARINT = 0;
    /** wire type 1：64 位定长（上游只跳过，不解析） */
    public static final int WIRE_FIXED64 = 1;
    /** wire type 2：length-delimited（字节串 / 字符串 / 嵌套消息） */
    public static final int WIRE_LEN = 2;
    /** wire type 5：32 位定长（上游只跳过，不解析） */
    public static final int WIRE_FIXED32 = 5;

    /** 共享空数组，避免每次 new。 */
    private static final byte[] EMPTY = new byte[0];

    private Proto() {
    }

    /**
     * 新建一个编码器。
     *
     * @return 空编码器
     */
    public static Encoder encode() {
        return new Encoder();
    }

    // ── 编码 ────────────────────────────────────────────────

    /**
     * protobuf 消息编码器。
     *
     * <p>与上游 {@code PbUtil.PbEncoder} 等价：{@code writeStr}/{@code writeBytes}/
     * {@code writeVar}/{@code writeTag}/{@code writeVarint}。当前类里语义化的命名对应：
     * <pre>
     *   string(f, s) == writeStr(f, s)
     *   bytes(f, b)  == writeBytes(f, b)
     *   varint(f, v) == writeVar(f, v)
     *   tag(f, w)    == writeTag(f, w)
     * </pre>
     * 空 bytes / 空串<b>照样写</b>（tag + 长度 0，共 2 字节）—— 上游也是这么做的，
     * 不能因为值是空就省掉字段。
     */
    public static final class Encoder {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        /**
         * 写 varint 字段（wire type 0）。
         *
         * @param field 字段号
         * @param value 值（可为负数，按 64 位无符号右移编码，最多 10 字节）
         * @return this
         */
        public Encoder varint(int field, long value) {
            tag(field, WIRE_VARINT);
            return varint(value);
        }

        /**
         * 写 length-delimited 字段（wire type 2）。
         *
         * @param field 字段号
         * @param value 字节串，null 视作空串（仍写出字段）
         * @return this
         */
        public Encoder bytes(int field, byte[] value) {
            tag(field, WIRE_LEN);
            byte[] v = value == null ? EMPTY : value;
            varint(v.length);
            out.write(v, 0, v.length);
            return this;
        }

        /**
         * 写 UTF-8 字符串字段。
         *
         * @param field 字段号
         * @param value 文本，null 视作空串
         * @return this
         */
        public Encoder string(int field, String value) {
            return bytes(field, (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        }

        /**
         * 写嵌套消息字段。
         *
         * @param field  字段号
         * @param nested 已构建好的子编码器，null 视作空消息
         * @return this
         */
        public Encoder message(int field, Encoder nested) {
            return bytes(field, nested == null ? null : nested.toByteArray());
        }

        /**
         * 只写 tag。
         *
         * @param field     字段号
         * @param wireType  wire type（只取低 3 位）
         * @return this
         */
        public Encoder tag(int field, int wireType) {
            return varint((((long) field) << 3) | (wireType & 7L));
        }

        /**
         * 写裸 varint（不带 tag）。
         *
         * @param value 值
         * @return this
         */
        public Encoder varint(long value) {
            long v = value;
            do {
                int b = (int) (v & 0x7F);
                v >>>= 7;
                if (v != 0) {
                    b |= 0x80;
                }
                out.write(b);
            } while (v != 0);
            return this;
        }

        /**
         * 写裸字节（不校验来自输入的数据）。
         *
         * @param raw 字节数组，null 忽略
         * @return this
         */
        public Encoder raw(byte[] raw) {
            if (raw != null && raw.length > 0) {
                out.write(raw, 0, raw.length);
            }
            return this;
        }

        /**
         * 已写字节数。
         *
         * @return 长度
         */
        public int size() {
            return out.size();
        }

        /**
         * 收尾，取出字节。
         *
         * @return 编码结果
         */
        public byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    // ── 字段 ────────────────────────────────────────────────

    /**
     * 一个已解析字段：字段号 + wire type + 值。
     *
     * <p>值为 {@link Long}（varint）或 {@code byte[]}（length-delimited）。
     * 取错类型时返回中性值而不是抛异常 —— 与上游 {@code PbField} 一致。
     */
    public static final class Field {

        private final int number;
        private final int wireType;
        private final Object value;

        /**
         * @param number   字段号
         * @param wireType wire type
         * @param value    {@link Long} 或 {@code byte[]}
         */
        public Field(int number, int wireType, Object value) {
            this.number = number;
            this.wireType = wireType;
            this.value = value;
        }

        /** @return 字段号 */
        public int number() {
            return number;
        }

        /** @return wire type */
        public int wireType() {
            return wireType;
        }

        /** @return 原始值（{@link Long} 或 {@code byte[]}） */
        public Object value() {
            return value;
        }

        /** @return 是否为 length-delimited 字段 */
        public boolean isBytes() {
            return value instanceof byte[];
        }

        /** @return varint 值；不是 varint 时返回 0 */
        public long asLong() {
            return value instanceof Long ? ((Long) value).longValue() : 0L;
        }

        /** @return 字节串；不是 length-delimited 时返回空数组（与上游一致） */
        public byte[] asBytes() {
            return value instanceof byte[] ? (byte[]) value : new byte[0];
        }

        /** @return UTF-8 文本；不是 length-delimited 时返回空串 */
        public String asString() {
            return value instanceof byte[] ? new String((byte[]) value, StandardCharsets.UTF_8) : "";
        }

        /** @return 把该字段当嵌套消息再解析一层；不是 length-delimited 时返回空列表 */
        public List<Field> asMessage() {
            return value instanceof byte[] ? decode((byte[]) value) : Collections.<Field>emptyList();
        }

        @Override
        public String toString() {
            String v = value instanceof byte[]
                    ? "bytes[" + ((byte[]) value).length + "]"
                    : String.valueOf(value);
            return number + "/" + wireType + "=" + v;
        }
    }

    // ── 解码 ────────────────────────────────────────────────

    /**
     * 解析一段 protobuf 字节为字段列表（保持出现顺序）。
     *
     * @param data 报文，可为 null/空
     * @return 字段列表；输入非法时返回已解析出的前缀（不抛异常）
     */
    public static List<Field> decode(byte[] data) {
        List<Field> out = new ArrayList<>();
        if (data == null || data.length == 0) {
            return out;
        }
        int cursor = 0;
        while (cursor < data.length) {
            int[] pos = {cursor};
            long tag;
            try {
                tag = readVarint(data, pos);
            } catch (RuntimeException e) {
                return out;
            }
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7L);
            try {
                if (wire == WIRE_VARINT) {
                    long v = readVarint(data, pos);
                    out.add(new Field(field, wire, Long.valueOf(v)));
                } else if (wire == WIRE_LEN) {
                    long len = readVarint(data, pos);
                    if (len < 0 || len > data.length - pos[0]) {
                        return out;
                    }
                    byte[] v = new byte[(int) len];
                    System.arraycopy(data, pos[0], v, 0, (int) len);
                    pos[0] += (int) len;
                    out.add(new Field(field, wire, v));
                } else if (wire == WIRE_FIXED64) {
                    if (pos[0] + 8 > data.length) {
                        return out;
                    }
                    pos[0] += 8;
                } else if (wire == WIRE_FIXED32) {
                    if (pos[0] + 4 > data.length) {
                        return out;
                    }
                    pos[0] += 4;
                } else {
                    // wire type 3/4（组）/6/7：上游直接 break，这里同样停止
                    return out;
                }
            } catch (RuntimeException e) {
                return out;
            }
            if (pos[0] <= cursor) {
                // 理论上不可达；防御性终止，杜绝任何形式的死循环
                return out;
            }
            cursor = pos[0];
        }
        return out;
    }

    /**
     * 读一个 varint，并把游标推进到 varint 之后。
     *
     * <p>游标用 {@code int[1]} 传出，便于一边读一边前进（上游也这么写）。
     * 抛异常时游标仍已前进到已消费的位置 —— 与上游 {@code PbUtil.a(byte[], int[])} 一致。
     *
     * @param data   报文
     * @param cursor 长度为 1 的游标数组
     * @return 解出的值（按 64 位无符号累加）
     * @throws IllegalArgumentException 报文在 varint 中途结束，或续位超过 10 字节
     */
    public static long readVarint(byte[] data, int[] cursor) {
        long result = 0L;
        int shift = 0;
        int p = cursor[0];
        while (p < data.length) {
            int b = data[p++] & 0xFF;
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                cursor[0] = p;
                return result;
            }
            shift += 7;
            if (shift >= 70) {
                break;
            }
        }
        cursor[0] = p;
        throw new IllegalArgumentException("malformed varint");
    }

    /**
     * 把已解析字段列表压成 {@code number -> field} 的查找（后者覆盖前者）。
     *
     * <p>便捷方法：调用方常常只关心某几个字段号。
     *
     * @param fields 字段列表，可为 null
     * @param number 目标字段号
     * @return 最后一个同号字段；不存在返回 null
     */
    public static Field find(List<Field> fields, int number) {
        if (fields == null) {
            return null;
        }
        Field hit = null;
        for (Field f : fields) {
            if (f.number() == number) {
                hit = f;
            }
        }
        return hit;
    }

    /**
     * 收集所有同号 length-delimited 字段。
     *
     * @param fields 字段列表，可为 null
     * @param number 目标字段号
     * @return 非 null 列表（可能为空）
     */
    public static List<Field> findAll(List<Field> fields, int number) {
        List<Field> out = new ArrayList<>();
        if (fields == null) {
            return out;
        }
        for (Field f : fields) {
            if (f.number() == number) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * 字节数组转小写 hex（调试/断言用）。
     *
     * @param data 字节，可为 null
     * @return hex 串
     */
    public static String hex(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", Byte.valueOf(b)));
        }
        return sb.toString();
    }
}
