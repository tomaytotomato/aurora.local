package com.tomaytotomato.aurora.services;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.tomaytotomato.aurora.services.DockerService.LogTail;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 * B3 (v0.3): {@link DockerService#tailLogs} + {@link DockerService#inspectContainer}
 * unit contracts. Docker interactions are stubbed at the DockerClient
 * interface — no real socket, no async subscription.
 */
class DockerServiceLogsTests {

  private static Frame frame(StreamType type, String text) {
    Frame f = Mockito.mock(Frame.class);
    Mockito.when(f.getStreamType()).thenReturn(type);
    Mockito.when(f.getPayload()).thenReturn(text.getBytes(StandardCharsets.UTF_8));
    return f;
  }

  private static DockerClient clientWithLogs(List<Frame> frames) {
    DockerClient docker = Mockito.mock(DockerClient.class);
    LogContainerCmd cmd = Mockito.mock(LogContainerCmd.class);
    Mockito.when(docker.logContainerCmd(Mockito.anyString())).thenReturn(cmd);
    Mockito.when(cmd.withTail(Mockito.anyInt())).thenReturn(cmd);
    Mockito.when(cmd.withStdOut(Mockito.anyBoolean())).thenReturn(cmd);
    Mockito.when(cmd.withStdErr(Mockito.anyBoolean())).thenReturn(cmd);
    Mockito.when(cmd.withTimestamps(Mockito.anyBoolean())).thenReturn(cmd);
    Mockito.when(cmd.withFollowStream(Mockito.anyBoolean())).thenReturn(cmd);
    // exec(cb) fires all frames synchronously then returns the cb.
    Mockito.when(cmd.exec(Mockito.<ResultCallback.Adapter<Frame>>any()))
        .thenAnswer((Answer<ResultCallback.Adapter<Frame>>) inv -> {
          @SuppressWarnings("unchecked")
          ResultCallback.Adapter<Frame> cb = inv.getArgument(0);
          for (Frame f : frames) cb.onNext(f);
          cb.onComplete();
          return cb;
        });
    return docker;
  }

  @Test
  void tailLogs_parses_rfc3339_timestamps_from_docker() {
    List<Frame> frames = List.of(
        frame(StreamType.STDOUT, "2026-08-03T08:15:00.000000000Z boot: starting\n"),
        frame(StreamType.STDERR, "2026-08-03T08:15:01.000000000Z warn: slow disk\n"));
    DockerService svc = new DockerService(clientWithLogs(frames));

    LogTail tail = svc.tailLogs("c", 200, Duration.ofSeconds(1));
    assertEquals(2, tail.lines().size());
    assertEquals("2026-08-03T08:15:00.000000000Z", tail.lines().get(0).ts());
    assertEquals("stdout", tail.lines().get(0).stream());
    assertEquals("boot: starting", tail.lines().get(0).line());
    assertEquals("stderr", tail.lines().get(1).stream());
    assertEquals("warn: slow disk", tail.lines().get(1).line());
    assertFalse(tail.truncated());
  }

  @Test
  void tailLogs_handles_lines_without_timestamps() {
    // Older docker daemons + `docker logs --timestamps=false` upstream
    // paths may leak untimestamped frames. Must not crash on ts=null.
    List<Frame> frames = List.of(
        frame(StreamType.STDOUT, "plain line without timestamp\n"));
    DockerService svc = new DockerService(clientWithLogs(frames));
    LogTail tail = svc.tailLogs("c", 10, Duration.ofSeconds(1));
    assertEquals(1, tail.lines().size());
    assertNull(tail.lines().get(0).ts());
    assertEquals("plain line without timestamp", tail.lines().get(0).line());
  }

  @Test
  void tailLogs_splits_multi_line_frames() {
    // docker-java frames may bundle multiple newline-separated lines.
    // A single frame with two lines should surface as two LogLines.
    List<Frame> frames = List.of(
        frame(StreamType.STDOUT,
            "2026-08-03T08:15:00Z line one\n2026-08-03T08:15:01Z line two\n"));
    DockerService svc = new DockerService(clientWithLogs(frames));
    LogTail tail = svc.tailLogs("c", 10, Duration.ofSeconds(1));
    assertEquals(2, tail.lines().size());
    assertEquals("line one", tail.lines().get(0).line());
    assertEquals("line two", tail.lines().get(1).line());
  }

  @Test
  void tailLogs_caps_returned_list_at_requested_tail() {
    // Emit 5 frames but request tail=3. Docker's tail semantics are
    // usually correct; the belt-and-braces cap in tailLogs keeps
    // over-emission bounded when a chatty stderr slips in during close.
    List<Frame> frames = List.of(
        frame(StreamType.STDOUT, "l1\n"),
        frame(StreamType.STDOUT, "l2\n"),
        frame(StreamType.STDOUT, "l3\n"),
        frame(StreamType.STDOUT, "l4\n"),
        frame(StreamType.STDOUT, "l5\n"));
    DockerService svc = new DockerService(clientWithLogs(frames));
    LogTail tail = svc.tailLogs("c", 3, Duration.ofSeconds(1));
    assertEquals(3, tail.lines().size());
    assertEquals("l3", tail.lines().get(0).line());
    assertEquals("l5", tail.lines().get(2).line());
  }

  @Test
  void tailLogs_truncates_beyond_byte_cap() {
    // Big single-frame payload > 2 MiB → truncated=true.
    String big = "x".repeat((int) (DockerService.LOG_BYTES_CAP + 100L));
    List<Frame> frames = List.of(frame(StreamType.STDOUT, big + "\n"));
    DockerService svc = new DockerService(clientWithLogs(frames));
    LogTail tail = svc.tailLogs("c", 200, Duration.ofSeconds(1));
    assertTrue(tail.truncated(), "expected truncation flag on oversize payload");
  }

  @Test
  void tailLogs_returns_empty_on_docker_not_found() {
    DockerClient docker = Mockito.mock(DockerClient.class);
    LogContainerCmd cmd = Mockito.mock(LogContainerCmd.class);
    Mockito.when(docker.logContainerCmd(Mockito.anyString())).thenReturn(cmd);
    Mockito.when(cmd.withTail(Mockito.anyInt())).thenReturn(cmd);
    Mockito.when(cmd.withStdOut(Mockito.anyBoolean())).thenReturn(cmd);
    Mockito.when(cmd.withStdErr(Mockito.anyBoolean())).thenReturn(cmd);
    Mockito.when(cmd.withTimestamps(Mockito.anyBoolean())).thenReturn(cmd);
    Mockito.when(cmd.withFollowStream(Mockito.anyBoolean())).thenReturn(cmd);
    Mockito.when(cmd.exec(any()))
        .thenThrow(new NotFoundException("no such container"));

    DockerService svc = new DockerService(docker);
    LogTail tail = svc.tailLogs("ghost", 200, Duration.ofSeconds(1));
    assertEquals(List.of(), tail.lines());
    assertFalse(tail.truncated());
  }

  @Test
  void inspectContainer_returns_id_on_success() {
    DockerClient docker = Mockito.mock(DockerClient.class);
    InspectContainerCmd cmd = Mockito.mock(InspectContainerCmd.class);
    InspectContainerResponse res = Mockito.mock(InspectContainerResponse.class);
    Mockito.when(docker.inspectContainerCmd(Mockito.anyString())).thenReturn(cmd);
    Mockito.when(cmd.exec()).thenReturn(res);
    Mockito.when(res.getId()).thenReturn("abc123");

    DockerService svc = new DockerService(docker);
    assertEquals(Optional.of("abc123"), svc.inspectContainer("sonarr"));
  }

  @Test
  void inspectContainer_empty_on_not_found() {
    DockerClient docker = Mockito.mock(DockerClient.class);
    InspectContainerCmd cmd = Mockito.mock(InspectContainerCmd.class);
    Mockito.when(docker.inspectContainerCmd(Mockito.anyString())).thenReturn(cmd);
    Mockito.when(cmd.exec()).thenThrow(new NotFoundException("nope"));

    DockerService svc = new DockerService(docker);
    assertEquals(Optional.empty(), svc.inspectContainer("ghost"));
  }

  @Test
  void inspectContainer_empty_on_null_or_blank() {
    DockerService svc = new DockerService(Mockito.mock(DockerClient.class));
    assertEquals(Optional.empty(), svc.inspectContainer(null));
    assertEquals(Optional.empty(), svc.inspectContainer(""));
    assertEquals(Optional.empty(), svc.inspectContainer("   "));
  }

  @Test
  void inspectContainer_empty_when_daemon_throws_generic() {
    DockerClient docker = Mockito.mock(DockerClient.class);
    InspectContainerCmd cmd = Mockito.mock(InspectContainerCmd.class);
    Mockito.when(docker.inspectContainerCmd(Mockito.anyString())).thenReturn(cmd);
    Mockito.when(cmd.exec()).thenThrow(new RuntimeException("socket refused"));

    DockerService svc = new DockerService(docker);
    // Fail-closed so the controller emits 404 rather than propagating
    // a 500 for a transient daemon blip.
    assertEquals(Optional.empty(), svc.inspectContainer("c"));
  }
}
