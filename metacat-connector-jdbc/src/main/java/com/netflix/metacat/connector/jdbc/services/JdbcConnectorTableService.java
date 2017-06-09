/*
 *
 *  Copyright 2017 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.metacat.connector.jdbc.services;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.netflix.metacat.common.QualifiedName;
import com.netflix.metacat.common.dto.Pageable;
import com.netflix.metacat.common.dto.Sort;
import com.netflix.metacat.common.server.connectors.ConnectorContext;
import com.netflix.metacat.common.server.connectors.ConnectorTableService;
import com.netflix.metacat.common.server.connectors.model.FieldInfo;
import com.netflix.metacat.common.server.connectors.model.TableInfo;
import com.netflix.metacat.connector.jdbc.JdbcExceptionMapper;
import com.netflix.metacat.connector.jdbc.JdbcTypeConverter;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Generic JDBC implementation of the ConnectorTableService.
 *
 * @author tgianos
 * @since 1.0.0
 */
@Slf4j
@Getter
public class JdbcConnectorTableService implements ConnectorTableService {

    static final String[] TABLE_TYPES = {"TABLE", "VIEW"};
    private static final String ZERO = "0";

    private final DataSource dataSource;
    private final JdbcTypeConverter typeConverter;
    private final JdbcExceptionMapper exceptionMapper;

    /**
     * Constructor.
     *
     * @param dataSource      the datasource to use to connect to the database
     * @param typeConverter   The type converter to use from the SQL type to Metacat canonical type
     * @param exceptionMapper The exception mapper to use
     */
    @Inject
    public JdbcConnectorTableService(
        @Nonnull @NonNull final DataSource dataSource,
        @Nonnull @NonNull final JdbcTypeConverter typeConverter,
        @Nonnull @NonNull final JdbcExceptionMapper exceptionMapper
    ) {
        this.dataSource = dataSource;
        this.typeConverter = typeConverter;
        this.exceptionMapper = exceptionMapper;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delete(@Nonnull final ConnectorContext context, @Nonnull final QualifiedName name) {
        final String databaseName = name.getDatabaseName();
        final String tableName = name.getTableName();
        log.debug("Attempting to delete table {} from database {} for request {}", tableName, databaseName, context);
        try (final Connection connection = this.dataSource.getConnection()) {
            final boolean upperCase = connection.getMetaData().storesUpperCaseIdentifiers();
            if (upperCase) {
                connection.setSchema(databaseName.toUpperCase(Locale.ENGLISH));
            } else {
                connection.setSchema(databaseName);
            }
            final String finalTableName = upperCase ? tableName.toUpperCase(Locale.ENGLISH) : tableName;
            JdbcConnectorUtils.executeUpdate(connection, this.getDropTableSql(name, finalTableName));
            log.debug("Deleted table {} from database {} for request {}", tableName, databaseName, context);
        } catch (final SQLException se) {
            throw this.exceptionMapper.toConnectorException(se, name);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TableInfo get(@Nonnull final ConnectorContext context, @Nonnull final QualifiedName name) {
        log.debug("Beginning to get table metadata for qualified name {} for request {}", name, context);

        try (final Connection connection = this.dataSource.getConnection()) {
            final String database = name.getDatabaseName();
            connection.setSchema(database);
            final ImmutableList.Builder<FieldInfo> fields = ImmutableList.builder();
            try (final ResultSet columns = this.getColumns(connection, name)) {
                while (columns.next()) {
                    final String type = columns.getString("TYPE_NAME");
                    final String size = columns.getString("COLUMN_SIZE");
                    final String precision = columns.getString("DECIMAL_DIGITS");
                    final String sourceType = this.buildSourceType(type, size, precision);
                    final FieldInfo.FieldInfoBuilder fieldInfo = FieldInfo.builder()
                        .name(columns.getString("COLUMN_NAME"))
                        .sourceType(sourceType)
                        .type(this.typeConverter.toMetacatType(sourceType))
                        .comment(columns.getString("REMARKS"))
                        .isNullable(columns.getString("IS_NULLABLE").equals("YES"))
                        .defaultValue(columns.getString("COLUMN_DEF"));

                    if (size != null) {
                        fieldInfo.size(Integer.parseInt(size));
                    }

                    fields.add(fieldInfo.build());
                }
            }
            log.debug("Finished getting table metadata for qualified name {} for request {}", name, context);
            return TableInfo.builder().name(name).fields(fields.build()).build();
        } catch (final SQLException se) {
            throw this.exceptionMapper.toConnectorException(se, name);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<TableInfo> list(
        @Nonnull final ConnectorContext context,
        @Nonnull final QualifiedName name,
        @Nullable final QualifiedName prefix,
        @Nullable final Sort sort,
        @Nullable final Pageable pageable
    ) {
        log.debug("Beginning to list table metadata for {} for request {}", name, context);

        final ImmutableList.Builder<TableInfo> builder = ImmutableList.builder();
        for (final QualifiedName tableName : this.listNames(context, name, prefix, sort, pageable)) {
            builder.add(this.get(context, tableName));
        }
        log.debug("Finished listing table metadata for {} for request {}", name, context);
        return builder.build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<QualifiedName> listNames(
        @Nonnull final ConnectorContext context,
        @Nonnull final QualifiedName name,
        @Nullable final QualifiedName prefix,
        @Nullable final Sort sort,
        @Nullable final Pageable pageable
    ) {
        log.debug("Beginning to list tables names for qualified name {} for request {}", name, context);
        final String catalog = name.getCatalogName();
        final String database = name.getDatabaseName();

        try (final Connection connection = this.dataSource.getConnection()) {
            connection.setSchema(database);
            final List<QualifiedName> names = Lists.newArrayList();
            try (final ResultSet tables = this.getTables(connection, name, prefix)) {
                while (tables.next()) {
                    names.add(QualifiedName.ofTable(catalog, database, tables.getString("TABLE_NAME")));
                }
            }

            // Does user want sorting?
            if (sort != null) {
                final Comparator<QualifiedName> comparator = Comparator.comparing(QualifiedName::getTableName);
                JdbcConnectorUtils.sort(names, sort, comparator);
            }

            // Does user want pagination?
            final List<QualifiedName> results = JdbcConnectorUtils.paginate(names, pageable);

            log.debug("Finished listing tables names for qualified name {} for request {}", name, context);
            return results;
        } catch (final SQLException se) {
            throw this.exceptionMapper.toConnectorException(se, name);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void rename(
        @Nonnull final ConnectorContext context,
        @Nonnull final QualifiedName oldName,
        @Nonnull final QualifiedName newName
    ) {
        final String oldDatabaseName = oldName.getDatabaseName();
        final String newDatabaseName = newName.getDatabaseName();
        final String oldTableName = oldName.getTableName();
        final String newTableName = newName.getTableName();
        log.debug(
            "Attempting to re-name table {}/{} to {}/{} for request {}",
            oldDatabaseName,
            oldTableName,
            newDatabaseName,
            newTableName,
            context
        );

        if (!oldDatabaseName.equals(newDatabaseName)) {
            throw new IllegalArgumentException(
                "Database names must match and they are " + oldDatabaseName + " and " + newDatabaseName
            );
        }
        try (final Connection connection = this.dataSource.getConnection()) {
            final boolean upperCase = connection.getMetaData().storesUpperCaseIdentifiers();
            if (upperCase) {
                connection.setSchema(oldDatabaseName.toUpperCase(Locale.ENGLISH));
            } else {
                connection.setSchema(oldDatabaseName);
            }
            final String finalOldTableName = upperCase ? oldTableName.toUpperCase(Locale.ENGLISH) : oldTableName;
            final String finalNewTableName = upperCase ? newTableName.toUpperCase(Locale.ENGLISH) : newTableName;
            JdbcConnectorUtils.executeUpdate(
                connection,
                this.getRenameTableSql(oldName, finalOldTableName, finalNewTableName)
            );
            log.debug(
                "Renamed table {}/{} to {}/{} for request {}",
                oldDatabaseName,
                oldTableName,
                newDatabaseName,
                newTableName,
                context
            );
        } catch (final SQLException se) {
            throw this.exceptionMapper.toConnectorException(se, oldName);
        }
    }

    /**
     * Get the tables. See {@link java.sql.DatabaseMetaData#getTables(String, String, String, String[]) getTables} for
     * expected format of the ResultSet columns.
     *
     * @param connection The database connection to use
     * @param name       The qualified name of the database to get tables for
     * @param prefix     An optional database table name prefix to search for
     * @return The result set with columns as described in the getTables method from java.sql.DatabaseMetaData
     * @throws SQLException on query error
     */
    protected ResultSet getTables(
        @Nonnull @NonNull final Connection connection,
        @Nonnull @NonNull final QualifiedName name,
        @Nullable final QualifiedName prefix
    ) throws SQLException {
        final String database = name.getDatabaseName();
        final DatabaseMetaData metaData = connection.getMetaData();
        return prefix == null || StringUtils.isEmpty(prefix.getTableName())
            ? metaData.getTables(database, database, null, TABLE_TYPES)
            : metaData
            .getTables(
                database,
                database,
                prefix.getTableName() + JdbcConnectorUtils.MULTI_CHARACTER_SEARCH,
                TABLE_TYPES
            );
    }

    /**
     * Get the columns for a table. See
     * {@link java.sql.DatabaseMetaData#getColumns(String, String, String, String) getColumns} for format of the
     * ResultSet columns.
     *
     * @param connection The database connection to use
     * @param name       The qualified name of the table to get the column descriptions for
     * @return The result set of information
     * @throws SQLException on query error
     */
    protected ResultSet getColumns(
        @Nonnull @NonNull final Connection connection,
        @Nonnull @NonNull final QualifiedName name
    ) throws SQLException {
        final String database = name.getDatabaseName();
        final DatabaseMetaData metaData = connection.getMetaData();
        return metaData.getColumns(
            database,
            database,
            name.getTableName(),
            JdbcConnectorUtils.MULTI_CHARACTER_SEARCH
        );
    }

    /**
     * Rebuild a source type definition.
     *
     * @param type      The base type e.g. VARCHAR
     * @param size      The size if applicable to the {@code type}
     * @param precision The precision if applicable to the {@code type} e.g. DECIMAL's
     * @return The representation of source type e.g. INTEGER, VARCHAR(50) or DECIMAL(20, 10)
     * @throws SQLDataException When size or precision can't be parsed to integers if non null
     */
    protected String buildSourceType(
        @Nonnull @NonNull final String type,
        @Nullable final String size,
        @Nullable final String precision
    ) throws SQLDataException {
        Integer sizeInt;
        Integer precisionInt;
        try {
            sizeInt = size != null ? Integer.parseInt(size) : null;
            if (sizeInt != null && sizeInt < 1) {
                sizeInt = null;
            }
        } catch (final NumberFormatException nfe) {
            throw new SQLDataException("Size field could not be converted to integer", nfe);
        }
        try {
            precisionInt = precision != null ? Integer.parseInt(precision) : null;
            if (precisionInt != null && precisionInt < 1) {
                precisionInt = null;
            }
        } catch (final NumberFormatException nfe) {
            throw new SQLDataException("Precision field could not be converted to integer", nfe);
        }
        if (sizeInt != null && precisionInt != null) {
            return type + "(" + sizeInt + ", " + precisionInt + ")";
        } else if (sizeInt != null) {
            return type + "(" + sizeInt + ")";
        } else {
            return type;
        }
    }

    /**
     * Build the SQL for renaming a table out of the components provided. SQL will be executed.
     *
     * @param oldName           The fully qualified name for the current table
     * @param finalOldTableName The string for what the current table should be called in the sql
     * @param finalNewTableName The string for what the new name fo the table should be in the sql
     * @return The rename table sql to execute
     */
    protected String getRenameTableSql(
        final QualifiedName oldName,
        final String finalOldTableName,
        final String finalNewTableName
    ) {
        return "ALTER TABLE " + finalOldTableName + " RENAME TO " + finalNewTableName;
    }

    /**
     * Get the SQL for dropping the given table.
     *
     * @param name The fully qualified name of the table
     * @param finalTableName The final table name that should be dropped
     * @return The SQL to execute to drop the table
     */
    protected String getDropTableSql(final QualifiedName name, final String finalTableName) {
        return "DROP TABLE " + finalTableName;
    }
}
