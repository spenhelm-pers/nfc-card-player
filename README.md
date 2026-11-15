# TagTunes

A phone-based Toniebox: put an NFC tag on the back of the phone and it plays an audio file stored on the phone.

## Build and install on a Pixel 7
1. Open this folder in Android Studio (File > Open). Let it sync; it downloads Gradle 8.9 and Android Gradle Plugin 8.7.3. Accept any upgrade prompts if you like.
2. On the phone: Settings > About phone > tap Build number 7 times, then Settings > System > Developer options > USB debugging on.
3. Plug the phone in, accept the debugging prompt, pick it in the device dropdown, press Run.

## Using it
- Copy MP3s onto the phone (Music or Download folder, or a synced folder).
- Tap an unknown tag: choose "Pick songs" (one or more files) or "Pick a folder" (plays every song in it, in name order; add files to the folder later and they show up).
- Tap a paired tag: the player screen opens and starts playing. Big Play/Pause, previous/next song, volume down/up.
- Lifting the tag does nothing; playback keeps going. Each tag remembers which song it was on and where.
- Previous restarts the current song if you're more than 3 seconds in, otherwise goes back a song.
- To leave the player screen, press and hold "⟵ Tags" (system Back is ignored there so kids stay put).
- Music keeps playing with the screen off or in other apps. Control it from the notification, the lock screen, or Bluetooth speaker/headphone buttons. Tapping the notification opens the player.
- It pauses for phone calls, and pauses if a Bluetooth speaker or headphones disconnect instead of switching to the phone speaker.
- "Volume limit": press and hold to set a maximum volume (30–80%). On-screen buttons, phone buttons, speaker buttons and the volume slider can't go past it.
- Tap a tag in the list to play, rename, change what it plays, or forget it.
- "Pin app" uses Android screen pinning so a kid can't leave the app. Enable it under Settings > Security > App pinning. Unpin by holding Back + Overview.

## Look
- "Reading Room" design: walnut, brass and cream, the current book standing on a library shelf.
- Cover art: put `cover.jpg` (or folder.jpg / front.jpg / .png) in a tag's folder, or use MP3s with embedded artwork. Without art, the book shows the tag's name on a green cloth cover.
- Font: Lora (SIL Open Font License), bundled in `app/src/main/res/font`.

## Tablets/phones without NFC (e.g. Galaxy Tab A SM-T350)
- Use a USB PN532 reader with a built-in CH340 chip, plugged in through a USB OTG cable.
- Set the reader's switches to HSU (UART) mode.
- When you plug it in, Android asks whether to open TagTunes; tick "Use by default" and accept. That grants USB access.
- Tags work the same as phone NFC (tap once to start; resting a tag on the reader doesn't retrigger). Tags paired on one device use the same IDs on another.
- The reader handles NTAG and MIFARE tags/cards (including the cards it ships with).

## Notes
- Tags: NTAG213/215 stickers or cards work best. 125 kHz "RFID" fobs won't read on a phone.
- NFC tags only read while the app is open and the screen is on and unlocked; the app keeps the screen on while open.
- Swiping the app away from recents keeps music going if it's playing; stop it from the notification.
- Progress is saved every few seconds while playing.
- The Pixel 7's NFC antenna is around the middle of the back, below the camera bar.
