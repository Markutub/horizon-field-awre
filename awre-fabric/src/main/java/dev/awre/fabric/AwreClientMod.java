package dev.awre.fabric;

import dev.awre.protocol.Awre;
import dev.awre.stream.ClientRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * THIN Minecraft 1.21.11 / Fabric adapter. All client logic (session, view, planner, logging, level changes) lives in the pure-Java
 * {@link ClientRuntime}; this class only wires Fabric events, the payload channel and {@link HorizonRenderer}. A port to another
 * Minecraft generation rewrites this file and HorizonRenderer, nothing else.
 *
 * <p>JVM properties: {@code -Dawre.render=auto|on|off} (auto = off only when another distant-terrain LOD mod, Distant Horizons or Voxy, is loaded),
 * {@code -Dawre.view.maxDistance=16384}, {@code -Dawre.iris=auto|off} (auto: ask Iris to shade AWRE terrain with the pack's terrain program), {@code -Dawre.far=auto|off|<blocks>} (far plane of the AWRE pass; off = vanilla's projection), {@code -Dawre.test.minVersion/maxVersion} (forces an incompatible client for tests).
 */
public final class AwreClientMod implements ClientModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("AWRE");
    private static final String MOD_VERSION = "0.1.0";
    private static final int FREE_DELAY_FRAMES = 3;     // explicit-API backends may still read a buffer drawn in the last frames
    private final HorizonRenderer renderer = new HorizonRenderer();
    private ClientRuntime<HorizonRenderer.GpuMesh> runtime;
    private dev.awre.stream.compat.ShaderPackBridge shaderBridge = dev.awre.stream.compat.ShaderPackBridge.NONE;
    private boolean lastShaderActive;
    private float lastDepthFar = -1;
    private int lastEffectiveRd = -1;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playS2C().register(AwrePayload.TYPE, AwrePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(AwrePayload.TYPE, AwrePayload.CODEC);

        var decision = dev.awre.stream.compat.RenderPolicy.decide(dev.awre.stream.compat.RenderPolicy.Mode.parse(System.getProperty("awre.render", "auto")),
                id -> FabricLoader.getInstance().isModLoaded(id));
        decision.notes().forEach(LOG::info);
        boolean renderEnabled = decision.renderEnabled();
        loadColorOverrides();
        loadBiomeOverrides();
        shaderBridge = FabricLoader.getInstance().isModLoaded("iris") ? IrisBridge.create() : dev.awre.stream.compat.ShaderPackBridge.NONE;
        renderer.setShaderBridge(shaderBridge, !"off".equalsIgnoreCase(System.getProperty("awre.iris", "auto")));

        double maxDistance = Double.parseDouble(System.getProperty("awre.view.maxDistance", "16384"));
        renderer.configureFar(System.getProperty("awre.far", "auto"), maxDistance);
        runtime = new ClientRuntime<>(new ClientRuntime.Host() {
            @Override public void send(byte[] frame) { AwreClientMod.this.send(frame); }
            @Override public void info(String m) { LOG.info(m); }
            @Override public void warn(String m) { LOG.warn(m); }
        }, renderer, MOD_VERSION, maxDistance, FREE_DELAY_FRAMES, renderEnabled);

        ClientPlayNetworking.registerGlobalReceiver(AwrePayload.TYPE, (payload, context) -> runtime.onPayload(payload.data(), payload.skippedLength()));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            renderer.releaseAll();
            runtime.onJoin(Integer.getInteger("awre.test.minVersion", Awre.PROTOCOL_MIN), Integer.getInteger("awre.test.maxVersion", Awre.PROTOCOL_MAX));
            renderer.setPlanner(runtime.planner());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            renderer.releaseAll();                       // GPU meshes first, then the CPU side
            runtime.onDisconnect();
            renderer.setPlanner(null);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean shaders = shaderBridge.shaderPackActive();
            if (shaders != lastShaderActive) {
                lastShaderActive = shaders;
                LOG.info("[AWRE] shader pack {}{}", shaders ? "ACTIVE" : "inactive", shaders ? ": plain terrain pipeline is used (no reveal effect); whether the pack shades it depends on Iris accepting assignPipeline (see the assignPipeline log line); UNVERIFIED visually" : "");
            }
            // The square vanilla really draws is limited by what the SERVER sends: Options#getEffectiveRenderDistance() is min(client option, server
            // view distance). The raw option (renderDistance().get()) hid ~450 tiles that vanilla was not drawing (G2 audit; signature from mc-api-dump).
            double vanilla = client.options.getEffectiveRenderDistance() * 16.0;
            float depthFar = client.gameRenderer.getDepthFar();       // diagnostic: tiles farther than the projection far plane are clipped by the hardware
            int effectiveRd = client.options.getEffectiveRenderDistance();
            // logged when EITHER changes: the first line is written in the main menu (no server distance yet); the in-world value is the one that matters
            if (depthFar != lastDepthFar || effectiveRd != lastEffectiveRd) { lastDepthFar = depthFar; lastEffectiveRd = effectiveRd; LOG.info("[AWRE] projection far plane {} blocks, effective render distance {} chunks, in world: {}", depthFar, effectiveRd, client.level != null); }
            // ResourceKey#toString looks like "ResourceKey[minecraft:dimension / minecraft:overworld]"; parsed so the accessor name does not matter
            String dim = client.level == null ? null : dev.awre.stream.DimensionGate.fromResourceKeyString(String.valueOf(client.level.dimension()));
            runtime.onTick(client.player != null, client.level, client.player == null ? 0 : client.player.getX(), client.player == null ? 0 : client.player.getZ(), vanilla, dim);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { renderer.releaseAll(); runtime.shutdown(); renderer.setPlanner(null); renderer.shutdown(); });

        // CI-confirmed (session 18 build log): WorldRenderEvents.END_MAIN exists and Camera#position() compiles, but WorldRenderContext has NO
        // positionMatrix(). The view matrix is built from the camera rotation (world -> view, no translation: the translation is the
        // per-draw offset). API-ASSUMPTION still open: Camera#rotation() returning a joml Quaternionf.
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.END_MAIN.register(ctx -> {
            var camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
            var cam = camera.position();
            var view = new org.joml.Matrix4f().rotation(camera.rotation().conjugate(new org.joml.Quaternionf()));
            renderer.render(view, cam.x, cam.y, cam.z);
        });
        LOG.info("[AWRE] client G0 loaded; protocol v{}..v{}, channel {}", Awre.PROTOCOL_MIN, Awre.PROTOCOL_MAX, Awre.CHANNEL);
    }

    /** config/awre-colors.txt: "namespace:block = #RRGGBB" rules for blocks the built-in colour table does not know (see ColorOverrides). */
    private static void loadColorOverrides() {
        java.nio.file.Path f = FabricLoader.getInstance().getConfigDir().resolve("awre-colors.txt");
        if (!java.nio.file.Files.isRegularFile(f)) return;
        try {
            var o = dev.horizonfield.core.terrain.ColorOverrides.parse(java.nio.file.Files.readAllLines(f));
            dev.horizonfield.core.terrain.BlockColors.setOverrides(o);
            LOG.info("[AWRE] colour overrides: {} rules from {}", o.size(), f.getFileName());
            for (String r : o.rejected()) LOG.warn("[AWRE] awre-colors.txt {}", r);
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("[AWRE] could not read {}: {}", f.getFileName(), e.toString());
        }
    }

    /** config/awre-biomes.txt: "biome_id = grass:#RRGGBB foliage:#RRGGBB water:#RRGGBB" for biomes from worldgen mods (see BiomeTint). */
    private static void loadBiomeOverrides() {
        java.nio.file.Path f = FabricLoader.getInstance().getConfigDir().resolve("awre-biomes.txt");
        if (!java.nio.file.Files.isRegularFile(f)) return;
        try {
            var p = dev.horizonfield.core.terrain.BiomeTint.parse(java.nio.file.Files.readAllLines(f));
            dev.horizonfield.core.terrain.BiomeTint.setOverrides(p.overrides());
            LOG.info("[AWRE] biome overrides: {} biomes from {}", p.overrides().size(), f.getFileName());
            for (String r : p.rejected()) LOG.warn("[AWRE] awre-biomes.txt {}", r);
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("[AWRE] could not read {}: {}", f.getFileName(), e.toString());
        }
    }

    private void send(byte[] frame) {
        try {
            ClientPlayNetworking.send(new AwrePayload(frame, 0));
        } catch (RuntimeException e) {
            LOG.warn("[AWRE] send failed: {}", e.toString());
        }
    }
}
