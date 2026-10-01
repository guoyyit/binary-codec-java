package com.github.guoyyit.binarycodecjava.codec;

/**
 * 编码或解码失败时抛出，携带输入/输出中的字节位置。
 */
public class CodecException extends RuntimeException {

    private final long position;

    public CodecException(String message, long position) {
        super(position >= 0 ? message + " (at byte " + position + ")" : message);
        this.position = position;
    }

    public CodecException(String message) {
        this(message, -1);
    }

    public long getPosition() {
        return position;
    }
}
