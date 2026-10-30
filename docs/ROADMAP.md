# TrailGuide GPS roadmap

All seven planned phases are done (as of 0.9.25, October 2026): offline topo map, statewide Oregon maps, waypoints and tracks, search and places, route planning, field-test features (settings, route following, off-route alerts, battery, pace learning, wildfires), and satellite imagery.

This file keeps ideas that have been discussed but not built, with what's known about each, so they can be picked up later.

## Ideas, not started

### Target shooting areas
Asked about 2026-10-28; documented only, not built.

- **Shooting ranges (easy).** OpenStreetMap tags public and club ranges (`leisure=sports_centre` + `sport=shooting`, `shooting=*`). Add them to `pois.geojson` in `pipeline/build_osm_layers.py` (include ways/areas, like campgrounds), a `range` kind with its own icon, a layer switch and search.
- **Where dispersed shooting is generally allowed (medium).** National forest and BLM land allow it by default, with limits (36 CFR 261.10(d) and 261.52(b); see the [Ochoco NF summary](https://www.fs.usda.gov/r06/ochoco/recreation/target-shooting)):
  - not within 150 yards of a residence, building, campsite, developed recreation site or occupied area;
  - not across a Forest Service road or body of water, or in any way that endangers people or property;
  - no exploding targets (Tannerite), tracer or incendiary ammunition.
  
  Possible build: a shooting overlay that shades federal land (from the PAD-US land manager layer) and cuts out 150-yard buffers around campgrounds, trailheads, buildings and developed sites from OpenStreetMap. Show it as "generally allowed: check local rules", never as permission. OpenStreetMap misses some dispersed campsites and cabins.
- **Closures and fire-season bans (hard).** Oregon's Forest Service region publishes no closure data (checked October 2026; only regions 3 and 4 do). Shooting closures and fire precaution levels are PDFs and web notices. The app already links each forest's alerts page when you tap its land; reuse that.
- State, county and private land have their own rules; the overlay should cover federal land only.

### Smaller ideas
- **Monthly map rebuild** on a schedule (`schedule:` cron in `build-maps.yml`), so trails and public land stay current. The app already offers only changed files.
- **Multi-night trips:** route legs grouped into days, camps marked, per-day miles and climb, and a "campsites near water" filter.
- **Sunrise, sunset and daylight left**, computed offline, with a warning if a route's finish time is after dark.
- **Weather for a route** (online), at the route's location and elevation.
- **"Go to" a point:** a compass arrow and distance to one waypoint or place, for off-trail travel without planning a route.
- **Photos on waypoints**, kept with the waypoint and included in GPX export.
- **Battery settings prompt** for phones that kill background apps (Samsung, Xiaomi, OnePlus): send people to set the app to "Unrestricted".

### Other map layers
Ideas, roughly most useful first. All are public data; each would follow the satellite pattern (online, with optional saved areas) or be built into the offline maps.

- **Forest Service motor vehicle use maps (MVUM):** which forest roads are open to cars, and in which months. Useful for driving to remote trailheads. A national Forest Service map service; verify before building.
- **Slope angle shading:** colors slopes 30–45° and steeper, computed offline from the elevation data already in the maps. Standard for snow and scrambling.
- **Past wildfire burn areas:** recent burn scars mean standing dead trees, blowdown and washed-out trail. National fire history perimeters are public.
- **Classic USGS topo (scanned quads):** the traditional USGS map look, as an alternative base map.
- **Snow depth (online):** SNOTEL stations and national snow analysis, for early or late season trips.
- **ODFW hunting units**, for hunting season awareness.

## Done after phase 7
- 0.9.26: **Driving directions** (opens Google Maps or the phone's maps app) and **Copy coordinates** on every place and waypoint card.
- 1.0: **Backup and restore** (one .zip to the phone, Google Drive, OneDrive or any storage app); **trip plan PDF** to email or text; dirt and forest roads drawn differently from trails; place cards link to the official page, the forest's alerts and a web search; route names on their lines with unselected routes faded; separate Layers and Settings buttons with SOS under them; **search filters** by type.

## Publishing on Google Play (discussed 2026-10-28)

Not decided. If it goes ahead, a sensible order: register as a developer → internal testing with family → ask Oregon GEO about the 2024 imagery → move map files to proper hosting → add more states → 12-tester closed test → public listing. Findings:

- **Account:** one-time US$25 developer account with identity verification. Personal accounts created after November 2023 must run a **closed test with at least 12 testers opted in for 14 days in a row** before production ([Google](https://support.google.com/googleplay/android-developer/answer/14151465)).
- **Internal testing track:** up to 100 testers, no review wait, installs and auto-updates through the Play Store. A simple way to share with family without going public.
- **Developer verification for sideloading:** Google is rolling out developer verification for apps installed outside Play. It started with four countries in September 2026 and expands to certified devices globally in 2027. Unregistered apps get a longer "advanced sideloading" flow with a 24-hour wait. A free limited-distribution account (up to 20 devices, no ID or fee) covers personal use ([Android Authority](https://www.androidauthority.com/android-sideloading-changes-timeline-3679204/)). **Before 2027, register the signing key** so GitHub installs keep working normally.
- **Policy work for a public listing:** privacy policy URL; data safety form (the app collects nothing, which is easy to state); foreground service declarations with a demo video for `location` and `dataSync`; target the latest Android API level each year.
- **Data and servers:** OpenStreetMap (ODbL attribution, already shown), USGS 3DEP, imagery and PAD-US (public domain) and NIFC fire data are fine to use publicly. Oregon's 2024 imagery (OSIP) has no stated license and the app asks its server to draw each image. For a public app, ask Oregon GEO first, or limit Sharp/Sharpest to personal builds. GitHub release downloads are fine for a few users; many users would need a CDN or object storage.
- **Name:** check that "TrailGuide GPS" isn't taken on Play or trademarked before listing.
- **Liability:** add a clear "for planning, not for life-safety navigation" notice. The emergency screen already says the Pixel 9a has no satellite SOS.

## More states (discussed 2026-10-28)

Only if the app is published on Google Play; for personal use, Oregon is enough. The map pipeline is already region-driven (`pipeline/regions.json`); the app is not.

**Pipeline (small):**
- Add each state to `regions.json` with its bbox, Geofabrik extract, DEM product and PAD-US state code.
- Each state build runs about an hour on GitHub Actions (free for public repos), and its files go in its own release.
- Watch the 2 GB per-file release limit for big states: Oregon's terrain file is 581 MB, so California fits but Texas is near the limit and Alaska needs splitting.

**App (a phase of its own):**
- A state catalog from the release list (size, date), with multiple states installed side by side, a delete button for each, and only changed files on update. Today `MapStore` assumes one region and deletes others.
- The map style gets one set of sources per installed state. The routing graph, search index and place lookups work per state; routes that cross a state line fall back to a straight line at the border unless graphs are merged.
- Satellite: USGS imagery works nationwide. The sharp Oregon 2024 imagery is Oregon only; other states would need their own sources, or Good detail only.
- Wildfires: the query box follows installed states. Forest alerts links are Oregon-specific (`PublicLand.FOREST_ALERTS`).
