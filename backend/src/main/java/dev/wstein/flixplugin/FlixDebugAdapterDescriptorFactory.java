package dev.wstein.flixplugin;

import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor;
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptorFactory;
import com.redhat.devtools.lsp4ij.templates.ServerMappingSettings;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class FlixDebugAdapterDescriptorFactory extends DebugAdapterDescriptorFactory {

    // NOTE: the real base class (verified against LSP4IJ 0.20.1's actual compiled API, not just
    // its docs) declares this as RunConfigurationOptions, not the more specific
    // DAPRunConfigurationOptions the developer guide's own sample uses -- that sample doesn't
    // compile against 0.20.1 as written.
    @Override
    public DebugAdapterDescriptor createDebugAdapterDescriptor(@NotNull RunConfigurationOptions options,
                                                                @NotNull ExecutionEnvironment environment) {
        return new FlixDebugAdapterDescriptor(options, environment, getServerDefinition());
    }

    /**
     * Called by LSP4IJ's built-in DAPRunConfigurationProvider (a LazyRunConfigurationProducer) when
     * a .flix file is recognized via the second fileNamePatternMapping in
     * flix.jetbrains.plugin.backend.xml -- i.e. this is what "click Debug on this file" actually
     * runs, for a fresh run configuration with nothing configured yet. The base implementation
     * (decompiled: LSP4IJ 0.20.1 ships no sources jar) sets file/name/debugMode(LAUNCH)/serverId
     * but not serverMappings, so without this override the auto-created configuration would still
     * fail every breakpoint with "not supported" -- exactly like a manually-created one does before
     * its own Mappings tab is filled in by hand (see FlixDebugAdapterDescriptor#isDebuggableFile).
     * This fills in the same *.flix mapping automatically instead, closing that gap.
     */
    @Override
    public boolean prepareConfiguration(@NotNull RunConfiguration configuration,
                                        @NotNull VirtualFile file,
                                        @NotNull Project project) {
        boolean prepared = super.prepareConfiguration(configuration, file, project);
        if (prepared && configuration instanceof DAPRunConfiguration dapConfiguration) {
            dapConfiguration.setServerMappings(List.of(
                    ServerMappingSettings.createFileNamePatternsMappingSettings(List.of("*.flix"), "flix")));
        }
        return prepared;
    }
}
