package dev.sandbox.timesheet;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TimesheetService {
  final EmployeeRepository employees;
  final TimeEntryRepository entries;
  final Telemetry telemetry;

  public TimesheetService(
      EmployeeRepository employees, TimeEntryRepository entries, Telemetry telemetry) {
    this.employees = employees;
    this.entries = entries;
    this.telemetry = telemetry;
    if (employees.count() == 0) employees.save(new Employee("Alex Morgan", "Engineering"));
  }

  Employee employee(Long id) {
    return employees
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
  }

  TimeEntry entry(Long id) {
    return entries
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found"));
  }

  public TimeEntry save(TimeEntry input, Long id) {
    if (input.employeeId == null
        || input.date == null
        || input.hours == null
        || input.hours.signum() < 0
        || input.hours.compareTo(BigDecimal.valueOf(24)) > 0)
      throw new IllegalArgumentException(
          "employeeId, date and hours between 0 and 24 are required");
    employee(input.employeeId);
    TimeEntry result;
    if (id == null) {
      if (input.submissionId != null && !input.submissionId.isBlank()) {
        result =
            entries.findAll().stream()
                .filter(
                    e ->
                        e.employeeId.equals(input.employeeId)
                            && input.submissionId.equals(e.submissionId))
                .findFirst()
                .orElseGet(
                    () ->
                        new TimeEntry(
                            input.employeeId,
                            input.date,
                            input.hours,
                            input.description,
                            input.submissionId));
      } else {
        result =
            new TimeEntry(
                input.employeeId, input.date, input.hours, input.description, input.submissionId);
      }
    } else {
      result = entry(id);
    }
    result.employeeId = input.employeeId;
    result.date = input.date;
    result.hours = input.hours;
    result.description = input.description;
    result.submissionId = input.submissionId;
    // Retried submissions currently follow the same insertion path as first submissions.
    result = entries.save(result);
    if (result.submissionId != null && !result.submissionId.isBlank()) {
      final String key = result.submissionId;
      final Long employeeId = result.employeeId;
      long copies =
          entries.findAll().stream()
              .filter(e -> e.employeeId.equals(employeeId) && key.equals(e.submissionId))
              .count();
      if (copies > 1)
        telemetry.incident(
            "duplicate-submission",
            "duplicate_submission",
            "Submission retry created multiple time entries",
            null,
            "save",
            Map.of("submission_id", key, "copy_count", copies, "employee_id", employeeId));
    }
    return result;
  }

  public List<TimeEntry> list(Long employeeId, String month) {
    YearMonth period = month == null ? null : YearMonth.parse(month);
    return entries.findAll().stream()
        .filter(e -> employeeId == null || e.employeeId.equals(employeeId))
        .filter(e -> period == null || YearMonth.from(e.date).equals(period))
        .sorted(Comparator.comparing((TimeEntry e) -> e.date).thenComparing(e -> e.id))
        .toList();
  }

  public Map<String, Object> timesheet(Long employeeId, String month) {
    employee(employeeId);
    YearMonth period = YearMonth.parse(month);
    LocalDate start = period.atDay(1);
    LocalDate end = start.withDayOfMonth(31);
    List<TimeEntry> rows =
        entries.findAll().stream()
            .filter(
                e ->
                    e.employeeId.equals(employeeId)
                        && !e.date.isBefore(start)
                        && !e.date.isAfter(end))
            .toList();
    BigDecimal total =
        rows.stream()
            .map(e -> BigDecimal.valueOf(e.hours.intValue()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal exact = rows.stream().map(e -> e.hours).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.compareTo(exact) != 0)
      telemetry.incident(
          "monthly-total",
          "total_mismatch",
          "Monthly total differs from the persisted entry sum",
          null,
          "timesheet",
          Map.of(
              "employee_id",
              employeeId,
              "month",
              month,
              "reported_hours",
              total,
              "expected_hours",
              exact));
    return Map.of("employeeId", employeeId, "month", month, "entries", rows, "totalHours", total);
  }
}
