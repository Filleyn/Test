# TrailGuide GPS: features and requirements

Current as of app 1.3 and the October 30, 2026 maps. Ideas not yet built are in [ROADMAP.md](ROADMAP.md).

## Requirements set at the start

| Requirement | How it's met |
| --- | --- |
| Offline backpacking app for a Google Pixel 9a (Android 17), Gaia GPS look and feel | Android app (Kotlin, Jetpack Compose, MapLibre); everything core works in airplane mode |
| All of Oregon | Statewide map files (about 1.4 GB) built from open data |
| Feet and miles everywhere | All distances, elevations and speeds in imperial units |
| Topo map and live GPS | Vector topo, 40 ft contours, hillshade; GPS read directly, no Google services needed |
| Waypoints, track recording, water sources | See below |
| Route planning online and offline, with distance, saved routes | Offline trail routing on the phone, with saved routes |
| Time estimate: 2 mph + 30 min per 1,000 ft of climb + 10 min break per hour, adjustable | Pace settings with those defaults |
| Emergency coordinates screen | SOS button opens it |
| Satellite last, optional | Phase 7 |

## Map

- **Topo map:** OpenStreetMap roads, trails, water, land cover, peaks and place names, drawn offline.
- **Contour lines:** every 40 ft, with 200 ft index lines labeled in feet.
- **Hillshade:** terrain shading from USGS 3DEP elevation data.
- **Trails vs forest roads:** trails are dashed red-brown; dirt and forest roads are solid brown.
- **Base maps:** Topo, Satellite and Hybrid (imagery with roads, streams and names) as picture buttons showing the area you're looking at.
- **Overlays:** grouped in sections that open and close (Terrain, Trails and water, Places, Land and roads, Hazards), each showing how many are on, with a color key under the newer ones; all remembered.
- **Quick sets:** Hiking, Driving to the trailhead, Snow and scrambling, and Minimal switch a whole set of overlays at once.
- **Slope angle shading:** slopes of 25°, 30°, 35°, 45° and 60°+ in yellow to purple, computed from the elevation data (zoom in to see it). For snow travel and scrambling.
- **Forest road access:** Forest Service roads colored by who can drive them (passenger car, high clearance, OHV only or closed), dashed where open only part of the year; tap one for its maintenance level, surface, open dates and forest. Rough and 4WD roads from OpenStreetMap are dotted purple (from the November map build on).
- **Past wildfire burns:** fires since 2000 over 50 acres, shaded darker the more recent; tap one for its name, year and size, with a warning about snags, blowdown and washouts.
- **Live GPS:** blue dot with heading, center-on-me, and a north-up or heading-up compass mode.
- **Hideable bottom panel:** swipe it down (or tap its handle) to see more map; swipe up to bring it back. It comes back by itself when you tap something.
- **Off-the-map notice:** says when you're outside the downloaded area.

## Places and water

- **Water sources:** springs, taps, water points, wells and creek crossings, each with its own icon; seasonal ones paler with a dashed ring.
- **Water notes:** add your own note to a water source ("flowing, Aug 2026").
- **Fire lookouts (61) and historic sites (252)**, with their own layer switches and a History search filter.
- **Places:** about 2,700 trailheads, 1,750 campsites, 4,500 peaks with elevation, plus lakes, waterfalls, shelters, viewpoints, hot springs and towns.
- **Extra trailheads:** includes ones mapped only as "Trailhead" parking lots, campgrounds drawn as areas, and a trailhead wherever a named trail ends at a road (shown from zoom 11).
- **Tap for details:** a short card with the name, type, distance and direction from you and the nearest town, plus Go to, Drive, Copy and Pin buttons; pull it up (or tap More) for coordinates, notes, links, Peaks in view, Weather and water notes.
- **Links:** the official page (555 places), the forest's alerts page, and a web search.
- **Driving directions and Copy coordinates** on every place and waypoint card.

## Search

- **Offline search:** trails, water, peaks, lakes, campsites, trailheads and towns, nearest first, each with its nearest town.
- **Filters:** All, Trails, Trailheads, Campsites, Water, Peaks, Waterfalls and Towns; with nothing typed, a filter lists the nearest of that type.
- **Nearest lists:** with an empty search box, the nearest water, campsites and trailheads.
- **Camps by water:** campsites and shelters with mapped water within 1,000 ft, with the water's name and distance.

## Waypoints and tracks

- **Waypoints:** drop with Pin (your location) or Pin on any place card, or long-press and choose "Waypoint instead"; name, color, note and elevation; edit, delete.
- **Track recording:** keeps recording with the screen off; pause and resume; live miles, climb, moving time and speed; survives the app being closed (recovered on restart).
- **Saved tracks:** show on the map, rename, delete.
- **GPX import and export:** waypoints, tracks and routes to and from other apps and GPS units.

## Route planning

- **Plan by tapping:** a route follows the trails between your points, fully offline (Oregon trail network of about 170,000 mi).
- **Long-press to route:** long-press anywhere to plan a route there, from where you are if you're within about 25 mi, otherwise starting at that spot.
- **Straight-line legs:** for off-trail parts, drawn dashed.
- **Planning tools:** undo, add a saved waypoint as a stop, discard confirmation.
- **Live totals:** miles, feet of climb and descent, estimated time, and the trails used ("Via …").
- **Elevation graph:** press or drag on it to mark that spot on the map.
- **Difficulty rating:** the Shenandoah National Park scale, plus steepest stretch, high point, surface mix (trail, dirt road, road, off-trail) and the SAC technical rating where trails have one.
- **Saved routes:** name, color (6 choices), rename, export, delete; arrows along the line show which way it runs.
- **Edit a saved route:** Edit (on the map or in Saved) opens it in the planner: tap a point to move or remove it, add points at the end or the start; save the changes or save as a new route. The parts you don't touch stay exactly as they were.
- **Several routes at once:** names on their lines; tap a line to see its details; Show all / Hide all.

## Tools

- **Reverse a route:** saves a copy running the other way for the hike out, with climb, time and difficulty recalculated.
- **Measuring tool:** ruler button; tap points for total and leg distance, bearing, and area.
- **Go to:** compass arrow, distance, true bearing and climb to any place or waypoint.
- **Water along a route:** water by mile with distance off route, seasonal flags and your notes; longest dry stretch, and how it changes if seasonal sources are dry. Includes about 30,000 trail and road creek crossings.
- **Sunrise, sunset and daylight left**, offline, with after-dark warnings when planning, following and in trip plans.
- **Peaks in view:** from any place, waypoint or your location, the named peaks within 60 mi you can see, worked out offline from the terrain with the earth's curve. A full screen with a compass dial that turns as you do (up is where the phone points) and names the peak you're facing, plus a list by direction or distance with "only just visible" flags. Filter by kind (mountains, buttes, hills, rocks and points) or only peaks higher than you.
- **Weather (online):** National Weather Service forecast for a place, or for a route's start, high point and camps, on a full screen with a day strip (icons, highs, lows, chance of rain or snow) and each day's full forecast; temperatures adjusted for how far you'll be above the forecast area; kept for reading offline.

## Following a route

- **Follow:** miles left, climb left, time left and arrival time, with a progress bar; works with the screen off.
- **Off-route alert:** buzzes and notifies at 100, 250, 500 or 1,000 ft off route (or off), says which way the route is, and draws a red dashed line back to it; also alerts when you're back on route and at the end.
- **Keep the screen on** while following (optional).

## Trip planning and safety

- **Emergency screen:** large coordinates in decimal, degrees-minutes-seconds and UTM, plus elevation and accuracy, for reading to 911; shareable as a text. It notes that the Pixel 9a has no satellite SOS.
- **Trip plan PDF:** who's going, phones, start, expected return, call-for-help time, vehicle and trailhead, with the route map, numbers and elevation profile; sent by email or text.
- **Trip folders:** any mix of routes, tracks and waypoints; pick a folder when saving; show a whole trip on the map; export it as GPX.
- **Multi-day trips:** split a route into days. Camps are suggested so each day takes about the same time at your pace, then moved to a nearby campsite (with water if possible) or water source. A full screen shows the elevation profile split into days with campsites and water marked; drag a camp along it, move it by half a mile, or pick from campsites nearby. Each day shows miles, climb, time and water; camps show on the map as "Night 1", "Night 2"; the trip plan PDF lists each day and camp.

## Public land and alerts

- **Land manager shading:** Forest Service, BLM, Park Service, state, tribal, city and private conservation land, in the usual map colors (USGS PAD-US data).
- **Boundaries:** national forest, refuge and reservation boundaries with names.
- **Wilderness and special areas:** wilderness, wilderness study areas, national monuments and recreation areas.
- **Tap for land info:** who manages it, whether the public can get in, and a link to that agency's alerts.
- **Dispersed camping rules:** on Forest Service and BLM land the land card says what's generally allowed (14-day limits; 100 ft from water on national forests, about 200 ft on BLM) and to check for closures.
- **Active wildfires (online):** perimeters and fire locations with size and containment, kept for offline use with an "as of" time, plus a link to InciWeb closures.

## Satellite

- **Satellite layer (online):** USGS imagery anywhere; Oregon's 1-ft 2024 imagery when zoomed in.
- **Saved satellite areas:** frame an area and choose Good (6 ft, USGS), Sharp (3 ft) or Sharpest (1.4 ft, Oregon 2024); see the size first; downloads in the background.
- **Managing areas:** rename, change the boundary (only new parts download), pause or resume, delete.

## Settings, battery and data

- **Settings screen:** its own gear button.
- **Hiking pace:** adjustable, with defaults of 2 mph, 30 min per 1,000 ft and 10 min breaks per hour.
- **Pace from your hikes:** after 3 recorded hikes of a mile or more, suggests a pace from how you actually hike.
- **Battery saver:** GPS every 15 seconds instead of 5.
- **Low battery warnings:** at 20% and 10%.
- **Automatic battery saver:** switches on at 20%.
- **Monthly map refresh:** the Oregon maps rebuild automatically on the 2nd of each month.
- **Map updates:** checked when the app opens (can be turned off); only changed files download, and downloads resume.
- **Backup and restore:** one file to the phone, Google Drive, OneDrive or email; restoring merges and never deletes.

## Behind the scenes

- **Map build pipeline (GitHub Actions):** builds all the Oregon map files from OpenStreetMap, USGS 3DEP, PAD-US, the Forest Service motor vehicle use maps and national fire history, then publishes them with a check report.
- **App builds:** every change builds a signed app automatically and publishes it on GitHub.
- **No account, no tracking:** nothing about you leaves the phone except map, imagery and fire-data downloads.
