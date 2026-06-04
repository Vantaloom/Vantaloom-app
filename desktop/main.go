package main

import (
	"embed"
	"io/fs"
	"log"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
)

//go:embed all:frontend/dist
var assetsFS embed.FS

func main() {
	// Serve the splash from frontend/dist with index.html at the FS root.
	assets, err := fs.Sub(assetsFS, "frontend/dist")
	if err != nil {
		log.Fatalf("embed assets: %v", err)
	}

	app := NewApp()

	err = wails.Run(&options.App{
		Title:     "Vantaloom",
		Width:     1200,
		Height:    820,
		MinWidth:  480,
		MinHeight: 600,
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
