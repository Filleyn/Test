# Offer Scorer for Android

Shows a green, yellow or red score bubble over Uber ride and Uber Eats offers, based on
net pay per hour after your vehicle costs. It also keeps an offer log and a GPS mileage
log for taxes.

- **Read-only.** The reader can't tap, swipe or type. You accept or decline yourself.
- **Private.** Everything is stored on the phone. The app goes online only if you add a rules-file link and tap **Update now**.
- **No extra libraries.** It uses only what's built into Android, so there's very little that can break when building.

Requires Android 10 or newer.

## 1. Install

**From GitHub (no PC needed).** Every push to `offerscorer/` builds a new APK and publishes it as a release named `offerscorer-v<version>.<build number>`.

1. On the phone, open the newest `offerscorer-v…` release on GitHub and tap the `.apk` file.
2. Allow your browser to install apps when Android asks (one time).
3. New versions install over the old one and keep your data.

If you first installed the app from Android Studio, uninstall that copy once before installing from GitHub: the two are signed with different keys. Export your offers and mileage CSVs first, because uninstalling deletes the app's data.

**From Android Studio.** Choose **File > Open**, select this `offerscorer` folder, let Gradle sync, plug in the phone with USB debugging on, and click **Run ▶**. If Studio offers to upgrade the Android Gradle Plugin, decline.

## 2. Turn on the reader

1. In the app's **Status** tab, tap **Turn on the reader**. Android's Accessibility settings open.
2. Choose **Offer Scorer reader** and turn it on. Android warns that the app can see your screen. That's how it reads offer cards.
3. If the switch is grayed out with a "restricted setting" message, go back to Offer Scorer and tap **Open App info**. Tap the **⋮** menu, choose **Allow restricted settings**, then try again.
4. Back in the app, tap **Show a test bubble**. Drag the bubble to where it won't cover anything you need. Tap it to hide it.

## 3. First shift: check the reader

Uber's exact on-screen wording hasn't been tested against real offers yet, so check it on your first shift:

1. In **Settings**, tap **Record Uber screen text for 15 minutes**.
2. Go online in Uber and take a few offers as usual.
3. If a bubble shows the right numbers, the reader works.
4. If you see a gray **COULDN'T READ** bubble, or no bubble at all, go to **Offers**, tap **Export** under "Screens the reader couldn't read", and send the file to Claude to get updated rules. The file contains only the text from Uber's screens, no images.

You can also paste text into **Test the reader** on the Status tab to see how the reader handles it.

## 4. Mileage

- Tap **Start shift** when you go online and **End shift** when you go offline. You can also end the shift from the notification.
- GPS readings are filtered for parked jitter, inaccurate fixes and impossible jumps.
- Progress saves every 30 seconds. If Android stops the tracker, the shift shows as paused with its miles kept. Tap **Resume shift** to continue.
- In the **Mileage** tab, you can add miles by hand (miles, or start and end odometer readings), see totals by month, and export a year's CSV. The CSV has the date, times, miles, purpose, odometer readings, IRS rate and estimated deduction.
- Built-in IRS rates are $0.70 for 2025, $0.725 for Jan–Jun 2026 and $0.76 for Jul–Dec 2026. For other dates, set a rate in Settings. Confirm with your tax preparer.

## 5. How offers are scored

```
cost per mile = gas price ÷ MPG + maintenance + depreciation
ride:     miles = pickup + trip,   minutes = pickup + trip
delivery: miles = total,           minutes = total + restaurant wait per pickup + extra-stop time
net       = pay − miles × cost per mile
net/hr    = net ÷ minutes × 60
```

The bubble's color depends on your target:

- **Green:** net/hr meets the target.
- **Yellow:** net/hr is within the yellow zone below the target.
- **Red:** net/hr is below that, or the pickup is longer than your max.

A ride and a delivery can have different targets.

Whether you accepted an offer is detected from the screen after the card closes, based on words like "Picking up" and "Arriving". It's an estimate, so the log labels it as detected.

## 6. Updating the reading rules without rebuilding

The reader's patterns are stored in `app/src/main/assets/rules.json`. To change them without rebuilding:

1. Put an edited copy online at an https link, such as a file in a GitHub repo opened with **Raw**.
2. Paste the link into **Settings > Reading rules**.
3. Tap **Update now**.

The app checks the file before using it. A bad file is rejected, and the current rules stay in place.

| Field | What it does |
|---|---|
| `packages` | Apps to read. Uber rides and Uber Eats use `com.ubercab.driver`. |
| `pickupWords`, `tripWords`, `totalWords` | The word after "7 min (3.1 mi)" that marks pickup, trip or delivery total |
| `deliveryWords` | Any of these on the card marks the offer as a delivery |
| `offerHintWords` | A button with one of these labels, plus a dollar amount, means "this looks like an offer", even when it can't be fully read |
| `acceptedWords` | Seen after the card closes means you accepted |
| `pickupCountRegex`, `dropoffCountRegex` | Count stops on stacked deliveries (group 1 = number) |
| `fareRegex` | Optional. Group 1 = the amount |
| `segmentRegex` | Optional. Groups: 1 hours, 2 minutes, 3 distance, 4 unit. `{words}` is replaced by the suffix words. |
| `maxFare` | Pay above this is treated as a misread |

Increase `version` whenever you change the file, so Settings shows which rules are loaded.

## Project layout

```
app/src/main/java/com/offerscorer/
  core/       Scoring, offer text parser, GPS distance filter, IRS rates (plain Kotlin, unit-tested)
  data/       Settings, SQLite storage, rules loader, saved screen text
  service/    OfferReaderService (reads screen text), Bubble (overlay), MileageService (GPS)
  ui/         The four tabs
app/src/test/ Parser and scoring tests: right-click > Run 'ParserTest'
```
