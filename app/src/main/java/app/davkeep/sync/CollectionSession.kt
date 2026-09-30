package app.davkeep.sync

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.ktor.DavAddressBook
import at.bitfire.dav4jvm.ktor.DavCalendar
import at.bitfire.dav4jvm.ktor.DavCollection as DavCollectionResource
import at.bitfire.dav4jvm.ktor.MultiStatusItem
import at.bitfire.dav4jvm.ktor.Response as DavResponse
import at.bitfire.dav4jvm.ktor.exception.DavException
import at.bitfire.dav4jvm.ktor.exception.NotFoundException
import at.bitfire.dav4jvm.ktor.exception.PreconditionFailedException
import at.bitfire.dav4jvm.property.caldav.CalDAV
import at.bitfire.dav4jvm.property.caldav.CalendarData
import at.bitfire.dav4jvm.property.caldav.GetCTag
import at.bitfire.dav4jvm.property.carddav.AddressData
import at.bitfire.dav4jvm.property.webdav.GetETag
import at.bitfire.dav4jvm.property.webdav.ResourceType
import at.bitfire.dav4jvm.property.webdav.SyncToken
import at.bitfire.dav4jvm.property.webdav.WebDAV
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.protocolWithAuthority
import app.davkeep.core.CollectionType
import app.davkeep.core.DavCollection
import app.davkeep.core.RemoteItem

/** The DAV status with which `sync-collection` reports a member that is gone (RFC 6578). */
private const val REMOVED_STATUS = 404

/** Server answers that mean "I don't do sync-collection" when they carry no condition element. */
private val UNSUPPORTED_REPORT_STATUS = setOf(405, 501)

/**
 * The DAV status a server sends when it answered with less than was asked for (RFC 4918 §11.5).
 *
 * It arrives as a response element of its own inside the multistatus, so a truncated listing reads
 * as an ordinary one until this is looked for: everything before it is real, and everything after
 * it was never sent. Treating that as a complete listing is what licenses deleting the remainder.
 */
private const val TRUNCATED_STATUS = 507

/** The same statement made as a condition, for a server that sends it under another status. */
private val NUMBER_OF_MATCHES_WITHIN_LIMITS =
    Property.Name(WebDAV.NS_WEBDAV, "number-of-matches-within-limits")

/**
 * How many pages of a truncated `sync-collection` one run follows before it reports the listing
 * truncated and stops. A bound this side states beats a loop the server's paging controls.
 */
private const val MAX_REPORT_PAGES = 10

/**
 * One Collection's DAV access for one sync run.
 *
 * Every request builds a fresh [DavResource] at [location] and is collected before the next one
 * starts. dav4jvm rewrites a resource's location in place when it follows a redirect while the flows
 * it returns are lazy (dav4jvm#209), so the location is carried here instead: no two flows ever come
 * from one resource, and the redirect the server applied is what the next request uses.
 */
internal class CollectionSession(
    private val http: HttpClient,
    private val collection: DavCollection,
    /** Called before each DAV operation, so evidence never outlives the request that produced it. */
    private val onOperationStart: () -> Unit = {},
) {

    private var location: Url = Url(collection.url)

    /**
     * The Collection's own URL, as the last request left it: the location a redirect settled on.
     *
     * Creates are addressed from this and a new item's key is checked against it, rather than
     * against the URL the Account stored. After a redirect the two differ, and a create PUT to the
     * path it redirected *from* is a resource no listing of the Collection will ever name.
     */
    val collectionUrl: Url get() = location

    /** The DAV method of the last request, for §5's evidence. */
    var lastMethod: String? = null
        private set

    /** §6 step 1: the cheap check. Returns the Collection's CTag, or null when it has none. */
    suspend fun ctag(): String? {
        onOperationStart()
        val resource = newResource()
        lastMethod = "PROPFIND"

        var ctag: String? = null
        // Depth 0 addresses exactly the Collection, so its own response is the one that answers.
        resource.propfind(0, CalDAV.GetCTag).collect { item ->
            if (item is MultiStatusItem.Response && item.response.isSuccess())
                ctag = item.response[GetCTag::class.java]?.cTag
        }

        location = resource.location
        return ctag
    }

    /**
     * §6 step 2: the listing.
     *
     * The flow is collected to its end, which is what [Members.completed] records — a listing that
     * was cut short must never be mistaken for a complete one.
     */
    suspend fun members(): Members {
        val members = Members()
        onOperationStart()
        val resource = newResource()
        lastMethod = "PROPFIND"

        resource.propfind(1, WebDAV.GetETag, WebDAV.ResourceType).collect { item ->
            if (item is MultiStatusItem.Response) members.add(item)
        }

        location = resource.location
        members.markCompleted()
        return members
    }

    /**
     * §6: the `sync-collection` REPORT that collapses steps 1 and 2 where it works.
     *
     * The first attempt *is* the support probe: `<supported-report/>` is the negative answer, so
     * detection costs nothing beyond the request. Returns null when the server refused, and throws
     * for every other failure — those are the Collection's failure, not a licence to try again.
     *
     * Two answers are handled here rather than passed up, because each is the server saying how to
     * ask again rather than that the Collection failed:
     *
     * - **a rejected token** (RFC 6578 §3.7), which obliges a full re-list. This run does it now and
     *   says so through [Report.relisted]: a token the server has forgotten is evidence about
     *   nothing, and keeping it only buys the same refusal on every run from here on.
     * - **a truncated answer** (RFC 6578 §3.6), which carries the token the next page starts from.
     *   The run follows up to [MAX_REPORT_PAGES] of them; if the server is still holding members
     *   back after that, the listing stays marked truncated so that nothing downstream mistakes it
     *   for the whole Collection.
     */
    suspend fun reportChanges(syncToken: String?): Report? {
        val members = Members()

        var page = requestChanges(members, syncToken)
        var relisted = false
        if (page is Page.TokenRejected) {
            members.clear()
            relisted = true
            page = requestChanges(members, null)
        }

        var listing = when (page) {
            Page.Unsupported -> return null
            // A server that answers "no such token" to a request that carried none is not one this
            // can argue with; polling asks for the same Collection without a token at all.
            Page.TokenRejected -> return null
            is Page.Listed -> page
        }

        var pages = 1
        while (members.truncated && listing.token != null && pages < MAX_REPORT_PAGES) {
            // Each page answers for itself, so the flag is cleared before asking: the page that
            // stops setting it is the one that finished the listing.
            members.clearTruncated()
            listing = requestChanges(members, listing.token) as? Page.Listed ?: break
            pages++
        }

        members.markCompleted()
        // A request that carried no token enumerated every member, which is a full listing.
        return Report(
            members = members,
            token = listing.token,
            fullListing = syncToken == null || relisted,
            relisted = relisted,
        )
    }

    /** One REPORT, adding whatever it reported to [members]. */
    private suspend fun requestChanges(members: Members, syncToken: String?): Page {
        onOperationStart()
        val resource = newResource()
        lastMethod = "REPORT"
        var token: String? = null

        try {
            // Depth "1" — the members of this Collection, not of nested ones.
            resource.reportChanges(syncToken, false, null, WebDAV.GetETag, WebDAV.ResourceType)
                .collect { item ->
                    when (item) {
                        is MultiStatusItem.ExtraProperty ->
                            (item.property as? SyncToken)?.let { token = it.token }
                        is MultiStatusItem.Response -> members.add(item)
                    }
                }
        } catch (e: DavException) {
            if (e.refusesSyncCollection()) return Page.Unsupported
            if (e.rejectsSyncToken()) return Page.TokenRejected
            throw e
        }

        location = resource.location
        return Page.Listed(token)
    }

    /**
     * §6 step 3: one batch of bodies, requested by href.
     *
     * The responses are keyed exactly as the listing keyed them, because both derive the key from the
     * href with the same rule; a body that arrives under an href we did not ask for is ignored rather
     * than invented a row for.
     *
     * Each body carries the ETag its own response named, which is what lets a resource be fetched
     * without a listing to read an ETag from: the multiget REPORT asks for `getetag` beside the data,
     * so the two arrive together and describe the same version. A caller that already has the
     * listing's ETag keeps using that one.
     *
     * A response that names a status of its own is read before the success check, the way a listing
     * reads one: a server answering `404` for an href has said by name that it no longer has that
     * resource, which is a different statement from not answering at all, and only a caller that can
     * tell the two apart can ever converge on the first.
     */
    suspend fun multiget(hrefs: List<Url>): Fetched {
        val bodies = LinkedHashMap<String, FetchedBody>()
        val gone = LinkedHashSet<String>()
        onOperationStart()
        val resource = newResource()
        lastMethod = "REPORT"

        val flow = when (resource) {
            is DavAddressBook -> resource.multiget(hrefs)
            is DavCalendar -> resource.multiget(hrefs)
            else -> error("${collection.type} has no multiget")
        }
        flow.collect { item ->
            if (item !is MultiStatusItem.Response) return@collect

            val response = item.response
            if (response.status?.value == REMOVED_STATUS) {
                keyOf(response)?.let { gone += it }
                return@collect
            }
            if (!response.isSuccess()) return@collect

            val key = keyOf(response)
            if (key == null) return@collect

            bodyOf(response)?.let {
                bodies[key] = FetchedBody(it, storedEtag(response[GetETag::class.java]?.eTag))
            }
        }

        location = resource.location
        return Fetched(bodies, gone)
    }

    /**
     * The URL of one member, built the way every other request in this class builds one: on the
     * location the last request left, so a Collection the server redirected stays followed.
     */
    fun memberUrl(href: String): Url = Url(location.protocolWithAuthority + href)

    /**
     * §6 step U: one resource, sent whole.
     *
     * [href] is a path inside the Collection — the shape every key in this class has, because it is
     * the href's path that survives a redirect, a scheme change or a proxy. The request is built at
     * the location the last request left rather than at the URL the Account stored, so a server that
     * redirects is answered the same way here as it is for a listing; a fresh resource per request
     * for dav4jvm#209, the same as everywhere else in this class.
     *
     * The conditional headers are the caller's, because only it knows what the row's state is: an
     * update carries `If-Match` with the ETag it last stored, a create carries `If-None-Match: *` so
     * the name it minted cannot overwrite an item this run has not seen. [ifMatchAny] is the third
     * case and the weakest: an update of a resource the server holds under a version this phone
     * cannot name. It says only that the resource still exists, which is the one thing that is
     * known, and it is sent rather than nothing at all because an unconditional `PUT` of a resource
     * the server already has is the lost update the whole write path exists to prevent.
     *
     * A `404` to a conditional update is read as the precondition failing, which is what RFC 9110
     * says of `If-Match` against a resource with no current representation and what Radicale and
     * sabre answer `412` for. An origin that answers `404` instead — or a proxy in front of one —
     * is saying the same thing by another number: the version this phone holds is not the one the
     * server has, there being none. Left as an error it was neither retried nor resolved, and the
     * row was counted pending and held out of every listing on every run for good; as a refusal it
     * takes the conflict path, the restore asks for the resource by name, and the server's `404`
     * there is the answer that removes the rows or makes them a create again. A create's `404` is
     * still an error: it is about the Collection, not the resource.
     */
    suspend fun put(
        href: String,
        body: String,
        contentType: String,
        ifMatch: String?,
        ifMatchAny: Boolean,
        ifNoneMatchAny: Boolean,
    ): PutAnswer {
        onOperationStart()
        val resource = resourceAt(href)
        lastMethod = "PUT"

        val headers = Headers.build {
            when {
                ifMatch != null -> append(HttpHeaders.IfMatch, entityTag(ifMatch))
                ifMatchAny -> append(HttpHeaders.IfMatch, "*")
            }
            if (ifNoneMatchAny) append(HttpHeaders.IfNoneMatch, "*")
        }

        return try {
            resource.put<PutAnswer>(
                TextContent(body, ContentType.parse(contentType)),
                headers,
            ) { response ->
                PutAnswer.Stored(
                    etag = storedEtag(response.headers[HttpHeaders.ETag]),
                    location = locationOf(response.headers[HttpHeaders.Location], resource.location),
                )
            }
        } catch (e: PreconditionFailedException) {
            // The answer, not a failure: the precondition the row's own state dictated did not hold.
            PutAnswer.PreconditionFailed
        } catch (e: NotFoundException) {
            if (ifMatch == null && !ifMatchAny) throw e
            PutAnswer.PreconditionFailed
        }
        // [location] is deliberately not moved here. It is the Collection's own address, followed
        // across redirects so that the listing and the multiget keep addressing the right place; a
        // member's final URL says nothing about it. Adopting it would point every later request in
        // this run at the item just written, which answers 404 for a REPORT.
    }

    /**
     * §6 step U: one resource, removed.
     *
     * A 404 is an answer rather than a failure (§2 of the lifecycle): the server and the phone agree
     * the item is gone, and the tombstone is dropped either way. `If-Match` is sent whenever the row
     * has an ETag; a row that has none is deleted unconditionally, because the server has given
     * nothing to condition on and refusing to ask would leave the tombstone there forever.
     */
    suspend fun delete(href: String, ifMatch: String?): DeleteAnswer {
        onOperationStart()
        val resource = resourceAt(href)
        lastMethod = "DELETE"

        val headers = Headers.build {
            ifMatch?.let { append(HttpHeaders.IfMatch, entityTag(it)) }
        }

        return try {
            resource.delete<DeleteAnswer>(headers) { DeleteAnswer.Gone }
        } catch (e: NotFoundException) {
            // The server does not have it, which is what the delete wanted.
            DeleteAnswer.Gone
        } catch (e: PreconditionFailedException) {
            DeleteAnswer.PreconditionFailed
        }
        // [location] is left where it was, for the reason given in [put].
    }

    /**
     * The ETag of one member: asked for when a `PUT` was accepted without naming it, and when an
     * update is about to be sent for a row that holds none.
     *
     * RFC 9110 does not oblige a server to answer a write with an `ETag`, and Radicale is not the
     * only one that sometimes does not. Storing null instead costs a download: the listing cannot
     * compare a null against what the server reports, so the item this run just uploaded is fetched
     * straight back, and an edit made in the seconds between is overwritten by the body the server
     * already has. One extra `PROPFIND Depth: 0` buys that back.
     *
     * Null when the server has no ETag for it either — then the row keeps a null and takes the same
     * path an ETag-less item has always taken — and null when the server does not have the member
     * at all, which is not a failure of this run: the caller sends its `PUT` under `If-Match: *`,
     * the server refuses it, and the conflict path puts the Collection's own answer on the row.
     */
    suspend fun etagOf(href: String): String? {
        onOperationStart()
        val resource = resourceAt(href)
        lastMethod = "PROPFIND"

        var etag: String? = null
        try {
            resource.propfind(0, WebDAV.GetETag).collect { item ->
                if (item is MultiStatusItem.Response && item.response.isSuccess())
                    etag = item.response[GetETag::class.java]?.eTag
            }
        } catch (e: NotFoundException) {
            return null
        }
        return storedEtag(etag)
    }

    /**
     * An ETag as `If-Match` must spell it: an entity-tag carries its quotes (RFC 9110 §8.8.3), and a
     * server compares the whole token including them.
     *
     * The two places an ETag reaches a row disagree about that. A `PUT`'s `ETag` header arrives
     * quoted and is stored as it came, while the listing's `getetag` is parsed out of XML with the
     * quotes already stripped — so a row's stored value may be either spelling. Sent back bare, a
     * server that quotes its tags sees no match and answers 412, which this app reads as "the
     * server's copy moved" and gives the user's edit up. Found on a device: every second edit to
     * the same item was lost that way, with a conflict reported for a change nobody else had made.
     */
    private fun entityTag(value: String): String {
        val trimmed = value.trim()
        val quoted = trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2
        return if (quoted || trimmed.startsWith("W/\"")) trimmed else "\"$trimmed\""
    }

    private fun newResource(): DavCollectionResource = resourceAt(location)

    /** A resource for one request: the Collection itself, or one member of it. */
    private fun resourceAt(url: Url): DavCollectionResource = when (collection.type) {
        CollectionType.ADDRESS_BOOK -> DavAddressBook(http, url)
        CollectionType.CALENDAR -> DavCalendar(http, url)
    }

    /** The resource a member's path addresses, built on the location the last request left. */
    private fun resourceAt(href: String): DavCollectionResource =
        resourceAt(Url(location.protocolWithAuthority + href))

    /**
     * A `Location` header as a URL, or null when there is none worth adopting.
     *
     * `Location` is a URI reference (RFC 7231 §7.1.2), so a server may send a path rather than an
     * absolute URL, and both spellings are resolved against the request that was just answered. A
     * value that parses as neither is reported as no `Location` at all, which leaves the caller
     * keying the item by the path it PUT to — always inside the Collection.
     */
    private fun locationOf(header: String?, request: Url): Url? {
        val value = header?.takeIf { it.isNotBlank() } ?: return null
        val target = when {
            "://" in value -> value
            value.startsWith("/") -> request.protocolWithAuthority + value
            else -> request.protocolWithAuthority + request.encodedPath.substringBeforeLast('/') + "/" + value
        }
        return runCatching { Url(target) }.getOrNull()
    }

    private fun bodyOf(response: DavResponse): String? = when (collection.type) {
        CollectionType.ADDRESS_BOOK -> response[AddressData::class.java]?.card
        CollectionType.CALENDAR -> response[CalendarData::class.java]?.iCalendar
    }
}

/**
 * One member's body as a multiget answered for it, with the ETag that same response named.
 *
 * The two belong together: an ETag read anywhere other than beside the body it describes may name a
 * version the body is not, and a row stored that way would claim to be current when it is not.
 */
internal class FetchedBody(val text: String, val etag: String?)

/** One multiget's answer: the bodies it carried, and the hrefs the server said it no longer has. */
internal class Fetched(val bodies: Map<String, FetchedBody>, val gone: Set<String>)

/** The answer to one `PUT` or `DELETE`, as §2 to §4's answer tables read it. */
internal sealed interface WriteAnswer

/** What one `PUT` answered. */
internal sealed interface PutAnswer : WriteAnswer {

    /** 2xx: the server has the resource. [etag] and [location] are what it returned, either null. */
    data class Stored(val etag: String?, val location: Url?) : PutAnswer

    /** 412: the precondition the row's own state dictated did not hold. */
    data object PreconditionFailed : PutAnswer
}

/** What one `DELETE` answered. */
internal sealed interface DeleteAnswer : WriteAnswer {

    /** 2xx, or 404 for an item the server no longer has: either way it is not there any more. */
    data object Gone : DeleteAnswer

    /** 412: the server's item moved after this phone last saw it. */
    data object PreconditionFailed : DeleteAnswer
}

/** What one `sync-collection` REPORT answered. */
private sealed interface Page {
    /** `<supported-report/>`: this server does not do `sync-collection` at all. */
    data object Unsupported : Page

    /** `<valid-sync-token/>`: the token this run sent is not one the server still knows. */
    data object TokenRejected : Page

    /** A listing, carrying the token to send next — null when the server issued none. */
    data class Listed(val token: String?) : Page
}

/** One `sync-collection` REPORT that ran to completion. */
internal class Report(
    val members: Members,
    /** The token to send next time, or null when the server issued none. */
    val token: String?,
    /** True when the request enumerated every member, because no sync token was sent. */
    val fullListing: Boolean,
    /**
     * True when the server rejected the token this run started with and the run listed in full
     * instead. Worth reporting once: it explains a run that suddenly cost a whole listing.
     */
    val relisted: Boolean = false,
)

/**
 * The members one request reported, and whether that request ran to completion.
 *
 * [completed] is the licence to delete, and it is earned twice over: the collect has to have run to
 * its end — a listing cut short by an I/O error or a cancelled sync says nothing about the members
 * it never reached — and the server must not have said it was holding members back. A server that
 * truncates answers a well-formed multistatus, so without [truncated] a short page reads as the
 * whole Collection and every member it omitted looks deleted.
 */
internal class Members {

    /** href path -> member, in the order the server reported them. */
    val byKey = LinkedHashMap<String, RemoteItem>()

    /** Members the server explicitly reported as gone; `sync-collection` reports removals this way. */
    val removed = LinkedHashSet<String>()

    var completed = false
        private set

    /** True when the server said this answer holds less than was asked for. */
    var truncated = false
        private set

    fun markCompleted() {
        completed = !truncated
    }

    /** Forgets everything reported so far: used when a run starts its listing over. */
    fun clear() {
        byKey.clear()
        removed.clear()
        truncated = false
    }

    /** Forgets only the truncation, so the next page answers for itself. */
    fun clearTruncated() {
        truncated = false
    }

    fun add(item: MultiStatusItem.Response) {
        val response = item.response

        // Read before the success check and before the member check: the marker is neither a
        // success nor a member, and it is the one response whose absence would be believed.
        if (response.isTruncation()) {
            truncated = true
            return
        }

        if (response.status?.value == REMOVED_STATUS) {
            keyOf(response)?.let { removed += it }
            return
        }
        if (!response.isSuccess()) return

        // The Collection itself, and anything outside it, is not a member.
        if (item.relation != DavResponse.HrefRelation.MEMBER) return

        // Nor is a nested Collection, which would otherwise be treated as an item of its parent.
        if (response[ResourceType::class.java]?.types?.contains(WebDAV.Collection) == true) return

        val key = keyOf(response) ?: return
        byKey[key] = RemoteItem(href = response.href.toString(), etag = response[GetETag::class.java]?.eTag)
    }
}

/**
 * Whether this response is the server saying it cut the answer short.
 *
 * Either spelling counts: the status on its own, which is what RFC 4918 gives it, or the condition,
 * which some servers send under a 200 for the Collection itself.
 */
/**
 * An ETag as a row stores it: the opaque value, without the quotes or the weak marker.
 *
 * The two sources disagree on spelling. A `PUT`'s `ETag` header arrives as the wire spells it,
 * quoted; the listing's `getetag` is parsed out of XML with the quotes already stripped. Store the
 * quoted form and the next listing compares `"abc"` against `abc`, finds a difference that is not
 * there, and downloads the resource this run has just uploaded — overwriting, in the seconds
 * between, any edit the user made after it was sent. Normalising here is what makes the two
 * comparable; [entityTag] puts the quotes back on the way out.
 */
internal fun storedEtag(value: String?): String? {
    val trimmed = value?.trim()?.removePrefix("W/")?.trim() ?: return null
    if (trimmed.isEmpty()) return null
    return trimmed.removeSurrounding("\"").ifEmpty { null }
}

private fun DavResponse.isTruncation(): Boolean =
    status?.value == TRUNCATED_STATUS ||
        error?.any { it.name == NUMBER_OF_MATCHES_WITHIN_LIMITS } == true

/**
 * The identity of a member, and the key every mapper sees: the href's path exactly as the server
 * returned it, with its percent-encoding, and without scheme, host or a normalisation of our own.
 *
 * The path is what stays stable when a server redirects, changes scheme or answers through a proxy,
 * and it is the one part of the href its own `<href>` element fully determines.
 *
 * Null for a href with no path of its own — the Collection root — which is never a member.
 */
private fun keyOf(response: DavResponse): String? =
    response.href.encodedPath.takeIf { it.isNotEmpty() && it != "/" }

/**
 * True when the server answered the REPORT with "I don't support that".
 *
 * The condition is the documented answer; a refused method is the same statement in a blunter form.
 * Some servers send the condition without an XML content type, in which case dav4jvm's exception
 * carries the body excerpt but no parsed error element.
 */
private fun DavException.refusesSyncCollection(): Boolean =
    errors.any { it.name == WebDAV.SupportedReport } ||
        responseExcerpt?.contains("supported-report") == true ||
        statusCode in UNSUPPORTED_REPORT_STATUS

/**
 * True when the server answered the REPORT with "that token is not one of mine".
 *
 * RFC 6578 §3.7 makes this a 403 carrying `<valid-sync-token/>`, and the documented answer to it is
 * to list the Collection in full — so it is not a failure, it is an instruction. Matched the same
 * two ways as the refusal above, because the servers that send the condition without an XML content
 * type send this one that way too.
 */
private fun DavException.rejectsSyncToken(): Boolean =
    errors.any { it.name == WebDAV.ValidSyncToken } ||
        responseExcerpt?.contains("valid-sync-token") == true
