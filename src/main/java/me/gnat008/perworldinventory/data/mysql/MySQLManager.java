/*
 * Copyright (C) 2014-2015  Gnat008
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.gnat008.perworldinventory.data.mysql;

import me.gnat008.perworldinventory.PerWorldInventory;

import java.sql.Connection;
import java.sql.SQLException;

public class MySQLManager {

    private static MySQLManager instance = null;

    private Connection conn = null;
    private PerWorldInventory plugin;

    private MySQLManager() {
        this.plugin = PerWorldInventory.getInstance();
    }

    public static MySQLManager getInstance() {
        if (instance == null) {
            instance = new MySQLManager();
        }

        return instance;
    }

    public void disable() {
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (SQLException ex) {
            plugin.getLogger().severe("Could not close connection: " + ex.getMessage());
        }

        instance = null;
    }

    public void startConnection() throws SQLException {
        conn = MySQL.getConnection();
        plugin.getLogger().info("Connected to MySQL database!");
    }

    private void setupTables() throws SQLException {
        conn.
    }
}
