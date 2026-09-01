import Foundation
import Observation

/// Trip history shared by the rider Activity tab and driver history.
@Observable
@MainActor
final class TripStore {
    private(set) var riderTrips: [TripRecord] = []
    private(set) var driverTrips: [TripRecord] = []

    private nonisolated struct Snapshot: Codable, Sendable {
        var rider: [TripRecord]
        var driver: [TripRecord]
    }

    init() {
        if let snapshot = Persistence.load(Snapshot.self, key: Persistence.Key.trips) {
            riderTrips = snapshot.rider
            driverTrips = snapshot.driver
        } else {
            riderTrips = TripRecord.riderSamples
            driverTrips = TripRecord.driverSamples
            persist()
        }
    }

    func recordRiderTrip(_ trip: TripRecord) {
        riderTrips.insert(trip, at: 0)
        persist()
    }

    func recordDriverTrip(_ trip: TripRecord) {
        driverTrips.insert(trip, at: 0)
        persist()
    }

    func rate(tripID: String, stars: Int) {
        guard let index = riderTrips.firstIndex(where: { $0.id == tripID }) else { return }
        riderTrips[index].rating = stars
        persist()
    }

    /// Earnings grouped Monday-to-Sunday for the driver chart.
    func weeklyEarnings(reference: Date = Date()) -> [DailyEarnings] {
        var calendar = Calendar(identifier: .gregorian)
        calendar.firstWeekday = 2
        let labels = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
        let startOfWeek = calendar.dateInterval(of: .weekOfYear, for: reference)?.start ?? reference

        return labels.enumerated().map { index, label in
            guard let day = calendar.date(byAdding: .day, value: index, to: startOfWeek) else {
                return DailyEarnings(dayLabel: label, amount: 0, trips: 0)
            }
            let trips = driverTrips.filter { calendar.isDate($0.date, inSameDayAs: day) && !$0.wasCancelled }
            return DailyEarnings(
                dayLabel: label,
                amount: trips.reduce(0) { $0 + $1.fare },
                trips: trips.count
            )
        }
    }

    var weeklyTotal: Double {
        weeklyEarnings().reduce(0) { $0 + $1.amount }
    }

    var todaysEarnings: Double {
        let calendar = Calendar.current
        return driverTrips
            .filter { calendar.isDateInToday($0.date) && !$0.wasCancelled }
            .reduce(0) { $0 + $1.fare }
    }

    var todaysTripCount: Int {
        let calendar = Calendar.current
        return driverTrips.filter { calendar.isDateInToday($0.date) && !$0.wasCancelled }.count
    }

    var driverAverageRating: Double {
        let rated = driverTrips.compactMap(\.rating)
        guard !rated.isEmpty else { return 4.93 }
        return Double(rated.reduce(0, +)) / Double(rated.count)
    }

    private func persist() {
        Persistence.save(Snapshot(rider: riderTrips, driver: driverTrips), key: Persistence.Key.trips)
    }
}

extension TripRecord {
    static var riderSamples: [TripRecord] {
        [
            TripRecord(id: "t1", pickupName: "Rue Oberkampf", dropoffName: "Gare du Nord", date: Date(timeIntervalSinceNow: -3600 * 26), fare: 12.40, currencyCode: "EUR", className: "RideAX Go", counterpartName: "Ahmed K.", rating: 5, wasCancelled: false),
            TripRecord(id: "t2", pickupName: "Louvre Museum", dropoffName: "Le Marais", date: Date(timeIntervalSinceNow: -3600 * 52), fare: 9.80, currencyCode: "EUR", className: "RideAX Go", counterpartName: "Léa M.", rating: 5, wasCancelled: false),
            TripRecord(id: "t3", pickupName: "CDG Airport", dropoffName: "Rue Oberkampf", date: Date(timeIntervalSinceNow: -3600 * 120), fare: 42.10, currencyCode: "EUR", className: "Comfort", counterpartName: "Marco R.", rating: 4, wasCancelled: false),
            TripRecord(id: "t4", pickupName: "Bastille", dropoffName: "Montmartre", date: Date(timeIntervalSinceNow: -3600 * 190), fare: 0, currencyCode: "EUR", className: "RideAX Go", counterpartName: "Sofia D.", rating: nil, wasCancelled: true)
        ]
    }

    static var driverSamples: [TripRecord] {
        let calendar = Calendar.current
        let today = Date()
        func day(_ offset: Int, hour: Int) -> Date {
            let base = calendar.date(byAdding: .day, value: offset, to: today) ?? today
            return calendar.date(bySettingHour: hour, minute: 15, second: 0, of: base) ?? base
        }
        return [
            TripRecord(id: "dt1", pickupName: "Gare du Nord", dropoffName: "Montmartre", date: day(0, hour: 18), fare: 14.60, currencyCode: "EUR", className: "RideAX Go", counterpartName: "Chloé B.", rating: 5, wasCancelled: false),
            TripRecord(id: "dt2", pickupName: "Opéra", dropoffName: "Bastille", date: day(0, hour: 11), fare: 11.30, currencyCode: "EUR", className: "RideAX Go", counterpartName: "Yanis T.", rating: 5, wasCancelled: false),
            TripRecord(id: "dt3", pickupName: "Le Marais", dropoffName: "La Défense", date: day(0, hour: 9), fare: 22.40, currencyCode: "EUR", className: "Comfort", counterpartName: "Nora P.", rating: 4, wasCancelled: false),
            TripRecord(id: "dt4", pickupName: "CDG Airport", dropoffName: "Le Marais", date: day(-1, hour: 21), fare: 42.10, currencyCode: "EUR", className: "Comfort", counterpartName: "Tomás L.", rating: 5, wasCancelled: false),
            TripRecord(id: "dt5", pickupName: "Bastille", dropoffName: "Nation", date: day(-1, hour: 15), fare: 9.20, currencyCode: "EUR", className: "RideAX Go", counterpartName: "Alice R.", rating: 5, wasCancelled: false),
            TripRecord(id: "dt6", pickupName: "Saint-Germain", dropoffName: "Invalides", date: day(-2, hour: 12), fare: 10.70, currencyCode: "EUR", className: "RideAX Go", counterpartName: "Hugo V.", rating: 5, wasCancelled: false),
            TripRecord(id: "dt7", pickupName: "Montparnasse", dropoffName: "Louvre", date: day(-3, hour: 8), fare: 13.90, currencyCode: "EUR", className: "RideAX Go", counterpartName: "Emma S.", rating: 4, wasCancelled: false),
            TripRecord(id: "dt8", pickupName: "Belleville", dropoffName: "Gare de Lyon", date: day(-4, hour: 19), fare: 16.20, currencyCode: "EUR", className: "Comfort", counterpartName: "Jonas W.", rating: 5, wasCancelled: false)
        ]
    }
}
