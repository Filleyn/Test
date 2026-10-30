#!/bin/bash
j() { python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception as e: print('not json', e); sys.exit()
if 'error' in d: print('ERROR', d['error']); sys.exit()
$1"; }
echo "## MVUM"
curl -sS -m 40 "https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_MVUM_02/MapServer?f=json" | j "print([ (l['id'], l['name']) for l in d.get('layers',[])])"
curl -sS -m 40 "https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_MVUM_02/MapServer/1?f=json" | j "print(d.get('name'), d.get('maxRecordCount')); print([f['name'] for f in d.get('fields',[])])"
curl -sS -m 60 -G "https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_MVUM_02/MapServer/1/query" --data-urlencode "where=FORESTNAME LIKE 'Deschutes%'" --data-urlencode "returnCountOnly=true" --data-urlencode "f=json" | head -c 300; echo
curl -sS -m 60 -G "https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_MVUM_02/MapServer/1/query" --data-urlencode "where=FORESTNAME LIKE 'Deschutes%'" --data-urlencode "outFields=*" --data-urlencode "returnGeometry=false" --data-urlencode "resultRecordCount=3" --data-urlencode "f=json" | j "
for f in d.get('features',[]): print({k:v for k,v in f['attributes'].items() if v not in (None,'',' ')})"
echo "## Fire history"
B=https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services
curl -sS -m 40 "$B?f=json" | j "print([s['name'] for s in d.get('services',[]) if 'istor' in s['name'] or 'Perim' in s['name']])"
for S in InterAgencyFirePerimeterHistory_All_Years_View WFIGS_Interagency_Perimeters; do
  curl -sS -m 40 "$B/$S/FeatureServer/0?f=json" | j "print('$S', d.get('name'), d.get('maxRecordCount')); print([f['name'] for f in d.get('fields',[])][:40])"
done
curl -sS -m 60 -G "$B/InterAgencyFirePerimeterHistory_All_Years_View/FeatureServer/0/query" --data-urlencode "where=FIRE_YEAR_INT >= 2000" \
  --data-urlencode "geometry=-124.7,41.95,-116.45,46.3" --data-urlencode "geometryType=esriGeometryEnvelope" --data-urlencode "inSR=4326" \
  --data-urlencode "returnCountOnly=true" --data-urlencode "f=json" | head -c 300; echo
echo "## ODFW wildlife management units"
for u in "https://nrimp.dfw.state.or.us/arcgis/rest/services?f=json" "https://services.arcgis.com/uUvqNMGPm7axC2dD/ArcGIS/rest/services?f=json"; do
  echo "$u"; curl -sS -m 40 "$u" | j "print([s.get('name') for s in d.get('services',[]) if any(k in s.get('name','').lower() for k in ('wmu','management','hunt','unit'))][:20]); print('folders', d.get('folders'))"
done
echo "## NWS"
curl -sS -m 30 -H "User-Agent: TrailGuideGPS (test)" "https://api.weather.gov/points/44.06,-121.31" | j "p=d['properties']; print(p['forecast'], p['relativeLocation']['properties']['city'])"
