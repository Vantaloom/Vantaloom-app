package main

import (
	"embed"
	"io/fs"
	"log"
	"os"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
)

//go:embed all:frontend/dist
var assetsFS embed.FS

func main() {
	// The app UI runs inside a cross-origin <iframe> hosted by the Wails asset
	// page (the webview never leaves the Wails origin — that's where the
	// runtime's frameless drag/resize provably works). Chromium partitions
	// third-party iframe storage by default, which would give the embedded app
	// a DIFFERENT localStorage than it had as a top-level page (logins/prefs
	// gone). Disable partitioning so the iframe keeps the app's first-party
	// storage. WebView2's loader reads this env var at creation time.
	const storageFlag = "--disable-features=ThirdPartyStoragePartitioning"
	if cur := os.Getenv("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS"); cur != "" {
		os.Setenv("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", cur+" "+storageFlag)
	} else {
		os.Setenv("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", storageFlag)
	}
	// Serve the splash from frontend/dist with index.html at the FS root.
	assets, err := fs.Sub(assetsFS, "frontend/dist")
	if err != nil {
		log.Fatalf("embed assets: %v", err)
	}

	app := NewApp()

	err = wails.Run(&options.App{
		Title:            "Vantaloom",
		Width:            1200,
		Height:           820,
		MinWidth:         480,
		MinHeight:        600,
		Frameless:        true,
		AssetServer: &assetserver.Options{
			Assets: assets,
		},
		BackgroundColour: &options.RGBA{R: 11, G: 13, B: 20, A: 255},
		OnStartup:        app.startup,
		Bind: []interface{}{
			app,
		},
	})
	if err != nil {
		log.Fatalf("vantaloom desktop: %v", err)
	}
}
