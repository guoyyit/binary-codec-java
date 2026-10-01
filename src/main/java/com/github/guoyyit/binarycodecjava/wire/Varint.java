package com.github.guoyyit.binarycodecjava.wire;

import com.github.guoyyit.binarycodecjava.codec.CodecException;

public final class Varint {
    public static final int MAX_VARINT_BYTES = 10;

    private Varint() {
    }

    public static long encodeZigZag32(int value) {
        return (long) (value << 1) ^ (value >> 31);
    }

    public static int decodeZigZag32(long value) {
        return (int) (value >>> 1) ^ -(int) (value & 1L);
    }

    public static long encodeZigZag64(long value) {
        return (value << 1) ^ (value >> 63);
    }

    public static long decodeZigZag64(long value) {
        return (value >>> 1) ^ -(value & 1L);
    }

    public static int varintSize(long value) {
        int size = 1;
        while ((value & ~0x7FL) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }

    public static void writeLong(long value, byte[] out, int[] offset) {
        int pos = offset[0];
        while (true) {
            if ((value & ~0x7FL) == 0) {
                out[pos++] = (byte) value;
                offset[0] = pos;
                return;
            }
            out[pos++] = (byte) ((int) value & 0x7F | 0x80);
            value >>>= 7;
        }
    }

    public static long readLong(byte[] in, int[] offset, int limit) {
        int pos = offset[0];
        long result = 0;
        int shift = 0;
        for (int i = 0; i < MAX_VARINT_BYTES; i++) {
            if (pos >= limit) {
                throw new CodecException("truncated varint at position " + pos, pos);
            }
            byte b = in[pos++];
            if (i == MAX_VARINT_BYTES - 1) {
                if ((b & 0x7F) > 1 || (b & 0x80) != 0) {
                    throw new CodecException("varint overflow at position " + (pos - 1), pos - 1L);
                }
            }
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                offset[0] = pos;
                return result;
            }
            shift += 7;
        }
        throw new CodecException("varint too long at position " + pos, pos);
    }
}
