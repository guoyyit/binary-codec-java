package com.github.guoyyit.binarycodecjava.codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 解码后的消息值：仅保存显式出现的字段与未知字段。
 *
 * <p>值的 Java 表示：整数为 {@link Long}，布尔为 {@link Boolean}，浮点为 {@link Double}，
 * 字节串为 {@code byte[]}，字符串为 {@link String}，枚举为常量名 {@link String}，
 * 嵌套消息为 {@link MessageValue}，重复字段为 {@link List}，映射字段为 {@link Map}。
 */
public final class MessageValue {

    private final MessageDef def;
    private final Function<FieldDef, Object> defaultResolver;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final List<UnknownField> unknownFields = new ArrayList<>();

    public MessageValue(MessageDef def) {
        this(def, null);
    }

    MessageValue(MessageDef def, Function<FieldDef, Object> defaultResolver) {
        if (def == null) {
            throw new IllegalArgumentException("def must not be null");
        }
        this.def = def;
        this.defaultResolver = defaultResolver;
    }

    public MessageDef getDef() {
        return def;
    }

    /**
     * 字段是否被显式设置（含显式设置为默认值的情况）。
     */
    public boolean has(String fieldName) {
        return values.containsKey(fieldName);
    }

    /**
     * 读取字段值。未显式设置时返回模式默认值，未声明默认值时返回类型固有默认值。
     */
    public Object get(String fieldName) {
        FieldDef field = requireField(fieldName);
        Object value = values.get(fieldName);
        if (value != null) {
            return value;
        }
        return defaultOf(field);
    }

    /**
     * 设置字段值。设置互斥联合成员时会清除同组其他成员。
     */
    public MessageValue set(String fieldName, Object value) {
        FieldDef field = requireField(fieldName);
        if (value == null) {
            throw new IllegalArgumentException("value must not be null; use unset to clear '" + fieldName + "'");
        }
        if (field.getOneofName() != null) {
            for (FieldDef sibling : def.getOneofs().get(field.getOneofName())) {
                if (!sibling.getName().equals(fieldName)) {
                    values.remove(sibling.getName());
                }
            }
        }
        values.put(fieldName, value);
        return this;
    }

    /**
     * 清除字段的显式设置，使其回到未设置状态。
     */
    public MessageValue unset(String fieldName) {
        requireField(fieldName);
        values.remove(fieldName);
        return this;
    }

    /**
     * 当前互斥联合中被选中的字段名；不属于联合或无选中时返回 null。
     */
    public String selectedOneofField(String oneofName) {
        List<FieldDef> members = def.getOneofs().get(oneofName);
        if (members == null) {
            throw new IllegalArgumentException("message '" + def.getName() + "' has no oneof '" + oneofName + "'");
        }
        for (FieldDef member : members) {
            if (values.containsKey(member.getName())) {
                return member.getName();
            }
        }
        return null;
    }

    public List<UnknownField> getUnknownFields() {
        return Collections.unmodifiableList(unknownFields);
    }

    public void addUnknownField(UnknownField unknownField) {
        unknownFields.add(unknownField);
    }

    private FieldDef requireField(String fieldName) {
        FieldDef field = def.findField(fieldName);
        if (field == null) {
            throw new IllegalArgumentException(
                    "message '" + def.getName() + "' has no field '" + fieldName + "'");
        }
        return field;
    }

    private Object defaultOf(FieldDef field) {
        if (field.isRepeated()) {
            return List.of();
        }
        if (field.getType().getKind() == TypeRef.Kind.MAP) {
            return Map.of();
        }
        if (field.getDefaultValue() != null) {
            return field.getDefaultValue();
        }
        if (field.getType().getKind() == TypeRef.Kind.NAMED && defaultResolver != null) {
            return defaultResolver.apply(field);
        }
        return switch (field.getType().getKind()) {
            case INT32, INT64 -> 0L;
            case BOOL -> Boolean.FALSE;
            case FLOAT, DOUBLE -> 0.0d;
            case STRING -> "";
            case BYTES -> new byte[0];
            default -> null;
        };
    }
}
