import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

apply(plugin = "maven-publish")

group = "io.github.ibabyblue"
version = "0.2.0"

afterEvaluate {
    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = project.name
                pom {
                    name.set(project.name)
                    description.set("Kotlin-first Media3 audio and video playback for Android and Compose.")
                    url.set("https://github.com/ibabyblue/AMedia")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("ibabyblue")
                            name.set("ibabyblue")
                            email.set("ibabyblue_z@icloud.com")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/ibabyblue/AMedia.git")
                        developerConnection.set("scm:git:ssh://github.com/ibabyblue/AMedia.git")
                        url.set("https://github.com/ibabyblue/AMedia")
                    }
                }
            }
        }
    }
}
