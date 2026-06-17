package runtime

// Mesh-service registration for the desktop shell.
//
// The desktop's layDown mirrors only the file-laydown half of the CLI install;
// it deliberately drops the one privileged side-effect the CLI performs:
// registering/starting the EasyTier (P2P) sidecar service. vantaloomctl
// install/start only writes config + spawns api/agent/web, so without this the
// mesh never comes up under a desktop-exe install (P2P silently falls back to
// the Hub relay forever).
//
// This file restores that step, mirroring packages/cli/src/cli.mjs
// (ensureMeshService / meshServiceNeedsApply / elevateWindowsMeshApply /
// elevateDarwinMeshInstall / ensureLinuxMeshCapabilities), but in pure Go and
// fully best-effort/non-fatal.
//
// "Unchanged mesh → no UAC" property: the reproducible mesh build means the
// installed mesh binaries are byte-identical across routine updates. We record
// the sha256 of the installed mesh binaries in an applied-marker after each
// successful apply; ensureMeshService re-elevates ONLY when a mesh binary is
// missing, its hash changed since the last apply, or the OS service is not
// currently running. So a routine update that doesn't touch the mesh is a no-op
// and never raises a UAC / sudo prompt.

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"

	"vantaloom.local/apps/desktop/internal/winproc"
)

// meshAppliedMarker records the hash of the mesh binaries at the time of the
// last successful (elevated) apply, so an unchanged mesh skips re-elevation.
const meshAppliedMarker = "mesh-applied.json"

// meshExePath returns the installed vantaloom-mesh binary path ("" semantics via
// the caller's existence check).
func (m *Manager) meshExePath() string {
	return filepath.Join(m.Prefix, "bin", binaryName("vantaloom-mesh"))
}

// ensureMeshService registers (or lock-safely re-applies) the privileged
// EasyTier sidecar — the one elevation of the desktop install. On Windows/macOS
// it raises a UAC / auth prompt; on Linux it grants TUN capabilities via
// pkexec/sudo. It is gated so an unchanged mesh is a no-op (no prompt), and it
// never fails the install — on error it logs and returns (P2P falls back to the
// Hub relay), matching the CLI's non-fatal handling.
func (m *Manager) ensureMeshService(ctx context.Context) {
	binDir := filepath.Join(m.Prefix, "bin")

	if runtime.GOOS == "linux" {
		m.ensureLinuxMeshCapabilities(ctx, binDir)
		return
	}
	if runtime.GOOS != "windows" && runtime.GOOS != "darwin" {
		return
	}

	meshExe := m.meshExePath()
	if !fileExists(meshExe) {
		return // sidecar not bundled — nothing to register
	}
	if !m.meshServiceNeedsApply(binDir) {
		return // installed binaries current and service already up → no UAC
	}

	var err error
	switch runtime.GOOS {
	case "windows":
		err = m.elevateWindowsMeshApply(ctx, binDir)
	case "darwin":
		err = m.elevateDarwinMeshInstall(ctx, meshExe)
	}
	if err != nil {
		log.Printf("[mesh] 注册 P2P 服务失败（P2P 将回退到 Hub 中继）: %v", err)
		return
	}
	// Record the applied state so an unchanged mesh skips re-elevation next time.
	if e := m.writeMeshAppliedMarker(binDir); e != nil {
		log.Printf("[mesh] 写入 applied 标记失败（不影响本次注册）: %v", e)
	}
	log.Printf("[mesh] 已注册并启动 P2P 服务")
}

// meshBinaryNames returns the lockable mesh files to hash/compare for this
// platform (mirrors the CLI's meshBinarySet / WINDOWS_MESH_BINARIES).
func meshBinaryNames() []string {
	if runtime.GOOS == "windows" {
		return []string{"easytier-core.exe", "easytier-cli.exe", "wintun.dll",
			"Packet.dll", "WinDivert64.sys", "vantaloom-mesh.exe"}
	}
	return []string{binaryName("vantaloom-mesh"), binaryName("easytier-core")}
}

// meshServiceNeedsApply reports whether the privileged service must be
// (re)applied: true if any installed mesh binary is missing or its hash differs
// from the last applied marker, OR the OS service isn't currently running. This
// is what preserves "unchanged mesh → no UAC": when every present binary matches
// the marker and the service is up, it returns false and no prompt is raised.
func (m *Manager) meshServiceNeedsApply(binDir string) bool {
	marker := m.readMeshAppliedMarker()
	for _, name := range meshBinaryNames() {
		p := filepath.Join(binDir, name)
		sum, err := fileSha(p)
		if err != nil {
			// A required binary is missing on Windows (the full mesh set incl. DLLs)
			// → must apply. On macOS only mesh+core are required; a missing optional
			// is impossible here since the set is exactly those two.
			return true
		}
		if marker[name] != sum {
			return true // never applied, or this binary changed since last apply
		}
	}
	// Binaries match the last apply — only re-apply if the service isn't running.
	return !m.meshServiceRunning(binDir)
}

// meshServiceRunning queries OS service state via the UNPRIVILEGED mesh `status`
// command (mirrors the CLI's meshServiceRunning).
func (m *Manager) meshServiceRunning(binDir string) bool {
	exe := filepath.Join(binDir, binaryName("vantaloom-mesh"))
	if !fileExists(exe) {
		return false
	}
	cmd := exec.Command(exe, "status")
	winproc.Hide(cmd)
	out, err := cmd.CombinedOutput()
	if err != nil {
		// A nonzero exit from `status` is treated as "not running" on darwin (the
		// CLI keys off the status text), but on Windows we still inspect output.
		if runtime.GOOS == "darwin" {
			return false
		}
	}
	low := strings.ToLower(string(out))
	if runtime.GOOS == "windows" {
		return strings.Contains(low, "running")
	}
	return err == nil && !strings.Contains(low, "not loaded") && !strings.Contains(low, "not installed")
}

// elevateWindowsMeshApply runs the lock-safe `apply` elevated via a single UAC
// prompt (mirrors the CLI's elevateWindowsMeshApply). The desktop has no
// persisted staged package dir (the temp extract is deleted after layDown), so
// --pkg-bin points at the installed bin: on a fresh install that bin already
// holds the freshly-copied mesh binaries; on an update where the mesh was
// skipped it holds the current ones — so apply is correct either way.
func (m *Manager) elevateWindowsMeshApply(ctx context.Context, binDir string) error {
	exe := filepath.Join(binDir, "vantaloom-mesh.exe")
	argList := strings.Join([]string{
		psQuote("apply"),
		psQuote("--pkg-bin"), psQuote(binDir),
		psQuote("--install-dir"), psQuote(m.Prefix),
	}, ",")
	ps := fmt.Sprintf("$ErrorActionPreference='Stop'; $p = Start-Process -FilePath %s -ArgumentList %s -Verb RunAs -Wait -PassThru; exit $p.ExitCode",
		psQuote(exe), argList)
	cmd := exec.CommandContext(ctx, "powershell", "-NoProfile", "-NonInteractive", "-Command", ps)
	winproc.Hide(cmd)
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("powershell Start-Process RunAs apply: %v\n%s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

// elevateDarwinMeshInstall registers the LaunchDaemon with admin rights. A Wails
// GUI app has no controlling TTY, so a plain `sudo` can't prompt — we wrap it in
// osascript "with administrator privileges", which surfaces the macOS auth
// dialog (mirrors the CLI's intent of one auth prompt).
func (m *Manager) elevateDarwinMeshInstall(ctx context.Context, meshExe string) error {
	inner := fmt.Sprintf("%s install --install-dir %s",
		shQuote(meshExe), shQuote(m.Prefix))
	script := fmt.Sprintf("do shell script %s with administrator privileges", osaQuote(inner))
	cmd := exec.CommandContext(ctx, "osascript", "-e", script)
	winproc.Hide(cmd)
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("osascript install: %v\n%s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

// ensureLinuxMeshCapabilities grants easytier-core the TUN capabilities so the
// unprivileged runtime can bring up the mesh (mirrors the CLI). It only elevates
// when the capability is missing (getcap check), preserving "unchanged → no
// prompt". Best-effort: a missing setcap / cancelled auth just logs.
func (m *Manager) ensureLinuxMeshCapabilities(ctx context.Context, binDir string) {
	core := filepath.Join(binDir, "easytier-core")
	if !fileExists(core) {
		return
	}
	const caps = "cap_net_admin,cap_net_bind_service+ep"

	// Already capable? (we may have kept an unchanged easytier-core in place) →
	// nothing to do, and crucially no sudo prompt.
	if out, err := exec.CommandContext(ctx, "getcap", core).CombinedOutput(); err == nil {
		s := string(out)
		if strings.Contains(s, "cap_net_admin") && strings.Contains(s, "cap_net_bind_service") {
			return
		}
	}

	if os.Geteuid() == 0 {
		if err := exec.CommandContext(ctx, "setcap", caps, core).Run(); err != nil {
			log.Printf("[mesh] setcap 失败（P2P 将回退到 Hub 中继）: %v", err)
		}
		return
	}
	// Non-root GUI context: prefer pkexec (graphical auth), fall back to sudo.
	var err error
	if _, e := exec.LookPath("pkexec"); e == nil {
		err = exec.CommandContext(ctx, "pkexec", "setcap", caps, core).Run()
	} else {
		err = exec.CommandContext(ctx, "sudo", "setcap", caps, core).Run()
	}
	if err != nil {
		log.Printf("[mesh] 授予 TUN 能力失败（P2P 将回退到 Hub 中继）: 手动执行  sudo setcap %s %s", caps, core)
	}
}

// ── applied-marker (hash) helpers ──

func (m *Manager) meshMarkerPath() string {
	return filepath.Join(m.Prefix, "runtime", meshAppliedMarker)
}

// readMeshAppliedMarker returns name→sha256 from the last successful apply ({} on
// any error, which forces an apply).
func (m *Manager) readMeshAppliedMarker() map[string]string {
	b, err := os.ReadFile(m.meshMarkerPath())
	if err != nil {
		return map[string]string{}
	}
	var out map[string]string
	if json.Unmarshal(b, &out) != nil || out == nil {
		return map[string]string{}
	}
	return out
}

// writeMeshAppliedMarker records the current hash of each present mesh binary.
func (m *Manager) writeMeshAppliedMarker(binDir string) error {
	cur := map[string]string{}
	var names []string
	for _, name := range meshBinaryNames() {
		if sum, err := fileSha(filepath.Join(binDir, name)); err == nil {
			cur[name] = sum
			names = append(names, name)
		}
	}
	sort.Strings(names) // deterministic marker content
	b, err := json.MarshalIndent(cur, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(m.meshMarkerPath()), 0o755); err != nil {
		return err
	}
	return os.WriteFile(m.meshMarkerPath(), append(b, '\n'), 0o644)
}

// fileSha returns the sha256 hex of a file.
func fileSha(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// ── shell-quoting helpers ──

// psQuote wraps a value as a PowerShell single-quoted string literal (mirrors
// the CLI's psQuote).
func psQuote(value string) string {
	return "'" + strings.ReplaceAll(value, "'", "''") + "'"
}

// shQuote wraps a value as a POSIX single-quoted shell literal.
func shQuote(value string) string {
	return "'" + strings.ReplaceAll(value, "'", `'\''`) + "'"
}

// osaQuote wraps a value as an AppleScript double-quoted string literal (used as
// the argument to `do shell script`).
func osaQuote(value string) string {
	value = strings.ReplaceAll(value, `\`, `\\`)
	value = strings.ReplaceAll(value, `"`, `\"`)
	return `"` + value + `"`
}
