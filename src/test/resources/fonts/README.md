# Synthetic Unicode PDF fixture font

`AnnotrailTestCJK-Regular.ttf` is a 41,060-byte test-only derivative of Noto Sans CJK KR.
It contains ASCII, the Korean/Japanese/Chinese characters used in
`UnicodePdfTest`, and U+FB01 LATIN SMALL LIGATURE FI. It is not a complete font.
Tests embed the font in temporary PDFs; no user fonts or documents are used.
Maven's test resources are not bundled in the production application jar.

## License and attribution

Upstream font copyright: © 2014–2021 Adobe (http://www.adobe.com/),
with Reserved Font Name 'Source'. Licensed under the SIL Open Font License 1.1.
The complete, unmodified license is in `OFL.txt`; its copyright and license
metadata also remain embedded in the font. The derivative has been renamed
**Annotrail Test CJK** and remains under OFL 1.1. Font subsetting/embedding is
permitted under the included license. The test code and rebuild script use the
repository's Apache 2.0 license.

## Pinned source and integrity

- Official repository: https://github.com/notofonts/noto-cjk
- Revision: `f8d157532fbfaeda587e826d4cd5b21a49186f7c`
- Upstream font: https://raw.githubusercontent.com/notofonts/noto-cjk/f8d157532fbfaeda587e826d4cd5b21a49186f7c/Sans/Variable/TTF/NotoSansCJKkr-VF.ttf
- Upstream license: https://raw.githubusercontent.com/notofonts/noto-cjk/f8d157532fbfaeda587e826d4cd5b21a49186f7c/Sans/LICENSE
- Upstream font version: `2.004`
- Upstream font bytes: `36140528`
- Upstream font SHA-256: `7715af52f5fe77153ce5678546258993982d2da61abea8d25fb89eb5aaec5ca6`
- License SHA-256: `6a73f9541c2de74158c0e7cf6b0a58ef774f5a780bf191f2d7ec9cc53efe2bf2`
- Subset SHA-256: `7fcbf4c4e02de6aa61449586e0d6323be77e4d9bb382039afd3c84ec11c9c8a9`

## Rebuild

Download only the font URL above to a temporary directory. With Python and
`fonttools==4.60.1` installed in an isolated environment, run:

```sh
python subset_font.py /path/to/NotoSansCJKkr-VF.ttf
```

The script validates the upstream SHA-256, fixes weight to 400, removes hinting,
subsets the required characters, preserves licensing metadata, renames the
fixture family, and writes `AnnotrailTestCJK-Regular.ttf` next to itself.
Font timestamps are preserved to make the result deterministic. The original
36 MB font and the Python environment are not needed to run Java tests and are
not committed.
