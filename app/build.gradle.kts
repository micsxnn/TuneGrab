plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Compilador Compose do Kotlin 2.x — liga o buildFeatures.compose.
    id("org.jetbrains.kotlin.plugin.compose")
    // @Serializable — serialização JSON usada pela camada de rede das letras.
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.tunegrab.app"
    // 34 → 37 (v0.22.16): o Backdrop 2.x e o Compose BOM 2026.06.01 do
    // LastWave exigem APIs novas. targetSdk continua 34 — comportamento
    // do app em runtime não muda.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tunegrab.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 93
        versionName = "0.22.20"

        // ABIs do motor yt-dlp embutido (python + ffmpeg são nativos).
        // x86/x86_64 ficam de fora para o APK não dobrar de tamanho —
        // aparelhos físicos são arm32/arm64.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore/tunegrab.keystore")
            storePassword = "tunegrab2025"
            keyAlias = "tunegrab"
            keyPassword = "tunegrab2025"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }


    buildFeatures {
        // Compose ligado: as bibliotecas de vidro (Backdrop/Shapes) são
        // Compose Multiplatform — sem isso elas nem empacotam direito.
        compose = true
        viewBinding = true
        // BuildConfig.VERSION_NAME na tela de boas-vindas e no rodapé do
        // Início (v0.18.8) — a versão mostrada nunca desatualiza
        buildConfig = true
    }

    packaging {
        // Exigido pelo youtubedl-android: os binários do python/ffmpeg precisam
        // ficar extraídos no disco (não dá para executar direto do APK).
        jniLibs {
            useLegacyPackaging = true
        }
        // LIXO DE PACOTE fora do APK (v0.19.14, versão final): os *.version
        // das libs (~48 arquivos de metadata), a info de VCS (textproto com o
        // hash do commit) e o app-metadata.properties do AGP — nada disso é
        // lido em runtime; é só rastro de build embutido de graça.
        resources {
            excludes += "META-INF/*.version"
            excludes += "META-INF/version-control-info.textproto"
            excludes += "META-INF/com/android/build/gradle/app-metadata.properties"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        abortOnError = false
    }
}

// DSL novo do Kotlin 2.x (o kotlinOptions antigo saiu do Kotlin 2.4).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media:media:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Tutorial de primeira abertura (v0.19.6): o pager deslizante do tour
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Player HD (v0.17.0): ExoPlayer/Media3 junta a faixa de vídeo DASH (até
    // 1080p, video-only) com a faixa de áudio no play do Início — o MediaPlayer
    // nativo só toca faixa muxada (vídeo+áudio juntos), e o YouTube não
    // entrega muxed acima de 360p/720p.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // Extração do YouTube — mesma engine usada pelo app NewPipe.
    // Fork próprio (https://github.com/micsxnn/NewPipeExtractor, tag v0.26.5-android4):
    //  1) compatibilidade Android < 13 (a v0.26.5 oficial usa URLDecoder/URLEncoder com
    //     Charset — Java 10 — e String.isBlank() — Java 11 —, que causam NoSuchMethodError
    //     em aparelhos antigos);
    //  2) clients extras de streams (TVHTML5 + visionOS + iOS) e fallback em cascata;
    //  3) suporte completo a PoTokenProvider: player request WEB com PoToken (destrava o
    //     bot-check "Sign in to confirm you're not a bot"), client iOS com PoToken e
    //     WEB+PoToken/iOS/visionOS/TV como fallback primário quando os clients anônimos falham.
    implementation("com.github.MicaelSanPedro:NewPipeExtractor:v0.26.5-android4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Motor de download yt-dlp EMBUTIDO no APK (python 3.12 + yt-dlp + ffmpeg),
    // a mesma engine usada pelo app Seal — o “plano A” do download na v0.4.0.
    // O yt-dlp é mantido semanalmente contra as mudanças do YouTube (rotação de
    // clients, desafios de JS via QuickJS embutido) e ainda é auto-atualizado
    // na primeira execução de cada versão do app (UpdateChannel.STABLE).
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    // =============================================================
    // BIBLIOTECAS DO LASTWAVE-NATIVE (Clash-Projects) — v0.22.16
    // Mesmas versões do gradle/libs.versions.toml deles. Fundação pra
    // Liquid Glass + letras de música; a UI chega nas próximas versões.
    // =============================================================

    // Stack Compose mínima que o Backdrop exige (mesma combinação do
    // LastWave: BOM 2026.06.01 + ui + ui-graphics + material3).
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // LIQUID GLASS do LastWave: Backdrop 2.0.1 (Kyant0/AndroidLiquidGlass,
    // o motor do vidro deles) + Shapes 1.2.1 (formas iOS-like arredondadas).
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")

    // Paleta: extrai a cor dominante da capa pra tingir o vidro (mesmo
    // papel que no LastWave).
    implementation("androidx.palette:palette-ktx:1.0.0")

    // InnerTubeX (MetrolistGroup) — cliente InnerTube do LastWave, o canal
    // deles pra buscar streams E LETRAS. runtimeOnly igual no LastWave: a
    // lib nasce com metadado Kotlin mais novo que o compilador — chamada via
    // adapter em runtime (ktor-client entra junto).
    runtimeOnly("com.github.MetrolistGroup.innertubex:innertubex:0.11.2")
    runtimeOnly("io.ktor:ktor-client-cio:3.6.0")
    // Engine do Ktor com o mesmo caminho de TLS do OkHttp do app: o InnerTubeX
    // fala Ktor, e o engine do CIO falha no Android quando a app tem
    // cleartext liberado no network_security_config.
    runtimeOnly("io.ktor:ktor-client-okhttp:3.6.0")

    // Camada de rede do sistema de letras (v0.22.17): Retrofit + conversor
    // kotlinx.serialization + JSON e protobuf. A versão do runtime sobe
    // pra 1.12.0 porque é a que o Kotlin 2.4.10 (compilador do app) e o
    // LastWave usam — o 1.6.3 era do Kotlin 1.9 e o plugin de serialização
    // do 2.4 exige o runtime mais novo.
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.12.0")

    // Parser HTML — raspagem das fontes de letra (Genius).
    implementation("org.jsoup:jsoup:1.18.3")
}
