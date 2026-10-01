package com.github.guoyyit.binarycodecjava.codec;

/**
 * 消息字段定义：编号、名称、类型、重复/可选修饰与所属联合。
 */
public final class FieldDef {

    private final String name;
    private final int number;
    private final TypeRef type;
    private final boolean repeated;
    private final boolean optional;
    private final String oneofName;
    private final Object defaultValue;
    private final int line;

    public FieldDef(String name, int number, TypeRef type, boolean repeated, boolean optional,
                    String oneofName, Object defaultValue, int line) {
        this.name = name;
        this.number = number;
        this.type = type;
        this.repeated = repeated;
        this.optional = optional;
        this.oneofName = oneofName;
        this.defaultValue = defaultValue;
        this.line = line;
    }

    public String getName() {
        return name;
    }

    public int getNumber() {
        return number;
    }

    public TypeRef getType() {
        return type;
    }

    public boolean isRepeated() {
        return repeated;
    }

    public boolean isOptional() {
        return optional;
    }

    public String getOneofName() {
        return oneofName;
    }

    /**
     * 模式声明的默认值：Long/Boolean/Double/String，枚举为常量名字符串；未声明时为 null。
     */
    public Object getDefaultValue() {
        return defaultValue;
    }

    public int getLine() {
        return line;
    }
}
