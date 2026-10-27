#!/bin/bash
python3 - <<'PY'
import math, subprocess, time
R = "https://imagery.oregonexplorer.info/arcgis/rest/services/OSIP_2024/OSIP_2024_WM/ImageServer/exportImage"
E = 20037508.342789244
def tile(lat, lon, z):
    n = 2 ** z
    return int((lon + 180) / 360 * n), int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
def bbox(z, x, y):
    size = 2 * E / 2 ** z
    return (-E + x * size, E - (y + 1) * size, -E + (x + 1) * size, E - y * size)
for name, (lat, lon) in {"Bend": (44.058, -121.315), "Three Sisters": (44.10, -121.77), "Wallowas": (45.20, -117.30), "Coast": (44.30, -124.10)}.items():
    for z in (12, 15, 16, 17, 18, 19):
        x, y = tile(lat, lon, z)
        b = ",".join("%.3f" % v for v in bbox(z, x, y))
        url = f"{R}?bbox={b}&bboxSR=3857&imageSR=3857&size=256,256&format=jpgpng&compression=75&f=image"
        url = url.replace("jpgpng", "jpg")
        t = time.time()
        r = subprocess.run(["curl", "-s", "-m", "60", "-o", f"/tmp/{name[:4]}{z}.jpg", "-w", "%{http_code} %{size_download} %{content_type}", url], capture_output=True, text=True).stdout
        print(name, z, r, "%.2fs" % (time.time() - t))
PY
