#!/bin/bash
# USGS National Map imagery: which zooms have imagery over Oregon, and tile sizes.
B="https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer"
curl -sS -m 30 "$B?f=json" | python3 -c "import sys,json; d=json.load(sys.stdin); print('copyright:', d.get('copyrightText')); print('desc:', (d.get('description') or d.get('serviceDescription') or '')[:600]); ti=d.get('tileInfo',{}); print('lods', [l['level'] for l in ti.get('lods',[])][-6:], 'maxLOD?', d.get('maxLOD'), 'maxScale', d.get('maxScale'))"
python3 - <<'PY'
import math, subprocess
def tile(lat, lon, z):
    n = 2 ** z
    x = int((lon + 180) / 360 * n)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    return x, y
spots = {"Bend": (44.058, -121.315), "Three Sisters": (44.10, -121.77), "Cottonwood": (45.48, -120.47), "Wallowas": (45.20, -117.30), "Coast": (44.30, -124.10)}
B = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile"
for name, (lat, lon) in spots.items():
    out = []
    for z in (12, 14, 15, 16, 17, 18):
        sizes = []
        for dx in (0, 1, 2):
            x, y = tile(lat, lon, z)
            r = subprocess.run(["curl", "-s", "-m", "30", "-o", "/tmp/t", "-w", "%{http_code} %{size_download} %{content_type}", f"{B}/{z}/{y}/{x+dx}"], capture_output=True, text=True).stdout
            sizes.append(r)
        out.append(f"z{z}: " + " | ".join(sizes))
    print(name); print("  " + "\n  ".join(out))
PY
