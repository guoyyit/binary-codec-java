package com.github.guoyyit.binarycodecjava.codec;

/**
 * 模式声明解析或注册失败时抛出，携带可定位到声明行的行号。
 */
public class SchemaException extends RuntimeException {

    private final int line;

    public SchemaException(String message, int line) {
        super(line > 0 ? "line " + line + ": " + message : message);
        this.line = line;
    }

    public SchemaException(String message) {
        this(message, -1);
    }

    public int getLine() {
        return line;
    }
}
