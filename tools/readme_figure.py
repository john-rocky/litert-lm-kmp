#!/usr/bin/env python3
"""Draw the figure at the top of README.md from the first table of "Numbers" in docs/tested-devices.md.

    python3 tools/readme_figure.py

Writes docs/images/summary-time-light.svg and docs/images/summary-time-dark.svg, and prints the sentence the figure's alt text uses.
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = ROOT / "docs" / "tested-devices.md"
OUT = ROOT / "docs" / "images"
FONT = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif"
THEMES = {
    "light": {"text": "#0b0b0b", "muted": "#52514e", "LiteRT-LM": "#2a78d6", "Gemini Nano": "#eb6834"},
    "dark": {"text": "#f0efec", "muted": "#c3c2b7", "LiteRT-LM": "#3987e5", "Gemini Nano": "#d95926"},
}
HEADER = "| Device | Provider, backend | Seconds |"
TITLE = "The same summary call on each device"
NOTE = "Seconds for a three-bullet summary of the Gettysburg Address, with the model loaded, 2026-11-05."
WIDTH, LABELS, ROW, BAR, VALUE_ROOM = 860, 280, 28, 16, 66


def read():
    """Return [(device, provider, backend, seconds)], read from the record."""
    if not SOURCE.is_file():
        sys.exit(f"readme_figure: {SOURCE} not found")
    lines = SOURCE.read_text(encoding="utf-8").splitlines()
    if HEADER not in lines:
        sys.exit(f"readme_figure: no table {HEADER!r} in {SOURCE}")
    rows = []
    for line in lines[lines.index(HEADER) + 2:]:
        if not line.startswith("|"):
            break
        device, what, seconds = [c.strip() for c in line.strip("|").split("|")]
        if device.startswith("Mac"):
            continue  # phones only: the Mac row stays in docs/tested-devices.md
        provider, _, backend = what.partition(", ")
        rows.append((device, provider, backend, float(seconds)))
    if not rows:
        sys.exit(f"readme_figure: the table in {SOURCE} has no rows")
    return rows


def text(x, y, body, size, fill, weight="400"):
    return f'<text x="{x:g}" y="{y:g}" font-size="{size}" font-weight="{weight}" fill="{fill}">{body}</text>'


def draw(rows, theme, desc):
    c = THEMES[theme]
    unit = (WIDTH - LABELS - VALUE_ROOM) / max(r[3] for r in rows)
    parts = [text(0, 28, TITLE, 20, c["text"], "600"), text(0, 52, NOTE, 14, c["muted"])]
    x = 0
    for key, name, step in (("LiteRT-LM", "Gemma 4 E2B on LiteRT-LM", 230), ("Gemini Nano", "Gemini Nano", 130)):
        parts.append(f'<rect x="{x}" y="66" width="14" height="14" rx="4" fill="{c[key]}"/>')
        parts.append(text(x + 20, 78, name, 14, c["muted"]))
        x += step
    y = 110
    for device, provider, backend, seconds in rows:
        label = f"{device}, {backend}" if backend else f"{device}, Gemini Nano"
        parts.append(text(0, y + 13, label, 14, c["text"]))
        w = seconds * unit
        parts.append(f'<rect x="{LABELS}" y="{y}" width="{w:g}" height="{BAR}" rx="4" fill="{c[provider]}"/>')
        parts.append(text(LABELS + w + 8, y + 13, f"{seconds:.1f} s", 14, c["text"], "600"))
        y += ROW
    height = y + 4
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {WIDTH} {height:g}" width="{WIDTH}" height="{height:g}" '
        f'role="img" aria-labelledby="t d" font-family="{FONT}">\n'
        f'<title id="t">{TITLE}</title>\n<desc id="d">{desc}</desc>\n' + "\n".join(parts) + "\n</svg>\n"
    )


def main():
    rows = read()
    each = " ".join(f"{d}, {p}{', ' + b if b else ''}: {s:.1f} s." for d, p, b, s in rows)
    desc = f"{TITLE}. {NOTE} {each}"
    OUT.mkdir(parents=True, exist_ok=True)
    for theme in THEMES:
        (OUT / f"summary-time-{theme}.svg").write_text(draw(rows, theme, desc), encoding="utf-8")
    print(desc)


if __name__ == "__main__":
    main()
