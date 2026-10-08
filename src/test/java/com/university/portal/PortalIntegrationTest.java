package com.university.portal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PortalIntegrationTest {
    private record Reply(int status, String body) {
    }

    private record Captured(String method, String path, String body, String key) {
    }

    private interface Responder {
        Reply respond(HttpExchange exchange) throws IOException;
    }

    private static final HttpServer SERVER;
    private static final AtomicReference<Responder> RESPONDER = new AtomicReference<>();
    private static final ConcurrentLinkedQueue<Captured> CAPTURED = new ConcurrentLinkedQueue<>();

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                CAPTURED.add(new Captured(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    body,
                    exchange.getRequestHeaders().getFirst("X-API-Key")
                ));
                Reply reply = RESPONDER.get().respond(exchange);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (reply.status() == 204) {
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    byte[] data = reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(reply.status(), data.length);
                    exchange.getResponseBody().write(data);
                }
                exchange.close();
            });
            SERVER.start();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("university.api-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("university.api-key", () -> "private-test-key");
        registry.add("university.team-id", () -> "01");
        registry.add("university.timeout-seconds", () -> "2");
    }

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void reset() {
        CAPTURED.clear();
        RESPONDER.set(exchange -> new Reply(200, "{\"data\":[],\"pagination\":{\"page\":1,\"limit\":10,\"total\":0,\"totalPages\":0}}"));
    }

    @AfterAll
    static void shutdown() {
        SERVER.stop(0);
    }

    @Test
    void studentsListIsNormalizedWithPagination() throws Exception {
        RESPONDER.set(exchange -> new Reply(200,
            "{\"data\":[{\"id\":1,\"firstName\":\"Anna\"}],\"pagination\":{\"page\":2,\"limit\":5,\"total\":12,\"totalPages\":3}}"));

        mvc.perform(get("/api/portal/students?page=2&limit=5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].firstName").value("Anna"))
            .andExpect(jsonPath("$.pagination.total").value(12))
            .andExpect(jsonPath("$.pagination.page").value(2));

        assertThat(CAPTURED.peek().path()).contains("/api/students?", "page=2", "limit=5");
    }

    @Test
    void singleStudentHasConsistentShape() throws Exception {
        RESPONDER.set(exchange -> new Reply(200, "{\"data\":{\"id\":7,\"lastName\":\"Ivanova\"}}"));

        mvc.perform(get("/api/portal/students/7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.item.id").value(7))
            .andExpect(jsonPath("$.item.lastName").value("Ivanova"));
    }

    @Test
    void externalNotFoundIsControlled() throws Exception {
        RESPONDER.set(exchange -> new Reply(404, "{\"message\":\"Internal upstream text\"}"));

        mvc.perform(get("/api/portal/students/999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").value("Запись не найдена"));
    }

    @Test
    void createAssignsTeamNumber() throws Exception {
        RESPONDER.set(exchange -> new Reply(201, "{\"data\":{\"id\":11,\"studentNumber\":\"TEAM-01-123\"}}"));

        mvc.perform(post("/api/portal/students")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validStudent()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.item.id").value(11));

        assertThat(CAPTURED.peek().body()).contains("TEAM-01-");
        assertThat(CAPTURED.peek().method()).isEqualTo("POST");
    }

    @Test
    void invalidStudentIsRejectedBeforeUpstream() throws Exception {
        mvc.perform(post("/api/portal/students")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validStudent().replace("\"year\":2", "\"year\":7")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        assertThat(CAPTURED).isEmpty();
    }

    @Test
    void externalStudentNumberCannotBeChosen() throws Exception {
        mvc.perform(post("/api/portal/students")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validStudent().replace("{", "{\"studentNumber\":\"TEAM-02-10\",")))
            .andExpect(status().isBadRequest());

        assertThat(CAPTURED).isEmpty();
    }

    @Test
    void foreignStudentCannotBeEdited() throws Exception {
        RESPONDER.set(exchange -> new Reply(200, "{\"data\":{\"id\":9,\"studentNumber\":\"TEAM-02-123\"}}"));

        mvc.perform(patch("/api/portal/students/9")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"firstName\":\"New\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(CAPTURED).hasSize(1);
        assertThat(CAPTURED.peek().method()).isEqualTo("GET");
    }

    @Test
    void ownStudentCanBeUpdated() throws Exception {
        RESPONDER.set(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                return new Reply(200, "{\"data\":{\"id\":9,\"studentNumber\":\"TEAM-01-123\"}}");
            }
            return new Reply(200, "{\"data\":{\"id\":9,\"firstName\":\"New\"}}");
        });

        mvc.perform(patch("/api/portal/students/9")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"firstName\":\"New\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.item.firstName").value("New"));

        assertThat(CAPTURED.stream().map(Captured::method)).containsExactly("GET", "PATCH");
    }

    @Test
    void ownStudentCanBeDeleted() throws Exception {
        RESPONDER.set(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                return new Reply(200, "{\"data\":{\"id\":9,\"studentNumber\":\"TEAM-01-123\"}}");
            }
            return new Reply(204, "");
        });

        mvc.perform(delete("/api/portal/students/9"))
            .andExpect(status().isNoContent());

        assertThat(CAPTURED.stream().map(Captured::method)).containsExactly("GET", "DELETE");
    }

    @Test
    void foreignStudentCannotBeDeleted() throws Exception {
        RESPONDER.set(exchange -> new Reply(200, "{\"data\":{\"id\":9,\"studentNumber\":\"OTHER-123\"}}"));

        mvc.perform(delete("/api/portal/students/9"))
            .andExpect(status().isForbidden());

        assertThat(CAPTURED).hasSize(1);
    }

    @Test
    void courseListIncludesTeacherAndKeyIsServerSide() throws Exception {
        RESPONDER.set(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/api/courses")) {
                return new Reply(200,
                    "{\"data\":[{\"id\":2,\"name\":\"Calculus\",\"teacherId\":4}],\"pagination\":{\"total\":1}}");
            }
            return new Reply(200,
                "{\"data\":[{\"id\":4,\"firstName\":\"Иван\",\"lastName\":\"Петров\"}],\"pagination\":{\"total\":1}}");
        });

        mvc.perform(get("/api/portal/courses"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].teacherName").value("Иван Петров"));

        assertThat(CAPTURED.stream().filter(c -> c.path().startsWith("/api/courses"))
            .findFirst().orElseThrow().key()).isEqualTo("private-test-key");
        assertThat(CAPTURED.stream().filter(c -> c.path().startsWith("/api/teachers"))
            .findFirst().orElseThrow().key()).isNull();
    }

    @Test
    void courseDetailLooksUpTeacher() throws Exception {
        RESPONDER.set(exchange -> {
            if (exchange.getRequestURI().getPath().contains("/courses/")) {
                return new Reply(200, "{\"data\":{\"id\":2,\"name\":\"Calculus\",\"teacherId\":4}}");
            }
            return new Reply(200, "{\"data\":{\"id\":4,\"firstName\":\"Иван\",\"lastName\":\"Петров\"}}");
        });

        mvc.perform(get("/api/portal/courses/2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.item.teacherName").value("Иван Петров"));
    }

    @Test
    void searchTextIsUrlEncoded() throws Exception {
        mvc.perform(get("/api/portal/students").param("q", "Anna & Bob"))
            .andExpect(status().isOk());
        assertThat(CAPTURED.peek().path()).contains("/api/students/search?", "q=Anna+%26+Bob");
    }

    @Test
    void yearFilterUsesDedicatedEndpoint() throws Exception {
        mvc.perform(get("/api/portal/students").param("year", "3"))
            .andExpect(status().isOk());
        assertThat(CAPTURED.peek().path()).contains("/api/students/byYear?", "year=3");
    }

    @Test
    void invalidFilterCombinationIsRejected() throws Exception {
        mvc.perform(get("/api/portal/students").param("year", "2").param("status", "active"))
            .andExpect(status().isBadRequest());
        assertThat(CAPTURED).isEmpty();
    }

    @Test
    void upstreamAuthenticationErrorIsControlled() throws Exception {
        RESPONDER.set(exchange -> new Reply(401, "{\"error\":\"secret upstream details\"}"));

        mvc.perform(get("/api/portal/courses"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UPSTREAM_AUTH"));
    }

    @Test
    void upstreamFailureBecomesBadGateway() throws Exception {
        RESPONDER.set(exchange -> new Reply(500, "{\"trace\":\"Internal text\"}"));

        mvc.perform(get("/api/portal/students"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error.code").value("UPSTREAM_ERROR"));
    }

    @Test
    void staticPortalLoads() throws Exception {
        mvc.perform(get("/"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/portal/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));
        assertThat(CAPTURED).isEmpty();
    }

    private String validStudent() {
        return "{\"firstName\":\"Anna\",\"lastName\":\"Ivanova\",\"email\":\"anna@example.org\","
            + "\"year\":2,\"gpa\":3.5,\"status\":\"active\"}";
    }
}
