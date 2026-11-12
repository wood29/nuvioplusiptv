# avonioTV Studio (Windows and Mac)

The desktop app for arranging avonioTV Live TV channels with a mouse: folders, order, numbers,
names, hiding, removing whole countries from the box.

- On the TV: Settings → Live TV → Channels → turn on **Allow avonioTV Studio**.
- **Windows:** start `avonioTV-Studio.exe` (portable, one file).
- **Mac:** unzip `avonioTV-Studio-Mac.zip` and move **avonioTV Studio** to Applications.
  The first time: right-click → Open (it isn't from the App Store).
- It finds the box on the home network by itself and opens in its own window (Chrome or Edge as
  an app window; otherwise the normal browser).
- Without the box (off, or away from home) it works from the channel list it saw last time and
  keeps your changes until the box can take them.

Built by the Release workflow with `build.sh` from `main.go` and the page the box serves
(`app/src/main/assets/channel_manager.html`). Downloads (always the newest):
`https://github.com/wood29/nuvioplusiptv/releases/download/downloader/avonioTV-Studio.exe` and
`.../avonioTV-Studio-Mac.zip`.
