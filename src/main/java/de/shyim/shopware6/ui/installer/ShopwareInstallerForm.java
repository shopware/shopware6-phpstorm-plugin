package de.shyim.shopware6.ui.installer;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.ui.UIUtil;
import de.shyim.shopware6.installer.ShopwareApiUtil;
import de.shyim.shopware6.installer.ShopwareVersion;

import javax.swing.*;
import java.util.List;

public class ShopwareInstallerForm {
    public JPanel contentPane;
    public JComboBox<ShopwareVersion> versionField;

    public ShopwareInstallerForm() {
        updateShopwareVersions();
    }

    private void updateShopwareVersions() {
        versionField.setModel(new DefaultComboBoxModel<>());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final List<ShopwareVersion> versions = ShopwareApiUtil.INSTANCE.getAllVersions();
            UIUtil.invokeLaterIfNeeded(() -> versionField.setModel(new DefaultComboBoxModel<>(versions.toArray(ShopwareVersion[]::new))));
        });
    }

}
