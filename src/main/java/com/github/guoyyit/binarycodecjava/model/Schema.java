package com.github.guoyyit.binarycodecjava.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Schema {
    private final String name;
    private final String version;
    private final List<MessageDescriptor> messages;
    private final List<EnumDescriptor> enums;
    private final Map<String, MessageDescriptor> messagesByFullName = new LinkedHashMap<>();
    private final Map<String, EnumDescriptor> enumsByFullName = new LinkedHashMap<>();
    private final Map<String, MessageDescriptor> messagesBySimpleName = new LinkedHashMap<>();
    private final Map<String, EnumDescriptor> enumsBySimpleName = new LinkedHashMap<>();

    public Schema(String name,
                  String version,
                  List<MessageDescriptor> messages,
                  List<EnumDescriptor> enums) {
        this.name = name;
        this.version = version;
        this.messages = List.copyOf(messages);
        this.enums = List.copyOf(enums);
        for (MessageDescriptor message : messages) {
            indexMessage(message);
        }
        for (EnumDescriptor enumeration : enums) {
            indexEnum(enumeration);
        }
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public List<MessageDescriptor> messages() {
        return messages;
    }

    public List<EnumDescriptor> enums() {
        return enums;
    }

    public MessageDescriptor findMessage(String typeName) {
        MessageDescriptor exact = messagesByFullName.get(typeName);
        if (exact != null) {
            return exact;
        }
        if (typeName.startsWith(".")) {
            return messagesByFullName.get(typeName.substring(1));
        }
        return messagesBySimpleName.get(typeName);
    }

    public EnumDescriptor findEnum(String typeName) {
        EnumDescriptor exact = enumsByFullName.get(typeName);
        if (exact != null) {
            return exact;
        }
        if (typeName.startsWith(".")) {
            return enumsByFullName.get(typeName.substring(1));
        }
        return enumsBySimpleName.get(typeName);
    }

    private void indexMessage(MessageDescriptor message) {
        messagesByFullName.put(message.fullName(), message);
        messagesByFullName.putIfAbsent(message.fullName().substring(name.length() + 1), message);
        messagesBySimpleName.putIfAbsent(message.name(), message);
        for (MessageDescriptor nested : message.nestedMessages()) {
            indexMessage(nested);
        }
        for (EnumDescriptor nestedEnum : message.nestedEnums()) {
            indexEnum(nestedEnum);
        }
    }

    private void indexEnum(EnumDescriptor enumeration) {
        enumsByFullName.put(enumeration.fullName(), enumeration);
        enumsBySimpleName.putIfAbsent(enumeration.name(), enumeration);
    }
}
