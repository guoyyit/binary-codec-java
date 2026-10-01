package com.github.guoyyit.binarycodecjava.tests;

import com.github.guoyyit.binarycodecjava.codec.Codec;
import com.github.guoyyit.binarycodecjava.model.MessageValue;
import com.github.guoyyit.binarycodecjava.registry.SchemaRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoundTripTest {

    private static final String SCHEMA = """
            schema demo;
            version "1.0";

            enum Color {
                UNKNOWN = 0;
                RED = 1;
                GREEN = 2;
            }

            message Address {
                string city = 1;
                int32 zip = 2;
            }

            message Person {
                reserved 10 to 12, 20;
                reserved "legacy_name";

                int32 age = 1;
                int64 big = 2;
                bool active = 3;
                float score = 4;
                double ratio = 5;
                bytes blob = 6;
                string name = 7;
                Color color = 8;
                Address address = 9;
                repeated int32 tags = 13;
                repeated string nicknames = 14;
                repeated Address offices = 15;
                map<string, string> labels = 16;
                map<int64, Color> color_by_code = 17;

                oneof contact {
                    string email = 21;
                    int64 phone = 22;
                }
            }
            """;

    private Codec codecWithSchema() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(SCHEMA);
        return new Codec(registry);
    }

    @Test
    void roundTripsEveryFieldKind() {
        Codec codec = codecWithSchema();
        MessageValue person = codec.newMessage("demo", "Person");
        person.setField("age", -7);
        person.setField("big", -1234567890123L);
        person.setField("active", true);
        person.setField("score", 1.5f);
        person.setField("ratio", 3.25d);
        person.setField("blob", new byte[]{1, 2, 3, -1});
        person.setField("name", "张三");
        person.setField("color", "GREEN");

        MessageValue address = codec.newMessage("demo", "Address");
        address.setField("city", "Shanghai");
        address.setField("zip", 200000);
        person.setField("address", address);

        person.setField("tags", new ArrayList<>(Arrays.asList(-1, 2, 300)));
        person.setField("nicknames", new ArrayList<>(List.of("a", "b")));

        MessageValue office = codec.newMessage("demo", "Address");
        office.setField("city", "Beijing");
        person.setField("offices", new ArrayList<>(List.of(office)));

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("z", "last");
        labels.put("a", "first");
        person.setField("labels", labels);

        Map<Long, String> colors = new LinkedHashMap<>();
        colors.put(20L, "GREEN");
        colors.put(5L, "RED");
        person.setField("color_by_code", colors);

        person.setField("email", "x@example.com");

        byte[] encoded = codec.encode(person);
        MessageValue decoded = codec.decode(encoded, "demo", "Person");

        assertEquals(-7, decoded.getInt("age"));
        assertEquals(-1234567890123L, decoded.getLong("big"));
        assertTrue(decoded.getBool("active"));
        assertEquals(1.5f, decoded.getFloat("score"), 0.0f);
        assertEquals(3.25d, decoded.getDouble("ratio"), 0.0d);
        assertArrayEquals(new byte[]{1, 2, 3, -1}, decoded.getBytes("blob"));
        assertEquals("张三", decoded.getString("name"));
        assertEquals("GREEN", decoded.getString("color"));

        MessageValue decodedAddress = (MessageValue) decoded.getField("address");
        assertEquals("Shanghai", decodedAddress.getString("city"));
        assertEquals(200000, decodedAddress.getInt("zip"));

        assertEquals(List.of(-1, 2, 300), decoded.getField("tags"));
        assertEquals(List.of("a", "b"), decoded.getField("nicknames"));
        List<?> offices = (List<?>) decoded.getField("offices");
        assertEquals(1, offices.size());
        assertEquals("Beijing", ((MessageValue) offices.get(0)).getString("city"));

        Map<?, ?> decodedLabels = (Map<?, ?>) decoded.getField("labels");
        assertEquals("first", decodedLabels.get("a"));
        assertEquals("last", decodedLabels.get("z"));
        Map<?, ?> decodedColors = (Map<?, ?>) decoded.getField("color_by_code");
        assertEquals("GREEN", decodedColors.get(20L));
        assertEquals("RED", decodedColors.get(5L));

        assertEquals("email", decoded.oneofCase("contact").name());
        assertEquals("x@example.com", decoded.getString("email"));
        assertFalse(decoded.hasField("phone"));
    }

    @Test
    void encodingIsDeterministicAcrossRepeatedRuns() {
        Codec codec = codecWithSchema();
        MessageValue person = codec.newMessage("demo", "Person");
        person.setField("name", "n");
        person.setField("age", 1);
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("b", "2");
        labels.put("a", "1");
        person.setField("labels", labels);

        byte[] first = codec.encode(person);
        byte[] second = codec.encode(person);
        assertArrayEquals(first, second);
    }

    @Test
    void smallAndNegativeIntegersStayCompact() {
        Codec codec = codecWithSchema();
        MessageValue person = codec.newMessage("demo", "Person");
        person.setField("age", 1);
        byte[] one = codec.encode(person);
        assertEquals(2, one.length);

        MessageValue negative = codec.newMessage("demo", "Person");
        negative.setField("age", -1);
        byte[] neg = codec.encode(negative);
        assertEquals(2, neg.length);
    }
}
