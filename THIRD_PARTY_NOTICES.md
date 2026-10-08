# Third-party notices

Annotrail's own source is licensed under Apache License 2.0. That license does not replace the separate terms for bundled libraries, fonts, character data or a Java runtime. The original upstream texts below are part of the distribution and must be retained.

This inventory was checked against the resolved runtime dependency tree for Annotrail 0.1.0 on 2026-10-08. Test-only JUnit dependencies and Maven build plugins are not part of the application runtime inventory.

| Component | Version | Declared project license | Original notices |
|---|---|---|---|
| Apache PDFBox | 3.0.8 | Apache-2.0 plus bundled component terms | [LICENSE](docs/licenses/pdfbox-3.0.8/LICENSE), [NOTICE](docs/licenses/pdfbox-3.0.8/NOTICE) |
| Apache FontBox | 3.0.8 | Apache-2.0 plus bundled component terms | [LICENSE](docs/licenses/fontbox-3.0.8/LICENSE), [NOTICE](docs/licenses/fontbox-3.0.8/NOTICE) |
| Apache PDFBox IO | 3.0.8 | Apache-2.0 | [LICENSE](docs/licenses/pdfbox-io-3.0.8/LICENSE), [NOTICE](docs/licenses/pdfbox-io-3.0.8/NOTICE) |
| Apache Commons Logging | 1.4.0 | Apache-2.0 | [LICENSE](docs/licenses/commons-logging-1.4.0/LICENSE.txt), [NOTICE](docs/licenses/commons-logging-1.4.0/NOTICE.txt) |
| Google Gson | 2.13.2 | Apache-2.0 | [LICENSE](docs/licenses/gson-2.13.2/LICENSE), [published POM](docs/licenses/gson-2.13.2/pom.xml) |
| Google Error Prone annotations | 2.41.0 | Apache-2.0 | [LICENSE](docs/licenses/error_prone_annotations-2.41.0/LICENSE), [published POM](docs/licenses/error_prone_annotations-2.41.0/pom.xml) |

PDFBox and FontBox carry additional copyright and permission statements, including Adobe font metrics/CMaps/glyph data, original PDFBox/FontBox contributors, Unicode data, TwelveMonkeys contributions, SIL Open Font License fonts and CC0 profile material. Their complete LICENSE files are preserved without editing. This list is descriptive; the full original files control the terms for each included component. Fonts and data are not relicensed as Annotrail code.

Gson and Error Prone annotations did not contain a LICENSE or NOTICE entry in the inspected binary JARs. Their published POMs declare Apache-2.0; the preserved license texts were retrieved from the corresponding upstream release tags, [gson-parent-2.13.2](https://github.com/google/gson/tree/gson-parent-2.13.2) and [v2.41.0](https://github.com/google/error-prone/tree/v2.41.0). No missing NOTICE text was invented.

The [license inventory](docs/licenses/inventory.json) records exact coordinates, original JAR SHA-256 values, document SHA-256 values and upstream locations. The JAR contains a copy under `META-INF/third-party/`; packaged archives also carry `docs/licenses/`. Keep the full texts when redistributing the JAR or application. No upstream source code has been edited; the application build combines library classes/resources into an executable JAR and merges standard manifest/notice metadata. Original notices are also retained separately to avoid loss during that merge.

## Java runtime in application images

Portable application images use Eclipse Temurin OpenJDK **17.0.20.1+1**. The standalone application JAR does not contain the Java runtime. The original [Temurin NOTICE](docs/licenses/temurin-17.0.20.1+1/NOTICE), [GPLv2 with Classpath Exception](docs/licenses/temurin-17.0.20.1+1/legal/java.base/LICENSE), [additional license information](docs/licenses/temurin-17.0.20.1+1/legal/java.base/ADDITIONAL_LICENSE_INFO) and [Assembly Exception](docs/licenses/temurin-17.0.20.1+1/legal/java.base/ASSEMBLY_EXCEPTION) are preserved, together with the legal files for modules selected by the packaging script and their dependencies.

The Classpath Exception permits linking qualifying independent application modules under their own terms; it does not remove obligations for distributing the Java runtime itself. Retain the runtime image's complete `legal/` directory and Temurin NOTICE. JDK third-party components retain their stated terms, including the font/rendering and character-data notices in `java.desktop` and `java.base`. This documentation copy was audited from the macOS ARM64 build; each platform image's own legal directory remains authoritative.

Runtime-bearing releases must provide the exact corresponding source and build scripts alongside the binary downloads. The following archives are prepared for publication as separate release assets:

- `OpenJDK17U-jdk-sources_17.0.20.1_1.tar.gz` — full upstream source, including OpenJDK configure/make scripts and build documentation. SHA-256: `21e2a065d244ab048e737f21af5d1fc74daaeb6707de36477ead8db1dca71214`.
- `temurin-build-e6ba7dec3d07654074559310376a3ae89da5f4ac.tar.gz` — exact upstream Temurin build orchestration scripts. SHA-256: `b1340378b9ed62b32acfacd012dd069ec2b9d940fc39d3c73f32142d64e89713`.

The [source provenance record](docs/licenses/temurin-source-provenance.json) includes official download URLs, commits, hashes and recipe locations. Annotrail's `scripts/package.py` records the final module-image and application packaging steps. The runtime sources were not changed; `jlink`/`jpackage` select modules and create the application image. Check the provenance record and actual release assets rather than treating this notice as confirmation that an upload has already happened. Do not publish a runtime-bearing release without its corresponding source assets and retained notices.

The exact upstream runtime source revision is `79597447bd94ff9f8216e2e3a573d5261ee49f2e`; the Temurin build-script revision is `e6ba7dec3d07654074559310376a3ae89da5f4ac`. Platform-specific compiler settings are in the official release metadata. Rebuilding those sources has not been claimed or tested by Annotrail; the application images use the upstream runtime binaries.

Names and trademarks identify the upstream components and do not imply endorsement of Annotrail.
