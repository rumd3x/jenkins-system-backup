# Restore Pipeline (Docker) — Technical Reference

**File:** `restore-docker.groovy`

Restores a backup produced by [backup-docker.groovy](backup-docker.md) onto a Linux host that already has Docker and Docker Compose installed and working: it copies every volume/bind mount back to its original place, uploads the compose file and starts all containers.

---

## Parameters

Identical to v1. Values are validated by the first stage (see below).

| Parameter | Type | Default | Description |
|---|---|---|---|
| `TARGET_HOST` | string | — | IPv4 address or hostname of the machine to restore the containers to (letters, digits, `.`, `_`, `-`) |
| `SSH_CREDENTIAL` | credentials | — | `UsernamePassword` credential for SSH access to the target host |
| `NAS_HOST` | string | — | IPv4 address or hostname of the NAS storage system |
| `NAS_SSH_CREDENTIAL` | credentials | — | `UsernamePassword` credential for SSH access to the NAS |
| `BACKUP_PATH` | string | — | Absolute directory path on the NAS where backups are stored (no spaces or shell characters) |

---

## Stages

### 1. Validate Parameters

Fails fast unless `TARGET_HOST`/`NAS_HOST` are plain hostnames or IPv4 addresses (no IPv6) and `BACKUP_PATH` is an absolute path made of `A-Za-z0-9._/+@=-`.

### 2. Select Backup from NAS

Lists `BACKUP_PATH` on the NAS, filters entries matching `YYYY_MM_DD-<host>` (`<host>` limited to `A-Za-z0-9._-`), and pauses with a Jenkins `input` choice so an operator can pick one. Fails if no backups are found. The choice is stored in `env.SELECTED_BACKUP`.

### 3. Download Backup from NAS

Downloads the selected backup directory to the Jenkins agent with `rsync -aHX --numeric-ids --fake-super` (also fake-super on the NAS side), preserving the xattr-stored ownership metadata. The NAS password is passed through `SSHPASS`, not the command line.

### 4. Verify Backup Integrity

Before the target is touched, checks that `docker-compose.yaml`, `mounts.tsv`, and `networks.tsv` exist, that the compose file has a `services:` section, and that every mount folder listed in `mounts.tsv` is present. Because `mounts.tsv` decides where root writes on the target, each entry is also checked: type must be `bind`/`volume`, kind `d`/`f`, the source an absolute path outside `/`, `/proc`, `/sys`, `/dev`, `/run` and `/var/run`, and the backup folder under `volumes/` or `binds/` without `..` components. A malformed or tampered `mounts.tsv` fails the build before anything is changed.

### 5. Upload Compose File and Create Networks

Copies `docker-compose.yaml` to `~/docker-restore/` on the target (a fixed directory keeps the compose project name stable). Then creates each network from `networks.tsv` that does not exist yet (same driver and subnet as the source), because the generated compose file references them as `external`.

### 6. Restore Volumes and Mounts

For each entry in `mounts.tsv`:

1. As root: for named volumes, creates the volume if missing (the compose file declares them `external`) and resolves its mountpoint with `docker volume inspect`; for bind mounts, uses the original host path. Creates the destination directory.
2. Runs `rsync -aHX --numeric-ids --fake-super` from the agent straight into place; the target side runs `sudo rsync` (`--rsync-path`), so **no temporary copy of the data is made on the target**. The sender reads the real uid/gid, modes and symlink/special-file info from the xattrs written at backup time, and the receiver (root on the target) applies them for real.

Before the loop, the stage creates a private (mode 700) temp directory on the target holding a `sudo` askpass helper, and removes it when the stage ends — on success, failure, or build abort (EXIT/INT/TERM/HUP traps). Existing files at the destination are overwritten; unrelated files are left alone.

### 7. Start Containers

Runs `docker compose up -d` (or `docker-compose up -d` if the plugin is not available) in `~/docker-restore/`. Images are pulled if not present.

---

## Post

Removes backup directories from the workspace and runs `cleanWs()` regardless of outcome.

---

## Requirements

- Jenkins agent with label `docker`, with `sshpass`, `scp` (compose file upload) and `rsync`
- `rsync` installed on the target host and on the NAS
- Agent workspace on a filesystem that supports user xattrs
- Target host with Docker and Docker Compose (plugin or standalone) already working
- The SSH user on the target must be `root` or a sudoer (the SSH password from `SSH_CREDENTIAL` is also used as the sudo password, or sudo may be passwordless)
- The NAS must be accessible via SSH from the Jenkins agent

---

## Security Notes

- SSH host key checking is disabled (`-o StrictHostKeyChecking=no`). For production use, consider pre-populating the Jenkins agent's known hosts file instead.
- Credentials are injected from the Jenkins Credentials store via `withCredentials`; SSH passwords are passed to `sshpass` through the `SSHPASS` environment variable rather than the command line, and are never written to the Jenkins agent's disk.
- Jenkins runs without a TTY, so `sudo` cannot prompt. Each remote script is streamed over SSH with the password as the first stdin line; a shell function wraps `sudo` as `sudo -S -p ''`, feeding it from a shell variable. Arguments passed to remote scripts are single-quoted with embedded quotes escaped, so values from `mounts.tsv` cannot inject commands. The password never appears in a command line on either host. If the SSH user is `root`, `sudo` is bypassed.
- rsync owns the SSH stdin, so `sudo -S` cannot be used for the remote `sudo rsync`. For the restore stage the SSH password is written to a file in a private (mode 700) temp directory on the target, read by a `SUDO_ASKPASS` helper, and deleted when the stage ends (including on abort). It is the only place the password touches a disk, and only on the target. If the SSH user is `root` or sudo is passwordless, the helper is unused.
- **This pipeline overwrites files at the original mount paths and starts containers on the target.** Existing containers with the same names will conflict, and existing named volumes with the same names are merged into. Use a clean target and verify the selected backup before approving the input prompt.
- Restored `docker-compose.yaml` contains the containers' environment variables, which may include secrets.
