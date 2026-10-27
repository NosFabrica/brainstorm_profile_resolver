package com.nosfabrica.profileresolver.store

/** How a domain has been answering. Shared by the lightning and NIP-05 lookups. */
data class DomainHealth(
    val failures: Int,
    /** Epoch seconds; no request to the domain before this. */
    val retryAt: Long,
)

/** A lightning address's answer, cached so it is fetched once however many users share it. */
data class AddressAnswer(
    val status: LnStatus,
    val signer: String?,
    val checkedAt: Long,
)

enum class LnStatus {
    /** `allowsNostr` and a valid `nostrPubkey`: the signer of this address's zap receipts. */
    OK,

    /** The endpoint answered, but does no zaps. */
    NO_ZAPS,

    /** The server is up and has no such address. */
    NOT_FOUND,

    /** Never fetched: not https, a non-public host, an oversized answer. */
    INVALID,

    /** Not settled yet: the domain was down. Retried on a later run, never cached. */
    PENDING,
    ;

    val isFinal get() = this != PENDING
}

enum class Nip05Status {
    VERIFIED,

    /** The domain's `names` does not map this name to this pubkey. */
    MISMATCH,

    /** The server is up and has no `nostr.json` for this name. */
    NOT_FOUND,

    INVALID,
    PENDING,
    ;

    val isFinal get() = this != PENDING
}

/** The small, fast state: Redis in production. */
interface KeyValueStore {
    fun addressAnswer(url: String): AddressAnswer?

    fun putAddressAnswer(
        url: String,
        answer: AddressAnswer,
    )

    fun domainHealth(domain: String): DomainHealth?

    fun putDomainHealth(
        domain: String,
        health: DomainHealth?,
    )

    /** Epoch seconds the last complete incremental pass started at. */
    fun cursor(): Long?

    fun putCursor(epochSeconds: Long)

    /** Replaces `zaps_via:<pubkey>`, the forward set the GrapeRank worker reads. Null clears it. */
    fun setZapsVia(
        pubkey: String,
        signer: String?,
    )
}

/** What the graph holds for one user, as this job last wrote it. */
data class UserState(
    val pubkey: String,
    val lnurl: String? = null,
    val lnurlStatus: LnStatus? = null,
    val nip05: String? = null,
    val nip05Status: Nip05Status? = null,
    val nip05CheckedAt: Long? = null,
)

data class LightningWrite(
    val pubkey: String,
    /** Null clears the user's address, status and edge. */
    val lnurl: String?,
    val status: LnStatus?,
    /** The `ZAPS_VIA` target; null leaves the user with no edge. */
    val signer: String?,
)

data class Nip05Write(
    val pubkey: String,
    /** Null clears the user's NIP-05 fields. */
    val nip05: String?,
    val status: Nip05Status?,
    val checkedAt: Long?,
)

/** The graph: Neo4j in production. Only users already in it are touched. */
interface GraphStore {
    /** The users among [pubkeys] the graph holds; anyone else is absent. */
    fun users(pubkeys: List<String>): Map<String, UserState>

    /** Graph pubkeys after [after], in order, at most [limit]. */
    fun pubkeysAfter(
        after: String,
        limit: Int,
    ): List<String>

    /** Users whose lightning lookup is still pending, after [after]. */
    fun pendingLightning(
        after: String,
        limit: Int,
    ): List<UserState>

    /** Users whose NIP-05 is pending or was last checked before [checkedBefore], after [after]. */
    fun nip05Due(
        checkedBefore: Long,
        after: String,
        limit: Int,
    ): List<UserState>

    fun writeLightning(rows: List<LightningWrite>)

    fun writeNip05(rows: List<Nip05Write>)
}
