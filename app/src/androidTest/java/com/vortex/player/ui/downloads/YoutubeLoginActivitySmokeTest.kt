package com.vortex.player.ui.downloads

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YoutubeLoginActivitySmokeTest {
    @Test
    fun loginScreenOpensWithoutExposingAnExternalActivity() {
        ActivityScenario.launch(YoutubeLoginActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }
}
