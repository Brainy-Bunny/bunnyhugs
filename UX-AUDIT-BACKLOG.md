# Twogether App — UX Audit Backlog (raw user input, 2026-08-20)

Source: user reported these directly after v2.7 shipped. This file is the durable record of the raw
ask before the multi-model advisory round groups/expands it. Do not lose or silently drop any item
below during triage — each one needs an explicit verify/fix/reject-with-reason outcome.

## Explicit feature requests / bugs (verbatim intent, organized)

1. **Time capsule cannot be deleted.** Currently it apparently can be — remove that ability.
2. **Time capsule should always show its timeline**: when it was created, and the unlock duration
   (hours needed) — and this info should STILL be visible even after the capsule has been unlocked
   (i.e. also show when it WAS unlocked, not just blank out the creation info post-unlock).
3. **Camera screen missing obvious controls**: front/back flip button, volume-button-as-shutter, and
   "other obvious additions like this" — i.e. treat this as an example, not an exhaustive list; find
   the rest of what a normal camera screen would have.
4. **App re-locks too aggressively.** Should stay unlocked until the phone itself locks or the app is
   fully closed — NOT re-lock just because the user briefly switched to another app.
5. **"You're apart" notification frequency is unclear/possibly wrong** — user is asking "how many times
   does it fire?" as an open question. Needs investigation of actual current behavior, not just a fix.
6. **Reunion-before-target-time logic**: if the couple reunites before the scheduled "reunion time",
   the countdown/timer should reset/resume from that point instead of whatever it currently does
   (going negative, staying frozen, etc. — verify current behavior first).
7. **Reminder notifications should be loud and override DND.** Explicitly request this permission from
   the user (don't just silently try and fail), and keep nagging/re-prompting until granted. This
   applies as a general rule to ALL permission requests in the app — make sure none of them conflict
   with each other (e.g. two permission dialogs racing, or one blocking another).
8. **Moments — manual status edit + auto-detect + pickers + format**:
   - Add ability to manually edit a Moment's "taken apart" vs "taken together" status.
   - Investigate: does the app currently auto-relate an uploaded photo to a "together" session based
     on the photo's own date/time falling inside that session's window? If not, it should.
   - Improve the date/time picker UX (currently apparently not great).
   - Use **DD MM YYYY** format consistently EVERYWHERE in the app (this is the format the couple uses).
9. **Moment notes ordering**: an already-written note (partner's or own) should render ABOVE the
   "write your own note" input box — i.e. existing content should be more visually prominent than an
   empty input inviting more typing.
10. **Open question**: would a badges/achievement meter be more engaging featured on the home screen?
    (Not a confirmed ask — a suggestion to evaluate and give an opinion on, or implement if it clearly
    fits the app's existing gamification.)
11. **Stats bar graphs need exact values.** Right now bars only show relative height differences; the
    precise number should be visible on/near each bar.
12. **"Synced Xm ago" text needs unit auto-scaling.** Currently shows raw minutes even at absurd values
    (e.g. "972m ago"). Should roll over to hours, then days, automatically — and this rule should be
    applied consistently EVERYWHERE in the app that shows a relative time, not just this one spot.
13. **Reminders for lists**: ability to set a reminder on an individual list item OR on a whole list
    (e.g. "remind me X minutes after we're together"). Similarly, the existing "take a photo" reminder
    should be customizable (currently apparently fixed/not configurable).
14. **Meta-instruction**: the app has many "obvious" missing features / inconsistencies beyond what's
    listed above (camera flip, date format, minutes-only sync time are given as *examples* of the
    class of issue, not the full set). Run an advisory round with ALL models to compile the complete
    list of this class of issue for better intuitive UX, then fix everything found.
15. **Stats "Down (this month vs last)" tile**: should display the actual number of days down by (not
    a vague indicator), and should be clickable to open the same kind of 6-month bar-chart drill-down
    that the "Most Met" stat already has.
16. **"Last 6 months" bar charts are wrong window.** They currently appear to show a fixed calendar
    range (e.g. Jan–Jun); they should be a genuine rolling window of the 6 months immediately prior to
    the current month.
17. **Cross-feature navigation/sync is missing.** Tapping a date on the Calendar should be able to jump
    to the Moments/photos from that date, and vice versa. Same idea for Time Capsule — should link
    through to when it was unlocked. General principle: related data across screens should be
    cross-clickable, not siloed.
18. **Add a Notes option on Calendar days** — free-text note attachable to a specific day, independent
    of Moments.
19. **Pre-added/manually-added calendar dates should stay editable after creation**, not just at
    creation time.
20. **Bug: Moments note "Saved" toast lies.** Clicking Save shows a "Saved" confirmation, but the note
    does not actually appear in the UI afterward — likely a real persistence or refresh bug, not just
    copy.

21. **New: in-app notification panel.** Add a bell-icon button next to the gear/settings icon (likely
    on Home's top bar) that opens a panel listing all "persistent" notifications the app currently
    surfaces piecemeal — update-available, pending-resync-approval, battery-optimization warning,
    permission-missing nags, etc. The bell should show a dot/badge as long as ANY of those items is
    unresolved, and clear once they're all addressed/dismissed. Requested while the advisory round was
    still running — folds into the item-7 permission-nagging work and the item-17 cross-feature-nav
    work, since it's effectively a single inbox for everything currently scattered across banners,
    one-shot system notifications, and dialogs.

## Instruction for the advisory round

Beyond verifying/grounding the 20 items above against the real codebase, each model should
independently scan the whole app (screen by screen) for the SAME CLASS of issue: obvious missing
interactions, formatting inconsistencies, and features a normal user would expect but that are absent
— the kind of thing that makes an app feel "not very consistent" even when individual features work.
Ground every finding in actual file:line references, not speculation. Do not implement fixes — this is
audit/advisory only.
