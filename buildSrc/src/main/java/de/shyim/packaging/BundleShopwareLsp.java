package de.shyim.packaging;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Verifies pinned release archives before extracting executable and shared UI assets. */
public abstract class BundleShopwareLsp extends DefaultTask {
    @InputFile public abstract RegularFileProperty getManifest();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();
    @LocalState public abstract DirectoryProperty getDownloadDirectory();

    @TaskAction public void bundle() throws Exception {
        Properties manifest = new Properties();
        try (var in = Files.newInputStream(getManifest().get().getAsFile().toPath())) { manifest.load(in); }
        Path output = getOutputDirectory().get().getAsFile().toPath();
        Path downloads = getDownloadDirectory().get().getAsFile().toPath();
        Files.createDirectories(output);
        Files.createDirectories(downloads);
        String version = manifest.getProperty("version");
        for (String target : manifest.getProperty("targets").split(",")) {
            String asset = manifest.getProperty(target + ".asset");
            String checksum = manifest.getProperty(target + ".sha256");
            Path archive = downloads.resolve(asset);
            if (!Files.exists(archive) || !sha256(archive).equals(checksum)) {
                Path temporary = Files.createTempFile(downloads, "download-", ".tmp");
                try {
                    var connection = URI.create("https://github.com/shopware/shopware-lsp/releases/download/" + version + "/" + asset).toURL().openConnection();
                    connection.setConnectTimeout(30_000);
                    connection.setReadTimeout(120_000);
                    try (var in = connection.getInputStream()) { Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING); }
                    if (!sha256(temporary).equals(checksum)) throw new IOException("Shopware LSP checksum mismatch: " + asset);
                    Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temporary); }
            }
            Path directory = output.resolve(target);
            Files.createDirectories(directory);
            String binary = target.startsWith("windows") ? "shopware-lsp.exe" : "shopware-lsp";
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                extract(zip, "extension/" + binary, directory.resolve(binary));
                directory.resolve(binary).toFile().setExecutable(true, false);
                Files.writeString(directory.resolve("sha256.txt"), sha256(directory.resolve(binary)));
                Files.writeString(directory.resolve("version.txt"), version);
                extract(zip, "extension/LICENSE.txt", directory.resolve("LICENSE.txt"));
                if (target.equals("mac-arm64")) {
                    extract(zip, "extension/dist/entityDesignerWebview.js", output.resolve("entityDesignerWebview.js"));
                }
            }
        }
    }

    private static void extract(ZipFile zip, String name, Path output) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) throw new IOException("Release archive is missing " + name);
        try (var in = zip.getInputStream(entry)) { Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING); }
    }

    public static String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
