package app.davkeep.sync

import android.accounts.Account
import android.content.Context
import app.davkeep.core.AccountStore
import app.davkeep.core.DavAccount
import app.davkeep.core.DavCollection
import app.davkeep.core.DavHttpClientFactory
import app.davkeep.core.SyncErrorClassifier
import app.davkeep.ui.CollectionDiscovery
import app.davkeep.ui.SyncLog
import app.davkeep.ui.updateCollections

/**
 * §8's re-enumeration, as the sync path reaches it: one walk of the Account's server, and the four
 * things a completed one ends with — the log line, the merge with what is stored, the store write
 * and the schedule.
 *
 * A seam rather than a method on [SyncEngine] because the walk's log line, the store write and the
 * schedule all live where the `Context` is, and the engine deliberately has no `Context`: it is
 * constructible with fakes, and [SyncWiring] is what keeps it so.
 *
 * The walk itself is [CollectionDiscovery], reached here the way the settings screen's Check
 * collections reaches it. This is deliberately the same walk over the same merge: there is one
 * answer to what the Account's Collections are, whether it is learned by a button or by the clock.
 */
fun interface CollectionEnumerator {

    /**
     * Walks [davAccount]'s server and, when the walk ran to the end, stores what it found.
     *
     * Its failures are results rather than exceptions: a walk that cannot be made — the client
     * factory, the probe, or a store that refuses the result — is a line in the log and a null,
     * because the run this was reached from is for the Collections already stored rather than for
     * this walk, and skipping them would trade a traffic problem for a sync that did nothing.
     *
     * @return the Account's Collections as the walk left them — a newly discovered one unselected, a
     *   Collection the server no longer lists marked unavailable — or null when there is nothing to
     *   adopt, because the walk did not run to the end or its result was not stored. Null is also
     *   what stops the caller's day from being stamped, so the next run tries again.
     */
    fun enumerate(account: Account, davAccount: DavAccount): List<DavCollection>?
}

/**
 * §8's walk, reached once a day instead of from a button.
 *
 * The settings screen's sequence, in the settings screen's order: discover, log every attempt, and
 * — only for a walk that ran to the end — merge with what is stored, save, and re-apply the
 * schedule. The order is what makes the sequence safe to repeat daily: the log is written before
 * anything is trusted, and the store is not touched by a walk that learned nothing.
 *
 * New Collections arrive unselected, which is [CollectionDiscovery.merge]'s promise and §8's: a
 * calendar that appeared on the server must not start writing into the phone because a timer went
 * off. What the merge does change is the vanished direction — a stored Collection the server no
 * longer lists is marked unavailable rather than deleted, and unavailable Collections are not
 * synced.
 *
 * The merge starts from the record as it is stored when the walk ends, not from the copy the run
 * loaded before the walk began. Discovery spends seconds on the network, and the settings screens
 * write the same record in that time: a `writable` switch turned off while the walk was out was
 * turned back on by a merge over the run's snapshot, and the next run sent edits to a Collection
 * the user had just declared read-only. [updateCollections] is the settings screens' own path for
 * the same reason, and going through it here is what makes the two writers agree.
 */
internal class DailyEnumeration(
    context: Context,
    private val factory: DavHttpClientFactory,
    private val classifier: SyncErrorClassifier,
    private val store: AccountStore,
) : CollectionEnumerator {

    private val appContext = context.applicationContext

    override fun enumerate(account: Account, davAccount: DavAccount): List<DavCollection>? = try {
        walk(account, davAccount)
    } catch (e: Exception) {
        // §8: every attempt is surfaced with its outcome, and a walk that could not make its first
        // request is one of them — otherwise an Account whose server always fails here would simply
        // never enumerate and nothing would say why. Only the exception's kind is logged: its
        // message is free text this class did not phrase, and the log's redaction is structural.
        note(davAccount.label, "discovery did not run: ${e.javaClass.simpleName}")
        null
    }

    private fun walk(account: Account, davAccount: DavAccount): List<DavCollection>? {
        val outcome = CollectionDiscovery.discover(
            factory = factory,
            classifier = classifier,
            davAccount = davAccount,
        )
        // Before anything is trusted, and at the level the two outcomes someone comes back to the
        // log to understand deserve: a walk that did not run to the end, and one that found nothing.
        SyncLog(appContext).appendDiscovery(
            account = davAccount.label,
            level = if (outcome.completed && outcome.collections.isNotEmpty()) {
                SyncLog.Level.INFO
            } else {
                SyncLog.Level.WARN
            },
            notes = outcome.notes,
        )
        if (!outcome.completed) return null

        // The store, not the Account record: a walk rewrites the selection and nothing else, and
        // AccountStore.save is total — it would take the header names and the password this walk
        // never read as removals.
        var stored: List<DavCollection> = davAccount.collections
        val merged = store.updateCollections(account) { current ->
            stored = current
            CollectionDiscovery.merge(current, outcome.collections)
        }
        // Only when the walk moved the Account's syncable set. Applying a selection reschedules
        // every authority — removePeriodicSync then addPeriodicSync — and rescheduling the authority
        // this run belongs to cancels this run, which the Collection loop then stops on at its first
        // check. There is nothing to apply on a day the server's list has not moved, so the run
        // keeps the sync it was woken for; on a day it has, the run that follows is the one that
        // syncs what this one selected. (A Collection discovered here arrives unselected, which on
        // its own never changes the answer, so a new one costs no reschedule either.) The comparison
        // is against the record the merge started from, for the same reason the merge is.
        if (hasSyncableCollections(merged) != hasSyncableCollections(stored)) {
            SyncScheduler.applySelection(appContext, account, merged)
        }
        return merged
    }

    private fun note(label: String, line: String) {
        SyncLog(appContext).appendDiscovery(
            account = label,
            level = SyncLog.Level.WARN,
            notes = listOf(line),
        )
    }
}
