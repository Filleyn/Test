#!/bin/bash
# Look at candidate online sources for closures and fire restrictions in Oregon.
q() { echo "### $1"; curl -sS -m 40 "$2" | head -c "${3:-2500}"; echo; }
echo "## Forest Service region 6 (Oregon/Washington) services"
curl -sS -m 40 "https://apps.fs.usda.gov/fsgisx02/rest/services/r06?f=json" | python3 -c "import sys,json; d=json.load(sys.stdin); print([s['name']+':'+s['type'] for s in d.get('services',[])])" 2>&1 | head -c 4000; echo
for f in r04 r03 EDW; do
  curl -sS -m 40 "https://apps.fs.usda.gov/fsgisx02/rest/services/$f?f=json" | python3 -c "import sys,json; d=json.load(sys.stdin); print('$f', [s['name'] for s in d.get('services',[]) if any(k in s['name'].lower() for k in ('alert','closure','order','restrict','fire'))])" 2>&1 | head -c 1500; echo
done
curl -sS -m 40 "https://apps.fs.usda.gov/arcx/rest/services?f=json" | python3 -c "import sys,json; d=json.load(sys.stdin); print('arcx folders', d.get('folders'))" 2>&1 | head -c 1500; echo
curl -sS -m 40 "https://apps.fs.usda.gov/arcx/rest/services/EDW?f=json" | python3 -c "import sys,json; d=json.load(sys.stdin); print('arcx EDW', [s['name'] for s in d.get('services',[]) if any(k in s['name'].lower() for k in ('alert','closure','order','restrict','fire','recreation'))])" 2>&1 | head -c 2500; echo
echo "## ODF regulated use"
q "layer" "https://gis.odf.oregon.gov/ags1/rest/services/WebMercator/RegulatedUse/MapServer/0?f=json" 3000
q "sample" "https://gis.odf.oregon.gov/ags1/rest/services/WebMercator/RegulatedUse/MapServer/0/query?where=1%3D1&outFields=*&returnGeometry=false&resultRecordCount=8&f=json" 3500
echo "## WFIGS current perimeters"
q "layer" "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/WFIGS_Interagency_Perimeters_Current/FeatureServer/0?f=json" 1500
q "count OR" "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/WFIGS_Interagency_Perimeters_Current/FeatureServer/0/query?where=attr_POOState%3D%27US-OR%27&returnCountOnly=true&f=json"
q "sample OR" "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/WFIGS_Interagency_Perimeters_Current/FeatureServer/0/query?where=attr_POOState%3D%27US-OR%27&outFields=poly_IncidentName,attr_IncidentName,poly_GISAcres,attr_PercentContained,attr_ModifiedOnDateTime_dt&returnGeometry=false&resultRecordCount=5&f=json" 2500
echo "## Deschutes County fire restrictions"
q "service" "https://maps.deschutes.org/arcgis/rest/services/DeschutesCounty_PublicUseFireRestrictions/MapServer?f=json" 2000
