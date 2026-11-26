#!/usr/bin/env python3
"""Resolver and CLI checks for the diff walkthrough viewer."""

from __future__ import annotations

import importlib.machinery
import importlib.util
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SCRIPT = ROOT / "diff-walkthrough"
REPO = ROOT.parent
EXAMPLE_SHA = "91b56d743e9ef9462bdae37fc6fc31dce9f2e232"
EXAMPLE_RANGE = f"{EXAMPLE_SHA}^..{EXAMPLE_SHA}"

GIT_ENV = os.environ.copy()
GIT_ENV.update(
    {
        "GIT_AUTHOR_NAME": "Walkthrough Test",
        "GIT_AUTHOR_EMAIL": "walkthrough@example.com",
        "GIT_COMMITTER_NAME": "Walkthrough Test",
        "GIT_COMMITTER_EMAIL": "walkthrough@example.com",
    }
)


def load_tool():
    loader = importlib.machinery.SourceFileLoader("diff_walkthrough", str(SCRIPT))
    spec = importlib.util.spec_from_loader("diff_walkthrough", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


TOOL = load_tool()


def statuses(payload):
    found = [("intro", ref) for ref in payload["introRefs"]]
    for section in payload["sections"]:
        found.extend((section["title"], ref) for ref in section["refs"])
    return found


class ParseTests(unittest.TestCase):
    def test_review_fingerprints_follow_cited_hunks(self):
        diff = """diff --git a/demo.py b/demo.py
index aaa..bbb 100644
--- a/demo.py
+++ b/demo.py
@@ -1,2 +1,2 @@
 context
-old
+new
@@ -50 +50 @@
-before
+after
"""
        notes = """## First
`demo.py:1`
## Second
`demo.py:50`
## Both
`demo.py:1-50`
## File
`demo.py`
"""

        def fingerprints(patch, layout="line-by-line"):
            payload = TOOL.build_payload(patch, notes, "main...HEAD", layout)
            return {section["title"]: section["reviewFingerprint"] for section in payload["sections"]}

        original = fingerprints(diff)
        self.assertEqual(original, fingerprints(diff, "side-by-side"))
        for old, new in [("-old", "-older"), ("+new", "+newer"), (" context", " other")]:
            with self.subTest(change=new):
                changed = fingerprints(diff.replace(old, new).replace("aaa..bbb", "aaa..ccc"))
                self.assertEqual(original["Second"], changed["Second"])
                for title in ["First", "Both", "File"]:
                    self.assertNotEqual(original[title], changed[title])
        missing = fingerprints(diff.split("@@ -50")[0])
        self.assertNotEqual(original["Second"], missing["Second"])
        self.assertEqual(original["First"], missing["First"])

    def test_hunk_lines_context_and_additions(self):
        diff = "\n".join(
            [
                "diff --git a/src/A.java b/src/A.java",
                "index 111..222 100644",
                "--- a/src/A.java",
                "+++ b/src/A.java",
                "@@ -10,3 +10,4 @@ class A {",
                " same",
                "-old",
                "+new1",
                "+new2",
                " same2",
                "",
            ]
        )
        files = TOOL.parse_unified_diff(diff)
        self.assertEqual(files[0]["new_lines"], {10, 11, 12, 13})

    def test_quoted_and_escaped_paths(self):
        diff = "\n".join(
            [
                r'diff --git "a/dir/my file.txt" "b/dir/my file.txt"',
                r'--- "a/dir/my file.txt"',
                r'+++ "b/dir/my file.txt"',
                "@@ -1 +1 @@",
                "-a",
                "+b",
                r'diff --git "a/caf\303\251.txt" "b/caf\303\251.txt"',
                r'--- "a/caf\303\251.txt"',
                r'+++ "b/caf\303\251.txt"',
                "@@ -1 +1 @@",
                "-a",
                "+b",
                "",
            ]
        )
        files = TOOL.parse_unified_diff(diff)
        self.assertEqual([item["new"] for item in files], ["dir/my file.txt", "café.txt"])
        self.assertEqual(files[0]["new_lines"], {1})

    def test_binary_deleted_and_rename(self):
        diff = "\n".join(
            [
                "diff --git a/img/a.png b/img/a.png",
                "index 111..222 100644",
                "Binary files a/img/a.png and b/img/a.png differ",
                "diff --git a/src/Gone.java b/src/Gone.java",
                "deleted file mode 100644",
                "--- a/src/Gone.java",
                "+++ /dev/null",
                "@@ -1,2 +0,0 @@",
                "-a",
                "-b",
                "diff --git a/src/Old.java b/src/New.java",
                "similarity index 90",
                "rename from src/Old.java",
                "rename to src/New.java",
                "--- a/src/Old.java",
                "+++ b/src/New.java",
                "@@ -1,3 +1,3 @@",
                " same",
                "-old",
                "+new",
                " tail",
                "",
            ]
        )
        markdown = "\n".join(
            [
                "# Sample",
                "",
                "```",
                "`src/New.java:99`",
                "```",
                "",
                "See **`src/New.java:2`** in bold.",
                "",
                "## Files",
                "",
                "`img/a.png:1`",
                "`src/Gone.java:1`",
                "`src/Old.java:2`",
                "`src/New.java:2`",
                "`src/Missing.java:3-1`",
                "`src/New.java`",
                "",
            ]
        )
        payload = TOOL.build_payload(diff, markdown, "HEAD^..HEAD", "side-by-side")
        intro = payload["introRefs"]
        self.assertEqual([ref["raw"] for ref in intro], ["src/New.java:2"])
        self.assertEqual(intro[0]["status"], "ok")
        section = {ref["raw"]: ref for ref in payload["sections"][0]["refs"]}
        self.assertEqual(section["img/a.png:1"]["status"], "binary")
        self.assertEqual(section["src/Gone.java:1"]["status"], "deleted")
        self.assertEqual(section["src/Old.java:2"]["status"], "renamed")
        self.assertEqual(section["src/Old.java:2"]["jump"], "lines")
        self.assertIn("src/New.java", section["src/Old.java:2"]["message"])
        self.assertEqual(section["src/New.java:2"]["status"], "ok")
        self.assertEqual(section["src/Missing.java:3-1"]["status"], "bad-range")
        self.assertEqual(section["src/New.java"]["status"], "file")
        self.assertEqual(section["src/New.java"]["jump"], "file")
        self.assertTrue(all("99" not in ref["raw"] for ref in payload["sections"][0]["refs"]))
        self.assertNotIn("`src/New.java:99`", json.dumps(payload["sections"]))

    def test_partial_and_outside(self):
        diff = "\n".join(
            [
                "diff --git a/src/A.java b/src/A.java",
                "--- a/src/A.java",
                "+++ b/src/A.java",
                "@@ -10,3 +10,4 @@",
                " same",
                "-old",
                "+new1",
                "+new2",
                " same2",
                "",
            ]
        )
        markdown = "## Change\n\n`src/A.java:11-12`\n\n`src/A.java:1-2`\n\n`src/A.java:10-14`\n"
        payload = TOOL.build_payload(diff, markdown, "A..B", "side-by-side")
        refs = {ref["raw"]: ref for ref in payload["sections"][0]["refs"]}
        self.assertEqual(refs["src/A.java:11-12"]["status"], "ok")
        self.assertIsNone(refs["src/A.java:11-12"]["message"])
        self.assertEqual(refs["src/A.java:1-2"]["status"], "outside")
        self.assertEqual(refs["src/A.java:1-2"]["jump"], "none")
        self.assertEqual(refs["src/A.java:10-14"]["status"], "partial")
        self.assertEqual(refs["src/A.java:10-14"]["jump"], "lines")
        self.assertIn("14", refs["src/A.java:10-14"]["message"])


class CliTests(unittest.TestCase):
    def _git(self, repo, *args):
        subprocess.run(["git", "-C", str(repo), *args], check=True, env=GIT_ENV, capture_output=True, text=True)

    def _init_repo(self, repo: Path) -> None:
        self._git(repo, "init", "-b", "main")
        demo = repo / "src" / "Demo.java"
        demo.parent.mkdir(parents=True)
        demo.write_text("\n".join(f"line {number}" for number in range(1, 21)) + "\n", encoding="utf-8")
        (repo / "src" / "Gone.java").write_text("gone 1\ngone 2\n", encoding="utf-8")
        (repo / "src" / "OldName.java").write_text("alpha\nbeta\ngamma\ndelta\n", encoding="utf-8")
        self._git(repo, "add", ".")
        self._git(repo, "commit", "-m", "init")
        lines = demo.read_text(encoding="utf-8").splitlines()
        lines[9] = "line 10 changed"
        demo.write_text("\n".join(lines) + "\n", encoding="utf-8")
        (repo / "src" / "Gone.java").unlink()
        self._git(repo, "mv", "src/OldName.java", "src/NewName.java")
        (repo / "src" / "NewName.java").write_text("alpha\nbeta\ngamma changed\ndelta\n", encoding="utf-8")
        self._git(repo, "add", "-A")
        self._git(repo, "commit", "-m", "edit")

    def test_cli_resolves_and_warns(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            self._init_repo(repo)
            notes = repo / "notes.md"
            notes.write_text(
                "\n".join(
                    [
                        "# Demo change",
                        "",
                        "Nearby context is `src/Demo.java:11`.",
                        "",
                        "```",
                        "`src/Demo.java:999`",
                        "```",
                        "",
                        "## The edit",
                        "",
                        "`src/Demo.java:10`",
                        "",
                        "`src/Demo.java`",
                        "",
                        "## Not in the diff",
                        "",
                        "`src/Demo.java:1-2`",
                        "",
                        "`src/Demo.java:10-16`",
                        "",
                        "`src/Missing.java:4`",
                        "",
                        "`src/Gone.java:1`",
                        "",
                        "`src/OldName.java:3`",
                        "",
                        "## New name",
                        "",
                        "`src/NewName.java:3`",
                        "",
                    ]
                ),
                encoding="utf-8",
            )
            out = repo / "out.html"
            proc = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--repo",
                    str(repo),
                    "--range",
                    "HEAD^..HEAD",
                    "--walkthrough",
                    str(notes),
                    "--no-open",
                    "--out",
                    str(out),
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(proc.returncode, 0, proc.stderr)
            self.assertIn("warning:", proc.stderr)
            self.assertIn("Missing.java", proc.stderr)
            self.assertIn("Gone.java", proc.stderr)
            html = out.read_text(encoding="utf-8")
            self.assertIn("diff2html@", html)
            match = re.search(r'<script type="application/json" id="walk-data">(.*?)</script>', html, re.DOTALL)
            self.assertIsNotNone(match)
            payload = json.loads(match.group(1))
            self.assertEqual(payload["outputFormat"], "line-by-line")
            self.assertIn("line 10 changed", payload["diff"])
            intro = payload["introRefs"][0]
            self.assertEqual(intro["raw"], "src/Demo.java:11")
            self.assertEqual(intro["status"], "ok")
            flat = {ref["raw"]: ref for ref in payload["sections"][0]["refs"] + payload["sections"][1]["refs"] + payload["sections"][2]["refs"]}
            self.assertEqual(flat["src/Demo.java:10"]["status"], "ok")
            self.assertEqual(flat["src/Demo.java"]["jump"], "file")
            self.assertEqual(flat["src/Demo.java:1-2"]["status"], "outside")
            self.assertEqual(flat["src/Demo.java:10-16"]["status"], "partial")
            self.assertEqual(flat["src/Missing.java:4"]["status"], "missing-file")
            self.assertEqual(flat["src/Gone.java:1"]["status"], "deleted")
            self.assertEqual(flat["src/OldName.java:3"]["status"], "renamed")
            self.assertEqual(flat["src/NewName.java:3"]["status"], "ok")
            self.assertNotIn("999", [ref["raw"] for ref in flat.values()])

            side = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--repo",
                    str(repo),
                    "--range",
                    "HEAD^..HEAD",
                    "--walkthrough",
                    str(notes),
                    "--side-by-side",
                    "--no-open",
                    "--out",
                    str(repo / "side.html"),
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(side.returncode, 0, side.stderr)
            side_html = (repo / "side.html").read_text(encoding="utf-8")
            side_payload = json.loads(
                re.search(
                    r'<script type="application/json" id="walk-data">(.*?)</script>',
                    side_html,
                    re.DOTALL,
                ).group(1)
            )
            self.assertEqual(side_payload["outputFormat"], "side-by-side")

    def test_example_commit_resolves(self):
        probe = subprocess.run(
            ["git", "-C", str(REPO), "cat-file", "-e", f"{EXAMPLE_SHA}^{{commit}}"],
            capture_output=True,
            text=True,
        )
        if probe.returncode != 0:
            self.skipTest("example commit is not in this clone")
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "example.html"
            proc = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--repo",
                    str(REPO),
                    "--range",
                    EXAMPLE_RANGE,
                    "--walkthrough",
                    str(ROOT / "examples" / "mac-dock-icons.md"),
                    "--no-open",
                    "--out",
                    str(out),
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(proc.returncode, 0, proc.stderr)
            self.assertNotIn("warning:", proc.stderr)
            self.assertIn("0 warning", proc.stdout)
            html = out.read_text(encoding="utf-8")
            payload = json.loads(
                re.search(
                    r'<script type="application/json" id="walk-data">(.*?)</script>',
                    html,
                    re.DOTALL,
                ).group(1)
            )
            self.assertGreaterEqual(len(payload["sections"]), 5)
            for _title, ref in statuses(payload):
                self.assertEqual(ref["status"], "ok", ref)
                self.assertEqual(ref["jump"], "lines", ref)
                self.assertIsNone(ref["message"])


if __name__ == "__main__":
    unittest.main()
