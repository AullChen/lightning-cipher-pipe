"""Run opt-in storage/index boundary checks in fresh, heap-limited JVMs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
from run import ROOT, OUT, Memory, dump


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUT / "index-scale")
    parser.add_argument("--slots", type=int, nargs="+", default=[65536, 1000000])
    args = parser.parse_args()
    if any(n < 2 or n > 1000000 for n in args.slots) or len(set(args.slots)) != len(args.slots):
        parser.error("Use distinct slot counts in 2..1000000")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
    classpath = os.pathsep.join([str(ROOT / "benchmarks/target/classes"), str(ROOT / "benchmarks/target/lib/*")])
    flags = ["-Xms64m", "-Xmx256m", "-XX:NativeMemoryTracking=summary", "-XX:+UnlockDiagnosticVMOptions", "-XX:+PrintNMTStatistics"]
    revision = subprocess.check_output(["git", "-c", f"safe.directory={ROOT.as_posix()}", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    sources = ["benchmarks/index_scale.py", "benchmarks/run.py",
               "benchmarks/src/main/java/io/github/aullchen/lcp/examples/IndexScaleRun.java"]
    evidence = {"baseRevision": revision, "harnessSha256": {p: hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in sources},
                "jvmFlags": flags, "rssIntervalMillis": 50, "runs": []}
    for slots in args.slots:
        folder = output / str(slots)
        log = output / f"{slots}.log"
        started = time.monotonic()
        peak = samples = 0
        timed_out = False
        with log.open("w", encoding="utf-8") as stream:
            proc = subprocess.Popen([str(java), *flags, "-cp", classpath,
                "io.github.aullchen.lcp.examples.IndexScaleRun", str(folder), str(slots)], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
            memory = Memory(proc.pid)
            try:
                while proc.poll() is None:
                    rss, high = memory.sample()
                    if rss is not None:
                        peak = max(peak, rss, high)
                        samples += 1
                    if time.monotonic() - started > 600:
                        proc.kill()
                        timed_out = True
                        break
                    time.sleep(.05)
                proc.wait()
            finally:
                memory.close()
        result_file = folder / "result.json"
        result = json.loads(result_file.read_text()) if result_file.exists() else {"status": "FAIL", "slots": slots}
        if proc.returncode != 0 or timed_out:
            result["status"] = "FAIL"
        text = log.read_text(encoding="utf-8", errors="replace")
        total = re.search(r"Total: reserved=(\d+), committed=(\d+)", text)
        heap = re.search(r"Java Heap \(reserved=(\d+), committed=(\d+)\)", text)
        result.update({"exitCode": proc.returncode, "timedOut": timed_out, "peakRssBytes": peak or None, "rssSamples": samples,
                       "nmtNativeCommittedAtExit": int(total[2])-int(heap[2]) if total and heap else None,
                       "wallSeconds": time.monotonic()-started})
        evidence["runs"].append(result)
        dump(output / "evidence.json", evidence)
        print(slots, result["status"], "peak RSS", peak, flush=True)
        if result["status"] != "PASS":
            raise SystemExit("Index check failed; preserve logs and inspect before any rerun")
        if not peak or result["nmtNativeCommittedAtExit"] is None:
            raise SystemExit("Missing required memory evidence")


if __name__ == "__main__":
    main()
