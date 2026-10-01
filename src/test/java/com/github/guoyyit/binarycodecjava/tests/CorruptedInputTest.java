package com.github.guoyyit.binarycodecjava.tests;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.guoyyit.binarycodecjava.codec.Codec;
import com.github.guoyyit.binarycodecjava.codec.CodecException;
import com.github.guoyyit.binarycodecjava.codec.CodecLimits;
import com.github.guoyyit.binarycodecjava.codec.MessageValue;
import com.github.guoyyit.binarycodecjava.codec.SchemaRegistry;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 损坏输入拒绝：截断、非法标记、越界长度、超限输入，均带位置信息且不出部分结果。
 */
class CorruptedInputTest {

    static final String SCHEMA = """
            message Node {
                int32 id = 1;
                string label = 2;
                Node child = 3;
                repeated int64 nums = 4;
            }
            """;

    Codec codec;

    @BeforeEach
    void setUp() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(SCHEMA);
        codec = new Codec(registry);
    }

    private CodecException decodeFails(byte[] data) {
        return assertThrows(CodecException.class, () -> codec.decode("Node", data));
    }

    @Test
    void truncatedInputIsRejectedWithPosition() {
        byte[] good = codec.encode(codec.newMessage("Node").set("id", 5L).set("label", "hello"));
        CodecException e = decodeFails(Arrays.copyOf(good, good.length - 2));
        assertTrue(e.getPosition() >= 0);
    }

    @Test
    void illegalWireTypeIsRejected() {
        for (int wireType : new int[]{3, 4, 6, 7}) {
            byte[] data = {(byte) ((1 << 3) | wireType), 0};
            CodecException e = decodeFails(data);
            assertTrue(e.getMessage().contains("wire type"), () -> "wireType=" + wireType);
        }
    }

    @Test
    void zeroFieldNumberIsRejected() {
        decodeFails(new byte[]{0x00, 0x01});
    }

    @Test
    void overlongLengthPrefixIsRejected() {
        byte[] data = {0x12, 0x7F, 0x41, 0x42};
        CodecException e = decodeFails(data);
        assertTrue(e.getMessage().contains("exceeds remaining"));
    }

    @Test
    void declaredLengthBeyondStringLimitIsRejected() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(SCHEMA);
        Codec strict = new Codec(registry, CodecLimits.builder().maxStringBytes(4).build());
        byte[] data = {0x12, 0x05, 'h', 'e', 'l', 'l', 'o'};
        CodecException e = assertThrows(CodecException.class, () -> strict.decode("Node", data));
        assertTrue(e.getMessage().contains("exceeds limit"));
    }

    @Test
    void nestingDepthLimitIsEnforced() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(SCHEMA);
        Codec shallow = new Codec(registry, CodecLimits.builder().maxDepth(3).build());
        MessageValue deep = codec.newMessage("Node").set("id", 1L);
        for (int i = 0; i < 5; i++) {
            deep = codec.newMessage("Node").set("child", deep);
        }
        byte[] data = codec.encode(deep);
        assertThrows(CodecException.class, () -> shallow.decode("Node", data));
    }

    @Test
    void containerElementLimitIsEnforced() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(SCHEMA);
        Codec strict = new Codec(registry, CodecLimits.builder().maxContainerElements(2).build());
        byte[] data = codec.encode(codec.newMessage("Node").set("nums", List.of(1L, 2L, 3L)));
        assertThrows(CodecException.class, () -> strict.decode("Node", data));
    }

    @Test
    void oversizedInputIsRejected() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(SCHEMA);
        Codec strict = new Codec(registry, CodecLimits.builder().maxMessageBytes(8).build());
        byte[] data = codec.encode(codec.newMessage("Node").set("label", "a much longer label"));
        assertThrows(CodecException.class, () -> strict.decode("Node", data));
    }

    @Test
    void varintOverflowIsRejected() {
        byte[] data = new byte[12];
        data[0] = 0x08;
        Arrays.fill(data, 1, 11, (byte) 0xFF);
        data[11] = 0x7F;
        decodeFails(data);
    }

    @Test
    void invalidUtf8IsRejected() {
        byte[] data = {0x12, 0x02, (byte) 0xC3, 0x28};
        CodecException e = decodeFails(data);
        assertTrue(e.getMessage().contains("UTF-8"));
    }

    @Test
    void mismatchedWireTypeForKnownFieldIsRejected() {
        byte[] data = {0x0A, 0x01, 0x01};
        CodecException e = decodeFails(data);
        assertTrue(e.getMessage().contains("expects wire type"));
    }
}
