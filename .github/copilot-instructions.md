# GitHub Copilot Instructions

## Project Summary

**jenkins-system-backup** is a pair of Jenkins declarative pipeline scripts for full-system backup and restore of Linux servers (VPS, homelab, bare-metal).

- **`backup.groovy`** — Streams a complete filesystem tar archive from a target host to a Jenkins agent over SSH, compresses it with `zstd`, splits it into configurable-size chunks, uploads the resulting directory to NAS storage with a date-stamped name (`YYYY_MM_DD-<host>/`), and rotates old backups to stay within a configurable retention count. Excludes virtual/pseudo filesystems (`/proc`, `/sys`, `/dev`, `/run`) and volatile paths (`/tmp`, `/mnt`, `/media`, `/lost+found`).

- **`restore.groovy`** — Lists available backup directories on the NAS, presents them to an operator as a human-approval input prompt, downloads the selected directory, verifies the archive end-to-end, then concatenates and decompresses all chunks and streams them onto a target host via SSH to restore the full filesystem. The target machine is rebooted automatically after extraction.

Both pipelines run on a Docker-labeled Jenkins agent and use `sshpass`/`scp` for SSH access. Credentials are managed through the Jenkins Credentials store.

- **`backup-docker.groovy`** — Docker-only variant of the backup. Uses `ghcr.io/red5d/docker-autocompose:latest` on the target to generate a compose file for all containers, then copies every volume/bind mount uncompressed with `rsync` (`sudo rsync` on the target, rsync again to/from the NAS; ownership, symlinks and special files travel as xattrs via `--fake-super`). Same parameters, NAS upload and rotation as v1. The backup folder holds `docker-compose.yaml`, manifests and the mount folders instead of `part.*` chunks.

- **`restore-docker.groovy`** — Docker-only variant of the restore. Assumes Docker/Compose on the target; creates networks and named volumes, copies mounts back to their original paths, uploads the compose file and runs it. No reboot.

**Key files:**
- `backup.groovy` — backup pipeline
- `restore.groovy` — restore pipeline
- `backup-docker.groovy` — Docker backup pipeline
- `restore-docker.groovy` — Docker restore pipeline
- `docs/backup.md` — full technical reference for the backup pipeline
- `docs/restore.md` — full technical reference for the restore pipeline
- `docs/backup-docker.md` — full technical reference for the Docker backup pipeline
- `docs/restore-docker.md` — full technical reference for the Docker restore pipeline

---

## Documentation Rules

When making any change to this repository, always check whether the documentation needs to be updated and apply those updates in the same response.

- Changes to pipeline parameters → update the parameters table in the relevant `docs/` file and the README if the parameter affects the getting started flow.
- Changes to pipeline stages (added, removed, renamed, or behaviour changed) → update the stage description in the relevant `docs/` file.
- New pipeline scripts → create a corresponding `docs/<name>.md` and add the pipeline to the README summary and to the project summary above.
- Changes to requirements or security properties → update the Requirements and Security Notes sections in the relevant `docs/` file.
- The README must remain high-level and inviting. Do not add technical depth to `README.md` — put it in `docs/` instead.
