#!/usr/bin/env python3
"""OCELOT Eval-Harness — LLM-based evaluation of collected run artifacts."""
import argparse
import json
import os
import sys
from pathlib import Path

try:
    from openai import OpenAI
except ImportError:
    print("ERROR: openai not installed. Run: pip install openai", file=sys.stderr)
    sys.exit(1)

SCRIPT_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPT_DIR))

from lib.config import load as load_config

DEFAULT_EXTRACTION_PROMPT = SCRIPT_DIR / "prompts" / "extraction.md"
DEFAULT_SYNTHESIS_PROMPT = SCRIPT_DIR / "prompts" / "synthesis.md"
DEFAULT_TEMPLATE = SCRIPT_DIR / "prompts" / "template.md"
DEFAULT_MULTI_RUN_SYNTHESIS_PROMPT = SCRIPT_DIR / "prompts" / "multi_run_synthesis.md"

# Rough safety cap for --combine's input (per-run evaluation.md documents are
# concatenated verbatim). ~4 chars/token, so this is a conservative ~100k-token
# budget — fail fast instead of hitting the model's context limit after all
# runs have already completed.
MAX_COMBINE_CHARS = 400_000


def _read(path: Path, default: str = "(not available)") -> str:
    try:
        return path.read_text(encoding="utf-8")
    except (FileNotFoundError, IsADirectoryError):
        return default


def _get_prompt_dirs(run_dir: Path) -> list[Path]:
    dirs = sorted(
        (d for d in run_dir.iterdir() if d.is_dir() and d.name.startswith("prompt-")),
        key=lambda p: int(p.name.split("-")[1]),
    )
    return dirs


def _extract_per_prompt(
    client: OpenAI,
    model: str,
    extraction_prompt: str,
    prompt_dir: Path,
) -> dict:
    """LLM call for one prompt run → structured JSON block. Cached in eval_block.json."""
    cache = prompt_dir / "eval_block.json"
    if cache.exists():
        print(f"    (cached)")
        return json.loads(cache.read_text())

    name = _read(prompt_dir / "prompt_name.txt", prompt_dir.name)
    prompt_text = _read(prompt_dir / "prompt.txt")
    conversation = _read(prompt_dir / "conversation.md")
    metrics = json.loads(_read(prompt_dir / "metrics.json", "{}"))
    context = _read(prompt_dir / "context.txt")
    oracle_report = _read(prompt_dir / "oracle_report.json", "(Oracle hint service not used for this run)")

    user_msg = f"""# Prompt Configuration: {name}

## Prompt Sent to Agent
{prompt_text}

## Agent Conversation
{conversation}

## Token Metrics
```json
{json.dumps(metrics, indent=2)}
```

## Device Context (eval.sh output)
```
{context}
```

## Oracle Hint-Service Report (ask_oracle usage this run)
```json
{oracle_report}
```"""

    response = client.chat.completions.create(
        model=model,
        messages=[
            {"role": "system", "content": extraction_prompt},
            {"role": "user", "content": user_msg},
        ],
        response_format={"type": "json_object"},
    )

    result = json.loads(response.choices[0].message.content)
    # Merge in metrics from metrics.json so summary step has reliable numbers
    result.setdefault("_prompt_name", name)
    result.setdefault("_prompt_tokens", metrics.get("prompt_tokens", 0))
    result.setdefault("_completion_tokens", metrics.get("completion_tokens", 0))
    result.setdefault("_total_tokens", metrics.get("total_tokens", 0))
    result.setdefault("_model", metrics.get("model"))

    cache.write_text(json.dumps(result, indent=2, ensure_ascii=False))
    return result


def _generate_document(
    client: OpenAI,
    model: str,
    synthesis_prompt: str,
    template: str,
    blocks: list[dict],
    meta: dict,
) -> str:
    """Final LLM call: fill the complete evaluation template using all extracted blocks."""
    system = synthesis_prompt.replace("[TEMPLATE_WILL_BE_INSERTED_HERE]", template)

    user = f"""# Per-Configuration Evaluation Blocks
```json
{json.dumps(blocks, indent=2, ensure_ascii=False)}
```

# Run Metadata
```json
{json.dumps(meta, indent=2)}
```"""

    response = client.chat.completions.create(
        model=model,
        messages=[
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
    )
    return response.choices[0].message.content


def _combine_runs(
    client: OpenAI,
    model: str,
    multi_run_synthesis_prompt: str,
    run_dirs: list[Path],
) -> str:
    """Final LLM call: merge N independent per-run evaluation.md documents into one report."""
    sections = []
    for idx, run_dir in enumerate(run_dirs, start=1):
        meta = json.loads(_read(run_dir / "meta.json", "{}"))
        doc = _read(run_dir / "evaluation.md")
        sections.append(
            f"# Run {idx} ({run_dir.name})\n\n"
            f"## Run Metadata\n```json\n{json.dumps(meta, indent=2)}\n```\n\n"
            f"## Evaluation Document\n{doc}"
        )

    user_msg = "\n\n---\n\n".join(sections)

    if len(user_msg) > MAX_COMBINE_CHARS:
        print(
            f"ERROR: Combined input for --combine is {len(user_msg):,} characters, "
            f"exceeding the {MAX_COMBINE_CHARS:,}-character safety limit "
            "(likely to exceed the model's context window). "
            "Reduce runs.count, shorten the per-run evaluation.md documents/template, "
            "or raise MAX_COMBINE_CHARS in evaluate.py if your model can handle more.",
            file=sys.stderr,
        )
        sys.exit(1)

    response = client.chat.completions.create(
        model=model,
        messages=[
            {"role": "system", "content": multi_run_synthesis_prompt},
            {"role": "user", "content": user_msg},
        ],
    )
    return response.choices[0].message.content


def main() -> None:
    parser = argparse.ArgumentParser(description="OCELOT Eval-Harness — evaluation step")
    parser.add_argument("--config", required=True, help="Path to run config YAML")
    parser.add_argument("--run-dir", help="Run directory (contains prompt-N/ subdirs)")
    parser.add_argument(
        "--combine",
        nargs="+",
        metavar="RUN_DIR",
        help="Combine mode: paths to already-evaluated run directories (each must contain evaluation.md)",
    )
    parser.add_argument("--output", help="Output path for --combine mode's combined evaluation.md")
    parser.add_argument("--extraction-prompt", help="Override extraction prompt file")
    parser.add_argument("--synthesis-prompt", help="Override synthesis prompt file")
    parser.add_argument("--multi-run-synthesis-prompt", help="Override multi-run combination prompt file")
    parser.add_argument("--template", help="Override evaluation template file")
    args = parser.parse_args()

    if bool(args.run_dir) == bool(args.combine):
        print("ERROR: Specify exactly one of --run-dir or --combine.", file=sys.stderr)
        sys.exit(1)
    if args.combine and not args.output:
        print("ERROR: --combine requires --output.", file=sys.stderr)
        sys.exit(1)

    cfg = load_config(args.config)
    eval_cfg = cfg.get("evaluation", {})
    llm_cfg = eval_cfg.get("llm", {})

    model = llm_cfg.get("model", "gpt-4o")
    api_key_env = llm_cfg.get("api_key_env", "EVAL_LLM_API_KEY")
    base_url_env = llm_cfg.get("base_url_env", "EVAL_LLM_BASE_URL")

    api_key = llm_cfg.get("api_key") or os.environ.get(api_key_env)
    if not api_key:
        print(f"ERROR: No API key configured. Set 'evaluation.llm.api_key' in config.yml or set env var '{api_key_env}'.", file=sys.stderr)
        sys.exit(1)

    base_url = llm_cfg.get("base_url") or os.environ.get(base_url_env) or None
    client = OpenAI(api_key=api_key, base_url=base_url)

    if args.combine:
        multi_run_synthesis_prompt_path = (
            args.multi_run_synthesis_prompt
            or eval_cfg.get("multi_run_synthesis_prompt")
            or str(DEFAULT_MULTI_RUN_SYNTHESIS_PROMPT)
        )
        multi_run_synthesis_prompt = Path(multi_run_synthesis_prompt_path).read_text(encoding="utf-8")

        run_dirs = [Path(d) for d in args.combine]
        for rdir in run_dirs:
            if not (rdir / "evaluation.md").exists():
                print(f"ERROR: {rdir} has no evaluation.md — run its per-run evaluation first.", file=sys.stderr)
                sys.exit(1)

        print(f"Combining {len(run_dirs)} run evaluation(s)...")
        document = _combine_runs(client, model, multi_run_synthesis_prompt, run_dirs)

        output_path = Path(args.output)
        output_path.write_text(document, encoding="utf-8")
        print(f"Combined evaluation saved: {output_path}")
        return

    extraction_prompt_path = (
        args.extraction_prompt
        or eval_cfg.get("extraction_prompt")
        or str(DEFAULT_EXTRACTION_PROMPT)
    )
    synthesis_prompt_path = (
        args.synthesis_prompt
        or eval_cfg.get("synthesis_prompt")
        or str(DEFAULT_SYNTHESIS_PROMPT)
    )
    template_path = (
        args.template
        or eval_cfg.get("template")
        or str(DEFAULT_TEMPLATE)
    )

    extraction_prompt = Path(extraction_prompt_path).read_text(encoding="utf-8")
    synthesis_prompt = Path(synthesis_prompt_path).read_text(encoding="utf-8")
    template = Path(template_path).read_text(encoding="utf-8")

    run_dir = Path(args.run_dir)
    meta = json.loads(_read(run_dir / "meta.json", "{}"))

    prompt_dirs = _get_prompt_dirs(run_dir)
    if not prompt_dirs:
        print(f"ERROR: No prompt-N directories found in {run_dir}", file=sys.stderr)
        sys.exit(1)

    print(f"Extracting evaluation blocks for {len(prompt_dirs)} prompt configuration(s)...")
    blocks: list[dict] = []
    for pdir in prompt_dirs:
        name = _read(pdir / "prompt_name.txt", pdir.name)
        print(f"  [{pdir.name}] {name}...", end=" ", flush=True)
        block = _extract_per_prompt(client, model, extraction_prompt, pdir)
        blocks.append(block)
        print("done")

    print("Generating final evaluation document...")
    document = _generate_document(client, model, synthesis_prompt, template, blocks, meta)

    output_path = run_dir / "evaluation.md"
    output_path.write_text(document, encoding="utf-8")
    print(f"Evaluation saved: {output_path}")


if __name__ == "__main__":
    main()
