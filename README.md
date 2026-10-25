# TrailGuide GPS

An offline, Gaia-style trail map for Android (Pixel), focused on Oregon. Everything works with no signal: topo map, live GPS, waypoints, track recording, water sources, route planning and an emergency location screen. Units are feet and miles.

This repo has two parts:

| Folder | What it is | Status |
| --- | --- | --- |
| `pipeline/` + `.github/workflows/build-maps.yml` | Builds the offline map files from open data | Phase 1 |
| `app/` | The Android app (Kotlin, Jetpack Compose, MapLibre) | Phase 2 onward |

## Phase 1: building the map files

The map files are built by a GitHub Actions workflow, so nothing needs installing on a PC.

1. Open the repo on GitHub and go to **Actions → Build map files → Run workflow**.
2. Pick a region:
   - `cottonwood-canyon`: the small test area around Cottonwood Canyon State Park and the Pinnacles Trail. Takes about 10 to 15 minutes.
   - `oregon`: the whole state. Takes much longer and produces several GB.
3. When it finishes, the files are attached to a new **Release** (right side of the repo page), named like `maps-cottonwood-canyon-2026-10-25`.

### What the build produces

| File | Contents | Used for |
| --- | --- | --- |
| `topo.pmtiles` | Vector tiles from OpenStreetMap: roads, trails, water, land cover, peaks, place names | The basemap |
| `contours.pmtiles` | Contour lines every 40 ft, with 200 ft index lines marked `major = 1` | Topo contours |
| `terrain.pmtiles` | Elevation tiles (terrain-RGB, PNG) from USGS 3DEP | Hillshade in MapLibre and elevation lookups |
| `water.geojson` | Springs, drinking water, water points, wells | The water sources layer |
| `trails.geojson` | Named trails, paths and tracks | Offline trail search |
| `routing-graph.json.gz` | Trail and road network with segment lengths in miles and climb/descent in feet | Offline route planning |
| `manifest.json` | Region, build date, sources, file sizes and SHA-256 checksums | Version info shown in the app |

### Checking the phase 1 gate

Phase 1 passes when the files open and look right in a desktop viewer:

1. Download `topo.pmtiles` and `contours.pmtiles` from the release.
2. Go to <https://pmtiles.io> and drag each file onto the page.
3. Zoom to Cottonwood Canyon. You should see the John Day River, the Pinnacles Trail along its west bank, and contour lines on the canyon walls.

The build also writes a short check report to the workflow run's summary page: tile counts, number of water sources and trails, and whether a trail named "Pinnacles" was found.

## Data sources and licenses

- **OpenStreetMap** (via Geofabrik): © OpenStreetMap contributors, ODbL. The app must show this attribution.
- **USGS 3DEP elevation**: public domain.
- **Natural Earth and OSM water polygons** (downloaded by Planetiler): public domain / ODbL.

## Seasonal closure note

Parts of the Pinnacles Trail close each year to protect nesting golden eagles, roughly February through August. Closures are not yet in the map data; they are planned as a hand-maintained overlay in phase 4. Check Oregon State Parks for current dates before hiking.
