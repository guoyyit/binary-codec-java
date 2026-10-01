package com.github.guoyyit.binarycodecjava.codec;

/**
 * 解码时跳过并完整保留的未知字段，再次编码时按原样写回。
 */
public final class UnknownField {

    private final int number;
    private final int wireType;
    private final byte[] payload;

    public UnknownField(int number, int wireType, byte[] payload) {
        this.number = number;
        this.wireType = wireType;
        this.payload = payload;
    }

    public int getNumber() {
        return number;
    }

    public int getWireType() {
        return wireType;
    }

    public byte[] getPayload() {
        return payload;
    }
}
