//go:build !windows

// Package winproc hides the console/GUI window that Windows would otherwise
// allocate for a spawned child process. On non-Windows platforms Hide is a
// no-op.
package winproc

import "os/exec"

// Hide is a no-op on non-Windows platforms.
func Hide(cmd *exec.Cmd) {}
