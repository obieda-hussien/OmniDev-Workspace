plugins { id("com.android.library") }
android {
    namespace = "com.omnidev.system.contract"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    buildFeatures { aidl = true }
}
