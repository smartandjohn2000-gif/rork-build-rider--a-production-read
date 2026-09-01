import SwiftUI

/// The rider shell: map-led booking, trip history and account.
struct RiderTabView: View {
    @State private var selection: Int = 0

    var body: some View {
        TabView(selection: $selection) {
            BookingHomeView()
                .tabItem {
                    Label("Ride", systemImage: "car.fill")
                }
                .tag(0)

            ActivityView()
                .tabItem {
                    Label("Activity", systemImage: "clock.fill")
                }
                .tag(1)

            AccountView()
                .tabItem {
                    Label("Account", systemImage: "person.fill")
                }
                .tag(2)
        }
        .tint(RideTheme.accent)
        .preferredColorScheme(.dark)
    }
}
