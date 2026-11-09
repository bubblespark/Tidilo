package me.bubblespark.tidilo.ui.components

/** Presentation order is independent of composition ancestry, including sibling modal windows. */
internal class OverlayLayerRegistry {
    private var nextToken = 1L
    private val layers = linkedSetOf<Long>()
    val activeCount: Int get() = layers.size
    fun allocate(): Long = nextToken++
    fun register(): Long = allocate().also(::register)
    fun register(token: Long) { layers.add(token) }
    fun unregister(token: Long) { layers.remove(token) }
    fun lowerLayers(token: Long): List<Long> = layers.filter { it < token }
}
