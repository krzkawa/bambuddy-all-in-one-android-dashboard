# Running this dashboard on an iPhone

Written 2026-09-22, against `main` at `4fd2cf6`, in answer to two questions: make the app
work on iOS 12 and later, and can an iPhone scan and write ordinary NFC stickers.

Every claim about Apple's APIs below was checked against Apple's own documentation rather
than recalled, and the exact availability each API reports is quoted.

## Short answer

An iPhone cannot read a genuine Bambu Lab spool tag. Not on iOS 12, not on iOS 26, not on
any iPhone. This is not a deployment-target problem and there is no workaround in software.

An iPhone *can* read an ordinary NDEF sticker, from iOS 11 on an iPhone 7 or newer. It can
only *write* one from iOS 13 — but that does not matter, because the Android phone can do
all the writing. So a sticker on each spool does give an iPhone a way to identify spools.

The iOS 12 floor costs more than it buys, for the reason in section 3: the only iPhones that
stop at iOS 12 are the three that have no third-party NFC at all. It is kept anyway, with the
features gated per version as krzys asked — section 7 sets out where each gate sits.

## 1. Bambu tags and Core NFC

Bambu spool tags are MIFARE Classic 1K. The per-sector keys are derived from the tag's own
UID with HKDF-SHA256, which is what `nfc/BambuKeys.kt` implements; the spool data lives in
those encrypted sectors, not in an NDEF message.

Authenticating a MIFARE Classic sector requires NXP's proprietary Crypto1 handshake. That
runs in the NFC controller, below any public API, and Apple's controller does not implement
it. Apple's own documentation shows the consequence:

- `NFCMiFareFamily` (iOS 13.0+) has exactly four cases: `unknown`, `ultralight`, `plus`,
  `desfire`. There is no `classic`.
- `NFCTagReaderSession` (iOS 13.0+) is described as "A reader session for detecting ISO7816,
  ISO15693, FeliCa, and MIFARE tags" — MIFARE here meaning the four families above.
- `NFCMiFareTag.sendMiFareCommand` exists, but a Classic tag never arrives as an
  `NFCMiFareTag` in the first place. Developers report that a Classic 1K held to an iPhone
  produces no delegate callback at all from `NFCTagReaderSession`: the tag is rejected during
  polling, so the app never even sees the UID it would need to derive the keys from.

So all 495 lines of key derivation and block decoding in `nfc/` are unusable on iOS, not
because they would be hard to port, but because nothing on the platform can hand them sector
bytes.

## 2. What iOS 12 allows, versus iOS 13

Tag-level access arrived after iOS 12. Availability, straight from Apple's documentation:

| API | Available from |
| --- | --- |
| `NFCNDEFReaderSession` — read an NDEF message | iOS 11.0 |
| `NFCTagReaderSession` — ISO7816 / ISO15693 / FeliCa / MIFARE | iOS 13.0 |
| `NFCMiFareTag`, `NFCMiFareFamily` | iOS 13.0 |
| `NFCNDEFTag`, `queryNDEFStatus`, `writeNDEF` — write a tag | iOS 13.0 |
| `NFCNDEFReaderSession.connect(to:)` | iOS 13.0 |

On iOS 12 the whole of Core NFC is one class that reads NDEF messages. No raw tag access, no
UID, and no writing.

## 3. Which iPhones actually stop at iOS 12

This is the part that makes the iOS 12 floor self-defeating.

- Core NFC needs an **iPhone 7 or newer**. The iPhone 6 and 6 Plus have NFC hardware, but it
  is locked to Apple Pay and no third-party app can use it.
- The iPhones whose last iOS is 12.x are the **iPhone 5s, 6 and 6 Plus** — they are still
  getting security updates (12.5.8 shipped in January 2026) and will never go past 12.
- Everything newer is on a later branch: the iPhone 6s, SE (1st gen) and 7 all run iOS
  15.8.x, and the XS and later run current iOS.

The two sets do not overlap. An iPhone that can only run iOS 12 has no usable NFC whatsoever,
and an iPhone that can use Core NFC left iOS 12 years ago. Setting the floor at 12 buys
support for exactly three phones from 2013–2014, all of which are the ones that can scan
nothing.

It is not free either:

- **No SwiftUI.** SwiftUI is iOS 13+, so an iOS 12 app is hand-written UIKit. That at least
  matches how this app is already built — Android Views in code, no XML and no Compose — so
  it is a familiar style rather than an alien one.
- **No simulator.** Xcode 26 will accept a deployment target of 12.0 if it is typed in by
  hand, but its simulators only go down to iOS 15. An iOS 12 build can only be tested on a
  physical iOS 12 device, and Xcode 27 already shows a supported range starting at 15.0.

None of this makes the floor unworkable, and it is the floor krzys asked for. It does mean the
floor is paid for in UIKit and in testing, not in features — an iOS 12 phone loses nothing it
could have used. Section 7 is where each feature's gate lands.

## 4. NFC stickers — the way round the Classic problem

This is the useful finding, and it is closer to done than it looks.

**The Android app already reads stickers.** `nfc/BambuTag.kt` tries the Bambu Classic path
first and falls back to `tryOpenSpool()` for any tag carrying an NDEF record in the OpenSpool
format — see `nfc/OpenSpool.kt`, which is unit tested. A blank NTAG sticker written with an
OpenSpool record is read by the app today. Nothing needs to change for the app to handle two
kinds of tag; that dispatch already exists.

**What is missing is writing.** Roughly:

- A "write this spool to a sticker" action, taking a spool either from Bambuddy's inventory
  or from a Bambu tag just scanned, and writing it as an NDEF text record via `Ndef` /
  `NdefFormatable`. Around 100 lines plus a screen to trigger it. Writing NDEF has been in
  Android since long before API 24, so there is no compatibility question on his phone.
- Worth writing two records: the OpenSpool JSON (so any OpenSpool-aware reader works) and the
  Bambuddy spool id (so the app can look up the live remaining length, which a static sticker
  can never know).

**Buy NTAG215 or NTAG216, not NTAG213.** An OpenSpool record is about 130 bytes of JSON, and
with the NDEF text-record header and TLV wrapper it lands near 145 bytes. NTAG213 has 144
bytes of user memory — right on the edge, before the Bambuddy id is added. NTAG215 has 504
and NTAG216 has 888.

**The workflow this gives him:** scan a Bambu spool once on the Android phone, which decodes
the Classic tag properly, then tap a sticker to copy that onto it. From then on the spool is
readable by an iPhone, by another phone, or by anything else that reads NDEF.

**What the iPhone can and cannot do with those stickers:**

- Read: yes, from iOS 11, on an iPhone 7 or newer, via `NFCNDEFReaderSession`.
- Write: only from iOS 13. This does not block anything, because the writing happens on the
  Android phone. The iPhone only ever reads.
- In Safari: no. WebKit does not implement Web NFC in any version, on any iOS browser,
  including Chrome for iOS. NFC on an iPhone means a native app, full stop.

So stickers do make iPhone scanning possible — but only in a native iOS app, which is what
section 6 costs out.

## 5. What ports from the Kotlin app

Measured on `main` at `4fd2cf6`, after the GUI rework merged:

| Area | Lines | Ports? |
| --- | ---: | --- |
| `nfc/` pure logic — keys, blocks, sectors, OpenSpool, SpoolTag | 495 | Ports cleanly, but is dead weight on iOS: nothing can feed it Classic sectors. The OpenSpool parsing is the one part a sticker-reading iOS app would want, and it is ~64 lines. |
| `nfc/BambuTag.kt` — Android NFC transport | 221 | No iOS equivalent exists. |
| `net/Api.kt` — REST client | 417 | Shape ports, code does not: OkHttp → `URLSession`, `org.json` → `Codable`. |
| `net/Repo.kt` — polling, state | 220 | Shape ports: coroutines + `StateFlow` → `async`/`await` + an observable object. |
| `net/Prefs.kt` — credential storage | 196 | `EncryptedSharedPreferences` → Keychain. |
| `ui/` — 27 files, all ten tabs, incl. a hand-rolled MJPEG decoder | 5,548 | Complete rewrite. |

About 85% of the 7,197 Kotlin lines is a rewrite either way. The Bambuddy API surface and the polling
behaviour are the real assets, and those are knowledge rather than code — they transfer
whether or not a single line does.

**Kotlin Multiplatform** is the alternative to rewriting `net/`. It is not worth it here: it
means restructuring the single Gradle module into KMP source sets, swapping OkHttp for Ktor,
and it shares about a sixth of the code while adding a toolchain that still needs a Mac at
the end of it.

## 6. What it costs outside the code

- **A Mac.** Xcode runs on nothing else, and signing an app requires it. There is no Linux
  path, so this is a hard gate: no Mac, no native iOS app.
- **An Apple Developer Program membership, $99/year**, for a build that lasts a year on his
  phone. Without it, a free Apple ID works but the app expires **7 days** after signing and
  must be re-signed from the Mac every week, with a limit of 3 apps installed at once and
  about 10 new app IDs per rolling week.
- **CI.** The current build runs on Linux GitHub Actions runners. iOS needs macOS runners,
  which bill at a **10x multiplier** on a private repo — the 2,000 free minutes become about
  200 macOS minutes a month, and past that macOS is $0.062/min against $0.006 for Linux.
- **Getting it onto the phone.** There is no equivalent of the `latest` release link he
  installs the APK from. An `.ipa` cannot be downloaded and opened from Safari. It is a cable
  and Xcode, or TestFlight (which needs the $99 membership, and Apple review for anyone
  outside his own devices), or AltStore.

## 7. How the features gate by iOS version

krzys settled this on 2026-09-22: build the iPhone app, and gate features on the iOS version
rather than hold everything back to one floor. Deployment target 12.0, then:

| Feature | Gate | Why |
| --- | --- | --- |
| Dashboard, printers, control, camera, AMS, queue, history, stats, settings | iOS 12.0, every device | All plain HTTP to Bambuddy; nothing here touches NFC. |
| Read an NDEF sticker | iOS 12.0 floor, iPhone 7 or newer | `NFCNDEFReaderSession` is iOS 11.0+, so it is available at the floor. The real gate is hardware, checked at runtime with `NFCNDEFReaderSession.readingAvailable`, which is false on an iPhone 6 or older. |
| Write an NDEF sticker | iOS 13.0 | `NFCNDEFTag.writeNDEF` and `NFCTagReaderSession` are both iOS 13.0+. Below that the button is hidden; the Android phone writes stickers anyway. |
| Read a genuine Bambu Classic tag | never, on any iOS version | Section 1. The scan entry point simply does not exist on iOS. |

Two consequences of holding the target at 12.0, worth being clear about rather than
discovering later:

- **The whole UI is UIKit.** SwiftUI is iOS 13+, and mixing the two across a 5,500-line app to
  save nothing is not worth it. UIKit in code matches how the Android app is already written,
  so this is the familiar style, not a penalty.
- **iOS 12 can only be tested on real hardware.** Xcode's simulators stop at iOS 15. CI can
  prove an iOS 12 build compiles; only a physical iPhone 5s, 6 or 6 Plus can prove it runs,
  and those are the three phones that can scan nothing anyway.

**Assumption, because it has never been stated:** which iPhone and which iOS version he
actually has. Everything above is written so that it does not matter — the gates sort
themselves out at runtime — but if the phone turns out to be an iPhone 7 or newer, which is
every iPhone still in use that can do NFC at all, then in practice it is running iOS 15.8.x
or later and every gate above is open except Bambu Classic scanning.

**A note for later, not a question:** everything except sticker reading is plain HTTP, and a
browser page served from his own Bambuddy host would deliver that part on any iPhone with no
Mac, no $99/year and no App Store. Bambuddy's missing CORS headers only bite a page served
from somewhere else, so a same-origin page has no such problem. It is not a substitute for
the native app he asked for — WebKit implements no Web NFC on any iOS version, so a browser
page can never read a sticker — but it is the cheap fallback if the Mac or the developer
account turns out to be the thing that stops this.

## 8. If genuine Bambu tags ever have to be read from the iPhone

The only route is external hardware: an ESP32 with a PN532 or RC522 reader can run Crypto1
and read the Classic tag properly, then push the spool to Bambuddy over WiFi. The community
already builds exactly this (BambuTagger and similar projects). Any phone, iPhone included,
then assigns the spool from Bambuddy's inventory without touching NFC at all. It is a soldering
job rather than a software one, and it is genuinely the only way an iPhone participates in
reading a real Bambu tag.

## Sources

- [NFCMiFareFamily](https://developer.apple.com/documentation/corenfc/nfcmifarefamily)
- [NFCMiFareTag](https://developer.apple.com/documentation/corenfc/nfcmifaretag)
- [NFCTagReaderSession](https://developer.apple.com/documentation/corenfc/nfctagreadersession)
- [NFCNDEFReaderSession](https://developer.apple.com/documentation/corenfc/nfcndefreadersession)
- [NFCNDEFTag](https://developer.apple.com/documentation/corenfc/nfcndeftag)
- [Core NFC](https://developer.apple.com/documentation/corenfc)
- [iOS NFC read Mifare 1k classic tag's uid — Apple Developer Forums](https://developer.apple.com/forums/thread/717021)
- [Bambu-Research-Group/RFID-Tag-Guide](https://github.com/Bambu-Research-Group/RFID-Tag-Guide)
- [Apple iOS 11 supports reading NFC tags for iPhone 7 and 8 — GoToTags](https://gototags.com/articles/apple-ios-11-supports-reading-nfc-tags-for-iphone-7-and-8-with-core-nfc-api)
- [Maximum supported iOS for all iPhones — EveryMac](https://everymac.com/systems/by_capability/maximum-ios-supported-by-all-iphone.html)
- [What will be the minimum iOS deployment target for Xcode 26? — Apple Developer Forums](https://developer.apple.com/forums/thread/799576)
- [Developer account overview — Apple Developer](https://developer.apple.com/support/compare-memberships/)
- [Update to GitHub Actions pricing — GitHub Changelog](https://github.blog/changelog/2025-12-16-coming-soon-simpler-pricing-and-a-better-experience-for-github-actions/)
- [NDEFReader — MDN](https://developer.mozilla.org/en-US/docs/Web/API/NDEFReader)
