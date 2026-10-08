# Security and data handling

All processing is local. No URLs, commands, plugins, external model, analytics,
or network listener are used. PDF content is untrusted parser input. The pinned
PDFBox version, process heap cap, page/text/annotation/candidate caps and explicit
cancellation reduce exposure; this is not an OS sandbox or a malware scanner.

Inputs are opened read-only and never used as output. Source hashes are checked
again when exporting, and a saved plan is checked against fresh analysis before
its mappings are applied. New output paths are required. Keep originals and
review the resulting PDF visually before replacing your own working copy.

Use output folders you control. Exclusive creation prevents overwriting an
existing destination, but portable filesystem APIs cannot make identity checks
and rollback deletion atomic against a hostile concurrent process. Providers
without file keys also cannot distinguish a later replacement with identical
contents. On volumes
without hard links, a destination is visible while its contents are copied;
interrupted power or forced termination may leave partial output. Only a
successful export confirms that both the PDF and report completed.

The report intentionally retains annotation text, comments, author labels,
filenames and hashes. These are potentially private. Sharing a report is a user
action outside the application; review/redact it first. No document corpus is
uploaded during issue reporting.

Encrypted input, signed revised PDFs and specified active-content constructs
are rejected. Existing static links may remain in the revised PDF; the app does
not open them. Writing a new PDF may change serialization and metadata; the
result is not an archival identity-preserving rewrite or signature-preserving
workflow.

Report vulnerabilities through GitHub private vulnerability reporting if
available, otherwise open a minimal issue requesting a private contact without
including sensitive files or exploit content.
