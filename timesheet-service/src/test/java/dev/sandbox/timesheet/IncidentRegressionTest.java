package dev.sandbox.timesheet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:regression;DB_CLOSE_DELAY=-1")
@Transactional
class IncidentRegressionTest {
  @Autowired TimesheetService service;
  @Autowired EmployeeRepository employees;
  @Autowired TimeEntryRepository entries;

  @Test
  void februaryTimesheetUsesActualMonthEndAndDoesNotFail() {
    EmployeeRepository employees = mock(EmployeeRepository.class);
    TimeEntryRepository entries = mock(TimeEntryRepository.class);
    Telemetry telemetry = mock(Telemetry.class);

    Employee employee = new Employee("Alex Morgan", "Engineering");
    employee.id = 101L;
    when(employees.count()).thenReturn(1L);
    when(employees.findById(101L)).thenReturn(Optional.of(employee));

    TimeEntry february12 =
        new TimeEntry(101L, LocalDate.of(2026, 2, 12), new BigDecimal("8"), "work", "feb-12");
    TimeEntry february28 =
        new TimeEntry(101L, LocalDate.of(2026, 2, 28), new BigDecimal("7"), "work", "feb-28");
    TimeEntry march1 =
        new TimeEntry(101L, LocalDate.of(2026, 3, 1), new BigDecimal("5"), "work", "mar-01");
    when(entries.findAll()).thenReturn(List.of(february12, february28, march1));

    TimesheetService service = new TimesheetService(employees, entries, telemetry);

    Map<String, Object> report = assertDoesNotThrow(() -> service.timesheet(101L, "2026-02"));

    @SuppressWarnings("unchecked")
    List<TimeEntry> rows = (List<TimeEntry>) report.get("entries");
    assertThat(rows).contains(february28);
    assertThat(rows).doesNotContain(march1);
    assertThat((BigDecimal) report.get("totalHours")).isEqualByComparingTo("15");
    verify(telemetry, never())
        .incident(
            eq("month-boundary"), eq("month_boundary"), anyString(), any(), eq("timesheet"), any());
  }

  @Test
  void retriedSubmissionWithSameSubmissionIdDoesNotCreateDuplicateEntry() {
    Employee employee = employees.save(new Employee("Regression Test", "QA"));
    String submissionId = UUID.randomUUID().toString();
    TimeEntry input =
        new TimeEntry(
            employee.id,
            LocalDate.parse("2026-01-12"),
            new BigDecimal("8"),
            "Retried submission",
            submissionId);

    TimeEntry first = service.save(input, null);
    TimeEntry second = service.save(input, null);

    long persisted =
        entries.findAll().stream()
            .filter(e -> employee.id.equals(e.employeeId) && submissionId.equals(e.submissionId))
            .count();
    assertEquals(1L, persisted, "Exactly one entry should exist for a retried submission");
    assertNotNull(first.id);
    assertNotNull(second.id);
    assertEquals(first.id, second.id, "A retry should resolve to the already persisted entry");
  }
}
