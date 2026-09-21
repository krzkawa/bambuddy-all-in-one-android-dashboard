package io.github.krzkawa.bambuddyaio.ui

import io.github.krzkawa.bambuddyaio.nfc.SpoolTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The tag the phone is holding right now, shared between the reader and the scan screen. */
object ScanState {

    private val _tag = MutableStateFlow<SpoolTag?>(null)
    val tag: StateFlow<SpoolTag?> = _tag.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * A read that was turned away because what is already on screen is worth more.
     *
     * The scan screen shows it as a banner over the scan it kept, so a brush against
     * the spool is reported without costing the user the scan he was working with.
     */
    private val _hiccup = MutableStateFlow<SpoolTag?>(null)
    val hiccup: StateFlow<SpoolTag?> = _hiccup.asStateFlow()

    fun reading() { _busy.value = true }

    /**
     * Offers a freshly read tag.
     *
     * The spool stays near the phone while the user reaches for a slot button, so the
     * same tag is read again and again, often only halfway. Such a read is published
     * as a [hiccup] rather than replacing the scan on screen.
     */
    fun found(tag: SpoolTag) {
        _busy.value = false
        val held = _tag.value
        if (held != null && supersedes(held, tag)) {
            _hiccup.value = tag
        } else {
            _tag.value = tag
            _hiccup.value = null
        }
    }

    /** True when [held] should stay on screen and [incoming] be shown as a hiccup instead. */
    private fun supersedes(held: SpoolTag, incoming: SpoolTag): Boolean = when {
        // A read that failed never replaces one that worked, whatever tag it came from:
        // a failure carries nothing but a UID, so there is nothing to gain by taking it.
        held.failure == null && incoming.failure != null -> true
        // Two good reads of the same tag: keep whichever got more off it.
        held.failure == null && incoming.failure == null &&
            held.tagUid == incoming.tagUid && incoming.fieldCount < held.fieldCount -> true
        else -> false
    }

    fun dismissHiccup() { _hiccup.value = null }

    fun clear() {
        _tag.value = null
        _hiccup.value = null
        _busy.value = false
    }
}
