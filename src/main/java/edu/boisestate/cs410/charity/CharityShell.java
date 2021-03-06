package edu.boisestate.cs410.charity;

import com.budhash.cliche.Command;
import com.budhash.cliche.ShellFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.*;

public class CharityShell {
    private final Connection db;

    public CharityShell(Connection cxn) {
        db = cxn;
    }

    @Command
    public void funds() throws SQLException {
        String query = "SELECT fund_id, fund_name FROM fund";
        try (Statement stmt = db.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            System.out.format("Funds:%n");
            while (rs.next()) {
                System.out.format("%d: %s%n",
                                  rs.getInt(1),
                                  rs.getString(2));
            }
        }
    }

    @Command
    public void donor(int id) throws SQLException {
        String query =
                "SELECT donor_name, donor_address,\n" +
                        " donor_city, donor_state, donor_zip,\n" +
                        " SUM(amount) AS total_given\n" +
                        "FROM donor\n" +
                        "JOIN gift USING (donor_id)\n" +
                        "JOIN gift_fund_allocation USING (gift_id)\n" +
                        "WHERE donor_id = ?\n" +
                        "GROUP BY donor_id;";
        try (PreparedStatement stmt = db.prepareStatement(query)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    System.err.format("%d: donor does not exist%n", id);
                    return;
                }
                System.out.format("%s%n", rs.getString("donor_name"));
                System.out.format("%s%n", rs.getString("donor_address"));
                System.out.format("%s, %s %s%n",
                                  rs.getString("donor_city"),
                                  rs.getString("donor_state"),
                                  rs.getString("donor_zip"));
                System.out.format("Total given: %s%n",
                                  rs.getBigDecimal("total_given"));
            }
        }
    }

    @Command
    public void renameDonor(int id, String name) throws SQLException {
        String query = "UPDATE donor SET donor_name = ? WHERE donor_id = ?";
        try (PreparedStatement stmt = db.prepareStatement(query)) {
            stmt.setString(1, name);
            stmt.setInt(2, id);
            System.out.format("Renaming donor %d to %s%n", id, name);
            int nrows = stmt.executeUpdate();
            System.out.format("updated %d donors%n", nrows);
        }
    }

    @Command
    public void addGift(int donor, String date, String... allocs) throws SQLException {
        String insertGift = "INSERT INTO gift (donor_id, gift_date) VALUES (?, ?)";
        String allocate = "INSERT INTO gift_fund_allocation (gift_id, fund_id, amount) VALUES (?, ?, ?)";
        int giftId;
        db.setAutoCommit(false);
        try {
            try (PreparedStatement stmt = db.prepareStatement(insertGift, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setInt(1, donor);
                stmt.setString(2, date);
                stmt.executeUpdate();
                // fetch the generated gift_id!
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (!rs.next()) {
                        throw new RuntimeException("no generated keys???");
                    }
                    giftId = rs.getInt(1);
                    System.out.format("Creating gift %d%n", giftId);
                }
            }
            try (PreparedStatement stmt = db.prepareStatement(allocate)) {
                for (int i = 0; i < allocs.length; i += 2) {
                    stmt.setInt(1, giftId);
                    stmt.setInt(2, Integer.parseInt(allocs[i]));
                    stmt.setBigDecimal(3, new BigDecimal(allocs[i + 1]));
                    stmt.executeUpdate();
                }
            }
            db.commit();
        } catch (SQLException | RuntimeException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    @Command
    public void generate() throws SQLException {
        generate(50);
    }

    @Command
    public void generate(int donors) throws SQLException {
        generate(donors, 10);
    }

    @Command
    public void generate(int donors, double gpd) throws SQLException {
        String cq = "SELECT COUNT(DISTINCT gift_id) AS ngifts, SUM(amount) AS total FROM gift_fund_allocation";
        db.setAutoCommit(false);
        try {
            var gen = new CharityDataGenerator(db, donors, gpd);
            gen.generate();
            try (Statement s = db.createStatement();
                 ResultSet rs = s.executeQuery(cq)) {
                rs.next();
                int ng = rs.getInt("ngifts");
                BigDecimal funds = rs.getBigDecimal("total");
                System.out.format("database has %d gifts totalling %s%n", ng, funds);
            }
            db.commit();
        } catch (SQLException | RuntimeException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    @Command
    public void echo(String... args) {
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                System.out.print(' ');
            }
            System.out.print(args[i]);
        }
        System.out.println();
    }

    public static void main(String[] args) throws IOException, SQLException {
        // First (and only) command line argument: database URL
        String dbUrl = args[0];
        try (Connection cxn = DriverManager.getConnection("jdbc:" + dbUrl)) {
            CharityShell shell = new CharityShell(cxn);
            ShellFactory.createConsoleShell("charity", "", shell)
                        .commandLoop();
        }
    }
}
