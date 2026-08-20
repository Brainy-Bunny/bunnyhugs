# Twogether — UX Fix Plan (post 4-model advisory round, 2026-08-20)

Source: F:\Claude\Twogether App\UX-AUDIT-BACKLOG.md (21 raw items) + independent audits from Sonnet,
Opus, Fable, Haiku (all converged, cross-verified with file:line evidence) + two targeted follow-up
investigations (timer-reset grace window, update-nag reach) + user's answers to 5 clarifying questions.

Resolved decisions baked into this plan (do not re-litigate):
- Time capsule: delete removed entirely, no exception (literal ask).
- Date format: `dd MM yyyy` everywhere, one shared formatter.
- Stats "Down" tile: show actual **days** down by (user's literal wording), not hours.
- Badges meter: build ONE compact "next badge" progress card on Home (all models agreed it fits; user
  said "add it if you agree" — they do).
- Timer-reset fix: keep the fast ~100s Apart/Together flip for the status card exactly as today (so
  "Apart" shows immediately, honest moment-to-moment) — but the underlying `TogetherSession` row and
  the continuous timer's `startedAt` do NOT reset on a brief gap. If the partner reconnects within a
  grace window (~10 min), the session never actually closed, so the "Together for" timer snaps back to
  the old running total instead of restarting from zero. If they don't reconnect in time, the session
  closes for real, exactly like today.
- "You're apart" notification: confirmed NOT an alert (silent, ~once/min ongoing status text) — no new
  alert to build, just fix its number formatting via the shared relative-time utility. Do not add a new
  periodic "apart" ping (user explicitly doesn't want that).
- v2.7 already contains the real note-loss bug fix — user told to update now regardless of this plan.
- Item 21 (notification bell) folds into item 7 (permission nagging) — one shared "inbox" for anything
  currently scattered across banners/dialogs/one-shot notifications.

## Phase 1 — Shared infrastructure + isolated high-value fixes
(Do first: later phases depend on the formatters. All of these are self-contained otherwise.)

1. `util/DateFormats.kt` — one shared `dd MM yyyy` date formatter (+ time formatter), replace every
   call site found across all 4 audits (Moments, Stats x2, Badges, Settings, Calendar, GalleryImport —
   ~15+ sites cataloged in the audits above).
2. `util/RelativeTime.kt` — one shared `relativeAgo(millis)` (just now/Xm/Xh/Xd, no giant raw-minute
   numbers) and `formatDuration(millis)` (Xm/XhYm/XdXh, rolls to days). Replace the 4-5 duplicated
   ad-hoc implementations (OurListsScreen, SettingsScreen, HomeScreen x2, ProximityForegroundService).
   This alone fixes the "972m ago" complaint and the notification's "96h 12m ago" text.
3. `HoursDetailScreen.DailyHoursBarChart` — add missing per-bar value labels (matches the pattern
   already used correctly in the Monthly and Weekday charts).
4. Bar-chart label/bar alignment fix across all 3 chart screens (shared root cause, bundle together).
5. Stats "This month vs last" tile — surface the actual day-delta number (already computed and
   discarded in `StatsCalculator.compute`), make it clickable like its siblings.
6. Fix the rolling-window bug in `MonthlyDetailScreen` (6-month) AND `HoursDetailScreen` (14-day) —
   anchor pages from "now" backward, not from the couple's first together-month forward.
7. Moments notes: reorder so an existing note (partner's AND your own) renders as a read-only card
   ABOVE the input, with an Edit affordance. This is the single fix for BOTH item 9 (ordering) and
   item 20 (the "Saved but doesn't appear" bug — root cause was there was never a rendered card for
   your own note, only the input field).

## Phase 2 — Real behavior/bug fixes
8. App lock: stop re-locking on process `ON_STOP`. Lock only on `ACTION_SCREEN_OFF` or genuine app
   close (task removed) or cold start; add a short grace window so switching apps briefly never
   re-triggers the PIN screen.
9. Together-timer grace-window fix (see resolved decision above) — new `pendingApartSince` state,
   ~10 min grace before a session actually closes, self-heal logic updated to handle a restart mid-grace.
10. Reunion celebration: drop the same-calendar-day requirement (currently an overnight or multi-day
    reunion never celebrates/counts, which is clearly wrong) — keep the ≥60min gap requirement.
11. Time Capsule: remove delete entirely (all states); add creation date + unlock-hours + (once
    unlocked) unlock date to BOTH card states — currently blanks out on unlock.
12. Gallery import: auto-detect "taken together" by checking whether the photo's timestamp falls
    inside an existing session window (currently always defaults to apart, even when the user
    explicitly ticks "we were together that day" for that exact window — active contradiction bug);
    keep the real EXIF time-of-day instead of forcing noon.
13. Calendar day-cell photo marker — fix inverted logic (currently shows a "no photo" icon on days
    WITH photos and nothing on days without, per Sonnet/Opus/Fable's independent finding).
14. Update-nag reach fix: surface `pendingUpdateVersionCode` as a Home banner (not Settings-only);
    bump the update channel to importance HIGH; tighten WorkManager job resilience; also check for
    updates from the foreground service's existing tick (process is already alive/networked there).
15. Badge unlock-date bug: record the unlock timestamp in the service's periodic tick, not lazily when
    the user happens to open the Badges screen (currently shows the wrong "earned on" date).

## Phase 3 — New features / UI work
16. Camera screen overhaul: front/back flip, volume-button shutter, flash/torch toggle, tap-to-focus,
    pinch-zoom, a working close button (the `onCancel` param is currently dead code), permission-denied
    recovery (grant button + settings deep link), brief post-capture retake step.
17. Manual "taken apart"/"taken together" edit toggle on a Moment (currently capture-time-only, never
    editable).
18. Replace every free-text date/time entry field with native Material3 `DatePickerDialog`/
    `TimePickerDialog` (Moments gallery import, Calendar manual session, Time Capsule hours).
19. "Edit", not just delete: manual calendar sessions, milestone label/date, list/list-item names —
    same underlying gap (add-only, delete-only) repeated across 3+ screens; the sync/merge layer
    already supports edits arriving over the wire in most cases, this is mostly a UI-layer pass.
20. Cross-feature navigation: Calendar day → that day's Moments photos and back; Time Capsule → the
    day it unlocked; Home throwback/on-this-day card → the actual Moment/date; Milestone → Calendar
    date; Badge → the stat that earned it.
21. Badges: single compact "next badge" progress card on Home, tappable through to the Badges screen.
22. Notification bell icon next to Settings gear — panel aggregating pending-resync-approval, update
    available, battery-optimization warning, missing-permission nags, etc. Badge dot persists until
    everything in it is resolved.
23. Reminder notifications: new high-importance, DND-bypassing channel; explicit
    `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` request flow that keeps re-surfacing until granted
    (feeds into the item-22 inbox rather than a one-off banner); a single ordered permission queue so
    dialogs/banners never stack (fixes the found battery-optimization-blocks-BLE-grant bug).
24. Configurable photo reminder interval (currently hardcoded 15 min) + reminders on lists/list items
    ("remind me X minutes after we're together").

## Phase 4 — Larger net-new feature (own migration/sync work)
25. Calendar day notes: new `DayNote` entity (mirroring `MomentNote`'s per-device LWW+tombstone shape),
    DAO, repository, Room migration, sync-payload wiring, backup wiring, UI field in the day-detail
    dialog, and a marker on days that have one.

## Deferred / lower priority (noted, not scheduled yet)
- Biometric unlock on the PIN screen (would soften the impact of item 8's lock fix even further).
- Partner name/emoji editable after pairing.
- Pairing code copy/share button.
- Various small polish items from the B-lists (dead `onCancel`, snooze-chip highlight logic, etc.) —
  will be swept up opportunistically while touching nearby code in the phases above, not scheduled as
  standalone work.
