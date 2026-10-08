# Validation evidence

## macOS execution

Apple M5 Pro, 15 CPU cores, 48 GiB unified memory, macOS 27.0.1, Temurin
17.0.20.1+1. Tests use synthetic PDFs and a small OFL-licensed Noto font subset.

- 50 JUnit tests passed in the local integrated run, including core, CLI,
  Korean/Japanese/Chinese/ligatures, review decisions, and real Swing component
  workflows. No skipped tests in that run.
- The workflow test clicks the actual offscreen Analyze/Approve/Skip controls,
  waits for background PDF rendering, exports via the same worker as the desktop,
  reopens the output, and checks comments, coordinates, decisions and unchanged
  original bytes. Native window-manager/file-picker clicking is **not verified**:
  the available desktop automation connection failed. Offscreen rendering is
  not presented as a native desktop screenshot.
- Fresh JAR workflow: analyze -> explicit confident-only export -> output
  reopen checks -> overwrite refusal -> tampered-plan refusal -> cleanup passed.
- Independent review reproduced and fixed CCW/Z-order quads, partial-word
  matching, invisible crop-area targets, automatic OpenAction URI, and a plan
  larger than the CLI could reload. Cancellation after first output publication
  removes it; report publication conflicts preserve the existing report.

## Performance

Command after `mvn verify`:

```
java -Djava.awt.headless=true -Xmx512m -cp target/test-classes:target/annotrail-0.1.0.jar net.whago.annotrail.Benchmark target 100
```

Windows uses `;` instead of `:` in the classpath. macOS `/usr/bin/time -l`
measured peak process RSS. Results include analyze, fresh reanalysis and export;
fixture generation is excluded from that timer but included in process RSS.
The JVM heap cap is not a total process-memory cap.

| Synthetic workload | Input bytes (both) | Analysis/reanalysis/export | Peak RSS |
|---|---:|---:|---:|
| 100 pages per PDF, 100 annotations | 112,099 | 0.773 s | 476,102,656 B |
| 400 pages per PDF, 400 annotations | 445,435 | 2.363 s | 677,363,712 B |

All proposed mappings were transferred and originals verified. These are simple
text PDFs, not image-heavy publishing documents. The local LLM was resident and
other project work was possible; load was not controlled, so these are not
isolated benchmark claims. Generated PDFs and reports were deleted automatically.

## Distribution and CI

macOS runtime-included app ZIP extraction -> native launcher -> full CLI workflow
-> removal passed. Three-OS CI results are linked from the release after execution.
A configured workflow is not a passing result. Native packages are
unsigned; macOS notarization and interactive OS trust-dialog behavior are not
verified. Runtime source archives and upstream notices accompany releases.

## Scope limits exercised or documented

Input size/page/text/annotation/candidate/plan budgets, corrupt/encrypted input,
signed revised PDFs, unsupported annotations/rotation, no selected text, repeated
text, crop offsets, line wrapping, absent anchors, stale plans, repeated transfer,
source hashes, atomic publication/rollback and cancellation are covered. An
anchor that crosses a revised page boundary is unresolved; OCR text-layer
accuracy is unverified. No private user corpus or third-party paid service was
used. This release does not establish real-user adoption or willingness to pay.
