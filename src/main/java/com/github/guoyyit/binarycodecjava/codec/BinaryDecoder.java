package com.github.guoyyit.binarycodecjava.codec;

import com.github.guoyyit.binarycodecjava.model.EnumDescriptor;
import com.github.guoyyit.binarycodecjava.model.FieldDescriptor;
import com.github.guoyyit.binarycodecjava.model.MessageDescriptor;
import com.github.guoyyit.binarycodecjava.model.MessageValue;
import com.github.guoyyit.binarycodecjava.model.ScalarType;
import com.github.guoyyit.binarycodecjava.model.Schema;
import com.github.guoyyit.binarycodecjava.model.UnknownField;
import com.github.guoyyit.binarycodecjava.model.WireType;
import com.github.guoyyit.binarycodecjava.registry.SchemaRegistry;
import com.github.guoyyit.binarycodecjava.wire.ByteReader;
import com.github.guoyyit.binarycodecjava.wire.Varint;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BinaryDecoder {
    private final SchemaRegistry registry;

    public BinaryDecoder(SchemaRegistry registry) {
        this.registry = registry;
    }

    public MessageValue decode(byte[] data, String schemaName, String messageName) {
        return decode(data, registry.getLatest(schemaName), messageName);
    }

    public MessageValue decode(byte[] data, String schemaName, String version, String messageName) {
        return decode(data, registry.get(schemaName, version), messageName);
    }

    private MessageValue decode(byte[] data, Schema schema, String messageName) {
        if (data == null) {
            throw new CodecException("input must not be null", 0);
        }
        if (data.length > CodecLimits.MAX_INPUT_SIZE) {
            throw new CodecException("input size " + data.length + " exceeds limit "
                    + CodecLimits.MAX_INPUT_SIZE, 0);
        }
        MessageDescriptor descriptor = schema.findMessage(messageName);
        if (descriptor == null) {
            throw new IllegalArgumentException(
                    "message '" + messageName + "' not found in schema " + schema.name());
        }
        ByteReader reader = new ByteReader(data);
        MessageValue message = readMessage(reader, descriptor, 1, new DecodeContext());
        return message;
    }

    public MessageValue decode(byte[] data, MessageDescriptor descriptor) {
        if (data == null) {
            throw new CodecException("input must not be null", 0);
        }
        if (data.length > CodecLimits.MAX_INPUT_SIZE) {
            throw new CodecException("input size " + data.length + " exceeds limit "
                    + CodecLimits.MAX_INPUT_SIZE, 0);
        }
        ByteReader reader = new ByteReader(data);
        return readMessage(reader, descriptor, 1, new DecodeContext());
    }

    private MessageValue readMessage(ByteReader reader, MessageDescriptor descriptor, int depth,
                                     DecodeContext context) {
        if (depth > CodecLimits.MAX_NESTING_DEPTH) {
            throw new CodecException("nesting depth exceeds limit " + CodecLimits.MAX_NESTING_DEPTH,
                    reader.position());
        }
        MessageValue message = new MessageValue(descriptor);
        while (reader.hasRemaining()) {
            int tagStart = reader.position();
            long tagValue = reader.readVarint();
            if (tagValue == 0) {
                throw new CodecException("illegal field tag 0 at position " + tagStart, tagStart);
            }
            int fieldNumber = (int) (tagValue >>> 3);
            int wireTypeValue = (int) (tagValue & 0x7);
            if (fieldNumber < 1 || fieldNumber > CodecLimits.MAX_FIELD_NUMBER) {
                throw new CodecException("field number " + fieldNumber
                        + " out of allowed range at position " + tagStart, tagStart);
            }
            WireType wireType = WireType.fromValue(wireTypeValue);
            if (wireType == null) {
                throw new CodecException("illegal wire type " + wireTypeValue
                        + " for field " + fieldNumber + " at position " + tagStart, tagStart);
            }
            FieldDescriptor field = descriptor.fieldByNumber(fieldNumber);
            if (field == null) {
                context.tick(tagStart);
                readUnknownField(reader, message, fieldNumber, wireType);
            } else {
                readKnownField(reader, message, field, wireType, depth, tagStart, context);
            }
        }
        return message;
    }

    private void readUnknownField(ByteReader reader, MessageValue message, int fieldNumber,
                                  WireType wireType) {
        switch (wireType) {
            case VARINT -> message.addUnknownField(
                    new UnknownField(fieldNumber, wireType, reader.readRawVarint().raw()));
            case FIXED32 -> {
                reader.readFixed32();
                message.addUnknownField(
                        new UnknownField(fieldNumber, wireType, reader.copyTrailing(4)));
            }
            case FIXED64 -> {
                reader.readFixed64();
                message.addUnknownField(
                        new UnknownField(fieldNumber, wireType, reader.copyTrailing(8)));
            }
            case LENGTH_DELIMITED -> {
                int length = readCheckedLength(reader);
                message.addUnknownField(new UnknownField(fieldNumber, wireType, reader.readBytes(length)));
            }
        }
    }

    private int readCheckedLength(ByteReader reader) {
        int length = reader.readLength();
        if (length > CodecLimits.MAX_DECLARED_LENGTH) {
            throw new CodecException("declared length " + length + " exceeds limit "
                    + CodecLimits.MAX_DECLARED_LENGTH, reader.position());
        }
        return length;
    }

    private void readKnownField(ByteReader reader, MessageValue message, FieldDescriptor field,
                                WireType wireType, int depth, int tagStart, DecodeContext context) {
        if (field.isMap()) {
            if (wireType != WireType.LENGTH_DELIMITED) {
                throw new CodecException("map field '" + field.name()
                        + "' must be length-delimited at position " + tagStart, tagStart);
            }
            readMapEntry(reader, message, field, depth, tagStart, context);
            return;
        }
        if (field.isRepeated() && wireType == WireType.LENGTH_DELIMITED && field.isPackable()) {
            readPackedElements(reader, message, field, tagStart, context);
            return;
        }
        expectWireType(field, wireType, tagStart);
        context.tick(tagStart);
        Object value = readSingularValue(reader, field, wireType, depth, context);
        if (field.isRepeated()) {
            appendRepeated(message, field, value, context, tagStart);
        } else {
            if (field.inOneof()) {
                for (FieldDescriptor member : message.descriptor().oneofFields(field.oneofName())) {
                    if (member.number() != field.number()) {
                        message.clearField(member.name());
                    }
                }
            }
            message.setField(field, value);
        }
    }

    private void expectWireType(FieldDescriptor field, WireType wireType, int tagStart) {
        if (wireType != field.wireType()) {
            throw new CodecException("field '" + field.name() + "' expects wire type "
                    + field.wireType() + " but got " + wireType + " at position " + tagStart,
                    tagStart);
        }
    }

    @SuppressWarnings("unchecked")
    private void appendRepeated(MessageValue message, FieldDescriptor field, Object value,
                                DecodeContext context, int tagStart) {
        List<Object> values = (List<Object>) message.getField(field);
        if (values.isEmpty() || !message.hasField(field)) {
            values = new ArrayList<>();
            message.setField(field, values);
        }
        context.checkCollection(values.size(), field.name(), tagStart);
        values.add(value);
    }

    private void readPackedElements(ByteReader reader, MessageValue message, FieldDescriptor field,
                                    int tagStart, DecodeContext context) {
        int length = readCheckedLength(reader);
        ByteReader packed = reader.slice(length);
        List<Object> values = new ArrayList<>();
        int count = 0;
        while (packed.hasRemaining()) {
            context.checkCollection(count, field.name(), packed.position());
            count++;
            values.add(readScalarFromPacked(packed, field));
        }
        appendAllRepeated(message, field, values);
    }

    @SuppressWarnings("unchecked")
    private void appendAllRepeated(MessageValue message, FieldDescriptor field, List<Object> values) {
        List<Object> existing = (List<Object>) message.getField(field);
        if (existing.isEmpty() || !message.hasField(field)) {
            message.setField(field, values);
        } else {
            existing.addAll(values);
        }
    }

    private Object readScalarFromPacked(ByteReader reader, FieldDescriptor field) {
        return switch (field.wireType()) {
            case VARINT -> readVarintScalar(reader, field);
            case FIXED32 -> convertFixed32(field, reader.readFixed32());
            case FIXED64 -> convertFixed64(field, reader.readFixed64());
            default -> throw new CodecException("non-packable element", reader.position());
        };
    }

    private Object readSingularValue(ByteReader reader, FieldDescriptor field, WireType wireType,
                                     int depth, DecodeContext context) {
        if (field.isMessage()) {
            int length = readCheckedLength(reader);
            ByteReader nested = reader.slice(length);
            return readMessage(nested, field.messageType(), depth + 1, context);
        }
        if (field.isEnum()) {
            long raw = reader.readVarint();
            int number = Varint.decodeZigZag32(raw);
            EnumDescriptor.Value value = field.enumType().findByNumber(number);
            return value == null ? Long.valueOf(number) : value.name;
        }
        return readScalar(reader, field.scalarType());
    }

    private Object readVarintScalar(ByteReader reader, FieldDescriptor field) {
        long raw = reader.readVarint();
        if (field.isEnum()) {
            int number = Varint.decodeZigZag32(raw);
            EnumDescriptor.Value value = field.enumType().findByNumber(number);
            return value == null ? Long.valueOf(number) : value.name;
        }
        return switch (field.scalarType()) {
            case INT32 -> {
                int decoded = Varint.decodeZigZag32(raw);
                yield Integer.valueOf(decoded);
            }
            case INT64 -> Varint.decodeZigZag64(raw);
            case BOOL -> raw != 0;
            default -> throw new CodecException("unexpected varint for field '"
                    + field.name() + "'", reader.position());
        };
    }

    private Object readScalar(ByteReader reader, ScalarType type) {
        return switch (type) {
            case INT32 -> {
                long raw = reader.readVarint();
                if (raw > 0xFFFFFFFFL) {
                    throw new CodecException("int32 value out of range", reader.position());
                }
                yield Integer.valueOf(Varint.decodeZigZag32(raw));
            }
            case INT64 -> Varint.decodeZigZag64(reader.readVarint());
            case BOOL -> reader.readVarint() != 0;
            case FLOAT -> Float.intBitsToFloat(reader.readFixed32());
            case DOUBLE -> Double.longBitsToDouble(reader.readFixed64());
            case BYTES -> {
                int length = readCheckedLength(reader);
                yield reader.readBytes(length);
            }
            case STRING -> {
                int length = readCheckedLength(reader);
                int before = reader.position();
                byte[] bytes = reader.readBytes(length);
                yield decodeUtf8(bytes, before);
            }
        };
    }

    private Object convertFixed32(FieldDescriptor field, int raw) {
        if (field.scalarType() == ScalarType.FLOAT) {
            return Float.intBitsToFloat(raw);
        }
        throw new CodecException("unexpected fixed32 for field '" + field.name() + "'", 0);
    }

    private Object convertFixed64(FieldDescriptor field, long raw) {
        if (field.scalarType() == ScalarType.DOUBLE) {
            return Double.longBitsToDouble(raw);
        }
        throw new CodecException("unexpected fixed64 for field '" + field.name() + "'", 0);
    }

    private String decodeUtf8(byte[] bytes, int position) {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder();
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new CodecException("invalid UTF-8 sequence in string field", position);
        }
    }

    @SuppressWarnings("unchecked")
    private void readMapEntry(ByteReader reader, MessageValue message, FieldDescriptor field,
                              int depth, int entryTagStart, DecodeContext context) {
        int entryDepth = depth + 1;
        if (entryDepth > CodecLimits.MAX_NESTING_DEPTH) {
            throw new CodecException("nesting depth exceeds limit " + CodecLimits.MAX_NESTING_DEPTH,
                    reader.position());
        }
        context.tick(entryTagStart);
        int length = readCheckedLength(reader);
        ByteReader entryReader = reader.slice(length);
        Object key = null;
        Object value = null;
        boolean hasKey = false;
        boolean hasValue = false;
        while (entryReader.hasRemaining()) {
            int tagStart = entryReader.position();
            long tag = entryReader.readVarint();
            int entryFieldNumber = (int) (tag >>> 3);
            WireType entryWire = WireType.fromValue((int) (tag & 0x7));
            if (entryWire == null || entryFieldNumber < 1) {
                throw new CodecException("illegal tag inside map entry at position " + tagStart,
                        tagStart);
            }
            if (entryFieldNumber == 1) {
                key = readMapKey(entryReader, field.mapKeyScalarType(), entryWire, tagStart);
                hasKey = true;
            } else if (entryFieldNumber == 2) {
                value = readMapValue(entryReader, field, entryWire, entryDepth, tagStart, context);
                hasValue = true;
            } else {
                skipField(entryReader, entryWire);
            }
        }
        if (!hasKey) {
            return;
        }
        if (!hasValue) {
            value = field.scalarDefault();
        }
        Map<Object, Object> map = (Map<Object, Object>) message.getField(field);
        if (!message.hasField(field)) {
            map = new java.util.LinkedHashMap<>();
            message.setField(field, map);
        }
        context.checkCollection(map.size(), field.name(), reader.position());
        map.put(key, value);
    }

    private Object readMapKey(ByteReader reader, ScalarType keyType, WireType wireType, int tagStart) {
        return switch (keyType) {
            case INT32 -> {
                expectMapWire(wireType, WireType.VARINT, tagStart);
                long raw = reader.readVarint();
                if (raw > 0xFFFFFFFFL) {
                    throw new CodecException("int32 map key out of range", tagStart);
                }
                yield Integer.valueOf(Varint.decodeZigZag32(raw));
            }
            case INT64 -> {
                expectMapWire(wireType, WireType.VARINT, tagStart);
                yield Varint.decodeZigZag64(reader.readVarint());
            }
            case BOOL -> {
                expectMapWire(wireType, WireType.VARINT, tagStart);
                yield reader.readVarint() != 0;
            }
            case STRING -> {
                expectMapWire(wireType, WireType.LENGTH_DELIMITED, tagStart);
                int length = readCheckedLength(reader);
                int before = reader.position();
                yield decodeUtf8(reader.readBytes(length), before);
            }
            default -> throw new CodecException("unsupported map key type " + keyType, tagStart);
        };
    }

    private Object readMapValue(ByteReader reader, FieldDescriptor field, WireType wireType,
                                int depth, int tagStart, DecodeContext context) {
        if (field.isMessage()) {
            if (wireType != WireType.LENGTH_DELIMITED) {
                throw new CodecException("map message value must be length-delimited", tagStart);
            }
            int length = readCheckedLength(reader);
            return readMessage(reader.slice(length), field.messageType(), depth + 1, context);
        }
        if (field.isEnum()) {
            expectMapWire(wireType, WireType.VARINT, tagStart);
            int number = Varint.decodeZigZag32(reader.readVarint());
            EnumDescriptor.Value enumValue = field.enumType().findByNumber(number);
            return enumValue == null ? Long.valueOf(number) : enumValue.name;
        }
        return switch (field.scalarType()) {
            case INT32 -> {
                expectMapWire(wireType, WireType.VARINT, tagStart);
                long raw = reader.readVarint();
                if (raw > 0xFFFFFFFFL) {
                    throw new CodecException("int32 value out of range", tagStart);
                }
                yield Integer.valueOf(Varint.decodeZigZag32(raw));
            }
            case INT64 -> {
                expectMapWire(wireType, WireType.VARINT, tagStart);
                yield Varint.decodeZigZag64(reader.readVarint());
            }
            case BOOL -> {
                expectMapWire(wireType, WireType.VARINT, tagStart);
                yield reader.readVarint() != 0;
            }
            case FLOAT -> {
                expectMapWire(wireType, WireType.FIXED32, tagStart);
                yield Float.intBitsToFloat(reader.readFixed32());
            }
            case DOUBLE -> {
                expectMapWire(wireType, WireType.FIXED64, tagStart);
                yield Double.longBitsToDouble(reader.readFixed64());
            }
            case STRING -> {
                expectMapWire(wireType, WireType.LENGTH_DELIMITED, tagStart);
                int length = readCheckedLength(reader);
                int before = reader.position();
                yield decodeUtf8(reader.readBytes(length), before);
            }
            case BYTES -> {
                expectMapWire(wireType, WireType.LENGTH_DELIMITED, tagStart);
                int length = readCheckedLength(reader);
                yield reader.readBytes(length);
            }
        };
    }

    private void expectMapWire(WireType actual, WireType expected, int tagStart) {
        if (actual != expected) {
            throw new CodecException("map element expects wire type " + expected + " but got "
                    + actual + " at position " + tagStart, tagStart);
        }
    }

    private void skipField(ByteReader reader, WireType wireType) {
        switch (wireType) {
            case VARINT -> reader.readVarint();
            case FIXED32 -> reader.readFixed32();
            case FIXED64 -> reader.readFixed64();
            case LENGTH_DELIMITED -> {
                int length = readCheckedLength(reader);
                reader.readBytes(length);
            }
        }
    }

    private static final class DecodeContext {
        private int totalValues;

        private void tick(int position) {
            totalValues++;
            if (totalValues > CodecLimits.MAX_TOTAL_VALUES) {
                throw new CodecException("decoded value count exceeds total limit "
                        + CodecLimits.MAX_TOTAL_VALUES, position);
            }
        }

        private void checkCollection(int currentSize, String fieldName, int position) {
            if (currentSize >= CodecLimits.MAX_COLLECTION_ELEMENTS) {
                throw new CodecException("collection '" + fieldName + "' exceeds element limit "
                        + CodecLimits.MAX_COLLECTION_ELEMENTS, position);
            }
        }
    }
}
