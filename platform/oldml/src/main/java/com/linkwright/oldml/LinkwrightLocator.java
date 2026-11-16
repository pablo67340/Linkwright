package com.linkwright.oldml;

import com.linkwright.LwLog;
import com.linkwright.fml.LaunchArgs;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Offers Linkwright's own jar to FML as a mod on Forge 1.14 to 1.16.5. FML reads the located mods
 * in locator order, and asks each file's locator whether it is valid just before reading it; this
 * locator comes after FML's own, so when FML asks about Linkwright's jar every other mod has been
 * read and none checked yet. That is when the rules are relaxed.
 *
 * <p>FML loads locators in a class loader of its own, so this reaches the transformation service
 * through a {@link Runnable} it leaves in the system properties.
 */
public final class LinkwrightLocator implements IModLocator {
    private Path jar;
    private FileSystem fileSystem;

    @Override
    public List<IModFile> scanMods() {
        try {
            // Forge 1.13's locator interface takes FML's ModFile instead; it never loads this one
            Class.forName("net.minecraftforge.forgespi.locating.IModFile");
            jar = LaunchArgs.jarFile(LinkwrightLocator.class.getProtectionDomain().getCodeSource().getLocation().toString());
            fileSystem = FileSystems.newFileSystem(jar, (ClassLoader) null);
            return Collections.singletonList(newModFile(jar));
        } catch (Throwable t) {
            LwLog.error("Linkwright could not offer its jar to FML", t);
            return Collections.emptyList();
        }
    }

    /** {@code ModFile.newFMLInstance} from Forge 1.15, a public constructor before. */
    private IModFile newModFile(Path path) throws ReflectiveOperationException {
        Class<?> modFile = Class.forName("net.minecraftforge.fml.loading.moddiscovery.ModFile");
        try {
            return (IModFile) modFile.getMethod("newFMLInstance", Path.class, IModLocator.class).invoke(null, path, this);
        } catch (NoSuchMethodException e) {
            Constructor<?> constructor = modFile.getConstructor(Path.class, IModLocator.class);
            return (IModFile) constructor.newInstance(path, this);
        }
    }

    @Override
    public boolean isValid(IModFile modFile) {
        Object hook = System.getProperties().get(LinkwrightService.RELAX_HOOK);
        if (hook instanceof Runnable) {
            ((Runnable) hook).run();
        }
        return true;
    }

    @Override
    public String name() {
        return "linkwright";
    }

    @Override
    public Path findPath(IModFile modFile, String... path) {
        return fileSystem.getPath(path[0], Arrays.copyOfRange(path, 1, path.length));
    }

    @Override
    public void scanFile(IModFile modFile, Consumer<Path> pathConsumer) {
        try (Stream<Path> files = Files.walk(fileSystem.getPath("/"))) {
            List<Path> classes = files.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList());
            classes.forEach(pathConsumer);
        } catch (IOException e) {
            LwLog.error("Linkwright could not list its own classes", e);
        }
    }

    @Override
    public Optional<Manifest> findManifest(Path file) {
        try (InputStream in = Files.newInputStream(fileSystem.getPath("META-INF", "MANIFEST.MF"))) {
            return Optional.of(new Manifest(in));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
    }

    @Override
    public String toString() {
        return "{linkwright locator at " + jar + "}";
    }
}
