package com.university.portal.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class StudentValidator {
    private static final Set<String> STATUSES = Set.of("active", "inactive", "graduated", "suspended");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Set<String> ALLOWED_FIELDS = Set.of(
        "firstName", "lastName", "email", "phone", "dateOfBirth", "year", "gpa", "status"
    );

    public void validate(JsonNode input, boolean full) {
        if (input == null || !input.isObject() || input.size() == 0) {
            invalid();
        }

        input.fieldNames().forEachRemaining(field -> {
            if (!ALLOWED_FIELDS.contains(field)) {
                invalid();
            }
        });

        for (String name : new String[]{"firstName", "lastName"}) {
            if (full || input.has(name)) {
                if (!input.has(name) || !input.get(name).isTextual()
                    || input.get(name).asText().trim().isEmpty()
                    || input.get(name).asText().length() > 100) {
                    invalid();
                }
            }
        }

        if (full || input.has("email")) {
            if (!input.has("email") || !input.get("email").isTextual()
                || !EMAIL.matcher(input.get("email").asText()).matches()) {
                invalid();
            }
        }

        if (full || input.has("year")) {
            if (!input.has("year") || !input.get("year").isIntegralNumber()
                || input.get("year").asInt() < 1 || input.get("year").asInt() > 4) {
                invalid();
            }
        }

        if (full || input.has("gpa")) {
            if (!input.has("gpa") || !input.get("gpa").isNumber()
                || input.get("gpa").asDouble() < 0 || input.get("gpa").asDouble() > 4) {
                invalid();
            }
        }

        if (full || input.has("status")) {
            if (!input.has("status") || !input.get("status").isTextual()
                || !STATUSES.contains(input.get("status").asText())) {
                invalid();
            }
        }

        if (input.has("phone") && !input.get("phone").isNull()
            && (!input.get("phone").isTextual() || input.get("phone").asText().length() > 40)) {
            invalid();
        }

        if (input.has("dateOfBirth") && !input.get("dateOfBirth").isNull()) {
            if (!input.get("dateOfBirth").isTextual()
                || !input.get("dateOfBirth").asText().matches("\\d{4}-\\d{2}-\\d{2}")) {
                invalid();
            }
            try {
                LocalDate.parse(input.get("dateOfBirth").asText());
            } catch (DateTimeParseException exception) {
                invalid();
            }
        }
    }

    public void validateStatus(String status) {
        if (!STATUSES.contains(status)) {
            invalid();
        }
    }

    public void validateYear(int year) {
        if (year < 1 || year > 4) {
            invalid();
        }
    }

    private void invalid() {
        throw new PortalException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Проверьте введённые данные");
    }
}
