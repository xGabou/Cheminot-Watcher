import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.HeadlessException;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.Console;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Vector;
import java.net.HttpURLConnection;
import java.net.URL;

public final class SeatAlertWatcher {
    private static final String DEFAULT_HOST = "lancelot.etsmtl.ca";
    private static final String DEFAULT_TAG = "ChemiNot";
    private static final int DEFAULT_PORT = 8815;
    private static final int DEFAULT_INTERVAL_SECONDS = 10;
    private static final String DOTENV_NAME = ".env";

    private final Config config;
    private final Notifier notifier;
    private final ConnectionBridge connectionBridge;
    private Object connection;

    private SeatAlertWatcher(Config config) throws Exception {
        this.config = config;
        this.notifier = new Notifier(config.disableTray, config.discordWebhookUrl);
        this.connectionBridge = new ConnectionBridge();
    }

    public static void main(String[] args) {
        try {
            Config config = Config.parse(args);
            if (config.help) {
                Config.printUsage();
                return;
            }
            new SeatAlertWatcher(config).run();
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            Config.printUsage();
            System.exit(1);
        } catch (Exception ex) {
            System.err.println("Listener failed: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private void run() throws Exception {
        connect();

        Map<String, Map<Integer, GroupState>> previousByCourse = null;
        while (true) {
            try {
                Map<String, List<GroupState>> currentByCourse = fetchSnapshot();
                if (previousByCourse == null) {
                    printInitialState(currentByCourse);
                } else {
                    for (String course : config.courses) {
                        List<GroupState> current = currentByCourse.get(course);
                        Map<Integer, GroupState> previous = previousByCourse.get(course);
                        if (current == null || previous == null) {
                            continue;
                        }
                        List<GroupState> openings = detectOpenings(previous, current);
                        if (!openings.isEmpty()) {
                            String title = course + " has openings";
                            String message = formatOpenings(course, openings);
                            System.out.println(message);
                            notifier.notify(title, message);
                            Toolkit.getDefaultToolkit().beep();
                        }
                    }
                }
                previousByCourse = indexByCourse(currentByCourse);
            } catch (Exception ex) {
                System.err.println("Poll failed: " + ex.getMessage());
                ex.printStackTrace(System.err);
                reconnect();
                previousByCourse = null;
            }

            if (config.once) {
                break;
            }
            Thread.sleep(config.intervalSeconds * 1000L);
        }
    }

    private void connect() throws Exception {
        closeConnection();
        this.connection = this.connectionBridge.open(config.host);
        this.connectionBridge.setTag(this.connection, config.tag);
        int role = this.connectionBridge.login(this.connection, config.code, config.password);
        if (role <= 0) {
            throw new IllegalStateException("Login failed");
        }
    }

    private void reconnect() throws Exception {
        connect();
    }

    private void closeConnection() {
        if (this.connection != null) {
            try {
                this.connectionBridge.close(this.connection);
            } catch (Exception ignored) {
            }
            this.connection = null;
        }
    }

    private Map<String, List<GroupState>> fetchSnapshot() throws Exception {
        Map<String, List<GroupState>> snapshots = new LinkedHashMap<String, List<GroupState>>();
        for (String course : config.courses) {
            Vector rows = new Vector();
            int rc = this.connectionBridge.query(this.connection, "036", new Object[]{course, Integer.valueOf(config.session)}, rows);
            if (rc < 0) {
                throw new IllegalStateException("036 query failed for " + course + ": " + this.connectionBridge.error(this.connection));
            }

            String separator = this.connectionBridge.separator(this.connection);
            List<GroupState> states = new ArrayList<GroupState>();
            for (int i = 0; i < rows.size(); ++i) {
                GroupState state = GroupState.parse(course, rows.elementAt(i).toString(), separator);
                if (state == null) {
                    continue;
                }
                if (config.groups != null && !config.groups.contains(Integer.valueOf(state.groupNumber))) {
                    continue;
                }
                states.add(state);
            }
            snapshots.put(course, states);
        }
        return snapshots;
    }

    private void printInitialState(Map<String, List<GroupState>> currentByCourse) {
        System.out.println("Watching " + joinCourses(config.courses) + " / " + config.session + " every " + config.intervalSeconds + "s");
        for (String course : config.courses) {
            List<GroupState> current = currentByCourse.get(course);
            if (current == null || current.isEmpty()) {
                System.out.println("No groups returned for " + course + " / " + config.session);
                continue;
            }
            for (GroupState state : current) {
                System.out.println(state.describe(course));
            }
        }
    }

    private List<GroupState> detectOpenings(Map<Integer, GroupState> previous, List<GroupState> current) {
        List<GroupState> openings = new ArrayList<GroupState>();
        for (GroupState state : current) {
            GroupState old = previous.get(Integer.valueOf(state.groupNumber));
            if (old == null) {
                if (state.isOpen()) {
                    openings.add(state);
                }
                continue;
            }
            if (!old.isOpen() && state.isOpen()) {
                openings.add(state);
            }
        }
        return openings;
    }

    private Map<Integer, GroupState> indexByGroup(List<GroupState> current) {
        Map<Integer, GroupState> map = new LinkedHashMap<Integer, GroupState>();
        for (GroupState state : current) {
            map.put(Integer.valueOf(state.groupNumber), state);
        }
        return map;
    }

    private String formatOpenings(String course, List<GroupState> openings) {
        StringBuilder sb = new StringBuilder();
        sb.append(course).append(" / ").append(config.session).append(": ");
        for (int i = 0; i < openings.size(); ++i) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(openings.get(i).shortLabel(course));
        }
        return sb.toString();
    }

    private Map<String, Map<Integer, GroupState>> indexByCourse(Map<String, List<GroupState>> currentByCourse) {
        Map<String, Map<Integer, GroupState>> indexed = new LinkedHashMap<String, Map<Integer, GroupState>>();
        for (Map.Entry<String, List<GroupState>> entry : currentByCourse.entrySet()) {
            indexed.put(entry.getKey(), indexByGroup(entry.getValue()));
        }
        return indexed;
    }

    private String joinCourses(List<String> courses) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < courses.size(); ++i) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(courses.get(i));
        }
        return sb.toString();
    }

    private static final class GroupState {
        private final int groupNumber;
        private final int capacity;
        private final int occupied;
        private final String activity1;
        private final String activity2;

        private GroupState(int groupNumber, int capacity, int occupied, String activity1, String activity2) {
            this.groupNumber = groupNumber;
            this.capacity = capacity;
            this.occupied = occupied;
            this.activity1 = activity1 == null ? "" : activity1.trim();
            this.activity2 = activity2 == null ? "" : activity2.trim();
        }

        private static GroupState parse(String course, String row, String separator) {
            List<String> tokens = tokenize(row, separator);
            if (tokens.size() < 4) {
                tokens = tokenize(row, null);
            }
            if (tokens.size() < 4) {
                System.err.println("Skipping malformed 036 row for " + course + ": " + row);
                return null;
            }
            int groupNumber = parseInt(tokens.get(0), -1);
            int occupiedA = parseInt(tokens.get(1), -1);
            int occupiedB = parseInt(tokens.get(2), -1);
            int capacity = parseInt(tokens.get(3), -1);
            if (groupNumber < 0 || occupiedA < 0 || occupiedB < 0 || capacity < 0) {
                System.err.println("Skipping invalid 036 row for " + course + ": " + row);
                return null;
            }
            if (capacity == 0) {
                capacity = 1000;
            }
            return new GroupState(groupNumber, capacity, occupiedA + occupiedB, "", "");
        }

        private boolean isOpen() {
            return this.capacity > this.occupied;
        }

        private String shortLabel(String course) {
            return course + " groupe " + groupCode() + " (" + this.occupied + "/" + this.capacity + ")";
        }

        private String describe(String course) {
            StringBuilder sb = new StringBuilder();
            sb.append(course).append(" groupe ").append(groupCode()).append(" - ");
            sb.append(this.occupied).append("/").append(this.capacity);
            sb.append(this.isOpen() ? " open" : " full");
            if (!this.activity1.isEmpty() || !this.activity2.isEmpty()) {
                sb.append(" - ").append(this.activity1);
                if (!this.activity2.isEmpty()) {
                    sb.append(" / ").append(this.activity2);
                }
            }
            return sb.toString();
        }

        private String groupCode() {
            if (this.groupNumber < 10) {
                return "0" + this.groupNumber;
            }
            return Integer.toString(this.groupNumber);
        }

        private static List<String> tokenize(String row, String separator) {
            List<String> tokens = new ArrayList<String>();
            String delimiters = separator == null || separator.length() == 0 ? null : separator;
            java.util.StringTokenizer tokenizer = delimiters == null ? new java.util.StringTokenizer(row) : new java.util.StringTokenizer(row, delimiters);
            while (tokenizer.hasMoreTokens()) {
                tokens.add(tokenizer.nextToken().trim());
            }
            return tokens;
        }

        private static int parseInt(String value, int defaultValue) {
            try {
                return Integer.parseInt(value.trim());
            } catch (Exception ex) {
                return defaultValue;
            }
        }
    }

    private static final class Notifier {
        private final boolean disabled;
        private final String discordWebhookUrl;
        private TrayIcon trayIcon;

        private Notifier(boolean disabled, String discordWebhookUrl) {
            this.disabled = disabled;
            this.discordWebhookUrl = discordWebhookUrl == null ? "" : discordWebhookUrl.trim();
            if (!disabled) {
                initTray();
            }
        }

        private void initTray() {
            try {
                if (!SystemTray.isSupported()) {
                    return;
                }
                SystemTray tray = SystemTray.getSystemTray();
                Image image = createIcon();
                this.trayIcon = new TrayIcon(image, "Seat alert");
                this.trayIcon.setImageAutoSize(true);
                tray.add(this.trayIcon);
            } catch (HeadlessException | AWTException ignored) {
                this.trayIcon = null;
            }
        }

        private Image createIcon() {
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setColor(new Color(0x1F, 0x5A, 0x9E));
                g.fillRect(0, 0, 16, 16);
                g.setColor(Color.WHITE);
                g.setFont(new Font("Dialog", Font.BOLD, 12));
                g.drawString("S", 4, 12);
            } finally {
                g.dispose();
            }
            return image;
        }

        private void notify(String title, String message) {
            if (this.trayIcon != null) {
                this.trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
            }
            System.out.println(title + ": " + message);
            if (!this.discordWebhookUrl.isEmpty()) {
                try {
                    sendDiscordWebhook(title, message);
                } catch (Exception ex) {
                    System.err.println("Discord webhook failed: " + ex.getMessage());
                }
            }
        }

        private void sendDiscordWebhook(String title, String message) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) new URL(this.discordWebhookUrl).openConnection();
            try {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("User-Agent", "SeatAlertWatcher/1.0");
                String payload = "{\"content\":\"" + escapeJson(title + "\\n" + message) + "\"}";
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Length", Integer.toString(bytes.length));
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("HTTP " + status);
                }
            } finally {
                connection.disconnect();
            }
        }

        private String escapeJson(String value) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < value.length(); ++i) {
                char c = value.charAt(i);
                switch (c) {
                    case '"':
                        sb.append("\\\"");
                        break;
                    case '\\':
                        sb.append("\\\\");
                        break;
                    case '\b':
                        sb.append("\\b");
                        break;
                    case '\f':
                        sb.append("\\f");
                        break;
                    case '\n':
                        sb.append("\\n");
                        break;
                    case '\r':
                        sb.append("\\r");
                        break;
                    case '\t':
                        sb.append("\\t");
                        break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            return sb.toString();
        }
    }

    private static final class Config {
        private String host = DEFAULT_HOST;
        private int port = DEFAULT_PORT;
        private String tag = DEFAULT_TAG;
        private String code;
        private String password;
        private List<String> courses;
        private int session = -1;
        private int intervalSeconds = DEFAULT_INTERVAL_SECONDS;
        private String discordWebhookUrl;
        private boolean once;
        private boolean help;
        private boolean disableTray;
        private Set<Integer> groups;

        private static Config parse(String[] args) throws Exception {
            Config config = new Config();
            applyEnvFile(config, findDotEnv());
            List<String> list = Arrays.asList(args);
            for (int i = 0; i < list.size(); ++i) {
                String arg = list.get(i);
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    config.help = true;
                    return config;
                }
                if ("--once".equals(arg)) {
                    config.once = true;
                    continue;
                }
                if ("--no-tray".equals(arg)) {
                    config.disableTray = true;
                    continue;
                }
                if ("--host".equals(arg)) {
                    config.host = requireValue(list, ++i, "--host");
                    continue;
                }
                if ("--port".equals(arg)) {
                    config.port = Integer.parseInt(requireValue(list, ++i, "--port"));
                    continue;
                }
                if ("--tag".equals(arg)) {
                    config.tag = requireValue(list, ++i, "--tag");
                    continue;
                }
                if ("--code".equals(arg)) {
                    config.code = requireValue(list, ++i, "--code");
                    continue;
                }
                if ("--password".equals(arg)) {
                    config.password = requireValue(list, ++i, "--password");
                    continue;
                }
                if ("--webhook".equals(arg)) {
                    config.discordWebhookUrl = requireValue(list, ++i, "--webhook");
                    continue;
                }
                if ("--course".equals(arg)) {
                    config.courses = parseCourses(requireValue(list, ++i, "--course"));
                    continue;
                }
                if ("--session".equals(arg)) {
                    config.session = Integer.parseInt(requireValue(list, ++i, "--session"));
                    continue;
                }
                if ("--interval".equals(arg)) {
                    config.intervalSeconds = parseSeconds(requireValue(list, ++i, "--interval"));
                    continue;
                }
                if ("--groups".equals(arg)) {
                    config.groups = parseGroups(requireValue(list, ++i, "--groups"));
                    continue;
                }
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }

            if (config.courses == null || config.courses.isEmpty()) {
                throw new IllegalArgumentException("Missing --course");
            }
            if (config.session < 0) {
                throw new IllegalArgumentException("Missing --session");
            }
            if (config.code == null || config.code.isEmpty()) {
                config.code = prompt("Code d'acces");
            }
            if (config.password == null) {
                config.password = promptPassword("Mot de passe");
            }
            if (config.intervalSeconds < 1) {
                throw new IllegalArgumentException("--interval must be >= 1");
            }
            if (config.groups != null && config.groups.isEmpty()) {
                config.groups = null;
            }
            return config;
        }

        private static void applyEnvFile(Config config, Path envPath) throws Exception {
            if (envPath == null || !Files.exists(envPath)) {
                return;
            }
            List<String> lines = Files.readAllLines(envPath, StandardCharsets.UTF_8);
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim().toUpperCase(Locale.ROOT);
                String value = unquote(line.substring(eq + 1).trim());
                if ("HOST".equals(key) && config.host == DEFAULT_HOST && !value.isEmpty()) {
                    config.host = value;
                } else if ("PORT".equals(key) && config.port == DEFAULT_PORT && !value.isEmpty()) {
                    config.port = Integer.parseInt(value);
                } else if ("TAG".equals(key) && config.tag == DEFAULT_TAG && !value.isEmpty()) {
                    config.tag = value;
                } else if ("CODE".equals(key) && (config.code == null || config.code.isEmpty()) && !value.isEmpty()) {
                    config.code = value;
                } else if ("PASSWORD".equals(key) && config.password == null && !value.isEmpty()) {
                    config.password = value;
                } else if ("COURSE".equals(key) && (config.courses == null || config.courses.isEmpty()) && !value.isEmpty()) {
                    config.courses = parseCourses(value);
                } else if ("COURSES".equals(key) && (config.courses == null || config.courses.isEmpty()) && !value.isEmpty()) {
                    config.courses = parseCourses(value);
                } else if ("DISCORD_WEBHOOK".equals(key) && (config.discordWebhookUrl == null || config.discordWebhookUrl.isEmpty()) && !value.isEmpty()) {
                    config.discordWebhookUrl = value;
                } else if ("WEBHOOK_URL".equals(key) && (config.discordWebhookUrl == null || config.discordWebhookUrl.isEmpty()) && !value.isEmpty()) {
                    config.discordWebhookUrl = value;
                } else if ("SESSION".equals(key) && config.session < 0 && !value.isEmpty()) {
                    config.session = Integer.parseInt(value);
                } else if ("INTERVAL".equals(key) && config.intervalSeconds == DEFAULT_INTERVAL_SECONDS && !value.isEmpty()) {
                    config.intervalSeconds = parseSeconds(value);
                } else if ("GROUPS".equals(key) && config.groups == null && !value.isEmpty()) {
                    config.groups = parseGroups(value);
                } else if ("ONCE".equals(key) && !config.once && !value.isEmpty()) {
                    config.once = parseBoolean(value);
                } else if ("NO_TRAY".equals(key) && !config.disableTray && !value.isEmpty()) {
                    config.disableTray = parseBoolean(value);
                }
            }
        }

        private static Path findDotEnv() {
            Path cwd = Paths.get("").toAbsolutePath();
            Path candidate = cwd.resolve(DOTENV_NAME);
            if (Files.exists(candidate)) {
                return candidate;
            }
            return null;
        }

        private static String requireValue(List<String> args, int index, String flag) {
            if (index >= args.size()) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            return args.get(index).trim();
        }

        private static String normalizeCourse(String value) {
            return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
        }

        private static List<String> parseCourses(String value) {
            List<String> courses = new ArrayList<String>();
            if (value == null) {
                return courses;
            }
            for (String token : value.split("[,;\\s]+")) {
                String normalized = normalizeCourse(token);
                if (normalized == null || normalized.isEmpty()) {
                    continue;
                }
                courses.add(normalized);
            }
            return courses;
        }

        private static Set<Integer> parseGroups(String value) {
            Set<Integer> groups = new TreeSet<Integer>();
            for (String token : value.split(",")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                groups.add(Integer.valueOf(Integer.parseInt(trimmed)));
            }
            return groups;
        }

        private static boolean parseBoolean(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            return "1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized);
        }

        private static int parseSeconds(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (normalized.endsWith("seconds")) {
                normalized = normalized.substring(0, normalized.length() - "seconds".length()).trim();
            } else if (normalized.endsWith("sec")) {
                normalized = normalized.substring(0, normalized.length() - "sec".length()).trim();
            } else if (normalized.endsWith("s")) {
                normalized = normalized.substring(0, normalized.length() - 1).trim();
            }
            return Integer.parseInt(normalized);
        }

        private static String unquote(String value) {
            if (value == null || value.length() < 2) {
                return value;
            }
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }

        private static void printUsage() {
            System.out.println("Usage:");
            System.out.println("  java -cp .;ChemiNotC.jar SeatAlertWatcher [options]");
            System.out.println();
            System.out.println("Required:");
            System.out.println("  --course SIGLE         Course code list, for example LOG660,MTH230");
            System.out.println("  --session NNNNN        Session code, for example 20263");
            System.out.println();
            System.out.println("Optional:");
            System.out.println("  --code VALUE           Access code");
            System.out.println("  --password VALUE       Password");
            System.out.println("  --webhook URL          Discord webhook URL");
            System.out.println("  --host VALUE           Server host, default " + DEFAULT_HOST);
            System.out.println("  --port VALUE           Server port, default " + DEFAULT_PORT);
            System.out.println("  --interval SECONDS     Poll interval in seconds, default " + DEFAULT_INTERVAL_SECONDS);
            System.out.println("  --groups 1,2,3         Restrict to specific groups");
            System.out.println("  --tag VALUE            App tag sent to the server, default " + DEFAULT_TAG);
            System.out.println("  --once                 Run a single poll and exit");
            System.out.println("  --no-tray              Disable system tray notifications");
            System.out.println("  --help                 Show this help");
            System.out.println();
            System.out.println("You can also set values in a local .env file.");
        }
    }

    private static final class ConnectionBridge {
        private final Class<?> securityClass;
        private final Class<?> connectionClass;
        private final java.lang.reflect.Constructor<?> connectionCtor;
        private final java.lang.reflect.Method setTag;
        private final java.lang.reflect.Method login;
        private final java.lang.reflect.Method query;
        private final java.lang.reflect.Method separator;
        private final java.lang.reflect.Method close;
        private final java.lang.reflect.Method error;
        private final java.lang.reflect.Method init;

        private ConnectionBridge() throws Exception {
            this.securityClass = Class.forName("ets.b.a.a");
            this.connectionClass = Class.forName("ets.b.b");
            this.connectionCtor = this.connectionClass.getConstructor(String.class, this.securityClass);
            this.setTag = this.connectionClass.getDeclaredMethod("a", String.class);
            this.login = this.connectionClass.getDeclaredMethod("a", String.class, String.class);
            this.query = this.connectionClass.getDeclaredMethod("a", String.class, Object[].class, Vector.class);
            this.separator = this.connectionClass.getDeclaredMethod("h");
            this.close = this.connectionClass.getDeclaredMethod("b");
            this.error = this.connectionClass.getDeclaredMethod("a");
            this.init = this.securityClass.getDeclaredMethod("a", long.class, long.class, long.class, long.class);
            this.setTag.setAccessible(true);
            this.login.setAccessible(true);
            this.query.setAccessible(true);
            this.separator.setAccessible(true);
            this.close.setAccessible(true);
            this.error.setAccessible(true);
            this.init.setAccessible(true);
        }

        private Object open(String host) throws Exception {
            Object security = this.securityClass.getDeclaredConstructor().newInstance();
            this.init.invoke(security, 967687137L, 652059871L, 585765643L, 1250586253L);
            return this.connectionCtor.newInstance(host, security);
        }

        private void setTag(Object connection, String tag) throws Exception {
            this.setTag.invoke(connection, tag);
        }

        private int login(Object connection, String code, String password) throws Exception {
            return ((Integer) this.login.invoke(connection, code, password)).intValue();
        }

        private int query(Object connection, String op, Object[] args, Vector rows) throws Exception {
            return ((Integer) this.query.invoke(connection, op, args, rows)).intValue();
        }

        private String separator(Object connection) throws Exception {
            return (String) this.separator.invoke(connection);
        }

        private void close(Object connection) throws Exception {
            this.close.invoke(connection);
        }

        private String error(Object connection) throws Exception {
            Object value = this.error.invoke(connection);
            return value == null ? "" : value.toString();
        }
    }

    private static String prompt(String label) throws Exception {
        Console console = System.console();
        if (console != null) {
            String value = console.readLine("%s: ", label);
            if (value == null) {
                throw new IllegalStateException("Missing " + label);
            }
            return value.trim();
        }
        System.out.print(label + ": ");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String value = reader.readLine();
        if (value == null) {
            throw new IllegalStateException("Missing " + label);
        }
        return value.trim();
    }

    private static String promptPassword(String label) throws Exception {
        Console console = System.console();
        if (console != null) {
            char[] value = console.readPassword("%s: ", label);
            if (value == null) {
                throw new IllegalStateException("Missing " + label);
            }
            return new String(value).trim();
        }
        return prompt(label);
    }

}
