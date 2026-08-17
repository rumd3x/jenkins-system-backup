# Backup Pipeline — Technical Reference

**File:** `backup.groovy`

Streams a full filesystem snapshot from a target Linux host to the Jenkins agent, compresses it with `zstd`, splits it into fixed-size chunks, uploads the chunks as a directory to NAS storage, and rotates old backups to stay within a configurable retention limit.

---

## Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `TARGET_HOST` | string | — | IP or hostname of the machine to back up |
| `SSH_CREDENTIAL` | credentials | — | `UsernamePassword` credential for SSH access to the target host |
| `NAS_HOST` | string | — | IP or hostname of the NAS storage system |
| `NAS_SSH_CREDENTIAL` | credentials | — | `UsernamePassword` credential for SSH access to the NAS |
| `BACKUP_PATH` | string | — | Directory path on the NAS where backups will be stored |
| `DAYS_TO_KEEP` | string | `7` | Maximum number of backup directories to retain per host before the oldest are deleted |
| `CHUNK_SIZE` | string | `2G` | Size of each backup chunk (e.g. `2G`, `500M`) — smaller values reduce peak disk usage on the Jenkins agent |

---

## Stages

### 1. Cleanup Workspace

Removes any stale backup directories in the Jenkins workspace left over from a previous failed run.

### 2. Create and Stream Backup

Computes the backup name (`YYYY_MM_DD-<host>`) and stores it in `env.BACKUP_NAME`. Then SSHes into `TARGET_HOST` using `sshpass` and runs `sudo tar -cpf -` to stream the entire filesystem to stdout. The stream is piped through `zstd` for compression and then through `split` to produce fixed-size chunks named `part.000`, `part.001`, etc. inside a local directory named after the backup.

**Why stream instead of copy?** The tar is never written to disk on the target — it streams directly to stdout and over SSH to the Jenkins agent. The agent only needs enough disk for the compressed chunks.

**Why `zstd`?** It delivers significantly better compression than `lz4` while remaining faster than `gzip`, making it a good balance for large system backups. It is available as the `zstd` package on most Linux distributions.

**Excluded paths** (virtual/pseudo filesystems and volatile directories that must not be restored):

| Path | Reason |
|---|---|
| `/proc`, `/sys` | Kernel virtual filesystems, recreated at boot |
| `/dev` | Device node filesystem, managed by `udev` |
| `/run` | Runtime data, valid only for the current boot |
| `/tmp` | Ephemeral data |
| `/mnt`, `/media` | External mount points |
| `/lost+found` | Filesystem recovery artifacts |

**`--warning=no-file-changed`** suppresses non-fatal tar warnings raised when a file is modified during archiving (e.g. an active log file), which would otherwise cause a non-zero exit code and fail the pipeline.

### 3. Verify Backup Integrity

Pipes all chunks through `zstd -d` and then through `tar -tf - > /dev/null` entirely in-memory, without writing anything to disk. This validates the complete archive structure end-to-end before the upload.

### 4. Upload to NAS

Uploads the local backup directory (`YYYY_MM_DD-<host>/`) to `NAS_HOST:BACKUP_PATH/` via `scp -r`. The date-prefix naming ensures alphabetical sort equals chronological order, which the rotation stage depends on.

### 5. Rotate Old Backups

Lists all backup directories on the NAS whose names end with `-<host>` for the current target. If the total count exceeds `DAYS_TO_KEEP`, the oldest directories (lowest in alphabetical/date sort order) are deleted via `rm -rf` over SSH. If no backups exist yet, the stage exits cleanly.

---

## Post

`cleanWs()` always runs to remove the Jenkins workspace regardless of build outcome.

---

## Requirements

- Jenkins agent with label `docker`
- `sshpass`, `scp`, and `zstd` available on the Jenkins agent
- `split` (GNU coreutils) available on the Jenkins agent
- The SSH user on the target host must be able to run `sudo tar` without a password prompt (or be `root`)
- The NAS must be accessible via SSH/SCP from the Jenkins agent

---

## Security Notes

- SSH host key checking is disabled (`-o StrictHostKeyChecking=no`). For production use, consider pre-populating the Jenkins agent's known hosts file instead.
- Credentials are injected from the Jenkins Credentials store via `withCredentials` and are never written to disk or printed in logs.
