/**
 * Copyright (C) insightsoftware 2026. All rights reserved.
 */
package com.zoomdata.connector.example.provider.graphql;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zoomdata.connector.example.framework.common.Meta;
import com.zoomdata.gen.edc.request.CollectionInfo;
import com.zoomdata.gen.edc.types.FieldMetadata;
import com.zoomdata.gen.edc.types.FieldParams;
import com.zoomdata.gen.edc.types.FieldType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GraphQLIntrospector {

    private static final Logger log = LoggerFactory.getLogger(GraphQLIntrospector.class);

    private static final String INTROSPECTION_QUERY =
            "{ __schema { queryType { name fields { name description type { " +
            "name kind ofType { name kind ofType { name kind ofType { name kind } } } } " +
            "args { name type { name kind ofType { name kind } } } } } " +
            "types { name kind description fields { name description " +
            "type { name kind ofType { name kind ofType { name kind ofType { name kind } } } } } } } }";

    private final GraphQLHttpClient httpClient;
    private final GraphQLTypesMapping typesMapping;

    public GraphQLIntrospector(GraphQLHttpClient httpClient, GraphQLTypesMapping typesMapping) {
        this.httpClient = httpClient;
        this.typesMapping = typesMapping;
    }

    public JsonObject fetchSchema(String url, Map<String, String> headers) throws IOException {
        JsonObject result = httpClient.execute(url, INTROSPECTION_QUERY, headers);
        return result.getAsJsonObject("data").getAsJsonObject("__schema");
    }

    public boolean validateConnection(String url, Map<String, String> headers) {
        try {
            fetchSchema(url, headers);
            return true;
        } catch (Exception e) {
            log.error("Connection validation failed: {}", e.getMessage());
            return false;
        }
    }

    public List<CollectionInfo> getCollections(String url, Map<String, String> headers) throws IOException {
        JsonObject schema = fetchSchema(url, headers);
        JsonObject queryType = schema.getAsJsonObject("queryType");
        JsonArray fields = queryType.getAsJsonArray("fields");

        List<CollectionInfo> collections = new ArrayList<>();
        for (JsonElement fieldElement : fields) {
            JsonObject field = fieldElement.getAsJsonObject();
            String fieldName = field.get("name").getAsString();

            // Skip introspection fields and Relay internal fields
            if (fieldName.startsWith("__")) continue;
            if ("nodeId".equals(fieldName)) continue;

            // Get the return type
            JsonObject returnType = unwrapType(field.getAsJsonObject("type"));
            String typeName = getTypeName(returnType);

            // Only include fields that return object types (likely data collections)
            if (returnType != null && isObjectOrListOfObjects(field.getAsJsonObject("type"))) {
                CollectionInfo collection = new CollectionInfo();
                collection.setCollection(fieldName);
                collection.setSchema("default");
                collections.add(collection);
                log.debug("Found collection: {} (type: {})", fieldName, typeName);
            }
        }

        return collections;
    }

    public List<FieldMetadata> describeCollection(String url, Map<String, String> headers,
                                                    String collectionName) throws IOException {
        JsonObject schema = fetchSchema(url, headers);

        // Find the query field for this collection
        JsonObject queryType = schema.getAsJsonObject("queryType");
        JsonArray queryFields = queryType.getAsJsonArray("fields");

        String targetTypeName = null;
        for (JsonElement fieldElement : queryFields) {
            JsonObject field = fieldElement.getAsJsonObject();
            if (field.get("name").getAsString().equals(collectionName)) {
                JsonObject returnType = unwrapType(field.getAsJsonObject("type"));
                targetTypeName = getTypeName(returnType);
                break;
            }
        }

        if (targetTypeName == null) {
            throw new IOException("Collection not found: " + collectionName);
        }

        // Relay pattern: if the type is a Connection, resolve to the Node type
        // e.g. frc_customersConnection -> frc_customers (the node type)
        if (targetTypeName.endsWith("Connection")) {
            String nodeTypeName = targetTypeName.substring(0, targetTypeName.length() - "Connection".length());
            log.info("Relay connection detected: {} -> resolving node type: {}", targetTypeName, nodeTypeName);
            targetTypeName = nodeTypeName;
        }

        // Find the type definition
        JsonArray types = schema.getAsJsonArray("types");
        JsonObject targetType = null;
        for (JsonElement typeElement : types) {
            JsonObject type = typeElement.getAsJsonObject();
            String name = type.has("name") && !type.get("name").isJsonNull()
                    ? type.get("name").getAsString() : null;
            if (targetTypeName.equals(name)) {
                targetType = type;
                break;
            }
        }

        if (targetType == null || !targetType.has("fields") || targetType.get("fields").isJsonNull()) {
            throw new IOException("Type definition not found for: " + targetTypeName);
        }

        List<FieldMetadata> metadata = new ArrayList<>();
        JsonArray typeFields = targetType.getAsJsonArray("fields");
        for (JsonElement fieldElement : typeFields) {
            JsonObject field = fieldElement.getAsJsonObject();
            String fieldName = field.get("name").getAsString();

            // Skip introspection fields and Relay internal fields
            if (fieldName.startsWith("__")) continue;
            if ("nodeId".equals(fieldName)) continue;

            JsonObject fieldType = field.getAsJsonObject("type");

            // Skip object and list-of-object fields (require subfield selection in GraphQL)
            if (isObjectOrListOfObjects(fieldType)) {
                log.debug("Skipping object field: {} (needs subfield selection)", fieldName);
                continue;
            }

            String scalarType = resolveScalarType(fieldType);
            Meta meta = typesMapping.metaForType(scalarType);

            FieldMetadata fm = new FieldMetadata();
            fm.setName(fieldName);
            fm.setType(meta.getThriftType());
            fm.setFieldParams(new FieldParams());

            metadata.add(fm);
            log.debug("Field: {} -> type: {} (scalar: {})", fieldName, meta.getThriftType(), scalarType);
        }

        return metadata;
    }

    /**
     * Unwrap NON_NULL and LIST wrappers to get the underlying named type.
     */
    private JsonObject unwrapType(JsonObject type) {
        if (type == null) return null;
        String kind = type.has("kind") ? type.get("kind").getAsString() : "";
        if ("NON_NULL".equals(kind) || "LIST".equals(kind)) {
            if (type.has("ofType") && !type.get("ofType").isJsonNull()) {
                return unwrapType(type.getAsJsonObject("ofType"));
            }
        }
        return type;
    }

    private String getTypeName(JsonObject type) {
        if (type == null) return "Unknown";
        return type.has("name") && !type.get("name").isJsonNull()
                ? type.get("name").getAsString() : "Unknown";
    }

    private String resolveScalarType(JsonObject type) {
        JsonObject unwrapped = unwrapType(type);
        if (unwrapped == null) return "String";
        String kind = unwrapped.has("kind") ? unwrapped.get("kind").getAsString() : "";
        if ("SCALAR".equals(kind) || "ENUM".equals(kind)) {
            return getTypeName(unwrapped);
        }
        // For object types, we'll stringify them
        return "String";
    }

    private boolean isObjectOrListOfObjects(JsonObject type) {
        if (type == null) return false;
        String kind = type.has("kind") ? type.get("kind").getAsString() : "";

        if ("OBJECT".equals(kind)) return true;

        if ("NON_NULL".equals(kind) || "LIST".equals(kind)) {
            if (type.has("ofType") && !type.get("ofType").isJsonNull()) {
                return isObjectOrListOfObjects(type.getAsJsonObject("ofType"));
            }
        }
        return false;
    }
}
