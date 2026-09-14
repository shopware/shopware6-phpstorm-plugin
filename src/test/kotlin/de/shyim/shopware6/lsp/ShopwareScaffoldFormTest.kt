package de.shyim.shopware6.lsp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.*

class ShopwareScaffoldFormTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture() = com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl()

    fun testPluginFormCollectsAllFieldsAndOmitsBlankOptionalValues() {
        val form = form("plugin", "Plugin Skeleton", "AcmeExample", listOf(
            field("namespace", "PHP namespace"), field("description", "Description"),
            field("author", "Author", default = "Acme"), field("license", "License", default = "MIT"), field("package", "Composer package"),
        ))
        assertNotNull(form.validate())
        assertEquals("", form.name.text)
        form.name.text = "AcmeExample"
        (form.fields.getValue("namespace") as JTextField).text = "Acme\\Example"
        assertNull(form.validate())
        val params = form.parameters()
        assertEquals("AcmeExample", params.string("name"))
        assertEquals("Acme\\Example", params.getAsJsonObject("options").string("namespace"))
        assertEquals("MIT", params.getAsJsonObject("options").string("license"))
        assertFalse(params.getAsJsonObject("options").has("package"))
        render(form, "plugin")
    }

    fun testNumericValidationPreservesOtherFields() {
        val form = form("scheduled-task", "Scheduled Task", "Cleanup", listOf(
            field("namespace", "PHP namespace"), field("interval", "Interval in seconds", "integer", 300), field("taskName", "Task name"),
        ))
        form.name.text = "Cleanup"
        val interval = form.fields.getValue("interval") as JTextField
        assertEquals("300", interval.text)
        interval.text = "not a number"
        assertEquals(interval, form.validate()!!.component)
        assertEquals("Cleanup", form.name.text)
        interval.text = "600"
        assertEquals(600, form.parameters().getAsJsonObject("options").get("interval").asInt)
        render(form, "scheduled-task")
    }

    fun testComponentModesKeepNamesAndHideIrrelevantOptionsFromRequest() {
        val form = form("admin-component", "Administration Component", "sw-example-card", listOf(
            field("mode", "Mode", "enum", "register").apply { add("choices", json("values" to listOf("register", "extend", "override")).get("values")) },
            field("target", "Existing component"), field("generateTwig", "Generate Twig", "boolean"), field("generateScss", "Generate SCSS", "boolean"),
            field("method", "Method"), field("methodGroup", "Method group"), field("parameters", "Parameters"),
        ))
        form.name.text = "custom-product"
        assertFalse(form.fields.getValue("target").isEnabled)
        val initial = form.parameters().getAsJsonObject("options")
        assertTrue(initial.get("generateTwig").asBoolean)
        assertTrue(initial.get("generateScss").asBoolean)
        assertFalse(initial.has("target"))
        (form.fields.getValue("mode") as JComboBox<*>).selectedItem = "override"
        assertEquals(form.fields["target"], form.validate()!!.component)
        (form.fields.getValue("target") as JTextField).text = "sw-product-detail"
        assertFalse(form.name.isEnabled)
        assertEquals("sw-product-detail", form.parameters().string("name"))
        assertFalse(form.fields.getValue("methodGroup").isEnabled)
        (form.fields.getValue("method") as JTextField).text = "onSave"
        assertTrue(form.fields.getValue("methodGroup").isEnabled)
        form.setBusy(true)
        assertTrue(form.fields.values.none { it.isEnabled })
        form.setBusy(false)
        assertEquals("onSave", form.parameters().getAsJsonObject("options").string("method"))
        (form.fields.getValue("mode") as JComboBox<*>).selectedItem = "extend"
        assertEquals("custom-product", form.name.text)
        render(form, "component")
    }

    fun testRequiredCatalogOptionsAndUnknownGeneratorsWorkWithoutRegistration() {
        val form = form("future-scaffold", "Future Generator", "Example", listOf(field("requiredField", "Required field").apply { addProperty("required", true) }))
        form.name.text = "Example"
        assertEquals(form.fields["requiredField"], form.validate()!!.component)
        (form.fields.getValue("requiredField") as JTextField).text = "value"
        assertEquals("future-scaffold", form.parameters().string("kind"))
        assertNull(form.validate())
    }

    private fun field(name: String, label: String, type: String = "string", default: Any? = null) = json("name" to name, "label" to label, "type" to type).apply {
        if (default != null) add("default", ShopwareProtocol.gson.toJsonTree(default))
    }

    private fun form(kind: String, label: String, placeholder: String, fields: List<JsonObject>): ShopwareScaffoldForm {
        val definition = json("kind" to kind, "family" to "shopware", "label" to label, "namePlaceholder" to placeholder,
            "description" to "Create $label in the selected directory.", "options" to JsonArray().apply { fields.forEach(::add) })
        return ShopwareScaffoldForm(project, definition, myFixture.tempDirFixture.getFile("")!!)
    }

    private fun render(form: ShopwareScaffoldForm, name: String) {
        val panel = form.panel
        panel.setSize(640, panel.preferredSize.height)
        fun layout(component: Container) { component.doLayout(); component.components.filterIsInstance<Container>().forEach(::layout) }
        layout(panel)
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { panel.printAll(graphics) } finally { graphics.dispose() }
        val directory = Path.of("build/reports/scaffold-ui")
        Files.createDirectories(directory)
        ImageIO.write(image, "png", directory.resolve("$name.png").toFile())
    }
}
