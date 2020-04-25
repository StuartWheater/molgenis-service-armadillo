package org.molgenis.datashield;

import static java.time.Instant.now;
import static java.util.Arrays.asList;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.molgenis.datashield.DataShieldUtils.serializeExpression;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
import static org.springframework.http.MediaType.TEXT_PLAIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.molgenis.datashield.command.Commands;
import org.molgenis.datashield.command.DataShieldCommandDTO;
import org.molgenis.datashield.exceptions.DataShieldExpressionException;
import org.molgenis.datashield.service.DataShieldExpressionRewriter;
import org.molgenis.r.model.RPackage;
import org.obiba.datashield.r.expr.ParseException;
import org.rosuda.REngine.REXP;
import org.rosuda.REngine.REXPDouble;
import org.rosuda.REngine.REXPRaw;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(DataController.class)
class DataControllerTest {

  public static RPackage BASE =
      RPackage.builder()
          .setName("base")
          .setVersion("3.6.1")
          .setBuilt("3.6.1")
          .setLibPath("/usr/local/lib/R/site-library")
          .build();

  public static RPackage DESC =
      RPackage.builder()
          .setName("desc")
          .setVersion("1.2.0")
          .setBuilt("3.6.1")
          .setLibPath("/usr/local/lib/R/site-library")
          .build();

  @Autowired private MockMvc mockMvc;
  @MockBean private DataShieldExpressionRewriter expressionRewriter;
  @MockBean private Commands commands;
  @Mock private REXP rexp;

  @Test
  @WithMockUser
  void testGetPackages() throws Exception {
    when(commands.getPackages()).thenReturn(completedFuture(List.of(BASE, DESC)));
    mockMvc
        .perform(get("/packages"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(APPLICATION_JSON))
        .andExpect(content().json("[{\"name\": \"base\"}, {\"name\": \"desc\"}]"));
  }

  @Test
  @WithMockUser
  void getGetTables() throws Exception {
    when(commands.evaluate("base::local(base::ls(.DSTableEnv))")).thenReturn(completedFuture(rexp));
    when(rexp.asStrings()).thenReturn(new String[] {"datashield.PATIENT", "datashield.SAMPLE"});

    mockMvc
        .perform(get("/tables"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(APPLICATION_JSON))
        .andExpect(content().json("[\"datashield.PATIENT\",\"datashield.SAMPLE\"]"));
  }

  @Test
  @WithMockUser
  void testTableExists() throws Exception {
    when(commands.evaluate("base::local(base::ls(.DSTableEnv))")).thenReturn(completedFuture(rexp));
    when(rexp.asStrings()).thenReturn(new String[] {"datashield.PATIENT", "datashield.SAMPLE"});

    mockMvc.perform(head("/tables/datashield.PATIENT")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser
  void testTableNotFound() throws Exception {
    when(commands.evaluate("base::local(base::ls(.DSTableEnv))")).thenReturn(completedFuture(rexp));
    when(rexp.asStrings()).thenReturn(new String[] {});

    mockMvc.perform(head("/tables/datashield.PATIENT")).andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser
  void getGetSymbols() throws Exception {
    when(commands.evaluate("base::ls()")).thenReturn(completedFuture(rexp));
    when(rexp.asStrings()).thenReturn(new String[] {"D"});

    mockMvc
        .perform(get("/symbols"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(APPLICATION_JSON))
        .andExpect(content().json("[\"D\"]"));
  }

  @Test
  @WithMockUser
  void deleteSymbol() throws Exception {
    when(commands.evaluate("base::rm(D)")).thenReturn(completedFuture(null));
    mockMvc.perform(delete("/symbols/D")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser
  void testGetLastResultNoResult() throws Exception {
    MvcResult result =
        mockMvc.perform(get("/lastresult").accept(APPLICATION_OCTET_STREAM)).andReturn();
    mockMvc.perform(asyncDispatch(result)).andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser
  void testGetLastResult() throws Exception {
    byte[] bytes = {0x0, 0x1, 0x2};
    when(commands.getLastExecution()).thenReturn(Optional.of(completedFuture(new REXPRaw(bytes))));

    MvcResult result =
        mockMvc.perform(get("/lastresult").accept(APPLICATION_OCTET_STREAM)).andReturn();
    mockMvc
        .perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andExpect(content().contentType(APPLICATION_OCTET_STREAM))
        .andExpect(content().bytes(bytes));
  }

  @Test
  @WithMockUser
  void testGetLastCommandNotFound() throws Exception {
    mockMvc.perform(get("/lastcommand").accept(APPLICATION_JSON)).andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser
  void testGetLastCommand() throws Exception {
    DataShieldCommandDTO command =
        DataShieldCommandDTO.builder()
            .createDate(now())
            .status(Commands.DataShieldCommandStatus.PENDING)
            .expression("expression")
            .id(UUID.randomUUID())
            .withResult(true)
            .build();
    when(commands.getLastCommand()).thenReturn(Optional.of(command));

    mockMvc
        .perform(get("/lastcommand").accept(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentType(APPLICATION_JSON))
        .andExpect(jsonPath("status").value("PENDING"));
  }

  @Test
  @WithMockUser(username = "henk")
  void testDeleteWorkspace() throws Exception {
    mockMvc.perform(delete("/workspaces/test")).andExpect(status().isOk());

    verify(commands).removeWorkspace("henk/test.RData");
  }

  @Test
  @WithMockUser(username = "henk")
  void testSaveWorkspace() throws Exception {
    when(commands.saveWorkspace("henk/test.RData")).thenReturn(completedFuture(null));

    mockMvc.perform(post("/workspaces/test")).andExpect(status().isCreated());
  }

  @Test
  @WithMockUser
  void testSaveWorkspaceWrongId() throws Exception {
    mockMvc
        .perform(post("/workspaces/)(wrongid"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.message")
                .value(
                    "saveUserWorkspace.id: Please use only letters, numbers, dashes or underscores"));
  }

  @Test
  @WithMockUser(username = "henk")
  void testLoadWorkspace() throws Exception {
    when(commands.loadUserWorkspace("henk/blah.RData")).thenReturn(completedFuture(null));

    mockMvc.perform(post("/load-workspace?id=blah")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser
  void testExecute() throws Exception {
    String expression = "meanDS(D$age)";
    String rewrittenExpression = "dsBase::meanDS(D$age)";
    when(expressionRewriter.rewriteAggregate(expression)).thenReturn(rewrittenExpression);
    String serializedExpression = serializeExpression(rewrittenExpression);

    when(commands.evaluate(serializedExpression))
        .thenReturn(completedFuture(new REXPRaw(new byte[0])));

    mockMvc
        .perform(
            post("/execute")
                .accept(APPLICATION_OCTET_STREAM)
                .contentType(TEXT_PLAIN)
                .content(expression))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser
  void testExecuteAsync() throws Exception {
    when(commands.evaluate("meanDS(D$age)")).thenReturn(completedFuture(new REXPDouble(36.6)));

    MvcResult result =
        mockMvc
            .perform(
                post("/execute?async=true")
                    .contentType(TEXT_PLAIN)
                    .content("meanDS(D$age)")
                    .accept(APPLICATION_OCTET_STREAM))
            .andReturn();
    mockMvc
        .perform(asyncDispatch(result))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost/lastcommand"))
        .andExpect(content().string(""));
  }

  @Test
  @WithMockUser
  void testAssign() throws Exception {
    String expression = "meanDS(D$age)";
    String rewrittenExpression = "dsBase::meanDS(D$age)";
    when(expressionRewriter.rewriteAssign(expression)).thenReturn(rewrittenExpression);

    CompletableFuture<Void> assignment = new CompletableFuture<>();
    when(commands.assign("E", rewrittenExpression)).thenReturn(assignment);

    MvcResult result =
        mockMvc.perform(post("/symbols/E").contentType(TEXT_PLAIN).content(expression)).andReturn();

    assignment.complete(null);
    mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
  }

  @Test
  @WithMockUser
  void testAssignSyntaxError() throws Exception {
    String expression = "meanDS(D$age";
    doThrow(new DataShieldExpressionException(new ParseException("Missing end bracket")))
        .when(expressionRewriter)
        .rewriteAssign(expression);

    MvcResult mvcResult =
        mockMvc
            .perform(post("/symbols/D").contentType(TEXT_PLAIN).content(expression))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertEquals(
        "Error parsing expression: Missing end bracket",
        mvcResult.getResolvedException().getMessage());
  }

  @Test
  @WithMockUser
  void testAsyncAssignExecutionFails() throws Exception {
    String expression = "meanDS(D$age)";
    doThrow(new DataShieldExpressionException(new ParseException("Missing end bracket")))
        .when(expressionRewriter)
        .rewriteAssign(expression);

    MvcResult mvcResult =
        mockMvc
            .perform(post("/symbols/D").contentType(TEXT_PLAIN).content(expression))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertEquals(
        "Error parsing expression: Missing end bracket",
        mvcResult.getResolvedException().getMessage());
  }

  @Test
  @WithMockUser
  void testExecuteSyntaxError() throws Exception {
    String expression = "meanDS(D$age";
    doThrow(new DataShieldExpressionException(new ParseException("Missing end bracket")))
        .when(expressionRewriter)
        .rewriteAggregate(expression);

    MvcResult mvcResult =
        mockMvc
            .perform(
                post("/execute?async=true")
                    .accept(APPLICATION_OCTET_STREAM)
                    .contentType(TEXT_PLAIN)
                    .content(expression))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertEquals(
        "Error parsing expression: Missing end bracket",
        mvcResult.getResolvedException().getMessage());
  }

  @Test
  @WithMockUser
  void testAssignAsync() throws Exception {
    String expression = "meanDS(D$age)";
    String rewrittenExpression = "dsBase::meanDS(D$age)";
    when(expressionRewriter.rewriteAssign(expression)).thenReturn(rewrittenExpression);

    when(commands.assign("E", rewrittenExpression)).thenReturn(new CompletableFuture<>());

    MvcResult result =
        mockMvc
            .perform(
                post("/symbols/E?async=true")
                    .contentType(TEXT_PLAIN)
                    .content("meanDS(D$age)")
                    .accept(APPLICATION_OCTET_STREAM))
            .andReturn();
    mockMvc
        .perform(asyncDispatch(result))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost/lastcommand"))
        .andExpect(content().string(""));
  }

  @Test
  @WithMockUser
  void testLoadTableDoesNotExist() throws Exception {
    when(commands.evaluate("base::local(base::ls(.DSTableEnv))")).thenReturn(completedFuture(rexp));
    when(rexp.asStrings()).thenReturn(new String[] {});

    mockMvc.perform(post("/symbols/D?table=datashield.PATIENT")).andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser
  void testLoadTable() throws Exception {
    when(commands.evaluate("base::local(base::ls(.DSTableEnv))")).thenReturn(completedFuture(rexp));
    when(rexp.asStrings()).thenReturn(new String[] {"datashield.PATIENT"});

    when(commands.assign("D", "base::local(datashield.PATIENT, envir = .DSTableEnv)"))
        .thenReturn(completedFuture(null));

    mockMvc.perform(post("/symbols/D?table=datashield.PATIENT")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser
  void testLoadTableWithVariables() throws Exception {
    when(commands.evaluate("base::local(base::ls(.DSTableEnv))")).thenReturn(completedFuture(rexp));
    when(rexp.asStrings()).thenReturn(new String[] {"datashield.PATIENT"});

    when(commands.assign("D", "base::local(datashield.PATIENT[,c(\"age\")], envir = .DSTableEnv)"))
        .thenReturn(completedFuture(null));

    mockMvc
        .perform(post("/symbols/D?table=datashield.PATIENT&variables=age"))
        .andExpect(status().isOk());
  }

  @WithMockUser(roles = {"DIABETES_RESEARCHER"})
  @Test
  public void testLoadTibbles() throws Exception {
    when(commands.loadWorkspaces(asList("DIABETES/patient.RData")))
        .thenReturn(completedFuture(null));
    mockMvc.perform(post("/load-tables?workspace=DIABETES/patient")).andExpect(status().isOk());
  }
}