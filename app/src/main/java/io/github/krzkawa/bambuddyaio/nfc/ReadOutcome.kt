package io.github.krzkawa.bambuddyaio.nfc

import java.io.IOException

/**
 * What went wrong during one pass at a Mifare Classic tag, and therefore what to do
 * about it.
 *
 * The distinction that matters is between a tag that refused the derived keys — which
 * really does mean it is not a Bambu spool tag — and a tag that simply left the field.
 * Both used to end at the same place, so a spool the phone lost contact with was
 * reported to the user as a counterfeit.
 */
internal enum class ReadOutcome {

    /** The blocks came back and parsed. */
    DECODED,

    /** The tag would not unlock, or holds nothing. Worth trying the OpenSpool path. */
    NOT_BAMBU,

    /** Contact was lost part-way through. Worth another go with the spool held still. */
    LOST,

    /** The tag handle went stale, usually because a second tag arrived. Nothing to retry. */
    STALE,
}

/**
 * Classifies a failure from one pass at the tag.
 *
 * Returns null for anything that is not the reader's to handle, which the caller
 * rethrows rather than reporting as an unreadable spool.
 *
 * A plain [IOException] is the one that used to be read wrong. It is what
 * `MifareClassic.connect()` throws when the tag goes out of range before the sector
 * walk begins — the walk itself wraps its own failures in [TagLostException] — so it
 * means lost contact, not a rejected key.
 */
internal fun outcomeOf(error: Throwable): ReadOutcome? = when (error) {
    // Sector 0 refused the key derived from the UID, so this is not a genuine Bambu tag.
    is SectorLockedException -> ReadOutcome.NOT_BAMBU
    // An empty or nonsensical UID; there is nothing to derive keys from.
    is IllegalArgumentException -> ReadOutcome.NOT_BAMBU
    is TagLostException -> ReadOutcome.LOST
    is SecurityException -> ReadOutcome.STALE
    is IOException -> ReadOutcome.LOST
    else -> null
}
