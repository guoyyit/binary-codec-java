package com.github.guoyyit.binarycodecjava.codec;

public class CodecException extends RuntimeException {
    private final long position;

    public CodecException(String message, long position) {
        super(message + " (byte position " + position + ")");
        this.position = position;
    }

    public long position() {
        return position;
    }
}
