package de.shyim.shopware6.lsp

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.*
import java.util.concurrent.ConcurrentHashMap
import com.intellij.util.Alarm

@Service(Service.Level.PROJECT)
class ShopwareLspService(private val project: Project) : Disposable {
    private val catalogs = ConcurrentHashMap<LspClient, JsonObject>()
    private val startup = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    fun initialized(descriptor: ShopwareLspDescriptor, attempts: Int = 100) {
        // The public descriptor callback precedes the manager's Running state.
        startup.addRequest({
            if (project.isDisposed || !descriptor.active) return@addRequest
            val client = LspClientManager.getInstance(project).getClients(ShopwareLspIntegration::class.java)
                .firstOrNull { it.descriptor === descriptor && it.state == LspServerState.Running }
            if (client != null) background { catalog(client) }
            else if (attempts > 0) initialized(descriptor, attempts - 1)
        }, 50)
    }

    fun forget(descriptor: ShopwareLspDescriptor) {
        catalogs.keys.removeIf { it.descriptor === descriptor }
    }

    fun client(file: VirtualFile): LspClient = LspClientManager.getInstance(project)
        .getClients(ShopwareLspIntegration::class.java).filter {
            it.state == LspServerState.Running && (it.descriptor as ShopwareLspDescriptor).active &&
                it.descriptor.roots.any { root -> VfsUtilCore.isAncestor(root, file, false) }
        }.maxByOrNull { it.descriptor.roots.maxOf { root -> root.path.length } }
        ?: error("Shopware LSP is not active for this directory. Open a supported project file and check the Language Services status widget.")

    fun catalog(client: LspClient): JsonObject = catalogs[client] ?: requireNotNull(client.sendRequestSync(30_000) {
        (it as ShopwareLanguageServer).catalog()
    }).also { ShopwareProtocol.validateCatalog(it); catalogs[client] = it }

    fun background(task: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            try { task() } catch (error: com.intellij.openapi.progress.ProcessCanceledException) { throw error }
            catch (error: Exception) { notifyError(project, error.cause?.message ?: error.message ?: "Request failed") }
        }
    }

    override fun dispose() { catalogs.clear() }
}
