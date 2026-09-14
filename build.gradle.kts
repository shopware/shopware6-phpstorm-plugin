import java.util.Base64
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import de.shyim.packaging.BundleShopwareLsp
import de.shyim.packaging.NativePluginDistributions

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()

val bundleShopwareLsp = tasks.register<BundleShopwareLsp>("bundleShopwareLsp") {
    manifest = layout.projectDirectory.file("gradle/shopware-lsp.properties")
    outputDirectory = layout.buildDirectory.dir("shopware-lsp")
    downloadDirectory = layout.buildDirectory.dir("downloads/shopware-lsp")
}

val buildNativePlugins = tasks.register<NativePluginDistributions>("buildNativePlugins") {
    sourceArchive = tasks.buildPlugin.flatMap { it.archiveFile }
    targets = listOf("mac-arm64", "mac-x86_64", "linux-arm64", "linux-x86_64", "windows-x86_64")
    outputDirectory = layout.buildDirectory.dir("native-distributions")
}

val nativeTarget = providers.gradleProperty("nativeTarget")
if (nativeTarget.isPresent) {
    require(nativeTarget.get() in listOf("mac-arm64", "mac-x86_64", "linux-arm64", "linux-x86_64", "windows-x86_64")) {
        "Unsupported nativeTarget: ${nativeTarget.get()}"
    }
    val nativeArchiveName = tasks.buildPlugin.flatMap { it.archiveFile }
        .map { it.asFile.name.removeSuffix(".zip") }
        .zip(nativeTarget) { name, target -> "$name-$target.zip" }
    val nativeDirectory = buildNativePlugins.flatMap { it.outputDirectory }
    tasks.signPlugin {
        dependsOn(buildNativePlugins)
        archiveFile = nativeDirectory.zip(nativeArchiveName) { directory, name -> directory.file(name) }
        signedArchiveFile = layout.buildDirectory.file(nativeArchiveName.map {
            "signed-native-distributions/${it.removeSuffix(".zip")}-signed.zip"
        })
    }
    tasks.publishPlugin {
        // Never upload an unsigned or universal archive when publishing a platform variant.
        archiveFile = tasks.signPlugin.flatMap { it.signedArchiveFile }
        val signingConfigured = providers.environmentVariable("PRIVATE_KEY").orElse("")
            .zip(providers.environmentVariable("CERTIFICATE_CHAIN").orElse("")) { key, certificate ->
                key.isNotBlank() && certificate.isNotBlank()
            }
        doFirst {
            require(signingConfigured.get()) { "Publishing a native distribution requires PRIVATE_KEY and CERTIFICATE_CHAIN" }
        }
    }
    val certificateFile = layout.buildDirectory.file("tmp/native-signing-certificate.pem")
    val prepareNativeSigningCertificate = tasks.register("prepareNativeSigningCertificate") {
        val certificate = providers.environmentVariable("CERTIFICATE_CHAIN")
        inputs.property("certificate", certificate)
        outputs.file(certificateFile)
        doLast {
            val value = certificate.get()
            certificateFile.get().asFile.apply {
                parentFile.mkdirs()
                writeText(runCatching { String(Base64.getDecoder().decode(value.trim())) }.getOrDefault(value))
            }
        }
    }
    tasks.verifyPluginSignature {
        dependsOn(tasks.signPlugin)
        dependsOn(prepareNativeSigningCertificate)
        inputArchiveFile = tasks.signPlugin.flatMap { it.signedArchiveFile }
        // Gradle plugin 2.18.1 incorrectly passes certificate content as an extra CLI argument.
        certificateChain.unset()
        certificateChain.unsetConvention()
        certificateChainFile = certificateFile
    }
}

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(25)
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })
        bundledModules("intellij.platform.ui.jcef", "intellij.libraries.jcef")

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // GitHub Releases explicitly selects channels; local publishing infers EAP from a version suffix.
        // Stable releases also reach EAP subscribers because custom repositories take precedence.
        // https://plugins.jetbrains.com/docs/marketplace/custom-release-channels.html
        channels = providers.gradleProperty("pluginChannels").map { it.split(',') }
            .orElse(providers.gradleProperty("pluginVersion").map {
                if ('-' in it.substringBefore('+')) listOf("eap") else listOf("default", "eap")
            })
    }

    pluginVerification {
        ides {
            recommended()
            create("PS", "2026.2.1")
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
kover {
    currentProject {
        instrumentation {
            // Platform tests load the IDE and its plugins. Recording their coverage exhausts
            // the Gradle daemon heap when Kover aggregates it; only instrument our own code.
            includedClasses.addAll("de.shyim.shopware6.*", "icons.*")
        }
    }
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }

    runIde {
        jvmArgs("-Xmx4000m")
    }

    // The bundled Vue plugin fails to initialize its LSP service in the test IDE because it cannot
    // resolve its language-server binary from the transformed distribution layout. That failure
    // fires from VFS listeners as soon as a `.js` file is added to a fixture project and is promoted
    // to a test failure. Disable Vue only in the test sandbox; normal IDEs retain base Vue editing.
    prepareTestSandbox {
        from(bundleShopwareLsp) {
            into("${properties("pluginName").get()}/shopware-lsp")
        }
        val configDir = sandboxConfigDirectory
        doLast {
            configDir.get().asFile.resolve("disabled_plugins.txt")
                .writeText("org.jetbrains.plugins.vue\n")
        }
    }
    prepareSandbox {
        from(bundleShopwareLsp) {
            into("${properties("pluginName").get()}/shopware-lsp")
        }
    }
    processResources {
        exclude("fileTemplates/j2ee/**")
        from(fileTree("src/main/resources/fileTemplates/j2ee").files) {
            eachFile {
                relativePath = RelativePath(true, "fileTemplates", "j2ee", this.name)
            }
        }
    }
}
