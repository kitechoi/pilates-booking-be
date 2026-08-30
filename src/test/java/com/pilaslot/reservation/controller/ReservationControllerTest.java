package com.pilaslot.reservation.controller;

import com.pilaslot.classsession.domain.ClassType;
import com.pilaslot.global.config.TimeConfig;
import com.pilaslot.global.exception.BusinessException;
import com.pilaslot.global.exception.ErrorCode;
import com.pilaslot.global.exception.GlobalExceptionHandler;
import com.pilaslot.global.security.CustomAuthenticationEntryPoint;
import com.pilaslot.global.security.JwtAuthenticationFilter;
import com.pilaslot.global.security.JwtTokenProvider;
import com.pilaslot.global.security.SecurityConfig;
import com.pilaslot.reservation.domain.ReservationStatus;
import com.pilaslot.reservation.dto.response.MyReservationListResponse;
import com.pilaslot.reservation.dto.response.MyReservationResponse;
import com.pilaslot.reservation.dto.response.ReservationCreateResponse;
import com.pilaslot.reservation.service.ReservationQueryService;
import com.pilaslot.reservation.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(ReservationController.class)
@Import({
        GlobalExceptionHandler.class,
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        CustomAuthenticationEntryPoint.class,
        TimeConfig.class
})
class ReservationControllerTest {

    private static final LocalDate WEEK_START = LocalDate.of(2026, 8, 17);
    private static final LocalDateTime RESERVED_AT = LocalDateTime.of(2026, 8, 19, 13, 0, 1);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private ReservationQueryService reservationQueryService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUpAuthentication() {
        given(jwtTokenProvider.getMemberId("valid-token")).willReturn(42L);
    }

    @Test
    void returnsUnauthorizedWhenCreatingWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classSessionId\":10}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/api/v1/reservations"));
    }

    @Test
    void createsReservationUsingAuthenticatedMemberId() throws Exception {
        given(reservationService.reserve(42L, 10L)).willReturn(new ReservationCreateResponse(
                55L,
                10L,
                200L,
                "테스트 30회권",
                29,
                ReservationStatus.RESERVED,
                RESERVED_AT
        ));

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classSessionId\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(55))
                .andExpect(jsonPath("$.classSessionId").value(10))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.reservedAt").value("2026-08-19T13:00:01"));
        verify(reservationService).reserve(42L, 10L);
    }

    @Test
    void returnsInvalidRequestWhenCreateBodyIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.path").value("/api/v1/reservations"));
    }

    @Test
    void returnsInvalidRequestWhenClassSessionIdIsNull() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classSessionId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("classSessionId"));
    }

    @Test
    void returnsInvalidRequestForMalformedClassSessionId() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classSessionId\":\"not-a-number\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.path").value("/api/v1/reservations"));
    }

    @Test
    void returnsExistingErrorResponseWhenCreationFails() throws Exception {
        given(reservationService.reserve(42L, 999L))
                .willThrow(new BusinessException(ErrorCode.CLASS_SESSION_NOT_FOUND));

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classSessionId\":999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLASS_SESSION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("수업을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.path").value("/api/v1/reservations"));
    }

    @Test
    void returnsUnauthorizedWhenGettingWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/reservations")
                        .param("weekStart", WEEK_START.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/api/v1/reservations"));
    }

    @Test
    void returnsMyReservationsUsingAuthenticatedMemberId() throws Exception {
        given(reservationQueryService.getMyReservations(42L, WEEK_START, null))
                .willReturn(myReservationsResponse());

        mockMvc.perform(get("/api/v1/reservations")
                        .header("Authorization", "Bearer valid-token")
                        .param("weekStart", WEEK_START.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekStart").value("2026-08-17"))
                .andExpect(jsonPath("$.reservations[0].id").value(55))
                .andExpect(jsonPath("$.reservations[0].status").value("RESERVED"))
                .andExpect(jsonPath("$.reservations[0].reservedAt")
                        .value("2026-08-14T13:00:01"))
                .andExpect(jsonPath("$.reservations[0].cancelledAt").isEmpty())
                .andExpect(jsonPath("$.reservations[0].cancellable").value(true))
                .andExpect(jsonPath("$.reservations[0].cancellationDeadline")
                        .value("2026-08-17T11:00:00"))
                .andExpect(jsonPath("$.reservations[0].classSession.classSessionId").value(10))
                .andExpect(jsonPath("$.reservations[0].classSession.classType")
                        .value("CHAIR_BARREL"))
                .andExpect(jsonPath("$.reservations[0].classSession.startAt")
                        .value("2026-08-17T19:00:00"))
                .andExpect(jsonPath("$.reservations[0].classSession.endAt")
                        .value("2026-08-17T19:50:00"))
                .andExpect(jsonPath("$.reservations[0].classSession.instructor.instructorId")
                        .value(3))
                .andExpect(jsonPath("$.reservations[0].classSession.instructor.name")
                        .value("김라라"))
                .andExpect(jsonPath(
                        "$.reservations[0].classSession.instructor.profileImageUrl"
                ).value("https://example.com/instructors/kim.jpg"));
        verify(reservationQueryService).getMyReservations(42L, WEEK_START, null);
    }

    @Test
    void returnsInvalidRequestWhenWeekStartIsMissing() throws Exception {
        mockMvc.perform(get("/api/v1/reservations")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("weekStart"));
    }

    @Test
    void returnsInvalidRequestForMalformedWeekStart() throws Exception {
        mockMvc.perform(get("/api/v1/reservations")
                        .header("Authorization", "Bearer valid-token")
                        .param("weekStart", "2026-08-xx"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("weekStart"));
    }

    @Test
    void returnsInvalidWeekStartForNonMonday() throws Exception {
        LocalDate tuesday = WEEK_START.plusDays(1);
        given(reservationQueryService.getMyReservations(42L, tuesday, null))
                .willThrow(new BusinessException(ErrorCode.INVALID_WEEK_START));

        mockMvc.perform(get("/api/v1/reservations")
                        .header("Authorization", "Bearer valid-token")
                        .param("weekStart", tuesday.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WEEK_START"))
                .andExpect(jsonPath("$.message").value("weekStart는 월요일이어야 합니다."));
    }

    @Test
    void returnsInvalidRequestForUnknownStatus() throws Exception {
        mockMvc.perform(get("/api/v1/reservations")
                        .header("Authorization", "Bearer valid-token")
                        .param("weekStart", WEEK_START.toString())
                        .param("status", "foo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    @Test
    void acceptsReservedStatusFilter() throws Exception {
        given(reservationQueryService.getMyReservations(
                42L,
                WEEK_START,
                ReservationStatus.RESERVED
        )).willReturn(new MyReservationListResponse(WEEK_START, List.of()));

        mockMvc.perform(get("/api/v1/reservations")
                        .header("Authorization", "Bearer valid-token")
                        .param("weekStart", WEEK_START.toString())
                        .param("status", "RESERVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations").isEmpty());
        verify(reservationQueryService).getMyReservations(
                42L,
                WEEK_START,
                ReservationStatus.RESERVED
        );
    }

    @Test
    void acceptsCancelledStatusFilter() throws Exception {
        given(reservationQueryService.getMyReservations(
                42L,
                WEEK_START,
                ReservationStatus.CANCELLED
        )).willReturn(new MyReservationListResponse(WEEK_START, List.of()));

        mockMvc.perform(get("/api/v1/reservations")
                        .header("Authorization", "Bearer valid-token")
                        .param("weekStart", WEEK_START.toString())
                        .param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations").isEmpty());
        verify(reservationQueryService).getMyReservations(
                42L,
                WEEK_START,
                ReservationStatus.CANCELLED
        );
    }

    @Test
    void returnsUnauthorizedWhenCancellingWithoutToken() throws Exception {
        mockMvc.perform(delete("/api/v1/reservations/55"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/api/v1/reservations/55"));
    }

    @Test
    void cancelsReservationUsingAuthenticatedMemberId() throws Exception {
        mockMvc.perform(delete("/api/v1/reservations/55")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(reservationService).cancel(42L, 55L);
    }

    @Test
    void returnsInvalidRequestForMalformedReservationId() throws Exception {
        mockMvc.perform(delete("/api/v1/reservations/not-a-number")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.path").value("/api/v1/reservations/not-a-number"));
    }

    @Test
    void returnsExistingErrorResponseWhenCancellationFails() throws Exception {
        willThrow(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND))
                .given(reservationService)
                .cancel(42L, 999L);

        mockMvc.perform(delete("/api/v1/reservations/999")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("예약을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/reservations/999"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    private MyReservationListResponse myReservationsResponse() {
        MyReservationResponse.InstructorResponse instructor =
                new MyReservationResponse.InstructorResponse(
                        3L,
                        "김라라",
                        "https://example.com/instructors/kim.jpg"
                );
        MyReservationResponse.ClassSessionSummary classSession =
                new MyReservationResponse.ClassSessionSummary(
                        10L,
                        ClassType.CHAIR_BARREL,
                        LocalDateTime.of(2026, 8, 17, 19, 0),
                        LocalDateTime.of(2026, 8, 17, 19, 50),
                        instructor
                );
        MyReservationResponse reservation = new MyReservationResponse(
                55L,
                ReservationStatus.RESERVED,
                LocalDateTime.of(2026, 8, 14, 13, 0, 1),
                null,
                true,
                LocalDateTime.of(2026, 8, 17, 11, 0),
                200L,
                "테스트 30회권",
                classSession
        );
        return new MyReservationListResponse(WEEK_START, List.of(reservation));
    }
}
