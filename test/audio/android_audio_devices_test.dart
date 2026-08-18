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

import 'package:flutter_test/flutter_test.dart';

import 'package:livekit_client/src/audio/audio_manager.dart';

void main() {
  group('AndroidAudioDevices', () {
    test('parses a device snapshot and preserves available-device order', () {
      final devices = AndroidAudioDevices.fromMap({
        'available': [
          {'kind': 'bluetooth', 'name': 'Headset'},
          {'kind': 'wired', 'name': 'USB headset'},
          {'kind': 'speaker', 'name': 'Speakerphone'},
        ],
        'selected': {'kind': 'wired', 'name': 'USB headset'},
        'userSelected': 'bluetooth',
      });

      expect(
        devices.available.map((device) => device.kind),
        [
          AndroidAudioDeviceKind.bluetooth,
          AndroidAudioDeviceKind.wired,
          AndroidAudioDeviceKind.speaker,
        ],
      );
      expect(devices.selected?.kind, AndroidAudioDeviceKind.wired);
      expect(devices.selected?.name, 'USB headset');
      expect(devices.userSelected, AndroidAudioDeviceKind.bluetooth);
    });

    test('drops available devices with unknown or missing kinds', () {
      final devices = AndroidAudioDevices.fromMap({
        'available': [
          {'kind': 'speaker', 'name': 'Speakerphone'},
          {'kind': 'unknown', 'name': 'Unknown'},
          {'name': 'Missing kind'},
          'not a map',
        ],
        'selected': {'kind': 'unknown', 'name': 'Unknown'},
        'userSelected': 'unknown',
      });

      expect(devices.available, hasLength(1));
      expect(devices.available.single.kind, AndroidAudioDeviceKind.speaker);
      expect(devices.selected, isNull);
      expect(devices.userSelected, isNull);
    });

    test('parses an empty snapshot with an immutable available list', () {
      final devices = AndroidAudioDevices.fromMap({});

      expect(devices.available, isEmpty);
      expect(devices.selected, isNull);
      expect(devices.userSelected, isNull);
      expect(
        () => devices.available.add(
          const AndroidAudioDevice(kind: AndroidAudioDeviceKind.earpiece),
        ),
        throwsUnsupportedError,
      );
    });
  });

  test('AndroidAudioDeviceKind maps known wire values', () {
    for (final kind in AndroidAudioDeviceKind.values) {
      expect(AndroidAudioDeviceKind.fromWire(kind.wire), kind);
    }
    expect(AndroidAudioDeviceKind.fromWire('unknown'), isNull);
    expect(AndroidAudioDeviceKind.fromWire(null), isNull);
  });
}
