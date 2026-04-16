/**
 * Copyright (C) insightsoftware 2026. All rights reserved.
 */
package com.zoomdata.connector.example.provider.graphql;

import com.zoomdata.connector.example.framework.ConnectorServerMain;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GraphQLConnectorMain extends ConnectorServerMain {

    public static void main(String[] args) {
        SpringApplication.run(GraphQLConnectorMain.class, args);
    }
}
