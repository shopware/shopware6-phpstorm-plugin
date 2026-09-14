package de.shyim.shopware6.lsp

import com.intellij.openapi.components.service
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

object ShopwareLspBinary {
    internal lateinit var pluginDirectory: Path

    fun target(os: String = System.getProperty("os.name"), arch: String = System.getProperty("os.arch")): String {
        val platform = when {
            os.startsWith("Mac", true) -> "mac"
            os.startsWith("Windows", true) -> "windows"
            os.startsWith("Linux", true) -> "linux"
            else -> error("Shopware LSP has no bundled binary for $os. Select a custom executable in Settings | Tools | Shopware LSP.")
        }
        val cpu = when (arch.lowercase()) {
            "aarch64", "arm64" -> "arm64"
            "amd64", "x86_64" -> "x86_64"
            else -> error("Shopware LSP has no bundled binary for $arch. Select a custom executable in settings.")
        }
        return "$platform-$cpu"
    }

    fun resolve(): Path {
        val custom = service<ShopwareLspExecutableSettings>().state.executable.trim()
        if (custom.isNotEmpty()) return Path.of(custom).also {
            require(Files.isRegularFile(it) && Files.isExecutable(it)) { "The configured Shopware LSP executable is not executable: $it" }
        }
        val directory = pluginDirectory.resolve("shopware-lsp").resolve(target())
        val executable = directory.resolve(if (target().startsWith("windows")) "shopware-lsp.exe" else "shopware-lsp")
        require(Files.isRegularFile(executable)) { "No bundled Shopware LSP for ${target()}. Select a custom executable in Settings | Tools | Shopware LSP." }
        verify(executable, Files.readString(directory.resolve("sha256.txt")).trim())
        if (!Files.isExecutable(executable)) require(executable.toFile().setExecutable(true, true)) { "Cannot make Shopware LSP executable: $executable" }
        return executable
    }

    fun verify(file: Path, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(HexFormat.of().formatHex(digest.digest()) == expected) { "Bundled Shopware LSP checksum mismatch. Reinstall the plugin." }
    }
}
