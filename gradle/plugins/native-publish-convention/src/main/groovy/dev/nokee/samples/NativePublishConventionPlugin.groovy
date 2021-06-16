package dev.nokee.samples

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ConfigurationVariant
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.component.ConfigurationVariantDetails
import org.gradle.api.component.SoftwareComponentContainer
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.bundling.Zip

import javax.inject.Inject

abstract class NativePublishConventionPlugin implements Plugin<Project> {
	@Override
	void apply(Project project) {
		/*native-component*/def component = extension(project)
		SoftwareComponentContainer components = project.getComponents()

		project.afterEvaluate {
			def generateTask = tasks.register('generateGradleModuleMetadata', GenerateComponentGradleModuleMetadata) { task ->
				task.variants.set(component.variants.elements)
				task.moduleFile.convention(layout.buildDirectory.file('main.module'))
			}
			project.pluginManager.withPlugin('maven-publish') {
				def publication = project.extensions.getByType(PublishingExtension).publications.create("main", MavenPublication)
				publication.artifact(generateTask)
				generateTask.configure { task ->
					task.groupId.set(providers.provider { publication.groupId })
					task.artifactId.set(providers.provider { publication.artifactId })
					task.version.set(providers.provider { publication.version })
					task.moduleFile.set(layout.buildDirectory.file("${publication.artifactId}.module"))
				}
			}

			for (def variant : component.variants.get()) {
				AdhocComponentWithVariants adhocComponent = softwareComponentFactory.adhoc(variant.identifier.name)

				def apiElements = configurations.getByName("${variant.identifier.name}ApiElements".uncapitalize()) { Configuration configuration ->
					configuration.outgoing.artifacts*.type = 'directory'
					configuration.outgoing.variants.create('zip') { ConfigurationVariant v ->
						def zipTask = tasks.register("zip${configuration.name.capitalize()}", Zip) { task ->
							task.from({ configuration.outgoing.artifacts*.file })
							task.destinationDirectory = layout.buildDirectory.dir("tmp/${task.name}")
							task.archiveBaseName = 'headers'
							task.archiveExtension = 'zip'
						}
						v.artifact(zipTask.flatMap { it.archiveFile }) { it.type = 'zip' }
					}
				}
				def linkElements = configurations.getByName("${variant.identifier.name}LinkElements".uncapitalize())
				def runtimeElements = configurations.getByName("${variant.identifier.name}RuntimeElements".uncapitalize())

				adhocComponent.addVariantsFromConfiguration(apiElements, skipIf(hasUnpublishableArtifactType()))
				adhocComponent.addVariantsFromConfiguration(linkElements) {}
				adhocComponent.addVariantsFromConfiguration(runtimeElements) {}

				components.add(adhocComponent)

				project.pluginManager.withPlugin('maven-publish') {
					def publication = project.extensions.getByType(PublishingExtension).publications.create(variant.identifier.name, MavenPublication)
					generateTask.configure { task -> task.from(publication) }
					publication.artifactId = "${project.name}-${publication.name}"
					publication.from(adhocComponent)
				}
			}
		}
	}

	/*native-component*/def extension(Project project) {
		return project.extensions.library
	}

	private static Action<ConfigurationVariantDetails> skipIf(Spec<? super ConfigurationVariant> predicate) {
		return { variantDetails ->
			if (predicate.isSatisfiedBy(variantDetails.getConfigurationVariant())) {
				variantDetails.skip()
			}
		}
	}

	private static Spec<ConfigurationVariant> hasUnpublishableArtifactType() {
		return { ConfigurationVariant element ->
			return element.artifacts.find { it.type == 'directory'} != null
		}
	}

	@Inject
	protected abstract SoftwareComponentFactory getSoftwareComponentFactory()

	@Inject
	abstract ProjectLayout getLayout()

	@Inject
	abstract ProviderFactory getProviders()

	@Inject
	abstract TaskContainer getTasks()

	@Inject
	abstract ConfigurationContainer getConfigurations()
}
