package com.vortex.player.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

class VortexCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val notifications = NotificationOptions.Builder()
            .setTargetActivityClassName(VortexExpandedControlsActivity::class.java.name)
            .build()
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(notifications)
            .setExpandedControllerActivityClassName(VortexExpandedControlsActivity::class.java.name)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(media)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
