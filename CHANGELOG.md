<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# shopware6-phpstorm-plugin Changelog

## Unreleased

- Replace native Shopware analysis and generators with bundled Shopware LSP, including catalog-driven scaffolds and the shared entity designer. Requires PhpStorm or IntelliJ IDEA Ultimate 2026.2.
- Package verified LSP binaries for macOS and Linux on ARM64/x86_64 and Windows on x86_64, with matching Marketplace distributions and no runtime downloads.
- Restore categorized Shopware generator menus and native forms with all options together, inline validation, and retryable errors instead of sequential input prompts.

## 0.1.1 - 2026-08-21

- Restored the inheritance gutter markers on Twig blocks that were lost with the reworked Twig template handling in `0.1.0`: a block now shows an "overrides" icon when it overrides a block of the `sw_extends` chain and an "overridden" icon when extending templates override it, both navigating to the related blocks. The markers are provided by the plugin itself and no longer require the Symfony plugin. Fixes #307

## 0.1.0 - 2026-07-30

- Replaced the Symfony plugin integration for `sw_extends` / `sw_include` with own navigation and autocompletion: navigating a template reference now offers all templates of that view path (the referenced bundle first, then all plugin overrides), and completion suggests templates of every bundle including plugins in `custom/plugins`
- Added navigation from a Twig block name to the upstream block it overrides (nearest parent first, following the `sw_extends` chain)
- Templates are indexed by their view path and `sw_extends` target, so template resolution, chain walking and completion no longer scan files
- Twig block versioning comments now also work for templates of third-party extensions, both installed via Composer and in `custom/plugins` (block changed / removed / comment missing inspections). The versioning comment records the version of the extension the block belongs to (from the Composer package or the extension's composer.json). Showing a diff of the upstream changes is only supported for Shopware core templates.
- The "versioning comment missing" inspection only reports files that extend another template via `sw_extends`
- The upstream of a block is now resolved through the `sw_extends` chain of the template, so versioning also works when extending a template at a different relative path and sibling overrides of other plugins cannot be mistaken for the upstream
- When the chain cannot be resolved, blocks carrying a versioning comment are treated as overrides and no longer count as upstream, so they cannot mask upstream changes or removals
- Added new inspection that warns when a Twig block with a versioning comment has been removed from the upstream Shopware template
- Fixed "block does not have a versioning comment" inspection being reported on the Shopware core templates themselves

## 0.0.55 - 2026-07-10

- Marked as compatible with next major version of Jetbrains IDEs

## 0.0.54 - 2026-03-30

- Changelog update - `0.0.53` by @github-actions[bot] in https://github.com/shopwareLabs/shopware6-phpstorm-plugin/pull/259

## 0.0.53 - 2026-02-11

- Changed YAML Symfony Config to PHP File

## 0.0.52 - 2025-12-12

- Fixed creating new Shopware projects
- Fixed missing Admin snippets in newer Shopware versions

## 0.0.51 - 2025-12-11

- Fixed release for latest 2025.3 update for Twig versioning commment and snippet indexing

## 0.0.50 - 2025-11-14

- Updated Shopware Icon to be better visible
- Added Shopware Icon to all Shopware Intentions

## 0.0.49 - 2025-11-07

- removed changelog creation, not required anymore in shopware itself
- fixed a bug where the block indexer fails when the block name is empty

## 0.0.48 - 2025-09-02

- Fixed 2025.2 build

## 0.0.47 - 2025-04-16

- 2025.1 support
- [fix: add event listener attribute](https://github.com/shopwareLabs/shopware6-phpstorm-plugin/commit/4cfe9d84e11e42b5a126907eabcb733e798472c0)
- [feat: enable autoconfigure autowire on new plugins](https://github.com/shopwareLabs/shopware6-phpstorm-plugin/commit/04dff895eb065e3024cda86c84d38f5812890bb9)

## 0.0.46 - 2024-12-18

- Changelog update - `v0.0.45` by @github-actions in https://github.com/shopwareLabs/shopware6-phpstorm-plugin/pull/219
- chore(deps): bump org.jetbrains.qodana from 2024.2.2 to 2024.2.3 by @dependabot in https://github.com/shopwareLabs/shopware6-phpstorm-plugin/pull/220
- chore(deps): bump codecov/codecov-action from 4 to 5 by @dependabot in https://github.com/shopwareLabs/shopware6-phpstorm-plugin/pull/227
- Add link to Block versioning blog post by @amenk in https://github.com/shopwareLabs/shopware6-phpstorm-plugin/pull/228
- chore: upgrade dependencies & compatibility by @jankal in https://github.com/shopwareLabs/shopware6-phpstorm-plugin/pull/230
- fix(indexing): do not access index before it is ready / while in dumb mode by @jankal in https://github.com/shopwareLabs/shopware6-phpstorm-plugin/pull/232
- chore(deps): bump org.gradle.toolchains.foojay-resolver-convention from 0.8.0 to 0.9.0 by @dependabot in https://github.com/shopwareLabs/shopware6-phpstorm-plugin/pull/233
- #222 configure project by @amenk in https://github.com/shopwareLabs/shopware6-phpstorm-plugin/pull/234
- feat: made symfony plugin optional by @shyim in https://github.com/shopwareLabs/shopware6-phpstorm-plugin/pull/237
- @jankal made their first contribution in https://github.com/shopwareLabs/shopware6-phpstorm-plugin/pull/230

## 0.0.45 - 2024-09-20

- Fixed that previous deprecated blocks mark the next block also as deprecated

## 0.0.44 - 2024-09-16

- Added Twig Block Versioning
- Removed old PHPUnit live templates

## 0.0.43 - 2024-06-08

- Improved Compatibility with PhpStorm 2024.2 (EAP)

## 0.0.42 - 2024-03-03

- Improved Compatibility with PhpStorm 2024.1
- Fixed the Shopware Installer Project Wizard with newer Shopware versions

## 0.0.41 - 2023-11-25

- Added inspection for template slot attribute to migrate to new Vue syntax
- Added inspection to show which Twig blocks are deprecated in the template.

## 0.0.40 - 2023-09-29

- Fix support for EAP 2023.3

## 0.0.39 - 2023-08-18

- Fix deprecations in IDEA 2023.3

## 0.0.37 - 2023-07-11

- Add migration generator as Action

## 0.0.36 - 2023-06-04

- Renamed Extension Sync to Configure Shopware Project
  - Add exclude directories for Shopware
  - Cleanup namespaces when platform is open
- Fixed some deprecations

## 0.0.35 - 2023-05-19

- Support newest EAP
- Fixed errors with empty hook factory

## 0.0.34 - 2023-01-25

- Allow latest EAP

## 0.0.33

- Added new required extension scss
- Fixed scss formatting of cms modules
- Support latest EAP releases

## 0.0.32

- Fixed snippet source of insert snippet in admin
- Added an override method action in admin

## 0.0.31

- Catch error on scheduled task creation when namespace cannot be determined
- extend twig block intention creates wrong folder structure on bundles
- Ignore plugins outside custom/plugins for store inspections
- Added extend block editor popup to avoid getting loss of read only mode
- Added insert snippet code action using ALT+Insert
- Added intent to override/extend an admin component
- Added admin component line markers, to find all extends/overrides, or navigate to base component
- Added admin component symbol contributor to find in symbol search

## 0.0.30

- Added autocomplete for vue routes
- Added missing length for composer.json check
- Improved extension namespace sync

## 0.0.29

- Added autocomplete to sw_csrf

## 0.0.28

- Added new Create action for App Scripts and Entities.xml
- Added DAL Autocomplete To Criteria when it has a generic Typehint like /** @var Criteria<ProductDefinition> */

## 0.0.27

- Mark next EAP version as compatible

## 0.0.26

- Added composer.json missing extra.labelextra.descriptionextra.manufacturerLinkextra.supportLinkextra.require.shopware/core

## 0.0.25

- Added criteria id misused inspection
- Fixed ExtendTwigBlock when template folder is not existing
- Fixed compatibility with symfony plugin 0.23.215

## 0.0.24

- Apps works now also on Extend this twig blockservices.Xservices.repository.searchnext-xxx/some-name

## 0.0.23

- Support EAP 2022

## 0.0.22

## 0.0.21

- Fix compatibility issue with 2021.3

## 0.0.20

- Fixed issue with cms element naming thanks to @breaker92
- Add Create an App dialog
- Fixed deprecations

## 0.0.19

- Added cms element action
- Added copy snippet as code in JSON file
- Fixed cms block preview scss file

## 0.0.18

- Improve compatibility with newest EAP

## 0.0.17

- Added new action to create a CMS block
- Fixed a bug in SynchronizeNamespaceAction where it doesn't get applied

## 0.0.16

- Allow snippet creation using Quick Fix
- Added new action to synchronize IDE settings with existing extensions

## 0.0.15

- Added Shopware.Mixin.getByName autocomplete
- Added CreateEventListenerIntention

## 0.0.14

- Scans for storefront snippets as before
- The bundles in ExtendTemplate now sorted
- Added inspection for missing snippets in Administration

## 0.0.13

- Extend twig block works now in vendor folder also ignores vendor plugins to choose
- ShopwareBundleIndex ignores plugins created for testing
- Added inspection or missing administration translation

## 0.0.12

- increase min idea version

## 0.0.11

- Added autocomplete to this.repositoryFactory.create('x');
- Added Intention Action "Extend this block" to easy extending a block
- Added inspection to show an error when abstract class has been wrong used in constructor

## 0.0.10

- Fix system config autocomplete to add .config

## 0.0.9

- Added autocomplete to twig functions theme_config and config
- Added autocomplete for Module.register labels
- Show only admin component autocomplete when the twig file is next to an index.js

## 0.0.8

- Show all translation on goto handler
- Show admin components only in HTML context
- Remove internal variable usage

## 0.0.7

- Added autocomplete for admin snippets
- Added autocomplete for admin components
- Added autocomplete for seoUrl, sw_include and sw_extends
- Added plugin generator

## 0.0.6

- Added vue module generator
- Added scheduled task generator
- Added feature flag autocomplete and goto handler

## 0.0.5

- Removed custom navigation goto and implemented into Symfony plugin
- The Symfony plugin does not show anymore missing snippets

## 0.0.4

- Add changelog title escaping to dash

## 0.0.3

- Moved generated files to file templates. Can be now edited in Settings
- Improved Vue component generation
- Added Changelog generation
- Added storefront snippet autocompletion

## 0.0.2

- Added UUID live template
- Fix conflict with Shopware 5 PhpStorm Plugin
- Added PHPUnit live templates
