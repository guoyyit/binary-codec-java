package com.github.guoyyit.binarycodecjava.tests;

import com.github.guoyyit.binarycodecjava.parser.SchemaException;
import com.github.guoyyit.binarycodecjava.registry.SchemaRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaValidationTest {

    private SchemaException register(String text) {
        SchemaRegistry registry = new SchemaRegistry();
        return assertThrows(SchemaException.class, () -> registry.register(text));
    }

    @Test
    void rejectsDuplicateFieldNumberWithLine() {
        SchemaException error = register("""
                schema s;
                version "1";
                message M {
                    int32 a = 1;
                    int32 b = 1;
                }
                """);
        assertEquals(5, error.line());
        assertTrue(error.getMessage().contains("duplicate field number"));
    }

    @Test
    void rejectsDuplicateFieldName() {
        SchemaException error = register("""
                schema s;
                version "1";
                message M {
                    int32 a = 1;
                    string a = 2;
                }
                """);
        assertEquals(5, error.line());
    }

    @Test
    void rejectsReusedReservedNumber() {
        SchemaException error = register("""
                schema s;
                version "1";
                message M {
                    reserved 5 to 10;
                    int32 a = 7;
                }
                """);
        assertEquals(5, error.line());
        assertTrue(error.getMessage().contains("reserved number"));
    }

    @Test
    void rejectsReusedReservedName() {
        SchemaException error = register("""
                schema s;
                version "1";
                message M {
                    reserved "old";
                    int32 old = 1;
                }
                """);
        assertEquals(5, error.line());
    }

    @Test
    void rejectsUnknownTypeReference() {
        SchemaException error = register("""
                schema s;
                version "1";
                message M {
                    Missing x = 1;
                }
                """);
        assertEquals(4, error.line());
        assertTrue(error.getMessage().contains("unknown type"));
    }

    @Test
    void rejectsDuplicateMessageDefinition() {
        SchemaException error = register("""
                schema s;
                version "1";
                message M {
                    int32 a = 1;
                }
                message M {
                    int32 b = 2;
                }
                """);
        assertEquals(6, error.line());
        assertTrue(error.getMessage().contains("duplicate type definition"));
    }

    @Test
    void rejectsDuplicateEnumValueAndNumber() {
        SchemaException error = register("""
                schema s;
                version "1";
                enum E {
                    A = 0;
                    A = 1;
                }
                """);
        assertEquals(5, error.line());

        SchemaException numberError = register("""
                schema s;
                version "1";
                enum E {
                    A = 0;
                    B = 0;
                }
                """);
        assertEquals(5, numberError.line());
    }

    @Test
    void rejectsInvalidMapKeyType() {
        SchemaException error = register("""
                schema s;
                version "1";
                message M {
                    map<float, string> m = 1;
                }
                """);
        assertEquals(4, error.line());
    }

    @Test
    void rejectsFieldNumberOutOfRange() {
        SchemaException error = register("""
                schema s;
                version "1";
                message M {
                    int32 a = 0;
                }
                """);
        assertEquals(4, error.line());
    }

    @Test
    void failedRegistrationDoesNotAffectExistingSchema() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register("""
                schema good;
                version "1";
                message M {
                    int32 a = 1;
                }
                """);

        assertThrows(SchemaException.class, () -> registry.register("""
                schema bad;
                version "1";
                message N {
                    Missing x = 1;
                }
                """));

        assertTrue(registry.isRegistered("good"));
        assertFalse(registry.isRegistered("bad"));
        assertEquals("1", registry.get("good", "1").version());

        assertThrows(SchemaException.class, () -> registry.register("""
                schema good;
                version "1";
                message Duplicate {
                    int32 a = 1;
                }
                schema good;
                version "1";
                message Duplicate {
                    int32 a = 1;
                }
                """));
        assertTrue(registry.isRegistered("good"));
    }
}
