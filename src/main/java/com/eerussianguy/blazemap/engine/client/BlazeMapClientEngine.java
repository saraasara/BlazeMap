package com.eerussianguy.blazemap.engine.client;

import java.util.*;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import com.eerussianguy.blazemap.BlazeMap;
import com.eerussianguy.blazemap.api.event.DimensionChangedEvent;
import com.eerussianguy.blazemap.api.event.ServerJoinedEvent;
import com.eerussianguy.blazemap.api.maps.LayerRegion;
import com.eerussianguy.blazemap.api.markers.IMarkerStorage;
import com.eerussianguy.blazemap.api.markers.IStorageFactory;
import com.eerussianguy.blazemap.api.markers.MapLabel;
import com.eerussianguy.blazemap.api.markers.Waypoint;
import com.eerussianguy.blazemap.api.pipeline.MasterDatum;
import com.eerussianguy.blazemap.api.pipeline.PipelineType;
import com.eerussianguy.blazemap.api.util.IStorageAccess;
import com.eerussianguy.blazemap.engine.RegistryController;
import com.eerussianguy.blazemap.engine.StorageAccess;
import com.eerussianguy.blazemap.engine.async.AsyncChainRoot;
import com.eerussianguy.blazemap.engine.async.AsyncDataCruncher;
import com.eerussianguy.blazemap.engine.async.DebouncingThread;
import com.eerussianguy.blazemap.network.BlazeNetwork;
import com.eerussianguy.blazemap.util.Helpers;
import com.ibm.icu.impl.duration.TimeUnit;

public class BlazeMapClientEngine {
    private static final Set<Consumer<LayerRegion>> TILE_CHANGE_LISTENERS = new HashSet<>();
    private static final Map<ResourceKey<Level>, ClientPipeline> PIPELINES = new HashMap<>();
    private static final Map<ResourceKey<Level>, IMarkerStorage<Waypoint>> WAYPOINTS = new HashMap<>();
    private static final ResourceLocation WAYPOINT_STORAGE = Helpers.identifier("waypoints.bin");

    private static DebouncingThread debouncer;
    private static AsyncDataCruncher dataCruncher;
    private static AsyncChainRoot async;
    private static ClientPipeline activePipeline;
    private static IMarkerStorage.Layered<MapLabel> activeLabels;
    private static IMarkerStorage<Waypoint> activeWaypoints;
    private static IStorageFactory<IMarkerStorage<Waypoint>> waypointStorageFactory;
    private static String serverID;
    private static StorageAccess.Internal storage;
    private static boolean isServerSource;
    private static String mdSource;

    public static void init() {
        MinecraftForge.EVENT_BUS.register(BlazeMapClientEngine.class);
        dataCruncher = new AsyncDataCruncher("Blaze Map (Client)");
        async = new AsyncChainRoot(dataCruncher, Helpers::runOnMainThread);
        debouncer = new DebouncingThread("Blaze Map (Client)");
    }

    public static AsyncChainRoot async() {
        return async;
    }

    public static AsyncDataCruncher cruncher() {
        return dataCruncher;
    }

    public static DebouncingThread debouncer() {
        return debouncer;
    }

    @SubscribeEvent
    public static void onJoinServer(ClientPlayerNetworkEvent.LoggingIn event) {
        RegistryController.ensureRegistriesReady();
        LocalPlayer player = event.getPlayer();
        if(player == null) return;
        serverID = Helpers.getServerID();
        storage = new StorageAccess.Internal(Helpers.getClientSideStorageDir());
        isServerSource = BlazeNetwork.ENGINE.isRemotePresent(event.getConnection());
        ServerJoinedEvent serverJoined = new ServerJoinedEvent(serverID, storage.addon(), isServerSource);
        MinecraftForge.EVENT_BUS.post(serverJoined);
        waypointStorageFactory = serverJoined.getWaypointStorageFactory();
        switchToPipeline(player.level.dimension());
        mdSource = "unknown";
    }

    @SubscribeEvent
    public static void onLeaveServer(ClientPlayerNetworkEvent.LoggingOut event) {
        PIPELINES.clear();
        WAYPOINTS.clear();
        if(activePipeline != null) {
            activePipeline.shutdown();
            activePipeline = null;
        }
        activeLabels = null;
        activeWaypoints = null;
        serverID = null;
        storage = null;
        waypointStorageFactory = null;
    }

    @SubscribeEvent
    public static void onChangeWorld(PlayerEvent.PlayerChangedDimensionEvent event) {
        switchToPipeline(event.getTo());
    }

    private static void switchToPipeline(ResourceKey<Level> dimension) {
        if(activePipeline != null) {
            if(activePipeline.dimension.equals(dimension)) return;
            activePipeline.shutdown();
        }

        activePipeline = getPipeline(dimension);
        activeLabels = new LabelStorage(dimension);

        IStorageAccess fileStorage = activePipeline.addonStorage;
        activeWaypoints = WAYPOINTS.computeIfAbsent(dimension, d -> waypointStorageFactory.create(
            () -> fileStorage.read(WAYPOINT_STORAGE),
            () -> fileStorage.write(WAYPOINT_STORAGE),
            () -> fileStorage.exists(WAYPOINT_STORAGE)
        ));

        TILE_CHANGE_LISTENERS.clear();
        DimensionChangedEvent event = new DimensionChangedEvent(
            dimension,
            activePipeline.availableMapTypes,
            activePipeline.availableLayers,
            TILE_CHANGE_LISTENERS::add,
            activePipeline::consumeTile,
            activeLabels,
            activeWaypoints,
            fileStorage
        );
        MinecraftForge.EVENT_BUS.post(event);
    }

    private static ClientPipeline getPipeline(ResourceKey<Level> dimension) {
        return PIPELINES.computeIfAbsent(dimension, d -> new ClientPipeline(async, debouncer, d, storage.internal(d.location()), isClientSource() ? PipelineType.CLIENT_STANDALONE : PipelineType.CLIENT_AND_SERVER)).activate();
    }

    public static void onChunkChanged(ChunkPos pos, String source) {
        if(isServerSource) {
            if(activePipeline != null) {
                activePipeline.setHot();
            }
            return;
        }
        if(activePipeline == null) {
            BlazeMap.LOGGER.warn("Ignoring chunk update for {}, pipeline: {}, isServerSource: {}, brand: {}", pos, activePipeline.getClass().getSimpleName() + "@" + activePipeline.hashCode(), isServerSource, source);
            return;
        }
        mdSource = source;
        activePipeline.onChunkChanged(pos);
    }

    public static void submitChanges(ResourceKey<Level> dimension, ChunkPos pos, List<MasterDatum> data, String source) {
        isServerSource = true;
        mdSource = source;

        // If the storage hasn't been set, that means the player login event hasn't been received yet
        // and getPipeline will throw a NullPointerException that crashes the game.
        // To avoid this, will wait until storage exists before submitting this MD
        while (storage == null) {
            try {
                // BlazeMap.LOGGER.warn("666 tried to submitChanges but storage was null");
                Thread.sleep(50);
            } catch (InterruptedException e) {
                // This isn't supposed to happen, but I can't compile without it
                e.printStackTrace();
            }
        }

        // if (storage == null) {
        getPipeline(dimension).insertMasterData(pos, data);
        // }
    }

    static void notifyLayerRegionChange(LayerRegion layerRegion) {
        for(Consumer<LayerRegion> listener : TILE_CHANGE_LISTENERS) {
            listener.accept(layerRegion);
        }
    }


    // =================================================================================================================
    // Debug Info Access
    public static String getMDSource() {
        return mdSource;
    }

    public static boolean isClientSource() {
        return !isServerSource;
    }

    public static int numCollectors() {
        return activePipeline.numCollectors;
    }

    public static int numProcessors() {
        return activePipeline.numProcessors;
    }

    public static int numTransformers() {
        return activePipeline.numTransformers;
    }

    public static int numLayers() {
        return activePipeline.numLayers;
    }

    public static int dirtyTiles() {
        return activePipeline.getDirtyTiles();
    }

    public static int dirtyChunks() {
        return activePipeline.getDirtyChunks();
    }

    public static String avgFPS(){
        return Minecraft.getInstance().fpsString.split(" ")[0];
    }
}
