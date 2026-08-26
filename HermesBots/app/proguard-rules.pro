# Hermes Bots — reguły R8/proguard (release)

# OkHttp / Okio — uniknij ostrzeżeń o brakujących klasach platformowych
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Firebase Messaging — usługi są wołane z manifestu przez refleksję
-keep class eu.draconest.hermesbots.data.HermesMessagingService { *; }

# JSON-RPC przez org.json (android built-in) — bez refleksji, nic nie trzeba

# Compose — standardowo nie wymaga keep, ale zachowaj composable metadata dla toolingu
