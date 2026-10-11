package com.lagradost.cloudstream3.utils.persistence

import com.lagradost.cloudstream3.CloudStreamApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Enterprise Persistence Abstraction Layer.
 * Decouples domain logic from specific storage implementations (SharedPreferences, Room, DataStore).
 */
interface DataRepository {
    fun <T : Any> get(key: String, clazz: Class<T>): T?
    fun <T : Any> set(key: String, value: T): Boolean
    fun remove(key: String): Boolean
    fun contains(key: String): Boolean
    fun clearAll(): Boolean
    fun <T : Any> observe(key: String, clazz: Class<T>, defaultValue: T? = null): Flow<T?>
}

/**
 * Default implementation backed by CloudStream's persistence store with reactive observation.
 */
class DefaultDataRepository : DataRepository {
    private val flows = ConcurrentHashMap<String, MutableStateFlow<Any?>>()

    override fun <T : Any> get(key: String, clazz: Class<T>): T? {
        return CloudStreamApp.getKeyClass(key, clazz)
    }

    override fun <T : Any> set(key: String, value: T): Boolean {
        CloudStreamApp.setKeyClass(key, value)
        flows[key]?.value = value
        return true
    }

    override fun remove(key: String): Boolean {
        CloudStreamApp.removeKey(key)
        flows[key]?.value = null
        return true
    }

    override fun contains(key: String): Boolean {
        val ctx = CloudStreamApp.context ?: return false
        return with(com.lagradost.cloudstream3.utils.DataStore) {
            ctx.containsKey(key)
        }
    }

    override fun clearAll(): Boolean {
        // Clear registered reactive flows
        flows.forEach { (_, flow) -> flow.value = null }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observe(key: String, clazz: Class<T>, defaultValue: T?): Flow<T?> {
        val initial = get(key, clazz) ?: defaultValue
        val flow = flows.computeIfAbsent(key) { MutableStateFlow(initial) }
        return flow.asStateFlow() as Flow<T?>
    }
}
