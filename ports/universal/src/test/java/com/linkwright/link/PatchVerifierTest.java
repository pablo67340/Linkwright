package com.linkwright.link;

import com.linkwright.TestMods;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Patches that would break a class must be caught before anything is approved. */
class PatchVerifierTest {
    @TempDir
    Path temp;

    private final PatchVerifier verifier = new PatchVerifier(new Hierarchy(new ClassIndex(List.of()), McNames.NONE));

    private byte[] user() throws Exception {
        Map<String, byte[]> api = TestMods.compile(temp, Map.of("b.Api", """
                package b;
                public class Api {
                    public static String NAME = "n";
                    public static String g(String s) { return s; }
                }"""), Map.of());
        return TestMods.compile(temp, Map.of("a.User", """
                package a;
                public class User {
                    static String call(String s) { return b.Api.g(s); }
                    static String read(boolean c) {
                        String s = c ? b.Api.NAME : "x";
                        return s;
                    }
                }"""), api).get("a/User");
    }

    @Test
    void refusesACallLeftShortOfAnArgument() throws Exception {
        Bridge broken = new Bridge("a/User", "call", "(Ljava/lang/String;)Ljava/lang/String;", Opcodes.INVOKESTATIC, "b/Api", "g",
                "(Ljava/lang/String;)Ljava/lang/String;", "(Ljava/lang/String;I)Ljava/lang/String;", false,
                List.<Bridge.Arg>of(new Bridge.FromOld(0, Conversion.IDENTITY)), Conversion.IDENTITY, 0);
        assertThrows(PatchVerifier.Failure.class, () -> verifier.patch(user(), List.of(broken)));
    }

    @Test
    void refusesAValueOfTheWrongTypeAcrossABranch() throws Exception {
        FieldBridge broken = new FieldBridge("a/User", "read", "(Z)Ljava/lang/String;", Opcodes.GETSTATIC, "b/Api", "NAME",
                "Ljava/lang/String;", "Ljava/lang/Object;", Conversion.IDENTITY);
        assertThrows(PatchVerifier.Failure.class, () -> verifier.patch(user(), List.of(broken)));
    }

    @Test
    void refusesAPatchThatChangesNothing() throws Exception {
        Bridge stray = new Bridge("a/User", "call", "(Ljava/lang/String;)Ljava/lang/String;", Opcodes.INVOKESTATIC, "b/Api", "gone",
                "()V", "(I)V", false, List.<Bridge.Arg>of(), Conversion.IDENTITY, 0);
        assertThrows(PatchVerifier.Failure.class, () -> verifier.patch(user(), List.of(stray)));
    }

    @Test
    void acceptsASoundPatch() throws Exception {
        Bridge sound = new Bridge("a/User", "call", "(Ljava/lang/String;)Ljava/lang/String;", Opcodes.INVOKESTATIC, "b/Api", "g",
                "(Ljava/lang/String;)Ljava/lang/String;", "()Ljava/lang/String;", false, List.<Bridge.Arg>of(),
                Conversion.IDENTITY, 0);
        assertNotNull(verifier.patch(user(), List.of(sound)));
    }
}
