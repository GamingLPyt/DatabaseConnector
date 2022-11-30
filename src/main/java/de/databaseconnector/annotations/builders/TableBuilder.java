package de.databaseconnector.annotations.builders;

import de.databaseconnector.Column;
import de.databaseconnector.ColumnType;
import lombok.Data;
import lombok.NonNull;

import java.util.List;

@Data
public class TableBuilder {

    private final String name;
    private final List<Column> columns;
    private String primaryKey;

    public void addColumn(@NonNull final ColumnType type, @NonNull final String name) {
        this.columns.add(new Column(type, name));
    }

    public @NonNull TableBuilder addColumn(@NonNull final Column column) {
        this.columns.add(column);
        return this;
    }
}
