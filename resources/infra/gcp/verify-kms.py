#!/usr/bin/env python3
"""Read-only IAM checks and synthetic live KMS acceptance. No wallet material."""
import argparse
import copy
import importlib.util
import json
import pathlib
import resource
import subprocess
import sys
import urllib.parse
import urllib.request


def gcloud(*args):
    result = subprocess.run(["gcloud", *args, "--quiet"], text=True, capture_output=True, timeout=45)
    if result.returncode:
        raise RuntimeError("GCP read or credential check failed")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--binary", required=True, type=pathlib.Path)
    parser.add_argument("--ca-bundle", required=True)
    parser.add_argument("--location", default="us-central1")
    parser.add_argument("--keyring", default="surprising-wallet-mpc")
    args = parser.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("wallet_kms_wrap", pathlib.Path(__file__).with_name("wrap-storage-key.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    project_policy = json.loads(gcloud("projects", "get-iam-policy", args.project, "--format=json"))
    for holder in (1, 2, 3):
        account = f"serviceAccount:wallet-mpc-holder-{holder}@{args.project}.iam.gserviceaccount.com"
        if any(account in binding.get("members", []) for binding in project_policy.get("bindings", [])):
            raise RuntimeError("holder has unexpected project-wide IAM grants")
        key = f"holder-{holder}-storage"
        policy = json.loads(gcloud("kms", "keys", "get-iam-policy", key, "--keyring=" + args.keyring,
                                   "--location=" + args.location, "--project=" + args.project, "--format=json"))
        bindings = policy.get("bindings", [])
        if bindings != [{"role": "roles/cloudkms.cryptoKeyDecrypter", "members": [account]}]:
            raise RuntimeError("unexpected holder key IAM policy")
        name = f"projects/{args.project}/locations/{args.location}/keyRings/{args.keyring}/cryptoKeys/{key}"
        config = {"storageKey": module.wrap(name, "a" * 64, bytes([holder]) * 32)}
        config["storageKey"]["caBundleFile"] = args.ca_bundle
        # macOS may use an OS-level loopback HTTP proxy absent from env vars.
        # Only the non-installed acceptance binary supports this transport seam.
        proxy = urllib.parse.urlparse(urllib.request.getproxies().get("https", ""))
        if proxy.scheme == "http" and proxy.hostname in ("127.0.0.1", "localhost") and proxy.port:
            config["testHttpsProxyPort"] = proxy.port
        token = gcloud("auth", "print-access-token").strip()
        data = {"accessToken": token, "config": config, "party": "a" * 64}

        def check(value, should_pass):
            result = subprocess.run([str(args.binary.resolve()), "--live"], input=json.dumps(value),
                                    text=True, capture_output=True, timeout=45)
            if (result.returncode == 0) != should_pass:
                raise RuntimeError("live KMS result differed from expected; payload suppressed")

        check(data, True)
        changed = copy.deepcopy(data)
        changed["party"] = "b" * 64
        check(changed, False)
        changed = copy.deepcopy(data)
        changed["config"]["storageKey"]["kmsKeyName"] = name.replace(f"holder-{holder}-", f"holder-{holder % 3 + 1}-")
        check(changed, False)
        changed = copy.deepcopy(data)
        changed["accessToken"] = "invalid-acceptance-token"
        check(changed, False)
        print(f"PASS holder-{holder}: isolated key IAM, live unwrap/reopen, wrong SPKI/key/token rejected", flush=True)
    print("PASS synthetic live KMS acceptance; existing wallet material untouched", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        raise SystemExit("KMS acceptance failed; credentials and provider payloads suppressed")
