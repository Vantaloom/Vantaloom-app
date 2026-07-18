package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestVersion(t *testing.T) {
	var stdout bytes.Buffer
	var stderr bytes.Buffer
	if code := runCLI([]string{"--version"}, strings.NewReader(""), &stdout, &stderr); code != 0 {
		t.Fatalf("runCLI returned %d: %s", code, stderr.String())
	}
	if !strings.Contains(stdout.String(), "yaegi v0.16.1") {
		t.Fatalf("unexpected version output: %s", stdout.String())
	}
}

func TestRunFileAndArguments(t *testing.T) {
	script := writeScript(t, `package main
import (
    "fmt"
    "os"
)
func main() { fmt.Printf("%s:%s", os.Args[0], os.Args[1]) }
`)
	var stdout bytes.Buffer
	var stderr bytes.Buffer
	if code := runCLI([]string{"run", script, "ok"}, strings.NewReader(""), &stdout, &stderr); code != 0 {
		t.Fatalf("runCLI returned %d: %s", code, stderr.String())
	}
	if !strings.HasSuffix(stdout.String(), ":ok") {
		t.Fatalf("unexpected script output: %s", stdout.String())
	}
}

func TestDangerousPackagesAreUnavailable(t *testing.T) {
	for _, importPath := range []string{"os/exec", "syscall", "unsafe"} {
		t.Run(importPath, func(t *testing.T) {
			script := writeScript(t, "package main\nimport _ \""+importPath+"\"\nfunc main() {}\n")
			var stderr bytes.Buffer
			if code := runCLI([]string{"run", script}, strings.NewReader(""), &bytes.Buffer{}, &stderr); code == 0 {
				t.Fatalf("import %s unexpectedly succeeded", importPath)
			}
		})
	}
}

func TestServerEntryPointsAreUnavailable(t *testing.T) {
	for name, source := range map[string]string{
		"net-listen": `package main
import "net"
func main() { _, _ = net.Listen("tcp", "127.0.0.1:0") }
`,
		"http-server": `package main
import "net/http"
func main() { _ = http.ListenAndServe("127.0.0.1:0", nil) }
`,
	} {
		t.Run(name, func(t *testing.T) {
			script := writeScript(t, source)
			var stderr bytes.Buffer
			if code := runCLI([]string{"run", script}, strings.NewReader(""), &bytes.Buffer{}, &stderr); code == 0 {
				t.Fatalf("server entry point unexpectedly succeeded")
			}
		})
	}
}

func TestRestrictedSymbolMap(t *testing.T) {
	symbols := restrictedSymbols()
	for packagePath := range deniedPackages {
		if _, ok := symbols[packagePath]; ok {
			t.Fatalf("denied package exported: %s", packagePath)
		}
	}
	for packagePath, names := range deniedSymbols {
		for _, name := range names {
			if _, ok := symbols[packagePath][name]; ok {
				t.Fatalf("denied symbol exported: %s.%s", packagePath, name)
			}
		}
	}
}

func writeScript(t *testing.T, source string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "main.go")
	if err := os.WriteFile(path, []byte(source), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}
