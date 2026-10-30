import SwiftUI

/// What's open from home. On a narrow screen it takes the screen; on the open iPhone Duo it sits beside home.
enum Pane: Hashable {
    case map(MapFocus)
    case activity
}

/// Where the store map opens: on an area, or on a store that has stock, among the others that do.
struct MapFocus: Hashable {
    var area: String?
    var store: String?
}

/// Where a page opened from home puts its controls. With the screen to itself they go in the bar, like any other
/// screen. Beside home on the open iPhone Duo the bar is home's, so they float over the page instead, with a
/// button to give it the whole screen.
enum PageChrome {
    case bar
    case pane(fullScreen: Binding<Bool>)

    var isBar: Bool {
        if case .bar = self { true } else { false }
    }
}

extension View {
    func pageChrome(_ chrome: PageChrome, title: String, close: @escaping () -> Void) -> some View {
        modifier(PageChromeModifier(chrome: chrome, title: title, close: close))
    }

    /// Keeps a column of cards to a comfortable width when the open iPhone Duo gives it the whole screen.
    func readableWidth() -> some View {
        frame(maxWidth: 640).frame(maxWidth: .infinity)
    }

    /// Open, home and the page beside it share the bar. Left to shrink as one of them scrolls, it would take the
    /// other's top edge and the live badge with it.
    @ViewBuilder func barStaysPut(_ staysPut: Bool) -> some View {
        if #available(iOS 27.0, *) {
            toolbarMinimizationBehavior(staysPut ? .never : .automatic, for: .navigationBar)
        } else {
            self
        }
    }
}

/// Both kinds of chrome are always applied, one of them empty, so folding or unfolding the phone doesn't rebuild
/// the page and lose where the map was.
private struct PageChromeModifier: ViewModifier {
    let chrome: PageChrome
    let title: String
    let close: () -> Void

    func body(content: Content) -> some View {
        content
            .navigationTitle(chrome.isBar ? title : "")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if chrome.isBar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(role: .close, action: close)
                    }
                }
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                if case .pane(let fullScreen) = chrome {
                    PaneControls(title: title, fullScreen: fullScreen, close: close)
                }
            }
            // The controls bring their own glass, so the page scrolls off the top like home beside it.
            .scrollEdgeEffectHidden(!chrome.isBar, for: .top)
    }
}

/// Close, the title and full screen, in glass over the top of the pane.
private struct PaneControls: View {
    let title: String
    @Binding var fullScreen: Bool
    let close: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            button("Close", systemImage: "xmark", action: close)
            Spacer(minLength: 0)
            Text(title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, 18)
                .frame(height: 44)
                .glassEffect(.regular, in: .capsule)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 0)
            button(
                fullScreen ? "Exit full screen" : "Full screen",
                systemImage: fullScreen ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right"
            ) {
                withAnimation(.smooth) { fullScreen.toggle() }
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private func button(_ title: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.body.weight(.semibold))
                .frame(width: 44, height: 44)
                .contentShape(.circle)
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.interactive(), in: .circle)
        .accessibilityLabel(title)
    }
}
