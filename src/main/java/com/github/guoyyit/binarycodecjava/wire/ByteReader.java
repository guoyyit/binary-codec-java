package com.github.guoyyit.binarycodecjava.wire;

import com.github.guoyyit.binarycodecjava.codec.CodecException;

public final class ByteReader {
    private final byte[] data;
    private int position;
    private final int limit;

    public ByteReader(byte[] data) {
        this(data, 0, data.length);
    }

    public ByteReader(byte[] data, int offset, int length) {
        this.data = data;
        this.position = offset;
        this.limit = offset + length;
    }

    public boolean hasRemaining() {
        return position < limit;
    }

    public int position() {
        return position;
    }

    public int limit() {
        return limit;
    }

    public byte readByte() {
        if (position >= limit) {
            throw new CodecException("truncated input: expected 1 byte", position);
        }
        return data[position++];
    }

    public long readVarint() {
        int[] pos = new int[]{position};
        long value = Varint.readLong(data, pos, limit);
        position = pos[0];
        return value;
    }

    public RawVarint readRawVarint() {
        int start = position;
        long value = readVarint();
        int length = position - start;
        byte[] raw = new byte[length];
        System.arraycopy(data, start, raw, 0, length);
        return new RawVarint(value, raw);
    }

    public byte[] copyTrailing(int length) {
        int start = position - length;
        byte[] raw = new byte[length];
        System.arraycopy(data, start, raw, 0, length);
        return raw;
    }

    public int readFixed32() {
        if (limit - position < 4) {
            throw new CodecException("truncated input: expected 4 bytes for fixed32", position);
        }
        int value = (data[position] & 0xFF)
                | ((data[position + 1] & 0xFF) << 8)
                | ((data[position + 2] & 0xFF) << 16)
                | ((data[position + 3] & 0xFF) << 24);
        position += 4;
        return value;
    }

    public long readFixed64() {
        if (limit - position < 8) {
            throw new CodecException("truncated input: expected 8 bytes for fixed64", position);
        }
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= (data[position + i] & 0xFFL) << (i * 8);
        }
        position += 8;
        return value;
    }

    public int readLength() {
        long length = readVarint();
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new CodecException("declared length " + length + " exceeds addressable size", position);
        }
        return (int) length;
    }

    public byte[] readBytes(int length) {
        if (length < 0 || length > limit - position) {
            throw new CodecException("declared length " + length
                    + " exceeds remaining " + Math.max(0, limit - position) + " bytes", position);
        }
        byte[] result = new byte[length];
        System.arraycopy(data, position, result, 0, length);
        position += length;
        return result;
    }

    public ByteReader slice(int length) {
        if (length < 0 || length > limit - position) {
            throw new CodecException("nested length " + length
                    + " exceeds remaining " + Math.max(0, limit - position) + " bytes", position);
        }
        ByteReader reader = new ByteReader(data, position, length);
        position += length;
        return reader;
    }

    public record RawVarint(long value, byte[] raw) {
    }
}
