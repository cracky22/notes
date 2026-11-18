# Notizen Android

Native Android client in Kotlin + Jetpack Compose for the supplied FastAPI Notizen backend.

## Included

- Login via `POST /api/login`; the backend's `notizen_session` cookie is persisted encrypted.
- Notes pagination, note creation/editing, checklist items, full-text search, pin/archive/trash/restore/delete actions.
- Text edits use the backend's WebSocket Operational Transformation endpoint (`/ws`) rather than writing note payloads directly.
- Local read cache in Room; each cached note payload is encrypted with an AES-GCM key held by Android Keystore.
- The account's `cache_generation` is checked after authentication. If the server/admin increments it, this app clears that user's local note cache, matching the backend reset mechanism.
- Haptic taps and motion transitions on navigation, note cards, checklists, and QR confirmation.
- Camera permission is requested only when the signed-in user starts scanning; QR frames are processed on-device and not stored. The ML Kit QR scanner reads the browser login URL and asks for an explicit in-app confirmation before calling `POST /api/device-login/approve`.
- Server base URLs may include a reverse-proxy prefix, for example `https://notes.example.org/notizen/`.

## Build and run

1. Use JDK 17, Android SDK 35, and Gradle 8.9 or newer. Android Gradle Plugin 8.7.3 is pinned in the project.
2. If your Android Studio requires a wrapper, run `gradle wrapper --gradle-version 8.9` once from the project root, then open the folder in Android Studio.
3. Let Gradle resolve the pinned dependencies, then run the `app` configuration on an emulator or device.
4. The debug build starts with `http://10.0.2.2:8000/` for a backend running on the development computer. For a device on a LAN, enter the server's reachable IP and port in the login screen.
5. For production, use HTTPS. The release manifest disallows cleartext HTTP; only the debug variant permits it for local development.

The backend must be reachable at the entered base URL, with `/api/` and `/ws` available under the same path prefix. Create users with the backend's admin CLI before signing in.

## Mobile scope and limitations

This is a native companion, not a full port of every web feature. It supports plain text and checklist editing, but does not currently edit rich-text formatting, images, labels, collaborators, versions, or profile settings. Offline mode is cached, read-only access; new edits require a live server connection. QR approval requires an already signed-in app session; the user must explicitly confirm each browser login. Formatting is preserved if untouched, but changing the body in this simple editor replaces that body with plain text. As with any cross-device note app, avoid storing patient-identifiable or other sensitive data unless the deployment and device are approved for it.

## Backend compatibility

The client sends `X-Requested-With: notizen` on mutating REST requests, uses the backend's cookie authentication, and writes document changes through the server's `{t: "op", id, rev, cid, change}` protocol. It does not modify the backend or database schema.
