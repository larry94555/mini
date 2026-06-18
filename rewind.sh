#!/usr/bin/env sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/scripts/common.sh"
echo "Rewinding the most recent file edit..."
api_post /rewind ""
echo
echo "(To see available rewind points: open $IMINI_URL/checkpoints in a browser)"
