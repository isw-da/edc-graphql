/**
 * Copyright (C) insightsoftware 2026. All rights reserved.
 */
package com.zoomdata.connector.example.provider.graphql;

import com.zoomdata.connector.example.framework.annotation.Connector;
import com.zoomdata.connector.example.framework.api.AbstractDataProvider;
import com.zoomdata.connector.example.framework.api.IDescriptionProvider;
import com.zoomdata.connector.example.framework.async.IComputeTaskFactory;
import com.zoomdata.connector.example.framework.provider.serverdescription.GenericDescriptionProvider;
import com.zoomdata.gen.edc.request.CollectionInfo;
import com.zoomdata.gen.edc.request.DataReadRequest;
import com.zoomdata.gen.edc.request.ExecuteCommandRequest;
import com.zoomdata.gen.edc.request.ExecuteCommandResponse;
import com.zoomdata.gen.edc.request.ExecuteException;
import com.zoomdata.gen.edc.request.MetaCollectionsResponse;
import com.zoomdata.gen.edc.request.MetaDescribeRequest;
import com.zoomdata.gen.edc.request.MetaDescribeResponse;
import com.zoomdata.gen.edc.request.MetaSchemasRequest;
import com.zoomdata.gen.edc.request.MetaSchemasResponse;
import com.zoomdata.gen.edc.request.MetaCollectionsRequest;
import com.zoomdata.gen.edc.request.RequestInfo;
import com.zoomdata.gen.edc.request.ResponseInfo;
import com.zoomdata.gen.edc.request.ResponseStatus;
import com.zoomdata.gen.edc.request.SampleRequest;
import com.zoomdata.gen.edc.request.SampleResponse;
import com.zoomdata.gen.edc.request.ServerInfoRequest;
import com.zoomdata.gen.edc.request.ServerInfoResponse;
import com.zoomdata.gen.edc.request.ValidateCollectionRequest;
import com.zoomdata.gen.edc.request.ValidateCollectionResponse;
import com.zoomdata.gen.edc.request.ValidateSourceRequest;
import com.zoomdata.gen.edc.request.ValidateSourceResponse;
import com.zoomdata.gen.edc.types.Field;
import com.zoomdata.gen.edc.types.FieldMetadata;
import com.zoomdata.gen.edc.types.SampleField;
import com.zoomdata.gen.edc.types.SampleRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.zoomdata.connector.example.common.utils.metadatabuilders.ResponseInfoBuilder.ok;
import static com.zoomdata.connector.example.common.utils.metadatabuilders.ResponseInfoBuilder.serverError;
import static com.zoomdata.connector.example.framework.provider.serverdescription.connectionparameters.impl.PasswordConnectionParameter.PasswordConnectionParameterBuilder.passwordParameter;
import static com.zoomdata.connector.example.framework.provider.serverdescription.connectionparameters.impl.StringConnectionParameter.StringConnectionParameterBuilder.stringParameter;
import static com.zoomdata.connector.example.provider.graphql.GraphQLDataProvider.CONNECTION_TYPE;

@Connector(CONNECTION_TYPE)
public class GraphQLDataProvider extends AbstractDataProvider {

    private static final Logger log = LoggerFactory.getLogger(GraphQLDataProvider.class);

    protected static final String CONNECTION_TYPE = "GRAPHQL";

    private static final String PARAM_URL = "GRAPHQL_URL";
    private static final String PARAM_AUTH_TOKEN = "AUTH_TOKEN";
    private static final String PARAM_AUTH_HEADER_NAME = "AUTH_HEADER_NAME";
    private static final String PARAM_AUTH_HEADER_PREFIX = "AUTH_HEADER_PREFIX";
    private static final String PARAM_CUSTOM_HEADERS = "CUSTOM_HEADERS";

    private final GraphQLTypesMapping typesMapping = new GraphQLTypesMapping();
    private final GraphQLFeatures features = new GraphQLFeatures();
    private final GraphQLHttpClient httpClient = new GraphQLHttpClient();

    @Override
    public ValidateSourceResponse pingSource(ValidateSourceRequest request) {
        try {
            String url = extractParam(request.getRequestInfo(), PARAM_URL);
            Map<String, String> headers = extractHeaders(request.getRequestInfo());

            GraphQLIntrospector introspector = new GraphQLIntrospector(httpClient, typesMapping);
            boolean valid = introspector.validateConnection(url, headers);

            if (valid) {
                return new ValidateSourceResponse(ok());
            } else {
                return new ValidateSourceResponse(serverError("Failed to connect to GraphQL endpoint"));
            }
        } catch (Exception e) {
            log.error("pingSource failed: {}", e.getMessage());
            return new ValidateSourceResponse(serverError(e.getMessage()));
        }
    }

    @Override
    public ValidateCollectionResponse pingCollection(ValidateCollectionRequest request) {
        try {
            String url = extractParam(request.getRequestInfo(), PARAM_URL);
            Map<String, String> headers = extractHeaders(request.getRequestInfo());
            String collectionName = request.getCollectionInfo().getCollection();

            GraphQLIntrospector introspector = new GraphQLIntrospector(httpClient, typesMapping);
            List<CollectionInfo> collections = introspector.getCollections(url, headers);

            boolean found = collections.stream()
                    .anyMatch(c -> c.getCollection().equals(collectionName));

            if (found) {
                return new ValidateCollectionResponse(ok());
            } else {
                return new ValidateCollectionResponse(
                        serverError("Collection '" + collectionName + "' not found"));
            }
        } catch (Exception e) {
            log.error("pingCollection failed: {}", e.getMessage());
            return new ValidateCollectionResponse(serverError(e.getMessage()));
        }
    }

    @Override
    public ServerInfoResponse info(ServerInfoRequest request) {
        Map<String, String> allFeatures = features.getAllFeatures();
        List<String> keys = request.getKeys();

        Map<String, String> result;
        if (keys != null && keys.size() == 1 && "*".equals(keys.get(0))) {
            result = new HashMap<>(allFeatures);
        } else if (keys != null) {
            result = new HashMap<>();
            for (String key : keys) {
                result.put(key, allFeatures.getOrDefault(key, "UNKNOWN"));
            }
        } else {
            result = new HashMap<>(allFeatures);
        }

        return new ServerInfoResponse(result, ok());
    }

    @Override
    public ExecuteCommandResponse executeCommand(ExecuteCommandRequest request) {
        return new ExecuteCommandResponse(ok());
    }

    @Override
    public MetaSchemasResponse schemas(MetaSchemasRequest request) {
        return new MetaSchemasResponse(Collections.singletonList("default"), ok());
    }

    @Override
    public MetaCollectionsResponse collections(MetaCollectionsRequest request) {
        try {
            String url = extractParam(request.getRequestInfo(), PARAM_URL);
            Map<String, String> headers = extractHeaders(request.getRequestInfo());

            GraphQLIntrospector introspector = new GraphQLIntrospector(httpClient, typesMapping);
            List<CollectionInfo> collections = introspector.getCollections(url, headers);

            return new MetaCollectionsResponse(collections, ok());
        } catch (Exception e) {
            log.error("collections failed: {}", e.getMessage());
            return new MetaCollectionsResponse(Collections.emptyList(), serverError(e.getMessage()));
        }
    }

    @Override
    public MetaDescribeResponse describe(MetaDescribeRequest request) {
        try {
            String url = extractParam(request.getRequestInfo(), PARAM_URL);
            Map<String, String> headers = extractHeaders(request.getRequestInfo());
            String collectionName = request.getCollectionInfo().getCollection();

            GraphQLIntrospector introspector = new GraphQLIntrospector(httpClient, typesMapping);
            List<FieldMetadata> fields = introspector.describeCollection(url, headers, collectionName);

            return new MetaDescribeResponse(fields, ok());
        } catch (Exception e) {
            log.error("describe failed: {}", e.getMessage());
            return new MetaDescribeResponse(Collections.emptyList(), serverError(e.getMessage()));
        }
    }

    @Override
    public SampleResponse sample(SampleRequest request) {
        try {
            String url = extractParam(request.getRequestInfo(), PARAM_URL);
            Map<String, String> headers = extractHeaders(request.getRequestInfo());
            String collectionName = request.getCollectionInfo().getCollection();

            GraphQLIntrospector introspector = new GraphQLIntrospector(httpClient, typesMapping);
            List<FieldMetadata> fields = introspector.describeCollection(url, headers, collectionName);
            List<String> fieldNames = fields.stream()
                    .map(FieldMetadata::getName)
                    .collect(Collectors.toList());

            GraphQLComputeTask task = new GraphQLComputeTask(
                    httpClient, url, headers, collectionName, fieldNames, typesMapping, 10);
            GraphQLComputeTask.GraphQLCursor cursor =
                    (GraphQLComputeTask.GraphQLCursor) task.compute();

            List<SampleRecord> samples = new ArrayList<>();
            int count = 0;
            while (cursor.hasNext() && count < 10) {
                com.zoomdata.gen.edc.types.Record record = cursor.next();
                List<SampleField> sampleFields = new ArrayList<>();
                List<Field> recFields = record.getRecord();
                for (int i = 0; i < recFields.size(); i++) {
                    Field f = recFields.get(i);
                    String name = (i < fieldNames.size()) ? fieldNames.get(i) : "field_" + i;
                    SampleField sf = new SampleField(name);
                    if (i < fields.size()) {
                        sf.setType(fields.get(i).getType());
                    }
                    if (f.isIsNull()) {
                        sf.setIsNull(true);
                    } else {
                        sf.setValue(f.getValue());
                    }
                    sampleFields.add(sf);
                }
                samples.add(new SampleRecord(sampleFields));
                count++;
            }

            return new SampleResponse(samples, ok());
        } catch (Exception e) {
            log.error("sample failed: {}", e.getMessage());
            return new SampleResponse(Collections.emptyList(), serverError(e.getMessage()));
        }
    }

    @Override
    protected IComputeTaskFactory createComputeTaskFactory(DataReadRequest request, int fetchSize)
            throws ExecuteException {
        try {
            String url = extractParam(request.getRequestInfo(), PARAM_URL);
            Map<String, String> headers = extractHeaders(request.getRequestInfo());

            // Collection info is on the structured request in v25.4.0
            String collectionName;
            if (request.getStructured() != null && request.getStructured().getCollectionInfo() != null) {
                collectionName = request.getStructured().getCollectionInfo().getCollection();
            } else {
                throw new IllegalArgumentException("No collection info in request");
            }

            // Extract requested fields from whatever request type the QE sends
            List<String> requestedFields = extractFieldsFromRequest(request);

            return new GraphQLComputeTaskFactory(
                    httpClient, url, headers, collectionName,
                    requestedFields, typesMapping, fetchSize);

        } catch (Exception e) {
            throw new ExecuteException("Failed to create compute task: " + e.getMessage());
        }
    }

    @Override
    protected IDescriptionProvider createDescriptionProvider() {
        return new GenericDescriptionProvider(CONNECTION_TYPE)
                .addParameters(
                        stringParameter(PARAM_URL)
                                .isRequired(true)
                                .description("GraphQL endpoint URL (e.g. https://api.example.com/graphql)"))
                .addParameters(
                        passwordParameter(PARAM_AUTH_TOKEN)
                                .description("Authentication token (API key, Bearer token, etc.)"))
                .addParameters(
                        stringParameter(PARAM_AUTH_HEADER_NAME)
                                .description("Auth header name (default: Authorization)"))
                .addParameters(
                        stringParameter(PARAM_AUTH_HEADER_PREFIX)
                                .description("Auth token prefix (default: Bearer)"))
                .addParameters(
                        stringParameter(PARAM_CUSTOM_HEADERS)
                                .description("Additional HTTP headers as JSON (e.g. {\"X-Api-Key\": \"abc\"})"))
                .minVersion("1.0")
                .maxVersion("1.0");
    }

    /**
     * Extract field names from any structured request type (raw, agg, stats, distinct).
     * For RAW_DATA_ONLY connectors, the QE still sends aggDataRequest for GROUP BY queries.
     * We need to return ALL data fields so the QE can aggregate locally.
     * If we can identify specific fields, we return those; otherwise null triggers wildcard.
     */
    private List<String> extractFieldsFromRequest(DataReadRequest request) {
        if (request.getStructured() == null) return null;

        com.zoomdata.gen.edc.request.StructuredRequest sr = request.getStructured();

        // Raw data request: fields are directly listed
        if (sr.getRawDataRequest() != null
                && sr.getRawDataRequest().getFields() != null
                && !sr.getRawDataRequest().getFields().isEmpty()) {
            log.info("Extracting fields from rawDataRequest: {}", sr.getRawDataRequest().getFields());
            return sr.getRawDataRequest().getFields();
        }

        // Aggregation request: extract group fields + metric fields
        if (sr.getAggDataRequest() != null) {
            java.util.Set<String> fields = new java.util.LinkedHashSet<>();

            // Group-by fields
            if (sr.getAggDataRequest().getGroups() != null) {
                for (com.zoomdata.gen.edc.group.Group g : sr.getAggDataRequest().getGroups()) {
                    if (g.getAttributeGroup() != null) {
                        fields.add(g.getAttributeGroup().getField());
                    }
                    if (g.getTimeGroup() != null) {
                        fields.add(g.getTimeGroup().getField());
                    }
                }
            }

            // Metric fields (sum, avg, min, max, count)
            if (sr.getAggDataRequest().getMetrics() != null) {
                for (com.zoomdata.gen.edc.metric.Metric m : sr.getAggDataRequest().getMetrics()) {
                    if (m.getSum() != null) fields.add(m.getSum().getField());
                    if (m.getAvg() != null) fields.add(m.getAvg().getField());
                    if (m.getMin() != null) fields.add(m.getMin().getField());
                    if (m.getMax() != null) fields.add(m.getMax().getField());
                }
            }

            // RAW_DATA_ONLY: always return ALL fields for aggregation queries.
            // The QE aggregates locally and needs the full row.
            log.info("aggDataRequest detected, returning all fields (RAW_DATA_ONLY mode)");
            return null;
        }

        // Stats request: extract stat fields
        if (sr.getStatsDataRequest() != null
                && sr.getStatsDataRequest().getStatFields() != null) {
            List<String> fields = new ArrayList<>();
            for (com.zoomdata.gen.edc.request.StatField sf : sr.getStatsDataRequest().getStatFields()) {
                fields.add(sf.getField());
            }
            log.info("Extracting fields from statsDataRequest: {}", fields);
            return fields;
        }

        // Distinct values request
        if (sr.getDistinctValuesRequest() != null) {
            log.info("distinctValuesRequest detected, using wildcard");
            return null;
        }

        return null;
    }

    private String extractParam(RequestInfo requestInfo, String paramName) {
        if (requestInfo.getDataSourceInfo() == null
                || requestInfo.getDataSourceInfo().getParams() == null) {
            throw new IllegalArgumentException("Missing connection parameters");
        }
        String value = requestInfo.getDataSourceInfo().getParams().get(paramName);
        if (value == null && PARAM_URL.equals(paramName)) {
            throw new IllegalArgumentException("Missing required parameter: " + paramName);
        }
        return value;
    }

    private Map<String, String> extractHeaders(RequestInfo requestInfo) {
        String authToken = extractParam(requestInfo, PARAM_AUTH_TOKEN);
        String authHeaderName = extractParam(requestInfo, PARAM_AUTH_HEADER_NAME);
        String authHeaderPrefix = extractParam(requestInfo, PARAM_AUTH_HEADER_PREFIX);
        String customHeaders = extractParam(requestInfo, PARAM_CUSTOM_HEADERS);

        return GraphQLHttpClient.buildHeaders(authToken, authHeaderName, authHeaderPrefix, customHeaders);
    }
}
