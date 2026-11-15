package com.linkwright.link;

/**
 * The runtime names of the few Minecraft classes Linkwright's conversions use. Mods see
 * Minecraft under different names depending on the loader and era: Mojang's own names on
 * NeoForge 1.20.5+ and Forge 1.20.6+, Mojang class names with SRG member names on Forge 1.17 to
 * 1.20.4, MCP names on Forge 1.13 to 1.16, Fabric's intermediary names on Fabric and Quilt.
 *
 * <p>Member names are only listed when they are stable. Otherwise each conversion finds its
 * method by shape (owner, static-ness, descriptor) among the indexed Minecraft classes, and is
 * simply unavailable when that is not unique.
 */
public final class McNames {
    public static final McNames NONE = new McNames(null, null, null, null, false);

    /** NeoForge 1.20.5+ and Forge 1.20.6+. */
    public static final McNames MOJANG = new McNames("net/minecraft/core/BlockPos", "net/minecraft/core/Vec3i",
            "net/minecraft/world/level/ChunkPos", "net/minecraft/resources/ResourceLocation", true);

    /** Forge 1.17 to 1.20.4 and NeoForge 20.2 to 20.4: Mojang class names, SRG member names. */
    public static final McNames SRG = new McNames("net/minecraft/core/BlockPos", "net/minecraft/core/Vec3i",
            "net/minecraft/world/level/ChunkPos", "net/minecraft/resources/ResourceLocation", false);

    /** Forge 1.16. */
    public static final McNames MCP_116 = new McNames("net/minecraft/util/math/BlockPos",
            "net/minecraft/util/math/vector/Vector3i", "net/minecraft/util/math/ChunkPos",
            "net/minecraft/util/ResourceLocation", false);

    /** Forge 1.12 to 1.15. */
    public static final McNames MCP = new McNames("net/minecraft/util/math/BlockPos", "net/minecraft/util/math/Vec3i",
            "net/minecraft/util/math/ChunkPos", "net/minecraft/util/ResourceLocation", false);

    /** Fabric and Quilt. */
    public static final McNames INTERMEDIARY = new McNames("net/minecraft/class_2338", "net/minecraft/class_2382",
            "net/minecraft/class_1923", "net/minecraft/class_2960", false);

    public final String blockPos;
    public final String vec3i;
    public final String chunkPos;
    public final String resourceLocation;
    /** Whether Mojang's member names ({@code asLong}, {@code of}, ...) are the runtime names. */
    public final boolean mojangMembers;

    public McNames(String blockPos, String vec3i, String chunkPos, String resourceLocation, boolean mojangMembers) {
        this.blockPos = blockPos;
        this.vec3i = vec3i;
        this.chunkPos = chunkPos;
        this.resourceLocation = resourceLocation;
        this.mojangMembers = mojangMembers;
    }
}
