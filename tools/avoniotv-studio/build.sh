#!/usr/bin/env bash
# Builds avonioTV Studio for Windows (one .exe with the avonioTV icon) and the Mac
# (an "avonioTV Studio.app", Apple Silicon + Intel, zipped). Output in ./dist.
set -euo pipefail
cd "$(dirname "$0")"
cp ../../app/src/main/assets/channel_manager.html .
rm -rf dist && mkdir -p dist
export CGO_ENABLED=0
VERSION="${STUDIO_VERSION:-1.0}"

# Windows: icon + "no console window".
go install github.com/tc-hib/go-winres@v0.3.3
"$(go env GOPATH)/bin/go-winres" simply --icon assets/icon-256.png --manifest gui \
  --file-description "avonioTV Studio" --product-name "avonioTV Studio" \
  --product-version "$VERSION.0.0" --file-version "$VERSION.0.0" --original-filename "avonioTV-Studio.exe"
GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-H windowsgui -s -w" -o "dist/avonioTV-Studio.exe" .
rm -f rsrc_windows_*.syso

# Mac: one program for Apple Silicon and Intel, in an app bundle.
GOOS=darwin GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o dist/studio-arm64 .
GOOS=darwin GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o dist/studio-amd64 .
go install github.com/randall77/makefat@latest
APP="dist/avonioTV Studio.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
"$(go env GOPATH)/bin/makefat" "$APP/Contents/MacOS/avonioTV Studio" dist/studio-arm64 dist/studio-amd64
rm dist/studio-arm64 dist/studio-amd64
chmod +x "$APP/Contents/MacOS/avonioTV Studio"
cp assets/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>avonioTV Studio</string>
  <key>CFBundleDisplayName</key><string>avonioTV Studio</string>
  <key>CFBundleIdentifier</key><string>com.wood29.avoniotv.studio</string>
  <key>CFBundleExecutable</key><string>avonioTV Studio</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>$VERSION</string>
  <key>LSMinimumSystemVersion</key><string>11.0</string>
  <!-- The window is a browser window: no extra Dock icon for the helper. -->
  <key>LSUIElement</key><true/>
  <key>NSLocalNetworkUsageDescription</key><string>avonioTV Studio looks for your avonioTV box on your home network.</string>
</dict></plist>
PLIST
(cd dist && zip -qry "avonioTV-Studio-Mac.zip" "avonioTV Studio.app" && rm -rf "avonioTV Studio.app")
ls -la dist
