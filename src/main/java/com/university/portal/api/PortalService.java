package com.university.portal.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class PortalService {
    private static final Set<String> TYPES = Set.of("students", "teachers", "courses");
    private static final Set<String> STUDENT_SORT = Set.of(
        "firstName", "lastName", "studentNumber", "year", "gpa", "status", "email"
    );
    private static final Set<String> TEACHER_SORT = Set.of(
        "firstName", "lastName", "employeeNumber", "department", "position"
    );
    private static final Set<String> COURSE_SORT = Set.of(
        "name", "title", "code", "credits", "semester", "status"
    );

    private final UniversityApiClient client;
    private final ObjectMapper mapper;
    private final StudentValidator validator;
    private final String teamPrefix;
    private final AtomicLong sequence = new AtomicLong();

    public PortalService(
        UniversityApiClient client,
        ObjectMapper mapper,
        StudentValidator validator,
        Environment environment
    ) {
        this.client = client;
        this.mapper = mapper;
        this.validator = validator;
        String teamId = environment.getProperty("university.team-id", "01");

        if (!teamId.matches("[0-9A-Za-z-]{1,16}")) {
            throw new IllegalArgumentException("Invalid TEAM_ID");
        }

        this.teamPrefix = "TEAM-" + teamId + "-";
    }

    public ObjectNode list(
        String type,
        int page,
        int limit,
        String sortBy,
        String sortOrder,
        String search,
        Integer year,
        String status
    ) {
        checkType(type);
        checkPage(page, limit);
        Set<String> allowed = switch (type) {
            case "students" -> STUDENT_SORT;
            case "teachers" -> TEACHER_SORT;
            default -> COURSE_SORT;
        };

        if (sortBy != null && !sortBy.isBlank() && !allowed.contains(sortBy)) {
            throw invalid();
        }

        if (!"asc".equals(sortOrder) && !"desc".equals(sortOrder)) {
            throw invalid();
        }

        if (search != null && search.length() > 150) {
            throw invalid();
        }

        if (!type.equals("students") && (year != null || status != null)) {
            throw invalid();
        }

        if (year != null) {
            validator.validateYear(year);
        }

        if (status != null && !status.isBlank()) {
            validator.validateStatus(status);
        }

        int filters = (search != null && !search.isBlank() ? 1 : 0)
            + (year != null ? 1 : 0)
            + (status != null && !status.isBlank() ? 1 : 0);

        if (type.equals("students") && filters > 1) {
            throw new PortalException(
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Используйте поиск или один фильтр"
            );
        }

        String path = "/api/" + type;
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("page", Integer.toString(page));
        parameters.put("limit", Integer.toString(limit));

        if (sortBy != null && !sortBy.isBlank()) {
            parameters.put("sortBy", sortBy);
            parameters.put("sortOrder", sortOrder);
        }

        if (search != null && !search.isBlank() && !type.equals("teachers")) {
            path += "/search";
            parameters.put("q", search.trim());
        } else if (year != null) {
            path += "/byYear";
            parameters.put("year", year.toString());
        } else if (status != null && !status.isBlank()) {
            path += "/byStatus";
            parameters.put("status", status);
        }

        ObjectNode result = normalizeList(
            client.request("GET", path, parameters, null, type.equals("courses")),
            type, page, limit
        );

        if (type.equals("courses")) {
            attachTeachers(result.withArray("items"));
        }

        return result;
    }

    public ObjectNode get(String type, String id) {
        checkType(type);
        String safeId = safeId(id);
        JsonNode raw = client.request(
            "GET", "/api/" + type + "/" + safeId,
            Map.of(), null, type.equals("courses")
        );

        ObjectNode result = mapper.createObjectNode();
        JsonNode item = unwrapItem(raw, type);

        if (!item.isObject()) {
            throw new PortalException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", "Некорректный ответ сервиса");
        }

        ObjectNode copy = item.deepCopy();
        if (type.equals("courses")) {
            attachTeacher(copy, null);
        } else if (type.equals("students")) {
            copy.put("owned", isOwned(copy));
        }

        result.set("item", copy);
        return result;
    }

    public ObjectNode createStudent(JsonNode data) {
        validator.validate(data, true);
        ObjectNode body = data.deepCopy();
        long timestamp = sequence.updateAndGet(current ->
            Math.max(System.currentTimeMillis(), current + 1)
        );
        body.put("studentNumber", teamPrefix + timestamp);

        JsonNode result = client.request("POST", "/api/students", Map.of(), body, false);
        ObjectNode output = mapper.createObjectNode();
        output.set("item", unwrapItem(result, "students"));
        return output;
    }

    public ObjectNode updateStudent(String id, JsonNode data) {
        validator.validate(data, false);
        requireOwnedStudent(id);
        JsonNode result = client.request(
            "PATCH", "/api/students/" + safeId(id), Map.of(), data, false
        );
        ObjectNode output = mapper.createObjectNode();
        output.set("item", unwrapItem(result, "students"));
        return output;
    }

    public void deleteStudent(String id) {
        requireOwnedStudent(id);
        client.request("DELETE", "/api/students/" + safeId(id), Map.of(), null, false);
    }

    private void requireOwnedStudent(String id) {
        JsonNode student = get("students", id).path("item");
        if (!isOwned(student)) {
            throw new PortalException(
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "Можно изменять только записи своей команды"
            );
        }
    }

    private boolean isOwned(JsonNode student) {
        String number = student.path("studentNumber").asText("");
        String suffix = number.startsWith(teamPrefix)
            ? number.substring(teamPrefix.length()) : "";
        return suffix.matches("\\d+");
    }

    private ObjectNode normalizeList(JsonNode raw, String type, int page, int limit) {
        JsonNode container = raw;
        if (container.isObject() && container.has("data") && container.get("data").isObject()) {
            container = container.get("data");
        }

        JsonNode items = findArray(container, type);

        if (items == null && raw.has("data") && raw.get("data").isArray()) {
            items = raw.get("data");
        }

        if (items == null) {
            if (raw.isArray()) {
                items = raw;
            } else {
                throw new PortalException(
                    HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", "Некорректный формат списка"
                );
            }
        }

        JsonNode pagination = raw.path("pagination");

        if (!pagination.isObject()) {
            pagination = container.path("pagination");
        }

        int total = numeric(pagination, "total", numeric(container, "total", items.size()));
        int totalPages = numeric(
            pagination, "totalPages",
            (int) Math.ceil((double) total / limit)
        );

        ObjectNode output = mapper.createObjectNode();
        output.set("items", items.deepCopy());

        if (type.equals("students")) {
            for (JsonNode student : output.withArray("items")) {
                if (student.isObject()) {
                    ((ObjectNode) student).put("owned", isOwned(student));
                }
            }
        }

        ObjectNode paging = output.putObject("pagination");
        paging.put("page", numeric(pagination, "page", page));
        paging.put("limit", numeric(pagination, "limit", limit));
        paging.put("total", total);
        paging.put("totalPages", totalPages);
        return output;
    }

    private JsonNode findArray(JsonNode container, String type) {
        if (container.isArray()) {
            return container;
        }

        for (String field : new String[]{"items", type, "results", "data"}) {
            JsonNode value = container.path(field);

            if (value.isArray()) {
                return value;
            }
        }

        return null;
    }

    private JsonNode unwrapItem(JsonNode raw, String type) {
        JsonNode node = raw;
        if (node.has("data") && node.get("data").isObject()) {
            node = node.get("data");
        }

        String singular = type.substring(0, type.length() - 1);

        for (String key : new String[]{"item", singular}) {
            if (node.has(key) && node.get(key).isObject()) {
                return node.get(key);
            }
        }

        return node;
    }

    private int numeric(JsonNode source, String field, int fallback) {
        JsonNode value = source.path(field);
        return value.canConvertToInt() ? value.asInt() : fallback;
    }

    private void attachTeachers(ArrayNode courses) {
        Map<String, String> teachers = new HashMap<>();

        try {
            ObjectNode teacherList = normalizeList(
                client.request("GET", "/api/teachers", Map.of("page", "1", "limit", "100"), null, false),
                "teachers", 1, 100
            );

            for (JsonNode teacher : teacherList.withArray("items")) {
                String id = teacher.path("id").asText("");
                teachers.put(id, fullName(teacher));
            }
        } catch (PortalException exception) {
            for (JsonNode course : courses) {
                if (course.isObject()) {
                    attachTeacher((ObjectNode) course, null);
                }
            }
            return;
        }

        for (JsonNode course : courses) {
            if (course.isObject()) {
                attachTeacher((ObjectNode) course, teachers);
            }
        }
    }

    private void attachTeacher(ObjectNode course, Map<String, String> teachers) {
        String teacherId = course.path("teacherId").asText("");

        if (teacherId.isBlank() || teacherId.equals("null")) {
            course.put("teacherName", "Не назначен");
            return;
        }

        if (teachers != null && teachers.containsKey(teacherId)) {
            course.put("teacherName", teachers.get(teacherId));
            return;
        }

        try {
            JsonNode teacher = unwrapItem(
                client.request(
                    "GET", "/api/teachers/" + safeId(teacherId),
                    Map.of(), null, false
                ),
                "teachers"
            );
            course.put("teacherName", fullName(teacher));
        } catch (PortalException exception) {
            course.put("teacherName", "Преподаватель не найден");
        }
    }

    private String fullName(JsonNode teacher) {
        String name = (teacher.path("firstName").asText("") + " "
            + teacher.path("lastName").asText("")).trim();
        return name.isEmpty() ? "Не указан" : name;
    }

    private String safeId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw invalid();
        }
        return id;
    }

    private void checkType(String type) {
        if (!TYPES.contains(type)) {
            throw new PortalException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Раздел не найден");
        }
    }

    private void checkPage(int page, int limit) {
        if (page < 1 || limit < 1 || limit > 100) {
            throw invalid();
        }
    }

    private PortalException invalid() {
        return new PortalException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Некорректные параметры запроса");
    }
}
