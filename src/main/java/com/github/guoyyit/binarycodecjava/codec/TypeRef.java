package com.github.guoyyit.binarycodecjava.codec;

/**
 * 字段类型引用：标量类型、具名类型（枚举/消息）或映射类型。
 */
public final class TypeRef {

    public enum Kind {
        INT32, INT64, BOOL, FLOAT, DOUBLE, BYTES, STRING, NAMED, ENUM, MESSAGE, MAP
    }

    private final Kind kind;
    private final String typeName;
    private final TypeRef keyType;
    private final TypeRef valueType;

    private TypeRef(Kind kind, String typeName, TypeRef keyType, TypeRef valueType) {
        this.kind = kind;
        this.typeName = typeName;
        this.keyType = keyType;
        this.valueType = valueType;
    }

    public static TypeRef scalar(Kind kind) {
        return new TypeRef(kind, null, null, null);
    }

    public static TypeRef named(Kind kind, String typeName) {
        return new TypeRef(kind, typeName, null, null);
    }

    public static TypeRef map(TypeRef keyType, TypeRef valueType) {
        return new TypeRef(Kind.MAP, null, keyType, valueType);
    }

    public Kind getKind() {
        return kind;
    }

    public String getTypeName() {
        return typeName;
    }

    public TypeRef getKeyType() {
        return keyType;
    }

    public TypeRef getValueType() {
        return valueType;
    }
}
