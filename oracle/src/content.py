"""Loads hint content from a mounted JSON file — no external backend, no HTTP client.

Content is scenario-scoped: $ORACLE_CONTENT_DIR/<scenario>.json, loaded once at process
startup (see main.py) based on ORACLE_SCENARIO / ORACLE_CONTENT_DIR (config.py). Loading fails
fast and loudly on a missing or malformed file rather than starting with partial content —
mirrors the old CTFd-backed version's own startup-time failure mode (a bad CTFd login also
crashed at import time), just for a different root cause.
"""

import json
from pathlib import Path

from .models import HintContent


def load_content(content_dir: Path, scenario: str) -> HintContent:
    path = content_dir / f"{scenario}.json"
    if not path.is_file():
        raise FileNotFoundError(f"No hint content for scenario '{scenario}' at {path}")
    return HintContent.model_validate(json.loads(path.read_text(encoding="utf-8")))
