#!/usr/bin/env bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "$0")"
if [[ $# -lt 3 ]]; then
  echo 'Usage: bash sync_from_url.sh SOURCE_URL NEW_OUTPUT_DIR UNIQUE_OBJECT_PREFIX [--allow-host HOST ...]' >&2
  exit 1
fi
source_url=$1
output_dir=$2
object_prefix=$3
shift 3
for name in LINODE_ENDPOINT LINODE_REGION LINODE_BUCKET LINODE_ACCESS_KEY LINODE_SECRET_KEY; do
  if [[ -z ${!name:-} ]]; then
    echo "Missing required variable: $name" >&2
    exit 1
  fi
done
"${PYTHON:-python3}" download_hls.py "$source_url" "$output_dir" "$@"
# A failed or incomplete download exits above; only asset files are uploaded.
bash run.sh "$output_dir/assets" "$object_prefix"
