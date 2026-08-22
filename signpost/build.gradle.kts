plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

/**
 * signpost — 道沿いヒント（wayside tips）の汎用 Compose ライブラリ。
 *
 * ⭐⭐ **売りは部品ではなく契約**——「ヒントが出ている間は、それが指すもの以外へ進めない」。
 * 幕（スクリム）で周囲を覆い、遮ることそのものを部品側が引き受ける。
 *
 * ⚠️ **アプリの都合を一切持ち込まない。** 依存は Compose だけで、DI も coroutines も要求しない
 * （Hilt に依存しない／変更通知は素のリスナー）。利用者に構成を強制しないことを、
 * 依存の少なさで担保している。
 *
 * ⚠️ **スコープは「道沿いに1枚ずつ」に絞る。** 出す順番を持った固定ツアーは作らない。
 */
android {
    namespace = "com.bathtimefish.signpost"
    compileSdk = 36

    defaultConfig {
        // ⚠️ すりガラス（BlurEffect / RenderEffect）が API 31 以降。33 は実機で通した下限で、
        // 下げること自体は可能だが**ぼかしの経路に分岐が要る**ので、下げるなら実機確認とセット。
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // ⚠️ **api で公開する**。SignpostHost / Modifier.tipAnchor のシグネチャに Compose の型が
    // 出るので、implementation にすると利用者側でコンパイルが通らない。
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose) // BackHandler

    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            // ⚠️ **バージョンは project.version から取る。** JitPack はタグを
            // `-Pversion=<tag>` で渡してくるので、ここで固定値を書くと
            // **タグと中身のバージョンが食い違う**（v0.2.0 を取ったのに 0.1.0 が入る）。
            groupId = project.group.toString()
            artifactId = "sisa-signpost-ui-android"
            version = project.version.toString()

            afterEvaluate { from(components["release"]) }

            pom {
                name.set("signpost")
                description.set(
                    "Wayside tips for Jetpack Compose. The contract, not the widget: " +
                        "while a tip is showing, nothing but what it points at can be touched."
                )
                url.set("https://github.com/bathtimefish/sisa-signpost-ui-android")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("bathtimefish")
                        name.set("Masakazu Muraoka")
                        url.set("https://github.com/bathtimefish")
                    }
                }
                scm {
                    url.set("https://github.com/bathtimefish/sisa-signpost-ui-android")
                    connection.set("scm:git:https://github.com/bathtimefish/sisa-signpost-ui-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/bathtimefish/sisa-signpost-ui-android.git")
                }
            }
        }
    }
}
