# Backup Pipeline (Docker) — Technical Reference

**File:** `backup-docker.groovy`

Backs up a Linux host that runs Docker containers. Instead of archiving the whole filesystem (see [backup.md](backup.md)), it generates a compose file that recreates **all** containers on the host and copies every container volume and bind mount over SCP **without compression**. The result is uploaded to NAS storage and old backups are rotated.

Pair with [restore-docker.groovy](restore-docker.md).

---

## Backup layout

```
YYYY_MM_DD-<host>/
├── docker-compose.yaml     # recreates all containers
├── mounts.tsv              # type, volume name, host source path, kind (d/f), folder in backup
├── networks.tsv            # user-defined networks to pre-create on restore (name, driver, subnet)
├── ownership.tsv           # uid/gid/mode/type/symlink target of every copied entry
├── volumes/<volume name>/  # content of each named volume
└── binds/<absolute path>/  # content of each bind mount
```

---

## Parameters

Identical to v1.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `TARGET_HOST` | string | — | IP or hostname of the machine to back up |
| `SSH_CREDENTIAL` | credentials | — | `UsernamePassword` credential for SSH access to the target host |
| `NAS_HOST` | string | — | IP or hostname of the NAS storage system |
| `NAS_SSH_CREDENTIAL` | credentials | — | `UsernamePassword` credential for SSH access to the NAS |
| `BACKUP_PATH` | string | — | Directory path on the NAS where backups will be stored |
| `DAYS_TO_KEEP` | string | `7` | Maximum number of backup directories to retain per host before the oldest are deleted |
| `CHUNK_SIZE` | string | `2G` | **Unused in the Docker variant** (nothing is compressed or split). Kept so v1 job configurations keep working |

---

## Stages

### 1. Cleanup Workspace

Removes any stale backup directories in the Jenkins workspace left over from a previous failed run.

### 2. Generate Compose File

Computes the backup name (`YYYY_MM_DD-<host>`) into `env.BACKUP_NAME`, then SSHes into the target and runs `red5d/docker-autocompose:latest` (with the Docker socket mounted) against **every** container (`docker ps -a`, including stopped ones). The YAML written to stdout is saved as `docker-compose.yaml`. The stage fails if the host has no containers or the output has no `services:` section.

### 3. Inspect Mounts and Networks

Uses `docker inspect` on all containers to write:

- `mounts.tsv` — every `bind` and `volume` mount (deduplicated). Docker socket/runtime (`/var/run`, `/run`) and kernel/device paths (`/proc`, `/sys`, `/dev`) are skipped, as are sources that no longer exist. Named volumes are stored under `volumes/<name>`, bind mounts under `binds/<absolute path>`.
- `networks.tsv` — non-internal, user-defined networks used by the containers. autocompose declares these as `external`, so they must be created on restore. Internal networks are created by compose itself.

### 4. Copy Volumes and Mounts

For each entry in `mounts.tsv`:

1. Records ownership, permissions, type and symlink target of every entry into `ownership.tsv` (`find -printf`).
2. Copies the source with `sudo cp -a` into a temporary directory under `/var/tmp` on the target and `chown`s that copy to the SSH user. This is what lets a non-root SSH user read root-owned volume data.
3. Removes anything that is not a regular file or directory from the copy (SCP follows symlinks and hangs on fifos/sockets). Symlinks are not lost: they are recorded in `ownership.tsv` and recreated on restore.
4. `scp -r` the copy to the backup folder on the agent — uncompressed.
5. Deletes the temporary copy on the target (also on failure).

**Why the ownership manifest?** SCP cannot preserve uid/gid, and the agent cannot `chown`. Without the manifest, restored volumes (e.g. databases owned by uid 999 with mode 700) would be unusable.

### 5. Verify Backup Integrity

Checks that `docker-compose.yaml` is non-empty and contains `services:`, that each mount folder exists, and compares the number of files/directories copied with the manifest. Count mismatches only warn, because live data can change during the copy.

### 6. Upload to NAS

Uploads the backup directory to `NAS_HOST:BACKUP_PATH/` via `scp -r`. The date-prefixed name makes alphabetical order equal chronological order, which rotation relies on.

### 7. Rotate Old Backups

Same as v1: lists backup directories ending in `-<host>`, and if more than `DAYS_TO_KEEP` exist, deletes the oldest over SSH.

---

## Post

`cleanWs()` always runs to remove the Jenkins workspace regardless of build outcome.

---

## Requirements

- Jenkins agent with label `docker`, with `sshpass` and `scp`
- Target host with Docker; the SSH user must be able to run `sudo docker`, `sudo find`, `sudo cp`, `sudo chown` and `sudo rm` without a password prompt (or be `root`), and must be able to pull `red5d/docker-autocompose:latest`
- Free space on the target (`/var/tmp`) for a temporary copy of the **largest single mount**, and on the Jenkins agent for the **entire backup** (nothing is compressed)
- The NAS must be accessible via SSH/SCP from the Jenkins agent

---

## Limitations

- Data is copied live and is not crash-consistent: stop or quiesce databases beforehand if you need guaranteed consistency.
- Hard links are copied as separate files; sockets and fifos are not backed up.
- Only `bind` and `volume` mounts are backed up (`tmpfs` has no data). Volumes using non-local drivers are not copied.
- Images are not backed up; they are pulled again on restore.

---

## Security Notes

- SSH host key checking is disabled (`-o StrictHostKeyChecking=no`). For production use, consider pre-populating the Jenkins agent's known hosts file instead.
- Credentials are injected from the Jenkins Credentials store via `withCredentials`; SSH passwords are passed to `sshpass` through the `SSHPASS` environment variable (`sshpass -e`) rather than the command line, and are never written to disk.
- The backup contains the full container configuration, including **environment variables (which often hold secrets)** and unencrypted volume data. Restrict access to the NAS location accordingly.
- The temporary copy on the target is owned by the SSH user for the duration of each mount's transfer.
