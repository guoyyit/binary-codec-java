package com.github.guoyyit.binarycodecjava.model;

public final class UnknownField {
    private final int number;
    private final WireType wireType;
    private final byte[] payload;

    public UnknownField(int number, WireType wireType, byte[] payload) {
        this.number = number;
        this.wireType = wireType;
        this.payload = payload;
    }

    public int number() {
        return number;
    }

    public WireType wireType() {
        return wireType;
    }

    public byte[] payload() {
        return payload;
    }
}
