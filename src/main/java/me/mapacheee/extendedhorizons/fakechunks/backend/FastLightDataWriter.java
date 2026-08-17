package me.mapacheee.extendedhorizons.fakechunks.backend;

import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import me.mapacheee.extendedhorizons.fakechunks.antixray.VarIntUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class FastLightDataWriter {

    private static final int NO_SKY_HEADER_BYTES = 3;
    private static final int EXTRA_LIGHT_SECTIONS = 2;
    private static final int FULL_BRIGHT_ARRAY_BYTES = 2048;
    private static final byte[] FULL_BRIGHT;
    static {
        FULL_BRIGHT = new byte[FULL_BRIGHT_ARRAY_BYTES];
        Arrays.fill(FULL_BRIGHT, (byte) 0xFF);
    }

    private static final Map<Integer, byte[]> SYNTHETIC_LIGHT_CACHE = new ConcurrentHashMap<>();

    private static final MethodHandle GET_STORAGE_VISIBLE = createStorageVisibleHandle();

    private static MethodHandle createStorageVisibleHandle() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(SWMRNibbleArray.class, MethodHandles.lookup());
            return lookup.findGetter(SWMRNibbleArray.class, "storageVisible", byte[].class);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException("Unable to access SWMRNibbleArray.storageVisible", exception);
        }
    }

    private FastLightDataWriter() {
    }

    static PreparedLight prepareLightData(LevelChunk chunk) {
        byte[][] blockLight = java.util.Objects.requireNonNull(convertStarlightToBytes(chunk.starlight$getBlockNibbles(), false));
        byte[][] skyLight = convertStarlightToBytes(chunk.starlight$getSkyNibbles(), true);

        if (skyLight == null) {
            List<byte[]> blockData = new ArrayList<>(blockLight.length);
            NoSkyMasks masks = buildNoSkyMasks(blockLight, blockData);
            long[] notBlockEmpty = masks.notBlockEmpty().toLongArray();
            long[] blockEmpty = masks.blockEmpty().toLongArray();
            int size = NO_SKY_HEADER_BYTES
                + estimateBitSet(notBlockEmpty)
                + estimateBitSet(blockEmpty)
                + estimateByteArrayList(blockData);
            return new PreparedLight(false, null, notBlockEmpty, null, blockEmpty, null, blockData, size);
        }

        LightMasks masks = buildMasks(blockLight, skyLight);
        long[] notSkyEmpty = masks.notSkyEmpty().toLongArray();
        long[] notBlockEmpty = masks.notBlockEmpty().toLongArray();
        long[] skyEmpty = masks.skyEmpty().toLongArray();
        long[] blockEmpty = masks.blockEmpty().toLongArray();
        int size = estimateBitSet(notSkyEmpty)
            + estimateBitSet(notBlockEmpty)
            + estimateBitSet(skyEmpty)
            + estimateBitSet(blockEmpty)
            + estimateByteArrayList(masks.skyData())
            + estimateByteArrayList(masks.blockData());
        return new PreparedLight(true, notSkyEmpty, notBlockEmpty, skyEmpty, blockEmpty, masks.skyData(), masks.blockData(), size);
    }

    static void writeLightData(FriendlyByteBuf out, PreparedLight prepared) {
        if (!prepared.hasSky()) {
            out.writeByte(0);
            writeBitSet(out, prepared.notBlockEmpty());
            out.writeByte(0);
            writeBitSet(out, prepared.blockEmpty());
            out.writeByte(0);
            writeByteArrayList(out, prepared.blockData());
            return;
        }

        writeBitSet(out, prepared.notSkyEmpty());
        writeBitSet(out, prepared.notBlockEmpty());
        writeBitSet(out, prepared.skyEmpty());
        writeBitSet(out, prepared.blockEmpty());
        writeByteArrayList(out, prepared.skyData());
        writeByteArrayList(out, prepared.blockData());
    }

    static boolean hasInitialisedLight(LevelChunk chunk) {
        SWMRNibbleArray[] blockNibbles = chunk.starlight$getBlockNibbles();
        if (blockNibbles != null) {
            for (SWMRNibbleArray layer : blockNibbles) {
                if (layer != null && layer.isInitialisedVisible()) {
                    return true;
                }
            }
        }
        SWMRNibbleArray[] skyNibbles = chunk.starlight$getSkyNibbles();
        if (skyNibbles != null) {
            for (SWMRNibbleArray layer : skyNibbles) {
                if (layer != null && layer.isInitialisedVisible()) {
                    return true;
                }
            }
        }
        return false;
    }

    static void writeSyntheticFullBrightLight(FriendlyByteBuf out, LevelChunk chunk) {
        SWMRNibbleArray[] blockNibbles = chunk.starlight$getBlockNibbles();
        boolean hasSky = chunk.starlight$getSkyNibbles() != null;
        int sectionCount = blockNibbles != null ? blockNibbles.length : chunk.getSectionsCount() + EXTRA_LIGHT_SECTIONS;

        int cacheKey = (sectionCount << 1) | (hasSky ? 1 : 0);
        byte[] payload = SYNTHETIC_LIGHT_CACHE.computeIfAbsent(cacheKey, key -> buildSyntheticLightPayload(sectionCount, hasSky));
        out.writeBytes(payload);
    }

    private static byte[] buildSyntheticLightPayload(int sectionCount, boolean hasSky) {
        ByteBuf scratch = Unpooled.buffer(sectionCount * (FULL_BRIGHT_ARRAY_BYTES + 3) + 64);
        try {
            if (hasSky) {
                BitSet notSkyEmpty = new BitSet(sectionCount);
                notSkyEmpty.set(0, sectionCount);
                BitSet notBlockEmpty = new BitSet(sectionCount);
                BitSet skyEmpty = new BitSet(sectionCount);
                BitSet blockEmpty = new BitSet(sectionCount);
                blockEmpty.set(0, sectionCount);

                writeBitSet(scratch, notSkyEmpty.toLongArray());
                writeBitSet(scratch, notBlockEmpty.toLongArray());
                writeBitSet(scratch, skyEmpty.toLongArray());
                writeBitSet(scratch, blockEmpty.toLongArray());

                VarIntUtil.writeVarInt(scratch, sectionCount);
                for (int i = 0; i < sectionCount; i++) {
                    VarIntUtil.writeVarInt(scratch, FULL_BRIGHT_ARRAY_BYTES);
                    scratch.writeBytes(FULL_BRIGHT);
                }
                scratch.writeByte(0);
            } else {
                BitSet notSkyEmpty = new BitSet(sectionCount);
                BitSet notBlockEmpty = new BitSet(sectionCount);
                notBlockEmpty.set(0, sectionCount);
                BitSet skyEmpty = new BitSet(sectionCount);
                skyEmpty.set(0, sectionCount);
                BitSet blockEmpty = new BitSet(sectionCount);

                writeBitSet(scratch, notSkyEmpty.toLongArray());
                writeBitSet(scratch, notBlockEmpty.toLongArray());
                writeBitSet(scratch, skyEmpty.toLongArray());
                writeBitSet(scratch, blockEmpty.toLongArray());

                scratch.writeByte(0);
                VarIntUtil.writeVarInt(scratch, sectionCount);
                for (int i = 0; i < sectionCount; i++) {
                    VarIntUtil.writeVarInt(scratch, FULL_BRIGHT_ARRAY_BYTES);
                    scratch.writeBytes(FULL_BRIGHT);
                }
            }

            byte[] payload = new byte[scratch.readableBytes()];
            scratch.readBytes(payload);
            return payload;
        } finally {
            scratch.release();
        }
    }

    private static byte[][] convertStarlightToBytes(SWMRNibbleArray[] layers, boolean allowEmpty) {
        try {
            int layerCount = layers.length;
            byte[][] byteLayers = new byte[layerCount][];
            boolean converted = false;
            for (int i = 0; i < layerCount; i++) {
                SWMRNibbleArray layer = layers[i];
                if (layer != null && layer.isInitialisedVisible()) {
                    byteLayers[i] = (byte[]) GET_STORAGE_VISIBLE.invoke(layer);
                    converted = true;
                }
            }
            return converted || !allowEmpty ? byteLayers : null;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to convert starlight nibble arrays", throwable);
        }
    }

    private static NoSkyMasks buildNoSkyMasks(byte[][] blockLight, List<byte[]> blockDataOut) {
        int sectionCount = blockLight.length;
        BitSet notBlockEmpty = new BitSet(sectionCount);
        BitSet blockEmpty = new BitSet(sectionCount);

        for (int indexY = 0; indexY < sectionCount; indexY++) {
            byte[] block = blockLight[indexY];
            if (block == null) {
                blockEmpty.set(indexY);
                continue;
            }
            notBlockEmpty.set(indexY);
            blockDataOut.add(block);
        }

        return new NoSkyMasks(notBlockEmpty, blockEmpty);
    }

    private static LightMasks buildMasks(byte[][] blockLight, byte[][] skyLight) {
        int sectionCount = blockLight.length;
        List<byte[]> skyData = new ArrayList<>(sectionCount);
        BitSet notSkyEmpty = new BitSet(sectionCount);
        BitSet skyEmpty = new BitSet(sectionCount);

        List<byte[]> blockData = new ArrayList<>(sectionCount);
        BitSet notBlockEmpty = new BitSet(sectionCount);
        BitSet blockEmpty = new BitSet(sectionCount);

        for (int indexY = 0; indexY < sectionCount; indexY++) {
            byte[] sky = skyLight[indexY];
            if (sky == null) {
                skyEmpty.set(indexY);
            } else {
                notSkyEmpty.set(indexY);
                skyData.add(sky);
            }
            byte[] block = blockLight[indexY];
            if (block == null) {
                blockEmpty.set(indexY);
            } else {
                notBlockEmpty.set(indexY);
                blockData.add(block);
            }
        }

        return new LightMasks(skyData, notSkyEmpty, skyEmpty, blockData, notBlockEmpty, blockEmpty);
    }

    private static void writeBitSet(ByteBuf out, long[] set) {
        VarIntUtil.writeVarInt(out, set.length);
        for (long value : set) {
            out.writeLong(value);
        }
    }

    private static int estimateBitSet(long[] set) {
        return varIntSize(set.length) + (set.length * Long.BYTES);
    }

    private static void writeByteArrayList(ByteBuf out, List<byte[]> list) {
        int len = list.size();
        if (len == 0) {
            out.writeByte(0);
            return;
        }
        VarIntUtil.writeVarInt(out, len);
        for (byte[] bytes : list) {
            FriendlyByteBuf.writeByteArray(out, bytes);
        }
    }

    private static int estimateByteArrayList(List<byte[]> list) {
        int len = list.size();
        int size = varIntSize(len);
        for (byte[] bytes : list) {
            size += varIntSize(bytes.length) + bytes.length;
        }
        return size;
    }

    private static int varIntSize(int value) {
        if ((value & (0xFFFFFFFF << 7)) == 0) {
            return 1;
        }
        if ((value & (0xFFFFFFFF << 14)) == 0) {
            return 2;
        }
        if ((value & (0xFFFFFFFF << 21)) == 0) {
            return 3;
        }
        if ((value & (0xFFFFFFFF << 28)) == 0) {
            return 4;
        }
        return 5;
    }

    record PreparedLight(
        boolean hasSky,
        long[] notSkyEmpty,
        long[] notBlockEmpty,
        long[] skyEmpty,
        long[] blockEmpty,
        List<byte[]> skyData,
        List<byte[]> blockData,
        int size
    ) {}

    private record LightMasks(
        List<byte[]> skyData,
        BitSet notSkyEmpty,
        BitSet skyEmpty,
        List<byte[]> blockData,
        BitSet notBlockEmpty,
        BitSet blockEmpty
    ) {}

    private record NoSkyMasks(
        BitSet notBlockEmpty,
        BitSet blockEmpty
    ) {}
}
