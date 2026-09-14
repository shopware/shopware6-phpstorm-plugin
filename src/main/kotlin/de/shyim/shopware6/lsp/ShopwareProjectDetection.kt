package de.shyim.shopware6.lsp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** Only the root markers shared with internal/projectdetect; never scans or indexes sources. */
object ShopwareProjectDetection {
    val extensions = setOf("php", "twig", "html", "js", "ts", "vue", "scss", "json", "xml", "xlf", "xliff", "yaml", "yml")
    private val shopware = setOf("shopware/core", "shopware/platform", "shopware/production", "shopware/development")

    fun supports(root: Path): Boolean {
        if (Files.isRegularFile(root.resolve(".config/shopware/lsp.yaml"))) return true
        return try {
            val composer = json(root.resolve("composer.json"))
            if (composer != null) {
                if (composer.string("name").trim().lowercase() in shopware ||
                    composer.string("type").trim().equals("shopware-platform-plugin", true) ||
                    composer.getAsJsonObject("extra")?.string("shopware-plugin-class")?.isNotBlank() == true) return true
                val dependencies = listOf("require", "require-dev").flatMap {
                    composer.getAsJsonObject(it)?.keySet().orEmpty()
                }.map { it.trim().lowercase() }
                if (dependencies.any { it in shopware || it == "symfony/framework-bundle" }) return true
            }
            val manifest = root.resolve("manifest.xml")
            if (Files.isRegularFile(manifest)) {
                val factory = XMLInputFactory.newFactory().apply {
                    setProperty(XMLInputFactory.SUPPORT_DTD, false)
                    setProperty("javax.xml.stream.isSupportingExternalEntities", false)
                }
                Files.newInputStream(manifest).use { input ->
                    val xml = factory.createXMLStreamReader(input)
                    try {
                        while (xml.hasNext()) {
                            if (xml.next() == XMLStreamConstants.START_ELEMENT) {
                                if (xml.localName.equals("manifest", true) && (0 until xml.attributeCount).any {
                                    val value = xml.getAttributeValue(it).lowercase()
                                    "shopware" in value && "manifest" in value
                                }) return true
                                break
                            }
                        }
                    } finally { xml.close() }
                }
            }
            val bundles = root.resolve("config/bundles.php")
            if (Files.isRegularFile(bundles)) {
                val text = read(bundles)
                if ("Symfony\\Bundle\\FrameworkBundle\\FrameworkBundle" in text || "FrameworkBundle::class" in text) return true
            }
            val lock = json(root.resolve("composer.lock"))
            listOf("packages", "packages-dev").any { key ->
                lock?.getAsJsonArray(key)?.any {
                    val name = it.asJsonObject.string("name").trim().lowercase()
                    name in shopware || name == "symfony/framework-bundle"
                } == true
            }
        } catch (_: Exception) { false }
    }

    private fun json(file: Path): JsonObject? = if (Files.isRegularFile(file)) JsonParser.parseString(read(file)).asJsonObject else null
    private fun read(file: Path): String {
        require(Files.size(file) <= 16 * 1024 * 1024) { "Project marker is too large" }
        return Files.readString(file)
    }
}

internal fun JsonObject.string(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
