package com.netcourier.chatbot.service.ingestion;

import com.netcourier.chatbot.model.IngestUploadResponse;

public interface IngestionService {

    IngestUploadResponse ingestDocument(IngestDocumentCommand command);

    IngestUploadResponse ingestText(IngestTextCommand command);
}
