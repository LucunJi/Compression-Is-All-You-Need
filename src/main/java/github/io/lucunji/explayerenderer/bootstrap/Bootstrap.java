package github.io.lucunji.explayerenderer.bootstrap;

import com.google.common.collect.Lists;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.examples.Expander;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.core.util.ReflectionUtil;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

public class Bootstrap implements PreLaunchEntrypoint {
    private static final String MODID = "explayerenderer";
    private static final String MIXIN_CFG = "explayerenderer.mixins.json";

    /**
     * Decompress packed resource and code, update paths, language adapters, load AW, mixin configs, transformers.
     * Entrypoints are usually fine.
     * <br/>
     * Need to take care of the things happened after calling {@link FabricLoaderImpl#load} inside {@link net.fabricmc.loader.impl.launch.knot.Knot#init}:
     * <ul>
     * <li>{@link FabricLoaderImpl#finishModLoading}</li>
     * <li>all callers of {@link ModContainer#getRootPaths}, and {@link ModContainerImpl#getCodeSourcePaths}</li>
     * <li>......</li>
     * </ul>
     */
    @Override
    public void onPreLaunch() {
        FabricLoaderImpl loader = (FabricLoaderImpl) FabricLoader.getInstance();
        if (loader.getEnvironmentType() != EnvType.CLIENT) return;
        if (loader.isDevelopmentEnvironment()) return;

        ClassLoader classLoader = FabricLauncherBase.getLauncher().getTargetClassLoader();
        ModContainerImpl mod = (ModContainerImpl) loader.getModContainer(MODID).orElseThrow();

        Expander expander = new Expander();
        File extractionDir = loader.getModsDirectory().toPath().resolve("." + MODID).toFile();
        try (GZIPInputStream gzStream = new GZIPInputStream(Objects.requireNonNull(classLoader.getResourceAsStream(MODID + ".tar.gz")));
             InputStream expandStream = new BufferedInputStream(gzStream)) {
            FileUtils.deleteDirectory(extractionDir);
            expander.expand(ArchiveStreamFactory.TAR, expandStream, extractionDir, Closeable::close);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // https://stackoverflow.com/a/35212952, too lazy to verify
        Runtime.getRuntime().addShutdownHook(new Thread(() -> FileUtils.deleteQuietly(extractionDir)));

        try {
            FabricLauncherBase.getLauncher().addToClassPath(extractionDir.toPath());
            List<Path> roots = Lists.newArrayList(mod.getRootPaths());
            roots.add(extractionDir.toPath());
            ReflectionUtil.setFieldValue(ModContainerImpl.class.getDeclaredField("roots"), mod, roots);

            Mixins.addConfiguration(MIXIN_CFG);
            IMixinConfig mixinCfg = Mixins.getConfigs().stream().filter(cfg -> cfg.getName().equals(MIXIN_CFG)).findFirst().get().getConfig();
            mixinCfg.decorate(FabricUtil.KEY_MOD_ID, MODID);
            Method getMixinCompat = Class.forName("net.fabricmc.loader.impl.launch.FabricMixinBootstrap$MixinConfigDecorator")
                    .getDeclaredMethod("getMixinCompat", ModContainerImpl.class);
            getMixinCompat.setAccessible(true);
            mixinCfg.decorate(FabricUtil.KEY_COMPATIBILITY, Objects.requireNonNull(getMixinCompat).invoke(null, mod));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
