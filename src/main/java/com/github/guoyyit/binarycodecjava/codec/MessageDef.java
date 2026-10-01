package com.github.guoyyit.binarycodecjava.codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息定义：字段表、编号索引与保留编号区间。
 */
public final class MessageDef {

    private final String name;
    private final int line;
    private final List<FieldDef> fields;
    private final Map<Integer, FieldDef> byNumber;
    private final Map<String, FieldDef> byName;
    private final List<int[]> reservedRanges;
    private final Map<String, List<FieldDef>> oneofs;

    public MessageDef(String name, int line, List<FieldDef> fields, List<int[]> reservedRanges) {
        this.name = name;
        this.line = line;
        this.fields = List.copyOf(fields);
        this.reservedRanges = new ArrayList<>(reservedRanges);
        Map<Integer, FieldDef> numberIndex = new LinkedHashMap<>();
        Map<String, FieldDef> nameIndex = new LinkedHashMap<>();
        Map<String, List<FieldDef>> oneofIndex = new LinkedHashMap<>();
        for (FieldDef field : this.fields) {
            FieldDef clash = numberIndex.get(field.getNumber());
            if (clash != null) {
                throw new SchemaException("field number " + field.getNumber() + " of message '" + name
                        + "' conflicts with field '" + clash.getName() + "' declared at line " + clash.getLine(),
                        field.getLine());
            }
            if (nameIndex.put(field.getName(), field) != null) {
                throw new SchemaException("duplicate field name '" + field.getName() + "' in message '" + name + "'",
                        field.getLine());
            }
            numberIndex.put(field.getNumber(), field);
            if (isReserved(field.getNumber())) {
                throw new SchemaException("field '" + field.getName() + "' reuses reserved number "
                        + field.getNumber() + " of message '" + name + "'", field.getLine());
            }
            if (field.getOneofName() != null) {
                oneofIndex.computeIfAbsent(field.getOneofName(), k -> new ArrayList<>()).add(field);
            }
        }
        this.byNumber = Collections.unmodifiableMap(numberIndex);
        this.byName = Collections.unmodifiableMap(nameIndex);
        Map<String, List<FieldDef>> oneofCopy = new LinkedHashMap<>();
        oneofIndex.forEach((k, v) -> oneofCopy.put(k, List.copyOf(v)));
        this.oneofs = Collections.unmodifiableMap(oneofCopy);
    }

    public String getName() {
        return name;
    }

    public int getLine() {
        return line;
    }

    public List<FieldDef> getFields() {
        return fields;
    }

    public FieldDef findField(int number) {
        return byNumber.get(number);
    }

    public FieldDef findField(String name) {
        return byName.get(name);
    }

    public boolean isReserved(int number) {
        for (int[] range : reservedRanges) {
            if (number >= range[0] && number <= range[1]) {
                return true;
            }
        }
        return false;
    }

    public Map<String, List<FieldDef>> getOneofs() {
        return oneofs;
    }
}
