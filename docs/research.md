# Why this problem

Research reviewed 2026-10-08. This is a hypothesis supported by repeated feature
requests, not a verified paid-customer business.

Zotero users request transferring annotations to revised papers and differently
cropped document editions. Existing coordinates can become incorrect after
replacement, and built-in transfer was unavailable in the cited discussions:

- https://forums.zotero.org/discussion/125669/transfering-my-annotations-from-old-pdf-to-new-pdf
- https://forums.zotero.org/discussion/118039/zotero-7-annotation-transfer

Adobe's comment import preserves original positions, which can misalign when
the target document changes:
https://helpx.adobe.com/uk/acrobat/using/importing-exporting-comments.html

The useful unit of work is therefore old annotated PDF + revised PDF -> proposed
text anchors -> explicit review -> new annotated PDF + unresolved record.

## Alternatives and counterevidence

Zotero is the preferred place to manage a research library; this app complements
its embedded-PDF export/import workflow, it does not replace the library or
modify its database: https://www.zotero.org/support/kb/annotations_in_database

Adobe is suitable for comments on unchanged layouts. Other products, including
Perdix, have been reported to offer guided reconciliation; an exact primary
product URL could not be independently verified during this task. We do not
claim to invent annotation reconciliation or to be better than every existing
product. Independent local files, explicit unresolved decisions and original
preservation are the product contract to validate with real users.

## Language and library decision

| Role | Choice | Reason | Cost compared with simpler alternatives |
|---|---|---|---|
| PDF text positions, annotations, rendering | Java 17 + PDFBox 3.0.8 | One Apache-licensed library handles all three | JVM package size and PDF coordinate complexity |
| Desktop review | Swing | Same runtime, native desktop workflow, no web service | Less modern appearance and manual UI work |
| Build/package helper | Python standard library | Portable jpackage orchestration and checksums | Build-time Python only; not a second product runtime |

Java is chosen for the PDF APIs, not to increase a language count. PDFSharp's
FAQ says it lacks PDF rendering and high-level text extraction; combining it
with PdfPig plus a renderer adds coordinate conversion and interoperability
risk for this scope: https://docs.pdfsharp.net/PDFsharp/Overview/FAQ.html

PDFBox 3.0.8 is pinned from the official release index:
https://pdfbox.apache.org/download.html
Its 3.x APIs include Loader and concrete annotation subclasses:
https://pdfbox.apache.org/3.0/migration.html
JDK jpackage creates a native runtime-containing image on each target OS:
https://docs.oracle.com/en/java/javase/17/jpackage/packaging-overview.html

## Acceptance criteria

Generated PDFs must cover inserted pages, reflow, repeated text, missing text,
multiline markup, comments, crop offsets, rotation rejection, all supported
annotation types, signed/encrypted/active-content rejection, cancellation,
corrupt input, stale plans, and output conflicts. Exported PDFs are reloaded to
check annotation count, content and coordinates; original SHA256s stay fixed.
Large fixtures are deleted after measurement. No private user PDFs are included.
