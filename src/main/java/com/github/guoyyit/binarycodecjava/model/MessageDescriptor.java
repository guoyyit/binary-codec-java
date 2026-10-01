package com.github.guoyyit.binarycodecjava.model;

import java.util.ArrayList;
import java.util.List;

public final class MessageDescriptor {
    private final String name;
    private final String fullName;
    private final int declaredLine;
    private final List<FieldDescriptor> fields;
    private final List<OneofDescriptor> oneofs;
    private final List<MessageDescriptor> nestedMessages;
    private final List<EnumDescriptor> nestedEnums;
    private final List<int[]> reservedRanges;
    private final List<String> reservedNames;

    public MessageDescriptor(String name,
                             String fullName,
                             int declaredLine,
                             List<FieldDescriptor> fields,
                             List<OneofDescriptor> oneofs,
                             List<MessageDescriptor> nestedMessages,
                             List<EnumDescriptor> nestedEnums,
                             List<int[]> reservedRanges,
                             List<String> reservedNames) {
        this.name = name;
        this.fullName = fullName;
        this.declaredLine = declaredLine;
        this.fields = List.copyOf(fields);
        this.oneofs = List.copyOf(oneofs);
        this.nestedMessages = List.copyOf(nestedMessages);
        this.nestedEnums = List.copyOf(nestedEnums);
        this.reservedRanges = List.copyOf(reservedRanges);
        this.reservedNames = List.copyOf(reservedNames);
    }

    public String name() {
        return name;
    }

    public String fullName() {
        return fullName;
    }

    public int declaredLine() {
        return declaredLine;
    }

    public List<FieldDescriptor> fields() {
        return fields;
    }

    public List<OneofDescriptor> oneofs() {
        return oneofs;
    }

    public List<MessageDescriptor> nestedMessages() {
        return nestedMessages;
    }

    public List<EnumDescriptor> nestedEnums() {
        return nestedEnums;
    }

    public FieldDescriptor fieldByNumber(int number) {
        for (FieldDescriptor field : fields) {
            if (field.number() == number) {
                return field;
            }
        }
        return null;
    }

    public FieldDescriptor fieldByName(String fieldName) {
        for (FieldDescriptor field : fields) {
            if (field.name().equals(fieldName)) {
                return field;
            }
        }
        return null;
    }

    public List<FieldDescriptor> oneofFields(String oneofName) {
        for (OneofDescriptor oneof : oneofs) {
            if (oneof.name().equals(oneofName)) {
                return oneof.fields();
            }
        }
        return List.of();
    }

    public boolean isReservedNumber(int number) {
        for (int[] range : reservedRanges) {
            if (number >= range[0] && number <= range[1]) {
                return true;
            }
        }
        return false;
    }

    public boolean isReservedName(String reservedName) {
        return reservedNames.contains(reservedName);
    }

    public List<MessageDescriptor> allNestedMessages() {
        List<MessageDescriptor> result = new ArrayList<>(nestedMessages);
        for (MessageDescriptor nested : nestedMessages) {
            result.addAll(nested.allNestedMessages());
        }
        return result;
    }

    public List<EnumDescriptor> allNestedEnums() {
        List<EnumDescriptor> result = new ArrayList<>(nestedEnums);
        for (MessageDescriptor nested : nestedMessages) {
            result.addAll(nested.allNestedEnums());
        }
        return result;
    }
}
