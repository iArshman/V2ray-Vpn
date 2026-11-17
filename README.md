# Hollow VPN

Hollow VPN is an Android VPN client built with Kotlin and Jetpack Compose, using the Xray core for tunnel connections.

## Build locally

1. Install Android Studio with Android SDK 36 and JDK 21.
2. Copy the environment template:

   ```bash
   cp .env.example .env
   ```

3. Build or test with the Gradle wrapper:

   ```bash
   ./gradlew :app:assembleDebug
   ./gradlew :app:testDebugUnitTest
   ```

The native `libv2ray.aar` dependency is downloaded automatically when it is not present. It is intentionally ignored by Git and cached by GitHub Actions.

## GitHub Actions

- **Android CI** runs on pull requests and can also be started manually. It runs unit tests and uploads a debug APK as a workflow artifact.
- **Build and Release APKs** runs on pushes to `main` or `master`, version tags beginning with `v`, and manual dispatches. It builds split release APKs, uploads workflow artifacts, creates a GitHub Release, and publishes SHA-256 checksums.

Release builds use the configured repository secrets when available:

- `RELEASE_KEYSTORE_BASE64` or `KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD` or `STORE_PASSWORD`
- `RELEASE_KEY_ALIAS` or `KEY_ALIAS`
- `RELEASE_KEY_PASSWORD` or `KEY_PASSWORD`

If these secrets are not configured, the workflow produces APKs signed with a temporary debug keystore so that the release pipeline remains testable. Configure a real release keystore before distributing production updates.

## License

See the repository history and project metadata for licensing information.
