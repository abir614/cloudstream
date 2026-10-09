use std::collections::{HashMap, VecDeque};
use std::sync::{Mutex, OnceLock};

/// Thread-safe, memory-efficient LRU Cache implemented in native Rust.
/// Holds cache entries directly on the native heap, completely eliminating JVM GC pauses
/// and object header fragmentation on low-end Android devices.
pub struct LruCache {
    capacity: usize,
    map: HashMap<String, String>,
    order: VecDeque<String>,
}

impl LruCache {
    pub fn new(capacity: usize) -> Self {
        Self {
            capacity: capacity.max(1),
            map: HashMap::with_capacity(capacity.min(256)),
            order: VecDeque::with_capacity(capacity.min(256)),
        }
    }

    pub fn get(&mut self, key: &str) -> Option<String> {
        if let Some(val) = self.map.get(key) {
            // Move key to back (most recently used)
            if let Some(pos) = self.order.iter().position(|k| k == key) {
                self.order.remove(pos);
                self.order.push_back(key.to_string());
            }
            Some(val.clone())
        } else {
            None
        }
    }

    pub fn put(&mut self, key: String, value: String) {
        if self.map.contains_key(&key) {
            // Update existing
            self.map.insert(key.clone(), value);
            if let Some(pos) = self.order.iter().position(|k| k == &key) {
                self.order.remove(pos);
            }
            self.order.push_back(key);
        } else {
            // Check capacity
            if self.map.len() >= self.capacity {
                if let Some(oldest) = self.order.pop_front() {
                    self.map.remove(&oldest);
                }
            }
            self.order.push_back(key.clone());
            self.map.insert(key, value);
        }
    }

    pub fn remove(&mut self, key: &str) -> bool {
        if self.map.remove(key).is_some() {
            if let Some(pos) = self.order.iter().position(|k| k == key) {
                self.order.remove(pos);
            }
            true
        } else {
            false
        }
    }

    pub fn clear(&mut self) {
        self.map.clear();
        self.order.clear();
    }

    pub fn len(&self) -> usize {
        self.map.len()
    }

    pub fn is_empty(&self) -> bool {
        self.map.is_empty()
    }
}

/// Global registry of named native caches (e.g., "session", "metadata", "url").
static CACHE_REGISTRY: OnceLock<Mutex<HashMap<String, LruCache>>> = OnceLock::new();

fn get_registry() -> &'static Mutex<HashMap<String, LruCache>> {
    CACHE_REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

pub fn cache_get(cache_name: &str, key: &str) -> Option<String> {
    let mut reg = get_registry().lock().ok()?;
    let cache = reg.entry(cache_name.to_string()).or_insert_with(|| LruCache::new(500));
    cache.get(key)
}

pub fn cache_put(cache_name: &str, key: String, value: String) {
    if let Ok(mut reg) = get_registry().lock() {
        let cache = reg.entry(cache_name.to_string()).or_insert_with(|| LruCache::new(500));
        cache.put(key, value);
    }
}

pub fn cache_remove(cache_name: &str, key: &str) -> bool {
    if let Ok(mut reg) = get_registry().lock() {
        if let Some(cache) = reg.get_mut(cache_name) {
            return cache.remove(key);
        }
    }
    false
}

pub fn cache_clear(cache_name: &str) {
    if let Ok(mut reg) = get_registry().lock() {
        if let Some(cache) = reg.get_mut(cache_name) {
            cache.clear();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_lru_cache_operations() {
        let mut cache = LruCache::new(2);
        cache.put("a".to_string(), "1".to_string());
        cache.put("b".to_string(), "2".to_string());
        assert_eq!(cache.get("a"), Some("1".to_string()));

        // Adding "c" should evict "b" since "a" was accessed recently
        cache.put("c".to_string(), "3".to_string());
        assert_eq!(cache.get("a"), Some("1".to_string()));
        assert_eq!(cache.get("b"), None);
        assert_eq!(cache.get("c"), Some("3".to_string()));
    }

    #[test]
    fn test_named_cache_registry() {
        cache_put("test_cache", "hello".to_string(), "world".to_string());
        assert_eq!(cache_get("test_cache", "hello"), Some("world".to_string()));
        assert!(cache_remove("test_cache", "hello"));
        assert_eq!(cache_get("test_cache", "hello"), None);
    }
}
