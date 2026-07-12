package runtime

// EnsureNode guarantees a usable Node.js exists before the desktop shell boots
// the runtime. The runtime launcher (vantaloom.cmd / vantaloom) shells out to
// the system `node`; on a fresh machine with no Node that fails.
//
// EnsureNode detects an existing node and, failing that, downloads a pinned LTS
// from a China-friendly mirror (npmmirror) into a per-user managed dir, persists
// it on the user-level PATH, and returns the dir containing the node executable
// so the caller can prepend it to a spawned process's PATH.
//
// This intentionally duplicates apps/api/internal/nodeenv: the desktop shell is
// a separate Go module that ships standalone to the public repo and cannot
// import apps/api/internal/*. It is stdlib-only (+ reg.exe on Windows) so it adds
// no module dependencies.
//
// Idempotent and non-fatal: all failures return an error the caller soft-handles
// (boot proceeds, relying on whatever node may already exist).

import (
	"archive/tar"
	"archive/zip"
	"compress/gzip"
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"vantaloom.local/apps/desktop/internal/winproc"
)

// nodeVersion is the pinned LTS we install. Pinning avoids parsing a "latest"
// redirect and keeps the mirror URLs deterministic.
const nodeVersion = "v20.18.1"

// nodeMirrorBase is the npmmirror Node binary mirror (China-friendly).
const nodeMirrorBase = "https://registry.npmmirror.com/-/binary/node/"

// nodeDownloadTimeout caps the archive download.
const nodeDownloadTimeout = 2 * time.Minute

// EnsureNode returns the directory CONTAINING the node executable, installing a
// pinned LTS from a China mirror if no working node is found.
func EnsureNode(ctx context.Context) (string, error) {
	if dir := workingNodeOnPath(); dir != "" {
		return dir, nil
	}

	managed := nodeManagedDir()
	if dir := workingNodeInDir(managed); dir != "" {
		persistNodePath(dir)
		return dir, nil
	}

	dir, err := downloadAndInstallNode(ctx, managed)
	if err != nil {
		log.Printf("[node] 自动安装 Node.js 失败（启动将依赖系统已有 node）: %v", err)
		return "", err
	}
	persistNodePath(dir)
	log.Printf("[node] 已安装 Node.js %s 到 %s", nodeVersion, dir)
	return dir, nil
}

func workingNodeOnPath() string {
	p, err := exec.LookPath("node")
	if err != nil {
		return ""
	}
	if !nodeRuns(p) {
		return ""
	}
	abs, err := filepath.Abs(p)
	if err != nil {
		return filepath.Dir(p)
	}
	return filepath.Dir(abs)
}

func workingNodeInDir(dir string) string {
	if dir == "" {
		return ""
	}
	bin := nodeExePath(dir)
	if _, err := os.Stat(bin); err != nil {
		return ""
	}
	if !nodeRuns(bin) {
		return ""
	}
	return dir
}

func nodeRuns(nodePath string) bool {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return exec.CommandContext(ctx, nodePath, "--version").Run() == nil
}

// nodeExePath returns the node executable in a managed-dir layout
// (Windows: <dir>\node.exe; unix: <dir>/bin/node).
func nodeExePath(dir string) string {
	if runtime.GOOS == "windows" {
		return filepath.Join(dir, "node.exe")
	}
	return filepath.Join(dir, "bin", "node")
}

// nodeManagedDir is the per-user dir we install Node into.
//
//	Windows: %LOCALAPPDATA%\Vantaloom\node
//	unix:    $HOME/.vantaloom/node
func nodeManagedDir() string {
	if runtime.GOOS == "windows" {
		base := os.Getenv("LOCALAPPDATA")
		if base == "" {
			if home, err := os.UserHomeDir(); err == nil {
				base = filepath.Join(home, "AppData", "Local")
			}
		}
		return filepath.Join(base, "Vantaloom", "node")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		home = os.Getenv("HOME")
	}
	return filepath.Join(home, ".vantaloom", "node")
}

// nodePathDir returns the dir to put on PATH (unix node lives in <managed>/bin).
func nodePathDir(managed string) string {
	if runtime.GOOS == "windows" {
		return managed
	}
	return filepath.Join(managed, "bin")
}

func nodeArchiveName() string {
	v := nodeVersion
	switch runtime.GOOS {
	case "windows":
		if runtime.GOARCH == "amd64" {
			return fmt.Sprintf("node-%s-win-x64.zip", v)
		}
		if runtime.GOARCH == "arm64" {
			return fmt.Sprintf("node-%s-win-arm64.zip", v)
		}
	case "darwin":
		if runtime.GOARCH == "arm64" {
			return fmt.Sprintf("node-%s-darwin-arm64.tar.gz", v)
		}
		if runtime.GOARCH == "amd64" {
			return fmt.Sprintf("node-%s-darwin-x64.tar.gz", v)
		}
	case "linux":
		if runtime.GOARCH == "amd64" {
			return fmt.Sprintf("node-%s-linux-x64.tar.gz", v)
		}
		if runtime.GOARCH == "arm64" {
			return fmt.Sprintf("node-%s-linux-arm64.tar.gz", v)
		}
	}
	return ""
}

func downloadAndInstallNode(ctx context.Context, managed string) (string, error) {
	name := nodeArchiveName()
	if name == "" {
		return "", fmt.Errorf("不支持的平台: %s/%s", runtime.GOOS, runtime.GOARCH)
	}
	url := nodeMirrorBase + nodeVersion + "/" + name

	dlCtx, cancel := context.WithTimeout(ctx, nodeDownloadTimeout)
	defer cancel()

	tmp, err := os.CreateTemp("", "vantaloom-node-*"+nodeArchiveExt(name))
	if err != nil {
		return "", fmt.Errorf("创建临时文件: %w", err)
	}
	tmpPath := tmp.Name()
	defer os.Remove(tmpPath)

	req, err := http.NewRequestWithContext(dlCtx, http.MethodGet, url, nil)
	if err != nil {
		tmp.Close()
		return "", err
	}
	req.Header.Set("User-Agent", "vantaloom-desktop")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		tmp.Close()
		return "", fmt.Errorf("下载 %s: %w", url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		tmp.Close()
		return "", fmt.Errorf("下载 %s: HTTP %d", url, resp.StatusCode)
	}
	if _, err := io.Copy(tmp, resp.Body); err != nil {
		tmp.Close()
		return "", fmt.Errorf("写入临时文件: %w", err)
	}
	tmp.Close()

	_ = os.RemoveAll(managed)
	if err := os.MkdirAll(managed, 0o755); err != nil {
		return "", fmt.Errorf("创建安装目录: %w", err)
	}

	if strings.HasSuffix(name, ".zip") {
		if err := extractNodeZip(tmpPath, managed); err != nil {
			return "", fmt.Errorf("解压 zip: %w", err)
		}
	} else {
		if err := extractNodeTarGz(tmpPath, managed); err != nil {
			return "", fmt.Errorf("解压 tar.gz: %w", err)
		}
	}

	dir := nodePathDir(managed)
	bin := nodeExePath(managed)
	if runtime.GOOS != "windows" {
		_ = os.Chmod(bin, 0o755)
		_ = os.Chmod(filepath.Join(managed, "bin", "npm"), 0o755)
		_ = os.Chmod(filepath.Join(managed, "bin", "npx"), 0o755)
	}
	if !nodeRuns(bin) {
		return "", fmt.Errorf("安装后 node 无法运行: %s", bin)
	}
	return dir, nil
}

func nodeArchiveExt(name string) string {
	if strings.HasSuffix(name, ".zip") {
		return ".zip"
	}
	return ".tar.gz"
}

// stripNodeTopLevel removes the leading "node-<ver>-<plat>/" component.
func stripNodeTopLevel(name string) string {
	name = strings.ReplaceAll(name, "\\", "/")
	idx := strings.IndexByte(name, '/')
	if idx < 0 {
		return ""
	}
	return name[idx+1:]
}

func extractNodeZip(src, dst string) error {
	zr, err := zip.OpenReader(src)
	if err != nil {
		return err
	}
	defer zr.Close()
	for _, f := range zr.File {
		rel := stripNodeTopLevel(f.Name)
		if rel == "" {
			continue
		}
		target, err := nodeSafeJoin(dst, rel)
		if err != nil {
			return err
		}
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}
		rc, err := f.Open()
		if err != nil {
			return err
		}
		if err := writeNodeFile(target, rc, f.Mode()); err != nil {
			rc.Close()
			return err
		}
		rc.Close()
	}
	return nil
}

func extractNodeTarGz(src, dst string) error {
	f, err := os.Open(src)
	if err != nil {
		return err
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer gz.Close()
	tr := tar.NewReader(gz)
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		rel := stripNodeTopLevel(hdr.Name)
		if rel == "" {
			continue
		}
		target, err := nodeSafeJoin(dst, rel)
		if err != nil {
			return err
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
			if err := writeNodeFile(target, tr, os.FileMode(hdr.Mode)); err != nil {
				return err
			}
		case tar.TypeSymlink:
			_ = os.MkdirAll(filepath.Dir(target), 0o755)
			_ = os.Remove(target)
			_ = os.Symlink(hdr.Linkname, target)
		}
	}
	return nil
}

func nodeSafeJoin(base, rel string) (string, error) {
	target := filepath.Join(base, filepath.FromSlash(rel))
	clean := filepath.Clean(target)
	if clean != base && !strings.HasPrefix(clean, base+string(os.PathSeparator)) {
		return "", fmt.Errorf("非法归档路径: %s", rel)
	}
	return clean, nil
}

func writeNodeFile(target string, r io.Reader, mode os.FileMode) error {
	if mode == 0 {
		mode = 0o644
	}
	out, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode.Perm()|0o200)
	if err != nil {
		return err
	}
	_, copyErr := io.Copy(out, r)
	closeErr := out.Close()
	if copyErr != nil {
		return copyErr
	}
	return closeErr
}

// persistNodePath records nodeDir on the USER-level PATH. Best-effort, non-fatal.
func persistNodePath(nodeDir string) {
	if nodeDir == "" {
		return
	}
	if runtime.GOOS == "windows" {
		persistDirOnUserPathWindows(nodeDir)
		return
	}
	persistDirOnUserPathUnixLabeled(nodeDir, "Node.js")
}

// persistDirOnUserPath records dir on the USER-level PATH (HKCU on Windows,
// shell profiles on unix). Best-effort, non-fatal, idempotent. Generalized from
// the node-install path persistence so other install steps (e.g. putting the
// Vantaloom prefix on PATH) can reuse the same battle-tested, unprivileged
// mechanism — see ensureInPath in manager.go.
func persistDirOnUserPath(dir string) {
	if dir == "" {
		return
	}
	if runtime.GOOS == "windows" {
		persistDirOnUserPathWindows(dir)
		return
	}
	persistDirOnUserPathUnix(dir)
}

// persistDirOnUserPathWindows updates HKCU\Environment Path via reg.exe. We avoid
// `setx PATH` (truncates at 1024 chars and corrupts PATH by flattening the
// merged REG_EXPAND_SZ into a literal REG_SZ).
func persistDirOnUserPathWindows(dir string) {
	existing := readUserNodePathWindows()
	if nodePathContains(existing, dir) {
		return
	}
	merged := dir
	if strings.TrimSpace(existing) != "" {
		merged = strings.TrimRight(existing, ";") + ";" + dir
	}
	cmd := exec.Command("reg", "add", `HKCU\Environment`, "/v", "Path", "/t", "REG_EXPAND_SZ", "/d", merged, "/f")
	winproc.Hide(cmd)
	if out, err := cmd.CombinedOutput(); err != nil {
		log.Printf("[path] 写入用户 PATH 失败: %v\n%s", err, strings.TrimSpace(string(out)))
	}
}

func readUserNodePathWindows() string {
	cmd := exec.Command("reg", "query", `HKCU\Environment`, "/v", "Path")
	winproc.Hide(cmd)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(out), "\n") {
		fields := strings.Fields(strings.TrimSpace(line))
		if len(fields) >= 3 && strings.EqualFold(fields[0], "Path") &&
			(strings.EqualFold(fields[1], "REG_SZ") || strings.EqualFold(fields[1], "REG_EXPAND_SZ")) {
			idx := strings.Index(line, fields[1])
			if idx >= 0 {
				return strings.TrimSpace(line[idx+len(fields[1]):])
			}
		}
	}
	return ""
}

func persistDirOnUserPathUnix(dir string) {
	persistDirOnUserPathUnixLabeled(dir, "")
}

func persistDirOnUserPathUnixLabeled(dir, label string) {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return
	}
	var profiles []string
	if strings.Contains(os.Getenv("SHELL"), "zsh") {
		profiles = []string{filepath.Join(home, ".zshrc")}
	} else {
		profiles = []string{filepath.Join(home, ".bashrc"), filepath.Join(home, ".profile")}
	}
	comment := "# Added by Vantaloom"
	if label != "" {
		comment = fmt.Sprintf("# Added by Vantaloom (%s)", label)
	}
	line := fmt.Sprintf("export PATH=\"%s:$PATH\"", dir)
	block := fmt.Sprintf("\n%s\n%s\n", comment, line)
	for _, prof := range profiles {
		if data, err := os.ReadFile(prof); err == nil {
			if strings.Contains(string(data), dir) {
				continue
			}
		}
		f, err := os.OpenFile(prof, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
		if err != nil {
			continue
		}
		_, _ = f.WriteString(block)
		_ = f.Close()
	}
}

func nodePathContains(pathVal, dir string) bool {
	if strings.TrimSpace(pathVal) == "" {
		return false
	}
	sep := string(os.PathListSeparator)
	want := filepath.Clean(dir)
	for _, p := range strings.Split(pathVal, sep) {
		p = filepath.Clean(strings.TrimSpace(p))
		if runtime.GOOS == "windows" {
			if strings.EqualFold(p, want) {
				return true
			}
		} else if p == want {
			return true
		}
	}
	return false
}
