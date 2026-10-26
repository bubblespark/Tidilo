package me.bubblespark.tidilo

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import me.bubblespark.tidilo.data.ImportRepository
import me.bubblespark.tidilo.data.notifications.NotificationCoordinator

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        disableActivityTransitions()
        super.onCreate(savedInstanceState)

        handleShareIntent(intent)

        finish()
        disableActivityTransitions()
    }

    private fun disableActivityTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND || intent.type != "text/plain") {
            NotificationCoordinator.getInstance(applicationContext).importError(
                IllegalArgumentException("Expected a shared text URL"))
            return
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val validUrl = ImportRepository.extractUrlFromText(sharedText)

        if (validUrl != null) {
            val repository = ImportRepository.getInstance(applicationContext)
            repository.importUrl(validUrl)
        } else {
            NotificationCoordinator.getInstance(applicationContext).importError(
                IllegalArgumentException("Shared text does not contain a valid HTTP/HTTPS URL"))
        }
    }

}
