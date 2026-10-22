# jenkins-system-backup

Automated full-system backup and restore for Linux servers — powered by Jenkins pipelines.

Got a VPS, homelab server, or bare-metal machine you'd hate to lose? This project gives you two Jenkins pipelines that handle the entire backup lifecycle: snapshot the whole filesystem, store it safely on a NAS, keep only the most recent copies, and restore any of them with a single click.

No agents to install on the target. No special software. Just SSH.

---

## What it does

**Backup** (`backup.groovy`) — Streams a complete filesystem snapshot from your server directly to a NAS over SSH. Verifies the archive, names it by date, and automatically deletes older backups beyond a configurable retention limit.

**Restore** (`restore.groovy`) — Lists available backups on your NAS, asks you to pick one, verifies it, and streams it back onto any target host. The machine reboots automatically when done.

### Docker edition

Running everything in Docker? **`backup-docker.groovy`** and **`restore-docker.groovy`** take the same parameters but skip the full-system archive: they generate a compose file that recreates all your containers and copy just their volumes and bind mounts. Restore to any host that already has Docker and Compose — no reboot, no tarballs.

---

## What you need

- A Jenkins instance with a Docker-capable agent
- A source/target Linux server accessible via SSH
- A NAS (or any SSH-accessible storage) to hold the backup files
- SSH credentials stored in Jenkins' credential store

---

## Getting started

1. Copy `backup.groovy` and `restore.groovy` into your Jenkins instance as Pipeline jobs (or point them at this repository using a Multibranch Pipeline).
2. Add your SSH credentials to the Jenkins credential store as **Username with password** entries.
3. Run the **backup** pipeline — fill in your server address, NAS address, and destination path.
4. When you need to recover, run the **restore** pipeline — it will show you a list of available snapshots to choose from.

For full parameter references, stage-by-stage explanations, and design notes, see the [docs](docs/) folder.

---

## License

[MIT](LICENSE)
