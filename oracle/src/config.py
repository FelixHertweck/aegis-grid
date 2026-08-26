"""Env-driven settings for the Oracle wrapper. Plain env vars, matching how other containers in
this repo are configured (docker-compose `environment:`).
"""

import os
from dataclasses import dataclass, field
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    scenario: str = field(
        default_factory=lambda: os.environ.get("ORACLE_SCENARIO", "scenario-3.1")
    )

    host: str = field(default_factory=lambda: os.environ.get("ORACLE_HOST", "0.0.0.0"))
    port: int = field(default_factory=lambda: int(os.environ.get("ORACLE_PORT", "8080")))

    log_dir: Path = field(
        default_factory=lambda: Path(os.environ.get("ORACLE_LOG_DIR", "/var/log/oracle"))
    )
    content_dir: Path = field(
        default_factory=lambda: Path(os.environ.get("ORACLE_CONTENT_DIR", "/app/content"))
    )


settings = Settings()
