# Restore Pipeline — Technical Reference

**File:** `restore.groovy`

Lists available backups on a NAS, prompts an operator to select one, downloads the backup directory, verifies the archive, streams it decompressed onto a target Linux host to restore the full filesystem, and reboots the machine.

---

## Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `TARGET_HOST` | string | — | IP or hostname of the machine to restore the backup to |
| `SSH_CREDENTIAL` | credentials | — | `UsernamePassword` credential for SSH access to the target host |
| `NAS_HOST` | string | — | IP or hostname of the NAS storage system |
| `NAS_SSH_CREDENTIAL` | credentials | — | `UsernamePassword` credential for SSH access to the NAS |
| `BACKUP_PATH` | string | — | Directory path on the NAS where backups are stored |

---

## Stages

### 1. Select Backup from NAS

SSHes into the NAS and runs `ls -1` on `BACKUP_PATH`. The result is filtered in Groovy to entries matching the `YYYY_MM_DD-<host>` naming pattern, which excludes any unrelated files or directories. The filtered list is presented to the operator as a `choice` input parameter via the Jenkins UI. **The pipeline pauses at this stage and waits for a human to make a selection.** The chosen directory name is stored in `env.SELECTED_BACKUP` for subsequent stages.

If no matching backup directories are found, the pipeline fails immediately with a descriptive error.

### 2. Download Backup from NAS

Downloads the selected backup directory from `NAS_HOST:BACKUP_PATH/<selected>/` to the Jenkins agent workspace via `scp -r`. The local directory will contain all numbered chunk files (`part.000`, `part.001`, etc.).

### 3. Verify Backup Integrity

Pipes all chunks through `zstd -d` and then `tar -tf - > /dev/null` entirely in-memory. This validates the complete archive end-to-end without extracting anything, before the target host is touched.

### 4. Stream and Restore Backup on Target Host

Concatenates all chunk files in order (`part.000`, `part.001`, …) and pipes them through `zstd -d` to decompress, then over SSH into `sudo tar -xpf - -C / --numeric-owner` on the target host, overwriting the entire filesystem from root.

**`--numeric-owner`** preserves the original uid/gid numbers from the backup as-is, rather than resolving them against account names on the target system. This is important when the target is a freshly provisioned machine whose `/etc/passwd` may differ from the source.

**`sudo reboot`** is chained after extraction so the restored OS re-initializes all runtime state (systemd units, network interfaces, device nodes, etc.) from a clean boot. The pipeline stage itself will exit when the SSH connection drops due to the reboot — this is expected.

---

## Post

Removes all backup directories from the workspace (`rm -rf */`) and runs `cleanWs()` regardless of pipeline outcome.

---

## Requirements

- Jenkins agent with label `docker`
- `sshpass`, `scp`, and `zstd` available on the Jenkins agent
- The SSH user on the target host must be able to run `sudo tar` and `sudo reboot` without a password prompt (or be `root`)
- The NAS must be accessible via SSH/SCP from the Jenkins agent

---

## Security Notes

- SSH host key checking is disabled (`-o StrictHostKeyChecking=no`). For production use, consider pre-populating the Jenkins agent's known hosts file instead.
- Credentials are injected from the Jenkins Credentials store via `withCredentials` and are never written to disk or printed in logs.
- **This pipeline overwrites the entire target filesystem and immediately reboots the machine.** Verify the target host and selected backup carefully before approving the input prompt.
