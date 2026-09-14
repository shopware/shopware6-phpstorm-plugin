package de.shyim.shopware6.lsp

import com.google.gson.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.ui.jcef.*
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import javax.swing.*
import java.awt.Dimension

/** Uses the server release's shared designer. Kotlin only bridges messages and applies edits. */
class ShopwareEntityDesigner private constructor(private val client: LspClient, private val directory: VirtualFile) : DialogWrapper(client.project, false), Disposable {
    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Shopware entity designer").apply { isDaemon = true } }
    @Volatile private var closed = false
    private var previewRequest: JsonObject? = null
    private var preview: JsonObject? = null
    private var consumedRevision: String? = null

    init {
        title = "Shopware Entity Designer"
        isModal = false
        setOKButtonText("Close")
        Disposer.register(disposable, browser)
        Disposer.register(disposable, query)
        query.addHandler { payload ->
            executor.submit {
                if (!closed && !client.project.isDisposed) {
                    var message = JsonObject()
                    try {
                        message = JsonParser.parseString(payload).asJsonObject
                        handle(message)
                    } catch (error: Exception) {
                        send(json("type" to "error", "operation" to message.string("type"), "requestId" to message.get("requestId"), "message" to (error.cause?.message ?: error.message)))
                    }
                }
            }
            JBCefJSQuery.Response(null)
        }
        init()
        val script = Files.readString(ShopwareLspBinary.pluginDirectory.resolve("shopware-lsp/entityDesignerWebview.js"))
        val template = javaClass.getResourceAsStream("/lsp/entity-designer.html")!!.bufferedReader().use { it.readText() }
        val bridge = """
            let savedState;
            window.acquireVsCodeApi = () => ({
              postMessage: message => { ${query.inject("JSON.stringify(message)")} },
              setState: state => { savedState = state; }, getState: () => savedState
            });
        """.trimIndent()
        val theme = """<style>:root{--vscode-font-family:system-ui;--vscode-foreground:#252525;--vscode-editor-background:#fafafa;--vscode-input-background:#fff;--vscode-input-border:#aaa;--vscode-panel-border:#ccc;--vscode-button-background:#3574f0;--vscode-button-foreground:#fff;--vscode-descriptionForeground:#666;--vscode-errorForeground:#c62828;--vscode-focusBorder:#3574f0;--vscode-textCodeBlock-background:#eee;--vscode-button-secondaryBackground:#ddd;--vscode-button-secondaryForeground:#222}</style>"""
        browser.loadHTML(template.replace("__NONCE__", UUID.randomUUID().toString()).replace("__BRIDGE__", bridge)
            .replace("__SCRIPT__", script.replace("</script", "<\\/script")).replace("</head>", "$theme</head>"))
    }

    override fun createCenterPanel(): JComponent = browser.component.apply { preferredSize = Dimension(1200, 820) }
    override fun createActions(): Array<Action> = arrayOf(okAction)
    override fun dispose() {
        closed = true
        executor.shutdownNow()
        super.dispose()
    }

    private fun handle(message: JsonObject) {
        when (message.string("type")) {
            "ready" -> bootstrap()
            "search" -> {
                val result = client.sendRequestSync(30_000) { (it as ShopwareLanguageServer).entitySearch(json("query" to message.string("query"), "limit" to 100)) }
                send(json("type" to "search", "requestId" to message.get("requestId"), "value" to result))
            }
            "load" -> {
                val params = json("definitionClass" to message.string("definitionClass"), "definitionKind" to message.get("definitionKind"), "fileUri" to message.get("fileUri"), "documents" to documents())
                val result = ShopwareClientCommands.request(client) { it.entityLoad(params) }
                previewRequest = null; preview = null
                send(json("type" to "loaded", "value" to result))
            }
            "preview" -> {
                val request = json("spec" to message.getAsJsonObject("spec"), "decisions" to (message.get("decisions") ?: JsonArray()), "driftDecision" to message.get("driftDecision"), "documents" to documents())
                if (previewRequest != request || preview == null || preview?.string("revision") == consumedRevision) {
                    preview = ShopwareClientCommands.request(client) { it.entityPreview(request) }
                    // The server allocates the timestamp once; retain it for the exact subsequent apply request.
                    preview!!.get("migrationTimestamp")?.let { request.getAsJsonObject("spec").add("migrationTimestamp", it) }
                    previewRequest = request.deepCopy()
                }
                send(json("type" to "preview", "requestId" to message.get("requestId"), "value" to preview))
            }
            "apply" -> {
                val result = requireNotNull(preview) { "Preview the entity before applying it" }
                val request = requireNotNull(previewRequest).deepCopy()
                val revision = result.string("revision")
                require(revision.isNotBlank() && revision != consumedRevision) { "Request a fresh preview before applying again" }
                require(result.getAsJsonArray("issues")?.none { it.asJsonObject.string("severity") == "error" } != false) { "Resolve the preview issues before applying" }
                require(request.getAsJsonObject("documents") == documents()) { "Documents changed since preview. Refresh the preview." }
                require(!closed && !client.project.isDisposed)
                request.addProperty("revision", revision)
                request.addProperty("allowDestructive", message.get("allowDestructive")?.asBoolean == true)
                consumedRevision = revision
                val applied = ShopwareClientCommands.request(client) { it.entityApply(request) }
                require(!closed && !client.project.isDisposed)
                ShopwareClientCommands.applyResult(client, applied)
                send(json("type" to "applied", "snapshotId" to applied.string("snapshotId")))
            }
            "reconcile" -> {
                val result = ShopwareClientCommands.request(client) { it.entityReconcile(json("directoryUri" to directory.toNioPath().toUri().toString(), "selectedLeaf" to message.string("selectedLeaf"))) }
                ShopwareClientCommands.applyResult(client, result)
                bootstrap()
            }
            else -> error("Unsupported entity designer message: ${message.string("type")}")
        }
    }

    private fun bootstrap() {
        preview = null; previewRequest = null; consumedRevision = null
        val result = ShopwareClientCommands.request(client) { it.entityBootstrap(json("directoryUri" to directory.toNioPath().toUri().toString())) }
        send(json("type" to "bootstrap", "value" to result))
    }

    private fun documents(): JsonObject = ui {
        JsonObject().apply {
            FileDocumentManager.getInstance().unsavedDocuments.forEach { document ->
                val file = FileDocumentManager.getInstance().getFile(document) ?: return@forEach
                if (client.descriptor.roots.any { com.intellij.openapi.vfs.VfsUtilCore.isAncestor(it, file, false) }) {
                    val version = client.getDocumentVersion(document)
                    add(file.toNioPath().toUri().toString(), json("text" to document.text, "version" to version))
                }
            }
        }
    }

    private fun send(message: JsonObject) {
        ApplicationManager.getApplication().invokeLater {
            if (!closed) browser.cefBrowser.executeJavaScript("window.dispatchEvent(new MessageEvent('message', {data: ${ShopwareProtocol.gson.toJson(message)}}));", browser.cefBrowser.url, 0)
        }
    }

    companion object {
        fun open(client: LspClient, directory: VirtualFile) {
            require(JBCefApp.isSupported()) { "The entity designer requires JetBrains Runtime with JCEF support" }
            ShopwareEntityDesigner(client, directory).show()
        }
    }
}
