// Copyright 2026 LiveKit, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import 'package:flutter/services.dart';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart' as rtc;

import 'package:livekit_client/src/support/native.dart';
import 'package:livekit_client/src/track/audio_visualizer.dart';
import 'package:livekit_client/src/track/audio_visualizer_native.dart';
import 'package:livekit_client/src/track/local/local.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('does not subscribe when native visualizer startup fails', () async {
    final nativeCalls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(Native.channel, (
      call,
    ) async {
      nativeCalls.add(call);
      return call.method == 'startVisualizer' ? false : null;
    });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        Native.channel,
        null,
      ),
    );

    final visualizer = AudioVisualizerNative(
      _FakeAudioTrack(),
      visualizerOptions: const AudioVisualizerOptions(),
    );
    addTearDown(visualizer.dispose);

    final eventCalls = <MethodCall>[];
    final eventChannel = MethodChannel(
      'io.livekit.audio.visualizer/eventChannel-audio-track-${visualizer.visualizerId}',
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(eventChannel, (
      call,
    ) async {
      eventCalls.add(call);
      return null;
    });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        eventChannel,
        null,
      ),
    );

    await visualizer.start();
    await visualizer.stop();

    expect(nativeCalls.map((call) => call.method), ['startVisualizer', 'stopVisualizer']);
    expect(eventCalls, isEmpty);
  });

  test('subscribes when native visualizer startup succeeds', () async {
    final nativeCalls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(Native.channel, (
      call,
    ) async {
      nativeCalls.add(call);
      return call.method == 'startVisualizer' ? true : null;
    });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        Native.channel,
        null,
      ),
    );

    final visualizer = AudioVisualizerNative(
      _FakeAudioTrack(),
      visualizerOptions: const AudioVisualizerOptions(),
    );
    addTearDown(visualizer.dispose);

    final eventCalls = <MethodCall>[];
    final eventChannel = MethodChannel(
      'io.livekit.audio.visualizer/eventChannel-audio-track-${visualizer.visualizerId}',
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(eventChannel, (
      call,
    ) async {
      eventCalls.add(call);
      return null;
    });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        eventChannel,
        null,
      ),
    );

    await visualizer.start();

    expect(nativeCalls.map((call) => call.method), ['startVisualizer']);
    expect(eventCalls.map((call) => call.method), ['listen']);

    await visualizer.stop();

    expect(nativeCalls.map((call) => call.method), ['startVisualizer', 'stopVisualizer']);
    expect(eventCalls.map((call) => call.method), ['listen', 'cancel']);
  });
}

class _FakeAudioTrack extends Fake implements AudioTrack {
  @override
  rtc.MediaStreamTrack get mediaStreamTrack => _FakeMediaStreamTrack();
}

class _FakeMediaStreamTrack extends Fake implements rtc.MediaStreamTrack {
  @override
  String? get id => 'audio-track';
}
