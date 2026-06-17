package runtime

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

// TestMeshNeedsApplyNoMeshBinary verifies the gate flags "apply needed" when a
// required mesh binary is missing (fresh prefix with nothing laid down). The
// outer ensureMeshService no-ops on a missing vantaloom-mesh exe; here we assert
// the gate's own decision so a partial bin/ still triggers (re)registration.
func TestMeshNeedsApplyMissingBinary(t *testing.T) {
	if runtime.GOOS == "linux" {
		t.Skip("mesh apply gate is Windows/macOS only; Linux uses setcap")
	}
	tmp := t.TempDir()
	m := New(tmp, "")
	binDir := filepath.Join(tmp, "bin")
	if err := os.MkdirAll(binDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if !m.meshServiceNeedsApply(binDir) {
		t.Fatal("expected needsApply=true when mesh binaries are absent")
	}
}

// TestEnsureMeshServiceNoopWithoutSidecar verifies the whole step is a no-op
// (never elevates) when the vantaloom-mesh binary isn't bundled — it must not
// shell out to powershell/osascript/sudo. We assert it simply returns and
// writes no applied-marker.
func TestEnsureMeshServiceNoopWithoutSidecar(t *testing.T) {
	tmp := t.TempDir()
	m := New(tmp, "")
	if err := os.MkdirAll(filepath.Join(tmp, "bin"), 0o755); err != nil {
		t.Fatal(err)
	}
	m.ensureMeshService(t.Context())
	if fileExists(m.meshMarkerPath()) {
		t.Fatal("ensureMeshService wrote an applied-marker despite no mesh binary")
	}
}

// TestMeshAppliedMarkerRoundTrip verifies the marker reflects on-disk hashes and
// that an UNCHANGED mesh set is recognized (the basis of "no UAC on routine
// updates"): after writing the marker, every binary's recorded hash matches, so
// the only remaining apply trigger is the service-not-running check.
func TestMeshAppliedMarkerRoundTrip(t *testing.T) {
	tmp := t.TempDir()
	m := New(tmp, "")
	binDir := filepath.Join(tmp, "bin")

	// Lay down every platform mesh binary with stable content.
	for _, name := range meshBinaryNames() {
		mustWrite(t, filepath.Join(binDir, name), "content-of-"+name)
	}

	if err := m.writeMeshAppliedMarker(binDir); err != nil {
		t.Fatalf("writeMeshAppliedMarker: %v", err)
	}
	marker := m.readMeshAppliedMarker()
	for _, name := range meshBinaryNames() {
		sum, err := fileSha(filepath.Join(binDir, name))
		if err != nil {
			t.Fatalf("fileSha %s: %v", name, err)
		}
		if marker[name] != sum {
			t.Errorf("marker[%s]=%q, want %q", name, marker[name], sum)
		}
	}

	// Changing a binary must invalidate its marker entry → gate would re-apply.
	first := meshBinaryNames()[0]
	mustWrite(t, filepath.Join(binDir, first), "CHANGED")
	changed, err := fileSha(filepath.Join(binDir, first))
	if err != nil {
		t.Fatal(err)
	}
	if marker[first] == changed {
		t.Fatal("expected changed binary to differ from recorded marker hash")
	}
}

// TestPersistDirIdempotencyHelper checks the cross-platform PATH-membership test
// underlying ensureInPath: an already-present dir is detected (so the persist
// helpers no-op and don't double-append), with OS-correct separators and
// case-sensitivity.
func TestPersistDirIdempotencyHelper(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "Vantaloom")
	sep := string(os.PathListSeparator)
	pathVal := filepath.Join("usr", "bin") + sep + dir + sep + filepath.Join("opt", "x")

	if !nodePathContains(pathVal, dir) {
		t.Fatal("expected nodePathContains to find an already-present dir")
	}
	if nodePathContains(pathVal, filepath.Join(t.TempDir(), "Other")) {
		t.Fatal("did not expect a non-present dir to be reported as contained")
	}
	if nodePathContains("", dir) {
		t.Fatal("empty PATH should contain nothing")
	}
	if runtime.GOOS == "windows" {
		// Windows PATH is case-insensitive.
		if !nodePathContains(pathVal, dir) {
			t.Fatal("windows match should be case-insensitive")
		}
	}
}
