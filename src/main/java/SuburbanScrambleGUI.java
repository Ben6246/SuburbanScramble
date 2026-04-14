import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import javax.swing.JFrame;


public class SuburbanScrambleGUI extends JFrame {

    private static final int POP = 0;
    private static final int AREA = 1;

    // Holds all towns and their [population, area]
    private HashMap<String, int[]> towns = new HashMap<>();

    // These track which towns are claimed by each team
    private HashMap<String, int[]> t1_towns = new HashMap<>();
    private HashMap<String, int[]> t2_towns = new HashMap<>();

    public SuburbanScrambleGUI() {
        // Initialize data
        initTowns();

        // Basic frame settings
        setTitle("Suburban Scramble");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 700);
        setLayout(null);  // we'll do absolute positioning for demonstration

        // Create a panel (or multiple sub-panels) to represent your "map"
        JPanel mapPanel = new JPanel();
        mapPanel.setLayout(null);  // absolute positioning within mapPanel
        mapPanel.setBounds(0, 0, 800, 600);
        mapPanel.setBackground(Color.WHITE);

        // Create "town buttons" that roughly approximate your layout
        // Adjust these coordinates and sizes to match your diagram
        addTownButton(mapPanel, "Naperville",      0,   360, 240, 240);
        addTownButton(mapPanel, "Bolingbrook",     240, 480, 240, 120);
        addTownButton(mapPanel, "Lisle",           240, 240, 120, 120);
        addTownButton(mapPanel, "Downers Grove",   360, 240, 120, 240);
        addTownButton(mapPanel, "Woodridge",       240, 360, 120, 120);
        addTownButton(mapPanel, "Darien",          480, 480, 120, 120);
        addTownButton(mapPanel, "Willowbrook",     600, 480, 120, 120);
        addTownButton(mapPanel, "Westmont",        480, 360, 120, 120);
        addTownButton(mapPanel, "Clarendon Hills", 600, 360, 120, 120);
        addTownButton(mapPanel, "Hinsdale",        600, 240, 120, 120);
        addTownButton(mapPanel, "Oak Brook",       480, 240, 120, 120);
        addTownButton(mapPanel, "Lombard",         360, 120, 120, 120);
        addTownButton(mapPanel, "Villa Park",      480, 120, 120, 120);
        addTownButton(mapPanel, "Glen Ellyn",      240, 120, 120, 120);
        addTownButton(mapPanel, "Elmhurst",        480, 0,   120, 120);
        addTownButton(mapPanel, "Wheaton",         120, 120, 120, 240);



        // Add map panel to the frame
        add(mapPanel);

        // Create a control panel with two buttons: "End Game" and "Display Data"
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new FlowLayout());
        controlPanel.setBounds(0, 600, 800, 70);
        JButton endGameBtn = new JButton("End Game");
        JButton displayDataBtn = new JButton("Display Team Data");

        // End Game button
        endGameBtn.addActionListener(e -> {
            // Show final data, then exit
            showGameStats();
            System.exit(0);
        });

        // Display Team Data button
        displayDataBtn.addActionListener(e -> {
            showGameStats();
        });

        controlPanel.add(displayDataBtn);
        controlPanel.add(endGameBtn);
        add(controlPanel);

        // Show the GUI
        setVisible(true);
    }

    /**
     * Creates a TownButton for the specified town name, places it on the mapPanel
     * at the given coordinates and size.
     */
    private void addTownButton(JPanel mapPanel, String townName,
                               int x, int y, int width, int height) {
        TownButton btn = new TownButton(townName);
        btn.setBounds(x, y, width, height);
        mapPanel.add(btn);
    }

    /**
     * Populates the towns map with [population, area] data.
     */
    private void initTowns() {
        towns.put("Downers Grove",     new int[]{50437, 149});
        towns.put("Westmont",          new int[]{24685,  48});
        towns.put("Lisle",             new int[]{23464,  78});
        towns.put("Woodridge",         new int[]{34158,  96});
        towns.put("Clarendon Hills",   new int[]{ 8702,  17});
        towns.put("Hinsdale",          new int[]{17395,  47});
        towns.put("Oak Brook",         new int[]{ 8163,  82});
        towns.put("Lombard",           new int[]{44476, 109});
        towns.put("Villa Park",        new int[]{21113,  48});
        towns.put("Darien",            new int[]{21916,  60});
        towns.put("Bolingbrook",       new int[]{73922, 243});
        towns.put("Glen Ellyn",        new int[]{28846,  65});
        towns.put("Elmhurst",          new int[]{46748, 103});
        towns.put("Addison",           new int[]{35702, 100});
        towns.put("Willowbrook",       new int[]{ 8500,  35});
        towns.put("Wheaton",           new int[]{53970, 115});
        towns.put("Naperville",        new int[]{149540,391});
    }

    /**
     * Displays team stats in a popup.
     */
    private void showGameStats() {
        // Compute stats
        int[] t1Stats = calculateTeamStats(t1_towns);
        int[] t2Stats = calculateTeamStats(t2_towns);

        // Determine points for each team
        int t1Points = t1Stats[0]; // 1 point per claimed town
        int t2Points = t2Stats[0];

        // Population bonus
        boolean t1PopBonus = t1Stats[1] > t2Stats[1];
        boolean t2PopBonus = t2Stats[1] > t1Stats[1];
        if (t1PopBonus) t1Points++;
        if (t2PopBonus) t2Points++;

        // Area bonus
        boolean t1AreaBonus = t1Stats[2] > t2Stats[2];
        boolean t2AreaBonus = t2Stats[2] > t1Stats[2];
        if (t1AreaBonus) t1Points++;
        if (t2AreaBonus) t2Points++;

        // Build a detailed string of stats for each team
        StringBuilder sb = new StringBuilder();
        sb.append("=== GAME STATS ===\n\n");

        sb.append(printTeamStats("Team 1", t1Stats, t1PopBonus, t1AreaBonus)).append("\n");
        sb.append(printTeamStats("Team 2", t2Stats, t2PopBonus, t2AreaBonus)).append("\n");

        // Determine winner
        if (t1Points > t2Points) {
            sb.append("\nTeam 1 is winning with ").append(t1Points).append(" points!\n");
        } else if (t2Points > t1Points) {
            sb.append("\nTeam 2 is winning with ").append(t2Points).append(" points!\n");
        } else {
            sb.append("\nThe game is tied with ").append(t1Points).append(" points each!\n");
        }

        // Show in a dialog
        JOptionPane.showMessageDialog(this, sb.toString(),
                "Team Stats", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Helper: returns [#towns, total population, total area] for a given team's map
     */
    private int[] calculateTeamStats(HashMap<String, int[]> teamTowns) {
        int totalPop = 0;
        int totalArea = 0;
        for (int[] data : teamTowns.values()) {
            totalPop += data[POP];
            totalArea += data[AREA];
        }
        return new int[]{teamTowns.size(), totalPop, totalArea};
    }

    /**
     * Returns a nicely formatted summary string for a single team's stats.
     */
    private String printTeamStats(String teamName, int[] stats,
                                  boolean popBonus, boolean areaBonus) {
        StringBuilder sb = new StringBuilder();
        sb.append(teamName).append(":\n");
        sb.append("  Towns claimed: ").append(stats[0]).append("\n");
        sb.append("  Total population: ").append(stats[1]).append("\n");

        // Area formatting: if area is 3 digits, e.g. 149, we display it as "14.9"
        // This matches your substring approach from the console code:
        String totAreaStr = String.valueOf(stats[2]);
        if (totAreaStr.length() <= 1) {
            sb.append("  Total area: ").append(totAreaStr).append(" sq mi\n");
        } else {
            // Insert a decimal before the last digit
            String areaFormatted = totAreaStr.substring(0, totAreaStr.length() - 1)
                    + "." + totAreaStr.substring(totAreaStr.length() - 1);
            sb.append("  Total area: ").append(areaFormatted).append(" sq mi\n");
        }

        sb.append("  Bonuses: ");
        if (!popBonus && !areaBonus) {
            sb.append("None");
        } else {
            if (popBonus) sb.append("Population ");
            if (areaBonus) sb.append("Area");
        }
        sb.append("\n");

        return sb.toString();
    }

    /**
     * A custom JButton that remembers how many times it's been clicked
     * so that we can switch ownership from Team 1 -> Team 2, etc.
     */
        private class TownButton extends JButton {
            private int clickCount = 0;
            private String townName;

            public TownButton(String townName) {
                super(townName);
                this.townName = townName;
                if ("Downers Grove".equals(townName)) {
                    setText("<html>Downers<br>Grove</html>");
                } else

                {
                    setText(townName);
                }
                setOpaque(true); // Ensure that background colors show correctly
                setBackground(Color.WHITE);

                // Clicking logic using a modulo approach for a 3-click cycle
                addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        clickCount++;
                        int state = clickCount % 3; // 1: Team 1, 2: Team 2, 0: reset/unclaimed

                        if (state == 1) {
                            // First click: Claim for Team 1 (green)
                            setBackground(Color.GREEN);
                            t1_claim(townName);
                        } else if (state == 2) {
                            // Second click: Claim for Team 2 (red)
                            setBackground(Color.RED);
                            t2_claim(townName);
                        } else { // state == 0, third click resets the claim
                            setBackground(Color.WHITE);
                            resetClaim(townName);
                        }
                    }
                });
            }

            // Remove the town from any team's claim
            private void resetClaim(String townName) {
                if (t1_towns.containsKey(townName) || t2_towns.containsKey(townName)) {
                    t1_towns.remove(townName);
                    t2_towns.remove(townName);
                    System.out.println("Reset claim for: " + townName);
                }
            }
        }

        /**
     * Logic to claim a town for Team 1, replicates your console method.
     */
    private void t1_claim(String name) {
        if (t2_towns.containsKey(name)) {
            // If Team 2 had it, remove from Team 2
            t2_towns.remove(name);
        }
        if (t1_towns.containsKey(name)) {
            System.out.println("Already claimed by Team 1 (click #1).");
        } else if (towns.containsKey(name)) {
            t1_towns.put(name, towns.get(name));
            System.out.println("Team 1 has claimed: " + name);
        } else {
            System.out.println("Town name not recognized. (shouldn't happen if button is valid)");
        }
    }

    /**
     * Logic to claim a town for Team 2, replicates your console method.
     */
    private void t2_claim(String name) {
        if (t1_towns.containsKey(name)) {
            // If Team 1 had it, remove from Team 1
            t1_towns.remove(name);
        }
        if (t2_towns.containsKey(name)) {
            System.out.println("Already claimed by Team 2 (click #2).");
        } else if (towns.containsKey(name)) {
            t2_towns.put(name, towns.get(name));
            System.out.println("Team 2 has claimed: " + name);
        } else {
            System.out.println("Town name not recognized. (shouldn't happen if button is valid)");
        }
    }

    public static void main(String[] args) {
        // Launch the GUI
        SwingUtilities.invokeLater(() -> {
            new SuburbanScrambleGUI();
        });
    }
}
