package com.linkwright;

import com.linkwright.link.AccessorBridge;
import com.linkwright.link.Bridge;
import com.linkwright.link.ClassMove;
import com.linkwright.link.FieldBridge;
import com.linkwright.link.ImplBridge;
import com.linkwright.link.PairReport;
import com.linkwright.link.Patch;
import com.linkwright.link.PatchSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each test builds "modb" twice from source (the 1.x that "moda" was compiled against, and the
 * 3.0 that is installed), lets Linkwright analyze the pair, then patches moda and runs it against
 * modb 3.0 in a fresh class loader, so the JVM's own verifier sees every patched class.
 */
class PatchTypesTest {
    @TempDir
    Path temp;

    private Linkwright.Analysis analysis;
    private Map<String, byte[]> modbNew;
    private Map<String, byte[]> moda;

    private void build(Map<String, String> modbOld, Map<String, String> modbNewSources, Map<String, String> modaSources,
                       Map<String, String> modaResources) throws Exception {
        Map<String, byte[]> old = TestMods.compile(temp, modbOld, Map.of());
        modbNew = TestMods.compile(temp, modbNewSources, Map.of());
        moda = TestMods.compile(temp, modaSources, old);
        Path mods = Files.createDirectories(temp.resolve("mods"));
        TestMods.jar(mods.resolve("modb.jar"), modbNew, TestMods.toml("modb", "3.0", ""), Map.of());
        TestMods.jar(mods.resolve("moda.jar"), moda,
                TestMods.toml("moda", "1.0", TestMods.requires("moda", "modb", "[1,2)")), modaResources);
        analysis = Linkwright.analyze(new TestPlatform(mods), LinkwrightConfig.defaults());
        System.out.println(ReportWriter.render(LinkwrightConfig.defaults(), "Test", "client",
                analysis.result.reports, null, analysis.warnings));
    }

    private PairReport report() {
        return analysis.result.reports.get(0);
    }

    private Object runApproved() throws Exception {
        assertEquals(PairReport.Decision.APPROVED, report().decision, report().problems.toString());
        return TestMods.run(modbNew, moda, new PatchSet(analysis.result.patches), "a.User", "run");
    }

    private long count(Class<? extends Patch> type) {
        return analysis.result.patches.stream().filter(type::isInstance).count();
    }

    @Test
    void unwrapsSupplierOptionalAndHolder() throws Exception {
        String item = "package b; public class Item { public final String name; public Item(String n) { name = n; } }";
        String block = "package b; public class Block { public final String name; public Block(String n) { name = n; } }";
        build(Map.of("b.Item", item, "b.Block", block,
                        "b.Items", """
                                package b;
                                public class Items {
                                    public static Item THING = new Item("thing");
                                    public static Item OTHER = new Item("other");
                                    public static Item thing() { return THING; }
                                }""",
                        "b.Blocks", "package b; public class Blocks { public static Block STONE = new Block(\"stone\"); }"),
                Map.of("b.Item", item, "b.Block", block,
                        "net.minecraft.core.Holder", "package net.minecraft.core; public interface Holder<T> { T value(); boolean isBound(); }",
                        "b.Ref", """
                                package b;
                                public class Ref<T> implements java.util.function.Supplier<T> {
                                    private final T value;
                                    public Ref(T value) { this.value = value; }
                                    public T get() { return value; }
                                }""",
                        "b.ItemRef", "package b; public class ItemRef<T extends Item> extends Ref<T> { public ItemRef(T v) { super(v); } }",
                        "b.Items", """
                                package b;
                                import java.util.Optional;
                                import java.util.function.Supplier;
                                public class Items {
                                    public static final Supplier<Item> THING = () -> new Item("thing");
                                    public static final ItemRef<Item> OTHER = new ItemRef<>(new Item("other"));
                                    public static Optional<Item> thing() { return Optional.of(new Item("thing2")); }
                                }""",
                        "b.Blocks", """
                                package b;
                                import net.minecraft.core.Holder;
                                public class Blocks {
                                    public static final Holder<Block> STONE = new Holder<Block>() {
                                        public Block value() { return new Block("stone"); }
                                        public boolean isBound() { return true; }
                                    };
                                }"""),
                Map.of("a.User", """
                        package a;
                        import b.*;
                        public class User {
                            public static String run() {
                                return Items.THING.name + "," + Items.thing().name + "," + Items.OTHER.name + "," + Blocks.STONE.name;
                            }
                        }"""), Map.of());
        assertEquals("thing,thing2,other,stone", runApproved());
        assertEquals(3, count(FieldBridge.class));
        assertEquals(1, count(Bridge.class));
    }

    @Test
    void refusesAnUnwrapTheGenericsDoNotProve() throws Exception {
        String item = "package b; public class Item { public final String name; public Item(String n) { name = n; } }";
        build(Map.of("b.Item", item, "b.Items", "package b; public class Items { public static Item THING = new Item(\"t\"); }"),
                Map.of("b.Item", item, "b.Items", """
                        package b;
                        public class Items { public static final java.util.function.Supplier<Object> THING = () -> new Item("t"); }"""),
                Map.of("a.User", "package a; public class User { public static String run() { return b.Items.THING.name; } }"),
                Map.of());
        assertEquals(PairReport.Decision.REFUSED, report().decision);
    }

    @Test
    void swapsFieldsForAccessorsAndBack() throws Exception {
        build(Map.of("b.Config", """
                        package b;
                        public class Config {
                            public int size;
                            public static String mode = "fast";
                            public boolean enabled = true;
                            public String getId() { return "cfg"; }
                        }"""),
                Map.of("b.Config", """
                        package b;
                        public class Config {
                            private int size;
                            private static String mode = "fast";
                            private boolean enabled = true;
                            public final String id = "cfg";
                            public int getSize() { return size; }
                            public Config setSize(int size) { this.size = size; return this; }
                            public static String getMode() { return mode; }
                            public boolean isEnabled() { return enabled; }
                        }"""),
                Map.of("a.User", """
                        package a;
                        import b.Config;
                        public class User {
                            public static String run() {
                                Config c = new Config();
                                c.size = 5;
                                return c.size + Config.mode + c.enabled + c.getId();
                            }
                        }"""), Map.of());
        assertEquals("5fasttruecfg", runApproved());
        assertEquals(5, count(AccessorBridge.class));
    }

    @Test
    void followsRecordStyleRenames() throws Exception {
        build(Map.of("b.Point", """
                        package b;
                        public class Point {
                            private final int x;
                            public Point(int x) { this.x = x; }
                            public int getX() { return x; }
                            public String name() { return "p"; }
                        }""",
                        "b.Named", "package b; public interface Named { String getName(); }",
                        "b.Names", "package b; public class Names { public static String of(Named n) { return n.getName(); } }"),
                Map.of("b.Point", "package b; public record Point(int x) { public String getName() { return \"p\"; } }",
                        "b.Named", "package b; public interface Named { String name(); }",
                        "b.Names", "package b; public class Names { public static String of(Named n) { return n.name(); } }"),
                Map.of("a.User", """
                        package a;
                        import b.*;
                        public class User implements Named {
                            public String getName() { return "user"; }
                            public static String run() {
                                Point p = new Point(7);
                                return p.getX() + p.name() + Names.of(new User());
                            }
                        }"""), Map.of());
        assertEquals("7puser", runApproved());
        assertEquals(2, count(Bridge.class));
        assertEquals(1, count(ImplBridge.class));
    }

    @Test
    void dropsParametersThatWereRemoved() throws Exception {
        build(Map.of("b.Api", """
                        package b;
                        public class Api {
                            public static String greet(String who, Object ctx) { return "hi " + who; }
                            public String tag(int level, String text) { return text; }
                        }"""),
                Map.of("b.Api", """
                        package b;
                        public class Api {
                            public static String greet(String who) { return "hello " + who; }
                            public String tag(String text) { return "<" + text + ">"; }
                        }"""),
                Map.of("a.User", """
                        package a;
                        public class User {
                            public static String run() { return b.Api.greet("bob", new Object()) + "|" + new b.Api().tag(3, "x"); }
                        }"""), Map.of());
        assertEquals("hello bob|<x>", runApproved());
    }

    @Test
    void refusesWhenWhichParameterWasRemovedIsAmbiguous() throws Exception {
        build(Map.of("b.Api", "package b; public class Api { public static String pick(String a, String b) { return a; } }"),
                Map.of("b.Api", "package b; public class Api { public static String pick(String a) { return a; } }"),
                Map.of("a.User", "package a; public class User { public static String run() { return b.Api.pick(\"x\", \"y\"); } }"),
                Map.of());
        assertEquals(PairReport.Decision.REFUSED, report().decision);
    }

    private static final String HELPER = """
            package com.modb.core.%s;
            public class Helper {
                public static String help() { return "h"; }
                public String name() { return "n"; }
            }""";

    private void buildMove(String extra) throws Exception {
        build(Map.of("com.modb.core.util.Helper", HELPER.formatted("util"),
                        "com.modb.core.Api", "package com.modb.core; public class Api { public static com.modb.core.util.Helper make() { return new com.modb.core.util.Helper(); } }"),
                Map.of("com.modb.core.common.Helper", HELPER.formatted("common"),
                        "com.modb.core.Api", "package com.modb.core; public class Api { public static com.modb.core.common.Helper make() { return new com.modb.core.common.Helper(); } }"),
                Map.of("a.User", """
                        package a;
                        import com.modb.core.util.Helper;
                        public class User {
                            private Helper field = com.modb.core.Api.make();
                            public static String run() {
                                Helper h = new Helper();
                                %s
                                return Helper.help() + h.name() + new User().field.name() + describe(h);
                            }
                            static String describe(Helper h) { return h == null ? "null" : "ok"; }
                        }""".formatted(extra)), Map.of());
    }

    @Test
    void followsAClassThatMovedPackage() throws Exception {
        buildMove("");
        assertEquals("hnnok", runApproved());
        assertEquals(1, count(ClassMove.class));
    }

    @Test
    void refusesAMoveWhenTheOldNameIsAlsoAString() throws Exception {
        buildMove("String reflective = \"com.modb.core.util.Helper\";");
        assertEquals(PairReport.Decision.REFUSED, report().decision);
    }

    @Test
    void switchesBetweenStaticAndInstance() throws Exception {
        build(Map.of("b.Box", """
                        package b;
                        public class Box {
                            public int w = 3;
                            public int weight() { return w; }
                            public String version() { return "v1"; }
                        }""",
                        "b.Api", "package b; public class Api { public static int size(Box b) { return b.w * 2; } }",
                        "b.Registry", "package b; public class Registry { public static String lookup(String k) { return k; } }"),
                Map.of("b.Box", """
                        package b;
                        public class Box {
                            public int w = 3;
                            public static int weight(Box b) { return b.w + 100; }
                            public static String version() { return "v3"; }
                            public int size() { return w * 10; }
                        }""",
                        "b.Api", "package b; public class Api { }",
                        "b.Registry", """
                                package b;
                                public class Registry {
                                    public static final Registry INSTANCE = new Registry();
                                    public String lookup(String k) { return "[" + k + "]"; }
                                }"""),
                Map.of("a.User", """
                        package a;
                        import b.*;
                        public class User {
                            public static String run() {
                                Box b = new Box();
                                return Api.size(b) + "," + b.weight() + "," + b.version() + "," + Registry.lookup("k");
                            }
                        }"""), Map.of());
        assertEquals("30,103,v3,[k]", runApproved());
        List<Bridge.Receiver> receivers = analysis.result.patches.stream().filter(Bridge.class::isInstance)
                .map(p -> ((Bridge) p).receiver).sorted().toList();
        assertEquals(List.of(Bridge.Receiver.DROP, Bridge.Receiver.FROM_ARG0, Bridge.Receiver.AS_ARG0,
                Bridge.Receiver.SINGLETON), receivers);
    }

    @Test
    void followsAClassThatBecameAnInterface() throws Exception {
        build(Map.of("b.Shape", "package b; public abstract class Shape { public abstract String name(); }",
                        "b.Shapes", "package b; public class Shapes { public static Shape circle() { return new Shape() { public String name() { return \"circle\"; } }; } }"),
                Map.of("b.Shape", "package b; public interface Shape { String name(); }",
                        "b.Shapes", "package b; public class Shapes { public static Shape circle() { return () -> \"circle\"; } }"),
                Map.of("a.User", "package a; public class User { public static String run() { return b.Shapes.circle().name(); } }"),
                Map.of());
        assertEquals("circle", runApproved());
        assertTrue(report().patches.get(0).describe().contains("now an interface"), report().patches.toString());
    }

    @Test
    void refusesAnImplementationWhenASupertypeIsNotInstalled() throws Exception {
        String named = "package b; public interface Named { String getName(); }";
        Map<String, byte[]> old = TestMods.compile(temp, Map.of("b.Named", named), Map.of());
        modbNew = TestMods.compile(temp, Map.of("b.Named", "package b; public interface Named { String name(); }"), Map.of());
        Map<String, byte[]> classpath = new java.util.HashMap<>(old);
        classpath.putAll(TestMods.compile(temp, Map.of("c.Base", "package c; public class Base { public String name() { return \"base\"; } }"), Map.of()));
        moda = TestMods.compile(temp, Map.of("a.User", """
                package a;
                public class User extends c.Base implements b.Named {
                    public String getName() { return "user"; }
                }"""), classpath);
        Path mods = Files.createDirectories(temp.resolve("mods"));
        TestMods.jar(mods.resolve("modb.jar"), modbNew, TestMods.toml("modb", "3.0", ""), Map.of());
        TestMods.jar(mods.resolve("moda.jar"), moda,
                TestMods.toml("moda", "1.0", TestMods.requires("moda", "modb", "[1,2)")), Map.of());
        analysis = Linkwright.analyze(new TestPlatform(mods), LinkwrightConfig.defaults());

        assertEquals(PairReport.Decision.REFUSED, report().decision);
        assertTrue(report().problems.toString().contains("c.Base is not installed"), report().problems.toString());
        assertEquals(0, count(ImplBridge.class));
    }
}
