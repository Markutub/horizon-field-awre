# Troubleshooting: "AWRE does not show distant terrain"

Written from a real test session (Paper 1.21.11 server, Fabric 1.21.11 client). Every row says what the symptom MEANS and what to do. Nothing here
proves that tiles render correctly on screen: that is still the project's biggest unverified claim (see `docs/STATUS.md`).

| What you see | What it means | What to do |
|---|---|---|
| `tilesSent=1` / `server.tile.sent = 1` in `/awre status` or `/awre stats` | Only the handshake's own test tile (`auto-send-test-tile`) was sent. NO terrain was streamed. | Fix the server stream config (next rows). |
| Startup warning `stream.enabled is false` | `stream.enabled` under `stream:` is false (the default). | Set it to `true` and restart. |
| Startup warning `source is synthetic and synthetic-chunk-radius is 0` | Synthetic terrain with radius 0 is "none" by design. | For your real world use `source: captured`. |
| `/awre stream` shows `terrain chunks in memory: 0` and `stream: not attached` | Nothing was recorded and your session is not receiving a stream. | Same as above; then join and move around so the server loads chunks. |
| `LOD plan R=4000: N tiles ...` in chat | Output of `/awre lodplan` (default radius 4000): a PLANNER demo of how many tiles would be needed. It is not delivery and ignores `stream.radius`. | Ignore it for streaming questions. |
| `/awre status` first line `enabled=true` | The plugin's MASTER switch (the channel works). Not terrain streaming. | Read the `stream: enabled=... source=...` line under it. |
| Startup line `config file <path> -> stream.enabled=... source=...` | The exact file the plugin loaded and the values it READ. | Edit THAT path. If the values are not yours, your edit is in another file. |
| Your edit never shows up | On Windows, Notepad may have saved `config.yml.txt` (Explorer hides `.txt`). Or there are two `enabled:` keys and you changed the top-level one. | Show file name extensions; edit the `enabled:` indented under `stream:`. AWRE never writes `config.yml` back (it only creates a missing one), so it cannot undo an edit. |
| `stream.enabled=true source=captured` but still no distant terrain | Captured mode streams ONLY chunks the server has already loaded; it never generates. | Move around, or load the area (pregeneration may feed it: NOT verified; check that `terrain chunks in memory` rises). |
| Tiles are sent (`sent` well above 1) but nothing extra is drawn | A rendering or visibility problem. Possible: vanilla render distance already covers the area; `render = off` or Distant Horizons/Voxy loaded with `render = auto`; fog or the projection far plane hiding the tiles (UNVERIFIED). | Set the client render distance to 4-6 chunks, check `[AWRE] options:` in `logs/latest.log`, and send the `[AWRE] horizon view:` lines (`ready`, `drawn`). |

## Useful commands and logs
- Server: `/awre status` (channel, `stream:` setting, warnings), `/awre stream` (chunks in memory, sent/held), `/awre report` (writes a result line).
- Server startup log: `[AWRE] config file ...` and any `stream.*` warning.
- Client: `logs/latest.log` lines containing `[AWRE]`: `options:` (value and source of each option), `horizon view:` (`held`, `ready`, `drawn`, `pending`), `render:`.
