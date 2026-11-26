package com.hanmaum.backend.careitem.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hanmaum.backend.careitem.repository.CareItemApplicationRepository;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiFieldError;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CareItemDateValidationTests {
  private static final Map<String, String> DATE_FIELDS =
      Map.of(
          "SCHEDULE",
          "scheduledDate",
          "TASK",
          "dueDate",
          "OBSERVATION",
          "observedDate",
          "GUIDANCE",
          "receivedDate");
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final CareItemApplicationRepository repository =
      mock(CareItemApplicationRepository.class);
  private final CareItemApplicationService applications =
      new CareItemApplicationService(repository, mapper);
  private final UUID space = UUID.randomUUID();

  static Stream<Arguments> invalidDates() {
    return DATE_FIELDS.entrySet().stream()
        .flatMap(
            field ->
                Stream.of("CREATE", "UPDATE", "RECONFIRMATION")
                    .flatMap(
                        operation ->
                            Stream.of(
                                    "0000-01-01",
                                    "0000-02-29",
                                    "1900-02-29",
                                    "2026-1-2",
                                    "10000-01-01")
                                .map(
                                    date ->
                                        Arguments.of(
                                            field.getKey(), field.getValue(), operation, date))));
  }

  @ParameterizedTest
  @MethodSource("invalidDates")
  void rejectsDatesOutsideTheAiContractWithTheOriginalFieldError(
      String type, String field, String operation, String date) {
    var values = values();
    values.withObject("fields").put(field, date);
    assertThatThrownBy(() -> prepare(type, operation, values))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> {
              assertThat(error.code()).isEqualTo(CommonErrorCode.INVALID_REQUEST);
              assertThat(error.errors())
                  .containsExactly(
                      new ApiFieldError("values.fields." + field, "유효한 날짜 또는 시각을 입력해주세요."));
            });
  }

  static Stream<Arguments> validDates() {
    return DATE_FIELDS.entrySet().stream()
        .flatMap(
            field ->
                Stream.of("CREATE", "UPDATE", "RECONFIRMATION")
                    .flatMap(
                        operation ->
                            Stream.of("0001-01-01", "9999-12-31", "2000-02-29", "2024-02-29", null)
                                .map(
                                    date ->
                                        Arguments.of(
                                            field.getKey(), field.getValue(), operation, date))));
  }

  @ParameterizedTest
  @MethodSource("validDates")
  void preservesValidBoundaryLeapAndNullDates(
      String type, String field, String operation, String date) {
    var values = values();
    values.withObject("fields").put(field, date);
    var actual = prepare(type, operation, values).path("fields").path(field);
    if (date == null) assertThat(actual.isNull()).isTrue();
    else assertThat(actual.asText()).isEqualTo(date);
  }

  static Stream<Arguments> omittedDates() {
    return DATE_FIELDS.entrySet().stream()
        .flatMap(
            field ->
                Stream.of("CREATE", "UPDATE", "RECONFIRMATION")
                    .map(operation -> Arguments.of(field.getKey(), field.getValue(), operation)));
  }

  @ParameterizedTest
  @MethodSource("omittedDates")
  void omittedDatesRemainUnknownOrKeepTheExistingValue(
      String type, String field, String operation) {
    var actual = prepare(type, operation, values()).path("fields").path(field);
    if (operation.equals("UPDATE")) assertThat(actual.asText()).isEqualTo("2026-10-15");
    else assertThat(actual.isNull()).isTrue();
  }

  private ObjectNode values() {
    var values = mapper.createObjectNode().put("title", "확인한 항목").putNull("description");
    values.putObject("fields");
    return values;
  }

  private ObjectNode prepare(String type, String operation, ObjectNode values) {
    if (operation.equals("CREATE")) {
      values.put("itemType", type);
      return applications
          .prepare(space, type, operation, null, values, "values", CommonErrorCode.VERSION_CONFLICT)
          .body();
    }
    UUID id = UUID.randomUUID();
    var current = mapper.createObjectNode().put("title", "기존 항목").putNull("description");
    current.putObject("fields").put(DATE_FIELDS.get(type), "2026-10-15");
    String status =
        switch (type) {
          case "SCHEDULE" -> "SCHEDULED";
          case "TASK" -> "NOT_STARTED";
          default -> null;
        };
    when(repository.lock(space, id))
        .thenReturn(
            Optional.of(
                new CareItemApplicationRepository.Item(
                    id, type, 1L, UUID.randomUUID(), status, current.toString(), true, true)));
    var locked = applications.lock(space, id);
    if (operation.equals("RECONFIRMATION"))
      return applications.reconfirmedValues(space, locked, values);
    return applications
        .prepare(space, type, operation, locked, values, "values", CommonErrorCode.VERSION_CONFLICT)
        .body();
  }
}
