/**
 * Copyright (C) insightsoftware 2026. All rights reserved.
 */
package com.zoomdata.connector.example.provider.graphql;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zoomdata.connector.example.framework.async.Cursor;
import com.zoomdata.connector.example.framework.async.IComputeTask;
import com.zoomdata.connector.example.framework.common.Meta;
import com.zoomdata.gen.edc.filter.Filter;
import com.zoomdata.gen.edc.filter.FilterFunction;
import com.zoomdata.gen.edc.types.Field;
import com.zoomdata.gen.edc.types.FieldType;
import com.zoomdata.gen.edc.types.Record;
import com.zoomdata.gen.edc.types.ResponseMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

public class GraphQLComputeTask implements IComputeTask {

    private static final Logger log = LoggerFactory.getLogger(GraphQLComputeTask.class);

    private final GraphQLHttpClient httpClient;
    private final String url;
    private final Map<String, String> headers;
    private final String collectionName;
    private final List<String> requestedFields;
    private final GraphQLTypesMapping typesMapping;
    private final int fetchSize;
    private final List<Filter> filters;

    private volatile boolean cancelled = false;
    private volatile double progress = 0.0;
    private List<Record> records;
    private List<ResponseMetadata> metadata;
    // The authoritative field order, set during buildQuery() and used for all record/metadata building
    private List<String> resolvedFieldOrder;

    public GraphQLComputeTask(GraphQLHttpClient httpClient, String url, Map<String, String> headers,
                               String collectionName, List<String> requestedFields,
                               GraphQLTypesMapping typesMapping, int fetchSize) {
        this(httpClient, url, headers, collectionName, requestedFields, typesMapping, fetchSize, null);
    }

    public GraphQLComputeTask(GraphQLHttpClient httpClient, String url, Map<String, String> headers,
                               String collectionName, List<String> requestedFields,
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
    }

    @Override
    public Cursor compute() {
        try {
            String query = buildQuery();
            log.info("Executing GraphQL query for collection '{}': {}", collectionName, query);

            JsonObject result = httpClient.execute(url, query, headers);
            JsonObject data = result.getAsJsonObject("data");

            if (data == null || !data.has(collectionName)) {
                log.warn("No data returned for collection '{}'", collectionName);
                records = Collections.emptyList();
                metadata = Collections.emptyList();
                progress = 100.0;
                return new GraphQLCursor(records, metadata);
            }

            JsonElement collectionData = data.get(collectionName);
            records = new ArrayList<>();
            metadata = null;

            // Extract items from response, handling multiple patterns:
            // 1. Flat array: [{ ... }, { ... }]
            // 2. Relay connection: { edges: [{ node: { ... } }] }
            // 3. Single object: { ... }
            JsonArray items = extractItems(collectionData);

            if (items != null) {
                for (int i = 0; i < items.size() && !cancelled; i++) {
                    JsonElement item = items.get(i);
                    if (item.isJsonObject()) {
                        Record record = jsonToRecord(item.getAsJsonObject());
                        records.add(record);
                        if (metadata == null) {
                            metadata = buildMetadata(item.getAsJsonObject());
                        }
                    }
                    progress = ((double) (i + 1) / items.size()) * 100.0;
                }
            } else if (collectionData.isJsonObject()) {
                Record record = jsonToRecord(collectionData.getAsJsonObject());
                records.add(record);
                metadata = buildMetadata(collectionData.getAsJsonObject());
                progress = 100.0;
            }

            if (metadata == null) {
                metadata = Collections.emptyList();
            }

            progress = 100.0;
            log.info("GraphQL query returned {} records for '{}'", records.size(), collectionName);
            return new GraphQLCursor(records, metadata);

        } catch (Exception e) {
            log.error("Failed to execute GraphQL query: {}", e.getMessage(), e);
            throw new RuntimeException("GraphQL query failed: " + e.getMessage(), e);
        }
    }

    @Override
    public double progress() {
        return progress;
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }

    @Override
    public void close() {
        // Nothing to clean up for HTTP-based queries
    }

    /**
     * Extract items from a GraphQL response, handling flat arrays and Relay connections.
     */
    private JsonArray extractItems(JsonElement collectionData) {
        if (collectionData == null) return null;

        // Pattern 1: flat array
        if (collectionData.isJsonArray()) {
            return collectionData.getAsJsonArray();
        }

        // Pattern 2: Relay connection { edges: [{ node: { ... } }] }
        if (collectionData.isJsonObject()) {
            JsonObject obj = collectionData.getAsJsonObject();
            if (obj.has("edges") && obj.get("edges").isJsonArray()) {
                JsonArray edges = obj.getAsJsonArray("edges");
                JsonArray nodes = new JsonArray();
                for (JsonElement edge : edges) {
                    if (edge.isJsonObject() && edge.getAsJsonObject().has("node")) {
                        nodes.add(edge.getAsJsonObject().get("node"));
                    }
                }
                log.info("Extracted {} nodes from Relay connection pattern", nodes.size());
                return nodes;
            }
        }

        return null;
    }

    private String buildQuery() {
        StringBuilder sb = new StringBuilder("{ ");
        sb.append(collectionName);

        // Determine which fields to request
        List<String> fields = requestedFields;

        // If no fields specified or contains wildcard, introspect to get all scalar fields
        if (fields == null || fields.isEmpty()
                || (fields.size() == 1 && "*".equals(fields.get(0)))) {
            try {
                GraphQLIntrospector introspector = new GraphQLIntrospector(httpClient, typesMapping);
                List<com.zoomdata.gen.edc.types.FieldMetadata> meta =
                        introspector.describeCollection(url, headers, collectionName);
                fields = new ArrayList<>();
                for (com.zoomdata.gen.edc.types.FieldMetadata fm : meta) {
                    fields.add(fm.getName());
                }
                log.info("Resolved wildcard to {} fields for '{}'", fields.size(), collectionName);
            } catch (Exception e) {
                log.warn("Failed to introspect fields for wildcard, using __typename fallback: {}", e.getMessage());
                fields = Collections.singletonList("__typename");
            }
        }

        // Save the resolved field order for use in jsonToRecord and buildMetadata
        this.resolvedFieldOrder = new ArrayList<>(fields);

        // Build filter argument (Supabase GraphQL filter syntax)
        String filterArg = buildFilterArgument();
        if (filterArg != null && !filterArg.isEmpty()) {
            sb.append("(filter: {").append(filterArg).append("})");
            log.info("Applied pushdown filter for '{}': {}", collectionName, filterArg);
        }

        // Build field selection string
        StringBuilder fieldList = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) fieldList.append(" ");
            fieldList.append(fields.get(i));
        }
        String fieldsStr = fieldList.toString();

        // Detect if this is a Relay-style API (collection names ending in "Collection")
        if (collectionName.endsWith("Collection")) {
            // Relay pattern: { edges { node { field1 field2 } } }
            sb.append(" { edges { node { ").append(fieldsStr).append(" } } }");
        } else {
            // Flat array pattern: { field1 field2 }
            sb.append(" { ").append(fieldsStr).append(" }");
        }

        sb.append(" }");
        return sb.toString();
    }

    /**
     * Build a Supabase-style GraphQL filter argument from EDC filters.
     * Multiple filters are ANDed by placing them as sibling fields in the filter object.
     * Returns null if no supported filters are present.
     */
    private String buildFilterArgument() {
        if (filters == null || filters.isEmpty()) return null;

        // Flatten AND filters and collect individual filter clauses
        List<Filter> flatFilters = flattenFilters(filters);
        List<String> clauses = new ArrayList<>();

        for (Filter filter : flatFilters) {
            String clause = convertFilter(filter);
            if (clause != null) {
                clauses.add(clause);
            }
        }

        if (clauses.isEmpty()) return null;
        return String.join(", ", clauses);
    }

    /**
     * Flatten a list of filters, expanding AND composites into individual filters.
     */
    private List<Filter> flattenFilters(List<Filter> filterList) {
        List<Filter> flat = new ArrayList<>();
        for (Filter f : filterList) {
            if (f.getType() == FilterFunction.AND && f.getFilterAND() != null
                    && f.getFilterAND().getFilters() != null) {
                flat.addAll(flattenFilters(f.getFilterAND().getFilters()));
            } else {
                flat.add(f);
            }
        }
        return flat;
    }

    /**
     * Convert a single EDC filter to a Supabase GraphQL filter clause.
     * Returns null for unsupported filter types (QE handles those locally).
     */
    private String convertFilter(Filter filter) {
        if (filter == null || filter.getType() == null) return null;

        switch (filter.getType()) {
            case EQ:
                if (filter.getFilterEQ() != null) {
                    String path = filter.getFilterEQ().getPath();
                    String value = filter.getFilterEQ().getValue().getValue();
                    FieldType type = filter.getFilterEQ().getType();
                    return path + ": {eq: " + formatValue(value, type) + "}";
                }
                break;
            case GT:
                if (filter.getFilterGT() != null) {
                    String path = filter.getFilterGT().getPath();
                    String value = filter.getFilterGT().getValue().getValue();
                    FieldType type = filter.getFilterGT().getType();
                    return path + ": {gt: " + formatValue(value, type) + "}";
                }
                break;
            case GE:
                if (filter.getFilterGE() != null) {
                    String path = filter.getFilterGE().getPath();
                    String value = filter.getFilterGE().getValue().getValue();
                    FieldType type = filter.getFilterGE().getType();
                    return path + ": {gte: " + formatValue(value, type) + "}";
                }
                break;
            case LT:
                if (filter.getFilterLT() != null) {
                    String path = filter.getFilterLT().getPath();
                    String value = filter.getFilterLT().getValue().getValue();
                    FieldType type = filter.getFilterLT().getType();
                    return path + ": {lt: " + formatValue(value, type) + "}";
                }
                break;
            case LE:
                if (filter.getFilterLE() != null) {
                    String path = filter.getFilterLE().getPath();
                    String value = filter.getFilterLE().getValue().getValue();
                    FieldType type = filter.getFilterLE().getType();
                    return path + ": {lte: " + formatValue(value, type) + "}";
                }
                break;
            case IN:
                if (filter.getFilterIN() != null) {
                    String path = filter.getFilterIN().getPath();
                    FieldType type = filter.getFilterIN().getType();
                    List<String> values = filter.getFilterIN().getValues().stream()
                            .map(f -> formatValue(f.getValue(), type))
                            .collect(Collectors.toList());
                    return path + ": {in: [" + String.join(", ", values) + "]}";
                }
                break;
            case IS_NULL:
                if (filter.getFilterISNULL() != null) {
                    String path = filter.getFilterISNULL().getPath();
                    return path + ": {is: NULL}";
                }
                break;
            case NOT:
                if (filter.getFilterNOT() != null && filter.getFilterNOT().getFilter() != null) {
                    Filter inner = filter.getFilterNOT().getFilter();
                    if (inner.getType() == FilterFunction.IS_NULL && inner.getFilterISNULL() != null) {
                        return inner.getFilterISNULL().getPath() + ": {is: NOT_NULL}";
                    }
                }
                log.debug("Skipping NOT filter (only NOT(IS_NULL) is pushdown-supported): {}", filter);
                return null;
            case CONTAINS:
                if (filter.getFilterCONTAINS() != null) {
                    return wildcardClause(
                            filter.getFilterCONTAINS().getPath(),
                            filter.getFilterCONTAINS().getValue().getValue(),
                            filter.getFilterCONTAINS().getMode(),
                            "%", "%");
                }
                break;
            case STARTS_WITH:
                if (filter.getFilterSTARTS_WITH() != null) {
                    return wildcardClause(
                            filter.getFilterSTARTS_WITH().getPath(),
                            filter.getFilterSTARTS_WITH().getValue().getValue(),
                            filter.getFilterSTARTS_WITH().getMode(),
                            "", "%");
                }
                break;
            case ENDS_WITH:
                if (filter.getFilterENDS_WITH() != null) {
                    return wildcardClause(
                            filter.getFilterENDS_WITH().getPath(),
                            filter.getFilterENDS_WITH().getValue().getValue(),
                            filter.getFilterENDS_WITH().getMode(),
                            "%", "");
                }
                break;
            default:
                // TEXT_SEARCH, OR, EQI not supported for Supabase pushdown
                log.debug("Skipping unsupported filter type for pushdown: {}", filter.getType());
                return null;
        }
        return null;
    }

    /**
     * Build a wildcard clause for CONTAINS / STARTS_WITH / ENDS_WITH using
     * Supabase's like / ilike operators. CASE_INSENSITIVE and the
     * datasource-default mode use ilike; CASE_SENSITIVE uses like.
     */
    private String wildcardClause(String path, String value,
                                  com.zoomdata.gen.edc.filter.CaseSensitivityMode mode,
                                  String prefix, String suffix) {
        if (value == null) return null;
        String op = (mode == com.zoomdata.gen.edc.filter.CaseSensitivityMode.CASE_SENSITIVE)
                ? "like" : "ilike";
        String pattern = prefix + escapeWildcards(value) + suffix;
        return path + ": {" + op + ": \"" + escapeGraphQLString(pattern) + "\"}";
    }

    private String escapeWildcards(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Format a filter value for the GraphQL query.
     * Strings are quoted, numbers are not.
     */
    private String formatValue(String value, FieldType type) {
        if (type == null) {
            return "\"" + escapeGraphQLString(value) + "\"";
        }
        switch (type) {
            case INTEGER:
            case DOUBLE:
                return value;
            default:
                return "\"" + escapeGraphQLString(value) + "\"";
        }
    }

    private String escapeGraphQLString(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Record jsonToRecord(JsonObject json) {
        Record record = new Record();
        List<Field> fields = new ArrayList<>();

        // CRITICAL: Fields must be in the same order as ResponseMetadata.
        // Use resolvedFieldOrder (set during buildQuery) which matches describe() order.
        List<String> orderedFields = (resolvedFieldOrder != null && !resolvedFieldOrder.isEmpty())
                ? resolvedFieldOrder
                : new ArrayList<>(json.keySet());

        for (String fieldName : orderedFields) {
            JsonElement value = json.has(fieldName) ? json.get(fieldName) : null;

            Field field = new Field();

            if (value == null || value.isJsonNull()) {
                field.setIsNull(true);
                field.setValue("");
            } else if (value.isJsonPrimitive()) {
                if (value.getAsJsonPrimitive().isNumber()) {
                    Number num = value.getAsNumber();
                    if (value.getAsString().contains(".")) {
                        field.setValue(String.valueOf(num.doubleValue()));
                    } else {
                        field.setValue(String.valueOf(num.longValue()));
                    }
                } else if (value.getAsJsonPrimitive().isBoolean()) {
                    field.setValue(String.valueOf(value.getAsBoolean()));
                } else {
                    field.setValue(value.getAsString());
                }
            } else {
                // Arrays and objects: stringify
                field.setValue(value.toString());
            }

            fields.add(field);
        }

        record.setRecord(fields);
        return record;
    }

    private List<ResponseMetadata> buildMetadata(JsonObject sampleRecord) {
        List<ResponseMetadata> meta = new ArrayList<>();

        // Use same ordered field list as jsonToRecord (resolvedFieldOrder matches describe() order)
        List<String> orderedFields = (resolvedFieldOrder != null && !resolvedFieldOrder.isEmpty())
                ? resolvedFieldOrder
                : new ArrayList<>(sampleRecord.keySet());

        // Try to get authoritative types from introspection (same as describe())
        Map<String, FieldType> schemaTypes = new HashMap<>();
        try {
            GraphQLIntrospector introspector = new GraphQLIntrospector(httpClient, typesMapping);
            List<com.zoomdata.gen.edc.types.FieldMetadata> fieldMeta =
                    introspector.describeCollection(url, headers, collectionName);
            for (com.zoomdata.gen.edc.types.FieldMetadata fm : fieldMeta) {
                schemaTypes.put(fm.getName(), fm.getType());
            }
        } catch (Exception e) {
            log.debug("Could not introspect types for metadata, falling back to inference");
        }

        for (String fieldName : orderedFields) {
            ResponseMetadata rm = new ResponseMetadata();
            rm.setName(fieldName);
            // Use schema type if available, otherwise infer from value
            if (schemaTypes.containsKey(fieldName)) {
                rm.setType(schemaTypes.get(fieldName));
            } else {
                JsonElement value = sampleRecord.has(fieldName) ? sampleRecord.get(fieldName) : null;
                rm.setType(inferType(value));
            }
            meta.add(rm);
        }
        return meta;
    }

    private FieldType inferType(JsonElement value) {
        if (value == null || value.isJsonNull()) return FieldType.STRING;
        if (value.isJsonPrimitive()) {
            if (value.getAsJsonPrimitive().isNumber()) {
                String numStr = value.getAsString();
                if (numStr.contains(".")) return FieldType.DOUBLE;
                return FieldType.INTEGER;
            }
            if (value.getAsJsonPrimitive().isBoolean()) return FieldType.STRING;
        }
        return FieldType.STRING;
    }

    /**
     * Simple cursor implementation over an in-memory list of records.
     */
    static class GraphQLCursor implements Cursor {

        private final List<Record> records;
        private final List<ResponseMetadata> metadata;
        private final Iterator<Record> iterator;

        GraphQLCursor(List<Record> records, List<ResponseMetadata> metadata) {
            this.records = records;
            this.metadata = metadata;
            this.iterator = records.iterator();
        }

        @Override
        public List<ResponseMetadata> getMetadata() {
            return metadata;
        }

        @Override
        public boolean hasNextBatch() {
            return false; // All data fetched in one batch for now
        }

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public Record next() {
            if (!hasNext()) throw new NoSuchElementException();
            return iterator.next();
        }
    }
}
