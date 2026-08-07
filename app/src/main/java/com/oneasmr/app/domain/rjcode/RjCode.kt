package com.oneasmr.app.domain.rjcode

/**
 * A parsed work code: normalized uppercase prefix (RJ/BJ/VJ) plus its 6- or 8-digit part.
 *
 * @property prefix normalized uppercase prefix, one of RJ/BJ/VJ
 * @property digits the 6- or 8-digit numeric part as a string
 */
data class RjCode(val prefix: String, val digits: String) {

    /** Canonical display form and local DB key: prefix + digits, e.g. "RJ123456". */
    val canonical: String get() = prefix + digits

    override fun toString(): String = canonical
}
