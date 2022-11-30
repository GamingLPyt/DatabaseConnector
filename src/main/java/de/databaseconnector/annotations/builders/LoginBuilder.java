package de.databaseconnector.annotations.builders;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginBuilder {

    private String host;
    private int port;
    private String username;
    private String password;
    private String database;

}

