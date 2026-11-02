#!/usr/bin/env python3
"""Wrap a holder's storage key in memory. Output contains ciphertext only."""
import argparse
import base64
import hashlib
import json
import os
import pathlib
import re
import resource
import stat
import subprocess
import sys
import urllib.request


def crc32c(data):
    crc = 0xFFFFFFFF
    for value in data:
        crc ^= value
        for _ in range(8):
            crc = (crc >> 1) ^ (0x82F63B78 if crc & 1 else 0)
    return crc ^ 0xFFFFFFFF


def run(*args, data=None):
    result = subprocess.run(args, input=data, capture_output=True, timeout=45)
    if result.returncode:
        raise RuntimeError("credential or public certificate command failed")
    return result.stdout


def wrap(key_name, party, key):
    if not re.fullmatch(r"projects/[a-z0-9-]+/locations/[a-z0-9-]+/keyRings/[A-Za-z0-9_-]+/cryptoKeys/[A-Za-z0-9_-]+", key_name):
        raise ValueError("invalid KMS key resource")
    if not re.fullmatch(r"[0-9a-f]{64}", party) or len(key) != 32:
        raise ValueError("holder SPKI and 32-byte key are required")
    aad = ("SW-MPC-STORAGE-KEY-V1\n" + party).encode()
    token = run("gcloud", "auth", "print-access-token", "--quiet").decode().strip()
    request = urllib.request.Request(
        "https://cloudkms.googleapis.com/v1/" + key_name + ":encrypt",
        data=json.dumps({"plaintext": base64.b64encode(key).decode(),
                         "plaintextCrc32c": str(crc32c(key)),
                         "additionalAuthenticatedData": base64.b64encode(aad).decode(),
                         "additionalAuthenticatedDataCrc32c": str(crc32c(aad))}).encode(),
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=20) as response:
        result = json.loads(response.read(16385))
    ciphertext = base64.b64decode(result["ciphertext"], validate=True)
    if (not result.get("verifiedPlaintextCrc32c") or not result.get("verifiedAdditionalAuthenticatedDataCrc32c")
            or str(crc32c(ciphertext)) != str(result["ciphertextCrc32c"])
            or not result["name"].startswith(key_name + "/cryptoKeyVersions/")):
        raise RuntimeError("KMS encryption integrity verification failed")
    return {"kmsKeyName": key_name, "wrappedKey": result["ciphertext"],
            "caBundleFile": "/etc/ssl/certs/ca-certificates.crt"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--key-name", required=True)
    parser.add_argument("--certificate", required=True, type=pathlib.Path,
                        help="public holder certificate; binds ciphertext to its SPKI identity")
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--existing-key-file", type=pathlib.Path,
                        help="one-time wrapping of the ORIGINAL key for an existing store; never replaced")
    args = parser.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    os.umask(0o077)
    if args.output.exists() or args.output.is_symlink():
        raise RuntimeError("refusing to overwrite an existing envelope")
    public = run("openssl", "x509", "-in", str(args.certificate), "-pubkey", "-noout")
    party = hashlib.sha256(run("openssl", "pkey", "-pubin", "-outform", "DER", data=public)).hexdigest()
    if args.existing_key_file:
        fd = os.open(args.existing_key_file, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(fd, "rb") as source:
            info = os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size != 32:
                raise RuntimeError("original key must be an owner-only 32-byte regular file")
            key = source.read(33)
    else:
        # New, empty holders only. Never use this mode to reopen an existing store.
        key = os.urandom(32)
    envelope = wrap(args.key_name, party, key)
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w") as output:
        json.dump(envelope, output, indent=2)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    parent_fd = os.open(args.output.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(parent_fd)
    finally:
        os.close(parent_fd)
    print("Saved KMS ciphertext bound to holder SPKI; no plaintext key was written.")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # Provider responses and exception payloads may contain sensitive material.
        print("Storage key wrapping failed; verify credentials, IAM, paths and KMS integrity. Details suppressed.", file=sys.stderr)
        sys.exit(1)
