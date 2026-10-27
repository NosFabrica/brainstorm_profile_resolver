# brainstorm_profile_resolver

Nightly Kotlin job: kind-0 profiles from our search relay → lightning zap signers (NIP-57) and
NIP-05 verification → Neo4j + Redis. Read [README.md](README.md) first; its "What it writes"
tables are a contract with `brainstorm_server` and `brainstorm_graperank_algorithm`.

## Map

| Path | What |
|---|---|
| `Main.kt` | Wires the stores, the relay client and the resolvers; one run, then exit. |
| `ResolverJob.kt` | One run: full or incremental profile pass, then retries. Owns the cursor. |
| `ProfileResolver.kt` | Per batch: skip the unchanged, resolve the rest, write. |
| `profiles/` | `ProfileFields` (lud16/lud06/nip05 from a kind-0) and `SearchRelayProfiles` (Quartz `fetchAllPages`). |
| `http/FencedHttp.kt` | The only outbound GET to user-supplied hosts. Classifies every outcome. |
| `http/DomainGate.kt` | Per-domain spacing, global concurrency, and backoff for domains that are down. |
| `lightning/`, `nip05/` | The two lookups. |
| `store/` | `KeyValueStore` (Redis) and `GraphStore` (Neo4j) and their contracts. |

## Rules

- **Every request to a user-supplied host goes through `FencedHttp` and `DomainGate`.** Never a
  bare OkHttp call: the fence is the SSRF protection and the gate is what keeps us polite.
- **Only touch users already in the graph.** Profile authors outside it are counted and skipped.
- **A lightning answer is fetched once.** Don't add a periodic refresh of settled addresses
  without measuring that signers actually rotate.
- **`PENDING` is never cached** in `lnurl:` — it means "ask again later", not an answer.
- **Changing a Neo4j property, edge type or Redis key name is a cross-repo change.** The GrapeRank
  worker reads `zaps_via:`; the server reads the node properties.
- Quartz comes from Maven Central and is built with a newer Kotlin than older plugins can read;
  keep the Kotlin plugin at or above Quartz's.

## Build

`./gradlew test`. Maven Central rate-limits bursts of parallel downloads; if a fresh build gets
HTTP 429, re-run with `--max-workers=1`.
