# Changelog

All notable changes to Foldman. Versions follow [semantic versioning](https://semver.org); downloads are on the [releases page](https://github.com/deveshrai/Foldman/releases).

## [0.2.0] — 2026-11-01

### Changed
- **The player is now drawn from a single piece of artwork**, with every moving part laid over it: the five keys latch like the mechanism, the reels turn (and blur while winding), the volume knob is turned with a finger, the position bar can be tapped or dragged, and the clock, mode symbol and lamps are live. Title and artist are written on the cassette label.
- **The tape on the reels now grows and shrinks** with the song: the take-up reel fills while the supply reel empties.
- **Tall phones no longer get black bars.** Extra plain body is added above the brand name and between the window and the counter; the keys, window and dials keep their size.
- **Sound.** The TAPE and NR controls are now a real signal chain on local playback (tape-type curves, a treble expander for NR) instead of an equaliser, and the VU lamps read with proper VU ballistics.

### Added
- **Tape settings** (press and hold the cassette): wow and flutter, hiss, saturation, head bump, ReplayGain levelling, auto-reverse (side B) and a sleep timer that fades out over its last 15 seconds.
- **Side B.** With auto-reverse on, the tape turns over at its end and plays on; the label shows B and the reels swap jobs.
- **Faster starts for big libraries.** The library is remembered between runs and only new or changed files are read again. All tags are read now (it was the first 600), three files at a time.
- **Screen reader support** for the keys, knob, position bar, switches and cassette, with state announcements and custom actions.
- **Unit tests** (sound engine, library cache, reel physics, lamp scale, body stretch) run on every build.

### Fixed
- Albums with the same folder name in different places are no longer merged.
- Apple Music commands sent before its player process is up (or while it restarts) are held and sent, not lost.
- The update loop rests while the app is off screen, saving battery.
- Cover pictures are only fetched over https or from content providers and are size-limited.
- Old folder permissions are released when a new folder is chosen.
- A changed folder no longer restarts the song that is playing.

## [0.1.2] — 2026-10-21

### Changed
- **Foldman's settings no longer go into Android backups or device transfers.** They include your Apple Music user token, which now stays on the phone. When you move to a new phone, sign in to Apple Music again there.

## [0.1.1] — 2026-10-21

### Fixed
- **Apple Music showed the next song instead of the one playing.** MusicKit lists only the songs *after* the current one in its queue, and Foldman read the playing song's position from that list. The title, artist, cassette label and cover now come from the song that's actually playing, and the queue and Up next start from it.
- **"Track 0 of N"** for Apple Music now shows the right position and count, including songs already played.

### Changed
- Apple Music playlists always play in order: Foldman explicitly turns shuffle off when it loads one, and turns it off again if Apple's player ever reports it on.

## [0.1.0] — 2026-10-21

The first release.

### Added
- **Three postures.** On the cover screen, an edge-to-edge upright portable player with the tape on end behind a window and the keys down a silver side strip. Unfolded, the same player laid down as a deck between a library panel and a now-playing panel. Half-open on a table (Flex mode), the deck on the top half and the library on the bottom.
- **The mechanism.** Latching PLAY, PAUSE, STOP·EJECT, FF and REW keys and AMS skip buttons. Reels turn at tape speed ÷ radius with spin-up and coast-down. A mechanical tape counter, VU needles that follow the real audio on local files, an OPERATE LED, synthesised key clicks and haptics, and CLICK / HAPTIC switches.
- **Local folders.** Media3 playback in a background service with media controls. Album art comes from embedded art or a cover image in the folder, and each sub-folder is listed as an album.
- **Apple Music via MusicKit** (optional AARs). Sign-in, library playlist browsing through the Apple Music API, and in-app streaming in a separate, crash-isolated process that resumes the song after a crash.
- **YT Music and Apple Music via media sessions.** Transport, artwork and queue, using notification access.
- Adaptive launcher icon with a themed (monochrome) layer.

[0.2.0]: https://github.com/deveshrai/Foldman/releases/tag/v0.2.0
[0.1.2]: https://github.com/deveshrai/Foldman/releases/tag/v0.1.2
[0.1.1]: https://github.com/deveshrai/Foldman/releases/tag/v0.1.1
[0.1.0]: https://github.com/deveshrai/Foldman/releases/tag/v0.1.0
