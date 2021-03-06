/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import org.apache.ignite.cache.affinity.AffinityKey;
import org.apache.ignite.configuration.OdbcConfiguration;
import org.apache.ignite.internal.IgniteVersionUtils;
import org.apache.ignite.internal.jdbc.JdbcDriverPropertyInfo;
import org.apache.ignite.internal.jdbc.thin.JdbcThinConnection;

/**
 * JDBC driver thin implementation for In-Memory Data Grid.
 * <p>
 * Driver allows to get distributed data from Ignite cache using standard
 * SQL queries and standard JDBC API. It will automatically get only fields that
 * you actually need from objects stored in cache.
 * <h1 class="header">Limitations</h1>
 * Data in Ignite cache is usually distributed across several nodes,
 * so some queries may not work as expected since the query will be sent to each
 * individual node and results will then be collected and returned as JDBC result set.
 * Keep in mind following limitations (not applied if data is queried from one node only,
 * or data is fully co-located or fully replicated on multiple nodes):
 * <ul>
 *     <li>
 *         Joins will work correctly only if joined objects are stored in
 *         collocated mode. Refer to
 *         {@link AffinityKey}
 *         javadoc for more details.
 *     </li>
 *     <li>
 *         Note that if you are connected to local or replicated cache, all data will
 *         be queried only on one node, not depending on what caches participate in
 *         the query (some data from partitioned cache can be lost). And visa versa,
 *         if you are connected to partitioned cache, data from replicated caches
 *         will be duplicated.
 *     </li>
 * </ul>
 * <h1 class="header">SQL Notice</h1>
 * Driver allows to query data from several caches. Cache that driver is connected to is
 * treated as default schema in this case. Other caches can be referenced by their names.\
 *
 * <h1 class="header">Dependencies</h1>
 * JDBC driver is located in main Ignite JAR in {@code IGNITE_HOME/libs} folder.
 * <h1 class="header">Configuration</h1>
 *
 * <p>
 * JDBC connection URL has the following pattern:
 * {@code jdbc:ignite://<hostname>:<port>/}<br>
 * Note the following:
 * <ul>
 *     <li>Hostname is required.</li>
 *     <li>If port is not defined, {@code 10800} is used (default for Ignite thin client).</li>
 * </ul>
 * Other properties can be defined in {@link Properties} object passed to
 * {@link DriverManager#getConnection(String, Properties)} method:
 * <table class="doctable">
 *     <tr>
 *         <th>Name</th>
 *         <th>Description</th>
 *         <th>Default</th>
 *         <th>Optional</th>
 *     </tr>
 *     <tr>
 *         <td><b>ignite.jdbc.distributedJoins</b></td>
 *         <td>Flag to enable distributed joins.</td>
 *         <td>{@code false} (distributed joins are disabled)</td>
 *         <td>Yes</td>
 *     </tr>
 *     <tr>
 *         <td><b>ignite.jdbc.enforceJoinOrder</b></td>
 *         <td>Flag to enforce join order of tables in the query.</td>
 *         <td>{@code false} (enforcing join order is disabled)</td>
 *         <td>Yes</td>
 *     </tr>
 * </table>
 * <h1 class="header">Example</h1>
 * <pre name="code" class="java">
 * // Register JDBC driver.
 * Class.forName("org.apache.ignite.IgniteJdbcThinDriver");
 *
 * // Open JDBC connection.
 * Connection conn = DriverManager.getConnection("jdbc:ignite:thin//localhost:10800");
 *
 * // Query persons' names
 * ResultSet rs = conn.createStatement().executeQuery("select name from Person");
 *
 * while (rs.next()) {
 *     String name = rs.getString(1);
 *
 *     ...
 * }
 *
 * // Query persons with specific age
 * PreparedStatement stmt = conn.prepareStatement("select name, age from Person where age = ?");
 *
 * stmt.setInt(1, 30);
 *
 * ResultSet rs = stmt.executeQuery();
 *
 * while (rs.next()) {
 *     String name = rs.getString("name");
 *     int age = rs.getInt("age");
 *
 *     ...
 * }
 * </pre>
 */
@SuppressWarnings("JavadocReference")
public class IgniteJdbcThinDriver implements Driver {
    /** Prefix for property names. */
    private static final String PROP_PREFIX = "ignite.jdbc";

    /** Distributed joins parameter name. */
    private static final String PARAM_DISTRIBUTED_JOINS = "distributedJoins";

    /** Enforce join order parameter name. */
    private static final String ENFORCE_JOIN_ORDER = "enforceJoinOrder";

    /** Hostname property name. */
    public static final String PROP_HOST = PROP_PREFIX + "host";

    /** Port number property name. */
    public static final String PROP_PORT = PROP_PREFIX + "port";

    /** Distributed joins property name. */
    public static final String PROP_DISTRIBUTED_JOINS = PROP_PREFIX + PARAM_DISTRIBUTED_JOINS;

    /** Transactions allowed property name. */
    public static final String PROP_ENFORCE_JOIN_ORDER = PROP_PREFIX + ENFORCE_JOIN_ORDER;

    /** URL prefix. */
    public static final String URL_PREFIX = "jdbc:ignite:thin://";

    /** Default port. */
    public static final int DFLT_PORT = OdbcConfiguration.DFLT_TCP_PORT_FROM;

    /** Major version. */
    private static final int MAJOR_VER = IgniteVersionUtils.VER.major();

    /** Minor version. */
    private static final int MINOR_VER = IgniteVersionUtils.VER.minor();

    /*
     * Register driver.
     */
    static {
        try {
            DriverManager.registerDriver(new IgniteJdbcThinDriver());
        }
        catch (SQLException e) {
            throw new RuntimeException("Failed to register Ignite JDBC driver.", e);
        }
    }

    /** {@inheritDoc} */
    @Override public Connection connect(String url, Properties props) throws SQLException {
        if (!acceptsURL(url))
            return null;

        if (!parseUrl(url, props))
            throw new SQLException("URL is invalid: " + url);

        return new JdbcThinConnection(url, props);
    }

    /** {@inheritDoc} */
    @Override public boolean acceptsURL(String url) throws SQLException {
        return url.startsWith(URL_PREFIX);
    }

    /** {@inheritDoc} */
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        if (!parseUrl(url, info))
            throw new SQLException("URL is invalid: " + url);

        List<DriverPropertyInfo> props = Arrays.<DriverPropertyInfo>asList(
            new JdbcDriverPropertyInfo("Hostname", info.getProperty(PROP_HOST), ""),
            new JdbcDriverPropertyInfo("Port number", info.getProperty(PROP_PORT), ""),
            new JdbcDriverPropertyInfo("Distributed Joins", info.getProperty(PROP_DISTRIBUTED_JOINS), ""),
            new JdbcDriverPropertyInfo("Enforce Join Order", info.getProperty(PROP_ENFORCE_JOIN_ORDER), "")
        );

        return props.toArray(new DriverPropertyInfo[0]);
    }

    /** {@inheritDoc} */
    @Override public int getMajorVersion() {
        return MAJOR_VER;
    }

    /** {@inheritDoc} */
    @Override public int getMinorVersion() {
        return MINOR_VER;
    }

    /** {@inheritDoc} */
    @Override public boolean jdbcCompliant() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging is not used.");
    }

    /**
     * Validates and parses connection URL.
     *
     * @param props Properties.
     * @param url URL.
     * @return Whether URL is valid.
     */
    private boolean parseUrl(String url, Properties props) {
        if (url == null)
            return false;

        if (url.startsWith(URL_PREFIX) && url.length() > URL_PREFIX.length())
            return parseJdbcUrl(url, props);

        return false;
    }

    /**
     * @param url Url.
     * @param props Properties.
     * @return Whether URL is valid.
     */
    private boolean parseJdbcUrl(String url, Properties props) {
        url = url.substring(URL_PREFIX.length());

        String[] parts = url.split("\\?");

        if (parts.length > 2)
            return false;

        if (parts.length == 2)
            if (!parseParameters(parts[1], "&", props))
                return false;

        parts = parts[0].split("/");

        assert parts.length > 0;

        if (parts.length > 1)
            return false;

        url = parts[0];

        parts = url.split(":");

        assert parts.length > 0;

        if (parts.length > 2)
            return false;

        props.setProperty(PROP_HOST, parts[0]);

        try {
            props.setProperty(PROP_PORT, String.valueOf(parts.length == 2 ? Integer.valueOf(parts[1]) : DFLT_PORT));
        }
        catch (NumberFormatException ignored) {
            return false;
        }

        return true;
    }

    /**
     * Validates and parses URL parameters.
     *
     * @param val Parameters string.
     * @param delim Delimiter.
     * @param props Properties.
     * @return Whether URL parameters string is valid.
     */
    private boolean parseParameters(String val, String delim, Properties props) {
        String[] params = val.split(delim);

        for (String param : params) {
            String[] pair = param.split("=");

            if (pair.length != 2 || pair[0].isEmpty() || pair[1].isEmpty())
                return false;

            props.setProperty(PROP_PREFIX + pair[0], pair[1]);
        }

        return true;
    }
}
