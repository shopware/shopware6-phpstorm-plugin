package de.shyim.shopware6.lsp

import com.google.gson.JsonArray
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat

class ShopwareAdapterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `supported markers are bounded to the project root`() {
        val root = temporary.newFolder().toPath()
        assertFalse(ShopwareProjectDetection.supports(root))
        Files.writeString(root.resolve("composer.json"), """{"require":{"symfony/console":"*"}}""")
        assertFalse(ShopwareProjectDetection.supports(root))
        Files.writeString(root.resolve("composer.json"), """{"require-dev":{"symfony/framework-bundle":"*"}}""")
        assertTrue(ShopwareProjectDetection.supports(root))
        Files.writeString(root.resolve("composer.json"), """{"type":"shopware-platform-plugin"}""")
        assertTrue(ShopwareProjectDetection.supports(root))
        Files.writeString(root.resolve("composer.json"), "{}")
        Files.writeString(root.resolve("composer.lock"), """{"packages":[{"name":"shopware/core"}]}""")
        assertTrue(ShopwareProjectDetection.supports(root))
    }

    @Test fun `configuration permits a temporarily malformed composer file`() {
        val root = temporary.newFolder().toPath()
        Files.writeString(root.resolve("composer.json"), "{")
        assertFalse(ShopwareProjectDetection.supports(root))
        Files.createDirectories(root.resolve(".config/shopware"))
        Files.writeString(root.resolve(".config/shopware/lsp.yaml"), "{}")
        assertTrue(ShopwareProjectDetection.supports(root))
    }

    @Test fun `app manifests require Shopware schema evidence`() {
        val root = temporary.newFolder().toPath()
        Files.writeString(root.resolve("manifest.xml"), "<manifest/>")
        assertFalse(ShopwareProjectDetection.supports(root))
        Files.writeString(root.resolve("manifest.xml"), """<manifest schema="https://shopware.com/manifest-3.0.xsd"/>""")
        assertTrue(ShopwareProjectDetection.supports(root))
    }

    @Test fun `protocol negotiation rejects incompatible or generic presentations`() {
        fun result(version: Int, profile: String, active: Boolean = true) = InitializeResult(ServerCapabilities().apply {
            experimental = json("shopwareLSP" to json("protocolVersion" to version, "presentationProfile" to profile, "active" to active))
        })
        assertTrue(ShopwareProtocol.active(result(1, "framework")))
        assertFalse(ShopwareProtocol.active(result(1, "framework", false)))
        assertThrows(IllegalArgumentException::class.java) { ShopwareProtocol.active(result(2, "framework")) }
        assertThrows(IllegalArgumentException::class.java) { ShopwareProtocol.active(result(1, "full")) }
        val options = ShopwareProtocol.initialization("""{"traceProviders":true}""")
        assertFalse(options.get("allowUnsupportedProject").asBoolean)
        assertEquals(6, options.getAsJsonObject("shopwareClient").getAsJsonArray("supportedCommands").size())
    }

    @Test fun `UTF16 edits preserve emoji and CRLF line endings`() {
        val source = "A🛍️B\r\nsecond\r\n"
        val edits = JsonArray().apply { add(json("range" to json("start" to json("line" to 0, "character" to 4), "end" to json("line" to 0, "character" to 5)), "newText" to "!")) }
        assertEquals("A🛍️!\r\nsecond\r\n", ShopwareWorkspaceEdits.applyText(source, edits))
        assertEquals(7, ShopwareWorkspaceEdits.offset(source, json("line" to 1, "character" to 0)))
        assertThrows(IllegalArgumentException::class.java) { ShopwareWorkspaceEdits.offset(source, json("line" to 0, "character" to 100)) }
    }

    @Test fun `overlapping edits are rejected before applying any text`() {
        val edits = JsonArray()
        for (start in listOf(0, 1)) edits.add(json("range" to json("start" to json("line" to 0, "character" to start), "end" to json("line" to 0, "character" to 3)), "newText" to ""))
        assertThrows(IllegalArgumentException::class.java) { ShopwareWorkspaceEdits.applyText("abcd", edits) }
    }

    @Test fun `workspace edits cannot traverse outside the root`() {
        val root = temporary.newFolder().toPath()
        val sibling = temporary.newFolder().toPath()
        assertEquals(root.toRealPath().resolve("new/file.php"), ShopwareWorkspaceEdits.checkedPath(root.resolve("new/file.php").toUri().toString(), listOf(root)))
        assertThrows(IllegalArgumentException::class.java) { ShopwareWorkspaceEdits.checkedPath(sibling.resolve("file.php").toUri().toString(), listOf(root)) }
        if (!System.getProperty("os.name").startsWith("Windows")) {
            Files.createSymbolicLink(root.resolve("outside"), sibling)
            assertThrows(IllegalArgumentException::class.java) { ShopwareWorkspaceEdits.checkedPath(root.resolve("outside/file.php").toUri().toString(), listOf(root)) }
        }
    }

    @Test fun `binary checksums and platform mapping are verified`() {
        assertEquals("mac-arm64", ShopwareLspBinary.target("Mac OS X", "aarch64"))
        assertEquals("linux-x86_64", ShopwareLspBinary.target("Linux", "amd64"))
        assertEquals("windows-x86_64", ShopwareLspBinary.target("Windows 11", "amd64"))
        assertThrows(IllegalStateException::class.java) { ShopwareLspBinary.target("FreeBSD", "amd64") }
        val file = temporary.newFile().toPath()
        Files.writeString(file, "verified")
        val checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
        ShopwareLspBinary.verify(file, checksum)
        Files.writeString(file, "corrupted")
        assertThrows(IllegalArgumentException::class.java) { ShopwareLspBinary.verify(file, checksum) }
    }
}
