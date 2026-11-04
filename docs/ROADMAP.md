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

### Hunting and fishing areas (backlog, needs discussion)
Looked at for 1.2 and held back:
- **Hunting units:** ODFW publishes hunt boundaries as several 2026 hunt-series layers (100, 400, 500 and 700 series), not one simple wildlife-management-unit map, and the boundaries change by season and tag. Before building: decide which hunts matter (deer and elk general season?), and whether a once-a-year snapshot is good enough.
- **Fishing:** regulations are by water body and zone in the ODFW synopsis (a PDF); there's no clean statewide data layer. A link to the ODFW zone page from lake and river cards may be enough.

### Smaller ideas

### Other map layers
Ideas, roughly most useful first. All are public data; each would follow the satellite pattern (online, with optional saved areas) or be built into the offline maps.

- **Classic USGS topo (scanned quads):** the traditional USGS map look, as an alternative base map.
- **Snow depth (online):** SNOTEL stations and national snow analysis, for early or late season trips.

## Done after phase 7
- 0.9.26: **Driving directions** (opens Google Maps or the phone's maps app) and **Copy coordinates** on every place and waypoint card.
- 1.0: **Backup and restore** (one .zip to the phone, Google Drive, OneDrive or any storage app); **trip plan PDF** to email or text; dirt and forest roads drawn differently from trails; place cards link to the official page, the forest's alerts and a web search; route names on their lines with unselected routes faded; separate Layers and Settings buttons with SOS under them; **search filters** by type.
- 1.0.31: tapping routes, trails and forest boundaries fixed (fully transparent tap layers aren't tappable in MapLibre); backup through the share sheet so OneDrive appears.
- 1.0.32: **trip folders** holding any mix of routes, tracks and waypoints; choose a folder when saving; show a whole folder on the map; export a folder as GPX; folders included in backups.
- 1.5.56: **online map from Cloudflare** (R2 storage + the Protomaps PMTiles Worker at trailguide-tiles.filleyn.workers.dev). Map builds copy their tile files there; the "Online map server" workflow sets up the server and copies existing maps. Other states show online by default again.
- 1.5.54: streaming states you haven't downloaded is **experimental and off by default** (Settings → Maps). Reading map files straight from GitHub releases crashed the map engine once ("incorrect header check"); a guard now rejects bad answers. Real fix before publishing: serve online tiles from a proper tile host (e.g. Cloudflare R2 + the PMTiles Worker, normal z/x/y tile addresses).
- 1.5: **maps for several states** (Oregon, Washington, Idaho, California, Nevada): state picker at setup, per-state download, update and delete, online map for states not downloaded, search by state, state outlines so neighbours don't overlap; **battery prompt** for phones that stop background apps. Routes across state lines came next (1.5.55): the route finds where neighbouring states' trail networks meet and goes through the best crossing. Map builds now keep roads and bridges a hiking route uses (the PCT over the Bridge of the Gods and through Cascade Locks).
- 1.4.48: **trail notes** (with folders, map icons and the route water list), **photos** on waypoints and notes, **tracks to routes** (and editable imported routes), **automatic Google backup** without map files, wording no longer assumes Oregon or a Pixel 9a.
- 1.4: **dark mode** and **sunlight mode** (app and map), **quick tips** on first run, built-in base map pictures, route editing, peak filters.
- 1.3.4x: full screens for **Days and camps** (draggable camps on the profile), **Weather** (day strip with icons) and **Peaks in view** (compass dial); route direction arrows; water icons by type; trail names a zoom earlier; section all-on/off and your own quick sets; pick routes for the map from Saved.
- 1.3: **base map picker** (Topo, Satellite, Hybrid with live previews), overlays grouped with **quick sets**, **icon buttons** for actions, **pull-up place cards**, long-press starts a route, swipe-away bottom panel; automatic screenshots in the app build.
- 1.2: **slope angle shading**, **forest road access** (Forest Service MVUM, plus rough/4WD roads from OpenStreetMap), **past wildfire burns** (since 2000), **dispersed camping rules** on land cards, **peaks in view** with compass, **multi-day trips** with camps, **camps by water** search filter, **weather** (National Weather Service) for places and routes. Hunting and fishing areas moved to the backlog above.
- 1.1: **reverse a route**, **measuring tool**, **Go to** with compass, **water along a route** (with stream crossings in the map build), **sunrise/sunset** and after-dark warnings, **fire lookouts and historic sites**, **monthly automatic map builds** (keeping the last 3); the app now picks the most recently published map release.

## Two repositories (decided 2026-10-31, later)
Stay on this one public repository while testing. Later, move to two new repositories, for example **Tripguide** (public: only the finished app and map files) and **Tripguideprivate** (private: code, build pipeline, secrets). Steps then: create both; move the code; builds publish releases to the public one with a token limited to it (stored as a secret); point the app's `MAPS_REPO` at the public one; copy the current maps across once. Private builds use the free plan's 2,000 Actions minutes a month (an Oregon map build is about 100, an app build about 10). Selling later would move map files to Cloudflare R2 instead.

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
