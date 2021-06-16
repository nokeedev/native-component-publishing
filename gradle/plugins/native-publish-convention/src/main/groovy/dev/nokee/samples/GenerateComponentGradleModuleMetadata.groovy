package dev.nokee.samples

import dev.gradleplugins.grava.publish.metadata.GradleModuleMetadata
import dev.gradleplugins.grava.publish.metadata.GradleModuleMetadataWriter
import dev.nokee.platform.base.Variant
import org.gradle.api.DefaultTask
import org.gradle.api.Named
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.attributes.Attribute
import org.gradle.api.capabilities.Capability
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import javax.inject.Inject
import java.util.function.Consumer;

abstract class GenerateComponentGradleModuleMetadata extends DefaultTask {
    private final List<MavenPublication> publications = new ArrayList<>()

    @Internal
    abstract ListProperty<Variant> getVariants()

    @OutputFile
    abstract RegularFileProperty getModuleFile()

    @Internal
    abstract Property<String> getGroupId();

    @Internal
    abstract Property<String> getArtifactId();

    @Internal
    abstract Property<String> getVersion();

    @Inject
    protected abstract ConfigurationContainer getConfigurations()

    GenerateComponentGradleModuleMetadata() {
        outputs.upToDateWhen { false }
    }

    GenerateComponentGradleModuleMetadata from(MavenPublication... publications) {
        for (MavenPublication publication : publications) {
            this.publications.add(publication)
        }
        return this
    }

    @TaskAction
    private void doGenerate() {
        def moduleBuilder = GradleModuleMetadata.builder().formatVersion('1.1').component(GradleModuleMetadata.Component.ofComponent(groupId.get(), artifactId.get(), version.get()))
        for (def publication : publications) {
            def variant = findVariant(publication.name).orElseThrow { new RuntimeException("No variant for publication '${publication.name}'") }
            Optional.ofNullable(configurations.findByName("${variant.identifier.name}ApiElements".uncapitalize())).ifPresent { apiElements ->
                moduleBuilder.remoteVariant(variantBuilder(apiElements).andThen(availableAt(publication)))
            }

            Optional.ofNullable(configurations.findByName("${variant.identifier.name}LinkElements".uncapitalize())).ifPresent { linkElements ->
                moduleBuilder.remoteVariant(variantBuilder(linkElements).andThen(availableAt(publication)))
            }

            def runtimeElements = configurations.getByName("${variant.identifier.name}RuntimeElements".uncapitalize())
            moduleBuilder.remoteVariant(variantBuilder(runtimeElements).andThen(availableAt(publication)))
        }
        GradleModuleMetadata.withWriter(moduleFile.get().asFile) { GradleModuleMetadataWriter out ->
            out.write(moduleBuilder.build())
        }
    }

    private Optional<Variant> findVariant(String name) {
        return Optional.ofNullable(variants.get().find { it.identifier.name == name })
    }

    private static Consumer<GradleModuleMetadata.RemoteVariant.Builder> variantBuilder(Configuration configuration) {
        return { GradleModuleMetadata.RemoteVariant.Builder builder ->
            builder.name(configuration.name)
            configuration.attributes.keySet().each { Attribute key ->
                def value = configuration.attributes.getAttribute(key)
                if (value instanceof Named) {
                    value = value.name
                } else if (value instanceof Enum) {
                    value = value.toString()
                }
                builder.attribute(GradleModuleMetadata.Attribute.ofAttribute(key.name, value))
            }
            configuration.outgoing.capabilities.each { Capability capability ->
                builder.capability(GradleModuleMetadata.Capability.ofCapability(capability.group, capability.name, capability.version))
            }
        }
    }

    private static Consumer<GradleModuleMetadata.RemoteVariant.Builder> availableAt(MavenPublication publication) {
        return { GradleModuleMetadata.RemoteVariant.Builder builder ->
            builder.availableAt {it ->
                it.group(publication.groupId).module(publication.artifactId).version(publication.version)
                    .url("../../${publication.artifactId}/${publication.version}/${publication.artifactId}-${publication.version}.module")
            }
//            builder.dependency { depBuilder ->
//                depBuilder.version(GradleModuleMetadata.Version.strictly(publication.version))
//                        .name(publication.artifactId)
//                        .group(publication.groupId)
//            }
        }
    }
//    private static Consumer<GradleModuleMetadata.LocalVariant.Builder> variantBuilder(Configuration configuration) {
//        return { GradleModuleMetadata.LocalVariant.Builder builder ->
//            builder.name(configuration.name)
//            configuration.attributes.keySet().each { Attribute key ->
//                def value = configuration.attributes.getAttribute(key)
//                if (value instanceof Named) {
//                    value = value.name
//                } else if (value instanceof Enum) {
//                    value = value.toString()
//                }
//                builder.attribute(GradleModuleMetadata.Attribute.ofAttribute(key.name, value))
//            }
//            configuration.outgoing.capabilities.each { Capability capability ->
//                builder.capability(GradleModuleMetadata.Capability.ofCapability(capability.group, capability.name, capability.version))
//            }
//        }
//    }
//
//    private static Consumer<GradleModuleMetadata.LocalVariant.Builder> attachDependency(MavenPublication publication) {
//        return { GradleModuleMetadata.LocalVariant.Builder builder ->
//            builder.dependency { depBuilder ->
//                depBuilder.version(GradleModuleMetadata.Version.strictly(publication.version))
//                        .name(publication.artifactId)
//                        .group(publication.groupId)
//            }
//        }
//    }
}
