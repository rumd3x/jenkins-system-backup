# Backup Pipeline (Docker) — Technical Reference

**File:** `backup-docker.groovy`

Backs up a Linux host that runs Docker containers. Instead of archiving the whole filesystem (see [backup.md](backup.md)), it generates a compose file that recreates **all** containers on the host and copies every container volume and bind mount with `rsync` **without compression**. The result is uploaded to NAS storage and old backups are rotated.

Pair with [restore-docker.groovy](restore-docker.md).

---

## Backup layout

```
YYYY_MM_DD-<host>/
├── docker-compose.yaml     # recreates all containers
├── mounts.tsv              # type, volume name, host source path, kind (d/f), folder in backup
├── networks.tsv            # user-defined networks to pre-create on restore (name, driver, subnet)
├── volumes/<volume name>/  # content of each named volume
└── binds/<absolute path>/  # content of each bind mount
```

---

## Parameters

Identical to v1. Values are validated by the first stage (see below) because they end up in shell commands and paths on three machines.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `TARGET_HOST` | string | — | IPv4 address or hostname of the machine to back up (letters, digits, `.`, `_`, `-`) |
| `SSH_CREDENTIAL` | credentials | — | `UsernamePassword` credential for SSH access to the target host |
| `NAS_HOST` | string | — | IPv4 address or hostname of the NAS storage system |
| `NAS_SSH_CREDENTIAL` | credentials | — | `UsernamePassword` credential for SSH access to the NAS |
| `BACKUP_PATH` | string | — | Absolute directory path on the NAS where backups will be stored (no spaces or shell characters); created if missing |
| `DAYS_TO_KEEP` | string | `7` | Maximum number of backup directories to retain per host before the oldest are deleted. Must be a positive integer |
| `CHUNK_SIZE` | string | `2G` | **Unused in the Docker variant** (nothing is compressed or split). Kept so v1 job configurations keep working |

---

## Stages

### 1. Validate Parameters

Fails fast unless `TARGET_HOST`/`NAS_HOST` are plain hostnames or IPv4 addresses (no IPv6), `BACKUP_PATH` is an absolute path made of `A-Za-z0-9._/+@=-`, and `DAYS_TO_KEEP` is a positive integer (`0` would make rotation delete the backup that was just uploaded).

### 2. Cleanup Workspace

Removes any stale backup directories in the Jenkins workspace left over from a previous failed run.

### 3. Generate Compose File

Computes the backup name (`YYYY_MM_DD-<host>`) into `env.BACKUP_NAME`, then SSHes into the target and runs the image in the `AUTOCOMPOSE_IMAGE` environment variable (default `ghcr.io/red5d/docker-autocompose:latest`, with the Docker socket mounted) against **every** container (`docker ps -a`, including stopped ones). The YAML written to stdout is saved as `docker-compose.yaml`. The image is pulled from GHCR (`ghcr.io/red5d/docker-autocompose`) because the Docker Hub copy is outdated and crashes with `KeyError: 'MacAddress'` on current Docker engines. The stage fails if the host has no containers or the output has no `services:` section.

### 4. Inspect Mounts and Networks

Uses `docker inspect` on all containers to write:

- `mounts.tsv` — every `bind` and `volume` mount (deduplicated). Docker socket/runtime (`/var/run`, `/run`) and kernel/device paths (`/proc`, `/sys`, `/dev`) are skipped, as are sources that no longer exist or contain a tab in their path (they cannot be stored in the TSV). Named volumes are stored under `volumes/<name>`, bind mounts under `binds/<absolute path>`.
- `networks.tsv` — non-internal, user-defined networks used by the containers. autocompose declares these as `external`, so they must be created on restore. Internal networks are created by compose itself.

### 5. Copy Volumes and Mounts

For each entry in `mounts.tsv`:

1. Runs `rsync -aHX --numeric-ids --fake-super` from the target straight into the backup folder on the agent, uncompressed. The target side runs `sudo rsync` (`--rsync-path`), so root-owned data is readable and **no temporary copy is made on the target**. Exit code 24 (files vanished during transfer) is tolerated because data is live.
2. Because the agent is not root, `--fake-super` stores the real uid/gid, mode, and symlink/fifo/device information in extended attributes (`user.rsync.%stat`) instead of applying it. Symlinks and special files therefore appear as small placeholder files on the agent and NAS, and hard links are preserved.

Before the loop, the stage creates a private (mode 700) temp directory on the target holding a `sudo` askpass helper, and removes it when the stage ends — on success, failure, or build abort (EXIT/INT/TERM/HUP traps). See Security Notes.

**Why `--fake-super` and not a manifest?** The agent and NAS users are normally not root and cannot `chown`. rsync is used for every hop (target → agent, agent → NAS, and back on restore), so the ownership metadata travels with the files in xattrs and the restore applies it for real on the root side.

### 6. Verify Backup Integrity

Checks that `docker-compose.yaml` is non-empty and contains `services:`, and that each mount folder exists.

### 7. Upload to NAS

Uploads the backup directory to `NAS_HOST:BACKUP_PATH/<backup name>/` with `rsync -aHX --delete --numeric-ids --fake-super` (also fake-super on the NAS side), so the xattr-stored metadata survives. `--delete` makes a second run on the same day replace the earlier upload rather than merge into it, and `BACKUP_PATH` is created if it does not exist. The NAS password is passed through `SSHPASS`, not the command line. The date-prefixed name makes alphabetical order equal chronological order, which rotation relies on.

### 8. Rotate Old Backups

Lists backup directories named exactly `YYYY_MM_DD-<host>` (the date prefix is part of the glob, so host `web` never matches `my-web`'s backups), and if more than `DAYS_TO_KEEP` exist, deletes the oldest over SSH.

---

## Post

`cleanWs()` always runs to remove the Jenkins workspace regardless of build outcome.

---

## Requirements

- Jenkins agent with label `docker`, with `sshpass` and `rsync`
- `rsync` installed on the target host and on the NAS
- The agent workspace and the NAS backup location must be on filesystems that support **user xattrs** (e.g. ext4, xfs, btrfs; not older tmpfs). If they don't, rsync fails instead of silently losing ownership
- Target host with Docker; the SSH user must be `root` or a sudoer (the SSH password from `SSH_CREDENTIAL` is also used as the sudo password, or sudo may be passwordless), and the host must be able to pull the autocompose image (`ghcr.io`)
- Free space on the Jenkins agent for the **entire backup** (nothing is compressed); nothing extra is needed on the target
- The NAS must be accessible via SSH from the Jenkins agent

---

## Limitations

- Data is copied live and is not crash-consistent: stop or quiesce databases beforehand if you need guaranteed consistency.
- Unix sockets are not backed up. Symlinks, fifos and device nodes are stored as placeholders and recreated only when restored as root. ACLs are not copied.
- Ownership lives in xattrs, so copying the backup folder with tools that drop xattrs (plain `cp`, `scp`) loses it. Use rsync (or `tar --xattrs`).
- Only `bind` and `volume` mounts are backed up (`tmpfs` has no data). Volumes using non-local drivers are not copied.
- Images are not backed up; they are pulled again on restore.

---

## Security Notes

- SSH host key checking is disabled (`-o StrictHostKeyChecking=no`). For production use, consider pre-populating the Jenkins agent's known hosts file instead.
- Credentials are injected from the Jenkins Credentials store via `withCredentials`; SSH passwords (target and NAS) are passed to `sshpass` through the `SSHPASS` environment variable (`sshpass -e`) rather than the command line, and are never written to the Jenkins agent's disk.
- Jenkins runs without a TTY, so `sudo` cannot prompt. Each remote script is streamed over SSH with the password as the first stdin line; a shell function wraps `sudo` as `sudo -S -p ''`, feeding it from a shell variable. Arguments passed to remote scripts are single-quoted with embedded quotes escaped, so mount paths and names cannot inject commands. The password never appears in a command line on either host. If the SSH user is `root`, `sudo` is bypassed.
- Backup folders and the compose file are created with the agent's default umask (typically `755`/`644`), so other users of the NAS share (e.g. SMB clients) can browse them. Contents of each mount keep the source permissions. If you want the compose file private, restrict the NAS folder or `chmod` the file.
- The backup contains the full container configuration, including **environment variables (which often hold secrets)** and unencrypted volume data. Restrict access to the NAS location accordingly.
- The autocompose container gets the Docker socket (root-equivalent on the target). `latest` is a moving tag: set `AUTOCOMPOSE_IMAGE` to a pinned digest if you need reproducibility or supply-chain control.
- Parameter values are validated before use (stage 1); this protects the remote `ls`/`rm` used by rotation, which interpolate `BACKUP_PATH` and the host name.
- rsync owns the SSH stdin, so `sudo -S` cannot be used for the remote `sudo rsync`. For the copy stage the SSH password is written to a file in a private (mode 700) temp directory on the target, read by a `SUDO_ASKPASS` helper, and deleted when the stage ends (including on abort). It is the only place the password touches a disk, and only on the target. If the SSH user is `root` or sudo is passwordless, the helper is unused.
