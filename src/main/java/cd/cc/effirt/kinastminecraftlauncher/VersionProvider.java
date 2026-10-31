package cd.cc.effirt.kinastminecraftlauncher;

import java.io.IOException;
import java.io.InputStream;
import java.time.Year;
import java.util.Properties;

import cd.cc.effirt.util.Log;
import picocli.CommandLine;

public class VersionProvider implements CommandLine.IVersionProvider {
    private static final String VERSION_RESOURCE = "/kmcl.properties";

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
        return new String[]{"Kinast Minecraft Launcher " + doLoadVersion(), "Copyright " + Year.now() + " Effirt"};
    }

    public static String getVer() {
        return doLoadVersion();
    }
}
