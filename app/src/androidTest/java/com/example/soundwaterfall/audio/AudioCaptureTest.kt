package com.example.soundwaterfall.audio

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioCaptureTest {

    @get:Rule
    val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun opensTheMicrophoneAndDeliversSamples() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = AudioCapture(context).open(hop = 1024)

        assertTrue("capture failed: $result", result is SourceResult.Ok)
        val source = (result as SourceResult.Ok).source
        try {
            println(
                "AudioCaptureTest: source=${source.sourceLabel} rate=${source.sampleRate}"
            )
            assertTrue(
                "unexpected sample rate ${source.sampleRate}",
                source.sampleRate in AudioSourceChain.SAMPLE_RATES.toList(),
            )

            // Read a few buffers; at least one must deliver samples.
            val buffer = ShortArray(1024)
            var delivered = 0
            repeat(20) {
                val n = source.read(buffer)
                assertTrue("read returned error $n", n >= 0)
                delivered += n
            }
            assertTrue("no samples delivered at all", delivered > 0)
        } finally {
            source.close()
        }
    }
}
