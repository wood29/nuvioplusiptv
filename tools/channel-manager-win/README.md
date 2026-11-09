# Nuvio Plus Channel Manager for Windows

A portable, single-file Windows app (`NuvioPlusChannelManager.exe`) for arranging Nuvio Plus Live TV
channels with a mouse: folders, order, numbers, names, hiding, removing countries from the box.

- On the TV: Settings → Live TV → Channels → turn on **Allow the Channel Manager App**.
- Start the .exe on a PC in the same home network: it finds the box by itself and opens in its own window.
- Without the box (off, or away from home) it works from the channel list it saw last time and
  keeps your changes on the PC until the box can take them.
- Its files (last box address, last channel list, waiting changes) are kept next to the .exe.

Built by the Release workflow (Go, no dependencies) from `main.go` and the same page the box serves
(`app/src/main/assets/channel_manager.html`).
