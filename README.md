# demo-lab Observability Architecture (Improved)

## 1. Goal
- `trace-admin` 하나에서 `trace + metrics + logs`를 통합 조회한다.
- 운영 환경은 `Kubernetes`와 `VM`을 모두 지원한다.
- 기존 모듈(`trace-agent`, `trace-collector`, `trace-batch`, `trace-admin`)을 유지하면서 확장한다.

## 2. Current Baseline
- `trace-agent`: `-javaagent`로 애플리케이션 trace 이벤트 생성
- `trace-collector`: TCP 수신 후 Kafka(`trace-events`)로 전달
- `trace-batch`: Kafka 소비 후 DB(`trace_events`) 저장
- `trace-admin`: DB 조회 및 화면 제공

현재 파이프라인은 사실상 trace 중심이다. metrics/logs를 통합하려면 공통 모델과 저장/조회 계층 확장이 필요하다.

## 3. Target Architecture
### 3.1 New Modules
- `modules/trace-metrics` 추가
- `modules/trace-log` 추가
- 역할:
  - `trace-metrics`: Actuator endpoint pull (`/actuator/metrics`, 필요 시 `/actuator/prometheus`)
  - `trace-log`: file/stdout tail (증분 읽기, rotate/truncate 대응)
  - 두 모듈 모두 수집 데이터를 표준 내부 이벤트로 변환 후 `trace-collector`로 전송

### 3.2 End-to-End Flow
1. `trace-agent` -> `trace-collector` (trace push)
2. `trace-metrics` -> `trace-collector` (metrics push)
3. `trace-log` -> `trace-collector` (logs push)
4. `trace-collector` -> Kafka (signal별 topic 분리 권장)
5. `trace-batch` -> DB (trace/metrics/logs 저장)
6. `trace-admin` -> 단일 UI 통합 조회

### 3.3 Deployment Model (K8s + VM)
- Kubernetes:
  - 앱: Deployment
  - `trace-metrics`: DaemonSet 또는 sidecar
  - `trace-log`: DaemonSet 또는 sidecar
- VM:
  - 앱: systemd/프로세스
  - `trace-metrics`: systemd 서비스
  - `trace-log`: systemd 서비스

핵심은 배포형태와 무관하게 수집 파이프라인 계약(프로토콜/이벤트 스키마)을 동일하게 유지하는 것이다.

## 4. Dynamic Registration Model
애플리케이션이 `trace-admin`을 직접 알 필요는 없다. 최소한 `collector endpoint`만 알면 된다.

1. 첫 데이터 수신 시 `service_registry` upsert
2. 키: `service.name + service.instance.id + environment`
3. `last_seen` 갱신, TTL 기반 `inactive` 처리
4. `trace-admin`에서 서비스별 수집 정책(샘플링률, 로그 레벨, 활성화) 관리

## 5. Data Contract (Recommended)
## Common Resource Attributes
- `service.name`
- `service.instance.id`
- `environment`
- `host.name`
- `k8s.namespace`, `k8s.pod.name` (k8s only)
- `timestamp`

### Trace Event
- `trace_id`, `span_id`, `parent_span_id`, `name`, `kind`, `duration_ms`, `status`

### Metric Event
- `metric_name`, `metric_type(counter/gauge/histogram)`, `value`, `unit`, `labels`

### Log Event
- `severity`, `message`, `logger`, `thread`, `trace_id(optional)`, `span_id(optional)`, `attributes`

## 6. Reliability & Performance Principles
`docs/skills.md`의 설계 원칙을 반영한 운영 기준:

- 책임 분리: 수집(collector) / 저장(batch) / 조회(admin) 분리
- 확장성: 토픽/파티션 기반 수평 확장
- 방어적 설계:
  - 배치 전송 + 재시도 + DLQ
  - backpressure (큐 상한, drop policy)
  - 로그 폭주 시 rate limit
- 성능 예산:
  - trace: near real-time
  - metrics: 1~15초 주기
  - logs: tail 기반 sub-second~few seconds

## 7. Security & Operations
- 수집 채널 TLS 적용 (collector ingress)
- 서비스/에이전트 인증 토큰 적용
- PII/민감정보 마스킹 규칙 (log processor)
- 수집기 자체 메트릭 노출:
  - ingest rate
  - queue depth
  - drop/retry count
  - flush latency

## 8. Log Collection Policy (K8s vs VM)
로그 수집 복잡도를 낮추기 위해 환경별 기본 전략을 고정한다.

### Kubernetes (Recommended Baseline)
- 애플리케이션 로그는 `stdout/stderr` 표준 출력 사용
- `trace-log`는 노드의 컨테이너 로그 경로(`/var/log/containers/*.log`)를 tail
- 장점:
  - 앱별 파일 경로/날짜별 파일명 규칙 관리 불필요
  - 수집 설정 표준화가 쉬움

### VM (Requires Explicit Convention)
- VM은 파일 로그 수집 규약을 반드시 정의해야 함
- 권장 규약:
  - 경로: `/var/log/apps/{service}/app.log`
  - 패턴: `app.log*`
  - 롤링: size/time 기반 logrotate 정책 표준화
- `trace-log` 필수 기능:
  - offset checkpoint(재시작 복구)
  - truncate 감지(크기 감소 시 offset 재설정)
  - rotate 감지(fileKey/inode 변경 추적)
  - 멀티라인(스택트레이스) 결합 규칙

### Conclusion
- K8s는 stdout 표준으로 상대적으로 단순하게 운영 가능
- VM은 경로/롤링/포맷 규약을 사전에 고정해야 안정 수집 가능

## 9. Trade-offs
### Split Modules: `trace-metrics` + `trace-log`
Pros:
- 장애 격리 (로그 폭주가 metrics 수집에 미치는 영향 최소화)
- 신호별 성능 튜닝 용이 (주기/배치/큐/재시도 독립 설정)
- 신호별 확장 전략 분리 가능 (logs high-throughput, metrics periodic)

Cons:
- 배포 단위 증가
- 공통 로직 중복 위험

Mitigation:
- `trace-common`에 공통 SDK/모델/전송 유틸 통합
- 공통 운영 규약(설정 키, health, metrics, retry policy) 표준화

## 10. Incremental Migration Plan
1. Phase 1: `trace-metrics` 모듈 생성 + metrics pull 수집
2. Phase 2: `trace-log` 모듈 생성 + logs tail 수집(rotate/truncate 안정화)
3. Phase 3: collector/batch에 metrics/logs topic + 저장 스키마 확장
4. Phase 4: admin 통합 화면(Trace/Metric/Log Drill-down) 추가
5. Phase 5: 동적 등록/정책 관리 UI 활성화

## 11. Sample CRUD App Onboarding (`test-modules/sample-crud-app`)
- Trace:
  - 기존 `-javaagent` 유지
- Metrics:
  - Actuator endpoint 노출 후 `trace-metrics`가 pull
- Logs:
  - JSON 로그 파일 또는 stdout 출력
  - `trace-log`가 tail 수집
- Correlation:
  - 로그에 `trace_id/span_id` 포함(MDC) 권장

---
이 문서는 현재 `demo-lab` 모듈 구조를 유지하면서, 단일 `trace-admin` 중심 통합 관측성으로 점진 전환하기 위한 기준 아키텍처다.
