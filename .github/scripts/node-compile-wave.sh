#!/usr/bin/env bash
# One Node/V8 compile wave for GitHub Actions.
#
# Soft-timeout: after NODE_WAVE_SOFT_SECONDS (default 45m) `timeout` stops make
# cleanly so completed .o files stay on disk for the next wave. Real compile
# errors (clang 139 / OOM / hard make failures) still fail the step. When
# NODE_COMPILE_REQUIRE_BINARY=1 the binary must exist at the end.
set -euo pipefail

SOFT_SECONDS="${NODE_WAVE_SOFT_SECONDS:-2700}"
WAVE_LABEL="${NODE_COMPILE_WAVE:-wave}"
REQUIRE_BINARY="${NODE_COMPILE_REQUIRE_BINARY:-0}"
BUILD_LOG="${RUNNER_TEMP:-/tmp}/node-wave-${WAVE_LABEL//\//-}.log"
STATE_FILE="${WORK_DIR:?WORK_DIR is required}/state.env"

mkdir -p "$(dirname "$BUILD_LOG")"
: >"$BUILD_LOG"

echo "==== Node compile wave ${WAVE_LABEL} (soft timeout ${SOFT_SECONDS}s) ===="
free -h || true
swapon --show || true

if [[ -f "$STATE_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$STATE_FILE"
fi
if [[ -n "${NODE_BINARY:-}" && -f "$NODE_BINARY" ]]; then
  echo "Node binary already present at $NODE_BINARY; skipping wave ${WAVE_LABEL}."
  exit 0
fi

# timeout(1) returns 124 on soft expiry. --kill-after gives make a chance to
# flush after SIGTERM before SIGKILL; completed objects remain for the next wave.
set +e
timeout --signal=TERM --kill-after=30s "${SOFT_SECONDS}s" \
  bash runtime-engines/scripts/build-all.sh node-compile \
  2>&1 | tee -a "$BUILD_LOG"
# PIPESTATUS[0] is timeout/build-all; [1] is tee (always 0 unless disk full).
BUILD_STATUS=${PIPESTATUS[0]}
set -e

if [[ -f "$STATE_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$STATE_FILE"
fi

if [[ -n "${NODE_BINARY:-}" && -f "$NODE_BINARY" ]]; then
  echo "Wave ${WAVE_LABEL}: Node binary ready ($NODE_BINARY)"
  free -h || true
  exit 0
fi

# Soft timeout / incomplete wave: allow handoff unless this is the last wave.
if [[ "$REQUIRE_BINARY" == "1" ]]; then
  {
    echo "### Node compile wave ${WAVE_LABEL} failed (binary required)"
    echo '```text'
    tail -n 120 "$BUILD_LOG"
    echo '```'
  } >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  echo "::error title=Node compile incomplete::wave ${WAVE_LABEL} ended without producing the Node binary"
  exit 1
fi

# 124 = timeout soft expiry; 130/137/143 = SIGINT/SIGKILL/SIGTERM during handoff.
if [[ "$BUILD_STATUS" -eq 0 || "$BUILD_STATUS" -eq 124 || "$BUILD_STATUS" -eq 130 || "$BUILD_STATUS" -eq 137 || "$BUILD_STATUS" -eq 143 ]]; then
  echo "Wave ${WAVE_LABEL}: incomplete but resumable (status=${BUILD_STATUS}); continuing with next wave."
  free -h || true
  # Progress marker for the step summary so each wave's cost is visible.
  {
    echo "### Node compile wave ${WAVE_LABEL}"
    echo "status: resumable handoff (${BUILD_STATUS})"
    echo '```text'
    free -h || true
    echo '```'
  } >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  exit 0
fi

# Real compile error — surface the interesting lines and fail hard.
while IFS= read -r ERROR_LINE; do
  [[ -n "$ERROR_LINE" ]] || continue
  ERROR_LINE="${ERROR_LINE:0:3000}"
  ERROR_LINE="${ERROR_LINE//'%'/'%25'}"
  ERROR_LINE="${ERROR_LINE//$'\r'/'%0D'}"
  echo "::error file=.github,line=1,title=Android runtime compiler error::$ERROR_LINE"
done < <(
  grep -Eai 'fatal error:|error:|undefined reference|killed|out of memory|no space left|exit code 139|segmentation fault|signal 9|signal 11|ld\.lld:|make(\[[0-9]+\])?: \*\*\*' "$BUILD_LOG" |
    tail -n 20
)
{
  echo "### Node compile wave ${WAVE_LABEL} hard failure"
  echo '```text'
  free -h || true
  swapon --show || true
  tail -n 160 "$BUILD_LOG"
  echo '```'
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
echo "Wave ${WAVE_LABEL}: hard failure (status=${BUILD_STATUS})"
exit "$BUILD_STATUS"
