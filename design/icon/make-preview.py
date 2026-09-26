#!/usr/bin/env python3
"""Render design/icon/preview.html -> preview.png — the head-to-head sheet for
the two designs the user asked to keep (I 渐变声波场 and L 霓虹声线).

Each card shows the draft the way a launcher shows it: full size, the three
adaptive-icon mask shapes, the real small sizes, a 4x hard-pixel blow-up of the
48px render, a launcher row among four neutral placeholder icons, and a small
"同款打磨版" strip with the product-grade variants of the same concept.

Usage: python3 design/icon/make-preview.py   (then screenshot preview.html)
"""
from pathlib import Path

HERE = Path(__file__).parent

# 用户明确要求保留的两稿（★），各自附上同款打磨版缩略图
MARKS = [
    ("I", "渐变声波场", "oneasmr-mark-i-wavefield.svg", "★ 你要求保留的第一稿：强色场 + 极简白色声波",
     [("C1 冷", "oneasmr-c1-wavefield-cool.svg"), ("C2 暖", "oneasmr-c2-wavefield-warm.svg")]),
    ("L", "霓虹声线", "oneasmr-style-l-neon.svg", "★ 你要求保留的第二稿：深底 + 青绿→暖金霓虹声波",
     [("C3 深色高级版", "oneasmr-c3-nightwave.svg")]),
]

# 四个中性占位图标（我自己画的抽象图形，用来模拟"旁边还有别的 App"）
PLACEHOLDERS = [
    """<svg viewBox="0 0 108 108" xmlns="http://www.w3.org/2000/svg">
         <rect width="108" height="108" fill="#3A4152"/>
         <circle cx="54" cy="54" r="20" fill="none" stroke="#E8ECF6" stroke-width="7"/></svg>""",
    """<svg viewBox="0 0 108 108" xmlns="http://www.w3.org/2000/svg">
         <rect width="108" height="108" fill="#2C6E63"/>
         <rect x="32" y="32" width="44" height="44" rx="12" fill="#E8F6F2"/></svg>""",
    """<svg viewBox="0 0 108 108" xmlns="http://www.w3.org/2000/svg">
         <rect width="108" height="108" fill="#4A3A63"/>
         <g fill="#F0E9FA">
           <rect x="30" y="36" width="48" height="8" rx="4"/>
           <rect x="30" y="50" width="48" height="8" rx="4"/>
           <rect x="30" y="64" width="30" height="8" rx="4"/></g></svg>""",
    """<svg viewBox="0 0 108 108" xmlns="http://www.w3.org/2000/svg">
         <rect width="108" height="108" fill="#2A3040"/>
         <path d="M30,40 h48 a8,8 0 0 1 8,8 v18 a8,8 0 0 1 -8,8 h-26 l-12,10 v-10 h-10
                  a8,8 0 0 1 -8,-8 v-18 a8,8 0 0 1 8,-8 z" fill="#EAEFF8"/></svg>""",
]


def svg_of(name: str) -> str:
    text = (HERE / name).read_text(encoding="utf-8")
    return text[text.index("<svg"):]


def masked(svg: str, radius: str) -> str:
    return f'<div class="mask" style="border-radius:{radius}">{svg}</div>'


def tile(svg: str, size: int, wallpaper: str) -> str:
    return f'<div class="tile {wallpaper}"><div style="width:{size}px;height:{size}px">{svg}</div></div>'


def launcher_row(svg: str) -> str:
    return (f'<div class="launcher">'
            f'<div class="app">{PLACEHOLDERS[0]}</div>'
            f'<div class="app">{PLACEHOLDERS[1]}</div>'
            f'<div class="app me">{svg}</div>'
            f'<div class="app">{PLACEHOLDERS[2]}</div>'
            f'<div class="app">{PLACEHOLDERS[3]}</div></div>')


def variants_strip(variants: list) -> str:
    if not variants:
        return ""
    items = "".join(
        f'<figure class="variant"><div class="vthumb">{svg_of(f)}</div>'
        f'<figcaption>{label}</figcaption></figure>'
        for label, f in variants
    )
    return f'<figure class="variants"><div class="vrow">{items}</div>' \
           f'<figcaption>同款打磨版（可选，用来对比完成度）</figcaption></figure>'


def card(code: str, title: str, subtitle: str, svg: str, variants: list) -> str:
    return f"""
    <section class="card">
      <header><span class="badge">{code} ★</span><h2>{title}</h2><p>{subtitle}</p></header>
      <div class="hero">{svg}</div>
      <div class="masks">
        <figure>{masked(svg, "50%")}<figcaption>圆形</figcaption></figure>
        <figure>{masked(svg, "32%")}<figcaption>squircle</figcaption></figure>
        <figure>{masked(svg, "18%")}<figcaption>圆角方形</figcaption></figure>
      </div>
      <div class="sizes">
        {tile(svg, 72, "dark")}{tile(svg, 48, "dark")}{tile(svg, 72, "light")}{tile(svg, 48, "light")}
      </div>
      <div class="zoomrow">
        <div class="zoom"><div style="width:48px;height:48px">{svg}</div></div>
      </div>
      <figure class="launcherfig">
        {launcher_row(svg)}
        <figcaption>启动器语境：真实尺寸（56px）与四个中性占位图标并排</figcaption>
      </figure>
      {variants_strip(variants)}
      <figcaption>上：三种遮罩、72/48px 实尺寸、48px 放大 4 倍；中：与别的 App 并排；下：同款打磨版</figcaption>
    </section>"""


def rows(cards: list, per_row: int = 2) -> str:
    out = []
    for i in range(0, len(cards), per_row):
        out.append(f'<div class="cols">{"".join(cards[i:i + per_row])}</div>')
    return "\n".join(out)


def main() -> None:
    cards = [card(c, t, s, svg_of(f), v) for c, t, f, s, v in MARKS]
    legacy = svg_of("legacy-current.svg")
    html = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>OneAsmr 保留稿正面对比（I vs L）</title>
<style>
  :root {{ color-scheme: dark; }}
  * {{ box-sizing: border-box; }}
  body {{ margin:0; padding:32px 28px 44px; background:#15161c; color:#e8e9f2;
         font:14px/1.5 -apple-system,"PingFang SC","Helvetica Neue",Arial,sans-serif; }}
  h1 {{ margin:0 0 6px; font-size:23px; letter-spacing:.3px; }}
  .lede {{ margin:0; color:#9aa0b8; max-width:1040px; }}
  .lede b {{ color:#e8e9f2; }}
  .head {{ display:flex; align-items:flex-start; gap:30px; margin-bottom:24px; }}
  .legacy {{ width:96px; height:96px; flex:0 0 auto; border-radius:24%; overflow:hidden; }}
  .legacy svg {{ width:100%; height:100%; display:block; }}
  .legacy-note {{ color:#8d94ad; font-size:11.5px; margin-top:6px; text-align:center; }}
  .cols {{ display:flex; gap:22px; align-items:stretch; margin-bottom:20px; }}
  .card {{ flex:1; min-width:0; background:#1c1e27; border:1px solid #2b2e3c;
           border-radius:16px; padding:18px 20px 12px; }}
  header {{ margin-bottom:12px; }}
  header h2 {{ display:inline; margin:0 0 0 6px; font-size:19px; }}
  header p {{ margin:6px 0 0; color:#98a0bb; font-size:12.5px; }}
  .badge {{ display:inline-block; padding:0 8px; height:22px; line-height:22px;
            border-radius:6px; background:#E5B36B; color:#2a1d05;
            font-weight:700; font-size:12.5px; }}
  figure {{ margin:0; }}
  figcaption {{ margin-top:6px; color:#7f869d; font-size:11px; }}
  .hero {{ width:240px; margin:4px auto 16px; }}
  .hero svg {{ width:100%; height:auto; display:block; border-radius:24%; }}
  .masks {{ display:flex; gap:12px; margin-bottom:14px; }}
  .masks figure {{ flex:1; text-align:center; }}
  .mask {{ width:100%; aspect-ratio:1; overflow:hidden; }}
  .mask svg {{ width:100%; height:100%; display:block; }}
  .sizes {{ display:flex; gap:10px; align-items:center; justify-content:center; margin-bottom:14px; }}
  .tile {{ display:flex; align-items:center; justify-content:center; padding:8px; border-radius:11px; }}
  .tile.dark {{ background:#101218; }}
  .tile.light {{ background:#e9ebf2; }}
  .tile svg {{ display:block; width:100%; height:100%; border-radius:22%; }}
  .zoomrow {{ display:flex; align-items:center; justify-content:center; margin-bottom:16px; }}
  .zoom {{ width:184px; height:184px; display:flex; align-items:center; justify-content:center; }}
  .zoom div {{ display:flex; align-items:center; justify-content:center; }}
  .zoom svg {{ display:block; width:100%; height:100%; image-rendering:pixelated; }}
  .launcherfig {{ margin-top:4px; }}
  .launcher {{ display:flex; gap:16px; align-items:center; justify-content:center;
               padding:16px 12px; border-radius:14px; background:linear-gradient(160deg,#1b2233,#0d1018); }}
  .app {{ width:62px; height:62px; border-radius:26%; overflow:hidden; flex:0 0 auto;
          box-shadow:0 1px 6px rgba(0,0,0,.45); }}
  .app svg {{ width:100%; height:100%; display:block; }}
  .app.me {{ outline:2px solid #E5B36B; outline-offset:3px; }}
  .variants {{ margin-top:16px; padding-top:14px; border-top:1px solid #2b2e3c; }}
  .vrow {{ display:flex; gap:18px; align-items:flex-start; justify-content:center; }}
  .variant {{ width:96px; text-align:center; }}
  .vthumb {{ width:80px; height:80px; margin:0 auto; border-radius:24%; overflow:hidden; }}
  .vthumb svg {{ width:100%; height:100%; display:block; }}
  .variant figcaption {{ font-size:11px; }}
</style></head>
<body>
  <div class="head">
    <div>
      <h1>你要求保留的两稿：I 与 L</h1>
      <p class="lede">左边 <b>I · 渐变声波场</b>（你说"就还不错，保留一下"），右边 <b>L · 霓虹声线</b>（你说"设计得还可以，也帮我保留一下"）。
      两张卡片用完全相同的条件对比：整体、三种系统遮罩、72/48px 实尺寸、48px 放大 4 倍的硬像素、以及在启动器里与别的 App 并排的样子。
      下方各自的"同款打磨版"是同一概念的产品级完成度版本（I → C1 冷 / C2 暖，L → C3），只作参考，不影响你在这两稿之间做选择。</p>
    </div>
    <div>
      <div class="legacy">{legacy}</div>
      <div class="legacy-note">现状（旧图标）<br>60 条路径</div>
    </div>
  </div>
  {rows(cards, 2)}
</body></html>"""
    (HERE / "preview.html").write_text(html, encoding="utf-8")
    print(f"wrote {HERE / 'preview.html'}")


if __name__ == "__main__":
    main()
