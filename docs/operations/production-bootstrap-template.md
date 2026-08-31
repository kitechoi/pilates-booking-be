# 운영 데이터 최초 입력 템플릿

이 문서는 **사람이 명시적으로** 운영 DB에 강사·수강권 상품·회원·수강권 데이터를 처음 입력할 때 쓰는 절차다.
`dev/local-fixture.sql`이 로컬용인 것과 같은 위치의 운영용 스크립트가
[`production-bootstrap-template.sql`](production-bootstrap-template.sql)이다.

## 실행 전 확인

- 이 스크립트는 운영 RDS에 실제 데이터를 남긴다. 실행 전 어떤 섹션을 실제로
  넣을지 이 문서 하단의 "입력할 데이터 초안"을 사람이 검토·확정한다.
- Instructor/ClassSession은 unique 제약이 없어 완전한 재실행 안전성을 보장할
  수 없다. 이미 넣은 적이 있는 섹션은 실행 전에 반드시 아래 "중복 확인" 쿼리로
  기존 데이터 존재 여부를 먼저 확인한다.
- Member/PassProduct/MemberPass 섹션은 `ON CONFLICT`/`WHERE NOT EXISTS`로
  재실행해도 안전하다.
- 비밀번호는 평문이 아니라 BCrypt 해시로 전달한다 (`dev/local-fixture.sql`과 동일한 방식).

## 중복 확인 쿼리 (Instructor/ClassSession 실행 전 필수)

```sql
SELECT id, name FROM instructor ORDER BY id;
SELECT id, class_type, start_at, capacity FROM class_session ORDER BY start_at;
```

결과가 이미 있다면 해당 섹션은 스크립트에서 빼고 실행한다.

## 실행 방법

운영 RDS는 `pilaslot-rds-sg`가 EC2 보안 그룹에서만 접근을 허용하므로, EC2에서
SSM Session Manager로 접속한 뒤 다음과 같이 실행한다 (로컬에 psql 클라이언트가
없으므로 `postgres:16-alpine`을 일회용 클라이언트로 사용).

```bash
docker run --rm -i --network host postgres:16-alpine \
  psql "$PILASLOT_DB_URL" \
  -v password_hash='<BCrypt 해시>' \
  -f production-bootstrap-template.sql
```

세션 매니저 웹 터미널은 여러 줄 붙여넣기가 깨질 수 있으므로, SQL 파일을 base64로
인코딩해 한 줄로 전달하는 방식을 권장한다 (수강권 도메인 운영 이관 때 썼던 방법과 동일).

## 입력할 데이터

### 1. PassProduct (수강권 상품 카탈로그)

| code | name | default_price | default_count | default_validity_days |
|---|---|---|---|---|
| `PILATES_30_90D` | 필라테스 30회권 | 450000 | 30 | 90 |
| `PILATES_10_60D` | 필라테스 10회권 | 200000 | 10 | 60 |

한 번에 여러 상품을 등록해도 되지만, 실제로 파는 상품만 등록한다 — 나중에
바꿀 걸 미리 등록해두지 않는다.

### 2. Instructor (강사) — 이미 등록돼 있는지 위 중복 확인 쿼리로 먼저 확인

이미 데모용으로 넣어둔 강사 데이터가 있다면 이 섹션은 생략하고 기존 데이터를
그대로 쓴다. 신규 강사가 추가된 경우에만 그 강사만 추가로 넣는다.

### 3. ClassSession (수업)

정원 규칙을 반드시 지킨다 — `ANIMAL_FLOW`만 8명, 나머지 `REFORMER`/
`CHAIR_BARREL`/`RANDOM`은 4명. 예약 오픈 시각은 해당 수업 주차 전주
금요일 13:00 (`reservation_open_at = 해당 주 월요일 00:00 - 3일 + 13시간`).
이미 반복 시간표가 등록돼 있다면 생략한다.

### 4. Member (첫 실제 회원)

| member_number | name | phone_number |
|---|---|---|
| `1234` | `TEST` | `01012341234` |

최초 비밀번호는 `member_number`와 동일해야 하므로, `password_hash`는
`1234`를 BCrypt로 해싱한 값을 넘긴다.

### 5. MemberPass (그 회원에게 발급하는 수강권)

- `PILATES_30_90D`(30회권) 발급
- `price_paid` 450000 (정가 그대로 결제)
- `valid_from`은 발급일(오늘), `expires_on`은 발급일 + 90일
