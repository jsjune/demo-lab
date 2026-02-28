# Trace Agent Application Guide

이 가이드는 `trace-agent`를 Java 애플리케이션에 적용하는 방법을 설명합니다.

## 1. 개요
`trace-agent`는 바이트코드 조작(ASM) 방식을 사용하여 소스 코드 수정 없이 HTTP, JDBC, Kafka 호출을 자동으로 추적합니다. 수집된 데이터는 설정된 Collector 서버로 TCP 전송됩니다.

## 2. 에이전트 빌드
프로젝트 루트에서 다음 명령어를 실행하여 에이전트 JAR 파일을 생성합니다.

```bash
./gradlew :modules:trace-agent:build
```

빌드 결과물은 다음 경로에 생성됩니다:
`modules/trace-agent/build/libs/trace-agent-0.0.1-SNAPSHOT.jar`

## 3. 애플리케이션 적용
JVM 시작 옵션에 `-javaagent` 설정을 추가하여 실행합니다. 에이전트 설정은 `-Dtrace.agent.{key}` 형식을 사용합니다.

### 실행 예시 (CLI)
```bash
java -javaagent:./trace-agent.jar \
     -Dtrace.agent.server-name=order-service \
     -Dtrace.agent.collector.host=localhost \
     -Dtrace.agent.collector.port=9090 \
     -Dtrace.agent.header-key=X-Trace-Id \
     -jar your-app.jar
```

### 주요 설정 프로퍼티
모든 프로퍼티는 `trace-agent.properties`에 정의되어 있으며, JVM 옵션으로 오버라이드 가능합니다.

| 프로퍼티 키 | 설명 | 기본값 |
| :--- | :--- | :--- |
| `server-name` | 에이전트가 설치된 서버의 식별 이름 | `unknown-server` |
| `collector.host` | 이벤트를 수집할 Collector 서버 주소 | `localhost` |
| `collector.port` | Collector 서버 TCP 포트 | `9090` |
| `header-key` | 트레이스 ID 전파에 사용할 헤더 키 | `X-Tx-Id` |
| `sampling.rate` | 샘플링 비율 (0.0 ~ 1.0) | `1.0` |

## 4. 주요 기능
- **자동 Trace ID 생성**: 요청 헤더에 Trace ID가 없을 경우 에이전트가 자동으로 생성하여 컨텍스트를 시작합니다.
- **전파(Propagation)**: 생성된 ID는 `RestTemplate` 호출 및 `Kafka` 메시지 발송 시 자동으로 헤더에 포함되어 다음 서비스로 전달됩니다.
- **안정성**: 에이전트 내부 로직에서 발생하는 모든 예외는 격리되어 비즈니스 로직에 영향을 주지 않습니다.
- **비동기 전송**: 수집된 이벤트는 백그라운드 쓰레드에서 TCP를 통해 비동기로 전송됩니다.

## 5. 트러블슈팅
- **로그 확인**: 에이전트 기동 시 콘솔에 `[TRACE AGENT] Premain started` 메시지가 출력되는지 확인하세요.
- **연결 확인**: Collector 서버가 띄워져 있지 않아도 애플리케이션은 정상 작동하며, 에이전트는 내부 큐에 이벤트를 보관하다가 연결이 복구되면 재전송을 시도합니다.
