package dev.sandbox.timesheet;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimeEntryRepository extends JpaRepository<TimeEntry, Long> {
  Optional<TimeEntry> findByEmployeeIdAndSubmissionId(Long employeeId, String submissionId);
}
