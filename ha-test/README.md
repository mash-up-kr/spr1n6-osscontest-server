# DB HA 기능테스트 (H-1 ~ H-6)

2026 오픈소스 개발자대회 기능명세서의 **DB HA 항목** 6개를 온라인 시연(Zoom)에서 재현하는 시험 도구다.

원칙은 하나다. **누가 언제 실행해도 같은 명령·같은 형식의 결과·같은 판정이 나온다.** 사람이 로그를 읽고
판단하지 않고, 스크립트가 성공 기준과 비교해 Pass/Fail 을 낸다.

macOS 기준으로 작성했다.

| # | 소분류 | 무엇을 확인하나 |
|---|---|---|
| H-1 | DB 데이터 복제 | VIP 로 넣은 행이 두 Replica 에 1초 안에 복제되고 streaming·Lag 0 인지 |
| H-2 | 읽기 요청 분산 | 검색 쿼리가 Replica 에서만 처리되고 Primary CPU 가 오르지 않는지, 업로드(쓰기)는 Primary 에서 처리되는지 |
| H-3 | DB 자동 장애 복구 | Primary 를 죽였을 때 사람 개입 없이 승격해 쓰기가 45초 안에 재개되는지 |
| H-4 | 장애 복구 후 데이터 유지 | 장애 전 성공 응답을 받은 행이 새 Primary 에 모두 남는지(유실 건수) |
| H-5 | DB 연결 경로 자동 전환 | Active OpenProxy 를 멈췄을 때 같은 VIP 로 5초 안에 다시 쓰이는지 |
| H-6 | 노드별 상태 조회 | 3대의 역할·상태·Lag 가 표시되고 정지·원복이 반영되는지 |

## 쓰는 법

저장소 루트에서 실행한다. 추가 설치는 없다(Python 3 표준 라이브러리 + SSH 만).

```bash
./ha-test/ha-test --simulate all        # ① 서버 없이 전체 흐름을 먼저 본다
./ha-test/ha-test status                # ② 지금 노드 상태(역할·Lag·VIP)
./ha-test/ha-test check                 # ③ 기준 상태인지만 검사(읽기 전용)
./ha-test/ha-test H-3                   # ④ 시험 하나를 끝까지
./ha-test/ha-test all                   # ⑤ H-1~H-6 전부
./ha-test/ha-test restore               # 중간에 멈췄을 때 기준 상태로 되돌리기
./ha-test/ha-test unlock                # 강제로 끊긴 시험이 남긴 잠금 풀기
```

옵션: `--simulate`(서버 없이 모의 클러스터로 완주), `--yes`(장애 주입 확인 생략).
서버에 실제로 보낸 명령은 시험마다 `results/<TC>-<시각>/commands.log` 에 남는다. 명령 내용은 `hatest/commands.py` 한 파일에 모여 있다.

## 시연 화면 준비 (터미널 + Grafana)

왼쪽에 이 도구를 돌리는 터미널, 오른쪽에 노드 모니터링 대시보드(`docker-compose.monitoring.yml`)를 띄운다.

1. **모니터링을 db-03 경유로 띄운다.** 기본 설정은 터널이 `SSH_HOST`(db-01) 하나를 거쳐 3대 지표를 모두 가져온다.
   H-3·H-4 는 그 db-01 을 죽이고 watchdog 이 재부팅하므로, 그대로 두면 **승격 장면에서 대시보드 전체가 끊긴다.**
   시험에서 절대 끄지 않는 관찰 노드(db-03)를 거치게 한다. 명령줄 값이 `.env` 보다 우선한다.
   `$(ssh -G ha-db-03 ...)` 는 `~/.ssh/config` 의 `ha-db-03` 별칭에서 db-03 공인 IP 를 꺼낸다.

   ```bash
   SSH_HOST=$(ssh -G ha-db-03 | awk '/^hostname /{print $2}') docker compose -f docker-compose.monitoring.yml up -d
   ```

   H-3·H-4 를 시작할 때 터널이 db-01 을 거치고 있으면 도구가 위 명령과 함께 경고한다.
2. http://localhost:3030 에서 "OpenSQL HA 노드 현황" 을 연다. 지표는 2초마다 모이고 화면은 5초마다 새로 그린다.
3. 시험을 돌리면 도구가 대시보드에 **세로선**을 찍는다(태그 `ha-test`): 장애 주입, 새 Leader, 쓰기 재개, VIP 이동,
   옛 Primary 다시 시작·합류, 원복 완료. 세로선 시각은 서버 시계라 그래프의 변화와 같은 자리에 선다.
   Grafana 가 꺼져 있으면 세로선만 빼고 시험은 그대로 한다.

터미널에는 장애를 넣은 뒤 2초마다 지금 상태가 한 줄씩 찍힌다(아래는 `--simulate` 출력이라 숫자는 각본 값이다).

```
[02:46:48] 장애 주입 · db-01 Patroni 자동 시작을 끄고 Patroni·PostgreSQL 강제 종료
    ↳ Grafana 세로선: H-3 장애 주입: db-01 강제 종료
    +  2.0s  Leader 없음 · db-01 응답 없음 · db-02 Replica · db-03 Replica · 쓰기 실패 중(연속 3회)
    + 30.0s  Leader db-02 ★ 승격 · db-01 응답 없음 · db-02 Leader · db-03 Replica · 쓰기 실패 중(연속 59회)
    + 34.0s  Leader db-02 · db-01 응답 없음 · db-02 Leader · db-03 Replica · 쓰기 재개 ✔
[02:47:22] 옛 Primary 다시 시작 · db-01 (watchdog 재부팅 중이면 접속될 때까지 기다린다)
    + 24.0s  db-01 접속 대기 (watchdog 재부팅 중)
    + 16.0s  db-01 Replica streaming
```

진행 줄은 보여 주기용이다. 판정에 쓰는 값(승격까지·쓰기 멈춤·합류)은 끝난 뒤 기록 도구의 서버 시각 기록에서 다시 계산한다.

## 한 시험이 도는 순서

1. **기준 상태 검사** — Leader `db-01`, Replica 2대 streaming·Lag 0, VIP 주인 `db-02`(서버·AWS 양쪽),
   Leader watchdog active, Patroni 3대 active·enabled. 하나라도 다르면 **시작하지 않는다.**
2. **확인** — 장애를 넣는 시험(H-3·H-4·H-5)만 "공용 DB 에 장애를 넣는다. 진행할까?"를 묻는다.
3. **기록 시작** — `db-03` 에서 0.5초마다 VIP 로 INSERT(성공 id), 1초마다 3대 역할을 남긴다.
4. **장애 주입** — 시각은 서버 시계로 남긴다(chrony 로 3대가 맞춰져 있어 기록과 바로 비교된다).
5. **측정** — 승격·쓰기 재개·VIP 이동 등을 2초마다 찍으며 기다린다. 한계 시간을 넘기면 Fail.
   H-3 은 문서 절차 ④대로 옛 Primary 를 다시 켜고(재부팅 중이면 접속될 때까지 기다린다) streaming 합류까지 잰다.
6. **원복** — 재부팅 중인 노드를 기다리고, Patroni·OpenProxy 를 켜고, Leader 가 db-01 이 아니면 switchover 하고,
   VIP 가 db-02 로 돌아올 때까지 기다린 뒤 기준 상태를 다시 검사한다. 다음 시험이 같은 조건에서 시작된다.
7. **판정·저장** — 정해진 형식으로 화면에 찍고 `ha-test/results/<TC>-<시각>/` 에 보고서·원본 로그를 남긴다.

중간에 끊기거나(Ctrl-C) 실패해도 기록을 멈추고 원복을 시도한다. 자동 원복이 안 되면 직접 칠 명령을 보여 준다.

## 설정

[cluster.conf](cluster.conf) 에 노드·VIP·경로가 있다. 값은 **환경변수(`HA_` 접두사) → 저장소 `.env` 의 `HA_` 키
→ cluster.conf** 순서로 찾는다. DB 비밀번호는 저장소 `.env` 의 `DB_PASSWORD` 를 쓴다(기록 도구가 VIP 로 쓸 때만 필요).

## 구성

```
ha-test/
  ha-test              실행기(이 스크립트를 부른다)
  cluster.conf         시험 대상 클러스터 값
  node/recorder.sh     db-03 에서 도는 기록 도구(쓰기 probe·역할 기록). 매 실행 때 서버로 올린다
  hatest/              Python 패키지
    config  clock  model  parse  commands  remote  cluster  simulated
    measure  baseline  status  report  scenarios  runner  appclient  grafana  cli
  tests/               단위 테스트(python3 -m unittest discover -s tests)
  results/             실행 결과(git 제외)
```

## 검증

```bash
cd ha-test && python3 -m unittest discover -s tests      # 파싱·측정·판정·기준검사·결과형식·완주·진행표시·앱 호출
./ha-test --simulate all                                 # 6개 TC 모두 Pass
```

## H-2 를 돌리기 전에

server 를 dev 프로필로 띄워 두고, 검색이 `aidocs_ro`(Replica) 풀을 쓰도록 반영돼 있어야 한다.
업로드(쓰기) 확인 때문에 MinIO 도 떠 있어야 한다. 시험용으로 올린 문서는 Primary 에서 행을 확인한 뒤 지운다.
`cluster.conf` 의 `APP_URL`·`SEARCH_QUERIES` 가 그 server 를 가리킨다.

## 주의

- **장애 주입 방식(H-3·H-4)은 확정 전이다.** 지금은 `systemctl disable patroni` 로 자동 시작을 끄고
  Patroni·PostgreSQL 을 `SIGKILL` 한다. watchdog(required)이 노드를 재부팅해도 죽은 채로 남게 하려는 것이다.
  4차 시험에서 실제로 쓴 명령과 맞는지 확인하고 `hatest/commands.py` 의 `kill_db_stack` 을 맞춘다.
- `node/recorder.sh` 는 서버의 `timeout`(coreutils)·`psql`·`curl` 을 쓴다. Rocky Linux 9 에 모두 있다.
- 공용 클러스터를 건드리는 도구다. 먼저 `--simulate` 로 흐름을 확인하고 `check` 가 OK 일 때 실제로 돌린다.
