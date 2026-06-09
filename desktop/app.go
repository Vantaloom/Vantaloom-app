package main

import (
	"context"
	"fmt"
	"time"

	wruntime "github.com/wailsapp/wails/v2/pkg/runtime"

	rt "vantaloom.local/apps/desktop/internal/runtime"
)

// bootEvent is the event name the splash frontend listens on for live progress.
const bootEvent = "vantaloom:boot"

// App is the Wails-bound application object. Its exported methods are callable
// from the splash UI as window.go.main.App.<Method>().
type App struct {
	ctx context.Context
	mgr *rt.Manager
}

// NewApp builds the App with a runtime Manager bound to the default install
// prefix (overridable by VANTALOOM_HOME) and the public npm registry.
func NewApp() *App {
	return &App{mgr: rt.New("", "")}
}

// startup is wired to Wails OnStartup; it captures the runtime context used for
// emitting events and for cancelling work when the window closes.
func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
}

// GetStatus returns the current install/run snapshot (for diagnostics in the UI).
func (a *App) GetStatus() rt.Status {
	return a.mgr.Status(a.ctx)
}

// OpenInBrowser opens a URL in the user's default browser — a fallback offered
// on the splash if in-window navigation is undesired.
func (a *App) OpenInBrowser(url string) {
	wruntime.BrowserOpenURL(a.ctx, url)
}

// Bootstrap detects the local runtime and brings it to a ready state, emitting
// progress events as it goes, then returns the URL the webview should load.
//
//	not installed         → install latest, start
//	installed, stopped    → auto-update if a newer version is reachable, then start
//	already running        → return immediately
//
// The whole flow is unprivileged (mesh registration is deferred to the CLI).
func (a *App) Bootstrap() (string, error) {
	emit := func(phase, msg string, pct int) {
		wruntime.EventsEmit(a.ctx, bootEvent, rt.Progress{Phase: phase, Message: msg, Percent: pct})
	}

	ctx, cancel := context.WithTimeout(a.ctx, 12*time.Minute)
	defer cancel()

	// Ensure a usable Node.js exists before booting the runtime: the launcher
	// scripts and ACP adapters shell out to the system `node`. Auto-installs a
	// pinned LTS from a China mirror if absent. Non-fatal — boot proceeds with
	// whatever node may already be present on failure.
	nodeCtx, cancelNode := context.WithTimeout(ctx, 3*time.Minute)
	if _, err := rt.EnsureNode(nodeCtx); err != nil {
		fmt.Printf("[desktop] Node.js 检测/安装失败（继续启动）: %v\n", err)
	}
	cancelNode()

	st := a.mgr.Status(ctx)

	if st.Running {
		// Backend already up. We never silently hot-update a running backend (an
		// active agent session would be killed mid-run) — but we also don't
		// silently skip. If a newer version is reachable, ASK the user: they know
		// whether an agent is currently running. If one is, they defer to the next
		// open; if not, they can stop + update + restart right now.
		emit("check", "正在检查更新…", 1)
		checkCtx, cancelCheck := context.WithTimeout(ctx, 6*time.Second)
		available, latest, checkErr := a.mgr.UpdateAvailable(checkCtx)
		cancelCheck()

		if checkErr != nil || !available {
			// No update (or offline) — hand straight off to the live app.
			emit("ready", "后端已在运行", 100)
			return a.mgr.BackendURL(), nil
		}

		choice, _ := wruntime.MessageDialog(a.ctx, wruntime.MessageDialogOptions{
			Type:    wruntime.QuestionDialog,
			Title:   "发现新版本 " + latest,
			Message: "Vantaloom 仍在运行。请先确认当前是否有 agent 正在运行：\n\n" +
				"• 有正在运行的 agent → 选择「忽略本次更新」，本次跳过，更新留到下次打开。\n" +
				"• 没有 agent 在运行 → 选择「结束并更新」，将先停止当前服务，更新后自动重启。",
			Buttons:       []string{"忽略本次更新", "结束并更新"},
			DefaultButton: "忽略本次更新",
			CancelButton:  "忽略本次更新",
		})
		if choice != "结束并更新" {
			emit("ready", "后端已在运行（本次跳过更新）", 100)
			return a.mgr.BackendURL(), nil
		}

		// User confirmed no agent is running: stop the runtime, then update.
		emit("update", "正在停止当前服务…", 2)
		if err := a.mgr.Stop(ctx); err != nil {
			// Can't stop cleanly — don't strand the user; hand off to the running app.
			emit("ready", "无法停止当前服务，已跳过本次更新", 100)
			return a.mgr.BackendURL(), nil
		}
		emit("update", fmt.Sprintf("正在更新到 %s…", latest), 3)
		if _, err := a.mgr.Install(ctx, "latest", func(p rt.Progress) {
			wruntime.EventsEmit(a.ctx, bootEvent, p)
		}); err != nil {
			// Update failed after stopping — start what's already installed so the
			// user isn't left with a stopped backend.
			emit("start", "更新未完成，正在启动已安装版本…", 88)
			if e := a.mgr.Start(ctx); e != nil {
				return "", e
			}
		}
		// Install() starts the runtime; fall through to the shared health wait.
	} else {
		switch {
		case !st.Installed:
			emit("install", "首次运行：正在安装 Vantaloom 核心服务…", 0)
			if _, err := a.mgr.Install(ctx, "latest", func(p rt.Progress) {
				wruntime.EventsEmit(a.ctx, bootEvent, p)
			}); err != nil {
				return "", err
			}

		default:
			// Installed but stopped. Try an auto-update, but cap the registry check
			// so an offline launch falls back quickly to the installed version.
			emit("check", "正在检查更新…", 1)
			checkCtx, cancelCheck := context.WithTimeout(ctx, 6*time.Second)
			available, latest, checkErr := a.mgr.UpdateAvailable(checkCtx)
			cancelCheck()

			if checkErr == nil && available {
				emit("update", fmt.Sprintf("发现新版本 %s，正在更新…", latest), 2)
				if _, err := a.mgr.Install(ctx, "latest", func(p rt.Progress) {
					wruntime.EventsEmit(a.ctx, bootEvent, p)
				}); err != nil {
					// Update failed mid-flight — don't strand the user; start what's
					// already installed.
					emit("start", "更新未完成，正在启动已安装版本…", 88)
					if e := a.mgr.Start(ctx); e != nil {
						return "", e
					}
				}
			} else {
				emit("start", "正在启动后端服务…", 88)
				if err := a.mgr.Start(ctx); err != nil {
					return "", err
				}
			}
		}
	}

	emit("wait", "正在等待后端就绪…", 95)
	if err := a.mgr.WaitHealthy(ctx, 90*time.Second); err != nil {
		return "", err
	}
	emit("ready", "就绪", 100)
	return a.mgr.BackendURL(), nil
}
