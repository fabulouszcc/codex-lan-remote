# Codex LAN Remote

Follow the user's global AGENTS.md directory and local-configuration conventions.

- Verify desktop integration against the installed desktop Codex binary. The npm CLI may have a different protocol version.
- Separate persisted history access from access to a live desktop runtime. Never claim live synchronization from a successful history read.
- Never force writer locks, edit Codex databases/rollouts, replace desktop binaries, or stop existing Codex/EasyTier processes for a probe.
- EasyTier node status includes configuration secrets. Persist and display only explicitly selected status fields.
- Keep machine paths, generated schemas, probe outputs, credentials and personal configuration local and ignored.
- A bundled EasyTier instance and an existing system instance have separate ownership. Manage only the instance explicitly selected by the user.

