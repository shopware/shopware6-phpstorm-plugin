# Shopware 6 Toolbox

![Build](https://github.com/shyim/shopware6-phpstorm-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/17632.svg)](https://plugins.jetbrains.com/plugin/17632)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/17632.svg)](https://plugins.jetbrains.com/plugin/17632)

<!-- Plugin description -->
Shopware 6 Toolbox integrates [Shopware LSP](https://github.com/shopware/shopware-lsp) into PhpStorm 2026.2.
It adds Shopware and Symfony completion, navigation, diagnostics, code actions, inlays, and code lenses while
PhpStorm handles base PHP, Twig, JavaScript, and other language editing.

Create Shopware/Symfony files through a server-provided scaffold catalog, or use the entity designer to
preview and apply DAL definitions, associations, extensions, migrations, and snapshots. The Shopware
project wizard, live templates, and file templates are also included.
<!-- Plugin description end -->

## Installation

- Using IDE built-in plugin system (**recommended**):

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Shopware 6 Toolbox"</kbd> >
  <kbd>Install Plugin</kbd>
  
- Manually:

  Download the [latest release](https://github.com/shyim/shopware6-phpstorm-plugin/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

### Pre-release updates

To receive pre-release builds, open <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> >
<kbd>Manage Plugin Repositories...</kbd> and add:

```text
https://plugins.jetbrains.com/plugins/eap/17632
```

The EAP channel includes pre-releases and stable releases. Remove this repository to return to stable updates;
if you want to downgrade immediately, reinstall the plugin from the Marketplace.

## Publishing releases

- Set `pluginVersion` in `gradle.properties` to the next version, such as `0.1.2-eap.1` for a pre-release or `0.1.2` for a stable release.
- After a successful build on `main`, the workflow creates a draft release. Versions with a pre-release suffix are automatically marked as pre-releases.
- Review and publish the draft. GitHub releases marked **Set as a pre-release** publish only to `eap`; stable releases publish to both `default` and `eap`.

The release tag supplies the built plugin version (an optional leading `v` is removed). Use a unique pre-release version
for each build, then publish a separate stable release with a stable version. Changing an existing release's pre-release
checkbox does not publish another build. Only stable releases create a changelog update pull request.

For local publishing, `./gradlew publishPlugin -PnativeTarget=mac-arm64` signs and publishes that platform variant and infers channels from `pluginVersion`.
Override them with `-PpluginChannels=eap` or `-PpluginChannels=default,eap` if needed.
See [JetBrains' custom release channel documentation](https://plugins.jetbrains.com/docs/marketplace/custom-release-channels.html).

## Shopware LSP

The plugin bundles Shopware LSP; it does not download executables when opening a project.
Marketplace releases contain the binary for your OS and architecture. When installing from disk,
choose the matching `mac-arm64`, `mac-x86_64`, `linux-arm64`, `linux-x86_64`, or `windows-x86_64` ZIP.

Use **Settings → Tools → Shopware LSP** for a custom executable or editor configuration, and the
**Language Services** widget for process status and restart. **New → New Shopware File…** opens the
scaffold catalog and entity designer.

See [integration, packaging, and development details](doc/lsp-integration.md).
