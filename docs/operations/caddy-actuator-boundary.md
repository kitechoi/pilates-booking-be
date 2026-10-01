# M1.1: Caddy 공개 Actuator 경계

M1.1은 공개 Caddy 경로에서 `/actuator/health`만 기존 앱으로 전달하고,
나머지 Actuator 요청은 앱에 전달하지 않고 `404 Not Found`로 종료한다.
Prometheus 활성화, Spring 보안·설정, Compose, 로컬 바인딩, Alloy 설치는 이 변경에 포함하지 않는다.

## 경로 계약

- `/actuator/health`와 query string을 붙인 요청은 메서드·인증·본문을 포함해 기존대로 앱에 전달한다.
- `/actuator`, `/actuator/*`, servlet path parameter 형태인 `/actuator;*`는 차단한다.
- Caddy path matcher는 URL decoding, dot segment 정리, 중복 슬래시 정규화와 대소문자 무시를 적용한다.
  health 예외는 원본 URI로 판별하므로 `/actuator/%68ealth`, `/actuator//health`,
  `/ACTUATOR/health`, `/actuator/health/`는 허용하지 않는다.
- `/actuator/health/readiness` 같은 하위 경로도 예외가 아니다.
- Authorization, Cookie, X-Forwarded-For는 차단 예외 조건이 아니다.
- 잘못된 percent encoding은 Caddy의 HTTP 파서에서 `400`으로 거절될 수 있다.
  파서 거절과 라우팅의 `404`를 구분하며, 모두 upstream 미도달을 확인한다.
- 일반 API와 Swagger는 그대로 전달한다. `/actuator-other`처럼 별도 namespace까지 차단하지 않는다.
- HTTP 80의 기존 HTTPS redirect는 유지한다. redirect 후 HTTPS에서 차단한다.
- 차단은 공개 Caddy 경로에 적용된다. EC2의 기존 `127.0.0.1:8081/8082` 직접 접근에는 적용되지 않는다.

`route`가 차단 응답을 `reverse_proxy`보다 먼저 처리한다. `__APP_UPSTREAM__`는 한 번만 유지하며,
기존 배포 스크립트의 치환, Caddy reload, 설정 백업·복원을 바꾸지 않는다.

참고: [Caddy path matcher](https://caddyserver.com/docs/caddyfile/matchers#path),
[route 처리 순서](https://caddyserver.com/docs/caddyfile/directives/route),
[Caddy 2.11.4 원본 URI](https://github.com/caddyserver/caddy/blob/v2.11.4/modules/caddyhttp/replacer.go).

## 로컬 자동 검증

Python 3와 실행 중인 Docker가 필요하다. 저장소 루트에서 실행한다.

```bash
python3 scripts/test_caddy_actuator_boundary.py
```

스크립트는 `caddy:2.11.4-alpine`을 사용하고, 없으면 내려받는다. 테스트 전용 이름의 컨테이너 3개,
네트워크 1개, 임시 설정 파일을 생성한다. 호스트에는 임의의 loopback 포트 하나만 게시한다.
Spring·DB·인증정보·운영 리소스 없이 실제 저장소 Caddyfile을 검증한다.
종료 시 자신의 컨테이너·익명 볼륨·네트워크·임시 파일만 정리한다.

검증 범위:

1. blue/green 각각의 placeholder 치환 결과에 `caddy adapt`와 `caddy validate` 실행.
2. health, 일반 API, query, 본문, 메서드 보존. mock 색상 응답 확인.
3. GET/HEAD/POST, 인증 헤더 유무, trailing/duplicate slash, percent encoding,
   dot segment, 대소문자, path parameter에 대한 차단 행렬.
4. upstream 요청 로그의 고유 요청 ID를 비교해 허용 요청은 모두 도착하고 차단 요청은 0건임을 검증.
5. blue→green reload, 이전 설정 복원을 통한 green→blue, 실패한 reload 후 기존 설정 유지.
6. upstream 종료 후에도 차단 경로는 `404`; health는 `502`로 프록시 동작과 구분.

이 테스트의 proxy listener는 로컬 HTTP를 사용한다. 실제 도메인·TLS·HTTP→HTTPS redirect는
아래 운영 검증으로 확인한다. mock에서의 인증 헤더는 전달 여부 검증용 가짜 값이며,
유효한 사용자 JWT를 검증하는 Spring 테스트가 아니다.

## 적용 순서와 운영 검증

M1.1만 기존 배포 workflow로 배포한다. 기존 내부 health → Caddy 전환 → HTTPS health →
성공 시 이전 앱 종료 순서를 유지한다. 아래 명령은 배포 후 수행하며, 로컬 테스트 성공을
운영 배포 성공으로 간주하지 않는다.

### EC2 내부

`.env` 전체를 출력하거나 shell에 source하지 않는다.

```bash
cd /opt/pilaslot
API_DOMAIN="$(docker exec pilaslot-caddy printenv API_DOMAIN)"

docker exec pilaslot-caddy caddy version
docker exec pilaslot-caddy caddy validate \
  --config /etc/caddy/Caddyfile --adapter caddyfile

curl --fail --silent --show-error \
  --resolve "$API_DOMAIN:443:127.0.0.1" \
  "https://$API_DOMAIN/actuator/health"

curl --silent --show-error --path-as-is --include \
  --resolve "$API_DOMAIN:443:127.0.0.1" \
  "https://$API_DOMAIN/actuator/prometheus"

docker exec pilaslot-caddy \
  wget -qO- http://127.0.0.1:2019/config/apps/http/servers/
```

기대 결과: Caddy 2.11.4, 설정 유효, health `200` 및 `status=UP`, Prometheus `404 Not Found`.
마지막 GET은 실행 중인 admin 설정의 읽기 전용 조회다. 파일만 검증하지 말고 실제 활성 설정에서
Actuator matcher와 static 404가 reverse proxy보다 앞에 있고 upstream이 배포한 색상인지 확인한다.
admin 포트를 호스트에 새로 게시하지 않는다.

### EC2 외부

`API_DOMAIN`에는 현재 운영 API 도메인을 입력한다. 내부 `--resolve` 검사는 EC2 외부 검사를 대신하지 않는다.

```bash
API_DOMAIN='실제 API 도메인'

curl --fail --silent --show-error "https://$API_DOMAIN/actuator/health"

for request_path in \
  '/actuator' \
  '/actuator/env' \
  '/actuator/prometheus' \
  '/actuator/health/' \
  '/actuator/health/readiness' \
  '/%61ctuator/prometheus' \
  '/actuator%2Fprometheus' \
  '/actuator//prometheus?probe=m1-1' \
  '/x/../actuator/prometheus' \
  '/actuator;v=1/prometheus'
do
  curl --silent --show-error --path-as-is --include \
    "https://$API_DOMAIN$request_path"
done

curl --silent --show-error --include "http://$API_DOMAIN/actuator/prometheus"

curl --fail --silent --show-error --get \
  "https://$API_DOMAIN/api/v1/class-sessions" \
  --data-urlencode 'weekStart=2026-09-28'
```

HTTPS 차단 요청은 `404 Not Found`, HTTP 요청은 기존 HTTPS redirect를 기대한다.
API는 배포 전후 같은 요청의 상태와 응답 구조를 비교한다(위 날짜는 고정된 유효한 월요일).
정상 로그인 세션의 요청으로도 Prometheus가 `404`인지 확인한다. JWT는 문서·shell history·검증 로그에 남기지 않는다.

운영 404만으로 upstream 미도달을 단정하지 않는다. 고정 버전 로컬 테스트의 upstream 미도달 증거,
활성 Caddy 설정, 실제 외부 응답을 함께 확인한다. 배포 commit, 검증 시각, 활성 upstream,
health/API 결과와 차단 결과를 기록하되 인증정보는 제외한다.

## 롤백

### 배포 진행 중 실패

기존 workflow가 `Caddyfile.previous` 복원 → reload → health 재검증을 수행한다.
롤백도 실패하면 기존대로 신·구 앱을 모두 유지하고 수동 조사한다.
M1.1 최초 배포의 이전 설정에는 차단 규칙이 없을 수 있다. 복원되면 M1.1 미완료 상태로 처리한다.

### 배포 성공 후 회귀

성공 후에는 이전 앱이 제거되므로 `Caddyfile.previous`를 무조건 복사하지 않는다.
그 파일의 upstream은 이미 종료되었을 수 있다.

1. M1.1의 Caddy 변경만 되돌리는 revert를 검토해 기존 workflow로 배포한다.
2. 새 앱 기동·health·blue/green 전환을 정상 절차로 수행한다.
3. health와 API 회복을 확인한다. 차단을 되돌렸으면 M1.1 완료 상태도 취소한다.
4. 원인 수정 후 경계를 다시 배포·검증한다.

M1.2는 경계가 운영에서 검증된 뒤에만 진행한다. telemetry 배포 때 생성·사용되는 롤백용 설정에도
차단 규칙이 남아야 한다. M1.1 이전의 무차단 설정으로 telemetry 앱을 공개해서는 안 된다.

## 수용 기준

- 정규 health와 일반 API의 기존 전달 동작 유지.
- 차단 요청의 Caddy 404 및 mock upstream 도달 0건.
- 요청 변형·인증 헤더로 차단 우회 불가(위 테스트 행렬 기준).
- placeholder, 양방향 전환, reload 실패 및 설정 복원 회귀 검증 통과.
- 운영 배포 후 EC2 내부·외부 검증 완료 전까지 운영 경계 확보 완료로 보고하지 않음.
- Spring, Compose, 의존성, 업무 코드, 배포 workflow 및 M1.2/M1.3 변경 없음.
