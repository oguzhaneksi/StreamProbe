import UIKit

/// Thin UIKit AppDelegate retained only to vend a `UISceneConfiguration` that installs our custom
/// `SceneDelegate` — SwiftUI's `App` lifecycle does not provide scene callbacks, which we need to
/// create the second (overlay) `UIWindow`.
class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        config.delegateClass = SceneDelegate.self
        return config
    }
}
