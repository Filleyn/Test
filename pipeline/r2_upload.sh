#!/usr/bin/env bash
# Copies one state's map tile files (*.pmtiles) to Cloudflare R2 for the online map:
#   r2_upload.sh <region> <dir>
# Files land at trailguide-maps/<region>/<name>.pmtiles, which the map server
# (workflow "Online map server") serves as /<region>/<name>/{z}/{x}/{y}.
# Needs CLOUDFLARE_ACCOUNT_ID, AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY (the R2 key).
set -euo pipefail
region="$1"; dir="$2"
bucket="${R2_BUCKET:-trailguide-maps}"
if [ -z "${CLOUDFLARE_ACCOUNT_ID:-}" ] || [ -z "${AWS_ACCESS_KEY_ID:-}" ] || [ -z "${AWS_SECRET_ACCESS_KEY:-}" ]; then
  echo "::warning::Cloudflare keys aren't set up, so the online map wasn't updated."
  exit 0
fi
export AWS_DEFAULT_REGION=auto
# R2 doesn't accept the newer default upload checksums of the AWS tool.
export AWS_REQUEST_CHECKSUM_CALCULATION=when_required AWS_RESPONSE_CHECKSUM_VALIDATION=when_required
endpoint="https://${CLOUDFLARE_ACCOUNT_ID}.r2.cloudflarestorage.com"
for f in "$dir"/*.pmtiles; do
  [ -e "$f" ] || continue
  echo "Uploading $(basename "$f") ($(du -h "$f" | cut -f1))"
  aws s3 cp "$f" "s3://$bucket/$region/$(basename "$f")" --endpoint-url "$endpoint" --only-show-errors \
    --content-type application/octet-stream
done
echo "::notice::Online map updated for $region"
