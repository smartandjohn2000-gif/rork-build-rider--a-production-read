import SwiftUI

/// Three-step first-run experience that ends with the rider / driver choice.
struct OnboardingView: View {
    @Environment(SessionStore.self) private var session

    @State private var page: Int = 0
    @State private var selectedRole: AppRole = .rider
    @State private var showsAuth: Bool = false
    @State private var authMode: AuthMode = .signUp

    private let pages: [OnboardingPage] = OnboardingPage.all

    var body: some View {
        ZStack {
            BrandBackdrop()

            VStack(spacing: 0) {
                header

                TabView(selection: $page) {
                    ForEach(Array(pages.enumerated()), id: \.element.id) { index, item in
                        pageContent(item, index: index)
                            .tag(index)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .animation(.easeInOut(duration: 0.25), value: page)

                footer
            }
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showsAuth) {
            AuthView(mode: authMode, role: selectedRole)
                .presentationDetents([.large])
                .presentationBackground(RideTheme.canvas)
        }
    }

    private var header: some View {
        VStack(spacing: 14) {
            BrandMark(size: 78)
            BrandWordmark(size: 44)
        }
        .padding(.top, 24)
        .padding(.bottom, 8)
    }

    @ViewBuilder
    private func pageContent(_ item: OnboardingPage, index: Int) -> some View {
        VStack(spacing: 22) {
            VStack(spacing: 10) {
                Text(item.title)
                    .font(.system(.title, design: .default, weight: .bold))
                    .foregroundStyle(RideTheme.textPrimary)
                    .multilineTextAlignment(.center)

                Text(item.subtitle)
                    .font(.body)
                    .foregroundStyle(RideTheme.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }
            .padding(.top, 18)

            if index == pages.count - 1 {
                roleCards
                    .padding(.horizontal, RideTheme.margin)
            } else {
                highlights(item)
                    .padding(.horizontal, RideTheme.margin)
            }

            Spacer(minLength: 0)
        }
    }

    private func highlights(_ item: OnboardingPage) -> some View {
        VStack(spacing: 12) {
            ForEach(item.highlights, id: \.title) { highlight in
                HStack(spacing: 14) {
                    Image(systemName: highlight.symbol)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(RideTheme.accent)
                        .frame(width: 42, height: 42)
                        .background(RideTheme.accent.opacity(0.12), in: .circle)

                    VStack(alignment: .leading, spacing: 3) {
                        Text(highlight.title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(RideTheme.textPrimary)
                        Text(highlight.detail)
                            .font(.footnote)
                            .foregroundStyle(RideTheme.textSecondary)
                    }
                    Spacer(minLength: 0)
                }
                .rideCard(padding: 14)
            }
        }
    }

    private var roleCards: some View {
        HStack(spacing: 12) {
            ForEach(AppRole.allCases, id: \.self) { role in
                RoleCard(role: role, isSelected: selectedRole == role) {
                    withAnimation(.spring(response: 0.32, dampingFraction: 0.75)) {
                        selectedRole = role
                    }
                    Haptics.tap()
                }
            }
        }
    }

    private var footer: some View {
        VStack(spacing: 16) {
            HStack(spacing: 10) {
                Text("\(page + 1) of \(pages.count)")
                    .font(.footnote)
                    .foregroundStyle(RideTheme.textSecondary)
            }

            HStack(spacing: 8) {
                ForEach(0..<pages.count, id: \.self) { index in
                    Capsule()
                        .fill(index == page ? RideTheme.accent : RideTheme.textSecondary.opacity(0.35))
                        .frame(width: index == page ? 22 : 8, height: 8)
                        .animation(.spring(response: 0.3, dampingFraction: 0.8), value: page)
                }
            }

            Button(primaryTitle) {
                Haptics.tap()
                if page < pages.count - 1 {
                    withAnimation { page += 1 }
                } else {
                    authMode = .signUp
                    session.switchRole(to: selectedRole)
                    showsAuth = true
                }
            }
            .buttonStyle(RidePrimaryButtonStyle())

            Button {
                authMode = .signIn
                session.switchRole(to: selectedRole)
                showsAuth = true
            } label: {
                HStack(spacing: 4) {
                    Text("Already have an account?")
                        .foregroundStyle(RideTheme.textSecondary)
                    Text("Sign in")
                        .foregroundStyle(RideTheme.accent)
                        .fontWeight(.semibold)
                }
                .font(.subheadline)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, RideTheme.margin)
        .padding(.bottom, 12)
    }

    private var primaryTitle: String {
        page < pages.count - 1 ? "Continue" : selectedRole.ctaTitle
    }
}

/// One of the two large role choices on the final onboarding page.
private struct RoleCard: View {
    let role: AppRole
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 12) {
                illustration
                    .frame(height: 84)
                    .frame(maxWidth: .infinity)
                    .background(RideTheme.canvasDeep.opacity(0.6), in: .rect(cornerRadius: 12))

                VStack(alignment: .leading, spacing: 4) {
                    Text(role == .rider ? "I need a ride" : "I want to drive")
                        .font(.headline)
                        .foregroundStyle(RideTheme.textPrimary)
                    Text(role == .rider ? "Book, track and pay" : "Earn with RideAX")
                        .font(.footnote)
                        .foregroundStyle(RideTheme.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(14)
            .background(RideTheme.surface, in: .rect(cornerRadius: RideTheme.cardRadius))
            .overlay {
                RoundedRectangle(cornerRadius: RideTheme.cardRadius)
                    .strokeBorder(isSelected ? RideTheme.accent : RideTheme.hairline, lineWidth: isSelected ? 2 : 1)
            }
            .shadow(color: isSelected ? RideTheme.accent.opacity(0.28) : .clear, radius: 14, y: 6)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? [.isSelected, .isButton] : .isButton)
    }

    @ViewBuilder
    private var illustration: some View {
        if role == .rider {
            ZStack {
                RoadCurve()
                    .stroke(RideTheme.accent.opacity(0.7), style: StrokeStyle(lineWidth: 3, lineCap: .round, dash: [6, 5]))
                    .frame(width: 78, height: 46)
                    .offset(x: -6, y: 6)
                Image(systemName: "mappin.circle.fill")
                    .font(.system(size: 26))
                    .foregroundStyle(.white, RideTheme.accent)
                    .offset(x: 30, y: -18)
                Circle()
                    .strokeBorder(RideTheme.textSecondary, lineWidth: 2)
                    .frame(width: 10, height: 10)
                    .offset(x: -36, y: 26)
            }
        } else {
            ZStack {
                Image(systemName: "steeringwheel")
                    .font(.system(size: 38, weight: .regular))
                    .foregroundStyle(RideTheme.textSecondary)
                    .offset(x: -18)
                Image(systemName: "car.side.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(RideTheme.accent)
                    .offset(x: 22, y: 8)
            }
        }
    }
}

nonisolated struct OnboardingPage: Identifiable, Sendable {
    struct Highlight: Sendable {
        var symbol: String
        var title: String
        var detail: String
    }

    var id: String
    var title: String
    var subtitle: String
    var highlights: [Highlight]

    static let all: [OnboardingPage] = [
        OnboardingPage(
            id: "book",
            title: "A ride, wherever you are",
            subtitle: "Set your pickup, pick a destination and see the fare before you book.",
            highlights: [
                Highlight(symbol: "location.fill", title: "Instant pickup", detail: "We detect where you are and find the nearest car."),
                Highlight(symbol: "eurosign.circle.fill", title: "Upfront pricing", detail: "The fare you see is the fare you pay.")
            ]
        ),
        OnboardingPage(
            id: "track",
            title: "Follow every metre",
            subtitle: "Watch your driver approach live, message them, and share your trip with someone you trust.",
            highlights: [
                Highlight(symbol: "dot.radiowaves.left.and.right", title: "Live tracking", detail: "Real-time driver location and arrival countdown."),
                Highlight(symbol: "shield.lefthalf.filled", title: "Safety built in", detail: "Trip sharing and emergency help, one tap away.")
            ]
        ),
        OnboardingPage(
            id: "role",
            title: "Get moving in seconds",
            subtitle: "Book a ride in your city, or earn on your own schedule.",
            highlights: []
        )
    ]
}

#Preview {
    OnboardingView()
        .environment(SessionStore())
}
