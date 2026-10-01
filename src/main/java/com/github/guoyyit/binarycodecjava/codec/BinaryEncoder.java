package com.github.guoyyit.binarycodecjava.codec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 二进制编码器：同一消息在同一模式下编码结果逐字节确定。
 *
 * <p>线格式：tag 为 {@code (fieldNumber << 3) | wireType} 的变长整数；
 * 整数与枚举使用 zigzag 变长编码，浮点使用定长小端，字符串/字节串/嵌套消息
 * 使用长度前缀，标量重复字段使用紧凑的连续（packed）存储，映射字段按键排序后
 * 编码为重复的键值条目。
 */
public final class BinaryEncoder {

    static final int WIRE_VARINT = 0;
    static final int WIRE_FIXED64 = 1;
    static final int WIRE_LEN = 2;
    static final int WIRE_FIXED32 = 5;

    private final SchemaRegistry registry;

    public BinaryEncoder(SchemaRegistry registry) {
        this.registry = registry;
    }

    public byte[] encode(MessageValue value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeMessage(value, out);
        return out.toByteArray();
    }

    private void writeMessage(MessageValue value, ByteArrayOutputStream out) {
        List<FieldDef> fields = new ArrayList<>(value.getDef().getFields());
        fields.sort(Comparator.comparingInt(FieldDef::getNumber));
        for (FieldDef field : fields) {
            if (!value.has(field.getName())) {
                continue;
            }
            writeField(field, value.get(field.getName()), out);
        }
        for (UnknownField unknown : value.getUnknownFields()) {
            writeTag(unknown.getNumber(), unknown.getWireType(), out);
            out.writeBytes(unknown.getPayload());
        }
    }

    private void writeField(FieldDef field, Object value, ByteArrayOutputStream out) {
        TypeRef type = field.getType();
        if (type.getKind() == TypeRef.Kind.MAP) {
            writeMapField(field, value, out);
        } else if (field.isRepeated()) {
            List<?> elements = requireList(field, value);
            if (isPackable(type)) {
                writePacked(field, elements, out);
            } else {
                for (Object element : elements) {
                    writeTag(field.getNumber(), wireTypeOf(type), out);
                    writeValue(field, type, element, out);
                }
            }
        } else {
            writeTag(field.getNumber(), wireTypeOf(type), out);
            writeValue(field, type, value, out);
        }
    }

    private void writePacked(FieldDef field, List<?> elements, ByteArrayOutputStream out) {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        for (Object element : elements) {
            writeValue(field, field.getType(), element, packed);
        }
        writeTag(field.getNumber(), WIRE_LEN, out);
        writeVarint(packed.size(), out);
        out.writeBytes(packed.toByteArray());
    }

    private void writeMapField(FieldDef field, Object value, ByteArrayOutputStream out) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new CodecException("field '" + field.getName() + "' expects a Map value");
        }
        List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
        entries.sort((a, b) -> compareKeys(a.getKey(), b.getKey()));
        TypeRef keyType = field.getType().getKeyType();
        TypeRef valueType = field.getType().getValueType();
        for (Map.Entry<?, ?> entry : entries) {
            ByteArrayOutputStream entryBytes = new ByteArrayOutputStream();
            writeTag(1, wireTypeOf(keyType), entryBytes);
            writeValue(field, keyType, entry.getKey(), entryBytes);
            writeTag(2, wireTypeOf(valueType), entryBytes);
            writeValue(field, valueType, entry.getValue(), entryBytes);
            writeTag(field.getNumber(), WIRE_LEN, out);
            writeVarint(entryBytes.size(), out);
            out.writeBytes(entryBytes.toByteArray());
        }
    }

    private void writeValue(FieldDef field, TypeRef type, Object value, ByteArrayOutputStream out) {
        switch (type.getKind()) {
            case INT32 -> {
                long v = requireLong(field, value);
                if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                    throw new CodecException("field '" + field.getName() + "' value " + v + " out of int32 range");
                }
                writeZigzag(v, out);
            }
            case INT64 -> writeZigzag(requireLong(field, value), out);
            case BOOL -> writeVarint(requireBoolean(field, value) ? 1 : 0, out);
            case FLOAT -> writeFixed32(Float.floatToIntBits(requireDouble(field, value).floatValue()), out);
            case DOUBLE -> writeFixed64(Double.doubleToLongBits(requireDouble(field, value)), out);
            case BYTES -> {
                if (!(value instanceof byte[] bytes)) {
                    throw new CodecException("field '" + field.getName() + "' expects a byte[] value");
                }
                writeVarint(bytes.length, out);
                out.writeBytes(bytes);
            }
            case STRING -> {
                if (!(value instanceof String string)) {
                    throw new CodecException("field '" + field.getName() + "' expects a String value");
                }
                byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
                writeVarint(bytes.length, out);
                out.writeBytes(bytes);
            }
            case NAMED, ENUM, MESSAGE -> writeNamed(field, type, value, out);
            default -> throw new CodecException("field '" + field.getName() + "' has unsupported type");
        }
    }

    private void writeNamed(FieldDef field, TypeRef type, Object value, ByteArrayOutputStream out) {
        TypeRef.Kind resolved = type.getKind() == TypeRef.Kind.NAMED
                ? registry.resolveNamed(type.getTypeName()) : type.getKind();
        if (resolved == TypeRef.Kind.ENUM) {
            if (!(value instanceof String constantName)) {
                throw new CodecException("field '" + field.getName() + "' expects an enum constant name");
            }
            EnumDef enumDef = registry.findEnum(type.getTypeName());
            Integer number = enumDef == null ? null : enumDef.findValue(constantName);
            if (number == null) {
                throw new CodecException("field '" + field.getName() + "': '" + constantName
                        + "' is not a constant of enum '" + type.getTypeName() + "'");
            }
            writeZigzag(number, out);
        } else if (resolved == TypeRef.Kind.MESSAGE) {
            if (!(value instanceof MessageValue nested)) {
                throw new CodecException("field '" + field.getName() + "' expects a nested message value");
            }
            byte[] bytes = encode(nested);
            writeVarint(bytes.length, out);
            out.writeBytes(bytes);
        } else {
            throw new CodecException("field '" + field.getName() + "' references unknown type '"
                    + type.getTypeName() + "'");
        }
    }

    private static boolean isPackable(TypeRef type) {
        return switch (type.getKind()) {
            case INT32, INT64, BOOL, FLOAT, DOUBLE, NAMED, ENUM -> true;
            default -> false;
        };
    }

    private int wireTypeOf(TypeRef type) {
        return switch (type.getKind()) {
            case INT32, INT64, BOOL, ENUM -> WIRE_VARINT;
            case DOUBLE -> WIRE_FIXED64;
            case FLOAT -> WIRE_FIXED32;
            case NAMED -> registry.resolveNamed(type.getTypeName()) == TypeRef.Kind.MESSAGE
                    ? WIRE_LEN : WIRE_VARINT;
            default -> WIRE_LEN;
        };
    }

    private static int compareKeys(Object a, Object b) {
        int rankA = keyRank(a);
        int rankB = keyRank(b);
        if (rankA != rankB) {
            return Integer.compare(rankA, rankB);
        }
        return switch (rankA) {
            case 0 -> Boolean.compare((Boolean) a, (Boolean) b);
            case 1 -> Long.compare((Long) a, (Long) b);
            default -> ((String) a).compareTo((String) b);
        };
    }

    private static int keyRank(Object key) {
        if (key instanceof Boolean) {
            return 0;
        }
        if (key instanceof Long) {
            return 1;
        }
        if (key instanceof String) {
            return 2;
        }
        throw new CodecException("unsupported map key type: " + key.getClass().getSimpleName());
    }

    private static List<?> requireList(FieldDef field, Object value) {
        if (!(value instanceof List<?> list)) {
            throw new CodecException("field '" + field.getName() + "' expects a List value");
        }
        return list;
    }

    private static Long requireLong(FieldDef field, Object value) {
        if (!(value instanceof Long v)) {
            throw new CodecException("field '" + field.getName() + "' expects a Long value");
        }
        return v;
    }

    private static Boolean requireBoolean(FieldDef field, Object value) {
        if (!(value instanceof Boolean v)) {
            throw new CodecException("field '" + field.getName() + "' expects a Boolean value");
        }
        return v;
    }

    private static Double requireDouble(FieldDef field, Object value) {
        if (!(value instanceof Double v)) {
            throw new CodecException("field '" + field.getName() + "' expects a Double value");
        }
        return v;
    }

    static void writeTag(int fieldNumber, int wireType, ByteArrayOutputStream out) {
        writeVarint(((long) fieldNumber << 3) | wireType, out);
    }

    static void writeZigzag(long value, ByteArrayOutputStream out) {
        writeVarint((value << 1) ^ (value >> 63), out);
    }

    static void writeVarint(long value, ByteArrayOutputStream out) {
        long remaining = value;
        while (true) {
            if ((remaining & ~0x7FL) == 0) {
                out.write((int) remaining);
                return;
            }
            out.write(((int) remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
    }

    static void writeFixed32(int value, ByteArrayOutputStream out) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    static void writeFixed64(long value, ByteArrayOutputStream out) {
        for (int i = 0; i < 8; i++) {
            out.write((int) (value >>> (i * 8)) & 0xFF);
        }
    }
}
