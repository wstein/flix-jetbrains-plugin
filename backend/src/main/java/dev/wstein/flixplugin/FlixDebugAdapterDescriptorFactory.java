package dev.wstein.flixplugin;

import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor;
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptorFactory;
import org.jetbrains.annotations.NotNull;

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
}
