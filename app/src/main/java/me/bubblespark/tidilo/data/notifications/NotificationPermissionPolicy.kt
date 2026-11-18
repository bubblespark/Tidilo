package me.bubblespark.tidilo.data.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.core.content.ContextCompat

const val NOTIFICATION_PREFS_NAME = "tidilo_notifications"
const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "permission_requested"

@Volatile
internal var postNotificationsPermissionOverrideForTesting: ((Context) -> Boolean)? = null

@Volatile
internal var activityResultRegistryOwnerOverrideForTesting: ActivityResultRegistryOwner? = null

fun hasPostNotificationsPermission(context: Context): Boolean =
    postNotificationsPermissionOverrideForTesting?.invoke(context)
        ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

/** Notification permission rationale is offered at most once when permission is not yet granted. */
fun shouldOfferNotificationPermissionRationale(
    permissionGranted: Boolean,
    alreadyRequested: Boolean
): Boolean = !permissionGranted && !alreadyRequested

/**
 * Evaluates whether an RSS settings or registration event should trigger the one-time
 * notification permission rationale dialog.
 */
fun shouldPromptNotificationPermissionOnRssEvent(
    permissionGranted: Boolean,
    alreadyRequested: Boolean,
    previousDailyEnabled: Boolean = false,
    newDailyEnabled: Boolean = false,
    initialRegistrationCompleted: Boolean = false
): Boolean = shouldOfferNotificationPermissionRationale(permissionGranted, alreadyRequested) &&
    ((!previousDailyEnabled && newDailyEnabled) || initialRegistrationCompleted)
