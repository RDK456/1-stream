package android.content

import android.content.res.Resources
import java.io.File

/** Minimal stand-in for android.content.Context so CloudStream plugins converted from dex can load on desktop. */
abstract class Context {
    open fun getPackageName(): String = "app.onestream"
    open fun getApplicationContext(): Context = this
    open fun getResources(): Resources = Resources()
    open fun getFilesDir(): File = File(System.getProperty("java.io.tmpdir"), "1-stream-files").also { it.mkdirs() }
    open fun getCacheDir(): File = File(System.getProperty("java.io.tmpdir"), "1-stream-cache").also { it.mkdirs() }
    // ponytail: kept in memory for the session, not written to disk; persist if a plugin needs settings to survive restarts
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs.getOrPut(name) { MemoryPrefs() }

    private companion object { val prefs = java.util.concurrent.ConcurrentHashMap<String, SharedPreferences>() }
}

class StubContext : Context()

interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun contains(key: String): Boolean
    fun getAll(): Map<String, Any?>
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

private class MemoryPrefs : SharedPreferences {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private inline fun <reified T> get(key: String, def: T): T = map[key] as? T ?: def
    override fun getString(key: String, defValue: String?) = get(key, defValue)
    override fun getInt(key: String, defValue: Int) = get(key, defValue)
    override fun getLong(key: String, defValue: Long) = get(key, defValue)
    override fun getFloat(key: String, defValue: Float) = get(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean) = get(key, defValue)
    @Suppress("UNCHECKED_CAST") override fun getStringSet(key: String, defValues: Set<String>?) = map[key] as? Set<String> ?: defValues
    override fun contains(key: String) = map.containsKey(key)
    override fun getAll(): Map<String, Any?> = map.toMap()
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val staged = HashMap<String, Any?>()
        private var cleared = false
        private fun put(key: String, v: Any?) = apply { staged[key] = v }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun putStringSet(key: String, values: Set<String>?) = put(key, values?.toSet())
        override fun remove(key: String) = put(key, null)
        override fun clear() = apply { cleared = true }
        override fun apply() { commit() }
        override fun commit(): Boolean {
            if (cleared) map.clear()
            staged.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            return true
        }
    }
}