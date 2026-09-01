import Foundation
import CoreLocation
import Observation

/// Wraps CoreLocation so views only ever see a coordinate and an authorization
/// state. Falls back to a city centre when no fix is available (e.g. simulator).
@Observable
final class LocationService: NSObject {
    /// Paris city centre — used until a real fix arrives.
    static let fallbackPoint = GeoPoint(latitude: 48.8656, longitude: 2.3712)

    private(set) var currentPoint: GeoPoint = LocationService.fallbackPoint
    private(set) var authorizationStatus: CLAuthorizationStatus = .notDetermined
    private(set) var hasRealFix: Bool = false

    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 10
        authorizationStatus = manager.authorizationStatus
    }

    var isDenied: Bool {
        authorizationStatus == .denied || authorizationStatus == .restricted
    }

    func requestPermission() {
        manager.requestWhenInUseAuthorization()
    }

    func startUpdating() {
        guard !isDenied else { return }
        manager.startUpdatingLocation()
    }

    func stopUpdating() {
        manager.stopUpdatingLocation()
    }
}

extension LocationService: CLLocationManagerDelegate {
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorizationStatus = status
            if status == .authorizedWhenInUse || status == .authorizedAlways {
                self.startUpdating()
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        let point = GeoPoint(location.coordinate)
        Task { @MainActor in
            self.currentPoint = point
            self.hasRealFix = true
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        #if DEBUG
        print("[LocationService] location update failed: \(error.localizedDescription)")
        #endif
    }
}
