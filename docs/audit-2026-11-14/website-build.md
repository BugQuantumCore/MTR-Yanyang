# Supplemental static source review: website and build configuration

Review date: 2026-11-14 (Asia/Shanghai).
Repository: MTR-Yanyang.
Source HEAD at completion: ac143aed74205f5d7b8df6d5be88097e7a045791, branch perf/1.21-render-simulation-audit. Parent-agent workflow edits were in progress and are outside this report.

## Method and result

Every file in the coverage lists below was read in full, including empty stylesheets. Large source files were read in bounded consecutive ranges; inventory/keyword scans were only used to locate files and precise line references. This is a static source review, not a runtime or visual verification. No local builds, tests, package installation, application launch or code-generation tasks were run. No repository file was modified for this supplemental review.

No newly introduced or currently demonstrated release-blocking defect was established in this supplemental scope. The issues below are pre-existing follow-up risks; they do not mean the repository is defect-free. Runtime findings cannot be dismissed by a successful compilation.

## Findings to retain for follow-up

1. **P2 — stale asynchronous editor responses can overwrite newer state.** `website/src/app/service/data.service.ts:98-108` assigns every completed response into the same DTO without revision/order checks. `update()` at lines 15-25 can additionally restore the earlier captured DTO when `skipResourceWrapperSync` is true. Overlapping form edits, previews, refreshes, uploads and sound requests can therefore restore stale state when responses complete out of order. Recommended follow-up: serialized mutating requests with server revision IDs, and deliberate response policies for commands that do not change editor resources. Do not simply cancel writes without preserving server ordering.
2. **P2 — resource-selection keys are not unique identifiers.** `edit-vehicle-model-properties.dialog.ts:50-66` formats model IDs to basenames, and `writeList()` at lines 217-236 stores those labels in a normal object. Two paths with the same basename overwrite one index; the UI can select/save the wrong model. `matchData()` at lines 244-247 also treats inherited object properties as real entries (e.g. a manually entered `constructor`). Use stable resource IDs and a `Map`, with separately displayed labels.
3. **P2 — build inputs are not reproducible.** `BuildTools.java:280` downloads moving `javagl/Obj` master. Source patches at lines 287-302 use exact string replacement without requiring a match, including a CRLF-dependent DefaultMtl replacement. An upstream format/code change can silently drop the intended material fix. Pin a reviewed commit/archive digest, validate patch matches, and fail an intentional source acquisition task when its required source is absent. Dynamic `+` dependencies and the Fabric Loom snapshot in the Gradle scripts create additional input drift. This report does not assert that a particular dependency update has broken the current build.
4. **P2 — an existing website build is accepted without checking source freshness.** Fabric lines 257-262 and NeoForge lines 264-269 return whenever `dist/website/browser/index.html` exists. Changed TS, templates or schemas can then be embedded with stale assets in an incremental CI workspace. `npm install` also permits dependency updates within declared ranges. A proper Gradle task should declare website/schema/package-lock inputs and generated output; a clean GitHub runner reduces but does not eliminate the underlying defect.
5. **P2 conditional — optional translation archive extraction is not confined to the language directory.** `BuildTools.java:103-106` directly resolves a downloaded ZIP entry name, without normalized-path containment checks. If a trusted Crowdin export ever contains an absolute or `../` entry, build-source files outside the intended directory can be overwritten. This path requires a nonempty Crowdin key; this checkout's default key is empty. No malicious archive or exploitation was observed.
6. **P2 conditional — supporter display text is emitted as unescaped Java literals.** `BuildTools.java:260` inserts names/tier titles directly into generated quoted strings. A legitimate quote, backslash or newline can break compilation when Patreon integration is enabled; crafted content could alter the generated Java source. Escape the strings with a Java-literal encoder. The default Patreon key is empty, so no current CI failure was established.
7. **P3 — editor updates copy the whole DTO and resource catalog.** `data.service.ts:18-20` JSON-serializes the entire resource wrapper before clearing the Minecraft resource arrays. Model-part form subscriptions call update on every change. Large packs can therefore cause repeated UI serialization and payload work. Construct a narrowly scoped update payload before serialization and debounce text/numeric form updates with final flush semantics.
8. **P3 — URI components are not encoded consistently.** `data.service.ts:21` and `sound.component.ts:61` interpolate IDs directly; `export()` already demonstrates proper `encodeURIComponent` usage. Resource IDs are usually restricted, but unexpected `&`, `#` or `?` characters change request interpretation.
9. **P3 — file-extension validation has inconsistent case handling.** Uploader lines 50 and 58 accept upper-case extensions but preserve their case; ManageResources lines 46-49 only recognize lower-case `obj`/`mtl`. Upper-case inputs can bypass or incorrectly fail the matching-pair check. Normalize once before validation.
10. **P3 — schema metadata contains stale/inconsistent required entries.** `schema/resource/vehicleModelWrapper.json:104` requires nonexistent property `modelProperties`; the defined property is `parts`. `vehicleResourceWrapper.json` defines `capacity` but omits it from required, unlike `vehicleResource.json`. The custom generator may tolerate these, and no generated-code compile defect was established. Align the metadata and exercise schema/generator round trips on GitHub.
11. **P3 — build-time network/generation work is not robustly bounded or isolated.** Crowdin polling loops until finished with no deadline or error-state exit; `getJson()` has retries but no explicit connect/read timeout. `setupFiles` executes mutation-heavy generation while configuring the task rather than inside an explicit task action with declared outputs, and several generated paths are shared across version projects. Current GitHub jobs isolate targets, but shared parallel/incremental builds deserve dedicated follow-up.
12. **P3 — smaller UI cleanup/performance issues.** `ManageResources.updateIdLists()` deduplicates via repeated array `includes()` (quadratic). Sound/scroll timeouts do not have explicit component-destruction cancellation. No long-lived resource leak or release blocker was demonstrated.

## Coverage exclusions

Generated TypeScript/Java outputs, build/dist/node_modules output, Gradle wrapper executable scripts/JAR, dependency lockfile internals, vendored binaries/POMs, asset images/fonts, and source templates were not exhaustively read as authored application code in this supplemental task. The author-maintained package/config files and all authored stylesheet files were read. `native/build.sh`, Rust/C++ and shader sources belong to the native agent's review; this report does not claim coverage of them. The GitHub workflows belong to the parent agent's active review. There is no authored Kotlin `.kt` file in the selected buildSrc inventory; its Kotlin DSL build file was read.

## Full-read coverage: Website authored source and configuration (59 files)

- `website/src/app/app.component.css`
- `website/src/app/app.component.html`
- `website/src/app/app.component.ts`
- `website/src/app/component/accordion/accordion.component.css`
- `website/src/app/component/accordion/accordion.component.html`
- `website/src/app/component/accordion/accordion.component.ts`
- `website/src/app/component/autocomplete/autocomplete.component.css`
- `website/src/app/component/autocomplete/autocomplete.component.html`
- `website/src/app/component/autocomplete/autocomplete.component.ts`
- `website/src/app/component/edit-vehicle-model-part/edit-vehicle-model-part.dialog.css`
- `website/src/app/component/edit-vehicle-model-part/edit-vehicle-model-part.dialog.html`
- `website/src/app/component/edit-vehicle-model-part/edit-vehicle-model-part.dialog.ts`
- `website/src/app/component/edit-vehicle-model-parts/edit-vehicle-model-parts.dialog.css`
- `website/src/app/component/edit-vehicle-model-parts/edit-vehicle-model-parts.dialog.html`
- `website/src/app/component/edit-vehicle-model-parts/edit-vehicle-model-parts.dialog.ts`
- `website/src/app/component/edit-vehicle-model-properties/edit-vehicle-model-properties.dialog.css`
- `website/src/app/component/edit-vehicle-model-properties/edit-vehicle-model-properties.dialog.html`
- `website/src/app/component/edit-vehicle-model-properties/edit-vehicle-model-properties.dialog.ts`
- `website/src/app/component/edit-vehicle-properties/edit-vehicle-properties.dialog.css`
- `website/src/app/component/edit-vehicle-properties/edit-vehicle-properties.dialog.html`
- `website/src/app/component/edit-vehicle-properties/edit-vehicle-properties.dialog.ts`
- `website/src/app/component/edit/edit.component.css`
- `website/src/app/component/edit/edit.component.html`
- `website/src/app/component/edit/edit.component.ts`
- `website/src/app/component/export/export.component.css`
- `website/src/app/component/export/export.component.html`
- `website/src/app/component/export/export.component.ts`
- `website/src/app/component/large-button/large-tile.component.css`
- `website/src/app/component/large-button/large-tile.component.html`
- `website/src/app/component/large-button/large-tile.component.ts`
- `website/src/app/component/manage-resources/manage-resources.dialog.css`
- `website/src/app/component/manage-resources/manage-resources.dialog.html`
- `website/src/app/component/manage-resources/manage-resources.dialog.ts`
- `website/src/app/component/prepare/prepare.component.css`
- `website/src/app/component/prepare/prepare.component.html`
- `website/src/app/component/prepare/prepare.component.ts`
- `website/src/app/component/prepare/reset.dialog.html`
- `website/src/app/component/preview/preview.component.css`
- `website/src/app/component/preview/preview.component.html`
- `website/src/app/component/preview/preview.component.ts`
- `website/src/app/component/sound/sound.component.css`
- `website/src/app/component/sound/sound.component.html`
- `website/src/app/component/sound/sound.component.ts`
- `website/src/app/component/uploader/uploader.component.css`
- `website/src/app/component/uploader/uploader.component.html`
- `website/src/app/component/uploader/uploader.component.ts`
- `website/src/app/pipe/formatFileNamePipe.ts`
- `website/src/app/pipe/formatIconPipe.ts`
- `website/src/app/pipe/formatStringListPipe.ts`
- `website/src/app/service/data.service.ts`
- `website/src/index.html`
- `website/src/main.ts`
- `website/src/styles.css`
- `website/src/theme.scss`
- `website/package.json`
- `website/angular.json`
- `website/tsconfig.json`
- `website/tsconfig.app.json`
- `website/tsconfig.spec.json`

## Full-read coverage: buildSrc Java/Kotlin DSL and JSON schemas (32 files)

- `buildSrc/build.gradle.kts`
- `buildSrc/src/main/java/org/mtr/BuildTools.java`
- `buildSrc/src/main/resources/schema/config/client.json`
- `buildSrc/src/main/resources/schema/config/config.json`
- `buildSrc/src/main/resources/schema/config/server.json`
- `buildSrc/src/main/resources/schema/legacy/objectResource.json`
- `buildSrc/src/main/resources/schema/legacy/railResource.json`
- `buildSrc/src/main/resources/schema/legacy/signResource.json`
- `buildSrc/src/main/resources/schema/legacy/vehicleResource.json`
- `buildSrc/src/main/resources/schema/resource/blockbenchElement.json`
- `buildSrc/src/main/resources/schema/resource/blockbenchModel.json`
- `buildSrc/src/main/resources/schema/resource/blockbenchOutline.json`
- `buildSrc/src/main/resources/schema/resource/blockbenchResolution.json`
- `buildSrc/src/main/resources/schema/resource/customResources.json`
- `buildSrc/src/main/resources/schema/resource/liftResource.json`
- `buildSrc/src/main/resources/schema/resource/minecraftModelResource.json`
- `buildSrc/src/main/resources/schema/resource/modelMirror.json`
- `buildSrc/src/main/resources/schema/resource/modelProperties.json`
- `buildSrc/src/main/resources/schema/resource/modelPropertiesPart.json`
- `buildSrc/src/main/resources/schema/resource/modelPropertiesPartWrapper.json`
- `buildSrc/src/main/resources/schema/resource/modelWrapper.json`
- `buildSrc/src/main/resources/schema/resource/objectResource.json`
- `buildSrc/src/main/resources/schema/resource/partPosition.json`
- `buildSrc/src/main/resources/schema/resource/positionDefinition.json`
- `buildSrc/src/main/resources/schema/resource/positionDefinitions.json`
- `buildSrc/src/main/resources/schema/resource/railResource.json`
- `buildSrc/src/main/resources/schema/resource/resourceWrapper.json`
- `buildSrc/src/main/resources/schema/resource/signResource.json`
- `buildSrc/src/main/resources/schema/resource/vehicleModel.json`
- `buildSrc/src/main/resources/schema/resource/vehicleModelWrapper.json`
- `buildSrc/src/main/resources/schema/resource/vehicleResource.json`
- `buildSrc/src/main/resources/schema/resource/vehicleResourceWrapper.json`

## Full-read coverage: Root/versions build configuration (10 files)

- `build-fabric.gradle.kts`
- `build-neoforge.gradle.kts`
- `settings.gradle.kts`
- `stonecutter.gradle.kts`
- `gradle.properties`
- `gradle/wrapper/gradle-wrapper.properties`
- `versions/1.21.1-fabric/gradle.properties`
- `versions/1.21.1-neoforge/gradle.properties`
- `versions/1.21.4-fabric/gradle.properties`
- `versions/1.21.4-neoforge/gradle.properties`

