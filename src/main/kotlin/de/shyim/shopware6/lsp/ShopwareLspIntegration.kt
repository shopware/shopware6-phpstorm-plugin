package de.shyim.shopware6.lsp

import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.extensions.PluginAware
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.*
import com.intellij.platform.lsp.api.customization.*
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import org.eclipse.lsp4j.*

class ShopwareLspIntegration : LspIntegrationProvider, PluginAware {
    override fun setPluginDescriptor(pluginDescriptor: PluginDescriptor) {
        ShopwareLspBinary.pluginDirectory = pluginDescriptor.pluginPath
    }

    override fun fileOpened(project: Project, file: VirtualFile, clientStarter: LspIntegrationProvider.LspClientStarter) {
        if (!file.isInLocalFileSystem || !project.service<ShopwareLspSettings>().state.enabled || file.extension?.lowercase() !in ShopwareProjectDetection.extensions) return
        val root = rootFor(project, file) ?: return
        project.service<ShopwareLspService>()
        clientStarter.ensureClientStarted(ShopwareLspDescriptor(project, root))
    }

    override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?) =
        object : LspClientWidgetItem(lspClient, currentFile, AllIcons.Nodes.Plugin, ShopwareLspConfigurable::class.java) {
            override val statusBarTooltip: String
                get() = (lspClient.descriptor as ShopwareLspDescriptor).failure ?: super.statusBarTooltip
            override val widgetActionText: String
                get() = (lspClient.descriptor as ShopwareLspDescriptor).failure ?: super.widgetActionText
        }

    companion object {
        fun roots(project: Project): List<VirtualFile> = ProjectRootManager.getInstance(project).contentRoots.toList()
        fun rootFor(project: Project, file: VirtualFile): VirtualFile? {
            val detection = project.service<ShopwareProjectRoots>()
            for (root in roots(project).filter { it.isInLocalFileSystem && VfsUtilCore.isAncestor(it, file, false) }
                .sortedByDescending { it.path.length }) {
                when (detection.supports(root.toNioPath())) {
                    true -> return root
                    // Do not attach a nested root to its parent while detection is pending.
                    null -> return null
                    false -> Unit
                }
            }
            return null
        }
    }
}

class ShopwareLspDescriptor(project: Project, val root: VirtualFile) : LspClientDescriptor(project, "Shopware LSP", root) {
    @Volatile var active = false
    @Volatile var failure: String? = null
    override fun isSupportedFile(file: VirtualFile) = file.isInLocalFileSystem &&
        file.extension?.lowercase() in ShopwareProjectDetection.extensions && ShopwareLspIntegration.rootFor(project, file) == root

    override fun createCommandLine(): GeneralCommandLine = GeneralCommandLine(ShopwareLspBinary.resolve().toString()).withWorkDirectory(root.path)
    override fun createInitializationOptions(): Any = ShopwareProtocol.initialization(project.service<ShopwareLspSettings>().state.configuration)
    override val lsp4jServerClass = ShopwareLanguageServer::class.java
    override val clientCapabilities: ClientCapabilities
        get() = super.clientCapabilities.apply {
            window = (window ?: WindowClientCapabilities()).apply { workDoneProgress = true }
            // The server owns filesystem watching and indexing.
            workspace?.didChangeWatchedFiles = null
        }
    override fun getWorkspaceConfiguration(item: ConfigurationItem): Any =
        ShopwareProtocol.initialization(project.service<ShopwareLspSettings>().state.configuration).getAsJsonObject("configuration")

    override val lspServerListener = object : LspServerListener {
        override fun serverStopped(shutdownNormally: Boolean) {
            active = false
            project.serviceIfCreated<ShopwareLspService>()?.forget(this@ShopwareLspDescriptor)
        }

        override fun serverInitialized(params: InitializeResult) {
            active = false
            failure = null
            try {
                active = ShopwareProtocol.active(params)
                if (!active) failure = "Inactive: this project is not supported by Shopware LSP"
                else project.service<ShopwareLspService>().initialized(this@ShopwareLspDescriptor)
            } catch (error: Exception) {
                // Reject capabilities before the IDE starts presenting results from an incompatible server.
                params.capabilities = ServerCapabilities()
                failure = error.message ?: "Incompatible Shopware LSP"
                notifyError(project, failure!!)
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) LspClientManager.getInstance(project).stopClients(ShopwareLspIntegration::class.java)
                }
            }
        }
    }
    override val lspCustomization = object : LspCustomization() {
        override val commandsCustomizer = object : LspCommandsSupport() {
            override fun executeCommand(lspClient: LspClient, contextFile: VirtualFile, command: Command) {
                if (command.command in ShopwareProtocol.commands) ShopwareClientCommands.execute(lspClient, contextFile, command)
                else super.executeCommand(lspClient, contextFile, command)
            }
        }
        override val codeActionsCustomizer = object : LspCodeActionsSupport() {
            override fun createQuickFix(lspClient: LspClient, codeAction: CodeAction) = action(lspClient, codeAction)
            override fun createIntentionAction(lspClient: LspClient, codeAction: CodeAction) = action(lspClient, codeAction)
            private fun action(client: LspClient, codeAction: CodeAction) = object : LspIntentionAction(client, codeAction) {
                override fun applyWorkspaceEdit(workspaceEdit: WorkspaceEdit, uriToDocumentMap: Map<String, com.intellij.openapi.editor.Document>) {
                    ShopwareWorkspaceEdits.apply(client, ShopwareWorkspaceEdits.toJson(workspaceEdit))
                }
            }
        }
    }
}

internal fun notifyError(project: Project, message: String) {
    if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("Shopware LSP")
        .createNotification("Shopware LSP", message, NotificationType.ERROR).notify(project)
}
