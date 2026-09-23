# Experimental boarding capability

`BoardingSupport` is optional and common-side. Existing `MountProvider` implementations
without it remain eligible for ordinary nearby recall when the client opts out of automatic
riding. They reject automatic riding; absence is never interpreted as a generic horse seat.

`describeBoarding(Entity, UUID)` inspects native seating eligibility and returns a
`ProviderResult<SeatEnvelope>`. It may receive the living source or an unspawned entity
restored from a Recovery payload. It must not mutate entities, load chunks, spawn entities,
post mounting events or import client-only classes. Native ownership, age, tame and saddle
rules must match the provider's supported entity type. Sitting and steering are not always
the same permission.

The immutable envelope gives minimum/maximum seat-anchor coordinates relative to the mount
origin at yaw zero. Coordinates must be finite, ordered, and within -16 through 16 blocks.
Zero extent is valid for a fixed anchor. Cover all relevant native attachment poses and body
yaw variation; do not include player width, height or the player's Y offset. Core rotates the
envelope to the candidate yaw and adds those dimensions. Unknown or unprovable geometry must
return a failure, never an invented offset. A successful result without geometry also rejects.

Core independently checks ownership, occupancy, loaded-world bounds, full border clearance,
mount placement, rider collision and water/lava/fire exposure. A provider cannot waive those
rules. Eligibility is inspected again after arrival. Core attempts non-forced native boarding
once and verifies the resulting relationship. Native mounting hooks can still veto; a late
failure leaves the successfully summoned mount and its cooldown intact. No boarding request
is replayed after disconnect or restart.

If an attachment fails verification, core first attempts normal dismount and verifies both
sides of the passenger relationship. It may then remove only the link created by that failed
attempt even if a dismount hook vetoes cleanup. This rollback exception never forces boarding
or removes established rides or unrelated passengers. Core restores a checked unmounted
position and synchronizes the result; inability to contain an unsafe rollback is a fatal
safety failure, not an ordinary summon failure. Addons must not rely on a rejected provisional
attachment being retained by a dismount veto.

Use `example.addon.BoardingAddonFixture` in test sources as a compile-only illustration of
the public import boundary. It deliberately denies all entities; it is not a functional
mount provider or proof of addon runtime compatibility. Runtime conformance must cover actual
seated position, native veto and passenger synchronization.

Recovery validates the actual unspawned instance used for each admission attempt. Preparation
is not permission to spawn: core retains its existing journal acknowledgement and source
fences. A paused attempt is discarded; a later reconstruction is checked again. Providers
must not assume that a successful earlier seat declaration authorizes a different instance.
An expired original request may allow acknowledged recovery to converge without boarding;
an unavailable context lookup does not waive a current request's rider safety.

Client preference: `config/mountcollection-client.cfg`, category `recall`, boolean
`automatic_riding`, defaults true. Restart the client after editing. There is no GUI toggle.
Both peers must support protocol revision 2, which enforces rider-aware recall. Revision 1
reserved the same boolean field without implementing that behavior and is rejected.
