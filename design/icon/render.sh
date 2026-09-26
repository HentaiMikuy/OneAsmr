#!/usr/bin/env bash
# Render the icon design drafts to PNG with headless Chrome.
#
#   design/icon/preview.png        评审图（内联 SVG + 遮罩 + 小尺寸 + 48px 硬像素）
#   design/icon/<code>-512.png     每个候选单独 512px（a/b/c/…/style-x）
#
# Chrome runs with a throwaway /tmp profile and crash reporting off (a stale
# profile or the sandbox's denial of ~/Library can otherwise make --headless
# hang), and every shot is bounded by a wait loop so a stuck render can never
# block the shell. Keep the helper self-contained: the loop lives inside shot()
# and the call list lives outside it.
set -u
cd "$(dirname "$0")/../.."
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
PROFILE=/tmp/oneasmr-chrome-icon
SCALE=${SCALE:-2}
WAIT_SECONDS=${WAIT_SECONDS:-15}

shot() { # url out width height
  local url="$1" out="$2" w="$3" h="$4" pid
  rm -f "$out"
  "$CHROME" --headless=new --no-sandbox --disable-gpu --hide-scrollbars \
    --disable-breakpad --disable-crash-reporter --no-first-run --no-default-browser-check \
    --user-data-dir="$PROFILE" --force-device-scale-factor="$SCALE" \
    --window-size="$w,$h" --screenshot="$out" "$url" >/dev/null 2>&1 &
  pid=$!
  for _ in $(seq 1 "$WAIT_SECONDS"); do
    sleep 1
    [ -s "$out" ] && break
    kill -0 "$pid" 2>/dev/null || break
  done
  kill "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null
  if [ -s "$out" ]; then echo "  ok   ${out##*/}"; else echo "  FAIL ${out##*/}"; fi
}

python3 design/icon/legacy-icon.py >/dev/null
python3 design/icon/make-preview.py >/dev/null

# Review sheet (inline SVGs, masks, real sizes, 48px hard-pixel zoom).
shot "file://$PWD/design/icon/preview.html" "$PWD/design/icon/preview.png" 1180 1340

# Individual 512 renders: mark drafts...
for m in a-chibi icon-b-headphones c-wave d-listener e-moon g-glow h-vinyl i-wavefield j-play; do
  case "$m" in
    a-chibi) src="$PWD/design/icon/oneasmr-icon-a-chibi.svg"; out="$PWD/design/icon/a-512.png" ;;
    icon-b-headphones) src="$PWD/design/icon/oneasmr-icon-b-headphones.svg"; out="$PWD/design/icon/b-512.png" ;;
    *) src="$PWD/design/icon/oneasmr-mark-$m.svg"; out="$PWD/design/icon/${m%%-*}-512.png" ;;
  esac
  [ -f "$src" ] && shot "file://$src" "$out" 512 512
done

# ...and the style explorations.
for st in k-glass-negative l-neon m-monogram n-aurora p-dotwave q-isometric s-cassette w-liquid r-radial t-spiral u-interference x-spectrogram; do
  src="$PWD/design/icon/oneasmr-style-$st.svg"
  out="$PWD/design/icon/style-${st%%-*}-512.png"
  [ -f "$src" ] && shot "file://$src" "$out" 512 512
done

for c in c1-wavefield-cool c2-wavefield-warm c3-nightwave c4-radialwhite; do
  src="$PWD/design/icon/oneasmr-$c.svg"
  out="$PWD/design/icon/${c%%-*}-512.png"
  [ -f "$src" ] && shot "file://$src" "$out" 512 512
done

rm -rf "$PROFILE"
