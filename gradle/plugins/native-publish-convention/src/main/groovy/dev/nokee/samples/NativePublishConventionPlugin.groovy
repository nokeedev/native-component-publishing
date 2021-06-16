package dev.nokee.samples

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.XmlProvider
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
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip

import javax.inject.Inject

abstract class NativePublishConventionPlugin implements Plugin<Project> {
	@Override
	void apply(Project project) {
		/*native-component*/def component = extension(project)
		SoftwareComponentContainer components = project.getComponents()

		project.afterEvaluate {
			def generateTask = registerComponentPublication(project)
			for (def variant : component.variants.get()) {
				// Create variant aware component for publishing
				AdhocComponentWithVariants adhocComponent = softwareComponentFactory.adhoc(variant.identifier.name)

				// Query configurations of the variant
				def apiElements = configurations.findByName("${variant.identifier.name}ApiElements".uncapitalize())
				if (apiElements != null) {
					apiElements.outgoing.artifacts*.type = 'directory' // force current artifact as directory (will be the default soon)
					// Register archive of the headers (there is a limitation when sources/headers are mixed, will be fixed soon)
					apiElements.outgoing.variants.create('zip') { ConfigurationVariant v ->
						def zipTask = tasks.register("zip${apiElements.name.capitalize()}", Zip) { task ->
							task.from({ apiElements.outgoing.artifacts*.file })
							task.destinationDirectory = layout.buildDirectory.dir("tmp/${task.name}")
							task.archiveBaseName = 'headers'
							task.archiveExtension = 'zip'
						}
						v.artifact(zipTask.flatMap { it.archiveFile }) { it.type = 'zip' }
					}

					adhocComponent.addVariantsFromConfiguration(apiElements, skipIf(hasUnpublishableArtifactType()))
				}

				def linkElements = configurations.findByName("${variant.identifier.name}LinkElements".uncapitalize())
				if (linkElements != null) {
					adhocComponent.addVariantsFromConfiguration(linkElements) {}
				}

				def runtimeElements = configurations.getByName("${variant.identifier.name}RuntimeElements".uncapitalize())
				adhocComponent.addVariantsFromConfiguration(runtimeElements) {}

				components.add(adhocComponent)

				// Creates publication automatically and attach to main publication
				project.pluginManager.withPlugin('maven-publish') {
					def publication = project.extensions.getByType(PublishingExtension).publications.create(variant.identifier.name, MavenPublication)
					generateTask.configure { task -> task.from(publication) }
					publication.artifactId = "${project.name}-${publication.name}"
					publication.alias = true
					publication.from(adhocComponent)
				}
			}
		}
	}

	/*native-component*/static def extension(Project project) {
		return Optional.ofNullable(project.extensions.findByName('library')).orElseGet { project.extensions.findByName('application') }
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

	private static Action<XmlProvider> withGradleModuleMetadataRedirectComment() {
		return { XmlProvider xml ->
			def idx = xml.asString().indexOf("  <modelVersion>")
			xml.asString().insert(idx, '''<!-- This module was also published with a richer model, Gradle metadata,  -->
				|  <!-- which should be used instead. Do not delete the following line which  -->
				|  <!-- is to indicate to Gradle or any Gradle module metadata file consumer  -->
				|  <!-- that they should prefer consuming it instead. -->
				|  <!-- do_not_remove: published-with-gradle-metadata -->
				|'''.stripMargin())
		}
	}

	private static TaskProvider<GenerateComponentGradleModuleMetadata> registerComponentPublication(Project project) {
		/*native-component*/def component = extension(project)
		def generateTask = project.tasks.register('generateGradleModuleMetadata', GenerateComponentGradleModuleMetadata) { task ->
			task.variants.set(component.variants.elements)
			task.moduleFile.convention(project.layout.buildDirectory.file('main.module'))
		}
		project.pluginManager.withPlugin('maven-publish') {
			// We are publishing a custom publication with a self-generated Gradle Module Metadata.
			//   The alternative is to use internal APIs which is very inconvenient (and breaks easily).
			//   The self-generated Gradle Module Metadata follows the official specs and allow use to use `available-at` remote variant feature.
			def publication = project.extensions.getByType(PublishingExtension).publications.create("main", MavenPublication)
			publication.artifact(generateTask)
			publication.pom.packaging = 'pom' // force pom
			publication.pom.withXml(withGradleModuleMetadataRedirectComment()) // force module redirection comment
			generateTask.configure { task ->
				task.groupId.set(project.providers.provider { publication.groupId })
				task.artifactId.set(project.providers.provider { publication.artifactId })
				task.version.set(project.providers.provider { publication.version })
				task.moduleFile.set(project.layout.buildDirectory.file("${publication.artifactId}.module"))
			}
		}

		return generateTask
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
