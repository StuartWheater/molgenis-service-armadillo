package org.molgenis.armadillo.controller;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.molgenis.armadillo.audit.AuditEventPublisher;
import org.molgenis.armadillo.command.Commands;
import org.molgenis.armadillo.config.ProfileConfigProps;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.audit.listener.AuditApplicationEvent;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@WebMvcTest(DevelopmentController.class)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
@Import(AuditEventPublisher.class)
class DevelopmentControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired AuditEventPublisher auditEventPublisher;
  @MockBean private ProfileConfigProps profileConfigProps;
  @MockBean private Commands commands;
  @MockBean private ApplicationEventPublisher applicationEventPublisher;

  @Mock(lenient = true)
  private Clock clock;

  @Captor private ArgumentCaptor<AuditApplicationEvent> eventCaptor;
  MockHttpSession session = new MockHttpSession();
  private String sessionId;
  private final Instant instant = Instant.now();

  @BeforeEach
  public void setup() {
    auditEventPublisher.setClock(clock);
    auditEventPublisher.setApplicationEventPublisher(applicationEventPublisher);
    when(clock.instant()).thenReturn(instant);
    sessionId = session.changeSessionId();
  }

  @Test
  @WithMockUser(roles = "SU")
  void testInstallPackageSu() throws Exception {
    String filename = "hello.txt";
    MockMultipartFile file =
        new MockMultipartFile(
            "file", filename, MediaType.TEXT_PLAIN_VALUE, "Hello, World!".getBytes());
    when(commands.installPackage(any(Principal.class), any(Resource.class), any(String.class)))
        .thenReturn(completedFuture(null));
    mockMvc
        .perform(MockMvcRequestBuilders.multipart("/install-package").file(file))
        .andExpect(status().is(204));
  }

  @Test
  @WithMockUser
  void testInstallPackageUser() throws Exception {
    String filename = "hello.txt";
    MockMultipartFile file =
        new MockMultipartFile(
            "file", filename, MediaType.TEXT_PLAIN_VALUE, "Hello, World!".getBytes());
    when(commands.installPackage(any(Principal.class), any(Resource.class), any(String.class)))
        .thenReturn(completedFuture(null));
    mockMvc
        .perform(MockMvcRequestBuilders.multipart("/install-package").file(file))
        .andExpect(status().is(403));
  }

  @Test
  void testGetPackageNameFromFilename() {
    String filename = "hello_world_test.tar.gz";
    DevelopmentController controller =
        new DevelopmentController(commands, auditEventPublisher, profileConfigProps);
    String pkgName = controller.getPackageNameFromFilename(filename);
    assertEquals("hello_world", pkgName);
  }
}