"""Local configuration validation and route-preview commands."""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import replace
from pathlib import Path

from .config import EngineSettings
from .errors import NewStarAIError
from .registry import ModelRegistry
from .router import ModelRouter, RoutingRequest


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="new-star-ai", description=__doc__)
    parser.add_argument(
        "--models-file",
        type=Path,
        help="model registry JSON path; defaults to NEW_STAR_AI_MODELS_FILE or config/models.json",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("validate", help="validate the configured model registry")
    plan = commands.add_parser("plan", help="preview model candidates without making network calls")
    plan.add_argument("--capability", required=True)
    plan.add_argument("--preferred-model")
    plan.add_argument("--language")
    plan.add_argument("--required-tag", action="append", default=[])
    plan.add_argument("--exclude-model", action="append", default=[])
    plan.add_argument("--max-candidates", type=int)
    serve = commands.add_parser("serve", help="start the HTTP chat inference server")
    serve.add_argument("--host")
    serve.add_argument("--port", type=int)
    return parser


def run(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        settings = EngineSettings.from_env()
        if args.models_file is not None:
            settings = replace(settings, models_file=args.models_file)
        if args.command == "serve":
            from .server import create_server
            server = create_server(host=args.host, port=args.port, settings=settings)
            host, port = server.server_address[:2]
            print(f"New Star AI inference server listening on http://{host}:{port}", flush=True)
            try:
                server.serve_forever(poll_interval=0.25)
            except KeyboardInterrupt:
                pass
            finally:
                server.shutdown()
                server.server_close()
            return 0
        registry = ModelRegistry.from_json_file(settings.models_file, settings)
        if args.command == "validate":
            payload = {
                "valid": True,
                "model_count": len(registry.list_models()),
                "enabled_model_count": len(registry.list_models(enabled_only=True)),
                "models": [
                    {
                        "model_id": model.model_id,
                        "provider": model.provider,
                        "protocol": model.protocol,
                        "capabilities": sorted(model.capabilities),
                        "enabled": model.enabled,
                    }
                    for model in registry.list_models()
                ],
            }
        else:
            request = RoutingRequest(
                capability=args.capability,
                preferred_model=args.preferred_model,
                required_tags=frozenset(args.required_tag),
                excluded_models=frozenset(args.exclude_model),
                language=args.language,
                max_candidates=args.max_candidates,
            )
            route = ModelRouter(registry).plan(request)
            payload = {
                "request": {
                    "capability": request.capability,
                    "language": request.language,
                    "preferred_model": request.preferred_model,
                },
                "candidates": [
                    {
                        "model_id": model.model_id,
                        "provider": model.provider,
                        "protocol": model.protocol,
                        "model_name": model.model_name,
                        "priority": model.priority,
                        "weight": model.weight,
                    }
                    for model in route.candidates
                ],
            }
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0
    except (NewStarAIError, OSError, ValueError) as exc:
        print(f"new-star-ai: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(run())
