package de.shyim.shopware6.lsp

import com.google.gson.*
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

interface ShopwareLanguageServer : LanguageServer {
    @JsonRequest("shopware/integration/catalog") fun catalog(): CompletableFuture<JsonObject>
    @JsonRequest("shopware/extension/all") fun extensions(): CompletableFuture<JsonArray>
    @JsonRequest("shopware/twig/extendBlock") fun extendBlock(params: JsonObject): CompletableFuture<JsonObject>
    @JsonRequest("shopware/twig/getBlockDiff") fun blockDiff(params: JsonObject): CompletableFuture<JsonObject>
    @JsonRequest("shopware/admin/twig/override") fun overrideBlock(params: JsonObject): CompletableFuture<JsonObject>
    @JsonRequest("shopware/scaffold/create") fun scaffold(params: JsonObject): CompletableFuture<JsonObject>
    @JsonRequest("shopware/symfony/scaffold/create") fun symfonyScaffold(params: JsonObject): CompletableFuture<JsonObject>
    @JsonRequest("shopware/entity-schema/bootstrap") fun entityBootstrap(params: JsonObject): CompletableFuture<JsonObject>
    @JsonRequest("shopware/entity-schema/search") fun entitySearch(params: JsonObject): CompletableFuture<JsonArray>
    @JsonRequest("shopware/entity-schema/load") fun entityLoad(params: JsonObject): CompletableFuture<JsonObject>
    @JsonRequest("shopware/entity-schema/preview") fun entityPreview(params: JsonObject): CompletableFuture<JsonObject>
    @JsonRequest("shopware/entity-schema/apply") fun entityApply(params: JsonObject): CompletableFuture<JsonObject>
    @JsonRequest("shopware/entity-schema/reconcile") fun entityReconcile(params: JsonObject): CompletableFuture<JsonObject>
    @JsonRequest("shopware/configuration/catalog") fun configurationCatalog(): CompletableFuture<JsonObject>
    @JsonRequest("shopware/configuration/effective") fun configurationEffective(params: JsonObject): CompletableFuture<JsonObject>
    @JsonRequest("shopware/configuration/reload") fun configurationReload(): CompletableFuture<JsonObject>
}

object ShopwareProtocol {
    const val VERSION = 1
    val commands = setOf("shopware.openReferences", "shopware.admin.extendComponent", "shopware.admin.overrideMethod",
        "shopware.admin.overrideTwigBlock", "shopware.twig.extendBlock", "shopware.twig.showBlockDiff")
    val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun initialization(configuration: String): JsonObject = json(
        "configuration" to JsonParser.parseString(configuration).asJsonObject,
        "allowUnsupportedProject" to false,
        "shopwareClient" to json("protocolVersion" to VERSION, "presentationProfile" to "framework", "supportedCommands" to commands),
    )

    fun active(result: InitializeResult): Boolean {
        val experimental = gson.toJsonTree(result.capabilities.experimental).asJsonObject
        val state = requireNotNull(experimental.getAsJsonObject("shopwareLSP")) { "Server does not support the Shopware integration protocol" }
        require(state.get("protocolVersion")?.asInt == VERSION) { "Incompatible Shopware LSP protocol; expected $VERSION" }
        require(state.string("presentationProfile") == "framework") { "Shopware LSP did not negotiate the framework profile" }
        return state.get("active")?.asBoolean == true
    }

    fun validateCatalog(catalog: JsonObject) {
        require(catalog.get("protocolVersion")?.asInt == VERSION) { "Incompatible Shopware integration catalog" }
        val available = catalog.getAsJsonArray("clientCommands").map { it.asJsonObject.string("id") }.toSet()
        require(available.containsAll(commands)) { "Shopware LSP is missing required client commands: ${commands - available}" }
    }
}

internal fun json(vararg entries: Pair<String, Any?>): JsonObject = JsonObject().apply {
    for ((name, value) in entries) add(name, ShopwareProtocol.gson.toJsonTree(value))
}
