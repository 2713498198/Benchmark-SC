#!/usr/bin/env python3
"""Contract tests for the portable Windows Runner archive."""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "build-runner-package.py"


class RunnerPackageTest(unittest.TestCase):
    def test_builds_deterministic_archive_with_portable_layout(self) -> None:
        with tempfile.TemporaryDirectory(prefix="cbsc-runner-package-test-") as temporary:
            root = Path(temporary)
            node = root / "node.exe"
            license_file = root / "LICENSE"
            node.write_bytes(b"MZ-test-node-runtime")
            license_file.write_text("test license\n", encoding="utf-8")
            output = root / "out"
            command = [
                sys.executable,
                str(SCRIPT),
                "--output-dir", str(output),
                "--node-exe", str(node),
                "--node-license", str(license_file),
                "--source-commit", "a" * 40,
            ]
            for _ in range(2):
                subprocess.run(command, cwd=ROOT, check=True, capture_output=True, text=True)
                manifest_path = output / "runner-package-manifest.json"
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                archive = output / manifest["filename"]
                digest = hashlib.sha256(archive.read_bytes()).hexdigest()
                self.assertEqual(manifest["schema"], "cryptobench-runner-package-v1")
                self.assertEqual(manifest["platform"], "windows-x64")
                self.assertEqual(manifest["nodeVersion"], "24.22.0")
                self.assertEqual(manifest["sha256"], digest)
                self.assertEqual(manifest["bytes"], archive.stat().st_size)
                with zipfile.ZipFile(archive) as bundle:
                    names = set(bundle.namelist())
                    self.assertIn("CryptoBench-Runner/node.exe", names)
                    self.assertIn("CryptoBench-Runner/cryptobench-runner.cmd", names)
                    self.assertIn("CryptoBench-Runner/src/cli.mjs", names)
                    self.assertIn("CryptoBench-Runner/examples/codex.json", names)
                    self.assertIn("CryptoBench-Runner/NODE-LICENSE.txt", names)
                    metadata = json.loads(
                        bundle.read("CryptoBench-Runner/runner-package.json").decode("utf-8")
                    )
                    self.assertEqual(metadata["sourceCommit"], "a" * 40)
                first_digest = digest
            self.assertEqual(first_digest, digest)


if __name__ == "__main__":
    unittest.main()
