#!/bin/bash
# Synchronize PNG and PNG-backed SVG icons with the master artwork.
# Requires ImageMagick (magick or convert) and GNU base64.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ICON_ROOT="$ROOT/omc-gtk/src/main/resources/icons/hicolor"
ICON_NAME=open-media-converter
MASTER="$ROOT/resources/$ICON_NAME.png"

if command -v magick >/dev/null 2>&1; then
    IMAGE_MAGICK=(magick)
elif command -v convert >/dev/null 2>&1; then
    IMAGE_MAGICK=(convert)
else
    echo "ImageMagick is required to generate application icons." >&2
    exit 1
fi

# Embed the PNG so SVG consumers do not depend on external image paths.
# Fixed-size SVGs embed the matching PNG to retain its optimized sampling.
write_svg() {
    local source="$1" destination="$2" size="$3"
    {
        printf '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="%s" height="%s" viewBox="0 0 %s %s" role="img" aria-labelledby="title description">\n' "$size" "$size" "$size" "$size"
        printf '  <title id="title">Open Media Converter</title>\n'
        printf '  <desc id="description">White conversion arrows surround red video, blue audio, green image and amber document symbols on a dark rounded tile.</desc>\n'
        printf '  <image width="%s" height="%s" xlink:href="data:image/png;base64,' "$size" "$size"
        base64 -w 0 "$source"
        printf '"/>\n</svg>\n'
    } > "$destination"
}

write_svg "$MASTER" "$ROOT/resources/$ICON_NAME.svg" 256
mkdir -p "$ICON_ROOT/scalable/apps"
cp "$ROOT/resources/$ICON_NAME.svg" "$ICON_ROOT/scalable/apps/$ICON_NAME.svg"

for size in 16 24 32 48 64 128 256 512; do
    destination="$ICON_ROOT/${size}x${size}/apps"
    mkdir -p "$destination"
    "${IMAGE_MAGICK[@]}" "$MASTER" \
        -colorspace RGB -filter Lanczos -resize "${size}x${size}" \
        -colorspace sRGB -strip "PNG32:$destination/$ICON_NAME.png"
    if [[ "$size" -le 256 ]]; then
        write_svg "$destination/$ICON_NAME.png" "$destination/$ICON_NAME.svg" "$size"
    fi
done
