package ocd.mc170012.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.Util;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.light.LightingProvider;
import ocd.mc170012.ServerLightingProviderAccessor;

@Mixin(ServerLightingProvider.class)
public abstract class ServerLightingProviderMixin extends LightingProvider implements ServerLightingProviderAccessor
{
    public ServerLightingProviderMixin(final ChunkProvider chunkProvider, final boolean hasBlockLight, final boolean hasSkyLight)
    {
        super(chunkProvider, hasBlockLight, hasSkyLight);
    }

    @Shadow
    protected abstract void enqueue(int x, int z, ServerLightingProvider.Stage stage, Runnable task);

    @Override
    public CompletableFuture<Chunk> setupLightmaps(final Chunk chunk)
    {
        final ChunkPos chunkPos = chunk.getPos();

        // This evaluates the non-empty subchunks concurrently on the lighting thread...
        this.enqueue(chunkPos.x, chunkPos.z, ServerLightingProvider.Stage.PRE_UPDATE, Util.debugRunnable(() -> {
            ChunkSection[] chunkSections = chunk.getSectionArray();

            for (int i = 0; i < chunkSections.length; ++i)
            {
                ChunkSection chunkSection = chunkSections[i];
                if (!ChunkSection.isEmpty(chunkSection))
                    super.updateSectionStatus(ChunkSectionPos.from(chunkPos, i), false);
            }

            if (chunk.isLightOn())
                super.setLightEnabled(chunkPos, true);
        },
            () -> "setupLightmaps " + chunkPos)
        );

        return CompletableFuture.supplyAsync(() -> {
            super.setRetainData(chunkPos, false);
            return chunk;
        },
            (runnable) -> this.enqueue(chunkPos.x, chunkPos.z, ServerLightingProvider.Stage.POST_UPDATE, runnable)
        );
    }

    @Shadow
    @Final
    private ThreadedAnvilChunkStorage chunkStorage;

    /**
     * @author PhiPro
     * @reason Move parts of the logic to {@link #setupLightmaps(Chunk)}
     */
    @Overwrite
    public CompletableFuture<Chunk> light(Chunk chunk, boolean excludeBlocks)
    {
        final ChunkPos chunkPos = chunk.getPos();

        this.enqueue(chunkPos.x, chunkPos.z, ServerLightingProvider.Stage.PRE_UPDATE, Util.debugRunnable(() -> {
            if (!excludeBlocks)
            {
                chunk.getLightSourcesStream().forEach((blockPos) -> {
                    super.addLightSource(blockPos, chunk.getLuminance(blockPos));
                });
            }

            if (!chunk.isLightOn())
                super.setLightEnabled(chunkPos, true);
        },
            () -> "lightChunk " + chunkPos + " " + excludeBlocks
        ));

        return CompletableFuture.supplyAsync(() -> {
            chunk.setLightOn(true);
            ((ThreadedAnvilChunkStorageAccessor) this.chunkStorage).invokeReleaseLightTicket(chunkPos);

            return chunk;
        },
            (runnable) -> this.enqueue(chunkPos.x, chunkPos.z, ServerLightingProvider.Stage.POST_UPDATE, runnable)
        );
    }
}