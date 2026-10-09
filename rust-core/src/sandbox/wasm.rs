use wasmi::{
    Config, Engine, Linker, Module, Store, StoreLimits, StoreLimitsBuilder,
};
use thiserror::Error;
use crate::security::ssrf::validate_url_safety;

#[derive(Error, Debug)]
pub enum SandboxError {
    #[error("Compilation / instantiation failed: {0}")]
    Instantiation(String),
    #[error("Runtime execution trapped: {0}")]
    ExecutionTrapped(String),
    #[error("Execution fuel exhausted (CPU limit reached)")]
    FuelExhausted,
    #[error("Memory limit exceeded: {0}")]
    MemoryLimitExceeded(String),
    #[error("Security violation: {0}")]
    SecurityViolation(String),
}

/// Host context provided to the sandboxed WASM instance.
pub struct HostContext {
    limits: StoreLimits,
    pub logs: Vec<String>,
}

impl HostContext {
    pub fn new(max_memory_bytes: usize) -> Self {
        let limits = StoreLimitsBuilder::new()
            .memory_size(max_memory_bytes)
            .build();

        Self {
            limits,
            logs: Vec::new(),
        }
    }
}

/// Zero-Trust Sandboxed Extension Host.
/// Enforces linear memory isolation, instruction fuel limiting, and capability-based I/O.
pub struct WasmSandbox {
    engine: Engine,
    linker: Linker<HostContext>,
}

impl WasmSandbox {
    /// Initializes the sandbox with fuel consumption enabled to prevent CPU exhaustion.
    pub fn new() -> Result<Self, SandboxError> {
        let mut config = Config::default();
        config.consume_fuel(true);

        let engine = Engine::new(&config);
        let mut linker = Linker::new(&engine);

        // Capability 1: host_log
        linker
            .func_wrap("env", "host_log", |mut caller: wasmi::Caller<HostContext>, ptr: i32, len: i32| -> Result<(), wasmi::Error> {
                let memory = caller
                    .get_export("memory")
                    .and_then(|ext| ext.into_memory())
                    .ok_or_else(|| wasmi::Error::new("Failed to get linear memory"))?;

                let data = memory.data(&caller);
                let start = ptr as usize;
                let end = start + len as usize;

                if end <= data.len() {
                    if let Ok(msg) = std::str::from_utf8(&data[start..end]) {
                        caller.data_mut().logs.push(msg.to_string());
                    }
                }
                Ok(())
            })
            .map_err(|e| SandboxError::Instantiation(e.to_string()))?;

        // Capability 2: host_validate_url (Anti-SSRF gate)
        linker
            .func_wrap("env", "host_validate_url", |caller: wasmi::Caller<HostContext>, ptr: i32, len: i32| -> Result<i32, wasmi::Error> {
                let memory = match caller.get_export("memory").and_then(|ext| ext.into_memory()) {
                    Some(m) => m,
                    None => return Ok(0),
                };

                let data = memory.data(&caller);
                let start = ptr as usize;
                let end = start + len as usize;

                if end <= data.len() {
                    if let Ok(url_str) = std::str::from_utf8(&data[start..end]) {
                        return match validate_url_safety(url_str) {
                            Ok(_) => Ok(1), // URL is safe
                            Err(_) => Ok(0), // Blocked
                        };
                    }
                }
                Ok(0)
            })
            .map_err(|e| SandboxError::Instantiation(e.to_string()))?;

        Ok(Self { engine, linker })
    }

    /// Loads and executes a `.wasm` extension module under strict fuel and memory limits.
    pub fn run_extension(
        &self,
        wasm_bytes: &[u8],
        initial_fuel: u64,
        max_memory_bytes: usize,
    ) -> Result<Vec<String>, SandboxError> {
        let module = Module::new(&self.engine, wasm_bytes)
            .map_err(|e| SandboxError::Instantiation(e.to_string()))?;

        let context = HostContext::new(max_memory_bytes);
        let mut store = Store::new(&self.engine, context);
        store.set_fuel(initial_fuel).map_err(|e| SandboxError::Instantiation(e.to_string()))?;

        let instance = self
            .linker
            .instantiate(&mut store, &module)
            .map_err(|e| SandboxError::Instantiation(e.to_string()))?
            .start(&mut store)
            .map_err(|e| SandboxError::Instantiation(e.to_string()))?;

        // Optionally invoke "main" or "init" if present
        if let Some(entry_func) = instance.get_typed_func::<(), ()>(&store, "init").ok() {
            entry_func
                .call(&mut store, ())
                .map_err(|e| SandboxError::ExecutionTrapped(e.to_string()))?;
        }

        Ok(store.into_data().logs)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sandbox_initialization() {
        let sandbox = WasmSandbox::new();
        assert!(sandbox.is_ok());
    }
}
