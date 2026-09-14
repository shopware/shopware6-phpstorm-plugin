package de.shyim.shopware6.lsp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.*
import com.intellij.platform.lsp.api.LspClient
import org.eclipse.lsp4j.WorkspaceEdit
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Preflight the complete edit, then apply it as one undoable IDE command. */
object ShopwareWorkspaceEdits {
    data class Change(val path: Path, val original: String?, var text: String, val stamp: Long?)

    fun toJson(edit: WorkspaceEdit): JsonObject = JsonObject().apply {
        edit.changes?.let { add("changes", ShopwareProtocol.gson.toJsonTree(it)) }
        edit.documentChanges?.let { changes ->
            add("documentChanges", JsonArray().apply {
                changes.forEach { add(ShopwareProtocol.gson.toJsonTree(if (it.isLeft) it.left else it.right)) }
            })
        }
    }

    fun apply(client: LspClient, edit: JsonObject) {
        ui {
            require(!client.project.isDisposed) { "The project is closed" }
            val manager = FileDocumentManager.getInstance()
            val changes = linkedMapOf<Path, Change>()
            val created = mutableSetOf<Path>()
            fun load(uri: String): Change {
                val path = checkedPath(uri, client.descriptor.roots.map { it.toNioPath() })
                return changes.getOrPut(path) {
                    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                    val document = file?.let { manager.getDocument(it) }
                    require(file == null || document != null) { "Cannot edit a binary file: $path" }
                    Change(path, document?.text, document?.text.orEmpty(), document?.modificationStamp)
                }
            }
            fun textEdit(uri: String, edits: JsonArray, version: Int? = null) {
                val change = load(uri)
                require(change.original != null || change.path in created) { "Edit refers to a missing file: $uri" }
                if (version != null && change.original != null) {
                    val file = requireNotNull(LocalFileSystem.getInstance().findFileByNioFile(change.path))
                    val document = requireNotNull(manager.getDocument(file))
                    require(client.getDocumentVersion(document) == version) { "Document changed; request a fresh preview: $uri" }
                }
                change.text = applyText(change.text, edits)
            }
            created.clear()
            try {
                edit.getAsJsonObject("changes")?.entrySet()?.forEach { (uri, edits) -> textEdit(uri, edits.asJsonArray) }
                edit.getAsJsonArray("documentChanges")?.forEach { value ->
                    val operation = value.asJsonObject
                    if (operation.has("kind")) {
                        require(operation.string("kind") == "create") { "Unsupported workspace resource operation: ${operation.string("kind")}" }
                        val change = load(operation.string("uri"))
                        val options = operation.getAsJsonObject("options")
                        val exists = change.original != null || change.path in created
                        if (exists && options?.get("ignoreIfExists")?.asBoolean == true && options.get("overwrite")?.asBoolean != true) return@forEach
                        require(!exists || options?.get("overwrite")?.asBoolean == true) { "File already exists: ${change.path}" }
                        created.add(change.path)
                        change.text = ""
                    } else {
                        val document = operation.getAsJsonObject("textDocument")
                        textEdit(document.string("uri"), operation.getAsJsonArray("edits"), document.get("version")?.takeUnless { it.isJsonNull }?.asInt)
                    }
                }
                val existing = changes.values.mapNotNull { LocalFileSystem.getInstance().findFileByNioFile(it.path) }
                require(!ReadonlyStatusHandler.getInstance(client.project).ensureFilesWritable(existing).hasReadonlyFiles()) { "Some files are read-only" }
                WriteCommandAction.runWriteCommandAction(client.project, "Shopware LSP", null, Runnable {
                    changes.values.forEach { change ->
                        val document = LocalFileSystem.getInstance().findFileByNioFile(change.path)?.let { manager.getDocument(it) }
                        require(document?.modificationStamp == change.stamp) { "Document changed; request a fresh preview: ${change.path}" }
                    }
                    val newFiles = mutableListOf<VirtualFile>()
                    val newDirectories = mutableListOf<VirtualFile>()
                    fun directory(path: Path): VirtualFile {
                        LocalFileSystem.getInstance().findFileByNioFile(path)?.let {
                            require(it.isDirectory) { "Not a directory: $path" }
                            return it
                        }
                        return directory(requireNotNull(path.parent)).createChildDirectory(this, path.fileName.toString())
                            .also { newDirectories.add(it) }
                    }
                    try {
                        changes.values.forEach { change ->
                            val file = LocalFileSystem.getInstance().findFileByNioFile(change.path) ?: directory(change.path.parent)
                                .createChildData(this, change.path.fileName.toString()).also { newFiles.add(it) }
                            requireNotNull(manager.getDocument(file)).setText(change.text)
                        }
                    } catch (error: Exception) {
                        changes.values.filter { it.original != null }.forEach { change ->
                            LocalFileSystem.getInstance().findFileByNioFile(change.path)?.let { manager.getDocument(it)?.setText(change.original!!) }
                        }
                        newFiles.asReversed().forEach { it.delete(this) }
                        newDirectories.asReversed().forEach { if (it.isValid && it.children.isEmpty()) it.delete(this) }
                        throw error
                    }
                })
            } finally { created.clear() }
        }
    }

    fun checkedPath(uri: String, roots: List<Path>): Path {
        val parsed = URI(uri)
        require(parsed.scheme == "file" && parsed.authority.isNullOrEmpty()) { "Only local file edits are supported" }
        val path = Path.of(parsed).toAbsolutePath().normalize()
        var ancestor = path
        while (!Files.exists(ancestor)) ancestor = requireNotNull(ancestor.parent)
        val resolved = ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize()
        require(roots.any { resolved.startsWith(it.toRealPath()) }) { "Workspace edit escapes the project: $uri" }
        return resolved
    }

    fun applyText(text: String, edits: JsonArray): String {
        val ranges = edits.map { value ->
            val edit = value.asJsonObject
            val range = edit.getAsJsonObject("range")
            Triple(offset(text, range.getAsJsonObject("start")), offset(text, range.getAsJsonObject("end")), edit.string("newText"))
        }.sortedBy { it.first }
        ranges.forEachIndexed { index, range ->
            require(range.first <= range.second && (index == 0 || ranges[index - 1].second <= range.first)) { "Invalid or overlapping text edits" }
        }
        return StringBuilder(text).apply { ranges.asReversed().forEach { replace(it.first, it.second, it.third) } }.toString()
    }

    fun offset(text: String, position: JsonObject): Int {
        val line = position.get("line").asInt
        val character = position.get("character").asInt
        require(line >= 0 && character >= 0)
        var start = 0
        repeat(line) { start = text.indexOf('\n', start).also { require(it >= 0) { "LSP line is out of bounds" } } + 1 }
        var end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        if (end > start && text[end - 1] == '\r') end--
        require(start + character <= end) { "LSP character is out of bounds" }
        return start + character
    }

    fun navigate(client: LspClient, uri: String, line: Int = 0) = ui {
        val parsed = URI(uri)
        require(parsed.scheme == "file" && parsed.authority.isNullOrEmpty()) { "Only local navigation is supported" }
        val path = Path.of(parsed)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.let { OpenFileDescriptor(client.project, it, line, 0).navigate(true) }
    }
}

internal fun <T> ui(block: () -> T): T {
    if (ApplicationManager.getApplication().isDispatchThread) return block()
    var result: Result<T>? = null
    ApplicationManager.getApplication().invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
}
