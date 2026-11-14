package com.tunegrab.app.security

import java.io.File
import java.io.IOException

/** Keys from media/document providers are data, never arbitrary filesystem paths. */
object StoragePaths {
    fun isName(value: String): Boolean = value.isNotEmpty() && value != "." && value != ".." &&
        value.none { it == '/' || it == '\\' || it.isISOControl() }

    fun isKey(value: String): Boolean = value.isEmpty() || value.split('/').all(::isName)

    /** Do not follow symlinked library paths during move/delete, even aliases within the root. */
    fun resolve(root: File, key: String): File? {
        if (!isKey(key)) return null
        return try {
            val base = root.canonicalFile
            val lexical = File(base, key).absoluteFile
            val candidate = lexical.canonicalFile
            candidate.takeIf { it == lexical && (it == base || it.path.startsWith(base.path.trimEnd(File.separatorChar) + File.separator)) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
