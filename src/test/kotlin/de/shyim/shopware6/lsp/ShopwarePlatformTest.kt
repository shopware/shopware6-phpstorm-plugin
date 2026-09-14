package de.shyim.shopware6.lsp

import com.google.gson.JsonArray
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.lsp.api.*
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.lang.reflect.Proxy

class ShopwarePlatformTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture() = com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl()
    fun testBundledServerStartsOnceAndStops() {
        ExtensionPointName.create<LspIntegrationProvider>("com.intellij.platform.lsp.integrationProvider").extensionList
        myFixture.addFileToProject("composer.json", """{"type":"shopware-platform-plugin"}""")
        val root = myFixture.tempDirFixture.getFile("")!!
        com.intellij.testFramework.PsiTestUtil.addContentRoot(module, root)
        val manager = LspClientManager.getInstance(project)
        val provider = ShopwareLspIntegration::class.java
        try {
            val source = myFixture.addFileToProject("src/Example.php", "<?php").virtualFile
            myFixture.openFileInEditor(source)
            manager.startClientsIfNeeded(provider)
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Shopware LSP did not initialize", {
                manager.getClients(provider).any { it.state == LspServerState.Running }
            }, 30)
            val first = manager.getClients(provider).single()
            assertTrue((first.descriptor as ShopwareLspDescriptor).active)
            manager.ensureClientStarted(provider, ShopwareLspDescriptor(project, root))
            assertEquals(1, manager.getClients(provider).size)
            manager.stopClients(provider)
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Shopware LSP did not stop", {
                first.state == LspServerState.ShutdownNormally
            }, 15)
            manager.ensureClientStarted(provider, ShopwareLspDescriptor(project, root))
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Shopware LSP did not restart", {
                manager.getClients(provider).any { it.state == LspServerState.Running }
            }, 30)
            assertEquals(1, manager.getClients(provider).count { it.state == LspServerState.Running })
        } finally {
            manager.stopClients(provider)
        }
    }

    fun testProviderIsRegisteredWithoutLegacyIndexes() {
        val providers = ExtensionPointName.create<LspIntegrationProvider>("com.intellij.platform.lsp.integrationProvider").extensionList
        assertTrue(providers.any { it is ShopwareLspIntegration })
        val indexes = ExtensionPointName.create<com.intellij.util.indexing.FileBasedIndexExtension<*, *>>("com.intellij.fileBasedIndex").extensionList
        assertFalse(indexes.any { it.javaClass.name.startsWith("de.shyim.shopware6") })
    }

    fun testSupportedFilesStartExactlyTheProjectRoot() {
        myFixture.addFileToProject("composer.json", """{"type":"shopware-platform-plugin"}""")
        val file = myFixture.addFileToProject("src/Example.php", "<?php").virtualFile
        val root = myFixture.tempDirFixture.getFile("")!!
        com.intellij.testFramework.PsiTestUtil.addContentRoot(module, root)
        val descriptors = mutableListOf<LspClientDescriptor>()
        waitForRoot(file, root)
        ShopwareLspIntegration().fileOpened(project, file, object : LspIntegrationProvider.LspClientStarter {
            override fun ensureClientStarted(descriptor: LspClientDescriptor) { descriptors.add(descriptor) }
        })
        assertEquals(1, descriptors.size)
        assertEquals(myFixture.tempDirFixture.getFile("")!!.path, descriptors.single().roots.single().path)
        assertTrue(descriptors.single().isSupportedFile(file))
    }

    fun testCreateAndEditAreAppliedTogether() {
        val existing = myFixture.addFileToProject("existing.php", "old").virtualFile
        val root = myFixture.tempDirFixture.getFile("")!!
        val newUri = root.toNioPath().resolve("nested/new.php").toUri().toString()
        val operations = JsonArray().apply {
            add(json("kind" to "create", "uri" to newUri))
            add(textEdit(newUri, 0, "<?php // new"))
            add(textEdit(existing.toNioPath().toUri().toString(), 3, "changed"))
        }
        ShopwareWorkspaceEdits.apply(client(), json("documentChanges" to operations))
        assertEquals("changed", FileDocumentManager.getInstance().getDocument(existing)!!.text)
        val created = root.findFileByRelativePath("nested/new.php")!!
        assertEquals("<?php // new", FileDocumentManager.getInstance().getDocument(created)!!.text)
    }

    fun testStaleOrUnsupportedEditCannotPartiallyApply() {
        val existing = myFixture.addFileToProject("existing.php", "old").virtualFile
        val uri = existing.toNioPath().toUri().toString()
        val operations = JsonArray().apply {
            add(textEdit(uri, 3, "changed"))
            add(json("kind" to "delete", "uri" to uri))
        }
        assertThrows(IllegalArgumentException::class.java) { ShopwareWorkspaceEdits.apply(client(), json("documentChanges" to operations)) }
        assertEquals("old", FileDocumentManager.getInstance().getDocument(existing)!!.text)
        val stale = textEdit(uri, 3, "stale")
        stale.getAsJsonObject("textDocument").addProperty("version", 99)
        assertThrows(IllegalArgumentException::class.java) { ShopwareWorkspaceEdits.apply(client(), json("documentChanges" to JsonArray().apply { add(stale) })) }
        assertEquals("old", FileDocumentManager.getInstance().getDocument(existing)!!.text)
    }

    fun testLsp4jCreateOnlyCodeActionAppliesWithoutChangesMap() {
        val root = myFixture.tempDirFixture.getFile("")!!
        val uri = root.toNioPath().resolve("generated.php").toUri().toString()
        val edit = org.eclipse.lsp4j.WorkspaceEdit().apply {
            documentChanges = listOf(
                org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(org.eclipse.lsp4j.CreateFile(uri)),
                org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(org.eclipse.lsp4j.TextDocumentEdit(
                    org.eclipse.lsp4j.VersionedTextDocumentIdentifier(uri, null),
                    listOf(org.eclipse.lsp4j.TextEdit(org.eclipse.lsp4j.Range(org.eclipse.lsp4j.Position(0, 0), org.eclipse.lsp4j.Position(0, 0)), "<?php"))
                ))
            )
        }
        ShopwareWorkspaceEdits.apply(client(), ShopwareWorkspaceEdits.toJson(edit))
        assertEquals("<?php", FileDocumentManager.getInstance().getDocument(root.findChild("generated.php")!!)!!.text)
    }

    fun testDuplicateCreateIsRejectedBeforeWriting() {
        val root = myFixture.tempDirFixture.getFile("")!!
        val uri = root.toNioPath().resolve("duplicate.php").toUri().toString()
        val operations = JsonArray().apply {
            add(json("kind" to "create", "uri" to uri))
            add(textEdit(uri, 0, "original"))
            add(json("kind" to "create", "uri" to uri))
        }
        assertThrows(IllegalArgumentException::class.java) { ShopwareWorkspaceEdits.apply(client(), json("documentChanges" to operations)) }
        assertNull(root.findChild("duplicate.php"))
    }

    fun testWriteFailureRollsBackFilesAndNewDirectories() {
        val existing = myFixture.addFileToProject("existing.php", "old").virtualFile
        myFixture.addFileToProject("blocker", "not a directory")
        val root = myFixture.tempDirFixture.getFile("")!!
        val newUri = root.toNioPath().resolve("nested/deeper/new.php").toUri().toString()
        val blockedUri = root.toNioPath().resolve("blocker/new.php").toUri().toString()
        val operations = JsonArray().apply {
            add(textEdit(existing.toNioPath().toUri().toString(), 3, "changed"))
            add(json("kind" to "create", "uri" to newUri))
            add(textEdit(newUri, 0, "created"))
            add(json("kind" to "create", "uri" to blockedUri))
        }
        assertThrows(IllegalArgumentException::class.java) { ShopwareWorkspaceEdits.apply(client(), json("documentChanges" to operations)) }
        assertEquals("old", FileDocumentManager.getInstance().getDocument(existing)!!.text)
        assertNull(root.findChild("nested"))
    }

    fun testNestedContentRootsDoNotOpenFilesInBothServers() {
        myFixture.addFileToProject("composer.json", """{"type":"shopware-platform-plugin"}""")
        myFixture.addFileToProject("child/composer.json", """{"type":"shopware-platform-plugin"}""")
        val file = myFixture.addFileToProject("child/src/Example.php", "<?php").virtualFile
        val root = myFixture.tempDirFixture.getFile("")!!
        val child = root.findChild("child")!!
        com.intellij.testFramework.PsiTestUtil.addContentRoot(module, root)
        com.intellij.testFramework.PsiTestUtil.addContentRoot(module, child)
        waitForRoot(file, child)
        assertFalse(ShopwareLspDescriptor(project, root).isSupportedFile(file))
        assertTrue(ShopwareLspDescriptor(project, child).isSupportedFile(file))
    }

    fun testProjectDetectionUpdatesAfterMarkerChanges() {
        val file = myFixture.addFileToProject("src/Example.php", "<?php").virtualFile
        val root = myFixture.tempDirFixture.getFile("")!!
        com.intellij.testFramework.PsiTestUtil.addContentRoot(module, root)
        val detection = project.service<ShopwareProjectRoots>()
        fun awaitSupport(expected: Boolean) {
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Project detection did not update", {
                detection.supports(root.toNioPath()) == expected
            }, 10)
        }
        awaitSupport(false)
        val marker = myFixture.addFileToProject("composer.json", """{"type":"shopware-platform-plugin"}""").virtualFile
        waitForRoot(file, root)
        com.intellij.openapi.application.WriteAction.run<RuntimeException> {
            com.intellij.openapi.vfs.VfsUtil.saveText(marker, "{}")
        }
        awaitSupport(false)
        val configuration = myFixture.addFileToProject(".config/shopware/lsp.yaml", "{}").virtualFile
        waitForRoot(file, root)
        com.intellij.openapi.application.WriteAction.run<RuntimeException> { configuration.parent.rename(this, "disabled") }
        awaitSupport(false)
        com.intellij.openapi.application.WriteAction.run<RuntimeException> { configuration.parent.rename(this, "shopware") }
        waitForRoot(file, root)
        com.intellij.openapi.application.WriteAction.run<RuntimeException> { configuration.delete(this) }
        awaitSupport(false)
    }

    private fun waitForRoot(file: com.intellij.openapi.vfs.VirtualFile, root: com.intellij.openapi.vfs.VirtualFile) {
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Project root detection did not complete", {
            ShopwareLspIntegration.rootFor(project, file) == root
        }, 10)
    }

    private fun textEdit(uri: String, end: Int, text: String) = json("textDocument" to json("uri" to uri), "edits" to JsonArray().apply {
        add(json("range" to json("start" to json("line" to 0, "character" to 0), "end" to json("line" to 0, "character" to end)), "newText" to text))
    })

    private fun client(): LspClient {
        val descriptor = ShopwareLspDescriptor(project, myFixture.tempDirFixture.getFile("")!!)
        return Proxy.newProxyInstance(LspClient::class.java.classLoader, arrayOf(LspClient::class.java)) { _, method, _ ->
            when (method.name) {
                "getProject" -> project
                "getDescriptor" -> descriptor
                "getDocumentVersion" -> 1
                else -> error("Unexpected LSP call: ${method.name}")
            }
        } as LspClient
    }
}
