# 현재 C/F 로컬 실행 (2026-10-03)

3307은 `F:/MySQL/test`, 설정은 `F:/MySQL/test/runtime.ini`다. 3306의 MYSQL84와 Redis 서비스는 기존 주소·포트·계정을 유지하며 외부 서비스 설정이 각각 F를 사용한다. 앱·Gradle JDBC 설정에서 데이터 디스크를 선택하지 않는다. 실행 파일/JDK는 C Program Files에 유지한다.

현재 소유권 기준은 `scripts/mysql-test-instance.json`이다. 이 파일은 이전된 UUID와 실제 설정/실행 파일 SHA-256, root, port, owner만 기록한다. 복사된 F/E `ownership.json`의 과거 PID·경로는 초기화 이력이며 현재 프로세스의 증거가 아니다. 초기화 이력을 수정하거나 현재 PID로 덮어쓰지 않는다. 설정/실행 파일이 바뀌면 변경 이유와 실제 값을 검토해 새 기준을 정해야 하며 검사를 우회하지 않는다.

## 기동·접속·정상 종료

비상승 cgt PowerShell에서 C repo root를 현재 디렉터리로 사용한다. `local-secrets/secrets.yml`은 기존 안전한 주입 helper가 읽으며 출력하지 않는다. 기존 C JDK21과 검증한 F Gradle cache의 동일 JDBC driver를 사용한다. 시스템/사용자 전역 환경과 C 설치 위치는 변경하지 않는다.

```powershell
$run = 'phase18-db-check-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
.\scripts\mysql-test-instance.ps1 -Action Inspect -RunId $run
.\scripts\mysql-test-instance.ps1 -Action Start -RunId $run
try {
    .\scripts\mysql-test-instance.ps1 -Action Verify -RunId $run
} finally {
    .\scripts\mysql-test-instance.ps1 -Action Stop -RunId $run
}
```

Start는 초기화/DDL 없이 기존 instance만 기동한다. UUID·runtime hash·loopback·내구성·data/redo/undo/tmp/binlog/log/imports 경로와 현재 launcher/server PID/start/parent/pid-file을 확인한다. SQL probe는 기존 `pixel_place_test`에 읽기 전용 접속한다. Stop은 같은 identity와 다른 DB client 부재를 다시 확인하고 SQL SHUTDOWN 뒤 launcher/child/listener 소멸까지 관측한다. 기동 후 실패하면 새로 남은 start intent/identity를 확인하고 동일 RunId의 Stop을 사용한다. start.json조차 없는 중간 장애는 자동 재시작/채택하지 말고 현장 process 확인이 필요하다. 강제 kill이나 새 PID 대입 금지.

증거는 `build/agent-runs/$run`에 CREATE_NEW로 저장한다. 재검사는 `-Receipt verify02` 같은 새 이름을 사용한다. 같은 RunId로 다시 시작하지 않는다. MySQL 사용량과 증가량은 F root, 여유는 실제 사용하는 C/F 각각 검사한다. 기본 DB 증가 예약 2GiB·증거 64MiB·최소 여유 10GiB이며 Start 시 `-MySqlGrowthBytes`/`-EvidenceBytes`로 실행에 맞는 유한 예약을 정하고 이후에는 저장된 예약을 사용한다. 과거 15단계 누적 장부를 새 실행의 예산으로 승계하지 않는다.

Start/Verify/Environment는 기존 용량·최소 여유·서비스 검사를 계속 통과해야 한다. Stop은 용량/최소 여유 위반이나 3306·Redis identity 변경을 실패로 보존하면서, 소유권이 검증된 3307의 정상 종료를 시도한다. 잘못된 3307 identity는 계속 종료를 차단한다. Stop의 `Failures`에는 종료 전/후 검사, 소유권, SQL probe, 종료 관측, 증거 쓰기의 실패 지점과 원인을 구분한다. 위반이 하나라도 있으면 명령은 예외/nonzero이며 `MYSQL_TEST_STOP_OK`를 출력하지 않는다.

Stop 결과의 `ProbeInvoked`는 검증 probe 호출 여부이며 SQL 전송 보장이 아니다. `SqlExit=0`은 probe 성공, null은 성공 미확인이다. `ShutdownObserved`는 launcher/child/listener 소멸 관측, `NormalShutdown`은 probe 성공과 소멸 관측을 모두 충족한 결과다. 종료 후 검사나 증거 쓰기 실패도 이 결과를 덮어쓰지 않는다. CREATE_NEW receipt를 쓸 수 없으면 `ReceiptWrite=failed`와 원래 실패·종료 상태를 `MYSQL_TEST_STOP_RESULT` 출력 및 예외의 `Data['StopResult']`로 남긴다. 명령 실패 시 이 결과로 실제 종료 여부를 먼저 확인한다. Stop 실패 경로 회귀는 `scripts/test-mysql-stop.ps1 -EvidenceRoot <새 증거 폴더>`로 실행하며, DB/서비스를 변경하지 않는 대역을 사용한다.

## 새 18단계 환경·계획

기동 상태에서 `Environment`를 호출한다. Redis 2~15 중 현재 다른 client가 없고 빈 index만 허용한다. DB0/1과 키는 변경하지 않는다.

```powershell
.\scripts\mysql-test-instance.ps1 -Action Environment -RunId $run -RedisDatabase 2
$python = 'C:/Users/cgt/AppData/Roaming/uv/tools/graphifyy/Scripts/python.exe'
& $python .\scripts\new-phase18-plan.py --template <기존-workload-plan-절대경로> `
  --environment "$PWD/build/agent-runs/$run/environment-environment.json" `
  --predecessor <현재-인계문서-절대경로> --run-id <새-trial-id> --label check `
  --policy-template <해당-workload-policy-절대경로>
```

이 생성기는 단일 case의 workload/runtime/bounds를 보존하고 session/run/catalog·현재 입력 집합·HEAD·선행 인계·환경 hash를 새로 동결한다. 기존 plan/policy를 덮어쓰지 않는다. 새 환경의 DB 증가/증거 한도가 template phase/bounds와 맞아야 한다. 현재 source/runtime으로 `phase18ToolsClasspath`를 새 위치에 생성하고 `measure-phase18.ps1 -Action Plan`으로 먼저 검증한다. Environment는 실제 실행 직전에 새로 수집하며 MySQL 재시작 후에는 새 session이 필요하다. 계획 생성/Plan 검사는 부하 실행 허가나 성능 검증을 뜻하지 않는다.

```powershell
. .\scripts\pixel-place-work.ps1
$work = Set-PixelPlaceWorkEnvironment $run
# 새 F build에서 현재 source 컴파일. C evidence는 별도 보존
$buildArguments = $work.GradleArguments
.\gradlew.bat phase18ToolsClasspath bootJar @buildArguments `
  -Pphase18ClasspathFile="$PWD/build/agent-runs/$run/classpath.txt"
.\scripts\measure-phase18.ps1 -Action Plan -PlanPath "$PWD/build/agent-runs/$run/plan-check.json" `
  -Java "$env:JAVA_HOME/bin/java.exe" -ClasspathFile "$PWD/build/agent-runs/$run/classpath.txt"
```

환경·도구 guard 회귀는 `test-mysql-test-tools.ps1 -EvidenceRoot <새 증거 폴더>`와 `python scripts/test-new-phase18-plan.py`를 사용한다. 실제 MySQL 통합 회귀가 필요한 작업에서는 위 Start/Verify를 거친 뒤 기존 전용 `pixel_place_test` 초기화 가능·동시 사용 부재를 확인하고, 기존 비밀 주입과 `mysqlIntegrationTest -Pphase15MySqlRegression=3307`을 사용한다. 새 build/test XML·실행 exit를 남기고 finally에서 Stop한다. 이 task는 테스트 스키마를 변경하므로 이번 read-only 경로 검증에서는 실행하지 않았다. 일반 `test`와 Redis DB1 회귀에는 접속 변경이 없다.

실제 서비스 action은 Plan 검사와 별도로 기존 benchmark 접속 환경도 필요하다. 현재 비밀 helper로 `DB_PASSWORD`를 읽은 뒤, 해당 프로세스의 `PIXEL_PLACE_BENCH_DB_HOST=127.0.0.1`, `PIXEL_PLACE_BENCH_DB_PORT=3307`, `PIXEL_PLACE_BENCH_DB_USERNAME`(현재 application.yml 계정), `PIXEL_PLACE_BENCH_DB_PASSWORD=$env:DB_PASSWORD`, `PIXEL_PLACE_BENCH_REDIS_HOST=127.0.0.1`, `PIXEL_PLACE_BENCH_REDIS_PORT=6379`, `PIXEL_PLACE_BENCH_REDIS_DATABASE`(Environment에서 확인한 2~15), `PIXEL_PLACE_BENCH_PRODUCTION_REDIS_DATABASE=0`을 설정한다. 비밀값을 명령 인자·receipt·로그에 기록하지 않는다. 실제 credential/plan/live Redis index 불일치는 실행 전 거부된다.

복구 백업이 큰 전환 작업의 증거와 A 측정 증거를 같은 폴더에 넣어 64MiB 상한을 넘기지 않는다. 별도 새 session을 만들고 그 session의 새 F build에서 현재 source/runtime receipt를 생성한다. 기존 상한을 올리거나 백업을 옮겨 gate를 우회하지 않는다.

## 보존한 도구·E 참조

- `prepare-phase15-mysql.ps1`, `start/stop-phase15-c-mysql.ps1`, `stop-phase15-mysql.ps1`, `test/measure-phase15.ps1` 및 Phase15 Java 보조 도구는 완료된 15단계 E 소유권·누적 예산·manifest용이다. **현재 DB를 기동/종료/회귀하는 진입점으로 재실행하지 않는다.** 새 방법은 위 공통 도구다. 초기화 도구로 F DB를 준비하지 않는다.
- `verify-phase17-c.ps1`/Phase17CPlan은 해당 C plan·receipt 의미가 필요하다. 당시 plan/mysql ownership을 현재 사실로 재사용하지 않는다. 18 Java 실행기는 새 Environment.mysqlRoot를 따라 F를 검사하므로 소스의 E 전체 치환이 필요 없다.
- `build/agent-runs/phase18-*/inspect-environment.ps1`, `mysql-owner.ps1`, `live-environment.ps1`, `freeze-plan.py`와 17-B `Phase17DatabaseOwnership.java`는 당시 session의 실행 증거다. 경로·PID·hash를 바꿔 재실행하지 않는다. 현재 생성기는 scripts의 새 두 도구다.
- E의 `phase15-a-20260918/mysql`은 이전 데이터·초기화 이력이다. 현재 새 도구가 그 DB 파일을 읽거나 쓰지 않는다. descriptor의 previousRoot는 provenance 문자열이다.
- E의 각 session 결과/WAL 이력/build/Gradle cache/project-cache/temp는 과거 보관 자료다. 현재 실행은 `F:/pixel-place-work/gradle-home`과 새 F session만 사용한다. E 원본·C 이전 WAL·F 백업은 삭제/이동하지 않는다. 새 계획 생성기는 `F:/pixel-place-wal/phase18/<sessionId>/<runId>/pixel-place.wal`을 사용하며 일반 앱의 `F:/pixel-place-wal/runtime`과 분리한다. 현재 지원·전환 근거는 `작업기록/phase-18-progress.md`를 따른다.
- F 이전 후 성능은 장치 배치가 다른 새 환경의 결과다. 이전 E DB의 baseline/반복 수/오차 분포에 합산하거나 같은 환경의 전후 비교로 표시하지 않는다.

현재 경로 검증 결과와 검토 근거: `작업기록/db-storage-migration-20261003.md`.

## 일반 앱·빌드 환경

위 `Set-PixelPlaceWorkEnvironment`는 현재 PowerShell과 자식 프로세스에만 JDK21/GRADLE_USER_HOME/TEMP/TMP/Gradle JVM temp/JNA를 적용한다. 기존 E 경로가 상속 환경에 남으면 값 노출 없이 거부한다. F 캐시 준비 실패 시 E 자동 복귀는 없다. 새 세션 이름을 사용하고 기존 F 작업 파일을 덮어쓰지 않는다. Gradle은 위 공통 인자를 계속 전달하며 테스트 JVM JNA는 temp와 분리된 jna 폴더다.

일반 앱은 같은 새 build의 `build/libs/pixel-place-0.0.1-SNAPSHOT.jar`를 `& $work.Java "-Djava.io.tmpdir=$($work.Temp)" "-Djna.tmpdir=$($work.Jna)" -jar <F의 실제 새 JAR>`로 실행한다. 비밀정보는 기존 helper로 해당 프로세스에만 주입한다. `application.yml`의 production DB3306·F runtime WAL·group 정책을 유지한다. 같은 DB/WAL의 다른 앱/writer 부재를 확인한 뒤 실행하고 Ctrl+C/Spring 정상 종료 완료를 확인한다. 검증에서는 `--server.address=127.0.0.1 --server.port=0 --spring.data.redis.database=<현재 미사용·소유 확인한 2~15>`만 프로세스에 지정한다. Redis0/1·영구 설정·사용자 데이터에 검증 쓰기를 하지 않는다.

MySQL 종료는 환경·예산 위반을 먼저 실패로 기록한 뒤 검증된 대상의 SQL 종료를 시도한다. 종료 probe의 경로 환경은 알려진 C/F로 격리하며, 종료 성공으로 원래 환경 위반을 성공 처리하지 않는다. 실행·검토·복구 자료는 C의 새 session 증거 폴더에 보존한다.
