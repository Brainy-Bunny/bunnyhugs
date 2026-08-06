# Twogether 💛

A cute, fully offline couple app. Two paired phones detect each other over
Bluetooth (no internet, no accounts, no server — everything stays on your
two devices) and the app tracks how much time you spend together, celebrates
reunions, nudges you to take a photo after 15 minutes together, and gives you
a shared space for date ideas, memories, and streaks.

## Install (both phones)

1. Copy the current `Twogether-<version>.apk` (e.g. `Twogether-2.4.apk`) to each
   phone (email it to yourself, AirDrop-equivalent, USB, Google Drive — anything).
   The version in the filename always matches the latest build in this folder;
   after that, the in-app auto-updater keeps both phones current automatically.
2. On each phone: tap the APK file → allow "install from this source" if asked
   → Install.
3. Open the app on both phones and grant the Bluetooth + notification
   permissions when asked (on Android 11 and older, it'll also ask for
   Location permission — that's only required by Android itself for Bluetooth
   scanning; the app never reads or stores your location).

## Pairing (do this once)

1. On phone A: **Create Pair** → you'll see a 6-digit code.
2. On phone B: **Join Pair** → type in that code.
3. That's it — no accounts, no internet round-trip. The code is only used to
   generate a private shared key so your two phones recognize each other (and
   nobody else running the app).

## What it does

- **Home** — live "together" status with a pulsing heart, streaks at a glance.
- **Stats** — total hours together, daily & weekly streaks (shown separately),
  longest session, reunions, days together, favorite day of the week.
- **Calendar** — a month view with your together-days highlighted.
- **Photo reminder** — after 15 minutes continuously together, a gentle nudge
  to snap a photo (snoozable to any length you want).
- **Our Moments** — the photo gallery, grouped by day, plus a "memory of the
  day" throwback card on Home.
- **Date Ideas** — a shared checklist that syncs between your two phones
  whenever you're together (fully offline, peer-to-peer over Bluetooth).
- **Time Capsules** — write a note that unlocks once you've spent a chosen
  number of hours together.
- **Badges** — milestones for hours, streaks, and reunions, each with an
  unlock date.
- **Manual entry** — add past time together (before you installed the app, or
  anything Bluetooth missed) from the Calendar screen.
- **PIN lock** — optional, in Settings, if you want to keep the app private.

## Notes

- Both phones need Bluetooth turned on. The app runs a small background
  service (you'll see a permanent notification while it's active — that's
  required by Android for reliable Bluetooth scanning, and it doubles as your
  live "together" status).
- Nothing ever leaves your two phones — there's no server, no account, no
  analytics, and Android's automatic app-backup is explicitly turned off so
  none of this data can end up in a cloud backup.
- This build targets Android 8.0 (Oreo) and up.

Built and hardened through several rounds of multi-model code review and
live two-device testing — enjoy!
