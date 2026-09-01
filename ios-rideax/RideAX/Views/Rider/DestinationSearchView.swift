import SwiftUI

/// Destination picker with live place search and saved shortcuts.
struct DestinationSearchView: View {
    @Environment(SessionStore.self) private var session
    @Environment(RideStore.self) private var rideStore
    @Environment(\.dismiss) private var dismiss

    let onSelect: (Place) -> Void

    @State private var query: String = ""
    @State private var results: [Place] = []
    @State private var isSearching: Bool = false
    @State private var searchTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            ZStack {
                RideTheme.canvas.ignoresSafeArea()

                VStack(spacing: 12) {
                    searchBar

                    ScrollView {
                        VStack(spacing: 10) {
                            if query.isEmpty {
                                SectionHeader(title: "Saved places")
                                    .padding(.top, 4)
                                ForEach(session.savedPlaces) { place in
                                    placeRow(place)
                                }

                                SectionHeader(title: "Popular in your city")
                                    .padding(.top, 10)
                                ForEach(Place.popular) { place in
                                    placeRow(place)
                                }
                            } else if isSearching && results.isEmpty {
                                ProgressView()
                                    .tint(RideTheme.accent)
                                    .padding(.top, 40)
                            } else if results.isEmpty {
                                EmptyStateView(
                                    symbol: "mappin.slash",
                                    title: "No places found",
                                    message: "Try a different street, landmark or business name."
                                )
                            } else {
                                ForEach(results) { place in
                                    placeRow(place)
                                }
                            }
                        }
                        .padding(.horizontal, RideTheme.margin)
                        .padding(.bottom, 30)
                    }
                }
            }
            .navigationTitle("Where to?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(RideTheme.canvas, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel") { dismiss() }
                        .foregroundStyle(RideTheme.textSecondary)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private var searchBar: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(RideTheme.textSecondary)
            TextField("", text: $query, prompt: Text("Search street, landmark or place").foregroundStyle(RideTheme.textSecondary))
                .foregroundStyle(RideTheme.textPrimary)
                .autocorrectionDisabled()
                .submitLabel(.search)
            if !query.isEmpty {
                Button {
                    query = ""
                    results = []
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(RideTheme.textSecondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14)
        .frame(height: 50)
        .background(RideTheme.surface, in: .rect(cornerRadius: RideTheme.controlRadius))
        .overlay {
            RoundedRectangle(cornerRadius: RideTheme.controlRadius)
                .strokeBorder(RideTheme.hairline, lineWidth: 1)
        }
        .padding(.horizontal, RideTheme.margin)
        .onChange(of: query) { _, newValue in
            search(newValue)
        }
    }

    private func placeRow(_ place: Place) -> some View {
        Button {
            Haptics.tap()
            onSelect(place)
            dismiss()
        } label: {
            HStack(spacing: 14) {
                Image(systemName: place.symbol)
                    .font(.system(size: 16))
                    .foregroundStyle(RideTheme.accent)
                    .frame(width: 40, height: 40)
                    .background(RideTheme.surfaceRaised, in: .circle)

                VStack(alignment: .leading, spacing: 2) {
                    Text(place.name)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(RideTheme.textPrimary)
                        .lineLimit(1)
                    Text(place.subtitle)
                        .font(.footnote)
                        .foregroundStyle(RideTheme.textSecondary)
                        .lineLimit(1)
                }

                Spacer(minLength: 0)

                Image(systemName: "arrow.up.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(RideTheme.textSecondary)
            }
            .rideCard(padding: 12)
        }
        .buttonStyle(.plain)
    }

    private func search(_ text: String) {
        searchTask?.cancel()
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard trimmed.count > 1 else {
            results = []
            isSearching = false
            return
        }
        isSearching = true
        let origin = rideStore.pickup.point
        searchTask = Task {
            try? await Task.sleep(for: .milliseconds(280))
            guard !Task.isCancelled else { return }
            let found = await RouteService().search(query: trimmed, around: origin)
            guard !Task.isCancelled else { return }
            results = found
            isSearching = false
        }
    }
}

extension Place {
    static let popular: [Place] = [
        Place(id: "gare-du-nord", name: "Gare du Nord", subtitle: "18 Rue de Dunkerque", point: GeoPoint(latitude: 48.8809, longitude: 2.3553), symbol: "tram.fill"),
        Place(id: "louvre", name: "Louvre Museum", subtitle: "Rue de Rivoli", point: GeoPoint(latitude: 48.8606, longitude: 2.3376), symbol: "building.columns.fill"),
        Place(id: "montmartre", name: "Montmartre", subtitle: "Sacré-Cœur, 18th arr.", point: GeoPoint(latitude: 48.8867, longitude: 2.3431), symbol: "mountain.2.fill"),
        Place(id: "bastille", name: "Bastille", subtitle: "Place de la Bastille", point: GeoPoint(latitude: 48.8532, longitude: 2.3692), symbol: "theatermasks.fill")
    ]
}
