# Card fonts

Static font instances used by `site.asm0dey.calit.web.og.CardFonts` to render the social-preview
card (Task 3). AWT's `Font.createFont` ignores a variable font's `fvar` axes — it silently loads
whatever the font's default named instance is — so weight/width/optical-size selection has to
happen ahead of time, baked into a static instance, rather than at runtime.

All seven files here were produced with [fontTools](https://github.com/fonttools/fonttools)
`varLib.instancer` (tested with fontTools 4.63.0) from the corresponding Google Fonts variable
source. Each family's `OFL.txt` license is included alongside its instance(s) as
`OFL-<Family>.txt`, fetched from the same directory in the `google/fonts` repo.

## Regenerating

```bash
cd /tmp && mkdir -p calit-fonts && cd calit-fonts
curl -sSL -o Rubik.ttf          "https://raw.githubusercontent.com/google/fonts/main/ofl/rubik/Rubik%5Bwght%5D.ttf"
curl -sSL -o NotoSans.ttf       "https://raw.githubusercontent.com/google/fonts/main/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf"
curl -sSL -o NotoSansHebrew.ttf "https://raw.githubusercontent.com/google/fonts/main/ofl/notosanshebrew/NotoSansHebrew%5Bwdth%2Cwght%5D.ttf"
curl -sSL -o Hanken.ttf         "https://raw.githubusercontent.com/google/fonts/main/ofl/hankengrotesk/HankenGrotesk%5Bwght%5D.ttf"
curl -sSL -o Fraunces.ttf       "https://raw.githubusercontent.com/google/fonts/main/ofl/fraunces/Fraunces%5BSOFT%2CWONK%2Copsz%2Cwght%5D.ttf"

python3 -m fontTools.varLib.instancer Rubik.ttf          wght=400              -o Rubik-Regular.ttf
python3 -m fontTools.varLib.instancer Rubik.ttf          wght=600              -o Rubik-SemiBold.ttf
python3 -m fontTools.varLib.instancer NotoSans.ttf       wght=400 wdth=100     -o NotoSans-Regular.ttf
python3 -m fontTools.varLib.instancer NotoSans.ttf       wght=600 wdth=100     -o NotoSans-SemiBold.ttf
python3 -m fontTools.varLib.instancer NotoSansHebrew.ttf wght=400 wdth=100     -o NotoSansHebrew-Regular.ttf
python3 -m fontTools.varLib.instancer Hanken.ttf         wght=700              -o HankenGrotesk-Bold.ttf
python3 -m fontTools.varLib.instancer Fraunces.ttf       wght=600 opsz=14 SOFT=0 WONK=0 -o Fraunces-Chip.ttf

mkdir -p ~/work_self/calit/src/main/resources/fonts
cp Rubik-Regular.ttf Rubik-SemiBold.ttf NotoSans-Regular.ttf NotoSans-SemiBold.ttf \
   NotoSansHebrew-Regular.ttf HankenGrotesk-Bold.ttf Fraunces-Chip.ttf \
   ~/work_self/calit/src/main/resources/fonts/
```

Then fetch each family's `OFL.txt` from the same `google/fonts` directory as its source `.ttf`
and save it here as `OFL-<Family>.txt`.

Fraunces is deliberately instanced at `opsz=14`, not the `opsz=144` display cut — 14 matches the
~17px the site renders the brand chip's "c" at (`.lp-brand` on the landing page). Using the
display cut here would look wrong at chip size.

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
    print(path, f['glyf'][gid].xMax - f['glyf'][gid].xMin, f['hmtx'][gid], f['OS/2'].usWeightClass)
"
```

`fvar` must be absent (confirms a static instance) and the glyph bbox width / advance width /
`OS/2.usWeightClass` must differ between the Regular and SemiBold/Bold instance of the same
family.

## Files

| File | Family | Weight | Notes |
|---|---|---|---|
| `Rubik-Regular.ttf` | Rubik | 400 | Primary text face — covers Latin, Cyrillic, Hebrew, Arabic |
| `Rubik-SemiBold.ttf` | Rubik | 600 | Primary text face, semibold |
| `NotoSans-Regular.ttf` | Noto Sans | 400, wdth=100 | Fallback for scripts Rubik doesn't cover (e.g. Greek) |
| `NotoSans-SemiBold.ttf` | Noto Sans | 600, wdth=100 | Fallback, semibold |
| `NotoSansHebrew-Regular.ttf` | Noto Sans Hebrew | 400, wdth=100 | Hebrew fallback tail of the chain |
| `HankenGrotesk-Bold.ttf` | Hanken Grotesk | 700 | "calit" wordmark — matches the site's body sans at weight 700 |
| `Fraunces-Chip.ttf` | Fraunces | 600, opsz=14 | The chip's "c" — matches `.lp-brand` on the landing page |
