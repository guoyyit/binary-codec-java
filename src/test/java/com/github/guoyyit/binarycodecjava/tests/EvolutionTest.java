package com.github.guoyyit.binarycodecjava.tests;

import com.github.guoyyit.binarycodecjava.codec.Codec;
import com.github.guoyyit.binarycodecjava.model.MessageValue;
import com.github.guoyyit.binarycodecjava.model.UnknownField;
import com.github.guoyyit.binarycodecjava.model.WireType;
import com.github.guoyyit.binarycodecjava.registry.SchemaRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvolutionTest {

    private static final String V1 = """
            schema users;
            version "1";
            message User {
                string name = 1;
                int32 level = 2;
            }
            """;

    private static final String V2 = """
            schema users;
            version "2";
            enum Role {
                GUEST = 0;
                ADMIN = 1;
            }
            message User {
                string name = 1;
                int32 level = 2;
                string email = 3;
                Role role = 4;
                repeated int32 scores = 5;
            }
            """;

    private SchemaRegistry registryWith(String... schemas) {
        SchemaRegistry registry = new SchemaRegistry();
        for (String schema : schemas) {
            registry.register(schema);
        }
        return registry;
    }

    @Test
    void oldMessageReadByNewSchemaUsesDefaultsForMissingFields() {
        SchemaRegistry registry = registryWith(V1, V2);
        Codec codec = new Codec(registry);

        MessageValue v1User = codec.newMessage("users", "User");
        v1User.setField("name", "alice");
        byte[] wire = codec.encode(v1User);

        MessageValue v2User = codec.decode(wire, "users", "2", "User");
        assertEquals("alice", v2User.getString("name"));
        assertEquals(0, v2User.getInt("level"));
        assertEquals("", v2User.getString("email"));
        assertEquals("GUEST", v2User.getString("role"));
        assertFalse(v2User.hasField("email"));
        assertFalse(v2User.hasField("level"));
        assertFalse(v2User.hasField("role"));
    }

    @Test
    void newMessageReadByOldSchemaKeepsUnknownFields() {
        SchemaRegistry registry = registryWith(V1, V2);
        Codec codec = new Codec(registry);

        MessageValue v2User = codec.newMessage("users", "User");
        v2User.setField("name", "bob");
        v2User.setField("level", 3);
        v2User.setField("email", "bob@example.com");
        v2User.setField("role", "ADMIN");
        v2User.setField("scores", new java.util.ArrayList<>(List.of(10, 20)));

        byte[] v2Wire = codec.encode(v2User);
        MessageValue v1View = codec.decode(v2Wire, "users", "1", "User");
        assertEquals("bob", v1View.getString("name"));

        List<UnknownField> unknown = v1View.unknownFields();
        assertFalse(unknown.isEmpty());
        assertTrue(unknown.stream().anyMatch(f -> f.number() == 3
                && f.wireType() == WireType.LENGTH_DELIMITED));
        assertTrue(unknown.stream().anyMatch(f -> f.number() == 4
                && f.wireType() == WireType.VARINT));
    }

    @Test
    void unknownFieldsAreWrittenBackByteIdentically() {
        SchemaRegistry registry = registryWith(V1, V2);
        Codec codec = new Codec(registry);

        MessageValue producer = codec.newMessage("users", "User");
        producer.setField("name", "carol");
        producer.setField("email", "carol@example.com");
        producer.setField("role", "ADMIN");
        producer.setField("scores", new java.util.ArrayList<>(List.of(1, 2, 3)));
        byte[] originalWire = codec.encode(producer);

        MessageValue oldView = codec.decode(originalWire, "users", "1", "User");
        byte[] rewritten = codec.encode(oldView);
        assertArrayEquals(originalWire, rewritten);

        MessageValue newViewAgain = codec.decode(rewritten, "users", "2", "User");
        assertEquals("carol@example.com", newViewAgain.getString("email"));
        assertEquals("ADMIN", newViewAgain.getString("role"));
        assertEquals(List.of(1, 2, 3), newViewAgain.getField("scores"));
    }

    @Test
    void interspersedUnknownFieldsKeepOriginalOrdering() {
        String sparse = """
                schema order;
                version "1";
                message M {
                    int32 low = 1;
                    int32 high = 100;
                }
                """;
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(sparse);
        Codec codec = new Codec(registry);

        MessageValue value = codec.newMessage("order", "M");
        value.setField("low", 1);
        value.setField("high", 2);
        byte[] base = codec.encode(value);

        com.github.guoyyit.binarycodecjava.wire.ByteWriter writer =
                new com.github.guoyyit.binarycodecjava.wire.ByteWriter();
        writer.writeVarint((1 << 3) | 0);
        writer.writeVarint(com.github.guoyyit.binarycodecjava.wire.Varint.encodeZigZag32(1));
        writer.writeVarint((50 << 3) | 0);
        writer.writeVarint(7);
        writer.writeBytes(java.util.Arrays.copyOfRange(base, 2, base.length));
        byte[] crafted = writer.toByteArray();

        MessageValue oldView = codec.decode(crafted, "order", "M");
        assertEquals(1, oldView.getInt("low"));
        assertEquals(2, oldView.getInt("high"));
        assertEquals(1, oldView.unknownFields().size());
        assertEquals(50, oldView.unknownFields().get(0).number());

        assertArrayEquals(crafted, codec.encode(oldView));
    }

    @Test
    void acceptsLegacyNonPackedRepeatedScalar() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register("""
                schema legacy;
                version "1";
                message M {
                    repeated int32 nums = 1;
                }
                """);
        Codec codec = new Codec(registry);

        com.github.guoyyit.binarycodecjava.wire.ByteWriter writer =
                new com.github.guoyyit.binarycodecjava.wire.ByteWriter();
        for (int value : new int[]{3, 250, -4}) {
            writer.writeVarint((1 << 3) | 0);
            writer.writeVarint(com.github.guoyyit.binarycodecjava.wire.Varint.encodeZigZag32(value));
        }

        MessageValue decoded = codec.decode(writer.toByteArray(), "legacy", "M");
        assertEquals(java.util.List.of(3, 250, -4), decoded.getField("nums"));

        byte[] repacked = codec.encode(decoded);
        MessageValue recanonicalized = codec.decode(repacked, "legacy", "M");
        assertEquals(java.util.List.of(3, 250, -4), recanonicalized.getField("nums"));
    }

    @Test
    void explicitDefaultValueIsDistinguishedFromUnset() {
        SchemaRegistry registry = registryWith(V2);
        Codec codec = new Codec(registry);

        MessageValue unset = codec.newMessage("users", "User");
        byte[] unsetWire = codec.encode(unset);
        assertEquals(0, unsetWire.length);
        MessageValue decodedUnset = codec.decode(unsetWire, "users", "User");
        assertFalse(decodedUnset.hasField("level"));
        assertFalse(decodedUnset.hasField("name"));

        MessageValue explicit = codec.newMessage("users", "User");
        explicit.setField("level", 0);
        explicit.setField("name", "");
        byte[] explicitWire = codec.encode(explicit);
        assertTrue(explicitWire.length > 0);

        MessageValue decodedExplicit = codec.decode(explicitWire, "users", "User");
        assertTrue(decodedExplicit.hasField("level"));
        assertTrue(decodedExplicit.hasField("name"));
        assertEquals(0, decodedExplicit.getInt("level"));
        assertEquals("", decodedExplicit.getString("name"));
    }

    @Test
    void oneofFieldsAreMutuallyExclusive() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register("""
                schema contacts;
                version "1";
                message C {
                    oneof way {
                        string email = 1;
                        int64 phone = 2;
                    }
                }
                """);
        Codec codec = new Codec(registry);
        MessageValue value = codec.newMessage("contacts", "C");
        value.setField("email", "a@b.c");
        value.setField("phone", 123L);
        assertFalse(value.hasField("email"));
        assertTrue(value.hasField("phone"));

        byte[] wire = codec.encode(value);
        MessageValue decoded = codec.decode(wire, "contacts", "C");
        assertEquals("phone", decoded.oneofCase("way").name());
    }

    @Test
    void unknownEnumNumberIsKeptAsNumberAndRoundTrips() {
        SchemaRegistry registry = registryWith(V2);
        Codec codec = new Codec(registry);

        MessageValue producer = codec.newMessage("users", "User");
        producer.setField("name", "dave");
        producer.setField("role", "ADMIN");
        byte[] adminWire = codec.encode(producer);

        com.github.guoyyit.binarycodecjava.wire.ByteWriter writer =
                new com.github.guoyyit.binarycodecjava.wire.ByteWriter();
        writer.writeVarint((4 << 3) | 0);
        writer.writeVarint(com.github.guoyyit.binarycodecjava.wire.Varint.encodeZigZag32(99));
        byte[] futureRole = writer.toByteArray();

        byte[] namePrefix = java.util.Arrays.copyOfRange(adminWire, 0,
                adminWire.length - 2);
        byte[] futureWire = new byte[namePrefix.length + futureRole.length];
        System.arraycopy(namePrefix, 0, futureWire, 0, namePrefix.length);
        System.arraycopy(futureRole, 0, futureWire, namePrefix.length, futureRole.length);

        MessageValue decoded = codec.decode(futureWire, "users", "User");
        assertEquals(99L, decoded.getField("role"));
        assertArrayEquals(futureWire, codec.encode(decoded));
    }
}
