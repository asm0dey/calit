# Card fonts

Static font instances used by `site.asm0dey.calit.web.og.CardFonts` to render the social-preview
card (Task 3). AWT's `Font.createFont` ignores a variable font's `fvar` axes — it silently loads
whatever the font's default named instance is — so weight/width/optical-size selection has to
happen ahead of time, baked into a static instance, rather than at runtime.

All seven files here were produced with [fontTools](https://github.com/fonttools/fonttools)
`varLib.instancer` (tested with fontTools 4.63.0) from the corresponding Google Fonts variable
source. Each family's `OFL.txt` license is included alongside its instance(s) as
`OFL-<Family>.txt`, fetched from the same directory in the `google/fonts` repo.

Every command below passes `--update-name-table` so the instanced file's `name` table (what
`Font.getFontName()` reads in Java) describes the actual pinned weight, instead of parroting
whatever the source variable font's *default* named instance happened to be. Without it, e.g.
both `Rubik-Regular.ttf` and `Rubik-SemiBold.ttf` would report the identical stale name `"Rubik
Light"` (the source's own default instance), even though their `OS/2.usWeightClass` and glyph
outlines correctly differ — a real trap for anything that reads `getFontName()`, including tests.

## Regenerating

```bash
cd /tmp && mkdir -p calit-fonts && cd calit-fonts
curl -sSL -o Rubik.ttf          "https://raw.githubusercontent.com/google/fonts/main/ofl/rubik/Rubik%5Bwght%5D.ttf"
curl -sSL -o NotoSans.ttf       "https://raw.githubusercontent.com/google/fonts/main/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf"
curl -sSL -o NotoSansHebrew.ttf "https://raw.githubusercontent.com/google/fonts/main/ofl/notosanshebrew/NotoSansHebrew%5Bwdth%2Cwght%5D.ttf"
curl -sSL -o Hanken.ttf         "https://raw.githubusercontent.com/google/fonts/main/ofl/hankengrotesk/HankenGrotesk%5Bwght%5D.ttf"
curl -sSL -o Fraunces.ttf       "https://raw.githubusercontent.com/google/fonts/main/ofl/fraunces/Fraunces%5BSOFT%2CWONK%2Copsz%2Cwght%5D.ttf"

python3 -m fontTools.varLib.instancer --update-name-table Rubik.ttf          wght=400              -o Rubik-Regular.ttf
python3 -m fontTools.varLib.instancer --update-name-table Rubik.ttf          wght=600              -o Rubik-SemiBold.ttf
python3 -m fontTools.varLib.instancer --update-name-table NotoSans.ttf       wght=400 wdth=100     -o NotoSans-Regular.ttf
python3 -m fontTools.varLib.instancer --update-name-table NotoSans.ttf       wght=600 wdth=100     -o NotoSans-SemiBold.ttf
python3 -m fontTools.varLib.instancer --update-name-table NotoSansHebrew.ttf wght=400 wdth=100     -o NotoSansHebrew-Regular.ttf
python3 -m fontTools.varLib.instancer --update-name-table Hanken.ttf         wght=700              -o HankenGrotesk-Bold.ttf
```

Fraunces needs one extra step (see "Why Fraunces is a three-step recipe" below) instead of a
single `--update-name-table` invocation:

```bash
# Step 1: the shape-correct instance at the real pin (opsz=14). --update-name-table CANNOT be
# used directly here: Fraunces' STAT table only registers exact opsz nominals at 9, 72 and 144,
# and 14 doesn't match any of them, so fontTools refuses with
#   ValueError: Cannot find Axis Values {'opsz': 14.0}
python3 -m fontTools.varLib.instancer Fraunces.ttf wght=600 opsz=14 SOFT=0 WONK=0 -o Fraunces-Chip.ttf

# Step 2: a throwaway instance pinned at the nearest STAT-registered opsz nominal (9 — the same
# "9pt" bucket that 14 falls inside, range 9.0-40.5), purely so fontTools can derive the correct
# style-name string via --update-name-table.
python3 -m fontTools.varLib.instancer --update-name-table Fraunces.ttf wght=600 opsz=9 SOFT=0 WONK=0 -o Fraunces-NameProbe.ttf

# Step 3: transplant the derived name table onto the shape-correct instance from Step 1. Only the
# `name` table is copied — glyf/hmtx/OS2/etc. all still come from the real opsz=14 instance.
python3 -c "
from fontTools.ttLib import TTFont
shaped = TTFont('Fraunces-Chip.ttf')
named = TTFont('Fraunces-NameProbe.ttf')
shaped['name'] = named['name']
shaped.save('Fraunces-Chip.ttf')
"
rm -f Fraunces-NameProbe.ttf
```

Then copy everything into the repo:

```bash
mkdir -p ~/work_self/calit/src/main/resources/fonts
cp Rubik-Regular.ttf Rubik-SemiBold.ttf NotoSans-Regular.ttf NotoSans-SemiBold.ttf \
   NotoSansHebrew-Regular.ttf HankenGrotesk-Bold.ttf Fraunces-Chip.ttf \
   ~/work_self/calit/src/main/resources/fonts/
```

Fetch each family's `OFL.txt` from the same `google/fonts` directory as its source `.ttf` and save
it here as `OFL-<Family>.txt`.

Fraunces is deliberately instanced at `opsz=14`, not the `opsz=144` display cut — 14 matches the
~17px the site renders the brand chip's "c" at (`.lp-brand` on the landing page). Using the
display cut here would look wrong at chip size.

### Why Fraunces is a three-step recipe

`--update-name-table` derives the new style name from the font's own STAT table, but it only
accepts a pin that lands exactly on an existing STAT `AxisValue`'s registered nominal — it does
**not** do range-membership matching, even for a STAT axis value declared as a range (Format 2).
Fraunces' `opsz` axis registers exactly three nominals — 9 ("9pt", range 9.0-40.5), 72 ("72pt"),
144 ("144pt") — so pinning `opsz=14` directly with `--update-name-table` fails outright, even
though 14 is unambiguously inside the "9pt" bucket's own declared range. Pinning `opsz=9` instead
(Step 2) hits that nominal exactly and lets fontTools name the result correctly; because the name
string only depends on *which* STAT bucket matches — not the exact numeric pin within it — the
name computed for opsz=9 is the correct name for opsz=14 too. Step 3 copies only that computed
`name` table onto the real opsz=14 shape from Step 1, so the shipped glyph outlines are unaffected
by the naming workaround (verified: the "9pt" opsz=14 and opsz=9 instances have measurably
different glyph bounding boxes / advance widths — the transplant only touches `name`).

## Sanity-checking an instance actually changed

A no-op instancer run is the failure mode to watch for: if two weights of the same family
report byte-identical glyph outlines, the instancing silently did nothing and the card will
render every weight identically. Verify with fontTools:

```bash
python3 -c "
from fontTools.ttLib import TTFont
for path in ['Rubik-Regular.ttf', 'Rubik-SemiBold.ttf']:
    f = TTFont(path)
    assert 'fvar' not in f, f'{path} is still variable!'
    cmap = f.getBestCmap()
    gid = cmap[ord('A')]
    print(path, f['glyf'][gid].xMax - f['glyf'][gid].xMin, f['hmtx'][gid], f['OS/2'].usWeightClass,
          f['name'].getDebugName(4))
"
```

`fvar` must be absent (confirms a static instance), the glyph bbox width / advance width /
`OS/2.usWeightClass` must differ between the Regular and SemiBold/Bold instance of the same
family, and — since every command above passes `--update-name-table` (or, for Fraunces, its
Step-3 equivalent) — `name` ID 4 (what `Font.getFontName()` returns in Java) must differ too.

## Files

| File | Family | Weight | `getFontName()` in Java | Notes |
|---|---|---|---|---|
| `Rubik-Regular.ttf` | Rubik | 400 | `Rubik Regular` | Primary text face — covers Latin, Cyrillic, Hebrew, Arabic |
| `Rubik-SemiBold.ttf` | Rubik | 600 | `Rubik SemiBold` | Primary text face, semibold |
| `NotoSans-Regular.ttf` | Noto Sans | 400, wdth=100 | `Noto Sans Regular` | Fallback for scripts Rubik doesn't cover (e.g. Greek) |
| `NotoSans-SemiBold.ttf` | Noto Sans | 600, wdth=100 | `Noto Sans SemiBold` | Fallback, semibold |
| `NotoSansHebrew-Regular.ttf` | Noto Sans Hebrew | 400, wdth=100 | `Noto Sans Hebrew Regular` | Hebrew fallback tail of the chain |
| `HankenGrotesk-Bold.ttf` | Hanken Grotesk | 700 | `Hanken Grotesk Bold` | "calit" wordmark — matches the site's body sans at weight 700 |
| `Fraunces-Chip.ttf` | Fraunces | 600, opsz=14 | `Fraunces 9pt SemiBold NonWonky` | The chip's "c" — matches `.lp-brand` on the landing page |
