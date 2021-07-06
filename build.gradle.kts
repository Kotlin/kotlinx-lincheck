import org.gradle.jvm.tasks.Jar

// atomicfu
buildscript {
    val atomicfuVersion: String by project
    dependencies {
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:$atomicfuVersion")
    }
}
apply(plugin = "kotlinx-atomicfu")

plugins {
    java
    kotlin("multiplatform")
    id("maven-publish")
    id("maven")
    id("kotlinx.team.infra") version "0.2.0-dev-55"
}

repositories {
    mavenCentral()
    jcenter()
}

kotlin {
    jvm {
        withJava()

        val main by compilations.getting {
            kotlinOptions.jvmTarget = "1.8"
        }

        val test by compilations.getting {
            kotlinOptions.jvmTarget = "1.8"
        }
    }

    sourceSets {
        val jvmMain by getting {
            kotlin.srcDir("src/jvm/main")

            val kotlinVersion: String by project
            val kotlinxCoroutinesVersion: String by project
            val asmVersion: String by project
            val reflectionsVersion: String by project
            dependencies {
                api("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
                api("org.jetbrains.kotlin:kotlin-stdlib-common:$kotlinVersion")
                api("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
                api("org.ow2.asm:asm-commons:$asmVersion")
                api("org.ow2.asm:asm-util:$asmVersion")
                api("org.reflections:reflections:$reflectionsVersion")
            }
        }

        val jvmTest by getting {
            kotlin.srcDir("src/jvm/test")

            val junitVersion: String by project
            val jctoolsVersion: String by project
            dependencies {
                implementation("junit:junit:$junitVersion")
                implementation("org.jctools:jctools-core:$jctoolsVersion")
                implementation("org.apache.commons:commons-math3:3.5")
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

sourceSets.main {
    java.srcDirs("src/jvm/main")
}

sourceSets.test {
    java.srcDirs("src/jvm/test")
}

tasks {
    // empty xxx-javadoc.jar
    register<Jar>("javadocJar") {
        archiveClassifier.set("javadoc")
    }

    withType<Test> {
        maxParallelForks = 1
        maxHeapSize = "4g"
        jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports", "java.base/jdk.internal.util=ALL-UNNAMED")
    }

    withType<Jar> {
        manifest {
            val inceptionYear: String by project
            val lastCopyrightYear: String by project
            attributes("Copyright" to
                "Copyright (C) 2015 - 2019 Devexperts, LLC\n                                " +
                "Copyright (C) $inceptionYear - $lastCopyrightYear JetBrains, s.r.o."
            )
        }
    }
}

publishing {
    publications.withType<MavenPublication> {
        // add empty javadoc
        if (name == "jvm") {
            artifact(tasks.getByName("javadocJar"))
        }
        mavenCentralMetadata()
    }
    publications {
        mavenCentralMetadata()
    }
}

fun PublishingExtension.mavenCentralMetadata() {
    publications.withType(MavenPublication::class) {
        pom {
            if (!name.isPresent) {
                name.set(artifactId)
            }
            description.set("Lincheck - Framework for testing concurrent data structures")
            url.set("https://github.com/Kotlin/kotlinx-lincheck")
            licenses {
                license {
                    name.set("GNU Lesser General Public License v3.0")
                    url.set("https://www.gnu.org/licenses/lgpl-3.0.en.html")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("JetBrains")
                    name.set("JetBrains Team")
                    organization.set("JetBrains")
                    organizationUrl.set("https://www.jetbrains.com")
                }
            }
            scm {
                url.set("https://github.com/Kotlin/kotlinx-lincheck")
            }
        }
    }
}

infra {
    teamcity {
        bintrayUser = "%env.BINTRAY_USER%"
        bintrayToken = "%env.BINTRAY_API_KEY%"
    }
    publishing {
        include(":")

        bintray {
            organization = "kotlin"
            repository = "kotlinx"
            library = "kotlinx.lincheck"
            username = findProperty("bintrayUser") as String?
            password = findProperty("bintrayApiKey") as String?
        }
    }
}