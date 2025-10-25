package com.netcourier.chatbot.controller;

import com.netcourier.chatbot.model.IngestTextRequest;
import com.netcourier.chatbot.model.IngestUploadResponse;
import com.netcourier.chatbot.service.ingestion.IngestDocumentCommand;
import com.netcourier.chatbot.service.ingestion.IngestTextCommand;
import com.netcourier.chatbot.service.ingestion.IngestionException;
import com.netcourier.chatbot.service.ingestion.IngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping
@Validated
public class IngestionController {

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(value = "/admin/ingest/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public IngestUploadResponse upload(@RequestParam("tenantId") String tenantId,
                                       @RequestParam(value = "title", required = false) String title,
                                       @RequestParam(value = "roles", required = false) List<String> roles,
                                       @RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IngestionException(HttpStatus.BAD_REQUEST, "File payload is required");
        }
        try {
            byte[] bytes = file.getBytes();
            return ingestionService.ingestDocument(new IngestDocumentCommand(tenantId, title, file.getOriginalFilename(), bytes, defaultedRoles(roles)));
        } catch (IOException e) {
            throw new IngestionException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read uploaded file", e);
        }
    }

    @PostMapping(value = "/api/ingest", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public IngestUploadResponse ingest(@Valid @RequestBody IngestTextRequest request) {
        List<String> roles = defaultedRoles(request.roles());
        return ingestionService.ingestText(new IngestTextCommand(request.tenantId(), request.title(), request.text(), roles));
    }

    private List<String> defaultedRoles(List<String> roles) {
        return roles == null ? List.of() : roles;
    }
}
