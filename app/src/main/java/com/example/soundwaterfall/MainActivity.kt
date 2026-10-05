package com.example.soundwaterfall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.soundwaterfall.audio.AudioCapture
import com.example.soundwaterfall.databinding.ActivityMainBinding
import com.example.soundwaterfall.dsp.AnalyzerSettings
import com.example.soundwaterfall.dsp.FrequencyScale
import com.example.soundwaterfall.dsp.SpectrumSnapshot
import com.example.soundwaterfall.dsp.WindowFunction
import com.example.soundwaterfall.ui.WaterfallView

class MainActivity : AppCompatActivity(), AnalyzerEngine.Sink {

    private lateinit var binding: ActivityMainBinding

    /** Held directly so the audio thread never touches the binding. */
    private lateinit var waterfall: WaterfallView

    private var engine: AnalyzerEngine? = null

    @Volatile
    private var snapshot: SpectrumSnapshot? = null

    @Volatile
    private var dbFloor: Float = AnalyzerSettings.DEFAULT.dbFloor

    private var settings: AnalyzerSettings = AnalyzerSettings.DEFAULT

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            binding.spectrum.refresh()
            waterfall.refresh()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCapture() else showPermissionPanel()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        waterfall = binding.waterfall
        binding.root.keepScreenOn = true

        savedInstanceState?.let { state ->
            settings = AnalyzerSettings(
                fftSize = state.getInt(KEY_FFT_SIZE, AnalyzerSettings.DEFAULT.fftSize),
                window = WindowFunction.entries[
                    state.getInt(KEY_WINDOW, AnalyzerSettings.DEFAULT.window.ordinal)
                ],
                dbFloor = state.getFloat(KEY_DB_FLOOR, AnalyzerSettings.DEFAULT.dbFloor),
            )
        }
        applySettingsLocally(settings)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_FFT_SIZE, settings.fftSize)
        outState.putInt(KEY_WINDOW, settings.window.ordinal)
        outState.putFloat(KEY_DB_FLOOR, settings.dbFloor)
    }

    override fun onStart() {
        super.onStart()
        // Re-checked every time: the permission may have been revoked while
        // the app was backgrounded (spec §8).
        if (hasPermission()) {
            startCapture()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        super.onStop()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        engine?.stop()
        engine = null
        snapshot = null
    }

    // --- settings ------------------------------------------------------------

    /** Updates local state and the views; Task 13 drives this from the controls. */
    private fun updateSettings(next: AnalyzerSettings) {
        val floorChanged = next.dbFloor != settings.dbFloor
        settings = next
        applySettingsLocally(next)
        engine?.updateSettings(next)
        // Existing rows were colourised against the old floor (spec §6.5).
        if (floorChanged) waterfall.clear()
    }

    private fun applySettingsLocally(next: AnalyzerSettings) {
        dbFloor = next.dbFloor
        binding.spectrum.dbFloor = next.dbFloor
    }

    // --- capture lifecycle ---------------------------------------------------

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCapture() {
        hidePanel()
        if (engine != null) return
        val capture = AudioCapture(this)
        val started = AnalyzerEngine(
            openSource = { capture.open(settings.hop) },
            sink = this,
            initialSettings = settings,
        )
        engine = started
        started.start()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    // --- AnalyzerEngine.Sink (audio thread unless noted) ---------------------

    override fun onStarted(sourceLabel: String, sampleRate: Int, scale: FrequencyScale) {
        val snap = SpectrumSnapshot(scale.binCount)
        snapshot = snap
        runOnUiThread {
            binding.sourceChip.text = getString(R.string.source_format, sourceLabel, sampleRate)
            bindViews(snap, scale)
        }
    }

    override fun onPipelineChanged(scale: FrequencyScale) {
        val snap = SpectrumSnapshot(scale.binCount)
        snapshot = snap
        runOnUiThread {
            bindViews(snap, scale)
            waterfall.clear()
        }
    }

    /**
     * Audio thread, ~47 times a second. Deliberately does NOT post to the main
     * thread: both sinks are built for cross-thread handoff, and the
     * Choreographer callback handles redrawing.
     */
    override fun onFrame(db: FloatArray, scale: FrequencyScale) {
        snapshot?.publish(db)
        waterfall.submitFrame(db, dbFloor)
    }

    override fun onError(message: String) {
        runOnUiThread { showErrorPanel(message) }
    }

    private fun bindViews(snap: SpectrumSnapshot, scale: FrequencyScale) {
        binding.spectrum.bind(snap, scale)
        binding.frequencyAxis.bind(scale)
    }

    // --- panels --------------------------------------------------------------

    private fun hidePanel() {
        binding.panel.visibility = View.GONE
    }

    private fun showPermissionPanel() {
        // ActivityCompat, not the Activity method: the platform one is API 23+
        // and would throw NoSuchMethodError on the API 21 test device.
        val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.RECORD_AUDIO,
        )
        binding.panelTitle.setText(R.string.permission_title)
        binding.panelBody.setText(R.string.permission_body)
        binding.panelAction.setText(
            if (permanentlyDenied) R.string.permission_settings else R.string.permission_grant
        )
        binding.panelAction.setOnClickListener {
            if (permanentlyDenied) {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    )
                )
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        binding.panel.visibility = View.VISIBLE
    }

    private fun showErrorPanel(message: String) {
        engine?.stop()
        engine = null
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        binding.panelTitle.setText(R.string.error_title)
        binding.panelBody.text = message
        binding.panelAction.setText(R.string.error_retry)
        binding.panelAction.setOnClickListener { startCapture() }
        binding.panel.visibility = View.VISIBLE
    }

    private companion object {
        const val KEY_FFT_SIZE = "fftSize"
        const val KEY_WINDOW = "window"
        const val KEY_DB_FLOOR = "dbFloor"
    }
}
