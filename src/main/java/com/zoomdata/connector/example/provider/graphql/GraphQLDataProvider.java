/**
 * Copyright (C) insightsoftware 2026. All rights reserved.
 */
package com.zoomdata.connector.example.provider.graphql;

import com.zoomdata.connector.example.framework.annotation.Connector;
import com.zoomdata.connector.example.framework.api.AbstractDataProvider;
import com.zoomdata.connector.example.framework.api.IDescriptionProvider;
import com.zoomdata.connector.example.framework.async.IComputeTaskFactory;
import com.zoomdata.connector.example.framework.provider.serverdescription.GenericDescriptionProvider;
import com.zoomdata.gen.edc.filter.Filter;
import com.zoomdata.gen.edc.request.CollectionInfo;
import com.zoomdata.gen.edc.request.DataReadRequest;
import com.zoomdata.gen.edc.request.ExecuteCommandRequest;
import com.zoomdata.gen.edc.request.ExecuteCommandResponse;
import com.zoomdata.gen.edc.request.ExecuteException;
import com.zoomdata.gen.edc.request.MetaCollectionsResponse;
import com.zoomdata.gen.edc.request.MetaDescribeRequest;
import com.zoomdata.gen.edc.request.MetaDescribeResponse;
import com.zoomdata.gen.edc.request.MetaDescribeSchemaRequest;
import com.zoomdata.gen.edc.request.MetaDescribeSchemaResponse;
import com.zoomdata.gen.edc.request.MetaSchemasRequest;
import com.zoomdata.gen.edc.request.MetaSchemasResponse;
import com.zoomdata.gen.edc.request.MetaCollectionsRequest;
import com.zoomdata.gen.edc.request.RequestInfo;
import com.zoomdata.gen.edc.request.ResponseInfo;
import com.zoomdata.gen.edc.request.ResponseStatus;
import com.zoomdata.gen.edc.request.SampleRequest;
import com.zoomdata.gen.edc.request.SampleResponse;
import com.zoomdata.gen.edc.request.Schema;
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
    public MetaDescribeSchemaResponse describeSchemas(MetaDescribeSchemaRequest request) {
        try {
            String url = extractParam(request.getRequestInfo(), PARAM_URL);
            Map<String, String> headers = extractHeaders(request.getRequestInfo());

            GraphQLIntrospector introspector = new GraphQLIntrospector(httpClient, typesMapping);

            // Get all collections (or filter to requested ones)
            List<CollectionInfo> allCollections = introspector.getCollections(url, headers);

            // If the request specifies particular collections, filter to those
            List<CollectionInfo> requestedCollections = request.getCollections();
            if (requestedCollections != null && !requestedCollections.isEmpty()) {
                List<String> requestedNames = requestedCollections.stream()
                        .map(CollectionInfo::getCollection)
                        .collect(Collectors.toList());
                allCollections = allCollections.stream()
                        .filter(c -> requestedNames.contains(c.getCollection()))
                        .collect(Collectors.toList());
            }

            // Build CollectionInfo with field metadata for each collection
            List<CollectionInfo> collectionsWithFields = new ArrayList<>();
            for (CollectionInfo ci : allCollections) {
                try {
                    List<FieldMetadata> fields = introspector.describeCollection(
                            url, headers, ci.getCollection());
                    CollectionInfo enriched = new CollectionInfo();
                    enriched.setCollection(ci.getCollection());
                    enriched.setSchema("default");
                    enriched.setFields(fields);
                    collectionsWithFields.add(enriched);
                } catch (Exception e) {
                    log.warn("Failed to describe collection '{}': {}",
                            ci.getCollection(), e.getMessage());
                }
            }

            // Build Schema with name="default" and derive relations from GraphQL
            Schema schema = new Schema("default");
            schema.setCollections(collectionsWithFields);

            try {
                List<String> collectionNames = collectionsWithFields.stream()
                        .map(CollectionInfo::getCollection)
                        .collect(Collectors.toList());
                List<List<com.zoomdata.gen.edc.request.RelationMetadata>> relations =
                        introspector.describeRelationships(url, headers, collectionNames);
                schema.setRelations(relations);
                log.info("describeSchemas derived {} relations", relations.size());
            } catch (Exception e) {
                log.warn("Relation discovery failed (continuing without relations): {}", e.getMessage());
            }

            List<Schema> schemas = Collections.singletonList(schema);
            log.info("describeSchemas returning {} schemas with {} collections",
                    schemas.size(), collectionsWithFields.size());

            return new MetaDescribeSchemaResponse(schemas, ok());
        } catch (Exception e) {
            log.error("describeSchemas failed: {}", e.getMessage());
            return new MetaDescribeSchemaResponse(
                    Collections.emptyList(), serverError(e.getMessage()));
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

            // Extract requested fields from the QE's fieldMetadata (authoritative order)
            // or fall back to request-type-specific extraction
            List<String> requestedFields = extractFieldsFromRequest(request);

            // Extract filters from the structured request for pushdown
            List<Filter> filters = null;
            if (request.getStructured() != null) {
                if (request.getStructured().getRawDataRequest() != null
                        && request.getStructured().getRawDataRequest().getFilters() != null) {
                    filters = request.getStructured().getRawDataRequest().getFilters();
                } else if (request.getStructured().getAggDataRequest() != null
                        && request.getStructured().getAggDataRequest().getFilters() != null) {
                    filters = request.getStructured().getAggDataRequest().getFilters();
                }
            }

            return new GraphQLComputeTaskFactory(
                    httpClient, url, headers, collectionName,
                    requestedFields, typesMapping, fetchSize, filters);

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
     * Extract field names from the structured request.
     *
     * CRITICAL: The QE's fieldMetadata map is the authoritative source of which
     * fields to return and in what order. For aggregation queries, the QE sends
     * only the fields it needs (group-by + metric fields) in fieldMetadata.
     * Returning all fields or fields in a different order causes
     * NumberFormatException in the QE's RowConverter.
     */
    private List<String> extractFieldsFromRequest(DataReadRequest request) {
        if (request.getStructured() == null) return null;

        com.zoomdata.gen.edc.request.StructuredRequest sr = request.getStructured();

        // Log fieldMetadata for debugging but always return all fields.
        // With RAW_DATA_ONLY=false, the QE handles aggregation via its normal path.
        if (sr.getFieldMetadata() != null && !sr.getFieldMetadata().isEmpty()) {
            log.info("fieldMetadata present ({} fields: {}), returning all fields",
                    sr.getFieldMetadata().size(), sr.getFieldMetadata().keySet());
        }

        // FALLBACK: Raw data request with explicit field list
        if (sr.getRawDataRequest() != null
                && sr.getRawDataRequest().getFields() != null
                && !sr.getRawDataRequest().getFields().isEmpty()) {
            log.info("Using rawDataRequest fields: {}", sr.getRawDataRequest().getFields());
            return sr.getRawDataRequest().getFields();
        }

        // FALLBACK: Stats request
        if (sr.getStatsDataRequest() != null
                && sr.getStatsDataRequest().getStatFields() != null) {
            List<String> fields = new ArrayList<>();
            for (com.zoomdata.gen.edc.request.StatField sf : sr.getStatsDataRequest().getStatFields()) {
                fields.add(sf.getField());
            }
            log.info("Using statsDataRequest fields: {}", fields);
            return fields;
        }

        // No explicit fields: return null (triggers wildcard via introspection)
        log.info("No field list in request, using wildcard");
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
