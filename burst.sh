#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:?usage: ./burst.sh <BASE_URL> [BEARER_TOKEN]}"
BEARER_TOKEN="${2:-demo-token}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec java "$SCRIPT_DIR/burst/Burst.java" "$BASE_URL" "$BEARER_TOKEN"
