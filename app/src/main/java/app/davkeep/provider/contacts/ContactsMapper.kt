package app.davkeep.provider.contacts

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.OperationApplicationException
import android.database.Cursor
import android.net.Uri
import android.provider.BaseColumns
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.DisplayPhoto
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import android.provider.ContactsContract.SyncState
import android.util.Log
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import app.davkeep.core.ChangeKind
import app.davkeep.core.CollectionState
import app.davkeep.core.DavCollection
import app.davkeep.core.LocalChange
import app.davkeep.core.ProviderMapper
import app.davkeep.core.RestorePlan
import app.davkeep.core.UploadBody

/**
 * Writes one Account's address books into `ContactsContract`.
 *
 * Identity is `(Account, SYNC3, SOURCE_ID)`: [RawContacts.SOURCE_ID] holds the key the sync engine
 * lists resources under, `SYNC1` its vCard UID and `SYNC2` its ETag, both stored exactly as given.
 * `SYNC3` carries the Collection id, which is what scopes every lookup, diff and delete in this
 * class to the one address book the call is about: an Account spans many Collections.
 *
 * Both directions are live. A write from the server replaces an item's rows wholesale, which is why a
 * fetched row is written `DIRTY=0`; a write from the phone is serialised by [patchContact] from the
 * item's own stored vCard, and `DIRTY` returns to 0 only where the run has answered for the edit:
 * [markUploaded] after the server accepted the `PUT`, [acknowledgeUnchanged] for a body the server
 * already holds, and [revertLocalChange] and [replaceOverEdit] for an edit given up and reported.
 * Nothing else may clear it: a row cleared without an answer is an edit thrown away. Each clear names
 * the row it is about, matching on the `RawContacts.VERSION` the row was read under, and the answer
 * to an upload commits in the same batch as the rows re-derived from the body: a contact the user
 * edited while the request was in flight keeps its flag and its newer rows.
 *
 * @param photoFetcher used for `PHOTO;VALUE=uri`, and only for a URL that is on the Collection's own
 *   Origin. Left null, such photos are skipped and counted.
 */
class ContactsMapper(
    context: Context,
    private val photoFetcher: PhotoFetcher? = null,
) : ProviderMapper {

    private val resolver: ContentResolver = context.contentResolver
    private val accountManager: AccountManager = AccountManager.get(context)

    /** The provider owns this bound and it varies with device memory, so it is asked, once. */
    private var maxPhotoDimension: Int? = null

    override val authority: String = ContactsContract.AUTHORITY

    override fun assertAccountRegistered(account: Account) {
        // Load-bearing, not a sanity check: the provider resolves an account from every write URI and
        // creates an accounts row for whatever it is handed, while its cleanup path deletes the rows
        // of an ACCOUNT_TYPE it can no longer resolve. An unregistered Account therefore means rows
        // owned by nobody, or rows that are about to be deleted under the user.
        val registered = accountManager.getAccountsByType(account.type).any { it.name == account.name }
        check(registered) { "Account ${account.name} (${account.type}) is not registered" }
    }

    override fun readState(account: Account, collection: DavCollection): CollectionState =
        readStates(account)[collection.id] ?: CollectionState()

    override fun writeState(account: Account, collection: DavCollection, state: CollectionState) {
        assertAccountRegistered(account)
        // SyncState keeps one row per Account, so this Collection's state is merged into that row's
        // blob: replacing it outright would forget every other Collection of the Account.
        val states = readStates(account).toMutableMap()
        states[collection.id] = state
        val data = SyncStateCodec.encode(states)

        val rowId = syncStateRowId(account)
        if (rowId == null) {
            resolver.insert(
                SyncState.CONTENT_URI.forSyncState(),
                ContentValues().apply {
                    put(SyncState.ACCOUNT_NAME, account.name)
                    put(SyncState.ACCOUNT_TYPE, account.type)
                    put(SyncState.DATA, data)
                },
            )
        } else {
            resolver.update(
                ContentUris.withAppendedId(SyncState.CONTENT_URI, rowId).forSyncState(),
                ContentValues().apply { put(SyncState.DATA, data) },
                null,
                null,
            )
        }
    }

    override fun localItems(account: Account, collection: DavCollection): Map<String, String?> {
        val items = LinkedHashMap<String, String?>()
        resolver.query(
            RawContacts.CONTENT_URI.forSyncAdapter(account),
            arrayOf(RawContacts.SOURCE_ID, RawContacts.SYNC2),
            contactSelection(),
            collectionArgs(account, collection),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val sourceId = cursor.getString(0) ?: continue
                items[sourceId] = cursor.getString(1)
            }
        }
        return items
    }

    override fun upsert(
        account: Account,
        collection: DavCollection,
        resources: Map<String, String>,
        etags: Map<String, String?>,
    ): Set<String> = write(account, collection, resources, etags, givenUp = emptySet())

    /**
     * [upsert]'s work, with the one thing [replaceOverEdit] does differently: [givenUp] names the
     * contacts whose pending edit the server's copy may be written over, and nothing else about the
     * write changes — the same rows, the same batch, the same guard on `VERSION`, minus the guard on
     * `DIRTY` for those keys alone.
     *
     * @return the keys whose rows now hold the server's copy.
     */
    private fun write(
        account: Account,
        collection: DavCollection,
        resources: Map<String, String>,
        etags: Map<String, String?>,
        givenUp: Set<String>,
    ): Set<String> {
        assertAccountRegistered(account)

        val contacts = ArrayList<Resource>()
        val groups = ArrayList<Resource>()
        var unreadable = 0
        for ((key, text) in resources) {
            val parsed = parseVCard(text)
            if (parsed == null) {
                unreadable++
                Log.w(LOG_TAG, "${collection.id}: $key did not parse, leaving it for the next run")
                continue
            }
            if (parsed.isGroup) groups += Resource(key, text, parsed) else contacts += Resource(key, text, parsed)
        }

        val dataUri = Data.CONTENT_URI.forSyncAdapter(account)
        val rawContactsUri = RawContacts.CONTENT_URI.forSyncAdapter(account)
        val groupsUri = Groups.CONTENT_URI.forSyncAdapter(account)

        // The batch is a function of the snapshot it was built from and of nothing else, so that a row
        // that moved under it can be answered by reading the snapshot again and building the whole
        // batch a second time.
        fun assemble(known: KnownRows): Assembly {
            val stats = UpsertStats()
            stats.unreadable = unreadable
            val batch = ArrayList<ContentProviderOperation>()

            // Pass 1: contacts, with their ETags, in the rows that carry them. Nothing else in the
            // batch may point at a contact that does not exist yet.
            val writtenContacts = ArrayList<Pair<Resource, RowRef>>()
            val contactsByUid = HashMap<String, RowRef>()
            val photoContacts = ArrayList<RowRef>()
            for (resource in contacts) {
                val ref = appendContact(
                    account, collection, resource, etags[resource.key], dataUri, rawContactsUri, batch, stats, known,
                    photoContacts, overEdit = resource.key in givenUp,
                ) ?: continue
                writtenContacts += resource to ref
                resource.parsed.uid?.let { contactsByUid[it] = ref }
            }

            // Pass 2: groups, which memberships need a row id for.
            val writtenGroups = ArrayList<Pair<Resource, RowRef>>()
            val groupsByTitle = HashMap<String, RowRef>()
            for (resource in groups) {
                val ref =
                    appendGroup(account, collection, resource, etags[resource.key], dataUri, groupsUri, batch, known)
                writtenGroups += resource to ref
                resource.parsed.displayName?.let { groupsByTitle[it.lowercase(Locale.ROOT)] = ref }
            }

            val unresolved = ArrayList<DeferredMembership>()
            // A contact's CATEGORIES name the groups it belongs to, which is how a DAV client that has
            // no group objects at all still expresses membership.
            for ((resource, ref) in writtenContacts) {
                for (category in resource.parsed.categories) {
                    val group = groupsByTitle[category.lowercase(Locale.ROOT)]
                        ?: known.groupIdsByTitle[category]?.let { RowRef.existing(it) }
                    if (group == null) {
                        unresolved += DeferredMembership(MEMBERSHIP_FROM_CATEGORIES, contact = ref, groupTitle = category)
                        continue
                    }
                    batch += membershipOperation(dataUri, ref, group, MEMBERSHIP_FROM_CATEGORIES)
                    stats.memberships++
                }
            }
            // A group vCard names its members by UID, which is the other direction of the same relation.
            for ((resource, group) in writtenGroups) {
                for (uid in resource.parsed.members) {
                    // A contact whose own edit is still pending is not a row to add a membership to: the
                    // next run resolves it once the flag is gone.
                    val contact = contactsByUid[uid]
                        ?: known.contactIdsByUid[uid]?.takeIf { !it.pending }?.let { RowRef.existing(it.id) }
                    if (contact == null) {
                        unresolved += DeferredMembership(MEMBERSHIP_FROM_GROUP, contactUid = uid, group = group)
                        continue
                    }
                    batch += membershipOperation(dataUri, contact, group, MEMBERSHIP_FROM_GROUP)
                    stats.memberships++
                }
            }

            stats.items = contacts.size + groups.size
            return Assembly(
                batch, photoContacts, unresolved, stats,
                written = writtenContacts.mapTo(LinkedHashSet()) { it.first.key } + writtenGroups.map { it.first.key },
            )
        }

        // The rows this Collection already holds, for every name this batch asks about, read once for
        // the batch: the write path asks "which row is this href?" once per resource and "which row is
        // this UID or title?" once per membership end, so a run in which only contacts changed used to
        // pay an indexed query for each of those answers. The names are the batch's own, so this reads
        // what those questions can have an answer for and nothing else.
        var known = knownRows(account, collection, contacts, groups)
        var assembly = assemble(known)

        // Nothing to write is a real outcome — every resource may have failed to parse — and an
        // empty batch is not something the framework accepts.
        if (assembly.batch.isEmpty()) {
            assembly.stats.log(collection)
            return emptySet()
        }

        // Re-checked here and not only at the start: a long first sync is exactly when a user is
        // most likely to remove the Account, and the rows of an Account that no longer exists are
        // rows the provider reaps.
        assertAccountRegistered(account)
        val results = try {
            resolver.applyBatch(authority, assembly.batch)
        } catch (e: OperationApplicationException) {
            // One row moved between the snapshot and the batch, and the provider rolls a batch back
            // whole: giving up here would lose a listing's worth of resources to a single keystroke.
            // The snapshot is read again and the batch rebuilt around what the rows are now — the row
            // that moved is dirty or deleted, so `appendContact` holds it back rather than guarding on
            // it again — and a second failure is a race this run cannot win.
            Log.i(LOG_TAG, "${collection.id}: a contact moved while the batch was built, rebuilding it once: $e")
            known = knownRows(account, collection, contacts, groups)
            assembly = assemble(known)
            if (assembly.batch.isEmpty()) {
                emptyArray()
            } else {
                assertAccountRegistered(account)
                resolver.applyBatch(authority, assembly.batch)
            }
        }
        val stats = assembly.stats

        // Pass 2a: the photo rows this batch wrote now hold bytes the provider re-encoded, and a
        // digest of those bytes is what the next upload compares against to tell a photo the user
        // changed from one the source already carries (`VCardPatch.PhotoEdit`).
        refreshPhotoBaselines(account, assembly.photoContacts.mapNotNull { resolveId(it, results) })

        // Pass 3: memberships whose other end this batch never named. Both ends can arrive in any
        // order across batches, and a batch sees everything the run wrote before it: whatever is not
        // in [known] either does not exist, in which case the next run resolves it once it does, or is
        // counted as unresolved and logged rather than failing the batch around it.
        val reconciliation = ArrayList<ContentProviderOperation>()
        for (membership in assembly.unresolved) {
            val contactId = membership.contact?.let { resolveId(it, results) }
                ?: membership.contactUid?.let { known.contactIdsByUid[it]?.takeIf { known -> !known.pending }?.id }
            val groupId = membership.group?.let { resolveId(it, results) }
                ?: membership.groupTitle?.let { known.groupIdsByTitle[it] }
            if (contactId == null || groupId == null) {
                stats.unresolvedMemberships++
                continue
            }
            reconciliation += membershipOperation(
                dataUri,
                RowRef.existing(contactId),
                RowRef.existing(groupId),
                membership.provenance,
            )
            stats.memberships++
        }
        if (reconciliation.isNotEmpty()) {
            assertAccountRegistered(account)
            resolver.applyBatch(authority, reconciliation)
        }

        stats.log(collection)
        return assembly.written
    }

    override fun deleteMissing(account: Account, collection: DavCollection, keepHrefs: Set<String>): Int {
        assertAccountRegistered(account)
        // The caller contract is that this only ever runs against a listing that completed; the
        // mapper's own part in that is scoping every delete to this Account and this Collection, so
        // a short listing can never reach another address book's rows.
        //
        // `DIRTY=0` is the other part: a row the user is still editing is not one the server has been
        // asked about, and a listing that does not name it says nothing about an edit nobody sent yet.
        // It is deleted by a later run, once its upload has been answered or reverted.
        val staleContacts = ArrayList<Long>()
        resolver.query(
            RawContacts.CONTENT_URI.forSyncAdapter(account),
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID),
            "${contactSelection()} AND ${RawContacts.DIRTY}=0",
            collectionArgs(account, collection),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val sourceId = cursor.getString(1) ?: continue
                if (sourceId !in keepHrefs) staleContacts += cursor.getLong(0)
            }
        }

        // Groups are scoped by SYNC3 for the same reason contacts are: a group's href is unique on
        // the server, but nothing about it says which address book the listing came from.
        val staleGroups = ArrayList<Long>()
        resolver.query(
            Groups.CONTENT_URI.forSyncAdapter(account),
            arrayOf(Groups._ID, Groups.SOURCE_ID),
            "${Groups.ACCOUNT_NAME}=? AND ${Groups.ACCOUNT_TYPE}=? AND ${Groups.SYNC3}=? " +
                "AND ${Groups.SOURCE_ID} IS NOT NULL",
            collectionArgs(account, collection),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val sourceId = cursor.getString(1) ?: continue
                if (sourceId !in keepHrefs) staleGroups += cursor.getLong(0)
            }
        }

        val deleted = deleteRows(RawContacts.CONTENT_URI.forSyncAdapter(account), staleContacts) +
            deleteRows(Groups.CONTENT_URI.forSyncAdapter(account), staleGroups)
        if (deleted > 0) {
            Log.i(LOG_TAG, "${collection.id}: deleted $deleted rows that are gone from the server")
        }
        return deleted
    }

    override fun ensureCollection(account: Account, collection: DavCollection) {
        // Nothing to refresh, and that is the whole implementation: whether an address book may be
        // written is not something the stock Contacts app can be told per Collection —
        // `res/xml/contacts.xml` declares the account type's editable kinds once for all of them — so
        // a read-only Collection is enforced by the engine, which reverts what step U finds for it,
        // rather than expressed here the way a calendar's access level is.
    }

    override fun pendingChanges(account: Account, collection: DavCollection): List<LocalChange> {
        assertAccountRegistered(account)
        adoptPhotoBaselines(account, collection)
        // Deletions first, then creates, then updates. A deletion goes first so that a contact deleted
        // and added again does not meet its own old href on the server; creates before updates so that
        // nothing an update refers to is missing when it is sent.
        return pendingRows(
            account,
            kind = ChangeKind.DELETE,
            selection = collectionSelection(PENDING_DELETES),
            args = collectionArgs(account, collection),
        ) + pendingRows(
            account,
            kind = ChangeKind.CREATE,
            // A contact made in the stock app names an Account, not one of its address books, so the
            // row has no SYNC3 until its first upload adopts it: it is the Account's to send, and the
            // engine asks for it through the default address book.
            selection = accountSelection(PENDING_CREATES),
            args = accountArgs(account),
        ) + pendingRows(
            account,
            kind = ChangeKind.UPDATE,
            selection = collectionSelection(PENDING_UPDATES),
            args = collectionArgs(account, collection),
        )
    }


    /**
     * Gives a baseline to a clean contact that has none, before anything it holds can be uploaded.
     *
     * A contact synced by a version that kept the baseline on the photo row arrives here with
     * `RawContacts.SYNC4` empty, and an empty baseline means "the photo changed" — so the first edit
     * to such a contact, even one that never touched the photo, would replace the server's `PHOTO`
     * with the provider's re-encode. That is the loss issue #31 was about, once per contact, and it
     * would land on address books that were synced before this app started hashing photos.
     *
     * A clean contact's rows are the server's: nothing local has touched them since the fetch wrote
     * them, so the photo it holds is what the last upload or fetch left, and recording its digest
     * states a fact rather than assuming one. A dirty contact is skipped deliberately — its photo may
     * be the edit waiting to be sent, and claiming it matches the server would drop that edit
     * silently, which is the same failure pointing the other way.
     *
     * Contacts with no photo get a marker rather than being left empty, and [markUploaded] writes the
     * same marker, so that the query converges to nothing instead of reading every photo-less
     * contact's bytes on every run. A photo added later hashes to something else, which is exactly the
     * "changed" answer it should be.
     */
    private fun adoptPhotoBaselines(account: Account, collection: DavCollection) {
        val candidates = ArrayList<Pair<Long, Long>>()
        resolver.query(
            RawContacts.CONTENT_URI.forSyncAdapter(account),
            arrayOf(RawContacts._ID, RawContacts.VERSION),
            collectionSelection(BASELINE_MISSING),
            collectionArgs(account, collection),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) candidates += cursor.getLong(0) to cursor.getLong(1)
        }
        if (candidates.isEmpty()) return

        // Which of them have a photo at all, in one query per chunk: a contact without a photo row
        // needs no bytes read to know its answer, and on an address book of thousands that is most of
        // them.
        val withPhoto = contactsWithPhotoRow(account, candidates.map { it.first })
        val rawContactsUri = RawContacts.CONTENT_URI.forSyncAdapter(account)
        var adopted = 0
        var unreadable = 0
        // Chunked because an address book synced before this existed can hold thousands of candidates,
        // and one batch of all of them overflows the Binder transaction: the whole batch then fails,
        // and fails identically on every later run, so exactly the address books this is for would
        // never converge.
        for (chunk in candidates.chunked(BASELINE_OPERATIONS_PER_BATCH)) {
            val batch = ArrayList<ContentProviderOperation>()
            for ((rowId, version) in chunk) {
                val baseline = if (rowId in withPhoto) {
                    // A photo row whose bytes cannot be read is left without a baseline rather than
                    // given the no-photo marker: marking it would claim there is no photo, and bytes
                    // that became readable later would then compare as changed.
                    displayPhotoBytes(account, rowId)?.let { photoDigest(it) } ?: run { unreadable++; null }
                } else {
                    NO_PHOTO
                } ?: continue
                // Guarded like every other write that claims a row is current: an edit that arrived
                // between the query and this batch leaves the contact without a baseline, and the next
                // run offers it again.
                batch += ContentProviderOperation.newUpdate(rawContactsUri)
                    .withSelection(
                        "${RawContacts._ID}=? AND ${RawContacts.VERSION}=? AND " +
                            "${RawContacts.DIRTY}=0 AND ${RawContacts.DELETED}=0",
                        arrayOf(rowId.toString(), version.toString()),
                    )
                    .withValue(RawContacts.SYNC4, baseline)
                    .build()
            }
            if (batch.isEmpty()) continue
            try {
                adopted += resolver.applyBatch(authority, batch).count { (it.count ?: 0) > 0 }
            } catch (e: Exception) {
                // This chunk is left for the next run; the others still converge.
                Log.w(LOG_TAG, "${collection.id}: could not adopt photo baselines for ${batch.size} contacts", e)
            }
        }
        Log.i(
            LOG_TAG,
            "${collection.id}: adopted a photo baseline for $adopted of ${candidates.size} contacts" +
                if (unreadable > 0) ", $unreadable with an unreadable photo left for a later run" else "",
        )
    }

    /**
     * Which of [rowIds] have a photo row, whatever its bytes.
     *
     * The distinction the baselines turn on: no row means no photo, which [NO_PHOTO] records, while a
     * row whose bytes cannot be read is a photo nobody can describe yet.
     */
    private fun contactsWithPhotoRow(account: Account, rowIds: List<Long>): Set<Long> {
        val found = HashSet<Long>()
        for (chunk in rowIds.chunked(SQL_VARIABLES_PER_STATEMENT)) {
            resolver.query(
                Data.CONTENT_URI.forSyncAdapter(account),
                arrayOf(Data.RAW_CONTACT_ID),
                "${Data.RAW_CONTACT_ID} IN (${chunk.joinToString(",") { "?" }}) AND ${Data.MIMETYPE}=?",
                chunk.map { it.toString() }.toTypedArray() + Photo.CONTENT_ITEM_TYPE,
                null,
            )?.use { cursor -> while (cursor.moveToNext()) found += cursor.getLong(0) }
        }
        return found
    }

    override fun serialize(account: Account, collection: DavCollection, change: LocalChange): UploadBody? {
        assertAccountRegistered(account)
        // A tombstone has no bytes: the engine sends DELETE against change.key, and only asks for a
        // body for a create or an edit.
        if (change.kind == ChangeKind.DELETE) return null

        val uid = change.uid ?: if (change.kind == ChangeKind.CREATE) mintUid(account, change.rowId) else null
        val bytes = patchContact(
            source = verbatimText(account, change.rowId),
            rows = dataRows(account, change.rowId),
            photo = photoEdit(account, change.rowId),
            categories = categoryEdit(account, collection, change.rowId),
            newUid = uid,
            now = Instant.now(),
        )
        if (bytes == null) {
            // The row keeps its copy, so the next run is offered the same edit; uploading from the rows
            // alone would send a vCard without everything the copy is holding.
            Log.w(LOG_TAG, "${collection.id}: contact ${change.rowId} does not parse, leaving it for the next run")
            return null
        }
        return UploadBody(bytes.text, bytes.uid.orEmpty(), unchanged = bytes.unchanged)
    }

    /**
     * Clears `DIRTY` for a contact whose vCard says nothing the server does not already hold.
     *
     * `RawContacts.VERSION` counts the rows a vCard is made of, and a raw-contact-level write —
     * `STARRED`, `CUSTOM_RINGTONE`, `SEND_TO_VOICEMAIL`, `PINNED` — sets the flag without moving it,
     * which is exactly the shape that arrives here. The guard is still the version step U read: an
     * edit that landed since moved it, and that edit is one nothing has sent.
     */
    override fun acknowledgeUnchanged(account: Account, collection: DavCollection, change: LocalChange): Boolean {
        assertAccountRegistered(account)
        val version = checkNotNull(change.version) { "contact ${change.rowId} has no version to guard on" }
        val cleared = resolver.update(
            RawContacts.CONTENT_URI.forSyncAdapter(account),
            ContentValues().apply { put(RawContacts.DIRTY, 0) },
            "${RawContacts._ID}=? AND ${RawContacts.VERSION}=?",
            arrayOf(change.rowId.toString(), version.toString()),
        )
        if (cleared == 0) {
            Log.i(
                LOG_TAG,
                "${collection.id}: contact ${change.rowId} changed while step U read it; it stays pending",
            )
        }
        return cleared > 0
    }

    override fun markUploaded(
        account: Account,
        collection: DavCollection,
        change: LocalChange,
        key: String,
        uid: String,
        etag: String?,
        body: String,
    ): Boolean {
        assertAccountRegistered(account)
        // Every contacts change is read by `pendingRows`, which projects the version the guards below
        // need; a change without one could only come from a reader that does not exist.
        val version = checkNotNull(change.version) { "contact ${change.rowId} has no version to guard on" }
        val rawContactsUri = RawContacts.CONTENT_URI.forSyncAdapter(account)
        val identity = ContentValues().apply {
            put(RawContacts.SOURCE_ID, key)
            // A source its own vCard gave no UID has none here either, and null says that better than
            // an empty string would.
            if (uid.isNotBlank()) put(RawContacts.SYNC1, uid) else putNull(RawContacts.SYNC1)
            if (etag != null) put(RawContacts.SYNC2, etag) else putNull(RawContacts.SYNC2)
            put(RawContacts.SYNC3, collection.id)
        }
        // The identity and the ETag are not a claim about the row's state, so they are written
        // unconditionally: the server does hold this body under this name, and the next run's
        // `If-Match` has to be conditioned on what it actually holds. Storing them even when the row
        // moved is also what keeps a created contact from being fetched back as a second row: without
        // an href of its own, the listing's copy of the body just PUT would insert one.
        resolver.update(rawContactsUri, identity, "${RawContacts._ID}=?", arrayOf(change.rowId.toString()))

        // The photo baseline is read before the batch rather than after it: the batch leaves the photo
        // row alone, so a digest taken now still describes the bytes the contact carries when it
        // commits, and a digest taken after it could not be written under the version the batch
        // matches on.
        //
        // A contact without a photo is recorded as having none, so that the next run's adoption does
        // not find it baseline-less and read it again; a photo whose bytes cannot be read keeps the
        // baseline it had rather than being recorded as absent.
        val hasPhoto = contactsWithPhotoRow(account, listOf(change.rowId)).isNotEmpty()
        val baseline = if (hasPhoto) displayPhotoBytes(account, change.rowId)?.let { photoDigest(it) } else NO_PHOTO

        val batch = ArrayList<ContentProviderOperation>()
        // `DIRTY` is the claim that the row is what the server holds, and only a row that did not move
        // since it was serialised may make it. It is the first operation because every operation after
        // it writes `Data`, which moves the very version this one matches on.
        batch += ContentProviderOperation.newUpdate(rawContactsUri)
            .withSelection(
                "${RawContacts._ID}=? AND ${RawContacts.VERSION}=?",
                arrayOf(change.rowId.toString(), version.toString()),
            )
            .withValue(RawContacts.DIRTY, 0)
            .apply { if (baseline != null) withValue(RawContacts.SYNC4, baseline) }
            .withExpectedCount(1)
            .build()
        // The row is now what the server holds, so the copy it keeps must be too: the next edit is
        // patched onto these bytes, and the rows beside them are re-derived so that they carry the
        // handles this text yields. Without that, a row the editor inserted would go on looking like a
        // property nothing in the source matches, and its parameters would be rebuilt from columns on
        // every edit after this one. They share the batch with the claim above so that a body the
        // guard rejected cannot replace rows that describe a newer edit.
        batch += rebaselineOperations(account, collection, change.rowId, key, body)

        try {
            resolver.applyBatch(authority, batch)
        } catch (e: OperationApplicationException) {
            // Zero rows matched the guard, so nothing of this batch is on disk: the user edited during
            // the PUT, the edit is still pending, and the next run sends it under the ETag stored
            // above.
            Log.w(
                LOG_TAG,
                "${collection.id}: contact ${change.rowId} changed while it was uploaded, " +
                    "keeping the edit pending for the next run",
            )
            return false
        }
        return true
    }

    override fun purgeDeleted(account: Account, collection: DavCollection, change: LocalChange) {
        assertAccountRegistered(account)
        // A real delete, not a tombstone: the sync-adapter URI is what makes the provider remove the
        // row and, with it, everything hanging off it. The Account is repeated in the selection
        // because a row created and deleted again before any run never reached a Collection, and an id
        // from a raw contact of another Account is not this Account's to delete.
        resolver.delete(
            RawContacts.CONTENT_URI.forSyncAdapter(account),
            "${RawContacts._ID}=? AND ${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.ACCOUNT_TYPE}=?",
            arrayOf(change.rowId.toString(), account.name, account.type),
        )
    }

    // `sent` is not consulted: RawContacts.VERSION was read in the same snapshot that produced
    // [change], so it tells a moved row from a still one whether or not a body reached the server.
    override fun revertLocalChange(
        account: Account,
        collection: DavCollection,
        change: LocalChange,
        sent: Boolean,
    ): Boolean {
        assertAccountRegistered(account)
        val version = checkNotNull(change.version) { "contact ${change.rowId} has no version to guard on" }
        val values = ContentValues().apply {
            put(RawContacts.DELETED, 0)
            put(RawContacts.DIRTY, 0)
            putNull(RawContacts.SYNC2)
            // A create the server answered 412 to is a name that already exists — the lost answer to an
            // earlier attempt of ours. Adopting it is what stops the next attempt from minting a second
            // contact, and SYNC3 goes with it because a row with an href and no Collection is one no
            // listing, diff or fetch of ours can see again.
            if (change.key != null) {
                put(RawContacts.SOURCE_ID, change.key)
                put(RawContacts.SYNC3, collection.id)
            }
        }
        // One request per resource, because a resource is one row here: a tombstone is the raw contact,
        // and a locally created contact is a raw contact the provider will not delete. The version
        // guards it because an edit made while the server was refusing the older body is newer than
        // the refusal: dropping it here would be the lost update the refusal exists to prevent.
        val reverted = resolver.update(
            RawContacts.CONTENT_URI.forSyncAdapter(account),
            values,
            "${RawContacts._ID}=? AND ${RawContacts.VERSION}=?",
            arrayOf(change.rowId.toString(), version.toString()),
        )
        if (reverted == 0) {
            Log.w(
                LOG_TAG,
                "${collection.id}: contact ${change.rowId} changed while it was uploaded, " +
                    "withholding the revert and keeping the edit pending for the next run",
            )
            return false
        }
        return true
    }

    /**
     * The contacts a revert left behind: clean, keyed, and with no ETag.
     *
     * `revertLocalChange` clears `DIRTY` and `DELETED` and nulls `SYNC2`, and nothing else leaves a
     * clean keyed row without an ETag except a server that named none for it. Groups are never
     * reverted, so they are not read here.
     *
     * Nothing is ever owed a source alone. A contact's upload patches the vCard this app stored
     * beside it where there is one and is written from the `Data` rows where there is not, so a
     * contact with no stored vCard still has a body to send and never waits for one
     * ([#35](https://github.com/t1nk333r/davkeep/issues/35) is the calendar's shape, where a keyed
     * resource with no stored text can be serialised no other way).
     */
    override fun restorePlan(account: Account, collection: DavCollection): RestorePlan {
        val keys = LinkedHashSet<String>()
        resolver.query(
            RawContacts.CONTENT_URI.forSyncAdapter(account),
            arrayOf(RawContacts.SOURCE_ID),
            "${contactSelection()} AND ${RawContacts.DIRTY}=0 AND ${RawContacts.SYNC2} IS NULL",
            collectionArgs(account, collection),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let { keys += it }
        }
        return RestorePlan(full = keys)
    }

    /**
     * Writes the server's copy over a contact whose edit has been given up.
     *
     * [restorePlan] names no contact conflicted of its own — a contact whose vCard this app never
     * stored still serialises from its `Data` rows, so no edit here is unsendable for want of a
     * base. What reaches this is the engine's other finding: a dirty contact with no ETag on a
     * server that names one, whose stored vCard is therefore the version a `412` already said the
     * server had moved past. Patching the edit onto that and sending it under the ETag just asked
     * for would carry the stale card back over the newer one with nothing to refuse it, which is
     * the lost update this whole class is written around. So the server's copy is written whole,
     * through the same batch a listing's copy takes, minus the `DIRTY` guard for this key alone,
     * and the run reports the conflict.
     *
     * A copy that does not parse resolves it the only other way: the row goes, with the edit, and
     * the conflict is reported. The server still holds the card, and a body that becomes readable
     * is fetched by the next listing that names it; left here the row would ask for the same body
     * on every run and hold the edit out of every upload while doing so.
     */
    override fun replaceOverEdit(
        account: Account,
        collection: DavCollection,
        key: String,
        text: String,
        etag: String?,
    ): Boolean {
        val written = try {
            write(account, collection, mapOf(key to text), mapOf(key to etag), givenUp = setOf(key))
        } catch (e: OperationApplicationException) {
            // The row moved under both attempts: nothing of either is on disk, and the next run
            // meets the contact as it is then.
            Log.i(LOG_TAG, "${collection.id}: $key moved while the server's copy was being written; nothing was written")
            return false
        }
        if (key in written) return true
        if (parseVCard(text) != null) return false
        val deleted = resolver.delete(
            RawContacts.CONTENT_URI.forSyncAdapter(account),
            "${contactSelection()} AND ${RawContacts.SOURCE_ID}=?",
            collectionArgs(account, collection) + key,
        )
        if (deleted > 0) {
            Log.w(LOG_TAG, "${collection.id}: $key: the phone cannot hold the server's copy; the row and the edit on it are removed")
        }
        return deleted > 0
    }

    /**
     * Removes a clean contact whose server copy the restore fetched and could not parse.
     *
     * The row is what a revert left — clean, named, no ETag, the phone's rejected card — and the
     * server's version cannot land on it, so every run would ask for it again. It goes the way a
     * listing gives no row to a card it cannot read; the server still holds it, and a body that
     * becomes readable is fetched by the next listing that names it. The `DIRTY=0` in the selection
     * is the whole guard: a contact is one row, so an edit made since the plan was read keeps it.
     */
    override fun discardUnwritable(account: Account, collection: DavCollection, key: String): Int {
        assertAccountRegistered(account)
        val deleted = deleteClean(account, collection, key)
        if (deleted > 0) {
            Log.w(LOG_TAG, "${collection.id}: $key: the phone cannot hold the server's copy; its row is removed")
        }
        return deleted
    }

    /** Deletes the contact stored under [key] if it holds no unsent change. */
    private fun deleteClean(account: Account, collection: DavCollection, key: String): Int =
        resolver.delete(
            RawContacts.CONTENT_URI.forSyncAdapter(account),
            "${contactSelection()} AND ${RawContacts.SOURCE_ID}=? AND ${RawContacts.DIRTY}=0",
            collectionArgs(account, collection) + key,
        )

    /**
     * The contact the server answered `404` for by name — the shape [deleteMissing] uses for a
     * clean stale contact, narrowed to the one key.
     *
     * A dirty row is an edit made since the server answered, so it is not deleted; but it stops
     * claiming a name the server no longer has, because an `If-Match` against that name can only
     * fail. Without its `SOURCE_ID` the contact is what it now is — one this phone has and the
     * server does not — and step U sends it as a create. A tombstone keeps its name: the `DELETE`
     * it still owes is addressed by it.
     */
    override fun resourceGone(account: Account, collection: DavCollection, key: String): Int {
        assertAccountRegistered(account)
        val deleted = deleteClean(account, collection, key)
        if (deleted > 0) {
            Log.i(LOG_TAG, "${collection.id}: $key is no longer on the server; its rows are removed")
        }
        val unnamed = resolver.update(
            RawContacts.CONTENT_URI.forSyncAdapter(account),
            ContentValues().apply {
                putNull(RawContacts.SOURCE_ID)
                putNull(RawContacts.SYNC2)
            },
            "${contactSelection()} AND ${RawContacts.SOURCE_ID}=? AND ${RawContacts.DIRTY}=1",
            collectionArgs(account, collection) + key,
        )
        if (unnamed > 0) {
            Log.i(LOG_TAG, "${collection.id}: $key is no longer on the server; the edit on it is a create again")
        }
        return deleted
    }

    // ------------------------------------------------------------------ upload rows

    /**
     * The rows of one pending-edit flavour, as the engine hands them to `serialize`.
     *
     * `VERSION` comes along because it is what guards the clear: the value read here is compared
     * against the row's at the moment the server answers, and a row that moved in between keeps its
     * `DIRTY` flag.
     */
    private fun pendingRows(account: Account, kind: ChangeKind, selection: String, args: Array<String>): List<LocalChange> =
        ArrayList<LocalChange>().also { changes ->
            resolver.query(
                RawContacts.CONTENT_URI.forSyncAdapter(account),
                arrayOf(
                    RawContacts._ID,
                    RawContacts.SOURCE_ID,
                    RawContacts.SYNC2,
                    RawContacts.SYNC1,
                    RawContacts.VERSION,
                ),
                selection,
                args,
                "${RawContacts._ID} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    changes += LocalChange(
                        rowId = cursor.getLong(0),
                        kind = kind,
                        key = cursor.getString(1),
                        etag = cursor.getString(2),
                        uid = cursor.getString(3),
                        version = cursor.getLong(4),
                    )
                }
            }
        }

    /**
     * The UID for a contact made on the phone, minted once and stored before the first attempt.
     *
     * Stored first so that a `PUT` whose answer was lost is retried under the same name rather than
     * making a second contact. Bare, not `urn:uuid:`: the read path stores a `UID` exactly as it was
     * given and strips that prefix from a `MEMBER` reference, so either spelling resolves to this row.
     */
    private fun mintUid(account: Account, rowId: Long): String {
        val uid = UUID.randomUUID().toString()
        resolver.update(
            RawContacts.CONTENT_URI.forSyncAdapter(account),
            ContentValues().apply { put(RawContacts.SYNC1, uid) },
            "${RawContacts._ID}=?",
            arrayOf(rowId.toString()),
        )
        return uid
    }

    /** The source vCard the row keeps, or null when it has none — a contact made on the phone. */
    private fun verbatimText(account: Account, rowId: Long): String? =
        resolver.query(
            Data.CONTENT_URI.forSyncAdapter(account),
            arrayOf(Data.DATA1),
            "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?",
            arrayOf(rowId.toString(), VCARD_MIME_TYPE),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /**
     * The contact's rows as [patchContact] reads them: each kind's columns as text, and the handle of
     * the source property the row came from (`Data.SYNC1`), which is what tells a property the user
     * deleted from one the rows have never known about.
     *
     * Every `data` column is projected, null or not, because the patcher compares a row against what
     * the source derived column by column: a column that is simply absent from the projection could
     * not tell a value the user cleared from one that is not part of the kind.
     */
    private fun dataRows(account: Account, rowId: Long): List<DataRow> {
        val rows = ArrayList<DataRow>()
        resolver.query(
            Data.CONTENT_URI.forSyncAdapter(account),
            arrayOf(Data.MIMETYPE, Data.SYNC1) + DATA_COLUMNS,
            "${Data.RAW_CONTACT_ID}=?",
            arrayOf(rowId.toString()),
            null,
        )?.use { cursor ->
            val indices = DATA_COLUMNS.map { cursor.getColumnIndexOrThrow(it) }
            while (cursor.moveToNext()) {
                val values = LinkedHashMap<String, String?>()
                for ((position, column) in DATA_COLUMNS.withIndex()) {
                    values[column] = cursor.getString(indices[position])
                }
                rows += DataRow(cursor.getString(0), values, cursor.getString(1))
            }
        }
        return rows
    }

    /**
     * What the contact's photo row says about `PHOTO`.
     *
     * "Unchanged" is the photo's bytes hashing to what [refreshPhotoBaselines] recorded the last time
     * this app wrote or sent them. The bytes are the provider's re-encode, never the source's, so the
     * comparison is against the previous re-encode rather than against anything the server sent — but
     * a re-encode the user has not touched is byte-identical to itself, and that is the whole
     * question being asked.
     *
     * A row without a baseline, or one whose bytes no longer hash to it, is rebuilt from the display
     * photo. A row that is gone takes the source's property with it.
     *
     * The baseline used to be the row's `DATA_VERSION`, which was wrong in a way no reading of the
     * code showed: AOSP's `data_updated` trigger increments `data_version` on *every* update of a
     * `Data` row, including the sync-adapter write that stores the baseline, so the recorded value was
     * stale the moment it was written and every photo counted as edited. Measured on a device — a
     * freshly synced photo row read `data_version=1, data_sync2=0` — and the consequence was a
     * name-only edit replacing the server's 8932-character `PHOTO` with a 6660-character re-encode.
     * A hash of the bytes cannot invalidate itself by being stored: see issue #31.
     *
     * It is kept on the raw contact and not on the row it describes because [markUploaded] writes it
     * inside the batch guarded by `RawContacts.VERSION`: a sync-adapter update of a raw-contact column
     * moves no version, while a `Data` update moves the parent's, so a baseline stored on the photo
     * row would invalidate the very guard it commits under.
     *
     * A row still carrying the old `Data.SYNC2` baseline and no new one is the one-time upgrade
     * window: a contact synced before the digest existed and dirtied before the first run that would
     * have adopted one — adoption skips a dirty contact, since its photo may be the edit. The old
     * baseline was written as the row's `DATA_VERSION` and then bumped once by its own write, so an
     * untouched row reads exactly `DATA_VERSION == SYNC2 + 1`, and every editor write to the row since
     * moves it further. That is the one fact the old bookkeeping does prove, and it is read here for
     * what it proves: the row's bytes are the ones the fetch or the last upload left, so the source's
     * `PHOTO` is kept and [markUploaded] records the digest afterwards. Any other reading — no old
     * baseline, a row moved since — rebuilds the photo, as before.
     */
    private fun photoEdit(account: Account, rowId: Long): PhotoEdit {
        var present = false
        var untouchedSinceOldBaseline = false
        resolver.query(
            Data.CONTENT_URI.forSyncAdapter(account),
            arrayOf(Data.DATA_VERSION, Data.SYNC2),
            "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?",
            arrayOf(rowId.toString(), Photo.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                present = true
                val old = cursor.getString(1)?.toLongOrNull()
                untouchedSinceOldBaseline = old != null && cursor.getLong(0) == old + 1
            }
        }
        // No row at all: the photo is gone, and so is the source's property.
        if (!present) return PhotoEdit.Dropped
        val baseline = resolver.query(
            RawContacts.CONTENT_URI.forSyncAdapter(account),
            arrayOf(RawContacts.SYNC4),
            "${RawContacts._ID}=?",
            arrayOf(rowId.toString()),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (baseline == null && untouchedSinceOldBaseline) return PhotoEdit.Kept
        val bytes = displayPhotoBytes(account, rowId)
        if (bytes == null) {
            // A photo this app cannot spell is not a reason to delete one the user did not touch.
            Log.w(LOG_TAG, "contact $rowId: photo bytes are not readable, keeping the server's photo")
            return PhotoEdit.Kept
        }
        if (baseline != null && baseline == photoDigest(bytes)) return PhotoEdit.Kept
        return PhotoEdit.Rebuilt(bytes)
    }

    /**
     * The photo bytes' identity, as stored in `RawContacts.SYNC4`.
     *
     * SHA-256 and not the provider's own bookkeeping: a digest of the bytes is unchanged by the
     * writes this app makes around them, which is exactly what the previous baseline was not.
     */
    private fun photoDigest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * The bytes of the display photo, or null when there are none to read.
     *
     * The file the provider keeps for an image larger than a thumbnail first, because it is the one
     * the user's editor wrote; the `DATA15` thumbnail second, because a photo the provider never
     * promoted to a file is still a photo.
     */
    private fun displayPhotoBytes(account: Account, rowId: Long): ByteArray? {
        val photoUri = RawContacts.CONTENT_URI.buildUpon()
            .appendPath(rowId.toString())
            .appendPath(RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
            .build()
        val file = runCatching { resolver.openAssetFileDescriptor(photoUri.forSyncAdapter(account), "r") }
            .getOrNull()
        if (file != null) {
            file.use { descriptor ->
                runCatching { descriptor.createInputStream()?.use { it.readBytes() } }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
        }
        return resolver.query(
            Data.CONTENT_URI.forSyncAdapter(account),
            arrayOf(Photo.PHOTO),
            "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?",
            arrayOf(rowId.toString(), Photo.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * What the contact's own membership rows say about `CATEGORIES`.
     *
     * The rows this app wrote from a group's member list are excluded: belonging to a group is that
     * group's relation, not this contact's, and the group re-writes it when the group changes. What
     * remains is the contact's own — the rows the editor inserts carry no provenance at all — resolved
     * to the group titles a `CATEGORIES` value spells.
     */
    private fun categoryEdit(account: Account, collection: DavCollection, rowId: Long): CategoryEdit {
        val groupIds = ArrayList<Long>()
        resolver.query(
            Data.CONTENT_URI.forSyncAdapter(account),
            arrayOf(GroupMembership.GROUP_ROW_ID),
            "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=? AND (${Data.DATA2} IS NULL OR ${Data.DATA2}=?)",
            arrayOf(rowId.toString(), GroupMembership.CONTENT_ITEM_TYPE, MEMBERSHIP_FROM_CATEGORIES),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) groupIds += cursor.getLong(0)
        }
        return CategoryEdit(
            titles = groupTitles(account, collection, "${Groups._ID} IN (${groupIds.joinToString(",") { "?" }})", groupIds.map { it.toString() }),
            // Every group title, not only the ones this contact belongs to: a source value naming a
            // group that exists is one the read path resolved and showed the user, so its absence from
            // the rows is a deletion — and a value naming no group is the one to preserve.
            groupTitles = groupTitles(account, collection, null, emptyList()).toSet(),
        )
    }

    /** The titles of the Collection's groups, optionally narrowed by [selection]. */
    private fun groupTitles(
        account: Account,
        collection: DavCollection,
        selection: String?,
        args: List<String>,
    ): List<String> {
        if (selection != null && args.isEmpty()) return emptyList()
        val titles = ArrayList<String>()
        val scope = "${Groups.ACCOUNT_NAME}=? AND ${Groups.ACCOUNT_TYPE}=? AND ${Groups.SYNC3}=?"
        val scopeArgs = collectionArgs(account, collection)
        resolver.query(
            Groups.CONTENT_URI.forSyncAdapter(account),
            arrayOf(Groups.TITLE),
            if (selection == null) scope else "$scope AND $selection",
            if (selection == null) scopeArgs else scopeArgs + args,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { titles += it }
        }
        return titles
    }

    /**
     * The operations that rewrite the row's stored vCard to [body] and re-derive the rows beside it
     * from the same text.
     *
     * Operations rather than a batch of their own: they belong to [markUploaded]'s batch, behind the
     * guard that says the row is still the one whose body was sent, and a copy newer than its rows
     * would make the next edit look like it deleted everything the rows no longer spell.
     *
     * Two kinds of row survive the replace. A photo row is kept, not re-derived: the sent `PHOTO` may
     * be a link the read path fetched, which cannot be fetched back from here, and its bytes are what
     * the user's editor wrote. A membership row is kept because the mapping does not derive one: the
     * `CATEGORIES` the upload carried came from these rows, and re-deriving would either lose the ones
     * the collection has no group for or delete the memberships outright.
     */
    private fun rebaselineOperations(
        account: Account,
        collection: DavCollection,
        rowId: Long,
        key: String,
        body: String,
    ): List<ContentProviderOperation> {
        val parsed = parseVCard(body)
        if (parsed == null) {
            // Our own output failed to parse, which is a bug rather than input: keeping the old copy is
            // the safe half of the change, since the rows still describe something the server accepted.
            Log.e(LOG_TAG, "${collection.id}: uploaded body for contact $rowId does not parse back, not re-baselining")
            return emptyList()
        }
        val dataUri = Data.CONTENT_URI.forSyncAdapter(account)
        val ref = RowRef.existing(rowId)
        val operations = ArrayList<ContentProviderOperation>()
        operations += ContentProviderOperation.newDelete(dataUri)
            .withSelection(
                "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE} NOT IN (?, ?)",
                arrayOf(rowId.toString(), Photo.CONTENT_ITEM_TYPE, GroupMembership.CONTENT_ITEM_TYPE),
            )
            .build()
        for (row in parsed.rows) operations += dataInsert(dataUri, ref, row)
        operations += dataInsert(dataUri, ref, verbatimRow(Resource(key, body, parsed), collection))
        return operations
    }

    /**
     * Records what the photos a fetch just wrote look like: `RawContacts.SYNC4` holds a digest of the
     * photo's bytes, and the next upload finding the same digest is what lets it copy the server's own
     * `PHOTO` through untouched instead of replacing it with a re-encode.
     *
     * Read after the rows are written, because the bytes that matter are the ones the provider kept —
     * it re-encodes an image it is given, so what was handed to it is not what will be read back. That
     * second read cannot share the fetch's transaction, so each write carries the `RawContacts.VERSION`
     * the photo row was found under: a miss is a contact the user edited between the two batches, and
     * leaving its baseline absent only costs the photo being sent once more.
     *
     * The upload path records its own baseline inside [markUploaded]'s guarded batch, which is why
     * this runs for the fetch alone.
     */
    private fun refreshPhotoBaselines(account: Account, rowIds: List<Long>) {
        if (rowIds.isEmpty()) return
        val placeholders = rowIds.joinToString(",") { "?" }
        // The `Data` view exposes the parent raw contact's version, so the photo row and the version
        // its baseline is written under come out of one query.
        val versions = LinkedHashMap<Long, Long>()
        resolver.query(
            Data.CONTENT_URI.forSyncAdapter(account),
            arrayOf(Data.RAW_CONTACT_ID, RawContacts.VERSION),
            "${Data.RAW_CONTACT_ID} IN ($placeholders) AND ${Data.MIMETYPE}=?",
            rowIds.map { it.toString() }.toTypedArray() + Photo.CONTENT_ITEM_TYPE,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) versions[cursor.getLong(0)] = cursor.getLong(1)
        }
        if (versions.isEmpty()) return
        val rawContactsUri = RawContacts.CONTENT_URI.forSyncAdapter(account)
        val batch = ArrayList<ContentProviderOperation>()
        for ((contactId, version) in versions) {
            // Unreadable bytes leave the baseline alone rather than writing one that describes
            // nothing: the next comparison then rebuilds the photo, which is the safe direction.
            val digest = displayPhotoBytes(account, contactId)?.let { photoDigest(it) } ?: continue
            // DIRTY as well as VERSION: the version above is read after the fetch's batch, so an editor
            // that saved a new photo in between moved the version *before* it was read, and the version
            // alone would then match — recording the user's new photo as what the server holds, and
            // the upload that should send it would copy the server's old one through instead.
            batch += ContentProviderOperation.newUpdate(rawContactsUri)
                .withSelection(
                    "${RawContacts._ID}=? AND ${RawContacts.VERSION}=? AND " +
                        "${RawContacts.DIRTY}=0 AND ${RawContacts.DELETED}=0",
                    arrayOf(contactId.toString(), version.toString()),
                )
                .withValue(RawContacts.SYNC4, digest)
                .build()
        }
        if (batch.isEmpty()) return
        resolver.applyBatch(authority, batch)
    }

    // ------------------------------------------------------------------ rows

    /**
     * Writes one contact's identity and body, and returns the row the rest of the batch refers to.
     *
     * The ETag goes in the same operation as the body: an ETag persisted on its own, or a body
     * without one, would make the next run's diff against [localItems] describe something that is
     * not there.
     */
    private fun appendContact(
        account: Account,
        collection: DavCollection,
        resource: Resource,
        etag: String?,
        dataUri: Uri,
        rawContactsUri: Uri,
        batch: MutableList<ContentProviderOperation>,
        stats: UpsertStats,
        known: KnownRows,
        /** Collects the contacts whose photo row this batch writes, for the baseline pass after it. */
        photoContacts: MutableList<RowRef>,
        /**
         * Whether an edit on the existing row has been given up, which only [replaceOverEdit] says.
         * It lifts the hold on a dirty row and the `DIRTY=0` guard below; a tombstone is still
         * refused, being a change the server has answered for.
         */
        overEdit: Boolean = false,
    ): RowRef? {
        val existing = known.contactIdsBySourceId[resource.key]
        // The phone's version of a row the user has edited or deleted since this listing was fetched is
        // the newer one, server or no server. The engine keeps such keys out of `wanted` for the same
        // reason; this is the second line, because the two can disagree in the seconds between.
        if (existing != null && (existing.deleted || (existing.dirty && !overEdit))) {
            stats.heldBack++
            Log.i(LOG_TAG, "${collection.id}/${resource.key}: has an edit waiting to be uploaded, not overwriting it")
            return null
        }

        val sync = ContentValues().apply {
            put(RawContacts.SOURCE_ID, resource.key)
            put(RawContacts.SYNC3, collection.id)
            put(RawContacts.SYNC1, resource.parsed.uid)
            if (etag != null) put(RawContacts.SYNC2, etag) else putNull(RawContacts.SYNC2)
            // What the server says is not an edit waiting to be uploaded.
            put(RawContacts.DIRTY, 0)
        }

        val ref: RowRef
        if (existing == null) {
            val index = batch.size
            batch += ContentProviderOperation.newInsert(rawContactsUri)
                .withValues(ContentValues(sync).apply {
                    put(RawContacts.ACCOUNT_NAME, account.name)
                    put(RawContacts.ACCOUNT_TYPE, account.type)
                })
                .build()
            ref = RowRef.pending(index)
        } else {
            ref = RowRef.existing(existing.id)
            // The server's copy may only be written over the row the snapshot read: the delete and the
            // inserts below depend on this row being the one that was clean, and an expected-count
            // miss rolls all three back together. `DIRTY` and `DELETED` are matched as well as the
            // version because a star the user toggled dirties a row without moving its version — the
            // one caller that has given the edit up matches on the version alone, the flag being the
            // very thing it is here to clear.
            batch += ContentProviderOperation.newUpdate(rawContactsUri)
                .withSelection(
                    "${RawContacts._ID}=? AND ${RawContacts.VERSION}=?" +
                        (if (overEdit) "" else " AND ${RawContacts.DIRTY}=0") +
                        " AND ${RawContacts.DELETED}=0",
                    arrayOf(existing.id.toString(), existing.version.toString()),
                )
                .withValues(sync)
                .withExpectedCount(1)
                .build()
            // The server is the source of truth, so nothing the previous run wrote may survive: a
            // phone number the server has since dropped would otherwise stay on the contact forever.
            // The one exception is a membership a group vCard wrote — belonging to a group is not
            // this contact's to own, and the group re-writes that relation when the group changes.
            batch += ContentProviderOperation.newDelete(dataUri)
                .withSelection(
                    "${Data.RAW_CONTACT_ID}=? AND NOT (${Data.MIMETYPE}=? AND ${Data.DATA2}=?)",
                    arrayOf(existing.id.toString(), GroupMembership.CONTENT_ITEM_TYPE, MEMBERSHIP_FROM_GROUP),
                )
                .build()
        }

        for (row in resource.parsed.rows) {
            batch += dataInsert(dataUri, ref, row)
        }
        photoRow(resource, collection, stats)?.let { bytes ->
            batch += ContentProviderOperation.newInsert(dataUri)
                .withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                .withValue(Photo.PHOTO, bytes)
                .withRowRef(Data.RAW_CONTACT_ID, ref)
                .build()
            // The snapshot this row needs can only be taken once it exists and the provider has given
            // it a version.
            photoContacts += ref
        }
        batch += dataInsert(dataUri, ref, verbatimRow(resource, collection))
        stats.unmappedProperties += resource.parsed.unmappedProperties
        return ref
    }

    /**
     * Writes a group vCard's row, and returns the row memberships point at.
     *
     * A group is not a contact: it has no raw contact row, so nothing of its vCard can be stored
     * beside it the way [verbatimRow] stores a contact's. What it does carry is kept — its title, its
     * href, and one membership row per member that also exists locally.
     */
    private fun appendGroup(
        account: Account,
        collection: DavCollection,
        resource: Resource,
        etag: String?,
        dataUri: Uri,
        groupsUri: Uri,
        batch: MutableList<ContentProviderOperation>,
        known: KnownRows,
    ): RowRef {
        val sync = ContentValues().apply {
            put(Groups.SOURCE_ID, resource.key)
            put(Groups.SYNC3, collection.id)
            put(Groups.TITLE, resource.parsed.displayName)
            if (etag != null) put(Groups.SYNC2, etag) else putNull(Groups.SYNC2)
            put(Groups.DIRTY, 0)
        }

        val existing = known.groupIdsBySourceId[resource.key]
        return if (existing == null) {
            val index = batch.size
            batch += ContentProviderOperation.newInsert(groupsUri)
                .withValues(ContentValues(sync).apply {
                    put(Groups.ACCOUNT_NAME, account.name)
                    put(Groups.ACCOUNT_TYPE, account.type)
                    // A group nobody can see is a group the Contacts app will not offer.
                    put(Groups.GROUP_VISIBLE, 1)
                })
                .build()
            RowRef.pending(index)
        } else {
            batch += ContentProviderOperation.newUpdate(groupsUri)
                .withSelection("${Groups._ID}=?", arrayOf(existing.toString()))
                .withValues(ContentValues(sync).apply { put(Groups.GROUP_VISIBLE, 1) })
                .build()
            // This group's previous members are replaced rather than added to: a member the server
            // has since taken out of the group must not stay in it. Only this direction's rows are
            // touched, so the memberships the contacts' own categories produced are left alone.
            batch += ContentProviderOperation.newDelete(dataUri)
                .withSelection(
                    "${Data.MIMETYPE}=? AND ${GroupMembership.GROUP_ROW_ID}=? AND ${Data.DATA2}=?",
                    arrayOf(GroupMembership.CONTENT_ITEM_TYPE, existing.toString(), MEMBERSHIP_FROM_GROUP),
                )
                .build()
            RowRef.existing(existing)
        }
    }

    /**
     * One `Data` row of a mapped kind.
     *
     * The handle the source property landed under goes into `SYNC1`, the sync adapter's own column:
     * the upload path matches a row against the text it patches by it, and it is absent exactly when
     * the row is one the mapping never derived a property for.
     */
    private fun dataInsert(dataUri: Uri, ref: RowRef, row: DataRow): ContentProviderOperation {
        val insert = ContentProviderOperation.newInsert(dataUri)
            .withValues(row.toContentValues())
            .withValue(Data.MIMETYPE, row.mimeType)
            .withRowRef(Data.RAW_CONTACT_ID, ref)
        row.handle?.let { insert.withValue(Data.SYNC1, it) }
        return insert.build()
    }

    /**
     * The row as the provider wants it.
     *
     * Values travel as text so that a row a cursor returned and one the mapping derived can be
     * compared without a type table (`VCardPatch.kt`), so the few columns the provider stores as
     * numbers have to be handed back as numbers: its data-kind handlers read a type column as an
     * integer and refuse a row without one.
     *
     * Which column that is depends on the MIME type, not on the column name. `Phone.TYPE`,
     * `Email.TYPE` and `Event.TYPE` are all the string `data2`, and so is `StructuredName`'s
     * `GIVEN_NAME` — keyed on the name alone, a contact called Test has a given name parsed as an
     * integer, which is a crash on every name this app writes.
     */
    private fun DataRow.toContentValues(): ContentValues = ContentValues().apply {
        val integerColumn = INTEGER_COLUMN_BY_MIME_TYPE[mimeType]
        for ((column, value) in values) {
            when {
                value == null -> putNull(column)
                column == integerColumn -> value.toIntOrNull()?.let { put(column, it) } ?: put(column, value)
                else -> put(column, value)
            }
        }
    }

    /**
     * The value for [column]: a row id, or a reference to the result of an earlier operation in the
     * same batch — the only way to write a row whose parent does not exist until the batch runs.
     */
    private fun ContentProviderOperation.Builder.withRowRef(column: String, ref: RowRef): ContentProviderOperation.Builder {
        val id = ref.id
        if (id != null) return withValue(column, id)
        val index = ref.batchIndex
        checkNotNull(index) { "row reference has neither an id nor a batch index" }
        return withValueBackReference(column, index)
    }

    /**
     * A membership row: DATA1 is the group, and DATA2 records which side of the relation wrote it.
     * The marker is this app's own convention — the platform spends DATA1 on the group row id and
     * computes the group's source id from a join — and it exists because the two directions that can
     * write a membership refresh independently of each other.
     */
    private fun membershipOperation(
        dataUri: Uri,
        contact: RowRef,
        group: RowRef,
        provenance: String,
    ): ContentProviderOperation =
        ContentProviderOperation.newInsert(dataUri)
            .withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
            .withRowRef(Data.RAW_CONTACT_ID, contact)
            .withRowRef(GroupMembership.GROUP_ROW_ID, group)
            .withValue(Data.DATA2, provenance)
            .build()

    /**
     * The resource exactly as the server sent it.
     *
     * A contact editor can only show what has a column, so anything without a mapping — a property
     * this version predates, the `ADR` the platform reshapes, the exact spelling of a name — would
     * otherwise be gone for good. One row buys that back, which is what makes read-only v1 lossless
     * at rest and a future upload able to send back more than it was given.
     *
     * DATA2 and DATA3 are the summary and detail columns `res/xml/contacts.xml` declares for this
     * data kind, so the row has something short and honest to show: where the contact came from.
     */
    private fun verbatimRow(resource: Resource, collection: DavCollection): DataRow =
        DataRow(
            VCARD_MIME_TYPE,
            mapOf(
                Data.DATA1 to resource.text,
                Data.DATA2 to (collection.displayName ?: collection.id),
                Data.DATA3 to resource.key,
            ),
        )

    /**
     * The bytes for the photo row, from bytes the vCard carries or from a URL it points at.
     *
     * The bytes written are the display-size image: the provider derives both the thumbnail and the
     * display photo file from them, and only creates that file when what it is given is larger than
     * a thumbnail.
     */
    private fun photoRow(resource: Resource, collection: DavCollection, stats: UpsertStats): ByteArray? {
        val source = resource.parsed.photo ?: return null
        val bytes = when (source) {
            is PhotoSource.Inline -> source.bytes
            is PhotoSource.Link -> linkedPhotoBytes(source, collection, resource.key, stats) ?: return null
        }
        val image = PhotoImages.toDisplayPhoto(bytes, displayPhotoDimension())
        if (image == null) {
            stats.unreadablePhotos++
            Log.w(LOG_TAG, "${collection.id}/${resource.key}: photo does not decode, skipping it")
            return null
        }
        return image
    }

    /**
     * Fetches a photo the vCard only points at.
     *
     * The URL is data the server chose, so it is fetched only when it names the same Origin as the
     * Collection: anything else would send this Account's Credentials to a host the data itself
     * named. A URL that fails that test is skipped and counted — never fetched, and never quietly
     * treated as though the vCard had no photo.
     */
    private fun linkedPhotoBytes(
        source: PhotoSource.Link,
        collection: DavCollection,
        key: String,
        stats: UpsertStats,
    ): ByteArray? {
        val target = PhotoImages.resolve(collection.url, source.reference)
        if (target == null || !PhotoImages.isSameOrigin(collection.url, target)) {
            stats.foreignPhotos++
            Log.w(LOG_TAG, "${collection.id}/$key: refusing a photo that is not on the Origin")
            return null
        }
        val fetcher = photoFetcher
        if (fetcher == null) {
            stats.unfetchedPhotos++
            return null
        }
        return fetcher.fetch(target.toString())
    }

    // ------------------------------------------------------------------ lookups

    /**
     * The rows of this Collection that the names in one batch can mean.
     *
     * Read once for the batch, before any of it is built — which is what makes it correct as well as
     * cheap. Rows an earlier batch of this run wrote are committed and therefore visible; rows this
     * batch writes are not, and those are the batch's own maps and [RowRef.pending], both of which are
     * consulted first. A direction this batch does not name costs no read at all.
     */
    private fun knownRows(
        account: Account,
        collection: DavCollection,
        contacts: List<Resource>,
        groups: List<Resource>,
    ): KnownRows {
        val rawContactsUri = RawContacts.CONTENT_URI.forSyncAdapter(account)
        val groupsUri = Groups.CONTENT_URI.forSyncAdapter(account)
        val scope = collectionArgs(account, collection)
        return KnownRows(
            contactIdsBySourceId = contactsByName(
                rawContactsUri,
                RawContacts.SOURCE_ID,
                visibleContactsSelection(),
                scope,
                contacts.map { it.key },
            ),
            // A group's MEMBER names a contact by its vCard UID, which this class stores in SYNC1.
            contactIdsByUid = contactsByName(
                rawContactsUri,
                RawContacts.SYNC1,
                visibleContactsSelection(),
                scope,
                groups.flatMap { it.parsed.members }.distinct(),
            ),
            groupIdsBySourceId = idsByName(
                groupsUri,
                Groups.SOURCE_ID,
                groupSelection(),
                scope,
                groups.map { it.key },
            ),
            // Groups are named by their title, which is all a `CATEGORIES` value has to go on.
            groupIdsByTitle = idsByName(
                groupsUri,
                Groups.TITLE,
                groupSelection(),
                scope,
                contacts.flatMap { it.parsed.categories }.distinct(),
            ),
        )
    }

    /**
     * The id of every row carrying one of [names] in [column], within [scope].
     *
     * The first row wins, which is what the per-resource query returned as well.
     */
    private fun idsByName(
        uri: Uri,
        column: String,
        selection: String,
        scopeArgs: Array<String>,
        names: List<String>,
    ): Map<String, Long> {
        if (names.isEmpty()) return emptyMap()
        val ids = HashMap<String, Long>()
        eachNamedRow(uri, arrayOf(BaseColumns._ID, column), column, selection, scopeArgs, names) { cursor ->
            val name = cursor.getString(1) ?: return@eachNamedRow
            ids.putIfAbsent(name, cursor.getLong(0))
        }
        return ids
    }

    /**
     * The same lookup for contacts, carrying the two flags that decide whether a write may touch the
     * row: `DIRTY` is an edit waiting to be uploaded and `DELETED` is a tombstone — both of them rows
     * the server's copy of that resource must not be written over.
     *
     * `VERSION` comes out of the same query as those flags, so that what the write guards on and what
     * the decision to write was made on are one snapshot of the row.
     */
    private fun contactsByName(
        uri: Uri,
        column: String,
        selection: String,
        scopeArgs: Array<String>,
        names: List<String>,
    ): Map<String, KnownContact> {
        if (names.isEmpty()) return emptyMap()
        val contacts = HashMap<String, KnownContact>()
        eachNamedRow(
            uri,
            arrayOf(BaseColumns._ID, column, RawContacts.DIRTY, RawContacts.DELETED, RawContacts.VERSION),
            column,
            selection,
            scopeArgs,
            names,
        ) { cursor ->
            val name = cursor.getString(1) ?: return@eachNamedRow
            contacts.putIfAbsent(
                name,
                KnownContact(
                    cursor.getLong(0),
                    dirty = cursor.getLong(2) != 0L,
                    deleted = cursor.getLong(3) != 0L,
                    version = cursor.getLong(4),
                ),
            )
        }
        return contacts
    }

    /**
     * One `IN`-bounded lookup, in chunks.
     *
     * Chunked for the same reason [deleteRows] is: an `IN` list is bounded by the variables one
     * statement may bind, and a group vCard may name thousands of members. [selectionArgs] spends
     * three of those, which one chunk of [SQL_VARIABLES_PER_STATEMENT] leaves room for.
     */
    private fun eachNamedRow(
        uri: Uri,
        columns: Array<String>,
        column: String,
        selection: String,
        selectionArgs: Array<String>,
        names: List<String>,
        collect: (Cursor) -> Unit,
    ) {
        for (chunk in names.chunked(SQL_VARIABLES_PER_STATEMENT)) {
            val placeholders = chunk.joinToString(",") { "?" }
            resolver.query(uri, columns, "$selection AND $column IN ($placeholders)", selectionArgs + chunk, null)
                ?.use { cursor ->
                    while (cursor.moveToNext()) collect(cursor)
                }
        }
    }

    /**
     * The rows one batch may point at, read for the batch as a whole. A name that is absent is not an
     * error: no such row is there yet, which is what [RowRef.pending] exists for.
     */
    private class KnownRows(
        val contactIdsBySourceId: Map<String, KnownContact>,
        val contactIdsByUid: Map<String, KnownContact>,
        val groupIdsBySourceId: Map<String, Long>,
        val groupIdsByTitle: Map<String, Long>,
    )

    /**
     * One contact row, the two flags that make the phone's version of it one the server must not
     * overwrite, and the provider's version counter, which is what a write over it matches on.
     */
    private class KnownContact(val id: Long, val dirty: Boolean, val deleted: Boolean, val version: Long) {
        val pending: Boolean get() = dirty || deleted
    }

    private fun queryId(uri: Uri, selection: String, args: Array<String>): Long? =
        resolver.query(uri, arrayOf(BaseColumns._ID), selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

    private fun syncStateRowId(account: Account): Long? =
        queryId(
            SyncState.CONTENT_URI.forSyncState(),
            "${SyncState.ACCOUNT_NAME}=? AND ${SyncState.ACCOUNT_TYPE}=?",
            arrayOf(account.name, account.type),
        )

    private fun readStates(account: Account): Map<String, CollectionState> =
        resolver.query(
            SyncState.CONTENT_URI.forSyncState(),
            arrayOf(SyncState.DATA),
            "${SyncState.ACCOUNT_NAME}=? AND ${SyncState.ACCOUNT_TYPE}=?",
            arrayOf(account.name, account.type),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) SyncStateCodec.decode(cursor.getBlob(0)) else emptyMap()
        } ?: emptyMap()

    /**
     * The longest side the provider will keep for a display photo. It is the provider's bound, not
     * this app's, and it is smaller on a memory-constrained device: asking costs one query and stops
     * a large photo from being decoded at full size for nothing.
     */
    private fun displayPhotoDimension(): Int {
        maxPhotoDimension?.let { return it }
        val queried = resolver.query(
            DisplayPhoto.CONTENT_MAX_DIMENSIONS_URI,
            arrayOf(DisplayPhoto.DISPLAY_MAX_DIM),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        } ?: 0
        val dimension = queried.takeIf { it > 0 } ?: FALLBACK_MAX_PHOTO_DIMENSION
        maxPhotoDimension = dimension
        return dimension
    }

    // ------------------------------------------------------------------ selections

    /**
     * The scope of every read and write in this class: one Account, one Collection.
     *
     * Contacts can be selected by account directly, because a query and an update are both evaluated
     * against the raw contacts view. A group *write* cannot: the provider applies it to the groups
     * table, which has no account name column, so group writes select on `_id` and take their Account
     * from the URI parameters instead.
     */
    private fun contactSelection(): String =
        "${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.SYNC3}=? " +
            "AND ${RawContacts.SOURCE_ID} IS NOT NULL AND ${RawContacts.DELETED}=0"

    /**
     * The same scope with the tombstones left in, for the one caller that has to see a deleted row:
     * [upsert], which would otherwise insert a second contact beside the row whose href it is writing
     * — the row it cannot see being a row the user deleted, whose upload has not run yet.
     */
    private fun visibleContactsSelection(): String =
        "${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.SYNC3}=? " +
            "AND ${RawContacts.SOURCE_ID} IS NOT NULL"

    /**
     * The scope of a pending-edit query: one Account, one Collection, and whatever applies to the kind
     * of edit being asked about. A created row has no Collection yet, which is why
     * [accountSelection] exists beside it.
     */
    private fun collectionSelection(rest: String): String =
        "${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.SYNC3}=? AND $rest"

    private fun accountSelection(rest: String): String =
        "${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.ACCOUNT_TYPE}=? AND $rest"

    private fun accountArgs(account: Account): Array<String> = arrayOf(account.name, account.type)

    /** The same scope for groups, whose lookups do run against the groups view. */
    private fun groupSelection(): String =
        "${Groups.ACCOUNT_NAME}=? AND ${Groups.ACCOUNT_TYPE}=? AND ${Groups.SYNC3}=?"

    private fun collectionArgs(account: Account, collection: DavCollection): Array<String> =
        arrayOf(account.name, account.type, collection.id)

    /**
     * Deletes rows by id, in chunks.
     *
     * An `IN` list is the only form that stays precise under a listing of a few thousand items, and
     * it is bounded by SQLite's variable limit per statement — a delete built from the whole
     * Collection at once would fail on exactly the accounts this app is for.
     */
    private fun deleteRows(uri: Uri, ids: List<Long>): Int {
        var deleted = 0
        for (chunk in ids.chunked(SQL_VARIABLES_PER_STATEMENT)) {
            val placeholders = chunk.joinToString(",") { "?" }
            deleted += resolver.delete(
                uri,
                "${BaseColumns._ID} IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray(),
            )
        }
        return deleted
    }

    private fun resolveId(ref: RowRef, results: Array<ContentProviderResult>): Long? =
        ref.id ?: ref.batchIndex?.let { index ->
            results.getOrNull(index)?.uri?.let { uri -> ContentUris.parseId(uri) }
        }

    /**
     * A row the batch is about to create, or one that already exists — exactly one of the two,
     * because which of them it is decides whether a value is a row id or a back-reference.
     */
    private class RowRef private constructor(val id: Long?, val batchIndex: Int?) {
        companion object {
            fun existing(id: Long): RowRef = RowRef(id, null)
            fun pending(batchIndex: Int): RowRef = RowRef(null, batchIndex)
        }
    }

    /** One resource of a batch: the key it is known by, its text, and what the text says. */
    private class Resource(val key: String, val text: String, val parsed: ParsedVCard)

    /** A membership whose other end the batch could not name, kept for the reconciliation pass. */
    private class DeferredMembership(
        val provenance: String,
        val contact: RowRef? = null,
        val contactUid: String? = null,
        val group: RowRef? = null,
        val groupTitle: String? = null,
    )

    /**
     * One attempt at [upsert]'s batch, and what the passes after it need from that attempt.
     *
     * A batch the provider rolled back leaves nothing of its attempt behind, so the passes that follow
     * must read the attempt that actually committed: a photo to baseline or a membership to reconcile
     * that belongs to a discarded batch names a row nobody wrote.
     */
    private class Assembly(
        val batch: ArrayList<ContentProviderOperation>,
        val photoContacts: List<RowRef>,
        val unresolved: List<DeferredMembership>,
        val stats: UpsertStats,
        /** The keys this attempt writes: every resource that parsed and was not held back. */
        val written: Set<String>,
    )

    /** What one [upsert] did, in the numbers that make a sync readable in a log. */
    private class UpsertStats {
        var items = 0
        var memberships = 0
        var unmappedProperties = 0
        var unresolvedMemberships = 0
        var unreadable = 0
        var foreignPhotos = 0
        var unreadablePhotos = 0
        var unfetchedPhotos = 0
        var heldBack = 0

        fun log(collection: DavCollection) {
            Log.i(
                LOG_TAG,
                "${collection.id}: $items items, $memberships memberships, " +
                    "$unmappedProperties unmapped properties, $unresolvedMemberships unresolved " +
                    "memberships, $unreadable unreadable, $foreignPhotos photos off the Origin, " +
                    "$unreadablePhotos photos that do not decode, $unfetchedPhotos photos not fetched, " +
                    "$heldBack rows with an edit waiting to be uploaded",
            )
        }
    }

    private companion object {
        /** AOSP's own bound for a large-memory device, used only if the provider cannot be asked. */
        const val FALLBACK_MAX_PHOTO_DIMENSION = 720

        /** Comfortably under SQLite's bound on the number of variables in one statement. */
        const val SQL_VARIABLES_PER_STATEMENT = 500

        /**
         * Marks a membership row written from a contact's `CATEGORIES`, as opposed to one written
         * from a group's member list. The two are refreshed at different times — a contact changes
         * without its groups changing, and a group changes without its members changing — so each
         * side replaces only the rows it owns.
         */
        const val MEMBERSHIP_FROM_CATEGORIES = "categories"

        /** Marks a membership row written from a group vCard's member list. */
        const val MEMBERSHIP_FROM_GROUP = "members"

        /**
         * Every `Data` column a mapped kind can use. The mapping writes each kind into these ten, so
         * they are what the upload path projects: it has to see the columns a kind does not use as
         * null, or a value the user cleared would read the same as one that never applied.
         */
        val DATA_COLUMNS = listOf(
            Data.DATA1,
            Data.DATA2,
            Data.DATA3,
            Data.DATA4,
            Data.DATA5,
            Data.DATA6,
            Data.DATA7,
            Data.DATA8,
            Data.DATA9,
            Data.DATA10,
        )

        /**
         * The one `Data` column each kind stores as a number, by MIME type.
         *
         * Keyed by MIME type rather than by column because the column names collide: `Phone.TYPE`,
         * `Email.TYPE`, `StructuredPostal.TYPE`, `Website.TYPE` and `Event.TYPE` are all `data2`,
         * and `data2` is also `StructuredName.GIVEN_NAME`, which is a name and not a number.
         */
        val INTEGER_COLUMN_BY_MIME_TYPE = mapOf(
            Phone.CONTENT_ITEM_TYPE to Phone.TYPE,
            Email.CONTENT_ITEM_TYPE to Email.TYPE,
            StructuredPostal.CONTENT_ITEM_TYPE to StructuredPostal.TYPE,
            Website.CONTENT_ITEM_TYPE to Website.TYPE,
            Event.CONTENT_ITEM_TYPE to Event.TYPE,
            GroupMembership.CONTENT_ITEM_TYPE to GroupMembership.GROUP_ROW_ID,
        )

        /** Contacts deleted on the phone, which name a resource to delete on the server. */
        const val PENDING_DELETES = "${RawContacts.DELETED}=1 AND ${RawContacts.SOURCE_ID} IS NOT NULL"

        /** Edits to a resource this app has already written. */
        const val PENDING_UPDATES =
            "${RawContacts.DIRTY}=1 AND ${RawContacts.SOURCE_ID} IS NOT NULL AND ${RawContacts.DELETED}=0"

        /** Contacts made on the phone, which no server has been told about yet. */
        const val PENDING_CREATES = "${RawContacts.SOURCE_ID} IS NULL AND ${RawContacts.DELETED}=0"

        /**
         * Clean contacts this app has synced that carry no photo baseline: the ones a version which
         * kept the baseline on the photo row left behind. Dirty and deleted rows are excluded
         * because a baseline may only be adopted from a contact whose rows are still the server's.
         */
        const val BASELINE_MISSING =
            "${RawContacts.SYNC4} IS NULL AND ${RawContacts.DIRTY}=0 AND ${RawContacts.DELETED}=0 " +
                "AND ${RawContacts.SOURCE_ID} IS NOT NULL"

        /**
         * Stands for "this contact has no photo" in `SYNC4`. A real digest is 64 hex characters, so
         * the two can never be confused, and a photo added later cannot hash to it.
         */
        const val NO_PHOTO = "none"

        /**
         * Baseline writes per batch. Each operation parcels a sync-adapter URI, a selection and its
         * arguments — a few hundred bytes — and the Binder transaction a batch travels in is 1 MiB, so
         * this keeps a batch an order of magnitude inside it.
         */
        const val BASELINE_OPERATIONS_PER_BATCH = 200
    }
}
