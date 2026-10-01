package com.github.guoyyit.binarycodecjava.tests;

import com.github.guoyyit.binarycodecjava.codec.Codec;
import com.github.guoyyit.binarycodecjava.codec.CodecException;
import com.github.guoyyit.binarycodecjava.model.MessageValue;
import com.github.guoyyit.binarycodecjava.registry.SchemaRegistry;
import com.github.guoyyit.binarycodecjava.wire.ByteWriter;
import com.github.guoyyit.binarycodecjava.wire.Varint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorruptionTest {

    private static final String SCHEMA = """
            schema net;
            version "1";
            message Inner {
                int32 x = 1;
                Inner child = 3;
            }
            message Outer {
                int32 a = 1;
                string s = 2;
                Inner child = 3;
                bytes b = 4;
                repeated int32 nums = 5;
                float f = 6;
                double d = 7;
            }
            """;

    private Codec codec() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(SCHEMA);
        return new Codec(registry);
    }

    @Test
    void rejectsTruncatedVarint() {
        Codec codec = codec();
        byte[] data = {(byte) 0x08, (byte) 0x80};
        CodecException error = assertThrows(CodecException.class,
                () -> codec.decode(data, "net", "Outer"));
        assertTrue(error.position() >= 0);
    }

    @Test
    void rejectsTruncatedFixed32AndFixed64() {
        Codec codec = codec();
        CodecException floatError = assertThrows(CodecException.class,
                () -> codec.decode(new byte[]{0x35, 0x01, 0x02}, "net", "Outer"));
        assertTrue(floatError.getMessage().contains("truncated"));

        CodecException doubleError = assertThrows(CodecException.class,
                () -> codec.decode(new byte[]{0x39, 0x01}, "net", "Outer"));
        assertTrue(doubleError.getMessage().contains("truncated"));
    }

    @Test
    void rejectsIllegalWireType() {
        Codec codec = codec();
        byte[] data = {(byte) 0x0F};
        CodecException error = assertThrows(CodecException.class,
                () -> codec.decode(data, "net", "Outer"));
        assertTrue(error.getMessage().contains("illegal wire type"));
    }

    @Test
    void rejectsZeroTag() {
        Codec codec = codec();
        CodecException error = assertThrows(CodecException.class,
                () -> codec.decode(new byte[]{0x00}, "net", "Outer"));
        assertTrue(error.getMessage().contains("illegal field tag"));
    }

    @Test
    void rejectsLengthExceedingRemainingBytes() {
        Codec codec = codec();
        ByteWriter writer = new ByteWriter();
        writer.writeVarint((2 << 3) | 2);
        writer.writeVarint(100);
        writer.writeBytes(new byte[]{1, 2, 3});
        CodecException error = assertThrows(CodecException.class,
                () -> codec.decode(writer.toByteArray(), "net", "Outer"));
        assertTrue(error.getMessage().contains("exceeds remaining"));
    }

    @Test
    void rejectsLengthOverDeclaredLimit() {
        Codec codec = codec();
        ByteWriter writer = new ByteWriter();
        writer.writeVarint((4 << 3) | 2);
        writer.writeVarint(Integer.MAX_VALUE);
        CodecException error = assertThrows(CodecException.class,
                () -> codec.decode(writer.toByteArray(), "net", "Outer"));
        assertTrue(error.getMessage().contains("exceeds limit"));
    }

    @Test
    void rejectsVarintOverflow() {
        Codec codec = codec();
        byte[] data = new byte[11];
        for (int i = 0; i < 10; i++) {
            data[i] = (byte) 0x80;
        }
        data[10] = 0x01;
        assertThrows(CodecException.class, () -> codec.decode(data, "net", "Outer"));
    }

    @Test
    void rejectsInvalidUtf8() {
        Codec codec = codec();
        ByteWriter writer = new ByteWriter();
        writer.writeVarint((2 << 3) | 2);
        writer.writeVarint(2);
        writer.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFE});
        assertThrows(CodecException.class,
                () -> codec.decode(writer.toByteArray(), "net", "Outer"));
    }

    @Test
    void rejectsWireTypeMismatch() {
        Codec codec = codec();
        ByteWriter writer = new ByteWriter();
        writer.writeVarint((1 << 3) | 2);
        writer.writeVarint(1);
        writer.writeBytes(new byte[]{0});
        CodecException error = assertThrows(CodecException.class,
                () -> codec.decode(writer.toByteArray(), "net", "Outer"));
        assertTrue(error.getMessage().contains("expects wire type"));
    }

    @Test
    void rejectsExcessiveNestingDepth() {
        Codec codec = codec();
        byte[] nested = deeplyNestedMessage(80);
        CodecException error = assertThrows(CodecException.class,
                () -> codec.decode(nested, "net", "Outer"));
        assertTrue(error.getMessage().contains("nesting depth"));
    }

    @Test
    void acceptsMessageAtNestingDepthBoundary() {
        Codec codec = codec();
        byte[] nested = deeplyNestedMessage(64);
        MessageValue decoded = codec.decode(nested, "net", "Outer");
        MessageValue current = decoded;
        for (int i = 0; i < 63; i++) {
            current = (MessageValue) current.getField("child");
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, current.getInt("x"));
    }

    @Test
    void rejectsOversizedInputBeforeParsing() {
        Codec codec = codec();
        byte[] huge = new byte[70 * 1024 * 1024];
        CodecException error = assertThrows(CodecException.class,
                () -> codec.decode(huge, "net", "Outer"));
        assertTrue(error.getMessage().contains("input size"));
    }

    private byte[] deeplyNestedMessage(int depth) {
        ByteWriter innermost = new ByteWriter();
        innermost.writeVarint((1 << 3) | 0);
        innermost.writeVarint(Varint.encodeZigZag32(1));
        byte[] payload = innermost.toByteArray();
        for (int level = 0; level < depth - 1; level++) {
            ByteWriter outer = new ByteWriter();
            outer.writeVarint((3 << 3) | 2);
            outer.writeVarint(payload.length);
            outer.writeBytes(payload);
            payload = outer.toByteArray();
        }
        return payload;
    }
}
