package com.github.guoyyit.binarycodecjava.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.guoyyit.binarycodecjava.codec.Codec;
import com.github.guoyyit.binarycodecjava.codec.MessageValue;
import com.github.guoyyit.binarycodecjava.codec.SchemaRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 新旧版本模式共存演进：跨版本读写、未知字段保留与回写。
 */
class CompatibilityTest {

    static final String SCHEMA_V1 = """
            message Person {
                int32 id = 1;
                optional string name = 2;
            }
            """;

    static final String SCHEMA_V2 = """
            message Person {
                int32 id = 1;
                optional string name = 2;
                optional int32 age = 3 [default = 18];
                optional string nickname = 4;
            }
            """;

    private Codec codecOf(String schema) {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(schema);
        return new Codec(registry);
    }

    @Test
    void newReaderSeesSchemaDefaultsForMissingFields() {
        Codec oldCodec = codecOf(SCHEMA_V1);
        Codec newCodec = codecOf(SCHEMA_V2);
        byte[] bytes = oldCodec.encode(oldCodec.newMessage("Person")
                .set("id", 1L).set("name", "v"));
        MessageValue decoded = newCodec.decode("Person", bytes);
        assertEquals(1L, decoded.get("id"));
        assertFalse(decoded.has("age"));
        assertEquals(18L, decoded.get("age"));
        assertFalse(decoded.has("nickname"));
        assertEquals("", decoded.get("nickname"));
    }

    @Test
    void oldReaderSkipsAndPreservesUnknownFields() {
        Codec oldCodec = codecOf(SCHEMA_V1);
        Codec newCodec = codecOf(SCHEMA_V2);
        byte[] bytes = newCodec.encode(newCodec.newMessage("Person")
                .set("id", 2L).set("age", 33L).set("nickname", "vy"));
        MessageValue decoded = oldCodec.decode("Person", bytes);
        assertEquals(2L, decoded.get("id"));
        assertEquals(2, decoded.getUnknownFields().size());
        byte[] rewritten = oldCodec.encode(decoded);
        MessageValue backAgain = newCodec.decode("Person", rewritten);
        assertEquals(33L, backAgain.get("age"));
        assertEquals("vy", backAgain.get("nickname"));
    }

    @Test
    void unknownFieldsSurviveMultipleRewriteCycles() {
        Codec oldCodec = codecOf(SCHEMA_V1);
        Codec newCodec = codecOf(SCHEMA_V2);
        byte[] original = newCodec.encode(newCodec.newMessage("Person")
                .set("id", 3L).set("age", 40L));
        byte[] cycle1 = oldCodec.encode(oldCodec.decode("Person", original));
        byte[] cycle2 = oldCodec.encode(oldCodec.decode("Person", cycle1));
        assertArrayEquals(cycle1, cycle2);
        assertEquals(40L, newCodec.decode("Person", cycle2).get("age"));
    }

    @Test
    void unknownEnumValueIsPreservedAsUnknownField() {
        Codec wide = codecOf("""
                enum Kind { USER = 0; ADMIN = 1; SUPER = 2; }
                message Holder { Kind kind = 1; }
                """);
        Codec narrow = codecOf("""
                enum Kind { USER = 0; ADMIN = 1; }
                message Holder { Kind kind = 1; }
                """);
        byte[] bytes = wide.encode(wide.newMessage("Holder").set("kind", "SUPER"));
        MessageValue decoded = narrow.decode("Holder", bytes);
        assertFalse(decoded.has("kind"));
        assertEquals(1, decoded.getUnknownFields().size());
        MessageValue roundTrip = wide.decode("Holder", narrow.encode(decoded));
        assertEquals("SUPER", roundTrip.get("kind"));
    }

    @Test
    void enumWithoutDeclaredDefaultFallsBackToFirstConstant() {
        Codec codec = codecOf("""
                enum Kind { USER = 0; ADMIN = 1; }
                message Holder { Kind kind = 1; }
                """);
        MessageValue decoded = codec.decode("Holder", new byte[0]);
        assertFalse(decoded.has("kind"));
        assertEquals("USER", decoded.get("kind"));
    }

    @Test
    void unknownEnumValueInsideRepeatedFieldIsSkippedAndPreserved() {
        Codec wide = codecOf("""
                enum K { A = 0; B = 1; }
                message H { repeated K ks = 1; }
                """);
        Codec wider = codecOf("""
                enum K { A = 0; B = 1; C = 2; }
                message H { repeated K ks = 1; }
                """);
        byte[] bytes = wider.encode(wider.newMessage("H").set("ks", List.of("A", "C", "B")));
        MessageValue decoded = wide.decode("H", bytes);
        assertEquals(List.of("A", "B"), decoded.get("ks"));
        assertEquals(1, decoded.getUnknownFields().size());
        MessageValue restored = wider.decode("H", wide.encode(decoded));
        assertTrue(((List<?>) restored.get("ks")).containsAll(List.of("A", "B", "C")));
    }

    @Test
    void fieldTypeChangeAcrossVersionsIsRejectedAsIllegalWireType() {
        Codec v1 = codecOf("message M { int32 a = 1; }");
        Codec v2 = codecOf("message M { string a = 1; }");
        byte[] bytes = v1.encode(v1.newMessage("M").set("a", 5L));
        org.junit.jupiter.api.Assertions.assertThrows(
                com.github.guoyyit.binarycodecjava.codec.CodecException.class,
                () -> v2.decode("M", bytes));
    }

    @Test
    void hotRegistrationDoesNotDisturbExistingSchema() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(SCHEMA_V1);
        Codec codec = new Codec(registry);
        MessageValue before = codec.newMessage("Person").set("id", 9L);
        byte[] encodedBefore = codec.encode(before);
        registry.register("message Extra { int64 seq = 1; }");
        assertArrayEquals(encodedBefore, codec.encode(codec.newMessage("Person").set("id", 9L)));
        assertEquals(9L, codec.decode("Person", encodedBefore).get("id"));
        assertTrue(registry.findMessage("Extra") != null);
    }
}
