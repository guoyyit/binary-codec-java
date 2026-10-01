package com.github.guoyyit.binarycodecjava.codec;

import com.github.guoyyit.binarycodecjava.model.FieldDescriptor;
import com.github.guoyyit.binarycodecjava.model.FieldLabel;
import com.github.guoyyit.binarycodecjava.model.MessageDescriptor;
import com.github.guoyyit.binarycodecjava.model.MessageValue;
import com.github.guoyyit.binarycodecjava.model.ScalarType;
import com.github.guoyyit.binarycodecjava.model.UnknownField;
import com.github.guoyyit.binarycodecjava.model.WireType;
import com.github.guoyyit.binarycodecjava.wire.ByteWriter;
import com.github.guoyyit.binarycodecjava.wire.Varint;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class BinaryEncoder {

    public byte[] encode(MessageValue message) {
        if (message == null) {
            throw new CodecException("message must not be null", 0);
        }
        ByteWriter writer = new ByteWriter();
        try {
            writeMessage(message, writer, 0);
        } catch (CodecException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CodecException("encoding failed: " + e.getMessage(), writer.position());
        }
        return writer.toByteArray();
    }

    private void writeMessage(MessageValue message, ByteWriter writer, int depth) {
        if (depth > CodecLimits.MAX_NESTING_DEPTH) {
            throw new CodecException("nesting depth exceeds limit " + CodecLimits.MAX_NESTING_DEPTH,
                    writer.position());
        }
        MessageDescriptor descriptor = message.descriptor();
        List<FieldAction> actions = new ArrayList<>();
        for (FieldDescriptor field : descriptor.fields()) {
            if (!message.hasField(field)) {
                continue;
            }
            Object value = message.getField(field);
            final FieldDescriptor capturedField = field;
            final Object capturedValue = value;
            actions.add(new FieldAction(capturedField.number(),
                    () -> writeKnownField(capturedField, capturedValue, writer, depth)));
        }
        for (UnknownField unknownField : message.unknownFields()) {
            final UnknownField capturedUnknown = unknownField;
            actions.add(new FieldAction(capturedUnknown.number(),
                    () -> writeUnknownField(capturedUnknown, writer)));
        }
        actions.sort(Comparator.comparingInt(FieldAction::number));
        for (FieldAction action : actions) {
            action.write().run();
        }
    }

    private record FieldAction(int number, Runnable write) {
    }

    private void writeKnownField(FieldDescriptor field, Object value, ByteWriter writer, int depth) {
        int tag = (field.number() << 3) | field.wireType().value();
        if (field.isMap()) {
            writeMapField(field, (Map<?, ?>) value, writer, depth);
        } else if (field.isRepeated()) {
            writeRepeatedField(field, (List<?>) value, tag, writer, depth);
        } else {
            writer.writeVarint(tag);
            writeSingularValue(field, value, writer, depth);
        }
    }

    private void writeRepeatedField(FieldDescriptor field, List<?> values, int tag,
                                    ByteWriter writer, int depth) {
        if (values.isEmpty()) {
            return;
        }
        if (field.isPackable()) {
            ByteWriter packed = new ByteWriter();
            for (Object element : values) {
                writeScalarValue(field, element, packed);
            }
            int packedTag = (field.number() << 3) | WireType.LENGTH_DELIMITED.value();
            writer.writeVarint(packedTag);
            writer.writeVarint(packed.position());
            writer.writeBytes(packed.toByteArray());
        } else {
            for (Object element : values) {
                writer.writeVarint(tag);
                writeSingularValue(field, element, writer, depth);
            }
        }
    }

    private void writeMapField(FieldDescriptor field, Map<?, ?> rawMap, ByteWriter writer, int depth) {
        TreeMap<Object, Object> sorted = new TreeMap<>(mapKeyComparator(field.mapKeyScalarType()));
        sorted.putAll(rawMap);
        int tag = (field.number() << 3) | WireType.LENGTH_DELIMITED.value();
        for (Map.Entry<Object, Object> entry : sorted.entrySet()) {
            ByteWriter entryWriter = new ByteWriter();
            writeMapEntryScalar(field.mapKeyScalarType(), 1, entry.getKey(), entryWriter);
            if (field.scalarType() != null) {
                writeMapEntryScalar(field.scalarType(), 2, entry.getValue(), entryWriter);
            } else if (field.isEnum()) {
                writeMapEntryEnum(field, 2, entry.getValue(), entryWriter);
            } else {
                writeMapEntryMessage(field, 2, entry.getValue(), entryWriter, depth);
            }
            writer.writeVarint(tag);
            writer.writeVarint(entryWriter.position());
            writer.writeBytes(entryWriter.toByteArray());
        }
    }

    private void writeSingularValue(FieldDescriptor field, Object value, ByteWriter writer, int depth) {
        if (field.isMessage()) {
            ByteWriter nested = new ByteWriter();
            writeMessage((MessageValue) value, nested, depth + 1);
            writer.writeVarint(nested.position());
            writer.writeBytes(nested.toByteArray());
            return;
        }
        writeScalarValue(field, value, writer);
    }

    private void writeScalarValue(FieldDescriptor field, Object raw, ByteWriter writer) {
        if (field.isEnum()) {
            EnumValue enumValue = resolveEnumValue(field, raw);
            writer.writeVarint(Varint.encodeZigZag32(enumValue.number));
            return;
        }
        writeScalar(field.scalarType(), raw, writer);
    }

    private void writeScalar(ScalarType type, Object raw, ByteWriter writer) {
        switch (type) {
            case INT32 -> writer.writeVarint(Varint.encodeZigZag32(((Number) raw).intValue()));
            case INT64 -> writer.writeVarint(Varint.encodeZigZag64(((Number) raw).longValue()));
            case BOOL -> writer.writeVarint(Boolean.TRUE.equals(raw) ? 1L : 0L);
            case FLOAT -> writer.writeFixed32(Float.floatToRawIntBits(((Number) raw).floatValue()));
            case DOUBLE -> writer.writeFixed64(Double.doubleToRawLongBits(((Number) raw).doubleValue()));
            case BYTES -> {
                byte[] data = (byte[]) raw;
                writer.writeVarint(data.length);
                writer.writeBytes(data);
            }
            case STRING -> {
                byte[] data = ((String) raw).getBytes(StandardCharsets.UTF_8);
                writer.writeVarint(data.length);
                writer.writeBytes(data);
            }
        }
    }

    private void writeMapEntryScalar(ScalarType type, int fieldNumber, Object raw, ByteWriter writer) {
        WireType wire = switch (type) {
            case INT32, INT64, BOOL -> WireType.VARINT;
            case FLOAT -> WireType.FIXED32;
            case DOUBLE -> WireType.FIXED64;
            case BYTES, STRING -> WireType.LENGTH_DELIMITED;
        };
        writer.writeVarint((fieldNumber << 3) | wire.value());
        writeScalar(type, raw, writer);
    }

    private void writeMapEntryEnum(FieldDescriptor field, int fieldNumber, Object raw, ByteWriter writer) {
        EnumValue enumValue = resolveEnumValue(field, raw);
        writer.writeVarint((fieldNumber << 3) | WireType.VARINT.value());
        writer.writeVarint(Varint.encodeZigZag32(enumValue.number));
    }

    private void writeMapEntryMessage(FieldDescriptor field, int fieldNumber, Object raw,
                                      ByteWriter writer, int depth) {
        ByteWriter nested = new ByteWriter();
        writeMessage((MessageValue) raw, nested, depth + 1);
        writer.writeVarint((fieldNumber << 3) | WireType.LENGTH_DELIMITED.value());
        writer.writeVarint(nested.position());
        writer.writeBytes(nested.toByteArray());
    }

    private void writeUnknownField(UnknownField unknownField, ByteWriter writer) {
        writer.writeVarint((unknownField.number() << 3) | unknownField.wireType().value());
        switch (unknownField.wireType()) {
            case VARINT -> writer.writeBytes(unknownField.payload());
            case FIXED32, FIXED64 -> writer.writeBytes(unknownField.payload());
            case LENGTH_DELIMITED -> {
                writer.writeVarint(unknownField.payload().length);
                writer.writeBytes(unknownField.payload());
            }
        }
    }

    private EnumValue resolveEnumValue(FieldDescriptor field, Object raw) {
        if (raw instanceof Number number) {
            return new EnumValue(number.intValue());
        }
        var enumValue = field.enumType().findByName(String.valueOf(raw));
        if (enumValue == null) {
            throw new CodecException("value '" + raw + "' is not a valid value of enum "
                    + field.enumType().fullName(), 0);
        }
        return new EnumValue(enumValue.number);
    }

    private Comparator<Object> mapKeyComparator(ScalarType keyType) {
        return (left, right) -> switch (keyType) {
            case STRING -> ((String) left).compareTo((String) right);
            case BOOL -> Boolean.compare((Boolean) left, (Boolean) right);
            default -> Long.compare(((Number) left).longValue(), ((Number) right).longValue());
        };
    }

    private record EnumValue(int number) {
    }
}
