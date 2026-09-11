package de.shyim.shopware6.lsp

import com.google.gson.JsonParser
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.ui.components.*
import com.intellij.util.ui.FormBuilder
import org.eclipse.lsp4j.DidChangeConfigurationParams
import javax.swing.*

class ShopwareLspConfigurable(private val project: Project) : Configurable {
    private var panel: JPanel? = null
    private val enabled = JBCheckBox("Enable Shopware LSP")
    private val executable = JBTextField()
    private val configuration = JBTextArea(10, 70)
    override fun getDisplayName() = "Shopware LSP"
    override fun createComponent(): JComponent {
        val inspect = JButton("Show server configuration…").apply {
            addActionListener {
                project.service<ShopwareLspService>().background {
                    val client = LspClientManager.getInstance(project).getClients(ShopwareLspIntegration::class.java).firstOrNull()
                        ?: error("Open a supported project file to start Shopware LSP")
                    val catalog = ShopwareClientCommands.request(client) { it.configurationCatalog() }
                    val effective = ShopwareClientCommands.request(client) { it.configurationEffective(json()) }
                    ui { JOptionPane.showMessageDialog(panel, JBScrollPane(JBTextArea(ShopwareProtocol.gson.toJson(json("catalog" to catalog, "effective" to effective)), 28, 100).apply { isEditable = false }), "Shopware LSP Configuration", JOptionPane.INFORMATION_MESSAGE) }
                }
            }
        }
        val reload = JButton("Reload committed configuration").apply {
            addActionListener { project.service<ShopwareLspService>().background {
                LspClientManager.getInstance(project).getClients(ShopwareLspIntegration::class.java).forEach { client ->
                    ShopwareClientCommands.request(client) { it.configurationReload() }
                }
            } }
        }
        val supported = ShopwareLspIntegration.roots(project).any { ShopwareProjectDetection.supports(it.toNioPath()) }
        panel = FormBuilder.createFormBuilder().addComponent(JBLabel(if (supported) "Supported Shopware/Symfony project" else "Inactive: no supported project marker found. Add .config/shopware/lsp.yaml to opt in." )).addComponent(enabled)
            .addLabeledComponent("Custom executable (blank uses bundled LSP):", executable)
            .addLabeledComponent("Editor configuration (JSON):", JBScrollPane(configuration))
            .addComponent(inspect).addComponent(reload)
            .addComponent(JBLabel("Committed project configuration: .config/shopware/lsp.yaml"))
            .addComponentFillVertically(JPanel(), 0).panel
        reset()
        return panel!!
    }
    override fun isModified() = enabled.isSelected != project.service<ShopwareLspSettings>().state.enabled ||
        configuration.text != project.service<ShopwareLspSettings>().state.configuration || executable.text != service<ShopwareLspExecutableSettings>().state.executable
    override fun reset() {
        enabled.isSelected = project.service<ShopwareLspSettings>().state.enabled
        configuration.text = project.service<ShopwareLspSettings>().state.configuration
        executable.text = service<ShopwareLspExecutableSettings>().state.executable
    }
    override fun apply() {
        val parsed = try { JsonParser.parseString(configuration.text).asJsonObject } catch (_: Exception) { throw ConfigurationException("Editor configuration must be a JSON object") }
        val settings = project.service<ShopwareLspSettings>().state
        val executableChanged = executable.text != service<ShopwareLspExecutableSettings>().state.executable
        val restart = settings.enabled != enabled.isSelected
        settings.enabled = enabled.isSelected
        settings.configuration = configuration.text
        service<ShopwareLspExecutableSettings>().state.executable = executable.text
        val manager = LspClientManager.getInstance(project)
        if (executableChanged) ProjectManager.getInstance().openProjects.forEach { LspClientManager.getInstance(it).stopAndRestartClientsIfNeeded(ShopwareLspIntegration::class.java) }
        else if (restart) manager.stopAndRestartClientsIfNeeded(ShopwareLspIntegration::class.java)
        else manager.getClients(ShopwareLspIntegration::class.java).forEach { it.sendNotification { server -> server.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(parsed)) } }
    }
    override fun disposeUIResources() { panel = null }
}
