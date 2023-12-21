/*
 * Copyright 2020-2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.fhir.analytics;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.fhir.analytics.JdbcConnectionPools.DataSourceConfig;
import com.google.fhir.analytics.model.DatabaseConfiguration;
import com.google.fhir.analytics.view.ViewApplicationException;
import com.google.fhir.analytics.view.ViewApplicator;
import com.google.fhir.analytics.view.ViewApplicator.FlatRow;
import com.google.fhir.analytics.view.ViewApplicator.RowElement;
import com.google.fhir.analytics.view.ViewApplicator.RowList;
import com.google.fhir.analytics.view.ViewDefinition;
import com.google.fhir.analytics.view.ViewDefinitionException;
import com.google.fhir.analytics.view.ViewManager;
import java.beans.PropertyVetoException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO switch to using JdbcIO; currently we can't do this because resource processing is done
// in the same DoFn as writing (for historical memory-related reasons).
// See: https://github.com/GoogleCloudPlatform/openmrs-fhir-analytics/issues/288

/** Writes FHIR resources to a relational database with a simple JSON based table schema. */
public class JdbcResourceWriter {

  private static final Logger log = LoggerFactory.getLogger(JdbcResourceWriter.class);

  private final ViewManager viewManager;

  private final IParser parser;

  private final DataSource jdbcDataSource;

  JdbcResourceWriter(
      DataSource jdbcDataSource, String viewDefinitionsDir, FhirContext fhirContext) {
    if (Strings.isNullOrEmpty(viewDefinitionsDir)) {
      viewManager = null;
    } else {
      try {
        viewManager = ViewManager.createForDir(viewDefinitionsDir);
      } catch (IOException | ViewDefinitionException e) {
        String errorMsg = String.format("Error while reading views from %s", viewDefinitionsDir);
        log.error(errorMsg, e);
        throw new IllegalArgumentException(errorMsg);
      }
    }
    this.parser = fhirContext.newJsonParser();
    this.jdbcDataSource = jdbcDataSource;
  }

  private static void createSingleTable(DataSource dataSource, String createStatement)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(createStatement)) {
      log.info("Table creation statement is " + statement);
      statement.execute();
    }
  }

  static void createTables(FhirEtlOptions options)
      throws PropertyVetoException, IOException, SQLException, ViewDefinitionException {
    // This should not be triggered in pipeline workers because concurrent CREATEs lead to failures:
    // https://stackoverflow.com/questions/54351783/duplicate-key-value-violates-unique-constraint
    Preconditions.checkArgument(!Strings.nullToEmpty(options.getSinkDbConfigPath()).isEmpty());
    DataSourceConfig dbConfig =
        JdbcConnectionPools.dbConfigToDataSourceConfig(
            DatabaseConfiguration.createConfigFromFile(options.getSinkDbConfigPath()));
    DataSource jdbcSource =
        JdbcConnectionPools.getInstance()
            .getPooledDataSource(
                dbConfig, options.getJdbcInitialPoolSize(), options.getJdbcMaxPoolSize());
    log.info(
        String.format(
            "Connecting to DB url %s with user %s.", dbConfig.jdbcUrl(), dbConfig.dbUser()));

    String viewDir = Strings.nullToEmpty(options.getViewDefinitionsDir());
    if (viewDir.isEmpty()) {
      log.info("Creating tables for each resource type.");
      for (String resourceType : options.getResourceList().split(",")) {
        String tableCreate =
            "CREATE TABLE IF NOT EXISTS %s (id VARCHAR(100) NOT NULL, "
                + "datab JSONB, PRIMARY KEY (id) );";
        String createStatement = String.format(tableCreate, resourceType);
        createSingleTable(jdbcSource, createStatement);
      }
    } else {
      ViewManager viewManager = ViewManager.createForDir(viewDir);
      for (String resourceType : options.getResourceList().split(",")) {
        ImmutableList<ViewDefinition> views = viewManager.getViewsForType(resourceType);
        if (views == null || views.isEmpty()) {
          log.warn("No views found for resource type {} in directory {}!", resourceType, viewDir);
        } else {
          for (ViewDefinition vDef : views) {
            if (Strings.isNullOrEmpty(vDef.getName())) {
              throw new ViewDefinitionException("Field `name` in ViewDefinition is not defined.");
            }
            // TODO if tables already exist, the better way is to check their schema to see if it is
            //  consistent with the view; and fail if it is not.
            StringBuilder builder = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
            builder.append(vDef.getName()).append(" (");
            builder.append(
                String.join(
                    ",",
                    vDef.getDbSchema().entrySet().stream()
                        .map(e -> String.format("%s %s", e.getKey(), e.getValue().typeString()))
                        .collect(Collectors.toList())));
            builder.append(");");
            createSingleTable(jdbcSource, builder.toString());
          }
        }
      }
    }
  }

  public void writeResource(Resource resource) throws SQLException, ViewApplicationException {
    if (viewManager == null) {
      try (Connection connection = jdbcDataSource.getConnection()) {
        String tableName = resource.getResourceType().name();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO "
                    + tableName
                    + " (id, datab) VALUES(?, ?::jsonb) "
                    + "ON CONFLICT (id) DO UPDATE SET id=?, datab=?::jsonb ;");
        statement.setString(1, resource.getIdElement().getIdPart());
        statement.setString(2, parser.encodeResourceToString(resource));
        statement.setString(3, resource.getIdElement().getIdPart());
        statement.setString(4, parser.encodeResourceToString(resource));
        statement.execute();
        statement.close();
      }
    } else {
      ImmutableList<ViewDefinition> views = viewManager.getViewsForType(resource.fhirType());
      if (views != null) {
        for (ViewDefinition vDef : views) {
          if (Strings.isNullOrEmpty(vDef.getName())) {
            throw new SQLException("Field `name` in ViewDefinition is not defined.");
          }
          ViewApplicator applicator = new ViewApplicator(vDef);
          RowList rowList = applicator.apply(resource);
          for (FlatRow row : rowList.getRows()) {
            StringBuilder builder = new StringBuilder("INSERT INTO ");
            builder.append(vDef.getName()).append(" (");
            builder.append(String.join(",", rowList.getColumnNames()));
            builder.append(") VALUES(");
            // TODO add resource ID requirement and replacing old rows for incremental update; also
            //  handle deleted resources: https://github.com/google/fhir-data-pipes/issues/588
            builder.append(
                String.join(
                    ",", row.getElements().stream().map(e -> "?").collect(Collectors.toList())));
            builder.append(");");
            try (Connection connection = jdbcDataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(builder.toString())) {
              int ind = 0;
              for (RowElement re : row.getElements()) {
                // TODO instead of string use the right type once column type derivation is done!
                statement.setString(++ind, re.getValue());
              }
              statement.execute();
            }
          }
        }
      }
    }
  }
}
