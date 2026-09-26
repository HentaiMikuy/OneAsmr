#!/usr/bin/env python3
"""Convert the CURRENT launcher-icon vector drawables to one SVG, purely so the
review sheet can show a like-for-like "before" thumbnail next to the drafts.

Android <vector> path data is SVG-compatible; only the attribute names and the
aapt gradient blocks need rewriting. The conversion is mechanical and lossy in
no way that matters for a thumbnail (same geometry, same colours, gradients
re-expressed as userSpaceOnUse).

Usage: python3 design/icon/legacy-icon.py   -> design/icon/legacy-512.png source
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res/drawable"
OUT = Path(__file__).parent / "legacy-current.svg"

ATTR = {
    "android:pathData": "d",
    "android:fillColor": "fill",
    "android:strokeColor": "stroke",
    "android:strokeWidth": "stroke-width",
    "android:strokeLineCap": "stroke-linecap",
    "android:strokeLineJoin": "stroke-linejoin",
    "android:fillAlpha": "fill-opacity",
    "android:strokeAlpha": "stroke-opacity",
    "android:fillType": "fill-rule",
}


def gradient_to_svg(block: str, gid: str) -> str:
    """<gradient android:type=...>...</gradient>  ->  SVG <linearGradient>/<radialGradient>."""
    kind = "radial" if 'android:type="radial"' in block else "linear"
    stops = "".join(
        f'<stop offset="{m.group(1)}" stop-color="{m.group(2)}"/>'
        for m in re.finditer(r'<item android:offset="([^"]+)" android:color="([^"]+)"', block)
    )
    if kind == "linear":
        geom = (
            f'x1="{re.search(r"startX=.([^\"]+)", block).group(1)}" '
            f'y1="{re.search(r"startY=.([^\"]+)", block).group(1)}" '
            f'x2="{re.search(r"endX=.([^\"]+)", block).group(1)}" '
            f'y2="{re.search(r"endY=.([^\"]+)", block).group(1)}"'
        )
    else:
        geom = (
            f'cx="{re.search(r"centerX=.([^\"]+)", block).group(1)}" '
            f'cy="{re.search(r"centerY=.([^\"]+)", block).group(1)}" '
            f'r="{re.search(r"gradientRadius=.([^\"]+)", block).group(1)}"'
        )
    return f'<{kind}Gradient id="{gid}" gradientUnits="userSpaceOnUse" {geom}>{stops}</{kind}Gradient>'


def convert(source: Path, defs: list, counter: list) -> str:
    text = source.read_text(encoding="utf-8")
    body = text[text.index("<vector") :]
    out = []
    for chunk in re.split(r"(?=<path)", body):
        if not chunk.lstrip().startswith("<path"):
            continue
        style = []
        # aapt gradient -> defs entry + a fill/stroke reference on this element
        for tag in re.findall(r"<aapt:attr[^>]*>.*?</aapt:attr>", chunk, re.S):
            counter[0] += 1
            gid = f"legacy-g{counter[0]}"
            defs.append(gradient_to_svg(tag, gid))
            name = re.search(r'name="android:(strokeColor|fillColor)"', tag).group(1)
            style.append(f'{"stroke" if name == "strokeColor" else "fill"}="url(#{gid})"')
            chunk = chunk.replace(tag, "")
        # element end: <path ... /> or <path ...>...</path>
        self_close = chunk.find("/>")
        paired = chunk.find("</path>")
        if paired != -1 and (self_close == -1 or paired < self_close):
            chunk = chunk[: paired + len("</path>")]
        else:
            chunk = chunk[: self_close + 2]
        for android_name, svg_name in ATTR.items():
            m = re.search(rf'{android_name}="([^"]*)"', chunk)
            if m:
                value = m.group(1)
                if svg_name == "fill-rule":
                    value = "evenodd" if value.lower() == "evenodd" else "nonzero"
                if value.startswith("url(#") and not value.endswith(")"):
                    value += ")"
                style.append(f'{svg_name}="{value}"')
        # Android's default fill is transparent; SVG's default fill is BLACK.
        # Stroke-only paths (headband, eyes, rings...) must be told so.
        if any(s.startswith("stroke=") for s in style) and not any(
            s.startswith("fill=") for s in style
        ):
            style.insert(0, 'fill="none"')
        out.append("<path " + " ".join(style) + "/>")
    return "\n  ".join(out)


def main() -> None:
    defs: list = []
    counter = [0]
    bg = convert(RES / "ic_launcher_background.xml", defs, counter)
    fg = convert(RES / "ic_launcher_foreground.xml", defs, counter)
    svg = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        "<!-- 现状（旧图标 vol.3）机械转换而来，仅供评审对比 -->\n"
        '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">\n'
        "  <defs>\n    " + "\n    ".join(defs) + "\n  </defs>\n"
        f"  <g id=\"background-layer\">\n  {bg}\n  </g>\n"
        f"  <g id=\"glyph-layer\">\n  {fg}\n  </g>\n"
        "</svg>\n"
    )
    OUT.write_text(svg, encoding="utf-8")
    paths = svg.count("<path")
    print(f"wrote {OUT}  ({paths} paths kept from the original drawables)")


if __name__ == "__main__":
    main()
