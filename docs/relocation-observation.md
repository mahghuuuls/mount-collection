# Development relocation observation

This read-only diagnostic is unavailable outside a deobfuscated development runtime. It does not change protocol, pose, passengers, settings or saves. It is not a player feature.

On each client being observed, run `/mcrelocationtrace <physical-entity-uuid>` immediately before the action. This is a **client-local command**, not a server/toolkit bundle command. It arms one matching packet for at most45 wall-clock seconds; `/mcrelocationtrace clear` cancels it. Default state is off. A new challenge, disconnect, replaced connection/world/player, expiry, lost entity or second matching packet ends observation. There is no automatic rearm.

Client records in `latest.log` use `[MountCollection][RELOCATION_TRACE]`:

- `BEFORE`: native state immediately before admission/application.
- `APPLIED`: native state read immediately after application, not copied from the packet.
- `REJECTED_VIEW`, `REJECTED_ENTITY`, `APPLICATION_FAILED`: the receiver did not complete application.
- `END_SAMPLE_1` through12, then40 and160: actual native state at bounded client tick-END callbacks. The first callback can be in the application tick; indices count callbacks, not elapsed server ticks. `worldTick` is included but client/server clocks are not assumed interchangeable.
- `SUPERSEDED` / `ENTITY_LOST`: later data cannot be attributed to the first application; the capture stops.

A complete normal capture contains16 records. Early expiry/lifetime changes can end it sooner: an incomplete capture must not be called a delayed-stability pass. Native current, previous and render positions, motion, encoded baseline, bounding box and links are recorded. Passenger UUID lists are capped at four and marked `more` when truncated. There is no private-field reflection or interception of vanilla packets. Tick-boundary samples are not proof of every render frame or every intermediate native operation.

Server `SEND_ATTEMPT` records require the existing detailed diagnostics setting and a deobfuscated runtime. They are capped at64 per network instance/process lifetime, including all recipient deliveries. They identify recipient, session, sequence, entity and native pair state before the write attempt; they do **not** establish successful delivery. Server snapshots do not replace client observations. Budget exhaustion requires a later planned restart for further server evidence; toggling the setting does not reset it.

Correlate exact session/sequence/entity/dimension with client records, server lifecycle records, independent toolkit native inspections and owner visual observations. No response packet or diagnostic acknowledgement is sent. A silent observer may be unarmed, expired, out of budget, in the wrong view or missing the entity; absence alone is not a success verdict. Log/snapshot failures stop the affected diagnostic without changing packet delivery or application. Release defaults and gameplay settings are unchanged.
