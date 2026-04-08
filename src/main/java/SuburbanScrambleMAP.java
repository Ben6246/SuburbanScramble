import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.OSMTileFactoryInfo;
import org.jxmapviewer.viewer.DefaultTileFactory;
import org.jxmapviewer.viewer.GeoPosition;
import org.jxmapviewer.viewer.TileFactoryInfo;
import org.jxmapviewer.painter.CompoundPainter;
import org.jxmapviewer.painter.Painter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SuburbanScrambleMAP extends JFrame {

    // ── Data model ──────────────────────────────────────────────────────────

    /** One town/municipality fetched from Overpass */
    static class TownRegion {
        String name;
        List<GeoPosition> polygon = new ArrayList<>(); // outer boundary points
        int claimState = 0; // 0 = unclaimed, 1 = team1, 2 = team2
    }

    // Claim maps (name → region) mirroring API.java
    private final Map<String, TownRegion> t1_towns = new HashMap<>();
    private final Map<String, TownRegion> t2_towns = new HashMap<>();
    private final List<TownRegion> regions = new ArrayList<>();

    // The map widget
    private JXMapViewer mapViewer;

    // ── Constructor ─────────────────────────────────────────────────────────

    public SuburbanScrambleMAP() {

        // 1) Prompt user (same as API.java)
        JTextField tfTown   = new JTextField();
        JTextField tfState  = new JTextField();
        JTextField tfRadius = new JTextField("10");

        JPanel prompt = new JPanel(new GridLayout(0, 1));
        prompt.add(new JLabel("Town name:"));   prompt.add(tfTown);
        prompt.add(new JLabel("State code:")); prompt.add(tfState);
        prompt.add(new JLabel("Radius (mi):")); prompt.add(tfRadius);

        int choice = JOptionPane.showConfirmDialog(
                null, prompt, "Enter Town, State + Radius", JOptionPane.OK_CANCEL_OPTION);
        if (choice != JOptionPane.OK_OPTION) System.exit(0);

        String townName  = tfTown.getText().trim();
        String stateCode = tfState.getText().trim().toUpperCase();
        int radiusMeters;
        try {
            radiusMeters = (int)(Integer.parseInt(tfRadius.getText().trim()) * 1609.34);
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(null, "Invalid radius.");
            return;
        }

        // 2) Geocode with GeoNames (same pattern as API.java)
        double centerLat, centerLng;
        try {
            String geoUrl = "http://api.geonames.org/searchJSON"
                    + "?q=" + URLEncoder.encode(townName, StandardCharsets.UTF_8)
                    + "&country=US"
                    + "&adminCode1=" + URLEncoder.encode(stateCode, StandardCharsets.UTF_8)
                    + "&maxRows=1&username=bsam6246";

            String resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(geoUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();

            JsonNode entry = new ObjectMapper().readTree(resp)
                    .path("geonames").get(0);
            centerLat = entry.get("lat").asDouble();
            centerLng = entry.get("lng").asDouble();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "Geocoding failed:\n" + ex.getMessage());
            return;
        }

        // 3) Fetch town boundaries from Overpass
        try {
            fetchBoundaries(centerLat, centerLng, radiusMeters);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "Overpass fetch failed:\n" + ex.getMessage());
            return;
        }

        // 4) Build the GUI
        buildUI(centerLat, centerLng, townName, stateCode);
    }

    // ── Overpass fetch ───────────────────────────────────────────────────────

    /**
     * Queries Overpass for admin_level=8 boundaries within radiusMeters of (lat,lng).
     * Populates this.regions.
     */
    private void fetchBoundaries(double lat, double lng, int radiusMeters) throws Exception {
        // admin_level 8 = city/town/village in the US
        String query = "[out:json];"
                + "(relation[\"boundary\"=\"administrative\"][\"admin_level\"=\"8\"]"
                + "(around:" + radiusMeters + "," + lat + "," + lng + "););"
                + "out geom;";

        String encoded = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://overpass-api.de/api/interpreter"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encoded))
                .build();

        String body = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString()).body();

        JsonNode elements = new ObjectMapper().readTree(body).path("elements");

        for (JsonNode el : elements) {
            TownRegion region = new TownRegion();
            region.name = el.path("tags").path("name").asText("Unknown");

            // Each relation has members; we want the outer way geometry
            for (JsonNode member : el.path("members")) {
                if (!"outer".equals(member.path("role").asText())) continue;

                JsonNode geometry = member.path("geometry");
                for (JsonNode pt : geometry) {
                    double pLat = pt.get("lat").asDouble();
                    double pLng = pt.get("lon").asDouble();
                    region.polygon.add(new GeoPosition(pLat, pLng));
                }
                break; // only need the first outer ring for now
            }

            if (region.polygon.size() > 2) {
                regions.add(region);
            }
        }
    }

    // ── GUI builder ──────────────────────────────────────────────────────────

    private void buildUI(double centerLat, double centerLng,
                         String townName, String stateCode) {

        // Set up JXMapViewer
        mapViewer = new JXMapViewer();
        TileFactoryInfo info = new OSMTileFactoryInfo();
        mapViewer.setTileFactory(new DefaultTileFactory(info));
        mapViewer.setAddressLocation(new GeoPosition(centerLat, centerLng));
        mapViewer.setZoom(7); // adjust: lower = more zoomed in

        // Paint town polygons on top of the map
        mapViewer.setOverlayPainter(new CompoundPainter<>(buildPainters()));

        // Click detection: find which region was clicked
        mapViewer.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                GeoPosition clicked = mapViewer.convertPointToGeoPosition(e.getPoint());
                TownRegion hit = findRegionAt(clicked);
                if (hit != null) cycleClaim(hit);
            }
        });

        // Control panel
        JButton statsBtn = new JButton("Display Team Data");
        JButton endBtn   = new JButton("End Game");
        statsBtn.addActionListener(e -> showGameStats());
        endBtn.addActionListener(e -> { showGameStats(); System.exit(0); });

        JPanel controls = new JPanel(new FlowLayout());
        controls.add(statsBtn);
        controls.add(endBtn);

        setTitle("SuburbanScramble MAP – " + townName + ", " + stateCode);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        add(mapViewer, BorderLayout.CENTER);
        add(controls,  BorderLayout.SOUTH);
        setSize(900, 700);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // ── Painters (draw polygons) ─────────────────────────────────────────────

    private List<Painter<JXMapViewer>> buildPainters() {
        List<Painter<JXMapViewer>> painters = new ArrayList<>();

        for (TownRegion region : regions) {
            painters.add((g2, map, w, h) -> {
                // Convert geo polygon to screen polygon
                Path2D path = new Path2D.Double();
                boolean first = true;
                for (GeoPosition gp : region.polygon) {
                    Point2D pt = map.convertGeoPositionToPoint(gp);
                    if (first) { path.moveTo(pt.getX(), pt.getY()); first = false; }
                    else         path.lineTo(pt.getX(), pt.getY());
                }
                path.closePath();

                // Fill color based on claim state
                Color fill = switch (region.claimState) {
                    case 1  -> new Color(0, 255, 0, 80);   // team 1 green
                    case 2  -> new Color(255, 0, 0, 80);   // team 2 red
                    default -> new Color(200, 200, 255, 60); // unclaimed light blue
                };

                g2.setColor(fill);
                g2.fill(path);
                g2.setColor(Color.DARK_GRAY);
                g2.setStroke(new BasicStroke(1.5f));
                g2.draw(path);

                // Draw town name at centroid
                Point2D center = map.convertGeoPositionToPoint(
                        new GeoPosition(
                                region.polygon.stream().mapToDouble(p -> p.getLatitude()).average().orElse(0),
                                region.polygon.stream().mapToDouble(p -> p.getLongitude()).average().orElse(0)
                        ));
                g2.setColor(Color.BLACK);
                g2.setFont(new Font("SansSerif", Font.BOLD, 11));
                g2.drawString(region.name, (float) center.getX() - 20, (float) center.getY());
            });
        }
        return painters;
    }

    // ── Hit testing ──────────────────────────────────────────────────────────

    /**
     * Checks if a clicked GeoPosition falls inside any region's polygon.
     * Uses a simple ray-casting algorithm on screen coordinates.
     */
    private TownRegion findRegionAt(GeoPosition clicked) {
        Point2D clickPt = mapViewer.convertGeoPositionToPoint(clicked);

        for (TownRegion region : regions) {
            Path2D path = new Path2D.Double();
            boolean first = true;
            for (GeoPosition gp : region.polygon) {
                Point2D pt = mapViewer.convertGeoPositionToPoint(gp);
                if (first) { path.moveTo(pt.getX(), pt.getY()); first = false; }
                else         path.lineTo(pt.getX(), pt.getY());
            }
            path.closePath();

            if (path.contains(clickPt)) return region;
        }
        return null;
    }

    // ── Claim logic ──────────────────────────────────────────────────────────

    private void cycleClaim(TownRegion region) {
        region.claimState = (region.claimState + 1) % 3;

        switch (region.claimState) {
            case 1 -> { t2_towns.remove(region.name); t1_towns.put(region.name, region); }
            case 2 -> { t1_towns.remove(region.name); t2_towns.put(region.name, region); }
            case 0 -> { t1_towns.remove(region.name); t2_towns.remove(region.name); }
        }

        mapViewer.repaint(); // redraw polygons with new color
    }

    // ── Stats (mirrors API.java) ─────────────────────────────────────────────

    private void showGameStats() {
        int t1 = t1_towns.size(), t2 = t2_towns.size();
        String winner = t1 > t2 ? "Team 1 wins!" : t2 > t1 ? "Team 2 wins!" : "Tie!";

        String msg = "=== GAME STATS ===\n\n"
                + "Team 1: " + t1 + " towns\n"
                + "Team 2: " + t2 + " towns\n\n"
                + winner;

        JOptionPane.showMessageDialog(this, msg, "Stats", JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    public static void main(String[] args) {
        SwingUtilities.invokeLater(SuburbanScrambleMAP::new);
    }
}