# Trace Agent Application Guide

이 문서는 `trace-agent`를 Java 애플리케이션에 적용하고 운영하는 최신 방법을 설명합니다.

## 1. 개요
`trace-agent`는 ASM 기반 Java Agent로 애플리케이션 코드 수정 없이 다음 이벤트를 자동 추적합니다.

- HTTP Inbound/Outbound (Spring MVC/WebFlux/RestTemplate/WebClient)
- JDBC Query
- Kafka Produce/Consume
- Cache (Redis)
- File I/O
- Async Executor

수집 이벤트는 TCP로 Collector에 전송됩니다. 전송은 `single` 또는 `batch` 모드를 지원합니다.

## 2. 에이전트 빌드

```bash
./gradlew :modules:trace-agent:build
```

산출물:
`modules/trace-agent/build/libs/trace-agent-0.0.1-SNAPSHOT.jar`

## 3. 애플리케이션 적용
JVM 옵션으로 `-javaagent`와 `-Dtrace.agent.*` 설정을 전달합니다.

### 실행 예시
```bash
java -javaagent:./trace-agent.jar \
     -Dtrace.agent.server-name=order-service \
     -Dtrace.agent.collector.host=localhost \
     -Dtrace.agent.collector.port=9200 \
     -Dtrace.agent.header-key=X-Tx-Id \
     -Dtrace.agent.sender.mode=batch \
     -Dtrace.agent.sender.batch.size=50 \
     -Dtrace.agent.sender.batch.flush-ms=500 \
     -jar your-app.jar
```

## 4. 주요 설정 프로퍼티
기본값은 `modules/trace-agent/src/main/resources/trace-agent.properties`를 따릅니다.

| 키 | 설명 | 기본값 |
| :--- | :--- | :--- |
| `server-name` | 서비스 식별자 | `unknown-server` |
| `collector.host` | Collector 주소 | `localhost` |
| `collector.port` | Collector TCP 포트 | `9200` |
| `header-key` | TxId 전파 헤더 키 | `X-Tx-Id` |
| `force-sample-header` | 강제 샘플링 헤더 키 | `X-Trace-Force` |
| `sampling.rate` | 샘플링 비율 (0.0~1.0) | `1.0` |
| `sampling.strategy` | 샘플링 전략 (`rate`, `error-only`) | `rate` |
| `buffer.capacity` | 내부 큐 용량 | `1000` |
| `sender.mode` | 전송 모드 (`single`, `batch`) | `single` |
| `sender.batch.size` | 배치 최대 건수 | `50` |
| `sender.batch.flush-ms` | 배치 최대 대기(ms) | `500` |
| `shutdown.drain-timeout-ms` | 종료 시 drain 대기(ms) | `3000` |
| `log.file.path` | 에이전트 로그 파일 경로 | `logs/trace-agent.log` |
| `log.level` | 로그 레벨 | `INFO` |

## 5. 동작 특성

- 요청에 TxId가 없으면 새 txId를 생성합니다.
- `RestTemplate`, `WebClient`, Kafka produce에 TxId 헤더를 자동 주입합니다.
- 예외는 에이전트 내부에서 격리되어 비즈니스 로직으로 전파되지 않습니다.
- 종료 시 shutdown hook에서 큐 잔여 이벤트를 drain 시도합니다.

## 6. 운영/트러블슈팅

- 에이전트 로그는 기본적으로 `logs/trace-agent.log`에 기록됩니다.
- Collector 미연결 시 애플리케이션은 계속 동작하며 재연결을 시도합니다.
- 큐가 가득 차면 가장 오래된 이벤트부터 드롭됩니다.
- 최근 리팩터링으로 Reflection 캐시는 `ClassValue` 기반으로 동작합니다.
  - 목적: `ClassLoader` 강참조 캐시로 인한 장기 JVM 메모리 누수 위험 완화

## 7. 테스트

### 7.1 계약 테스트
```bash
./gradlew :modules:trace-agent:test \
  --tests "org.example.agent.core.TraceRuntimeTypeCategoryContractTest" \
  --no-daemon
```
