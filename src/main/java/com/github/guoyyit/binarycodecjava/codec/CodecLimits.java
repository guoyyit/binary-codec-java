package com.github.guoyyit.binarycodecjava.codec;

public final class CodecLimits {
    public static final int MAX_NESTING_DEPTH = 64;
    public static final int MAX_DECLARED_LENGTH = 16 * 1024 * 1024;
    public static final int MAX_INPUT_SIZE = 64 * 1024 * 1024;
    public static final int MAX_FIELD_NUMBER = 536870911;
    public static final int MAX_COLLECTION_ELEMENTS = 10_000_000;
    public static final int MAX_TOTAL_VALUES = 32_000_000;

    private CodecLimits() {
    }
}
