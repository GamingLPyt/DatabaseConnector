package de.databaseconnector.annotations.builders;

import lombok.Getter;

import java.util.HashMap;

@Getter
public class InsertBuilder {

    private final HashMap<String, String> values = new HashMap<>();
    private String table;

    public InsertBuilder value(final String column, final String value) {
        this.values.put(column, value);
        return this;
    }

    public InsertBuilder table(final String table) {
        this.table = table;
        return this;
    }

}

