# Shopware LSP integration

Shopware 6 Toolbox uses the Shopware LSP `framework` presentation profile on
PhpStorm/IntelliJ IDEA Ultimate 2026.2. PhpStorm owns base language editing;
Shopware LSP owns Shopware/Symfony indexing, diagnostics, navigation, completion,
code actions, symbols, inlays, and code lenses. There is no native analysis fallback.
The IDE-only project wizard, live templates, and file templates remain available.

## Runtime

Opening a supported file starts one stdio process for its supported project root.
Root detection checks Composer metadata, Shopware app manifests, Symfony
FrameworkBundle registration, or `.config/shopware/lsp.yaml`. Unrelated projects
remain inactive. No extra indexing file watcher runs in the adapter.
Project-marker detection runs in the background and caches both supported and
unsupported roots. File-support checks only consult memory. IDE VFS events for
marker files or their parent directories invalidate the affected entries and
trigger a recheck of open files; ordinary source edits leave the cache intact.

Use **Settings → Tools → Shopware LSP** to select a custom executable, disable
LSP for a project, or configure editor-local JSON options. The executable override
is an application setting; project options are stored in the workspace settings.
The configuration catalog and effective configuration are read from the server.
Committed configuration stays in `.config/shopware/lsp.yaml`.

The Language Services widget provides process status and stop/restart controls.
Protocol logs use the standard `#com.intellij.platform.lsp` debug logging category.
Initialization requests protocol version 1 and an exact six-command allow-list:

- `shopware.openReferences`
- `shopware.admin.extendComponent`
- `shopware.admin.overrideMethod`
- `shopware.admin.overrideTwigBlock`
- `shopware.twig.extendBlock`
- `shopware.twig.showBlockDiff`

The integration catalog is checked before custom operations. Other client-side
commands are not advertised; the server filters their command-backed presentations.
Server-side `workspace/executeCommand` capabilities are handled by the platform.

## Scaffolds and entities

**New → Shopware Platform** restores the Plugin, PHP, App, and Administration
menus and adds Symfony generators. Entries come from `shopware/integration/catalog`.
While the catalog loads, **New Shopware File…** offers a searchable artifact picker.
Selecting an artifact opens a single native form with the name, target directory,
and all catalog options together. Boolean options use checkboxes; choices use
dropdowns. Required fields and numeric values are validated in the form.

Component extend/override actions use the same form, prefilled with the selected
component and method. Inapplicable fields are disabled. Creation runs in the
background; errors preserve the entered values, and cancelling discards any
pending response. The adapter applies the returned workspace edit through the IDE;
it never generates PHP, Twig, configuration, or migrations.

The entity designer embeds the matching release's shared UI in JCEF, including
entity, mapping, extension, bulk extension, association, translation, hierarchy,
and inheritance controls. The Kotlin bridge forwards bootstrap/search/load,
preview/apply, and reconciliation requests. It preserves the complete JSON
specification, including unknown fields and raw PHP method properties.

Applying an entity requires the preview's exact opaque revision and specification,
including its allocated migration timestamp. Unsaved document snapshots participate
in preview and apply. Changes invalidate the preview, and a revision cannot be
submitted twice. The shared UI presents validation, rename, drift, and destructive
change decisions. Reconciliation restarts bootstrap.

All custom edits are preflighted before a single undoable IDE write command.
They support UTF-16 text positions, both workspace edit forms, ordered file creates,
and optional document versions. Stale edits, overlapping ranges, unsupported
resource operations, and paths outside the project (including symlink escapes)
are rejected. Opening or saving generated files remains under IDE control.

## Native distributions

`gradle/shopware-lsp.properties` pins the LSP release and archive SHA-256 checksums
from that release's `SHA256SUMS`. Release 0.3.59 publishes platform VSIX archives;
the build extracts only the executable, license, and shared designer script.
There are no runtime binary downloads.

`./gradlew buildPlugin` creates a universal development ZIP with all binaries.
`./gradlew buildNativePlugins` creates these Marketplace variants:

| Target | Bundled release asset |
| --- | --- |
| mac-arm64 | darwin-arm64 |
| mac-x86_64 | darwin-x64 |
| linux-arm64 | linux-arm64 |
| linux-x86_64 | linux-x64 |
| windows-x86_64 | win32-x64 |

Each native ZIP contains only the matching executable. Its descriptor adds
`com.intellij.modules.os.*` and `com.intellij.modules.arch.*` dependencies and
appends the target to the plugin version. This follows
[native-versions-showcase](https://github.com/jreznot/native-versions-showcase).
The OS/architecture routing is an experimental JetBrains feature, so compatibility
is intentionally bounded to the tested 262 platform line. Windows ARM64 is omitted
because the pinned LSP release does not provide that executable.

`-PnativeTarget=mac-arm64` selects an already-packaged native ZIP for `signPlugin`
and `publishPlugin`. Signing happens **after** descriptor and binary selection;
publishing a native variant requires its signed archive. The release workflow
publishes all five variants to the channels selected by the GitHub release and
attaches the signed native ZIPs. Channel selection uses the base release version,
not the platform suffix.

## Development and validation

Use JDK 25. The IDE SDK and PHP/Twig dependencies are pinned in `gradle.properties`.

```sh
./gradlew check buildNativePlugins
python3 scripts/check-native-distributions.py build/native-distributions
python3 scripts/check-lsp-integration.py build/shopware-lsp/mac-arm64/shopware-lsp
```

Select your platform's executable for the last command. The test uses a temporary
fixture, including supported/unsupported startup, shutdown, protocol negotiation,
catalog/configuration requests, scaffold and entity preview/apply responses,
Twig snippet completion/navigation, unsaved diagnostics, and document closure.
Adapter unit/platform tests exercise registration, project markers, binary
verification, UTF-16 edits, file creation, version rejection, and atomic preflight.

Add `--project-root /path/to/sw-trunk` to the protocol command for an opt-in,
read-only real-project check of indexing, framework symbols, and suppression of
generic PHP diagnostics. It uses a temporary cache outside the project.

When updating LSP, update the version and every archive checksum together and
refresh `src/main/resources/lsp/entity-designer.html` from the same release's
`vscode-extension/src/entityDesigner.ts`. The compiled designer script and license
are extracted from the verified release archive. Re-run the protocol tests and
inspect the designer before publishing.
