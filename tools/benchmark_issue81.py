"""Compare isolated baseline/current debug applications without touching the user's library.

Run from the repository root with Python, adb, Java, and the Android SDK available.
Build logs and raw metrics stay in the ignored plan workspace. UI polling adds
instrumentation overhead; content-readiness and search timings are upper bounds.
"""
from pathlib import Path
import os
import io
import json
import re
import shutil
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / ".superpowers/sdd/issue81-continuation/benchmark"
BASE = "56d9a35"
PACKAGE = "me.bubblespark.tidilo"


def run(args, *, cwd=ROOT, timeout=120):
    result = subprocess.run(args, cwd=cwd, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"{args}: {result.stderr.decode('utf-8', errors='replace')}")
    return result.stdout.decode("utf-8", errors="replace")


def adb(*args):
    return run(["adb", *args])


def gradle_command(target, platform=None):
    return [str(target / "gradlew.bat")] if (platform or os.name) == "nt" else ["sh", str(target / "gradlew")]


def build(label):
    target = WORK / label
    target.mkdir(parents=True, exist_ok=True)
    if label == "baseline":
        archive = subprocess.check_output(["git", "archive", "--format=zip", BASE], cwd=ROOT, timeout=120)
        with zipfile.ZipFile(io.BytesIO(archive)) as source:
            for entry in source.infolist():
                resolved = (target / entry.filename).resolve()
                if not resolved.is_relative_to(target.resolve()):
                    raise RuntimeError("Invalid archive path")
            source.extractall(target)
    else:
        paths = run(["git", "ls-files"]).splitlines() + run(["git", "ls-files", "--others", "--exclude-standard"]).splitlines()
        for name in paths:
            if name.startswith(("app/src/", "gradle/")) or name in (
                "gradlew", "gradlew.bat", "gradle.properties", "settings.gradle.kts", "build.gradle.kts", "app/build.gradle.kts"
            ):
                output = target / name
                output.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / name, output)
    gradle = target / "app/build.gradle.kts"
    source = gradle.read_text(encoding="utf-8")
    source = source.replace('applicationIdSuffix = ".debug"', f'applicationIdSuffix = ".benchmark{label}"')
    gradle.write_text(source, encoding="utf-8")
    print(f"Building {label}", flush=True)
    with (WORK / f"build-{label}.log").open("w", encoding="utf-8") as log:
        result = subprocess.run([*gradle_command(target), "assembleDebug", "--no-daemon"], cwd=target, stdout=log, stderr=log, timeout=900)
    if result.returncode:
        raise RuntimeError(f"Build failed: {label}; inspect build-{label}.log")
    adb("install", "-r", str(target / "app/build/outputs/apk/debug/app-debug.apk"))
    return PACKAGE + ".benchmark" + label


def fixture(count):
    path = WORK / f"fixture-{count}-{time.time_ns()}.db"
    connection = sqlite3.connect(path)
    connection.executescript((ROOT / "app/src/androidTest/assets/schema-v6.sql").read_text(encoding="utf-8"))
    connection.execute("PRAGMA user_version=6")
    for index in range(count):
        title = f"Bookmark {index:06}" + (" needle42" if index == 42 else "")
        connection.execute("""INSERT INTO bookmarks(id,title,url,description,category,subcategory,entities,
            createdAt,updatedAt,note,isNsfw,isAiSkipped) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
            (f"{index:08}", title, f"https://fixture{index % 20}.invalid/{index}", "Synthetic description " * 20,
             "Article", "Tech", '["Kotlin","Android"]', count - index, count - index, "", 0, 0))
    connection.commit()
    connection.close()
    return path


def seed(package, path):
    # Only packages built by this script are reset; never clear the normal debug/release application.
    if package not in (PACKAGE + ".benchmarkbaseline", PACKAGE + ".benchmarkpaging"):
        raise RuntimeError("Refusing to reset a non-benchmark package")
    adb("shell", "am", "force-stop", package)
    adb("shell", "pm", "clear", package)
    adb("push", str(path), "/data/local/tmp/issue81-fixture.db")
    adb("shell", "run-as", package, "mkdir", "-p", "databases", "cache/domain_icons")
    adb("shell", "run-as", package, "cp", "/data/local/tmp/issue81-fixture.db", "databases/tidilo_database.db")
    for index in range(20):
        adb("shell", "run-as", package, "touch", f"cache/domain_icons/fixture{index}.invalid.none")


def nodes():
    adb("shell", "uiautomator", "dump", "/data/local/tmp/issue81-ui.xml")
    xml = adb("shell", "cat", "/data/local/tmp/issue81-ui.xml")
    return list(ET.fromstring(xml[xml.index("<hierarchy"):]).iter("node"))


def wait_for(predicate, timeout=35):
    start = time.perf_counter()
    while time.perf_counter() - start < timeout:
        current = nodes()
        if any(predicate(node) for node in current):
            return (time.perf_counter() - start) * 1000, current
    raise TimeoutError("UI content did not become ready")


def tap(node):
    left, top, right, bottom = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def measure(package, count, path):
    seed(package, path)
    samples = []
    for sample in range(3):
        print(f"Measuring {package} count={count} cold-start={sample + 1}", flush=True)
        adb("shell", "am", "force-stop", package)
        start = time.perf_counter()
        launch = adb("shell", "am", "start", "-W", "-n", package + "/" + PACKAGE + ".MainActivity")
        _, tree = wait_for(lambda node: node.attrib.get("text", "").startswith("Bookmark "))
        ready = (time.perf_counter() - start) * 1000
        memory = adb("shell", "dumpsys", "meminfo", package)
        ttid = re.search(r"TotalTime:\s*(\d+)", launch)
        pss = re.search(r"TOTAL PSS:\s*(\d+)", memory)
        if not pss:
            pss = re.search(r"^\s*TOTAL\s+(\d+)", memory, re.MULTILINE)
        samples.append({"ttid_ms": int(ttid.group(1)) if ttid else None,
                        "content_ready_upper_ms": round(ready), "pss_kb": int(pss.group(1)) if pss else None})
    # Exercise the real search screen; look for the result title, not the query text.
    search = next(node for node in tree if node.attrib.get("text") in ("Search", "検索") or node.attrib.get("content-desc") in ("Search", "検索"))
    tap(search)
    _, tree = wait_for(lambda node: node.attrib.get("class") == "android.widget.EditText")
    edit = next(node for node in tree if node.attrib.get("class") == "android.widget.EditText")
    tap(edit)
    start = time.perf_counter()
    adb("shell", "input", "text", "needle42")
    search_ms, _ = wait_for(lambda node: "Bookmark 000042" in node.attrib.get("text", ""))
    search_ms += (time.perf_counter() - start) * 1000 - search_ms
    adb("shell", "am", "force-stop", package)
    adb("shell", "am", "start", "-W", "-n", package + "/" + PACKAGE + ".MainActivity")
    wait_for(lambda node: node.attrib.get("text", "").startswith("Bookmark "))
    adb("shell", "dumpsys", "gfxinfo", package, "reset")
    for _ in range(12):
        adb("shell", "input", "swipe", "540", "1700", "540", "500", "300")
    gfx = adb("shell", "dumpsys", "gfxinfo", package)
    memory = adb("shell", "dumpsys", "meminfo", package)
    (WORK / f"gfx-{package}-{count}.txt").write_text(gfx, encoding="utf-8")
    (WORK / f"memory-{package}-{count}.txt").write_text(memory, encoding="utf-8")
    return {"package": package, "count": count, "startup": samples, "search_upper_ms": round(search_ms),
            "scroll_metrics": [line.strip() for line in gfx.splitlines() if any(key in line for key in ("Total frames", "Janky frames", "50th percentile", "90th percentile", "95th percentile"))]}


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    results = []
    for label in ("baseline", "paging"):
        package = build(label)
        for count in (1000, 10000):
            results.append(measure(package, count, fixture(count)))
            (WORK / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(json.dumps(results, indent=2), flush=True)


if __name__ == "__main__":
    try:
        main()
    except subprocess.TimeoutExpired as error:
        raise SystemExit(f"Benchmark failed: process timed out after {error.timeout}s: {error.cmd}") from error
