package com.university.portal.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/portal")
public class PortalController {
    private final PortalService service;

    public PortalController(PortalService service) {
        this.service = service;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/{type}")
    public ObjectNode list(
        @PathVariable String type,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int limit,
        @RequestParam(required = false) String sortBy,
        @RequestParam(defaultValue = "asc") String sortOrder,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) Integer year,
        @RequestParam(required = false) String status
    ) {
        return service.list(type, page, limit, sortBy, sortOrder, q, year, status);
    }

    @GetMapping("/{type}/{id}")
    public ObjectNode detail(@PathVariable String type, @PathVariable String id) {
        return service.get(type, id);
    }

    @PostMapping("/students")
    public ResponseEntity<ObjectNode> create(@RequestBody JsonNode input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createStudent(input));
    }

    @PatchMapping("/students/{id}")
    public ObjectNode patch(@PathVariable String id, @RequestBody JsonNode input) {
        return service.updateStudent(id, input);
    }

    @DeleteMapping("/students/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.deleteStudent(id);
        return ResponseEntity.noContent().build();
    }
}
