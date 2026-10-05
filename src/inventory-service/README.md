# Inventory Service + Redis

Online Boutique에 연결하기 위한 **Java 21 / Spring Boot 3.5.16 재고 예약 학습 프로젝트**입니다.
실제 Redis Lua 스크립트로 초과 예약, 중복 차감, 중복 재고 반환을 방지합니다.

현재는 독립 실행 가능한 REST 서비스입니다. Online Boutique 원본을 복제하거나 Checkout 코드를 수정한 상태는 아닙니다.
1차 범위는 **단일 상품 예약**이며, 장바구니 전체 예약·결제 연동·gRPC는 다음 단계입니다.

## 빠른 시작 — Docker

Docker Desktop을 실행한 후 프로젝트 폴더에서:

```sh
docker compose up --build -d
curl http://localhost:8080/actuator/health
```

Java/Maven이 호스트에 없어도 컨테이너에서 빌드합니다. 최초 실행에는 이미지·의존성 다운로드가 필요합니다.
API는 `localhost:8080`, Redis는 `localhost:6379`로 접근할 수 있습니다.
포트가 이미 사용 중이면 Compose의 호스트 포트를 변경하세요.
Redis 데이터는 `inventory-data` 볼륨에 보관됩니다.

```sh
# 실제 Redis를 사용하는 통합 테스트 10개
# mvn test만으로는 *IT 통합 테스트가 실행되지 않습니다.
docker compose --profile test run --rm tests

# 실행 중인 서비스에 100개 동시 HTTP 요청
python3 scripts/concurrency_demo.py

# 종료: 데이터 볼륨은 유지
docker compose down
```

## 현재 작업 폴더에서 Docker 없이 실행

이 Mac에서 검증에 사용한 Java 21·Redis 실행 파일은 프로젝트 밖 `../../work/tooling/`에 있습니다.
시스템 Java 11 설정은 변경하지 않았습니다. 빌드된 JAR를 바로 실행하려면:

```sh
./scripts/run-local.sh
```

`localhost:8080`에서 API가 실행됩니다. Ctrl+C로 앱과 이 스크립트가 시작한 Redis를 함께 종료합니다.
Redis 데이터는 `target/local-runtime`에 남습니다. `mvn clean` 시 지워지는 실습 데이터입니다.
이 편의 스크립트는 현재 폴더 배치에 의존하므로 프로젝트를 다른 PC로 옮기면 Docker 경로를 사용하세요.

## 직접 실행 — Java 21 + Maven Wrapper + Redis

```sh
# Redis가 localhost:6379에서 실행 중일 때
./mvnw verify
./mvnw spring-boot:run
```

다른 포트를 사용하는 경우 테스트는 `TEST_REDIS_PORT`, 앱은 `REDIS_PORT`로 설정합니다.
테스트는 임의의 전용 namespace를 사용하며 자기 데이터만 삭제합니다. Redis 미접속 시 테스트가 실패하며 생략되지 않습니다.

```sh
TEST_REDIS_PORT=16379 ./mvnw verify
REDIS_PORT=16379 ./mvnw spring-boot:run
```

## API 사용 예제

### 1. 재고 초기화

```sh
curl -i -X PUT http://localhost:8080/api/v1/stock/OLJCESPC7Z \
  -H 'Content-Type: application/json' -d '{"quantity":10}'
```

최초 생성만 허용합니다. 이미 있는 상품은 `409 ALREADY_INITIALIZED`이며 기존 재고를 덮어쓰지 않습니다.
다시 실습할 때는 다른 상품 ID를 사용하세요. 초기화 API는 로컬 실습용이며 운영용 입고 API가 아닙니다.

### 2. 1개 예약

```sh
curl -X POST http://localhost:8080/api/v1/reservations \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"order-001","productId":"OLJCESPC7Z","quantity":1}'
```

```json
{"reservationId":"order-001","productId":"OLJCESPC7Z","quantity":1,"status":"RESERVED"}
```

`requestId`는 호출자가 생성하고 **같은 논리적 요청의 재시도에서 반드시 재사용**합니다.
이 MVP에서는 requestId를 reservationId로 사용합니다.
같은 ID·상품·수량은 기존 예약의 현재 상태를 반환합니다. 다른 상품 또는 수량은 `409 IDEMPOTENCY_CONFLICT`입니다.
취소된 예약을 재시도해도 다시 차감하지 않습니다. 새로운 구매는 새 ID를 사용해야 합니다.
재고 부족 등 예약이 생성되지 않은 실패는 저장하지 않습니다. 이후 재고가 반환되면 같은 요청이 성공할 수 있습니다.

### 3. 확정 또는 취소

```sh
# 둘 중 하나를 실행
curl -X POST http://localhost:8080/api/v1/reservations/order-001/confirm
curl -X POST http://localhost:8080/api/v1/reservations/order-001/release

# 현재 재고와 예약 상태 확인
curl http://localhost:8080/api/v1/stock/OLJCESPC7Z
curl http://localhost:8080/api/v1/reservations/order-001
```

| 전이 | 재고 처리 | 반복 호출 |
|---|---|---|
| RESERVED → CONFIRMED | 추가 차감 없음 | 같은 결과, 200 |
| RESERVED → RELEASED | 예약 수량 한 번 반환 | 같은 결과, 200 |
| CONFIRMED → RELEASED | 금지 | 409 INVALID_STATE |
| RELEASED → CONFIRMED | 금지 | 409 INVALID_STATE |

판매 확정 후 환불은 별도의 후속 기능입니다.

## 구조와 핵심 코드

```text
HTTP client → InventoryController → InventoryService → Redis Lua → Redis
```

| 파일 | 읽어볼 내용 |
|---|---|
| `src/main/resources/lua/reserve.lua` | 중복 확인 + 재고 검사 + 차감 + 예약 기록 |
| `src/main/resources/lua/transition.lua` | 확정/취소 경쟁과 중복 반환 방지 |
| `src/main/java/dev/boutique/inventory/InventoryService.java` | Spring Data Redis 스크립트 실행 |
| `src/test/java/dev/boutique/inventory/InventoryIT.java` | 잘못된 구현 재현과 수정 후 HTTP 통합 테스트 |

Redis 키:

```text
boutique:inventory:stock:<productId>        → 판매 가능한 재고 (String)
boutique:inventory:reservation:<requestId> → productId, quantity, status (Hash)
```

`INVENTORY_NAMESPACE`로 키 접두사를 바꿀 수 있습니다. 여러 앱 인스턴스가 동일한 Redis와 namespace를 사용해야 합니다.
프로세스 내부 `synchronized`나 별도 분산 락 대신 Redis에서 검사와 변경을 함께 수행합니다.

**Lua의 원자성은 중간 오류의 자동 롤백을 의미하지 않습니다.** 쓰기 전에 키 타입과 값을 검사합니다.
Redis 키를 다른 프로그램이 임의로 변경하지 않는 것을 전제로 합니다.

## 검증 시나리오

1. 재고 10개 + 서로 다른 요청 100개 → 성공 10, 품절 90, 남은 재고 0, 예약 기록 10.
2. 같은 요청 ID 100개 → 모두 같은 예약, 한 번만 차감.
3. 동일 ID에 다른 수량·상품 → 충돌, 추가 차감 없음.
4. 취소 30개 동시 요청 → 한 번만 복구, 취소 후 예약 재시도에도 재차감 없음.
5. 확정과 취소 경쟁 → 하나만 성공, 최종 상태와 재고 일치.
6. 확정 반복 → 멱등, 확정 후 취소 거절.
7. 음수·0·과다 수량·소수·누락 값·잘못된 ID → 400, 재고 유지.
8. 없는 상품/예약, 품절, 재초기화 → 지정된 오류.
9. 잘못된 예약 키 타입 → 재고 차감 전 거절.
10. 의도적으로 잘못된 GET→SET을 테스트 내부에서 재현 → 재고 1개에 2건 성공하는 문제 확인.

두 개의 독립 앱 프로세스를 같은 Redis에 연결하면 아래처럼 검증할 수 있습니다.
두 번째 프로세스에는 `PORT=8081`을 설정하세요.

```sh
python3 scripts/concurrency_demo.py \
  --base-url http://localhost:8080 --base-url http://localhost:8081
```

데모는 매번 고유 상품을 만들고, 성공한 예약을 모두 취소합니다. 확인용 상품·예약 기록은 남습니다.

## 이번 생성 시 실행한 검증

- 실제 Redis 7.4.2 + Java 21에서 `verify`: 테스트 10개, 실패/오류/생략 0.
- 독립 앱 프로세스 2개에 HTTP 요청 100개 분산: 성공 10, 품절 90. 취소 후 재고 10으로 복구.
- `run-local.sh`: health UP 및 앱/Redis 정상 종료 확인. 검증용 서버는 종료했습니다.
- Docker daemon이 실행되지 않아 Docker 이미지 빌드와 Compose 실행은 검증하지 않았습니다.
- [검증 결과 JSON](docs/verification-results.json).

## 의도적으로 남겨둔 다음 단계

- **Online Boutique 연결:** [연동 설계](docs/online-boutique-integration.md).
- **여러 상품:** 모든 상품의 재고를 먼저 검사한 뒤 한 스크립트로 예약. 단순히 API를 상품별로 반복하면 부분 예약이 생깁니다.
- **예약 만료:** 현재 TTL 없음. 미확정 예약은 명시적으로 취소해야 합니다. TTL로 Hash만 삭제하면 재고는 복구되지 않습니다.
- **내구성:** Compose는 AOF everysec + 영속 볼륨 + noeviction. 장애 시 최근 쓰기 손실 가능성은 남습니다. 원자성은 장애 후 무손실 보장과 다릅니다.
- **Redis Cluster:** 현재 단일 Redis용 키 설계. 여러 키 스크립트는 Cluster에서 같은 hash slot 제약을 고려해 재설계해야 합니다.
- **운영 보안:** 인증 없는 로컬 실습용 API. Compose 포트는 127.0.0.1에만 바인딩합니다. 외부 공개 전 인증·권한·TLS·입고 정책을 추가해야 합니다.

## 참고

- [Online Boutique](https://github.com/GoogleCloudPlatform/microservices-demo)
- [Redis Lua 원자성](https://redis.io/docs/latest/develop/programmability/eval-intro/)
- [Spring Data Redis scripting](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html)
- [Redis persistence](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)
