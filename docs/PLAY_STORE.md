# Google Play: form answers for TrailGuide GPS

Drafted November 11, 2026 from what the app does at version 1.5.104 and Google's help pages on that date. Google changes these forms; where a form shows options that differ from what is written here, pick the closest and keep the meaning.

## Status of the technical requirements

As of version 1.5.105 the app targets Android 16 (API 36), which Google Play has required for new apps since August 31, 2026. Each release also includes a signed app bundle (the `.aab` file, "for Google Play"), and the native libraries are laid out for 16 KB memory pages, which Play also requires. Version 1.5.105 needs a check on a phone before it is submitted.

## Store listing

- **App name:** TrailGuide GPS (check it is not taken or trademarked first)
- **Short description (80 characters max):** Offline topo maps, route planning and track recording for hiking in the U.S.
- **Category:** Maps & Navigation
- **Contact email:** yours; it is shown publicly on the listing
- **Privacy policy URL:** https://github.com/Filleyn/Test/blob/main/docs/PRIVACY.md

**Full description**

> TrailGuide GPS is a hiking and backpacking map that works with no signal. Download a state once and you have its topo map, trails, public land, water sources and trailheads on your phone.
>
> PLAN
> • Tap out a route along trails and see miles, climb, time at your pace and a difficulty rating
> • Split a trip into days with camps, rest days and out-and-back routes
> • Water sources by mile, with how much to carry each day
> • A packing checklist suggested from the trip's nights, heat and water
> • A printable trip plan to leave with someone at home
>
> ON THE TRAIL
> • Record your track with the screen off
> • Follow a route with progress, time to camp, daylight left and off-route alerts
> • Ways out: the nearest trailheads and road, measured along trails
> • Elevation profile, peaks, weather forecasts saved for offline reading
>
> YOURS
> • No account, no ads, no tracking. Your routes and tracks stay on your phone.
> • GPX import and export, and backups you control
>
> Maps cover all 50 states. Map data © OpenStreetMap contributors; elevation, imagery and public land from USGS and other public agencies.
>
> TrailGuide GPS is a planning and navigation aid, not a safety device. Maps and estimates can be wrong or out of date. Carry a paper map and compass and tell someone your plan.

## App content section

| Question | Answer |
|---|---|
| Privacy policy | The URL above |
| Ads | No, the app does not contain ads |
| App access | All functionality is available without special access (no login) |
| Content rating questionnaire | Category: Utility, Productivity, Communication or Other. Answer No to every content question (violence, sexual content, language, drugs, gambling, user-to-user communication, sharing location with other users, purchases). Expected rating: Everyone. |
| Target audience | 18 and over. (Choosing any group under 13 brings in the Families policy, which restricts location use.) |
| News app | No |
| Government app | No |
| Financial features | None |
| Health apps | The app records hikes (distance, climb, time) but has no health or medical features. If the form lists "Activity and fitness", selecting it is the cautious answer; otherwise "My app does not have any health features". This one is a judgment call. |
| Advertising ID | No, the app does not use an advertising ID |

## Data safety form

Google counts data as **collected** when the app sends it off the phone, even to someone else's server and even briefly. Most of what the app handles never leaves the phone, so it is not collected. The one item that does is the location sent for a weather forecast.

**Does your app collect or share any of the required user data types?** Yes

**Is all of the user data collected by your app encrypted in transit?** Yes (every connection the app makes is HTTPS)

**Do you provide a way for users to request that their data is deleted?** The developer holds no user data, so there is nothing to request. If the form requires an answer, choose the option that says data is not retained or, failing that, No, and rely on the privacy policy's explanation.

**Data types**

| Data type | Collected? | Shared? | Details to select |
|---|---|---|---|
| Location › Precise location | Yes | No | Processed ephemerally: Yes. Required or optional: Optional (only when the user asks for a forecast). Purpose: App functionality. |
| Location › Approximate location | No | No | |
| Everything else (personal info, financial info, health and fitness, messages, photos and videos, audio, files and docs, calendar, contacts, app activity, web browsing, app info and performance, device or other IDs) | No | No | |

Why these answers:

- **Precise location, collected:** when the user asks for a forecast, the app sends the coordinates of the place or route points to the U.S. National Weather Service. For "weather here" that is the user's position. The developer never receives it.
- **Not "shared":** Google exempts a transfer made by "a specific user-initiated action, where the user reasonably expects the data to be shared". Asking for a forecast for a spot is that.
- **Photos, files, fitness data, crash logs: not collected.** They stay on the phone. Trip plans, GPX files and backups leave only through Android's share sheet at the user's request, which is user-initiated and goes to the recipient the user picks, not to the developer.
- **Map, wildfire and imagery downloads** send no user data beyond the request itself. The area of imagery being viewed is not the user's location.

If the app later adds analytics, crash reporting to a server, accounts or any AI service, this form and the privacy policy must be updated first.

## Foreground service declarations

The app declares two foreground service types. Each needs a description, the user impact, a use case and a link to a short video (an unlisted YouTube link works).

### Location (`FOREGROUND_SERVICE_LOCATION`)

- **Use case:** Background location updates: navigation
- **Description:** TrailGuide GPS is a hiking navigation app. When the user taps Record to record a track, or Follow to be guided along a route, the app starts a foreground service that keeps receiving GPS positions with the screen off, so the track is recorded and the user is alerted if they leave the route. A notification is shown the whole time; tapping it opens the app, where Stop is one tap. The service starts only from these two buttons and stops as soon as the user stops recording or following.
- **User impact if deferred or interrupted:** Hikers keep the phone in a pocket with the screen off for hours. If the service were deferred or stopped, the recorded track would have gaps or end early, and off-route alerts would not fire, which can leave a hiker on the wrong trail without knowing it.
- **Video (about 30 seconds):** open the app → tap Record → show the notification → lock the screen for a few seconds → unlock and show the track growing → tap Stop and save. Then open a saved route → tap Follow → show the Follow panel and notification → tap Stop.

### Data sync (`FOREGROUND_SERVICE_DATA_SYNC`)

- **Use case:** Network transfer: upload or download
- **Description:** The user chooses a state to download in the Maps screen (or an area of satellite imagery) so the map works offline. A state's map files are several hundred megabytes to a few gigabytes. The app starts a foreground service for the download so it can finish if the user switches apps or the screen turns off, with a notification showing progress. The service starts only when the user taps Download or Update and stops when the download finishes, fails or is cancelled.
- **User impact if deferred or interrupted:** The download would stop whenever the user left the app, and large map files would not finish. Users download maps at home on Wi-Fi shortly before a trip; an unfinished download means no map where there is no signal.
- **Video (about 20 seconds):** Settings → Maps → pick a state → tap Download → show the progress notification → leave the app and come back to show it still running.

## Location permissions

- The app requests precise and approximate location and uses them only while it is open or while the user is recording or following (through the foreground service above). It does **not** request the "allow all the time" background permission (`ACCESS_BACKGROUND_LOCATION`), so the background location declaration should not appear.
- If the console asks why approximate location is not enough: *Hiking navigation needs the user's position to within a few yards to show which trail they are on, record an accurate track, measure distance and climb, and warn when they are more than a set distance off their route. Approximate location (accurate to about a mile) cannot do any of these.*
- Google's newer location policy asks apps to use the smallest scope that works and adds an Android "location button" for one-time requests in apps targeting Android 17. A continuous navigation and recording app needs ongoing precise location, so the button does not replace it, but expect a declaration asking for the justification above.

## Getting to production

1. Developer account (one-time US$25, identity verification).
2. Internal testing track first: up to 100 testers, no review wait.
3. A personal account created after November 2023 must run a closed test with at least 12 testers opted in for 14 days in a row before applying for production.
4. Fill in the forms above, upload screenshots (phone, at least two) and a 512 × 512 icon and 1024 × 500 feature graphic.

## Pricing (decided November 11, 2026)

- An annual subscription through Google Play, starting with a free trial (about 14 days) of the full app. No limited free tier at launch.
- A small permanent free mode (online map, GPS position, one saved route) may be added later if installs suggest people want it. It would need the online map server turned back on.
- No accounts and no server: the app asks Google Play on the phone whether the subscription is active. It must keep working with no signal.
- Build the paywall once the app is in Play's internal testing track, because Play billing can only be tested on an app installed from Play. Builds installed from the GitHub release page stay unlocked for field testing.

## Map hosting and updates (decided November 11, 2026)

- Moving map downloads to Cloudflare waits until after more field testing.
- Map rebuilds: quarterly, spread over several days, keeping the newest copy and the one before it for each state. Not scheduled yet; waiting for a go-ahead.

## Work still needed in the app

| Item | Why | Size |
|---|---|---|
| Target Android 16 (API 36) | Required for any new app since August 31, 2026 | Done in 1.5.105; check on the phone |
| App bundle (.aab) build for Play | Play takes bundles, not APKs, for new apps | Done in 1.5.105 |
| Play App Signing | Google holds the release key for new apps; the current key becomes the upload key | Done in the console when creating the app |
| Oregon 2024 imagery | No stated license; ask Oregon GEO, or leave Sharp/Sharpest out of the public build | A decision, then small |
| Map hosting | GitHub release downloads are fine for a few users, not for many | Larger; waiting until after field testing |
| Subscription and free trial | The pricing above | Medium; after the app is in internal testing |
| Code shrinking | Optional; smaller download | One test version |

## Sources

- [Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
- [Understanding foreground service and full-screen intent requirements](https://support.google.com/googleplay/android-developer/answer/13392821)
- [Target API level requirements](https://developer.android.com/google/play/requirements/target-sdk)
- [Location permissions policy preview](https://support.google.com/googleplay/android-developer/answer/16909972)
