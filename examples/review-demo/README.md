# Try annotation transfer in five minutes

Download [old.pdf](https://raw.githubusercontent.com/rad1092/annotrail/main/examples/review-demo/old.pdf)
and [new.pdf](https://raw.githubusercontent.com/rad1092/annotrail/main/examples/review-demo/new.pdf)
into a practice folder. These tiny authored documents contain only synthetic
text and annotations. Open the app from [Releases](https://github.com/rad1092/annotrail/releases);
the runtime-included package does not require a separate Java installation.

1. Select `old.pdf` as **Original annotated PDF** and `new.pdf` as **Revised PDF**.
2. Choose **Analyze PDFs**. Expect four entries: one **Unique exact text**, one **Needs review**,
   one **Not found** and one **Unsupported**.
3. Inspect the unique highlighted sentence. It moved from old page 1 to new
   page 2. Approve its candidate after comparing the page previews.
4. Explicitly skip the other three entries for this exercise. The repeated
   phrase has two possible destinations; the missing phrase has none; ink
   drawings are unsupported. None of these should be silently guessed.
5. Choose **Export reviewed PDF…** (top right) and use fresh names such as `reviewed.pdf` and `reconciliation.json`.
6. Open the exported PDF: its first page keeps the revision's existing sticky
   note, and page 2 has the transferred highlight. The report should show
   **1 transferred, 3 skipped**. The two input PDFs should still be unchanged.

## CLI version of the exercise

From the checkout root with Java 17+ and a built JAR:

```sh
java -Xmx512m -jar target/annotrail-0.1.4.jar analyze --old examples/review-demo/old.pdf --new examples/review-demo/new.pdf --plan plan.json
```

Exit `1` is expected: three entries need an explicit decision. Inspect the plan,
then use the included [choices.json](choices.json), which accepts `p1-a0` and
skips the other three annotations in these exact sample files:

```sh
java -Xmx512m -jar target/annotrail-0.1.4.jar export --old examples/review-demo/old.pdf --new examples/review-demo/new.pdf --plan plan.json --choices examples/review-demo/choices.json --output reviewed.pdf --report reconciliation.json
```

Expect exit `0`. Repeating the export with the same output names must fail with
exit `2`, preserving the completed outputs. For the standalone release JAR,
replace `target/annotrail-0.1.4.jar` with its actual path. Never reuse these IDs or
choices with your own PDFs; create and review their own plan.

## If your own PDFs do not match

- No selectable text: this release does not perform OCR.
- Zotero-only annotations: first export an annotated PDF; the app does not read
  Zotero's database.
- Changed wording or multiple matches: inspect context and explicitly skip or
  choose; exact text matching does not prove unchanged meaning.
- Stale/edited plan: analyze the current inputs again before making decisions.
- Existing output path: select new PDF and report filenames.

## Provenance and reproduced check

The samples come from the repository's `FixtureGenerator` test helper (PDFBox,
standard Helvetica); no customer documents or external fonts are included.
After `mvn verify`, maintainers can regenerate them in a **new** directory with:

```sh
java -cp target/test-classes:target/annotrail-0.1.4.jar net.whago.annotrail.FixtureGenerator /tmp/annotrail-new-samples
```

On Windows the classpath separator is `;`. Generation refuses existing PDFs.
PDF identifiers may vary, so regenerate a review plan after regenerating samples.

On 2026-10-12, the public 0.1.2 macOS arm64 ZIP passed its checksum and strict
ad-hoc signature checks on macOS 27.0.1. The installed launcher passed analyze,
reviewed export, stale-plan rejection, overwrite rejection and unchanged-input
hash checks. This is synthetic evidence, not a claim about arbitrary PDFs or
manual testing of every native file dialog.
