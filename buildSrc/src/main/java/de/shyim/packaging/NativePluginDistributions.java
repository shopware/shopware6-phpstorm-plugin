package de.shyim.packaging;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;

/** Platform variants use the OS/arch modules introduced in IntelliJ 2026.1. */
public abstract class NativePluginDistributions extends DefaultTask {
    @InputFile public abstract RegularFileProperty getSourceArchive();
    @Input public abstract ListProperty<String> getTargets();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();

    @TaskAction public void build() throws Exception {
        Path output = getOutputDirectory().get().getAsFile().toPath();
        Files.createDirectories(output);
        // This directory is owned by this task; do not leave variants from an older version.
        try (var files = Files.list(output)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".zip")).toList()) {
                Files.delete(file);
            }
        }
        File source = getSourceArchive().get().getAsFile();
        for (String target : getTargets().get()) {
            Path destination = output.resolve(source.getName().replaceFirst("\\.zip$", "-" + target + ".zip"));
            boolean patched = false;
            try (ZipFile zip = new ZipFile(source); ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(destination))) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    int bin = name.indexOf("/shopware-lsp/");
                    if (bin >= 0) {
                        String relative = name.substring(bin + "/shopware-lsp/".length());
                        if (relative.contains("/") && !relative.startsWith(target + "/")) continue;
                    }
                    byte[] data;
                    try (var in = zip.getInputStream(entry)) { data = in.readAllBytes(); }
                    if (name.endsWith(".jar")) {
                        byte[] rewritten = patchJar(data, target);
                        if (rewritten != null) {
                            if (patched) throw new IOException("Multiple plugin descriptors found");
                            patched = true;
                            data = rewritten;
                        }
                    }
                    ZipEntry copy = new ZipEntry(name);
                    copy.setTime(0);
                    out.putNextEntry(copy);
                    out.write(data);
                    out.closeEntry();
                }
            }
            if (!patched) throw new IOException("Plugin descriptor not found in " + source);
        }
    }

    private byte[] patchJar(byte[] data, String target) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        boolean found = false;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(data)); ZipOutputStream out = new ZipOutputStream(result)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] bytes = in.readAllBytes();
                if (entry.getName().equals("META-INF/plugin.xml")) {
                    String xml = new String(bytes, StandardCharsets.UTF_8);
                    if (!xml.contains("<id>de.shyim.shopware6</id>")) return null;
                    String[] platform = target.split("-", 2);
                    xml = xml.replaceFirst("<version>([^<]+)</version>", "<version>$1-" + target + "</version>");
                    xml = xml.replace("</idea-plugin>", "<depends>com.intellij.modules.os." + platform[0] + "</depends>\n"
                        + "<depends>com.intellij.modules.arch." + platform[1] + "</depends>\n</idea-plugin>");
                    bytes = xml.getBytes(StandardCharsets.UTF_8);
                    found = true;
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(0);
                out.putNextEntry(copy);
                out.write(bytes);
                out.closeEntry();
            }
        }
        return found ? result.toByteArray() : null;
    }
}
