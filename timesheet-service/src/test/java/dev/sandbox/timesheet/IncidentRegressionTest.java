package dev.sandbox.timesheet;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
public class IncidentRegressionTest {

  @Autowired TimesheetService service;
  @Autowired EmployeeRepository employees;
  @Autowired TimeEntryRepository entries;

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
