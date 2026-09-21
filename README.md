# Bambuddy all-in-one dashboard

An Android app for a [Bambuddy](https://bambuddy.cool) server: printers, AMS,
queue, history, statistics and camera on one landscape screen, plus reading a
spool's NFC tag and assigning that spool to a chosen AMS slot.

Built for an old phone. It targets Android 7.0 and up, keeps the screen on, and
is locked to landscape so it can sit propped up next to a printer.

## Getting the app

Every push builds an APK. Open the latest run under
[Actions](../../actions/workflows/build.yml), and download the
`bambuddy-aio-apk` artifact at the bottom of the page. It holds two files:

- `bambuddy-aio.apk` — the one to install.
- `bambuddy-aio-debug.apk` — same app with logging left in.

The phone will ask permission to install an app from outside the Play Store.

## Setting it up

On first launch the app asks for:

- **Server address** — where Bambuddy is on your network, like
  `http://192.168.1.50:8000`.
- **An API key**, made under Settings → API Keys on the server, with Read
  Status, Control Printer and Manage Inventory turned on. This is the better
  option for a phone left on a shelf, because it does not expire.
- **Or your account** — the same username and password you use in the browser.
  Two-factor accounts are not supported yet; use an API key for those.

Leave both blank if your Bambuddy has authentication switched off.

## Scanning spools

Hold a spool against the back of the phone. Genuine Bambu Lab spools carry a
Mifare Classic tag whose sectors are locked with keys derived from the tag's own
UID, using the published research at
[Bambu-Research-Group/RFID-Tag-Guide](https://github.com/Bambu-Research-Group/RFID-Tag-Guide).
The app derives those keys, reads the filament type, colour, weight and
temperatures, and looks the spool up in your Bambuddy inventory. OpenSpool NDEF
tags are read too, and an unrecognised tag can still be linked to a spool by
hand.

Once the spool is known, its AMS slots appear as buttons: one tap assigns it.
The assignment is stored on Bambuddy and it configures the tray over MQTT.
Nothing is ever written back to the tag.

Two caveats worth knowing. Reading a Bambu tag needs a phone whose NFC chip
supports Mifare Classic — NXP chips do, some others do not, and the app says so
when it hits one. And a spool tag sits in the cardboard core near the rim, so
that is where to hold it.

## Building it yourself

Android Studio, or:

```
./gradlew assembleRelease
```

The release build is signed with the debug key so that CI can produce something
installable. Replace that before it goes anywhere but your own phone.
