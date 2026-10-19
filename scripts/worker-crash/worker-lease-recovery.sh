#!/usr/bin/env bash
# Worker 비정상 종료 시나리오. 실행 방법은 같은 폴더의 README.md에 있다.
# 처리 중인 Worker 1을 SIGKILL하고, lease 만료 후 같은 Job을 Worker 2가 완료하는지 확인한다.
# DB는 조회만 한다. lease·상태를 수정하거나 회수 프로시저를 직접 호출하지 않는다.
set -euo pipefail
command -v python3 >/dev/null 2>&1 || { echo 'python3가 필요합니다.' >&2; exit 1; }
exec python3 - "$@" <<'PYTHON'
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time

DESCRIPTION = '''Worker 1 SIGKILL → 실제 lease 만료/cron 회수 → Worker 2 재claim → 완료 검증.

전제: 이 DB 큐를 소비하는 Worker는 지정한 두 대뿐이고, 테스트 동안 문서 하나만
업로드합니다. 기존 미종결 Job이 있으면 시작하지 않습니다. Worker 2를 잠시 정상 종료하여
Worker 1만 최초 작업을 받고, cron이 PENDING으로 회수한 것을 확인한 뒤 다시 시작합니다.
Worker 1은 종료 상태로 남습니다. 오류/Ctrl-C에서도 이 스크립트가 종료한 Worker 2는
재개를 시도합니다. 실행 중 터미널 자체를 SIGKILL하면 자동 정리는 보장되지 않습니다.

필수 명령: docker, psql, python3. DB는 쓰기 Primary로 연결해야 합니다.
비밀번호는 ~/.pgpass 또는 PGPASSWORD 사용(스크립트/인자에 기록하지 않음).
기본 컨테이너는 aidocs-dev-worker-1/2이고, DB 접속은 localhost:15432/aidocs입니다.

예:
  bash scripts/worker-crash/worker-lease-recovery.sh
  WORKER1_CONTAINER=my-worker-1 WORKER2_CONTAINER=my-worker-2 \\
    DB_PORT=15432 bash scripts/worker-crash/worker-lease-recovery.sh --kill-phase EMBEDDING

실행 안내가 나온 뒤 웹에서 새 문서를 하나 업로드하세요. EMBEDDING이 너무 짧으면
충분한 분량의 문서로 다시 실행하거나 --kill-phase PARSING / ANY를 사용하세요.
각 Worker의 worker-id는 서로 달라야 합니다. 두 Worker의 FAULT_INJECTION_ENABLED는
false로 두고, 원문 저장소와 임베딩 API에 정상 접속할 수 있어야 합니다.

종료 코드: 0=검증 성공, 1=실패/환경 오류, 2=관측 부족·경합으로 판정 불가, 130=중단.
빠르게 완료되어 재claim을 관측하지 못하면 성공으로 처리하지 않습니다.
'''

parser = argparse.ArgumentParser(prog='worker-lease-recovery.sh', description=DESCRIPTION,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument('--worker1', default=os.getenv('WORKER1_CONTAINER', 'aidocs-dev-worker-1'))
parser.add_argument('--worker2', default=os.getenv('WORKER2_CONTAINER', 'aidocs-dev-worker-2'))
parser.add_argument('--kill-phase', default=os.getenv('KILL_PHASE', 'EMBEDDING'),
                    choices=['ANY', 'DOWNLOADING', 'PARSING', 'CHUNKING', 'EMBEDDING'])
parser.add_argument('--poll-interval', type=float, default=0.25)
parser.add_argument('--upload-timeout', type=int, default=300)
parser.add_argument('--recovery-timeout', type=int, default=300,
                    help='SIGKILL 이후 lease 만료+cron 회수 대기 상한(초)')
parser.add_argument('--completion-timeout', type=int, default=600)
parser.add_argument('--output-dir', help='새 결과 디렉터리(이미 존재하면 거절)')
parser.add_argument('--yes', action='store_true', help='시작 전 안내 확인 생략')
args = parser.parse_args()
if min(args.poll_interval, args.upload_timeout, args.recovery_timeout, args.completion_timeout) <= 0:
    parser.error('주기와 timeout은 양수여야 합니다.')
if args.worker1 == args.worker2:
    parser.error('서로 다른 두 컨테이너를 지정하세요.')

class StopTest(Exception):
    def __init__(self, message, code=1):
        super().__init__(message)
        self.code = code

def say(text):
    print(text, flush=True)

def run(command, *, stdin=None, timeout=15):
    try:
        proc = subprocess.run(command, input=stdin, text=True, capture_output=True,
                              timeout=timeout)
    except subprocess.TimeoutExpired:
        raise StopTest(f'명령 시간 초과: {command[0]}')
    if proc.returncode:
        raise StopTest(f'{command[0]} 실패: {proc.stderr.strip() or proc.stdout.strip()}')
    return proc.stdout.strip()

schema = os.getenv('DB_SCHEMA', 'public')
if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', schema):
    parser.error('DB_SCHEMA는 영문/숫자/밑줄로 지정하세요.')
os.environ.setdefault('PGCONNECT_TIMEOUT', '5')
os.environ['PGOPTIONS'] = (os.environ.get('PGOPTIONS', '') +
                          ' -c statement_timeout=5000 -c lock_timeout=2000')
PSQL = ['psql', '-X', '-w', '-qAt', '-v', 'ON_ERROR_STOP=1',
        '-h', os.getenv('DB_HOST', 'localhost'), '-p', os.getenv('DB_PORT', '15432'),
        '-U', os.getenv('DB_USER', 'aidocs'), '-d', os.getenv('DB_NAME', 'aidocs'),
        '-v', 'schema=' + schema]

def query(sql, **parameters):
    command = PSQL[:]
    for key, value in parameters.items():
        command.extend(['-v', f'{key}={value}'])
    # OpenProxy는 연결마다 쓰기 대상(Primary)을 지정한다. 새 psql 세션의 첫 문장으로 보내야 한다.
    sql = "SET SERVER ROLE TO 'default';\n" + sql
    raw = run(command, stdin=sql, timeout=12)
    try:
        return json.loads(raw) if raw else None
    except json.JSONDecodeError:
        raise StopTest('DB JSON 응답 해석 실패. psql 접속/출력 설정을 확인하세요.')

def container(name):
    try:
        return json.loads(run(['docker', 'inspect', '--format', '{{json .}}', name]))
    except json.JSONDecodeError:
        raise StopTest('docker inspect 응답 해석 실패')

def active(info):
    return info['State']['Running'] and not info['State']['Paused']

out = None
stopped_by_us = False
kill_sent = False
job_id = None
baseline = None
cutoff = ''
before = None
after = None
handoff = None
initial1 = None
initial2 = None
expected_worker2_start = None
last_signature = None
start_iso = dt.datetime.now(dt.timezone.utc).isoformat()

def save(name, data):
    if out is not None:
        (out / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def snapshot(stage):
    # 같은 Job의 실행권과 DB 시각을 함께 기록한다. lease 만료 판정은 로컬 시계를 쓰지 않는다.
    global last_signature
    row = query('''
SELECT json_build_object(
 'id',j.id,'source_event_id',j.source_event_id,'document_id',j.document_id,
 'document_version_id',j.document_version_id,'job_type',j.job_type,
 'enqueue_seq',j.enqueue_seq,'status',j.status,'phase',j.phase,
 'worker_id',j.worker_id,'lease_until',j.lease_until,
 'execution_epoch',j.execution_epoch,'document_epoch',d.execution_epoch,
 'attempt_count',j.attempt_count,'next_retry_at',j.next_retry_at,
 'outcome',j.outcome,'completed_at',j.completed_at,'updated_at',j.updated_at,
 'last_error_code',j.last_error_code,'last_error_message',j.last_error_message,
 'db_now',clock_timestamp(),
 'lease_expired',j.lease_until <= clock_timestamp(),
 'killed_lease_expired',clock_timestamp() >= NULLIF(:'cutoff','')::timestamptz)
FROM :"schema".indexing_job j JOIN :"schema".document d ON d.id=j.document_id
WHERE j.id=:'job_id'::bigint;
''', job_id=job_id, cutoff=cutoff)
    if row is None:
        raise StopTest(f'대상 Job 또는 문서가 사라졌습니다: jobId={job_id}')
    with (out / 'timeline.jsonl').open('a', encoding='utf-8') as f:
        f.write(json.dumps({'stage': stage, **row}, ensure_ascii=False) + '\n')
    if before:
        for key in ('id', 'source_event_id', 'document_id', 'document_version_id', 'enqueue_seq', 'job_type'):
            if row[key] != before[key]:
                raise StopTest(f'동일 Job 식별 정보가 변경됐습니다: {key}')
    signature = tuple(row.get(k) for k in ('status','phase','worker_id','execution_epoch','lease_expired'))
    if signature != last_signature:
        say(f"  {row['db_now']} | {row['status']} | {row['phase']} | "
            f"owner={row['worker_id']} | epoch={row['execution_epoch']} | "
            f"attempt={row['attempt_count']} | lease={row['lease_until']}")
        last_signature = signature
    return row

def worker1_stays_stopped():
    # Worker 1이 다시 뜨면 다른 Worker가 인계했다는 전제를 유지할 수 없다.
    current = container(args.worker1)
    if current['Id'] != initial1['Id'] or current['State']['Running'] or current['State']['Restarting']:
        raise StopTest('Worker 1이 다시 시작되거나 교체되었습니다. 다른 Worker 인계 판정 불가.', 2)

def worker2_unchanged(expect_running):
    current = container(args.worker2)
    if (current['Id'] != initial2['Id'] or current['State']['Running'] != expect_running or
        current['State']['Paused'] or current['State']['Restarting']):
        raise StopTest('Worker 2의 실행 상태가 예상과 다릅니다.', 2)
    if expect_running and expected_worker2_start and current['State']['StartedAt'] != expected_worker2_start:
        raise StopTest('Worker 2가 테스트 도중 재시작됐습니다.', 2)

def resume_worker2():
    global stopped_by_us, expected_worker2_start
    if stopped_by_us:
        current = container(args.worker2)
        if current['Id'] != initial2['Id']:
            raise StopTest('Worker 2가 교체되어 자동 시작하지 않았습니다.')
        if current['State']['Paused']:
            raise StopTest('Worker 2가 외부에서 pause되어 자동 변경하지 않았습니다.')
        if not current['State']['Running']:
            run(['docker', 'start', args.worker2], timeout=45)
        expected_worker2_start = container(args.worker2)['State']['StartedAt']
        stopped_by_us = False

def interrupted(signum, frame):
    raise StopTest(f'사용자 중단(signal={signum})', 130)

signal.signal(signal.SIGINT, interrupted)
signal.signal(signal.SIGTERM, interrupted)
code = 1
message = '검증 시작 전'
try:
    for tool in ('docker', 'psql'):
        if not shutil.which(tool):
            raise StopTest(f'{tool} 명령이 필요합니다.')
    say('[1/7] 두 Worker와 Primary DB 확인')
    initial1, initial2 = container(args.worker1), container(args.worker2)
    if initial1['Id'] == initial2['Id']:
        raise StopTest('두 이름이 같은 컨테이너를 가리킵니다.')
    if not active(initial1) or not active(initial2):
        raise StopTest('두 Worker 모두 실행 중이며 pause되지 않은 상태여야 합니다.')
    health = query('''SELECT json_build_object('standby',pg_is_in_recovery(),
 'read_only',current_setting('transaction_read_only'), 'database',current_database());''')
    if health['standby'] or health['read_only'] != 'off':
        raise StopTest('쓰기 가능한 Primary DB로 연결하세요. 현재 연결은 standby 또는 read-only입니다.')
    # 컨테이너를 멈추기 전에 뒤에서 조회할 테이블·컬럼과 접근 권한을 확인한다.
    query('''SELECT COALESCE(json_agg(x),'[]'::json) FROM (
 SELECT j.id,j.source_event_id,j.document_id,j.document_version_id,j.job_type,j.enqueue_seq,
 j.status,j.phase,j.worker_id,j.lease_until,j.execution_epoch,j.attempt_count,j.next_retry_at,
 j.outcome,j.completed_at,j.updated_at,j.last_error_code,j.last_error_message,d.execution_epoch
 FROM :"schema".indexing_job j JOIN :"schema".document d ON d.id=j.document_id LIMIT 0) x;''')
    query('''SELECT COUNT(*) FROM (SELECT document_version_id,chunk_no
 FROM :"schema".document_chunk LIMIT 0) x;''')
    queued = query('''SELECT COUNT(*) FROM :"schema".indexing_job
 WHERE status IN ('PENDING','PROCESSING','RETRY_WAIT');''')
    if queued:
        raise StopTest(f'미종결 Job {queued}건이 있습니다. 기존 작업이 끝난 뒤 실행하세요.')
    say(f'  대상: {args.worker1} (SIGKILL), {args.worker2} (정상 종료 후 다시 시작)')
    say('  이 큐를 소비하는 Worker가 위 두 대뿐인지 확인하세요. 테스트 중 다른 업로드/재처리는 금지합니다.')
    if not args.yes:
        say('  확인했으면 ENTER. 취소는 Ctrl-C.')
        try:
            with open('/dev/tty', 'r') as tty:
                tty.readline()
        except OSError:
            raise StopTest('대화형 터미널이 없습니다. 전제를 확인한 뒤 --yes로 실행하세요.')
    directory = args.output_dir or ('worker-recovery-' + dt.datetime.now().strftime('%Y%m%d-%H%M%S') + '-' + str(os.getpid()))
    candidate_out = Path(directory).resolve()
    candidate_out.mkdir(parents=True, exist_ok=False)
    out = candidate_out
    save('environment.json', {'worker1': args.worker1, 'worker2': args.worker2,
         'worker1_container_id': initial1['Id'], 'worker2_container_id': initial2['Id'],
         'database': health['database'], 'schema': schema, 'kill_phase': args.kill_phase,
         'only_two_consumers': 'operator_confirmed', 'started_at': start_iso})

    say('[2/7] Worker 2 정상 종료 → 새 문서 업로드 대기')
    # Worker 2는 정상 종료해 유휴 트랜잭션의 잠금을 풀고 Worker 1만 첫 작업을 받게 한다.
    # 종료 응답이 유실돼도 finally에서 다시 시작할 수 있도록 표시를 먼저 남긴다.
    stopped_by_us = True
    run(['docker', 'stop', '--time', '30', args.worker2], timeout=45)
    worker2_unchanged(False)
    checkpoint = query('''SELECT json_build_object('baseline',COALESCE(MAX(enqueue_seq),0),
 'unfinished',COUNT(*) FILTER(WHERE status IN ('PENDING','PROCESSING','RETRY_WAIT')))
 FROM :"schema".indexing_job;''')
    if checkpoint['unfinished']:
        raise StopTest('준비 중 새 작업이 들어왔습니다. 큐가 비워진 뒤 다시 실행하세요.', 2)
    baseline = checkpoint['baseline']
    say('\n>>> 지금 웹에서 테스트 문서 하나를 새로 업로드하세요. <<<')
    say(f"  {args.kill_phase} 상태를 관측하면 추가 입력 없이 Worker 1을 강제 종료합니다.")
    deadline = time.monotonic() + args.upload_timeout
    while time.monotonic() < deadline:
        candidates = query('''SELECT COALESCE(json_agg(id),'[]'::json)
 FROM :"schema".indexing_job WHERE enqueue_seq > :'baseline'::bigint;''', baseline=baseline)
        if len(candidates) > 1:
            raise StopTest('새 Job이 여러 개 등록됐습니다. 대상을 임의로 선택하지 않습니다.', 2)
        if candidates:
            job_id = candidates[0]
            row = snapshot('before_kill')
            if row['job_type'] != 'INDEX':
                raise StopTest('새 INDEX Job으로 테스트하세요.', 2)
            if row['status'] in ('COMPLETED', 'FAILED'):
                raise StopTest('강제 종료 전에 Job이 종결됐습니다. 문서 분량/kill-phase를 조정하세요.', 2)
            if row['status'] == 'PROCESSING' and (args.kill_phase == 'ANY' or row['phase'] == args.kill_phase):
                worker2_unchanged(False)
                if not row['worker_id'] or not row['lease_until'] or row['lease_expired']:
                    raise StopTest('강제 종료 전 유효한 실행권을 확인하지 못했습니다.', 2)
                if row['execution_epoch'] != row['document_epoch']:
                    raise StopTest('강제 종료 전 Job/문서 epoch가 다릅니다.')
                before = row
                save('before-kill.json', before)
                break
        time.sleep(args.poll_interval)
    else:
        raise StopTest('새 Job의 지정 처리 단계 관측 시간 초과. 업로드/Worker/서킷 상태를 확인하세요.', 2)

    say('[3/7] 처리 중인 Worker 1 SIGKILL')
    if container(args.worker1)['Id'] != initial1['Id']:
        raise StopTest('Worker 1 컨테이너가 바뀌었습니다.', 2)
    run(['docker', 'kill', '--signal=SIGKILL', args.worker1])
    kill_sent = True
    worker1_stays_stopped()
    after = snapshot('after_kill')
    if (after['status'] != 'PROCESSING' or after['worker_id'] != before['worker_id'] or
        after['execution_epoch'] != before['execution_epoch']):
        raise StopTest('조회와 SIGKILL 사이에 상태가 바뀌었습니다. 처리 중 종료를 확정할 수 없어 재시험이 필요합니다.', 2)
    cutoff = after['lease_until']
    save('after-kill.json', after)
    say(f"  jobId={job_id}, sourceEventId={before['source_event_id']}, 만료 기준={cutoff}")

    say('[4/7] 실제 lease 만료와 pg_cron의 PENDING 회수 대기')
    # Worker 2를 멈춘 채 PENDING을 먼저 관측해야 자동 회수와 재선점을 구분할 수 있다.
    say('  DB 상태/lease를 수정하거나 회수 프로시저를 수동 호출하지 않습니다.')
    deadline = time.monotonic() + args.recovery_timeout
    while time.monotonic() < deadline:
        worker1_stays_stopped()
        worker2_unchanged(False)
        row = snapshot('waiting_recovery')
        if row['status'] == 'PENDING':
            if not row['killed_lease_expired']:
                raise StopTest('원래 lease 만료 전에 PENDING으로 변경됐습니다.')
            if any(row[k] is not None for k in ('worker_id','lease_until','next_retry_at')):
                raise StopTest('PENDING 회수 후 소유권/재시도 정보가 남아 있습니다.')
            if (row['execution_epoch'] != after['execution_epoch'] or
                row['document_epoch'] != after['document_epoch'] or
                row['attempt_count'] != after['attempt_count']):
                raise StopTest('회수 단계에서 epoch 또는 attempt_count가 변경됐습니다.')
            save('recovered-pending.json', row)
            break
        if row['status'] != 'PROCESSING':
            raise StopTest(f"예상 회수 정책(PENDING)과 다릅니다: {row['status']}")
        if any(row[k] != after[k] for k in ('worker_id','execution_epoch','lease_until','attempt_count')):
            raise StopTest('Worker 1 종료 후에도 실행권 정보가 변경됐습니다. 다른 소비자/재시작 여부를 확인하세요.', 2)
        time.sleep(args.poll_interval)
    else:
        raise StopTest('회수 시간 초과. 실제 cron 실행 기록/권한/프로시저/Primary 유예와 lease 길이를 확인하세요.')

    say('[5/7] Worker 2 시작 → 같은 Job의 새 실행권 관측')
    # 새 소유자와 증가한 epoch·시도 횟수를 본 뒤 그 실행의 완료까지 확인한다.
    resume_worker2()
    deadline = time.monotonic() + args.completion_timeout
    while time.monotonic() < deadline:
        worker1_stays_stopped()
        worker2_unchanged(True)
        row = snapshot('waiting_completion')
        if row['status'] == 'PROCESSING':
            if (not row['worker_id'] or row['worker_id'] == before['worker_id'] or
                row['execution_epoch'] <= before['execution_epoch'] or
                row['attempt_count'] <= before['attempt_count'] or
                row['execution_epoch'] != row['document_epoch'] or not row['lease_until']):
                raise StopTest('재claim의 새 Worker/epoch/attempt/lease 조건이 충족되지 않았습니다.')
            if not row['lease_expired']:
                if handoff is None or handoff['execution_epoch'] != row['execution_epoch']:
                    handoff = row
                    save('handoff.json', handoff)
                    say(f"  인계 확인: {before['worker_id']} → {row['worker_id']}")
        elif row['status'] == 'FAILED':
            raise StopTest(f"Job FAILED: {row['last_error_code']} / {row['last_error_message']}")
        elif row['status'] == 'COMPLETED':
            save('completed.json', row)
            if handoff is None or row['execution_epoch'] != handoff['execution_epoch']:
                raise StopTest('완료는 확인했지만 해당 epoch의 재claim을 관측하지 못했습니다. 성공 판정 없이 재시험하세요.', 2)
            if row['outcome'] != 'INDEXED' or not row['completed_at']:
                raise StopTest('COMPLETED지만 INDEXED 완료가 아닙니다(삭제 생략 등).')
            if any(row[k] is not None for k in ('worker_id','lease_until','next_retry_at')):
                raise StopTest('완료 후 소유권/재시도 정보가 해제되지 않았습니다.')
            break
        time.sleep(args.poll_interval)
    else:
        raise StopTest('Worker 2의 최종 완료 시간 초과. API/MinIO/서킷/로그를 확인하세요.')

    say('[6/7] 최종 결과와 Job 식별자 확인')
    result = query('''SELECT json_build_object(
 'same_event_jobs',(SELECT COUNT(*) FROM :"schema".indexing_job WHERE source_event_id=:'event_id'::uuid),
 'chunk_count',(SELECT COUNT(*) FROM :"schema".document_chunk WHERE document_version_id=:'version_id'::bigint),
 'duplicate_chunk_keys',(SELECT COUNT(*) FROM (
 SELECT chunk_no FROM :"schema".document_chunk WHERE document_version_id=:'version_id'::bigint
 GROUP BY chunk_no HAVING COUNT(*)>1) duplicates));''',
        event_id=before['source_event_id'], version_id=before['document_version_id'])
    save('result-checks.json', result)
    if result['same_event_jobs'] != 1 or result['chunk_count'] < 1 or result['duplicate_chunk_keys'] != 0:
        raise StopTest('Job 중복 또는 청크 저장 검증 실패: ' + json.dumps(result, ensure_ascii=False))
    worker1_stays_stopped()
    code = 0
    message = 'Worker 1 처리 중 종료 → lease 만료 후 PENDING 회수 → Worker 2 새 실행권 → INDEXED 완료 확인'
    say('[7/7] 장애 복구 테스트 성공')
    say(f"  jobId={job_id}, chunk_count={result['chunk_count']}")
except StopTest as exc:
    code, message = exc.code, str(exc)
except (OSError, ValueError, KeyError, TypeError) as exc:
    code, message = 1, f'환경/응답 처리 오류: {exc}'
finally:
    # Worker 1은 종료 상태로 남겨 두고, 시험을 위해 멈춘 Worker 2만 다시 시작한다.
    try:
        resume_worker2()
    except Exception as exc:
        code = 1
        message += f' / Worker 2 자동 재개 실패: {exc}'
        say(f'수동 복구 필요: docker start {args.worker2}')
    if out is not None:
        for label, name in (('worker1', args.worker1), ('worker2', args.worker2)):
            try:
                logs = subprocess.run(['docker','logs','--timestamps','--since',start_iso,name],
                                      text=True, capture_output=True, timeout=15)
                (out / (label + '.log')).write_text(logs.stdout + logs.stderr, encoding='utf-8')
            except Exception as exc:
                say(f'참고 로그 수집 실패({label}): {exc}')
        save('summary.json', {'exit_code': code, 'message': message, 'job_id': job_id,
             'kill_sent': kill_sent, 'source_event_id': before['source_event_id'] if before else None,
             'old_owner': before['worker_id'] if before else None,
             'new_owner': handoff['worker_id'] if handoff else None,
             'container_attribution': 'DB owner observed; container attribution requires only the two confirmed consumers',
             'scope': 'single crash/recovery; no exactly-once API, stale-worker write or HA failover validation'})
        say(f'결과 파일: {out}')
    say(('성공: ' if code == 0 else '판정 불가: ' if code == 2 else '종료: ') + message)
    if kill_sent:
        say(f'테스트 후 Worker 1 복구: docker start {args.worker1}')
sys.exit(code)
PYTHON
