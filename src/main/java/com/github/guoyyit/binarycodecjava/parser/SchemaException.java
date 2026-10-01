package com.github.guoyyit.binarycodecjava.parser;

public class SchemaException extends RuntimeException {
    private final int line;

    public SchemaException(String message, int line) {
        super("schema error at line " + line + ": " + message);
        this.line = line;
    }

    public int line() {
        return line;
    }
}
