package de.shyim.shopware6.lsp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.*
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.event.DocumentEvent

/** One native form, with no generator implementation or template logic in the IDE. */
internal class ShopwareScaffoldForm(
    private val project: Project,
    val definition: JsonObject,
    initialDirectory: VirtualFile,
    initialName: String = "",
    initialOptions: JsonObject = JsonObject(),
) {
    val name = JBTextField(initialName, 34).apply { emptyText.text = definition.string("namePlaceholder") }
    val fields = linkedMapOf<String, JComponent>()
    private val directoryText = JBTextField(initialDirectory.presentableUrl, 34).apply {
        isEditable = false
        toolTipText = initialDirectory.presentableUrl
        caretPosition = document.length
    }
    var directory: VirtualFile = initialDirectory
        private set
    private val browse = JButton("Browse…").apply {
        addActionListener {
            FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Create in"), project, directory)?.let {
                directory = it
                directoryText.text = it.presentableUrl
                directoryText.toolTipText = it.presentableUrl
            }
        }
    }
    private val options = (definition.getAsJsonArray("options") ?: JsonArray()).map { it.asJsonObject }
    private var busy = false
    private var wasOverride = false
    private var editableName = initialName
    val panel: JPanel

    init {
        val builder = object : FormBuilder() {
            override fun getFill(component: JComponent): Int =
                if (component is JTextField) java.awt.GridBagConstraints.HORIZONTAL else super.getFill(component)
        }.setVerticalGap(10)
            .addComponent(JBTextArea(definition.string("description")).apply {
                isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true
                font = com.intellij.util.ui.UIUtil.getLabelFont()
                columns = 48; rows = 2
            })
            .addLabeledComponent("Create in:", JPanel(BorderLayout(8, 0)).apply { add(directoryText); add(browse, BorderLayout.EAST) })
            .addLabeledComponent("Name:", name)
            .addSeparator()
        for (field in options) {
            val key = field.string("name")
            val initial = initialOptions.get(key) ?: field.get("default")
            val control: JComponent = when {
                field.string("type") == "boolean" -> JBCheckBox(field.string("label"), initial?.asBoolean
                    ?: (definition.string("kind") == "admin-component" && key in setOf("generateTwig", "generateScss")))
                field.has("choices") -> ComboBox(field.getAsJsonArray("choices").map { it.asString }.toTypedArray()).apply {
                    if (initial != null) selectedItem = initial.asString
                    renderer = object : com.intellij.ui.SimpleListCellRenderer<String>() {
                        override fun customize(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                            text = value?.replaceFirstChar { it.uppercase() }.orEmpty()
                        }
                    }
                }
                else -> JBTextField(initial?.asString.orEmpty(), 34)
            }
            fields[key] = control
            if (control is JBCheckBox) builder.addLabeledComponent("", control)
            else builder.addLabeledComponent(field.string("label") + if (field.get("required")?.asBoolean == true) " *:" else ":", control)
        }
        panel = builder.addComponent(JBLabel("Blank optional fields use the project's defaults.").apply {
            foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
        }).panel.apply { border = JBUI.Borders.empty(8); minimumSize = JBUI.size(580, 0) }
        (fields["mode"] as? JComboBox<*>)?.addActionListener { updateEnabled() }
        (fields["target"] as? JTextField)?.document?.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) { updateEnabled() }
        })
        (fields["method"] as? JTextField)?.document?.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) { updateEnabled() }
        })
        updateEnabled()
    }

    private fun text(key: String): String = when (val component = fields[key]) {
        is JTextField -> component.text.trim()
        is JComboBox<*> -> component.selectedItem?.toString().orEmpty()
        else -> ""
    }

    private fun applicable(key: String): Boolean {
        if (definition.string("kind") != "admin-component") return true
        return when (key) {
            "target", "method" -> text("mode") != "register"
            "methodGroup", "parameters" -> text("mode") != "register" && text("method").isNotBlank()
            else -> true
        }
    }

    fun setBusy(value: Boolean) { busy = value; updateEnabled() }

    private fun updateEnabled() {
        val override = definition.string("kind") == "admin-component" && text("mode") == "override"
        if (override && !wasOverride) editableName = name.text
        if (override) name.text = text("target")
        else if (wasOverride) name.text = editableName
        wasOverride = override
        name.isEnabled = !busy && !override
        browse.isEnabled = !busy
        fields.forEach { (key, component) -> component.isEnabled = !busy && applicable(key) }
    }

    fun validate(): ValidationInfo? {
        if (!directory.isValid || !directory.isDirectory) return ValidationInfo("Choose an existing target directory", directoryText)
        if (name.text.isBlank()) return if (wasOverride) ValidationInfo("Enter an existing component", fields["target"])
            else ValidationInfo("Enter a name", name)
        for (field in options) {
            val key = field.string("name")
            if (!applicable(key) || field.string("type") == "boolean") continue
            val value = text(key)
            if ((field.get("required")?.asBoolean == true || key == "target" && definition.string("kind") == "admin-component") && value.isBlank()) {
                return ValidationInfo("Enter ${field.string("label").lowercase()}", fields[key])
            }
            if (value.isNotEmpty() && field.string("type") == "integer" && value.toIntOrNull() == null) {
                return ValidationInfo("Enter a whole number", fields[key])
            }
        }
        return null
    }

    fun parameters(): JsonObject {
        check(validate() == null)
        val values = JsonObject()
        for (field in options) {
            val key = field.string("name")
            if (!applicable(key)) continue
            if (field.string("type") == "boolean") values.addProperty(key, (fields.getValue(key) as JBCheckBox).isSelected)
            else {
                val value = text(key)
                if (value.isEmpty()) continue
                if (field.string("type") == "integer") values.addProperty(key, value.toInt())
                else values.addProperty(key, value)
            }
        }
        return json("kind" to definition.string("kind"), "directoryUri" to directory.toNioPath().toUri().toString(), "name" to name.text.trim(), "options" to values)
    }
}

internal class ShopwareScaffoldDialog(
    private val client: LspClient,
    private val definition: JsonObject,
    directory: VirtualFile,
    name: String = "",
    options: JsonObject = JsonObject(),
) : DialogWrapper(client.project, true) {
    private val form = ShopwareScaffoldForm(client.project, definition, directory, name, options)
    @Volatile private var closed = false
    private var busy = false

    init {
        title = "Create ${definition.string("label")}"
        setOKButtonText("Create")
        init()
    }
    override fun createCenterPanel(): JComponent = form.panel
    override fun getPreferredFocusedComponent(): JComponent = if (form.name.isEnabled) form.name else form.fields["target"] ?: form.name
    override fun doValidate(): ValidationInfo? = form.validate()

    override fun doOKAction() {
        if (busy) return
        val problem = form.validate()
        if (problem != null) { setErrorText(problem.message); problem.component?.requestFocusInWindow(); return }
        val params = form.parameters()
        val modality = ModalityState.stateForComponent(form.panel)
        busy = true
        form.setBusy(true)
        setOKActionEnabled(false)
        setOKButtonText("Creating…")
        setErrorText(null)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { ShopwareClientCommands.request(client) {
                if (definition.string("family") == "symfony") it.symfonyScaffold(params) else it.scaffold(params)
            } }
            ApplicationManager.getApplication().invokeLater({
                if (!closed && !client.project.isDisposed) {
                    busy = false
                    form.setBusy(false)
                    setOKActionEnabled(true)
                    setOKButtonText("Create")
                    try {
                        ShopwareClientCommands.applyResult(client, result.getOrThrow())
                        close(OK_EXIT_CODE)
                    } catch (error: Exception) {
                        setErrorText(error.cause?.message ?: error.message ?: "Could not create files")
                    }
                }
            }, modality)
        }
    }

    override fun dispose() { closed = true; super.dispose() }
}
