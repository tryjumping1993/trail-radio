# Trail Radio

A small push-to-talk group radio for Android and the web. The host keeps the channel open and grants it to one speaker at a time. Audio is sent to group members whose latest location is within about 1,500 feet of the speaker. Everyone needs an internet connection, microphone and location permissions. The host must remain online; GPS and peer-to-peer connectivity can vary by device and network. This is not an emergency communications tool.

## Android

Install `Trail-Radio.apk` from a GitHub release. Android invitations use a `trailradio://join` link and open on devices with Trail Radio installed. The app uses the bundled web files; the radio still depends on the external PeerJS signaling service, and some networks may block peer-to-peer calls.

The APK is signed with a dedicated key stored **only on this PC** under `Z:\trail-radio-runner\signing`. Back it up securely: losing it prevents future APKs from updating existing installations. The key and password are not stored in this repository. The password file is encrypted for this Windows user on this PC; before replacing the PC, export the password securely while signed in as the same user and back it up separately from the keystore.

To build a signed release on the configured runner, the environment must contain `TRAIL_KEYSTORE`, `TRAIL_STORE_PASSWORD`, `TRAIL_KEY_PASSWORD`, and optionally `TRAIL_KEY_ALIAS`, plus `ANDROID_HOME` and `JAVA_HOME`. Run `android\gradlew.bat assembleRelease` from `android`. For a local debug build, run `android\gradlew.bat assembleDebug`.

## Releases

Push a tag such as `v1.0.0` to GitHub. The Windows runner labeled `trail-radio` builds the signed APK, creates a GitHub release if needed, and attaches the APK and its SHA-256 checksum. The workstation must be on and its scheduled runner task active. Workflow run numbers provide increasing Android version codes; releases should be tagged in the order they are published.

## Web

Serve `dist/` over HTTPS or localhost. Microphone and location require a secure browser context. Web invite links point to the site serving the page. The original hosted Site is private, so visitors need access to it separately from a group invitation.
