package com.github.guoyyit.binarycodecjava.codec;

import java.util.HashMap;
import java.util.Map;

/**
 * 模式注册表：并发可读，支持事务式热注册。注册失败不影响已生效模式。
 */
public final class SchemaRegistry {

    private record Snapshot(Map<String, MessageDef> messages, Map<String, EnumDef> enums) {
    }

    private final Object registerLock = new Object();
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());

    public SchemaRegistry() {
    }

    /**
     * 解析并注册模式声明文本。任一校验失败则整体拒绝，已注册内容保持不变。
     */
    public void register(String schemaText) {
        SchemaParser.ParseResult result = new SchemaParser().parse(schemaText);
        synchronized (registerLock) {
            Snapshot current = snapshot;
            Map<String, MessageDef> messages = new HashMap<>(current.messages());
            Map<String, EnumDef> enums = new HashMap<>(current.enums());
            for (MessageDef message : result.messages()) {
                requireAbsent(messages, enums, message.getName(), message.getLine());
                messages.put(message.getName(), message);
            }
            for (EnumDef enumDef : result.enums()) {
                requireAbsent(messages, enums, enumDef.getName(), enumDef.getLine());
                enums.put(enumDef.getName(), enumDef);
            }
            for (MessageDef message : result.messages()) {
                validateReferences(message, messages, enums);
            }
            snapshot = new Snapshot(Map.copyOf(messages), Map.copyOf(enums));
        }
    }

    private static void requireAbsent(Map<String, MessageDef> messages, Map<String, EnumDef> enums,
                                      String name, int line) {
        if (messages.containsKey(name) || enums.containsKey(name)) {
            throw new SchemaException("duplicate definition of '" + name + "'", line);
        }
    }

    private static void validateReferences(MessageDef message, Map<String, MessageDef> messages,
                                           Map<String, EnumDef> enums) {
        for (FieldDef field : message.getFields()) {
            validateType(field, field.getType(), messages, enums);
            if (field.getDefaultValue() != null) {
                validateDefault(message, field, enums);
            }
        }
    }

    private static void validateType(FieldDef field, TypeRef type, Map<String, MessageDef> messages,
                                     Map<String, EnumDef> enums) {
        if (type.getKind() == TypeRef.Kind.NAMED) {
            String name = type.getTypeName();
            if (!messages.containsKey(name) && !enums.containsKey(name)) {
                throw new SchemaException("field '" + field.getName() + "' references unknown type '" + name + "'",
                        field.getLine());
            }
        } else if (type.getKind() == TypeRef.Kind.MAP) {
            validateType(field, type.getValueType(), messages, enums);
        }
    }

    private static void validateDefault(MessageDef message, FieldDef field, Map<String, EnumDef> enums) {
        if (field.isRepeated() || field.getType().getKind() == TypeRef.Kind.MAP) {
            throw new SchemaException("field '" + field.getName() + "' of message '" + message.getName()
                    + "' cannot declare a default value", field.getLine());
        }
        if (field.getType().getKind() == TypeRef.Kind.NAMED) {
            EnumDef enumDef = enums.get(field.getType().getTypeName());
            if (enumDef == null) {
                throw new SchemaException("field '" + field.getName() + "' of message '" + message.getName()
                        + "' cannot declare a default value: type is a message", field.getLine());
            }
            if (enumDef.findValue((String) field.getDefaultValue()) == null) {
                throw new SchemaException("default value '" + field.getDefaultValue() + "' of field '"
                        + field.getName() + "' is not a constant of enum '" + enumDef.getName() + "'",
                        field.getLine());
            }
        }
    }

    public MessageDef findMessage(String name) {
        return snapshot.messages().get(name);
    }

    public EnumDef findEnum(String name) {
        return snapshot.enums().get(name);
    }

    /**
     * 判断具名引用是消息还是枚举；未注册时返回 null。
     */
    public TypeRef.Kind resolveNamed(String name) {
        Snapshot current = snapshot;
        if (current.messages().containsKey(name)) {
            return TypeRef.Kind.MESSAGE;
        }
        if (current.enums().containsKey(name)) {
            return TypeRef.Kind.ENUM;
        }
        return null;
    }

    /**
     * 枚举字段未声明默认值时回退到枚举第一个常量；消息类型返回 null。
     */
    java.util.function.Function<FieldDef, Object> defaultResolver() {
        return field -> {
            EnumDef enumDef = findEnum(field.getType().getTypeName());
            return enumDef == null ? null : enumDef.getConstants().get(0).name();
        };
    }
}
