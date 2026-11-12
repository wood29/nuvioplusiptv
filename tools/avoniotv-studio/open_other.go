//go:build !windows && !darwin

package main

import "os/exec"

const keepNextToApp = true

func openWindow(url string) { _ = exec.Command("xdg-open", url).Start() }
