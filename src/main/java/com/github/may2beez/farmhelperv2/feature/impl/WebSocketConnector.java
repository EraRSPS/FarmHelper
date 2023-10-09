package com.github.may2beez.farmhelperv2.feature.impl;

import com.github.may2beez.farmhelperv2.FarmHelper;
import com.github.may2beez.farmhelperv2.config.FarmHelperConfig;
import com.github.may2beez.farmhelperv2.feature.IFeature;
import com.github.may2beez.farmhelperv2.handler.MacroHandler;
import com.github.may2beez.farmhelperv2.util.LogUtils;
import com.github.may2beez.farmhelperv2.util.helper.Clock;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

public class WebSocketConnector implements IFeature {
    private static WebSocketConnector instance;

    public static WebSocketConnector getInstance() {
        if (instance == null) {
            instance = new WebSocketConnector();
        }
        return instance;
    }

    public WebSocketConnector() {
        if (isConnected()) return;
        try {
            LogUtils.sendDebug("Connecting to analytics server...");
            client = createNewWebSocketClient();
            for (Map.Entry<String, JsonElement> header : getHeaders().entrySet()) {
                client.addHeader(header.getKey(), header.getValue().getAsString());
            }
            client.connect();
        } catch (URISyntaxException e) {
            e.printStackTrace();
            client = null;
        }
    }

    private WebSocketClient client;

    @Getter
    @Setter
    private int bans = 0;

    @Getter
    @Setter
    private int minutes = 0;

    @Getter
    @Setter
    private int bansByMod = 0;

    @Getter
    private boolean banwave = false;

    @Override
    public String getName() {
        return "Banwave Checker";
    }

    @Override
    public boolean isRunning() {
        return isToggled();
    }

    @Override
    public boolean shouldPauseMacroExecution() {
        return false;
    }

    @Override
    public boolean shouldStartAtMacroStart() {
        return false; // it's running all the time
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {

    }

    @Override
    public void resetStatesAfterMacroDisabled() {

    }

    @Override
    public boolean isToggled() {
        return FarmHelperConfig.banwaveCheckerEnabled;
    }

    public boolean isConnected() {
        return client != null && client.isOpen();
    }

    @SubscribeEvent
    public void onClientConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        if (Minecraft.getMinecraft().thePlayer == null || Minecraft.getMinecraft().theWorld == null) return;

        client.send("{\"message\":\"banwaveInfo\", \"mod\": \"farmHelper\"}");
    }

    private final Clock reconnectDelay = new Clock();

    @SubscribeEvent
    public void onTickReconnect(TickEvent.ClientTickEvent event) {
        if (Minecraft.getMinecraft().thePlayer == null || Minecraft.getMinecraft().theWorld == null) return;
        if (reconnectDelay.isScheduled() && !reconnectDelay.passed()) return;

        if (client == null || client.isClosed() || !client.isOpen()) {
            try {
                reconnectDelay.reset();
                LogUtils.sendDebug("Connecting to analytics server...");
                client = createNewWebSocketClient();
                for (Map.Entry<String, JsonElement> header : getHeaders().entrySet()) {
                    client.addHeader(header.getKey(), header.getValue().getAsString());
                }
                client.connect();
            } catch (URISyntaxException e) {
                e.printStackTrace();
                client = null;
                reconnectDelay.schedule(5_000);
            }
        }
    }

    public void playerBanned(int days, String banId) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("message", "gotBanned");
        jsonObject.addProperty("mod", "farmHelper");
        JsonObject additionalInfo = new JsonObject();
        additionalInfo.addProperty("days", days);
        additionalInfo.addProperty("banId", banId);
        String config = FarmHelper.gson.toJson(FarmHelper.config);
        JsonObject configJson = FarmHelper.gson.fromJson(config, JsonObject.class);
        configJson.remove("webHookURL"); // remove webhook url from config
        configJson.remove("discordRemoteControlToken"); // remove discord remote control token from config
        String configJsonString = FarmHelper.gson.toJson(configJson);
        additionalInfo.addProperty("config", configJsonString);
        jsonObject.add("additionalInfo", additionalInfo);
        client.send(jsonObject.toString());
    }

    public void sendAnalyticsData() {
        System.out.println("Sending analytics data");
        if (MacroHandler.getInstance().getAnalyticsTimer().getElapsedTime() <= 60_000) return; // ignore if macroing for less than 60 seconds
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("message", "analyticsData");
        jsonObject.addProperty("mod", "farmHelper");
        jsonObject.addProperty("username", Minecraft.getMinecraft().getSession().getUsername());
        jsonObject.addProperty("id", Minecraft.getMinecraft().getSession().getPlayerID()); // that's public uuid bozos, not a token to login
        jsonObject.addProperty("modVersion", FarmHelper.VERSION);
        jsonObject.addProperty("timeMacroing", MacroHandler.getInstance().getAnalyticsTimer().getElapsedTime());
        JsonObject additionalInfo = new JsonObject();
        additionalInfo.addProperty("cropType", MacroHandler.getInstance().getCrop().toString());
        additionalInfo.addProperty("bps", ProfitCalculator.getInstance().getBPS());
        additionalInfo.addProperty("profit", ProfitCalculator.getInstance().getRealProfitString());
        jsonObject.add("additionalInfo", additionalInfo);
        client.send(jsonObject.toString());
    }

    private JsonObject getHeaders() {
        JsonObject handshake = new JsonObject();
        handshake.addProperty("reason", "WebSocketConnector");
        handshake.addProperty("id", Minecraft.getMinecraft().getSession().getPlayerID()); // that's public uuid bozos, not a token to login
        handshake.addProperty("username", Minecraft.getMinecraft().getSession().getUsername());
        handshake.addProperty("modVersion", FarmHelper.VERSION);
        handshake.addProperty("mod", "farmHelper");
        return handshake;
    }

    private WebSocketClient createNewWebSocketClient() throws URISyntaxException {
        return new WebSocketClient(new URI("ws://may2beez.ddns.net:3000")) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                LogUtils.sendDebug("Connected to analytics websocket server");
            }

            @Override
            public void onMessage(String message) {
                JsonObject jsonObject = FarmHelper.gson.fromJson(message, JsonObject.class);
                String msg = jsonObject.get("message").getAsString();
                switch (msg) {
                    case "banwaveInfo": {
                        int bans = jsonObject.get("bansInLast15Minutes").getAsInt();
                        int minutes = jsonObject.get("bansInLast15MinutesTime").getAsInt();
                        int bansByMod = jsonObject.get("bansInLast15MinutesMod").getAsInt();
                        WebSocketConnector.getInstance().setBans(bans);
                        WebSocketConnector.getInstance().setMinutes(minutes);
                        WebSocketConnector.getInstance().setBansByMod(bansByMod);
                        break;
                    }
                    case "playerGotBanned": {
                        String username = jsonObject.get("username").getAsString();
                        String days = jsonObject.get("days").getAsString();
                        String mod = jsonObject.get("mod").getAsString();
                        LogUtils.sendWarning("Player " + username + " got banned for " + days + " days by using " + mod);
                        break;
                    }
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                LogUtils.sendDebug("Disconnected from analytics server");
                LogUtils.sendDebug("Code: " + code + ", reason: " + reason + ", remote: " + remote);
                reconnectDelay.schedule(5_000);
            }

            @Override
            public void onError(Exception ex) {
                LogUtils.sendDebug("Error while connecting to analytics server. " + ex.getMessage());
                ex.printStackTrace();
            }
        };
    }
}