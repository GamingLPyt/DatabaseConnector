package de.databaseconnector;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.SneakyThrows;

import java.sql.Connection;
import java.sql.ResultSet;

@Data
@AllArgsConstructor
public class Statement {

    private String value;
    private Connection connection;

    /**
     * Execute your statement
     *
     * @return a {@link ResultSet}
     */
    @SneakyThrows
    public ResultSet executeWithResults() {
        return this.connection.createStatement().executeQuery(this.getValue());
    }

    /**
     * Execute your statement
     */
    @SneakyThrows
    public void execute() {
        this.connection.createStatement().execute(this.getValue());
    }
}

