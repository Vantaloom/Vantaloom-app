package runtime

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestPlatformID(t *testing.T) {
	cases := []struct {
		goos, goarch, want string
	}{
		{"windows", "amd64", "win32-x64"},
		{"windows", "arm64", "win32-arm64"},
		{"darwin", "arm64", "darwin-arm64"},
		{"darwin", "amd64", "darwin-x64"},
		{"linux", "amd64", "linux-x64"},
		{"linux", "arm64", "linux-arm64"},
	}
	for _, c := range cases {
		if got := platformID(c.goos, c.goarch); got != c.want {
			t.Errorf("platformID(%q,%q) = %q, want %q", c.goos, c.goarch, got, c.want)
		}
	}
}

func TestRuntimePackageName(t *testing.T) {
	want := "@vantaloom/runtime-" + platformID(runtime.GOOS, runtime.GOARCH)
	if got := RuntimePackageName(); got != want {
		t.Errorf("RuntimePackageName() = %q, want %q", got, want)
	}
}

func TestDefaultPrefixHonorsEnv(t *testing.T) {
	t.Setenv("VANTALOOM_HOME", filepath.Join(t.TempDir(), "custom"))
	if got, want := DefaultPrefix(), os.Getenv("VANTALOOM_HOME"); got != want {
		t.Errorf("DefaultPrefix() = %q, want %q", got, want)
	}
}

// buildTarGz writes a gzipped tarball whose entries are the given map of
// archive-path -> content. Directory entries are created implicitly.
func buildTarGz(t *testing.T, path string, files map[string]string) {
	t.Helper()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	for name, content := range files {
		if err := tw.WriteHeader(&tar.Header{
			Name:     name,
			Mode:     0o644,
			Size:     int64(len(content)),
			Typeflag: tar.TypeReg,
		}); err != nil {
			t.Fatal(err)
		}
		if _, err := tw.Write([]byte(content)); err != nil {
			t.Fatal(err)
		}
	}
	if err := tw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := gz.Close(); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestExtractTarGz(t *testing.T) {
	tmp := t.TempDir()
	archive := filepath.Join(tmp, "pkg.tgz")
	buildTarGz(t, archive, map[string]string{
		"package/manifest.json":    `{"version":"1.2.3"}`,
		"package/VERSION":          "1.2.3\n",
		"package/bin/vantaloomctl": "ctl",
		"package/web/index.html":   "<html></html>",
		"package/cli/config.json":  "{}",
	})
	dest := filepath.Join(tmp, "out")
	if err := extractTarGz(archive, dest); err != nil {
		t.Fatalf("extractTarGz: %v", err)
	}
	got, err := os.ReadFile(filepath.Join(dest, "package", "web", "index.html"))
	if err != nil || string(got) != "<html></html>" {
		t.Fatalf("extracted web/index.html = %q, err=%v", got, err)
	}
	if v := readPackageVersion(filepath.Join(dest, "package")); v != "1.2.3" {
		t.Errorf("readPackageVersion = %q, want 1.2.3", v)
	}
}

func TestExtractTarGzRejectsTraversal(t *testing.T) {
	tmp := t.TempDir()
	archive := filepath.Join(tmp, "evil.tgz")
	buildTarGz(t, archive, map[string]string{
		"../escape.txt": "pwned",
	})
	if err := extractTarGz(archive, filepath.Join(tmp, "out")); err == nil {
		t.Fatal("expected extractTarGz to reject a path-traversal entry")
	}
}

// writeFakePackage lays down a minimal valid runtime package on disk (not a
// tarball) for exercising layDown directly.
func writeFakePackage(t *testing.T, root, version, appContent string) {
	t.Helper()
	mustWrite(t, filepath.Join(root, "manifest.json"), `{"version":"`+version+`"}`)
	mustWrite(t, filepath.Join(root, "VERSION"), version+"\n")
	mustWrite(t, filepath.Join(root, "bin", binaryName("vantaloomctl")), "ctl-binary")
	mustWrite(t, filepath.Join(root, "bin", binaryName("vantaloom-api")), appContent)
	mustWrite(t, filepath.Join(root, "web", "index.html"), "<html></html>")
	mustWrite(t, filepath.Join(root, "cli", "config.json"), `{"existing":true}`)
}

func mustWrite(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestLayDownPlacesFilesAndConfig(t *testing.T) {
	tmp := t.TempDir()
	pkg := filepath.Join(tmp, "package")
	writeFakePackage(t, pkg, "9.9.9", "API-V1")

	m := New(filepath.Join(tmp, "prefix"), "https://registry.example.test/")
	if err := m.layDown(t.Context(), pkg, "9.9.9", resolved{Version: "9.9.9"}); err != nil {
		t.Fatalf("layDown: %v", err)
	}

	if !m.Installed() {
		t.Fatal("expected Installed() true after layDown")
	}
	if v := m.InstalledVersion(); v != "9.9.9" {
		t.Errorf("InstalledVersion = %q, want 9.9.9", v)
	}
	if !fileExists(filepath.Join(m.Prefix, "manifest.json")) {
		t.Error("manifest.json not copied")
	}
	if !fileExists(filepath.Join(m.Prefix, "web", "index.html")) {
		t.Error("web/index.html not copied")
	}
	launcher := "vantaloom"
	if runtime.GOOS == "windows" {
		launcher = "vantaloom.cmd"
	}
	if !fileExists(filepath.Join(m.Prefix, launcher)) {
		t.Errorf("launcher %s not written", launcher)
	}

	cfg := readJSON(t, filepath.Join(m.Prefix, "cli", "config.json"))
	if cfg["runtimePackage"] != RuntimePackageName() {
		t.Errorf("config runtimePackage = %v, want %v", cfg["runtimePackage"], RuntimePackageName())
	}
	if cfg["npmRegistry"] != "https://registry.example.test" {
		t.Errorf("config npmRegistry = %v", cfg["npmRegistry"])
	}
	if cfg["existing"] != true {
		t.Error("expected existing config keys to be preserved")
	}
}

func TestAssertPackage(t *testing.T) {
	tmp := t.TempDir()
	if err := assertPackage(tmp); err == nil {
		t.Fatal("expected assertPackage to fail on empty dir")
	}
	writeFakePackage(t, tmp, "1.0.0", "a")
	if err := assertPackage(tmp); err != nil {
		t.Errorf("assertPackage on valid package: %v", err)
	}
}

func readJSON(t *testing.T, path string) map[string]any {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var out map[string]any
	if err := json.Unmarshal(b, &out); err != nil {
		t.Fatal(err)
	}
	return out
}
