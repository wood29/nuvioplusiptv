package main

import (
	"os"
	"os/exec"
	"path/filepath"
)

const keepNextToApp = false

// openWindow: Chrome, Edge or Brave as an app window (no tabs, no address bar), else Safari.
func openWindow(url string) {
	home, _ := os.UserHomeDir()
	apps := []string{"Google Chrome", "Microsoft Edge", "Brave Browser", "Chromium"}
	profile := file("browser-profile")
	for _, a := range apps {
		for _, root := range []string{"/Applications", filepath.Join(home, "Applications")} {
			if _, err := os.Stat(filepath.Join(root, a+".app")); err == nil {
				cmd := exec.Command("open", "-na", a, "--args", "--app="+url, "--user-data-dir="+profile, "--window-size=1440,900", "--no-first-run")
				if cmd.Start() == nil {
					return
				}
			}
		}
	}
	_ = exec.Command("open", url).Start()
}
