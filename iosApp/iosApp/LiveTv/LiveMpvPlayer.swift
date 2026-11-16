// Nuvio Plus Live TV player (iPhone / iPad): mpv drawing into a Metal layer.
// Written from scratch for Nuvio Plus's clean Live TV part (see iptvui/LICENSE); it only uses
// libmpv's public C API. mpv and FFmpeg are LGPL.

import UIKit
import QuartzCore
import Libmpv
import ComposeApp

/// The view mpv draws into.
final class LiveMpvView: UIView {
    override class var layerClass: AnyClass { CAMetalLayer.self }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        contentScaleFactor = UIScreen.main.scale
        isUserInteractionEnabled = false
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard let metal = layer as? CAMetalLayer, bounds.width > 1, bounds.height > 1 else { return }
        metal.drawableSize = CGSize(width: bounds.width * contentScaleFactor, height: bounds.height * contentScaleFactor)
    }
}

final class LiveMpvPlayer: NSObject, LivePlayer {
    private let videoView = LiveMpvView(frame: CGRect(x: 0, y: 0, width: 16, height: 9))
    private var mpv: OpaquePointer?
    private let events = DispatchQueue(label: "plus.nuvio.live.mpv")
    private var currentStatus = "idle"

    override init() {
        super.init()
        start()
    }

    deinit { shutdown() }

    // MARK: LivePlayer

    func view() -> UIView { videoView }

    func play(url: String, headers: [String: String]) {
        load(url: url, headers: headers, start: "none")
    }

    func playFrom(url: String, headers: [String: String], startSeconds: Double) {
        load(url: url, headers: headers, start: startSeconds > 1 ? String(format: "%.1f", startSeconds) : "none")
    }

    private func load(url: String, headers: [String: String], start: String) {
        guard mpv != nil else { return }
        setString("start", start)
        currentStatus = "buffering"
        let ua = headers.first { $0.key.lowercased() == "user-agent" }?.value ?? "Mozilla/5.0 (iPhone) NuvioPlus"
        setString("user-agent", ua)
        let other = headers.filter { $0.key.lowercased() != "user-agent" }
            .map { "\($0.key): \($0.value)" }
            .joined(separator: ",")
        setString("http-header-fields", other)
        command(["loadfile", url, "replace"])
        setFlag("pause", false)
    }

    func setPaused(paused: Bool) { setFlag("pause", paused) }

    func stop() {
        command(["stop"])
        currentStatus = "idle"
    }

    func close() { shutdown() }

    func status() -> String { currentStatus }

    func seek(seconds: Double, relative: Bool) {
        command(["seek", String(format: "%.1f", seconds), relative ? "relative" : "absolute"])
    }

    func position() -> Double { double("time-pos") }
    func duration() -> Double { double("duration") }

    func isPaused() -> Bool {
        guard let handle = mpv else { return false }
        var flag: Int32 = 0
        mpv_get_property(handle, "pause", MPV_FORMAT_FLAG, &flag)
        return flag != 0
    }

    func tracks(type: String) -> String {
        guard let handle = mpv else { return "" }
        var count: Int64 = 0
        mpv_get_property(handle, "track-list/count", MPV_FORMAT_INT64, &count)
        var lines: [String] = []
        for i in 0..<max(0, Int(count)) {
            guard string("track-list/\(i)/type") == type else { continue }
            let id = string("track-list/\(i)/id") ?? ""
            let lang = string("track-list/\(i)/lang")
            let title = string("track-list/\(i)/title")
            var parts: [String] = []
            if let lang, !lang.isEmpty {
                parts.append(Locale.current.localizedString(forLanguageCode: lang)?.capitalized ?? lang)
            }
            if let title, !title.isEmpty, !parts.contains(title) { parts.append(title) }
            if type == "audio", let ch = string("track-list/\(i)/demux-channel-count"), let n = Int(ch) {
                switch n { case 1: parts.append("Mono"); case 2: parts.append("Stereo"); case 6: parts.append("5.1"); case 8: parts.append("7.1"); default: break }
            }
            let label = parts.isEmpty ? "Track \(id)" : parts.joined(separator: " · ")
            let selected = string("track-list/\(i)/selected") == "yes" ? "1" : "0"
            lines.append("\(id)\t\(label)\t\(selected)")
        }
        return lines.joined(separator: "\n")
    }

    func selectTrack(type: String, id: String) {
        setString(type == "audio" ? "aid" : "sid", id)
    }

    private func double(_ name: String) -> Double {
        guard let handle = mpv else { return 0 }
        var value: Double = 0
        mpv_get_property(handle, name, MPV_FORMAT_DOUBLE, &value)
        return value.isFinite ? max(0, value) : 0
    }

    private func string(_ name: String) -> String? {
        guard let handle = mpv, let c = mpv_get_property_string(handle, name) else { return nil }
        defer { mpv_free(c) }
        return String(cString: c)
    }

    // MARK: mpv

    private func start() {
        guard let handle = mpv_create() else { currentStatus = "error: the player couldn't start"; return }
        mpv = handle
        var layerPointer = Int64(Int(bitPattern: Unmanaged.passUnretained(videoView.layer).toOpaque()))
        mpv_set_option(handle, "wid", MPV_FORMAT_INT64, &layerPointer)
        let options: [(String, String)] = [
            ("vo", "gpu-next"),
            ("gpu-api", "vulkan"),
            ("gpu-context", "moltenvk"),
            ("hwdec", "videotoolbox"),
            ("keep-open", "no"),
            ("idle", "yes"),
            // Live streams: a cushion against short hiccups, reconnect when the server drops.
            ("cache", "yes"),
            ("cache-secs", "20"),
            ("demuxer-max-bytes", "64MiB"),
            ("demuxer-readahead-secs", "8"),
            ("network-timeout", "20"),
            ("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5"),
            ("audio-channels", "auto"),
            ("audio-fallback-to-null", "yes"),
            ("input-default-bindings", "no"),
            ("terminal", "no"),
            ("msg-level", "all=warn"),
        ]
        for (name, value) in options { mpv_set_option_string(handle, name, value) }
        guard mpv_initialize(handle) >= 0 else { currentStatus = "error: the player couldn't start"; return }
        mpv_observe_property(handle, 0, "paused-for-cache", MPV_FORMAT_FLAG)
        mpv_set_wakeup_callback(handle, { context in
            guard let context else { return }
            let player = Unmanaged<LiveMpvPlayer>.fromOpaque(context).takeUnretainedValue()
            player.events.async { player.drainEvents() }
        }, Unmanaged.passUnretained(self).toOpaque())
    }

    private func drainEvents() {
        guard let handle = mpv else { return }
        while true {
            guard let event = mpv_wait_event(handle, 0) else { return }
            switch event.pointee.event_id {
            case MPV_EVENT_NONE:
                return
            case MPV_EVENT_START_FILE:
                currentStatus = "buffering"
            case MPV_EVENT_FILE_LOADED, MPV_EVENT_PLAYBACK_RESTART:
                currentStatus = "playing"
            case MPV_EVENT_END_FILE:
                if let data = event.pointee.data?.assumingMemoryBound(to: mpv_event_end_file.self) {
                    if data.pointee.reason == MPV_END_FILE_REASON_ERROR {
                        currentStatus = "error: " + String(cString: mpv_error_string(data.pointee.error))
                    } else if data.pointee.reason == MPV_END_FILE_REASON_EOF {
                        currentStatus = "ended"
                    }
                }
            case MPV_EVENT_PROPERTY_CHANGE:
                if let prop = event.pointee.data?.assumingMemoryBound(to: mpv_event_property.self),
                   String(cString: prop.pointee.name) == "paused-for-cache",
                   prop.pointee.format == MPV_FORMAT_FLAG,
                   let value = prop.pointee.data?.assumingMemoryBound(to: Int32.self) {
                    currentStatus = value.pointee != 0 ? "buffering" : "playing"
                }
            case MPV_EVENT_SHUTDOWN:
                return
            default:
                break
            }
        }
    }

    private func shutdown() {
        guard let handle = mpv else { return }
        mpv = nil
        mpv_set_wakeup_callback(handle, nil, nil)
        mpv_terminate_destroy(handle)
        currentStatus = "idle"
    }

    private func command(_ args: [String]) {
        guard let handle = mpv else { return }
        var cArgs: [UnsafeMutablePointer<CChar>?] = args.map { strdup($0) }
        cArgs.append(nil)
        cArgs.withUnsafeMutableBufferPointer { buffer in
            buffer.baseAddress!.withMemoryRebound(to: UnsafePointer<CChar>?.self, capacity: buffer.count) { pointer in
                _ = mpv_command(handle, pointer)
            }
        }
        cArgs.forEach { free($0) }
    }

    private func setString(_ name: String, _ value: String) {
        guard let handle = mpv else { return }
        mpv_set_property_string(handle, name, value)
    }

    private func setFlag(_ name: String, _ value: Bool) {
        guard let handle = mpv else { return }
        var flag: Int32 = value ? 1 : 0
        mpv_set_property(handle, name, MPV_FORMAT_FLAG, &flag)
    }
}

final class LiveMpvPlayerCreator: NSObject, LivePlayerCreator {
    func create() -> any LivePlayer { LiveMpvPlayer() }
}
