//go:build windows

package main

import (
	"fmt"
)

// Native frameless window drag/resize. Wails implements these in its runtime
// JS, which is only injected into the Wails asset origin — the app page at
// 127.0.0.1:<apiPort> never gets it (and the raw webview message channel is
// origin-filtered). So the in-app title bar calls the shell's ctl endpoints
// and we hand the interaction to the OS.
//
// The starting sequence MUST mirror Wails' own startDrag/startResize exactly:
// ReleaseCapture + PostMessage(WM_NCLBUTTONDOWN, <hit-test>). PostMessage
// queues the message into the main thread's regular pump, where DefWindowProc
// starts the native move/size loop cleanly — the empirically proven path (the
// splash page drags/resizes this very window through it). A cross-thread
// SendMessage instead starts the modal loop inside inter-thread-send handling
// and it exits immediately ("阴影变深一瞬就断"); AttachThreadInput "fixes"
// even harder by resetting the shared key state, erasing the held button.

var (
	procReleaseCapture = user32.NewProc("ReleaseCapture")
	procPostMessageW   = user32.NewProc("PostMessageW")
)

const wmNCLButtonDown = 0x00A1

// Native hit-test codes (WM_NCHITTEST results).
const (
	htCaption     = 2
	htLeft        = 10
	htRight       = 11
	htTop         = 12
	htTopLeft     = 13
	htTopRight    = 14
	htBottom      = 15
	htBottomLeft  = 16
	htBottomRight = 17
)

var resizeEdgeHitTest = map[string]uintptr{
	"l":  htLeft,
	"r":  htRight,
	"t":  htTop,
	"b":  htBottom,
	"tl": htTopLeft,
	"tr": htTopRight,
	"bl": htBottomLeft,
	"br": htBottomRight,
}

// beginNCInteraction posts the non-client button-down that starts the OS move
// or size loop. Must run while the user still holds the left button (the ctl
// HTTP round trip is ~1ms, well inside the press).
func beginNCInteraction(hitTest uintptr) error {
	hwnd := findMainWindow()
	if hwnd == 0 {
		return fmt.Errorf("vantaloom window not found")
	}
	procReleaseCapture.Call()
	procPostMessageW.Call(hwnd, wmNCLButtonDown, hitTest, 0)
	return nil
}

func startNativeDrag() error {
	return beginNCInteraction(htCaption)
}

func startNativeResize(edge string) error {
	ht, ok := resizeEdgeHitTest[edge]
	if !ok {
		return fmt.Errorf("unknown resize edge %q", edge)
	}
	return beginNCInteraction(ht)
}
