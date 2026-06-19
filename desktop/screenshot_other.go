//go:build !windows

package main

import "errors"

// captureMainWindow is Windows-only for now. macOS window capture requires the
// Screen Recording permission (CGWindowListCreateImage) and Linux varies by
// compositor, so non-Windows desktop shells report the capability as
// unsupported and the frontend falls back (disables the 截图 button).
func captureMainWindow() (string, error) {
	return "", errors.New("webview capture is only supported on the Windows desktop shell")
}
