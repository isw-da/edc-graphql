package com.zoomdata.connector.example.provider.graphql;

import com.zoomdata.gen.edc.ConnectorService;
import com.zoomdata.gen.edc.request.CollectionInfo;
import com.zoomdata.gen.edc.request.DataSourceInfo;
import com.zoomdata.gen.edc.request.MetaDescribeSchemaRequest;
import com.zoomdata.gen.edc.request.MetaDescribeSchemaResponse;
import com.zoomdata.gen.edc.request.RequestInfo;
import com.zoomdata.gen.edc.request.Schema;
import com.zoomdata.gen.edc.types.FieldMetadata;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.transport.THttpClient;

import java.util.HashMap;
import java.util.Map;

public class DescribeSchemasTest {

    private static final String CONNECTOR_URL = "http://localhost:7338/connector/";
    private static final String SUPABASE_URL = "https://cqkemdwjcuhiraiqxpzf.supabase.co/graphql/v1";
    private static final String SUPABASE_API_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImNxa2VtZHdqY3VoaXJhaXF4cHpmIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjUyNTMxMjIsImV4cCI6MjA4MDgyOTEyMn0.ORN6kq-0oBIm9RSvrR2mBPiFDfGAz9Li8DH-h1S7IQY";

    public static void main(String[] args) throws Exception {
        THttpClient transport = new THttpClient(CONNECTOR_URL);
        TCompactProtocol protocol = new TCompactProtocol(transport);
        ConnectorService.Client client = new ConnectorService.Client(protocol);

        Map<String, String> params = new HashMap<>();
        params.put("GRAPHQL_URL", SUPABASE_URL);
        params.put("CUSTOM_HEADERS", "{\"apikey\":\"" + SUPABASE_API_KEY + "\"}");

        DataSourceInfo dsInfo = new DataSourceInfo();
        dsInfo.setParams(params);
        RequestInfo info = new RequestInfo();
        info.setDataSourceInfo(dsInfo);

        MetaDescribeSchemaRequest req = new MetaDescribeSchemaRequest();
        req.setRequestInfo(info);

        System.out.println("Calling describeSchemas against " + CONNECTOR_URL + " -> " + SUPABASE_URL);
        MetaDescribeSchemaResponse resp = client.describeSchemas(req);

        System.out.println("ResponseInfo: " + resp.getResponseInfo());
        int schemaCount = resp.getSchemas() == null ? 0 : resp.getSchemas().size();
        System.out.println("Schemas returned: " + schemaCount);

        if (resp.getSchemas() != null) {
            for (Schema s : resp.getSchemas()) {
                System.out.println("  Schema: " + s.getName());
                if (s.getCollections() != null) {
                    System.out.println("  Collections (" + s.getCollections().size() + "):");
                    for (CollectionInfo c : s.getCollections()) {
                        int fc = c.getFields() == null ? 0 : c.getFields().size();
                        System.out.println("    - " + c.getCollection() + " (" + fc + " fields)");
                        if (c.getFields() != null && fc <= 8) {
                            for (FieldMetadata f : c.getFields()) {
                                System.out.println("        " + f.getName() + " : " + f.getType());
                            }
                        }
                    }
                }
            }
        }

        if (schemaCount == 0
                || resp.getSchemas().get(0).getCollections() == null
                || resp.getSchemas().get(0).getCollections().isEmpty()) {
            System.err.println("FAIL: describeSchemas returned no collections");
            System.exit(1);
        }
        System.out.println("PASS");
        transport.close();
    }
}
