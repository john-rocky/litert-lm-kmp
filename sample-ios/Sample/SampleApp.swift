import SampleShared
import SwiftUI

@main
struct SampleApp: App {
  init() {
    IosKt.startDeviceCheckIfRequested(arguments: CommandLine.arguments)
  }

  var body: some Scene {
    WindowGroup {
      // Compose applies the safe area itself.
      RootView()
        .ignoresSafeArea()
        // The screen stays on for a download and a long answer.
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
    }
  }
}

private struct RootView: UIViewControllerRepresentable {
  func makeUIViewController(context: Context) -> UIViewController {
    IosKt.MainViewController()
  }

  func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
