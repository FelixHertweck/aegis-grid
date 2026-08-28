#!/usr/bin/env python3
"""YAML config loader. Run as CLI to export shell variables: config.py export <file>"""
import os
import sys
from pathlib import Path

import yaml


def _default_port_registry_dir() -> str:
    """Registry must live somewhere multiple run.sh invocations actually share.

    Inside the eval container that's the docker-compose bind mount at
    /app/port-registry; run.sh can also be invoked directly on a host with no
    Docker involved at all, where /app doesn't exist — fall back to a path
    next to this file, which concurrent direct invocations from the same
    checkout share just fine since it's a real path on the real host fs.
    """
    env = os.environ.get("PORT_REGISTRY_DIR")
    if env:
        return env
    if Path("/.dockerenv").exists():
        return "/app/port-registry"
    return str(Path(__file__).resolve().parent.parent / ".port-registry")


DEFAULTS: dict = {
    "scenario": {
        "cave_config_name": "",
        # Default from the CAVE_WRAPPER_DIR env var so the docker-compose mount
        # (source==target host path) and the harness agree without editing config.
        "cave_wrapper_dir": os.environ.get("CAVE_WRAPPER_DIR", "/cave-wrapper"),
        "configs_subpath": "backend/configs",
    },
    "deploy": {
        "wait_time": 600,
        "lab_prefix": "auto",
        "public_vpn_port": "auto",
        "port_pool": "51820-51920",
        "port_registry_dir": _default_port_registry_dir(),
    },
    "openhands": {
        "base_url": "http://10.1.1.20:3000",
        "initial_wait": 15,
        "poll_interval": 15,
        "run_timeout": 7200,
    },
    "prompts": {
        "source": "",
        "mode": "cumulative",
    },
    "runs": {
        "count": 1,
    },
    "oracle": {
        "enabled": False,
        "base_url": "",
    },
    "context_script": {"cmd": "bash eval.sh"},
    "cleanup_script": {"cmd": "bash reset.sh"},
    "evaluation": {
        "template": "/app/prompts/template.md",
        "extraction_prompt": "/app/prompts/extraction.md",
        "output_dir": "/app/results",
        "output_name": "{scenario}_{model}_{date}.md",
        "llm": {
            "base_url_env": "EVAL_LLM_BASE_URL",
            "api_key_env": "EVAL_LLM_API_KEY",
            "model": "gpt-4o",
        },
    },
}


def _deep_merge(base: dict, override: dict) -> dict:
    result = base.copy()
    for k, v in override.items():
        if k in result and isinstance(result[k], dict) and isinstance(v, dict):
            result[k] = _deep_merge(result[k], v)
        else:
            result[k] = v
    return result


def load(config_file: str) -> dict:
    with open(config_file, encoding="utf-8") as f:
        user_cfg = yaml.safe_load(f) or {}
    return _deep_merge(DEFAULTS, user_cfg)


def _flatten(cfg: dict) -> dict[str, str]:
    s = cfg["scenario"]
    d = cfg["deploy"]
    o = cfg["openhands"]
    p = cfg["prompts"]
    r = cfg.get("runs", {})
    orc = cfg.get("oracle", {})
    cs = cfg.get("context_script", {})
    cl = cfg.get("cleanup_script", {})
    e = cfg["evaluation"]
    lm = e.get("llm", {})
    return {
        "CAVE_CONFIG_NAME": str(s.get("cave_config_name", "")),
        "CAVE_WRAPPER_DIR": str(s.get("cave_wrapper_dir", "/cave-wrapper")),
        "SCENARIO_CONFIG_DIR": "/".join([
            str(s.get("cave_wrapper_dir", "/cave-wrapper")).rstrip("/"),
            str(s.get("configs_subpath", "backend/configs")).strip("/"),
            str(s.get("cave_config_name", "")),
        ]),
        "DEPLOY_WAIT_TIME": str(d.get("wait_time", 600)),
        "LAB_PREFIX_CONFIG": str(d.get("lab_prefix", "auto")),
        "VPN_PORT_CONFIG": str(d.get("public_vpn_port", "auto")),
        "PORT_POOL": str(d.get("port_pool", "51820-51920")),
        "PORT_REGISTRY_DIR": str(d.get("port_registry_dir", _default_port_registry_dir())),
        "OH_BASE_URL": str(o.get("base_url", "http://10.1.1.20:3000")),
        "OH_INITIAL_WAIT": str(o.get("initial_wait", 15)),
        "OH_POLL_INTERVAL": str(o.get("poll_interval", 15)),
        "OH_RUN_TIMEOUT": str(o.get("run_timeout", 3600)),
        "PROMPTS_SOURCE": str(p.get("source", "")) if str(p.get("source", "")).startswith("/") else f"/app/config/prompts/{p.get('source', '')}",
        "PROMPTS_MODE": str(p.get("mode", "cumulative")),
        "NUM_RUNS": str(r.get("count", 1)),
        "ORACLE_ENABLED": "true" if orc.get("enabled", False) else "false",
        "ORACLE_BASE_URL": str(orc.get("base_url", "")),
        "CONTEXT_CMD": str(cs.get("cmd", "bash eval.sh")),
        "CLEANUP_CMD": str(cl.get("cmd", "bash reset.sh")),
        "EVAL_TEMPLATE": str(e.get("template", "/app/prompts/template.md")),
        "EVAL_EXTRACTION_PROMPT": str(e.get("extraction_prompt", "/app/prompts/extraction.md")),
        "EVAL_OUTPUT_DIR": str(e.get("output_dir", "/app/results")),
        "EVAL_OUTPUT_NAME": str(e.get("output_name", "{scenario}_{model}_{date}.md")),
        "EVAL_LLM_MODEL": str(lm.get("model", "")),
        "EVAL_LLM_API_KEY": str(lm.get("api_key", "")),
        "EVAL_LLM_API_KEY_ENV": str(lm.get("api_key_env", "EVAL_LLM_API_KEY")),
        "EVAL_LLM_BASE_URL": str(lm.get("base_url", "")),
        "EVAL_LLM_BASE_URL_ENV": str(lm.get("base_url_env", "EVAL_LLM_BASE_URL")),
    }


if __name__ == "__main__":
    if len(sys.argv) < 3 or sys.argv[1] != "export":
        print("Usage: config.py export <config_file>", file=sys.stderr)
        sys.exit(1)
    flat = _flatten(load(sys.argv[2]))
    for key, value in flat.items():
        escaped = value.replace("'", "'\\''")
        print(f"export {key}='{escaped}'")
