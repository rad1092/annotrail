# Validation evidence

## macOS execution

Apple M5 Pro, 15 CPU cores, 48 GiB unified memory, macOS 27.0.1, Temurin
17.0.20.1+1. Tests use synthetic PDFs and a small OFL-licensed Noto font subset.

- 72 JUnit tests passed in the 0.1.1 local integrated run, including core, CLI,
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
-> removal passed. Three-OS CI results are linked from the corresponding release after execution.
A configured workflow is not a passing result. Native packages are not Developer ID/Authenticode signed; macOS notarization
and interactive OS trust-dialog behavior are not verified. The macOS ad-hoc
resource-integrity seal is a separate requirement, exercised in the 0.1.2 checks
below. Runtime source archives and upstream notices accompany releases.

## Scope limits exercised or documented

Input size/page/text/annotation/candidate/plan budgets, corrupt/encrypted input,
signed revised PDFs, unsupported annotations/rotation, no selected text, repeated
text, crop offsets, line wrapping, absent anchors, stale plans, repeated transfer,
source hashes, exclusive publication/rollback and cancellation are covered. An
anchor that crosses a revised page boundary is unresolved; OCR text-layer
accuracy is unverified. No private user corpus or third-party paid service was
used. This release does not establish real-user adoption or willingness to pay.


## 0.1.1 follow-up

The prior hard-link-only output path could not publish files on exFAT/FAT32.
Version 0.1.1 retains exclusive hard-link publication when available and uses
exclusive new-file copy otherwise. The fallback does not offer atomic visibility
or power-loss durability; callers must wait for export success. Regression
verification covers unsupported links, collisions, cancellation/partial writes,
report publication failure, and preservation of replacement files.

A real 64 MiB exFAT disk-image test was attempted on this Mac, but the operating
system refused image creation before mounting. No security/permission change
was attempted. Actual external exFAT/FAT32 hardware is therefore not a tested
platform; injected unsupported-hardlink, missing-file-key and write-failure tests exercise
the fallback.

Native UI connection was retried using the verified bundle ID and installed app
path. Inventory worked, but the path-based tool call exceeded its requested
10-second timeout and required cancellation. No further calls of that kind are
made. Native window/file-picker clicks remain unverified. The signing-identity
query returned zero valid identities; no signing key, new credential, payment,
or operating-system security change was introduced.

The full 0.1.1 local suite passed **72/72 tests**, with zero skipped tests. The
new 22 cases cover publication failures and complete PDF fallback exports.
A separate native-window runner created a real macOS JFrame and verified
window-open/showing state, two PDF previews, two approvals/two skips, repeated
candidate selection, export/reopen and original-byte preservation. Against the
fresh 0.1.1 JAR it completed in 1.181 seconds and disposed its own window. This
runner drives the application's own Swing controls; it does not synthesize OS
input, open the native file picker, or capture the user's screen.

Reproduce after `mvn verify` with `python3 scripts/native_smoke.py` and `JAVA_HOME`
set. The wrapper bounds the subprocess to 60 seconds, keeps only its small JSON
and rendered-content image/logs in `target/native-smoke`, and removes generated
PDFs. Linux needs a graphical display or `xvfb-run`. CI executes this native
window workflow in addition to the headless JUnit suite and extracted-package
CLI workflow on every configured platform.


## 0.1.2 macOS package integrity correction

The 0.1.0/0.1.1 ZIP writer dereferenced 21 runtime legal-document symlinks
**after** jpackage had signed those links. The installed runtime and app therefore
failed strict sealed-resource verification. This was a packaging bug, separate
from the lack of Developer ID and notarization.

Version 0.1.2 materializes only bounded relative runtime legal-file links before
signing. It rejects unexpected/escaping/broken links, signs Mach-O contents,
then the runtime and outer app, and writes only regular files to the portable
ZIP. The actual ZIP is extracted and both seals verified before packaging can
succeed. The extracted-install workflow repeats verification before launching.

Local validation on the Mac above passed:

- 72/72 JUnit tests, with no failures or skipped tests.
- 13/13 packaging tests in both normal and Python `-O` modes. These reproduce
  the original post-signing link-flattening failure, verify the corrected round
  trip, and reject altered license resources and JAR payloads.
- Independently extracted real 0.1.2 ZIP: runtime and outer app passed
  `codesign --verify --deep --strict`; all 28 Mach-O files passed individual
  strict verification. Both resource seals contain zero symlink entries.
- The extracted native launcher completed the PDF analyze/export/reopen workflow.
- The real native-window runner passed in 1.235 seconds: 4 annotations,
  2 approvals, 2 skips, 2 previews, repeated candidate selection, PDF/report
  export, reopening, and source-byte preservation. It disposed its own window.

CI runs the portable packaging regressions on all three platforms. The two
codesign integration tests are macOS-only and explicitly skipped elsewhere;
Windows hosts unable to create test symlinks explicitly skip affected cases.
Executed CI results are linked in the release notes. External OS mouse/keyboard
input and the native file chooser remain untested (zero such inputs). Ad-hoc
integrity verification establishes neither publisher identity nor notarization.

The initial 0.1.2 Windows CI exposed non-native symlink targets in the new
test fixtures and Python ZIP-name normalization. The fixtures now use native
link targets and byte-exact malformed ZIP names; extraction checks the raw
name before normalization. Backslash/NUL rejection and the Windows runtime
layout are covered. Packaged license paths also match the notice links.
