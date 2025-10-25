package com.netcourier.chatbot.service.retrieval;

import com.netcourier.chatbot.model.ChatRequest;
import com.netcourier.chatbot.model.RetrievedChunk;

import java.util.List;

public interface DenseRetriever {
    List<RetrievedChunk> search(ChatRequest request, String intent);
}
