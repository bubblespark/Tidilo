"""Check reflective ML Kit registrar constructors in an already minified APK."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=Path(__file__).resolve().parents[1]
                        / "app/build/outputs/apk/release/app-release.apk")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT", "")
    executable = "apkanalyzer.bat" if os.name == "nt" else "apkanalyzer"
    parser.add_argument("--apkanalyzer", type=Path,
                        default=Path(sdk) / "cmdline-tools/latest/bin" / executable)
    args = parser.parse_args()

    def analyze(*command: str) -> str:
        result = subprocess.run([str(args.apkanalyzer), *command, str(args.apk)],
                                check=True, capture_output=True, text=True, encoding="utf-8")
        return result.stdout

    manifest = ET.fromstring(analyze("manifest", "print"))
    android = "{http://schemas.android.com/apk/res/android}"
    registrars = []
    for service in manifest.findall("./application/service"):
        if service.get(android + "name") != "com.google.mlkit.common.internal.MlKitComponentDiscoveryService":
            continue
        for metadata in service.findall("meta-data"):
            name = metadata.get(android + "name", "")
            prefix = "com.google.firebase.components:"
            if name.startswith(prefix) and metadata.get(android + "value") == "com.google.firebase.components.ComponentRegistrar":
                registrars.append(name[len(prefix):])
    if not registrars:
        raise RuntimeError("No ML Kit component registrars found in the release manifest")
    for registrar in registrars:
        code = analyze("dex", "code", "--class", registrar)
        if not re.search(r"(?m)^\.method public constructor <init>\(\)V\s*$", code):
            raise RuntimeError(f"Missing reflective public no-argument constructor: {registrar}")
        print(f"PASS: {registrar} retains its public no-argument constructor")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, subprocess.CalledProcessError, ET.ParseError, RuntimeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
