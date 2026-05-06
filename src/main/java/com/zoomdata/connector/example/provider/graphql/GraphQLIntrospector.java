/**
 * Copyright (C) insightsoftware 2026. All rights reserved.
 */
package com.zoomdata.connector.example.provider.graphql;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zoomdata.connector.example.framework.common.Meta;
import com.zoomdata.gen.edc.request.CollectionInfo;
import com.zoomdata.gen.edc.request.RelationMetadata;
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
            FieldParams params = new FieldParams();
            params.setFieldName(fieldName);
            if (field.has("description") && !field.get("description").isJsonNull()) {
                String desc = field.get("description").getAsString();
                if (!desc.isEmpty()) {
                    params.setFieldLabel(desc);
                }
            }
            fm.setFieldParams(params);

            metadata.add(fm);
            log.debug("Field: {} -> type: {} (scalar: {})", fieldName, meta.getThriftType(), scalarType);
        }

        return metadata;
    }

    /**
     * Derive foreign-key relationships from Relay-style GraphQL object fields.
     *
     * Walks each collection's node type, looking for fields of kind OBJECT whose
     * target type is another known collection's node type (N-1 forward
     * references). Reverse (xxxConnection) fields are skipped; the forward
     * direction on the owning type carries the FK column.
     *
     * Supabase pg_graphql convention: a forward field `equipment.site` of
     * target type `site` implies `equipment.site_id -> site.id`.
     */
    public List<List<RelationMetadata>> describeRelationships(String url,
                                                                Map<String, String> headers,
                                                                List<String> collectionNames) throws IOException {
        JsonObject schema = fetchSchema(url, headers);

        // Build a map: node type name -> collection name (e.g. site -> siteCollection)
        java.util.Map<String, String> nodeTypeToCollection = new java.util.HashMap<>();
        JsonArray queryFields = schema.getAsJsonObject("queryType").getAsJsonArray("fields");
        for (JsonElement cfe : queryFields) {
            JsonObject cf = cfe.getAsJsonObject();
            String collName = cf.get("name").getAsString();
            if (!collectionNames.contains(collName)) continue;
            JsonObject ret = unwrapType(cf.getAsJsonObject("type"));
            String retName = getTypeName(ret);
            String nodeName = retName.endsWith("Connection")
                    ? retName.substring(0, retName.length() - "Connection".length())
                    : retName;
            nodeTypeToCollection.put(nodeName, collName);
        }

        List<List<RelationMetadata>> relations = new ArrayList<>();
        JsonArray types = schema.getAsJsonArray("types");
        for (JsonElement te : types) {
            JsonObject type = te.getAsJsonObject();
            String typeName = type.has("name") && !type.get("name").isJsonNull()
                    ? type.get("name").getAsString() : null;
            if (typeName == null || !nodeTypeToCollection.containsKey(typeName)) continue;
            if (!type.has("fields") || type.get("fields").isJsonNull()) continue;

            String sourceCollection = nodeTypeToCollection.get(typeName);
            for (JsonElement fe : type.getAsJsonArray("fields")) {
                JsonObject field = fe.getAsJsonObject();
                JsonObject ft = field.getAsJsonObject("type");
                JsonObject unwrapped = unwrapType(ft);
                String fKind = unwrapped != null && unwrapped.has("kind")
                        ? unwrapped.get("kind").getAsString() : "";
                if (!"OBJECT".equals(fKind)) continue;
                String targetName = getTypeName(unwrapped);
                // Skip reverse Connection references; forward side owns the FK
                if (targetName.endsWith("Connection")) continue;
                if (!nodeTypeToCollection.containsKey(targetName)) continue;

                String targetCollection = nodeTypeToCollection.get(targetName);
                String fieldName = field.get("name").getAsString();

                RelationMetadata rel = new RelationMetadata();
                rel.setPkSchema("default");
                rel.setPkTable(targetCollection);
                rel.setPkColumn("id");
                rel.setFkSchema("default");
                rel.setFkTable(sourceCollection);
                rel.setFkColumn(fieldName + "_id");
                relations.add(java.util.Collections.singletonList(rel));
                log.debug("Relation: {}.{} -> {}.id (via {}.{}_id)",
                        sourceCollection, fieldName + "_id", targetCollection, sourceCollection, fieldName);
            }
        }
        return relations;
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
