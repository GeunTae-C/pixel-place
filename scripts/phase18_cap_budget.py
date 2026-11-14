"""Append-only CAP storage ledger. Service-free; never deletes or releases old charges."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import time
import uuid
from pixel_place_paths import control_path

GIB=1024**3
GROUPS={'evidence':'C','graph':'C','work':'F','cache':'F','mysql':'F','wal':'F'}

# 보존된 17-B fixture 한 곳만 허용. 새 링크/상위 링크/다른 reparse 예외로 확장 금지.
HISTORICAL_LINK=Path('phase17-B-20260928-190355/symlink-fixture/wal')
HISTORICAL_TARGET=HISTORICAL_LINK.with_name('unrelated-target')
HISTORICAL_CONTENT=b'preserved'
REPARSE_ATTRIBUTE=0x400
SYMLINK_TAG=0xA000000C
F_GROUPS={'evidence':'F','graph':'F','work':'F','cache':'F','mysql':'F','wal':'F','archive':'F','project':'F','retained':'C'}

C_GRAPH_LAYOUT='f-output-c-graph-v2'
C_GRAPH_GROUPS=dict(F_GROUPS,graph='C')

def groups_for(state):
    layout=state.get('volumeLayout')
    if layout is None:return GROUPS
    if layout=='f-output-v1':return F_GROUPS
    require(layout==C_GRAPH_LAYOUT,'Unknown storage layout')
    return C_GRAPH_GROUPS


def require(ok,message):
    if not ok:raise ValueError(message)

def expand_limit(limit,usage):
    """새 S2 준비의 정수 50%·5배 규칙. 런타임 한도 변경과 분리."""
    maximum=2**63-1
    require(type(limit) is int and type(usage) is int and 0<limit<=maximum and 0<=usage<=maximum//2,
            'Finite integer expansion inputs required')
    while 2*usage>=limit:
        require(limit<=maximum//5,'Storage expansion overflow')
        limit*=5
    return limit

def storage_expansion(prior,observed,previous_hash,reservation):
    """기존 chain의 과거 소비 + 다음 추가 peak를 보존한 미등록 정책 계산."""
    require(set(observed)==set(groups_for(prior)) and all(type(v) is int for v in observed.values()),'Storage expansion observation')
    peak={d:sum(v for k,v in reservation['additionalPeak'].items() if groups_for(prior)[k]==d)+reservation['transientAllowance'][d] for d in ('C','F')}
    delta={d:sum(max(0,observed[k]-prior['observed'][k]) for k in groups_for(prior) if groups_for(prior)[k]==d) for d in ('C','F')}
    usage={d:prior['charged'][d]+delta[d]+peak[d]+(64*1024**2 if d=='C' else 5*GIB) for d in ('C','F')}
    if prior.get('volumeLayout'):usage['F']+=64*1024**2
    usage['trial']=sum(peak.values())
    limits={d:expand_limit(start,usage[d]) for d,start in dict(C=20*GIB,F=100*GIB,trial=8*GIB).items()}
    return dict(kind='cap1-storage-50pct-v1' if reservation.get('stage')=='CAP-1' else 's2-storage-50pct-v1',previousHash=previous_hash,observed=dict(observed),usage=usage,limits=limits)

def read(path):
    with Path(path).open('rb') as stream:data=stream.read(8*1024*1024+1)
    require(len(data)<=8*1024*1024,'Ledger file byte bound')
    return json.loads(data.decode('utf-8-sig')),hashlib.sha256(data).hexdigest()

def seed_state(seed,seed_hash):
    require(seed['CLimitBytes']==20*GIB and seed['FLimitBytes']==100*GIB and seed['MinimumFreeBytes']==10*GIB,'Frozen CAP limits')
    observed=dict(evidence=seed['CObservedEvidenceGrowthBytes'],graph=seed['CGraphWholeRootConservativeBytes'],
                  work=seed['FOwnedSessionBytes'],cache=seed['FSharedCacheNetGrowthBytes'],
                  mysql=seed['FMySqlNetGrowthBytes'],wal=seed['FPhase18WalGrowthBytes'])
    require(seed['CConservativeChargedBytes']==observed['evidence']+observed['graph']+seed['CSourceAndFinalReportReservationBytes'],'C seed reconciliation')
    require(seed['FConservativeChargedBytes']==observed['work']+observed['cache']+seed['FMySqlCumulativeObservedPeakGrowthBytes']+observed['wal']+seed['FTransientBuildTestAllowanceBytes'],'F seed reconciliation')
    return dict(schemaVersion=1,sequence=0,seedHash=seed_hash,previousHash=seed_hash,observed=observed,
                charged={'C':seed['CConservativeChargedBytes'],'F':seed['FConservativeChargedBytes']},
                pastAllowances={'C':seed['CSourceAndFinalReportReservationBytes'],'F':seed['FTransientBuildTestAllowanceBytes']})

def advance(prior,observed,free,previous_hash):
    require(set(observed)==set(groups_for(prior)),'Storage group set')
    require(all(type(v) is int for v in observed.values()),'Integer observed bytes')
    require(all(type(free.get(d)) is int and free[d]>=0 for d in ('C','F')),'Volume free bytes')
    delta={k:max(0,observed[k]-prior['observed'][k]) for k in groups_for(prior)}
    charged={d:prior['charged'][d]+sum(delta[k] for k in groups_for(prior) if groups_for(prior)[k]==d) for d in ('C','F')}
    reservation={'C':64*1024**2,'F':5*GIB+(64*1024**2 if prior.get('volumeLayout') else 0)}
    limits=prior.get('storageLimits',dict(C=20*GIB,F=100*GIB))
    met=all(charged[d]+reservation[d]<=limits[d] and free[d]>=10*GIB+reservation[d] for d in ('C','F'))
    result=dict(schemaVersion=1,sequence=prior['sequence']+1,seedHash=prior['seedHash'],previousHash=previous_hash,
                observed=dict(observed),positiveObservedGrowth=delta,charged=charged,pastAllowances=dict(prior['pastAllowances']),
                free=dict(free),nextReservation=reservation,gateMet=met,
                accounting='Historical charged floor retained; only positive observed group changes added. Past unmetered allowances stay charged. Next reservation is not added to consumed bytes. Net observations do not claim exact transient generation.')

    if prior.get('volumeLayout'):
        result.update(schemaVersion=3,volumeLayout=prior['volumeLayout'],storageLimits=prior['storageLimits'],pastUncertain=prior['pastUncertain'])
    return result

def reserve(prior,observed,free,previous_hash,reservation,closed=False):
    """같은 장부의 명시 전환. 현재 점유는 free에 이미 포함되며 미래 증가만 예약."""
    require(reservation['schemaVersion']==2 and reservation['id'] and reservation['scope']=='k6-mod3','Explicit k6 budget transition')
    require(type(reservation['issuedEpochMillis']) is int and reservation['issuedEpochMillis']>0,'Transition timestamp')
    require(isinstance(reservation['pastUncertain'],list) and bool(reservation['pastUncertain']),'Preserve past uncertain costs')
    require(set(reservation['additionalPeak'])==set(groups_for(prior)) and set(reservation['transientAllowance'])=={'C','F'},'All storage scopes reserved')
    require(all(type(v) is int and v>=0 for v in [*reservation['additionalPeak'].values(),*reservation['transientAllowance'].values()]),'Finite nonnegative peak/allowance')
    peak={d:sum(v for k,v in reservation['additionalPeak'].items() if groups_for(prior)[k]==d)+reservation['transientAllowance'][d] for d in ('C','F')}
    old=prior.get('transition'); same=old is not None and old['reservation']['id']==reservation['id']
    policy=reservation.get('storagePolicy')
    limits=prior.get('storageLimits',dict(C=20*GIB,F=100*GIB,trial=32*GIB))
    if policy is not None:
        require(policy.get('kind')==('cap1-storage-50pct-v1' if reservation.get('stage')=='CAP-1' else 's2-storage-50pct-v1'),'Explicit stage storage expansion')
        require(set(policy['usage'])=={'C','F','trial'} and set(policy['limits'])=={'C','F','trial'},'Storage expansion scopes')
        require(policy['limits']=={d:expand_limit(start,policy['usage'][d]) for d,start in dict(C=20*GIB,F=100*GIB,trial=8*GIB).items()},'Frozen storage expansion calculation')
        if not same:
            # 후보 저장·발급 증거의 새 증가를 허용하되 원래 산정 근거와 현재 준비 여유를 각각 검증.
            require(policy==storage_expansion(prior,policy['observed'],previous_hash,reservation),'Storage expansion predecessor/usage')
            current=storage_expansion(prior,observed,previous_hash,reservation)
            require(all(2*current['usage'][d]<policy['limits'][d] for d in ('C','F','trial')),
                    'Storage preparation growth requires new frozen candidate')
        require(reservation['registeredTrialBytes']==policy['limits']['trial'],'Expanded trial binding')
        limits=policy['limits']
    require(type(reservation['registeredTrialBytes']) is int and 0<reservation['registeredTrialBytes']<=limits['trial'] and sum(peak.values())<=reservation['registeredTrialBytes'],'Registered/per-trial peak limit')
    if same:
        require(old['reservation']==reservation and not old['closed'],'Immutable live reservation')
        base=old['baseObserved']; allowance={'C':0,'F':0}
    else:
        require(old is None or old['closed'],'Previous reservation must close')
        if old: require(set(old['reservation']['pastUncertain'])<=set(reservation['pastUncertain']),'Historical uncertainty cannot disappear')
        if prior.get('volumeLayout'): require(set(prior['pastUncertain'])<=set(reservation['pastUncertain']),'Historical uncertainty cannot disappear')
        base=dict(observed); allowance=reservation['transientAllowance']
    result=advance(prior,observed,free,previous_hash)
    for d in ('C','F'):result['charged'][d]+=allowance[d]
    remaining={d:0 if closed else sum(max(0,v-max(0,observed[k]-base[k])) for k,v in reservation['additionalPeak'].items() if groups_for(prior)[k]==d)+reservation['transientAllowance'][d] for d in ('C','F')}
    # 소비된 transient allowance는 charged에 한 번만 포함. 여유 검사는 재생성 peak를 별도 포함.
    charge_reserve={d:0 if closed else sum(max(0,v-max(0,observed[k]-base[k])) for k,v in reservation['additionalPeak'].items() if groups_for(prior)[k]==d) for d in ('C','F')}
    # 같은 trial의 고정 기준 대비 점유 + 아직 필요한 예약. root별 예약은 독립 상한 아님.
    occupied=sum(max(0,observed[k]-base[k]) for k in groups_for(prior))
    required_peak=occupied+sum(charge_reserve.values())+sum(reservation['transientAllowance'].values())
    violation=prior.get('trialBudget',{}).get('violation') if same else None
    if violation is None and required_peak>reservation['registeredTrialBytes']:
        violation=dict(reason='registered_trial_limit_exceeded',firstSequence=result['sequence'],
                       requiredPeakBytes=required_peak,registeredTrialBytes=reservation['registeredTrialBytes'])
    # trial 초과만 고정. 여유 부족 등 다른 실패와 구분하며 감소/close 뒤에도 최초 사유 보존.
    result.update(schemaVersion=3 if prior.get('volumeLayout') else 2,transition=dict(reservation=reservation,baseObserved=base,closed=closed),remainingPeak=remaining,
                  trialBudget=dict(additionalObservedBytes=occupied,requiredPeakBytes=required_peak,violation=violation),
                  accounting='Historical charged floor/allowances/uncertainty retained. Positive group growth never refunded. New transient allowance charged once. Current occupied bytes are already reflected in free; only additional remaining peak and next reservation deducted.')
    result['gateMet']=violation is None and all(result['charged'][d]+charge_reserve[d]+result['nextReservation'][d]<=limits[d] and free[d]-remaining[d]-result['nextReservation'][d]>=10*GIB for d in ('C','F'))
    if prior.get('volumeLayout'):result['storageLimits']=limits
    return result


def relocate_layout(prior,observed,free,previous_hash,copied_bytes,preparation_growth=None):
    """기존 chain에 한 번만 F 배치 연결. 과거 소비/allowance 환급과 trial 발급 없음."""
    require(not prior.get('volumeLayout'),'F volume layout already adopted')
    require(prior.get('transition',{}).get('closed',False),'Close current reservation before relocation')
    require(set(observed)==set(F_GROUPS) and all(type(v) is int and v>=0 for v in observed.values()),'F relocation observations')
    require(type(copied_bytes) is int and copied_bytes>=0,'Verified relocation copy bytes')
    limits=prior['transition']['reservation'].get('storagePolicy',{}).get('limits',dict(C=20*GIB,F=100*GIB,trial=32*GIB))
    preparation_growth=preparation_growth or dict(C=0,F=0)
    require(set(preparation_growth)=={'C','F'} and all(type(v) is int and v>=0 for v in preparation_growth.values()),'Finite preparation growth')
    charged={d:prior['charged'][d]+preparation_growth[d] for d in ('C','F')};charged['F']+=copied_bytes
    next_reservation=dict(C=64*1024**2,F=5*GIB+64*1024**2)
    return dict(schemaVersion=3,sequence=prior['sequence']+1,seedHash=prior['seedHash'],previousHash=previous_hash,
        volumeLayout='f-output-v1',layoutTransition=dict(copiedBytes=copied_bytes,preparationGrowth=preparation_growth,previousObserved=prior['observed']),
        observed=dict(observed),positiveObservedGrowth={k:0 for k in F_GROUPS},charged=charged,
        pastAllowances=dict(prior['pastAllowances']),pastUncertain=list(prior['transition']['reservation']['pastUncertain']),
        storageLimits=limits,free=dict(free),nextReservation=next_reservation,
        gateMet=all(charged[d]+next_reservation[d]<=limits[d] and free[d]-next_reservation[d]>=10*GIB for d in ('C','F')),
        accounting='Explicit F layout baseline; historical C/F charges and uncertainty retained. Verified copy writes charged to F once. Occupancy changes never refund history. No trial or service time issued.')

def adopt_c_graph(prior,observed,free,previous_hash,known_growth,external_growth):
    """C Graphify 복원 이후의 명시 후속 배치. 과거 replay와 소비 하한 보존."""
    require(prior.get('volumeLayout')=='f-output-v1','C graph successor requires f-output-v1 exactly once')
    require('transition' not in prior or prior['transition']['closed'],'Close current reservation before C graph successor')
    require(set(observed)==set(C_GRAPH_GROUPS) and set(known_growth)==set(C_GRAPH_GROUPS),'C graph successor scopes')
    require(set(external_growth)=={'C','F'} and set(free)=={'C','F'},'C graph successor volume scopes')
    require(all(type(v) is int and 0<=v<=2**63-1 for v in [*observed.values(),*known_growth.values(),*external_growth.values(),*free.values()]),'Finite C graph successor bytes')
    # graph의 이전 관측은 다른 볼륨의 파일. 현재 C 복원 쓰기의 보수 하한을 한 번 연결.
    require(known_growth['graph']>=observed['graph'],'C graph restored write evidence required')
    delta={k:max(known_growth[k],max(0,observed[k]-prior['observed'][k]) if k!='graph' else 0) for k in C_GRAPH_GROUPS}
    charged={d:prior['charged'][d]+external_growth[d]+sum(delta[k] for k in C_GRAPH_GROUPS if C_GRAPH_GROUPS[k]==d) for d in ('C','F')}
    require(all(v<=2**63-1 for v in charged.values()),'C graph successor charge overflow')
    nxt=dict(C=64*1024**2,F=5*GIB+64*1024**2);limits=prior['storageLimits']
    return dict(schemaVersion=3,sequence=prior['sequence']+1,seedHash=prior['seedHash'],previousHash=previous_hash,
        volumeLayout=C_GRAPH_LAYOUT,layoutSuccessor=dict(previousLayout=prior['volumeLayout'],previousObserved=prior['observed'],knownPositiveGrowth=known_growth,externalPositiveGrowth=external_growth),
        observed=dict(observed),positiveObservedGrowth=delta,charged=charged,pastAllowances=dict(prior['pastAllowances']),
        pastUncertain=list(prior['pastUncertain']),storageLimits=dict(limits),free=dict(free),nextReservation=nxt,
        gateMet=all(charged[d]+nxt[d]<=limits[d] and free[d]-nxt[d]>=10*GIB for d in ('C','F')),
        accounting='Explicit C Graphify successor. Disjoint known positive writes and positive net growth use their maximum per group; external writes are separate. No refund, historical replay change, trial, or expiry extension.')


def f_observation(repo,deadline):
    """분리된 실제 root만 합산. archive와 최신 graph를 별도 계상하며 링크 추적 없음."""
    from pixel_place_paths import EVIDENCE,ARCHIVE,GRAPH
    project=Path('F:/pixel-place-work/projects/pixel-place')
    paths=dict(evidence=EVIDENCE,graph=GRAPH,work=Path('F:/pixel-place-work/sessions'),
        cache=Path('F:/pixel-place-work/gradle-home'),mysql=Path('F:/MySQL/test'),wal=Path('F:/pixel-place-wal/phase18'),archive=ARCHIVE)
    observed={k:directory_bytes(v,deadline) for k,v in paths.items()}
    observed['project']=sum(directory_bytes(project/area,deadline) for area in ('build','project-cache','temp','jna','control'))
    audit=[]
    observed['retained']=directory_bytes(Path(repo)/'build/agent-runs',deadline,historical=True,link_audit=audit)
    return observed,audit

def load_chain(root):
    root=control_path(root)
    seed,seed_hash=read(root/'cap-budget.json');state=seed_state(seed,seed_hash);previous_hash=seed_hash
    # 초기화는 별도 명시 작업. marker/tip 부재를 최초 사용으로 추정하지 않음
    initialized,_=read(root/'cap-storage-initialized.json')
    require(initialized['seedHash']==seed_hash,'Ledger initialization seed mismatch')
    anchor_path=control_path(initialized['anchor'])
    require(anchor_path.is_absolute() and not anchor_path.is_relative_to(root.resolve()),'Independent absolute ledger anchor required')
    require(not anchor_path.with_name(anchor_path.name+'.pending').exists(),'Ledger interrupted anchor write')
    anchor,_=read(anchor_path)
    require(anchor['initializationId']==initialized['initializationId'] and anchor['seedHash']==seed_hash,'Ledger anchor identity mismatch')
    require(anchor['status']=='committed','Ledger interrupted transaction; no automatic recovery')
    records=sorted(root.glob('cap-storage-ledger-*.json'))
    require(len(records)<=4096,'Ledger entry bound')
    require(len(records)==anchor['sequence'],'Ledger suffix loss/extra record')
    require(len(list(root.glob('cap-storage-intent-*.json')))==len(records) and len(list(root.glob('cap-storage-commit-*.json')))==len(records),'Ledger interrupted transaction witnesses')
    for i,path in enumerate(records,1):
        require(path.name==f'cap-storage-ledger-{i:06d}.json','Ledger sequence gap')
        actual,actual_hash=read(path)
        if 'layoutSuccessor' in actual:
            change=actual['layoutSuccessor']
            expected=adopt_c_graph(state,actual['observed'],actual['free'],previous_hash,change['knownPositiveGrowth'],change['externalPositiveGrowth'])
        elif 'layoutTransition' in actual:
            expected=relocate_layout(state,actual['observed'],actual['free'],previous_hash,actual['layoutTransition']['copiedBytes'],actual['layoutTransition']['preparationGrowth'])
        elif 'transition' in actual:
            expected=reserve(state,actual['observed'],actual['free'],previous_hash,actual['transition']['reservation'],actual['transition']['closed'])
        else:
            expected=advance(state,actual['observed'],actual['free'],previous_hash)
        require(state.get('schemaVersion')!=2 or actual.get('schemaVersion') in (2,3),'Budget transition cannot revert')
        require(not state.get('volumeLayout') or actual.get('volumeLayout')==state['volumeLayout'] or
                state['volumeLayout']=='f-output-v1' and actual.get('volumeLayout')==C_GRAPH_LAYOUT and 'layoutSuccessor' in actual,'Volume layout cannot revert')
        if actual.get('schemaVersion')==2 and 'trialBudget' not in actual:
            # 구형 정상 기록의 bytes/hash 보존. 초과 누락 기록을 정상으로 재해석하지 않음.
            require(expected['trialBudget']['violation'] is None,'Historical trial limit violation')
            expected.pop('trialBudget')
        require(all(actual.get(k)==v for k,v in expected.items()),'Ledger chain/charge mismatch')
        intent,_=read(root/f'cap-storage-intent-{i:06d}.json');committed,_=read(root/f'cap-storage-commit-{i:06d}.json')
        require(intent==committed and intent==dict(sequence=i,sha256=actual_hash,previousHash=previous_hash,initializationId=initialized['initializationId']),'Ledger transaction witness mismatch')
        state=actual;previous_hash=actual_hash
    require(anchor['sha256']==previous_hash,'Ledger committed tip mismatch')
    return state,previous_hash

def durable_new(path,value):
    data=(json.dumps(value,indent=2)+'\n').encode('utf-8')
    with Path(path).open('xb') as stream:stream.write(data);stream.flush();os.fsync(stream.fileno())
    return hashlib.sha256(data).hexdigest()

def replace_anchor(path,value):
    # 교체 전 상태는 intent/commit 증거에 보존. 중단 시 미확정 상태를 소비 환급으로 복구하지 않음
    temporary=path.with_name(path.name+'.pending')
    durable_new(temporary,value)
    os.replace(temporary,path)

def initialize_chain(root,anchor):
    root=Path(root).resolve();anchor=Path(anchor).absolute()
    require(not anchor.is_relative_to(root),'Anchor must be outside ledger directory')
    require(not list(root.glob('cap-storage-*')) and not anchor.exists() and not anchor.with_name(anchor.name+'.pending').exists(),'Ledger already initialized or uncertain; initialization forbidden')
    seed,seed_hash=read(root/'cap-budget.json');seed_state(seed,seed_hash)
    initialization_id=uuid.uuid4().hex
    durable_new(root/'cap-storage-initialized.json',dict(seedHash=seed_hash,anchor=str(anchor),initializationId=initialization_id))
    durable_new(anchor,dict(seedHash=seed_hash,initializationId=initialization_id,status='committed',sequence=0,sha256=seed_hash))

def append_record(root,result,interrupt=None):
    root=control_path(root);prior,prior_hash=load_chain(root)
    require(result['sequence']==prior['sequence']+1 and result['previousHash']==prior_hash,'Ledger append predecessor mismatch')
    initialized,_=read(root/'cap-storage-initialized.json');anchor=control_path(initialized['anchor'])
    require(not anchor.with_name(anchor.name+'.pending').exists(),'Ledger anchor pending write')
    sequence=result['sequence'];data=(json.dumps(result,indent=2)+'\n').encode('utf-8');digest=hashlib.sha256(data).hexdigest()
    witness=dict(sequence=sequence,sha256=digest,previousHash=prior_hash,initializationId=initialized['initializationId'])
    def checkpoint(name):
        if interrupt:interrupt(name)
    durable_new(root/f'cap-storage-intent-{sequence:06d}.json',witness);checkpoint('intent')
    tip=dict(seedHash=result['seedHash'],initializationId=initialized['initializationId'],status='prepared',sequence=sequence,sha256=digest)
    replace_anchor(anchor,tip);checkpoint('prepared')
    path=root/f'cap-storage-ledger-{sequence:06d}.json';durable_new(path,result);checkpoint('record')
    tip['status']='committed';replace_anchor(anchor,tip);checkpoint('anchor')
    durable_new(root/f'cap-storage-commit-{sequence:06d}.json',witness)
    return path,digest

def is_reparse(info):
    return stat.S_ISLNK(info.st_mode) or bool(getattr(info,'st_file_attributes',0)&REPARSE_ATTRIBUTE)

def plain_path(path,directory,allow_missing=False):
    """전체 경로의 lstat 검증. 존재하지 않는 경로와 dangling link를 구분함."""
    path=Path(os.path.abspath(path))
    for node in [*reversed(path.parents),path]:
        try:
            info=node.lstat()
        except FileNotFoundError:
            if allow_missing:return None
            raise
        require(not is_reparse(info),'Storage reparse path')
        expected_directory=node!=path or directory
        require(stat.S_ISDIR(info.st_mode) if expected_directory else stat.S_ISREG(info.st_mode),
                'Storage unexpected path kind')
    return info

def file_identity(info):
    return info.st_dev,info.st_ino,info.st_mode,info.st_size,info.st_mtime_ns,info.st_ctime_ns

def opened_file_identity(info):
    # Windows lstat/fstat의 ctime 의미 차이 분리. 각 API 내부의 전후 ctime 대조는 유지.
    require(info.st_dev!=0 and info.st_ino>0,'Storage file identity unavailable')
    return info.st_dev,info.st_ino,info.st_mode,info.st_size,info.st_mtime_ns

def read_historical_target(target,target_info):
    require(target_info.st_size==len(HISTORICAL_CONTENT),'Storage historical target content changed')
    with target.open('rb') as stream:
        opened=os.fstat(stream.fileno())
        require(opened_file_identity(opened)==opened_file_identity(target_info),
                'Storage historical target changed before read')
        require(stream.read(len(HISTORICAL_CONTENT)+1)==HISTORICAL_CONTENT,
                'Storage historical target content changed')
        require(file_identity(os.fstat(stream.fileno()))==file_identity(opened),
                'Storage historical opened target changed during read')
    require(file_identity(plain_path(target,False))==file_identity(target_info),
            'Storage historical target path changed after read')
    return opened

def historical_link(root,path,info):
    """고정 fixture의 링크를 읽기만 함. 대상은 원래 경로에서 일반 파일로 별도 계상."""
    require(path==root/HISTORICAL_LINK,'Storage reparse child')
    require(stat.S_ISLNK(info.st_mode),'Storage historical fixture is not a symbolic link')
    if os.name=='nt':
        require(getattr(info,'st_reparse_tag',None)==SYMLINK_TAG,'Storage unexpected reparse tag')
    target=root/HISTORICAL_TARGET
    plain_path(path.parent,True)
    # readlink 문자열만 비교하며 resolve/stat으로 링크를 따라가지 않음.
    raw=os.readlink(path)
    normalized=raw[4:] if raw.startswith('\\\\?\\') else raw
    require(os.path.isabs(normalized),'Storage historical target must be absolute')
    require(os.path.normcase(os.path.normpath(normalized))==os.path.normcase(str(target)),
            'Storage historical target changed')
    target_info=plain_path(target,False)
    # SegmentedWalFileSetTest의 보존 계약. 대상의 일반 경로만 읽고 handle/path 전후 대조.
    opened=read_historical_target(target,target_info)
    require(file_identity(path.lstat())==file_identity(info) and os.readlink(path)==raw,
            'Storage historical link changed during observation')
    return dict(path=str(path),target=str(target),rawTarget=raw,kind='symbolic-link',
                followed=False,linkLogicalBytes=info.st_size,targetLogicalBytes=target_info.st_size,
                targetContentSha256=hashlib.sha256(HISTORICAL_CONTENT).hexdigest(),
                targetCountedAt=str(HISTORICAL_TARGET),identity=list(file_identity(info)),
                targetIdentity=list(file_identity(target_info)),
                targetOpenedIdentity=list(file_identity(opened)),
                identityPolicy='cross-api: dev/ino/mode/size/mtime; same-api before/after: also ctime')

def directory_bytes(root,deadline,historical=False,link_audit=None):
    root=Path(os.path.abspath(root))
    require(time.time()*1000<deadline,'CAP storage observation deadline')
    if plain_path(root,True,allow_missing=True) is None:
        require(not historical,'Storage historical evidence root missing')
        return 0
    total=0;pending=[root];accepted=[];target_count=0;counted_target=None
    while pending:
        require(time.time()*1000<deadline,'CAP storage observation deadline')
        folder=pending.pop()
        plain_path(folder,True)
        with os.scandir(folder) as entries:
            for entry in entries:
                require(time.time()*1000<deadline,'CAP storage observation deadline')
                path=Path(entry.path)
                # Windows DirEntry.stat의 0 file ID/캐시를 identity 근거로 사용하지 않음.
                info=path.lstat()
                if is_reparse(info):
                    require(historical,'Storage reparse child')
                    accepted.append(historical_link(root,path,info))
                    total+=info.st_size
                elif stat.S_ISDIR(info.st_mode):
                    pending.append(path)
                elif stat.S_ISREG(info.st_mode):
                    total+=info.st_size
                    if historical and path==root/HISTORICAL_TARGET:
                        target_count+=1
                        counted_target=list(file_identity(info))
                else:
                    raise ValueError('Storage unexpected child kind')
    if historical:
        require(len(accepted)==1 and target_count==1,'Storage historical fixture missing or uncounted')
        require(counted_target==accepted[0]['targetIdentity'],'Storage counted target changed during observation')
        # 탐색 중 삭제/대상 교체가 감춰지지 않도록 마지막에 다시 대조.
        current=historical_link(root,root/HISTORICAL_LINK,(root/HISTORICAL_LINK).lstat())
        require(current==accepted[0],'Storage historical fixture changed during observation')
        if link_audit is not None:link_audit.extend(accepted)
    require(time.time()*1000<deadline,'CAP storage observation deadline')
    return total

def storage_observation(paths,base,deadline):
    """evidence root에서만 고정 fixture 허용. 나머지 다섯 root는 기존 strict 거부 유지."""
    audit=[]
    observed={key:directory_bytes(path,deadline,historical=(key=='evidence'),link_audit=audit)-base[key]
              for key,path in paths.items()}
    return observed,audit

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--ledger-root',required=True)
    parser.add_argument('--initialize-anchor',type=Path)
    parser.add_argument('--reservation',type=Path)
    parser.add_argument('--close-reservation',action='store_true')
    parser.add_argument('--observe-current',action='store_true')
    for name in ('repo','output','execution-expiry'):parser.add_argument('--'+name)
    args=parser.parse_args();root=control_path(args.ledger_root)
    if args.initialize_anchor:
        initialize_chain(root,args.initialize_anchor);return 0
    if args.observe_current:
        return observe_current(root,args.repo,args.output)
    require(all((args.repo,args.output,args.execution_expiry)),'Observation requires repo/output/expiry')
    from pixel_place_paths import plain_absolute,EVIDENCE
    require(plain_absolute(args.output).is_relative_to(EVIDENCE),'New storage output requires F evidence root')
    repo=Path(args.repo);expiry=int(args.execution_expiry)
    require(os.name=='nt','Actual C/F budget observation requires Windows')
    require((root/'cap-controller.lock').is_file(),'Exclusive CAP controller must own the ledger')
    state,previous_hash=load_chain(root)
    baseline,_=read(root/'environment-before.json');b=baseline['sizes']
    paths={'evidence':repo/'build/agent-runs','graph':repo/'graphify-out','work':Path('F:/pixel-place-work/sessions'),
           'cache':Path('F:/pixel-place-work/gradle-home'),'mysql':Path('F:/MySQL/test'),'wal':Path('F:/pixel-place-wal/phase18')}
    base={'evidence':b['evidence'],'graph':0,'work':b['workSessions'],'cache':b['gradleCache'],'mysql':b['mysqlTest'],'wal':b['walPhase18']}
    require(not state.get('volumeLayout') or state['volumeLayout']==C_GRAPH_LAYOUT,'Current C Graphify requires explicit layout successor')
    observed,link_audit=f_observation(repo,expiry) if state.get('volumeLayout') else storage_observation(paths,base,expiry)
    import shutil
    free={d:shutil.disk_usage(d+':/').free for d in ('C','F')}
    require(args.reservation is not None or state.get('schemaVersion')!=2,'Transition reservation required; no legacy fallback')
    require(not args.close_reservation or args.reservation is not None,'Close needs original reservation')
    if args.reservation:
        reservation,_=read(args.reservation)
        require(reservation['issuedEpochMillis']<=time.time()*1000<expiry,'Current transition/expiry')
        result=reserve(state,observed,free,previous_hash,reservation,args.close_reservation)
    else:result=advance(state,observed,free,previous_hash)
    result['storageScan']={'version':3,'historicalLinks':link_audit,
                           'measurement':'Logical file lengths; link target counted once at ordinary path. Not allocated clusters or exact cumulative temporary generation.'}
    result['observedEpochMillis']=int(time.time()*1000)
    require(result['observedEpochMillis']<expiry,'CAP storage observation deadline')
    path,result_hash=append_record(root,result)
    with Path(args.output).open('x',encoding='utf-8') as stream:json.dump(dict(ledger=str(path),sha256=result_hash,**result),stream,indent=2)
    return 0 if result['gateMet'] else 1

def observe_current(root,repo,output):
    """수동 정리 전후 현장 관측만 기존 chain에 연결. trial/서비스 발급·만료 연장 없음."""
    import ctypes
    import shutil
    from pixel_place_paths import plain_absolute,EVIDENCE
    require(os.name=='nt' and repo and output,'Windows maintenance observation inputs')
    target=plain_absolute(output)
    require(target.is_relative_to(EVIDENCE) and not target.exists(),'New F observation receipt required')
    root=plain_absolute(root)
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.CreateFileW.argtypes=[ctypes.c_wchar_p,ctypes.c_ulong,ctypes.c_ulong,ctypes.c_void_p,ctypes.c_ulong,ctypes.c_ulong,ctypes.c_void_p]
    kernel.CreateFileW.restype=ctypes.c_void_p
    kernel.CloseHandle.argtypes=[ctypes.c_void_p]
    guard=kernel.CreateFileW(str(root/'cap-preparation-close.guard'),0xC0000000,0,None,4,0,None)
    require(guard!=ctypes.c_void_p(-1).value,'Exclusive CAP maintenance guard unavailable')
    try:
        require(not (root/'cap-controller.lock').exists(),'CAP controller requires ownership investigation')
        state,tip=load_chain(root)
        require(state.get('volumeLayout')==C_GRAPH_LAYOUT,'Explicit C Graphify layout successor required')
        require('transition' not in state or state['transition']['closed'],'Active reservation cannot be observed as maintenance')
        observed,audit=f_observation(repo,int(time.time()*1000)+120000)
        free={d:shutil.disk_usage(d+':/').free for d in ('C','F')}
        result=advance(state,observed,free,tip)
        result['maintenanceObservation']=dict(servicesStarted=0,trialsIssued=0,expiryExtended=False,historicalLinks=audit)
        path,digest=append_record(root,result)
        durable_new(target,dict(ledger=str(path),sha256=digest,**result))
        return 0 if result['gateMet'] else 1
    finally:
        kernel.CloseHandle(guard)


if __name__=='__main__':raise SystemExit(main())
