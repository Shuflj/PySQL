import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.shuflj"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("platformLocalPath"))
        bundledPlugins("intellij.jupyter", "com.intellij.notebooks.core", "com.intellij.database")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }
    }
    buildSearchableOptions = false

    pluginVerification {
        ides {
            local(file(providers.gradleProperty("platformLocalPath").get()))
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.runIde {
    args(layout.projectDirectory.dir("demo").asFile.absolutePath)
}

val pycharmPluginsDir = providers.gradleProperty("pycharmPluginsDir")
    .orElse(System.getProperty("user.home") + "/Library/Application Support/JetBrains/PyCharm2026.2/plugins")

tasks.register<Sync>("installPlugin") {
    group = "intellij platform"
    description = "Tests the plugin and installs it into the local PyCharm; restart PyCharm afterwards."
    dependsOn(tasks.check, tasks.verifyPluginStructure)
    from(zipTree(tasks.buildPlugin.flatMap { it.archiveFile }))
    eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
    includeEmptyDirs = false
    into(pycharmPluginsDir.map { "$it/${rootProject.name}" })
}
