"""Regression checks for response censoring and trace-derived rates."""
import unittest

from window_adaptation_report import MIB, STRATEGIES, confirmed_at, data_end, response, summarize


def trace(window, end=40):
    return [{'seconds':i / 4, 'window':window(i / 4),
             'confirmedBytes':min(i / 4, 32) * 100} for i in range(end * 4 + 1)]


class TraceAnalysisTest(unittest.TestCase):
    def test_response_requires_two_seconds_in_band(self):
        rows=trace(lambda t:1 if 13<=t<14 or t>=16 else 4)
        result=response(rows,12,28,lambda w:w<=1)
        self.assertEqual(result,{'seconds':4,'alreadyInBand':False,'censored':False})

    def test_transient_response_is_censored(self):
        rows=trace(lambda t:1 if 13<=t<14 else 4)
        result=response(rows,12,28,lambda w:w<=1)
        self.assertTrue(result['censored'])
        self.assertIsNone(result['seconds'])
        self.assertEqual(result['observedUntilSeconds'],16)

    def test_verification_tail_cannot_complete_response(self):
        rows=trace(lambda t:4 if t>=31 else 1)
        end=data_end(rows,3200)
        self.assertEqual(end,32)
        self.assertTrue(response(rows,28,end,lambda w:w>=3)['censored'])
        self.assertFalse(response(rows,28,40,lambda w:w>=3)['censored'])

    def test_already_in_band_is_distinct_from_adjustment(self):
        result=response(trace(lambda t:1),12,28,lambda w:w<=1)
        self.assertEqual(result,{'seconds':0,'alreadyInBand':True,'censored':False})

    def test_boundary_adjustment_is_not_already_in_band(self):
        result=response(trace(lambda t:4 if t<12 else 1),12,28,lambda w:w<=1)
        self.assertEqual(result,{'seconds':0,'alreadyInBand':False,'censored':False})

    def test_byte_interpolation_and_end_clamping(self):
        rows=[{'seconds':0,'confirmedBytes':0},{'seconds':2,'confirmedBytes':200},
              {'seconds':4,'confirmedBytes':300}]
        self.assertEqual(confirmed_at(rows,1),100)
        self.assertEqual(confirmed_at(rows,3),250)
        self.assertEqual(confirmed_at(rows,5),300)


class AcceptanceTest(unittest.TestCase):
    def matrix(self):
        records=[];traces={}
        for repeat in range(3):
            for strategy,seconds in zip(STRATEGIES,[100,80,60,64,65]):
                name=f'{strategy}-r{repeat}'
                records.append(dict(case=name,strategy=strategy,repeat=repeat,status='COMPLETED',
                                    inputSha256='same',outputSha256='same',inputBytes=256*MIB,verifiedBytes=256*MIB,
                                    chunks=1024,sourceLeasedBytesAtEnd=0,targetLeasedBytesAtEnd=0,capacityActiveAtEnd=0,
                                    completionNanos=seconds*10**9,cpuNanos=10**9,decisionNanos=1000,
                                    retries=0,gateBusy=0,retriedFrameBytes=0,
                                    started=0,evaluated=0,retained=0,rolledBack=0,interrupted=0))
                traces[name]=[dict(seconds=t,confirmedBytes=min(t,40)/40*256*MIB,
                                   capacity=1 if 12<=t<28 else 4,window=1) for t in [0,12,28,40,seconds]]
        return records,traces

    def test_every_initial_window_must_meet_time_limit(self):
        records,traces=self.matrix()
        self.assertTrue(summarize(records,traces)['operationalGoalMet'])
        next(r for r in records if r['case']=='A4-r2')['completionNanos']=70*10**9
        result=summarize(records,traces)
        self.assertFalse(result['withinTenPercentForBothStarts'])
        self.assertTrue(result['lowerStartSensitivityEveryRepeat'])
        self.assertFalse(result['operationalGoalMet'])

    def test_missing_repeat_cannot_pass(self):
        records,traces=self.matrix()
        result=summarize(records[:-1],traces)
        self.assertFalse(result['completeMatrix'])
        self.assertFalse(result['operationalGoalMet'])

    def test_integrity_failure_cannot_pass(self):
        records,traces=self.matrix();records[0]['outputSha256']='wrong'
        result=summarize(records,traces)
        self.assertFalse(result['integrityGate'])
        self.assertFalse(result['operationalGoalMet'])


if __name__=='__main__':unittest.main()
