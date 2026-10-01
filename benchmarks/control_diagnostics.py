"""Control-cycle and pressure diagnostics, separate from the frozen performance matrix.
Run after mvnw -Pbenchmarks package. Python 3.10+, JDK 17; standard library only.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import subprocess
import statistics

ROOT = Path(__file__).resolve().parents[1]
MIB = 1024 * 1024

def dump(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

def summarize(results):
    cycles = [r for r in results if r["case"].startswith("cycles-")]
    counts = {d: {key: sum(r[d+"Trials"+key] for r in cycles)
                  for key in ["Started", "Evaluated", "Retained", "RolledBack", "Interrupted"]}
              for d in ["window", "compression", "chunk"]}
    pressure = []
    for scenario in ["stable", "sink", "journal"]:
        for window in [1, 2, 4]:
            rows = [r for r in results if r["case"].startswith(f"pressure-{scenario}-w{window}-")]
            pressure.append({"scenario": scenario, "window": window, "runs": len(rows),
                             "secondsMedian": statistics.median(r["completionNanos"]/1e9 for r in rows),
                             "ackP95MillisMedian": statistics.median(r["ackP95Nanos"]/1e6 for r in rows),
                             "queueShareMedian": statistics.median(r["queueShare"] for r in rows),
                             "persistShareMedian": statistics.median(r["persistShare"] for r in rows),
                             "busy": [r["busy"] for r in rows]})
    consistent = all(r[d+"TrialsEvaluated"] == r[d+"TrialsRetained"]+r[d+"TrialsRolledBack"]
                     and r[d+"TrialsStarted"] >= r[d+"TrialsEvaluated"]+r[d+"TrialsInterrupted"]
                     for r in cycles for d in counts)
    complete = len(results) == 30 and len(cycles) == 3 and consistent and all(
        r[d+"TrialsEvaluated"] > 0 for r in cycles for d in counts)
    return {"controlCycleGate": complete, "cycleTrials": counts, "pressure": pressure}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="new output directory (never overwritten)")
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java")
    commands = [java, "-Xms128m", "-Xmx768m", "-XX:ActiveProcessorCount=4",
                "-Dsun.net.httpserver.maxReqTime=60", "-Dsun.net.httpserver.maxRspTime=60",
                "-Djdk.httpserver.maxConnections=32", "-cp",
                os.pathsep.join(map(str, [ROOT/"benchmarks/target/classes", ROOT/"benchmarks/target/lib/*"])),
                "io.github.aullchen.lcp.examples.BenchmarkRun"]
    manifest = {"protocol": "control-diagnostics-v1", "seed": 104729,
                "java": subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT, text=True),
                "sources": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                            for parent in [ROOT/"modules", ROOT/"benchmarks/src"]
                            for p in sorted(parent.rglob("*.java")) if "target" not in p.parts},
                "cycleProfile": {"chunkBounds": [262144, 1048576], "windowBounds": [1, 4],
                                 "levelBounds": [1, 5], "initial": [262144, 1, 3]},
                "inputs": {}}
    # Alternate zero and seeded entropy blocks: compressible but not a zero-only shortcut.
    for name, mib in [("pressure", 16), ("cycles", 512)]:
        path = out / (name + ".bin")
        rng = random.Random(104729)
        with path.open("wb") as stream:
            for _ in range(mib * 8):
                stream.write(bytes(65536)); stream.write(rng.randbytes(65536))
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(MIB), b""): digest.update(block)
        manifest["inputs"][name] = {"bytes": path.stat().st_size, "sha256": digest.hexdigest()}
    # Freeze order before execution; rotate windows to reduce order bias.
    cases = []
    for repeat in range(3):
        for scenario in ["stable", "sink", "journal"]:
            windows = [1, 2, 4]
            for window in windows[repeat:] + windows[:repeat]:
                cases.append((f"pressure-{scenario}-w{window}-r{repeat}", "pressure", "B0", scenario, window, False))
        cases.append((f"cycles-r{repeat}", "cycles", "FULL", "sink", 1, True))
    manifest["cases"] = cases
    dump(out/"manifest.json", manifest)
    results = []
    for name, source, strategy, scenario, window, cycles in cases:
        command = commands + [str(out/(source+".bin")), str(out/name), strategy, scenario, "train",
                              "262144", str(window), "3", "4096"]
        if cycles: command.append("control-cycles")
        with (out/(name+".log")).open("w", encoding="utf-8") as log:
            try:
                run = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=300)
                record = json.loads((out/name/"result.json").read_text(encoding="utf-8")) if (out/name/"result.json").exists() else {"status": "PROCESS_FAILED", "exitCode": run.returncode}
            except subprocess.TimeoutExpired:
                record = {"status": "TIMEOUT"}
        record["case"] = name
        results.append(record)
        dump(out/"results.json", results)
        print(name, record["status"], flush=True)
        if record["status"] != "COMPLETED": raise SystemExit("Failure retained; diagnostic stopped")
        if record["inputSha256"] != record["outputSha256"] or record["sourceLeasedBytesAtEnd"] or record["targetLeasedBytesAtEnd"]:
            raise SystemExit("Integrity/lease invariant violated; diagnostic stopped")
    summary = summarize(results)
    dump(out/"summary.json", summary)
    if not summary["controlCycleGate"]:
        raise SystemExit("Control-cycle gate failed; retain all results, do not claim joint control verified")
    print("Completed 30 diagnostic runs with complete cycles in all dimensions; not performance acceptance.", flush=True)

if __name__ == "__main__":
    main()
