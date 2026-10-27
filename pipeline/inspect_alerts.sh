#!/bin/bash
R=https://imagery.oregonexplorer.info/arcgis/rest/services
echo "## OSIP_2024 folder"; curl -sS -m 30 "$R/OSIP_2024?f=json" | head -c 1500; echo
for s in OSIP_2024_WM; do
  for t in ImageServer MapServer; do
    echo "## $s $t"
    curl -sS -m 30 "$R/OSIP_2024/$s/$t?f=json" | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception as e: print('no json', e); sys.exit()
if 'error' in d: print(d['error']); sys.exit()
print('caps', d.get('capabilities'), 'singleFusedMapCache', d.get('singleFusedMapCache'), 'cacheType', d.get('cacheType'))
print('copyright', d.get('copyrightText')); print('desc', (d.get('description') or d.get('serviceDescription') or '')[:700])
ti=d.get('tileInfo') or {}; print('lods', [l['level'] for l in ti.get('lods',[])], 'origin', ti.get('origin'), 'wkid', (ti.get('spatialReference') or {}).get('latestWkid'), 'rows', ti.get('rows'))
print('pixel', d.get('pixelSizeX'), d.get('minLOD'), d.get('maxLOD'))
"
  done
done
python3 - <<'PY'
import math, subprocess
def tile(lat, lon, z):
    n = 2 ** z
    return int((lon + 180) / 360 * n), int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
R = "https://imagery.oregonexplorer.info/arcgis/rest/services/OSIP_2024/OSIP_2024_WM/ImageServer/tile"
for name, (lat, lon) in {"Bend": (44.058, -121.315), "Three Sisters": (44.10, -121.77), "Wallowas": (45.20, -117.30)}.items():
    for z in (14, 16, 17, 18, 19, 20):
        x, y = tile(lat, lon, z)
        r = subprocess.run(["curl", "-s", "-m", "30", "-o", "/dev/null", "-w", "%{http_code} %{size_download} %{content_type}", f"{R}/{z}/{y}/{x}"], capture_output=True, text=True).stdout
        print(name, z, r)
PY
