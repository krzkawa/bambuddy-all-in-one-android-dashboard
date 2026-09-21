package io.github.krzkawa.bambuddyaio.nfc

/**
 * Why a scan produced no filament data.
 *
 * [SpoolTag.warning] says the same thing in words for the scan card; this says it in a
 * form the UI can branch on, so an unreadable tag and an unsupported phone can be offered
 * different next steps rather than the same block of text.
 */
enum class ScanFailure {

    /** The tag is not Mifare Classic, so it cannot be a genuine Bambu spool tag. */
    NOT_MIFARE_CLASSIC,

    /**
     * This phone's NFC controller cannot do Mifare Classic at all. Phones built around a
     * Qualcomm NFC controller are in this group, and no app can work around it — the only
     * fix is a different phone or an external reader. Nothing to retry.
     */
    UNSUPPORTED_DEVICE,

    /**
     * The tag is Mifare Classic but rejected the keys derived from its UID. Either it is
     * not a Bambu tag, or it is a clone written with different keys.
     */
    AUTH_FAILED,

    /** The tag left the field part-way through the read. Worth asking the user to retry. */
    TAG_LOST,

    /** The tag unlocked, but the blocks do not hold readable Bambu filament data. */
    MALFORMED,
}
