package me.bubblespark.tidilo.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.net.URLClassLoader

class ScreenInitOrderTest {

    private fun createIsolatedClassLoader(): URLClassLoader {
        val classPath = System.getProperty("java.class.path") ?: ""
        val urls = classPath.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it).toURI().toURL() }
            .toTypedArray()
        return URLClassLoader(urls, ClassLoader.getSystemClassLoader().parent)
    }

    @Test
    fun importLoadedFirst_allTabsContainsNonNullImport() {
        createIsolatedClassLoader().use { loader ->
            // Load Screen$Import first (simulating notification launch)
            val importClass = loader.loadClass("me.bubblespark.tidilo.ui.navigation.Screen\$Import")
            val importInstance = importClass.getField("INSTANCE").get(null)
            assertNotNull(importInstance)

            // Inspect Screen.allTabs
            val screenClass = loader.loadClass("me.bubblespark.tidilo.ui.navigation.Screen")
            val companion = screenClass.getField("Companion").get(null)
            val getAllTabs = companion.javaClass.getMethod("getAllTabs")
            val tabs = getAllTabs.invoke(companion) as List<*>

            assertEquals(3, tabs.size)
            assertNotNull("Screen.Library must not be null in allTabs", tabs[0])
            assertNotNull("Screen.Search must not be null in allTabs", tabs[1])
            assertNotNull("Screen.Import must not be null in allTabs even when loaded first", tabs[2])
        }
    }

    @Test
    fun searchLoadedFirst_allTabsContainsNonNullSearch() {
        createIsolatedClassLoader().use { loader ->
            // Load Screen$Search first
            val searchClass = loader.loadClass("me.bubblespark.tidilo.ui.navigation.Screen\$Search")
            val searchInstance = searchClass.getField("INSTANCE").get(null)
            assertNotNull(searchInstance)

            // Inspect Screen.allTabs
            val screenClass = loader.loadClass("me.bubblespark.tidilo.ui.navigation.Screen")
            val companion = screenClass.getField("Companion").get(null)
            val getAllTabs = companion.javaClass.getMethod("getAllTabs")
            val tabs = getAllTabs.invoke(companion) as List<*>

            assertEquals(3, tabs.size)
            assertNotNull("Screen.Library must not be null in allTabs", tabs[0])
            assertNotNull("Screen.Search must not be null in allTabs even when loaded first", tabs[1])
            assertNotNull("Screen.Import must not be null in allTabs", tabs[2])
        }
    }

    @Test
    fun libraryLoadedFirst_allTabsContainsNonNullLibrary() {
        createIsolatedClassLoader().use { loader ->
            // Load Screen$Library first
            val libraryClass = loader.loadClass("me.bubblespark.tidilo.ui.navigation.Screen\$Library")
            val libraryInstance = libraryClass.getField("INSTANCE").get(null)
            assertNotNull(libraryInstance)

            // Inspect Screen.allTabs
            val screenClass = loader.loadClass("me.bubblespark.tidilo.ui.navigation.Screen")
            val companion = screenClass.getField("Companion").get(null)
            val getAllTabs = companion.javaClass.getMethod("getAllTabs")
            val tabs = getAllTabs.invoke(companion) as List<*>

            assertEquals(3, tabs.size)
            assertNotNull("Screen.Library must not be null in allTabs even when loaded first", tabs[0])
            assertNotNull("Screen.Search must not be null in allTabs", tabs[1])
            assertNotNull("Screen.Import must not be null in allTabs", tabs[2])
        }
    }

    @Test
    fun normalClassLoading_allTabsContainsExpectedScreens() {
        val tabs = Screen.allTabs
        assertEquals(3, tabs.size)
        assertEquals(listOf(Screen.Library, Screen.Search, Screen.Import), tabs)
    }
}
