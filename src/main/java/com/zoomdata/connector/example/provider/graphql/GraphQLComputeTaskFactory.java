/**
 * Copyright (C) insightsoftware 2026. All rights reserved.
 */
package com.zoomdata.connector.example.provider.graphql;

import com.zoomdata.connector.example.framework.async.IComputeTask;
import com.zoomdata.connector.example.framework.async.IComputeTaskFactory;
import com.zoomdata.gen.edc.filter.Filter;

import java.util.List;
import java.util.Map;

public class GraphQLComputeTaskFactory implements IComputeTaskFactory {

    private final GraphQLHttpClient httpClient;
    private final String url;
    private final Map<String, String> headers;
    private final String collectionName;
    private final List<String> requestedFields;
    private final GraphQLTypesMapping typesMapping;
    private final int fetchSize;
    private final List<Filter> filters;
    private final String rawQuery;

    public GraphQLComputeTaskFactory(GraphQLHttpClient httpClient, String url,
                                      Map<String, String> headers, String collectionName,
                                      List<String> requestedFields,
                                      GraphQLTypesMapping typesMapping, int fetchSize) {
        this(httpClient, url, headers, collectionName, requestedFields, typesMapping, fetchSize, null);
    }

    public GraphQLComputeTaskFactory(GraphQLHttpClient httpClient, String url,
                                      Map<String, String> headers, String collectionName,
                                      List<String> requestedFields,
                                      GraphQLTypesMapping typesMapping, int fetchSize,
                                      List<Filter> filters) {
        this.httpClient = httpClient;
        this.url = url;
        this.headers = headers;
        this.collectionName = collectionName;
        this.requestedFields = requestedFields;
        this.typesMapping = typesMapping;
        this.fetchSize = fetchSize;
        this.filters = filters;

        // Build a human-readable representation of the query for logging
        StringBuilder sb = new StringBuilder("{ ").append(collectionName);
        if (filters != null && !filters.isEmpty()) {
            sb.append("(filter: ...)");
        }
        if (requestedFields != null && !requestedFields.isEmpty()) {
            sb.append(" { ").append(String.join(" ", requestedFields)).append(" }");
        }
        sb.append(" }");
        this.rawQuery = sb.toString();
    }

    @Override
    public IComputeTask create() {
        return new GraphQLComputeTask(httpClient, url, headers, collectionName,
                requestedFields, typesMapping, fetchSize, filters);
    }

    @Override
    public String getRawQuery() {
        return rawQuery;
    }

    @Override
    public int getFetchSize() {
        return fetchSize;
    }
}
