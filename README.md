# brainstorm_profile_resolver

A nightly job that reads nostr profiles (kind 0) from our search relay and resolves what they
point to off-nostr, writing the answers into Brainstorm's graph:

- **Lightning (NIP-57).** A profile's `lud16` (else `lud06`) leads to an LNURL-pay endpoint whose
  `nostrPubkey` signs that user's zap receipts. The user chose that signer, so GrapeRank treats it
  like a kind-10040 designation: a `ZAPS_VIA` edge every Observer's run reads, rated 0.95; an
  Observer's own signer is pinned at rank 95.
- **NIP-05.** A profile's `nip05` is checked against the domain's `/.well-known/nostr.json`; the
  verdict is stored on the user's node.

It touches only users already in the graph, and looks up only what changed.

## What it writes

The other Brainstorm services depend on these names, so they are a contract.

**Neo4j**, on `(:NostrUser {pubkey})` nodes:

| Property / edge | Meaning |
|---|---|
| `lnurl` | The LNURL-pay URL the user's lightning address resolves to. |
| `lnurl_status` | `OK`, `NO_ZAPS`, `NOT_FOUND`, `INVALID`, or `PENDING` (the domain was down; retried). |
| `-[:ZAPS_VIA]->(:NostrUser)` | The key that signs the user's zap receipts. Present only for `OK`, and never to the user's own key. |
| `nip05` | The user's NIP-05 identifier, lowercased. |
| `nip05_status` | `VERIFIED`, `MISMATCH`, `NOT_FOUND`, `INVALID`, or `PENDING`. |
| `nip05_checked_at` | Epoch seconds of the last check. |

**Redis:**

| Key | Meaning |
|---|---|
| `zaps_via:<pubkey>` | SET holding the user's signer. The forward set the GrapeRank worker reads, beside `designates:<pubkey>`. |
| `lnurl:<sha256(url)>` | HASH caching an address's answer (`status`, `signer`, `checked_at`, `url`). |
| `resolver_domain:<domain>` | HASH of a failing domain's `failures` and `retry_at`. |
| `profile_resolver:cursor` | Epoch seconds the last complete incremental pass started. |

## How a run goes

1. **Profiles.** The first run (or `RESOLVER_MODE=FULL`) reads every graph user's profile by
   author, 500 per request. Later runs read only profiles changed since the cursor (minus an
   hour of overlap), paged newest-first with Quartz's `fetchAllPages`. Reads are unranked
   (`include:spam`) — lookups, not searches. No signature checks: we run the relay and it verifies
   what it stores. The cursor advances only when the relay drained the window.
2. **Lookups.** For each graph user whose address or NIP-05 changed:
   - **Each lightning address is fetched once.** Users sharing an address share one request and
     one cached answer; an address is fetched again only when a user's address changes.
   - **A domain that is down is left alone.** A connection failure, timeout, TLS error, 5xx or 429
     backs the whole domain off — 1 h, 6 h, 1 day, 3 days, then weekly — and its addresses stay
     `PENDING`. A 404 means the server is up; only that address is `NOT_FOUND`.
   - **At most one request per domain per 500 ms**, and 16 in flight overall.
   - **NIP-05s are re-checked monthly**: domains add and drop names. Each name is checked with
     `?name=`, the form every domain we surveyed answers. A domain with 20 or more names due in a
     batch is first asked once per run for its whole list (the bare `nostr.json`, up to 2 MB), which
     then answers the names it holds. Few domains serve a usable one — of 40 surveyed (2026-10-27),
     the biggest (primal.net, fountain.fm) return an empty list, some refuse it, coinos.io's is
     partial, and the mostr bridges' run to megabytes — so a name missing from a list is always
     asked for itself, and an empty, refused or oversized list costs one request and changes nothing.
3. **Retries.** `PENDING` addresses and NIP-05s due a re-check are looked up again.

Writes go to the worker's Redis set first, then Neo4j: the graph is what marks a user settled, so
a crash between the two leaves the user unsettled and the next run writes both again.

## The outbound fetch

Addresses come from profiles anyone can write, so every GET is fenced: https only, no redirects,
a 64 KB body cap, and hosts that resolve only to public addresses. OkHttp connects to exactly
the addresses the fence approved, so a host cannot pass the check and then rebind to an internal
address. The client never uses a proxy, which would bypass that check.

## Running

```sh
./gradlew test
./gradlew run   # needs the environment below
```

| Variable | Default | |
|---|---|---|
| `NEO4J_URL`, `NEO4J_USERNAME`, `NEO4J_PASSWORD` | — | Same names as the GrapeRank worker. |
| `REDIS_HOST`, `REDIS_PORT` | — | |
| `SEARCH_RELAY_URL` | `wss://search.brainstorm.world` | |
| `RESOLVER_MODE` | `INCREMENTAL` | `FULL` re-reads every graph user's profile. |
| `NIP05_RECHECK_DAYS` | `30` | |
| `PER_DOMAIN_INTERVAL_MS` | `500` | |
| `MAX_CONCURRENT_LOOKUPS` | `16` | |

CI builds `ghcr.io/nosfabrica/brainstorm_profile_resolver` on every push (branch, `sha-<short>`,
and `latest` on `main`). It runs as a nightly CronJob in
[brainstorm-k8s](https://github.com/NosFabrica/brainstorm-k8s).
