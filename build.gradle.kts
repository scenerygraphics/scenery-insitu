plugins {
    `java-library`
    val ktVersion = "1.6.10"
    kotlin("jvm") version ktVersion
    id("com.github.johnrengelman.shadow") version "6.1.0"
}

repositories {
    mavenCentral()
    maven("https://maven.scijava.org/content/groups/public")
//    maven("https://dl.bintray.com/kotlin/kotlin-eap")
    maven("https://raw.githubusercontent.com/kotlin-graphics/mary/master")

    maven("https://jitpack.io")
}

dependencies {

    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2-native-mt")
    implementation("org.slf4j:slf4j-api:1.7.25")

    implementation("org.jetbrains.kotlin:kotlin-scripting-jsr223:1.5.31")


//    implementation("graphics.scenery:scenery:8ed735b") {
//        isTransitive = true
//    }
    api("graphics.scenery:scenery:0.7.0-beta-8-SNAPSHOT-00")
//    {
//        isTransitive = true
//    }

    implementation("org.zeromq:jeromq:0.4.3")
    implementation("org.msgpack:msgpack-core:0.9.0")
    implementation("org.msgpack:jackson-dataformat-msgpack:0.9.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.13.0")

    implementation(platform("org.lwjgl:lwjgl-bom:3.2.3"))
    implementation("net.imglib2:imglib2:5.10.0")
    implementation("net.imglib2:imglib2-roi:0.10.3")
    implementation("org.scijava:scijava-common:2.83.0")
    implementation("net.clearvolume:cleargl:2.2.10")

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13")
    testImplementation("org.slf4j:slf4j-simple:1.7.25")
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
