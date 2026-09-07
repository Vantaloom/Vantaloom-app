import SwiftUI

/// 启动页：运行时起来之前的那一两秒。文案来自 RuntimeHost.progressMessage。
struct LaunchView: View {
    let message: String

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Text("Vantaloom")
                .font(.system(size: 34, weight: .semibold, design: .rounded))
            ProgressView()
                .controlSize(.large)
            Text(message)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// 占位 / 错误页。`retry` 为 nil 时不渲染重试按钮（「运行时未打包」这种结构性
/// 不可用不该给一颗点了也没用的按钮——置灰与不渲染的判据见 CLAUDE.md 前端约定）。
struct RuntimeUnavailableView: View {
    let title: String
    let reason: String
    let detail: String
    let retry: (() -> Void)?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(title)
                    .font(.title2.weight(.semibold))
                Text(reason)
                    .font(.body)
                    .textSelection(.enabled)
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if let retry {
                    Button("重试", action: retry)
                        .buttonStyle(.borderedProminent)
                        .padding(.top, 8)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(24)
        }
    }
}
