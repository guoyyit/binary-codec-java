package com.github.guoyyit.binarycodecjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.guoyyit.binarycodecjava.codec.SchemaException;
import com.github.guoyyit.binarycodecjava.codec.SchemaRegistry;
import org.junit.jupiter.api.Test;

/**
 * 注册阶段校验：编号冲突、重复定义、未知类型引用、保留编号复用，错误可定位到行。
 */
class RegistryValidationTest {

    private SchemaException registerFails(String schema) {
        SchemaRegistry registry = new SchemaRegistry();
        return assertThrows(SchemaException.class, () -> registry.register(schema));
    }

    @Test
    void duplicateFieldNumberIsRejectedWithLine() {
        SchemaException e = registerFails("""
                message M {
                    int32 a = 1;
                    int32 b = 1;
                }
                """);
        assertEquals(3, e.getLine());
        assertTrue(e.getMessage().contains("conflict"));
    }

    @Test
    void duplicateTypeDefinitionIsRejected() {
        SchemaException e = registerFails("""
                message M { int32 a = 1; }
                message M { int32 b = 1; }
                """);
        assertEquals(2, e.getLine());
        assertTrue(e.getMessage().contains("duplicate definition"));
    }

    @Test
    void duplicateAgainstAlreadyRegisteredIsRejected() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register("message M { int32 a = 1; }");
        SchemaException e = assertThrows(SchemaException.class,
                () -> registry.register("enum M { X = 0; }"));
        assertTrue(e.getMessage().contains("duplicate definition"));
    }

    @Test
    void unknownTypeReferenceIsRejectedWithLine() {
        SchemaException e = registerFails("""
                message M {
                    int32 a = 1;
                    Ghost g = 2;
                }
                """);
        assertEquals(3, e.getLine());
        assertTrue(e.getMessage().contains("unknown type 'Ghost'"));
    }

    @Test
    void reservedNumberReuseIsRejectedWithLine() {
        SchemaException e = registerFails("""
                message M {
                    reserved 10 to 20;
                    int32 a = 15;
                }
                """);
        assertEquals(3, e.getLine());
        assertTrue(e.getMessage().contains("reserved"));
    }

    @Test
    void reservedSingleNumberIsEnforced() {
        SchemaException e = registerFails("message M { reserved 7; int32 a = 7; }");
        assertTrue(e.getMessage().contains("reserved"));
    }

    @Test
    void failedRegistrationKeepsPreviousSchemaIntact() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register("message Good { int32 a = 1; }");
        assertThrows(SchemaException.class,
                () -> registry.register("message Bad { Missing x = 1; }"));
        assertNotNull(registry.findMessage("Good"));
        assertTrue(registry.findMessage("Bad") == null);
    }

    @Test
    void forwardReferenceWithinSameBatchIsAllowed() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register("""
                message A { B b = 1; }
                message B { int32 x = 1; }
                """);
        assertNotNull(registry.findMessage("A"));
        assertNotNull(registry.findMessage("B"));
    }

    @Test
    void invalidEnumDefaultIsRejected() {
        SchemaException e = registerFails("""
                enum K { A = 0; }
                message M { K k = 1 [default = B]; }
                """);
        assertTrue(e.getMessage().contains("not a constant"));
    }

    @Test
    void syntaxErrorReportsLine() {
        SchemaException e = registerFails("""
                message M {
                    int32 a = ;
                }
                """);
        assertEquals(2, e.getLine());
    }
}
