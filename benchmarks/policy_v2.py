"""Frozen 18-run policy v2 diagnostic. Build -Pbenchmarks first; Python 3.10+ and JDK 17."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[1]
STRATEGIES = ["FULL", "V2", "FIXED"]
EXPECTED_HASH = "161018c4871f690ca5687ec262efb5eed7a1258b855ad05d26002a38212bd27c"

def dump(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")

def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1048576), b""): h.update(block)
    return h.hexdigest()

def summarize(records):
    groups, comparisons = [], []
    complete = len(records) == 18 and len({r["case"] for r in records}) == 18
    integrity = all(r.get("status") == "COMPLETED" and r.get("inputSha256") == r.get("outputSha256") == EXPECTED_HASH
                    and r.get("sourceLeasedBytesAtEnd") == r.get("targetLeasedBytesAtEnd") == 0 for r in records)
    cycles = all(r.get(d+"TrialsEvaluated", 0) > 0 for r in records if r["strategy"] != "FIXED" for d in ["window", "chunk"])
    compression = all(r.get("compressionTrialsStarted") == 0 for r in records if r["strategy"] == "V2")
    counters = all(r.get(d+"TrialsEvaluated") == r.get(d+"TrialsRetained",0)+r.get(d+"TrialsRolledBack",0)
                   and r.get(d+"TrialsStarted",0) >= r.get(d+"TrialsEvaluated",0)+r.get(d+"TrialsInterrupted",0)
                   for r in records for d in ["window", "compression", "chunk"])
    for scenario in ["sink", "journal"]:
        rows = {s: sorted([r for r in records if r["scenario"] == scenario and r["strategy"] == s],key=lambda r:r["repeat"]) for s in STRATEGIES}
        for strategy, group in rows.items():
            good = [r for r in group if r.get("status") == "COMPLETED"]
            groups.append({"scenario": scenario, "strategy": strategy, "runs": len(group), "successes": len(good),
                           "seconds": [r["completionNanos"]/1e9 for r in good],
                           "secondsMedian": statistics.median(r["completionNanos"]/1e9 for r in good) if good else None,
                           "cpuSecondsMedian": statistics.median(r["cpuNanos"]/1e9 for r in good) if good else None,
                           "retries": [r["retries"] for r in good], "busy": [r["busy"] for r in good],
                           "trials": {d: {k: sum(r[d+"Trials"+k] for r in good) for k in ["Started","Evaluated","Retained","RolledBack","Interrupted"]}
                                      for d in ["window","compression","chunk"]}})
        if all(len(rows[s]) == 3 and all(r.get("status")=="COMPLETED" for r in rows[s]) for s in STRATEGIES):
            for baseline in ["FULL", "FIXED"]:
                a, b = rows[baseline], rows["V2"]
                paired = [100*(x["completionNanos"]-y["completionNanos"])/x["completionNanos"] for x,y in zip(a,b)]
                comparisons.append({"scenario":scenario, "baseline":baseline, "pairedTimeImprovementPercent":paired,
                                    "medianTimeImprovementPercent":100*(1-statistics.median(r["completionNanos"] for r in b)/statistics.median(r["completionNanos"] for r in a)),
                                    "everyPairFaster":all(x>0 for x in paired)})
    return {"completeMatrix": complete, "integrityAndLeases": integrity, "dynamicCycleGate": cycles,
            "v2CompressionFixed": compression, "counterConsistency": counters, "groups": groups, "comparisons": comparisons,
            "diagnosticGate": complete and integrity and cycles and compression and counters}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--summarize", action="store_true", help="only recompute summary from existing records")
    args = parser.parse_args(); out = args.output.resolve()
    if args.summarize:
        dump(out/"summary.json", summarize(json.loads((out/"results.json").read_text(encoding="utf-8"))))
        return
    out.mkdir(parents=True, exist_ok=False)
    java = str(Path(os.environ["JAVA_HOME"])/"bin"/"java")
    jvm = ["-Xms128m","-Xmx768m","-XX:ActiveProcessorCount=4","-Dsun.net.httpserver.maxReqTime=60",
           "-Dsun.net.httpserver.maxRspTime=60","-Djdk.httpserver.maxConnections=32"]
    cases = [(f"{scenario}-{strategy}-r{repeat}",scenario,strategy,repeat)
             for repeat in range(3) for scenario in ["sink","journal"]
             for strategy in STRATEGIES[repeat:]+STRATEGIES[:repeat]]
    input_path = out/"mixed-512MiB.bin"; rng = random.Random(104729)
    with input_path.open("wb") as stream:
        for _ in range(512*8): stream.write(bytes(65536)); stream.write(rng.randbytes(65536))
    if digest(input_path) != EXPECTED_HASH: raise SystemExit("Input differs from approved diagnostic")
    manifest = {"protocol":"policy-v2-diagnostic-1", "cases":cases, "seed":104729, "inputBytes":input_path.stat().st_size,
                "inputSha256":EXPECTED_HASH,"jvmArguments":jvm,"timeoutSeconds":300,
                "sourceCommit":subprocess.check_output(["git","-c",f"safe.directory={ROOT.as_posix()}","rev-parse","HEAD"],cwd=ROOT,text=True).strip(),
                "java":subprocess.check_output([java,"-version"],stderr=subprocess.STDOUT,text=True),
                "sources":{p.relative_to(ROOT).as_posix():digest(p) for parent in [ROOT/"modules",ROOT/"examples/src",ROOT/"benchmarks/src"]
                           for p in sorted(parent.rglob("*.java")) if "target" not in p.parts},
                "runnerSha256":digest(Path(__file__)),
                "interpretation":"Paired repetitions and medians are descriptive. Only consistent paired gains support follow-up; no tuning or production performance claim from three repetitions."}
    dump(out/"manifest.json",manifest)
    results=[]
    for name,scenario,strategy,repeat in cases:
        fixed = strategy == "FIXED"
        cmd=[java]+jvm+["-cp",os.pathsep.join(map(str,[ROOT/"benchmarks/target/classes",ROOT/"benchmarks/target/lib/*"])),
              "io.github.aullchen.lcp.examples.BenchmarkRun",str(input_path),str(out/name),strategy,scenario,"train",
              "1048576" if fixed else "262144","4" if fixed else "1","3","4096"]
        if not fixed: cmd.append("control-cycles")
        print("START", name, flush=True)
        with (out/(name+".log")).open("w",encoding="utf-8") as log:
            try:
                run=subprocess.run(cmd,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=300)
                path=out/name/"result.json"
                record=json.loads(path.read_text(encoding="utf-8")) if path.exists() else {"status":"PROCESS_FAILED"}
                if run.returncode: record.update(status="PROCESS_FAILED",exitCode=run.returncode)
            except subprocess.TimeoutExpired: record={"status":"TIMEOUT"}
        record.update(case=name,scenario=scenario,strategy=strategy,repeat=repeat)
        results.append(record); dump(out/"results.json",results)
        print("END",name,record["status"],record.get("completionNanos",0)/1e9,flush=True)
        if (record["status"]!="COMPLETED" or record["inputSha256"]!=record["outputSha256"]
                or record["sourceLeasedBytesAtEnd"] or record["targetLeasedBytesAtEnd"]):
            dump(out/"summary.json",summarize(results)); raise SystemExit("Invariant/process failure retained; stopped")
    summary=summarize(results); dump(out/"summary.json",summary)
    if not summary["diagnosticGate"]: raise SystemExit("Diagnostic gate failed; do not extend input or tune rules")
    print("18 runs complete; inspect paired comparisons before claiming effectiveness",flush=True)

if __name__ == "__main__": main()
