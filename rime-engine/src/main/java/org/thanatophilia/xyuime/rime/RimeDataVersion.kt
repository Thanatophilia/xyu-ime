package org.thanatophilia.xyuime.rime

/** Resource version is a single directory-name component, never an arbitrary path. */
internal object RimeDataVersion {
    private val pattern = Regex("[A-Za-z0-9._-]+")

    fun validate(value: String) {
        check(pattern.matches(value)) { "Invalid Rime data version" }
    }
}
