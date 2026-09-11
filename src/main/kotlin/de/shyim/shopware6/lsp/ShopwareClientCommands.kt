package de.shyim.shopware6.lsp

import com.google.gson.*
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import org.eclipse.lsp4j.Command
import javax.swing.*

object ShopwareClientCommands {
    fun execute(client: LspClient, file: VirtualFile, command: Command) {
        client.project.service<ShopwareLspService>().background {
            client.project.service<ShopwareLspService>().catalog(client)
            val args = command.arguments.orEmpty().map { ShopwareProtocol.gson.toJsonTree(it) }
            fun arg(index: Int) = args.getOrNull(index)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            when (command.command) {
                "shopware.openReferences" -> {
                    val references = args.firstOrNull()?.asJsonArray ?: JsonArray()
                    val selected = choose(client.project, "References", references.map { it.asString }) ?: return@background
                    val uri = selected.substringBeforeLast('#')
                    val line = selected.substringAfterLast('#', "1").toIntOrNull()?.minus(1) ?: 0
                    ShopwareWorkspaceEdits.navigate(client, uri, line.coerceAtLeast(0))
                }
                "shopware.twig.showBlockDiff" -> {
                    val result = request(client) { it.blockDiff(json("textUri" to arg(0), "blockName" to arg(1))) }
                    ui {
                        val factory = DiffContentFactory.getInstance()
                        DiffManager.getInstance().showDiff(client.project, SimpleDiffRequest("Twig block: ${arg(1)}",
                            factory.create(result.string("originalContent")), factory.create(result.string("currentContent")),
                            result.string("originalVersion"), result.string("currentVersion")))
                    }
                }
                "shopware.twig.extendBlock", "shopware.admin.overrideTwigBlock" -> {
                    val extensions = requireNotNull(client.sendRequestSync(30_000) { (it as ShopwareLanguageServer).extensions() })
                        .map { it.asJsonObject }.filter { command.command == "shopware.twig.extendBlock" || it.get("Type")?.asInt == 0 }
                    val name = choose(client.project, "Target extension", extensions.map { it.string("Name") }) ?: return@background
                    val params = json("textUri" to arg(0), "blockName" to arg(1), "extension" to name)
                    val result = request(client) { if (command.command == "shopware.twig.extendBlock") it.extendBlock(params) else it.overrideBlock(params) }
                    applyResult(client, result)
                }
                "shopware.admin.extendComponent", "shopware.admin.overrideMethod" -> {
                    val component = arg(0)
                    val mode = choose(client.project, "Extend or override $component", listOf("extend", "override")) ?: return@background
                    val directory = chooseDirectory(client.project, file.parent) ?: return@background
                    val name = if (mode == "extend") input(client.project, "New component name", "custom-$component") ?: return@background else component
                    val options = json("mode" to mode, "target" to component, "generateTwig" to false, "generateScss" to false)
                    if (command.command == "shopware.admin.overrideMethod") {
                        options.addProperty("method", arg(1)); options.addProperty("methodGroup", arg(2)); options.addProperty("parameters", arg(3))
                    }
                    val result = request(client) { it.scaffold(json("kind" to "admin-component", "directoryUri" to directory.toNioPath().toUri().toString(), "name" to name, "options" to options)) }
                    applyResult(client, result)
                }
                else -> error("Unsupported Shopware client command: ${command.command}")
            }
        }
    }

    fun applyResult(client: LspClient, result: JsonObject) {
        checkResult(result)
        result.getAsJsonObject("edit")?.let { ShopwareWorkspaceEdits.apply(client, it) }
        val uri = result.string("primaryFileUri").ifEmpty { result.string("uri") }
        if (uri.isNotEmpty()) ShopwareWorkspaceEdits.navigate(client, uri, result.get("line")?.asInt ?: 0)
    }

    fun request(client: LspClient, call: (ShopwareLanguageServer) -> java.util.concurrent.CompletableFuture<JsonObject>): JsonObject =
        requireNotNull(client.sendRequestSync(60_000) { call(it as ShopwareLanguageServer) }).also(::checkResult)

    fun checkResult(result: JsonObject) {
        require(!result.has("code")) { result.string("message").ifEmpty { result.string("code") } }
    }
}

class ShopwareNewFileAction : AnAction("New Shopware File…", "Create a Shopware or Symfony artifact", null) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        event.presentation.isEnabledAndVisible = project != null && file != null &&
            project.service<ShopwareLspSettings>().state.enabled && ShopwareLspIntegration.rootFor(project, file) != null
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        project.service<ShopwareLspService>().background {
            val client = project.service<ShopwareLspService>().client(file)
            val definitions = project.service<ShopwareLspService>().catalog(client).getAsJsonArray("scaffolds").map { it.asJsonObject }
            val label = choose(project, "New Shopware File", definitions.map { it.string("label") }) ?: return@background
            val definition = definitions.first { it.string("label") == label }
            val directory = if (file.isDirectory) file else file.parent
            if (definition.string("workflow") == "entity-schema") {
                ui { ShopwareEntityDesigner.open(client, directory) }
                return@background
            }
            val name = input(project, definition.string("label"), definition.string("namePlaceholder")) ?: return@background
            val options = JsonObject()
            for (value in definition.getAsJsonArray("options") ?: JsonArray()) {
                val field = value.asJsonObject
                val default = field.get("default")?.asString.orEmpty()
                val selected = if (field.has("choices")) choose(project, field.string("label"), field.getAsJsonArray("choices").map { it.asString })
                    else if (field.string("type") == "boolean") choose(project, field.string("label"), listOf("false", "true"))
                    else input(project, field.string("label"), default)
                if (selected == null) return@background
                if (selected.isEmpty() && field.get("required")?.asBoolean != true) continue
                require(selected.isNotBlank()) { "${field.string("label")} is required" }
                when (field.string("type")) {
                    "integer" -> options.addProperty(field.string("name"), selected.toInt())
                    "boolean" -> options.addProperty(field.string("name"), selected.toBooleanStrict())
                    else -> options.addProperty(field.string("name"), selected)
                }
            }
            val params = json("kind" to definition.string("kind"), "directoryUri" to directory.toNioPath().toUri().toString(), "name" to name, "options" to options)
            val result = ShopwareClientCommands.request(client) { if (definition.string("family") == "symfony") it.symfonyScaffold(params) else it.scaffold(params) }
            ShopwareClientCommands.applyResult(client, result)
        }
    }
}

internal fun input(project: Project, title: String, initial: String = ""): String? = ui {
    JOptionPane.showInputDialog(null, title, "Shopware", JOptionPane.QUESTION_MESSAGE, null, null, initial) as? String
}
internal fun choose(project: Project, title: String, choices: List<String>): String? {
    require(choices.isNotEmpty()) { "No choices available for $title" }
    return ui { JOptionPane.showInputDialog(null, title, "Shopware", JOptionPane.QUESTION_MESSAGE, null, choices.toTypedArray(), choices.first()) as? String }
}
internal fun chooseDirectory(project: Project, initial: VirtualFile): VirtualFile? = ui {
    FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Target directory"), project, initial)
}
