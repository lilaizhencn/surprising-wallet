#!/usr/bin/env python3
"""Check the installed node rejects fixture/plaintext key inputs before listening."""
import json
import os
import pathlib
import subprocess
import sys
import tempfile

from node_test import Cluster, require


os.umask(0o077)
binary = pathlib.Path(sys.argv[1])
with tempfile.TemporaryDirectory(prefix="sw-kms-startup-") as directory:
    root = pathlib.Path(directory)
    cluster = Cluster(root, binary)
    try:
        cluster.close()
        config = json.loads((root / "node0.json").read_text())
        secret = config["testStorageKeyHex"]
        for case in ("fixture", "plaintext", "invalid-kms"):
            value = dict(config)
            if case == "plaintext":
                value.pop("testStorageKeyHex")
                value["encryptionKeyFile"] = "/must-never-be-opened/storage.key"
            if case == "invalid-kms":
                value.pop("testStorageKeyHex")
                value["storageKey"] = {"kmsKeyName": "https://untrusted.invalid/key", "wrappedKey": "AQIDBA=="}
            path = root / (case + ".json")
            path.write_text(json.dumps(value))
            result = subprocess.run([str(binary), str(path)], text=True, capture_output=True, timeout=10)
            require(result.returncode == 1, "production node accepted a forbidden key source")
            require(secret not in result.stdout + result.stderr, "startup diagnostic leaked a storage key")
        print("PASS production binary rejects fixture/plaintext keys and untrusted KMS resource; no secret diagnostics")
    finally:
        cluster.close()
