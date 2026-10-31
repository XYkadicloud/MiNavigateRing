#!/usr/bin/env python3
"""Upload the CI signing material as encrypted repository secrets.

The four files produced by `scripts/gen-debug-signing.py` (or downloaded from the
bootstrap `signing-material` artifact) are base64-encoded and pushed to the repository
via the GitHub Actions secrets API. Encryption uses the repo public key (libsodium
sealed box), so the plaintext never leaves this machine in a readable form.

Why this exists: every CI run used to generate a fresh keystore, so the APK signature
changed on every build. Mi Fitness records the calling app's certificate the first time
the user grants DEVICE_MANAGER, then compares it on every send — a rotated certificate
therefore produced `fingerPrintVerify failed` and every test message died with
"通道任务失败". Persisting one keystore makes the fingerprint stable.

Usage:
    python scripts/upload-signing-secrets.py <owner/repo> <signing-dir> <token>
"""

import base64
import json
import sys
import urllib.request

import nacl.public
import nacl.encoding

# Secret name -> file name inside the signing directory.
FILES = {
    "SIGNING_KEYSTORE_B64": "debug.jks",
    "SIGNING_PROPERTIES_B64": "signing.local.properties",
    "SIGNING_PRIVATE_PEM_B64": "private.pem",
    "SIGNING_CERT_PEM_B64": "certificate.pem",
}

API = "https://api.github.com"


def request(url, token, method="GET", payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req) as resp:
        body = resp.read()
        return json.loads(body) if body else {}


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    repo, signing_dir, token = sys.argv[1], sys.argv[2], sys.argv[3]

    key = request(f"{API}/repos/{repo}/actions/secrets/public-key", token)
    sealed_box = nacl.public.SealedBox(
        nacl.public.PublicKey(key["key"].encode(), nacl.encoding.Base64Encoder())
    )

    for name, filename in FILES.items():
        path = f"{signing_dir}/{filename}"
        with open(path, "rb") as fh:
            raw = fh.read()
        encoded = base64.b64encode(raw)
        encrypted = base64.b64encode(sealed_box.encrypt(encoded)).decode()
        request(
            f"{API}/repos/{repo}/actions/secrets/{name}",
            token,
            method="PUT",
            payload={"encrypted_value": encrypted, "key_id": key["key_id"]},
        )
        print(f"uploaded {name} ({len(raw)} bytes -> {len(encoded)} b64)")

    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
