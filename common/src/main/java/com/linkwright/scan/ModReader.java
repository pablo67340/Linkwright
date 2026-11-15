package com.linkwright.scan;

import java.io.IOException;
import java.util.List;

/** Reads one loader's mod metadata format out of a jar. */
public interface ModReader {
    /** The mods this jar declares in this format; empty when it has none. */
    List<ModEntry> read(ModJar jar) throws IOException;

    /** The jars this format nests inside {@code jar}, as entry names. */
    List<String> nestedJars(ModJar jar) throws IOException;
}
