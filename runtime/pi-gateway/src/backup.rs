use std::collections::BTreeSet;
use std::fs::{self, File, OpenOptions};
use std::io::{Cursor, Read, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Component, Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use uuid::Uuid;
use walkdir::WalkDir;
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

use crate::{Error, Result};

const MANIFEST_NAME: &str = "manifest.json";
const SESSIONS_PREFIX: &str = "sessions/";
const MAX_MANIFEST_BYTES: u64 = 64 * 1024;

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(deny_unknown_fields)]
struct BackupManifest {
    format: String,
    version: u32,
    created_at_epoch_ms: u64,
    session_count: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiskSession {
    pub session_path: String,
    pub bytes: u64,
    pub modified_at_epoch_ms: Option<u64>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RestoreReport {
    pub restored: Vec<String>,
    pub skipped_identical: Vec<String>,
    pub renamed_conflicts: Vec<String>,
}

pub fn list_disk_sessions(root: &Path) -> Result<Vec<DiskSession>> {
    let mut sessions = Vec::new();
    for entry in WalkDir::new(root).follow_links(false) {
        let entry = entry.map_err(|error| Error::Io(std::io::Error::other(error)))?;
        if !entry.file_type().is_file()
            || entry.path().extension().and_then(|v| v.to_str()) != Some("jsonl")
        {
            continue;
        }
        let canonical = fs::canonicalize(entry.path())?;
        if !canonical.starts_with(root) {
            continue;
        }
        let metadata = entry
            .metadata()
            .map_err(|error| Error::Io(std::io::Error::other(error)))?;
        let relative = canonical
            .strip_prefix(root)
            .map_err(|_| Error::InvalidRequest("session path escaped root".into()))?;
        sessions.push(DiskSession {
            session_path: path_to_archive_name(relative)?,
            bytes: metadata.len(),
            modified_at_epoch_ms: metadata.modified().ok().and_then(system_time_epoch_ms),
        });
    }
    sessions.sort_by(|left, right| left.session_path.cmp(&right.session_path));
    Ok(sessions)
}

pub fn create_backup(root: &Path, max_bytes: u64) -> Result<Vec<u8>> {
    let disk_sessions = list_disk_sessions(root)?;
    let total = disk_sessions.iter().try_fold(0_u64, |total, session| {
        total
            .checked_add(session.bytes)
            .ok_or_else(|| Error::InvalidRequest("backup size overflow".into()))
    })?;
    if total > max_bytes {
        return Err(Error::InvalidRequest(format!(
            "session data exceeds configured backup limit ({max_bytes} bytes)"
        )));
    }

    let cursor = Cursor::new(Vec::new());
    let mut writer = ZipWriter::new(cursor);
    let options = SimpleFileOptions::default()
        .compression_method(CompressionMethod::Deflated)
        .unix_permissions(0o600);
    let manifest = BackupManifest {
        format: "openhouse-pi-conversations".into(),
        version: 1,
        created_at_epoch_ms: system_time_epoch_ms(SystemTime::now()).unwrap_or(0),
        session_count: disk_sessions.len(),
    };
    writer.start_file(MANIFEST_NAME, options)?;
    writer.write_all(&serde_json::to_vec_pretty(&manifest)?)?;

    for session in disk_sessions {
        let source = root.join(Path::new(&session.session_path));
        writer.start_file(
            format!("{SESSIONS_PREFIX}{}", session.session_path),
            options,
        )?;
        let file = File::open(source)?;
        std::io::copy(&mut file.take(max_bytes), &mut writer)?;
    }
    Ok(writer.finish()?.into_inner())
}

#[allow(clippy::too_many_lines)]
pub fn restore_backup(root: &Path, archive: &[u8], max_bytes: u64) -> Result<RestoreReport> {
    if u64::try_from(archive.len()).unwrap_or(u64::MAX) > max_bytes {
        return Err(Error::InvalidRequest(
            "compressed backup exceeds configured limit".into(),
        ));
    }
    let mut archive = ZipArchive::new(Cursor::new(archive))?;
    let mut total = 0_u64;
    let mut seen = BTreeSet::new();
    let mut candidates = Vec::new();
    let mut manifest = None;

    for index in 0..archive.len() {
        let mut file = archive.by_index(index)?;
        if file.is_dir() {
            continue;
        }
        if file.name() == MANIFEST_NAME {
            if manifest.is_some() {
                return Err(Error::InvalidRequest(
                    "backup contains duplicate manifest.json".into(),
                ));
            }
            if file.size() > MAX_MANIFEST_BYTES {
                return Err(Error::InvalidRequest(
                    "backup manifest exceeds 64 KiB".into(),
                ));
            }
            total = total
                .checked_add(file.size())
                .ok_or_else(|| Error::InvalidRequest("restore size overflow".into()))?;
            if total > max_bytes {
                return Err(Error::InvalidRequest(
                    "backup contents exceed configured limit".into(),
                ));
            }
            let mut bytes = Vec::new();
            file.by_ref()
                .take(MAX_MANIFEST_BYTES + 1)
                .read_to_end(&mut bytes)?;
            if u64::try_from(bytes.len()).unwrap_or(u64::MAX) > MAX_MANIFEST_BYTES {
                return Err(Error::InvalidRequest(
                    "backup manifest exceeds 64 KiB".into(),
                ));
            }
            manifest = Some(serde_json::from_slice::<BackupManifest>(&bytes)?);
            continue;
        }
        let Some(relative_name) = file.name().strip_prefix(SESSIONS_PREFIX) else {
            return Err(Error::InvalidRequest(format!(
                "unexpected backup member: {}",
                file.name()
            )));
        };
        let relative = safe_relative_jsonl_path(relative_name)?;
        if !seen.insert(relative.clone()) {
            return Err(Error::InvalidRequest(format!(
                "duplicate backup member: {}",
                relative.display()
            )));
        }
        total = total
            .checked_add(file.size())
            .ok_or_else(|| Error::InvalidRequest("restore size overflow".into()))?;
        if total > max_bytes {
            return Err(Error::InvalidRequest(
                "uncompressed backup exceeds configured limit".into(),
            ));
        }
        let capacity = usize::try_from(file.size())
            .map_err(|_| Error::InvalidRequest("backup member is too large".into()))?;
        let mut data = Vec::with_capacity(capacity);
        file.read_to_end(&mut data)?;
        candidates.push((relative, data));
    }

    let manifest = manifest
        .ok_or_else(|| Error::InvalidRequest("backup is missing required manifest.json".into()))?;
    if manifest.format != "openhouse-pi-conversations" || manifest.version != 1 {
        return Err(Error::InvalidRequest(
            "unsupported conversation backup format or version".into(),
        ));
    }
    if manifest.session_count != candidates.len() {
        return Err(Error::InvalidRequest(format!(
            "manifest sessionCount {} does not match {} JSONL entries",
            manifest.session_count,
            candidates.len()
        )));
    }

    fs::create_dir_all(root)?;
    let mut report = RestoreReport {
        restored: Vec::new(),
        skipped_identical: Vec::new(),
        renamed_conflicts: Vec::new(),
    };
    for (relative, data) in candidates {
        let mut destination = root.join(&relative);
        let relative_string = path_to_archive_name(&relative)?;
        if destination.exists() {
            if fs::symlink_metadata(&destination)?.file_type().is_symlink() {
                return Err(Error::InvalidRequest(format!(
                    "restore destination is a symlink: {}",
                    relative.display()
                )));
            }
            if fs::read(&destination)? == data {
                report.skipped_identical.push(relative_string);
                continue;
            }
            destination = conflict_path(&destination);
            report.renamed_conflicts.push(path_to_archive_name(
                destination.strip_prefix(root).map_err(|_| {
                    Error::InvalidRequest("restore destination escaped root".into())
                })?,
            )?);
        }
        let parent = destination
            .parent()
            .ok_or_else(|| Error::InvalidRequest("restore path has no parent".into()))?;
        ensure_safe_parent(root, parent)?;
        let temporary = destination.with_extension(format!("{}.tmp", Uuid::new_v4()));
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .mode(0o600)
            .open(&temporary)?;
        file.write_all(&data)?;
        file.sync_all()?;
        fs::rename(&temporary, &destination)?;
        report.restored.push(path_to_archive_name(
            destination
                .strip_prefix(root)
                .map_err(|_| Error::InvalidRequest("restore destination escaped root".into()))?,
        )?);
    }
    Ok(report)
}

fn ensure_safe_parent(root: &Path, parent: &Path) -> Result<()> {
    let relative = parent
        .strip_prefix(root)
        .map_err(|_| Error::InvalidRequest("restore parent escaped session root".into()))?;
    let mut current = root.to_owned();
    for component in relative.components() {
        let Component::Normal(name) = component else {
            return Err(Error::InvalidRequest("unsafe restore parent path".into()));
        };
        current.push(name);
        match fs::symlink_metadata(&current) {
            Ok(metadata) => {
                if metadata.file_type().is_symlink() || !metadata.is_dir() {
                    return Err(Error::InvalidRequest(format!(
                        "restore parent is not a real directory: {}",
                        current.display()
                    )));
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                fs::create_dir(&current)?;
            }
            Err(error) => return Err(error.into()),
        }
        if !fs::canonicalize(&current)?.starts_with(root) {
            return Err(Error::InvalidRequest(
                "restore parent escaped session root".into(),
            ));
        }
    }
    Ok(())
}

fn safe_relative_jsonl_path(name: &str) -> Result<PathBuf> {
    let path = Path::new(name);
    if path.as_os_str().is_empty()
        || path.is_absolute()
        || path
            .components()
            .any(|component| !matches!(component, Component::Normal(_)))
        || path.extension().and_then(|value| value.to_str()) != Some("jsonl")
    {
        return Err(Error::InvalidRequest(format!(
            "unsafe session backup path: {name}"
        )));
    }
    Ok(path.to_owned())
}

fn path_to_archive_name(path: &Path) -> Result<String> {
    let mut parts = Vec::new();
    for component in path.components() {
        let Component::Normal(value) = component else {
            return Err(Error::InvalidRequest("unsafe session path".into()));
        };
        parts.push(
            value
                .to_str()
                .ok_or_else(|| Error::InvalidRequest("session path is not UTF-8".into()))?,
        );
    }
    Ok(parts.join("/"))
}

fn conflict_path(original: &Path) -> PathBuf {
    let stem = original
        .file_stem()
        .and_then(|value| value.to_str())
        .unwrap_or("session");
    original.with_file_name(format!("{stem}.restored-{}.jsonl", Uuid::new_v4()))
}

fn system_time_epoch_ms(time: SystemTime) -> Option<u64> {
    u64::try_from(time.duration_since(UNIX_EPOCH).ok()?.as_millis()).ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::{MetadataExt, symlink};

    #[test]
    fn backup_round_trip_and_conflict_preservation() {
        let source = tempfile::tempdir().expect("source");
        fs::create_dir_all(source.path().join("project")).expect("mkdir");
        fs::write(
            source.path().join("project/chat.jsonl"),
            b"{\"type\":\"session\"}\n",
        )
        .expect("write");
        let bytes = create_backup(source.path(), 1024 * 1024).expect("backup");

        let target = tempfile::tempdir().expect("target");
        let first = restore_backup(target.path(), &bytes, 1024 * 1024).expect("restore");
        assert_eq!(first.restored, vec!["project/chat.jsonl"]);
        assert_eq!(
            fs::metadata(target.path().join("project/chat.jsonl"))
                .expect("metadata")
                .mode()
                & 0o777,
            0o600
        );
        let second = restore_backup(target.path(), &bytes, 1024 * 1024).expect("restore again");
        assert_eq!(second.skipped_identical, vec!["project/chat.jsonl"]);
        fs::write(target.path().join("project/chat.jsonl"), b"different").expect("modify");
        let third = restore_backup(target.path(), &bytes, 1024 * 1024).expect("conflict");
        assert_eq!(third.renamed_conflicts.len(), 1);
        assert_eq!(third.restored.len(), 1);
    }

    #[test]
    fn rejects_path_traversal() {
        assert!(safe_relative_jsonl_path("../auth.jsonl").is_err());
        assert!(safe_relative_jsonl_path("auth.json").is_err());
    }

    #[test]
    fn restore_requires_valid_manifest_and_matching_count() {
        let cursor = Cursor::new(Vec::new());
        let mut writer = ZipWriter::new(cursor);
        writer
            .start_file("sessions/chat.jsonl", SimpleFileOptions::default())
            .expect("member");
        writer.write_all(b"{}\n").expect("member data");
        let missing = writer.finish().expect("zip").into_inner();
        let target = tempfile::tempdir().expect("target");
        assert!(restore_backup(target.path(), &missing, 1024 * 1024).is_err());

        let cursor = Cursor::new(Vec::new());
        let mut writer = ZipWriter::new(cursor);
        writer
            .start_file(MANIFEST_NAME, SimpleFileOptions::default())
            .expect("manifest");
        writer
            .write_all(
                br#"{"format":"openhouse-pi-conversations","version":1,"createdAtEpochMs":0,"sessionCount":0}"#,
            )
            .expect("manifest data");
        writer
            .start_file("sessions/chat.jsonl", SimpleFileOptions::default())
            .expect("member");
        writer.write_all(b"{}\n").expect("member data");
        let mismatch = writer.finish().expect("zip").into_inner();
        assert!(restore_backup(target.path(), &mismatch, 1024 * 1024).is_err());
    }

    #[test]
    fn restore_rejects_compressed_oversized_manifest() {
        let cursor = Cursor::new(Vec::new());
        let mut writer = ZipWriter::new(cursor);
        writer
            .start_file(
                MANIFEST_NAME,
                SimpleFileOptions::default().compression_method(CompressionMethod::Deflated),
            )
            .expect("manifest");
        writer
            .write_all(&vec![
                b' ';
                usize::try_from(MAX_MANIFEST_BYTES + 1).expect("size")
            ])
            .expect("oversized manifest");
        let archive = writer.finish().expect("zip").into_inner();
        assert!(archive.len() < usize::try_from(MAX_MANIFEST_BYTES).expect("max"));
        let target = tempfile::tempdir().expect("target");
        let error = restore_backup(target.path(), &archive, 1024 * 1024)
            .expect_err("oversized manifest must fail");
        assert!(error.to_string().contains("manifest exceeds 64 KiB"));
    }

    #[test]
    fn restore_rejects_symlink_parent_escape() {
        let source = tempfile::tempdir().expect("source");
        fs::create_dir_all(source.path().join("linked")).expect("source dir");
        fs::write(source.path().join("linked/chat.jsonl"), b"{}\n").expect("source file");
        let bytes = create_backup(source.path(), 1024 * 1024).expect("backup");

        let target = tempfile::tempdir().expect("target");
        let outside = tempfile::tempdir().expect("outside");
        symlink(outside.path(), target.path().join("linked")).expect("symlink");
        assert!(restore_backup(target.path(), &bytes, 1024 * 1024).is_err());
        assert!(!outside.path().join("chat.jsonl").exists());
    }
}
