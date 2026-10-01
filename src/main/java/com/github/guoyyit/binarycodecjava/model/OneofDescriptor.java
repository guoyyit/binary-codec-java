package com.github.guoyyit.binarycodecjava.model;

import java.util.List;

public final class OneofDescriptor {
    private final String name;
    private final int declaredLine;
    private final List<FieldDescriptor> fields;

    public OneofDescriptor(String name, int declaredLine, List<FieldDescriptor> fields) {
        this.name = name;
        this.declaredLine = declaredLine;
        this.fields = List.copyOf(fields);
    }

    public String name() {
        return name;
    }

    public int declaredLine() {
        return declaredLine;
    }

    public List<FieldDescriptor> fields() {
        return fields;
    }
}
