"""Rebuild the licensed synthetic fixture; requires fonttools==4.60.1."""
import hashlib
import pathlib
import sys
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

SOURCE_SHA256 = "7715af52f5fe77153ce5678546258993982d2da61abea8d25fb89eb5aaec5ca6"
TEXT = (
    "개정 문서에서도 한글 인용문과 수치 42를 정확하게 보존합니다."
    "改訂後も日本語の引用文と数値42を正確に保持します。"
    "修订文档仍然保留中文引用和数值42的准确内容。"
    "ﬁ"
)
if len(sys.argv) != 2:
    raise SystemExit("Usage: python subset_font.py PATH_TO_NotoSansCJKkr-VF.ttf")
source = pathlib.Path(sys.argv[1])
if hashlib.sha256(source.read_bytes()).hexdigest() != SOURCE_SHA256:
    raise SystemExit("Source SHA-256 does not match the pinned upstream font.")
font = TTFont(source, recalcTimestamp=False)
required = set(range(0x20, 0x7F)) | {ord(c) for c in TEXT}
missing = required - set(font.getBestCmap())
if missing:
    raise SystemExit(f"Upstream font lacks required codepoints: {sorted(missing)}")
font = instantiateVariableFont(font, {"wght": 400}, inplace=True)
options = subset.Options()
options.hinting = False
options.name_IDs = ["*"]
options.name_languages = ["*"]
options.recalc_timestamp = False
subsetter = subset.Subsetter(options=options)
subsetter.populate(unicodes=required)
subsetter.subset(font)
# The derivative is only a fixture, not a general Noto distribution.
rename = {
    1: "Annotrail Test CJK", 2: "Regular", 3: "Annotrail-Test-CJK-Regular-1",
    4: "Annotrail Test CJK Regular", 6: "AnnotrailTestCJK-Regular",
    16: "Annotrail Test CJK", 17: "Regular",
}
for name in font["name"].names:
    if name.nameID in rename:
        name.string = rename[name.nameID].encode(name.getEncoding())
output = pathlib.Path(__file__).with_name("AnnotrailTestCJK-Regular.ttf")
font.save(output, reorderTables=True)
print(f"{hashlib.sha256(output.read_bytes()).hexdigest()}  {output.name}")
print(f"{output.stat().st_size} bytes")
