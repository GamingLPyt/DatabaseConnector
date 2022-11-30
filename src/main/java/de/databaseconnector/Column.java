package de.databaseconnector;

import lombok.Data;

@Data
public class Column {
    private final ColumnType type;
    private final String name;
    private int length = 255;
    private boolean allowNull = false;
    private String defaultValue = null;
}
