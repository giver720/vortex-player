package com.vortex.player.ui.player

import android.graphics.Color
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerActivitySmokeTest {

    @Test
    fun playerLaunchesWithAnOpaqueBackground() {
        ActivityScenario.launch(PlayerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val value = TypedValue()
                assertTrue(
                    activity.theme.resolveAttribute(
                        android.R.attr.colorBackground,
                        value,
                        true
                    )
                )
                val color = if (value.resourceId != 0) {
                    ContextCompat.getColor(activity, value.resourceId)
                } else {
                    value.data
                }
                assertEquals(255, Color.alpha(color))
                assertTrue(!activity.isFinishing)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }
}
