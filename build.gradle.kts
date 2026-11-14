// R8 explícito: o AGP 9.1.1 (necessário pro Backdrop 2.x do LastWave)
// pede um R8 mais novo que o embutido — mesma receita do LastWave-Native.
buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://storage.googleapis.com/r8-releases/raw") }
    }
    dependencies {
        classpath("com.android.tools:r8:9.4.14")
    }
}

plugins {
    // v0.22.16: AGP 8.5.2 → 9.1.1 e Kotlin 1.9.24 → 2.4.10 — a dupla exata
    // do LastWave-Native, exigida pelas bibliotecas que entraram no app
    // (Backdrop 2.0.1 é Compose Multiplatform com stdlib 2.4.10).
    id("com.android.application") version "9.1.1" apply false
    // 2.4.21 closes unsafe build-cache deserialization (GHSA-r937-wjx7-w2jp).
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}
