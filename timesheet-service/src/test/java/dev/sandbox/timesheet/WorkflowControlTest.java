package dev.sandbox.timesheet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:workflow-controls;DB_CLOSE_DELAY=-1",
      "sandbox.log-file=${java.io.tmpdir}/timesheet-workflow-control-test.jsonl"
    })
class WorkflowControlTest {
  @Autowired TestRestTemplate http;
  @MockitoSpyBean WorkflowService workflow;

  private JsonNode completed(String caseId) throws InterruptedException {
    var response =
        http.postForEntity(
            "/api/benchmark/runs",
            Map.of("caseId", caseId, "seed", 37, "noise", 10),
            JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    String runId = response.getBody().path("runId").asText();
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    JsonNode status;
    do {
      status = http.getForObject("/api/benchmark/runs/" + runId, JsonNode.class);
      if (status.path("state").asText().equals("completed")
          || status.path("state").asText().equals("failed")) break;
      Thread.sleep(25);
    } while (System.nanoTime() < deadline);
    assertThat(status.path("state").asText()).isEqualTo("completed");
    assertThat(status.path("outcome").asText()).isEqualTo("healthy");
    return http.getForObject("/api/benchmark/runs/" + runId + "/context", JsonNode.class);
  }

  @Test
  void normalReportingWorkflowKeepsApprovalLedgerInvoiceAndExportConsistent() throws Exception {
    JsonNode context = completed("case-903");
    JsonNode entry = context.path("entries").get(0);
    assertThat(entry.path("status").asText()).isEqualTo("APPROVED");
    assertThat(entry.path("hours").decimalValue())
        .isEqualByComparingTo(context.path("fixture").path("approvedHours").decimalValue());
    assertThat(context.path("ledger").size()).isEqualTo(1);
    assertThat(context.path("ledger").get(0).path("hours").decimalValue())
        .isEqualByComparingTo(entry.path("hours").decimalValue());
    assertThat(context.path("observations").path("invoice").path("hours").decimalValue())
        .isEqualByComparingTo(entry.path("hours").decimalValue());
    assertThat(context.path("observations").path("export").path("rows").get(0).path("id").asLong())
        .isEqualTo(entry.path("id").asLong());
    assertThat(context.path("periods").get(0).path("closed").asBoolean()).isTrue();
    assertThat(context.path("correlationIds").size()).isGreaterThan(5);
  }

  @Test
  void invalidHoursProduceClientErrorAndValidFollowupIsPersisted() throws Exception {
    JsonNode context = completed("case-902");
    assertThat(context.path("observations").path("validation").path("invalidStatus").asInt())
        .isEqualTo(400);
    assertThat(context.path("entries").size()).isEqualTo(1);
    assertThat(context.path("entries").get(0).path("hours").decimalValue())
        .isEqualByComparingTo(context.path("fixture").path("approvedHours").decimalValue());
  }

  @Test
  void actualDependencyTimeoutRecoversThroughRetry() throws Exception {
    JsonNode context = completed("case-901");
    assertThat(context.path("observations").path("dependencyFirstStatus").asInt()).isEqualTo(504);
    assertThat(context.path("observations").path("dependency").path("delivered").asBoolean())
        .isTrue();
    assertThat(context.path("observations").path("dependency").path("attempts").asInt())
        .isEqualTo(2);
  }

  @Test
  void benchmarkInputIsValidatedBeforeAnyRunStarts() {
    var response =
        http.postForEntity(
            "/api/benchmark/runs",
            Map.of("caseId", "case-903", "seed", 1, "noise", 101),
            JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void evaluatorSnapshotsReadPersistenceIndependentlyOfBusinessListMethods() throws Exception {
    JsonNode original = completed("case-903");
    doReturn(List.of()).when(workflow).teams();
    doReturn(List.of()).when(workflow).projects();
    doReturn(List.of()).when(workflow).records();
    doReturn(List.of()).when(workflow).sheets();
    doReturn(List.of()).when(workflow).ledger();
    doReturn(List.of()).when(workflow).periods();
    doReturn(List.of()).when(workflow).audit();
    JsonNode context =
        http.getForObject(
            "/api/benchmark/runs/" + original.path("runId").asText() + "/context", JsonNode.class);
    for (String field :
        List.of("teams", "projects", "entries", "timesheets", "ledger", "periods", "audit")) {
      assertThat(context.path(field)).isEqualTo(original.path(field));
      assertThat(context.path(field).isEmpty()).isFalse();
    }
  }
}
