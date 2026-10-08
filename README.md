# Annotrail

**Move embedded PDF annotations to a revised PDF, with a review trail.**
Choose the annotated old PDF and its new revision, inspect matching text and page
previews, then export approved annotations into a new PDF. Original files stay
unchanged. Everything runs on your computer, without accounts, uploads or AI.

Annotrail is useful when pages or line breaks move between paper drafts, course
notes, or document editions. It searches text rather than copying old page
coordinates. Exact text does not prove unchanged meaning: read the surrounding
context before approving a suggestion.

## Install and open

Download your platform's portable package and `SHA256SUMS` from
[Releases](https://github.com/rad1092/annotrail/releases). Verify the archive's
SHA-256, then extract it into a folder you control. The app includes Java:

- macOS: open `Annotrail.app`.
- Windows: open `Annotrail/Annotrail.exe`.
- Linux: run `Annotrail/bin/Annotrail` in a desktop session.

macOS bundle build `2` corresponds to product version `0.1.1` (shown by `--version`).
These packages are unsigned and not notarized. OS trust prompts
may apply; no security protection needs to be disabled. Headless systems can
use the included `annotrail-0.1.1.jar` with Java 17+.

To remove the app, close it and delete only the extracted app folder. No service,
login item, browser extension, global configuration, or hidden user database is
installed. Your PDFs and exported reports are ordinary files you choose.

## Review workflow

1. Select **Old annotated PDF** and **Revised PDF**, then analyze.
2. Select an annotation. Compare the old page with candidate positions on the
   revised page, and read the quoted text/comment and surrounding context.
3. Approve a candidate or explicitly skip it. “Accept confident” accepts only
   unique exact-text suggestions; “Skip unresolved” is a separate decision.
4. Export to a **new** PDF filename and a **new** JSON reconciliation report.
   Existing annotations in the revised PDF remain in place.
5. Reopen the output in your normal PDF reader and review the transferred marks.
   The report retains skipped/unresolved annotation details for follow-up.

| Classification | Meaning |
|---|---|
| Unique exact text | One sufficiently long exact normalized text match; still review context |
| Review required | Repeated/short text or a sticky-note anchor; choose a candidate or skip |
| Not found | No exact text anchor found; wording may have changed or extraction failed |
| Unsupported | Annotation or page geometry outside the supported scope |

No fuzzy match or LLM decides that changed wording is equivalent. “Not found”
does not establish that the content was deleted. Candidate counts are bounded;
when a phrase has many matches, the report explains the limit.

### Supported first-release scope

- Embedded **Highlight**, **Underline**, **StrikeOut**, and sticky **Text** comments.
- Selectable horizontal text, including line-wrap and whitespace changes within one page.
  An anchor split across two revised pages remains unresolved.
- Page insertion/reordering and supported crop offsets.
- Exact text matching with Unicode normalization; ambiguous matches stay visible.

Image-only scans (no OCR is performed), ink, drawings, free-text boxes, stamps, rotated pages/text, arbitrary
manual text selection, encrypted files, and signed revised PDFs are excluded.
OCR text-layer accuracy is not validated, and invisible text can still be extracted;
review the rendered location. Active JavaScript, embedded files, launch actions and additional actions are
rejected. This is a limited PDF editor, not a general hostile-PDF sandbox.

## Zotero

Zotero stores its own annotations separately from the PDF by default. Export
an annotated PDF using Zotero's **Export PDF** or reader **Save As** first.
After Annotrail exports the revised PDF, add it as a **new attachment** and use
Zotero's **Import Annotations** if you want editable Zotero annotations.
Annotrail does not open or modify the Zotero database. See the
[official annotation storage guide](https://www.zotero.org/support/kb/annotations_in_database).

## CLI and automation

Java 17+ is required for the standalone JAR:

```sh
java -Xmx512m -jar annotrail-0.1.1.jar analyze \
  --old annotated.pdf --new revision.pdf --plan plan.json
```

The plan contains input names/hashes, every annotation, candidate locations,
comments and author metadata. **It can contain private document content.**
Review it before sharing. Save explicit choices as a JSON object, using an
annotation's `id` as the key and a zero-based candidate index or `-1` to skip:

```json
{"p1-a0": 0, "p2-a0": -1}
```

IDs above illustrate the format; always use the actual IDs in your plan. Every
annotation must have a decision. Then:

```sh
java -Xmx512m -jar annotrail-0.1.1.jar export \
  --old annotated.pdf --new revision.pdf --plan plan.json \
  --choices choices.json --output reviewed.pdf --report reconciliation.json
```

Alternatively, `--accept-confident` explicitly accepts unique exact matches and
skips all unresolved entries. It is convenient for a reviewed plan, not a claim
that unattended transfer is semantically safe. The engine reanalyzes inputs
before export and rejects a stale or edited plan. After upgrading from 0.1.0,
analyze again to create a 0.1.1 review plan.

Exit codes: `0` complete, `1` analysis has entries needing review, `2` input/output
or resource error, `130` cooperative cancellation. Every output must be a new
path, distinct from all inputs. Do not treat a failed/cancelled run as complete.

Outputs use an exclusive hard link where available. On filesystems without hard
links, such as exFAT and FAT32, Annotrail reserves each new name exclusively and
copies the verified staged file. Existing files are never replaced. During this
fallback another program may briefly see an incomplete destination; wait for a
successful export before opening it. Cancellation or a write failure attempts
to remove only files still identified as belonging to that export. A power loss
can leave partial files, so a failed run must not be treated as completed.

Default caps: 256 MiB per input file, 500,000 inspected PDF objects, 500 pages per file, 2 million extracted characters, 2,000 source
annotations, 20 candidates per annotation, an 8 MiB serialized-entry plan budget,
and a 512 MiB JVM heap in launchers.
PDF parsing can still be expensive; OS quotas and trusted storage remain useful
for large/untrusted documents. See [security](SECURITY.md).

## Build and verify

JDK 17+ and Maven 3.9+:

```sh
mvn -B verify
java -Xmx512m -jar target/annotrail-0.1.1.jar --help
java -Xmx512m -jar target/annotrail-0.1.1.jar gui
```

`python3 scripts/package.py --skip-build` creates a runtime-included app image
and ZIP using the active `JAVA_HOME`. Package on each target OS; cross-building
native launchers is not claimed. GitHub CI tests core/CLI/review state, validates
installed app runtimes, and produces macOS/Linux/Windows packages.

[Validation evidence](docs/validation.md) distinguishes executed checks from
unverified desktop interactions. [Research](docs/research.md) describes the
problem, alternatives, and remaining market assumptions.

Apache-2.0 licensed. PDFBox, Gson, and the bundled Java runtime retain their
licenses. See [NOTICE](NOTICE) and [third-party notices](THIRD_PARTY_NOTICES.md).
