#!/usr/bin/env bash
# Copies one state's map tile files (*.pmtiles) to Cloudflare R2 for the online map:
#   r2_upload.sh <region> <dir> [file names...]   (all *.pmtiles in <dir> when none are named)
# Files land at trailguide-maps/<region>/<name>.pmtiles, which the map server
# (workflow "Online map server") serves as /<region>/<name>/{z}/{x}/{y}.
# Needs CLOUDFLARE_ACCOUNT_ID, AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY (the R2 key).
# The elevation file (terrain.pmtiles) is sent at zoom 10 at most: online it's only used
# for hill shading, which overzooms fine, and that keeps Cloudflare storage small.
# Downloads keep the full-detail file from the GitHub release.
set -euo pipefail
region="$1"; dir="$2"; shift 2
bucket="${R2_BUCKET:-trailguide-maps}"
if [ -z "${CLOUDFLARE_ACCOUNT_ID:-}" ] || [ -z "${AWS_ACCESS_KEY_ID:-}" ] || [ -z "${AWS_SECRET_ACCESS_KEY:-}" ]; then
  echo "::warning::Cloudflare keys aren't set up, so the online map wasn't updated."
  exit 0
fi
export AWS_DEFAULT_REGION=auto
# R2 doesn't accept the newer default upload checksums of the AWS tool.
export AWS_REQUEST_CHECKSUM_CALCULATION=when_required AWS_RESPONSE_CHECKSUM_VALIDATION=when_required
endpoint="https://${CLOUDFLARE_ACCOUNT_ID}.r2.cloudflarestorage.com"
if [ $# -gt 0 ]; then files=(); for n in "$@"; do files+=("$dir/$n"); done; else files=("$dir"/*.pmtiles); fi
here="$(cd "$(dirname "$0")" && pwd)"
lite_dir="$(mktemp -d)"
trap 'rm -rf "$lite_dir"' EXIT
for f in "${files[@]}"; do
  [ -e "$f" ] || continue
  if [ "$(basename "$f")" = terrain.pmtiles ]; then
    python3 "$here/pmtiles_maxzoom.py" "$f" "$lite_dir/terrain.pmtiles" --maxzoom "${ONLINE_TERRAIN_MAXZOOM:-10}"
    f="$lite_dir/terrain.pmtiles"
  fi
  echo "Uploading $(basename "$f") ($(du -h "$f" | cut -f1))"
  aws s3 cp "$f" "s3://$bucket/$region/$(basename "$f")" --endpoint-url "$endpoint" --only-show-errors \
    --content-type application/octet-stream
  rm -f "$lite_dir/terrain.pmtiles"
done
echo "::notice::Online map updated for $region"
