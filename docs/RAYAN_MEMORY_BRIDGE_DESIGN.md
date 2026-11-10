# Rayan Memory Bridge — implementation design

Status: design approved for implementation; no bridge code has been added yet.
Target branch: feature/rayan-archive-staging-2026-11-10
Main branch must remain untouched.

## Goal
Connect PersonalAI2 on Android to the canonical Rayan memory in Termux (PersonalAI_Memory) so the app can search, read, and append approved memories. The Android encrypted screen-memory archive and conversation archive remain separate stores; they must not be silently treated as the canonical Termux memory.

## Current verified code facts
- MainActivity sends model requests to http://127.0.0.1:8000/ (llama-server).
- RayanMemoryContext reads only RayanScreenMemoryRepository on the Android app's private files.
- RayanScreenMemoryRepository reads rayan_screen_memory_v1.enc, encrypted with Android Keystore.
- RayanConversationArchive stores app conversation messages in a separate encrypted archive.
- RayanScreenMemoryService captures visible text only from the ChatGPT package after the user enables Android Accessibility. This is not a connection to PersonalAI_Memory.
- The existing Rayan-Termux service has a local health endpoint at 127.0.0.1:8080, but the current app source does not call it.
- Rayan-Termux MCP session was unavailable during the latest inspection, so its server routes and memory implementation must be inspected before coding.

## Proposed architecture
1. Keep llama-server on 127.0.0.1:8000 exclusively for model inference.
2. Add a dedicated, narrowly scoped Memory Bridge API to the Termux service on 127.0.0.1:8080. Do not expose a general shell or arbitrary file API.
3. Initial API operations:
   - GET /health: bridge readiness and protocol version only.
   - POST /v1/memory/search: bounded query; returns a small ranked set of memory IDs, categories, timestamps, and excerpts.
   - POST /v1/memory/read: reads only explicitly returned memory IDs.
   - POST /v1/memory/append: appends one user-approved memory record with category and source metadata.
4. Reuse the existing PersonalAI_Memory data layer; do not create a second canonical memory database or write directly to memory.json from Android.
5. Android app uses a dedicated RayanMemoryBridgeClient. Search relevant memory before each model request, add only bounded excerpts to context, and offer a clear indication when retrieval fails. Never silently pretend memory was retrieved.
6. Memory writes require explicit user action/consent in the app. No automatic capture-to-canonical-memory writes.
7. Keep the existing encrypted Android screen-memory and conversation archive as independent local stores until a separately reviewed migration/sync policy exists.

## Security requirements
- Bind only to 127.0.0.1; no 0.0.0.0 exposure.
- Authenticate app requests with a high-entropy bridge token stored in Termux private config (0600) and Android Keystore-backed storage. Never hardcode or log the token.
- Require POST for all operations that carry content; enforce JSON content type, payload/query limits, timeouts, and rate limits.
- Validate operation, category, IDs, text length, and UTF-8 input. Reject path traversal and arbitrary paths.
- Do not return passwords, secrets, tokens, or private configuration.
- Audit operation metadata without logging memory contents or credentials.
- If authentication or retrieval fails, show a visible status and continue only under a defined no-memory fallback policy.

## Test plan
- Unit tests for query validation, authorization, ID validation, payload bounds, and serialization.
- Integration tests against a temporary PersonalAI_Memory fixture: search -> read -> append -> search.
- Negative tests: missing/wrong token, malformed JSON, oversized payload, unknown ID, invalid category, unavailable memory file, corrupted record.
- Android tests with mocked bridge responses: relevant memory included, no result handled, timeout/auth failure shown clearly, bounded context enforced.
- Device acceptance test only when available: same phone, app + Termux bridge, write a consented test memory, restart app and Termux, retrieve the same record.
- Do not claim persistence or end-to-end integration until the device acceptance test passes.

## Delivery sequence
A. Restore Rayan-Termux connectivity and verify current backup before any server edits.
B. Inspect actual MCP server routing and PersonalAI_Memory schema; reconcile this design with the existing implementation.
C. Implement the smallest authenticated bridge and tests in Termux.
D. Implement Android client and visible retrieval status.
E. Build APK through GitHub Actions; verify artifact metadata.
F. Run device acceptance test only when explicitly ready. No APK installation is required merely to develop the bridge.

## Reusable app build/test monitor
Keep APK_BUILD_MONITOR_TOOL_SPEC.md as the generic monitor design. It must accept repository, workflow, branch/ref, optional run/commit ID, expected artifact name, polling cadence, and stop conditions. Read-only by default; verify jobs and artifact metadata; report API outages as unknown; never dispatch, rerun, merge, edit source, or modify main without explicit authorization.
