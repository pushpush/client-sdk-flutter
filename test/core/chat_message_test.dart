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

@Timeout(Duration(seconds: 5))
library;

import 'dart:io';

import 'package:fixnum/fixnum.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:livekit_client/livekit_client.dart';
import 'package:livekit_client/src/internal/events.dart';
import 'package:livekit_client/src/proto/livekit_models.pb.dart' as lk_models;
import '../mock/e2e_container.dart';
import '../mock/test_data.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late E2EContainer container;
  late Room room;

  setUp(() async {
    container = E2EContainer();
    room = container.room;
    await container.connectRoom(captureOutbound: true);
  });

  tearDown(() async {
    await container.dispose();
  });

  test('maps an inbound chat packet onto ChatMessageEvent', () async {
    await container.simulateRemoteParticipantJoin(remoteParticipantData.identity);

    final eventFuture = room.events.waitFor<ChatMessageEvent>(
      duration: const Duration(seconds: 1),
    );

    container.deliverInboundDataPacket(
      lk_models.DataPacket(
        kind: lk_models.DataPacket_Kind.RELIABLE,
        participantIdentity: remoteParticipantData.identity,
        chatMessage: lk_models.ChatMessage(
          id: 'msg-1',
          message: 'hello',
          timestamp: Int64(1700000000),
          editTimestamp: Int64(1700000001),
        ),
      ),
    );

    final event = await eventFuture;
    expect(event.participant?.identity, remoteParticipantData.identity);
    expect(event.chatMessage.id, 'msg-1');
    expect(event.chatMessage.message, 'hello');
    expect(event.chatMessage.timestamp, 1700000000);
    expect(event.chatMessage.editTimestamp, 1700000001);
  });

  test('does not throw when the chat sender identity is the local participant', () async {
    final eventFuture = room.events.waitFor<ChatMessageEvent>(
      duration: const Duration(seconds: 1),
    );

    room.engine.events.emit(
      EngineChatMessageEvent(
        identity: room.localParticipant!.identity,
        chatMessage: lk_models.ChatMessage(
          id: 'msg-local',
          message: 'from local',
          timestamp: Int64(1),
        ),
      ),
    );

    final event = await eventFuture;
    expect(event.participant, isNull);
    expect(event.chatMessage.id, 'msg-local');
    expect(event.chatMessage.message, 'from local');
  });

  test('sendChatMessage publishes a reliable chat packet', () async {
    await room.localParticipant!.sendChatMessage(
      ChatMessage(
        id: 'out-1',
        timestamp: 42,
        message: 'outbound',
        attachedFiles: [File('unused.txt')],
      ),
    );

    final packet = container.capturedDataPackets.singleWhere(
      (item) => item.whichValue() == lk_models.DataPacket_Value.chatMessage,
    );
    expect(packet.kind, lk_models.DataPacket_Kind.RELIABLE);
    expect(packet.chatMessage.id, 'out-1');
    expect(packet.chatMessage.message, 'outbound');
    expect(packet.chatMessage.timestamp.toInt(), 42);
  });
}
