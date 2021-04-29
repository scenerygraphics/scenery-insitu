plugins {
    `java-library`
    kotlin("jvm") version "1.4.0"
    id("com.github.johnrengelman.shadow") version "6.1.0"
}

repositories {
    mavenCentral()
    maven("https://maven.scijava.org/content/groups/public")
    maven("https://dl.bintray.com/kotlin/kotlin-eap")
    maven("https://jitpack.io")
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.9")
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13")
    implementation("org.slf4j:slf4j-api:1.7.25")
    testImplementation("org.slf4j:slf4j-simple:1.7.25")
//    implementation("graphics.scenery:scenery:8ed735b") {
//        isTransitive = true
//    }
    api("graphics.scenery:scenery:8ed735b")
//    {
//        isTransitive = true
//    }
    implementation(platform("org.lwjgl:lwjgl-bom:3.2.3"))
    implementation("net.imglib2:imglib2:5.10.0")
    implementation("net.imglib2:imglib2-roi:0.10.3")
    implementation("org.scijava:scijava-common:2.83.0")
    implementation("net.clearvolume:cleargl:2.2.10")
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().all {
        val version = System.getProperty("java.version").substringBefore('.').toInt()
        val default = if (version == 1) "1.8" else "$version"
//        println("insitu is: $version")
        kotlinOptions {
            jvmTarget = project.properties["jvmTarget"]?.toString() ?: default
//            println(jvmTarget)
            freeCompilerArgs += listOf("-Xinline-classes", "-Xopt-in=kotlin.RequiresOptIn")
        }
        sourceCompatibility = project.properties["sourceCompatibility"]?.toString() ?: default
//        println(sourceCompatibility)
    }

    test {
        useJUnit()
    }

    shadowJar {
        isZip64 = true
    }

    task<Copy>("copyDependencies") {
        from(configurations.default).into("$buildDir/dependencies")
    }

    println(buildDir)

    task<Jar>("testJar") {
        archiveClassifier.set("tests")
        from(sourceSets.test.get().output)
        dependsOn("assemble")
    }
}
