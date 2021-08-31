package org.embulk.plugin;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;

public class PluginClassLoaderFactoryImpl implements PluginClassLoaderFactory {
    private PluginClassLoaderFactoryImpl(
            final Collection<String> parentFirstPackages,
            final Collection<String> parentFirstResources) {
        this.parentFirstPackages = parentFirstPackages;
        this.parentFirstResources = parentFirstResources;
    }

    public static PluginClassLoaderFactoryImpl of(
            final Collection<String> parentFirstPackages,
            final Collection<String> parentFirstResources) {
        return new PluginClassLoaderFactoryImpl(parentFirstPackages, parentFirstResources);
    }

    @Override
    public PluginClassLoader create(final Collection<URL> urls, final ClassLoader parentClassLoader) {
        final PluginClassLoader created = PluginClassLoader.create(
                parentClassLoader,
                urls,
                parentFirstPackages,
                parentFirstResources);
        return created;
    }

    @Override
    public PluginClassLoader forSelfContainedPlugin(final String selfContainedPluginName, final ClassLoader parentClassLoader) {
        final PluginClassLoader created = PluginClassLoader.forSelfContainedPlugin(
                parentClassLoader,
                selfContainedPluginName,
                this.parentFirstPackages,
                this.parentFirstResources);
        return created;
    }

    @Override
    public void clear() {
        // "close()" is intentionally not called for them considering: https://bugs.openjdk.java.net/browse/JDK-8246714
        /*
        for (final PluginClassLoader pluginClassLoader : this.createdPluginClassLoaders) {
            pluginClassLoader.close();
        }
        */
    }

    private final Collection<String> parentFirstPackages;
    private final Collection<String> parentFirstResources;
}
