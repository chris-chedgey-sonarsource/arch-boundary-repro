package com.example;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Minimal reproduction for the arch_boundaries.boundary_key length defect.
 *
 * shortQuery() is the control: its SQL is well under the 255-character limit
 * that boundary_key imposes, so its exit point stores without complaint.
 *
 * longQuery() carries a statement past that limit. The architecture analyser
 * builds the boundary key from the statement text, so the key overflows the
 * column and the insert is rejected -- taking every other boundary in the same
 * batch with it, including the one from shortQuery().
 */
public class QueryRunner {

    public ResultSet shortQuery(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        return statement.executeQuery("SELECT id, name FROM customer WHERE active = true");
    }

    public ResultSet longQuery(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        return statement.executeQuery(
            "SELECT c.id, c.name, c.email, c.created_at, o.order_id, o.total_amount, "
          + "o.placed_at, o.status, p.product_id, p.product_name, p.unit_price, "
          + "i.quantity, i.discount_pct, a.line1, a.line2, a.city, a.postcode, "
          + "a.country_code, s.carrier, s.tracking_ref, s.dispatched_at "
          + "FROM customer c "
          + "JOIN orders o ON o.customer_id = c.id "
          + "JOIN order_item i ON i.order_id = o.order_id "
          + "JOIN product p ON p.product_id = i.product_id "
          + "JOIN address a ON a.customer_id = c.id "
          + "LEFT JOIN shipment s ON s.order_id = o.order_id "
          + "WHERE c.active = true AND o.placed_at >= ? AND o.status <> 'CANCELLED' "
          + "ORDER BY o.placed_at DESC, c.name ASC");
    }

    public void createTable(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        statement.execute(
            "CREATE TABLE IF NOT EXISTS audit_event ("
          + "  event_id BIGSERIAL PRIMARY KEY, "
          + "  actor_id BIGINT NOT NULL, "
          + "  actor_display_name VARCHAR(255) NOT NULL, "
          + "  event_kind VARCHAR(64) NOT NULL, "
          + "  target_type VARCHAR(64) NOT NULL, "
          + "  target_id VARCHAR(128) NOT NULL, "
          + "  payload JSONB NOT NULL, "
          + "  correlation_ref UUID, "
          + "  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL, "
          + "  recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()"
          + ")");
    }
}
