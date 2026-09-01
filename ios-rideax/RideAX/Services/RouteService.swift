import Foundation
import MapKit

nonisolated struct RouteEstimate: Sendable, Hashable {
    var polyline: [GeoPoint]
    var distanceMeters: Double
    var durationSeconds: Double

    var formattedDistance: String {
        Measurement(value: distanceMeters, unit: UnitLength.meters)
            .formatted(.measurement(width: .abbreviated, usage: .road))
    }

    var minutes: Int { max(1, Int((durationSeconds / 60).rounded())) }
}

/// Directions + place search. Uses MapKit so the app works without any
/// third-party map key; the interface is deliberately small so a different
/// routing provider can be swapped in behind it.
nonisolated struct RouteService: Sendable {

    /// Real driving directions with a straight-line fallback so the UI never
    /// gets stuck when the network or the directions service is unavailable.
    func route(from origin: GeoPoint, to destination: GeoPoint) async -> RouteEstimate {
        let request = MKDirections.Request()
        request.source = MKMapItem(placemark: MKPlacemark(coordinate: origin.coordinate))
        request.destination = MKMapItem(placemark: MKPlacemark(coordinate: destination.coordinate))
        request.transportType = .automobile

        do {
            let response = try await MKDirections(request: request).calculate()
            guard let route = response.routes.first else { return Self.fallback(origin, destination) }
            return RouteEstimate(
                polyline: Self.points(from: route.polyline),
                distanceMeters: route.distance,
                durationSeconds: route.expectedTravelTime
            )
        } catch {
            #if DEBUG
            print("[RouteService] directions unavailable, using estimate: \(error.localizedDescription)")
            #endif
            return Self.fallback(origin, destination)
        }
    }

    /// Local place search for the destination picker.
    func search(query: String, around point: GeoPoint) async -> [Place] {
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = query
        request.region = MKCoordinateRegion(
            center: point.coordinate,
            latitudinalMeters: 20_000,
            longitudinalMeters: 20_000
        )

        do {
            let response = try await MKLocalSearch(request: request).start()
            return response.mapItems.prefix(12).map { item in
                Place(
                    id: "\(item.placemark.coordinate.latitude),\(item.placemark.coordinate.longitude)-\(item.name ?? "")",
                    name: item.name ?? "Dropped pin",
                    subtitle: Self.subtitle(for: item.placemark),
                    point: GeoPoint(item.placemark.coordinate),
                    symbol: "mappin.circle.fill"
                )
            }
        } catch {
            #if DEBUG
            print("[RouteService] search failed: \(error.localizedDescription)")
            #endif
            return []
        }
    }

    /// Reverse geocode used to label the rider's live pickup point.
    func placeName(for point: GeoPoint) async -> String? {
        let geocoder = CLGeocoder()
        do {
            let placemarks = try await geocoder.reverseGeocodeLocation(
                CLLocation(latitude: point.latitude, longitude: point.longitude)
            )
            guard let placemark = placemarks.first else { return nil }
            return placemark.name ?? placemark.thoroughfare ?? placemark.locality
        } catch {
            return nil
        }
    }

    private static func subtitle(for placemark: CLPlacemark) -> String {
        [placemark.thoroughfare, placemark.locality, placemark.country]
            .compactMap { $0 }
            .joined(separator: ", ")
    }

    private static func points(from polyline: MKPolyline) -> [GeoPoint] {
        var coordinates = [CLLocationCoordinate2D](
            repeating: kCLLocationCoordinate2DInvalid,
            count: polyline.pointCount
        )
        polyline.getCoordinates(&coordinates, range: NSRange(location: 0, length: polyline.pointCount))
        return coordinates.map(GeoPoint.init)
    }

    /// Straight line plus a typical urban speed, so fares stay plausible offline.
    private static func fallback(_ origin: GeoPoint, _ destination: GeoPoint) -> RouteEstimate {
        let straight = origin.distance(to: destination)
        let roadDistance = straight * 1.32
        let averageSpeed: Double = 7.5 // metres per second, ~27 km/h city traffic
        let steps = 24
        let polyline = (0...steps).map { step in
            origin.interpolated(to: destination, progress: Double(step) / Double(steps))
        }
        return RouteEstimate(
            polyline: polyline,
            distanceMeters: roadDistance,
            durationSeconds: max(roadDistance / averageSpeed, 120)
        )
    }
}
