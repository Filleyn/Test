#!/bin/bash
B=https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services
P="geometry=-125.5,41.0,-115.5,47.0&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects&outSR=4326&f=geojson"
echo "## perimeters"
curl -sS -m 60 -G "$B/WFIGS_Interagency_Perimeters_Current/FeatureServer/0/query?$P&maxAllowableOffset=0.0003" \
  --data-urlencode "where=attr_IncidentTypeCategory = 'WF'" \
  --data-urlencode "outFields=poly_IncidentName,poly_GISAcres,attr_PercentContained,attr_ModifiedOnDateTime_dt,attr_FireDiscoveryDateTime" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('error' if 'error' in d else '', d.get('error')); f=d.get('features',[]); print(len(f), 'features'); print(f[0]['properties'] if f else '', f[0]['geometry']['coordinates'][0][0][:2] if f else '')"
echo "## points"
curl -sS -m 60 -G "$B/WFIGS_Incident_Locations_Current/FeatureServer/0/query?$P" \
  --data-urlencode "where=IncidentTypeCategory = 'WF'" \
  --data-urlencode "outFields=IncidentName,IncidentSize,PercentContained,FireDiscoveryDateTime,ModifiedOnDateTime_dt" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('error')); f=d.get('features',[]); print(len(f), 'features'); [print(x['properties'], x['geometry']['coordinates']) for x in f[:4]]"
