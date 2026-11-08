#!/bin/sh
# Runs an adb server on this computer for the dev container (.devcontainer),
# which cannot see USB devices itself. Nothing is installed: adb is downloaded
# into a temporary directory that is removed when this script stops (Ctrl-C).
# Needs only sh, curl and unzip, which macOS and Linux have.
#
#   ./contrib/host-adb.sh            listen on all interfaces (what Docker Desktop needs)
#   LOCAL_ONLY=1 ./contrib/host-adb.sh   listen on localhost only (try this first)
#
# Anyone who can reach the port can run commands on the authorized phones, so
# use it on a network you trust, and only while testing.
set -e

cd "$(dirname "$0")/.."
VERSION=$(sed -n 's/^ARG PLATFORM_TOOLS=//p' .devcontainer/Dockerfile)
[ -n "$VERSION" ] || { echo "PLATFORM_TOOLS not found in .devcontainer/Dockerfile" >&2; exit 1; }

case "$(uname -s)" in
  Darwin) OS=darwin ;;
  Linux) OS=linux ;;
  *) echo "Unsupported system: $(uname -s)" >&2; exit 1 ;;
esac

DIR=$(mktemp -d)
PID=
cleanup() {
  trap - EXIT INT TERM
  [ -n "$PID" ] && kill "$PID" 2>/dev/null || true
  rm -rf "$DIR"
  echo "adb stopped and removed."
}
trap cleanup EXIT INT TERM

# curl does not mark the download as quarantined, unlike a browser
curl -fsSL -o "$DIR/pt.zip" "https://dl.google.com/android/repository/platform-tools_r$VERSION-$OS.zip"
unzip -q "$DIR/pt.zip" -d "$DIR"

ADB="$DIR/platform-tools/adb"
"$ADB" kill-server 2>/dev/null || true
echo "adb $VERSION on port 5037. Ctrl-C stops it and removes it."
# In the background, so that a signal to this script is handled at once
if [ -n "$LOCAL_ONLY" ]; then
  "$ADB" -P 5037 nodaemon server &
else
  "$ADB" -a -P 5037 nodaemon server &
fi
PID=$!
wait "$PID"
