"""Append-only structured log — the audit trail every granted `ask_oracle` call feeds. It's also
what the hint service's tier-progression logic reads instead of keeping any in-memory state.

One JSONL file per MCP session under ORACLE_LOG_DIR, so concurrent sessions never interleave and
each starts its own tier progression from scratch.
"""

import threading
from pathlib import Path

from .models import OracleLogEntry
from .session import validate_session_id


class OracleLogStore:
    def __init__(self, log_dir: Path):
        self._log_dir = log_dir
        self._lock = threading.Lock()

    def _path(self, session_id: str) -> Path:
        # Re-validated here too, not just at the two call sites (ask_oracle's header, /report's
        # query param) — this is the one place a path actually gets built, so it's the backstop
        # against any future caller that forgets to validate upstream.
        validate_session_id(session_id)
        self._log_dir.mkdir(parents=True, exist_ok=True)
        return self._log_dir / f"{session_id}.jsonl"

    def append(self, entry: OracleLogEntry) -> None:
        with self._lock:
            with self._path(entry.session_id).open("a", encoding="utf-8") as f:
                f.write(entry.model_dump_json() + "\n")

    def all_entries(self, session_id: str) -> list[OracleLogEntry]:
        path = self._path(session_id)
        if not path.exists():
            return []
        entries = []
        with path.open(encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    entries.append(OracleLogEntry.model_validate_json(line))
        return entries

    def entries_for_category(self, session_id: str, category: str) -> list[OracleLogEntry]:
        return [e for e in self.all_entries(session_id) if e.category == category]

    def count_calls(self, session_id: str, category: str) -> int:
        return len(self.entries_for_category(session_id, category))

    def all_session_ids(self) -> list[str]:
        if not self._log_dir.is_dir():
            return []
        return sorted(p.stem for p in self._log_dir.glob("*.jsonl"))

    def all_entries_every_session(self) -> list[OracleLogEntry]:
        return [entry for sid in self.all_session_ids() for entry in self.all_entries(sid)]

    def reset_all(self) -> int:
        """Delete every session's log file. Returns how many were removed. Only ever globs
        *.jsonl inside ORACLE_LOG_DIR — never a caller-supplied path, so this carries none of
        the path-traversal concerns the session-keyed methods above guard against."""
        if not self._log_dir.is_dir():
            return 0
        with self._lock:
            paths = list(self._log_dir.glob("*.jsonl"))
            for p in paths:
                p.unlink()
        return len(paths)
