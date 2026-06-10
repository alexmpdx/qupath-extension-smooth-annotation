plugins {
    // Plain Java extension
    `java-library`
    // Optionally create a shadow/fat jar that bundles up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-simplify-path"
    group = "io.github.imagescientist"
    version = "0.1.0"
    description = "Inkscape-style smooth path/ROI simplification for QuPath"
    automaticModule = "io.github.imagescientist.simplifypath"
}

dependencies {
    // QuPath APIs (provided by the running QuPath instance, not bundled)
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    // Testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)
    // Gradle 9 no longer puts the JUnit Platform launcher on the test classpath automatically
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
