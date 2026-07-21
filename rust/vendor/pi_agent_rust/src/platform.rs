//! Shared platform-identity utilities.
//!
//! Provides OS name, architecture, and client identity strings used by
//! provider request builders. Centralises the mapping from Rust's
//! `std::env::consts` values to the provider-expected naming conventions
//! (e.g. `darwin` instead of `macos`, `arm64` instead of `aarch64`).

/// Crate version baked in at compile time.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

// ---------------------------------------------------------------------------
// OS
// ---------------------------------------------------------------------------

/// Return the OS name in the format most providers expect.
///
/// Maps Rust's `std::env::consts::OS`:
///   - `"macos"` → `"darwin"`
///   - everything else passed through (`"linux"`, `"windows"`, …)
#[inline]
pub fn os_name() -> &'static str {
    match std::env::consts::OS {
        "macos" => "darwin",
        other => other,
    }
}

// ---------------------------------------------------------------------------
// Architecture
// ---------------------------------------------------------------------------

/// Return the architecture name in the format most providers expect.
///
/// Maps Rust's `std::env::consts::ARCH`:
///   - `"aarch64"` → `"arm64"`
///   - `"x86_64"`  → `"amd64"`
///   - everything else passed through
#[inline]
pub fn arch_name() -> &'static str {
    match std::env::consts::ARCH {
        "aarch64" => "arm64",
        "x86_64" => "amd64",
        other => other,
    }
}

// ---------------------------------------------------------------------------
// Composite helpers
// ---------------------------------------------------------------------------

/// `"{os}/{arch}"` — e.g. `"linux/amd64"`, `"darwin/arm64"`.
pub fn platform_tag() -> String {
    format!("{}/{}", os_name(), arch_name())
}

/// Canonical Pi User-Agent: `"pi_agent_rust/{version}"`.
pub fn pi_user_agent() -> String {
    format!("pi_agent_rust/{VERSION}")
}

/// Canonical Pi User-Agent with an additional component:
/// `"pi_agent_rust/{version} {extra}"`.
pub fn pi_user_agent_with(extra: &str) -> String {
    format!("pi_agent_rust/{VERSION} {extra}")
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn os_name_not_empty() {
        assert!(!os_name().is_empty());
    }

    #[test]
    fn arch_name_not_empty() {
        assert!(!arch_name().is_empty());
    }

    #[test]
    fn platform_tag_has_slash() {
        let tag = platform_tag();
        assert!(tag.contains('/'), "expected OS/ARCH, got: {tag}");
    }

    #[test]
    fn pi_user_agent_contains_version() {
        let ua = pi_user_agent();
        assert!(ua.starts_with("pi_agent_rust/"), "ua: {ua}");
        assert!(ua.contains(VERSION), "ua should contain version");
    }

    #[test]
    fn pi_user_agent_with_appends() {
        let ua = pi_user_agent_with("Antigravity/1.2.3");
        assert!(ua.starts_with("pi_agent_rust/"));
        assert!(ua.ends_with("Antigravity/1.2.3"));
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_os_name() {
        assert_eq!(os_name(), "linux");
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_maps_to_darwin() {
        assert_eq!(os_name(), "darwin");
    }

    #[cfg(target_arch = "x86_64")]
    #[test]
    fn x86_64_maps_to_amd64() {
        assert_eq!(arch_name(), "amd64");
    }

    #[cfg(target_arch = "aarch64")]
    #[test]
    fn aarch64_maps_to_arm64() {
        assert_eq!(arch_name(), "arm64");
    }
}
