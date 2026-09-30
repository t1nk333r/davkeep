package app.davkeep.provider.contacts

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.parameter.ImageType
import ezvcard.property.Address
import ezvcard.property.Birthday
import ezvcard.property.Categories
import ezvcard.property.FormattedName
import ezvcard.property.RawProperty
import ezvcard.property.Uid
import ezvcard.property.VCardProperty
import ezvcard.util.PartialDate
import java.time.Instant
import java.time.LocalDate
import ezvcard.property.Email as EzEmail
import ezvcard.property.Nickname as EzNickname
import ezvcard.property.Note as EzNote
import ezvcard.property.Organization as EzOrganization
import ezvcard.property.Photo as EzPhoto
import ezvcard.property.StructuredName as EzStructuredName
import ezvcard.property.Telephone as EzTelephone
import ezvcard.property.Title as EzTitle
import ezvcard.property.Url as EzUrl

/**
 * The bytes of one contact for upload, the UID they carry, and whether they say anything new.
 *
 * [unchanged] compares the card as the rows left it with the card as the source spelled it, both
 * written by the same writer and both before the `PRODID` and `REV` below are stamped: equal means
 * every property this app owns came out the way the server already has it. `DIRTY` is set by writes
 * that touch no property at all — `STARRED`, a ringtone, `SEND_TO_VOICEMAIL` — and this is what
 * tells such a row from one the user typed into.
 */
internal class ContactBytes(val text: String, val uid: String?, val unchanged: Boolean = false)

/**
 * What the contact's photo row says about `PHOTO`.
 *
 * "Unchanged" cannot be decided by comparing bytes against the source: the provider re-encodes an
 * image it is given, so the row can never be equal to it. It is decided by a digest of the row's
 * bytes against the one the mapper recorded when it last wrote or sent them (see `ContactsMapper`).
 */
internal sealed interface PhotoEdit {
    /** The row changed since the source was written: the property is rebuilt from these bytes. */
    class Rebuilt(val bytes: ByteArray) : PhotoEdit

    /** The row is unchanged: the source `PHOTO`, whatever form it took, is copied through. */
    object Kept : PhotoEdit

    /** The row is gone: the source `PHOTO` goes with it. */
    object Dropped : PhotoEdit
}

/**
 * What the contact's membership rows say about `CATEGORIES`: the titles of the groups the rows name,
 * and the titles of the groups this Collection has at all.
 *
 * The second set is what separates a value the user removed from one the read path could never
 * resolve. A `CATEGORIES` value naming no local group was never shown to the user — the read path
 * counted it unresolved — so the user cannot have deleted it, and it is preserved.
 */
internal class CategoryEdit(val titles: List<String>, val groupTitles: Set<String>)

/** This app's `PRODID`, which replaces whatever the source had on every write. */
private const val PRODID = "-//satr//dav-provider-android//EN"

/**
 * Rewrites one contact for upload: [source] with the property classes this app maps produced from
 * [rows], or a fresh vCard built from [rows] when the row has no source copy at all.
 *
 * Ownership is decided per property class, never per value (issue #28, "Serialisation, contacts"): a
 * class this app maps is regenerated from the rows, a class it does not map is copied through exactly
 * as the server wrote it, parameters and all. A property is dropped only when the source derived a
 * row from it and no current row carries that row's handle — which is why a deleted phone number
 * disappears while an `X-ABLabel` beside it survives an edit that never touched it.
 *
 * Returns null when [source] is not a vCard this app can read. The upload is refused rather than
 * built from the rows alone, because the source is where everything the rows cannot hold lives.
 */
internal fun patchContact(
    source: String?,
    rows: List<DataRow>,
    photo: PhotoEdit,
    categories: CategoryEdit,
    /** The UID to give a contact that has no source of its own; ignored when [source] is not null. */
    newUid: String?,
    now: Instant,
): ContactBytes? {
    val vcard: VCard
    if (source == null) {
        // A contact made on the phone has nothing to patch, so the minimum a server reads is built
        // from the rows. 3.0, because RFC 6352 makes it the version every CardDAV server must accept.
        vcard = VCard(VCardVersion.V3_0)
        newUid?.let { vcard.setUid(Uid(it)) }
    } else {
        vcard = readVCard(source) ?: return null
    }

    val current = rows.groupBy { it.mimeType }
    val derived = mapVCard(vcard).rows.groupBy { it.mimeType }

    // What the source says, written by the writer that writes the upload: the rebuilds below mutate
    // the card in place, so the comparison has to be taken before they run, and taking it through
    // the same writer is what keeps a difference in folding or property order from reading as an
    // edit. A card built from the rows alone has nothing to compare against and is never unchanged.
    val before = if (source == null) null else write(vcard)

    rebuildName(vcard, current, derived, rows, created = source == null)
    rebuildNicknames(vcard, current, derived)
    rebuildClass(vcard, EzTelephone::class.java, "TEL", current, derived, PHONE_COLUMNS, ::rewritePhone, ::buildPhone)
    rebuildClass(vcard, EzEmail::class.java, "EMAIL", current, derived, EMAIL_COLUMNS, ::rewriteEmail, ::buildEmail)
    rebuildClass(vcard, Address::class.java, "ADR", current, derived, ADDRESS_COLUMNS, ::rewriteAddress, ::buildAddress)
    rebuildOrganization(vcard, current, derived)
    rebuildClass(vcard, EzNote::class.java, "NOTE", current, derived, NOTE_COLUMNS, ::rewriteNote, ::buildNote)
    rebuildClass(vcard, EzUrl::class.java, "URL", current, derived, URL_COLUMNS, ::rewriteUrl, ::buildUrl)
    rebuildClass(vcard, Birthday::class.java, "BDAY", current, derived, BIRTHDAY_COLUMNS, ::rewriteBirthday, ::buildBirthday)
    rebuildCategories(vcard, categories)
    rebuildPhoto(vcard, photo)

    val unchanged = before != null && before == write(vcard)

    // This app's own product id, and the time of the write: the source's are the server's bookkeeping,
    // not something an edit preserves. The default one on the writer chain is off, so exactly one of
    // ours reaches the server.
    vcard.removeProperties(ezvcard.property.ProductId::class.java)
    vcard.setProductId(PRODID)
    vcard.setRevision(now)

    return ContactBytes(
        text = write(vcard),
        uid = vcard.uid?.value ?: newUid,
        unchanged = unchanged,
    )
}

/** One spelling of the writer, so that the comparison above and the upload cannot disagree. */
private fun write(vcard: VCard): String =
    Ezvcard.write(vcard).version(versionToWrite(vcard)).prodId(false).go()

/**
 * The version to write.
 *
 * The source's own, because ez-vcard drops a property the target version does not support: writing a
 * 4.0 source as 3.0 would lose `KIND`, `MEMBER` and every 4.0 parameter. A card that declares no
 * version parses as 2.1 — the format's oldest default rather than a choice the source made, and this
 * app maps nothing that is 2.1-only — so an undeclared version is written as 3.0.
 */
private fun versionToWrite(vcard: VCard): VCardVersion =
    vcard.version?.takeIf { it != VCardVersion.V2_1 } ?: VCardVersion.V3_0

/**
 * Rebuilds one owned class: the properties a current row still claims, the ones no row claims any
 * more, and the ones only the rows know about.
 *
 * A property is *kept as the server wrote it* — every parameter ez-vcard models and every one it
 * does not — while a row still says exactly what the source derived. It is rewritten when that row
 * changed, dropped when the handle it landed under has no row left, and added when a row carries no
 * handle the source knows (a row the editor inserted). A property the source wrote that its own
 * mapping could not turn into a row is left alone: nobody saw it, so nobody deleted it.
 */
private fun <P : VCardProperty> rebuildClass(
    vcard: VCard,
    type: Class<P>,
    name: String,
    current: Map<String, List<DataRow>>,
    derived: Map<String, List<DataRow>>,
    columns: List<String>,
    rewrite: (P, DataRow, DataRow) -> Unit,
    build: (DataRow) -> P?,
) {
    val sourceRows = derived[rowsOf(type)].orEmpty()
    if (sourceRows.isEmpty() && current[rowsOf(type)].isNullOrEmpty()) return

    val derivedByHandle = HashMap<String, DataRow>()
    for (row in sourceRows) row.handle?.let { derivedByHandle[it] = row }
    val claimed = HashMap<String, DataRow>()
    val added = ArrayList<DataRow>()
    for (row in current[rowsOf(type)].orEmpty()) {
        val handle = row.handle
        if (handle != null && derivedByHandle.containsKey(handle)) claimed[handle] = row else added += row
    }

    // A snapshot: the list `getProperties` hands back is a view of the card, so removing from it
    // while walking it would be walking a list that is changing underneath.
    for ((index, property) in vcard.getProperties(type).toList().withIndex()) {
        val sourceRow = derivedByHandle["$name:$index"] ?: continue
        val row = claimed.remove("$name:$index")
        when {
            row == null -> vcard.removeProperty(property)
            !sameColumns(row, sourceRow, columns) -> rewrite(property, row, sourceRow)
        }
    }
    for (row in added) build(row)?.let { vcard.addProperty(it) }
}

/**
 * `NICKNAME` is the one owned class the rows can own single values of: the read path splits a
 * list-valued property into one row per value, so a handle names `<property>/<value>` and a value can
 * be kept, rewritten or dropped inside the property it was parsed from.
 */
private fun rebuildNicknames(vcard: VCard, current: Map<String, List<DataRow>>, derived: Map<String, List<DataRow>>) {
    val rows = current[Nickname.CONTENT_ITEM_TYPE].orEmpty()
    val sourceRows = derived[Nickname.CONTENT_ITEM_TYPE].orEmpty()
    if (rows.isEmpty() && sourceRows.isEmpty()) return

    val derivedByHandle = HashMap<String, DataRow>()
    for (row in sourceRows) row.handle?.let { derivedByHandle[it] = row }
    val claimed = HashMap<String, DataRow>()
    val added = ArrayList<DataRow>()
    for (row in rows) {
        val handle = row.handle
        if (handle != null && derivedByHandle.containsKey(handle)) claimed[handle] = row else added += row
    }

    for ((index, nickname) in vcard.getProperties(EzNickname::class.java).toList().withIndex()) {
        val values = nickname.values
        // Backwards, so removing a value cannot move the ones still to be looked at.
        for (valueIndex in values.indices.reversed()) {
            val handle = "NICKNAME:$index/$valueIndex"
            val sourceRow = derivedByHandle[handle] ?: continue
            val row = claimed.remove(handle)
            if (row == null) {
                values.removeAt(valueIndex)
                continue
            }
            val value = row.values[Nickname.NAME]?.takeIf { it.isNotBlank() }
            if (value != null && value != sourceRow.values[Nickname.NAME]) values[valueIndex] = value
        }
        if (values.isEmpty()) vcard.removeProperty(nickname)
    }
    for (row in added) {
        val value = row.values[Nickname.NAME]?.takeIf { it.isNotBlank() } ?: continue
        vcard.addProperty(EzNickname().apply { values.add(value) })
    }
}

/**
 * The name class: `N`, `FN` and the `X-PHONETIC-*` properties, all of them from the one name row.
 *
 * An untouched name keeps the source's `N` and `FN` exactly, which is what a deliberate server-side
 * `FN` is worth preserving for. They are regenerated together otherwise, because the row is one thing:
 * a component the user changed makes both stale.
 */
private fun rebuildName(
    vcard: VCard,
    current: Map<String, List<DataRow>>,
    derived: Map<String, List<DataRow>>,
    rows: List<DataRow>,
    created: Boolean,
) {
    val row = current[StructuredName.CONTENT_ITEM_TYPE]?.firstOrNull()
    val sourceRow = derived[StructuredName.CONTENT_ITEM_TYPE]?.firstOrNull()
    if (row != null && sourceRow != null && sameName(row, sourceRow, vcard.formattedName?.value)) return
    // An update of a card that never named the contact, whose rows hold no name either, has nothing
    // to remove and nothing to write.
    if (!created && row == null && sourceRow == null && vcard.formattedName == null && !hasPhonetics(vcard)) return

    vcard.removeProperties(EzStructuredName::class.java)
    vcard.removeProperties(FormattedName::class.java)
    removePhonetics(vcard)
    vcard.addProperty(structuredName(row))
    // `FN` is mandatory in both versions, so a contact the user named nothing after is named after
    // the first thing on it a person would recognise.
    vcard.addProperty(FormattedName(displayName(row) ?: firstReachable(rows) ?: ""))
    addPhonetics(vcard, row)
}

/**
 * Whether the name row still says what the source did.
 *
 * `DISPLAY_NAME` is not compared like the other columns: the provider fills it in from the components
 * whenever the read path left it out, so an untouched name row always carries one, and comparing the
 * columns alone would report a change on every contact whose `FN` the server spelled its own way. The
 * name is unchanged while every component matches and the display name is the one the provider would
 * have derived — which is the source's `FN` in exactly that case.
 */
private fun sameName(row: DataRow, source: DataRow, sourceFn: String?): Boolean {
    if (NAME_COLUMNS.any { row.values[it] != source.values[it] }) return false
    val display = row.values[StructuredName.DISPLAY_NAME]?.takeIf { it.isNotBlank() } ?: return true
    val derived = source.values[StructuredName.DISPLAY_NAME]?.takeIf { it.isNotBlank() } ?: sourceFn ?: return false
    return nameKey(display) == nameKey(derived)
}

/**
 * `ORG` and `TITLE` share one row, so they are regenerated together: a row the user emptied takes
 * both with it, which is the correct outcome for an organisation the contact no longer has.
 */
private fun rebuildOrganization(vcard: VCard, current: Map<String, List<DataRow>>, derived: Map<String, List<DataRow>>) {
    val row = current[Organization.CONTENT_ITEM_TYPE]?.firstOrNull()
    val sourceRow = derived[Organization.CONTENT_ITEM_TYPE]?.firstOrNull()
    if (row != null && sourceRow != null && sameColumns(row, sourceRow, ORGANIZATION_COLUMNS)) return
    // A source that wrote no organisation either — an `ORG` of nothing at all — is nothing to remove.
    if (row == null && sourceRow == null) return
    vcard.removeProperties(EzOrganization::class.java)
    vcard.removeProperties(EzTitle::class.java)
    if (row == null) return
    val company = row.values[Organization.COMPANY]?.takeIf { it.isNotBlank() }
    if (company != null) {
        vcard.addProperty(EzOrganization().apply {
            values.add(company)
            row.values[Organization.DEPARTMENT]?.takeIf { it.isNotBlank() }?.let { values.add(it) }
        })
    }
    row.values[Organization.TITLE]?.takeIf { it.isNotBlank() }?.let { vcard.addProperty(EzTitle(it)) }
}

/**
 * `CATEGORIES`, from the contact's own membership rows.
 *
 * The comparison is the read path's, case-insensitively: equal means the property is left exactly as
 * the server wrote it, including on a source that spelled the same memberships its own way. Otherwise
 * the property is one of the memberships the rows name, plus every source value naming no group this
 * Collection has — a value the read path could not resolve, which the user therefore never saw.
 */
private fun rebuildCategories(vcard: VCard, edit: CategoryEdit) {
    val source = vcard.getProperties(Categories::class.java).flatMap { it.values }.filterNotNull()
    val titles = edit.titles.filter { it.isNotBlank() }.distinct()
    val unchanged = titles.size == source.size && titles.all { title -> source.any { it.equals(title, ignoreCase = true) } }
    if (unchanged) return

    val preserved = source.filter { value -> edit.groupTitles.none { it.equals(value, ignoreCase = true) } }
    vcard.removeProperties(Categories::class.java)
    val values = (titles + preserved).distinct()
    if (values.isEmpty()) return
    vcard.addProperty(Categories().apply { this.values.addAll(values) })
}

/**
 * `PHOTO`: dropped with its row, rebuilt from the display photo when the row changed, and left alone
 * — link, base64, parameters and all — while the row still says what the source's bytes produced.
 */
private fun rebuildPhoto(vcard: VCard, edit: PhotoEdit) {
    when (edit) {
        is PhotoEdit.Rebuilt ->
            if (edit.bytes.isEmpty()) {
                vcard.removeProperties(EzPhoto::class.java)
            } else {
                vcard.removeProperties(EzPhoto::class.java)
                // The display photo is JPEG; ez-vcard spells it `ENCODING=b;TYPE=JPEG` in 3.0 and a
                // `data:` URI in 4.0.
                vcard.addProperty(EzPhoto(edit.bytes, ImageType.JPEG))
            }
        PhotoEdit.Dropped -> vcard.removeProperties(EzPhoto::class.java)
        PhotoEdit.Kept -> Unit
    }
}

// ------------------------------------------------------------------ rows

/**
 * The MIME type whose rows own [type].
 *
 * One class, one kind: the mapping writes the property class to the kind that holds it, and the
 * patches above ask for the same kind back.
 */
private fun rowsOf(type: Class<*>): String = when (type) {
    EzTelephone::class.java -> Phone.CONTENT_ITEM_TYPE
    EzEmail::class.java -> Email.CONTENT_ITEM_TYPE
    Address::class.java -> StructuredPostal.CONTENT_ITEM_TYPE
    EzNote::class.java -> Note.CONTENT_ITEM_TYPE
    EzUrl::class.java -> Website.CONTENT_ITEM_TYPE
    Birthday::class.java -> Event.CONTENT_ITEM_TYPE
    else -> error("no data kind maps to ${type.simpleName}")
}

/** Whether [row] still says exactly what the source derived said, column by column. */
private fun sameColumns(row: DataRow, source: DataRow, columns: List<String>): Boolean =
    columns.all { column -> row.values[column] == source.values[column] }

private fun DataRow.text(column: String): String? = values[column]?.takeIf { it.isNotBlank() }

/**
 * Replaces a property's `TYPE` values, which is the only part of it a row owns: `PREF`, `PID`,
 * `ALTID`, `VALUE=uri` and an `item1.` group are the server's spelling and stay as it wrote them.
 */
private fun VCardProperty.replaceTypes(types: List<String>) {
    parameters.removeAll("TYPE")
    for (type in types) parameters.addType(type)
}

/** The vCard's word for the row's type: the reverse of the read path's `phoneType`. */
private fun phoneTypes(row: DataRow): List<String> = when (row.values[Phone.TYPE]?.toIntOrNull()) {
    Phone.TYPE_MOBILE -> listOf("CELL")
    Phone.TYPE_HOME -> listOf("HOME")
    Phone.TYPE_WORK -> listOf("WORK")
    Phone.TYPE_FAX_HOME -> listOf("FAX")
    Phone.TYPE_FAX_WORK -> listOf("WORK", "FAX")
    Phone.TYPE_PAGER -> listOf("PAGER")
    Phone.TYPE_CAR -> listOf("CAR")
    Phone.TYPE_ISDN -> listOf("ISDN")
    Phone.TYPE_MAIN -> listOf("MAIN")
    Phone.TYPE_TTY_TDD -> listOf("TEXTPHONE")
    // `OTHER` holds what the read path could not name, which it put in `LABEL`; `CUSTOM` holds the
    // word the user typed. Both are spelled as a type value.
    Phone.TYPE_OTHER, Phone.TYPE_CUSTOM -> listOfNotNull(row.text(Phone.LABEL))
    else -> emptyList()
}

private fun buildPhone(row: DataRow): EzTelephone? =
    row.text(Phone.NUMBER)?.let { number -> EzTelephone(number).apply { replaceTypes(phoneTypes(row)) } }

private fun rewritePhone(property: EzTelephone, row: DataRow, source: DataRow) {
    row.text(Phone.NUMBER)?.let { property.text = it }
    // A row whose value changed but whose type did not keeps the type list as the server spelled it,
    // which is the case the phone formatter produces on an untouched row.
    if (row.values[Phone.TYPE] != source.values[Phone.TYPE] || row.values[Phone.LABEL] != source.values[Phone.LABEL]) {
        property.replaceTypes(phoneTypes(row))
    }
}

/** The vCard's word for the row's type: the reverse of the read path's `emailType`. */
private fun emailTypes(row: DataRow): List<String> = when (row.values[Email.TYPE]?.toIntOrNull()) {
    Email.TYPE_HOME -> listOf("HOME")
    Email.TYPE_WORK -> listOf("WORK")
    Email.TYPE_MOBILE -> listOf("CELL")
    Email.TYPE_OTHER, Email.TYPE_CUSTOM -> listOfNotNull(row.text(Email.LABEL))
    else -> emptyList()
}

private fun buildEmail(row: DataRow): EzEmail? =
    row.text(Email.ADDRESS)?.let { address -> EzEmail(address).apply { replaceTypes(emailTypes(row)) } }

private fun rewriteEmail(property: EzEmail, row: DataRow, source: DataRow) {
    row.text(Email.ADDRESS)?.let { property.value = it }
    if (row.values[Email.TYPE] != source.values[Email.TYPE] || row.values[Email.LABEL] != source.values[Email.LABEL]) {
        property.replaceTypes(emailTypes(row))
    }
}

/** The vCard's word for the row's type: the reverse of the read path's `addressType`. */
private fun addressTypes(row: DataRow): List<String> = when (row.values[StructuredPostal.TYPE]?.toIntOrNull()) {
    StructuredPostal.TYPE_HOME -> listOf("HOME")
    StructuredPostal.TYPE_WORK -> listOf("WORK")
    StructuredPostal.TYPE_OTHER, StructuredPostal.TYPE_CUSTOM -> listOfNotNull(row.text(StructuredPostal.LABEL))
    else -> emptyList()
}

private fun buildAddress(row: DataRow): Address? {
    val address = Address()
    setAddressComponents(address, row)
    if (ADDRESS_COMPONENTS.none { address.component(it) != null }) return null
    address.replaceTypes(addressTypes(row))
    return address
}

private fun rewriteAddress(property: Address, row: DataRow, source: DataRow) {
    if (ADDRESS_COMPONENTS.any { row.values[it] != source.values[it] }) {
        // The source's `LABEL` described an address that is no longer this one.
        property.label = null
        setAddressComponents(property, row)
    }
    if (row.values[StructuredPostal.TYPE] != source.values[StructuredPostal.TYPE] ||
        row.values[StructuredPostal.LABEL] != source.values[StructuredPostal.LABEL]
    ) {
        property.replaceTypes(addressTypes(row))
    }
}

/**
 * The seven `ADR` components, extended left empty.
 *
 * The read path folds a present extended address into `STREET`, so a changed address cannot put it
 * back; a changed address writes the components the columns hold and nothing else.
 */
private fun setAddressComponents(address: Address, row: DataRow) {
    address.poBox = row.text(StructuredPostal.POBOX)
    address.streetAddress = row.text(StructuredPostal.STREET)
    address.locality = row.text(StructuredPostal.CITY)
    address.region = row.text(StructuredPostal.REGION)
    address.postalCode = row.text(StructuredPostal.POSTCODE)
    address.country = row.text(StructuredPostal.COUNTRY)
}

private fun Address.component(column: String): String? = when (column) {
    StructuredPostal.POBOX -> poBox
    StructuredPostal.STREET -> streetAddress
    StructuredPostal.CITY -> locality
    StructuredPostal.REGION -> region
    StructuredPostal.POSTCODE -> postalCode
    else -> country
}

private fun buildNote(row: DataRow): EzNote? = row.text(Note.NOTE)?.let { EzNote(it) }

private fun rewriteNote(property: EzNote, row: DataRow, source: DataRow) {
    row.text(Note.NOTE)?.let { property.value = it }
}

/** The vCard's word for the row's type: the reverse of the read path's `websiteType`. */
private fun websiteType(row: DataRow): String? = when (row.values[Website.TYPE]?.toIntOrNull()) {
    Website.TYPE_HOMEPAGE -> "HOME"
    Website.TYPE_WORK -> "WORK"
    Website.TYPE_BLOG -> "BLOG"
    Website.TYPE_FTP -> "FTP"
    Website.TYPE_PROFILE -> "PROFILE"
    Website.TYPE_OTHER, Website.TYPE_CUSTOM -> row.text(Website.LABEL)
    else -> null
}

private fun buildUrl(row: DataRow): EzUrl? =
    row.text(Website.URL)?.let { value -> EzUrl(value).apply { type = websiteType(row) } }

private fun rewriteUrl(property: EzUrl, row: DataRow, source: DataRow) {
    row.text(Website.URL)?.let { property.value = it }
    if (row.values[Website.TYPE] != source.values[Website.TYPE] || row.values[Website.LABEL] != source.values[Website.LABEL]) {
        property.type = websiteType(row)
    }
}

/**
 * `BDAY` from the row's `START_DATE`, which the read path wrote as `yyyy-MM-dd` or, for a birthday
 * with no year, as `--MM-dd`.
 *
 * A year-less birthday is written as a partial date in both versions: ez-vcard spells it `--04-12`
 * in 3.0 and `--0412` in 4.0, and the read path parses either back into the same partial date, so
 * Apple's `X-APPLE-OMIT-YEAR` placeholder — a birthday the server would read as 1604 — is not needed
 * to make the round trip work.
 */
private fun buildBirthday(row: DataRow): Birthday? {
    val text = row.text(Event.START_DATE) ?: return null
    if (text.startsWith("--")) {
        val month = text.removePrefix("--").substringBefore('-').toIntOrNull() ?: return null
        val day = text.substringAfterLast('-').toIntOrNull() ?: return null
        return Birthday(PartialDate.builder().month(month).date(day).build())
    }
    return runCatching { LocalDate.parse(text) }.getOrNull()?.let { Birthday(it) }
}

private fun rewriteBirthday(property: Birthday, row: DataRow, source: DataRow) {
    val rebuilt = buildBirthday(row) ?: return
    // The three value forms are one slot in the model, and setting one clears the others, so only the
    // side the row actually holds is set.
    val partial = rebuilt.partialDate
    if (partial != null) {
        property.date = null
        property.partialDate = partial
    } else {
        property.partialDate = null
        property.date = rebuilt.date
    }
}

// ------------------------------------------------------------------ the name

/** The name columns, in the order a reader joins them. */
private val NAME_COMPONENTS = listOf(
    StructuredName.PREFIX,
    StructuredName.GIVEN_NAME,
    StructuredName.MIDDLE_NAME,
    StructuredName.FAMILY_NAME,
    StructuredName.SUFFIX,
)

/** Android's phonetic-name columns and the `X-PHONETIC-*` properties they travel as. */
private val PHONETIC_COLUMNS = listOf(
    StructuredName.PHONETIC_GIVEN_NAME to "X-PHONETIC-FIRST-NAME",
    StructuredName.PHONETIC_MIDDLE_NAME to "X-PHONETIC-MIDDLE-NAME",
    StructuredName.PHONETIC_FAMILY_NAME to "X-PHONETIC-LAST-NAME",
)

/** Everything the one name row owns. `DISPLAY_NAME` is compared separately: see [sameName]. */
private val NAME_COLUMNS = NAME_COMPONENTS + PHONETIC_COLUMNS.map { it.first }

private fun structuredName(row: DataRow?): EzStructuredName = EzStructuredName().apply {
    row?.text(StructuredName.PREFIX)?.let { prefixes.add(it) }
    row?.text(StructuredName.GIVEN_NAME)?.let { given = it }
    row?.text(StructuredName.MIDDLE_NAME)?.let { additionalNames.add(it) }
    row?.text(StructuredName.FAMILY_NAME)?.let { family = it }
    row?.text(StructuredName.SUFFIX)?.let { suffixes.add(it) }
}

/** The display name the provider would derive from the row, in the order it joins the components. */
private fun displayName(row: DataRow?): String? {
    val explicit = row?.text(StructuredName.DISPLAY_NAME)
    if (explicit != null) return explicit
    if (row == null) return null
    return NAME_COMPONENTS.mapNotNull { row.text(it) }.joinToString(" ").takeIf { it.isNotEmpty() }
}

/**
 * What to call a contact the rows give no name at all — nothing typed into the name fields and no
 * components either. `FN` is mandatory, and the Contacts app shows such a contact as its first
 * number or address, so the upload says the same.
 */
private fun firstReachable(rows: List<DataRow>): String? {
    for ((kind, column) in listOf(Phone.CONTENT_ITEM_TYPE to Phone.NUMBER, Email.CONTENT_ITEM_TYPE to Email.ADDRESS)) {
        rows.firstOrNull { it.mimeType == kind }?.text(column)?.let { return it }
    }
    return null
}

/** Removes the phonetic properties by name: every other `X-` property is the server's to keep. */
private fun removePhonetics(vcard: VCard) {
    for (property in vcard.getProperties(RawProperty::class.java).toList()) {
        if (isPhonetic(property)) vcard.removeProperty(property)
    }
}

/** Whether the card already spells any of the phonetic names. */
private fun hasPhonetics(vcard: VCard): Boolean =
    vcard.getProperties(RawProperty::class.java).any { isPhonetic(it) }

private fun isPhonetic(property: RawProperty): Boolean =
    PHONETIC_COLUMNS.any { it.second.equals(property.propertyName, ignoreCase = true) }

private fun addPhonetics(vcard: VCard, row: DataRow?) {
    for ((column, name) in PHONETIC_COLUMNS) {
        row?.text(column)?.let { vcard.addProperty(RawProperty(name, it)) }
    }
}

// ------------------------------------------------------------------ columns

private val PHONE_COLUMNS = listOf(Phone.NUMBER, Phone.TYPE, Phone.LABEL)
private val EMAIL_COLUMNS = listOf(Email.ADDRESS, Email.TYPE, Email.LABEL)
private val NOTE_COLUMNS = listOf(Note.NOTE)
private val URL_COLUMNS = listOf(Website.URL, Website.TYPE, Website.LABEL)
private val BIRTHDAY_COLUMNS = listOf(Event.START_DATE, Event.TYPE)
private val ORGANIZATION_COLUMNS = listOf(Organization.COMPANY, Organization.DEPARTMENT, Organization.TITLE)

/**
 * The `ADR` columns the rows own. `FORMATTED_ADDRESS` is deliberately absent: the provider derives it
 * from the components, so reading it back as the property's `LABEL` would add a label to every
 * address that never had one.
 */
private val ADDRESS_COLUMNS = listOf(
    StructuredPostal.POBOX,
    StructuredPostal.STREET,
    StructuredPostal.CITY,
    StructuredPostal.REGION,
    StructuredPostal.POSTCODE,
    StructuredPostal.COUNTRY,
    StructuredPostal.TYPE,
    StructuredPostal.LABEL,
)

private val ADDRESS_COMPONENTS = listOf(
    StructuredPostal.POBOX,
    StructuredPostal.STREET,
    StructuredPostal.CITY,
    StructuredPostal.REGION,
    StructuredPostal.POSTCODE,
    StructuredPostal.COUNTRY,
)
