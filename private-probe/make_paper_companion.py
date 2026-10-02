"""Freeze a byte-distinct Paper-only JAR without changing ByteBans runtime entries.

Modrinth rejects a second version carrying an already uploaded file hash. A
manifest marker makes the Paper 26.1.1 version distinct while retaining every
class and resource from the separately tested 1.1.0 candidate.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import zipfile


HERE = Path(__file__).resolve().parent
BASE = HERE / "frozen/release/ByteBans-1.1.0.jar"
COMPANION = HERE / "frozen/release/ByteBans-1.1.0-paper-26.1.1.jar"
ATTESTATION = HERE / "frozen/release/paper-companion-attestation.json"
MANIFEST = "META-INF/MANIFEST.MF"
MARKER = b"X-ByteBans-Release-Variant: paper-only-26.1.1\r\n"


def sha512(path: Path) -> str:
    digest = hashlib.sha512()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def expected_manifest(original: bytes) -> bytes:
    if not original.endswith(b"\r\n\r\n") or MARKER in original:
        raise ValueError("Unexpected original JAR manifest")
    return original[:-2] + MARKER + b"\r\n"


def verify_runtime(base: Path, companion: Path) -> int:
    with zipfile.ZipFile(base) as source, zipfile.ZipFile(companion) as other:
        names = source.namelist()
        if names != other.namelist() or len(names) != len(set(names)) or names.count(MANIFEST) != 1:
            raise ValueError("Companion JAR entry list differs from the frozen candidate")
        if other.read(MANIFEST) != expected_manifest(source.read(MANIFEST)):
            raise ValueError("Companion JAR manifest marker differs")
        if any(source.read(name) != other.read(name) for name in names if name != MANIFEST):
            raise ValueError("Companion JAR changes a runtime entry")
        return len(names)


def make() -> dict:
    if BASE.is_symlink() or not BASE.is_file() or COMPANION.is_symlink() or ATTESTATION.is_symlink():
        raise ValueError("Frozen artifact path is missing or symbolic")
    frozen = json.loads((HERE / "frozen/release/manifest.json").read_text(encoding="utf-8"))
    if sha512(BASE) != frozen["sha512"]:
        raise ValueError("Frozen ByteBans 1.1.0 JAR changed")
    with zipfile.ZipFile(BASE) as source, zipfile.ZipFile(COMPANION, "w") as output:
        for info in source.infolist():
            content = source.read(info.filename)
            if info.filename == MANIFEST:
                content = expected_manifest(content)
            output.writestr(info, content)
    count = verify_runtime(BASE, COMPANION)
    proof = {
        "source_revision": frozen["source_revision"],
        "primary_sha512": frozen["sha512"],
        "companion_sha512": sha512(COMPANION),
        "entry_count": count,
        "changed_entries": [MANIFEST],
        "runtime_entries_equal": True,
        "manifest_marker": MARKER.decode("ascii").strip(),
    }
    if proof["primary_sha512"] == proof["companion_sha512"]:
        raise ValueError("Companion JAR is not byte-distinct")
    ATTESTATION.write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
    return proof


if __name__ == "__main__":
    print(json.dumps(make(), indent=2))
