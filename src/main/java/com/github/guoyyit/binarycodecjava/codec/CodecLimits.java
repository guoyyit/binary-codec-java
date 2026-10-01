package com.github.guoyyit.binarycodecjava.codec;

/**
 * 解码约束上限：总长度、嵌套深度、重复/映射元素数与字符串长度。
 */
public final class CodecLimits {

    public static final CodecLimits DEFAULT = builder().build();

    private final int maxMessageBytes;
    private final int maxDepth;
    private final int maxStringBytes;
    private final int maxContainerElements;

    private CodecLimits(Builder builder) {
        this.maxMessageBytes = builder.maxMessageBytes;
        this.maxDepth = builder.maxDepth;
        this.maxStringBytes = builder.maxStringBytes;
        this.maxContainerElements = builder.maxContainerElements;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getMaxMessageBytes() {
        return maxMessageBytes;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public int getMaxStringBytes() {
        return maxStringBytes;
    }

    public int getMaxContainerElements() {
        return maxContainerElements;
    }

    public static final class Builder {
        private int maxMessageBytes = 4 * 1024 * 1024;
        private int maxDepth = 100;
        private int maxStringBytes = 1024 * 1024;
        private int maxContainerElements = 1 << 16;

        private Builder() {
        }

        public Builder maxMessageBytes(int value) {
            this.maxMessageBytes = requirePositive(value, "maxMessageBytes");
            return this;
        }

        public Builder maxDepth(int value) {
            this.maxDepth = requirePositive(value, "maxDepth");
            return this;
        }

        public Builder maxStringBytes(int value) {
            this.maxStringBytes = requirePositive(value, "maxStringBytes");
            return this;
        }

        public Builder maxContainerElements(int value) {
            this.maxContainerElements = requirePositive(value, "maxContainerElements");
            return this;
        }

        public CodecLimits build() {
            return new CodecLimits(this);
        }

        private static int requirePositive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive, got " + value);
            }
            return value;
        }
    }
}
