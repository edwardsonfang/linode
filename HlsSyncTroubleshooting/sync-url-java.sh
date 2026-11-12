#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
if [[ ! -f target/classpath.txt || ! -d target/classes ]]; then
  echo 'Build first: mvn package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt' >&2
  exit 1
fi
exec "${JAVA:-java}" -cp "target/classes:$(cat target/classpath.txt)" LinodeHlsSync "$@"
