import Foundation
import Observation

/// Turns the sparse driver location pings a realtime feed delivers (one every
/// second or two) into a marker that moves continuously across the map.
///
/// Drawing pings raw makes the vehicle teleport between fixes. Instead we keep
/// the last ping as an anchor and ease the marker towards the newest one along
/// the *route polyline*, so the car follows corners instead of cutting across
/// blocks, and rotate its heading gradually so it turns rather than snapping.
///
/// The loop only runs while there is motion left to play out; once the marker
/// settles on the newest ping it idles until the next one arrives.
@Observable
@MainActor
final class DriverTrackAnimator {
    /// Interpolated position, updated at frame cadence.
    private(set) var point: GeoPoint?
    /// Smoothed direction of travel in degrees, 0° = north.
    private(set) var heading: Double = 0
    /// Interpolated distance along the current leg, 0...1.
    private(set) var progress: Double = 0
    /// True while the vehicle is actually covering ground.
    private(set) var isMoving: Bool = false
    private(set) var speedKilometresPerHour: Int = 0

    private var route: [GeoPoint] = []
    private var fromProgress: Double = 0
    private var toProgress: Double = 0
    private var startedAt: Date = .now
    private var window: Double = 1.5
    private var lastSequence: Int = -1
    private var frameTask: Task<Void, Never>?
    private var idleFrames: Int = 0

    /// ~30 fps. Smooth to the eye and far cheaper than redrawing the map at 60.
    private static let frameInterval: Duration = .milliseconds(33)
    /// Frames of stillness before the loop parks itself.
    private static let idleFrameLimit: Int = 20
    /// How quickly the marker rotates towards the road's direction.
    private static let turnRate: Double = 0.16

    /// Feeds in a new location update. Stale or duplicate sequences are ignored,
    /// which is what keeps an out-of-order socket delivery from rubber-banding
    /// the marker backwards.
    func ingest(_ ping: DriverPing, route newRoute: [GeoPoint]) {
        guard ping.sequence != lastSequence else { return }
        let isNewLeg = newRoute != route
        route = newRoute

        if isNewLeg || point == nil || ping.sequence < lastSequence {
            // First fix, or the driver switched legs (pickup → dropoff):
            // place the vehicle directly rather than sliding it there.
            fromProgress = ping.progress
            toProgress = ping.progress
            progress = ping.progress
            point = ping.point
            heading = ping.heading
        } else {
            fromProgress = progress
            toProgress = max(ping.progress, progress)
        }

        lastSequence = ping.sequence
        startedAt = .now
        // Stretch the easing window slightly past the expected cadence so the
        // marker is still gliding when the next ping lands, instead of
        // stopping dead and lurching forward again.
        window = max(ping.expectedInterval * 1.15, 0.2)
        speedKilometresPerHour = ping.speedKilometresPerHour
        isMoving = toProgress > fromProgress + 0.0001
        idleFrames = 0
        startFrames()
    }

    /// Clears everything, used when the ride ends or the screen goes away.
    func reset() {
        stopFrames()
        route = []
        point = nil
        heading = 0
        progress = 0
        isMoving = false
        speedKilometresPerHour = 0
        lastSequence = -1
    }

    func stopFrames() {
        frameTask?.cancel()
        frameTask = nil
    }

    private func startFrames() {
        guard frameTask == nil else { return }
        frameTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                self.tick()
                try? await Task.sleep(for: Self.frameInterval)
            }
        }
    }

    private func tick() {
        guard route.count > 1 else { return }
        let elapsed = Date.now.timeIntervalSince(startedAt)
        let fraction = window > 0 ? min(max(elapsed / window, 0), 1) : 1
        let eased = Self.ease(fraction)
        let value = fromProgress + (toProgress - fromProgress) * eased

        var didChange = false

        if abs(value - progress) > 0.00002 {
            progress = value
            didChange = true
        }
        if let next = route.point(atProgress: value), next != point {
            point = next
            didChange = true
        }

        let desired = route.heading(atProgress: value)
        let turned = Self.turned(from: heading, towards: desired, rate: Self.turnRate)
        if abs(Self.angularDelta(from: heading, to: turned)) > 0.05 {
            heading = turned
            didChange = true
        }

        if fraction >= 1 {
            if isMoving { isMoving = false }
            idleFrames = didChange ? 0 : idleFrames + 1
            if idleFrames >= Self.idleFrameLimit { stopFrames() }
        }
    }

    /// Gentle ease-out so each leg between two pings decelerates a touch into
    /// the next fix — it reads as a car easing off, not a linear conveyor.
    private static func ease(_ t: Double) -> Double {
        1 - pow(1 - t, 1.6)
    }

    private static func angularDelta(from current: Double, to target: Double) -> Double {
        var delta = (target - current).truncatingRemainder(dividingBy: 360)
        if delta > 180 { delta -= 360 }
        if delta < -180 { delta += 360 }
        return delta
    }

    /// Rotates the shortest way round, so 350° → 10° turns 20° right rather
    /// than spinning 340° left.
    private static func turned(from current: Double, towards target: Double, rate: Double) -> Double {
        var next = current + angularDelta(from: current, to: target) * rate
        if next < 0 { next += 360 }
        if next >= 360 { next -= 360 }
        return next
    }
}
