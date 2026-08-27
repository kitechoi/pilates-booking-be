package com.pilaslot.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_WEEK_START(HttpStatus.BAD_REQUEST, "weekStart는 월요일이어야 합니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    INVALID_LOGIN_CREDENTIALS(HttpStatus.UNAUTHORIZED, "회원번호 또는 비밀번호가 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "인증 토큰이 만료되었습니다."),
    CLASS_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "수업을 찾을 수 없습니다."),
    CLASS_SESSION_CANCELLED(HttpStatus.CONFLICT, "취소된 수업은 예약할 수 없습니다."),
    RESERVATION_NOT_OPEN(HttpStatus.CONFLICT, "아직 예약할 수 없는 수업입니다."),
    RESERVATION_CLOSED(HttpStatus.CONFLICT, "예약이 마감된 수업입니다."),
    DUPLICATE_RESERVATION(HttpStatus.CONFLICT, "이미 예약한 수업입니다."),
    WEEKLY_RESERVATION_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "주간 예약 가능 횟수를 초과했습니다."),
    CLASS_SESSION_FULL(HttpStatus.CONFLICT, "수업 정원이 마감되었습니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."),
    RESERVATION_ALREADY_CANCELLED(HttpStatus.CONFLICT, "이미 취소된 예약입니다."),
    CANCELLATION_CLOSED(HttpStatus.CONFLICT, "취소 가능 시간이 지났습니다."),
    WEEKLY_CANCELLATION_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "주간 취소 가능 횟수를 초과했습니다."),
    NO_USABLE_MEMBER_PASS(HttpStatus.CONFLICT, "수업일에 사용할 수 있는 수강권이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String message;
}
