/**
 * Copyright (C) insightsoftware 2026. All rights reserved.
 */
package com.zoomdata.connector.example.provider.graphql;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class GraphQLHttpClient {

    private static final Logger log = LoggerFactory.getLogger(GraphQLHttpClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Gson gson = new Gson();

    private final OkHttpClient httpClient;

    public GraphQLHttpClient(int connectTimeoutSec, int readTimeoutSec) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
                .writeTimeout(readTimeoutSec, TimeUnit.SECONDS)
                .build();
    }

    public GraphQLHttpClient() {
        this(30, 60);
    }

    public JsonObject execute(String url, String query, Map<String, Object> variables,
                              Map<String, String> headers) throws IOException {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("query", query);
        if (variables != null && !variables.isEmpty()) {
            requestBody.add("variables", gson.toJsonTree(variables));
        }

        String bodyJson = gson.toJson(requestBody);
        log.debug("GraphQL request to {}: {}", url, bodyJson);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, bodyJson))
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json");

        if (headers != null) {
            headers.forEach(requestBuilder::addHeader);
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("GraphQL request failed with HTTP " + response.code()
                        + ": " + response.message());
            }

            String responseBody = response.body() != null ? response.body().string() : "{}";
            log.debug("GraphQL response: {}", responseBody);

            JsonObject result = gson.fromJson(responseBody, JsonObject.class);

            if (result.has("errors")) {
                JsonArray errors = result.getAsJsonArray("errors");
                if (errors.size() > 0) {
                    String errorMsg = errors.get(0).getAsJsonObject().get("message").getAsString();
                    throw new IOException("GraphQL error: " + errorMsg);
                }
            }

            return result;
        }
    }

    public JsonObject execute(String url, String query, Map<String, String> headers) throws IOException {
        return execute(url, query, null, headers);
    }

    public static Map<String, String> buildHeaders(String authToken, String authHeaderName,
                                                     String authHeaderPrefix, String customHeadersJson) {
        Map<String, String> headers = new HashMap<>();

        if (authToken != null && !authToken.isEmpty()) {
            String headerName = (authHeaderName != null && !authHeaderName.isEmpty())
                    ? authHeaderName : "Authorization";
            String prefix = (authHeaderPrefix != null && !authHeaderPrefix.isEmpty())
                    ? authHeaderPrefix + " " : "Bearer ";
            headers.put(headerName, prefix + authToken);
        }

        if (customHeadersJson != null && !customHeadersJson.trim().isEmpty()) {
            try {
                JsonObject customHeaders = gson.fromJson(customHeadersJson, JsonObject.class);
                for (Map.Entry<String, JsonElement> entry : customHeaders.entrySet()) {
                    headers.put(entry.getKey(), entry.getValue().getAsString());
                }
            } catch (Exception e) {
                log.warn("Failed to parse custom headers JSON: {}", e.getMessage());
            }
        }

        return headers;
    }
}
