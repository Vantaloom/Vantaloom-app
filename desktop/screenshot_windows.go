//go:build windows

package main

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"image"
	"image/png"
	"syscall"
	"unsafe"
)

// Windows webview capture. The bundled Obscura headless engine can't screenshot,
// so "snapshot the page" captures the actual rendered pixels of the Vantaloom
// window's client area (which includes the WebView2-rendered <iframe> preview,
// cross-origin or not) by BitBlt-ing from the screen DC. Pure Win32 syscalls —
// no cgo, no external deps. Best-effort: the window must be visible/un-occluded
// (true for a user-initiated capture); GetDIBits then hands us the pixels.

var (
	user32 = syscall.NewLazyDLL("user32.dll")
	gdi32  = syscall.NewLazyDLL("gdi32.dll")

	procFindWindowW         = user32.NewProc("FindWindowW")
	procGetForegroundWindow = user32.NewProc("GetForegroundWindow")
	procGetClientRect       = user32.NewProc("GetClientRect")
	procClientToScreen      = user32.NewProc("ClientToScreen")
	procGetDC               = user32.NewProc("GetDC")
	procReleaseDC           = user32.NewProc("ReleaseDC")

	procCreateCompatibleDC     = gdi32.NewProc("CreateCompatibleDC")
	procCreateCompatibleBitmap = gdi32.NewProc("CreateCompatibleBitmap")
	procSelectObject           = gdi32.NewProc("SelectObject")
	procBitBlt                 = gdi32.NewProc("BitBlt")
	procDeleteObject           = gdi32.NewProc("DeleteObject")
	procDeleteDC               = gdi32.NewProc("DeleteDC")
	procGetDIBits              = gdi32.NewProc("GetDIBits")
)

type winRect struct{ left, top, right, bottom int32 }
type winPoint struct{ x, y int32 }

type bitmapInfoHeader struct {
	Size          uint32
	Width         int32
	Height        int32
	Planes        uint16
	BitCount      uint16
	Compression   uint32
	SizeImage     uint32
	XPelsPerMeter int32
	YPelsPerMeter int32
	ClrUsed       uint32
	ClrImportant  uint32
}

const (
	srcCopy      = 0x00CC0020
	dibRGBColors = 0
	biRGB        = 0
)

// findMainWindow locates the Wails window by its title ("Vantaloom", set in
// main.go), falling back to the foreground window (the app is foreground for a
// user-initiated capture).
func findMainWindow() uintptr {
	if title, err := syscall.UTF16PtrFromString("Vantaloom"); err == nil {
		if hwnd, _, _ := procFindWindowW.Call(0, uintptr(unsafe.Pointer(title))); hwnd != 0 {
			return hwnd
		}
	}
	fg, _, _ := procGetForegroundWindow.Call()
	return fg
}

// captureMainWindow returns a base64-encoded PNG (no data: prefix) of the
// Vantaloom window's client area.
func captureMainWindow() (string, error) {
	hwnd := findMainWindow()
	if hwnd == 0 {
		return "", fmt.Errorf("vantaloom window not found")
	}

	var rc winRect
	procGetClientRect.Call(hwnd, uintptr(unsafe.Pointer(&rc)))
	w := int(rc.right - rc.left)
	h := int(rc.bottom - rc.top)
	if w <= 0 || h <= 0 {
		return "", fmt.Errorf("window has no visible client area")
	}
	origin := winPoint{0, 0}
	procClientToScreen.Call(hwnd, uintptr(unsafe.Pointer(&origin)))

	hdcScreen, _, _ := procGetDC.Call(0)
	if hdcScreen == 0 {
		return "", fmt.Errorf("GetDC(screen) failed")
	}
	defer procReleaseDC.Call(0, hdcScreen)

	hdcMem, _, _ := procCreateCompatibleDC.Call(hdcScreen)
	if hdcMem == 0 {
		return "", fmt.Errorf("CreateCompatibleDC failed")
	}
	defer procDeleteDC.Call(hdcMem)

	hbm, _, _ := procCreateCompatibleBitmap.Call(hdcScreen, uintptr(w), uintptr(h))
	if hbm == 0 {
		return "", fmt.Errorf("CreateCompatibleBitmap failed")
	}
	defer procDeleteObject.Call(hbm)

	old, _, _ := procSelectObject.Call(hdcMem, hbm)
	if ret, _, _ := procBitBlt.Call(hdcMem, 0, 0, uintptr(w), uintptr(h), hdcScreen, uintptr(origin.x), uintptr(origin.y), srcCopy); ret == 0 {
		procSelectObject.Call(hdcMem, old)
		return "", fmt.Errorf("BitBlt failed")
	}
	// GetDIBits requires the bitmap NOT be selected into a DC.
	procSelectObject.Call(hdcMem, old)

	bi := bitmapInfoHeader{
		Size:        uint32(unsafe.Sizeof(bitmapInfoHeader{})),
		Width:       int32(w),
		Height:      -int32(h), // negative height => top-down rows
		Planes:      1,
		BitCount:    32,
		Compression: biRGB,
	}
	buf := make([]byte, w*h*4)
	if ret, _, _ := procGetDIBits.Call(hdcMem, hbm, 0, uintptr(h), uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&bi)), dibRGBColors); ret == 0 {
		return "", fmt.Errorf("GetDIBits failed")
	}

	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for i := 0; i < w*h; i++ {
		// Win32 DIB is BGRA; alpha from BitBlt is unreliable, force opaque.
		img.Pix[i*4+0] = buf[i*4+2] // R
		img.Pix[i*4+1] = buf[i*4+1] // G
		img.Pix[i*4+2] = buf[i*4+0] // B
		img.Pix[i*4+3] = 255
	}

	var out bytes.Buffer
	if err := png.Encode(&out, img); err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(out.Bytes()), nil
}
