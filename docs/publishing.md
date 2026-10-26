# Publishing a 0.1.x maintenance release

This branch is based on the annotated `0.1.0` tag. The separate `0.2.x` line has additional
public audio-amplitude APIs; do not include those APIs in a 0.1.x patch.

1. Update `gradle/publishing.gradle.kts`, the example version name, README, QA and CHANGELOG.
2. Run `./gradlew test lint apiCheck assembleRelease :example:assembleDebug` and the connected
   preload/cache tests from `docs/qa.md`. Do not regenerate ABI files to hide a compatibility break.
3. Inspect `git diff --check`, commit only the release changes, and create an annotated version tag.
4. Push the maintenance branch and tag. Verify the remote peeled tag matches the tested commit.
5. Request the JitPack POM/AAR for each consumed artifact under
   `com.github.ibabyblue.AMedia:<artifact>:<version>` and wait for a successful build.
6. In a consuming app, inspect dependency resolution and the downloaded AAR. A pushed tag or a
   successful Maven Local build alone does not prove JitPack publication or app integration.

Never move an already published tag. Fix a publication defect in a new version. Keep all AMedia
artifacts on the same version when consuming the release.
