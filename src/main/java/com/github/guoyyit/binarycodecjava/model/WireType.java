package com.github.guoyyit.binarycodecjava.model;

public enum WireType {
    VARINT(0),
    FIXED64(1),
    LENGTH_DELIMITED(2),
    FIXED32(5);

    private final int value;

    WireType(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static WireType fromValue(int value) {
        for (WireType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        return null;
    }
}
