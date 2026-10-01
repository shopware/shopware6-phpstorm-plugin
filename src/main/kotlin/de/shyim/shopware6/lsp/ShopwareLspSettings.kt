package de.shyim.shopware6.lsp

import com.intellij.openapi.components.*

@Service(Service.Level.APP)
@State(name = "ShopwareLspExecutable", storages = [Storage(value = "shopware-lsp.xml", roamingType = RoamingType.DISABLED)])
class ShopwareLspExecutableSettings : PersistentStateComponent<ShopwareLspExecutableSettings.Data> {
    data class Data(var executable: String = "")
    private var data = Data()
    override fun getState() = data
    override fun loadState(state: Data) { data = state }
}

@Service(Service.Level.PROJECT)
@State(name = "ShopwareLsp", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ShopwareLspSettings : PersistentStateComponent<ShopwareLspSettings.Data> {
    data class Data(var enabled: Boolean = true, var configuration: String = "{}")
    private var data = Data()
    override fun getState() = data
    override fun loadState(state: Data) { data = state }
}
