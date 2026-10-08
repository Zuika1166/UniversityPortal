package com.university.portal.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

@Component
public class UniversityApiClient {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;

    public UniversityApiClient(ObjectMapper mapper, Environment environment) {
        this.mapper = mapper;
        this.baseUrl = environment.getProperty(
            "university.api-url", "https://api-university.vercel.app"
        ).replaceAll("/+$", "");
        this.apiKey = environment.getProperty("university.api-key", "");
        int timeoutSeconds = environment.getProperty("university.timeout-seconds", Integer.class, 8);
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.client = HttpClient.newBuilder()
            .connectTimeout(this.timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public JsonNode request(
        String method,
        String path,
        Map<String, String> query,
        JsonNode body,
        boolean courseEndpoint
    ) {
        if (courseEndpoint && apiKey.isBlank()) {
            throw new PortalException(
                HttpStatus.UNAUTHORIZED,
                "API_KEY_MISSING",
                "Не настроен ключ доступа к курсам"
            );
        }

        StringJoiner parameters = new StringJoiner("&");

        query.forEach((name, value) -> {
            if (value != null && !value.isBlank()) {
                parameters.add(encode(name) + "=" + encode(value));
            }
        });

        String url = baseUrl + path;
        String queryString = parameters.toString();

        if (!queryString.isEmpty()) {
            url += "?" + queryString;
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Accept", "application/json");

        if (courseEndpoint) {
            builder.header("X-API-Key", apiKey);
        }

        try {
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            }

            HttpResponse<String> response = client.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            int status = response.statusCode();

            if (status >= 300 && status < 400) {
                throw new PortalException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", "Сервис данных недоступен");
            }

            if (status >= 400) {
                throw mapError(status);
            }

            if (status == 204 || response.body().isBlank()) {
                return NullNode.getInstance();
            }

            return mapper.readTree(response.body());
        } catch (PortalException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new PortalException(HttpStatus.GATEWAY_TIMEOUT, "TIMEOUT", "Сервис данных не ответил вовремя");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PortalException(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", "Нет связи с сервисом данных");
        } catch (IOException exception) {
            throw new PortalException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", "Не удалось получить данные");
        } catch (IllegalArgumentException exception) {
            throw new PortalException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", "Некорректный адрес сервиса данных");
        }
    }

    private PortalException mapError(int status) {
        return switch (status) {
            case 400, 422 -> new PortalException(HttpStatus.BAD_REQUEST, "UPSTREAM_VALIDATION", "Сервис отклонил введённые данные");
            case 401, 403 -> new PortalException(HttpStatus.UNAUTHORIZED, "UPSTREAM_AUTH", "Нет доступа к University API");
            case 404 -> new PortalException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Запись не найдена");
            case 409 -> new PortalException(HttpStatus.CONFLICT, "CONFLICT", "Такая запись уже существует");
            case 429 -> new PortalException(HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMIT", "Сервис перегружен. Повторите запрос позже");
            default -> new PortalException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", "Ошибка сервиса данных");
        };
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
