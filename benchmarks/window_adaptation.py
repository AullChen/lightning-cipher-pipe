"""Run the frozen changing-capacity window study. Python standard library, JDK 17."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import subprocess

ROOT=Path(__file__).resolve().parents[1]
STRATEGIES=["F1","F2","F4","A1","A4"]

def dump(path,value):
    path.write_text(json.dumps(value,indent=2)+"\n",encoding="utf-8")

def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for b in iter(lambda:stream.read(1048576),b''):h.update(b)
    return h.hexdigest()

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('output',type=Path);args=ap.parse_args()
    out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    rng=random.Random(104729); source=out/'mixed-256MiB.bin'
    with source.open('wb') as f:
        for _ in range(256*8):f.write(bytes(65536));f.write(rng.randbytes(65536))
    expected=digest(source)
    java=str(Path(os.environ['JAVA_HOME'])/'bin'/'java')
    flags=['-Xms128m','-Xmx768m','-XX:ActiveProcessorCount=4','-Dsun.net.httpserver.maxReqTime=60',
           '-Dsun.net.httpserver.maxRspTime=60','-Djdk.httpserver.maxConnections=32']
    cases=[(f'{strategy}-r{repeat}',strategy,repeat) for repeat in range(3)
           for strategy in STRATEGIES[repeat:]+STRATEGIES[:repeat]]
    dump(out/'manifest.json',{'protocol':'window-adaptation-1','cases':cases,'seed':104729,'inputBytes':source.stat().st_size(),
         'inputSha256':expected,'jvmArguments':flags,'timeoutSeconds':300,'figureRepetition':0,
         'sourceCommit':subprocess.check_output(['git','-c',f'safe.directory={ROOT.as_posix()}','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
         'java':subprocess.check_output([java,'-version'],stderr=subprocess.STDOUT,text=True),
         'sources':{p.relative_to(ROOT).as_posix():digest(p) for base in [ROOT/'modules',ROOT/'examples/src',ROOT/'benchmarks/src']
                    for p in sorted(base.rglob('*.java')) if 'target' not in p.parts},
         'runnerSha256':digest(Path(__file__)),'protocolSha256':digest(ROOT/'docs/window-adaptation-protocol.md')})
    results=[]
    for name,strategy,repeat in cases:
        command=[java]+flags+['-cp',os.pathsep.join(map(str,[ROOT/'benchmarks/target/classes',ROOT/'benchmarks/target/lib/*'])),
                    'io.github.aullchen.lcp.examples.WindowAdaptationRun',str(source),str(out/name),strategy]
        print('START',name,flush=True)
        with (out/(name+'.log')).open('w',encoding='utf-8') as log:
            try:
                process=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=300)
                path=out/name/'result.json'
                r=json.loads(path.read_text(encoding='utf-8')) if path.exists() else {'status':'PROCESS_FAILED'}
                if process.returncode:r.update(status='PROCESS_FAILED',exitCode=process.returncode)
            except subprocess.TimeoutExpired:r={'status':'TIMEOUT'}
        r.update(case=name,strategy=strategy,repeat=repeat);results.append(r);dump(out/'results.json',results)
        print('END',name,r['status'],r.get('completionNanos',0)/1e9,flush=True)
        if (r['status']!='COMPLETED' or r['inputSha256']!=expected or r['outputSha256']!=expected
                or r['verifiedBytes']!=256*1048576 or r['chunks']!=1024
                or r['sourceLeasedBytesAtEnd'] or r['targetLeasedBytesAtEnd'] or r['capacityActiveAtEnd']):
            raise SystemExit('Failure preserved; matrix stopped without retry')
    print('15 runs complete; analyze traces without tuning policy',flush=True)

if __name__=='__main__':main()
