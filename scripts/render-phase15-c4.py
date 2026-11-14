"""C-4 독립 계산 JSON을 반복별 비교표로 표현. 수치의 의미·off 결측·시계 경계 보존."""
from pathlib import Path
import argparse
import json


def number(value, places=3):
    return 'N/A' if value is None else f'{value:.{places}f}'


def table(lines, headers, rows):
    lines.extend(['', '| ' + ' | '.join(headers) + ' |', '| ' + ' | '.join(['---'] * len(headers)) + ' |'])
    lines.extend('| ' + ' | '.join(map(str, row)) + ' |' for row in rows)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('input')
    parser.add_argument('output')
    args = parser.parse_args()
    source = Path(args.input).resolve()
    target = Path(args.output).resolve()
    root = Path('E:/pixel-place-phase15/phase15-a-20260918/agent-runs').resolve()
    assert source.is_relative_to(root) and target.is_relative_to(root)
    report = json.loads(source.read_text(encoding='utf-8-sig'))
    assert report['complete'] and report['completed'] == 15 and report['independentChecksPassed']
    rows = report['trials']
    lines = ['# C-4 고정 비교 원시 결과 재계산', '', f"세션 `{report['session']}`. 입력 `{source.name}`.",
        '', '대표 목표: accepted ≥100/s, 성공 RTT p95 ≤100ms·p99 ≤250ms, 시스템 오류율 ≤0.001, 정합성 불일치 0.',
        'accepted/s는 측정 완료창, RTT·시스템 오류율은 측정 송신 cohort 기준. 정책 거부와 not_sent는 별도 분모. busy/unknown 503은 시스템 오류에 포함.',
        '원시 summary의 performanceDecision은 당시 runner의 고정 placeholder이며 아래 독립 판정의 근거로 사용하지 않음.']
    values=[]
    for row in rows:
        m=row['measurement']; outcomes=m['outcomes']
        values.append([row['matrixEntry'], number(m['acceptedPerSecond']), number(m['successRtt']['p95Millis']),
            number(m['successRtt']['p99Millis']), number(m['systemErrorRate'],6), outcomes.get('policy_4xx',0),
            outcomes.get('client_not_sent',0), f"{m['sent']}/{m['planned']}", m['drainAccepted'],
            'PASS' if row['drain']['complete'] else 'FAIL',
            ('PASS' if row['goalPassed'] else 'MISS') if row['role']=='target' and not row['matrixEntry'].endswith('-off') else '보조'])
    table(lines,['entry','accepted/s','p95 ms','p99 ms','system error','policy','not_sent','sent/planned','drain accepted','drain','goal'],values)
    lines.extend(['', '## 실제 force·batch·fresh capture·복구', '',
        'force와 batch는 준비/warmup/측정/drain 전체 trial의 실제 storage 계수. batch 평균은 batchRecords/batchCalls. fresh capture는 실제 record 수이며 seq 차이가 아님.',
        '마지막 30초는 parent/server 단조시계의 SEND_DONE 수신 끝점 기준. client nanoTime과 차감하지 않으며 pipe 경계 지연을 포함.'])
    values=[]
    for row in rows:
        c=row['walCounts']; f=row['freshCapture']; final=row['drain']['observations'][-1]
        values.append([row['matrixEntry'],f"{c['recordForces']}/{c['completedRecords']}",number(row['forcePerEvent'],5),
            number(row['actualAppendBatchMean']), 'N/A (off)' if c['batchSizes'] is None else json.dumps(c['batchSizes'],sort_keys=True),
            c['rotations'],f['maxRecords'],number(f['last30MeanRecords']),final['capture']['records'],row['recoveryPid'],row['consistency']['mismatches']])
    table(lines,['entry','force/events','force/event','batch mean','batch size:count','rotation','fresh max','last30 mean','drain records','new JVM PID','mismatch'],values)
    lines.extend(['','## 자원과 대기 관측','',
        'CPU는 사용 core 수, heap은 관측 peak MiB. timer는 측정 phase 완료 count의 평균이며 중첩 timer를 합산하지 않음. transaction은 committed outcome, 나머지는 success outcome. single의 queue_wait는 해당 경로 없음으로 N/A. disk는 E 볼륨 전체 1초 표본으로 다른 호스트 작업의 기여 가능.',
        'off의 timers/batchSizes/lastCycle/broadcastFailures만 계측 비활성 사유로 N/A. 필수 WAL 계수·실제 batch 평균·활동/정합성/drain/복구는 그대로 검증.'])
    values=[]
    operations=('write_wait','write_held','queue_wait','capture_wait','transaction','broadcast')
    for row in rows:
        timers={t['operation']:t['meanMillis'] for t in row['timers']
            if t['outcome']==('committed' if t['operation']=='transaction' else 'success')}
        s=row['serverResources']; c=row['clientResources']; disk=row['disk'].get('counters',{})
        values.append([row['matrixEntry'],number(s['cpuMeanCores']),number(s['heapPeakBytes']/1024**2,1),
            f"{s['gcCount']}/{s['gcMillis']}",number(c['cpuMeanCores']),row['rawHttpOutstandingPeak'],
            *[number(timers.get(op)) for op in operations],
            number(disk.get('avg. disk sec/transfer',{}).get('mean'),6),number(disk.get('current disk queue length',{}).get('maximum'))])
    table(lines,['entry','server CPU','heap MiB','GC count/ms','client CPU','HTTP peak',*operations,'disk mean s','disk queue max'],values)
    lines.extend(['','## 구간별 backlog와 비송신 사유','',
        '30초 구간의 fresh capture 평균/최대와 executor 최대값은 동일 서버 시계로 묶음. 마지막 정상 drain은 모든 미완료 활동이 0이고 fresh capture records=0·checkpoint=tail을 만족해야 함.'])
    for row in rows:
        lines.extend(['', f"### {row['matrixEntry']} — {row['trial']}", '',
            'not_sent: '+json.dumps(row['notSentReasons'],sort_keys=True),
            '503: '+json.dumps(row['serviceUnavailableReasons'],sort_keys=True),
            '선택 비활성: '+json.dumps(row['disabledFields'],ensure_ascii=False,sort_keys=True)])
        table(lines,['quarter','capture mean/max','queue','claimed','worker','outstanding','unpublished','command','pending samples','GC ms'],
            [[q['quarter'],f"{number(q['freshCaptureMean'])}/{q['freshCaptureMax']}",
              *[q['executorMax'][k] for k in ('queued','claimedUnsettled','workerInFlight','outstanding','unpublishedTerminal')],
              q['commandMax'],q['pendingSamples'],q['resources']['gcMillis']] for q in row['serverQuarters']])
        table(lines,['quarter',*operations,'client CPU','client GC ms','disk mean s','disk queue max'],
            [[q['quarter'],*[number((q['timerDeltas'] or {}).get(op,{}).get('meanMillis')) for op in operations],
              number(c['resources']['cpuMeanCores']),c['resources']['gcMillis'],
              number(c['disk'].get('counters',{}).get('avg. disk sec/transfer',{}).get('mean'),6),
              number(c['disk'].get('counters',{}).get('current disk queue length',{}).get('maximum'))]
             for q,c in zip(row['serverQuarters'],row['clientQuarters'])])
    lines.extend(['','## GC-08 수치 기준','',json.dumps(report['goals'],ensure_ascii=False),
        '',json.dumps(report['spreadPairs'],ensure_ascii=False),
        '',f"수치상 group 채택 후보: {report['groupNumericallyEligible']}. 부작용/관측 범위/최종 코드 검토는 별도 판정.",
        '',f"대표 실제 목표 부하 충족: {report['targetLoadAdequate']}; 최소 실제 송신 {report['minimumTargetActualSentPerSecond']:.4f}/s. {report['finalSelection']}",
        '', '원시 검증 및 15회 실행 완료는 C-4/전체 15 완료와 별개다. 발생기가 목표 부하를 걸지 못한 경우 서버 목표 성능과 최종 도입 판단을 보류하며 target role을 probe로 바꾸지 않음.',
        '', '저부하 및 on/off 각 1쌍은 일반적인 추가 지연 0 또는 계측 비용 0의 증명이 아님. 실제 rotation 0인 trial은 회전 성능 증거가 아님.'])
    with target.open('x',encoding='utf-8') as stream:
        stream.write('\n'.join(lines)+'\n')
    print(str(target))


if __name__ == '__main__':
    main()
