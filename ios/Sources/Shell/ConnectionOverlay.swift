import SwiftUI

/// 回前台重连期间盖在 WebView 底部的状态卡。控制端最重要的体验：切后台约 30s 后
/// 进程被挂起，QUIC 会话必断；回前台时页面自己不知道（fetch 只会开始报错），
/// 这里把「正在重新连接 / 连接中断 + 原因」明明白白显示出来，并给两颗逃生按钮。
///
/// 只在 `host.resumePending` 为 true 时出现（回前台且已有目标机器），拨通即消失；
/// 不与前端连接页自己的「正在连接执行机…」画面打架。
struct ConnectionOverlay: View {
    @EnvironmentObject private var host: ControllerHost

    var body: some View {
        let status = host.status
        if host.resumePending, status.hasTarget, status.isConnecting || status.isError {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    if status.isConnecting {
                        ProgressView().controlSize(.small)
                        Text("正在重新连接 \(status.target ?? "")…")
                            .font(.subheadline.weight(.medium))
                    } else {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                        Text("与执行机的连接已中断")
                            .font(.subheadline.weight(.medium))
                    }
                    Spacer(minLength: 0)
                }
                if status.isError, let error = status.error, !error.isEmpty {
                    ScrollView {
                        Text(error)
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 140)
                }
                if let link = status.link, !link.connected {
                    Text("账号服务（Hub）信令未连接" + ((link.lastError ?? "").isEmpty ? "" : "：\(link.lastError ?? "")"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if status.isError {
                    HStack(spacing: 10) {
                        Button("重试") { host.retryConnect() }
                            .buttonStyle(.borderedProminent)
                        Button("重新选择机器") { host.backToPicker() }
                            .buttonStyle(.bordered)
                    }
                    .padding(.top, 2)
                }
            }
            .padding(14)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .padding(.horizontal, 12)
            .padding(.bottom, 12)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }
}
