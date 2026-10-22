# Restore Pipeline (Docker) — Technical Reference

**File:** `restore-docker.groovy`

Restores a backup produced by [backup-docker.groovy](backup-docker.md) onto a Linux host that already has Docker and Docker Compose installed and working: it copies every volume/bind mount back to its original place, uploads the compose file and starts all containers.

---

## Parameters

Identical to v1.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `TARGET_HOST` | string | — | IP or hostname of the machine to restore the containers to |
| `SSH_CREDENTIAL` | credentials | — | `UsernamePassword` credential for SSH access to the target host |
| `NAS_HOST` | string | — | IP or hostname of the NAS storage system |
| `NAS_SSH_CREDENTIAL` | credentials | — | `UsernamePassword` credential for SSH access to the NAS |
| `BACKUP_PATH` | string | — | Directory path on the NAS where backups are stored |

---

## Stages

### 1. Select Backup from NAS

Lists `BACKUP_PATH` on the NAS, filters entries matching `YYYY_MM_DD-<host>`, and pauses with a Jenkins `input` choice so an operator can pick one. Fails if no backups are found. The choice is stored in `env.SELECTED_BACKUP`.

### 2. Download Backup from NAS

Downloads the selected backup directory to the Jenkins agent via `scp -r`.

### 3. Verify Backup Integrity

Before the target is touched, checks that `docker-compose.yaml`, `mounts.tsv`, `networks.tsv` and `ownership.tsv` exist, that the compose file has a `services:` section, and that every mount folder listed in `mounts.tsv` is present. Entry-count mismatches against the manifest only warn.

### 4. Upload Compose File and Create Networks

Copies `docker-compose.yaml` to `~/docker-restore/` on the target (a fixed directory keeps the compose project name stable). Then creates each network from `networks.tsv` that does not exist yet (same driver and subnet as the source), because the generated compose file references them as `external`.

### 5. Restore Volumes and Mounts

For each entry in `mounts.tsv`:

1. Uploads the folder (and its slice of `ownership.tsv`) via `scp -r` to a temporary directory in `/var/tmp` on the target.
2. As root: for named volumes, creates the volume if missing (the compose file declares them `external`) and resolves its mountpoint with `docker volume inspect`; for bind mounts, uses the original host path. Copies the data into place with `cp -a`.
3. Re-applies uid/gid and permissions from the manifest (`chown` before `chmod`, since `chown` clears setuid/setgid) and recreates symlinks.
4. Deletes the temporary directory (also on failure).

Existing files at the destination are overwritten; unrelated files are left alone.

### 6. Start Containers

Runs `docker compose up -d` (or `docker-compose up -d` if the plugin is not available) in `~/docker-restore/`. Images are pulled if not present.

---

## Post

Removes backup directories from the workspace and runs `cleanWs()` regardless of outcome.

---

## Requirements

- Jenkins agent with label `docker`, with `sshpass` and `scp`
- Target host with Docker and Docker Compose (plugin or standalone) already working
- The SSH user on the target must be able to run `sudo docker`, `sudo docker compose`, `sudo cp`, `sudo chown`, `sudo chmod`, `sudo mkdir` and `sudo rm` without a password prompt (or be `root`)
- Free space in `/var/tmp` on the target for a temporary copy of the **largest single mount**
- The NAS must be accessible via SSH/SCP from the Jenkins agent

---

## Security Notes

- SSH host key checking is disabled (`-o StrictHostKeyChecking=no`). For production use, consider pre-populating the Jenkins agent's known hosts file instead.
- Credentials are injected from the Jenkins Credentials store via `withCredentials`; SSH passwords are passed to `sshpass` through the `SSHPASS` environment variable rather than the command line.
- **This pipeline overwrites files at the original mount paths and starts containers on the target.** Existing containers with the same names will conflict, and existing named volumes with the same names are merged into. Use a clean target and verify the selected backup before approving the input prompt.
- Restored `docker-compose.yaml` contains the containers' environment variables, which may include secrets.
