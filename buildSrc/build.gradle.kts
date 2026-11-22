plugins {
    kotlin("jvm") version "2.4.21"
}

repositories {
    // Fork: aliyun mirror first, upstream hosts kept as fallback (repo.maven.apache.org
    // handshakes get terminated on this network).
    maven("https://maven.aliyun.com/repository/public")
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())
    implementation("org.apache.xmlgraphics:batik-all:1.20")
}
