package com.github.guoyyit.binarycodecjava.model;

import java.util.Map;

public final class FieldDescriptor {
    private final String name;
    private final int number;
    private final int declaredLine;
    private final FieldLabel label;
    private final ScalarType scalarType;
    private final String typeName;
    private final ScalarType mapKeyScalarType;
    private final boolean inOneof;
    private final String oneofName;
    private EnumDescriptor enumType;
    private MessageDescriptor messageType;

    public FieldDescriptor(String name,
                           int number,
                           int declaredLine,
                           FieldLabel label,
                           ScalarType scalarType,
                           String typeName,
                           ScalarType mapKeyScalarType,
                           boolean inOneof,
                           String oneofName) {
        this.name = name;
        this.number = number;
        this.declaredLine = declaredLine;
        this.label = label;
        this.scalarType = scalarType;
        this.typeName = typeName;
        this.mapKeyScalarType = mapKeyScalarType;
        this.inOneof = inOneof;
        this.oneofName = oneofName;
    }

    public String name() {
        return name;
    }

    public int number() {
        return number;
    }

    public int declaredLine() {
        return declaredLine;
    }

    public FieldLabel label() {
        return label;
    }

    public ScalarType scalarType() {
        return scalarType;
    }

    public String typeName() {
        return typeName;
    }

    public ScalarType mapKeyScalarType() {
        return mapKeyScalarType;
    }

    public boolean inOneof() {
        return inOneof;
    }

    public String oneofName() {
        return oneofName;
    }

    public boolean isMap() {
        return label == FieldLabel.MAP;
    }

    public boolean isRepeated() {
        return label == FieldLabel.REPEATED;
    }

    public boolean isEnum() {
        return enumType != null;
    }

    public boolean isMessage() {
        return messageType != null;
    }

    public EnumDescriptor enumType() {
        return enumType;
    }

    public MessageDescriptor messageType() {
        return messageType;
    }

    public void resolveTypes(EnumDescriptor resolvedEnum, MessageDescriptor resolvedMessage) {
        this.enumType = resolvedEnum;
        this.messageType = resolvedMessage;
    }

    public WireType wireType() {
        if (isMessage()) {
            return WireType.LENGTH_DELIMITED;
        }
        if (scalarType != null) {
            return switch (scalarType) {
                case INT32, INT64, BOOL -> WireType.VARINT;
                case FLOAT -> WireType.FIXED32;
                case DOUBLE -> WireType.FIXED64;
                case BYTES, STRING -> WireType.LENGTH_DELIMITED;
            };
        }
        return WireType.VARINT;
    }

    public boolean isPackable() {
        if (!isRepeated()) {
            return false;
        }
        WireType wire = wireType();
        return wire == WireType.VARINT || wire == WireType.FIXED32 || wire == WireType.FIXED64;
    }

    public Object defaultValue() {
        if (isRepeated()) {
            return java.util.Collections.emptyList();
        }
        if (isMap()) {
            return Map.of();
        }
        return scalarDefault();
    }

    public Object scalarDefault() {
        if (scalarType != null) {
            return switch (scalarType) {
                case INT32, INT64 -> 0L;
                case BOOL -> Boolean.FALSE;
                case FLOAT -> 0.0f;
                case DOUBLE -> 0.0d;
                case BYTES -> new byte[0];
                case STRING -> "";
            };
        }
        if (enumType != null) {
            return enumType.defaultValue().name;
        }
        return null;
    }
}
