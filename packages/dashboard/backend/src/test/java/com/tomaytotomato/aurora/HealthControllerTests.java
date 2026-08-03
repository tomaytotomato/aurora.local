package com.tomaytotomato.aurora;

import com.github.dockerjava.api.DockerClient;
import com.tomaytotomato.aurora.controllers.HealthController;
import com.tomaytotomato.aurora.services.DockerService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies GET /api/health returns 200 with the expected JSON shape.
 *
 * Spring Boot 4 dropped @WebMvcTest / @AutoConfigureMockMvc and TestRestTemplate
 * from the test-autoconfigure module, and the full-context @SpringBootTest path
 * currently fails with a bean-override collision (pre-existing, tracked separately).
 * A standalone MockMvc setup keeps this test focused on HealthController alone,
 * with no Spring context, no docker socket, no DB.
 */
class HealthControllerTests {

  @Test
  void healthEndpointReturnsOkWithExpectedShape() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(eq("SELECT 1"), (Class<Integer>) any(Class.class))).thenReturn(1);

    DockerService docker = mock(DockerService.class, Mockito.RETURNS_DEEP_STUBS);
    when(docker.version()).thenReturn(Optional.of("test-docker-27.0.0"));

    HealthController controller = new HealthController(jdbc, docker);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

    mockMvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"))
        .andExpect(jsonPath("$.db").value(true))
        .andExpect(jsonPath("$.docker").value("test-docker-27.0.0"));
  }

  @Test
  void healthReportsDegradedWhenDbFails() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(eq("SELECT 1"), (Class<Integer>) any(Class.class)))
        .thenThrow(new RuntimeException("db down"));

    DockerService docker = mock(DockerService.class);
    when(docker.version()).thenReturn(Optional.empty());

    HealthController controller = new HealthController(jdbc, docker);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

    mockMvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("degraded"))
        .andExpect(jsonPath("$.db").value(false));
  }
}
