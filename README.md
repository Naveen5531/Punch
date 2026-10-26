# Punch APK
Your index.html wrapped in an Android app with a foreground location service, so auto check-in/out keeps working with the screen off or phone locked.

## Build (pick one)
1. Android Studio: File > Open this folder > Build > Build APK(s). Output: app/build/outputs/apk/debug/app-debug.apk
2. No install: push this folder to a GitHub repo; Actions > "Build APK" > download artifact "Punch-apk".
3. Command line with Android SDK + Gradle 8.9: `gradle assembleDebug`

## First run on the phone
Allow location > "Allow all the time" > allow notifications > "Unrestricted" battery. Tick "Auto check-in" in Settings.
On Xiaomi/Oppo/Vivo/Samsung also enable Autostart / "Never sleeping apps".

## Changes in v1.1
- Downloads now save with proper names (Punch_Attendance_Last-30-Days_2026-10-07.csv, Punch_Salary_<month>_<date>.csv, Punch_Trip_Log_<date>.csv).
- Payslip button opens Android's print dialog; choose "Save as PDF" (file name Punch_Payslip_<month>_<date>).
- Shift alarm > "Choose audio file" now opens the phone's file picker (the app was missing the file-chooser hook).
- Maps search: live suggestions while typing, results ranked by distance from you, spelling fallback, coordinates / Google Maps long-link paste, and a "Navigate in Google Maps" button.

## Changes in v1.2
- Alarm: "Choose audio file" now uses the phone's own picker; tones up to 12 MB are stored inside the app.
- Maps (still free OpenStreetMap, no Google key): search now also checks local shops/businesses by name within 30 km, plus one-tap "nearby" chips (petrol pump, ATM, hospital, pharmacy, ...).
- Downloads are named by content: Attendance_Report_Last_30_Days_<date>.csv, Salary_Statement_<month>_<date>.csv, Trip_Log_<date>.csv, Payslip_<month>_<date>.pdf.

## Changes in v1.3
- Attendance: new "Comp off" mark (calendar, bulk range, and per-day buttons in Reports). Shown as CO in teal, counted as a paid day in Salary and the payslip.
- Maps: new "Exact location" box with latitude / longitude fields: fetch your GPS position (with accuracy), go to any typed coordinates, copy them, or open them in Google Maps.

## Changes in v1.4
- Attendance tab > "Holidays and Comp off": upload Holidays and Optional holidays from Excel (.xlsx) or .csv with columns Date, Holiday, Occasion, Region. A template (Holiday_List_Template.xlsx) can be downloaded from the same card.
- Region filter: pick your region so only your holidays (plus All / India / National) count.
- Working on a holiday earns Comp off (full day = 1, half day = 0.5). Comp off earned / used / balance is shown in Attendance and Reports; marking a Comp off day uses it.
- Optional holidays have a Take button; a taken optional holiday behaves like a holiday.

## Changes in v1.5
- Attendance tab > "Leave balance": PL, SL and CL balances. Defaults: PL 21 a year (1.75 a month), SL 1.25 a month, CL 1.25 a month. Rates, opening balance and the month counting starts from are editable.
- Leave can be marked as PL / SL / CL from the calendar, the date-range option, and the per-day buttons in Reports; each day is deducted from that balance.

## Changes in v1.6
- Tenure now shows in years and months (for example "2 yr 3 mo") on the Profile and on the payslip, counted from the Joining date in Settings.
- Expenses: if no admin PIN is set you can add and delete expenses straight away (before, it asked for a PIN that did not exist). Once a PIN is set it locks as before. Expenses are still deducted from that month's take-home.
- Auto restart: if Punch is swiped away from recent apps or killed, an alarm restarts the tracking service within seconds, plus a 15-minute watchdog. Only runs while "Auto check-in" is ticked. Android does not let an app re-open its own screen, so tap the "Punch is running" notification to see the app.
- A manual "Force stop" from App info still stops everything; that is an Android rule.
- Expenses: new Monthly / Quarterly / Half-yearly / Yearly view with total, entries, average per month, category totals and a month-by-month list. Quarters, half-years and the year follow the financial year (April to March). Pick any month inside the period with the Month box.
- Expenses: new Custom period with From and To dates (any range), alongside Monthly / Quarterly / Half-yearly / Yearly.
- Expenses: the duration is shown as MM/YY (for example Duration 04/26 to 09/26), also in the month-by-month list.

## Changes in v1.7
- Payslip / Salary statement: every expense now appears as its own line under Deductions (category, note and how often it repeats), and Total deductions includes all of them.
- Add expense: new "Repeats" option: One-time, Monthly, Quarterly, Half-yearly, Yearly or Custom (every N months). The date you pick is the first due date; the full amount is deducted in each month it falls due. Optional "Until" month stops the repeat.
- The Expenses tab (Monthly / Quarterly / Half-yearly / Yearly / Custom views) also counts the repeating expenses in each month they fall due.
- Existing expenses stay as One-time and are not changed.
- Payslip: pending days (today and upcoming days with no check-in) are no longer counted as paid. They are paid once marked present. LOP days are not deducted a second time, because they are already outside the payable days.
- Attendance requests: a user can no longer mark attendance directly. In Attendance, pick days (or a range) and a status, add an optional reason, and tap "Send request". Requests are listed under "My attendance requests" and can be cancelled while pending. A pending request does not count in Reports, Salary or the payslip.
- Reports: the per-day mark buttons are replaced by "Request change" until the admin unlocks.
- Optional holidays: "Take" now sends a request. Only the admin can remove a taken holiday.
- Admin > "Attendance requests": review each request, choose the final status from a dropdown, then Approve or Reject. When the admin is unlocked, Attendance, the range option and Reports mark days directly.
- New admin status "Holiday + Present": the day counts as both a holiday and a present day, so it gets double pay and earns 1 comp off. Available on the admin day list and in the approve dropdown. It also works on days that already have a recorded check-in.
- Fix: working on a holiday now really earns comp off (full day 1, half day 0.5); it was always 0 before.

## Changes in v1.8
- Fix: the app no longer crashes or closes by itself ("keeps stopping"). The tracking service was being restarted from the background without location permission, which Android 14+ answers by killing the whole app, and it then restarted itself every few seconds. It now starts only when location is allowed, and from the background only with "Allow all the time".
- Fix: the app no longer freezes on start when location was refused or only "Approximate" was chosen. Before, it asked again and again in an endless loop. It now asks once and shows a "Open settings" message instead.
- Fix: Check in / Check out, Maps "my location" and the "Exact location" box now use the phone's GPS directly, so location works inside the app. Clear messages for: permission off, only approximate location, GPS turned off, no fix.
- Approximate-only location is now accepted by the tracking service (network fixes) instead of blocking it; the app asks you to turn on "Use precise location" for accurate check-in.
- Login: new "Keep me logged in" box (ticked by default). You stay logged in after closing the app, swiping it away, a phone restart or Android stopping the app in the background. Only Logout or a password change logs you out.

## Changes in v1.9 (all features working inside the app)
- Shift alarm now uses a real Android alarm: it rings and vibrates on time with the screen off, the phone locked or the app closed, shows on the lock screen and keeps ringing until you open it. Snooze and Test still work. A pending alarm survives a phone restart.
- Notifications work in the app (the browser notification feature does not exist inside Android apps).
- Delete / Cancel confirmations now show as proper Android dialogs.
- Share report opens the Android share sheet (WhatsApp, mail, ...). Copy uses the phone clipboard.
- Vibration permission added.

## Changes in v2.0 (Google Maps)
- Maps tab > "Google Maps" card: paste a Google Maps API key and the Maps tab switches to real Google Maps (Map / Satellite / Hybrid / Terrain, Street View).
- Search uses Google: live suggestions while typing, full Google results ranked near you, and the nearby chips (petrol pump, ATM, hospital, ...) use Google places. Tap any shop or landmark on the map to pick it.
- Saved places, home, workplace (with its check-in radius), your live position and the route are drawn on the Google map.
- Without a key, or if Google rejects the key or cannot load, the free map is used automatically, as before.
- The key needs "Maps JavaScript API" and "Places API (New)" enabled, billing on, and website restriction https://appassets.androidplatform.net/*

## Changes in v2.1 (live time)
- Check In: the clock shows seconds, and the check-in timer and Today's total change every second.
- Home: today's worked time, the shift bar and a clock with seconds change every second (the rest of the page is no longer rebuilt every second, so buttons respond to taps).
- Shift alarm line counts down in seconds.
- One timer drives every live time, on the second, and refreshes at once when the app is opened or comes back to the front.
- If one part of the page hits a problem (for example saved data from an older version), the clocks and timers keep running; before, such a problem at start-up stopped them at the time the app was opened.

## Changes in v2.2 (Google sync)
- Settings > Google sync: Sign in with Google. Everything Punch keeps (attendance, trips, places, salary, leave, settings, login password) is saved in the user's own Google Drive, in a hidden folder only Punch can see (appDataFolder), a few seconds after each change and when the app goes to the background.
- After a reinstall or on a new phone: sign in with the same Google account and the data comes back (the page reopens once). Records made on this phone before signing in are kept too (merged); for settings, the Google copy wins on the first sign-in, after that the newer side wins.
- Home shows a "Sign in with Google" banner until sync is on.
- One-time setup in Google Cloud Console (listed in the app): Google Drive API on; OAuth consent screen External and published; Android OAuth client for com.punch.attendance with this app's SHA-1 (32:D2:DE:CE:AB:39:F1:52:54:EB:E8:4B:34:BA:A8:BC:1D:20:B0:C8 for the punch.keystore key).
- Not synced: a custom ring tone file (choose it again on a new phone).

## Changes in v2.3 (no false check-outs, festival look everywhere)
- Auto check-out needs firm evidence. Indoors a phone often reports a spot a few hundred metres away for a moment; one such reading used to start the "wait outside" timer, and if no new reading came in, Punch checked out while you were still at work.
  - A reading counts as outside only if even its accuracy circle is beyond the radius + 30 m; any reading that could be inside cancels the count and the wait.
  - The wait starts after 3 outside readings over at least 2 minutes (5 minutes with only Wi-Fi / mobile-network readings; those are ignored while GPS is working), counted from the first one.
  - At the end of the wait Punch checks out only on a fresh outside reading; with none in the last 5 minutes it asks the phone for one first, and stays checked in if it is inside.
- Festival themes reach the whole app: buttons, switches, tabs, the Check In button and links take the festival colour (readable in light and dark). Added Telangana festivals: Navratri & Bathukamma (10–19 Oct 2026), Bonalu (from 16 Jul 2026), Telangana Formation Day (2 Jun), Milad-un-Nabi (24 Aug 2026), Bhogi–Sankranti–Kanuma, Karthika Pournami.
- Google sync: when Google closes the sign-in without finishing (usually because the one-time setup is missing), Punch says so and opens the setup steps instead of "Sign-in was cancelled".

## Changes in v2.4 (missed trips / late check-in)
- Every location reading is now saved by the phone with its real time, even when the app's page is paused or closed. When the app opens (or the phone lets it run again) it replays the missed readings, so the Home to Office trip and the auto check-in get the real times instead of the time the app was opened.
- Extra free location readings: whenever any other app gets a location (Maps, weather), Punch uses it too.
- Settings > "Tracking check": shows in green / red whether Location "Allow all the time", GPS, Battery "No restrictions", Autostart (Xiaomi / Poco / Redmi and similar), notifications and the tracking service are OK, with a Fix button for each, plus the time of the last reading.
- When the phone stopped Punch (no tracking for more than 20 minutes while Auto check-in was on), a red notice at the top shows when, with a button to the Tracking check.

## Changes in v2.5
- Tracking check: Autostart is now read from the phone on Xiaomi / Poco / Redmi (HyperOS, MIUI) and shows green when it is on. On phones that do not share it, a "Done" button marks it as set.

## Changes in v3.4
- Salary: new Conveyance field, and PF can be set as a % of earned Basic (12% by default), rounded to the rupee like a DMart payslip. Amounts now keep paise (e.g. 18,544.40).
- Salary page: new "How it's calculated" card showing every step: days in month, before joining, days worked, week offs, LOP, paid days (PD), pay factor, each earning (full amount x PD / days), PF, professional tax, total deductions, net pay, per-day rate and full-month take-home.
- Payslip: header shows Total Days, Paid Days (PD), Days Worked, Week off / Paid holiday and LOP; net pay in words includes paise; a "How it's calculated" table (can be turned off on the Payslip page). Still one A4 page.
- Salary CSV includes the calculation steps.

## Changes in v3.5
- Employee login: Salary shows only the month picker and the final figures (earnings, deductions, net pay). Salary settings, days, paid days (PD), LOP and the "How it's calculated" card are hidden. Payslip shows no PD/days rows and no calculation table. Salary CSV has final figures only.
- Admin login: everything as in v3.4 (all settings, days, PD and full calculation).

## Changes in v3.6
- Quick switch: a "⇄ Admin" / "⇄ Employee" button in the top bar switches login without logging out. It always asks for that login's user ID and password.
- Hike %: admin sets "Hike %" and "Hike from month" on the Salary page. From that month on, Basic, HRA, Conveyance and Special allowance go up by the %, and PF follows the new Basic. The calculation shows each step; employees see a "Hike x% from <month>" line with the final figures.

## Changes in v3.7
- Worked holiday is paid once: by default it earns a comp off (no extra double-pay day). Turn on "Pay double for a worked holiday (instead of a comp off)" to get double pay instead; then no comp off is earned. Before, both were given, so paid days could go above the days in the month.
- Home dashboard "Net pay" now always shows the current month (it used the month picked on the Salary page) and counts Conveyance.
