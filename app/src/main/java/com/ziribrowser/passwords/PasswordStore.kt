package com.ziribrowser.passwords

import com.ziribrowser.data.CredentialDao
import com.ziribrowser.data.CredentialEntity
import com.ziribrowser.data.NeverSaveEntity

/** Saved logins: reads and writes the database and seals or opens the passwords. Nothing here touches the UI. */
class PasswordStore(private val dao: CredentialDao, private val box: SecretBox) {
    val all = dao.all()
    val count = dao.count()
    val neverSaved = dao.neverSaved()

    /** The decrypted password, or null if it can't be opened (for example after the phone's keys were reset). */
    fun reveal(c: CredentialEntity): String? = box.open(c.secret)

    suspend fun forOrigin(origin: String): List<CredentialEntity> = dao.forOrigin(origin)

    suspend fun savedLogins(origin: String): List<SavedLogin> =
        dao.forOrigin(origin).mapNotNull { c -> box.open(c.secret)?.let { SavedLogin(c.id, c.username, it) } }

    suspend fun isNeverSaved(origin: String) = dao.isNeverSaved(origin)
    suspend fun neverSave(origin: String) = dao.neverSave(NeverSaveEntity(origin))
    suspend fun allowSaving(origin: String) = dao.allowSaving(origin)

    suspend fun add(origin: String, username: String, password: String) {
        dao.insert(CredentialEntity(origin = origin, username = username, secret = box.seal(password)))
    }

    suspend fun update(id: Long, username: String, password: String) = dao.update(id, username, box.seal(password))
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun deleteAll() = dao.deleteAll()
    suspend fun markUsed(id: Long) = dao.touch(id)

    /** Adds the logins from an exported file; existing ones with the same site and username are kept as they are. */
    suspend fun import(rows: List<CsvLogin>): Int {
        var added = 0
        for (r in rows) {
            val origin = PasswordPolicy.originOf(r.url.let { if ("://" in it) it else "https://$it" }) ?: continue
            if (!PasswordPolicy.eligible(origin) || r.password.length > PasswordPolicy.MAX_FIELD) continue
            if (dao.forOrigin(origin).any { it.username == r.username }) continue
            add(origin, r.username, r.password); added++
        }
        return added
    }

    /** Every login with its password, for an export the person asked for. */
    suspend fun export(): List<CsvLogin> =
        dao.allNow().mapNotNull { c -> box.open(c.secret)?.let { CsvLogin(c.origin, c.username, it) } }
}
