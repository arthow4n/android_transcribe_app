# Agent Guidelines & Workflow Instructions

## Automatic Draft Release Workflow

After completing a feature or bug fix and pushing the commit to `origin`:

1. **Build the APK (Without Embedded Model)**:
   - Run `./build-debug-without-model.sh` to compile the native Rust code (`cargo-ndk`) and package the Android debug APK without the 463MB embedded speech model (`~25MB`).
   - The output APK is located at: `app/build/outputs/apk/debug/app-debug-no-model.apk`.

2. **Publish as GitHub Draft Release**:
   - Automatically create a GitHub **draft release** (`draft: true`) using GitHub API credentials in `~/.git-credentials`.
   - **Tag format**: `v<version>-<feature-slug>` (e.g., `v0.1.20-parakeet-unified-streaming`).
   - **Release title**: `v<version> – <feature-slug> (draft)`.
   - **Asset name**: `android_transcribe_app_v<version>-<feature-slug>-debug.apk`.
   - **Body**: Detailed bulleted changelog highlighting the new features/fixes and noting that the build excludes the bundled model (speech models can be downloaded or imported in-app).
   - Upload the APK as a release asset.

---

## Build & Toolchain Guidelines

- **Toolchain Location**: SDK, NDK (`28.0.13004108`), JDK, and Rust toolchain reside under:
  `${HOME}/.local/share/android-transcribe-toolchain`.
- **Reference Libraries**: Keep any third-party crate/source references inside `.vendor/` (git-ignored) so inspections do not prompt for out-of-workspace permissions.
- **Verification**: Always verify changes by compiling through `./build-debug-without-model.sh` before pushing.
