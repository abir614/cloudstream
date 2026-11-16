package com.lagradost.cloudstream3.utils.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Detailed representation of a supported display mode (resolution & refresh rate).
 */
data class DisplayModeInfo(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float
)

/**
 * High-precision physical monitor descriptor.
 */
data class MonitorMetrics(
    val displayId: Int,
    val name: String,
    val isDefault: Boolean,
    val isPresentation: Boolean,
    val isValid: Boolean,
    val state: Int,
    val physicalWidth: Int,
    val physicalHeight: Int,
    val currentRefreshRate: Float,
    val maxRefreshRate: Float,
    val supportedModes: List<DisplayModeInfo>,
    val widthInches: Float?,
    val heightInches: Float?,
    val diagonalInches: Float?,
    val aspectRatio: Float,
    val is16by9: Boolean,
    val is16by10: Boolean,
    val isUltraWide: Boolean,
    val is4KOrHigher: Boolean,
    val isHdrSupported: Boolean,
    val supportedHdrTypes: List<Int>,
    val isWideColorGamut: Boolean
)

/**
 * Comprehensive snapshot of system display topology and hardware connection state.
 */
data class ExternalDisplayState(
    val isConnected: Boolean = false,
    val isHdmiHardwarePlugged: Boolean = false,
    val isHdmiAudioRouted: Boolean = false,
    val externalDisplayCount: Int = 0,
    val primaryDisplayId: Int = Display.DEFAULT_DISPLAY,
    val secondaryDisplayId: Int? = null,
    val activeMonitor: MonitorMetrics? = null,
    val displays: List<MonitorMetrics> = emptyList()
)

/**
 * Robust Hardware & OS Display Topology Manager.
 *
 * Implements real-time detection of external monitors, HDMI hotplug events,
 * secondary presentation displays, physical resolution/aspect-ratio inspection,
 * and display configuration changes across Phones, Foldables, Tablets, Android TV,
 * and Desktop mode (Samsung DeX, etc.).
 */
object DisplayTopologyManager {

    private const val TAG = "DisplayTopologyManager"
    private const val ACTION_HDMI_PLUGGED = "android.intent.action.HDMI_PLUGGED"

    private var appContext: Context? = null
    private var displayManager: DisplayManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _displayState = MutableStateFlow(ExternalDisplayState())
    val displayState: StateFlow<ExternalDisplayState> = _displayState.asStateFlow()

    private val listeners = CopyOnWriteArrayList<(ExternalDisplayState) -> Unit>()

    private var isHdmiPluggedExtra: Boolean = false
    private var isReceiverRegistered: Boolean = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            refreshTopology("onDisplayAdded(id=$displayId)")
        }

        override fun onDisplayChanged(displayId: Int) {
            refreshTopology("onDisplayChanged(id=$displayId)")
        }

        override fun onDisplayRemoved(displayId: Int) {
            refreshTopology("onDisplayRemoved(id=$displayId)")
        }
    }

    /**
     * Polymorphic evaluation of the "state" extra from [ACTION_HDMI_PLUGGED].
     * Certain OEMs (e.g. MediaTek, Infinix/Transsion) put a Boolean extra instead of an Integer,
     * which causes a ClassCastException logcat warning when calling [Intent.getIntExtra].
     */
    fun extractHdmiPluggedValue(extra: Any?): Boolean {
        return when (extra) {
            is Boolean -> extra
            is Number -> extra.toInt() == 1
            is String -> extra.equals("true", ignoreCase = true) || extra == "1"
            else -> false
        }
    }

    fun extractHdmiPluggedState(intent: Intent?): Boolean {
        if (intent == null) return false
        val extra = try {
            intent.extras?.get("state")
        } catch (_: Throwable) {
            null
        }
        return extractHdmiPluggedValue(extra)
    }

    private val hdmiBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_HDMI_PLUGGED) {
                // state extra: 1/true = plugged, 0/false = unplugged
                val plugged = extractHdmiPluggedState(intent)
                if (isHdmiPluggedExtra != plugged) {
                    isHdmiPluggedExtra = plugged
                    refreshTopology("ACTION_HDMI_PLUGGED(plugged=$plugged)")
                }
            }
        }
    }

    /**
     * Initializes the manager with Application Context. Safe to call multiple times.
     */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val application = context.applicationContext ?: context
        appContext = application

        try {
            val dm = application.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            displayManager = dm
            dm?.registerDisplayListener(displayListener, mainHandler)
        } catch (t: Throwable) {
            logError(t)
        }

        try {
            val filter = IntentFilter(ACTION_HDMI_PLUGGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val stickyIntent = application.registerReceiver(
                    hdmiBroadcastReceiver,
                    filter,
                    Context.RECEIVER_EXPORTED
                )
                stickyIntent?.let {
                    isHdmiPluggedExtra = extractHdmiPluggedState(it)
                }
            } else {
                val stickyIntent = application.registerReceiver(hdmiBroadcastReceiver, filter)
                stickyIntent?.let {
                    isHdmiPluggedExtra = extractHdmiPluggedState(it)
                }
            }
            isReceiverRegistered = true
        } catch (t: Throwable) {
            logError(t)
        }

        // Initial snapshot
        refreshTopology("init")
    }

    /**
     * Registers a listener for display topology changes. Immediately emits current state.
     */
    fun addListener(listener: (ExternalDisplayState) -> Unit) {
        listeners.addIfAbsent(listener)
        listener(_displayState.value)
    }

    /**
     * Removes an active listener.
     */
    fun removeListener(listener: (ExternalDisplayState) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Evaluates current system display state, queries displays, and notifies subscribers.
     */
    @Synchronized
    fun refreshTopology(reason: String = "manual") {
        val context = appContext ?: return
        val dm = displayManager ?: (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
        val allDisplays = try {
            dm?.displays ?: emptyArray()
        } catch (_: Throwable) {
            emptyArray()
        }

        val monitors = mutableListOf<MonitorMetrics>()
        var foundSecondaryId: Int? = null

        val isTvDevice = try {
            val uiMode = context.resources.configuration.uiMode
            (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        } catch (_: Throwable) {
            false
        }

        for (display in allDisplays) {
            val metrics = inspectDisplay(display)
            monitors.add(metrics)

            if (!metrics.isDefault && metrics.isValid) {
                if (foundSecondaryId == null || metrics.isPresentation) {
                    foundSecondaryId = metrics.displayId
                }
            }
        }

        val isHdmiAudio = probeHdmiAudio(context)
        val hasSecondaryDisplay = (foundSecondaryId != null)
        val isConnected = hasSecondaryDisplay || isHdmiPluggedExtra || isHdmiAudio || isTvDevice

        val activeMonitor = when {
            foundSecondaryId != null -> monitors.firstOrNull { it.displayId == foundSecondaryId }
            else -> monitors.firstOrNull { it.isDefault } ?: monitors.firstOrNull()
        }

        val newState = ExternalDisplayState(
            isConnected = isConnected,
            isHdmiHardwarePlugged = isHdmiPluggedExtra,
            isHdmiAudioRouted = isHdmiAudio,
            externalDisplayCount = monitors.count { !it.isDefault && it.isValid },
            primaryDisplayId = Display.DEFAULT_DISPLAY,
            secondaryDisplayId = foundSecondaryId,
            activeMonitor = activeMonitor,
            displays = monitors
        )

        _displayState.value = newState

        if (Looper.myLooper() == Looper.getMainLooper()) {
            notifyListeners(newState)
        } else {
            mainHandler.post { notifyListeners(newState) }
        }
    }

    /**
     * Inspects a Display instance with high precision to extract hardware resolution,
     * physical dimensions in inches, aspect ratios, refresh rates, and HDR/gamut capabilities.
     */
    fun inspectDisplay(display: Display): MonitorMetrics {
        val isDefault = (display.displayId == Display.DEFAULT_DISPLAY)
        val flags = display.flags
        val isPresentation = (flags and Display.FLAG_PRESENTATION) != 0

        val (physWidth, physHeight, currentHz, supportedModes) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val currentMode = display.mode
                val modes = display.supportedModes?.map { m ->
                    DisplayModeInfo(
                        modeId = m.modeId,
                        width = m.physicalWidth,
                        height = m.physicalHeight,
                        refreshRate = m.refreshRate
                    )
                } ?: emptyList()
                val w = currentMode?.physicalWidth ?: 1920
                val h = currentMode?.physicalHeight ?: 1080
                val hz = currentMode?.refreshRate ?: 60.0f
                Quadruple(w, h, hz, modes)
            } catch (_: Throwable) {
                Quadruple(1920, 1080, 60.0f, emptyList())
            }
        } else {
            @Suppress("DEPRECATION")
            val w = display.width
            @Suppress("DEPRECATION")
            val h = display.height
            @Suppress("DEPRECATION")
            val hz = display.refreshRate
            Quadruple(w, h, hz, emptyList())
        }

        val maxHz = supportedModes.maxOfOrNull { it.refreshRate } ?: currentHz

        // Physical screen dimensions via DisplayMetrics xdpi/ydpi
        val dm = DisplayMetrics()
        try {
            @Suppress("DEPRECATION")
            display.getRealMetrics(dm)
        } catch (_: Throwable) {
            try {
                @Suppress("DEPRECATION")
                display.getMetrics(dm)
            } catch (_: Throwable) {}
        }

        val widthInches: Float?
        val heightInches: Float?
        val diagonalInches: Float?
        if (dm.xdpi > 10f && dm.ydpi > 10f && physWidth > 0 && physHeight > 0) {
            val wIn = physWidth / dm.xdpi
            val hIn = physHeight / dm.ydpi
            widthInches = wIn
            heightInches = hIn
            diagonalInches = hypot(wIn.toDouble(), hIn.toDouble()).toFloat()
        } else {
            widthInches = null
            heightInches = null
            diagonalInches = null
        }

        val rawRatio = if (physHeight > 0) physWidth.toFloat() / physHeight.toFloat() else (16f / 9f)
        val is16by9 = abs(rawRatio - (16f / 9f)) < 0.05f
        val is16by10 = abs(rawRatio - (16f / 10f)) < 0.05f
        val isUltraWide = rawRatio >= 2.1f
        val is4KOrHigher = (physWidth >= 3840 || physHeight >= 2160)

        val (isHdr, hdrTypes) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val caps = display.hdrCapabilities
                val types = caps?.supportedHdrTypes?.toList() ?: emptyList()
                (types.isNotEmpty()) to types
            } catch (_: Throwable) {
                false to emptyList()
            }
        } else {
            false to emptyList()
        }

        val isWideGamut = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                display.isWideColorGamut
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }

        return MonitorMetrics(
            displayId = display.displayId,
            name = display.name ?: "Display #${display.displayId}",
            isDefault = isDefault,
            isPresentation = isPresentation,
            isValid = display.isValid,
            state = display.state,
            physicalWidth = physWidth,
            physicalHeight = physHeight,
            currentRefreshRate = currentHz,
            maxRefreshRate = maxHz,
            supportedModes = supportedModes,
            widthInches = widthInches,
            heightInches = heightInches,
            diagonalInches = diagonalInches,
            aspectRatio = rawRatio,
            is16by9 = is16by9,
            is16by10 = is16by10,
            isUltraWide = isUltraWide,
            is4KOrHigher = is4KOrHigher,
            isHdrSupported = isHdr,
            supportedHdrTypes = hdrTypes,
            isWideColorGamut = isWideGamut
        )
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun notifyListeners(state: ExternalDisplayState) {
        for (listener in listeners) {
            try {
                listener(state)
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }

    /**
     * Probes audio outputs for active HDMI or HDMI-ARC routing.
     */
    fun probeHdmiAudio(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                return devices.any { device ->
                    device.type == AudioDeviceInfo.TYPE_HDMI ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && device.type == AudioDeviceInfo.TYPE_HDMI_ARC) ||
                    (Build.VERSION.SDK_INT >= 31 && device.type == AudioDeviceInfo.TYPE_HDMI_EARC)
                }
            } catch (_: Throwable) {
                return false
            }
        }
        return false
    }

    /**
     * Unregisters listeners and releases system resources.
     */
    @Synchronized
    fun destroy() {
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Throwable) {}

        if (isReceiverRegistered && appContext != null) {
            try {
                appContext?.unregisterReceiver(hdmiBroadcastReceiver)
            } catch (_: Throwable) {}
            isReceiverRegistered = false
        }

        listeners.clear()
        appContext = null
        displayManager = null
    }
}
