package com.github.guoyyit.binarycodecjava.codec;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 二进制解码器：拒绝截断、非法标记与超限输入，并保留未知字段。
 *
 * <p>内存占用受双重约束：长度声明先与配置上限比较，再与剩余实际输入比较，
 * 任一不满足即拒绝；重复字段与映射字段的元素总数受上限约束。
 */
public final class BinaryDecoder {

    private final SchemaRegistry registry;
    private final CodecLimits limits;

    public BinaryDecoder(SchemaRegistry registry, CodecLimits limits) {
        this.registry = registry;
        this.limits = limits;
    }

    public MessageValue decode(String messageName, byte[] data) {
        MessageDef def = registry.findMessage(messageName);
        if (def == null) {
            throw new CodecException("unknown message type '" + messageName + "'");
        }
        if (data.length > limits.getMaxMessageBytes()) {
            throw new CodecException("input of " + data.length + " bytes exceeds max message bytes "
                    + limits.getMaxMessageBytes(), 0);
        }
        Cursor cursor = new Cursor(data, 0, data.length);
        return readMessage(def, cursor, 1, new int[]{0});
    }

    private MessageValue readMessage(MessageDef def, Cursor cursor, int depth, int[] elementCount) {
        if (depth > limits.getMaxDepth()) {
            throw new CodecException("nesting depth exceeds max depth " + limits.getMaxDepth(), cursor.pos);
        }
        MessageValue value = new MessageValue(def, registry.defaultResolver());
        Map<String, List<Object>> repeatedValues = new LinkedHashMap<>();
        Map<String, Map<Object, Object>> mapValues = new LinkedHashMap<>();
        while (cursor.pos < cursor.end) {
            long tagStart = cursor.pos;
            long tag = readVarint(cursor);
            int wireType = (int) (tag & 0x7);
            long fieldNumber = tag >>> 3;
            if (fieldNumber == 0 || fieldNumber > SchemaParser.MAX_FIELD_NUMBER) {
                throw new CodecException("illegal field tag " + tag, tagStart);
            }
            FieldDef field = def.findField((int) fieldNumber);
            if (field == null) {
                readUnknownField(cursor, wireType, value, (int) fieldNumber, tagStart);
                continue;
            }
            readKnownField(cursor, value, field, wireType, tagStart, repeatedValues, mapValues,
                    elementCount, depth);
        }
        repeatedValues.forEach((name, list) -> value.set(name, List.copyOf(list)));
        mapValues.forEach((name, map) -> value.set(name, Map.copyOf(map)));
        return value;
    }

    private void readKnownField(Cursor cursor, MessageValue value, FieldDef field, int wireType,
                                long tagStart, Map<String, List<Object>> repeatedValues,
                                Map<String, Map<Object, Object>> mapValues, int[] elementCount, int depth) {
        TypeRef type = field.getType();
        if (type.getKind() == TypeRef.Kind.MAP) {
            expectWireType(field, wireType, BinaryEncoder.WIRE_LEN, tagStart);
            readMapEntry(cursor, value, field, mapValues, elementCount, depth);
            return;
        }
        if (field.isRepeated()) {
            List<Object> list = repeatedValues.computeIfAbsent(field.getName(), k -> new ArrayList<>());
            if (wireType == BinaryEncoder.WIRE_LEN && isPackable(type)) {
                int length = readLength(cursor, limits.getMaxMessageBytes());
                Cursor packed = cursor.slice(length);
                while (packed.pos < packed.end) {
                    Object element = readScalarValue(packed, field, type, value, elementCount, depth);
                    if (element != Skip.INSTANCE) {
                        list.add(element);
                    }
                }
            } else {
                expectWireType(field, wireType, wireTypeOf(type), tagStart);
                Object element = readScalarValue(cursor, field, type, value, elementCount, depth);
                if (element != Skip.INSTANCE) {
                    list.add(element);
                }
            }
            return;
        }
        expectWireType(field, wireType, wireTypeOf(type), tagStart);
        Object decoded = readScalarValue(cursor, field, type, value, elementCount, depth);
        if (decoded != Skip.INSTANCE) {
            value.set(field.getName(), decoded);
        }
    }

    private enum Skip {
        INSTANCE
    }

    private Object readScalarValue(Cursor source, FieldDef field, TypeRef type,
                                   MessageValue value, int[] elementCount, int depth) {
        countElements(elementCount, 1, source);
        switch (type.getKind()) {
            case INT32 -> {
                long v = readZigzag(source);
                if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                    throw new CodecException("int32 field '" + field.getName() + "' value out of range",
                            source.pos);
                }
                return v;
            }
            case INT64 -> {
                return readZigzag(source);
            }
            case BOOL -> {
                long v = readVarint(source);
                if (v > 1) {
                    throw new CodecException("illegal bool value " + v + " for field '" + field.getName() + "'",
                            source.pos);
                }
                return v == 1;
            }
            case FLOAT -> {
                return (double) Float.intBitsToFloat((int) readFixed32(source));
            }
            case DOUBLE -> {
                return Double.longBitsToDouble(readFixed64(source));
            }
            case BYTES -> {
                int length = readLength(source, limits.getMaxMessageBytes());
                return source.readBytes(length);
            }
            case STRING -> {
                int length = readLength(source, limits.getMaxStringBytes());
                byte[] bytes = source.readBytes(length);
                try {
                    return StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString();
                } catch (CharacterCodingException e) {
                    throw new CodecException("invalid UTF-8 in string field '" + field.getName() + "'",
                            source.pos - length);
                }
            }
            case NAMED, ENUM, MESSAGE -> {
                return readNamedValue(source, field, type, value, depth, elementCount);
            }
            default -> throw new CodecException("unsupported type for field '" + field.getName() + "'",
                    source.pos);
        }
    }

    private Object readNamedValue(Cursor source, FieldDef field, TypeRef type,
                                  MessageValue value, int depth, int[] elementCount) {
        TypeRef.Kind resolved = type.getKind() == TypeRef.Kind.NAMED
                ? registry.resolveNamed(type.getTypeName()) : type.getKind();
        if (resolved == TypeRef.Kind.ENUM) {
            long valueStart = source.pos;
            int number = (int) readZigzag(source);
            EnumDef enumDef = registry.findEnum(type.getTypeName());
            String name = enumDef == null ? null : enumDef.findName(number);
            if (name == null) {
                byte[] raw = Arrays.copyOfRange(source.data, (int) valueStart, source.pos);
                value.addUnknownField(new UnknownField(field.getNumber(), BinaryEncoder.WIRE_VARINT, raw));
                return Skip.INSTANCE;
            }
            return name;
        }
        if (resolved == TypeRef.Kind.MESSAGE) {
            MessageDef nestedDef = registry.findMessage(type.getTypeName());
            int length = readLength(source, limits.getMaxMessageBytes());
            Cursor nested = source.slice(length);
            return readMessage(nestedDef, nested, depth + 1, elementCount);
        }
        throw new CodecException("field '" + field.getName() + "' references unknown type '"
                + type.getTypeName() + "'", source.pos);
    }

    private void readMapEntry(Cursor cursor, MessageValue value, FieldDef field,
                              Map<String, Map<Object, Object>> mapValues, int[] elementCount, int depth) {
        countElements(elementCount, 1, cursor);
        int length = readLength(cursor, limits.getMaxMessageBytes());
        Cursor entry = cursor.slice(length);
        TypeRef keyType = field.getType().getKeyType();
        TypeRef valueType = field.getType().getValueType();
        Object key = null;
        Object entryValue = null;
        while (entry.pos < entry.end) {
            long tagStart = entry.pos;
            long tag = readVarint(entry);
            int wireType = (int) (tag & 0x7);
            long number = tag >>> 3;
            if (number == 1) {
                expectWireType(field, wireType, wireTypeOf(keyType), tagStart);
                key = readScalarValue(entry, field, keyType, value, elementCount, depth);
            } else if (number == 2) {
                expectWireType(field, wireType, wireTypeOf(valueType), tagStart);
                entryValue = readScalarValue(entry, field, valueType, value, elementCount, depth);
            } else {
                skipValue(entry, wireType, tagStart);
            }
        }
        Map<Object, Object> map = mapValues.computeIfAbsent(field.getName(), k -> new LinkedHashMap<>());
        if (entryValue != Skip.INSTANCE) {
            map.put(key != null ? key : defaultKeyOf(keyType),
                    entryValue != null ? entryValue : defaultValueOf(valueType));
        }
    }

    private Object defaultKeyOf(TypeRef type) {
        return switch (type.getKind()) {
            case INT32, INT64 -> 0L;
            case BOOL -> Boolean.FALSE;
            case STRING -> "";
            case FLOAT, DOUBLE -> 0.0d;
            default -> throw new CodecException("map entry requires explicit key/value of scalar type");
        };
    }

    private Object defaultValueOf(TypeRef type) {
        if (type.getKind() == TypeRef.Kind.NAMED
                && registry.resolveNamed(type.getTypeName()) == TypeRef.Kind.MESSAGE) {
            return new MessageValue(registry.findMessage(type.getTypeName()), registry.defaultResolver());
        }
        if (type.getKind() == TypeRef.Kind.NAMED) {
            EnumDef enumDef = registry.findEnum(type.getTypeName());
            return enumDef.getConstants().get(0).name();
        }
        return defaultKeyOf(type);
    }

    private void readUnknownField(Cursor cursor, int wireType, MessageValue value, int fieldNumber,
                                  long tagStart) {
        int start = cursor.pos;
        skipValue(cursor, wireType, tagStart);
        byte[] payload = Arrays.copyOfRange(cursor.data, start, cursor.pos);
        value.addUnknownField(new UnknownField(fieldNumber, wireType, payload));
    }

    private void skipValue(Cursor cursor, int wireType, long tagStart) {
        switch (wireType) {
            case BinaryEncoder.WIRE_VARINT -> readVarint(cursor);
            case BinaryEncoder.WIRE_FIXED64 -> readFixed64(cursor);
            case BinaryEncoder.WIRE_LEN -> {
                int length = readLength(cursor, limits.getMaxMessageBytes());
                cursor.skip(length);
            }
            case BinaryEncoder.WIRE_FIXED32 -> readFixed32(cursor);
            default -> throw new CodecException("illegal wire type " + wireType, tagStart);
        }
    }

    private void expectWireType(FieldDef field, int actual, int expected, long tagStart) {
        if (actual != expected) {
            throw new CodecException("field '" + field.getName() + "' expects wire type " + expected
                    + " but got " + actual, tagStart);
        }
    }

    private int wireTypeOf(TypeRef type) {
        return switch (type.getKind()) {
            case INT32, INT64, BOOL, ENUM -> BinaryEncoder.WIRE_VARINT;
            case DOUBLE -> BinaryEncoder.WIRE_FIXED64;
            case FLOAT -> BinaryEncoder.WIRE_FIXED32;
            case NAMED -> registry.resolveNamed(type.getTypeName()) == TypeRef.Kind.MESSAGE
                    ? BinaryEncoder.WIRE_LEN : BinaryEncoder.WIRE_VARINT;
            default -> BinaryEncoder.WIRE_LEN;
        };
    }

    private static boolean isPackable(TypeRef type) {
        return switch (type.getKind()) {
            case INT32, INT64, BOOL, FLOAT, DOUBLE, NAMED, ENUM -> true;
            default -> false;
        };
    }

    private void countElements(int[] elementCount, int delta, Cursor cursor) {
        elementCount[0] += delta;
        if (elementCount[0] > limits.getMaxContainerElements()) {
            throw new CodecException("container element count exceeds max "
                    + limits.getMaxContainerElements(), cursor.pos);
        }
    }

    private int readLength(Cursor cursor, int declaredLimit) {
        long tagStart = cursor.pos;
        long length = readVarint(cursor);
        if (length > declaredLimit) {
            throw new CodecException("declared length " + length + " exceeds limit " + declaredLimit,
                    tagStart);
        }
        if (length > cursor.remaining()) {
            throw new CodecException("declared length " + length + " exceeds remaining input "
                    + cursor.remaining(), tagStart);
        }
        return (int) length;
    }

    private static long readZigzag(Cursor cursor) {
        long raw = readVarint(cursor);
        return (raw >>> 1) ^ -(raw & 1);
    }

    private static long readVarint(Cursor cursor) {
        long result = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            if (cursor.pos >= cursor.end) {
                throw new CodecException("truncated varint", cursor.pos);
            }
            int b = cursor.data[cursor.pos++] & 0xFF;
            if (shift == 63 && (b & 0xFE) != 0) {
                throw new CodecException("varint overflow", cursor.pos - 1);
            }
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new CodecException("varint too long", cursor.pos);
    }

    private static long readFixed32(Cursor cursor) {
        if (cursor.remaining() < 4) {
            throw new CodecException("truncated fixed32", cursor.pos);
        }
        long result = 0;
        for (int i = 0; i < 4; i++) {
            result |= (long) (cursor.data[cursor.pos++] & 0xFF) << (i * 8);
        }
        return result;
    }

    private static long readFixed64(Cursor cursor) {
        if (cursor.remaining() < 8) {
            throw new CodecException("truncated fixed64", cursor.pos);
        }
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= (long) (cursor.data[cursor.pos++] & 0xFF) << (i * 8);
        }
        return result;
    }

    private static final class Cursor {
        final byte[] data;
        int pos;
        final int end;

        Cursor(byte[] data, int pos, int end) {
            this.data = data;
            this.pos = pos;
            this.end = end;
        }

        int remaining() {
            return end - pos;
        }

        void skip(int length) {
            if (length > remaining()) {
                throw new CodecException("truncated input", pos);
            }
            pos += length;
        }

        byte[] readBytes(int length) {
            if (length > remaining()) {
                throw new CodecException("truncated input", pos);
            }
            byte[] result = Arrays.copyOfRange(data, pos, pos + length);
            pos += length;
            return result;
        }

        Cursor slice(int length) {
            if (length > remaining()) {
                throw new CodecException("truncated input", pos);
            }
            Cursor slice = new Cursor(data, pos, pos + length);
            pos += length;
            return slice;
        }
    }
}
