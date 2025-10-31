# Auto Fixer

This directory contains a Python script that attempts to detect failing GitHub Actions
workflows on the `main` branch and automatically generate fixes using an LLM.

## Environment Variables

The script requires the following environment variables to run:

- `GITHUB_TOKEN` – token with `repo` and `workflow` scopes.
- `REPO_OWNER` – GitHub organisation or user name.
- `REPO_NAME` – repository name.
- `OPENAI_API_KEY` – API key for the selected OpenAI compatible model.
- `OPENAI_MODEL` *(optional)* – model identifier (defaults to `gpt-4.1-mini` when unset or blank).
- `AUTO_FIX_MAX_ITERATIONS` *(optional)* – maximum retry count (default `3`).

## Usage

Install dependencies:

```bash
pip install -r tools/auto_fixer/requirements.txt
```

Run a dry run (detect latest failing run and summarise logs):

```bash
python tools/auto_fixer/auto_fixer.py --dry-run
```

Execute the auto-fix loop:

```bash
python tools/auto_fixer/auto_fixer.py
```

Integrate the script inside a GitHub workflow triggered on `workflow_run`
failures to automatically attempt fixes when the `main` branch pipeline fails.
