/**
 * Copyright (C) insightsoftware 2026. All rights reserved.
 */
package com.zoomdata.connector.example.provider.graphql;

import com.google.common.collect.ImmutableMap;
import com.zoomdata.connector.example.framework.api.ITypesMapping;
import com.zoomdata.connector.example.framework.common.Meta;
import com.zoomdata.connector.example.framework.common.ThriftTypeFunction;
import com.zoomdata.gen.edc.types.FieldType;

import java.util.Map;

public class GraphQLTypesMapping implements ITypesMapping {

    private final Meta defaultMeta = metaString();

    private final Map<String, Meta> typesMapping =
            ImmutableMap.<String, Meta>builder()
                    // GraphQL built-in scalar types
                    .put("string", metaString())
                    .put("int", metaInt())
                    .put("float", metaDouble())
                    .put("boolean", metaString())
                    .put("id", metaString())
                    // Common custom scalar types
                    .put("datetime", metaTimestamp())
                    .put("date", metaString())
                    .put("timestamp", metaString())
                    .put("time", metaString())
                    .put("long", metaInt())
                    .put("bigint", metaInt())
                    .put("decimal", metaDouble())
                    .put("bigfloat", metaDouble())
                    .put("numeric", metaDouble())
                    .put("json", metaString())
                    .put("jsonobject", metaString())
                    .put("url", metaString())
                    .put("email", metaString())
                    .put("uuid", metaString())
                    .build();

    private static Meta metaInt() {
        return Meta.from(FieldType.INTEGER, ThriftTypeFunction.GET_INTEGER);
    }

    private static Meta metaDouble() {
        return Meta.from(FieldType.DOUBLE, ThriftTypeFunction.GET_DOUBLE);
    }

    private static Meta metaString() {
        return Meta.from(FieldType.STRING, ThriftTypeFunction.GET_STRING);
    }

    private static Meta metaDate() {
        return Meta.from(FieldType.DATE, ThriftTypeFunction.GET_DATE);
    }

    private static Meta metaTimestamp() {
        return Meta.from(FieldType.DATE, ThriftTypeFunction.GET_TIMESTAMP);
    }

    @Override
    public Meta metaForType(String type) {
        return typesMapping.getOrDefault(type.toLowerCase(), defaultMeta);
    }
}
