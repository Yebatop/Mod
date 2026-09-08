plugins {
    // Версию плагина здесь можно задать только литералом: в блоке plugins {}
    // получатель — PluginDependenciesSpec, и property() резолвится в
    // ObjectFactory.property, что даёт ошибку несовпадения получателя.
    id("fabric-loom") version "1.17.20"
    `java-library`
}

version = "${property("mod_version")}"
group = "${property("maven_group")}"

base { archivesName.set("${property("archives_base_name")}") }

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    // Gradle 9 больше не подкладывает лаунчер JUnit Platform в classpath тестов сам:
    // без этой строки задача :test падает ещё до первого теста с «Failed to load
    // JUnit Platform». Версию берёт BOM выше.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test { useJUnitPlatform() }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
