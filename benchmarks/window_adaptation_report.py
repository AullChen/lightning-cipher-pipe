"""Summarize the frozen window study and render one aligned mechanism figure."""
import argparse
import csv
import gzip
import json
import math
from pathlib import Path
import shutil
import statistics

STRATEGIES=['F1','F2','F4','A1','A4']
MIB=1048576

def load_trace(path):
    with path.open(encoding='utf-8') as f:
        return [{k:float(v) if v else math.nan for k,v in row.items()} for row in csv.DictReader(f)]

def data_end(trace, size):
    return next(row['seconds'] for row in trace if row['confirmedBytes']>=size)

def response(trace, start, end, predicate):
    """First observed band held for >=2 seconds; verification tail is excluded by caller."""
    before=[r for r in trace if r['seconds']<start]
    first=True; began=None; already=False
    for row in trace:
        t=row['seconds']
        if t<start:continue
        if t>=end:break
        if predicate(row['window']):
            if began is None:
                already=first and bool(before) and predicate(before[-1]['window'])
                began=start if already else t
            if t-began>=2:
                return {'seconds':max(0,began-start),'alreadyInBand':already,'censored':False}
        else:began=None;already=False
        first=False
    return {'seconds':None,'alreadyInBand':False,'censored':True,'observedUntilSeconds':max(0,end-start)}

def confirmed_at(trace, t):
    if t<=trace[0]['seconds']:return trace[0]['confirmedBytes']
    for a,b in zip(trace,trace[1:]):
        if a['seconds']<=t<=b['seconds']:
            fraction=(t-a['seconds'])/(b['seconds']-a['seconds'])
            return a['confirmedBytes']+fraction*(b['confirmedBytes']-a['confirmedBytes'])
    return trace[-1]['confirmedBytes']

def summarize(records, traces):
    expected={(s,r) for s in STRATEGIES for r in range(3)}
    complete={(r['strategy'],r['repeat']) for r in records}==expected and len(records)==15
    integrity=all(r.get('status')=='COMPLETED' and r.get('inputSha256')==r.get('outputSha256')
                  and r.get('verifiedBytes')==256*MIB and r.get('chunks')==1024
                  and r.get('sourceLeasedBytesAtEnd')==r.get('targetLeasedBytesAtEnd')==r.get('capacityActiveAtEnd')==0 for r in records)
    cases=[]
    for r in records:
        if r.get('status')!='COMPLETED':continue
        trace=traces[r['case']];end=data_end(trace,r['inputBytes'])
        assert all(b['seconds']>a['seconds'] and b['confirmedBytes']>=a['confirmedBytes'] for a,b in zip(trace,trace[1:]))
        assert all(row['capacity']==(1 if 12<=row['seconds']<28 else 4) for row in trace)
        assert end>28, 'Transfer did not see both capacity changes'
        for row in trace:
            if not math.isnan(row['window']):assert 1<=row['window']<=4
        phase_rates=[(confirmed_at(trace,b)-confirmed_at(trace,a))/(b-a)/MIB for a,b in [(0,12),(12,28),(28,end)]]
        c={**r,'completionSeconds':r['completionNanos']/1e9,'dataEndSeconds':end,'phaseGoodputMiBps':phase_rates,
           'maxSampleGapSeconds':max(b['seconds']-a['seconds'] for a,b in zip(trace,trace[1:]))}
        if r['strategy'].startswith('A'):
            c['downResponse']=response(trace,12,min(28,end),lambda w:w<=1)
            c['upResponse']=response(trace,28,end,lambda w:w>=3)
        cases.append(c)
    pairs=[]
    if complete and integrity:
        for repeat in range(3):
            times={r['strategy']:r['completionNanos']/1e9 for r in records if r['repeat']==repeat}
            best=min(times[s] for s in ['F1','F2','F4'])
            pairs.append({'repeat':repeat,'bestFixedSeconds':best,'bestFixed':min(['F1','F2','F4'],key=times.get),
                          'excessPercent':{s:100*(times[s]/best-1) for s in STRATEGIES},
                          'adaptiveStartSensitivity':abs(times['A1']-times['A4'])/min(times['A1'],times['A4']),
                          'fixedEndpointSensitivity':abs(times['F1']-times['F4'])/min(times['F1'],times['F4'])})
    groups=[]
    for s in STRATEGIES:
        rows=[c for c in cases if c['strategy']==s]
        if not rows:continue
        groups.append({'strategy':s,'successes':len(rows),'secondsMedian':statistics.median(c['completionSeconds'] for c in rows),
                       'secondsAll':[c['completionSeconds'] for c in rows],
                       'cpuSecondsMedian':statistics.median(c['cpuNanos']/1e9 for c in rows),
                       'decisionMillisMedian':statistics.median(c['decisionNanos']/1e6 for c in rows),
                       'retries':[c['retries'] for c in rows], 'gateBusy':[c['gateBusy'] for c in rows],
                       'retryMiBMedian':statistics.median(c['retriedFrameBytes']/MIB for c in rows),
                       'phaseGoodputMedian':[statistics.median(c['phaseGoodputMiBps'][i] for c in rows) for i in range(3)],
                       'trials':{k:sum(c[k] for c in rows) for k in ['started','evaluated','retained','rolledBack','interrupted']}})
    acceptable=complete and integrity and all(p['excessPercent'][s]<=10 for p in pairs for s in ['A1','A4'])
    less_sensitive=complete and integrity and all(p['adaptiveStartSensitivity']<p['fixedEndpointSensitivity'] for p in pairs)
    return {'completeMatrix':complete,'integrityGate':integrity,'withinTenPercentForBothStarts':acceptable,
            'lowerStartSensitivityEveryRepeat':less_sensitive,'operationalGoalMet':acceptable and less_sensitive,
            'groups':groups,'pairedComparisons':pairs,'cases':cases}

def plot(traces, report):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    colors={'F1':'#94a3b8','F2':'#64748b','F4':'#20252c','A1':'#0072b2','A4':'#d55e00'}
    plt.rcParams.update({'font.size':10,'axes.spines.top':False,'axes.spines.right':False,'svg.fonttype':'none'})
    fig,axes=plt.subplots(4,1,figsize=(11.4,9),sharex=True,gridspec_kw={'height_ratios':[.7,1,1.35,1.2]})
    end=max(traces[s+'-r0'][-1]['seconds'] for s in STRATEGIES)
    axes[0].step([0,12,28,end],[4,1,4,4],where='post',color='#303740',lw=2)
    axes[0].set_ylabel('Receiver\nadmission limit');axes[0].set_yticks([1,4]);axes[0].set_ylim(.5,4.8)
    lines=[]
    for s in STRATEGIES:
        rows=traces[s+'-r0'];t=[r['seconds'] for r in rows];w=[r['window'] for r in rows]
        style='-' if s.startswith('A') else '--'
        line,=axes[1].step(t,w,where='post',color=colors[s],ls=style,lw=1.9 if s.startswith('A') else 1.2,label=s)
        lines.append(line)
        mid=[];rate=[]
        for i in range(math.ceil(t[-1])):
            a=float(i);b=min(i+1,t[-1])
            if b>a:mid.append((a+b)/2);rate.append((confirmed_at(rows,b)-confirmed_at(rows,a))/(b-a)/MIB)
        axes[2].plot(mid,rate,color=colors[s],ls=style,lw=1.4)
        axes[3].plot(t,[r['ackP95Millis'] for r in rows],color=colors[s],ls=style,lw=1.4)
    axes[1].set_ylabel('Physical\nsender window');axes[1].set_yticks([1,2,3,4]);axes[1].set_ylim(.7,4.3)
    axes[2].set_ylabel('Confirmed goodput\n(MiB/s; 1 s bins)');axes[2].set_ylim(bottom=0)
    axes[3].set_ylabel('Rolling ACK P95\n(ms; last 64 valid)');axes[3].set_ylim(bottom=0);axes[3].set_xlabel('Seconds since transfer invocation')
    for i,ax in enumerate(axes):
        ax.axvspan(12,28,color='#e7b96b',alpha=.16)
        ax.axvline(12,color='#9c7b48',ls=':',lw=1);ax.axvline(28,color='#9c7b48',ls=':',lw=1)
        ax.grid(axis='y',alpha=.17);ax.set_xlim(0,end)
        ax.text(-.075,1.03,'ABCD'[i],transform=ax.transAxes,weight='bold',size=12)
    axes[0].text(12,4.5,' 4 → 1',ha='left',size=9);axes[0].text(28,4.5,' 1 → 4',ha='left',size=9)
    fig.suptitle('Window feedback under a changing receiver',x=.12,ha='left',size=16,weight='bold')
    fig.legend(lines,STRATEGIES,loc='upper left',bbox_to_anchor=(.115,.955),ncol=5,frameon=False)
    fig.subplots_adjust(left=.12,right=.975,top=.885,bottom=.09,hspace=.22)
    fig.text(.12,.025,'Repetition 0 selected before measurement · F: fixed window · A: policy v2, initial window · No smoothing of parameters',size=9,color='#555555')
    fig.savefig(report/'mechanism.png',dpi=180);fig.savefig(report/'mechanism.svg');plt.close(fig)
    svg=report/'mechanism.svg'
    svg.write_text('\n'.join(line.rstrip() for line in svg.read_text(encoding='utf-8').splitlines())+'\n',encoding='utf-8')
    return matplotlib.__version__

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('output',type=Path);ap.add_argument('report',type=Path);args=ap.parse_args()
    records=json.loads((args.output/'results.json').read_text(encoding='utf-8'))
    traces={r['case']:load_trace(args.output/r['case']/'trace.csv') for r in records if r.get('status')=='COMPLETED'}
    summary=summarize(records,traces)
    args.report.mkdir(parents=True,exist_ok=False)
    for name in ['manifest.json','results.json']:shutil.copyfile(args.output/name,args.report/name)
    (args.report/'traces').mkdir()
    for name in traces:
        with (args.output/name/'trace.csv').open('rb') as src,(args.report/'traces'/(name+'.csv.gz')).open('wb') as raw:
            with gzip.GzipFile(fileobj=raw,mode='wb',mtime=0) as zipped:shutil.copyfileobj(src,zipped)
    if summary['completeMatrix'] and summary['integrityGate']:summary['matplotlibVersion']=plot(traces,args.report)
    (args.report/'summary.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in summary.items() if k not in ['cases']},indent=2))
    if not summary['completeMatrix'] or not summary['integrityGate']:raise SystemExit('Incomplete or failed matrix retained')

if __name__=='__main__':main()
