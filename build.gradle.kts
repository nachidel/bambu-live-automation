plugins {
    kotlin("jvm") version "2.3.20"
    application
}

group = "com.nachidel"
version = "0.1.0"

tasks.named<JavaExec>("run") {
    standardInput = System.`in`

    val bambuEmail = providers.gradleProperty("bambu.email")
    val bambuPassword = providers.gradleProperty("bambu.password")

    environment(
        "BAMBU_EMAIL",
        bambuEmail.getOrElse("")
    )

    environment(
        "BAMBU_PASSWORD",
        bambuPassword.getOrElse("")
    )

    environment(
        "BAMBU_TOKEN",
        providers.gradleProperty("bambu.token").getOrElse("")
    )
}


repositories {
    mavenCentral()

    maven {
        name = "GitHubPackages"

        url = uri("https://maven.pkg.github.com/nachidel/bambu-cloud-kotlin")

        credentials {
            username =
                findProperty("gpr.user") as String?
                    ?: System.getenv("GITHUB_ACTOR")

            password =
                findProperty("gpr.key") as String?
                    ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("com.nachidel:bambu-cloud-kotlin:0.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.nachidel.bambu.live.MainKt")
}