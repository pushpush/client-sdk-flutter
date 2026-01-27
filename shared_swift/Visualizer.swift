/*
 * Copyright 2025 LiveKit
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

import AVFoundation
import WebRTC

#if os(macOS)
import Cocoa
import FlutterMacOS
#else
import Flutter
import UIKit
#endif

public class Visualizer: NSObject, RTCAudioRenderer, FlutterStreamHandler {
    private var eventSink: FlutterEventSink?

    private var channel: FlutterEventChannel?
    
    // Flag to prevent race conditions when stopping
    private let isStopped = NSLock()
    private var _stopped: Bool = false
    private var stopped: Bool {
        get {
            isStopped.lock()
            defer { isStopped.unlock() }
            return _stopped
        }
        set {
            isStopped.lock()
            defer { isStopped.unlock() }
            _stopped = newValue
        }
    }

    public func onListen(withArguments _: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        eventSink = events
        return nil
    }

    public func onCancel(withArguments _: Any?) -> FlutterError? {
        eventSink = nil
        return nil
    }

    public let isCentered: Bool
    public let smoothingFactor: Float
    public let smoothTransition: Bool
    public var bands: [Float]

    private let _processor: AudioVisualizeProcessor
    private weak var _track: AudioTrack?

    public init(track: AudioTrack?,
                binaryMessenger: FlutterBinaryMessenger,
                bandCount: Int = 7,
                isCentered: Bool = true,
                smoothTransition: Bool = true,
                smoothingFactor: Float = 0.3,
                visualizerId: String)
    {
        self.isCentered = isCentered
        self.smoothingFactor = smoothingFactor
        self.smoothTransition = smoothTransition
        bands = Array(repeating: 0.0, count: bandCount)
        _processor = AudioVisualizeProcessor(bandsCount: bandCount)
        _track = track
        super.init()
        _track?.add(audioRenderer: self)
        let channelName = "io.livekit.audio.visualizer/eventChannel-" + (track?.mediaTrack.trackId ?? "") + "-" + visualizerId
        channel = FlutterEventChannel(name: channelName, binaryMessenger: binaryMessenger)
        channel?.setStreamHandler(self)
    }

    deinit {
        cleanup()
    }
    
    func cleanup() {
        // Mark as stopped to prevent race conditions
        stopped = true
        
        // Remove from track first
        _track?.remove(audioRenderer: self)
        
        // Release FlutterEventChannel
        channel?.setStreamHandler(nil)
        channel = nil
        
        // Clear eventSink
        eventSink = nil
    }

    public func render(pcmBuffer: AVAudioPCMBuffer) {
        // Early return if stopped to prevent race conditions
        guard !stopped else { return }
        
        let newBands = _processor.process(pcmBuffer: pcmBuffer)
        guard var newBands else { return }

        // If centering is enabled, rearrange the normalized bands
        if isCentered {
            newBands.sort(by: >)
            newBands = centerBands(newBands)
        }

        // Capture eventSink locally to avoid race condition
        guard let sink = eventSink else { return }
        
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.stopped else { return }
            
            // Use captured sink to avoid race condition with eventSink
            bands = zip(bands, newBands).map { old, new in
                if self.smoothTransition {
                    return self._smoothTransition(from: old, to: new, factor: self.smoothingFactor)
                }
                return new
            }
            sink(bands)
        }
    }

    // MARK: - Private

    /// Centers the sorted bands by placing higher values in the middle.
    @inline(__always) private func centerBands(_ sortedBands: [Float]) -> [Float] {
        var centeredBands = [Float](repeating: 0, count: sortedBands.count)
        var leftIndex = sortedBands.count / 2
        var rightIndex = leftIndex

        for (index, value) in sortedBands.enumerated() {
            if index % 2 == 0 {
                // Place value to the right
                centeredBands[rightIndex] = value
                rightIndex += 1
            } else {
                // Place value to the left
                leftIndex -= 1
                centeredBands[leftIndex] = value
            }
        }

        return centeredBands
    }

    /// Applies an easing function to smooth the transition.
    @inline(__always) private func _smoothTransition(from oldValue: Float, to newValue: Float, factor: Float) -> Float {
        // Calculate the delta change between the old and new value
        let delta = newValue - oldValue
        // Apply an ease-in-out cubic easing curve
        let easedFactor = _easeInOutCubic(t: factor)
        // Calculate and return the smoothed value
        return oldValue + delta * easedFactor
    }

    /// Easing function: ease-in-out cubic
    @inline(__always) private func _easeInOutCubic(t: Float) -> Float {
        t < 0.5 ? 4 * t * t * t : 1 - pow(-2 * t + 2, 3) / 2
    }
}
