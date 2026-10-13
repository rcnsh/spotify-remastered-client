# App Remote maps JSON onto these with Gson reflection.
-keep class com.spotify.protocol.types.** { *; }
-keep class com.spotify.protocol.** { *; }
-keep class com.spotify.android.appremote.** { *; }
-dontwarn com.spotify.base.annotations.**

# App Remote references Jackson annotations it never needs at runtime (it maps with Gson).
-dontwarn com.fasterxml.jackson.**
