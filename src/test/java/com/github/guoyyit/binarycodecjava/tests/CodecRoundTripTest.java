package com.github.guoyyit.binarycodecjava.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.guoyyit.binarycodecjava.codec.Codec;
import com.github.guoyyit.binarycodecjava.codec.MessageValue;
import com.github.guoyyit.binarycodecjava.codec.SchemaRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CodecRoundTripTest {

    static final String SCHEMA = """
            enum Kind {
                USER = 0;
                ADMIN = 1;
            }
            message Address {
                string city = 1;
                int32 zip = 2;
            }
            message Person {
                int32 id = 1;
                optional string name = 2 [default = "anon"];
                repeated int64 scores = 3;
                map<string, int32> attrs = 4;
                Address addr = 5;
                Kind kind = 6 [default = USER];
                bool active = 7;
                double ratio = 8;
                float score = 9;
                bytes avatar = 10;
                oneof contact {
                    string email = 11;
                    string phone = 12;
                }
                reserved 100 to 199, 300;
            }
            """;

    SchemaRegistry registry;
    Codec codec;

    @BeforeEach
    void setUp() {
        registry = new SchemaRegistry();
        registry.register(SCHEMA);
        codec = new Codec(registry);
    }

    MessageValue samplePerson() {
        MessageValue addr = codec.newMessage("Address")
                .set("city", "Shanghai")
                .set("zip", 200000L);
        return codec.newMessage("Person")
                .set("id", 42L)
                .set("name", "vincent")
                .set("scores", List.of(1L, -2L, 300L))
                .set("attrs", Map.of("a", 1L, "b", -2L))
                .set("addr", addr)
                .set("kind", "ADMIN")
                .set("active", true)
                .set("ratio", 0.75d)
                .set("score", 9.5d)
                .set("avatar", new byte[]{1, 2, 3})
                .set("email", "v@example.com");
    }

    @Test
    void roundTripPreservesAllFieldKinds() {
        byte[] encoded = codec.encode(samplePerson());
        MessageValue decoded = codec.decode("Person", encoded);
        assertEquals(42L, decoded.get("id"));
        assertEquals("vincent", decoded.get("name"));
        assertEquals(List.of(1L, -2L, 300L), decoded.get("scores"));
        assertEquals(Map.of("a", 1L, "b", -2L), decoded.get("attrs"));
        MessageValue addr = assertInstanceOf(MessageValue.class, decoded.get("addr"));
        assertEquals("Shanghai", addr.get("city"));
        assertEquals(200000L, addr.get("zip"));
        assertEquals("ADMIN", decoded.get("kind"));
        assertEquals(true, decoded.get("active"));
        assertEquals(0.75d, decoded.get("ratio"));
        assertEquals(9.5d, (Double) decoded.get("score"), 1e-6);
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) decoded.get("avatar"));
        assertEquals("v@example.com", decoded.get("email"));
        assertFalse(decoded.has("phone"));
    }

    @Test
    void smallAndNegativeIntsStayCompact() {
        MessageValue small = codec.newMessage("Person").set("id", 1L);
        assertEquals(2, codec.encode(small).length);
        MessageValue negative = codec.newMessage("Person").set("id", -1L);
        assertEquals(2, codec.encode(negative).length);
        MessageValue big = codec.newMessage("Person").set("id", 300L);
        assertEquals(3, codec.encode(big).length);
    }

    @Test
    void encodingIsDeterministic() {
        MessageValue value = samplePerson();
        assertArrayEquals(codec.encode(value), codec.encode(value));
        MessageValue shuffled = codec.newMessage("Person")
                .set("attrs", Map.of("b", -2L, "a", 1L));
        MessageValue ordered = codec.newMessage("Person")
                .set("attrs", Map.of("a", 1L, "b", -2L));
        assertArrayEquals(codec.encode(ordered), codec.encode(shuffled));
    }

    @Test
    void unsetOptionalFieldsAreOmittedAndReadBackAsDefaults() {
        MessageValue value = codec.newMessage("Person").set("id", 7L);
        byte[] encoded = codec.encode(value);
        MessageValue decoded = codec.decode("Person", encoded);
        assertFalse(decoded.has("name"));
        assertEquals("anon", decoded.get("name"));
        assertEquals("USER", decoded.get("kind"));
        assertEquals(false, decoded.get("active"));
        assertEquals(List.of(), decoded.get("scores"));
        assertEquals(Map.of(), decoded.get("attrs"));
    }

    @Test
    void explicitDefaultIsDistinguishedFromUnset() {
        MessageValue explicit = codec.newMessage("Person").set("name", "anon");
        MessageValue unset = codec.newMessage("Person");
        assertTrue(explicit.has("name"));
        assertFalse(unset.has("name"));
        assertTrue(codec.encode(explicit).length > codec.encode(unset).length);
        MessageValue decoded = codec.decode("Person", codec.encode(explicit));
        assertTrue(decoded.has("name"));
        assertEquals("anon", decoded.get("name"));
    }

    @Test
    void oneofSelectionIsExclusive() {
        MessageValue value = codec.newMessage("Person")
                .set("email", "a@b.c")
                .set("phone", "123");
        assertFalse(value.has("email"));
        assertEquals("phone", value.selectedOneofField("contact"));
        MessageValue decoded = codec.decode("Person", codec.encode(value));
        assertEquals("123", decoded.get("phone"));
        assertFalse(decoded.has("email"));
    }

    @Test
    void packedRepeatedRoundTrips() {
        MessageValue value = codec.newMessage("Person").set("scores", List.of(5L, 6L, 7L));
        byte[] encoded = codec.encode(value);
        assertEquals(2 + 3, encoded.length);
        assertEquals(List.of(5L, 6L, 7L), codec.decode("Person", encoded).get("scores"));
    }

    @Test
    void enumRejectsUnknownConstantOnEncode() {
        MessageValue value = codec.newMessage("Person").set("kind", "GHOST");
        assertThrows(Exception.class, () -> codec.encode(value));
    }
}
