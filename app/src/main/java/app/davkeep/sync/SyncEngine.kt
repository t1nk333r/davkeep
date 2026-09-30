package app.davkeep.sync

import android.accounts.Account
import android.content.ContentResolver
import android.content.SyncResult
import android.os.Bundle
import io.ktor.http.Url
import java.util.UUID
import app.davkeep.core.AccountStore
import app.davkeep.core.ChangeKind
import app.davkeep.core.CollectionState
import app.davkeep.core.CollectionType
import app.davkeep.core.CredentialsUnreadableException
import app.davkeep.core.DavAccount
import app.davkeep.core.DavCollection
import app.davkeep.core.DavHttpClientFactory
import app.davkeep.core.ErrorClass
import app.davkeep.core.LocalChange
import app.davkeep.core.ProviderMapper
import app.davkeep.core.RemoteItem
import app.davkeep.core.SyncError
import app.davkeep.core.SyncErrorClassifier

/** §6 step 3 keeps traffic moving: a non-user-initiated sync stalling for 60s gets cancelled. */
internal const val MULTIGET_BATCH_SIZE = 50

/** §6: sync-collection support is re-probed weekly. */
private const val SYNC_COLLECTION_REPROBE_MS = 7L * 24 * 60 * 60 * 1000

/**
 * The error classes that abort the Account's remaining Collections.
 *
 * §6 names 1, 2, 4 and 5 — the failures that cannot improve by being asked again on the next
 * Collection. The other terminal classes (6, 12) are terminal for notification, not for the run;
 * they are still reported, per Collection.
 */
private val ACCOUNT_ABORTING_CLASSES = setOf(
    ErrorClass.PROXY_REJECTED_CREDENTIALS,
    ErrorClass.ORIGIN_WANTS_CREDENTIALS,
    ErrorClass.CERTIFICATE_UNAVAILABLE,
    ErrorClass.CREDENTIALS_UNREADABLE,
)

/**
 * Syncs one Account's Collections for one authority, following §6.
 *
 * Collections run sequentially, and each is self-contained: a failure that is not one of
 * [ACCOUNT_ABORTING_CLASSES] leaves the others alone.
 *
 * The three §6 invariants are enforced here rather than left to the mapper:
 *
 * 1. Rows may be written before a Collection completes; its CTag may not. The
 *    [ProviderMapper.writeState] carrying the new CTag is the last thing a Collection does, after
 *    every batch is committed — recording one early would make the next run skip the items that
 *    never arrived.
 * 2. Deletion runs only against a listing that completed, asserted in [deleteMissing]: it is the
 *    only invariant whose violation destroys user data rather than wasting traffic.
 * 3. Sync state lives with the data. Item ETags reach the mapper together with the bodies they
 *    describe, and Collection state goes through [ProviderMapper.readState]/[ProviderMapper.writeState];
 *    there is no local database that could outlive the provider's account cleanup.
 *
 * And one about the direction this engine now runs in as well: a row is clean again only when the
 * run has answered for it. Step U sends every pending change before the listing, and a dirty flag
 * is cleared by [ProviderMapper.markUploaded] after the server accepted the body, by
 * [ProviderMapper.acknowledgeUnchanged] for a body the server already holds, or by a revert or a
 * conflict the run reports; the read-only backstop that used to clear it after every Collection is
 * gone, because once uploads exist every DIRTY row is a row an editor has changed and no run has
 * sent yet.
 *
 * A run the framework starts with `SYNC_EXTRAS_UPLOAD` is upload-only: step U for every writable
 * Collection and nothing else — no listing, no deletion, no state write. It still fetches what a
 * revert left, this run's or an earlier one's, because those rows are waiting for the server's
 * version and no listing is coming to bring it.
 *
 * Retrying is the framework's: the outcome is expressed in the [SyncResult] counters and nothing
 * here loops.
 *
 * A run begins with §8's pre-flight rather than with the first Collection: [extras] says whether the
 * user asked for this run, and the answer decides whether the Account's own unmetered-only setting
 * and its empty selection apply to it at all.
 *
 * §8's re-enumeration comes next and before the run's own client is built: manual plus daily, so a
 * Collection the server has gained is one the Account can be offered without anyone pressing
 * anything. It is a step of the run rather than of a Collection — one walk per Account per day,
 * shared by both authorities' engines — and it is the one step here whose failure is not the run's:
 * the Collections already stored are what this run is for.
 */
class SyncEngine(
    private val mapper: ProviderMapper,
    private val collectionType: CollectionType,
    private val accountStore: AccountStore,
    private val httpClientFactory: DavHttpClientFactory,
    private val classifier: SyncErrorClassifier,
    private val reporter: SyncReporter,
    private val preferences: SyncPreferences,
    private val deferrals: SyncDeferralRecorder,
    private val enumerator: CollectionEnumerator,
    metering: NetworkMetering,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** §8's own conditions on a run, applied before anything is read or opened. */
    private val preflight = SyncPreflight(metering, clock)

    suspend fun sync(
        account: Account,
        extras: Bundle,
        result: SyncResult,
        isCancelled: () -> Boolean = { false },
    ) {
        val outcomes = mutableListOf<CollectionOutcome>()

        // Read once, at the top: every exit from this method reports through it, and §8's missed-slot
        // evidence has to be able to tell the framework's own runs from the user's.
        val manual = extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)
        val automatic = !manual

        // What the framework asks for when a provider reports a dirtying write. Such a run has one
        // job, and the Collections below are the ones it has it for.
        val uploadOnly = extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, false)

        try {
            ensureAccountRegistered(account)
        } catch (e: AccountVanishedException) {
            result.databaseError = true
            report(account, outcomes, aborted = true, error = null, automatic = automatic)
            return
        }

        var davAccount = try {
            accountStore.load(account)
        } catch (e: CredentialsUnreadableException) {
            // The Account survived a device restore; its Credentials did not. §5 class 5.
            val error = ErrorMapping(classifier, certificateOffered = false, certificateConfigured = false)
                .classify(e, method = null)
            record(result, error)
            report(account, outcomes, aborted = true, error = error, automatic = automatic)
            return
        }
        if (davAccount == null) {
            // Removed between the two reads, or its record was never completed.
            result.databaseError = true
            report(account, outcomes, aborted = true, error = null, automatic = automatic)
            return
        }

        // §8's conditions, before a client is built: a run that is not allowed to do its work must
        // not open a connection, and one with nothing to do must not go looking for a certificate.
        when (
            val decision = preflight.decide(
                manual = manual,
                unmeteredOnly = preferences.unmeteredOnly(account),
                hasWork = collectionsOf(davAccount).isNotEmpty(),
            )
        ) {
            RunDecision.Proceed -> Unit

            // §5's OK with no Collections, which is the honest answer and leaves a deferral ended
            // by this run cleared.
            RunDecision.NothingToDo -> {
                report(account, outcomes, aborted = false, error = null, automatic = automatic)
                return
            }

            // No counter is touched: nothing failed, and the framework's own retry is what the
            // delay asks for. Recording the deferral is what the account screen shows as waiting.
            is RunDecision.Defer -> {
                result.delayUntil = decision.untilSeconds
                deferrals.recordDeferred(account)
                return
            }
        }

        // §8's daily half of re-enumeration, before this run's own client is built: discovery makes
        // its own clients, so there is nothing open here to spend, and what it stores is the
        // selection the Collection loop below works from.
        //
        // The day gates every run that reaches here, and `manual` is deliberately not an arm of it.
        // §8's manual half of re-enumeration is the settings screen's Check collections — a gesture
        // that asks for the walk — where this is a gesture that asks for a sync; one of those
        // reaches both authorities, so honouring it here would spend the day's walk twice on a
        // request that did not make it. The cadence is one walk per Account per day, which is only
        // true if the settings screen's walk spends the same day (see `SettingsActivity`).
        if (EnumerationDue.decide(preferences.lastEnumeratedAt(account), clock())) {
            val walked = try {
                enumerator.enumerate(account, davAccount)
            } catch (e: Exception) {
                // §8's rule for this step, enforced here rather than left to the walk: the Collections
                // already stored are what the run is for, so a walk that cannot be made — or whose
                // result cannot be stored — leaves this run to do exactly what it would have done had
                // the day not been up. Nothing below reads this, no counter moves, and no error is
                // recorded: the walk reports itself through the log, and the next run tries again.
                null
            }
            if (walked != null) {
                // Stamped only now, after a walk that ran to the end and was stored: one that failed
                // is retried by the next run instead of being suppressed for a day.
                preferences.setLastEnumeratedAt(account, clock())
                // And the run continues from the selection the walk left rather than the one it
                // loaded. A Collection the walk has just marked unavailable is one the server no
                // longer lists, and syncing it would report the server's own answer — a 404 on a
                // calendar that is gone — as this run's failure.
                davAccount = davAccount.copy(collections = walked)
            }
        }

        val httpClient = try {
            httpClientFactory.create(davAccount)
        } catch (e: Exception) {
            val error = ErrorMapping(classifier, certificateOffered = false, certificateConfigured = false)
                .classify(e, method = null)
            record(result, error)
            report(account, outcomes, aborted = true, error = error, automatic = automatic)
            return
        }

        val http = openDavHttpSession(httpClient)
        var aborted = false
        try {
            for (collection in collectionsOf(davAccount)) {
                if (isCancelled()) break

                // An upload-only run sends, and only sends: a Collection nobody has made writable has
                // nothing to send under any circumstances, so it is not visited at all rather than
                // refused — the periodic run is where a read-only Collection's edits are reverted.
                if (uploadOnly && !collection.writable) continue

                val outcome = syncCollection(account, davAccount, http, collection, result, uploadOnly)
                outcomes += outcome

                if (outcome.abortsAccount) {
                    aborted = true
                    break
                }
            }
        } catch (e: AccountVanishedException) {
            // Everything written so far stands; the provider reaps it with the Account. What must not
            // happen is more writing, or a CTag claiming a Collection that will not be finished.
            result.databaseError = true
            aborted = true
        } finally {
            http.close()
        }

        // An upload-only run that found no writable Collection visited nothing, learned nothing and
        // has nothing to say. Reporting it would overwrite the account's status with a clean "no
        // Collections are selected" — which is how a refusal came to be hidden: the framework starts
        // one of these the moment a provider goes dirty, so it lands moments after the periodic run
        // that refused the edit and speaks last.
        if (uploadOnly && outcomes.isEmpty() && !aborted) return

        report(account, outcomes, aborted = aborted, error = null, automatic = automatic)
    }

    /**
     * §6 steps 1–7 for one Collection.
     *
     * Where the server supports it, one REPORT collapses steps 1 and 2; otherwise the polling path
     * runs. Either way the Collection ends with the same steps 3–7.
     */
    private suspend fun syncCollection(
        account: Account,
        davAccount: DavAccount,
        http: DavHttpSession,
        collection: DavCollection,
        result: SyncResult,
        uploadOnly: Boolean,
    ): CollectionOutcome {
        val startedAt = clock()
        val state = mapper.readState(account, collection)
        val session = CollectionSession(http.client, collection, http::startOperation)
        val errors = errorMapping(davAccount, http)

        // Read outside the try so that a run which failed after step U still reports what step U
        // managed: those rows are on the server whatever the listing did next.
        var uploads = Uploads()

        return try {
            // §6 step 2, before anything is sent: what the provider shows about the Collection itself
            // — a calendar's access level — is refreshed on every run, because a Collection whose CTag
            // never moves would otherwise never learn that the user changed the setting.
            mapper.ensureCollection(account, collection)

            // §6 step U, and before the cheap check rather than after it. The upload is what makes a
            // Collection dirty, and a Collection whose only change is local has to be sent even though
            // its CTag has not moved; the other half of the reason is the fetch below, which replaces
            // a resource's rows wholesale and would discard an edit that has not left the phone yet.
            uploads = uploadChanges(account, collection, session, errors)

            // What a revert left behind is fetched here, by href, before any listing runs — and it
            // is read from the rows rather than carried from step U, because a revert an earlier
            // run could not repair looks exactly like one this run made. A revert leaves the
            // phone's rejected edit on the rows with no ETag, and it is the server's copy that
            // belongs there; waiting for the listing to bring it is only sound when the listing
            // names every member. A `sync-collection` delta names what changed since the stored
            // token, so a conflict whose server-side change this account already consumed — and a
            // refusal, where the server changed nothing at all — would never be named, and the row
            // would sit as if clean while holding an edit this run deliberately gave up. Asking
            // for those hrefs costs one REPORT per fifty resources and is owed by the revert
            // itself; a run with nothing reverted asks for nothing.
            //
            // Held rows are dirty and so are never in the clean half of the plan; the subtraction is
            // belt and braces against a mapper whose rows moved between step U and this read.
            //
            // The plan's other half is not subtracted from: those rows are dirty because the user's
            // edit is on them, and step U held every one of them for want of a base to patch. That
            // edit can never be sent, and the rows were never written from the server's text, so
            // the server's copy replaces them and the run reports a conflict — the same answer a
            // `412` gets, arrived at without one. Step U adds the resources it found stale by
            // asking: dirty rows with a text but no ETag, for which the server names one. Their
            // text was stored under no version the server now holds, and a body patched out of
            // it would have carried that stale base back under the ETag just asked for.
            //
            // When step U has already ended the Collection on an error, the restore is still tried —
            // the rows were given back either way — but its own failure must not replace the error the
            // run actually met: a step-U credential failure reported as a transport failure on the
            // REPORT would send the user after the wrong problem.
            val plan = mapper.restorePlan(account, collection)
            val owed = plan.full - uploads.held
            val conflicted = plan.conflicted + uploads.stale
            val failure = uploads.error
            val restore = if (failure == null) {
                restoreReverted(account, collection, session, owed, conflicted)
            } else {
                try {
                    restoreReverted(account, collection, session, owed, conflicted)
                } catch (e: AccountVanishedException) {
                    throw e
                } catch (e: Exception) {
                    // Not reported on its own: the run already reports step U's error, and every
                    // resource this failed to fetch is counted as missing in the outcome below.
                    Restore(unanswered = owed + conflicted)
                }
            }

            // Step U ended the Collection: the listing would meet the same failure, and the changes it
            // never reached are still DIRTY for the next run.
            if (failure != null) {
                record(result, failure)
                return uploads.outcome(collection).copy(
                    written = restore.written,
                    deleted = restore.deleted,
                    pending = pendingAfter(uploads, restore),
                    conflicts = uploads.conflicts + restore.conflicts,
                    missing = restore.unanswered.size,
                )
            }

            // An upload-only run stops here, with no listing and no state write: the framework asked
            // for the uploads, and the periodic run is what lists. A revert no longer makes it carry
            // on — the rows it gave back were fetched above, which is what the listing was for. A
            // resource the restore did not get back is counted, so the run does not report as done
            // while a row still holds the edit it gave up.
            if (uploadOnly) {
                return uploads.outcome(collection).copy(
                    written = restore.written,
                    deleted = restore.deleted,
                    pending = pendingAfter(uploads, restore),
                    conflicts = uploads.conflicts + restore.conflicts,
                    missing = restore.unanswered.size,
                )
            }

            // The answer this run got, carried into step 7's state write: the poll path must not
            // overwrite what the probe just learned.
            var supportsSyncCollection = state.supportsSyncCollection
            var capabilityCheckedAt = state.capabilityCheckedAt

            if (mayAttemptSyncCollection(state, startedAt)) {
                // A refusal is an answer worth remembering; a failure is not, so nothing is cached for
                // it and the next run probes again — §6's "re-probe after failure".
                val report = session.reportChanges(state.syncToken)

                if (report != null) {
                    return applyChanges(
                        account, collection, session, report.members, uploads,
                        fullListing = report.fullListing,
                        // A REPORT names no CTag; the last one we listed at is kept. A server whose
                        // CTag has moved on since then simply makes the next polling run list in full.
                        newCtag = state.ctag,
                        newToken = report.token,
                        supportsSyncCollection = true,
                        capabilityCheckedAt = startedAt,
                        result = result,
                        restore = restore,
                        relisted = report.relisted,
                    )
                }

                // The server answered `<supported-report/>`: remember it and poll like everyone else.
                cacheCapability(account, collection, state, supported = false, at = startedAt)
                supportsSyncCollection = false
                capabilityCheckedAt = startedAt
            }

            poll(
                account, collection, session, state, uploads,
                supportsSyncCollection, capabilityCheckedAt, restore, result,
            )
        } catch (e: AccountVanishedException) {
            throw e
        } catch (e: Exception) {
            val error = errors.classify(e, session.lastMethod)
            record(result, error)
            uploads.outcome(collection, error)
        }
    }

    /** §6 steps 1, 2 and — through [applyChanges] — 3 to 7, without sync-collection support. */
    private suspend fun poll(
        account: Account,
        collection: DavCollection,
        session: CollectionSession,
        state: CollectionState,
        uploads: Uploads,
        supportsSyncCollection: Boolean?,
        capabilityCheckedAt: Long,
        restore: Restore,
        result: SyncResult,
    ): CollectionOutcome {
        val ctag = session.ctag()
        if (ctag != null && ctag == state.ctag)
            return uploads.outcome(collection).copy(
                written = restore.written,
                deleted = restore.deleted,
                pending = pendingAfter(uploads, restore),
                conflicts = uploads.conflicts + restore.conflicts,
                missing = restore.unanswered.size,
                unchanged = true,
            )

        return applyChanges(
            account, collection, session, session.members(), uploads,
            fullListing = true,
            newCtag = ctag,
            // A full listing invalidates any sync token: the next REPORT must start from scratch.
            newToken = null,
            supportsSyncCollection = supportsSyncCollection,
            capabilityCheckedAt = capabilityCheckedAt,
            result = result,
            restore = restore,
        )
    }

    /**
     * §6 steps 3 to 7 for a Collection whose members are known, with [uploads] — what step U did
     * before this — carried into the outcome the run reports.
     */
    private suspend fun applyChanges(
        account: Account,
        collection: DavCollection,
        session: CollectionSession,
        members: Members,
        uploads: Uploads,
        fullListing: Boolean,
        newCtag: String?,
        newToken: String?,
        supportsSyncCollection: Boolean?,
        capabilityCheckedAt: Long,
        result: SyncResult,
        /** What the fetch of what a revert left already wrote and deleted, and what it did not get. */
        restore: Restore = Restore(written = 0, deleted = 0, unanswered = emptySet()),
        relisted: Boolean = false,
    ): CollectionOutcome {
        val local = mapper.localItems(account, collection)

        // Step 3: fetch adds and updates only. An href whose ETag already matches the row needs no
        // body, and an href the server gives no ETag for can never be compared, so it is fetched.
        //
        // The exception is the href of a change step U did not send: its rows are still DIRTY, and
        // `upsert` replaces a resource's rows wholesale, so fetching it here would discard the edit
        // the next run is still carrying. What a revert left, this run's or an earlier one's, is not
        // here at all: it was fetched by href before this listing ran, because a listing is not
        // something a revert may depend on.
        val wanted = mutableMapOf<String, RemoteItem>()
        for ((key, item) in members.byKey)
            if (key !in uploads.held && (item.etag == null || local[key] != item.etag))
                wanted[key] = item

        // Steps 3 and 4, in batches of 50: each batch is committed, and its ETags are persisted with
        // the bodies they belong to, so a later diff cannot see a body without its ETag. The ETag
        // stored is the listing's, which is the one the next run's diff compares against.
        var written = restore.written
        val fetched = HashSet<String>(wanted.size)
        for (batch in wanted.entries.chunked(MULTIGET_BATCH_SIZE)) {
            ensureAccountRegistered(account)

            val answer = session.multiget(batch.map { Url(it.value.href) })
            // A 404 for a member this listing named stays "missing": the state is withheld and the
            // next run lists again, rather than rows being deleted on one batch's answer.
            val bodies = answer.bodies
            if (bodies.isEmpty()) continue

            val etags = batch.associate { it.key to it.value.etag }.filterKeys { it in bodies }
            written += mapper.upsert(account, collection, bodies.mapValues { it.value.text }, etags).size
            // A body that arrived and could not be written is still answered: the server handed it
            // over, and withholding the state for a resource the provider cannot hold would list
            // the Collection in full on every run for as long as that body stays unreadable.
            fetched += bodies.keys
        }

        // An href the listing named and the multiget never answered. The body is simply absent —
        // the batch carried on without it — and the only place that absence can still be noticed is
        // here, before anything is claimed about the Collection.
        val missing = wanted.keys.count { it !in fetched }

        // A give-back the restore did not get is stranded only if this listing did not deal with it
        // either: named, it was fetched (or counted above if not); reported removed, or absent from a
        // full listing that completed, it is deleted below. Anything else still holds the edit the
        // revert gave up. It is reported, but it does not withhold the state: this listing is complete,
        // and keeping its token would not make a later delta name a change already behind it.
        val stranded = restore.unanswered.count { key ->
            key !in members.byKey && key !in members.removed && !(fullListing && members.completed)
        }

        // Step 6, and only behind a listing that earned it. A truncated listing is a well-formed
        // answer about part of a Collection, and deleting everything it did not mention would take
        // the rest of the Collection with it.
        val deleted = restore.deleted + (
            if (members.completed) deleteMissing(account, collection, members, keptHrefs(fullListing, local.keys, members))
            else 0
            )

        // Nothing clears a dirty flag here. A row is clean again only once `markUploaded` has stored
        // the server's answer for it, which step U did above for everything it managed to send; a
        // row step U left dirty is one this run has not sent, and clearing it here — which is what
        // the read-only backstop used to do — is exactly the edit thrown away this design exists to
        // stop. A read-only Collection's rows are reverted in step U, not here.

        // Step 7, and not a line earlier — nor at all when this run learned less than the Collection
        // had to say. The state written here is what the *next* run trusts: store a CTag or a token
        // now and the cheap check short-circuits a Collection whose missing item was never fetched,
        // for as long as the server's own state sits still. Leaving it exactly as found costs one
        // listing next run and is the difference between a retry and a permanent stall.
        //
        // A change left pending does not withhold it. The token describes the listing this run read,
        // and a row that is still dirty is found by `pendingChanges` at the start of the next run
        // whatever the token says — withholding the token for one would cost a full listing every run
        // for as long as the server keeps refusing that body, which is the stall this rule removed.
        // Nor does a held member the listing named: what that member's resource is owed is the
        // server's copy on the rows the run gave back, and step U fetched that by href, so nothing
        // is waiting on this token being asked for again.
        val incomplete = missing > 0 || members.truncated
        if (!incomplete) {
            mapper.writeState(
                account,
                collection,
                CollectionState(
                    ctag = newCtag,
                    syncToken = newToken,
                    supportsSyncCollection = supportsSyncCollection,
                    capabilityCheckedAt = capabilityCheckedAt,
                    lastSuccessAt = clock(),
                ),
            )
        }

        return CollectionOutcome(
            collection,
            written = written,
            deleted = deleted,
            uploaded = uploads.uploaded,
            pending = pendingAfter(uploads, restore),
            refused = uploads.refused,
            conflicts = uploads.conflicts + restore.conflicts,
            missing = missing + stranded,
            truncated = members.truncated,
            relisted = relisted,
        )
    }

    /**
     * What is still on the phone after the restore, which is not always what step U counted.
     *
     * A resource whose edit could not be serialised was counted pending and held, and the restore
     * may then have resolved it — the server's copy on the rows, the edit given up, the conflict
     * reported. Counting it pending as well would tell the user the edit is waiting for the next
     * run, which is the one thing it is not.
     */
    private fun pendingAfter(uploads: Uploads, restore: Restore): Int =
        uploads.pending - restore.conflicts.count { it in uploads.held }

    /**
     * The server's copy of every resource a revert left waiting, fetched by href in the same run.
     *
     * A revert is only half an answer: it stops the rows claiming to be current — `DIRTY` cleared,
     * the ETag nulled — but it leaves the user's rejected text on them, and the server's version is
     * what belongs there. The listing used to be relied on for that, which is sound only when it
     * names every member: a `sync-collection` delta names what changed since the stored token, so a
     * conflict whose server-side change an earlier run already consumed, and a refusal on a
     * Collection the server never touched, would both leave the row looking clean and holding an
     * edit nobody kept.
     *
     * [keys] comes from the rows — clean, named, no ETag — and so is asked for on every run until
     * the server answers for it, rather than once in the run that reverted. A fetch that fails is a
     * fetch the next run makes again, which is the whole difference between reporting the
     * divergence and repairing it.
     *
     * [conflicted] is the same debt on rows that have been edited since, and is what keeps a revert
     * from stranding an edit for good ([#35](https://github.com/t1nk333r/davkeep/issues/35)). A
     * create the server answered `412` is reverted under the name it was `PUT` to, and that name
     * arrives with no text behind it; until the restore brings one there is nothing for an edit to
     * be patched onto, so the row's own upload is held on every run — and the row, being dirty, has
     * dropped out of [keys] and is asked about by nothing. A revert of an update leaves its text
     * behind, and an edit on top of that is the same debt in a worse shape: the text is the version
     * a `412` already said the server had moved past, and step U, finding no ETag on the row and
     * one on the server, sends it here rather than patching onto it.
     *
     * Both are asked about here, and resolved as a conflict: the server's copy replaces the rows
     * and the run reports it, exactly as a `412` would be reported. Keeping the edit was tried and
     * is wrong — those rows hold no text the server's current version was ever read from, so a body
     * patched out of them would carry every column the server had changed meanwhile back to what
     * the phone happens to hold, under an ETag this phone got by asking, with no `412` to stop it
     * and nothing said. Proving the stored text is this phone's own lost create, or the version the
     * server still holds, would need the sent bytes kept on the row and compared, which is new state
     * for a comparison any normalising server makes meaningless; and the edit this gives up is one
     * that could never have been sent at all.
     *
     * The ETag stored is the one the multiget's own response named, because there is no listing here
     * to read one from and a row whose text has no ETag is fetched again by the next listing that
     * names it. A server that names none in a multiget is asked with the same `PROPFIND Depth: 0`
     * an ETag-less `PUT` answer uses — once per restore rather than once per resource: a server
     * that has no ETag for one member it just handed over has none for any, so the first null
     * answer stands for the rest, and a Collection on such a server costs one extra request here
     * rather than one per member.
     *
     * A body that arrived and could not be written is not an answer. The rows still hold what the
     * revert left, and asking again next run would get the same body: so a resource of [keys] whose
     * copy the provider cannot hold gives its rows up through [ProviderMapper.discardUnwritable],
     * the way a listing gives no rows to a resource it cannot read, and one of [conflicted] does
     * the same inside [ProviderMapper.replaceOverEdit] and is reported. What neither could touch —
     * rows that moved under the call — is counted in [Restore.unanswered] and met again next run.
     *
     * A `404` for an href is the server deciding, by name, that it no longer has the resource:
     * [ProviderMapper.resourceGone] removes its clean rows, and rows holding an edit give the name
     * back and are a create again — an `If-Match` against a resource the server does not have can
     * only fail, and a text-less row cannot even be serialised to try. Anything else — a response
     * that did not arrive, a batch that failed — leaves the rows as they were, is counted in
     * [Restore.unanswered] so the run does not report as done, and is asked for again next run.
     */
    private suspend fun restoreReverted(
        account: Account,
        collection: DavCollection,
        session: CollectionSession,
        keys: Set<String>,
        conflicted: Set<String>,
    ): Restore {
        val wanted = LinkedHashSet<String>(keys.size + conflicted.size)
        wanted += keys
        wanted += conflicted
        if (wanted.isEmpty()) return Restore()

        var written = 0
        var deleted = 0
        val conflicts = ArrayList<String>()
        val answered = HashSet<String>(wanted.size)
        // Whether the server has an ETag to name at all, learned from the first member it named
        // none for in a multiget. Null until a body arrives without one.
        var namesEtags: Boolean? = null
        suspend fun etagFor(key: String, body: FetchedBody): String? {
            body.etag?.let { return it }
            if (namesEtags == false) return null
            return session.etagOf(key).also { namesEtags = it != null }
        }
        for (batch in wanted.chunked(MULTIGET_BATCH_SIZE)) {
            ensureAccountRegistered(account)

            val fetched = session.multiget(batch.map { session.memberUrl(it) })
            // The two halves travel in one request and part here, where what the write means
            // differs: replacing rows nobody has touched gives nothing up, and replacing rows that
            // hold an edit gives that edit up and has to be reported.
            val whole = fetched.bodies.filterKeys { it in keys }
            if (whole.isNotEmpty()) {
                val etags = whole.mapValues { (key, body) -> etagFor(key, body) }
                val stored = mapper.upsert(account, collection, whole.mapValues { it.value.text }, etags)
                written += stored.size
                answered += stored
                for (key in whole.keys) {
                    if (key in stored) continue
                    // The server's copy cannot land on these rows, and nothing later in the run
                    // will do better with the same body. Rows an edit reached since the plan was
                    // read stay whole and unanswered: that edit is step U's next run.
                    val removed = mapper.discardUnwritable(account, collection, key)
                    if (removed > 0) {
                        deleted += removed
                        answered += key
                    }
                }
            }
            for ((key, body) in fetched.bodies) {
                if (key !in conflicted) continue
                val replaced = mapper.replaceOverEdit(account, collection, key, body.text, etagFor(key, body))
                // One resource at a time, so that what is reported as resolved is what was written:
                // a resource whose rows moved under the call keeps them and is met again next run.
                if (replaced) {
                    conflicts += key
                    answered += key
                }
            }
            for (key in fetched.gone) {
                // The server has decided: it no longer has the resource, by name. Counting it
                // missing instead would never converge, because nothing will ever name it again.
                deleted += mapper.resourceGone(account, collection, key)
                answered += key
            }
        }
        return Restore(
            written = written,
            deleted = deleted,
            conflicts = conflicts,
            unanswered = wanted.filterTo(HashSet()) { it !in answered },
        )
    }

    /**
     * What [restoreReverted] wrote, removed and resolved, and which of the resources it asked for
     * it did not get an answer about.
     */
    private data class Restore(
        val written: Int = 0,
        val deleted: Int = 0,
        val conflicts: List<String> = emptyList(),
        val unanswered: Set<String> = emptySet(),
    )

    /**
     * §6 step U: every row the phone has changed, sent before anything of the server's is read.
     *
     * Running it here rather than after the fetch is what the design turns on: `upsert` replaces a
     * resource's rows wholesale, so a server-side change fetched first would discard an edit that has
     * not been sent yet. What step U leaves behind is carried back in [Uploads.held], and `wanted`
     * keeps those hrefs out of the fetch for the same reason.
     *
     * Failures split two ways, and the split is the difference between a retry that happens and one
     * that never will. A retryable failure on any request ends step U and the Collection with that
     * error, because the listing would meet the same wall and §5 leaves retrying to the framework.
     * A refusal one item can do nothing about — a body the server will not take, a row the mapper
     * will not serialise — leaves that row DIRTY, counts it pending, and lets the run carry on: one
     * body the server will not take is not a reason to stop learning what the server has.
     */
    private suspend fun uploadChanges(
        account: Account,
        collection: DavCollection,
        session: CollectionSession,
        errors: ErrorMapping,
    ): Uploads {
        val changes = uploadOrder(mapper.pendingChanges(account, collection))

        // §7: a Collection the user has not made writable is refused rather than uploaded. An edit
        // to a resource the server already has is given up here — the narrow successor of the §7
        // backstop — and the rows it leaves behind are what this run's restore reads back out of
        // the provider: the server changed nothing, so no listing would name them.
        //
        // A create is the exception, and it is not a small one. A contact made in the editor exists
        // nowhere else: there is no server copy for the fetch to put back, so clearing its DIRTY
        // flag does not revert it, it strands it — the contact stays on the phone with nothing left
        // to say it was never sent. Found on a device: a contact saved through "Save contact to"
        // went quiet exactly this way, because `writable` is off by default and every run cleared
        // the flag again. It stays dirty and is counted refused on every run until a Collection can
        // take it.
        if (!collection.writable) {
            val refusedHeld = mutableSetOf<String>()
            for (change in revertibleOnRefusal(changes)) {
                ensureAccountRegistered(account)
                // A row that moved while this ran is newer than the refusal, so it keeps its edit
                // and is kept out of this run's fetch rather than being written over by it.
                if (!mapper.revertLocalChange(account, collection, change, sent = false)) {
                    change.key?.let { refusedHeld += it }
                }
            }
            return Uploads(refused = changes.size, held = refusedHeld)
        }

        var uploaded = 0
        var pending = 0
        val conflicts = mutableListOf<String>()
        val held = mutableSetOf<String>()
        val stale = mutableSetOf<String>()

        for ((index, change) in changes.withIndex()) {
            ensureAccountRegistered(account)

            try {
                when (change.kind) {
                    ChangeKind.DELETE -> {
                        // A row created and deleted before any run was never on the server: there is
                        // nothing to send, and the tombstone is dropped here.
                        if (change.key == null) {
                            mapper.purgeDeleted(account, collection, change)
                            uploaded++
                            continue
                        }

                        when (answerOf(session.delete(change.key, change.etag))) {
                            ChangeAction.Purge -> {
                                mapper.purgeDeleted(account, collection, change)
                                uploaded++
                            }

                            ChangeAction.Revert -> {
                                // The server's item moved after this phone last saw it, so the
                                // deletion is given up and the server's version is fetched back onto
                                // the row. Server wins, as it does for an update.
                                //
                                // Unless the row moved here too: a deletion undone while the server
                                // was answering is not this run's to resolve, and calling it a
                                // conflict would report a resolution that did not happen.
                                if (mapper.revertLocalChange(account, collection, change, sent = true)) {
                                    conflicts += change.key
                                } else {
                                    pending++
                                    held += change.key
                                }
                            }

                            is ChangeAction.MarkUploaded ->
                                error("a DELETE is never answered with a resource to store")
                        }
                    }

                    ChangeKind.CREATE, ChangeKind.UPDATE -> {
                        val body = mapper.serialize(account, collection, change)
                        if (body == null) {
                            // No bytes: a stored copy that does not parse, a row the rows do not
                            // represent. The row stays DIRTY and is counted pending — it is never
                            // uploaded from a partial reading of it.
                            pending++
                            change.key?.let { held += it }
                            continue
                        }

                        // A change with no key is one the phone created: it is PUT under the name
                        // its UID makes, and `If-None-Match: *` is what keeps that name from
                        // overwriting an item this run has not seen.
                        val creating = change.key == null
                        val target = change.key
                            ?: createPath(session.collectionUrl.encodedPath, collection, body.uid)

                        // A body that says nothing the server does not already hold is not sent at
                        // all. `RawContacts.STARRED`, a ringtone and `SEND_TO_VOICEMAIL` flag a
                        // contact dirty without touching a `Data` row, and an event's access level
                        // does the same for a calendar: none of them reaches the resource, so the
                        // `PUT` carries the bytes the server has, plus this app's own `PRODID` and
                        // `REV`. It is acknowledged here instead, under the guard the answer to a
                        // real upload uses, and counted neither uploaded nor pending: nothing went
                        // out, and nothing is left to send. A row that moved while step U was
                        // deciding keeps its flag — that edit is newer than this decision and is
                        // what the next run sends — and is held out of this run's fetch.
                        if (!creating && body.unchanged) {
                            if (!mapper.acknowledgeUnchanged(account, collection, change)) {
                                pending++
                                held += target
                            }
                            continue
                        }

                        // An update of a resource the server already holds is never sent
                        // unconditionally. A null ETag on such a row says this phone does not know
                        // which version the server has, and a `PUT` without `If-Match` would take
                        // whatever is there — the lost update `server wins` exists to prevent, and
                        // the shape a partial revert after a 412 used to leave behind. One
                        // `PROPFIND Depth: 0` is what the phone does not know, so it is asked for.
                        //
                        // What the answer says decides more than the header. Every path that stores
                        // a text on a row stores the ETag the server named for it — the listing's,
                        // the multiget's, or this same PROPFIND after a `PUT` the server answered
                        // without one — so a row that has a text and no ETag, on a server that
                        // names one now, holds a text the server's current version was never read
                        // from: a revert left it there, a `412` having said the server had moved,
                        // and the restore that was to replace it has not landed. Patching the edit
                        // onto it and sending the result under the ETag just asked for would put
                        // the stale base back over the version the `412` was about, with nothing
                        // to refuse it and nothing reported — the lost update the revert exists to
                        // prevent, through the machinery meant to repair it. So it is not sent: the
                        // resource goes to the restore's conflicted half, where the server's copy
                        // replaces the rows and the run reports it, as it reports every conflict.
                        //
                        // Only a server that names no ETag at all leaves the `PUT` to go, under
                        // `If-Match: *`: such a row's text can be nothing but the version the
                        // server holds, there being no other version for it to have named, and
                        // holding the edit back would stand it still forever.
                        var ifMatch = change.etag
                        if (!creating && ifMatch == null) {
                            ifMatch = session.etagOf(target)
                            if (ifMatch != null) {
                                pending++
                                held += target
                                stale += target
                                continue
                            }
                        }

                        val action = answerOf(
                            session.put(
                                href = target,
                                body = body.text,
                                contentType = contentTypeOf(collection),
                                ifMatch = ifMatch,
                                // A server that names no ETag for the resource leaves nothing to be
                                // conditional on but its existence, and that much is still said.
                                ifMatchAny = !creating && ifMatch == null,
                                ifNoneMatchAny = creating,
                            ),
                        )

                        when (action) {
                            is ChangeAction.MarkUploaded -> {
                                // The key is the path the server named the item by, which for a
                                // create need not be the one that was PUT to.
                                val key = if (creating)
                                    adoptKey(target, action.location, session.collectionUrl)
                                else target

                                if (key == null) {
                                    // A Location outside the Collection is one no listing of it will
                                    // ever name, so a row keyed by it is one `deleteMissing` removes
                                    // on the next run. The row stays DIRTY instead.
                                    pending++
                                    held += target
                                    continue
                                }

                                // A 2xx that named no ETag leaves the row with nothing the next
                                // listing can compare, so the item this run just sent is fetched
                                // straight back. One PROPFIND is cheaper than that download, and it
                                // closes the window where the fetch overwrites an edit made in the
                                // seconds since. A server with no ETag for it either leaves null,
                                // which is the path an ETag-less item already takes.
                                val etag = action.etag ?: session.etagOf(key)

                                val stored = mapper.markUploaded(
                                    account, collection, change, key, body.uid, etag, body.text,
                                )
                                if (stored) {
                                    uploaded++
                                } else {
                                    // The row moved while the answer was in flight: the edit that
                                    // arrived during the request is still pending, and the next run
                                    // sends it.
                                    pending++
                                    held += key
                                }
                            }

                            ChangeAction.Revert -> {
                                // Server wins, for a create as much as for an update. The one
                                // realistic 412 on a create is this app's own answer going missing,
                                // and retrying under a fresh name would leave the item the server did
                                // store behind as a duplicate.
                                //
                                // A create is reverted under the name it was PUT to, not under its
                                // own null key: the row has to carry that key before the resource is
                                // fetched back, or nothing links the row to the resource the server
                                // already holds and the fetch inserts it a second time.
                                val reverting = if (creating) change.copy(key = target) else change
                                // A revert the provider withheld means the row is newer than the
                                // 412, so this run neither resolved the conflict nor may fetch over
                                // it: both would discard the edit made while the server said no.
                                if (mapper.revertLocalChange(account, collection, reverting, sent = true)) {
                                    conflicts += (change.key ?: target)
                                } else {
                                    pending++
                                    held += (change.key ?: target)
                                }
                            }

                            ChangeAction.Purge -> error("a PUT is never answered like a DELETE")
                        }
                    }
                }
            } catch (e: AccountVanishedException) {
                throw e
            } catch (e: Exception) {
                val error = errors.classify(e, session.lastMethod)

                // Retryable: the listing would meet the same wall, so the Collection ends here and
                // every change that was not sent — this one included — stays DIRTY for the next run.
                //
                // Terminal too, and for a sharper reason: a credential failure, a certificate that
                // is gone or a proxy standing in the way is not this item's problem, and swallowing
                // it here would report a run that could not authenticate as a Collection with a
                // pending edit. The classes that stay per-item are the ones that are genuinely about
                // the body sent — the server refusing this method or this content, and the DAV
                // condition it answered with.
                if (error.errorClass.retryable || error.errorClass.terminal)
                    return Uploads(
                        uploaded = uploaded,
                        pending = pending + (changes.size - index),
                        conflicts = conflicts,
                        held = held,
                        stale = stale,
                        error = error,
                    )

                // The server will answer the same way next time, and it answered about this body:
                // the row stays DIRTY, is counted pending, and the run carries on to the listing.
                pending++
                change.key?.let { held += it }
            }
        }

        return Uploads(
            uploaded = uploaded,
            pending = pending,
            conflicts = conflicts,
            held = held,
            stale = stale,
        )
    }

    /**
     * §6 invariant 2, asserted rather than commented.
     *
     * A listing that did not complete says nothing about the members it never reached, and deleting
     * on that evidence removes rows the server still has. [Members] is completed only by a collect
     * that ran to its end; the flag is checked again here because this is the one call whose mistake
     * loses user data rather than traffic.
     */
    private fun deleteMissing(
        account: Account,
        collection: DavCollection,
        members: Members,
        keep: Set<String>,
    ): Int {
        check(members.completed) {
            "refusing to delete rows of ${collection.id}: the member listing did not complete"
        }
        return mapper.deleteMissing(account, collection, keep)
    }

    /**
     * Records the answer the sync-collection probe produced.
     *
     * The state is written with the CTag it already had: the Collection is not complete, so nothing
     * new may be claimed about it — only the capability cache moves.
     */
    private fun cacheCapability(
        account: Account,
        collection: DavCollection,
        state: CollectionState,
        supported: Boolean,
        at: Long,
    ) {
        mapper.writeState(
            account,
            collection,
            state.copy(supportsSyncCollection = supported, capabilityCheckedAt = at),
        )
    }

    /**
     * §6: a trusted "unsupported" answer is re-probed weekly, or as soon as it fails. A "supported"
     * answer needs no separate probe — asking again would be the request we were about to make.
     */
    private fun mayAttemptSyncCollection(state: CollectionState, now: Long): Boolean =
        when (state.supportsSyncCollection) {
            null, true -> true
            false -> now - state.capabilityCheckedAt >= SYNC_COLLECTION_REPROBE_MS
        }

    /**
     * §7: the provider reaps the rows of an ACCOUNT_TYPE/ACCOUNT_NAME missing from AccountManager,
     * and a user can remove the Account mid-run — most likely during a long first sync. So this is
     * asked at the start and again before every batch commit.
     */
    private fun ensureAccountRegistered(account: Account) {
        try {
            mapper.assertAccountRegistered(account)
        } catch (e: Exception) {
            throw AccountVanishedException(e)
        }
    }

    /** The Collections this authority syncs, in the Account's own order. */
    private fun collectionsOf(davAccount: DavAccount): List<DavCollection> =
        davAccount.collections.filter { it.type == collectionType && it.selected && it.available }

    /** §5's mapping onto the framework's counters. Nothing here sets up a retry of its own. */
    private fun record(result: SyncResult, error: SyncError) {
        when (error.errorClass) {
            ErrorClass.PROXY_REJECTED_CREDENTIALS,
            ErrorClass.ORIGIN_WANTS_CREDENTIALS,
            ErrorClass.CERTIFICATE_UNAVAILABLE,
            ErrorClass.CREDENTIALS_UNREADABLE,
            ErrorClass.NO_CERTIFICATE_SENT,
            ErrorClass.PROXY_INTERFERENCE,
            -> result.stats.numAuthExceptions++

            ErrorClass.TRANSPORT_FAILURE,
            ErrorClass.SERVER_ERROR,
            -> result.stats.numIoExceptions++

            ErrorClass.NOT_FOUND,
            ErrorClass.METHOD_REFUSED,
            ErrorClass.MALFORMED_RESPONSE,
            -> result.stats.numParseExceptions++

            // Class 3 is informational: it neither fails a Collection nor contributes to "Partial".
            ErrorClass.ORIGIN_REFUSED_INFO -> Unit
        }
    }

    private fun report(
        account: Account,
        outcomes: List<CollectionOutcome>,
        aborted: Boolean,
        error: SyncError?,
        automatic: Boolean,
    ) {
        reporter.onSyncFinished(
            AccountSyncReport(
                account = account,
                authority = mapper.authority,
                status = statusOf(outcomes, aborted, error),
                collections = outcomes,
                aborted = aborted,
                error = error,
                finishedAt = clock(),
                automatic = automatic,
            ),
        )
    }

    /** §5's OK / Partial / Failed rollup, where one broken Collection never masks the others. */
    private fun statusOf(
        outcomes: List<CollectionOutcome>,
        aborted: Boolean,
        error: SyncError?,
    ): AccountSyncStatus = when {
        aborted || error != null -> AccountSyncStatus.FAILED
        outcomes.any { it.error?.errorClass?.terminal == true } -> AccountSyncStatus.FAILED
        outcomes.any { it.failed } -> AccountSyncStatus.PARTIAL
        else -> AccountSyncStatus.OK
    }

    private fun errorMapping(davAccount: DavAccount, http: DavHttpSession) = ErrorMapping(
        classifier = classifier,
        certificateOffered = http.certificateOffered,
        certificateConfigured = davAccount.certificate != null,
        lastExchange = { http.lastExchange },
    )

    /** §6: only classes 1, 2, 4 and 5 stop the Account's remaining Collections. */
    private val CollectionOutcome.abortsAccount: Boolean
        get() = error?.errorClass?.let { it in ACCOUNT_ABORTING_CLASSES } ?: false
}

/**
 * The Account is no longer registered.
 *
 * Continuing would write rows the provider reaps as soon as the Account is gone, and could record a
 * completed Collection for data that is about to disappear.
 */
private class AccountVanishedException(cause: Throwable) : Exception(cause)

/**
 * The hrefs whose rows step 6 must not delete.
 *
 * Two answers, because the two kinds of listing say different things. A full listing names every
 * member there is, so anything of the Collection's that it left out is gone. A delta names only
 * what changed, so the rows it justifies deleting are the ones the server reported as removed, and
 * everything else has to be kept whether or not this run touched it.
 *
 * "Everything else" must include the members fetched earlier in the same run, which is the thing
 * [local] cannot do on its own: it is read before the first write, so it holds the Collection as it
 * was. Deletion runs after the writes, and [ProviderMapper.deleteMissing] dooms every row whose href
 * is absent from this set — so a delta that both removed and added members deleted its own additions
 * on every run, and then recorded the Collection as complete, which is what kept it from ever
 * coming back. Keeping a key with no row costs nothing, and the server has just listed these.
 */
internal fun keptHrefs(fullListing: Boolean, local: Set<String>, members: Members): Set<String> =
    if (fullListing) {
        members.byKey.keys
    } else {
        (local + members.byKey.keys) - members.removed
    }

/**
 * What step U did for one Collection.
 *
 * [held] is the half of its job the fetch depends on: the keys whose rows are still dirty. Written
 * over, they would lose the edit; skipped, the next run finds them again through `pendingChanges`
 * whatever the CTag says.
 *
 * [error] is set only when step U ended the Collection — a failure the next request would meet too.
 * An item the server refused does not set it: that row is still on the phone and the Collection is
 * failed by [pending], which is a different statement from "this run could not talk to the server".
 */
private class Uploads(
    /** Changes step U is done with: the server answered for them, or there was nothing to send. */
    val uploaded: Int = 0,
    /** Changes still on the phone: rows this run could not send, or rows that moved under an answer. */
    val pending: Int = 0,
    /** Changes given up because the Collection is not writable. */
    val refused: Int = 0,
    /** The keys of the changes the server's copy replaced under the edit. */
    val conflicts: List<String> = emptyList(),
    /** Keys whose rows are still dirty, which the fetch of this run must leave alone. */
    val held: Set<String> = emptySet(),
    /**
     * Of [held], the keys step U would have `PUT` from a text stored under no version the server
     * now names: the server has an ETag for them and the row has none. They are counted pending
     * here and go to the restore's conflicted half, which un-counts the ones it resolved.
     */
    val stale: Set<String> = emptySet(),
    /** The failure that ended step U, when one did. */
    val error: SyncError? = null,
) {

    /** This Collection's part of the run, as step U left it. */
    fun outcome(collection: DavCollection, error: SyncError? = this.error): CollectionOutcome =
        CollectionOutcome(
            collection,
            error = error,
            uploaded = uploaded,
            pending = pending,
            refused = refused,
            conflicts = conflicts,
        )
}

/**
 * The order step U sends a Collection's changes in: deletions first, then creates, then updates.
 *
 * Deletions first, so that a create never meets the name a deletion is about to free, and creates
 * before updates, so that nothing an update refers to is missing. Within each kind the order is the
 * mapper's — `sortedBy` is stable — so an unchanged Collection is sent in the same sequence every run.
 */
internal fun uploadOrder(changes: List<LocalChange>): List<LocalChange> = changes.sortedBy {
    when (it.kind) {
        ChangeKind.DELETE -> 0
        ChangeKind.CREATE -> 1
        ChangeKind.UPDATE -> 2
    }
}

/**
 * Of the changes a read-only Collection refuses, the ones that may be given up.
 *
 * An update or a deletion has a server copy behind it, so clearing the local change is a revert:
 * the run's own fetch puts the server's version back and the user sees their edit undone, which is
 * what "read-only" promised.
 *
 * A create has no such copy. Clearing its flag does not undo anything — it only removes the single
 * piece of state that said the contact had never been sent, leaving it on the phone where no run
 * will look at it again. It is refused and left dirty instead, so making a Collection writable
 * later is all it takes to send it.
 */
internal fun revertibleOnRefusal(changes: List<LocalChange>): List<LocalChange> =
    changes.filter { it.kind != ChangeKind.CREATE }

/**
 * The key an accepted create is stored under: the path the server named the item by, or the one it
 * was PUT to when the server named none.
 *
 * A `Location` outside the Collection is refused — null — rather than adopted. No listing of this
 * Collection will ever name such a path, so a row keyed by it is one `deleteMissing` removes on the
 * next run, and the item the server just stored comes back as a duplicate the run after that.
 *
 * "Outside" is judged on the origin as well as the path: a path under `/books/main/` served by
 * another host is a different Collection that happens to be laid out alike, and a key is only ever
 * compared against hrefs this Collection's own listing produced.
 */
internal fun adoptKey(requestPath: String, location: Url?, collectionUrl: Url): String? {
    if (location == null) return requestPath
    val sameOrigin = location.protocol == collectionUrl.protocol &&
        location.host == collectionUrl.host &&
        location.port == collectionUrl.port
    if (!sameOrigin) return null

    val base = collectionUrl.encodedPath.let { if (it.endsWith("/")) it else "$it/" }
    return location.encodedPath.takeIf { it.startsWith(base) && it.length > base.length }
}

/** What step U does with the answer to one change: §2 to §4's answer tables as one decision. */
internal sealed interface ChangeAction {

    /** The server has the resource: store its identity, its ETag and the clean flag. */
    data class MarkUploaded(val etag: String?, val location: Url?) : ChangeAction

    /** The server does not have the resource, which is what a delete wanted: drop the tombstone. */
    data object Purge : ChangeAction

    /** The server's copy moved under the edit: give the local change up and let the fetch replace it. */
    data object Revert : ChangeAction
}

/**
 * §2 to §4's answer tables as the one function both verbs go through.
 *
 * 412 is the same statement for both and the policy is the same — the server's copy moved under the
 * edit, so the local one is given up rather than retried — which is why it is one line here and not
 * two. A DELETE's 404 is the second way a server says it does not have the item, which is what a
 * delete wanted, so it purges like any 2xx: failing a Collection for a row the server and the phone
 * already agree about is class 9's mistake, made on a write.
 */
internal fun answerOf(answer: WriteAnswer): ChangeAction = when (answer) {
    is PutAnswer.Stored -> ChangeAction.MarkUploaded(answer.etag, answer.location)
    DeleteAnswer.Gone -> ChangeAction.Purge
    PutAnswer.PreconditionFailed, DeleteAnswer.PreconditionFailed -> ChangeAction.Revert
}

/** The characters a UID may be used verbatim as a file name with; anything else gets a random name. */
private val UID_SAFE = Regex("[A-Za-z0-9._-]+")

/**
 * The path a created resource is PUT to: the Collection's own path plus `<UID>` and the extension
 * that Collection's resources carry.
 *
 * [collectionPath] is the location the session's last request left rather than the URL the Account
 * stored, because a server that redirects the Collection would otherwise have the create PUT to the
 * path it redirected *from* — a resource no later listing of that Collection names.
 *
 * The name comes from the UID `serialize` minted and persisted before this request, so a retry after
 * an answer that went missing sends the same name: a fresh one would leave the item the server did
 * store behind as a duplicate. A UID that cannot be a file name keeps its place in the body and gets
 * a random one here, the name being ours to choose and the UID not; that costs the guarantee for
 * rows this app did not mint, and nothing else.
 */
private fun createPath(collectionPath: String, collection: DavCollection, uid: String): String {
    val base = if (collectionPath.endsWith("/")) collectionPath else "$collectionPath/"
    val name = if (UID_SAFE.matches(uid)) uid else UUID.randomUUID().toString()
    return base + name + when (collection.type) {
        CollectionType.ADDRESS_BOOK -> ".vcf"
        CollectionType.CALENDAR -> ".ics"
    }
}

/** The media type a Collection's resources are sent as; RFC 6350 and RFC 5545 both mandate UTF-8. */
private fun contentTypeOf(collection: DavCollection): String = when (collection.type) {
    CollectionType.ADDRESS_BOOK -> "text/vcard; charset=utf-8"
    CollectionType.CALENDAR -> "text/calendar; charset=utf-8"
}
