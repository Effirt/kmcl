package cd.cc.effirt.kinastminecraftlauncher;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import cd.cc.effirt.util.Log;
import picocli.CommandLine;

public class VersionProvider implements CommandLine.IVersionProvider{
    private static final String VERSION_RESOURCE = "/kmcl.properties";
    private static volatile String cachedVersion;

    private static String loadVersion() {
        String v = cachedVersion;
        if (v != null) return v;
        synchronized (VersionProvider.class) {
            if (cachedVersion == null) {
                cachedVersion = doLoadVersion();
            }
            return cachedVersion;
        }
    }

    private static String doLoadVersion() {
        try (InputStream is = VersionProvider.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (is == null) return "0.0.0";
            Properties p = new Properties();
            p.load(is);
            String s = p.getProperty("version");
            return (s == null || s.isBlank()) ? "0.0.0" : s;
        } catch (IOException e) {
            Log.debug("Failed to read '{}'", VERSION_RESOURCE, e);
            return "0.0.0";
        }
    }

    @Override
    public String[] getVersion() {
        return new String[]{"Kinast Minecraft Launcher " + loadVersion()};
    }

    public static String getVer() {
        return loadVersion();
    }
}
