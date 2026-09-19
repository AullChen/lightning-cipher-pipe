"""Validate and publish only generated, non-identifying benchmark evidence."""
from collections import Counter
import gzip
import hashlib
import json
from pathlib import Path
import random
import statistics
import run

REPORT = run.ROOT / "docs" / "experiments" / "feedback-v1"

def interval(baseline, candidate):
    rng=random.Random(1871); gains=[]
    for _ in range(10000):
        indices=[rng.randrange(len(baseline)) for _ in baseline]
        gains.append(1-statistics.median(candidate[i] for i in indices)/statistics.median(baseline[i] for i in indices))
    gains.sort(); return [gains[249],gains[9749]]

def main():
    run.summarize()
    rows=json.loads((run.OUT/"raw.json").read_text())
    rows=[r for r in rows if r["run"].startswith(("train-","eval-","confirm-","quota-","scale-"))]
    training=[r for r in rows if r["run"].startswith("train-")]
    evaluation=[r for r in rows if r["run"].startswith("eval-")]
    if len(training)!=90 or len(evaluation)!=275: raise ValueError("Incomplete registered matrix")
    for scenario,kind,size in run.CASES:
        for strategy in run.STRATEGIES:
            group=[r for r in evaluation if (r["scenario"],r["dataset"],r["size"],r["strategy"])==(scenario,kind,size,strategy)]
            if len(group)!=5 or len({r["run"] for r in group})!=5: raise ValueError("Missing or duplicate evaluation group")
    confirmation=[r for r in rows if r["run"].startswith("confirm-")]
    if confirmation:
        for scenario in ["step", "sink"]:
            for strategy in ["B1", "WC"]:
                group=[r for r in confirmation if r["scenario"]==scenario and r["strategy"]==strategy]
                if len(group)!=5 or len({r["run"] for r in group})!=5:
                    raise ValueError("Incomplete confirmation group")
        if len(confirmation)!=20: raise ValueError("Unexpected confirmation records")
    resources={r["run"]:r for r in rows if r["run"].startswith(("quota-", "scale-"))}
    if set(resources)!={"quota-3", "quota-4", "scale-short", "scale-long"}:
        raise ValueError("Incomplete resource checks")
    if resources["quota-3"]["status"]!="FAILED" or resources["quota-3"].get("errorReason")!="TRANSFER_QUOTA":
        raise ValueError("Expected explicit quota refusal")
    if any(resources[name]["status"]!="COMPLETED" for name in ["quota-4", "scale-short", "scale-long"]):
        raise ValueError("Resource completion check failed")
    for row in rows:
        if row["status"]=="COMPLETED":
            if row["inputSha256"]!=row["outputSha256"]: raise ValueError("SHA mismatch")
            if row["sourceLeasedBytesAtEnd"] or row["targetLeasedBytesAtEnd"]: raise ValueError("Retained leases")
        if row["peakRssBytes"] is None or row["nmtNativeCommittedAtExit"] is None: raise ValueError("Missing required memory evidence")
    selection=json.loads((run.OUT/"selection.json").read_text())
    summary=json.loads((run.OUT/"summary.json").read_text())
    for key,stats in summary.items():
        phase,scenario,kind,size,strategy=key.split("/")
        group=[r for r in rows if r["run"].startswith(phase+"-") and (r["scenario"],r["dataset"],r["size"],r["strategy"])==(scenario,kind,size,strategy) and r["status"]=="COMPLETED"]
        stats["medianGoodputMiBps"]=statistics.median(r["inputBytes"]/run.MIB/(r["completionNanos"]/1e9) for r in group) if group else None
        for field in ["cpuNanos","tlsUpstreamBytes","tlsDownstreamBytes","ackP95Nanos","queueShare","peakHeapPoolSum","nmtNativeCommittedAtExit","decisionNanos"]:
            values=[r[field] for r in group if r.get(field) is not None]
            stats["median_"+field]=statistics.median(values) if values else None
    manifest=json.loads((run.OUT/"inputs/manifest.json").read_text())
    REPORT.mkdir(parents=True,exist_ok=True)
    run.dump(REPORT/"summary.json",summary); run.dump(REPORT/"selection.json",selection); run.dump(REPORT/"inputs.json",manifest)
    raw="".join(json.dumps(r,separators=(",",":"),sort_keys=True)+"\n" for r in rows).encode()
    archive=gzip.compress(raw,mtime=0); (REPORT/"raw.jsonl.gz").write_bytes(archive)
    environment=json.loads((run.OUT/"environment.json").read_text())
    environment["jvmProcessorCountsByPhase"]={phase:dict(Counter(str(r["processors"]) for r in rows if r["run"].startswith(phase+"-"))) for phase in ["train", "eval", "confirm", "quota", "scale"]}
    environment["jvmReportedProcessors"]=sorted({r["processors"] for r in rows})
    environment.update({"sourceRevisions":sorted({r["sourceRevision"] for r in rows}),
                        "rawArchiveSha256":hashlib.sha256(archive).hexdigest()})
    run.dump(REPORT/"environment.json",environment)
    lines=["# 反馈策略 v1：受控传输实验", "", "本报告区分实现正确性与性能收益。所有组共用真实认证、FileSink 持久回执、Finish 重读及完整输出 SHA-256 校验。结果只适用于下述受控环境。", "", "## 方法与数据", "",
           "五策略为固定 B0、训练选参固定 B1、仅窗口 B2、窗口加块大小 WC、完整三参数 FULL。B0 为 4 MiB / 窗口 4 / ZSTD 3。训练使用独立种子与时序，六组候选在三场景各重复五次，共 90 次；评测有 11 个数据/场景组合，每策略重复五次，共 275 次。顺序按重复轮次交错。", "",
           f"B1 全局选定参数为 **{selection['selected'][0]//run.MIB} MiB / 窗口 {selection['selected'][1]} / ZSTD {selection['selected'][2]}**；不按评测场景重新选参。训练评分见 [selection.json](selection.json)。", "",
           f"评测长输入 {manifest['longMiB']} MiB，短合成输入 1 MiB；公开输入由四份未修改的 RFC 原文按编号连接。不同种子只影响伪随机部分；重复文本沿用同一模式，不代表独立语料泛化。数据摘要及出处见 [inputs.json](inputs.json)。环境参数独立记录，不从结果推测硬件。详细配置见 [environment.json](environment.json)。", "",
           "源、目标与 TLS 透明代理位于同一 JVM，CPU/RSS 均为三者合计。每次独立 JVM，输入预读校验在计时外；总完成时间包括 Open/TLS、传输、重试、退避及 Finish 重读。源码版本和有效初始窗口保存在每条原始记录中。硬件逻辑处理器数与 JVM 实际报告的可用处理器数分别记录，不能混为一谈。此配置下初始窗口 4 可获得四份完整块许可，反馈试探更大窗口可能受预算钳制。", "",
           "网络阶跃、高 RTT 和断线由有界字节流代理模拟，慢 Sink 在真实写入与 force 之间增加延迟。控制轨迹、数据生成、内存口径和命令详见 [实验协议](../../../benchmarks/README.md)。这些不是独立物理主机上的 WAN 测试。", "", "## 完成时间", "",
           "单位秒，列为五次中位数。括号标明成功数；失败不删除，也不以成功子集宣称改进。P95（五次时为最大值）、最小/最大值和所有辅助指标见机器可读结果。", "",
           "| 场景 / 数据 / 大小 | B0 | B1 | B2 | WC | FULL |", "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for scenario,kind,size in run.CASES:
        values=[]
        for strategy in run.STRATEGIES:
            g=summary[f"eval/{scenario}/{kind}/{size}/{strategy}"]
            value="—" if g["medianSeconds"] is None else f"{g['medianSeconds']:.3f}"
            values.append(value+f" ({g['runs']-g['failures']}/{g['runs']})")
        lines.append("| "+" / ".join([scenario,kind,size])+" | "+" | ".join(values)+" |")
    lines += ["", "有效明文吞吐如下，单位 MiB/s，包含 Finish 的总完成时间作为分母。其他辅助指标的中位数在 summary.json，逐次值在原始档案。", "",
              "| 场景 / 数据 / 大小 | B1 吞吐 | FULL 吞吐 |", "| --- | ---: | ---: |"]
    for scenario,kind,size in run.CASES:
        cells=[]
        for strategy in ["B1","FULL"]:
            value=summary[f"eval/{scenario}/{kind}/{size}/{strategy}"]["medianGoodputMiBps"]
            cells.append("—" if value is None else f"{value:.2f}")
        lines.append("| "+" / ".join([scenario,kind,size])+" | "+" | ".join(cells)+" |")
    lines += ["", "## 预选目标与不确定性", "", "预选目标仅为阶跃和慢 Sink：相对 B1 完成时间中位数降低至少 10%；或重传帧字节中位数降低至少 20% 且时间增加不超过 5%。B1 重传中位数为零时，第二项不适用。下列区间为按重复轮次配对的 10000 次 bootstrap 95% 区间；每组仅五次，区间不代表其他机器或真实网络的保证。", ""]
    met=[]
    for scenario in ["step","sink"]:
        groups=[]
        for strategy in ["B1","FULL"]:
            group=sorted([r for r in evaluation if r["scenario"]==scenario and r["strategy"]==strategy],key=lambda r:r["run"])
            groups.append(group)
        if any(r["status"]!="COMPLETED" for g in groups for r in g):
            lines.append(f"- {scenario}：存在失败，不作收益判断。"); met.append(False); continue
        b,c=[[r["completionNanos"] for r in g] for g in groups]
        gain=1-statistics.median(c)/statistics.median(b); ci=interval(b,c)
        br,cr=[statistics.median(r["retriedFrameBytes"] for r in g) for g in groups]
        passed=(gain>=.1 and ci[0]>0) or (br>0 and cr<=.8*br and gain>=-.05)
        met.append(passed)
        fixed=summary[f"eval/{scenario}/mixed/long/B0"]["medianSeconds"]
        full=summary[f"eval/{scenario}/mixed/long/FULL"]["medianSeconds"]
        lines.append(f"- {scenario} 的 FULL 相对 B0 时间改善 {(1-full/fixed)*100:.1f}%。即使相对 B1 达到数值阈值，也需结合 B0 与实际试探次数判断是否来自反馈。")
        lines.append(f"- {scenario}：FULL 时间相对 B1 改善 {gain*100:.1f}%，95% 区间 [{ci[0]*100:.1f}%, {ci[1]*100:.1f}%]；重传字节中位数 B1={br:g}、FULL={cr:g}。"+("达到本组工程阈值，仍须限制结论范围。" if passed else "未满足可确认的收益目标。"))
    lines += ["", "## 控制器行为与资源", ""]
    for strategy in ["B2","WC","FULL"]:
        group=[r for r in evaluation if r["strategy"]==strategy]
        active=sum(r.get("trials",0)>0 for r in group)
        lines.append(f"- {strategy}：{len(group)} 次评测中 {active} 次启动试探，共 {sum(r.get('trials',0) for r in group)} 次试探、{sum(r.get('rollbacks',0) for r in group)} 次回退；控制器累计耗时 {sum(r.get('decisionNanos',0) for r in group)/1e6:.3f} ms。")
    lines += [f"- 全部评测进程最大 RSS 为 {max(r['peakRssBytes'] for r in evaluation)/run.MIB:.1f} MiB。RSS 为进程高水位与 50 ms 采样的最大值；包含两端和代理，不能当作单端缓冲预算。",
              "- Heap 为各内存池峰值之和；NMT native 为 JVM 退出快照，非峰值，也不覆盖所有第三方原生分配。TLS 字节不包含 TCP/IP 首部和内核重传；应用重传按提交给 HTTP 的完整帧计量，可能含未全部发出的请求，不等价于抓包重传字节。ACK P95 为最近 64 条有效样本，不是全程直方图。", ""]
    for row in rows:
        if row["run"].startswith(("quota-","scale-")):
            lines.append(f"- `{row['run']}`：{row['status']}，输入 {row['inputBytes']/run.MIB:g} MiB，maxChunks={row['maxChunks']}，RSS {row['peakRssBytes']/run.MIB:.1f} MiB"+(f"，{row['errorReason']}" if row.get("errorReason") else "")+"。")
    lines += ["", "配额 3/4 的案例分别验证明确拒绝与精确上限完成；长短输入使用相同的 131072 槽位元数据上限。所有成功输出哈希一致且两端许可归零。两种规模的 RSS 观察与固定容量索引/队列为已测配置提供资源边界证据，不外推任意输入长度或断电持久性。", "", "## 有界简化复测", ""]
    confirmation=[r for r in rows if r["run"].startswith("confirm-")]
    if confirmation:
        if len(confirmation)!=20: raise ValueError("Incomplete confirmation matrix")
        lines.append("按预先声明的方案将压缩等级固定为 3（WC），不改变 policyVersion 1 阈值；仅在阶跃和慢 Sink 各与冻结 B1 交错复测五次。此轮用于确认简化方案，不据此继续挑选或调参。确认批次与前批耗时存在漂移，只比较同批交错运行的 B1/WC，不作跨批绝对耗时比较。")
        lines.append("")
        for scenario in ["step","sink"]:
            bg=summary[f"confirm/{scenario}/mixed/long/B1"]; cg=summary[f"confirm/{scenario}/mixed/long/WC"]
            if not cg["medianSeconds"] or not bg["medianSeconds"]:
                lines.append(f"- {scenario}：存在全组失败，不作收益判断。"); continue
            gain=1-cg["medianSeconds"]/bg["medianSeconds"]
            lines.append(f"- {scenario}：B1 {bg['medianSeconds']:.3f} 秒，WC {cg['medianSeconds']:.3f} 秒，相对改善 {gain*100:.1f}%；失败 B1={bg['failures']}、WC={cg['failures']}。")
    else: lines.append("FULL 已达到预选组目标，因此未触发条件式简化复测。")
    failures=[r for r in rows if r["status"]!="COMPLETED"]
    lines += ["", "## 结论与限制", "", ("至少一个预选组满足相对 B1 的数值工程阈值；仍不能把初始参数差异、共同管线行为或运行波动当成联合反馈的因果收益。" if any(met) else "本轮未确认完整反馈策略相对训练固定基线达到预定收益。已测安全传输、内容与资源不变式通过，研究收益目标尚未达成。"), "",
              "运行环境存在可观测变化：90 次训练均报告 32 个可用处理器，评测 261 次报告 32 个、14 次报告 16 个；全部确认及资源检查报告 16 个。变化影响首轮 mixed/entropy 稳定场景及阶跃场景，原因未确定，可能影响调度和耗时。所有记录保留，因此不能把这些比较当作严格恒定环境下的因果证据。", "",
              "发送管线在每批准备与发送之间切换，整块峰值预留也可能限制物理窗口；运行时间不足以完成试探的组不能用来证明稳态收益。应结合 trials、windowFullNanos、budgetBlockedNanos 与实际块数分析，不把未触发调参解释成有效自适应。", "",
              f"保留全部 {len(rows)} 次训练、评测、确认和资源检查结果，其中 {len(failures)} 次非成功（含预期配额拒绝）。失败明细如下：", ""]
    lines.extend(f"- `{r['run']}`：{r.get('errorReason',r.get('errorType','FAILED'))}。" for r in failures)
    lines += ["", "## 复现与证据", "", "构建及运行命令见 [实验入口](../../../benchmarks/README.md)。同一输入清单、已冻结 B1 选择与轨迹定义可以复现方法；精确耗时受操作系统调度、JIT、缓存和其他负载影响。", "",
              "- [所有分组统计](summary.json)", "- [训练选择](selection.json)", "- [数据校验与公开出处](inputs.json)", "- [环境与原始档案校验值](environment.json)", "- [完整原始 JSONL（gzip）](raw.jsonl.gz)", "",
              "公开证据仅含合成/公开数据摘要、参数和测量值；不含私钥、明文载荷、个人路径或机器标识。"]
    (REPORT/"README.md").write_text("\n".join(lines)+"\n",encoding="utf-8")
    print("Published",len(rows),"validated records; targets:",met)

if __name__=="__main__": main()
