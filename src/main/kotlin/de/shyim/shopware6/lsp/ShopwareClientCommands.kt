package de.shyim.shopware6.lsp

import com.google.gson.*
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.components.service
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
                    val definition = client.project.service<ShopwareLspService>().catalog(client)
                        .getAsJsonArray("scaffolds").map { it.asJsonObject }
                        .first { it.string("family") == "shopware" && it.string("kind") == "admin-component" }
                    val options = json("mode" to "override", "target" to arg(0), "generateTwig" to false, "generateScss" to false)
                    if (command.command == "shopware.admin.overrideMethod") {
                        options.addProperty("method", arg(1)); options.addProperty("methodGroup", arg(2)); options.addProperty("parameters", arg(3))
                    }
                    ui { ShopwareScaffoldDialog(client, definition, file.parent, "custom-${arg(0)}", options).show() }
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

internal fun choose(project: Project, title: String, choices: List<String>): String? {
    require(choices.isNotEmpty()) { "No choices available for $title" }
    return ui { JOptionPane.showInputDialog(null, title, "Shopware", JOptionPane.QUESTION_MESSAGE, null, choices.toTypedArray(), choices.first()) as? String }
}
