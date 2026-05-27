import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.OSMTileFactoryInfo;
import org.jxmapviewer.viewer.DefaultTileFactory;
import org.jxmapviewer.viewer.GeoPosition;
import org.jxmapviewer.viewer.TileFactoryInfo;
import org.jxmapviewer.input.PanMouseInputListener;
import org.jxmapviewer.painter.CompoundPainter;
import org.jxmapviewer.painter.Painter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.*;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class UrbEx extends JFrame {

    // ── Data model ──────────────────────────────────────────────────────────

    static class TownRegion {
        String name;
        List<List<GeoPosition>> rings = new ArrayList<>();
        int claimState = 0; // 0 = unclaimed, 1 = team1, 2 = team2
        int population;
    }

    static class TransitLine {
        Color color;
        List<List<GeoPosition>> segments = new ArrayList<>();
    }

    private double zoomAccumulator = 0;
    private final Map<String, TownRegion> t1_towns = new HashMap<>();
    private final Map<String, TownRegion> t2_towns = new HashMap<>();
    private final List<TownRegion> regions = new ArrayList<>();
    private final List<TransitLine> transitLines = new ArrayList<>();
    private final List<TransitLine> busLines = new ArrayList<>();
    private final List<GeoPosition> trainStations = new ArrayList<>();
    private final List<GeoPosition> busStops = new ArrayList<>();
    private boolean showBusLines = true;
    private boolean showTrainStations = true;
    private boolean showBusStops = true;
    private JXMapViewer mapViewer;

    private TownRegion hoveredRegion = null;
    private javax.swing.Timer hoverTimer = null;
    private JWindow hoverPopup = null;
    private Point lastMouseScreenPos;

    // ── Constructor ─────────────────────────────────────────────────────────

    public UrbEx() {
        // Prompt for which city GeoJSON to load
        JTextField tfCity = new JTextField("chicago");
        JPanel prompt = new JPanel(new GridLayout(0, 1));
        prompt.add(new JLabel("City (matches <city>.geojson file):"));
        prompt.add(tfCity);

        int choice = JOptionPane.showConfirmDialog(
                null, prompt, "Select City", JOptionPane.OK_CANCEL_OPTION);
        if (choice != JOptionPane.OK_OPTION) System.exit(0);

        String cityName = tfCity.getText().trim().toLowerCase();

        try {
            loadFromGeoJson("data/" + cityName + ".geojson");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "Failed to load GeoJSON:\n" + ex.getMessage());
            return;
        }

        if (regions.isEmpty()) {
            JOptionPane.showMessageDialog(null, "No regions found in file.");
            return;
        }

        // Compute map center from all loaded regions
        double centerLat = regions.stream()
                .flatMap(r -> r.rings.stream())
                .flatMap(List::stream)
                .mapToDouble(GeoPosition::getLatitude)
                .average().orElse(41.8827);
        double centerLng = regions.stream()
                .flatMap(r -> r.rings.stream())
                .flatMap(List::stream)
                .mapToDouble(GeoPosition::getLongitude)
                .average().orElse(-87.6233);

        try { loadBusLines("data/" + cityName + "_buses.geojson"); } catch (Exception ignored) {}
        try { loadStations("data/" + cityName + "_stations.geojson"); } catch (Exception ignored) {}
        try { loadBusStops("data/" + cityName + "_bus_stops.geojson"); } catch (Exception ignored) {}

        buildUI(centerLat, centerLng, cityName);
    }

    // ── GeoJSON loader ───────────────────────────────────────────────────────

    private void loadFromGeoJson(String filePath) throws Exception {
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)));
        JsonNode root = new ObjectMapper().readTree(content);

        Set<String> seenTransitRefs = new HashSet<>();

        for (JsonNode feature : root.path("features")) {
            JsonNode props = feature.path("properties");
            JsonNode geometry = feature.path("geometry");
            String geomType = geometry.path("type").asText();

            if ("administrative".equals(props.path("boundary").asText())) {
                TownRegion region = new TownRegion();
                region.name = props.path("name").asText("Unknown");
                region.population = props.path("population").asInt(0);

                if ("Polygon".equals(geomType)) {
                    for (JsonNode ring : geometry.path("coordinates")) {
                        List<GeoPosition> positions = new ArrayList<>();
                        for (JsonNode pt : ring) {
                            positions.add(new GeoPosition(pt.get(1).asDouble(), pt.get(0).asDouble()));
                        }
                        if (!positions.isEmpty()) region.rings.add(positions);
                    }
                } else if ("MultiPolygon".equals(geomType)) {
                    for (JsonNode polygon : geometry.path("coordinates")) {
                        for (JsonNode ring : polygon) {
                            List<GeoPosition> positions = new ArrayList<>();
                            for (JsonNode pt : ring) {
                                positions.add(new GeoPosition(pt.get(1).asDouble(), pt.get(0).asDouble()));
                            }
                            if (!positions.isEmpty()) region.rings.add(positions);
                        }
                    }
                }

                if (!region.rings.isEmpty()) regions.add(region);

            } else if ("subway".equals(props.path("route").asText())) {
                String ref = props.path("ref").asText();
                if (!seenTransitRefs.add(ref)) continue; // deduplicate by line ref

                String colourHex = props.path("colour").asText("#888888");
                TransitLine line = new TransitLine();
                try { line.color = Color.decode(colourHex); }
                catch (NumberFormatException e) { line.color = Color.GRAY; }

                if ("MultiLineString".equals(geomType)) {
                    for (JsonNode segment : geometry.path("coordinates")) {
                        List<GeoPosition> positions = new ArrayList<>();
                        for (JsonNode pt : segment) {
                            positions.add(new GeoPosition(pt.get(1).asDouble(), pt.get(0).asDouble()));
                        }
                        if (!positions.isEmpty()) line.segments.add(positions);
                    }
                } else if ("LineString".equals(geomType)) {
                    List<GeoPosition> positions = new ArrayList<>();
                    for (JsonNode pt : geometry.path("coordinates")) {
                        positions.add(new GeoPosition(pt.get(1).asDouble(), pt.get(0).asDouble()));
                    }
                    if (!positions.isEmpty()) line.segments.add(positions);
                }

                if (!line.segments.isEmpty()) transitLines.add(line);
            }
        }
    }

    private void loadBusLines(String filePath) throws Exception {
        if (!new java.io.File(filePath).exists()) return;
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)));
        JsonNode root = new ObjectMapper().readTree(content);
        for (JsonNode feature : root.path("features")) {
            JsonNode props = feature.path("properties");
            if (!"bus".equals(props.path("route").asText())) continue;
            JsonNode geometry = feature.path("geometry");
            String geomType = geometry.path("type").asText();
            TransitLine line = new TransitLine();
            try { line.color = Color.decode(props.path("colour").asText("#1e90ff")); }
            catch (NumberFormatException e) { line.color = new Color(30, 144, 255); }
            if ("MultiLineString".equals(geomType)) {
                for (JsonNode seg : geometry.path("coordinates")) {
                    List<GeoPosition> pts = new ArrayList<>();
                    for (JsonNode pt : seg) pts.add(new GeoPosition(pt.get(1).asDouble(), pt.get(0).asDouble()));
                    if (!pts.isEmpty()) line.segments.add(pts);
                }
            } else if ("LineString".equals(geomType)) {
                List<GeoPosition> pts = new ArrayList<>();
                for (JsonNode pt : geometry.path("coordinates")) pts.add(new GeoPosition(pt.get(1).asDouble(), pt.get(0).asDouble()));
                if (!pts.isEmpty()) line.segments.add(pts);
            }
            if (!line.segments.isEmpty()) busLines.add(line);
        }
    }

    private void loadStations(String filePath) throws Exception {
        if (!new java.io.File(filePath).exists()) return;
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)));
        JsonNode root = new ObjectMapper().readTree(content);
        for (JsonNode feature : root.path("features")) {
            JsonNode coords = feature.path("geometry").path("coordinates");
            if (coords.size() >= 2)
                trainStations.add(new GeoPosition(coords.get(1).asDouble(), coords.get(0).asDouble()));
        }
    }

    private void loadBusStops(String filePath) throws Exception {
        if (!new java.io.File(filePath).exists()) return;
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)));
        JsonNode root = new ObjectMapper().readTree(content);
        for (JsonNode feature : root.path("features")) {
            JsonNode coords = feature.path("geometry").path("coordinates");
            if (coords.size() >= 2)
                busStops.add(new GeoPosition(coords.get(1).asDouble(), coords.get(0).asDouble()));
        }
    }

    // ── GUI builder ──────────────────────────────────────────────────────────

    private void buildUI(double centerLat, double centerLng, String cityName) {
        mapViewer = new JXMapViewer();
        TileFactoryInfo info = new OSMTileFactoryInfo("CartoDB", "https://a.basemaps.cartocdn.com/light_all");
        DefaultTileFactory tileFactory = new DefaultTileFactory(info);
        tileFactory.setThreadPoolSize(4);
        mapViewer.setTileFactory(tileFactory);
        mapViewer.setAddressLocation(new GeoPosition(centerLat, centerLng));
        mapViewer.setZoom(7);

        mapViewer.setOverlayPainter(new CompoundPainter<>(buildPainters()));

        PanMouseInputListener panListener = new PanMouseInputListener(mapViewer);
        mapViewer.addMouseListener(panListener);
        mapViewer.addMouseMotionListener(panListener);
        mapViewer.addMouseWheelListener(e -> {
            zoomAccumulator += e.getPreciseWheelRotation();
            if (Math.abs(zoomAccumulator) >= 5) {
                int steps = (int) (zoomAccumulator / 5);
                mapViewer.setZoom(mapViewer.getZoom() + steps);
                zoomAccumulator -= steps * 5;
            }
        });

        mapViewer.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                TownRegion hit = findRegionAt(mapViewer.convertPointToGeoPosition(e.getPoint()));
                if (hit != null) cycleClaim(hit);
            }
        });

        MouseAdapter hoverAdapter = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                TownRegion region = findRegionAt(mapViewer.convertPointToGeoPosition(e.getPoint()));
                if (region != hoveredRegion) {
                    hoveredRegion = region;
                    if (hoverTimer != null) hoverTimer.stop();
                    hidePopup();
                    if (region != null) {
                        lastMouseScreenPos = e.getLocationOnScreen();
                        hoverTimer = new javax.swing.Timer(1000, ev -> showHoverPopup(region, lastMouseScreenPos));
                        hoverTimer.setRepeats(false);
                        hoverTimer.start();
                    }
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoveredRegion = null;
                if (hoverTimer != null) hoverTimer.stop();
                hidePopup();
            }
        };
        mapViewer.addMouseListener(hoverAdapter);
        mapViewer.addMouseMotionListener(hoverAdapter);

        JButton statsBtn = new JButton("Display Team Data");
        JButton endBtn   = new JButton("End Game");
        statsBtn.addActionListener(e -> showGameStats());
        endBtn.addActionListener(e -> { showGameStats(); System.exit(0); });

        JToggleButton busToggle     = new JToggleButton("Bus Lines",      true);
        JToggleButton stationToggle = new JToggleButton("Train Stations", true);
        JToggleButton busStopToggle = new JToggleButton("Bus Stops",      true);
        busToggle.addActionListener(e     -> { showBusLines      = busToggle.isSelected();     mapViewer.repaint(); });
        stationToggle.addActionListener(e -> { showTrainStations = stationToggle.isSelected(); mapViewer.repaint(); });
        busStopToggle.addActionListener(e -> { showBusStops      = busStopToggle.isSelected(); mapViewer.repaint(); });

        JPanel controls = new JPanel(new FlowLayout());
        controls.add(statsBtn);
        controls.add(endBtn);
        controls.add(busToggle);
        controls.add(stationToggle);
        controls.add(busStopToggle);

        setTitle("UrbEx – " + cityName);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        add(mapViewer, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);
        setSize(900, 700);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // ── Painters ─────────────────────────────────────────────────────────────

    private List<Painter<JXMapViewer>> buildPainters() {
        List<Painter<JXMapViewer>> painters = new ArrayList<>();

        for (TownRegion region : regions) {
            painters.add((g2, map, w, h) -> {
                Path2D path = new Path2D.Double(Path2D.WIND_NON_ZERO);
                for (List<GeoPosition> ring : region.rings) {
                    boolean first = true;
                    for (GeoPosition gp : ring) {
                        Point2D pt = map.convertGeoPositionToPoint(gp);
                        if (first) { path.moveTo(pt.getX(), pt.getY()); first = false; }
                        else path.lineTo(pt.getX(), pt.getY());
                    }
                    path.closePath();
                }

                Color fill = switch (region.claimState) {
                    case 1  -> new Color(0, 255, 0, 80);
                    case 2  -> new Color(255, 0, 0, 80);
                    default -> new Color(200, 200, 255, 60);
                };

                g2.setColor(fill);
                g2.fill(path);
                g2.setColor(Color.DARK_GRAY);
                g2.setStroke(new BasicStroke(1.5f));
                g2.draw(path);

                // Label at centroid
                /* double avLat = 0, avLon = 0;
                int count = 0;
                for (List<GeoPosition> ring : region.rings) {
                    avLat += ring.stream().mapToDouble(GeoPosition::getLatitude).average().orElse(0);
                    avLon += ring.stream().mapToDouble(GeoPosition::getLongitude).average().orElse(0);
                    count++;
                }
                Point2D center = map.convertGeoPositionToPoint(new GeoPosition(avLat / count, avLon / count));
                g2.setColor(Color.BLACK);
                g2.setFont(new Font("SansSerif", Font.BOLD, 11));
                g2.drawString(region.name, (float) center.getX() - 20, (float) center.getY());
                */
            });
        }

        for (TransitLine line : transitLines) {
            painters.add((g2, map, w, h) -> {
                g2.setColor(line.color);
                g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (List<GeoPosition> segment : line.segments) {
                    Path2D path = new Path2D.Double();
                    boolean first = true;
                    for (GeoPosition gp : segment) {
                        Point2D pt = map.convertGeoPositionToPoint(gp);
                        if (first) { path.moveTo(pt.getX(), pt.getY()); first = false; }
                        else path.lineTo(pt.getX(), pt.getY());
                    }
                    g2.draw(path);
                }
            });
        }

        painters.add((g2, map, w, h) -> {
            if (!showBusLines) return;
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (TransitLine line : busLines) {
                g2.setColor(new Color(line.color.getRed(), line.color.getGreen(), line.color.getBlue(), 160));
                for (List<GeoPosition> segment : line.segments) {
                    Path2D path = new Path2D.Double();
                    boolean first = true;
                    for (GeoPosition gp : segment) {
                        Point2D pt = map.convertGeoPositionToPoint(gp);
                        if (first) { path.moveTo(pt.getX(), pt.getY()); first = false; }
                        else path.lineTo(pt.getX(), pt.getY());
                    }
                    g2.draw(path);
                }
            }
        });

        painters.add((g2, map, w, h) -> {
            if (!showTrainStations) return;
            for (GeoPosition pos : trainStations) {
                Point2D pt = map.convertGeoPositionToPoint(pos);
                int x = (int) pt.getX(), y = (int) pt.getY();
                g2.setColor(new Color(40, 40, 40));
                g2.fillOval(x - 5, y - 5, 10, 10);
                g2.setColor(Color.WHITE);
                g2.fillOval(x - 3, y - 3, 6, 6);
            }
        });

        painters.add((g2, map, w, h) -> {
            if (!showBusStops) return;
            g2.setColor(new Color(30, 144, 255, 180));
            for (GeoPosition pos : busStops) {
                Point2D pt = map.convertGeoPositionToPoint(pos);
                int x = (int) pt.getX(), y = (int) pt.getY();
                g2.fillOval(x - 2, y - 2, 4, 4);
            }
        });

        return painters;
    }

    // ── Hit testing ──────────────────────────────────────────────────────────

    private TownRegion findRegionAt(GeoPosition clicked) {
        Point2D clickPt = mapViewer.convertGeoPositionToPoint(clicked);

        for (TownRegion region : regions) {
            Path2D path = new Path2D.Double(Path2D.WIND_NON_ZERO);
            for (List<GeoPosition> ring : region.rings) {
                boolean first = true;
                for (GeoPosition gp : ring) {
                    Point2D pt = mapViewer.convertGeoPositionToPoint(gp);
                    if (first) { path.moveTo(pt.getX(), pt.getY()); first = false; }
                    else path.lineTo(pt.getX(), pt.getY());
                }
                path.closePath();
            }
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
        mapViewer.repaint();
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private int getPop(Map<String, TownRegion> towns) {
        return towns.values().stream().mapToInt(r -> r.population).sum();
    }

    private void showGameStats() {
        int t1 = t1_towns.size(), t2 = t2_towns.size();
        int t1pop = getPop(t1_towns), t2pop = getPop(t2_towns);
        int t1score = t1 + (t1pop > t2pop ? 1 : 0);
        int t2score = t2 + (t2pop > t1pop ? 1 : 0);
        String winner = t1score > t2score ? "Team 1 wins!" : t2score > t1score ? "Team 2 wins!" : "Tie!";

        JOptionPane.showMessageDialog(this,
                "=== GAME STATS ===\n\n"
                + "Team 1: " + t1 + " neighborhoods, " + t1pop + " people\n"
                + "Team 2: " + t2 + " neighborhoods, " + t2pop + " people\n\n"
                + winner,
                "Stats", JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Hover popup ───────────────────────────────────────────────────────────

    private void showHoverPopup(TownRegion region, Point screenPos) {
        hidePopup();
        hoverPopup = new JWindow(this);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(new Color(255, 255, 240));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        Font bold  = new Font("SansSerif", Font.BOLD, 12);
        Font plain = new Font("SansSerif", Font.PLAIN, 11);

        JLabel nameLabel = new JLabel(region.name);
        nameLabel.setFont(bold);
        JLabel popLabel  = new JLabel("Population: " + region.population);
        popLabel.setFont(plain);
        JLabel areaLabel = new JLabel(String.format("Area: %.2f sq mi", computeAreaSqMiles(region)));
        areaLabel.setFont(plain);

        panel.add(nameLabel);
        panel.add(popLabel);
        panel.add(areaLabel);

        hoverPopup.add(panel);
        hoverPopup.pack();
        hoverPopup.setLocation(screenPos.x + 15, screenPos.y + 15);
        hoverPopup.setVisible(true);
    }

    private void hidePopup() {
        if (hoverPopup != null) {
            hoverPopup.dispose();
            hoverPopup = null;
        }
    }

    private double computeAreaSqMiles(TownRegion region) {
        double totalAreaDeg2 = 0;
        for (List<GeoPosition> ring : region.rings) {
            double area = 0;
            int n = ring.size();
            for (int i = 0; i < n; i++) {
                GeoPosition a = ring.get(i);
                GeoPosition b = ring.get((i + 1) % n);
                area += a.getLongitude() * b.getLatitude() - b.getLongitude() * a.getLatitude();
            }
            totalAreaDeg2 += Math.abs(area) / 2.0;
        }
        double avgLat = region.rings.stream()
                .flatMap(List::stream)
                .mapToDouble(GeoPosition::getLatitude)
                .average().orElse(0);
        double areaSqKm = totalAreaDeg2 * 111.32 * 111.32 * Math.cos(Math.toRadians(avgLat));
        return areaSqKm * 0.386102;
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    public static void main(String[] args) {
        SwingUtilities.invokeLater(UrbEx::new);
    }
}