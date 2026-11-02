"""Regression tests for Minecraft CI event-delta classification."""

from __future__ import annotations

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "ci_changed_components", ROOT / "scripts/ci-changed-components.py"
)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class MinecraftScopeTests(unittest.TestCase):
    def test_unrelated_changes_skip_both_matrices(self):
        self.assertEqual(
            module.affected_components({"desktop/src/app.rs", "README.md"}),
            (False, False),
        )

    def test_changes_are_scoped_to_one_component(self):
        self.assertEqual(
            module.affected_components({"clients/mod/fabric/src/main/java/Main.java"}),
            (True, False),
        )
        self.assertEqual(
            module.affected_components({"clients/plugin/build.gradle.kts"}),
            (False, True),
        )

    def test_copied_java_protocol_and_build_rules_require_both(self):
        for path in (
            "clients/mod/core/src/main/java/io/github/gringoestrangeiro/openradplugin/Controller.java",
            "tests/fixtures/reference.json",
            "src/protocol.rs",
            ".github/workflows/minecraft-version-compat.yml",
            "scripts/ci-changed-components.py",
        ):
            with self.subTest(path=path):
                self.assertEqual(module.affected_components({path}), (True, True))

    def test_unknown_event_or_unavailable_history_runs_all(self):
        self.assertEqual(module.affected_components(None), (True, True))
        self.assertIsNone(
            module.changed_paths("workflow_dispatch", {}, ROOT)
        )

    def test_inventory_only_python_tests_do_not_build_java(self):
        self.assertEqual(
            module.affected_components({"tests/test_minecraft_ci_scope.py"}),
            (False, False),
        )

    def test_git_event_deltas_use_real_commits(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            def git(*args: str) -> str:
                return subprocess.check_output(
                    ["git", *args], cwd=repo, text=True
                ).strip()

            git("init", "-q")
            git("config", "user.email", "ci-tests@example.invalid")
            git("config", "user.name", "CI Tests")
            (repo / "clients/mod").mkdir(parents=True)
            (repo / "clients/mod/fixture.txt").write_text("initial")
            git("add", ".")
            git("commit", "-qm", "mod")
            previous = git("rev-parse", "HEAD")
            (repo / "desktop").mkdir()
            (repo / "desktop/app.rs").write_text("desktop change")
            git("add", ".")
            git("commit", "-qm", "desktop")
            head = git("rev-parse", "HEAD")

            changed = module.changed_paths(
                "push", {"before": previous, "after": head}, repo
            )
            self.assertEqual(changed, {"desktop/app.rs"})
            self.assertEqual(module.affected_components(changed), (False, False))

            changed = module.changed_paths(
                "pull_request",
                {
                    "action": "synchronize",
                    "before": previous,
                    "pull_request": {"head": {"sha": head}, "base": {"sha": previous}},
                },
                repo,
            )
            self.assertEqual(changed, {"desktop/app.rs"})

            changed = module.changed_paths(
                "pull_request",
                {
                    "action": "opened",
                    "pull_request": {"head": {"sha": head}, "base": {"sha": previous}},
                },
                repo,
            )
            self.assertEqual(changed, {"desktop/app.rs"})

            # A force push / unknown old commit must not silently skip checks.
            self.assertIsNone(
                module.changed_paths("push", {"before": "f" * 40, "after": head}, repo)
            )
            self.assertIsNone(
                module.changed_paths(
                    "pull_request",
                    {"action": "synchronize", "pull_request": {"head": {"sha": head}}},
                    repo,
                )
            )

    def test_workflows_gate_expensive_jobs_and_keep_full_manual_run(self):
        workflows = {
            "minecraft-version-compat.yml": ("fabric", "paper"),
            "minecraft-2.2.0-integration.yml": ("fabric", "paper", "fabric-runtime-smoke"),
        }
        for file, jobs in workflows.items():
            with self.subTest(workflow=file):
                text = (ROOT / ".github/workflows" / file).read_text(encoding="utf-8")
                self.assertIn("  workflow_dispatch:", text)
                self.assertIn("  changes:\n", text)
                self.assertIn("fetch-depth: 0", text)
                for job in jobs:
                    component = "fabric" if job == "fabric-runtime-smoke" else job
                    self.assertIn(
                        f"  {job}:\n"
                        "    needs: changes\n"
                        f"    if: ${{{{ needs.changes.outputs.{component} == 'true' }}}}",
                        text,
                    )
                for dependency in ("src/protocol.rs", "tests/fixtures/**"):
                    self.assertEqual(text.count(f"      - '{dependency}'"), 2)

    def test_deletions_are_detected(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            def git(*args: str) -> str:
                return subprocess.check_output(
                    ["git", *args], cwd=repo, text=True
                ).strip()

            git("init", "-q")
            git("config", "user.email", "ci-tests@example.invalid")
            git("config", "user.name", "CI Tests")
            p = repo / "clients/plugin/Old.java"
            p.parent.mkdir(parents=True)
            p.write_text("class Old {}")
            git("add", ".")
            git("commit", "-qm", "old")
            before = git("rev-parse", "HEAD")
            p.unlink()
            git("add", "-A")
            git("commit", "-qm", "delete")
            after = git("rev-parse", "HEAD")
            changed = module.changed_paths(
                "push", {"before": before, "after": after}, repo
            )
            self.assertEqual(changed, {"clients/plugin/Old.java"})
            self.assertEqual(module.affected_components(changed), (False, True))


if __name__ == "__main__":
    unittest.main()
