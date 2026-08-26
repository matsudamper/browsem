plugins {
    id("browser-jvm-library-convention")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
}
