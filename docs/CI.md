# Continuous Integration and Knowledge Base Automation

This repository ships two GitHub Actions pipelines that cover the Spring Boot
service build/test cycle and the knowledge base ingestion automation. Both
workflows live in `.github/workflows/` and can be customised per environment.

## Chat API CI (`app-ci.yml`)

The `Chat API CI` workflow validates all pull requests and direct pushes that
modify the Spring Boot service. It performs the following steps:

1. Checks out the repository.
2. Installs Temurin JDK 21 with Maven dependency caching enabled.
3. Runs `mvn -B -ntp verify` from the `chat-api/` directory to compile the app
   and execute the unit test suite.
4. Uploads the Surefire reports as a build artifact (visible even on failures).

The workflow triggers on:

- `pull_request` events that touch `chat-api/**`, `pom.xml`, or the workflow
  file itself.
- `push` events to `main` that impact the same paths.
- Manual `workflow_dispatch` invocations.

No secrets are required for this workflow.

## Knowledge Base Ingestion (`kb-ingest.yml`)

The `Knowledge Base Ingestion` workflow automates documentation uploads to the
Chat API ingestion endpoint. It contains two jobs:

### Incremental uploads

Triggered on pushes to `main` that modify files under `docs/**`. The job:

1. Checks out the repo with full history so it can diff against the previous
   commit.
2. Installs Python 3.11 and the ingestion helper dependencies.
3. Uses `scripts/kb_ingest.py incremental` to upload every changed file under
   `docs/` to the ingestion API.

If no documentation files changed, the job exits early with a log message.

### Manual full rebuild

Available through the `Run workflow` button in the Actions tab. Operators can
optionally supply a `target_index` input, representing the OpenSearch index that
should receive the active alias after the rebuild. The job:

1. Installs the same Python dependencies as the incremental job.
2. Calls `scripts/kb_ingest.py full --docs-dir docs` to upload the entire
   documentation tree.
3. When `target_index` is provided and alias environment variables are present,
   swaps the configured OpenSearch alias to the new index.

### Ingestion helper script

`scripts/kb_ingest.py` centralises the ingestion logic. It reads configuration
from environment variables and uploads documentation files via
`POST /admin/ingest/upload`. The full rebuild command optionally performs an
OpenSearch alias swap through the `_aliases` API.

### Required secrets

Store the following secrets in the repository or organisation settings so the
workflow can authenticate with downstream services:

| Secret | Description |
| --- | --- |
| `INGEST_BASE_URL` | Base URL of the Chat API instance (e.g. `https://chat-api.example.com`). |
| `INGEST_TENANT_ID` | Tenant identifier passed to the ingestion endpoint. |
| `INGEST_AUTH_TOKEN` | Bearer token added to the `Authorization` header. |
| `INGEST_DEFAULT_ROLES` | Optional comma-separated list of document roles to attach. |
| `INGEST_VERIFY_SSL` | Optional flag (`true`/`false`) to control TLS verification. |
| `KB_OPENSEARCH_URL` | OpenSearch endpoint used for alias management (required for full rebuilds that swap aliases). |
| `KB_OPENSEARCH_ALIAS` | The alias that should be repointed to the rebuilt index. |
| `KB_OPENSEARCH_USERNAME` | Optional basic-auth username for OpenSearch. |
| `KB_OPENSEARCH_PASSWORD` | Optional basic-auth password for OpenSearch. |
| `KB_OPENSEARCH_VERIFY_SSL` | Optional flag (`true`/`false`) to control TLS verification for alias swaps. |

If alias swapping is not required, omit the `KB_OPENSEARCH_*` secrets and skip
supplying `target_index` when running the manual job.

### Local testing

Run the ingestion helper locally by exporting the required environment variables
and executing:

```bash
pip install -r scripts/requirements.txt
python scripts/kb_ingest.py incremental --files docs/developers-guide.md
```

For full rebuilds, add `--target-index my_index_v2` to exercise the alias swap
logic.
