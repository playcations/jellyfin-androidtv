# Familyfin Native prototype

Based on official jellyfin-androidtv `6d880042a67db9845f3e8fc2d0cba8f80b28e6ae`.
Original namespace and licensing are retained. Application ID is
`com.playcations.familyfin.nativeclient` (`.debug` for the debug APK), version
`0.1.0-familyfin-native.1` / `100001`.

This fork adds a generic native host for the shared ClientExtensions v1 contract.
A provider can publish entry points, home rows, paged grid/category screens and
four typed actions without client changes. The native code never branches on a
provider ID. Media cards, details and playback use existing Jellyfin components.
Unknown actions/versions and unavailable providers fail locally. An absent bridge
leaves stock home/library/Live TV navigation available. Existing home-section
preferences are unchanged; extensions do not insert disabled Live TV or On Now.

All bridge requests use the active viewer's standard Jellyfin authorization
header, the connected server and fixed validated paths. Redirects are refused and
responses are bounded. Discovery, screen and scope data have no repository cache;
in-flight responses are rejected after server/viewer/token changes. Untrusted
`imageUrl` is deliberately ignored; normal SDK media artwork is supported.

Scoped native guides resolve the current authorized scope before navigation,
channel loading, program requests, refresh and periodic visible-scope checks.
The standard `/LiveTv/Channels` list endpoint has no ID filter, so scoped guides
fetch `/LiveTv/Channels/{id}` for precisely the authorized UUIDs (bounded parallel
batches), then query programs only for the displayed authorized IDs. Empty,
denied, missing or changed scopes never fall back to the all-channel endpoint.
Each guide owns its channel/program state and refresh generation. Legacy playback
caches are isolated by server/viewer/token and erased on logout/user changes.
One guide route is pushed from the selected category; Back restores that
category's selection and loaded-page count. Remote revocation of a visible screen
or scope is checked every 15 seconds and on every new request.

## Build and tests

```sh
JAVA_HOME=/home/mrpeyvan/jdks/jdk-21.0.12.1+1 \
ANDROID_HOME=/home/mrpeyvan/Android/Sdk \
./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain
```

`dev/extension_fixture.py` supplies three independent providers, paged media,
nested categories, disjoint scopes and absent/denied/removed/version/malformed
modes. JVM tests start their own loopback instance and clean it up. Exact shared
real-server snapshots are preserved in `dev/server-snapshots` and parsed by the
same client decoder. `RealBridgeTests` additionally exercises the real production
repository against a disposable Jellyfin when `FAMILYFIN_NATIVE_REAL_SESSION`
points to its private synthetic-session file. This file is never bundled.

For a real synthetic server, `dev/run_real_bridge.py --hold` uses the sibling
server checkout and writes only native-owned runtime evidence/fixtures. It
cleans up its randomly named container and credentials on SIGINT/SIGTERM.

The debug-only `ExtensionFixtureActivity` is restricted to emulator/loopback
server hosts and is absent from release builds. It exercises the actual native
renderer, action dispatcher, navigation host and guide, without household-device
access or stock login prerequisites. It defaults to `http://10.0.2.2:18096`.
`dev/configure_emulator_session.py <private-session-file>` writes a real synthetic
session directly into the debuggable emulator app's private storage, without
printing or putting tokens in intent arguments. Stop the app and remove that
private file to return to the local three-provider fixture.

## Prototype limits

No APK installation on a physical device, household service deployment, app-store
publication or production playback acceptance is implied. D-pad/focus/Back and
emulator results are recorded separately in the native artifact report; unit
navigation-callback checks alone are not a visual UI test. Live TV playback
fixtures intentionally have invalid stream URLs. Provider-supplied custom artwork
is unsupported, and category labels use a basic native focusable-button treatment.
A scope change closes the guide with an unavailable message; Back/reopen fetches
the new scope rather than silently changing the current channel selection.
