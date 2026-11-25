package taskmanager;

import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) throws Exception {
        Server.Config cfg = new Server.Config(
                Integer.parseInt(env("PORT", "8080")),
                env("BIND", "127.0.0.1"),
                Path.of(env("DATA_DIR", "data")),
                Path.of(env("WEB_DIR", "../web")),
                Boolean.parseBoolean(env("SECURE_COOKIES", "false")),
                Boolean.parseBoolean(env("TRUST_PROXY", "false")));
        Server server = new Server(cfg);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        System.out.println("Task manager on http://" + cfg.bind() + ":" + server.port() + "  (data in " + cfg.dataDir().toAbsolutePath() + ")");
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
