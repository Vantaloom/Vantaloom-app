// Package runtime detects, installs, updates, and supervises the local
// Vantaloom backend runtime (vantaloom-api / vantaloom-agent driven by
// vantaloomctl) that the desktop shell wraps.
//
// It deliberately mirrors the install logic of packages/cli
// (syncFromNpmRegistry + applyPackage) but in pure Go, so the desktop shell
// needs no Node.js on the end user's machine: the only external dependency is
// the runtime's own Go binary, vantaloomctl, which we invoke directly.
//
// This package is intentionally free of cgo and of any Wails dependency so it
// can be built and unit-tested with the plain Go toolchain.
package runtime

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"

	"vantaloom.local/apps/desktop/internal/winproc"
)

const (
	// DefaultAPIPort is the port vantaloom-api binds by default. The real port
	// (which may differ if 8780 was taken) is recorded in runtime/api.port.
	DefaultAPIPort = 8780

	defaultRegistry = "https://registry.npmjs.org"
	defaultRepo     = "Timefiles404/Vantaloom-next"
	defaultRelease  = "runtime-latest"

	healthTimeout = 2 * time.Second
)

// Status is a snapshot of the local runtime's state, suitable for returning to
// the frontend as JSON.
type Status struct {
	Prefix           string `json:"prefix"`
	Installed        bool   `json:"installed"`
	InstalledVersion string `json:"installedVersion"`
	Running          bool   `json:"running"`
	RunningVersion   string `json:"runningVersion"`
	APIPort          int    `json:"apiPort"`
}

// Progress is emitted during install/update so the UI can render a live bar.
type Progress struct {
	Phase   string `json:"phase"`
	Message string `json:"message"`
	Percent int    `json:"percent"`
}

// Manager drives a single install prefix.
type Manager struct {
	Prefix   string
	Registry string
	HTTP     *http.Client
}

// New builds a Manager. Empty prefix/registry fall back to the same defaults
// the CLI uses, so the shell and the CLI always agree on the install location.
func New(prefix, registry string) *Manager {
	if prefix == "" {
		prefix = DefaultPrefix()
	}
	if registry == "" {
		registry = defaultRegistry
	}
	return &Manager{
		Prefix:   prefix,
		Registry: strings.TrimRight(registry, "/"),
		HTTP:     &http.Client{},
	}
}

// platformID maps Go's GOOS/GOARCH to the npm runtime package platform id used
// by @vantaloom/runtime-<platform> (e.g. win32-x64, darwin-arm64, linux-x64).
func platformID(goos, goarch string) string {
	osName := map[string]string{"windows": "win32", "darwin": "darwin", "linux": "linux"}[goos]
	if osName == "" {
		osName = goos
	}
	arch := map[string]string{"amd64": "x64", "arm64": "arm64"}[goarch]
	if arch == "" {
		arch = goarch
	}
	return osName + "-" + arch
}

// PlatformID returns the npm platform id for the current build target.
func PlatformID() string { return platformID(runtime.GOOS, runtime.GOARCH) }

// RuntimePackageName returns the npm package that ships this platform's runtime.
func RuntimePackageName() string { return "@vantaloom/runtime-" + PlatformID() }

// DefaultPrefix mirrors the CLI's defaultPrefix(): VANTALOOM_HOME wins, then a
// per-platform default.
func DefaultPrefix() string {
	if h := os.Getenv("VANTALOOM_HOME"); h != "" {
		return h
	}
	switch runtime.GOOS {
	case "windows":
		// Prefer D: (the historical default) but fall back to C: when this
		// machine has no D: drive. Must stay in sync with the CLI's
		// defaultPrefix() so the shell and CLI agree on the location.
		if _, err := os.Stat(`D:\`); err == nil {
			return `D:\Vantaloom`
		}
		return `C:\Vantaloom`
	case "darwin":
		home, _ := os.UserHomeDir()
		return filepath.Join(home, "Applications", "Vantaloom")
	default:
		home, _ := os.UserHomeDir()
		return filepath.Join(home, ".local", "vantaloom")
	}
}

func binaryName(name string) string {
	if runtime.GOOS == "windows" {
		return name + ".exe"
	}
	return name
}

// meshBinaries are the privileged P2P (EasyTier) files held open by the mesh
// service. On update we leave any already-installed copy in place — overwriting
// them would require elevation (and break a running service); a no-op keeps the
// auto-install/update fully unprivileged, exactly like the CLI's "unchanged
// mesh → no UAC" path. Newly-installed prefixes still receive them.
func meshBinaries() map[string]bool {
	if runtime.GOOS == "windows" {
		return set("easytier-core.exe", "easytier-cli.exe", "wintun.dll",
			"Packet.dll", "WinDivert64.sys", "vantaloom-mesh.exe")
	}
	return set("vantaloom-mesh", "easytier-core")
}

func set(items ...string) map[string]bool {
	m := make(map[string]bool, len(items))
	for _, it := range items {
		m[it] = true
	}
	return m
}

func (m *Manager) ctlPath() string {
	return filepath.Join(m.Prefix, "bin", binaryName("vantaloomctl"))
}

// Installed reports whether a runtime is laid down at the prefix.
func (m *Manager) Installed() bool {
	_, err := os.Stat(m.ctlPath())
	return err == nil
}

// InstalledVersion reads the prefix VERSION file ("" if absent).
func (m *Manager) InstalledVersion() string {
	b, err := os.ReadFile(filepath.Join(m.Prefix, "VERSION"))
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

// APIPort returns the port the api actually bound (runtime/api.port), falling
// back to the default.
func (m *Manager) APIPort() int {
	b, err := os.ReadFile(filepath.Join(m.Prefix, "runtime", "api.port"))
	if err == nil {
		if p, e := strconv.Atoi(strings.TrimSpace(string(b))); e == nil && p > 0 {
			return p
		}
	}
	return DefaultAPIPort
}

// BackendURL is where the running web app is served.
func (m *Manager) BackendURL() string {
	return fmt.Sprintf("http://127.0.0.1:%d/", m.APIPort())
}

// health probes GET /healthz on the api port and returns its reported version.
func (m *Manager) health(ctx context.Context) (version string, ok bool) {
	url := fmt.Sprintf("http://127.0.0.1:%d/healthz", m.APIPort())
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", false
	}
	client := &http.Client{Timeout: healthTimeout}
	resp, err := client.Do(req)
	if err != nil {
		return "", false
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", false
	}
	var body struct {
		OK      bool   `json:"ok"`
		Version string `json:"version"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&body)
	return body.Version, body.OK
}

// Status snapshots install + run state.
func (m *Manager) Status(ctx context.Context) Status {
	s := Status{Prefix: m.Prefix, APIPort: m.APIPort()}
	s.Installed = m.Installed()
	s.InstalledVersion = m.InstalledVersion()
	v, ok := m.health(ctx)
	s.Running = ok
	s.RunningVersion = v
	return s
}

type resolved struct {
	Version string
	Tarball string
}

// resolve fetches the npm packument (abbreviated metadata) and selects a
// version, resolving a dist-tag like "latest" to a concrete version + tarball.
func (m *Manager) resolve(ctx context.Context, name, version string) (resolved, error) {
	// npm requires the "/" in the scope to be percent-encoded.
	metaURL := m.Registry + "/" + strings.Replace(name, "/", "%2f", 1)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, metaURL, nil)
	if err != nil {
		return resolved{}, err
	}
	req.Header.Set("Accept", "application/vnd.npm.install-v1+json")
	req.Header.Set("User-Agent", "vantaloom-desktop")
	resp, err := m.HTTP.Do(req)
	if err != nil {
		return resolved{}, fmt.Errorf("cannot reach registry %s: %w", m.Registry, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return resolved{}, fmt.Errorf("inspect %s: HTTP %d", name, resp.StatusCode)
	}
	var meta struct {
		DistTags map[string]string `json:"dist-tags"`
		Versions map[string]struct {
			Dist struct {
				Tarball string `json:"tarball"`
			} `json:"dist"`
		} `json:"versions"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&meta); err != nil {
		return resolved{}, fmt.Errorf("parse %s metadata: %w", name, err)
	}
	selected := version
	if tag, ok := meta.DistTags[version]; ok {
		selected = tag
	}
	pv, ok := meta.Versions[selected]
	if !ok || pv.Dist.Tarball == "" {
		return resolved{}, fmt.Errorf("missing %s@%s in registry", name, version)
	}
	return resolved{Version: selected, Tarball: pv.Dist.Tarball}, nil
}

// LatestVersion returns the registry's "latest" version for this platform.
func (m *Manager) LatestVersion(ctx context.Context) (string, error) {
	r, err := m.resolve(ctx, RuntimePackageName(), "latest")
	if err != nil {
		return "", err
	}
	return r.Version, nil
}

// UpdateAvailable compares the installed version against "latest". Like the
// CLI, this is an exact mismatch check rather than a semver comparison.
func (m *Manager) UpdateAvailable(ctx context.Context) (available bool, latest string, err error) {
	latest, err = m.LatestVersion(ctx)
	if err != nil {
		return false, "", err
	}
	installed := m.InstalledVersion()
	return installed != "" && installed != latest, latest, nil
}

// Install lays down (or updates to) the requested version of the runtime and
// starts it. version "" means "latest". progress may be nil.
func (m *Manager) Install(ctx context.Context, version string, progress func(Progress)) (string, error) {
	if version == "" {
		version = "latest"
	}
	report := func(phase, msg string, pct int) {
		if progress != nil {
			progress(Progress{Phase: phase, Message: msg, Percent: pct})
		}
	}

	report("resolve", "正在查询运行时版本…", 2)
	res, err := m.resolve(ctx, RuntimePackageName(), version)
	if err != nil {
		return "", fmt.Errorf("解析运行时包失败: %w", err)
	}

	tmp, err := os.MkdirTemp("", "vantaloom-rt-")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(tmp)

	report("download", fmt.Sprintf("正在下载运行时 %s…", res.Version), 8)
	archive := filepath.Join(tmp, "runtime.tgz")
	if err := m.download(ctx, res.Tarball, archive, func(p int) {
		report("download", fmt.Sprintf("正在下载运行时 %s… %d%%", res.Version, p), 8+p*44/100)
	}); err != nil {
		return "", fmt.Errorf("下载运行时失败: %w", err)
	}

	report("extract", "正在解压运行时…", 54)
	extract := filepath.Join(tmp, "extract")
	if err := os.MkdirAll(extract, 0o755); err != nil {
		return "", err
	}
	if err := extractTarGz(archive, extract); err != nil {
		return "", fmt.Errorf("解压失败: %w", err)
	}
	pkgRoot := filepath.Join(extract, "package")
	if !dirExists(pkgRoot) {
		if only, e := singleSubdir(extract); e == nil {
			pkgRoot = only
		}
	}
	if err := assertPackage(pkgRoot); err != nil {
		return "", err
	}
	ver := readPackageVersion(pkgRoot)

	report("install", "正在安装文件…", 60)
	if err := m.layDown(ctx, pkgRoot, ver, res); err != nil {
		return "", fmt.Errorf("写入安装文件失败: %w", err)
	}

	report("register", "正在注册本地服务…", 82)
	if err := m.runCtl(ctx, "install", "--prefix", m.Prefix, "--version", ver); err != nil {
		return "", fmt.Errorf("注册服务失败: %w", err)
	}

	report("start", "正在启动后端服务…", 90)
	if err := m.runCtl(ctx, "start", "--prefix", m.Prefix); err != nil {
		return "", fmt.Errorf("启动后端失败: %w", err)
	}

	report("done", "完成", 100)
	return ver, nil
}

// layDown copies bin/web/cli into the prefix and writes VERSION/manifest/config
// + launcher. It mirrors applyPackage's file placement but skips the privileged
// mesh-service registration (P2P falls back to the Hub relay until the user
// enables it via the CLI) so the operation never needs elevation.
func (m *Manager) layDown(ctx context.Context, pkgRoot, ver string, res resolved) error {
	if m.Installed() {
		// Free file locks before overwriting (best-effort, like the CLI).
		_ = m.runCtl(ctx, "stop", "--prefix", m.Prefix)
		m.killTray()
		if runtime.GOOS == "windows" {
			time.Sleep(800 * time.Millisecond)
		}
	}
	if err := os.MkdirAll(m.Prefix, 0o755); err != nil {
		return err
	}

	mesh := meshBinaries()
	for _, name := range []string{"bin", "web", "cli"} {
		src := filepath.Join(pkgRoot, name)
		dst := filepath.Join(m.Prefix, name)
		if !dirExists(src) {
			continue
		}
		if name == "bin" {
			// Never wipe bin/: overwrite in place, but skip a mesh binary that is
			// already installed (leave the running privileged service untouched).
			if err := copyTreeSkip(src, dst, func(rel string) bool {
				if !mesh[filepath.Base(rel)] {
					return false
				}
				_, err := os.Stat(filepath.Join(dst, rel))
				return err == nil
			}); err != nil {
				return err
			}
		} else {
			_ = os.RemoveAll(dst)
			if err := copyTree(src, dst); err != nil {
				return err
			}
		}
	}

	if runtime.GOOS != "windows" {
		binDir := filepath.Join(m.Prefix, "bin")
		if entries, err := os.ReadDir(binDir); err == nil {
			for _, e := range entries {
				_ = os.Chmod(filepath.Join(binDir, e.Name()), 0o755)
			}
		}
	}

	if err := writeLauncher(m.Prefix); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(m.Prefix, "VERSION"), []byte(ver+"\n"), 0o644); err != nil {
		return err
	}
	if err := copyFile(filepath.Join(pkgRoot, "manifest.json"), filepath.Join(m.Prefix, "manifest.json")); err != nil {
		return err
	}
	m.writeConfig(res)

	if !m.Installed() {
		return fmt.Errorf("vantaloomctl 未在安装后出现于 %s（下载可能损坏）", m.ctlPath())
	}
	return nil
}

// writeConfig persists cli/config.json so a later `vantaloom update` (or our own
// updater) keeps pointing at the right platform package and registry.
func (m *Manager) writeConfig(res resolved) {
	p := filepath.Join(m.Prefix, "cli", "config.json")
	cfg := map[string]any{}
	if b, err := os.ReadFile(p); err == nil {
		_ = json.Unmarshal(b, &cfg)
	}
	cfg["runtimePackage"] = RuntimePackageName()
	cfg["runtimeVersion"] = "latest"
	cfg["npmRegistry"] = m.Registry
	if _, ok := cfg["repo"]; !ok {
		cfg["repo"] = defaultRepo
	}
	if _, ok := cfg["releaseTag"]; !ok {
		cfg["releaseTag"] = defaultRelease
	}
	b, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return
	}
	_ = os.MkdirAll(filepath.Dir(p), 0o755)
	_ = os.WriteFile(p, append(b, '\n'), 0o644)
}

// Start launches the runtime services.
func (m *Manager) Start(ctx context.Context) error {
	return m.runCtl(ctx, "start", "--prefix", m.Prefix)
}

// Stop stops the runtime services.
func (m *Manager) Stop(ctx context.Context) error {
	return m.runCtl(ctx, "stop", "--prefix", m.Prefix)
}

// WaitHealthy blocks until /healthz reports ok or the timeout elapses.
func (m *Manager) WaitHealthy(ctx context.Context, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for {
		if _, ok := m.health(ctx); ok {
			return nil
		}
		if time.Now().After(deadline) {
			return errors.New("后端服务未在限定时间内就绪")
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(400 * time.Millisecond):
		}
	}
}

func (m *Manager) runCtl(ctx context.Context, args ...string) error {
	cmd := exec.CommandContext(ctx, m.ctlPath(), args...)
	winproc.Hide(cmd)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("vantaloomctl %s: %v\n%s", strings.Join(args, " "), err, strings.TrimSpace(string(out)))
	}
	return nil
}

// killTray stops a lingering tray process that may hold locks on bin/ (older
// trays don't write a pid that vantaloomctl stop can find). Best-effort.
func (m *Manager) killTray() {
	if runtime.GOOS != "windows" {
		kill := exec.Command("pkill", "-f", "vantaloom-tray")
		winproc.Hide(kill) // no-op on non-windows
		_ = kill.Run()
		return
	}
	pidFile := filepath.Join(m.Prefix, "runtime", "tray.pid")
	b, err := os.ReadFile(pidFile)
	if err != nil {
		return
	}
	if pid := strings.TrimSpace(string(b)); pid != "" {
		kill := exec.Command("taskkill", "/PID", pid, "/F")
		winproc.Hide(kill)
		_ = kill.Run()
		_ = os.Remove(pidFile)
	}
}

func (m *Manager) download(ctx context.Context, url, target string, onPct func(int)) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "vantaloom-desktop")
	resp, err := m.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d from %s", resp.StatusCode, url)
	}
	f, err := os.Create(target)
	if err != nil {
		return err
	}
	defer f.Close()

	total := resp.ContentLength
	var read int64
	last := -1
	buf := make([]byte, 64*1024)
	for {
		n, er := resp.Body.Read(buf)
		if n > 0 {
			if _, we := f.Write(buf[:n]); we != nil {
				return we
			}
			read += int64(n)
			if total > 0 && onPct != nil {
				if p := int(read * 100 / total); p != last {
					last = p
					onPct(p)
				}
			}
		}
		if er == io.EOF {
			return nil
		}
		if er != nil {
			return er
		}
	}
}

// ── package helpers ──

func extractTarGz(archive, dest string) error {
	f, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer gz.Close()

	cleanDest := filepath.Clean(dest)
	tr := tar.NewReader(gz)
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		target := filepath.Join(cleanDest, filepath.FromSlash(hdr.Name))
		// Guard against path traversal ("zip slip").
		if target != cleanDest && !strings.HasPrefix(target, cleanDest+string(os.PathSeparator)) {
			return fmt.Errorf("archive entry escapes destination: %s", hdr.Name)
		}
		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			mode := fs.FileMode(hdr.Mode).Perm()
			if mode == 0 {
				mode = 0o644
			}
			out, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode|0o200)
			if err != nil {
				return err
			}
			if _, err := io.Copy(out, tr); err != nil {
				out.Close()
				return err
			}
			out.Close()
		default:
			// Skip symlinks/devices/etc — the runtime package contains none.
		}
	}
}

func assertPackage(pkgRoot string) error {
	for _, name := range []string{"bin", "web", "cli"} {
		if !dirExists(filepath.Join(pkgRoot, name)) {
			return fmt.Errorf("无效的运行时包，缺少 %s/ 目录: %s", name, pkgRoot)
		}
	}
	if !fileExists(filepath.Join(pkgRoot, "manifest.json")) {
		return fmt.Errorf("无效的运行时包，缺少 manifest.json: %s", pkgRoot)
	}
	return nil
}

func readPackageVersion(pkgRoot string) string {
	if b, err := os.ReadFile(filepath.Join(pkgRoot, "VERSION")); err == nil {
		if v := strings.TrimSpace(string(b)); v != "" {
			return v
		}
	}
	if b, err := os.ReadFile(filepath.Join(pkgRoot, "manifest.json")); err == nil {
		var mani struct {
			Version string `json:"version"`
		}
		if json.Unmarshal(b, &mani) == nil && mani.Version != "" {
			return mani.Version
		}
	}
	return "dev"
}

func writeLauncher(prefix string) error {
	if runtime.GOOS == "windows" {
		return os.WriteFile(filepath.Join(prefix, "vantaloom.cmd"),
			[]byte("@echo off\r\nnode \"%~dp0cli\\bin\\vantaloom.mjs\" %*\r\n"), 0o644)
	}
	p := filepath.Join(prefix, "vantaloom")
	if err := os.WriteFile(p, []byte("#!/usr/bin/env sh\nexec node \"$(dirname \"$0\")/cli/bin/vantaloom.mjs\" \"$@\"\n"), 0o755); err != nil {
		return err
	}
	return os.Chmod(p, 0o755)
}

func copyTree(src, dst string) error {
	return copyTreeSkip(src, dst, nil)
}

func copyTreeSkip(src, dst string, skip func(rel string) bool) error {
	return filepath.WalkDir(src, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(src, p)
		if err != nil {
			return err
		}
		if d.IsDir() {
			return os.MkdirAll(filepath.Join(dst, rel), 0o755)
		}
		if skip != nil && skip(rel) {
			return nil
		}
		return copyFile(p, filepath.Join(dst, rel))
	})
}

func copyFile(src, dst string) error {
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	mode := fs.FileMode(0o644)
	if info, err := in.Stat(); err == nil {
		mode = info.Mode().Perm()
	}
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode|0o200)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

func dirExists(p string) bool {
	info, err := os.Stat(p)
	return err == nil && info.IsDir()
}

func fileExists(p string) bool {
	info, err := os.Stat(p)
	return err == nil && !info.IsDir()
}

func singleSubdir(root string) (string, error) {
	entries, err := os.ReadDir(root)
	if err != nil {
		return "", err
	}
	var dirs []string
	for _, e := range entries {
		if e.IsDir() {
			dirs = append(dirs, filepath.Join(root, e.Name()))
		}
	}
	if len(dirs) == 1 {
		return dirs[0], nil
	}
	return "", fmt.Errorf("expected a single package dir in %s, found %d", root, len(dirs))
}
