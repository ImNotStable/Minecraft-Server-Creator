package io.github.imnotstable.msc.stdlib.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Reads table, column and index names from JDBC metadata, whatever letter case the backend stores them in. */
final class Schema {
    private Schema() {}

    /** The table's name as the database stores it, or null. */
    static String findTable(Connection c, String table) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        for (String name : new String[]{table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)}) {
            try (ResultSet rs = meta.getTables(c.getCatalog(), null, name, new String[]{"TABLE"})) {
                if (rs.next()) {
                    return rs.getString("TABLE_NAME");
                }
            }
        }
        return null;
    }

    static Set<String> columns(Connection c, String table) throws SQLException {
        Set<String> out = new HashSet<>();
        String stored = findTable(c, table);
        if (stored == null) {
            return out;
        }
        try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, stored, null)) {
            while (rs.next()) {
                out.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    static Set<String> indexes(Connection c, String table) throws SQLException {
        Set<String> out = new HashSet<>();
        String stored = findTable(c, table);
        if (stored == null) {
            return out;
        }
        try (ResultSet rs = c.getMetaData().getIndexInfo(c.getCatalog(), null, stored, false, false)) {
            while (rs.next()) {
                String n = rs.getString("INDEX_NAME");
                if (n != null) {
                    out.add(n.toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
