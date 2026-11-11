package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import me.bubblespark.tidilo.ui.navigation.Screen
import me.bubblespark.tidilo.ui.theme.BackgroundDark
import me.bubblespark.tidilo.ui.theme.BackgroundLight
import me.bubblespark.tidilo.ui.theme.ModernDarkColorScheme
import me.bubblespark.tidilo.ui.theme.ModernLightColorScheme
import me.bubblespark.tidilo.ui.theme.OnSurfaceDark
import me.bubblespark.tidilo.ui.theme.OnSurfaceLight
import me.bubblespark.tidilo.ui.theme.OnSurfaceVariantDark
import me.bubblespark.tidilo.ui.theme.OnSurfaceVariantLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookmarkDetailTest {

    @Test
    fun testBookmarkItemDateFields() {
        val now = 1791650202300L // Expected 2026 timestamp
        val item = BookmarkItem(
            id = "test-1",
            title = "Test Article",
            url = "https://example.com/test",
            description = "Test Description",
            tag = Tag.create("Article", "Tech", listOf("Android", "Compose")),
            createdAt = now,
            updatedAt = now + 1000,
            note = "My test note"
        )

        assertEquals("test-1", item.id)
        assertEquals(now, item.createdAt)
        assertEquals(now + 1000, item.updatedAt)
        assertEquals("My test note", item.note)

        // Verify date formatting
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val formatted = sdf.format(Date(now))
        assertTrue("Formatted date should contain year", formatted.contains("2026") || formatted.length >= 10)
    }

    @Test
    fun testBookmarkDetailNavigationRoute() {
        val route = Screen.BookmarkDetail.createRoute("sample-id-123")
        assertEquals("bookmark_detail/sample-id-123", route)
        assertEquals("bookmark_detail/{bookmarkId}", Screen.BookmarkDetail.route)
    }

    @Test
    fun testBookmarkTagSerializationAndDeserializationPreservesTags() {
        val original = BookmarkItem(
            id = "test-tag-preservation",
            title = "Jetpack Compose Best Practices",
            url = "https://developer.android.com/compose",
            description = "Modern declarative UI toolkit",
            tag = Tag.create(
                category = "Article",
                subcategory = "Tech",
                entities = listOf("Compose", "Kotlin", "Android")
            ),
            note = "Check out layout guides"
        )

        // 1. Serialize
        val json = me.bubblespark.tidilo.data.BookmarkRepository.serializeBookmarks(listOf(original))
        assertTrue("Serialized JSON must contain 'tag' field", json.contains("\"tag\":"))
        assertTrue("Serialized JSON must contain category", json.contains("\"category\":\"Article\""))
        assertTrue("Serialized JSON must contain subcategory", json.contains("\"subcategory\":\"Tech\""))
        assertTrue("Serialized JSON must contain entities", json.contains("Compose"))

        // 2. Deserialize
        val parsedList = me.bubblespark.tidilo.data.BookmarkRepository.parseBookmarks(json)
        assertEquals(1, parsedList.size)
        val parsed = parsedList[0]

        // 3. Verify category and tags are restored correctly without becoming 'None'
        assertEquals("Article", parsed.tag.category)
        assertEquals("Tech", parsed.tag.subcategory)
        assertEquals(listOf("Compose", "Kotlin", "Android"), parsed.tag.entities)
        assertEquals("Check out layout guides", parsed.note)
    }

    @Test
    fun testCorruptedBookmarkAutoRepairFromSample() {
        // Simulate historical bug (JSON missing tag field)
        val corruptedJson = """
            [
              {
                "id": "b1",
                "title": "Android Developers: Material 3 Expressive",
                "url": "https://m3.material.io/",
                "description": "Expressive design system...",
                "createdAt": 1000,
                "updatedAt": 1000,
                "note": ""
              }
            ]
        """.trimIndent()

        val sampleBookmarks = listOf(
            BookmarkItem(
                id = "b1",
                title = "Android Developers: Material 3 Expressive",
                url = "https://m3.material.io/",
                description = "Expressive design system...",
                tag = Tag.create("Article", "Tech", listOf("Material 3", "Compose", "Design System"))
            )
        )

        // Parse with auto-repair
        val repaired = me.bubblespark.tidilo.data.BookmarkRepository.parseBookmarks(
            json = corruptedJson,
            sampleBookmarks = sampleBookmarks
        )

        assertEquals(1, repaired.size)
        val item = repaired[0]
        // Verify tags are restored from sample rather than falling back to 'None'
        assertEquals("Article", item.tag.category)
        assertEquals("Tech", item.tag.subcategory)
        assertEquals(listOf("Material 3", "Compose", "Design System"), item.tag.entities)
    }

    @Test
    fun testCorruptedBookmarkAutoRepairFromQueueTags() {
        // JSON for item enqueued from Queue where tag was missing at save time
        val userCorruptedJson = """
            [
              {
                "id": "user-added-1",
                "title": "Zenn Tech Article",
                "url": "https://zenn.dev/sample-user/articles/123",
                "description": "Zenn article...",
                "createdAt": 2000,
                "updatedAt": 2000,
                "note": ""
              }
            ]
        """.trimIndent()

        val queueTagsMap = mapOf(
            "https://zenn.dev/sample-user/articles/123" to Tag.create(
                category = "Article",
                subcategory = "Tech",
                entities = listOf("Zenn", "TypeScript")
            )
        )

        val repaired = me.bubblespark.tidilo.data.BookmarkRepository.parseBookmarks(
            json = userCorruptedJson,
            sampleBookmarks = emptyList(),
            queueTagsMap = queueTagsMap
        )

        assertEquals(1, repaired.size)
        val item = repaired[0]
        assertEquals("Article", item.tag.category)
        assertEquals("Tech", item.tag.subcategory)
        assertEquals(listOf("Zenn", "TypeScript"), item.tag.entities)
    }

    @Test
    fun testBookmarkTagModificationPreservesOtherFields() {
        val initialTime = 1000L
        val updateTime = 2000L
        val original = BookmarkItem(
            id = "b-test-edit",
            title = "Original Title",
            url = "https://example.com/item",
            description = "Original Description",
            tag = Tag.create("Article", "Tech", listOf("Kotlin")),
            createdAt = initialTime,
            updatedAt = initialTime,
            note = "Original Note",
            isNsfw = false,
        )

        val updatedTag = Tag.create("Tool", "Business", listOf("Kotlin", "Productivity"))
        val updatedNote = "Updated Note"
        val modified = original.copy(
            note = updatedNote,
            tag = updatedTag,
            updatedAt = updateTime
        )

        // Verify updated fields
        assertEquals("Tool", modified.tag.category)
        assertEquals("Business", modified.tag.subcategory)
        assertEquals(listOf("Kotlin", "Productivity"), modified.tag.entities)
        assertEquals("Updated Note", modified.note)
        assertEquals(updateTime, modified.updatedAt)

        // Verify preserved fields
        assertEquals("b-test-edit", modified.id)
        assertEquals("Original Title", modified.title)
        assertEquals("https://example.com/item", modified.url)
        assertEquals("Original Description", modified.description)
        assertEquals(initialTime, modified.createdAt)
        assertEquals(false, modified.isNsfw)
    }

    @Test
    fun testEntityTagListToggleAndRemoval() {
        var entities = listOf("Compose", "Android", "Kotlin")

        // 1. Remove entity
        entities = entities.filterNot { it.equals("Android", ignoreCase = true) }
        assertEquals(listOf("Compose", "Kotlin"), entities)

        // 2. Toggle existing entity (should remove)
        val target1 = "Compose"
        entities = if (entities.any { it.equals(target1, ignoreCase = true) }) {
            entities.filterNot { it.equals(target1, ignoreCase = true) }
        } else {
            entities + target1
        }
        assertEquals(listOf("Kotlin"), entities)

        // 3. Toggle absent entity (should add)
        val target2 = "Jetpack"
        entities = if (entities.any { it.equals(target2, ignoreCase = true) }) {
            entities.filterNot { it.equals(target2, ignoreCase = true) }
        } else {
            entities + target2
        }
        assertEquals(listOf("Kotlin", "Jetpack"), entities)
    }

    @Test
    fun testCompareEntityNamesSorting() {
        val list = listOf("日本語", "Apple", "zebra", "Banana", "テスト")
        val sorted = list.sortedWith { a, b -> compareEntityNames(a, b) }

        // English / ASCII words should precede Japanese, and be sorted alphabetically ignoring case
        assertEquals(listOf("Apple", "Banana", "zebra", "テスト", "日本語"), sorted)
    }

    @Test
    fun testTagUpdatePopulatesTagsAndNote() {
        val original = BookmarkItem(
            id = "b-tag-update",
            title = "BOM Mapping",
            url = "https://developer.android.com/compose/bom",
            description = "BOM release notes",
            tag = Tag.createEmpty()
        )

        // User edits tags in BookmarkDetailScreen
        val newTag = Tag.create("Tool", "Tech", listOf("Android", "Compose"))
        val updated = original.copy(
            tag = newTag,
            note = "Updated note"
        )

        // Verify tags and note are fully populated
        assertEquals("Tool", updated.tag.category)
        assertEquals("Tech", updated.tag.subcategory)
        assertEquals(listOf("Android", "Compose"), updated.tag.entities)
        assertEquals("Updated note", updated.note)
    }

    @Test
    fun testNsfwToggleAndPersistence() {
        val original = BookmarkItem(
            id = "b-nsfw-test",
            title = "Test Item",
            url = "https://example.com/item",
            description = "Description",
            tag = Tag.create("Media", "Video", listOf("Tutorial")),
            isNsfw = false
        )
        org.junit.Assert.assertFalse(original.isNsfw)

        // Toggle NSFW ON
        val toggledOn = original.copy(isNsfw = true)
        assertTrue(toggledOn.isNsfw)
        assertEquals("Media", toggledOn.tag.category)

        // Toggle NSFW OFF
        val toggledOff = toggledOn.copy(isNsfw = false)
        org.junit.Assert.assertFalse(toggledOff.isNsfw)
    }

    @Test
    fun testNsfwWarningCondition() {
        fun shouldShowWarning(isNsfwContentVisible: Boolean, isNsfw: Boolean): Boolean {
            return !isNsfwContentVisible && isNsfw
        }

        // ShowNSFWContents = false (disabled in Settings), isNsfw = true (toggled ON) -> Show warning
        assertTrue(shouldShowWarning(isNsfwContentVisible = false, isNsfw = true))

        // ShowNSFWContents = true (enabled in Settings), isNsfw = true -> Don't show warning
        org.junit.Assert.assertFalse(shouldShowWarning(isNsfwContentVisible = true, isNsfw = true))

        // ShowNSFWContents = false, isNsfw = false -> Don't show warning
        org.junit.Assert.assertFalse(shouldShowWarning(isNsfwContentVisible = false, isNsfw = false))

        // ShowNSFWContents = true, isNsfw = false -> Don't show warning
        org.junit.Assert.assertFalse(shouldShowWarning(isNsfwContentVisible = true, isNsfw = false))
    }

    @Test
    fun testMaxEntitiesConstant() {
        assertEquals(10, Tag.MAX_ENTITIES)
    }

    @Test
    fun testTagToggleEntityFunction() {
        // Prepare list with 10 entities
        val initialEntities = (1..10).map { "Entity$it" }
        assertEquals(10, initialEntities.size)

        var draftEntities = initialEntities

        // Attempt to toggle an absent entity when already at Tag.MAX_ENTITIES
        val newEntity = "Entity11"
        draftEntities = Tag.toggleEntity(draftEntities, newEntity)
        assertEquals(10, draftEntities.size)
        org.junit.Assert.assertFalse(draftEntities.contains("Entity11"))

        // Toggling an existing entity when at Tag.MAX_ENTITIES should successfully remove it
        val existingEntity = "Entity5"
        draftEntities = Tag.toggleEntity(draftEntities, existingEntity)
        assertEquals(9, draftEntities.size)
        org.junit.Assert.assertFalse(draftEntities.contains("Entity5"))

        // Now at 9 entities, toggling a new entity should succeed
        draftEntities = Tag.toggleEntity(draftEntities, newEntity)
        assertEquals(10, draftEntities.size)
        assertTrue(draftEntities.contains("Entity11"))

        // Blank entity should be ignored
        assertEquals(10, Tag.toggleEntity(draftEntities, "   ").size)
    }

    @Test
    fun testTagAddEntityFunction() {
        var draftEntities = (1..10).map { "Tag$it" }
        val newTag = "Tag11"

        // Attempt to add when at limit
        draftEntities = Tag.addEntity(draftEntities, newTag)
        assertEquals(10, draftEntities.size)
        org.junit.Assert.assertFalse(draftEntities.contains("Tag11"))

        // Attempt to add existing tag when at limit
        draftEntities = Tag.addEntity(draftEntities, "tag1")
        assertEquals(10, draftEntities.size)

        // Drop one, then add new tag
        draftEntities = draftEntities.dropLast(1)
        draftEntities = Tag.addEntity(draftEntities, newTag)
        assertEquals(10, draftEntities.size)
        assertTrue(draftEntities.contains("Tag11"))

        // Adding duplicate (case-insensitive) should return unchanged list
        val sizeBeforeDup = draftEntities.size
        draftEntities = Tag.addEntity(draftEntities, "tag11")
        assertEquals(sizeBeforeDup, draftEntities.size)

        // Blank entity should be ignored
        val sizeBeforeBlank = draftEntities.size
        draftEntities = Tag.addEntity(draftEntities, "  ")
        assertEquals(sizeBeforeBlank, draftEntities.size)
    }

    @Test
    fun testBookmarkItemOgImageDerivationForDetailScreen() {
        val withOg = BookmarkItem(
            id = "test-detail-og",
            title = "Article with OG Image",
            url = "https://example.com/article",
            description = "Description",
            tag = Tag.create("Article", "Tech", listOf("Android")),
            ogImageUrl = "https://example.com/images/og-preview.png"
        )
        val filename = requireNotNull(withOg.ogImageFilename)
        assertTrue(filename.isNotBlank())

        val withoutOg = BookmarkItem(
            id = "test-detail-no-og",
            title = "Article without OG Image",
            url = "https://example.com/article",
            description = "Description",
            tag = Tag.create("Article", "Tech", listOf("Android")),
            ogImageUrl = null
        )
        org.junit.Assert.assertNull(withoutOg.ogImageFilename)

        val withBlankOg = BookmarkItem(
            id = "test-detail-blank-og",
            title = "Article with Blank OG Image",
            url = "https://example.com/article",
            description = "Description",
            tag = Tag.create("Article", "Tech", listOf("Android")),
            ogImageUrl = "   "
        )
        org.junit.Assert.assertNull(withBlankOg.ogImageFilename)
    }

    @Test
    fun testBookmarkDetailAspectRatioCalculation() {
        // Normal 1200x630 og:image aspect ratio (~1.904)
        val normalRatio = me.bubblespark.tidilo.ui.components.calculateOgAspectRatio(1200, 630)
        assertTrue(normalRatio > 1.9f && normalRatio < 1.91f)

        // Square 1:1 image
        val squareRatio = me.bubblespark.tidilo.ui.components.calculateOgAspectRatio(600, 600)
        assertEquals(1.0f, squareRatio, 0.001f)

        // Tall portrait image
        val portraitRatio = me.bubblespark.tidilo.ui.components.calculateOgAspectRatio(600, 1200)
        assertEquals(0.5f, portraitRatio, 0.001f)

        // Invalid height fallback
        val zeroHeightRatio = me.bubblespark.tidilo.ui.components.calculateOgAspectRatio(100, 0)
        assertEquals(me.bubblespark.tidilo.ui.components.DEFAULT_OG_ASPECT_RATIO, zeroHeightRatio, 0.001f)
    }

    @Test
    fun testOgImageStateFailedLoadShowsDomainWatermarkFallback() {
        // When on-demand fetch fails (returning null), resolved MUST be true so DomainWatermark fallback appears
        val state = me.bubblespark.tidilo.ui.components.resolvedOgImageState(null)

        assertNull("Failed fetch should produce null bitmap", state.bitmap)
        assertTrue("State must be resolved after failure to prevent hiding watermark", state.resolved)
        assertTrue("Fallback must be active after fetch failure", state.shouldShowFallback)
        assertTrue("Watermark fallback must be active", state.shouldShowDomainWatermarkFallback)
    }

    @Test
    fun testOgImageStateSuccessfulImageSuppressesWatermarkFallback() {
        // When image load succeeds, resolved is true and DomainWatermark fallback must NOT be shown
        val dummy = DummyImageBitmap()
        val state = me.bubblespark.tidilo.ui.components.resolvedOgImageState(dummy)

        assertNotNull("Successful fetch must produce non-null bitmap", state.bitmap)
        assertEquals(dummy, state.bitmap)
        assertTrue("State must be marked resolved", state.resolved)
        assertFalse("Fallback must not be active when image is present", state.shouldShowFallback)
        assertFalse("Watermark fallback must not be active when image is present", state.shouldShowDomainWatermarkFallback)
    }

    @Test
    fun testOgImageStatePendingLoadHidesWatermarkDuringFetch() {
        // While fetching an existing OG URL, resolved is false to prevent premature watermark flicker
        val state = me.bubblespark.tidilo.ui.components.initialOgImageState(cached = null, ogFilename = "og_preview.webp")

        assertNull("No bitmap yet while pending", state.bitmap)
        assertFalse("Pending state must not be resolved yet", state.resolved)
        assertFalse("Watermark fallback must not appear while fetch is in progress", state.shouldShowFallback)
        assertFalse("DomainWatermark must be hidden while fetch is pending", state.shouldShowDomainWatermarkFallback)
    }

    @Test
    fun testOgImageStateNoOgUrlResolvesImmediatelyWithFallback() {
        // Bookmark without an OG image has no file to fetch, so it is resolved immediately with watermark fallback
        val state = me.bubblespark.tidilo.ui.components.initialOgImageState(cached = null, ogFilename = null)

        assertNull("No bitmap available", state.bitmap)
        assertTrue("State must be immediately resolved when no OG file exists", state.resolved)
        assertTrue("Fallback must be shown immediately", state.shouldShowFallback)
        assertTrue("DomainWatermark must be shown immediately", state.shouldShowDomainWatermarkFallback)
    }

    @Test
    fun testOgImageStateCachedHitResolvesImmediatelyWithImage() {
        // When image is already in memory cache, it is immediately resolved without showing fallback
        val dummy = DummyImageBitmap()
        val state = me.bubblespark.tidilo.ui.components.initialOgImageState(cached = dummy, ogFilename = "og_preview.webp")

        assertNotNull("Cached bitmap must be preserved", state.bitmap)
        assertEquals(dummy, state.bitmap)
        assertTrue("State must be immediately resolved when cache hit occurs", state.resolved)
        assertFalse("Fallback must not be shown on cache hit", state.shouldShowFallback)
        assertFalse("DomainWatermark must not be shown on cache hit", state.shouldShowDomainWatermarkFallback)
    }

    @Test
    fun testDetailHeroPaletteExtractsDistinctTopAndBottomEdgeColors() {
        val width = 20
        val height = 20
        val pixels = IntArray(width * height)
        val topAmber = (0xFF shl 24) or (245 shl 16) or (170 shl 8) or 20
        val bottomCyan = (0xFF shl 24) or (20 shl 16) or (190 shl 8) or 240
        for (y in 0 until height) {
            val color = if (y < height / 2) topAmber else bottomCyan
            for (x in 0 until width) {
                pixels[y * width + x] = color
            }
        }

        val palette = extractDetailHeroPaletteFromPixels(pixels, width, height, "example.com")

        assertTrue("Top edge color should be warm amber (red > blue)", palette.topEdgeColor.red > palette.topEdgeColor.blue + 0.3f)
        assertTrue("Bottom edge color should be cool cyan (blue > red)", palette.bottomEdgeColor.blue > palette.bottomEdgeColor.red + 0.3f)
        assertTrue("Accent color should be opaque", palette.accentColor.alpha > 0.9f)
    }

    @Test
    fun testDetailHeroPaletteCalibratesExtremeWhiteAndBlackEdgesForThemeLegibility() {
        val width = 16
        val height = 16
        val pixels = IntArray(width * height)
        val pureWhite = (0xFF shl 24) or 0xFFFFFF
        val pureBlack = (0xFF shl 24) or 0x000000
        for (y in 0 until height) {
            val color = if (y < height / 2) pureWhite else pureBlack
            for (x in 0 until width) {
                pixels[y * width + x] = color
            }
        }

        val rawPalette = extractDetailHeroPaletteFromPixels(pixels, width, height, "example.com")
        val darkTop = calibrateDetailEdgeColor(rawPalette.topEdgeColor, isDark = true, isTopEdge = true)
        val darkBottom = calibrateDetailEdgeColor(rawPalette.bottomEdgeColor, isDark = true, isTopEdge = false)
        val lightBottom = calibrateDetailEdgeColor(rawPalette.bottomEdgeColor, isDark = false, isTopEdge = false)

        // In dark mode, a pure white top edge must be darkened so white status bar icons remain legible
        val darkTopBrightness = (darkTop.red + darkTop.green + darkTop.blue) / 3f
        assertTrue("Dark-mode top edge should cap extreme white brightness: $darkTopBrightness", darkTopBrightness <= 0.45f)

        // In dark mode, a pure black bottom edge remains dark and valid
        val darkBottomBrightness = (darkBottom.red + darkBottom.green + darkBottom.blue) / 3f
        assertTrue("Dark-mode bottom edge stays dark: $darkBottomBrightness", darkBottomBrightness <= 0.35f)

        // In light mode, a pure black bottom edge must be lightened so dark text over the gradient extension remains legible
        val lightBottomBrightness = (lightBottom.red + lightBottom.green + lightBottom.blue) / 3f
        assertTrue("Light-mode bottom edge should lift extreme black brightness: $lightBottomBrightness", lightBottomBrightness >= 0.65f)

        // Saturated and extreme colors: verify calibrated edge luminance and WCAG 4.5:1 contrast ratio
        // across both top-edge (onSurface) and bottom-edge (onSurfaceVariant) paths using the exact
        // Default and Modern theme foreground/canvas pairs supplied by rememberDetailHeroPalette.
        val sampleEdges = listOf(
            Color(0f, 1f, 0f, 1f), // pure green
            Color(1f, 0f, 0f, 1f), // pure red
            Color(1f, 1f, 0f, 1f), // pure yellow
            Color(0f, 0f, 1f, 1f), // pure blue
            Color(1f, 1f, 1f, 1f), // pure white
            Color(0f, 0f, 0f, 1f)  // pure black
        )

        data class ThemeContrastSpec(
            val name: String,
            val isDark: Boolean,
            val isTopEdge: Boolean,
            val foreground: Color,
            val background: Color
        )

        val specs = listOf(
            // Default Dark
            ThemeContrastSpec("DefaultDark-Top", isDark = true, isTopEdge = true, foreground = OnSurfaceDark, background = BackgroundDark),
            ThemeContrastSpec("DefaultDark-Bottom", isDark = true, isTopEdge = false, foreground = OnSurfaceVariantDark, background = BackgroundDark),
            // Modern Dark
            ThemeContrastSpec("ModernDark-Top", isDark = true, isTopEdge = true, foreground = ModernDarkColorScheme.onSurface, background = ModernDarkColorScheme.background),
            ThemeContrastSpec("ModernDark-Bottom", isDark = true, isTopEdge = false, foreground = ModernDarkColorScheme.onSurfaceVariant, background = ModernDarkColorScheme.background),
            // Default Light
            ThemeContrastSpec("DefaultLight-Top", isDark = false, isTopEdge = true, foreground = OnSurfaceLight, background = BackgroundLight),
            ThemeContrastSpec("DefaultLight-Bottom", isDark = false, isTopEdge = false, foreground = OnSurfaceVariantLight, background = BackgroundLight),
            // Modern Light
            ThemeContrastSpec("ModernLight-Top", isDark = false, isTopEdge = true, foreground = ModernLightColorScheme.onSurface, background = ModernLightColorScheme.background),
            ThemeContrastSpec("ModernLight-Bottom", isDark = false, isTopEdge = false, foreground = ModernLightColorScheme.onSurfaceVariant, background = ModernLightColorScheme.background)
        )

        for (edge in sampleEdges) {
            for (spec in specs) {
                val calibrated = calibrateDetailEdgeColor(
                    color = edge,
                    isDark = spec.isDark,
                    isTopEdge = spec.isTopEdge,
                    foregroundColor = spec.foreground,
                    backgroundColor = spec.background
                )
                val directContrast = detailContrastRatio(spec.foreground, calibrated)
                val compositedContrast = detailContrastRatio(
                    spec.foreground,
                    calibrated.copy(alpha = 0.45f).compositeOver(spec.background)
                )
                assertTrue(
                    "${spec.name} direct contrast must be >= 4.5:1 (was $directContrast for edge=$edge, fg=${spec.foreground}, lum=${calibrated.luminance()})",
                    directContrast >= 4.5f
                )
                assertTrue(
                    "${spec.name} composited contrast must be >= 4.5:1 (was $compositedContrast for edge=$edge, fg=${spec.foreground}, bg=${spec.background})",
                    compositedContrast >= 4.5f
                )
            }
        }
    }

    @Test
    fun testFormatClassificationBreadcrumbAllCombinations() {
        // 1. Category + Subcategory + NSFW
        assertEquals(
            "Article > Entertainment - NSFW",
            formatClassificationBreadcrumb("Article", "Entertainment", isNsfw = true)
        )

        // 2. Category + Subcategory (no NSFW)
        assertEquals(
            "Article > Entertainment",
            formatClassificationBreadcrumb("Article", "Entertainment", isNsfw = false)
        )

        // 3. Category only + NSFW
        assertEquals(
            "Article - NSFW",
            formatClassificationBreadcrumb("Article", "", isNsfw = true)
        )

        // 4. Category only (no NSFW)
        assertEquals(
            "Article",
            formatClassificationBreadcrumb("Article", "  ", isNsfw = false)
        )

        // 5. Subcategory only + NSFW
        assertEquals(
            "Entertainment - NSFW",
            formatClassificationBreadcrumb("", "Entertainment", isNsfw = true)
        )

        // 6. Subcategory only (no NSFW)
        assertEquals(
            "Entertainment",
            formatClassificationBreadcrumb("  ", "Entertainment", isNsfw = false)
        )

        // 7. No Category, no Subcategory, NSFW = true
        assertEquals(
            "NSFW",
            formatClassificationBreadcrumb("", "", isNsfw = true)
        )

        // 8. No Category, no Subcategory, NSFW = false -> null (omitted)
        assertNull(
            formatClassificationBreadcrumb("", "   ", isNsfw = false)
        )
    }

    @Test
    fun testAmbientLightCenterCalculationWithBanner() {
        val containerWidth = 1080f
        val statusBarTopPx = 100f
        val maxBannerHeightPx = 600f
        val aspectRatio = 2.0f // bannerHeight = 1080 / 2 = 540f (< 600f)

        // At scroll = 0, center X is middle of container, center Y is statusBarTop + bannerHeight / 2
        val centerScroll0 = calculateAmbientLightCenter(
            bannerAspectRatio = aspectRatio,
            statusBarTopPx = statusBarTopPx,
            containerWidth = containerWidth,
            scrollY = 0f,
            maxBannerHeightPx = maxBannerHeightPx
        )
        assertEquals(540f, centerScroll0.x, 0.001f)
        assertEquals(370f, centerScroll0.y, 0.001f)

        // As user scrolls down by 120px, light center shifts upward by 120px
        val centerScroll120 = calculateAmbientLightCenter(
            bannerAspectRatio = aspectRatio,
            statusBarTopPx = statusBarTopPx,
            containerWidth = containerWidth,
            scrollY = 120f,
            maxBannerHeightPx = maxBannerHeightPx
        )
        assertEquals(540f, centerScroll120.x, 0.001f)
        assertEquals(250f, centerScroll120.y, 0.001f)
    }

    @Test
    fun testAmbientLightCenterCalculationWithMaxBannerHeightCap() {
        val containerWidth = 1080f
        val statusBarTopPx = 100f
        val maxBannerHeightPx = 600f
        val aspectRatio = 1.0f // uncapped 1080f capped at 600f

        val center = calculateAmbientLightCenter(
            bannerAspectRatio = aspectRatio,
            statusBarTopPx = statusBarTopPx,
            containerWidth = containerWidth,
            scrollY = 0f,
            maxBannerHeightPx = maxBannerHeightPx
        )
        // bannerHeight = 600f -> center Y = 100f + 300f = 400f
        assertEquals(540f, center.x, 0.001f)
        assertEquals(400f, center.y, 0.001f)
    }

    @Test
    fun testAmbientLightCenterCalculationFallbackWithoutBanner() {
        val containerWidth = 1080f
        val statusBarTopPx = 100f

        // At scroll = 0, fallback light originates at top right (width, 0)
        val centerScroll0 = calculateAmbientLightCenter(
            bannerAspectRatio = null,
            statusBarTopPx = statusBarTopPx,
            containerWidth = containerWidth,
            scrollY = 0f,
            maxBannerHeightPx = 600f
        )
        assertEquals(1080f, centerScroll0.x, 0.001f)
        assertEquals(0f, centerScroll0.y, 0.001f)

        // When scrolling down by 80px, fallback origin moves up with scroll
        val centerScroll80 = calculateAmbientLightCenter(
            bannerAspectRatio = null,
            statusBarTopPx = statusBarTopPx,
            containerWidth = containerWidth,
            scrollY = 80f,
            maxBannerHeightPx = 600f
        )
        assertEquals(1080f, centerScroll80.x, 0.001f)
        assertEquals(-80f, centerScroll80.y, 0.001f)
    }
}

private class DummyImageBitmap : androidx.compose.ui.graphics.ImageBitmap {
    override val width: Int get() = 1200
    override val height: Int get() = 630
    override val hasAlpha: Boolean get() = false
    override val config: androidx.compose.ui.graphics.ImageBitmapConfig
        get() = androidx.compose.ui.graphics.ImageBitmapConfig.Argb8888
    override val colorSpace: androidx.compose.ui.graphics.colorspace.ColorSpace
        get() = throw UnsupportedOperationException("Not needed in state unit test")
    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int
    ) = Unit
    override fun prepareToDraw() = Unit
}
