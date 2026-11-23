package com.extbridge.helper

/** Separate discovery of an existing daemon from a newly completed activation. */
internal class ActivationConnectionTracker {
    private var open = false
    private var requestedToken = ""

    fun open() {
        open = true
        requestedToken = ""
    }

    fun requestActivation(token: String) {
        if (open) requestedToken = token
    }

    fun dismiss() {
        open = false
        requestedToken = ""
    }

    /** Observing any global service, including an old one, cannot finish an operation. */
    fun observe(@Suppress("UNUSED_PARAMETER") connected: Boolean): Boolean = false

    fun complete(token: String, activated: Boolean): Boolean {
        if (!open || !activated || requestedToken.isEmpty() || requestedToken != token) return false
        dismiss()
        return true
    }
}
