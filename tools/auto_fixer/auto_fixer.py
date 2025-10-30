"""Automation to detect failing GitHub workflows and attempt auto-fixes.

This script orchestrates the following high-level steps:

1. Inspect the most recent failing workflow run for the relevant branch.
2. Download and summarise the failure logs with an LLM.
3. Ask the LLM for a unified diff patch that should resolve the failure.
4. Apply the patch on a new branch, push it, and open a PR with auto-merge enabled.
5. Monitor the PR checks and either finish successfully or iterate with the new failure logs.

The implementation deliberately isolates GitHub/OpenAI communication so that the
execution environment can supply mock clients when running integration tests.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import string
import subprocess
import tarfile
import tempfile
import textwrap
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, List, Optional

import requests

try:  # Optional dependency for OpenAI SDK; the script can fallback to REST.
    from openai import OpenAI
except Exception:  # pragma: no cover - OpenAI SDK may be unavailable in CI.
    OpenAI = None  # type: ignore


GITHUB_API = "https://api.github.com"


class AutoFixerError(RuntimeError):
    """Raised when the automation cannot proceed."""


@dataclass
class RepoConfig:
    owner: str
    name: str
    github_token: str
    openai_api_key: str
    model: str = "gpt-4.1-mini"
    max_iterations: int = 0
    initial_run_id: Optional[int] = None
    initial_branch: Optional[str] = None

    @classmethod
    def from_env(cls) -> "RepoConfig":
        missing = [
            name for name in ["GITHUB_TOKEN", "REPO_OWNER", "REPO_NAME", "OPENAI_API_KEY"]
            if not os.getenv(name)
        ]
        if missing:
            raise AutoFixerError(
                "Missing required environment variables: " + ", ".join(missing)
            )
        initial_run_id_env = os.getenv("FAILED_WORKFLOW_RUN_ID")
        return cls(
            owner=os.environ["REPO_OWNER"],
            name=os.environ["REPO_NAME"],
            github_token=os.environ["GITHUB_TOKEN"],
            openai_api_key=os.environ["OPENAI_API_KEY"],
            model=os.getenv("OPENAI_MODEL", "gpt-4.1-mini"),
            max_iterations=int(os.getenv("AUTO_FIX_MAX_ITERATIONS", "0")),
            initial_run_id=int(initial_run_id_env) if initial_run_id_env else None,
            initial_branch=os.getenv("FAILED_WORKFLOW_BRANCH"),
        )


@dataclass
class WorkflowRun:
    id: int
    html_url: str
    head_sha: str
    display_title: str
    head_branch: str
    conclusion: Optional[str]


@dataclass
class DiffResponse:
    patch: str
    rationale: str


class GitHubClient:
    def __init__(self, config: RepoConfig) -> None:
        self._config = config
        self._session = requests.Session()
        self._session.headers.update(
            {
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {config.github_token}",
                "X-GitHub-Api-Version": "2022-11-28",
            }
        )

    def _request(self, method: str, path: str, **kwargs) -> requests.Response:
        url = f"{GITHUB_API}{path}"
        response = self._session.request(method, url, timeout=60, **kwargs)
        if response.status_code >= 400:
            raise AutoFixerError(
                f"GitHub API request failed ({response.status_code}): {response.text}"
            )
        return response

    def latest_failed_run(self, branch: Optional[str] = None) -> Optional[WorkflowRun]:
        path = f"/repos/{self._config.owner}/{self._config.name}/actions/runs"
        params = {"status": "failure", "per_page": 1}
        if branch:
            params["branch"] = branch
        response = self._request("GET", path, params=params)
        data = response.json()
        runs = data.get("workflow_runs", [])
        if not runs:
            return None
        run = runs[0]
        return self._parse_workflow_run(run)

    def get_workflow_run(self, run_id: int) -> WorkflowRun:
        path = f"/repos/{self._config.owner}/{self._config.name}/actions/runs/{run_id}"
        response = self._request("GET", path)
        return self._parse_workflow_run(response.json())

    @staticmethod
    def _parse_workflow_run(data: dict) -> WorkflowRun:
        return WorkflowRun(
            id=data["id"],
            html_url=data["html_url"],
            head_sha=data["head_sha"],
            display_title=data.get("display_title", data.get("name", "workflow")),
            head_branch=data.get("head_branch", ""),
            conclusion=data.get("conclusion"),
        )

    def download_logs(self, run_id: int, destination: Path) -> Path:
        path = f"/repos/{self._config.owner}/{self._config.name}/actions/runs/{run_id}/logs"
        response = self._request("GET", path)
        archive = destination / "logs.tgz"
        archive.write_bytes(response.content)
        with tarfile.open(archive) as tar:
            tar.extractall(destination)
        return destination

    def create_branch(self, branch: str, base_sha: str) -> None:
        path = f"/repos/{self._config.owner}/{self._config.name}/git/refs"
        self._request(
            "POST",
            path,
            json={"ref": f"refs/heads/{branch}", "sha": base_sha},
        )

    def create_pull_request(self, branch: str, title: str, body: str) -> int:
        path = f"/repos/{self._config.owner}/{self._config.name}/pulls"
        response = self._request(
            "POST",
            path,
            json={
                "title": title,
                "body": body,
                "head": branch,
                "base": "main",
            },
        )
        return response.json()["number"]

    def enable_auto_merge(self, pr_number: int) -> None:
        query = textwrap.dedent(
            """
            mutation($prId: ID!) {
              enablePullRequestAutoMerge(
                input: {pullRequestId: $prId, mergeMethod: SQUASH}
              ) {
                pullRequest { number }
              }
            }
            """
        )
        pr_id = self._pull_request_node_id(pr_number)
        response = self._request(
            "POST",
            "/graphql",
            json={"query": query, "variables": {"prId": pr_id}},
        )
        data = response.json()
        if "errors" in data:
            raise AutoFixerError(f"Failed to enable auto-merge: {data['errors']}")

    def _pull_request_node_id(self, pr_number: int) -> str:
        query = textwrap.dedent(
            """
            query($owner: String!, $name: String!, $number: Int!) {
              repository(owner: $owner, name: $name) {
                pullRequest(number: $number) {
                  id
                }
              }
            }
            """
        )
        response = self._request(
            "POST",
            "/graphql",
            json={
                "query": query,
                "variables": {
                    "owner": self._config.owner,
                    "name": self._config.name,
                    "number": pr_number,
                },
            },
        )
        data = response.json()
        try:
            return data["data"]["repository"]["pullRequest"]["id"]
        except KeyError as exc:
            raise AutoFixerError("Unable to resolve PR node id") from exc

    def list_check_runs(self, sha: str) -> List[str]:
        path = f"/repos/{self._config.owner}/{self._config.name}/commits/{sha}/check-runs"
        response = self._request("GET", path)
        return [item["conclusion"] or "pending" for item in response.json().get("check_runs", [])]


class OpenAIClient:
    def __init__(self, config: RepoConfig) -> None:
        self._config = config
        if OpenAI is not None:
            self._client = OpenAI(api_key=config.openai_api_key)
        else:
            self._client = None
        self._session = requests.Session()
        self._session.headers.update(
            {
                "Authorization": f"Bearer {config.openai_api_key}",
                "Content-Type": "application/json",
            }
        )

    def summarise_logs(self, logs: str) -> str:
        system_prompt = (
            "You are an experienced DevOps engineer. Summarise the failure logs into "
            "a short, actionable problem description."
        )
        response = self._chat(
            system_prompt,
            [
                {
                    "role": "user",
                    "content": f"Summarise the following workflow failure logs:\n{logs}"
                    "\nPlease limit the summary to 200 words.",
                }
            ],
        )
        return response.strip()

    def propose_patch(self, summary: str, error_excerpt: str) -> DiffResponse:
        system_prompt = (
            "You are helping to fix a failing CI run. Respond ONLY with JSON object "
            "containing keys 'rationale' and 'patch'. The 'patch' must be a unified diff."
        )
        user_prompt = textwrap.dedent(
            f"""
            Based on the CI failure summary below, produce a unified diff that fixes the issue.
            - The diff must be directly applicable with `git apply` from the repository root.
            - Only include the diff, do not provide explanations in the diff itself.
            - Ensure the diff is syntactically valid.

            Summary:
            {summary}

            Key error excerpt:
            {error_excerpt}
            """
        )
        response = self._chat(system_prompt, [{"role": "user", "content": user_prompt}])
        try:
            data = json.loads(response)
            return DiffResponse(patch=data["patch"], rationale=data.get("rationale", ""))
        except json.JSONDecodeError as exc:  # pragma: no cover - depends on LLM output.
            raise AutoFixerError("Model did not return valid JSON") from exc

    def _chat(self, system_prompt: str, messages: List[dict]) -> str:
        if self._client is not None:
            completion = self._client.responses.create(
                model=self._config.model,
                input=[{"role": "system", "content": system_prompt}, *messages],
                max_output_tokens=2000,
            )
            return completion.output_text
        payload = {
            "model": self._config.model,
            "messages": [{"role": "system", "content": system_prompt}, *messages],
        }
        response = self._session.post("https://api.openai.com/v1/chat/completions", json=payload)
        if response.status_code >= 400:
            raise AutoFixerError(
                f"OpenAI API request failed ({response.status_code}): {response.text}"
            )
        data = response.json()
        return data["choices"][0]["message"]["content"]


def read_logs_from_directory(path: Path, limit: int = 2000) -> str:
    chunks: List[str] = []
    for file in sorted(path.rglob("*.log")):
        text = file.read_text(errors="ignore")
        chunks.append(text)
    combined = "\n".join(chunks)
    return combined[-limit:]


def extract_failure_snippet(logs: str, max_chars: int = 800) -> str:
    """Return the most relevant failure excerpt to share with the LLM."""

    lowered = logs.lower()
    keywords = ["error", "exception", "fail", "failure", "traceback"]
    indices = [lowered.rfind(keyword) for keyword in keywords if keyword in lowered]
    if indices:
        anchor = max(indices)
        start = max(0, anchor - 200)
        end = min(len(logs), anchor + max_chars)
        snippet = logs[start:end]
    else:
        snippet = logs[-max_chars:]
    return snippet.strip()


def run_git(*args: str) -> None:
    subprocess.run(["git", *args], check=True)


def random_branch_name() -> str:
    suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=8))
    return f"auto-fix/{suffix}"


def apply_patch(patch: str) -> None:
    process = subprocess.Popen(["git", "apply", "-"], stdin=subprocess.PIPE)
    stdout, stderr = process.communicate(patch.encode("utf-8"))
    if process.returncode != 0:
        raise AutoFixerError(f"Failed to apply patch. stdout={stdout} stderr={stderr}")


def commit_all(message: str) -> None:
    run_git("add", "-A")
    run_git("commit", "-m", message)


def push_branch(branch: str) -> None:
    run_git("push", "origin", branch)


def checkout(branch: str) -> None:
    run_git("checkout", branch)


def current_head_sha() -> str:
    return subprocess.check_output(["git", "rev-parse", "HEAD"]).decode().strip()


def poll_checks(client: GitHubClient, sha: str, timeout: int = 3600, interval: int = 30) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        conclusions = client.list_check_runs(sha)
        if conclusions and all(c == "success" for c in conclusions):
            return True
        if any(c in {"failure", "cancelled"} for c in conclusions):
            return False
        time.sleep(interval)
    raise AutoFixerError("Timed out waiting for checks to complete")


def iterate_auto_fix(config: RepoConfig) -> None:
    github = GitHubClient(config)
    openai_client = OpenAIClient(config)

    processed_runs: set[int] = set()
    branch_hint = config.initial_branch
    next_run_id = config.initial_run_id
    iteration = 1
    while config.max_iterations <= 0 or iteration <= config.max_iterations:
        max_display = "∞" if config.max_iterations <= 0 else str(config.max_iterations)
        print(f"Iteration {iteration}/{max_display}")
        if next_run_id is not None:
            run = github.get_workflow_run(next_run_id)
            next_run_id = None
        else:
            run = github.latest_failed_run(branch_hint)
        if run is None:
            print("No failing workflow detected. Exiting.")
            return
        if (run.conclusion or "").lower() != "failure":
            print(
                "Latest workflow run did not conclude with a failure. "
                "Auto fix only runs on failed pipelines. Exiting."
            )
            return
        branch_hint = run.head_branch or branch_hint
        if run.id in processed_runs:
            print(f"Workflow run {run.id} already processed. Waiting for a new failure.")
            time.sleep(60)
            continue
        processed_runs.add(run.id)
        print(f"Handling workflow run {run.id} ({run.display_title})")

        with tempfile.TemporaryDirectory() as tmpdir:
            logs_dir = github.download_logs(run.id, Path(tmpdir))
            logs = read_logs_from_directory(Path(logs_dir))
        error_excerpt = extract_failure_snippet(logs)
        summary = openai_client.summarise_logs(logs)
        print("Summary:\n", summary)
        print("Error excerpt:\n", error_excerpt)

        diff = openai_client.propose_patch(summary, error_excerpt)
        branch = random_branch_name()
        print(f"Creating branch {branch}")

        checkout("main")
        run_git("pull", "--ff-only")
        run_git("checkout", "-b", branch)

        apply_patch(diff.patch)
        commit_all(f"Auto fix for failing workflow {run.display_title}")
        head_sha = current_head_sha()
        push_branch(branch)

        branch_hint = branch

        pr_number = github.create_pull_request(
            branch,
            title=f"Auto fix: {run.display_title}",
            body=textwrap.dedent(
                f"""
                ## Summary
                {diff.rationale or 'Automated fix suggested by LLM.'}

                Workflow run: {run.html_url}
                """
            ).strip(),
        )
        print(f"Opened PR #{pr_number}")
        try:
            github.enable_auto_merge(pr_number)
            print("Enabled auto-merge")
        except AutoFixerError as exc:
            print(f"Warning: could not enable auto-merge: {exc}")

        print("Waiting for checks to complete...")
        success = poll_checks(github, head_sha)
        if success:
            print("Checks passed. Stopping automation.")
            return
        print("Checks failed. Iterating with updated failure logs.")
        iteration += 1
    raise AutoFixerError("Reached maximum iterations without resolving failures")


def parse_args(argv: Optional[Iterable[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Auto-fix failing GitHub workflows using LLMs")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Run through detection and summarisation without applying patches",
    )
    return parser.parse_args(argv)


def main(argv: Optional[Iterable[str]] = None) -> None:
    args = parse_args(argv)
    config = RepoConfig.from_env()
    if args.dry_run:
        github = GitHubClient(config)
        run = github.latest_failed_run()
        if run is None:
            print("No failing workflow detected.")
            return
        with tempfile.TemporaryDirectory() as tmpdir:
            logs_dir = github.download_logs(run.id, Path(tmpdir))
            logs = read_logs_from_directory(Path(logs_dir), limit=2000)
        openai_client = OpenAIClient(config)
        summary = openai_client.summarise_logs(logs)
        print(summary)
        print("\nError excerpt:\n", extract_failure_snippet(logs))
        return
    iterate_auto_fix(config)


if __name__ == "__main__":
    main()
