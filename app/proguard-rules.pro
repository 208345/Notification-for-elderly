# このアプリは難読化を行わない設定です（build.gradle.kts の isMinifyEnabled = false）。
# 将来有効にする場合に備えて、リフレクションで参照されるものだけ残します。
-keep class com.mimamori.reminder.** { *; }
