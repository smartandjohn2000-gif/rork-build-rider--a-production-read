//
//  RideAXApp.swift
//  RideAX
//

import SwiftUI

@main
struct RideAXApp: App {
    @State private var session = SessionStore()
    @State private var rideStore = RideStore()
    @State private var driverStore = DriverStore()
    @State private var tripStore = TripStore()
    @State private var locationService = LocationService()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(session)
                .environment(rideStore)
                .environment(driverStore)
                .environment(tripStore)
                .environment(locationService)
                .tint(RideTheme.accent)
        }
    }
}
