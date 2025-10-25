#!/usr/bin/env python3
"""Utility for uploading documentation to the Chat API ingestion endpoints."""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import List, Sequence

import requests


class IngestConfig:
    def __init__(self) -> None:
        try:
            self.base_url = os.environ["INGEST_BASE_URL"].rstrip("/")
            self.tenant_id = os.environ["INGEST_TENANT_ID"]
            self.auth_token = os.environ["INGEST_AUTH_TOKEN"]
        except KeyError as exc:
            missing = exc.args[0]
            raise RuntimeError(f"Missing required environment variable: {missing}") from exc
        self.roles = _split_optional(os.environ.get("INGEST_DEFAULT_ROLES"))
        self.verify_ssl = _parse_bool(os.environ.get("INGEST_VERIFY_SSL", "true"))

    def headers(self) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self.auth_token}",
        }


def _split_optional(raw: str | None) -> List[str]:
    if not raw:
        return []
    return [segment.strip() for segment in raw.split(",") if segment.strip()]


def _parse_bool(value: str | None) -> bool:
    if value is None:
        return False
    return value.strip().lower() in {"1", "true", "yes", "on"}


def load_files(paths: Sequence[str]) -> List[Path]:
    files: List[Path] = []
    for raw in paths:
        path = Path(raw).resolve()
        if path.is_file():
            files.append(path)
    return files


def discover_docs(root: Path) -> List[Path]:
    return sorted(path for path in root.rglob("*") if path.is_file())


def upload_documents(cfg: IngestConfig, files: Sequence[Path]) -> None:
    if not files:
        print("No documents to ingest; exiting.")
        return

    session = requests.Session()
    session.headers.update(cfg.headers())

    for path in files:
        print(f"Uploading {path}...")
        try:
            _upload_single(session, cfg, path)
        except Exception as exc:  # noqa: BLE001
            raise RuntimeError(f"Failed to ingest {path}: {exc}") from exc


def _upload_single(session: requests.Session, cfg: IngestConfig, path: Path) -> None:
    url = f"{cfg.base_url}/admin/ingest/upload"
    title = path.stem
    external_id = str(path.relative_to(Path.cwd()))

    fields: List[tuple[str, str]] = [
        ("tenantId", cfg.tenant_id),
        ("title", title),
        ("externalId", external_id),
    ]
    for role in cfg.roles:
        fields.append(("roles", role))

    with path.open("rb") as handle:
        files = {"file": (path.name, handle)}
        response = session.post(
            url,
            files=files,
            data=fields,
            verify=cfg.verify_ssl,
            timeout=60,
        )
    if response.status_code >= 300:
        message = _format_error(response)
        raise RuntimeError(message)

    payload = response.json()
    doc_id = payload.get("documentId") or payload.get("id")
    chunk_count = payload.get("chunkCount") or payload.get("chunks")
    print(f"Ingested {path.name}: document={doc_id}, chunks={chunk_count}")


def _format_error(response: requests.Response) -> str:
    try:
        body = response.json()
        detail = json.dumps(body, indent=2)
    except ValueError:
        detail = response.text
    return f"HTTP {response.status_code}: {detail}"


def swap_alias(target_index: str) -> None:
    base_url = os.environ.get("KB_OPENSEARCH_URL")
    alias = os.environ.get("KB_OPENSEARCH_ALIAS")
    if not base_url or not alias:
        print("Alias swap skipped: KB_OPENSEARCH_URL or KB_OPENSEARCH_ALIAS not set.")
        return

    username = os.environ.get("KB_OPENSEARCH_USERNAME")
    password = os.environ.get("KB_OPENSEARCH_PASSWORD")
    auth = (username, password) if username and password else None
    verify_ssl = _parse_bool(os.environ.get("KB_OPENSEARCH_VERIFY_SSL", "true"))

    session = requests.Session()
    session.auth = auth
    session.verify = verify_ssl

    alias_url = f"{base_url.rstrip('/')}/_alias/{alias}"
    response = session.get(alias_url, timeout=30)
    if response.status_code not in (200, 404):
        raise RuntimeError(_format_error(response))

    existing_indices: List[str] = []
    if response.status_code == 200:
        data = response.json()
        existing_indices = list(data.keys())

    actions: List[dict[str, dict[str, str]]] = []
    for index in existing_indices:
        actions.append({"remove": {"index": index, "alias": alias}})
    actions.append({"add": {"index": target_index, "alias": alias}})

    payload = {"actions": actions}
    aliases_url = f"{base_url.rstrip('/')}/_aliases"
    post_response = session.post(
        aliases_url,
        json=payload,
        timeout=30,
    )
    if post_response.status_code >= 300:
        raise RuntimeError(_format_error(post_response))

    print(f"Alias '{alias}' now points to index '{target_index}'.")


def run_incremental(args: argparse.Namespace) -> None:
    cfg = IngestConfig()
    files = load_files(args.files)
    upload_documents(cfg, files)


def run_full(args: argparse.Namespace) -> None:
    cfg = IngestConfig()
    docs_root = Path(args.docs_dir).resolve()
    if not docs_root.exists():
        raise RuntimeError(f"Docs directory not found: {docs_root}")

    files = discover_docs(docs_root)
    upload_documents(cfg, files)

    if args.target_index:
        swap_alias(args.target_index)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="NetCourier knowledge base ingestion helper")
    subparsers = parser.add_subparsers(dest="command", required=True)

    incremental = subparsers.add_parser("incremental", help="Upload a specific list of files")
    incremental.add_argument("--files", nargs="*", default=[], help="Files to ingest")
    incremental.set_defaults(func=run_incremental)

    full = subparsers.add_parser("full", help="Upload the entire docs tree and optionally swap aliases")
    full.add_argument("--docs-dir", default="docs", help="Directory containing documentation to ingest")
    full.add_argument("--target-index", help="OpenSearch index that should receive the alias")
    full.set_defaults(func=run_full)

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        args.func(args)
    except Exception as exc:  # noqa: BLE001
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
