# Member pass expand–contract runbook

이 기능은 애플리케이션 롤백이 Flyway 변경을 되돌리지 않는다는 전제로 배포한다.

## 1. 사전 점검과 프리즈

- `main` 머지를 중단한다.
- `production` concurrency group의 실행·대기 workflow가 0건인지 확인한다.
- 운영 DB를 백업한다.
- 기존 예약 수와 상태별 건수, 직접 SQL 취소 이력을 확인한다.

## 2. Expand

`V3__expand_member_pass_domain.sql`만 먼저 적용한다. 신규 테이블과 nullable 예약 컬럼은 구버전과 호환된다. 이 단계에는 다음 Contract 제약을 적용하지 않는다.

- `reservation.member_pass_id NOT NULL`
- 예약–회원 수강권 복합 FK
- 예약 상태–`cancellation_source` 연동 CHECK

## 3. 예약 쓰기 프리즈와 백필

예약 생성·취소를 차단한 상태에서 상품, 회원 수강권, `MIGRATION_OPENING`, 기존 예약의 `member_pass_id`를 백필한다. `RESERVED` 예약의 `cancellation_source`는 NULL로 둔다.

`CANCELLED` 예약은 예약 ID별 취소 근거를 확인해 `MEMBER`, `ADMIN`, `CLASS_SESSION` 중 실제 출처를 기록한다. 기존 회원 취소 API로 처리됐음이 확인된 행만 `MEMBER`로 분류하고, 직접 SQL 변경이나 운영 조치가 있었던 행은 감사 로그와 운영 기록을 기준으로 분류한다. 근거를 확인할 수 없는 행을 임의로 `MEMBER` 처리하지 않으며, 미분류 행이 남으면 Contract 배포를 중단한다.

## 4. 대사와 호환 기능 버전 배포

[`member-pass-reconciliation.sql`](member-pass-reconciliation.sql)의 모든 조회가 0행인지 확인한다. 이후 nullable 호환 기능 버전을 배포하고 다음 smoke test를 수행한다.

- 예약 1건 생성: 원래 수강권 연결, 잔액 -1, debit history 1건
- 동일 예약 취소: 같은 수강권 잔액 +1, refund history 1건, `cancellation_source=MEMBER`
- 재대사 결과 0행

실패 시 예약 쓰기 프리즈를 유지하고 구버전으로 복귀한다. DB가 nullable이므로 구버전 INSERT와 충돌하지 않는다.

## 5. 쓰기 재개와 안정화

smoke test와 대사를 통과한 뒤 쓰기를 재개한다. 운영 안정화 기간 동안 대사 쿼리를 반복 실행한다.

## 6. Contract 별도 배포

롤백 후보도 수강권 컬럼을 기록하는 호환 버전임을 확인한 후 별도 Flyway migration과 동일 배포의 JPA 매핑에서 다음을 적용한다.

```sql
ALTER TABLE reservation ALTER COLUMN member_pass_id SET NOT NULL;

ALTER TABLE reservation
    DROP CONSTRAINT fk_reservation_member_pass_expand,
    ADD CONSTRAINT fk_reservation_member_pass_member
        FOREIGN KEY (member_pass_id, member_id)
        REFERENCES member_pass (id, member_id),
    ADD CONSTRAINT ck_reservation_cancellation_source
        CHECK (
            (status = 'RESERVED' AND cancellation_source IS NULL)
            OR (
                status = 'CANCELLED'
                AND cancellation_source IN ('MEMBER', 'ADMIN', 'CLASS_SESSION')
            )
        );
```

JPA의 `Reservation.memberPass`도 이때 `optional=false`, `nullable=false`로 강화한다.
