# Local fixtures

로컬 데이터는 애플리케이션 프로파일이나 시작 시점 Seed로 생성하지 않는다.

필요한 개발자가 명시적으로 `local-fixture.sql`을 실행한다. 비밀번호에는 평문이 아니라 Spring Security와 호환되는 BCrypt 해시를 `password_hash` 변수로 전달해야 한다.

운영 환경에서는 이 파일을 사용하지 않고, 별도 검토된 운영 쿼리와 백필 런북을 사용한다.
