/*
 * Copyright 2026 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.plugin

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.twilio.audioswitch.AbstractAudioSwitch
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioSwitch
import com.twilio.audioswitch.CommDeviceAudioSwitch
import com.twilio.audioswitch.LegacyAudioSwitch

/**
 * Manages the Android platform audio session (audio mode, audio focus, and
 * output routing) for the LiveKit Flutter SDK, built on top of [AudioSwitch].
 *
 * This is LiveKit's own port of the audio-handling best practices from the
 * LiveKit Android SDK (`AudioSwitchHandler`) and flutter_webrtc
 * (`AudioSwitchManager`), so the Flutter SDK can own the platform audio session
 * directly instead of delegating to flutter_webrtc's native audio management.
 *
 * [AudioSwitch] is not thread-safe, so every interaction with it runs on a
 * single dedicated [HandlerThread].
 */
internal class LKAudioSwitchManager(private val context: Context) {
  // AudioSwitch is not threadsafe, so confine all access to a single long-lived
  // thread. The AudioSwitch instance is recreated per active session, while
  // queued lifecycle work stays serialized on this thread.
  private val thread = HandlerThread("LKAudioSwitchThread").also { it.start() }
  private val handler = Handler(thread.looper)
  private val mainHandler = Handler(Looper.getMainLooper())

  private var audioSwitch: AbstractAudioSwitch? = null
  private var isActive = false

  // Configuration. Defaults mirror a communication/VoIP session and match the
  // AudioSwitchHandler defaults in the LiveKit Android SDK.
  private var manageAudioFocus = true
  private var audioMode = AudioManager.MODE_IN_COMMUNICATION
  private var focusMode = AudioManager.AUDIOFOCUS_GAIN
  private var audioStreamType = AudioManager.STREAM_VOICE_CALL
  private var audioAttributeUsageType = AudioAttributes.USAGE_VOICE_COMMUNICATION
  private var audioAttributeContentType = AudioAttributes.CONTENT_TYPE_SPEECH
  private var forceHandleAudioRouting = false

  private var speakerOutputPreferred = true
  private var speakerOutputForced = false

  // Sticky user selection, read from both the handler thread and the thread
  // AudioSwitch reports device changes on. When non-null it is applied on every
  // switch (re)creation, after a deactivate/activate cycle, and whenever the
  // available-device list changes. `null` means "no explicit selection yet,
  // follow the preferred-device list".
  @Volatile
  private var selectedDeviceKind: String? = null

  // Last state reported by AudioSwitch, read by `devicesSnapshot()` from the
  // main thread and pushed out via [deviceChangeListener]. Held as a single
  // object so a reader can never pair a new device list with a stale selection.
  @Volatile
  private var lastState = DeviceState()

  // Last map handed to [deviceChangeListener], used to drop no-op events.
  // Main thread only.
  private var lastNotifiedSnapshot: Map<String, Any?>? = null

  /**
   * Listener invoked on the main thread whenever the AudioSwitch device list
   * or the current selection changes. Set from the plugin to forward events to
   * a Flutter EventChannel. Confined to the main thread: assign and clear it
   * from there only, and expect at most one listener.
   */
  var deviceChangeListener: ((Map<String, Any?>) -> Unit)? = null

  /**
   * Apply an audio session configuration. Unspecified keys keep their current
   * value. When the session is already active, changes that only take effect at
   * activate() time trigger a deactivate and activate cycle so they apply live.
   */
  @Synchronized
  fun configure(configuration: Map<String, Any?>) {
    val previous = sessionConfigSnapshot()
    (configuration["manageAudioFocus"] as? Boolean)?.let { manageAudioFocus = it }
    audioModeForName(configuration["androidAudioMode"] as? String)?.let { audioMode = it }
    focusModeForName(configuration["androidAudioFocusMode"] as? String)?.let { focusMode = it }
    streamTypeForName(configuration["androidAudioStreamType"] as? String)?.let { audioStreamType = it }
    usageTypeForName(configuration["androidAudioAttributesUsageType"] as? String)?.let { audioAttributeUsageType = it }
    contentTypeForName(configuration["androidAudioAttributesContentType"] as? String)?.let { audioAttributeContentType = it }
    (configuration["forceHandleAudioRouting"] as? Boolean)?.let { forceHandleAudioRouting = it }
    val sessionConfig = sessionConfigSnapshot()
    val sessionConfigChanged = sessionConfig != previous
    val speakerRouting = speakerRoutingSnapshot()

    handler.post {
      val switch = audioSwitch ?: return@post
      applyConfiguration(switch, sessionConfig)
      // AudioSwitch applies the audio mode, focus, and attributes at activate()
      // time, so a live reconfiguration (e.g. communication to media) needs a
      // deactivate and activate cycle to take effect on an already active
      // session. Reassert speaker routing afterward.
      if (isActive && sessionConfigChanged) {
        switch.deactivate()
        switch.activate()
        applySpeakerRouting(switch, speakerRouting)
        // deactivate/activate resets the internal selection state on some
        // AudioSwitch backends, so re-apply the sticky user selection so a
        // live reconfigure does not silently drop the user's choice.
        applyStickySelection(switch)
      }
    }
  }

  // Snapshot of the AudioSwitch properties applied only at activate() time, used
  // to detect when a live session needs a deactivate and activate cycle to pick
  // up a configuration change.
  private fun sessionConfigSnapshot() = SessionConfig(
    manageAudioFocus = manageAudioFocus,
    audioMode = audioMode,
    focusMode = focusMode,
    audioStreamType = audioStreamType,
    audioAttributeUsageType = audioAttributeUsageType,
    audioAttributeContentType = audioAttributeContentType,
    forceHandleAudioRouting = forceHandleAudioRouting,
  )

  /** Create (if needed) and activate the audio session: acquire focus, set mode and routing. */
  @Synchronized
  fun start() {
    val sessionConfig = sessionConfigSnapshot()
    val speakerRouting = speakerRoutingSnapshot()
    handler.post {
      val switch = audioSwitch ?: createSwitch(sessionConfig, speakerRouting).also { audioSwitch = it }
      if (!isActive) {
        switch.activate()
        applySpeakerRouting(switch, speakerRouting)
        applyStickySelection(switch)
        isActive = true
      }
    }
  }

  /** Deactivate and tear down the audio session: release focus and restore the previous mode. */
  @Synchronized
  fun stop() {
    handler.post {
      val hadSwitch = audioSwitch != null
      audioSwitch?.stop()
      audioSwitch = null
      isActive = false
      lastState = DeviceState()
      // AudioSwitch drops its own device-change listener in stop(), so emit the
      // cleared snapshot here; otherwise listeners keep the last device list of
      // an already released session.
      if (hadSwitch) {
        notifyDeviceChange(buildSnapshot(lastState))
      }
    }
  }

  /** Final cleanup when the plugin detaches. The manager must not be used after this. */
  @Synchronized
  fun dispose() {
    // Cleared on the calling (main) thread rather than from the queued block:
    // the plugin owns this listener from the main thread, and no snapshot may
    // reach an engine that is already detached.
    deviceChangeListener = null
    handler.post {
      audioSwitch?.stop()
      audioSwitch = null
      isActive = false
      lastState = DeviceState()
      thread.quitSafely()
    }
  }

  /**
   * Prefer routing to/from the speaker, letting a connected headset keep priority
   * unless [force] is true.
   */
  @Synchronized
  fun setSpeakerphoneOn(enable: Boolean, force: Boolean) {
    speakerOutputPreferred = enable
    speakerOutputForced = enable && force
    val speakerRouting = speakerRoutingSnapshot()
    handler.post {
      val switch = audioSwitch ?: return@post
      applySpeakerRouting(switch, speakerRouting)
    }
  }

  /**
   * Explicitly route audio playout to the device matching [kind].
   *
   * [kind] is one of `bluetooth`, `wired`, `speaker`, `earpiece`. The
   * selection is sticky: it is re-applied after a live reconfigure
   * (deactivate/activate cycle), whenever the available-device list changes,
   * and after the underlying AudioSwitch is recreated on the next [start]. If
   * no matching device is currently available the latch is still updated so a
   * later hot-plug picks it up; `selectDevice` is not called with a null device
   * because `AudioSwitch` treats that as "select no device" rather than "fall
   * back to auto".
   */
  @Synchronized
  fun selectDevice(kind: String): Boolean {
    // Unknown kinds are not latched: applyStickySelection could never match
    // them, and the stale latch would block the preferred-device fallback.
    if (!isSupportedAudioDeviceKind(kind)) return false
    selectedDeviceKind = kind
    handler.post {
      audioSwitch?.let { applyStickySelection(it) }
      // The latch is part of the snapshot, and AudioSwitch stays silent when the
      // requested device is absent or already selected, so publish the new pin
      // here rather than waiting for the next device change.
      notifyDeviceChange(buildSnapshot(lastState))
    }
    return true
  }

  /**
   * Snapshot of the last known device list and current selection. Returned as
   * a Flutter-friendly map so it can be sent through a MethodChannel result
   * or an EventChannel event.
   */
  fun devicesSnapshot(): Map<String, Any?> = buildSnapshot(lastState)

  private fun createSwitch(
    sessionConfig: SessionConfig,
    speakerRouting: SpeakerRouting,
  ): AbstractAudioSwitch {
    val focusListener = AudioManager.OnAudioFocusChangeListener { }
    // API-aware switch selection, matching the LiveKit Android SDK's
    // AudioSwitchHandler: CommDeviceAudioSwitch uses the modern
    // AudioManager.setCommunicationDevice routing on API 31+.
    val switch = when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        CommDeviceAudioSwitch(context, false, focusListener, speakerRouting.preferredDeviceList)

      Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
        AudioSwitch(context, false, focusListener, speakerRouting.preferredDeviceList)

      else ->
        LegacyAudioSwitch(context, false, focusListener, speakerRouting.preferredDeviceList)
    }
    applyConfiguration(switch, sessionConfig)
    switch.start { devices, selected -> onDeviceChange(devices, selected) }
    applyStickySelection(switch)
    return switch
  }

  private fun onDeviceChange(devices: List<AudioDevice>, selected: AudioDevice?) {
    val previous = lastState
    val state = DeviceState(devices, selected)
    lastState = state
    // A changed device list means a hot-plug, and AudioSwitch forgets its own
    // user selection when the selected device disconnects, so re-apply the latch
    // to survive unplug/replug. Only a changed list triggers this: when the list
    // is unchanged and AudioSwitch moved off the pinned device, it did so
    // because the device is not usable right now while keeping its own
    // selection, and forcing the route back would both bypass that check and
    // ping-pong with the scanner through this very callback.
    if (shouldReapplyStickyAudioDeviceSelection(previous.kinds, state.kinds, selectedDeviceKind)) {
      handler.post {
        audioSwitch?.let { applyStickySelection(it) }
      }
    }
    notifyDeviceChange(buildSnapshot(state))
  }

  private fun notifyDeviceChange(snapshot: Map<String, Any?>) {
    // Deliver on the main thread so the Flutter EventChannel receives events
    // on the platform message loop.
    mainHandler.post {
      if (!shouldNotifyAudioDeviceSnapshot(lastNotifiedSnapshot, snapshot)) return@post
      lastNotifiedSnapshot = snapshot
      deviceChangeListener?.invoke(snapshot)
    }
  }

  private fun applyStickySelection(switch: AbstractAudioSwitch) {
    applyStickyAudioDeviceSelection(switch, selectedDeviceKind)
  }

  private fun buildSnapshot(state: DeviceState): Map<String, Any?> = mapOf(
    "available" to state.devices.map { deviceMap(it) },
    "selected" to state.selected?.let { deviceMap(it) },
    "userSelected" to selectedDeviceKind,
  )

  private fun deviceMap(device: AudioDevice): Map<String, Any?> = mapOf(
    "kind" to kindForDevice(device),
    "name" to device.name,
  )

  private fun kindForDevice(device: AudioDevice): String = when (device) {
    is AudioDevice.BluetoothHeadset -> "bluetooth"
    is AudioDevice.WiredHeadset -> "wired"
    is AudioDevice.Speakerphone -> "speaker"
    is AudioDevice.Earpiece -> "earpiece"
  }

  private fun applyConfiguration(switch: AbstractAudioSwitch, sessionConfig: SessionConfig) {
    switch.manageAudioFocus = sessionConfig.manageAudioFocus
    switch.audioMode = sessionConfig.audioMode
    switch.focusMode = sessionConfig.focusMode
    switch.audioStreamType = sessionConfig.audioStreamType
    switch.audioAttributeUsageType = sessionConfig.audioAttributeUsageType
    switch.audioAttributeContentType = sessionConfig.audioAttributeContentType
    switch.forceHandleAudioRouting = sessionConfig.forceHandleAudioRouting
  }

  private fun applySpeakerRouting(switch: AbstractAudioSwitch, speakerRouting: SpeakerRouting) {
    // AudioSwitch treats selectDevice(null) as "select no device"; it does not
    // recompute the best route from the preferred-device list. Keep routing
    // automatic here so normal preference and forced-speaker priority both
    // follow device hot-plug changes without leaving a sticky selected device.
    switch.setPreferredDeviceList(speakerRouting.preferredDeviceList)
  }

  private fun speakerRoutingSnapshot() = SpeakerRouting(
    preferredDeviceList = preferredDeviceList(
      speakerOutputPreferred = speakerOutputPreferred,
      speakerOutputForced = speakerOutputForced,
    ),
  )

  private fun preferredDeviceList(
    speakerOutputPreferred: Boolean,
    speakerOutputForced: Boolean,
  ): List<Class<out AudioDevice>> =
    when {
      speakerOutputForced -> listOf(
        AudioDevice.Speakerphone::class.java,
        AudioDevice.BluetoothHeadset::class.java,
        AudioDevice.WiredHeadset::class.java,
        AudioDevice.Earpiece::class.java,
      )

      speakerOutputPreferred -> listOf(
        AudioDevice.BluetoothHeadset::class.java,
        AudioDevice.WiredHeadset::class.java,
        AudioDevice.Speakerphone::class.java,
        AudioDevice.Earpiece::class.java,
      )

      else -> listOf(
        AudioDevice.BluetoothHeadset::class.java,
        AudioDevice.WiredHeadset::class.java,
        AudioDevice.Earpiece::class.java,
        AudioDevice.Speakerphone::class.java,
      )
    }

  private data class SessionConfig(
    val manageAudioFocus: Boolean,
    val audioMode: Int,
    val focusMode: Int,
    val audioStreamType: Int,
    val audioAttributeUsageType: Int,
    val audioAttributeContentType: Int,
    val forceHandleAudioRouting: Boolean,
  )

  private data class SpeakerRouting(
    val preferredDeviceList: List<Class<out AudioDevice>>,
  )

  private data class DeviceState(
    val devices: List<AudioDevice> = emptyList(),
    val selected: AudioDevice? = null,
  ) {
    val kinds: List<Class<out AudioDevice>> get() = devices.map { it.javaClass }
  }
}

internal fun isSupportedAudioDeviceKind(kind: String): Boolean =
  deviceClassForKind(kind) != null

internal fun applyStickyAudioDeviceSelection(
  audioSwitch: AbstractAudioSwitch,
  kind: String?,
): Boolean {
  val deviceClass = kind?.let(::deviceClassForKind) ?: return false
  if (audioSwitch.selectedAudioDevice?.javaClass == deviceClass) return false
  // Only call selectDevice when we have a matching physical device.
  // AudioSwitch treats selectDevice(null) as "select no device", not "auto".
  val target =
    audioSwitch.availableAudioDevices.firstOrNull { it.javaClass == deviceClass }
      ?: return false
  audioSwitch.selectDevice(target)
  return true
}

internal fun shouldReapplyStickyAudioDeviceSelection(
  previousKinds: List<Class<out AudioDevice>>,
  currentKinds: List<Class<out AudioDevice>>,
  selectedKind: String?,
): Boolean = selectedKind != null && currentKinds != previousKinds

internal fun shouldNotifyAudioDeviceSnapshot(
  previous: Map<String, Any?>?,
  current: Map<String, Any?>,
): Boolean = previous != current

internal fun deviceClassForKind(kind: String): Class<out AudioDevice>? = when (kind) {
  "bluetooth" -> AudioDevice.BluetoothHeadset::class.java
  "wired" -> AudioDevice.WiredHeadset::class.java
  "speaker" -> AudioDevice.Speakerphone::class.java
  "earpiece" -> AudioDevice.Earpiece::class.java
  else -> null
}

// Map the Flutter-side enum names (see android_audio_session_adapter.dart) to
// Android framework constants. Ported from flutter_webrtc's AudioUtils.

private fun audioModeForName(name: String?): Int? = when (name) {
  null -> null
  "normal" -> AudioManager.MODE_NORMAL
  "callScreening" -> AudioManager.MODE_CALL_SCREENING
  "inCall" -> AudioManager.MODE_IN_CALL
  "inCommunication" -> AudioManager.MODE_IN_COMMUNICATION
  "ringtone" -> AudioManager.MODE_RINGTONE
  else -> null
}

private fun focusModeForName(name: String?): Int? = when (name) {
  null -> null
  "gain" -> AudioManager.AUDIOFOCUS_GAIN
  "gainTransient" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
  "gainTransientExclusive" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
  "gainTransientMayDuck" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
  else -> null
}

private fun streamTypeForName(name: String?): Int? = when (name) {
  null -> null
  "accessibility" -> AudioManager.STREAM_ACCESSIBILITY
  "alarm" -> AudioManager.STREAM_ALARM
  "dtmf" -> AudioManager.STREAM_DTMF
  "music" -> AudioManager.STREAM_MUSIC
  "notification" -> AudioManager.STREAM_NOTIFICATION
  "ring" -> AudioManager.STREAM_RING
  "system" -> AudioManager.STREAM_SYSTEM
  "voiceCall" -> AudioManager.STREAM_VOICE_CALL
  else -> null
}

private fun usageTypeForName(name: String?): Int? = when (name) {
  null -> null
  "alarm" -> AudioAttributes.USAGE_ALARM
  "assistanceAccessibility" -> AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
  "assistanceNavigationGuidance" -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
  "assistanceSonification" -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
  "assistant" -> AudioAttributes.USAGE_ASSISTANT
  "game" -> AudioAttributes.USAGE_GAME
  "media" -> AudioAttributes.USAGE_MEDIA
  "notification" -> AudioAttributes.USAGE_NOTIFICATION
  "notificationEvent" -> AudioAttributes.USAGE_NOTIFICATION_EVENT
  "notificationRingtone" -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
  "unknown" -> AudioAttributes.USAGE_UNKNOWN
  "voiceCommunication" -> AudioAttributes.USAGE_VOICE_COMMUNICATION
  "voiceCommunicationSignalling" -> AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
  else -> null
}

private fun contentTypeForName(name: String?): Int? = when (name) {
  null -> null
  "movie" -> AudioAttributes.CONTENT_TYPE_MOVIE
  "music" -> AudioAttributes.CONTENT_TYPE_MUSIC
  "sonification" -> AudioAttributes.CONTENT_TYPE_SONIFICATION
  "speech" -> AudioAttributes.CONTENT_TYPE_SPEECH
  "unknown" -> AudioAttributes.CONTENT_TYPE_UNKNOWN
  else -> null
}
