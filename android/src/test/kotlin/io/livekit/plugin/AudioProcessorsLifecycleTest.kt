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

import io.flutter.plugin.common.BinaryMessenger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

class AudioProcessorsLifecycleTest {
  @Test
  fun `disposed cached track is replaced`() {
    val staleTrack = mock(LKAudioTrack::class.java)
    doThrow(IllegalStateException("disposed")).`when`(staleTrack).id()
    val staleProcessors = AudioProcessors(staleTrack)
    val cache = mutableMapOf("track-id" to staleProcessors)
    val replacementTrack = mock(LKAudioTrack::class.java)

    val result = getOrCreateAudioProcessors(cache, "track-id") { replacementTrack }

    assertNotSame(staleProcessors, result)
    assertSame(replacementTrack, result?.track)
    assertSame(result, cache["track-id"])
  }

  @Test
  fun `cleanup continues after a renderer fails`() {
    val processors = AudioProcessors(mock(LKAudioTrack::class.java))
    val failingRenderer = mock(AudioRenderer::class.java)
    val remainingRenderer = mock(AudioRenderer::class.java)
    val visualizer = mock(Visualizer::class.java)
    doThrow(IllegalStateException("disposed")).`when`(failingRenderer).detach()
    processors.renderers["failing"] = failingRenderer
    processors.renderers["remaining"] = remainingRenderer
    processors.visualizers["visualizer"] = visualizer

    processors.cleanup()

    verify(failingRenderer).detach()
    verify(remainingRenderer).detach()
    verify(visualizer).stop()
    assertTrue(processors.renderers.isEmpty())
    assertTrue(processors.visualizers.isEmpty())
  }

  @Test
  fun `renderer detach remains idempotent when track is disposed`() {
    val track = mock(LKAudioTrack::class.java)
    val renderer = AudioRenderer(
      track,
      mock(BinaryMessenger::class.java),
      "renderer-id",
      RendererAudioFormat(bitsPerSample = 16, sampleRate = 48000, numberOfChannels = 1),
    )
    doThrow(IllegalStateException("disposed")).`when`(track).removeSink(renderer)

    renderer.detach()
    renderer.detach()

    verify(track, times(1)).removeSink(renderer)
  }

  @Test
  fun `visualizer is removed by id after track id changes`() {
    val processors = AudioProcessors(mock(LKAudioTrack::class.java))
    val visualizer = mock(Visualizer::class.java)
    processors.visualizers["visualizer-id"] = visualizer
    val cache = mutableMapOf("old-track-id" to processors)

    val removed = removeVisualizer(cache, "new-track-id", "visualizer-id")

    assertTrue(removed)
    verify(visualizer).stop()
    assertTrue(cache.isEmpty())
  }

  @Test
  fun `missing visualizer is a no-op`() {
    val processors = AudioProcessors(mock(LKAudioTrack::class.java))
    val cache = mutableMapOf("track-id" to processors)

    val removed = removeVisualizer(cache, "track-id", "missing")

    assertFalse(removed)
    assertSame(processors, cache["track-id"])
  }
}
