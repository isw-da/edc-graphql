package com.zoomdata.connector.example.provider.graphql;

import com.zoomdata.gen.edc.ConnectorService;
import com.zoomdata.gen.edc.request.*;
import com.zoomdata.gen.edc.request.serverdescription.ServerDescription;
import com.zoomdata.gen.edc.types.Field;
import com.zoomdata.gen.edc.types.FieldMetadata;
import com.zoomdata.gen.edc.types.FieldType;
import com.zoomdata.gen.edc.types.Record;
import com.zoomdata.gen.edc.types.ResponseMetadata;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.transport.THttpClient;

import java.util.*;

public class GraphQLConnectorTest {

    private static final String CONNECTOR_URL = "http://localhost:7338/connector/";

    // Supabase FRC endpoint (Relay pattern)
    private static final String SUPABASE_URL = "https://cqkemdwjcuhiraiqxpzf.supabase.co/graphql/v1";
    private static final String SUPABASE_API_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImNxa2VtZHdqY3VoaXJhaXF4cHpmIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjUyNTMxMjIsImV4cCI6MjA4MDgyOTEyMn0.ORN6kq-0oBIm9RSvrR2mBPiFDfGAz9Li8DH-h1S7IQY";
    private static final String SUPABASE_COLLECTION = "frc_customersCollection";

    // Countries API (flat array pattern)
    private static final String COUNTRIES_URL = "https://countries.trevorblades.com/graphql";
    private static final String COUNTRIES_COLLECTION = "countries";

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        THttpClient transport = new THttpClient(CONNECTOR_URL);
        TCompactProtocol protocol = new TCompactProtocol(transport);
        ConnectorService.Client client = new ConnectorService.Client(protocol);

        System.out.println("============================================================");
        System.out.println("  GraphQL EDC Connector -- Comprehensive Test Suite");
        System.out.println("============================================================\n");

        // ---------------------------------------------------------------
        // SECTION A: Basic connector operations
        // ---------------------------------------------------------------
        section("A", "Basic Connector Operations");

        // A1. Ping
        test("A1", "Ping", () -> {
            String pong = client.ping();
            assertTrue(pong != null && pong.toLowerCase().contains("pong"), "ping response should be pong (got: " + pong + ")");
        });

        // A2. Describe Server
        test("A2", "DescribeServer", () -> {
            DescribeServerResponse resp = client.describeServer(new DescribeServerRequest());
            assertNotNull(resp.getServerDescription(), "server descriptions");
            boolean foundGraphQL = false;
            for (ServerDescription sd : resp.getServerDescription()) {
                System.out.println("       Storage type: " + sd.getStorageType());
                System.out.println("       Params count: " + sd.getConnectionParametersSize());
                if ("GRAPHQL".equalsIgnoreCase(sd.getStorageType())) foundGraphQL = true;
            }
            assertTrue(foundGraphQL, "should have GRAPHQL storage type");
        });

        // ---------------------------------------------------------------
        // SECTION B: Countries API (flat array pattern)
        // ---------------------------------------------------------------
        section("B", "Countries API (flat array)");

        RequestInfo countriesRI = buildRequestInfo(COUNTRIES_URL, null, null, null, null);

        // B1. Validate source
        test("B1", "ValidateSource (countries)", () -> {
            ValidateSourceRequest req = new ValidateSourceRequest();
            req.setRequestInfo(countriesRI);
            ValidateSourceResponse resp = client.validateSource(req);
            assertEqual(ResponseStatus.SUCCESS, resp.getResponseInfo().getStatus(), "validate status");
        });

        // B2. Schemas
        test("B2", "Schemas (countries)", () -> {
            MetaSchemasRequest req = new MetaSchemasRequest();
            req.setRequestInfo(countriesRI);
            MetaSchemasResponse resp = client.schemas(req);
            assertTrue(resp.getSchemas().contains("default"), "should contain 'default' schema");
        });

        // B3. Collections
        test("B3", "Collections (countries)", () -> {
            MetaCollectionsRequest req = new MetaCollectionsRequest();
            req.setRequestInfo(countriesRI);
            MetaCollectionsResponse resp = client.collections(req);
            assertNotNull(resp.getCollections(), "collections list");
            boolean found = false;
            System.out.println("       Available collections:");
            for (CollectionInfo ci : resp.getCollections()) {
                System.out.println("         - " + ci.getCollection());
                if ("countries".equals(ci.getCollection())) found = true;
            }
            assertTrue(found, "should find 'countries' collection");
        });

        // B4. Describe countries
        test("B4", "Describe 'countries'", () -> {
            MetaDescribeResponse resp = describeCollection(client, countriesRI, COUNTRIES_COLLECTION);
            assertNotNull(resp.getFields(), "fields");
            assertTrue(resp.getFields().size() > 0, "should have fields");
            System.out.println("       Fields from describe:");
            for (FieldMetadata fm : resp.getFields()) {
                System.out.println("         - " + fm.getName() + " (" + fm.getType() + ")");
            }
        });

        // B5. Prepare + Fetch (countries, specific fields)
        test("B5", "Prepare+Fetch 'countries' (name,code,capital)", () -> {
            List<String> fields = Arrays.asList("name", "code", "capital");
            DataResponse data = prepareAndFetch(client, countriesRI, COUNTRIES_COLLECTION, fields, 5);
            assertNotNull(data.getRecords(), "records");
            assertTrue(data.getRecords().size() > 0, "should have records");
            System.out.println("       Records: " + data.getRecords().size());
            printRecords(data, 3);
        });

        // ---------------------------------------------------------------
        // SECTION C: Supabase FRC API (Relay pattern) -- THE CRITICAL TESTS
        // ---------------------------------------------------------------
        section("C", "Supabase FRC API (Relay pattern) -- FIELD ORDER DIAGNOSIS");

        String supabaseCustomHeaders = "{\"apikey\": \"" + SUPABASE_API_KEY + "\"}";
        RequestInfo supabaseRI = buildRequestInfo(SUPABASE_URL, null, null, null, supabaseCustomHeaders);

        // C1. Validate source
        test("C1", "ValidateSource (Supabase)", () -> {
            ValidateSourceRequest req = new ValidateSourceRequest();
            req.setRequestInfo(supabaseRI);
            ValidateSourceResponse resp = client.validateSource(req);
            assertEqual(ResponseStatus.SUCCESS, resp.getResponseInfo().getStatus(), "validate status");
        });

        // C2. Collections
        test("C2", "Collections (Supabase)", () -> {
            MetaCollectionsRequest req = new MetaCollectionsRequest();
            req.setRequestInfo(supabaseRI);
            MetaCollectionsResponse resp = client.collections(req);
            assertNotNull(resp.getCollections(), "collections");
            boolean found = false;
            System.out.println("       Available collections:");
            for (CollectionInfo ci : resp.getCollections()) {
                System.out.println("         - " + ci.getCollection());
                if (SUPABASE_COLLECTION.equals(ci.getCollection())) found = true;
            }
            assertTrue(found, "should find '" + SUPABASE_COLLECTION + "'");
        });

        // C3. Describe frc_customersCollection -- capture field metadata order
        List<FieldMetadata> describeFields = new ArrayList<>();
        test("C3", "Describe '" + SUPABASE_COLLECTION + "' (field types)", () -> {
            MetaDescribeResponse resp = describeCollection(client, supabaseRI, SUPABASE_COLLECTION);
            assertNotNull(resp.getFields(), "fields");
            describeFields.addAll(resp.getFields());
            System.out.println("       DESCRIBE field order (from introspection schema):");
            for (int i = 0; i < describeFields.size(); i++) {
                FieldMetadata fm = describeFields.get(i);
                System.out.println("         [" + i + "] " + fm.getName() + " -> " + fm.getType());
            }

            // Validate expected types
            Map<String, FieldType> expected = new LinkedHashMap<>();
            expected.put("nodeId", FieldType.STRING);    // ID -> String
            expected.put("id", FieldType.INTEGER);       // Int -> Integer
            expected.put("name", FieldType.STRING);
            expected.put("industry", FieldType.STRING);
            expected.put("country", FieldType.STRING);
            expected.put("region", FieldType.STRING);
            expected.put("risk_rating", FieldType.STRING);
            expected.put("onboarded_date", FieldType.DATE);  // Date -> Date
            expected.put("annual_revenue", FieldType.DOUBLE);  // BigFloat -> Double
            expected.put("employee_count", FieldType.INTEGER); // Int -> Integer
            expected.put("is_active", FieldType.STRING);       // Boolean -> String

            System.out.println("\n       Expected type checks:");
            for (Map.Entry<String, FieldType> e : expected.entrySet()) {
                FieldMetadata found = null;
                for (FieldMetadata fm : describeFields) {
                    if (fm.getName().equals(e.getKey())) { found = fm; break; }
                }
                if (found != null) {
                    String match = found.getType() == e.getValue() ? "OK" : "MISMATCH";
                    System.out.println("         " + e.getKey() + ": expected=" + e.getValue()
                            + " actual=" + found.getType() + " [" + match + "]");
                } else {
                    System.out.println("         " + e.getKey() + ": NOT FOUND in describe");
                }
            }
        });

        // C4. Prepare + Fetch with ALL fields (wildcard) -- THE CRITICAL ORDER TEST
        test("C4", "Prepare+Fetch '" + SUPABASE_COLLECTION + "' (wildcard) -- FIELD ORDER AUDIT", () -> {
            // Use wildcard to let the connector choose field order
            List<String> fields = Arrays.asList("*");
            DataResponse data = prepareAndFetch(client, supabaseRI, SUPABASE_COLLECTION, fields, 5);
            assertNotNull(data.getRecords(), "records");
            assertTrue(data.getRecords().size() > 0, "should have records");

            // Get ResponseMetadata from the response
            List<ResponseMetadata> respMeta = data.getMetadata();
            Record firstRecord = data.getRecords().get(0);
            List<Field> recFields = firstRecord.getRecord();

            System.out.println("       ResponseMetadata field count: " + (respMeta != null ? respMeta.size() : "NULL"));
            System.out.println("       Record field count:           " + recFields.size());
            System.out.println();

            // *** THE CRITICAL COMPARISON ***
            System.out.println("  ====================================================================");
            System.out.println("  FIELD ORDER COMPARISON: ResponseMetadata vs Record values");
            System.out.println("  ====================================================================");
            System.out.println(String.format("  %-4s %-22s %-10s %-30s", "Pos", "Metadata Name", "Type", "Actual Value"));
            System.out.println("  " + "-".repeat(68));

            int maxLen = Math.max(respMeta != null ? respMeta.size() : 0, recFields.size());
            boolean mismatchFound = false;

            for (int i = 0; i < maxLen; i++) {
                String metaName = (respMeta != null && i < respMeta.size()) ? respMeta.get(i).getName() : "???";
                String metaType = (respMeta != null && i < respMeta.size()) ? respMeta.get(i).getType().toString() : "???";
                String actualVal = (i < recFields.size()) ? recFields.get(i).getValue() : "???";
                if (actualVal != null && actualVal.length() > 28) {
                    actualVal = actualVal.substring(0, 28) + "..";
                }

                // Detect mismatch: if metadata says INTEGER but value is not parseable as integer
                String flag = "";
                if (respMeta != null && i < respMeta.size()) {
                    FieldType ft = respMeta.get(i).getType();
                    if (ft == FieldType.INTEGER && actualVal != null && !actualVal.isEmpty()) {
                        try {
                            Long.parseLong(actualVal);
                        } catch (NumberFormatException e) {
                            flag = " *** MISMATCH: expected integer, got string!";
                            mismatchFound = true;
                        }
                    }
                    if (ft == FieldType.DOUBLE && actualVal != null && !actualVal.isEmpty()) {
                        try {
                            Double.parseDouble(actualVal);
                        } catch (NumberFormatException e) {
                            flag = " *** MISMATCH: expected double, got non-numeric!";
                            mismatchFound = true;
                        }
                    }
                }

                System.out.println(String.format("  [%2d] %-22s %-10s %-30s%s", i, metaName, metaType, actualVal, flag));
            }
            System.out.println("  " + "-".repeat(68));

            if (mismatchFound) {
                System.out.println("\n  *** FIELD ORDER BUG CONFIRMED ***");
                System.out.println("  The ResponseMetadata field names do NOT match the Record field positions.");
                System.out.println("  This causes NumberFormatException when the QE tries to parse values.");
            } else {
                System.out.println("\n  Field order looks correct -- no type/value mismatches detected.");
            }
        });

        // C5. Prepare + Fetch with EXPLICIT field list -- test whether explicit order is correct
        test("C5", "Prepare+Fetch '" + SUPABASE_COLLECTION + "' (explicit fields) -- ORDER CHECK", () -> {
            List<String> fields = Arrays.asList("id", "name", "country", "annual_revenue", "is_active");
            DataResponse data = prepareAndFetch(client, supabaseRI, SUPABASE_COLLECTION, fields, 3);
            assertNotNull(data.getRecords(), "records");

            List<ResponseMetadata> respMeta = data.getMetadata();
            Record firstRecord = data.getRecords().get(0);
            List<Field> recFields = firstRecord.getRecord();

            System.out.println("       Requested fields: " + fields);
            System.out.println("       ResponseMetadata count: " + (respMeta != null ? respMeta.size() : "NULL"));
            System.out.println("       Record field count:     " + recFields.size());
            System.out.println();
            System.out.println("       EXPLICIT FIELD ORDER COMPARISON:");
            System.out.println(String.format("       %-4s %-20s %-12s %-25s %-20s", "Pos", "Requested", "Meta Name", "Meta Type", "Actual Value"));

            int maxLen = Math.max(fields.size(), recFields.size());
            for (int i = 0; i < maxLen; i++) {
                String requested = i < fields.size() ? fields.get(i) : "n/a";
                String metaName = (respMeta != null && i < respMeta.size()) ? respMeta.get(i).getName() : "???";
                String metaType = (respMeta != null && i < respMeta.size()) ? respMeta.get(i).getType().toString() : "???";
                String actualVal = (i < recFields.size()) ? recFields.get(i).getValue() : "???";
                System.out.println(String.format("       [%2d] %-20s %-12s %-25s %-20s", i, requested, metaName, metaType, actualVal));
            }
        });

        // C6. Compare DESCRIBE order vs FETCH ResponseMetadata order
        test("C6", "DESCRIBE vs FETCH metadata field order comparison", () -> {
            // Fetch with wildcard to get the compute task's metadata order
            List<String> fields = Arrays.asList("*");
            DataResponse data = prepareAndFetch(client, supabaseRI, SUPABASE_COLLECTION, fields, 1);

            List<ResponseMetadata> fetchMeta = data.getMetadata();

            System.out.println("  ====================================================================");
            System.out.println("  DESCRIBE ORDER vs FETCH ResponseMetadata ORDER");
            System.out.println("  ====================================================================");
            System.out.println(String.format("  %-4s %-25s %-25s %-8s", "Pos", "Describe Field", "Fetch Meta Field", "Match?"));
            System.out.println("  " + "-".repeat(64));

            int maxLen = Math.max(describeFields.size(), fetchMeta != null ? fetchMeta.size() : 0);
            boolean orderMismatch = false;
            for (int i = 0; i < maxLen; i++) {
                String dName = i < describeFields.size() ? describeFields.get(i).getName() : "---";
                String fName = (fetchMeta != null && i < fetchMeta.size()) ? fetchMeta.get(i).getName() : "---";
                boolean match = dName.equals(fName);
                if (!match) orderMismatch = true;
                System.out.println(String.format("  [%2d] %-25s %-25s %-8s", i, dName, fName, match ? "YES" : "*** NO"));
            }

            if (orderMismatch) {
                System.out.println("\n  *** ORDERING MISMATCH between describe() and fetch ResponseMetadata ***");
                System.out.println("  This means the QE's field type assumptions from describe() don't match");
                System.out.println("  the actual positions in the fetch response, causing NumberFormatException.");
            } else {
                System.out.println("\n  Orders match between describe() and fetch metadata.");
            }
        });

        // ---------------------------------------------------------------
        // SECTION D: Error cases
        // ---------------------------------------------------------------
        section("D", "Error Cases");

        // D1. Invalid URL
        test("D1", "ValidateSource with invalid URL", () -> {
            RequestInfo badRI = buildRequestInfo("http://localhost:99999/nope", null, null, null, null);
            ValidateSourceRequest req = new ValidateSourceRequest();
            req.setRequestInfo(badRI);
            ValidateSourceResponse resp = client.validateSource(req);
            assertTrue(resp.getResponseInfo().getStatus() != ResponseStatus.SUCCESS,
                    "should fail for bad URL (got: " + resp.getResponseInfo().getStatus() + ")");
            System.out.println("       Status: " + resp.getResponseInfo().getStatus());
        });

        // D2. Invalid collection
        test("D2", "Describe invalid collection name", () -> {
            try {
                MetaDescribeResponse resp = describeCollection(client, countriesRI, "nonExistentCollection");
                // If we get here, check if the response has an error status
                if (resp.getFields() != null && resp.getFields().isEmpty()) {
                    System.out.println("       Got empty fields (error handled gracefully)");
                } else if (resp.getResponseInfo().getStatus() != ResponseStatus.SUCCESS) {
                    System.out.println("       Got error status: " + resp.getResponseInfo().getStatus());
                } else {
                    System.out.println("       Warning: no error for invalid collection");
                }
            } catch (Exception e) {
                System.out.println("       Exception (expected): " + e.getMessage());
            }
        });

        // D3. Missing GRAPHQL_URL parameter
        test("D3", "ValidateSource with missing URL param", () -> {
            RequestInfo emptyRI = new RequestInfo();
            DataSourceInfo dsi = new DataSourceInfo();
            dsi.setParams(new HashMap<>());
            emptyRI.setDataSourceInfo(dsi);
            ValidateSourceRequest req = new ValidateSourceRequest();
            req.setRequestInfo(emptyRI);
            try {
                ValidateSourceResponse resp = client.validateSource(req);
                assertTrue(resp.getResponseInfo().getStatus() != ResponseStatus.SUCCESS,
                        "should fail for missing URL");
                System.out.println("       Status: " + resp.getResponseInfo().getStatus());
            } catch (Exception e) {
                System.out.println("       Exception (expected): " + e.getMessage());
            }
        });

        // ---------------------------------------------------------------
        // SUMMARY
        // ---------------------------------------------------------------
        System.out.println("\n============================================================");
        System.out.println("  TEST SUMMARY");
        System.out.println("============================================================");
        System.out.println("  Passed: " + passed);
        System.out.println("  Failed: " + failed);
        if (!failures.isEmpty()) {
            System.out.println("  Failures:");
            for (String f : failures) {
                System.out.println("    - " + f);
            }
        }
        System.out.println("============================================================\n");

        transport.close();
        System.exit(failed > 0 ? 1 : 0);
    }

    // ===================================================================
    // Helper methods
    // ===================================================================

    private static RequestInfo buildRequestInfo(String url, String authToken,
                                                  String authHeaderName, String authHeaderPrefix,
                                                  String customHeaders) {
        Map<String, String> params = new HashMap<>();
        params.put("GRAPHQL_URL", url);
        if (authToken != null) params.put("AUTH_TOKEN", authToken);
        if (authHeaderName != null) params.put("AUTH_HEADER_NAME", authHeaderName);
        if (authHeaderPrefix != null) params.put("AUTH_HEADER_PREFIX", authHeaderPrefix);
        if (customHeaders != null) params.put("CUSTOM_HEADERS", customHeaders);

        DataSourceInfo dsInfo = new DataSourceInfo();
        dsInfo.setParams(params);

        RequestInfo ri = new RequestInfo();
        ri.setDataSourceInfo(dsInfo);
        return ri;
    }

    private static MetaDescribeResponse describeCollection(ConnectorService.Client client,
                                                             RequestInfo ri, String collection) throws Exception {
        MetaDescribeRequest req = new MetaDescribeRequest();
        req.setRequestInfo(ri);
        CollectionInfo ci = new CollectionInfo();
        ci.setCollection(collection);
        ci.setSchema("default");
        req.setCollectionInfo(ci);
        return client.describe(req);
    }

    private static DataResponse prepareAndFetch(ConnectorService.Client client,
                                                  RequestInfo ri, String collection,
                                                  List<String> fields, int limit) throws Exception {
        DataReadRequest readReq = new DataReadRequest();
        readReq.setRequestInfo(ri);
        readReq.setType(DataRequestType.STRUCTURED);

        StructuredRequest sr = new StructuredRequest();
        sr.setType(StructuredRequestType.RAW);

        CollectionInfo ci = new CollectionInfo();
        ci.setCollection(collection);
        ci.setSchema("default");
        sr.setCollectionInfo(ci);

        RawDataRequest rdr = new RawDataRequest();
        rdr.setFields(fields);
        rdr.setLimit(limit);
        sr.setRawDataRequest(rdr);
        readReq.setStructured(sr);

        PrepareResponse prep = client.prepare(readReq);
        String reqId = prep.getRequestIds().get(0).getId();
        System.out.println("       Query: " + prep.getRequestIds().get(0).getRawQuery());

        DataRequest fetchReq = new DataRequest();
        fetchReq.setRequestId(reqId);
        fetchReq.setRequestInfo(ri);
        return client.fetch(fetchReq);
    }

    private static void printRecords(DataResponse data, int maxRows) {
        if (data.getRecords() == null) return;
        int n = 0;
        for (Record rec : data.getRecords()) {
            if (n >= maxRows) break;
            StringBuilder sb = new StringBuilder("         ");
            for (Field f : rec.getRecord()) {
                String val = f.isIsNull() ? "NULL" : f.getValue();
                sb.append(val).append(" | ");
            }
            System.out.println(sb);
            n++;
        }
    }

    // ===================================================================
    // Test framework helpers
    // ===================================================================

    private static void section(String id, String name) {
        System.out.println("------------------------------------------------------------");
        System.out.println("  SECTION " + id + ": " + name);
        System.out.println("------------------------------------------------------------");
    }

    @FunctionalInterface
    interface TestAction {
        void run() throws Exception;
    }

    private static void test(String id, String name, TestAction action) {
        System.out.println("\n  [" + id + "] " + name);
        try {
            action.run();
            System.out.println("       -> PASSED");
            passed++;
        } catch (AssertionError e) {
            System.out.println("       -> FAILED: " + e.getMessage());
            failed++;
            failures.add(id + " " + name + ": " + e.getMessage());
        } catch (Exception e) {
            System.out.println("       -> ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            failed++;
            failures.add(id + " " + name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void assertEqual(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected '" + expected + "' but got '" + actual + "'");
        }
    }

    private static void assertNotNull(Object obj, String label) {
        if (obj == null) {
            throw new AssertionError(label + " should not be null");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
