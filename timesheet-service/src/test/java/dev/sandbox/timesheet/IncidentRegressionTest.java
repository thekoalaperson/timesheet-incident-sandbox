package dev.sandbox.timesheet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import org.junit.jupiter.api.Test;

class IncidentRegressionTest {
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

    Map<String, Object> report =
        assertDoesNotThrow(() -> service.timesheet(101L, "2026-02"));

    @SuppressWarnings("unchecked")
    List<TimeEntry> rows = (List<TimeEntry>) report.get("entries");
    assertThat(rows).contains(february28);
    assertThat(rows).doesNotContain(march1);
    assertThat((BigDecimal) report.get("totalHours")).isEqualByComparingTo("15");
    verify(telemetry, never())
        .incident(
            eq("month-boundary"),
            eq("month_boundary"),
            anyString(),
            any(),
            eq("timesheet"),
            any());
  }
}
