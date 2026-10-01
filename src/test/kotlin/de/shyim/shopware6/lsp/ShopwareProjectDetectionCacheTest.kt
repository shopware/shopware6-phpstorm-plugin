package de.shyim.shopware6.lsp

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class ShopwareProjectDetectionCacheTest {
    private val root = Path.of("/project")

    @Test fun `cold checks schedule one background read and warm checks never read`() {
        for (supported in listOf(true, false)) {
            val work = mutableListOf<() -> Unit>()
            var reads = 0
            var updates = 0
            val cache = ShopwareProjectDetectionCache({ reads++; supported }, { work.add(it) }, { updates++ })
            repeat(100) { assertNull(cache.supports(root)) }
            assertEquals(0, reads)
            assertEquals(1, work.size)
            work.removeAt(0)()
            repeat(100) { assertEquals(supported, cache.supports(root)) }
            assertEquals(1, reads)
            assertEquals(1, updates)
            assertTrue(work.isEmpty())
        }
    }

    @Test fun `only marker or ancestor changes invalidate detection`() {
        val changedPaths = listOf("composer.json", "composer.lock", "manifest.xml", "config/bundles.php", ".config/shopware/lsp.yaml", "config", ".config/shopware", ".config", "")
        for (changed in changedPaths) {
            var reads = 0
            val cache = ShopwareProjectDetectionCache({ reads++; true }, { it() }, {})
            assertEquals(true, cache.supports(root))
            assertFalse(cache.invalidate(listOf(root.resolve("src/Example.php"), root.resolve("nested/composer.json"), Path.of("/other/composer.json"))))
            assertEquals(true, cache.supports(root))
            assertEquals(1, reads)
            assertTrue("Expected invalidation for $changed", cache.invalidate(listOf(root.resolve(changed))))
            assertEquals(true, cache.supports(root))
            assertEquals(2, reads)
        }
    }

    @Test fun `invalidation cannot publish an obsolete in-flight result`() {
        val work = mutableListOf<() -> Unit>()
        var supported = true
        var updates = 0
        val cache = ShopwareProjectDetectionCache({ supported }, { work.add(it) }, { updates++ })
        assertNull(cache.supports(root))
        assertTrue(cache.invalidate(listOf(root.resolve("composer.json"))))
        assertNull(cache.supports(root))
        work.removeAt(0)()
        assertEquals(0, updates)
        assertNull(cache.supports(root))
        supported = false
        work.removeAt(0)()
        assertEquals(false, cache.supports(root))
        assertEquals(1, updates)
    }
}
