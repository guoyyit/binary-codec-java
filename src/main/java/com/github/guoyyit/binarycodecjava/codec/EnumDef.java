package com.github.guoyyit.binarycodecjava.codec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 枚举定义：名称到编号、编号到名称的双向映射。
 */
public final class EnumDef {

    public record Constant(String name, int value, int line) {
    }

    private final String name;
    private final int line;
    private final List<Constant> constants;
    private final Map<String, Integer> byName;
    private final Map<Integer, String> byValue;

    public EnumDef(String name, int line, List<Constant> constants) {
        this.name = name;
        this.line = line;
        this.constants = List.copyOf(constants);
        Map<String, Integer> nameIndex = new LinkedHashMap<>();
        Map<Integer, String> valueIndex = new LinkedHashMap<>();
        for (Constant constant : this.constants) {
            if (nameIndex.put(constant.name(), constant.value()) != null) {
                throw new SchemaException("duplicate constant '" + constant.name() + "' in enum '" + name + "'",
                        constant.line());
            }
            if (valueIndex.put(constant.value(), constant.name()) != null) {
                throw new SchemaException("duplicate value " + constant.value() + " in enum '" + name + "'",
                        constant.line());
            }
        }
        this.byName = Collections.unmodifiableMap(nameIndex);
        this.byValue = Collections.unmodifiableMap(valueIndex);
    }

    public String getName() {
        return name;
    }

    public int getLine() {
        return line;
    }

    public List<Constant> getConstants() {
        return constants;
    }

    public Integer findValue(String constantName) {
        return byName.get(constantName);
    }

    public String findName(int value) {
        return byValue.get(value);
    }
}
