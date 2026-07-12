//go:build !windows

package main

import "errors"

// Non-Windows: window drag uses the Wails "drag" message on the native webview
// channel (macOS WKWebView keeps webkit.messageHandlers across origins), and
// edge resize is native AppKit — neither needs the ctl endpoints.

func startNativeDrag() error {
	return errors.New("native drag not supported on this platform")
}

func startNativeResize(string) error {
	return errors.New("native resize not supported on this platform")
}
