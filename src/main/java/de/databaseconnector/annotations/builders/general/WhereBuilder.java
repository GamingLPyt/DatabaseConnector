package de.databaseconnector.annotations.builders.general;

import lombok.Getter;

@Getter
public class WhereBuilder {

    private String key;
    private String value;

    public WhereBuilder key(final String key) {
        this.key = key;
        return this;
    }

    public WhereBuilder value(final String value) {
        this.value = value;
        return this;
    }
}
