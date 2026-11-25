package me.bubblespark.tidilo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.RepositoryLibraryStore

/** PNG bytes of a tiny solid bitmap, so visual-cache stubs never allocate full-size artwork. */
internal fun tinyPng(width: Int, height: Int, color: Int = Color.GREEN): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    return try {
        bitmap.eraseColor(color)
        ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    } finally {
        bitmap.recycle()
    }
}

/** Keeps og:image and favicon lookups offline until [close] restores the previous hooks. */
internal class OfflineVisualCaches(imageBytes: suspend (String) -> ByteArray? = { null }) : AutoCloseable {
    private val oldImageDownloader = OgImageManager.imageDownloader
    private val oldIconDownloader = DomainIconManager.httpDownloader
    private val oldIconDiscoverer = DomainIconManager.htmlDiscoverer

    init {
        OgImageManager.imageDownloader = imageBytes
        DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, true, false) }
        DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
    }

    override fun close() {
        OgImageManager.imageDownloader = oldImageDownloader
        DomainIconManager.httpDownloader = oldIconDownloader
        DomainIconManager.htmlDiscoverer = oldIconDiscoverer
    }
}

/** The production Library store over an in-memory database, isolated from the app's on-disk data. */
internal class InMemoryLibrary(private val context: Context) : AutoCloseable {
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val uiScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val prefsName = "library-test-${UUID.randomUUID()}"
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val policies = DomainPolicyStore(db, storeScope = ioScope)
    val repository = BookmarkRepository(db, repositoryScope = ioScope, domainPolicyStore = policies)
    val store = RepositoryLibraryStore(
        repository, CollectionRepository(db), policies,
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    )

    /** A ViewModel whose coroutines end with [close]. */
    fun viewModel(): LibraryViewModel = LibraryViewModel(store, SavedStateHandle(), MutableStateFlow(true), uiScope)

    suspend fun awaitReady() = repository.retryInitializationAndAwait()

    override fun close() {
        uiScope.cancel()
        ioScope.cancel()
        db.close()
        context.deleteSharedPreferences(prefsName)
    }
}

/** Test tag of the [ViewportBox] container. */
internal const val VIEWPORT_TAG = "test_viewport"

/**
 * A [widthDp] × [heightDp] viewport that fits the test window by lowering the density,
 * so landscape and portrait layouts are measured without rotating the device.
 *
 * The viewport has no system bars: the test window's insets are in device pixels and would not
 * scale with the lowered density, so they are replaced with empty insets while it is shown.
 */
@Composable
internal fun ViewportBox(widthDp: Int, heightDp: Int, fontScale: Float, content: @Composable () -> Unit) {
    val contentView = LocalView.current.rootView.findViewById<ViewGroup>(android.R.id.content)
    DisposableEffect(contentView) {
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { _, _ -> WindowInsetsCompat.Builder().build() }
        contentView.requestApplyInsets()
        onDispose { ViewCompat.setOnApplyWindowInsetsListener(contentView, null) }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        val density = minOf(
            LocalDensity.current.density,
            constraints.maxWidth / widthDp.toFloat(),
            constraints.maxHeight / heightDp.toFloat()
        )
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
            Box(Modifier.size(widthDp.dp, heightDp.dp).testTag(VIEWPORT_TAG)) { content() }
        }
    }
}
