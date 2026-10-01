package com.github.guoyyit.binarycodecjava.model;

import java.util.List;

public final class EnumDescriptor {
    private final String name;
    private final String fullName;
    private final List<Value> values;

    public EnumDescriptor(String name, String fullName, List<Value> values) {
        this.name = name;
        this.fullName = fullName;
        this.values = List.copyOf(values);
    }

    public String name() {
        return name;
    }

    public String fullName() {
        return fullName;
    }

    public List<Value> values() {
        return values;
    }

    public Value findByNumber(int number) {
        for (Value value : values) {
            if (value.number == number) {
                return value;
            }
        }
        return null;
    }

    public Value findByName(String valueName) {
        for (Value value : values) {
            if (value.name.equals(valueName)) {
                return value;
            }
        }
        return null;
    }

    public Value defaultValue() {
        for (Value value : values) {
            if (value.number == 0) {
                return value;
            }
        }
        return values.get(0);
    }

    public static final class Value {
        public final String name;
        public final int number;
        public final int declaredLine;

        public Value(String name, int number, int declaredLine) {
            this.name = name;
            this.number = number;
            this.declaredLine = declaredLine;
        }
    }
}
