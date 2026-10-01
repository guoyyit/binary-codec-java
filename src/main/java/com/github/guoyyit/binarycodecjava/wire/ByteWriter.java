package com.github.guoyyit.binarycodecjava.wire;

import com.github.guoyyit.binarycodecjava.codec.CodecLimits;

public final class ByteWriter {
    private byte[] buffer;
    private int position;

    public ByteWriter() {
        this(64);
    }

    public ByteWriter(int initialCapacity) {
        this.buffer = new byte[initialCapacity];
    }

    public void writeByte(int value) {
        ensureCapacity(1);
        buffer[position++] = (byte) value;
    }

    public void writeBytes(byte[] data) {
        ensureCapacity(data.length);
        System.arraycopy(data, 0, buffer, position, data.length);
        position += data.length;
    }

    public void writeVarint(long value) {
        ensureCapacity(Varint.MAX_VARINT_BYTES);
        int[] pos = new int[]{position};
        Varint.writeLong(value, buffer, pos);
        position = pos[0];
    }

    public void writeFixed32(int value) {
        ensureCapacity(4);
        buffer[position++] = (byte) value;
        buffer[position++] = (byte) (value >>> 8);
        buffer[position++] = (byte) (value >>> 16);
        buffer[position++] = (byte) (value >>> 24);
    }

    public void writeFixed64(long value) {
        ensureCapacity(8);
        for (int i = 0; i < 8; i++) {
            buffer[position++] = (byte) (value >>> (i * 8));
        }
    }

    public int position() {
        return position;
    }

    public void setIntAt(int offset, int value) {
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >>> 8);
        buffer[offset + 2] = (byte) (value >>> 16);
        buffer[offset + 3] = (byte) (value >>> 24);
    }

    public byte[] toByteArray() {
        byte[] result = new byte[position];
        System.arraycopy(buffer, 0, result, 0, position);
        return result;
    }

    private void ensureCapacity(int additional) {
        long required = (long) position + additional;
        if (required > CodecLimits.MAX_INPUT_SIZE) {
            throw new com.github.guoyyit.binarycodecjava.codec.CodecException(
                    "encoded message exceeds size limit " + CodecLimits.MAX_INPUT_SIZE, position);
        }
        if ((int) required <= buffer.length) {
            return;
        }
        int newCapacity = Math.max(buffer.length << 1, (int) required);
        byte[] grown = new byte[newCapacity];
        System.arraycopy(buffer, 0, grown, 0, position);
        buffer = grown;
    }
}
