package de.shyim.shopware6.lsp

import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.ui.SimpleListCellRenderer
import icons.ShopwareToolBoxIcons
import javax.swing.JList

/** Categories are presentation only; available artifacts and fields come from the server catalog. */
internal fun scaffoldCategory(definition: JsonObject): String = when {
    definition.string("family") == "symfony" -> "Symfony"
    definition.string("kind") in setOf("plugin", "system-config") -> "Plugin"
    definition.string("kind").startsWith("app") -> "App"
    definition.string("kind").startsWith("admin-") || definition.string("kind").startsWith("cms-") -> "Administration"
    else -> "PHP"
}

private fun scaffoldContext(event: AnActionEvent): Pair<Project, VirtualFile>? {
    val project = event.project ?: return null
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
    if (!project.service<ShopwareLspSettings>().state.enabled || ShopwareLspIntegration.rootFor(project, file) == null) return null
    return project to if (file.isDirectory) file else file.parent
}

class ShopwareScaffoldActionGroup : ActionGroup("Shopware Platform", true) {
    init { templatePresentation.icon = ShopwareToolBoxIcons.SHOPWARE }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = scaffoldContext(event) != null }
    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        val (project, directory) = event?.let(::scaffoldContext) ?: return emptyArray()
        val catalog = project.service<ShopwareLspService>().cachedCatalog(directory)
            ?: return arrayOf(ShopwareNewFileAction())
        val definitions = catalog.getAsJsonArray("scaffolds").map { it.asJsonObject }
        val categories = listOf("Plugin", "PHP", "App", "Administration", "Symfony")
        return definitions.groupBy(::scaffoldCategory).toSortedMap(compareBy { categories.indexOf(it) }).map { (category, items) ->
            DefaultActionGroup(category, true).apply {
                items.forEach { add(ShopwareNewFileAction(it)) }
            }
        }.toTypedArray()
    }
}

class ShopwareNewFileAction(private val definition: JsonObject? = null) : AnAction(
    definition?.string("label")?.plus("…") ?: "New Shopware File…",
    definition?.string("description") ?: "Create a Shopware or Symfony artifact",
    ShopwareToolBoxIcons.SHOPWARE,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = scaffoldContext(event) != null }
    override fun actionPerformed(event: AnActionEvent) {
        val (project, directory) = scaffoldContext(event) ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Shopware generators", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val service = project.service<ShopwareLspService>()
                    var client = service.clientOrNull(directory)
                    if (client == null) {
                        val root = ShopwareLspIntegration.rootFor(project, directory) ?: error("No supported project found")
                        ui { LspClientManager.getInstance(project).ensureClientStarted(ShopwareLspIntegration::class.java, ShopwareLspDescriptor(project, root)) }
                        val deadline = System.nanoTime() + 30_000_000_000L
                        while (client == null && System.nanoTime() < deadline) {
                            indicator.checkCanceled()
                            Thread.sleep(50)
                            client = service.clientOrNull(directory)
                        }
                    }
                    val readyClient = requireNotNull(client) { "Shopware LSP did not start. Check the Language Services widget." }
                    val definitions = service.catalog(readyClient).getAsJsonArray("scaffolds").map { it.asJsonObject }
                    indicator.checkCanceled()
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || indicator.isCanceled) return@invokeLater
                        if (definition != null) {
                            val current = definitions.firstOrNull { it.string("family") == definition.string("family") && it.string("kind") == definition.string("kind") }
                            if (current != null) openScaffold(readyClient, directory, current)
                            else notifyError(project, "This generator is no longer available")
                        } else {
                            JBPopupFactory.getInstance().createPopupChooserBuilder(definitions)
                                .setTitle("New Shopware File")
                                .setRenderer(object : SimpleListCellRenderer<JsonObject>() {
                                    override fun customize(list: JList<out JsonObject>, value: JsonObject?, index: Int, selected: Boolean, hasFocus: Boolean) {
                                        text = value?.let { "${scaffoldCategory(it)} · ${it.string("label")}" }.orEmpty()
                                    }
                                })
                                .setNamerForFiltering { it.string("label") + " " + scaffoldCategory(it) }
                                .setItemChosenCallback { openScaffold(readyClient, directory, it) }
                                .createPopup().showCenteredInCurrentWindow(project)
                        }
                    }
                } catch (error: com.intellij.openapi.progress.ProcessCanceledException) { throw error }
                catch (error: Exception) { if (!indicator.isCanceled) notifyError(project, error.cause?.message ?: error.message ?: "Could not load generators") }
            }
        })
    }
}

private fun openScaffold(client: LspClient, directory: VirtualFile, definition: JsonObject) {
    if (definition.string("workflow") == "entity-schema") ShopwareEntityDesigner.open(client, directory)
    else ShopwareScaffoldDialog(client, definition, directory).show()
}
