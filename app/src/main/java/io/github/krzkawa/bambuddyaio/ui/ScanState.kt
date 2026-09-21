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

    fun reading() { _busy.value = true }

    fun found(tag: SpoolTag) {
        _tag.value = tag
        _busy.value = false
    }

    fun clear() {
        _tag.value = null
        _busy.value = false
    }
}
