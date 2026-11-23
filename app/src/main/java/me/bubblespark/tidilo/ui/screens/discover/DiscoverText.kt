package me.bubblespark.tidilo.ui.screens.discover

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.discover.DiscoverReason
import me.bubblespark.tidilo.data.discover.DiscoverShelf
import me.bubblespark.tidilo.data.discover.OnThisDayPeriod
import me.bubblespark.tidilo.data.engagement.EngagementSurface

/** A localized text reference that can be resolved later, so the reason mapping stays JVM-testable. */
internal sealed interface TextSpec {
    data class Plain(@StringRes val res: Int, val args: List<Any> = emptyList()) : TextSpec
    data class Plural(@PluralsRes val res: Int, val count: Int) : TextSpec
}

/** §4.3 reason label for [reason]. Every count is a plural. */
internal fun reasonTextSpec(reason: DiscoverReason): TextSpec = when (reason) {
    is DiscoverReason.DormantNeverOpened -> TextSpec.Plural(R.plurals.discover_reason_dormant, reason.days)
    is DiscoverReason.AffinityEntity -> TextSpec.Plain(R.string.discover_reason_affinity, listOf(reason.entity))
    is DiscoverReason.OnThisDay -> TextSpec.Plain(when (reason.period) {
        OnThisDayPeriod.ONE_YEAR -> R.string.discover_reason_on_this_day_year
        OnThisDayPeriod.SIX_MONTHS -> R.string.discover_reason_on_this_day_six_months
        OnThisDayPeriod.ONE_MONTH -> R.string.discover_reason_on_this_day_month
    })
    is DiscoverReason.RssExpiring -> TextSpec.Plural(R.plurals.discover_reason_rss_expiring, reason.days)
    is DiscoverReason.Connection -> TextSpec.Plain(R.string.discover_reason_connection, listOf(reason.entity))
    is DiscoverReason.Rising -> TextSpec.Plural(R.plurals.discover_reason_rising, reason.count)
}

/** Short overline for "On this day" ("A year ago"). */
@StringRes
internal fun periodLabelRes(period: OnThisDayPeriod): Int = when (period) {
    OnThisDayPeriod.ONE_YEAR -> R.string.discover_period_year
    OnThisDayPeriod.SIX_MONTHS -> R.string.discover_period_six_months
    OnThisDayPeriod.ONE_MONTH -> R.string.discover_period_month
}

@StringRes
internal fun shelfTitleRes(shelf: DiscoverShelf): Int = when (shelf) {
    DiscoverShelf.HERO -> R.string.discover_shelf_hero
    DiscoverShelf.ON_THIS_DAY -> R.string.discover_shelf_on_this_day
    DiscoverShelf.JUST_SAVED -> R.string.discover_shelf_just_saved
    DiscoverShelf.DECK -> R.string.discover_shelf_deck
    DiscoverShelf.SLEEPING_GEMS -> R.string.discover_shelf_sleeping_gems
    DiscoverShelf.RISING -> R.string.discover_shelf_rising
    DiscoverShelf.FRESH_RSS -> R.string.discover_shelf_fresh_rss
    DiscoverShelf.CONNECTIONS -> R.string.discover_shelf_connections
    DiscoverShelf.MAP -> R.string.discover_shelf_map
}

/** Engagement surface of a card on [shelf] (the Map holds no bookmarks). */
internal fun surfaceOf(shelf: DiscoverShelf): EngagementSurface = when (shelf) {
    DiscoverShelf.HERO -> EngagementSurface.DISCOVER_HERO
    DiscoverShelf.ON_THIS_DAY -> EngagementSurface.DISCOVER_ON_THIS_DAY
    DiscoverShelf.JUST_SAVED -> EngagementSurface.DISCOVER_JUST_SAVED
    DiscoverShelf.DECK -> EngagementSurface.DISCOVER_DECK
    DiscoverShelf.SLEEPING_GEMS -> EngagementSurface.DISCOVER_SLEEPING_GEMS
    DiscoverShelf.RISING -> EngagementSurface.DISCOVER_RISING
    DiscoverShelf.FRESH_RSS -> EngagementSurface.DISCOVER_FRESH_RSS
    DiscoverShelf.CONNECTIONS -> EngagementSurface.DISCOVER_CONNECTIONS
    DiscoverShelf.MAP -> EngagementSurface.UNKNOWN
}

@Composable
internal fun resolve(spec: TextSpec): String = when (spec) {
    is TextSpec.Plain -> stringResource(spec.res, *spec.args.toTypedArray())
    is TextSpec.Plural -> pluralStringResource(spec.res, spec.count, spec.count)
}

@Composable
internal fun reasonText(reason: DiscoverReason?): String? = reason?.let { resolve(reasonTextSpec(it)) }
