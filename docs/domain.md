# Proposed domain model

This is a design for review, not an existing schema. Add executable migrations
and constraint tests before implementing each feature. Shared saved status and
the destructive content lifecycle were approved on 2026-10-25; exact onboarding
and pairing mechanics are described in [the roadmap](roadmap.md).

## Entities

Use UUID primary keys, `timestamptz` UTC timestamps, explicit `NOT NULL` rules,
foreign keys, and checked lifecycle values. Foreign keys default to restricted
deletion while tombstones are needed; cleanup purges dependent rows in an
explicit order after external deletion succeeds. Do not derive authorization
from UUID unpredictability. `created_at`/`updated_at` are explicit where
mutable.

| Entity            | Important columns and constraints                                                                                                                                              |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| User (`app_user`) | UUID; unique `(auth_issuer, auth_subject)`; display name; optional private avatar media reference; notification/widget preferences; active/deletion-pending status; timestamps |
| Couple            | UUID; `ACTIVE` or `CLOSED`; created/closed timestamps; monotonic access version                                                                                                |
| CoupleMembership  | Couple/user FKs; slot 1 or 2; joined/left timestamps; unique `(couple_id, user_id)` and `(couple_id, slot)`; partial unique user index for membership with no `left_at`        |
| CoupleInvitation  | UUID; inviter FK; unique code digest; optional claimed-by FK; pending/claimed/accepted/revoked/expired state; expiry, claim, acceptance timestamps; accepted couple FK         |
| MediaAsset        | UUID; uploader FK; unique internal object key; detected type, byte size, dimensions, checksum; pending/ready/deletion-pending/deleted state; expiry for unfinished uploads     |
| ContentItem       | UUID; couple and sender FKs; photo/drawing type; unique media FK; shared `saved_at` and optional `saved_by`; version; created/deleted timestamps                               |
| DeviceToken       | UUID; user FK; installation ID; unique token digest; protected token value; enabled/revoked timestamps and last registration; user may have several installations              |
| GameMatch         | UUID; couple FK; game type; inviter/next-player references; invited/active/completed/cancelled status; authoritative state; version; result; timestamps                        |
| GameParticipant   | Match and user FKs; role/seat; unique `(match_id, user_id)` and `(match_id, seat)`                                                                                             |
| GameMove          | UUID; match and actor FKs; sequence; action; expected/resulting versions; unique `(match_id, sequence)`; timestamp                                                             |
| IdempotencyRecord | User FK, operation, key; request digest; original resource ID/status; unique `(user_id, operation, key)`                                                                       |
| OutboxEvent       | UUID; type; resource ID/version; recipient; deduplication key; pending/processing/delivered/failed state; attempts, next attempt, lease, created/delivered timestamps          |
| CleanupJob        | UUID; resource/object reference; delete-media/delete-identity type; retry/lease fields; unique operation/resource pair                                                         |

User settings may start as explicit columns on `app_user`; separate tables are
not needed without a query/lifecycle reason. Media metadata stays separate to
represent unfinished uploads. Cleanup and outbox rows contain only minimal
internal IDs; never copy media or guesses into delivery payloads.

## Pairing integrity

An active couple exists only after both people consent. A pending invitation is
not a one-member couple. Approved guest flow: one user creates a code, another
claims and explicitly accepts it, and the creator confirms that claimant. No
content access is granted during a claim.

Acceptance locks the invitation and both user rows in a stable order, rechecks
expiry, consent, account status, and absence of active memberships, then creates
the couple and both memberships atomically. Repeated confirmation by the same
actor/key returns the same result; another claimant cannot reuse the invitation.
Self-pairing, expired/revoked claims, and a third member are rejected.

Database enforcement must include:

- `CHECK (slot IN (1, 2))`, slot uniqueness, and the partial unique active-user
  membership index. These enforce at most two people and at most one active
  couple per user.
- A deferred constraint trigger on couple and membership changes verifies
  exactly two current members for every active couple and zero current members
  for a closed couple at commit. Lock the parent couple row before membership
  changes to serialize concurrent updates; check both old and new parents on
  reassignment.
- Checks tying accepted invitation state to the accepted couple and timestamps,
  and prohibiting inviter = claimant. Couple closure revokes pending invitations
  involving its users. Invitation expiry is checked against server time within
  the acceptance transaction, not a time-dependent `CHECK` constraint.

A row-level `CHECK` cannot count other rows safely. PostgreSQL provides
[constraints](https://www.postgresql.org/docs/current/ddl-constraints.html) and
[deferred constraint triggers](https://www.postgresql.org/docs/current/sql-createtrigger.html)
for these distinct jobs. Test the raw SQL constraints as well as service
behavior.

## Content and memories

The approved memory model is a shared flag: `saved_at IS NOT NULL` means both
partners see the item as saved. Either member may save or unsave; the media is
stored once. History includes every non-deleted sent item; the saved gallery
filters the same rows. Unsave never deletes exchange history.

Use a composite FK `(couple_id, sender_id)` to membership, so a sender belongs
to the same historical couple. A DB trigger on submission/save changes rejects a
closed couple, inactive sender, or non-ready media; couple closure is serialized
with submission. `saved_by` must also reference that couple's membership. Only
the server sets sender/couple IDs after authorization. The uploader must match
sender, and ready media is immutable and can attach to only one content item.

Deleting content records a tombstone and a cleanup job in the same transaction.
Read predicates exclude tombstones and closed couples immediately. New content
has no automatic expiry. Unfinished upload expiry is housekeeping, not
expiration of exchanged content.

## Games and concurrency

Match participants must be the couple's two members at creation, checked under
the couple lock; composite membership FKs and a deferred participant-count
constraint enforce the relationship. `(match_id, actor_id)` on moves references
participants. The server transaction locks the match, checks active membership,
status, turn, rules, and expected version, then writes the move, result, and
outbox event.

Use unique move sequence and idempotency constraints to prevent duplicates. Two
requests based on one version cannot both advance the game. Database constraints
protect structure; game-specific legality is checked by authoritative Go rules
inside that transaction, never by trusting client board state.

- **Draw & Guess:** invited → active drawing → awaiting guess → completed;
  invitation decline, participant cancellation, or unpairing → cancelled. Store
  private prompt/drawing/guess state; never return the prompt to the guesser.
- **Tic-tac-toe:** invited → active → completed (win/draw);
  decline/cancel/unpair → cancelled. Network disconnect alone does not cancel. A
  versioned snapshot recovers state after reconnect. Turn/guess limits and
  cancellation UX need contracts in their game issues before implementation.

## Indexes and migration verification

Index referencing FKs used for lookup/deletion explicitly. Add chronological
`(couple_id, created_at DESC, id DESC)` indexes for non-deleted content, a
partial saved-content index when gallery queries exist, membership lookups,
unique invite digests, `(match_id, sequence)`, and pending-job
`(next_attempt_at, id)` indexes. Use a least-privilege application DB role;
migrations use a separate role.

Each schema PR must migrate an empty PostgreSQL database and an existing fixture
forward, test direct constraint violations and concurrent transactions, and
verify rollback on failure. Do not substitute SQLite or in-memory maps for
PostgreSQL integrity/concurrency tests. Never edit an applied migration to
change history.
