import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.net.http.*;
import java.net.URI;
import java.net.URLEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

/**
 * Prompts for a town name, state code, and radius, fetches nearby towns via GeoNames
 * (filtered by state), then displays each as a 120×120 button showing name and population,
 * sorted by latitude ascending.
 * Teams can claim towns by clicking:
 *   1st click → Team 1 (green),
 *   2nd click → Team 2 (red),
 *   3rd click → unclaimed (white).
 * "Display Team Data" and "End Game" buttons show stats or quit.
 */
public class SuburbanScrambleAPI extends JFrame {
    // DTO for a town
    static class Town {
        double lng;
        double lat;
        String name;
        String state;
        int population;
    }

    // Track claims
    private final Map<String,int[]> t1_towns = new HashMap<>();
    private final Map<String,int[]> t2_towns = new HashMap<>();
    private final List<TownButton> townButtons = new ArrayList<>();
    private boolean showPopulations = true;


    public SuburbanScrambleAPI() {
        // 1) Prompt for Town, State, and Radius
        JTextField tfTown   = new JTextField();
        JTextField tfState  = new JTextField();
        JTextField tfRadius = new JTextField("5");
        JPanel prompt = new JPanel(new GridLayout(0,1));
        prompt.add(new JLabel("Town name:"));
        prompt.add(tfTown);
        prompt.add(new JLabel("State code (e.g. IL):"));
        prompt.add(tfState);
        prompt.add(new JLabel("Radius (mi):"));
        prompt.add(tfRadius);

        int choice = JOptionPane.showConfirmDialog(
                null, prompt, "Enter Town, State + Radius", JOptionPane.OK_CANCEL_OPTION
        );
        if (choice != JOptionPane.OK_OPTION) System.exit(0);

        String townName  = tfTown.getText().trim();
        String stateCode = tfState.getText().trim().toUpperCase();
        int radiusKm;
        try {
            radiusKm = (int)(Integer.parseInt(tfRadius.getText().trim()) * 1.60934);
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(null,
                    "Invalid radius", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 2) Geocode to get lat/lng (filtered by state)
        double lat, lng;
        try {
            String query = URLEncoder.encode(townName, StandardCharsets.UTF_8);
            String geoUrl = "http://api.geonames.org/searchJSON"
                    + "?q=" + query
                    + "&country=US"
                    + "&adminCode1=" + URLEncoder.encode(stateCode, StandardCharsets.UTF_8)
                    + "&maxRows=10"
                    + "&username=bsam6246"; // replace

            HttpClient client = HttpClient.newHttpClient();
            String resp = client.send(
                    HttpRequest.newBuilder(URI.create(geoUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            ).body();

            JsonNode arr = new ObjectMapper().readTree(resp).path("geonames");
            if (!arr.isArray() || arr.isEmpty()) {
                JOptionPane.showMessageDialog(null,
                        "Town not found in " + stateCode,
                        "Error", JOptionPane.ERROR_MESSAGE);
                System.exit(0);
            }
            JsonNode entry = arr.get(0);
            lat = entry.get("lat").asDouble();
            lng = entry.get("lng").asDouble();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null,
                    "Geocoding failed:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }

        // 3) Fetch nearby towns filtered by state into a HashMap
        Map<String,int[]> towns = new HashMap<>();
        try {
            int max_rows = 50;  // increase as needed
            String nearbyUrl = String.format(
                    "http://api.geonames.org/findNearbyPlaceNameJSON"
                            + "?lat=%f&lng=%f&radius=%d&cities=cities1000&maxRows=%d"
                            + "&country=US&adminCode1=%s&username=bsam6246",
                    lat, lng, radiusKm, max_rows,
                    URLEncoder.encode(stateCode, StandardCharsets.UTF_8)
            );
            HttpClient client = HttpClient.newHttpClient();
            String resp = client.send(
                    HttpRequest.newBuilder(URI.create(nearbyUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            ).body();

            JsonNode arr = new ObjectMapper().readTree(resp).path("geonames");
            for (JsonNode node : arr) {
                String name = node.get("name").asText();
                String state = node.get("adminCode1").asText();
                int pop = node.get("population").asInt(0);
                double lonD = node.get("lng").asDouble();
                double latD = node.get("lat").asDouble();
                // scale coords to int
                int lon = (int)(lonD * 1e5);
                int latI = (int)(latD * 1e5);
                towns.put(name + "," + state, new int[]{pop, lon, latI});
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null,
                    "Nearby fetch failed:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }

        // 4) We already have entries sorted by latitude ascending:
        List<Map.Entry<String,int[]>> entries = new ArrayList<>(towns.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<String,int[]> e) -> e.getValue()[2])
                .reversed());

        // 5) Build GUI with a 4‑column grid, sorting each row by longitude
        setTitle("Nearby Towns around " + townName + ", " + stateCode);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));

        int cols = (int) Math.sqrt(entries.size());
        int cols_rem = entries.size()%(cols*cols);
        if(cols_rem > (cols/2)){
            cols+=1;
        }
        int rows = (entries.size() + cols - 1) / cols;
        JPanel btnPanel = new JPanel(new GridLayout(rows, cols, 5, 5));
        //JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        for (int r = 0; r < rows; r++) {
            int start = r * cols;
            int end   = Math.min(start + cols, entries.size());
            // slice out this row
            List<Map.Entry<String,int[]>> rowEntries =
                    new ArrayList<>(entries.subList(start, end));
            // now sort this row by longitude (value[1])
            rowEntries.sort(Comparator.comparingInt(e -> e.getValue()[1]));
            // add each button in longitude order
            for (Map.Entry<String,int[]> e : rowEntries) {
                String key = e.getKey();
                int pop    = e.getValue()[0];
                TownButton btn = new TownButton(key, pop);
                btn.setPreferredSize(new Dimension(Math.max(100, Math.min(pop / 1000, 300)),
                        Math.max(100, Math.min(pop / 1000, 300))));
                btn.setMaximumSize(new Dimension(300, 300));
                btn.setMinimumSize(new Dimension(100, 100));
                btnPanel.add(btn);
                townButtons.add(btn); // ✅ Track it
            }
            container.add(btnPanel);
        }

        add(new JScrollPane(container), BorderLayout.CENTER);


        // Control panel
        JPanel control = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        JButton infoBtn = new JButton("Display Team Data");
        JButton endBtn  = new JButton("End Game");
        infoBtn.addActionListener(e -> showGameStats());
        endBtn.addActionListener(e -> { showGameStats(); System.exit(0); });
        control.add(infoBtn);
        control.add(endBtn);
        add(control, BorderLayout.SOUTH);

        setSize(800, 600);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // Display team statistics
    private void showGameStats() {
        int[] s1 = calcStats(t1_towns), s2 = calcStats(t2_towns);
        int pts1 = s1[0] + (s1[1] > s2[1] ? 1 : 0);
        int pts2 = s2[0] + (s2[1] > s1[1] ? 1 : 0);
        StringBuilder sb = new StringBuilder("=== GAME STATS ===\n\n");
        sb.append(formatStats("Team 1", s1, s1[1] > s2[1])).append("\n");
        sb.append(formatStats("Team 2", s2, s2[1] > s1[1])).append("\n\n");
        if (pts1 > pts2) sb.append("Team 1 wins: ").append(pts1);
        else if (pts2 > pts1) sb.append("Team 2 wins: ").append(pts2);
        else             sb.append("Tie: ").append(pts1).append(" each");
        JOptionPane.showMessageDialog(this, sb.toString(), "Stats", JOptionPane.INFORMATION_MESSAGE);
    }

    private int[] calcStats(Map<String, int[]> team) {
        int pop = 0;
        for (int[] d : team.values()) pop += d[0];
        return new int[]{team.size(), pop};
    }

    private String formatStats(String name, int[] s, boolean popBonus) {
        StringBuilder sb = new StringBuilder(name + ":\n");
        sb.append("  Towns: ").append(s[0]).append("\n");
        sb.append("  Pop: ").append(s[1]).append("\n");
        sb.append("  Bonus: ").append(popBonus ? "Population" : "None");
        return sb.toString();
    }

    // Button with 3-click claim cycle
    private class TownButton extends JButton {
        int clicks = 0;
        final String key;
        final int[] data;

        TownButton(String key, int pop) {
            super(""); // We'll set the label dynamically
            this.key = key;
            this.data = new int[]{pop};
            setOpaque(true);
            setBackground(Color.WHITE);
            updateLabel(); // default

            addActionListener(e -> {
                clicks++;
                int st = clicks % 3;
                if (st == 1) {
                    setBackground(Color.GREEN);
                    t2_towns.remove(key);
                    t1_towns.put(key, data);
                } else if (st == 2) {
                    setBackground(Color.RED);
                    t1_towns.remove(key);
                    t2_towns.put(key, data);
                } else {
                    setBackground(Color.WHITE);
                    t1_towns.remove(key);
                    t2_towns.remove(key);
                }
            });
        }


        void updateLabel() {
            if (showPopulations) {
                setText("<html><center>" + key.replace(",", ", ") + "<br/>Pop: " + data[0] + "</center></html>");
            } else {
                setText("<html><center>" + key.replace(",", ", ") + "</center></html>");
            }
        }
    }

        public static void main(String[] args) {
        SwingUtilities.invokeLater(SuburbanScrambleAPI::new);
    }
}
