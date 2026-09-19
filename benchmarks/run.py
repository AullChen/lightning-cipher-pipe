"""Reproducible, serial benchmark runner. Python 3.10+, JDK 17; no third-party Python packages."""
import argparse
import ctypes
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import random
import re
import shutil
import statistics
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "benchmarks" / "results"
MIB = 1024 * 1024
STRATEGIES = ["B0", "B1", "B2", "WC", "FULL"]
CANDIDATES = [(4194304,4,3), (1048576,4,1), (1048576,4,3), (4194304,1,3), (8388608,4,1), (8388608,4,3)]
# Declared before evaluation: no per-evaluation-case parameter selection.
CASES = [("stable", "text", "long"), ("stable", "mixed", "long"), ("stable", "entropy", "long"),
         ("step", "mixed", "long"), ("rtt", "text", "long"), ("sink", "mixed", "long"),
         ("outage", "mixed", "long"), ("stable", "text", "short"), ("stable", "mixed", "short"),
         ("stable", "entropy", "short"), ("stable", "public", "short")]

def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")

def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(MIB), b""): h.update(block)
    return h.hexdigest()

def prepare(long_mib):
    directory = OUT / "inputs"
    if directory.exists(): raise SystemExit("Inputs already exist; retain their manifest or use a new results directory")
    directory.mkdir(parents=True)
    manifest = {"generator": "CPython-random-MT19937-randbytes-v1", "python": platform.python_version(), "longMiB": long_mib, "files": {}}
    text = (b"LightningCipherPipe durable authenticated data exchange. Fixed seed benchmark record.\n" * 1024)
    for split, seed in [("train", 773), ("eval", 991)]:
        for kind in ["text", "mixed", "entropy"]:
            for size, length in [("short", MIB), ("long", (min(128,long_mib) if split=="train" else long_mib)*MIB)]:
                name = f"{split}-{kind}-{size}.bin"; path = directory / name
                rng = random.Random(seed); remaining = length; count = 0
                with path.open("wb") as stream:
                    while remaining:
                        n = min(65536, remaining)
                        block = text[:n] if kind=="text" or kind=="mixed" and count%2==0 else rng.randbytes(n)
                        stream.write(block); remaining -= n; count += 1
                manifest["files"][name] = {"bytes":length,"sha256":digest(path),"seed":seed}
    public = directory / "eval-public-short.bin"
    sources = []
    with public.open("wb") as destination:
        for number in [8446,9110,9111,9112]:
            url = f"https://www.rfc-editor.org/rfc/rfc{number}.txt"
            with urllib.request.urlopen(url,timeout=30) as response: data=response.read(4*MIB+1)
            if len(data)>4*MIB: raise ValueError("Public input exceeded bound")
            destination.write(data)
            sources.append({"url":url,"bytes":len(data),"sha256":hashlib.sha256(data).hexdigest()})
    manifest["files"][public.name] = {"bytes":public.stat().st_size,"sha256":digest(public),"sources":sources}
    dump(directory/"manifest.json", manifest)
    print("Prepared",len(manifest["files"]),"inputs",flush=True)

class Memory:
    def __init__(self, pid):
        self.pid=pid; self.handle=None
        if os.name=="nt":
            from ctypes import wintypes as w
            class Counters(ctypes.Structure):
                _fields_=[("cb",w.DWORD),("faults",w.DWORD)]+[(name,ctypes.c_size_t) for name in
                  ["peak","rss","peakPaged","paged","peakNonPaged","nonPaged","pagefile","peakPagefile","private"]]
            self.counters=Counters(); self.counters.cb=ctypes.sizeof(Counters)
            self.kernel=ctypes.WinDLL("kernel32",use_last_error=True); self.psapi=ctypes.WinDLL("psapi")
            self.kernel.OpenProcess.restype=w.HANDLE; self.kernel.OpenProcess.argtypes=[w.DWORD,w.BOOL,w.DWORD]
            self.kernel.CloseHandle.argtypes=[w.HANDLE]
            self.psapi.GetProcessMemoryInfo.argtypes=[w.HANDLE,ctypes.POINTER(Counters),w.DWORD]
            self.handle=self.kernel.OpenProcess(0x410,False,pid)
    def sample(self):
        if os.name=="nt":
            if self.handle and self.psapi.GetProcessMemoryInfo(self.handle,ctypes.byref(self.counters),self.counters.cb):
                return int(self.counters.rss), int(self.counters.peak)
        else:
            try:
                data=Path(f"/proc/{self.pid}/status").read_text()
                return int(re.search(r"VmRSS:\s+(\d+)",data)[1])*1024, int(re.search(r"VmHWM:\s+(\d+)",data)[1])*1024
            except (OSError,TypeError): pass
        return None,None
    def close(self):
        if self.handle: self.kernel.CloseHandle(self.handle)

def run(name, strategy, scenario, kind, size, split="eval", params=None, max_chunks=131072):
    params=params or (4194304,4,3)
    revision=subprocess.check_output(["git","-c",f"safe.directory={ROOT.as_posix()}","rev-parse","HEAD"],cwd=ROOT,text=True).strip()
    folder=OUT/"runs"/name
    if folder.exists(): raise ValueError(f"Refusing to overwrite run {name}")
    folder.parent.mkdir(parents=True,exist_ok=True)
    input_file=OUT/"inputs"/f"{split}-{kind}-{size}.bin"
    java=Path(os.environ["JAVA_HOME"])/"bin"/("java.exe" if os.name=="nt" else "java")
    classpath=os.pathsep.join([str(ROOT/"benchmarks/target/classes"),str(ROOT/"benchmarks/target/lib/*")])
    flags=["-Xms128m","-Xmx2048m","-XX:NativeMemoryTracking=summary","-XX:+UnlockDiagnosticVMOptions","-XX:+PrintNMTStatistics",
           "-Dsun.net.httpserver.maxReqTime=60","-Dsun.net.httpserver.maxRspTime=60","-Djdk.httpserver.maxConnections=32"]
    command=[str(java),*flags,"-cp",classpath,"io.github.aullchen.lcp.examples.BenchmarkRun",str(input_file),str(folder),
             strategy,scenario,split,*map(str,params),str(max_chunks)]
    log=folder.parent/(name+".log"); peak=0; samples=0; started=time.monotonic(); timed_out=False
    with log.open("w",encoding="utf-8") as stream:
        proc=subprocess.Popen(command,stdout=stream,stderr=subprocess.STDOUT,cwd=ROOT)
        memory=Memory(proc.pid)
        try:
            while proc.poll() is None:
                rss, high=memory.sample()
                if rss is not None: peak=max(peak,rss,high); samples+=1
                if time.monotonic()-started>300: proc.kill(); timed_out=True; break
                time.sleep(.05)
            proc.wait()
        finally: memory.close()
    result_file=folder/"result.json"
    result=json.loads(result_file.read_text()) if result_file.exists() else {"status":"FAILED","errorType":"ProcessTimeout" if timed_out else "ProcessExit"}
    raw_log=log.read_text(encoding="utf-8",errors="replace")
    total=re.search(r"Total: reserved=(\d+), committed=(\d+)",raw_log)
    heap=re.search(r"Java Heap \(reserved=(\d+), committed=(\d+)\)",raw_log)
    result.update({"run":name,"sourceRevision":revision,"dataset":kind,"size":size,"strategy":strategy,"scenario":scenario,"split":split,"exitCode":proc.returncode,
                   "peakRssBytes":peak or None,"rssSamples":samples,"rssIntervalMillis":50,
                   "nmtNativeCommittedAtExit":int(total[2])-int(heap[2]) if total and heap else None,
                   "jvmFlags":flags,"wallSecondsIncludingSetup":time.monotonic()-started})
    if result.get("status")=="COMPLETED":
        manifest=json.loads((OUT/"inputs/manifest.json").read_text())
        if result.get("outputSha256") != manifest["files"][input_file.name]["sha256"]: raise ValueError("Independent manifest SHA mismatch")
        if result["sourceLeasedBytesAtEnd"] or result["targetLeasedBytesAtEnd"]: raise ValueError("Leaked byte reservations")
    dump(folder/"result.json",result)
    # Delete only the generated payload owned by this unique run, retaining control evidence and JSON.
    owned=(folder/"sink").resolve()
    for payload in owned.glob("*/payload.bin"):
        if not payload.resolve().is_relative_to(owned): raise ValueError("Unsafe output path")
        payload.unlink()
    print(name,result["status"],round(result.get("completionNanos",0)/1e9,3),"seconds",flush=True)
    return result

def train(repeats):
    if repeats<5: raise ValueError("Training requires at least five independent repeats")
    groups=[("stable","text"),("step","mixed"),("sink","mixed")]
    values={str(i):[[] for _ in groups] for i in range(len(CANDIDATES))}
    for repeat in range(repeats):
        for group,(scenario,kind) in enumerate(groups):
            for offset in range(len(CANDIDATES)):
                index=(offset+repeat)%len(CANDIDATES); params=CANDIDATES[index]
                r=run(f"train-{repeat}-{index}-{scenario}","B1",scenario,kind,"long","train",params)
                if r["status"]!="COMPLETED": raise RuntimeError("Training failure; retain evidence and inspect")
                values[str(index)][group].append(r["completionNanos"])
    medians={key:[statistics.median(group) for group in groups] for key,groups in values.items()}
    scores={key:math.exp(sum(math.log(v/b) for v,b in zip(times,medians["0"]))/len(groups)) for key,times in medians.items()}
    selected=min(scores,key=scores.get)
    dump(OUT/"selection.json",{"selection":"minimum geometric mean of training median completion ratios to B0", "candidates":CANDIDATES,
                              "trainingGroups":groups,"repeats":repeats,"scores":scores,"selected":CANDIDATES[int(selected)],"trace":"train-v1"})

def evaluate(repeats):
    if repeats<5: raise ValueError("Evaluation requires at least five independent repeats")
    selection=json.loads((OUT/"selection.json").read_text())
    for repeat in range(repeats):
        order=STRATEGIES[repeat%5:]+STRATEGIES[:repeat%5]
        for index,(scenario,kind,size) in enumerate(CASES):
            for strategy in order:
                name=f"eval-{repeat}-{index}-{strategy}"
                run(name,strategy,scenario,kind,size,params=selection["selected"] if strategy=="B1" else None)

def summarize():
    records=[json.loads(path.read_text()) for path in sorted((OUT/"runs").glob("*/result.json"))]
    groups={}
    for r in records:
        phase=r["run"].split("-")[0]
        if phase not in ["eval","confirm"]: continue
        key="/".join([phase,r["scenario"],r["dataset"],r["size"],r["strategy"]]); groups.setdefault(key,[]).append(r)
    summary={}
    for key,group in groups.items():
        passed=[r for r in group if r["status"]=="COMPLETED"]
        seconds=sorted(r["completionNanos"]/1e9 for r in passed)
        summary[key]={"runs":len(group),"failures":len(group)-len(passed),"medianSeconds":statistics.median(seconds) if seconds else None,
                      "p95Seconds":seconds[math.ceil(.95*len(seconds))-1] if seconds else None,"minSeconds":min(seconds) if seconds else None,
                      "maxSeconds":max(seconds) if seconds else None,"medianRetryBytes":statistics.median(r["retriedFrameBytes"] for r in passed) if passed else None,
                      "maxRssBytes":max((r.get("peakRssBytes") or 0 for r in group),default=0),
                      "trials":sum(r.get("trials",0) for r in group),"rollbacks":sum(r.get("rollbacks",0) for r in group)}
    dump(OUT/"summary.json",summary)
    dump(OUT/"raw.json",records)
    print("Summarized",len(records),"runs in",len(groups),"evaluation groups",flush=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode",choices=["prepare","smoke","train","evaluate","scale","confirm","summary"])
    parser.add_argument("--long-mib",type=int,default=512); parser.add_argument("--repeats",type=int,default=5)
    args=parser.parse_args()
    if args.mode=="prepare": prepare(args.long_mib)
    elif args.mode=="smoke":
        for strategy in STRATEGIES: run("smoke-"+strategy,strategy,"stable","mixed","short")
    elif args.mode=="train": train(args.repeats)
    elif args.mode=="evaluate": evaluate(args.repeats)
    elif args.mode=="confirm":
        if args.repeats<5: raise ValueError("Confirmation requires five repeats")
        selected=json.loads((OUT/"selection.json").read_text())["selected"]
        for repeat in range(args.repeats):
            for scenario in ["step","sink"]:
                for strategy in (["B1","WC"] if repeat%2==0 else ["WC","B1"]):
                    run(f"confirm-{repeat}-{scenario}-{strategy}",strategy,scenario,"mixed","long",params=selected if strategy=="B1" else None)
    elif args.mode=="scale":
        for quota in [3,4]:
            result=run("quota-"+str(quota),"B0","stable","mixed","short",params=(262144,1,3),max_chunks=quota)
            if quota==3 and (result["status"]!="FAILED" or result.get("errorReason")!="TRANSFER_QUOTA"): raise ValueError("Missing explicit quota failure")
            if quota==4 and result["status"]!="COMPLETED": raise ValueError("Exact quota did not complete")
        for size in ["short","long"]: run("scale-"+size,"FULL","stable","mixed",size)
    else: summarize()

if __name__=="__main__": main()
