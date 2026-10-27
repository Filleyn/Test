#!/bin/bash
for s in deschutes willamette mthood ochoco umpqua rogue-siskiyou fremont-winema malheur umatilla wallowa-whitman siuslaw colville okawen gifford-pinchot; do
  code=$(curl -s -o /dev/null -w "%{http_code} %{url_effective}" -L -m 30 "https://www.fs.usda.gov/r06/$s/alerts")
  echo "$s $code"
done
for u in "https://www.blm.gov/alerts" "https://www.oregon.gov/odf/fire/pages/restrictions.aspx" "https://inciweb.wildfire.gov/state/oregon" "https://stateparks.oregon.gov/index.cfm?do=v.page&id=5" ; do
  echo "$u $(curl -s -o /dev/null -w '%{http_code}' -L -m 30 "$u")"
done
echo "## WFIGS incident points"
curl -sS -m 40 "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/WFIGS_Incident_Locations_Current/FeatureServer/0/query?where=POOState%3D%27US-OR%27&outFields=IncidentName,IncidentSize,PercentContained,FireDiscoveryDateTime,ModifiedOnDateTime_dt,IncidentTypeCategory&returnGeometry=false&resultRecordCount=5&f=json" | head -c 2500; echo
