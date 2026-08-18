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

import com.twilio.audioswitch.AbstractAudioSwitch
import com.twilio.audioswitch.AudioDevice
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class LKAudioSwitchManagerTest {
  @Test
  fun `sticky selection does nothing when target is already selected`() {
    val audioSwitch = mock(AbstractAudioSwitch::class.java)
    val bluetooth = mock(AudioDevice.BluetoothHeadset::class.java)
    `when`(audioSwitch.selectedAudioDevice).thenReturn(bluetooth)
    `when`(audioSwitch.availableAudioDevices).thenReturn(listOf(bluetooth))

    val changed = applyStickyAudioDeviceSelection(audioSwitch, "bluetooth")

    assertFalse(changed)
    verify(audioSwitch, never()).selectDevice(any())
  }

  @Test
  fun `sticky selection waits while target kind is unavailable`() {
    val audioSwitch = mock(AbstractAudioSwitch::class.java)
    val speaker = mock(AudioDevice.Speakerphone::class.java)
    `when`(audioSwitch.selectedAudioDevice).thenReturn(speaker)
    `when`(audioSwitch.availableAudioDevices).thenReturn(listOf(speaker))

    val changed = applyStickyAudioDeviceSelection(audioSwitch, "bluetooth")

    assertFalse(changed)
    verify(audioSwitch, never()).selectDevice(any())
  }

  @Test
  fun `sticky selection routes to an available target`() {
    val audioSwitch = mock(AbstractAudioSwitch::class.java)
    val earpiece = mock(AudioDevice.Earpiece::class.java)
    val bluetooth = mock(AudioDevice.BluetoothHeadset::class.java)
    `when`(audioSwitch.selectedAudioDevice).thenReturn(earpiece)
    `when`(audioSwitch.availableAudioDevices).thenReturn(listOf(bluetooth, earpiece))

    val changed = applyStickyAudioDeviceSelection(audioSwitch, "bluetooth")

    assertTrue(changed)
    verify(audioSwitch).selectDevice(bluetooth)
  }

  @Test
  fun `sticky selection is reapplied only when available kinds change`() {
    val bluetoothKinds = listOf<Class<out AudioDevice>>(
      AudioDevice.BluetoothHeadset::class.java,
      AudioDevice.Speakerphone::class.java,
    )
    val wiredKinds = listOf<Class<out AudioDevice>>(
      AudioDevice.WiredHeadset::class.java,
      AudioDevice.Speakerphone::class.java,
    )

    assertFalse(
      shouldReapplyStickyAudioDeviceSelection(
        bluetoothKinds,
        bluetoothKinds,
        "bluetooth",
      ),
    )
    assertFalse(
      shouldReapplyStickyAudioDeviceSelection(
        bluetoothKinds,
        wiredKinds,
        null,
      ),
    )
    assertTrue(
      shouldReapplyStickyAudioDeviceSelection(
        bluetoothKinds,
        wiredKinds,
        "bluetooth",
      ),
    )
  }

  @Test
  fun `unchanged device snapshots are deduplicated`() {
    val snapshot = mapOf<String, Any?>(
      "available" to listOf(mapOf("kind" to "speaker")),
      "selected" to mapOf("kind" to "speaker"),
      "userSelected" to "speaker",
    )

    assertTrue(shouldNotifyAudioDeviceSnapshot(null, snapshot))
    assertFalse(shouldNotifyAudioDeviceSnapshot(snapshot.toMap(), snapshot))
    assertTrue(
      shouldNotifyAudioDeviceSnapshot(
        snapshot,
        snapshot + ("userSelected" to "bluetooth"),
      ),
    )
  }

  @Test
  fun `unknown device kind is rejected before it can be latched`() {
    assertFalse(isSupportedAudioDeviceKind("unknown"))
    assertTrue(isSupportedAudioDeviceKind("bluetooth"))
  }
}
