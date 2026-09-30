package app.davkeep.core

import android.accounts.Account
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Shared contracts between the slices. Owned by the integration boundary: implementations
 * live in their own packages and must not change these signatures without coordination.
 *
 * Vocabulary follows CONTEXT.md — Account, Collection, Credentials, Origin.
 */

const val ACCOUNT_TYPE = "app.davkeep"

// ---------------------------------------------------------------- model

/** One name/value pair sent on every request for an Account. Values are secrets. */
data class DavHeader(val name: String, val value: String)

enum class CollectionType { ADDRESS_BOOK, CALENDAR }

/**
 * One address book or calendar. [id] is stable and local; it lands in RawContacts.SYNC3
 * so that (account, SYNC3, SOURCE_ID) is unique across Collections of one Account.
 */
data class DavCollection(
    val id: String,
    val url: String,
    val type: CollectionType,
    val displayName: String?,
    val color: Int?,
    val selected: Boolean = false,
    val available: Boolean = true,
    /**
     * True when the user named this Collection by its URL rather than a walk finding it.
     *
     * A discovery walk enumerates the home sets, and a Collection outside them is one it can say
     * nothing about — including that it is gone. Without this flag the first walk retires exactly
     * the Collections a user had to paste a URL for, which are the ones a server does not publish.
     */
    val pinned: Boolean = false,
    /**
     * True when an edit made on the phone may be sent to the server for this Collection.
     *
     * Default false: the spec of record promised read-only, and an upgrade must not begin writing
     * to a server nobody asked it to write to. Neither `supportsUploading` nor `<EditSchema>` can
     * be told about one Collection, so this is what the engine enforces per Collection.
     */
    val writable: Boolean = false,
)

/**
 * Where an Account's client certificate comes from.
 *
 * Two sources, because they fail differently. A [KeyChainAlias] is owned by the system: the key
 * never enters this process, but the alias stops resolving after a device restore. An [Imported]
 * archive is owned by the app: it survives a restore inside the encrypted export, at the cost of
 * holding key material we are then responsible for.
 */
sealed interface ClientCertificateSource {
    /** The system KeyChain holds the key; only the alias is stored. */
    data class KeyChainAlias(val alias: String) : ClientCertificateSource

    /** A PKCS#12 archive the app holds, re-wrapped under its own random passphrase. */
    data object Imported : ClientCertificateSource
}

/**
 * What an imported identity says about itself. [expired] is reported, never enforced: only the
 * server decides whether it still accepts a certificate.
 */
data class ClientCertificateInfo(
    val subject: String,
    val issuer: String,
    val notAfter: Long,
    val expired: Boolean,
)

/** One configured server. Exactly one AccountManager account. */
data class DavAccount(
    val label: String,
    val baseUrl: String,
    val headers: List<DavHeader> = emptyList(),
    val certificate: ClientCertificateSource? = null,
    val username: String? = null,
    val password: String? = null,
    val collections: List<DavCollection> = emptyList(),
) {
    val androidAccount: Account get() = Account(label, ACCOUNT_TYPE)

    /**
     * The origin an identity may be released to. A redirect elsewhere must never receive it.
     *
     * Built from OkHttp's parser rather than `java.net.URI`, whose `host` is null for a name that is
     * not ASCII: an account on one produced the origin `https://null:-1`, which matches no peer, so
     * its certificate was never offered and the failure looked like a TLS or authentication problem.
     * `HttpUrl` resolves the port as well, so an origin has one spelling rather than one per way of
     * writing the URL.
     */
    val origin: String? get() = baseUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }
}

// ---------------------------------------------------------------- errors

/** The twelve failure classes of the spec's taxonomy. Order matters: see SyncErrorClassifier. */
enum class ErrorClass(val terminal: Boolean, val retryable: Boolean) {
    PROXY_REJECTED_CREDENTIALS(true, false),
    ORIGIN_WANTS_CREDENTIALS(true, false),
    ORIGIN_REFUSED_INFO(false, false),
    CERTIFICATE_UNAVAILABLE(true, false),
    CREDENTIALS_UNREADABLE(true, false),
    NO_CERTIFICATE_SENT(true, false),
    TRANSPORT_FAILURE(false, true),
    SERVER_ERROR(false, true),
    NOT_FOUND(false, false),
    METHOD_REFUSED(false, false),
    MALFORMED_RESPONSE(false, false),
    PROXY_INTERFERENCE(true, false),
}

/**
 * What the app observed. [summary] is the plain-language line; the expander shows the rest
 * verbatim. Never claim more than was observed: see NO_CERTIFICATE_SENT.
 */
data class SyncError(
    val errorClass: ErrorClass,
    val summary: String,
    val httpStatus: Int?,
    val firstBodyLine: String?,
    val certificateOffered: Boolean,
    val requestMethod: String?,
    val davCondition: String? = null,
    val cause: Throwable? = null,
)

/** Evidence handed to the classifier. Deliberately transport-agnostic so it is testable. */
data class ResponseEvidence(
    val httpStatus: Int?,
    val locationHeader: String?,
    val wwwAuthenticate: String?,
    val contentType: String?,
    val body: String?,
    val requestMethod: String?,
    val certificateOffered: Boolean,
    val transportFailure: Throwable? = null,
)

interface SyncErrorClassifier {
    /** Applies the spec's ordered rules; first match wins. Status alone never decides severity. */
    fun classify(evidence: ResponseEvidence): SyncError
}

// ------------------------------------------------- imported client certificates

/** Why a PKCS#12 import failed, kept distinct so the message names something the user can act on. */
sealed interface CertificateImportResult {
    data class Success(val info: ClientCertificateInfo) : CertificateImportResult

    /** PKCS#12 integrity is a MAC over the passphrase, so well-formed DER that will not load is this. */
    data object WrongPassphrase : CertificateImportResult

    /** Not a DER SEQUENCE, or not a PKCS#12 at all. Blaming the passphrase here would be a lie. */
    data object NotAPkcs12 : CertificateImportResult

    /** An archive holding only certificates. Nothing here can authenticate a handshake. */
    data object NoPrivateKey : CertificateImportResult
}

/** A private key and its chain, held only for the duration of a handshake. */
data class ClientIdentity(
    val privateKey: java.security.PrivateKey,
    val chain: Array<java.security.cert.X509Certificate>,
)

/**
 * Holds an imported PKCS#12 identity for an Account.
 *
 * The user's own passphrase is never persisted — it is often reused elsewhere. The archive is
 * re-wrapped under a random passphrase generated here, and both the bytes and that passphrase go
 * through [CredentialStore], so no key material is ever written in the clear.
 */
interface ClientCertificateStore {
    fun import(account: Account, pkcs12: ByteArray, passphrase: CharArray): CertificateImportResult

    fun info(account: Account): ClientCertificateInfo?

    /**
     * The identity, or null when none is installed. Implementations self-heal: if our own copy no
     * longer parses (Keystore key rotated, record corrupted) both records are deleted rather than
     * failing every request from then on.
     */
    fun identity(account: Account): ClientIdentity?

    fun remove(account: Account)
}

// ---------------------------------------------------------------- credentials

/**
 * Keystore-wrapped ciphertext stored in AccountManager userdata (ADR-0001).
 * Credentials can be replaced, never revealed.
 */
interface CredentialStore {
    fun put(account: Account, key: String, secret: String)
    /** @throws CredentialsUnreadableException after a device restore: the Keystore key is gone. */
    fun get(account: Account, key: String): String?
    fun remove(account: Account, key: String)
    fun clear(account: Account)
}

class CredentialsUnreadableException(cause: Throwable?) :
    Exception("Credentials can't be read — this happens after restoring a device.", cause)

/**
 * Thrown by [AccountStore.create] when an account with this label is already on the device.
 *
 * The label is the Android account name, so creating one would not add a second account: it would
 * write the new record over the account that is there, replacing an address, a stored password, a
 * certificate and a Collection selection that the caller never saw and could not show. A caller
 * that means to update an account it knows exists wants [AccountStore.save].
 */
class AccountExistsException(val label: String) :
    Exception("An account named \"$label\" already exists on this device.")

/** Persistence of the Account record itself, in AccountManager. */
interface AccountStore {
    fun list(): List<DavAccount>
    fun load(account: Account): DavAccount?

    /**
     * Registers a new Account, refusing when one with this label is already there.
     *
     * @throws AccountExistsException rather than writing over the account that is there.
     */
    fun create(davAccount: DavAccount)

    /** Rewrites an Account that is expected to exist. This is the update path. */
    fun save(davAccount: DavAccount)

    /**
     * Keeps the Account and everything its providers hold, and takes away what makes it a server:
     * the address, the Credentials, the certificate and the Collection selection.
     *
     * The Account staying registered is the whole point of this method existing. The providers
     * delete the rows of an account that is not in AccountManager (spec §7), so an account that
     * goes takes its synced contacts and events with it. What is left is one the settings screen
     * can show as disconnected and offer to configure again, with its data still on the device.
     */
    fun disconnect(account: Account)

    fun delete(account: Account)
}

// ---------------------------------------------------------------- http

/**
 * Builds the HTTP client for one Account: header interceptor plus an optional
 * KeyChain-backed client certificate.
 */
interface DavHttpClientFactory {
    fun create(davAccount: DavAccount): DavHttpClient
}

/**
 * [certificateOffered] reports whether the X509KeyManager alias callback actually fired,
 * which is the only honest answer to "was a certificate sent".
 */
interface DavHttpClient {
    val okHttp: OkHttpClient
    val certificateOffered: Boolean
}

// ---------------------------------------------------------------- sync state

/** Per-Collection state. Lives with the data in the provider, never in a separate database. */
data class CollectionState(
    val ctag: String? = null,
    val syncToken: String? = null,
    val supportsSyncCollection: Boolean? = null,
    val capabilityCheckedAt: Long = 0L,
    val lastSuccessAt: Long = 0L,
)

/** One item as listed by PROPFIND Depth:1. */
data class RemoteItem(val href: String, val etag: String?)

/**
 * Writes fetched resources into a platform provider. One implementation per authority.
 * The sync engine owns ordering, batching and the invariants; mappers own columns.
 */
interface ProviderMapper {
    val authority: String

    /** Must be called before any write; the provider reaps rows of unregistered accounts. */
    fun assertAccountRegistered(account: Account)

    fun readState(account: Account, collection: DavCollection): CollectionState
    fun writeState(account: Account, collection: DavCollection, state: CollectionState)

    /** Local (href -> ETag) for every row of this Collection. */
    fun localItems(account: Account, collection: DavCollection): Map<String, String?>

    /**
     * Upsert one batch. [resources] is href -> raw vCard/iCalendar text; [etags] is href -> ETag
     * for the same hrefs, written onto the rows in the same transaction as the bodies.
     *
     * The two must be persisted together: an ETag stored without its body (or vice versa) would
     * make the next run's diff against [localItems] lie about what is already present.
     *
     * Returns the hrefs whose rows now hold the server's copy. A body that is left out of the
     * answer was not written: it did not parse, it describes nothing the provider can hold, or
     * its rows hold an edit that arrived since they were last read. The caller can tell the
     * three apart only by what it does next, which is why they are not distinguished here.
     */
    fun upsert(
        account: Account,
        collection: DavCollection,
        resources: Map<String, String>,
        etags: Map<String, String?>,
    ): Set<String>

    /** Only ever called with a listing that completed. */
    fun deleteMissing(account: Account, collection: DavCollection, keepHrefs: Set<String>): Int

    /**
     * Refreshes what the provider shows for the Collection itself — the calendar's access level,
     * which is how a read-only Collection is expressed to the stock Calendar app.
     *
     * Called at the start of every Collection, because a Collection whose CTag never moves would
     * otherwise never learn that the user changed the setting.
     */
    fun ensureCollection(account: Account, collection: DavCollection)

    /** Rows of this Collection awaiting upload: deletions first, then creates, then updates. */
    fun pendingChanges(account: Account, collection: DavCollection): List<LocalChange>

    /**
     * The resource's bytes, or null when they cannot be produced.
     *
     * A [ChangeKind.CREATE] with no UID mints one and persists it before returning, so a retry
     * after a lost answer sends the same name rather than a duplicate.
     */
    fun serialize(account: Account, collection: DavCollection, change: LocalChange): UploadBody?

    /**
     * Stores identity, ETag and `DIRTY=0` in one operation, after the server answered 2xx.
     *
     * Returns false when the row moved since [change] was read: the edit that arrived during the
     * request is still pending, so the row stays dirty and the next run sends it. A dirty flag is
     * cleared here, by the answer to the row's own upload, and in three other places that each
     * account for the edit they clear: [acknowledgeUnchanged], for a body the server already holds;
     * [revertLocalChange], for an edit the server refused or a Collection would not take, which the
     * run reports; and [replaceOverEdit], for an edit that can never be sent, which the run reports
     * as a conflict. Nothing clears one as a backstop — a row cleared without one of those answers
     * is an edit thrown away.
     */
    fun markUploaded(
        account: Account,
        collection: DavCollection,
        change: LocalChange,
        key: String,
        uid: String,
        etag: String?,
        body: String,
    ): Boolean

    /** Really deletes a tombstone the server has accepted, and everything hanging off it. */
    fun purgeDeleted(account: Account, collection: DavCollection, change: LocalChange)

    /**
     * `DELETED=0`, `DIRTY=0`, ETag null on every row of the resource: the local edit is given up
     * and the row now waits for the server's version, which this same run fetches onto it.
     *
     * @param sent whether a body reached the server and this is its answer. A refusal sends
     * nothing, so a provider that marks the rows it is about to send has nothing marked to match on
     * and must fall back to what it can still prove — that the row is the dirty one it was told
     * about. Without this a refused Collection reverts nothing at all, and §7's promise that a
     * read-only Collection gives an edit back becomes silence.
     * @return false when the row moved since [change] was read, in which case **nothing is written**.
     * An edit made while the server was answering is newer than the answer, so giving it up would
     * lose exactly what the user just typed; it stays pending and meets the same answer next run,
     * against the row as it is then.
     */
    fun revertLocalChange(
        account: Account,
        collection: DavCollection,
        change: LocalChange,
        sent: Boolean,
    ): Boolean

    /**
     * What this run owes the server's copy to, read from the rows at the start of every run.
     *
     * [RestorePlan.full] is what a revert leaves: rows that are clean and carry no ETag, holding
     * text nobody kept and unable to say which version the server has. They are fetched by href
     * before anything else — on every run, until the server answers for them.
     *
     * [RestorePlan.conflicted] is a row that has been edited since: named, dirty, and with no
     * stored copy of the server's text. Its edit cannot be serialised at all — there is no base to
     * patch it onto — so it can never be sent, and nothing the rows say can be trusted to describe
     * what the server holds. Those resources are fetched and resolved the way a `412` is resolved:
     * the server's copy replaces the rows, through [replaceOverEdit], and the run reports a
     * conflict. The engine adds to that half the resource whose rows do hold a text but no ETag
     * while the server names one for it: that text was stored under no version the server now
     * holds, so a body patched out of it would carry a stale base back, and it is resolved the
     * same way.
     */
    fun restorePlan(account: Account, collection: DavCollection): RestorePlan

    /**
     * Writes the server's copy of [key] over rows holding an edit, clearing their flags: the
     * conflict resolution for a resource whose edit can never be sent.
     *
     * This is the one write that discards a local change without an answer from the server about
     * that change, and it is legitimate for one reason only — the rows hold no text the server's
     * current version was ever read from, so the difference between them and [text] is not an edit
     * anyone can describe, and the resource is [RestorePlan.conflicted] or one the engine found
     * stale by asking. Called with nothing else.
     *
     * A [text] the provider cannot hold — one that does not parse, or describes no row — still
     * resolves the conflict: the rows are removed, because the server's version is what belongs
     * there and the phone has no way to show it, exactly as a listing gives such a resource no
     * rows. Leaving them would ask for the same body on every run for good and hold the edit out
     * of every upload while doing so.
     *
     * Returns whether the rows now show the server's answer; false leaves them exactly as they
     * were, which is what a resource whose master became a tombstone, or whose rows moved under
     * this call, gets.
     */
    fun replaceOverEdit(
        account: Account,
        collection: DavCollection,
        key: String,
        text: String,
        etag: String?,
    ): Boolean

    /**
     * The server's copy of [key] arrived for rows a revert left, and [upsert] could not write it:
     * it did not parse, or it describes nothing the provider can hold.
     *
     * The rows are the phone's rejected text, waiting for a version that cannot land on them, and
     * every run would ask for it again. So they go, the way a listing gives no rows to a resource
     * it cannot read — the server still holds it, and a body that becomes readable is fetched by
     * the next listing that names it. Only rows holding no unsent change are removed, decided in
     * the same transaction as the delete: an edit made since the plan was read is newer than this
     * answer and keeps every row of the resource, for step U to meet next run.
     *
     * Returns the rows removed; zero means the resource holds an edit and nothing was written.
     */
    fun discardUnwritable(account: Account, collection: DavCollection, key: String): Int

    /**
     * The server has said, by name, that it no longer has the resource stored under [key].
     *
     * Every clean row of it is removed. A row holding an unsent edit is not: that edit is newer
     * than this answer, and the only place left to send it is a resource of its own, so the rows
     * give the name and the ETag back instead and are a create again. Leaving them named would
     * leave them asking for a resource that will never answer, on every run, with the edit held
     * out of every upload. A tombstone keeps its name, because the `DELETE` it sends needs one.
     *
     * Returns the rows removed; rows that gave back a name are not removed and are not counted.
     */
    fun resourceGone(account: Account, collection: DavCollection, key: String): Int

    /**
     * Clears `DIRTY` for a change whose body says nothing the server does not already hold, under
     * the same guard [markUploaded] uses.
     *
     * Nothing else is written: no identity, no ETag and no stored source, because none of them
     * moved. Returns false when the row moved since [change] was read — a real edit made while step
     * U was deciding is newer than this answer, so it stays pending and the next run sends it.
     */
    fun acknowledgeUnchanged(account: Account, collection: DavCollection, change: LocalChange): Boolean
}

/**
 * The resources one run owes the server's copy to, split by what the rows under them mean.
 *
 * Both halves are fetched together, by href, before any listing. They part at the write, and the
 * split is the whole of what separates a repair from a lost update: rows nobody has touched since
 * the revert are the server's to replace and nothing is given up by replacing them, while rows
 * holding an edit that can never be sent are a conflict, resolved the way every other conflict is
 * — the server's version wins and the run says so.
 */
data class RestorePlan(
    /** Named, clean, no ETag: the rows are the phone's rejected text and the server's belongs there. */
    val full: Set<String> = emptySet(),
    /** Named, dirty, with no stored text: an edit with no base to be patched onto, and no way out. */
    val conflicted: Set<String> = emptySet(),
)

enum class ChangeKind { CREATE, UPDATE, DELETE }

/** One row awaiting upload, as the provider held it when step U began. */
data class LocalChange(
    /** `RawContacts._ID` or the master `Events._ID`. */
    val rowId: Long,
    val kind: ChangeKind,
    /** The resource name this row was last stored under; null for a create. */
    val key: String? = null,
    val etag: String? = null,
    val uid: String? = null,
    /** `RawContacts.VERSION`, which guards the clear; null where the provider keeps no counter. */
    val version: Long? = null,
)

/**
 * The bytes of one resource, and the UID they carry — already persisted on the row.
 *
 * [unchanged] says the rows produced a resource the server already holds: every property came out
 * the way the stored source spelled it, and the only differences are this app's own bookkeeping —
 * the `PRODID` naming the last writer and, for a contact, the `REV` stamped at serialisation. A
 * dirty flag can be set by a write that reaches no property of the resource, so such a body is one
 * step U has nothing to tell the server with.
 */
data class UploadBody(val text: String, val uid: String, val unchanged: Boolean = false)
