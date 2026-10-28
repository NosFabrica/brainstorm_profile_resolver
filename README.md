# brainstorm_profile_resolver

A nightly job that reads nostr profiles (kind 0) from our search relay and resolves what they
point to off-nostr, writing the answers into Brainstorm's graph:

- **Lightning (NIP-57).** A profile's `lud16` (else `lud06`) leads to an LNURL-pay endpoint whose
  `nostrPubkey` signs that user's zap receipts. The user chose that signer, so GrapeRank treats it
  like a kind-10040 designation: a `ZAPS_VIA` edge every Observer's run reads, rated 0.95; an
  Observer's own signer is pinned at rank 95.
- **NIP-05.** A profile's `nip05` is checked against the domain's `/.well-known/nostr.json`; the
  verdict is stored on the user's node. GrapeRank uses it only against the user: someone claiming a
  name the domain gives to another key is scored zero, and a claim the domain does not back is
  halved (brainstorm_server ADR 0006). A verified NIP-05, or none, changes nothing.

It touches only users already in the graph, and looks up only what changed.

## What it writes

The other Brainstorm services depend on these names, so they are a contract.

**Neo4j**, on `(:NostrUser {pubkey})` nodes:

| Property / edge | Meaning |
|---|---|
| `lnurl` | The LNURL-pay URL the user's lightning address resolves to. |
| `lnurl_status` | `OK`, `NO_ZAPS`, `NOT_FOUND` (404 or 410), `INVALID` (never fetched: a refused URL); or, retried each run, `PENDING` (the domain was down, or a 5xx) and `UNKNOWN` (a 403, a redirect or another 4xx — usually a firewall, not the user's doing). |
| `-[:ZAPS_VIA]->(:NostrUser)` | The key that signs the user's zap receipts. Present only for `OK`, and never to the user's own key. |
| `nip05` | The user's NIP-05 identifier, lowercased. |
| `nip05_status` | `VERIFIED`; `SUSPECTED` (the domain lists the name under another key, seen once — re-checked at least 12 h later); `IMPERSONATION` (the same on two checks in a row); `NOT_LISTED` (the domain answered without the name, or a 404 or 410); `UNKNOWN` (a 403, redirect, unreadable answer, a non-key in `names`, or a refused URL — not the user's doing); or `PENDING` (domain down, or a 5xx). The GrapeRank worker zeroes `IMPERSONATION` and halves `NOT_LISTED` (brainstorm_server ADR 0006). |
| `nip05_checked_at` | Epoch seconds of the last check. |

**Redis:**

| Key | Meaning |
|---|---|
| `zaps_via:<pubkey>` | SET holding the user's signer. The forward set the GrapeRank worker reads, beside `designates:<pubkey>`. |
| `lnurl:<sha256(url)>` | HASH caching an address's answer (`status`, `signer`, `checked_at`, `url`). |
| `resolver_domain:<domain>` | HASH of a failing domain's `failures` and `retry_at`. |
| `profile_resolver:cursor` | Epoch seconds the last complete pass started. |
| `profile_resolver:full_pass` | HASH of an unfinished full pass (`started_at`, `after`): the next run resumes it. |
| `profile_resolver:last_full_pass` | Epoch seconds the last finished full pass started. |

## How a run goes

1. **Profiles.** A full pass reads every graph user's profile by author, 500 per request: on the
   first run, with `RESOLVER_MODE=FULL`, and weekly (`FULL_PASS_EVERY_DAYS`), since a user who
   joins the graph with an old profile shows up in no incremental walk. A full pass that outlives
   its run resumes where it stopped. Other runs read only profiles changed since the cursor (minus
   an hour of overlap), paged newest-first with Quartz's `fetchAllPages`, newest per author kept. Reads are unranked
   (`include:spam`) — lookups, not searches. No signature checks: we run the relay and it verifies
   what it stores. The cursor advances only when the relay drained the window.
2. **Lookups.** For each graph user whose address or NIP-05 changed:
   - **Each lightning address is fetched once.** Users sharing an address share one request and
     one cached answer; an address is fetched again only when a user's address changes.
   - **A domain that is down is left alone.** A connection failure, timeout, TLS error, 429 or 408
     backs the whole domain off — 1 h, 6 h, 1 day, 3 days, then weekly — and its addresses stay
     `PENDING`. A 5xx leaves only that address `PENDING`: a profile anyone writes can point at a
     path that fails, and that must not back off the domain for everyone on it. A 404 means the
     server is up; only that address is `NOT_FOUND`.
   - **At most one request per domain per 500 ms**, and 16 in flight overall.
   - **A name listed under another key is only `SUSPECTED` at first**, and becomes `IMPERSONATION` when a
     check at least 12 hours later agrees. A domain that names someone else for at least 10 of a run's
     checks, and at least half of them, is doubted instead (`UNKNOWN`, including suspicions written earlier
     in the run): that is a broken domain, not a crowd of impersonators.
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

Addresses come from profiles anyone can write, so every GET is fenced: https on port 443 only, no
redirects, a 64 KB body cap, and hosts that resolve only to public addresses — an IP-literal host
is checked directly, since OkHttp never asks the DNS about one. OkHttp connects to exactly
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
| `FULL_PASS_EVERY_DAYS` | `7` | `0` leaves full passes to the first run and `FULL` mode. |

CI builds `ghcr.io/nosfabrica/brainstorm_profile_resolver` on every push (branch, `sha-<short>`,
and `latest` on `main`). It runs as a nightly CronJob in
[brainstorm-k8s](https://github.com/NosFabrica/brainstorm-k8s).
