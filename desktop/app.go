package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net"
	"net/http"
	"sync"
	"time"

	wruntime "github.com/wailsapp/wails/v2/pkg/runtime"

	rt "vantaloom.local/apps/desktop/internal/runtime"
)

// bootEvent is the event name the splash frontend listens on for live progress.
const bootEvent = "vantaloom:boot"

// updatePromptEvent asks the splash to show the in-page update modal. We use an
// HTML modal (not wruntime.MessageDialog) because the native Windows dialog only
// offers yes/no and ignores custom button labels, so the user's choice was
// effectively lost ("选啥都没用").
const updatePromptEvent = "vantaloom:update-prompt"

// App is the Wails-bound application object. Its exported methods are callable
// from the splash UI as window.go.main.App.<Method>().
type App struct {
	ctx context.Context
	mgr *rt.Manager

	// updateAnswer carries the user's choice from the in-page update modal back
	// to a blocked Bootstrap. Guarded by updateMu (a fresh channel per prompt).
	updateMu     sync.Mutex
	updateAnswer chan bool

	// monitorOnce ensures the background health monitor starts at most once.
	monitorOnce sync.Once
	// lastVersion is the version observed after Bootstrap, used by the monitor.
	lastVersion string

	// ctlPort/ctlToken describe the local window-control HTTP endpoint (see
	// startCtl). Zero port = the ctl server failed to start.
	ctlPort  int
	ctlToken string
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
	a.startCtl()
}

// ── Window-control HTTP endpoint ─────────────────────────────────────────────
//
// The webview navigates away from the Wails asset origin to the local app
// (http://127.0.0.1:<apiPort>), where Wails does NOT inject window.go /
// window.runtime — they only exist on pages served by the Wails asset server.
// The in-app frameless title bar therefore drives the window over a tiny
// localhost HTTP endpoint instead: the shell appends ?vtlshell=1&vtlport=…&
// vtltoken=… to the app URL, and the frontend fetches /minimise etc. Window
// DRAG can't go over HTTP; the frontend posts the raw "drag" message on the
// native webview channel (chrome.webview.postMessage / webkit.messageHandlers.
// external), which is exactly how the official Wails runtime implements
// --wails-draggable under the hood.

// startCtl starts the loopback window-control server on a random port with a
// per-launch token. Best-effort: on failure the app still works, the in-app
// title bar just loses min/max/close (drag is independent of this server).
func (a *App) startCtl() {
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err != nil {
		fmt.Printf("[desktop] ctl token: %v\n", err)
		return
	}
	token := hex.EncodeToString(buf)

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		fmt.Printf("[desktop] ctl listen: %v\n", err)
		return
	}

	mux := http.NewServeMux()
	action := func(path string, fn func()) {
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Access-Control-Allow-Origin", "*")
			if r.URL.Query().Get("t") != token {
				w.WriteHeader(http.StatusForbidden)
				return
			}
			fn()
			w.WriteHeader(http.StatusNoContent)
		})
	}
	action("/minimise", func() { wruntime.WindowMinimise(a.ctx) })
	action("/toggle-maximise", func() { wruntime.WindowToggleMaximise(a.ctx) })
	action("/close", func() { wruntime.Quit(a.ctx) })
	// /drag and /resize hand the pointer interaction to the OS via
	// WM_NCLBUTTONDOWN (see winctl_windows.go) — native move/size loops with
	// Aero Snap. Windows-only; macOS drags via the webview "drag" message.
	action("/drag", func() { _ = startNativeDrag() })
	mux.HandleFunc("/resize", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		if r.URL.Query().Get("t") != token {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		if err := startNativeResize(r.URL.Query().Get("edge")); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})
	// /capture lets the in-app 截图 button work again (its old window.go path
	// was dead on the external origin for the same injection reason).
	mux.HandleFunc("/capture", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		if r.URL.Query().Get("t") != token {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		b64, err := captureMainWindow()
		if err != nil {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "text/plain")
		_, _ = w.Write([]byte(b64))
	})

	a.ctlPort = ln.Addr().(*net.TCPAddr).Port
	a.ctlToken = token
	go func() { _ = http.Serve(ln, mux) }()
}

// appURL is the URL the webview loads after Bootstrap: the local app plus the
// desktop-shell markers the in-app title bar detects.
func (a *App) appURL() string {
	base := a.mgr.BackendURL()
	if a.ctlPort == 0 {
		return base + "?vtlshell=1"
	}
	return fmt.Sprintf("%s?vtlshell=1&vtlport=%d&vtltoken=%s", base, a.ctlPort, a.ctlToken)
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

// CaptureWebview returns a base64-encoded PNG of the app window so the frontend
// can offer a "snapshot the page" affordance now that the bundled Obscura
// headless browser engine can't itself produce screenshots.
//
// On Windows it BitBlts the window's client area from the screen DC (pure Win32
// syscalls — no cgo/deps), capturing the actual rendered WebView2 pixels
// including the cross-origin <iframe> preview. macOS/Linux report unsupported
// (see screenshot_other.go) and the frontend falls back (hides the snapshot
// button). Bound to the UI as window.go.main.App.CaptureWebview().
func (a *App) CaptureWebview() (string, error) {
	return captureMainWindow()
}

// Bootstrap detects the local runtime and brings it to a ready state, emitting
// progress events as it goes, then returns the URL the webview should load.
//
//	not installed         → install latest, start
//	installed, stopped    → auto-update if a newer version is reachable, then start
//	already running        → return immediately
//
// The whole flow is unprivileged except for the one-time legacy mesh-service
// cleanup (see rt.Manager.uninstallLegacyMeshOnce), which self-gates behind a
// done-marker and never blocks on a declined elevation prompt.
func (a *App) Bootstrap() (string, error) {
	emit := func(phase, msg string, pct int) {
		wruntime.EventsEmit(a.ctx, bootEvent, rt.Progress{Phase: phase, Message: msg, Percent: pct})
	}

	ctx, cancel := context.WithTimeout(a.ctx, 12*time.Minute)
	defer cancel()

	// Ensure a usable Node.js exists before booting the runtime: the launcher
	// scripts shell out to the system `node`. Auto-installs a
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
			a.lastVersion = st.RunningVersion
			url := a.appURL()
			a.startMonitor()
			return url, nil
		}

		// Ask via an in-page modal rendered by the splash (see updatePromptEvent).
		if !a.askUpdatePrompt(ctx, latest) {
			emit("ready", "后端已在运行（本次跳过更新）", 100)
			a.lastVersion = st.RunningVersion
			url := a.appURL()
			a.startMonitor()
			return url, nil
		}

		// User confirmed no agent is running: stop the runtime, then update.
		emit("update", "正在停止当前服务…", 2)
		if err := a.mgr.Stop(ctx); err != nil {
			// Can't stop cleanly — don't strand the user; hand off to the running app.
			emit("ready", "无法停止当前服务，已跳过本次更新", 100)
			a.lastVersion = st.RunningVersion
			url := a.appURL()
			a.startMonitor()
			return url, nil
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

	// Record the running version for the health monitor.
	if v, ok := a.mgr.Health(a.ctx); ok {
		a.lastVersion = v
	}

	emit("ready", "就绪", 100)
	url := a.appURL()
	a.startMonitor()
	return url, nil
}

// startMonitor launches the background health monitor (at most once).
func (a *App) startMonitor() {
	a.monitorOnce.Do(func() {
		go a.monitorBackend()
	})
}

// monitorBackend polls the backend health endpoint. When the backend restarts
// with a different version (e.g. after a CLI-driven update), it force-reloads
// the webview so the user gets the new frontend without restarting the shell.
// When the backend goes down (during an update), it injects a lightweight
// overlay so the user sees progress instead of a broken page.
func (a *App) monitorBackend() {
	downSince := time.Time{}
	overlayShown := false

	for {
		select {
		case <-a.ctx.Done():
			return
		case <-time.After(2 * time.Second):
		}

		v, ok := a.mgr.Health(a.ctx)
		if !ok {
			if downSince.IsZero() {
				downSince = time.Now()
			}
			// Show an overlay after the backend has been down for 4+ seconds
			// (ignore brief healthcheck blips during normal operation).
			if !overlayShown && time.Since(downSince) > 4*time.Second {
				overlayShown = true
				wruntime.WindowExecJS(a.ctx, updateOverlayJS)
			}
			continue
		}

		// Backend is up.
		if overlayShown {
			overlayShown = false
			// Backend came back — force reload to pick up new frontend.
			wruntime.WindowExecJS(a.ctx, "window.__vtlReloadApp ? window.__vtlReloadApp() : window.location.reload()")
			a.lastVersion = v
			downSince = time.Time{}
			continue
		}

		if a.lastVersion != "" && v != "" && v != a.lastVersion {
			// Version changed without downtime (hot restart). Reload.
			a.lastVersion = v
			wruntime.WindowExecJS(a.ctx, "window.__vtlReloadApp ? window.__vtlReloadApp() : window.location.reload()")
		}
		downSince = time.Time{}
	}
}

// updateOverlayJS injects a full-screen overlay when the backend goes down
// during an update, matching the Vantaloom splash aesthetic.
const updateOverlayJS = `(function(){
  if(document.getElementById('__vt_update_overlay')) return;
  var d=document.createElement('div');
  d.id='__vt_update_overlay';
  d.style.cssText='position:fixed;inset:0;z-index:99999;display:flex;align-items:center;justify-content:center;flex-direction:column;background:rgba(11,13,20,0.92);backdrop-filter:blur(12px);font-family:-apple-system,BlinkMacSystemFont,Segoe UI,PingFang SC,Microsoft YaHei,system-ui,sans-serif;color:#f3f5fb;';
  d.innerHTML='<div style="width:48px;height:48px;border-radius:14px;background:linear-gradient(135deg,#3ad0c8,#8b7bf0);display:flex;align-items:center;justify-content:center;font-size:24px;font-weight:700;color:#0b0d14;margin-bottom:18px">V</div><div style="font-size:15px;font-weight:600;margin-bottom:8px">正在更新 Vantaloom</div><div style="font-size:13px;color:#8b93a7;margin-bottom:20px">后端服务重启中，完成后自动刷新…</div><div style="width:180px;height:4px;border-radius:99px;background:rgba(255,255,255,0.08);overflow:hidden"><div style="width:35%;height:100%;border-radius:99px;background:linear-gradient(90deg,#3ad0c8,#8b7bf0);animation:__vt_slide 1.1s ease-in-out infinite"></div></div><style>@keyframes __vt_slide{0%{margin-left:-35%}100%{margin-left:100%}}</style>';
  document.body.appendChild(d);
})();`

// askUpdatePrompt asks the splash to render the in-page update modal and blocks
// until the user answers via AnswerUpdatePrompt — true = stop+update+restart,
// false = skip this launch. A done context or a generous timeout defaults to
// skipping so a dismissed/stuck modal never strands the user.
func (a *App) askUpdatePrompt(ctx context.Context, latest string) bool {
	ch := make(chan bool, 1)
	a.updateMu.Lock()
	a.updateAnswer = ch
	a.updateMu.Unlock()
	defer func() {
		a.updateMu.Lock()
		a.updateAnswer = nil
		a.updateMu.Unlock()
	}()

	wruntime.EventsEmit(a.ctx, updatePromptEvent, map[string]any{"latest": latest})

	select {
	case proceed := <-ch:
		return proceed
	case <-ctx.Done():
		return false
	case <-time.After(10 * time.Minute):
		return false
	}
}

// ── Window controls (frameless title bar) ───────────────────────────────────

// Minimise minimises the window. Bound as window.go.main.App.Minimise().
func (a *App) Minimise() { wruntime.WindowMinimise(a.ctx) }

// ToggleMaximise toggles maximise/restore. Bound as window.go.main.App.ToggleMaximise().
func (a *App) ToggleMaximise() { wruntime.WindowToggleMaximise(a.ctx) }

// CloseWindow closes the app. Bound as window.go.main.App.CloseWindow().
func (a *App) CloseWindow() { wruntime.Quit(a.ctx) }

// AnswerUpdatePrompt is invoked from the splash modal with the user's choice:
// true = 结束并更新 (stop + update + restart now), false = 忽略本次更新 (skip).
// Bound to the UI as window.go.main.App.AnswerUpdatePrompt(bool).
func (a *App) AnswerUpdatePrompt(proceed bool) {
	a.updateMu.Lock()
	ch := a.updateAnswer
	a.updateMu.Unlock()
	if ch == nil {
		return
	}
	select {
	case ch <- proceed:
	default:
	}
}
