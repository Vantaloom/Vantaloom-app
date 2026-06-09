//go:build windows

// Package winproc hides the console/GUI window that Windows would otherwise
// allocate for a spawned child process. On non-Windows platforms every function
// here is a no-op (see winproc_other.go).
package winproc

import (
	"os/exec"
	"syscall"
)

// createNoWindow is the Windows CREATE_NO_WINDOW process-creation flag. For
// console-subsystem children (vantaloomctl, taskkill, reg, …) it suppresses
// console allocation entirely; HideWindow covers the GUI case.
const createNoWindow = 0x08000000

// Hide configures cmd so Windows does not flash a console/GUI window when the
// child is spawned. It is additive: existing CreationFlags are preserved.
func Hide(cmd *exec.Cmd) {
	if cmd == nil {
		return
	}
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	cmd.SysProcAttr.HideWindow = true
	cmd.SysProcAttr.CreationFlags |= createNoWindow
}
