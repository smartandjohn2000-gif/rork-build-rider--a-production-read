import SwiftUI

/// Routes between onboarding, the rider shell and the driver shell.
struct ContentView: View {
    @Environment(SessionStore.self) private var session

    var body: some View {
        Group {
            if !session.hasCompletedOnboarding || !session.isSignedIn {
                OnboardingView()
                    .transition(.opacity)
            } else {
                switch session.role {
                case .rider:
                    RiderTabView()
                        .transition(.opacity)
                case .driver:
                    DriverHomeView()
                        .transition(.opacity)
                }
            }
        }
        .animation(.easeInOut(duration: 0.3), value: session.hasCompletedOnboarding)
        .animation(.easeInOut(duration: 0.3), value: session.role)
        .preferredColorScheme(.dark)
    }
}

#Preview {
    ContentView()
        .environment(SessionStore())
        .environment(RideStore())
        .environment(DriverStore())
        .environment(TripStore())
        .environment(LocationService())
}
