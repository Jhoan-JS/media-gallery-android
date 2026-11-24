"""Check tracked publication files without printing potential secret values."""
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN_PARTS = {
    ".qa", ".gradle", "build", "dist", "desktop", "private", "evidence",
    "__pycache__", "node_modules",
}
FORBIDDEN_SUFFIXES = {
    ".apk", ".aab", ".aar", ".jar", ".class", ".dex", ".so", ".dll", ".exe",
    ".onnx", ".safetensors", ".f32", ".bin", ".jks", ".keystore", ".pem",
    ".p12", ".key", ".log", ".db", ".sqlite", ".zip", ".mp4", ".jpg", ".png",
}
SECRET_PATTERNS = {
    "private-key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "github-token": re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    "aws-key": re.compile(r"\bAKIA[A-Z0-9]{16}\b"),
    "google-key": re.compile(r"\bAIza[A-Za-z0-9_-]{30,}\b"),
    "openai-key": re.compile(r"\bsk-(?:proj-)?[A-Za-z0-9_-]{32,}\b"),
    "bot-token": re.compile(r"\b[1-9][0-9]{7,12}:[A-Za-z0-9_-]{30,}\b"),
    "personal-path": re.compile(r"[A-Za-z]:[/\\]Users[/\\]"),
}


def main():
    result = subprocess.run(
        ["git", "-C", str(ROOT), "ls-files", "-z"],
        check=True, capture_output=True,
    )
    paths = [Path(p) for p in result.stdout.decode().split("\0") if p]
    failures = []
    for relative in paths:
        display = relative.as_posix()
        if FORBIDDEN_PARTS.intersection(relative.parts) or relative.suffix.lower() in FORBIDDEN_SUFFIXES:
            failures.append({"path": display, "check": "excluded-artifact"})
            continue
        path = ROOT / relative
        if path.is_symlink():
            failures.append({"path": display, "check": "symlink"})
            continue
        data = path.read_bytes()
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            failures.append({"path": display, "check": "non-text-content"})
            continue
        if "\0" in text:
            failures.append({"path": display, "check": "binary-content"})
        for name, pattern in SECRET_PATTERNS.items():
            for match in pattern.finditer(text):
                failures.append({
                    "path": display, "check": name,
                    "line": text.count("\n", 0, match.start()) + 1,
                })
    print(json.dumps({"tracked_files": len(paths), "findings": failures}, indent=2))
    if not paths or failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
