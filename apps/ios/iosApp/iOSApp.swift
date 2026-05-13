import SwiftUI
import UIKit
import ComposeApp

@main
struct iOSApp: App {
    
    let permissionManager = IOSPermissionManager()
    let reviewPrompter = IOSReviewPrompter()
    private let nativeViewFactory = IOSNativeViewFactory.shared
    private let iOSAppComponent: IosAppComponent

    init() {
        self.iOSAppComponent = create(
            permissionManager: permissionManager,
            reviewPrompter: reviewPrompter,
            nativeViewFactory: nativeViewFactory
        )
        iOSAppComponent.telemetry.initialize()
        // Force eager initialization of lifecycle observer
        _ = iOSAppComponent.appEventDispatcher
    }
    
    var body: some Scene {
        WindowGroup {
            RootComposeView(
                appComponent: iOSAppComponent,
                nativeViewFactory: nativeViewFactory
            )
            .onOpenURL { url in
                // Forward URLs from custom-scheme links and Universal Links
                // into the Kotlin DeepLinkBridge — App.kt collects from it
                // and calls navController.handleDeepLink.
                iOSAppComponent.deepLinkBridge.emit(url: url.absoluteString)
            }
        }
    }
}

struct RootComposeView: View {
    @Environment(\.scenePhase) private var scenePhase
    let appComponent: IosAppComponent
    let nativeViewFactory: VirtuNativeViewFactory

    var body: some View {
        ComposeView(
            appComponent: appComponent,
            nativeViewFactory: nativeViewFactory
        )
        .ignoresSafeArea()
    }
}

struct ComposeView: UIViewControllerRepresentable {
    let appComponent: IosAppComponent
    let nativeViewFactory: VirtuNativeViewFactory

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(
            appComponent: appComponent
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

