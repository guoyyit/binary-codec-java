package com.github.guoyyit.binarycodecjava.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MessageValue {
    private final MessageDescriptor descriptor;
    private final Map<Integer, Object> values = new LinkedHashMap<>();
    private final List<UnknownField> unknownFields = new ArrayList<>();

    public MessageValue(MessageDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    public MessageDescriptor descriptor() {
        return descriptor;
    }

    public void setField(String fieldName, Object value) {
        setField(requireField(fieldName), value);
    }

    public void setField(FieldDescriptor field, Object value) {
        if (field == null) {
            throw new IllegalArgumentException("field descriptor must not be null");
        }
        if (field.inOneof() && value != null) {
            clearOneof(field.oneofName());
        }
        if (value == null) {
            values.remove(field.number());
        } else {
            values.put(field.number(), value);
        }
    }

    public Object getField(String fieldName) {
        return getField(requireField(fieldName));
    }

    public Object getField(FieldDescriptor field) {
        Object value = values.get(field.number());
        if (value != null) {
            return value;
        }
        if (field.isRepeated()) {
            return List.of();
        }
        if (field.isMap()) {
            return Map.of();
        }
        return field.scalarDefault();
    }

    public boolean hasField(String fieldName) {
        return hasField(requireField(fieldName));
    }

    public boolean hasField(FieldDescriptor field) {
        return values.containsKey(field.number());
    }

    public void clearField(String fieldName) {
        values.remove(requireField(fieldName).number());
    }

    public Map<Integer, Object> presentFields() {
        return values;
    }

    public FieldDescriptor oneofCase(String oneofName) {
        for (FieldDescriptor field : descriptor.oneofFields(oneofName)) {
            if (values.containsKey(field.number())) {
                return field;
            }
        }
        return null;
    }

    public void addUnknownField(UnknownField unknownField) {
        unknownFields.add(unknownField);
    }

    public List<UnknownField> unknownFields() {
        return unknownFields;
    }

    public int getInt(String fieldName) {
        return ((Number) getField(fieldName)).intValue();
    }

    public long getLong(String fieldName) {
        return ((Number) getField(fieldName)).longValue();
    }

    public boolean getBool(String fieldName) {
        return (Boolean) getField(fieldName);
    }

    public float getFloat(String fieldName) {
        return ((Number) getField(fieldName)).floatValue();
    }

    public double getDouble(String fieldName) {
        return ((Number) getField(fieldName)).doubleValue();
    }

    public String getString(String fieldName) {
        return (String) getField(fieldName);
    }

    public byte[] getBytes(String fieldName) {
        return (byte[]) getField(fieldName);
    }

    private FieldDescriptor requireField(String fieldName) {
        FieldDescriptor field = descriptor.fieldByName(fieldName);
        if (field == null) {
            throw new IllegalArgumentException(
                    "unknown field '" + fieldName + "' in message " + descriptor.fullName());
        }
        return field;
    }

    private void clearOneof(String oneofName) {
        for (FieldDescriptor member : descriptor.oneofFields(oneofName)) {
            values.remove(member.number());
        }
    }
}
