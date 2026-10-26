import UIKit

/// The Apple Store app claims apple.com/uk/shop/buy-… links as universal links, so a product page opens
/// there, with the model already chosen, ready for Apple Pay. Safari is the fallback if it isn't installed.
enum AppleStore {
    static func open(_ url: URL) async {
        if await UIApplication.shared.open(url, options: [.universalLinksOnly: true]) { return }
        _ = await UIApplication.shared.open(url)
    }
}
