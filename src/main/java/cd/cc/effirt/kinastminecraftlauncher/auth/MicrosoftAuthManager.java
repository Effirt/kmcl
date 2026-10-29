package cd.cc.effirt.kinastminecraftlauncher.auth;

import cd.cc.effirt.kinastminecraftlauncher.VersionProvider;
import cd.cc.effirt.util.Log;
import com.google.gson.JsonObject;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Consumer;

public class MicrosoftAuthManager {

    private static final String USER_AGENT = "KinastMinecraftLauncher/" + VersionProvider.getVer();

    public static Account login() throws IOException {
        HttpClient httpClient = MinecraftAuth.createHttpClient(USER_AGENT);

        try {
            JavaAuthManager authManager = JavaAuthManager.create(httpClient)
                    .login(
                            (client, config, consumer) -> new DeviceCodeMsaAuthService(client, config, consumer, 5 * 60_000),
                            (Consumer<MsaDeviceCode>) deviceCode -> Log.info(deviceCode.getDirectVerificationUri())
                    );

            MinecraftProfile profile = authManager.getMinecraftProfile().getUpToDate();
            String name = profile.getName();
            UUID id = profile.getId();
            JsonObject tokens = JavaAuthManager.toJson(authManager);

            return Account.msa(name, id, tokens);
        } catch (Exception e) {
            throw new IOException("Microsoft login failed", e);
        }
    }
}