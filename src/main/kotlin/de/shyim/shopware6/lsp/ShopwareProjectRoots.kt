package de.shyim.shopware6.lsp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.platform.lsp.api.LspClientManager
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** File-support predicates only consult memory; marker reads run on a pooled thread. */
@Service(Service.Level.PROJECT)
class ShopwareProjectRoots(private val project: Project) : Disposable {
    private val cache = ShopwareProjectDetectionCache(
        detect = ShopwareProjectDetection::supports,
        schedule = { task -> ApplicationManager.getApplication().executeOnPooledThread {
            if (!project.isDisposed) task()
        } },
        updated = ::recheckOpenFiles,
    )

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            private var previousPaths = emptyList<Path>()

            override fun before(events: List<VFileEvent>) {
                previousPaths = paths(events)
            }

            override fun after(events: List<VFileEvent>) {
                // Keep both paths for renames and moves, including marker parent directories.
                if (cache.invalidate(previousPaths + paths(events))) recheckOpenFiles()
                previousPaths = emptyList()
            }

            private fun paths(events: List<VFileEvent>): List<Path> = events
                .filter { it.fileSystem is com.intellij.openapi.vfs.LocalFileSystem }
                .flatMap { listOfNotNull(it.path, it.file?.path) }
                .map(Path::of)
        })
    }

    fun supports(root: Path): Boolean? = cache.supports(root)

    private fun recheckOpenFiles() {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) LspClientManager.getInstance(project).startClientsIfNeeded(ShopwareLspIntegration::class.java)
        }
    }

    override fun dispose() { cache.clear() }
}

internal class ShopwareProjectDetectionCache(
    private val detect: (Path) -> Boolean,
    private val schedule: (() -> Unit) -> Unit,
    private val updated: () -> Unit,
) {
    private class Entry { @Volatile var supported: Boolean? = null }
    private val entries = ConcurrentHashMap<Path, Entry>()
    private val markers = listOf("composer.json", "composer.lock", "manifest.xml", "config/bundles.php", ".config/shopware/lsp.yaml")

    /** Null means detection is pending. Cache negative results as well as supported roots. */
    fun supports(root: Path): Boolean? {
        entries[root]?.let { return it.supported }
        val entry = Entry()
        entries.putIfAbsent(root, entry)?.let { return it.supported }
        schedule {
            val supported = detect(root)
            // A marker may have changed while the old detection was in flight.
            if (entries[root] === entry) {
                entry.supported = supported
                updated()
            }
        }
        return entry.supported
    }

    fun invalidate(paths: List<Path>): Boolean {
        var invalidated = false
        for (root in entries.keys) {
            if (paths.any { changed -> root.startsWith(changed) || markers.any { root.resolve(it).startsWith(changed) } }) {
                invalidated = entries.remove(root) != null || invalidated
            }
        }
        return invalidated
    }

    fun clear() { entries.clear() }
}
