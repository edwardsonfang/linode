#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
java_cmd="${JAVA:-java}"
if [[ ! -f target/classpath.txt || ! -d target/classes ]]; then
  echo 'Build first: mvn package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt' >&2
  exit 1
fi
exec "$java_cmd" -cp "target/classes:$(cat target/classpath.txt)" repro.HlsSync "$@"
