package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.utils.persistence.DataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class DataRepositoryTest {

    /**
     * In-memory mock implementation verifying the DataRepository contract.
     */
    class InMemoryDataRepository : DataRepository {
        private val store = ConcurrentHashMap<String, Any>()
        private val flows = ConcurrentHashMap<String, MutableStateFlow<Any?>>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: String, clazz: Class<T>): T? {
            return store[key] as? T
        }

        override fun <T : Any> set(key: String, value: T): Boolean {
            store[key] = value
            flows[key]?.value = value
            return true
        }

        override fun remove(key: String): Boolean {
            val removed = store.remove(key) != null
            flows[key]?.value = null
            return removed
        }

        override fun contains(key: String): Boolean {
            return store.containsKey(key)
        }

        override fun clearAll(): Boolean {
            store.clear()
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

    @Test
    fun `test put get and contains operations`() {
        val repo = InMemoryDataRepository()
        assertFalse(repo.contains("test_key"))

        repo.set("test_key", "sample_value")
        assertTrue(repo.contains("test_key"))
        assertEquals("sample_value", repo.get("test_key", String::class.java))

        repo.remove("test_key")
        assertFalse(repo.contains("test_key"))
        assertNull(repo.get("test_key", String::class.java))
    }

    @Test
    fun `test reactive observation emits updated values`() = runBlocking {
        val repo = InMemoryDataRepository()
        val flow = repo.observe("reactive_key", String::class.java, "default")

        assertEquals("default", flow.first())

        repo.set("reactive_key", "updated_value")
        assertEquals("updated_value", flow.first())
    }
}
