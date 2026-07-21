//! Cross-implementation file locking compatible with Node `proper-lockfile`.
//!
//! Upstream TS pi (`@earendil-works/pi-coding-agent`) locks the shared files under
//! `~/.pi/agent/` (`auth.json`, `settings.json`, `sessions/session-index`) with
//! [`proper-lockfile`](https://www.npmjs.com/package/proper-lockfile) `4.1.2`.
//! That protocol represents a held lock as a **directory** created atomically with
//! `mkdir(2)` at `<target>.lock`; existence means "held", release is `rmdir(2)`,
//! and a lock whose directory mtime is older than a staleness threshold may be
//! reclaimed (`rmdir` + re-`mkdir`).
//!
//! pi_agent_rust historically used `flock(2)` (via `fs4`) on a persistent, never-
//! deleted **regular file** at the same `<target>.lock` path. That is mutually
//! incompatible with proper-lockfile in both directions:
//!
//! * proper-lockfile's `mkdir` sees the leftover regular file and returns `EEXIST`;
//!   its stale-reclaim then calls `rmdir` on that regular file and fails with
//!   `ENOTDIR`, permanently poisoning the lock path (upstream issue
//!   earendil-works/pi#1871).
//! * a rust `open(O_CREAT)` against the directory proper-lockfile creates fails
//!   with `EISDIR`.
//!
//! This module makes pi_agent_rust speak proper-lockfile's directory protocol so
//! the two implementations mutually exclude correctly, can reclaim each other's
//! stale locks, and never leave a poisoning regular file behind. When it
//! encounters a stale leftover regular file (from an older pi_agent_rust build) it
//! removes it, healing the poisoning for the TS side as well.
//!
//! Constants mirror proper-lockfile's defaults: `stale = 10_000ms` (minimum
//! 2_000ms in proper-lockfile; we use the default 10s). pi_agent_rust holds these
//! locks only for the duration of a small read or an atomic temp-file rename —
//! orders of magnitude below the stale threshold — so, unlike proper-lockfile, it
//! does not run a background mtime "updater" thread. If any future caller holds a
//! shared-file lock across slow or networked I/O, add periodic mtime refresh here
//! to keep a concurrent proper-lockfile holder from reclaiming it as stale.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime};

/// proper-lockfile default `stale` threshold. A lock directory whose mtime is
/// older than this is considered abandoned and may be reclaimed.
const STALE: Duration = Duration::from_secs(10);

/// ENOTDIR raw errno (a component of the path — here the lock path itself — is a
/// regular file). `io::ErrorKind::NotADirectory` is unstable, so match the errno.
#[cfg(unix)]
const ENOTDIR: i32 = 20;

/// Compute the proper-lockfile lock-directory path for `target`: `<target>.lock`.
/// Mirrors proper-lockfile's `getLockFile` (`${file}.lock`).
pub fn lock_path_for(target: &Path) -> PathBuf {
    let mut p = target.as_os_str().to_os_string();
    p.push(".lock");
    PathBuf::from(p)
}

/// True when `meta`'s mtime is older than the stale threshold.
///
/// Mirrors proper-lockfile's `isLockStale`: `stat.mtime < Date.now() - stale`.
/// A future mtime (clock skew) or an unreadable mtime is treated as *fresh*
/// (i.e. held) so we never steal a lock we cannot prove is abandoned.
fn is_stale(meta: &fs::Metadata) -> bool {
    match meta.modified() {
        Ok(mtime) => SystemTime::now()
            .duration_since(mtime)
            .map(|age| age > STALE)
            .unwrap_or(false),
        Err(_) => false,
    }
}

/// Remove whatever occupies the lock path so acquisition can retry.
///
/// A directory is removed with `rmdir` (matching proper-lockfile). A regular
/// file or symlink is a legacy `flock` poisoning artifact from an older
/// pi_agent_rust build (proper-lockfile never creates one); remove it too so the
/// path stops poisoning the TS side. Errors are ignored: a concurrent acquirer
/// may have already removed it, and the subsequent `mkdir` is the real arbiter.
fn reclaim(lock_path: &Path, meta: &fs::Metadata) {
    if meta.is_dir() {
        let _ = fs::remove_dir(lock_path);
    } else {
        let _ = fs::remove_file(lock_path);
    }
}

/// Exponential backoff with light jitter, capped, mirroring the previous
/// `fs4`-based retry loops in this crate.
fn backoff(attempt: u32) -> Duration {
    let base_ms: u64 = 10;
    let cap_ms: u64 = 500;
    let sleep_ms = base_ms
        .checked_shl(attempt.min(5))
        .unwrap_or(cap_ms)
        .min(cap_ms);
    let jitter = (sleep_ms / 4).max(1);
    Duration::from_millis(sleep_ms / 2 + jitter)
}

/// A held directory lock. Releases (`rmdir`) on drop.
///
/// The directory protocol is inherently mutually exclusive; there is no
/// shared/read variant (upstream TS pi likewise takes an exclusive lock for both
/// reads and writes), so a single [`DirLock`] serves both the read and write
/// paths.
#[derive(Debug)]
#[must_use = "the lock is released as soon as the DirLock is dropped"]
pub struct DirLock {
    lock_path: PathBuf,
}

impl DirLock {
    /// Acquire the directory lock at `lock_path` (an already-computed
    /// `<target>.lock` path), waiting up to `timeout`.
    ///
    /// Semantics match proper-lockfile: `mkdir` to acquire; on `EEXIST`, reclaim
    /// the lock if its mtime is stale, otherwise wait and retry until `timeout`.
    pub fn acquire(lock_path: &Path, timeout: Duration) -> io::Result<DirLock> {
        if let Some(parent) = lock_path.parent()
            && !parent.as_os_str().is_empty()
        {
            fs::create_dir_all(parent)?;
        }

        let start = Instant::now();
        let mut attempt: u32 = 0;
        loop {
            match fs::create_dir(lock_path) {
                Ok(()) => {
                    #[cfg(unix)]
                    {
                        use std::os::unix::fs::PermissionsExt as _;
                        let _ =
                            fs::set_permissions(lock_path, fs::Permissions::from_mode(0o700));
                    }
                    return Ok(DirLock {
                        lock_path: lock_path.to_path_buf(),
                    });
                }
                Err(e) if is_already_exists(&e) => {
                    // Something occupies the path. Decide held-vs-stale exactly as
                    // proper-lockfile does, via the mtime of whatever is there.
                    match fs::symlink_metadata(lock_path) {
                        Ok(meta) => {
                            if is_stale(&meta) {
                                reclaim(lock_path, &meta);
                                attempt = 0; // reclaimed: retry promptly
                            }
                            // fresh: fall through to wait/retry
                        }
                        // Vanished between mkdir and stat: retry promptly.
                        Err(e) if e.kind() == io::ErrorKind::NotFound => attempt = 0,
                        Err(e) => return Err(e),
                    }
                }
                Err(e) => return Err(e),
            }

            if start.elapsed() >= timeout {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    format!("timed out waiting for lock at {}", lock_path.display()),
                ));
            }
            std::thread::sleep(backoff(attempt));
            attempt = attempt.saturating_add(1);
        }
    }

    /// Acquire the directory lock for a `target` file, computing the
    /// `<target>.lock` path with [`lock_path_for`].
    pub fn acquire_for(target: &Path, timeout: Duration) -> io::Result<DirLock> {
        Self::acquire(&lock_path_for(target), timeout)
    }
}

/// `mkdir` reports a pre-existing entry as `AlreadyExists`; when the path
/// component is itself a regular file some platforms surface `ENOTDIR`. Treat
/// both as "already occupied" so the stale/heal path runs.
fn is_already_exists(e: &io::Error) -> bool {
    if e.kind() == io::ErrorKind::AlreadyExists {
        return true;
    }
    #[cfg(unix)]
    {
        return e.raw_os_error() == Some(ENOTDIR);
    }
    #[cfg(not(unix))]
    {
        false
    }
}

impl Drop for DirLock {
    fn drop(&mut self) {
        // Release == rmdir, matching proper-lockfile. Ignore errors: the only
        // failure modes are "already gone" (a stale-reclaim beat us) or a
        // transient FS error, neither of which we can act on here.
        let _ = fs::remove_dir(&self.lock_path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lock_path_appends_dot_lock() {
        assert_eq!(
            lock_path_for(Path::new("/x/auth.json")),
            PathBuf::from("/x/auth.json.lock")
        );
        assert_eq!(
            lock_path_for(Path::new("/x/sessions/session-index")),
            PathBuf::from("/x/sessions/session-index.lock")
        );
    }

    #[test]
    fn acquire_creates_dir_and_release_removes_it() {
        let dir = tempfile::tempdir().expect("tempdir");
        let lp = dir.path().join("auth.json.lock");
        {
            let _g = DirLock::acquire(&lp, Duration::from_secs(5)).expect("acquire");
            assert!(lp.is_dir(), "lock should be a directory while held");
        }
        assert!(!lp.exists(), "lock directory should be removed on drop");
    }

    #[test]
    fn second_acquire_times_out_while_held() {
        let dir = tempfile::tempdir().expect("tempdir");
        let lp = dir.path().join("auth.json.lock");
        let _g = DirLock::acquire(&lp, Duration::from_secs(5)).expect("first acquire");
        let err = DirLock::acquire(&lp, Duration::from_millis(200))
            .expect_err("second acquire must time out while held");
        assert_eq!(err.kind(), io::ErrorKind::TimedOut);
    }

    #[test]
    fn reclaims_stale_directory() {
        let dir = tempfile::tempdir().expect("tempdir");
        let lp = dir.path().join("auth.json.lock");
        fs::create_dir(&lp).expect("mkdir stale");
        let old = SystemTime::now() - Duration::from_secs(30);
        filetime_set(&lp, old);
        let g =
            DirLock::acquire(&lp, Duration::from_millis(500)).expect("should reclaim stale dir");
        assert!(lp.is_dir());
        drop(g);
    }

    #[test]
    fn does_not_reclaim_fresh_directory() {
        let dir = tempfile::tempdir().expect("tempdir");
        let lp = dir.path().join("auth.json.lock");
        fs::create_dir(&lp).expect("mkdir fresh");
        let err = DirLock::acquire(&lp, Duration::from_millis(200))
            .expect_err("must not steal a fresh foreign lock");
        assert_eq!(err.kind(), io::ErrorKind::TimedOut);
    }

    #[test]
    fn heals_stale_leftover_regular_file() {
        // Simulates the poisoning artifact left by older flock-based pi_agent_rust.
        let dir = tempfile::tempdir().expect("tempdir");
        let lp = dir.path().join("auth.json.lock");
        fs::write(&lp, b"").expect("write leftover regular file");
        let old = SystemTime::now() - Duration::from_secs(30);
        filetime_set(&lp, old);
        assert!(lp.is_file());
        {
            let _g = DirLock::acquire(&lp, Duration::from_millis(500))
                .expect("should heal stale regular file and acquire");
            assert!(lp.is_dir(), "poisoning file must be replaced by a directory");
        }
        assert!(!lp.exists());
    }

    // Minimal mtime setter (avoids adding a dev-dep); uses std `File::set_times`.
    fn filetime_set(path: &Path, when: SystemTime) {
        let f = fs::File::open(path).expect("open for set_times");
        let times = fs::FileTimes::new().set_modified(when).set_accessed(when);
        f.set_times(times).expect("set_times");
    }
}
