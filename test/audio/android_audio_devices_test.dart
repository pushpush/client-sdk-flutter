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

import 'package:livekit_client/src/audio/audio_manager.dart';
import 'package:livekit_client/src/support/native.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
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

    test('compares snapshots by value so unchanged updates can be filtered', () {
      Map<String, dynamic> snapshot({required String selected}) => {
        'available': [
          {'kind': 'bluetooth', 'name': 'Headset'},
          {'kind': 'speaker', 'name': 'Speakerphone'},
        ],
        'selected': {'kind': selected, 'name': 'Headset'},
        'userSelected': 'bluetooth',
      };

      final devices = AndroidAudioDevices.fromMap(snapshot(selected: 'bluetooth'));

      expect(devices, AndroidAudioDevices.fromMap(snapshot(selected: 'bluetooth')));
      expect(devices.hashCode, AndroidAudioDevices.fromMap(snapshot(selected: 'bluetooth')).hashCode);
      expect(devices, isNot(AndroidAudioDevices.fromMap(snapshot(selected: 'speaker'))));
      expect(devices, isNot(const AndroidAudioDevices.empty()));
    });
  });

  test('AndroidAudioDeviceKind maps known wire values', () {
    for (final kind in AndroidAudioDeviceKind.values) {
      expect(AndroidAudioDeviceKind.fromWire(kind.wire), kind);
    }
    expect(AndroidAudioDeviceKind.fromWire('unknown'), isNull);
    expect(AndroidAudioDeviceKind.fromWire(null), isNull);
  });

  group('Android audio device channel', () {
    late List<MethodCall> calls;

    setUp(() {
      calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        Native.channel,
        (call) async {
          calls.add(call);
          if (call.method == 'getAndroidAudioDevices') {
            return <Object?, Object?>{
              'available': [
                <Object?, Object?>{'kind': 'earpiece', 'name': 'Earpiece'},
              ],
              'selected': <Object?, Object?>{'kind': 'earpiece', 'name': 'Earpiece'},
              'userSelected': 'earpiece',
            };
          }
          return null;
        },
      );
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        Native.channel,
        null,
      );
    });

    test('activates the Android audio session through the platform method', () async {
      await Native.activateAndroidAudioSession();

      expect(calls.single.method, 'activateAndroidAudioSession');
      expect(calls.single.arguments, isNull);
    });

    test('passes the requested output device kind to the platform method', () async {
      await Native.selectAndroidAudioOutput(AndroidAudioDeviceKind.bluetooth.wire);

      expect(calls.single.method, 'selectAndroidAudioDevice');
      expect(calls.single.arguments, {'kind': 'bluetooth'});
    });

    test('reads the device snapshot from the platform method', () async {
      final devices = AndroidAudioDevices.fromMap(await Native.getAndroidAudioDevices());

      expect(calls.single.method, 'getAndroidAudioDevices');
      expect(devices.available, [const AndroidAudioDevice(kind: AndroidAudioDeviceKind.earpiece, name: 'Earpiece')]);
      expect(devices.selected?.kind, AndroidAudioDeviceKind.earpiece);
      expect(devices.userSelected, AndroidAudioDeviceKind.earpiece);
    });

    test('returns an empty snapshot when the platform call fails', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        Native.channel,
        (call) async => throw PlatformException(code: 'nativeFailure'),
      );

      expect(await Native.getAndroidAudioDevices(), isEmpty);
    });

    test('Android-only APIs are no-ops on other platforms', () async {
      await AudioManager.instance.activateAndroidAudioSession();
      await AudioManager.instance.deactivateAndroidAudioSession();
      await AudioManager.instance.selectAndroidAudioOutput(AndroidAudioDeviceKind.speaker);

      expect(await AudioManager.instance.getAndroidAudioDevices(), const AndroidAudioDevices.empty());
      expect(await AudioManager.instance.androidAudioDevicesStream.toList(), isEmpty);
      expect(calls, isEmpty);
    });
  });
}
