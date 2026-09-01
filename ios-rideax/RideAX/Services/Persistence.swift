import Foundation

/// Small JSON-backed store for local state (session, trips, preferences).
/// Sensitive credentials never go here — they belong in the Keychain.
nonisolated enum Persistence {
    private static let defaults = UserDefaults.standard

    static func save<T: Encodable>(_ value: T, key: String) {
        do {
            let data = try JSONEncoder().encode(value)
            defaults.set(data, forKey: key)
        } catch {
            #if DEBUG
            print("[Persistence] failed to save \(key): \(error.localizedDescription)")
            #endif
        }
    }

    static func load<T: Decodable>(_ type: T.Type, key: String) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            #if DEBUG
            print("[Persistence] failed to load \(key): \(error.localizedDescription)")
            #endif
            return nil
        }
    }

    static func remove(key: String) {
        defaults.removeObject(forKey: key)
    }

    enum Key {
        static let session = "rideax.session.v1"
        static let trips = "rideax.trips.v1"
        static let driverState = "rideax.driver.v1"
    }
}
