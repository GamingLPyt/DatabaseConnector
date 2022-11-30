package de.databaseconnector;

import de.databaseconnector.annotations.DontSave;
import de.databaseconnector.annotations.builders.InsertBuilder;
import de.databaseconnector.annotations.builders.LoginBuilder;
import de.databaseconnector.annotations.builders.TableBuilder;
import de.databaseconnector.annotations.builders.general.WhereBuilder;
import de.databaseconnector.annotations.constructors.DatabaseConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Getter
@Setter
public class Database {

    private boolean debug;
    private boolean inTransaction;

    private String host;
    private int port;
    private String username;
    private String password;
    private String databaseName;
    private File sqlLiteFile;

    private Connection connection;

    /**
     * Create a database instance with MySQL
     *
     * @param host         The host which you would like to connect to
     * @param port         The port on which the database is hosted
     * @param username     The username you'd like to use
     * @param password     The password you'd like to use.
     * @param databaseName The name of the database
     */
    @SneakyThrows
    public Database(@NotNull final String host, final int port, @NotNull final String username, @NotNull final String password, @NotNull final String databaseName) {
        this(host, port, username, password, databaseName, false);
    }

    /**
     * Create a database instance using {@link LoginBuilder} with MySQL
     *
     * @param loginBuilder The {@link LoginBuilder} you'd like to use
     */
    @SneakyThrows
    public Database(@NotNull final LoginBuilder loginBuilder) {
        this(loginBuilder.getHost(), loginBuilder.getPort(), loginBuilder.getUsername(), loginBuilder.getPassword(), loginBuilder.getDatabase());
    }

    /**
     * Create a database instance with MySQL
     *
     * @param host         The host which you would like to connect to
     * @param port         The port on which the database is hosted
     * @param username     The username you'd like to use
     * @param password     The password you'd like to use.
     * @param databaseName The name of the database
     * @param debug        Whether you'd like to debug the database
     */
    @SneakyThrows
    public Database(@NotNull final String host, final int port, @NotNull final String username, @NotNull final String password, @NotNull final String databaseName, final boolean debug) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.databaseName = databaseName;
        this.debug = debug;

        if (debug)
            this.log("Debugging enabled");
    }

    /**
     * Creates a database instance with SQLite
     *
     * @param file The file which you would like to use
     */
    public Database(final File file) {
        this.sqlLiteFile = file;
    }

    /**
     * Initiate the connection to the database
     */
    @SneakyThrows
    public void connect() {
        if (this.sqlLiteFile != null) {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.sqlLiteFile.getAbsolutePath());
            return;
        }

        this.connection = DriverManager.getConnection("jdbc:mysql://" + this.host + ":" + this.port + "/" + this.databaseName + "?autoReconnect=true", this.username, this.password);

        if (this.debug)
            this.log("Connected to database");
    }

    /**
     * Disconnect from the database
     */
    @SneakyThrows
    public void disconnect() {
        this.connection.close();
        if (this.debug)
            this.log("Disconnected from database");
    }

    /**
     * Creates a table within the Database
     *
     * @param table The table you would like to create
     * @throws IllegalStateException If the arraylist is empty
     */
    public void createTable(@NotNull final TableBuilder table) throws SQLException, IllegalStateException {
        final StringBuilder statement = new StringBuilder("CREATE TABLE `" + table.getName() + "` (\n");

        if (table.getColumns().isEmpty())
            throw new IllegalStateException("There are no columns for table " + table.getName() + ".");

        final Column first = table.getColumns().get(0);
        final Column last = table.getColumns().get(table.getColumns().size() - 1);
        for (final Column column : table.getColumns()) {
            final String type = column.getType().toString();
            final String name = column.getName();

            if (first == column)
                statement.append("\t`").append(name).append("` ").append(type);
            else
                statement.append("\n\t`").append(name).append("` ").append(type);


            statement.append("(").append(column.getLength()).append(")");

            if (!column.isAllowNull())
                statement.append(" NOT NULL");


            if (!last.equals(column))
                statement.append(",");

        }

        if (table.getPrimaryKey() != null)
            statement.append(",\n\tPRIMARY KEY (`").append(table.getPrimaryKey()).append("`)");

        statement.append("\n);");

        if (this.debug)
            this.log("Creating table " + table.getName() + ": " + statement.toString());

        new Statement(statement.toString(), this.connection).execute();

        table.getColumns().forEach(column -> {
            if (column.getDefaultValue() != null) try {
                this.setColumnDefaultValue(table.getName(), column.getName(), column.getDefaultValue());
            } catch (final SQLException e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Start a transaction
     *
     * @throws SQLException          if there is an error with the connection
     * @throws IllegalStateException if the connection is already in a transaction
     */
    public void startTransaction() throws SQLException, IllegalStateException {
        if (this.isInTransaction())
            throw new IllegalStateException("Transaction already started");

        this.connection.setAutoCommit(false);
        new Statement("START TRANSACTION", this.connection).execute();

        if (this.debug)
            this.log("Started transaction");
    }

    /**
     * Rollback a transaction
     *
     * @throws SQLException          if there is an error with the connection
     * @throws IllegalStateException if the connection is not in a transaction
     */
    public void rollback() throws SQLException, IllegalStateException {
        if (!this.isInTransaction())
            throw new IllegalStateException("No transaction to rollback");
        new Statement("ROLLBACK", this.connection).execute();

        if (this.debug)
            this.log("Rolled back transaction");
    }

    /**
     * Commit a transaction
     *
     * @throws SQLException          if there is an error with the connection
     * @throws IllegalStateException if there is no transaction to commit
     */
    public void commit() throws SQLException, IllegalStateException {
        if (!this.isInTransaction())
            throw new IllegalStateException("No transaction to commit");

        new Statement("COMMIT", this.connection).execute();
        this.connection.setAutoCommit(true);

        if (this.debug)
            this.log("Committed transaction");
    }

    /**
     * Get something from the database
     * <p></p>
     * <p>For example, if you wanted to get the details about a player,</p>
     * <p>the key parameter would be "name" or whatever it is within your table</p>
     * <p>and the value parameter would be the player's name of whom you wish to get the details of.</p>
     * <p></p>
     * <p>The "column" parameter would be the specific detail you'd like to get. For example, </p>
     * <p>if my table contained a "age" column, and I wanted to get the player's age,</p>
     * <p>I'd set the column parameter to "age"</p>
     * <p>
     *
     * @param table  the table you'd like to pull from
     * @param key    The key you'd like to check
     * @param value  The value that you'd like to check
     * @param column The column you'd like to get
     * @return An object
     * @throws SQLException if there is an error retrieving the request value
     */
    @Nullable
    public Object get(@NotNull final String table, @NotNull final String key, @NotNull final String value, @NotNull final String column) throws SQLException {
        final String statement = "SELECT * FROM `" + table + "`";
        final ResultSet set = new Statement(statement, this.connection).executeWithResults();

        if (this.debug)
            this.log("Getting " + column + " from " + table + " where " + key + " = " + value);

        while (set.next()) if (set.getObject(key).equals(value))
            return set.getObject(column);
        if (this.debug)
            this.log("Getting value from table " + table + " failed");
        return null;
    }

    /**
     * Get something from the database
     * <p></p>
     * <p>For example, if you wanted to get the details about a player,</p>
     * <p>the key parameter would be "name" or whatever it is within your table</p>
     * <p>and the value parameter would be the player's name of whom you wish to get the details of.</p>
     * <p></p>
     * <p>The "column" parameter would be the specific detail you'd like to get. For example, </p>
     * <p>if my table contained a "age" column, and I wanted to get the player's age,</p>
     * <p>I'd set the column parameter to "age"</p>
     * <p>
     *
     * @param table  the table you'd like to pull from
     * @param key    The key you'd like to check
     * @param value  The value that you'd like to check
     * @param column The column you'd like to get
     * @return An object
     * @throws SQLException if there is an error retrieving the request value
     */
    @Nullable
    public Optional<List<Object>> getList(@NotNull final String table, @NotNull final String key, @NotNull final String value, @NotNull final String column) throws SQLException {
        final String statement = "SELECT * FROM `" + table + "`";
        final ResultSet set = new Statement(statement, this.connection).executeWithResults();

        if (this.debug)
            this.log("Getting " + column + " from " + table + " where " + key + " = " + value);

        final List<Object> objects = new ArrayList<>();
        while (set.next()) if (set.getObject(key).equals(value))
            objects.add(set.getObject(column));

        if (this.debug)
            this.log("Getting value from table " + table + " failed");

        if (objects.isEmpty())
            return Optional.empty();
        return Optional.of(objects);
    }

    /**
     * Get something from the database
     * <p></p>
     * <p>The "column" parameter would be the specific detail you'd like to get. For example, </p>
     * <p>if my table contained a "age" column, and I wanted to get the player's age,</p>
     * <p>I'd set the column parameter to "age"</p>
     * <p>
     *
     * @param table  the table you'd like to pull from
     * @param column The column you'd like to get
     * @return An object
     * @throws SQLException if there is an error retrieving the request value
     */
    @Nullable
    public Optional<List<Object>> getList(@NotNull final String table, @NotNull final String column) throws SQLException {
        final String statement = "SELECT * FROM `" + table + "`";
        final ResultSet set = new Statement(statement, this.connection).executeWithResults();

        if (this.debug)
            this.log("Getting " + column + " from " + table);

        final List<Object> objects = new ArrayList<>();
        while (set.next()) objects.add(set.getObject(column));

        if (this.debug)
            this.log("Getting value from table " + table + " failed");

        if (objects.isEmpty())
            return Optional.empty();
        return Optional.of(objects);
    }

    /**
     * Check if a table exists
     *
     * @param tableName The table you'd like to check
     * @return A boolean if the table exists or not
     * @throws SQLException If there is an error
     */
    public boolean tableExists(@NotNull final String tableName) throws SQLException {
        final DatabaseMetaData meta = this.connection.getMetaData();
        final ResultSet resultSet = meta.getTables(null, null, tableName, new String[]{"TABLE"});
        if (this.debug)
            this.log("Checking if table exists: " + tableName);
        return resultSet.next();
    }

    /**
     * Insert into a database
     *
     * @param table  The table you'd like to insert to
     * @param values A hashmap of keys, and values
     * @throws SQLException if there is an error
     */
    public void insert(@NotNull final String table, @NotNull final HashMap<String, String> values) throws SQLException {
        final StringBuilder statement = new StringBuilder("insert into `" + table + "` (\n\t");

        final ArrayList<String> keysArray = new ArrayList<>(values.keySet());
        final String lastKey = keysArray.get(keysArray.size() - 1);
        for (final String key : values.keySet())
            if (!key.equals(lastKey))
                statement.append(key).append(",");
            else
                statement.append(key).append("\n)\n\t");

        statement.append(" values (\n\t");

        final ArrayList<String> valuesArray = new ArrayList<>(values.values());
        final String lastValue = valuesArray.get(valuesArray.size() - 1);
        for (final String value : values.values())
            if (!value.equals(lastValue))
                statement.append("?, ");
            else
                statement.append("?\n);");

        if (this.debug)
            this.log(String.valueOf(statement));

        final PreparedStatement prepStatement = this.connection.prepareStatement(statement.toString());
        int i = 0;

        for (final String value : values.values()) {
            i++;
            prepStatement.setObject(i, value);
        }

        if (this.debug)
            this.log("Inserting into table: " + table + " with values: " + values);
        prepStatement.executeUpdate();
    }

    /**
     * Insert into a database
     *
     * @param builder The builder you'd like to use
     * @throws SQLException if there is an error
     */
    public void insert(@NotNull final InsertBuilder builder) throws SQLException {
        final StringBuilder statement = new StringBuilder("insert into `" + builder.getTable() + "` (\n\t");

        final ArrayList<String> keysArray = new ArrayList<>(builder.getValues().keySet());
        final String lastKey = keysArray.get(keysArray.size() - 1);
        for (final String key : builder.getValues().keySet())
            if (!key.equals(lastKey))
                statement.append(key).append(",");
            else
                statement.append(key).append("\n)\n\t");

        statement.append(" values (\n\t");

        final ArrayList<String> valuesArray = new ArrayList<>(builder.getValues().values());
        final String lastValue = valuesArray.get(valuesArray.size() - 1);
        for (final String value : builder.getValues().values())
            if (!value.equals(lastValue))
                statement.append("?, ");
            else
                statement.append("?\n);");

        if (this.debug)
            this.log(String.valueOf(statement));

        final PreparedStatement prepStatement = this.connection.prepareStatement(statement.toString());
        int i = 0;

        for (final String value : builder.getValues().values()) {
            i++;
            prepStatement.setObject(i, value);
        }

        if (this.debug)
            this.log("Inserting into table: " + builder.getTable() + " with values: " + builder.getValues());
        prepStatement.executeUpdate();
    }

    /**
     * Delete a row rom the database
     *
     * @param table The table you'd like to edit
     * @param key   The key, basically the identifier
     * @param value The value, such as the player's name
     */
    public void delete(@NotNull final String table, @NotNull final String key, @NotNull final String value) throws SQLException {
        final String statement = "DELETE FROM `" + table + "` WHERE '" + key + "'='" + value + "'";
        new Statement(statement, this.connection).execute();
        if (this.debug)
            this.log("Deleting from table: " + table + " with key: " + key + " and value: " + value);
    }

    /**
     * Check if a row exists
     *
     * @param table The table you'd like to check
     * @param key   The key
     * @param value The value
     * @return whether that row exists
     * @throws SQLException if there is an error connecting to the database
     */
    public boolean rowExists(@NotNull final String table, @NotNull final String key, @NotNull final String value) throws SQLException {
        final String statement = "SELECT * FROM `" + table + "` WHERE '" + key + "'='" + value + "'";
        if (this.debug)
            this.log("Checking if row exists: " + statement);
        return new Statement(statement, this.connection).executeWithResults().next();
    }

    /**
     * Check if a row exists
     *
     * @param table   The table you'd like to check
     * @param builder The builder you'd like to use
     * @return whether that row exists
     * @throws SQLException if there is an error connecting to the database
     */
    public boolean rowExists(@NotNull final String table, @NotNull final WhereBuilder builder) throws SQLException {
        final String statement = "SELECT * FROM `" + table + "` WHERE '" + builder.getKey() + "'='" + builder.getValue() + "'";
        if (this.debug)
            this.log("Checking if row exists: " + statement);
        return new Statement(statement, this.connection).executeWithResults().next();
    }

    /**
     * Replace a current row with a new one
     *
     * @param table  The table in which the row is located
     * @param key    The key you would like to check
     * @param value  the value of that key
     * @param values the values of the new row you'd like to insert
     * @throws SQLException If there's an error communicating with the database
     */
    public void replace(@NotNull final String table, @NotNull final String key, @NotNull final String value, @NotNull final HashMap<String, String> values) throws SQLException {
        if (!this.rowExists(table, key, value)) return;

        if (this.debug)
            this.log("Replacing row in table: " + table + " with key: " + key + " and value: " + value);

        this.delete(table, key, value);
        this.insert(table, values);
    }

    /**
     * Replace a current row with a new one
     *
     * @param table        The table in which the row is located
     * @param whereBuilder The where builder you'd like to use
     * @param values       the values of the new row you'd like to insert
     * @throws SQLException If there's an error communicating with the database
     */
    public void replace(@NotNull final String table, @NotNull final WhereBuilder whereBuilder, @NotNull final HashMap<String, String> values) throws SQLException {
        if (!this.rowExists(table, whereBuilder.getKey(), whereBuilder.getValue())) return;

        if (this.debug)
            this.log("Replacing row in table: " + table + " with key: " + whereBuilder.getKey() + " and value: " + whereBuilder.getValue());

        this.delete(table, whereBuilder.getKey(), whereBuilder.getValue());
        this.insert(table, values);
    }

    /**
     * Delete a table
     *
     * @param name The name of the table you'd like to delete
     * @throws SQLException if there is an error communicating with the database
     */
    public void deleteTable(@NotNull final String name) throws SQLException {
        if (!this.tableExists(name)) return;
        if (this.debug)
            this.log("Deleteing table: " + name);
        new Statement("DROP TABLE " + name + ";", this.connection).execute();
    }

    /**
     * Update a row in a table
     *
     * @param table        The table you'd like to update
     * @param whereBuilder The where builder you'd like to use
     * @param column       The column you'd like to update
     * @param newColumn    The new value you'd like to insert
     * @throws SQLException if there is an error communicating with the database
     */
    public void update(@NotNull final String table, @NotNull final WhereBuilder whereBuilder, @NotNull final String column, @NotNull final String newColumn) throws SQLException {
        final String statement = "UPDATE `" + table + "` SET `" + column + "`=`" + newColumn + "` WHERE `" + whereBuilder.getKey() + "`='" + whereBuilder.getValue() + "'";
        if (this.debug)
            this.log("Updating row with table: " + table + " with key: " + whereBuilder.getKey() + " and value: " + whereBuilder.getValue() + " with column: " + column + " and new value: " + newColumn);
        new Statement(statement, this.connection).execute();
    }


    /**
     * Update a table in the database
     *
     * @param table  The table you'd like to update
     * @param column The column you'd like to update
     * @param type   The type of the column
     * @throws SQLException if there is an error communicating with the database
     */
    public void addColumnToTable(final String table, final String column, final String type, final int amount) throws SQLException {
        final String statement = "ALTER TABLE `" + table + "` ADD `" + column + "` " + type + "(" + amount + ");";
        if (this.debug)
            this.log("Adding column to table: " + table + " with name: " + column + " and type: " + type);
        new Statement(statement, this.connection).execute();
    }

    /**
     * Remove a column from a table
     *
     * @param table  The table you'd like to remove a column from
     * @param column The column you'd like to remove
     * @throws SQLException if there is an error communicating with the database
     */
    public void removeColumnFromTable(final String table, final String column) throws SQLException {
        final String statement = "ALTER TABLE `" + table + "` DROP COLUMN `" + column + "`;";
        if (this.debug)
            this.log("Removing column: " + column + " from table: " + table);
        new Statement(statement, this.connection).execute();
    }

    /**
     * Change a column's name
     *
     * @param table   The table you'd like to change a column's name in
     * @param oldName The old name of the column
     * @param newName The new name of the column
     * @throws SQLException if there is an error communicating with the database
     */
    public void changeColumnName(final String table, final String oldName, final String newName) throws SQLException {
        final String statement = "ALTER TABLE `" + table + "` CHANGE `" + oldName + "` `" + newName + "`;";
        if (this.debug)
            this.log("Changing column name: " + oldName + " to " + newName + " in table: " + table);
        new Statement(statement, this.connection).execute();
    }

    /**
     * Delete a column from a table
     *
     * @param table  The table you'd like to delete a column from
     * @param column The column you'd like to delete
     * @throws SQLException if there is an error communicating with the database
     */
    public void deleteColumnFromTable(final String table, final String column) throws SQLException {
        final String statement = "ALTER TABLE `" + table + "` DROP COLUMN `" + column + "`;";
        if (this.debug)
            this.log("Deleteing column: " + column + " from table: " + table);
        new Statement(statement, this.connection).execute();
    }

    /**
     * Export a table to a file
     *
     * @param table    The table you'd like to export
     * @param filePath The file's path you'd like to export to
     * @throws SQLException if there is an error communicating with the database
     */
    public void exportToCSV(final String table, final String filePath) throws SQLException {
        final String statement = "SELECT * FROM `" + table + "`";
        if (this.debug)
            this.log("Exporting table: " + table + " to file: " + filePath);
        final ResultSet resultSet = new Statement(statement, this.connection).executeWithResults();
        try {
            final FileWriter writer = new FileWriter(filePath);
            while (resultSet.next()) {
                for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                    writer.write(resultSet.getString(i));
                    if (i != resultSet.getMetaData().getColumnCount())
                        writer.write(",");
                }
                writer.write("\n");
            }
            writer.close();
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Import a table from a file
     *
     * @param table    The table you'd like to import into
     * @param filePath The file's path you'd like to import from
     * @throws SQLException if there is an error communicating with the database
     */
    public void importFromFile(final String table, final String filePath) throws SQLException {
        final String statement = "LOAD DATA INFILE '" + filePath + "' INTO TABLE `" + table + "`";
        if (this.debug)
            this.log("Importing table: " + table + " from file: " + filePath);
        new Statement(statement, this.connection).execute();
    }

    /**
     * Count the number of rows in a table
     *
     * @param table The table you'd like to count
     * @return The number of rows in the table
     * @throws SQLException if there is an error communicating with the database
     */
    public int countRows(final String table) throws SQLException {
        final String statement = "SELECT COUNT(*) FROM `" + table + "`";
        if (this.debug)
            this.log("Counting rows in table: " + table);
        final ResultSet resultSet = new Statement(statement, this.connection).executeWithResults();
        resultSet.next();
        return resultSet.getInt(1);
    }

    /**
     * Get all tables in the database
     *
     * @return A list of all tables in the database
     * @throws SQLException if there is an error communicating with the database
     */
    public ResultSet getAllTables() throws SQLException {
        final String statement = "SHOW TABLES";
        if (this.debug)
            this.log("Getting all tables");
        return new Statement(statement, this.connection).executeWithResults();
    }

    /**
     * Get all data in a table
     *
     * @param table The table you'd like to get data from
     * @return A list of all data in the table
     * @throws SQLException if there is an error communicating with the database
     */
    public ResultSet getAllDataInTable(final String table) throws SQLException {
        final String statement = "SELECT * FROM `" + table + "`";
        if (this.debug)
            this.log("Getting all data in table: " + table);
        return new Statement(statement, this.connection).executeWithResults();
    }

    /**
     * Delete a table if it exists
     *
     * @param table The table you'd like to delete
     * @throws SQLException if there is an error communicating with the database
     */
    public void deleteTableIfExists(final String table) throws SQLException {
        final String statement = "DROP TABLE IF EXISTS `" + table + "`";
        if (this.debug)
            this.log("Deleting table if it exists: " + table);
        new Statement(statement, this.connection).execute();
    }

    /**
     * Replace the primary key of a table
     *
     * @param table      The table you'd like to replace the primary key in
     * @param primaryKey The new primary key
     */
    public void replacePrimaryKey(final String table, final String primaryKey) {
        final String statement = "ALTER TABLE `" + table + "` DROP PRIMARY KEY, ADD PRIMARY KEY (`" + primaryKey + "`);";
        if (this.debug)
            this.log("Changing primary key of table: " + table + " to: " + primaryKey);
        new Statement(statement, this.connection).execute();
    }

    /**
     * Copies the contents of one table to another
     *
     * @param table    The table you'd like to copy to
     * @param copyFrom The table you'd like to copy from
     * @throws SQLException if there is an error communicating with the database
     */
    public void copyContentsToNewTable(final String table, final String copyFrom) throws SQLException {
        final String statement = "INSERT INTO `" + table + "` SELECT * FROM `" + copyFrom + "`;";
        if (this.debug)
            this.log("Copying contents from table: " + copyFrom + " to table: " + table);
        new Statement(statement, this.connection).execute();
    }

    /**
     * Describe a table
     *
     * @param table The table you'd like to describe
     * @return The descrhosttion of the table
     * @throws SQLException if there is an error communicating with the database
     */
    public ResultSet describeTable(final String table) throws SQLException {
        final String statement = "DESCRIBE `" + table + "`";
        if (this.debug)
            this.log("Describing table: " + table);
        return new Statement(statement, this.connection).executeWithResults();
    }

    /**
     * Describe a column in a table
     *
     * @param table  The table you'd like to describe
     * @param column The column you'd like to describe
     * @return The descrhosttion of the column
     * @throws SQLException if there is an error communicating with the database
     */
    public ResultSet describeColumn(final String table, final String column) throws SQLException {
        final String statement = "DESCRIBE `" + table + "` `" + column + "`";
        if (this.debug)
            this.log("Describing column: " + column + " in table: " + table);
        return new Statement(statement, this.connection).executeWithResults();
    }

    /**
     * Set a column's default value
     *
     * @param table  The table you'd like to set the default value in
     * @param column The column you'd like to set the default value for
     * @param value  The default value you'd like to set
     * @throws SQLException if there is an error communicating with the database
     */
    public void setColumnDefaultValue(final String table, final String column, final String value) throws SQLException {
        final String statement = "ALTER TABLE `" + table + "` ALTER `" + column + "` SET DEFAULT " + value + ";";
        if (this.debug)
            this.log("Setting default value: " + value + " for column: " + column + " in table: " + table);
        new Statement(statement, this.connection).execute();
    }

    /**
     * Write {@code Java Objects} to a table
     *
     * @param table  The table you'd like to write to
     * @param object The object you'd like to insert
     * @throws SQLException if there is an error communicating with the database
     */
    public void insert(final String table, final Object object) throws SQLException {
        final ArrayList<String> keys = new ArrayList<>();
        final ArrayList<String> values = new ArrayList<>();

        // Adds all fields to the keys and values ArrayLists
        for (final Field field : object.getClass().getDeclaredFields()) {
            String key = field.getName();

            // Checks the field's annotations
            if (field.isAnnotationPresent(DontSave.class)) continue;
            if (field.isAnnotationPresent(de.databaseconnector.annotations.Column.class)) {
                // If there is an annotation, use the annotation's name instead of the field's name
                key = field.getAnnotation(de.databaseconnector.annotations.Column.class).value();
                return;
            }

            keys.add(key);
            try {
                field.setAccessible(true);
                values.add(field.get(object).toString());
            } catch (final IllegalAccessException e) {
                e.printStackTrace();
            }
        }

        // Adds all fields from the superclass to the keys and values ArrayLists
        for (final Field field : object.getClass().getSuperclass().getDeclaredFields()) {
            String key = field.getName();

            // Checks the field's annotations
            if (field.isAnnotationPresent(DontSave.class)) continue;
            if (field.isAnnotationPresent(de.databaseconnector.annotations.Column.class)) {
                // If there is an annotation, use the annotation's name instead of the field's name
                key = field.getAnnotation(de.databaseconnector.annotations.Column.class).value();
                return;
            }

            keys.add(key);
            try {
                field.setAccessible(true);
                values.add(field.get(object).toString());
            } catch (final IllegalAccessException e) {
                e.printStackTrace();
            }
        }

        // Loops through and puts everything in a HashMap
        final HashMap<String, String> keyValuesHashMap = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) keyValuesHashMap.put(keys.get(i), values.get(i));

        // Writes the HashMap to the table
        this.insert(table, keyValuesHashMap);

        if (this.debug)
            this.log("Wrote object to table: " + table);
    }

    /**
     * Write multihost {@code Java Objects} to a table
     *
     * @param table   The table you'd like to write to
     * @param objects The objects you'd like to insert
     */
    public void insertList(final String table, final List<?> objects) {
        objects.forEach(object -> {
            final ArrayList<String> keys = new ArrayList<>();
            final ArrayList<String> values = new ArrayList<>();

            // Adds all fields to the keys and values ArrayLists
            for (final Field field : object.getClass().getDeclaredFields()) {
                String key = field.getName();

                // Checks the field's annotations
                if (field.isAnnotationPresent(DontSave.class)) continue;
                if (field.isAnnotationPresent(de.databaseconnector.annotations.Column.class)) {
                    // If there is an annotation, use the annotation's name instead of the field's name
                    key = field.getAnnotation(de.databaseconnector.annotations.Column.class).value();
                    return;
                }

                keys.add(key);
                try {
                    field.setAccessible(true);
                    values.add(field.get(object).toString());
                } catch (final IllegalAccessException e) {
                    e.printStackTrace();
                }
            }

            // Adds all fields from the superclass to the keys and values ArrayLists
            for (final Field field : object.getClass().getSuperclass().getDeclaredFields()) {
                String key = field.getName();

                // Checks the field's annotations
                if (field.isAnnotationPresent(DontSave.class)) continue;
                if (field.isAnnotationPresent(de.databaseconnector.annotations.Column.class)) {
                    // If there is an annotation, use the annotation's name instead of the field's name
                    key = field.getAnnotation(de.databaseconnector.annotations.Column.class).value();
                    return;
                }

                keys.add(key);
                try {
                    field.setAccessible(true);
                    values.add(field.get(object).toString());
                } catch (final IllegalAccessException e) {
                    e.printStackTrace();
                }
            }

            // Loops through and puts everything in a HashMap
            final HashMap<String, String> keyValuesHashMap = new HashMap<>();
            for (int i = 0; i < keys.size(); i++) keyValuesHashMap.put(keys.get(i), values.get(i));

            // Writes the HashMap to the table
            try {
                this.insert(table, keyValuesHashMap);
            } catch (final SQLException e) {
                e.printStackTrace();
            }

            if (this.debug)
                this.log("Wrote object to table: " + table);
        });
    }

    /**
     * Reads {@code Java Objects} from a table
     *
     * @param table The table you'd like to read from
     * @param key   The key you'd like to read from
     * @param value The value you'd like to read from
     * @param clazz The class you'd like to read into
     * @return The object you read into
     * @throws SQLException              if there is an error communicating with the database
     * @throws IllegalAccessException    if there is an error accessing the object
     * @throws InstantiationException    if there is an error instantiating the object
     * @throws InvocationTargetException if there is an error invoking the object
     */
    public Object get(final String table, final String key, final String value, final Class<?> clazz) throws SQLException, InvocationTargetException, InstantiationException, IllegalAccessException {
        final String statement = "SELECT * FROM `" + table + "` WHERE `" + key + "` = '" + value + "';";
        if (this.debug)
            this.log("Reading object from table: " + table + " with key: " + key + " and value: " + value);
        final ResultSet resultSet = new Statement(statement, this.connection).executeWithResults();

        // Creates a new instance of the class
        final Object object;
        final Constructor<?> constructor = this.retrieveConstructor(clazz);
        final ArrayList<Object> parameters = new ArrayList<>();

        final HashMap<String, Object> keyValuesHashMap = new HashMap<>();

        // Loops through all the columns and adds them to the HashMap
        while (resultSet.next()) for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++)
            keyValuesHashMap.put(resultSet.getMetaData().getColumnName(i), resultSet.getObject(i));

        for (final Parameter p : constructor.getParameters())
            if (this.hasAnnotation(p))
                parameters.add(keyValuesHashMap.get(p.getAnnotation(de.databaseconnector.annotations.Column.class).value()));

        if (this.debug)
            this.log("Read object from table: " + table);
        object = constructor.newInstance(parameters.toArray());
        return object;
    }

    /**
     * Reads a list of {@code Java Objects} from a table that match the where clause
     *
     * @param key   The key you'd like to use
     * @param value The value the key should be
     * @param table The table you'd like to read from
     * @param clazz The type of class you want to return
     * @return An Optional class either containing a List<?> or nothing if it didn't find any results</?>
     * @throws SQLException              If there is an error communicating with the database
     * @throws InvocationTargetException If there is an error invoking the object
     * @throws InstantiationException    If there is an error instantiating the object
     * @throws IllegalAccessException    If there is an error accessing some parameters within the object
     */
    public Optional<List<?>> getList(final String key, final String value, final String table, final Class<?> clazz) throws SQLException, InvocationTargetException, InstantiationException, IllegalAccessException {
        final String statement = "SELECT * FROM `" + table + "` WHERE `" + key + "` = '" + value + "';";
        if (this.debug)
            this.log("Reading objects from table: " + table + " with key: " + key + " and value: " + value);
        final ResultSet resultSet = new Statement(statement, this.connection).executeWithResults();
        final List<Object> returnObjects = new ArrayList<>();

        int i = 0;
        while (resultSet.next()) {
            returnObjects.add(this.get(table, key, value, clazz, i));
            i++;
        }

        return returnObjects.isEmpty() ? Optional.empty() : Optional.of(returnObjects);
    }

    /**
     * Reads a list of {@code Java Objects} from a table. This method assumes that every row in the list represents the same object.
     *
     * @param table The table you'd like to read from
     * @param clazz The type of class you want to return
     * @return An Optional class either containing a List<?> or nothing if it didn't find any results</?>
     * @throws SQLException              If there is an error communicating with the database
     * @throws InvocationTargetException If there is an error invoking the object
     * @throws InstantiationException    If there is an error instantiating the object
     * @throws IllegalAccessException    If there is an error accessing some parameters within the object
     */
    public Optional<List<?>> getList(final String table, final Class<?> clazz) throws SQLException, InvocationTargetException, InstantiationException, IllegalAccessException {
        final String statement = "SELECT * FROM `" + table + "`;";
        if (this.debug)
            this.log("Reading objects from table: " + table);
        final ResultSet resultSet = new Statement(statement, this.connection).executeWithResults();
        final List<Object> returnObjects = new ArrayList<>();

        int i = 0;
        while (resultSet.next()) {
            returnObjects.add(this.get(table, clazz, i));
            i++;
        }

        return returnObjects.isEmpty() ? Optional.empty() : Optional.of(returnObjects);
    }

    /**
     * Reads {@code Java Objects} from a table
     *
     * @param table The table you'd like to read from
     * @param key   The key you'd like to read from
     * @param value The value you'd like to read from
     * @param clazz The class you'd like to read into
     * @return The object you read into
     * @throws SQLException              if there is an error communicating with the database
     * @throws IllegalAccessException    if there is an error accessing the object
     * @throws InstantiationException    if there is an error instantiating the object
     * @throws InvocationTargetException if there is an error invoking the object
     */
    private Object get(final String table, final String key, final String value, final Class<?> clazz, final int index) throws SQLException, InvocationTargetException, InstantiationException, IllegalAccessException {
        final String statement = "SELECT * FROM `" + table + "` WHERE `" + key + "` = '" + value + "';";
        if (this.debug)
            this.log("Reading object from table: " + table + " with key: " + key + " and value: " + value);
        final ResultSet resultSet = new Statement(statement, this.connection).executeWithResults();

        // Creates a new instance of the class
        final Object object;
        final Constructor<?> constructor = this.retrieveConstructor(clazz);
        final ArrayList<Object> parameters = new ArrayList<>();

        final HashMap<String, Object> keyValuesHashMap = new HashMap<>();

        int resultSetIndex = 0;
        while (resultSet.next()) {
            // Loops through all the columns and adds them to the HashMap
            if (resultSetIndex == index) for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++)
                keyValuesHashMap.put(resultSet.getMetaData().getColumnName(i), resultSet.getObject(i));
            resultSetIndex++;
        }

        for (final Parameter p : constructor.getParameters())
            if (this.hasAnnotation(p))
                parameters.add(keyValuesHashMap.get(p.getAnnotation(de.databaseconnector.annotations.Column.class).value()));

        if (this.debug)
            this.log("Read object from table: " + table);
        object = constructor.newInstance(parameters.toArray());
        return object;
    }

    /**
     * Reads {@code Java Objects} from a table
     *
     * @param table The table you'd like to read from
     * @param clazz The class you'd like to read into
     * @return The object you read into
     * @throws SQLException              if there is an error communicating with the database
     * @throws IllegalAccessException    if there is an error accessing the object
     * @throws InstantiationException    if there is an error instantiating the object
     * @throws InvocationTargetException if there is an error invoking the object
     */
    private Object get(final String table, final Class<?> clazz, final int index) throws SQLException, InvocationTargetException, InstantiationException, IllegalAccessException {
        final String statement = "SELECT * FROM `" + table + "`;";
        if (this.debug)
            this.log("Reading object from table: " + table);
        final ResultSet resultSet = new Statement(statement, this.connection).executeWithResults();

        // Creates a new instance of the class
        final Object object;
        final Constructor<?> constructor = this.retrieveConstructor(clazz);
        final ArrayList<Object> parameters = new ArrayList<>();

        final HashMap<String, Object> keyValuesHashMap = new HashMap<>();

        int resultSetIndex = 0;
        while (resultSet.next()) {
            // Loops through all the columns and adds them to the HashMap
            if (resultSetIndex == index) for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++)
                keyValuesHashMap.put(resultSet.getMetaData().getColumnName(i), resultSet.getObject(i));
            resultSetIndex++;
        }

        for (final Parameter p : constructor.getParameters())
            if (this.hasAnnotation(p))
                parameters.add(keyValuesHashMap.get(p.getAnnotation(de.databaseconnector.annotations.Column.class).value()));

        if (this.debug)
            this.log("Read object from table: " + table);
        object = constructor.newInstance(parameters.toArray());
        return object;
    }

    /**
     * Retrieves the correct constructor for a class
     *
     * @param clazz The class you'd like to get the constructor for
     * @return The constructor you retrieved
     */
    private Constructor<?> retrieveConstructor(final Class<?> clazz) {
        final ArrayList<Constructor<?>> constructors = new ArrayList<>(Arrays.asList(clazz.getConstructors()));
        final AtomicReference<Constructor<?>> validConstructor = new AtomicReference<>();
        for (final Constructor<?> constructor : constructors) {
            constructor.setAccessible(true);
            Arrays.stream(constructor.getAnnotations()).forEach(annotation -> {
                if (annotation.annotationType().equals(DatabaseConstructor.class)) validConstructor.set(constructor);
            });
        }
        return validConstructor.get();
    }

    /**
     * Checks if a parameter has the {@link de.databaseconnector.annotations.Column} annotation
     *
     * @param param The parameter you'd like to check
     * @return Whether the parameter has the {@link de.databaseconnector.annotations.Column} annotation
     */
    private boolean hasAnnotation(final Parameter param) {
        return param.isAnnotationPresent(de.databaseconnector.annotations.Column.class);
    }

    /**
     * Logs a message to the console
     *
     * @param text The message you'd like to log
     */
    private void log(@NotNull final String text) {
        System.out.println("[Database] " + text);
    }

}

